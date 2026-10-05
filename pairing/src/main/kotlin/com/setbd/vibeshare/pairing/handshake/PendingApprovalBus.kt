package com.setbd.vibeshare.pairing.handshake

import com.setbd.vibeshare.domain.model.DeviceInfo
import com.setbd.vibeshare.domain.model.ShareSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bridges the blocking server-authentication path to the UI: when an unknown
 * device requests pairing, the UI surfaces an approval dialog and answers
 * through [respond]. Unanswered requests time out on the engine side.
 */
class PendingApprovalBus {

    data class Request(
        val peer: DeviceInfo,
        val session: ShareSession,
        val responder: (Boolean) -> Unit,
    )

    private val _request = MutableStateFlow<Request?>(null)
    val request: StateFlow<Request?> = _request.asStateFlow()

    fun emit(request: Request?) {
        _request.value = request
    }

    fun current(): Request? = _request.value

    fun respond(approved: Boolean) {
        _request.value?.responder?.invoke(approved)
        _request.value = null
    }
}
