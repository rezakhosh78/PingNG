package com.v2ray.ang.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Small, UI-independent scheduler for WARP Plus endpoint discovery.
 *
 * It owns only search orchestration. The caller supplies the real probe, so
 * this core can be reused by the startup path without starting/stopping the
 * Android service for every candidate. The worker limit prevents thousands of
 * candidates from becoming thousands of coroutines, while still allowing the
 * native probe implementation to become parallel-safe later.
 */
object WarpSearchCore {
    data class Candidate(
        val inner: WarpEndpointTester.Endpoint,
        val outer: WarpEndpointTester.Endpoint,
    )

    data class Result(
        val candidate: Candidate,
        val delay: Long,
    )

    data class Options(
        val timeoutMs: Long,
        val deadlineMs: Long,
        val workers: Int = DEFAULT_WORKERS,
        val chooseFastest: Boolean,
    )

    private const val DEFAULT_WORKERS = 4

    suspend fun search(
        candidates: List<Candidate>,
        options: Options,
        probe: suspend (Candidate, Long) -> Long,
        onCandidateCompleted: suspend (tested: Int, total: Int) -> Unit = { _, _ -> },
    ): Result? = coroutineScope {
        if (candidates.isEmpty()) return@coroutineScope null

        val workerCount = options.workers.coerceIn(1, DEFAULT_WORKERS)
        val work = Channel<Candidate>(Channel.UNLIMITED)
        val results = Channel<Result>(Channel.UNLIMITED)
        val startedAt = System.nanoTime()
        val completedCount = AtomicInteger(0)

        val producer = launch(Dispatchers.Default) {
            try {
                for (candidate in candidates) {
                    ensureActive()
                    if (remainingMs(startedAt, options.deadlineMs) <= 0L) break
                    work.send(candidate)
                }
            } finally {
                work.close()
            }
        }

        val workers = (0 until workerCount).map {
            launch(Dispatchers.Default) {
                for (candidate in work) {
                    ensureActive()
                    val remaining = remainingMs(startedAt, options.deadlineMs)
                    if (remaining <= 0L) break
                    val delay = runCatching {
                        probe(candidate, minOf(options.timeoutMs, remaining))
                    }.getOrDefault(-1L)
                    // A blocking native probe may finish after Stop cancelled
                    // the search. Never publish its late progress or result.
                    ensureActive()
                    onCandidateCompleted(completedCount.incrementAndGet(), candidates.size)
                    if (delay >= 0L) results.send(Result(candidate, delay))
                }
            }
        }

        if (!options.chooseFastest) {
            val closer = launch {
                producer.join()
                workers.joinAll()
                results.close()
            }
            val first = results.receiveCatching().getOrNull()
            producer.cancel()
            work.close()
            workers.forEach { it.cancel() }
            closer.join()
            first
        } else {
            producer.join()
            workers.joinAll()
            results.close()
            results.tryReceive().getOrNull()?.let { first ->
                var best = first
                while (true) {
                    val next = results.tryReceive().getOrNull() ?: break
                    if (next.delay < best.delay) best = next
                }
                best
            }
        }
    }

    private fun remainingMs(startedAt: Long, deadlineMs: Long): Long {
        val elapsed = (System.nanoTime() - startedAt) / 1_000_000L
        return deadlineMs - elapsed
    }
}
