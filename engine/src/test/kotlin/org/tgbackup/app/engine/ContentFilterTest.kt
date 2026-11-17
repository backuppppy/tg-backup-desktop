package org.tgbackup.app.engine

import org.tgbackup.app.data.Job
import org.tgbackup.app.data.MediaType
import org.tgbackup.copy.CopyKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContentFilterTest {
    private fun kinds(t: MediaType) = CopyKind.entries.filter { it.matches(t) }.toSet()

    @Test
    fun legacyTypesKeepTodaysMeaning() {
        assertEquals(
            setOf(CopyKind.VIDEO, CopyKind.DOCUMENT, CopyKind.AUDIO, CopyKind.ANIMATION, CopyKind.VOICE, CopyKind.ROUND),
            kinds(MediaType.VIDEO_DOC),
        )
        assertEquals(setOf(CopyKind.PHOTO), kinds(MediaType.PHOTO))
        assertEquals(setOf(CopyKind.TEXT), kinds(MediaType.TEXT))
        assertEquals(CopyKind.entries.toSet(), kinds(MediaType.ALL))
        assertEquals(kinds(MediaType.VIDEO_DOC) + CopyKind.PHOTO, kinds(MediaType.ALL_MEDIA))
    }

    @Test
    fun newTypesMatchExactlyTheirKinds() {
        assertEquals(setOf(CopyKind.VIDEO, CopyKind.ANIMATION, CopyKind.ROUND), kinds(MediaType.VIDEO))
        assertEquals(setOf(CopyKind.DOCUMENT), kinds(MediaType.FILE))
        assertEquals(setOf(CopyKind.AUDIO), kinds(MediaType.MUSIC))
        assertEquals(setOf(CopyKind.VOICE), kinds(MediaType.VOICE))
    }

    @Test
    fun parsesExtensions() {
        assertEquals(setOf("pdf", "zip", "rar"), parseExtensions(" .PDF, zip  rar,,"))
        assertEquals(emptySet(), parseExtensions("  "))
    }

    @Test
    fun legacyJobUsesItsSingleType() {
        val f = Job(mediaType = MediaType.PHOTO).contentFilter()
        assertTrue(f.accepts(CopyKind.PHOTO, null))
        assertFalse(f.accepts(CopyKind.DOCUMENT, "a.pdf"))
    }

    @Test
    fun typesOrExtensions() {
        val f = Job(mediaTypes = listOf(MediaType.PHOTO), extensions = "pdf").contentFilter()
        assertTrue(f.accepts(CopyKind.PHOTO, null))
        assertTrue(f.accepts(CopyKind.DOCUMENT, "Book.PDF"))
        assertFalse(f.accepts(CopyKind.DOCUMENT, "x.zip"))
        assertFalse(f.accepts(CopyKind.VIDEO, "clip.mp4"))
    }

    @Test
    fun onlyExtensions() {
        val f = Job(mediaTypes = emptyList(), extensions = "mp4").contentFilter()
        assertTrue(f.accepts(CopyKind.VIDEO, "clip.mp4"))
        assertTrue(f.accepts(CopyKind.DOCUMENT, "clip.MP4"))
        assertFalse(f.accepts(CopyKind.PHOTO, null))
        assertFalse(f.accepts(CopyKind.DOCUMENT, "mp4"))
    }

    @Test
    fun allIgnoresExtensions() {
        val f = Job(mediaTypes = listOf(MediaType.ALL), extensions = "pdf").contentFilter()
        assertTrue(f.accepts(CopyKind.STICKER, null))
        assertTrue(f.accepts(CopyKind.DOCUMENT, "x.zip"))
    }

    @Test
    fun fileIsNotVoiceOrMusic() {
        val f = Job(mediaTypes = listOf(MediaType.FILE)).contentFilter()
        assertTrue(f.accepts(CopyKind.DOCUMENT, "a.pdf"))
        assertFalse(f.accepts(CopyKind.VOICE, null))
        assertFalse(f.accepts(CopyKind.AUDIO, "song.mp3"))
    }

    @Test
    fun labelsListTheChoices() {
        assertEquals("תמונות + קבצים + .pdf", Job(mediaTypes = listOf(MediaType.PHOTO, MediaType.FILE), extensions = "pdf").contentLabel())
        assertEquals("תמונות בלבד", Job(mediaType = MediaType.PHOTO).contentLabel())
    }

    @Test
    fun extensionsWithTwoDotsAndPastedSpaces() {
        assertEquals(setOf("pdf", "zip"), parseExtensions("pdf\u00A0zip"))
        val f = Job(mediaTypes = emptyList(), extensions = "tar.gz").contentFilter()
        assertTrue(f.accepts(CopyKind.DOCUMENT, "backup.TAR.GZ"))
        assertFalse(f.accepts(CopyKind.DOCUMENT, "tar.gz"))
    }

    @Test
    fun oldTypesStayAsTheyAre() {
        for (t in listOf(MediaType.VIDEO_DOC, MediaType.ALL_MEDIA, MediaType.PHOTO, MediaType.TEXT, MediaType.ALL)) {
            assertEquals(t, legacyMediaType(listOf(t)))
        }
        assertEquals(MediaType.VIDEO_DOC, legacyMediaType(listOf(MediaType.VIDEO)))
    }
}
