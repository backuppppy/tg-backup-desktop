package org.tgbackup.desktop

import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExternalLinksTest {
    @Test
    fun httpAndHttpsWithAHostAreAllowed() {
        assertTrue(ExternalLinks.allowed("https://github.com/tdlib/td"))
        assertTrue(ExternalLinks.allowed("http://example.com/a?b=1#c"))
        assertTrue(ExternalLinks.allowed("HTTPS://T.ME/channel"))
        assertTrue(ExternalLinks.allowed("https://t.me/c/1000000000/1"))
    }

    @Test
    fun everyOtherSchemeIsRejected() {
        for (url in listOf(
            "javascript:alert(1)", "file:///C:/Windows/system32/calc.exe", "mailto:a@b.c", "tg://resolve?domain=x",
            "ms-settings:", "data:text/html,hi", "ftp://example.com/", "\\\\server\\share", "C:\\Windows",
        )) assertFalse(ExternalLinks.allowed(url), url)
    }

    @Test
    fun blankMalformedOrHostlessUrlsAreRejected() {
        for (url in listOf("", "   ", "https://", "https:///path", "http:example.com", "https://exa mple.com/", "https://a.com/\n")) {
            assertFalse(ExternalLinks.allowed(url), url)
        }
    }

    @Test
    fun theBindingArgumentIsTheFirstStringOfTheJsonArray() {
        assertEquals("https://x.org/", ExternalLinks.urlFromBindArgs("[\"https://x.org/\"]"))
        assertNull(ExternalLinks.urlFromBindArgs("[]"))
        assertNull(ExternalLinks.urlFromBindArgs("[1]"))
        assertNull(ExternalLinks.urlFromBindArgs("not json"))
        assertNull(ExternalLinks.urlFromBindArgs(null))
    }

    @Test
    fun theInitScriptHooksClicksAndWindowOpen() {
        val js = ExternalLinks.INIT_SCRIPT
        assertTrue("addEventListener('click'" in js)
        assertTrue("auxclick" in js)
        assertTrue("window.open =" in js)
        assertTrue("tgbOpenExternal" in js)
        assertTrue("_blank" in js)
    }
}

class SmokePageTest {
    private val tabs = "[\"משימות\",\"ייצוא\",\"חשבונות\",\"לוג\",\"הגדרות\"]"

    @Test
    fun theRealScreenWithTheFiveTabsPasses() {
        assertNull(SmokePage.problem("[{\"href\":\"http://127.0.0.1:5000/#jobs\",\"screen\":true,\"tabs\":$tabs}]"))
    }

    @Test
    fun aMissingScreenIsAFailure() {
        assertEquals("no #screen content", SmokePage.problem("[{\"href\":\"http://127.0.0.1:5000/\",\"screen\":false,\"tabs\":$tabs}]"))
    }

    @Test
    fun aMissingTabIsNamed() {
        assertEquals(
            "missing tabs: לוג, הגדרות",
            SmokePage.problem("[{\"href\":\"http://127.0.0.1:5000/\",\"screen\":true,\"tabs\":[\"משימות\",\"ייצוא\",\"חשבונות\"]}]"),
        )
    }

    @Test
    fun anotherPageIsAFailure() {
        assertEquals(
            "unexpected page",
            SmokePage.problem("[{\"href\":\"https://example.com/\",\"screen\":true,\"tabs\":$tabs}]"),
        )
        assertEquals("bad report", SmokePage.problem("garbage"))
    }

    @Test
    fun theSmokeDataDirIsASiblingOfTheRealOne() {
        val real = Paths.get("/home/u/.tgbackup")
        val smoke = smokeDataDir(real)
        assertEquals(real.parent, smoke.parent)
        assertEquals(".tgbackup-smoke", smoke.fileName.toString())
    }
}

class ShutdownTest {
    @Test
    fun stepsThatFinishInTimeDoNotHalt() {
        val ran = AtomicInteger()
        var halted: Int? = null
        val done = Shutdown.run(listOf({ ran.incrementAndGet(); Unit }, { ran.incrementAndGet(); Unit }), limitMs = 5_000) { halted = it }
        assertTrue(done)
        assertEquals(2, ran.get())
        assertNull(halted)
    }

    @Test
    fun aFailingStepDoesNotSkipTheNext() {
        val ran = AtomicInteger()
        val done = Shutdown.run(listOf({ error("boom") }, { ran.incrementAndGet(); Unit }), limitMs = 5_000) { }
        assertTrue(done)
        assertEquals(1, ran.get())
    }

    @Test
    fun aHangingStepHaltsAfterTheLimit() {
        var halted: Int? = null
        val started = System.nanoTime()
        val done = Shutdown.run(listOf({ Thread.sleep(10_000) }), limitMs = 200) { halted = it }
        assertFalse(done)
        assertEquals(0, halted)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
    }
}
