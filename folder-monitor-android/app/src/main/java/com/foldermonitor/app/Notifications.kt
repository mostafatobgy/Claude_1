package com.foldermonitor.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Notifications {
    const val CH_ALERT = "alerts"
    const val CH_SERVICE = "service"
    const val SERVICE_ID = 1

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, ctx.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, ctx.getString(R.string.channel_service), NotificationManager.IMPORTANCE_MIN)
                .apply { setShowBadge(false) }
        )
    }

    private fun openApp(ctx: Context) = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun service(ctx: Context): Notification {
        ensureChannels(ctx)
        val count = Store.folders(ctx).size
        val rules = Store.rules(ctx)
        val last = Store.lastRun(ctx)
        val lastText = if (last > 0) " • آخر فحص " + SimpleDateFormat("HH:mm", Locale.US).format(Date(last)) else ""
        val stop = PendingIntent.getService(
            ctx, 1, Intent(ctx, MonitorService::class.java).setAction(MonitorService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(ctx, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("يراقب $count فولدر")
            .setContentText("فحص كل ${rules.intervalMin} دقيقة$lastText")
            .setContentIntent(openApp(ctx))
            .addAction(0, "إيقاف المراقبة", stop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun canNotify(ctx: Context) =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun updateService(ctx: Context) {
        if (!Store.isEnabled(ctx) || !canNotify(ctx)) return
        try {
            NotificationManagerCompat.from(ctx).notify(SERVICE_ID, service(ctx))
        } catch (e: SecurityException) { }
    }

    fun alert(ctx: Context, a: Alert) {
        ensureChannels(ctx)
        if (!canNotify(ctx)) return
        val n = NotificationCompat.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(ContextCompat.getColor(ctx, R.color.alert))
            .setContentTitle(a.title())
            .setContentText(a.text().lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(a.text()))
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(1000 + (a.path.hashCode() and 0xFFFFFF), n)
        } catch (e: SecurityException) { }
    }
}
