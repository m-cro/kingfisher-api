#!/usr/bin/env python3
"""
UIUC Course Explorer Historical Scraper & Data Manager
======================================================
Scrapes historical and live scheduled course sections from UIUC Course Explorer
(courses.illinois.edu/cisapp/explorer/schedule.xml) back to 2004.

Features:
- Dynamically discovers all past calendar years & terms.
- Robust checkpointing with SQLite (safe to interrupt and resume anytime).
- Multi-format Export: CSV (flat tabular) and JSON (structured hierarchy).
- CSV Import: Fast bulk import from CSV directly into PostgreSQL without re-scraping.
- Configurable request identity, timing jitter, and server-aware retry backoff.
"""

import argparse
import csv
import json
import math
import os
import random
import re
import sqlite3
import ssl
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
from typing import Dict, List, Optional, Set, Tuple

BASE_URL = "https://courses.illinois.edu/cisapp/explorer/schedule"
USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
DEFAULT_DELAY = 0.75  # Seconds between requests
MAX_RETRIES = 4
BACKOFF_FACTOR = 2.0
SEASON_ORDER = {"spring": 1, "summer": 2, "fall": 3, "winter": 4}


def create_ssl_context():
    """Use the platform trust store; never disable certificate verification."""
    return ssl.create_default_context()


def parse_xml_clean(xml_text: str) -> ET.Element:
    """Strips XML namespaces and prefixes for reliable element matching."""
    clean = re.sub(r'\sxmlns(:\w+)?="[^"]+"', '', xml_text)
    clean = re.sub(r'<(/?)\w+:([a-zA-Z0-9_-]+)', r'<\1\2', clean)
    return ET.fromstring(clean)


class FetchError(RuntimeError):
    """A failed request must not be checkpointed as a completed course/term."""


class RateLimitedFetcher:
    """Sequential requests with bounded retries and additive timing jitter."""

    def __init__(self, delay: float = DEFAULT_DELAY, jitter: float = 0.5,
                 user_agent: Optional[str] = None):
        if any(not math.isfinite(v) or v < 0 for v in (delay, jitter)):
            raise ValueError("delay and jitter must be finite and non-negative")
        self.user_agent = user_agent if user_agent is not None else os.getenv("COURSE_EXPLORER_USER_AGENT", USER_AGENT)
        if not self.user_agent.strip() or any(c in self.user_agent for c in "\r\n"):
            raise ValueError("user agent must be a non-empty single line")
        self.delay = delay
        self.jitter = jitter
        self.last_request_time = None
        self.ssl_ctx = create_ssl_context()

    @staticmethod
    def retry_after(value: Optional[str]) -> float:
        if not value:
            return 0.0
        try:
            return max(0.0, float(int(value)))
        except ValueError:
            try:
                when = parsedate_to_datetime(value)
                return max(0.0, (when - datetime.now(timezone.utc)).total_seconds())
            except (ValueError, TypeError, OverflowError):
                return 0.0

    def fetch(self, url: str) -> Optional[str]:
        req = urllib.request.Request(url, headers={
            "User-Agent": self.user_agent,
            "Accept": "application/xml, text/xml;q=0.9, */*;q=0.8",
            "Accept-Language": "en-US,en;q=0.9",
        })
        for attempt in range(1, MAX_RETRIES + 1):
            if self.last_request_time is not None:
                interval = self.delay + random.uniform(0, self.jitter)
                time.sleep(max(0.0, interval - (time.monotonic() - self.last_request_time)))
            self.last_request_time = time.monotonic()
            try:
                with urllib.request.urlopen(req, timeout=20, context=self.ssl_ctx) as resp:
                    return resp.read().decode("utf-8", errors="replace")
            except urllib.error.HTTPError as exc:
                exc.close()
                if exc.code == 404:
                    return None
                if exc.code not in (403, 429, 500, 502, 503, 504):
                    raise FetchError(f"HTTP {exc.code} for {url}") from exc
                if attempt == MAX_RETRIES:
                    raise FetchError(f"HTTP {exc.code} for {url} after {attempt} attempts") from exc
                backoff = 12 * attempt if exc.code == 403 else BACKOFF_FACTOR ** attempt
                wait = max(backoff, self.retry_after(exc.headers.get("Retry-After")))
                wait += random.uniform(0, self.jitter)
                print(f"[!] HTTP {exc.code}; retrying in {wait:.1f}s: {url}", file=sys.stderr)
                time.sleep(wait)
            except (urllib.error.URLError, TimeoutError, OSError) as exc:
                if attempt == MAX_RETRIES:
                    raise FetchError(f"Request failed for {url} after {attempt} attempts: {exc}") from exc
                time.sleep(BACKOFF_FACTOR ** attempt + random.uniform(0, self.jitter))
        raise FetchError(f"Request failed for {url}")


class CheckpointManager:
    """Tracks scrape progress in SQLite database so runs can resume."""

    def __init__(self, db_path: str):
        self.db_path = db_path
        os.makedirs(os.path.dirname(os.path.abspath(db_path)), exist_ok=True)
        self.conn = sqlite3.connect(db_path)
        self.conn.execute("PRAGMA journal_mode=WAL;")
        self._init_tables()

    def _init_tables(self):
        with self.conn:
            self.conn.execute("""
                CREATE TABLE IF NOT EXISTS completed_courses (
                    year_term TEXT NOT NULL,
                    subject TEXT NOT NULL,
                    course_number TEXT NOT NULL,
                    completed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (year_term, subject, course_number)
                );
            """)
            self.conn.execute("""
                CREATE TABLE IF NOT EXISTS completed_terms (
                    year_term TEXT PRIMARY KEY,
                    completed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                );
            """)
            self.conn.execute("""
                CREATE TABLE IF NOT EXISTS scraped_sections (
                    year_term TEXT NOT NULL,
                    subject TEXT NOT NULL,
                    course_number TEXT NOT NULL,
                    crn TEXT NOT NULL,
                    data_json TEXT NOT NULL,
                    PRIMARY KEY (year_term, subject, course_number, crn)
                );
            """)

    def is_course_completed(self, year_term: str, subject: str, course_num: str) -> bool:
        cur = self.conn.cursor()
        cur.execute(
            "SELECT 1 FROM completed_courses WHERE year_term = ? AND subject = ? AND course_number = ?",
            (year_term, subject, course_num),
        )
        return cur.fetchone() is not None

    def mark_course_completed(self, year_term: str, subject: str, course_num: str):
        with self.conn:
            self.conn.execute(
                "INSERT OR IGNORE INTO completed_courses (year_term, subject, course_number) VALUES (?, ?, ?)",
                (year_term, subject, course_num),
            )

    def is_term_completed(self, year_term: str) -> bool:
        cur = self.conn.cursor()
        cur.execute("SELECT 1 FROM completed_terms WHERE year_term = ?", (year_term,))
        return cur.fetchone() is not None

    def mark_term_completed(self, year_term: str):
        with self.conn:
            self.conn.execute(
                "INSERT OR IGNORE INTO completed_terms (year_term) VALUES (?)",
                (year_term,),
            )

    def save_section(self, year_term: str, subject: str, course_num: str, crn: str, data: dict):
        with self.conn:
            self.conn.execute(
                "INSERT OR REPLACE INTO scraped_sections (year_term, subject, course_number, crn, data_json) VALUES (?, ?, ?, ?, ?)",
                (year_term, subject, course_num, crn, json.dumps(data)),
            )

    def get_all_scraped_sections(self, year_term: Optional[str] = None) -> List[dict]:
        cur = self.conn.cursor()
        if year_term:
            cur.execute("SELECT data_json FROM scraped_sections WHERE year_term = ? ORDER BY year_term, subject, course_number, crn", (year_term,))
        else:
            cur.execute("SELECT data_json FROM scraped_sections ORDER BY year_term, subject, course_number, crn")
        return [json.loads(row[0]) for row in cur.fetchall()]


def season_to_code(season: str) -> str:
    s = season.lower()
    if s == "spring":
        return "sp"
    elif s == "summer":
        return "su"
    elif s == "fall":
        return "fa"
    elif s == "winter":
        return "wi"
    return s[:2]


def discover_available_terms(fetcher: RateLimitedFetcher) -> List[Tuple[int, str, str]]:
    """Fetches schedule.xml and returns [(year, season, year_term)] sorted newest to oldest."""
    root_xml = fetcher.fetch(f"{BASE_URL}.xml")
    if not root_xml:
        print("[!] Failed to fetch root schedule.xml", file=sys.stderr)
        return []

    root = parse_xml_clean(root_xml)
    years = []
    for cal_year in root.findall(".//calendarYear"):
        y_str = cal_year.get("id") or (cal_year.text.strip() if cal_year.text else "")
        if y_str.isdigit():
            years.append(int(y_str))

    years.sort(reverse=True)
    discovered_terms = []

    for year in years:
        year_xml = fetcher.fetch(f"{BASE_URL}/{year}.xml")
        if not year_xml:
            continue
        try:
            y_root = parse_xml_clean(year_xml)
            for term_el in y_root.findall(".//term"):
                href = term_el.get("href", "")
                match = re.search(r"/(\d{4})/([a-zA-Z]+)\.xml", href)
                if match:
                    y = int(match.group(1))
                    season = match.group(2).lower()
                    yt = f"{y}-{season_to_code(season)}"
                    discovered_terms.append((y, season, yt))
        except Exception as e:
            print(f"[!] Error parsing year {year}: {e}", file=sys.stderr)

    return sorted(discovered_terms, key=lambda term: (term[0], SEASON_ORDER.get(term[1], 0)), reverse=True)


def parse_section_xml(xml_text: str) -> dict:
    """Parses single section XML into structured dictionary."""
    root = parse_xml_clean(xml_text)

    def text_or_none(tag: str) -> Optional[str]:
        el = root.find(tag)
        if el is not None and el.text:
            t = el.text.strip()
            return t if t else None
        return None

    section_num = text_or_none("sectionNumber")
    status_code = text_or_none("statusCode")
    part_of_term = text_or_none("partOfTerm")
    start_date = text_or_none("startDate")
    end_date = text_or_none("endDate")

    notes_parts = []
    for tag in ("sectionText", "sectionNotes", "sectionCappArea"):
        val = text_or_none(tag)
        if val:
            notes_parts.append(val)
    notes = "\n\n".join(notes_parts) if notes_parts else None

    meetings = []
    for idx, meet_el in enumerate(root.findall(".//meeting")):
        def meet_text(t: str) -> Optional[str]:
            el = meet_el.find(t)
            return el.text.strip() if el is not None and el.text and el.text.strip() else None

        type_el = meet_el.find("type")
        type_code = type_el.get("code") if type_el is not None else None
        type_desc = type_el.text.strip() if type_el is not None and type_el.text else None

        instructors = []
        for instr_el in meet_el.findall(".//instructor"):
            if instr_el.text and instr_el.text.strip():
                instructors.append(instr_el.text.strip())

        meetings.append({
            "meeting_index": idx,
            "start_time": meet_text("start"),
            "end_time": meet_text("end"),
            "days_of_week": meet_text("daysOfTheWeek"),
            "room_number": meet_text("roomNumber"),
            "building_name": meet_text("buildingName"),
            "type_code": type_code,
            "type_description": type_desc,
            "instructors": instructors,
        })

    return {
        "section_number": section_num,
        "status_code": status_code,
        "part_of_term": part_of_term,
        "start_date": start_date,
        "end_date": end_date,
        "notes": notes,
        "meetings": meetings,
    }


def scrape_term(
    year: int,
    season: str,
    year_term: str,
    fetcher: RateLimitedFetcher,
    checkpoint: CheckpointManager,
    on_section_scraped=None,
) -> int:
    """Scrapes all subjects, courses, and sections for one term."""
    if checkpoint.is_term_completed(year_term):
        print(f"[*] Term {year_term} already completed in checkpoint. Skipping.")
        return 0

    print(f"\n========================================================")
    print(f"[*] Starting scrape for {season.capitalize()} {year} ({year_term})")
    print(f"========================================================")

    term_xml = fetcher.fetch(f"{BASE_URL}/{year}/{season}.xml")
    if not term_xml:
        print(f"[!] Could not fetch term XML for {year_term}. Skipping.")
        return 0

    term_root = parse_xml_clean(term_xml)
    subject_codes = [
        el.get("id") for el in term_root.findall(".//subject") if el.get("id")
    ]
    print(f"[*] Found {len(subject_codes)} subjects in {year_term}.")

    total_sections_scraped = 0
    term_complete = True

    for s_idx, subj in enumerate(subject_codes, 1):
        subj_xml = fetcher.fetch(f"{BASE_URL}/{year}/{season}/{subj}.xml")
        if not subj_xml:
            continue

        subj_root = parse_xml_clean(subj_xml)
        course_els = subj_root.findall(".//course")

        for c_el in course_els:
            course_num = c_el.get("id")
            course_title = c_el.text.strip() if c_el.text else ""
            if not course_num:
                continue

            if checkpoint.is_course_completed(year_term, subj, course_num):
                continue

            course_xml = fetcher.fetch(f"{BASE_URL}/{year}/{season}/{subj}/{course_num}.xml")
            if not course_xml:
                checkpoint.mark_course_completed(year_term, subj, course_num)
                continue

            c_root = parse_xml_clean(course_xml)
            section_els = c_root.findall(".//section")

            course_complete = True
            for sec_el in section_els:
                crn = sec_el.get("id")
                if not crn:
                    continue

                sec_xml = fetcher.fetch(f"{BASE_URL}/{year}/{season}/{subj}/{course_num}/{crn}.xml")
                if not sec_xml:
                    continue

                try:
                    parsed = parse_section_xml(sec_xml)
                    record = {
                        "year": year,
                        "season": season.capitalize(),
                        "year_term": year_term,
                        "subject": subj,
                        "course_number": course_num,
                        "course_title": course_title,
                        "crn": crn,
                        **parsed,
                    }
                    checkpoint.save_section(year_term, subj, course_num, crn, record)
                    total_sections_scraped += 1

                    if on_section_scraped:
                        on_section_scraped(record)

                except Exception as ex:
                    course_complete = False
                    term_complete = False
                    print(f"\n[!] Error parsing {subj} {course_num} CRN {crn}: {ex}", file=sys.stderr)

            if course_complete:
                checkpoint.mark_course_completed(year_term, subj, course_num)

        sys.stdout.write(f"\r  -> Progress: {s_idx}/{len(subject_codes)} subjects processed ({subj}) | {total_sections_scraped} sections saved")
        sys.stdout.flush()

    if term_complete:
        checkpoint.mark_term_completed(year_term)
    status = "Completed" if term_complete else "Incomplete (parsing failures; rerun to retry)"
    print(f"\n[*] {status} term {year_term}. Total new sections: {total_sections_scraped}")
    return total_sections_scraped


# ----------------------------------------------------------------------
# CSV EXPORT & IMPORT UTILITIES
# ----------------------------------------------------------------------

CSV_HEADERS = [
    "year",
    "season",
    "year_term",
    "subject",
    "course_number",
    "course_title",
    "crn",
    "section_number",
    "status_code",
    "part_of_term",
    "start_date",
    "end_date",
    "meeting_index",
    "start_time",
    "end_time",
    "days_of_week",
    "room_number",
    "building_name",
    "type_code",
    "type_description",
    "instructors",
    "notes",
]


def export_sections_to_csv(sections: List[dict], output_csv_path: str):
    """Exports structured section records into a flat tabular CSV file."""
    os.makedirs(os.path.dirname(os.path.abspath(output_csv_path)), exist_ok=True)
    row_count = 0

    with open(output_csv_path, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(CSV_HEADERS)

        for sec in sections:
            meetings = sec.get("meetings", [])
            if not meetings:
                writer.writerow([
                    sec.get("year", ""),
                    sec.get("season", ""),
                    sec.get("year_term", ""),
                    sec.get("subject", ""),
                    sec.get("course_number", ""),
                    sec.get("course_title", ""),
                    sec.get("crn", ""),
                    sec.get("section_number", ""),
                    sec.get("status_code", ""),
                    sec.get("part_of_term", ""),
                    sec.get("start_date", ""),
                    sec.get("end_date", ""),
                    0,
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    sec.get("notes", "") or "",
                ])
                row_count += 1
            else:
                for m in meetings:
                    instr_str = "; ".join(m.get("instructors", []))
                    writer.writerow([
                        sec.get("year", ""),
                        sec.get("season", ""),
                        sec.get("year_term", ""),
                        sec.get("subject", ""),
                        sec.get("course_number", ""),
                        sec.get("course_title", ""),
                        sec.get("crn", ""),
                        sec.get("section_number", ""),
                        sec.get("status_code", ""),
                        sec.get("part_of_term", ""),
                        sec.get("start_date", ""),
                        sec.get("end_date", ""),
                        m.get("meeting_index", 0),
                        m.get("start_time", "") or "",
                        m.get("end_time", "") or "",
                        m.get("days_of_week", "") or "",
                        m.get("room_number", "") or "",
                        m.get("building_name", "") or "",
                        m.get("type_code", "") or "",
                        m.get("type_description", "") or "",
                        instr_str,
                        sec.get("notes", "") or "",
                    ])
                    row_count += 1

    print(f"[✓] Exported {len(sections)} sections ({row_count} rows) to CSV: {output_csv_path}")


def export_sections_to_json(sections: List[dict], output_json_path: str):
    """Exports sections into structured JSON."""
    os.makedirs(os.path.dirname(os.path.abspath(output_json_path)), exist_ok=True)
    with open(output_json_path, "w", encoding="utf-8") as f:
        json.dump(sections, f, indent=2, ensure_ascii=False)
    print(f"[✓] Exported {len(sections)} sections to JSON: {output_json_path}")


def import_csv_to_postgres(csv_path: str, db_url: str):
    """
    Imports exported sections CSV directly into Kingfisher PostgreSQL database tables:
    terms, subjects, courses, course_offerings, scheduled_sections,
    scheduled_section_meetings, instructors, scheduled_section_meeting_instructors.
    """
    if not os.path.exists(csv_path):
        print(f"[!] CSV file not found: {csv_path}", file=sys.stderr)
        return False

    print(f"[*] Importing CSV ({csv_path}) into PostgreSQL...")

    sections_by_crn: Dict[Tuple[str, str, str, str], dict] = {}

    with open(csv_path, "r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            key = (row["year_term"], row["subject"], row["course_number"], row["crn"])
            if key not in sections_by_crn:
                sections_by_crn[key] = {
                    "year": int(row["year"]),
                    "season": row["season"],
                    "year_term": row["year_term"],
                    "subject": row["subject"],
                    "course_number": row["course_number"],
                    "course_title": row["course_title"],
                    "crn": row["crn"],
                    "section_number": row["section_number"],
                    "status_code": row["status_code"],
                    "part_of_term": row["part_of_term"],
                    "start_date": row["start_date"] or None,
                    "end_date": row["end_date"] or None,
                    "notes": row["notes"] or None,
                    "meetings": [],
                }

            meeting_fields = ("start_time", "end_time", "days_of_week", "room_number",
                              "building_name", "type_code", "type_description", "instructors")
            if any(row.get(field, "").strip() for field in meeting_fields):
                instructors = [i.strip() for i in row["instructors"].split(";") if i.strip()]
                sections_by_crn[key]["meetings"].append({
                    "meeting_index": int(row.get("meeting_index") or len(sections_by_crn[key]["meetings"])),
                    "start_time": row["start_time"] or None,
                    "end_time": row["end_time"] or None,
                    "days_of_week": row["days_of_week"] or None,
                    "room_number": row["room_number"] or None,
                    "building_name": row["building_name"] or None,
                    "type_code": row["type_code"] or None,
                    "type_description": row["type_description"] or None,
                    "instructors": instructors,
                })

    print(f"[*] Parsed {len(sections_by_crn)} unique scheduled sections from CSV.")

    sql_file = csv_path + ".import.sql"
    generate_import_sql(sections_by_crn.values(), sql_file)
    print(f"[*] Executing SQL batch import via Docker psql...")
    cmd = ["docker", "exec", "-i", "kingfisher-api-db-1", "psql", "--set=ON_ERROR_STOP=1", "-U", "postgres", "-d", "illini_grades"]
    try:
        with open(sql_file, "r", encoding="utf-8") as sf:
            res = subprocess.run(cmd, stdin=sf, capture_output=True, text=True)
        if res.returncode == 0:
            print(f"[✓] Successfully imported {len(sections_by_crn)} scheduled sections into PostgreSQL!")
            return True
        else:
            print(f"[!] psql error: {res.stderr}", file=sys.stderr)
            return False
    except Exception as ex:
        print(f"[!] Import execution failed: {ex}. SQL script saved at {sql_file}", file=sys.stderr)
        return False


def sql_literal(value) -> str:
    """Quote PostgreSQL text/date values with standard_conforming_strings enabled."""
    return "NULL" if value is None else "'" + str(value).replace("'", "''") + "'"


def generate_import_sql(sections, sql_output_path: str):
    """Upsert sections and replace their meeting snapshots in one transaction.

    Meeting deletion cascades to instructor links, but shared instructor records
    and sections not present in the import are retained, as in the live writer.
    """
    with open(sql_output_path, "w", encoding="utf-8") as f:
        f.write("BEGIN;\nSET LOCAL standard_conforming_strings = on;\n\n")
        for s in sections:
            year = int(s["year"])
            number = int(s["course_number"])
            season = sql_literal(s["season"])
            term = sql_literal(s["year_term"])
            subject = sql_literal(s["subject"])
            title = sql_literal(s.get("course_title") or "")
            crn = sql_literal(s["crn"])
            section_number = sql_literal(s.get("section_number") or None)
            status = sql_literal(s.get("status_code") or None)
            part = sql_literal(s.get("part_of_term") or None)
            start = sql_literal(s.get("start_date") or None)
            end = sql_literal(s.get("end_date") or None)
            notes = sql_literal(s.get("notes") or None)
            # Title distinguishes special-topics courses sharing a subject/number.
            course_id = f"(SELECT c.id FROM courses c JOIN subjects sub ON sub.id = c.subject_id WHERE sub.code = {subject} AND c.number = {number} AND c.title = {title})"
            offering_id = f"(SELECT co.id FROM course_offerings co JOIN terms t ON t.id = co.term_id WHERE co.course_id = {course_id} AND t.year_term = {term})"
            section_id = f"(SELECT ss.id FROM scheduled_sections ss WHERE ss.course_offering_id = {offering_id} AND ss.crn = {crn})"
            f.write(f"""
INSERT INTO terms (year, season, year_term) VALUES ({year}, {season}, {term}) ON CONFLICT (year_term) DO NOTHING;
INSERT INTO subjects (code) VALUES ({subject}) ON CONFLICT (code) DO NOTHING;
INSERT INTO courses (subject_id, number, title) VALUES ((SELECT id FROM subjects WHERE code = {subject}), {number}, {title}) ON CONFLICT DO NOTHING;
INSERT INTO course_offerings (course_id, term_id) VALUES ({course_id}, (SELECT id FROM terms WHERE year_term = {term})) ON CONFLICT (course_id, term_id) DO NOTHING;

INSERT INTO scheduled_sections (course_offering_id, crn, section_number, status_code, part_of_term, start_date, end_date, notes)
VALUES ({offering_id}, {crn}, {section_number}, {status}, {part}, {start}, {end}, {notes})
ON CONFLICT (course_offering_id, crn) DO UPDATE SET
    section_number = EXCLUDED.section_number, status_code = EXCLUDED.status_code, part_of_term = EXCLUDED.part_of_term,
    start_date = EXCLUDED.start_date, end_date = EXCLUDED.end_date, notes = EXCLUDED.notes;
DELETE FROM scheduled_section_meetings WHERE scheduled_section_id = {section_id};
""")
            seen_indices = set()
            for position, meeting in enumerate(s.get("meetings") or []):
                index = int(meeting.get("meeting_index", position))
                if not 0 <= index <= 32767 or index in seen_indices:
                    raise ValueError(f"Invalid or duplicate meeting_index {index} for {s['year_term']} CRN {s['crn']}")
                seen_indices.add(index)
                fields = ("start_time", "end_time", "days_of_week", "room_number",
                          "building_name", "type_code", "type_description")
                values = ", ".join(sql_literal(meeting.get(field) or None) for field in fields)
                f.write(f"""
INSERT INTO scheduled_section_meetings (scheduled_section_id, meeting_index, {', '.join(fields)})
VALUES ({section_id}, {index}, {values});
""")
                # A co-taught meeting can repeat a name in source data; link it once.
                names = dict.fromkeys(name.strip() for name in meeting.get("instructors", []) if name and name.strip())
                for name in names:
                    instructor = sql_literal(name)
                    f.write(f"""
INSERT INTO instructors (name) VALUES ({instructor}) ON CONFLICT (name) DO NOTHING;
INSERT INTO scheduled_section_meeting_instructors (meeting_id, instructor_id)
SELECT m.id, i.id FROM scheduled_section_meetings m CROSS JOIN instructors i
WHERE m.scheduled_section_id = {section_id} AND m.meeting_index = {index} AND i.name = {instructor}
ON CONFLICT (meeting_id, instructor_id) DO NOTHING;
""")
        f.write("\nCOMMIT;\n")


# ----------------------------------------------------------------------
# CLI MAIN
# ----------------------------------------------------------------------

def main():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    api_dir = os.path.dirname(script_dir)
    default_data_dir = os.path.join(api_dir, "data", "course_explorer")
    default_checkpoint_db = os.path.join(api_dir, "data", "scraper_checkpoint.db")

    parser = argparse.ArgumentParser(
        description="Scrapes historical UIUC Course Explorer data (2004-present) and exports/imports CSV/JSON/Postgres.",
        formatter_class=argparse.RawTextHelpFormatter,
    )

    mode_group = parser.add_argument_group("Scraping Modes")
    mode_group.add_argument("--all", action="store_true", help="Scrape all available historical terms from Course Explorer (2004 to present).")
    mode_group.add_argument("--start-year", type=int, help="Starting year to scrape (e.g. 2018).")
    mode_group.add_argument("--end-year", type=int, help="Ending year to scrape (e.g. 2024).")
    mode_group.add_argument("--terms", type=str, help="Comma-separated list of terms (e.g. 2023-fa,2024-sp).")
    mode_group.add_argument("--term", type=str, help="Single term (e.g. 2024-fa).")
    mode_group.add_argument("--discover-terms", action="store_true", help="List all available terms from schedule.xml and exit.")

    data_group = parser.add_argument_group("Data Export & Import")
    data_group.add_argument("--export-csv", type=str, help="Path to write exported CSV file.")
    data_group.add_argument("--export-json", type=str, help="Path to write exported JSON file.")
    data_group.add_argument("--import-csv", type=str, help="Path to CSV file to import directly into PostgreSQL.")
    data_group.add_argument("--db-url", type=str, default=os.getenv("DATABASE_URL", "postgresql://postgres:postgres@localhost:5432/illini_grades"), help="PostgreSQL connection URL.")

    config_group = parser.add_argument_group("Configuration")
    config_group.add_argument("--delay", type=float, default=DEFAULT_DELAY, help=f"Delay between requests in seconds (default: {DEFAULT_DELAY}).")
    config_group.add_argument("--jitter", type=float, default=0.5, help="Maximum extra random delay in seconds (default: 0.5).")
    config_group.add_argument("--user-agent", default=os.getenv("COURSE_EXPLORER_USER_AGENT", USER_AGENT), help="HTTP User-Agent (or COURSE_EXPLORER_USER_AGENT).")
    config_group.add_argument("--checkpoint-db", type=str, default=default_checkpoint_db, help="Path to SQLite checkpoint database.")
    config_group.add_argument("--output-dir", type=str, default=default_data_dir, help="Directory for term export files.")

    args = parser.parse_args()

    # Mode 1: Import CSV directly into database
    if args.import_csv:
        success = import_csv_to_postgres(args.import_csv, args.db_url)
        sys.exit(0 if success else 1)

    try:
        fetcher = RateLimitedFetcher(delay=args.delay, jitter=args.jitter, user_agent=args.user_agent)
    except ValueError as exc:
        parser.error(str(exc))

    # Mode 2: Discover terms
    if args.discover_terms:
        print("[*] Querying Course Explorer schedule.xml...")
        terms = discover_available_terms(fetcher)
        print(f"\nDiscovered {len(terms)} available terms:")
        for y, season, yt in terms:
            print(f"  - {yt} ({season.capitalize()} {y})")
        sys.exit(0)

    checkpoint = CheckpointManager(args.checkpoint_db)

    # Determine terms to scrape
    target_terms: List[Tuple[int, str, str]] = []

    if args.term:
        parts = args.term.lower().split("-")
        y = int(parts[0])
        s_code = parts[1]
        s_map = {"sp": "spring", "su": "summer", "fa": "fall", "wi": "winter"}
        season = s_map.get(s_code, "fall")
        target_terms = [(y, season, f"{y}-{season_to_code(season)}")]

    elif args.terms:
        for t_spec in args.terms.split(","):
            t_clean = t_spec.strip().lower()
            parts = t_clean.split("-")
            y = int(parts[0])
            s_map = {"sp": "spring", "su": "summer", "fa": "fall", "wi": "winter"}
            season = s_map.get(parts[1], "fall")
            target_terms.append((y, season, f"{y}-{season_to_code(season)}"))

    elif args.all or args.start_year or args.end_year:
        print("[*] Discovering available terms from UIUC Course Explorer...")
        all_terms = discover_available_terms(fetcher)
        for y, season, yt in all_terms:
            if args.start_year and y < args.start_year:
                continue
            if args.end_year and y > args.end_year:
                continue
            target_terms.append((y, season, yt))

    if not target_terms and not (args.export_csv or args.export_json):
        parser.print_help()
        sys.exit(1)

    # Always prioritize recent semesters, including explicit --terms lists.
    target_terms.sort(key=lambda term: (term[0], SEASON_ORDER.get(term[1], 0)), reverse=True)

    # Perform scraping
    if target_terms:
        print(f"[*] Target terms to scrape ({len(target_terms)} total): {[t[2] for t in target_terms]}")
        for year, season, yt in target_terms:
            scrape_term(year, season, yt, fetcher, checkpoint)

    # Perform Exports if requested
    if args.export_csv or args.export_json:
        print("\n[*] Loading scraped sections from checkpoint database...")
        has_term_selection = bool(args.term or args.terms or args.all
                                  or args.start_year is not None or args.end_year is not None)
        if has_term_selection:
            # An explicitly selected but empty range must export no rows, not history.
            selected_terms = sorted({yt for _, _, yt in target_terms})
            all_sections = [section for yt in selected_terms
                            for section in checkpoint.get_all_scraped_sections(yt)]
        else:
            all_sections = checkpoint.get_all_scraped_sections()
        print(f"[*] Loaded {len(all_sections)} total sections.")

        if args.export_csv:
            export_sections_to_csv(all_sections, args.export_csv)
        if args.export_json:
            export_sections_to_json(all_sections, args.export_json)


if __name__ == "__main__":
    main()
