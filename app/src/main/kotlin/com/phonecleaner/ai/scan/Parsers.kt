package com.phonecleaner.ai.scan

import org.json.JSONArray

/** Pure text parsers for shell output. Each is best effort and never throws. */
object Parsers {
    data class PmEntry(val pkg: String, val path: String, val installer: String?)

    /** Parses "pm list packages -f -i -u". */
    fun pmList(text: String): List<PmEntry> = text.lineSequence().mapNotNull { raw ->
        val line = raw.trim()
        if (!line.startsWith("package:")) return@mapNotNull null
        val body = line.removePrefix("package:")
        val instIdx = body.indexOf(" installer=")
        val main = (if (instIdx >= 0) body.substring(0, instIdx) else body).trim()
        val installer = if (instIdx >= 0) {
            body.substring(instIdx + " installer=".length).trim().split(Regex("\\s+")).firstOrNull()
                ?.takeIf { it.isNotEmpty() && it != "null" }
        } else null
        val eq = main.lastIndexOf('=')
        val pkg = (if (eq >= 0) main.substring(eq + 1) else main).trim().split(Regex("\\s+")).first()
        if (!isValidPackage(pkg)) return@mapNotNull null
        PmEntry(pkg, if (eq >= 0) main.substring(0, eq) else "", installer)
    }.toList()

    /** Parses plain "pm list packages" style output into a set of names. */
    fun pmNames(text: String): Set<String> = text.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("package:") }
        .map { it.removePrefix("package:").substringAfterLast('=').trim().split(Regex("\\s+")).first() }
        .filter { isValidPackage(it) }
        .toSet()

    fun isValidPackage(pkg: String) = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$").matches(pkg) || pkg == "android"

    /** Package names from "appops query-op" output (one per line). */
    fun plainPackages(text: String): Set<String> =
        text.lineSequence().map { it.trim() }.filter { isValidPackage(it) }.toSet()

    /** Active device admins from "dumpsys device_policy". */
    fun deviceAdmins(text: String): Set<String> =
        Regex("admin=ComponentInfo\\{([A-Za-z0-9_.]+)/").findAll(text).map { it.groupValues[1] }.toSet()

    /** Packages from a colon separated component list such as enabled_accessibility_services. */
    fun componentPackages(text: String): Set<String> = text.trim().split(':')
        .map { it.substringBefore('/').trim() }
        .filter { isValidPackage(it) }
        .toSet()

    private fun parseUid(s: String): Int? {
        s.toIntOrNull()?.let { return it }
        val m = Regex("u(\\d+)([asi])(\\d+)").matchEntire(s) ?: return null
        val user = m.groupValues[1].toInt()
        val n = m.groupValues[3].toInt()
        val appId = when (m.groupValues[2]) {
            "a" -> 10000 + n
            "s" -> n
            else -> return null
        }
        return user * 100000 + appId
    }

    /** Estimated power use per uid (mAh) from "dumpsys batterystats". */
    fun batteryByUid(text: String): Map<Int, Double> {
        val out = HashMap<Int, Double>()
        val re = Regex("^\\s+(?:UID|Uid)\\s+(\\S+?):\\s+([0-9.]+)")
        for (line in text.lineSequence()) {
            val m = re.find(line) ?: continue
            val uid = parseUid(m.groupValues[1]) ?: continue
            val mah = m.groupValues[2].toDoubleOrNull() ?: continue
            out[uid] = maxOf(out[uid] ?: 0.0, mah)
        }
        return out
    }

    /** Total bytes (received + sent) per uid from "dumpsys netstats detail". */
    fun dataByUid(text: String): Map<Int, Long> {
        val out = HashMap<Int, Long>()
        var inUid = false
        var cur = -1
        val uidRe = Regex("\\buid=(\\d+)\\s+set=")
        val bucketRe = Regex("\\brb=(\\d+)\\s.*\\btb=(\\d+)")
        for (line in text.lineSequence()) {
            val t = line.trim()
            if (t.endsWith("stats:") && !t.contains('=')) {
                inUid = t.startsWith("UID stats")
                cur = -1
                continue
            }
            if (!inUid) continue
            uidRe.find(t)?.let { cur = it.groupValues[1].toInt(); return@let }
            if (cur >= 0) bucketRe.find(t)?.let {
                out[cur] = (out[cur] ?: 0L) + it.groupValues[1].toLong() + it.groupValues[2].toLong()
            }
        }
        return out
    }

    /** Storage (app + data + cache) per package from "dumpsys diskstats". */
    fun storageByPackage(text: String): Map<String, Long> {
        fun arr(prefix: String): JSONArray? {
            val line = text.lineSequence().firstOrNull { it.trim().startsWith(prefix) } ?: return null
            return runCatching { JSONArray(line.substringAfter(':').trim()) }.getOrNull()
        }
        val names = arr("Package Names:") ?: return emptyMap()
        val parts = listOfNotNull(arr("App Sizes:"), arr("App Data Sizes:"), arr("Cache Sizes:"))
        if (parts.isEmpty()) return emptyMap()
        val out = HashMap<String, Long>()
        for (i in 0 until names.length()) {
            var sum = 0L
            for (p in parts) if (i < p.length()) sum += p.optLong(i, 0L)
            out[names.optString(i)] = sum
        }
        return out
    }

    /** Most recent "lastTimeUsed" per package from "dumpsys usagestats". */
    fun lastUsedByPackage(text: String): Map<String, String> {
        val out = HashMap<String, String>()
        val re = Regex("package=([A-Za-z0-9_.]+).*?lastTimeUsed=\"([^\"]+)\"")
        for (line in text.lineSequence()) {
            if (!line.contains("lastTimeUsed=")) continue
            val m = re.find(line) ?: continue
            val pkg = m.groupValues[1]
            val time = m.groupValues[2]
            if (time.startsWith("1970")) continue
            val old = out[pkg]
            if (old == null || time > old) out[pkg] = time
        }
        return out
    }
}
