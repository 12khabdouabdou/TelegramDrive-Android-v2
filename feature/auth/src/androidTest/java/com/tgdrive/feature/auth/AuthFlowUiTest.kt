package com.tgdrive.feature.auth

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tgdrive.designsystem.editorial.EditorialHeader
import com.tgdrive.designsystem.theme.TelegramDriveTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UI smoke test for the editorial components used by AuthFlow.
 *
 * Previous version rendered `Text("Get your API credentials...")` and
 * asserted it was visible — that tested nothing. This version renders
 * the actual EditorialHeader component that AuthFlow uses.
 *
 * Full Hilt instrumented testing of AuthFlow (with mocked TDLib +
 * Keystore) is still future work — this at least verifies the Compose
 * components render without throwing.
 */
@RunWith(AndroidJUnit4::class)
class AuthFlowUiTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun editorialHeader_displaysAuthTitle() {
        compose.setContent {
            TelegramDriveTheme {
                EditorialHeader(title = "Welcome")
            }
        }
        compose.onNodeWithText("Welcome").assertIsDisplayed()
    }
}
