package io.github.crmapache.amazingclaudecode.editor

import io.github.crmapache.amazingclaudecode.claude.ClaudeHome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.File
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
 * The CLI keeps a copy of every file it edits, taken before the first edit of the file in each turn, under
 * `file-history/<conversation>/<sha256 of the path, 16 hex>@v<N>` in its home - the copies its own rewind of
 * code puts back. The newest copy of the file is therefore the file as the turn found it, and the file on disk
 * right after the result is the file as the edit left it. The hunks between the two, less what the turn's
 * earlier edits of the file already reported, are the edit's. They are written into the event as
 * `structuredPatch` with `accPatch: true` beside them, and the counts as `accCounts` - the phone is sent the
 * event without the hunks, which can run to megabytes, and reads the counts (see RemoteAgent's fitted).
 *
 * Nothing here is a guess: no copy, a file too big to read, a diff too long to carry - and the event goes on as
 * the CLI wrote it.
 */
internal class UndiffedEdits(private val home: () -> ClaudeHome?) {

    /**
     * The edits of the turn going on now, by conversation and file: what the base of the next undiffed edit is
     * built from. Cleared at the turn's end - the next turn starts from a newer copy.
     */
    private val seen = ConcurrentHashMap<String, MutableList<ChangeRevert.Edit>>()

    /** [line] with the missing diff filled in - or [line] itself, which is nearly always what comes back. */
    fun fill(line: String, conversationId: String?): String {
        if (conversationId == null || !line.contains(USER) || !line.contains(PATCH_FIELD)) return line
        return runCatching { filled(line, conversationId) }.getOrNull() ?: line
    }

    /** The turn is over: its edits stop being the base of anything. */
    fun turnEnded(conversationId: String?) {
        if (conversationId == null) return
        seen.keys.removeIf { it.startsWith("$conversationId|") }
    }

    private fun filled(line: String, conversationId: String): String? {
        val root = Json.parseToJsonElement(line) as? JsonObject ?: return null
        if (root.text("type") != "user") return null
        val result = root[RESULT] as? JsonObject ?: return null
        val path = result.text("filePath").ifEmpty { return null }
        val patch = result["structuredPatch"] as? JsonArray ?: return null
        val key = "$conversationId|$path"

        // An edit the CLI did report: remembered, as the base of an undiffed one later in the turn.
        if (result.text("type") == "create") {
            remember(key, ChangeRevert.Edit.Create(result.text("content")))
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
        val base = when {
            earlier.firstOrNull() is ChangeRevert.Edit.Create ->
                (ChangeRevert.redo(null, earlier) as? ChangeRevert.Result.Text)?.text
            earlier.isEmpty() -> newestCopy(home, conversationId, path)
            else -> newestCopy(home, conversationId, path)?.let { copy ->
                (ChangeRevert.redo(copy, earlier) as? ChangeRevert.Result.Text)?.text
            }
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

    /** The newest copy the CLI kept of [path] in this conversation - the file as the turn found it. */
    private fun newestCopy(home: ClaudeHome, conversationId: String, path: String): String? {
        val folder = File(home.configDirectory, "file-history/$conversationId")
        val name = sha256(path).take(16)
        val newest = folder.listFiles { candidate -> candidate.name.startsWith("$name@v") }
            ?.maxByOrNull { it.name.substringAfter("@v").toIntOrNull() ?: 0 }
            ?: return null
        if (newest.length() > MAX_FILE_BYTES) return null
        return newest.readText()
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

        /** Cheap checks before anything is parsed: this sees every line of every conversation. */
        private const val USER = "\"type\":\"user\""
        private const val PATCH_FIELD = "\"structuredPatch\""

        /** A file bigger than this is not read for a diff - the event goes on as the CLI wrote it. */
        private const val MAX_FILE_BYTES = 8L * 1024 * 1024

        /**
         * [message] with every diff worked out here taken back out, the counts left - for a phone, whose frame a
         * diff of thousands of lines would not fit (see RemoteAgent's fitted). Null when there was none to take.
         */
        fun lightened(message: String): String? {
            if (!message.contains("\"$ACC_PATCH\":true")) return null
            return runCatching { lighten(Json.parseToJsonElement(message)).toString() }.getOrNull()
        }

        private fun lighten(element: JsonElement): JsonElement = when (element) {
            is JsonObject ->
                if ((element[ACC_PATCH] as? JsonPrimitive)?.contentOrNull == "true") {
                    JsonObject(element + ("structuredPatch" to JsonArray(emptyList())))
                } else {
                    JsonObject(element.mapValues { (_, value) -> lighten(value) })
                }
            is JsonArray -> JsonArray(element.map(::lighten))
            else -> element
        }
    }
}
