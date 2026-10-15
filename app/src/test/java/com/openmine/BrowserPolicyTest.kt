package com.openmine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserPolicyTest {
    @Test fun normalizesTypedAddressesWithoutChangingEncodedContent() {
        assertEquals("https://example.com/a%20b?q=x%26y#section", BrowserPolicy.address(" EXAMPLE.COM/a%20b?q=x%26y#section "))
        assertEquals("https://example.com:8443/", BrowserPolicy.address("example.com:8443/"))
        assertEquals("http://localhost:8080/", BrowserPolicy.address("HTTP://LOCALHOST:8080/"))
        assertEquals("http://127.0.0.1:8080/", BrowserPolicy.navigation("http://127.0.0.1:8080/"))
        assertEquals("https://[::1]:8443/", BrowserPolicy.navigation("https://[::1]:8443/"))
    }

    @Test fun rejectsUnsafeSchemesCredentialUrlsAndCleartextSpoofs() {
        listOf("javascript:alert(1)", "data:text/html,test", "file:///sdcard/file", "content://files/a",
            "intent://example.com", "about:blank", "mailto:person@example.com", "tel:1234",
            "http://example.com/", "http://localhost.evil.test/", "http://127.0.0.1.evil.test/",
            "http://127.0.0.2/", "http://[::1]/", "https://person:secret@example.com/",
            "https://example.com:0/", "https://example.com:65536/", "https:///path",
            "https://example.com/\nscript", "https://example.com\\@localhost/").forEach { address ->
            assertTrue(address, runCatching { BrowserPolicy.navigation(address) }.isFailure)
        }
    }

    @Test fun rejectsAmbiguousOrEmptyUserInput() {
        listOf("", " ", "search terms", "https://example.com/a b", "content://private/file",
            "javascript:alert(1)", "https://example.com/\u0000", "https://example.com/" + "x".repeat(16_384)).forEach {
            assertTrue(it.take(80), runCatching { BrowserPolicy.address(it) }.isFailure)
        }
    }
}
