package io.github.crmapache.amazingclaudecode.editor

import io.github.crmapache.amazingclaudecode.claude.ClaudeHome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The diff the CLI leaves out of a big edit, worked out from its own copy of the file - and the line diff it is
 * worked out with, held to the one thing a revert needs: its hunks take one text exactly to the other and back.
 */
class UndiffedEditsTest {

    private fun roundTrip(before: String, after: String) {
        val hunks = assertNotNull(LineDiff.hunks(before, after))
        val edit = ChangeRevert.Edit.Patch(hunks.map { ChangeRevert.Hunk(it.newStart, it.lines, it.oldStart) })
        assertEquals(ChangeRevert.Result.Text(after), ChangeRevert.redo(before, listOf(edit)), "forwards")
        assertEquals(ChangeRevert.Result.Text(before), ChangeRevert.undo(after, listOf(edit)), "backwards")
    }

    @Test
    fun `a replace across every line goes there and back`() {
        val before = (1..2000).joinToString("") { "item_$it = foo($it)\n" }
        roundTrip(before, before.replace("foo(", "bar("))
    }

    @Test
    fun `scattered changes of either length go there and back`() {
        val before = (1..300).joinToString("") { "line $it\n" }
        val after = before.replace("line 7\n", "seven\n").replace("line 150\n", "").replace("line 299\n", "line 299\nadded\nadded too\n")
        roundTrip(before, after)
        // Only the changes and their context, not the whole file.
        assertTrue(LineDiff.hunks(before, after)!!.sumOf { it.lines.size } < 40)
    }

    @Test
    fun `files without a final line break go there and back`() {
        roundTrip("a\nb\nc", "a\nB\nc")
        roundTrip("a\nb\nc\n", "a\nb\nc")
        roundTrip("a\nb\nc", "a\nb\nc\nd\n")
        roundTrip("", "x\n")
    }

    @Test
    fun `a diff too long to carry is not made`() {
        val before = (1..100).joinToString("") { "$it\n" }
        assertNull(LineDiff.hunks(before, before.replace("\n", " changed\n"), maxLines = 50))
    }

    private fun home(config: File) = ClaudeHome(
        configDirectory = config,
        managedSettingsDirectory = config,
        projectPaths = emptyList(),
        remote = false,
        toHost = { it },
    )

    private fun sha(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    /** A conversation's transcript, and the edits read against it - heard first, as a process's first line is. */
    private fun undiffed(config: File, transcript: File = Files.createTempFile("conv-1", ".jsonl").toFile()) =
        UndiffedEdits(home = { home(config) }, transcript = { transcript }).also { it.fill(INIT, "conv-1") } to transcript

    /** A copy of [file] taken by the CLI - written into its folder and down in the transcript, as a prompt's snapshot. */
    private fun copied(config: File, transcript: File, file: File, version: Int, text: String, at: String = "2026-10-15T10:00:0$version.000Z") {
        File(config, "file-history/conv-1").apply { mkdirs() }.let { File(it, "${sha(file.path)}@v$version").writeText(text) }
        transcript.appendText(
            buildJsonObject {
                put("type", "file-history-snapshot")
                put("messageId", "m$version")
                putJsonObject("snapshot") {
                    put("timestamp", at)
                    putJsonObject("trackedFileBackups") {
                        putJsonObject(file.name) { put("backupFileName", "${sha(file.path)}@v$version") }
                    }
                }
                put("isSnapshotUpdate", false)
            }.toString() + "\n",
        )
    }

    private val INIT = """{"type":"system","subtype":"init"}"""

    // The original the live stream sends beside a big edit is cut short - it must not read as "nothing changed".
    private fun resultLine(path: String, patch: String = "[]") =
        """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":"ok"}]},""" +
            """"tool_use_result":{"filePath":"$path","replaceAll":true,"originalFile":"x = foo(1)\\nx = fo","structuredPatch":$patch}}"""

    @Test
    fun `an edit reported without its diff gets one from the CLI's copy of the file`() {
        val config = Files.createTempDirectory("claude-home").toFile()
        val project = Files.createTempDirectory("project").toFile()
        val file = File(project, "big.js")
        val before = (1..500).joinToString("") { "x = foo($it)\n" }
        val after = before.replace("foo(", "bar(")
        file.writeText(after)
        val (undiffed, transcript) = undiffed(config)
        copied(config, transcript, file, 1, "old copy\n")
        copied(config, transcript, file, 2, before)

        val filled = Json.parseToJsonElement(undiffed.fill(resultLine(file.path), "conv-1")).jsonObject
        val result = filled["tool_use_result"]!!.jsonObject
        assertEquals("true", result["accPatch"]!!.jsonPrimitive.content)
        assertEquals("500", result["accCounts"]!!.jsonObject["added"]!!.jsonPrimitive.content)
        assertEquals("500", result["accCounts"]!!.jsonObject["removed"]!!.jsonPrimitive.content)

        // And the phone is sent the counts without the hunks.
        val light = Json.parseToJsonElement(UndiffedEdits.lightened(filled.toString())!!).jsonObject
        assertEquals(JsonArray(emptyList()), light["tool_use_result"]!!.jsonObject["structuredPatch"])
        assertTrue((light["tool_use_result"] as JsonObject).containsKey("accCounts"))
    }

    @Test
    fun `a line with its diff, or without a copy to work one out from, goes on as it came`() {
        val config = Files.createTempDirectory("claude-home").toFile()
        val file = File(Files.createTempDirectory("project").toFile(), "a.js").apply { writeText("b\n") }
        val (undiffed) = undiffed(config)

        val reported = resultLine(file.path, """[{"oldStart":1,"oldLines":1,"newStart":1,"newLines":1,"lines":["-a","+b"]}]""")
        assertSame(reported, undiffed.fill(reported, "conv-1"))

        val noCopy = resultLine(file.path)
        assertSame(noCopy, undiffed.fill(noCopy, "conv-1"))
        assertNull(UndiffedEdits.lightened(noCopy))
    }

    // --- The turn settled ------------------------------------------------------------

    private fun writeCall(id: String, path: String, content: String) = buildJsonObject {
        put("type", "assistant")
        putJsonObject("message") {
            putJsonArray("content") {
                addJsonObject {
                    put("type", "tool_use")
                    put("id", id)
                    put("name", "Write")
                    putJsonObject("input") {
                        put("file_path", path)
                        put("content", content)
                    }
                }
            }
        }
        put("parent_tool_use_id", JsonNull)
    }.toString()

    private fun answerLine(id: String, path: String, result: JsonObject) = buildJsonObject {
        put("type", "user")
        putJsonObject("message") {
            putJsonArray("content") {
                addJsonObject {
                    put("type", "tool_result")
                    put("tool_use_id", id)
                    put("content", "ok")
                }
            }
        }
        put("tool_use_result", JsonObject(result + ("filePath" to Json.parseToJsonElement("\"$path\""))))
    }.toString()

    private fun created(content: String) = buildJsonObject {
        put("type", "create")
        put("content", content)
        putJsonArray("structuredPatch") {}
    }

    private fun edited(vararg hunk: String, start: Int = 1) = buildJsonObject {
        putJsonArray("structuredPatch") {
            addJsonObject {
                put("oldStart", start)
                put("newStart", start)
                putJsonArray("lines") { hunk.forEach { add(it) } }
            }
        }
    }

    /** The settled event's one file, read back as the edits the panel will send for a revert. */
    private fun settledEdits(event: String): Pair<JsonObject, List<ChangeRevert.Edit>> {
        val root = Json.parseToJsonElement(event).jsonObject
        assertEquals(UndiffedEdits.SETTLED_TYPE, root["type"]!!.jsonPrimitive.content)
        val file = root["files"]!!.jsonArray.single().jsonObject
        return file to ChangeRevert.requestsOf(root["files"]).single().edits
    }

    @Test
    fun `a created file a formatter rewrote after the Write is settled as it stands, and reverts by deleting it`() {
        val config = Files.createTempDirectory("claude-home").toFile()
        val file = File(Files.createTempDirectory("project").toFile(), "style.css")
        val written = "a{color:red}\nb{color:blue}\n"
        val formatted = "a {\n  color: red;\n}\n\nb {\n  color: blue;\n}\n"
        val (undiffed) = undiffed(config)

        undiffed.fill(writeCall("w1", file.path, written), "conv-1")
        file.writeText(formatted)
        undiffed.fill(answerLine("w1", file.path, created(written)), "conv-1")

        val (settled, edits) = settledEdits(assertNotNull(undiffed.settle("conv-1")))
        assertEquals("7", settled["added"]!!.jsonPrimitive.content)
        assertEquals("0", settled["removed"]!!.jsonPrimitive.content)
        assertEquals(ChangeRevert.Result.Delete, ChangeRevert.revert(formatted, edits))
        assertEquals(ChangeRevert.Result.Text(formatted), ChangeRevert.redo(null, edits))
        // Touched by hand since, it is somebody's file and stays.
        assertEquals(ChangeRevert.Result.Changed, ChangeRevert.revert(formatted + "c {}\n", edits))

        // And the turn is over: nothing is settled twice.
        assertNull(undiffed.settle("conv-1"))
    }

    /** The CLI writes more into a memory file than the call did - the panel holds the file to the call's text. */
    @Test
    fun `a created file the CLI wrote more into than the call is settled by the call's text`() {
        val config = Files.createTempDirectory("claude-home").toFile()
        val file = File(Files.createTempDirectory("memory").toFile(), "note.md")
        val written = "---\nname: note\n---\n\nThe fact.\n"
        val onDisk = "---\nname: note\nmetadata:\n  originSessionId: conv-1\n---\n\nThe fact.\n"
        val (undiffed) = undiffed(config)

        undiffed.fill(writeCall("w1", file.path, written), "conv-1")
        file.writeText(onDisk)
        undiffed.fill(answerLine("w1", file.path, created(onDisk)), "conv-1")

        val (_, edits) = settledEdits(assertNotNull(undiffed.settle("conv-1")))
        assertEquals(ChangeRevert.Result.Delete, ChangeRevert.revert(onDisk, edits))
    }

    @Test
    fun `an edited file the agent's script changed besides is settled from the CLI's copy, and reverts to it`() {
        val config = Files.createTempDirectory("claude-home").toFile()
        val file = File(Files.createTempDirectory("project").toFile(), "app.js")
        val before = (1..20).joinToString("") { "line $it\n" }
        val afterEdit = before.replace("line 2\n", "two\n")
        // A sed in the same turn, where the edit's own lines stand too.
        val left = afterEdit.replace("two\n", "TWO\n").replace("line 15\n", "fifteen\n")
        val (undiffed, transcript) = undiffed(config)
        copied(config, transcript, file, 1, before)

        file.writeText(afterEdit)
        undiffed.fill(answerLine("e1", file.path, edited(" line 1", "-line 2", "+two", " line 3", " line 4", " line 5")), "conv-1")
        file.writeText(left)

        // By its own diff the edit's line is gone: that was "changed since".
        val reported = listOf(ChangeRevert.Edit.Patch(listOf(ChangeRevert.Hunk(1, listOf(" line 1", "-line 2", "+two", " line 3", " line 4", " line 5")))))
        assertEquals(ChangeRevert.Result.Changed, ChangeRevert.revert(left, reported))

        val (settled, edits) = settledEdits(assertNotNull(undiffed.settle("conv-1")))
        assertEquals("2", settled["added"]!!.jsonPrimitive.content)
        assertEquals("2", settled["removed"]!!.jsonPrimitive.content)
        assertEquals(ChangeRevert.Result.Text(before), ChangeRevert.revert(left, edits))
        // A line somebody added since elsewhere in the file stays where it is.
        val touched = left.replace("line 19\n", "line 19\nmine\n")
        assertEquals(ChangeRevert.Result.Text(before.replace("line 19\n", "line 19\nmine\n")), ChangeRevert.revert(touched, edits))
    }

    @Test
    fun `a file that stands as its diffs say is not settled`() {
        val config = Files.createTempDirectory("claude-home").toFile()
        val project = Files.createTempDirectory("project").toFile()
        val made = File(project, "new.txt")
        val edited = File(project, "old.txt")
        val (undiffed, transcript) = undiffed(config)
        copied(config, transcript, edited, 1, "a\nb\n")

        undiffed.fill(writeCall("w1", made.path, "x\n"), "conv-1")
        made.writeText("x\n")
        undiffed.fill(answerLine("w1", made.path, created("x\n")), "conv-1")
        edited.writeText("a\nB\n")
        undiffed.fill(answerLine("e1", edited.path, edited(" a", "-b", "+B")), "conv-1")

        assertNull(undiffed.settle("conv-1"))
    }

    /** A copy from before this turn would put back more than the turn did: the file is left to its own diffs. */
    @Test
    fun `a copy that is not the file the turn's first edit found settles nothing`() {
        val config = Files.createTempDirectory("claude-home").toFile()
        val file = File(Files.createTempDirectory("project").toFile(), "a.txt")
        val (undiffed, transcript) = undiffed(config)
        copied(config, transcript, file, 1, "long ago\n")

        file.writeText("a\nB\n")
        undiffed.fill(answerLine("e1", file.path, edited(" a", "-b", "+B")), "conv-1")
        file.writeText("a\nB\nformatted\n")

        assertNull(undiffed.settle("conv-1"))
    }

    /**
     * The CLI copies a file at a person's prompt, and a turn that starts without one - a task's notification - gets
     * no copy. The newest copy on the disk is then the file from before whatever changed it since: here an agent's
     * `sed` in a turn of its own. A diff from it would take that work along into this turn's revert: the file is left
     * to its own diffs instead, as is an edit with none.
     */
    @Test
    fun `a turn the CLI took no copy for is not settled from an older one`() {
        val config = Files.createTempDirectory("claude-home").toFile()
        val file = File(Files.createTempDirectory("project").toFile(), "app.js")
        val before = (1..300).joinToString("") { "line $it\n" }
        val (undiffed, transcript) = undiffed(config)

        // A prompt: the CLI copies the file, the turn changes line 10 and stands as its diff says.
        copied(config, transcript, file, 1, before)
        val first = before.replace("line 10\n", "ten\n")
        file.writeText(first)
        undiffed.fill(answerLine("e1", file.path, edited(" line 9", "-line 10", "+ten", " line 11", start = 9)), "conv-1")
        assertNull(undiffed.settle("conv-1"))

        // The next prompt: copied again, and the agent changes line 50 by a script, which reports no diff.
        copied(config, transcript, file, 2, first)
        val second = first.replace("line 50\n", "fifty\n")
        file.writeText(second)
        assertNull(undiffed.settle("conv-1"))

        // A task's notification starts the next: no copy. Line 200 by an edit, line 250 by a formatter besides.
        file.writeText(second.replace("line 200\n", "two hundred\n"))
        undiffed.fill(answerLine("e2", file.path, edited(" line 199", "-line 200", "+two hundred", " line 201", start = 199)), "conv-1")
        file.writeText(file.readText().replace("line 250\n", "formatted\n"))
        assertNull(undiffed.settle("conv-1"))
        // A big edit of such a turn is not worked out from an older copy either.
        undiffed.fill(answerLine("e3", file.path, edited(" line 299", "-line 300", "+end", start = 299)), "conv-1")
        assertEquals(resultLine(file.path), undiffed.fill(resultLine(file.path), "conv-1"))
        undiffed.settle("conv-1")

        // A turn the CLI did copy the file for is settled again, from that copy: the turns before it stay.
        val third = file.readText()
        copied(config, transcript, file, 3, third)
        file.writeText(third.replace("line 100\n", "hundred\n"))
        undiffed.fill(answerLine("e4", file.path, edited(" line 99", "-line 100", "+hundred", " line 101", start = 99)), "conv-1")
        file.writeText(file.readText().replace("line 120\n", "formatted\n"))
        val (_, edits) = settledEdits(assertNotNull(undiffed.settle("conv-1")))
        assertEquals(ChangeRevert.Result.Text(third), ChangeRevert.revert(file.readText(), edits))
    }

    /**
     * The copy is the one the transcript names: a file the CLI found as it was kept is not copied again, and the
     * newest copy on the disk may be one from a stretch a rewind cut off.
     */
    @Test
    fun `a turn is settled from the copy the CLI named for it, not the newest on the disk`() {
        val config = Files.createTempDirectory("claude-home").toFile()
        val file = File(Files.createTempDirectory("project").toFile(), "app.js")
        val before = (1..30).joinToString("") { "line $it\n" }
        val (undiffed, transcript) = undiffed(config)
        copied(config, transcript, file, 1, before)
        File(config, "file-history/conv-1/${sha(file.path)}@v2").writeText(before.replace("line 3\n", "cut off\n"))

        file.writeText(before.replace("line 20\n", "twenty\n"))
        undiffed.fill(answerLine("e1", file.path, edited(" line 19", "-line 20", "+twenty", " line 21", start = 19)), "conv-1")
        val left = file.readText().replace("line 25\n", "formatted\n")
        file.writeText(left)

        val (_, edits) = settledEdits(assertNotNull(undiffed.settle("conv-1")))
        assertEquals(ChangeRevert.Result.Text(before), ChangeRevert.revert(left, edits))
    }

    /**
     * The CLI writes its record of a copy down every hundred milliseconds and sends the edit's result without waiting:
     * a big edit of a file it has just begun to track arrives before the record of its copy. The row has no diff then,
     * and the turn's end works the file out from the copy, written down by that time.
     */
    @Test
    fun `a big edit whose copy is written down after it is settled at the turn's end`() {
        val config = Files.createTempDirectory("claude-home").toFile()
        val file = File(Files.createTempDirectory("project").toFile(), "big.js")
        val before = (1..500).joinToString("") { "x = foo($it)\n" }
        val (undiffed, transcript) = undiffed(config)
        file.writeText(before.replace("foo(", "bar("))

        assertEquals(resultLine(file.path), undiffed.fill(resultLine(file.path), "conv-1"))
        copied(config, transcript, file, 1, before)

        val (settled, edits) = settledEdits(assertNotNull(undiffed.settle("conv-1")))
        assertEquals("500", settled["added"]!!.jsonPrimitive.content)
        assertEquals(ChangeRevert.Result.Text(before), ChangeRevert.revert(file.readText(), edits))
    }

    /** A turn over with no result - stopped, cut back by a rewind - leaves nothing of itself to the next. */
    @Test
    fun `a turn over without a result leaves its copies to nobody`() {
        val config = Files.createTempDirectory("claude-home").toFile()
        val file = File(Files.createTempDirectory("project").toFile(), "app.js")
        val before = (1..30).joinToString("") { "line $it\n" }
        val (undiffed, transcript) = undiffed(config)
        copied(config, transcript, file, 1, before)
        file.writeText(before.replace("line 10\n", "ten\n"))
        undiffed.fill(answerLine("e1", file.path, edited(" line 9", "-line 10", "+ten", " line 11", start = 9)), "conv-1")
        undiffed.turnOver("conv-1")

        // A notification starts the next, with no copy of its own.
        file.writeText(file.readText().replace("line 20\n", "twenty\n"))
        undiffed.fill(answerLine("e2", file.path, edited(" line 19", "-line 20", "+twenty", " line 21", start = 19)), "conv-1")
        file.writeText(file.readText().replace("line 25\n", "formatted\n"))
        assertNull(undiffed.settle("conv-1"))
    }

    /** A fork is handed its source's records as its first turn begins, and they may land after the turn's own. */
    @Test
    fun `of two records of one file the later one counts, wherever it was written`() {
        val config = Files.createTempDirectory("claude-home").toFile()
        val file = File(Files.createTempDirectory("project").toFile(), "app.js")
        val before = (1..30).joinToString("") { "line $it\n" }
        val (undiffed, transcript) = undiffed(config)
        copied(config, transcript, file, 2, before, at = "2026-10-15T10:00:00.000Z")
        copied(config, transcript, file, 1, "the source's, long ago\n", at = "2026-10-01T10:00:00.000Z")

        file.writeText(before.replace("line 20\n", "twenty\n"))
        undiffed.fill(answerLine("e1", file.path, edited(" line 19", "-line 20", "+twenty", " line 21", start = 19)), "conv-1")
        val left = file.readText().replace("line 25\n", "formatted\n")
        file.writeText(left)

        val (_, edits) = settledEdits(assertNotNull(undiffed.settle("conv-1")))
        assertEquals(ChangeRevert.Result.Text(before), ChangeRevert.revert(left, edits))
    }

    @Test
    fun `the phone is sent a settled file's figures without its edits`() {
        val event = """{"type":"agent","sessionId":"s","event":{"type":"${UndiffedEdits.SETTLED_TYPE}","files":[""" +
            """{"path":"/p/a","added":3,"removed":0,"edits":[{"kind":"create","content":""}]}]}}"""
        val file = Json.parseToJsonElement(assertNotNull(UndiffedEdits.lightened(event))).jsonObject["event"]!!
            .jsonObject["files"]!!.jsonArray.single().jsonObject
        assertEquals("3", file["added"]!!.jsonPrimitive.content)
        assertTrue("edits" !in file)
    }
}
