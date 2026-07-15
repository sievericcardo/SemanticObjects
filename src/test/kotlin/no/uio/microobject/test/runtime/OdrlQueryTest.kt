package no.uio.microobject.test.runtime

import io.kotest.core.test.Enabled
import io.kotest.core.test.TestCase
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import no.uio.microobject.runtime.Interpreter
import no.uio.microobject.test.MicroObjectTest
import java.io.File

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
    }

    init {
        "odrl query with greenhouse data" {
            val dataSmol = File("src/test/resources/policies/ODRL_data.smol").readText()
            val policies = File("src/test/resources/policies/ODRL_policies.smol").readText()
            val requests = File("src/test/resources/policies/ODRL_requests.smol").readText()
            val sotw = File("src/test/resources/policies/ODRL_sotw.smol").readText()
            val main = File("src/test/resources/policies/ODRL.smol").readText()

            loadBackground("src/test/resources/policies.ttl", "http://www.smolang.org/greenhouseDT-policy#")
            val (interpreter, _) = initInterpreter(dataSmol + "\n" + policies + "\n" + requests + "\n" + sotw + "\n" + main, StringLoad.PRG)
            executeUntilBreak(interpreter)

            val (allowedAll, t) = interpreter.odrlQuery(
                userId = "pol:identifier:greenhouse",
                subjectId = "pol:identifier:basilicum2",
                actionType = "Use",
                purposeId = "pol:identifier:watering-purpose",
                attributes = listOf("plantId", "idealMoisture", "potId", "hasMoisture")
            )
            println("ODRL time result: $t")
//            (t < 120.0) shouldBe true
            allowedAll shouldBe true

            val (allowedSubset, _) = interpreter.odrlQuery(
                userId = "pol:identifier:greenhouse",
                subjectId = "pol:identifier:basilicum2",
                actionType = "Use",
                purposeId = "pol:identifier:watering-purpose",
                attributes = listOf("plantId", "potId")
            )
            allowedSubset shouldBe true

            val (deniedMissing, _) = interpreter.odrlQuery(
                userId = "pol:identifier:greenhouse",
                subjectId = "pol:identifier:basilicum2",
                actionType = "Use",
                purposeId = "pol:identifier:watering-purpose",
                attributes = listOf("plantId", "nonExistent")
            )
            deniedMissing shouldBe false

            val (deniedWrongUser, _) = interpreter.odrlQuery(
                userId = "pol:identifier:wrong-user",
                subjectId = "pol:identifier:basilicum2",
                actionType = "Use",
                purposeId = "pol:identifier:watering-purpose",
                attributes = emptyList()
            )
            deniedWrongUser shouldBe false

            val (deniedWrongAction, _) = interpreter.odrlQuery(
                userId = "pol:identifier:greenhouse",
                subjectId = "pol:identifier:basilicum2",
                actionType = "Share",
                purposeId = "pol:identifier:watering-purpose",
                attributes = emptyList()
            )
            deniedWrongAction shouldBe false

            val (deniedWrongPurpose, _) = interpreter.odrlQuery(
                userId = "pol:identifier:greenhouse",
                subjectId = "pol:identifier:basilicum2",
                actionType = "Use",
                purposeId = "pol:identifier:academic-research-purpose",
                attributes = emptyList()
            )
            deniedWrongPurpose shouldBe false

            val (noAttrs, _) = interpreter.odrlQuery(
                userId = "pol:identifier:greenhouse",
                subjectId = "pol:identifier:basilicum2",
                actionType = "Use",
                purposeId = "pol:identifier:watering-purpose",
                attributes = emptyList()
            )
            noAttrs shouldBe false
        }

        "odrl query with triplestore".config(enabledOrReasonIf = fusekiEndpointToTest) {
            val (interpreter, _) = initTripleStoreInterpreter("persons", StringLoad.RES)
            assertOdrlQueries(interpreter)
        }
    }
}
