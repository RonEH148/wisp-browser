from ._common import *

@step('Wisp starts and shows the start page')
def _():
    ms = launch()
    note('start time: %s ms' % ms)
    check(wisp_in_front(), 'Wisp is not in front: %s' % focus_window())
    check(present(rid='etUrl'), 'address bar missing')
    check(present(rid='ibMenu'), 'menu button missing')
    expect(tab_count() == 0, 'tab button should show 0 on the start page, shows %r' % tab_count())
    expect(address() in ('', None, hint_text()), 'address bar should be empty on the start page: %r' % address())
    no_lumen_text()
    note('memory at start: %s kB' % mem_kb())

@step('Start page: no search box in the middle, no loading bar')
def _():
    sleep(2)
    edits = [n for n in find(cls='EditText') if not n.rid.endswith('etUrl')]
    expect(not edits, 'a second text box is on the start page: %s' % edits)
    pb = find(rid='progressBar')
    expect(not pb, 'loading bar visible on the start page')

@step('Open a site by typing its name')
def _():
    open_url('example.com', expect='Example Domain', timeout=30)
    a = address()
    note('address bar: %r' % a)
    check(a and 'example.com' in a, 'address bar should show example.com: %r' % a)
    expect(tab_count() == 1, 'tab button should show 1: %r' % tab_count())

@step('Local test site opens (lvh.me via adb reverse)')
def _():
    goto('/t.html', expect='Wisp test page')
    a = address()
    note('address bar: %r' % a)
    expect(a and 'lvh.me' in a and '/t.html' in a, 'address should show lvh.me/t.html: %r' % a)

@step('Follow a link, Back, Forward')
def _():
    goto('/t.html', expect='Wisp test page')
    tap(text='BIG LINK AREA')
    check(page_has('You followed a link'), 'link did not open')
    back()
    check(page_has('Wisp test page'), 'Back did not return to the test page')
    menu('Forward')
    check(page_has('You followed a link'), 'Forward did not go to the next page')

@step('Reload button reloads the page')
def _():
    goto('/title?t=Reload%20me', expect='Reload me')
    m = log_mark()
    tap(rid='ibReload')
    sleep(3)
    check(server_hits('/title', m) >= 1, 'reload did not ask the site again')

@step('Stop button while a slow page loads')
def _():
    type_address(SITE + '/slow?s=12')
    enter()
    sleep(2)
    r = find(rid='ibReload')
    check(r, 'reload/stop button missing')
    note('button while loading: %r' % r[0].desc)
    expect('stop' in r[0].desc.lower(), 'button should be Stop while loading, is %r' % r[0].desc)
    shot('loading')
    tap_xy(*r[0].center)
    sleep(1.5)
    r = find(rid='ibReload')
    expect(r and 'reload' in r[0].desc.lower(), 'button should be Reload after stopping: %r' % (r[0].desc if r else None))
    expect(not page_has('Slow page done', 2), 'page finished although stopped')

@step('Loading bar shows while loading, hides when done')
def _():
    type_address(SITE + '/slow?s=5')
    enter()
    sleep(1.5)
    seen = present(rid='progressBar')
    page_has('Slow page done', 10)
    sleep(1)
    gone = not present(rid='progressBar')
    note('loading bar seen while loading: %s, gone after: %s' % (seen, gone))
    expect(gone, 'loading bar still visible after the page loaded')

@step('Hardware Back on the first page of a tab')
def _():
    new_tab()
    end_typing()
    goto('/other.html', expect='Other page')
    back()
    sleep(1.5)
    note('after Back: Wisp in front %s, address %r, tabs %r' % (wisp_in_front(), address(), tab_count()))
    shot('after back')
