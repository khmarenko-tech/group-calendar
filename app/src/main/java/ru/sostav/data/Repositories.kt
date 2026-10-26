package ru.sostav.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import ru.sostav.logic.Absence
import ru.sostav.logic.Availability
import ru.sostav.logic.Concert
import ru.sostav.logic.ConcertStatus
import ru.sostav.logic.DayPart
import ru.sostav.logic.Rsvp
import ru.sostav.logic.activeConcertsInRange
import ru.sostav.ui.dayMonth
import ru.sostav.ui.wireAvailability
import ru.sostav.ui.wireConcertStatus
import ru.sostav.ui.wirePart
import ru.sostav.ui.wireRsvp
import java.time.LocalDate
import kotlin.random.Random

sealed interface Session {
    data object Loading : Session
    data object SignedOut : Session
    data class Ready(val profile: Profile) : Session
    data class Failed(val message: String) : Session
}

object Graph {
    val auth: FirebaseAuth get() = FirebaseAuth.getInstance()
    val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()
    val accounts = AccountRepository()
    val groups = GroupRepository()
    val schedule = ScheduleRepository()
    var deviceToken: String? = null
}

private val codeAlphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

private fun newCode(): String = buildString(6) {
    repeat(6) { append(codeAlphabet[Random.nextInt(codeAlphabet.length)]) }
}

class AccountRepository {
    private val auth get() = Graph.auth
    private val db get() = Graph.db

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(): Flow<Session> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }.flatMapLatest { user ->
        if (user == null) {
            flowOf<Session>(Session.SignedOut)
        } else {
            callbackFlow<Session> {
                launch {
                    try {
                        ensureProfile(user)
                    } catch (error: Exception) {
                        trySend(Session.Failed(error.userMessage()))
                    }
                }
                val registration = db.collection("users").document(user.uid)
                    .addSnapshotListener { snap, error ->
                        if (error != null) {
                            trySend(Session.Failed(error.userMessage()))
                            return@addSnapshotListener
                        }
                        if (snap != null && snap.exists()) {
                            trySend(Session.Ready(snap.toProfile(user.uid)))
                        }
                    }
                awaitClose { registration.remove() }
            }
        }
    }

    suspend fun register(name: String, email: String, password: String) {
        validateCredentials(name, email, password, requireName = true)
        val result = auth.createUserWithEmailAndPassword(email.trim(), password).await()
        val user = result.user ?: throw AppException("Аккаунт не создался")
        user.updateProfile(
            UserProfileChangeRequest.Builder().setDisplayName(name.trim()).build(),
        ).await()
        writeProfile(user.uid, email.trim(), name.trim())
    }

    suspend fun signIn(email: String, password: String) {
        validateCredentials(name = "Участник", email = email, password = password, requireName = false)
        val result = auth.signInWithEmailAndPassword(email.trim(), password).await()
        val user = result.user ?: return
        ensureProfile(user)
    }

    suspend fun resetPassword(email: String) {
        if (!email.trim().contains("@")) throw AppException("Напишите почту, на неё придёт письмо")
        auth.sendPasswordResetEmail(email.trim()).await()
    }

    suspend fun ensureProfile(user: FirebaseUser) {
        val ref = db.collection("users").document(user.uid)
        val snap = ref.get().await()
        if (snap.exists()) return
        val name = user.displayName?.takeIf { it.isNotBlank() }
            ?: user.email?.substringBefore("@")
            ?: "Участник"
        writeProfile(user.uid, user.email.orEmpty(), name)
    }

    suspend fun signOut() {
        val uid = auth.currentUser?.uid
        val token = Graph.deviceToken
        if (uid != null && !token.isNullOrBlank()) {
            runCatching {
                db.collection("users").document(uid).collection("tokens").document(token).delete().await()
            }
        }
        auth.signOut()
    }

    suspend fun saveToken(uid: String, token: String) {
        Graph.deviceToken = token
        db.collection("users").document(uid).collection("tokens").document(token)
            .set(mapOf("updatedAt" to System.currentTimeMillis())).await()
    }

    private suspend fun writeProfile(uid: String, email: String, name: String) {
        auth.currentUser?.getIdToken(true)?.await()
        db.collection("users").document(uid).set(
            mapOf(
                "email" to email,
                "displayName" to name,
                "groupId" to null,
            ),
        ).await()
    }
}

class GroupRepository {
    private val auth get() = Graph.auth
    private val db get() = Graph.db

    fun observeGroup(groupId: String): Flow<GroupInfo?> = callbackFlow {
        val registration = db.collection("groups").document(groupId)
            .addSnapshotListener { snap, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                trySend(
                    snap?.takeIf { it.exists() }?.let {
                        GroupInfo(
                            id = it.id,
                            name = it.getString("name").orEmpty(),
                            inviteCode = it.getString("inviteCode").orEmpty(),
                        )
                    },
                )
            }
        awaitClose { registration.remove() }
    }

    fun observeMembers(groupId: String): Flow<List<Member>> = callbackFlow {
        val registration = db.collection("groups").document(groupId).collection("members")
            .addSnapshotListener { snap, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                trySend(snap?.documents?.mapNotNull { it.toMember() }.orEmpty())
            }
        awaitClose { registration.remove() }
    }

    suspend fun createGroup(profile: Profile, name: String): String {
        val title = name.trim()
        if (title.isEmpty() || title.length > 59) throw AppException("Название группы — от 1 до 59 символов")
        val uid = profile.uid
        val groupRef = db.collection("groups").document()
        val code = newCode()
        val memberRef = groupRef.collection("members").document(uid)
        val inviteRef = db.collection("inviteCodes").document(code)
        var groupCreated = false
        var memberCreated = false
        var inviteCreated = false
        try {
            groupRef.set(
                mapOf(
                    "name" to title,
                    "inviteCode" to code,
                    "createdBy" to uid,
                    "openAdminSlot" to true,
                    "createdAt" to System.currentTimeMillis(),
                ),
            ).await()
            groupCreated = true
            memberRef.set(memberData(profile, MemberRole.ADMIN, usedCode = null)).await()
            memberCreated = true
            inviteRef.set(mapOf("groupId" to groupRef.id, "groupName" to title)).await()
            inviteCreated = true
            db.collection("users").document(uid).update("groupId", groupRef.id).await()
            runCatching { groupRef.update("openAdminSlot", false).await() }
        } catch (error: Exception) {
            if (inviteCreated) runCatching { inviteRef.delete().await() }
            if (memberCreated) runCatching { memberRef.delete().await() }
            if (groupCreated) runCatching { groupRef.delete().await() }
            throw error
        }
        return groupRef.id
    }

    suspend fun previewInvite(rawCode: String): String {
        val code = normalizeCode(rawCode)
        val snap = db.collection("inviteCodes").document(code).get().await()
        if (!snap.exists()) throw AppException("Такого кода нет")
        return snap.getString("groupName")?.ifBlank { null } ?: "Группа"
    }

    suspend fun join(profile: Profile, rawCode: String) {
        val code = normalizeCode(rawCode)
        val snap = db.collection("inviteCodes").document(code).get().await()
        if (!snap.exists()) throw AppException("Такого кода нет")
        val groupId = snap.getString("groupId") ?: throw AppException("Такого кода нет")
        val memberRef = db.collection("groups").document(groupId).collection("members").document(profile.uid)
        memberRef.set(memberData(profile, MemberRole.MEMBER, code)).await()
        try {
            db.collection("users").document(profile.uid).update("groupId", groupId).await()
        } catch (error: Exception) {
            runCatching { memberRef.delete().await() }
            throw error
        }
    }

    suspend fun heal(uid: String, groupId: String) {
        val member = db.collection("groups").document(groupId).collection("members").document(uid).get().await()
        if (!member.exists()) {
            db.collection("users").document(uid).update("groupId", FieldValue.delete()).await()
        }
    }

    suspend fun rename(groupId: String, name: String, inviteCode: String) {
        val title = name.trim()
        if (title.isEmpty() || title.length > 59) throw AppException("Название группы — от 1 до 59 символов")
        db.collection("groups").document(groupId).update("name", title).await()
        if (inviteCode.isNotBlank()) {
            db.collection("inviteCodes").document(inviteCode).update("groupName", title).await()
        }
    }

    suspend fun rotateCode(groupId: String, groupName: String, previousCode: String) {
        val code = newCode()
        val created = db.collection("inviteCodes").document(code)
        try {
            created.set(mapOf("groupId" to groupId, "groupName" to groupName)).await()
            db.collection("groups").document(groupId).update("inviteCode", code).await()
        } catch (error: Exception) {
            runCatching { created.delete().await() }
            throw error
        }
        if (previousCode.isNotBlank() && previousCode != code) {
            runCatching { db.collection("inviteCodes").document(previousCode).delete().await() }
        }
    }

    suspend fun setRole(groupId: String, members: List<Member>, targetUid: String, role: MemberRole) {
        val admins = members.count { it.role == MemberRole.ADMIN }
        val target = members.find { it.uid == targetUid } ?: throw AppException("Участник не найден")
        if (target.role == MemberRole.ADMIN && role == MemberRole.MEMBER && admins <= 1) {
            throw AppException("В группе должен остаться хотя бы один админ")
        }
        db.collection("groups").document(groupId).collection("members").document(targetUid)
            .update("role", if (role == MemberRole.ADMIN) "admin" else "member").await()
    }

    suspend fun removeMember(groupId: String, members: List<Member>, actorUid: String, targetUid: String) {
        if (actorUid == targetUid) throw AppException("Себя уберите кнопкой «Выйти из группы»")
        val admins = members.count { it.role == MemberRole.ADMIN }
        val target = members.find { it.uid == targetUid } ?: throw AppException("Участник не найден")
        if (target.role == MemberRole.ADMIN && admins <= 1) {
            throw AppException("Нельзя удалить единственного админа")
        }
        deleteAbsences(groupId, targetUid)
        db.collection("groups").document(groupId).collection("members").document(targetUid).delete().await()
        db.collection("users").document(targetUid).update("groupId", FieldValue.delete()).await()
    }

    suspend fun leave(groupId: String, members: List<Member>, uid: String) {
        val me = members.find { it.uid == uid } ?: throw AppException("Вас нет в группе")
        val admins = members.count { it.role == MemberRole.ADMIN }
        if (me.role == MemberRole.ADMIN && admins <= 1) {
            throw AppException("Вы единственный админ. Сначала назначьте другого")
        }
        deleteAbsences(groupId, uid)
        db.collection("groups").document(groupId).collection("members").document(uid).delete().await()
        db.collection("users").document(uid).update("groupId", FieldValue.delete()).await()
    }

    suspend fun renameMe(groupId: String, uid: String, name: String) {
        val title = name.trim()
        if (title.isEmpty() || title.length > 59) throw AppException("Имя — от 1 до 59 символов")
        db.collection("users").document(uid).update("displayName", title).await()
        db.collection("groups").document(groupId).collection("members").document(uid)
            .update("displayName", title).await()
    }

    private suspend fun deleteAbsences(groupId: String, uid: String) {
        val snap = db.collection("groups").document(groupId).collection("absences")
            .whereEqualTo("uid", uid)
            .get()
            .await()
        snap.documents.chunked(400).forEach { chunk ->
            val batch = db.batch()
            chunk.forEach { batch.delete(it.reference) }
            batch.commit().await()
        }
    }

    private fun memberData(profile: Profile, role: MemberRole, usedCode: String?): Map<String, Any> {
        val data = mutableMapOf<String, Any>(
            "displayName" to profile.displayName,
            "email" to profile.email,
            "role" to if (role == MemberRole.ADMIN) "admin" else "member",
            "joinedAt" to System.currentTimeMillis(),
        )
        if (usedCode != null) data["usedCode"] = usedCode
        return data
    }

    private fun normalizeCode(raw: String): String {
        val code = raw.trim().uppercase()
        if (code.length != 6) throw AppException("Код состоит из 6 символов")
        return code
    }
}

class ScheduleRepository {
    private val db get() = Graph.db

    fun absences(groupId: String): Flow<List<Absence>> = callbackFlow {
        val registration = db.collection("groups").document(groupId).collection("absences")
            .addSnapshotListener { snap, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                trySend(snap?.documents?.mapNotNull { it.toAbsence() }.orEmpty())
            }
        awaitClose { registration.remove() }
    }

    fun concerts(groupId: String): Flow<List<Concert>> = callbackFlow {
        val registration = db.collection("groups").document(groupId).collection("concerts")
            .orderBy("date", Query.Direction.ASCENDING)
            .addSnapshotListener { snap, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                trySend(snap?.documents?.mapNotNull { it.toConcert() }.orEmpty())
            }
        awaitClose { registration.remove() }
    }

    fun rsvps(groupId: String, concertId: String): Flow<List<RsvpMark>> = callbackFlow {
        val registration = db.collection("groups").document(groupId)
            .collection("concerts").document(concertId).collection("rsvps")
            .addSnapshotListener { snap, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                trySend(
                    snap?.documents?.mapNotNull { doc ->
                        wireRsvp(doc.getString("status"))?.let { RsvpMark(doc.id, it) }
                    }.orEmpty(),
                )
            }
        awaitClose { registration.remove() }
    }

    fun notices(uid: String): Flow<List<Notice>> = callbackFlow {
        val registration = db.collection("users").document(uid).collection("inbox")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(100)
            .addSnapshotListener { snap, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                trySend(snap?.documents?.map { it.toNotice() }.orEmpty())
            }
        awaitClose { registration.remove() }
    }

    suspend fun saveAbsence(groupId: String, absence: Absence): String {
        if (absence.end.isBefore(absence.start)) throw AppException("Конец периода раньше начала")
        if (absence.parts.isEmpty()) throw AppException("Выберите утро, день или вечер")
        if (absence.note.length > 299) throw AppException("Заметка длиннее 299 символов")
        val col = db.collection("groups").document(groupId).collection("absences")
        val ref = if (absence.id.isBlank()) col.document() else col.document(absence.id)
        ref.set(
            mapOf(
                "uid" to absence.uid,
                "startDate" to absence.start.toString(),
                "endDate" to absence.end.toString(),
                "parts" to absence.parts.map { it.stored() }.sorted(),
                "status" to absence.status.stored(),
                "note" to absence.note.trim(),
                "updatedAt" to System.currentTimeMillis(),
            ),
        ).await()
        return ref.id
    }

    suspend fun deleteAbsence(groupId: String, absenceId: String) {
        db.collection("groups").document(groupId).collection("absences").document(absenceId).delete().await()
    }

    suspend fun saveConcert(groupId: String, concert: Concert): String {
        val title = concert.title.trim()
        if (title.isEmpty() || title.length > 119) throw AppException("Название концерта — от 1 до 119 символов")
        if (concert.note.length > 499) throw AppException("Заметка длиннее 499 символов")
        val col = db.collection("groups").document(groupId).collection("concerts")
        val ref = if (concert.id.isBlank()) col.document() else col.document(concert.id)
        val data = mutableMapOf<String, Any>(
            "title" to title,
            "date" to concert.date.toString(),
            "part" to concert.part.stored(),
            "city" to concert.city.trim(),
            "venue" to concert.venue.trim(),
            "note" to concert.note.trim(),
            "status" to concert.status.stored(),
            "createdBy" to concert.createdBy,
        )
        if (concert.id.isBlank()) data["createdAt"] = System.currentTimeMillis()
        ref.set(data, SetOptions.merge()).await()
        return ref.id
    }

    suspend fun deleteConcert(groupId: String, concertId: String) {
        db.collection("groups").document(groupId).collection("concerts").document(concertId).delete().await()
    }

    suspend fun setRsvp(groupId: String, concertId: String, uid: String, status: Rsvp) {
        db.collection("groups").document(groupId).collection("concerts").document(concertId)
            .collection("rsvps").document(uid)
            .set(mapOf("status" to status.stored(), "updatedAt" to System.currentTimeMillis())).await()
    }

    suspend fun notifyNewConcert(groupId: String, concert: Concert, fromUid: String, memberUids: List<String>) {
        if (concert.status == ConcertStatus.CANCELLED) return
        val place = listOf(concert.city, concert.venue).filter { it.isNotBlank() }.joinToString(", ")
        val body = buildString {
            append(concert.title)
            append(", ")
            append(concert.date.format(dayMonth))
            append(", ")
            append(concert.part.spoken())
            if (place.isNotBlank()) {
                append(", ")
                append(place)
            }
        }
        val writes = memberUids.filter { it != fromUid }.map { uid ->
            NoticeWrite(
                uid = uid,
                id = "concert_${concert.id}",
                payload = noticeMap(
                    toUid = uid,
                    fromUid = fromUid,
                    groupId = groupId,
                    title = "Новый концерт",
                    body = body,
                    concertId = concert.id,
                    type = "concert",
                ),
            )
        }
        applyNotices(writes)
    }

    suspend fun syncConflicts(
        groupId: String,
        fromUid: String,
        fromName: String,
        memberUids: List<String>,
        concerts: List<Concert>,
        myAbsences: List<Absence>,
        start: LocalDate,
        end: LocalDate,
    ) {
        val relevant = activeConcertsInRange(concerts, start, end)
        val others = memberUids.filter { it != fromUid }
        val writes = mutableListOf<NoticeWrite>()
        relevant.forEach { concert ->
            val blocks = myAbsences.any { absence ->
                absence.uid == fromUid &&
                    absence.status == Availability.CANNOT &&
                    absence.covers(concert.date, concert.part)
            }
            others.forEach { uid ->
                val id = "conflict_${concert.id}_$fromUid"
                writes += if (blocks) {
                    NoticeWrite(
                        uid = uid,
                        id = id,
                        payload = noticeMap(
                            toUid = uid,
                            fromUid = fromUid,
                            groupId = groupId,
                            title = "Кто-то не может",
                            body = "$fromName не может ${concert.part.spoken()} ${concert.date.format(dayMonth)}. Концерт «${concert.title}».",
                            concertId = concert.id,
                            type = "conflict",
                        ),
                    )
                } else {
                    NoticeWrite(uid, id, null)
                }
            }
        }
        applyNotices(writes)
    }

    suspend fun markRead(uid: String, noticeId: String) {
        db.collection("users").document(uid).collection("inbox").document(noticeId)
            .update("read", true).await()
    }

    suspend fun markAllRead(uid: String, notices: List<Notice>) {
        notices.filterNot { it.read }.map { it.id }.chunked(400).forEach { chunk ->
            val batch = db.batch()
            chunk.forEach { id ->
                batch.update(db.collection("users").document(uid).collection("inbox").document(id), "read", true)
            }
            batch.commit().await()
        }
    }

    private suspend fun applyNotices(writes: List<NoticeWrite>) {
        writes.chunked(400).forEach { chunk ->
            val batch = db.batch()
            chunk.forEach { write ->
                val ref = db.collection("users").document(write.uid).collection("inbox").document(write.id)
                if (write.payload == null) batch.delete(ref) else batch.set(ref, write.payload)
            }
            batch.commit().await()
        }
    }
}

private data class NoticeWrite(val uid: String, val id: String, val payload: Map<String, Any>?)

private fun noticeMap(
    toUid: String,
    fromUid: String,
    groupId: String,
    title: String,
    body: String,
    concertId: String,
    type: String,
): Map<String, Any> = mapOf(
    "toUid" to toUid,
    "fromUid" to fromUid,
    "groupId" to groupId,
    "title" to title,
    "body" to body,
    "concertId" to concertId,
    "type" to type,
    "read" to false,
    "createdAt" to System.currentTimeMillis(),
)

private fun DocumentSnapshot.toProfile(uid: String) = Profile(
    uid = uid,
    email = getString("email").orEmpty(),
    displayName = getString("displayName")?.ifBlank { null } ?: "Участник",
    groupId = getString("groupId")?.takeIf { it.isNotBlank() },
)

private fun DocumentSnapshot.toMember(): Member? {
    val role = when (getString("role")) {
        "admin" -> MemberRole.ADMIN
        "member" -> MemberRole.MEMBER
        else -> return null
    }
    return Member(
        uid = id,
        displayName = getString("displayName")?.ifBlank { null } ?: "Участник",
        email = getString("email").orEmpty(),
        role = role,
    )
}

private fun DocumentSnapshot.toAbsence(): Absence? {
    val start = getString("startDate")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
    val end = getString("endDate")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
    val status = wireAvailability(getString("status")) ?: return null
    val parts = (get("parts") as? List<*>)?.mapNotNull { wirePart(it?.toString()) }?.toSet().orEmpty()
    if (parts.isEmpty()) return null
    return Absence(
        id = id,
        uid = getString("uid") ?: return null,
        start = start,
        end = end,
        parts = parts,
        status = status,
        note = getString("note").orEmpty(),
    )
}

private fun DocumentSnapshot.toConcert(): Concert? {
    val date = getString("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
    val part = wirePart(getString("part")) ?: return null
    val status = wireConcertStatus(getString("status")) ?: return null
    return Concert(
        id = id,
        title = getString("title").orEmpty(),
        date = date,
        part = part,
        city = getString("city").orEmpty(),
        venue = getString("venue").orEmpty(),
        note = getString("note").orEmpty(),
        status = status,
        createdBy = getString("createdBy").orEmpty(),
    )
}

private fun DocumentSnapshot.toNotice() = Notice(
    id = id,
    title = getString("title").orEmpty(),
    body = getString("body").orEmpty(),
    concertId = getString("concertId").orEmpty(),
    createdAt = getLong("createdAt") ?: 0L,
    read = getBoolean("read") ?: false,
)

private fun DayPart.stored(): String = when (this) {
    DayPart.MORNING -> "morning"
    DayPart.AFTERNOON -> "afternoon"
    DayPart.EVENING -> "evening"
}

private fun DayPart.spoken(): String = when (this) {
    DayPart.MORNING -> "утром"
    DayPart.AFTERNOON -> "днём"
    DayPart.EVENING -> "вечером"
}

private fun Availability.stored(): String = when (this) {
    Availability.CAN -> "can"
    Availability.CANNOT -> "cannot"
    Availability.MAYBE -> "maybe"
}

private fun ConcertStatus.stored(): String = when (this) {
    ConcertStatus.IDEA -> "idea"
    ConcertStatus.CONFIRMED -> "confirmed"
    ConcertStatus.CANCELLED -> "cancelled"
}

private fun Rsvp.stored(): String = when (this) {
    Rsvp.GOING -> "going"
    Rsvp.NOT_GOING -> "not_going"
    Rsvp.MAYBE -> "maybe"
}

private fun validateCredentials(name: String, email: String, password: String, requireName: Boolean) {
    if (requireName && (name.trim().isEmpty() || name.trim().length > 59)) {
        throw AppException("Напишите имя, как вас видит группа")
    }
    if (!email.trim().contains("@") || email.trim().length < 5) throw AppException("Напишите почту")
    if (password.length < 8) throw AppException("Пароль не короче 8 символов")
}
