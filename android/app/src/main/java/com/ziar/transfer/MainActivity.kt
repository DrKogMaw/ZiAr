package com.ziar.transfer

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
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
import android.widget.TextView
import android.widget.Toast
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException

class MainActivity : Activity() {
    private var chooser: ValueCallback<Array<Uri>>? = null
    private lateinit var web: WebView
    private lateinit var address: EditText
    private lateinit var setup: LinearLayout
    private lateinit var discoveryStatus: TextView
    private var discoverySocket: DatagramSocket? = null
    @Volatile private var discovering = false
    @Volatile private var discoveryGeneration = 0
    private val chooserRequest = 42

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
                    startActivityForResult(params.createIntent(), chooserRequest)
                    return true
                }
            }
            setDownloadListener(DownloadListener { url, userAgent, disposition, _, _ ->
                try {
                    val uri = Uri.parse(url)
                    if (uri.host != currentHost() || uri.path != "/api/download") return@DownloadListener
                    val name = uri.getQueryParameter("name") ?: "ZiAr file"
                    val request = DownloadManager.Request(uri)
                        .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                        .addRequestHeader("User-Agent", userAgent)
                        .setTitle(name)
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                    (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                    Toast.makeText(this@MainActivity, "Downloading $name", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) { Toast.makeText(this@MainActivity, "Download failed: ${e.message}", Toast.LENGTH_LONG).show() }
            })
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(setup); addView(web, LinearLayout.LayoutParams(-1, 0, 1f)) }
        setContentView(root)
        address.setText(getPreferences(0).getString("host", ""))
        showSetup(true)
    }

    private fun currentHost() = getPreferences(0).getString("host", "") ?: ""
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
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == chooserRequest) { chooser?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data)); chooser = null }
    }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (web.visibility == View.VISIBLE) showSetup(true) else super.onBackPressed() }
    override fun onDestroy() { stopDiscovery(); web.destroy(); super.onDestroy() }
}
