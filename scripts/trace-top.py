"""Longest main-thread slices in an atrace text dump.   python -I scripts/trace-top.py <trace.txt> <pid>

Prints the slow frames (Choreographer#doFrame over 8 ms) and, inside each, the slices that took the most time,
nested names joined with " > ". No dependencies: reads the ftrace text format atrace prints.
"""
import re
import sys

path, pid = sys.argv[1], sys.argv[2]
line_re = re.compile(r'^\s*(.+?)-(\d+)\s+\(?\s*[\d-]*\)?\s*\[\d+\].*?\s(\d+\.\d+): tracing_mark_write: ([BE])\|(\d+)(?:\|(.*))?$')
stack, frames = [], []
for line in open(path, encoding='utf-8', errors='replace'):
    m = line_re.match(line)
    if not m or m.group(5) != pid or m.group(2) != pid:   # the main thread only (tid == pid)
        continue
    t, kind, name = float(m.group(3)) * 1000, m.group(4), (m.group(6) or '').strip()
    if kind == 'B':
        stack.append([name, t, []])
    elif stack:
        n, t0, kids = stack.pop()
        node = (n, t - t0, kids)
        if stack:
            stack[-1][2].append(node)
        elif n.startswith('Choreographer#doFrame'):
            frames.append((t0, node))


def flat(node, prefix, out):
    n, d, kids = node
    label = f'{prefix} > {n}' if prefix else n
    out.append((d, label))
    for k in kids:
        flat(k, label, out)


start = frames[0][0] if frames else 0
for t0, node in frames:
    if node[1] < 8:
        continue
    print(f'--- frame at {t0 - start:7.1f} ms: {node[1]:.1f} ms')
    out = []
    flat(node, '', out)
    for d, label in sorted(out, reverse=True)[1:12]:
        if d >= 0.5:
            print(f'  {d:6.2f}  {label[-150:]}')
print(f'{len(frames)} frames, {sum(1 for _, n in frames if n[1] >= 8)} at 8 ms or more')


def tree(node, depth, max_depth):
    n, d, kids = node
    if d < 0.3 or depth > max_depth:
        return
    print(f'{"  " * depth}{d:6.2f}  {n[:110]}')
    for k in sorted(kids, key=lambda k: -k[1]):
        tree(k, depth + 1, max_depth)


if len(sys.argv) > 3:   # also the slowest frame's tree under its measure-and-layout pass
    worst = max(frames, key=lambda f: f[1][1])[1]
    def find(node):
        if node[0] == 'AndroidOwner:measureAndLayout':
            return node
        for k in node[2]:
            r = find(k)
            if r:
                return r
    m = find(worst)
    if m:
        tree(m, 0, int(sys.argv[3]))

if len(sys.argv) > 4:   # average of each slice (by its path) over ordinary frames between the given durations
    lo, hi = (float(x) for x in sys.argv[4].split('-'))
    picked = [n for _, n in frames if lo <= n[1] <= hi]
    totals = {}
    def walk(node, prefix, depth):
        n, d, kids = node
        n = re.sub(r'\d{5,}', '#', n)
        label = f'{prefix} > {n}' if prefix else n
        totals[label] = totals.get(label, 0) + d
        if depth < 9:
            for k in kids:
                walk(k, label, depth + 1)
    for f in picked:
        walk(f, '', 0)
    print(f'mean over {len(picked)} frames of {lo}-{hi} ms:')
    for label, t in sorted(totals.items(), key=lambda kv: -kv[1])[:40]:
        if t / len(picked) >= 0.08:
            print(f'  {t / len(picked):5.2f}  {label[-160:]}')
