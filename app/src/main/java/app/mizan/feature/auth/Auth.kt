package app.mizan.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import app.mizan.R
import app.mizan.design.component.MizanMark
import app.mizan.design.component.MizanPrimaryButton
import app.mizan.design.component.MizanSecondaryButton
import app.mizan.design.theme.LocalMizanColors
import app.mizan.design.token.Space
import app.mizan.domain.model.SessionMode
import app.mizan.domain.model.TenantContext
import app.mizan.domain.model.TenantId
import app.mizan.graph.AppGraph
import app.mizan.integration.api.SessionApi
import app.mizan.integration.api.SignInResult
import app.mizan.onSimulationEntered
import app.mizan.simulationEntry
import app.mizan.session.SessionController
import app.mizan.session.WorkspaceSession
import app.mizan.ui.reasonLabel
import kotlinx.coroutines.launch
import java.time.Instant

@Composable
fun OnboardingRoute(graph: AppGraph, onDone: () -> Unit) {
    var page by remember { mutableIntStateOf(0) }
    val colors = LocalMizanColors.current
    val pages = listOf(
        R.string.onboarding_1_title to R.string.onboarding_1_body,
        R.string.onboarding_2_title to R.string.onboarding_2_body,
        R.string.onboarding_3_title to R.string.onboarding_3_body,
    )
    Column(
        Modifier.fillMaxSize().padding(Space.xl),
        verticalArrangement = Arrangement.spacedBy(Space.lg),
    ) {
        MizanMark()
        Text(stringResource(pages[page].first), style = MaterialTheme.typography.headlineMedium, color = colors.textPrimary)
        Text(stringResource(pages[page].second), style = MaterialTheme.typography.bodyLarge, color = colors.textSecondary)
        if (page < pages.lastIndex) {
            MizanPrimaryButton(stringResource(R.string.onboarding_next), { page++ })
            MizanSecondaryButton(stringResource(R.string.onboarding_skip), {
                graph.preferences.onboardingDone = true
                onDone()
            })
        } else {
            MizanPrimaryButton(stringResource(R.string.onboarding_start), {
                graph.preferences.onboardingDone = true
                onDone()
            })
        }
    }
}

@Composable
fun SignInRoute(graph: AppGraph, onSignedIn: () -> Unit) {
    if (graph.demoMode) {
        DemoSignIn(graph, onSignedIn)
    } else {
        RemoteSignIn(graph, onSignedIn)
    }
}

@Composable
private fun DemoSignIn(graph: AppGraph, onSignedIn: () -> Unit) {
    val colors = LocalMizanColors.current
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxSize().padding(Space.xl),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        MizanMark()
        Text(stringResource(R.string.demo_title), style = MaterialTheme.typography.headlineMedium, color = colors.textPrimary)
        Text(stringResource(R.string.demo_body), style = MaterialTheme.typography.bodyLarge, color = colors.textSecondary)
        MizanPrimaryButton(stringResource(R.string.demo_enter), {
            scope.launch {
                val (actor, tenant) = simulationEntry() ?: return@launch
                onSimulationEntered(graph, tenant.id)
                graph.session.open(
                    WorkspaceSession(actor, tenant, SessionMode.SIMULATION, expiresAt = null),
                )
                onSignedIn()
            }
        })
    }
}

@Composable
private fun RemoteSignIn(graph: AppGraph, onSignedIn: () -> Unit) {
    val colors = LocalMizanColors.current
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var url by remember { mutableStateOf(graph.apiBaseUrl) }
    var error by remember { mutableStateOf<String?>(null) }
    var invalidUrl by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val configured = graph.apiBaseUrl.isNotBlank() || url.startsWith("https://")
    Column(
        Modifier.fillMaxSize().padding(Space.xl),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        MizanMark()
        Text(stringResource(R.string.sign_in_title), style = MaterialTheme.typography.headlineMedium, color = colors.textPrimary)
        Text(stringResource(R.string.sign_in_body), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
        if (graph.apiBaseUrl.isBlank()) {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(stringResource(R.string.sign_in_url)) },
                singleLine = true,
            )
        }
        if (!configured && graph.apiBaseUrl.isBlank()) {
            Text(stringResource(R.string.sign_in_missing_service), color = colors.textSecondary)
        }
        OutlinedTextField(email, { email = it }, label = { Text(stringResource(R.string.sign_in_email)) }, singleLine = true)
        OutlinedTextField(
            password,
            { password = it },
            label = { Text(stringResource(R.string.sign_in_password)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
        )
        if (invalidUrl) Text(stringResource(R.string.sign_in_url_invalid), color = colors.danger)
        else if (error != null) Text(reasonLabel(error!!), color = colors.danger)
        MizanPrimaryButton(
            text = stringResource(R.string.sign_in_continue),
            loading = loading,
            enabled = email.isNotBlank() && password.isNotBlank(),
            onClick = {
                val target = graph.apiBaseUrl.ifBlank { url.trim() }
                if (!target.startsWith("https://")) {
                    invalidUrl = true
                    error = null
                    return@MizanPrimaryButton
                }
                invalidUrl = false
                loading = true
                scope.launch {
                    val result = SessionApi(target).signIn(email, password)
                    loading = false
                    password = ""
                    when (result) {
                        is SignInResult.Failed -> error = result.error.code
                        is SignInResult.Success -> {
                            graph.preferences.serviceUrlOverride = target
                            graph.tokens.write(result.session.token)
                            val tenant = TenantContext(
                                TenantId(result.session.tenantId),
                                result.session.tenantLabel,
                                "ERP",
                                graph.environment,
                            )
                            graph.session.open(
                                WorkspaceSession(
                                    actor = SessionController.actor(
                                        result.session.actorId,
                                        result.session.displayName,
                                        SessionController.roleOf(result.session.role),
                                        result.session.tenantId,
                                    ),
                                    tenant = tenant,
                                    mode = SessionMode.REMOTE,
                                    expiresAt = result.session.expiresAtEpochMillis?.let(Instant::ofEpochMilli),
                                ),
                            )
                            onSignedIn()
                        }
                    }
                }
            },
        )
    }
}

