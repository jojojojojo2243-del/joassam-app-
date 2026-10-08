package com.joassam.floating

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/** 다른 앱 위에 떠 있는 [등록] 버튼 */
class BubbleService : Service() {

    companion object {
        var instance: BubbleService? = null
    }

    private var wm: WindowManager? = null
    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        val n = Common.notification(this, "플로팅 버튼이 켜져 있어요")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
        showBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        setVisible(true)
        return START_STICKY
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun circle(color: Int): GradientDrawable {
        val g = GradientDrawable()
        g.shape = GradientDrawable.OVAL
        g.setColor(color)
        g.setStroke(dp(2), Color.WHITE)
        return g
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        val w = getSystemService(WINDOW_SERVICE) as WindowManager
        wm = w

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER_HORIZONTAL

        val reg = TextView(this)
        reg.text = "등록"
        reg.textSize = 15f
        reg.setTextColor(Color.WHITE)
        reg.gravity = Gravity.CENTER
        reg.background = circle(Color.parseColor("#2F6F4E"))
        box.addView(reg, LinearLayout.LayoutParams(dp(62), dp(62)))

        val close = TextView(this)
        close.text = "✕"
        close.textSize = 13f
        close.setTextColor(Color.WHITE)
        close.gravity = Gravity.CENTER
        close.background = circle(Color.parseColor("#5F5E5A"))
        val clp = LinearLayout.LayoutParams(dp(30), dp(30))
        clp.topMargin = dp(6)
        box.addView(close, clp)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = resources.displayMetrics.widthPixels - dp(80)
        p.y = (resources.displayMetrics.heightPixels * 0.45).toInt()
        params = p

        // 드래그로 위치 이동, 짧게 누르면 등록
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        reg.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = p.x; startY = p.y; touchX = e.rawX; touchY = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    p.x = startX + (e.rawX - touchX).toInt()
                    p.y = startY + (e.rawY - touchY).toInt()
                    try { w.updateViewLayout(box, p) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (abs(e.rawX - touchX) < dp(8) && abs(e.rawY - touchY) < dp(8)) onRegister()
                    true
                }
                else -> false
            }
        }
        close.setOnClickListener { stopSelf() }

        w.addView(box, p)
        root = box
    }

    private fun onRegister() {
        val i = Intent(this, CaptureActivity::class.java)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
    }

    /** 화면 캡처하는 동안 버튼 숨기기 */
    fun setVisible(v: Boolean) {
        root?.visibility = if (v) View.VISIBLE else View.GONE
    }

    override fun onDestroy() {
        try { root?.let { wm?.removeView(it) } } catch (_: Exception) {}
        root = null
        instance = null
        super.onDestroy()
    }
}
