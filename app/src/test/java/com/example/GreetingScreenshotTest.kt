package com.example

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.example.model.TenantInfo
import com.example.model.UserRole
import com.example.ui.components.MizanTopBar
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class GreetingScreenshotTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun greeting_screenshot() {
    val sampleTenant = TenantInfo(
      tenantId = "tenant-a",
      tenantNameEn = "Al-Amal Trading Co.",
      tenantNameAr = "شركة الأمل للتجارة",
      erpSystem = "Odoo 19 (JSON-2)",
      baseUrl = "https://alamal.odoo.com",
      isSandbox = false
    )

    composeTestRule.setContent {
      MyApplicationTheme {
        MizanTopBar(
          currentTenant = sampleTenant,
          onTenantSwitchClick = {},
          isArabic = true,
          onLanguageToggle = {},
          currentUserRole = UserRole.SALES_MANAGER,
          onRoleSwitchClick = {}
        )
      }
    }

    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/greeting.png")
  }
}
