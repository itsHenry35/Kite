package com.henry.kite.logs

import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bundles the information a problem report needs: this app's recent logcat
 * output plus the device context, written to one shareable text file.
 *
 * An unprivileged `logcat` invocation only returns entries logged by our own
 * UID — everything else is filtered by logd — so the dump stays small and
 * contains no other apps' data by construction.
 */
object Logs {

    /** Writes the log bundle to the cache dir and returns the file for sharing. */
    fun collect(context: Context): File {
        val dir = File(context.cacheDir, "logs").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "kite-logs-$stamp.txt")

        file.bufferedWriter().use { out ->
            out.appendLine("Kite ${appVersion(context)}")
            out.appendLine(
                "Device: ${Build.MANUFACTURER} ${Build.MODEL}, " +
                    "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            )
            out.appendLine("Collected: ${Date()}")
            out.appendLine()
            out.appendLine("---- logcat (pid ${Process.myPid()}) ----")
            out.appendLine(dumpLogcat())
        }
        return file
    }

    private fun appVersion(context: Context): String = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager
            .getPackageInfo(context.packageName, 0)
            .versionName
            .orEmpty()
            .ifBlank { "?" }
    }.getOrDefault("?")

    /**
     * `logcat -d` (dump and exit). Returns null on failure so the bundle keeps
     * whatever context it already has — a missing log should not block sharing.
     */
    private fun dumpLogcat(): String? = runCatching {
        val process = Runtime.getRuntime().exec(
            arrayOf("logcat", "-d", "-v", "time", "--pid=${Process.myPid()}"),
        )
        val output = process.inputStream.use { it.readBytes().decodeToString() }
        process.waitFor()
        process.destroy()
        output.ifBlank { null }
    }.getOrNull()
}
