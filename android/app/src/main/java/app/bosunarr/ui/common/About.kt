package app.bosunarr.ui.common

import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import app.bosunarr.BuildConfig
import kotlinx.serialization.Serializable

const val PRIVACY_URL = "https://selinjodhani.github.io/bosunarr/privacy.html"
private const val ISSUES_URL = "https://github.com/SelinJodhani/bosunarr/issues/new"

/** App and OS version only — never service addresses, names or anything else about the user's setup. */
fun deviceSummary(): String =
    "Bosunarr ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

/** A new GitHub issue, prefilled with a short template and the app/Android version. */
fun feedbackUrl(): String = issueUrl(
    title = "",
    body = """
        **What happened?**


        **What did you expect?**


        **Steps to reproduce**
        1.

        ---
        ${deviceSummary()}
    """.trimIndent(),
)

internal fun issueUrl(title: String, body: String, labels: String? = null): String =
    Uri.parse(ISSUES_URL).buildUpon()
        .appendQueryParameter("title", title)
        .appendQueryParameter("body", body)
        .apply { if (labels != null) appendQueryParameter("labels", labels) }
        .build().toString()

/* ================================================================ open-source licences */

@Serializable object LicencesRoute

private data class Library(val name: String, val by: String, val licence: String, val url: String)

private const val APACHE = "Apache License 2.0"

/** The libraries shipped in the app (build.gradle.kts), grouped as their projects publish them. */
private val LIBRARIES = listOf(
    Library("AndroidX (Core, Activity, Lifecycle, Navigation, DataStore, SplashScreen)", "The Android Open Source Project", APACHE, "https://developer.android.com/jetpack/androidx"),
    Library("Jetpack Compose (UI, Foundation, Material 3, Material Icons)", "The Android Open Source Project", APACHE, "https://developer.android.com/jetpack/compose"),
    Library("Kotlin standard library", "JetBrains", APACHE, "https://github.com/JetBrains/kotlin"),
    Library("kotlinx.coroutines", "JetBrains", APACHE, "https://github.com/Kotlin/kotlinx.coroutines"),
    Library("kotlinx.serialization", "JetBrains", APACHE, "https://github.com/Kotlin/kotlinx.serialization"),
    Library("Coil", "Coil Contributors", APACHE, "https://github.com/coil-kt/coil"),
    Library("OkHttp and Okio", "Square, Inc.", APACHE, "https://github.com/square/okhttp"),
    Library("Tink", "Google LLC", APACHE, "https://github.com/tink-crypto/tink-java"),
    Library("Dashboard Icons (service logos)", "homarr-labs", APACHE, "https://github.com/homarr-labs/dashboard-icons"),
)

@Composable
fun LicencesScreen(nav: NavController) {
    val uri = LocalUriHandler.current
    AppScaffold("Open-source licences", onBack = { nav.back() }) { p ->
        LazyColumn(
            Modifier.padding(p).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item("intro") {
                Text(
                    "Bosunarr is free software under the GNU AGPL v3, built on these open-source projects. Tap one to read its licence and source.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 16.dp),
                )
            }
            item("app") {
                LicenceRow(
                    Library("Bosunarr", "Selin Jodhani", "GNU Affero General Public License v3.0", "https://github.com/SelinJodhani/bosunarr/blob/main/LICENSE"),
                    RoundedCornerShape(24.dp),
                ) { uri.safeOpen(it) }
            }
            item("gap") { Spacer(Modifier.height(14.dp)) }
            items(LIBRARIES.size, key = { LIBRARIES[it].name }) { i ->
                val outer = 24.dp
                val inner = 6.dp
                val shape = RoundedCornerShape(
                    topStart = if (i == 0) outer else inner, topEnd = if (i == 0) outer else inner,
                    bottomStart = if (i == LIBRARIES.lastIndex) outer else inner, bottomEnd = if (i == LIBRARIES.lastIndex) outer else inner,
                )
                LicenceRow(LIBRARIES[i], shape) { uri.safeOpen(it) }
            }
            item("note") {
                Text(
                    "Service names and logos are trademarks of their respective projects and are used only to identify them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun LicenceRow(lib: Library, shape: RoundedCornerShape, onOpen: (String) -> Unit) {
    Surface(onClick = { onOpen(lib.url) }, shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(lib.name) },
            supportingContent = { Text("${lib.by} · ${lib.licence}") },
            trailingContent = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, "Open in browser", tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

/** Opens a link, ignoring devices with no browser rather than crashing. */
fun androidx.compose.ui.platform.UriHandler.safeOpen(url: String) {
    runCatching { openUri(url) }
}
