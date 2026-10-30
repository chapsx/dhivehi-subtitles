package mv.dhivehi.subtitles

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/** Records why the app last closed unexpectedly, so the next launch can show it. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                File(filesDir, CrashReporter.FILE).writeText(
                    "Version ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}\n\n$sw"
                )
            } catch (e: Exception) {
                // nothing more we can do
            }
            previous?.uncaughtException(thread, error)
        }
    }
}

object CrashReporter {
    const val FILE = "last_crash.txt"
    private const val PREFS = "crash_reporter"

    /** A Java/Kotlin crash, or (Android 11+) a native crash or a memory kill since the last check. */
    fun takeReport(context: Context): String? {
        val f = File(context.filesDir, FILE)
        if (f.exists()) {
            val text = f.readText()
            f.delete()
            return text
        }
        if (Build.VERSION.SDK_INT < 30) return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val since = prefs.getLong("since", 0L)
        val am = context.getSystemService(ActivityManager::class.java) ?: return null
        val exits = try {
            am.getHistoricalProcessExitReasons(context.packageName, 0, 5)
        } catch (e: Exception) {
            emptyList()
        }
        prefs.edit().putLong("since", System.currentTimeMillis()).apply()
        if (since == 0L) return null
        val bad = exits.firstOrNull {
            it.timestamp > since && it.reason in setOf(
                ApplicationExitInfo.REASON_CRASH_NATIVE,
                ApplicationExitInfo.REASON_LOW_MEMORY,
                ApplicationExitInfo.REASON_ANR,
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
                ApplicationExitInfo.REASON_SIGNALED,
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
            )
        } ?: return null
        val reason = when (bad.reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash (speech engine)"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "Phone ran out of memory"
            ApplicationExitInfo.REASON_ANR -> "App stopped responding"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Used too many resources"
            ApplicationExitInfo.REASON_SIGNALED -> "Closed by the system (signal ${bad.status})"
            else -> "Failed to start"
        }
        val memMb = bad.pss / 1024
        return "Version ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}\n\n" +
            "$reason\n${bad.description ?: ""}\nMemory used: ${memMb} MB"
    }

    fun showIfAny(context: Context) {
        val report = takeReport(context) ?: return
        Log.e("DhivehiSubs", "Previous crash:\n$report")
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.crash_title)
            .setMessage(context.getString(R.string.crash_message) + "\n\n" + report.take(1500))
            .setPositiveButton(R.string.crash_copy) { _, _ ->
                val cm = context.getSystemService(ClipboardManager::class.java)
                cm?.setPrimaryClip(ClipData.newPlainText("Crash report", report))
            }
            .setNegativeButton(R.string.ok, null)
            .show()
    }
}
