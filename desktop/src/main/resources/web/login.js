// The phone's login page: POST /api/login {password}; on success the server sets the session cookie and the app opens.
// A wrong password and the wait after too many tries show the server's text.

const form = document.getElementById('login-form');
const field = document.getElementById('password');
const error = document.getElementById('login-error');
const button = form.querySelector('button');

function showError(text) {
  error.textContent = text;
  error.hidden = false;
}

form.addEventListener('submit', async (e) => {
  e.preventDefault();
  error.hidden = true;
  button.disabled = true;
  try {
    const res = await fetch('/api/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ password: field.value }),
    });
    if (res.ok) {
      location.replace('/');
      return;
    }
    let data = null;
    try {
      data = await res.json();
    } catch {
      data = null;
    }
    showError(data && typeof data.error === 'string' ? data.error : `HTTP ${res.status}`);
  } catch (err) {
    showError(String(err.message || err));
  } finally {
    button.disabled = false;
  }
});

field.focus();

// The phone's offline page (sw.js): only on the phone's server, which is the only one with this page.
if ('serviceWorker' in navigator) navigator.serviceWorker.register('/sw.js').catch(() => {});
