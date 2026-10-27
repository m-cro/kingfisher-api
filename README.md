# Kingfisher API

A REST interface for UIUC course data powered by Spring Boot. This README is for setting up your own Kingfisher API and is not designed to help you navigate the website. Inspired by Madgrades.
[Web UI](https://kf.rthak.com/)
[Web UI Repo](https://github.com/m-cro/kingfisher-web)

## Setup

By default, the provided example .env should work, but should be changed.
```bash
cp .env.example .env
```

Run with docker compose:
```bash
docker compose up -d --build
```

OR run separately (requires Java 25 and the included Gradle wrapper):
```bash
docker compose up -d db
./gradlew bootRun
```

## Update DB
Once running, upload a relevant UIUC grades CSV (`uiuc-gpa-dataset.csv`) through the `/v1/admin/ingest` end-point. Supply the API Key located in your `.env`:

```bash
curl -X POST http://localhost:5902/v1/admin/ingest \
  -H "Authorization: Bearer your-api-key-here" \
  -F "file=@uiuc-gpa-dataset.csv"
```

## Instructor filtering

`GET /v1/courses?instructor=Talati%2C%20N` matches “Talati, N” and “Nishil Talati”
using an exact surname and compatible given names or initials, in either name
order. Full names also match their abbreviated form. The instructor search API
(`GET /v1/instructors?query=Talati%2C%20N`) uses the same rules. One-word partial
names continue to use fuzzy search. These are name matches, not merged identities.

Instructor SQL regression tests can be run against PostgreSQL with `pg_trgm`
installed using `TEST_DATABASE_URL=jdbc:postgresql://localhost/test_db ./gradlew test`.
The tests use temporary fixture tables that disappear when the connection closes.

## Live section schedules
`/v1/admin/ingest-sections` pulls live section schedules (CRN, meeting times/rooms, instructors) for a given term directly from the public UIUC Course Explorer, rather than from a CSV. Runs in the background (it's a long walk of the Course Explorer's own subject/course/section catalog with a deliberate delay between requests, so it respects their rate limits) — the response comes back immediately, check server logs for progress:

```bash
curl -X POST "http://localhost:5902/v1/admin/ingest-sections?year=2026&season=fall" \
  -H "Authorization: Bearer your-api-key-here"
```

Safe to re-trigger for the same term at any point (e.g. periodically during
registration) — it upserts by CRN and prunes sections that disappeared from the
schedule. Only one ingestion runs at a time; a second call while one is in progress
returns an error instead of running concurrently. Seat/enrollment counts aren't
included — the public Course Explorer API doesn't expose those, only schedule data.

Read the ingested data back via:
```
GET /v1/courses/{id}/scheduled-sections?term=2026-fa
```

## Historical Course Explorer Scraper & CSV Import/Export
For one-time bulk archival or populating past semesters (2004–present), use the scraper suite in `scripts/`:

```bash
# Discover all available terms on Course Explorer (2004-present)
./scripts/run_historical_scrape.sh discover

# Scrape all historical terms to CSV & JSON with resumable SQLite checkpointing
./scripts/run_historical_scrape.sh scrape-all

# Scrape a specific semester (e.g. 2024-fa)
./scripts/run_historical_scrape.sh scrape-term 2024-fa

# Scrape a year range (e.g. 2018 to 2025)
./scripts/run_historical_scrape.sh scrape-range 2018 2025

# Export scraped sections from SQLite checkpoint to CSV
./scripts/run_historical_scrape.sh export-csv data/course_explorer/all_sections.csv

# Import previously scraped CSV directly into PostgreSQL without network calls
./scripts/run_historical_scrape.sh import-csv data/course_explorer/all_historical_sections.csv
```

Exports combined with `--term`, `--terms`, `--start-year`/`--end-year`, or `--all`
include only the selected terms, including when scraping skips a completed term.
`run_historical_scrape.sh scrape-term 2027-sp` therefore writes only Spring 2027.
Export commands without a term selection still export the entire checkpoint.
CSV and JSON use the same scope.

CSV imports include meeting times, days, rooms, buildings, meeting types, and
all meeting instructors. Re-importing replaces each included section's meetings
and instructor links with the CSV snapshot, including clearing meetings for a
section with no meeting details. Shared instructors and sections absent from the
CSV are retained. All changes run in one transaction; SQL errors stop the import
and roll back the batch. Special-topics courses are resolved by subject, number,
and title.

To backfill meeting details for sections imported with an older script, run the
same `import-csv` command again. Import SQL integration tests run with
`TEST_POSTGRES_DSN=postgresql://localhost/test_db python3 -m unittest discover -s scripts/tests`.
They use isolated schemas in the test database.

## Course Explorer request settings

Both clients use a stable browser-style User-Agent, XML Accept headers, and an
English language preference. Override the identity with
`COURSE_EXPLORER_USER_AGENT` (in `.env` for Docker Compose, or exported in your
shell for Python and local Java runs). A browser-style header alone does not
guarantee requests will avoid detection or blocking.

Requests wait at least 750 ms, with up to 500 ms of additional random delay.
The historical script also exposes these settings directly:

```bash
python3 scripts/scrape_course_explorer.py --term 2024-fa \
  --delay 1.0 --jitter 0.5 --user-agent "your user agent"
```

The historical scraper honors `Retry-After` on retryable responses and stops
when retries are exhausted, preserving completed checkpoints for the next run.
A 404 is treated as a missing resource. Other request failures are never marked
complete. Requests keep TLS certificate verification enabled; if your system
trust store is missing certificates, fix that before running the scraper.

## Docs
[https://kingfisherapi.rthak.com/swagger-ui/index.html](https://kingfisherapi.rthak.com/swagger-ui/index.html)

TO DO:
- Automate messages to professors to request copies of past syllabi
