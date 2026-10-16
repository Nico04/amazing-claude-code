package io.github.crmapache.amazingclaudecode.claude

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CarryOnWordsTest {

    /** Every sentence the panel can say, for one language. */
    private fun everything(language: String): List<String> = listOf(
        CarryOnWords.afterAccountSwitch(language),
        CarryOnWords.afterLimit(language, moved = false),
        CarryOnWords.afterLimit(language, moved = true),
        CarryOnWords.afterRestart(language),
        CarryOnWords.helpersLost(language),
    )

    /**
     * English said into a Russian conversation is no mistake anybody would see - the agent simply goes on
     * in English from that message. So every language is checked against the English one.
     */
    @Test
    fun `no language quietly keeps the English wording`() {
        val english = everything("en")

        for (language in CarryOnWords.LANGUAGES) {
            if (language == "en") continue
            val words = everything(language)

            words.forEach { assertTrue(it.isNotBlank(), language) }
            val same = words.filterIndexed { index, said -> said == english[index] }
            assertTrue(same.isEmpty(), "$language repeats the English: $same")
        }
    }

    /** A tag we have no words for falls back to English rather than to an empty message. */
    @Test
    fun `an unknown language falls back to English`() {
        assertEquals(everything("en"), everything("sv"))
    }

    /** A limit and a moved account are different news, and the agent is told the one that is true. */
    @Test
    fun `a limit waited out and a limit left behind are said apart`() {
        val waited = CarryOnWords.of(CarryOns.Owed(CarryOns.Reason.LIMIT), "en")
        val moved = CarryOnWords.of(CarryOns.Owed(CarryOns.Reason.LIMIT, moved = true), "en")

        assertEquals(CarryOnWords.afterLimit("en", moved = false), waited)
        assertEquals(CarryOnWords.afterLimit("en", moved = true), moved)
    }

    /** Agents lost on the way are named after a limit's words - an account switch's words name them already. */
    @Test
    fun `lost agents are named once`() {
        val limit = CarryOnWords.of(CarryOns.Owed(CarryOns.Reason.LIMIT, helpersLost = true), "en")
        assertTrue(limit.endsWith(CarryOnWords.helpersLost("en")))

        val switch = CarryOnWords.of(CarryOns.Owed(CarryOns.Reason.ACCOUNT_SWITCH, helpersLost = true), "en")
        assertEquals(CarryOnWords.afterAccountSwitch("en"), switch)
    }

    /**
     * The tag is the only thing a transcript keeps of who said it, so it has to wrap the words whole: the feed
     * and the search recognise the message by it starting the text and closing it.
     */
    @Test
    fun `the agent is sent the words inside the tag that names the road`() {
        val words = CarryOnWords.afterAccountSwitch("ru")

        assertEquals(
            "<panel-carry-on reason=\"limit\">$words</panel-carry-on>",
            CarryOnWords.forAgent(CarryOns.Reason.LIMIT, words),
        )
        // Nothing the words say can close the tag early.
        for (language in CarryOnWords.LANGUAGES) {
            everything(language).forEach { assertFalse(it.contains("<"), language) }
        }
    }
}
