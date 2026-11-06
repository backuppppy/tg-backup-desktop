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
    /** Bumped by [forget]: a load that started before it does not put its chats back. */
    private val generations = ConcurrentHashMap<String, Long>()

    /** The account's generation now: taken when a load starts, given to [putChats] / [putTopics] as `since`. */
    fun generation(accountId: String): Long = generations[accountId] ?: 0L

    /**
     * Runs [block] alone for [accountId]: the export session's login, check, chats and topics loads share one
     * MTProto client, so a second request for the same account waits for the first.
     */
    suspend fun <T> exclusive(accountId: String, block: suspend () -> T): T =
        locks.computeIfAbsent(accountId) { Mutex() }.withLock { block() }

    /**
     * Replaces the account's chats; the topics of the previous load are dropped with them. Nothing is kept when the
     * account was forgotten since the load started ([since]).
     */
    fun putChats(accountId: String, chats: List<ChatEntry>, since: Long = generation(accountId)) {
        loaded.compute(accountId) { _, old ->
            if (generation(accountId) != since) old else Loaded(chats.associateBy { it.peerId })
        }
    }

    fun chat(accountId: String, peerId: Long): ChatEntry? = loaded[accountId]?.chats?.get(peerId)

    /** The last topics loaded for chat [peerId]. */
    fun putTopics(accountId: String, peerId: Long, topics: List<TopicEntry>, since: Long = generation(accountId)) {
        loaded.compute(accountId) { _, old ->
            if (generation(accountId) != since) return@compute old
            val base = old ?: Loaded(emptyMap())
            Loaded(base.chats, base.topics + (peerId to topics.associateBy { it.id }))
        }
    }

    fun topic(accountId: String, peerId: Long, topicId: Int): TopicEntry? = loaded[accountId]?.topics?.get(peerId)?.get(topicId)

    /** Drops what was loaded for [accountId] (the account deleted or logged out); a load in flight keeps nothing. */
    fun forget(accountId: String) {
        loaded.compute(accountId) { _, _ ->
            generations.merge(accountId, 1L, Long::plus)
            null
        }
    }
}
