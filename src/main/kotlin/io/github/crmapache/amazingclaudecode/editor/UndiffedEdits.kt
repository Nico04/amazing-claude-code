package io.github.crmapache.amazingclaudecode.editor

import io.github.crmapache.amazingclaudecode.claude.ClaudeHome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * The diff the CLI leaves out of a big edit, worked out here and put where it would have been.
 *
 * Every Edit and Write comes back with the hunks the CLI applied (`structuredPatch`), and the list of a turn's
 * changed files is drawn and put back from them (see feed/changes.ts and [ChangeRevert]). For a big change the
 * CLI sends an empty list instead - measured on 2.1.293: a `replace_all` across an 8000-line file came back
 * with `structuredPatch: []` and no original text at all. Such a file was missing from the list altogether,
 * the one change of the turn most worth seeing.
 *
 * The CLI keeps copies of the files it edits under `file-history/<conversation>/<sha256 of the path, 16 hex>@v<N>`
 * in its home - the copies its own rewind of code puts back - and writes down in the transcript which copy it took
 * when (see [Copies]). The copy it took for the turn going on now is the file as the turn found it, and the file on
 * disk right after the result is the file as the edit left it. The hunks between the two, less what the turn's
 * earlier edits of the file already reported, are the edit's. They are written into the event as
 * `structuredPatch` with `accPatch: true` beside them, and the counts as `accCounts` - the phone is sent the
 * event without the hunks, which can run to megabytes, and reads the counts (see RemoteAgent's fitted).
 *
 * Nothing here is a guess: no copy, a file too big to read, a diff too long to carry - and the event goes on as
 * the CLI wrote it.
 *
 * The same copies settle a turn at its end ([settle]). A file is changed by more than the calls that report
 * on it: a formatter the project runs after every edit (a PostToolUse hook), the agent's own `sed` or script in
 * the same turn, the CLI itself adding fields to a memory file it was told to write. Each left the file other
 * than its diffs say, and the revert, holding the file against those diffs, refused every such file as changed
 * since - while nobody had touched it after the turn. So when the turn is over, every file it edited is held
 * against the disk, and one that does not read as its diffs say is sent to the panel as what the turn really
 * did to it: from its copy (or from nothing, for a file the turn created) to the file as it stands.
 */
internal class UndiffedEdits(
    private val home: () -> ClaudeHome?,
    /** Where the CLI writes a conversation down - and with it, every copy of a file it takes (see [Copies]). */
    private val transcript: (conversationId: String) -> File? = { null },
) {

    /**
     * The edits of the turn going on now, by conversation and file: what the base of the next undiffed edit is
     * built from. Cleared at the turn's end - the next turn is built on the copies taken for it.
     */
    private val seen = ConcurrentHashMap<String, MutableList<ChangeRevert.Edit>>()

    /**
     * The text each Write of the turn going on now was called with, by conversation and call. A created file is
     * counted and put back by it in the panel (see changeOf in feed/changes.ts), so it is what [settle] holds the
     * disk against - the result's own copy is the file as the CLI left it, which for a memory file is already
     * more than the call wrote.
     */
    private val written = ConcurrentHashMap<String, String>()

    /**
     * The copies the CLI has taken since a conversation's last turn ended: what the turn going on now is built on.
     *
     * The CLI does not copy every file at every turn. It copies the files it tracks when a person's prompt starts a
     * turn - reusing a copy the file still matches - and a file it has never tracked, before its first edit; each
     * time it writes down which copy it took (`file-history-snapshot` and `file-history-delta` in the transcript). A
     * turn a task's notification starts gets no snapshot (measured on 2.1.293) - only a file it edits for the first
     * time gets its copy - and the newest copy on the disk of any other is then the file as some earlier turn found
     * it: before that turn's work, an agent's `sed` since, a hand's edit. A diff from it takes all of that along, and
     * a revert by it erases it. So only a copy written down since the last turn ended is the file as this turn found
     * it - and the transcript names it exactly, where the newest on the disk may be one from a stretch a rewind cut
     * off. Of two records of one file the later one counts, by its own time rather than by where it was written: a
     * fork is handed its source's records too, written down as its first turn begins (copyFileHistoryForResume).
     */
    private class Copies(
        /** Where the transcript stood when the last turn ended - or when the conversation was first heard here. */
        var from: Long,
    ) {
        /** How far it has been read since. */
        var read = from

        /**
         * The copy taken of each file since [from], and when the record that names it was made - by the first 16 hex of
         * the file's path's hash, as [copyFor] looks it up.
         */
        val taken = HashMap<String, Pair<String, String>>()

        /** The transcript, once found. */
        var file: File? = null
    }

    private val copies = ConcurrentHashMap<String, Copies>()

    /**
     * The files of the turn going on now whose undiffed edit could not be worked out when it came, by conversation and
     * file: the record of the copy it is worked out from reaches the transcript a moment after the edit's result (the
     * CLI writes it down every hundred milliseconds, and the result is not held for it). Such a file is settled at the
     * turn's end from its copy alone, whatever its other edits say - its row has no diff of its own until then.
     */
    private val owed = ConcurrentHashMap.newKeySet<String>()

    /** [line] with the missing diff filled in - or [line] itself, which is nearly always what comes back. */
    fun fill(line: String, conversationId: String?): String {
        if (conversationId == null) return line
        // The first line of a conversation heard here comes before the first copy taken for its turn (the CLI takes
        // them as the turn begins): what its transcript says from now on is this conversation's turns here.
        copies.computeIfAbsent(conversationId) { id -> Copies(from = transcriptOf(id, null)?.length() ?: 0) }
        if (line.contains(ASSISTANT) && line.contains(WRITE_CALL)) {
            runCatching { noteWrites(line, conversationId) }
            return line
        }
        if (!line.contains(USER) || !line.contains(PATCH_FIELD)) return line
        return runCatching { filled(line, conversationId) }.getOrNull() ?: line
    }

    /**
     * The turn is over. Every file it edited is held against the disk, and the ones its diffs do not take to what
     * stands there come back as one event for the panel - before the turn's result closes their list (see
     * settleChanges in feed/changes.ts). Null when there are none, which is nearly always.
     *
     * Its edits and its copies stop being the base of anything either way: the next turn is built on the copies the
     * CLI takes for it, if it takes any.
     */
    fun settle(conversationId: String?): String? {
        if (conversationId == null) return null
        val prefix = "$conversationId|"
        written.keys.removeIf { it.startsWith(prefix) }

        val home = home()
        val files = seen.keys.filter { it.startsWith(prefix) }.mapNotNull { key ->
            val edits = seen.remove(key) ?: return@mapNotNull null
            val owing = owed.remove(key)
            home?.let { runCatching { settled(it, key.removePrefix(prefix), edits, conversationId, owing) }.getOrNull() }
        }
        turnEnded(conversationId)
        if (files.isEmpty()) return null

        return buildJsonObject {
            put("type", SETTLED_TYPE)
            put("files", JsonArray(files))
        }.toString()
    }

    /**
     * The turn is over without a result to settle it by - stopped, cut back by a rewind, its process gone. Nothing of it
     * is the base of anything after it, as at a result (see [settle]).
     */
    fun turnOver(conversationId: String?) {
        if (conversationId == null) return
        val prefix = "$conversationId|"
        listOf(seen.keys, written.keys, owed).forEach { keys -> keys.removeIf { it.startsWith(prefix) } }
        turnEnded(conversationId)
    }

    /**
     * One file as the turn really left it - or null when its diffs say so already, or when nothing can be said for
     * sure: the file gone or too big, no copy of it, a diff too long to carry. [owing] - one of its edits came without
     * a diff that could be worked out then (see [owed]): its diffs say nothing for sure, and the copy says it all.
     */
    private fun settled(
        home: ClaudeHome,
        path: String,
        edits: List<ChangeRevert.Edit>,
        conversationId: String,
        owing: Boolean = false,
    ): JsonObject? {
        val file = home.hostPath(path)
        if (!file.isFile || file.length() > MAX_FILE_BYTES) return null
        val left = file.readText().replace("\r\n", "\n")

        val created = edits.firstOrNull() is ChangeRevert.Edit.Create
        val before = if (created) "" else copyFor(home, conversationId, path) ?: return null
        // The copy has to be the file this turn found (see Copies): one from before that would put back more than this
        // turn did, and a revert that does so is worse than one that refuses. And one the turn's first edit does not
        // even go on is somebody else's file.
        if (!created && !owing && ChangeRevert.redo(before, edits.take(1)) !is ChangeRevert.Result.Text) return null

        val expected = ChangeRevert.redo(if (created) null else before, edits) as? ChangeRevert.Result.Text
        if (!owing && expected?.text == left) return null

        val hunks = LineDiff.hunks(before, left)?.takeIf { it.isNotEmpty() } ?: return null
        val lines = hunks.flatMap { it.lines }
        return buildJsonObject {
            put("path", path)
            put("added", lines.count { it.startsWith("+") })
            put("removed", lines.count { it.startsWith("-") })
            putJsonArray("edits") {
                // A created file is made empty and then filled, rather than created with its text: its text then
                // travels as short lines, and a journal cuts long strings (see JournalTrim).
                if (created) {
                    addJsonObject {
                        put("kind", "create")
                        put("content", "")
                    }
                }
                addJsonObject {
                    put("kind", "patch")
                    put("hunks", hunksJson(hunks))
                }
            }
        }
    }

    /** The text of each Write in an assistant line, kept until its result says whether it created the file. */
    private fun noteWrites(line: String, conversationId: String) {
        val root = Json.parseToJsonElement(line) as? JsonObject ?: return
        // A subagent's call is never one the panel sees, and there it counts by the result (see changeOf).
        if (root.text("type") != "assistant" || root.text("parent_tool_use_id").isNotEmpty()) return
        val blocks = (root["message"] as? JsonObject)?.get("content") as? JsonArray ?: return
        for (block in blocks) {
            val call = block as? JsonObject ?: continue
            if (call.text("type") != "tool_use" || call.text("name") != "Write") continue
            val content = (call["input"] as? JsonObject)?.text("content").orEmpty()
            if (content.isNotEmpty()) written["$conversationId|${call.text("id")}"] = content
        }
    }

    /** The call a result line answers. */
    private fun callOf(root: JsonObject): String {
        val blocks = (root["message"] as? JsonObject)?.get("content") as? JsonArray ?: return ""
        return blocks.firstNotNullOfOrNull { block ->
            (block as? JsonObject)?.takeIf { it.text("type") == "tool_result" }?.text("tool_use_id")
        }.orEmpty()
    }

    private fun filled(line: String, conversationId: String): String? {
        val root = Json.parseToJsonElement(line) as? JsonObject ?: return null
        if (root.text("type") != "user") return null
        val result = root[RESULT] as? JsonObject ?: return null
        val path = result.text("filePath").ifEmpty { return null }
        val patch = result["structuredPatch"] as? JsonArray ?: return null
        val key = "$conversationId|$path"

        // An edit the CLI did report: remembered, as the base of an undiffed one later in the turn - and as what the
        // panel will put the file back by. A created file by the text the call wrote, as the panel counts it.
        if (result.text("type") == "create") {
            val call = written.remove("$conversationId|${callOf(root)}")
            remember(key, ChangeRevert.Edit.Create(call ?: result.text("content")))
            return null
        }
        if (patch.isNotEmpty()) {
            ChangeRevert.requestsOf(patchRequest(path, patch)).firstOrNull()?.edits?.forEach { remember(key, it) }
            return null
        }

        // A write of exactly the text the file held: nothing changed, nothing to work out. Only then - the original
        // the CLI sends beside a big edit may be cut short, and an edit always changes something.
        val written = result.text("content")
        if (written.isNotEmpty() && written == result.text("originalFile")) return null

        val home = home() ?: return null
        val file = home.hostPath(path)
        if (!file.isFile || file.length() > MAX_FILE_BYTES) return null
        val after = file.readText()

        // The file as this edit found it: as the turn found it, plus what the turn's earlier edits of it did. A
        // file the turn itself created has no copy - it is what its creation and the edits after it made.
        val earlier = seen[key].orEmpty()
        val created = earlier.firstOrNull() is ChangeRevert.Edit.Create
        // An edit already owed has left this turn's diffs of the file short: nothing after it is built on them either.
        if (!created && key in owed) return null
        val copy = if (created) null else copyFor(home, conversationId, path) ?: run {
            owe(key)
            return null
        }
        val base = when {
            created -> (ChangeRevert.redo(null, earlier) as? ChangeRevert.Result.Text)?.text
            earlier.isEmpty() -> copy
            else -> (ChangeRevert.redo(copy, earlier) as? ChangeRevert.Result.Text)?.text
        } ?: return null
        val hunks = LineDiff.hunks(base, after) ?: return null
        if (hunks.isEmpty()) return null

        val edit = ChangeRevert.Edit.Patch(hunks.map { ChangeRevert.Hunk(it.newStart, it.lines, it.oldStart) })
        remember(key, edit)

        val lines = hunks.flatMap { it.lines }
        val filledResult = JsonObject(
            result + mapOf(
                "structuredPatch" to hunksJson(hunks),
                ACC_PATCH to JsonPrimitive(true),
                "accCounts" to buildJsonObject {
                    put("added", lines.count { it.startsWith("+") })
                    put("removed", lines.count { it.startsWith("-") })
                },
            ),
        )
        return JsonObject(root + (RESULT to filledResult)).toString()
    }

    private fun remember(key: String, edit: ChangeRevert.Edit) {
        seen.getOrPut(key) { mutableListOf() }.add(edit)
    }

    /** An undiffed edit whose copy is not written down yet: the turn's end works the file out (see [owed]). */
    private fun owe(key: String) {
        owed += key
        seen.getOrPut(key) { mutableListOf() }
    }

    /**
     * The copy the CLI took of [path] for the turn going on now - the file as the turn found it - or null when it took
     * none (see [Copies]).
     */
    private fun copyFor(home: ClaudeHome, conversationId: String, path: String): String? {
        val record = copies[conversationId] ?: return null
        val name = synchronized(record) {
            catchUp(conversationId, record)
            record.taken[sha256(path).take(16)]?.first
        } ?: return null
        val copy = File(home.configDirectory, "file-history/$conversationId/$name")
        if (!copy.isFile || copy.length() > MAX_FILE_BYTES) return null
        return copy.readText()
    }

    /** The turn is over: what is written down from here on belongs to the next one. */
    private fun turnEnded(conversationId: String) {
        val record = copies[conversationId] ?: return
        synchronized(record) {
            record.from = transcriptOf(conversationId, record)?.length() ?: record.read
            record.read = record.from
            record.taken.clear()
        }
        val prefix = "$conversationId|"
        owed.removeIf { it.startsWith(prefix) }
    }

    private fun transcriptOf(conversationId: String, record: Copies?): File? {
        record?.file?.let { if (it.isFile) return it }
        return transcript(conversationId)?.also { record?.file = it }
    }

    /**
     * The copies the transcript has written down since [Copies.read], up to its last whole line. Only the lines that
     * open as the CLI's own copy records are read at all: the rest of a turn - its tool output - can run to megabytes.
     */
    private fun catchUp(conversationId: String, record: Copies) {
        val file = transcriptOf(conversationId, record) ?: return
        val length = file.length()
        if (length <= record.read) return

        val line = ByteArrayOutputStream()
        var ours = true
        RandomAccessFile(file, "r").use { input ->
            input.seek(record.read)
            val buffer = ByteArray(READ_CHUNK)
            var position = record.read
            while (position < length) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), length - position).toInt())
                if (count <= 0) break
                for (index in 0 until count) {
                    val byte = buffer[index]
                    if (byte == NEWLINE) {
                        if (ours && line.size() >= COPY_RECORD.size) runCatching { noteCopies(record, line.toString(Charsets.UTF_8)) }
                        line.reset()
                        ours = true
                        record.read = position + index + 1
                    } else if (ours) {
                        val at = line.size()
                        if (at < COPY_RECORD.size && byte != COPY_RECORD[at]) ours = false else line.write(byte.toInt())
                    }
                }
                position += count
            }
        }
    }

    /**
     * One record of copies taken: every file the CLI tracks, at a prompt, or one file it starts tracking. A file the
     * CLI found missing has no copy - nothing to build on.
     */
    private fun noteCopies(record: Copies, line: String) {
        val root = Json.parseToJsonElement(line) as? JsonObject ?: return
        val snapshot = root["snapshot"] as? JsonObject
        val (backups, at) = when (root.text("type")) {
            SNAPSHOT_RECORD -> (snapshot?.get("trackedFileBackups") as? JsonObject)?.values.orEmpty() to snapshot?.text("timestamp").orEmpty()
            DELTA_RECORD -> listOfNotNull(root["backup"]) to root.text("timestamp")
            else -> return
        }
        for (backup in backups) {
            val name = (backup as? JsonObject)?.text("backupFileName")?.takeIf { COPY_NAME.matches(it) } ?: continue
            val file = name.substringBefore("@v")
            // ISO times in one form compare as text.
            if (at >= (record.taken[file]?.second ?: "")) record.taken[file] = name to at
        }
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun hunksJson(hunks: List<LineDiff.Hunk>): JsonArray = buildJsonArray {
        for (hunk in hunks) {
            add(
                buildJsonObject {
                    put("oldStart", hunk.oldStart)
                    put("newStart", hunk.newStart)
                    put("lines", JsonArray(hunk.lines.map(::JsonPrimitive)))
                },
            )
        }
    }

    /** The CLI's patch in the shape [ChangeRevert.requestsOf] reads, so both sides parse it one way. */
    private fun patchRequest(path: String, patch: JsonArray): JsonElement = buildJsonArray {
        add(
            buildJsonObject {
                put("path", path)
                put(
                    "edits",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("kind", "patch")
                                put(
                                    "hunks",
                                    JsonArray(
                                        patch.mapNotNull { hunk ->
                                            val fields = hunk as? JsonObject ?: return@mapNotNull null
                                            val start = (fields["newStart"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
                                            buildJsonObject {
                                                put("newStart", start)
                                                (fields["oldStart"] as? JsonPrimitive)?.intOrNull?.let { put("oldStart", it) }
                                                fields["lines"]?.let { put("lines", it) }
                                            }
                                        },
                                    ),
                                )
                            },
                        )
                    },
                )
            },
        )
    }

    private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull ?: ""

    companion object {
        private const val RESULT = "tool_use_result"
        private const val ACC_PATCH = "accPatch"

        /** The panel's own event that carries what a turn really did to its files (see [settle]). */
        const val SETTLED_TYPE = "files_settled"

        /** Cheap checks before anything is parsed: this sees every line of every conversation. */
        private const val USER = "\"type\":\"user\""
        private const val PATCH_FIELD = "\"structuredPatch\""
        private const val ASSISTANT = "\"type\":\"assistant\""
        private const val WRITE_CALL = "\"name\":\"Write\""

        /** The CLI's own records of the copies it takes, as they open in its transcript (see [Copies]). */
        private const val SNAPSHOT_RECORD = "file-history-snapshot"
        private const val DELTA_RECORD = "file-history-delta"
        private val COPY_RECORD = "{\"type\":\"file-history-".toByteArray(Charsets.UTF_8)
        private val COPY_NAME = Regex("[0-9a-f]{16}@v\\d+")
        private const val NEWLINE: Byte = 0x0A
        private const val READ_CHUNK = 64 * 1024

        /** A file bigger than this is not read for a diff - the event goes on as the CLI wrote it. */
        private const val MAX_FILE_BYTES = 8L * 1024 * 1024

        /**
         * [message] with every diff worked out here taken back out, the counts left - for a phone, whose frame a
         * diff of thousands of lines would not fit (see RemoteAgent's fitted). Null when there was none to take.
         */
        fun lightened(message: String): String? {
            if (!message.contains("\"$ACC_PATCH\":true") && !message.contains("\"$SETTLED_TYPE\"")) return null
            return runCatching { lighten(Json.parseToJsonElement(message)).toString() }.getOrNull()
        }

        private fun lighten(element: JsonElement): JsonElement = when (element) {
            is JsonObject -> when {
                (element[ACC_PATCH] as? JsonPrimitive)?.contentOrNull == "true" ->
                    JsonObject(element + ("structuredPatch" to JsonArray(emptyList())))
                (element["type"] as? JsonPrimitive)?.contentOrNull == SETTLED_TYPE && element["files"] is JsonArray ->
                    JsonObject(element + ("files" to JsonArray((element["files"] as JsonArray).map { file -> JsonObject(((file as? JsonObject) ?: JsonObject(emptyMap())) - "edits") })))
                else -> JsonObject(element.mapValues { (_, value) -> lighten(value) })
            }
            is JsonArray -> JsonArray(element.map(::lighten))
            else -> element
        }
    }
}
