#!/usr/bin/env python3
"""Draws the chat emotes: original 16x16 pixel art, one sheet, one list of names.

usage: tools/make_emotes.py [PREVIEW.png]

Writes
  src/main/resources/assets/minecraft/textures/font/unicode_page_e0.png
  src/main/resources/assets/better112/emotes.txt

The sheet is a font page: Minecraft draws characters U+E000..U+E0FF from it, sixteen to a
row, so an emote in chat is just one character of text. The names follow the short codes
people know from Discord (:smile:, :joy:, :thumbsup:). Everything here is drawn by this
script; no emoji font or image set is copied.
"""
import os
import sys

from PIL import Image

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources", "assets")

DARK = (74, 47, 0, 255)
WHITE = (255, 255, 255, 255)
RED = (215, 38, 61, 255)
MOUTH = (139, 30, 45, 255)
BLUE = (79, 195, 247, 255)
PINK = (255, 143, 163, 255)
BLACK = (27, 27, 27, 255)
YELLOW = (255, 212, 59)


def shade(rgb, k):
    return tuple(max(0, min(255, int(c * k))) for c in rgb[:3]) + (255,)


def blank():
    return Image.new("RGBA", (16, 16), (0, 0, 0, 0))


def put(img, pixels, color):
    for x, y in pixels:
        if 0 <= x < 16 and 0 <= y < 16:
            img.putpixel((x, y), color)


def mirror(pixels):
    return [(15 - x, y) for x, y in pixels]


def both(pixels):
    return pixels + mirror(pixels)


def shape(rows, color, img=None, outline=0.62, light=1.12, top=0):
    """Fills the X cells of an ASCII picture, darker along the edge, lighter toward the upper left."""
    img = img or blank()
    cells = set()
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch == "X":
                cells.add((x, y + top))
    for x, y in cells:
        edge = any((x + dx, y + dy) not in cells for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))
        if edge:
            c = shade(color, outline)
        elif (x - 1, y - 1) in cells and (x - 2, y - 2) not in cells and (x - 1, y - 2) not in cells:
            c = shade(color, light)
        else:
            c = shade(color, 1.0)
        img.putpixel((x, y), c)
    return img


def face(fill=YELLOW):
    img = blank()
    cells = set()
    for y in range(16):
        for x in range(16):
            if (x - 7.5) ** 2 + (y - 7.5) ** 2 <= 7.3 ** 2:
                cells.add((x, y))
    for x, y in cells:
        edge = any((x + dx, y + dy) not in cells for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))
        if edge:
            c = shade(fill, 0.6)
        elif (x - 5) ** 2 + (y - 4) ** 2 < 9:
            c = shade(fill, 1.1)
        elif (x - 7.5) ** 2 + (y - 7.5) ** 2 > 5.6 ** 2 and x + y > 17:
            c = shade(fill, 0.88)
        else:
            c = shade(fill, 1.0)
        img.putpixel((x, y), c)
    return img


# --- eyes -------------------------------------------------------------------------------
EYE_L = [(4, 5), (5, 5), (4, 6), (5, 6)]
EYES_DOT = both(EYE_L)
EYES_TALL = both([(4, 4), (5, 4), (4, 5), (5, 5), (4, 6), (5, 6)])
HAPPY_L = [(3, 6), (4, 5), (5, 5), (6, 6)]
EYES_HAPPY = both(HAPPY_L)
LINE_L = [(3, 6), (4, 6), (5, 6), (6, 6)]
EYES_LINE = both(LINE_L)
EYES_SAD = both([(3, 5), (4, 6), (5, 6), (6, 5)])
EYES_SQUINT = [(4, 5), (5, 6), (4, 7), (11, 5), (10, 6), (11, 7)]
HEART_L = [(3, 4), (5, 4), (3, 5), (4, 5), (5, 5), (4, 6)]
BROWS_ANGRY = both([(3, 3), (4, 3), (5, 4), (6, 4)])
BROWS_WORRIED = both([(3, 4), (4, 4), (5, 3), (6, 3)])


def wide_eyes(img, pupil_dy=0):
    for ox in (3, 10):
        put(img, [(ox + dx, 4 + dy) for dx in range(3) for dy in range(3)], WHITE)
        put(img, [(ox + 1, 5 + pupil_dy)], BLACK)


# --- mouths -----------------------------------------------------------------------------
SMILE = [(4, 9), (11, 9), (5, 10), (10, 10), (6, 11), (7, 11), (8, 11), (9, 11)]
SLIGHT = [(5, 10), (10, 10), (6, 11), (7, 11), (8, 11), (9, 11)]
FROWN = [(6, 10), (7, 10), (8, 10), (9, 10), (5, 11), (10, 11)]
FLAT = [(5, 11), (6, 11), (7, 11), (8, 11), (9, 11), (10, 11)]
SMIRK = [(6, 11), (7, 11), (8, 11), (9, 11), (10, 10), (11, 9)]
SKEW = [(5, 11), (6, 11), (7, 11), (8, 10), (9, 10), (10, 10)]
ZIGZAG = [(4, 11), (5, 10), (6, 11), (7, 10), (8, 11), (9, 10), (10, 11), (11, 10)]


def grin(img):
    put(img, [(x, 9) for x in range(4, 12)] + [(4, 10), (11, 10), (5, 11), (10, 11)] + [(x, 12) for x in range(6, 10)], DARK)
    put(img, [(x, 10) for x in range(5, 11)], WHITE)
    put(img, [(x, 11) for x in range(6, 10)], MOUTH)


def teeth(img):
    put(img, [(x, 9) for x in range(4, 12)] + [(4, 10), (11, 10)] + [(x, 11) for x in range(4, 12)], DARK)
    put(img, [(x, 10) for x in range(5, 11)], WHITE)


def open_o(img, big=False):
    if big:
        put(img, [(6, 9), (7, 9), (8, 9), (9, 9), (5, 10), (10, 10), (5, 11), (10, 11), (5, 12), (10, 12), (6, 13), (7, 13), (8, 13), (9, 13)], DARK)
        put(img, [(x, y) for x in range(6, 10) for y in range(10, 13)], MOUTH)
    else:
        put(img, [(7, 9), (8, 9), (6, 10), (9, 10), (6, 11), (9, 11), (7, 12), (8, 12)], DARK)
        put(img, [(7, 10), (8, 10), (7, 11), (8, 11)], MOUTH)


def tongue(img, side=False):
    put(img, [(8, 12), (9, 12), (8, 13), (9, 13)] if side else [(7, 12), (8, 12), (7, 13), (8, 13)], RED)


def blush(img):
    put(img, both([(2, 8), (3, 8), (3, 9)]), PINK)


def sweat(img):
    put(img, [(12, 2), (12, 3), (13, 3), (12, 4), (13, 4)], BLUE)
    put(img, [(13, 4)], shade(BLUE[:3], 0.8))


def small_heart(img, x, y, color=RED):
    put(img, [(x, y), (x + 2, y), (x, y + 1), (x + 1, y + 1), (x + 2, y + 1), (x + 1, y + 2)], color)


def make_faces():
    out = []

    def add(names, build, fill=YELLOW):
        img = face(fill)
        build(img)
        out.append((names, img))

    def f(eyes=None, mouth=None, extra=None, eye_color=DARK):
        def build(img):
            if eyes:
                put(img, eyes, eye_color)
            if callable(mouth):
                mouth(img)
            elif mouth:
                put(img, mouth, DARK)
            if extra:
                extra(img)
        return build

    add("grinning", f(EYES_DOT, grin))
    add("smiley", f(EYES_TALL, grin))
    add("smile", f(EYES_HAPPY, grin))
    add("grin", f(EYES_HAPPY, teeth))
    add("laughing satisfied", f(EYES_SQUINT, grin))
    add("sweat_smile", f(EYES_HAPPY, grin, sweat))
    add("joy", f(EYES_HAPPY, grin, lambda i: put(i, both([(2, 7), (2, 8), (1, 8), (2, 9)]), BLUE)))
    add("slight_smile", f(EYES_DOT, SLIGHT))
    out.append(("upside_down", out[-1][1].transpose(Image.FLIP_TOP_BOTTOM)))
    add("wink", f(EYE_L + mirror(LINE_L), SMILE))
    add("blush", f(EYES_HAPPY, SMILE, blush))
    add("innocent", f(EYES_DOT, SMILE, lambda i: put(i, [(x, 0) for x in range(4, 12)] + [(3, 1), (12, 1)], (110, 198, 255, 255))))
    add("heart_eyes", f(None, SMILE, lambda i: (put(i, HEART_L, RED), put(i, mirror(HEART_L), RED))))
    add("kissing_heart", f(EYE_L + mirror(LINE_L), [(7, 10), (8, 11), (7, 12)], lambda i: small_heart(i, 11, 10)))
    add("yum", f(EYES_HAPPY, SMILE, lambda i: tongue(i, True)))
    add("stuck_out_tongue", f(EYES_DOT, SMILE, tongue))
    add("stuck_out_tongue_winking_eye", f(EYE_L + mirror(LINE_L), SMILE, tongue))

    def glasses(img):
        put(img, [(x, 5) for x in range(2, 14)], BLACK)
        put(img, [(x, y) for x in list(range(3, 7)) + list(range(9, 13)) for y in (6, 7)], BLACK)
        put(img, [(4, 6), (10, 6)], (120, 120, 130, 255))
    add("sunglasses", f(None, SMILE, glasses))
    add("smirk", f(EYES_DOT, SMIRK))
    add("neutral_face", f(EYES_DOT, FLAT))
    add("expressionless", f(EYES_LINE, FLAT))
    add("unamused", f(EYES_LINE + [(5, 7), (6, 7), (11, 7), (12, 7)], FROWN))
    add("rolling_eyes", f(None, FLAT, lambda i: wide_eyes(i, -1)))

    def thinking(img):
        put(img, [(9, 2), (10, 2), (11, 2), (12, 3), (3, 4), (4, 4), (5, 4)], DARK)
        hand = shade((242, 153, 0), 1.0)
        put(img, [(3, 12), (4, 12), (5, 12), (6, 12), (4, 13), (5, 13), (6, 13), (6, 11)], hand)
        put(img, [(3, 13), (7, 12), (7, 11)], shade((242, 153, 0), 0.7))
    add("thinking", f(EYES_DOT, [(7, 10), (8, 10), (9, 10), (10, 9)], thinking))
    add("flushed", f(None, [(6, 11), (7, 11), (8, 11), (9, 11)], lambda i: (wide_eyes(i), blush(i))))
    add("pensive", f(EYES_SAD, [(6, 11), (7, 10), (8, 10), (9, 11)]))
    add("confused", f(EYES_DOT, SKEW))
    add("worried", f(EYES_DOT + BROWS_WORRIED, FROWN))
    add("slight_frown frowning", f(EYES_DOT, FROWN))
    add("open_mouth", f(EYES_DOT, open_o))
    add("astonished", f(None, open_o, wide_eyes))
    add("cry", f(EYES_DOT, FROWN, lambda i: put(i, [(5, 7), (5, 8), (5, 9), (4, 9)], BLUE)))
    add("sob", f(EYES_SAD, lambda i: open_o(i, True), lambda i: put(i, both([(4, 7), (4, 8), (4, 9), (4, 10), (3, 9), (3, 10), (3, 11)]), BLUE)))

    def scared(img):
        for y in range(1, 4):
            for x in range(16):
                if img.getpixel((x, y))[3] and (x - 7.5) ** 2 + (y - 7.5) ** 2 < 6.4 ** 2:
                    img.putpixel((x, y), (150, 200, 245, 255) if y < 3 else (205, 215, 160, 255))
        wide_eyes(img)
    add("scream", f(None, lambda i: open_o(i, True), scared))
    add("fearful", f(None, FROWN, scared))
    add("sweat", f(EYES_DOT, FROWN, sweat))
    add("angry", f(EYES_DOT + BROWS_ANGRY, FROWN))
    add("rage", f(EYES_DOT + BROWS_ANGRY, FROWN), fill=(230, 74, 59))

    def zzz(img):
        put(img, [(11, 0), (12, 0), (13, 0), (12, 1), (11, 2), (12, 2), (13, 2)], (60, 110, 220, 255))
    add("sleeping", f(EYES_SAD, lambda i: open_o(i), zzz))
    add("nauseated_face sick", f(EYES_LINE + BROWS_WORRIED, ZIGZAG), fill=(139, 195, 74))

    def horns(img):
        put(img, both([(1, 0), (2, 1), (1, 1), (2, 2)]), (90, 40, 150, 255))
    add("smiling_imp", f(EYES_DOT + BROWS_ANGRY, SMILE, horns), fill=(156, 91, 214))
    return out


def recolor_heart(color):
    rows = ["................",
            "................",
            "...XXX....XXX...",
            "..XXXXX..XXXXX..",
            ".XXXXXXXXXXXXXX.",
            ".XXXXXXXXXXXXXX.",
            ".XXXXXXXXXXXXXX.",
            ".XXXXXXXXXXXXXX.",
            "..XXXXXXXXXXXX..",
            "...XXXXXXXXXX...",
            "....XXXXXXXX....",
            ".....XXXXXX.....",
            "......XXXX......",
            ".......XX.......",
            "................",
            "................"]
    img = shape(rows, color)
    put(img, [(3, 4), (4, 4), (3, 5)], shade(color, 1.45))
    return img


def make_symbols():
    out = []
    for name, color in (("heart", (221, 46, 68)), ("orange_heart", (244, 144, 12)), ("yellow_heart", (253, 203, 88)),
                        ("green_heart", (120, 177, 89)), ("blue_heart", (93, 173, 236)), ("purple_heart", (170, 142, 214)),
                        ("black_heart", (70, 76, 84)), ("white_heart", (230, 231, 232))):
        out.append((name, recolor_heart(color)))
    broken = recolor_heart((221, 46, 68))
    put(broken, [(8, 3), (7, 4), (8, 5), (7, 6), (8, 7), (7, 8), (8, 9), (7, 10), (8, 11), (7, 12)], (0, 0, 0, 0))
    out.append(("broken_heart", broken))

    fire = shape(["................",
                  ".......X........",
                  "......XX........",
                  "......XXX.......",
                  ".....XXXX..X....",
                  "....XXXXXX.XX...",
                  "...XXXXXXXXXX...",
                  "...XXXXXXXXXXX..",
                  "..XXXXXXXXXXXX..",
                  "..XXXXXXXXXXXX..",
                  "..XXXXXXXXXXXX..",
                  "..XXXXXXXXXXXX..",
                  "...XXXXXXXXXX...",
                  "....XXXXXXXX....",
                  ".....XXXXXX.....",
                  "................"], (244, 100, 20), outline=0.75)
    shape(["........",
           "........",
           "........",
           "........",
           "........",
           "........",
           ".......X........",
           "......XXX.......",
           "......XXXX......",
           ".....XXXXXX.....",
           ".....XXXXXX.....",
           ".....XXXXXX.....",
           "......XXXX......",
           "................"], (255, 204, 60), fire, outline=1.0, light=1.0)
    out.append(("fire flame", fire))

    out.append(("star", shape(["................",
                               ".......XX.......",
                               ".......XX.......",
                               "......XXXX......",
                               "......XXXX......",
                               ".XXXXXXXXXXXXXX.",
                               "..XXXXXXXXXXXX..",
                               "...XXXXXXXXXX...",
                               "....XXXXXXXX....",
                               "....XXXXXXXX....",
                               "...XXXXXXXXXX...",
                               "...XXXX..XXXX...",
                               "..XXX......XXX..",
                               "..XX........XX..",
                               "................",
                               "................"], (255, 204, 60), outline=0.72)))

    sparkles = blank()
    for cx, cy, r in ((5, 5, 3), (11, 10, 2), (12, 3, 1)):
        put(sparkles, [(cx, cy)] + [(cx + d, cy) for d in range(-r, r + 1)] + [(cx, cy + d) for d in range(-r, r + 1)], (255, 204, 60, 255))
        put(sparkles, [(cx, cy)], (255, 245, 190, 255))
        if r > 1:
            put(sparkles, [(cx - 1, cy - 1), (cx + 1, cy - 1), (cx - 1, cy + 1), (cx + 1, cy + 1)], (255, 220, 110, 255))
    out.append(("sparkles", sparkles))

    out.append(("zap", shape(["................",
                              ".......XXXX.....",
                              "......XXXX......",
                              ".....XXXX.......",
                              "....XXXX........",
                              "...XXXXXXXX.....",
                              "...XXXXXXX......",
                              "......XXX.......",
                              ".....XXX........",
                              ".....XX.........",
                              "....XX..........",
                              "....X...........",
                              "................",
                              "................",
                              "................",
                              "................"], (255, 204, 60), outline=0.7, top=1)))

    hundred = blank()
    red = (221, 46, 68, 255)
    digits = {"1": ["X.", "X.", "X.", "X.", "X."][0:5], "0": ["XXX", "X.X", "X.X", "X.X", "XXX"]}
    x = 2
    for ch in "100":
        rows = ["XX", ".X", ".X", ".X", ".X"] if ch == "1" else digits["0"]
        for dy, row in enumerate(rows):
            for dx, c in enumerate(row):
                if c == "X":
                    put(hundred, [(x + dx, 3 + dy), (x + dx, 4 + dy)] if False else [(x + dx, 3 + dy)], red)
        x += len(rows[0]) + 1
    # taller digits: double each row
    tall = blank()
    for y in range(5):
        for xx in range(16):
            if hundred.getpixel((xx, 3 + y))[3]:
                put(tall, [(xx + 1, 2 + y * 2), (xx + 1, 3 + y * 2)], red)
    put(tall, [(xx, 13) for xx in range(2, 14)] + [(xx, 15) for xx in range(4, 12)], red)
    out.append(("100", tall))

    thumb_rows = ["................",
                  ".......XX.......",
                  "......XXX.......",
                  "......XXX.......",
                  ".....XXXX.......",
                  ".....XXX........",
                  ".....XXXXXXXX...",
                  ".....XXXXXXXXX..",
                  ".....XXXXXXXXX..",
                  ".....XXXXXXXX...",
                  ".....XXXXXXXX...",
                  ".....XXXXXXX....",
                  ".....XXXXXXX....",
                  "......XXXXX.....",
                  "................",
                  "................"]
    thumb = shape(thumb_rows, (255, 204, 77), outline=0.68)
    shape(["..XX" if 6 <= i <= 13 else "...." for i in range(16)], (74, 144, 217), thumb, outline=0.8)
    put(thumb, [(8, 8), (9, 8), (10, 8), (11, 8), (8, 10), (9, 10), (10, 10)], shade((255, 204, 77), 0.8))
    out.append(("thumbsup +1", thumb))
    out.append(("thumbsdown -1", thumb.transpose(Image.FLIP_TOP_BOTTOM)))

    eyes = blank()
    for ox in (1, 9):
        cells = [(ox + dx, 4 + dy) for dy, row in enumerate(["..XX..", ".XXXX.", "XXXXXX", "XXXXXX", "XXXXXX", "XXXXXX", ".XXXX.", "..XX.."])
                 for dx, c in enumerate(row) if c == "X"]
        put(eyes, cells, (245, 245, 245, 255))
        cs = set(cells)
        put(eyes, [c for c in cells if any((c[0] + dx, c[1] + dy) not in cs for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))], (120, 120, 125, 255))
        put(eyes, [(ox + 1, 7), (ox + 2, 7), (ox + 1, 8), (ox + 2, 8), (ox + 1, 9), (ox + 2, 9)], BLACK)
    out.append(("eyes", eyes))

    check = shape(["................",
                   ".XXXXXXXXXXXXXX.",
                   "XXXXXXXXXXXXXXXX"] + ["XXXXXXXXXXXXXXXX"] * 11 + [".XXXXXXXXXXXXXX.", "................"], (76, 175, 80), outline=0.8)
    put(check, [(3, 8), (4, 8), (4, 9), (5, 9), (5, 10), (6, 10), (6, 11), (7, 10), (7, 9), (8, 9), (8, 8), (9, 8), (9, 7), (10, 7), (10, 6),
                (11, 6), (11, 5), (12, 5), (12, 4), (6, 9), (7, 11), (3, 7)], WHITE)
    out.append(("white_check_mark check", check))

    cross = blank()
    for i in range(2, 14):
        put(cross, [(i, i), (i + 1, i), (i, i + 1), (15 - i, i), (14 - i, i), (15 - i, i + 1)], (221, 46, 68, 255))
    out.append(("x", cross))

    warning = shape(["................",
                     ".......XX.......",
                     "......XXXX......",
                     "......XXXX......",
                     ".....XXXXXX.....",
                     ".....XXXXXX.....",
                     "....XXXXXXXX....",
                     "....XXXXXXXX....",
                     "...XXXXXXXXXX...",
                     "...XXXXXXXXXX...",
                     "..XXXXXXXXXXXX..",
                     "..XXXXXXXXXXXX..",
                     ".XXXXXXXXXXXXXX.",
                     ".XXXXXXXXXXXXXX.",
                     "................",
                     "................"], (255, 204, 60), outline=0.65)
    put(warning, [(7, 5), (8, 5), (7, 6), (8, 6), (7, 7), (8, 7), (7, 8), (8, 8), (7, 9), (8, 9), (7, 11), (8, 11)], BLACK)
    out.append(("warning", warning))

    question = blank()
    put(question, [(5, 3), (5, 4), (6, 2), (7, 1), (8, 1), (9, 1), (10, 2), (11, 3), (11, 4), (10, 5), (9, 6), (8, 7), (8, 8), (8, 9),
                   (6, 3), (7, 2), (10, 3), (10, 4), (9, 5), (8, 6), (7, 7), (7, 8), (7, 9), (6, 4), (9, 2), (8, 2),
                   (7, 12), (8, 12), (7, 13), (8, 13)], (221, 46, 68, 255))
    out.append(("question", question))
    exclamation = blank()
    put(exclamation, [(x, y) for x in (7, 8) for y in range(1, 10)] + [(6, y) for y in range(1, 5)] + [(9, y) for y in range(1, 5)]
        + [(7, 12), (8, 12), (7, 13), (8, 13)], (221, 46, 68, 255))
    out.append(("exclamation", exclamation))

    entry = face((221, 46, 68))
    put(entry, [(x, y) for x in range(3, 13) for y in (7, 8)], WHITE)
    out.append(("no_entry", entry))

    zzz = blank()
    for ox, oy, n in ((1, 9, 5), (6, 5, 4), (10, 1, 4)):
        put(zzz, [(ox + i, oy) for i in range(n)] + [(ox + i, oy + n - 1) for i in range(n)] + [(ox + n - 1 - i, oy + i) for i in range(n)],
            (60, 110, 220, 255))
    out.append(("zzz", zzz))

    note = blank()
    put(note, [(6, 2), (7, 2), (8, 2), (9, 2), (10, 2), (11, 2), (6, 3), (11, 3), (6, 4), (11, 4), (6, 5), (11, 5), (6, 6), (11, 6), (6, 7),
               (11, 7), (6, 8), (11, 8), (6, 9), (11, 9), (6, 3), (7, 3), (8, 3), (9, 3), (10, 3)], (110, 170, 240, 255))
    put(note, [(3, 10), (4, 9), (5, 9), (6, 10), (3, 11), (4, 11), (5, 11), (6, 11), (4, 12), (5, 12), (4, 10), (5, 10),
               (8, 10), (9, 9), (10, 9), (11, 10), (8, 11), (9, 11), (10, 11), (11, 11), (9, 12), (10, 12), (9, 10), (10, 10)], (110, 170, 240, 255))
    out.append(("musical_note notes", note))

    sun = blank()
    put(sun, [(7, 0), (8, 0), (7, 1), (8, 1), (7, 14), (8, 14), (7, 15), (8, 15), (0, 7), (0, 8), (1, 7), (1, 8), (14, 7), (14, 8), (15, 7),
              (15, 8), (2, 2), (3, 3), (13, 2), (12, 3), (2, 13), (3, 12), (13, 13), (12, 12)], (255, 172, 51, 255))
    for y in range(16):
        for x in range(16):
            d = (x - 7.5) ** 2 + (y - 7.5) ** 2
            if d <= 4.6 ** 2:
                sun.putpixel((x, y), (255, 204, 60, 255) if d < 3.7 ** 2 else (240, 160, 30, 255))
    out.append(("sunny sun", sun))

    moon = blank()
    for y in range(16):
        for x in range(16):
            if (x - 7.5) ** 2 + (y - 7.5) ** 2 <= 6.8 ** 2 and (x - 11.0) ** 2 + (y - 5.5) ** 2 > 5.6 ** 2:
                moon.putpixel((x, y), (255, 217, 102, 255))
    out.append(("crescent_moon moon", moon))

    snow = blank()
    ice = (130, 200, 245, 255)
    put(snow, [(7, y) for y in range(1, 15)] + [(8, y) for y in range(1, 15)] + [(x, 7) for x in range(1, 15)] + [(x, 8) for x in range(1, 15)], ice)
    put(snow, [(3 + i, 3 + i) for i in range(10)] + [(12 - i, 3 + i) for i in range(10)], ice)
    put(snow, [(5, 1), (10, 1), (5, 14), (10, 14), (1, 5), (1, 10), (14, 5), (14, 10)], ice)
    out.append(("snowflake", snow))

    out.append(("cloud", shape(["................",
                                "................",
                                "................",
                                ".....XXXX.......",
                                "....XXXXXX......",
                                "...XXXXXXXXXX...",
                                "..XXXXXXXXXXXX..",
                                ".XXXXXXXXXXXXXX.",
                                "XXXXXXXXXXXXXXXX",
                                "XXXXXXXXXXXXXXXX",
                                ".XXXXXXXXXXXXXX.",
                                "..XXXXXXXXXXXX..",
                                "................",
                                "................",
                                "................",
                                "................"], (236, 240, 245), outline=0.72)))

    crown = shape(["................",
                   "................",
                   "................",
                   ".X.....XX.....X.",
                   ".XX....XX....XX.",
                   ".XXX..XXXX..XXX.",
                   ".XXXX.XXXX.XXXX.",
                   ".XXXXXXXXXXXXXX.",
                   ".XXXXXXXXXXXXXX.",
                   ".XXXXXXXXXXXXXX.",
                   ".XXXXXXXXXXXXXX.",
                   ".XXXXXXXXXXXXXX.",
                   "................",
                   "................",
                   "................",
                   "................"], (255, 204, 60), outline=0.68)
    put(crown, [(4, 9), (7, 9), (8, 9), (11, 9)], (221, 46, 68, 255))
    out.append(("crown", crown))

    trophy = shape(["................",
                    "...XXXXXXXXXX...",
                    ".XXXXXXXXXXXXXX.",
                    ".X.XXXXXXXXXX.X.",
                    ".X.XXXXXXXXXX.X.",
                    ".XXXXXXXXXXXXXX.",
                    "...XXXXXXXXXX...",
                    "....XXXXXXXX....",
                    ".....XXXXXX.....",
                    "......XXXX......",
                    ".......XX.......",
                    ".......XX.......",
                    ".....XXXXXX.....",
                    "....XXXXXXXX....",
                    "................",
                    "................"], (255, 204, 60), outline=0.68)
    out.append(("trophy", trophy))

    skull = shape(["................",
                   "....XXXXXXXX....",
                   "...XXXXXXXXXX...",
                   "..XXXXXXXXXXXX..",
                   "..XXXXXXXXXXXX..",
                   "..XXXXXXXXXXXX..",
                   "..XXXXXXXXXXXX..",
                   "..XXXXXXXXXXXX..",
                   "..XXXXXXXXXXXX..",
                   "...XXXXXXXXXX...",
                   "....XXXXXXXX....",
                   "....XXXXXXXX....",
                   "....XXXXXXXX....",
                   ".....XXXXXX.....",
                   "................",
                   "................"], (232, 234, 237), outline=0.6)
    put(skull, [(4, 5), (5, 5), (6, 5), (4, 6), (5, 6), (6, 6), (4, 7), (5, 7), (6, 7), (9, 5), (10, 5), (11, 5), (9, 6), (10, 6), (11, 6),
                (9, 7), (10, 7), (11, 7), (7, 9), (8, 9), (6, 11), (8, 11), (10, 11), (6, 12), (8, 12), (10, 12)], BLACK)
    out.append(("skull", skull))

    poop = shape(["................",
                  "................",
                  ".......XX.......",
                  "......XXXX......",
                  ".....XXXXX......",
                  "....XXXXXXXX....",
                  "....XXXXXXXX....",
                  "...XXXXXXXXXX...",
                  "..XXXXXXXXXXXX..",
                  "..XXXXXXXXXXXX..",
                  ".XXXXXXXXXXXXXX.",
                  ".XXXXXXXXXXXXXX.",
                  ".XXXXXXXXXXXXXX.",
                  "..XXXXXXXXXXXX..",
                  "................",
                  "................"], (136, 92, 58), outline=0.62)
    put(poop, [(5, 8), (6, 8), (9, 8), (10, 8), (5, 9), (6, 9), (9, 9), (10, 9)], WHITE)
    put(poop, [(6, 9), (10, 9)], BLACK)
    put(poop, [(5, 11), (10, 11), (6, 12), (7, 12), (8, 12), (9, 12)], WHITE)
    out.append(("poop hankey", poop))

    ghost = shape(["................",
                   ".....XXXXXX.....",
                   "...XXXXXXXXXX...",
                   "..XXXXXXXXXXXX..",
                   "..XXXXXXXXXXXX..",
                   "..XXXXXXXXXXXX..",
                   "..XXXXXXXXXXXX..",
                   "..XXXXXXXXXXXX..",
                   "..XXXXXXXXXXXX..",
                   "..XXXXXXXXXXXX..",
                   "..XXXXXXXXXXXX..",
                   "..XXXXXXXXXXXX..",
                   "..XXXXXXXXXXXX..",
                   "..XX.XXXX.XXXX..",
                   "..X...XX...XX...",
                   "................"], (240, 242, 245), outline=0.68)
    put(ghost, [(5, 5), (5, 6), (10, 5), (10, 6), (7, 9), (8, 9), (7, 10), (8, 10)], BLACK)
    out.append(("ghost", ghost))

    alien = shape(["................",
                   "....XXXXXXXX....",
                   "..XXXXXXXXXXXX..",
                   ".XXXXXXXXXXXXXX.",
                   ".XXXXXXXXXXXXXX.",
                   ".XXXXXXXXXXXXXX.",
                   ".XXXXXXXXXXXXXX.",
                   ".XXXXXXXXXXXXXX.",
                   "..XXXXXXXXXXXX..",
                   "..XXXXXXXXXXXX..",
                   "...XXXXXXXXXX...",
                   "....XXXXXXXX....",
                   ".....XXXXXX.....",
                   "......XXXX......",
                   "................",
                   "................"], (130, 200, 120), outline=0.62)
    put(alien, [(3, 5), (4, 5), (4, 6), (5, 6), (5, 7), (6, 7), (3, 6), (4, 7), (12, 5), (11, 5), (11, 6), (10, 6), (10, 7), (9, 7), (12, 6),
                (11, 7), (7, 11), (8, 11)], BLACK)
    out.append(("alien", alien))

    robot = shape(["................",
                   ".......XX.......",
                   ".......XX.......",
                   "..XXXXXXXXXXXX..",
                   "..XXXXXXXXXXXX..",
                   "XXXXXXXXXXXXXXXX",
                   "XXXXXXXXXXXXXXXX",
                   "XXXXXXXXXXXXXXXX",
                   "..XXXXXXXXXXXX..",
                   "..XXXXXXXXXXXX..",
                   "..XXXXXXXXXXXX..",
                   "..XXXXXXXXXXXX..",
                   "..XXXXXXXXXXXX..",
                   "................",
                   "................",
                   "................"], (160, 170, 180), outline=0.6)
    put(robot, [(4, 5), (5, 5), (4, 6), (5, 6), (10, 5), (11, 5), (10, 6), (11, 6)], (80, 220, 240, 255))
    put(robot, [(4, 10), (6, 10), (8, 10), (10, 10), (5, 10), (7, 10), (9, 10), (11, 10)], BLACK)
    put(robot, [(5, 10), (7, 10), (9, 10), (11, 10)], WHITE)
    put(robot, [(7, 0), (8, 0)], (221, 46, 68, 255))
    out.append(("robot", robot))

    creeper = blank()
    greens = [(93, 187, 58), (80, 168, 50), (108, 200, 70), (86, 176, 54)]
    for y in range(1, 15):
        for x in range(1, 15):
            creeper.putpixel((x, y), greens[(x * 7 + y * 13 + (x // 2) * (y // 2)) % 4] + (255,))
    art = ["........", ".XX..XX.", ".XX..XX.", "...XX...", "..XXXX..", "..XXXX..", "..X..X..", "........"]
    for y, row in enumerate(art):
        for x, c in enumerate(row):
            if c == "X":
                put(creeper, [(x * 2 + dx, y * 2 + dy) for dx in (0, 1) for dy in (0, 1) if 1 <= x * 2 + dx <= 14 and 1 <= y * 2 + dy <= 14], BLACK)
    out.append(("creeper", creeper))
    return out


def main():
    emotes = make_faces() + make_symbols()
    assert len(emotes) <= 256
    sheet = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
    lines = ["# index names. Generated by tools/make_emotes.py; the art is in textures/font/unicode_page_e0.png of the minecraft domain."]
    seen = set()
    for i, (names, img) in enumerate(emotes):
        sheet.paste(img, ((i % 16) * 16, (i // 16) * 16))
        for name in names.split():
            assert name not in seen, name
            seen.add(name)
        lines.append("%d %s" % (i, names))
    font_dir = os.path.join(ROOT, "minecraft", "textures", "font")
    os.makedirs(font_dir, exist_ok=True)
    sheet.save(os.path.join(font_dir, "unicode_page_e0.png"))
    with open(os.path.join(ROOT, "better112", "emotes.txt"), "w") as f:
        f.write("\n".join(lines) + "\n")
    print("%d emotes, %d names" % (len(emotes), len(seen)))
    if len(sys.argv) > 1:
        cols = 12
        rows = (len(emotes) + cols - 1) // cols
        scale = 5
        preview = Image.new("RGBA", (cols * 16 * scale + 8, rows * 16 * scale + 8), (54, 57, 63, 255))
        for i, (_, img) in enumerate(emotes):
            big = img.resize((16 * scale, 16 * scale), Image.NEAREST)
            preview.paste(big, (4 + (i % cols) * 16 * scale, 4 + (i // cols) * 16 * scale), big)
        preview.save(sys.argv[1])


if __name__ == "__main__":
    main()
