package org.jdownloader.remote.presentation

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.json.*
import org.jdownloader.remote.R
import org.jdownloader.remote.cnl.*

private fun cnlUri(url: String): Boolean = runCatching {
    val uri = Uri.parse(url)
    uri.scheme in setOf("http", "https") && uri.host in setOf("127.0.0.1", "localhost") && uri.port == 9666 && uri.userInfo == null
}.getOrDefault(false)
class CnlBridge(private val server: ClicknLoadServer, private val reply: (String, CnlResponse) -> Unit) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val permits = Semaphore(8)
    // JavaScriptInterface calls are synchronous in the renderer. Return immediately:
    // CNL2 may need another WebView in the same renderer to evaluate its key function.
    @JavascriptInterface fun request(id: String, url: String, method: String, body: String) {
        if (!id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) return
        val allowed = cnlUri(url) && body.length <= CnlForm.MAX_BODY_BYTES
        val acquired = allowed && permits.tryAcquire()
        scope.launch {
            val result = if (!allowed) CnlResponse(403, "text/plain", "denied")
                else if (!acquired) CnlResponse(429, "text/plain", "busy") else try {
                    withTimeout(35000) { server.handle(method.uppercase(), Uri.parse(url).path.orEmpty(), body) }
                } catch (e: TimeoutCancellationException) { CnlResponse(504, "text/plain", "timeout") }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { CnlResponse(400, "text/plain", "failed") }
                finally { permits.release() }
            withContext(Dispatchers.Main) { reply(id, result) }
        }
    }
    fun close() { scope.cancel() }
}
private val interception = """(function(){
if(window.__jdCnlInstalled) return; window.__jdCnlInstalled=true;
function local(u){try{var x=new URL(u,location.href);return ['127.0.0.1','localhost'].includes(x.hostname)&&x.port==='9666'&&['http:','https:'].includes(x.protocol);}catch(e){return false;}}
function body(b){if(b instanceof FormData){var p=new URLSearchParams();b.forEach(function(v,k){if(typeof v==='string')p.append(k,v);});return p.toString();}return b?String(b):'';}
var pending=new Map(), serial=0, prefix=Date.now().toString(36)+'_'+Math.random().toString(36).slice(2)+'_';
window.__jdCnlReply=function(id,result){var p=pending.get(id);if(!p)return;pending.delete(id);clearTimeout(p.timer);p.resolve(result);};
function send(u,m,b){return new Promise(function(resolve,reject){if(pending.size>=32){reject(new Error('CNL busy'));return;}var id=prefix+(++serial);var timer=setTimeout(function(){pending.delete(id);reject(new Error('CNL timeout'));},40000);pending.set(id,{resolve:resolve,reject:reject,timer:timer});try{JDCnl.request(id,new URL(u,location.href).href,m,body(b));}catch(e){clearTimeout(timer);pending.delete(id);reject(e);}});}
var fetchOriginal=window.fetch;
window.fetch=function(input,options){var u=typeof input==='string'?input:input.url;if(!local(u))return fetchOriginal.apply(this,arguments);options=options||{};if(input instanceof Request){return input.clone().text().then(function(b){return send(u,options.method||input.method,options.body||b);}).then(function(r){return new Response(r.body,{status:r.status});});}return send(u,options.method||'GET',options.body).then(function(r){return new Response(r.body,{status:r.status});});};
var open=XMLHttpRequest.prototype.open, post=XMLHttpRequest.prototype.send;
XMLHttpRequest.prototype.open=function(m,u){this.__cnl=local(u)?{m:m,u:u}:null;return open.apply(this,arguments);};
XMLHttpRequest.prototype.send=function(b){if(!this.__cnl)return post.apply(this,arguments);var self=this;send(this.__cnl.u,this.__cnl.m,b).then(function(r){['readyState','status','responseText','response'].forEach(function(k){Object.defineProperty(self,k,{configurable:true,value:k==='readyState'?4:k==='status'?r.status:r.body});});self.dispatchEvent(new Event('readystatechange'));self.dispatchEvent(new Event('load'));self.dispatchEvent(new Event('loadend'));}).catch(function(){self.dispatchEvent(new Event('error'));self.dispatchEvent(new Event('loadend'));});};
function submit(form){var u=form.action;if(!local(u))return false;send(u,(form.method||'POST').toUpperCase(),new FormData(form)).then(function(r){window.dispatchEvent(new CustomEvent('jd-cnl-result',{detail:r}));}).catch(function(){window.dispatchEvent(new CustomEvent('jd-cnl-result',{detail:{status:504,body:'timeout'}}));});return true;}
document.addEventListener('submit',function(e){if(submit(e.target)){e.preventDefault();e.stopImmediatePropagation();}},true);
var nativeSubmit=HTMLFormElement.prototype.submit;HTMLFormElement.prototype.submit=function(){if(!submit(this))return nativeSubmit.apply(this,arguments);};
})();
""".trimIndent()

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable fun CnlBrowserScreen(enabled: Boolean, server: ClicknLoadServer) {
    var address by rememberSaveable { mutableStateOf("https://my.jdownloader.org") }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var bridge by remember { mutableStateOf<CnlBridge?>(null) }
    var cnlStatus by remember { mutableStateOf<Int?>(null) }
    Column(Modifier.fillMaxSize()) {
        if (!enabled) Text(stringResource(R.string.cnl_disabled), Modifier.padding(12.dp))
        cnlStatus?.let { status -> Text(stringResource(if (status == 200) R.string.links_added else R.string.request_error), Modifier.padding(horizontal = 12.dp)) }
        Row(Modifier.padding(8.dp)) {
            OutlinedTextField(address, { address = it }, label = { Text(stringResource(R.string.browser_address)) }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton(onClick = { val url = if (address.startsWith("https://")) address else "https://$address"; webView?.loadUrl(url) }) { Text(stringResource(R.string.go)) }
            TextButton(onClick = { if (webView?.canGoBack() == true) webView?.goBack() }) { Text(stringResource(R.string.back)) }
        }
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { context -> WebView(context).apply {
            webView = this
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            settings.allowFileAccess = false; settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            val owner = this
            bridge = CnlBridge(server) { id, result ->
                if (result.body.startsWith("success") || result.status >= 400) cnlStatus = result.status
                val payload = buildJsonObject { put("status", result.status); put("body", result.body) }.toString().replace("<", "\\u003c")
                owner.evaluateJavascript("if(window.__jdCnlReply)window.__jdCnlReply(${JsonPrimitive(id)},$payload);", null)
            }.also { addJavascriptInterface(it, "JDCnl") }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = request.url.scheme != "https" && !cnlUri(request.url.toString())
                override fun onPageFinished(view: WebView, url: String) { view.evaluateJavascript(interception, null) }
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (!cnlUri(request.url.toString())) return null
                    val result = if (request.method == "POST") CnlResponse(405, "text/plain", "POST requires the CNL bridge") else
                        try { runBlocking(Dispatchers.IO) { server.handle(request.method, request.url.path.orEmpty()) } } catch (_: Exception) { CnlResponse(400, "text/plain", "failed") }
                    return WebResourceResponse(result.mime, "UTF-8", result.status, if (result.status == 200) "OK" else "Error", mapOf("Access-Control-Allow-Origin" to "*"), result.body.byteInputStream())
                }
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) WebViewCompat.addDocumentStartJavaScript(this, interception, setOf("*"))
            loadUrl(address)
        } }, onReset = null, onRelease = { view ->
            bridge?.close(); bridge = null
            view.removeJavascriptInterface("JDCnl"); view.stopLoading(); view.destroy()
            if (webView === view) webView = null
        })
    }
}
