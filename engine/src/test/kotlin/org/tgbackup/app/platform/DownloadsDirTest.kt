package org.tgbackup.app.platform

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadsDirTest {
    @Test
    fun usesKnownFolderWhenPresent() {
        assertEquals("D:\\הורדות", DownloadsDir.resolve({ "D:\\הורדות" }, "C:\\Users\\u").path)
    }

    @Test
    fun fallsBackToHomeDownloads() {
        assertEquals(File("/home/u", "Downloads"), DownloadsDir.resolve({ null }, "/home/u"))
    }
}
