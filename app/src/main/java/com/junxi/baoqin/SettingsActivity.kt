package com.junxi.baoqin

import android.app.Activity
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/** 设置页：班级、实到人数、备注、定时报寝开关与时间、权限引导、站点高级配置。 */
class SettingsActivity : Activity() {

    private lateinit var spinClass: Spinner
    private lateinit var etPresent: EditText
    private lateinit var etOff: EditText
    private lateinit var etLeave: EditText
    private lateinit var etSusp: EditText
    private lateinit var etDep: EditText
    private lateinit var etOther: EditText
    private lateinit var etNote: EditText
    private lateinit var etBase: EditText
    private lateinit var etCohort: EditText
    private lateinit var swAuto: Switch
    private lateinit var btnTime: Button
    private lateinit var tvStatus: TextView

    private val classList = ArrayList<ReportEngine.ClassInfo>()
    private var selectedId = ""
    private var hour = Prefs.DEFAULT_HOUR
    private var minute = Prefs.DEFAULT_MINUTE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        spinClass = findViewById(R.id.spin_class)
        etPresent = findViewById(R.id.et_present)
        etOff = findViewById(R.id.et_off)
        etLeave = findViewById(R.id.et_leave)
        etSusp = findViewById(R.id.et_susp)
        etDep = findViewById(R.id.et_dep)
        etOther = findViewById(R.id.et_other)
        etNote = findViewById(R.id.et_note)
        etBase = findViewById(R.id.et_base)
        etCohort = findViewById(R.id.et_cohort)
        swAuto = findViewById(R.id.sw_auto)
        btnTime = findViewById(R.id.btn_time)
        tvStatus = findViewById(R.id.tv_status)

        selectedId = Prefs.classId(this)
        hour = Prefs.hour(this)
        minute = Prefs.minute(this)

        etPresent.setText(Prefs.present(this).toString())
        etOff.setText(Prefs.offCampus(this).toString())
        etLeave.setText(Prefs.leave(this).toString())
        etSusp.setText(Prefs.suspended(this).toString())
        etDep.setText(Prefs.departed(this).toString())
        etOther.setText(Prefs.other(this).toString())
        etNote.setText(Prefs.noteOverride(this))
        etBase.setText(Prefs.baseUrl(this))
        etCohort.setText(Prefs.cohort(this))
        swAuto.isChecked = Prefs.autoEnabled(this)
        updateTimeButton()

        btnTime.setOnClickListener {
            TimePickerDialog(
                this,
                { _, h, m ->
                    hour = h
                    minute = m
                    updateTimeButton()
                },
                hour, minute, true,
            ).show()
        }

        findViewById<Button>(R.id.btn_notif).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
            } else {
                tvStatus.text = "当前系统版本无需手动授权通知权限。"
            }
        }

        val btnExact = findViewById<Button>(R.id.btn_exact)
        if (Build.VERSION.SDK_INT >= 31) {
            btnExact.visibility = View.VISIBLE
            btnExact.setOnClickListener {
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")),
                    )
                } catch (e: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                    } catch (_: Exception) {
                    }
                }
            }
        } else {
            btnExact.visibility = View.GONE
        }

        findViewById<Button>(R.id.btn_battery).setOnClickListener {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
                )
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: Exception) {
                }
            }
        }

        findViewById<Button>(R.id.btn_save).setOnClickListener { save() }

        loadClasses()
    }

    private fun updateTimeButton() {
        btnTime.text = String.format("%02d:%02d", hour, minute)
    }

    private fun loadClasses() {
        tvStatus.text = "正在读取班级列表…"
        val base = etBase.text.toString().trim().trimEnd('/')
        val cohort = etCohort.text.toString().trim()
        thread {
            val list = try {
                ReportEngine.fetchClasses(base, cohort)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (list != null && list.isNotEmpty()) {
                    classList.clear()
                    classList.addAll(list)
                    val names = list.map {
                        if (it.id == selectedId) "${it.name}（当前）" else it.name
                    }
                    spinClass.adapter = ArrayAdapter(
                        this, android.R.layout.simple_spinner_dropdown_item, names,
                    )
                    spinClass.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                            if (position in classList.indices) selectedId = classList[position].id
                        }

                        override fun onNothingSelected(parent: AdapterView<*>?) {}
                    }
                    val idx = list.indexOfFirst { it.id == selectedId }
                    if (idx >= 0) {
                        spinClass.setSelection(idx)
                    } else {
                        selectedId = list[0].id
                    }
                    tvStatus.text = ""
                } else {
                    spinClass.adapter = ArrayAdapter(
                        this,
                        android.R.layout.simple_spinner_dropdown_item,
                        listOf(Prefs.className(this) + "（离线 · 未读到班级列表）"),
                    )
                    tvStatus.text = "班级列表读取失败，可检查网络后重新进入本页；已保存的班级仍可正常报寝。"
                }
            }
        }
    }

    private fun save() {
        val present = etPresent.text.toString().trim().toIntOrNull()
        if (present == null || present < 0) {
            tvStatus.text = "实到人数必须是 0 或正整数"
            return
        }
        val off = etOff.text.toString().trim().toIntOrNull() ?: 0
        val leaveN = etLeave.text.toString().trim().toIntOrNull() ?: 0
        val susp = etSusp.text.toString().trim().toIntOrNull() ?: 0
        val dep = etDep.text.toString().trim().toIntOrNull() ?: 0
        val otherN = etOther.text.toString().trim().toIntOrNull() ?: 0
        if (off < 0 || leaveN < 0 || susp < 0 || dep < 0 || otherN < 0) {
            tvStatus.text = "未到人数不能是负数（不填按 0 处理）"
            return
        }
        val base = etBase.text.toString().trim().trimEnd('/')
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            tvStatus.text = "站点地址需要以 http(s):// 开头"
            return
        }
        val info = classList.firstOrNull { it.id == selectedId }
        val className = info?.name ?: Prefs.className(this)
        // 提前校验人数平衡（离线时跳过，由服务器兑底）
        if (info != null) {
            val absence = off + leaveN + susp + dep + otherN
            val total = present + absence
            if (total != info.expected) {
                tvStatus.text = "人数对不上：实到 $present + 未到 $absence = $total，" +
                    "但 ${info.name} 应到 ${info.expected} 人。实到+未到必须等于应到。"
                return
            }
        }
        Prefs.putBaseUrl(this, base)
        Prefs.putCohort(this, etCohort.text.toString().trim())
        Prefs.putClass(this, selectedId, className)
        Prefs.putPresent(this, present)
        Prefs.putAbsences(this, off, leaveN, susp, dep, otherN)
        Prefs.putNoteOverride(this, etNote.text.toString().trim())
        Prefs.putAuto(this, swAuto.isChecked, hour, minute)
        if (swAuto.isChecked) {
            AlarmScheduler.scheduleDaily(this)
            tvStatus.text = "已保存。下一次自动报寝：${AlarmScheduler.nextRunText(this)}"
        } else {
            AlarmScheduler.cancel(this)
            tvStatus.text = "已保存。定时报寝当前为关闭状态。"
        }
        Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
    }
}
