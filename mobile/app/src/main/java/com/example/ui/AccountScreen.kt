package com.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.identity.AuthSessionState
import com.example.viewmodel.AccountUiState

private enum class GuestAccountMode {
    LANDING,
    SIGN_IN,
    CREATE_ACCOUNT
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    uiState: AccountUiState,
    onBack: () -> Unit,
    onSignIn: (String, String) -> Unit,
    onCreateAccount: (String, String, String) -> Unit,
    onSendPasswordReset: (String) -> Unit,
    onResendVerification: () -> Unit,
    onRefreshVerification: () -> Unit,
    onSignOut: () -> Unit,
    onDeleteAccount: (String) -> Unit,
    onClearTransientState: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Account & security") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("account_back")) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        },
        modifier = modifier.fillMaxSize()
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            AccountOperationMessage(uiState, onClearTransientState)

            when (val session = uiState.session) {
                AuthSessionState.Initializing -> {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .testTag("account_initializing")
                    )
                    Text(
                        text = "Checking your account…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
                AuthSessionState.Guest -> GuestAccountContent(
                    isLoading = uiState.isLoading,
                    onSignIn = onSignIn,
                    onCreateAccount = onCreateAccount,
                    onSendPasswordReset = onSendPasswordReset
                )
                is AuthSessionState.Authenticated -> AuthenticatedAccountContent(
                    session = session,
                    isLoading = uiState.isLoading,
                    onResendVerification = onResendVerification,
                    onRefreshVerification = onRefreshVerification,
                    onSignOut = onSignOut,
                    onDeleteAccount = onDeleteAccount
                )
            }
        }
    }
}

@Composable
private fun AccountOperationMessage(
    uiState: AccountUiState,
    onClearTransientState: () -> Unit
) {
    val message = uiState.errorMessage ?: uiState.successMessage ?: return
    val isError = uiState.errorMessage != null
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            }
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                color = if (isError) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                },
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onClearTransientState) { Text("Dismiss") }
        }
    }
}

@Composable
private fun ColumnScope.GuestAccountContent(
    isLoading: Boolean,
    onSignIn: (String, String) -> Unit,
    onCreateAccount: (String, String, String) -> Unit,
    onSendPasswordReset: (String) -> Unit
) {
    var mode by remember { mutableStateOf(GuestAccountMode.LANDING) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }

    Text(
        text = "FitDesi works without an account.",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold
    )
    Text(
        text = "Your fitness data currently stays on this device and is not cloud-synced.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    when (mode) {
        GuestAccountMode.LANDING -> {
            Button(
                onClick = { mode = GuestAccountMode.SIGN_IN },
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth().testTag("account_sign_in_start")
            ) {
                Text("Sign in")
            }
            OutlinedButton(
                onClick = { mode = GuestAccountMode.CREATE_ACCOUNT },
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth().testTag("account_create_start")
            ) {
                Text("Create account")
            }
        }
        GuestAccountMode.SIGN_IN -> {
            AccountEmailField(email, { email = it }, isLoading)
            AccountPasswordField(
                value = password,
                onValueChange = { password = it },
                label = "Password",
                enabled = !isLoading,
                tag = "account_sign_in_password"
            )
            Button(
                onClick = { onSignIn(email, password) },
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth().testTag("account_sign_in_submit")
            ) {
                AccountButtonText("Sign in", isLoading)
            }
            TextButton(
                onClick = { onSendPasswordReset(email) },
                enabled = !isLoading,
                modifier = Modifier.align(Alignment.CenterHorizontally).testTag("account_forgot_password")
            ) {
                Text("Forgot password?")
            }
            TextButton(
                onClick = {
                    password = ""
                    mode = GuestAccountMode.LANDING
                },
                enabled = !isLoading,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text("Choose another option")
            }
        }
        GuestAccountMode.CREATE_ACCOUNT -> {
            AccountEmailField(email, { email = it }, isLoading)
            AccountPasswordField(
                value = password,
                onValueChange = { password = it },
                label = "Password",
                enabled = !isLoading,
                tag = "account_create_password"
            )
            AccountPasswordField(
                value = confirmPassword,
                onValueChange = { confirmPassword = it },
                label = "Confirm password",
                enabled = !isLoading,
                tag = "account_confirm_password"
            )
            Button(
                onClick = { onCreateAccount(email, password, confirmPassword) },
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth().testTag("account_create_submit")
            ) {
                AccountButtonText("Create account", isLoading)
            }
            TextButton(
                onClick = {
                    password = ""
                    confirmPassword = ""
                    mode = GuestAccountMode.LANDING
                },
                enabled = !isLoading,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text("Choose another option")
            }
        }
    }
}

@Composable
private fun AuthenticatedAccountContent(
    session: AuthSessionState.Authenticated,
    isLoading: Boolean,
    onResendVerification: () -> Unit,
    onRefreshVerification: () -> Unit,
    onSignOut: () -> Unit,
    onDeleteAccount: (String) -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("Signed in as", style = MaterialTheme.typography.labelLarge)
            Text(
                text = session.email.ifBlank { "Email unavailable" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = if (session.emailVerified) "Verified" else "Verification needed",
                color = if (session.emailVerified) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
                fontWeight = FontWeight.SemiBold
            )
        }
    }

    Text(
        text = "Your FitDesi fitness data remains local to this device and is not cloud-synced.",
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    if (!session.emailVerified) {
        Button(
            onClick = onResendVerification,
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth().testTag("account_resend_verification")
        ) {
            Text("Resend verification")
        }
        OutlinedButton(
            onClick = onRefreshVerification,
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth().testTag("account_refresh_verification")
        ) {
            Text("I've verified my email — refresh")
        }
    }

    OutlinedButton(
        onClick = onSignOut,
        enabled = !isLoading,
        modifier = Modifier.fillMaxWidth().testTag("account_sign_out")
    ) {
        Text("Sign out")
    }

    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = "Danger zone",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.error,
        fontWeight = FontWeight.Bold
    )
    OutlinedButton(
        onClick = { showDeleteDialog = true },
        enabled = !isLoading,
        modifier = Modifier.fillMaxWidth().testTag("account_delete_start")
    ) {
        Text("Delete account", color = MaterialTheme.colorScheme.error)
    }

    if (showDeleteDialog) {
        DeleteAccountDialog(
            isLoading = isLoading,
            onDismiss = { showDeleteDialog = false },
            onConfirm = { password ->
                onDeleteAccount(password)
                showDeleteDialog = false
            }
        )
    }
}

@Composable
private fun DeleteAccountDialog(
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete FitDesi account?") },
        text = {
            Column(
                modifier = Modifier
                    .testTag("account_delete_dialog")
                    .semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("FitDesi fitness data currently stored locally on this device is not cloud synced.")
                Text(
                    "Deleting the account does not erase your local workout, nutrition, routine, " +
                        "profile, or preference data."
                )
                Text("Store subscription cancellation is a separate store action.")
                AccountPasswordField(
                    value = password,
                    onValueChange = { password = it },
                    label = "Confirm password",
                    enabled = !isLoading,
                    tag = "account_delete_password"
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val submittedPassword = password
                    password = ""
                    onConfirm(submittedPassword)
                },
                enabled = !isLoading,
                modifier = Modifier.testTag("account_delete_confirm")
            ) {
                Text("Delete account", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    password = ""
                    onDismiss()
                },
                enabled = !isLoading
            ) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun AccountEmailField(
    value: String,
    onValueChange: (String) -> Unit,
    isLoading: Boolean
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("Email") },
        singleLine = true,
        enabled = !isLoading,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth().testTag("account_email")
    )
}

@Composable
private fun AccountPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    tag: String
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth().testTag(tag)
    )
}

@Composable
private fun AccountButtonText(label: String, isLoading: Boolean) {
    if (isLoading) {
        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(20.dp))
    } else {
        Text(label)
    }
}
