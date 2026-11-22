from ._common import *

LINK_MENU = ['Open in new tab', 'Copy link', 'Share link', 'Download link']
IMAGE_MENU = ['Download image', 'Open image in new tab', 'Copy image address']

@step('Long press a text link: link menu')
def _():
    goto('/t.html', expect='Wisp test page')
    long_press(text='Long press this link')
    check(wait_for(timeout=5, text='Open in new tab'), 'link menu did not appear')
    have = texts()
    for item in LINK_MENU + ['Hide element']:
        expect(any(item.lower() in t.lower() for t in have), 'link menu has no "%s"' % item)
    shot('link menu')
    back()
    sleep(1)
    sel = find(any_of=['Select all', 'Copy'], pkg=PKG)
    expect(not [s for s in sel if s.cls.endswith('Button') or 'Select all' in s.label],
           'text selection still active after the link menu')

@step('Link menu: Open in new tab')
def _():
    goto('/t.html', expect='Wisp test page')
    c0 = tab_count()
    long_press(text='Long press this link')
    tap(text='Open in new tab')
    sleep(2)
    c1 = tab_count()
    note('tabs %r -> %r' % (c0, c1))
    expect(c1 == (c0 or 0) + 1, 'tab count did not go up by one')

@step('Link menu: Copy link')
def _():
    goto('/t.html', expect='Wisp test page')
    long_press(text='Long press this link')
    tap(text='Copy link')
    sleep(1)
    c = clipboard()
    note('clipboard: %r' % c[:200])
    if c and 'Unknown command' not in c and 'not' not in c.lower()[:20]:
        expect('other.html' in c, 'clipboard does not have the link: %r' % c[:200])

@step('Long press an image: image menu')
def _():
    goto('/t.html', expect='Wisp test page')
    n = scroll_to(4, text='test picture')
    check(n, 'test picture not found')
    long_press_xy(*n.center)
    check(wait_for(timeout=5, text='Download image'), 'image menu did not appear')
    have = texts()
    for item in IMAGE_MENU:
        expect(any(item.lower() in t.lower() for t in have), 'image menu has no "%s"' % item)
    shot('image menu')
    back()

@step('Long press plain text selects it')
def _():
    goto('/other.html', expect='Other page')
    long_press(text='You followed a link')
    sleep(1)
    shot('selection')
    note('selection menu texts: %s' % [t for t in texts() if t in ('Copy', 'Share', 'Select all', 'Web search')])
    back()

@step('Find in page finds both matches')
def _():
    goto('/t.html', expect='Wisp test page')
    menu('Find in page')
    type_text('needle')
    sleep(2)
    shot('find')
    t = ' '.join(texts())
    note('find bar texts: %s' % [x for x in texts() if '/' in x or x.isdigit()][:5])
    expect('2' in t, 'match count (2) not shown')
    nx = find(desc='Next match')
    if nx:
        tap_xy(*nx[0].center)
    end_typing()
    cl = find(desc='Close find in page')
    check(cl, 'no close button on the find bar')
    tap_xy(*cl[0].center)
    sleep(1)
    check(present(rid='etUrl'), 'address bar did not come back after find')

@step('JavaScript alert, confirm and prompt')
def _():
    goto('/dialogs.html', expect='Dialogs test')
    tap(text='Alert', exact=True)
    expect(wait_for(timeout=4, text='HELLO ALERT'), 'alert not shown')
    shot('alert')
    press_button('OK')
    expect(page_has('after alert', 4), 'page did not continue after the alert')
    tap(text='Confirm', exact=True)
    expect(wait_for(timeout=4, text='CONFIRM THIS'), 'confirm not shown')
    press_button('OK')
    expect(page_has('confirm: true', 4), 'confirm result wrong')
    tap(text='Prompt', exact=True)
    expect(wait_for(timeout=4, text='PROMPT TEXT'), 'prompt not shown')
    press_button('OK')
    expect(page_has('prompt: abc', 4), 'prompt result wrong')

@step('target=_blank link opens a new tab')
def _():
    goto('/links.html', expect='Links test')
    c0 = tab_count()
    tap(text='Target blank link')
    sleep(3)
    note('tabs %r -> %r, page: %s' % (c0, tab_count(), page_has('New window page', 3)))
    expect(page_has('New window page', 3), 'the link did not open')

@step('Pop-ups: automatic one blocked, tapped one opens')
def _():
    goto('/popup.html', expect='Popup test')
    sleep(2)
    t = ' '.join(texts())
    note('auto popup result on the page: %s' % ('BLOCKED' if 'BLOCKED' in t else 'OPENED' if 'OPENED' in t else '?'))
    expect('auto popup: BLOCKED' in t or not page_has('Auto popup', 1), 'the automatic pop-up opened')
    c0 = tab_count()
    tap(text='Open a window')
    sleep(3)
    note('after tapping: tabs %r -> %r' % (c0, tab_count()))
    expect(page_has('Popup window', 3) or tab_count() == (c0 or 0) + 1, 'the tapped window did not open')

@step('mailto: and tel: links hand over to other apps')
def _():
    for label in ('Mail link', 'Phone link'):
        goto('/links.html', expect='Links test')
        tap(text=label)
        sleep(2.5)
        note('%s -> in front: %s' % (label, focus_window()))
        shot(label)
        if not wisp_in_front():
            back()
            sleep(1)
            if not wisp_in_front():
                launch(2)
        check(wisp_running(), 'Wisp stopped after %s' % label)

@step('intent:// link with fallback opens the fallback page')
def _():
    goto('/links.html', expect='Links test')
    tap(text='Intent link with fallback')
    sleep(4)
    note('in front: %s, address %r' % (focus_window(), address()))
    expect(page_has('Other page', 3) or not wisp_in_front(), 'neither the app nor the fallback page opened')

@step('Store (market://) link')
def _():
    goto('/links.html', expect='Links test')
    tap(text='Store link')
    sleep(3)
    note('in front: %s' % focus_window())
    shot('store link')

@step('javascript: link runs')
def _():
    goto('/links.html', expect='Links test')
    tap(text='JavaScript link')
    expect(page_has('js link ran', 4), 'javascript link did not run')

@step('Redirect chain arrives')
def _():
    goto('/links.html', expect='Links test')
    tap(text='Redirect chain')
    check(page_has('Other page', 10), 'redirect chain did not arrive')
    expect('/other.html' in (address() or ''), 'address after redirects: %r' % address())

@step("Site's own 404 page is shown")
def _():
    goto('/status/404', expect='Not found page')

@step('Typing into page fields and the picker controls')
def _():
    goto('/form.html', expect='Form test')
    tap(text='Your name')
    sleep(1)
    type_text('Gaya')
    expect(page_has('typed: Gaya', 4), 'typing into the page field did not work')
    end_typing()
    tap(text='Apple')
    sleep(1.5)
    shot('select open')
    expect(wait_for(timeout=3, text='Cherry'), 'dropdown did not open')
    back()
    n = find(cls='CheckBox') or find(text='Tick me')
    if n:
        tap_xy(*n[0].center)
    shot('form done')

@step('File upload opens a file picker')
def _():
    goto('/upload.html', expect='Upload test')
    n = find(cls='Button', pkg=PKG) or find(text='Choose') or find(text='No file')
    f = [x for x in find(pkg=PKG) if 'file' in x.label.lower() or 'choose' in x.label.lower()]
    target = (f or n or [None])[0]
    check(target, 'file field not found')
    tap_xy(*target.center)
    sleep(3)
    w = focus_window()
    note('in front after tapping the file field: %s' % w)
    shot('picker')
    expect(not wisp_in_front() or present(text='Files') or present(text='Recent'), 'no file picker opened')
    back()
    sleep(1)
    if not wisp_in_front():
        launch(2)
    check(page_has('Upload test', 4), 'page gone after closing the picker')

@step('Site asks for location, camera, microphone, notifications')
def _():
    goto('/perm.html', expect='Permission test')
    for b in ('Ask location', 'Ask camera', 'Ask microphone', 'Ask notifications'):
        tap(text=b)
        sleep(2)
        shot(b)
        prompt = [t for t in texts() if any(w in t.lower() for w in ('allow', 'block', 'deny', 'while using'))]
        note('%s: dialog texts %s' % (b, prompt[:5]))
        if prompt:
            press_button('Block', 'Deny', "Don't allow", 'Not now', 'Cancel', timeout=2)
            sleep(1)
        if not wisp_in_front():
            back()
        r = [t for t in texts() if ':' in t and any(w in t for w in ('location', 'camera', 'microphone', 'notifications', 'permission'))]
        note('page says: %s' % r[:2])

@step('WebRTC does not show the real address (setting on)')
def _():
    goto('/webrtc.html', expect='WebRTC test')
    sleep(8)
    r = [t for t in texts() if t.startswith('WEBRTC:')]
    note('result: %s' % r)
    check(r, 'the WebRTC page gave no result')
    expect('host' not in r[0] and 'srflx' not in r[0], 'WebRTC shows host/public candidates: %s' % r[0])

@step('Scrolling a long page')
def _():
    goto('/long.html', expect='Long page top')
    for _ in range(5):
        swipe_up(0.6, 250)
    shot('scrolled')
    note('toolbar visible after scrolling down: %s' % present(rid='etUrl'))
    for _ in range(6):
        swipe_down(0.6, 250)
    sleep(1)
    expect(page_has('Long page top', 3), 'did not scroll back to the top')
    expect(present(rid='etUrl'), 'toolbar not back at the top of the page')
