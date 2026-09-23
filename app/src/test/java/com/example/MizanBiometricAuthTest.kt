package com.example

import com.example.auth.BiometricAuthProof
import com.example.auth.BiometricHardwareStatus
import com.example.auth.MizanBiometricManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MizanBiometricAuthTest {

    @Test
    fun testSimulatedBiometricProofGeneration() {
        val proof = MizanBiometricManager.createSimulatedProof(
            actorId = "USR-01",
            tenantId = "tenant-a",
            purpose = "ODOO_ERP_DETERMINISTIC_AUTHORITY",
            method = "SIMULATED_FINGERPRINT_STRONG"
        )

        assertNotNull("Proof cannot be null", proof)
        assertEquals("USR-01", proof.actorId)
        assertEquals("tenant-a", proof.tenantId)
        assertEquals("ODOO_ERP_DETERMINISTIC_AUTHORITY", proof.purpose)
        assertEquals("SIMULATED_FINGERPRINT_STRONG", proof.authType)
        assertTrue("Signature token must be at least 32 characters", proof.signatureToken.length >= 32)
        assertTrue("Verified timestamp must be recent", proof.verifiedAt > 0)
    }

    @Test
    fun testBiometricHardwareStatusLabels() {
        val available = BiometricHardwareStatus.AVAILABLE
        assertEquals("Biometric Sensors Ready", available.labelEn)
        assertEquals("مستشعرات البصمة جاهزة", available.labelAr)

        val noneEnrolled = BiometricHardwareStatus.NONE_ENROLLED
        assertEquals("No Biometrics Registered", noneEnrolled.labelEn)

        val noHardware = BiometricHardwareStatus.NO_HARDWARE
        assertEquals("No Biometric Hardware Detected", noHardware.labelEn)
    }

    @Test
    fun testProofCryptographicDeterministicHashing() {
        val p1 = MizanBiometricManager.createSimulatedProof(
            actorId = "USR-01",
            tenantId = "tenant-a",
            purpose = "ODOO_SESSION",
            method = "BIOMETRIC_PASSKEY"
        )
        val p2 = MizanBiometricManager.createSimulatedProof(
            actorId = "USR-02",
            tenantId = "tenant-a",
            purpose = "ODOO_SESSION",
            method = "BIOMETRIC_PASSKEY"
        )

        // Different actors must produce distinct cryptographic signature tokens
        assertTrue(p1.signatureToken != p2.signatureToken)
    }
}
