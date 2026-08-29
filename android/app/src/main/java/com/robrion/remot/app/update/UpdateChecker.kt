package com.robrion.remot.update

import com.robrion.remot.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * A published GitHub release the app knows how to install from. [versionCode] is
 * derived from the V/C/P tag using the same formula as the APK
 * (V*100000 + C*100 + P), so "newer" is a monotonic numeric comparison.
 */
data class ReleaseInfo(
    val tagName: String,
    val versionName: String,
    val versionCode: Long,
    val apkName: String,
    val apkUrl: String,
) {
    companion object {
        /**
         * Derive the numeric versionCode the APK would carry from a V/C/P git tag
         * (e.g. `v2c002` → 200200, `v2c002p03` → 200203). Returns null for tags that
         * are not production V/C/P tags.
         */
        fun versionCodeOfTag(tag: String): Long? {
            val m = Regex(
                "^v([0-9])c([0-9]{3})(?:p([0-9]{2}))?$",
                RegexOption.IGNORE_CASE
            ).find(tag.trim()) ?: return null
            val v = m.groupValues[1].toLong()
            val c = m.groupValues[2].toLong()
            val p = m.groupValues.getOrNull(3)?.takeIf { it.isNotEmpty() }?.toLong() ?: 0L
            return v * 100000L + c * 100L + p
        }
    }
}

/** UI-facing lifecycle of an in-app update. */
sealed interface UpdateInfoState {
    /** A newer release was found; offer to download. */
    data class Available(val release: ReleaseInfo) : UpdateInfoState
    /** Download in progress, 0..100. */
    data class Downloading(val release: ReleaseInfo, val progress: Int) : UpdateInfoState
    /** APK downloaded and the system installer was launched. */
    data class InstallStarted(val release: ReleaseInfo) : UpdateInfoState
    /** Download/install failed; message is user-fixable. */
    data class Error(val release: ReleaseInfo, val message: String) : UpdateInfoState
}

/**
 * Fetches the latest *published* (non-draft, non-prerelease) Remot release from
 * the GitHub Releases API and parses the APK asset + V/C/P version. Safe to call
 * off the main thread (OkHttp + org.json). Returns null on any network/rate-limit
 * failure so a transient GitHub hiccup never bothers the user.
 */
class UpdateChecker(
    private val client: OkHttpClient = OkHttpClient(),
    private val repo: String = "robprian/remot",
) {

    /** The resolved latest release, or null if none/parse-failed/unreachable. */
    fun fetchLatest(): ReleaseInfo? {
        return try {
            val req = Request.Builder()
                .url("https://api.github.com/repos/$repo/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Remot-Android/${BuildConfig.VERSION_NAME}")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                parseRelease(JSONObject(body))
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseRelease(json: JSONObject): ReleaseInfo? {
        val tag = json.optString("tag_name")
        val versionCode = ReleaseInfo.versionCodeOfTag(tag) ?: return null
        val versionName = tag.trim().uppercase()
        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            if (name.isNotEmpty() && url.isNotEmpty() &&
                name.endsWith(".apk", ignoreCase = true) &&
                !name.contains("debug", ignoreCase = true) &&
                !name.contains("split", ignoreCase = true)
            ) {
                return ReleaseInfo(tag, versionName, versionCode, name, url)
            }
        }
        return null
    }
}