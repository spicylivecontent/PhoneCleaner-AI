package com.phonecleaner.ai

import com.phonecleaner.ai.ai.ClaudeClient
import com.phonecleaner.ai.ai.ClaudeException
import com.phonecleaner.ai.ai.ClaudeModel
import com.phonecleaner.ai.scan.ScanResult
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ClaudeClientTest {
    private val server = MockWebServer()
    private val scan = ScanResult(1L, "Xiaomi Redmi 15C (25078RA3EE)", "HyperOS OS2.0", "15", emptyList(), emptyMap(), emptyList())
    private val batch = listOf(appInfo("com.miui.msa.global"), appInfo("com.facebook.katana", system = false))

    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    private fun client(model: ClaudeModel = ClaudeModel.OPUS) =
        ClaudeClient("sk-ant-test", model, server.url("").toString().trimEnd('/'))

    private fun verdicts(vararg pkgs: String) = JSONArray().also { a ->
        pkgs.forEach {
            a.put(
                JSONObject().put("package", it).put("appName", it).put("whatItDoes", "Gör något.")
                    .put("verdict", "safe").put("risk", "Inget.").put("isAdOrTracker", true)
                    .put("suspectedMalware", false).put("sources", JSONArray().put("https://example.com/$it"))
            )
        }
    }.toString()

    private fun message(text: String, stop: String = "end_turn", input: Int = 1000, output: Int = 200, searches: Int = 2) =
        JSONObject()
            .put("id", "msg_1").put("type", "message").put("role", "assistant").put("model", "claude-opus-5-5")
            .put("stop_reason", stop)
            .put(
                "content",
                JSONArray()
                    .put(JSONObject().put("type", "server_tool_use").put("id", "srvtoolu_1").put("name", "web_search").put("input", JSONObject().put("query", "q")))
                    .put(JSONObject().put("type", "web_search_tool_result").put("tool_use_id", "srvtoolu_1").put("content", JSONArray()))
                    .put(JSONObject().put("type", "text").put("text", text.take(text.length / 2)))
                    .put(JSONObject().put("type", "text").put("text", text.drop(text.length / 2)).put("citations", JSONArray()))
            )
            .put(
                "usage",
                JSONObject().put("input_tokens", input).put("output_tokens", output)
                    .put("server_tool_use", JSONObject().put("web_search_requests", searches))
            ).toString()

    @Test fun sendsDocumentedRequestAndParsesAnswer() = runBlocking {
        server.enqueue(MockResponse().setBody(message(verdicts("com.miui.msa.global", "com.facebook.katana", "com.not.asked"))))
        val result = client().analyze(batch, scan, swedish = true)

        assertEquals(listOf("com.miui.msa.global", "com.facebook.katana"), result.items.map { it.pkg })
        assertEquals("packages that were not asked about are dropped", 2, result.items.size)
        assertEquals(1000L, result.usage.inputTokens)
        assertEquals(2L, result.usage.searches)
        assertEquals(1000 / 1e6 * 4 + 200 / 1e6 * 20 + 0.02, result.usage.costUsd(ClaudeModel.OPUS), 1e-9)

        val req = server.takeRequest()
        assertEquals("/v1/messages", req.path)
        assertEquals("sk-ant-test", req.getHeader("x-api-key"))
        assertEquals("2026-10-21", req.getHeader("anthropic-version"))
        assertEquals("server-side-fallback-2026-07-01", req.getHeader("anthropic-beta"))
        val body = JSONObject(req.body.readUtf8())
        assertEquals("claude-opus-5-5", body.getString("model"))
        assertEquals("default", body.getString("fallbacks"))
        assertEquals("medium", body.getJSONObject("output_config").getString("effort"))
        val tool = body.getJSONArray("tools").getJSONObject(0)
        assertEquals("web_search_20260318", tool.getString("type"))
        assertEquals("web_search", tool.getString("name"))
        // Parameters newer models reject must never be sent.
        for (banned in listOf("temperature", "top_p", "top_k", "thinking", "tool_choice")) assertFalse(banned, body.has(banned))
        val user = body.getJSONArray("messages").getJSONObject(0)
        assertEquals("user", user.getString("role"))
        val prompt = user.getString("content")
        assertTrue(prompt.contains("Redmi 15C") && prompt.contains("HyperOS OS2.0") && prompt.contains("com.miui.msa.global"))
        assertTrue(body.getString("system").contains("Swedish"))
        assertFalse("the API key is never part of the body", body.toString().contains("sk-ant-test"))
    }

    @Test fun continuesPausedTurnWithUnchangedAssistantContent() = runBlocking {
        val paused = message("Searching…", stop = "pause_turn", input = 500, output = 50, searches = 3)
        server.enqueue(MockResponse().setBody(paused))
        server.enqueue(MockResponse().setBody(message(verdicts("com.miui.msa.global"), input = 700, output = 100, searches = 1)))
        val result = client().analyze(batch, scan, swedish = false)
        assertEquals(1, result.items.size)
        assertEquals(1200L, result.usage.inputTokens)
        assertEquals(4L, result.usage.searches)

        server.takeRequest()
        val second = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages")
        assertEquals(2, second.length())
        assertEquals("assistant", second.getJSONObject(1).getString("role"))
        assertEquals(
            JSONObject(paused).getJSONArray("content").toString(),
            second.getJSONObject(1).getJSONArray("content").toString(),
        )
    }

    @Test fun haikuSendsNoFallbackAndRetriesWithDirectSearch() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"type":"error","error":{"type":"invalid_request_error","message":"set allowed_callers to [\"direct\"]"}}""")
        )
        server.enqueue(MockResponse().setBody(message(verdicts("com.miui.msa.global"))))
        client(ClaudeModel.HAIKU).analyze(batch, scan, swedish = true)
        val first = server.takeRequest()
        assertNull(first.getHeader("anthropic-beta"))
        assertFalse(JSONObject(first.body.readUtf8()).has("fallbacks"))
        val tool = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("tools").getJSONObject(0)
        assertEquals("direct", tool.getJSONArray("allowed_callers").getString(0))
    }

    private fun failure(block: suspend () -> Unit): ClaudeException = runBlocking {
        try {
            block()
            fail("expected ClaudeException")
            throw IllegalStateException()
        } catch (e: ClaudeException) {
            e
        }
    }

    @Test fun reportsBadKeyRefusalAndGarbage() {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""")
        )
        val bad = failure { client().analyze(batch, scan, true) }
        assertEquals(ClaudeException.Kind.BAD_KEY, bad.kind)
        assertEquals("invalid x-api-key", bad.message)

        server.enqueue(MockResponse().setBody(JSONObject().put("stop_reason", "refusal").put("content", JSONArray()).toString()))
        assertEquals(ClaudeException.Kind.REFUSED, failure { client().analyze(batch, scan, true) }.kind)

        server.enqueue(MockResponse().setBody(message("Sorry, I could not produce the list.")))
        assertEquals(ClaudeException.Kind.BAD_RESPONSE, failure { client().analyze(batch, scan, true) }.kind)

        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"web search is not enabled"}}"""))
        val http = failure { client().analyze(batch, scan, true) }
        assertEquals(ClaudeException.Kind.HTTP, http.kind)
        assertTrue(http.message!!.contains("web search is not enabled"))
    }

    @Test fun retriesRateLimitThenSucceeds() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("retry-after", "1").setBody("{}"))
        server.enqueue(MockResponse().setBody(message(verdicts("com.miui.msa.global"))))
        assertEquals(1, client().analyze(batch, scan, true).items.size)
        assertEquals(2, server.requestCount)
    }

    @Test fun keyTestUsesModelsEndpoint() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"id":"claude-opus-5-5"}"""))
        assertNull(client().testKey())
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/v1/models/claude-opus-5-5", req.path)
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"invalid x-api-key"}}"""))
        assertEquals("401: invalid x-api-key", client().testKey())
    }

    @Test fun fallsBackToOlderSearchToolAndKeepsCostOfFailures() {
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"error":{"message":"tools.0: unknown tool type web_search_20260318"}}""")
        )
        server.enqueue(MockResponse().setBody(message("I could not finish.", stop = "max_tokens", input = 900, output = 16000, searches = 5)))
        val e = failure { client().analyze(batch, scan, true) }
        assertEquals(ClaudeException.Kind.BAD_RESPONSE, e.kind)
        assertEquals("the answer was cut off", e.message)
        assertEquals(900L, e.usage.inputTokens)
        assertEquals(5L, e.usage.searches)
        server.takeRequest()
        val tool = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("tools").getJSONObject(0)
        assertEquals("web_search_20260209", tool.getString("type"))
        assertFalse(tool.has("response_inclusion"))
    }

    @Test fun droppedConnectionIsNotSentAgain() {
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertEquals(ClaudeException.Kind.NETWORK, failure { client().analyze(batch, scan, true) }.kind)
        assertEquals("a request that may have been billed is not repeated", 1, server.requestCount)
    }
}
