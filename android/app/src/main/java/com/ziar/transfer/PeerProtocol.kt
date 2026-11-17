package com.ziar.transfer

import java.util.UUID

internal data class PeerAnnouncement(val reply: Boolean, val id: String, val name: String)

internal object PeerProtocol {
    const val TCP_PORT = 47833
    const val UDP_PORT = 47834
    const val MAGIC = "ZIAR/2"
    const val MAX_BYTES = 1024L * 1024 * 1024

    fun safeName(raw: String): String {
        val base = raw.replace('\\', '/').substringAfterLast('/')
        val cleaned = base.replace(Regex("[<>:\"/\\\\|?*\\x00-\\x1f]"), "_").trimEnd('.', ' ').take(180)
        return cleaned.ifBlank { "file" }
    }

    fun deviceName(raw: String): String = raw.replace(Regex("[^A-Za-z0-9 _.-]"), " ").replace(Regex("\\s+"), " ").trim().take(32).ifBlank { "Android phone" }

    fun announcement(reply: Boolean, id: String, name: String): ByteArray =
        "$MAGIC|${if (reply) "R" else "D"}|$id|${deviceName(name)}".toByteArray(Charsets.UTF_8)

    fun parseAnnouncement(bytes: ByteArray, length: Int): PeerAnnouncement? {
        if (length !in 1..256) return null
        val parts = String(bytes, 0, length, Charsets.UTF_8).split('|', limit = 4)
        if (parts.size != 4 || parts[0] != MAGIC || parts[1] !in listOf("D", "R")) return null
        try { UUID.fromString(parts[2]) } catch (_: Exception) { return null }
        return PeerAnnouncement(parts[1] == "R", parts[2], deviceName(parts[3]))
    }
}
