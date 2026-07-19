package com.crsk.openclaw.shizuku

import android.os.IBinder
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ShizukuCommandService : IShizukuCommandService.Stub() {

    private val executor = Executors.newFixedThreadPool(2)

    override fun version(): String = "shizuku-cmd-1"

    override fun exec(argv: Array<String>, stdin: String?, timeoutMs: Int): String {
        return try {
            val process = ProcessBuilder(*argv)
                .redirectErrorStream(false)
                .start()
            if (!stdin.isNullOrEmpty()) {
                process.outputStream.use { it.write(stdin.toByteArray()) }
            } else {
                process.outputStream.close()
            }

            // Read streams in background BEFORE waitFor to prevent pipe deadlock
            val outFuture = executor.submit(Callable { process.inputStream.bufferedReader().readText() })
            val errFuture = executor.submit(Callable { process.errorStream.bufferedReader().readText() })

            val finished = process.waitFor(
                timeoutMs.toLong().coerceAtLeast(1000L),
                TimeUnit.MILLISECONDS
            )
            if (!finished) {
                process.destroyForcibly()
                return "124\n\n---STDERR---\ntimeout after ${timeoutMs}ms"
            }
            val out = outFuture.get(2, TimeUnit.SECONDS)
            val err = errFuture.get(2, TimeUnit.SECONDS)
            "${process.exitValue()}\n$out\n---STDERR---\n$err"
        } catch (e: IOException) {
            "1\n\n---STDERR---\n${e.message ?: "io error"}"
        } catch (e: Throwable) {
            "1\n\n---STDERR---\n${e.javaClass.simpleName}: ${e.message}"
        }
    }

    override fun destroy() {
        System.exit(0)
    }

    override fun asBinder(): IBinder = this
}
