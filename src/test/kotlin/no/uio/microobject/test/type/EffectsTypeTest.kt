package no.uio.microobject.test.type

import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EffectsTypeTest : MicroObjectTypeTest() {
    init {
        "Effects tracking basic test" {
            val tC = checkClass("BaseDevice", "effectsTest")
            // This should pass - base method with effects
            assertTrue(tC.report(false))
        }
        
        "Effects override with same or fewer effects should pass" {
            val tC = checkClass("SmartDevice", "effectsTest")
            // This should pass - override with same or fewer effects
            assertTrue(tC.report(false))
        }
        
        "Effects override with additional effects should fail" {
            val tC = checkClass("AdvancedDevice", "effectsTest")
            // This should fail - override adds new effects
            assertFalse(tC.report(false))
        }

        "Base method must have effects if override has effects" {
            val tC = checkClass("AdvanceBaseDevice", "effectsTest")
            // This should pass - override with fewer effects
            assertFalse(tC.report(false))
        }
    }
}
