package com.example.ppgcollector_android

import androidx.lifecycle.SavedStateHandle
import java.io.Serializable
import java.nio.file.Path
import java.util.UUID

/** Only primitives/Serializable strings cross Android's saved-state boundary. */
data class PendingSessionExport(
    val kind: Kind,
    val directories: List<String>,
    val token: String,
) : Serializable {
    enum class Kind { SINGLE, ARCHIVE }
}

internal class PendingSessionExports(private val savedState: SavedStateHandle) {
    val pending: PendingSessionExport? get() = savedState[KEY]

    fun begin(kind: PendingSessionExport.Kind, directories: Set<Path>): PendingSessionExport? {
        if (pending != null || directories.isEmpty()) return null
        require(kind != PendingSessionExport.Kind.SINGLE || directories.size == 1)
        return PendingSessionExport(kind, directories.map { it.toAbsolutePath().normalize().toString() },
            UUID.randomUUID().toString()).also { savedState[KEY] = it }
    }

    fun consume(token: String): PendingSessionExport? {
        val request = pending?.takeIf { it.token == token } ?: return null
        savedState.remove<PendingSessionExport>(KEY)
        return request
    }

    private companion object { const val KEY = "pending_session_export" }
}
