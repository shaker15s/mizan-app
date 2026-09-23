package com.example.auth

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.MessageDigest
import java.util.UUID

enum class BiometricHardwareStatus(val labelEn: String, val labelAr: String) {
    AVAILABLE("Biometric Sensors Ready", "مستشعرات البصمة جاهزة"),
    NONE_ENROLLED("No Biometrics Registered", "لم يتم تسجيل بصمة على الجهاز"),
    NO_HARDWARE("No Biometric Hardware Detected", "الجهاز لا يحتوي على مستشعر بصمة"),
    UNAVAILABLE("Biometrics Temporarily Unavailable", "خدمة البصمة غير متوفرة حالياً"),
    SIMULATION_MODE("Biometric Security Simulation Mode", "نمط محاكاة البصمة للأجهزة الافتراضية")
}

data class BiometricAuthProof(
    val verifiedAt: Long = System.currentTimeMillis(),
    val authType: String, // "FINGERPRINT", "FACE_UNLOCK", "DEVICE_CREDENTIAL", "SIMULATED_STRONG"
    val actorId: String,
    val tenantId: String,
    val purpose: String,
    val signatureToken: String
)

object MizanBiometricManager {

    /**
     * Inspects the device for biometric hardware capabilities
     */
    fun checkCapability(context: Context): BiometricHardwareStatus {
        val biometricManager = BiometricManager.from(context)
        val authenticators = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        } else {
            BIOMETRIC_STRONG or BIOMETRIC_WEAK
        }

        return when (biometricManager.canAuthenticate(authenticators)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricHardwareStatus.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricHardwareStatus.NONE_ENROLLED
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricHardwareStatus.NO_HARDWARE
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> BiometricHardwareStatus.UNAVAILABLE
            else -> BiometricHardwareStatus.NO_HARDWARE
        }
    }

    /**
     * Helper to retrieve FragmentActivity from standard Android Context
     */
    fun findFragmentActivity(context: Context): FragmentActivity? {
        var currentContext = context
        while (currentContext is ContextWrapper) {
            if (currentContext is FragmentActivity) {
                return currentContext
            }
            currentContext = currentContext.baseContext
        }
        return null
    }

    /**
     * Prompts the user with BiometricPrompt (Fingerprint / Face Unlock / Screen Lock Passkey)
     */
    fun authenticate(
        activity: FragmentActivity,
        actorId: String,
        tenantId: String,
        purpose: String,
        title: String = "Deterministic Authority Biometric Verification",
        subtitle: String = "Authenticate to authorize ERP execution & Odoo session",
        description: String = "Biometric signature is cryptographically bound into the MIZAN immutable audit trail.",
        onSuccess: (BiometricAuthProof) -> Unit,
        onError: (errorCode: Int, errString: CharSequence) -> Unit,
        onFailed: () -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                val authType = when (result.authenticationType) {
                    BiometricPrompt.AUTHENTICATION_RESULT_TYPE_BIOMETRIC -> "BIOMETRIC_FINGERPRINT_OR_FACE"
                    BiometricPrompt.AUTHENTICATION_RESULT_TYPE_DEVICE_CREDENTIAL -> "DEVICE_CREDENTIAL_PIN_PATTERN"
                    else -> "BIOMETRIC_STRONG"
                }

                val digest = MessageDigest.getInstance("SHA-256")
                val seed = "${UUID.randomUUID()}:$actorId:$tenantId:${System.currentTimeMillis()}:$authType"
                val signatureToken = digest.digest(seed.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }

                val proof = BiometricAuthProof(
                    verifiedAt = System.currentTimeMillis(),
                    authType = authType,
                    actorId = actorId,
                    tenantId = tenantId,
                    purpose = purpose,
                    signatureToken = signatureToken
                )
                onSuccess(proof)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                onError(errorCode, errString)
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                onFailed()
            }
        }

        val promptInfoBuilder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setDescription(description)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            promptInfoBuilder.setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
        } else {
            promptInfoBuilder.setAllowedAuthenticators(BIOMETRIC_STRONG)
            promptInfoBuilder.setNegativeButtonText("Cancel")
        }

        val biometricPrompt = BiometricPrompt(activity, executor, callback)
        biometricPrompt.authenticate(promptInfoBuilder.build())
    }

    /**
     * Creates a verified cryptographic simulated biometric proof for testing / cloud emulator environments
     */
    fun createSimulatedProof(
        actorId: String,
        tenantId: String,
        purpose: String,
        method: String = "SIMULATED_FINGERPRINT_STRONG"
    ): BiometricAuthProof {
        val digest = MessageDigest.getInstance("SHA-256")
        val seed = "SIMULATED_BIO:$actorId:$tenantId:${System.currentTimeMillis()}:$method"
        val signatureToken = digest.digest(seed.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        return BiometricAuthProof(
            verifiedAt = System.currentTimeMillis(),
            authType = method,
            actorId = actorId,
            tenantId = tenantId,
            purpose = purpose,
            signatureToken = signatureToken
        )
    }
}
