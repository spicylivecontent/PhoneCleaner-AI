package com.phonecleaner.ai.scan

import org.json.JSONArray
import org.json.JSONObject

enum class LockReason { CORE, LAUNCHER, KEYBOARD, DEFAULT_APP, PERSISTENT, RESOURCE_OVERLAY, SELF }

data class AppInfo(
    val pkg: String,
    val label: String,
    val versionCode: Long,
    val versionName: String?,
    val uid: Int,
    val isSystem: Boolean,
    /** Installed for the current user (false = already uninstalled for user 0). */
    val installed: Boolean,
    val enabled: Boolean,
    val installer: String?,
    val sideloaded: Boolean,
    val dangerousPerms: List<String>,
    val overlay: Boolean,
    val deviceAdmin: Boolean,
    val accessibility: Boolean,
    val hasLauncherIcon: Boolean,
    val batteryMah: Double?,
    val dataBytes: Long?,
    val storageBytes: Long?,
    val lastUsed: String?,
    val lockReason: LockReason?,
    /** Heuristic adware/malware warning from the scan itself (before AI research). */
    val suspicious: Boolean,
) {
    val locked get() = lockReason != null

    fun toJson(): JSONObject = JSONObject().apply {
        put("pkg", pkg); put("label", label); put("versionCode", versionCode)
        put("versionName", versionName ?: JSONObject.NULL); put("uid", uid)
        put("isSystem", isSystem); put("installed", installed); put("enabled", enabled)
        put("installer", installer ?: JSONObject.NULL); put("sideloaded", sideloaded)
        put("dangerousPerms", JSONArray(dangerousPerms)); put("overlay", overlay)
        put("deviceAdmin", deviceAdmin); put("accessibility", accessibility)
        put("hasLauncherIcon", hasLauncherIcon)
        put("batteryMah", batteryMah ?: JSONObject.NULL); put("dataBytes", dataBytes ?: JSONObject.NULL)
        put("storageBytes", storageBytes ?: JSONObject.NULL); put("lastUsed", lastUsed ?: JSONObject.NULL)
        put("lockReason", lockReason?.name ?: JSONObject.NULL); put("suspicious", suspicious)
    }

    companion object {
        private fun JSONObject.strOrNull(k: String) = if (isNull(k)) null else getString(k)

        fun fromJson(o: JSONObject) = AppInfo(
            pkg = o.getString("pkg"), label = o.getString("label"), versionCode = o.getLong("versionCode"),
            versionName = o.strOrNull("versionName"), uid = o.getInt("uid"),
            isSystem = o.getBoolean("isSystem"), installed = o.getBoolean("installed"), enabled = o.getBoolean("enabled"),
            installer = o.strOrNull("installer"), sideloaded = o.getBoolean("sideloaded"),
            dangerousPerms = o.getJSONArray("dangerousPerms").let { a -> List(a.length()) { a.getString(it) } },
            overlay = o.getBoolean("overlay"), deviceAdmin = o.getBoolean("deviceAdmin"),
            accessibility = o.getBoolean("accessibility"), hasLauncherIcon = o.getBoolean("hasLauncherIcon"),
            batteryMah = if (o.isNull("batteryMah")) null else o.getDouble("batteryMah"),
            dataBytes = if (o.isNull("dataBytes")) null else o.getLong("dataBytes"),
            storageBytes = if (o.isNull("storageBytes")) null else o.getLong("storageBytes"),
            lastUsed = o.strOrNull("lastUsed"),
            lockReason = o.strOrNull("lockReason")?.let { runCatching { LockReason.valueOf(it) }.getOrNull() },
            suspicious = o.getBoolean("suspicious"),
        )
    }
}

data class ScanResult(
    val time: Long,
    val deviceModel: String,
    val osVersion: String,
    val androidVersion: String,
    val apps: List<AppInfo>,
    /** Raw values of relevant settings; "null" means the setting is unset. */
    val settings: Map<String, String>,
    /** Parts of the scan that could not be read on this phone. */
    val warnings: List<String>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("time", time); put("deviceModel", deviceModel); put("osVersion", osVersion)
        put("androidVersion", androidVersion)
        put("apps", JSONArray().also { a -> apps.forEach { a.put(it.toJson()) } })
        put("settings", JSONObject(settings))
        put("warnings", JSONArray(warnings))
    }

    companion object {
        fun fromJson(o: JSONObject): ScanResult {
            val apps = o.getJSONArray("apps")
            val s = o.getJSONObject("settings")
            val w = o.optJSONArray("warnings") ?: JSONArray()
            return ScanResult(
                time = o.getLong("time"), deviceModel = o.getString("deviceModel"),
                osVersion = o.getString("osVersion"), androidVersion = o.getString("androidVersion"),
                apps = List(apps.length()) { AppInfo.fromJson(apps.getJSONObject(it)) },
                settings = s.keys().asSequence().associateWith { s.getString(it) },
                warnings = List(w.length()) { w.getString(it) },
            )
        }
    }
}
