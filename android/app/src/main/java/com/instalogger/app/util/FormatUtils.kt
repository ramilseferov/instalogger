package com.instalogger.app.util

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

object FormatUtils {
    /**
     * Formats large follower, following, or post numbers into concise, ergonomic labels:
     * e.g., 950 -> "950"
     * 1,450 -> "1.45K"
     * 25,000 -> "25K"
     * 1,250,000 -> "1.25M"
     * Prevents multi-line stat wrapping on narrow mobile screens.
     */
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
