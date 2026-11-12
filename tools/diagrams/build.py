"""Builds the architecture diagrams of docs/architecture from the page models in pages.py.

Python 3, standard library only. For every page it computes the layout on a grid (fixed gaps, box size from the
label), routes orthogonal edges around the boxes, checks the result and fails loudly, then writes:

- docs/architecture/<file>.drawio: uncompressed draw.io XML, one tab per page;
- docs/architecture/png/<file>-<n>.svg.html: a standalone page per diagram page, from the same model, which
  tools/diagrams/render.mjs turns into docs/architecture/png/<file>-<n>.png (headless Chrome, Playwright).

Usage: python tools/diagrams/build.py          (writes .drawio and the HTML pages)
       python tools/diagrams/build.py --check  (layout and checks only, writes nothing)
"""

import heapq
import itertools
import html
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
OUT = os.path.join(ROOT, "docs", "architecture")
PNG = os.path.join(OUT, "png")

GAP = 60  # between boxes
GPAD = 40  # inside a group, around its children
GHEAD = 22  # group title band, on top of GPAD
BPAD = 12  # text padding inside a box
STEP = 10  # routing grid
CLEAR = 10  # distance an edge keeps from a box
MARGIN = 40  # page border
TOP = 86  # space for the page title and the note
MAX_SUB = 2  # small-text lines under a box title

KINDS = {
    "director": ("#dae8fc", "#6c8ebf", "the director, or the browser"),
    "floor42": ("#d5e8d4", "#82b366", "Floor42 code"),
    "agent": ("#e1d5e7", "#9673a6", "agent (Claude Code)"),
    "external": ("#f5f5f5", "#666666", "outside Floor42"),
    "store": ("#fff2cc", "#d6b656", "stored data"),
    "check": ("#f8cecc", "#b85450", "check that can refuse"),
    "keycloak": ("#ffe6cc", "#d79b00", "Keycloak"),
    "sandbox": ("#d4ecec", "#3d8c8c", "WSL sandbox"),
    "secret": ("#fde9f3", "#b5487f", "secret or credential"),
    "planned": ("#ffffff", "#999999", "planned, not built"),
    "time": ("#333333", "#333333", "time"),
    "threat": ("#f8cecc", "#b85450", "attack"),
    "residual": ("#fff2cc", "#d6b656", "residual risk"),
    "note": ("#fafafa", "#aaaaaa", None),
    "state": ("#ffffff", "#555555", "a state"),
}
EDGE_STYLES = {
    "call": ("#333333", False, "call or command"),
    "flow": ("#666666", True, "data, answer or redirect"),
    "dir": ("#3c6bc4", False, "moved by the director"),
    "agent": ("#8a4fb0", False, "moved by an agent"),
    "f42": ("#3d8a3d", False, "moved by Floor42"),
    "refuse": ("#b85450", False, "refused"),
    "plain": ("#555555", False, None),
}


class BuildError(Exception):
    pass


# ---------------------------------------------------------------- text measure

def text_w(s, size=12, bold=False, mono=False):
    """Width estimate of one line: 7 px a character at 12 px (wider for capitals and bold), scaled by font size."""
    if mono:
        return len(s) * 0.62 * size
    w = 0.0
    for ch in s:
        if ch in "il.,:;'|!() []{}/":
            w += 0.33
        elif ch.isupper() or ch in "mwMW@%&":
            w += 0.72 if not bold else 0.78
        else:
            w += 0.56 if not bold else 0.6
    base = max(w * size, len(s) * 7 * size / 12 * (0.9 if not bold else 1.0))
    return base


def wrap(s, width):
    """Wraps on spaces so that no line is wider than width (px, 10 px font)."""
    words, lines, cur = s.split(" "), [], ""
    for w in words:
        nxt = (cur + " " + w).strip()
        if cur and text_w(nxt, 10) > width:
            lines.append(cur)
            cur = w
        else:
            cur = nxt
    if cur:
        lines.append(cur)
    return lines


# ---------------------------------------------------------------- model

class Node:
    def __init__(self, id, title, sub=(), kind="floor42", shape="box", w=None, mono=False, lines=None):
        self.id, self.title, self.kind, self.shape, self.mono = id, title, kind, shape, mono
        self.sub = [sub] if isinstance(sub, str) else list(sub)
        self.sub = [s for s in self.sub if s]
        self.maxlines = lines if lines is not None else (MAX_SUB if shape in ("box", "state") else 40)
        self.minw = w
        self.x = self.y = self.w = self.h = 0
        self.group = None

    def size(self):
        if self.shape in ("start", "end"):
            return 22, 22
        if self.shape == "pill":
            return max(text_w(self.title, 11, True) + 2 * BPAD, 90), 26
        tw = text_w(self.title, 12, True)
        sw = max([text_w(s, 10, mono=self.mono) for s in self.sub] or [0])
        w = max(tw, sw) + 2 * BPAD
        w = max(w, self.minw or 0, 90 if self.shape != "note" else 0)
        h = 10 + (16 if self.title else 0) + 13 * len(self.sub) + 10
        return round_up(w), round_up(max(h, 40 if self.shape != "note" else 0))

    def rect(self):
        return (self.x, self.y, self.x + self.w, self.y + self.h)


class Group:
    def __init__(self, id, title, rows, kind="floor42", gap=None):
        self.id, self.title, self.rows, self.kind = id, title, rows, kind
        self.gap = gap
        self.x = self.y = self.w = self.h = 0
        self.group = None
        self.shape = "group"

    def rect(self):
        return (self.x, self.y, self.x + self.w, self.y + self.h)


class Edge:
    def __init__(self, src, dst, label="", style="call", sides=None):
        self.src, self.dst, self.label, self.style, self.sides = src, dst, label, style, sides
        self.points = []
        self.label_rect = None
        self.label_lines = []


class Page:
    """A box-and-arrow page laid out on a grid.

    rows: a list of rows, each a list of item ids (None leaves the cell empty); an id repeated in adjacent cells
    spans them. A Group has rows of its own, laid out the same way inside it.
    """

    def __init__(self, name, title, shows, items, rows, edges=(), gap=None, legend_edges=None, note_width=None):
        self.name, self.title, self.shows = name, title, shows
        self.items = {i.id: i for i in items}
        if len(self.items) != len(items):
            raise BuildError(f"{name}: duplicate ids")
        self.rows, self.edges, self.gap = rows, list(edges), gap
        self.legend_edges = legend_edges
        self.kind = "graph"
        self.extra = []  # decorations: (type, data)


class Seq:
    """A sequence diagram: lifelines left to right, numbered messages top to bottom."""

    def __init__(self, name, title, shows, parts, msgs, notes=()):
        self.name, self.title, self.shows = name, title, shows
        self.parts = parts  # [(id, title, sub, kind)]
        self.msgs = msgs  # [(from, to, text, style)]; style 'call' or 'flow'
        self.notes = notes  # [text lines] boxes under the messages
        self.kind = "seq"


class Table:
    """A table page: columns of cards, each a title and a list of lines."""

    def __init__(self, name, title, shows, columns, legend=None):
        self.name, self.title, self.shows = name, title, shows
        self.columns = columns  # [[Node(shape='card'), ...], ...]
        self.legend = legend or []
        self.kind = "table"


class Matrix:
    def __init__(self, name, title, shows, rows, cols, cells, note=None):
        self.name, self.title, self.shows = name, title, shows
        self.rlabels, self.clabels, self.cells, self.note = rows, cols, cells, note
        self.kind = "matrix"


def round_up(v, s=STEP):
    return int(-(-v // s) * s)


# ---------------------------------------------------------------- grid layout

def grid_cells(rows, where):
    spans = {}
    for r, row in enumerate(rows):
        for c, iid in enumerate(row):
            if iid is None:
                continue
            spans.setdefault(iid, []).append((r, c))
    out = {}
    for iid, cells in spans.items():
        rs = sorted({r for r, _ in cells})
        cs = sorted({c for _, c in cells})
        if len(cells) != len(rs) * len(cs) or rs != list(range(rs[0], rs[-1] + 1)) or cs != list(range(cs[0], cs[-1] + 1)):
            raise BuildError(f"{where}: item {iid} does not span a rectangle")
        out[iid] = (rs[0], cs[0], len(rs), len(cs))
    ncols = max(len(r) for r in rows)
    return out, len(rows), ncols


class Layout:
    def __init__(self, page):
        self.page = page
        self.gaps = {}  # (grid id, axis, index) -> px
        self.gap_rects = []  # (rect, key)

    def gap(self, gid, axis, k, default):
        return self.gaps.setdefault((gid, axis, k), default)

    def measure(self, gid, rows):
        cells, nr, nc = grid_cells(rows, f"{self.page.name}/{gid}")
        g = self.page.items[gid].gap if gid in self.page.items else self.page.gap
        dc, dr = (g if isinstance(g, tuple) else (g, g)) if g else (GAP, GAP)
        sizes = {}
        for iid in cells:
            it = self.page.items.get(iid)
            if it is None:
                raise BuildError(f"{self.page.name}: unknown item {iid}")
            if isinstance(it, Group):
                iw, ih = self.measure(it.id, it.rows)
                sizes[iid] = (iw + 2 * GPAD, ih + 2 * GPAD + GHEAD)
                tw = text_w(it.title, 12, True) + 2 * BPAD
                sizes[iid] = (max(sizes[iid][0], round_up(tw)), sizes[iid][1])
            else:
                sizes[iid] = it.size()
        cw, rh = [0] * nc, [0] * nr
        cg = [self.gap(gid, "c", k, dc) for k in range(nc - 1)]
        rg = [self.gap(gid, "r", k, dr) for k in range(nr - 1)]
        for iid, (r, c, rs, cs) in sorted(cells.items(), key=lambda kv: kv[1][2] * kv[1][3]):
            w, h = sizes[iid]
            have = sum(cw[c:c + cs]) + sum(cg[c:c + cs - 1])
            if w > have:
                for k in range(c, c + cs):
                    cw[k] += round_up((w - have) / cs)
            have = sum(rh[r:r + rs]) + sum(rg[r:r + rs - 1])
            if h > have:
                for k in range(r, r + rs):
                    rh[k] += round_up((h - have) / rs)
        rbh = [0] * nr
        for iid, (r, c, rs, cs) in cells.items():
            if rs == 1 and not isinstance(self.page.items[iid], Group):
                rbh[r] = max(rbh[r], sizes[iid][1])
        self.__dict__.setdefault("grids", {})[gid] = (cells, cw, rh, cg, rg, sizes)
        self.__dict__.setdefault("rbh", {})[gid] = rbh
        return sum(cw) + sum(cg), sum(rh) + sum(rg)

    def place(self, gid, x0, y0, parent):
        cells, cw, rh, cg, rg, sizes = self.grids[gid]
        xs, ys = [x0], [y0]
        for k in range(len(cw) - 1):
            xs.append(xs[-1] + cw[k] + cg[k])
        for k in range(len(rh) - 1):
            ys.append(ys[-1] + rh[k] + rg[k])
        top, bot = y0, ys[-1] + rh[-1]
        left, right = x0, xs[-1] + cw[-1]
        for k in range(len(cg)):
            self.gap_rects.append(((xs[k] + cw[k], top, xs[k + 1], bot), (gid, "c", k)))
        for k in range(len(rg)):
            self.gap_rects.append(((left, ys[k] + rh[k], right, ys[k + 1]), (gid, "r", k)))
        for iid, (r, c, rs, cs) in cells.items():
            it = self.page.items[iid]
            cx, cy = xs[c], ys[r]
            cwid = sum(cw[c:c + cs]) + sum(cg[c:c + cs - 1])
            chei = sum(rh[r:r + rs]) + sum(rg[r:r + rs - 1])
            it.group = parent
            if isinstance(it, Group):
                w, h = sizes[iid]
                it.w, it.h = cwid, h if rs == 1 else chei
                it.x, it.y = cx, cy + (0 if rs > 1 else (chei - it.h) // 2)
                iw = it.w - 2 * GPAD
                inner_w = sum(self.grids[iid][1]) + sum(self.grids[iid][3])
                self.place(iid, it.x + GPAD + (iw - inner_w) // 2, it.y + GPAD + GHEAD, it)
            else:
                w, h = sizes[iid]
                stretch = it.shape in ("box", "state", "card", "note")
                it.w = cwid if stretch and cs == 1 else (max(w, cwid) if stretch else w)
                it.h = max(h, self.rbh[gid][r]) if stretch and rs == 1 and it.shape not in ("note", "card") else h
                it.x = cx + (cwid - it.w) // 2
                it.y = cy + (chei - it.h) // 2


def layout_graph(page):
    lay = getattr(page, "_lay", None) or Layout(page)
    page._lay = lay
    lay.gap_rects = []
    w, h = lay.measure("_page", page.rows)
    lay.place("_page", MARGIN, TOP, None)
    page.width, page.height = w + 2 * MARGIN, h + TOP + MARGIN
    return lay


# ---------------------------------------------------------------- routing

def inflate(r, d):
    return (r[0] - d, r[1] - d, r[2] + d, r[3] + d)


def inside(px, py, r):
    return r[0] < px < r[2] and r[1] < py < r[3]


def boxes(page):
    return [i for i in page.items.values() if not isinstance(i, Group)]


def groups(page):
    return [i for i in page.items.values() if isinstance(i, Group)]


def ancestors(it):
    out = []
    while it is not None:
        out.append(it)
        it = it.group
    return out


def header_rect(g):
    return (g.x + 4, g.y + 2, g.x + 8 + text_w(g.title, 12, True) + 8, g.y + GHEAD + 10)


def route_all(page):
    W, H = page.width + 200, page.height + 200
    nx, ny = W // STEP, H // STEP
    blocked = bytearray(nx * ny)
    penal = [0] * (nx * ny)
    obst = [inflate(b.rect(), CLEAR) for b in boxes(page)] + [header_rect(g) for g in groups(page)]
    for r in obst:
        for i in range(max(0, int(r[0] // STEP)), min(nx, int(r[2] // STEP) + 1)):
            for j in range(max(0, int(r[1] // STEP)), min(ny, int(r[3] // STEP) + 1)):
                if inside(i * STEP, j * STEP, r):
                    blocked[j * nx + i] = 1
    for g in groups(page):
        r = g.rect()
        for i in range(int(r[0] // STEP), int(r[2] // STEP) + 1):
            for j in range(int(r[1] // STEP), int(r[3] // STEP) + 1):
                if 0 <= i < nx and 0 <= j < ny and inside(i * STEP, j * STEP, r):
                    penal[j * nx + i] += 1
    used = {}  # point index -> set of orientations ('h','v')
    near = {}  # point index next to a used one -> orientations
    ports = {}  # point index -> uses
    DIRS = {"up": (0, -1), "down": (0, 1), "left": (-1, 0), "right": (1, 0)}
    for e in page.edges:
        s, t = page.items[e.src], page.items[e.dst]
        sides_s = [e.sides[0]] if e.sides else ["top", "bottom", "left", "right"]
        sides_t = [e.sides[1]] if e.sides else ["top", "bottom", "left", "right"]
        if s is t and not e.sides:
            sides_s, sides_t = ["right"], ["top"]
        sgroups, tgroups = ancestors(s), ancestors(t)

        def portpts(it, side):
            x0, y0, x1, y1 = it.rect()
            pts = []
            if side in ("top", "bottom"):
                y = (int((y0 - CLEAR - 1) // STEP) * STEP) if side == "top" else (int((y1 + CLEAR) // STEP + 1) * STEP)
                xs = range(int((x0 + 8) // STEP + 1) * STEP, int(x1 - 8), STEP)
                for x in xs:
                    pts.append((x, y, x, y0 if side == "top" else y1, abs(x - (x0 + x1) / 2)))
            else:
                x = (int((x0 - CLEAR - 1) // STEP) * STEP) if side == "left" else (int((x1 + CLEAR) // STEP + 1) * STEP)
                ys = range(int((y0 + 8) // STEP + 1) * STEP, int(y1 - 8), STEP)
                for y in ys:
                    pts.append((x, y, x0 if side == "left" else x1, y, abs(y - (y0 + y1) / 2)))
            return pts

        outdir = {"top": "up", "bottom": "down", "left": "left", "right": "right"}
        indir = {"top": "down", "bottom": "up", "left": "right", "right": "left"}
        goals = {}
        for side in sides_t:
            for gx, gy, bx, by, off in portpts(t, side):
                i, j = gx // STEP, gy // STEP
                if 0 <= i < nx and 0 <= j < ny and not blocked[j * nx + i]:
                    k = j * nx + i
                    cost = off / STEP * 0.6 + 25 * ports.get(k, 0)
                    goals.setdefault(k, []).append((indir[side], (bx, by), cost))
        tr = inflate(t.rect(), CLEAR)
        heap, best, prev = [], {}, {}
        tick = itertools.count()
        for side in sides_s:
            for sx, sy, bx, by, off in portpts(s, side):
                i, j = sx // STEP, sy // STEP
                if not (0 <= i < nx and 0 <= j < ny) or blocked[j * nx + i]:
                    continue
                k = j * nx + i
                st = (k, outdir[side])
                c0 = off / STEP * 0.6 + 25 * ports.get(k, 0)
                if c0 < best.get(st, 1e18):
                    best[st] = c0
                    prev[st] = ("start", (bx, by))
                    heapq.heappush(heap, (c0, c0, next(tick), st))

        def h(k):
            i, j = k % nx, k // nx
            x, y = i * STEP, j * STEP
            dx = max(tr[0] - x, 0, x - tr[2])
            dy = max(tr[1] - y, 0, y - tr[3])
            return (dx + dy) / STEP

        own = [g.rect() for g in sgroups + tgroups if isinstance(g, Group)]
        found = None
        while heap:
            f, g, _, st = heapq.heappop(heap)
            if st[0] == "GOAL":
                found = st
                break
            if g > best.get(st, 1e18):
                continue
            k, d = st
            for gd, bpt, gc in goals.get(k, []):
                cost = g + gc + (6 if gd != d else 0)
                gs = ("GOAL", k, gd, bpt)
                if cost < best.get(gs, 1e18):
                    best[gs] = cost
                    prev[gs] = st
                    heapq.heappush(heap, (cost, cost, next(tick), gs))
            i, j = k % nx, k // nx
            back = tuple(-v for v in DIRS[d])
            for nd, (di, dj) in DIRS.items():
                if (di, dj) == back:
                    continue
                ni, nj = i + di, j + dj
                if not (0 <= ni < nx and 0 <= nj < ny):
                    continue
                nk = nj * nx + ni
                if blocked[nk]:
                    continue
                o = "h" if di else "v"
                u = used.get(nk, ())
                cost = g + 1 + (6 if nd != d else 0) + (5 if o in u else (1 if u else 0)) + (2 if o in near.get(nk, ()) else 0)
                if penal[nk] and not any(inside(ni * STEP, nj * STEP, r) for r in own):
                    cost += 2  # crossing a group that holds neither end
                ns = (nk, nd)
                if cost < best.get(ns, 1e18):
                    best[ns] = cost
                    prev[ns] = st
                    heapq.heappush(heap, (cost + h(nk), cost, next(tick), ns))
        if not found:
            raise BuildError(f"{page.name}: no route for edge {e.src} -> {e.dst}")
        # walk back
        _, gk, gd, gbpt = found
        chain = []
        st = prev[found]
        while True:
            p = prev[st]
            chain.append(st[0])
            if isinstance(p, tuple) and p[0] == "start":
                sbpt = p[1]
                break
            st = p
        chain.reverse()
        pts = [sbpt] + [((k % nx) * STEP, (k // nx) * STEP) for k in chain] + [gbpt]
        for a, b in zip(chain, chain[1:]):
            o = "h" if abs(a - b) == 1 else "v"
            used.setdefault(a, set()).add(o)
            used.setdefault(b, set()).add(o)
            for k in (a, b):  # keep the next edge one lane away from this one, so parallel lines stay apart
                for nb in ((k - 1, k + 1) if o == "v" else (k - nx, k + nx)):
                    near.setdefault(nb, set()).add(o)
        for end in (chain[0], chain[-1]):
            for nb in (end - 1, end + 1, end - nx, end + nx):
                ports[nb] = ports.get(nb, 0) + 0.4
            ports[end] = ports.get(end, 0) + 1
        e.points = simplify(pts)


def simplify(pts):
    out = [pts[0]]
    for p in pts[1:]:
        if p == out[-1]:
            continue
        if len(out) >= 2 and (out[-2][0] == out[-1][0] == p[0] or out[-2][1] == out[-1][1] == p[1]):
            out[-1] = p
        else:
            out.append(p)
    return out


# ---------------------------------------------------------------- edge labels

def rects_overlap(a, b, pad=0):
    return a[0] < b[2] + pad and b[0] < a[2] + pad and a[1] < b[3] + pad and b[1] < a[3] + pad


def seg_hits_rect(p, q, r):
    """True when the segment p-q passes through the interior of r (axis-parallel segment)."""
    x0, y0, x1, y1 = r
    if p[1] == q[1]:
        y = p[1]
        a, b = sorted((p[0], q[0]))
        return y0 < y < y1 and a < x1 and b > x0
    x = p[0]
    a, b = sorted((p[1], q[1]))
    return x0 < x < x1 and a < y1 and b > y0


def label_lines(text):
    if not text:
        return []
    if text_w(text, 10) <= 190:
        return [text]
    return wrap(text, 170)


def place_labels(page):
    """Places every edge label on its own edge; returns the failures as (edge, longest segment)."""
    fixed = [inflate(b.rect(), 3) for b in boxes(page)] + [header_rect(g) for g in groups(page)]
    taken = []
    fails = []
    for e in page.edges:
        e.label_lines = label_lines(e.label)
        e.label_rect = None
        if not e.label_lines:
            continue
        lw = max(text_w(l, 10) for l in e.label_lines) + 8
        lh = 13 * len(e.label_lines) + 4
        segs = sorted(zip(e.points, e.points[1:]), key=lambda pq: -abs(pq[0][0] - pq[1][0]) - abs(pq[0][1] - pq[1][1]))
        cands = []
        for p, q in segs:
            for f in (0.5, 0.35, 0.65, 0.2, 0.8):
                mx, my = p[0] + (q[0] - p[0]) * f, p[1] + (q[1] - p[1]) * f
                cands.append((mx - lw / 2, my - lh / 2, mx + lw / 2, my + lh / 2))
                if p[0] == q[0]:
                    cands.append((mx + 5, my - lh / 2, mx + 5 + lw, my + lh / 2))
                    cands.append((mx - 5 - lw, my - lh / 2, mx - 5, my + lh / 2))
                else:
                    cands.append((mx - lw / 2, my - lh - 3, mx + lw / 2, my - 3))
        others = [(p, q) for o in page.edges if o is not e for p, q in zip(o.points, o.points[1:])]
        for g in groups(page):
            x0, y0, x1, y1 = g.rect()
            others += [((x0, y0), (x1, y0)), ((x0, y1), (x1, y1)), ((x0, y0), (x0, y1)), ((x1, y0), (x1, y1))]
        chosen = None
        for strict in (True, False):
            for r in cands:
                if any(rects_overlap(r, f) for f in fixed) or any(rects_overlap(r, t, 2) for t in taken):
                    continue
                if r[0] < 0 or r[1] < TOP - 10:
                    continue
                if strict and any(seg_hits_rect(p, q, r) for p, q in others):
                    continue
                chosen = r
                break
            if chosen:
                break
        if chosen is None:
            fails.append((e, segs[0], lw, lh))
        else:
            e.label_rect = chosen
            taken.append(chosen)
    return fails


def widen_for(lay, fails):
    changed = False
    for e, (p, q), lw, lh in fails:
        mx, my = (p[0] + q[0]) / 2, (p[1] + q[1]) / 2
        hits = [(r, k) for r, k in lay.gap_rects if r[0] <= mx <= r[2] and r[1] <= my <= r[3]]
        if not hits:
            continue
        r, k = min(hits, key=lambda rk: (rk[0][2] - rk[0][0]) * (rk[0][3] - rk[0][1]))
        if k[1] == "c":
            need = (lw if p[1] == q[1] or True else lw) + 30
            have = r[2] - r[0]
        else:
            need = lh + 40
            have = r[3] - r[1]
        add = max(round_up(need - have), STEP * 4) if need > have else STEP * 4
        lay.gaps[k] = lay.gaps[k] + add
        changed = True
    return changed


# ---------------------------------------------------------------- checks

def check_graph(page):
    errs = []
    bs = boxes(page)
    allit = list(page.items.values())
    for i, a in enumerate(allit):
        for b in allit[i + 1:]:
            if a in ancestors(b) or b in ancestors(a):
                continue
            if rects_overlap(a.rect(), b.rect()):
                errs.append(f"overlap: {a.id} and {b.id}")
    for g in groups(page):
        for it in allit:
            if it.group is g:
                r, gr = it.rect(), g.rect()
                if r[0] - gr[0] < GPAD or gr[2] - r[2] < GPAD or r[1] - gr[1] < GPAD + GHEAD or gr[3] - r[3] < GPAD:
                    errs.append(f"group {g.id} does not hold {it.id} with {GPAD} px padding")
        if text_w(g.title, 12, True) + 2 * BPAD > g.w:
            errs.append(f"group title does not fit: {g.id}")
    for b in bs:
        errs += label_fits(b)
    for e in page.edges:
        s, t = page.items[e.src], page.items[e.dst]
        segs = list(zip(e.points, e.points[1:]))
        for n, (p, q) in enumerate(segs):
            if p[0] != q[0] and p[1] != q[1]:
                errs.append(f"edge {e.src}->{e.dst} has a diagonal segment")
            for b in bs:
                if seg_hits_rect(p, q, b.rect()):
                    errs.append(f"edge {e.src}->{e.dst} crosses box {b.id}")
        if e.label and e.label_rect is None:
            errs.append(f"edge {e.src}->{e.dst}: no room for its label")
        if e.label_rect:
            for b in bs:
                if rects_overlap(e.label_rect, b.rect()):
                    errs.append(f"label of {e.src}->{e.dst} covers {b.id}")
    return errs


def label_fits(b):
    errs = []
    if b.shape in ("start", "end"):
        return errs
    if b.shape == "pill":
        if text_w(b.title, 11, True) + 2 * BPAD > b.w + 0.5:
            errs.append(f"label does not fit: {b.id}")
        return errs
    if len(b.sub) > b.maxlines:
        errs.append(f"box {b.id}: {len(b.sub)} small lines, at most {b.maxlines}")
    need_w = max([text_w(b.title, 12, True)] + [text_w(s, 10, mono=b.mono) for s in b.sub]) + 2 * BPAD
    need_h = 10 + (16 if b.title else 0) + 13 * len(b.sub) + 10
    if need_w > b.w + 0.5 or need_h > b.h + 0.5:
        errs.append(f"label does not fit: {b.id} ({need_w:.0f}x{need_h} in {b.w}x{b.h})")
    return errs


# ---------------------------------------------------------------- legend

def add_legend(page, kinds, edge_styles, y):
    """Legend row at y: swatches for the kinds and edge styles used on the page."""
    items, x = [], MARGIN
    for k in kinds:
        label = KINDS[k][2]
        if not label:
            continue
        w = 16 + 6 + text_w(label, 10) + 18
        items.append(("box", k, label, x, y))
        x += w
    for s in edge_styles:
        label = EDGE_STYLES[s][2]
        if not label:
            continue
        w = 30 + 6 + text_w(label, 10) + 18
        items.append(("edge", s, label, x, y))
        x += w
    page.legend_items = items
    return x


# ---------------------------------------------------------------- pages of other kinds

def build_seq(page):
    parts = page.parts
    idx = {p[0]: i for i, p in enumerate(parts)}
    heads = []
    for pid, title, sub, kind in parts:
        n = Node(pid, title, sub, kind)
        heads.append(n)
    sizes = [n.size() for n in heads]
    hh = max(h for _, h in sizes)
    # column centres: gaps between neighbours, widened until each message label fits its span
    gaps = [max(GAP + (sizes[i][0] + sizes[i + 1][0]) / 2, 140) for i in range(len(parts) - 1)]
    msgs = []
    for n, (a, b, text, style) in enumerate(page.msgs, 1):
        if a not in idx or b not in idx:
            raise BuildError(f"{page.name}: message {n} names an unknown lifeline")
        t = f"{n}. {text}"
        lines = [t] if text_w(t, 11) <= 330 else wrap_px(t, 300, 11)
        msgs.append((idx[a], idx[b], lines, style))
    for _ in range(50):
        ok = True
        for i, j, lines, style in msgs:
            lw = max(text_w(l, 11) for l in lines)
            lo, hi = min(i, j), max(i, j)
            if lo == hi:
                need, lo2, hi2 = lw + 50, lo, lo + 1
                if hi2 >= len(parts):
                    continue
            else:
                need, lo2, hi2 = lw + 24, lo, hi
            have = sum(gaps[lo2:hi2])
            if have < need:
                ok = False
                for k in range(lo2, hi2):
                    gaps[k] += (need - have) / (hi2 - lo2) + 1
        if ok:
            break
    xs = [round_up(MARGIN + sizes[0][0] / 2)]
    for g in gaps:
        xs.append(xs[-1] + round_up(g))
    for n, (w, h), x in zip(heads, sizes, xs):
        n.w, n.h, n.x, n.y = w, hh, x - w // 2, TOP
    y = TOP + hh + 34
    rows = []
    for i, j, lines, style in msgs:
        rows.append((i, j, lines, style, y))
        y += 13 * len(lines) + (30 if i != j else 40)
    life_end = y
    notes = []
    ny = life_end + 30
    right = max(n.x + n.w for n in heads)
    lastx = xs[-1]
    for k, text in enumerate(page.notes):
        lines = text if isinstance(text, list) else [text]
        nd = Node(f"note{k}", "", lines, "note", "note")
        w, h = nd.size()
        nd.w, nd.h, nd.x, nd.y = w, h, MARGIN, ny
        notes.append(nd)
        ny += h + 30
    for i, j, lines, style, yy in rows:
        if i == j:
            right = max(right, xs[i] + 40 + max(text_w(l, 11) for l in lines) + 20)
    width = max([right, lastx] + [n.x + n.w for n in notes]) + MARGIN
    page.heads, page.xs, page.rows, page.life_end, page.notes_n = heads, xs, rows, life_end, notes
    page.width, page.height = width, ny + 60
    errs = []
    for h1 in heads:
        errs += label_fits(h1)
    for a, b in zip(heads, heads[1:]):
        if rects_overlap(a.rect(), b.rect(), GAP - 1):
            errs.append(f"lifelines {a.id} and {b.id} closer than {GAP} px")
    lab = []
    for i, j, lines, style, yy in rows:
        lw = max(text_w(l, 11) for l in lines)
        lh = 13 * len(lines)
        if i == j:
            r = (xs[i] + 34, yy - 4, xs[i] + 34 + lw, yy - 4 + lh)
            if i + 1 < len(xs) and r[2] > xs[i + 1] - 4:
                errs.append(f"{page.name}: self message at y {yy} runs into the next lifeline")
        else:
            lo, hi = sorted((xs[i], xs[j]))
            cx = (lo + hi) / 2
            r = (cx - lw / 2, yy - lh - 3, cx + lw / 2, yy - 3)
            if r[0] < lo + 6 or r[2] > hi - 6:
                errs.append(f"{page.name}: message label wider than its span at y {yy}")
        for o in lab:
            if rects_overlap(r, o):
                errs.append(f"{page.name}: two message labels overlap at y {yy}")
        lab.append(r)
    for nd in notes:
        errs += label_fits(nd)
    page.seq_labels = lab
    page.legend_y = page.height - 40
    kinds = []
    for p in parts:
        if p[3] not in kinds:
            kinds.append(p[3])
    styles = sorted({m[3] for m in page.msgs}, key=list(EDGE_STYLES).index)
    add_legend(page, kinds, styles, page.legend_y)
    return errs


def wrap_px(s, width, size):
    words, lines, cur = s.split(" "), [], ""
    for w in words:
        nxt = (cur + " " + w).strip()
        if cur and text_w(nxt, size) > width:
            lines.append(cur)
            cur = "   " + w
        else:
            cur = nxt
    lines.append(cur)
    return lines


def build_table(page):
    x = MARGIN
    errs = []
    bottom = TOP
    page.cards = []
    for col in page.columns:
        y, cw = TOP, 0
        for card in col:
            w, h = card.size()
            cw = max(cw, w)
        for card in col:
            w, h = card.size()
            card.x, card.y, card.w, card.h = x, y, cw, h
            errs += label_fits(card)
            page.cards.append(card)
            y += h + 30
        bottom = max(bottom, y)
        x += cw + 40
    page.width = x - 40 + MARGIN
    page.height = bottom + 50
    for i, a in enumerate(page.cards):
        for b in page.cards[i + 1:]:
            if rects_overlap(a.rect(), b.rect()):
                errs.append(f"cards {a.id} and {b.id} overlap")
    page.legend_y = page.height - 40
    kinds = []
    for c in page.cards:
        if c.kind not in kinds:
            kinds.append(c.kind)
    add_legend(page, kinds, [], page.legend_y)
    page.legend_items += [("text", None, t, None, None) for t in page.legend]
    return errs


def build_matrix(page):
    cw0 = max(text_w(r, 11, True) for r in page.rlabels) + 24
    cws = [max(text_w(c, 11, True) + 24, 56) for c in page.clabels]
    rh = 26
    page.mx = (MARGIN, TOP + 30, cw0, cws, rh)
    page.width = MARGIN * 2 + cw0 + sum(cws)
    y = TOP + 30 + rh * (len(page.rlabels) + 1)
    page.notes_n = []
    if page.note:
        nd = Node("note", "", page.note, "note", "note")
        w, h = nd.size()
        nd.x, nd.y, nd.w, nd.h = MARGIN, y + 30, w, h
        page.notes_n.append(nd)
        y += 30 + h
        page.width = max(page.width, w + 2 * MARGIN)
    page.height = y + 40
    page.legend_items = []
    return []


# ---------------------------------------------------------------- driver for graph pages

def build_graph(page):
    for it in page.items.values():
        if isinstance(it, Group):
            continue
    for e in page.edges:
        for end in (e.src, e.dst):
            if end not in page.items:
                raise BuildError(f"{page.name}: edge names unknown item {end}")
    for attempt in range(14):
        lay = layout_graph(page)
        route_all(page)
        fails = place_labels(page)
        if not fails:
            break
        if not widen_for(lay, fails):
            break
    bottom = max(i.y + i.h for i in page.items.values())
    kinds = []
    for it in page.items.values():
        k = it.kind if not isinstance(it, Group) or it.kind != "floor42" else None
        if isinstance(it, Node) and it.shape == "state":
            k = "state"
        if isinstance(it, Node) and it.shape in ("start", "end", "pill"):
            k = None
        if k and k not in kinds:
            kinds.append(k)
    styles = page.legend_edges if page.legend_edges is not None else sorted({e.style for e in page.edges}, key=list(EDGE_STYLES).index)
    page.legend_y = bottom + 40
    right = add_legend(page, kinds, styles, page.legend_y)
    page.height = page.legend_y + 50
    page.width = max(page.width, right + MARGIN, text_w(page.title, 18, True) + 2 * MARGIN, text_w(page.shows, 12) + 2 * MARGIN)
    errs = check_graph(page)
    return errs


def build_page(page):
    fn = {"graph": build_graph, "seq": build_seq, "table": build_table, "matrix": build_matrix}[page.kind]
    errs = fn(page)
    page.width = int(max(page.width, text_w(page.title, 18, True) + 2 * MARGIN, text_w(page.shows, 12) + 2 * MARGIN))
    page.height = int(page.height)
    if "—" in page.title + page.shows:
        errs.append("long dash in a title")
    return errs


# ---------------------------------------------------------------- output: SVG

def esc(s):
    return html.escape(s, quote=True)


def svg_text(x, y, s, size=12, bold=False, color="#222", anchor="start", mono=False):
    fam = "Consolas, 'Courier New', monospace" if mono else "'Segoe UI', Arial, Helvetica, sans-serif"
    weight = 'font-weight="bold" ' if bold else ""
    return (f'<text x="{x:.1f}" y="{y:.1f}" font-family="{fam}" font-size="{size}" '
            f'{weight}fill="{color}" text-anchor="{anchor}" xml:space="preserve">{esc(s)}</text>')


def svg_node(n, out):
    fill, stroke, _ = KINDS[n.kind]
    x, y, w, h = n.x, n.y, n.w, n.h
    if n.shape == "start":
        out.append(f'<circle cx="{x + 11}" cy="{y + 11}" r="9" fill="#333"/>')
        return
    if n.shape == "end":
        out.append(f'<circle cx="{x + 11}" cy="{y + 11}" r="10" fill="#fff" stroke="#333" stroke-width="2"/>'
                   f'<circle cx="{x + 11}" cy="{y + 11}" r="5.5" fill="#333"/>')
        return
    dash = ' stroke-dasharray="6 4"' if n.kind == "planned" or n.shape == "note" else ""
    rx = 14 if n.shape == "state" else (13 if n.shape == "pill" else 4)
    if n.shape == "pill":
        out.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}" fill="#333"/>')
        out.append(svg_text(x + w / 2, y + 17, n.title, 11, True, "#fff", "middle"))
        return
    if n.shape == "state":
        fill = "#ffffff"
    out.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}" fill="{fill}" stroke="{stroke}" stroke-width="1.3"{dash}/>')
    total = (16 if n.title else 0) + 13 * len(n.sub)
    ty = y + (h - total) / 2
    anchor, tx = ("middle", x + w / 2) if n.shape in ("box", "state") else ("start", x + BPAD)
    if n.title:
        out.append(svg_text(tx, ty + 12, n.title, 12, True, "#111", anchor))
        ty += 16
    for s in n.sub:
        out.append(svg_text(tx, ty + 10, s, 10, False, "#444", anchor, n.mono))
        ty += 13


def arrow(color, p, q):
    dx, dy = q[0] - p[0], q[1] - p[1]
    ln = max((dx * dx + dy * dy) ** 0.5, 1)
    ux, uy = dx / ln, dy / ln
    a = (q[0] - ux * 9 - uy * 4, q[1] - uy * 9 + ux * 4)
    b = (q[0] - ux * 9 + uy * 4, q[1] - uy * 9 - ux * 4)
    return f'<path d="M{q[0]},{q[1]} L{a[0]:.1f},{a[1]:.1f} L{b[0]:.1f},{b[1]:.1f} Z" fill="{color}"/>'


def legend_svg(page, out):
    for typ, k, label, x, y in getattr(page, "legend_items", []):
        if typ == "box":
            fill, stroke, _ = KINDS[k]
            rx = 6 if k == "state" else 2
            dash = ' stroke-dasharray="4 3"' if k == "planned" else ""
            out.append(f'<rect x="{x}" y="{y}" width="16" height="12" rx="{rx}" fill="{fill if k != "state" else "#fff"}" stroke="{stroke}"{dash}/>')
            out.append(svg_text(x + 22, y + 10, label, 10, False, "#444"))
        elif typ == "edge":
            color, dashed, _ = EDGE_STYLES[k]
            d = ' stroke-dasharray="5 4"' if dashed else ""
            out.append(f'<line x1="{x}" y1="{y + 6}" x2="{x + 24}" y2="{y + 6}" stroke="{color}" stroke-width="1.4"{d}/>')
            out.append(arrow(color, (x, y + 6), (x + 30, y + 6)))
            out.append(svg_text(x + 36, y + 10, label, 10, False, "#444"))
    texts = [i for i in getattr(page, "legend_items", []) if i[0] == "text"]
    if texts:
        x = max([i[3] + 60 + text_w(i[2], 10) for i in page.legend_items if i[0] != "text"] or [MARGIN])
        for t in texts:
            out.append(svg_text(x, page.legend_y + 10, t[2], 10, False, "#444"))
            x += text_w(t[2], 10) + 24


def svg_page(page):
    out = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{page.width}" height="{page.height}" viewBox="0 0 {page.width} {page.height}">',
           f'<rect width="100%" height="100%" fill="#ffffff"/>']
    out.append(svg_text(MARGIN, 34, page.title, 18, True, "#111"))
    out.append(svg_text(MARGIN, 58, page.shows, 12, False, "#555"))
    if page.kind == "graph":
        for g in sorted(groups(page), key=lambda g: len(ancestors(g))):
            fill, stroke, _ = KINDS[g.kind]
            out.append(f'<rect x="{g.x}" y="{g.y}" width="{g.w}" height="{g.h}" rx="6" fill="{fill}" fill-opacity="0.35" stroke="{stroke}" stroke-width="1.4"/>')
            out.append(svg_text(g.x + 12, g.y + 20, g.title, 12, True, "#222"))
        for e in page.edges:
            color, dashed, _ = EDGE_STYLES[e.style]
            d = ' stroke-dasharray="6 4"' if dashed else ""
            pts = " ".join(f"{x},{y}" for x, y in e.points)
            out.append(f'<polyline points="{pts}" fill="none" stroke="{color}" stroke-width="1.4"{d}/>')
            out.append(arrow(color, e.points[-2], e.points[-1]))
        for n in boxes(page):
            svg_node(n, out)
        for e in page.edges:
            if e.label_rect:
                r = e.label_rect
                out.append(f'<rect x="{r[0]:.1f}" y="{r[1]:.1f}" width="{r[2] - r[0]:.1f}" height="{r[3] - r[1]:.1f}" fill="#fff" fill-opacity="0.92"/>')
                for k, l in enumerate(e.label_lines):
                    out.append(svg_text((r[0] + r[2]) / 2, r[1] + 12 + 13 * k, l, 10, False, EDGE_STYLES[e.style][0], "middle"))
    elif page.kind == "seq":
        for n, x in zip(page.heads, page.xs):
            out.append(f'<line x1="{x}" y1="{n.y + n.h}" x2="{x}" y2="{page.life_end}" stroke="#999" stroke-dasharray="5 4"/>')
            svg_node(n, out)
        for (i, j, lines, style, y), r in zip(page.rows, page.seq_labels):
            color, dashed, _ = EDGE_STYLES[style]
            d = ' stroke-dasharray="6 4"' if dashed else ""
            xi, xj = page.xs[i], page.xs[j]
            if i == j:
                pts = [(xi, y - 6), (xi + 26, y - 6), (xi + 26, y + 10), (xi + 1, y + 10)]
                out.append(f'<polyline points="{" ".join(f"{a},{b}" for a, b in pts)}" fill="none" stroke="{color}" stroke-width="1.3"{d}/>')
                out.append(arrow(color, pts[-2], pts[-1]))
            else:
                out.append(f'<line x1="{xi}" y1="{y}" x2="{xj}" y2="{y}" stroke="{color}" stroke-width="1.3"{d}/>')
                out.append(arrow(color, (xi, y), (xj, y)))
            out.append(f'<rect x="{r[0] - 2:.1f}" y="{r[1] - 1:.1f}" width="{r[2] - r[0] + 4:.1f}" height="{r[3] - r[1] + 2:.1f}" fill="#fff" fill-opacity="0.9"/>')
            for k, l in enumerate(lines):
                ax = r[0] if i == j else (r[0] + r[2]) / 2
                out.append(svg_text(ax, r[1] + 10 + 13 * k, l, 11, False, "#222", "start" if i == j else "middle"))
        for nd in page.notes_n:
            svg_node(nd, out)
    elif page.kind == "table":
        for c in page.cards:
            svg_card(c, out)
    elif page.kind == "matrix":
        x0, y0, cw0, cws, rh = page.mx
        out.append(f'<rect x="{x0}" y="{y0}" width="{cw0 + sum(cws)}" height="{rh * (len(page.rlabels) + 1)}" fill="#fff" stroke="#888"/>')
        out.append(svg_text(x0 + 8, y0 + 17, "imports →", 11, True, "#555"))
        x = x0 + cw0
        for c, w in zip(page.clabels, cws):
            out.append(f'<rect x="{x}" y="{y0}" width="{w}" height="{rh}" fill="#eef3ea" stroke="#bbb"/>')
            out.append(svg_text(x + w / 2, y0 + 17, c, 11, True, "#222", "middle"))
            x += w
        for r, rl in enumerate(page.rlabels):
            y = y0 + rh * (r + 1)
            out.append(f'<rect x="{x0}" y="{y}" width="{cw0}" height="{rh}" fill="#eef3ea" stroke="#bbb"/>')
            out.append(svg_text(x0 + 8, y + 17, rl, 11, True, "#222"))
            x = x0 + cw0
            for c, w in enumerate(cws):
                v = page.cells.get((r, c))
                fill = "#d5e8d4" if v else "#fff"
                out.append(f'<rect x="{x}" y="{y}" width="{w}" height="{rh}" fill="{fill}" stroke="#ddd"/>')
                if v:
                    out.append(svg_text(x + w / 2, y + 17, str(v), 11, False, "#222", "middle"))
                x += w
        for nd in page.notes_n:
            svg_node(nd, out)
    legend_svg(page, out)
    out.append("</svg>")
    return "\n".join(out)


def svg_card(c, out):
    fill, stroke, _ = KINDS[c.kind]
    out.append(f'<rect x="{c.x}" y="{c.y}" width="{c.w}" height="{c.h}" rx="4" fill="#fff" stroke="{stroke}" stroke-width="1.3"/>')
    out.append(f'<rect x="{c.x}" y="{c.y}" width="{c.w}" height="26" rx="4" fill="{fill}" stroke="{stroke}" stroke-width="1.3"/>')
    out.append(svg_text(c.x + BPAD, c.y + 17, c.title, 12, True, "#111"))
    y = c.y + 26 + 4
    for s in c.sub:
        out.append(svg_text(c.x + BPAD, y + 10, s, 10, False, "#333", "start", c.mono))
        y += 13


# ---------------------------------------------------------------- output: draw.io

class Drawio:
    def __init__(self):
        self.cells, self.n = [], 2

    def nid(self):
        self.n += 1
        return f"c{self.n}"

    def vertex(self, value, style, x, y, w, h, cid=None):
        cid = cid or self.nid()
        self.cells.append(f'<mxCell id="{cid}" value="{esc(value)}" style="{style}" vertex="1" parent="1">'
                          f'<mxGeometry x="{x:.0f}" y="{y:.0f}" width="{w:.0f}" height="{h:.0f}" as="geometry"/></mxCell>')
        return cid

    def edge(self, style, pts, src=None, dst=None, sbox=None, tbox=None, value=""):
        cid = self.nid()
        st = style
        attrs = ""
        geo = []
        if src:
            ex, ey = (pts[0][0] - sbox[0]) / max(sbox[2] - sbox[0], 1), (pts[0][1] - sbox[1]) / max(sbox[3] - sbox[1], 1)
            nx_, ny_ = (pts[-1][0] - tbox[0]) / max(tbox[2] - tbox[0], 1), (pts[-1][1] - tbox[1]) / max(tbox[3] - tbox[1], 1)
            st += f"exitX={ex:.4f};exitY={ey:.4f};exitDx=0;exitDy=0;exitPerimeter=0;entryX={nx_:.4f};entryY={ny_:.4f};entryDx=0;entryDy=0;entryPerimeter=0;"
            attrs = f' source="{src}" target="{dst}"'
        else:
            geo.append(f'<mxPoint x="{pts[0][0]}" y="{pts[0][1]}" as="sourcePoint"/>')
            geo.append(f'<mxPoint x="{pts[-1][0]}" y="{pts[-1][1]}" as="targetPoint"/>')
        inner = pts[1:-1]
        if inner:
            geo.append('<Array as="points">' + "".join(f'<mxPoint x="{x}" y="{y}"/>' for x, y in inner) + "</Array>")
        self.cells.append(f'<mxCell id="{cid}" value="{esc(value)}" style="{st}" edge="1" parent="1"{attrs}>'
                          f'<mxGeometry relative="1" as="geometry">{"".join(geo)}</mxGeometry></mxCell>')


def node_html(n):
    parts = []
    if n.title:
        parts.append(f"<b>{html.escape(n.title)}</b>")
    if n.sub:
        fam = "font-family:Consolas,Courier New,monospace;" if n.mono else ""
        parts.append(f'<span style="font-size:10px;color:#444444;{fam}">' + "<br>".join(html.escape(s) for s in n.sub) + "</span>")
    return "<br>".join(parts)


def drawio_node(d, n, cid=None):
    fill, stroke, _ = KINDS[n.kind]
    if n.shape == "start":
        return d.vertex("", "ellipse;fillColor=#333333;strokeColor=none;", n.x + 2, n.y + 2, 18, 18, cid)
    if n.shape == "end":
        return d.vertex("", "ellipse;shape=doubleEllipse;fillColor=#333333;strokeColor=#333333;", n.x, n.y, 22, 22, cid)
    if n.shape == "pill":
        return d.vertex(f"<b>{html.escape(n.title)}</b>", "rounded=1;arcSize=50;whiteSpace=wrap;html=1;fillColor=#333333;strokeColor=none;fontColor=#ffffff;fontSize=11;", n.x, n.y, n.w, n.h, cid)
    align = "align=center;" if n.shape in ("box", "state") else "align=left;spacingLeft=8;"
    dash = "dashed=1;" if n.kind == "planned" or n.shape == "note" else ""
    if n.shape == "state":
        fill = "#ffffff"
    rounded = "rounded=1;arcSize=30;" if n.shape == "state" else "rounded=1;arcSize=6;"
    style = f"{rounded}whiteSpace=wrap;html=1;fillColor={fill};strokeColor={stroke};fontSize=12;{align}{dash}verticalAlign=middle;"
    if n.shape == "card":
        style = f"swimlane;startSize=26;rounded=1;arcSize=4;html=1;fillColor={fill};strokeColor={stroke};swimlaneFillColor=#ffffff;fontSize=12;align=left;spacingLeft=8;"
        cid = d.vertex(f"<b>{html.escape(n.title)}</b>", style, n.x, n.y, n.w, n.h, cid)
        fam = "fontFamily=Consolas;" if n.mono else ""
        d.vertex("<br>".join(html.escape(s) for s in n.sub), f"text;html=1;align=left;verticalAlign=top;spacingLeft=8;fontSize=10;{fam}",
                 n.x, n.y + 26, n.w, n.h - 26)
        return cid
    return d.vertex(node_html(n), style, n.x, n.y, n.w, n.h, cid)


def drawio_legend(d, page):
    for typ, k, label, x, y in getattr(page, "legend_items", []):
        if typ == "box":
            fill, stroke, _ = KINDS[k]
            dash = "dashed=1;" if k == "planned" else ""
            d.vertex("", f"rounded=1;html=1;fillColor={fill if k != 'state' else '#ffffff'};strokeColor={stroke};{dash}", x, y, 16, 12)
            d.vertex(html.escape(label), "text;html=1;align=left;verticalAlign=middle;fontSize=10;fontColor=#444444;", x + 20, y - 4, text_w(label, 10) + 8, 20)
        elif typ == "edge":
            color, dashed, _ = EDGE_STYLES[k]
            d.edge(f"endArrow=block;endFill=1;html=1;strokeColor={color};{'dashed=1;' if dashed else ''}", [(x, y + 6), (x + 30, y + 6)])
            d.vertex(html.escape(label), "text;html=1;align=left;verticalAlign=middle;fontSize=10;fontColor=#444444;", x + 34, y - 4, text_w(label, 10) + 8, 20)


def drawio_page(page):
    d = Drawio()
    d.vertex(f"<b>{html.escape(page.title)}</b>", "text;html=1;align=left;verticalAlign=middle;fontSize=18;", MARGIN - 4, 18, text_w(page.title, 18, True) + 20, 26)
    d.vertex(html.escape(page.shows), "text;html=1;align=left;verticalAlign=middle;fontSize=12;fontColor=#555555;", MARGIN - 4, 46, text_w(page.shows, 12) + 20, 20)
    if page.kind == "graph":
        ids = {}
        for g in sorted(groups(page), key=lambda g: len(ancestors(g))):
            fill, stroke, _ = KINDS[g.kind]
            ids[g.id] = d.vertex(f"<b>{html.escape(g.title)}</b>", f"rounded=1;arcSize=3;whiteSpace=wrap;html=1;fillColor={fill};opacity=40;strokeColor={stroke};verticalAlign=top;align=left;spacingLeft=8;spacingTop=2;fontSize=12;container=0;", g.x, g.y, g.w, g.h)
        for n in boxes(page):
            ids[n.id] = drawio_node(d, n)
        for e in page.edges:
            color, dashed, _ = EDGE_STYLES[e.style]
            st = f"edgeStyle=none;rounded=0;html=1;endArrow=block;endFill=1;strokeColor={color};strokeWidth=1.3;{'dashed=1;' if dashed else ''}"
            s, t = page.items[e.src], page.items[e.dst]
            d.edge(st, e.points, ids[e.src], ids[e.dst], s.rect(), t.rect())
            if e.label_rect:
                r = e.label_rect
                d.vertex("<br>".join(html.escape(l) for l in e.label_lines), f"text;html=1;align=center;verticalAlign=middle;fontSize=10;fontColor={color};labelBackgroundColor=#ffffff;", r[0], r[1], r[2] - r[0], r[3] - r[1])
    elif page.kind == "seq":
        for n, x in zip(page.heads, page.xs):
            d.edge("endArrow=none;html=1;dashed=1;strokeColor=#999999;", [(x, n.y + n.h), (x, page.life_end)])
            drawio_node(d, n)
        for (i, j, lines, style, y), r in zip(page.rows, page.seq_labels):
            color, dashed, _ = EDGE_STYLES[style]
            st = f"endArrow=block;endFill=1;html=1;rounded=0;strokeColor={color};{'dashed=1;' if dashed else ''}"
            xi, xj = page.xs[i], page.xs[j]
            if i == j:
                d.edge(st, [(xi, y - 6), (xi + 26, y - 6), (xi + 26, y + 10), (xi + 1, y + 10)])
                d.vertex("<br>".join(html.escape(l) for l in lines), "text;html=1;align=left;verticalAlign=top;fontSize=11;spacing=0;", r[0], r[1] - 2, r[2] - r[0] + 6, r[3] - r[1] + 4)
            else:
                d.edge(st, [(xi, y), (xj, y)])
                d.vertex("<br>".join(html.escape(l) for l in lines), "text;html=1;align=center;verticalAlign=bottom;fontSize=11;spacing=0;", r[0] - 4, r[1] - 2, r[2] - r[0] + 8, r[3] - r[1] + 4)
        for nd in page.notes_n:
            drawio_node(d, nd)
    elif page.kind == "table":
        for c in page.cards:
            drawio_node(d, c)
    elif page.kind == "matrix":
        x0, y0, cw0, cws, rh = page.mx
        d.vertex("<b>imports →</b>", "rounded=0;html=1;fillColor=#ffffff;strokeColor=#bbbbbb;align=left;spacingLeft=6;fontSize=11;fontColor=#555555;", x0, y0, cw0, rh)
        x = x0 + cw0
        for c, w in zip(page.clabels, cws):
            d.vertex(f"<b>{html.escape(c)}</b>", "rounded=0;html=1;fillColor=#eef3ea;strokeColor=#bbbbbb;fontSize=11;", x, y0, w, rh)
            x += w
        for r, rl in enumerate(page.rlabels):
            y = y0 + rh * (r + 1)
            d.vertex(f"<b>{html.escape(rl)}</b>", "rounded=0;html=1;fillColor=#eef3ea;strokeColor=#bbbbbb;align=left;spacingLeft=6;fontSize=11;", x0, y, cw0, rh)
            x = x0 + cw0
            for c, w in enumerate(cws):
                v = page.cells.get((r, c))
                d.vertex(str(v) if v else "", f"rounded=0;html=1;fillColor={'#d5e8d4' if v else '#ffffff'};strokeColor=#dddddd;fontSize=11;", x, y, w, rh)
                x += w
        for nd in page.notes_n:
            drawio_node(d, nd)
    drawio_legend(d, page)
    return d.cells


def write_drawio(path, pages):
    out = ['<mxfile host="floor42-tools-diagrams" type="device">']
    for k, p in enumerate(pages, 1):
        cells = drawio_page(p)
        out.append(f'<diagram id="p{k}" name="{esc(p.name)}"><mxGraphModel dx="0" dy="0" grid="1" gridSize="10" guides="1" '
                   f'tooltips="1" connect="1" arrows="1" fold="1" page="1" pageScale="1" pageWidth="{p.width}" '
                   f'pageHeight="{p.height}" math="0" shadow="0"><root><mxCell id="0"/><mxCell id="1" parent="0"/>')
        out += cells
        out.append("</root></mxGraphModel></diagram>")
    out.append("</mxfile>")
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(out) + "\n")


def write_html(path, page):
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(f'<!doctype html><html><head><meta charset="utf-8"><title>{esc(page.title)}</title>'
                f'<style>html,body{{margin:0;background:#fff}}</style></head><body>{svg_page(page)}</body></html>\n')


# ---------------------------------------------------------------- main

def selftest():
    """The checks must catch what they are for: each broken page below has to fail."""
    def page():
        p = Page("selftest", "Self test", "", [
            Group("g", "Group", [["a"]]), Node("a", "Alpha", ["one"]), Node("b", "Beta"), Node("c", "Gamma")],
            [["g", "b", "c"]], [Edge("b", "c"), Edge("g", "c")])
        build_graph(p)
        return p
    p = page()
    assert not check_graph(p), check_graph(p)
    p.items["b"].x = p.items["c"].x
    assert any("overlap" in e for e in check_graph(p))
    p = page()
    p.items["a"].w = 30
    assert any("does not fit" in e for e in check_graph(p))
    p = page()
    p.items["a"].x = p.items["g"].x + 5
    assert any("padding" in e for e in check_graph(p))
    p = page()
    a, c = p.items["a"], p.items["c"]
    p.edges[1].points = [(a.x + a.w, a.y + 20), (c.x, a.y + 20)]  # straight through Beta
    assert any("crosses box b" in e for e in check_graph(p))


def main():
    selftest()
    sys.path.insert(0, HERE)
    sys.modules.setdefault("build", sys.modules[__name__])  # pages.py imports the classes of this very module
    import pages as model

    check_only = "--check" in sys.argv
    errors = []
    built = []
    for fname, plist in model.files(ROOT):
        for p in plist:
            errs = build_page(p)
            errors += [f"{fname} / {p.name}: {e}" for e in errs]
        built.append((fname, plist))
    if errors:
        for e in errors:
            print("FAIL", e)
        raise SystemExit(f"{len(errors)} check(s) failed; nothing written")
    if check_only:
        print("checks passed:", sum(len(p) for _, p in built), "pages")
        return
    os.makedirs(PNG, exist_ok=True)
    for name in os.listdir(PNG):
        if name.endswith(".svg.html"):
            os.remove(os.path.join(PNG, name))
    for fname, plist in built:
        write_drawio(os.path.join(OUT, fname + ".drawio"), plist)
        for k, p in enumerate(plist, 1):
            write_html(os.path.join(PNG, f"{fname}-{k}.svg.html"), p)
        print(f"{fname}.drawio: {len(plist)} page(s): " + "; ".join(p.name for p in plist))
    print("checks passed; render the PNGs with: node tools/diagrams/render.mjs (see README)")


if __name__ == "__main__":
    try:
        main()
    except BuildError as e:
        raise SystemExit(f"FAIL {e}")
