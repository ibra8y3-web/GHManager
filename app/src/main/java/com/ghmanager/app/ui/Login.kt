package com.ghmanager.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghmanager.app.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableIntStateOf(0) }
    var clientId by remember { mutableStateOf(Store.clientId) }
    var pat by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf<String?>(null) }

    suspend fun finish(token: String) {
        Github.token = token
        val u = Github.get("/user")
        Store.addAccount(u.s("login"), token)
        Store.log("Signed in as ${u.s("login")}", true)
        onDone()
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(24.dp))
        Box(Modifier.size(84.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), Alignment.Center) {
            Icon(Icons.Default.AccountTree, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Text(t("GitHub Manager"), fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(
            "Repositories, uploads, builds and releases in one place.",
            color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center
        )
        TabRow(selectedTabIndex = mode, containerColor = MaterialTheme.colorScheme.background) {
            Tab(mode == 0, { mode = 0 }, text = { Text(t("GitHub login")) })
            Tab(mode == 1, { mode = 1 }, text = { Text(t("Access token")) })
        }
        if (mode == 0) {
            Field("OAuth App Client ID", clientId, { clientId = it })
            Text(
                "Create an OAuth App in GitHub developer settings, tick Enable Device Flow, and paste its Client ID here. No secret is stored in the app.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
            )
            TextButton(onClick = { openUrl(ctx, "https://github.com/settings/developers") }) { Text(t("Open developer settings")) }
            Button(
                onClick = {
                    err = null; busy = true
                    scope.launch {
                        try {
                            val id = clientId.trim()
                            Store.clientId = id
                            val d = Github.deviceStart(id)
                            code = d.getString("user_code")
                            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                .setPrimaryClip(ClipData.newPlainText(t("code"), code))
                            openUrl(ctx, d.getString("verification_uri"))
                            val t = Github.devicePoll(id, d.getString("device_code"), d.optInt("interval", 5), d.optInt("expires_in", 900))
                            finish(t)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            err = e.message; Github.token = ""
                        }
                        busy = false; code = null
                    }
                },
                enabled = !busy && clientId.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Icon(Icons.Default.Login, null); Spacer(Modifier.width(8.dp)); Text(t("Sign in with GitHub"))
            }
            val c = code
            if (c != null) Panel {
                Text(t("Enter this code on GitHub (already copied):"), style = MaterialTheme.typography.bodySmall)
                Text(c, fontSize = 30.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        } else {
            Field("Personal access token", pat, { pat = it }, password = true)
            Text(
                "Recommended scopes: repo, workflow, delete_repo, read:org, notifications, gist, admin:repo_hook. The token is stored encrypted on this device.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
            )
            TextButton(onClick = {
                openUrl(ctx, "https://github.com/settings/tokens/new?scopes=repo,workflow,delete_repo,read:org,notifications,gist,admin:repo_hook&description=GitHub%20Manager")
            }) { Text(t("Create a token on GitHub")) }
            Button(
                onClick = {
                    err = null; busy = true
                    scope.launch {
                        try { finish(pat.trim()) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                            err = e.message; Github.token = ""
                        }
                        busy = false
                    }
                },
                enabled = !busy && pat.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Icon(Icons.Default.VpnKey, null); Spacer(Modifier.width(8.dp)); Text(t("Sign in with token"))
            }
        }
        err?.let { ErrorBox(it) }
    }
}
