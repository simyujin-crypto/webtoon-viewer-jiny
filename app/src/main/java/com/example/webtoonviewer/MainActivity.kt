package com.example.webtoonviewer

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
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
    val temp: File?,
    val seriesIdx: Int = -1      // 연속 보기 폴더 안에서 몇 번째 회차인지 (-1: 연속 보기 아님)
)

/** 연속 보기 폴더: 선택한 폴더 아래의 회차(하위 폴더/압축파일) 목록 */
class Series(val tree: Uri, val episodes: List<Episode>) {
    val prefKey: String = "series:$tree"
}

class MainActivity : AppCompatActivity() {

    companion object {
        const val HOST = "viewer.local"
        const val ORIGIN = "https://viewer.local/"
    }

    private lateinit var web: WebView
    private val io = Executors.newSingleThreadExecutor()

    @Volatile private var loaded: Loaded? = null
    @Volatile private var series: Series? = null
    @Volatile private var busy = false
    @Volatile private var ver = 0
    @Volatile private var autoOn = false
    private var fullscreen = false

    private val folderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) openFolder(uri)
        }
    private val seriesPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) openSeries(uri)
        }
    private val filePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) openArchive(uri, -1)
        }
    private val imagesPicker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) openImages(uris)
        }

    // ------------------------------------------------------------ 생명주기
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cacheDir.listFiles()?.forEach { if (it.name.startsWith("zip_")) it.deleteRecursively() }

        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#1A1B1F"))
        setContentView(web)
        setupWebView()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fullscreen) {
                    applyFullscreen(false, true)
                } else {
                    // 회차 목록이 열려 있으면 목록만 닫고, 아니면 앱 종료
                    web.evaluateJavascript("window.wvBack?window.wvBack():false") { r ->
                        if (r != "true") finish()
                    }
                }
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
                it.temp?.deleteRecursively()
            }
            io.shutdown()
        }
        web.destroy()
        super.onDestroy()
    }

    /** 자동스크롤/자동 넘기기 중에는 볼륨 키로 속도(간격) 조절 */
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

    private fun toast(msg: String, ms: Int = 2200) {
        js("window.wvToast&&window.wvToast(${JSONObject.quote(msg)},$ms)")
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

        val s = series
        if (l != null && s != null && l.seriesIdx >= 0) {
            val so = JSONObject()
            so.put("idx", l.seriesIdx)
            val names = JSONArray()
            for (e in s.episodes) names.put(if (e.isDir) e.name else e.name.substringBeforeLast('.'))
            so.put("names", names)
            o.put("series", so)
        }
        return o.toString()
    }

    // ------------------------------------------------------------ 화면(JS) -> 앱
    inner class Bridge {
        @JavascriptInterface
        fun pick(kind: String) {
            runOnUiThread {
                when (kind) {
                    "folder" -> folderPicker.launch(null)
                    "series" -> seriesPicker.launch(null)
                    "file" -> filePicker.launch(arrayOf("*/*"))
                    "images" -> imagesPicker.launch(arrayOf("image/*"))
                }
            }
        }

        /** 이전 파일(-1) / 다음 파일(+1) */
        @JavascriptInterface
        fun navEpisode(delta: Int) {
            navigate(delta)
        }

        /** 회차 목록에서 고른 번호 열기 */
        @JavascriptInterface
        fun openEpisodeAt(i: Int) {
            if (busy) {
                toast("불러오는 중이에요. 잠시만 기다려 주세요.")
                return
            }
            openEpisode(i)
        }

        @JavascriptInterface
        fun setAuto(on: Boolean) {
            autoOn = on
            runOnUiThread { web.keepScreenOn = on }   // 자동 스크롤/넘기기 중 화면 꺼짐 방지
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
        if (isImageName(name)) openImages(listOf(uri)) else openArchive(uri, -1)
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
        busy = true
        toast(msg, 0)
        io.execute {
            try {
                val l = task()
                if (l.source.count == 0) {
                    l.source.close()
                    l.temp?.deleteRecursively()
                    throw IllegalStateException("이미지 파일을 찾을 수 없습니다.")
                }
                val old = loaded
                loaded = l
                ver++
                if (old != null) {
                    old.source.close()
                    old.temp?.deleteRecursively()
                }
                val s = series
                if (s != null && l.seriesIdx >= 0) {
                    getSharedPreferences("viewer", Context.MODE_PRIVATE)
                        .edit().putInt(s.prefKey, l.seriesIdx).apply()   // 마지막으로 본 회차 기억
                }
                js("window.wvReload&&window.wvReload()")
            } catch (e: Throwable) {
                toast("열기 실패: " + (e.message ?: e.toString()), 4500)
            } finally {
                busy = false
            }
        }
    }

    private fun openFolder(uri: Uri) {
        series = null
        loadAsync("폴더를 불러오는 중...") {
            val src = FolderSource(contentResolver, uri)
            val title = DocumentFile.fromTreeUri(this, uri)?.name ?: "폴더"
            Loaded(src, title, "dir:$uri", measure(src), null)
        }
    }

    /** seriesIdx >= 0 이면 연속 보기 회차로 여는 것 */
    private fun openArchive(uri: Uri, seriesIdx: Int) {
        if (seriesIdx < 0) series = null
        val name = displayName(contentResolver, uri)
        loadAsync("압축파일을 불러오는 중...") {
            val tmp = File(cacheDir, "zip_" + System.nanoTime() + ".tmp")
            var exDir: File? = null
            try {
                val ins = contentResolver.openInputStream(uri) ?: throw java.io.FileNotFoundException(name)
                ins.use { input -> tmp.outputStream().use { output -> input.copyTo(output, 1 shl 16) } }
                val size = tmp.length()
                val key = "zip:" + name.lowercase() + ":" + size
                val title = name.substringBeforeLast('.')
                val ext = extOf(name)
                if (looks7z(tmp) || ext == "7z" || ext == "cb7") {
                    js("window.wvToast&&window.wvToast('7z 압축을 푸는 중... (파일이 크면 오래 걸려요)',0)")
                    val dir = File(cacheDir, "zip_ex_" + System.nanoTime())
                    exDir = dir
                    val src = extract7z(tmp, dir)
                    tmp.delete()
                    Loaded(src, title, key, measure(src), dir, seriesIdx)
                } else {
                    val src = ZipSource(tmp)
                    Loaded(src, title, key, measure(src), tmp, seriesIdx)
                }
            } catch (e: java.util.zip.ZipException) {
                tmp.delete()
                exDir?.deleteRecursively()
                throw IllegalStateException("zip, cbz, 7z 형식만 열 수 있어요. rar 는 zip 이나 7z 로 다시 압축해 주세요.")
            } catch (e: Throwable) {
                tmp.delete()
                exDir?.deleteRecursively()
                throw e
            }
        }
    }

    private fun openImages(uris: List<Uri>) {
        series = null
        loadAsync("이미지를 불러오는 중...") {
            val src = UriListSource(contentResolver, uris)
            val first = if (src.count > 0) src.name(0) else ""
            Loaded(src, "${src.count}개 이미지", "img:${first.lowercase()}:${src.count}", measure(src), null)
        }
    }

    // ------------------------------------------------------------ 연속 보기 (이전/다음 파일)
    /** 회차(하위 폴더/압축파일)들이 들어 있는 상위 폴더를 골랐을 때 */
    private fun openSeries(tree: Uri) {
        busy = true
        toast("회차 목록을 불러오는 중...", 0)
        io.execute {
            try {
                val eps = listEpisodes(contentResolver, tree)
                if (eps.isEmpty()) {
                    throw IllegalStateException("회차(하위 폴더나 zip/cbz/7z 파일)를 찾지 못했어요. 회차들이 들어 있는 상위 폴더를 선택해 주세요.")
                }
                val ser = Series(tree, eps)
                val last = getSharedPreferences("viewer", Context.MODE_PRIVATE).getInt(ser.prefKey, 0)
                val start = if (last in eps.indices) last else 0
                series = ser
                busy = false
                openEpisode(start)
            } catch (e: Throwable) {
                busy = false
                toast("열기 실패: " + (e.message ?: e.toString()), 4500)
            }
        }
    }

    private fun openEpisode(i: Int) {
        val s = series ?: return
        if (i < 0 || i >= s.episodes.size) return
        val e = s.episodes[i]
        if (e.isDir) {
            loadAsync("폴더를 불러오는 중...") {
                val src = FolderSource(contentResolver, s.tree, e.docId)
                Loaded(src, e.name, "ser:" + s.tree + ":" + e.docId, measure(src), null, i)
            }
        } else {
            openArchive(DocumentsContract.buildDocumentUriUsingTree(s.tree, e.docId), i)
        }
    }

    private fun navigate(delta: Int) {
        val s = series
        val l = loaded
        if (s == null || l == null || l.seriesIdx < 0) {
            toast("'연속 보기 폴더'로 열었을 때만 이전/다음 파일을 쓸 수 있어요.", 3000)
            return
        }
        if (busy) {
            toast("불러오는 중이에요. 잠시만 기다려 주세요.")
            return
        }
        val t = l.seriesIdx + delta
        if (t < 0) {
            toast("첫 번째 파일입니다.")
        } else if (t >= s.episodes.size) {
            toast("마지막 파일입니다.")
        } else {
            openEpisode(t)
        }
    }
}
