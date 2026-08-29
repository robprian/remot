package com.robrion.remot.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.robrion.remot.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Downloads the release APK into app-private storage and hands it to the Android
 * package installer over a FileProvider. Installation is always the user's
 * explicit action via the system dialog — we never bypass Android's security.
 */
object ApkInstaller {

    /** App-private directory the downloaded APK is written to (mapped in file_paths.xml). */
    fun targetFile(context: Context, apkName: String): File {
        val dir = context.getExternalFilesDir("updates")
            ?: File(context.cacheDir, "updates").also { it.mkdirs() }
        dir.mkdirs()
        return File(dir, apkName)
    }

    /**
     * Streams [apkUrl] into [target], reporting 0..100 progress via [onProgress]
     * (not guaranteed mid-download if the server omits Content-Length).
     */
    @Throws(IOException::class)
    fun download(context: Context, client: OkHttpClient, apkUrl: String, target: File, onProgress: (Int) -> Unit) {
        target.parentFile?.mkdirs()
        val req = Request.Builder()
            .url(apkUrl)
            .header("User-Agent", "Remot-Android/${BuildConfig.VERSION_NAME}")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("empty response")
            val total = body.contentLength()
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        read += n
                        if (total > 0) onProgress(((read * 100) / total).toInt().coerceIn(0, 100))
                    }
                }
            }
            onProgress(100)
        }
    }

    /**
     * Launches the system installer for [apk]. Returns true when the install
     * intent was dispatched. On Android where installing from this source isn't
     * yet allowed, opens the "allow from this source" settings screen and returns
     * false so the UI can guide the user back.
     */
    fun launchInstall(context: Context, apk: File): Boolean {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (se: SecurityException) {
            openInstallUnknownSources(context)
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun openInstallUnknownSources(context: Context) {
        try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            } else {
                Intent(Settings.ACTION_SECURITY_SETTINGS)
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Exception) {
            // Nothing else we can do.
        }
    }
}