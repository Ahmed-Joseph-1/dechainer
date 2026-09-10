package io.github.warleysr.dechainer

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.UserManager

class DechainerDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence? {
        // Intercept the removal request to clean up sticky kernel policies
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(context, DechainerDeviceAdminReceiver::class.java)

            // Destroy the Sober Up dead proxy and network locks
            dpm.setRecommendedGlobalProxy(admin, null)
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_WIFI)
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS)
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_TETHERING)

        } catch (e: Exception) {
            e.printStackTrace()
        }

        return super.onDisableRequested(context, intent)
    }
}