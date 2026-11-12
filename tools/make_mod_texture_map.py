#!/usr/bin/env python3
"""Pairs a mod's 1.12.2 textures with the textures of its modern release.

usage: make_mod_texture_map.py NAME --old OLD.jar [...] --new NEW.jar [...]
           [--source REGEX ...] [--drop TOKEN,...] [--alias OLD=NEW,...]
           [--only-fuzzy-above 0.8] [--out FILE.map] [--sheet REVIEW.png]

Mods renamed nearly every texture on the way to modern Minecraft
(items/material/ingot_tin -> item/tin_ingot), so names are compared as bags of words:
split on / and _, singular, with folder words that carry no meaning dropped. A pair is
accepted when the bags are equal, the kind (block, item, entity, ...) is the same and the
two images have the same size, and nothing else competes for either side. A second, looser
pass accepts near matches; those are marked "fuzzy" in the review sheet so a person can
look at them before they ship.

The output is a .map file for src/main/resources/assets/better112/mods/. Lines starting with
@source are regular expressions for the file names of the modern jars the textures come
from; the mod looks for those jars on the player's machine at runtime.
"""
import argparse
import io
import re
import struct
import zipfile
from collections import defaultdict

KINDS = {"blocks": "block", "block": "block", "items": "item", "item": "item", "entity": "entity",
         "entities": "entity", "models": "model", "model": "model", "gui": "gui", "guis": "gui",
         "armor": "armor", "particle": "particle", "particles": "particle"}
# Folder words that only group files.
DROP = {"material", "materials", "misc", "storage", "common", "base", "textures", "texture"}


def read_textures(jar_path):
    """{(domain, path): (width, height, png bytes, has mcmeta)}, including jars nested in META-INF/jarjar."""
    out = {}

    def scan(zf):
        names = set(zf.namelist())
        for name in names:
            m = re.match(r"assets/([^/]+)/textures/(.+)\.png$", name)
            if m:
                data = zf.read(name)
                if data[:8] != b"\x89PNG\r\n\x1a\n":
                    continue
                w, h = struct.unpack(">II", data[16:24])
                out[(m.group(1), m.group(2))] = (w, h, data, (name + ".mcmeta") in names)
            elif name.startswith(("META-INF/jarjar/", "META-INF/jars/")) and name.endswith(".jar"):
                try:
                    with zipfile.ZipFile(io.BytesIO(zf.read(name))) as nested:
                        scan(nested)
                except zipfile.BadZipFile:
                    pass

    with zipfile.ZipFile(jar_path) as zf:
        scan(zf)
    return out


def words(path, drop, alias):
    path = re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", path).lower()
    parts = [p for p in re.split(r"[/_\-. ]+", path) if p]
    kind = "other"
    if parts and parts[0] in KINDS:
        kind = KINDS[parts[0]]
        parts = parts[1:]
    bag = []
    for p in parts:
        p = alias.get(p, p)
        if not p:
            continue
        if len(p) > 3 and p.endswith("s") and not p.endswith("ss"):
            p = p[:-1]
        p = alias.get(p, p)
        if p in drop or not p:
            continue
        bag.extend(p.split("+"))
    return kind, tuple(sorted(bag))


def fits(old_info, new_info):
    """Same shape (the resolution may differ, as the mod converts that), or a square still that the
    modern release turned into an animation strip."""
    if old_info[0] * new_info[1] == old_info[1] * new_info[0]:
        return True
    ow, oh, _, old_meta = old_info
    nw, nh, _, new_meta = new_info
    return ow == oh and not old_meta and new_meta and nw == ow and nh % nw == 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("name")
    ap.add_argument("--old", nargs="+", required=True)
    ap.add_argument("--new", nargs="+", required=True)
    ap.add_argument("--source", nargs="*", default=[])
    ap.add_argument("--drop", default="")
    ap.add_argument("--alias", default="")
    ap.add_argument("--fuzzy", type=float, default=0.8)
    ap.add_argument("--skip", default="", help="regex of old paths to leave alone")
    ap.add_argument("--manual", default="", help="file of old=new lines that override matching")
    ap.add_argument("--rule", action="append", default=[],
                    help="REGEX=>TEMPLATE on 'domain:path'; used when the result exists in the modern jars")
    ap.add_argument("--out")
    ap.add_argument("--sheet")
    ap.add_argument("--sheet-all", action="store_true")
    ap.add_argument("--unmatched", action="store_true", help="list the old textures left without a pair")
    ap.add_argument("--same-size", action="store_true",
                    help="never change a texture's resolution (for mods whose larger art is padded for oversized icons)")
    a = ap.parse_args()

    drop = DROP | {t for t in a.drop.split(",") if t}
    alias = dict(kv.split("=") for kv in a.alias.split(",") if kv)
    old, new = {}, {}
    for j in a.old:
        old.update(read_textures(j))
    for j in a.new:
        new.update(read_textures(j))
    skip = re.compile(a.skip) if a.skip else None

    by_key = defaultdict(list)
    for key, (w, h, _, _) in new.items():
        kind, bag = words(key[1], drop, alias)
        by_key[(kind, bag)].append(key)

    pairs = {}      # old key -> (new key, how)
    used = defaultdict(list)
    todo = []
    manual = {}
    if a.manual:
        for line in open(a.manual):
            line = line.strip()
            if line and not line.startswith("#"):
                o, n = line.split("=")
                manual[tuple(o.split(":", 1))] = tuple(n.split(":", 1)) if n else None

    for key in sorted(old):
        w, h, _, _ = old[key]
        if key in manual:
            target = manual[key]
            if target is not None and target in new and fits(old[key], new[target]):
                pairs[key] = (target, "manual")
            elif target is not None:
                print("   manual entry not usable (missing or different size): %s:%s -> %s:%s" % (key + target))
            continue
        full = key[0] + ":" + key[1]
        if skip and skip.search(full):
            continue
        ruled = False
        for rule in a.rule:
            pattern, template = rule.split("=>")
            m = re.fullmatch(pattern, full)
            if m:
                target = tuple(m.expand(template).split(":", 1))
                if target in new and fits(old[key], new[target]):
                    pairs[key] = (target, "rule")
                    ruled = True
                    break
        if ruled:
            continue
        kind, bag = words(key[1], drop, alias)
        if not bag:
            continue
        cands = [k for k in by_key.get((kind, bag), []) if new[k][0] * h == new[k][1] * w or new[k][:2] == (w, h)]
        cands = [k for k in cands if new[k][:2] == (w, h)] or cands
        if len(cands) == 1:
            pairs[key] = (cands[0], "exact")
            used[cands[0]].append(key)
        else:
            todo.append((key, kind, bag))

    # Names written without separators (advancedcontrolcircuit = advanced_control_circuit).
    def squash(path):
        parts = re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", path).lower().split("/")
        kind = "other"
        if parts and parts[0] in KINDS:
            kind = KINDS[parts[0]]
            parts = parts[1:]
        return kind, re.sub(r"[^a-z0-9]", "", "".join(parts))

    by_squash = defaultdict(list)
    for key in new:
        by_squash[squash(key[1])].append(key)
    still = []
    for key, kind, bag in todo:
        w, h, _, _ = old[key]
        cands = [k for k in by_squash.get(squash(key[1]), []) if new[k][:2] == (w, h)]
        if len(cands) == 1:
            pairs[key] = (cands[0], "squash")
            used[cands[0]].append(key)
        else:
            still.append((key, kind, bag))
    todo = still

    # Looser pass: same kind and size, most words in common.
    new_bags = [(k, words(k[1], drop, alias)) for k in new]
    for key, kind, bag in todo:
        w, h, _, _ = old[key]
        best, second = (0, None), 0
        sb = set(bag)
        for k, (nkind, nbag) in new_bags:
            if nkind != kind or new[k][:2] != (w, h):
                continue
            nb = set(nbag)
            score = len(sb & nb) / float(len(sb | nb)) if sb | nb else 0
            if score > best[0]:
                second = best[0]
                best = (score, k)
            elif score > second:
                second = score
        if best[1] is not None and best[0] >= a.fuzzy and best[0] - second >= 0.1:
            pairs[key] = (best[1], "fuzzy %.2f" % best[0])
            used[best[1]].append(key)

    if a.same_size:
        for key in list(pairs):
            if old[key][:2] != new[pairs[key][0]][:2] and not (old[key][0] == old[key][1] == new[pairs[key][0]][0]):
                del pairs[key]

    # A modern texture that is pixel-for-pixel the old one changes nothing; leave those out.
    from PIL import Image
    same = 0
    matched_keys = set(pairs)
    claimed = {v[0] for v in pairs.values()}
    for key in list(pairs):
        try:
            a_img = Image.open(io.BytesIO(old[key][2])).convert("RGBA")
            b_img = Image.open(io.BytesIO(new[pairs[key][0]][2])).convert("RGBA")
        except Exception:
            del pairs[key]  # an image that cannot be read here cannot be trusted to convert in game either
            continue
        if a_img.size == b_img.size and a_img.tobytes() == b_img.tobytes():
            del pairs[key]
            same += 1

    # Report
    total = len(old)
    exact = sum(1 for v in pairs.values() if v[1] == "exact")
    print("%s: %d old textures, %d modern; matched %d (%d exact, %d joined-name, %d fuzzy, %d by rule)" % (
        a.name, total, len(new), len(pairs), exact,
        sum(1 for v in pairs.values() if v[1] == "squash"),
        sum(1 for v in pairs.values() if v[1].startswith("fuzzy")),
        sum(1 for v in pairs.values() if v[1] in ("manual", "rule"))))
    print("   %d more matched but are unchanged art, so they are left out" % same)
    by_folder = defaultdict(lambda: [0, 0])
    for key in old:
        folder = key[0] + ":" + "/".join(key[1].split("/")[:2 if "/" in key[1] else 1][:-1] or [key[1].split("/")[0]])
        by_folder[folder][1] += 1
        if key in pairs:
            by_folder[folder][0] += 1
    for folder in sorted(by_folder):
        m, t = by_folder[folder]
        print("   %-46s %4d / %4d" % (folder, m, t))

    if a.unmatched:
        for key in sorted(old):
            if key not in matched_keys and not (skip and skip.search(key[0] + ":" + key[1])):
                print("   unmatched %s:%s %dx%d" % (key[0], key[1], old[key][0], old[key][1]))
        for key in sorted(new):
            if key not in claimed:
                print("   unclaimed %s:%s %dx%d" % (key[0], key[1], new[key][0], new[key][1]))

    if a.out:
        with open(a.out, "w") as f:
            f.write("# %s: 1.12.2 texture = modern texture. Generated by tools/make_mod_texture_map.py.\n" % a.name)
            for s in a.source:
                f.write("@source %s\n" % s)
            for key in sorted(pairs):
                nk = pairs[key][0]
                f.write("%s:%s=%s:%s\n" % (key[0], key[1], nk[0], nk[1]))

    if a.sheet:
        from PIL import ImageDraw
        items = [(k, v) for k, v in sorted(pairs.items()) if a.sheet_all or v[1] != "exact"]
        if not a.sheet_all:
            # a sample of the exact ones too
            exacts = [(k, v) for k, v in sorted(pairs.items()) if v[1] == "exact"]
            items += exacts[::max(1, len(exacts) // 60)]
        cell_w, cell_h, cols = 230, 44, 5
        rows = (len(items) + cols - 1) // cols
        sheet = Image.new("RGB", (cell_w * cols, max(1, rows) * cell_h), (48, 48, 56))
        d = ImageDraw.Draw(sheet)
        for i, (k, (nk, how)) in enumerate(items):
            x, y = (i % cols) * cell_w, (i // cols) * cell_h
            for j, (src, key) in enumerate(((old, k), (new, nk))):
                im = Image.open(io.BytesIO(src[key][2])).convert("RGBA")
                im = im.crop((0, 0, im.size[0], min(im.size[1], im.size[0])))
                s = max(1, 32 // max(im.size))
                im = im.resize((min(40, im.size[0] * s), min(40, im.size[1] * s)), 0)
                sheet.paste(im, (x + 2 + j * 44, y + 2), im)
            d.text((x + 92, y + 2), k[1].split("/")[-1][:22], fill=(255, 255, 255))
            d.text((x + 92, y + 14), nk[1].split("/")[-1][:22], fill=(150, 220, 200))
            d.text((x + 92, y + 26), how, fill=(255, 200, 90) if how != "exact" else (120, 120, 120))
        sheet.save(a.sheet)
        print("review sheet:", a.sheet, "(%d pairs)" % len(items))


if __name__ == "__main__":
    main()
