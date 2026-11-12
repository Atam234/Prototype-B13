package com.zeroinstall.relay.data

import android.content.Context
import com.zeroinstall.relay.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI

/** Small key/value settings (cloud endpoint) stored in SharedPreferences. */
class RelaySettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _cloudEndpoint = MutableStateFlow(
        prefs.getString(KEY_CLOUD_ENDPOINT, null) ?: BuildConfig.DEFAULT_CLOUD_ENDPOINT
    )

    val cloudEndpointFlow: StateFlow<String> = _cloudEndpoint.asStateFlow()
    val cloudEndpoint: String get() = _cloudEndpoint.value
    val serverPort: Int get() = BuildConfig.RELAY_SERVER_PORT

    /** Returns false (and keeps the old value) when [url] is not an absolute http(s) URL. */
    fun setCloudEndpoint(url: String): Boolean {
        val trimmed = url.trim()
        if (!isValidEndpoint(trimmed)) return false
        prefs.edit().putString(KEY_CLOUD_ENDPOINT, trimmed).apply()
        _cloudEndpoint.value = trimmed
        return true
    }

    companion object {
        private const val PREFS_NAME = "relay_settings"
        private const val KEY_CLOUD_ENDPOINT = "cloud_endpoint"

        fun isValidEndpoint(url: String): Boolean = try {
            val uri = URI(url)
            (uri.scheme == "https" || uri.scheme == "http") && !uri.host.isNullOrBlank()
        } catch (e: Exception) {
            false
        }
    }
}
