package com.reamicro.fix.hook

internal data class HostSearchTextBlock(
    val chapterIndex: Int,
    val chapterTitle: String,
    val anchor: String,
    val textAnchor: String,
    val text: String,
)

internal data class HostSearchHit(
    val anchor: String,
    val textAnchor: String,
    val elementOffset: Int,
    val length: Int,
)

internal object HostFullTextPolicy {
    private val whitespace = Regex("\\s+")
    fun normalize(text: String): String = whitespace.replace(text, " ").trim()

    fun forEachMatch(text: String, query: String, limit: Int, current: () -> Boolean, emit: (Int) -> Unit) {
        if (query.isEmpty() || limit <= 0) return
        var from = 0
        var count = 0
        while (count < limit && current()) {
            val start = text.indexOf(query, from, ignoreCase = true)
            if (start < 0) return
            emit(start)
            count++
            from = start + query.length
        }
    }

    fun pollDelay(elapsedMs: Long): Long = when {
        elapsedMs < 200 -> 32
        elapsedMs < 1000 -> 80
        else -> 200
    }
}

internal class HostSearchBlockCache(private val maxWeight: Long = 8L * 1024 * 1024) {
    private data class Entry(val modified: Long, val length: Long, val blocks: List<HostSearchTextBlock>, val weight: Long)
    private val entries = LinkedHashMap<String, Entry>(16, .75f, true)
    private var weight = 0L
    private var owner = java.lang.ref.WeakReference<Any>(null)

    @Synchronized
    fun get(epub: Any, path: String, modified: Long, length: Long): List<HostSearchTextBlock>? {
        if (owner.get() !== epub) { clear(); owner = java.lang.ref.WeakReference(epub) }
        val entry = entries[path] ?: return null
        if (entry.modified == modified && entry.length == length) return entry.blocks
        entries.remove(path)
        weight -= entry.weight
        return null
    }

    @Synchronized
    fun put(epub: Any, path: String, modified: Long, length: Long, blocks: List<HostSearchTextBlock>) {
        if (owner.get() !== epub) { clear(); owner = java.lang.ref.WeakReference(epub) }
        val cost = blocks.sumOf { 128L + (it.text.length.toLong() + it.anchor.length + it.textAnchor.length + it.chapterTitle.length) * 2 }
        entries.remove(path)?.let { weight -= it.weight }
        if (cost > maxWeight) return
        entries[path] = Entry(modified, length, blocks, cost)
        weight += cost
        while (weight > maxWeight || entries.size > 128) {
            val iterator = entries.entries.iterator()
            weight -= iterator.next().value.weight
            iterator.remove()
        }
    }

    @Synchronized
    fun clear() { entries.clear(); weight = 0; owner.clear() }
}
internal fun suppressStaleHostSearchResume(managed: Boolean, result: Any?, expected: Any?): Boolean =
    managed && result !== expected
