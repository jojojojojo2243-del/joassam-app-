package com.joassam.floating

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

object Common {
    const val DEFAULT_URL = "https://joassam-app.vercel.app"
    private const val PREFS = "joassam_floating"
    private const val CHANNEL_ID = "floating"

    fun baseUrl(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("base_url", DEFAULT_URL)!!.trimEnd('/')

    fun setBaseUrl(ctx: Context, url: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("base_url", url.trim().trimEnd('/')).apply()
    }

    fun notification(ctx: Context, text: String): Notification {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "플로팅 버튼", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return Notification.Builder(ctx, CHANNEL_ID)
            .setContentTitle(ctx.getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_input_add)
            .setOngoing(true)
            .build()
    }

    /** 주문관리 앱(PWA) 열기: 복사한 문자와 대화방 이름(주문자)을 넘김 */
    fun openOrderApp(ctx: Context, text: String, sender: String) {
        val url = baseUrl(ctx) + "/?from=float" +
            "&text=" + Uri.encode(text) +
            (if (sender.isNotBlank()) "&sender=" + Uri.encode(sender) else "")
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }
}
