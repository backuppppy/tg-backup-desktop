// One call to the server API. Resolves with the JSON body; a `{"error": "..."}` answer rejects with Error(error).

export async function api(method, path, body) {
  const init = { method, headers: {} };
  if (body !== undefined) {
    init.headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify(body);
  }
  const res = await fetch(path, init);
  let data = null;
  try {
    data = await res.json();
  } catch {
    data = null;
  }
  if (data && typeof data.error === 'string') throw new Error(data.error);
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return data ?? {};
}
