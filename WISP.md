# Wisp

Wisp is the phone version of Lumen, built from the same source as a separate app
(`com.sofabrowse.wisp`, its own icon and name, installs next to Lumen). It's light (32-bit ARM
only, no Google Play services) and private by default.

## What's different from Lumen

| | Wisp |
|---|---|
| Interface | One slim bar at the **top**: site info (shield or site icon), address (the site name; tap for the full address), tab counter, ⋮ menu. It hides while you scroll down and comes back when you scroll up. No remote cursor, no TV toolbar rows. |
| ⋮ menu | A bottom sheet like Fennec's: back, forward, share, reload; bookmark page, find in page, desktop site and ad blocker (On/Off pills), More (private mode, pop-ups, media links, make a GIF, add to home screen); tiles for history, bookmarks, downloads, passwords; settings; quit. Colours follow the wallpaper (Material You) on Android 12+. |
| + button | Next to the address: a new tab with the keyboard open. |
| GIF maker | WebM/WebP downloads ask "Save as GIF?" (width, frame rate, length); ⋮ → More → Make a GIF converts a file on the phone. Saved to Download/Wisp. |
| Site info | The shield/site icon: secure or not, ad blocking / third-party cookies / desktop site for this site, delete its cookies, storage or all data. |
| Tabs | The tab counter opens a two-column grid of tab cards with page previews: tap to switch, ✕ to close, + New tab, close all. |
| Phone extras | Find in page, pull to refresh (only when the page really reaches its top), text size, add to home screen, hold a link or image for its menu. Back on the start page leaves the app. |
| Private by default | Every fresh start opens in private mode (Settings → Privacy can turn this off). Switching to normal in the menu lasts until Wisp is closed. |
| Settings | Its own phone settings: General, Privacy and security, Ad blocking, Site content, Downloads, VPN, Advanced, About. No TV settings (cursor, remote shortcuts, deband, TV video hand-off, keep screen on, joystick). |
| No Google | No Play services, no Safe Browsing, DuckDuckGo by default, WebView usage statistics off. |
| HTTPS first | `http://` links open over `https://`; if the site has no working https version, Wisp opens the http address. |
| Third-party cookies | Blocked, except on sites with a human-verification widget and sites you allow (site info or Settings). |
| Videos | Always play in the page (no TV hand-offs); stream links (.m3u8/.mpd) still open in Wisp's player. |
| Permissions | No "all files access", no "see all apps", no LeanKeyboard token sharing. Websites never get the microphone. |
| Kept from Lumen | adblock-rust ad blocker, pop-up blocker, player, media links, downloads (multi-part, resume), passwords, desktop site, WireGuard, clear on exit, updates from GitHub. |

## Building and updates

`./gradlew assembleWispGeckoExcludedRelease`. Its build number is `wispVersionCode` at the top of
`app/build.gradle.kts` (raise it for every release you want Wisp to update to).

The workflow in `build-from-zip.yml` builds both apps from the zip and publishes
`Lumen-<n>.apk` (tag `lumen-v…`) and `Wisp-<n>.apk` (tag `wisp-v…`) as separate releases. Each app
installs only its own releases (Settings → Version). The repository is private, so Wisp needs the
same read-only access token once (Settings → Version → Update source).
