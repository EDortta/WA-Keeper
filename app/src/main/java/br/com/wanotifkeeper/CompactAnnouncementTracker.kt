package br.com.wanotifkeeper

class CompactAnnouncementTracker(
    private val resetAfterMs: Long = DEFAULT_RESET_AFTER_MS
) {
    private data class State(val count: Int, val lastAt: Long)
    private val states = mutableMapOf<String, State>()

    @Synchronized
    fun phrase(packageName: String, sender: String, now: Long): String {
        val key = "$packageName|${sender.trim().lowercase()}"
        val previous = states[key]
        val count = if (previous == null || now - previous.lastAt > resetAfterMs) {
            1
        } else {
            previous.count + 1
        }
        states[key] = State(count, now)

        return if (count == 1) {
            "Mensagem de $sender"
        } else {
            "$count mensagens de $sender"
        }
    }

    companion object {
        const val DEFAULT_RESET_AFTER_MS = 2 * 60_000L
    }
}
