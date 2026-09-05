package io.github.warleysr.dechainer.screens.tabs

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.warleysr.dechainer.BrowserRestrictionsManager
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.screens.common.RecoveryConfirmDialog
import io.github.warleysr.dechainer.security.SecurityManager
import io.github.warleysr.dechainer.models.AppItem
import io.github.warleysr.dechainer.viewmodels.AppsViewModel
import io.github.warleysr.dechainer.viewmodels.BrowserRestrictionsViewModel
import io.github.warleysr.dechainer.viewmodels.DeviceOwnerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserRestrictionsScreen(
    viewModel: BrowserRestrictionsViewModel = viewModel(),
    appsViewModel: AppsViewModel = viewModel(),
    deviceOwnerViewModel: DeviceOwnerViewModel = viewModel()
) {
    var singleSiteInput by remember { mutableStateOf("") }
    var showRestrictionsDialog by remember { mutableStateOf<AppItem?>(null) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var onCancelAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val context = LocalContext.current

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            item {
                Text(
                    stringResource(R.string.allowed_browsers),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        viewModel.browsers.forEachIndexed { index, browser ->
                            val manager = BrowserRestrictionsManager(context)
                            val notSupported = !manager.supportsRestrictions(browser.packageName)
                            ListItem(
                                modifier = Modifier.clickable(enabled = !notSupported) {
                                    showRestrictionsDialog = AppItem(
                                        name = browser.name,
                                        packageName = browser.packageName,
                                        icon = browser.icon,
                                        isSystem = false,
                                        isHidden = false,
                                        isUninstallBlocked = false
                                    )
                                },
                                headlineContent = { Text(browser.name) },
                                supportingContent = {
                                    Column {
                                        Text(browser.packageName)
                                        if (notSupported) {
                                            Text(
                                                stringResource(R.string.browser_support_warning),
                                                color = MaterialTheme.colorScheme.error,
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    }
                                },
                                leadingContent = {
                                    Image(
                                        bitmap = browser.icon.toBitmap().asImageBitmap(),
                                        contentDescription = null,
                                        modifier = Modifier.size(32.dp)
                                    )
                                },
                                trailingContent = {
                                    Switch(
                                        checked = browser.isEnabled,
                                        onCheckedChange = { checked ->
                                            val wasEnabled = browser.isEnabled
                                            viewModel.browsers[index] = browser.copy(isEnabled = checked)
                                            onCancelAction = {
                                                viewModel.browsers[index] = browser.copy(isEnabled = wasEnabled)
                                            }
                                            pendingAction = {
                                                appsViewModel.suspendApp(browser.packageName, !checked)
                                            }
                                        }
                                    )
                                }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            item {
                Text(
                    "Block Specific Websites",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
                )
                Text(
                    "Type a site URL to add it. If it already exists, typing it will remove it.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = singleSiteInput,
                    onValueChange = { singleSiteInput = it },
                    label = { Text(stringResource(R.string.site_url_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        if (singleSiteInput.isNotBlank()) {
                            val site = singleSiteInput.trim().lowercase()
                            val isRemoving = viewModel.hasHiddenSite(site)

                            if (isRemoving) {
                                pendingAction = {
                                    viewModel.toggleHiddenSite(site, remove = true)
                                    Toast.makeText(context, "Site removed", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                viewModel.toggleHiddenSite(site, remove = false)
                                Toast.makeText(context, "Site added", Toast.LENGTH_SHORT).show()
                            }
                            singleSiteInput = ""
                        }
                    }
                ) { Text("Add / Remove Site") }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    showRestrictionsDialog?.let { browser ->
        AppRestrictionsDialog(
            app = browser,
            viewModel = deviceOwnerViewModel,
            onDismiss = { showRestrictionsDialog = null },
            onSave = { restrictions ->
                pendingAction = {
                    deviceOwnerViewModel.setApplicationRestrictions(browser.packageName, restrictions)
                }
                showRestrictionsDialog = null
            }
        )
    }

    if (pendingAction != null) {
        val storedCode = SecurityManager.getRecoveryCode(context)
        if (storedCode == null) {
            pendingAction?.invoke()
            pendingAction = null
            onCancelAction = null
        } else {
            RecoveryConfirmDialog(
                onConfirm = { code ->
                    if (SecurityManager.validateRecoveryCode(code, storedCode)) {
                        pendingAction?.invoke()
                        pendingAction = null
                        onCancelAction = null
                        true
                    } else false
                },
                onDismiss = {
                    onCancelAction?.invoke()
                    onCancelAction = null
                    pendingAction = null
                }
            )
        }
    }
}