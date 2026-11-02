package com.ghmanager.app.data

import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import okio.BufferedSink
import okio.source
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, message: String) : Exception(message)

fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
fun encPath(s: String): String = s.split("/").joinToString("/") { enc(it) }
fun JSONObject.s(k: String): String = if (isNull(k)) "" else optString(k)
fun JSONArray.list(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)

fun human(n: Long): String {
    if (n < 1024) return "$n B"
    val u = arrayOf("KB", "MB", "GB")
    var v = n / 1024.0
    var i = 0
    while (v >= 1024 && i < 2) { v /= 1024; i++ }
    return "%.1f %s".format(v, u[i])
}

fun ago(iso: String): String = try {
    val s = Duration.between(Instant.parse(iso), Instant.now()).seconds
    when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60}m ago"
        s < 86400 -> "${s / 3600}h ago"
        s < 2592000 -> "${s / 86400}d ago"
        else -> "${s / 2592000}mo ago"
    }
} catch (e: Exception) { iso }

class UploadItem(val path: String, val size: Long, val open: () -> InputStream, val lfs: Boolean = false)

object Github {
    const val PER_PAGE = 30
    var token by mutableStateOf("")
    var scopes by mutableStateOf("")
    var expiry by mutableStateOf("")
    var rate by mutableStateOf("")
    var repoVersion by mutableIntStateOf(0)

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()
    private val JSON = "application/json".toMediaType()
    private const val ACCEPT = "application/vnd.github+json"

    private fun build(method: String, url: String, body: RequestBody?, accept: String): Request {
        val b = Request.Builder().url(url).header("Accept", accept).header("X-GitHub-Api-Version", "2022-11-28")
        if (token.isNotEmpty()) b.header("Authorization", "Bearer $token")
        val needsBody = method == "POST" || method == "PUT" || method == "PATCH"
        val rb: RequestBody? = body ?: if (needsBody) ByteArray(0).toRequestBody(null) else null
        return b.method(method, rb).build()
    }

    private fun errMsg(s: String, code: Int): String = try {
        val j = JSONObject(s)
        var m = j.optString("message", "HTTP $code")
        val errs = j.optJSONArray("errors")
        if (errs != null) for (i in 0 until errs.length()) {
            val e = errs.opt(i)
            m += " - " + (if (e is JSONObject) e.optString("message", e.toString()) else e.toString())
        }
        "$m (HTTP $code)"
    } catch (e: Exception) {
        "HTTP $code" + if (s.isNotBlank()) ": ${s.take(200)}" else ""
    }

    private fun exec(req: Request): String = http.newCall(req).execute().use { r ->
        r.header("x-oauth-scopes")?.let { scopes = it }
        r.header("github-authentication-token-expiration")?.let { expiry = it }
        r.header("x-ratelimit-remaining")?.let { rem -> rate = "$rem/${r.header("x-ratelimit-limit") ?: "?"}" }
        val s = r.body?.string().orEmpty()
        if (!r.isSuccessful) throw ApiException(r.code, errMsg(s, r.code))
        s
    }

    private fun full(path: String) = if (path.startsWith("http")) path else "https://api.github.com$path"

    suspend fun text(method: String, path: String, body: JSONObject? = null, accept: String = ACCEPT): String =
        withContext(Dispatchers.IO) {
            exec(build(method, full(path), body?.toString()?.toRequestBody(JSON), accept))
        }

    suspend fun get(path: String): JSONObject = JSONObject(text("GET", path))
    suspend fun arr(path: String): JSONArray = JSONArray(text("GET", path))
    suspend fun send(method: String, path: String, body: JSONObject? = null): JSONObject {
        val t = text(method, path, body)
        return if (t.isBlank()) JSONObject() else JSONObject(t)
    }

    suspend fun pageArr(path: String, page: Int, unwrap: String?): JSONArray {
        val sep = if (path.contains('?')) '&' else '?'
        val t = text("GET", "$path${sep}per_page=$PER_PAGE&page=$page")
        return if (unwrap == null) JSONArray(t) else (JSONObject(t).optJSONArray(unwrap) ?: JSONArray())
    }

    // ---------- OAuth device flow ----------
    private fun formPost(url: String, vararg kv: Pair<String, String>): JSONObject {
        val fb = FormBody.Builder()
        kv.forEach { fb.add(it.first, it.second) }
        val req = Request.Builder().url(url).header("Accept", "application/json").post(fb.build()).build()
        return http.newCall(req).execute().use { r ->
            val s = r.body?.string().orEmpty()
            try { JSONObject(s) } catch (e: Exception) { throw ApiException(r.code, "HTTP ${r.code}") }
        }
    }

    suspend fun deviceStart(clientId: String): JSONObject = withContext(Dispatchers.IO) {
        val j = formPost(
            "https://github.com/login/device/code",
            "client_id" to clientId,
            "scope" to "repo workflow delete_repo read:org notifications gist admin:repo_hook"
        )
        if (j.has("error")) throw ApiException(0, j.optString("error_description", j.optString("error")))
        j
    }

    suspend fun devicePoll(clientId: String, deviceCode: String, interval: Int, expiresIn: Int): String {
        var wait = interval
        val end = System.currentTimeMillis() + expiresIn * 1000L
        while (System.currentTimeMillis() < end) {
            delay(wait * 1000L)
            val j = withContext(Dispatchers.IO) {
                formPost(
                    "https://github.com/login/oauth/access_token",
                    "client_id" to clientId,
                    "device_code" to deviceCode,
                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"
                )
            }
            if (j.has("access_token")) return j.getString("access_token")
            when (j.optString("error")) {
                "authorization_pending" -> {}
                "slow_down" -> wait += 5
                else -> throw ApiException(0, j.optString("error_description", j.optString("error")))
            }
        }
        throw ApiException(0, "The code expired. Start again.")
    }

    // ---------- Downloads / assets ----------
    suspend fun download(
        url: String, dest: File, accept: String = ACCEPT,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        http.newCall(build("GET", full(url), null, accept)).execute().use { r ->
            if (!r.isSuccessful) throw ApiException(r.code, errMsg(r.body?.string().orEmpty(), r.code))
            val body = r.body!!
            val total = body.contentLength()
            var done = 0L
            dest.parentFile?.mkdirs()
            body.byteStream().use { i ->
                dest.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = i.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
        }
        dest
    }

    suspend fun uploadAsset(base: String, releaseId: Long, name: String, mime: String, bytes: ByteArray) =
        withContext(Dispatchers.IO) {
            val url = "https://uploads.github.com$base/releases/$releaseId/assets?name=${enc(name)}"
            exec(build("POST", url, bytes.toRequestBody(mime.toMediaType()), ACCEPT))
        }

    // ---------- Git LFS ----------
    private fun streamBody(size: Long, open: () -> InputStream) = object : RequestBody() {
        override fun contentType() = "application/octet-stream".toMediaType()
        override fun contentLength() = size
        override fun writeTo(sink: BufferedSink) { open().use { sink.writeAll(it.source()) } }
    }

    private fun lfsHeaders(b: Request.Builder, h: JSONObject?) {
        if (h != null) for (k in h.keys()) b.header(k, h.getString(k))
    }

    suspend fun lfsPointer(owner: String, repo: String, item: UploadItem): ByteArray = withContext(Dispatchers.IO) {
        val md = MessageDigest.getInstance("SHA-256")
        item.open().use { i ->
            val buf = ByteArray(65536)
            while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        val oid = md.digest().joinToString("") { "%02x".format(it) }
        val lfsType = "application/vnd.git-lfs+json".toMediaType()
        val batch = JSONObject().put("operation", "upload").put("transfers", JSONArray().put("basic"))
            .put("objects", JSONArray().put(JSONObject().put("oid", oid).put("size", item.size)))
        val req = Request.Builder().url("https://github.com/$owner/$repo.git/info/lfs/objects/batch")
            .header("Accept", "application/vnd.git-lfs+json")
            .header("Authorization", Credentials.basic("x-access-token", token))
            .post(batch.toString().toRequestBody(lfsType)).build()
        val resp = http.newCall(req).execute().use { r ->
            val s = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiException(r.code, "Git LFS batch failed: " + errMsg(s, r.code))
            JSONObject(s)
        }
        val obj = resp.getJSONArray("objects").getJSONObject(0)
        val oerr = obj.optJSONObject("error")
        if (oerr != null) throw ApiException(oerr.optInt("code"), oerr.optString("message"))
        val actions = obj.optJSONObject("actions")
        val up = actions?.optJSONObject("upload")
        if (up != null) {
            val b = Request.Builder().url(up.getString("href"))
            lfsHeaders(b, up.optJSONObject("header"))
            http.newCall(b.put(streamBody(item.size, item.open)).build()).execute().use { r ->
                if (!r.isSuccessful) throw ApiException(r.code, "LFS upload failed (HTTP ${r.code})")
            }
        }
        val ver = actions?.optJSONObject("verify")
        if (ver != null) {
            val b = Request.Builder().url(ver.getString("href"))
            lfsHeaders(b, ver.optJSONObject("header"))
            b.header("Accept", "application/vnd.git-lfs+json")
            val vb = JSONObject().put("oid", oid).put("size", item.size).toString().toRequestBody(lfsType)
            http.newCall(b.post(vb).build()).execute().use { r ->
                if (!r.isSuccessful) throw ApiException(r.code, "LFS verify failed (HTTP ${r.code})")
            }
        }
        "version https://git-lfs.github.com/spec/v1\noid sha256:$oid\nsize ${item.size}\n".toByteArray()
    }

    // ---------- Direct upload of a whole project (Git Data API) ----------
    suspend fun uploadFiles(
        owner: String, repo: String, branch: String, items: List<UploadItem>, message: String,
        cache: MutableMap<String, String>, onCache: () -> Unit,
        log: (String) -> Unit, progress: (Int, Int) -> Unit
    ): String {
        val base = "/repos/$owner/$repo"
        var files = items
        if (files.isEmpty()) throw ApiException(0, "Nothing to upload")
        val info = get(base)
        var baseSha: String
        try {
            baseSha = get("$base/git/ref/heads/${encPath(branch)}").getJSONObject("object").getString("sha")
        } catch (e: ApiException) {
            if (e.code == 409) {
                val first = files.firstOrNull { !it.lfs } ?: files.first()
                log("Empty repository: creating the first commit with ${first.path}")
                val bytes = withContext(Dispatchers.IO) { first.open().use { it.readBytes() } }
                send(
                    "PUT", "$base/contents/${encPath(first.path)}",
                    JSONObject().put("message", message).put("content", b64(bytes)).put("branch", branch)
                )
                files = files.filter { it !== first }
                baseSha = get("$base/git/ref/heads/${encPath(branch)}").getJSONObject("object").getString("sha")
            } else if (e.code == 404) {
                val def = info.s("default_branch")
                val sha = get("$base/git/ref/heads/${encPath(def)}").getJSONObject("object").getString("sha")
                send("POST", "$base/git/refs", JSONObject().put("ref", "refs/heads/$branch").put("sha", sha))
                log("Created branch $branch from $def")
                baseSha = sha
            } else throw e
        }
        if (files.isEmpty()) return baseSha
        var tree = get("$base/git/commits/$baseSha").getJSONObject("tree").getString("sha")
        val entries = mutableListOf<JSONObject>()
        files.forEachIndexed { i, f ->
            progress(i, files.size)
            val key = f.path + "@" + f.size + (if (f.lfs) "#lfs" else "")
            var sha = cache[key]
            if (sha == null) {
                val bytes = if (f.lfs) {
                    log("Git LFS upload: ${f.path} (${human(f.size)})")
                    lfsPointer(owner, repo, f)
                } else withContext(Dispatchers.IO) { f.open().use { it.readBytes() } }
                sha = send("POST", "$base/git/blobs", JSONObject().put("content", b64(bytes)).put("encoding", "base64")).getString("sha")
                cache[key] = sha
                onCache()
            }
            val name = f.path.substringAfterLast('/')
            val mode = if (name == "gradlew" || name.endsWith(".sh")) "100755" else "100644"
            entries.add(JSONObject().put("path", f.path).put("mode", mode).put("type", "blob").put("sha", sha))
            if ((i + 1) % 25 == 0) log("Uploaded ${i + 1}/${files.size} files")
        }
        for (chunk in entries.chunked(200)) {
            tree = send("POST", "$base/git/trees", JSONObject().put("base_tree", tree).put("tree", JSONArray(chunk))).getString("sha")
        }
        val commit = send(
            "POST", "$base/git/commits",
            JSONObject().put("message", message).put("tree", tree).put("parents", JSONArray().put(baseSha))
        )
        send("PATCH", "$base/git/refs/heads/${encPath(branch)}", JSONObject().put("sha", commit.getString("sha")))
        progress(files.size, files.size)
        return commit.getString("sha")
    }
}
