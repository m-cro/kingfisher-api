"""Import regression tests. Set TEST_POSTGRES_DSN for PostgreSQL integration tests."""
import copy
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch, Mock
import uuid

spec = importlib.util.spec_from_file_location("scraper", Path(__file__).parents[1] / "scrape_course_explorer.py")
scraper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scraper)


def section(**changes):
    data = {
        "year": 2027, "season": "spring", "year_term": "2027-sp", "subject": "CS",
        "course_number": "498", "course_title": "Architecture's Topics", "crn": "12345",
        "section_number": "A", "status_code": "A", "part_of_term": "1",
        "start_date": "2027-01-19", "end_date": "2027-05-05", "notes": "Bring O'Neil's book",
        "meetings": [
            {"meeting_index": 0, "start_time": "09:00 AM", "end_time": "09:50 AM",
             "days_of_week": "MWF", "room_number": "101", "building_name": "O'Neil Hall",
             "type_code": "LEC", "type_description": "Lecture",
             "instructors": [" O'Neil, N ", "Nishil Talati", "Nishil Talati", " "]},
            {"meeting_index": 3, "days_of_week": "T", "type_code": "LAB",
             "type_description": "Laboratory", "instructors": ["Nishil Talati"]},
        ],
    }
    data.update(changes)
    return data


class CsvMeetingTest(unittest.TestCase):
    def test_preserves_sparse_meetings_and_skips_section_only_rows(self):
        for field in ("start_time", "end_time", "days_of_week", "room_number", "building_name",
                      "type_code", "type_description", "instructors"):
            with self.subTest(field=field), tempfile.TemporaryDirectory() as directory:
                csv_path = str(Path(directory) / "sections.csv")
                meeting = {"meeting_index": 3, field: ["Talati, N"] if field == "instructors" else "TBA"}
                scraper.export_sections_to_csv([section(meetings=[meeting]), section(crn="54321", meetings=[])], csv_path)
                captured = []
                with patch.object(scraper, "generate_import_sql", side_effect=lambda records, path: captured.extend(records)), \
                        patch.object(scraper.subprocess, "run", return_value=Mock(returncode=0)) as run:
                    # The import opens the generated SQL before invoking psql.
                    Path(csv_path + ".import.sql").touch()
                    self.assertTrue(scraper.import_csv_to_postgres(csv_path, "unused"))
                self.assertEqual(len(captured[0]["meetings"]), 1)
                self.assertEqual(captured[0]["meetings"][0]["meeting_index"], 3)
                self.assertEqual(captured[0]["meetings"][0][field], meeting[field])
                self.assertEqual(captured[1]["meetings"], [])
                self.assertIn("--set=ON_ERROR_STOP=1", run.call_args.args[0])

    def test_duplicate_meeting_indices_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "duplicate meeting_index"):
                scraper.generate_import_sql([section(meetings=[{"meeting_index": 0}, {"meeting_index": 0}])],
                                            str(Path(directory) / "import.sql"))


@unittest.skipUnless(os.getenv("TEST_POSTGRES_DSN") and shutil.which("psql"), "Set TEST_POSTGRES_DSN to test against PostgreSQL")
class ImportSqlPostgresTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.sql_path = str(Path(self.directory.name) / "import.sql")
        self.schema = "import_test_" + uuid.uuid4().hex
        self.execute(f"CREATE SCHEMA {self.schema};")
        self.addCleanup(lambda: self.execute(f"DROP SCHEMA {self.schema} CASCADE;"))
        migrations = Path(__file__).parents[2] / "src/main/resources/db/migration"
        # Match the real schema, omitting only the application database-level setting.
        initial = (migrations / "V1__initial_schema.sql").read_text().split("ALTER DATABASE")[0]
        self.execute(initial + (migrations / "V3__separate_special_topics.sql").read_text()
                     + (migrations / "V5__add_scheduled_sections.sql").read_text())

    def execute(self, sql, check=True):
        result = subprocess.run(["psql", os.environ["TEST_POSTGRES_DSN"], "-X", "-qAt", "--set=ON_ERROR_STOP=1"],
                                input=f"SET search_path TO {self.schema}, public;\n" + sql,
                                text=True, capture_output=True)
        if check:
            self.assertEqual(result.returncode, 0, result.stderr)
        return result

    def import_sections(self, sections, check=True):
        scraper.generate_import_sql(sections, self.sql_path)
        return self.execute(Path(self.sql_path).read_text(), check)

    def snapshot(self):
        # Ignore regenerated meeting IDs, retaining all business data and associations.
        result = self.execute("""
SELECT json_build_object(
 'sections', (SELECT json_agg(row_to_json(s) ORDER BY s.id) FROM scheduled_sections s),
 'meetings', (SELECT json_agg(row_to_json(data) ORDER BY scheduled_section_id, meeting_index) FROM (
   SELECT scheduled_section_id, meeting_index, start_time, end_time, days_of_week,
          room_number, building_name, type_code, type_description,
          ARRAY(SELECT i.name FROM scheduled_section_meeting_instructors mi JOIN instructors i ON i.id = mi.instructor_id WHERE mi.meeting_id = m.id ORDER BY i.name) AS instructors
   FROM scheduled_section_meetings m) data),
 'instructors', (SELECT json_agg(name ORDER BY name) FROM instructors));
""")
        return json.loads(result.stdout)

    def test_meetings_instructors_reimports_updates_and_removal(self):
        first = section()
        other = section(year=2026, season="fall", year_term="2026-fa", course_title="Different topic",
                        meetings=[{"meeting_index": 0, "room_number": "Other", "instructors": ["Nishil Talati"]}])
        empty = section(crn="99999", meetings=[])
        self.import_sections([first, other, empty])
        original = self.snapshot()
        self.assertEqual(len(original["sections"]), 3)
        self.assertEqual(len(original["meetings"]), 3)
        self.assertEqual(original["instructors"], ["Nishil Talati", "O'Neil, N"])
        meeting = original["meetings"][0]
        for field in ("start_time", "end_time", "days_of_week", "room_number", "building_name", "type_code", "type_description"):
            self.assertEqual(meeting[field], first["meetings"][0][field])
        self.assertEqual(meeting["instructors"], ["Nishil Talati", "O'Neil, N"])
        self.assertIsNone(original["meetings"][1]["start_time"])
        self.assertEqual(self.execute("SELECT count(*) FROM courses;").stdout.strip(), "2")
        self.import_sections([first, other, empty])
        self.assertEqual(self.snapshot(), original)
        updated = copy.deepcopy(first)
        updated["notes"] = "Updated"
        updated["meetings"] = [{"meeting_index": 0, "room_number": "202", "instructors": ["O'Neil, N"]}]
        self.import_sections([updated])
        changed = self.snapshot()
        self.assertEqual(len(changed["meetings"]), 2)
        self.assertEqual(changed["meetings"][0]["room_number"], "202")
        self.assertEqual(changed["meetings"][0]["instructors"], ["O'Neil, N"])
        self.assertEqual(changed["meetings"][1], original["meetings"][2])
        self.import_sections([section(meetings=[])])
        final = self.snapshot()
        self.assertEqual(final["meetings"], [original["meetings"][2]])
        self.assertEqual(final["instructors"], original["instructors"])

    def test_sql_error_rolls_back_the_entire_import(self):
        self.import_sections([section()])
        before = self.snapshot()
        broken = section(notes="Must be rolled back", meetings=[{"meeting_index": 0, "start_time": "X" * 11}])
        result = self.import_sections([broken], check=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.snapshot(), before)


if __name__ == "__main__":
    unittest.main()
