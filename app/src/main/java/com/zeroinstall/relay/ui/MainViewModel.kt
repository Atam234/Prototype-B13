package com.zeroinstall.relay.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zeroinstall.relay.ZeroInstallApp
import com.zeroinstall.relay.data.MessageEntity
import com.zeroinstall.relay.service.RelayEvent
import com.zeroinstall.relay.service.RelayRuntimeState
import com.zeroinstall.relay.service.RelayStateHolder
import com.zeroinstall.relay.sync.AutoSyncWorker
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Arrays

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = (app as ZeroInstallApp).graph

    val messages: StateFlow<List<MessageEntity>> = graph.repository.observeMessages()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val relayState: StateFlow<RelayRuntimeState> = RelayStateHolder.state
    val events: StateFlow<List<RelayEvent>> = RelayStateHolder.events
    val cloudEndpoint: StateFlow<String> = graph.settings.cloudEndpointFlow

    private val _isEncrypting = MutableStateFlow(false)
    val isEncrypting: StateFlow<Boolean> = _isEncrypting.asStateFlow()

    private val _notices = Channel<String>(Channel.BUFFERED)
    val notices: Flow<String> = _notices.receiveAsFlow()

    fun queueMessage(recipient: String, body: String, sharedSecret: String, onQueued: () -> Unit) {
        if (_isEncrypting.value) return
        _isEncrypting.value = true
        val secret = sharedSecret.toCharArray()
        viewModelScope.launch {
            try {
                val entity = graph.repository.queueMessage(recipient, body, secret)
                RelayStateHolder.log("Queued encrypted message ${entity.id.take(8)} for ${entity.recipientId}")
                // If the Sender happens to be online (now or later), deliver directly too.
                AutoSyncWorker.syncSoon(getApplication<Application>())
                onQueued()
                showNotice("Message encrypted and queued as PENDING")
            } catch (e: IllegalArgumentException) {
                showNotice(e.message ?: "Invalid input")
            } catch (e: Exception) {
                showNotice("Could not queue message: ${e.message}")
            } finally {
                Arrays.fill(secret, '\u0000')
                _isEncrypting.value = false
            }
        }
    }

    fun saveCloudEndpoint(url: String): Boolean {
        val ok = graph.settings.setCloudEndpoint(url)
        showNotice(if (ok) "Cloud endpoint saved" else "Enter a valid http(s) URL")
        return ok
    }

    fun syncNow() {
        AutoSyncWorker.syncSoon(getApplication<Application>())
        showNotice("Direct sync scheduled — runs as soon as this phone has internet")
    }

    fun clearDelivered() {
        viewModelScope.launch {
            val removed = graph.repository.clearDelivered()
            showNotice("Removed $removed delivered message(s)")
        }
    }

    fun showNotice(text: String) {
        _notices.trySend(text)
    }
}
