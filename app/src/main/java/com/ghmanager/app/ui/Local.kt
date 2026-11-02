package com.ghmanager.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ghmanager.app.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

private suspend fun identity(): Pair<String, String> {
    val u = Github.get("/user")
    val login = u.s("login")
    return u.s("name").ifEmpty { login } to u.s("email").ifEmpty { "${u.optLong("id")}+$login@users.noreply.github.com" }
}

@Composable
fun LocalTab(owner: String, name: String, repo: JSONObject?) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val dir = remember(owner, name) { LocalGit.dir(ctx, owner, name) }
    var cloned by remember { mutableStateOf(LocalGit.cloned(dir)) }
    var info by remember { mutableStateOf<LocalInfo?>(null) }
    var branches by remember { mutableStateOf(listOf<String>()) }
    var history by remember { mutableStateOf(listOf<CommitLine>()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var resolve by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var confirmDel by remember { mutableStateOf(false) }

    LaunchedEffect(cloned, reload) {
        if (cloned) try {
            info = LocalGit.info(dir); branches = LocalGit.branches(dir); history = LocalGit.log(dir, 15)
        } catch (e: CancellationException) { throw e } catch (e: Exception) { status = e.message }
    }
    fun run(label: String, block: suspend () -> String?) {
        scope.launch {
            busy = label; status = null
            try { status = block(); Store.log(label, true) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { status = e.message; Store.log(label, false, e.message.orEmpty()) }
            busy = null; reload++
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel {
            SectionTitle("Local copy on this phone")
            Text(dir.absolutePath, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            if (busy != null) { Text("${busy}...", style = MaterialTheme.typography.bodySmall); LinearProgressIndicator(Modifier.fillMaxWidth()) }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            if (!cloned) Button(onClick = {
                run("Clone $owner/$name") {
                    LocalGit.clone(dir, owner, name, repo?.s("default_branch").orEmpty().ifEmpty { "main" })
                    cloned = true; "Cloned"
                }
            }, enabled = busy == null) { Icon(Icons.Default.CloudDownload, null); Spacer(Modifier.width(6.dp)); Text(t("Clone")) }
        }
        val i = info
        if (cloned && i != null) {
            Panel {
                Text(t("Branch:") + " ${i.branch}   +${i.ahead} / -${i.behind}   ${i.state.lowercase()}", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { run("Fetch") { LocalGit.fetch(dir); "Fetched" } }, enabled = busy == null) { Text(t("Fetch")) }
                    OutlinedButton(onClick = { run("Pull") { LocalGit.pull(dir) } }, enabled = busy == null) { Text(t("Pull")) }
                    OutlinedButton(onClick = { run("Push") { LocalGit.push(dir) } }, enabled = busy == null) { Text(t("Push")) }
                }
            }
            if (i.conflicts.isNotEmpty()) Panel {
                SectionTitle("Conflicts")
                i.conflicts.forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, tint = Warn)
                        Spacer(Modifier.width(8.dp))
                        Text(c, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { resolve = c }) { Text(t("Resolve")) }
                    }
                }
                Text(t("Resolve every file, then commit to finish the merge."), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            Panel {
                SectionTitle("Commit")
                Field("Commit message", msg, { msg = it })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        run("Commit") { val (n, e) = identity(); "Committed " + LocalGit.commitAll(dir, msg, n, e).also { msg = "" } }
                    }, enabled = busy == null && msg.isNotBlank()) { Text(t("Commit all")) }
                    OutlinedButton(onClick = {
                        run("Commit and push") {
                            val (n, e) = identity(); val sha = LocalGit.commitAll(dir, msg, n, e); msg = ""
                            LocalGit.push(dir); "Committed $sha and pushed"
                        }
                    }, enabled = busy == null && msg.isNotBlank()) { Text(t("Commit and push")) }
                }
                if (i.changes.isEmpty()) Text(t("Working tree clean"), style = MaterialTheme.typography.bodySmall)
                i.changes.take(40).forEach { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
            }
            Panel {
                SectionTitle("Branches")
                Pick("Checkout", i.branch, branches) { b -> run("Checkout $b") { LocalGit.checkout(dir, b); "Switched to $b" } }
                Pick("Merge into current", "select", branches) { b -> run("Merge $b") { LocalGit.merge(dir, b) } }
            }
            Panel {
                SectionTitle("History")
                history.forEach { Text("${it.sha}  ${it.message}  (${it.author})", style = MaterialTheme.typography.bodySmall) }
            }
            OutlinedButton(onClick = { confirmDel = true }) { Icon(Icons.Default.DeleteForever, null); Spacer(Modifier.width(6.dp)); Text(t("Delete local copy")) }
        }
        Spacer(Modifier.height(24.dp))
    }
    if (confirmDel) AlertDialog(
        onDismissRequest = { confirmDel = false }, title = { Text(t("Delete local copy?")) },
        text = { Text(t("Uncommitted changes on this phone will be lost. The GitHub repository is not touched.")) },
        confirmButton = {
            Button(onClick = { confirmDel = false; scope.launch { LocalGit.delete(dir); cloned = false; info = null } },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text(t("Delete")) }
        },
        dismissButton = { TextButton(onClick = { confirmDel = false }) { Text(t("Cancel")) } }
    )
    resolve?.let { p -> ResolveDialog(dir, p, { resolve = null }) { resolve = null; reload++ } }
}

@Composable
fun ResolveDialog(dir: File, path: String, onClose: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(path) { try { text = LocalGit.readText(dir, path) } catch (e: CancellationException) { throw e } catch (e: Exception) { err = e.message } }
    fun act(block: suspend () -> Unit) {
        scope.launch { try { block(); onDone() } catch (e: CancellationException) { throw e } catch (e: Exception) { err = e.message } }
    }
    FullDialog(path, onClose) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            err?.let { ErrorBox(it) }
            Text(t("Edit the text and remove the <<<<<<<, ======= and >>>>>>> lines, or take one side entirely."), style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { act { LocalGit.take(dir, path, false) } }) { Text(t("Use mine")) }
                OutlinedButton(onClick = { act { LocalGit.take(dir, path, true) } }) { Text(t("Use theirs")) }
            }
            val tx = text
            if (tx == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else {
                Field("File content", tx, { text = it }, Modifier.fillMaxWidth().weight(1f), singleLine = false, mono = true)
                Button(onClick = { act { LocalGit.saveResolved(dir, path, text.orEmpty()) } }, modifier = Modifier.fillMaxWidth()) { Text(t("Save as resolved")) }
            }
        }
    }
}

/** One-tap flow for a pull request that has conflicts: clone, merge the base branch into the PR branch, resolve, push. */
@Composable
fun PrConflictDialog(
    owner: String, name: String, baseRef: String, headRef: String, sameRepo: Boolean,
    onClose: () -> Unit, onDone: () -> Unit
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val dir = remember(owner, name) { LocalGit.dir(ctx, owner, name) }
    var log by remember { mutableStateOf(listOf<String>()) }
    var busy by remember { mutableStateOf(false) }
    var conflicts by remember { mutableStateOf(listOf<String>()) }
    var merging by remember { mutableStateOf(false) }
    var resolve by remember { mutableStateOf<String?>(null) }
    var finished by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }

    fun add(s: String) { log = log + s }

    suspend fun refreshState() {
        val i = LocalGit.info(dir)
        conflicts = i.conflicts
        merging = i.state == "MERGING"
    }

    suspend fun finish() {
        refreshState()
        if (conflicts.isNotEmpty()) throw IllegalStateException("Resolve all conflicted files first.")
        if (merging) {
            val (n, e) = identity()
            add("Committing the merge...")
            LocalGit.commitAll(dir, "Merge branch '$baseRef' into $headRef", n, e)
        }
        add("Pushing $headRef...")
        LocalGit.push(dir)
        add("Done. The pull request should be mergeable now.")
        finished = true
        onDone()
    }

    fun launchStep(block: suspend () -> Unit) {
        scope.launch {
            busy = true; err = null
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { err = e.message; Store.log("Resolve PR conflicts", false, e.message.orEmpty()) }
            busy = false
        }
    }

    FullDialog("Resolve conflicts", onClose) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!sameRepo) {
                Panel {
                    Text("This pull request comes from a fork, so its branch is not in this repository. Open the fork, clone it in the Local tab, merge $baseRef, resolve and push.", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Panel {
                    Text("$headRef  <-  $baseRef", style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace)
                    Text("The app clones the repository, merges $baseRef into $headRef on your phone, lets you fix each conflicted file, then pushes.", style = MaterialTheme.typography.bodySmall)
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (!finished && conflicts.isEmpty() && !merging) {
                        Button(onClick = {
                            launchStep {
                                if (!LocalGit.cloned(dir)) { add("Cloning (first time only)..."); LocalGit.clone(dir, owner, name, headRef) }
                                add("Fetching..."); LocalGit.fetch(dir)
                                val i0 = LocalGit.info(dir)
                                if (i0.state == "MERGING") {
                                    add("Continuing the unfinished merge.")
                                } else {
                                    if (i0.changes.isNotEmpty()) throw IllegalStateException("The local copy has uncommitted changes. Commit or delete it in the Local tab first.")
                                    add("Switching to $headRef"); LocalGit.checkout(dir, headRef)
                                    add(LocalGit.pull(dir))
                                    add("Merging $baseRef..."); add(LocalGit.merge(dir, baseRef))
                                }
                                refreshState()
                                if (conflicts.isEmpty()) finish() else add("${conflicts.size} file(s) have conflicts.")
                            }
                        }, enabled = !busy) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Start") }
                    }
                }
                if (conflicts.isNotEmpty()) Panel {
                    SectionTitle("Conflicts")
                    conflicts.forEach { c ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, null, tint = Warn)
                            Spacer(Modifier.width(8.dp))
                            Text(c, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Button(onClick = { resolve = c }) { Text("Resolve") }
                        }
                    }
                }
                if (merging && conflicts.isEmpty() && !finished) {
                    Button(onClick = { launchStep { finish() } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.CloudUpload, null); Spacer(Modifier.width(6.dp)); Text("Commit merge and push")
                    }
                }
                if (log.isNotEmpty()) Panel { log.forEach { Text(it, style = MaterialTheme.typography.bodySmall) } }
                err?.let { ErrorBox(it) }
                if (finished) Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Close") }
            }
        }
    }
    resolve?.let { p ->
        ResolveDialog(dir, p, { resolve = null }) {
            resolve = null
            scope.launch { try { refreshState() } catch (e: Exception) { err = e.message } }
        }
    }
}
