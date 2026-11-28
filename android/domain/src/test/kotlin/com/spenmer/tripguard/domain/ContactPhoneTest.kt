package com.spenmer.tripguard.domain

import org.junit.Assert.*
import org.junit.Test

class ContactPhoneTest {
    @Test fun normalizesFormattedInternationalNumber() {
        assertEquals("+919876543210", ContactPhone.normalize("+91 (98765) 43210"))
    }
    @Test fun acceptsLocalNumber() {
        assertEquals("9876543210", ContactPhone.normalize("98765-43210"))
    }
    @Test fun rejectsDialCommandsAndInvalidNumbers() {
        listOf("112", "", "12345", "1234567890123456", "+91+9876543210", "*123#", "9876543210;123", "tel:9876543210").forEach {
            assertNull(it, ContactPhone.normalize(it))
        }
    }
}
