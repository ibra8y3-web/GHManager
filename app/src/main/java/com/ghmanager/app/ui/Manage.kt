package com.ghmanager.app.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ghmanager.app.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

suspend fun uploadUris(ctx: Context, base: String, releaseId: Long, uris: List<Uri>) {
    uris.forEach { u ->
        val nm = ctx.displayName(u)
        val bytes = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(u)!!.use { it.readBytes() } }
        Github.uploadAsset(base, releaseId, nm, ctx.contentResolver.getType(u) ?: "application/octet-stream", bytes)
    }
}

@Composable
fun ReleasesTab(base: String, repo: JSONObject?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var create by remember { mutableStateOf(false) }
    var del by remember { mutableStateOf<JSONObject?>(null) }
    var attach by remember { mutableStateOf<JSONObject?>(null) }
    var saved by remember { mutableStateOf<List<File>?>(null) }
    var prog by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val r = attach
        if (r != null && uris.isNotEmpty()) scope.op(ctx, "Upload ${uris.size} asset(s)", { reload++ }) { uploadUris(ctx, base, r.optLong("id"), uris) }
    }
    Box(Modifier.fillMaxSize()) {
        Column {
            prog?.let { Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
            PagedList("$base/releases", reload, empty = "No releases yet") { r ->
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Sell, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            Text(r.s("name").ifEmpty { r.s("tag_name") }, fontWeight = FontWeight.SemiBold)
                            Text("${r.s("tag_name")}  ${ago(r.s("published_at").ifEmpty { r.s("created_at") })}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        }
                        if (r.optBoolean("draft")) Pill("draft", Warn)
                        if (r.optBoolean("prerelease")) Pill("pre-release", MaterialTheme.colorScheme.tertiary)
                    }
                    if (r.s("body").isNotEmpty()) Text(r.s("body"), maxLines = 4, style = MaterialTheme.typography.bodySmall)
                    (r.optJSONArray("assets")?.list().orEmpty()).forEach { a ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(a.s("name"), style = MaterialTheme.typography.bodyMedium)
                                Text("${human(a.optLong("size"))}  ${a.optInt("download_count")} downloads", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            }
                            IconButton(onClick = {
                                scope.op(ctx, "Download ${a.s("name")}") {
                                    try {
                                        prog = "Downloading ${a.s("name")}..."
                                        val f = File(Files.downloads(ctx), a.s("name"))
                                        Github.download("$base/releases/assets/${a.optLong("id")}", f, "application/octet-stream") { d, t ->
                                            prog = "Downloading ${human(d)}" + if (t > 0) " of ${human(t)}" else ""
                                        }
                                        saved = listOf(f)
                                    } finally { prog = null }
                                }
                            }) { Icon(Icons.Default.Download, "Download") }
                            IconButton(onClick = {
                                scope.op(ctx, "Delete asset ${a.s("name")}", { reload++ }) { Github.send("DELETE", "$base/releases/assets/${a.optLong("id")}") }
                            }) { Icon(Icons.Default.Delete, "Delete asset") }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { attach = r; picker.launch(arrayOf("*/*")) }) { Icon(Icons.Default.AttachFile, null); Spacer(Modifier.width(6.dp)); Text(t("Add files")) }
                        TextButton(onClick = { del = r }) { Text(t("Delete release"), color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { create = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text(t("New release")) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        )
    }
    if (create) CreateReleaseDialog(base, repo?.s("default_branch").orEmpty(), { create = false }) { reload++ }
    del?.let { r ->
        AlertDialog(
            onDismissRequest = { del = null }, title = { Text("Delete release ${r.s("tag_name")}?") },
            text = { Text(t("The release and its files are removed. The Git tag stays.")) },
            confirmButton = {
                Button(onClick = {
                    scope.op(ctx, "Delete release ${r.s("tag_name")}", { del = null; reload++ }) { Github.send("DELETE", "$base/releases/${r.optLong("id")}") }
                }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text(t("Delete")) }
            },
            dismissButton = { TextButton(onClick = { del = null }) { Text(t("Cancel")) } }
        )
    }
    saved?.let { SavedDialog(it) { saved = null } }
}

@Composable
fun CreateReleaseDialog(base: String, def: String, onClose: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tag by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var target by remember { mutableStateOf(def) }
    var draft by remember { mutableStateOf(false) }
    var pre by remember { mutableStateOf(false) }
    var gen by remember { mutableStateOf(true) }
    var files by remember { mutableStateOf(listOf<Uri>()) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { files = it }
    FormDialog("New release", "Publish", onClose, enabled = tag.isNotBlank(), onConfirm = {
        scope.op(ctx, "Create release $tag", { onDone(); onClose() }) {
            val r = Github.send(
                "POST", "$base/releases",
                JSONObject().put("tag_name", tag.trim()).put("name", title.ifBlank { tag }).put("body", notes)
                    .put("target_commitish", target).put("draft", draft).put("prerelease", pre).put("generate_release_notes", gen)
            )
            if (files.isNotEmpty()) uploadUris(ctx, base, r.optLong("id"), files)
        }
    }) {
        Field("Tag (for example v1.0.0)", tag, { tag = it })
        Field("Title", title, { title = it })
        Field("Target branch", target, { target = it })
        Field("Notes", notes, { notes = it }, singleLine = false)
        SwitchRow("Generate notes from commits", gen) { gen = it }
        SwitchRow("Draft", draft) { draft = it }
        SwitchRow("Pre-release", pre) { pre = it }
        OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) {
            Icon(Icons.Default.AttachFile, null); Spacer(Modifier.width(6.dp)); Text(if (files.isEmpty()) "Attach APK or files" else "${files.size} file(s) attached")
        }
    }
}

@Composable
fun ManageTab(owner: String, name: String, repo: JSONObject?, onRepo: (JSONObject) -> Unit, back: () -> Unit) {
    val base = "/repos/$owner/$name"
    var sec by remember { mutableStateOf("Details") }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp, 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Details", "Secrets", "Variables", "Labels", "Milestones", "People", "Hooks", "Keys", "Security", "Insights", "Danger").forEach {
                FilterChip(sec == it, { sec = it }, { Text(t(it)) })
            }
        }
        Box(Modifier.weight(1f)) {
            when (sec) {
                "Details" -> if (repo == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else DetailsSection(base, name, repo, onRepo, back)
                "Secrets" -> SecretsSection(base)
                "Variables" -> SimpleList(
                    "$base/actions/variables", "Add variable", listOf("Name", "Value"), 2,
                    create = { v -> Github.send("POST", "$base/actions/variables", JSONObject().put("name", v[0].trim()).put("value", v[1])) },
                    title = { it.s("name") }, sub = { it.s("value") },
                    delete = { Github.send("DELETE", "$base/actions/variables/${enc(it.s("name"))}") }, unwrap = "variables"
                )
                "Labels" -> SimpleList(
                    "$base/labels", "Add label", listOf("Name", "Color (hex, no #)", "Description"), 1,
                    create = { v -> Github.send("POST", "$base/labels", JSONObject().put("name", v[0]).put("color", v[1].ifBlank { "0E8A6A" }).put("description", v[2])) },
                    title = { it.s("name") }, sub = { it.s("description") },
                    delete = { Github.send("DELETE", "$base/labels/${enc(it.s("name"))}") }
                )
                "Milestones" -> SimpleList(
                    "$base/milestones?state=all", "Add milestone", listOf("Title", "Description"), 1,
                    create = { v -> Github.send("POST", "$base/milestones", JSONObject().put("title", v[0]).put("description", v[1])) },
                    title = { it.s("title") }, sub = { "${it.optInt("open_issues")} open, ${it.optInt("closed_issues")} closed  ${it.s("state")}" },
                    delete = { Github.send("DELETE", "$base/milestones/${it.optInt("number")}") }
                )
                "People" -> SimpleList(
                    "$base/collaborators", "Add collaborator", listOf("Username", "Permission: pull, triage, push, maintain, admin"), 1,
                    create = { v -> Github.send("PUT", "$base/collaborators/${enc(v[0])}", JSONObject().put("permission", v[1].ifBlank { "push" })) },
                    title = { it.s("login") }, sub = { it.s("role_name") },
                    delete = { Github.send("DELETE", "$base/collaborators/${enc(it.s("login"))}") }
                )
                "Hooks" -> SimpleList(
                    "$base/hooks", "Add webhook", listOf("Payload URL"), 1,
                    create = { v ->
                        Github.send(
                            "POST", "$base/hooks",
                            JSONObject().put("name", "web").put("active", true).put("events", JSONArray().put("push"))
                                .put("config", JSONObject().put("url", v[0]).put("content_type", "json"))
                        )
                    },
                    title = { it.optJSONObject("config")?.s("url").orEmpty() },
                    sub = { "events: " + (it.optJSONArray("events")?.let { a -> (0 until a.length()).joinToString { i -> a.getString(i) } } ?: "") + if (it.optBoolean("active")) "  active" else "  inactive" },
                    delete = { Github.send("DELETE", "$base/hooks/${it.optLong("id")}") }
                )
                "Keys" -> SimpleList(
                    "$base/keys", "Add deploy key", listOf("Title", "Public key"), 2,
                    create = { v -> Github.send("POST", "$base/keys", JSONObject().put("title", v[0]).put("key", v[1]).put("read_only", true)) },
                    title = { it.s("title") }, sub = { if (it.optBoolean("read_only")) "read only" else "read and write" },
                    delete = { Github.send("DELETE", "$base/keys/${it.optLong("id")}") }
                )
                "Security" -> SecurityAlerts(base)
                "Insights" -> InsightsSection(base)
                else -> DangerSection(owner, name, repo, base, back)
            }
        }
    }
}

@Composable
fun SimpleList(
    path: String, addLabel: String?, fields: List<String>, required: Int,
    create: (suspend (List<String>) -> Unit)?, title: (JSONObject) -> String, sub: (JSONObject) -> String,
    delete: (suspend (JSONObject) -> Unit)?, unwrap: String? = null, hide: Set<Int> = emptySet(),
    extra: @Composable () -> Unit = {}
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    var del by remember { mutableStateOf<JSONObject?>(null) }
    Column(Modifier.fillMaxSize()) {
        extra()
        if (addLabel != null && create != null) {
            Button(onClick = { adding = true }, modifier = Modifier.padding(12.dp, 4.dp)) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text(addLabel)
            }
        }
        PagedList(path, reload, unwrap = unwrap, empty = "Nothing here yet") { o ->
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(title(o), fontWeight = FontWeight.SemiBold)
                        val s = sub(o)
                        if (s.isNotBlank()) Text(s, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                    if (delete != null) IconButton(onClick = { del = o }) { Icon(Icons.Default.Delete, "Delete") }
                }
            }
        }
    }
    if (adding && create != null && addLabel != null) {
        val vals = remember { mutableStateListOf<String>().apply { repeat(fields.size) { add("") } } }
        FormDialog(addLabel, "Add", { adding = false }, enabled = (0 until required).all { vals[it].isNotBlank() }, onConfirm = {
            scope.op(ctx, addLabel, { adding = false; reload++ }) { create(vals.toList()) }
        }) { fields.forEachIndexed { i, f -> Field(f, vals[i], { vals[i] = it }, password = i in hide) } }
    }
    val d = del
    if (d != null && delete != null) {
        AlertDialog(
            onDismissRequest = { del = null }, title = { Text("Remove ${title(d)}?") }, text = { Text(t("This takes effect on GitHub immediately.")) },
            confirmButton = {
                Button(onClick = { scope.op(ctx, "Remove ${title(d)}", { del = null; reload++ }) { delete(d) } },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text(t("Remove")) }
            },
            dismissButton = { TextButton(onClick = { del = null }) { Text(t("Cancel")) } }
        )
    }
}

@Composable
fun DetailsSection(base: String, name: String, repo: JSONObject, onRepo: (JSONObject) -> Unit, back: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var nm by remember(repo) { mutableStateOf(repo.s("name")) }
    var desc by remember(repo) { mutableStateOf(repo.s("description")) }
    var home by remember(repo) { mutableStateOf(repo.s("homepage")) }
    var priv by remember(repo) { mutableStateOf(repo.optBoolean("private")) }
    var arch by remember(repo) { mutableStateOf(repo.optBoolean("archived")) }
    var tmpl by remember(repo) { mutableStateOf(repo.optBoolean("is_template")) }
    var issues by remember(repo) { mutableStateOf(repo.optBoolean("has_issues", true)) }
    var wiki by remember(repo) { mutableStateOf(repo.optBoolean("has_wiki", true)) }
    var saved by remember { mutableStateOf<List<File>?>(null) }
    var prog by remember { mutableStateOf<String?>(null) }
    var gen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Panel {
            Field("Name", nm, { nm = it })
            Field("Description", desc, { desc = it })
            Field("Website", home, { home = it })
            SwitchRow("Private", priv) { priv = it }
            SwitchRow("Archived (read only)", arch) { arch = it }
            SwitchRow("Template repository", tmpl) { tmpl = it }
            SwitchRow("Issues", issues) { issues = it }
            SwitchRow("Wiki", wiki) { wiki = it }
            Button(onClick = {
                scope.op(ctx, "Update ${repo.s("full_name")}", { Github.repoVersion++ }) {
                    val u = Github.send(
                        "PATCH", base,
                        JSONObject().put("name", nm.trim()).put("description", desc).put("homepage", home).put("private", priv)
                            .put("archived", arch).put("is_template", tmpl).put("has_issues", issues).put("has_wiki", wiki)
                    )
                    if (nm.trim() != name) back() else onRepo(u)
                }
            }, modifier = Modifier.fillMaxWidth()) { Text(t("Save changes")) }
        }
        Panel {
            SectionTitle("Copies")
            prog?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            OutlinedButton(onClick = {
                scope.op(ctx, "Fork ${repo.s("full_name")}", { Github.repoVersion++ }) { Github.send("POST", "$base/forks") }
            }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.CallSplit, null); Spacer(Modifier.width(6.dp)); Text(t("Fork to my account")) }
            OutlinedButton(onClick = {
                scope.op(ctx, "Download ${repo.s("name")} as ZIP") {
                    try {
                        prog = "Downloading..."
                        val ref = repo.s("default_branch")
                        val f = File(Files.downloads(ctx), "${repo.s("name")}-$ref.zip")
                        Github.download("$base/zipball/${encPath(ref)}", f) { d, _ -> prog = "Downloaded ${human(d)}" }
                        saved = listOf(f)
                    } finally { prog = null }
                }
            }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Download, null); Spacer(Modifier.width(6.dp)); Text(t("Download as ZIP")) }
            if (repo.optBoolean("is_template")) OutlinedButton(onClick = { gen = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text(t("Create repository from template"))
            }
        }
    }
    if (gen) {
        var n by remember { mutableStateOf("") }
        var p by remember { mutableStateOf(true) }
        FormDialog("From template", "Create", { gen = false }, enabled = n.isNotBlank(), onConfirm = {
            scope.op(ctx, "Create $n from template", { gen = false; Github.repoVersion++ }) {
                val me = Github.get("/user").s("login")
                Github.send("POST", "$base/generate", JSONObject().put("owner", me).put("name", n.trim()).put("private", p))
            }
        }) {
            Field("New repository name", n, { n = it })
            SwitchRow("Private", p) { p = it }
        }
    }
    saved?.let { SavedDialog(it) { saved = null } }
}

@Composable
fun DangerSection(owner: String, name: String, repo: JSONObject?, base: String, back: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var delete by remember { mutableStateOf(false) }
    var transfer by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Panel {
            SectionTitle("Transfer ownership")
            Text(t("The new owner must accept the transfer. Your token needs admin rights."), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { transfer = true }) { Icon(Icons.Default.SwapHoriz, null); Spacer(Modifier.width(6.dp)); Text(t("Transfer")) }
        }
        Panel {
            SectionTitle("Delete repository")
            Text("This permanently deletes $owner/$name with all issues, releases and workflow history. It cannot be undone.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { delete = true }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                Icon(Icons.Default.DeleteForever, null); Spacer(Modifier.width(6.dp)); Text(t("Delete this repository"))
            }
        }
    }
    if (delete) {
        var typed by remember { mutableStateOf("") }
        FormDialog("Delete $owner/$name?", "Delete forever", { delete = false }, enabled = typed == "$owner/$name", onConfirm = {
            scope.op(ctx, "Delete repository $owner/$name", { Github.repoVersion++; back() }) { Github.send("DELETE", base) }
        }) {
            Text("Type $owner/$name to confirm. The token needs the delete_repo scope.")
            Field("Repository full name", typed, { typed = it })
        }
    }
    if (transfer) {
        var to by remember { mutableStateOf("") }
        FormDialog("Transfer $owner/$name", "Transfer", { transfer = false }, enabled = to.isNotBlank(), onConfirm = {
            scope.op(ctx, "Transfer $owner/$name to $to", { transfer = false; Github.repoVersion++; back() }) {
                Github.send("POST", "$base/transfer", JSONObject().put("new_owner", to.trim()))
            }
        }) { Field("New owner (user or organization)", to, { to = it }) }
    }
}
