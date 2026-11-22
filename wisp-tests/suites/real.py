from ._common import *

SITES = [
    ('YouTube', 'https://m.youtube.com', None),
    ('Google search', 'https://www.google.com/search?q=wisp+browser', None),
    ('DuckDuckGo', 'https://duckduckgo.com/?q=wisp+browser', None),
    ('Wikipedia', 'https://en.m.wikipedia.org/wiki/Web_browser', 'browser'),
    ('Reddit', 'https://www.reddit.com/r/android/', None),
    ('GitHub', 'https://github.com/', None),
    ('Amazon.de', 'https://www.amazon.de/', None),
    ('BBC', 'https://www.bbc.co.uk/news', None),
    ('Spiegel (many ads)', 'https://www.spiegel.de/', None),
    ('anime.nexus (Cloudflare)', 'https://anime.nexus/', None),
    ('nowsecure.nl (Cloudflare)', 'https://nowsecure.nl/', None),
    ('BrowserLeaks WebRTC', 'https://browserleaks.com/webrtc', None),
    ('What is my browser', 'https://www.whatismybrowser.com/', None),
]

def visit(label, url, want):
    def fn():
        open_url(url, timeout=40)
        sleep(10)
        shot(label)
        t = ' '.join(texts())
        cf = any(w in t for w in ('Verify you are human', 'Just a moment', 'Checking your browser', 'security check'))
        note('address %r; human check on screen: %s' % (address(), cf))
        if cf:
            sleep(15)
            shot(label + ' after waiting')
            t = ' '.join(texts())
            note('still checking after 25 s: %s' % any(w in t for w in ('Verify you are human', 'Just a moment')))
        check(wisp_running(), 'Wisp stopped on %s' % label)
        if want:
            expect(want.lower() in t.lower(), '"%s" not on the page' % want)
        for _ in range(3):
            swipe_up(0.6, 300)
        shot(label + ' scrolled')
    step(label, suite='real', needs=['real'])(fn)

for s in SITES:
    visit(*s)

@step('YouTube in desktop mode: no reload loop', needs=['real'])
def _():
    open_url('https://m.youtube.com', timeout=40)
    sleep(6)
    menu('Desktop site', wait=3)
    seen = []
    for _ in range(10):
        sleep(3)
        seen.append(address())
    shot('youtube desktop')
    note('addresses over 30 s: %s' % seen)
    changes = sum(1 for a, b in zip(seen, seen[1:]) if a != b)
    expect(changes <= 1, 'address changed %d times (desktop/mobile loop?)' % changes)
    loads = adb('logcat', '-d', '-t', '3000').count('reloadWithUserAgent')
    note('user-agent reloads in the log: %d' % loads)
    menu('Desktop site', wait=3)

@step('YouTube: play a video', needs=['real'])
def _():
    open_url('https://m.youtube.com/watch?v=aqz-KE-bpKQ', timeout=40)
    sleep(12)
    shot('youtube video')
    tap_xy(C.width // 2, int(C.height * 0.22))
    sleep(5)
    shot('youtube playing')
    check(wisp_running(), 'Wisp stopped')
