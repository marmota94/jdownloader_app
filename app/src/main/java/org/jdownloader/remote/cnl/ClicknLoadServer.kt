package org.jdownloader.remote.cnl

import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import io.ktor.http.*
import javax.inject.Inject
import javax.inject.Singleton
import java.net.URLDecoder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jdownloader.remote.domain.DownloaderRepository
import org.jdownloader.remote.data.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class CnlResponse(val status: Int, val mime: String, val body: String)
@Singleton class ClicknLoadServer @Inject constructor(private val decryptor: CnlDecryptor, private val repo: DownloaderRepository, private val settings: SettingsRepository) {
    private var engine: ApplicationEngine? = null
    private val lock = Mutex()
    private val requests = Semaphore(8)
    suspend fun handle(method: String, path: String, body: String = ""): CnlResponse = lock.withLock {
        if (!settings.settings.first().cnl) return@withLock CnlResponse(403, "text/plain", "disabled")
        when {
            method == "OPTIONS" -> CnlResponse(200, "text/plain", "")
            method == "GET" && path == "/jdcheck.js" -> CnlResponse(200, "application/javascript", "jdownloader=true; var version='9.581';")
            method == "GET" && path == "/crossdomain.xml" -> CnlResponse(200, "text/xml", "<?xml version=\"1.0\"?><cross-domain-policy><allow-access-from domain=\"*\" /></cross-domain-policy>")
            method == "POST" && path in setOf("/flash/add", "/flash/addcrypted2") -> {
                val fields = CnlForm.parse(body)
                val links = if (path.endsWith("addcrypted2")) decryptor.decrypt(fields["jk"].orEmpty(), fields["crypted"].orEmpty()) else fields["urls"].orEmpty()
                require(links.isNotBlank())
                repo.addLinks(links, fields["passwords"] ?: fields["password"].orEmpty(), fields["package"] ?: fields["packageName"].orEmpty())
                CnlResponse(200, "text/plain", "success\r\n")
            }
            else -> CnlResponse(404, "text/plain", "not found")
        }
    }
    @Synchronized fun start() {
        if (engine != null) return
        engine = embeddedServer(CIO, host = "127.0.0.1", port = 9666) {
            routing { route("/{path...}") { handle {
                try { requests.withPermit { withTimeout(35000) {
                    val length = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                    if (length != null && length > CnlForm.MAX_BODY_BYTES) { call.respond(HttpStatusCode.PayloadTooLarge); return@withTimeout }
                    val channel = call.receiveChannel()
                    val bytes = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (!channel.isClosedForRead) {
                        val count = channel.readAvailable(buffer)
                        if (count < 0) break
                        if (bytes.size() + count > CnlForm.MAX_BODY_BYTES) { call.respond(HttpStatusCode.PayloadTooLarge); return@withTimeout }
                        bytes.write(buffer, 0, count)
                    }
                    val result = this@ClicknLoadServer.handle(call.request.httpMethod.value, call.request.path(), bytes.toString("UTF-8"))
                    call.response.header("Access-Control-Allow-Origin", "*")
                    call.response.header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
                    call.response.header("Access-Control-Allow-Headers", "Content-Type")
                    call.response.header("Access-Control-Allow-Private-Network", "true")
                    call.respondText(result.body, ContentType.parse(result.mime), HttpStatusCode.fromValue(result.status))
                } }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { call.respondText("failed\r\n", status = HttpStatusCode.BadRequest) }
            } } }
        }.start(wait = false)
    }
    @Synchronized fun stop() { engine?.stop(100, 500); engine = null }
}
