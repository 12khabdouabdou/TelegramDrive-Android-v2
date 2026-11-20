package com.tgdrive.feature.browser

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tgdrive.designsystem.editorial.CalloutCard
import com.tgdrive.designsystem.editorial.EditorialHeader
import com.tgdrive.designsystem.editorial.HeroNumber
import com.tgdrive.designsystem.editorial.SectionLabel
import com.tgdrive.designsystem.theme.TelegramDriveTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UI tests for the editorial design system components.
 *
 * These test the REAL composables (not placeholder Text() strings) without
 * needing Hilt — the editorial components are pure Compose functions with
 * no ViewModel dependencies.
 *
 * Previous version of this file rendered `Text("Saved Messages")` and
 * asserted it was visible — that tested nothing. This version renders
 * the actual components used across the app.
 */
@RunWith(AndroidJUnit4::class)
class HomeScreenUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun editorialHeader_displaysTitle() {
        compose.setContent {
            TelegramDriveTheme {
                EditorialHeader(title = "Storage Analytics")
            }
        }
        compose.onNodeWithText("Storage Analytics").assertIsDisplayed()
    }

    @Test
    fun heroNumber_displaysValueAndLabel() {
        compose.setContent {
            TelegramDriveTheme {
                HeroNumber(value = "1.5 GB", label = "Total stored")
            }
        }
        compose.onNodeWithText("1.5 GB").assertIsDisplayed()
        compose.onNodeWithText("Total stored").assertIsDisplayed()
    }

    @Test
    fun sectionLabel_displaysText() {
        compose.setContent {
            TelegramDriveTheme {
                SectionLabel(text = "By folder")
            }
        }
        compose.onNodeWithText("By folder").assertIsDisplayed()
    }

    @Test
    fun calloutCard_displaysAllFields() {
        compose.setContent {
            TelegramDriveTheme {
                CalloutCard(
                    label = "Duplicates",
                    value = "250 MB",
                    subtitle = "5 groups · 12 copies",
                )
            }
        }
        compose.onNodeWithText("Duplicates").assertIsDisplayed()
        compose.onNodeWithText("250 MB").assertIsDisplayed()
        compose.onNodeWithText("5 groups · 12 copies").assertIsDisplayed()
    }
}
