package org.jdownloader.remote.presentation

import java.text.NumberFormat
import java.util.Locale

private val binaryUnits = listOf("B", "KiB", "MiB", "GiB", "TiB", "PiB", "EiB")

internal fun formatByteSize(bytes: Long, locale: Locale): String {
    var value = bytes.coerceAtLeast(0).toDouble()
    var unit = 0
    while (value >= 1024 && unit < binaryUnits.lastIndex) {
        value /= 1024
        unit++
    }
    val number = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }
    // Keep the displayed value below 1024 even when rounded to one decimal.
    if (value >= 1023.95 && unit < binaryUnits.lastIndex) {
        value /= 1024
        unit++
    }
    return "${number.format(value)} ${binaryUnits[unit]}"
}
