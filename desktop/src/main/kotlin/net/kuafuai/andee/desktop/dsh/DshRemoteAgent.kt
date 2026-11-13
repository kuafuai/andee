package net.kuafuai.andee.desktop.dsh

import net.kuafuai.andee.peer.PeerRemote
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/**
 * This Mac's dsh, as a paired device's own agent ([PeerRemote.Agent]): the phone's `delegate_task` and friends run
 * on [DshWorkers] directly, with no Andee in between. Every call arrives from one paired device ([device]); the
 * jobs it starts are marked with that device so the report goes back to it and not to this Mac's own brain.
 *
 * The trust of a job started this way is [DshPlan.Trust.OWN]: there is nobody at this Mac to ask when a worker wants
 * to go outside its folder, so the answer is no. (The phone's Andee decided to send the job; the Mac does not judge it,
 * but it does not hand the worker more than its folder either.)
 */
class DshRemoteAgent(private val workers: DshWorkers, private val device: String, private val on: () -> Boolean) : PeerRemote.Agent {

    override fun available() = on() && workers.available()

    override fun call(method: String, args: JSONObject): JSONObject = when (method) {
        "dsh.delegate" -> workers.delegate(args, DshPlan.Trust.OWN, origin = device)
        "dsh.status" -> {
            // One worker by id must be this device's own before it is described; the list is filtered to its own.
            if (args.optString("id").isNotBlank()) mine(args)
            onlyMine(workers.status(args))
        }
        "dsh.cancel" -> { mine(args); workers.cancel(args) }
        "dsh.read" -> { mine(args); workers.read(args) }
        // Finder is on this Mac and the phone has none; what it wants is the file, and that is `remote.get`.
        "dsh.open" -> throw IllegalStateException(
            "Showing a folder in Finder is not something that reaches a phone. Read the file with read_worker_file, or open it from the phone's own file view.",
        )
        else -> throw IllegalArgumentException("unknown method $method")
    }

    /** A folder's entries, one level. Inside the worker's folder only, and only a worker this device started. */
    override fun list(worker: String, path: String): JSONObject {
        val d = inside(worker, path)
        require(d.isDirectory) { "$path is not a folder in worker #$worker." }
        val arr = JSONArray()
        (d.listFiles() ?: emptyArray()).filter { !it.name.startsWith(".") }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })).forEach {
            arr.put(JSONObject().put("name", it.name).put("dir", it.isDirectory).put("size", if (it.isFile) it.length() else 0L).put("modified", it.lastModified()))
        }
        return JSONObject().put("path", path).put("entries", arr)
    }

    /** [length] bytes of a file from [offset], never the whole file. */
    override fun read(worker: String, path: String, offset: Long, length: Int): PeerRemote.Chunk {
        val f = inside(worker, path)
        require(f.isFile) { "$path is not a file in worker #$worker." }
        val size = f.length()
        RandomAccessFile(f, "r").use { r ->
            r.seek(offset.coerceIn(0, size))
            val buf = ByteArray(length.coerceIn(1, PeerRemote.CHUNK))
            val n = r.read(buf).coerceAtLeast(0)
            return PeerRemote.Chunk(buf.copyOf(n), size, offset.coerceIn(0, size) + n >= size)
        }
    }

    // ---- the walls ----

    private fun mine(args: JSONObject) {
        val id = args.optString("id").trim().removePrefix("#")
        val w = workers.get(id) ?: throw IllegalArgumentException("There is no worker #$id.")
        require(w.origin == device) { "Worker #$id was not started from this device." }
    }

    /** Status lists only the workers this device started: what other jobs are on this Mac is not its business. */
    private fun onlyMine(status: JSONObject): JSONObject {
        val arr = status.optJSONArray("workers") ?: return status
        val kept = JSONArray()
        for (i in 0 until arr.length()) arr.getJSONObject(i).takeIf { workers.get(it.optString("id"))?.origin == device }?.let(kept::put)
        return status.put("workers", kept)
    }

    /**
     * [path] inside worker [worker]'s folder, resolved through links: a link that leaves the folder is outside it. A
     * leading `/` is read as relative to the folder (it is joined, not substituted), so it names nothing outside it.
     */
    private fun inside(worker: String, path: String): File {
        val id = worker.trim().removePrefix("#")
        val w = workers.get(id) ?: throw IllegalArgumentException("There is no worker #$id.")
        require(w.origin == device) { "Worker #$id was not started from this device." }
        val base = File(w.dir).canonicalFile
        val f = File(base, path).canonicalFile
        require(f == base || f.path.startsWith(base.path + File.separator)) { "$path is not inside worker #$id's folder." }
        return f
    }
}
