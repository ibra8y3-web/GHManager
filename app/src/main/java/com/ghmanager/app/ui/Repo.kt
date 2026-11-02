package com.ghmanager.app.ui

import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoScreen(owner: String, name: String, back: () -> Unit) {
    val ctx = LocalContext.current
    val tabs = listOf(
        "Files" to Icons.Outlined.Folder, "Commits" to Icons.Outlined.History, "Branches" to Icons.Outlined.AccountTree,
        "Issues" to Icons.Outlined.BugReport, "Pull requests" to Icons.Outlined.CallMerge, "Actions" to Icons.Outlined.PlayCircle,
        "Releases" to Icons.Outlined.Sell, "Local" to Icons.Outlined.PhoneAndroid, "Manage" to Icons.Outlined.Settings
    )
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var repo by remember { mutableStateOf<JSONObject?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    val base = "/repos/$owner/$name"
    var starred by remember { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(owner, name) {
        try { repo = Github.get(base) } catch (e: CancellationException) { throw e } catch (e: Exception) { err = e.message }
        try { Github.text("GET", "/user/starred/$owner/$name"); starred = true } catch (e: ApiException) { starred = false } catch (e: CancellationException) { throw e } catch (e: Exception) {}
    }
    Scaffold(topBar = {
        Column {
            TopAppBar(
                title = { Column { Text(name, fontWeight = FontWeight.Bold, maxLines = 1); Text(owner, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline) } },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.Default.ArrowBack, "Back") } },
                actions = {
                    val st = starred
                    if (st != null) IconButton(onClick = {
                        scope.op(ctx, if (st) "Unstar $owner/$name" else "Star $owner/$name", { starred = !st }) {
                            Github.send(if (st) "DELETE" else "PUT", "/user/starred/$owner/$name")
                        }
                    }) { Icon(if (st) Icons.Default.Star else Icons.Default.StarBorder, "Star", tint = if (st) Warn else MaterialTheme.colorScheme.onSurface) }
                    IconButton(onClick = { openUrl(ctx, "https://github.com/$owner/$name") }) { Icon(Icons.Default.OpenInBrowser, "Open on GitHub") }
                }
            )
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                tabs.forEachIndexed { i, (tt, ic) -> Tab(tab == i, { tab = i }, text = { Text(t(tt)) }, icon = { Icon(ic, null) }) }
            }
        }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            val e = err
            if (e != null) ErrorBox(e)
            else when (tab) {
                0 -> FilesTab(base, repo)
                1 -> CommitsTab(base)
                2 -> BranchesTab(base, repo)
                3 -> ThreadsTab(base, false)
                4 -> ThreadsTab(base, true)
                5 -> ActionsTab(base, repo)
                6 -> ReleasesTab(base, repo)
                7 -> LocalTab(owner, name, repo)
                else -> ManageTab(owner, name, repo, { repo = it }, back)
            }
        }
    }
}

@Composable
fun FilesTab(base: String, repo: JSONObject?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var path by rememberSaveable { mutableStateOf("") }
    var ref by rememberSaveable { mutableStateOf("") }
    var branches by remember { mutableStateOf(listOf<String>()) }
    var entries by remember { mutableStateOf(listOf<JSONObject>()) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<JSONObject?>(null) }
    var menu by remember { mutableStateOf(false) }
    var newFile by remember { mutableStateOf(false) }
    val def = repo?.s("default_branch").orEmpty()
    LaunchedEffect(def) { if (ref.isEmpty() && def.isNotEmpty()) ref = def }
    LaunchedEffect(base) {
        try { branches = Github.arr("$base/branches?per_page=100").list().map { it.s("name") } } catch (e: Exception) {}
    }
    LaunchedEffect(path, ref, reload) {
        if (ref.isEmpty()) return@LaunchedEffect
        loading = true; err = null
        try {
            val r = Github.text("GET", "$base/contents/${encPath(path)}?ref=${enc(ref)}")
            entries = if (r.trim().startsWith("[")) {
                JSONArray(r).list().sortedWith(compareBy({ it.s("type") != "dir" }, { it.s("name").lowercase() }))
            } else emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            entries = emptyList()
            err = if (e.code == 404 && path.isEmpty()) "This repository is empty. Use Add to create the first file." else e.message
        } catch (e: Exception) {
            err = e.message
        }
        loading = false
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.op(ctx, "Upload file", { reload++ }) {
            val nm = ctx.displayName(uri)
            val bytes = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }
            val target = if (path.isEmpty()) nm else "$path/$nm"
            Github.send("PUT", "$base/contents/${encPath(target)}", JSONObject().put("message", "Add $nm").put("content", b64(bytes)).put("branch", ref))
        }
    }
    Box(Modifier.fillMaxSize()) {
        Column {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.width(170.dp)) { Pick("Branch", ref.ifEmpty { "..." }, branches) { ref = it; path = "" } }
                Text(
                    "/$path", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 1,
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState())
                )
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            err?.let { Box(Modifier.padding(12.dp)) { ErrorBox(it) } }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (path.isNotEmpty()) item {
                    Panel(onClick = { path = path.substringBeforeLast('/', "") }) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Icon(Icons.Default.ArrowUpward, null); Text(t("..")) }
                    }
                }
                items(entries) { f ->
                    val dir = f.s("type") == "dir"
                    Panel(onClick = { if (dir) path = f.s("path") else open = f }) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(if (dir) Icons.Default.Folder else Icons.Default.Description, null, tint = if (dir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                            Text(f.s("name"), Modifier.weight(1f), maxLines = 1)
                            if (!dir) Text(human(f.optLong("size")), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
        }
        Box(Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            ExtendedFloatingActionButton(onClick = { menu = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text(t("Add")) })
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text(t("New text file")) }, leadingIcon = { Icon(Icons.Default.NoteAdd, null) }, onClick = { menu = false; newFile = true })
                DropdownMenuItem(text = { Text(t("Upload from phone")) }, leadingIcon = { Icon(Icons.Default.UploadFile, null) }, onClick = { menu = false; picker.launch(arrayOf("*/*")) })
            }
        }
    }
    if (newFile) {
        var p by remember { mutableStateOf(if (path.isEmpty()) "" else "$path/") }
        var content by remember { mutableStateOf("") }
        var msg by remember { mutableStateOf("Add file") }
        FormDialog("New file", "Commit", { newFile = false }, enabled = p.isNotBlank() && !p.endsWith("/"), onConfirm = {
            scope.op(ctx, "Create $p", { newFile = false; reload++ }) {
                Github.send("PUT", "$base/contents/${encPath(p)}", JSONObject().put("message", msg).put("content", b64(content.toByteArray())).put("branch", ref))
            }
        }) {
            Field("Path", p, { p = it }, mono = true)
            Field("Content", content, { content = it }, singleLine = false, mono = true)
            Field("Commit message", msg, { msg = it })
        }
    }
    open?.let { f -> FileDialog(base, ref, f, { open = null }) { open = null; reload++ } }
}

@Composable
fun FileDialog(base: String, ref: String, file: JSONObject, onClose: () -> Unit, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val path = file.s("path")
    var text by remember { mutableStateOf<String?>(null) }
    var raw by remember { mutableStateOf<ByteArray?>(null) }
    var sha by remember { mutableStateOf(file.s("sha")) }
    var mode by remember { mutableStateOf("view") }
    var draft by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("Update $path") }
    var newPath by remember { mutableStateOf(path) }
    var editable by remember { mutableStateOf(false) }
    LaunchedEffect(path) {
        try {
            val j = Github.get("$base/contents/${encPath(path)}?ref=${enc(ref)}")
            sha = j.s("sha")
            val bytes = Base64.decode(j.s("content").replace("\n", ""), Base64.DEFAULT)
            raw = bytes
            when {
                bytes.size > 1_000_000 -> text = "Too large to preview (${human(bytes.size.toLong())})"
                bytes.any { it == 0.toByte() } -> text = "Binary file (${human(bytes.size.toLong())})"
                else -> { text = String(bytes); draft = text!!; editable = true }
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { text = "Could not load: ${e.message}" }
    }
    FullDialog(path, onClose, actions = {
        if (editable) IconButton(onClick = { mode = "edit" }) { Icon(Icons.Default.Edit, "Edit") }
        IconButton(onClick = { mode = "rename" }) { Icon(Icons.Default.DriveFileRenameOutline, "Rename") }
        IconButton(onClick = { mode = "delete" }) { Icon(Icons.Default.Delete, "Delete") }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(12.dp)) {
            if (mode == "edit") {
                Field("Commit message", msg, { msg = it })
                Spacer(Modifier.height(8.dp))
                Field("Content", draft, { draft = it }, Modifier.fillMaxWidth().weight(1f), singleLine = false, mono = true)
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    scope.op(ctx, "Edit $path", onChanged) {
                        Github.send("PUT", "$base/contents/${encPath(path)}", JSONObject().put("message", msg).put("content", b64(draft.toByteArray())).put("sha", sha).put("branch", ref))
                    }
                }, Modifier.fillMaxWidth()) { Text(t("Commit changes")) }
            } else {
                val t = text
                if (t == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                else Text(
                    t, Modifier.weight(1f).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()),
                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                )
            }
        }
    }
    if (mode == "rename") FormDialog("Rename or move", "Rename", { mode = "view" }, enabled = newPath.isNotBlank() && newPath != path, onConfirm = {
        scope.op(ctx, "Rename $path", onChanged) {
            val bytes = raw ?: throw ApiException(0, "File not loaded yet")
            Github.send("PUT", "$base/contents/${encPath(newPath)}", JSONObject().put("message", "Move $path to $newPath").put("content", b64(bytes)).put("branch", ref))
            Github.send("DELETE", "$base/contents/${encPath(path)}", JSONObject().put("message", "Move $path to $newPath").put("sha", sha).put("branch", ref))
        }
    }) { Field("New path", newPath, { newPath = it }, mono = true) }
    if (mode == "delete") AlertDialog(
        onDismissRequest = { mode = "view" }, title = { Text(t("Delete file?")) },
        text = { Text("$path will be removed in a new commit on $ref.") },
        confirmButton = {
            Button(onClick = {
                scope.op(ctx, "Delete $path", onChanged) {
                    Github.send("DELETE", "$base/contents/${encPath(path)}", JSONObject().put("message", "Delete $path").put("sha", sha).put("branch", ref))
                }
            }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text(t("Delete")) }
        },
        dismissButton = { TextButton(onClick = { mode = "view" }) { Text(t("Cancel")) } }
    )
}

@Composable
fun CommitsTab(base: String) {
    var sel by remember { mutableStateOf<String?>(null) }
    PagedList("$base/commits", empty = "No commits") { c ->
        val cm = c.getJSONObject("commit")
        Panel(onClick = { sel = c.s("sha") }) {
            Text(cm.s("message").lines().first(), fontWeight = FontWeight.SemiBold, maxLines = 2)
            Text(
                "${cm.optJSONObject("author")?.s("name").orEmpty()}  ${c.s("sha").take(7)}  ${ago(cm.optJSONObject("author")?.s("date").orEmpty())}",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline
            )
        }
    }
    sel?.let { CommitDialog(base, it) { sel = null } }
}

@Composable
fun CommitDialog(base: String, sha: String, onClose: () -> Unit) {
    var c by remember { mutableStateOf<JSONObject?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(sha) { try { c = Github.get("$base/commits/$sha") } catch (e: CancellationException) { throw e } catch (e: Exception) { err = e.message } }
    FullDialog(sha.take(7), onClose) { pad ->
        val j = c
        when {
            err != null -> Box(Modifier.padding(pad).padding(12.dp)) { ErrorBox(err!!) }
            j == null -> LinearProgressIndicator(Modifier.padding(pad).fillMaxWidth())
            else -> Column(Modifier.padding(pad)) {
                Column(Modifier.padding(12.dp)) {
                    Text(j.getJSONObject("commit").s("message"), fontWeight = FontWeight.SemiBold)
                    val st = j.optJSONObject("stats")
                    if (st != null) Text("+${st.optInt("additions")} -${st.optInt("deletions")}  ${st.optInt("total")} lines", style = MaterialTheme.typography.labelMedium)
                }
                DiffView((j.optJSONArray("files") ?: JSONArray()).list())
            }
        }
    }
}

@Composable
fun DiffView(files: List<JSONObject>) {
    val lines = remember(files) {
        val out = mutableListOf<Pair<Int, String>>()
        files.forEach { f ->
            out.add(0 to "${f.s("filename")}  +${f.optInt("additions")} -${f.optInt("deletions")}")
            val p = f.s("patch")
            if (p.isEmpty()) out.add(3 to "(no text diff)")
            else p.lines().forEach { l -> out.add((when { l.startsWith("+") -> 1; l.startsWith("-") -> 2; l.startsWith("@@") -> 4; else -> 3 }) to l) }
        }
        out
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(lines) { (k, t) ->
            val bg = when (k) {
                0 -> MaterialTheme.colorScheme.surfaceVariant
                1 -> Good.copy(alpha = 0.16f)
                2 -> MaterialTheme.colorScheme.error.copy(alpha = 0.16f)
                else -> androidx.compose.ui.graphics.Color.Transparent
            }
            Text(
                t, Modifier.fillMaxWidth().background(bg).padding(horizontal = 10.dp, vertical = if (k == 0) 8.dp else 1.dp),
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = if (k == 0) FontWeight.Bold else FontWeight.Normal),
                color = if (k == 4) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun BranchesTab(base: String, repo: JSONObject?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tags by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var create by remember { mutableStateOf(false) }
    var cmp by remember { mutableStateOf<String?>(null) }
    var del by remember { mutableStateOf<String?>(null) }
    val def = repo?.s("default_branch").orEmpty()
    Box(Modifier.fillMaxSize()) {
        Column {
            Row(Modifier.padding(12.dp, 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!tags, { tags = false }, { Text(t("Branches")) })
                FilterChip(tags, { tags = true }, { Text(t("Tags")) })
            }
            PagedList(if (tags) "$base/tags" else "$base/branches", reload, empty = if (tags) "No tags" else "No branches") { b ->
                val n = b.s("name")
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (tags) Icons.Default.Sell else Icons.Default.AccountTree, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(n, fontWeight = FontWeight.SemiBold)
                            val sh = (b.optJSONObject("commit")?.s("sha") ?: "").take(7)
                            Text(sh, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        }
                        if (n == def) Pill("default", Good)
                        if (b.optBoolean("protected")) Pill("protected", Warn)
                        if (!tags && n != def) IconButton(onClick = { cmp = n }) { Icon(Icons.Default.CompareArrows, "Compare") }
                        if (n != def && !b.optBoolean("protected")) IconButton(onClick = { del = n }) { Icon(Icons.Default.Delete, "Delete") }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { create = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text(if (tags) "New tag" else "New branch") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        )
    }
    if (create) {
        var n by remember { mutableStateOf("") }
        var src by remember { mutableStateOf(def) }
        FormDialog(if (tags) "New tag" else "New branch", "Create", { create = false }, enabled = n.isNotBlank() && src.isNotBlank(), onConfirm = {
            scope.op(ctx, "Create ${if (tags) "tag" else "branch"} $n", { create = false; reload++ }) {
                val sha = Github.get("$base/git/ref/heads/${encPath(src)}").getJSONObject("object").getString("sha")
                Github.send("POST", "$base/git/refs", JSONObject().put("ref", "refs/${if (tags) "tags" else "heads"}/${n.trim()}").put("sha", sha))
            }
        }) {
            Field("Name", n, { n = it })
            Field("From branch", src, { src = it })
        }
    }
    del?.let { n ->
        AlertDialog(
            onDismissRequest = { del = null }, title = { Text("Delete $n?") },
            text = { Text("The ${if (tags) "tag" else "branch"} will be removed from GitHub.") },
            confirmButton = {
                Button(onClick = {
                    scope.op(ctx, "Delete $n", { del = null; reload++ }) {
                        Github.send("DELETE", "$base/git/refs/${if (tags) "tags" else "heads"}/${encPath(n)}")
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text(t("Delete")) }
            },
            dismissButton = { TextButton(onClick = { del = null }) { Text(t("Cancel")) } }
        )
    }
    cmp?.let { CompareDialog(base, def, it, { cmp = null }) { reload++ } }
}

@Composable
fun CompareDialog(base: String, baseBranch: String, head: String, onClose: () -> Unit, onMerged: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var c by remember { mutableStateOf<JSONObject?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(head) {
        try { c = Github.get("$base/compare/${encPath(baseBranch)}...${encPath(head)}") } catch (e: CancellationException) { throw e } catch (e: Exception) { err = e.message }
    }
    FullDialog("$baseBranch ... $head", onClose) { pad ->
        val j = c
        when {
            err != null -> Box(Modifier.padding(pad).padding(12.dp)) { ErrorBox(err!!) }
            j == null -> LinearProgressIndicator(Modifier.padding(pad).fillMaxWidth())
            else -> Column(Modifier.padding(pad)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("$head is ${j.optInt("ahead_by")} ahead and ${j.optInt("behind_by")} behind $baseBranch (${j.s("status")}).")
                    if (j.optInt("ahead_by") > 0) Button(onClick = {
                        scope.op(ctx, "Merge $head into $baseBranch", { onMerged(); onClose() }) {
                            Github.send("POST", "$base/merges", JSONObject().put("base", baseBranch).put("head", head))
                        }
                    }) { Icon(Icons.Default.CallMerge, null); Spacer(Modifier.width(8.dp)); Text("Merge into $baseBranch") }
                    Text(t("If the merge reports a conflict, resolve it from a pull request on GitHub."), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
                DiffView((j.optJSONArray("files") ?: JSONArray()).list())
            }
        }
    }
}
