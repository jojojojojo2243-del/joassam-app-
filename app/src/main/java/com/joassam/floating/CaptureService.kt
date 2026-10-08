package com.joassam.floating

import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions

/** 화면을 한 번 찍어서 채팅방 제목(= 주문자)을 글자 인식 → 주문관리 앱 열기 */
class CaptureService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var finished = false
    private var clip = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = Common.notification(this, "주문 문자 읽는 중…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(2, n)
        }
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        clip = intent.getStringExtra("clip") ?: ""
        val code = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra("data", Intent::class.java)
        else
            @Suppress("DEPRECATION") intent.getParcelableExtra("data")
        if (data == null) { finish(""); return START_NOT_STICKY }

        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = mpm.getMediaProjection(code, data)
            projection = mp
            // 안드로이드 14부터 캡처 전에 콜백 등록이 필수
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { cleanup() }
            }, handler)
            // 투명 화면이 닫히고 채팅 화면이 다시 보일 때까지 잠깐 기다림
            handler.postDelayed({ capture(mp) }, 700)
            // 3초 안에 못 찍으면 주문자 없이 진행
            handler.postDelayed({ finish("") }, 4000)
        } catch (e: Exception) {
            finish("")
        }
        return START_NOT_STICKY
    }

    private fun screenSize(): Triple<Int, Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dpi = resources.displayMetrics.densityDpi
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            Triple(b.width(), b.height(), dpi)
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
            Triple(m.widthPixels, m.heightPixels, dpi)
        }
    }

    private fun capture(mp: MediaProjection) {
        if (finished) return
        try {
            val (w, h, dpi) = screenSize()
            val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            reader = r
            var got = false
            r.setOnImageAvailableListener({ ir ->
                val img = try { ir.acquireLatestImage() } catch (e: Exception) { null }
                if (img == null) return@setOnImageAvailableListener
                if (got || finished) { img.close(); return@setOnImageAvailableListener }
                got = true
                var bmp: Bitmap? = null
                try {
                    val plane = img.planes[0]
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * w
                    val full = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
                    full.copyPixelsFromBuffer(plane.buffer)
                    bmp = Bitmap.createBitmap(full, 0, 0, w, h)
                } catch (e: Exception) {
                } finally {
                    img.close()
                }
                cleanup()
                if (bmp == null) finish("") else recognize(bmp, w, h)
            }, handler)
            display = mp.createVirtualDisplay(
                "joassam-capture", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                r.surface, null, handler
            )
        } catch (e: Exception) {
            finish("")
        }
    }

    /** 화면 위쪽(채팅방 제목 영역)만 잘라서 한글 인식 */
    private fun recognize(bmp: Bitmap, w: Int, h: Int) {
        try {
            val top = (h * 0.025).toInt()
            val height = (h * 0.14).toInt()
            val crop = Bitmap.createBitmap(bmp, 0, top, w, height)
            val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            recognizer.process(InputImage.fromBitmap(crop, 0))
                .addOnSuccessListener { t -> finish(pickTitle(t)) }
                .addOnFailureListener { finish("") }
        } catch (e: Exception) {
            finish("")
        }
    }

    /** 인식된 줄 중에서 '채팅방 제목'으로 보이는 줄 고르기: 한글이 있고, 시간·배터리 같은 게 아니고, 글씨가 가장 큰 줄 */
    private fun pickTitle(t: Text): String {
        var best = ""
        var bestH = 0
        for (block in t.textBlocks) {
            for (line in block.lines) {
                var s = line.text.trim()
                s = s.replace(Regex("^[<‹〈←\\s]+"), "").replace(Regex("[∨˅⌄vV>›〉\\s]+$"), "").trim()
                if (s.length < 2 || s.length > 20) continue
                if (!Regex("[가-힣]").containsMatchIn(s)) continue
                if (Regex("^\\d{1,2}:\\d{2}").containsMatchIn(s)) continue
                if (Regex("(오전|오후)\\s*\\d").containsMatchIn(s)) continue
                if (Regex("^(검색|메시지|채팅|대화|전화|통화|입력|보내기)$").matches(s)) continue
                val hgt = line.boundingBox?.height() ?: 0
                if (hgt > bestH) { bestH = hgt; best = s }
            }
        }
        return best
    }

    private fun cleanup() {
        try { display?.release() } catch (_: Exception) {}
        display = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
    }

    private fun finish(sender: String) {
        if (finished) return
        finished = true
        cleanup()
        BubbleService.instance?.setVisible(true)
        try { Common.openOrderApp(this, clip, sender) } catch (_: Exception) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
