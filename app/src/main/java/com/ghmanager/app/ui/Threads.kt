package com.ghmanager.app.ui

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ghmanager.app.data.*
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

@Composable
fun ThreadsTab(base: String, pr: Boolean) {
    var state by remember { mutableStateOf("open") }
    var reload by remember { mutableIntStateOf(0) }
    var create by remember { mutableStateOf(false) }
    var sel by remember { mutableStateOf<JSONObject?>(null) }
    Box(Modifier.fillMaxSize()) {
        Column {
            Row(Modifier.padding(12.dp, 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("open", "closed", "all").forEach { s ->
                    FilterChip(state == s, { state = s }, { Text(t(s)) })
                }
            }
            PagedList(
                if (pr) "$base/pulls?state=$state" else "$base/issues?state=$state", reload,
                empty = if (pr) "No pull requests" else "No issues", filter = { pr || !it.has("pull_request") }
            ) { th ->
                Panel(onClick = { sel = th }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(th.s("title"), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 2)
                        val merged = th.s("merged_at").isNotEmpty()
                        when {
                            merged -> Pill("merged", MaterialTheme.colorScheme.tertiary)
                            th.s("state") == "open" -> Pill("open", Good)
                            else -> Pill("closed", MaterialTheme.colorScheme.error)
                        }
                    }
                    Text(
                        "#${th.optInt("number")}  ${th.optJSONObject("user")?.s("login").orEmpty()}  ${ago(th.s("created_at"))}  ${th.optInt("comments")} comments",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline
                    )
                    val labels = th.optJSONArray("labels")?.list().orEmpty()
                    if (labels.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        labels.forEach { l ->
                            val c = try { Color(android.graphics.Color.parseColor("#" + l.s("color"))) } catch (e: Exception) { Color.Gray }
                            Pill(l.s("name"), c)
                        }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { create = true }, icon = { Icon(Icons.Default.Add, null) },
            text = { Text(if (pr) "New pull request" else "New issue") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        )
    }
    if (create) CreateThreadDialog(base, pr, { create = false }) { reload++ }
    sel?.let { ThreadDialog(base, it, pr, { sel = null }) { reload++ } }
}

@Composable
fun CreateThreadDialog(base: String, pr: Boolean, onClose: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var head by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { try { target = Github.get(base).s("default_branch") } catch (e: Exception) {} }
    FormDialog(
        if (pr) "New pull request" else "New issue", "Create", onClose,
        enabled = title.isNotBlank() && (!pr || (head.isNotBlank() && target.isNotBlank())), onConfirm = {
            scope.op(ctx, "Create ${if (pr) "pull request" else "issue"}: $title", { onDone(); onClose() }) {
                if (pr) Github.send("POST", "$base/pulls", JSONObject().put("title", title).put("body", body).put("head", head.trim()).put("base", target.trim()))
                else Github.send("POST", "$base/issues", JSONObject().put("title", title).put("body", body))
            }
        }
    ) {
        Field("Title", title, { title = it })
        if (pr) {
            Field("Head branch (your changes)", head, { head = it })
            Field("Base branch", target, { target = it })
        }
        Field("Description", body, { body = it }, singleLine = false)
    }
}

@Composable
fun ThreadDialog(base: String, t0: JSONObject, pr: Boolean, onClose: () -> Unit, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val n = t0.optInt("number")
    var cur by remember { mutableStateOf(t0) }
    var comments by remember { mutableStateOf(listOf<JSONObject>()) }
    var reviews by remember { mutableStateOf(listOf<JSONObject>()) }
    var err by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var text by remember { mutableStateOf("") }
    var method by remember { mutableStateOf("merge") }
    var conflictDlg by remember { mutableStateOf(false) }
    LaunchedEffect(reload) {
        try {
            cur = Github.get(if (pr) "$base/pulls/$n" else "$base/issues/$n")
            comments = Github.arr("$base/issues/$n/comments?per_page=100").list()
            if (pr) reviews = Github.arr("$base/pulls/$n/reviews?per_page=100").list()
            err = null
        } catch (e: CancellationException) { throw e } catch (e: Exception) { err = e.message }
    }
    fun refresh() { reload++; onChanged() }
    val open = cur.s("state") == "open"
    val merged = cur.optBoolean("merged")
    val conflict = pr && !cur.isNull("mergeable") && !cur.optBoolean("mergeable", true)
    FullDialog("#$n ${cur.s("title")}", onClose, actions = {
        IconButton(onClick = { openUrl(ctx, cur.s("html_url")) }) { Icon(Icons.Default.OpenInBrowser, "Open on GitHub") }
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { err?.let { ErrorBox(it) } }
            item {
                Panel {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when {
                            merged -> Pill("merged", MaterialTheme.colorScheme.tertiary)
                            open -> Pill("open", Good)
                            else -> Pill("closed", MaterialTheme.colorScheme.error)
                        }
                        if (conflict) Pill("has conflicts", MaterialTheme.colorScheme.error)
                        if (pr) Pill("${cur.optJSONObject("head")?.s("ref").orEmpty()} into ${cur.optJSONObject("base")?.s("ref").orEmpty()}", MaterialTheme.colorScheme.secondary)
                    }
                    Text(cur.s("body").ifEmpty { "No description." })
                    if (pr) Text(
                        "${cur.optInt("commits")} commits  ${cur.optInt("changed_files")} files  +${cur.optInt("additions")} -${cur.optInt("deletions")}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline
                    )
                }
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        scope.op(ctx, "${if (open) "Close" else "Reopen"} #$n", ::refresh) {
                            Github.send("PATCH", "$base/issues/$n", JSONObject().put("state", if (open) "closed" else "open"))
                        }
                    }) { Text(if (open) "Close" else "Reopen") }
                    if (pr && open) {
                        OutlinedButton(onClick = {
                            scope.op(ctx, "Update branch of #$n", ::refresh) { Github.send("PUT", "$base/pulls/$n/update-branch") }
                        }) { Text(t("Update branch")) }
                    }
                }
            }
            if (pr && open) item {
                Panel {
                    SectionTitle("Merge")
                    Pick("Method", method, listOf("merge", "squash", "rebase")) { method = it }
                    Button(onClick = {
                        scope.op(ctx, "Merge #$n ($method)", ::refresh) {
                            Github.send("PUT", "$base/pulls/$n/merge", JSONObject().put("merge_method", method))
                        }
                    }, enabled = !conflict, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.CallMerge, null); Spacer(Modifier.width(8.dp)); Text(t("Merge pull request"))
                    }
                    if (conflict) Text(t("Resolve the conflicts on GitHub, then come back and merge."), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
            if (pr && reviews.isNotEmpty()) {
                item { SectionTitle("Reviews") }
                items(reviews) { r ->
                    Panel {
                        Text("${r.optJSONObject("user")?.s("login").orEmpty()}  ${r.s("state").lowercase().replace('_', ' ')}", fontWeight = FontWeight.SemiBold)
                        if (r.s("body").isNotEmpty()) Text(r.s("body"))
                    }
                }
            }
            item { SectionTitle("Comments (${comments.size})") }
            items(comments) { c ->
                Panel {
                    Text("${c.optJSONObject("user")?.s("login").orEmpty()}  ${ago(c.s("created_at"))}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                    Text(c.s("body"))
                }
            }
            item {
                Panel {
                    Field("Write a comment or review", text, { text = it }, singleLine = false)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        Button(onClick = {
                            scope.op(ctx, "Comment on #$n", { text = ""; refresh() }) {
                                Github.send("POST", "$base/issues/$n/comments", JSONObject().put("body", text))
                            }
                        }, enabled = text.isNotBlank()) { Text(t("Comment")) }
                        if (pr && open) {
                            listOf("APPROVE" to "Approve", "REQUEST_CHANGES" to "Request changes").forEach { (ev, label) ->
                                OutlinedButton(onClick = {
                                    scope.op(ctx, "$label on #$n", { text = ""; refresh() }) {
                                        Github.send("POST", "$base/pulls/$n/reviews", JSONObject().put("event", ev).put("body", text))
                                    }
                                }, enabled = ev == "APPROVE" || text.isNotBlank()) { Text(label) }
                            }
                        }
                    }
                }
            }
        }
    }
    if (conflictDlg) {
        val parts = base.removePrefix("/repos/").split("/")
        val headRepo = cur.optJSONObject("head")?.optJSONObject("repo")?.s("full_name").orEmpty()
        val baseRepo = cur.optJSONObject("base")?.optJSONObject("repo")?.s("full_name").orEmpty()
        PrConflictDialog(
            parts[0], parts[1], cur.optJSONObject("base")?.s("ref").orEmpty(), cur.optJSONObject("head")?.s("ref").orEmpty(),
            headRepo.isNotEmpty() && headRepo == baseRepo, { conflictDlg = false }
        ) { refresh() }
    }
}
