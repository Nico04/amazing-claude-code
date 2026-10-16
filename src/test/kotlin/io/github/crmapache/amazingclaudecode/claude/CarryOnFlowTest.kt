package io.github.crmapache.amazingclaudecode.claude

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The panel's own "carry on" through the real hub, with a live process under the tab.
 *
 * The process is a stand-in for the CLI that stays up and reads what it is sent (see [fakeCli]); what the CLI
 * would say back is fed to the conversation by hand, line by line, the way its stream delivers it. What is
 * checked is what every client is told: the messages that went into the conversation, in which order, and the
 * plan for after a limit.
 */
class CarryOnFlowTest : BasePlatformTestCase() {

    private class Recorder(override val id: String) : SessionClient {
        val received = CopyOnWriteArrayList<String>()

        override fun deliver(messages: List<String>) {
            received += messages
        }
    }

    private lateinit var script: File
    private var executableBefore = ""

    /**
     * A tab of this test's own. The light project, and with it the hub and its journals, outlives a test: a tab
     * name shared between tests hands every new client the messages of the test before.
     */
    private val TAB = "carry-on-${UUID.randomUUID()}"

    override fun setUp() {
        super.setUp()
        // The hub outlives a test and launches in the project's folder, which a test before may have removed: a
        // process that cannot start ends its turn at once, and everything after it would be about that.
        project.basePath?.let { File(it).mkdirs() }
        executableBefore = ClaudePreferences.executablePath
        script = fakeCli()
        ClaudePreferences.executablePath = script.absolutePath
    }

    override fun tearDown() {
        try {
            // Takes the stand-in process down with the tab.
            runCatching { ClaudeSessionHub.getInstance(project).closeSession(TAB) }
            ClaudePreferences.executablePath = executableBefore
            script.delete()
        } finally {
            super.tearDown()
        }
    }

    /**
     * Stays up reading its input when launched as a conversation, answers anything else (a look at `--help`)
     * at once - a probe left waiting would hold every launch up by its timeout.
     */
    private fun fakeCli(): File = File.createTempFile("fake-claude", ".sh").apply {
        writeText(
            """
            #!/bin/sh
            for a in "${'$'}@"; do [ "${'$'}a" = "--input-format" ] && exec cat > /dev/null; done
            echo "--setting-sources --input-format"
            """.trimIndent() + "\n",
        )
        setExecutable(true)
    }

    private fun hub(recorder: Recorder): ClaudeSessionHub = ClaudeSessionHub.getInstance(project).also {
        it.register(recorder)
        it.attach(recorder.id)
    }

    /** A message queued the way the panel queues one - with the pieces its card is drawn from. */
    private fun queue(hub: ClaudeSessionHub, text: String) {
        val echo = buildJsonObject {
            putJsonArray("tokens") {
                addJsonObject {
                    put("kind", "text")
                    put("value", text)
                }
            }
            putJsonArray("quotes") {}
        }
        hub.queuePrompt(TAB, UUID.randomUUID().toString(), text, echo = echo)
    }

    private fun feed(hub: ClaudeSessionHub, line: String) {
        hub.conversations.conversation(TAB)!!.heard(line + "\n")
    }

    /** The messages that went into the conversation, as every client saw them - their words, in order. */
    private fun said(recorder: Recorder): List<String> =
        recorder.received.filter { it.contains("\"type\":\"promptEcho\"") && it.contains("\"sessionId\":\"$TAB\"") }

    private fun waits(recorder: Recorder): List<String> =
        recorder.received.filter { it.contains("\"type\":\"carryOnWait\"") }

    /**
     * A turn under way, refused by the five-hour limit: the conversation has a name, and the process stands
     * idle on the refusal - the state every test here starts from.
     */
    private fun refusedTurn(hub: ClaudeSessionHub, resetsInSeconds: Long = 600) {
        hub.prompt(TAB, "Move the totals onto the new money type")
        feed(hub, """{"type":"system","subtype":"init","session_id":"conv-1","model":"claude-opus-5"}""")
        val resetsAt = System.currentTimeMillis() / 1000 + resetsInSeconds
        feed(
            hub,
            """{"type":"rate_limit_event","rate_limit_info":{"status":"rejected","rateLimitType":"five_hour","resetsAt":$resetsAt}}""",
        )
        feed(
            hub,
            """{"type":"assistant","message":{"model":"<synthetic>","content":[{"type":"text","text":"You've hit your session limit"}]},"error":"rate_limit"}""",
        )
        feed(hub, """{"type":"result","subtype":"success","is_error":true,"result":"You've hit your session limit","duration_ms":10}""")
    }

    /**
     * The limit refused the turn: every screen is told when the panel carries on, the queue waits behind it, and
     * at the reset the panel's "carry on" goes first and alone - the queued message after the turn it starts.
     */
    fun testALimitIsWaitedOutAndTheCarryOnGoesBeforeTheQueue() {
        val recorder = Recorder("desk")
        val hub = hub(recorder)

        refusedTurn(hub)

        val planned = hub.conversations.carryOnOf(TAB)
        assertEquals(CarryOns.Reason.LIMIT, planned?.reason)
        assertTrue(waits(recorder).joinToString(), waits(recorder).last().contains("\"until\":${planned!!.notBefore}"))

        queue(hub, "and then the invoices")
        // Held behind the limit: refused like the turn before it, it would only spend a request.
        assertEquals(said(recorder).joinToString("\n"), 0, said(recorder).size)

        hub.runQueued(TAB, now = planned.notBefore!!)

        assertEquals(said(recorder).joinToString("\n"), 1, said(recorder).size)
        assertTrue(said(recorder).last(), said(recorder).last().contains("\"auto\":\"limit\""))
        // The plan is spent and the screens are told so.
        assertFalse(waits(recorder).last(), waits(recorder).last().contains("\"until\""))

        // The carry-on's own turn goes through, and only then does the queue speak.
        feed(hub, """{"type":"result","subtype":"success","is_error":false,"result":"done","duration_ms":10}""")

        assertEquals(2, said(recorder).size)
        assertTrue(said(recorder).last(), said(recorder).last().contains("and then the invoices"))
    }

    /**
     * "Don't carry on" takes the panel's words away and leaves the wait: what is queued still goes at the
     * reset, with nothing said before it. It used to stand in the queue past the reset for good.
     */
    fun testSayingNoStillSendsTheQueueAtTheReset() {
        val recorder = Recorder("desk")
        val hub = hub(recorder)

        refusedTurn(hub)
        val at = hub.conversations.carryOnOf(TAB)!!.notBefore!!
        queue(hub, "and then the invoices")

        hub.cancelCarryOn(TAB)
        // No plan on the screens any more.
        assertFalse(waits(recorder).last(), waits(recorder).last().contains("\"until\""))
        // And still nothing before the reset.
        hub.runQueued(TAB)
        assertEquals(said(recorder).joinToString("\n"), 0, said(recorder).size)

        hub.runQueued(TAB, now = at)

        assertEquals(1, said(recorder).size)
        assertTrue(said(recorder).last(), said(recorder).last().contains("and then the invoices"))
        assertFalse(said(recorder).last(), said(recorder).last().contains("\"auto\""))
    }

    /** The person writing in the meantime is the next word: nothing of the panel's is said after it. */
    fun testThePersonsOwnMessageDropsTheWait() {
        val recorder = Recorder("desk")
        val hub = hub(recorder)

        refusedTurn(hub)
        val at = hub.conversations.carryOnOf(TAB)!!.notBefore!!

        hub.prompt(TAB, "never mind, do the invoices")
        assertNull(hub.conversations.carryOnOf(TAB))
        assertFalse(waits(recorder).last(), waits(recorder).last().contains("\"until\""))

        feed(hub, """{"type":"result","subtype":"success","is_error":false,"result":"done","duration_ms":10}""")
        hub.runQueued(TAB, now = at)

        assertTrue(said(recorder).none { it.contains("\"auto\"") })
    }

    /**
     * Two passes at the moment the wait ends - its timer and the minute round - with a message queued: one
     * message goes, the carry-on, and the queued one waits for the turn it starts. Both used to go, the second
     * into the turn the first had just begun.
     */
    fun testTwoPassesAtOnceSayOneThing() {
        val recorder = Recorder("desk")
        val hub = hub(recorder)

        refusedTurn(hub)
        val at = hub.conversations.carryOnOf(TAB)!!.notBefore!!
        queue(hub, "and then the invoices")

        val start = CyclicBarrier(2)
        List(2) { thread { start.await(); hub.runQueued(TAB, now = at) } }.forEach { it.join(10_000) }

        assertEquals(said(recorder).joinToString("\n"), 1, said(recorder).size)
        assertTrue(said(recorder).last(), said(recorder).last().contains("\"auto\":\"limit\""))
    }

    /** A turn that went through ends the count: the next limit is a fresh one, not another refused carry-on. */
    fun testATurnThatWentThroughStartsTheCountAgain() {
        val recorder = Recorder("desk")
        val hub = hub(recorder)

        refusedTurn(hub)
        hub.runQueued(TAB, now = hub.conversations.carryOnOf(TAB)!!.notBefore!!)
        feed(hub, """{"type":"result","subtype":"success","is_error":false,"result":"done","duration_ms":10}""")

        assertNull(hub.conversations.lastCarryOn(TAB))
    }

}
