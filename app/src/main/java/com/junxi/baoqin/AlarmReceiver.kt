package com.junxi.baoqin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 闹钟到点：先排上明天的，再启动前台服务执行今天的报寝。 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AlarmScheduler.ACTION_RUN -> {
                if (!Prefs.autoEnabled(context)) return
                AlarmScheduler.scheduleDaily(context)
                start(context)
            }
            AlarmScheduler.ACTION_RETRY -> start(context)
        }
    }

    private fun start(context: Context) {
        try {
            context.startForegroundService(Intent(context, ReportService::class.java))
        } catch (_: Exception) {
        }
    }
}
