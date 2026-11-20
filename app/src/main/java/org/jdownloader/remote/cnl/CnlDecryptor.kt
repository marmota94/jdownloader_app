package org.jdownloader.remote.cnl

import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.util.Base64
import org.jdownloader.remote.data.MyJdCrypto

@Singleton class CnlDecryptor @Inject constructor(@ApplicationContext private val context: Context) {
    private val mutex = Mutex()
    suspend fun decrypt(jk: String, crypted: String): String = mutex.withLock {
        require(jk.length <= 65536 && crypted.length <= 1_400_000)
        val keyHex = withContext(Dispatchers.Main) {
            // Key code runs in a disposable worker, not the WebView's main JS thread.
            // A looping key function can therefore be stopped without terminating the
            // renderer shared by other WebViews in this application.
            val ready = CompletableDeferred<Unit>()
            val view = WebView(context).apply {
                settings.javaScriptEnabled = true; settings.allowFileAccess = false; settings.allowContentAccess = false
                settings.blockNetworkLoads = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) { ready.complete(Unit) }
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?) =
                        WebResourceResponse("text/plain", "UTF-8", "".byteInputStream())
                }
            }
            try { withTimeout(2500) {
                view.loadDataWithBaseURL("https://cnl-key.invalid/", "<html><head><meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; script-src 'unsafe-inline' 'unsafe-eval' blob:; worker-src blob:; connect-src 'none'\"></head><body></body></html>", "text/html", "UTF-8", null)
                ready.await()
                val source = "'use strict';self.fetch=undefined;self.WebSocket=undefined;self.importScripts=undefined;try{" + jk + ";postMessage(String(f()));}catch(e){postMessage('');}"
                val quoted = JsonPrimitive(source).toString()
                evaluate(view, "window.__cnlKey=null;window.__cnlWorker=new Worker(URL.createObjectURL(new Blob([$quoted],{type:'application/javascript'})));window.__cnlWorker.onmessage=function(e){window.__cnlKey=typeof e.data==='string'?e.data:'';window.__cnlWorker.terminate();};window.__cnlWorker.onerror=function(){window.__cnlKey='';window.__cnlWorker.terminate();};")
                var result: String? = null
                while (result == null) {
                    delay(20)
                    val value = evaluate(view, "window.__cnlKey")
                    if (value != "null") result = Json.parseToJsonElement(value).jsonPrimitive.content
                }
                result
            } } finally {
                // This runs on the page thread even if the worker is busy forever.
                view.evaluateJavascript("if(window.__cnlWorker)window.__cnlWorker.terminate();", null)
                view.stopLoading()
                view.destroy()
            }
        }
        decode(keyHex, crypted)
    }
    private suspend fun evaluate(view: WebView, script: String): String = suspendCancellableCoroutine { continuation ->
        view.evaluateJavascript(script) { value -> if (continuation.isActive) continuation.resumeWith(Result.success(value)) }
    }
    companion object {
        fun decode(hexKey: String, crypted: String): String {
            require(hexKey.matches(Regex("[a-fA-F0-9]{32}")))
            val key = MyJdCrypto.unhex(hexKey)
            val bytes = Base64.getMimeDecoder().decode(crypted)
            require(bytes.isNotEmpty() && bytes.size % 16 == 0)
            val cipher = Cipher.getInstance("AES/CBC/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key)) }
            val plain = cipher.doFinal(bytes)
            // CNL payloads use zero padding; also accept correctly formed PKCS padding.
            var end = plain.size
            val pad = plain.last().toInt() and 255
            if (pad in 1..16 && plain.takeLast(pad).all { (it.toInt() and 255) == pad }) end -= pad
            while (end > 0 && plain[end - 1] == 0.toByte()) end--
            return String(plain, 0, end, Charsets.UTF_8).trim()
        }
    }
}
