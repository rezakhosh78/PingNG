package com.v2ray.ang.ui.main

import java.util.UUID

/** Tracks independent real-ping runs, with one replaceable run per subscription. */
internal class MainPingRequests {
    data class Request(val id: String, val groupId: String)
    data class Replacement(val previous: Request?, val current: Request)

    private val byId = linkedMapOf<String, Request>()
    private val idByGroup = linkedMapOf<String, String>()

    @Synchronized
    fun begin(groupId: String): Request? {
        if (idByGroup.containsKey(groupId)) return null
        return Request(UUID.randomUUID().toString(), groupId).also { request ->
            byId[request.id] = request
            idByGroup[groupId] = request.id
        }
    }

    /** A new tap restarts only this group's test; other subscriptions remain active. */
    @Synchronized
    fun replace(groupId: String): Replacement {
        val previous = idByGroup.remove(groupId)?.let(byId::remove)
        val current = Request(UUID.randomUUID().toString(), groupId)
        byId[current.id] = current
        idByGroup[groupId] = current.id
        return Replacement(previous, current)
    }

    @Synchronized
    fun get(requestId: String): Request? = byId[requestId]

    @Synchronized
    fun complete(requestId: String): Request? {
        val request = byId.remove(requestId) ?: return null
        if (idByGroup[request.groupId] == request.id) idByGroup.remove(request.groupId)
        return request
    }

    @Synchronized
    fun activeGroupIds(): Set<String> = idByGroup.keys.toSet()

    @Synchronized
    fun clear(): List<Request> = byId.values.toList().also {
        byId.clear()
        idByGroup.clear()
    }
}
