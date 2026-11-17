package com.ziar.transfer

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Semaphore

internal data class NearbyPhone(val id: String, val name: String, val address: String, val lastSeen: Long)

internal class PhonePeerService(
    private val context: Context,
    private val onPeers: (List<NearbyPhone>) -> Unit,
    private val onReceived: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    val id: String = UUID.randomUUID().toString()
    val code: String = (100000 + SecureRandom().nextInt(900000)).toString()
    private val name = PeerProtocol.deviceName(Build.MODEL ?: "Android phone")
    private val peers = mutableMapOf<String, NearbyPhone>()
    private val attempts = mutableMapOf<String, Pair<Int, Long>>()
    private val capacity = Semaphore(2)
    @Volatile private var running = false
    private var listener: ServerSocket? = null
    private var discovery: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start(): Boolean {
        if (running) return true
        return try {
            listener = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PeerProtocol.TCP_PORT)); soTimeout = 1000 }
            discovery = DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(PeerProtocol.UDP_PORT)); soTimeout = 1000; broadcast = true }
            try {
                val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                multicastLock = wifi.createMulticastLock("ziar-peer-discovery").apply { setReferenceCounted(false); acquire() }
            } catch (_: Exception) { /* Gateway discovery and manual address still work. */ }
            running = true
            Thread({ acceptLoop() }, "ziar-peer-receive").apply { isDaemon = true; start() }
            Thread({ discoveryLoop() }, "ziar-peer-discovery").apply { isDaemon = true; start() }
            Thread({ announceLoop() }, "ziar-peer-announce").apply { isDaemon = true; start() }
            true
        } catch (error: Exception) {
            stop()
            onError("Could not start phone sharing: ${error.message ?: "Network unavailable"}")
            false
        }
    }

    fun stop() {
        running = false
        listener?.close(); listener = null
        discovery?.close(); discovery = null
        multicastLock?.let { if (it.isHeld) it.release() }; multicastLock = null
        synchronized(peers) { peers.clear() }
        onPeers(emptyList())
    }

    fun addresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { it.isUp && !it.isLoopback }
            .flatMap { network -> network.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress }.map { it.hostAddress ?: "" }.filter { it.isNotBlank() }.distinct()
    } catch (_: Exception) { emptyList() }

    private fun acceptLoop() {
        while (running) {
            try {
                val socket = listener?.accept() ?: break
                if (!capacity.tryAcquire()) { socket.close(); continue }
                Thread({ try { handleIncoming(socket) } finally { capacity.release() } }, "ziar-peer-file").apply { isDaemon = true; start() }
            } catch (_: SocketTimeoutException) { /* Check whether sharing is still open. */ }
            catch (_: Exception) { if (running) onError("Phone sharing stopped. Reopen this screen to retry."); break }
        }
    }

    private fun discoveryLoop() {
        val socket = discovery ?: return
        while (running) {
            try {
                val packet = DatagramPacket(ByteArray(512), 512)
                socket.receive(packet)
                val message = PeerProtocol.parseAnnouncement(packet.data, packet.length) ?: continue
                if (message.id == id || packet.address !is Inet4Address || !packet.address.isSiteLocalAddress) continue
                val now = System.currentTimeMillis()
                synchronized(peers) { peers[message.id] = NearbyPhone(message.id, message.name, packet.address.hostAddress, now) }
                publishPeers()
                if (!message.reply) {
                    val answer = PeerProtocol.announcement(true, id, name)
                    socket.send(DatagramPacket(answer, answer.size, packet.address, PeerProtocol.UDP_PORT))
                }
            } catch (_: SocketTimeoutException) { prunePeers() }
            catch (_: Exception) { if (!running) break }
        }
    }

    private fun announceLoop() {
        while (running) {
            sendDiscovery()
            prunePeers()
            publishPeers()
            try { Thread.sleep(3000) } catch (_: InterruptedException) { break }
        }
    }

    @Suppress("DEPRECATION")
    private fun gateway(): InetAddress? = try {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val value = wifi.dhcpInfo?.gateway ?: 0
        if (value == 0) null else InetAddress.getByAddress(byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte()))
    } catch (_: Exception) { null }

    private fun sendDiscovery() {
        val message = PeerProtocol.announcement(false, id, name)
        val gateway = gateway()
        try {
            for (network in NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()) {
                if (!network.isUp || network.isLoopback) continue
                for (entry in network.interfaceAddresses) {
                    val address = entry.address
                    if (address !is Inet4Address || !address.isSiteLocalAddress) continue
                    try {
                        DatagramSocket(0, address).use { sender ->
                            sender.broadcast = true
                            val destinations = listOfNotNull(entry.broadcast, gateway).distinct()
                            for (destination in destinations) try {
                                sender.send(DatagramPacket(message, message.size, destination, PeerProtocol.UDP_PORT))
                            } catch (_: Exception) { /* Another network interface may still work. */ }
                        }
                    } catch (_: Exception) { /* Another network interface may still work. */ }
                }
            }
        } catch (_: Exception) { /* Manual address entry remains available. */ }
    }

    private fun prunePeers() {
        val now = System.currentTimeMillis()
        val changed = synchronized(peers) { peers.entries.removeAll { now - it.value.lastSeen > 12000 } }
        if (changed) publishPeers()
    }

    private fun publishPeers() {
        val list = synchronized(peers) { peers.values.sortedBy { it.name }.toList() }
        onPeers(list)
    }

    private fun codeAllowed(address: String, offered: String): Boolean = synchronized(attempts) {
        val now = System.currentTimeMillis()
        val previous = attempts[address]
        if (previous != null && previous.second > now) return@synchronized false
        if (offered == code) { attempts.remove(address); return@synchronized true }
        val count = (previous?.first ?: 0) + 1
        attempts[address] = if (count >= 5) 0 to (now + 60000) else count to 0L
        false
    }

    private fun canReceive(): Boolean = Build.VERSION.SDK_INT >= 29 || context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun handleIncoming(socket: Socket) {
        socket.use { peer ->
            peer.soTimeout = 30000
            val input = DataInputStream(peer.getInputStream())
            val output = DataOutputStream(peer.getOutputStream())
            try {
                val magic = input.readUTF()
                val offeredCode = input.readUTF()
                val rawName = input.readUTF()
                if (magic != PeerProtocol.MAGIC) { output.writeUTF("Incompatible ZiAr version."); return }
                if (!codeAllowed(peer.inetAddress.hostAddress, offeredCode)) { output.writeUTF("Incorrect or temporarily blocked receive code."); return }
                if (!canReceive()) { output.writeUTF("Allow storage access on the receiving phone."); return }
                val name = PeerProtocol.safeName(rawName)
                val target = try { createIncomingFile(name) } catch (error: Exception) {
                    output.writeUTF("Could not save file: ${error.message ?: "Storage unavailable"}"); output.flush(); return
                }
                try {
                    output.writeUTF("READY"); output.flush()
                    target.stream.use { destination ->
                        val buffer = ByteArray(64 * 1024)
                        var count = 0L
                        while (true) {
                            val length = input.read(buffer)
                            if (length < 0) break
                            count += length
                            if (count > PeerProtocol.MAX_BYTES) throw IOException("File is larger than 1 GB.")
                            destination.write(buffer, 0, length)
                        }
                    }
                    target.commit()
                    output.writeUTF("SAVED"); output.flush()
                    onReceived(name)
                } catch (error: Exception) {
                    target.abort()
                    try { output.writeUTF("Could not save file: ${error.message ?: "Unknown error"}"); output.flush() } catch (_: Exception) { }
                }
            } catch (_: Exception) { /* Invalid or interrupted transfer. */ }
        }
    }

    private class IncomingFile(val stream: OutputStream, val commit: () -> Unit, val abort: () -> Unit)

    private fun createIncomingFile(name: String): IncomingFile {
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val ext = name.substringAfterLast('.', "").lowercase()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/ZiAr Received/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("Could not create Downloads file.")
            val stream = resolver.openOutputStream(uri) ?: run { resolver.delete(uri, null, null); throw IOException("Could not open Downloads file.") }
            return IncomingFile(stream,
                { val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }; if (resolver.update(uri, ready, null, null) == 0) throw IOException("Could not finish Downloads file.") },
                { resolver.delete(uri, null, null) })
        }
        @Suppress("DEPRECATION")
        val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ZiAr Received")
        if (!directory.exists() && !directory.mkdirs()) throw IOException("Could not create Downloads folder.")
        val parsed = File(name)
        val stem = parsed.nameWithoutExtension
        val extension = parsed.extension.takeIf { it.isNotEmpty() }?.let { ".$it" } ?: ""
        var destination = File(directory, name)
        var number = 1
        while (destination.exists()) { destination = File(directory, "$stem ($number)$extension"); number++ }
        val temporary = File.createTempFile(".ziar-", ".part", directory)
        return IncomingFile(FileOutputStream(temporary),
            { if (!temporary.renameTo(destination)) throw IOException("Could not finish Downloads file.") },
            { temporary.delete() })
    }

    private fun selectedName(uri: Uri): String {
        val found = try { context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } } catch (_: Exception) { null }
        return PeerProtocol.safeName(found ?: uri.lastPathSegment ?: "file")
    }

    fun sendFiles(address: String, receiveCode: String, files: List<Uri>, status: (String, Boolean?) -> Unit) {
        Thread({
            for ((index, uri) in files.withIndex()) {
                try {
                    val name = selectedName(uri)
                    status("Sending ${index + 1} of ${files.size}: $name", null)
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(address, PeerProtocol.TCP_PORT), 5000)
                        socket.soTimeout = 30000
                        val output = DataOutputStream(socket.getOutputStream())
                        val input = DataInputStream(socket.getInputStream())
                        output.writeUTF(PeerProtocol.MAGIC)
                        output.writeUTF(receiveCode)
                        output.writeUTF(name)
                        output.flush()
                        val ready = input.readUTF()
                        if (ready != "READY") throw IOException(ready)
                        val source = context.contentResolver.openInputStream(uri) ?: throw IOException("Could not read selected file.")
                        source.use { file ->
                            val buffer = ByteArray(64 * 1024)
                            var count = 0L
                            while (true) {
                                val length = file.read(buffer)
                                if (length < 0) break
                                count += length
                                if (count > PeerProtocol.MAX_BYTES) throw IOException("File is larger than 1 GB.")
                                output.write(buffer, 0, length)
                            }
                        }
                        output.flush()
                        socket.shutdownOutput()
                        val result = input.readUTF()
                        if (result != "SAVED") throw IOException(result)
                    }
                } catch (error: Exception) {
                    status("Could not send file ${index + 1}: ${error.message ?: "Connection lost"}", false)
                    return@Thread
                }
            }
            status("Sent ${files.size} file${if (files.size == 1) "" else "s"} to the other phone.", true)
        }, "ziar-peer-send").apply { isDaemon = true; start() }
    }
}
