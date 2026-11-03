package org.tgbackup.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class AppDirsTest {
    @Test
    fun windowsUsesLocalAppData() {
        val p = AppDirs.dataDir(
            env = mapOf("LOCALAPPDATA" to "C:\\Users\\משה כהן\\AppData\\Local"),
            home = "C:\\Users\\משה כהן",
            os = "Windows 11",
        )
        assertEquals("C:\\Users\\משה כהן\\AppData\\Local\\TGBackup", p.toString())
    }

    @Test
    fun windowsWithoutLocalAppDataFallsBackToHome() {
        val p = AppDirs.dataDir(env = emptyMap(), home = "C:\\Users\\u", os = "Windows 10")
        assertEquals("C:\\Users\\u\\AppData\\Local\\TGBackup", p.toString())
    }

    @Test
    fun linuxUsesDotDirInHome() {
        val p = AppDirs.dataDir(env = emptyMap(), home = "/home/u", os = "Linux")
        assertEquals("/home/u/.tgbackup", p.toString())
    }
}
