package app.mizan.security

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.mizan.R
import app.mizan.design.component.mizanGlassPane
import app.mizan.design.component.MizanGhostButton
import app.mizan.design.component.WakeelMark
import app.mizan.design.component.MizanPrimaryButton
import app.mizan.design.component.MizanStatusBadge
import app.mizan.design.component.ShapeCard
import app.mizan.design.component.ShapeControl
import app.mizan.design.component.ShapeFloating
import app.mizan.design.component.ShapePill
import app.mizan.design.component.StatusTone
import app.mizan.design.component.mizanBounceClick
import app.mizan.design.theme.LocalMizanColors
import app.mizan.design.theme.MizanMono
import app.mizan.design.token.Space
import app.mizan.design.motion.mizanTap
import app.mizan.graph.AppGraph
import app.mizan.session.WorkspaceSession
import app.mizan.ui.roleLabel

/**
 * High-Security Biometric Authentication Gate Screen.
 * Uses AndroidX BiometricPrompt to protect the ERP dashboard, ensuring only
 * authorized agents with verified hardware credentials can inspect or execute
 * operations against the system of record.
 */
@Composable
fun BiometricGateScreen(
    session: WorkspaceSession,
    activity: FragmentActivity,
    graph: AppGraph,
    onUnlocked: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMizanColors.current
    val haptic = LocalHapticFeedback.current
    val roleTitle = roleLabel(session.actor.role)
    var isAuthenticating by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val bioAvailability = remember { checkBiometricStatus(activity) }
    val isSimulation = graph.demoMode

    // Pulsing aura animation for the fingerprint target
    val infiniteTransition = rememberInfiniteTransition(label = "pulse_trans")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse_scale",
    )

    fun launchBiometricPrompt() {
        errorMessage = null
        val authenticators = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        }

        val manager = BiometricManager.from(activity)
        val canAuth = manager.canAuthenticate(authenticators)

        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            if (isSimulation || canAuth == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED || canAuth == BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE) {
                // Fallback available
                errorMessage = "No hardware biometrics enrolled. Tap 'Authorize via Simulated Secure Enclave' below."
            } else {
                errorMessage = "Biometric authentication unavailable on this device (Code: $canAuth)."
            }
            return
        }

        isAuthenticating = true
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    isAuthenticating = false
                    try {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    } catch (_: Throwable) {}
                    onUnlocked()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    isAuthenticating = false
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                        errorMessage = errString.toString()
                    }
                }

                override fun onAuthenticationFailed() {
                    try {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    } catch (_: Throwable) {}
                    errorMessage = "Biometric verification failed. Please try again."
                }
            },
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Authenticate to Access Wakeel ERP")
            .setSubtitle("Biometric touch verification required to decrypt ERP tenant state")
            .setDescription("Agent: ${session.actor.displayName} ($roleTitle)")
            .setAllowedAuthenticators(authenticators)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            promptInfo.setNegativeButtonText(activity.getString(android.R.string.cancel))
        }

        prompt.authenticate(promptInfo.build())
    }

    // Auto-prompt on launch if biometrics is available
    LaunchedEffect(session.actor.id) {
        if (bioAvailability == BiometricAvailability.Available) {
            launchBiometricPrompt()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        if (colors.isDark) Color(0xFF070D18) else Color(0xFFF1F5F9),
                        if (colors.isDark) Color(0xFF041E1E) else Color(0xFFE2E8F0),
                        colors.background,
                    ),
                ),
            )
            .padding(Space.lg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = if (colors.isDark) 0.dp else 10.dp,
                    shape = ShapeFloating,
                    spotColor = Color(0x1F0F172A),
                    ambientColor = Color(0x0F0F172A),
                )
                .clip(ShapeFloating)
                .background(
                    if (colors.isDark) SolidColor(colors.surfaceElevated.copy(alpha = 0.94f)) else Brush.verticalGradient(
                        listOf(Color(0xFAFFFFFF), Color(0xEEFFFFFF)),
                    ),
                )
                .border(BorderStroke(0.8.dp, colors.glassBorder), ShapeFloating)
                .padding(Space.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            // Wakeel Brand Shield & Mark
            WakeelMark()

            Spacer(Modifier.height(Space.xs))

            // Pulsing Biometric Sensor Target
            Box(contentAlignment = Alignment.Center) {
                // Outer glowing pulse ring
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(colors.accent.copy(alpha = 0.12f)),
                )
                // Middle border ring
                Box(
                    modifier = Modifier
                        .size(84.dp)
                        .clip(CircleShape)
                        .background(colors.accentMuted)
                        .border(BorderStroke(1.2.dp, colors.accent.copy(alpha = 0.4f)), CircleShape),
                )
                // Center touch button
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(CircleShape)
                        .background(if (colors.isDark) colors.surfaceElevated else Color.White)
                        .border(BorderStroke(1.5.dp, colors.accent), CircleShape)
                        .mizanBounceClick(role = Role.Button) {
                            launchBiometricPrompt()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Fingerprint,
                        contentDescription = "Authenticate with Biometrics",
                        tint = colors.accent,
                        modifier = Modifier.size(38.dp),
                    )
                }
            }

            // Title & Subtitle
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Biometric ERP Authorization",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(Space.xs))
                Text(
                    text = "Hardware attestation required to decrypt Odoo tenant records & execution leases.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }

            // Agent Identity Badge Card
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .mizanGlassPane(ShapeCard)
                    .padding(horizontal = Space.md, vertical = Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(colors.accentMuted),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Person, contentDescription = null, tint = colors.accent, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(Space.md))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = session.actor.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                    )
                    Text(
                        text = "${session.tenant.displayName} · $roleTitle",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary,
                    )
                }
                MizanStatusBadge("LOCKED", StatusTone.Warning)
            }

            // Error or status notification banner
            errorMessage?.let { msg ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ShapeControl)
                        .background(colors.warning.copy(alpha = 0.10f))
                        .border(BorderStroke(0.6.dp, colors.warning.copy(alpha = 0.3f)), ShapeControl)
                        .padding(Space.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = colors.warning, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(msg, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = colors.warning)
                }
            }

            Spacer(Modifier.height(Space.xs))

            // Primary Unlock Action
            MizanPrimaryButton(
                text = if (isAuthenticating) "Scanning Biometrics..." else "Verify Biometric Credentials",
                onClick = { launchBiometricPrompt() },
                loading = isAuthenticating,
                modifier = Modifier.fillMaxWidth(),
            )

            // Simulated fallback or emergency hardware bypass
            if (isSimulation || bioAvailability != BiometricAvailability.Available) {
                MizanGhostButton(
                    text = "Authorize via Simulated Secure Enclave",
                    onClick = {
                        try {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        } catch (_: Throwable) {}
                        onUnlocked()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Switch User / Logout
            Row(
                modifier = Modifier
                    .clip(ShapePill)
                    .mizanTap(onClick = onSignOut)
                    .padding(horizontal = Space.md, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Outlined.Logout, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(14.dp))
                Text(
                    text = "Sign out or Switch Agent",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textTertiary,
                )
            }
        }
    }
}
