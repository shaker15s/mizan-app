package app.mizan.security

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.mizan.domain.model.ActorId
import app.mizan.domain.model.TenantId
import app.mizan.domain.security.AuthMethod
import app.mizan.domain.security.AuthProof
import java.time.Instant
import java.util.UUID

fun confirmDevice(
    activity: FragmentActivity,
    actorId: ActorId,
    tenantId: TenantId,
    operationId: String,
    allowSimulated: Boolean,
    title: String,
    subtitle: String,
    onProof: (AuthProof) -> Unit,
    onNeedSimulated: () -> Unit = {},
    onUnavailable: () -> Unit,
) {
    val manager = BiometricManager.from(activity)
    val authenticators = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    } else {
        BiometricManager.Authenticators.BIOMETRIC_STRONG
    }
    if (manager.canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
        if (allowSimulated) onNeedSimulated() else onUnavailable()
        return
    }
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val method = if (
                    result.authenticationType == BiometricPrompt.AUTHENTICATION_RESULT_TYPE_DEVICE_CREDENTIAL
                ) {
                    AuthMethod.DEVICE_CREDENTIAL
                } else {
                    AuthMethod.BIOMETRIC
                }
                onProof(
                    AuthProof(
                        proofId = UUID.randomUUID().toString(),
                        actorId = actorId,
                        tenantId = tenantId,
                        operationId = operationId,
                        method = method,
                        authenticatedAt = Instant.now(),
                    ),
                )
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onUnavailable()
            }
        },
    )
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setSubtitle(subtitle)
        .setAllowedAuthenticators(authenticators)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        info.setNegativeButtonText(activity.getString(android.R.string.cancel))
    }
    prompt.authenticate(info.build())
}

fun simulatedProof(actorId: ActorId, tenantId: TenantId, operationId: String) = AuthProof(
    proofId = UUID.randomUUID().toString(),
    actorId = actorId,
    tenantId = tenantId,
    operationId = operationId,
    method = AuthMethod.SIMULATED,
    authenticatedAt = Instant.now(),
)
