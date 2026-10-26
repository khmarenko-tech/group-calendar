package ru.sostav.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.sostav.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

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
        if (connection.responseCode !in 200..299) return@withContext null
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        parseLatestRelease(body, currentVersion)
    } finally {
        connection.disconnect()
    }
}

suspend fun downloadUpdate(offer: UpdateOffer, dest: File, onProgress: (Float) -> Unit) {
    withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        if (dest.exists() && !dest.delete()) error("Не удалось подготовить файл обновления.")
        val connection = open(offer.url, "application/octet-stream")
        try {
            if (connection.responseCode !in 200..299) error("Сервер отдал код ${connection.responseCode}.")
            val declared = connection.contentLengthLong
            if (declared > MAX_BYTES) error("Файл обновления слишком большой.")
            var received = 0L
            var reported = -1f
            connection.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (coroutineContext.isActive) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        if (received > MAX_BYTES || (offer.size > 0 && received > offer.size)) {
                            dest.delete()
                            error("Файл обновления слишком большой.")
                        }
                        output.write(buffer, 0, count)
                        val total = if (declared > 0) declared else offer.size
                        if (total > 0) {
                            val fraction = received.toFloat() / total.toFloat()
                            if (fraction - reported >= 0.02f) {
                                reported = fraction
                                withContext(Dispatchers.Main) { onProgress(fraction.coerceIn(0f, 1f)) }
                            }
                        }
                    }
                }
            }
            if (!coroutineContext.isActive) {
                dest.delete()
            }
        } catch (error: Throwable) {
            dest.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }
}

fun verifyUpdate(context: Context, file: File): String? {
    if (!file.isFile || file.length() <= 0L || file.length() > MAX_BYTES) {
        return "Файл обновления не скачался целиком."
    }
    val manager = context.packageManager
    val flags = if (Build.VERSION.SDK_INT >= 28) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        @Suppress("DEPRECATION")
        PackageManager.GET_SIGNATURES
    }
    @Suppress("DEPRECATION")
    val archive = manager.getPackageArchiveInfo(file.absolutePath, flags)
        ?: return "Файл обновления не читается."
    if (archive.packageName != context.packageName) {
        return "Это не пакет «Календаря группы»."
    }
    @Suppress("DEPRECATION")
    val installed = manager.getPackageInfo(context.packageName, flags)
    if (!sameCertificates(certificates(installed), certificates(archive))) {
        return "Подпись файла не совпадает с установленным приложением. Обновление отклонено."
    }
    val archiveCode = if (Build.VERSION.SDK_INT >= 28) {
        archive.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        archive.versionCode.toLong()
    }
    if (archiveCode <= BuildConfig.VERSION_CODE) {
        return "В файле номер сборки не выше уже установленной."
    }
    return null
}

fun canRequestInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

fun openInstallPermission(context: Context) {
    val intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

fun openInstaller(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
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

private fun certificates(info: PackageInfo): List<ByteArray> {
    val signatures = if (Build.VERSION.SDK_INT >= 28) {
        info.signingInfo?.apkContentsSigners
    } else {
        @Suppress("DEPRECATION")
        info.signatures
    } ?: return emptyList()
    return signatures.map { it.toByteArray() }
}

private fun sameCertificates(installed: List<ByteArray>, archive: List<ByteArray>): Boolean {
    if (installed.isEmpty() || installed.size != archive.size) return false
    return installed.all { left -> archive.any { right -> left.contentEquals(right) } }
}
