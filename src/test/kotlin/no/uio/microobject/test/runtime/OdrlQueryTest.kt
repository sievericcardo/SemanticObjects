package no.uio.microobject.test.runtime

import io.kotest.core.test.Enabled
import io.kotest.core.test.TestCase
import io.kotest.matchers.shouldBe
import no.uio.microobject.runtime.Interpreter
import no.uio.microobject.test.MicroObjectTest

class OdrlQueryTest : MicroObjectTest() {
    private val fusekiEndpointToTest: (TestCase) -> Enabled = {
        if (!System.getenv("FUSEKI_ENDPOINT").isNullOrBlank()) {
            Enabled.enabled
        } else {
            Enabled.disabled("Set FUSEKI_ENDPOINT to run ODRL triplestore query tests.")
        }
    }

    private fun assertOdrlQueries(interpreter: Interpreter) {
        val anyTriple = interpreter.ask("ASK WHERE { ?s ?p ?o }")
        anyTriple shouldBe true

        val hasController = interpreter.ask(
            """
            ASK WHERE {
                ?dc a prog:DataController ;
                    prog:DataController_id ?id .
                FILTER(STR(?id) = "dc-1")
            }
            """.trimIndent()
        )
        hasController shouldBe true

        val (allowedNoAttrs, _) = interpreter.odrlQuery(
            userId = "dc-1",
            subjectId = "ds-1",
            actionType = "read",
            purposeId = "purpose-1",
            attributes = emptyList()
        )
        allowedNoAttrs shouldBe true

        val (allowedWithAttrs, _) = interpreter.odrlQuery(
            userId = "dc-1",
            subjectId = "ds-1",
            actionType = "read",
            purposeId = "purpose-1",
            attributes = listOf("email", "name")
        )
        allowedWithAttrs shouldBe true

        val (allowedSingleAttr, _) = interpreter.odrlQuery(
            userId = "dc-1",
            subjectId = "ds-1",
            actionType = "read",
            purposeId = "purpose-1",
            attributes = listOf("email")
        )
        allowedSingleAttr shouldBe true

        val (allowedWithReorderedAttrs, _) = interpreter.odrlQuery(
            userId = "dc-1",
            subjectId = "ds-1",
            actionType = "read",
            purposeId = "purpose-1",
            attributes = listOf("name", "email")
        )
        allowedWithReorderedAttrs shouldBe true

        val (allowedWithDuplicateAttrs, _) = interpreter.odrlQuery(
            userId = "dc-1",
            subjectId = "ds-1",
            actionType = "read",
            purposeId = "purpose-1",
            attributes = listOf("email", "email", "name")
        )
        allowedWithDuplicateAttrs shouldBe true

        val (deniedWithWrongAttr, _) = interpreter.odrlQuery(
            userId = "dc-1",
            subjectId = "ds-1",
            actionType = "read",
            purposeId = "purpose-1",
            attributes = listOf("email", "missing")
        )
        deniedWithWrongAttr shouldBe false

        val (deniedAllMissingAttrs, _) = interpreter.odrlQuery(
            userId = "dc-1",
            subjectId = "ds-1",
            actionType = "read",
            purposeId = "purpose-1",
            attributes = listOf("missing1", "missing2")
        )
        deniedAllMissingAttrs shouldBe false
    }

    init {
        "odrl query with ttl background" {
            loadBackground("src/test/resources/odrl_query_test.ttl", "http://example.org/domain#")
            val (interpreter, _) = initInterpreter("persons", StringLoad.RES)
            assertOdrlQueries(interpreter)
        }

        "odrl query with triplestore".config(enabledOrReasonIf = fusekiEndpointToTest) {
            val (interpreter, _) = initTripleStoreInterpreter("persons", StringLoad.RES)
            assertOdrlQueries(interpreter)
        }
    }
}
