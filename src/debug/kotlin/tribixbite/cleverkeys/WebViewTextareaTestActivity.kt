package tribixbite.cleverkeys

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Test-only host for instrumented tests of the real keyboard in a WebView field (test-gap audit
 * 2026-11-28, item 11): one WebView holding two `<textarea>`s, `a` and `b`. Every WebView textarea
 * reports the same package, field id and input type, which is why the keyboard's learn window has
 * to tell them apart by their text. Debug APK only (src/debug), never in a release build; it loads
 * inline HTML, so it needs no network permission.
 */
class WebViewTextareaTestActivity : Activity() {

    lateinit var webView: WebView
        private set

    /** Counted down once the page has loaded. */
    val loaded = CountDownLatch(1)

    @SuppressLint("SetJavaScriptEnabled") // inline test page only
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) = loaded.countDown()
            }
            loadDataWithBaseURL(null, PAGE, "text/html", "utf-8", null)
        }
        setContentView(webView)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    }

    /**
     * Runs [script] in the page and returns its result as JSON text (WebView's
     * evaluateJavascript); call it OFF the main thread, it waits for the main thread.
     */
    fun evaluate(script: String, timeoutMs: Long = 5_000L): String? {
        val done = CountDownLatch(1)
        var result: String? = null
        runOnUiThread { webView.evaluateJavascript(script) { result = it; done.countDown() } }
        return if (done.await(timeoutMs, TimeUnit.MILLISECONDS)) result else null
    }

    /**
     * The centre of textarea [id] on the screen, in pixels: its CSS rect scaled by the page's
     * device pixel ratio, offset by the WebView's place on screen. Call off the main thread.
     */
    fun textareaCentreOnScreen(id: String): Pair<Float, Float> {
        val json = evaluate(
            "(function(){var r=document.getElementById('$id').getBoundingClientRect();" +
                "return [r.left+r.width/2, r.top+r.height/2, window.devicePixelRatio];})()"
        ) ?: error("no answer from the page for textarea $id")
        val (x, y, ratio) = json.trim('[', ']').split(',').map { it.trim().toFloat() }
        val origin = IntArray(2)
        val latch = CountDownLatch(1)
        runOnUiThread { webView.getLocationOnScreen(origin); latch.countDown() }
        latch.await(2, TimeUnit.SECONDS)
        return (origin[0] + x * ratio) to (origin[1] + y * ratio)
    }

    private companion object {
        /** Two textareas one above the other, tall enough to tap above the keyboard. */
        const val PAGE = """<!doctype html><html><head>
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>body{margin:8px;font:16px sans-serif}textarea{display:block;width:95%;height:72px;margin:0 0 16px 0}</style>
</head><body><textarea id="a"></textarea><textarea id="b"></textarea></body></html>"""
    }
}
