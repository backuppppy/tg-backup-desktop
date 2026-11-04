package org.tgbackup.desktop.server

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LogRoutesTest : RouteTestBase() {
    @AfterTest
    fun tearDown() = closeApp()

    @Test
    fun deleteLogClearsTheBuffer() = withServer { client ->
        app.log.info("א")
        app.log.info("ב")
        client.del("/api/log").assertOk()
        assertEquals(emptyList(), app.log.entries.value)
    }
}
