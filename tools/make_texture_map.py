#!/usr/bin/env python3
"""Builds src/main/resources/assets/better112/modern_textures.map.

Usage: make_texture_map.py old.txt new.txt out.map
  old.txt / new.txt: texture paths (relative to assets/minecraft/textures) of the
  1.12.2 client jar and of a modern one, one per line.

Each output line is  <1.12.2 path>=<modern path>[|<alternative>...][;option...]
without the .png. Options:
  tint=RRGGBB  multiply the colour in (modern water is grey and tinted by the game)
  samesize     only use a source whose size matches the 1.12.2 texture, so a model
               that was re-laid-out in a later version is left alone
  chest        a single chest: re-laid-out if the jar is 1.15 or newer
  chestdouble  a double chest: rebuilt from the jar's _left and _right halves
Every 1.12.2 block and item texture is either mapped to a name that exists in new.txt
or reported as unmapped so it can be reviewed by hand.
"""
import re
import sys

old = [l.strip() for l in open(sys.argv[1]) if l.strip()]
new = set(l.strip() for l in open(sys.argv[2]) if l.strip())

COLORS = ["white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
          "silver", "cyan", "purple", "blue", "brown", "green", "red", "black"]
WOODS = {"oak": "oak", "spruce": "spruce", "birch": "birch", "jungle": "jungle",
         "acacia": "acacia", "big_oak": "dark_oak", "dark_oak": "dark_oak",
         "roofed_oak": "dark_oak", "wood": "oak"}


def col(c):
    return "light_gray" if c == "silver" else c


B = {}  # blocks
I = {}  # items

for c in COLORS:
    B["wool_colored_" + c] = col(c) + "_wool"
    B["glass_" + c] = col(c) + "_stained_glass"
    B["glass_pane_top_" + c] = col(c) + "_stained_glass_pane_top"
    B["hardened_clay_stained_" + c] = col(c) + "_terracotta"
    B["concrete_" + c] = col(c) + "_concrete"
    B["concrete_powder_" + c] = col(c) + "_concrete_powder"
    B["glazed_terracotta_" + c] = col(c) + "_glazed_terracotta"
    B["shulker_top_" + c] = col(c) + "_shulker_box"

for o, n in WOODS.items():
    if o in ("wood", "dark_oak", "roofed_oak"):
        continue
    B["log_" + o] = n + "_log"
    B["log_" + o + "_top"] = n + "_log_top"
    B["planks_" + o] = n + "_planks"
    B["leaves_" + o] = n + "_leaves"
B["sapling_roofed_oak"] = "dark_oak_sapling"
for w in ("oak", "spruce", "birch", "jungle", "acacia"):
    B["sapling_" + w] = w + "_sapling"
for o in ("wood", "spruce", "birch", "jungle", "acacia", "dark_oak", "iron"):
    n = "iron" if o == "iron" else WOODS[o]
    B["door_%s_lower" % o] = n + "_door_bottom"
    B["door_%s_upper" % o] = n + "_door_top"
    I["door_" + o] = n + "_door"

for crop, n in (("beetroots", 4), ("carrots", 4), ("potatoes", 4), ("wheat", 8),
                ("nether_wart", 3), ("cocoa", 3)):
    for i in range(n):
        B["%s_stage_%d" % (crop, i)] = "%s_stage%d" % (crop, i)

B.update({
    "anvil_base": "anvil",
    "anvil_top_damaged_0": "anvil_top",
    "anvil_top_damaged_1": "chipped_anvil_top",
    "anvil_top_damaged_2": "damaged_anvil_top",
    "brick": "bricks",
    "cobblestone_mossy": "mossy_cobblestone",
    "comparator_off": "comparator",
    "deadbush": "dead_bush",
    "dirt_podzol_side": "podzol_side",
    "dirt_podzol_top": "podzol_top",
    "dispenser_front_horizontal": "dispenser_front",
    "dropper_front_horizontal": "dropper_front",
    "double_plant_fern_bottom": "large_fern_bottom",
    "double_plant_fern_top": "large_fern_top",
    "double_plant_grass_bottom": "tall_grass_bottom",
    "double_plant_grass_top": "tall_grass_top",
    "double_plant_paeonia_bottom": "peony_bottom",
    "double_plant_paeonia_top": "peony_top",
    "double_plant_rose_bottom": "rose_bush_bottom",
    "double_plant_rose_top": "rose_bush_top",
    "double_plant_sunflower_back": "sunflower_back",
    "double_plant_sunflower_bottom": "sunflower_bottom",
    "double_plant_sunflower_front": "sunflower_front",
    "double_plant_sunflower_top": "sunflower_top",
    "double_plant_syringa_bottom": "lilac_bottom",
    "double_plant_syringa_top": "lilac_top",
    "end_bricks": "end_stone_bricks",
    "endframe_eye": "end_portal_frame_eye",
    "endframe_side": "end_portal_frame_side",
    "endframe_top": "end_portal_frame_top",
    "farmland_dry": "farmland",
    "farmland_wet": "farmland_moist",
    "fire_layer_0": "fire_0",
    "fire_layer_1": "fire_1",
    "flower_allium": "allium",
    "flower_blue_orchid": "blue_orchid",
    "flower_dandelion": "dandelion",
    "flower_houstonia": "azure_bluet",
    "flower_oxeye_daisy": "oxeye_daisy",
    "flower_rose": "poppy",
    "flower_tulip_orange": "orange_tulip",
    "flower_tulip_pink": "pink_tulip",
    "flower_tulip_red": "red_tulip",
    "flower_tulip_white": "white_tulip",
    "furnace_front_off": "furnace_front",
    "grass_path_side": "dirt_path_side|grass_path_side",
    "grass_path_top": "dirt_path_top|grass_path_top",
    "grass_side": "grass_block_side",
    "grass_side_overlay": "grass_block_side_overlay",
    "grass_side_snowed": "grass_block_snow",
    "grass_top": "grass_block_top",
    "hardened_clay": "terracotta",
    "ice_packed": "packed_ice",
    "itemframe_background": "item_frame",
    "log_big_oak": "dark_oak_log",
    "log_big_oak_top": "dark_oak_log_top",
    "planks_big_oak": "dark_oak_planks",
    "leaves_big_oak": "dark_oak_leaves",
    "magma": "magma",
    "melon_stem_connected": "attached_melon_stem",
    "melon_stem_disconnected": "melon_stem",
    "pumpkin_stem_connected": "attached_pumpkin_stem",
    "pumpkin_stem_disconnected": "pumpkin_stem",
    "mob_spawner": "spawner",
    "mushroom_block_skin_brown": "brown_mushroom_block",
    "mushroom_block_skin_red": "red_mushroom_block",
    "mushroom_block_skin_stem": "mushroom_stem",
    "mushroom_brown": "brown_mushroom",
    "mushroom_red": "red_mushroom",
    "nether_brick": "nether_bricks",
    "noteblock": "note_block",
    "piston_top_normal": "piston_top",
    "portal": "nether_portal",
    "prismarine_dark": "dark_prismarine",
    "prismarine_rough": "prismarine",
    "pumpkin_face_off": "carved_pumpkin",
    "pumpkin_face_on": "jack_o_lantern",
    "quartz_block_chiseled": "chiseled_quartz_block",
    "quartz_block_chiseled_top": "chiseled_quartz_block_top",
    "quartz_block_lines": "quartz_pillar",
    "quartz_block_lines_top": "quartz_pillar_top",
    "quartz_ore": "nether_quartz_ore",
    "rail_activator": "activator_rail",
    "rail_activator_powered": "activator_rail_on",
    "rail_detector": "detector_rail",
    "rail_detector_powered": "detector_rail_on",
    "rail_golden": "powered_rail",
    "rail_golden_powered": "powered_rail_on",
    "rail_normal": "rail",
    "rail_normal_turned": "rail_corner",
    "red_nether_brick": "red_nether_bricks",
    "red_sandstone_carved": "chiseled_red_sandstone",
    "red_sandstone_normal": "red_sandstone",
    "red_sandstone_smooth": "cut_red_sandstone",
    "redstone_lamp_off": "redstone_lamp",
    "redstone_torch_on": "redstone_torch",
    "reeds": "sugar_cane",
    "repeater_off": "repeater",
    "sandstone_carved": "chiseled_sandstone",
    "sandstone_normal": "sandstone",
    "sandstone_smooth": "cut_sandstone",
    "slime": "slime_block",
    "sponge_wet": "wet_sponge",
    "stone_andesite": "andesite",
    "stone_andesite_smooth": "polished_andesite",
    "stone_diorite": "diorite",
    "stone_diorite_smooth": "polished_diorite",
    "stone_granite": "granite",
    "stone_granite_smooth": "polished_granite",
    "stone_slab_side": "smooth_stone_slab_side",
    "stone_slab_top": "smooth_stone",
    "stonebrick": "stone_bricks",
    "stonebrick_carved": "chiseled_stone_bricks",
    "stonebrick_cracked": "cracked_stone_bricks",
    "stonebrick_mossy": "mossy_stone_bricks",
    "tallgrass": "short_grass|grass",
    "torch_on": "torch",
    "trapdoor": "oak_trapdoor",
    "trip_wire": "tripwire",
    "trip_wire_source": "tripwire_hook",
    "waterlily": "lily_pad",
    "web": "cobweb",
    "observer_back_lit": "observer_back_on",
})

for c in COLORS:
    I["dye_powder_" + c] = col(c) + "_dye"
I.update({
    "dye_powder_black": "ink_sac",
    "dye_powder_blue": "lapis_lazuli",
    "dye_powder_brown": "cocoa_beans",
    "dye_powder_white": "bone_meal",
    "apple_golden": "golden_apple",
    "beef_cooked": "cooked_beef",
    "beef_raw": "beef",
    "book_enchanted": "enchanted_book",
    "book_normal": "book",
    "book_writable": "writable_book",
    "book_written": "written_book",
    "bow_standby": "bow",
    "bucket_empty": "bucket",
    "bucket_lava": "lava_bucket",
    "bucket_milk": "milk_bucket",
    "bucket_water": "water_bucket",
    "carrot_golden": "golden_carrot",
    "chicken_cooked": "cooked_chicken",
    "chicken_raw": "chicken",
    "chorus_fruit_popped": "popped_chorus_fruit",
    "fireball": "fire_charge",
    "fireworks": "firework_rocket",
    "fireworks_charge": "firework_star",
    "fireworks_charge_overlay": "firework_star_overlay",
    "fish_clownfish_raw": "tropical_fish",
    "fish_cod_cooked": "cooked_cod",
    "fish_cod_raw": "cod",
    "fish_pufferfish_raw": "pufferfish",
    "fish_salmon_cooked": "cooked_salmon",
    "fish_salmon_raw": "salmon",
    "fishing_rod_uncast": "fishing_rod",
    "map_empty": "map",
    "map_filled": "filled_map",
    "map_filled_markings": "filled_map_markings",
    "melon": "melon_slice",
    "melon_speckled": "glistering_melon_slice",
    "minecart_chest": "chest_minecart",
    "minecart_command_block": "command_block_minecart",
    "minecart_furnace": "furnace_minecart",
    "minecart_hopper": "hopper_minecart",
    "minecart_normal": "minecart",
    "minecart_tnt": "tnt_minecart",
    "mutton_cooked": "cooked_mutton",
    "mutton_raw": "mutton",
    "netherbrick": "nether_brick",
    "porkchop_cooked": "cooked_porkchop",
    "porkchop_raw": "porkchop",
    "potato_baked": "baked_potato",
    "potato_poisonous": "poisonous_potato",
    "potion_bottle_drinkable": "potion",
    "potion_bottle_empty": "glass_bottle",
    "potion_bottle_lingering": "lingering_potion",
    "potion_bottle_splash": "splash_potion",
    "rabbit_cooked": "cooked_rabbit",
    "rabbit_raw": "rabbit",
    "record_11": "music_disc_11",
    "record_13": "music_disc_13",
    "record_blocks": "music_disc_blocks",
    "record_cat": "music_disc_cat",
    "record_chirp": "music_disc_chirp",
    "record_far": "music_disc_far",
    "record_mall": "music_disc_mall",
    "record_mellohi": "music_disc_mellohi",
    "record_stal": "music_disc_stal",
    "record_strad": "music_disc_strad",
    "record_wait": "music_disc_wait",
    "record_ward": "music_disc_ward",
    "redstone_dust": "redstone",
    "reeds": "sugar_cane",
    "seeds_melon": "melon_seeds",
    "seeds_pumpkin": "pumpkin_seeds",
    "seeds_wheat": "wheat_seeds",
    "sign": "oak_sign",
    "slimeball": "slime_ball",
    "spider_eye_fermented": "fermented_spider_eye",
    "totem": "totem_of_undying",
    "wooden_armorstand": "armor_stand",
    "empty_armor_slot_shield": "empty_armor_slot_shield",
})
for t in ("axe", "hoe", "pickaxe", "shovel", "sword"):
    I["wood_" + t] = "wooden_" + t
    I["gold_" + t] = "golden_" + t
for t in ("boots", "chestplate", "helmet", "leggings", "horse_armor"):
    I["gold_" + t] = "golden_" + t

OPTIONS = {
    "blocks/water_still": "tint=3F76E4",
    "blocks/water_flow": "tint=3F76E4",
    "blocks/water_overlay": "tint=3F76E4",
}

# Entity and armour textures. Anything whose layout changed is caught by "samesize";
# chests kept their size but were rearranged in 1.15, so they get their own converter.
E = {}
SAME = """armorstand/wood banner_base bat beacon_beam bear/polarbear blaze cat/black cat/ocelot cat/red
cat/siamese chicken cow/cow creeper/creeper creeper/creeper_armor elytra enchanting_table_book
enderdragon/dragon enderdragon/dragon_exploding enderdragon/dragon_eyes enderdragon/dragon_fireball
enderman/enderman enderman/enderman_eyes endermite experience_orb ghast/ghast ghast/ghast_shooting
guardian guardian_beam guardian_elder illager/evoker illager/vex illager/vex_charging illager/vindicator
lead_knot llama/spit minecart parrot/parrot_blue parrot/parrot_green parrot/parrot_grey
parrot/parrot_red_blue parrot/parrot_yellow_blue pig/pig pig/pig_saddle projectiles/arrow
projectiles/spectral_arrow projectiles/tipped_arrow rabbit/black rabbit/brown rabbit/caerbannog
rabbit/gold rabbit/salt rabbit/toast rabbit/white rabbit/white_splotched sheep/sheep sheep/sheep_fur
shield_base shield_base_nopattern shulker/spark silverfish skeleton/skeleton skeleton/stray
skeleton/stray_overlay skeleton/wither_skeleton slime/magmacube slime/slime spider/cave_spider
spider/spider spider_eyes witch wither/wither wither/wither_armor wither/wither_invulnerable
wolf/wolf wolf/wolf_angry wolf/wolf_collar wolf/wolf_tame zombie/husk zombie/zombie
horse/donkey horse/mule horse/horse_black horse/horse_brown horse/horse_chestnut horse/horse_creamy
horse/horse_darkbrown horse/horse_gray horse/horse_white horse/horse_skeleton horse/horse_zombie
zombie_pigman""".split()
for name in SAME:
    E["entity/" + name] = "entity/" + name
for c in COLORS:
    E["entity/bed/" + c] = "entity/bed/" + col(c)
    E["entity/shulker/shulker_" + c] = "entity/shulker/shulker_" + col(c)
    E["entity/llama/decor/decor_" + c] = "entity/llama/decor/" + col(c)
for old_name, new_name in (("acacia", "acacia"), ("birch", "birch"), ("darkoak", "dark_oak"),
                           ("jungle", "jungle"), ("oak", "oak"), ("spruce", "spruce")):
    E["entity/boat/boat_" + old_name] = "entity/boat/" + new_name
for name in ("brown", "creamy", "gray", "white"):
    E["entity/llama/llama_" + name] = "entity/llama/" + name
E.update({
    "entity/llama/llama": "entity/llama/creamy",
    "entity/alex": "entity/player/slim/alex|entity/alex",
    "entity/steve": "entity/player/wide/steve|entity/steve",
    "entity/arrow": "entity/projectiles/arrow",
    "entity/cow/mooshroom": "entity/cow/red_mooshroom",
    "entity/endercrystal/endercrystal": "entity/end_crystal/end_crystal",
    "entity/endercrystal/endercrystal_beam": "entity/end_crystal/end_crystal_beam",
    "entity/illager/fangs": "entity/illager/evoker_fangs",
    "entity/illager/illusionist": "entity/illager/illusioner",
    "entity/iron_golem": "entity/iron_golem/iron_golem|entity/iron_golem",
    "entity/sign": "entity/signs/oak",
    "entity/snowman": "entity/snow_golem",
    "entity/squid": "entity/squid/squid|entity/squid",
})
for pattern in [l.split("/")[-1][:-4] for l in old if l.startswith("entity/banner/")]:
    E["entity/banner/" + pattern] = "entity/banner/" + pattern
    E["entity/shield/" + pattern] = "entity/shield/" + pattern
for name in E:
    OPTIONS[name] = "samesize"
for name in ("normal", "trapped", "ender", "christmas"):
    E["entity/chest/" + name] = "entity/chest/" + name
    OPTIONS["entity/chest/" + name] = "chest"
    if name != "ender":
        E["entity/chest/" + name + "_double"] = "entity/chest/" + name
        OPTIONS["entity/chest/" + name + "_double"] = "chestdouble"
for material in ("chainmail", "diamond", "gold", "iron", "leather"):
    modern = material
    E["models/armor/%s_layer_1" % material] = "models/armor/%s_layer_1|entity/equipment/humanoid/%s" % (material, modern)
    E["models/armor/%s_layer_2" % material] = "models/armor/%s_layer_2|entity/equipment/humanoid_leggings/%s" % (material, modern)
    OPTIONS["models/armor/%s_layer_1" % material] = "samesize"
    OPTIONS["models/armor/%s_layer_2" % material] = "samesize"
E["models/armor/leather_layer_1_overlay"] = "models/armor/leather_layer_1_overlay|entity/equipment/humanoid/leather_overlay"
E["models/armor/leather_layer_2_overlay"] = "models/armor/leather_layer_2_overlay|entity/equipment/humanoid_leggings/leather_overlay"
OPTIONS["models/armor/leather_layer_1_overlay"] = "samesize"
OPTIONS["models/armor/leather_layer_2_overlay"] = "samesize"

lines = []
unmapped = []
for path in old:
    m = re.match(r"^(blocks|items)/(.+?)\.png(\.mcmeta)?$", path)
    if not m:
        continue
    kind, name, meta = m.group(1), m.group(2), m.group(3) or ""
    table, newdir = (B, "block") if kind == "blocks" else (I, "item")
    cands = table.get(name, name).split("|")
    found = [c for c in cands if "%s/%s.png%s" % (newdir, c, meta) in new]
    if found:
        if not meta:
            key = "%s/%s" % (kind, name)
            line = "%s=%s" % (key, "|".join("%s/%s" % (newdir, c) for c in cands))
            if key in OPTIONS:
                line += ";" + OPTIONS[key]
            lines.append(line)
    else:
        unmapped.append(path)

oldset = set(old)
skipped = []
for key in sorted(E):
    if key + ".png" not in oldset:
        skipped.append(key)
        continue
    lines.append("%s=%s;%s" % (key, E[key], OPTIONS[key]))
header = ["# 1.12.2 texture = modern texture(s). Generated by tools/make_texture_map.py; edit that, not this."]
open(sys.argv[3], "w").write("\n".join(header + lines) + "\n")
if skipped:
    print("not in 1.12.2 (dropped):", " ".join(skipped))
print("mapped", len(lines))
print("unmapped", len(unmapped))
for u in unmapped:
    print("  ", u)
