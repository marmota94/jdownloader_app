package org.jdownloader.remote.presentation

import java.net.URI
import kotlinx.serialization.json.*
import org.jdownloader.remote.domain.text

data class CaptchaDocument(val baseUrl: String, val html: String)

/** Builds provider widgets using the origin and key supplied by the encrypted device API. */
object CaptchaDocuments {
    fun metadata(challenge: JsonElement): JsonElement = if (challenge is JsonPrimitive && challenge.isString && challenge.content.trimStart().startsWith("{"))
        runCatching { Json.parseToJsonElement(challenge.content) }.getOrDefault(challenge) else challenge

    fun token(type: String, challenge: JsonElement): CaptchaDocument? {
        val data = metadata(challenge)
        val key = data.text("siteKey")
        if (key.isBlank() || key.length > 1024) return null
        val url = data.text("siteUrl").ifBlank { data.text("contextUrl") }
        val origin = runCatching { URI(url) }.getOrNull() ?: return null
        if (origin.scheme !in setOf("https", "http") || origin.host.isNullOrBlank() || origin.userInfo != null) return null
        fun js(value: String) = JsonPrimitive(value).toString().replace("<", "\\u003c").replace(">", "\\u003e")
        val normalized = type.lowercase(java.util.Locale.ROOT)
        val script: String
        val render: String
        when {
            "hcaptcha" in normalized -> {
                script = "https://js.hcaptcha.com/1/api.js?onload=jdReady&render=explicit"
                render = "hcaptcha.render('challenge',{sitekey:${js(key)},callback:jdSolved});"
            }
            "turnstile" in normalized -> {
                script = "https://challenges.cloudflare.com/turnstile/v0/api.js?onload=jdReady&render=explicit"
                render = "turnstile.render('#challenge',{sitekey:${js(key)},callback:jdSolved});"
            }
            "recaptcha" in normalized -> {
                val enterprise = data.text("enterprise") == "true"
                val api = if (enterprise) "grecaptcha.enterprise" else "grecaptcha"
                val v3 = runCatching { Json.parseToJsonElement(data.text("v3Action")).jsonObject }.getOrNull()
                val action = (v3?.get("action") as? JsonPrimitive)?.contentOrNull
                val renderMode = if (action != null) java.net.URLEncoder.encode(key, "UTF-8") else "explicit"
                script = if (enterprise) "https://www.google.com/recaptcha/enterprise.js?onload=jdReady&render=$renderMode" else "https://www.google.com/recaptcha/api.js?onload=jdReady&render=$renderMode"
                val invisible = data.text("type").equals("INVISIBLE", true)
                val options = "sitekey:${js(key)},callback:jdSolved" + (if (invisible) ",size:'invisible'" else "") +
                    data.text("stoken").takeIf { it.isNotBlank() }?.let { ",stoken:${js(it)}" }.orEmpty()
                render = if (action != null) "$api.ready(function(){$api.execute(${js(key)},{action:${js(action)}}).then(jdSolved);});"
                    else "var widget=$api.render('challenge',{$options});" + if (invisible) "$api.execute(widget);" else ""
            }
            else -> return null
        }
        return CaptchaDocument(url, "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head><body><div id='challenge'></div><script>window.__jdToken='';function jdSolved(token){if(typeof token==='string')window.__jdToken=token;}function jdReady(){$render}</script><script src='$script' async defer></script></body></html>")
    }
}
