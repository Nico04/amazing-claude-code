package io.github.crmapache.amazingclaudecode.stats

import com.intellij.openapi.util.Disposer
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TranscriptTokensTest {

    private val directory = Files.createTempDirectory("acc-tokens")

    private val ledger = StatsLedger(directory.resolve("statistics.json"))

    /** The CLI's folder of transcripts: a project folder in it, and a subagent's folder under a conversation. */
    private val root = directory.resolve("projects").toFile().apply { mkdirs() }

    private val zone = ZoneId.of("UTC")

    private val now = Instant.parse("2026-10-16T12:00:00Z").toEpochMilli()

    private val hour = 60L * 60 * 1000

    @AfterTest
    fun tearDown() {
        Disposer.dispose(ledger)
        directory.toFile().deleteRecursively()
    }

    private fun answer(
        at: String,
        id: String,
        request: String = "req-$id",
        tokens: Int = 100,
        cwd: String? = null,
    ): String =
        """{"type":"assistant",""" + (cwd?.let { """"cwd":"$it",""" } ?: "") +
            """"timestamp":"$at","requestId":"$request","message":{"id":"$id","usage":""" +
            """{"input_tokens":${tokens / 4},"output_tokens":${tokens / 4},"cache_read_input_tokens":${tokens / 4},""" +
            """"cache_creation_input_tokens":${tokens / 4}}}}"""

    /** A transcript with these lines, last changed at [changed]. */
    private fun transcript(path: String, changed: Long, vararg lines: String): File {
        val file = File(root, path)
        file.parentFile.mkdirs()
        file.writeText(lines.joinToString("\n", postfix = "\n"))
        file.setLastModified(changed)
        return file
    }

    private fun hours(): Map<Long, Long> =
        ledger.read { HashMap(it.tokens[TranscriptTokens.keyOf(root)]?.hours ?: emptyMap()) }

    private fun hourOf(at: String): Long = Instant.parse(at).toEpochMilli() / hour

    @Test
    fun `the first reading goes through every transcript and counts all four kinds once`() {
        transcript(
            "-work-app/a.jsonl",
            now - 40 * 24 * hour,
            answer("2026-09-06T08:10:00Z", "m1"),
            """{"type":"user","message":{"content":"no usage here"}}""",
        )
        transcript(
            "-work-app/b.jsonl",
            now,
            answer("2026-10-16T09:05:00Z", "m2", tokens = 400),
            answer("2026-10-16T09:50:00Z", "m3", tokens = 200),
            // The same answer again - a resumed conversation carries its earlier answers along.
            answer("2026-10-16T09:05:00Z", "m2", tokens = 400),
        )
        transcript("-work-app/b/subagents/agent-1.jsonl", now, answer("2026-10-16T11:30:00Z", "m4", tokens = 40))

        val today = TranscriptTokens.refresh(root, ledger, zone, now)

        assertEquals(640L, today)
        assertEquals(100L, hours()[hourOf("2026-09-06T08:00:00Z")])
        assertEquals(600L, hours()[hourOf("2026-10-16T09:00:00Z")])
        assertEquals(40L, hours()[hourOf("2026-10-16T11:00:00Z")])
        assertEquals(now, ledger.read { it.tokens[TranscriptTokens.keyOf(root)]!!.readAt })
    }

    @Test
    fun `a streamed answer counts once, at its whole cost, in the hour it began`() {
        // A line per block of the answer, each with the usage as it stood when it was written: the output
        // keeps growing, and the last line crosses into the next hour.
        transcript(
            "-work-app/a.jsonl",
            now,
            answer("2026-10-16T09:58:00Z", "m1", tokens = 100),
            answer("2026-10-16T09:59:00Z", "m1", tokens = 160),
            answer("2026-10-16T10:01:00Z", "m1", tokens = 240),
        )

        TranscriptTokens.refresh(root, ledger, zone, now)

        assertEquals(240L, hours()[hourOf("2026-10-16T09:00:00Z")])
        assertNull(hours()[hourOf("2026-10-16T10:00:00Z")])
    }

    @Test
    fun `a later reading opens only what changed since the last one`() {
        val old = transcript("-work-app/old.jsonl", now - 10 * 24 * hour, answer("2026-10-06T10:00:00Z", "m1"))
        TranscriptTokens.refresh(root, ledger, zone, now)

        // Rewritten behind the reading's back, with its time left alone: a reading that opened it again
        // would count 900 for that hour.
        old.writeText(answer("2026-10-06T10:00:00Z", "m1", tokens = 900) + "\n")
        old.setLastModified(now - 10 * 24 * hour)
        transcript("-work-app/new.jsonl", now + hour, answer("2026-10-16T12:30:00Z", "m2", tokens = 80))

        val today = TranscriptTokens.refresh(root, ledger, zone, now + hour)

        assertEquals(80L, today)
        assertEquals(100L, hours()[hourOf("2026-10-06T10:00:00Z")])
        assertEquals(now + hour, ledger.read { it.tokens[TranscriptTokens.keyOf(root)]!!.readAt })
    }

    @Test
    fun `an old hour brought along by a resumed conversation is not counted down`() {
        transcript(
            "-work-app/old.jsonl",
            now - 10 * 24 * hour,
            answer("2026-10-06T10:00:00Z", "m1"),
            answer("2026-10-06T10:20:00Z", "m2"),
        )
        TranscriptTokens.refresh(root, ledger, zone, now)

        // Only one of the two answers made it into the new file - the reading sees half of the hour.
        transcript("-work-app/resumed.jsonl", now + hour, answer("2026-10-06T10:00:00Z", "m1"))
        TranscriptTokens.refresh(root, ledger, zone, now + hour)

        assertEquals(200L, hours()[hourOf("2026-10-06T10:00:00Z")])
    }

    @Test
    fun `without a folder the book answers for today and nothing is read`() {
        val missing = directory.resolve("nowhere").toFile()

        assertEquals(0L, TranscriptTokens.refresh(missing, ledger, zone, now))
        assertNull(ledger.read { it.tokens[TranscriptTokens.keyOf(missing)] })
    }

    private fun projects(): Map<String, TokenProject> =
        ledger.read { HashMap(it.tokens[TranscriptTokens.keyOf(root)]?.projects ?: emptyMap()) }

    @Test
    fun `the tokens go to the project the directory lies in`() {
        val work = directory.resolve("work").toFile()
        // Opened in the IDE: the book knows it under the key StatsCollector gives its path.
        val app = File(work, "app").apply { mkdirs() }
        ledger.update { it.project(StatsCollector.keyOf(app.path), "app") }
        // Never opened, but a repository - a worktree's .git is a file, and that counts the same.
        val tool = File(work, "tool").apply { mkdirs() }
        File(tool, ".git").writeText("gitdir: elsewhere")
        File(tool, "scripts").mkdirs()
        // Neither: a folder of its own.
        val scratch = File(work, "scratch").apply { mkdirs() }

        transcript(
            "-work-app/a.jsonl",
            now,
            answer("2026-10-16T09:05:00Z", "m1", tokens = 400, cwd = app.path),
            answer("2026-10-16T09:10:00Z", "m2", tokens = 40, cwd = "${app.path}/webview/src"),
        )
        transcript(
            "-work-tool/b.jsonl",
            now,
            answer("2026-10-16T10:05:00Z", "m3", tokens = 80, cwd = "${tool.path}/scripts"),
        )
        transcript("-work-scratch/c.jsonl", now, answer("2026-10-16T11:05:00Z", "m4", tokens = 8, cwd = scratch.path))
        // A line with no directory still counts in the day, just not for any project.
        transcript("-work-app/d.jsonl", now, answer("2026-10-16T11:20:00Z", "m5", tokens = 4))

        TranscriptTokens.refresh(root, ledger, zone, now)

        val projects = projects()
        val appTokens = projects[StatsCollector.keyOf(app.path)]
        assertEquals("app", appTokens?.name)
        assertEquals(mapOf(hourOf("2026-10-16T09:00:00Z") to 440L), appTokens?.hours?.toMap())
        assertEquals("tool", projects[StatsCollector.keyOf(tool.path)]?.name)
        assertEquals(80L, projects[StatsCollector.keyOf(tool.path)]?.hours?.values?.sum())
        assertEquals("scratch", projects[StatsCollector.keyOf(scratch.path)]?.name)
        assertEquals(3, projects.size)
        assertEquals(532L, hours().values.sum())
    }

    @Test
    fun `a book kept before the projects reads every transcript again`() {
        val app = directory.resolve("app").toFile().apply { mkdirs() }
        transcript("-app/old.jsonl", now - 20 * 24 * hour, answer("2026-09-26T10:00:00Z", "m1", cwd = app.path))
        transcript("-app/new.jsonl", now, answer("2026-10-16T10:00:00Z", "m2", cwd = app.path))
        // Read through yesterday by a plugin that knew nothing of projects: the totals are there, the
        // projects are not - and the reading time says nothing about them.
        ledger.update { book ->
            book.tokens[TranscriptTokens.keyOf(root)] = TokenHome().apply {
                record(mapOf(hourOf("2026-09-26T10:00:00Z") to 100L), now - 24 * hour)
            }
        }

        TranscriptTokens.refresh(root, ledger, zone, now)

        val tokens = projects()[StatsCollector.keyOf(app.path)]
        assertEquals(
            mapOf(hourOf("2026-09-26T10:00:00Z") to 100L, hourOf("2026-10-16T10:00:00Z") to 100L),
            tokens?.hours?.toMap(),
        )
        assertEquals(now, ledger.read { it.tokens[TranscriptTokens.keyOf(root)]!!.projectsReadAt })

        // From then on only what changed: the old file rewritten behind the reading's back is not opened.
        File(root, "-app/old.jsonl").apply {
            writeText(answer("2026-09-26T10:00:00Z", "m1", tokens = 900, cwd = app.path) + "\n")
            setLastModified(now - 20 * 24 * hour)
        }
        TranscriptTokens.refresh(root, ledger, zone, now + hour)
        assertEquals(100L, projects()[StatsCollector.keyOf(app.path)]?.hours?.get(hourOf("2026-09-26T10:00:00Z")))
    }
}
