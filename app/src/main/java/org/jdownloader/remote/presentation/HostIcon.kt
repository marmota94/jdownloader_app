package org.jdownloader.remote.presentation

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jdownloader.remote.R

internal fun hostIconUrl(host: String): String? {
    if (host.length !in 3..253 || !host.matches(Regex("(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,63}"))) return null
    return "https://www.google.com/s2/favicons?domain=$host&sz=32"
}

private val iconCache = LruCache<String, ImageBitmap>(64)
private val iconClient by lazy { HttpClient(OkHttp) { install(HttpTimeout) { requestTimeoutMillis = 5000 } } }

@Composable internal fun HostIcon(host: String, enabled: Boolean = true) {
    var icon by remember(host) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(host) {
        val url = hostIconUrl(host) ?: return@LaunchedEffect
        icon = iconCache.get(host) ?: try {
            withContext(Dispatchers.IO) {
                val response = iconClient.get(url)
                if (response.status.value != 200) return@withContext null
                val bytes = response.body<ByteArray>()
                if (bytes.size > 65536) return@withContext null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth !in 1..128 || bounds.outHeight !in 1..128) return@withContext null
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()?.also { iconCache.put(host, it) }
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }
    val bitmap = icon
    if (bitmap != null) Image(bitmap, null, Modifier.size(24.dp),
        colorFilter = if (enabled) null else ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }))
    else Icon(painterResource(R.drawable.ic_transfer_file), null, Modifier.size(24.dp))
}
