// The export session's 2FA requests (pure; unit-tested under node). The server sends every waiting request as
// `passwords`, oldest first, and the oldest alone as `password` (all an older server sends).

/** The waiting requests, oldest first. */
export function waitingPasswords(state) {
  if (Array.isArray(state.passwords) && state.passwords.length) return state.passwords;
  return state.password ? [state.password] : [];
}

/** The request the dialog shows: the oldest one not answered yet (`answered`, a Set of ids), or null. */
export function nextPassword(state, answered) {
  return waitingPasswords(state).find((r) => !answered.has(r.id)) ?? null;
}

/** Who asks, a job (`משימה`) or the export screen (`ייצוא`), and the account's name; null when not told. */
export function passwordAsker(request, accounts = []) {
  if (request.origin !== 'job' && request.origin !== 'screen') return null;
  const account = request.accountId ? (accounts.find((a) => a.id === request.accountId)?.name || request.accountId) : '';
  return { who: request.origin === 'job' ? 'משימה' : 'ייצוא', account };
}
