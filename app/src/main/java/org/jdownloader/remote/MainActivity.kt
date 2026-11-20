package org.jdownloader.remote

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import org.jdownloader.remote.presentation.*
import org.jdownloader.remote.data.RefreshWorker

@AndroidEntryPoint class MainActivity : AppCompatActivity() {
    private val model: RemoteViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RefreshWorker.schedule(this)
        if (savedInstanceState == null) consume(intent)
        setContent { RemoteApp(model) }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); consume(intent) }
    private fun consume(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SEND -> model.incoming(intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty())
            Intent.ACTION_VIEW -> intent.data?.let { uri -> lifecycleScope.launch {
                try {
                    val pair = withContext(Dispatchers.IO) {
                        var name = uri.lastPathSegment.orEmpty()
                        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) name = it.getString(0) }
                        val type = name.substringAfterLast('.', "").lowercase()
                        require(type in setOf("dlc", "ccf", "rsdf"))
                        val bytes = contentResolver.openInputStream(uri)?.use { input ->
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                require(output.size() + count <= 5_000_000)
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        } ?: error("EMPTY_CONTAINER")
                        require(bytes.size <= 5_000_000)
                        type to bytes
                    }
                    model.container(pair.first, pair.second)
                } catch (e: CancellationException) { throw e } catch (_: Exception) { model.failure() }
            } }
        }
    }
}
