from ._common import *

@step('Typed text stays when the phone turns')
def _():
    goto('/t.html', expect='Wisp test page')
    type_address('abcdef')
    rotate(True)
    shot('landscape typing')
    a = address()
    rotate(False)
    check(a == 'abcdef', 'typed text after turning: %r' % a)

@step('Typed text stays when the search engine list opens')
def _():
    goto('/t.html', expect='Wisp test page')
    type_address('xyzzy')
    b = find(rid='ibSiteData')
    check(b, 'search engine button missing while typing')
    note('button while typing: %r' % b[0].desc)
    expect('search engine' in b[0].desc.lower(), 'the button left of the address should be the search engine while typing: %r' % b[0].desc)
    tap_xy(*b[0].center)
    sleep(1.5)
    shot('engine list')
    check(wait_for(timeout=4, any_of=['Google', 'DuckDuckGo', 'Bing']), 'engine list did not open')
    back()
    sleep(1)
    check(address() == 'xyzzy', 'typed text after the engine list: %r' % address())

@step('Typed text stays when leaving the app and coming back')
def _():
    goto('/t.html', expect='Wisp test page')
    type_address('qwerty')
    home()
    launch(2)
    a = address()
    note('after coming back: %r, editing %s' % (a, editing()))
    check(a == 'qwerty', 'typed text after leaving the app: %r' % a)

@step('Closing the keyboard without Go brings the toolbar back')
def _():
    goto('/t.html', expect='Wisp test page')
    start_typing()
    check(keyboard_shown() is not False, 'keyboard did not open')
    back()
    sleep(1.5)
    check(present(rid='ibReload') and present(rid='tvTabCount'), 'toolbar buttons missing after the keyboard closed')
    a = address()
    check(a and '/t.html' in a, 'address should be the page again: %r' % a)
    expect(not editing(), 'address field still in typing mode')

@step('A tap on the page while typing only ends typing')
def _():
    goto('/t.html', expect='Wisp test page')
    start_typing()
    m = log_mark()
    big = wait_for(text='BIG LINK AREA')
    check(big, 'big link not on screen')
    tap_xy(*big.center)
    sleep(2.5)
    check(not page_has('You followed a link', 1), 'the tap also pressed the link')
    check(server_hits('/other.html', m) == 0, 'the link was requested')
    expect(not keyboard_shown(), 'keyboard still open')
    expect(not editing(), 'still typing')

@step('Address shows the path and query, not just the site')
def _():
    goto('/title?t=Pathcheck', expect='Pathcheck')
    a = address()
    note('address: %r' % a)
    check(a and '/title' in a and 't=Pathcheck' in a, 'path or query missing: %r' % a)

@step('Search words go to the search engine')
def _():
    open_url('wisp test words', timeout=30)
    sleep(4)
    a = address() or ''
    note('address after search: %r' % a)
    check('wisp' in a.lower() and ('search' in a.lower() or 'q=' in a.lower() or '?' in a),
          'search did not open a search page: %r' % a)
    shot('search result')

@step('Choose another search engine from the address bar')
def _():
    goto('/t.html', expect='Wisp test page')
    type_address('wisp engine words')
    tap(rid='ibSiteData')
    n = wait_for(timeout=4, text='DuckDuckGo')
    check(n, 'DuckDuckGo not in the list')
    tap_xy(*n.center)
    sleep(1)
    note('typed text after choosing: %r' % address())
    if address() != 'wisp engine words':
        type_address('wisp engine words')
    enter()
    sleep(6)
    a = address() or ''
    note('address: %r' % a)
    check('duckduckgo' in a.lower(), 'search did not go to DuckDuckGo: %r' % a)
    # back to the first engine
    type_address('x')
    tap(rid='ibSiteData')
    g = wait_for(timeout=4, text='Google')
    if g:
        tap_xy(*g.center)
    end_typing()

@step('HTTPS first: http://example.com opens over https')
def _():
    open_url('http://example.com', expect='Example Domain', timeout=30)
    a = address() or ''
    note('address: %r' % a)
    expect(not a.startswith('http://'), 'stayed on http: %r' % a)

@step('A site without https still opens (with a note)')
def _():
    goto('/other.html', expect='Other page')
    a = address() or ''
    note('address: %r' % a)
    expect(a.startswith('http://'), 'http-only address should show http:// : %r' % a)

@step('A very long address does not break the bar')
def _():
    long = SITE + '/title?t=Long&x=' + 'a' * 1500
    open_url(long, expect='Long', timeout=25)
    a = address() or ''
    note('address length shown: %d' % len(a))
    check(present(rid='ibMenu') and present(rid='tvTabCount'), 'toolbar broken by a long address')

@step('Go with an empty address does nothing bad')
def _():
    goto('/t.html', expect='Wisp test page')
    start_typing()
    clear_field()
    enter()
    sleep(2)
    check(wisp_in_front(), 'Wisp left the screen')
    note('address after empty Go: %r' % address())

@step('Suggestions while typing')
def _():
    goto('/t.html', expect='Wisp test page')
    type_address('lvh')
    sleep(2)
    shot('suggestions')
    note('texts on screen: %s' % [t for t in texts() if 'lvh' in t.lower()][:6])
    end_typing()

@step('Typed address of a download leaves the bar')
def _():
    goto('/t.html', expect='Wisp test page')
    before = files_in(*DOWNLOAD_DIRS)
    open_url(SITE + '/file.pdf')
    sleep(4)
    a = address() or ''
    note('address after: %r' % a)
    expect('file.pdf' not in a, 'address bar still shows the download address')
    new = files_in(*DOWNLOAD_DIRS) - before
    note('new files: %s' % sorted(new))
    expect(any(f.endswith('.pdf') for f in new), 'the pdf was not saved')
