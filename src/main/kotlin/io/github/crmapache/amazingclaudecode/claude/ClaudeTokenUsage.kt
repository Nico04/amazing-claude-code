package io.github.crmapache.amazingclaudecode.claude

import com.intellij.openapi.diagnostic.thisLogger
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * How many tokens the CLI has spent, read straight out of its transcripts - every project at once, the
 * same "tok" figure a user has in their personal ~/.claude/statusline.sh. A conversation cannot be asked
 * for this number: it knows only its own context, not every project and not the day's history - so, as
 * statusline.sh does, we read the transcripts directly.
 *
 * Subagents keep transcripts of their own in the same folder, and so does every scenario run, so their
 * work is counted too - which the turns the panel watches cannot do (see stats/TranscriptTokens).
 *
 * Deduplicated by (message.id, requestId): one and the same answer occurs many times over in the
 * transcripts. Mostly inside one file - the CLI writes a line per block of a streamed answer, each with
 * the answer's usage as it stood at that moment - and now and then across files (resumes, compactions,
 * copies inside subagents). Without the deduplication the total would come out several times over.
 */
internal object ClaudeTokenUsage {

    private const val MAX_DEPTH = 8

    private const val HOUR_MS = 60L * 60 * 1000

    /**
     * Threads for one reading. A first reading goes through every transcript there is - gigabytes on a
     * machine in daily use, a minute on one thread while the IDE is still starting - and the files are
     * independent of each other. Half the cores and no more than four, at the lowest priority: it runs
     * while somebody works in the editor.
     */
    private val THREADS = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)

    /**
     * One answer as the copies of it found so far say: the earliest time any of them carries, and the
     * largest usage. The lines of a streamed answer carry its usage as it stood when each was written, so
     * the first of them is short of most of the output; the last one is the answer's whole cost. Both
     * are taken the same way whichever copy turns up first, so a reading spread over threads comes out
     * the same as one on a single thread.
     *
     * The directory goes with the earliest copy, and on a tie with the smaller name - by the same rule:
     * a copy of a conversation resumed elsewhere must not move its answers to another project depending
     * on which file a thread happened to open first.
     */
    private class Answer(val at: Long, val tokens: Long, val directory: String) {
        fun with(other: Answer): Answer {
            val first = when {
                at != other.at -> if (at < other.at) this else other
                else -> if (directory <= other.directory) this else other
            }
            return Answer(first.at, maxOf(tokens, other.tokens), first.directory)
        }
    }

    /**
     * What one reading found: the tokens by the hour, and the same tokens again by the directory the CLI
     * was working in when it spent them - the `cwd` every line of a transcript carries, in the CLI's own
     * spelling of it (a Linux path inside WSL). Which project a directory belongs to is the statistics'
     * business, not the reader's (see stats/TranscriptTokens).
     */
    class Reading(
        /** Hour since the epoch -> tokens, every directory together. */
        val hours: Map<Long, Long>,
        /** Directory -> hour since the epoch -> tokens. An answer without a directory is in [hours] only. */
        val directories: Map<String, Map<Long, Long>>,
    )

    /**
     * The tokens of every transcript under [root] changed at or after [since] (epoch millis, zero for all
     * of them), by the hour each answer was written in - an hour counted from the epoch, so that the
     * figure means the same in every time zone (see stats/TokenHome) - and by the directory it was spent
     * in. Null if the folder could not be walked at all; a single file that could not be read is left out
     * and logged, the rest still count.
     */
    fun read(root: File, since: Long): Reading? {
        val files = runCatching {
            root.walkTopDown()
                .maxDepth(MAX_DEPTH)
                .filter { it.isFile && it.extension == "jsonl" && it.lastModified() >= since }
                // The biggest first: a big file started last would leave one thread reading it alone.
                .sortedByDescending { it.length() }
                .toList()
        }.onFailure { thisLogger().warn("Failed to scan projects for token usage", it) }.getOrNull() ?: return null

        // By a hash of the pair rather than the ids themselves: a first reading meets hundreds of thousands
        // of answers, and a map of their keys as strings would be a hundred megabytes.
        val answers = ConcurrentHashMap<Long, Answer>()
        val pool = Executors.newFixedThreadPool(THREADS) { task ->
            Thread(task, "Amazing Claude Code token reading").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }
        val finished = runCatching {
            files.map { file -> pool.submit(Runnable { scanFile(file, answers) }) }.forEach { it.get() }
        }
        pool.shutdownNow()
        finished.onFailure {
            thisLogger().warn("The token reading was cut short", it)
            return null
        }

        val hours = HashMap<Long, Long>()
        val directories = HashMap<String, HashMap<Long, Long>>()
        for (answer in answers.values) {
            val hour = Math.floorDiv(answer.at, HOUR_MS)
            hours.merge(hour, answer.tokens, Long::plus)
            if (answer.directory.isNotEmpty()) {
                directories.getOrPut(answer.directory) { HashMap() }.merge(hour, answer.tokens, Long::plus)
            }
        }
        return Reading(hours, directories)
    }

    /**
     * "445.5M" - the figure by the composer. A fixed locale: in an Arabic or Devanagari one the digits
     * themselves would come out in another script.
     */
    fun millions(tokens: Long): String = String.format(Locale.ROOT, "%.1fM", tokens / 1_000_000.0)

    private fun scanFile(file: File, answers: ConcurrentHashMap<Long, Answer>) {
        // One string per directory per file rather than one per line: a first reading keeps hundreds of
        // thousands of answers, and nearly all of a file's answers were spent in the same directory.
        val directories = HashMap<String, String>()
        runCatching {
            file.useLines { lines ->
                for (line in lines) {
                    // Most of a transcript's bytes are tool output; only an answer carries a usage.
                    if (!line.startsWith("{") || !line.contains("\"usage\"")) continue
                    countLine(line, answers, directories)
                }
            }
        }.onFailure { thisLogger().warn("Failed to scan $file for token usage", it) }
    }

    private fun countLine(
        line: String,
        answers: ConcurrentHashMap<Long, Answer>,
        directories: HashMap<String, String>,
    ) {
        val payload = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return
        val message = payload["message"] as? JsonObject ?: return
        val usage = message["usage"] as? JsonObject ?: return

        val timestamp = payload["timestamp"]?.jsonPrimitive?.contentOrNull ?: return
        val at = runCatching { Instant.parse(timestamp).toEpochMilli() }.getOrNull() ?: return

        val tokens = field(usage, "input_tokens") +
            field(usage, "output_tokens") +
            field(usage, "cache_creation_input_tokens") +
            field(usage, "cache_read_input_tokens")
        if (tokens <= 0) return

        val messageId = message["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val requestId = payload["requestId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val key = (messageId.hashCode().toLong() shl 32) or (requestId.hashCode().toLong() and 0xffffffffL)
        val directory = payload["cwd"]?.jsonPrimitive?.contentOrNull.orEmpty().let { directories.getOrPut(it) { it } }
        answers.merge(key, Answer(at, tokens, directory), Answer::with)
    }

    private fun field(usage: JsonObject, name: String): Long =
        usage[name]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
}
