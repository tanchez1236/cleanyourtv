#!/usr/bin/env python3
"""Write a standalone F1 calendar to host, for the launcher's race_calendar_url.

    race_calendar_feed.py --out docs/data/f1.json --season current --season next

A theme points at the file with:

    "race_calendar_url": "https://raw.githubusercontent.com/<user>/<repo>/main/docs/data/f1.json"

The launcher reads it at startup, caches the last good copy and refreshes it every six hours. Using
`--season current --season next` lets next year's calendar appear as soon as Jolpica publishes it, so the
panel keeps working across New Year. Races that are already over are left out.

The data comes from Jolpica (https://jolpi.ca), the free community successor to the Ergast API: no key,
no account. Run it from a scheduled job (the repository's race-calendar workflow does this daily) or by
hand when F1 moves a session.

Exit status: 0 the file is current (written, or already identical), 2 the run could not fetch or read its
arguments. A file is never replaced by a broken or empty calendar.
"""
import argparse
import datetime
import json
from pathlib import Path
import sys
import urllib.error
import urllib.request

try:
    from . import race_calendar
except ImportError:  # running as `python3 tools/race_calendar_feed.py`
    import race_calendar

API = race_calendar.API
FIRST_SEASON = race_calendar.FIRST_SEASON


class FeedError(Exception):
    """A season could not be fetched or read; the message is for stderr."""


def season_year(value, now):
    """`current`, `next`, or a championship year from Jolpica."""
    value = value.strip().lower()
    if value == "current":
        return now.year
    if value == "next":
        return now.year + 1
    race_calendar.season(value)   # raises ArgumentTypeError with the shared wording
    return int(value)


def season_years(values, now):
    """Repeatable/comma-separated values, deduplicated and in the order given."""
    out = []
    for value in values:
        for part in value.split(","):
            if not part.strip():
                raise ValueError("an empty season")
            year = season_year(part, now)
            if year not in out:
                out.append(year)
    return out


def fetch_table(year):
    endpoint = API.format(season=year)
    request = urllib.request.Request(endpoint, headers={"User-Agent": "cleanyourtv"})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            payload = json.load(response)
    except urllib.error.HTTPError as exc:
        raise FeedError(f"Jolpica answered {exc.code} {exc.reason} for {endpoint}") from exc
    except (urllib.error.URLError, TimeoutError) as exc:
        raise FeedError(f"could not reach Jolpica at {endpoint} ({getattr(exc, 'reason', exc)})") from exc
    except ValueError as exc:
        raise FeedError(f"Jolpica answered something that is not JSON for {endpoint} ({exc})") from exc
    try:
        return payload["MRData"]["RaceTable"]
    except (KeyError, TypeError) as exc:
        raise FeedError(f"the answer for {year} has no MRData.RaceTable in it") from exc


def races_for(table, now):
    """The launcher's calendar entries, with the season kept for a readable hosted file."""
    try:
        races = race_calendar.calendar(table["Races"], now)
    except ValueError as exc:
        raise FeedError(f"a session time in the {table['season']} calendar is not a timestamp this "
                        f"script understands ({exc})") from exc
    for race in races:
        race["season"] = int(table["season"])
    return races


def read_existing(path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def main():
    parser = argparse.ArgumentParser(description="Write a hosted F1 calendar for race_calendar_url.")
    parser.add_argument("--out", required=True, metavar="FILE", help="JSON file to write")
    parser.add_argument("--season", action="append",
                        help="current, next, or a year; repeat or comma-separate (default: current)")
    args = parser.parse_args()

    now = datetime.datetime.now(datetime.timezone.utc)
    try:
        years = season_years(args.season or ["current"], now)
    except (ValueError, argparse.ArgumentTypeError) as exc:
        print(f"--season: {exc}. Nothing was written.", file=sys.stderr)
        return 2

    races, used = [], []
    for year in years:
        try:
            table = fetch_table(year)
            found = races_for(table, now)
        except FeedError as exc:
            # Next year's calendar is often not published yet; keep what the other seasons gave us.
            print(f"season {year}: {exc}", file=sys.stderr)
            continue
        if found:
            races.extend(found)
            used.append(int(table["season"]))

    target = Path(args.out)
    if not races:
        print(f"no upcoming races in {', '.join(str(year) for year in years)}; "
              f"{target} was left unchanged", file=sys.stderr)
        return 0

    # Current season first, then next year: the launcher only looks at start times, but a stable file
    # reads better in a diff and in the GitHub UI.
    races.sort(key=lambda race: (race["season"], race["sessions"][0]["start"]))
    previous = read_existing(target)
    if previous and previous.get("seasons") == used and previous.get("race_calendar") == races:
        print(f"calendar unchanged -> {target}")
        return 0

    hosted = {
        "updated": now.replace(microsecond=0).isoformat().replace("+00:00", "Z"),
        "seasons": used,
        "race_calendar": races,
    }
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(hosted, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    first = races[0]
    print(f"{len(races)} races from {', '.join(str(year) for year in used)} -> {target}")
    print(f"next: round {first['round']} {first['name']}, race {first['sessions'][-1]['start']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
