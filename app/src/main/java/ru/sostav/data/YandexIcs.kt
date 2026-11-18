package ru.sostav.data

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

private const val MAX_BYTES = 1_500_000

suspend fun downloadYandexIcs(rawUrl: String): String = withContext(Dispatchers.IO) {
    val start = normalizeYandexUrl(rawUrl)
    if (!allowedYandexAddress(start)) {
        throw AppException("Нужна ссылка экспорта iCal из Яндекс.Календаря")
    }
    var current = start
    for (hop in 0 until 5) {
        if (!allowedYandexAddress(current)) {
            throw AppException("Ссылка ушла не на Яндекс.Календарь")
        }
        val connection = (URL(current).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "GroupCalendar")
            setRequestProperty("Accept", "text/calendar, text/plain, */*")
        }
        try {
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                    ?: throw AppException("Яндекс не отдал календарь")
                current = URL(URL(current), location).toString()
                continue
            }
            if (code !in 200..299) throw AppException("Яндекс ответил кодом $code")
            val length = connection.contentLengthLong
            if (length > MAX_BYTES) throw AppException("Файл календаря слишком большой")
            val bytes = readLimited(connection)
            val charset = charsetFrom(connection.contentType)
            return@withContext bytes.toString(charset)
        } finally {
            connection.disconnect()
        }
    }
    throw AppException("Слишком много переходов по ссылке календаря")
}

fun normalizeYandexUrl(rawUrl: String): String {
    val trimmed = rawUrl.trim().replace("\n", "").replace("\r", "")
    return when {
        trimmed.startsWith("webcal://", ignoreCase = true) -> "https://" + trimmed.substring(9)
        trimmed.startsWith("https://", ignoreCase = true) -> trimmed
        trimmed.startsWith("http://", ignoreCase = true) -> "https://" + trimmed.removePrefix("http://")
        else -> trimmed
    }
}

private fun allowedYandexAddress(url: String): Boolean {
    val uri = Uri.parse(url)
    val host = uri.host?.lowercase() ?: return false
    if (uri.scheme != "https") return false
    return host == "yandex.ru" ||
        host == "yandex.com" ||
        host == "ya.ru" ||
        host.endsWith(".yandex.ru") ||
        host.endsWith(".yandex.com") ||
        host.endsWith(".ya.ru")
}

private fun readLimited(connection: HttpURLConnection): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    connection.inputStream.use { input ->
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_BYTES) throw AppException("Файл календаря слишком большой")
            out.write(buffer, 0, count)
        }
    }
    return out.toByteArray()
}

private fun charsetFrom(contentType: String?): Charset {
    val name = contentType
        ?.split(';')
        ?.map { it.trim() }
        ?.find { it.startsWith("charset=", ignoreCase = true) }
        ?.substringAfter('=')
        ?.trim()
        ?.trim('"')
    return runCatching { Charset.forName(name ?: "UTF-8") }.getOrDefault(Charsets.UTF_8)
}
