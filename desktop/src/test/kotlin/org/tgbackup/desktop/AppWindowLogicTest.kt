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

    private val origin = "http://127.0.0.1:5000"

    @Test
    fun aRequestFromOurOriginWithAnHttpsUrlOpens() {
        assertEquals(
            LinkDecision.Open("https://x.org/"),
            ExternalLinks.decide("[\"$origin\",\"https://x.org/\"]", origin),
        )
    }

    @Test
    fun aRequestFromAnotherOriginIsRefusedWhateverTheUrl() {
        for (other in listOf("https://evil.example", "http://127.0.0.1:5001", "http://localhost:5000", "null", "")) {
            assertEquals(LinkDecision.WrongOrigin, ExternalLinks.decide("[\"$other\",\"https://x.org/\"]", origin), other)
        }
        // The old one-argument form carries no origin.
        assertEquals(LinkDecision.WrongOrigin, ExternalLinks.decide("[\"https://x.org/\"]", origin))
    }

    @Test
    fun aNonHttpUrlFromOurOriginIsRejected() {
        assertEquals(LinkDecision.Rejected, ExternalLinks.decide("[\"$origin\",\"file:///C:/x\"]", origin))
        assertEquals(LinkDecision.Rejected, ExternalLinks.decide("[\"$origin\",1]", origin))
    }

    @Test
    fun malformedArgumentsAreRefused() {
        for (req in listOf(null, "", "not json", "[]", "{}", "[1,2]")) {
            assertEquals(LinkDecision.WrongOrigin, ExternalLinks.decide(req, origin), req.toString())
        }
    }

    @Test
    fun theOriginOfTheEntryUrl() {
        assertEquals(origin, ExternalLinks.originOf("http://127.0.0.1:5000/?t=abc"))
        assertEquals("https://t.me:443", ExternalLinks.originOf("HTTPS://T.ME/x"))
        assertNull(ExternalLinks.originOf("about:blank"))
        assertNull(ExternalLinks.originOf("not a url"))
    }

    @Test
    fun ourPagesStayInTheWindow() {
        for (href in listOf("$origin/", "$origin/#jobs", "$origin/?t=abc", "about:blank")) {
            assertEquals(PageAction.Stay, ExternalLinks.onPage(href, origin), href)
        }
    }

    @Test
    fun aForeignPageSendsTheWindowHomeAndOpensItInTheBrowser() {
        assertEquals(PageAction.GoHome("https://t.me/x"), ExternalLinks.onPage("https://t.me/x", origin))
        // Same host, another port: not ours.
        assertEquals(PageAction.GoHome("http://127.0.0.1:50001/"), ExternalLinks.onPage("http://127.0.0.1:50001/", origin))
        // Not http(s): back home, but nothing is opened.
        assertEquals(PageAction.GoHome(null), ExternalLinks.onPage("file:///C:/Windows/", origin))
        assertEquals(PageAction.GoHome(null), ExternalLinks.onPage("data:text/html,hi", origin))
    }

    @Test
    fun theInitScriptIsBoundToOurOriginAndHooksClicksFormsAndWindowOpen() {
        val js = ExternalLinks.initScript(origin)
        assertTrue("var ORIGIN = \"$origin\";" in js)
        assertTrue("location.origin !== ORIGIN" in js)
        assertTrue("addEventListener('click'" in js)
        assertTrue("addEventListener('auxclick'" in js)
        assertTrue("addEventListener('submit'" in js)
        assertTrue("window.open =" in js)
        assertTrue("window.${ExternalLinks.BINDING}(ORIGIN, u)" in js)
        assertTrue("window.${ExternalLinks.PAGE_BINDING}(location.href)" in js)
        // A quote in the origin cannot break out of the string.
        assertTrue("var ORIGIN = \"a\\\"b\";" in ExternalLinks.initScript("a\"b"))
    }
}

class WindowLifecycleTest {
    @Test
    fun aWindowThatOpenedAndClosedShutsDownAndExitsZero() {
        val calls = mutableListOf<String>()
        val code = WindowLifecycle.run({ calls += "open"; WindowOutcome.CLOSED }, { calls += "shutdown" }, { calls += "message" })
        assertEquals(0, code)
        assertEquals(listOf("open", "shutdown"), calls)
    }

    @Test
    fun aThrowingOpenStillShutsTheEngineDown() {
        val calls = mutableListOf<String>()
        val code = WindowLifecycle.run({ error("no dll") }, { calls += "shutdown" }, { calls += "message" })
        assertEquals(1, code)
        assertEquals(listOf("shutdown"), calls)
    }

    @Test
    fun aMissingWebView2ShutsDownThenTellsTheUser() {
        val calls = mutableListOf<String>()
        val code = WindowLifecycle.run({ WindowOutcome.NO_WEBVIEW }, { calls += "shutdown" }, { calls += "message" })
        assertEquals(2, code)
        assertEquals(listOf("shutdown", "message"), calls)
    }

    @Test
    fun aFailingMessageDoesNotChangeTheExitCode() {
        val code = WindowLifecycle.run({ WindowOutcome.NO_WEBVIEW }, { }, { error("headless") })
        assertEquals(2, code)
    }

    @Test
    fun theMissingWebView2TextIsHebrew() {
        assertEquals("לא ניתן לפתוח את החלון: רכיב WebView2 חסר במחשב.", MSG_NO_WEBVIEW)
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

class WindowAppPropertiesTest {
    @Test
    fun theIdComesFirstThenTheRelaunchCommandNameAndCloudIcon() {
        val natives = java.io.File("C:\\Program Files\\TGBackup\\app\\natives")
        assertEquals(
            listOf(
                WindowAppProperties.PID_ID to "TGBackup.Desktop",
                WindowAppProperties.PID_RELAUNCH_COMMAND to "\"C:\\Program Files\\TGBackup\\TGBackup.exe\"",
                WindowAppProperties.PID_RELAUNCH_DISPLAY_NAME to "TG Backup",
                WindowAppProperties.PID_RELAUNCH_ICON to "${java.io.File(natives, WINDOW_ICON_FILE).path},0",
            ),
            WindowAppProperties.of("C:\\Program Files\\TGBackup\\TGBackup.exe", natives),
        )
        assertEquals("TGBackup-window.ico", WINDOW_ICON_FILE)
    }

    @Test
    fun withoutTheInstalledExeNothingIsWritten() {
        // A Gradle run has no TGBackup.exe to relaunch: a pinned button would point at java.exe.
        assertNull(WindowAppProperties.of(null, java.io.File("natives")))
        assertNull(WindowAppProperties.of("  ", java.io.File("natives")))
    }
}
