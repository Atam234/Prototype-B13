package com.zeroinstall.relay.service

import com.zeroinstall.relay.hotspot.HotspotInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class RelayPhase { STOPPED, STARTING, ACTIVE, ERROR }

data class RelayRuntimeState(
    val phase: RelayPhase = RelayPhase.STOPPED,
    val serverRunning: Boolean = false,
    val serverPort: Int = 0,
    val hotspot: HotspotInfo? = null,
    val localUrls: List<String> = emptyList(),
    val error: String? = null,
) {
    val isActiveOrStarting: Boolean get() = phase == RelayPhase.ACTIVE || phase == RelayPhase.STARTING || serverRunning
}

data class RelayEvent(val timestamp: Long, val text: String)

/** Process-wide state shared between the foreground service, the Ktor server and the UI. */
object RelayStateHolder {
    private const val MAX_EVENTS = 60

    private val _state = MutableStateFlow(RelayRuntimeState())
    val state: StateFlow<RelayRuntimeState> = _state.asStateFlow()

    private val _events = MutableStateFlow<List<RelayEvent>>(emptyList())
    val events: StateFlow<List<RelayEvent>> = _events.asStateFlow()

    fun update(transform: (RelayRuntimeState) -> RelayRuntimeState) = _state.update(transform)

    fun reset() {
        _state.value = RelayRuntimeState()
    }

    fun log(text: String) {
        val event = RelayEvent(System.currentTimeMillis(), text)
        _events.update { (listOf(event) + it).take(MAX_EVENTS) }
    }
}
