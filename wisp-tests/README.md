# Wisp user test

`run.py` uses Wisp like a person would, through adb. It taps, types, turns the screen,
downloads, signs in, quits and so on. It writes a report with a screenshot after every step.
It has 173 steps in 19 suites (`python run.py --list` shows them all).

It runs in two places:
- on GitHub, after every Wisp build: three emulators, see `build-wisp.yml`
- on your own computer, against an emulator or a real phone/tablet

## On your own computer (Windows, Mac or Linux)

1. Install **Python 3** (python.org; on Windows tick "Add to PATH").
2. Install **Android Studio**. In Device Manager, create a virtual device, for example
   Pixel 6 with Android 14 (Google APIs). Or connect a phone with USB debugging on.
3. Make `adb` reachable. It is in Android Studio's SDK folder, under `platform-tools`.
   Either add that folder to PATH, or pass it with `--adb`.
4. Optional, for smaller screenshots and the QR test: `pip install pillow qrcode`.
5. Unzip `wisp-source.zip`. Download `Wisp-<n>.apk` from your GitHub release into
   `sofabrowse/wisp-tests`.
6. In that folder, run:

```
python run.py --apk Wisp-19.apk                  # the emulator that is running
python run.py --apk Wisp-19.apk --avd Pixel_6    # starts that emulator first
python run.py --only core,address,tabs           # some suites only
python run.py --no-real-sites                    # only the local test site
```

The full run takes about 1–2 hours. When it finishes it makes `results.zip`: attach that to
the chat. The `results/index.html` page shows every step with its screenshots, and you can
open it in any browser.

**Real phone or tablet:** the test changes Wisp's settings. It also adds bookmarks, saved
passwords and a test VPN, and its Quit steps delete logins. Make a Wisp backup first
(Settings → Security & backup), then add `--allow-real-device`. Emulators are reset
before each run; a real device isn't.

## How it works

- `wisp/server.py` is the test website. It runs on your computer, and the device reaches it
  through `adb reverse` as `lvh.me:8000`. `lvh.me` and `localtest.me` are public names that
  point to 127.0.0.1, so subdomains (`www.lvh.me`, `app.lvh.me`) and a second site work
  without any setup. It serves logins, cookies, downloads, pop-ups, dialogs, WebRTC,
  permissions and so on.
- `wisp/lib.py`: adb, the screen layout (uiautomator), steps and the report.
  `wisp/app.py`: the address bar, menu, sheets, settings and tabs.
- `suites/*.py`: the tests. Each step is a function with `@step('name')`.
- Results:
  - `report.md`: problems first, then every step
  - `index.html`
  - `shots/`: a picture plus the screen layout `.xml` per step
  - `logcat-wisp.txt`, `crashes.txt`, `monkey-*.txt`, `server-log.txt`
  - `video/`

A step is PASS, FAIL (a check didn't hold), ERROR (the test itself broke), CRASH (Wisp crashed
or froze) or SKIP (not for this device). The "real" suite opens YouTube, Cloudflare sites and
others. Those sites change, so treat its results as information.
