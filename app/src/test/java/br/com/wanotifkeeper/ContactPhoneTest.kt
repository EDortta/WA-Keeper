package br.com.wanotifkeeper

import org.junit.Assert.assertEquals
import org.junit.Test

class ContactPhoneTest {

    @Test
    fun brazilianLocalMobileGetsCountryCode() {
        assertEquals(
            "5518999999999",
            ContactPhone.normalizeForWhatsApp("(18) 99999-9999")
        )
    }

    @Test
    fun brazilianNumberWithCountryCodeIsPreserved() {
        assertEquals(
            "5518999999999",
            ContactPhone.normalizeForWhatsApp("+55 18 99999-9999")
        )
    }

    @Test
    fun internationalPrefixDoubleZeroIsRemoved() {
        assertEquals(
            "59899123456",
            ContactPhone.normalizeForWhatsApp("00598 99 123 456")
        )
    }

    @Test
    fun explicitForeignPlusPrefixIsNotRewrittenAsBrazilian() {
        assertEquals(
            "59899123456",
            ContactPhone.normalizeForWhatsApp("+598 99 123 456")
        )
    }
}
