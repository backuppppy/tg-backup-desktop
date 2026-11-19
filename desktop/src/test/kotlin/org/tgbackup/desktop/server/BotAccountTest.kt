package org.tgbackup.desktop.server

import kotlinx.serialization.json.JsonPrimitive
import org.tgbackup.app.data.Account
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BotAccountTest {
    private val token = "123456789:AAbcdefghijklmnopqrstuvwxyz0123456"
    private val user = Account(id = "u", name = "u", apiId = 1234, apiHash = "0123456789abcdef0123", phone = "+1")

    @Test
    fun aNewBotTakesTheApiOfTheFirstRegularAccount() {
        val bot = AccountInput(name = "b", isBot = true, botToken = token).toAccount("b", storedHash = null, apiFrom = user)
        assertTrue(bot.isBot)
        assertEquals(1234, bot.apiId)
        assertEquals("", bot.phone)
    }

    @Test
    fun aBadTokenOrNoRegularAccountIsRefused() {
        assertFailsWith<Refusal> { AccountInput(isBot = true, botToken = "123:x").toAccount("b", null, apiFrom = user) }
        assertFailsWith<Refusal> { AccountInput(isBot = true, botToken = token).toAccount("b", null, apiFrom = null) }
    }

    @Test
    fun anEditKeepsTheStoredKind() {
        // A bot edited with the token field blank keeps its token and stays a bot.
        val bot = AccountInput(name = "b2").toAccount("b", storedHash = user.apiHash, storedToken = token, apiFrom = user, storedIsBot = true)
        assertEquals(token, bot.botToken)
        // A regular account sent with a token stays regular.
        val reg = AccountInput(name = "u", apiId = JsonPrimitive(1234), apiHash = MASKED_HASH, phone = "+1", botToken = token)
            .toAccount("u", storedHash = user.apiHash, apiFrom = user, storedIsBot = false)
        assertEquals(false, reg.isBot)
    }
}
