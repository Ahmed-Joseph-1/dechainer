package io.github.warleysr.dechainer.activities

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.AppBlocking
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.LockClock
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.screens.setup.SetupDeviceOwnerPrivileges
import io.github.warleysr.dechainer.screens.setup.SetupRecovery
import io.github.warleysr.dechainer.screens.tabs.*
import io.github.warleysr.dechainer.security.SecurityManager
import io.github.warleysr.dechainer.ui.theme.DechainerTheme
import io.github.warleysr.dechainer.viewmodels.DeviceOwnerViewModel
import kotlinx.coroutines.delay

import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.ui.platform.LocalContext


class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DechainerTheme {


                val viewModel: DeviceOwnerViewModel = viewModel()
                viewModel.addShizukuListener()

                val currentScreen = viewModel.selectedTab()
                val isRoot = currentScreen in listOf("restrictions", "apps", "config")

                BackHandler(enabled = !isRoot) {
                    viewModel.goBack()
                }

                var currentTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(Unit) {
                    while (true) {
                        currentTime = System.currentTimeMillis()
                        delay(1000)
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(
                            title = { Text(stringResource(R.string.app_name)) },
                            navigationIcon = {
                                if (!isRoot) {
                                    IconButton(onClick = { viewModel.goBack() }) {
                                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, null)
                                    }
                                }
                            },

                            actions = {

                                SoberUpTimerAction()

                                var currentTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
                                LaunchedEffect(Unit) {
                                    while(true) {
                                        currentTime = System.currentTimeMillis()
                                        kotlinx.coroutines.delay(1000)
                                    }
                                }

                                if (SecurityManager.isSessionActive()) {
                                    val remaining = SecurityManager.sessionEndTime - currentTime
                                    val minutes = (remaining / 1000) / 60
                                    val seconds = (remaining / 1000) % 60
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Outlined.LockClock, null, modifier = Modifier.padding(end = 4.dp))
                                        Text(
                                            text = "%02d:%02d".format(minutes, seconds),
                                            style = MaterialTheme.typography.labelLarge
                                        )
                                        IconButton(onClick = { SecurityManager.endSession() }) {
                                            Icon(Icons.Outlined.Logout, null)
                                        }
                                    }
                                }
                            }

                        )
                    },
                    bottomBar = {
                        val tabs = listOf(
                            Pair("restrictions", stringResource(R.string.restrictions)),
                            Pair("apps", stringResource(R.string.apps)),
                            Pair("config", stringResource(R.string.config))
                        )

                        val selectedBaseTab = when (currentScreen) {
                            "restrictions" -> "restrictions"
                            "apps" -> "apps"
                            "config", "setup_device_owner", "activity_blocker", "browser_restrictions", "blocked_words" -> "config"
                            else -> "restrictions"
                        }

                        NavigationBar {
                            tabs.forEach { pair ->
                                NavigationBarItem(
                                    selected = selectedBaseTab == pair.first,
                                    onClick = { viewModel.navigateTo(pair.first) },
                                    label = { Text(pair.second) },
                                    icon = {
                                        Icon(
                                            when (pair.first) {
                                                "restrictions" -> Icons.Outlined.Block
                                                "apps" -> Icons.Default.AppBlocking
                                                else -> Icons.Outlined.Settings
                                            }, contentDescription = null
                                        )
                                    }
                                )
                            }
                        }
                    }
                ) { innerPadding ->

                    if (!(SecurityManager.isRecoveryCodeSet(this)))
                        SetupRecovery(innerPadding)
                    else {
                        Box(modifier = Modifier.padding(innerPadding)) {
                            when (currentScreen) {
                                "restrictions" -> RestrictionsTab()
                                "apps" -> AppsTab()
                                "config" -> ConfigTab()
                                "setup_device_owner" -> SetupDeviceOwnerPrivileges()
                                "activity_blocker" -> ActivityBlockerScreen()
                                "browser_restrictions" -> BrowserRestrictionsScreen()
                                "blocked_words" -> BlockedWordsScreen()
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        SecurityManager.endSession()
    }
}


@Composable
fun SoberUpTimerAction() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val soberUpPrefs = context.getSharedPreferences("sober_up", android.content.Context.MODE_PRIVATE)

    var currentTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var networkEndTime by remember { mutableLongStateOf(soberUpPrefs.getLong("end_time", 0L)) }

    LaunchedEffect(Unit) {
        while (true) {
            currentTime = System.currentTimeMillis()
            networkEndTime = soberUpPrefs.getLong("end_time", 0L)
            kotlinx.coroutines.delay(1000)
        }
    }

    if (networkEndTime > currentTime) {
        val remaining = networkEndTime - currentTime
        val m = (remaining / 1000) / 60
        val s = (remaining / 1000) % 60

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(end = 12.dp)
        ) {
            Icon(
                Icons.Outlined.WifiOff,
                contentDescription = null,
                modifier = Modifier.padding(end = 4.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Text(
                text = "%02d:%02d".format(m, s),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
        }
    }
}