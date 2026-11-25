package com.phonecleaner.ai.shizuku

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.annotation.Keep
import com.phonecleaner.ai.IShellService
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Runs inside a process started by Shizuku, with Shizuku's identity
 * (normally the "shell" user, uid 2000). It never asks for root.
 */
@Keep
class ShellService() : IShellService.Stub() {

    @Keep
    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context) : this()

    override fun destroy() {
        exitProcess(0)
    }

    override fun run(command: String): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createPipe()
        thread(name = "pca-shell") {
            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out ->
                try {
                    val process = ProcessBuilder("sh", "-c", command)
                        .redirectErrorStream(true)
                        .start()
                    process.outputStream.close()
                    process.inputStream.copyTo(out)
                    val code = process.waitFor()
                    out.write("\n$EXIT_MARKER$code\n".toByteArray())
                } catch (t: Throwable) {
                    runCatching { out.write("\n${t.message}\n${EXIT_MARKER}-1\n".toByteArray()) }
                }
            }
        }
        return pipe[0]
    }

    override fun uid(): Int = android.os.Process.myUid()

    companion object {
        const val EXIT_MARKER = "__PCA_EXIT__:"
    }
}
