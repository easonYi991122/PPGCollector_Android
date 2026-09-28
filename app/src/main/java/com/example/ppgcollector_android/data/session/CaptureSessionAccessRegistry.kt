package com.example.ppgcollector_android.data.session

import java.nio.file.Path

/**
 * Process-wide directory ownership. Acquire before checking/creating a directory,
 * and hold the lease through the last I/O operation. No filesystem work runs under
 * this lock. Snapshot/export leases are exclusive too: a snapshot must not race a
 * writer, deletion, recovery, or another operation that may replace sidecars.
 */
class CaptureSessionAccessRegistry {
    enum class Access { WRITE, READ_SNAPSHOT, DESTRUCTIVE }

    private val lock = Any()
    private val leases = mutableMapOf<Path, Lease>()

    fun tryAcquire(directory: Path, access: Access): Lease? {
        val key = directory.toAbsolutePath().normalize()
        return synchronized(lock) {
            if (key in leases) null else Lease(key, access).also { leases[key] = it }
        }
    }

    inner class Lease internal constructor(val directory: Path, val access: Access) : AutoCloseable {
        override fun close() {
            synchronized(lock) {
                if (leases[directory] === this) leases.remove(directory)
            }
        }
    }

    companion object {
        /** Shared by the Application, default writers, and archive consumers. */
        val app = CaptureSessionAccessRegistry()
    }
}
