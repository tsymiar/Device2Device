#!/usr/bin/env python3
"""Generate Device2Device architecture diagram — enhanced hierarchy & color palette."""

from PIL import Image, ImageDraw, ImageFont
import os, math

# ==== OUTPUT SIZE ====
W, H = 5600, 9600
BG = (255, 255, 255, 255)
S = 2.0  # scale factor

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
FD = "/usr/share/fonts/truetype/dejavu/"
font_title = ImageFont.truetype(FD + "DejaVuSans-Bold.ttf", int(48*S))
font_h1    = ImageFont.truetype(FD + "DejaVuSans-Bold.ttf", int(36*S))
font_h2    = ImageFont.truetype(FD + "DejaVuSans-Bold.ttf", int(28*S))
font_body  = ImageFont.truetype(FD + "DejaVuSans.ttf",      int(24*S))
font_small = ImageFont.truetype(FD + "DejaVuSans.ttf",      int(20*S))
font_micro = ImageFont.truetype(FD + "DejaVuSans.ttf",      int(20*S))
font_tiny  = ImageFont.truetype(FD + "DejaVuSans.ttf",      int(17*S))

# ==== GLOBALS ====
img  = Image.new("RGBA", (W, H), BG)
draw = ImageDraw.Draw(img)

MARGIN      = int(80 * S)   # 160  — left/right page margin
LAYER_W     = W - 2 * MARGIN         # 5280 — content width
LEFT_ZONE   = int(50 * S)   # 100  — left margin for group labels
RIGHT_ZONE  = int(0  * S)   # 0    — right margin (reserved)
INNER_MARGIN = int(30 * S)  # 60   — padding inside layer background
CARD_RADIUS  = int(20 * S)  # 40
BAR_H        = int(100 * S) # 200  — layer header bar height
SECTION_GAP  = int(85 * S)   # 170  — gap between layers
CARD_PAD     = int(24 * S)  # 48   — horizontal padding inside background, prevents overlap

# ============================================================
# DRAWING HELPERS
# ============================================================

def rr(d, xy, r, fill, outline=None, width=1):
    d.rounded_rectangle(xy, radius=r, fill=fill, outline=outline, width=width)

def draw_card(x, y, w, h, header_color, title, lines):
    r = int(16*S)
    # Body (no shadow, no dark accent bar)
    rr(draw, (x, y, x+w, y+h), r, fill=(255,255,255,255), outline=header_color, width=int(2*S))
    # Header stripe
    hh = int(60*S)
    # Draw header background
    rr(draw, (x, y, x+w, y+hh), r, fill=header_color)
    draw.rectangle((x, y+r, x+w, y+hh), fill=header_color)
    # Title
    pad = int(24*S)
    draw.text((x+pad, y+int(16*S)), title, fill=(255,255,255), font=font_body)
    # Description lines
    ly = y + hh + int(16*S)
    lh = int(30*S)
    for line in lines:
        draw.text((x+pad, ly), line, fill=TEXT_SECONDARY, font=font_small)
        ly += lh

def draw_mini_card(x, y, w, h, header_color, title, desc):
    """Compact secondary card — thin header + single line, fits in half-height cards.
    Unified style for ThanksActivity / MyGitActivity / BuggerActivity."""
    r = int(12*S)
    # Body (no shadow, no dark accent bar)
    rr(draw, (x, y, x+w, y+h), r, fill=(255,255,255,255), outline=header_color, width=int(2*S))
    # Compact header
    hh = int(36*S)
    rr(draw, (x, y, x+w, y+hh), r, fill=header_color)
    draw.rectangle((x, y+r, x+w, y+hh), fill=header_color)
    # Title in header
    pad = int(18*S)
    draw.text((x+pad, y+int(10*S)), title, fill=(255,255,255), font=font_small)
    # Single description line
    draw.text((x+pad, y+hh+int(8*S)), desc, fill=TEXT_SECONDARY, font=font_tiny)

def draw_layer_header(x, y, color_bar, accent_color, title, subtitle=""):
    bar = BAR_H
    content_x = x + LEFT_ZONE
    content_r = x + LAYER_W
    # Main bar (no shadow to avoid dark strip)
    rr(draw, (content_x, y, content_r, y+bar), CARD_RADIUS, fill=color_bar)
    draw.rectangle((content_x, y+CARD_RADIUS, content_r, y+bar), fill=color_bar)
    # Left accent
    aw = int(8*S)
    draw.rectangle((content_x, y+CARD_RADIUS, content_x+aw, y+bar), fill=accent_color)
    # Title text
    draw.text((content_x+int(30*S), y+int(18*S)), title, fill=(255,255,255), font=font_h2)
    if subtitle:
        draw.text((content_x+int(30*S), y+int(56*S)), subtitle, fill=(255,255,255,220), font=font_small)
    return y + bar

def draw_layer_bg(x, y, w, h, bg_color):
    """Draw subtle tinted background behind a layer's cards.
    Matches header width exactly, fully contains cards and shadows.
    Top corners are square, bottom corners are rounded."""
    r = int(24*S)
    content_x = x + LEFT_ZONE
    content_r = x + LAYER_W
    pad = int(4*S)
    bg_top = y - int(2*S)
    bg_bot = y + h + pad
    # Rounded rect on all four, then square the top
    rr(draw, (content_x, bg_top, content_r, bg_bot), r, fill=bg_color)
    # Overwrite top rounded corners with a rectangle to make them square
    draw.rectangle((content_x, bg_top, content_r, bg_top + r), fill=bg_color)

def draw_layer_arrow(mid_x, y_top, y_bot, label, color):
    """Draw connecting arrow between layers — connects edge to edge, centered at W//2."""
    y1 = y_top          # start at upper layer's bottom edge
    y2 = y_bot           # end at lower layer's top edge
    if y2 <= y1 + int(10*S):
        return
    w = int(4*S)
    sz = int(26*S)
    dx = int(sz * 0.28)
    # Line: vertical from bar to arrowhead base
    draw.line((mid_x, y1, mid_x, y2 - sz), fill=color, width=w)
    # Arrowhead on top, covers line endpoint cleanly
    draw.polygon([(mid_x, y2), (mid_x-dx, y2-sz), (mid_x+dx, y2-sz)], fill=color)
    # Horizontal bar at top, bottom edge flush with line start
    bar_w = int(10*S)
    bar_h = int(6*S)
    draw.rectangle((mid_x-bar_w, y1-bar_h, mid_x+bar_w, y1), fill=color)
    # Label to the right of arrow, well clear of the vertical line
    if label:
        ly = (y1 + y2) // 2 - int(12*S)
        lx = mid_x + int(24*S)   # 48px from arrow center, 44px from line edge
        draw.text((lx, ly), label, fill=color, font=font_micro)

def compute_card_width(items, cols, usable_w, gap_ratio=0.30):
    """Compute content-adapted card width for layers WITH intra-layer arrows.
    Strategy: enforce gap ≥ ratio * card_w, shrink cards to widen gaps
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
    # Tighter padding: 1*pad per side (was 2*pad) — still leaves 32px/side
    min_card_w = int(max_w + pad + int(8*S))
    min_gap     = int(40*S)   #  80px floor
    max_gap     = int(140*S)  # 280px ceiling — allows wider gaps for arrow labels

    # Ideal balanced layout: card * cols + card*ratio*(cols-1) = usable_w
    ideal_card_w = int(usable_w / (cols + gap_ratio * (cols - 1)))
    
    if ideal_card_w >= min_card_w:
        card_w = ideal_card_w
        h_gap  = (usable_w - card_w * cols) // (cols - 1)
        h_gap  = min(h_gap, max_gap)
    else:
        # Tight content, start from min_card_w
        h_gap  = (usable_w - min_card_w * cols) // (cols - 1)
        h_gap  = max(h_gap, min_gap)
        h_gap  = min(h_gap, max_gap)
        card_w = (usable_w - (cols - 1) * h_gap) // cols
        card_w = max(card_w, min_card_w)

    return card_w, h_gap

def uniform_card_width(cols, usable_w):
    """Maximize card width with minimal gap — for layers WITHOUT intra-layer arrows."""
    min_gap = int(6*S)   # 12px — just visual separation, no arrows
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

def draw_intra_deps(x_start, y, cols, card_w, card_h, h_gap, deps, color, v_gap=None):
    """Draw call-flow arrows between cards within a layer.
    Same-row adjacent: prominent horizontal arrow in gap.
    Same-row non-adjacent or cross-row: C-shaped bend path above cards."""
    if v_gap is None:
        v_gap = h_gap
    w    = int(4*S)       # 8px  — clean thin line
    sz   = int(14*S)      # 28px — proportional arrowhead
    ahgap = int(4*S)      # 8px  — tight gap from card edge
    bend = int(14*S)      # 28px — bend offset from card edge
    # Junction square to fill corner gaps between orthogonal lines
    def jdot(x, y):
        jr = w // 2 + 1
        draw.rectangle((x-jr, y-jr, x+jr, y+jr), fill=color)

    for from_idx, to_idx, label in deps:
        f_col, f_row = from_idx % cols, from_idx // cols
        t_col, t_row = to_idx   % cols, to_idx  // cols

        fx1 = x_start + f_col*(card_w+h_gap) + card_w        # source right-edge
        fy1 = y + f_row*(card_h+v_gap) + card_h//2

        tx1 = x_start + t_col*(card_w+h_gap)                 # target left-edge
        ty1 = y + t_row*(card_h+v_gap) + card_h//2

        if f_row == t_row:
            # Same row
            if abs(f_col - t_col) == 1:
                # Adjacent → simple horizontal arrow, both ends on card borders
                mid_y = (fy1 + ty1) // 2
                # Compute actual card edges
                f_left  = x_start + f_col*(card_w+h_gap)
                f_right = f_left + card_w
                t_left  = x_start + t_col*(card_w+h_gap)
                t_right = t_left + card_w
                if f_col < t_col:
                    # L→R: source right-edge → arrowhead tip at target left-edge
                    tip_x = t_left
                    draw.line((f_right, mid_y, tip_x - sz, mid_y), fill=color, width=w)
                    draw.polygon([(tip_x, mid_y),
                                  (tip_x - sz, mid_y - sz//2),
                                  (tip_x - sz, mid_y + sz//2)], fill=color)
                else:
                    # R→L: source left-edge → arrowhead tip at target right-edge
                    tip_x = t_right
                    draw.line((f_left, mid_y, tip_x - sz, mid_y), fill=color, width=w)
                    draw.polygon([(tip_x, mid_y),
                                  (tip_x - sz, mid_y - sz//2),
                                  (tip_x - sz, mid_y + sz//2)], fill=color)
                # Label centered in gap, high enough to clear arrowhead (sz=14S → extends ±7S from center)
                if label:
                    lw = draw.textlength(label, font=font_micro)
                    mid_adj_x = (f_right + t_left) // 2           # center of gap — avoids arrowhead at edges
                    lbl_adj_y = mid_y - int(38*S)                 # 76px above arrow center, clears arrowhead top (mid_y-14)
                    draw.text((mid_adj_x - lw//2, lbl_adj_y), label, fill=color, font=font_micro)
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
                    xp = (bx + ex) // 2                 # center of horizontal segment — stays in gap
                    draw.text((xp - lw//2, ay - int(38*S)), label, fill=color, font=font_micro)
        else:
            # Different rows
            if f_col == t_col:
                # Same column → straight vertical drop through v_gap
                mid_x = x_start + f_col*(card_w+h_gap) + card_w//2
                sy = y + f_row*(card_h+v_gap) + card_h   # source bottom edge
                ty = y + t_row*(card_h+v_gap)            # target top edge
                draw.line((mid_x, sy, mid_x, ty - sz), fill=color, width=w)
                # Arrowhead pointing DOWN at target top
                draw.polygon([(mid_x, ty), (mid_x - sz//2, ty - sz),
                              (mid_x + sz//2, ty - sz)], fill=color)
                jdot(mid_x, sy)
                if label:
                    lw = draw.textlength(label, font=font_micro)
                    # Place label to the LEFT of vertical line, well clear of line + arrowhead
                    lx = mid_x - int(14*S) - lw   # 28px clearance from vertical line
                    ly = (sy + ty)//2 - int(10*S)       # centered between source and target
                    draw.text((lx, ly), label, fill=color, font=font_micro)
            # When target is below AND to the LEFT of source:
            #   route BETWEEN the rows (in the v_gap zone) to avoid colliding
            #   with same-row-above routing from the source.
            # Otherwise: route ABOVE the higher row.
            elif t_row > f_row and t_col < f_col:
                # Target below AND left of source → route BETWEEN rows through v_gap
                # Safer & more natural than going below everything
                ay = y + f_row*(card_h+v_gap) + card_h + v_gap//2
                sx = fx1 + bend
                en = tx1 - bend
                draw.line((fx1, fy1, sx, fy1), fill=color, width=w)    # right from source
                draw.line((sx, fy1, sx, ay), fill=color, width=w)      # down to gap center
                draw.line((sx, ay, en, ay), fill=color, width=w)       # across to target col
                draw.line((en, ay, en, ty1), fill=color, width=w)      # down/up to target
                draw.line((en, ty1, tx1 - sz, ty1), fill=color, width=w) # into target (stop at arrowhead base)
                draw.polygon([(tx1, ty1), (tx1-sz, ty1-sz//2),
                              (tx1-sz, ty1+sz//2)], fill=color)
                jdot(sx, fy1); jdot(sx, ay); jdot(en, ay)
                if label:
                    lw = draw.textlength(label, font=font_micro)
                    mid_ax = (sx + en) // 2                 # center of horizontal segment
                    draw.text((mid_ax - lw//2, ay - int(38*S)), label, fill=color, font=font_micro)
            else:
                ay = y + min(f_row, t_row)*(card_h+v_gap) - bend     # safe level above higher row
                # Source exit: right+bend, then up to ay
                sx = fx1 + bend
                draw.line((fx1, fy1, sx, fy1), fill=color, width=w)         # right from source
                draw.line((sx, fy1, sx, ay), fill=color, width=w)           # up to safe level
                # Target entry: from left-bend, down to target center
                en = tx1 - bend
                draw.line((en, ay, en, ty1), fill=color, width=w)           # down to target level
                draw.line((en, ty1, tx1 - sz, ty1), fill=color, width=w)    # into target (stop at arrowhead base)
                draw.polygon([(tx1, ty1), (tx1-sz, ty1-sz//2),
                              (tx1-sz, ty1+sz//2)], fill=color)
                # Horizontal connector at safe level ay
                draw.line((sx, ay, en, ay), fill=color, width=w)
                jdot(sx, fy1); jdot(sx, ay); jdot(en, ay)
                if label:
                    lw = draw.textlength(label, font=font_micro)
                    mid_ax = (sx + en) // 2                 # center of horizontal segment
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
    No horizontal tick lines — arcs connect smoothly to vertical.
    Shape:   ╮
             │  ← vertical (arc bulges LEFT)
             ◀
             │
             ╯"""
    brace_x  = x + LEFT_ZONE - int(28*S)   # leftmost point (clear of layer bg)
    curve_r  = int(22*S)                    # arc radius
    line_w   = int(4*S)                     # stroke width (uniform across arcs/vert/diag)
    mid_pt   = int(14*S)                    # middle pointer size (larger, hollow)

    tick_end_x = brace_x + int(14*S)        # arc center x reference
    vert_x     = tick_end_x - curve_r       # vertical line x (arcs bulge LEFT)

    # ---- Top hook: CCW arc 300°→180° (smooth diagonal start, no horizontal) ----
    draw_ccw_arc(tick_end_x, y_top + curve_r, curve_r, 300, 180, color, line_w, steps=64)
    # Fill gap at top arc→vertical junction
    jr = line_w // 2 + 1
    draw.rectangle((vert_x-jr, y_top+curve_r-jr, vert_x+jr, y_top+curve_r+jr), fill=color)

    # ---- Vertical line: split to avoid triangle overlap ----
    mid_y = (y_top + y_bot) // 2
    # Upper segment: top arc → above triangle
    draw.line((vert_x, y_top + curve_r, vert_x, mid_y - mid_pt),
              fill=color, width=line_w)
    # Lower segment: below triangle → bottom arc
    draw.line((vert_x, mid_y + mid_pt, vert_x, y_bot - curve_r),
              fill=color, width=line_w)

    # ---- Bottom hook: CCW arc 180°→60° (smooth diagonal end, no horizontal) ----
    draw_ccw_arc(tick_end_x, y_bot - curve_r, curve_r, 180, 60, color, line_w, steps=64)
    # Fill gap at vertical→bottom arc junction
    draw.rectangle((vert_x-jr, y_bot-curve_r-jr, vert_x+jr, y_bot-curve_r+jr), fill=color)

    # ---- Middle pointer: hollow V-shape (no base, no vertical overlap) ----
    tri_x = vert_x - mid_pt                # vertex left of vertical
    # Only two diagonal lines (底边/竖线重合部分已去掉)
    draw.line((tri_x, mid_y, vert_x, mid_y - mid_pt), fill=color, width=line_w)
    draw.line((tri_x, mid_y, vert_x, mid_y + mid_pt), fill=color, width=line_w)
    # Junction squares at V-shape ↔ vertical connections
    jr_v = line_w // 2 + 1
    draw.rectangle((vert_x-jr_v, mid_y-mid_pt-jr_v, vert_x+jr_v, mid_y-mid_pt+jr_v), fill=color)
    draw.rectangle((vert_x-jr_v, mid_y+mid_pt-jr_v, vert_x+jr_v, mid_y+mid_pt+jr_v), fill=color)
    draw.rectangle((tri_x-jr_v, mid_y-jr_v, tri_x+jr_v, mid_y+jr_v), fill=color)

    # Text to the LEFT of the brace (positioned entirely above triangle)
    lines = text.split("\n")
    line_spacing = int(22*S)
    total_h = len(lines) * line_spacing
    # Bottom of last text line sits just above triangle top
    cy = mid_y - mid_pt - total_h - int(6*S)
    text_right = brace_x - int(12*S)  # right edge of text, left of wider brace
    for line in lines:
        tw = draw.textlength(line, font=font_tiny)
        draw.text((text_right - tw, cy), line, fill=color, font=font_tiny)
        cy += line_spacing


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
# Separator line with gradient dots
sep_y = ty + int(120*S)
draw.line((MARGIN+LEFT_ZONE, sep_y, W-MARGIN, sep_y), fill=BLUEGREY_100, width=int(2*S))
# Small dot in center
dot_r = int(5*S)
draw.ellipse((W//2-dot_r, sep_y-dot_r, W//2+dot_r, sep_y+dot_r), fill=BLUEGREY_400)

current_y = sep_y + int(40*S)

# Track layer boundaries for group labels
layer_boundaries = []  # (y_start, y_end, group_name, group_color)

# ============================================================
# LAYER 1: ACTIVITIES — Indigo (Presentation / UI)
# ============================================================
layer1_header_y = current_y
body1 = draw_layer_header(MARGIN, current_y, INDIGO_500, INDIGO_900,
    "Activities Layer",
    "Java / Android UI \u2014 12 Activity Screens")
current_y = body1 + int(10*S)

# ---- Activity Layer: SelectActivity as hub (like JniMethods.cpp in Native) ----
# Grid: 6-col × 2-row, secondary cards (MyGit+Bugger) stacked half-height in col 5 row 1
#      Col 0         Col 1         Col 2         Col 3         Col 4         Col 5
#  R0: Texture       Wave          Graph         Connect       Main          Thanks
#  R1: (gap)         (gap)         Sensor        Devices       Commit        [MyGit ½/Bugger ½]
act_items = [
    # Row 0
    ("TextureActivity",  INDIGO_700, ["GPU/CPU Rendering",          "Image & Video Processing"]),
    ("WaveActivity",     INDIGO_700, ["Audio Waveform",             "Recording & Playback"]),
    ("GraphActivity",    INDIGO_700, ["Sensor / Menu Hub",          "Real-time Charts, Sub-nav"]),
    ("ConnectActivity",  INDIGO_800, ["Bluetooth Setup",            "Discoverability & Scanning"]),
    ("MainActivity",     INDIGO_900, ["Splash Screen",              "Auto-navigate Entry"]),
    None,  # ThanksActivity — drawn manually as mini card (unified secondary style)
    # Row 1
    None,
    None,
    ("SensorActivity",   INDIGO_800, ["Sensor List",                "Device Sensor Inventory"]),
    None,  # DevicesActivity — drawn manually with content-adapted width
    ("CommitActivity",   INDIGO_800, ["BT Communication",           "RFCOMM Serial Exchange"]),
    None,  # MyGit + Bugger — stacked half-height mini cards, drawn manually
]
cols1, v_gap1 = 6, int(40*S)  # 80px — room for cross-row arrow routing between rows
rows1 = 2
usable_w1 = LAYER_W - LEFT_ZONE - 2*CARD_PAD
# Include hub card text in width computation so hub isn't narrower than its content
_hub_text = [
    "Main Dashboard — Navigation Hub",
    "Network, Sensor, Chat, Files, BT",
    "startActivity / Intent dispatch",
]
_width_items = act_items + [("SelectActivity", INDIGO_700, _hub_text)]
card_w1, h_gap1 = compute_card_width(_width_items, cols1, usable_w1, gap_ratio=0.40)
total_w1 = cols1*card_w1 + (cols1-1)*h_gap1
# Center grid — no extra left shift (6 cols fits comfortably within canvas)
start_x1 = MARGIN + LEFT_ZONE + CARD_PAD + (usable_w1 - total_w1)//2
card_h1 = int(140*S)   # 280px — 2 lines content fits with padding

# ---- SelectActivity hub: same width as grid cards, centered above grid ----
hub_card_w = card_w1
hub_card_h = int(170*S)  # 340px — 3 lines content fits with padding
gap_hub = int(100*S)      # 200px — room for arrows + labels without overlap
grid_span = total_w1  # use real span for hub centering
hub_x = start_x1 + (grid_span - hub_card_w)//2
hub_bottom = current_y + hub_card_h
grid_y = hub_bottom + gap_hub  # grid starts after hub

# Layer background MUST be drawn BEFORE cards
bg_h1 = hub_card_h + gap_hub + rows1*(card_h1 + v_gap1) + int(50*S)
draw_layer_bg(MARGIN, current_y-int(20*S), LAYER_W - LEFT_ZONE, bg_h1, INDIGO_50)

# Draw SelectActivity hub on top of background
draw_card(hub_x, current_y, hub_card_w, hub_card_h, INDIGO_700,
    "SelectActivity",
    ["Main Dashboard — Navigation Hub",
     "Network, Sensor, Chat, Files, BT",
     "startActivity / Intent dispatch"])

# Draw grid cards — Thanks(idx 5), Devices(idx 9), MyGit/Bugger(idx 11) handled manually
_act_items_auto = act_items.copy()
_act_items_auto[5] = None   # skip ThanksActivity (col 5 row 0) — drawn as unified mini card
_act_items_auto[9] = None   # skip DevicesActivity (col 3 row 1) — drawn manually
_act_items_auto[11] = None  # skip MyGit/Bugger slot (col 5 row 1) — drawn as unified mini cards
fill_row_cards(start_x1, grid_y, cols1, card_w1, card_h1, h_gap1, _act_items_auto, v_gap1)

# ---- ThanksActivity: unified secondary mini card at col 5 row 0 ----
_mini_x = start_x1 + 5*(card_w1 + h_gap1)                         # col 5 left edge
_mini_h = card_h1 // 2                                              # half-height
draw_mini_card(_mini_x, grid_y + int(8*S), card_w1, _mini_h, INDIGO_900,
               "ThanksActivity", "Credits & Acknowledgements")

# DevicesActivity at col 3 row 1 — content-adapted width, centered under ConnectActivity
_devices_pad = int(24*S)
_devices_max_w = max(
    draw.textlength("DevicesActivity", font=font_body),
    draw.textlength("BT Device List", font=font_small),
    draw.textlength("Paired & Discovered", font=font_small))
_devices_card_w = int(_devices_max_w + _devices_pad + int(8*S))
_devices_x = start_x1 + 3*(card_w1 + h_gap1) + (card_w1 - _devices_card_w)//2  # centered in col 3 slot
_devices_y = grid_y + card_h1 + v_gap1           # row 1 top
draw_card(_devices_x, _devices_y, _devices_card_w, card_h1, INDIGO_800, "DevicesActivity",
    ["BT Device List", "Paired & Discovered"])

# ---- Thanks / MyGit / Bugger: evenly spaced in col 5, MyGit centered between Thanks & Bugger ----
_mini_y_top = grid_y + 1*(card_h1 + v_gap1)                       # row 1 top
_bugger_y = _mini_y_top + _mini_h                                  # Bugger at bottom of row 1
# MyGit centered vertically between Thanks and Bugger
_thanks_cy = (grid_y + int(8*S)) + _mini_h//2                       # Thanks center-y
_bugger_cy = _bugger_y + _mini_h//2                                 # Bugger center-y
_mygit_cy  = (_thanks_cy + _bugger_cy) // 2                         # midpoint between them
_mygit_y   = _mygit_cy - _mini_h//2
draw_mini_card(_mini_x, _mygit_y, card_w1, _mini_h, INDIGO_900,
               "MyGitActivity", "GitHub Project Page")
draw_mini_card(_mini_x, _bugger_y, card_w1, _mini_h, INDIGO_900,
               "BuggerActivity", "Bug Report & Feedback")

# ---- Intra-grid arrows (vertical only; connect arrow drawn manually below) ----
# Indices: 2=Graph(col2,r0), 8=Sensor(col2,r1), 3=Connect(col3,r0), 9=Devices(col3,r1)
draw_intra_deps(start_x1, grid_y, cols1, card_w1, card_h1, h_gap1,
    [
        (2, 8, "sensor"),      # Graph(col2,row0) → Sensor(col2,row1) — same-col vertical
        (3, 9, "scan"),        # Connect(col3,row0) → Devices(col3,row1) — same-col vertical
    ], INDIGO_500, v_gap1)

# ---- "connect" arrow: DevicesActivity → CommitActivity (same-row adjacent, custom Devices width) ----
# Uses actual DevicesActivity right-edge so arrow starts ON the card border, not in mid-air
_caw  = int(4*S)       # arrow line width
_casz = int(14*S)      # arrowhead size
_d_right = _devices_x + _devices_card_w                          # DevicesActivity actual right edge
_d_cy = _devices_y + card_h1//2                                   # DevicesActivity vertical center
_c_left = start_x1 + 4*(card_w1 + h_gap1)                         # CommitActivity left edge
_c_cy = grid_y + 1*(card_h1 + v_gap1) + card_h1//2                # CommitActivity vertical center
_my = (_d_cy + _c_cy) // 2                                        # mid-y for adjacent horizontal arrow
draw.line((_d_right, _my, _c_left - _casz, _my), fill=INDIGO_500, width=_caw)
draw.polygon([(_c_left, _my), (_c_left - _casz, _my - _casz//2),
              (_c_left - _casz, _my + _casz//2)], fill=INDIGO_500)
# Label centered between actual Devices right edge and Commit left edge
_connect_lw = draw.textlength("connect", font=font_micro)
_connect_lx = (_d_right + _c_left) // 2
draw.text((_connect_lx - _connect_lw//2, _my - int(38*S)), "connect", fill=INDIGO_500, font=font_micro)

# ---- Vertical arrows: SelectActivity hub → feature activities ----
aw = int(4*S)      # 8px line
asz = int(14*S)    # arrowhead size
# Hub bottom-center
hsx = hub_x + hub_card_w//2
hsy = hub_bottom
# Targets: (grid_col, label_above_card) — Texture(0, "texture"), Wave(1, "wave"), Graph(2), Connect(3)
hub_targets = [
    (0, "texture"),
    (1, "wave"),
    (2, None),       # chart — no label above card
    (3, None),       # bluetooth — no label above card
]
for t_col, label_above in hub_targets:
    tx = start_x1 + t_col*(card_w1 + h_gap1) + card_w1//2
    ty = grid_y  # top of grid
    # Route: down from hub, horizontal jog at gap center, then down to target
    mid_y = hsy + gap_hub//2
    jr = aw // 2 + 1
    jcol = INDIGO_500
    draw.line((hsx, hsy, hsx, mid_y), fill=jcol, width=aw)
    draw.line((hsx, mid_y, tx, mid_y), fill=jcol, width=aw)
    draw.line((tx, mid_y, tx, ty - asz), fill=jcol, width=aw)
    draw.rectangle((hsx-jr, mid_y-jr, hsx+jr, mid_y+jr), fill=jcol)
    draw.rectangle((tx-jr, mid_y-jr, tx+jr, mid_y+jr), fill=jcol)
    # Arrowhead pointing DOWN
    draw.polygon([(tx, ty), (tx - asz//2, ty - asz), (tx + asz//2, ty - asz)], fill=INDIGO_500)
    # Label: all 4 labels same Y level (horizontal alignment)
    # texture/wave right of arrow above their cards; chart/bluetooth centered under connector
    lbl = label_above if label_above else ("chart" if t_col == 2 else "bluetooth")
    lw = draw.textlength(lbl, font=font_micro)
    lx = (tx + int(46*S)) if label_above else (hsx + tx)//2  # offset right to clear arrow
    draw.text((lx - lw//2, mid_y + int(14*S)), lbl, fill=INDIGO_500, font=font_micro)

# Pad bottom for 3-row grid + background
layer1_bottom = grid_y + rows1*(card_h1 + v_gap1) + int(30*S)
layer_boundaries.append((layer1_header_y, layer1_bottom, "Android", INDIGO_500))
current_y = layer1_bottom + SECTION_GAP

# ============================================================
# LAYER 2: SERVICES & EVENTS — Teal (Background Services)
# ============================================================
layer2_header_y = current_y
arrow_zone_top = layer1_bottom
arrow_zone_bot = current_y
draw_layer_arrow(W//2, arrow_zone_top, arrow_zone_bot,
                 "startActivity / Intent", INDIGO_500)

body2 = draw_layer_header(MARGIN, current_y, TEAL_500, TEAL_900,
    "Services & Events",
    "Java / Background Tasks & Floating UI \u2014 10 Service Components")
current_y = body2 + int(10*S)

svc_items = [
    ("SubscribeService",     TEAL_700, ["Pub/Sub Subscribe",         "Floating Window"]),
    ("PublishService",       TEAL_700, ["Pub/Sub Publish",           "Async Thread"]),
    ("HttpFileService",      TEAL_700, ["HTTP File Server",          "SAF + File Mode"]),
    ("FileMsgDialog",        TEAL_800, ["File Transfer",             "Progress Callback"]),
    ("ReceiverService",      TEAL_800, ["BT Data Receive",           "Floating Overlay"]),
    ("ChatBoxDialog",        TEAL_800, ["DeepSeek AI Chat",          "Streaming Response"]),
    ("ToastNotificationSvc", TEAL_800, ["Global Toast",              "3s Auto-dismiss"]),
    ("EventNotify/Handle",   TEAL_900, ["Observer Events",           "Component Comm"]),
    ("SaveDataService",      TEAL_900, ["BT Data Persist",           "Local File Write"]),
    ("WindowService",        TEAL_900, ["Floating Window",           "System Overlay"]),
]
cols2, v_gap2 = 5, int(16*S)
usable_w2 = LAYER_W - LEFT_ZONE - 2*CARD_PAD
card_w2, h_gap2 = compute_card_width(svc_items, cols2, usable_w2)
total_w2 = cols2*card_w2 + (cols2-1)*h_gap2
start_x2 = MARGIN + LEFT_ZONE + CARD_PAD + (usable_w2 - total_w2)//2
card_h2 = int(155*S)  # 310px — comfortable spacing for 2-line content
rows2 = 2

bg_h2 = rows2*(card_h2 + v_gap2) + int(40*S)
draw_layer_bg(MARGIN, current_y-int(20*S), LAYER_W - LEFT_ZONE, bg_h2, TEAL_50)

fill_row_cards(start_x2, current_y, cols2, card_w2, card_h2, h_gap2, svc_items, v_gap2)

# Intra-layer deps: Subscribe→Publish (pub/sub), HttpFile→FileMsg (progress)
# Both adjacent same-row arrows: clearly visible in card gaps
draw_intra_deps(start_x2, current_y, cols2, card_w2, card_h2, h_gap2,
    [(0, 1, "pub/sub"), (2, 3, "progress")], TEAL_500, v_gap2)

layer2_bottom = current_y + rows2*(card_h2 + v_gap2) + int(20*S)
layer_boundaries.append((layer2_header_y, layer2_bottom, "Android", TEAL_500))
current_y = layer2_bottom + SECTION_GAP

# ============================================================
# LAYER 3: JNI BRIDGE — Deep Orange (Transitional)
# ============================================================
layer3_header_y = current_y
arrow_zone_top = layer2_bottom
arrow_zone_bot = current_y
draw_layer_arrow(W//2, arrow_zone_top, arrow_zone_bot,
                 "startService / bindService", TEAL_500)

body3 = draw_layer_header(MARGIN, current_y, DORANGE_700, DORANGE_900,
    "JNI Bridge Layer",
    "Java \u2194 C++ Interface \u2014 6 Wrapper Modules")
current_y = body3 + int(10*S)

jni_items = [
    ("CallbackWrapper", DORANGE_800, ["Message / PubSub / Toast", "SetIntField(receiver)", "Thread-safe dispatch"]),
    ("NetworkWrapper",  DORANGE_800, ["TCP / UDP / KCP / File",  "Socket management",     "File transfer callbacks"]),
    ("ViewWrapper",     DORANGE_800, ["GPU (EGL/GLES2)",         "CPU Bitmap Rendering",  "TextureView binding"]),
    ("MediaWrapper",    DORANGE_900, ["PCM \u2194 WAV Convert",    "YUV \u2194 RGB Convert",   "Audio Record / Play"]),
    ("TimeWrapper",     DORANGE_900, ["Timestamp Acquisition",   "Clock Synchronization", "JvmMethods bridge"]),
    ("SensorWrapper",   DORANGE_900, ["Accel + Voice Bridge",    "Sensor Events & Alerts", "AudioTrack / OpenSL ES"]),
]
cols3, v_gap3 = 3, int(16*S)
usable_w3 = LAYER_W - LEFT_ZONE - 2*CARD_PAD
card_w3, h_gap3 = compute_card_width(jni_items, cols3, usable_w3, gap_ratio=0.50)  # generous gaps
total_w3 = cols3*card_w3 + (cols3-1)*h_gap3
start_x3 = MARGIN + LEFT_ZONE + CARD_PAD + (usable_w3 - total_w3)//2
card_h3 = int(170*S)
rows3 = 2

bg_h3 = rows3*(card_h3 + v_gap3) + int(40*S)
draw_layer_bg(MARGIN, current_y-int(20*S), LAYER_W - LEFT_ZONE, bg_h3, DORANGE_50)

fill_row_cards(start_x3, current_y, cols3, card_w3, card_h3, h_gap3, jni_items, v_gap3)

layer3_bottom = current_y + rows3*(card_h3 + v_gap3) + int(20*S)
layer_boundaries.append((layer3_header_y, layer3_bottom, "JNI", DORANGE_700))
current_y = layer3_bottom + SECTION_GAP

# ============================================================
# LAYER 4: NATIVE C++ — Green (Core Engine)
# ============================================================
layer4_header_y = current_y
arrow_zone_top = layer3_bottom
arrow_zone_bot = current_y
draw_layer_arrow(W//2, arrow_zone_top, arrow_zone_bot,
                 "JNI native calls", DORANGE_500)

body4 = draw_layer_header(MARGIN, current_y, GREEN_500, GREEN_900,
    "Native Layer",
    "C++ / NDK / OpenGL ES / OpenSL ES \u2014 8 Core Modules")
current_y = body4 + int(10*S)

usable_w4 = LAYER_W - LEFT_ZONE - 2*CARD_PAD - int(60*S)  # -120px right breathing room

# ---- Native modules — 8 cards (even) in 4-col × 2-row grid ----
native_items = [
    # Row 0: Communication + Coordination
    ("Socket Layer",    GREEN_800, ["UDP Multicast Server/Client",
                                    "TCP Server / KCP Reliable UDP",
                                    "FileMsgSocket (64KB chunk)"]),
    ("scadup Pub/Sub",  GREEN_800, ["Publisher / Subscriber",
                                    "Broker (remote)",
                                    "Async thread pool",
                                    "shared_ptr buffer mgmt"]),
    ("Message Queue",   GREEN_700, ["Thread-safe messaging",
                                    "MSG_HINT / TOAST / STATUS",
                                    "FILE_PROGRESS callback",
                                    "SUBSCRIBER / PUBLISHER"]),
    ("Callback System", GREEN_800, ["Bidirectional Java \u2194 C++",
                                    "JNI_OnLoad / JVM Attach",
                                    "callJavaMethod dispatch",
                                    "Thread-safe observer"]),
    # Row 1: Config + Utilities + Rendering + Media (reordered per layout)
    ("Constants/Config",GREEN_800, ["Global Constants",
                                    "Buffer hex dump",
                                    "Build Info / Config"]),
    ("Time/File Utils", GREEN_700, ["TimeStamp / Clock",
                                    "File I/O & Logging",
                                    "Cross-platform macros"]),
    ("Display Engine",  GREEN_700, ["EGL / OpenGL ES 2.0",
                                    "CPU Bitmap Rendering",
                                    "TextureView output",
                                    "YUV/NV21 Surface"]),
    ("Media Convert",   GREEN_700, ["PCM \u2194 WAV Format",
                                    "YUV \u2194 RGB Color Space",
                                    "BMP Processing",
                                    "NV21 Rotation"]),
]
cols4, v_gap4 = 4, int(24*S)  # 48px — consistent spacing
card_w4, h_gap4 = compute_card_width(native_items, cols4, usable_w4, gap_ratio=0.35)  # wider gaps for arrow labels
total_w4 = cols4*card_w4 + (cols4-1)*h_gap4
start_x4 = MARGIN + LEFT_ZONE + CARD_PAD + (usable_w4 - total_w4)//2
card_h4 = int(210*S)
rows4 = 2

# ---- JniMethods.cpp — same width as other cards, centered above grid ----
jni_card_w = card_w4
jni_card_h = int(210*S)
gap_jni = int(100*S)  # 200px — room for arrows + labels without overlap
# Center JniMethods over the 4-col grid
grid_span = cols4*card_w4 + (cols4-1)*h_gap4
jni_x = start_x4 + (grid_span - jni_card_w)//2
jni_bottom = current_y + jni_card_h
current_y2 = jni_bottom + gap_jni  # grid starts after JniMethods

# Layer background MUST be drawn BEFORE cards (otherwise it covers them)
bg_h4 = jni_card_h + gap_jni + rows4*(card_h4 + v_gap4) + int(40*S)
draw_layer_bg(MARGIN, current_y-int(20*S), LAYER_W - LEFT_ZONE, bg_h4, GREEN_50)

# Draw JniMethods.cpp on top of background
draw_card(jni_x, current_y, jni_card_w, jni_card_h, GREEN_700,
    "JniMethods.cpp",
    ["All JNI Entry Points (5 Wrappers)",
     "Network: UDP/TCP/KCP Server+Client",
     "Pub/Sub: StartSubscribe / Publish",
     "Media: PCM\u2192WAV, Surface Rendering"])

fill_row_cards(start_x4, current_y2, cols4, card_w4, card_h4, h_gap4, native_items, v_gap4)

# Intra-layer deps: Socket(0)→scadup(1)→MsgQueue(2)→Callback(3), Display(6)→MediaConvert(7)
# Indices: 0=Socket,1=Scadup,2=MsgQueue,3=Callback,4=Constants,5=TimeFile,6=Display,7=MediaConvert
draw_intra_deps(start_x4, current_y2, cols4, card_w4, card_h4, h_gap4,
    [(0, 1, "delegate"), (1, 2, "enqueue"), (2, 3, "callback"),
     (6, 7, "convert")], GREEN_500, v_gap4)

# ---- Arrows: JniMethods → native modules (vertical dispatch) ----
aw = int(4*S)      # 8px line
asz = int(14*S)    # arrowhead size
# JniMethods bottom-center
jsx = jni_x + jni_card_w//2
jsy = jni_bottom
# Target cards by flat index
# Indices: 0=Socket,1=Scadup,2=MsgQueue,3=Callback,4=Constants,5=TimeFile,6=Display,7=MediaConvert
jni_targets = [
    (0, "dispatch"),           # → Socket Layer (col 0 row 0)
    (1, "subscribe/publish"),  # → scadup Pub/Sub (col 1 row 0)
    (3, "callback"),           # → Callback System (col 3 row 0)
    (7, "convert"),            # → Media Convert (col 3 row 1 — enters right border)
]
for t_idx, label in jni_targets:
    t_col = t_idx % cols4
    t_row = t_idx // cols4
    tx = start_x4 + t_col*(card_w4 + h_gap4) + card_w4//2
    ty = current_y2 + t_row*(card_h4 + v_gap4)  # top of target row
    mid_y = jsy + gap_jni//2  # center of gap — balanced spacing

    is_convert = (label == "convert")

    if is_convert:
        # ---- Right-side wrap: horizontal at mid_y (same Y as callback), arrowhead → card right edge ----
        right_x = start_x4 + grid_span + int(40*S)  # past grid right edge
        mc_left = start_x4 + t_col*(card_w4 + h_gap4)   # Media Convert left edge
        mc_right = mc_left + card_w4                    # Media Convert right edge
        mc_mid_y = ty + card_h4//2                      # Media Convert vertical center
        # 1. Drop from jsx to mid_y (same level as callback arrow)
        draw.line((jsx, jsy, jsx, mid_y), fill=GREEN_500, width=aw)
        # 2. Right to beyond grid edge at mid_y
        draw.line((jsx, mid_y, right_x, mid_y), fill=GREEN_500, width=aw)
        # 3. Down along right edge to Media Convert's vertical middle
        draw.line((right_x, mid_y, right_x, mc_mid_y), fill=GREEN_500, width=aw)
        # 4. Left into Media Convert's right border (stop at arrowhead base)
        draw.line((right_x, mc_mid_y, mc_right + asz, mc_mid_y), fill=GREEN_500, width=aw)
        # Junction squares at corners
        _jr = aw // 2 + 1
        for _px, _py in [(jsx, mid_y), (right_x, mid_y), (right_x, mc_mid_y), (mc_right+asz, mc_mid_y)]:
            draw.rectangle((_px-_jr, _py-_jr, _px+_jr, _py+_jr), fill=GREEN_500)
        # Arrowhead pointing LEFT into the card (tip at right edge, base to the right)
        draw.polygon([(mc_right, mc_mid_y),
                      (mc_right + asz, mc_mid_y - asz//2),
                      (mc_right + asz, mc_mid_y + asz//2)], fill=GREEN_500)
        # Label inside the elbow (right_x corner): below horizontal, left of vertical
        if label:
            lw = draw.textlength(label, font=font_micro)
            draw.text((right_x - lw - int(10*S), mid_y + int(10*S)), label, fill=GREEN_500, font=font_micro)
        continue

    # ---- Row 0 + other row 1: standard routing ----
    draw.line((jsx, jsy, jsx, mid_y), fill=GREEN_500, width=aw)
    jr_corner = aw // 2 + 1  # junction square half-size
    draw.rectangle((jsx-jr_corner, mid_y-jr_corner, jsx+jr_corner, mid_y+jr_corner), fill=GREEN_500)

    if t_row == 0:
        # Row 0 — clean vertical drop, no card obstruction (like Activities Layer)
        draw.line((jsx, mid_y, tx, mid_y), fill=GREEN_500, width=aw)
        draw.line((tx, mid_y, tx, ty - asz), fill=GREEN_500, width=aw)
        draw.rectangle((tx-jr_corner, mid_y-jr_corner, tx+jr_corner, mid_y+jr_corner), fill=GREEN_500)
        lx = (jsx + tx) // 2
    else:
        # Row 1 — route vertical segment through column gap to avoid row 0 cards
        if t_col < cols4 - 1:
            gap_x = start_x4 + t_col*(card_w4 + h_gap4) + card_w4 + h_gap4//2  # right gap
        else:
            gap_x = start_x4 + t_col*(card_w4 + h_gap4) - h_gap4//2               # left gap
        v_gap_y = current_y2 + card_h4 + v_gap4//2  # center of v_gap between row 0 & row 1
        # Horizontal to column gap at mid_y
        draw.line((jsx, mid_y, gap_x, mid_y), fill=GREEN_500, width=aw)
        # Down through gap (clear of cards) to v_gap level
        draw.line((gap_x, mid_y, gap_x, v_gap_y), fill=GREEN_500, width=aw)
        # Horizontal to target center at v_gap level
        draw.line((gap_x, v_gap_y, tx, v_gap_y), fill=GREEN_500, width=aw)
        # Down to target (stop at arrowhead base)
        draw.line((tx, v_gap_y, tx, ty - asz), fill=GREEN_500, width=aw)
        # Junction squares at corners (omit arrowhead base)
        for _px, _py in [(gap_x, mid_y), (gap_x, v_gap_y), (tx, v_gap_y)]:
            draw.rectangle((_px-jr_corner, _py-jr_corner, _px+jr_corner, _py+jr_corner), fill=GREEN_500)
        lx = (jsx + gap_x) // 2  # label centered on the main horizontal run

    # Arrowhead pointing DOWN
    draw.polygon([(tx, ty), (tx - asz//2, ty - asz), (tx + asz//2, ty - asz)], fill=GREEN_500)
    # Label BELOW horizontal connector
    if label:
        lw = draw.textlength(label, font=font_micro)
        draw.text((lx - lw//2, mid_y + int(14*S)), label, fill=GREEN_500, font=font_micro)

layer4_bottom = current_y2 + rows4*(card_h4 + v_gap4) + int(20*S)
layer_boundaries.append((layer4_header_y, layer4_bottom, "Native", GREEN_500))
current_y = layer4_bottom + SECTION_GAP

# ============================================================
# LAYER 5: COMMUNICATION — Blue Grey (Transport)
# ============================================================
layer5_header_y = current_y
arrow_zone_top = layer4_bottom
arrow_zone_bot = current_y
draw_layer_arrow(W//2, arrow_zone_top, arrow_zone_bot,
                 "Message dispatch / Callback", GREEN_500)

body5 = draw_layer_header(MARGIN, current_y, BLUEGREY_600, BLUEGREY_900,
    "Communication Channels",
    "Protocols & Transport \u2014 5 Channel Types")
current_y = body5 + int(10*S)

comm_items = [
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
]
cols5, v_gap5 = 5, int(16*S)
usable_w5 = LAYER_W - LEFT_ZONE - 2*CARD_PAD
card_w5, h_gap5 = uniform_card_width(cols5, usable_w5)  # no intra-layer arrows — max width
total_w5 = cols5*card_w5 + (cols5-1)*h_gap5
start_x5 = MARGIN + LEFT_ZONE + CARD_PAD + (usable_w5 - total_w5)//2
card_h5 = int(195*S)
rows5 = 1

bg_h5 = rows5*(card_h5 + v_gap5) + int(40*S)
draw_layer_bg(MARGIN, current_y-int(20*S), LAYER_W - LEFT_ZONE, bg_h5, BLUEGREY_50)

fill_row_cards(start_x5, current_y, cols5, card_w5, card_h5, h_gap5, comm_items, v_gap5)

layer5_bottom = current_y + rows5*(card_h5 + v_gap5) + int(20*S)
layer_boundaries.append((layer5_header_y, layer5_bottom, "Network", BLUEGREY_600))
current_y = layer5_bottom + SECTION_GAP

# ============================================================
# LAYER 6: EXTERNAL SYSTEMS — Deep Purple (External)
# ============================================================
layer6_header_y = current_y
arrow_zone_top = layer5_bottom
arrow_zone_bot = current_y
draw_layer_arrow(W//2, arrow_zone_top, arrow_zone_bot,
                 "Network I/O / API calls", BLUEGREY_600)

body6 = draw_layer_header(MARGIN, current_y, PURPLE_500, PURPLE_900,
    "External Systems & APIs",
    "Cloud / Remote / Peripheral \u2014 5 External Targets")
current_y = body6 + int(10*S)

ext_items = [
    ("DeepSeek AI",       PINK_700,   ["Chat & Reasoner Models",   "REST API (HTTPS)",   "Streaming JSON Response"]),
    ("scadup Broker",     PURPLE_500, ["Message Broker (Remote)",  "TCP Protocol",       "Pub/Sub Topic Routing"]),
    ("WiFi Network",      CYAN_700,   ["UDP Multicast Group",     "Device Discovery",   "Broadcast Communication"]),
    ("Bluetooth Devices", PURPLE_800, ["RFCOMM Serial Profile",   "Peripheral Connect", "SPP Data Exchange"]),
    ("HTTP Clients",      PURPLE_800, ["Browser / curl / wget",   "Embedded File Server","HTML Directory Listing"]),
]
cols6, v_gap6 = 5, int(16*S)
usable_w6 = LAYER_W - LEFT_ZONE - 2*CARD_PAD
card_w6, h_gap6 = uniform_card_width(cols6, usable_w6)  # no intra-layer arrows — max width
total_w6 = cols6*card_w6 + (cols6-1)*h_gap6
start_x6 = MARGIN + LEFT_ZONE + CARD_PAD + (usable_w6 - total_w6)//2
card_h6 = int(195*S)
rows6 = 1

bg_h6 = rows6*(card_h6 + v_gap6) + int(40*S)
draw_layer_bg(MARGIN, current_y-int(20*S), LAYER_W - LEFT_ZONE, bg_h6, PURPLE_50)

fill_row_cards(start_x6, current_y, cols6, card_w6, card_h6, h_gap6, ext_items, v_gap6)

layer6_bottom = current_y + rows6*(card_h6 + v_gap6) + int(20*S)
layer_boundaries.append((layer6_header_y, layer6_bottom, "External", PURPLE_500))
current_y = layer6_bottom + int(40*S)

# ============================================================
# DRAW ARCHITECTURAL GROUP LABELS (left side)
# Brace endpoints at layer midpoints, not full extent.
# ============================================================
def _layer_mid(hdr_y, bot_y):
    return (hdr_y + bot_y) / 2

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
avail_flow_w = LAYER_W - LEFT_ZONE - int(60*S)  # content area inside flow box

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
aw = int(40*S)      # arrow gap between boxes
agap = int(30*S)    # extra spacing around arrow

total_fw = total_tw + len(flow_texts)*box_pad + ngaps*(aw + agap)
# Scale down if overflow
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
        ax = fx                         # right edge of box i
        ay = fy + box_h//2
        bx = fx + aw + agap             # left edge of box i+1
        draw.line((ax, ay, bx, ay), fill=DORANGE_500, width=int(2*S))
        sz = int(12*S)
        draw.polygon([(bx, ay), (bx-sz, ay-int(sz*0.43)), (bx-sz, ay+int(sz*0.43))], fill=DORANGE_500)
        fx = bx                         # continue from next box position

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
    (TEAL_500,     "Services & Events (Background)"),
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

final_bottom = legend_y + legend_h + int(68*S)

# Crop bottom whitespace — target 98% fill rate
_target_fill = 0.98
_margin = int(final_bottom * (1.0 / _target_fill - 1.0))
crop_h = final_bottom + max(_margin, int(80*S))  # at least 160px margin
if crop_h < H:
    img = img.crop((0, 0, W, crop_h))

# ============================================================
# SAVE
# ============================================================
out_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "image", "device2device.png")
img.save(out_path, "PNG")
_out_h = crop_h if crop_h < H else H
_fill_pct = final_bottom / _out_h * 100
print(f"Saved: {out_path}  ({W}x{_out_h})  content_y: {final_bottom}/{_out_h}  fill: {_fill_pct:.0f}%")
