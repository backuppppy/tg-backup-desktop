package org.tgbackup.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopPrefsTest {
    private fun dir(): File = Files.createTempDirectory("desktop-prefs").resolve("תיקייה עם רווח").toFile().apply { mkdirs() }

    @Test
    fun aMissingFileGivesTheDefaults() {
        val prefs = DesktopPrefs(dir())
        assertEquals(DesktopPrefsData(autostart = false, keepAwake = true), prefs.value.value)
    }

    @Test
    fun anUpdateIsSavedAndReadBack() {
        val d = dir()
        val prefs = DesktopPrefs(d)
        prefs.update { it.copy(autostart = true, keepAwake = false) }
        assertEquals(DesktopPrefsData(autostart = true, keepAwake = false), prefs.value.value)
        assertTrue(File(d, "desktop.json").isFile)
        // Written through a temp file that is moved over desktop.json: no temp file stays behind.
        assertEquals(listOf("desktop.json"), d.list()!!.sorted())
        assertEquals(DesktopPrefsData(autostart = true, keepAwake = false), DesktopPrefs(d).value.value)
    }

    @Test
    fun aBadFileGivesTheDefaults() {
        val d = dir()
        File(d, "desktop.json").writeText("{not json")
        assertEquals(DesktopPrefsData(), DesktopPrefs(d).value.value)
        File(d, "desktop.json").writeText("""{"keepAwake":"maybe"}""")
        assertEquals(DesktopPrefsData(), DesktopPrefs(d).value.value)
    }

    @Test
    fun unknownAndMissingFieldsAreTolerated() {
        val d = dir()
        File(d, "desktop.json").writeText("""{"autostart":true,"later":1}""")
        val v = DesktopPrefs(d).value.value
        assertTrue(v.autostart)
        assertTrue(v.keepAwake)
        assertFalse(DesktopPrefsData().autostart)
    }
}
