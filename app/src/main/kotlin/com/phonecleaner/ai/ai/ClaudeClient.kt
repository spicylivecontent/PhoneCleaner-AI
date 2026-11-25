package com.phonecleaner.ai.ai

import com.phonecleaner.ai.data.AnalysisEntity
import com.phonecleaner.ai.scan.AppInfo
import com.phonecleaner.ai.scan.ScanResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Models offered in settings, with first-party API prices in USD per million tokens. */
enum class ClaudeModel(val id: String, val title: String, val inPrice: Double, val outPrice: Double, val fallbacks: Boolean) {
    OPUS("claude-opus-5-5", "Claude Opus 5.5", 4.0, 20.0, true),
    SONNET("claude-sonnet-5-5", "Claude Sonnet 5.5", 2.0, 10.0, true),
    HAIKU("claude-haiku-5-5", "Claude Haiku 5.5", 0.10, 0.50, false);

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id } ?: OPUS
    }
}

data class Usage(val inputTokens: Long = 0, val outputTokens: Long = 0, val searches: Long = 0) {
    operator fun plus(o: Usage) = Usage(inputTokens + o.inputTokens, outputTokens + o.outputTokens, searches + o.searches)

    fun costUsd(m: ClaudeModel) =
        inputTokens / 1e6 * m.inPrice + outputTokens / 1e6 * m.outPrice + searches * SEARCH_PRICE

    companion object {
        /** Web search costs 10 USD per 1,000 searches. */
        const val SEARCH_PRICE = 0.01

        /** Rough guess for one batch of about 15 packages, used before any real numbers exist. */
        val TYPICAL_BATCH = Usage(inputTokens = 45_000, outputTokens = 5_000, searches = 6)
    }
}

/** [usage] is what the failed request still cost, so the displayed total stays honest. */
class ClaudeException(val kind: Kind, message: String, val usage: Usage = Usage()) : Exception(message) {
    enum class Kind { BAD_KEY, REFUSED, BAD_RESPONSE, HTTP, NETWORK }
}

data class BatchResult(val items: List<AnalysisEntity>, val usage: Usage)

/**
 * Minimal Messages API client (raw HTTPS). Uses the server-side web search
 * tool so Claude can look up unfamiliar packages before giving a verdict.
 */
class ClaudeClient(
    private val apiKey: String,
    private val model: ClaudeModel,
    private val baseUrl: String = "https://api.anthropic.com",
) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        // Non-streaming requests with several web searches can take minutes.
        .readTimeout(15, TimeUnit.MINUTES)
        .build()

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        })
    }

    private fun request(url: String) = Request.Builder()
        .url(url)
        .header("x-api-key", apiKey)
        .header("anthropic-version", API_VERSION)

    /** Free check that the key and model work. Returns null when fine, else an error text. */
    suspend fun testKey(): String? = try {
        http.newCall(request("$baseUrl/v1/models/${model.id}").get().build()).await().use { r ->
            if (r.isSuccessful) null else "${r.code}: ${errorMessage(r.body.string())}"
        }
    } catch (e: IOException) {
        e.message ?: "network error"
    }

    private fun errorMessage(body: String): String =
        runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrDefault(body.take(300))

    private suspend fun post(body: JSONObject): JSONObject {
        var attempt = 0
        while (true) {
            attempt++
            val builder = request("$baseUrl/v1/messages")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
            if (model.fallbacks) builder.header("anthropic-beta", FALLBACK_BETA)
            val (code, text, retryAfter) = try {
                http.newCall(builder.build()).await().use { r ->
                    Triple(r.code, r.body.string(), r.header("retry-after")?.toLongOrNull())
                }
            } catch (e: IOException) {
                // Only retry when the request cannot have reached the API. A dropped
                // connection after that may already have been billed, so it is not repeated.
                val notSent = e is java.net.ConnectException || e is java.net.UnknownHostException
                if (!notSent || attempt >= 3) throw ClaudeException(ClaudeException.Kind.NETWORK, e.message ?: e.javaClass.simpleName)
                delay(3000L * attempt)
                continue
            }
            when {
                code in 200..299 -> return runCatching { JSONObject(text) }.getOrElse {
                    throw ClaudeException(ClaudeException.Kind.BAD_RESPONSE, "invalid JSON from API")
                }
                code == 401 || code == 403 -> throw ClaudeException(ClaudeException.Kind.BAD_KEY, errorMessage(text))
                (code == 429 || code >= 500) && attempt < 4 -> delay((retryAfter ?: (5L * attempt)).coerceIn(1, 60) * 1000)
                else -> throw ClaudeException(ClaudeException.Kind.HTTP, "$code: ${errorMessage(text)}")
            }
        }
    }

    private fun usageOf(resp: JSONObject): Usage {
        val u = resp.optJSONObject("usage") ?: return Usage()
        val input = u.optLong("input_tokens") + u.optLong("cache_creation_input_tokens") + u.optLong("cache_read_input_tokens")
        val searches = u.optJSONObject("server_tool_use")?.optLong("web_search_requests") ?: 0L
        return Usage(input, u.optLong("output_tokens"), searches)
    }

    suspend fun analyze(batch: List<AppInfo>, scan: ScanResult, swedish: Boolean): BatchResult {
        val messages = JSONArray().put(
            JSONObject().put("role", "user").put("content", Prompts.user(batch, scan))
        )
        var usage = Usage()
        var directSearch = false
        var newestTool = true
        var resp: JSONObject
        var rounds = 0
        while (true) {
            val tool = JSONObject().put("name", "web_search").put("max_uses", 10)
            if (newestTool) {
                // Newest version: search results the model already filtered are left out of the response.
                tool.put("type", "web_search_20260318").put("response_inclusion", "excluded")
            } else {
                tool.put("type", "web_search_20260209")
            }
            if (directSearch) tool.put("allowed_callers", JSONArray().put("direct"))
            val body = JSONObject()
                .put("model", model.id)
                .put("max_tokens", MAX_TOKENS)
                .put("system", Prompts.system(swedish))
                .put("output_config", JSONObject().put("effort", "medium"))
                .put("tools", JSONArray().put(tool))
                .put("messages", messages)
            // If a safety classifier declines, let the API retry on its recommended fallback model.
            if (model.fallbacks) body.put("fallbacks", "default")

            resp = try {
                post(body)
            } catch (e: ClaudeException) {
                val msg = e.message.orEmpty()
                if (e.kind == ClaudeException.Kind.HTTP && msg.startsWith("400")) {
                    // Models without programmatic tool calling must call web search directly.
                    if (!directSearch && msg.contains("allowed_callers")) {
                        directSearch = true
                        continue
                    }
                    // A model or account that does not accept the newest tool version yet.
                    if (newestTool && rounds == 0 && (msg.contains("web_search_20260318") || msg.contains("response_inclusion"))) {
                        newestTool = false
                        continue
                    }
                }
                throw ClaudeException(e.kind, msg, usage)
            }
            usage += usageOf(resp)
            val stop = resp.optString("stop_reason")
            if (stop == "refusal") throw ClaudeException(ClaudeException.Kind.REFUSED, "the model declined this batch", usage)
            if (stop == "pause_turn") {
                if (++rounds >= MAX_ROUNDS) throw ClaudeException(ClaudeException.Kind.BAD_RESPONSE, "the search did not finish", usage)
                // Long search turn: send the paused assistant message back unchanged to continue.
                messages.put(JSONObject().put("role", "assistant").put("content", resp.getJSONArray("content")))
                continue
            }
            break
        }

        val content = resp.optJSONArray("content") ?: JSONArray()
        val text = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            if (block.optString("type") == "text") text.append(block.optString("text"))
        }
        val wanted = batch.associateBy { it.pkg }
        val items = ResponseParser.parse(text.toString(), model.id).filter { it.pkg in wanted }
        if (items.isEmpty()) {
            val why = if (resp.optString("stop_reason") == "max_tokens") "the answer was cut off" else "no usable JSON in the answer"
            throw ClaudeException(ClaudeException.Kind.BAD_RESPONSE, why, usage)
        }
        return BatchResult(items, usage)
    }

    companion object {
        private const val API_VERSION = "2026-10-21"
        private const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
        private const val MAX_TOKENS = 16000
        private const val MAX_ROUNDS = 6
    }
}

object ResponseParser {
    /** Extracts the JSON array of verdicts from the model's final text. */
    fun parse(text: String, model: String, now: Long = System.currentTimeMillis()): List<AnalysisEntity> {
        val arr = extractArray(text) ?: return emptyList()
        val out = ArrayList<AnalysisEntity>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val pkg = o.optString("package")
            val verdict = o.optString("verdict").lowercase()
            if (pkg.isEmpty() || verdict !in setOf("safe", "caution", "keep")) continue
            val src = o.optJSONArray("sources")
            val sources = if (src == null) emptyList() else List(src.length()) { src.optString(it) }
                .filter { it.startsWith("http://") || it.startsWith("https://") }
            out += AnalysisEntity(
                pkg = pkg,
                appName = o.optString("appName", pkg),
                whatItDoes = o.optString("whatItDoes"),
                verdict = verdict,
                risk = o.optString("risk"),
                isAdOrTracker = o.optBoolean("isAdOrTracker", false),
                suspectedMalware = o.optBoolean("suspectedMalware", false),
                sources = sources.joinToString("\n"),
                model = model,
                analyzedAt = now,
            )
        }
        return out
    }
}

/**
 * Finds the first JSON array of objects in [text] by matching brackets (ignoring
 * those inside strings), so text or brackets after the array do not matter.
 * A cut-off array is repaired by keeping its complete objects.
 */
internal fun extractArray(text: String): JSONArray? {
    val start = Regex("\\[\\s*\\{").find(text)?.range?.first ?: return null
    var depth = 0
    var inString = false
    var escaped = false
    var lastObjectEnd = -1
    for (i in start until text.length) {
        val ch = text[i]
        if (inString) {
            when {
                escaped -> escaped = false
                ch == '\\' -> escaped = true
                ch == '"' -> inString = false
            }
            continue
        }
        when (ch) {
            '"' -> inString = true
            '[', '{' -> depth++
            ']', '}' -> {
                depth--
                if (depth == 1 && ch == '}') lastObjectEnd = i
                if (depth == 0) return runCatching { JSONArray(text.substring(start, i + 1)) }.getOrNull()
            }
        }
    }
    // Truncated: keep everything up to the last complete object.
    if (lastObjectEnd < 0) return null
    return runCatching { JSONArray(text.substring(start, lastObjectEnd + 1) + "]") }.getOrNull()
}

object Prompts {
    fun system(swedish: Boolean): String {
        val lang = if (swedish) "Swedish" else "English"
        return """
You are an Android security and debloating expert. The user will remove packages from their own phone with "pm uninstall -k --user 0" (reversible with "pm install-existing"), based on your verdicts. A wrong "safe" can break the phone, so be careful and conservative.

For every package in the list, decide:
- "safe": removing it for the user loses nothing important. Typical: vendor bloatware, ad/analytics/tracking services, promotional stubs, duplicate vendor apps.
- "caution": removal is possible but some feature the user may want stops working, or you are not sure what it does.
- "keep": removal can cause boot loops, crashes, loss of calls/SMS/network/camera/updates/security features, or breaks other system components.
When unsure between two verdicts, choose the more careful one.

Apps the user installed themselves from an app store are their own choice: give them "caution" (risk: the user loses the app) unless they are ad/tracker-only apps or look like malware.

Use web search to check packages you do not know with certainty, especially vendor-specific system packages and anything flagged as suspicious. Search for the exact package name. Prefer debloat lists and discussions for this exact phone and OS version. Do not spend searches on packages you already know well.

Set "suspectedMalware" to true only with real indications: known malware/adware family, or a sideloaded app that hides its icon and holds overlay, device-admin or accessibility powers without a legitimate reason.

Reply with ONLY a JSON array, no markdown fences and no text before or after. One object per package, same order as the input, with exactly these fields:
{"package": string, "appName": string, "whatItDoes": string (one sentence, in $lang), "verdict": "safe" | "caution" | "keep", "risk": string (what stops working if removed, in $lang; say so if nothing does), "isAdOrTracker": boolean, "suspectedMalware": boolean, "sources": array of URL strings you actually relied on (empty array if none)}
        """.trim()
    }

    fun user(batch: List<AppInfo>, scan: ScanResult): String {
        val arr = JSONArray()
        for (a in batch) {
            val o = JSONObject()
                .put("package", a.pkg)
                .put("label", a.label)
                .put("systemApp", a.isSystem)
                .put("installer", a.installer ?: JSONObject.NULL)
                .put("sideloaded", a.sideloaded)
                .put("enabled", a.enabled)
                .put("hasLauncherIcon", a.hasLauncherIcon)
                .put("canDrawOverOtherApps", a.overlay)
                .put("deviceAdmin", a.deviceAdmin)
                .put("accessibilityService", a.accessibility)
                .put("grantedDangerousPermissions", JSONArray(a.dangerousPerms))
                .put("flaggedByLocalHeuristic", a.suspicious)
            a.versionName?.let { o.put("version", it) }
            a.lastUsed?.let { o.put("lastUsed", it) }
            arr.put(o)
        }
        return "Phone: ${scan.deviceModel}\nOS: ${scan.osVersion}, Android ${scan.androidVersion}\n\n" +
            "Packages to assess (${batch.size}):\n" + arr.toString(1)
    }
}
