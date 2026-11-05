package org.tgbackup.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.net.URISyntaxException

/**
 * Links that leave the program's own pages open in the default browser, never inside the window. The webview
 * library has no navigation hook, so [INIT_SCRIPT] (run in every page before its own scripts) catches clicks on
 * `<a>` and `window.open`, and hands the URL to the bound `tgbOpenExternal`; Kotlin opens it only when [allowed].
 */
object ExternalLinks {
    const val BINDING = "tgbOpenExternal"
    private const val MAX_LENGTH = 8_192

    /** Only `http://` and `https://` URLs with a host; anything else (file:, javascript:, custom schemes) is refused. */
    fun allowed(url: String): Boolean {
        if (url.isBlank() || url.length > MAX_LENGTH) return false
        val uri = try {
            URI(url)
        } catch (e: URISyntaxException) {
            return false
        }
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        if (uri.isOpaque) return false
        return !uri.host.isNullOrBlank()
    }

    /** A webview binding gets its JS arguments as a JSON array; the URL is the first one, when it is a string. */
    fun urlFromBindArgs(req: String?): String? {
        if (req == null) return null
        val array = runCatching { Json.parseToJsonElement(req) }.getOrNull() as? JsonArray ?: return null
        val first = array.firstOrNull() as? JsonPrimitive ?: return null
        return if (first.isString) first.content else null
    }

    /**
     * Same-origin links without `target=_blank` (the tabs' `#jobs` and the like) stay in the window. A link to
     * another origin, or with `target=_blank`, goes to [BINDING]; so does every `window.open`.
     */
    val INIT_SCRIPT = """
        (function () {
          if (window.__tgbLinks) return;
          window.__tgbLinks = true;
          function abs(u) { try { return new URL(String(u), location.href).href; } catch (e) { return null; } }
          function external(u) { try { return new URL(u).origin !== location.origin; } catch (e) { return false; } }
          function send(u) { if (u && window.$BINDING) window.$BINDING(u); }
          function onClick(e) {
            if (e.type === 'auxclick' && e.button !== 1) return;
            var t = e.target;
            var a = t && t.closest ? t.closest('a[href]') : null;
            if (!a) return;
            var href = abs(a.getAttribute('href'));
            if (!href) return;
            var blank = (a.getAttribute('target') || '').toLowerCase() === '_blank';
            if (blank || external(href)) {
              e.preventDefault();
              e.stopPropagation();
              send(href);
            } else if (e.shiftKey || e.ctrlKey || e.metaKey || e.button === 1) {
              // A modified click would open our own page in a new, bare window: keep it in this one.
              e.preventDefault();
            }
          }
          document.addEventListener('click', onClick, true);
          document.addEventListener('auxclick', onClick, true);
          window.open = function (u) { send(abs(u)); return null; };
        })();
    """.trimIndent()
}
