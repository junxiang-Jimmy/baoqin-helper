package com.junxi.baoqin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/** 定时报寝执行器：前台服务跑一次「读状态→提交」，完成后发结果通知；未开放/网络差时自动重试。 */
class ReportService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannels(nm)
        val running = Notification.Builder(this, CH_RUNNING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("正在自动报寝…")
            .setContentText("正在提交 ${Prefs.className(this)} 的报寝")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, running, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, running)
        }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "baoqin:report")
        try {
            wl.acquire(60_000L)
        } catch (_: Exception) {
        }

        thread {
            try {
                handleOutcome(ReportEngine.run(config()))
            } finally {
                try {
                    if (wl.isHeld) wl.release()
                } catch (_: Exception) {
                }
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun config() = ReportEngine.Config(
        baseUrl = Prefs.baseUrl(this),
        cohort = Prefs.cohort(this),
        classId = Prefs.classId(this),
        present = Prefs.present(this),
        offCampus = Prefs.offCampus(this),
        leave = Prefs.leave(this),
        suspended = Prefs.suspended(this),
        departed = Prefs.departed(this),
        other = Prefs.other(this),
        noteOverride = Prefs.noteOverride(this),
    )

    private fun handleOutcome(outcome: ReportEngine.Outcome) {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
        when (outcome) {
            is ReportEngine.Outcome.Success -> {
                Prefs.putAttempts(this, today, 0)
                val absenceText = if (outcome.absence > 0) "，未到 ${outcome.absence} 人" else ""
                notifyResult(
                    "报寝成功",
                    "实到 ${outcome.present} / 应到 ${outcome.expected} 人$absenceText · ${outcome.className}",
                )
            }
            is ReportEngine.Outcome.Failure -> {
                val attempts = Prefs.attempts(this, today) + 1
                if (outcome.retryable && attempts <= MAX_ATTEMPTS) {
                    Prefs.putAttempts(this, today, attempts)
                    AlarmScheduler.scheduleRetry(this, RETRY_DELAY_MS)
                    notifyResult(
                        "报寝未完成，稍后自动重试",
                        "${outcome.message}\n（第 $attempts/$MAX_ATTEMPTS 次尝试，${RETRY_DELAY_MIN} 分钟后重试）",
                    )
                } else {
                    Prefs.putAttempts(this, today, 0)
                    notifyResult("报寝失败", outcome.message)
                }
            }
        }
    }

    private fun notifyResult(title: String, text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(this, CH_RESULT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try {
            nm.notify(RESULT_NOTIF_ID, n)
        } catch (_: Exception) {
        }
    }

    private fun ensureChannels(nm: NotificationManager) {
        nm.createNotificationChannel(
            NotificationChannel(CH_RESULT, "报寝结果", NotificationManager.IMPORTANCE_HIGH),
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_RUNNING, "报寝进行中", NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        private const val CH_RESULT = "baoqin_result"
        private const val CH_RUNNING = "baoqin_running"
        private const val NOTIF_ID = 100
        private const val RESULT_NOTIF_ID = 101
        private const val MAX_ATTEMPTS = 8
        private const val RETRY_DELAY_MIN = 5L
        private const val RETRY_DELAY_MS = RETRY_DELAY_MIN * 60_000L
    }
}
