package org.tgbackup.desktop.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.tgbackup.app.export.PasswordOrigin
import org.tgbackup.app.export.PasswordRequest
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** How long the export screen's login waits for the 2FA password before it gives up. */
internal val PASSWORD_WAIT: Duration = 10.minutes

/**
 * Runs [block], a login of the export screen (its `login` and `check` routes), with its 2FA wait bounded: a request
 * of the screen ([PasswordOrigin.Screen]) that comes while [block] runs and nobody answered within [timeout] is
 * answered with null, the dialog's `ביטול`, so the login ends with the engine's "ההתחברות בוטלה" and lets go of the
 * account's lock ([ChatHandles.exclusive]).
 *
 * A copying job's login (TakeoutJobRunner, through ExportManager.copyClient) asks with [PasswordOrigin.Job] and
 * waits without a bound, as in Android, even while a screen's login runs. A request already listed when [block]
 * starts is not this login's (another screen login bounds its own), and once [block] ends its timers go.
 */
internal suspend fun <T> withBoundedPasswordWait(
    requests: StateFlow<List<PasswordRequest>>,
    timeout: Duration = PASSWORD_WAIT,
    block: suspend () -> T,
): T = coroutineScope {
    val watch = boundPasswordWait(requests, timeout, except = requests.value)
    try {
        block()
    } finally {
        watch.cancel()
    }
}

/**
 * Answers each screen request of [requests] but [except] with null after [timeout], each with its own timer: a newer
 * request does not take the older one's away (the older one may still hold its account's lock). Job requests are
 * left alone.
 */
internal fun CoroutineScope.boundPasswordWait(
    requests: StateFlow<List<PasswordRequest>>,
    timeout: Duration = PASSWORD_WAIT,
    except: List<PasswordRequest> = emptyList(),
): Job = launch {
    val seen: MutableSet<PasswordRequest> = Collections.newSetFromMap(IdentityHashMap())
    seen.addAll(except)
    requests.collect { list ->
        for (request in list) {
            if (request.origin != PasswordOrigin.Screen || !seen.add(request)) continue
            launch {
                delay(timeout)
                request.answer.complete(null)
            }
        }
    }
}
