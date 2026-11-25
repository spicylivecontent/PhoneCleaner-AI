package com.phonecleaner.ai.ops

import android.content.Context
import com.phonecleaner.ai.BuildConfig
import com.phonecleaner.ai.data.AppDao
import com.phonecleaner.ai.data.ChangeEntity
import com.phonecleaner.ai.data.LogEntity
import com.phonecleaner.ai.scan.AppInfo
import com.phonecleaner.ai.scan.Critical
import com.phonecleaner.ai.scan.Parsers
import com.phonecleaner.ai.shizuku.ShellResult
import com.phonecleaner.ai.shizuku.ShizukuManager
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
class Ops(private val context: Context, private val dao: AppDao) {

    private suspend fun sh(cmd: String): ShellResult {
        val r = try {
            ShizukuManager.exec(cmd)
        } catch (t: Throwable) {
            ShellResult(-1, t.message ?: "shell unavailable")
        }
        dao.insertLog(LogEntity(time = System.currentTimeMillis(), command = cmd, result = r.output.take(2000), ok = r.ok))
        return r
    }

    private suspend fun note(text: String, ok: Boolean = true) {
        dao.insertLog(LogEntity(time = System.currentTimeMillis(), command = "#", result = text, ok = ok))
    }

    /** Packages that must never be touched, whatever the caller passes in. */
    private fun forbidden(pkg: String) =
        !Parsers.isValidPackage(pkg) || Critical.isCore(pkg) || pkg == BuildConfig.APPLICATION_ID

    /** Reads the current value of "namespace key"; "null" when unset. */
    suspend fun readSetting(target: String): String = sh("settings get $target").output.trim().ifEmpty { "null" }

    suspend fun readAppOp(pkg: String, op: String): String {
        val out = sh("appops get --user 0 $pkg $op").output
        return Regex("$op:\\s*([a-z_]+)").find(out)?.groupValues?.get(1) ?: "default"
    }

    /**
     * Writes the backup file before any change: the packages about to be
     * removed and the original value of every setting about to change.
     */
    suspend fun writeBackup(plan: List<Planned>): File {
        val dir = File(context.filesDir, "backups").apply { mkdirs() }
        val now = System.currentTimeMillis()
        val packages = JSONArray()
        val settings = JSONObject()
        val appops = JSONArray()
        for (p in plan) when (p.kind) {
            UNINSTALL -> packages.put(JSONObject().put("package", p.target).put("label", p.label))
            SETTING -> settings.put(p.target, readSetting(p.target))
            APPOP -> appops.put(
                JSONObject().put("package", p.target).put("op", p.detail).put("mode", readAppOp(p.target, p.detail))
            )
        }
        val json = JSONObject()
            .put("createdAt", now)
            .put("packages", packages)
            .put("originalSettings", settings)
            .put("originalAppOps", appops)
        val file = File(dir, "backup-$now.json")
        file.writeText(json.toString(2))
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

    /** Removes one package for user 0. Returns the recorded change kind, or null on failure. */
    suspend fun removePackage(app: AppInfo): String? {
        val pkg = app.pkg
        if (forbidden(pkg) || app.locked) {
            note("refused to touch protected package $pkg", ok = false)
            return null
        }
        val now = System.currentTimeMillis()
        // System apps can always come back with "pm install-existing". Apps the user
        // installed need their APK saved first; without it they are only disabled.
        val canUninstall = app.isSystem || backupApk(pkg)
        if (canUninstall) {
            val r = sh("pm uninstall -k --user 0 $pkg")
            if (r.output.contains("Success")) {
                dao.insertChange(ChangeEntity(time = now, kind = UNINSTALL, target = pkg, label = app.label, detail = "", originalValue = "installed", newValue = "uninstalled"))
                return UNINSTALL
            }
        } else {
            note("could not back up the APK for $pkg; disabling instead of uninstalling", ok = false)
        }
        val d = sh("pm disable-user --user 0 $pkg")
        if (d.output.contains("disabled-user")) {
            dao.insertChange(ChangeEntity(time = now, kind = DISABLE, target = pkg, label = app.label, detail = "", originalValue = "enabled", newValue = "disabled-user"))
            return DISABLE
        }
        return null
    }

    /** Applies a setting or app-op change, recording the original value first. */
    suspend fun apply(p: Planned): Boolean {
        val now = System.currentTimeMillis()
        when (p.kind) {
            SETTING -> {
                if (!Regex("^(global|secure|system) [A-Za-z0-9_.]+$").matches(p.target)) return false
                val original = readSetting(p.target)
                if (original == p.newValue) return true
                val r = sh(p.command)
                if (!r.ok || readSetting(p.target) != p.newValue) return false
                dao.insertChange(ChangeEntity(time = now, kind = SETTING, target = p.target, label = p.label, detail = "", originalValue = original, newValue = p.newValue))
                return true
            }
            APPOP -> {
                if (forbidden(p.target) || !Regex("^[A-Z_]+$").matches(p.detail)) return false
                val original = readAppOp(p.target, p.detail)
                if (original == p.newValue) return true
                val r = sh(p.command)
                if (!r.ok) return false
                dao.insertChange(ChangeEntity(time = now, kind = APPOP, target = p.target, label = p.label, detail = p.detail, originalValue = original, newValue = p.newValue))
                return true
            }
        }
        return false
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
                sh("pm enable --user 0 ${c.target}").output.contains("enabled")
            SETTING -> {
                val r = if (c.originalValue == "null") sh("settings delete ${c.target}")
                else sh("settings put ${c.target} ${q(c.originalValue)}")
                r.ok && readSetting(c.target) == c.originalValue
            }
            APPOP -> sh("appops set --user 0 ${c.target} ${c.detail} ${c.originalValue}").ok
            else -> false
        }
        if (ok) dao.markReverted(c.id)
        return ok
    }

    private suspend fun reinstallFromBackup(pkg: String): Boolean {
        val dir = "$APK_BACKUP_DIR/$pkg"
        val r = sh(
            "ls $dir/*.apk >/dev/null 2>&1 || exit 3; " +
                "S=\$(pm install-create -r --user 0 | sed 's/[^0-9]//g'); [ -n \"\$S\" ] || exit 4; " +
                "for f in $dir/*.apk; do pm install-write -S \$(stat -c %s \"\$f\") \$S \$(basename \"\$f\") \"\$f\" || exit 5; done; " +
                "pm install-commit \$S"
        )
        val ok = r.output.contains("Success")
        if (ok) sh("rm -rf $dir")
        return ok
    }

    companion object {
        const val UNINSTALL = "UNINSTALL"
        const val DISABLE = "DISABLE"
        const val SETTING = "SETTING"
        const val APPOP = "APPOP"
        const val APK_BACKUP_DIR = "/data/local/tmp/phonecleaner-backup"

        /** Single-quotes a value for the shell. */
        fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
    }
}
