package com.macropad.app.sync

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.macropad.app.MacroPadApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Activity to handle Dropbox OAuth callback.
 * Receives the authorization code from the browser redirect.
 */
class DropboxAuthActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data
        if (uri != null && uri.scheme == "macropad" && uri.host == "oauth") {
            val code = uri.getQueryParameter("code")
            if (code != null) {
                val app = application as MacroPadApplication
                CoroutineScope(Dispatchers.Main).launch {
                    val success = app.dropboxManager.handleAuthCallback(code)
                    // Broadcast result
                    val resultIntent = Intent(ACTION_AUTH_COMPLETE).apply {
                        putExtra(EXTRA_SUCCESS, success)
                    }
                    sendBroadcast(resultIntent)
                    finish()
                }
            } else {
                // Auth was cancelled or failed
                val resultIntent = Intent(ACTION_AUTH_COMPLETE).apply {
                    putExtra(EXTRA_SUCCESS, false)
                }
                sendBroadcast(resultIntent)
                finish()
            }
        } else {
            finish()
        }
    }

    companion object {
        const val ACTION_AUTH_COMPLETE = "com.macropad.app.DROPBOX_AUTH_COMPLETE"
        const val EXTRA_SUCCESS = "success"
    }
}
