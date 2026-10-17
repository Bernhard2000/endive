import sys, json
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent.parent / "split-function-optimization-2026-10-02"))
from parse_class_methods import methods
S = Path(sys.argv.pop(1))
variants = sys.argv[1:]
mods = sorted(p.name for p in (S / variants[0]).iterdir())
rows = []
for m in mods:
    r = {'module': m}
    for v in variants:
        ms = []
        for c in (S / v / m).glob('*.class'):
            ms.extend(methods(c))
        func = [x for x in ms if x['name'].startswith('func_')]
        r[v] = dict(total=sum(x['code_length'] for x in ms),
                    func=sum(x['code_length'] for x in func),
                    huge=sum(1 for x in func if x['code_length'] > 8000),
                    n=len(func),
                    sizes={x['name']: x['code_length'] for x in func})
    rows.append(r)
for r in rows:
    line = f"{r['module']:40s}"
    base = r[variants[0]]
    for v in variants:
        d = r[v]
        line += f" | {v}: func={d['func']:>9} huge={d['huge']:>3}"
        if v != variants[0]:
            line += f" ({(d['func']-base['func'])/base['func']*100:+.2f}%)"
    print(line)
    # method that shrank most (absolute) between first and last
    a, b = r[variants[0]]['sizes'], r[variants[-1]]['sizes']
    if a.keys() == b.keys():
        deltas = sorted(((b[k]-a[k], k, a[k], b[k]) for k in a), key=lambda t: t[0])
        print('   largest shrink:', deltas[:2], ' grew:', [d for d in deltas if d[0] > 0][:3])
