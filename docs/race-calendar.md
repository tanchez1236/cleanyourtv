# Race-weekend panel with automatic updates

The launcher shows a small F1 panel under the clock from 24 hours before a weekend's first session
until 4 hours after the race starts. It shows the next session with a countdown (or the live one) and
the weekend's session times in the TV's time zone. A short red line follows the live or next session
across the row. When no race is near, it takes no space.

The panel is part of the header, above the app grid, so it never overlaps app labels or the dock.

> This fork adds the `INTERNET` permission to read a hosted calendar. The upstream launcher has no
> network access; that is why this change lives in a fork and not in a pull request to the original
> repository.

## Static calendar (no network)

`tools/race_calendar.py <theme folder>` writes `race_calendar` into that theme's `config.json`. Push the
theme as usual; the TV reads the file once and never uses the network:

```sh
tools/race_calendar.py themes/aurora
tools/theme.sh themes/aurora
```

## Automatic updates

Point the theme at a hosted JSON file instead:

```json
{
  "race_calendar_url": "https://raw.githubusercontent.com/tanchez1236/cleanyourtv/main/docs/data/f1.json"
}
```

The launcher downloads it at startup, keeps the last good copy in its cache (so a TV that boots before
Wi-Fi still has a calendar), and refreshes at most once every six hours while Home stays open. If the
download and the cache are both unavailable, the `race_calendar` embedded in the theme is the fallback.

The file may be a bare array of races or an object with a `race_calendar` key. This fork's copy is
written by `.github/workflows/race-calendar.yml` every day from [Jolpica](https://jolpi.ca), the free
community successor to the Ergast API, and includes the current season plus next year's as soon as it is
published. Races that are already over are left out.

To publish the file somewhere else, write it once and upload it:

```sh
tools/race_calendar_feed.py --out f1.json --season current --season next
```

Use a URL that serves `application/json` (GitHub raw does). A scheduled job, a cron entry or a CI step
can rerun that command daily.

## Theme keys

| Key | Meaning |
|---|---|
| `race_calendar` | Embedded list of races, used when `race_calendar_url` is unset or unreachable |
| `race_calendar_url` | Optional remote JSON file, read at startup and refreshed every six hours |

Each race has `round`, `name`, `place` and `sessions`; each session has a `name` (`FP1`, `FP2`, `FP3`,
`SPRINT QUALI`, `SPRINT`, `QUALIFYING`, `RACE`) and a UTC `start` such as `2026-11-01T20:00:00Z`.
