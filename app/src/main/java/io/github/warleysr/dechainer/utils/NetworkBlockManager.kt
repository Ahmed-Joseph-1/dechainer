package io.github.warleysr.dechainer.utils

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.net.ProxyInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import androidx.core.content.edit
import io.github.warleysr.dechainer.DechainerDeviceAdminReceiver

object NetworkBlockManager {
    private val handler = Handler(Looper.getMainLooper())

    fun triggerBlock(context: Context) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, DechainerDeviceAdminReceiver::class.java)

        val prefs = context.getSharedPreferences("sober_up_prefs", Context.MODE_PRIVATE)
        val durationMin = prefs.getInt("duration", 1)
        val durationMs = durationMin * 60_000L

        // 1. Native Wi-Fi Toggle (Device Owners are exempt from Android 10+ restrictions)
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiManager.isWifiEnabled = false
        } catch (e: Exception) { e.printStackTrace() }

        // 2. Shizuku Fallback for Mobile Data (Fails silently if Shizuku is not running)
        try {
            ShizukuRunner.command("svc wifi disable && svc data disable", object : ShizukuRunner.CommandResultListener {})
        } catch (e: Exception) { e.printStackTrace() }

        if (dpm.isAdminActive(admin)) {
            // 3. The Bulletproof Kill Switch: Route all traffic to a dead port
            try {
                val proxy = ProxyInfo.buildDirectProxy("127.0.0.1", 8080)
                dpm.setRecommendedGlobalProxy(admin, proxy)
            } catch (e: Exception) { e.printStackTrace() }

            // 4. Lock network configuration screens
            dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_WIFI)
            dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS)
            dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_TETHERING)
        }

        val endTime = System.currentTimeMillis() + durationMs
        context.getSharedPreferences("sober_up", Context.MODE_PRIVATE).edit { putLong("end_time", endTime) }

        scheduleRestore(context, durationMs)
    }

    fun checkAndRestoreOnBoot(context: Context) {
        val endTime = context.getSharedPreferences("sober_up", Context.MODE_PRIVATE).getLong("end_time", 0L)
        val remaining = endTime - System.currentTimeMillis()

        if (remaining > 0) {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(context, DechainerDeviceAdminReceiver::class.java)

            try {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiManager.isWifiEnabled = false
            } catch (e: Exception) {}

            try {
                ShizukuRunner.command("svc wifi disable && svc data disable", object : ShizukuRunner.CommandResultListener {})
            } catch (e: Exception) {}

            if (dpm.isAdminActive(admin)) {
                try {
                    val proxy = ProxyInfo.buildDirectProxy("127.0.0.1", 8080)
                    dpm.setRecommendedGlobalProxy(admin, proxy)
                } catch (e: Exception) {}

                dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_WIFI)
                dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS)
                dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_TETHERING)
            }
            scheduleRestore(context, remaining)
        } else if (endTime > 0) {
            restoreNetwork(context)
        }
    }

    private fun scheduleRestore(context: Context, delay: Long) {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ restoreNetwork(context) }, delay)
    }

    private fun restoreNetwork(context: Context) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, DechainerDeviceAdminReceiver::class.java)

        if (dpm.isAdminActive(admin)) {
            // Clear the dead proxy, restoring internet
            try {
                dpm.setRecommendedGlobalProxy(admin, null)
            } catch (e: Exception) {}

            dpm.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_WIFI)
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS)
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_TETHERING)
        }
        context.getSharedPreferences("sober_up", Context.MODE_PRIVATE).edit { remove("end_time") }
    }
}