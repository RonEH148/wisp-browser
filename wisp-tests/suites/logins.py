from ._common import *
from .privacy import account_user

def fill_and_submit(user, pw, site=SITE):
    goto('/login', expect='Sign in', site=site)
    tap(text='Username')
    sleep(0.5)
    sh('input keyevent 123')
    type_text(user)
    tap(text='Password')
    type_text(pw)
    end_typing()
    btns = find(text='Sign in', exact=True)
    check(btns, 'no Sign in button')
    tap_xy(*btns[-1].center)
    sleep(3)

@step('Wisp offers to save a password, Save')
def _():
    fill_and_submit('alice', 'secret-alice')
    q = wait_for(timeout=5, text='Save password')
    shot('save offer')
    check(q, 'no "Save password" question')
    expect('lvh.me' in q.label, 'question does not name the site: %r' % q.label)
    press_button('Save')
    sleep(1)
    expect(account_user() == 'alice', 'not logged in')

@step('Saved login is filled in automatically')
def _():
    goto('/login', expect='Sign in')
    sleep(3)
    shot('autofill')
    t = texts()
    note('form fields: %s' % [n.text for n in find(cls='EditText') if not n.rid.endswith('etUrl')])
    expect(any(n.text == 'alice' for n in find(cls='EditText')), 'username not filled in')

@step('Second account: the "Sign in as" choice')
def _():
    fill_and_submit('bob', 'secret-bob')
    q = wait_for(timeout=5, text='Save')
    shot('second offer')
    press_button('Save')
    goto('/login', expect='Sign in')
    sleep(3)
    shot('two accounts')
    t = ' '.join(texts())
    note('texts: %s' % [x for x in texts() if 'alice' in x or 'bob' in x or 'Sign in as' in x])
    expect('alice' in t and 'bob' in t, 'no choice between the two accounts')

@step('Not now does not save')
def _():
    fill_and_submit('nobody', 'nope', site=SITE2)
    if wait_for(timeout=5, text='Save password'):
        press_button('Not now')
    goto('/login', expect='Sign in', site=SITE2)
    sleep(3)
    expect(not any(n.text == 'nobody' for n in find(cls='EditText')), 'filled in although Not now')

@step('Password manager lists the saved site')
def _():
    menu('Passwords', wait=2.5)
    shot('password manager')
    t = ' '.join(texts())
    note('locked prompt shown: %s' % ('Unlock' in t or 'fingerprint' in t.lower()))
    if 'Saved passwords' not in t and wisp_in_front() is False:
        back()
    check(wait_for(timeout=4, text='lvh.me'), 'lvh.me not in saved passwords')
    tap(text='lvh.me')
    sleep(1)
    shot('site logins')
    expect(not present(text='secret-alice'), 'password shown in plain text without asking')
    for _ in range(3):
        if present(rid='ibMenu'):
            break
        back()

@step('Saved passwords survive Quit')
def _():
    quit_wisp()
    launch()
    goto('/login', expect='Sign in')
    sleep(3)
    t = ' '.join(texts())
    expect('alice' in t, 'saved logins gone after Quit')

@step('Passwords open without any lock, also with a PIN set on the phone')
def _():
    out = sh('locksettings set-pin 1234')
    note('set a PIN on the device: %s' % out.strip()[:80])
    try:
        sleep(1)
        menu('Passwords', wait=3)
        shot('passwords with pin set')
        check(wisp_running(), 'Wisp crashed when opening Passwords')
        check(wisp_in_front(), 'something else opened (a lock screen?): %s' % focus_window())
        expect(wait_for(timeout=4, text='lvh.me'), 'saved passwords not shown straight away')
        for _ in range(3):
            if present(rid='ibMenu'):
                break
            back()
    finally:
        sh('locksettings clear --old 1234')
        sh('input keyevent 82')
