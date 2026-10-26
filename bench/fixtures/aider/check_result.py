import math_tools as m
assert m.clamp(-1,0,10)==0
assert m.clamp(5,0,10)==5
assert m.clamp(11,0,10)==10
assert m.clamp(2,2,2)==2
try:
    m.clamp(1,3,2)
    raise AssertionError('inverted bounds accepted')
except ValueError:
    pass
assert m.normalise_name('  HELLO   World  ')=='hello world'
assert m.normalise_name('  Straße\tTEST\n')=='strasse test'
try:
    m.normalise_name('  \t\n')
    raise AssertionError('empty name accepted')
except ValueError:
    pass
print('Two-turn Aider edits and regression cases: PASS')
