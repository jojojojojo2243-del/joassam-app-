package com.joassam.floating

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 주문관리 앱의 + 버튼에서 왔고 권한이 있으면: 플로팅 켜고 바로 홈 화면으로
        if (handleDeepLink(intent)) return
        buildUi()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) refreshStatus()
    }

    private fun handleDeepLink(i: Intent?): Boolean {
        if (i?.data?.scheme != "joassamfloat") return false
        if (!Settings.canDrawOverlays(this)) return false
        startBubble()
        val home = Intent(Intent.ACTION_MAIN)
        home.addCategory(Intent.CATEGORY_HOME)
        home.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(home)
        finish()
        return true
    }

    private fun startBubble() {
        val s = Intent(this, BubbleService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(s) else startService(s)
    }

    private fun btn(text: String, color: Int, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.textSize = 16f
        b.setTextColor(Color.WHITE)
        b.setBackgroundColor(color)
        b.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = 24
        b.layoutParams = lp
        return b
    }

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(48, 96, 48, 48)

        val title = TextView(this)
        title.text = "조아저씨쌈 플로팅"
        title.textSize = 24f
        title.setTextColor(Color.parseColor("#1B2430"))
        root.addView(title)

        val desc = TextView(this)
        desc.text = "사용 방법\n" +
            "1. 아래 '다른 앱 위에 표시 허용'을 켜주세요 (처음 한 번)\n" +
            "2. 주문관리 앱에서 + 버튼을 누르면 플로팅 버튼이 떠요\n" +
            "3. 문자나 카톡에서 주문 문자를 길게 눌러 '복사'\n" +
            "4. 플로팅 [등록] 버튼 → 화면 공유에서 그 채팅 앱 선택\n" +
            "5. 주문자(대화방 이름)와 문자가 주문관리 앱으로 자동 입력돼요"
        desc.textSize = 14f
        desc.setTextColor(Color.parseColor("#5F5E5A"))
        desc.setPadding(0, 24, 0, 8)
        root.addView(desc)

        status = TextView(this)
        status.textSize = 14f
        status.setPadding(0, 16, 0, 0)
        root.addView(status)

        root.addView(btn("1. 다른 앱 위에 표시 허용", Color.parseColor("#854F0B")) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        root.addView(btn("2. 알림 허용 (플로팅 유지에 필요)", Color.parseColor("#185FA5")) {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
            } else {
                Toast.makeText(this, "이 휴대폰은 따로 허용할 필요가 없어요", Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(btn("플로팅 버튼 켜기", Color.parseColor("#2F6F4E")) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "먼저 '다른 앱 위에 표시'를 허용해주세요", Toast.LENGTH_LONG).show()
            } else {
                startBubble()
                Toast.makeText(this, "플로팅 버튼을 켰어요", Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(btn("플로팅 버튼 끄기", Color.parseColor("#5F5E5A")) {
            stopService(Intent(this, BubbleService::class.java))
        })

        val urlLabel = TextView(this)
        urlLabel.text = "주문관리 앱 주소"
        urlLabel.setPadding(0, 40, 0, 0)
        root.addView(urlLabel)
        val urlEdit = EditText(this)
        urlEdit.setText(Common.baseUrl(this))
        urlEdit.isSingleLine = true
        root.addView(urlEdit)
        root.addView(btn("주소 저장", Color.parseColor("#5F5E5A")) {
            Common.setBaseUrl(this, urlEdit.text.toString())
            Toast.makeText(this, "저장했어요", Toast.LENGTH_SHORT).show()
        })

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
        refreshStatus()
    }

    private fun refreshStatus() {
        val overlay = Settings.canDrawOverlays(this)
        val notif = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        status.text = "다른 앱 위에 표시: " + (if (overlay) "허용됨 ✓" else "필요 ✗") +
            "\n알림: " + (if (notif) "허용됨 ✓" else "필요 ✗")
        status.setTextColor(if (overlay && notif) Color.parseColor("#2F6F4E") else Color.parseColor("#C0392B"))
        status.gravity = Gravity.START
    }
}
