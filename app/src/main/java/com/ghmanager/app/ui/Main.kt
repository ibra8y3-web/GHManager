package com.ghmanager.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import coil.compose.AsyncImage
import com.ghmanager.app.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val start = if (Github.token.isEmpty()) "login" else "main"
    NavHost(nav, start) {
        composable("login") {
            LoginScreen {
                nav.navigate("main") { popUpTo(nav.graph.startDestinationId) { inclusive = true } }
            }
        }
        composable("main") {
            MainScreen(
                openRepo = { o, n -> nav.navigate("repo/$o/$n") },
                addAccount = { nav.navigate("login") },
                signOut = {
                    val next = Store.removeActive()
                    if (next != null) {
                        Github.token = next.token
                        Github.repoVersion++
                    } else {
                        Github.token = ""
                        nav.navigate("login") { popUpTo(nav.graph.startDestinationId) { inclusive = true } }
                    }
                }
            )
        }
        composable("repo/{owner}/{name}") { e ->
            RepoScreen(
                e.arguments!!.getString("owner")!!, e.arguments!!.getString("name")!!,
                back = { nav.popBackStack() }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(openRepo: (String, String) -> Unit, addAccount: () -> Unit, signOut: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf(
        "Repos" to Icons.Outlined.Folder, "Upload" to Icons.Outlined.CloudUpload,
        "Inbox" to Icons.Outlined.Notifications, "Account" to Icons.Outlined.Person
    )
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t(if (tab == 0) "Repositories" else tabs[tab].first), fontWeight = FontWeight.Bold) },
                actions = { IconButton(onClick = { Github.repoVersion++ }) { Icon(Icons.Default.Refresh, "Refresh") } }
            )
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, (tt, ic) ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(ic, tt) }, label = { Text(tt) })
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> ReposScreen(openRepo)
                1 -> UploadScreen(openRepo)
                2 -> InboxScreen(openRepo)
                else -> AccountScreen(signOut, addAccount, openRepo)
            }
        }
    }
}

@Composable
fun ReposScreen(open: (String, String) -> Unit) {
    var q by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("All") }
    var create by remember { mutableStateOf(false) }
    val reload = Github.repoVersion
    val match: (JSONObject) -> Boolean = { r ->
        (q.isBlank() || r.s("full_name").contains(q, true) || r.s("description").contains(q, true)) && when (filter) {
            "Public" -> !r.optBoolean("private")
            "Private" -> r.optBoolean("private")
            "Forks" -> r.optBoolean("fork")
            "Archived" -> r.optBoolean("archived")
            "Templates" -> r.optBoolean("is_template")
            else -> true
        }
    }
    Box(Modifier.fillMaxSize()) {
        Column {
            OutlinedTextField(
                q, { q = it }, singleLine = true, placeholder = { Text(t("Search your repositories")) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                modifier = Modifier.fillMaxWidth().padding(12.dp, 4.dp), shape = RoundedCornerShape(28.dp)
            )
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("All", "Public", "Private", "Forks", "Archived", "Templates").forEach {
                    FilterChip(filter == it, { filter = it }, { Text(t(it)) })
                }
            }
            PagedList(
                "/user/repos?sort=updated&affiliation=owner,collaborator,organization_member", reload,
                empty = "No repositories match", filter = match
            ) { r -> RepoCard(r) { open(r.getJSONObject("owner").s("login"), r.s("name")) } }
        }
        ExtendedFloatingActionButton(
            onClick = { create = true },
            icon = { Icon(Icons.Default.Add, null) }, text = { Text(t("New repository")) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        )
    }
    if (create) CreateRepoDialog { create = false }
}

@Composable
fun RepoCard(r: JSONObject, onClick: () -> Unit) {
    Panel(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                if (r.optBoolean("private")) Icons.Default.Lock else Icons.Default.Public, null,
                Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline
            )
            Text(r.s("full_name"), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
            if (r.optBoolean("fork")) Pill("fork", MaterialTheme.colorScheme.tertiary)
            if (r.optBoolean("archived")) Pill("archived", Warn)
            if (r.optBoolean("is_template")) Pill("template", MaterialTheme.colorScheme.secondary)
        }
        val d = r.s("description")
        if (d.isNotEmpty()) Text(d, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, maxLines = 2)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            val lang = r.s("language")
            if (lang.isNotEmpty()) Text(lang, style = MaterialTheme.typography.labelMedium)
            Text("${r.optInt("stargazers_count")} stars", style = MaterialTheme.typography.labelMedium)
            Text("${r.optInt("open_issues_count")} open", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            Text(ago(r.s("pushed_at")), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
fun CreateRepoDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var priv by remember { mutableStateOf(true) }
    var readme by remember { mutableStateOf(true) }
    var owner by remember { mutableStateOf("") }
    var owners by remember { mutableStateOf(listOf<String>()) }
    LaunchedEffect(Unit) {
        try {
            val me = Github.get("/user").s("login")
            val orgs = Github.arr("/user/orgs").list().map { it.s("login") }
            owners = listOf(me) + orgs; owner = me
        } catch (e: Exception) {}
    }
    FormDialog("New repository", "Create", onClose, enabled = name.isNotBlank() && owner.isNotBlank(), onConfirm = {
        scope.op(ctx, "Create repository $owner/$name", { Github.repoVersion++; onClose() }) {
            val body = JSONObject().put("name", name.trim()).put("description", desc).put("private", priv).put("auto_init", readme)
            val me = owners.firstOrNull()
            if (owner == me) Github.send("POST", "/user/repos", body) else Github.send("POST", "/orgs/$owner/repos", body)
        }
    }) {
        if (owners.isNotEmpty()) Pick("Owner", owner, owners) { owner = it }
        Field("Repository name", name, { name = it })
        Field("Description", desc, { desc = it })
        SwitchRow("Private", priv) { priv = it }
        SwitchRow("Add a README", readme) { readme = it }
    }
}

@Composable
fun InboxScreen(open: (String, String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var local by remember { mutableIntStateOf(0) }
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = {
                scope.op(ctx, "Mark all notifications read", { local++ }) { Github.send("PUT", "/notifications") }
            }) { Icon(Icons.Default.DoneAll, null); Spacer(Modifier.width(6.dp)); Text(t("Mark all read")) }
        }
        PagedList("/notifications?all=true", Github.repoVersion + local, empty = "You are all caught up") { n ->
            val repo = n.optJSONObject("repository")
            val subject = n.optJSONObject("subject")
            Panel(onClick = {
                scope.launch {
                    try { Github.send("PATCH", "/notifications/threads/${n.s("id")}") } catch (e: Exception) {}
                    local++
                    if (repo != null) open(repo.getJSONObject("owner").s("login"), repo.s("name"))
                }
            }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (n.optBoolean("unread")) Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                    Text(subject?.s("title").orEmpty(), fontWeight = FontWeight.SemiBold, maxLines = 2, modifier = Modifier.weight(1f))
                }
                Text(
                    "${repo?.s("full_name").orEmpty()}  ${subject?.s("type").orEmpty()}  ${n.s("reason")}  ${ago(n.s("updated_at"))}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
fun AccountScreen(signOut: () -> Unit, addAccount: () -> Unit, openRepo: (String, String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var user by remember { mutableStateOf<JSONObject?>(null) }
    var orgs by remember { mutableStateOf(listOf<JSONObject>()) }
    var limit by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var clientId by remember { mutableStateOf(Store.clientId) }
    var logs by remember { mutableStateOf(Store.logs()) }
    var storage by remember { mutableLongStateOf(Files.size(Files.downloads(ctx))) }
    var accounts by remember { mutableStateOf(Store.accounts()) }
    var bg by remember { mutableStateOf(Store.bgNotify) }
    var tool by remember { mutableStateOf("") }
    var bpw by remember { mutableStateOf("") }
    var bTokens by remember { mutableStateOf(false) }

    LaunchedEffect(tick, Github.token) {
        err = null
        try {
            val u = Github.get("/user")
            user = u
            if (Store.accounts().none { it.login == u.s("login") }) Store.addAccount(u.s("login"), Github.token)
            accounts = Store.accounts()
            orgs = Github.arr("/user/orgs").list()
            val c = Github.get("/rate_limit").getJSONObject("resources").getJSONObject("core")
            limit = "${c.optInt("remaining")}/${c.optInt("limit")}"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            err = if (e is ApiException && e.code == 401) t("The token is invalid, revoked or expired. Sign in again.") else e.message
        }
    }

    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.op(ctx, "Export backup") {
            val bytes = Backup.export(bpw, bTokens)
            withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) } }
        }
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val raw = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }
                val r = Backup.import(raw, bpw)
                Lang.init(); accounts = Store.accounts(); clientId = Store.clientId; bg = Store.bgNotify
                if (Store.token.isNotEmpty()) Github.token = Store.token
                Github.repoVersion++
                toast(ctx, r, true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(ctx, "Failed: ${e.message}", true)
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        err?.let { ErrorBox(it) { tick++ } }
        user?.let { u ->
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    AsyncImage(u.s("avatar_url"), null, Modifier.size(64.dp).clip(CircleShape))
                    Column {
                        Text(u.s("name").ifEmpty { u.s("login") }, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("@${u.s("login")}", color = MaterialTheme.colorScheme.outline)
                        val plan = u.optJSONObject("plan")?.s("name").orEmpty()
                        if (plan.isNotEmpty()) Pill("plan: $plan", MaterialTheme.colorScheme.primary)
                    }
                }
                Text("${u.optInt("public_repos")} public  ${u.optInt("owned_private_repos")} private  ${u.optInt("followers")} followers", style = MaterialTheme.typography.bodySmall)
            }
        }
        Panel {
            SectionTitle("Accounts")
            accounts.forEach { a ->
                val active = a.token == Github.token
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(if (active) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked, null, tint = MaterialTheme.colorScheme.primary)
                    Text("@${a.login}", Modifier.weight(1f))
                    if (!active) {
                        TextButton(onClick = { Store.switchTo(a); Github.token = a.token; Github.repoVersion++; tick++ }) { Text(t("Switch")) }
                        IconButton(onClick = { Store.removeAccount(a.login); accounts = Store.accounts() }) { Icon(Icons.Default.Delete, "Remove") }
                    }
                }
            }
            OutlinedButton(onClick = addAccount) { Icon(Icons.Default.PersonAdd, null); Spacer(Modifier.width(6.dp)); Text(t("Add another account")) }
        }
        Panel {
            SectionTitle("Connection")
            Text(t("Scopes:") + " ${Github.scopes.ifEmpty { "-" }}", style = MaterialTheme.typography.bodySmall)
            Text(t("Token expiry:") + " ${Github.expiry.ifEmpty { "-" }}", style = MaterialTheme.typography.bodySmall)
            Text(t("API calls left this hour:") + " ${limit.ifEmpty { "..." }}", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { tick++ }) { Icon(Icons.Default.NetworkCheck, null); Spacer(Modifier.width(6.dp)); Text(t("Check")) }
                Button(onClick = signOut, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                    Icon(Icons.Default.Logout, null); Spacer(Modifier.width(6.dp)); Text(t("Sign out"))
                }
            }
        }
        if (orgs.isNotEmpty()) Panel {
            SectionTitle("Organizations")
            orgs.forEach { Text(it.s("login")) }
        }
        Panel {
            SectionTitle("Tools")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { tool = "gists" }) { Icon(Icons.Default.Code, null); Spacer(Modifier.width(6.dp)); Text(t("Gists")) }
                OutlinedButton(onClick = { tool = "search" }) { Icon(Icons.Default.Search, null); Spacer(Modifier.width(6.dp)); Text(t("Code search")) }
                OutlinedButton(onClick = { tool = "starred" }) { Icon(Icons.Default.Star, null); Spacer(Modifier.width(6.dp)); Text(t("Starred")) }
            }
        }
        Panel {
            SectionTitle("Settings")
            SwitchRow("Background notifications and build alerts", bg) {
                bg = it; Store.bgNotify = it
                if (it) Notifier.schedule(ctx) else Notifier.cancel(ctx)
            }
            Pick("Language", Lang.code.let { when (Store.lang) { "ar" -> "العربية"; "en" -> "English"; else -> "System" } }, listOf("System", "English", "العربية")) {
                Lang.set(when (it) { "العربية" -> "ar"; "English" -> "en"; else -> "system" })
            }
            Field("OAuth Client ID", clientId, { clientId = it })
            OutlinedButton(onClick = { Store.clientId = clientId.trim(); toast(ctx, "Saved") }) { Text(t("Save")) }
        }
        Panel {
            SectionTitle("Backup")
            Text(t("Exports settings and the account list. Tokens are included only when you set a password and tick the box; the file is then encrypted with AES-256."), style = MaterialTheme.typography.bodySmall)
            Field("Backup password (optional)", bpw, { bpw = it }, password = true)
            SwitchRow("Include tokens (encrypted)", bTokens) { bTokens = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exportPicker.launch("ghmanager-backup.json") }) { Icon(Icons.Default.Upload, null); Spacer(Modifier.width(6.dp)); Text(t("Export")) }
                OutlinedButton(onClick = { importPicker.launch(arrayOf("application/json", "*/*")) }) { Icon(Icons.Default.Download, null); Spacer(Modifier.width(6.dp)); Text(t("Import")) }
            }
        }
        Panel {
            SectionTitle("Storage")
            Text(t("Downloads:") + " ${human(storage)}", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                Files.downloads(ctx).listFiles()?.forEach { it.deleteRecursively() }
                storage = Files.size(Files.downloads(ctx))
            }) { Icon(Icons.Default.DeleteSweep, null); Spacer(Modifier.width(6.dp)); Text(t("Clear downloads")) }
        }
        Panel {
            SectionTitle("Activity log")
            if (logs.isEmpty()) Text(t("No operations yet."), style = MaterialTheme.typography.bodySmall)
            logs.take(40).forEach {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        if (it.ok) Icons.Default.CheckCircle else Icons.Default.Error, null, Modifier.size(18.dp),
                        tint = if (it.ok) Good else MaterialTheme.colorScheme.error
                    )
                    Column {
                        Text(it.label, style = MaterialTheme.typography.bodySmall)
                        if (it.detail.isNotEmpty()) Text(it.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (logs.isNotEmpty()) TextButton(onClick = { Store.clearLogs(); logs = emptyList() }) { Text(t("Clear log")) }
        }
        Spacer(Modifier.height(24.dp))
    }
    when (tool) {
        "gists" -> GistsDialog { tool = "" }
        "search" -> CodeSearchDialog(user?.s("login").orEmpty(), { o, n -> tool = ""; openRepo(o, n) }) { tool = "" }
        "starred" -> StarredDialog({ o, n -> tool = ""; openRepo(o, n) }) { tool = "" }
    }
}
