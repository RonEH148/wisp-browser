package com.phlox.tvwebbrowser.activity.main

import android.content.Context
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Wisp: reader view (Mozilla Readability, assets/readability, Apache 2.0) and pages saved for
 * reading offline (web archives in the app's own files, kept until deleted - Quit leaves them).
 */
object WispPages {

    // ---------------- reader view ----------------

    private var readabilitySource: String? = null

    private fun readability(context: Context): String =
        readabilitySource ?: context.assets.open("readability/Readability.js").bufferedReader().use { it.readText() }
            .also { readabilitySource = it }

    class Article(val title: String, val html: String, val byline: String?, val site: String?)

    /** Finds the article in the page shown; null when the page has none (e.g. a list or a home page). */
    fun extract(context: Context, webView: WebView, done: (Article?) -> Unit) {
        val js = readability(context) + "\n;(function(){try{" +
                "var a=new Readability(document.cloneNode(true),{charThreshold:300}).parse();" +
                "if(!a||!a.content||(a.length||0)<200)return '';" +
                "return JSON.stringify({t:a.title||document.title||'',c:a.content,b:a.byline||'',s:a.siteName||''});" +
                "}catch(e){return ''}})()"
        webView.evaluateJavascript(js) { raw ->
            val json = try { org.json.JSONTokener(raw).nextValue() as? String } catch (e: Exception) { null }
            if (json.isNullOrEmpty()) { done(null); return@evaluateJavascript }
            try {
                val o = JSONObject(json)
                done(Article(o.optString("t"), o.optString("c"), o.optString("b").ifBlank { null },
                    o.optString("s").ifBlank { null }))
            } catch (e: Exception) {
                done(null)
            }
        }
    }

    private fun esc(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** The reader page: the article only, in a readable size; A− / A+ change the size, ✕ goes back to the page. */
    fun readerHtml(a: Article, pageUrl: String, fontSize: Int): String {
        val host = try { android.net.Uri.parse(pageUrl).host?.removePrefix("www.") } catch (e: Exception) { null } ?: ""
        val meta = listOfNotNull(a.byline, a.site ?: host).joinToString(" · ")
        return """<!doctype html><html data-wisp-reader="1"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="light dark">
<title>${esc(a.title)}</title><style>
:root{--bg:#FAFAF8;--t:#1A1A1C;--m:#6E6E73;--l:#E4E4E7;--a:#2C5FA8}
@media (prefers-color-scheme:dark){:root{--bg:#161618;--t:#E6E6E8;--m:#A0A0A6;--l:#2E2E32;--a:#8AB4F8}}
html,body{margin:0;background:var(--bg);color:var(--t)}
body{font:${fontSize}px/1.65 Georgia,"Noto Serif",serif;-webkit-text-size-adjust:none}
#bar{position:sticky;top:0;display:flex;gap:6px;justify-content:flex-end;padding:8px 12px;background:var(--bg);border-bottom:1px solid var(--l);font:14px system-ui,sans-serif;z-index:9}
#bar button{all:unset;padding:6px 12px;border-radius:16px;border:1px solid var(--l);color:var(--t);cursor:pointer}
article{max-width:680px;margin:0 auto;padding:18px 20px 60px}
h1{font:700 1.45em/1.25 system-ui,sans-serif;margin:.3em 0 .2em}
.meta{font:13px system-ui,sans-serif;color:var(--m);margin-bottom:1.6em}
img,video,figure,iframe{max-width:100%;height:auto}figure{margin:1em 0}figcaption{font-size:.8em;color:var(--m)}
a{color:var(--a)}pre,code{white-space:pre-wrap;font-size:.85em}blockquote{margin:1em 0;padding-left:1em;border-left:3px solid var(--l);color:var(--m)}
table{border-collapse:collapse;max-width:100%;display:block;overflow-x:auto}td,th{border:1px solid var(--l);padding:4px 8px}
</style></head><body>
<div id="bar"><button id="sm">A−</button><button id="lg">A+</button><button id="x">✕ Reader</button></div>
<article><h1>${esc(a.title)}</h1><div class="meta">${esc(meta)}</div>${a.html}</article>
<script>
(function(){var s=$fontSize;
function save(n){try{if(window.TVBro&&TVBro.wispReaderFont){TVBro.wispReaderFont(n);return}}catch(e){}
try{prompt('wisp-reader-font',String(n))}catch(e){}}
function set(n){s=Math.max(13,Math.min(30,n));document.body.style.fontSize=s+'px';save(s)}
function unhide(){var h=document.getElementById('wisp-hidden');if(h)h.remove();
var a=document.querySelectorAll('[data-wisp-ad]');for(var i=0;i<a.length;i++){a[i].style.removeProperty('display');a[i].removeAttribute('data-wisp-ad')}}
unhide();document.addEventListener('DOMContentLoaded',unhide);window.addEventListener('load',unhide);
document.getElementById('sm').onclick=function(){set(s-2)};document.getElementById('lg').onclick=function(){set(s+2)};
document.getElementById('x').onclick=function(){location.replace(${JSONObject.quote(pageUrl)})};})();
</script></body></html>"""
    }

    // ---------------- pages saved offline ----------------

    class Saved(val file: String, val title: String, val url: String, val time: Long)

    private fun prefs(c: Context) = c.getSharedPreferences("wisp_offline", Context.MODE_PRIVATE)

    fun dir(c: Context) = File(c.filesDir, "offline").apply { mkdirs() }

    fun list(c: Context): List<Saved> = try {
        val arr = JSONArray(prefs(c).getString("pages", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Saved(o.getString("f"), o.optString("t"), o.optString("u"), o.optLong("d"))
        }.filter { File(it.file).exists() }.sortedByDescending { it.time }
    } catch (e: Exception) { emptyList() }

    private fun store(c: Context, pages: List<Saved>) {
        val arr = JSONArray()
        for (p in pages) arr.put(JSONObject().put("f", p.file).put("t", p.title).put("u", p.url).put("d", p.time))
        prefs(c).edit().putString("pages", arr.toString()).apply()
    }

    /** Backup restore: adds pages (files already in [dir]); written at once, Wisp restarts right after. */
    fun addRestored(c: Context, pages: List<Saved>) {
        val all = pages + list(c)
        val arr = JSONArray()
        for (p in all) arr.put(JSONObject().put("f", p.file).put("t", p.title).put("u", p.url).put("d", p.time))
        prefs(c).edit().putString("pages", arr.toString()).commit()
    }

    /** Saves the page shown as a web archive (.mht): text, pictures and styles, without its scripts. */
    fun save(c: Context, webView: WebView, title: String, url: String, done: (Boolean) -> Unit) {
        val f = File(dir(c), "page_" + System.currentTimeMillis() + ".mht")
        webView.saveWebArchive(f.absolutePath, false) { path ->
            val ok = path != null && File(path).length() > 0
            if (ok) store(c, listOf(Saved(path!!, title.ifBlank { url }, url, System.currentTimeMillis())) + list(c))
            else f.delete()
            done(ok)
        }
    }

    fun delete(c: Context, page: Saved) {
        File(page.file).delete()
        store(c, list(c).filter { it.file != page.file })
    }

    fun isSavedPage(c: Context, url: String?): Boolean =
        url != null && url.startsWith("file://") && url.removePrefix("file://").startsWith(dir(c).absolutePath)
}
