package org.tgbackup.desktop

import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeTmpTest {
    private val hebrewEnv = mapOf(
        "LOCALAPPDATA" to "C:\\Users\\משה כהן\\AppData\\Local",
        "ProgramData" to "C:\\ProgramData",
    )

    @Test
    fun asciiDataDirIsUsed() {
        val p = NativeTmp.choose(
            env = mapOf("LOCALAPPDATA" to "C:\\Users\\moshe\\AppData\\Local"),
            home = "C:\\Users\\moshe",
            os = "Windows 11",
            writable = { true },
        )
        assertEquals("C:\\Users\\moshe\\AppData\\Local\\TGBackup\\tmp", p.toString())
    }

    @Test
    fun hebrewDataDirFallsBackToProgramData() {
        val p = NativeTmp.choose(env = hebrewEnv, home = "C:\\Users\\משה כהן", os = "Windows 11", writable = { true })
        val s = p.toString()
        assertTrue(s.matches(Regex("""C:\\ProgramData\\TGBackup-[0-9a-f]{8}\\tmp""")), s)
    }

    @Test
    fun programDataDirIsPerUser() {
        val a = NativeTmp.choose(env = hebrewEnv, home = "C:\\Users\\משה כהן", os = "Windows 11", writable = { true })
        val b = NativeTmp.choose(
            env = hebrewEnv + ("LOCALAPPDATA" to "C:\\Users\\דנה לוי\\AppData\\Local"),
            home = "C:\\Users\\דנה לוי",
            os = "Windows 11",
            writable = { true },
        )
        assertTrue(a != b, "$a == $b")
    }

    @Test
    fun spaceInAsciiPathIsFine() {
        val p = NativeTmp.choose(
            env = mapOf("LOCALAPPDATA" to "C:\\Users\\Moshe Cohen\\AppData\\Local"),
            home = "C:\\Users\\Moshe Cohen",
            os = "Windows 10",
            writable = { true },
        )
        assertEquals("C:\\Users\\Moshe Cohen\\AppData\\Local\\TGBackup\\tmp", p.toString())
    }

    @Test
    fun unwritableCandidateIsSkipped() {
        val p = NativeTmp.choose(
            env = mapOf("LOCALAPPDATA" to "C:\\Users\\moshe\\AppData\\Local", "ProgramData" to "C:\\ProgramData"),
            home = "C:\\Users\\moshe",
            os = "Windows 11",
            writable = { !it.toString().contains("AppData") },
        )
        assertTrue(p.toString().startsWith("C:\\ProgramData\\TGBackup-"), p.toString())
    }

    @Test
    fun noUsableCandidateKeepsTheJvmDefault() {
        assertNull(NativeTmp.choose(env = hebrewEnv, home = "C:\\Users\\משה כהן", os = "Windows 11", writable = { false }))
        assertNull(
            NativeTmp.choose(
                env = mapOf("LOCALAPPDATA" to "C:\\Users\\משה\\AppData\\Local", "ProgramData" to "D:\\נתונים"),
                home = "C:\\Users\\משה",
                os = "Windows 11",
                writable = { true },
            ),
        )
    }

    @Test
    fun nonWindowsKeepsTheJvmDefault() {
        assertNull(NativeTmp.choose(env = emptyMap(), home = "/home/u", os = "Linux", writable = { true }))
    }

    @Test
    fun cleanStaleRemovesOnlyOldEntries() {
        val dir = Files.createTempDirectory("nativetmp-test")
        val old = dir.resolve("tdl-old").createDirectories()
        old.resolve("tdjsonjava.dll").writeText("x")
        val fresh = dir.resolve("tdl-fresh").createDirectories()
        val longAgo = FileTime.from(Instant.now().minusSeconds(3 * 3600))
        Files.setLastModifiedTime(old.resolve("tdjsonjava.dll"), longAgo)
        Files.setLastModifiedTime(old, longAgo)

        NativeTmp.cleanStale(dir)

        assertFalse(old.exists())
        assertTrue(fresh.exists())
    }
}
