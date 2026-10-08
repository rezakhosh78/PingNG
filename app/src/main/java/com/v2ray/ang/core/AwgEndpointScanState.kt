package com.v2ray.ang.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Internal endpoint-discovery state shared by the editor and connection flow. */
object AwgEndpointScanState {
    data class Snapshot(
        val guid: String = "",
        val running: Boolean = false,
        val message: String = "",
        val steps: List<String> = emptyList(),
        val endpoints: List<String> = emptyList(),
        val selectedEndpoint: String = "",
        val error: Boolean = false,
    )

    private val _state = MutableStateFlow(Snapshot())
    val state = _state.asStateFlow()

    private val endpointPattern = Regex(
        """(?:\[(?:[0-9A-Fa-f:]+)\]|(?:\d{1,3}\.){3}\d{1,3}|[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?)(?::\d{1,5})(?!\d)"""
    )

    fun begin(guid: String, message: String) {
        _state.value = Snapshot(guid = guid, running = true, message = message, steps = listOf(message))
    }

    fun clear(guid: String) {
        _state.value = Snapshot(guid = guid)
    }

    fun update(guid: String, message: String) {
        val text = sanitize(message) ?: return
        if (text.isBlank()) return
        _state.update { current ->
            if (current.guid != guid) {
                Snapshot(guid = guid, running = true, message = text)
            } else {
                val found = endpointPattern.find(text)?.value
                val steps = if (current.steps.lastOrNull() == text) current.steps
                    else (current.steps + text).takeLast(4)
                val endpoints = if (found.isNullOrBlank() || current.endpoints.contains(found)) {
                    current.endpoints
                } else {
                    (current.endpoints + found).takeLast(100)
                }
                current.copy(running = true, message = text, steps = steps, endpoints = endpoints, error = false)
            }
        }
    }

    fun complete(guid: String, message: String, selectedEndpoint: String) {
        _state.update { current ->
            val endpoints = if (selectedEndpoint.isBlank() || current.endpoints.contains(selectedEndpoint)) {
                current.endpoints
            } else {
                (current.endpoints + selectedEndpoint).takeLast(100)
            }
            current.copy(
                guid = guid,
                running = false,
                message = message,
                steps = (current.steps + message).takeLast(4),
                endpoints = endpoints,
                selectedEndpoint = selectedEndpoint,
                error = false,
            )
        }
    }

    fun fail(guid: String, message: String) {
        _state.update { current ->
            current.copy(guid = guid, running = false, message = sanitize(message) ?: "Endpoint scan failed", error = true)
        }
    }

    private fun sanitize(message: String): String? {
        val text = message.trim()
        if (text.isBlank()) return null
        val lower = text.lowercase()
        if (lower.contains("scan -p") || lower.contains("github.com/") ||
            lower.contains("endpoint scanner") || lower.contains("endpointscanner") ||
            lower.contains("v0.16.0") ||
            lower.startsWith("panic:") || lower.startsWith("goroutine ") ||
            lower.startsWith("main.") || lower.startsWith("runtime.")) {
            return null
        }
        return text.take(180)
    }
}
