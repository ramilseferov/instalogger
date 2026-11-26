package com.instalogger.app.util

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

object FormatUtils {
    fun formatCompactNumber(count: Long): String {
        if (count < 1000) return count.toString()
        val symbols = DecimalFormatSymbols(Locale.US)
        return when {
            count >= 1_000_000_000 -> {
                val value = count / 1_000_000_000.0
                DecimalFormat("#.##B", symbols).format(value)
            }
            count >= 1_000_000 -> {
                val value = count / 1_000_000.0
                DecimalFormat("#.##M", symbols).format(value)
            }
            count >= 100_000 -> {
                val value = count / 1_000.0
                DecimalFormat("#K", symbols).format(value)
            }
            count >= 10_000 -> {
                val value = count / 1_000.0
                DecimalFormat("#.#K", symbols).format(value)
            }
            count >= 1_000 -> {
                val value = count / 1_000.0
                DecimalFormat("#.##K", symbols).format(value)
            }
            else -> count.toString()
        }
    }

    fun formatCompactNumber(count: Int): String = formatCompactNumber(count.toLong())
}
