package com.example.webtoonviewer

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

val IMG_EXTS = setOf("jpg", "jpeg", "jpe", "jfif", "png", "gif", "webp", "bmp", "avif")

fun extOf(name: String): String = name.substringAfterLast('.', "").lowercase()

fun isImageName(name: String): Boolean = extOf(name) in IMG_EXTS

fun mimeOf(name: String): String = when (extOf(name)) {
    "jpg", "jpeg", "jpe", "jfif" -> "image/jpeg"
    "png" -> "image/png"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "bmp" -> "image/bmp"
    "avif" -> "image/avif"
    else -> "application/octet-stream"
}

/** 1, 2, 10 순서가 되도록 하는 자연 정렬 (파이썬 natkey 와 같은 역할) */
fun natCompare(a: String, b: String): Int {
    val x = a.replace('\\', '/').lowercase()
    val y = b.replace('\\', '/').lowercase()
    var i = 0
    var j = 0
    while (i < x.length && j < y.length) {
        val cx = x[i]
        val cy = y[j]
        if (cx in '0'..'9' && cy in '0'..'9') {
            var ie = i
            while (ie < x.length && x[ie] in '0'..'9') ie++
            var je = j
            while (je < y.length && y[je] in '0'..'9') je++
            val nx = x.substring(i, ie).trimStart('0')
            val ny = y.substring(j, je).trimStart('0')
            if (nx.length != ny.length) return nx.length - ny.length
            val c = nx.compareTo(ny)
            if (c != 0) return c
            i = ie
            j = je
        } else {
            if (cx != cy) return cx.compareTo(cy)
            i++
            j++
        }
    }
    return (x.length - i) - (y.length - j)
}

/** 이미지 앞부분만 읽어서 (폭, 높이)를 구한다. 실패하면 (0, 0) */
fun readSize(open: () -> InputStream): IntArray {
    val o = BitmapFactory.Options()
    o.inJustDecodeBounds = true
    open().use { BitmapFactory.decodeStream(it, null, o) }
    return if (o.outWidth > 0 && o.outHeight > 0) intArrayOf(o.outWidth, o.outHeight) else intArrayOf(0, 0)
}

fun displayName(cr: ContentResolver, uri: Uri): String {
    try {
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val n = c.getString(0)
                if (!n.isNullOrEmpty()) return n
            }
        }
    } catch (_: Exception) {
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
}

interface ImageSource {
    val count: Int
    fun name(i: Int): String
    fun open(i: Int): InputStream
    fun close()
}

/** zip / cbz */
class ZipSource(file: File) : ImageSource {
    private val zf: ZipFile
    private val list: List<Pair<String, ZipEntry>>

    init {
        var z: ZipFile? = null
        var l: List<Pair<String, ZipEntry>> = emptyList()
        var bad = false
        try {
            val nz = ZipFile(file, Charsets.UTF_8)
            z = nz
            l = collect(nz)
            bad = l.any { it.first.contains('\uFFFD') }
        } catch (e: java.util.zip.ZipException) {
            // 파일명이 UTF-8 이 아니거나 zip 이 아님 -> 한글(cp949) 로 다시 시도
            bad = true
        }
        if (bad) {
            val kr = koreanCharset()
            if (kr != null) {
                try {
                    z?.close()
                } catch (_: Exception) {
                }
                val nz = ZipFile(file, kr)   // 여기서도 실패하면 zip 이 아닌 것
                z = nz
                l = collect(nz)
            }
        }
        if (z == null) throw java.util.zip.ZipException("zip 형식이 아닙니다.")
        zf = z
        list = l
    }

    private fun koreanCharset(): Charset? {
        for (n in listOf("x-windows-949", "MS949", "EUC-KR")) {
            try {
                if (Charset.isSupported(n)) return Charset.forName(n)
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun collect(z: ZipFile): List<Pair<String, ZipEntry>> {
        val out = ArrayList<Pair<String, ZipEntry>>()
        val en = z.entries()
        while (en.hasMoreElements()) {
            val e = en.nextElement()
            if (e.isDirectory) continue
            val nm = e.name
            val base = nm.replace('\\', '/').substringAfterLast('/')
            if (nm.contains("__MACOSX") || base.startsWith("._")) continue
            if (isImageName(base)) out.add(Pair(nm, e))
        }
        out.sortWith(Comparator { a, b -> natCompare(a.first, b.first) })
        return out
    }

    override val count: Int get() = list.size
    override fun name(i: Int): String = list[i].first
    override fun open(i: Int): InputStream = zf.getInputStream(list[i].second)
    override fun close() {
        try {
            zf.close()
        } catch (_: Exception) {
        }
    }
}

/** 폴더 선택(SAF) -> 하위 폴더까지 이미지 수집 */
class FolderSource(private val cr: ContentResolver, tree: Uri) : ImageSource {
    private val items = ArrayList<Pair<String, Uri>>()

    init {
        walk(tree, DocumentsContract.getTreeDocumentId(tree), "")
        items.sortWith(Comparator { a, b -> natCompare(a.first, b.first) })
    }

    private fun walk(tree: Uri, docId: String, prefix: String) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        cr.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val mime = c.getString(2) ?: ""
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walk(tree, id, "$prefix$name/")
                } else if (isImageName(name)) {
                    items.add(Pair("$prefix$name", DocumentsContract.buildDocumentUriUsingTree(tree, id)))
                }
            }
        }
    }

    override val count: Int get() = items.size
    override fun name(i: Int): String = items[i].first
    override fun open(i: Int): InputStream =
        cr.openInputStream(items[i].second) ?: throw FileNotFoundException(items[i].first)

    override fun close() {}
}

/** 갤러리/파일 선택 창에서 고른 이미지 여러 장 */
class UriListSource(private val cr: ContentResolver, uris: List<Uri>) : ImageSource {
    private val items = ArrayList<Pair<String, Uri>>()

    init {
        for (u in uris) items.add(Pair(displayName(cr, u), u))
        items.sortWith(Comparator { a, b -> natCompare(a.first, b.first) })
    }

    override val count: Int get() = items.size
    override fun name(i: Int): String = items[i].first
    override fun open(i: Int): InputStream =
        cr.openInputStream(items[i].second) ?: throw FileNotFoundException(items[i].first)

    override fun close() {}
}
