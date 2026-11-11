package com.example.webtoonviewer

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.Executors

class Loaded(
    val source: ImageSource,
    val title: String,
    val key: String,
    val sizes: List<IntArray>,
    val temp: File?
)

class MainActivity : AppCompatActivity() {

    companion object {
        const val HOST = "viewer.local"
        const val ORIGIN = "https://viewer.local/"
    }

    private lateinit var web: WebView
    private val io = Executors.newSingleThreadExecutor()

    @Volatile private var loaded: Loaded? = null
    @Volatile private var ver = 0
    @Volatile private var autoOn = false
    private var fullscreen = false

    private val folderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) openFolder(uri)
        }
    private val filePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) openArchive(uri)
        }
    private val imagesPicker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) openImages(uris)
        }

    // ------------------------------------------------------------ 생명주기
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cacheDir.listFiles()?.forEach { if (it.name.startsWith("zip_")) it.delete() }

        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#1A1B1F"))
        setContentView(web)
        setupWebView()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fullscreen) applyFullscreen(false, true) else finish()
            }
        })

        web.loadUrl(ORIGIN)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        if (isFinishing) {
            loaded?.let {
                it.source.close()
                it.temp?.delete()
            }
            io.shutdown()
        }
        web.destroy()
        super.onDestroy()
    }

    /** 자동 스크롤 중에는 볼륨 키로 속도 조절 */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (autoOn && (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val d = if (code == KeyEvent.KEYCODE_VOLUME_UP) 20 else -20
                js("window.wvSpeed&&window.wvSpeed($d)")
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ------------------------------------------------------------ 웹뷰
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.setSupportZoom(false)
        s.builtInZoomControls = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.addJavascriptInterface(Bridge(), "Android")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = serve(request.url)

            // 외부 주소로 이동 금지
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
        }
    }

    private fun js(code: String) {
        web.post { web.evaluateJavascript("try{$code}catch(e){}", null) }
    }

    private fun resp(code: Int, mime: String, body: ByteArray): WebResourceResponse =
        WebResourceResponse(
            mime, "utf-8", code, if (code == 200) "OK" else "Not Found",
            mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(body)
        )

    /** 파이썬 버전의 로컬 웹 서버(GET /, /api/state, /img/N) 역할 */
    private fun serve(u: Uri): WebResourceResponse {
        if (u.host != HOST) return resp(404, "text/plain", "blocked".toByteArray())
        val p = u.path ?: "/"
        if (p == "/" || p == "/index.html") {
            return WebResourceResponse(
                "text/html", "utf-8", 200, "OK",
                mapOf("Cache-Control" to "no-store"), assets.open("index.html")
            )
        }
        if (p == "/api/state") {
            return resp(200, "application/json", stateJson().toByteArray(Charsets.UTF_8))
        }
        if (p.startsWith("/img/")) {
            val l = loaded
            val i = p.substring(5).toIntOrNull()
            if (l != null && i != null && i >= 0 && i < l.source.count) {
                try {
                    return WebResourceResponse(
                        mimeOf(l.source.name(i)), null, 200, "OK",
                        mapOf("Cache-Control" to "private, max-age=86400"), l.source.open(i)
                    )
                } catch (_: Exception) {
                }
            }
        }
        return resp(404, "text/plain", "not found".toByteArray())
    }

    private fun stateJson(): String {
        val l = loaded
        val o = JSONObject()
        o.put("loaded", l != null)
        o.put("title", l?.title ?: "")
        o.put("key", l?.key ?: "")
        o.put("ver", ver)
        val arr = JSONArray()
        l?.sizes?.forEach { arr.put(JSONArray().put(it[0]).put(it[1])) }
        o.put("items", arr)
        return o.toString()
    }

    // ------------------------------------------------------------ 화면(JS) -> 앱
    inner class Bridge {
        @JavascriptInterface
        fun pick(kind: String) {
            runOnUiThread {
                when (kind) {
                    "folder" -> folderPicker.launch(null)
                    "file" -> filePicker.launch(arrayOf("*/*"))
                    "images" -> imagesPicker.launch(arrayOf("image/*"))
                }
            }
        }

        @JavascriptInterface
        fun setAuto(on: Boolean) {
            autoOn = on
            runOnUiThread { web.keepScreenOn = on }   // 자동 스크롤 중 화면 꺼짐 방지
        }

        @JavascriptInterface
        fun setFullscreen(on: Boolean) {
            runOnUiThread { applyFullscreen(on, false) }
        }

        @JavascriptInterface
        fun quit() {
            runOnUiThread { finish() }
        }
    }

    private fun applyFullscreen(on: Boolean, notify: Boolean) {
        fullscreen = on
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (on) c.hide(WindowInsetsCompat.Type.systemBars()) else c.show(WindowInsetsCompat.Type.systemBars())
        if (notify) js("window.wvFs&&window.wvFs($on)")
    }

    // ------------------------------------------------------------ 열기
    private fun handleIntent(i: Intent?) {
        if (i == null || i.action != Intent.ACTION_VIEW) return
        val uri = i.data ?: return
        val name = displayName(contentResolver, uri)
        if (isImageName(name)) openImages(listOf(uri)) else openArchive(uri)
    }

    private fun measure(src: ImageSource): List<IntArray> {
        val out = ArrayList<IntArray>()
        for (i in 0 until src.count) {
            out.add(
                try {
                    readSize { src.open(i) }
                } catch (_: Exception) {
                    intArrayOf(0, 0)
                }
            )
        }
        return out
    }

    private fun loadAsync(msg: String, task: () -> Loaded) {
        js("window.wvToast&&window.wvToast(${JSONObject.quote(msg)},0)")
        io.execute {
            try {
                val l = task()
                if (l.source.count == 0) {
                    l.source.close()
                    l.temp?.delete()
                    throw IllegalStateException("이미지 파일을 찾을 수 없습니다.")
                }
                val old = loaded
                loaded = l
                ver++
                if (old != null) {
                    old.source.close()
                    old.temp?.delete()
                }
                js("window.wvReload&&window.wvReload()")
            } catch (e: Throwable) {
                val m = "열기 실패: " + (e.message ?: e.toString())
                js("window.wvToast&&window.wvToast(${JSONObject.quote(m)},4500)")
            }
        }
    }

    private fun openFolder(uri: Uri) {
        loadAsync("폴더를 불러오는 중...") {
            val src = FolderSource(contentResolver, uri)
            val title = DocumentFile.fromTreeUri(this, uri)?.name ?: "폴더"
            Loaded(src, title, "dir:$uri", measure(src), null)
        }
    }

    private fun openArchive(uri: Uri) {
        val name = displayName(contentResolver, uri)
        loadAsync("압축파일을 불러오는 중...") {
            val tmp = File(cacheDir, "zip_" + System.nanoTime() + ".tmp")
            try {
                val ins = contentResolver.openInputStream(uri) ?: throw java.io.FileNotFoundException(name)
                ins.use { input -> tmp.outputStream().use { output -> input.copyTo(output, 1 shl 16) } }
                val src = ZipSource(tmp)
                Loaded(src, name.substringBeforeLast('.'), "zip:${name.lowercase()}:${tmp.length()}", measure(src), tmp)
            } catch (e: java.util.zip.ZipException) {
                tmp.delete()
                throw IllegalStateException("zip / cbz 형식만 열 수 있어요. rar, 7z 는 zip 으로 다시 압축해 주세요.")
            } catch (e: Throwable) {
                tmp.delete()
                throw e
            }
        }
    }

    private fun openImages(uris: List<Uri>) {
        loadAsync("이미지를 불러오는 중...") {
            val src = UriListSource(contentResolver, uris)
            val first = if (src.count > 0) src.name(0) else ""
            Loaded(src, "${src.count}개 이미지", "img:${first.lowercase()}:${src.count}", measure(src), null)
        }
    }
}
