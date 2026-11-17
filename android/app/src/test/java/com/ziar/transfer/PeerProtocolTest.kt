package com.ziar.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PeerProtocolTest {
    @Test fun discoveryPacketsRoundTrip() {
        val id = "123e4567-e89b-12d3-a456-426614174000"
        val bytes = PeerProtocol.announcement(false, id, "Phone | 1")
        assertEquals(PeerAnnouncement(false, id, "Phone 1"), PeerProtocol.parseAnnouncement(bytes, bytes.size))
        assertNull(PeerProtocol.parseAnnouncement("not ZiAr".toByteArray(), 8))
        val invalid = PeerProtocol.announcement(true, "invalid", "Phone")
        assertNull(PeerProtocol.parseAnnouncement(invalid, invalid.size))
    }

    @Test fun receivedNamesCannotEscapeDownloads() {
        assertEquals("photo.jpg", PeerProtocol.safeName("../../photo.jpg"))
        assertEquals("bad_name.txt", PeerProtocol.safeName("bad?name.txt"))
        assertEquals("file", PeerProtocol.safeName(".."))
    }
}
