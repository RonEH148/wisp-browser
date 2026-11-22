from ._common import *

@step('No internet: error page, then it works again')
def _():
    set_network(False)
    try:
        open_url('example.org', timeout=20)
        sleep(4)
        shot('offline error')
        no_lumen_text()
        t = ' '.join(texts())
        note('error page: %s' % t[:200])
        check(wisp_running(), 'Wisp stopped without internet')
    finally:
        set_network(True)
    sleep(3)
    tap(rid='ibReload')
    expect(page_has('Example Domain', 25), 'page did not load after the internet came back')

@step('Internet lost while a page loads')
def _():
    type_address(SITE + '/slow?s=6')
    enter()
    sleep(1.5)
    set_network(False)
    sleep(6)
    set_network(True)
    shot('after loss')
    check(wisp_running(), 'Wisp stopped')

@step('Android closes Wisp in the background')
def _():
    goto('/title?t=Before%20kill', expect='Before kill')
    home()
    sh('am kill %s' % PKG)
    sleep(2)
    note('still running after kill: %s' % wisp_running())
    launch(5)
    shot('after kill')
    note('after reopening: address %r, counter %r' % (address(), tab_count()))
    check(wisp_in_front(), 'Wisp did not open again')

@step('Force stop and start again')
def _():
    goto('/title?t=Before%20stop', expect='Before stop')
    sh('am force-stop %s' % PKG)
    sleep(1)
    launch(5)
    note('address %r, counter %r' % (address(), tab_count()))
    check(present(rid='etUrl'), 'browser not shown')

@step('Low memory warning')
def _():
    goto('/t.html', expect='Wisp test page')
    for level in ('RUNNING_LOW', 'RUNNING_CRITICAL', 'COMPLETE'):
        sh('am send-trim-memory %s %s' % (PKG, level))
        sleep(1.5)
    home()
    sh('am send-trim-memory %s COMPLETE' % PKG)
    sleep(1)
    launch(3)
    check(wisp_running(), 'Wisp died on a memory warning')
    shot('after trim')

@step('Turning the phone many times while a page loads')
def _():
    type_address(SITE + '/article.html')
    enter()
    for i in range(6):
        rotate(i % 2 == 0)
    rotate(False)
    check(page_has('A long test article', 15), 'page lost after turning')

@step('Fast repeated taps on the toolbar')
def _():
    goto('/t.html', expect='Wisp test page')
    m = wait_for(rid='ibMenu')
    check(m, 'no menu button')
    for _ in range(3):
        sh('input tap %d %d' % m.center)
    sleep(2)
    shot('triple menu tap')
    n = len(find(text='Quit Wisp'))
    note('menus open: %d' % n)
    expect(n <= 1, 'more than one menu opened')
    to_base()
    t = wait_for(rid='tvTabCount')
    check(t, 'no tab button')
    for _ in range(3):
        sh('input tap %d %d' % t.center)
    sleep(2)
    shot('triple tabs tap')
    to_base()
    r = wait_for(rid='ibReload')
    check(r, 'no reload button')
    for _ in range(5):
        sh('input tap %d %d' % r.center)
    sleep(3)
    check(page_has('Wisp test page', 6), 'page lost after fast reloads')

@step('Back pressed many times')
def _():
    for p in ('/title?t=B1', '/title?t=B2', '/title?t=B3'):
        goto(p, expect=p.split('=')[1])
    for _ in range(8):
        sh('input keyevent 4')
        sleep(0.3)
    sleep(2)
    note('in front: %s' % focus_window())
    shot('after many backs')
    check(wisp_running(), 'Wisp stopped')

@step('System dark mode')
def _():
    sh('cmd uimode night yes')
    sleep(3)
    launch(2)
    shot('dark start')
    goto('/t.html', expect='Wisp test page')
    open_menu()
    shot('dark menu')
    back()
    open_settings()
    shot('dark settings')
    leave_settings()
    open_tabs_grid()
    shot('dark tabs')
    back()
    sh('cmd uimode night no')
    sleep(3)

@step('Large font (130 %)')
def _():
    sh('settings put system font_scale 1.3')
    sleep(3)
    launch(2)
    goto('/t.html', expect='Wisp test page')
    shot('large font page')
    open_menu()
    shot('large font menu')
    back()
    open_settings()
    shot('large font settings')
    tap(text='Privacy and security')
    shot('large font privacy')
    leave_settings()
    site_sheet()
    shot('large font site sheet')
    back()
    sh('settings put system font_scale 1.0')
    sleep(3)

@step('Long session: memory')
def _():
    m0 = mem_kb()
    for i in range(10):
        goto('/article.html' if i % 2 else '/t.html', expect='A long test article' if i % 2 else 'Wisp test page')
    m1 = mem_kb()
    note('memory %s -> %s kB' % (m0, m1))
