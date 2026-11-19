# Lumen

Lumen is a modified version of [TV Bro](https://github.com/truefedex/tv-bro) by Fedir Tsapana,
built for personal use. As TV Bro's license requires, it uses a different name, icon and
application ID (`com.sofabrowse.tv.foss`), and its About screen credits TV Bro.

## What's new compared to TV Bro

All extras can be switched on or off in **Settings → Lumen extras**. They work with the default
WebView engine.

| Feature | What it does |
|---|---|
| Error page | When a site can't be reached you get an explanation and a Try again button (instead of a blank page), with tips: turn on the VPN, or set Android Private DNS to dns.google. |
| Stronger ad blocker | Loads EasyList, EasyPrivacy, uBlock filters and Peter Lowe's list together (you can add more, one URL per line) and also hides leftover ad boxes, sticky ad bars and "disable your ad blocker" overlays. |
| YouTube ads | YouTube ads come from YouTube's own servers, so filter lists can't block them. Lumen removes the ad schedule from the player data before the player reads it; if an ad still starts it's muted and skipped to the end, banner/feed ads are hidden and the "ad blockers are not allowed" pop-up is closed. YouTube changes often, so this can need updates. |
| Pop-up / pop-under blocker | Stops new tabs that open without a click or from invisible full-page overlays. Clicks on real links and buttons always work; if something is blocked, a "Pop-up blocked · Open" bar lets you open it anyway. Sign-in pop-ups (Google, Apple, Microsoft, Facebook, PayPal…) still work. |
| Always highest quality | Picks the top resolution on YouTube and on sites using JW Player, Video.js, hls.js, dash.js, Shaka Player and Plyr. For HLS and DASH streams Lumen also trims the stream's quality list to the best version this device can decode before the site's player reads it, so players that are built into the site (and would otherwise start low and never climb to a 10+ Mbit/s version) play the top quality from the start. The site's quality menu then only shows that one version; switch this option off in Lumen extras to get the full menu back. |
| Force dark mode | Turns light pages dark. Photos and videos keep their colors. Pages that are already dark are left alone. |
| Custom script | Your own JavaScript, run after each page loads. |
| Cloudflare compatibility | Allows the third-party cookies that verification widgets need, removes the "WebView" marker from the browser identity and never ad-blocks challenge resources. |
| TV home screen | Bigger tiles, a bigger search box and a clear orange highlight on the selected tile. |
| Desktop site | The monitor button in the menu bar switches the current site between its desktop and mobile versions. The choice is remembered per site (subdomains included) and the button turns orange while it's on. WebView engine only. |
| Built-in stream player | Links to `.m3u8` / `.mpd` streams (clicked, typed, bookmarked or pasted in Download from link) and streams a page fails to play open in Lumen's built-in player. It's Media3/ExoPlayer, the engine used by VLC-style Android players: hardware video decoding (4K, HDR10, HLG, Dolby Vision as the device supports), a large buffer for high-bitrate streams, optional refresh-rate matching and tunneled playback, and an **Anime deband** button (top-left, appears with the controls), off by default and only for the video you switch it on for. It also removes interlacing lines on movement, then debands; it's tuned for anime: smooths banding in skies, gradients and flat colours while dark line art stays crisp. Runs on the GPU, turns itself off if the device drops frames, and isn't available for HDR/Dolby Vision. Normal videos keep playing in the page with the site's own controls. |
| Always-visible top bar | Home button (the Lumen icon) first, then the address/search bar, buttons next to it, tabs and page buttons (back, forward, reload, ad block, pop-ups, desktop site, grab, deband) on the second row. The address updates when a site changes it without loading a new page (e.g. when you open a video on YouTube-style sites). On TV, move the pointer to the top edge and press **Up**: the pointer continues over the bar (buttons, tabs and their ✕) like a mouse and goes back to the page when moved below it. **Menu** moves the remote's focus into the bar instead; on a tab, Right reaches its ✕. The ✕ at the end closes the browser. |
| Auto-hiding top bar | The bar slides smoothly; the page isn't re-laid out while it moves. On websites the bar hides about 2 seconds after the page has loaded and whenever you scroll down, so the page uses the whole screen. Scroll back to the very top (or pull down at the top, or move the cursor to the top edge and press **Up**) and it comes back; **Menu** always shows it. It stays visible on the home page. Settings → Lumen extras → Auto-hide the top bar switches it off. |
| Scrolling inside menus and boxes | Long submenus, drop-down lists, side panels, chat windows and sideways carousels can be scrolled with the remote: when the cursor is over such a box a thin teal outline appears; push the cursor against the box's top or bottom (or left/right) edge and the box scrolls instead of the page. When it reaches the end, the cursor moves on as usual. Also works inside frames from the same site. |
| Anime deband (web videos) | The gradient button in the second toolbar row opens two choices: **Play in Lumen player with anime deband** (recommended on TV: hands the video, at the same position, to Lumen's hardware player with the filter on from the first frame) or **Deband this page's video**, which shows "Anime deband is running on this video (W×H)" once the filter really runs. The in-page option turns on anime clean-up for the video on the current page: it first removes interlacing lines (the fine horizontal brightness lines and red/green colour stripes that interlaced anime TV/DVD masters show whenever something moves, also when a 480/576-line master was scaled up to 720p/1080p - the line spacing is worked out from the video height; only where they appear) and then debands with mpv's deband filter, the same one Hayase uses (via ThaUnknown's MIT-licensed video-deband), also in full screen; it stays on until the page reloads. Works for normal and streamed (MSE) videos; DRM-protected videos and some videos from other sites can't be read by the browser and are left unchanged (you get a message). |
| Paste a video address | A video file or stream address (.mp4, .mkv, .webm, .mov, .m3u8, .mpd, …) typed or pasted into the address bar or the home page search box plays in Lumen's built-in hardware player instead of the web page player. Other addresses typed into the home page search box open directly instead of being searched for. |
| No more crashes from heavy pages | If a web page's process runs out of memory (very high-bitrate videos can do that), the tab shows "This page stopped working" with a Reload button instead of the whole app closing. If Lumen ever does close unexpectedly, the next start shows the error with a **Copy details** button. |
| Automatic deband | Deband button → **Always use deband on this site** switches the anime clean-up on by itself on that site, in the page and in Lumen's player (Settings → Lumen extras → Sites with deband always on). With **Detect interlacing lines automatically** (on by default), Lumen checks playing videos every 1.5 s and switches the clean-up on when it finds the horizontal lines of an interlaced master. |
| Player for TV | Left/Right without the controls skip 10 s back/forward. Videos continue where you stopped (a **Start over** button appears). Subtitle files the page loaded are offered in the player (Subtitles button in the controls; device language or English first); the **Subtitles: size** button changes the text size, and Settings can put them on a dark box. Leaving the player while a video plays switches to picture-in-picture where the device supports it. On a tablet: double-tap left/right to skip, swipe up/down on the right for volume and on the left for brightness. |
| Light on memory and storage | Only the shown tab and the one before it keep their page in memory (others reload when opened), no tab screenshots, smaller icon cache, ARM-only native libraries and unused resources removed from the APK. |
| Updates | Lumen can download and install new builds from your GitHub Releases itself (see "Updates from inside Lumen" below). |
| Grab media links | The link button in the second toolbar row shows a small number when the page has loaded media links, and lists the video, audio and subtitle addresses the current page has loaded or contains (HLS/DASH playlists, MP4/WebM/MKV, MP3/M4A/…, VTT/SRT/ASS…), without stream fragments. Tick the ones you want and tap **Copy** (nothing ticked = copy all), or **Show as text** to select any part yourself. Start playing the video first so its links are loaded. |
| Close button on tabs | Every tab has its own ✕ in the tab bar. |
| Exit prompt | Back on the home page (or with no page history left) asks whether to exit. |
| Home page = your bookmarks | The built-in sample sites are removed. Your bookmarks appear on the home page automatically (the 8 newest). |
| Clear data on exit | Settings → Privacy: clear history (and close all tabs) and/or cookies & site data whenever you close the browser - also when it was left with Home and Android ended it later. Saved passwords are kept. |
| Download folder | Default: Internal storage/Download/Lumen. Settings → Downloads → Choose folder opens the system file manager, where you can pick any folder on internal storage, an SD card or a USB drive. |
| Download from link | Downloads screen → **Download from link**: paste or type an address (a copied link is filled in automatically). Lumen checks it first: web pages open in a tab, files start downloading with the server's file name. |
| Robust USB/SD saving | When you choose a folder, Lumen first writes a test file and refuses read-only or unsupported drives. If the drive is unplugged later, downloads go to internal storage with a notice instead of failing. Drives that only allow writing in order get the parts in a temporary file that's copied over at the end. FAT32's 4 GB limit and full drives are reported clearly. |
| Multi-part downloads | Big files (2 MB+) are downloaded over several connections at once (1–16, default 8), and a dropped connection resumes where it stopped instead of starting over when the server supports it, with an automatic fallback to a single connection. |
| No microphone | Voice search and the microphone permission are removed; websites are always refused the microphone. |
| Tab switching | **Channel Up / Channel Down** on the remote switch to the next or previous tab, with a "Tab 2 of 5: …" notice. You can change the keys in Settings → Shortcuts. |
| Password saving | After you sign in, Lumen offers to save the login (Save / Not now / Never for this site). Logins are encrypted with a key kept in the Android keystore and filled in automatically next time, including on two-step sign-in pages. It saves one login per site, never offers in incognito mode, and you manage logins in Settings → Passwords. |
| Ad blocker per site | The ad-block button in the menu now asks: block ads on this site, allow ads on this site always, or allow ads in this tab only. A site entry also covers its subdomains. Settings → Sites without ad blocking lists them. |
| WireGuard VPN | Settings → WireGuard VPN: import a `.conf` file or paste the config, then Connect. By default only Lumen goes through the VPN, or switch that off to send the whole TV through it. It can connect automatically on start. The config is stored encrypted and uses the official WireGuard library. |

## Building the APK (free, on GitHub, works from a phone)

1. Sign in to github.com and create a **new private repository**. Private matters because it
   contains your signing key.
2. Tap **Add file → Upload files** and upload `sofabrowse-source.zip`, then commit.
3. Tap **Add file → Create new file**, name it `.github/workflows/build.yml`, paste the contents of
   `build-from-zip.yml`, then commit.
4. Open the **Actions** tab. The build starts by itself and takes about 10–15 minutes.
5. When it turns green, open **Releases** (on the repository's main page) and download
   `Lumen-<number>.apk`.

If you put the unzipped project in a repository yourself, `.github/workflows/build-lumen.yml`
does the same job.

### Installing on the TV
Use an app like *Downloader* on the TV to open the release link, or copy the APK with a USB stick,
then allow "install unknown apps" when asked. Lumen installs next to TV Bro and does not
replace it. Every build is signed with the same key in `signing/`, so new builds install as updates.

### Updates from inside Lumen
Settings → Version → **Check for updates now** (and once a day by itself) looks at the Releases of
`RonEH148/Player`; **Update source…** changes the repository. Because the repository is private,
Lumen needs a read-only token once: github.com → Settings → Developer settings → Fine-grained
tokens → Generate new token → *Only select repositories* → your repository → Permissions →
*Contents: Read-only*. Paste it in **Update source…** (it's stored encrypted on the device).
