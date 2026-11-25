package com.phonecleaner.ai

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.phonecleaner.ai.shizuku.ShizukuManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

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
}
