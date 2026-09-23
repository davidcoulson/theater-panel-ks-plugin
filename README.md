# Theater Panel — Kiosk Satellite plugin

Puts the theater panel's look on the kiosk's own ESPHome device in Home Assistant, so it can be
read and set like anything else in the house:

| Entity | What it is |
|---|---|
| `select` **Theme** | Classic (chocolate & copper) or Sofa (slate tweed) |
| `select` **Holiday accent** | Auto, None, Halloween, Thanksgiving, Christmas, New Year, Valentine's, Birthday, Winter, Spring, Summer, Fall |
| `select` **Accent weather** | Off, 25%, 50%, 75%, 100% — how much falls, drifts and flaps over the lobby |
| `text_sensor` **Active accent** | What the calendar (or the setting) has picked right now |

The plugin talks to the panel's `/api/look`: it polls it so a change made on the panel's admin
page shows up here, and a Home Assistant command is posted straight back. The panel saves through
the same path as its admin page, so every open panel follows at once.

## Settings

- **Panel URL** — where the panel is served, e.g. `https://ht-kiosk.coulson.io`.
- **Panel key** — `PANEL_KEY` from the panel's admin page (Overview → Panel link). Leave empty if the
  kiosk is on one of the panel's trusted networks.
- **Check every** — polling interval, 5–120 s.

## Build

Needs a JDK and an Android SDK with build-tools and a platform (`ANDROID_HOME`).

```bash
python3 tools/build.py
```

produces `dist/theater-panel-<version>.zip` and its SHA-256. Install on the kiosk with
**Plugin Manager → Developer Tools → Install from ZIP**, or publish a GitHub release with the
manifest, ZIP and checksum (see `.github/workflows/build.yml`) and add the repository URL in
Plugin Manager for updates.

Built on the [Kiosk Satellite plugin SDK](https://github.com/jxlarrea/kiosk-satellite-plugin-hello-world).
