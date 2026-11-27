package com.spenmer.tripguard.domain
import org.junit.Assert.*
import org.junit.Test
class SafetyTest {
    @Test fun unknownNeverPasses() {
        assertEquals(Finding.UNABLE_TO_ASSESS, SafetyPolicy.assess(emptyList()))
        assertEquals(Finding.UNABLE_TO_ASSESS, SafetyPolicy.assess(listOf(Observation.NO_VISIBLE_CONCERN, Observation.UNKNOWN)))
        assertEquals(Finding.UNABLE_TO_ASSESS, SafetyPolicy.assess(listOf(Observation.NOT_APPLICABLE)))
    }
    @Test fun criticalOverridesAll() {
        assertEquals(Finding.CRITICAL, SafetyPolicy.assess(listOf(Observation.UNKNOWN, Observation.CRITICAL, Observation.POTENTIAL_CONCERN)))
    }
    @Test fun potentialOverridesUnknown() {
        assertEquals(Finding.POTENTIAL_CONCERN, SafetyPolicy.assess(listOf(Observation.UNKNOWN, Observation.POTENTIAL_CONCERN)))
    }
    @Test fun observedDoesNotCertify() {
        assertEquals(Finding.NO_VISIBLE_CONCERN, SafetyPolicy.assess(listOf(Observation.NO_VISIBLE_CONCERN, Observation.NOT_APPLICABLE)))
        assertTrue(SafetyPolicy.advice(Finding.NO_VISIBLE_CONCERN).contains("not a roadworthiness certificate"))
    }
    @Test fun vehicleTemplatesDiffer() {
        val ev = Vehicle("1", "EV", VehicleClass.CAR, Propulsion.ELECTRIC)
        val bike = Vehicle("2", "Bike", VehicleClass.TWO_WHEELER, Propulsion.PETROL)
        assertFalse(SafetyPolicy.checklist(ev).any { it.id == "oil" })
        assertTrue(SafetyPolicy.checklist(bike).any { it.id == "drive" })
        assertTrue(SafetyPolicy.checklist(bike).first { it.id == "cooling" }.guidance.contains("Never open"))
    }
}
