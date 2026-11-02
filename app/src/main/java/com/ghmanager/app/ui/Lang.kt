package com.ghmanager.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ghmanager.app.data.Store
import java.util.Locale

object Lang {
    var code by mutableStateOf("en")
    val isAr: Boolean get() = code == "ar"

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
fun t(s: String): String = if (Lang.isAr) (AR[s] ?: s) else s
