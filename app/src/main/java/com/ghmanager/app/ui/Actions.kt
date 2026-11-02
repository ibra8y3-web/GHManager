package com.ghmanager.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghmanager.app.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

@Composable
fun statusColor(status: String, conclusion: String) = when {
    status != "completed" -> MaterialTheme.colorScheme.secondary
    conclusion == "success" -> Good
    conclusion == "failure" || conclusion == "timed_out" -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.outline
}

fun statusIcon(status: String, conclusion: String) = when {
    status != "completed" -> Icons.Default.Autorenew
    conclusion == "success" -> Icons.Default.CheckCircle
    conclusion == "failure" || conclusion == "timed_out" -> Icons.Default.Error
    conclusion == "cancelled" -> Icons.Default.Cancel
    else -> Icons.Default.RemoveCircle
}

@Composable
fun ActionsTab(base: String, repo: JSONObject?) {
    var reload by remember { mutableIntStateOf(0) }
    var sel by remember { mutableStateOf<JSONObject?>(null) }
    var dispatch by remember { mutableStateOf(false) }
    var addWf by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp, 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { dispatch = true }) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(t("Run workflow")) }
            OutlinedButton(onClick = { addWf = true }) { Icon(Icons.Default.PostAdd, null); Spacer(Modifier.width(6.dp)); Text(t("Add workflow")) }
            OutlinedButton(onClick = { reload++ }) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text(t("Refresh")) }
        }
        PagedList("$base/actions/runs", reload, unwrap = "workflow_runs", empty = "No workflow runs yet") { r ->
            val st = r.s("status"); val cc = r.s("conclusion")
            Panel(onClick = { sel = r }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(statusIcon(st, cc), null, tint = statusColor(st, cc))
                    Column(Modifier.weight(1f)) {
                        Text(r.s("display_title"), fontWeight = FontWeight.SemiBold, maxLines = 2)
                        Text(
                            "${r.s("name")}  #${r.optInt("run_number")}  ${r.s("head_branch")}  ${r.s("event")}  ${ago(r.s("created_at"))}",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline
                        )
                    }
                    Pill(if (st == "completed") cc.ifEmpty { "done" } else st.replace('_', ' '), statusColor(st, cc))
                }
            }
        }
    }
    sel?.let { RunDialog(base, it, { sel = null }) { reload++ } }
    if (dispatch) DispatchDialog(base, repo?.s("default_branch").orEmpty(), { dispatch = false }) { reload++ }
    if (addWf) AddWorkflowDialog(base, repo?.s("default_branch").orEmpty()) { addWf = false }
}

@Composable
fun DispatchDialog(base: String, def: String, onClose: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var wfs by remember { mutableStateOf(listOf<JSONObject>()) }
    var chosen by remember { mutableStateOf<JSONObject?>(null) }
    var ref by remember { mutableStateOf(def) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try {
            wfs = (Github.get("$base/actions/workflows").optJSONArray("workflows")?.list().orEmpty()).filter { it.s("state") == "active" }
            chosen = wfs.firstOrNull()
        } catch (e: CancellationException) { throw e } catch (e: Exception) { err = e.message }
    }
    FormDialog("Run workflow", "Run", onClose, enabled = chosen != null && ref.isNotBlank(), onConfirm = {
        scope.op(ctx, "Run workflow ${chosen!!.s("name")}", { onDone(); onClose() }) {
            Github.send("POST", "$base/actions/workflows/${chosen!!.optLong("id")}/dispatches", JSONObject().put("ref", ref.trim()))
        }
    }) {
        err?.let { ErrorBox(it) }
        if (wfs.isEmpty() && err == null) Text(t("No active workflows found. Add one first."))
        if (wfs.isNotEmpty()) Pick("Workflow", chosen?.s("name").orEmpty(), wfs.map { it.s("name") }) { n -> chosen = wfs.first { it.s("name") == n } }
        Field("Branch or tag", ref, { ref = it })
        Text(t("The workflow needs a workflow_dispatch trigger."), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
fun AddWorkflowDialog(base: String, def: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var detected by remember { mutableStateOf<String?>(null) }
    var type by remember { mutableStateOf("Android") }
    var file by remember { mutableStateOf("build.yml") }
    LaunchedEffect(Unit) {
        try {
            val names = Github.arr("$base/contents?ref=${enc(def)}").list().map { it.s("name") }
            detected = Detect.type(names)
            detected?.let { type = it }
        } catch (e: Exception) {}
    }
    FormDialog("Add build workflow", "Commit workflow", onClose, enabled = file.isNotBlank(), onConfirm = {
        scope.op(ctx, "Add workflow $file", onClose) {
            val yml = Detect.template(ctx, type)
            Github.send(
                "PUT", "$base/contents/.github/workflows/${encPath(file.trim())}",
                JSONObject().put("message", "Add $type build workflow").put("content", b64(yml.toByteArray())).put("branch", def)
            )
        }
    }) {
        Text(if (detected != null) "Detected project type: $detected" else "Project type not detected. Pick a template.", style = MaterialTheme.typography.bodySmall)
        Pick("Template", type, listOf("Android", "Flutter", "Node")) { type = it }
        Field("File name", file, { file = it })
        Text(t("Pushing workflow files needs the workflow scope on your token."), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
fun RunDialog(base: String, run0: JSONObject, onClose: () -> Unit, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val id = run0.optLong("id")
    var run by remember { mutableStateOf(run0) }
    var jobs by remember { mutableStateOf(listOf<JSONObject>()) }
    var arts by remember { mutableStateOf(listOf<JSONObject>()) }
    var tick by remember { mutableIntStateOf(0) }
    var logJob by remember { mutableStateOf<JSONObject?>(null) }
    var saved by remember { mutableStateOf<List<File>?>(null) }
    var prog by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { if (run0.s("status") != "completed") Store.watch(base, id) }
    LaunchedEffect(tick) {
        while (true) {
            try {
                run = Github.get("$base/actions/runs/$id")
                jobs = Github.get("$base/actions/runs/$id/jobs?per_page=100").optJSONArray("jobs")?.list().orEmpty()
                arts = Github.get("$base/actions/runs/$id/artifacts").optJSONArray("artifacts")?.list().orEmpty()
            } catch (e: Exception) {}
            if (run.s("status") == "completed") { Store.unwatch("$base|$id"); break }
            delay(5000)
        }
    }
    val st = run.s("status"); val cc = run.s("conclusion")
    val finished = st == "completed"
    fun act(label: String, path: String, method: String = "POST") =
        scope.op(ctx, label, { tick++; onChanged() }) { Github.send(method, "$base/actions/runs/$id$path") }

    FullDialog("Run #${run.optInt("run_number")}", onClose, actions = {
        IconButton(onClick = { openUrl(ctx, run.s("html_url")) }) { Icon(Icons.Default.OpenInBrowser, "Open on GitHub") }
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(statusIcon(st, cc), null, tint = statusColor(st, cc))
                        Text(run.s("display_title"), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    }
                    Text(
                        "${run.s("name")}  ${run.s("head_branch")}  ${run.s("head_sha").take(7)}  ${run.s("event")}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline
                    )
                    Pill(if (finished) cc.ifEmpty { "done" } else st.replace('_', ' '), statusColor(st, cc))
                    prog?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!finished) OutlinedButton(onClick = { act("Cancel run #${run.optInt("run_number")}", "/cancel") }) { Icon(Icons.Default.Stop, null); Spacer(Modifier.width(6.dp)); Text(t("Cancel")) }
                    if (finished) {
                        OutlinedButton(onClick = { act("Re-run #${run.optInt("run_number")}", "/rerun") }) { Icon(Icons.Default.Replay, null); Spacer(Modifier.width(6.dp)); Text(t("Re-run")) }
                        if (cc == "failure") OutlinedButton(onClick = { act("Re-run failed jobs", "/rerun-failed-jobs") }) { Text(t("Re-run failed jobs")) }
                    }
                }
            }
            item { SectionTitle("Jobs") }
            items(jobs) { j ->
                val js = j.s("status"); val jc = j.s("conclusion")
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(statusIcon(js, jc), null, tint = statusColor(js, jc))
                        Text(j.s("name"), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { logJob = j }) { Text(t("Logs")) }
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
            if (arts.isEmpty()) item { Text(if (finished) "This run produced no artifacts." else "Artifacts appear when the run finishes.", style = MaterialTheme.typography.bodySmall) }
            items(arts) { a ->
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(a.s("name"), fontWeight = FontWeight.SemiBold)
                            Text(human(a.optLong("size_in_bytes")) + if (a.optBoolean("expired")) "  expired" else "", style = MaterialTheme.typography.labelSmall)
                        }
                        Button(enabled = !a.optBoolean("expired") && prog == null, onClick = {
                            scope.op(ctx, "Download artifact ${a.s("name")}") {
                                try {
                                    val aid = a.optLong("id")
                                    val dir = Files.downloads(ctx)
                                    val zip = File(dir, "${a.s("name")}-$aid.zip")
                                    prog = "Downloading ${a.s("name")}..."
                                    Github.download("$base/actions/artifacts/$aid/zip", zip) { d, t ->
                                        prog = "Downloading ${human(d)}" + if (t > 0) " of ${human(t)}" else ""
                                    }
                                    prog = "Extracting..."
                                    saved = withContext(Dispatchers.IO) { Files.unzip(zip, File(dir, "artifact-$aid")) }
                                } finally { prog = null }
                            }
                        }) { Icon(Icons.Default.Download, null); Spacer(Modifier.width(6.dp)); Text(t("Get")) }
                    }
                }
            }
        }
    }
    logJob?.let { LogDialog(base, it) { logJob = null } }
    saved?.let { SavedDialog(it) { saved = null } }
}

@Composable
fun LogDialog(base: String, job: JSONObject, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var lines by remember { mutableStateOf<List<String>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var q by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        try {
            val t = Github.text("GET", "$base/actions/jobs/${job.optLong("id")}/logs")
            lines = t.takeLast(400_000).lines()
        } catch (e: CancellationException) { throw e } catch (e: Exception) { err = e.message }
    }
    FullDialog(job.s("name"), onClose, actions = {
        IconButton(onClick = {
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText(t("log"), lines.orEmpty().joinToString("\n")))
            toast(ctx, "Log copied")
        }) { Icon(Icons.Default.ContentCopy, "Copy") }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Field("Filter lines", q, { q = it }, Modifier.fillMaxWidth().padding(12.dp, 8.dp))
            val all = lines
            val e = err
            when {
                e != null -> Box(Modifier.padding(12.dp)) { ErrorBox(e) }
                all == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                else -> {
                    val shown = remember(all, q) { if (q.isBlank()) all else all.filter { it.contains(q, true) } }
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
                        items(shown) { l ->
                            Text(
                                l, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                                color = if (l.contains("##[error]")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}
