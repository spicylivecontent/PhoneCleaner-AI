package com.phonecleaner.ai

import com.phonecleaner.ai.data.AnalysisEntity
import com.phonecleaner.ai.data.AppDao
import com.phonecleaner.ai.data.ChangeEntity
import com.phonecleaner.ai.data.LogEntity
import com.phonecleaner.ai.scan.AppInfo
import com.phonecleaner.ai.scan.LockReason
import com.phonecleaner.ai.shizuku.Shell
import com.phonecleaner.ai.shizuku.ShellResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Simulates the parts of an Android shell the app uses, with real pm/settings/appops semantics. */
class FakePhone : Shell {
    class Pkg(
        val name: String,
        val system: Boolean,
        val installer: String? = null,
        var installed: Boolean = true,
        var enabled: Boolean = true,
        /** Whether the APK still exists on the phone (user apps lose it when uninstalled). */
        var apkPresent: Boolean = true,
        var backedUp: Boolean = false,
        var uninstallFails: Boolean = false,
        var disableFails: Boolean = false,
        var backupFails: Boolean = false,
    )

    val pkgs = LinkedHashMap<String, Pkg>()
    val settings = HashMap<String, String>()
    val appops = HashMap<String, String>()
    val overlayAllowed = LinkedHashSet<String>()
    val dumps = HashMap<String, String>()
    val props = HashMap<String, String>()
    val roles = HashMap<String, String>()
    /** When set, reads of this setting or app-op fail (simulates a flaky shell). */
    var failReadsOf: String? = null
    var uidModes = HashMap<String, String>()
    /** Simulates Xiaomi refusing settings writes without "USB debugging (Security settings)". */
    var rejectPuts = false
    val commands = ArrayList<String>()
    /** Simulates Xiaomi blocking installs from the shell. */
    var shellInstallBlocked = false

    fun add(name: String, system: Boolean, installer: String? = null) = Pkg(name, system, installer).also { pkgs[name] = it }

    val mutating get() = commands.filterNot { READ_ONLY.containsMatchIn(it) }

    private fun ok(out: String = "") = ShellResult(0, out)
    private fun fail(out: String, code: Int = 1) = ShellResult(code, out)

    override suspend fun exec(command: String): ShellResult = synchronized(this) {
        commands += command
        run(command)
    }

    private fun unquote(v: String) = if (v.startsWith("'") && v.endsWith("'")) v.substring(1, v.length - 1).replace("'\\''", "'") else v

    private fun run(c: String): ShellResult {
        val backupDir = "/data/local/tmp/phonecleaner-backup/"
        return when {
            c == "id" -> ok("uid=2000(shell) gid=2000(shell)")
            c == "pm list packages -f -i -u --user 0" -> ok(pkgs.values.joinToString("\n") {
                "package:/${if (it.system) "system" else "data"}/app/~~x==/${it.name}-y==/base.apk=${it.name}  installer=${it.installer}"
            })
            c == "pm list packages --user 0" -> ok(pkgs.values.filter { it.installed }.joinToString("\n") { "package:${it.name}" })
            c == "pm list packages -s -u --user 0" -> ok(pkgs.values.filter { it.system }.joinToString("\n") { "package:${it.name}" })
            c == "pm list packages -d --user 0" -> ok(pkgs.values.filter { it.installed && !it.enabled }.joinToString("\n") { "package:${it.name}" })
            c == "appops query-op --user 0 SYSTEM_ALERT_WINDOW allow" -> ok(overlayAllowed.joinToString("\n"))
            c.startsWith("cmd role get-role-holders --user 0 ") -> ok(roles[c.substringAfterLast('.')] ?: "")
            c.startsWith("dumpsys ") -> ok(dumps[c.removePrefix("dumpsys ")] ?: "")
            c.startsWith("getprop ") -> ok(props[c.removePrefix("getprop ")] ?: "")
            failReadsOf != null && (c.startsWith("settings get ") || c.startsWith("appops get ")) && c.contains(failReadsOf!!) -> fail("Exception: transient", 255)
            c.startsWith("settings get ") -> ok(settings[c.removePrefix("settings get ")] ?: "null")
            c.startsWith("settings put ") -> {
                val parts = c.removePrefix("settings put ").split(" ", limit = 3)
                if (parts.size != 3) return fail("usage")
                if (rejectPuts) return fail("java.lang.SecurityException: Permission denial: writing to settings requires:android.permission.WRITE_SECURE_SETTINGS", 255)
                settings["${parts[0]} ${parts[1]}"] = unquote(parts[2])
                ok()
            }
            c.startsWith("settings delete ") -> {
                settings.remove(c.removePrefix("settings delete "))
                ok("Deleted 1 rows")
            }
            c.startsWith("appops get --user 0 ") -> {
                val (pkg, op) = c.removePrefix("appops get --user 0 ").split(" ")
                val uidLine = uidModes["$pkg $op"]?.let { "Uid mode: $op: $it\n" } ?: ""
                ok(uidLine + (appops["$pkg $op"]?.let { "$op: $it; time=+1d2h3m4s ago" } ?: if (uidLine.isEmpty()) "No operations." else ""))
            }
            c.startsWith("appops set --user 0 ") -> {
                val (pkg, op, mode) = c.removePrefix("appops set --user 0 ").split(" ")
                if (mode !in setOf("allow", "deny", "ignore", "default", "foreground")) return fail("Error: Mode $mode is not valid")
                if (mode == "default") appops.remove("$pkg $op") else appops["$pkg $op"] = mode
                ok()
            }
            c.startsWith("pm uninstall -k --user 0 ") -> {
                val p = pkgs[c.substringAfterLast(' ')] ?: return fail("Failure [not installed for 0]")
                if (!p.installed) return fail("Failure [not installed for 0]")
                if (p.uninstallFails) return fail("Failure [DELETE_FAILED_INTERNAL_ERROR]")
                p.installed = false
                if (!p.system) p.apkPresent = false
                ok("Success")
            }
            c.startsWith("pm install-existing --user 0 ") -> {
                val name = c.substringAfterLast(' ')
                val p = pkgs[name]
                if (p == null || !p.apkPresent) return fail("android.content.pm.PackageManager\$NameNotFoundException: Package $name doesn't exist")
                p.installed = true
                ok("Package $name installed for user: 0")
            }
            c.startsWith("pm disable-user --user 0 ") -> {
                val p = pkgs[c.substringAfterLast(' ')] ?: return fail("Error: unknown package")
                if (p.disableFails) return fail("java.lang.SecurityException: Cannot disable miui system apps")
                p.enabled = false
                ok("Package ${p.name} new state: disabled-user")
            }
            c.startsWith("pm enable --user 0 ") -> {
                val p = pkgs[c.substringAfterLast(' ')] ?: return fail("Error: unknown package")
                p.enabled = true
                ok("Package ${p.name} new state: enabled")
            }
            // APK backup script
            c.startsWith("rm -rf $backupDir") && c.contains("mkdir -p") -> {
                val p = pkgs[c.removePrefix("rm -rf $backupDir").substringBefore(';')] ?: return fail("", 1)
                if (p.backupFails || !p.apkPresent) return fail("cp: Permission denied")
                p.backedUp = true
                ok()
            }
            // Session install from the backup
            c.startsWith("ls $backupDir") && c.contains("pm install-create") -> {
                val p = pkgs[c.removePrefix("ls $backupDir").substringBefore('/')] ?: return fail("", 3)
                if (!p.backedUp) return fail("", 3)
                if (shellInstallBlocked) return fail("Success: streamed 100 bytes\nFailure [INSTALL_FAILED_USER_RESTRICTED: Install canceled by user]")
                p.installed = true
                p.apkPresent = true
                ok("Success: streamed 100 bytes\nSuccess")
            }
            c.startsWith("rm -rf $backupDir") -> {
                pkgs[c.removePrefix("rm -rf $backupDir")]?.backedUp = false
                ok()
            }
            else -> fail("unknown command: $c", 127)
        }
    }

    companion object {
        /** Commands that cannot change anything on the phone. */
        val READ_ONLY = Regex("^(id$|pm list packages |appops query-op |appops get |dumpsys |settings get |getprop |cmd role get-role-holders )")
    }
}

class FakeDao : AppDao {
    val analysisFlow = MutableStateFlow<List<AnalysisEntity>>(emptyList())
    val changeList = ArrayList<ChangeEntity>()
    val logList = ArrayList<LogEntity>()
    override fun analyses(): Flow<List<AnalysisEntity>> = analysisFlow
    override suspend fun analysesNow() = analysisFlow.value
    override suspend fun upsertAnalyses(items: List<AnalysisEntity>) {
        analysisFlow.value = analysisFlow.value.filter { old -> items.none { it.pkg == old.pkg } } + items
    }
    override suspend fun clearAnalyses() { analysisFlow.value = emptyList() }
    override fun changes(): Flow<List<ChangeEntity>> = MutableStateFlow(changeList.reversed())
    override suspend fun activeChanges() = changeList.filter { !it.reverted }.reversed()
    override suspend fun insertChange(c: ChangeEntity): Long {
        val id = ++nextId
        changeList += c.copy(id = id)
        return id
    }
    override suspend fun markReverted(id: Long) {
        val i = changeList.indexOfFirst { it.id == id }
        changeList[i] = changeList[i].copy(reverted = true)
    }
    override suspend fun deleteChange(id: Long) { changeList.removeAll { it.id == id } }
    override suspend fun updateNewValue(id: Long, value: String) {
        val i = changeList.indexOfFirst { it.id == id }
        changeList[i] = changeList[i].copy(newValue = value)
    }
    override suspend fun activeChangeFor(kind: String, target: String, detail: String) =
        changeList.firstOrNull { !it.reverted && it.kind == kind && it.target == target && it.detail == detail }
    private var nextId = 0L
    override fun logs(): Flow<List<LogEntity>> = MutableStateFlow(logList.reversed())
    override suspend fun insertLog(l: LogEntity) { logList += l }
}

fun appInfo(
    pkg: String,
    system: Boolean = true,
    lock: LockReason? = null,
    overlay: Boolean = false,
    suspicious: Boolean = false,
    installed: Boolean = true,
) = AppInfo(
    pkg = pkg, label = pkg.substringAfterLast('.'), versionCode = 1, versionName = "1.0", uid = 10100,
    isSystem = system, installed = installed, enabled = true, installer = null, sideloaded = !system,
    dangerousPerms = emptyList(), overlay = overlay, deviceAdmin = false, accessibility = false,
    hasLauncherIcon = true, batteryMah = null, dataBytes = null, storageBytes = null, lastUsed = null,
    lockReason = lock, suspicious = suspicious,
)
