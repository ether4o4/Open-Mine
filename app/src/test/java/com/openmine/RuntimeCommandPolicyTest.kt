package com.openmine

import org.junit.Assert.*
import org.junit.Test

class RuntimeCommandPolicyTest {
    @Test fun exactReviewedWhitespaceQuotesAndCommandsArePreserved(){
        val command="  printf '%s\\n' \"a b\"\ncd /root\nexport NAME='value'\n"
        assertEquals(command,RuntimeCommandPolicy.validate(command))
    }
    @Test fun emptyControlAndInvalidUnicodeCannotReachExecution(){
        for(command in listOf("  ","echo a\u0000; echo b","echo\rhidden","echo\u007fhidden","echo \uD800")){
            assertTrue(runCatching{RuntimeCommandPolicy.validate(command)}.isFailure)
        }
    }
    @Test fun byteLimitAccountsForUnicodeAndDoesNotSilentlyTruncate(){
        assertEquals("x".repeat(8192),RuntimeCommandPolicy.validate("x".repeat(8192)))
        assertTrue(runCatching{RuntimeCommandPolicy.validate("x".repeat(8193))}.isFailure)
        assertTrue(runCatching{RuntimeCommandPolicy.validate("界".repeat(3000))}.isFailure)
    }
}
