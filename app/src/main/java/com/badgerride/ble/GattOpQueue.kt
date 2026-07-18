package com.badgerride.ble

import android.os.Handler
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Serializes GATT operations for one BluetoothGatt connection - Android allows a
 * single in-flight op per *connection*. Ported unchanged from the ErgPoc proof of
 * concept; these invariants are load-bearing:
 *
 *  - Completions are identity-checked ([opDone]): after a timeout, a late callback
 *    would otherwise complete whatever op happens to be running next, permanently
 *    desynchronising the queue.
 *  - `start()` returning false means no callback is coming - the op is skipped,
 *    not awaited.
 *  - The 15 s watchdog is a last-resort escape hatch (Android's own ATT timeout is
 *    30 s). Lowering it makes late callbacks routine.
 */
internal class GattOpQueue(private val handler: Handler, private val log: (String) -> Unit) {

    companion object {
        /** Android allows ~30s for an ATT transaction; sit under that but above any real procedure. */
        const val OP_TIMEOUT_MS = 15_000L
    }

    internal class Op(
        val key: UUID,
        val cpOpcode: Byte? = null,      // non-null: completes on the 0x80 response to this opcode
        val isRead: Boolean = false,     // a read and a CCCD write on the same characteristic are distinct ops
        val quiet: Boolean = false,      // routine refresh op: logged only on failure
        val label: String,
        val onFailed: (String) -> Unit = {},   // must not enqueue
        val start: () -> Boolean               // false = never started, no callback is coming
    ) {
        var startedAt = 0L
    }

    private val ops = ConcurrentLinkedQueue<Op>()
    private var current: Op? = null
    private var generation = 0
    private var timeout: Runnable? = null

    /** Duration of the last completed op - surfaced in the ERG status line. */
    @Volatile var lastOpMs = 0L
        private set

    fun enqueue(op: Op) {
        ops.add(op)
        drive()
    }

    /** Drops queued (not in-flight) control-point writes for [opcode] - only the newest value matters. */
    fun dropPending(opcode: Byte) {
        ops.removeAll { it.cpOpcode == opcode }
    }

    @Synchronized
    private fun drive() {
        if (current != null) return
        while (true) {
            val op = ops.poll() ?: return
            current = op
            if (!op.quiet) log("-> ${op.label}")
            op.startedAt = System.currentTimeMillis()
            // A throw here would strand the queue with no timeout armed - the one
            // state the watchdog below cannot rescue. Treat it as a failed start.
            val started = try { op.start() } catch (t: Throwable) { log("${op.label}: $t"); false }
            if (started) {
                val gen = ++generation
                val r = Runnable { onTimeout(gen) }
                timeout = r
                handler.postDelayed(r, OP_TIMEOUT_MS)
                return
            }
            current = null
            val why = "${op.label}: write did not start"
            log(why)
            op.onFailed(why)
        }
    }

    /** Completes the in-flight op if [key]/[cpOpcode]/[isRead] identify it. Returns false for strays. */
    @Synchronized
    fun opDone(key: UUID, cpOpcode: Byte?, failure: String?, isRead: Boolean = false): Boolean {
        val op = current
        if (op == null || op.key != key || op.cpOpcode != cpOpcode || op.isRead != isRead) {
            log("Stray GATT completion for ${shortUuid(key)} - ignored")
            return false
        }
        finish(op, failure)
        return true
    }

    /** Whether the in-flight op is a quiet refresh - used to mute its routine log lines. */
    @Synchronized
    fun currentQuiet() = current?.quiet == true

    /** Completes the in-flight op regardless of identity (timeout, disconnect). */
    @Synchronized
    fun abortCurrent(reason: String) {
        finish(current ?: return, reason)
    }

    @Synchronized
    fun flush(reason: String) {
        ops.clear()
        abortCurrent(reason)
    }

    private fun finish(op: Op, failure: String?) {
        timeout?.let { handler.removeCallbacks(it) }
        timeout = null
        generation++
        current = null
        val ms = System.currentTimeMillis() - op.startedAt
        lastOpMs = ms
        if (failure != null) {
            log("$failure (after $ms ms)")
            op.onFailed(failure)
        } else if (!op.quiet) {
            log("<- ${op.label}: done ($ms ms)")
        }
        drive()
    }

    @Synchronized
    private fun onTimeout(gen: Int) {
        if (gen != generation) return
        abortCurrent("${current?.label ?: "GATT op"}: no reply in ${OP_TIMEOUT_MS}ms")
    }
}
