from ._common import *

def monkey(seed, events):
    out = sh('monkey -p %s --throttle 110 -s %d --pct-syskeys 0 --pct-appswitch 0 --pct-anyevent 0 '
             '--ignore-security-exceptions -v %d' % (PKG, seed, events), timeout=900)
    with open(os.path.join(C.out, 'monkey-%d.txt' % seed), 'w') as f:
        f.write(out)
    return out

@step('Random taps on the test site (2000)')
def _():
    goto('/index.html', expect='Wisp test site')
    out = monkey(4242, 2000)
    check('// CRASH' not in out, 'crash during random taps (see monkey-4242.txt)')
    check('// NOT RESPONDING' not in out and 'ANR' not in out, 'froze during random taps')

@step('Random taps from the start page (2000)')
def _():
    close_all_tabs()
    out = monkey(777, 2000)
    check('// CRASH' not in out, 'crash during random taps (see monkey-777.txt)')
    check('// NOT RESPONDING' not in out and 'ANR' not in out, 'froze during random taps')
