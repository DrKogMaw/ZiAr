package com.ziar.transfer

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.InputType
import android.text.InputFilter
import android.view.View
import android.view.Gravity
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
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
    private lateinit var setup: ScrollView
    private lateinit var discoveryStatus: TextView
    private lateinit var discoveryProgress: ProgressBar
    private lateinit var manualConnection: LinearLayout
    private lateinit var pcPanel: LinearLayout
    private lateinit var phonePanel: ScrollView
    private lateinit var peerStatus: TextView
    private lateinit var peerCode: TextView
    private lateinit var peerAddresses: TextView
    private lateinit var peerList: LinearLayout
    private lateinit var pcTab: Button
    private lateinit var phoneTab: Button
    private var peerService: PhonePeerService? = null
    private val updater by lazy { PcUpdateManager(this) }
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
        window.statusBarColor = Color.rgb(11, 16, 32)
        window.navigationBarColor = Color.rgb(11, 16, 32)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setup = buildSetup()
        web = WebView(this).apply {
            setBackgroundColor(Color.rgb(11, 16, 32))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = true
            settings.userAgentString = "${settings.userAgentString} ZiArAndroid"
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                    if (request.url.scheme == "ziar" && request.url.host == "change-pc") {
                        showSetup(false)
                        manualConnection.visibility = View.VISIBLE
                        return true
                    }
                    return request.url.host != currentHost()
                }
                override fun onPageFinished(view: WebView, url: String) {
                    if (url.startsWith("http://${currentHost()}:47831/"))
                        view.evaluateJavascript("(function(){var h=document.querySelector('main > .row');if(h)h.style.display='none'})()", null)
                }
                override fun onReceivedError(view: WebView, request: android.webkit.WebResourceRequest, error: android.webkit.WebResourceError) {
                    if (request.isForMainFrame) {
                        showSetup(false)
                        discoveryProgress.visibility = View.GONE
                        discoveryStatus.text = "PC unavailable. Check Wi-Fi or enter its address."
                        manualConnection.visibility = View.VISIBLE
                    }
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
        pcPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(setup, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        phonePanel = buildPhonePanel().apply { visibility = View.GONE }
        val header = buildHeader()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(11, 16, 32))
            addView(header)
            addView(pcPanel, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(phonePanel, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(0, bars.top, 0, maxOf(bars.bottom, keyboard.bottom))
            insets
        }
        setContentView(root)
        updateModeTabs()
        address.setText(getPreferences(0).getString("host", ""))
        showSetup(true)
        pollHandler.post(pollTask)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    private val ink get() = Color.rgb(240, 246, 255)
    private val muted get() = Color.rgb(166, 180, 207)
    private val accent get() = Color.rgb(141, 213, 255)
    private val surface get() = Color.rgb(21, 30, 52)
    private val outline get() = Color.rgb(43, 58, 88)

    private fun shape(color: Int, radius: Int, border: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (border != null) setStroke(dp(1), border)
    }

    private fun touchSurface(color: Int, radius: Int) = RippleDrawable(
        ColorStateList.valueOf(Color.argb(50, 255, 255, 255)), shape(color, radius), shape(Color.WHITE, radius)
    )

    private fun label(value: String, size: Float, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun action(value: String, primary: Boolean = true, click: () -> Unit) = Button(this).apply {
        text = value
        isAllCaps = false
        textSize = 16f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (primary) Color.rgb(6, 20, 38) else ink)
        backgroundTintList = null
        background = touchSurface(if (primary) accent else Color.rgb(34, 52, 87), 14)
        stateListAnimator = null
        minHeight = dp(56)
        setOnClickListener { click() }
    }

    private fun buildSetup(): ScrollView {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(36), dp(24), dp(32))
        }
        content.addView(ImageView(this).apply {
            setImageResource(R.drawable.ziar_ic_computer)
            setColorFilter(accent)
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = shape(surface, 16)
        }, LinearLayout.LayoutParams(dp(64), dp(64)))
        content.addView(label("Find your PC", 28f, ink, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(28) })
        content.addView(label("Open ZiAr on your computer.", 15f, muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(15))
            background = shape(surface, 14, outline)
        }
        discoveryProgress = ProgressBar(this, null, android.R.attr.progressBarStyleSmall).apply {
            indeterminateTintList = ColorStateList.valueOf(accent)
        }
        statusRow.addView(discoveryProgress, LinearLayout.LayoutParams(dp(22), dp(22)))
        discoveryStatus = label("Scanning Wi-Fi…", 15f)
        statusRow.addView(discoveryStatus, LinearLayout.LayoutParams(-1, -2).apply { marginStart = dp(14) })
        content.addView(statusRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(30) })

        manualConnection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        address = EditText(this).apply {
            hint = "PC address · 192.168.1.5"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            textSize = 16f
            setTextColor(ink)
            setHintTextColor(muted)
            setPadding(dp(16), 0, dp(16), 0)
            background = shape(surface, 13, outline)
        }
        manualConnection.addView(address, LinearLayout.LayoutParams(-1, dp(56)))
        manualConnection.addView(action("Connect to PC") { connect() }, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(12) })
        content.addView(manualConnection, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

        val links = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        links.addView(label("Enter address", 14f, accent, true).apply {
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { manualConnection.visibility = View.VISIBLE; address.requestFocus() }
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        links.addView(label("Search again", 14f, muted, true).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setOnClickListener { startDiscovery() }
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        content.addView(links, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        return ScrollView(this).apply { isVerticalScrollBarEnabled = false; isFillViewport = true; addView(content) }
    }

    private fun buildHeader(): LinearLayout {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(12))
            setBackgroundColor(Color.rgb(11, 16, 32))
        }
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(label("ZiAr", 24f, ink, true).apply {
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { gravity = Gravity.CENTER_VERTICAL })
        titleRow.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ziar_ic_update)
            setColorFilter(accent)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(12), dp(12), dp(12), dp(12))
            backgroundTintList = null
            background = touchSurface(surface, 14)
            contentDescription = "Check for Android updates"
            setOnClickListener { updater.check(currentHost()) }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(titleRow)

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = shape(surface, 15)
        }
        pcTab = modeTab("Computer") { showPcMode() }
        phoneTab = modeTab("Nearby") { showPhoneMode() }
        phoneTab.contentDescription = "Nearby phones"
        tabs.addView(pcTab, LinearLayout.LayoutParams(0, dp(48), 1f))
        tabs.addView(phoneTab, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(4) })
        header.addView(tabs, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        return header
    }

    private fun modeTab(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        minHeight = dp(48)
        setPadding(dp(8), 0, dp(8), 0)
        backgroundTintList = null
        stateListAnimator = null
        setOnClickListener { action() }
    }

    private fun updateModeTabs() {
        for ((button, active) in listOf(pcTab to !phoneMode, phoneTab to phoneMode)) {
            button.isSelected = active
            button.setTextColor(if (active) ink else muted)
            button.background = touchSurface(if (active) Color.rgb(39, 62, 99) else surface, 11)
        }
    }

    private fun currentHost() = getPreferences(0).getString("host", "") ?: ""
    private fun buildPhonePanel(): ScrollView {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(24), dp(24), dp(32)) }
        val heading = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        heading.addView(label("Phone sharing", 25f, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ziar_ic_help)
            setColorFilter(accent)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(12), dp(12), dp(12), dp(12))
            backgroundTintList = null
            background = touchSurface(surface, 14)
            contentDescription = "How phone sharing works"
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity).setTitle("Share nearby")
                    .setMessage("Keep ZiAr open on both phones. Use the same Wi-Fi, or connect one phone to the other's hotspot. Tap a phone below and enter its receive code.\n\nThis phone's address: ${peerAddresses.text}")
                    .setPositiveButton("Got it", null).show()
            }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        content.addView(heading)
        content.addView(label("Send and receive on the same Wi-Fi.", 15f, muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })

        val receiveCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = shape(surface, 16, outline)
        }
        receiveCard.addView(label("Your receive code", 14f, muted, true))
        peerCode = label("······", 34f, accent, true).apply { letterSpacing = 0.12f; setTextIsSelectable(true) }
        receiveCard.addView(peerCode, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        receiveCard.addView(label("Share this code with the sender", 14f, muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
        content.addView(receiveCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(26) })

        content.addView(label("Available phones", 19f, ink, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(30) })
        peerStatus = label("Looking for phones…", 14f, muted)
        content.addView(peerStatus, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        peerList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(peerList, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        content.addView(label("Connect by address", 15f, accent, true).apply {
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { openPeerAddressDialog() }
        }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(10) })
        content.addView(label("Received files save to Downloads / ZiAr Received", 13f, muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        if (Build.VERSION.SDK_INT < 29) content.addView(action("Allow file storage", false) { requestOldStoragePermission() }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(16) })
        peerAddresses = TextView(this)
        return ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(content) }
    }

    private fun showPhoneMode() {
        if (phoneMode) return
        phoneMode = true
        updateModeTabs()
        stopDiscovery()
        pcPanel.visibility = View.GONE
        phonePanel.visibility = View.VISIBLE
        val sharing = PhonePeerService(this,
            { phones -> runOnUiThread { if (phoneMode) { renderPeers(phones); peerAddresses.text = peerService?.addresses()?.joinToString()?.ifBlank { "Turn on Wi-Fi or hotspot" } ?: "Unavailable" } } },
            { name -> runOnUiThread { if (phoneMode) { peerStatus.text = "Received $name in Downloads / ZiAr Received"; Toast.makeText(this, "Received $name", Toast.LENGTH_LONG).show() } } },
            { error -> runOnUiThread { if (phoneMode) peerStatus.text = error } })
        peerService = sharing
        peerCode.text = sharing.code
        peerAddresses.text = sharing.addresses().joinToString().ifBlank { "Turn on Wi-Fi or hotspot" }
        renderPeers(emptyList())
        peerStatus.text = if (sharing.start()) "Looking for phones…" else "Phone sharing could not start."
        requestOldStoragePermission()
    }

    private fun showPcMode() {
        if (!phoneMode) return
        phoneMode = false
        updateModeTabs()
        peerService?.stop(); peerService = null
        chosenPeerAddress = null; chosenPeerCode = null
        phonePanel.visibility = View.GONE
        pcPanel.visibility = View.VISIBLE
        if (setup.visibility == View.VISIBLE) startDiscovery()
    }

    private fun renderPeers(phones: List<NearbyPhone>) {
        peerList.removeAllViews()
        if (phones.isEmpty()) {
            val empty = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(18), dp(18), dp(18))
                background = shape(surface, 15)
            }
            empty.addView(ImageView(this).apply {
                setImageResource(R.drawable.ziar_ic_phone)
                setColorFilter(muted)
            }, LinearLayout.LayoutParams(dp(28), dp(28)))
            empty.addView(label("No phones found yet", 15f, muted), LinearLayout.LayoutParams(-1, -2).apply { marginStart = dp(14) })
            peerList.addView(empty)
        } else for (phone in phones) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = touchSurface(surface, 15)
                isClickable = true
                isFocusable = true
                contentDescription = "Send files to ${phone.name}"
                setOnClickListener { choosePeer(phone.address, phone.name) }
            }
            row.addView(ImageView(this).apply {
                setImageResource(R.drawable.ziar_ic_phone)
                setColorFilter(accent)
            }, LinearLayout.LayoutParams(dp(26), dp(26)))
            val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            details.addView(label(phone.name, 16f, ink, true))
            details.addView(label(phone.address, 13f, muted))
            row.addView(details, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) })
            row.addView(label("Send", 14f, accent, true))
            peerList.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    private fun openPeerAddressDialog() {
        val entry = EditText(this).apply {
            hint = "Other phone's IP address"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setTextColor(ink)
            setHintTextColor(muted)
            setPadding(dp(16), 0, dp(16), 0)
            background = shape(surface, 12, outline)
        }
        val container = LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(entry, LinearLayout.LayoutParams(-1, dp(56))) }
        val dialog = AlertDialog.Builder(this).setTitle("Connect by address")
            .setMessage("Enter the address shown on the other phone.")
            .setView(container).setNegativeButton("Cancel", null).setPositiveButton("Continue", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val address = entry.text.toString().trim()
                if (validIpv4(address)) { dialog.dismiss(); choosePeer(address, address) }
                else entry.error = "Enter a valid IPv4 address"
            }
        }
        dialog.show()
    }

    private fun validIpv4(address: String): Boolean = Regex("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$").matches(address) && address.split('.').all { it.toInt() <= 255 }

    private fun choosePeer(address: String, name: String) {
        val entry = EditText(this).apply {
            hint = "Six-digit receive code"
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(6))
            setSingleLine(true)
            setTextColor(ink)
            setHintTextColor(muted)
            setPadding(dp(16), 0, dp(16), 0)
            background = shape(surface, 12, outline)
        }
        val container = LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(entry, LinearLayout.LayoutParams(-1, dp(56))) }
        val dialog = AlertDialog.Builder(this).setTitle("Send to $name")
            .setMessage("Enter the code on the other phone.").setView(container)
            .setNegativeButton("Cancel", null).setPositiveButton("Choose files", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val code = entry.text.toString()
                if (!Regex("[0-9]{6}").matches(code)) { entry.error = "Enter six digits"; return@setOnClickListener }
                dialog.dismiss()
                chosenPeerAddress = address; chosenPeerCode = code
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try { startActivityForResult(Intent.createChooser(intent, "Choose files to send"), peerChooserRequest) }
                catch (error: Exception) { peerStatus.text = "Could not open the file picker: ${error.message}" }
            }
        }
        dialog.show()
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
        val name = try { contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } } catch (_: Exception) { null }
        return name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "file"
    }
    private fun selectedSize(uri: Uri): Long? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0).takeIf { it >= 0 } else null
        }
    } catch (_: Exception) { null }

    private fun uploadProgress(sent: Long, total: Long?) {
        val value = total?.takeIf { it > 0 }?.let { ((sent.toDouble() / it) * 100).toInt().coerceIn(0, 100) }
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            val progress = if (value == null) "p.removeAttribute('value')" else "p.value=$value"
            val label = if (value == null) "Uploading…" else "$value% sent"
            web.evaluateJavascript("(function(){var b=document.getElementById('progressBox'),p=document.getElementById('progress'),t=document.getElementById('progressText');if(b&&p){b.classList.remove('hidden');$progress}if(t)t.textContent=${JSONObject.quote(label)}})()", null)
        }
    }
    private fun uploadStatus(message: String, completed: Boolean? = null) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            val style = when (completed) { true -> "success small"; false -> "error small"; null -> "small" }
            val hideProgress = if (completed != null) "var b=document.getElementById('progressBox');if(b)b.classList.add('hidden');" else ""
            web.evaluateJavascript("(function(){var m=document.getElementById('message');if(m){m.textContent=${JSONObject.quote(message)};m.className='$style';}$hideProgress})()", null)
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
                    val total = selectedSize(uri)
                    uploadStatus("Sending ${index + 1} of ${files.size}: $name")
                    uploadProgress(0, total)
                    val url = Uri.parse("http://$host:47831/api/upload").buildUpon().appendQueryParameter("name", name).build().toString()
                    val active = (URL(url).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        doOutput = true
                        connectTimeout = 15000
                        readTimeout = 120000
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
                            var lastProgress = 0L
                            while (true) {
                                val length = source.read(buffer)
                                if (length < 0) break
                                count += length
                                destination.write(buffer, 0, length)
                                val now = SystemClock.elapsedRealtime()
                                if (now - lastProgress >= 250) { uploadProgress(count, total); lastProgress = now }
                            }
                            uploadProgress(count, total)
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
        discoveryStatus.text = "Scanning Wi-Fi…"
        discoveryProgress.visibility = View.VISIBLE
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
                runOnUiThread { if (discovering && generation == discoveryGeneration) {
                    discoveryStatus.text = "Search unavailable. Enter the PC address."
                    discoveryProgress.visibility = View.GONE
                    manualConnection.visibility = View.VISIBLE
                } }
            } finally { socket?.close(); if (discoverySocket === socket) discoverySocket = null }
        }.start()
    }
    private fun stopDiscovery() { discovering = false; discoveryGeneration++; discoverySocket?.close(); discoverySocket = null }
    private fun connect() {
        val host = address.text.toString().trim()
        if (!Regex("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$").matches(host) || host.split('.').any { it.toInt() > 255 }) {
            address.error = "Enter a valid PC address"; return
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
        } else if (requestCode == PcUpdateManager.SETTINGS_REQUEST) {
            updater.returnedFromSettings()
        }
    }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (phoneMode) showPcMode() else if (web.visibility == View.VISIBLE) showSetup(true) else super.onBackPressed() }
    override fun onResume() { super.onResume(); isActive = true }
    override fun onPause() { isActive = false; super.onPause() }
    override fun onDestroy() { pollHandler.removeCallbacks(pollTask); stopDiscovery(); peerService?.stop(); web.destroy(); super.onDestroy() }
}
