package com.ghmanager.app.ui

import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.ghmanager.app.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.Instant

/** What kind of project a repository / build produces. Detected from real repo files and real artifact names. */
enum class Kind(val label: String) {
    WEB("Web"), ANDROID("Android"), FLUTTER("Flutter"), WINDOWS("Windows"),
    LINUX("Linux"), MAC("macOS"), OTHER("Other")
}

fun detectKind(rootNames: Set<String>, artifactNames: List<String>): Kind {
    val a = artifactNames.map { it.lowercase() }
    fun anyArt(vararg k: String) = a.any { n -> k.any { n.contains(it) } }
    // Real build output wins over guessing from sources.
    if (anyArt("apk", "android")) return Kind.ANDROID
    if (anyArt("windows", "win64", "win32", ".exe", "msi")) return Kind.WINDOWS
    if (anyArt("macos", "dmg", "osx")) return Kind.MAC
    if (anyArt("linux", "appimage", ".deb", "snap")) return Kind.LINUX
    if (anyArt("web", "dist", "site", "pages", "html")) return Kind.WEB
    val n = rootNames
    return when {
        "pubspec.yaml" in n -> Kind.FLUTTER
        "androidmanifest.xml" in n || "settings.gradle.kts" in n || "settings.gradle" in n -> Kind.ANDROID
        n.any { it.endsWith(".sln") || it.endsWith(".csproj") } || "src-tauri" in n -> Kind.WINDOWS
        "cmakelists.txt" in n || "meson.build" in n || "makefile" in n -> Kind.LINUX
        "index.html" in n || "package.json" in n -> Kind.WEB
        else -> Kind.OTHER
    }
}

private fun findIndex(root: File): File? {
    val kids = root.listFiles().orEmpty()
    val top = if (File(root, "index.html").exists()) root else kids.singleOrNull { it.isDirectory } ?: root
    for (p in listOf("index.html", "dist/index.html", "build/index.html", "docs/index.html", "public/index.html", "out/index.html")) {
        val f = File(top, p)
        if (f.isFile) {
            // A dev template (Vite/React source) can't run without a build: don't pretend it's a preview.
            val txt = try { f.readText().take(4000) } catch (e: Exception) { "" }
            if (txt.contains("src=\"/src/") || txt.contains("src=\"./src/")) return null
            return f
        }
    }
    return null
}

private suspend fun fetchArtifact(base: String, a: JSONObject, root: File, folder: String): File {
    val aid = a.optLong("id")
    val dir = File(root, "$folder$aid")
    if (!dir.exists()) {
        val zip = File(root, "$folder$aid.zip")
        try {
            Github.download("$base/actions/artifacts/$aid/zip", zip)
            withContext(Dispatchers.IO) { Files.unzip(zip, dir) }
        } catch (e: Exception) {
            dir.deleteRecursively(); throw e
        } finally { zip.delete() }
    }
    return dir
}

private fun firstImage(dir: File): File? =
    dir.walkTopDown().firstOrNull { it.isFile && it.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif") }

@Composable
private fun WebPreview(file: File, key: Int) {
    AndroidView(
        factory = { c ->
            WebView(c).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                webViewClient = WebViewClient()
            }
        },
        update = { w ->
            val tag = file.absolutePath + "#" + key
            if (w.tag != tag) { w.tag = tag; w.loadUrl("file://" + file.absolutePath) }
        },
        modifier = Modifier.fillMaxWidth().height(440.dp)
    )
}

@Composable
fun LiveBuildDialog(base: String, run0: JSONObject, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val id = run0.optLong("id")
    val cache = remember { File(ctx.cacheDir, "live").also { it.mkdirs() } }
    var run by remember { mutableStateOf(run0) }
    var jobs by remember { mutableStateOf(listOf<JSONObject>()) }
    var arts by remember { mutableStateOf(listOf<JSONObject>()) }
    var rootNames by remember { mutableStateOf(setOf<String>()) }
    var webIndex by remember { mutableStateOf<File?>(null) }
    var webKey by remember { mutableIntStateOf(0) }
    var shot by remember { mutableStateOf<File?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var prog by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf<List<File>?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var triedSha by remember { mutableStateOf("") }

    // 1) Real status every 3 seconds until the run completes.
    LaunchedEffect(id) {
        while (true) {
            try {
                run = Github.get("$base/actions/runs/$id")
                jobs = Github.get("$base/actions/runs/$id/jobs?per_page=100").optJSONArray("jobs")?.list().orEmpty()
                arts = Github.get("$base/actions/runs/$id/artifacts").optJSONArray("artifacts")?.list().orEmpty()
            } catch (e: CancellationException) { throw e } catch (e: Exception) {}
            if (run.s("status") == "completed") break
            delay(3000)
        }
    }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }

    val st = run.s("status"); val cc = run.s("conclusion")
    val finished = st == "completed"
    val sha = run.s("head_sha")

    // 2) What the repo contains at this exact commit.
    LaunchedEffect(sha) {
        if (sha.isEmpty()) return@LaunchedEffect
        try { rootNames = Github.arr("$base/contents?ref=$sha").list().map { it.s("name").lowercase() }.toSet() }
        catch (e: CancellationException) { throw e } catch (e: Exception) {}
    }
    val kind = detectKind(rootNames, arts.map { it.s("name") })

    // 3) Web: render the project's own files in a WebView, refreshed for every new commit.
    LaunchedEffect(kind, sha) {
        if (kind != Kind.WEB || sha.isEmpty() || triedSha == sha) return@LaunchedEffect
        triedSha = sha
        try {
            prog = "Preparing live preview..."
            val dir = File(cache, "src-$id-${sha.take(7)}")
            if (!dir.exists()) {
                val zip = File(cache, "src-$id-${sha.take(7)}.zip")
                try {
                    Github.download("$base/zipball/$sha", zip)
                    withContext(Dispatchers.IO) { Files.unzip(zip, dir) }
                } catch (e: Exception) { dir.deleteRecursively(); throw e } finally { zip.delete() }
            }
            val f = withContext(Dispatchers.IO) { findIndex(dir) }
            if (f != null) { webIndex = f; webKey++; note = null }
            else note = "No runnable index.html in the sources: the preview appears from the build output when the run finishes."
        } catch (e: CancellationException) { throw e } catch (e: Exception) { note = e.message }
        finally { prog = null }
    }

    // 4) After the build: preview from real artifacts (built website or a screenshot artifact).
    LaunchedEffect(finished, arts.size) {
        if (!finished || cc != "success") return@LaunchedEffect
        try {
            if (shot == null) {
                arts.firstOrNull { !it.optBoolean("expired") && it.s("name").lowercase().let { n -> n.contains("preview") || n.contains("screenshot") } }
                    ?.let { shot = firstImage(fetchArtifact(base, it, cache, "art-")) }
            }
            if (kind == Kind.WEB && webIndex == null) {
                arts.firstOrNull { !it.optBoolean("expired") && it.s("name").lowercase().let { n -> listOf("web", "dist", "site", "pages", "html", "build").any { k -> n.contains(k) } } }
                    ?.let { webIndex = findIndex(fetchArtifact(base, it, cache, "art-")); if (webIndex != null) { webKey++; note = null } }
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {}
    }

    // Progress comes only from real steps GitHub reports.
    val steps = jobs.flatMap { it.optJSONArray("steps")?.list().orEmpty() }
    val doneSteps = steps.count { it.s("status") == "completed" }
    val target = if (finished) 1f else if (steps.isEmpty()) 0f else doneSteps.toFloat() / steps.size
    val progress by animateFloatAsState(target, label = "progress")

    val startMs = try { Instant.parse(run.s("run_started_at").ifEmpty { run.s("created_at") }).toEpochMilli() } catch (e: Exception) { now }
    val endMs = if (finished) try { Instant.parse(run.s("updated_at")).toEpochMilli() } catch (e: Exception) { now } else now
    val secs = ((endMs - startMs) / 1000).coerceAtLeast(0)

    FullDialog(t("Live build"), onClose) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(statusIcon(st, cc), null, tint = statusColor(st, cc))
                        Text(run.s("display_title"), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 2)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill(kind.label, MaterialTheme.colorScheme.primary)
                        Pill(if (finished) cc.ifEmpty { "done" } else st.replace('_', ' '), statusColor(st, cc))
                        Text("%d:%02d".format(secs / 60, secs % 60), style = MaterialTheme.typography.labelMedium)
                    }
                    if (!finished && steps.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text("$doneSteps / ${steps.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    prog?.let { Text(t(it), style = MaterialTheme.typography.bodySmall) }
                }
            }
            item { SectionTitle("Live preview") }
            item {
                Panel {
                    val s = shot; val w = webIndex
                    when {
                        s != null -> AsyncImage(model = s, contentDescription = null, modifier = Modifier.fillMaxWidth())
                        w != null -> {
                            WebPreview(w, webKey)
                            OutlinedButton(onClick = { webKey++ }) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text(t("Refresh")) }
                        }
                        else -> Text(
                            t(when (kind) {
                                Kind.ANDROID, Kind.FLUTTER -> "An Android app can only run after the APK is built. It appears below when the build finishes, then tap Download and install."
                                Kind.WINDOWS, Kind.LINUX, Kind.MAC -> "Desktop apps cannot run on a phone. Follow the build live here and download the result. A screenshot appears here if the workflow uploads an artifact named preview or screenshot."
                                Kind.WEB -> "Preparing the website preview..."
                                Kind.OTHER -> "Follow the build live here. Results appear below when it finishes."
                            }),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    note?.let { Text(t(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline) }
                }
            }
            item { SectionTitle("Steps") }
            items(jobs) { j ->
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(statusIcon(j.s("status"), j.s("conclusion")), null, tint = statusColor(j.s("status"), j.s("conclusion")))
                        Text(j.s("name"), fontWeight = FontWeight.SemiBold)
                    }
                    (j.optJSONArray("steps")?.list().orEmpty()).forEach { s ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(statusIcon(s.s("status"), s.s("conclusion")), null, Modifier.size(16.dp), tint = statusColor(s.s("status"), s.s("conclusion")))
                            Text(s.s("name"), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item { SectionTitle("Artifacts") }
            if (arts.isEmpty()) item {
                Text(t(if (finished) "This run produced no artifacts." else "Artifacts appear when the run finishes."), style = MaterialTheme.typography.bodySmall)
            }
            items(arts) { a ->
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(a.s("name"), fontWeight = FontWeight.SemiBold)
                            Text(human(a.optLong("size_in_bytes")), style = MaterialTheme.typography.labelSmall)
                        }
                        val isApk = kind == Kind.ANDROID || kind == Kind.FLUTTER
                        Button(enabled = !a.optBoolean("expired") && prog == null, onClick = {
                            scope.op(ctx, "Download artifact ${a.s("name")}") {
                                try {
                                    prog = "Downloading ${a.s("name")}..."
                                    val dir = fetchArtifact(base, a, Files.downloads(ctx), "artifact-")
                                    saved = withContext(Dispatchers.IO) { dir.walkTopDown().filter { it.isFile }.toList() }
                                } finally { prog = null }
                            }
                        }) {
                            Icon(if (isApk) Icons.Default.InstallMobile else Icons.Default.Download, null)
                            Spacer(Modifier.width(6.dp))
                            Text(t(if (isApk) "Download and install" else "Get"))
                        }
                    }
                }
            }
        }
    }
    saved?.let { SavedDialog(it) { saved = null } }
}
