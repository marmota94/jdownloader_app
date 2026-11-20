package org.jdownloader.remote.cnl

import java.net.URLDecoder

object CnlForm {
    const val MAX_BODY_BYTES = 1_400_000
    fun parse(body: String): Map<String, String> {
        require(body.length <= MAX_BODY_BYTES)
        return body.split('&').filter { it.isNotEmpty() }.associate {
            val pair = it.split('=', limit = 2)
            URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
        }
    }
}
