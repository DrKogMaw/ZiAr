package com.ziar.transfer

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputType
import android.text.InputFilter
import android.view.View
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException
import java.net.HttpURLConnection
import java.net.URL
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {
    private var chooser: ValueCallback<Array<Uri>>? = null
    private lateinit var web: WebView
    private lateinit var address: EditText
    private lateinit var setup: LinearLayout
    private lateinit var discoveryStatus: TextView
    private lateinit var pcPanel: LinearLayout
    private lateinit var phonePanel: ScrollView
    private lateinit var peerStatus: TextView
    private lateinit var peerCode: TextView
    private lateinit var peerAddresses: TextView
    private lateinit var peerList: LinearLayout
    private lateinit var manualPeerAddress: EditText
    private var peerService: PhonePeerService? = null
    private var phoneMode = false
    private var chosenPeerAddress: String? = null
    private var chosenPeerCode: String? = null
    private var discoverySocket: DatagramSocket? = null
    @Volatile private var discovering = false
    @Volatile private var discoveryGeneration = 0
    private val chooserRequest = 42
    private val peerChooserRequest = 43
    private val storageRequest = 44
    private val pollHandler = Handler(Looper.getMainLooper())
    private val queuedDownloads = mutableSetOf<String>()
    private var pollInFlight = false
    private var isActive = false
    private val pollTask = object : Runnable {
        override fun run() {
            if (isActive && !phoneMode && ::web.isInitialized && web.visibility == View.VISIBLE && !pollInFlight) pollForPcFiles()
            pollHandler.postDelayed(this, 3000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 24) }
        discoveryStatus = TextView(this).apply { text = "Searching for your Windows PC on Wi-Fi…"; textSize = 18f }
        address = EditText(this).apply { hint = "PC address, e.g. 192.168.1.5"; inputType = android.text.InputType.TYPE_CLASS_TEXT }
        val connect = Button(this).apply { text = "Connect to PC"; setOnClickListener { connect() } }
        val search = Button(this).apply { text = "Search again"; setOnClickListener { startDiscovery() } }
        setup.addView(discoveryStatus); setup.addView(address); setup.addView(connect); setup.addView(search)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                    return request.url.host != currentHost()
                }
                override fun onReceivedError(view: WebView, request: android.webkit.WebResourceRequest, error: android.webkit.WebResourceError) {
                    if (request.isForMainFrame) { showSetup(false); discoveryStatus.text = "Cannot reach PC. Check Wi-Fi or enter its address below." }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                    chooser?.onReceiveValue(null)
                    chooser = callback
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    try {
                        startActivityForResult(Intent.createChooser(intent, "Choose files"), chooserRequest)
                    } catch (e: Exception) {
                        chooser?.onReceiveValue(null)
                        chooser = null
                        Toast.makeText(this@MainActivity, "Could not open the file picker.", Toast.LENGTH_LONG).show()
                    }
                    return true
                }
            }
            setDownloadListener(DownloadListener { url, userAgent, disposition, _, _ ->
                val uri = Uri.parse(url)
                if (uri.host == currentHost() && uri.path == "/api/download") {
                    val name = uri.getQueryParameter("name") ?: return@DownloadListener
                    if (name !in queuedDownloads && queueDownload(name, userAgent)) queuedDownloads.add(name)
                }
            })
        }
        pcPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(setup); addView(web, LinearLayout.LayoutParams(-1, 0, 1f)) }
        phonePanel = buildPhonePanel().apply { visibility = View.GONE }
        val modes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        modes.addView(Button(this).apply { text = "Windows PC"; setOnClickListener { showPcMode() } }, LinearLayout.LayoutParams(0, -2, 1f))
        modes.addView(Button(this).apply { text = "Phone to phone"; setOnClickListener { showPhoneMode() } }, LinearLayout.LayoutParams(0, -2, 1f))
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(modes); addView(pcPanel, LinearLayout.LayoutParams(-1, 0, 1f)); addView(phonePanel, LinearLayout.LayoutParams(-1, 0, 1f)) }
        setContentView(root)
        address.setText(getPreferences(0).getString("host", ""))
        showSetup(true)
        pollHandler.post(pollTask)
    }

    private fun currentHost() = getPreferences(0).getString("host", "") ?: ""
    private fun buildPhonePanel(): ScrollView {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 24, 32, 32) }
        content.addView(TextView(this).apply { text = "Share with another phone"; textSize = 23f })
        content.addView(TextView(this).apply {
            text = "Put both phones on the same Wi-Fi. One phone can turn on its hotspot and the other can join it. Keep ZiAr open on both phones."
            textSize = 16f
        })
        peerCode = TextView(this).apply { textSize = 26f; setPadding(0, 24, 0, 8) }
        content.addView(peerCode)
        peerAddresses = TextView(this).apply { textSize = 14f; setTextIsSelectable(true) }
        content.addView(peerAddresses)
        content.addView(TextView(this).apply { text = "Received files go to Downloads / ZiAr Received"; setPadding(0, 16, 0, 16) })
        content.addView(TextView(this).apply { text = "Nearby phones"; textSize = 20f })
        peerList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(peerList)
        content.addView(TextView(this).apply { text = "If a phone does not appear, enter its address shown above:"; setPadding(0, 24, 0, 4) })
        manualPeerAddress = EditText(this).apply { hint = "Other phone's IP address"; inputType = InputType.TYPE_CLASS_TEXT }
        content.addView(manualPeerAddress)
        content.addView(Button(this).apply { text = "Send by address"; setOnClickListener {
            val address = manualPeerAddress.text.toString().trim()
            if (validIpv4(address)) choosePeer(address, address) else peerStatus.text = "Enter the other phone's IPv4 address."
        } })
        if (Build.VERSION.SDK_INT < 29) content.addView(Button(this).apply { text = "Allow saving received files"; setOnClickListener { requestOldStoragePermission() } })
        peerStatus = TextView(this).apply { textSize = 16f; setPadding(0, 20, 0, 0) }
        content.addView(peerStatus)
        return ScrollView(this).apply { addView(content) }
    }

    private fun showPhoneMode() {
        if (phoneMode) return
        phoneMode = true
        stopDiscovery()
        pcPanel.visibility = View.GONE
        phonePanel.visibility = View.VISIBLE
        val sharing = PhonePeerService(this,
            { phones -> runOnUiThread { if (phoneMode) { renderPeers(phones); peerAddresses.text = "This phone's addresses: ${peerService?.addresses()?.joinToString()?.ifBlank { "Turn on Wi-Fi or hotspot" } ?: "Unavailable"}" } } },
            { name -> runOnUiThread { if (phoneMode) { peerStatus.text = "Received $name in Downloads / ZiAr Received"; Toast.makeText(this, "Received $name", Toast.LENGTH_LONG).show() } } },
            { error -> runOnUiThread { if (phoneMode) peerStatus.text = error } })
        peerService = sharing
        peerCode.text = "Your receive code: ${sharing.code}"
        peerAddresses.text = "This phone's addresses: ${sharing.addresses().joinToString().ifBlank { "Turn on Wi-Fi or hotspot" }}"
        renderPeers(emptyList())
        peerStatus.text = if (sharing.start()) "Searching for nearby phones…" else "Phone sharing could not start."
        requestOldStoragePermission()
    }

    private fun showPcMode() {
        if (!phoneMode) return
        phoneMode = false
        peerService?.stop(); peerService = null
        chosenPeerAddress = null; chosenPeerCode = null
        phonePanel.visibility = View.GONE
        pcPanel.visibility = View.VISIBLE
        if (setup.visibility == View.VISIBLE) startDiscovery()
    }

    private fun renderPeers(phones: List<NearbyPhone>) {
        peerList.removeAllViews()
        if (phones.isEmpty()) peerList.addView(TextView(this).apply { text = "Looking for another phone…" })
        else for (phone in phones) peerList.addView(Button(this).apply {
            text = "Send to ${phone.name} (${phone.address})"
            setOnClickListener { choosePeer(phone.address, phone.name) }
        })
    }

    private fun validIpv4(address: String): Boolean = Regex("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$").matches(address) && address.split('.').all { it.toInt() <= 255 }

    private fun choosePeer(address: String, name: String) {
        val entry = EditText(this).apply { hint = "Six-digit receive code"; inputType = InputType.TYPE_CLASS_NUMBER; filters = arrayOf(InputFilter.LengthFilter(6)) }
        AlertDialog.Builder(this).setTitle("Send to $name").setMessage("Enter the code shown on the other phone.").setView(entry)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Choose files") { _, _ ->
                val code = entry.text.toString()
                if (!Regex("[0-9]{6}").matches(code)) { peerStatus.text = "Enter the six-digit code from the other phone."; return@setPositiveButton }
                chosenPeerAddress = address; chosenPeerCode = code
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try { startActivityForResult(Intent.createChooser(intent, "Choose files to send"), peerChooserRequest) }
                catch (error: Exception) { peerStatus.text = "Could not open the file picker: ${error.message}" }
            }.show()
    }

    private fun requestOldStoragePermission() {
        if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), storageRequest)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == storageRequest && grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED)
            peerStatus.text = "Allow file storage to receive files on this phone."
    }
    private fun selectedName(uri: Uri): String {
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        return name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "file"
    }
    private fun uploadStatus(message: String, completed: Boolean? = null) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            val style = when (completed) { true -> "success small"; false -> "error small"; null -> "small" }
            web.evaluateJavascript("(function(){var m=document.getElementById('message');if(m){m.textContent=${JSONObject.quote(message)};m.className='$style';}})()", null)
            if (completed != null) Toast.makeText(this, message, if (completed) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
        }
    }
    private fun uploadSelectedFiles(files: List<Uri>) {
        val host = currentHost()
        val cookie = CookieManager.getInstance().getCookie("http://$host:47831/")
        if (cookie.isNullOrBlank()) { uploadStatus("Pair your phone with the PC again.", false); return }
        Thread {
            for ((index, uri) in files.withIndex()) {
                var connection: HttpURLConnection? = null
                try {
                    val name = selectedName(uri)
                    uploadStatus("Sending ${index + 1} of ${files.size}: $name")
                    val url = Uri.parse("http://$host:47831/api/upload").buildUpon().appendQueryParameter("name", name).build().toString()
                    val active = (URL(url).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        doOutput = true
                        connectTimeout = 10000
                        readTimeout = 30000
                        setRequestProperty("Cookie", cookie)
                        setRequestProperty("Content-Type", "application/octet-stream")
                        setChunkedStreamingMode(64 * 1024)
                    }
                    connection = active
                    val input = contentResolver.openInputStream(uri) ?: throw IOException("Could not read the selected file.")
                    input.use { source ->
                        active.outputStream.use { destination ->
                            val buffer = ByteArray(64 * 1024)
                            var count = 0L
                            while (true) {
                                val length = source.read(buffer)
                                if (length < 0) break
                                count += length
                                if (count > 1024L * 1024 * 1024) throw IOException("File is larger than 1 GB.")
                                destination.write(buffer, 0, length)
                            }
                        }
                    }
                    if (active.responseCode != 201) {
                        val response = active.errorStream?.bufferedReader()?.use { it.readText() }
                        val reason = try { JSONObject(response ?: "").optString("error") } catch (_: Exception) { "" }
                        throw IOException(reason.ifBlank { "PC rejected the file (${active.responseCode})." })
                    }
                } catch (error: Exception) {
                    uploadStatus("Could not send file ${index + 1}: ${error.message ?: "Unknown error"}", false)
                    return@Thread
                } finally { connection?.disconnect() }
            }
            uploadStatus("Sent ${files.size} file${if (files.size == 1) "" else "s"} to your PC.", true)
        }.start()
    }
    private fun queueDownload(name: String, userAgent: String = web.settings.userAgentString): Boolean {
        return try {
            val url = Uri.parse("http://${currentHost()}:47831/api/download").buildUpon().appendQueryParameter("name", name).build()
            val request = DownloadManager.Request(url)
                .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url.toString()) ?: "")
                .addRequestHeader("User-Agent", userAgent)
                .setTitle(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            Toast.makeText(this, "Receiving $name", Toast.LENGTH_SHORT).show()
            true
        } catch (e: Exception) {
            Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun pollForPcFiles() {
        val base = "http://${currentHost()}:47831"
        val cookie = CookieManager.getInstance().getCookie(base) ?: return
        pollInFlight = true
        Thread {
            var connection: HttpURLConnection? = null
            try {
                val active = (URL("$base/api/files").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3000
                    readTimeout = 3000
                    setRequestProperty("Cookie", cookie)
                }
                connection = active
                if (active.responseCode == 200) {
                    val names = JSONArray(active.inputStream.bufferedReader().use { it.readText() })
                    val pending = (0 until names.length()).map { names.getJSONObject(it).getString("name") }
                    runOnUiThread {
                        if (isActive && web.visibility == View.VISIBLE && currentHost() == Uri.parse(base).host) {
                            queuedDownloads.retainAll(pending.toSet())
                            for (name in pending) if (name !in queuedDownloads && queueDownload(name)) queuedDownloads.add(name)
                        }
                    }
                }
            } catch (_: Exception) { /* Retry while paired and the PC is available. */ }
            finally { connection?.disconnect(); runOnUiThread { pollInFlight = false } }
        }.start()
    }
    private fun startDiscovery() {
        stopDiscovery()
        discoveryStatus.text = "Searching for your Windows PC on Wi-Fi…"
        discovering = true
        val generation = discoveryGeneration
        Thread {
            var socket: DatagramSocket? = null
            try {
                val bound = DatagramSocket(47832).also { it.soTimeout = 1000 }
                socket = bound
                if (generation != discoveryGeneration) { bound.close(); return@Thread }
                discoverySocket = bound
                val buffer = ByteArray(32)
                while (discovering && generation == discoveryGeneration) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        bound.receive(packet)
                        if (String(packet.data, 0, packet.length, Charsets.US_ASCII) == "ZIAR/1") {
                            val host = packet.address.hostAddress ?: continue
                            runOnUiThread { if (discovering && generation == discoveryGeneration && setup.visibility == View.VISIBLE) { address.setText(host); connect() } }
                            break
                        }
                    } catch (_: SocketTimeoutException) { /* Check again while the app is open. */ }
                }
            } catch (_: Exception) {
                runOnUiThread { if (discovering && generation == discoveryGeneration) discoveryStatus.text = "Automatic discovery is unavailable. Enter the PC address below." }
            } finally { socket?.close(); if (discoverySocket === socket) discoverySocket = null }
        }.start()
    }
    private fun stopDiscovery() { discovering = false; discoveryGeneration++; discoverySocket?.close(); discoverySocket = null }
    private fun connect() {
        val host = address.text.toString().trim()
        if (!Regex("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$").matches(host) || host.split('.').any { it.toInt() > 255 }) {
            Toast.makeText(this, "Enter the PC's IPv4 address shown in ZiAr.", Toast.LENGTH_LONG).show(); return
        }
        getPreferences(0).edit().putString("host", host).apply()
        stopDiscovery()
        setup.visibility = View.GONE; web.visibility = View.VISIBLE
        web.loadUrl("http://$host:47831/")
    }
    private fun showSetup(search: Boolean) { setup.visibility = View.VISIBLE; web.visibility = View.GONE; if (search) startDiscovery() }
    private fun selectedFiles(resultCode: Int, data: Intent?): List<Uri> {
        if (resultCode != RESULT_OK) return emptyList()
        val files = mutableListOf<Uri>()
        data?.clipData?.let { selected -> for (i in 0 until selected.itemCount) files.add(selected.getItemAt(i).uri) }
        data?.data?.let { files.add(it) }
        return files.distinct()
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == chooserRequest) {
            val files = selectedFiles(resultCode, data)
            chooser?.onReceiveValue(null)
            chooser = null
            if (files.isEmpty()) Toast.makeText(this, "No files were selected.", Toast.LENGTH_SHORT).show()
            else uploadSelectedFiles(files)
        } else if (requestCode == peerChooserRequest) {
            val files = selectedFiles(resultCode, data)
            val address = chosenPeerAddress
            val code = chosenPeerCode
            chosenPeerAddress = null; chosenPeerCode = null
            if (files.isEmpty()) peerStatus.text = "No files were selected."
            else if (address != null && code != null) peerService?.sendFiles(address, code, files) { message, done ->
                runOnUiThread {
                    if (phoneMode) peerStatus.text = message
                    if (done != null) Toast.makeText(this, message, if (done) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (phoneMode) showPcMode() else if (web.visibility == View.VISIBLE) showSetup(true) else super.onBackPressed() }
    override fun onResume() { super.onResume(); isActive = true }
    override fun onPause() { isActive = false; super.onPause() }
    override fun onDestroy() { pollHandler.removeCallbacks(pollTask); stopDiscovery(); peerService?.stop(); web.destroy(); super.onDestroy() }
}
