package com.dogukan.toonceviri

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Çeviri sürerken bildirim çubuğunda ilerlemeyi gösteren ön plan hizmeti.
 * Bu bildirim açıkken Android, uygulama arka plandayken bile süreci kapatmaz.
 * Sıra boşalınca kendiliğinden kapanır.
 */
class WorkService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null
    @Volatile private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        ensureChannel(this)
        val first = Core.state.value
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        runCatching { ServiceCompat.startForeground(this, ID, build(first.work, first.queued.size), type) }
            .onFailure {
                stopSelf()
                return START_NOT_STICKY
            }

        if (watcher == null) {
            watcher = scope.launch {
                Core.state
                    .map { it.work to it.queued.size }
                    .distinctUntilChanged()
                    .collect { (work, queued) ->
                        if (work == null && queued == 0) {
                            ServiceCompat.stopForeground(this@WorkService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                            // Bu arada yeni iş geldiyse (daha yeni bir başlatma) hizmet kapanmaz
                            stopSelfResult(lastStartId)
                        } else {
                            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                            runCatching { nm.notify(ID, build(work, queued)) }
                        }
                    }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun build(work: WorkState?, queued: Int): android.app.Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = work?.title ?: "ToonÇeviri"
        val status = buildString {
            append(work?.status ?: "Hazırlanıyor…")
            if (queued > 0) append(" · sırada $queued bölüm")
        }
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(status)
            .setStyle(NotificationCompat.BigTextStyle().bigText(status))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (work != null && work.total > 0) b.setProgress(work.total, work.done, false)
        else b.setProgress(0, 0, true)
        return b.build()
    }

    companion object {
        private const val ID = 1001
        private const val CHANNEL = "ceviri"

        fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL) != null) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Çeviri ilerlemesi", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Bölüm çevrilirken ilerlemeyi gösterir"
                    setShowBadge(false)
                }
            )
        }
    }
}
