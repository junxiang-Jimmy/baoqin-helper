package com.junxi.baoqin

import android.content.Context
import android.content.SharedPreferences

/** 所有配置的存取。默认值：人工智能2404班 · 实到 25 · 每天 21:15。 */
object Prefs {
    private const val NAME = "baoqin"

    const val DEFAULT_BASE_URL = "https://baoqin.yeer.tech"
    const val DEFAULT_COHORT = "default"
    const val DEFAULT_CLASS_ID = "class-14"
    const val DEFAULT_CLASS_NAME = "人工智能2404班"
    const val DEFAULT_PRESENT = 25
    const val DEFAULT_HOUR = 21
    const val DEFAULT_MINUTE = 15

    private fun sp(c: Context): SharedPreferences =
        c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun baseUrl(c: Context): String = sp(c).getString("base_url", DEFAULT_BASE_URL)!!.trim().trimEnd('/')
    fun cohort(c: Context): String = sp(c).getString("cohort", DEFAULT_COHORT)!!.trim()
    fun classId(c: Context): String = sp(c).getString("class_id", DEFAULT_CLASS_ID)!!
    fun className(c: Context): String = sp(c).getString("class_name", DEFAULT_CLASS_NAME)!!
    fun present(c: Context): Int = sp(c).getInt("present", DEFAULT_PRESENT)
    fun noteOverride(c: Context): String = sp(c).getString("note_override", "")!!.trim()
    fun offCampus(c: Context): Int = sp(c).getInt("off_campus", 0)
    fun leave(c: Context): Int = sp(c).getInt("leave", 0)
    fun suspended(c: Context): Int = sp(c).getInt("suspended", 0)
    fun departed(c: Context): Int = sp(c).getInt("departed", 0)
    fun other(c: Context): Int = sp(c).getInt("other", 0)
    fun absenceTotal(c: Context): Int = offCampus(c) + leave(c) + suspended(c) + departed(c) + other(c)
    fun autoEnabled(c: Context): Boolean = sp(c).getBoolean("auto_enabled", false)
    fun hour(c: Context): Int = sp(c).getInt("hour", DEFAULT_HOUR)
    fun minute(c: Context): Int = sp(c).getInt("minute", DEFAULT_MINUTE)

    fun putBaseUrl(c: Context, v: String) = sp(c).edit().putString("base_url", v).apply()
    fun putCohort(c: Context, v: String) = sp(c).edit().putString("cohort", v).apply()
    fun putClass(c: Context, id: String, name: String) =
        sp(c).edit().putString("class_id", id).putString("class_name", name).apply()

    fun putPresent(c: Context, v: Int) = sp(c).edit().putInt("present", v).apply()
    fun putNoteOverride(c: Context, v: String) = sp(c).edit().putString("note_override", v).apply()

    fun putAbsences(c: Context, off: Int, leave: Int, susp: Int, dep: Int, other: Int) =
        sp(c).edit().putInt("off_campus", off).putInt("leave", leave).putInt("suspended", susp)
            .putInt("departed", dep).putInt("other", other).apply()

    /** 未到人数摘要，如「请假1 · 外宿2」；全 0 返回「无」。 */
    fun absenceSummary(c: Context): String {
        val parts = ArrayList<String>()
        if (offCampus(c) > 0) parts.add("外宿${offCampus(c)}")
        if (leave(c) > 0) parts.add("请假${leave(c)}")
        if (suspended(c) > 0) parts.add("休学${suspended(c)}")
        if (departed(c) > 0) parts.add("离校${departed(c)}")
        if (other(c) > 0) parts.add("其他未到${other(c)}")
        return if (parts.isEmpty()) "无" else parts.joinToString(" · ")
    }
    fun putAuto(c: Context, enabled: Boolean, h: Int, m: Int) =
        sp(c).edit().putBoolean("auto_enabled", enabled).putInt("hour", h).putInt("minute", m).apply()

    /** 定时任务当天已尝试次数（按日期分开记）。 */
    fun attempts(c: Context, day: String): Int = sp(c).getInt("attempts_$day", 0)
    fun putAttempts(c: Context, day: String, n: Int) = sp(c).edit().putInt("attempts_$day", n).apply()
}
