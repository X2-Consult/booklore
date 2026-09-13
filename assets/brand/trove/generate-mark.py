# Builds the TROVE symbol as SVG path data from geometry measured off the approved brand sheet
# (logo-primary-light.png). Coordinates are the sheet's pixels shifted to a 124x124 box.
import math, json
OX, OY = 6.5, 0.0   # centre the 111x124 mark in a 124x124 box

def P(x, y):  # sheet pixel -> viewBox
    return (x - 41 + OX, y - 34 + OY)

def fmt(v): return f"{v:.2f}".rstrip('0').rstrip('.')

def rounded_poly(pts, r):
    """Closed polygon with corners rounded by quadratic curves (tangent points r along each edge)."""
    n = len(pts); out = []
    for i in range(n):
        p0, p1, p2 = pts[i-1], pts[i], pts[(i+1) % n]
        def toward(a, b, d):
            dx, dy = b[0]-a[0], b[1]-a[1]; L = math.hypot(dx, dy); d = min(d, L/2)
            return (a[0]+dx*d/L, a[1]+dy*d/L)
        a = toward(p1, p0, r); b = toward(p1, p2, r)
        out.append(('L' if out else 'M', a)); out.append(('Q', p1, b))
    s = ''
    for cmd in out:
        if cmd[0] in 'ML': s += f"{cmd[0]}{fmt(cmd[1][0])} {fmt(cmd[1][1])}"
        else: s += f"Q{fmt(cmd[1][0])} {fmt(cmd[1][1])} {fmt(cmd[2][0])} {fmt(cmd[2][1])}"
    return s + 'Z'

def capsule(c1, c2, rad):
    """Stadium between two cap centres."""
    dx, dy = c2[0]-c1[0], c2[1]-c1[1]; L = math.hypot(dx, dy); nx, ny = -dy/L*rad, dx/L*rad
    a = (c1[0]+nx, c1[1]+ny); b = (c2[0]+nx, c2[1]+ny); c = (c2[0]-nx, c2[1]-ny); d = (c1[0]-nx, c1[1]-ny)
    return (f"M{fmt(a[0])} {fmt(a[1])}L{fmt(b[0])} {fmt(b[1])}A{fmt(rad)} {fmt(rad)} 0 0 0 {fmt(c[0])} {fmt(c[1])}"
            f"L{fmt(d[0])} {fmt(d[1])}A{fmt(rad)} {fmt(rad)} 0 0 0 {fmt(a[0])} {fmt(a[1])}Z")

def circle(cx, cy, r):
    return f"M{fmt(cx-r)} {fmt(cy)}a{fmt(r)} {fmt(r)} 0 1 0 {fmt(2*r)} 0a{fmt(r)} {fmt(r)} 0 1 0 {fmt(-2*r)} 0Z"

def line(m, b, x): return m*x + b
R = 2.6
# Left face: book spines. Edge lines fitted from the sheet (y = m*x + b in sheet pixels).
bar1 = rounded_poly([P(41, line(-0.546, 84.3, 41)), P(62.5, line(-0.546, 84.3, 62.5)),
                     P(62.5, line(0.554, 103.9, 62.5)), P(41, line(0.554, 103.9, 41))], R)
bar2 = rounded_poly([P(70, line(0.55, 14.8, 70)), P(84.5, line(0.55, 14.8, 84.5)),
                     P(84.5, line(0.55, 103.8, 84.5)), P(70, line(0.55, 103.8, 70))], R)
bar3 = capsule(P(99.75, 76.9), P(99.75, 149.4), 7.9)                 # front corner, round-ended
top  = capsule(P(86.3, 43.0), P(142.7, 67.8), 8.35)                  # top edge
# Right face: server slots, with the status dots cut out of slots 2 and 3.
slot1 = rounded_poly([P(115, line(-0.41, 139.4, 115)), P(151, line(-0.41, 139.4, 151)),
                      P(151, line(-0.537, 166.7, 151)), P(115, line(-0.537, 166.7, 115))], R)
slot2 = rounded_poly([P(115, line(-0.548, 175.6, 115)), P(151, line(-0.548, 175.6, 151)),
                      P(151, line(-0.554, 187.4, 151)), P(115, line(-0.554, 187.4, 115))], R)
slot3 = rounded_poly([P(115, line(-0.546, 193.6, 115)), P(151, line(-0.546, 193.6, 151)),
                      P(151, line(-0.565, 208.3, 151)), P(115, line(-0.565, 208.3, 115))], R)
d2 = circle(*P(145.2, 101.9), 1.9); d3 = circle(*P(145.2, 121.1), 1.9)
mark = bar1 + bar2 + bar3 + top + slot1 + slot2 + d2 + slot3 + d3
json.dump({'d': mark}, open('mark.json', 'w'))  # raw path data, as used in trove-mark.ts
grad = ('<linearGradient id="g" gradientUnits="userSpaceOnUse" x1="6" y1="118" x2="118" y2="10">'
        '<stop offset="0" stop-color="#0a66ff"/><stop offset=".55" stop-color="#10a6ee"/><stop offset="1" stop-color="#1ed3bf"/></linearGradient>')
open('trove-mark-gradient.svg', 'w').write(f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 124 124"><defs>{grad}</defs><path fill="url(#g)" fill-rule="evenodd" d="{mark}"/></svg>')
open('trove-mark-mono.svg', 'w').write(f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 124 124"><path fill="#111827" fill-rule="evenodd" d="{mark}"/></svg>')
print(len(mark), 'chars of path data')
