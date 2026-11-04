package org.tgbackup.desktop.server

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tgbackup.export.ChatEntry
import org.tgbackup.export.TopicEntry
import java.util.concurrent.ConcurrentHashMap

/**
 * The last loaded chats (and topics) per account. A [ChatEntry] holds an `MTPInputPeer` that cannot go to JSON, so
 * the page names a chat by its `peerId` and the export commands look the entry up here.
 */
class ChatHandles {
    private class Loaded(val chats: Map<Long, ChatEntry>, val topics: Map<Long, Map<Int, TopicEntry>> = emptyMap())

    private val loaded = ConcurrentHashMap<String, Loaded>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    /**
     * Runs [block] alone for [accountId]: the export session's login, check, chats and topics loads share one
     * MTProto client, so a second request for the same account waits for the first.
     */
    suspend fun <T> exclusive(accountId: String, block: suspend () -> T): T =
        locks.computeIfAbsent(accountId) { Mutex() }.withLock { block() }

    /** Replaces the account's chats; the topics of the previous load are dropped with them. */
    fun putChats(accountId: String, chats: List<ChatEntry>) {
        loaded[accountId] = Loaded(chats.associateBy { it.peerId })
    }

    fun chat(accountId: String, peerId: Long): ChatEntry? = loaded[accountId]?.chats?.get(peerId)

    /** The last topics loaded for chat [peerId]. */
    fun putTopics(accountId: String, peerId: Long, topics: List<TopicEntry>) {
        loaded.compute(accountId) { _, old ->
            val base = old ?: Loaded(emptyMap())
            Loaded(base.chats, base.topics + (peerId to topics.associateBy { it.id }))
        }
    }

    fun topic(accountId: String, peerId: Long, topicId: Int): TopicEntry? = loaded[accountId]?.topics?.get(peerId)?.get(topicId)

    /** Drops what was loaded for [accountId] (e.g. after the account was deleted). */
    fun forget(accountId: String) {
        loaded.remove(accountId)
    }
}
