"""tools/race_calendar_feed.py writes the hosted file the launcher refreshes, without touching the API."""
import contextlib
import datetime
import io
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock

from tools import race_calendar_feed


RESPONSE = json.dumps({"MRData": {"RaceTable": {"season": "2026", "Races": [{
    "round": "1", "raceName": "Example Grand Prix", "date": "2026-01-04", "time": "14:00:00Z",
}]}}}).encode("utf-8")
EMPTY = json.dumps({"MRData": {"RaceTable": {"season": "2026", "Races": []}}}).encode("utf-8")


class FixedDatetime(datetime.datetime):
    @classmethod
    def now(cls, tz=None):
        return cls(2026, 1, 1, tzinfo=tz)


class FeedTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.out = Path(self.tmp.name) / "data" / "f1.json"
        self.stdout = io.StringIO()
        self.stderr = io.StringIO()

    def stub(self, body=RESPONSE):
        opener = mock.patch.object(race_calendar_feed.urllib.request, "urlopen",
                                   side_effect=lambda *args, **kwargs: io.BytesIO(body))
        self.urlopen = opener.start()
        self.addCleanup(opener.stop)

    def run_main(self, *argv):
        clock = SimpleNamespace(datetime=FixedDatetime, timedelta=datetime.timedelta,
                                timezone=datetime.timezone)
        with mock.patch.object(sys, "argv", ["race_calendar_feed.py", *argv]), \
                mock.patch.object(race_calendar_feed, "datetime", clock), \
                contextlib.redirect_stdout(self.stdout), \
                contextlib.redirect_stderr(self.stderr):
            return race_calendar_feed.main()

    def test_next_is_the_year_after_the_clock(self):
        now = FixedDatetime(2026, 1, 1, tzinfo=datetime.timezone.utc)
        self.assertEqual(2027, race_calendar_feed.season_year("next", now))
        self.assertEqual([2026, 2027], race_calendar_feed.season_years(["current", "next,2026"], now))

    def test_writes_the_wrapper_the_launcher_reads(self):
        self.stub()
        status = self.run_main("--out", str(self.out), "--season", "2026")

        self.assertEqual(0, status, self.stderr.getvalue())
        hosted = json.loads(self.out.read_text(encoding="utf-8"))
        self.assertEqual([2026], hosted["seasons"])
        self.assertEqual("Example Grand Prix", hosted["race_calendar"][0]["name"])
        self.assertEqual(2026, hosted["race_calendar"][0]["season"])

    def test_a_second_run_leaves_an_identical_file_alone(self):
        self.stub()
        self.assertEqual(0, self.run_main("--out", str(self.out), "--season", "2026"))
        first = self.out.read_bytes()
        first_mtime = self.out.stat().st_mtime_ns

        self.assertEqual(0, self.run_main("--out", str(self.out), "--season", "2026"))
        self.assertEqual(first, self.out.read_bytes())
        self.assertEqual(first_mtime, self.out.stat().st_mtime_ns)

    def test_an_empty_calendar_never_replaces_a_good_file(self):
        self.out.parent.mkdir(parents=True)
        original = '{"race_calendar": [{"name": "old"}]}\n'
        self.out.write_text(original, encoding="utf-8")
        self.stub(EMPTY)

        self.assertEqual(0, self.run_main("--out", str(self.out), "--season", "2026"))
        self.assertEqual(original, self.out.read_text(encoding="utf-8"))
        self.assertIn("left unchanged", self.stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
