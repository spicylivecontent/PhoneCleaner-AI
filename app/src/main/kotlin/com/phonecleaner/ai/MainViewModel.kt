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
import com.phonecleaner.ai.data.ChangeEntity
import com.phonecleaner.ai.ops.Ops
import com.phonecleaner.ai.ops.Planned
import com.phonecleaner.ai.shizuku.ShizukuState
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

    // ---- Step 4: cleaning, optimisation and undo ----
    private val ops = Ops(app, dao)
    val changes = dao.changes().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val logs = dao.logs().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** A plan waiting for the user's confirmation. Nothing runs until confirmPlan(). */
    val pendingPlan = MutableStateFlow<List<Planned>?>(null)

    data class WorkState(
        val visible: Boolean = false,
        val running: Boolean = false,
        val restoring: Boolean = false,
        val total: Int = 0,
        val done: Int = 0,
        val succeeded: Int = 0,
        val disabledInstead: List<String> = emptyList(),
        val failed: List<String> = emptyList(),
        val backupPath: String? = null,
        val error: String? = null,
    )

    val work = MutableStateFlow(WorkState())

    fun planClean() {
        val sel = selected.value
        val plan = rows.value.filter { it.selectable && it.app.pkg in sel }
            .map { Planned(Ops.UNINSTALL, it.app.pkg, it.app.label, detail = if (it.app.isSystem) "" else "user") }
        if (plan.isNotEmpty()) pendingPlan.value = plan
    }

    /** Apps the scan or Claude flagged as ad/tracker/malware and that are still installed. */
    fun flaggedApps(): List<AppInfo> = rows.value.filter {
        it.selectable && (it.app.suspicious || it.analysis?.isAdOrTracker == true || it.analysis?.suspectedMalware == true)
    }.map { it.app }

    fun planOptimize(animations: Boolean, dns: Boolean, overlay: Boolean, background: Boolean) {
        val plan = ArrayList<Planned>()
        if (animations) for (key in listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")) {
            plan += Planned(Ops.SETTING, "global $key", key, newValue = "0.5")
        }
        if (dns) {
            plan += Planned(Ops.SETTING, "global private_dns_specifier", "private_dns_specifier", newValue = dnsHost.value)
            plan += Planned(Ops.SETTING, "global private_dns_mode", "private_dns_mode", newValue = "hostname")
        }
        for (app in flaggedApps()) {
            if (overlay && app.overlay) plan += Planned(Ops.APPOP, app.pkg, app.label, "SYSTEM_ALERT_WINDOW", "deny")
            if (background) plan += Planned(Ops.APPOP, app.pkg, app.label, "RUN_ANY_IN_BACKGROUND", "ignore")
        }
        if (plan.isNotEmpty()) pendingPlan.value = plan
    }

    fun cancelPlan() {
        pendingPlan.value = null
    }

    private fun updateScan(transform: (ScanResult) -> ScanResult) {
        val s = scan.value ?: return
        val next = transform(s)
        scan.value = next
        viewModelScope.launch(Dispatchers.IO) { runCatching { scanFile.writeText(next.toJson().toString()) } }
    }

    private fun setInstalled(pkg: String, installed: Boolean) = updateScan { s ->
        s.copy(apps = s.apps.map { if (it.pkg == pkg) it.copy(installed = installed) else it })
    }

    /** Runs the confirmed plan: backup file first, then each change, all logged. */
    fun confirmPlan() {
        val plan = pendingPlan.value ?: return
        pendingPlan.value = null
        if (work.value.running) return
        if (shizukuState.value != ShizukuState.READY) {
            work.value = WorkState(visible = true, error = "Shizuku")
            return
        }
        work.value = WorkState(visible = true, running = true, total = plan.size)
        viewModelScope.launch {
            try {
                val backup = ops.writeBackup(plan)
                work.value = work.value.copy(backupPath = backup.absolutePath)
                val apps = scan.value?.apps.orEmpty().associateBy { it.pkg }
                for (p in plan) {
                    var disabled = false
                    val ok = if (p.kind == Ops.UNINSTALL) {
                        val app = apps[p.target]
                        val kind = if (app == null) null else ops.removePackage(app)
                        if (kind != null) setInstalled(p.target, false)
                        disabled = kind == Ops.DISABLE
                        kind != null
                    } else {
                        val applied = ops.apply(p)
                        if (applied && p.kind == Ops.SETTING) updateScan { it.copy(settings = it.settings + (p.target to p.newValue)) }
                        applied
                    }
                    val name = if (p.kind == Ops.APPOP) "${p.label} (${p.detail})" else p.label
                    work.value = work.value.let {
                        it.copy(
                            done = it.done + 1,
                            succeeded = it.succeeded + if (ok) 1 else 0,
                            disabledInstead = if (disabled) it.disabledInstead + name else it.disabledInstead,
                            failed = if (ok) it.failed else it.failed + name,
                        )
                    }
                }
            } catch (t: Throwable) {
                work.value = work.value.copy(error = t.message ?: t.javaClass.simpleName)
            } finally {
                work.value = work.value.copy(running = false)
            }
        }
    }

    fun dismissWork() {
        if (!work.value.running) work.value = WorkState()
    }

    /** Undoes the given recorded changes (newest first). */
    fun revert(list: List<ChangeEntity>) {
        if (work.value.running || list.isEmpty()) return
        if (shizukuState.value != ShizukuState.READY) {
            work.value = WorkState(visible = true, restoring = true, error = "Shizuku")
            return
        }
        work.value = WorkState(visible = true, running = true, restoring = true, total = list.size)
        viewModelScope.launch {
            try {
                for (c in list) {
                    val ok = ops.revert(c)
                    if (ok) when (c.kind) {
                        Ops.UNINSTALL, Ops.DISABLE -> setInstalled(c.target, true)
                        Ops.SETTING -> updateScan { it.copy(settings = it.settings + (c.target to c.originalValue)) }
                    }
                    work.value = work.value.let {
                        it.copy(
                            done = it.done + 1,
                            succeeded = it.succeeded + if (ok) 1 else 0,
                            failed = if (ok) it.failed else it.failed + c.label,
                        )
                    }
                }
            } catch (t: Throwable) {
                work.value = work.value.copy(error = t.message ?: t.javaClass.simpleName)
            } finally {
                work.value = work.value.copy(running = false)
            }
        }
    }

    companion object {
        const val BATCH_SIZE = 15
    }
}
