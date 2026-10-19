package com.rouast.vitallens.ui

/**
 * Keeps a hint on screen long enough to read. While a held message is showing, later messages
 * wait and only the most recent one is shown afterwards.
 *
 * Keep behaviour in sync with the other VitalLens SDKs.
 *
 * Not thread-safe: call from one thread (the UI thread).
 *
 * @param minMs How long a held message stays before a later one can replace it.
 * @param now The current time in milliseconds.
 * @param schedule Runs an action after a delay (ms) and returns a function that cancels it.
 * @param apply Shows a message. The flag is the `force` value given to [show].
 */
internal class MessageHold(
    private val minMs: Long = 3_000,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val schedule: (delayMs: Long, action: () -> Unit) -> () -> Unit,
    private val apply: (text: String, force: Boolean) -> Unit,
) {
    private data class Pending(val text: String, val hold: Boolean, val force: Boolean)

    private var current: String? = null
    private var heldUntil = 0L
    private var pending: Pending? = null
    private var cancelTimer: (() -> Unit)? = null

    /**
     * Shows a message, or queues it while a held message is showing.
     *
     * @param hold Keep this message on screen for at least the minimum time.
     * @param immediate Show now, dropping any hold and waiting message.
     * @param force Passed through to `apply`.
     */
    fun show(text: String, hold: Boolean = false, immediate: Boolean = false, force: Boolean = false) {
        if (immediate) {
            release()
            applyNow(text, hold, force)
            return
        }
        if (now() < heldUntil) {
            if (text == current) {
                clearPending()
            } else {
                pending = Pending(text, hold, force)
                scheduleWaiting()
            }
            return
        }
        applyNow(text, hold, force)
    }

    /** Drops the hold and any waiting message. */
    fun release() {
        clearPending()
        heldUntil = 0
    }

    private fun applyNow(text: String, hold: Boolean, force: Boolean) {
        // Re-showing the same message does not restart its hold
        if (text != current) {
            current = text
            heldUntil = if (hold) now() + minMs else 0
        }
        apply(text, force)
    }

    private fun scheduleWaiting() {
        if (cancelTimer != null) return
        cancelTimer = schedule(maxOf(0, heldUntil - now())) {
            cancelTimer = null
            pending?.let { next ->
                pending = null
                applyNow(next.text, next.hold, next.force)
            }
        }
    }

    private fun clearPending() {
        pending = null
        cancelTimer?.invoke()
        cancelTimer = null
    }
}
