package io.github.crmapache.amazingclaudecode.editor

import io.github.crmapache.amazingclaudecode.claude.ClaudeHome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        File(config, "file-history/conv-1").apply { mkdirs() }.let { folder ->
            File(folder, "${sha(file.path)}@v1").writeText("old copy\n")
            File(folder, "${sha(file.path)}@v2").writeText(before)
        }

        val undiffed = UndiffedEdits { home(config) }
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
        val undiffed = UndiffedEdits { home(config) }

        val reported = resultLine(file.path, """[{"oldStart":1,"oldLines":1,"newStart":1,"newLines":1,"lines":["-a","+b"]}]""")
        assertSame(reported, undiffed.fill(reported, "conv-1"))

        val noCopy = resultLine(file.path)
        assertSame(noCopy, undiffed.fill(noCopy, "conv-1"))
        assertNull(UndiffedEdits.lightened(noCopy))
    }
}
