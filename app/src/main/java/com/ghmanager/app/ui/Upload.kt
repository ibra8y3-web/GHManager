package com.ghmanager.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.unit.dp
import com.ghmanager.app.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun UploadScreen(open: (String, String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tree by remember { mutableStateOf<Uri?>(null) }
    var files by remember { mutableStateOf<List<Local>>(emptyList()) }
    var findings by remember { mutableStateOf<List<Finding>>(emptyList()) }
    val allow = remember { mutableStateListOf<String>() }
    var scanning by remember { mutableStateOf(false) }
    var skipJunk by remember { mutableStateOf(true) }
    var target by remember { mutableStateOf<JSONObject?>(null) }
    var newMode by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newPrivate by remember { mutableStateOf(true) }
    var branch by remember { mutableStateOf("main") }
    var msg by remember { mutableStateOf("Upload from GitHub Manager") }
    var addWf by remember { mutableStateOf(false) }
    var autoLfs by remember { mutableStateOf(true) }
    var lfsPatterns by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var pick by remember { mutableStateOf(false) }
    var jobs by remember { mutableStateOf(Queue.all().reversed()) }
    var formErr by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { while (true) { jobs = Queue.all().reversed(); delay(1500) } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) {}
            tree = uri
        }
    }

    LaunchedEffect(tree, skipJunk) {
        val uri = tree ?: return@LaunchedEffect
        scanning = true
        val res = withContext(Dispatchers.IO) { val l = Tree.list(ctx, uri, skipJunk); l to Tree.scan(ctx, l) }
        files = res.first; findings = res.second; allow.clear(); scanning = false
    }

    val skipPaths = findings.map { it.path }.filter { it !in allow }.toSet()
    val sendCount = files.count { it.path !in skipPaths }
    val detected = Detect.type(files.map { it.path })
    val ready = tree != null && !scanning && !submitting && sendCount > 0 && branch.isNotBlank() &&
        (if (newMode) newName.isNotBlank() else target != null)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel {
            SectionTitle("1. Project folder")
            Button(onClick = { picker.launch(null) }, enabled = !submitting) {
                Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text(if (tree == null) t("Choose a folder") else t("Choose another folder"))
            }
            if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (tree != null && !scanning) {
                Text("${files.size} " + t("files") + ", ${human(files.sumOf { it.size })}", style = MaterialTheme.typography.bodySmall)
                if (detected != null) Text(t("Detected project type:") + " $detected", style = MaterialTheme.typography.bodySmall)
            }
            SwitchRow("Skip build and cache folders", skipJunk) { skipJunk = it }
        }
        if (findings.isNotEmpty()) Panel {
            SectionTitle("Sensitive files found")
            Text(t("These are excluded from the upload unless you tick them."), style = MaterialTheme.typography.bodySmall)
            findings.forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(f.path in allow, { c -> if (c) allow.add(f.path) else allow.remove(f.path) })
                    Column {
                        Text(f.path, style = MaterialTheme.typography.bodySmall)
                        Text(t(f.reason), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        Panel {
            SectionTitle("2. Destination")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!newMode, { newMode = false }, { Text(t("Existing repository")) })
                FilterChip(newMode, { newMode = true }, { Text(t("New repository")) })
            }
            if (newMode) {
                Field("New repository name", newName, { newName = it })
                SwitchRow("Private", newPrivate) { newPrivate = it }
            } else {
                OutlinedButton(onClick = { pick = true }) {
                    Icon(Icons.Default.Storage, null); Spacer(Modifier.width(8.dp)); Text(target?.s("full_name") ?: t("Choose a repository"))
                }
            }
            Field("Branch", branch, { branch = it })
            Field("Commit message", msg, { msg = it })
            if (detected != null) SwitchRow(t("Add a build workflow") + " ($detected)", addWf) { addWf = it }
        }
        Panel {
            SectionTitle("Git LFS")
            SwitchRow("Use Git LFS automatically for files over 50 MB", autoLfs) { autoLfs = it }
            Field("Extra LFS patterns (comma separated, e.g. *.apk,*.zip)", lfsPatterns, { lfsPatterns = it })
            Text(t("Large files are uploaded to GitHub LFS storage and replaced by pointers. Your account needs LFS quota."), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
        formErr?.let { ErrorBox(it) }
        Button(
            onClick = {
                submitting = true; formErr = null
                scope.launch {
                    try {
                        val owner: String
                        val repo: String
                        if (newMode) {
                            val r = Github.send("POST", "/user/repos", JSONObject().put("name", newName.trim()).put("private", newPrivate))
                            owner = r.getJSONObject("owner").s("login"); repo = r.s("name")
                        } else {
                            owner = target!!.getJSONObject("owner").s("login"); repo = target!!.s("name")
                        }
                        val job = UploadJob.create(
                            owner, repo, branch.trim(), msg, tree.toString(), skipPaths.toList(), skipJunk,
                            if (addWf) detected else null,
                            lfsPatterns.split(",").map { it.trim() }.filter { it.isNotEmpty() }, if (autoLfs) 50 else 0
                        )
                        Queue.enqueue(ctx, job)
                        jobs = Queue.all().reversed()
                        Github.repoVersion++
                        toast(ctx, "Queued: $owner/$repo")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        formErr = e.message
                    }
                    submitting = false
                }
            },
            enabled = ready, modifier = Modifier.fillMaxWidth().height(54.dp)
        ) {
            Icon(Icons.Default.CloudUpload, null); Spacer(Modifier.width(8.dp)); Text(t("Queue upload of") + " $sendCount " + t("files"))
        }
        if (jobs.isNotEmpty()) {
            SectionTitle("Upload queue")
            jobs.forEach { j ->
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(j.label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        Pill(j.state, when (j.state) {
                            "done" -> Good
                            "failed" -> MaterialTheme.colorScheme.error
                            "running" -> MaterialTheme.colorScheme.secondary
                            else -> Warn
                        })
                    }
                    if (j.state == "running" && j.total > 0) LinearProgressIndicator(progress = { j.done / j.total.toFloat() }, modifier = Modifier.fillMaxWidth())
                    if (j.detail.isNotEmpty()) Text(j.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (j.state in listOf("queued", "running", "waiting")) OutlinedButton(onClick = { Queue.cancel(ctx, j.id); jobs = Queue.all().reversed() }) { Text(t("Cancel")) }
                        if (j.state in listOf("failed", "cancelled", "waiting")) OutlinedButton(onClick = { Queue.enqueue(ctx, j); jobs = Queue.all().reversed() }) { Text(t("Retry")) }
                        if (j.state == "done") Button(onClick = { open(j.owner, j.repo) }) { Text(t("Open repository")) }
                        if (j.state in listOf("done", "failed", "cancelled")) TextButton(onClick = { Queue.remove(ctx, j.id); jobs = Queue.all().reversed() }) { Text(t("Remove")) }
                    }
                }
            }
            Text(t("Uploads continue in the background and resume automatically after a lost connection."), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
        Spacer(Modifier.height(24.dp))
    }

    if (pick) {
        var q by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pick = false },
            title = { Text(t("Choose a repository")) },
            text = {
                Column(Modifier.height(420.dp)) {
                    Field("Search", q, { q = it })
                    PagedList(
                        "/user/repos?sort=updated&affiliation=owner,collaborator,organization_member",
                        filter = { r -> q.isBlank() || r.s("full_name").contains(q, true) }
                    ) { r ->
                        Panel(onClick = { target = r; branch = r.s("default_branch").ifEmpty { "main" }; pick = false }) { Text(r.s("full_name")) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pick = false }) { Text(t("Close")) } }
        )
    }
}
