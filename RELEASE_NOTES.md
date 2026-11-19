## Lumen 107
- Videos sent to another player (Just Player etc.): fewer "HTTP 403". Playlists served without ".m3u8" or with a wrong type are now recognised and rewritten, DASH (.mpd) manifests are rewritten too, so every segment goes through Lumen with the page's headers; requests also carry the browser headers sites check (Accept-Language, Sec-Fetch-*).

* Several bugfixes (handling physical keyboards, page preview rendering)