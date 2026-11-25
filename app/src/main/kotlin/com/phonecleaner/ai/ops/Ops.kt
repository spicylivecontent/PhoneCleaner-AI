package com.phonecleaner.ai.ops

import com.phonecleaner.ai.BuildConfig
import com.phonecleaner.ai.data.AppDao
import com.phonecleaner.ai.data.ChangeEntity
import com.phonecleaner.ai.data.LogEntity
import com.phonecleaner.ai.scan.AppInfo
import com.phonecleaner.ai.scan.Critical
import com.phonecleaner.ai.scan.Parsers
import com.phonecleaner.ai.shizuku.Shell
import com.phonecleaner.ai.shizuku.ShellResult
import com.phonecleaner.ai.shizuku.ShizukuManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One planned change, shown to the user before anything runs. */
data class Planned(
    val kind: String,
    val target: String,
    val label: String,
    val detail: String = "",
    val newValue: String = "",
) {
    /** The exact command that will be run. */
    val command: String
        get() = when (kind) {
            Ops.UNINSTALL -> "pm uninstall -k --user 0 $target"
            Ops.SETTING -> "settings put $target ${Ops.q(newValue)}"
            Ops.APPOP -> "appops set --user 0 $target $detail $newValue"
            else -> ""
        }
}

/**
 * Everything that changes the phone goes through this class. Every change
 * is logged and recorded with its original value so it can be undone.
 * Only shell-level commands are used; nothing here needs or asks for root.
 */
class Ops(
    private val backupDir: File,
    private val dao: AppDao,
    private val shell: Shell = ShizukuManager,
) {

    private suspend fun sh(cmd: String): ShellResult {
        val r = try {
            shell.exec(cmd)
        } catch (t: Throwable) {
            ShellResult(-1, t.message ?: "shell unavailable")
        }
        dao.insertLog(LogEntity(time = System.currentTimeMillis(), command = cmd, result = r.output.take(2000), ok = r.ok))
        return r
    }

    private suspend fun note(text: String, ok: Boolean = true) {
        dao.insertLog(LogEntity(time = System.currentTimeMillis(), command = "#", result = text, ok = ok))
    }

    suspend fun refuse(pkg: String) = note("refused to touch protected package $pkg", ok = false)

    /** Packages that must never be touched, whatever the caller passes in. */
    private fun forbidden(pkg: String) =
        !Parsers.isValidPackage(pkg) || Critical.isCore(pkg) || pkg == BuildConfig.APPLICATION_ID

    /** Reads the current value of "namespace key"; "null" when unset; null (no value) when the read failed. */
    suspend fun readSetting(target: String): String? {
        val r = sh("settings get $target")
        return if (r.ok) r.output.trim().ifEmpty { "null" } else null
    }

    /** The package-level mode of an app-op; "default" when not set; null when the read failed. */
    suspend fun readAppOp(pkg: String, op: String): String? {
        val r = sh("appops get --user 0 $pkg $op")
        if (!r.ok) return null
        // "Uid mode: ..." lines describe the uid, not the package, and must not be written back as a package mode.
        val own = r.output.lineSequence().filterNot { it.trim().startsWith("Uid mode") }.joinToString("\n")
        return Regex("\\b$op:\\s*([a-z_]+)").find(own)?.groupValues?.get(1) ?: "default"
    }

    private suspend fun current(p: Planned): String? =
        if (p.kind == SETTING) readSetting(p.target) else readAppOp(p.target, p.detail)

    private fun valid(p: Planned): Boolean = when (p.kind) {
        SETTING -> Regex("^(global|secure|system) [A-Za-z0-9_.]+$").matches(p.target)
        APPOP -> !forbidden(p.target) && Regex("^[A-Z_]+$").matches(p.detail) && p.newValue in APPOP_MODES
        UNINSTALL -> !forbidden(p.target)
        else -> false
    }

    /**
     * Writes the backup file before any change: the packages about to be
     * removed and the original value of every setting about to change.
     * Fails (and so stops the whole plan) if an original value cannot be read.
     */
    suspend fun writeBackup(plan: List<Planned>): File {
        val now = System.currentTimeMillis()
        val packages = JSONArray()
        val settings = JSONObject()
        val appops = JSONArray()
        for (p in plan) {
            if (!valid(p)) continue
            when (p.kind) {
                UNINSTALL -> packages.put(JSONObject().put("package", p.target).put("label", p.label))
                SETTING -> settings.put(p.target, readSetting(p.target) ?: throw IllegalStateException("cannot read ${p.target}"))
                APPOP -> appops.put(
                    JSONObject().put("package", p.target).put("op", p.detail)
                        .put("mode", readAppOp(p.target, p.detail) ?: throw IllegalStateException("cannot read ${p.detail} for ${p.target}"))
                )
            }
        }
        val json = JSONObject()
            .put("createdAt", now)
            .put("packages", packages)
            .put("originalSettings", settings)
            .put("originalAppOps", appops)
        val file = withContext(Dispatchers.IO) {
            backupDir.mkdirs()
            File(backupDir, "backup-$now.json").also { it.writeText(json.toString(2)) }
        }
        note("backup saved: ${file.absolutePath}")
        return file
    }

    /** Copies a user app's APK files aside so it can be reinstalled later. */
    private suspend fun backupApk(pkg: String): Boolean {
        val dir = "$APK_BACKUP_DIR/$pkg"
        val r = sh(
            "rm -rf $dir; mkdir -p $dir || exit 1; " +
                "for f in \$(pm path --user 0 $pkg | sed 's/^package://'); do cp \"\$f\" $dir/ || exit 1; done; " +
                "ls $dir/*.apk >/dev/null 2>&1"
        )
        return r.ok
    }

    /**
     * Removes one package for user 0. Returns the recorded change kind, or null on failure.
     * The undo record is written before the command runs, so it exists even if the app dies mid-way.
     */
    suspend fun removePackage(app: AppInfo): String? {
        val pkg = app.pkg
        if (forbidden(pkg) || app.locked) {
            note("refused to touch protected package $pkg", ok = false)
            return null
        }
        val now = System.currentTimeMillis()
        // System apps can always come back with "pm install-existing". Apps the user
        // installed need their APK saved and a proven way to install it again;
        // without that they are only disabled.
        val canUninstall = app.isSystem || (backupApk(pkg) && canReinstall(pkg))
        if (canUninstall) {
            val id = dao.insertChange(ChangeEntity(time = now, kind = UNINSTALL, target = pkg, label = app.label, detail = "", originalValue = "installed", newValue = "uninstalled"))
            val r = sh("pm uninstall -k --user 0 $pkg")
            if (r.output.lineSequence().any { it.trim() == "Success" }) return UNINSTALL
            dao.deleteChange(id)
        } else {
            note("no proven way to reinstall $pkg on this phone; disabling instead of uninstalling", ok = false)
        }
        val id = dao.insertChange(ChangeEntity(time = now, kind = DISABLE, target = pkg, label = app.label, detail = "", originalValue = "enabled", newValue = "disabled-user"))
        val d = sh("pm disable-user --user 0 $pkg")
        if (d.output.contains("new state: disabled-user")) return DISABLE
        dao.deleteChange(id)
        return null
    }

    /**
     * Applies a setting or app-op change. The original value is read and recorded
     * first; if it cannot be read, nothing is changed. A target that this app has
     * already changed keeps its single record with the true original value.
     */
    suspend fun apply(p: Planned): Boolean {
        if (p.kind != SETTING && p.kind != APPOP) return false
        if (!valid(p)) return false
        val before = current(p) ?: return false
        if (before == p.newValue) return true
        val existing = dao.activeChangeFor(p.kind, p.target, p.detail)
        val id = if (existing != null) {
            dao.updateNewValue(existing.id, p.newValue)
            existing.id
        } else {
            dao.insertChange(ChangeEntity(time = System.currentTimeMillis(), kind = p.kind, target = p.target, label = p.label, detail = p.detail, originalValue = before, newValue = p.newValue))
        }
        val r = sh(p.command)
        val after = current(p)
        if (after == before) {
            // Nothing changed: drop the record again.
            if (existing != null) dao.updateNewValue(id, existing.newValue) else dao.deleteChange(id)
            return false
        }
        // Changed (or unknown): the record stays so it can be undone.
        return r.ok && (after == p.newValue || after != null)
    }

    /** Undoes one recorded change. */
    suspend fun revert(c: ChangeEntity): Boolean {
        val ok = when (c.kind) {
            UNINSTALL -> {
                if (!Parsers.isValidPackage(c.target)) return false
                val r = sh("pm install-existing --user 0 ${c.target}")
                if (r.output.contains("installed for user")) true else reinstallFromBackup(c.target)
            }
            DISABLE -> Parsers.isValidPackage(c.target) &&
                sh("pm enable --user 0 ${c.target}").output.contains("new state: enabled")
            SETTING -> {
                val r = sh(revertCommand(c))
                r.ok && readSetting(c.target) == c.originalValue
            }
            APPOP -> Parsers.isValidPackage(c.target) && c.originalValue in APPOP_MODES &&
                sh(revertCommand(c)).ok && readAppOp(c.target, c.detail) == c.originalValue
            else -> false
        }
        if (ok) dao.markReverted(c.id)
        return ok
    }

    /** Whether this phone lets the shell install APKs. Checked once, before the first uninstall of a user app. */
    private var shellInstallWorks: Boolean? = null

    /**
     * Proves the undo path before anything is removed: reinstalls the app's own
     * APK over itself. Some phones (Xiaomi without "Install via USB") block this.
     */
    private suspend fun canReinstall(pkg: String): Boolean =
        shellInstallWorks ?: installFromBackup(pkg).also { shellInstallWorks = it }

    private suspend fun installFromBackup(pkg: String): Boolean {
        val dir = "$APK_BACKUP_DIR/$pkg"
        val r = sh(
            "ls $dir/*.apk >/dev/null 2>&1 || exit 3; " +
                "S=\$(pm install-create -r --user 0 | sed 's/[^0-9]//g'); [ -n \"\$S\" ] || exit 4; " +
                "for f in $dir/*.apk; do pm install-write -S \$(stat -c %s \"\$f\") \$S \$(basename \"\$f\") \"\$f\" || { pm install-abandon \$S >/dev/null 2>&1; exit 5; }; done; " +
                "pm install-commit \$S || { pm install-abandon \$S >/dev/null 2>&1; exit 6; }"
        )
        return r.ok && r.output.lineSequence().lastOrNull { it.isNotBlank() }?.trim()?.startsWith("Success") == true
    }

    private suspend fun reinstallFromBackup(pkg: String): Boolean {
        val ok = installFromBackup(pkg)
        if (ok) sh("rm -rf $APK_BACKUP_DIR/$pkg")
        return ok
    }

    companion object {
        const val UNINSTALL = "UNINSTALL"
        const val DISABLE = "DISABLE"
        const val SETTING = "SETTING"
        const val APPOP = "APPOP"
        const val APK_BACKUP_DIR = "/data/local/tmp/phonecleaner-backup"

        val APPOP_MODES = setOf("allow", "deny", "ignore", "default", "foreground")

        /** The exact command that undoes a recorded change (shown before it runs). */
        fun revertCommand(c: ChangeEntity): String = when (c.kind) {
            UNINSTALL -> "pm install-existing --user 0 ${c.target}"
            DISABLE -> "pm enable --user 0 ${c.target}"
            SETTING -> if (c.originalValue == "null") "settings delete ${c.target}" else "settings put ${c.target} ${q(c.originalValue)}"
            APPOP -> "appops set --user 0 ${c.target} ${c.detail} ${c.originalValue}"
            else -> ""
        }

        /** Single-quotes a value for the shell. */
        fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
    }
}
