package com.instalogger.app.data.remote

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.*
import java.util.concurrent.TimeUnit

sealed class WsEvent {
    data class ChangeDetected(
        val targetId: String,
        val username: String,
        val fieldName: String,
        val oldValue: String?,
        val newValue: String?
    ) : WsEvent()

    data class TargetChecked(val targetId: String, val username: String) : WsEvent()
    data class Connected(val url: String) : WsEvent()
    data class Disconnected(val reason: String?) : WsEvent()
}

class WebSocketManager(
    private var serverUrl: String = "ws://127.0.0.1:3000/ws"
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _events = MutableSharedFlow<WsEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<WsEvent> = _events.asSharedFlow()

    fun updateUrl(httpBaseUrl: String) {
        val wsUrl = httpBaseUrl
            .replace("http://", "ws://")
            .replace("https://", "wss://")
            .let { if (it.endsWith("/")) "${it}ws" else "$it/ws" }
        this.serverUrl = wsUrl
        reconnect()
    }

    fun connect() {
        if (webSocket != null) return
        val request = Request.Builder().url(serverUrl).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                scope.launch {
                    _events.emit(WsEvent.Connected(serverUrl))
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                parseMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scope.launch {
                    _events.emit(WsEvent.Disconnected(reason))
                }
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scope.launch {
                    _events.emit(WsEvent.Disconnected(t.message))
                }
                scheduleReconnect()
            }
        })
    }

    private fun parseMessage(text: String) {
        try {
            val json = gson.fromJson(text, JsonObject::class.java)
            val type = json.get("type")?.asString ?: return
            val payload = json.getAsJsonObject("payload") ?: return

            when (type) {
                "change_detected" -> {
                    val targetId = payload.get("target_id")?.asString ?: ""
                    val username = payload.get("username")?.asString ?: ""
                    val fieldName = payload.get("event_type")?.asString ?: payload.get("field_name")?.asString ?: ""
                    val oldValue = if (payload.has("old_value") && !payload.get("old_value").isJsonNull) payload.get("old_value").asString else null
                    val newValue = if (payload.has("new_value") && !payload.get("new_value").isJsonNull) payload.get("new_value").asString else null

                    scope.launch {
                        _events.emit(WsEvent.ChangeDetected(targetId, username, fieldName, oldValue, newValue))
                    }
                }
                "target_checked" -> {
                    val targetId = payload.get("target_id")?.asString ?: ""
                    val username = payload.get("username")?.asString ?: ""
                    scope.launch {
                        _events.emit(WsEvent.TargetChecked(targetId, username))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("WebSocketManager", "Parse error", e)
        }
    }

    private fun scheduleReconnect() {
        webSocket = null
        scope.launch {
            delay(5000)
            connect()
        }
    }

    fun reconnect() {
        disconnect()
        connect()
    }

    fun disconnect() {
        webSocket?.close(1000, "Normal closure")
        webSocket = null
    }
}
