const WEEKDAYS = { MO: 1, TU: 2, WE: 3, TH: 4, FR: 5, SA: 6, SU: 7 };

function pad(n) { return String(n).padStart(2, "0"); }
function isoFromDate(d) { return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`; }
function parseIso(s) { const [y, m, d] = s.split("-").map(Number); return new Date(y, m - 1, d); }
function plusDays(s, n) { const d = parseIso(s); d.setDate(d.getDate() + n); return isoFromDate(d); }
function daysBetween(a, b) { return Math.round((parseIso(b) - parseIso(a)) / 86400000); }
function dow(s) { const d = parseIso(s).getDay(); return d === 0 ? 7 : d; }
function plusMonthsDate(s, n) {
  const [y, m, d] = s.split("-").map(Number);
  const total = y * 12 + (m - 1) + n;
  const ny = Math.floor(total / 12);
  const nm = total % 12;
  const last = new Date(ny, nm + 1, 0).getDate();
  return `${ny}-${pad(nm + 1)}-${pad(Math.min(d, last))}`;
}

export function parseIcs(text) {
  const lines = unfoldIcs(text);
  if (!lines.some((line) => line.toUpperCase() === "BEGIN:VCALENDAR")) {
    throw new Error("Это не файл календаря iCal");
  }
  const events = extractEventBlocks(lines)
    .flatMap(parseEvent)
    .filter((event) => event.title);
  const seen = new Set();
  return events.filter((event) => {
    const key = `${event.uid}|${event.date}|${event.endDate}|${event.title}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  }).sort((a, b) => a.date.localeCompare(b.date) || a.timeText.localeCompare(b.timeText) || a.title.localeCompare(b.title, "ru"));
}

export function alreadyImported(event, concerts) {
  if (event.uid && concerts.some((c) => c.importUid === event.uid)) return true;
  return concerts.some((c) =>
    (c.title || "").toLowerCase() === event.title.toLowerCase() &&
    c.date === event.date &&
    (c.endDate || c.date) === event.endDate,
  );
}

export function toConcertDraft(event, createdBy) {
  const [city, venue] = splitLocation(event.location);
  const note = [event.timeText, event.description].filter(Boolean).join(" · ").slice(0, 499);
  return {
    title: (event.title || "Событие").slice(0, 119),
    date: event.date,
    endDate: event.endDate,
    city: city.slice(0, 80),
    venue: venue.slice(0, 80),
    note,
    status: event.cancelled ? "cancelled" : "confirmed",
    createdBy,
    importUid: event.uid,
    repeat: event.repeat || "none",
    repeatUntil: event.repeatUntil || null,
  };
}

function unfoldIcs(text) {
  const raw = text.replace(/^\uFEFF/, "").replace(/\r\n/g, "\n").replace(/\r/g, "\n").split("\n");
  const lines = [];
  for (const line of raw) {
    if (line.startsWith(" ") || line.startsWith("\t")) {
      if (lines.length) lines[lines.length - 1] += line.slice(1);
    } else if (line) {
      lines.push(line);
    }
  }
  return lines;
}

function extractEventBlocks(lines) {
  const blocks = [];
  let current = null;
  let nested = 0;
  for (const line of lines) {
    const upper = line.toUpperCase();
    if (!current) {
      if (upper === "BEGIN:VEVENT") current = [];
      continue;
    }
    if (upper.startsWith("BEGIN:")) { nested += 1; continue; }
    if (nested > 0) {
      if (upper.startsWith("END:")) nested -= 1;
      continue;
    }
    if (upper === "END:VEVENT") {
      blocks.push(current);
      current = null;
      continue;
    }
    current.push(line);
  }
  return blocks;
}

function parseEvent(lines) {
  const props = lines.map(parseProp).filter(Boolean);
  const title = unescapeIcs(last(props, "SUMMARY")?.value || "").trim();
  if (!title) return [];
  const startProp = last(props, "DTSTART");
  if (!startProp) return [];
  const start = parseWhen(startProp);
  if (!start) return [];
  const end = last(props, "DTEND") ? parseWhen(last(props, "DTEND")) : durationEnd(start, last(props, "DURATION")?.value);
  const allDay = !start.time;
  const lastDay = inclusiveLast(start, end, allDay);
  const timeText = formatTime(start, end, allDay);
  const location = unescapeIcs(last(props, "LOCATION")?.value || "").trim();
  const description = stripTags(unescapeIcs(last(props, "DESCRIPTION")?.value || "")).trim();
  const cancelled = (last(props, "STATUS")?.value || "").toUpperCase() === "CANCELLED";
  const uid = unescapeIcs(last(props, "UID")?.value || "").trim();
  const exception = props.some((p) => p.name === "RECURRENCE-ID");
  const rrule = last(props, "RRULE")?.value;
  const exdates = new Set(
    props.filter((p) => p.name === "EXDATE")
      .flatMap((p) => p.value.split(","))
      .map((raw) => parseWhen({ name: "EXDATE", params: {}, value: raw.trim() })?.date)
      .filter(Boolean),
  );
  const template = {
    uid: instanceUid(uid, start.date),
    title,
    date: start.date,
    endDate: lastDay,
    timeText,
    hour: start.time?.h ?? null,
    location,
    description,
    cancelled,
    repeat: "none",
    repeatUntil: null,
  };
  if (exception || !rrule) return [template];
  const collapsed = collapseRrule(template, uid, rrule, exdates);
  if (collapsed) return collapsed;
  return expandRrule(start.date, lastDay, rrule, exdates).map(([from, to]) => ({
    ...template,
    uid: instanceUid(uid, from),
    date: from,
    endDate: to,
  }));
}

function last(props, name) {
  return [...props].reverse().find((p) => p.name === name);
}

function collapseRrule(template, uid, rule, exdates) {
  if (exdates.size) return null;
  const parts = ruleParts(rule);
  const freq = (parts.FREQ || "").toUpperCase();
  const until = parts.UNTIL ? stampDate(parts.UNTIL) : plusMonthsDate(template.date, 12);
  const byDays = (parts.BYDAY || "").split(",").map((token) => WEEKDAYS[token.trim().toUpperCase().slice(-2)]).filter(Boolean);
  const hasNth = (parts.BYDAY || "").split(",").some((token) => {
    const prefix = token.trim().slice(0, -2);
    return prefix !== "" && Number.isInteger(Number(prefix));
  });
  if (hasNth) return null;
  const span = Math.max(0, daysBetween(template.date, template.endDate));
  const eventOn = (start, period) => ({
    ...template,
    uid: instanceUid(uid, start),
    date: start,
    endDate: plusDays(start, span),
    repeat: period,
    repeatUntil: until,
  });
  if (freq === "DAILY") return [eventOn(template.date, "daily")];
  if (freq === "WEEKLY") {
    const days = byDays.length ? byDays : [dow(template.date)];
    return days.map((day) => {
      let start = template.date;
      while (dow(start) !== day) start = plusDays(start, 1);
      return eventOn(start, "weekly");
    });
  }
  if (freq === "MONTHLY") return parts.BYDAY ? null : [eventOn(template.date, "monthly")];
  if (freq === "YEARLY") return [eventOn(template.date, "yearly")];
  return null;
}

function expandRrule(start, lastDay, rule, exdates) {
  const parts = ruleParts(rule);
  const freq = (parts.FREQ || "").toUpperCase();
  const interval = Math.max(1, Number(parts.INTERVAL) || 1);
  const count = Math.min(80, Math.max(1, Number(parts.COUNT) || 80));
  const until = parts.UNTIL ? stampDate(parts.UNTIL) : plusMonthsDate(start, 12);
  const span = Math.max(0, daysBetween(start, lastDay));
  const byDays = (parts.BYDAY || "").split(",").map((token) => WEEKDAYS[token.trim().toUpperCase().slice(-2)]).filter(Boolean);
  const monthlyWeekday = (parts.BYDAY || "").split(",").map((token) => {
    const clean = token.trim().toUpperCase();
    const day = WEEKDAYS[clean.slice(-2)];
    const ordinal = Number(clean.slice(0, -2));
    if (!day || !Number.isInteger(ordinal)) return null;
    return { day, ordinal };
  }).find(Boolean);
  const monthDay = parts.BYMONTHDAY ? Number(parts.BYMONTHDAY) : null;
  let dates = [start];
  if (freq === "DAILY") {
    dates = [];
    let cursor = start;
    while (cursor <= until && dates.length < count) {
      dates.push(cursor);
      cursor = plusDays(cursor, interval);
    }
  } else if (freq === "WEEKLY") {
    dates = expandWeekly(start, until, interval, count, byDays);
  } else if (freq === "MONTHLY") {
    dates = expandMonthly(start, until, interval, count, monthDay, monthlyWeekday);
  }
  return dates.filter((day) => !exdates.has(day)).map((day) => [day, plusDays(day, span)]);
}

function expandWeekly(start, until, interval, count, byDays) {
  const days = byDays.length ? byDays : [dow(start)];
  const originMonday = mondayOnOrBefore(start);
  const out = [];
  let cursor = start;
  while (cursor <= until && out.length < count) {
    const weekMonday = mondayOnOrBefore(cursor);
    const weeks = Math.round(daysBetween(originMonday, weekMonday) / 7);
    if (weeks % interval === 0 && days.includes(dow(cursor))) out.push(cursor);
    cursor = plusDays(cursor, 1);
  }
  return out;
}

function mondayOnOrBefore(s) {
  const n = dow(s);
  return plusDays(s, 1 - n);
}

function expandMonthly(start, until, interval, count, monthDay, weekday) {
  const out = [];
  let [y, m] = start.split("-").map(Number);
  while (out.length < count) {
    let date = null;
    if (weekday) date = nthWeekday(y, m, weekday.ordinal, weekday.day);
    else if (monthDay) date = safeDay(y, m, monthDay);
    else date = safeDay(y, m, Number(start.slice(8)));
    if (date && date >= start && date <= until) out.push(date);
    m += interval;
    while (m > 12) { m -= 12; y += 1; }
    const first = `${y}-${pad(m)}-01`;
    if (first > until) break;
  }
  return out;
}

function nthWeekday(year, month, ordinal, day) {
  const jsDay = day === 7 ? 0 : day;
  if (ordinal > 0) {
    const first = new Date(year, month - 1, 1);
    const delta = (jsDay - first.getDay() + 7) % 7;
    const date = new Date(year, month - 1, 1 + delta + (ordinal - 1) * 7);
    return date.getMonth() === month - 1 ? isoFromDate(date) : null;
  }
  if (ordinal < 0) {
    const last = new Date(year, month, 0);
    const delta = (last.getDay() - jsDay + 7) % 7;
    const date = new Date(year, month, 0 - delta + (ordinal + 1) * 7);
    return date.getMonth() === month - 1 ? isoFromDate(date) : null;
  }
  return null;
}

function safeDay(year, month, day) {
  const last = new Date(year, month, 0).getDate();
  if (day < 1 || day > last) return null;
  return `${year}-${pad(month)}-${pad(day)}`;
}

function instanceUid(uid, date) { return `${uid || "ics"}/${date}`; }

function parseProp(line) {
  const colon = line.indexOf(":");
  if (colon <= 0) return null;
  const parts = splitParams(line.slice(0, colon));
  if (!parts.length) return null;
  const params = {};
  for (let i = 1; i < parts.length; i += 1) {
    const eq = parts[i].indexOf("=");
    if (eq <= 0) continue;
    params[parts[i].slice(0, eq).toUpperCase()] = parts[i].slice(eq + 1).replace(/^"|"$/g, "");
  }
  return { name: parts[0].toUpperCase(), params, value: line.slice(colon + 1) };
}

function splitParams(meta) {
  const parts = [];
  let current = "";
  let quoted = false;
  for (const ch of meta) {
    if (ch === '"') { quoted = !quoted; current += ch; }
    else if (ch === ";" && !quoted) { parts.push(current); current = ""; }
    else current += ch;
  }
  if (current) parts.push(current);
  return parts;
}

function parseWhen(prop) {
  const raw = (prop.value || "").trim();
  if (!raw) return null;
  const asDate = (prop.params.VALUE || "").toUpperCase() === "DATE" || (!raw.includes("T") && raw.length >= 8);
  if (asDate) {
    const date = stampDate(raw);
    return date ? { date, time: null } : null;
  }
  const utc = raw.endsWith("Z") || raw.endsWith("z");
  const stamp = raw.replace(/z$/i, "");
  const y = Number(stamp.slice(0, 4));
  const mo = Number(stamp.slice(4, 6));
  const d = Number(stamp.slice(6, 8));
  const h = Number(stamp.slice(9, 11) || 0);
  const mi = Number(stamp.slice(11, 13) || 0);
  const s = Number(stamp.slice(13, 15) || 0);
  if (!y || !mo || !d) return null;
  if (utc) return moscowLocal(new Date(Date.UTC(y, mo - 1, d, h, mi, s)));
  return { date: `${y}-${pad(mo)}-${pad(d)}`, time: { h, mi }, hour: h };
}

function moscowLocal(date) {
  const parts = new Intl.DateTimeFormat("en-GB", {
    timeZone: "Europe/Moscow",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).formatToParts(date);
  const get = (type) => parts.find((p) => p.type === type)?.value;
  const h = Number(get("hour"));
  return { date: `${get("year")}-${get("month")}-${get("day")}`, time: { h, mi: Number(get("minute")) }, hour: h };
}

function stampDate(raw) {
  const s = raw.replace(/z$/i, "").slice(0, 8);
  if (s.length < 8) return null;
  return `${s.slice(0, 4)}-${s.slice(4, 6)}-${s.slice(6, 8)}`;
}

function durationEnd(start, value) {
  if (!value || !value.startsWith("P")) return null;
  const hours = Number(/\d+(?=H)/.exec(value)?.[0] || 0);
  const minutes = Number(/(\d+)M/.exec(value)?.[1] || 0);
  const days = Number(/P(?:T.*)?(\d+)D/.exec(value)?.[1] || /^P(\d+)D/.exec(value)?.[1] || 0);
  if (!start.time) return { date: plusDays(start.date, days), time: null };
  const dt = parseIso(start.date);
  dt.setHours(start.time.h, start.time.mi, 0, 0);
  dt.setDate(dt.getDate() + days);
  dt.setHours(dt.getHours() + hours, dt.getMinutes() + minutes);
  return { date: isoFromDate(dt), time: { h: dt.getHours(), mi: dt.getMinutes() } };
}

function inclusiveLast(start, end, allDay) {
  if (!end) return start.date;
  if (allDay || !end.time) {
    const last = plusDays(end.date, -1);
    return last < start.date ? start.date : last;
  }
  if (end.time.h === 0 && end.time.mi === 0 && end.date > start.date) return plusDays(end.date, -1);
  return end.date < start.date ? start.date : end.date;
}

function formatTime(start, end, allDay) {
  if (!start.time || allDay) return "";
  const from = `${pad(start.time.h)}:${pad(start.time.mi)}`;
  if (!end?.time) return from;
  if (end.date !== start.date && end.time.h === 0 && end.time.mi === 0) return from;
  if (end.time.h === start.time.h && end.time.mi === start.time.mi) return from;
  return `${from}–${pad(end.time.h)}:${pad(end.time.mi)}`;
}

function ruleParts(rule) {
  const out = {};
  for (const chunk of rule.split(";")) {
    const eq = chunk.indexOf("=");
    if (eq <= 0) continue;
    out[chunk.slice(0, eq).toUpperCase()] = chunk.slice(eq + 1);
  }
  return out;
}

function unescapeIcs(value) {
  let out = "";
  for (let i = 0; i < value.length; i += 1) {
    if (value[i] === "\\" && i + 1 < value.length) {
      const next = value[i + 1];
      out += next === "n" || next === "N" ? "\n" : next;
      i += 1;
    } else out += value[i];
  }
  return out;
}

function stripTags(value) {
  if (!value.includes("<")) return value.replace(/\\n/g, "\n");
  return value.replace(/<br\s*\/?>/gi, "\n").replace(/<[^>]+>/g, "")
    .replace(/&nbsp;/g, " ").replace(/&amp;/g, "&").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, '"');
}

function splitLocation(location) {
  const clean = location.trim();
  if (!clean) return ["", ""];
  const comma = clean.indexOf(",");
  if (comma >= 1 && comma <= 39) {
    const city = clean.slice(0, comma).trim();
    const venue = clean.slice(comma + 1).trim();
    if (city && venue) return [city, venue];
  }
  return ["", clean];
}

export function defaultImport(event, today) {
  if (event.cancelled || event.endDate < today) return false;
  const title = event.title.toLowerCase();
  if (title.includes("минус") || title.includes("оплата") || title.includes("аренда")) return false;
  return true;
}

export function tooOld(event, today) {
  return event.endDate < plusDays(today, -14);
}
