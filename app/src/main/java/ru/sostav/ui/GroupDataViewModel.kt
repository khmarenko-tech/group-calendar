package ru.sostav.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import ru.sostav.data.Graph
import ru.sostav.data.GroupInfo
import ru.sostav.data.Member
import ru.sostav.data.MemberRole
import ru.sostav.data.Notice
import ru.sostav.data.userMessage
import ru.sostav.logic.Absence
import ru.sostav.logic.Availability
import ru.sostav.logic.Concert
import ru.sostav.logic.DayPart
import ru.sostav.logic.Rsvp
import ru.sostav.logic.exactSlot
import java.time.LocalDate

class GroupDataViewModel : ViewModel() {
    var group by mutableStateOf<GroupInfo?>(null)
        private set
    var members by mutableStateOf<List<Member>>(emptyList())
        private set
    var absences by mutableStateOf<List<Absence>>(emptyList())
        private set
    var concerts by mutableStateOf<List<Concert>>(emptyList())
        private set
    var notices by mutableStateOf<List<Notice>>(emptyList())
        private set
    var error by mutableStateOf<String?>(null)
    var uid by mutableStateOf("")
        private set
    var myName by mutableStateOf("")
    var groupId by mutableStateOf("")
        private set
    val me: Member? get() = members.find { it.uid == uid }
    val isAdmin: Boolean get() = me?.role == MemberRole.ADMIN
    val unread: Int get() = notices.count { !it.read }

    private var startedKey: String? = null
    private var job: Job? = null

    fun start(groupId: String, userId: String, name: String) {
        this.groupId = groupId
        uid = userId
        myName = name
        val key = "$groupId:$userId"
        if (startedKey == key && job?.isActive == true) return
        stop()
        startedKey = key
        job = viewModelScope.launch {
            launch {
                Graph.groups.observeGroup(groupId).catch { error = it.userMessage() }.collect { group = it }
            }
            launch {
                Graph.groups.observeMembers(groupId).catch { error = it.userMessage() }.collect { members = it }
            }
            launch {
                Graph.schedule.absences(groupId).catch { error = it.userMessage() }.collect { absences = it }
            }
            launch {
                Graph.schedule.concerts(groupId).catch { error = it.userMessage() }.collect { concerts = it }
            }
            launch {
                Graph.schedule.notices(userId).catch { error = it.userMessage() }.collect { notices = it }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        startedKey = null
    }

    fun saveQuick(date: LocalDate, part: DayPart, status: Availability) {
        viewModelScope.launch {
            runCatching {
                val existing = exactSlot(absences, uid, date, part)
                val draft = Absence(
                    id = existing?.id.orEmpty(),
                    uid = uid,
                    start = date,
                    end = date,
                    parts = setOf(part),
                    status = status,
                    note = existing?.note.orEmpty(),
                )
                val id = Graph.schedule.saveAbsence(groupId, draft)
                val next = absences.filterNot { it.id == existing?.id } + draft.copy(id = id)
                Graph.schedule.syncConflicts(
                    groupId, uid, myName, members.map { it.uid }, concerts, next, date, date,
                )
            }.onFailure { error = it.userMessage() }
        }
    }

    fun saveAbsence(previous: Absence?, draft: Absence) {
        viewModelScope.launch {
            runCatching {
                val id = Graph.schedule.saveAbsence(groupId, draft)
                val next = absences.filterNot { it.id == draft.id || it.id == previous?.id } + draft.copy(id = id)
                val start = kotlin.comparisons.minOf(draft.start, previous?.start ?: draft.start)
                val end = kotlin.comparisons.maxOf(draft.end, previous?.end ?: draft.end)
                Graph.schedule.syncConflicts(
                    groupId, uid, myName, members.map { it.uid }, concerts, next, start, end,
                )
            }.onFailure { error = it.userMessage() }
        }
    }

    fun deleteAbsence(absence: Absence) {
        viewModelScope.launch {
            runCatching {
                Graph.schedule.deleteAbsence(groupId, absence.id)
                val next = absences.filterNot { it.id == absence.id }
                Graph.schedule.syncConflicts(
                    groupId, uid, myName, members.map { it.uid }, concerts, next, absence.start, absence.end,
                )
            }.onFailure { error = it.userMessage() }
        }
    }

    suspend fun saveConcert(draft: Concert): String {
        val created = draft.id.isBlank()
        val id = Graph.schedule.saveConcert(groupId, draft)
        if (created) {
            Graph.schedule.notifyNewConcert(
                groupId,
                draft.copy(id = id),
                uid,
                members.map { it.uid },
            )
        }
        return id
    }

    fun deleteConcert(concertId: String) {
        viewModelScope.launch {
            runCatching { Graph.schedule.deleteConcert(groupId, concertId) }
                .onFailure { error = it.userMessage() }
        }
    }

    fun setRsvp(concertId: String, status: Rsvp) {
        viewModelScope.launch {
            runCatching { Graph.schedule.setRsvp(groupId, concertId, uid, status) }
                .onFailure { error = it.userMessage() }
        }
    }

    fun markRead(noticeId: String) {
        viewModelScope.launch {
            runCatching { Graph.schedule.markRead(uid, noticeId) }
        }
    }

    fun markAllRead() {
        viewModelScope.launch {
            runCatching { Graph.schedule.markAllRead(uid, notices) }
                .onFailure { error = it.userMessage() }
        }
    }
}
