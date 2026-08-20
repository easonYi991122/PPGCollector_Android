package com.example.ppgcollector_android.core.protocol

/**
 * Small bounded-friendly byte queue for incremental wire decoders.  Removing
 * a prefix only advances [head], so fragmented notifications do not turn into
 * repeated ArrayList.removeAt(0) copies on the BLE callback lane.
 */
internal class ByteRingBuffer(initialCapacity: Int = 256) {
    private var storage = ByteArray(initialCapacity.coerceAtLeast(16))
    private var head = 0
    var size: Int = 0
        private set

    fun add(value: Byte) {
        ensureCapacity(size + 1)
        storage[(head + size) % storage.size] = value
        size++
    }

    fun clear() {
        head = 0
        size = 0
    }

    operator fun get(index: Int): Byte {
        require(index in 0 until size)
        return storage[(head + index) % storage.size]
    }

    fun lastOrNull(): Byte? = if (size == 0) null else get(size - 1)

    fun removeFirst(count: Int) {
        val actual = count.coerceIn(0, size)
        head = (head + actual) % storage.size
        size -= actual
        if (size == 0) head = 0
    }

    fun toByteArray(count: Int = size): ByteArray {
        val actual = count.coerceIn(0, size)
        return ByteArray(actual) { index -> get(index) }
    }

    private fun ensureCapacity(required: Int) {
        if (required <= storage.size) return
        var capacity = storage.size
        while (capacity < required) capacity = capacity.coerceAtMost(Int.MAX_VALUE / 2) * 2
        val expanded = ByteArray(capacity)
        repeat(size) { expanded[it] = get(it) }
        storage = expanded
        head = 0
    }
}
