package com.ziar.transfer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.webkit.CookieManager
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONObject

internal object AppVersions {
    fun compare(left: String, right: String): Int? {
        if (!Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(left) || !Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(right)) return null
        val a = left.split('.').map { it.toLong() }
        val b = right.split('.').map { it.toLong() }
        for (i in 0..2) if (a[i] != b[i]) return a[i].compareTo(b[i])
        return 0
    }
}

internal class PcUpdateManager(private val activity: Activity) {
    companion object { const val SETTINGS_REQUEST = 45; private const val MAX_APK_BYTES = 200L * 1024 * 1024 }
    private var pendingApk: File? = null

    fun check(host: String) {
        if (host.isBlank()) { message("Connect and pair with your PC first."); return }
        val base = "http://$host:47831"
        val cookie = CookieManager.getInstance().getCookie(base)
        if (cookie.isNullOrBlank()) { message("Pair with your PC first, then check for updates."); return }
        Thread {
            try {
                val response = readInfo("$base/api/update/android", cookie)
                if (!response.optBoolean("available")) { message("No Android update is in the PC's APKs folder."); return@Thread }
                val version = response.getString("version")
                val size = response.getLong("size")
                val hash = response.getString("sha256")
                if (size < 1024 || size > MAX_APK_BYTES || !Regex("[a-f0-9]{64}").matches(hash)) throw IOException("The update information is invalid.")
                val installed = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "0.0.0"
                val comparison = AppVersions.compare(version, installed) ?: throw IOException("The update version is invalid.")
                if (comparison <= 0) { message("ZiAr $installed is up to date."); return@Thread }
                onUi {
                    AlertDialog.Builder(activity).setTitle("ZiAr $version is ready")
                        .setMessage("Download this update from your paired PC and open Android's installer?")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Download update") { _, _ -> download(base, cookie, version, size, hash) }
                        .show()
                }
            } catch (error: Exception) { message(error.message ?: "Could not check for updates.") }
        }.start()
    }

    private fun readInfo(url: String, cookie: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000; readTimeout = 10000; setRequestProperty("Cookie", cookie)
        }
        return try {
            if (connection.responseCode == 401) throw IOException("Pair with your PC first.")
            if (connection.responseCode != 200) throw IOException("Could not check the PC for updates.")
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }

    private fun download(base: String, cookie: String, version: String, size: Long, expectedHash: String) {
        Toast.makeText(activity, "Downloading ZiAr $version from your PC…", Toast.LENGTH_SHORT).show()
        Thread {
            val directory = File(activity.cacheDir, "updates")
            val partial = File(directory, "ziar-update.part")
            var connection: HttpURLConnection? = null
            try {
                if (!directory.exists() && !directory.mkdirs()) throw IOException("Could not prepare update storage.")
                val active = (URL("$base/api/update/android.apk").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000; readTimeout = 30000; setRequestProperty("Cookie", cookie)
                }
                connection = active
                if (active.responseCode != 200) throw IOException("The PC could not send the update.")
                val digest = MessageDigest.getInstance("SHA-256")
                var received = 0L
                active.inputStream.use { source -> FileOutputStream(partial).use { destination ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        received += count
                        if (received > MAX_APK_BYTES) throw IOException("The update is too large.")
                        digest.update(buffer, 0, count)
                        destination.write(buffer, 0, count)
                    }
                } }
                val actualHash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                if (received != size || actualHash != expectedHash) throw IOException("The update did not download correctly. Try again.")
                val apk = File(directory, "ZiAr-Android-$version.apk")
                if (apk.exists() && !apk.delete()) throw IOException("Could not replace the previous update download.")
                if (!partial.renameTo(apk)) throw IOException("Could not finish the update download.")
                onUi { pendingApk = apk; openInstaller(apk) }
            } catch (error: Exception) { message(error.message ?: "Could not download the update.") }
            finally { partial.delete(); connection?.disconnect() }
        }.start()
    }

    private fun openInstaller(apk: File) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(activity).setTitle("Allow ZiAr updates")
                .setMessage("Android needs your permission to install updates supplied by your PC. Return to ZiAr after allowing it.")
                .setNegativeButton("Later", null)
                .setPositiveButton("Open settings") { _, _ ->
                    try {
                        val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                        activity.startActivityForResult(settings, SETTINGS_REQUEST)
                    } catch (error: Exception) { message(error.message ?: "Could not open install settings.") }
                }.show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updatefiles", apk)
            val install = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(install)
        } catch (error: Exception) { message(error.message ?: "Could not open Android's installer.") }
    }

    fun returnedFromSettings() {
        val apk = pendingApk ?: return
        if (activity.packageManager.canRequestPackageInstalls()) openInstaller(apk)
        else message("Allow ZiAr to install updates, then tap Update again.")
    }

    private fun onUi(action: () -> Unit) = activity.runOnUiThread { if (!activity.isFinishing && !activity.isDestroyed) action() }
    private fun message(text: String) = onUi { AlertDialog.Builder(activity).setMessage(text).setPositiveButton("OK", null).show() }
}
