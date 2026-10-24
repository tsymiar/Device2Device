#!/usr/bin/env python3
"""Generate the Device2Device architecture diagram — image/device2device.png.

The layout is data-driven: each layer declares its cards, grid geometry,
optional hub card and intra-layer dependencies; shared helpers draw them.
Fonts are discovered at runtime (Linux / macOS / Windows), so no path
editing is needed to run the script on a different machine.

Usage:  python3 tools/generate_diagram.py
"""

import math
import os
import sys

from PIL import Image, ImageDraw, ImageFont, ImageOps

# ==== OUTPUT SIZE ====
W, H = 6000, 12000   # H is only a ceiling — the image is cropped to its ink
BG = (255, 255, 255, 255)
S = 2.0              # scale factor
EDGE_MARGIN = int(60 * S)   # guaranteed whitespace around content in the output

# ==== ENHANCED COLOR PALETTE — natural gradient from UI to External ====
# Layer 1 — Indigo (Presentation / UI)
INDIGO_50  = (232, 234, 246); INDIGO_100 = (197, 202, 233)
INDIGO_400 = (121, 134, 203); INDIGO_500 = ( 63,  81, 181)
INDIGO_700 = ( 48,  63, 159); INDIGO_800 = ( 40,  53, 147)
INDIGO_900 = ( 26,  35, 126)

# Layer 2 — Teal (Background Services)
TEAL_50  = (224, 242, 241); TEAL_100 = (178, 223, 219)
TEAL_400 = ( 38, 166, 154); TEAL_500 = (  0, 150, 136)
TEAL_700 = (  0, 121, 107); TEAL_800 = (  0, 105,  92)
TEAL_900 = (  0,  77,  64)

# Layer 3 — Deep Orange (JNI Bridge — transitional warm color)
DORANGE_50  = (255, 243, 224); DORANGE_100 = (255, 224, 196)
DORANGE_300 = (244, 185, 148); DORANGE_400 = (230, 168, 133); DORANGE_500 = (215, 151, 118)
DORANGE_700 = (186, 122,  89); DORANGE_800 = (167, 104,  73)
DORANGE_900 = (148,  87,  58)

# Layer 4 — Green (Native C++ Engine)
GREEN_50  = (232, 245, 233); GREEN_100 = (200, 230, 201)
GREEN_400 = (102, 187, 106); GREEN_500 = ( 76, 175,  80)
GREEN_700 = ( 56, 142,  60); GREEN_800 = ( 46, 125,  50)
GREEN_900 = ( 27,  94,  32)

# Layer 5 — Blue Grey (Communication / Transport)
BLUEGREY_50  = (236, 239, 241); BLUEGREY_100 = (207, 216, 220)
BLUEGREY_200 = (176, 190, 197); BLUEGREY_400 = (120, 144, 156)
BLUEGREY_600 = ( 84, 110, 122); BLUEGREY_700 = ( 69,  90, 100)
BLUEGREY_800 = ( 55,  71,  79); BLUEGREY_900 = ( 38,  50,  56)

# Layer 6 — Deep Purple (External Systems)
PURPLE_50  = (237, 231, 246); PURPLE_100 = (209, 196, 233)
PURPLE_400 = (126,  87, 194); PURPLE_500 = (103,  58, 183)
PURPLE_700 = ( 81,  45, 168); PURPLE_800 = ( 69,  39, 160)
PURPLE_900 = ( 49,  27, 146)

# Accent colors for external targets
PINK_700   = (194,  24,  91)
CYAN_700   = (  0, 151, 167)
AMBER_700  = (255, 160,   0)

TEXT_PRIMARY   = (33, 33, 33)
TEXT_SECONDARY = (97, 97, 97)
TEXT_HINT      = (158, 158, 158)

# ==== FONTS ====
def _font_dirs():
    """Candidate font directories per platform, best first."""
    if sys.platform.startswith("win"):
        return [r"C:\Windows\Fonts", os.path.join(os.environ.get("LOCALAPPDATA", ""), "Microsoft", "Windows", "Fonts")]
    if sys.platform == "darwin":
        return ["/System/Library/Fonts/Supplemental", "/System/Library/Fonts", "/Library/Fonts"]
    return ["/usr/share/fonts/truetype/dejavu", "/usr/share/fonts", "/usr/local/share/fonts"]

# Regular / bold file names, tried in order inside every directory above.
_REGULAR = ["DejaVuSans.ttf", "Arial.ttf", "Arial Unicode.ttf", "Helvetica.ttc", "Geneva.ttf"]
_BOLD    = ["DejaVuSans-Bold.ttf", "Arial Bold.ttf", "Arial Unicode.ttf", "Helvetica.ttc", "Geneva.ttf"]

def _find_font(names):
    for d in _font_dirs():
        for n in names:
            path = os.path.join(d, n)
            if os.path.isfile(path):
                return path
    return None

def _load_font(names, size):
    path = _find_font(names)
    if path:
        try:
            return ImageFont.truetype(path, int(size))
        except Exception:
            pass
    try:
        return ImageFont.load_default(size=int(size))   # Pillow >= 10.1
    except TypeError:
        return ImageFont.load_default()

font_title = _load_font(_BOLD,    48 * S)
font_h1    = _load_font(_BOLD,    36 * S)
font_h2    = _load_font(_BOLD,    28 * S)
font_body  = _load_font(_BOLD,    24 * S)
font_small = _load_font(_REGULAR, 24 * S)
font_micro = _load_font(_REGULAR, 20 * S)
font_tiny  = _load_font(_REGULAR, 17 * S)

# ==== GLOBALS ====
img  = Image.new("RGBA", (W, H), BG)
draw = ImageDraw.Draw(img)

MARGIN       = int(120 * S)   # left/right page margin
LAYER_W      = W - 2 * MARGIN         # content width
LEFT_ZONE    = int(70 * S)   # left margin for group labels (fits "EXTERNAL" inside the margin)
INNER_MARGIN = int(30 * S)   # padding inside layer background
CARD_RADIUS  = int(20 * S)
BAR_H        = int(90 * S)   # layer header bar height (room for title + subtitle)
SECTION_GAP  = int(75 * S)   # gap between layers
CARD_PAD     = int(24 * S)   # horizontal padding inside background, prevents overlap

# Arrow geometry shared by fan-out and intra-layer connectors
ARROW_W  = int(4 * S)
ARROW_SZ = int(14 * S)
HUB_GAP  = int(95 * S)       # vertical room between a hub card and its grid

# ============================================================
# DRAWING HELPERS
# ============================================================

def rr(d, xy, r, fill, outline=None, width=1):
    d.rounded_rectangle(xy, radius=r, fill=fill, outline=outline, width=width)

def draw_card(x, y, w, h, header_color, title, lines):
    r = int(16*S)
    rr(draw, (x, y, x+w, y+h), r, fill=(255,255,255,255), outline=header_color, width=int(2*S))
    hh = int(60*S)
    rr(draw, (x, y, x+w, y+hh), r, fill=header_color)
    draw.rectangle((x, y+r, x+w, y+hh), fill=header_color)
    pad = int(24*S)
    draw.text((x+pad, y+int(16*S)), title, fill=(255,255,255), font=font_body)
    ly = y + hh + int(16*S)
    lh = int(30*S)
    for line in lines:
        draw.text((x+pad, ly), line, fill=TEXT_SECONDARY, font=font_small)
        ly += lh

def draw_mini_card(x, y, w, h, header_color, title, desc):
    """Compact secondary card — thin header + single line.
    Short (stacked) minis shrink their band and text so neither the title
    nor the description is ever clipped by the card edge or the next mini."""
    r = int(12*S)
    rr(draw, (x, y, x+w, y+h), r, fill=(255,255,255,255), outline=header_color, width=int(2*S))
    small = h < int(150*S)
    hh = int(24*S) if small else int(36*S)
    rr(draw, (x, y, x+w, y+hh), r, fill=header_color)
    draw.rectangle((x, y+r, x+w, y+hh), fill=header_color)
    pad = int(14*S) if small else int(18*S)
    draw.text((x+pad, y+int(6*S)), title, fill=(255,255,255),
              font=font_tiny if small else font_small)
    draw.text((x+pad, y+hh+int(6*S)), desc, fill=TEXT_SECONDARY, font=font_tiny)

def draw_layer_header(x, y, color_bar, accent_color, title, subtitle=""):
    bar = BAR_H
    content_x = x + LEFT_ZONE
    content_r = x + LAYER_W
    rr(draw, (content_x, y, content_r, y+bar), CARD_RADIUS, fill=color_bar)
    draw.rectangle((content_x, y+CARD_RADIUS, content_r, y+bar), fill=color_bar)
    aw = int(8*S)
    draw.rectangle((content_x, y+CARD_RADIUS, content_x+aw, y+bar), fill=accent_color)
    draw.text((content_x+int(30*S), y+int(12*S)), title, fill=(255,255,255), font=font_h2)
    if subtitle:
        draw.text((content_x+int(30*S), y+int(52*S)), subtitle, fill=(255,255,255,220), font=font_small)
    return y + bar

def draw_layer_bg(x, y, w, h, bg_color):
    """Subtle tinted background behind a layer's cards.
    Matches header width exactly, fully contains cards and shadows.
    Top corners are square, bottom corners are rounded."""
    r = int(24*S)
    content_x = x + LEFT_ZONE
    content_r = x + LAYER_W
    pad = int(4*S)
    bg_top = y - int(2*S)
    bg_bot = y + h + pad
    rr(draw, (content_x, bg_top, content_r, bg_bot), r, fill=bg_color)
    draw.rectangle((content_x, bg_top, content_r, bg_top + r), fill=bg_color)

def draw_layer_arrow(mid_x, y_top, y_bot, label, color):
    """Draw connecting arrow between layers — connects edge to edge, centered at W//2."""
    y1 = y_top
    y2 = y_bot
    if y2 <= y1 + int(10*S):
        return
    w = int(4*S)
    sz = int(26*S)
    dx = int(sz * 0.28)
    draw.line((mid_x, y1, mid_x, y2 - sz), fill=color, width=w)
    draw.polygon([(mid_x, y2), (mid_x-dx, y2-sz), (mid_x+dx, y2-sz)], fill=color)
    bar_w = int(10*S)
    bar_h = int(6*S)
    draw.rectangle((mid_x-bar_w, y1-bar_h, mid_x+bar_w, y1), fill=color)
    if label:
        ly = (y1 + y2) // 2 - int(12*S)
        lx = mid_x + int(24*S)
        draw.text((lx, ly), label, fill=color, font=font_micro)

def compute_card_width(items, cols, usable_w, gap_ratio=0.30):
    """Compute content-adapted card width for layers WITH intra-layer arrows.
    Strategy: enforce gap >= ratio * card_w, shrink cards to widen gaps
    so arrows are clearly visible between cards."""
    pad = int(24*S)
    max_w = 0
    for item in items:
        if item is None:
            continue
        name, color, lines = item
        max_w = max(max_w, draw.textlength(name, font=font_body))
        for line in lines:
            max_w = max(max_w, draw.textlength(line, font=font_small))
    min_card_w = int(max_w + pad + int(8*S))
    min_gap     = int(40*S)
    max_gap     = int(140*S)

    ideal_card_w = int(usable_w / (cols + gap_ratio * (cols - 1)))

    if ideal_card_w >= min_card_w:
        card_w = ideal_card_w
        h_gap  = (usable_w - card_w * cols) // (cols - 1)
        h_gap  = min(h_gap, max_gap)
    else:
        h_gap  = (usable_w - min_card_w * cols) // (cols - 1)
        h_gap  = max(h_gap, min_gap)
        h_gap  = min(h_gap, max_gap)
        card_w = (usable_w - (cols - 1) * h_gap) // cols
        card_w = max(card_w, min_card_w)

    # Hard guarantee: the grid must fit the usable width. When content is too
    # wide for the column count, keep a gap that still fits arrow labels and
    # shrink the cards — text may get tight, but nothing is ever clipped.
    if cols * card_w + (cols - 1) * h_gap > usable_w:
        h_gap  = max(min(h_gap, int(40*S)), int(20*S))
        card_w = (usable_w - (cols - 1) * h_gap) // cols
        print(f"warning: grid content too wide for {cols} columns; "
              f"cards shrunk to {card_w}px", file=sys.stderr)

    return card_w, h_gap

def uniform_card_width(cols, usable_w):
    """Maximize card width with minimal gap — for layers WITHOUT intra-layer arrows."""
    min_gap = int(6*S)
    card_w = (usable_w - (cols - 1) * min_gap) // cols
    return card_w, min_gap

def fill_row_cards(x_start, y, cols, card_w, card_h, h_gap, items, v_gap=None):
    if v_gap is None:
        v_gap = h_gap
    for i, item_data in enumerate(items):
        if item_data is None:  # padding slot — skip
            continue
        col = i % cols
        row = i // cols
        cx = x_start + col * (card_w + h_gap)
        cy = y + row * (card_h + v_gap)
        name, color, desc_lines = item_data
        draw_card(cx, cy, card_w, card_h, color, name, desc_lines)

def draw_slot_minis(x, y, w, h, entries, color):
    """Stack compact mini cards inside one grid slot, top to bottom."""
    n = len(entries)
    seg = h // n
    for i, (title, desc) in enumerate(entries):
        draw_mini_card(x, y + i*seg, w, seg - int(8*S), color, title, desc)

def _label_pill(cx, cy, text, color):
    """White-backed annotation pill — stays readable even where it crosses
    card headers, and never looks 'covered' by surrounding ink."""
    lw = draw.textlength(text, font=font_micro)
    th = int(26*S)
    px, py = int(10*S), int(3*S)
    rr(draw, (cx - lw//2 - px, cy - th//2 - py, cx + lw//2 + px, cy + th//2 + py),
       int(8*S), fill=(255,255,255,240), outline=color, width=int(2*S))
    draw.text((cx - lw//2, cy - th//2), text, fill=color, font=font_micro)

def draw_intra_deps(x_start, y, cols, card_w, card_h, h_gap, deps, color, v_gap=None):
    """Draw call-flow arrows between cards within a layer.
    Same-row adjacent: prominent horizontal arrow in gap.
    Same-row non-adjacent or cross-row: C-shaped bend path above cards."""
    if v_gap is None:
        v_gap = h_gap
    w    = ARROW_W
    sz   = ARROW_SZ
    bend = int(14*S)
    def jdot(x, y):
        jr = w // 2 + 1
        draw.rectangle((x-jr, y-jr, x+jr, y+jr), fill=color)

    for from_idx, to_idx, label in deps:
        f_col, f_row = from_idx % cols, from_idx // cols
        t_col, t_row = to_idx   % cols, to_idx   // cols

        fx1 = x_start + f_col*(card_w+h_gap) + card_w        # source right-edge
        fy1 = y + f_row*(card_h+v_gap) + card_h//2

        tx1 = x_start + t_col*(card_w+h_gap)                 # target left-edge
        ty1 = y + t_row*(card_h+v_gap) + card_h//2

        if f_row == t_row:
            if abs(f_col - t_col) == 1:
                # Adjacent → simple horizontal arrow, both ends on card borders
                mid_y = (fy1 + ty1) // 2
                f_left  = x_start + f_col*(card_w+h_gap)
                f_right = f_left + card_w
                t_left  = x_start + t_col*(card_w+h_gap)
                t_right = t_left + card_w
                if f_col < t_col:
                    tip_x = t_left
                    draw.line((f_right, mid_y, tip_x - sz, mid_y), fill=color, width=w)
                    draw.polygon([(tip_x, mid_y),
                                  (tip_x - sz, mid_y - sz//2),
                                  (tip_x - sz, mid_y + sz//2)], fill=color)
                else:
                    tip_x = t_right
                    draw.line((f_left, mid_y, tip_x - sz, mid_y), fill=color, width=w)
                    draw.polygon([(tip_x, mid_y),
                                  (tip_x - sz, mid_y - sz//2),
                                  (tip_x - sz, mid_y + sz//2)], fill=color)
                if label:
                    _label_pill((f_right + t_left) // 2, mid_y, label, color)
            else:
                # Non-adjacent same row → route above cards
                ay = y + f_row*(card_h+v_gap) - bend
                bx = fx1 + bend
                ex = tx1 - bend
                draw.line((fx1, fy1, bx, fy1), fill=color, width=w)
                draw.line((bx, fy1, bx, ay), fill=color, width=w)
                draw.line((bx, ay, ex, ay), fill=color, width=w)
                draw.line((ex, ay, ex, ty1), fill=color, width=w)
                draw.line((ex, ty1, tx1 - sz, ty1), fill=color, width=w)
                draw.polygon([(tx1, ty1), (tx1-sz, ty1-sz//2),
                              (tx1-sz, ty1+sz//2)], fill=color)
                jdot(bx, fy1); jdot(bx, ay); jdot(ex, ay)
                if label:
                    lw = draw.textlength(label, font=font_micro)
                    xp = (bx + ex) // 2
                    draw.text((xp - lw//2, ay - int(38*S)), label, fill=color, font=font_micro)
        else:
            if f_col == t_col:
                # Same column → straight vertical drop through v_gap
                mid_x = x_start + f_col*(card_w+h_gap) + card_w//2
                sy = y + f_row*(card_h+v_gap) + card_h
                ty = y + t_row*(card_h+v_gap)
                draw.line((mid_x, sy, mid_x, ty - sz), fill=color, width=w)
                draw.polygon([(mid_x, ty), (mid_x - sz//2, ty - sz),
                              (mid_x + sz//2, ty - sz)], fill=color)
                jdot(mid_x, sy)
                if label:
                    _label_pill(mid_x, (sy + ty)//2, label, color)
            elif t_row > f_row and t_col < f_col:
                # Target below AND left of source → route BETWEEN rows through v_gap
                ay = y + f_row*(card_h+v_gap) + card_h + v_gap//2
                sx = fx1 + bend
                en = tx1 - bend
                draw.line((fx1, fy1, sx, fy1), fill=color, width=w)
                draw.line((sx, fy1, sx, ay), fill=color, width=w)
                draw.line((sx, ay, en, ay), fill=color, width=w)
                draw.line((en, ay, en, ty1), fill=color, width=w)
                draw.line((en, ty1, tx1 - sz, ty1), fill=color, width=w)
                draw.polygon([(tx1, ty1), (tx1-sz, ty1-sz//2),
                              (tx1-sz, ty1+sz//2)], fill=color)
                jdot(sx, fy1); jdot(sx, ay); jdot(en, ay)
                if label:
                    lw = draw.textlength(label, font=font_micro)
                    mid_ax = (sx + en) // 2
                    draw.text((mid_ax - lw//2, ay - int(38*S)), label, fill=color, font=font_micro)
            else:
                ay = y + min(f_row, t_row)*(card_h+v_gap) - bend     # safe level above higher row
                sx = fx1 + bend
                draw.line((fx1, fy1, sx, fy1), fill=color, width=w)
                draw.line((sx, fy1, sx, ay), fill=color, width=w)
                en = tx1 - bend
                draw.line((en, ay, en, ty1), fill=color, width=w)
                draw.line((en, ty1, tx1 - sz, ty1), fill=color, width=w)
                draw.polygon([(tx1, ty1), (tx1-sz, ty1-sz//2),
                              (tx1-sz, ty1+sz//2)], fill=color)
                draw.line((sx, ay, en, ay), fill=color, width=w)
                jdot(sx, fy1); jdot(sx, ay); jdot(en, ay)
                if label:
                    lw = draw.textlength(label, font=font_micro)
                    mid_ax = (sx + en) // 2
                    draw.text((mid_ax - lw//2, ay - int(38*S)), label, fill=color, font=font_micro)

def draw_ccw_arc(cx, cy, r, deg_start, deg_end, color, width, steps=32):
    """Draw counter-clockwise arc as smooth polyline.
    PIL coords: 0°=right, 90°=down, 180°=left, 270°=up.  CCW = angle decreases."""
    pts = []
    for i in range(steps + 1):
        deg = deg_start - (deg_start - deg_end) * i / steps
        rad = math.radians(deg)
        pts.append((cx + r * math.cos(rad), cy + r * math.sin(rad)))
    draw.line(pts, fill=color, width=width)

def draw_group_label(x, y_top, y_bot, text, color):
    """Draw standard curly brace '{' + architectural group label on the left.
    Brace bulge goes LEFT (away from content) — vertical sits LEFT of arc center.
    No horizontal tick lines — arcs connect smoothly to vertical."""
    brace_x  = x + LEFT_ZONE - int(28*S)
    curve_r  = int(22*S)
    line_w   = int(4*S)
    mid_pt   = int(14*S)

    tick_end_x = brace_x + int(14*S)
    vert_x     = tick_end_x - curve_r

    draw_ccw_arc(tick_end_x, y_top + curve_r, curve_r, 300, 180, color, line_w, steps=64)
    jr = line_w // 2 + 1
    draw.rectangle((vert_x-jr, y_top+curve_r-jr, vert_x+jr, y_top+curve_r+jr), fill=color)

    mid_y = (y_top + y_bot) // 2
    draw.line((vert_x, y_top + curve_r, vert_x, mid_y - mid_pt),
              fill=color, width=line_w)
    draw.line((vert_x, mid_y + mid_pt, vert_x, y_bot - curve_r),
              fill=color, width=line_w)

    draw_ccw_arc(tick_end_x, y_bot - curve_r, curve_r, 180, 60, color, line_w, steps=64)
    draw.rectangle((vert_x-jr, y_bot-curve_r-jr, vert_x+jr, y_bot-curve_r+jr), fill=color)

    # Middle pointer: hollow V-shape
    tri_x = vert_x - mid_pt
    draw.line((tri_x, mid_y, vert_x, mid_y - mid_pt), fill=color, width=line_w)
    draw.line((tri_x, mid_y, vert_x, mid_y + mid_pt), fill=color, width=line_w)
    jr_v = line_w // 2 + 1
    draw.rectangle((vert_x-jr_v, mid_y-mid_pt-jr_v, vert_x+jr_v, mid_y-mid_pt+jr_v), fill=color)
    draw.rectangle((vert_x-jr_v, mid_y+mid_pt-jr_v, vert_x+jr_v, mid_y+mid_pt+jr_v), fill=color)
    draw.rectangle((tri_x-jr_v, mid_y-jr_v, tri_x+jr_v, mid_y+jr_v), fill=color)

    # Text to the LEFT of the brace, clamped so it can never leave the canvas
    lines = text.split("\n")
    line_spacing = int(22*S)
    total_h = len(lines) * line_spacing
    cy = mid_y - mid_pt - total_h - int(6*S)
    text_right = brace_x - int(12*S)
    for line in lines:
        tw = draw.textlength(line, font=font_tiny)
        draw.text((max(int(40*S), text_right - tw), cy), line, fill=color, font=font_tiny)
        cy += line_spacing

# ============================================================
# LAYER RENDERING (data-driven)
# ============================================================

def draw_hub_fanout(hub_cx, hub_bottom, targets, grid_x, grid_y,
                    cols, card_w, card_h, h_gap, v_gap, color, gap):
    """Arrows from a hub card's bottom edge down to grid cards.
    Row 0 targets get a clean vertical drop; row 1 targets route through
    the column gap so the line never crosses a row-0 card."""
    jsx = hub_cx
    jsy = hub_bottom
    mid_y = jsy + gap // 2
    jr = ARROW_W // 2 + 1

    for t_idx, label in targets:
        t_col, t_row = t_idx % cols, t_idx // cols
        tx = grid_x + t_col*(card_w+h_gap) + card_w//2
        ty = grid_y + t_row*(card_h+v_gap)

        draw.line((jsx, jsy, jsx, mid_y), fill=color, width=ARROW_W)
        draw.rectangle((jsx-jr, mid_y-jr, jsx+jr, mid_y+jr), fill=color)

        if t_row == 0:
            draw.line((jsx, mid_y, tx, mid_y), fill=color, width=ARROW_W)
            draw.line((tx, mid_y, tx, ty - ARROW_SZ), fill=color, width=ARROW_W)
            draw.rectangle((tx-jr, mid_y-jr, tx+jr, mid_y+jr), fill=color)
            lx = (jsx + tx) // 2
        else:
            gap_x = grid_x + t_col*(card_w+h_gap) + card_w + h_gap//2
            v_gap_y = grid_y + card_h + v_gap//2
            draw.line((jsx, mid_y, gap_x, mid_y), fill=color, width=ARROW_W)
            draw.line((gap_x, mid_y, gap_x, v_gap_y), fill=color, width=ARROW_W)
            draw.line((gap_x, v_gap_y, tx, v_gap_y), fill=color, width=ARROW_W)
            draw.line((tx, v_gap_y, tx, ty - ARROW_SZ), fill=color, width=ARROW_W)
            for px, py in [(gap_x, mid_y), (gap_x, v_gap_y), (tx, v_gap_y)]:
                draw.rectangle((px-jr, py-jr, px+jr, py+jr), fill=color)
            lx = (jsx + gap_x) // 2

        draw.polygon([(tx, ty), (tx - ARROW_SZ//2, ty - ARROW_SZ),
                      (tx + ARROW_SZ//2, ty - ARROW_SZ)], fill=color)
        if label:
            lw = draw.textlength(label, font=font_micro)
            draw.text((lx - lw//2, mid_y + int(14*S)), label, fill=color, font=font_micro)

def render_layer(cfg, y):
    """Render one architecture layer at vertical offset y.

    cfg keys:
      title, subtitle      layer header text
      bar, accent, bg      header / accent / background colors
      cols, rows           grid geometry
      card_h, v_gap        card height and vertical gap
      gap_ratio            None → uniform card width, else arrows get wider gaps
      items                grid cards: (name, color, [lines]) or None for empty slots
      minis                {slot_index: [(title, desc), ...]} compact cards
      deps                 [(from_idx, to_idx, label)] intra-layer arrows
      hub                  {title, lines, color, h} card centered above the grid
      hub_targets          [(flat_idx, label)] fan-out arrows from the hub
      right_room           extra right padding inside the layer background
    Returns (header_top, bottom) for group-label bookkeeping.
    """
    cols, rows = cfg["cols"], cfg["rows"]
    v_gap  = cfg.get("v_gap", int(16*S))
    card_h = cfg["card_h"]
    hub    = cfg.get("hub")

    usable_w = LAYER_W - LEFT_ZONE - 2*CARD_PAD - cfg.get("right_room", 0)

    # Grid cards drive the column width; a long hub line must never widen them
    gap_ratio = cfg.get("gap_ratio")
    if gap_ratio is None:
        card_w, h_gap = uniform_card_width(cols, usable_w)
    else:
        card_w, h_gap = compute_card_width(cfg["items"], cols, usable_w, gap_ratio)
    total_w = cols*card_w + (cols-1)*h_gap
    start_x = MARGIN + LEFT_ZONE + CARD_PAD + (usable_w - total_w)//2

    bg_h = (hub["h"] + HUB_GAP if hub else 0) + rows*(card_h + v_gap) + int(40*S)

    # Tinted background FIRST — drawn after the header bar it would cover the
    # bottom of the bar and clip the subtitle text.
    draw_layer_bg(MARGIN, y + BAR_H, LAYER_W - LEFT_ZONE, bg_h, cfg["bg"])

    header_top = y
    header_bottom = draw_layer_header(MARGIN, y, cfg["bar"], cfg["accent"],
                                      cfg["title"], cfg["subtitle"])
    y = header_bottom + int(10*S)

    if hub:
        # The hub card gets its own content-adapted width, centered on the grid
        pad = int(24*S)
        hub_w = min(int(max(draw.textlength(line, font=font_small)
                            for line in hub["lines"]) + 2*pad), usable_w)
        hub_x = start_x + (total_w - hub_w)//2
        hub_h = hub["h"]
        hub_cx = hub_x + hub_w//2
        hub_bottom = y + hub_h
        grid_y = hub_bottom + HUB_GAP
    else:
        grid_y = y

    if hub:
        draw_card(hub_x, y, hub_w, hub["h"], hub["color"], hub["title"], hub["lines"])

    fill_row_cards(start_x, grid_y, cols, card_w, card_h, h_gap, cfg["items"], v_gap)

    for slot, entries in cfg.get("minis", {}).items():
        col, row = slot % cols, slot // cols
        draw_slot_minis(start_x + col*(card_w+h_gap),
                        grid_y + row*(card_h+v_gap),
                        card_w, card_h, entries, cfg["mini_color"])

    if cfg.get("deps"):
        draw_intra_deps(start_x, grid_y, cols, card_w, card_h, h_gap,
                        cfg["deps"], cfg["dep_color"], v_gap)

    if hub and cfg.get("hub_targets"):
        draw_hub_fanout(hub_cx, hub_bottom, cfg["hub_targets"], start_x, grid_y,
                        cols, card_w, card_h, h_gap, v_gap, cfg["dep_color"], HUB_GAP)

    bottom = grid_y + rows*(card_h + v_gap) + int(20*S)
    return header_top, bottom

# ============================================================
# TITLE SECTION
# ============================================================
ty = int(60*S)
title_text = "Device2Device Architecture"
tw = draw.textlength(title_text, font=font_title)
draw.text(((W - tw)//2, ty), title_text, fill=TEXT_PRIMARY, font=font_title)
stext = "Android Peer-to-Peer Communication & Multimedia Processing Platform"
sw = draw.textlength(stext, font=font_body)
draw.text(((W - sw)//2, ty + int(64*S)), stext, fill=TEXT_SECONDARY, font=font_body)
sep_y = ty + int(120*S)
draw.line((MARGIN+LEFT_ZONE, sep_y, W-MARGIN, sep_y), fill=BLUEGREY_100, width=int(2*S))
dot_r = int(5*S)
draw.ellipse((W//2-dot_r, sep_y-dot_r, W//2+dot_r, sep_y+dot_r), fill=BLUEGREY_400)

current_y = sep_y + int(40*S)

# Track layer boundaries for group labels
layer_boundaries = []  # (y_start, y_end, group_name, group_color)

# ============================================================
# LAYER DEFINITIONS
# ============================================================

# ---- Layer 1: Activities — Indigo (Presentation / UI) ----
# Grid 7-col × 2-row; SelectActivity is the hub above the grid.
# Slot 6 (col 6 row 0) holds ThanksActivity; slot 13 (col 6 row 1) holds
# MyGit + Bugger as stacked half-height mini cards.
LAYER_ACTIVITIES = {
    "title":    "Activities Layer",
    "subtitle": "Java / Android UI \u2014 14 Activity Screens",
    "bar": INDIGO_500, "accent": INDIGO_900, "bg": INDIGO_50,
    "cols": 7, "rows": 2, "card_h": int(140*S), "v_gap": int(40*S),
    "gap_ratio": 0.40,
    "dep_color": INDIGO_500, "mini_color": INDIGO_900,
    "hub": {
        "title": "SelectActivity", "color": INDIGO_700, "h": int(170*S),
        "lines": ["Main Dashboard \u2014 Navigation Hub",
                  "Network, Sensor, Chat, Files, BT",
                  "startActivity / Intent dispatch"],
    },
    "items": [
        # Row 0
        ("TextureActivity",  INDIGO_700, ["GPU/CPU Rendering",   "Image & Video Processing"]),
        ("WaveActivity",     INDIGO_700, ["Audio Waveform",      "Recording & Playback"]),
        ("GraphActivity",    INDIGO_700, ["Sensor / Menu Hub",   "Real-time Charts, Sub-nav"]),
        ("BtDialogActivity", INDIGO_800, ["Bluetooth Setup",     "Discoverability & Scanning"]),
        ("MainActivity",     INDIGO_900, ["Splash Screen",       "Auto-navigate Entry"]),
        ("MarketActivity",   INDIGO_800, ["Market Quotes",       "K-Line, Widgets, Heatmap"]),
        None,  # ThanksActivity — mini card
        # Row 1
        None,
        None,
        ("SensorActivity",   INDIGO_800, ["Sensor List",         "Device Sensor Inventory"]),
        ("BtDevicesActivity",INDIGO_800, ["BT Device List",      "Paired & Discovered"]),
        ("BtRemoteActivity", INDIGO_800, ["BT Communication",    "RFCOMM Serial Exchange"]),
        ("AvatarActivity",   INDIGO_800, ["Parametric 3D Body",  "SDF/SurfaceNets + Anny"]),
        None,  # MyGit + Bugger — stacked mini cards
    ],
    "minis": {
        6:  [("ThanksActivity", "Credits & Acknowledgements")],
        13: [("MyGitActivity",  "GitHub Project Page"),
             ("BuggerActivity", "Bug Report & Feedback")],
    },
    "deps": [
        (2, 9,  "sensor"),    # Graph → Sensor (same column)
        (3, 10, "scan"),      # BtDialog → BtDevices (same column)
        (10, 11, "connect"),  # BtDevices → BtRemote (adjacent)
    ],
    "hub_targets": [
        (0, "texture"), (1, "wave"), (2, "chart"),
        (3, "bluetooth"), (5, "market"),
    ],
}

# ---- Layer 2: Services, Dialogs & Events — Teal ----
LAYER_SERVICES = {
    "title":    "Services, Dialogs & Events",
    "subtitle": "Java / Background Tasks & Floating UI \u2014 15 Components",
    "bar": TEAL_500, "accent": TEAL_900, "bg": TEAL_50,
    "cols": 5, "rows": 3, "card_h": int(155*S), "v_gap": int(16*S),
    "gap_ratio": 0.30,
    "dep_color": TEAL_500, "mini_color": TEAL_900,
    "items": [
        ("SubscribeService",       TEAL_700, ["Pub/Sub Subscribe",     "Floating Window"]),
        ("PublishService",         TEAL_700, ["Pub/Sub Publish",       "Async Thread"]),
        ("HttpServerService",      TEAL_700, ["HTTP File Server :8080","SAF + File Mode"]),
        ("HttpBrowserService",     TEAL_700, ["HTTP Server Engine",    "Sortable Listing"]),
        ("SshServerService",       TEAL_800, ["SSH Server :2222",      "User d2d, Shell"]),
        ("FileMsgServerService",   TEAL_800, ["File Transfer Server",  "Chunked TCP"]),
        ("ReceiverService",        TEAL_800, ["BT Data Receive",       "Floating Overlay"]),
        ("SaveDataService",        TEAL_900, ["BT Data Persist",       "Local File Write"]),
        ("WindowService",          TEAL_900, ["Floating Window",       "System Overlay"]),
        ("ToastNotificationService",TEAL_900,["Global Toast",          "3s Auto-dismiss"]),
        ("ChatBoxDialog",          TEAL_800, ["Multi-provider AI Chat","Streaming Response"]),
        ("FileMsgDialog",          TEAL_800, ["File Transfer UI",      "Progress Callback"]),
        ("LanMsgDialog",           TEAL_800, ["LAN Messenger",         "KCP Text / Image"]),
        ("GameDialog",             TEAL_800, ["Voice Deduction Board", "Full-screen Game"]),
        ("EventNotify/Handle",     TEAL_900, ["Observer Events",       "Component Comm"]),
    ],
    "deps": [
        (0, 1,  "pub/sub"),  # Subscribe → Publish
        (2, 3,  "engine"),   # HttpServerService → HttpBrowserService
        (13, 14, "events"),  # GameDialog → EventNotify
    ],
}

# ---- Layer 3: JNI Bridge — Deep Orange (Transitional) ----
LAYER_JNI = {
    "title":    "JNI Bridge Layer",
    "subtitle": "Java \u2194 C++ Interface \u2014 5 Wrapper Modules",
    "bar": DORANGE_700, "accent": DORANGE_900, "bg": DORANGE_50,
    "cols": 5, "rows": 1, "card_h": int(170*S), "v_gap": int(16*S),
    "gap_ratio": 0.35,
    "dep_color": DORANGE_500, "mini_color": DORANGE_900,
    "items": [
        ("CallbackWrapper", DORANGE_800, ["Message / PubSub / Toast", "SetIntField(receiver)", "Thread-safe dispatch"]),
        ("NetworkWrapper",  DORANGE_800, ["TCP / UDP / KCP / File",   "LAN Messenger (KCP)",  "Socket management"]),
        ("ViewWrapper",     DORANGE_800, ["GPU (EGL/GLES2)",          "CPU Bitmap Rendering", "TextureView binding"]),
        ("MediaWrapper",    DORANGE_900, ["PCM \u2194 WAV Convert",   "YUV \u2194 RGB Convert", "Audio Record / Play"]),
        ("TimeWrapper",     DORANGE_900, ["Timestamp Acquisition",    "Clock Synchronization","JvmMethods bridge"]),
    ],
}

# ---- Layer 4: Native C++ — Green (Core Engine) ----
LAYER_NATIVE = {
    "title":    "Native Layer",
    "subtitle": "C++ / NDK / OpenGL ES / OpenSL ES \u2014 10 Core Modules",
    "bar": GREEN_500, "accent": GREEN_900, "bg": GREEN_50,
    "cols": 5, "rows": 2, "card_h": int(210*S), "v_gap": int(24*S),
    "gap_ratio": 0.35, "right_room": int(60*S),
    "dep_color": GREEN_500, "mini_color": GREEN_900,
    "hub": {
        "title": "JniMethods.cpp", "color": GREEN_700, "h": int(210*S),
        "lines": ["All JNI Entry Points (5 Wrappers)",
                  "Network: UDP/TCP/KCP Server+Client",
                  "Pub/Sub: StartSubscribe / Publish",
                  "LAN Messenger + File Transfer"],
    },
    "items": [
        ("Socket Layer",     GREEN_800, ["UDP Multicast Server/Client",
                                         "TCP Server / KCP Reliable UDP",
                                         "FileMsgSocket (64KB chunk)",
                                         "LanMessenger (KCP over UDP)"]),
        ("scadup Pub/Sub",   GREEN_800, ["Publisher / Subscriber",
                                         "Broker (remote)",
                                         "Async thread pool",
                                         "shared_ptr buffer mgmt"]),
        ("Message Queue",    GREEN_700, ["Thread-safe messaging",
                                         "MSG_HINT / TOAST / STATUS",
                                         "FILE_PROGRESS callback",
                                         "SUBSCRIBER / PUBLISHER"]),
        ("Callback System",  GREEN_800, ["Bidirectional Java \u2194 C++",
                                         "JNI_OnLoad / JVM Attach",
                                         "callJavaMethod dispatch",
                                         "Thread-safe observer"]),
        ("Native Codec",     GREEN_700, ["AMediaCodec / MediaExtractor",
                                         "Video Decode \u2192 Surface",
                                         "Audio Encode / Decode",
                                         "PCM \u2194 WAV bridge"]),
        ("Constants/Config", GREEN_800, ["Global Constants",
                                         "Buffer hex dump",
                                         "Build Info / Config"]),
        ("Time/File Utils",  GREEN_700, ["TimeStamp / Clock",
                                         "File I/O & Logging",
                                         "Cross-platform macros"]),
        ("Display Engine",   GREEN_700, ["EGL / OpenGL ES 2.0",
                                         "CPU Bitmap Rendering",
                                         "TextureView output",
                                         "YUV/NV21 Surface"]),
        ("Media Convert",    GREEN_700, ["PCM \u2194 WAV Format",
                                         "YUV \u2194 RGB Color Space",
                                         "BMP Processing",
                                         "NV21 Rotation"]),
        ("Bitmap",           GREEN_700, ["Bitmap I/O & Masks",
                                         "JPEG / PNG Decode",
                                         "Pixel Operations"]),
    ],
    "deps": [
        (0, 1, "delegate"),  # Socket → scadup
        (1, 2, "enqueue"),   # scadup → Message Queue
        (2, 3, "callback"),  # Message Queue → Callback System
        (7, 8, "convert"),   # Display Engine → Media Convert
    ],
    "hub_targets": [
        (0, "dispatch"),           # → Socket Layer
        (1, "subscribe/publish"),  # → scadup Pub/Sub
        (3, "callback"),           # → Callback System
        (8, "convert"),            # → Media Convert (row 1)
    ],
}

# ---- Layer 5: Communication — Blue Grey (Transport) ----
LAYER_COMM = {
    "title":    "Communication Channels",
    "subtitle": "Protocols & Transport \u2014 5 Channel Types",
    "bar": BLUEGREY_600, "accent": BLUEGREY_900, "bg": BLUEGREY_50,
    "cols": 5, "rows": 1, "card_h": int(195*S), "v_gap": int(16*S),
    "gap_ratio": None,
    "dep_color": BLUEGREY_600, "mini_color": BLUEGREY_900,
    "items": [
        ("Bluetooth RFCOMM", BLUEGREY_700, ["Bidirectional serial communication",
                                            "Device scanning & pairing",
                                            "Directional control commands"]),
        ("WiFi / Multicast", BLUEGREY_700, ["UDP Multicast group messaging",
                                            "Real-time broadcast data",
                                            "Low-latency transmission"]),
        ("KCP Reliable UDP", BLUEGREY_800, ["ARQ-based reliable transport",
                                            "Lower latency than TCP",
                                            "Configurable window size"]),
        ("TCP Sockets",      BLUEGREY_800, ["Connection-oriented stream",
                                            "File transfer support",
                                            "HTTP embedded server"]),
        ("DeepSeek AI API",  BLUEGREY_900, ["HTTPS REST integration",
                                            "Chat / Reasoner models",
                                            "Streaming response parsing"]),
    ],
}

# ---- Layer 6: External Systems — Deep Purple ----
LAYER_EXTERNAL = {
    "title":    "External Systems & APIs",
    "subtitle": "Cloud / Remote / Peripheral \u2014 5 External Targets",
    "bar": PURPLE_500, "accent": PURPLE_900, "bg": PURPLE_50,
    "cols": 5, "rows": 1, "card_h": int(195*S), "v_gap": int(16*S),
    "gap_ratio": None,
    "dep_color": PURPLE_500, "mini_color": PURPLE_900,
    "items": [
        ("DeepSeek AI",       PINK_700,   ["Chat & Reasoner Models",  "REST API (HTTPS)",   "Streaming JSON Response"]),
        ("scadup Broker",     PURPLE_500, ["Message Broker (Remote)", "TCP Protocol",       "Pub/Sub Topic Routing"]),
        ("WiFi Network",      CYAN_700,   ["UDP Multicast Group",     "Device Discovery",   "Broadcast Communication"]),
        ("Bluetooth Devices", PURPLE_800, ["RFCOMM Serial Profile",   "Peripheral Connect", "SPP Data Exchange"]),
        ("HTTP Clients",      PURPLE_800, ["Browser / curl / wget",   "Embedded File Server","HTML Directory Listing"]),
    ],
}

# ============================================================
# RENDER LAYERS (with connecting arrows in the gaps)
# ============================================================
LAYERS = [
    (LAYER_ACTIVITIES, None,                  None),         # first layer — no arrow above
    (LAYER_SERVICES,   "startActivity / Intent",      INDIGO_500),
    (LAYER_JNI,        "startService / bindService",  TEAL_500),
    (LAYER_NATIVE,     "JNI native calls",            DORANGE_500),
    (LAYER_COMM,       "Message dispatch / Callback", GREEN_500),
    (LAYER_EXTERNAL,   "Network I/O / API calls",     BLUEGREY_600),
]

prev_bottom = None
for cfg, arrow_label, arrow_color in LAYERS:
    if arrow_label:
        draw_layer_arrow(W//2, prev_bottom, current_y, arrow_label, arrow_color)
    top, bottom = render_layer(cfg, current_y)
    layer_boundaries.append((top, bottom))
    prev_bottom = bottom
    current_y = bottom + SECTION_GAP

# ============================================================
# DRAW ARCHITECTURAL GROUP LABELS (left side)
# Brace endpoints at layer midpoints, not full extent.
# ============================================================
def _layer_mid(hdr_y, bot_y):
    return (hdr_y + bot_y) // 2

# Group 1: Android (layers 1+2) — mid L1 to mid L2
g_y1 = _layer_mid(layer_boundaries[0][0], layer_boundaries[0][1])
g_y2 = _layer_mid(layer_boundaries[1][0], layer_boundaries[1][1])
draw_group_label(MARGIN, g_y1, g_y2, "ANDROID\nJAVA", INDIGO_500)

# Group 2: JNI (layer 3) — middle 50% span
h3 = layer_boundaries[2][0]; b3 = layer_boundaries[2][1]; s3 = b3 - h3
draw_group_label(MARGIN, h3 + s3*0.25, b3 - s3*0.25, "JNI\nBRIDGE", DORANGE_700)

# Group 3: Native (layer 4) — middle 50% span
h4 = layer_boundaries[3][0]; b4 = layer_boundaries[3][1]; s4 = b4 - h4
draw_group_label(MARGIN, h4 + s4*0.25, b4 - s4*0.25, "NATIVE\nC++", GREEN_500)

# Group 4: External (layers 5+6) — mid L5 to mid L6
g_y7 = _layer_mid(layer_boundaries[4][0], layer_boundaries[4][1])
g_y8 = _layer_mid(layer_boundaries[5][0], layer_boundaries[5][1])
draw_group_label(MARGIN, g_y7, g_y8, "EXTERNAL", PURPLE_500)

# ============================================================
# MESSAGE FLOW
# ============================================================
flow_y = current_y
flow_h = int(120*S)
avail_flow_w = LAYER_W - LEFT_ZONE - int(60*S)

rr(draw, (MARGIN+LEFT_ZONE, flow_y, MARGIN+LAYER_W, flow_y+flow_h),
   int(16*S), fill=(255,248,225,255), outline=DORANGE_100, width=int(2*S))
draw.text((MARGIN+LEFT_ZONE+int(30*S), flow_y+int(14*S)), "Message Flow",
          fill=DORANGE_700, font=font_h2)

flow_texts = [
    "C++ setMessage()",
    "JNI SetIntField(receiver)",
    "Handler.dispatchMessage()",
    "Java UI (txt_status/txt_hint/Toast)",
]
fw = font_small
total_tw = sum(draw.textlength(t, font=fw) for t in flow_texts)
ngaps = len(flow_texts) - 1
box_pad = int(20*S)
aw = int(40*S)
agap = int(30*S)

total_fw = total_tw + len(flow_texts)*box_pad + ngaps*(aw + agap)
if total_fw > avail_flow_w:
    scale = avail_flow_w / total_fw
    box_pad = int(box_pad * scale)
    aw = int(aw * scale)
    agap = int(agap * scale)
    total_fw = int(total_fw * scale)

fx = MARGIN + LEFT_ZONE + (LAYER_W - LEFT_ZONE - total_fw)//2
fy = flow_y + int(66*S)
box_h = int(34*S)
for i, t in enumerate(flow_texts):
    tw = draw.textlength(t, font=fw)
    bw = int(tw + box_pad)
    rr(draw, (fx, fy, fx+bw, fy+box_h), int(8*S),
       fill=(255,255,255,255), outline=DORANGE_300, width=int(2*S))
    draw.text((fx+box_pad//2, fy+int(6*S)), t, fill=TEXT_PRIMARY, font=fw)
    fx += bw
    if i < ngaps:
        ax = fx
        ay = fy + box_h//2
        bx = fx + aw + agap
        draw.line((ax, ay, bx, ay), fill=DORANGE_500, width=int(2*S))
        sz = int(12*S)
        draw.polygon([(bx, ay), (bx-sz, ay-int(sz*0.43)), (bx+sz, ay+int(sz*0.43))], fill=DORANGE_500)
        fx = bx

current_y = flow_y + flow_h + int(40*S)

# ============================================================
# LEGEND (bottom)
# ============================================================
legend_y = current_y
legend_h = int(160*S)
rr(draw, (MARGIN+LEFT_ZONE, legend_y, MARGIN+LAYER_W, legend_y+legend_h),
   int(16*S), fill=(248,248,252,255), outline=BLUEGREY_200, width=int(5*S))
draw.text((MARGIN+LEFT_ZONE+int(30*S), legend_y+int(14*S)), "Layer Legend",
          fill=TEXT_PRIMARY, font=font_h2)

legend_items = [
    (INDIGO_500,   "Activities Layer (Java UI)"),
    (TEAL_500,     "Services, Dialogs & Events"),
    (DORANGE_500,  "JNI Bridge (Java \u2194 C++)"),
    (GREEN_500,    "Native Layer (C++ / NDK)"),
    (BLUEGREY_600, "Communication Channels"),
    (PURPLE_500,   "External Systems & APIs"),
]
lcols = 3
lcard_w = int(800*S)
lgap_x = (LAYER_W - LEFT_ZONE - int(30*S)*2 - lcols*lcard_w) // (lcols-1)
lx = MARGIN + LEFT_ZONE + int(30*S)
ly = legend_y + int(52*S)
mark_sz = int(32*S)
for i, (color, label) in enumerate(legend_items):
    col = i % lcols
    row = i // lcols
    cx = lx + col*(lcard_w + lgap_x)
    cy = ly + row*int(42*S)
    rr(draw, (cx, cy, cx+mark_sz, cy+mark_sz), int(6*S), fill=color)
    draw.text((cx+mark_sz+int(14*S), cy+int(4*S)), label, fill=TEXT_SECONDARY, font=font_body)

# ============================================================
# CROP TO CONTENT — nothing may ever stick out of the image
# ============================================================
# Crop to the actual ink bounding box plus a guaranteed margin. Should any
# drawing touch a canvas edge (which would otherwise be clipped), the image
# is first pasted onto a larger white canvas so every side of the final
# image carries the margin.
bbox = ImageOps.invert(img.convert("RGB")).getbbox()
if bbox is None:
    bbox = (0, 0, W, H)
l, t, r, b = bbox
# Pad symmetrically (same amount left/right, top/bottom) so the margin stays
# uniform even when one side of the ink runs close to a canvas edge.
pad_x = max(EDGE_MARGIN - l, EDGE_MARGIN - (W - r), 0)
pad_y = max(EDGE_MARGIN - t, EDGE_MARGIN - (H - b), 0)
if pad_x or pad_y:
    padded = Image.new("RGBA", (W + 2*pad_x, H + 2*pad_y), BG)
    padded.paste(img, (pad_x, pad_y))
    img = padded
    l, t, r, b = l + pad_x, t + pad_y, r + pad_x, b + pad_y
img = img.crop((l - EDGE_MARGIN, t - EDGE_MARGIN, r + EDGE_MARGIN, b + EDGE_MARGIN))

# ============================================================
# SAVE
# ============================================================
# This script lives in tools/, one level below the project root the image folder sits in
_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
out_path = os.path.join(_root, "image", "device2device.png")
img.save(out_path, "PNG")
print(f"Saved: {out_path}  ({img.width}x{img.height})  "
      f"ink: {(l, t, r, b)}  margin: {EDGE_MARGIN}px on every side")
