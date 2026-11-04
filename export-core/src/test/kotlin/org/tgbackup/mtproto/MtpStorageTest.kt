package org.tgbackup.mtproto

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class MtpStorageTest {
    @Test
    fun secondSetIsKeptAfterReload() {
        val file = Files.createTempDirectory("mtp").toFile().resolve("session.properties")
        val storage = MtpStorage(file)
        storage.set("main_dc", "2")
        storage.set("main_dc", "4")

        assertEquals(4, MtpStorage(file).mainDc)
    }
}
