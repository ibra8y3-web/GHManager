package com.ghmanager.app.data

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import androidx.documentfile.provider.DocumentFile
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.util.zip.ZipInputStream

data class LogEntry(val time: Long, val label: String, val ok: Boolean, val detail: String)

object Store {
    private lateinit var sec: SharedPreferences
    private lateinit var plain: SharedPreferences

    fun init(c: Context) {
        plain = c.getSharedPreferences("plain_store", Context.MODE_PRIVATE)
        sec = try { openSecure(c) } catch (e: Exception) {
            c.deleteSharedPreferences("secure_store")
            openSecure(c)
        }
    }

    private fun openSecure(c: Context): SharedPreferences {
        val mk = MasterKey.Builder(c).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            c, "secure_store", mk,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    var token: String
        get() = sec.getString("token", "") ?: ""
        set(v) { sec.edit().putString("token", v).apply() }

    var clientId: String
        get() = plain.getString("client_id", "") ?: ""
        set(v) { plain.edit().putString("client_id", v).apply() }

    fun log(label: String, ok: Boolean, detail: String = "") {
        val arr = JSONArray(plain.getString("log", "[]"))
        arr.put(JSONObject().put("t", System.currentTimeMillis()).put("l", label).put("ok", ok).put("d", detail))
        val out = JSONArray()
        for (i in maxOf(0, arr.length() - 200) until arr.length()) out.put(arr.get(i))
        plain.edit().putString("log", out.toString()).apply()
    }

    fun logs(): List<LogEntry> {
        val a = JSONArray(plain.getString("log", "[]"))
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            LogEntry(o.optLong("t"), o.optString("l"), o.optBoolean("ok"), o.optString("d"))
        }.reversed()
    }

    fun clearLogs() { plain.edit().putString("log", "[]").apply() }

    fun plainGet(k: String, d: String = ""): String = plain.getString(k, d) ?: d
    fun plainPut(k: String, v: String) { plain.edit().putString(k, v).apply() }

    var lang: String
        get() = plainGet("lang", "system")
        set(v) = plainPut("lang", v)
    var bgNotify: Boolean
        get() = plainGet("bg_notify", "0") == "1"
        set(v) = plainPut("bg_notify", if (v) "1" else "0")

    // ----- accounts (tokens stay in the encrypted store) -----
    fun accounts(): List<Account> {
        val a = JSONArray(sec.getString("accounts", "[]"))
        return (0 until a.length()).map { Account(a.getJSONObject(it).optString("login"), a.getJSONObject(it).optString("token")) }
    }
    private fun saveAccounts(l: List<Account>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("login", it.login).put("token", it.token)) }
        sec.edit().putString("accounts", a.toString()).apply()
    }
    fun addAccount(login: String, tok: String) {
        saveAccounts(accounts().filter { it.login != login } + Account(login, tok))
        token = tok
        plainPut("active_login", login)
    }
    val activeLogin: String get() = plainGet("active_login")
    fun switchTo(a: Account) { token = a.token; plainPut("active_login", a.login) }
    /** Removes the active account. Returns the account that became active, or null when none is left. */
    fun removeActive(): Account? {
        val rest = accounts().filter { it.token != token && it.login != activeLogin }
        saveAccounts(rest)
        val next = rest.firstOrNull()
        if (next != null) switchTo(next) else { token = ""; plainPut("active_login", "") }
        return next
    }
    fun removeAccount(login: String) { saveAccounts(accounts().filter { it.login != login }) }

    // ----- watched runs + seen notifications (background worker) -----
    private fun strList(k: String): MutableList<String> {
        val a = JSONArray(plainGet(k, "[]"))
        return (0 until a.length()).map { a.getString(it) }.toMutableList()
    }
    private fun saveStrList(k: String, l: List<String>) { plainPut(k, JSONArray(l.takeLast(300)).toString()) }
    fun watched(): List<String> = strList("watch")
    fun watch(base: String, id: Long) { val l = strList("watch"); val e = "$base|$id"; if (e !in l) { l.add(e); saveStrList("watch", l) } }
    fun unwatch(e: String) { val l = strList("watch"); l.remove(e); saveStrList("watch", l) }
    fun seen(): Set<String> = strList("seen").toSet()
    fun markSeen(ids: Collection<String>) { saveStrList("seen", (strList("seen") + ids).distinct()) }
}

data class Account(val login: String, val token: String)

class Local(val path: String, val doc: DocumentFile, val size: Long)
class Finding(val path: String, val reason: String)

object Tree {
    private val JUNK = setOf(".gradle", ".idea", "build", "node_modules", ".dart_tool", "__pycache__", ".DS_Store", "captures", ".cxx")

    fun walk(dir: DocumentFile, prefix: String, skip: Boolean, out: MutableList<Local>) {
        for (f in dir.listFiles()) {
            val n = f.name ?: continue
            if (n == ".git" || (skip && n in JUNK)) continue
            val p = if (prefix.isEmpty()) n else "$prefix/$n"
            if (f.isDirectory) walk(f, p, skip, out) else if (f.isFile) out.add(Local(p, f, f.length()))
        }
    }

    fun list(c: Context, uri: Uri, skipJunk: Boolean): List<Local> {
        val root = DocumentFile.fromTreeUri(c, uri) ?: return emptyList()
        val out = mutableListOf<Local>()
        walk(root, "", skipJunk, out)
        return out
    }

    fun scan(c: Context, files: List<Local>): List<Finding> {
        val f = mutableListOf<Finding>()
        for (l in files) {
            val nm = Scanner.byName(l.path)
            if (nm != null) f.add(Finding(l.path, nm))
            else if (l.size in 1..1_000_000) {
                val head = try { c.contentResolver.openInputStream(l.doc.uri)?.use { it.readBytes() } } catch (e: Exception) { null }
                val hit = if (head != null) Scanner.byContent(head) else null
                if (hit != null) f.add(Finding(l.path, hit))
            }
        }
        return f
    }
}

object Backup {
    private fun key(pw: String, salt: ByteArray): SecretKeySpec {
        val k = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pw.toCharArray(), salt, 120_000, 256))
        return SecretKeySpec(k.encoded, "AES")
    }

    fun export(pw: String, includeTokens: Boolean): ByteArray {
        val accts = JSONArray()
        Store.accounts().forEach {
            val o = JSONObject().put("login", it.login)
            if (includeTokens && pw.isNotEmpty()) o.put("token", it.token)
            accts.put(o)
        }
        val body = JSONObject().put("v", 1).put("client_id", Store.clientId).put("lang", Store.lang)
            .put("bg_notify", Store.bgNotify).put("accounts", accts)
        if (pw.isEmpty()) return body.toString(2).toByteArray()
        val rnd = SecureRandom()
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(pw, salt), GCMParameterSpec(128, iv))
        val data = c.doFinal(body.toString().toByteArray())
        return JSONObject().put("enc", 1).put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .put("iv", Base64.encodeToString(iv, Base64.NO_WRAP)).put("data", Base64.encodeToString(data, Base64.NO_WRAP))
            .toString(2).toByteArray()
    }

    fun import(raw: ByteArray, pw: String): String {
        var j = JSONObject(String(raw))
        if (j.has("enc")) {
            if (pw.isEmpty()) throw IllegalArgumentException("This backup is encrypted. Enter its password.")
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(pw, Base64.decode(j.getString("salt"), Base64.DEFAULT)), GCMParameterSpec(128, Base64.decode(j.getString("iv"), Base64.DEFAULT)))
            j = JSONObject(String(c.doFinal(Base64.decode(j.getString("data"), Base64.DEFAULT))))
        }
        Store.clientId = j.optString("client_id", Store.clientId)
        Store.lang = j.optString("lang", Store.lang)
        Store.bgNotify = j.optBoolean("bg_notify", Store.bgNotify)
        var restored = 0; var needLogin = 0
        val a = j.optJSONArray("accounts") ?: JSONArray()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val tok = o.optString("token")
            if (tok.isNotEmpty()) {
                restored++
                Store.addAccount(o.optString("login"), tok)
            } else needLogin++
        }
        return "Settings restored. $restored account(s) restored, $needLogin need a new sign-in."
    }
}

object Scanner {
    private val badNames = Regex(
        "(^|/)(\\.env(\\..*)?|keystore\\.properties|local\\.properties|id_rsa|id_ed25519|id_dsa|secrets?\\.(json|ya?ml|properties)|google-services\\.json|\\.npmrc|\\.pypirc|\\.netrc)$",
        RegexOption.IGNORE_CASE
    )
    private val badExt = setOf("jks", "keystore", "pem", "p12", "pfx", "p8", "key", "der", "kdbx")
    private val patterns = listOf(
        "GitHub token" to Regex("gh[pousr]_[A-Za-z0-9]{36,}"),
        "GitHub fine-grained token" to Regex("github_pat_[A-Za-z0-9_]{50,}"),
        "AWS access key" to Regex("AKIA[0-9A-Z]{16}"),
        "Private key block" to Regex("-----BEGIN (RSA |EC |DSA |OPENSSH |PGP )?PRIVATE KEY"),
        "Google API key" to Regex("AIza[0-9A-Za-z_\\-]{35}"),
        "Slack token" to Regex("xox[abprs]-[A-Za-z0-9-]{10,}"),
        "Stripe live key" to Regex("sk_live_[0-9a-zA-Z]{20,}"),
        "AI provider key" to Regex("sk-(ant-)?[A-Za-z0-9_\\-]{32,}")
    )

    fun byName(path: String): String? {
        if (badNames.containsMatchIn(path)) return "Sensitive file name"
        val ext = path.substringAfterLast('.', "").lowercase()
        if (ext in badExt) return "Key or certificate file (.$ext)"
        return null
    }

    fun byContent(bytes: ByteArray): String? {
        if (bytes.any { it == 0.toByte() }) return null
        val t = String(bytes, Charsets.ISO_8859_1)
        return patterns.firstOrNull { it.second.containsMatchIn(t) }?.first
    }
}

object Detect {
    fun type(paths: List<String>): String? {
        val root = paths.filter { !it.contains('/') }.toSet()
        return when {
            "pubspec.yaml" in root -> "Flutter"
            root.any { it == "build.gradle" || it == "build.gradle.kts" || it == "settings.gradle" || it == "settings.gradle.kts" } -> "Android"
            "package.json" in root -> "Node"
            else -> null
        }
    }

    fun template(c: Context, type: String): String =
        c.assets.open("templates/${type.lowercase()}.yml").bufferedReader().use { it.readText() }
}

object Files {
    fun downloads(c: Context): File =
        (c.getExternalFilesDir("downloads") ?: File(c.filesDir, "downloads")).also { it.mkdirs() }

    fun unzip(zip: File, out: File): List<File> {
        out.mkdirs()
        val res = mutableListOf<File>()
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            var e = z.nextEntry
            while (e != null) {
                val f = File(out, e.name)
                if (!f.canonicalPath.startsWith(out.canonicalPath + File.separator)) {
                    throw SecurityException("Blocked unsafe path in archive: ${e.name}")
                }
                if (e.isDirectory) f.mkdirs() else {
                    f.parentFile?.mkdirs()
                    f.outputStream().use { z.copyTo(it) }
                    res.add(f)
                }
                e = z.nextEntry
            }
        }
        return res
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i ->
            val b = ByteArray(65536)
            while (true) {
                val n = i.read(b)
                if (n < 0) break
                md.update(b, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun size(f: File): Long = if (f.isFile) f.length() else (f.listFiles()?.sumOf { size(it) } ?: 0L)

    fun install(c: Context, f: File) {
        if (!c.packageManager.canRequestPackageInstalls()) {
            c.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        val uri = FileProvider.getUriForFile(c, "${c.packageName}.files", f)
        c.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
