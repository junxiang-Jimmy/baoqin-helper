package com.junxi.baoqin

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import kotlin.concurrent.thread

/** 打开即报寝：自动读取状态、把实到填成设置值并提交，页面直接显示结果。 */
class MainActivity : Activity() {

    private lateinit var meta: TextView
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnRetry: Button
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        meta = findViewById(R.id.meta)
        statusTitle = findViewById(R.id.status_title)
        statusDetail = findViewById(R.id.status_detail)
        progress = findViewById(R.id.progress)
        btnRetry = findViewById(R.id.btn_retry)
        btnRetry.setOnClickListener { runReport() }
        findViewById<Button>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btn_web).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Prefs.baseUrl(this))))
            } catch (_: Exception) {
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshMeta()
        runReport()
    }

    private fun refreshMeta() {
        val auto = if (Prefs.autoEnabled(this)) {
            String.format("每天 %02d:%02d 自动报寝", Prefs.hour(this), Prefs.minute(this))
        } else {
            "定时报寝未开启（到设置里打开后，每天无需再手动点）"
        }
        val note = Prefs.noteOverride(this)
        val noteText = if (note.isEmpty()) "无" else "已填"
        meta.text = "班级：${Prefs.className(this)}\n$auto\n本次将提交：实到 ${Prefs.present(this)} · 未到 ${Prefs.absenceSummary(this)} · 备注$noteText"
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

    private fun runReport() {
        if (running) return
        running = true
        btnRetry.isEnabled = false
        progress.visibility = View.VISIBLE
        statusTitle.text = "正在报寝…"
        statusTitle.setTextColor(0xFF2A2017.toInt())
        statusDetail.text = "正在读取 ${Prefs.className(this)} 今天的报寝状态并提交"
        thread {
            val outcome = ReportEngine.run(config())
            runOnUiThread {
                running = false
                progress.visibility = View.GONE
                btnRetry.isEnabled = true
                when (outcome) {
                    is ReportEngine.Outcome.Success -> {
                        statusTitle.text = "今日报寝已提交"
                        statusTitle.setTextColor(0xFF2E7D32.toInt())
                        val absenceText =
                            if (outcome.absence > 0) "，未到 ${outcome.absence} 人（${Prefs.absenceSummary(this)}）" else ""
                        statusDetail.text =
                            "实到 ${outcome.present} / 应到 ${outcome.expected} 人$absenceText\n${outcome.className} · ${outcome.day}"
                    }
                    is ReportEngine.Outcome.Failure -> {
                        statusTitle.text = "本次没有提交成功"
                        statusTitle.setTextColor(0xFFC62828.toInt())
                        statusDetail.text = outcome.message
                    }
                }
            }
        }
    }
}
