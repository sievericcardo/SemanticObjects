@file:Suppress(
    "LiftReturnOrAssignment"
)

package no.uio.microobject.runtime

import com.influxdb.client.kotlin.InfluxDBClientKotlin
import com.influxdb.client.kotlin.InfluxDBClientKotlinFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import no.uio.microobject.ast.*
import no.uio.microobject.ast.expr.LiteralExpr
import no.uio.microobject.ast.stmt.ReturnStmt
import no.uio.microobject.data.TripleManager
import no.uio.microobject.main.Settings
import no.uio.microobject.type.*
import org.apache.jena.query.QueryExecution
import org.apache.jena.query.QueryExecutionFactory
import org.apache.jena.query.QueryFactory
import org.apache.jena.query.QuerySolution
import org.apache.jena.query.ResultSet
import org.semanticweb.HermiT.Reasoner
import org.semanticweb.owlapi.apibinding.OWLManager
import org.semanticweb.owlapi.manchestersyntax.parser.ManchesterOWLSyntaxParserImpl
import org.semanticweb.owlapi.model.OWLNamedIndividual
import org.semanticweb.owlapi.model.OntologyConfigurator
import org.semanticweb.owlapi.reasoner.NodeSet
import java.io.File
import java.io.FileWriter
import java.util.*
import kotlin.streams.toList

data class InfluxDBConnection(val url : String, val org : String, val token : String, val bucket : String){
    private var influxDBClient : InfluxDBClientKotlin? = null
    private fun connect(){
        influxDBClient = InfluxDBClientKotlinFactory.create(url, token.toCharArray(), org)
    }
    fun queryOneSeries(flux : String, settings: Settings) : List<Double>{
        connect()
        if(settings.verbose) println("RAN QUERY: $flux")
        val results = influxDBClient!!.getQueryKotlinApi().query(flux.replace("\\\"","\""))
        var next = emptyList<Double>()
        runBlocking {
            launch(Dispatchers.Unconfined) {
                next = results.consumeAsFlow().toList().map { it.value as Double }
            }
        }
        disconnect()
        return next
    }
    private fun disconnect(){
        influxDBClient?.close()
    }
}

data class EvalResult(val current: StackEntry?, val spawns: List<StackEntry>, val debug : Boolean = false)

@Suppress("unused")
class Interpreter(
    val stack: Stack<StackEntry>,               // This is the process stack
    var heap: GlobalMemory,             // This is a map from objects to their heap memory
    var simMemory: SimulationMemory,    // This is a map from simulation objects to their handler
    val staticInfo: StaticTable,                // Class table etc.
    val settings : Settings                    // Settings from the user
) {

    // TripleManager used to provide virtual triples etc.
    val tripleManager : TripleManager = TripleManager(settings, staticInfo, this)

    /**
     * Evaluates a call on a method of a class
     *
     * This method is used to evaluate a call on a method of a class. It constructs the initial state, adds the
     * parameters to the memory, and runs the interpreter until the return value is reached.
     * Must ONLY be called if nm is checked to have no side-effects (i.e., is rule)
     *
     * @param objName the name of the object
     * @param className the name of the class
     * @param metName the name of the method
     * @param params the parameters of the method. It must be structured as "paramName" to "paramValue" as a LiteralExpr as a map
     * @return a pair of the created object and the return value
     * @throws Exception if an error occurs during the generation of the builtin
     */
    fun evalCall(objName: String, className: String, metName: String, params: Map<String, LiteralExpr> = mapOf()): Pair<LiteralExpr, LiteralExpr> {
        //Construct initial state
        val classStmt =
            staticInfo.methodTable[className]
                ?: throw Exception("Error during builtin generation")
        val met = classStmt[metName] ?: throw Exception("Error during builtin generation")
        val mem: Memory = mutableMapOf()

        val obj = LiteralExpr(
            objName,
            heap.keys.first { it.literal == objName }.tag //retrieve real class, because rule methods can be inheritated
        )
        mem["this"] = obj

        // Add parameters to memory
        if (params.isNotEmpty()) {
            for ((paramName, paramExpr) in params) {
                mem[paramName] = paramExpr
            }
        }

        val myId = Names.getStackId()
        val se = StackEntry(met.stmt, mem, obj, myId)
        stack.push(se)

        //Run your own mini-REPL
        //But 1. We ignore `breakpoint` and
        //    2. we do not terminate the interpreter but stop at the return of the added stack frame so we get the return value
        while (true) {
            if (stack.peek().active is ReturnStmt && stack.peek().id == myId) {
                //Evaluate final return expressions
                val resStmt = stack.peek().active as ReturnStmt
                val res = resStmt.value
                val topmost = evalTopMost(res)
                stack.pop() //clean up
                return Pair(obj, topmost)
            }
            makeStep()
        }
    }

    fun evalClassLevel(expr: Expression, obj: LiteralExpr): Any {
        return eval(expr, mutableMapOf(), heap, simMemory, obj)
    }

    /**
     * Retrieve the list of ObjectNames of a given class from the heap
     * @param className the name of the class
     * @return the list of object names
     */
    fun getObjectNames(className: String): List<String> {
        return heap.keys.filter { it.tag.toString() == className }.map { it.literal }
    }

    private fun createQuery(str: String): QueryExecution {
        // Adding prefixes to the query
        var queryWithPrefixes = ""
        for ((key, value) in settings.prefixMap()) queryWithPrefixes += "PREFIX $key: <$value>\n"
        queryWithPrefixes += str

        val model = tripleManager.getModel()
        queryWithPrefixes = queryWithPrefixes.replace("\\\"", "\"")

        if(settings.verbose) println("execute ISSA\n: $queryWithPrefixes")
        val query = QueryFactory.create(queryWithPrefixes)
        val qexec = QueryExecutionFactory.create(query, model)

        return qexec
    }

    fun ask(str: String): Boolean {
        val startNs = System.nanoTime()
        val qexec = createQuery(str)
        val result = qexec.execAsk()
        settings.metrics.recordQuery(System.nanoTime() - startNs)
        return result
    }

    // Run SPARQL query (str)
    fun query(str: String): ResultSet? {
        val startNs = System.nanoTime()
        val qexec = createQuery(str)
        val result = qexec.execSelect()
        settings.metrics.recordQuery(System.nanoTime() - startNs)
        return result
    }

    fun odrlQuery(userId: String, subjectId: String, actionType: String, purposeId: String, attributes: List<String>): Pair<Boolean, Double> {
        val startNs = System.nanoTime()
        val quotedUserId = "\"${userId.replace("\"", "\\\"")}\""
        val quotedSubjectId = "\"${subjectId.replace("\"", "\\\"")}\""
        val queryStr = """
        SELECT ?permission ?actionsList ?constraintsList ?assetList WHERE {
            ?permission a prog:RequestPermission ;
                        prog:RequestPermission_dc ?dc ;
                        prog:RequestPermission_ds ?ds ;
                        prog:RequestPermission_actions ?actionsList ;
                        prog:RequestPermission_constraints ?constraintsList ;
                        prog:RequestPermission_assets ?assetList .
            ?dc a prog:DataController ;
                prog:DataController_id $quotedUserId .
            ?ds a prog:DataSubject ;
                prog:DataSubject_id $quotedSubjectId .
        }
        """.trimIndent()

        val results = query(queryStr)
        var matched = false
        var heapResultCount = 0
        if (results != null) {
            for (r in results) {
                heapResultCount++
                if (checkPermissionManually(r, actionType, purposeId, attributes.toSet())) {
                    matched = true
                    break
                }
            }
        }

        // Fallback to SPARQL query if no matches found in heap and domainPrefix is set
        if (!matched && heapResultCount == 0 && settings.domainPrefix.isNotEmpty()) {
            matched = checkPermissionViaSparql(userId, subjectId, actionType, purposeId, attributes.toSet())
        }

        val endNs = System.nanoTime()
        settings.metrics.recordQuery(endNs - startNs)
        return Pair(matched, (endNs - startNs).toDouble() / 100000000.0)
    }

    private fun checkPermissionManually(r: QuerySolution, actionType: String, purposeId: String, requiredAttrs: Set<String>): Boolean {
        val permNode = r.get("permission") ?: return false
        val actionsListNode = r.get("actionsList") ?: return false
        val constraintsListNode = r.get("constraintsList") ?: return false
        val assetListNode = r.get("assetList") ?: return false

        val permName = permNode.toString().removePrefix(settings.runPrefix)
        val actionsListName = actionsListNode.toString().removePrefix(settings.runPrefix)
        val constraintsListName = constraintsListNode.toString().removePrefix(settings.runPrefix)
        val assetListName = assetListNode.toString().removePrefix(settings.runPrefix)

        if (permName == permNode.toString()) return false

        val smolNullUri = "${settings.langPrefix}null"

        // Action/constraint/asset checks: heap traversal first, combined SPARQL fallback
        val actionOk = if (actionsListName != smolNullUri) {
            checkListContainsActionType(actionsListName, actionType)
        } else false
        val purposeOk = if (constraintsListName != smolNullUri) {
            checkListContainsPurpose(constraintsListName, purposeId)
        } else false
        val attrsOk = if (requiredAttrs.isEmpty()) {
            true
        } else if (assetListName != smolNullUri) {
            checkListHasAllAttributes(assetListName, requiredAttrs)
        } else false

        if (actionOk && purposeOk && attrsOk) return true

        val permMem = getHeapMemory(permName)
        val permStoredId = (permMem?.get("id") as? LiteralExpr)?.literal?.removeSurrounding("\"")
        val permFullId = if (permStoredId != null) settings.runPrefix + permStoredId else null
        if (permFullId != null && checkOdrlViaSparql(permFullId, actionType, purposeId, requiredAttrs)) return true
        return false
    }

    // Direct SPARQL check against the background ontology, used as a fallback when
    // adaptAddRequestPermission failed to create heap-based RequestPermission objects.
    // Uses a single SELECT query with COUNT(DISTINCT) to verify all attributes in one round-trip.
    private fun checkPermissionViaSparql(userId: String, subjectId: String, actionType: String, purposeId: String, requiredAttrs: Set<String>): Boolean {
        val odrl = "http://www.w3.org/ns/odrl/2/"
        val domain = settings.domainPrefix
        val sotwContext = "https://w3id.org/force/sotw#context"

        if (requiredAttrs.isEmpty()) {
            return ask("""
                ASK WHERE {
                    ?perm a <${odrl}Permission> ;
                          <${odrl}assignee> ?dc ;
                          <${odrl}assigner> ?ds ;
                          <${odrl}action> ?action ;
                          <$sotwContext> ?constraint .
                    ?dc <${domain}identifier> "$userId" .
                    ?ds <${domain}identifier> "$subjectId" .
                    ?action <${odrl}label> "$actionType" .
                    ?constraint <${odrl}rightOperand> ?purpose .
                    ?purpose <${domain}identifier> "$purposeId" .
                }
            """.trimIndent())
        }

        val attrFilter = requiredAttrs.joinToString(", ") { "\"$it\"" }
        val results = query("""
            SELECT ?perm (COUNT(DISTINCT ?asset) AS ?matched) WHERE {
                ?perm a <${odrl}Permission> ;
                      <${odrl}assignee> ?dc ;
                      <${odrl}assigner> ?ds ;
                      <${odrl}action> ?action ;
                      <$sotwContext> ?constraint .
                ?dc <${domain}identifier> "$userId" .
                ?ds <${domain}identifier> "$subjectId" .
                ?action <${odrl}label> "$actionType" .
                ?constraint <${odrl}rightOperand> ?purpose .
                ?purpose <${domain}identifier> "$purposeId" .
                ?perm <${odrl}target> ?asset .
                ?asset <${odrl}label> ?attrName .
                FILTER (?attrName IN ($attrFilter))
            }
            GROUP BY ?perm
            HAVING (?matched >= ${requiredAttrs.size})
        """.trimIndent())
        return results != null && results.hasNext()
    }

    // Combined SPARQL check that verifies action, purpose, and attributes in a single query.
    // Used when heap-based lists are null (set*() methods failed due to runPrefix stripping).
    private fun checkOdrlViaSparql(permFullId: String, actionType: String, purposeId: String, requiredAttrs: Set<String>): Boolean {
        if (settings.domainPrefix.isEmpty()) return false
        val odrl = "http://www.w3.org/ns/odrl/2/"
        val sotwContext = "https://w3id.org/force/sotw#context"
        val domain = settings.domainPrefix
        val fullPurposeId = settings.runPrefix + purposeId

        if (requiredAttrs.isEmpty()) {
            return ask("""
                ASK WHERE {
                    ?perm a <${odrl}Permission> ;
                          <${domain}identifier> "$permFullId" ;
                          <${odrl}action> ?action ;
                          <$sotwContext> ?constraint .
                    ?action <${odrl}label> "$actionType" .
                    ?constraint <${odrl}rightOperand> ?purpose .
                    ?purpose <${domain}identifier> "$fullPurposeId" .
                }
            """.trimIndent())
        }

        val attrFilter = requiredAttrs.joinToString(", ") { "\"$it\"" }
        val results = query("""
            SELECT ?perm (COUNT(DISTINCT ?asset) AS ?matched) WHERE {
                ?perm a <${odrl}Permission> ;
                      <${domain}identifier> "$permFullId" ;
                      <${odrl}action> ?action ;
                      <$sotwContext> ?constraint .
                ?action <${odrl}label> "$actionType" .
                ?constraint <${odrl}rightOperand> ?purpose .
                ?purpose <${domain}identifier> "$fullPurposeId" .
                ?perm <${odrl}target> ?asset .
                ?asset <${odrl}label> ?attrName .
                FILTER (?attrName IN ($attrFilter))
            }
            GROUP BY ?perm
            HAVING (?matched >= ${requiredAttrs.size})
        """.trimIndent())
        return results != null && results.hasNext()
    }

    private fun getHeapMemory(name: String): Memory? {
        val key = heap.keys.find { it.literal == name } ?: return null
        return heap[key]
    }

    private fun checkListContainsActionType(listHeadName: String, actionType: String): Boolean {
        var currentName = listHeadName
        while (true) {
            val listKey = heap.keys.find { it.literal == currentName } ?: break
            val listMem = heap[listKey] ?: break
            val contentRef = listMem["content"] as? LiteralExpr ?: break
            val actionMem = getHeapMemory(contentRef.literal) ?: break
            val actionTypeVal = (actionMem["type"] as? LiteralExpr)?.literal?.removeSurrounding("\"")
            if (actionTypeVal != null && actionTypeVal == actionType) return true
            val nextRef = listMem["next"] as? LiteralExpr ?: break
            if (nextRef.literal == "null") break
            currentName = nextRef.literal
        }
        return false
    }

    private fun checkListContainsPurpose(constraintsListHeadName: String, purposeId: String): Boolean {
        var constraintListName = constraintsListHeadName
        while (true) {
            val constraintListKey = heap.keys.find { it.literal == constraintListName } ?: break
            val constraintListMem = heap[constraintListKey] ?: break
            val constraintRef = constraintListMem["content"] as? LiteralExpr ?: break
            val constraintMem = getHeapMemory(constraintRef.literal) ?: break

            if (checkOperandsListContainsPurpose(constraintMem, purposeId)) return true

            val nextRef = constraintListMem["next"] as? LiteralExpr ?: break
            if (nextRef.literal == "null") break
            constraintListName = nextRef.literal
        }
        return false
    }

    private fun checkOperandsListContainsPurpose(constraintMem: Memory, purposeId: String): Boolean {
        val rightOperandsRef = constraintMem["rightOperands"] as? LiteralExpr ?: return false
        var operandsListName = rightOperandsRef.literal
        while (true) {
            val operandsListKey = heap.keys.find { it.literal == operandsListName } ?: break
            val operandsListMem = heap[operandsListKey] ?: break
            val operandRef = operandsListMem["content"] as? LiteralExpr ?: break
            val operandMem = getHeapMemory(operandRef.literal) ?: break
            val operId = (operandMem["id"] as? LiteralExpr)?.literal?.removeSurrounding("\"")
            if (operId != null && operId == purposeId) return true
            val nextRef = operandsListMem["next"] as? LiteralExpr ?: break
            if (nextRef.literal == "null") break
            operandsListName = nextRef.literal
        }
        return false
    }

    private fun checkListHasAllAttributes(listHeadName: String, requiredAttrs: Set<String>): Boolean {
        val foundAttrs = mutableSetOf<String>()
        var currentName = listHeadName
        while (true) {
            val listKey = heap.keys.find { it.literal == currentName } ?: break
            val listMem = heap[listKey] ?: break
            val contentRef = listMem["content"] as? LiteralExpr ?: break
            val assetMem = getHeapMemory(contentRef.literal)
            if (assetMem != null) {
                val attrName = (assetMem["attributeName"] as? LiteralExpr)?.literal?.removeSurrounding("\"")
                if (attrName != null && attrName in requiredAttrs) {
                    foundAttrs.add(attrName)
                    if (foundAttrs.size >= requiredAttrs.size) return true
                }
            }
            val nextRef = listMem["next"] as? LiteralExpr ?: break
            if (nextRef.literal == "null") break
            currentName = nextRef.literal
        }
        return false
    }

    // Run OWL query and return all instances of the described class.
    // str should be in Manchester syntax
    fun owlQuery(str: String): NodeSet<OWLNamedIndividual> {
        val startNs = System.nanoTime()
        val out : String = settings.replaceKnownPrefixesNoColon(str.removeSurrounding("\""))
        val m = OWLManager.createOWLOntologyManager()
        val ontology = tripleManager.getOntology()
        val reasoner = Reasoner.ReasonerFactory().createReasoner(ontology)
        val parser = ManchesterOWLSyntaxParserImpl(OntologyConfigurator(), m.owlDataFactory)
        parser.setDefaultOntology(ontology)
        val expr = parser.parseClassExpression(out)
        val result = reasoner.getInstances(expr)
        settings.metrics.recordQuery(System.nanoTime() - startNs)
        return result
    }

    // Dump all triples in the virtual model to ${settings.outdir}/file
    internal fun dump(file: String) {
        val model = tripleManager.getModel()
        File(settings.outdir).mkdirs()
        File("${settings.outdir}/${file}").createNewFile()
        model.write(FileWriter("${settings.outdir}/${file}"),"TTL")
    }

    fun evalTopMost(expr: Expression) : LiteralExpr{
        if(stack.isEmpty()) return LiteralExpr("ERROR") // program terminated
        return eval(expr, stack.peek())
    }

    /**
     * Executes exactly one step of the interpreter, and returns true if
     * another step can be executed.  Note that rewritings also count as one
     * executing step.
     */
    fun makeStep() : Boolean {
        if(stack.isEmpty()) return false // program terminated

        //get current frame
        val current = stack.pop()

        if(heap[current.obj] == null)
            throw Exception("This object is unknown: ${current.obj}")

        //get own local memory
        val heapObj: Memory = heap.getOrDefault(current.obj, mutableMapOf())

        //evaluate
        val eRes = current.active.eval(heapObj, current, this)


        //if the current frame is not finished, push its modification back
        if(eRes.current != null){
            stack.push(eRes.current)
        }

        //in case we spawn more frames, push them as well
        for( se in eRes.spawns){
            stack.push(se)
        }

        return !eRes.debug
    }

    /**
     * This implements the substitution of meta-variables %i
     */
    fun prepareQuery(queryExpr : Expression, params : List<Expression>, stackMemory: Memory, heap: GlobalMemory, obj: LiteralExpr, SPARQL : Boolean = true) : String{
        val query = eval(queryExpr, stackMemory, heap, simMemory, obj)
        if (query.tag != STRINGTYPE)
            throw Exception("Query is not a string: $query")
        var str = query.literal
        var i = 1
        for (expr in params) {
            val p = eval(expr, stackMemory, heap, simMemory, obj)
            str = when (p.tag) {
                INTTYPE     -> str.replace("%${i++}", if(SPARQL) "\"${p.literal}\"^^xsd:integer" else p.literal);
                DOUBLETYPE  -> str.replace("%${i++}", if(SPARQL)"\"${p.literal}\"^^xsd:double" else p.literal);
                STRINGTYPE  -> str.replace("%${i++}", p.literal);
                else        -> str.replace("%${i++}", "run:${p.literal}")
            }
        }
        if (!staticInfo.fieldTable.containsKey("List") || !staticInfo.fieldTable["List"]!!.any { it.name == "content" } || !staticInfo.fieldTable["List"]!!.any { it.name == "next" }
        ) {
            throw Exception("Could not find List class in this model")
        }
        return str
    }


    fun eval(expr: Expression, stackEntry: StackEntry) = eval(expr, stackEntry.store, this.heap, this.simMemory, stackEntry.obj)
    fun eval(expr: Expression, stack: Memory, heap: GlobalMemory, simMemory: SimulationMemory, obj: LiteralExpr) : LiteralExpr
            = expr.eval(stack, heap, simMemory, obj)

    override fun toString() : String =
        """
Global store : $heap
Stack:
${stack.joinToString(
            separator = "",
            transform = { "Prc${it.id}@${it.obj}:\n\t" + it.store.toString() + "\nStatement:\n\t" + it.active.toString() + "\n" })}
""".trimIndent()

    fun terminate() {
        for(sim in simMemory.values)
            sim.terminate()
    }
}
