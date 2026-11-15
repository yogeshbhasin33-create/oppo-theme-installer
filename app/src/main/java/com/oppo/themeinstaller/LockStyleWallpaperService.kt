package com.oppo.themeinstaller

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.File
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/** One drawable piece of an OPPO "lockstyle" manifest. */
sealed class StyleItem
class ImageItem(
    val x: Float, val y: Float, val w: Float, val h: Float,
    val src: String, val srcId: String
) : StyleItem()

class DateItem(
    val color: Int, val format: String, val typeface: String,
    val x: Float, val y: Float, val align: String, val size: Float
) : StyleItem()

class LockStyle(val baseDir: File, val screenWidth: Float, val items: List<StyleItem>) {
    companion object {
        fun load(extractedDir: File): LockStyle? {
            val manifest = File(extractedDir, "advance/manifest.xml")
            if (!manifest.isFile || manifest.length() > 1_000_000L) return null
            val xml = manifest.readText(Charsets.UTF_8)
            if (xml.contains("<!DOCTYPE", ignoreCase = true)) return null
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(InputSource(StringReader(xml)))
            val root = doc.documentElement
            val width = root.getAttribute("screenWidth").toFloatOrNull() ?: 1080f
            val items = ArrayList<StyleItem>()
            val nodes = root.childNodes
            for (i in 0 until nodes.length) {
                val node = nodes.item(i)
                if (node.nodeType != Node.ELEMENT_NODE) continue
                val el = node as org.w3c.dom.Element
                fun num(name: String): Float = el.getAttribute(name).toFloatOrNull() ?: 0f
                when (el.tagName) {
                    "Image" -> items.add(
                        ImageItem(num("x"), num("y"), num("w"), num("h"),
                            el.getAttribute("src"), el.getAttribute("srcid"))
                    )
                    "DateTime" -> {
                        val color = try {
                            Color.parseColor(el.getAttribute("color"))
                        } catch (e: Exception) { Color.WHITE }
                        items.add(
                            DateItem(color, el.getAttribute("format").ifBlank { "dd MMM yyyy" },
                                el.getAttribute("typeface"), num("x"), num("y"),
                                el.getAttribute("align"), num("size"))
                        )
                    }
                }
            }
            return LockStyle(File(extractedDir, "advance"), width, items)
        }
    }
}

class LockStyleWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = StyleEngine()

    inner class StyleEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val bitmaps = HashMap<String, Bitmap?>()
        private val typefaces = HashMap<String, Typeface?>()
        private var style: LockStyle? = null
        private var loaded = false
        private var visible = false

        private val ticker = object : Runnable {
            override fun run() {
                drawFrame()
                if (visible) {
                    val delay = 60_000L - (System.currentTimeMillis() % 60_000L) + 50L
                    handler.postDelayed(this, delay)
                }
            }
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            visible = isVisible
            handler.removeCallbacks(ticker)
            if (isVisible) {
                // Reload so a newly imported theme shows up.
                loaded = false
                bitmaps.clear()
                typefaces.clear()
                handler.post(ticker)
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            drawFrame()
        }

        override fun onDestroy() {
            visible = false
            handler.removeCallbacks(ticker)
            super.onDestroy()
        }

        private fun drawFrame() {
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas() ?: return
                render(canvas)
            } catch (e: Exception) {
                // Never crash the wallpaper process.
            } finally {
                if (canvas != null) {
                    try { holder.unlockCanvasAndPost(canvas) } catch (e: Exception) { }
                }
            }
        }

        private fun render(canvas: Canvas) {
            if (!loaded) {
                style = try {
                    LockStyle.load(File(this@LockStyleWallpaperService.filesDir, "lockstyle"))
                } catch (e: Exception) { null }
                loaded = true
            }
            canvas.drawColor(Color.BLACK)
            val s = style ?: return
            val scale = canvas.width / s.screenWidth
            val now = Calendar.getInstance()
            for (item in s.items) {
                when (item) {
                    is ImageItem -> drawImage(canvas, s, item, scale, now)
                    is DateItem -> drawDate(canvas, s, item, scale)
                }
            }
        }

        private fun drawImage(canvas: Canvas, s: LockStyle, item: ImageItem, scale: Float, now: Calendar) {
            var path = item.src
            if (item.srcId.isNotBlank()) {
                val digit = evalDigit(item.srcId, now) ?: return
                val dot = path.lastIndexOf('.')
                if (dot < 0) return
                path = path.substring(0, dot) + "_" + digit + path.substring(dot)
            }
            val bmp = bitmaps.getOrPut(path) {
                val f = File(s.baseDir, path)
                if (f.isFile && f.canonicalPath.startsWith(s.baseDir.canonicalPath + File.separator))
                    BitmapFactory.decodeFile(f.absolutePath) else null
            } ?: return
            val dst = RectF(item.x * scale, item.y * scale, (item.x + item.w) * scale, (item.y + item.h) * scale)
            canvas.drawBitmap(bmp, null, dst, paint)
        }

        private fun drawDate(canvas: Canvas, s: LockStyle, item: DateItem, scale: Float) {
            val tf = if (item.typeface.isBlank()) null else typefaces.getOrPut(item.typeface) {
                val f = File(s.baseDir, item.typeface)
                try {
                    if (f.isFile && f.canonicalPath.startsWith(s.baseDir.canonicalPath + File.separator))
                        Typeface.createFromFile(f) else null
                } catch (e: Exception) { null }
            }
            val text = try {
                SimpleDateFormat(item.format, Locale.getDefault()).format(Date())
            } catch (e: Exception) { return }
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.color = item.color
            p.textSize = item.size * scale
            if (tf != null) p.typeface = tf
            p.textAlign = when (item.align) {
                "left" -> Paint.Align.LEFT
                "right" -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
            val baseline = item.y * scale - (p.ascent() + p.descent()) / 2f
            canvas.drawText(text, item.x * scale, baseline, p)
        }

        /** Handles the srcid forms used by the sample: #hour24/10, #hour24%10, #minute/10, #minute%10. */
        private fun evalDigit(expr: String, now: Calendar): Int? {
            val m = Regex("#(hour24|hour12|minute)\\s*([/%])\\s*(\\d+)").matchEntire(expr.trim()) ?: return null
            val value = when (m.groupValues[1]) {
                "hour24" -> now.get(Calendar.HOUR_OF_DAY)
                "hour12" -> { val h = now.get(Calendar.HOUR); if (h == 0) 12 else h }
                else -> now.get(Calendar.MINUTE)
            }
            val n = m.groupValues[3].toIntOrNull() ?: return null
            if (n == 0) return null
            return if (m.groupValues[2] == "/") value / n else value % n
        }
    }
}

