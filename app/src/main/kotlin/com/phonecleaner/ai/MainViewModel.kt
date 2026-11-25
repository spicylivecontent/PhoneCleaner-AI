package com.phonecleaner.ai

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.phonecleaner.ai.shizuku.ShizukuManager
import com.phonecleaner.ai.scan.ScanResult
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
}
