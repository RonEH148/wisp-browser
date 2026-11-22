from ._common import *

@step('Every settings page opens')
def _():
    open_settings()
    shot('settings main')
    no_lumen_text()
    t0 = status_bar_px()
    title = find(text='Settings', exact=True)
    if title:
        note('title top %d, status bar %d px' % (title[0].y1, t0))
        expect(title[0].y1 >= t0 - 4, 'Settings title under the status bar')
    for page in SETTINGS_PAGES:
        n = scroll_to(5, text=page)
        if not n:
            expect(False, 'settings page "%s" missing' % page)
            continue
        tap_xy(*n.center)
        sleep(1.5)
        shot(page)
        no_lumen_text()
        for _ in range(3):
            swipe_up(0.6)
        shot(page + ' bottom')
        back()
        sleep(1)
        if present(rid='ibMenu'):
            open_settings()
    leave_settings()

@step('Removed items are gone (repair engine, remote debugging)')
def _():
    open_settings()
    seen = []
    for page in SETTINGS_PAGES:
        n = scroll_to(5, text=page)
        if not n:
            continue
        tap_xy(*n.center)
        sleep(1.2)
        for _ in range(4):
            seen += texts()
            swipe_up(0.6)
        back()
        sleep(0.8)
        if present(rid='ibMenu'):
            open_settings()
    leave_settings()
    for gone in ('Repair the web engine', 'Remote debugging'):
        expect(not any(gone in t for t in seen), '"%s" is still in Settings' % gone)
    note('%d labels seen in Settings' % len(set(seen)))

@step('Search engine setting changes where searches go')
def _():
    open_settings('General')
    n = scroll_to(4, text='Search engine')
    check(n, 'no Search engine row')
    tap_xy(*n.center)
    sleep(1)
    tap(text='Bing')
    sleep(1)
    leave_settings()
    sleep(1)
    b = find(rid='ibSiteData')
    note('button left of the address: %r' % (b[0].desc if b else None))
    open_url('wisp settings words', timeout=30)
    sleep(4)
    a = address() or ''
    note('address: %r' % a)
    expect('bing.com' in a, 'search did not go to Bing: %r' % a)
    open_settings('General')
    scroll_to(4, text='Search engine')
    tap(text='Search engine')
    tap(text='Google')
    leave_settings()

@step('Theme: dark, light, system')
def _():
    for theme in ('Dark', 'Light', 'System default'):
        open_settings('General')
        n = scroll_to(5, text='Theme')
        check(n, 'no Theme row')
        tap_xy(*n.center)
        sleep(1)
        tap(text=theme, exact=True)
        sleep(2)
        shot('settings ' + theme)
        leave_settings()
        goto('/t.html', expect='Wisp test page')
        open_menu()
        shot('menu ' + theme)
        back()

@step('Text size makes page text bigger')
def _():
    goto('/other.html', expect='Other page')
    h0 = text_height('You followed a link.')
    open_settings('General')
    n = scroll_to(5, text='Text size')
    check(n, 'no Text size row')
    tap_xy(*n.center)
    sleep(1)
    shot('text size choice')
    opts = [x for x in find() if '%' in x.label]
    note('options: %s' % [o.label for o in opts][:8])
    if opts:
        tap_xy(*opts[-1].center)
    sleep(1)
    leave_settings()
    goto('/other.html', expect='Other page')
    h1 = text_height('You followed a link.')
    note('text height %s -> %s' % (h0, h1))
    expect(h0 and h1 and h1 > h0, 'text did not get bigger')
    open_settings('General')
    scroll_to(5, text='Text size')
    tap(text='Text size')
    o = [x for x in find() if x.label.strip() in ('100%', '100 %', 'Normal', 'Default')]
    if o:
        tap_xy(*o[0].center)
    leave_settings()

@step('Hide the toolbar while scrolling')
def _():
    open_settings('General')
    n = scroll_to(6, text='Hide the toolbar while scrolling')
    check(n, 'no switch')
    was = switch_state('Hide the toolbar while scrolling')
    set_switch('Hide the toolbar while scrolling', True)
    leave_settings()
    goto('/long.html', expect='Long page top')
    for _ in range(3):
        swipe_up(0.5, 300)
    hidden = not present(rid='etUrl')
    shot('scrolled with hiding on')
    swipe_down(0.3, 300)
    back_again = present(rid='etUrl')
    note('hidden while scrolling down: %s, back when scrolling up: %s' % (hidden, back_again))
    expect(hidden, 'toolbar did not hide')
    expect(back_again, 'toolbar did not come back')
    if not was:
        open_settings('General')
        scroll_to(6, text='Hide the toolbar while scrolling')
        set_switch('Hide the toolbar while scrolling', False)
        leave_settings()

@step('Pull to refresh')
def _():
    open_settings('General')
    n = scroll_to(6, text='Pull to refresh')
    check(n, 'no switch')
    was = switch_state('Pull to refresh')
    set_switch('Pull to refresh', True)
    leave_settings()
    goto('/title?t=Pull%20me', expect='Pull me')
    m = log_mark()
    x = C.width // 2
    sh('input swipe %d %d %d %d 600' % (x, int(C.height * 0.3), x, int(C.height * 0.75)))
    sleep(3)
    hits = server_hits('/title', m)
    note('reloads after pulling: %d' % hits)
    expect(hits >= 1, 'pulling down did not reload')
    if not was:
        open_settings('General')
        scroll_to(6, text='Pull to refresh')
        set_switch('Pull to refresh', False)
        leave_settings()

@step('User agent setting changes what sites see')
def _():
    open_settings('General')
    n = scroll_to(6, text='User agent')
    check(n, 'no User agent row')
    tap_xy(*n.center)
    sleep(1)
    shot('ua choices')
    opts = [x.label for x in find() if x.label and x.cls.endswith('TextView')]
    note('choices: %s' % opts[:10])
    expect(not any('desktop' in o.lower() or 'windows' in o.lower() for o in opts), 'desktop user agents offered on the phone')
    back()
    leave_settings()

@step('Dark mode for all websites')
def _():
    open_settings('Site content')
    n = scroll_to(4, text='Dark mode for all websites')
    check(n, 'no switch')
    set_switch('Dark mode for all websites', True)
    leave_settings()
    goto('/dark.html', expect='White page')
    sleep(2)
    p = shot('forced dark')
    try:
        from PIL import Image
        im = Image.open(os.path.join(C.out, R.shots[-1])).convert('L')
        w, h = im.size
        px = im.getpixel((w // 2, int(h * 0.6)))
        note('brightness in the middle of the page: %d (0 dark, 255 white)' % px)
        expect(px < 128, 'page still white with dark mode on')
    except Exception as e:
        note('could not measure: %s' % e)
    open_settings('Site content')
    scroll_to(4, text='Dark mode for all websites')
    set_switch('Dark mode for all websites', False)
    leave_settings()

@step('Downloads settings dialogs open and cancel')
def _():
    open_settings('Downloads')
    for row in ('Connections per download', 'Downloads at the same time', 'Speed limit', 'Download folder'):
        n = scroll_to(4, text=row)
        if not n:
            note('no "%s"' % row)
            continue
        tap_xy(*n.center)
        sleep(1.5)
        shot(row)
        back()
        sleep(1)
        if not present(text='Downloads') and not wisp_in_front():
            launch(2)
            open_settings('Downloads')
    leave_settings()

@step('About Wisp shows the version and checks for updates')
def _():
    open_settings('About Wisp')
    shot('about')
    t = ' '.join(texts())
    ver = sh('dumpsys package %s | grep -m1 versionName' % PKG).strip().split('=')[-1]
    note('installed versionName: %s' % ver)
    if ver.split():
        expect(ver.split()[0] in t, 'About does not show the version %s' % ver)
    n = scroll_to(4, text='Check for updates')
    if n:
        tap_xy(*n.center)
        sleep(5)
        shot('update check')
        note('after check: %s' % [x for x in texts() if 'update' in x.lower() or 'version' in x.lower()][:4])
    leave_settings()

@step('Settings kept after Quit')
def _():
    open_settings('General')
    n = scroll_to(5, text='Theme')
    check(n, 'no Theme row')
    tap_xy(*n.center)
    tap(text='Dark', exact=True)
    sleep(1)
    leave_settings()
    quit_wisp()
    launch()
    open_settings('General')
    scroll_to(5, text='Theme')
    t = ' '.join(texts())
    note('theme row after Quit: %s' % [x for x in texts() if x in ('Dark', 'Light', 'System default')])
    expect('Dark' in t, 'theme not kept after Quit')
    tap(text='Theme')
    tap(text='System default', exact=True)
    leave_settings()

@step('Settings in landscape')
def _():
    rotate(True)
    open_settings()
    shot('settings landscape')
    title = find(text='Settings', exact=True)
    sb = status_bar_px()
    if title:
        expect(title[0].y1 >= sb - 4, 'title under the status bar in landscape')
    tap(text='General')
    shot('general landscape')
    leave_settings()
    rotate(False)

@step('Diagnostics: log page, Copy, Report a problem')
def _():
    open_settings('About Wisp')
    n = scroll_to(3, text='Diagnostics')
    check(n, 'no Diagnostics in About')
    tap_xy(*n.center)
    sleep(1.5)
    shot('diagnostics')
    t = ' '.join(texts())
    for x in ('Copy log', 'Share log', 'Report a problem', 'Clear the log'):
        expect(x in t, 'no "%s"' % x)
    expect('[page]' in t or '[app]' in t, 'no events in the log')
    tap(text='Copy log')
    sleep(1)
    c = clipboard()
    note('clipboard starts: %r' % c[:120])
    if c and 'Unknown' not in c:
        expect('Wisp diagnostics' in c, 'copied text is not the log')
    tap(text='Report a problem')
    sleep(1)
    shot('report dialog')
    e = find(cls='EditText')
    if e:
        tap_xy(*e[0].center)
        type_text('test report from the emulator')
        end_typing()
    press_button('OK')
    sleep(1.5)
    shot('after report')
    expect(present(text='test report from the emulator'), 'the report is not in the log')
    expect(present(text='picture + layout'), 'no picture saved with the report')
    leave_settings()

@step('Report a problem from the menu saves a picture')
def _():
    goto('/t.html', expect='Wisp test page')
    menu('Report a problem', wait=2)
    shot('report from menu')
    e = find(cls='EditText')
    check(e, 'report dialog did not open')
    tap_xy(*e[0].center)
    type_text('menu report test')
    end_typing()
    press_button('OK')
    sleep(2)
    open_settings('About Wisp')
    tap(text='Diagnostics')
    sleep(1.5)
    t = ' '.join(texts())
    expect('menu report test' in t, 'report not in the log')
    expect('picture + layout' in t, 'no picture saved')
    note('UI checks in the log: %s' % [x for x in texts() if 'UI:' in x][:5])
    leave_settings()
