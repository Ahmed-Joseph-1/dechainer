package io.github.warleysr.dechainer.viewmodels

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Drawable
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import io.github.warleysr.dechainer.DechainerApplication
import androidx.core.content.edit
import io.github.warleysr.dechainer.BrowserRestrictionsManager
import org.json.JSONArray
import org.json.JSONObject

data class BrowserApp(
    val name: String,
    val packageName: String,
    val icon: Drawable,
    var isEnabled: Boolean
)

data class BlockedList(
    val id: String,
    val title: String,
    val sites: List<String>
)

class BrowserRestrictionsViewModel : ViewModel() {
    private val context = DechainerApplication.getInstance()
    private val packageManager = context.packageManager
    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val adminName = ComponentName(context, io.github.warleysr.dechainer.DechainerDeviceAdminReceiver::class.java)
    private val manager = BrowserRestrictionsManager(context)

    var browsers = mutableStateListOf<BrowserApp>()
        private set

    private var blockedLists = mutableStateListOf<BlockedList>()

    init {
        loadBrowsers()
        loadBlockedLists()
    }

    private fun loadBrowsers() {
        val possibleBrowsers = manager.getPossibleBrowsers()
        browsers.clear()
        possibleBrowsers.forEach { info ->
            val packageName = info.activityInfo.packageName
            val isSuspended = try {
                dpm.isPackageSuspended(adminName, packageName)
            } catch (_: Exception) { false }
            browsers.add(
                BrowserApp(
                    name = info.loadLabel(packageManager).toString(),
                    packageName = packageName,
                    icon = info.loadIcon(packageManager),
                    isEnabled = !isSuspended
                )
            )
        }
    }

    private fun loadBlockedLists() {
        val prefs = context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
        val json = prefs.getString("blocked_lists_json", null)
        blockedLists.clear()
        if (json != null) {
            parseJsonToList(json, blockedLists)
        }
    }

    private fun parseJsonToList(json: String, targetList: MutableList<BlockedList>) {
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.getString("id")
                val title = obj.getString("title")
                val sitesArray = obj.getJSONArray("sites")
                val sites = mutableListOf<String>()
                for (j in 0 until sitesArray.length()) {
                    sites.add(sitesArray.getString(j))
                }
                targetList.add(BlockedList(id, title, sites))
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun saveBlockedLists() {
        val prefs = context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
        val array = JSONArray()
        blockedLists.forEach { list ->
            val obj = JSONObject().apply {
                put("id", list.id)
                put("title", list.title)
                put("sites", JSONArray(list.sites))
            }
            array.put(obj)
        }
        prefs.edit { putString("blocked_lists_json", array.toString()) }
    }

    fun hasHiddenSite(site: String): Boolean {
        val list = blockedLists.find { it.id == "hidden_list" }
        return list?.sites?.contains(site) == true
    }

    fun toggleHiddenSite(site: String, remove: Boolean) {
        val listIndex = blockedLists.indexOfFirst { it.id == "hidden_list" }
        val currentSites = if (listIndex != -1) blockedLists[listIndex].sites.toMutableList() else mutableListOf()

        if (remove) {
            currentSites.remove(site)
        } else {
            if (!currentSites.contains(site)) currentSites.add(site)
        }

        val newList = BlockedList("hidden_list", "Hidden Sites", currentSites)
        if (listIndex != -1) {
            blockedLists[listIndex] = newList
        } else {
            blockedLists.add(newList)
        }
        saveBlockedLists()
        manager.applyRestrictions()
    }
}