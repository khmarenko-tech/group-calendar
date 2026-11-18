package ru.sostav.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class UpdateOffer(val version: String, val url: String, val size: Long)

private const val MAX_BYTES = 80L * 1024L * 1024L
private val versionPattern = Regex("""^\d+\.\d+\.\d+$""")

fun isNewerVersion(remote: String, local: String): Boolean {
    val remoteParts = versionParts(remote)
    val localParts = versionParts(local)
    if (remoteParts.isEmpty()) return false
    val count = maxOf(remoteParts.size, localParts.size)
    for (index in 0 until count) {
        val difference = remoteParts.getOrElse(index) { 0 } - localParts.getOrElse(index) { 0 }
        if (difference != 0) return difference > 0
    }
    return false
}

fun parseLatestRelease(json: String, currentVersion: String): UpdateOffer? {
    val root = JSONObject(json)
    val version = root.optString("tag_name").removePrefix("v")
    if (!versionPattern.matches(version) || !isNewerVersion(version, currentVersion)) return null
    val assets = root.optJSONArray("assets") ?: return null
    val expectedName = "group-calendar-$version.apk"
    val expectedUrl = "https://github.com/khmarenko-tech/group-calendar/releases/download/v$version/$expectedName"
    for (index in 0 until assets.length()) {
        val asset = assets.optJSONObject(index) ?: continue
        if (asset.optString("name") != expectedName) continue
        if (asset.optString("browser_download_url") != expectedUrl) return null
        val size = asset.optLong("size")
        if (size <= 0L || size > MAX_BYTES) return null
        return UpdateOffer(version, expectedUrl, size)
    }
    return null
}

suspend fun fetchUpdateOffer(currentVersion: String): UpdateOffer? = withContext(Dispatchers.IO) {
    val connection = open(
        "https://api.github.com/repos/khmarenko-tech/group-calendar/releases/latest",
        "application/vnd.github+json",
    )
    try {
        val code = connection.responseCode
        if (code == 404) return@withContext null
        if (code !in 200..299) error("GitHub ответил кодом $code")
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        parseLatestRelease(body, currentVersion)
    } finally {
        connection.disconnect()
    }
}

fun openUpdatePage(context: Context, offer: UpdateOffer) {
    context.startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse(offer.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

private fun open(url: String, accept: String): HttpURLConnection {
    var current = url
    for (hop in 0 until 5) {
        if (!allowedAddress(current)) error("Адрес обновления не из релиза GitHub.")
        val connection = (URL(current).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "GroupCalendar")
            setRequestProperty("Accept", accept)
        }
        val code = connection.responseCode
        if (code in 300..399) {
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            if (location.isNullOrBlank()) error("Сервер не указал, куда скачивать.")
            current = URL(URL(current), location).toString()
            continue
        }
        return connection
    }
    error("Слишком много переходов по ссылке обновления.")
}

private fun allowedAddress(url: String): Boolean {
    val uri = Uri.parse(url)
    val host = uri.host?.lowercase() ?: return false
    if (uri.scheme != "https") return false
    return host == "github.com" || host == "api.github.com" || host.endsWith(".githubusercontent.com")
}

private fun versionParts(value: String): List<Int> {
    val clean = value.removePrefix("v")
    if (!versionPattern.matches(clean)) return emptyList()
    return clean.split('.').map { it.toInt() }
}
