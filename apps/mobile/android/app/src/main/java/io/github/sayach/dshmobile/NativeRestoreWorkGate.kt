package io.github.sayach.dshmobile

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Serializes physical restore work for one credential without blocking the UI or other devices.
 * Replacing a request withdraws its callbacks elsewhere; its slot lasts until its worker exits.
 * Only the latest waiting request for each key is retained, and ownership is checked at execution.
 */
internal class NativeRestoreWorkGate<K : Any>(private val executor: Executor) {
    private class Work<K>(
        val key: K,
        val isCurrent: () -> Boolean,
        val onRejected: () -> Unit,
        val executor: Executor,
        val operation: () -> Unit,
    )

    private val running = mutableMapOf<K, Work<K>>()
    private val waiting = mutableMapOf<K, Work<K>>()

    /** Enqueue the newest owner; cancellation does not release a still-running physical request. */
    fun submit(key: K, isCurrent: () -> Boolean, onRejected: () -> Unit, workExecutor: Executor = executor, operation: () -> Unit) {
        val work = Work(key, isCurrent, onRejected, workExecutor, operation)
        val start = synchronized(this) {
            if (running.containsKey(key)) {
                waiting[key] = work
                false
            } else {
                running[key] = work
                true
            }
        }
        if (start) execute(work)
    }

    private fun execute(work: Work<K>) {
        val entered = AtomicBoolean(false)
        try {
            work.executor.execute {
                entered.set(true)
                try {
                    if (work.isCurrent()) work.operation()
                } finally {
                    completed(work)
                }
            }
        } catch (failure: RejectedExecutionException) {
            // A direct Executor may propagate the operation's own exception after its finally.
            if (entered.get()) throw failure
            try {
                if (work.isCurrent()) work.onRejected()
            } finally {
                completed(work)
            }
        }
    }

    private fun completed(work: Work<K>) {
        val next = synchronized(this) {
            if (running[work.key] !== work) return
            running.remove(work.key)
            waiting.remove(work.key)?.also { running[work.key] = it }
        }
        if (next != null) execute(next)
    }
}
