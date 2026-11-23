package app.lookout.server

import app.lookout.core.LookoutCore

/** The tiny page at /: a live view and the ROI list. */
object WebPage {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private const val STYLE = """
        :root { color-scheme: light dark; --bg:#f6f6f4; --fg:#1b1b1b; --muted:#6b6b6b; --card:#fff; --line:#ddd; }
        @media (prefers-color-scheme: dark) { :root { --bg:#141414; --fg:#eee; --muted:#9a9a9a; --card:#1e1e1e; --line:#333; } }
        * { box-sizing: border-box; }
        body { margin:0; font: 15px/1.45 system-ui, sans-serif; background:var(--bg); color:var(--fg); }
        main { max-width: 960px; margin: 0 auto; padding: 16px; }
        h1 { font-size: 20px; margin: 0 0 12px; }
        .card { background:var(--card); border:1px solid var(--line); border-radius:10px; padding:14px; margin-bottom:14px; }
        img.live { width:100%; border-radius:6px; background:#000; display:block; }
        code { font: 13px ui-monospace, monospace; word-break: break-all; }
        .muted { color:var(--muted); }
        table { border-collapse: collapse; width:100%; }
        td, th { text-align:left; padding:4px 6px; border-bottom:1px solid var(--line); font-size:14px; }
        input, button { font: inherit; padding: 8px 10px; border-radius: 6px; border: 1px solid var(--line); }
        button { cursor: pointer; }
    """

    fun login(): String = """
        <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Lookout</title><style>$STYLE</style></head><body><main>
        <h1>Lookout</h1>
        <div class="card"><p>Enter a token. The owner token is in Setup on the phone.</p>
        <form method="get" action="/"><input name="token" type="password" placeholder="token" autocomplete="off" style="width:100%;max-width:420px">
        <button type="submit">Open</button></form></div>
        </main></body></html>
    """.trimIndent()

    fun home(core: LookoutCore, token: String): String {
        val t = esc(java.net.URLEncoder.encode(token, "UTF-8"))
        val rois = core.rois.list().joinToString("") { r ->
            "<tr><td>${esc(r.name)}</td><td><code>%.3f, %.3f, %.3f, %.3f</code></td></tr>".format(r.rect.x, r.rect.y, r.rect.w, r.rect.h)
        }.ifEmpty { "<tr><td colspan=2 class=muted>No ROIs yet</td></tr>" }
        return """
            <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Lookout</title><style>$STYLE</style></head><body><main>
            <h1>Lookout <span class="muted">· ${esc(core.deviceName)}</span></h1>
            <div class="card"><img class="live" src="/v1/stream.mjpeg?fps=4&amp;width=960&amp;token=$t" alt="Live view"></div>
            <div class="card"><h2 style="font-size:16px;margin:0 0 8px">ROIs</h2><table>$rois</table></div>
            </main></body></html>
        """.trimIndent()
    }
}
