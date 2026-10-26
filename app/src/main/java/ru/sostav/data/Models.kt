package ru.sostav.data

import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.firestore.FirebaseFirestoreException

data class Profile(
    val uid: String,
    val email: String,
    val displayName: String,
    val groupId: String?,
)

data class GroupInfo(
    val id: String,
    val name: String,
    val inviteCode: String,
)

enum class MemberRole { ADMIN, MEMBER }

data class Member(
    val uid: String,
    val displayName: String,
    val email: String,
    val role: MemberRole,
)

data class Notice(
    val id: String,
    val title: String,
    val body: String,
    val concertId: String,
    val createdAt: Long,
    val read: Boolean,
)

data class RsvpMark(
    val uid: String,
    val status: ru.sostav.logic.Rsvp,
)

class AppException(message: String) : Exception(message)

fun Throwable.userMessage(): String {
    if (this is AppException) return message ?: "Не получилось"
    val code = when (this) {
        is FirebaseAuthException -> errorCode
        is FirebaseFirestoreException -> this.code.name
        else -> ""
    }
    val text = "${message.orEmpty()} $code"
    return when {
        text.contains("INVALID_LOGIN", true) ||
            text.contains("INVALID_CREDENTIAL", true) ||
            text.contains("WRONG_PASSWORD", true) ||
            text.contains("USER_NOT_FOUND", true) ->
            "Такой почты нет или пароль не тот. Если аккаунта ещё нет, сначала зарегистрируйтесь"
        text.contains("EMAIL_EXISTS", true) || text.contains("EMAIL_ALREADY_IN_USE", true) ->
            "Эта почта уже занята. Войдите или восстановите пароль"
        text.contains("INVALID_EMAIL", true) -> "Почта написана с ошибкой"
        text.contains("WEAK_PASSWORD", true) -> "Пароль слишком простой. Нужно хотя бы 8 символов"
        text.contains("NETWORK", true) || text.contains("UNAVAILABLE", true) ->
            "Нет сети. Проверьте интернет"
        text.contains("PERMISSION_DENIED", true) -> "Недостаточно прав для этого действия"
        text.contains("TOO_MANY_REQUESTS", true) -> "Слишком много попыток. Подождите немного"
        else -> "Не получилось. Попробуйте ещё раз"
    }
}
