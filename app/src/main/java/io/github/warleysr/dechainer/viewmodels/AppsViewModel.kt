package io.github.warleysr.dechainer.viewmodels

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.warleysr.dechainer.DechainerApplication
import io.github.warleysr.dechainer.DechainerDeviceAdminReceiver
import io.github.warleysr.dechainer.data.AppRepository
import io.github.warleysr.dechainer.models.AppItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.content.edit
import java.util.concurrent.TimeUnit
import android.os.Bundle

class AppsViewModel : ViewModel() {
    private val context = DechainerApplication.getInstance()
    private val packageManager = context.packageManager
    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val adminName = ComponentName(context, DechainerDeviceAdminReceiver::class.java)

    var apps by mutableStateOf<List<AppItem>>(emptyList())
        private set
    var isLoading by mutableStateOf(false)
        private set

    var preventedPackages by mutableStateOf<List<String>>(emptyList())
        private set

    init {
        loadApps()
    }

    fun loadApps() {
        viewModelScope.launch {
            isLoading = true
            apps = withContext(Dispatchers.IO) {
                try {
                    AppRepository.getApps()
                } catch (e: Exception) {
                    emptyList()
                }
            }

            // Load prevented packages
            val installPrefs = context.getSharedPreferences("install_blocker", Context.MODE_PRIVATE)
            preventedPackages = installPrefs.all.keys.toList().sorted()

            isLoading = false
        }
    }

    fun blockApp(packageName: String, hidden: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                dpm.setApplicationHidden(adminName, packageName, hidden)
                loadApps()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun suspendApp(packageName: String, suspended: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                dpm.setPackagesSuspended(adminName, arrayOf(packageName), suspended)
                loadApps()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun setUninstallBlocked(packageName: String, block: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                dpm.setUninstallBlocked(adminName, packageName, block)
                loadApps()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun setAppTimeLimit(packageName: String, minutes: Int) {
        context.getSharedPreferences("app_limits", Context.MODE_PRIVATE).edit {
            if (minutes > 0) putInt(packageName, minutes) else remove(packageName)
        }
        loadApps()
    }

    fun getAppUsage(packageName: String, inMinutes: Boolean = false): Long {
        val prefs = context.getSharedPreferences("internal_usage_stats", Context.MODE_PRIVATE)
        val used = prefs.getLong(packageName, 0L)
        if (inMinutes)
            return TimeUnit.MILLISECONDS.toMinutes(used)
        return used
    }

    fun setAppReopenTime(packageName: String, seconds: Int) {
        context.getSharedPreferences("reopen_times", Context.MODE_PRIVATE).edit {
            if (seconds > 0) putInt(packageName, seconds) else remove(packageName)
        }
    }

    fun getAppReopenTime(packageName: String): Int {
        return context.getSharedPreferences("reopen_times", Context.MODE_PRIVATE).getInt(packageName, 0)
    }

    // --- NEW: UNINSTALL & BLOCK INSTALLATION FEATURE ---
    @SuppressLint("MissingPermission")
    fun blockFutureInstallations(packageName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 1. Uninstall the app silently if it exists
                try {
                    val packageInstaller = packageManager.packageInstaller
                    val pendingIntent = PendingIntent.getBroadcast(
                        context,
                        0,
                        Intent("io.github.warleysr.dechainer.UNINSTALL_APP"),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    packageInstaller.uninstall(packageName, pendingIntent.intentSender)
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                // 2. Instruct the DeviceOwner to block this app from being installed
                dpm.setUninstallBlocked(adminName, packageName, false)
                dpm.setApplicationHidden(adminName, packageName, true)

                context.getSharedPreferences("install_blocker", Context.MODE_PRIVATE).edit {
                    putBoolean(packageName, true)
                }

                loadApps()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun removeInstallationBlock(packageName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                dpm.setApplicationHidden(adminName, packageName, false)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            context.getSharedPreferences("install_blocker", Context.MODE_PRIVATE).edit {
                remove(packageName)
            }
            loadApps()
        }
    }
}