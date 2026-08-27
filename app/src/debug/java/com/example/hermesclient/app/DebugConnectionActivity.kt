package com.example.hermesclient.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.example.hermesclient.domain.repository.ConnectionRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class DebugConnectionActivity : ComponentActivity() {
    @Inject
    lateinit var connectionRepository: ConnectionRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val baseUrl = intent.getStringExtra(EXTRA_BASE_URL).orEmpty()
        val apiKey = intent.getStringExtra(EXTRA_API_KEY).orEmpty()
        Log.d(TAG, "Starting debug connection bootstrap (urlPresent=${baseUrl.isNotBlank()}, keyPresent=${apiKey.isNotBlank()})")
        lifecycleScope.launch {
            connectionRepository.testAndSave(baseUrl, apiKey).fold(
                onSuccess = {
                    Log.i(TAG, "Hermes connection verified and saved")
                    startActivity(
                        Intent(this@DebugConnectionActivity, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
                onFailure = { error ->
                    Log.w(TAG, "Hermes connection failed: ${error::class.simpleName}")
                    setResult(RESULT_CANCELED, Intent().putExtra(EXTRA_ERROR_TYPE, error::class.simpleName))
                    finish()
                },
            )
        }
    }

    companion object {
        private const val TAG = "HermesDebugConnection"
        const val EXTRA_BASE_URL = "baseUrl"
        const val EXTRA_API_KEY = "apiKey"
        const val EXTRA_ERROR_TYPE = "errorType"
    }
}
