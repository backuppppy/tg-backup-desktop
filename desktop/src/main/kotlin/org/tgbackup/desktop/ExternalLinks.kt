package org.tgbackup.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.net.URISyntaxException

/** What to do with a `tgbOpenExternal(origin, url)` call. */
sealed interface LinkDecision {
    data class Open(val url: String) : LinkDecision
    /** From our page, but not an http(s) URL: logged without the URL. */
    data object Rejected : LinkDecision
    /** Not from our page (another origin, or malformed arguments): ignored. */
    data object WrongOrigin : LinkDecision
}

/** What to do when a document starts in the window ([ExternalLinks.PAGE_BINDING]). */
sealed interface PageAction {
    data object Stay : PageAction
    /** A page that is not ours: navigate back to the entry URL, and open [external] (when http(s)) in the browser. */
    data class GoHome(val external: String?) : PageAction
}

/**
 * Links that leave the program's own pages open in the default browser, never inside the window. The webview
 * library has no navigation hook, so [initScript] (run in every document before its own scripts):
 * - reports every document's URL to [PAGE_BINDING], so a foreign page that got into the window anyway is left at
 *   once ([onPage]);
 * - only on our origin, catches clicks on `<a>`, form submits and `window.open`, and hands the URL with the page's
 *   origin to [BINDING]; Kotlin opens it only when the origin is ours and the URL is [allowed] ([decide]).
 */
object ExternalLinks {
    const val BINDING = "tgbOpenExternal"
    const val PAGE_BINDING = "tgbPage"
    private const val MAX_LENGTH = 8_192

    /** Only `http://` and `https://` URLs with a host; anything else (file:, javascript:, custom schemes) is refused. */
    fun allowed(url: String): Boolean {
        if (url.isBlank() || url.length > MAX_LENGTH) return false
        val uri = parse(url) ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        if (uri.isOpaque) return false
        return !uri.host.isNullOrBlank()
    }

    /** `scheme://host:port` (lower case, explicit port) of an http(s) URL, else null. */
    fun originOf(url: String): String? {
        if (!allowed(url)) return null
        val uri = parse(url) ?: return null
        val scheme = uri.scheme.lowercase()
        val port = if (uri.port != -1) uri.port else if (scheme == "https") 443 else 80
        return "$scheme://${uri.host.lowercase()}:$port"
    }

    /** The binding's JSON arguments are `[origin, url]`; [expectedOrigin] is `http://127.0.0.1:<port>`. */
    fun decide(bindArgs: String?, expectedOrigin: String): LinkDecision {
        val args = jsonArray(bindArgs) ?: return LinkDecision.WrongOrigin
        if (args.size != 2) return LinkDecision.WrongOrigin
        val origin = (args[0] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (origin != expectedOrigin) return LinkDecision.WrongOrigin
        val url = (args[1] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return LinkDecision.Rejected
        return if (allowed(url)) LinkDecision.Open(url) else LinkDecision.Rejected
    }

    /** The URL reported by [PAGE_BINDING] (`[href]`), or null. */
    fun hrefFromBindArgs(bindArgs: String?): String? =
        (jsonArray(bindArgs)?.firstOrNull() as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** Our own pages (and the window's first `about:blank`) stay; anything else sends the window home. */
    fun onPage(href: String, expectedOrigin: String): PageAction {
        if (href == "about:blank") return PageAction.Stay
        if (originOf(href) == originOf(expectedOrigin)) return PageAction.Stay
        return PageAction.GoHome(href.takeIf(::allowed))
    }

    private fun parse(url: String): URI? = try {
        URI(url)
    } catch (e: URISyntaxException) {
        null
    }

    private fun jsonArray(text: String?): JsonArray? =
        text?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() as? JsonArray }

    /**
     * [origin] is `http://127.0.0.1:<port>`. Same-origin links without `target=_blank` (the tabs' `#jobs` and the
     * like) stay in the window. A link or form to another origin, or a link with `target=_blank`, goes to
     * [BINDING]; so does every `window.open`. On any other origin only the page report runs.
     */
    fun initScript(origin: String): String = """
        (function () {
          var ORIGIN = ${JsonPrimitive(origin)};
          try { if (window.$PAGE_BINDING) window.$PAGE_BINDING(location.href); } catch (e) {}
          if (location.origin !== ORIGIN) return;
          if (window.__tgbLinks) return;
          window.__tgbLinks = true;
          function abs(u) { try { return new URL(String(u), location.href).href; } catch (e) { return null; } }
          function external(u) { try { return new URL(u).origin !== ORIGIN; } catch (e) { return false; } }
          function send(u) { if (u && window.$BINDING) window.$BINDING(ORIGIN, u); }
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
          function onSubmit(e) {
            var f = e.target;
            if (!f || !f.getAttribute) return;
            var action = abs(f.getAttribute('action') || location.href);
            if (action && external(action)) {
              e.preventDefault();
              e.stopPropagation();
              send(action);
            }
          }
          document.addEventListener('click', onClick, true);
          document.addEventListener('auxclick', onClick, true);
          document.addEventListener('submit', onSubmit, true);
          window.open = function (u) { send(abs(u)); return null; };
        })();
    """.trimIndent()
}
