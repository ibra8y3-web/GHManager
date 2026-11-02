package com.ghmanager.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.CreateBranchCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand
import org.eclipse.jgit.lib.BranchTrackingStatus
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File

data class LocalInfo(
    val branch: String, val ahead: Int, val behind: Int, val state: String,
    val conflicts: List<String>, val changes: List<String>
)

data class CommitLine(val sha: String, val message: String, val author: String)

object LocalGit {
    fun dir(c: Context, owner: String, name: String): File =
        File(c.getExternalFilesDir("repos") ?: File(c.filesDir, "repos"), "${owner}__$name")

    fun cloned(dir: File) = File(dir, ".git").exists()

    private fun creds() = UsernamePasswordCredentialsProvider("x-access-token", Github.token)

    private suspend fun <T> git(dir: File, block: (Git) -> T): T = withContext(Dispatchers.IO) { Git.open(dir).use(block) }

    suspend fun clone(dir: File, owner: String, name: String, branch: String) = withContext(Dispatchers.IO) {
        if (dir.exists()) dir.deleteRecursively()
        Git.cloneRepository().setURI("https://github.com/$owner/$name.git").setDirectory(dir)
            .setBranch(branch).setCredentialsProvider(creds()).call().close()
    }

    suspend fun delete(dir: File) = withContext(Dispatchers.IO) { dir.deleteRecursively() }

    suspend fun info(dir: File): LocalInfo = git(dir) { g ->
        val repo = g.repository
        val st = g.status().call()
        val branch = repo.branch ?: "HEAD"
        val track = try { BranchTrackingStatus.of(repo, branch) } catch (e: Exception) { null }
        val changes = mutableListOf<String>()
        st.added.forEach { changes.add("A  $it") }
        st.changed.forEach { changes.add("M  $it") }
        st.modified.forEach { changes.add("M* $it") }
        st.removed.forEach { changes.add("D  $it") }
        st.missing.forEach { changes.add("D* $it") }
        st.untracked.forEach { changes.add("?  $it") }
        LocalInfo(branch, track?.aheadCount ?: 0, track?.behindCount ?: 0, repo.repositoryState.name, st.conflicting.sorted(), changes.sorted())
    }

    suspend fun fetch(dir: File) = git(dir) { g -> g.fetch().setCredentialsProvider(creds()).call(); Unit }

    suspend fun pull(dir: File): String = git(dir) { g ->
        val r = g.pull().setCredentialsProvider(creds()).call()
        val m = r.mergeResult
        if (r.isSuccessful) "Pull complete (${m?.mergeStatus ?: "up to date"})"
        else "Pull stopped: ${m?.mergeStatus ?: "see conflicts below"}"
    }

    suspend fun commitAll(dir: File, msg: String, name: String, email: String): String = git(dir) { g ->
        if (g.status().call().conflicting.isNotEmpty()) throw IllegalStateException("Resolve the conflicts first.")
        g.add().addFilepattern(".").call()
        g.add().setUpdate(true).addFilepattern(".").call()
        val st = g.status().call()
        if (st.added.isEmpty() && st.changed.isEmpty() && st.removed.isEmpty() && g.repository.repositoryState.name != "MERGING") {
            throw IllegalStateException("Nothing to commit.")
        }
        g.commit().setMessage(msg).setAuthor(name, email).setCommitter(name, email).call().name.take(7)
    }

    suspend fun push(dir: File): String = git(dir) { g ->
        val results = g.push().setCredentialsProvider(creds()).call()
        val bad = results.flatMap { it.remoteUpdates }.firstOrNull {
            it.status != RemoteRefUpdate.Status.OK && it.status != RemoteRefUpdate.Status.UP_TO_DATE
        }
        if (bad != null) throw IllegalStateException("Push rejected: ${bad.status} ${bad.message.orEmpty()}")
        "Pushed"
    }

    suspend fun branches(dir: File): List<String> = git(dir) { g ->
        g.branchList().setListMode(ListBranchCommand.ListMode.ALL).call()
            .map { it.name.removePrefix("refs/heads/").removePrefix("refs/remotes/origin/") }
            .filter { it != "HEAD" }.distinct().sorted()
    }

    suspend fun checkout(dir: File, name: String) = git(dir) { g ->
        val exists = g.repository.findRef("refs/heads/$name") != null
        val cmd = g.checkout().setName(name).setCreateBranch(!exists)
        if (!exists) cmd.setStartPoint("origin/$name").setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
        cmd.call(); Unit
    }

    suspend fun merge(dir: File, branch: String): String = git(dir) { g ->
        val ref = g.repository.resolve("origin/$branch") ?: g.repository.resolve(branch)
            ?: throw IllegalStateException("Branch $branch not found. Fetch first.")
        "Merge result: " + g.merge().include(ref).call().mergeStatus
    }

    suspend fun log(dir: File, n: Int): List<CommitLine> = git(dir) { g ->
        g.log().setMaxCount(n).call().map { CommitLine(it.name.take(7), it.shortMessage, it.authorIdent.name) }
    }

    suspend fun readText(dir: File, path: String): String = withContext(Dispatchers.IO) { File(dir, path).readText() }

    suspend fun saveResolved(dir: File, path: String, text: String) = git(dir) { g ->
        if (text.contains("<<<<<<<") || text.contains(">>>>>>>")) throw IllegalStateException("Conflict markers are still in the file.")
        File(dir, path).writeText(text)
        g.add().addFilepattern(path).call(); Unit
    }

    suspend fun take(dir: File, path: String, theirs: Boolean) = git(dir) { g ->
        g.checkout().setStage(if (theirs) org.eclipse.jgit.api.CheckoutCommand.Stage.THEIRS else org.eclipse.jgit.api.CheckoutCommand.Stage.OURS)
            .addPath(path).call()
        g.add().addFilepattern(path).call(); Unit
    }
}
