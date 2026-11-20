package org.jdownloader.remote.presentation

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.serialization.json.*
import kotlinx.coroutines.delay
import org.jdownloader.remote.R
import org.jdownloader.remote.domain.*

@SuppressLint("SetJavaScriptEnabled")
@Composable fun CaptchaScreen(state: RemoteState, vm: RemoteViewModel) {
    var selected by remember { mutableStateOf<Long?>(null) }
    var answer by remember { mutableStateOf("") }
    var resultFormat by remember { mutableStateOf("text") }
    var selectedType by remember { mutableStateOf("") }
    var browser by remember { mutableStateOf<WebView?>(null) }
    LaunchedEffect(browser, selected, state.captcha) {
        while (browser != null && state.captcha != null) {
            delay(400)
            browser?.evaluateJavascript("window.__jdToken||''") { value ->
                runCatching { Json.parseToJsonElement(value).jsonPrimitive.content }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { answer = it }
            }
        }
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        if (state.snapshot?.captchas.isNullOrEmpty()) Text(stringResource(R.string.no_captchas))
        LazyColumn(Modifier.weight(1f)) {
            items(state.snapshot?.captchas.orEmpty()) { job ->
                val id = job.number("id")
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Column(Modifier.padding(12.dp)) {
                    Text(job.text("hoster") + " · " + job.text("type"))
                    Row {
                        TextButton(onClick = {
                            selected = id; answer = ""; selectedType = job.text("type")
                            resultFormat = if (listOf("recaptcha", "hcaptcha", "turnstile").any { selectedType.contains(it, true) }) "rawtoken" else "text"
                            vm.clearCaptcha(); vm.captcha(id, resultFormat)
                        }) { Text(stringResource(R.string.open_captcha)) }
                        TextButton(onClick = { vm.command("/captcha/skip", JsonPrimitive(id), JsonPrimitive("SINGLE")); vm.clearCaptcha() }) { Text(stringResource(R.string.skip)) }
                    }
                } }
            }
        }
        val challenge = state.captcha
        if (challenge != null && selected != null) {
            Text(stringResource(R.string.captcha_hint), style = MaterialTheme.typography.bodySmall)
            val data = CaptchaDocuments.metadata(challenge)
            val raw = if (data is JsonPrimitive) data.content else data.text("captcha")
            val challengeUrl = if (data is JsonObject) data.text("url").ifBlank { data.text("challengeUrl") } else raw
            val image = if (raw.startsWith("data:image/")) raw else if (data is JsonObject) data.text("image") else ""
            val html = if (image.matches(Regex("data:image/(png|jpeg|gif);base64,[A-Za-z0-9+/=\\r\\n]+"))) "<html><body style='margin:0'><img style='max-width:100%' src='$image'/></body></html>" else ""
            val token = if (resultFormat == "rawtoken") CaptchaDocuments.token(selectedType, data) else null
            if (token != null || html.isNotEmpty() || challengeUrl.startsWith("https://")) AndroidView(modifier = Modifier.fillMaxWidth().height(300.dp), factory = { context -> WebView(context).apply {
                browser = this; settings.javaScriptEnabled = true; settings.domStorageEnabled = true; settings.allowFileAccess = false; settings.allowContentAccess = false
                webViewClient = object : WebViewClient() { override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = request.url.scheme != "https" }
            } }, onReset = null, onRelease = { view -> view.stopLoading(); view.destroy(); if (browser === view) browser = null }, update = { view ->
                val value = token?.html ?: html.ifBlank { challengeUrl }
                if (view.tag != value) { view.tag = value; if (token != null) view.loadDataWithBaseURL(token.baseUrl, token.html, "text/html", "UTF-8", null) else if (html.isNotEmpty()) view.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null) else view.loadUrl(challengeUrl) }
            })
            OutlinedTextField(answer, { answer = it }, label = { Text(stringResource(R.string.captcha_answer)) }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { vm.command("/captcha/solve", JsonPrimitive(selected!!), JsonPrimitive(answer), JsonPrimitive(resultFormat)); vm.clearCaptcha(); answer = "" }, enabled = answer.isNotBlank()) { Text(stringResource(R.string.solve)) }
        }
    }
}
