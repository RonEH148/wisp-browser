from ._common import *

@step('A link from another app opens in Wisp')
def _():
    c0 = tab_count()
    sh('am start -a android.intent.action.VIEW -d "%s/title?t=From%%20outside" -p %s' % (SITE, PKG))
    sleep(4)
    check(wisp_in_front(), 'Wisp did not open the link')
    expect(page_has('From outside', 6), 'page not shown')
    note('tabs %r -> %r' % (c0, tab_count()))

@step('A link opens Wisp from scratch (cold start)')
def _():
    sh('am force-stop %s' % PKG)
    sleep(1)
    sh('am start -a android.intent.action.VIEW -d "%s/title?t=Cold%%20start" -p %s' % (SITE, PKG))
    sleep(6)
    check(wisp_in_front(), 'Wisp did not start')
    expect(page_has('Cold start', 8), 'page not shown after a cold start')

@step('Text shared to Wisp')
def _():
    sh('am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "%s/title?t=Shared%%20in" -p %s' % (SITE, PKG))
    sleep(5)
    note('in front: %s, address %r' % (focus_window(), address()))
    shot('shared text')
    if not wisp_in_front():
        launch(2)

@step('Web search from another app')
def _():
    sh('am start -a android.intent.action.WEB_SEARCH --es query "wisp outside search" -p %s' % PKG)
    sleep(5)
    note('in front: %s, address %r' % (focus_window(), address()))
    shot('web search')
    if not wisp_in_front():
        launch(2)

@step('Share a page from Wisp')
def _():
    goto('/title?t=Share%20me', expect='Share me')
    menu('Share', wait=2.5)
    note('in front: %s' % focus_window())
    shot('share sheet')
    expect(not wisp_in_front() or present(text='Share'), 'no share sheet')
    back()
    sleep(1)
    if not wisp_in_front():
        launch(2)

@step('Add to home screen')
def _():
    goto('/title?t=Home%20shortcut', expect='Home shortcut')
    menu('Add to home screen', wait=2.5)
    shot('add to home')
    note('texts: %s' % texts()[:8])
    press_button('Cancel', timeout=2) or back()
    if not wisp_in_front():
        launch(2)
