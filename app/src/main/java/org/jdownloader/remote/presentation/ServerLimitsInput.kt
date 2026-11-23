package org.jdownloader.remote.presentation

import java.math.BigDecimal
import org.jdownloader.remote.domain.ServerLimits

internal fun speedLimitText(bytes: Int): String = BigDecimal(bytes).divide(BigDecimal(1024)).stripTrailingZeros().toPlainString()

internal fun parseServerLimits(speed: String, downloads: String, chunks: String = "1", perHost: String = "1"): ServerLimits? = runCatching {
    val normalized = speed.trim().replace(',', '.')
    require(normalized.length in 1..24 && normalized.matches(Regex("[0-9]+(?:\\.[0-9]{1,10})?")))
    val amount = normalized.toBigDecimal()
    val bytes = amount.multiply(BigDecimal(1024)).setScale(0, java.math.RoundingMode.HALF_UP).intValueExact()
    require(amount.signum() == 0 || bytes > 0)
    ServerLimits(bytes, downloads.trim().toInt(), chunks.trim().toInt(), perHost.trim().toInt())
}.getOrNull()
