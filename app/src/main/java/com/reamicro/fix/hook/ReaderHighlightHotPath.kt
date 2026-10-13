package com.reamicro.fix.hook

internal class HighlightIdentityCache<K : Any, V : Any>(
    private val maxEntries: Int,
    private val maxWeight: Int,
) {
    private class IdentityKey(val value: Any) {
        override fun hashCode(): Int = System.identityHashCode(value)
        override fun equals(other: Any?): Boolean = other is IdentityKey && value === other.value
    }
    private data class Entry<V>(val value: V?, val weight: Int)
    private val entries = LinkedHashMap<IdentityKey, Entry<V>>(16, 0.75f, true)
    private var token: String? = null
    private var weight = 0L

    private var generation = 0L

    fun getOrCompute(key: K, version: String, cost: Int, compute: () -> V?): V? {
        val identity = IdentityKey(key)
        val ticket = synchronized(this) {
            if (token != version) {
                clear()
                token = version
            }
            entries[identity]?.let { return it.value }
            generation
        }

        val value = compute()
        synchronized(this) {
            if (ticket != generation || token != version) return value
            entries[identity]?.let { return it.value }
            if (cost < 0 || cost > maxWeight) return value
            entries[identity] = Entry(value, cost)
            weight += cost
            val iterator = entries.entries.iterator()
            while ((entries.size > maxEntries || weight > maxWeight) && iterator.hasNext()) {
                weight -= iterator.next().value.weight
                iterator.remove()
            }
        }
        return value
    }

    @Synchronized
    fun clear() {
        generation++
        entries.clear()
        token = null
        weight = 0L
    }
}

internal class HighlightRuntimeToken {
    private var book = ""
    private var version = Long.MIN_VALUE
    private var dark = false
    private var enabled = false
    private var token = ""

    @Synchronized
    fun get(book: String, version: Long, dark: Boolean, enabled: Boolean): String {
        if (this.book != book || this.version != version || this.dark != dark || this.enabled != enabled) {
            this.book = book
            this.version = version
            this.dark = dark
            this.enabled = enabled
            token = "$book|$version|$dark|$enabled"
        }
        return token
    }
}

internal fun uniqueHighlightTextOffset(source: String, page: String): Int {
    if (page.isEmpty()) return -1
    val first = source.indexOf(page)
    return if (first >= 0 && source.indexOf(page, first + 1) < 0) first else -1
}
