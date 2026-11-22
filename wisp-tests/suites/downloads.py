from ._common import *

def download_and_check(trigger, want_ext, label):
    before = files_in(*DOWNLOAD_DIRS)
    trigger()
    new = set()
    for _ in range(12):
        sleep(1)
        new = files_in(*DOWNLOAD_DIRS) - before
        if new:
            break
    sleep(1)
    new = files_in(*DOWNLOAD_DIRS) - before
    note('%s: new files %s' % (label, sorted(new)))
    check(new, '%s: nothing saved' % label)
    expect(any(f.lower().endswith(want_ext) for f in new), '%s: saved file is not %s: %s' % (label, want_ext, sorted(new)))
    expect(not any(f.endswith('.bin') for f in new) or want_ext == '.bin', '%s: saved as .bin' % label)
    return new

@step('Download an image from the long-press menu')
def _():
    goto('/t.html', expect='Wisp test page')
    def go():
        n = scroll_to(4, text='test picture')
        check(n, 'test picture not found')
        long_press_xy(*n.center)
        tap(text='Download image')
    download_and_check(go, '.png', 'image')
    shot('after image download')

@step('Download complete notification')
def _():
    sleep(2)
    ns = notifications()
    note('Wisp notifications: %d' % len(ns))
    for l in ns[:5]:
        note(l.strip()[:200])
    sh('cmd statusbar expand-notifications')
    sleep(1.5)
    shot('notifications')
    expect(any('complete' in t.lower() or 'pic' in t.lower() for t in texts()), 'no "Download complete" notification')
    sh('cmd statusbar collapse')
    sleep(1)

@step('Download a link from the long-press menu')
def _():
    goto('/t.html', expect='Wisp test page')
    def go():
        n = scroll_to(4, text='Download the PDF')
        check(n, 'pdf link not found')
        long_press_xy(*n.center)
        tap(text='Download link')
    download_and_check(go, '.pdf', 'link')

@step('Attachment (zip) downloads with its name')
def _():
    goto('/t.html', expect='Wisp test page')
    download_and_check(lambda: open_url(SITE + '/dl/archive.zip?attach=1'), '.zip', 'zip')
    a = address() or ''
    expect('archive.zip' not in a, 'address bar kept the download address: %r' % a)

@step('File without extension gets the right one (.gif, not .bin)')
def _():
    goto('/t.html', expect='Wisp test page')
    download_and_check(lambda: open_url(SITE + '/dl/noext'), '.gif', 'no extension')

@step('WebM: Save as GIF? question, original kept')
def _():
    goto('/t.html', expect='Wisp test page')
    before = files_in(*DOWNLOAD_DIRS)
    open_url(SITE + '/dl/clip.webm?attach=1')
    q = wait_for(timeout=6, text='Save as GIF?')
    shot('gif question')
    expect(q, 'no "Save as GIF?" question for a WebM')
    if q:
        tap(text='Download original')
    sleep(5)
    new = files_in(*DOWNLOAD_DIRS) - before
    note('new files %s' % sorted(new))
    expect(any(f.endswith('.webm') for f in new), 'webm not saved')

@step('MP3 and an 8 MB file')
def _():
    goto('/t.html', expect='Wisp test page')
    download_and_check(lambda: open_url(SITE + '/dl/song.mp3?attach=1'), '.mp3', 'mp3')
    download_and_check(lambda: open_url(SITE + '/dl/big.bin?attach=1'), '.bin', 'big')

@step('Downloads screen shows only running downloads')
def _():
    menu('Downloads', wait=2.5)
    shot('downloads screen')
    t = ' '.join(texts())
    note('downloads screen: %s' % t[:300])
    expect('No downloads running' in t or 'No downloads yet' in t, 'finished downloads are listed')
    back()

@step('Download with no internet fails cleanly')
def _():
    goto('/t.html', expect='Wisp test page')
    set_network(False)
    try:
        open_url('https://speed.hetzner.de/100MB.bin')
        sleep(4)
        shot('no internet download')
        note('texts: %s' % [t for t in texts() if 'download' in t.lower() or 'couldn' in t.lower()][:4])
        check(wisp_running(), 'Wisp stopped')
    finally:
        set_network(True)
