from ._common import *

def video_page():
    goto('/video.html', expect='Video test')
    tap(text='Play', exact=True)
    sleep(6)

@step('Media links lists the playing video', needs=['real'])
def _():
    video_page()
    menu('Media links', wait=2)
    shot('media list')
    t = texts()
    note('media sheet: %s' % t[:12])
    check(any('mp4' in x.lower() or 'mov_bbb' in x.lower() for x in t), 'the video is not in Media links')
    for item in ('Download', 'Play in Wisp', 'Play in another app', 'Copy link'):
        expect(any(item.lower() == x.lower() or item.lower() in x.lower() for x in t), 'no "%s" in Media links' % item)
    back()

@step('Play in Wisp opens the player', needs=['real'])
def _():
    video_page()
    menu('Media links', wait=2)
    tap(text='Play in Wisp')
    sleep(6)
    w = focus_window()
    note('in front: %s' % w)
    shot('player')
    expect('Player' in w or 'player' in w, 'the player did not open: %s' % w)
    back()
    sleep(2)
    if not wisp_in_front():
        launch(2)
    expect(page_has('Video test', 4), 'did not come back to the page')

@step('Play in another app', needs=['real'])
def _():
    video_page()
    menu('Media links', wait=2)
    tap(text='Play in another app')
    sleep(3)
    note('in front: %s' % focus_window())
    shot('chooser')
    back()
    sleep(1)
    if not wisp_in_front():
        launch(2)

@step('Download from Media links (no GIF question)', needs=['real'])
def _():
    video_page()
    before = files_in(*DOWNLOAD_DIRS)
    menu('Media links', wait=2)
    tap(text='Download', exact=True)
    sleep(2)
    expect(not present(text='Save as GIF?'), 'GIF question shown for a media download')
    for _ in range(20):
        sleep(1)
        if files_in(*DOWNLOAD_DIRS) - before:
            break
    new = files_in(*DOWNLOAD_DIRS) - before
    note('new files %s' % sorted(new))
    expect(any(f.endswith('.mp4') for f in new), 'mp4 not saved')

@step('Fullscreen video', needs=['real'])
def _():
    goto('/video.html', expect='Video test')
    tap(text='Fullscreen', exact=True)
    sleep(4)
    shot('fullscreen')
    expect(not present(rid='etUrl'), 'toolbar still visible in fullscreen')
    rotate(True)
    shot('fullscreen landscape')
    rotate(False)
    back()
    sleep(2)
    shot('after fullscreen')
    expect(present(rid='etUrl'), 'toolbar did not come back after fullscreen')

@step('Stream (HLS) link is recognised', needs=['real'])
def _():
    goto('/video.html', expect='Video test')
    long_press(text='HLS stream link')
    sleep(1)
    shot('hls menu')
    note('menu: %s' % texts()[:10])
    back()

@step('Make a GIF tool opens')
def _():
    goto('/t.html', expect='Wisp test page')
    menu('Make a GIF', wait=3)
    note('in front: %s' % focus_window())
    shot('gif tool')
    back()
    sleep(1)
    if not wisp_in_front():
        launch(2)
