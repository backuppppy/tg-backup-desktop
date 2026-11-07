package org.tgbackup.desktop.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.tgbackup.app.export.PasswordRequest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** How long the export screen's login waits for the 2FA password before it gives up. */
internal val PASSWORD_WAIT: Duration = 10.minutes

/**
 * Runs [block], a login of the export screen (its `login` and `check` routes), with its 2FA wait bounded: a request
 * that comes while [block] runs and nobody answered within [timeout] is answered with null, the dialog's `ביטול`,
 * so the login ends with the engine's "ההתחברות בוטלה" and lets go of the account's lock ([ChatHandles.exclusive]).
 *
 * A copying job's login (TakeoutJobRunner, through ExportManager.copyClient) asks through the same flow and waits
 * without a bound, as in Android. The engine does not say whose a request is, so the bound goes by time: a request
 * already there when [block] starts is not this login's, and once [block] ends its timers go. A job's request that
 * comes while [block] runs gets the bound as well (both logins wait on one dialog then).
 * The engine (a copy of the Android one) is left as is.
 */
internal suspend fun <T> withBoundedPasswordWait(
    requests: StateFlow<PasswordRequest?>,
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
 * Answers each request of [requests] but [except] with null after [timeout], each with its own timer: a newer
 * request does not take the older one's away (the older one may still hold its account's lock).
 */
internal fun CoroutineScope.boundPasswordWait(
    requests: StateFlow<PasswordRequest?>,
    timeout: Duration = PASSWORD_WAIT,
    except: PasswordRequest? = null,
): Job = launch {
    requests.collect { request ->
        if (request == null || request === except) return@collect
        launch {
            delay(timeout)
            request.answer.complete(null)
        }
    }
}
