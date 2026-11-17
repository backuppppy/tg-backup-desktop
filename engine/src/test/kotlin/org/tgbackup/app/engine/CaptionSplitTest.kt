package org.tgbackup.app.engine

import dev.g000sha256.tdl.dto.FormattedText
import dev.g000sha256.tdl.dto.TextEntity
import dev.g000sha256.tdl.dto.TextEntityTypeBold
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptionSplitTest {
    private fun ft(s: String, vararg e: TextEntity) = FormattedText(s, arrayOf(*e))

    @Test
    fun shortStaysWhole() {
        val (head, rest) = splitFormatted(ft("שלום"), 10)
        assertEquals("שלום", head.text)
        assertNull(rest)
    }

    @Test
    fun cutsAtWhitespaceAndKeepsAllText() {
        val text = "aaaa bbbb cccc dddd"
        val (head, rest) = splitFormatted(ft(text), 12)
        assertEquals("aaaa bbbb", head.text)
        assertEquals("cccc dddd", rest!!.text)
        assertTrue(head.text.length <= 12)
    }

    @Test
    fun noWhitespaceCutsAtMax() {
        val (head, rest) = splitFormatted(ft("abcdefghij"), 4)
        assertEquals("abcd", head.text)
        assertEquals("efghij", rest!!.text)
    }

    @Test
    fun neverSplitsASurrogatePair() {
        // "😀" is two UTF-16 units; a cut at 4 would fall between them.
        val (head, rest) = splitFormatted(ft("abc😀def"), 4)
        assertEquals("abc", head.text)
        assertEquals("😀def", rest!!.text)
    }

    @Test
    fun entitiesFollowTheirText() {
        // "bold" at 0..4 stays in the head; "tail" at 10..14 moves to the rest, shifted; one crossing is clipped to both.
        val text = "bold xx yy tail"
        val (head, rest) = splitFormatted(
            ft(text, TextEntity(0, 4, TextEntityTypeBold()), TextEntity(11, 4, TextEntityTypeBold()), TextEntity(5, 5, TextEntityTypeBold())),
            8,
        )
        assertEquals("bold xx", head.text)
        assertEquals("yy tail", rest!!.text)
        assertEquals(listOf(0 to 4, 5 to 2), head.entities.map { it.offset to it.length })
        assertEquals(listOf(3 to 4, 0 to 2), rest.entities.map { it.offset to it.length })
    }

    @Test
    fun chunksCoverEverything() {
        val text = (1..50).joinToString(" ") { "word$it" }
        val parts = splitAll(ft(text), 40)
        assertTrue(parts.all { it.text.length <= 40 })
        assertEquals(text.split(" "), parts.flatMap { it.text.split(" ") })
    }

    @Test
    fun captionTooLongIsRecognized() {
        assertTrue(isCaptionTooLong("MEDIA_CAPTION_TOO_LONG"))
        assertTrue(isCaptionTooLong("Message caption is too long"))
        assertTrue(!isCaptionTooLong("FLOOD_WAIT_10"))
    }

    @Test
    fun captionRetryTriesTheOriginalFirst() {
        // A replaced caption on a server-side copy: the original first, then the split, then a plain failure.
        assertEquals(CaptionStep.KeepOriginal, captionRetry(upload = false, replacedCaption = true, triedOriginal = false, alreadySplit = false))
        assertEquals(CaptionStep.Split, captionRetry(upload = false, replacedCaption = false, triedOriginal = true, alreadySplit = false))
        assertEquals(CaptionStep.GiveUp, captionRetry(upload = false, replacedCaption = false, triedOriginal = false, alreadySplit = true))
        // Nothing replaced, or an upload: straight to the split.
        assertEquals(CaptionStep.Split, captionRetry(upload = false, replacedCaption = false, triedOriginal = false, alreadySplit = false))
        assertEquals(CaptionStep.Split, captionRetry(upload = true, replacedCaption = true, triedOriginal = false, alreadySplit = false))
    }
}
