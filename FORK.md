# This is a fork

This repository is a fork of **[Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv)**,
maintained at **[tanchez1236/cleanyourtv](https://github.com/tanchez1236/cleanyourtv)** for one TV:
a Hisense HiSmartTV A4 on Android 9.

The launcher is the same plain-Java launcher as upstream, with the changes below. Everything else
(debloat lists, tools, themes, docs) tracks the original project.

## Changes in this fork

| Change | Where |
|---|---|
| **Live F1 calendar.** A theme can set `race_calendar_url`; Home downloads it at startup, caches the last good copy, refreshes every 6 hours and falls back to the theme's embedded calendar. | `RaceCalendarLoader.java`, `Config.java`, `MainActivity.java` |
| **Daily calendar publisher.** `tools/race_calendar_feed.py` writes a hosted JSON with the current season and next year as soon as Jolpica publishes it. `.github/workflows/race-calendar.yml` runs it every day and commits only when the races changed. | `tools/race_calendar_feed.py`, `.github/workflows/race-calendar.yml`, `docs/data/f1.json` |
| **Weather widget.** Above the date: the TV's last known place, the current temperature in °C and a monochrome icon for sun, night, partly cloudy, cloud, rain, storm, snow or fog. It uses Android's last known location, BigDataCloud for the Spanish place name and Open-Meteo for the reading; no API key. It refreshes every 30 minutes and keeps a cached reading. | `WeatherLoader.java`, `MainActivity.java`, `res/drawable/ic_weather_*` |
| **Race panel in the header flow.** The F1 panel now sits in the header column, so it pushes the grid down instead of rendering behind app labels. | `res/layout*/activity_main.xml` |
| **Smaller date text** (12sp instead of 14sp). | `res/layout*/activity_main.xml` |
| **Five-app dock.** `themes/aurora` pins Netflix, Prime Video, YouTube, Disney+ and HBO Max and turns `fill_dock` off, so the dock never grows to six. The five tiles spread across the free slot instead of leaving a blank space at the end. | `themes/aurora/config.json`, `AppAdapter.java` |

The launcher therefore declares `android.permission.INTERNET`. It also uses `ACCESS_FINE_LOCATION`,
which upstream already declared for the Wi-Fi name; `launcher/build-and-install.sh` grants it.

## Why there is no pull request to the original repository

Upstream's [`AGENTS.md`](https://github.com/Gathin23/cleanyourtv/blob/main/AGENTS.md) has a hard rule:

> **No network in the launcher:** no `INTERNET` permission, no network features, no analytics or
> telemetry, no background services or polling…

and routes changes with *new permissions or network access* to **your own repository**, plus a row in
`SHOWCASE.md`. It also asks contributors not to edit `.github/workflows/`, which the daily calendar
publisher needs. A pull request with the live calendar and weather widget would therefore be rejected,
which is why these changes stay in this fork.

The non-network part (moving the race panel into the header flow and the smaller date) could be
proposed upstream later; it is kept together here because this TV uses the whole set.

## Using this fork

```sh
# Build and install on the TV
TV_HOST=192.168.1.204 launcher/build-and-install.sh

# Push the theme with the five-app dock and the calendar URL
TV_HOST=192.168.1.204 tools/theme.sh themes/aurora
```

The hosted calendar is read from:

```
https://raw.githubusercontent.com/tanchez1236/cleanyourtv/main/docs/data/f1.json
```

It is refreshed by the GitHub Actions workflow every day at 05:17 UTC; a manual run is available from
the Actions tab. The TV itself refreshes the downloaded calendar at startup and every six hours.

## Keeping up with upstream

```sh
git fetch origin
git merge origin/main
```

The fork-only files are listed above; conflicts are most likely in `Config.java`, `MainActivity.java`
and the two `activity_main.xml` layouts.
