package com.ghmanager.app.ui

import android.net.Uri
import android.util.Base64
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghmanager.app.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun GistsDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var create by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf<JSONObject?>(null) }
    var del by remember { mutableStateOf<JSONObject?>(null) }
    FullDialog("Gists", onClose, actions = { IconButton(onClick = { create = true }) { Icon(Icons.Default.Add, "New gist") } }) { pad ->
        Box(Modifier.padding(pad)) {
            PagedList("/gists", reload, empty = "No gists yet") { g ->
                val first = g.optJSONObject("files")?.keys()?.asSequence()?.firstOrNull().orEmpty()
                Panel(onClick = { view = g }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(g.s("description").ifEmpty { first }, fontWeight = FontWeight.SemiBold, maxLines = 2)
                            Text("${g.optJSONObject("files")?.length() ?: 0} files  ${ago(g.s("updated_at"))}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        }
                        Pill(if (g.optBoolean("public")) "public" else "secret", if (g.optBoolean("public")) Good else Warn)
                        IconButton(onClick = { del = g }) { Icon(Icons.Default.Delete, "Delete") }
                    }
                }
            }
        }
    }
    if (create) {
        var d by remember { mutableStateOf("") }
        var fn by remember { mutableStateOf("snippet.txt") }
        var body by remember { mutableStateOf("") }
        var pub by remember { mutableStateOf(false) }
        FormDialog("New gist", "Create", { create = false }, enabled = fn.isNotBlank() && body.isNotBlank(), onConfirm = {
            scope.op(ctx, "Create gist", { create = false; reload++ }) {
                Github.send("POST", "/gists", JSONObject().put("description", d).put("public", pub)
                    .put("files", JSONObject().put(fn.trim(), JSONObject().put("content", body))))
            }
        }) {
            Field("Description", d, { d = it })
            Field("File name", fn, { fn = it })
            Field("Content", body, { body = it }, singleLine = false, mono = true)
            SwitchRow("Public", pub) { pub = it }
        }
    }
    del?.let { g ->
        AlertDialog(
            onDismissRequest = { del = null }, title = { Text(t("Delete this gist?")) }, text = { Text(t("This cannot be undone.")) },
            confirmButton = {
                Button(onClick = { scope.op(ctx, "Delete gist", { del = null; reload++ }) { Github.send("DELETE", "/gists/${g.s("id")}") } },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text(t("Delete")) }
            },
            dismissButton = { TextButton(onClick = { del = null }) { Text(t("Cancel")) } }
        )
    }
    view?.let { g ->
        var full by remember { mutableStateOf<JSONObject?>(null) }
        LaunchedEffect(g) { try { full = Github.get("/gists/${g.s("id")}") } catch (e: Exception) {} }
        FullDialog(g.s("description").ifEmpty { "Gist" }, { view = null }) { pad ->
            Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val f = full
                if (f == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                else {
                    val files = f.optJSONObject("files")
                    if (files != null) for (k in files.keys()) {
                        Text(k, fontWeight = FontWeight.Bold)
                        Text(files.getJSONObject(k).s("content"), Modifier.horizontalScroll(rememberScrollState()), style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp))
                    }
                }
            }
        }
    }
}

@Composable
fun CodeSearchDialog(login: String, openRepo: (String, String) -> Unit, onClose: () -> Unit) {
    var q by remember { mutableStateOf("") }
    var scopeQ by remember { mutableStateOf(if (login.isNotEmpty()) "user:$login" else "") }
    var run by remember { mutableStateOf("") }
    FullDialog("Code search", onClose) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Search term", q, { q = it })
                Field("Scope (for example user:name or repo:owner/name)", scopeQ, { scopeQ = it })
                Button(onClick = { run = "$q $scopeQ".trim() }, enabled = q.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Search, null); Spacer(Modifier.width(6.dp)); Text(t("Search"))
                }
            }
            if (run.isNotEmpty()) PagedList("/search/code?q=${enc(run)}", unwrap = "items", empty = "No results") { r ->
                val repo = r.optJSONObject("repository")
                Panel(onClick = { if (repo != null) openRepo(repo.getJSONObject("owner").s("login"), repo.s("name")) }) {
                    Text(r.s("path"), fontWeight = FontWeight.SemiBold)
                    Text(repo?.s("full_name").orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

@Composable
fun StarredDialog(openRepo: (String, String) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    FullDialog("Starred repositories", onClose) { pad ->
        Box(Modifier.padding(pad)) {
            PagedList("/user/starred", reload, empty = "Nothing starred yet") { r ->
                Panel(onClick = { openRepo(r.getJSONObject("owner").s("login"), r.s("name")) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.s("full_name"), fontWeight = FontWeight.SemiBold)
                            Text(r.s("description"), style = MaterialTheme.typography.bodySmall, maxLines = 2, color = MaterialTheme.colorScheme.outline)
                        }
                        IconButton(onClick = {
                            scope.op(ctx, "Unstar ${r.s("full_name")}", { reload++ }) { Github.send("DELETE", "/user/starred/${r.s("full_name")}") }
                        }) { Icon(Icons.Default.Star, "Unstar", tint = Warn) }
                    }
                }
            }
        }
    }
}

@Composable
fun SecretsSection(base: String) {
    val ctx = LocalContext.current
    var signing by remember { mutableStateOf(false) }
    SimpleList(
        "$base/actions/secrets", "Add secret", listOf("Name", "Value"), 2,
        create = { v -> Crypto.putSecret(base, v[0].trim(), v[1].toByteArray()) },
        title = { it.s("name") }, sub = { "updated " + ago(it.s("updated_at")) },
        delete = { Github.send("DELETE", "$base/actions/secrets/${enc(it.s("name"))}") },
        unwrap = "secrets", hide = setOf(1),
        extra = {
            OutlinedButton(onClick = { signing = true }, modifier = Modifier.padding(horizontal = 12.dp)) {
                Icon(Icons.Default.Key, null); Spacer(Modifier.width(6.dp)); Text(t("Android signing secrets"))
            }
        }
    )
    if (signing) {
        var uri by remember { mutableStateOf<Uri?>(null) }
        var storePw by remember { mutableStateOf("") }
        var alias by remember { mutableStateOf("") }
        var keyPw by remember { mutableStateOf("") }
        val scope = rememberCoroutineScope()
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri = it }
        FormDialog("Signing secrets", "Save secrets", { signing = false }, enabled = uri != null && storePw.isNotBlank() && alias.isNotBlank(), onConfirm = {
            scope.op(ctx, "Save signing secrets", { signing = false }) {
                val bytes = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri!!)!!.use { it.readBytes() } }
                Crypto.putSecret(base, "KEYSTORE_BASE64", Base64.encodeToString(bytes, Base64.NO_WRAP).toByteArray())
                Crypto.putSecret(base, "KEYSTORE_PASSWORD", storePw.toByteArray())
                Crypto.putSecret(base, "KEY_ALIAS", alias.toByteArray())
                Crypto.putSecret(base, "KEY_PASSWORD", keyPw.ifBlank { storePw }.toByteArray())
            }
        }) {
            Text(t("Creates KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS and KEY_PASSWORD, encrypted for this repository."), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Icon(Icons.Default.AttachFile, null); Spacer(Modifier.width(6.dp)); Text(uri?.let { ctx.displayName(it) } ?: t("Choose keystore file")) }
            Field("Keystore password", storePw, { storePw = it }, password = true)
            Field("Key alias", alias, { alias = it })
            Field("Key password (empty = same)", keyPw, { keyPw = it }, password = true)
        }
    }
}

@Composable
fun SecurityAlerts(base: String) {
    var kind by remember { mutableStateOf("Dependabot") }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp, 0.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Dependabot", "Code scanning", "Secret scanning").forEach { FilterChip(kind == it, { kind = it }, { Text(t(it)) }) }
        }
        when (kind) {
            "Dependabot" -> SimpleList(
                "$base/dependabot/alerts?state=open", null, emptyList(), 0, null,
                title = { it.optJSONObject("security_advisory")?.s("summary").orEmpty() },
                sub = { it.optJSONObject("dependency")?.optJSONObject("package")?.s("name").orEmpty() + "  " + it.optJSONObject("security_advisory")?.s("severity").orEmpty() },
                delete = null
            )
            "Code scanning" -> SimpleList(
                "$base/code-scanning/alerts?state=open", null, emptyList(), 0, null,
                title = { it.optJSONObject("rule")?.s("description").orEmpty() },
                sub = { it.optJSONObject("rule")?.s("severity").orEmpty() + "  " + it.optJSONObject("most_recent_instance")?.optJSONObject("location")?.s("path").orEmpty() },
                delete = null
            )
            else -> SimpleList(
                "$base/secret-scanning/alerts?state=open", null, emptyList(), 0, null,
                title = { it.s("secret_type_display_name").ifEmpty { it.s("secret_type") } },
                sub = { "state: " + it.s("state") }, delete = null
            )
        }
    }
}

@Composable
fun InsightsSection(base: String) {
    var langs by remember { mutableStateOf<List<Pair<String, Long>>>(emptyList()) }
    var people by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var views by remember { mutableStateOf("") }
    var clones by remember { mutableStateOf("") }
    LaunchedEffect(base) {
        try {
            val j = Github.get("$base/languages")
            langs = j.keys().asSequence().map { it to j.getLong(it) }.sortedByDescending { it.second }.toList()
        } catch (e: CancellationException) { throw e } catch (e: Exception) {}
        try { people = Github.arr("$base/contributors?per_page=10").list() } catch (e: CancellationException) { throw e } catch (e: Exception) {}
        try { val v = Github.get("$base/traffic/views"); views = "${v.optInt("count")} views, ${v.optInt("uniques")} unique (14 days)" } catch (e: CancellationException) { throw e } catch (e: Exception) { views = "Traffic needs push access" }
        try { val v = Github.get("$base/traffic/clones"); clones = "${v.optInt("count")} clones, ${v.optInt("uniques")} unique (14 days)" } catch (e: CancellationException) { throw e } catch (e: Exception) {}
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel {
            SectionTitle("Languages")
            val total = langs.sumOf { it.second }.coerceAtLeast(1L)
            langs.forEach { (n, b) ->
                Text("$n  ${"%.1f".format(b * 100.0 / total)}%", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { b / total.toFloat() }, modifier = Modifier.fillMaxWidth())
            }
            if (langs.isEmpty()) Text(t("No data"), style = MaterialTheme.typography.bodySmall)
        }
        Panel {
            SectionTitle("Traffic")
            Text(views.ifEmpty { "..." }, style = MaterialTheme.typography.bodySmall)
            if (clones.isNotEmpty()) Text(clones, style = MaterialTheme.typography.bodySmall)
        }
        Panel {
            SectionTitle("Top contributors")
            people.forEach { Text("${it.s("login")}  ${it.optInt("contributions")} commits", style = MaterialTheme.typography.bodySmall) }
            if (people.isEmpty()) Text(t("No data"), style = MaterialTheme.typography.bodySmall)
        }
    }
}
