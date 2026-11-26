package com.ghmanager.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ghmanager.app.data.Store
import com.ghmanager.app.data.isArabicUi
import java.util.Locale

object Lang {
    var code by mutableStateOf("en")
    val isAr: Boolean get() = code == "ar"

    init { isArabicUi = { isAr } }

    fun init() { apply(Store.lang) }

    fun set(pref: String) { Store.lang = pref; apply(pref) }

    private fun apply(pref: String) {
        code = when (pref) {
            "ar" -> "ar"
            "en" -> "en"
            else -> if (Locale.getDefault().language == "ar") "ar" else "en"
        }
    }
}

/** Translates a UI string. Unknown strings (repo names, API text) pass through unchanged. */
fun t(s: String): String = if (Lang.isAr) (AR[s] ?: dynamicAr(s)) else s

private val AR_PATTERNS: List<Pair<Regex, String>> = listOf(
    Regex("^(\\d+) stars$") to "$1 نجمة",
    Regex("^(\\d+) open$") to "$1 مفتوح",
    Regex("^Run #(\\d+)$") to "التشغيل #$1",
    Regex("^Done: (.*)$") to "تم: $1",
    Regex("^Failed: (.*)$") to "فشل: $1",
    Regex("^Downloading (.*)\\.\\.\\.$") to "جاري تنزيل $1...",
    Regex("^Comments \\((\\d+)\\)$") to "التعليقات ($1)",
    Regex("^Saved (\\d+) file\\(s\\)$") to "تم حفظ $1 ملف",
    Regex("^Delete (.*)\\?$") to "حذف $1؟",
    Regex("^Merge into (.*)$") to "دمج في $1",
    Regex("^Detected project type: (.*)$") to "نوع المشروع المكتشف: $1",
    Regex("^Add workflow (.*)$") to "إضافة workflow: $1",
    Regex("^Download artifact (.*)$") to "تنزيل الملف: $1",
    Regex("^Create repository (.*)$") to "إنشاء مستودع $1",
    Regex("^Uploaded (\\d+)/(\\d+) files$") to "تم رفع $1 من $2 ملف",
    Regex("^Changes: (\\d+) added or updated, (\\d+) unchanged, (\\d+) skipped, (\\d+) deleted$") to "التغييرات: $1 مضاف أو محدّث، $2 بدون تغيير، $3 متخطى، $4 محذوف"
)
private val dynCache = HashMap<String, String>()

private fun dynamicAr(s: String): String = dynCache.getOrPut(s) {
    for ((re, rep) in AR_PATTERNS) {
        val m = re.matchEntire(s) ?: continue
        var out = rep
        for (i in 1..m.groupValues.lastIndex) out = out.replace("$" + i, t(m.groupValues[i]))
        return@getOrPut out
    }
    s
}
