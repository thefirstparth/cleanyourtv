# Parents' TV: Redmi TV 43" (MiTV-MOOQ3), Android TV 10

Firmware `QTG3.200305.006.3261`. Set up 26 Nov 2026 on branch `parents-redmi-tv`.

## What is on it

| Part | Setting | Where |
|---|---|---|
| Debloat | 39 packages disabled (never uninstalled), incl. PatchWall, Xiaomi TV+, analytics, the stock Google TV launcher and Xiaomi's update service | `my-tv/debloat.txt`; undo with `tools/restore.sh my-tv/debloat.txt` |
| Home | This repo's launcher, with a weather corner (house-of-1400 `/api/live/wx`: Prayagraj, Bengaluru, Gurugram) | `launcher/…/WeatherPanel.java`, `config.xml` `weather_url`, `weather_cities` |
| Power-on | `power_on_home` + HomeKeyService (`HOME_KEY_SERVICE=1` at install), `key_filter` false so held volume still repeats. The Live TV app's own boot receiver otherwise opens the empty tuner ("no channels") at every power-on | `config.xml` |
| Stock launcher insurance | `stock_home_redirect` = `com.google.android.tvlauncher`: if a Play update ever turns it back on, the helper sends the TV back to this Home | `config.xml` |
| Look | aurora wallpaper, sky 60, 12-hour clock, 5 columns, no dock title, no tile names, icon-only Wi-Fi and Settings | `config.xml` (baked in) and `my-tv/theme` (pushed) |
| Dock and grid order | YouTube, Netflix, Prime Video, JioHotstar, SonyLIV; then Spotify, FanCode, Media Player, Play Store, Mi Cast, AirScreen, AirCast | `dock_apps` in both |
| Screensavers | Home: clock after 10 idle minutes. System (inside apps): the launcher's ClockDream | `settings put secure screensaver_components com.gathin.tvhome/.ClockDream` |

## Weather: how it behaves

- Asks when Home appears (not within 2 min of the last ask), every 15 min while Home stays up, every minute after
  a failure (plus two quick retries 8 s apart), and again when the network comes back.
- Keeps the last good copy in a file. Over 30 min old it says "Updated h:mm"; over 6 hours old it hides.
- Any error hides the corner; it never takes Home down.
- To change cities: edit `weather_cities` (feed ids `blr`, `pyj`, `rnc`, `ggn`) and rebuild. Do not edit
  house-of-1400's `config/house.json`: the other TV (tv-vibehack) reads it.

## Rebuild and install (needs debugging on, same Wi-Fi)

```bash
HOME_KEY_SERVICE=1 TV_HOST=<tv ip> launcher/build-and-install.sh
TV_HOST=<tv ip> tools/theme.sh my-tv/theme
TV_HOST=<tv ip> tools/venv/bin/python -I tools/check.py --fix my-tv/debloat.txt
```

A new INTERNET permission only takes effect after a reboot (`EPERM` until then).

## Firmware update (rarely needed)

`pm enable com.xiaomi.mitv.updateservice`, update from Settings, then `tools/disable.sh my-tv/debloat.txt`.
