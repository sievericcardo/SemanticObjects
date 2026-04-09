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
        
        "Effects override with same effects should pass" {
            val tC = checkClass("SmartDevice", "effectsTest")
            // This should pass - override with same or fewer effects
            assertTrue(tC.report(false))
        }
        
        "Effects override with additional effects should fail" {
            val tC = checkClass("AdvancedDevice", "effectsTest")
            // This should fail - override adds new effects
            assertFalse(tC.report(false))
        }
    }
}
