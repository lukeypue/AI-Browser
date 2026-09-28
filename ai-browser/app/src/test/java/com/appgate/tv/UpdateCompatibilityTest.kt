package com.appgate.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCompatibilityTest {
    @Test
    fun `matching signer certificates are compatible`() {
        assertTrue(UpdateCompatibility.sameSigner("abc123", "ABC123"))
    }

    @Test
    fun `different signer certificates are rejected`() {
        assertFalse(UpdateCompatibility.sameSigner("abc123", "def456"))
    }

    @Test
    fun `missing signer certificate is rejected`() {
        assertFalse(UpdateCompatibility.sameSigner("", "abc123"))
        assertFalse(UpdateCompatibility.sameSigner("abc123", ""))
    }
}
