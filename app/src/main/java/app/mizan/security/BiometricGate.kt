package app.mizan.security

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.mizan.R
import app.mizan.design.component.MizanGhostButton
import app.mizan.design.component.MizanPrimaryButton
import app.mizan.design.component.MizanStatusBadge
import app.mizan.design.component.ShapeFloating
import app.mizan.design.component.ShapePill
import app.mizan.design.component.StatusTone
import app.mizan.design.theme.LocalMizanColors
import app.mizan.design.theme.MizanMono
import app.mizan.design.token.Space
import app.mizan.domain.model.ActorId
import app.mizan.domain.model.TenantId
import app.mizan.domain.security.AuthMethod
import app.mizan.domain.security.AuthProof
import java.time.Instant
import java.util.UUID

enum class BiometricAvailability {
    Available,
    NoneEnrolled,
    NoHardware,
    HardwareUnavailable,
    Unsupported,
}

fun checkBiometricStatus(activity: FragmentActivity): BiometricAvailability {
    val manager = BiometricManager.from(activity)
    val authenticators = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    } else {
        BiometricManager.Authenticators.BIOMETRIC_STRONG
    }
    return when (manager.canAuthenticate(authenticators)) {
        BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.Available
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NoneEnrolled
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricAvailability.NoHardware
        BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> BiometricAvailability.HardwareUnavailable
        else -> BiometricAvailability.Unsupported
    }
}

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
                        proofId = "BIO-${UUID.randomUUID().toString().take(8).uppercase()}",
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
    proofId = "SIM-BIO-${UUID.randomUUID().toString().take(8).uppercase()}",
    actorId = actorId,
    tenantId = tenantId,
    operationId = operationId,
    method = AuthMethod.SIMULATED,
    authenticatedAt = Instant.now(),
)

/**
 * Apple Glass Biometric Verification Sheet.
 * High-precision biometric touch authentication dialog for sensitive ERP requests.
 */
@Composable
fun BiometricAuthModal(
    operationName: String,
    operationId: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    isSimulated: Boolean = false,
) {
    val colors = LocalMizanColors.current
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .clip(ShapeFloating)
                .background(colors.surfaceElevated.copy(alpha = 0.96f))
                .border(BorderStroke(0.8.dp, colors.glassBorder), ShapeFloating)
                .padding(Space.lg),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Space.md),
                modifier = Modifier.fillMaxWidth(),
            ) {
                // Biometric sensor glowing emblem
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(colors.accentMuted)
                        .border(BorderStroke(1.dp, colors.accent.copy(alpha = 0.4f)), CircleShape)
                        .clickable(onClick = onConfirm),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Fingerprint,
                        contentDescription = "Touch Sensor",
                        tint = colors.accent,
                        modifier = Modifier.size(42.dp),
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.reauth_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.textPrimary,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(Space.xs))
                    Text(
                        text = if (isSimulated) {
                            stringResource(R.string.reauth_sim_note)
                        } else {
                            stringResource(R.string.reauth_body)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center,
                    )
                }

                // Security metadata pill
                Row(
                    modifier = Modifier
                        .clip(ShapePill)
                        .background(colors.glass)
                        .border(BorderStroke(0.5.dp, colors.glassBorder), ShapePill)
                        .padding(horizontal = Space.md, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Shield,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Hardware Attestation · $operationId",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = MizanMono),
                        color = colors.textSecondary,
                    )
                }

                Spacer(Modifier.height(Space.xs))

                // Action buttons
                MizanPrimaryButton(
                    text = if (isSimulated) {
                        stringResource(R.string.reauth_sim)
                    } else {
                        stringResource(R.string.reauth_action)
                    },
                    onClick = onConfirm,
                    modifier = Modifier.fillMaxWidth(),
                )

                MizanGhostButton(
                    text = stringResource(R.string.cd_back),
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
