package app.bosunarr.ui.common

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import kotlin.system.exitProcess

/**
 * Local-only crash capture: an uncaught exception is written to a file in the app's private storage (nothing is
 * uploaded). On the next launch [CrashReportPrompt] offers to open a prefilled GitHub issue, which the user reviews
 * and submits themselves. Addresses (URLs, IPs) are stripped from the report.
 */
object CrashReporter {
    private const val FILE_NAME = "last_crash.txt"
    /** GitHub's new-issue URL tops out around 8 KB; keep the body well under it. */
    private const val MAX_TRACE = 5_000

    private var file: File? = null
    private val _pending = MutableStateFlow<String?>(null)
    /** The report of the last crash, until the user reports or dismisses it. */
    val pending: StateFlow<String?> = _pending

    /** Call once from the launcher activity's onCreate; later calls do nothing. */
    @Synchronized
    fun install(context: Context) {
        if (file != null) return
        val f = File(context.applicationContext.filesDir, FILE_NAME)
        file = f
        _pending.value = runCatching { f.takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() } }.getOrNull()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { f.writeText(report(thread, error)) }
            if (previous != null) previous.uncaughtException(thread, error) else exitProcess(10)
        }
    }

    fun clear() {
        _pending.value = null
        runCatching { file?.delete() }
    }

    private fun report(thread: Thread, error: Throwable): String {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        return buildString {
            appendLine("Time: ${Instant.now()}")
            appendLine(deviceSummary())
            appendLine("Thread: ${thread.name}")
            appendLine()
            append(redact(trace).take(MAX_TRACE))
        }
    }

    /** Service addresses can appear in exception messages: drop URLs and IP addresses. */
    internal fun redact(text: String): String = text
        .replace(Regex("""[a-zA-Z][a-zA-Z0-9+.-]*://[^\s"')]+"""), "<url>")
        .replace(Regex("""\b\d{1,3}(\.\d{1,3}){3}(:\d+)?\b"""), "<ip>")
        .replace(Regex("""\[?[0-9a-fA-F]{1,4}(:[0-9a-fA-F]{0,4}){3,7}]?"""), "<ip>")

    /** "IllegalStateException" from the report's first exception line. */
    internal fun headline(report: String): String {
        val line = report.lineSequence().dropWhile { it.isNotBlank() }.firstOrNull { it.isNotBlank() }.orEmpty()
        return line.substringBefore(':').substringAfterLast('.').ifBlank { "Crash" }
    }
}

/** Shown once after a crash: report it (opens a prefilled GitHub issue) or dismiss. */
@Composable
fun CrashReportPrompt() {
    val report by CrashReporter.pending.collectAsState()
    val text = report ?: return
    val uri = LocalUriHandler.current
    var details by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { CrashReporter.clear() },
        title = { Text("Bosunarr closed unexpectedly") },
        text = {
            Column {
                Text(
                    "Sorry about that. Reporting it helps get it fixed: it opens a GitHub issue with the error details " +
                        "and your app and Android versions, which you can review before posting. Nothing is sent automatically.",
                )
                TextButton(onClick = { details = !details }, modifier = Modifier.padding(top = 4.dp)) { Text(if (details) "Hide details" else "Show details") }
                if (details) {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState())
                            .padding(12.dp),
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                uri.safeOpen(issueUrl(title = "Crash: ${CrashReporter.headline(text)}", body = "**What were you doing when it closed?**\n\n\n---\n```\n$text\n```"))
                CrashReporter.clear()
            }) { Text("Report") }
        },
        dismissButton = { TextButton(onClick = { CrashReporter.clear() }) { Text("Not now") } },
    )
}
