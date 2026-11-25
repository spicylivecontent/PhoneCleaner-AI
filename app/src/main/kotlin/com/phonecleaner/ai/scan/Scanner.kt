package com.phonecleaner.ai.scan

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager
import com.phonecleaner.ai.BuildConfig
import com.phonecleaner.ai.shizuku.Shell
import com.phonecleaner.ai.shizuku.ShizukuManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Step 1: collects information about the phone. Everything here is read-only:
 * only "list", "get", "query" and "dumpsys" commands are used.
 */
class Scanner(private val context: Context, private val shell: Shell = ShizukuManager) {

    private val knownStores = setOf(
        "com.android.vending", "com.xiaomi.mipicks", "com.xiaomi.market", "com.xiaomi.discover",
        "com.huawei.appmarket", "com.sec.android.app.samsungapps", "com.amazon.venezia",
        "com.oppo.market", "com.heytap.market", "com.bbk.appstore", "com.vivo.appstore",
        "org.fdroid.fdroid", "com.aurora.store",
    )

    val settingKeys = listOf(
        "global window_animation_scale", "global transition_animation_scale",
        "global animator_duration_scale", "global private_dns_mode", "global private_dns_specifier",
    )

    private suspend fun sh(cmd: String, warnings: MutableList<String>): String {
        val r = try {
            shell.exec(cmd)
        } catch (t: Throwable) {
            warnings += "$cmd: ${t.message}"
            return ""
        }
        if (!r.ok) warnings += "$cmd: exit ${r.exitCode} ${r.output.take(120)}"
        return if (r.ok) r.output else ""
    }

    /**
     * Packages that must not be removed right now: home screens, keyboards and the
     * apps holding important default roles. Cheap, so it is re-checked before every removal.
     */
    suspend fun protectedNow(warnings: MutableList<String> = mutableListOf()): Map<String, LockReason> {
        val pm = context.packageManager
        val out = LinkedHashMap<String, LockReason>()
        for (role in listOf("DIALER", "SMS", "BROWSER", "ASSISTANT")) {
            Parsers.plainPackages(sh("cmd role get-role-holders --user 0 android.app.role.$role", warnings).replace(';', '\n'))
                .forEach { out[it] = LockReason.DEFAULT_APP }
        }
        runCatching { context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage?.let { out[it] = LockReason.DEFAULT_APP } }
        runCatching { Telephony.Sms.getDefaultSmsPackage(context)?.let { out[it] = LockReason.DEFAULT_APP } }
        sh("settings get global webview_provider", warnings).trim().takeIf { Parsers.isValidPackage(it) }
            ?.let { out[it] = LockReason.CORE }

        runCatching {
            context.getSystemService(InputMethodManager::class.java)?.inputMethodList?.forEach { out[it.packageName] = LockReason.KEYBOARD }
        }.onFailure { warnings += "keyboard list unavailable" }
        (Parsers.componentPackages(sh("settings get secure enabled_input_methods", warnings)) +
            Parsers.componentPackages(sh("settings get secure default_input_method", warnings)))
            .forEach { out[it] = LockReason.KEYBOARD }

        runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                .forEach { out[it.activityInfo.packageName] = LockReason.LAUNCHER }
        }.onFailure { warnings += "launcher list unavailable" }
        Parsers.plainPackages(sh("cmd role get-role-holders --user 0 android.app.role.HOME", warnings).replace(';', '\n'))
            .forEach { out[it] = LockReason.LAUNCHER }
        return out
    }

    suspend fun scan(progress: (Int) -> Unit): ScanResult = withContext(Dispatchers.Default) {
        val warnings = mutableListOf<String>()
        val pm = context.packageManager
        // All commands target user 0. In Second Space or a work profile the two views would not match.
        if (android.os.Process.myUid() / 100000 != 0) throw IllegalStateException("not running as the main user (user 0)")

        progress(0)
        val listed = Parsers.pmList(sh("pm list packages -f -i -u --user 0", warnings))
        if (listed.isEmpty()) throw IllegalStateException("pm list packages returned nothing")
        val installedNow = Parsers.pmNames(sh("pm list packages --user 0", warnings))
        val systemSet = Parsers.pmNames(sh("pm list packages -s -u --user 0", warnings))
        val disabledSet = Parsers.pmNames(sh("pm list packages -d --user 0", warnings))

        progress(1)
        @Suppress("DEPRECATION")
        val infos: Map<String, PackageInfo> =
            pm.getInstalledPackages(PackageManager.GET_PERMISSIONS or PackageManager.MATCH_UNINSTALLED_PACKAGES)
                .associateBy { it.packageName }
        // Without package details the "always running" and "no code" locks cannot be computed: stop here.
        if (infos.isEmpty()) throw IllegalStateException("the package manager returned no package details")
        val launcherPkgs = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).map { it.activityInfo.packageName }.toSet()

        progress(2)
        val overlayOut = sh("appops query-op --user 0 SYSTEM_ALERT_WINDOW allow", warnings)
        val overlayKnown = overlayOut.isNotBlank()
        val overlayAllowed = Parsers.plainPackages(overlayOut)

        val admins = Parsers.deviceAdmins(sh("dumpsys device_policy", warnings)).toMutableSet()
        runCatching {
            context.getSystemService(DevicePolicyManager::class.java)?.activeAdmins?.forEach { admins += it.packageName }
        }
        val a11y = Parsers.componentPackages(sh("settings get secure enabled_accessibility_services", warnings))

        progress(3)
        val battery = Parsers.batteryByUid(sh("dumpsys batterystats --charged", warnings))
        val data = Parsers.dataByUid(sh("dumpsys netstats detail", warnings))
        val storage = Parsers.storageByPackage(sh("dumpsys diskstats", warnings))
        val lastUsed = Parsers.lastUsedByPackage(sh("dumpsys usagestats", warnings))
        if (battery.isEmpty()) warnings += "battery usage unavailable"
        if (data.isEmpty()) warnings += "data usage unavailable"
        if (storage.isEmpty()) warnings += "storage per app unavailable"

        progress(4)
        val settings = LinkedHashMap<String, String>()
        for (key in settingKeys) settings[key] = sh("settings get $key", warnings).trim().ifEmpty { "null" }

        val osName = sh("getprop ro.mi.os.version.name", warnings).trim()
        val osIncr = sh("getprop ro.mi.os.version.incremental", warnings).trim()
        val miui = sh("getprop ro.miui.ui.version.name", warnings).trim()
        val market = sh("getprop ro.product.marketname", warnings).trim()
        val osVersion = when {
            osName.isNotEmpty() -> "HyperOS $osName" + if (osIncr.isNotEmpty()) " ($osIncr)" else ""
            miui.isNotEmpty() -> "MIUI $miui"
            else -> Build.DISPLAY
        }
        val model = listOf(Build.MANUFACTURER, market.ifEmpty { Build.MODEL }, "(${Build.MODEL})").joinToString(" ")

        val dynamicLocks = protectedNow(warnings)

        val permDangerous = HashMap<String, Boolean>()
        fun isDangerous(name: String) = permDangerous.getOrPut(name) {
            runCatching {
                pm.getPermissionInfo(name, 0).protection == PermissionInfo.PROTECTION_DANGEROUS
            }.getOrDefault(false)
        }

        val apps = listed.distinctBy { it.pkg }.map { e ->
            val pi = infos[e.pkg]
            val ai: ApplicationInfo? = pi?.applicationInfo
            val isSystem = e.pkg in systemSet || (ai != null && ai.flags and ApplicationInfo.FLAG_SYSTEM != 0)
            val requested = pi?.requestedPermissions.orEmpty()
            val flags = pi?.requestedPermissionsFlags
            val dangerous = requested.filterIndexed { i, name ->
                val granted = flags != null && i < flags.size &&
                    flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0
                granted && isDangerous(name)
            }.map { it.removePrefix("android.permission.") }
            val overlay = if (overlayKnown) e.pkg in overlayAllowed
            else "android.permission.SYSTEM_ALERT_WINDOW" in requested
            val sideloaded = !isSystem && (e.installer == null || e.installer !in knownStores)
            val hasIcon = e.pkg in launcherPkgs
            val admin = e.pkg in admins
            val acc = e.pkg in a11y
            val installed = e.pkg in installedNow

            val lock = when {
                e.pkg == BuildConfig.APPLICATION_ID -> LockReason.SELF
                Critical.isCore(e.pkg) -> LockReason.CORE
                e.pkg in dynamicLocks -> dynamicLocks.getValue(e.pkg)
                // No details from the package manager: we cannot tell what it is, so it stays locked.
                ai == null && installed -> LockReason.UNKNOWN
                ai != null && isSystem && ai.flags and ApplicationInfo.FLAG_PERSISTENT != 0 -> LockReason.PERSISTENT
                ai != null && isSystem && ai.flags and ApplicationInfo.FLAG_HAS_CODE == 0 -> LockReason.RESOURCE_OVERLAY
                else -> null
            }
            // Adware pattern: a user-installed app that hides itself and holds strong powers.
            val signals = listOf(!hasIcon, overlay, admin, acc).count { it }
            val suspicious = lock == null && !isSystem && installed && sideloaded &&
                (signals >= 2 || (admin && !hasIcon) || (acc && !hasIcon))

            AppInfo(
                pkg = e.pkg,
                label = ai?.let { runCatching { it.loadLabel(pm).toString() }.getOrNull() } ?: e.pkg,
                versionCode = pi?.longVersionCode ?: 0L,
                versionName = pi?.versionName,
                uid = ai?.uid ?: -1,
                isSystem = isSystem,
                installed = installed,
                enabled = e.pkg !in disabledSet,
                installer = e.installer,
                sideloaded = sideloaded,
                dangerousPerms = dangerous,
                overlay = overlay,
                deviceAdmin = admin,
                accessibility = acc,
                hasLauncherIcon = hasIcon,
                batteryMah = ai?.uid?.let { battery[it] },
                dataBytes = ai?.uid?.let { data[it] },
                storageBytes = storage[e.pkg],
                lastUsed = lastUsed[e.pkg],
                lockReason = lock,
                suspicious = suspicious,
            )
        }.sortedBy { it.label.lowercase() }

        progress(5)
        ScanResult(
            time = System.currentTimeMillis(),
            deviceModel = model,
            osVersion = osVersion,
            androidVersion = Build.VERSION.RELEASE ?: "",
            apps = apps,
            settings = settings,
            warnings = warnings,
        )
    }
}
