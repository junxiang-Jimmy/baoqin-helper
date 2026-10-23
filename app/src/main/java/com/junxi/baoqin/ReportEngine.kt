package com.junxi.baoqin

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * 报寝接口引擎。
 *
 * 网站接口（免登录、无 cookie、无 CSRF）：
 *   GET  /api/classes?cohort=…              班级列表 {classes:[{class_id,name,expected}], mode}
 *   GET  /api/report?class_id=…&cohort=…    当日班级报表 {day, submission_open, phase, times{open,close}, row{…}}
 *   PUT  /api/report?cohort=…               提交/保存报表，JSON 字段固定为
 *        class_id, day, expected, present, off_campus, leave, suspended, departed, other, note
 *        （后台校验：present + 各未到项之和 == expected，多余字段会被拒绝）
 *
 * 人工智能2404班 = class-14，应到 25；报寝窗口每天 21:10 开放、23:50 截止。
 */
object ReportEngine {

    data class ClassInfo(val id: String, val name: String, val expected: Int)

    data class Config(
        val baseUrl: String,
        val cohort: String,
        val classId: String,
        val present: Int,
        val offCampus: Int = 0,
        val leave: Int = 0,
        val suspended: Int = 0,
        val departed: Int = 0,
        val other: Int = 0,
        val noteOverride: String = "",
    )

    sealed class Outcome {
        data class Success(
            val className: String,
            val expected: Int,
            val present: Int,
            val absence: Int,
            val day: String,
        ) : Outcome()

        data class Failure(val message: String, val retryable: Boolean) : Outcome()
    }

    private const val TIMEOUT_MS = 20000

    /** 拉取班级列表（设置页用）。失败时抛异常。 */
    fun fetchClasses(baseUrl: String, cohort: String): List<ClassInfo> {
        val text = http("GET", "$baseUrl/api/classes?cohort=${enc(cohort)}")
        val arr = JSONObject(text).optJSONArray("classes") ?: JSONArray()
        val result = ArrayList<ClassInfo>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            result.add(ClassInfo(o.optString("class_id"), o.optString("name"), o.optInt("expected", 0)))
        }
        return result
    }

    /** 执行一次报寝：读取状态 → 校验 → 组包 → 提交。 */
    fun run(config: Config): Outcome {
        return try {
            runInternal(config)
        } catch (e: ApiError) {
            Outcome.Failure(e.message ?: "请求失败", e.retryable)
        } catch (e: Exception) {
            Outcome.Failure("网络连接失败：${e.message ?: e.javaClass.simpleName}", true)
        }
    }

    private class ApiError(message: String, val retryable: Boolean) : Exception(message)

    private fun runInternal(config: Config): Outcome {
        // 1. 读取今天的班级报表
        val getUrl = "${config.baseUrl}/api/report?class_id=${enc(config.classId)}&cohort=${enc(config.cohort)}"
        val root = JSONObject(http("GET", getUrl))
        val detail0 = root.opt("detail")
        if (detail0 != null) return Outcome.Failure(describeDetail(detail0), false)

        val day = root.optString("day")
        val open = root.optBoolean("submission_open", false)
        val phase = root.optString("phase", "")
        val times = root.optJSONObject("times")
        val openTime = times?.optString("open") ?: ""
        val closeTime = times?.optString("close") ?: ""

        val row = root.getJSONObject("row")
        val className = row.optString("name")
        val expected = row.optInt("expected", config.present)

        // 2. 窗口没开就明确说明（定时模式遇到「未开放」会自动重试）
        if (!open) {
            val message = when {
                phase == "not_open" -> "今天的报寝 $openTime 才开放，现在还没到时间"
                phase.startsWith("approved") -> "今天的报表已被管理员审核锁定，无法再修改"
                else -> "今天的报寝 $closeTime 已截止，如需更正请联系管理员"
            }
            return Outcome.Failure(message, phase == "not_open")
        }

        // 3. 人数对不上直接拒绝提交，避免交上去一张对不平的表
        val absence = config.offCampus + config.leave + config.suspended + config.departed + config.other
        val total = config.present + absence
        if (total != expected) {
            return Outcome.Failure(
                "应到 $expected 人，但实到 ${config.present} + 未到 $absence = $total，对不上。" +
                    "（实到+外宿+请假+休学+离校+其他未到 必须等于应到）请到设置里调整。",
                false,
            )
        }

        // 4. 组包：字段与网页端提交完全一致
        val payload = JSONObject()
        payload.put("class_id", config.classId)
        payload.put("day", day)
        payload.put("expected", expected)
        payload.put("present", config.present)
        payload.put("off_campus", config.offCampus)
        payload.put("leave", config.leave)
        payload.put("suspended", config.suspended)
        payload.put("departed", config.departed)
        payload.put("other", config.other)
        // 备注选填：用户没填就提交空字符串（需求：默认不填）
        payload.put("note", config.noteOverride)

        // 5. 提交（等同网页上点「保存修改」）
        val putUrl = "${config.baseUrl}/api/report?cohort=${enc(config.cohort)}"
        val saved = JSONObject(http("PUT", putUrl, payload.toString().toByteArray(StandardCharsets.UTF_8)))
        val detail1 = saved.opt("detail")
        if (detail1 != null) return Outcome.Failure(describeDetail(detail1), false)

        val newRow = saved.optJSONObject("row") ?: row
        return Outcome.Success(className, expected, newRow.optInt("present", config.present), absence, day)
    }

    private fun describeDetail(d: Any): String {
        return when (d) {
            is String -> d
            is JSONArray -> joinValidation(d)
            else -> "提交内容不符合要求"
        }
    }

    private fun joinValidation(arr: JSONArray): String {
        val parts = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val msg = item.optString("msg", "").removePrefix("Value error, ")
            if (msg.isNotBlank()) parts.add(msg)
        }
        return if (parts.isEmpty()) "提交内容不符合要求" else parts.joinToString("；")
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun http(method: String, urlStr: String, body: ByteArray? = null): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.requestMethod = method
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val stream: InputStream? = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let { readAll(it) } ?: ""
            if (code !in 200..299) {
                throw ApiError(parseErrorMessage(text, code), code >= 500 || code == 429)
            }
            return text
        } finally {
            try {
                conn.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun readAll(input: InputStream): String = try {
        BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { it.readText() }
    } catch (_: Exception) {
        ""
    }

    private fun parseErrorMessage(text: String, code: Int): String {
        return try {
            val o = JSONObject(text)
            when (val d = o.opt("detail")) {
                is String -> d
                is JSONArray -> joinValidation(d)
                else -> "请求失败（HTTP $code）"
            }
        } catch (_: Exception) {
            "请求失败（HTTP $code）"
        }
    }
}
