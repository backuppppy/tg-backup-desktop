package org.tgbackup.desktop.server

import io.ktor.sse.ServerSentEvent
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.JsonElement
import org.tgbackup.app.BuildConfig
import org.tgbackup.app.engine.LogEntry
import org.tgbackup.app.export.PasswordRequest
import org.tgbackup.app.export.RunningExport
import org.tgbackup.desktop.DesktopApp
import org.tgbackup.desktop.DesktopPrefsData
import org.tgbackup.export.ExportState
import java.security.SecureRandom
import java.util.IdentityHashMap

/** The one [Events] of a `tgbModule`, for route files that publish (e.g. `chatsProgress`). */
val EventsKey = AttributeKey<Events>("tgb.events")

/** How often a running export's mutable progress is sampled for the `export` event. */
internal const val PROCESSING_SAMPLE_MS = 300L

/** Published events kept per connection (both while the snapshot is sent and later); the oldest are dropped. */
private const val HELD_CAPACITY = 256

/**
 * Builds [SnapshotDto] and the per-connection SSE stream. Each [stream] collector is one connection: it gets
 * `snapshot` first, then only changes against what it was sent. Collection stops when the connection does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Events(private val app: DesktopApp) {
    private val published = MutableSharedFlow<ServerSentEvent>(
        extraBufferCapacity = HELD_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Open connections (each one subscribes once to published events). */
    internal val connections: StateFlow<Int> get() = published.subscriptionCount

    /** Sends [data] as event [event] to every open connection (connections opened later do not get it). */
    fun publish(event: String, data: JsonElement) {
        published.tryEmit(ServerSentEvent(data = data.toString(), event = event))
    }

    private val chatsLoading = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** A chats load of [accountId] has [count] chats so far: kept for the snapshot and sent as `chatsProgress`. */
    fun chatsProgress(accountId: String, count: Int) {
        chatsLoading[accountId] = count
        publish("chatsProgress", TgbJson.encodeToJsonElement(ChatsProgressDto.serializer(), ChatsProgressDto(accountId, count)))
    }

    /** The chats load of [accountId] ended (either way). */
    fun chatsDone(accountId: String) {
        val last = chatsLoading.remove(accountId) ?: 0
        publish("chatsProgress", TgbJson.encodeToJsonElement(ChatsProgressDto.serializer(), ChatsProgressDto(accountId, last, done = true)))
    }

    /** A random id per [PasswordRequest] instance, kept while it is listed: the same request keeps its id. */
    private val passwordIds = IdentityHashMap<PasswordRequest, String>()

    @Synchronized
    fun passwordId(request: PasswordRequest): String = passwordIds.getOrPut(request) {
        ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    }

    /** The waiting request with [id], or null (answered, gone, or never there). */
    fun passwordRequest(id: String): PasswordRequest? = app.exports.passwords.value.firstOrNull { passwordId(it) == id }

    @Synchronized
    private fun List<PasswordRequest>.toDtos(): List<PasswordRequestDto> {
        // Ids of requests no longer waiting go (a request never comes back once it left). Checked against the
        // engine's list now, not this one: a connection mapping an older list must not drop a newer request's id.
        val now = app.exports.passwords.value
        passwordIds.keys.retainAll { kept -> now.any { it === kept } || any { it === kept } }
        return map { it.toDto() }
    }

    private fun PasswordRequest.toDto() = toDto(passwordId(this))

    private fun exportPrefs() = app.exports.prefs.value.toDto(app.exports.defaultPath())

    fun snapshot(): SnapshotDto {
        val config = app.store.config.value
        val waiting = app.exports.passwords.value.toDtos()
        return SnapshotDto(
            version = BuildConfig.VERSION_NAME,
            tdlibVersion = TdlibVersion,
            accounts = config.accounts.map { it.toDto() },
            accountStates = app.accountStates(config.accounts),
            jobs = config.jobs.map { it.toDto() },
            statuses = app.jobs.statuses.value.mapValues { it.value.toDto() },
            exportPrefs = exportPrefs(),
            runningExport = app.exports.running.value?.toDto(),
            passwords = waiting,
            password = waiting.firstOrNull(),
            log = app.log.entries.value.map { it.toDto() },
            chatsLoading = HashMap(chatsLoading),
            serverNow = System.currentTimeMillis(),
            desktopPrefs = app.prefs.value.value,
        )
    }

    private val configs: Flow<ConfigDto> = app.store.config.map { it.toDto() }

    private val statuses: Flow<Map<String, JobStatusDto>> =
        app.jobs.statuses.map { m -> m.mapValues { it.value.toDto() } }

    /** Follows the configured accounts, their clients, and each client's auth and last error. */
    private val accountStates: Flow<List<AccountStateDto>> =
        combine(app.store.config.map { it.accounts }.distinctUntilChanged(), app.accounts.clients) { a, c -> a to c }
            .flatMapLatest { (accounts, clients) ->
                if (accounts.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    combine(
                        accounts.map { acc ->
                            val client = clients[acc.id]
                            if (client == null) {
                                flowOf(accountState(acc.id, null))
                            } else {
                                combine(client.auth, client.lastError) { auth, err -> accountState(acc.id, auth, err) }
                            }
                        },
                    ) { it.toList() }
                }
            }

    private val prefs: Flow<ExportPrefsDto> = app.exports.prefs.map { it.toDto(app.exports.defaultPath()) }

    /** State changes go out at once; while `Processing` the mutable progress is sampled every 300 ms. */
    private val export: Flow<RunningExportDto?> = app.exports.running.flatMapLatest { running ->
        if (running == null) flowOf(null) else exportOf(running)
    }

    private fun exportOf(running: RunningExport): Flow<RunningExportDto> =
        sampledExportStates(running.controller.state).map { RunningExportDto(running.accountId, running.title, it) }

    private val passwords: Flow<List<PasswordRequestDto>> = app.exports.passwords.map { it.toDtos() }

    /**
     * One SSE connection: `snapshot` (with [viewer], `local` or `remote`), then changes. Cancelled with the collector
     * (the connection).
     */
    fun stream(viewer: String = VIEWER_LOCAL): Flow<ServerSentEvent> = channelFlow {
        // Subscribe to published events before the snapshot is taken, and hold them until it is sent.
        val held = Channel<ServerSentEvent>(HELD_CAPACITY, BufferOverflow.DROP_OLDEST)
        launch(start = CoroutineStart.UNDISPATCHED) { published.collect { held.trySend(it) } }

        val snap = snapshot().copy(viewer = viewer)
        send(event("snapshot", SnapshotDto.serializer(), snap))
        launch { for (e in held) send(e) }

        fun <T> forward(name: String, serializer: KSerializer<T>, source: Flow<T>, sent: T) = launch {
            source.changesSince(sent).collect { send(event(name, serializer, it)) }
        }
        forward("config", ConfigDto.serializer(), configs, ConfigDto(snap.accounts, snap.jobs))
        // The server's now is added as each one goes out, so it does not make equal statuses look changed.
        launch {
            statuses.changesSince(snap.statuses).collect {
                send(event("statuses", StatusesEventDto.serializer(), StatusesEventDto(it, System.currentTimeMillis())))
            }
        }
        forward("accountStates", ListSerializer(AccountStateDto.serializer()), accountStates, snap.accountStates)
        forward("exportPrefs", ExportPrefsDto.serializer(), prefs, snap.exportPrefs)
        forward("desktopPrefs", DesktopPrefsData.serializer(), app.prefs.value, snap.desktopPrefs)
        forward("export", RunningExportDto.serializer().nullable, export, snap.runningExport)
        // `passwords` is the list; `password` (its oldest) is kept for pages that read only that.
        launch {
            passwords.changesSince(snap.passwords).collect {
                send(event("passwords", ListSerializer(PasswordRequestDto.serializer()), it))
                send(event("password", PasswordRequestDto.serializer().nullable, it.firstOrNull()))
            }
        }

        launch {
            var lastSeq: Long? = snap.log.lastOrNull()?.seq
            app.log.entries.collect { entries ->
                val update = logUpdate(lastSeq, entries) ?: return@collect
                lastSeq = entries.lastOrNull()?.seq
                send(event("log", LogEventDto.serializer(), update))
            }
        }
    }

    private fun <T> event(name: String, serializer: KSerializer<T>, value: T) =
        ServerSentEvent(data = TgbJson.encodeToString(serializer, value), event = name)
}

/**
 * The `log` event for [entries], given the newest seq already sent. A clear is detected when that entry is gone
 * (seq keeps growing across [org.tgbackup.app.engine.LogBus.clear]); it resends the whole current list.
 * Returns null when there is nothing new.
 *
 * If more than LogBus's MAX (500) lines arrive between two collector runs (StateFlow conflates), the last-sent
 * entry has also fallen out of the window: that reads as a clear too, and the full current list goes out with
 * `cleared:true`. That is correct, since the client ends with exactly LogBus's list.
 */
internal fun logUpdate(lastSentSeq: Long?, entries: List<LogEntry>): LogEventDto? {
    if (lastSentSeq != null && entries.none { it.seq == lastSentSeq }) {
        return LogEventDto(entries.map { it.toDto() }, cleared = true)
    }
    val fresh = entries.filter { lastSentSeq == null || it.seq > lastSentSeq }
    return if (fresh.isEmpty()) null else LogEventDto(fresh.map { it.toDto() }, cleared = false)
}

/** Values of this flow that differ from [sent] and from each other. */
private fun <T> Flow<T>.changesSince(sent: T): Flow<T> = onStart { emit(sent) }.distinctUntilChanged().drop(1)

/**
 * The `export` state of one controller. The Controller publishes a new [ExportState.Processing] instance on every
 * progress tick (and mutates [org.tgbackup.export.ProcessingState] in place), so Processing -> Processing counts as
 * the same state: while it lasts, [ExportState] is sampled every [periodMs] and sent only when the snapshot changed.
 * Any change to another variant is sent at once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun sampledExportStates(
    states: StateFlow<ExportState>,
    periodMs: Long = PROCESSING_SAMPLE_MS,
): Flow<ExportStateDto> = states
    .distinctUntilChanged { a, b -> a is ExportState.Processing && b is ExportState.Processing }
    .flatMapLatest { st ->
        if (st is ExportState.Processing) {
            flow {
                while (true) {
                    // A newer non-Processing value restarts this flow upstream; never send a stale Processing then.
                    val now = states.value as? ExportState.Processing ?: break
                    emit(now.toDto())
                    delay(periodMs)
                }
            }
        } else {
            flowOf(st.toDto())
        }
    }
    .distinctUntilChanged()
