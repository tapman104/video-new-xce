package com.example

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.VideoPlayerScreen
import com.example.ui.VideoPlayerViewModel
import com.example.ui.VoraLog
import com.example.ui.theme.VoraPlayerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        enableEdgeToEdge()
        
        setContent {
            VoraPlayerTheme(darkTheme = true) {
                val viewModel: VideoPlayerViewModel = viewModel()
                
                DisposableEffect(Unit) {
                    val listener = androidx.core.util.Consumer<Intent> { newIntent ->
                        if (newIntent.action == Intent.ACTION_VIEW) {
                            newIntent.data?.let { uri ->
                                val fileName = getFileName(uri) ?: uri.lastPathSegment ?: "Shared Video"
                                viewModel.onVideoSelected(uri, fileName)
                            }
                        }
                    }
                    addOnNewIntentListener(listener)
                    
                    // Handle initial intent
                    if (intent.action == Intent.ACTION_VIEW) {
                        intent.data?.let { uri ->
                            val fileName = getFileName(uri) ?: uri.lastPathSegment ?: "Shared Video"
                            viewModel.onVideoSelected(uri, fileName)
                        }
                    }
                    
                    onDispose {
                        removeOnNewIntentListener(listener)
                    }
                }

                VideoPlayerScreen(viewModel = viewModel)
            }
        }
    }

    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            try {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index != -1) {
                            result = cursor.getString(index)
                        }
                    }
                }
            } catch (e: Exception) {
                VoraLog.player("contentResolver.query failed: ${e.message}")
            }
        }
        if (result == null) {
            result = uri.path?.let { path ->
                val cut = path.lastIndexOf('/')
                if (cut != -1) path.substring(cut + 1) else path
            }
        }
        return result
    }
}
