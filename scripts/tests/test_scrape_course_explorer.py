"""Offline regression tests for request behavior and resumable failures."""
import csv
import json
import importlib.util
from pathlib import Path
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from email.utils import format_datetime
from unittest.mock import MagicMock, patch
from urllib.error import HTTPError

spec = importlib.util.spec_from_file_location("scraper", Path(__file__).parents[1] / "scrape_course_explorer.py")
scraper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scraper)
URL = "https://example.test/schedule.xml"


def response():
    result = MagicMock()
    result.__enter__.return_value.read.return_value = b"<schedule/>"
    return result


class FetcherTest(unittest.TestCase):
    def test_headers_and_minimum_spacing(self):
        fetcher = scraper.RateLimitedFetcher(delay=1, jitter=0.5, user_agent="custom-agent")
        with patch.object(scraper.urllib.request, "urlopen", return_value=response()) as open_url, \
                patch.object(scraper.time, "monotonic", side_effect=[0, 0.2, 1.5]), \
                patch.object(scraper.random, "uniform", return_value=0.5), \
                patch.object(scraper.time, "sleep") as sleep:
            self.assertEqual(fetcher.fetch(URL), "<schedule/>")
            fetcher.fetch(URL)
        sleep.assert_called_once_with(1.3)
        request = open_url.call_args.args[0]
        self.assertEqual(request.get_header("User-agent"), "custom-agent")
        self.assertIn("application/xml", request.get_header("Accept"))
        self.assertEqual(request.get_header("Accept-language"), "en-US,en;q=0.9")

    def test_retry_after_and_stable_identity(self):
        error = HTTPError(URL, 429, "limited", {"Retry-After": "30"}, None)
        fetcher = scraper.RateLimitedFetcher(delay=0, jitter=0)
        with patch.object(scraper.urllib.request, "urlopen", side_effect=[error, response()]) as open_url, \
                patch.object(scraper.time, "sleep") as sleep:
            fetcher.fetch(URL)
        self.assertEqual(sleep.call_args_list[0].args, (30.0,))
        self.assertIs(open_url.call_args_list[0].args[0], open_url.call_args_list[1].args[0])

    def test_retry_after_formats(self):
        future = format_datetime(datetime.now(timezone.utc) + timedelta(seconds=60), usegmt=True)
        self.assertGreater(scraper.RateLimitedFetcher.retry_after(future), 58)
        for value in [None, "garbage", "-1", "Wed, 21 Oct 2015 07:28:00 GMT"]:
            self.assertEqual(scraper.RateLimitedFetcher.retry_after(value), 0)

    def test_404_is_missing_but_exhaustion_raises(self):
        fetcher = scraper.RateLimitedFetcher(delay=0, jitter=0)
        with patch.object(scraper.urllib.request, "urlopen", side_effect=HTTPError(URL, 404, "missing", {}, None)):
            self.assertIsNone(fetcher.fetch(URL))
        with patch.object(scraper.urllib.request, "urlopen", side_effect=HTTPError(URL, 503, "down", {}, None)) as open_url, \
                patch.object(scraper.time, "sleep"):
            with self.assertRaises(scraper.FetchError):
                fetcher.fetch(URL)
        self.assertEqual(open_url.call_count, scraper.MAX_RETRIES)

    def test_invalid_configuration(self):
        for config in [{"delay": -1}, {"jitter": float("nan")}, {"delay": float("inf")},
                       {"user_agent": ""}, {"user_agent": "test\r\nInjected: header"}]:
            with self.subTest(config=config), self.assertRaises(ValueError):
                scraper.RateLimitedFetcher(**config)

    def test_failed_course_remains_resumable(self):
        fetcher = MagicMock()
        fetcher.fetch.side_effect = [
            '<term><subject id="CS"/></term>',
            '<subject><course id="101">Intro</course></subject>',
            scraper.FetchError("rate limited"),
        ]
        with tempfile.TemporaryDirectory() as directory:
            checkpoint = scraper.CheckpointManager(str(Path(directory) / "checkpoint.db"))
            try:
                with self.assertRaises(scraper.FetchError):
                    scraper.scrape_term(2024, "fall", "2024-fa", fetcher, checkpoint)
                self.assertFalse(checkpoint.is_course_completed("2024-fa", "CS", "101"))
                self.assertFalse(checkpoint.is_term_completed("2024-fa"))
            finally:
                checkpoint.conn.close()


class ExportScopeTest(unittest.TestCase):
    def test_exports_follow_term_selection(self):
        cases = [
            (["--term", "2027-sp"], {"2027-sp"}, 2),
            (["--terms", "2027-sp,2027-sp,2026-fa"], {"2026-fa", "2027-sp"}, 3),
            (["--start-year", "2027", "--end-year", "2027"], {"2027-sp"}, 2),
            (["--start-year", "2030"], set(), 0),
            ([], {"2025-fa", "2026-fa", "2027-sp"}, 4),
        ]
        for flags, expected_terms, expected_rows in cases:
            with self.subTest(flags=flags), tempfile.TemporaryDirectory() as directory:
                checkpoint = scraper.CheckpointManager(str(Path(directory) / "checkpoint.db"))
                csv_path = str(Path(directory) / "sections.csv")
                json_path = str(Path(directory) / "sections.json")
                terms = [(2025, "fall", "2025-fa"), (2026, "fall", "2026-fa"), (2027, "spring", "2027-sp")]
                try:
                    for year, season, year_term in terms:
                        checkpoint.save_section(year_term, "CS", "101", "12345", {
                            "year": year, "season": season, "year_term": year_term,
                            "subject": "CS", "course_number": "101", "crn": "12345",
                            "meetings": [{"meeting_index": 0}, {"meeting_index": 1}] if year == 2027 else [],
                        })
                        checkpoint.mark_term_completed(year_term)
                    argv = ["scraper", *flags, "--export-csv", csv_path, "--export-json", json_path]
                    with patch.object(scraper.sys, "argv", argv), \
                            patch.object(scraper, "CheckpointManager", return_value=checkpoint), \
                            patch.object(scraper, "RateLimitedFetcher") as fetcher, \
                            patch.object(scraper, "discover_available_terms", return_value=terms):
                        scraper.main()
                        fetcher.return_value.fetch.assert_not_called()
                    with open(csv_path, newline="", encoding="utf-8") as stream:
                        rows = list(csv.DictReader(stream))
                    self.assertEqual(len(rows), expected_rows)
                    self.assertEqual({row["year_term"] for row in rows}, expected_terms)
                    with open(json_path, encoding="utf-8") as stream:
                        sections = json.load(stream)
                    self.assertEqual({section["year_term"] for section in sections}, expected_terms)
                    self.assertEqual(len(sections), len(expected_terms))
                finally:
                    checkpoint.conn.close()


if __name__ == "__main__":
    unittest.main()
