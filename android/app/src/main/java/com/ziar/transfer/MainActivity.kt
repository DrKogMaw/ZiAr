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
import android.widget.Toast

class MainActivity : Activity() {
    private var chooser: ValueCallback<Array<Uri>>? = null
    private lateinit var web: WebView
    private lateinit var address: EditText
    private lateinit var setup: LinearLayout
    private val chooserRequest = 42

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 24) }
        address = EditText(this).apply { hint = "PC address, e.g. 192.168.1.5"; inputType = android.text.InputType.TYPE_CLASS_TEXT }
        val connect = Button(this).apply { text = "Connect to PC"; setOnClickListener { connect() } }
        setup.addView(address); setup.addView(connect)
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
                    if (request.isForMainFrame) { showSetup(); Toast.makeText(this@MainActivity, "Cannot reach PC. Check the address and Wi-Fi.", Toast.LENGTH_LONG).show() }
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
        if (address.text.isNotBlank()) connect() else showSetup()
    }

    private fun currentHost() = getPreferences(0).getString("host", "") ?: ""
    private fun connect() {
        val host = address.text.toString().trim()
        if (!Regex("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$").matches(host) || host.split('.').any { it.toInt() > 255 }) {
            Toast.makeText(this, "Enter the PC's IPv4 address shown in ZiAr.", Toast.LENGTH_LONG).show(); return
        }
        getPreferences(0).edit().putString("host", host).apply()
        setup.visibility = View.GONE; web.visibility = View.VISIBLE
        web.loadUrl("http://$host:47831/")
    }
    private fun showSetup() { setup.visibility = View.VISIBLE; web.visibility = View.GONE }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == chooserRequest) { chooser?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data)); chooser = null }
    }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (web.visibility == View.VISIBLE) showSetup() else super.onBackPressed() }
    override fun onDestroy() { web.destroy(); super.onDestroy() }
}
