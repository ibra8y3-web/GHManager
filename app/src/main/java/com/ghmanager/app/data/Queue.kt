package com.ghmanager.app.data

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

data class UploadJob(
    val id: String, val owner: String, val repo: String, val branch: String, val message: String,
    val tree: String, val skip: List<String>, val skipJunk: Boolean, val wf: String?,
    val lfsPatterns: List<String>, val autoLfsMb: Int,
    val state: String = "queued", val detail: String = "", val done: Int = 0, val total: Int = 0,
    val created: Long = System.currentTimeMillis(), val commit: String = ""
) {
    val label get() = "$owner/$repo ($branch)"

    fun toJson(): JSONObject = JSONObject().put("id", id).put("owner", owner).put("repo", repo).put("branch", branch)
        .put("message", message).put("tree", tree).put("skip", JSONArray(skip)).put("skipJunk", skipJunk)
        .put("wf", wf ?: "").put("lfs", JSONArray(lfsPatterns)).put("autoLfsMb", autoLfsMb).put("state", state)
        .put("detail", detail).put("done", done).put("total", total).put("created", created).put("commit", commit)

    companion object {
        fun fromJson(o: JSONObject): UploadJob {
            fun strs(k: String): List<String> { val a = o.optJSONArray(k) ?: JSONArray(); return (0 until a.length()).map { a.getString(it) } }
            return UploadJob(
                o.getString("id"), o.getString("owner"), o.getString("repo"), o.getString("branch"), o.optString("message"),
                o.getString("tree"), strs("skip"), o.optBoolean("skipJunk", true), o.optString("wf").ifEmpty { null },
                strs("lfs"), o.optInt("autoLfsMb", 0), o.optString("state"), o.optString("detail"),
                o.optInt("done"), o.optInt("total"), o.optLong("created"), o.optString("commit")
            )
        }
        fun create(owner: String, repo: String, branch: String, message: String, tree: String, skip: List<String>,
                   skipJunk: Boolean, wf: String?, lfsPatterns: List<String>, autoLfsMb: Int) =
            UploadJob(UUID.randomUUID().toString(), owner, repo, branch, message, tree, skip, skipJunk, wf, lfsPatterns, autoLfsMb)
    }
}

object Queue {
    private val lock = Any()
    private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun all(): List<UploadJob> = synchronized(lock) {
        val a = JSONArray(Store.plainGet("jobs", "[]"))
        (0 until a.length()).mapNotNull { try { UploadJob.fromJson(a.getJSONObject(it)) } catch (e: Exception) { null } }
    }
    private fun save(l: List<UploadJob>) {
        val a = JSONArray(); l.takeLast(30).forEach { a.put(it.toJson()) }
        Store.plainPut("jobs", a.toString())
    }
    fun get(id: String): UploadJob? = all().firstOrNull { it.id == id }
    fun put(j: UploadJob) = synchronized(lock) { save(all().filter { it.id != j.id } + j) }
    fun update(id: String, f: (UploadJob) -> UploadJob) = synchronized(lock) { save(all().map { if (it.id == id) f(it) else it }) }
    fun remove(c: Context, id: String) = synchronized(lock) {
        File(c.filesDir, "upload-$id.json").delete()
        save(all().filter { it.id != id })
    }

    fun enqueue(c: Context, job: UploadJob) {
        put(job.copy(state = "queued", detail = ""))
        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(workDataOf("id" to job.id)).setConstraints(net)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(c).enqueueUniqueWork("upload-${job.id}", ExistingWorkPolicy.REPLACE, req)
    }

    fun cancel(c: Context, id: String) {
        update(id) { it.copy(state = "cancelled", detail = "Cancelled by you") }
        WorkManager.getInstance(c).cancelUniqueWork("upload-$id")
    }

    private fun glob(p: String): Regex =
        Regex("^" + p.trim().split("*").joinToString(".*") { Regex.escape(it) } + "$", RegexOption.IGNORE_CASE)

    suspend fun run(c: Context, job: UploadJob, log: (String) -> Unit, progress: (Int, Int) -> Unit): String {
        val all = Tree.list(c, Uri.parse(job.tree), job.skipJunk)
        val skip = job.skip.toSet()
        val patterns = job.lfsPatterns.filter { it.isNotBlank() }.map { glob(it) }
        val limit = job.autoLfsMb * 1024L * 1024L
        val items = mutableListOf<UploadItem>()
        val lfsLines = mutableListOf<String>()
        job.lfsPatterns.filter { it.isNotBlank() }.forEach { lfsLines.add("${it.trim()} filter=lfs diff=lfs merge=lfs -text") }
        for (l in all) {
            if (l.path in skip) continue
            val name = l.path.substringAfterLast('/')
            val byPattern = patterns.any { it.matches(name) }
            val big = job.autoLfsMb > 0 && l.size >= limit
            val lfs = byPattern || big
            if (!lfs && l.size > 90L * 1024 * 1024) { log("Skipped ${l.path}: over 90 MB (enable Git LFS)"); continue }
            if (big && !byPattern) lfsLines.add("${l.path} filter=lfs diff=lfs merge=lfs -text")
            items.add(UploadItem(l.path, l.size, { c.contentResolver.openInputStream(l.doc.uri)!! }, lfs))
        }
        if (lfsLines.isNotEmpty()) {
            val existing = all.firstOrNull { it.path == ".gitattributes" }
                ?.let { c.contentResolver.openInputStream(it.doc.uri)?.use { s -> String(s.readBytes()) } }.orEmpty()
            val lines = (existing.lines().filter { it.isNotBlank() } + lfsLines).distinct()
            val bytes = (lines.joinToString("\n") + "\n").toByteArray()
            items.removeAll { it.path == ".gitattributes" }
            items.add(0, UploadItem(".gitattributes", bytes.size.toLong(), { ByteArrayInputStream(bytes) }))
        }
        if (job.wf != null && items.none { it.path.startsWith(".github/workflows/") }) {
            val bytes = Detect.template(c, job.wf).toByteArray()
            items.add(UploadItem(".github/workflows/build.yml", bytes.size.toLong(), { ByteArrayInputStream(bytes) }))
        }
        if (items.isEmpty()) throw ApiException(0, "No files to upload")
        val cacheFile = File(c.filesDir, "upload-${job.id}.json")
        val cache = mutableMapOf<String, String>()
        if (cacheFile.exists()) try {
            val o = JSONObject(cacheFile.readText()); for (k in o.keys()) cache[k] = o.getString(k)
        } catch (e: Exception) {}
        var lastSave = 0L
        return Github.uploadFiles(job.owner, job.repo, job.branch, items, job.message, cache, {
            val now = System.currentTimeMillis()
            if (now - lastSave > 1500) { lastSave = now; cacheFile.writeText(JSONObject(cache as Map<*, *>).toString()) }
        }, log, progress)
    }
}

class UploadWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("id") ?: return Result.failure()
        val job = Queue.get(id) ?: return Result.failure()
        if (job.state == "cancelled") return Result.success()
        Github.token = Store.token
        try { setForeground(Notifier.foreground(applicationContext, "Uploading ${job.repo}")) } catch (e: Exception) {}
        Queue.update(id) { it.copy(state = "running", detail = "") }
        var lastWrite = 0L
        return try {
            val sha = Queue.run(applicationContext, job, { msg -> Queue.update(id) { it.copy(detail = msg) } }, { d, t ->
                val now = System.currentTimeMillis()
                if (now - lastWrite > 700 || d == t) { lastWrite = now; Queue.update(id) { it.copy(done = d, total = t) } }
            })
            Queue.update(id) { it.copy(state = "done", detail = "Commit ${sha.take(7)}", commit = sha) }
            Store.log("Upload to ${job.label}", true)
            Notifier.post(applicationContext, id.hashCode(), "Upload finished", "${job.label} - commit ${sha.take(7)}")
            Result.success()
        } catch (e: CancellationException) {
            if (Queue.get(id)?.state != "cancelled") Queue.update(id) { it.copy(state = "waiting", detail = "Paused, will resume") }
            throw e
        } catch (e: Exception) {
            val transient = e is IOException || (e is ApiException && (e.code >= 500 || e.code == 429))
            if (transient && runAttemptCount < 10) {
                Queue.update(id) { it.copy(state = "waiting", detail = "Waiting for network: ${e.message}") }
                Result.retry()
            } else {
                Queue.update(id) { it.copy(state = "failed", detail = e.message.orEmpty()) }
                Store.log("Upload to ${job.label}", false, e.message.orEmpty())
                Notifier.post(applicationContext, id.hashCode(), "Upload failed", "${job.label}: ${e.message}")
                Result.failure()
            }
        }
    }
}

class PollWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        if (Store.token.isEmpty()) return Result.success()
        Github.token = Store.token
        try {
            val seen = Store.seen()
            val unread = Github.arr("/notifications?all=false&per_page=30").list()
            val fresh = unread.filter { (it.s("id") + ":" + it.s("updated_at")) !in seen }
            fresh.take(5).forEach {
                Notifier.post(
                    applicationContext, it.s("id").hashCode(),
                    it.optJSONObject("repository")?.s("full_name").orEmpty(), it.optJSONObject("subject")?.s("title").orEmpty()
                )
            }
            if (fresh.size > 5) Notifier.post(applicationContext, 7001, "GitHub", "${fresh.size - 5} more notifications")
            Store.markSeen(fresh.map { it.s("id") + ":" + it.s("updated_at") })
            for (w in Store.watched()) {
                val (base, rid) = w.split("|")
                try {
                    val r = Github.get("$base/actions/runs/$rid")
                    if (r.s("status") == "completed") {
                        Notifier.post(applicationContext, rid.hashCode(), "Build ${r.s("conclusion")}", "${r.s("name")}: ${r.s("display_title")}")
                        Store.unwatch(w)
                    }
                } catch (e: ApiException) { if (e.code == 404) Store.unwatch(w) }
            }
        } catch (e: IOException) { return Result.retry() } catch (e: ApiException) { return Result.success() }
        return Result.success()
    }
}

object Notifier {
    private const val CH = "ghm"

    fun init(c: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(NotificationChannel(CH, "GitHub Manager", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    private fun build(c: Context, title: String, text: String) =
        NotificationCompat.Builder(c, CH).setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true)

    @SuppressLint("MissingPermission")
    fun post(c: Context, id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        NotificationManagerCompat.from(c).notify(id, build(c, title, text).build())
    }

    fun foreground(c: Context, title: String): ForegroundInfo {
        val n = build(c, title, "Working in the background").setOngoing(true).setSmallIcon(android.R.drawable.stat_sys_upload).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(9001, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(9001, n)
    }

    fun schedule(c: Context) {
        val req = PeriodicWorkRequestBuilder<PollWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(c).enqueueUniquePeriodicWork("poll", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun cancel(c: Context) { WorkManager.getInstance(c).cancelUniqueWork("poll") }
}
