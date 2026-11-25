package com.phonecleaner.ai.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.phonecleaner.ai.BuildConfig
import com.phonecleaner.ai.IShellService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

enum class ShizukuState { NOT_INSTALLED, NOT_RUNNING, NO_PERMISSION, CONNECTING, READY }

data class ShellResult(val exitCode: Int, val output: String) {
    val ok get() = exitCode == 0
}

/** Something that can run a shell command on the phone. */
interface Shell {
    suspend fun exec(command: String): ShellResult
}

class ShellUnavailableException : Exception("Shizuku shell is not connected")

/** Owns the Shizuku connection and is the only place that runs shell commands. */
object ShizukuManager : Shell {
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val REQUEST_CODE = 4711

    private val _state = MutableStateFlow(ShizukuState.NOT_RUNNING)
    val state: StateFlow<ShizukuState> = _state

    /** uid the shell runs as: 2000 = shell (adb), 0 = root. -1 when unknown. */
    private val _uid = MutableStateFlow(-1)
    val uid: StateFlow<Int> = _uid

    @Volatile private var service: IShellService? = null
    @Volatile private var bound = false
    private lateinit var appContext: Context

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, ShellService::class.java.name))
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.pingBinder()) {
                val s = IShellService.Stub.asInterface(binder)
                service = s
                _uid.value = runCatching { s.uid() }.getOrDefault(-1)
                _state.value = ShizukuState.READY
            } else {
                // Dead or missing binder: allow a new bind attempt instead of hanging on "connecting".
                service = null
                bound = false
                _state.value = ShizukuState.NOT_RUNNING
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            refresh()
        }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        Shizuku.addBinderReceivedListenerSticky { refresh() }
        Shizuku.addBinderDeadListener {
            service = null
            bound = false
            refresh()
        }
        Shizuku.addRequestPermissionResultListener { _, _ -> refresh() }
        refresh()
    }

    fun isInstalled(): Boolean = try {
        appContext.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** Re-evaluates the state and binds the shell service when possible. */
    fun refresh() {
        if (testShell != null) return
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!alive) {
            service = null
            bound = false
            _state.value = if (isInstalled()) ShizukuState.NOT_RUNNING else ShizukuState.NOT_INSTALLED
            return
        }
        val granted = runCatching {
            !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (!granted) {
            _state.value = ShizukuState.NO_PERMISSION
            return
        }
        if (service != null) {
            _state.value = ShizukuState.READY
            return
        }
        _state.value = ShizukuState.CONNECTING
        if (!bound) {
            bound = true
            runCatching { Shizuku.bindUserService(userServiceArgs, connection) }
                .onFailure {
                    bound = false
                    _state.value = ShizukuState.NOT_RUNNING
                }
        }
    }

    fun requestPermission() {
        runCatching { Shizuku.requestPermission(REQUEST_CODE) }
    }

    /** Replaces the real shell in tests. Never used in the app itself. */
    @Volatile private var testShell: Shell? = null

    @androidx.annotation.VisibleForTesting
    fun setTestShell(shell: Shell?, uid: Int = 2000) {
        testShell = shell
        if (shell != null) {
            _uid.value = uid
            _state.value = ShizukuState.READY
        } else {
            _state.value = ShizukuState.NOT_RUNNING
        }
    }

    /** Runs a shell command through the Shizuku user service. */
    override suspend fun exec(command: String): ShellResult = withContext(Dispatchers.IO) {
        testShell?.let { return@withContext it.exec(command) }
        val s = service ?: throw ShellUnavailableException()
        val pfd = try {
            s.run(command)
        } catch (t: Throwable) {
            throw ShellUnavailableException()
        }
        val text = ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
        val idx = text.lastIndexOf(ShellService.EXIT_MARKER)
        if (idx < 0) return@withContext ShellResult(-1, text.trim())
        val code = text.substring(idx + ShellService.EXIT_MARKER.length).trim().toIntOrNull() ?: -1
        ShellResult(code, text.substring(0, idx).trim())
    }
}
