package com.phonecleaner.ai

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.phonecleaner.ai.shizuku.ShizukuManager
import com.phonecleaner.ai.ai.ClaudeClient
import com.phonecleaner.ai.ai.ClaudeException
import com.phonecleaner.ai.ai.ClaudeModel
import com.phonecleaner.ai.ai.Usage
import com.phonecleaner.ai.data.AnalysisEntity
import com.phonecleaner.ai.data.AppDb
import com.phonecleaner.ai.data.Prefs
import com.phonecleaner.ai.scan.AppInfo
import com.phonecleaner.ai.scan.ScanResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import com.phonecleaner.ai.scan.Scanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

enum class Screen { HOME, SCAN, RESEARCH, LIST, OPTIMIZE, RESTORE, LOG, SETTINGS }

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val screen = MutableStateFlow(Screen.HOME)
    val shizukuState = ShizukuManager.state
    val shizukuUid = ShizukuManager.uid

    /** Output of the harmless "id" command, proving the shell works. */
    val shellTest = MutableStateFlow<String?>(null)

    fun go(s: Screen) {
        screen.value = s
    }

    fun refreshShizuku() = ShizukuManager.refresh()

    fun requestShizukuPermission() = ShizukuManager.requestPermission()

    fun testShell() {
        viewModelScope.launch {
            shellTest.value = try {
                ShizukuManager.exec("id").output
            } catch (t: Throwable) {
                t.message ?: t.javaClass.simpleName
            }
        }
    }

    // ---- Step 1: scan (read-only) ----
    val scan = MutableStateFlow<ScanResult?>(null)
    val scanning = MutableStateFlow(false)
    val scanStep = MutableStateFlow(0)
    val scanError = MutableStateFlow<String?>(null)
    private val scanFile get() = File(getApplication<Application>().filesDir, "scan.json")

    init {
        viewModelScope.launch {
            scan.value = withContext(Dispatchers.IO) {
                runCatching { ScanResult.fromJson(JSONObject(scanFile.readText())) }.getOrNull()
            }
            onScanLoaded()
        }
    }

    fun startScan() {
        if (scanning.value) return
        scanning.value = true
        scanError.value = null
        viewModelScope.launch {
            try {
                val result = Scanner(getApplication()).scan { scanStep.value = it }
                scan.value = result
                withContext(Dispatchers.IO) { scanFile.writeText(result.toJson().toString()) }
                onScanLoaded()
            } catch (t: Throwable) {
                scanError.value = t.message ?: t.javaClass.simpleName
            } finally {
                scanning.value = false
            }
        }
    }

    private fun onScanLoaded() {
    }

    // ---- Settings ----
    val prefs = Prefs(app)
    private val dao = AppDb.get(app).dao()
    val hasApiKey = MutableStateFlow(prefs.hasApiKey())
    val model = MutableStateFlow(ClaudeModel.of(prefs.model))
    val dnsHost = MutableStateFlow(prefs.dnsHost)
    val keyTest = MutableStateFlow<String?>(null)

    fun saveApiKey(key: String) {
        prefs.setApiKey(key)
        hasApiKey.value = prefs.hasApiKey()
        keyTest.value = null
    }

    fun setModel(m: ClaudeModel) {
        prefs.model = m.id
        model.value = m
    }

    fun setDnsHost(host: String) {
        val h = host.trim()
        if (Regex("^[A-Za-z0-9.-]{3,253}$").matches(h)) {
            prefs.dnsHost = h
            dnsHost.value = h
        }
    }

    /** Checks the key against the Models endpoint, which costs nothing. */
    fun testApiKey() {
        val key = prefs.apiKey() ?: return
        keyTest.value = "…"
        viewModelScope.launch {
            keyTest.value = ClaudeClient(key, model.value).testKey() ?: "OK"
        }
    }

    fun clearAnalysisCache() {
        viewModelScope.launch { dao.clearAnalyses() }
    }

    // ---- Step 2: AI research ----
    val analyses = dao.analyses().map { list -> list.associateBy { it.pkg } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap<String, AnalysisEntity>())

    data class ResearchState(
        val running: Boolean = false,
        val batchesTotal: Int = 0,
        val batchesDone: Int = 0,
        val packagesDone: Int = 0,
        val usage: Usage = Usage(),
        val error: String? = null,
        val errorKind: ClaudeException.Kind? = null,
        val finished: Boolean = false,
    )

    val research = MutableStateFlow(ResearchState())
    private var researchJob: Job? = null

    /** Packages that may be sent to Claude: installed and not locked. */
    fun researchCandidates(s: ScanResult): List<AppInfo> = s.apps.filter { it.installed && !it.locked }

    fun startResearch() {
        val s = scan.value ?: return
        val key = prefs.apiKey() ?: return
        if (research.value.running) return
        val m = model.value
        val swedish = java.util.Locale.getDefault().language == "sv"
        researchJob = viewModelScope.launch {
            val cached = dao.analysesNow().map { it.pkg }.toSet()
            val batches = researchCandidates(s).filter { it.pkg !in cached }.chunked(BATCH_SIZE)
            research.value = ResearchState(running = true, batchesTotal = batches.size)
            val client = ClaudeClient(key, m)
            try {
                for (batch in batches) {
                    val result = client.analyze(batch, s, swedish)
                    dao.upsertAnalyses(result.items)
                    research.value = research.value.let {
                        it.copy(
                            batchesDone = it.batchesDone + 1,
                            packagesDone = it.packagesDone + result.items.size,
                            usage = it.usage + result.usage,
                        )
                    }
                }
                research.value = research.value.copy(running = false, finished = true)
            } catch (e: CancellationException) {
                research.value = research.value.copy(running = false)
                throw e
            } catch (e: ClaudeException) {
                research.value = research.value.copy(running = false, error = e.message, errorKind = e.kind)
            } catch (t: Throwable) {
                research.value = research.value.copy(running = false, error = t.message ?: t.javaClass.simpleName)
            }
        }
    }

    fun cancelResearch() {
        researchJob?.cancel()
    }

    // ---- Step 3: review list ----
    enum class Group { MALWARE, ADS, SAFE, UNSURE, LOCKED }

    data class Row(val app: AppInfo, val analysis: AnalysisEntity?) {
        val group: Group = when {
            app.locked || analysis?.verdict == "keep" -> Group.LOCKED
            analysis?.suspectedMalware == true || app.suspicious -> Group.MALWARE
            analysis?.isAdOrTracker == true -> Group.ADS
            analysis?.verdict == "safe" -> Group.SAFE
            else -> Group.UNSURE
        }
        val selectable get() = group != Group.LOCKED

        /**
         * Pre-ticked only when Claude says "safe" and it is not simply an app
         * the user installed on purpose.
         */
        val defaultChecked get() = selectable && analysis?.verdict == "safe" &&
            (app.isSystem || analysis.isAdOrTracker || analysis.suspectedMalware)
    }

    /** The user's own ticks; packages not in the map use the default. */
    private val overrides = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    val rows = combine(scan, analyses) { s, a ->
        s?.apps.orEmpty().filter { it.installed }.map { Row(it, a[it.pkg]) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val selected = combine(rows, overrides) { r, o ->
        r.filter { it.selectable && (o[it.app.pkg] ?: it.defaultChecked) }.map { it.app.pkg }.toSet()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    fun toggle(pkg: String, checked: Boolean) {
        val row = rows.value.firstOrNull { it.app.pkg == pkg } ?: return
        if (!row.selectable) return
        overrides.value = overrides.value + (pkg to checked)
    }

    fun uncheckAll() {
        overrides.value = rows.value.filter { it.selectable }.associate { it.app.pkg to false }
    }

    fun resetSelection() {
        overrides.value = emptyMap()
    }

    companion object {
        const val BATCH_SIZE = 15
    }
}
