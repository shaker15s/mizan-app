package app.mizan.feature.shell

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import app.mizan.design.component.ShapeFloating
import app.mizan.design.component.ShapePill
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.FactCheck
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Rule
import androidx.compose.material.icons.outlined.SyncProblem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.mizan.R
import app.mizan.design.component.MizanBanner
import app.mizan.design.component.MizanMark
import app.mizan.design.component.StatusTone
import app.mizan.design.theme.LocalMizanColors
import app.mizan.feature.account.AccountRoute
import app.mizan.feature.agent.AgentRoute
import app.mizan.feature.auth.OnboardingRoute
import app.mizan.feature.auth.SignInRoute
import app.mizan.feature.evidence.EvidenceRoute
import app.mizan.feature.governance.GovernanceRoute
import app.mizan.feature.home.HomeRoute
import app.mizan.feature.operations.OperationsRoute
import app.mizan.feature.reconciliation.ReconciliationRoute
import app.mizan.feature.search.SearchRoute
import app.mizan.graph.AppGraph
import app.mizan.graph.recoverExpiredLeases

private data class Dest(val route: String, val label: Int, val icon: ImageVector, val compact: Boolean)

private val destinations = listOf(
    Dest("home", R.string.nav_home, Icons.Outlined.Home, true),
    Dest("agent", R.string.nav_agent, Icons.Outlined.Hub, true),
    Dest("operations", R.string.nav_operations, Icons.Outlined.FactCheck, true),
    Dest("reconciliation", R.string.nav_reconcile, Icons.Outlined.SyncProblem, false),
    Dest("evidence", R.string.nav_evidence, Icons.Outlined.FactCheck, true),
    Dest("governance", R.string.nav_governance, Icons.Outlined.Rule, false),
    Dest("account", R.string.nav_account, Icons.Outlined.AccountCircle, true),
)

@Composable
fun MizanShell(
    graph: AppGraph,
    activity: FragmentActivity,
    onPreferencesChanged: () -> Unit,
) {
    val colors = LocalMizanColors.current
    val nav = rememberNavController()
    val session by graph.session.session.collectAsStateWithLifecycle()
    val start = when {
        !graph.preferences.onboardingDone -> "onboarding"
        session == null -> "sign-in"
        else -> "home"
    }
    LaunchedEffect(session?.expiresAt, session?.actor?.id) {
        val expiry = session?.expiresAt ?: return@LaunchedEffect
        val wait = java.time.Duration.between(java.time.Instant.now(), expiry).toMillis()
        if (wait > 0) delay(wait)
        if (graph.session.expireIfNeeded(java.time.Instant.now())) {
            graph.tokens.clear()
        }
    }
    LaunchedEffect(session?.tenant?.id) {
        val tenant = session?.tenant?.id ?: return@LaunchedEffect
        recoverExpiredLeases(graph, tenant)
    }
    LaunchedEffect(session == null, graph.preferences.onboardingDone) {
        val route = nav.currentDestination?.route
        if (!graph.preferences.onboardingDone && route != "onboarding") {
            nav.navigate("onboarding") { popUpTo(0) }
        } else if (graph.preferences.onboardingDone && session == null && route != "sign-in") {
            nav.navigate("sign-in") { popUpTo(0) }
        }
    }
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showNav = session != null && route in destinations.map { it.route }
    BoxWithConstraints(Modifier.fillMaxSize().background(colors.background)) {
        val expanded = maxWidth >= 840.dp
        val medium = maxWidth >= 600.dp
        Row(Modifier.fillMaxSize()) {
            if (showNav && (medium || expanded)) {
                NavigationRail(containerColor = colors.surface, modifier = Modifier.fillMaxHeight()) {
                    Column(Modifier.padding(vertical = 12.dp)) {
                        MizanMark()
                    }
                    destinations.filter { expanded || it.compact }.forEach { dest ->
                        NavigationRailItem(
                            selected = route == dest.route,
                            onClick = { nav.navigateTab(dest.route) },
                            icon = { Icon(dest.icon, contentDescription = stringResource(dest.label)) },
                            label = { Text(stringResource(dest.label)) },
                        )
                    }
                }
            }
            Scaffold(
                modifier = Modifier.weight(1f),
                containerColor = colors.background,
                bottomBar = {
                    if (showNav && !medium) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .navigationBarsPadding(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .shadow(
                                        elevation = if (colors.isDark) 0.dp else 10.dp,
                                        shape = ShapeFloating,
                                        spotColor = Color(0x1A0F172A),
                                        ambientColor = Color(0x0F0F172A),
                                    )
                                    .clip(ShapeFloating)
                                    .background(
                                        if (colors.isDark) SolidColor(colors.surfaceElevated.copy(alpha = 0.94f)) else Brush.verticalGradient(
                                            listOf(Color(0xF8FFFFFF), Color(0xEEFFFFFF)),
                                        ),
                                    )
                                    .border(BorderStroke(0.8.dp, colors.glassBorder), ShapeFloating)
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceAround,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                destinations.filter { it.compact }.forEach { dest ->
                                    val selected = route == dest.route
                                    Column(
                                        modifier = Modifier
                                            .clip(ShapePill)
                                            .background(if (selected) colors.accentMuted else Color.Transparent)
                                            .clickable { nav.navigateTab(dest.route) }
                                            .padding(horizontal = 12.dp, vertical = 6.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Icon(
                                            dest.icon,
                                            contentDescription = stringResource(dest.label),
                                            tint = if (selected) colors.accent else colors.textSecondary,
                                            modifier = Modifier.size(20.dp),
                                        )
                                        Text(
                                            text = stringResource(dest.label),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                            color = if (selected) colors.textPrimary else colors.textTertiary,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    if (graph.demoMode && session != null) {
                        MizanBanner(stringResource(R.string.simulation_banner), StatusTone.Warning)
                    }
                    NavHost(
                        navController = nav,
                        startDestination = start,
                        modifier = Modifier.weight(1f),
                    ) {
                        composable("onboarding") {
                            OnboardingRoute(graph) {
                                nav.navigate("sign-in") { popUpTo(0) }
                            }
                        }
                        composable("sign-in") {
                            SignInRoute(graph) {
                                nav.navigate("home") { popUpTo(0) }
                            }
                        }
                        composable("home") {
                            HomeRoute(graph, expanded, onOpen = { route ->
                                if (destinations.any { it.route == route }) nav.navigateTab(route) else nav.navigate(route)
                            })
                        }
                        composable("agent") { AgentRoute(graph, activity, expanded) }
                        composable("operations") { OperationsRoute(graph, expanded) }
                        composable("reconciliation") { ReconciliationRoute(graph, expanded) }
                        composable("evidence") { EvidenceRoute(graph, expanded) }
                        composable("governance") { GovernanceRoute(graph) }
                        composable("account") {
                            AccountRoute(
                                graph = graph,
                                onOpen = { nav.navigate(it) },
                                onPreferencesChanged = onPreferencesChanged,
                                onSignedOut = { nav.navigate("sign-in") { popUpTo(0) } },
                            )
                        }
                        composable("search") { SearchRoute(graph) { nav.popBackStack() } }
                        composable("connection") {
                            app.mizan.feature.account.ConnectionRoute(graph) { nav.popBackStack() }
                        }
                        composable("security") {
                            app.mizan.feature.account.SecurityRoute(graph) { nav.popBackStack() }
                        }
                    }
                }
            }
        }
    }
}

private fun androidx.navigation.NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
