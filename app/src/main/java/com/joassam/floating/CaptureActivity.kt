package com.joassam.floating

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/**
 * 투명 화면: (1) 복사한 문자 읽기 (2) 화면 공유 허락 받기 → CaptureService로 넘김
 * 안드로이드 10부터 클립보드는 화면에 떠 있는 앱만 읽을 수 있어서 잠깐 투명 화면을 띄움
 */
class CaptureActivity : Activity() {

    companion object { private const val REQ = 7001 }

    private var asked = false
    private var clip = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BubbleService.instance?.setVisible(false)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || asked) return
        asked = true
        clip = readClipboard()
        if (clip.isBlank()) {
            Toast.makeText(this, "먼저 주문 문자를 길게 눌러 '복사'해 주세요", Toast.LENGTH_LONG).show()
            BubbleService.instance?.setVisible(true)
            finish()
            return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ)
    }

    private fun readClipboard(): String {
        return try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val c = cm.primaryClip
            if (c != null && c.itemCount > 0) (c.getItemAt(0).coerceToText(this)?.toString() ?: "").trim() else ""
        } catch (e: Exception) { "" }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ) return
        if (resultCode == RESULT_OK && data != null) {
            val s = Intent(this, CaptureService::class.java)
            s.putExtra("resultCode", resultCode)
            s.putExtra("data", data)
            s.putExtra("clip", clip)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(s) else startService(s)
        } else {
            // 화면 공유를 취소하면 주문자 없이 문자만 넘김
            BubbleService.instance?.setVisible(true)
            Common.openOrderApp(this, clip, "")
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
