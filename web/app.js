import { alreadyImported, defaultImport, parseIcs, toConcertDraft, tooOld } from "./ics.js";
import { initializeApp } from "https://www.gstatic.com/firebasejs/11.0.1/firebase-app.js";
import {
  getAuth,
  onAuthStateChanged,
  signInWithEmailAndPassword,
  createUserWithEmailAndPassword,
  sendPasswordResetEmail,
  signOut,
  updateProfile,
} from "https://www.gstatic.com/firebasejs/11.0.1/firebase-auth.js";
import {
  getFirestore,
  doc,
  getDoc,
  setDoc,
  updateDoc,
  deleteDoc,
  collection,
  onSnapshot,
  writeBatch,
  deleteField,
} from "https://www.gstatic.com/firebasejs/11.0.1/firebase-firestore.js";

const firebaseConfig = {
  apiKey: "AIzaSyDzH7frZwRUWPPof9-AiIely89rd9gFTSM",
  authDomain: "group-calendar-57860.firebaseapp.com",
  projectId: "group-calendar-57860",
  storageBucket: "group-calendar-57860.firebasestorage.app",
  messagingSenderId: "907587101422",
};

const app = initializeApp(firebaseConfig);
const auth = getAuth(app);
const db = getFirestore(app);

const MONTHS = ["январь", "февраль", "март", "апрель", "май", "июнь", "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь"];
const MONTHS_GEN = ["января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря"];
const WEEKDAYS = ["Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"];
const REPEAT = { none: "Разово", daily: "Каждый день", weekly: "Каждую неделю", monthly: "Каждый месяц", yearly: "Каждый год" };
const STATUS = { idea: "Идея", confirmed: "Будет", cancelled: "Нет" };
const MARK = { can: "Могу", cannot: "Нет", maybe: "Может" };
const RSVP = { going: "Еду", not_going: "Не еду", maybe: "Под вопросом" };

const state = {
  screen: "boot",
  authMode: "in",
  error: "",
  busy: false,
  profile: null,
  group: null,
  members: [],
  concerts: [],
  absences: [],
  month: startOfMonth(new Date()),
  selected: iso(new Date()),
  modal: null,
  rsvps: {},
};

let userUnsub = null;
let groupUnsubs = [];
let currentGroupId = null;
const root = document.getElementById("app");

function pad(n) { return String(n).padStart(2, "0"); }
function iso(d) { return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`; }
function parseIso(s) { const [y, m, d] = s.split("-").map(Number); return new Date(y, m - 1, d); }
function startOfMonth(d) { return new Date(d.getFullYear(), d.getMonth(), 1); }
function addDays(d, n) { const x = new Date(d); x.setDate(x.getDate() + n); return x; }
function addMonths(d, n) { return new Date(d.getFullYear(), d.getMonth() + n, 1); }
function daysBetween(a, b) { return Math.round((parseIso(b) - parseIso(a)) / 86400000); }
function cmp(a, b) { return a < b ? -1 : a > b ? 1 : 0; }
function todayIso() { return iso(new Date()); }
function ruDay(s) { const d = parseIso(s); return `${d.getDate()} ${MONTHS_GEN[d.getMonth()]}`; }
function ruFull(s) { const d = parseIso(s); return `${d.getDate()} ${MONTHS_GEN[d.getMonth()]} ${d.getFullYear()}`; }
function ruRange(a, b) { return a === b ? ruFull(a) : `${ruDay(a)} — ${ruFull(b)}`; }
function lastDay(c) { return c.endDate && c.endDate >= c.date ? c.endDate : c.date; }
function spanDays(c) { return Math.max(0, daysBetween(c.date, lastDay(c))); }
function esc(s) { return String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c])); }
function memberName(uid) { return state.members.find((m) => m.uid === uid)?.displayName || "Участник"; }

function monthBoard(monthDate) {
  const first = startOfMonth(monthDate);
  const lead = (first.getDay() + 6) % 7;
  const cells = [];
  for (let i = lead; i > 0; i -= 1) {
    cells.push({ day: iso(addDays(first, -i)), outside: true });
  }
  const count = daysInMonth(first.getFullYear(), first.getMonth());
  for (let d = 1; d <= count; d += 1) {
    cells.push({ day: iso(new Date(first.getFullYear(), first.getMonth(), d)), outside: false });
  }
  while (cells.length < 42) {
    cells.push({ day: iso(addDays(parseIso(cells[cells.length - 1].day), 1)), outside: true });
  }
  return cells;
}

function timePrefix(note) {
  return (note || "").match(/^(\d{1,2}:\d{2})/)?.[1] || "";
}

function eventChipText(concert) {
  const time = timePrefix(concert.note);
  return time ? `${time} ${concert.title}` : concert.title;
}

function markChipText(absence) {
  if (absence.note) return absence.note;
  const short = memberName(absence.uid).split(" ")[0] || "Участник";
  if (absence.status === "cannot") return `${short} минус`;
  if (absence.status === "maybe") return `${short} ?`;
  return short;
}

function chipsOn(day) {
  const events = state.concerts.filter((c) => concertCovers(c, day)).map((c) => ({
    kind: "event",
    id: c.id,
    text: eventChipText(c),
    tone: c.status || "confirmed",
  }));
  const seen = new Set();
  const marks = [];
  state.absences.filter((a) => absenceCovers(a, day)).forEach((absence) => {
    const text = markChipText(absence);
    const key = text.toLowerCase();
    if (seen.has(key)) return;
    seen.add(key);
    marks.push({
      kind: "mark",
      id: absence.id,
      uid: absence.uid,
      text,
      tone: absence.status,
    });
  });
  return events.concat(marks);
}

function daysInMonth(year, month) { return new Date(year, month + 1, 0).getDate(); }
function monthGrid(monthDate) {
  const first = startOfMonth(monthDate);
  const lead = (first.getDay() + 6) % 7;
  const count = daysInMonth(first.getFullYear(), first.getMonth());
  const cells = Array(lead).fill(null);
  for (let d = 1; d <= count; d++) cells.push(iso(new Date(first.getFullYear(), first.getMonth(), d)));
  while (cells.length % 7) cells.push(null);
  return cells;
}

function monthlyStart(c, start) {
  const a = parseIso(c.date);
  const b = parseIso(start);
  if (a.getDate() !== b.getDate()) return false;
  const months = (b.getFullYear() - a.getFullYear()) * 12 + (b.getMonth() - a.getMonth());
  if (months < 0) return false;
  const expected = new Date(a.getFullYear(), a.getMonth() + months, a.getDate());
  return iso(expected) === start;
}

function yearlyStart(c, start) {
  const a = parseIso(c.date);
  const b = parseIso(start);
  if (a.getMonth() !== b.getMonth() || a.getDate() !== b.getDate()) return false;
  if (b.getFullYear() < a.getFullYear()) return false;
  const expected = new Date(b.getFullYear(), a.getMonth(), a.getDate());
  return iso(expected) === start;
}

function isOccurrenceStart(c, start) {
  if (start < c.date) return false;
  if (c.repeatUntil && start > c.repeatUntil) return false;
  if (c.repeat === "daily") return true;
  if (c.repeat === "weekly") return daysBetween(c.date, start) % 7 === 0;
  if (c.repeat === "monthly") return monthlyStart(c, start);
  if (c.repeat === "yearly") return yearlyStart(c, start);
  return start === c.date;
}

function occurrenceStartOn(c, day) {
  if (day < c.date) return null;
  let candidate = day;
  let step = 0;
  const span = spanDays(c);
  while (candidate >= c.date && step <= span) {
    if (isOccurrenceStart(c, candidate) && day <= iso(addDays(parseIso(candidate), span))) return candidate;
    candidate = iso(addDays(parseIso(candidate), -1));
    step += 1;
  }
  return null;
}

function concertCovers(c, day) { return occurrenceStartOn(c, day) != null; }
function absenceCovers(a, day) { return a.end >= a.start && day >= a.start && day <= a.end; }

function winningAbsence(uid, day) {
  const matches = state.absences.filter((a) => a.uid === uid && absenceCovers(a, day));
  return matches.find((a) => a.status === "cannot")
    || matches.find((a) => a.status === "maybe")
    || matches.find((a) => a.status === "can")
    || null;
}

function exactDay(uid, day) {
  return state.absences.find((a) => a.uid === uid && a.start === day && a.end === day) || null;
}

function concertLabel(c) {
  const first = ruRange(c.date, lastDay(c));
  if (!c.repeat || c.repeat === "none") return first;
  const until = c.repeatUntil ? ` до ${ruFull(c.repeatUntil)}` : "";
  return `${first} · ${REPEAT[c.repeat] || ""}${until}`;
}

function mapAuthError(err) {
  const code = err?.code || "";
  if (code.includes("invalid-credential") || code.includes("wrong-password") || code.includes("user-not-found")) return "Неверная почта или пароль";
  if (code.includes("email-already-in-use")) return "Эта почта уже занята";
  if (code.includes("too-many-requests")) return "Слишком много попыток, подождите";
  if (code.includes("network")) return "Нет сети";
  return err?.message || "Не получилось";
}

function clearGroup() {
  groupUnsubs.forEach((u) => u());
  groupUnsubs = [];
  currentGroupId = null;
}

function listenGroup(groupId) {
  if (currentGroupId === groupId) return;
  clearGroup();
  currentGroupId = groupId;
  groupUnsubs.push(onSnapshot(doc(db, "groups", groupId), (snap) => {
    state.group = snap.exists() ? { id: snap.id, ...snap.data() } : null;
    render();
  }));
  groupUnsubs.push(onSnapshot(collection(db, "groups", groupId, "members"), (snap) => {
    state.members = snap.docs.map((d) => ({ uid: d.id, ...d.data() }));
    render();
  }));
  groupUnsubs.push(onSnapshot(collection(db, "groups", groupId, "concerts"), (snap) => {
    state.concerts = snap.docs.map((d) => {
      const x = d.data();
      return {
        id: d.id,
        title: x.title || "",
        date: x.date,
        endDate: x.endDate || x.date,
        city: x.city || "",
        venue: x.venue || "",
        note: x.note || "",
        status: x.status || "confirmed",
        createdBy: x.createdBy || "",
        repeat: x.repeat || "none",
        repeatUntil: x.repeatUntil || null,
        importUid: x.importUid || "",
      };
    });
    render();
  }));
  groupUnsubs.push(onSnapshot(collection(db, "groups", groupId, "absences"), (snap) => {
    state.absences = snap.docs.map((d) => {
      const x = d.data();
      return {
        id: d.id,
        uid: x.uid,
        start: x.startDate,
        end: x.endDate || x.startDate,
        status: x.status,
        note: x.note || "",
      };
    });
    render();
  }));
}

onAuthStateChanged(auth, async (user) => {
  if (userUnsub) { userUnsub(); userUnsub = null; }
  clearGroup();
  state.error = "";
  if (!user) {
    state.screen = "auth";
    state.profile = null;
    state.group = null;
    state.members = [];
    state.concerts = [];
    state.absences = [];
    render();
    return;
  }
  try {
    const ref = doc(db, "users", user.uid);
    const snap = await getDoc(ref);
    if (!snap.exists()) {
      await setDoc(ref, {
        email: user.email || "",
        displayName: user.displayName || user.email?.split("@")[0] || "Участник",
        groupId: null,
      });
    }
    userUnsub = onSnapshot(ref, (docSnap) => {
      const data = docSnap.data() || {};
      state.profile = {
        uid: user.uid,
        email: data.email || user.email || "",
        displayName: data.displayName || "Участник",
        groupId: data.groupId || null,
      };
      if (state.profile.groupId) {
        state.screen = "app";
        listenGroup(state.profile.groupId);
      } else {
        clearGroup();
        state.screen = "gate";
        state.group = null;
      }
      render();
    });
  } catch (err) {
    state.screen = "auth";
    state.error = mapAuthError(err);
    render();
  }
});

async function applyNotices(writes) {
  for (let i = 0; i < writes.length; i += 400) {
    const batch = writeBatch(db);
    writes.slice(i, i + 400).forEach((write) => {
      const ref = doc(db, "users", write.uid, "inbox", write.id);
      if (write.payload) batch.set(ref, write.payload);
      else batch.delete(ref);
    });
    await batch.commit();
  }
}

function noticeMap({ toUid, fromUid, groupId, title, body, concertId, type }) {
  return {
    toUid, fromUid, groupId, title, body,
    concertId: concertId || "",
    type,
    read: false,
    createdAt: Date.now(),
  };
}

async function notifyImported(count) {
  if (count <= 0) return;
  const others = state.members.map((m) => m.uid).filter((uid) => uid !== state.profile.uid);
  const body = `${state.profile.displayName} добавил события из Яндекс.Календаря: ${count}`;
  const id = `import_${Date.now()}`;
  await applyNotices(others.map((uid) => ({
    uid,
    id,
    payload: noticeMap({
      toUid: uid,
      fromUid: state.profile.uid,
      groupId: state.profile.groupId,
      title: "События",
      body,
      concertId: "",
      type: "import",
    }),
  })));
}

function applyImportText(text) {
  const modal = state.modal?.type === "import" ? state.modal : { type: "import", url: "", rows: [] };
  try {
    const today = todayIso();
    const events = parseIcs(text).filter((event) => !tooOld(event, today)).slice(0, 120);
    if (!events.length) throw new Error("В календаре нет ближайших событий");
    modal.rows = events.map((event) => {
      const duplicate = alreadyImported(event, state.concerts);
      return { event, duplicate, selected: !duplicate && defaultImport(event, today) };
    });
    const fresh = modal.rows.filter((row) => row.selected).length;
    const copies = modal.rows.filter((row) => row.duplicate).length;
    modal.info = `Нашли ${modal.rows.length}${fresh ? ` · к добавлению ${fresh}` : ""}${copies ? ` · уже есть ${copies}` : ""}`;
    modal.error = "";
  } catch (err) {
    modal.rows = [];
    modal.info = "";
    modal.error = err.message || "Не удалось прочитать календарь. Нужен файл iCal из Яндекса.";
  }
  state.modal = modal;
}

function normalizeYandexUrl(raw) {
  const trimmed = raw.trim().replace(/[\n\r]/g, "");
  if (trimmed.toLowerCase().startsWith("webcal://")) return `https://${trimmed.slice(9)}`;
  if (trimmed.toLowerCase().startsWith("http://")) return `https://${trimmed.slice(7)}`;
  return trimmed;
}

function allowedYandex(url) {
  try {
    const host = new URL(url).hostname.toLowerCase();
    if (new URL(url).protocol !== "https:") return false;
    return host === "yandex.ru" || host === "yandex.com" || host === "ya.ru" ||
      host.endsWith(".yandex.ru") || host.endsWith(".yandex.com") || host.endsWith(".ya.ru");
  } catch {
    return false;
  }
}

async function fetchYandexIcs(raw) {
  const url = normalizeYandexUrl(raw);
  if (!allowedYandex(url)) throw new Error("Нужна ссылка экспорта iCal из Яндекс.Календаря");
  try {
    const response = await fetch(url, { headers: { Accept: "text/calendar, text/plain, */*" } });
    if (!response.ok) throw new Error(`Яндекс ответил кодом ${response.status}`);
    const text = await response.text();
    if (!text.includes("BEGIN:VCALENDAR")) throw new Error("Это не файл календаря iCal");
    return text;
  } catch (err) {
    if (String(err.message || "").includes("Яндекс") || String(err.message || "").includes("ссылка") || String(err.message || "").includes("iCal")) throw err;
    throw new Error("Браузер не открывает ссылку Яндекса. Скачайте файл .ics в настройках календаря и загрузите его сюда.");
  }
}

async function notifyNewConcert(concert) {
  const others = state.members.map((m) => m.uid).filter((uid) => uid !== state.profile.uid);
  const place = [concert.city, concert.venue].filter(Boolean).join(", ");
  const verb = concert.status === "cancelled" ? "отменил" : "добавил";
  const body = `${state.profile.displayName} ${verb} «${concert.title}» · ${concertLabel(concert)}${place ? ` · ${place}` : ""}`;
  await applyNotices(others.map((uid) => ({
    uid,
    id: `concert_${concert.id}`,
    payload: noticeMap({
      toUid: uid,
      fromUid: state.profile.uid,
      groupId: state.profile.groupId,
      title: "Событие",
      body,
      concertId: concert.id,
      type: "concert",
    }),
  })));
}

async function notifyMark(absence, noticeId) {
  const others = state.members.map((m) => m.uid).filter((uid) => uid !== state.profile.uid);
  if (!others.length) return;
  if (!absence) {
    await applyNotices(others.map((uid) => ({ uid, id: noticeId, payload: null })));
    return;
  }
  const note = (absence.note || "").trim();
  const body = `${state.profile.displayName}: ${MARK[absence.status]} · ${ruRange(absence.start, absence.end)}${note ? ` · ${note}` : ""}`;
  await applyNotices(others.map((uid) => ({
    uid,
    id: noticeId,
    payload: noticeMap({
      toUid: uid,
      fromUid: state.profile.uid,
      groupId: state.profile.groupId,
      title: "Календарь",
      body,
      concertId: "",
      type: "mark",
    }),
  })));
}

async function syncConflicts(absences, start, end) {
  const relevant = state.concerts.filter((c) => {
    if (c.status === "cancelled") return false;
    const last = lastDay(c);
    return last >= start && c.date <= end;
  });
  const others = state.members.map((m) => m.uid).filter((uid) => uid !== state.profile.uid);
  const writes = [];
  relevant.forEach((concert) => {
    const blocks = absences.some((a) => a.uid === state.profile.uid && a.status === "cannot" && a.end >= concert.date && a.start <= lastDay(concert));
    others.forEach((uid) => {
      const id = `conflict_${concert.id}_${state.profile.uid}`;
      writes.push(blocks
        ? {
          uid,
          id,
          payload: noticeMap({
            toUid: uid,
            fromUid: state.profile.uid,
            groupId: state.profile.groupId,
            title: "Кто-то не может",
            body: `${state.profile.displayName} не может ${concertLabel(concert)}. «${concert.title}».`,
            concertId: concert.id,
            type: "conflict",
          }),
        }
        : { uid, id, payload: null });
    });
  });
  if (writes.length) await applyNotices(writes);
}

async function saveConcert(draft) {
  const title = draft.title.trim();
  if (!title || title.length > 119) throw new Error("Название — от 1 до 119 символов");
  if (draft.endDate < draft.date) throw new Error("Конец раньше начала");
  if ((draft.note || "").length > 499) throw new Error("Заметка длиннее 499 символов");
  const col = collection(db, "groups", state.profile.groupId, "concerts");
  const ref = draft.id ? doc(col, draft.id) : doc(col);
  const data = {
    title,
    date: draft.date,
    endDate: draft.endDate,
    part: "evening",
    city: (draft.city || "").trim(),
    venue: (draft.venue || "").trim(),
    note: (draft.note || "").trim(),
    status: draft.status,
    createdBy: draft.createdBy || state.profile.uid,
    repeat: draft.repeat || "none",
  };
  if (!draft.id) data.createdAt = Date.now();
  if (draft.importUid) data.importUid = draft.importUid;
  if (draft.repeat && draft.repeat !== "none" && draft.repeatUntil) data.repeatUntil = draft.repeatUntil;
  else data.repeatUntil = deleteField();
  await setDoc(ref, data, { merge: true });
  return ref.id;
}

async function saveAbsence(draft) {
  if (draft.end < draft.start) throw new Error("Конец периода раньше начала");
  if ((draft.note || "").length > 299) throw new Error("Заметка длиннее 299 символов");
  const col = collection(db, "groups", state.profile.groupId, "absences");
  const ref = draft.id ? doc(col, draft.id) : doc(col);
  await setDoc(ref, {
    uid: state.profile.uid,
    startDate: draft.start,
    endDate: draft.end,
    parts: ["afternoon", "evening", "morning"],
    status: draft.status,
    note: (draft.note || "").trim(),
    updatedAt: Date.now(),
  });
  return ref.id;
}

function readEventDraft(form) {
  return {
    id: formVal(form, "id"),
    createdBy: formVal(form, "createdBy") || state.profile.uid,
    title: formVal(form, "title"),
    date: formVal(form, "date"),
    endDate: formVal(form, "endDate") || formVal(form, "date"),
    city: formVal(form, "city"),
    venue: formVal(form, "venue"),
    note: formVal(form, "note"),
    status: formVal(form, "status") || "confirmed",
    repeat: formVal(form, "repeat") || "none",
    repeatUntil: formVal(form, "repeatUntil") || null,
  };
}

async function run(task) {
  state.busy = true;
  state.error = "";
  render();
  try {
    await task();
  } catch (err) {
    state.error = mapAuthError(err);
  } finally {
    state.busy = false;
    render();
  }
}

function formVal(form, name) { return new FormData(form).get(name)?.toString() || ""; }

root.addEventListener("submit", (e) => {
  const form = e.target;
  if (!(form instanceof HTMLFormElement)) return;
  e.preventDefault();
  const kind = form.dataset.form;
  if (kind === "auth") {
    run(async () => {
      const email = formVal(form, "email").trim();
      const password = formVal(form, "password");
      const name = formVal(form, "name").trim();
      state.authEmail = email;
      state.authName = name;
      if (state.authMode === "reset") {
        if (!email.includes("@")) throw new Error("Напишите почту, на неё придёт письмо");
        await sendPasswordResetEmail(auth, email);
        state.error = "Письмо отправлено";
        state.authMode = "in";
        return;
      }
      if (!email.includes("@") || email.length < 5) throw new Error("Напишите почту");
      if (password.length < 8) throw new Error("Пароль не короче 8 символов");
      if (state.authMode === "up") {
        if (!name || name.length > 59) throw new Error("Напишите имя, как вас видит группа");
        const cred = await createUserWithEmailAndPassword(auth, email, password);
        await updateProfile(cred.user, { displayName: name });
        await setDoc(doc(db, "users", cred.user.uid), { email, displayName: name, groupId: null });
      } else {
        await signInWithEmailAndPassword(auth, email, password);
      }
    });
  }
  if (kind === "join") {
    run(async () => {
      const code = formVal(form, "code").trim().toUpperCase();
      if (code.length !== 6) throw new Error("Код из 6 символов");
      const snap = await getDoc(doc(db, "inviteCodes", code));
      if (!snap.exists()) throw new Error("Такого кода нет");
      const groupId = snap.data().groupId;
      await setDoc(doc(db, "groups", groupId, "members", state.profile.uid), {
        displayName: state.profile.displayName,
        email: state.profile.email,
        role: "member",
        usedCode: code,
      });
      await updateDoc(doc(db, "users", state.profile.uid), { groupId });
    });
  }
  if (kind === "event") {
    run(async () => {
      const draft = readEventDraft(form);
      const id = await saveConcert(draft);
      if (!draft.id) await notifyNewConcert({ ...draft, id, title: draft.title.trim() });
      state.modal = null;
    });
  }
  if (kind === "mark") {
    run(async () => {
      const previousId = formVal(form, "id");
      const id = await saveAbsence({
        id: previousId,
        start: formVal(form, "start"),
        end: formVal(form, "end") || formVal(form, "start"),
        status: formVal(form, "status"),
        note: formVal(form, "note"),
      });
      const saved = {
        id,
        uid: state.profile.uid,
        start: formVal(form, "start"),
        end: formVal(form, "end") || formVal(form, "start"),
        status: formVal(form, "status"),
        note: formVal(form, "note"),
      };
      const next = state.absences.filter((a) => a.id !== previousId && a.id !== id).concat(saved);
      await notifyMark(saved, `mark_${id}`);
      await syncConflicts(next, saved.start, saved.end);
      state.modal = null;
    });
  }
});

root.addEventListener("click", (e) => {
  const node = e.target.closest("[data-action]");
  if (!node) return;
  if (node.dataset.action === "close" && e.target.closest(".dialog") && e.target !== node) return;
  const action = node.dataset.action;
  if (action === "auth-in") { state.authMode = "in"; state.error = ""; render(); }
  if (action === "auth-up") { state.authMode = "up"; state.error = ""; render(); }
  if (action === "auth-reset") { state.authMode = "reset"; state.error = ""; render(); }
  if (action === "out") run(() => signOut(auth));
  if (action === "prev") { state.month = addMonths(state.month, -1); render(); }
  if (action === "next") { state.month = addMonths(state.month, 1); render(); }
  if (action === "today") { state.month = startOfMonth(new Date()); state.selected = todayIso(); render(); }
  if (action === "pick") { state.selected = node.dataset.day; render(); }
  if (action === "event-new") {
    state.modal = { type: "event", draft: { date: state.selected, endDate: state.selected, status: "confirmed", repeat: "none" } };
    render();
  }
  if (action === "import-open") {
    state.modal = { type: "import", url: "", rows: [], info: "", error: "" };
    render();
  }
  if (action === "import-load") {
    const url = document.getElementById("import-url")?.value || "";
    if (state.modal?.type === "import") state.modal.url = url;
    run(async () => {
      applyImportText(await fetchYandexIcs(url));
    });
  }
  if (action === "import-paste") {
    run(async () => {
      const text = await navigator.clipboard.readText();
      if (state.modal?.type === "import") state.modal.url = text;
      if (/BEGIN:VCALENDAR/i.test(text)) applyImportText(text);
    });
  }
  if (action === "import-all" && state.modal?.type === "import") {
    const on = node.dataset.on === "1";
    state.modal.rows = state.modal.rows.map((row) => row.duplicate ? row : { ...row, selected: on });
    render();
  }
  if (action === "import-save" && state.modal?.type === "import") {
    run(async () => {
      const drafts = state.modal.rows.filter((row) => row.selected && !row.duplicate)
        .map((row) => toConcertDraft(row.event, state.profile.uid));
      if (!drafts.length) throw new Error("Отметьте события, которых ещё нет в группе");
      for (const draft of drafts) await saveConcert(draft);
      await notifyImported(drafts.length);
      state.modal = null;
    });
  }
  if (action === "event-edit") {
    const concert = state.concerts.find((c) => c.id === node.dataset.id);
    if (concert) { state.modal = { type: "event", draft: { ...concert, endDate: lastDay(concert), repeat: concert.repeat || "none" } }; render(); }
  }
  if (action === "mark-new") {
    const mine = exactDay(state.profile.uid, state.selected);
    state.modal = { type: "mark", draft: mine || { start: state.selected, end: state.selected, status: "cannot" } };
    render();
  }
  if (action === "mark-edit") {
    const absence = state.absences.find((a) => a.id === node.dataset.id);
    if (absence) { state.modal = { type: "mark", draft: { ...absence, start: absence.startDate || absence.start, end: absence.endDate || absence.end } }; render(); }
  }
  if (action === "quick") {
    run(async () => {
      const existing = exactDay(state.profile.uid, state.selected);
      const savedId = await saveAbsence({
        id: existing?.id || "",
        start: state.selected,
        end: state.selected,
        status: node.dataset.status,
        note: existing?.note || "",
      });
      const saved = { id: savedId, uid: state.profile.uid, start: state.selected, end: state.selected, status: node.dataset.status, note: existing?.note || "" };
      const next = state.absences.filter((a) => a.id !== existing?.id).concat(saved);
      await notifyMark(saved, `mark_${savedId}`);
      await syncConflicts(next, state.selected, state.selected);
    });
  }
  if (action === "close") { state.modal = null; render(); }
  if (action === "delete-event") {
    run(async () => {
      await deleteDoc(doc(db, "groups", state.profile.groupId, "concerts", node.dataset.id));
      state.modal = null;
    });
  }
  if (action === "delete-mark") {
    run(async () => {
      await deleteDoc(doc(db, "groups", state.profile.groupId, "absences", node.dataset.id));
      const next = state.absences.filter((a) => a.id !== node.dataset.id);
      await notifyMark(null, `mark_${node.dataset.id}`);
      await syncConflicts(next, state.selected, state.selected);
      state.modal = null;
    });
  }
  if (action === "rsvp") {
    run(async () => {
      await setDoc(doc(db, "groups", state.profile.groupId, "concerts", node.dataset.id, "rsvps", state.profile.uid), {
        status: node.dataset.status,
        updatedAt: Date.now(),
      });
      state.rsvps[node.dataset.id] = { ...(state.rsvps[node.dataset.id] || {}), [state.profile.uid]: node.dataset.status };
    });
  }
});

root.addEventListener("input", (e) => {
  if (e.target.id === "import-url" && state.modal?.type === "import") state.modal.url = e.target.value;
});

root.addEventListener("change", (e) => {
  if (e.target.name === "repeat" && state.modal?.type === "event" && e.target.form) {
    state.modal.draft = readEventDraft(e.target.form);
    render();
  }
  if (e.target.dataset.action === "import-toggle" && state.modal?.type === "import") {
    const uid = e.target.dataset.uid;
    const on = e.target.checked;
    state.modal.rows = state.modal.rows.map((row) => (
      row.event.uid === uid && !row.duplicate ? { ...row, selected: on } : row
    ));
    render();
  }
  if (e.target.id === "import-file" && e.target.files?.[0]) {
    e.target.files[0].text().then((text) => {
      applyImportText(text);
      render();
    }).catch(() => {
      if (state.modal?.type === "import") state.modal.error = "Не удалось открыть файл";
      render();
    });
  }
});

function renderAuth() {
  const reset = state.authMode === "reset";
  const up = state.authMode === "up";
  return `
    <div class="center">
      <form class="card" data-form="auth">
        <div class="brand">К</div>
        <h1>Календарь группы</h1>
        <p class="lead">Тот же вход, что в телефоне. С компьютера удобно ставить даты.</p>
        ${up ? `<label for="name">Имя</label><input id="name" name="name" autocomplete="name" value="${esc(state.authName || "")}" required>` : ""}
        <label for="email">Почта</label>
        <input id="email" name="email" type="email" autocomplete="email" value="${esc(state.authEmail || "")}" required>
        ${reset ? "" : `<label for="password">Пароль</label><input id="password" name="password" type="password" autocomplete="${up ? "new-password" : "current-password"}" required>`}
        <p class="error">${esc(state.error)}</p>
        <div class="actions">
          <button class="primary" ${state.busy ? "disabled" : ""}>${reset ? "Отправить письмо" : up ? "Создать аккаунт" : "Войти"}</button>
          ${up || reset ? `<button type="button" class="ghost" data-action="auth-in">Назад ко входу</button>` : `
            <button type="button" class="ghost" data-action="auth-up">Регистрация</button>
            <button type="button" class="ghost" data-action="auth-reset">Не помню пароль</button>`}
        </div>
      </form>
    </div>`;
}

function renderGate() {
  return `
    <div class="center">
      <form class="card" data-form="join">
        <h1>Код группы</h1>
        <p class="lead">Введите код из приложения, чтобы видеть те же даты.</p>
        <label>Код</label>
        <input name="code" maxlength="6" placeholder="ABC123" required>
        <p class="error">${esc(state.error)}</p>
        <div class="actions">
          <button class="primary" ${state.busy ? "disabled" : ""}>Войти в группу</button>
          <button type="button" class="ghost" data-action="out">Выйти из аккаунта</button>
        </div>
      </form>
    </div>`;
}

function renderCalendar() {
  const cells = monthBoard(state.month);
  const title = `${MONTHS[state.month.getMonth()]} ${state.month.getFullYear()}`;
  const mine = winningAbsence(state.profile.uid, state.selected);
  const weekend = (day) => {
    const n = parseIso(day).getDay();
    return n === 0 || n === 6;
  };
  return `
    <header class="bar">
      <div class="brand-line">
        <strong>${esc(state.group?.name || "Календарь группы")}</strong>
        <span class="hint">${esc(state.profile.displayName)}</span>
      </div>
      <div class="month-switch">
        <button class="icon-btn" data-action="prev" aria-label="Предыдущий месяц">‹</button>
        <h1>${title}</h1>
        <button class="icon-btn" data-action="next" aria-label="Следующий месяц">›</button>
      </div>
      <div class="bar-actions">
        <button class="chip" data-action="today">Сегодня</button>
        <button class="chip" data-action="import-open">Яндекс</button>
        <button class="primary" data-action="event-new">+ Событие</button>
        <button class="ghost" data-action="out">Выйти</button>
      </div>
    </header>
    <div class="strip">
      <b>${ruFull(state.selected)}</b>
      ${["can", "cannot", "maybe"].map((status) => `
        <button class="chip ${mine?.status === status ? `on-${status}` : ""}" data-action="quick" data-status="${status}">${MARK[status]}</button>
      `).join("")}
      <button class="chip" data-action="mark-new">Несколько дней</button>
      ${state.error ? `<span class="error">${esc(state.error)}</span>` : ""}
    </div>
    <div class="weekdays">${WEEKDAYS.map((d) => `<span>${d}</span>`).join("")}</div>
    <div class="board">
      ${cells.map(({ day, outside }) => {
        const chips = chipsOn(day);
        const extra = chips.length - 4;
        const visible = chips.slice(0, 4);
        return `
          <div class="cell ${outside ? "out" : ""} ${weekend(day) ? "weekend" : ""} ${day === todayIso() ? "today" : ""} ${day === state.selected ? "selected" : ""}" data-action="pick" data-day="${day}">
            <span class="n">${Number(day.slice(-2))}</span>
            <div class="chips">
              ${visible.map((chip) => {
                const canOpen = chip.kind === "event" || chip.uid === state.profile.uid;
                const action = chip.kind === "event" ? "event-edit" : "mark-edit";
                return `<button type="button" class="plaque ${chip.tone}" ${canOpen ? `data-action="${action}" data-id="${chip.id}"` : "disabled"}>${esc(chip.text)}</button>`;
              }).join("")}
              ${extra > 0 ? `<span class="more">+${extra}</span>` : ""}
            </div>
          </div>`;
      }).join("")}
    </div>`;
}

function renderEventModal() {
  const d = state.modal.draft;
  const repeating = (d.repeat || "none") !== "none";
  return `
    <div class="overlay" data-action="close">
      <form class="dialog" data-form="event" data-stop="1">
        <h2>${d.id ? "Событие" : "Новое событие"}</h2>
        <input type="hidden" name="id" value="${esc(d.id || "")}">
        <input type="hidden" name="createdBy" value="${esc(d.createdBy || state.profile.uid)}">
        <label>Название</label>
        <input name="title" value="${esc(d.title || "")}" required maxlength="119">
        <div class="row">
          <div><label>С</label><input type="date" name="date" value="${d.date}" required></div>
          <div><label>По</label><input type="date" name="endDate" value="${d.endDate || d.date}" required></div>
        </div>
        <label>Повтор</label>
        <select name="repeat">
          ${Object.entries(REPEAT).map(([k, v]) => `<option value="${k}" ${ (d.repeat || "none") === k ? "selected" : ""}>${v}</option>`).join("")}
        </select>
        ${repeating ? `<label>До какой даты</label><input type="date" name="repeatUntil" value="${d.repeatUntil || ""}">` : ""}
        <div class="row">
          <div><label>Город</label><input name="city" value="${esc(d.city || "")}"></div>
          <div><label>Площадка</label><input name="venue" value="${esc(d.venue || "")}"></div>
        </div>
        <label>Статус</label>
        <select name="status">
          ${Object.entries(STATUS).map(([k, v]) => `<option value="${k}" ${(d.status || "confirmed") === k ? "selected" : ""}>${v}</option>`).join("")}
        </select>
        <label>Заметка, время</label>
        <textarea name="note" maxlength="499">${esc(d.note || "")}</textarea>
        ${d.id ? `
          <p class="hint">Кто едет</p>
          <div class="rsvp">
            ${Object.entries(RSVP).map(([k, v]) => `<button type="button" class="chip" data-action="rsvp" data-id="${d.id}" data-status="${k}">${v}</button>`).join("")}
          </div>` : ""}
        <p class="error">${esc(state.error)}</p>
        <div class="actions">
          <button class="primary" ${state.busy ? "disabled" : ""}>Сохранить</button>
          ${d.id ? `<button type="button" class="danger" data-action="delete-event" data-id="${d.id}">Удалить</button>` : ""}
          <button type="button" class="ghost" data-action="close">Закрыть</button>
        </div>
      </form>
    </div>`;
}

function renderMarkModal() {
  const d = state.modal.draft;
  const start = d.startDate || d.start || state.selected;
  const end = d.endDate || d.end || start;
  return `
    <div class="overlay" data-action="close">
      <form class="dialog" data-form="mark" data-stop="1">
        <h2>Отметка</h2>
        <input type="hidden" name="id" value="${esc(d.id || "")}">
        <label>Как</label>
        <select name="status">
          ${Object.entries(MARK).map(([k, v]) => `<option value="${k}" ${(d.status || "cannot") === k ? "selected" : ""}>${v}</option>`).join("")}
        </select>
        <div class="row">
          <div><label>С</label><input type="date" name="start" value="${start}" required></div>
          <div><label>По</label><input type="date" name="end" value="${end}" required></div>
        </div>
        <label>Заметка</label>
        <textarea name="note" maxlength="299">${esc(d.note || "")}</textarea>
        <p class="error">${esc(state.error)}</p>
        <div class="actions">
          <button class="primary" ${state.busy ? "disabled" : ""}>Сохранить</button>
          ${d.id ? `<button type="button" class="danger" data-action="delete-mark" data-id="${d.id}">Удалить</button>` : ""}
          <button type="button" class="ghost" data-action="close">Закрыть</button>
        </div>
      </form>
    </div>`;
}

function renderApp() {
  return `
    <div class="app">
      ${renderCalendar()}
    </div>
    ${state.modal?.type === "event" ? renderEventModal() : ""}
    ${state.modal?.type === "mark" ? renderMarkModal() : ""}
    ${state.modal?.type === "import" ? renderImportModal() : ""}`;
}

function renderImportModal() {
  const modal = state.modal;
  const selected = modal.rows.filter((row) => row.selected && !row.duplicate).length;
  return `
    <div class="overlay" data-action="close">
      <div class="dialog wide" data-stop="1">
        <h2>Яндекс.Календарь</h2>
        <p class="hint">В Яндекс.Календаре откройте календарь → настройки → «Экспорт». Лучше скачать файл .ics и загрузить его сюда. Ссылку iCal тоже можно вставить, пароль Яндекса не нужен.</p>
        <label for="import-url">Ссылка iCal</label>
        <textarea id="import-url">${esc(modal.url || "")}</textarea>
        <div class="quick">
          <button class="chip" data-action="import-paste">Вставить из буфера</button>
          <button class="primary" data-action="import-load" ${state.busy ? "disabled" : ""}>${state.busy ? "Загружаю…" : "Загрузить по ссылке"}</button>
          <label class="chip file-chip">Файл .ics<input id="import-file" type="file" accept=".ics,.ical,text/calendar,text/plain"></label>
        </div>
        <p class="error">${esc(modal.error || state.error)}</p>
        ${modal.info ? `<p class="hint">${esc(modal.info)}</p>` : ""}
        ${modal.rows.some((row) => !row.duplicate) ? `
          <div class="quick">
            <button class="chip" data-action="import-all" data-on="1">Все</button>
            <button class="chip" data-action="import-all" data-on="0">Снять</button>
          </div>` : ""}
        <div class="import-list">
          ${modal.rows.map((row) => `
            <label class="import-row ${row.duplicate ? "off" : ""}">
              <input type="checkbox" ${row.selected ? "checked" : ""} ${row.duplicate ? "disabled" : ""} data-action="import-toggle" data-uid="${esc(row.event.uid).replace(/"/g, "")}">
              <span>
                <b>${esc(row.event.title)}</b>
                <span class="meta">${esc([
                  ruRange(row.event.date, row.event.endDate),
                  row.event.timeText,
                  row.event.location,
                  row.event.repeat && row.event.repeat !== "none" ? REPEAT[row.event.repeat] : "",
                  row.duplicate ? "уже в группе" : "",
                ].filter(Boolean).join(" · "))}</span>
              </span>
            </label>
          `).join("")}
        </div>
        <div class="actions">
          <button class="primary" data-action="import-save" ${state.busy || selected === 0 ? "disabled" : ""}>${state.busy ? "Добавляем…" : `Добавить в группу: ${selected}`}</button>
          <button type="button" class="ghost" data-action="close">Закрыть</button>
        </div>
      </div>
    </div>`;
}

function render() {
  if (state.screen === "boot") root.innerHTML = `<div class="center"><p class="hint">Загрузка…</p></div>`;
  else if (state.screen === "auth") root.innerHTML = renderAuth();
  else if (state.screen === "gate") root.innerHTML = renderGate();
  else root.innerHTML = renderApp();
}

render();
