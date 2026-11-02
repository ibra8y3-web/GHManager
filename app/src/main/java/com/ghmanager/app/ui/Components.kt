package com.ghmanager.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ghmanager.app.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

fun toast(c: Context, m: String, long: Boolean = false) =
    Toast.makeText(c, m, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

fun openUrl(c: Context, u: String) {
    try { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {}
}

fun Context.displayName(u: Uri): String =
    contentResolver.query(u, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (c.moveToFirst() && i >= 0) c.getString(i) else null
    } ?: u.lastPathSegment ?: "file"

fun CoroutineScope.op(ctx: Context, label: String, after: () -> Unit = {}, block: suspend () -> Unit) {
    launch {
        try {
            block()
            Store.log(label, true)
            toast(ctx, "Done: $label")
            after()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Store.log(label, false, e.message.orEmpty())
            toast(ctx, "Failed: ${e.message}", true)
        }
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    val inner: @Composable ColumnScope.() -> Unit = {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
    if (onClick != null) Card(onClick = onClick, modifier = modifier.fillMaxWidth(), colors = colors, border = border, content = inner)
    else Card(modifier = modifier.fillMaxWidth(), colors = colors, border = border, content = inner)
}

@Composable
fun Pill(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.15f)) {
        Text(t(text), color = color, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp))
    }
}

@Composable
fun Field(
    label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier.fillMaxWidth(),
    singleLine: Boolean = true, mono: Boolean = false, password: Boolean = false, enabled: Boolean = true
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(t(label)) }, singleLine = singleLine,
        modifier = modifier, enabled = enabled, shape = RoundedCornerShape(12.dp),
        textStyle = if (mono) TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp) else LocalTextStyle.current,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None
    )
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(t(label), Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

@Composable
fun Pick(label: String, value: String, options: List<String>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(t(label) + ": " + t(value), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(t(o)) }, onClick = { onPick(o); open = false }) }
        }
    }
}

@Composable
fun FormDialog(
    title: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit,
    enabled: Boolean = true, content: @Composable ColumnScope.() -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t(title)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp), content = content) },
        confirmButton = { Button(onClick = onConfirm, enabled = enabled) { Text(t(confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("Cancel")) } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullDialog(
    title: String, onClose: () -> Unit, actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(topBar = {
                TopAppBar(
                    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close") } },
                    actions = actions
                )
            }, content = content)
        }
    }
}

@Composable
fun ErrorBox(msg: String, retry: (() -> Unit)? = null) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
            Text(msg, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
        }
        if (retry != null) TextButton(onClick = retry) { Text(t("Try again")) }
    }
}

@Composable
fun EmptyState(icon: ImageVector, text: String) {
    Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.outline)
        Text(t(text), color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
fun SectionTitle(title: String) =
    Text(t(title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))

@Composable
fun PagedList(
    path: String, reload: Int = 0, unwrap: String? = null, empty: String = "Nothing here yet",
    filter: (JSONObject) -> Boolean = { true }, row: @Composable (JSONObject) -> Unit
) {
    val rows = remember(path, reload) { mutableStateListOf<JSONObject>() }
    var page by remember(path, reload) { mutableIntStateOf(1) }
    var loading by remember(path, reload) { mutableStateOf(false) }
    var end by remember(path, reload) { mutableStateOf(false) }
    var error by remember(path, reload) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun load() {
        if (loading || end) return
        loading = true; error = null
        scope.launch {
            try {
                val a = Github.pageArr(path, page, unwrap)
                for (i in 0 until a.length()) rows.add(a.getJSONObject(i))
                if (a.length() < Github.PER_PAGE) end = true else page++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message
            }
            loading = false
        }
    }

    val shown = rows.filter(filter)
    LaunchedEffect(shown.size, end, loading, error, path, reload) {
        if (shown.size < 8 && !end && !loading && error == null) load()
    }
    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(shown) { r -> row(r) }
        item {
            val e = error
            when {
                loading -> Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
                e != null -> ErrorBox(e) { error = null; load() }
                !end -> TextButton(onClick = { load() }, modifier = Modifier.fillMaxWidth()) { Text(t("Load more")) }
                shown.isEmpty() -> EmptyState(Icons.Default.Inbox, empty)
            }
        }
    }
}

@Composable
fun SavedDialog(files: List<File>, onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var sums by remember { mutableStateOf<Map<File, String>>(emptyMap()) }
    LaunchedEffect(files) {
        sums = withContext(Dispatchers.IO) { files.associateWith { Files.sha256(it) } }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Saved ${files.size} file(s)") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                files.forEach { f ->
                    Column {
                        Text(f.name, style = MaterialTheme.typography.titleSmall)
                        Text(f.parentFile?.absolutePath.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        Text("${human(f.length())}  SHA-256 ${sums[f]?.take(16) ?: "..."}", style = MaterialTheme.typography.labelSmall)
                        if (f.name.endsWith(".apk")) {
                            Button(onClick = { Files.install(ctx, f) }) {
                                Icon(Icons.Default.InstallMobile, null); Spacer(Modifier.width(8.dp)); Text(t("Install"))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(t("Close")) } }
    )
}
