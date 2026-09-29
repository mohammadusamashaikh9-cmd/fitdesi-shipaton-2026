package com.example.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.identity.AuthSessionState
import com.example.ui.theme.MyPersonalTrainerTheme
import com.example.viewmodel.AccountUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AccountScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `guest account screen keeps local product available without pressure`() {
        render(AccountUiState(session = AuthSessionState.Guest))

        composeTestRule.onNodeWithText("FitDesi works without an account.").assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "Your fitness data currently stays on this device and is not cloud-synced."
        ).assertIsDisplayed()
        composeTestRule.onNodeWithTag("account_sign_in_start").assertIsDisplayed()
        composeTestRule.onNodeWithTag("account_create_start").assertIsDisplayed()
    }

    @Test
    fun `sign in form exposes privacy-safe password reset action`() {
        var resetRequests = 0
        render(
            AccountUiState(session = AuthSessionState.Guest),
            onSendPasswordReset = { resetRequests++ }
        )

        composeTestRule.onNodeWithTag("account_sign_in_start").performClick()
        composeTestRule.onNodeWithTag("account_forgot_password").performScrollTo().performClick()

        assertEquals(1, resetRequests)
    }

    @Test
    fun `unverified account exposes verification actions sign out and deletion`() {
        var resendRequests = 0
        var refreshRequests = 0
        var signOutRequests = 0
        render(
            AccountUiState(
                session = AuthSessionState.Authenticated(
                    uid = "hidden-uid",
                    email = "person@example.com",
                    emailVerified = false
                )
            ),
            onResendVerification = { resendRequests++ },
            onRefreshVerification = { refreshRequests++ },
            onSignOut = { signOutRequests++ }
        )

        composeTestRule.onNodeWithText("person@example.com").assertIsDisplayed()
        composeTestRule.onNodeWithText("Verification needed").assertIsDisplayed()
        composeTestRule.onNodeWithTag("account_resend_verification").assertExists()
        composeTestRule.onNodeWithTag("account_refresh_verification").assertExists()
        composeTestRule.onNodeWithTag("account_sign_out").assertExists()
        composeTestRule.onNodeWithTag("account_delete_start").assertExists()
        composeTestRule.onNodeWithText("hidden-uid").assertDoesNotExist()
        composeTestRule.onNodeWithTag("account_resend_verification").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("account_refresh_verification").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("account_sign_out").performScrollTo().performClick()
        assertEquals(1, resendRequests)
        assertEquals(1, refreshRequests)
        assertEquals(1, signOutRequests)
    }

    @Test
    fun `verified account reports verification without resend controls`() {
        render(
            AccountUiState(
                session = AuthSessionState.Authenticated(
                    uid = "hidden-uid",
                    email = "person@example.com",
                    emailVerified = true
                )
            )
        )

        composeTestRule.onNodeWithText("Verified").assertIsDisplayed()
        composeTestRule.onNodeWithTag("account_resend_verification").assertDoesNotExist()
        composeTestRule.onNodeWithTag("account_refresh_verification").assertDoesNotExist()
    }

    @Test
    fun `delete confirmation explains local preservation and store separation`() {
        render(
            AccountUiState(
                session = AuthSessionState.Authenticated(
                    uid = "hidden-uid",
                    email = "person@example.com",
                    emailVerified = true
                )
            )
        )

        composeTestRule.onNodeWithTag("account_delete_start").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("account_delete_dialog").assertExists()
        composeTestRule.onNodeWithText(
            "Deleting the account does not erase your local workout, nutrition, routine, " +
                "profile, or preference data."
        ).assertExists()
        composeTestRule.onNodeWithText(
            "Store subscription cancellation is a separate store action."
        ).assertExists()
    }

    @Test
    fun `Profile account entry exposes current session summary and opens detail`() {
        var opens = 0
        composeTestRule.setContent {
            MyPersonalTrainerTheme {
                ProfileAccountAndAppCard(
                    personalDetailsValue = "Profile complete",
                    accountValue = "Guest · local data stays on this device",
                    versionName = "test",
                    onPersonalDetailsClick = {},
                    onAccountClick = { opens++ }
                )
            }
        }

        composeTestRule.onNodeWithTag("profile_account_security")
            .assertTextContains("Account & security")
            .assertTextContains("Guest · local data stays on this device")
            .performClick()

        assertEquals(1, opens)
    }

    private fun render(
        state: AccountUiState,
        onSendPasswordReset: (String) -> Unit = {},
        onResendVerification: () -> Unit = {},
        onRefreshVerification: () -> Unit = {},
        onSignOut: () -> Unit = {}
    ) {
        composeTestRule.setContent {
            MyPersonalTrainerTheme {
                AccountScreen(
                    uiState = state,
                    onBack = {},
                    onSignIn = { _, _ -> },
                    onCreateAccount = { _, _, _ -> },
                    onSendPasswordReset = onSendPasswordReset,
                    onResendVerification = onResendVerification,
                    onRefreshVerification = onRefreshVerification,
                    onSignOut = onSignOut,
                    onDeleteAccount = { _ -> },
                    onClearTransientState = {}
                )
            }
        }
    }
}
