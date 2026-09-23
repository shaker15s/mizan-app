package com.example

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Balance
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.MizanRepository
import com.example.data.local.MizanDatabase
import com.example.model.IdentityPrincipal
import com.example.model.TenantInfo
import com.example.model.UserRole
import com.example.ui.components.MizanTopBar
import com.example.ui.components.TrustReceiptDialog
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.AgentScreen
import com.example.ui.screens.EvidenceScreen
import com.example.ui.screens.GovernanceScreen
import com.example.ui.screens.PipelineScreen
import com.example.ui.screens.ReconciliationScreen
import com.example.ui.theme.MizanCardBg
import com.example.ui.theme.MizanCardBorder
import com.example.ui.theme.MizanCoral
import com.example.ui.theme.MizanCyan
import com.example.ui.theme.MizanDarkBg
import com.example.ui.theme.MizanGold
import com.example.ui.theme.MizanGreen
import com.example.ui.theme.MizanSurface
import com.example.ui.theme.MizanTextMuted
import com.example.ui.theme.MizanTextPrimary
import com.example.ui.theme.MizanTextSecondary
import com.example.ui.theme.LocalLiquidGlass
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.MizanViewModel
import com.example.viewmodel.MizanViewModelFactory

class MainActivity : FragmentActivity() {

    private lateinit var viewModel: MizanViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val database = MizanDatabase.getDatabase(applicationContext)
        val repository = MizanRepository(database.mizanDao())
        val factory = MizanViewModelFactory(repository)
        viewModel = ViewModelProvider(this, factory)[MizanViewModel::class.java]

        setContent {
            val isDarkTheme by viewModel.isDarkTheme.collectAsStateWithLifecycle()
            MyApplicationTheme(darkTheme = isDarkTheme) {
                MizanApp(viewModel = viewModel)
            }
        }
    }
}

sealed class MizanNavDestination(
    val titleEn: String,
    val titleAr: String,
    val icon: ImageVector,
    val testTag: String
) {
    object Dashboard : MizanNavDestination("Dashboard", "الرئيسية", Icons.Default.Dashboard, "nav_dashboard")
    object Agent : MizanNavDestination("Agent", "الوكيل", Icons.Default.Terminal, "nav_agent")
    object Pipeline : MizanNavDestination("Pipeline", "المسار", Icons.Default.Timeline, "nav_pipeline")
    object Reconcile : MizanNavDestination("Reconcile", "التسوية", Icons.Default.Warning, "nav_reconcile")
    object Governance : MizanNavDestination("Governance", "الحوكمة", Icons.Default.Gavel, "nav_governance")
    object Evidence : MizanNavDestination("Evidence", "الأدلة", Icons.Default.Fingerprint, "nav_evidence")
}

@Composable
fun MizanApp(viewModel: MizanViewModel) {
    val isArabic by viewModel.isArabic.collectAsStateWithLifecycle()
    val isDarkTheme by viewModel.isDarkTheme.collectAsStateWithLifecycle()
    val currentTenant by viewModel.currentTenant.collectAsStateWithLifecycle()
    val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()
    val selectedReceipt by viewModel.selectedTrustReceipt.collectAsStateWithLifecycle()

    var selectedNavIndex by remember { mutableIntStateOf(0) }
    var showTenantDialog by remember { mutableStateOf(false) }
    var showRoleDialog by remember { mutableStateOf(false) }

    val destinations = listOf(
        MizanNavDestination.Dashboard,
        MizanNavDestination.Agent,
        MizanNavDestination.Pipeline,
        MizanNavDestination.Reconcile,
        MizanNavDestination.Governance,
        MizanNavDestination.Evidence
    )

    // Layout direction: RTL for Arabic, LTR for English
    val layoutDirection = if (isArabic) LayoutDirection.Rtl else LayoutDirection.Ltr

    val glass = LocalLiquidGlass.current

    CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            containerColor = glass.bg,
            topBar = {
                MizanTopBar(
                    currentTenant = currentTenant,
                    onTenantSwitchClick = { showTenantDialog = true },
                    isArabic = isArabic,
                    onLanguageToggle = { viewModel.toggleLanguage() },
                    isDarkTheme = isDarkTheme,
                    onThemeToggle = { viewModel.toggleTheme() },
                    currentUserRole = currentUser.role,
                    onRoleSwitchClick = { showRoleDialog = true }
                )
            },
            bottomBar = {
                Surface(
                    color = glass.surfaceElevated.copy(alpha = 0.90f),
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, glass.borderSubtle),
                    shadowElevation = 12.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                ) {
                    NavigationBar(
                        containerColor = Color.Transparent,
                        tonalElevation = 0.dp,
                        modifier = Modifier.testTag("mizan_bottom_nav")
                    ) {
                        destinations.forEachIndexed { index, dest ->
                            val selected = selectedNavIndex == index
                            NavigationBarItem(
                                selected = selected,
                                onClick = { selectedNavIndex = index },
                                icon = {
                                    Icon(
                                        imageVector = dest.icon,
                                        contentDescription = if (isArabic) dest.titleAr else dest.titleEn
                                    )
                                },
                                label = {
                                    Text(
                                        text = if (isArabic) dest.titleAr else dest.titleEn,
                                        fontSize = 11.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Color.White,
                                    selectedTextColor = glass.accentTeal,
                                    indicatorColor = glass.accentTeal,
                                    unselectedIconColor = glass.textMuted,
                                    unselectedTextColor = glass.textMuted
                                ),
                                modifier = Modifier.testTag(dest.testTag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when (selectedNavIndex) {
                    0 -> DashboardScreen(
                        viewModel = viewModel,
                        onNavigateToAgent = { selectedNavIndex = 1 },
                        onNavigateToEvidence = { selectedNavIndex = 5 }
                    )
                    1 -> AgentScreen(
                        viewModel = viewModel,
                        onNavigateToReconcile = { selectedNavIndex = 3 }
                    )
                    2 -> PipelineScreen(viewModel = viewModel)
                    3 -> ReconciliationScreen(viewModel = viewModel)
                    4 -> GovernanceScreen(viewModel = viewModel)
                    5 -> EvidenceScreen(viewModel = viewModel)
                }

                // Trust Receipt Full Dialog
                if (selectedReceipt != null) {
                    TrustReceiptDialog(
                        receipt = selectedReceipt!!,
                        isArabic = isArabic,
                        onDismiss = { viewModel.selectTrustReceipt(null) }
                    )
                }

                // Tenant Switch Dialog (Proving Multi-Tenant Isolation)
                if (showTenantDialog) {
                    TenantSelectionDialog(
                        currentTenant = currentTenant,
                        tenantA = viewModel.repository.tenantA,
                        tenantB = viewModel.repository.tenantB,
                        isArabic = isArabic,
                        onSelectTenant = {
                            viewModel.selectTenant(it)
                            showTenantDialog = false
                        },
                        onDismiss = { showTenantDialog = false }
                    )
                }

                // Role Switch Dialog (Testing Separation of Duties & Approvals)
                if (showRoleDialog) {
                    RoleSelectionDialog(
                        currentUser = currentUser,
                        repository = viewModel.repository,
                        isArabic = isArabic,
                        onSelectUser = {
                            viewModel.selectUser(it)
                            showRoleDialog = false
                        },
                        onDismiss = { showRoleDialog = false }
                    )
                }
            }
        }
    }
}

@Composable
fun TenantSelectionDialog(
    currentTenant: TenantInfo,
    tenantA: TenantInfo,
    tenantB: TenantInfo,
    isArabic: Boolean,
    onSelectTenant: (TenantInfo) -> Unit,
    onDismiss: () -> Unit
) {
    val glass = LocalLiquidGlass.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = glass.cardBackground,
        shape = RoundedCornerShape(24.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(glass.accentTeal.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(imageVector = Icons.Default.Business, contentDescription = null, tint = glass.accentTeal, modifier = Modifier.size(20.dp))
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = if (isArabic) "تبديل المستأجر (Tenant)" else "Switch Isolated Tenant",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = glass.textPrimary
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = if (isArabic)
                        "اختر المستأجر لاختبار العزل الكامل على مستوى الخادم (بيانات الاعتماد، السجلات، ونظام ERP):"
                    else
                        "Select tenant to prove multi-tenant isolation (credentials, ledgers, and connector instances):",
                    fontSize = 12.sp,
                    color = glass.textSecondary
                )

                TenantCardOption(
                    tenant = tenantA,
                    isSelected = currentTenant.tenantId == tenantA.tenantId,
                    isArabic = isArabic,
                    onClick = { onSelectTenant(tenantA) }
                )

                TenantCardOption(
                    tenant = tenantB,
                    isSelected = currentTenant.tenantId == tenantB.tenantId,
                    isArabic = isArabic,
                    onClick = { onSelectTenant(tenantB) }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(if (isArabic) "إغلاق" else "Close", color = glass.accentTeal)
            }
        }
    )
}

@Composable
fun TenantCardOption(tenant: TenantInfo, isSelected: Boolean, isArabic: Boolean, onClick: () -> Unit) {
    val glass = LocalLiquidGlass.current
    Surface(
        color = if (isSelected) glass.accentTeal.copy(alpha = 0.16f) else glass.surfaceElevated,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) glass.accentTeal else glass.borderSubtle),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = if (isArabic) tenant.tenantNameAr else tenant.tenantNameEn,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = glass.textPrimary
                )
                Text(
                    text = "${tenant.tenantId} • ${tenant.erpSystem}",
                    fontSize = 11.sp,
                    color = glass.accentTeal
                )
            }

            if (isSelected) {
                Icon(imageVector = Icons.Default.Check, contentDescription = "Active", tint = glass.accentTeal)
            }
        }
    }
}

@Composable
fun RoleSelectionDialog(
    currentUser: IdentityPrincipal,
    repository: MizanRepository,
    isArabic: Boolean,
    onSelectUser: (IdentityPrincipal) -> Unit,
    onDismiss: () -> Unit
) {
    val glass = LocalLiquidGlass.current
    val users = listOf(
        repository.userSalesRep,
        repository.userSalesManager,
        repository.userFinanceApprover,
        repository.userAuditor
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = glass.cardBackground,
        shape = RoundedCornerShape(24.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(glass.accentOrange.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(imageVector = Icons.Default.Person, contentDescription = null, tint = glass.accentOrange, modifier = Modifier.size(20.dp))
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = if (isArabic) "تبديل الدور لاختبار فصل المهام (SoD)" else "Switch Role for SoD Testing",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = glass.textPrimary
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (isArabic)
                        "بدّل الدور لاختبار شروط الاعتماد (مثال: إنشاء الطلب كمندوب ثم اعتماده كمدير مبيعات):"
                    else
                        "Switch roles to test Separation of Duties (e.g. create order as Rep, approve as Manager):",
                    fontSize = 12.sp,
                    color = glass.textSecondary
                )

                users.forEach { user ->
                    val isSelected = currentUser.userId == user.userId
                    Surface(
                        color = if (isSelected) glass.accentOrange.copy(alpha = 0.16f) else glass.surfaceElevated,
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) glass.accentOrange else glass.borderSubtle),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectUser(user) }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = user.fullName,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = glass.textPrimary
                                )
                                Text(
                                    text = if (isArabic) user.role.roleNameAr else user.role.roleNameEn,
                                    fontSize = 11.sp,
                                    color = glass.accentOrange
                                )
                            }
                            if (isSelected) {
                                Icon(imageVector = Icons.Default.Check, contentDescription = null, tint = glass.accentOrange)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(if (isArabic) "إغلاق" else "Close", color = glass.accentOrange)
            }
        }
    )
}
