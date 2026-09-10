package io.github.warleysr.dechainer.utils

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ProxyInfo
import android.os.UserManager
import io.github.warleysr.dechainer.DechainerDeviceAdminReceiver

class NetworkBlockManager : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("sober_up", Context.MODE_PRIVATE)
        val endTime = prefs.getLong("end_time", 0L)

        // If the current time has surpassed the end time, safely restore the network.
        if (System.currentTimeMillis() >= endTime) {
            cancelBlock(context)
        } else {
            // The alarm fired too early (e.g., due to a reboot or time change). Reschedule it.
            scheduleRestore(context, endTime - System.currentTimeMillis())
        }
    }

    companion object {
        fun triggerBlock(context: Context) {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminName = ComponentName(context, DechainerDeviceAdminReceiver::class.java)

            try {
                val proxy = ProxyInfo.buildDirectProxy("127.0.0.1", 8080)
                dpm.setRecommendedGlobalProxy(adminName, proxy)
                dpm.addUserRestriction(adminName, UserManager.DISALLOW_CONFIG_WIFI)
                dpm.addUserRestriction(adminName, UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS)
                dpm.addUserRestriction(adminName, UserManager.DISALLOW_CONFIG_TETHERING)
            } catch (e: Exception) { e.printStackTrace() }

            val durationPrefs = context.getSharedPreferences("sober_up_prefs", Context.MODE_PRIVATE)
            val durationMs = durationPrefs.getInt("duration", 1) * 60000L
            val endTime = System.currentTimeMillis() + durationMs

            context.getSharedPreferences("sober_up", Context.MODE_PRIVATE).edit().putLong("end_time", endTime).apply()

            // Aggressively schedule the restore, overriding any previous alarms
            scheduleRestore(context, durationMs)
        }

        // --- BOOT CHECK & RECOVERY ---
        fun checkAndRestoreOnBoot(context: Context) {
            val prefs = context.getSharedPreferences("sober_up", Context.MODE_PRIVATE)
            val endTime = prefs.getLong("end_time", 0L)

            // If there's no saved timer, there is nothing to do.
            if (endTime == 0L) return

            // If the time already expired while the phone was off, unlock immediately.
            if (System.currentTimeMillis() >= endTime) {
                cancelBlock(context)
            } else {
                // If the time hasn't expired yet, reschedule the alarm to finish the countdown!
                scheduleRestore(context, endTime - System.currentTimeMillis())
            }
        }

        private fun scheduleRestore(context: Context, delayMs: Long) {
            val intent = Intent(context, NetworkBlockManager::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val triggerTime = System.currentTimeMillis() + delayMs

            try {
                // Try to set a highly precise alarm
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            } catch (e: SecurityException) {
                // Safe fallback: If Android denies the exact alarm, use a standard alarm instead
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            }
        }

        // --- UNIVERSAL KILLSWITCH ---
        fun cancelBlock(context: Context) {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminName = ComponentName(context, DechainerDeviceAdminReceiver::class.java)

            try {
                // 1. Wipe the Kernel restrictions
                dpm.setRecommendedGlobalProxy(adminName, null)
                dpm.clearUserRestriction(adminName, UserManager.DISALLOW_CONFIG_WIFI)
                dpm.clearUserRestriction(adminName, UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS)
                dpm.clearUserRestriction(adminName, UserManager.DISALLOW_CONFIG_TETHERING)

                // 2. Wipe the saved timer so it doesn't trigger again
                context.getSharedPreferences("sober_up", Context.MODE_PRIVATE).edit().remove("end_time").apply()

                // 3. Destroy any pending alarms
                val intent = Intent(context, NetworkBlockManager::class.java)
                val pendingIntent = PendingIntent.getBroadcast(
                    context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                alarmManager.cancel(pendingIntent)
            } catch (e: Exception) { e.printStackTrace() }
        }
    }
}