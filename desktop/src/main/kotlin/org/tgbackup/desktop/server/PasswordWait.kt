package org.tgbackup.desktop.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.tgbackup.app.export.PasswordRequest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** How long the export session's login waits for the 2FA password before it gives up. */
internal val PASSWORD_WAIT: Duration = 10.minutes

/**
 * Bounds the export session's 2FA wait: a request nobody answered within [timeout] is answered with null, the
 * dialog's `ביטול`, so the login ends with the engine's "ההתחברות בוטלה" and lets go of the account's lock
 * ([ChatHandles.exclusive]). The engine (a copy of the Android one) waits without a bound; it is left as is.
 */
internal fun CoroutineScope.boundPasswordWait(
    requests: StateFlow<PasswordRequest?>,
    timeout: Duration = PASSWORD_WAIT,
): Job = launch {
    // A new request (or none) cancels the wait for the previous one.
    requests.collectLatest { request ->
        if (request == null) return@collectLatest
        delay(timeout)
        request.answer.complete(null)
    }
}
