package no.uio.microobject.runtime

import no.uio.microobject.ast.Expression
import no.uio.microobject.ast.expr.LiteralExpr
import no.uio.microobject.ast.Statement
import no.uio.microobject.type.Type
import org.apache.jena.query.ResultSet

/*
We use the term "heap" NOT in the sense of C and other low-level here.
Heap memory is barely the opposite of local memory, we have no assumptions about the memory.
 */
typealias Memory = MutableMap<String, LiteralExpr>       // Maps variable names to values
typealias GlobalMemory = MutableMap<LiteralExpr, Memory>  // Maps object name literals to local memories
typealias SimulationMemory = MutableMap<LiteralExpr, SimulatorObject>  // Maps object name literals to local memories
typealias FieldEntry = List<FieldInfo>                   //list of fields
typealias ModelsEntry = Pair<Expression, String>      //guard expression and models string
typealias QueryCache = MutableMap<String, ResultSet>      //query cache

enum class Visibility { DEFAULT, HIDE }

data class FieldInfo(val name: String, val type: Type, val computationVisibility : Visibility, val declaredIn : Type, val isDomain : Boolean, val internalInit : Expression? = null)
data class MethodInfo(val stmt: Statement, val params: List<String>, val isRule : Boolean, val isDomain: Boolean, val declaringClass: String, val retType : Type)
data class StaticTable(
    val fieldTable: Map<String, FieldEntry>,                // This maps class names to their fields
    val methodTable: Map<String, Map<String, MethodInfo>>, // This maps class names to a map that maps method names to their definition
    val hierarchy: MutableMap<String, MutableSet<String>> = mutableMapOf(), // DOWNWARDS class hierarchy
    val modelsTable: Map<String, List<ModelsEntry>>,                // This maps class names to complex models blocks
    val hiddenSet: Set<String>,//This set of classes is skipped by the lifting
    val owldescr: MutableMap<String, String> // This maps class names to the default models block

) {
    override fun toString(): String =
"""
Class Hierarchy : $hierarchy 
FieldTable      : $fieldTable 
MethodTable     : $methodTable 
""".trimIndent()

    private fun getSuper(name : String) : String?{
        for(obj in hierarchy.entries){
            for(obj2 in obj.value){
                if(obj2 == name) return obj.key
            }
        }
        return null
    }

    fun getSuperMethod(className : String, methodName : String) : MethodInfo?{
        var current = getSuper(className)
        while(current != null && current != "Object"){
            if(!methodTable.containsKey(current)) return null
            if(methodTable[current]!!.containsKey(methodName)) return methodTable[current]!![methodName]
            current = getSuper(current)
        }
        return null
    }
}

data class StackEntry(val active: Statement, val store: Memory, val obj: LiteralExpr, val id: Int)
