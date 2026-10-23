package com.junxi.baoqin

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** 每天定时报寝 + 失败重试的闹钟调度。 */
object AlarmScheduler {
    const val ACTION_RUN = "com.junxi.baoqin.action.RUN_REPORT"
    const val ACTION_RETRY = "com.junxi.baoqin.action.RETRY_REPORT"

    private const val REQ_DAILY = 1001
    private const val REQ_RETRY = 1002

    fun nextTrigger(hour: Int, minute: Int): Calendar {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal
    }

    fun nextRunText(context: Context): String {
        val cal = nextTrigger(Prefs.hour(context), Prefs.minute(context))
        return SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA).format(cal.time)
    }

    fun scheduleDaily(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val at = nextTrigger(Prefs.hour(context), Prefs.minute(context)).timeInMillis
        setBest(am, at, dailyPending(context))
    }

    fun scheduleRetry(context: Context, delayMs: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        setBest(am, System.currentTimeMillis() + delayMs, retryPending(context))
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(dailyPending(context))
        am.cancel(retryPending(context))
    }

    /** 优先精确闹钟；没有权限时退化为允许休眠中执行的非精确闹钟（可能晚几分钟）。 */
    private fun setBest(am: AlarmManager, at: Long, pi: PendingIntent) {
        val exactAllowed = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        try {
            if (exactAllowed) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun dailyPending(context: Context): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).setAction(ACTION_RUN)
        return PendingIntent.getBroadcast(
            context, REQ_DAILY, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun retryPending(context: Context): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).setAction(ACTION_RETRY)
        return PendingIntent.getBroadcast(
            context, REQ_RETRY, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
