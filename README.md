# Roomtape

## The complete Roomtape project

| Part | Where | What it does |
|---|---|---|
| Roomtape web app | `web/roomtape.html`, live at https://claude.ai/artifact/KbKC1icdGbXWY8rZ8df4xr | Measure rooms (photo or typed), floors and positions, floor plans, 3D view with drag-and-drop furniture, real furniture prices via Parallel Search, auto-furnish within a budget and shop limit, import of scans |
| Roomtape Scanner (Android) | `app/` | Scans rooms with the camera (ARCore) and saves a scan file for the web app |
| Android app download | https://github.com/frederikdejonckheere2-cyber/roomtape-scanner/releases/download/latest/roomtape-scanner.apk | Built automatically by GitHub Actions on every change |
| Sample scan | `samples/sample-scan.json` | Try the import in the web app |

The web app only works fully when opened through the claude.ai link (account saving, price lookup and auto-furnish use Claude). The file in `web/` is the source.

## Roomtape Scanner for Android

Measures your home with the phone camera (Google ARCore) and sends the rooms to Roomtape.

## How it works

1. Tap **Scan rooms**, name the room and pick the floor.
2. Aim the cross at each floor corner and tap **Add corner**. Go round the room. The wall lengths appear live on screen.
3. Tap **Room done**, aim the cross at the line where a wall meets the ceiling and tap **Set height** (or **Skip** for 2.50 m).
4. The app asks if there is another room. Walk to it **without closing the app**: the phone keeps tracking, so it knows where the next room is. Going up or down the stairs is detected from the height difference.
5. When you are done, tap **Save scan file** (or **Copy scan for Roomtape**). In Roomtape, open **Scan → Import a scan from the Android app**.

Needs a phone on Google's ARCore list: https://developers.google.com/ar/devices
Google Play Services for AR is installed automatically the first time.

## Getting the app on your phone

The repository builds the APK automatically with GitHub Actions (`.github/workflows/build.yml`):

1. Put this folder in a GitHub repository (push to the `main` branch).
2. Open the repository's **Actions** tab, open the latest **Build APK** run and download **roomtape-scanner-apk**.
3. Unzip it, copy `app-debug.apk` to your phone and open it. Android asks to allow installing from this source once.

Or open the folder in Android Studio and press Run with your phone connected.

## Tips for accurate scans

- Good light helps. Move slowly and keep the floor in view at the start until the cross turns yellow.
- Corners hidden behind furniture: aim at the floor just in front of the corner.
- If a room ends up slightly off, drag it into place on Roomtape's floor plan.

## Scan file format

```json
{ "format": "roomtape-scan", "version": 1,
  "rooms": [ { "name": "Kitchen", "level": 0, "height": 2.6, "floorY": -1.42,
               "session": "a1b2c3d4", "corners": [[0.12, -3.4], [3.5, -3.38], [3.52, 0.71], [0.1, 0.7]] } ] }
```

Corners are floor points in metres (x, z) in the AR world of that walk (`session`).
