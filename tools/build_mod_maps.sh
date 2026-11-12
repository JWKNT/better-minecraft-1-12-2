#!/bin/sh
# Regenerates src/main/resources/assets/better112/mods/*.map.
#   OLD: a folder holding the 1.12.2 jars (a pack's mods folder)
#   NEW: a folder holding modern releases of the same mods
# usage: tools/build_mod_maps.sh OLD NEW [REVIEW_DIR]
#
# Pairs whose modern texture is pixel-identical to the old one are dropped by the tool, so
# a mod that kept its art (Blood Magic, Draconic Evolution) ends up with a nearly empty table.
set -e
OLD="$1"; NEW="$2"; REVIEW="${3:-}"
OUT="$(dirname "$0")/../src/main/resources/assets/better112/mods"
TOOL="$(dirname "$0")/make_mod_texture_map.py"
sheet() { [ -n "$REVIEW" ] && echo "--sheet $REVIEW/$1.png --sheet-all"; }
MODERN='1\.(1[6-9]|2[0-9])'
# Never taken from any mod: screens, 3D model sheets, mobs and worn armor change layout between versions.
COMMON=':gui/|:models?/|:entity/|:entities/|armor|:logo'
TEXMAPS="$(dirname "$0")/texmaps"
# ONLY="aether quark" limits a run to those tables.
want() { [ -z "$ONLY" ] || case " $ONLY " in *" $1 "*) true ;; *) false ;; esac; }

# Thermal series. Machines kept their faces; coins and plates became stackable variants.
# Dynamos and mobs changed model, so they are left alone.
want thermal && python3 "$TOOL" thermal \
  --old "$OLD"/ThermalFoundation-1.12.2-*.jar "$OLD"/ThermalExpansion-1.12.2-*.jar \
  --new "$NEW"/thermal_foundation-*.jar "$NEW"/thermal_expansion-*.jar "$NEW"/thermal_innovation-*.jar "$NEW"/cofh_core-*.jar \
  --drop face \
  --alias "compactor=press,transposer=bottler,tapper=tree+extractor" \
  --rule 'thermalfoundation:items/material/(coin|plate)_(\w+)=>thermal:item/\2_\1_0' \
  --skip "$COMMON|/dynamo/|florb|dynamo_throttle" \
  --source 'thermal_foundation-.*\.jar' 'thermal_expansion-.*\.jar' 'thermal_innovation-.*\.jar' 'cofh_core-.*\.jar' \
  --out "$OUT/thermal.map" $(sheet thermal)

# Ender IO. Most art carried over unchanged; conduits changed model.
want enderio && python3 "$TOOL" enderio \
  --old "$OLD"/EnderIO-1.12.2-*.jar "$OLD"/EnderIO-endergy-1.12.2-*.jar \
  --new "$NEW"/EnderIO-1.2*.jar \
  --drop item,block,alloy \
  --alias "ball=grinding+ball,sagmill=sag+mill,gen=generator,on=active,off=" \
  --skip "$COMMON|conduit" \
  --source 'EnderIO-1\.(1[6-9]|2[0-9]).*\.jar' \
  --out "$OUT/enderio.map" $(sheet enderio)

# Mekanism wrote names without separators (advancedcontrolcircuit) and moved the kind of
# thing to the front (copperdust -> dust_copper). Factories are skipped: the old ones had a
# texture per tier, the new ones share one, so tiers would become indistinguishable.
want mekanism && python3 "$TOOL" mekanism \
  --old "$OLD"/Mekanism-1.12.2-*.jar \
  --new "$NEW"/Mekanism-1.2*.jar \
  --manual "$TEXMAPS/mekanism.txt" \
  --rule 'mekanism:items/dirty([a-z]+)dust=>mekanism:item/dirty_dust_\1' \
  --rule 'mekanism:items/([a-z]+)(clump|crystal|shard|dust|ingot|nugget)=>mekanism:item/\2_\1' \
  --rule 'mekanism:items/([a-z]+)upgrade=>mekanism:item/upgrade_\1' \
  --rule 'mekanism:blocks/bin/bin(basic|advanced|elite|ultimate|creative)([a-z]+?)on=>mekanism:block/bin/\1_\2_active' \
  --rule 'mekanism:blocks/bin/bin(basic|advanced|elite|ultimate|creative)([a-z]+)=>mekanism:block/bin/\1_\2' \
  --rule 'mekanism:blocks/([a-z_]+)/[a-z]+?(front|back|left|right|top|bottom|side)on=>mekanism:block/\1/\2_active' \
  --rule 'mekanism:blocks/([a-z_]+)/[a-z]+?(front|back|left|right|top|bottom|side)=>mekanism:block/\1/\2' \
  --rule 'mekanism:blocks/([a-z]+)block=>mekanism:block/block_\1' \
  --rule 'mekanism:blocks/([a-z]+)ore=>mekanism:block/\1_ore' \
  --skip "$COMMON|-ctm|/factory/|blocks/laser\$|blocks/teleporter\$" \
  --source "Mekanism-$MODERN.*\.jar" \
  --out "$OUT/mekanism.map" $(sheet mekanism)

# Tinkers' Construct. Tool and part textures are left alone: Tinkers 3 redrew every tool,
# and a tool is several layered textures that only line up as a complete set.
want tconstruct && python3 "$TOOL" tconstruct \
  --old "$OLD"/TConstruct-1.12.2-*.jar \
  --new "$NEW"/TConstruct-1.2*.jar \
  --skip "$COMMON|:items/(longbow|shortbow|scythe|battleaxe|hammer|lumberaxe|excavator|shovel|pickaxe|mattock|crossbow|cleaver|broadsword|shuriken|kama|hatchet|battlesign|rapier|longsword|frypan|arrow|cutlass|bolt|parts)/|connected|:colormap|:particle|reinforcement" \
  --source "TConstruct-$MODERN.*\.jar" \
  --out "$OUT/tconstruct.map" $(sheet tconstruct)

want botania && python3 "$TOOL" botania \
  --old "$OLD"/botania-r1.10-*.jar \
  --new "$NEW"/Botania-1.2*.jar \
  --skip "$COMMON|:model/" \
  --source "Botania-$MODERN.*\.jar" \
  --out "$OUT/botania.map" $(sheet botania)

# DivineRPG redrew nearly everything and kept the names.
want divinerpg && python3 "$TOOL" divinerpg \
  --old "$OLD"/DivineRPG-1.7*.jar \
  --new "$NEW"/DivineRPG-1.1*.jar \
  --skip "$COMMON|:shield/|:model/" \
  --source 'DivineRPG-1\.(1[0-9]|[2-9][0-9]).*\.jar' \
  --out "$OUT/divinerpg.map" $(sheet divinerpg)

# Immersive Engineering: mostly unchanged art. The drill, railgun and powerpack are model sheets.
want immersiveengineering && python3 "$TOOL" immersiveengineering \
  --old "$OLD"/ImmersiveEngineering-0.12-*.jar \
  --new "$NEW"/ImmersiveEngineering-1.2*.jar \
  --skip "$COMMON|railgun|drill|powerpack|wiring|toolupgrade|revolver" \
  --source "ImmersiveEngineering-$MODERN.*\.jar" \
  --out "$OUT/immersiveengineering.map" $(sheet immersiveengineering)

want industrialforegoing && python3 "$TOOL" industrialforegoing \
  --old "$OLD"/industrialforegoing-1.12.2-*.jar \
  --new "$NEW"/industrial-foregoing-1.2*.jar \
  --skip "$COMMON" \
  --source "industrial-foregoing-$MODERN.*\.jar" \
  --out "$OUT/industrialforegoing.map" $(sheet industrialforegoing)

want bloodmagic && python3 "$TOOL" bloodmagic \
  --old "$OLD"/BloodMagic-1.12.2-*.jar \
  --new "$NEW"/BloodMagic-1.1*.jar \
  --skip "$COMMON" \
  --source 'BloodMagic-1\.1[6-9].*\.jar' \
  --out "$OUT/bloodmagic.map" $(sheet bloodmagic)

want draconicevolution && python3 "$TOOL" draconicevolution \
  --old "$OLD"/Draconic-Evolution-1.12.2-*.jar \
  --new "$NEW"/Draconic-Evolution-1.2*.jar \
  --skip "$COMMON" \
  --source "Draconic-Evolution-$MODERN.*\.jar" \
  --out "$OUT/draconicevolution.map" $(sheet draconicevolution)

# Applied Energistics 2 (the 1.12.2 jar here is the Extended Life fork). Cells, components and
# a few tools were renamed outright; planes and the drive's cell lights changed layout.
want ae2 && python3 "$TOOL" ae2 \
  --old "$OLD"/ae2-uel-*.jar \
  --new "$NEW"/appliedenergistics2-*.jar \
  --alias "print=printed" \
  --manual "$TEXMAPS/ae2.txt" \
  --skip "$COMMON|:guis/|/debug|:orientation|plane|drive_cell" \
  --source 'appliedenergistics2-(forge-|neoforge-)?1[2-9]\..*\.jar' \
  --out "$OUT/ae2.map" $(sheet ae2)

# Divine Journey 2's other big mods (added 2026-11-12). [-_]ctm sheets are only ever replaced together
# with the texture that names them, never on their own.
CTM='[-_]ctm'

# Twilight Forest kept most of its art; renamed blocks and armor come from the table. Pedestals became
# overlays, and the particles and dark leaves are tinted in code now, so those stay. Its 32-pixel item
# art is a 16-pixel picture padded for an oversized icon (the overlap_gui model), so sizes must match.
want twilightforest && python3 "$TOOL" twilightforest \
  --old "$OLD"/twilightforest-1.12.2-*.jar \
  --new "$NEW"/twilightforest-1.2*.jar \
  --manual "$TEXMAPS/twilightforest.txt" \
  --same-size \
  --skip "$COMMON|$CTM|tconstruct|immersiveengineering|pedestal|:particles/|darkwood_leaves" \
  --source "twilightforest-$MODERN.*\.jar" \
  --out "$OUT/twilightforest.map" $(sheet twilightforest)

# Astral Sorcery 1.16.5 is its last release; little was redrawn. Crystal models changed layout.
want astralsorcery && python3 "$TOOL" astralsorcery \
  --old "$OLD"/astralsorcery-1.12.2-*.jar \
  --new "$NEW"/astralsorcery-1.1[6-9]*.jar \
  --manual "$TEXMAPS/astralsorcery.txt" \
  --skip "$COMMON|$CTM|:effect/|:environment/|/crystal/|:misc/" \
  --source "astralsorcery-$MODERN.*\.jar" \
  --out "$OUT/astralsorcery.map" $(sheet astralsorcery)

# The Aether: the 1.12.2 port (aether_legacy) against the Aether team's modern release, which sorts
# its textures into folders. Accessory slots, bosses and holiday art are left alone.
want aether && python3 "$TOOL" aether \
  --old "$OLD"/aether-1.12.2-*.jar \
  --new "$NEW"/aether-1.2*.jar \
  --manual "$TEXMAPS/aether.txt" \
  --drop natural,construction,dungeon,utility,miscellaneous,weapon,tool,food,projectile \
  --alias "plank=planks,base=top" \
  --skip "$COMMON|$CTM|:blur/|:bosses/|:tile_entities/|:other/|emissive|holiday|present|slots" \
  --source "aether-1\.(19|2[0-9]).*\.jar" \
  --out "$OUT/aether.map" $(sheet aether)

# Mystical Agriculture and Agradditions: every seed and essence was redrawn and moved into a folder.
want mysticalagriculture && python3 "$TOOL" mysticalagriculture \
  --old "$OLD"/MysticalAgriculture-1.12.2-*.jar "$OLD"/MysticalAgradditions-1.12.2-*.jar \
  --new "$NEW"/MysticalAgriculture-1.2*.jar "$NEW"/MysticalAgradditions-1.2*.jar \
  --skip "$COMMON|$CTM" \
  --source "MysticalAgriculture-$MODERN.*\.jar" "MysticalAgradditions-$MODERN.*\.jar" \
  --out "$OUT/mysticalagriculture.map" $(sheet mysticalagriculture)

# Atum 2 (1.16.5): Atum the god became Atem, broaches became brooches, stained glass took vanilla names.
want atum && python3 "$TOOL" atum \
  --old "$OLD"/Atum-1.12.2-*.jar \
  --new "$NEW"/Atum-1.1[6-9]*.jar \
  --manual "$TEXMAPS/atum.txt" \
  --skip "$COMMON|$CTM|:entity_icon/|:hud/|:particle/|:shield/|/chest/" \
  --source "Atum-$MODERN.*\.jar" \
  --out "$OUT/atum.map" $(sheet atum)

# Quark (the 1.12.2 Rotten Nostalgia fork) against Quark 4: slate became shale, carved stone chiseled
# bricks. Pipes changed model.
want quark && python3 "$TOOL" quark \
  --old "$OLD"/QuarkRotN-*.jar \
  --new "$NEW"/Quark-[3-9]*.jar \
  --manual "$TEXMAPS/quark.txt" \
  --skip "$COMMON|$CTM|:emotes/|:cursors/|:misc/|minecraft:|pipe_" \
  --source 'Quark-.*\.jar' \
  --out "$OUT/quark.map" $(sheet quark)

# Actually Additions dropped the block_/item_ prefixes and named its crystals. Drill and laser upgrades
# and lenses all became one grey chip each in the modern art, so they keep their 1.12.2 look.
want actuallyadditions && python3 "$TOOL" actuallyadditions \
  --old "$OLD"/ActuallyAdditions-1.12.2-*.jar \
  --new "$NEW"/actuallyadditions-*.jar \
  --manual "$TEXMAPS/actuallyadditions.txt" \
  --drop block,item,misc,colored,fluid \
  --alias "bio=bio+reactor,reactor=,grinder=crusher" \
  --rule 'actuallyadditions:blocks/block_([a-z_]+?)=>actuallyadditions:block/\1_side' \
  --skip "$COMMON|$CTM|/models/|drill_upgrade|laser_upgrade|lens|display_stand" \
  --source '(?i)actuallyadditions-.*(1\.(1[6-9]|2[0-9])).*\.jar' \
  --out "$OUT/actuallyadditions.map" $(sheet actuallyadditions)

# Extreme Reactors: materials only. Reactor and turbine casings join up differently in the modern mod,
# and a half-converted reactor would look patched.
want bigreactors && python3 "$TOOL" bigreactors \
  --old "$OLD"/ExtremeReactors-1.12.2-*.jar \
  --new "$NEW"/ExtremeReactors2-*.jar \
  --rule 'bigreactors:items/(dust|ingot)([a-z]+)=>bigreactors:item/\2_\1' \
  --rule 'bigreactors:items/mineral([a-z]+)=>bigreactors:item/\1_crystal' \
  --rule 'bigreactors:blocks/block([a-z]+)=>bigreactors:block/\1_block' \
  --rule 'bigreactors:blocks/ore([a-z]+)(0?)=>bigreactors:block/\1_ore\2' \
  --skip "$COMMON|$CTM|glass|:guiicons/|:book/" \
  --source "ExtremeReactors2?-$MODERN.*\.jar" \
  --out "$OUT/bigreactors.map" $(sheet bigreactors)

want extendedcrafting && python3 "$TOOL" extendedcrafting \
  --old "$OLD"/extendedcrafting-*.jar \
  --new "$NEW"/ExtendedCrafting-1.2*.jar \
  --rule 'extendedcrafting:blocks/storage_([a-z_]+)=>extendedcrafting:block/\1_block' \
  --rule 'extendedcrafting:blocks/storage_([a-z_]+)=>extendedcrafting:block/\1_ingot_block' \
  --rule 'extendedcrafting:items/material_black_iron=>extendedcrafting:item/black_iron_ingot' \
  --skip "$COMMON|$CTM|:jei/|:icon" \
  --source "ExtendedCrafting-$MODERN.*\.jar" \
  --out "$OUT/extendedcrafting.map" $(sheet extendedcrafting)

# Storage Drawers called dark oak "big oak".
want storagedrawers && python3 "$TOOL" storagedrawers \
  --old "$OLD"/StorageDrawers-1.12.2-*.jar \
  --new "$NEW"/StorageDrawers-*1.2*.jar \
  --alias big=dark \
  --skip "$COMMON|$CTM" \
  --source "StorageDrawers-.*$MODERN.*\.jar" \
  --out "$OUT/storagedrawers.map" $(sheet storagedrawers)

want bewitchment && python3 "$TOOL" bewitchment \
  --old "$OLD"/bewitchment-1.12.2-*.jar \
  --new "$NEW"/bewitchment-1.2*.jar \
  --skip "$COMMON|$CTM|brazier" \
  --source "bewitchment-$MODERN.*\.jar" \
  --out "$OUT/bewitchment.map" $(sheet bewitchment)

want betternether && python3 "$TOOL" betternether \
  --old "$OLD"/betternether-0.*.jar \
  --new "$NEW"/better-nether-*.jar \
  --skip "$COMMON|$CTM|lantern" \
  --source 'better-?nether-.*\.jar' \
  --out "$OUT/betternether.map" $(sheet betternether)

# Avaritia has no official modern release; Re:Avaritia continues it with the same names.
want avaritia && python3 "$TOOL" avaritia \
  --old "$OLD"/Avaritia-1.12.2-*.jar \
  --new "$NEW"/Re-Avaritia-*.jar \
  --skip "$COMMON|$CTM" \
  --source 'Re-Avaritia-.*\.jar' \
  --out "$OUT/avaritia.map" $(sheet avaritia)

want chiselsandbits && python3 "$TOOL" chiselsandbits \
  --old "$OLD"/chiselsandbits-14.*.jar \
  --new "$NEW"/chisels-and-bits-*.jar \
  --skip "$COMMON|$CTM" \
  --source 'chisels-and-bits-.*\.jar' \
  --out "$OUT/chiselsandbits.map" $(sheet chiselsandbits)

want traverse && python3 "$TOOL" traverse \
  --old "$OLD"/Traverse-1.12.2-*.jar \
  --new "$NEW"/traverse-[0-9]*.jar \
  --skip "$COMMON|$CTM" \
  --source 'traverse-[0-9].*\.jar' \
  --out "$OUT/traverse.map" $(sheet traverse)

want projectred && python3 "$TOOL" projectred \
  --old "$OLD"/ProjectRed-1.12.2-*.jar \
  --new "$NEW"/ProjectRed-1.2*-core.jar "$NEW"/ProjectRed-1.2*-integration.jar "$NEW"/ProjectRed-1.2*-transmission.jar \
  --skip "$COMMON|$CTM" \
  --source "ProjectRed-$MODERN.*-core\.jar" \
  --out "$OUT/projectred.map" $(sheet projectred)

want simplyjetpacks && python3 "$TOOL" simplyjetpacks \
  --old "$OLD"/SimplyJetpacks2-1.12.2-*.jar \
  --new "$NEW"/SimplyJetpacks2-1.2*.jar \
  --skip "$COMMON|$CTM" \
  --source "SimplyJetpacks2-$MODERN.*\.jar" \
  --out "$OUT/simplyjetpacks.map" $(sheet simplyjetpacks)

# EvilCraft and ProjectE kept nearly all their art. The dark ore became a full texture instead of an
# overlay, and the condenser is a model sheet.
want evilcraft && python3 "$TOOL" evilcraft \
  --old "$OLD"/EvilCraft-1.12.2-*.jar \
  --new "$NEW"/EvilCraft-1.2*.jar \
  --skip "$COMMON|$CTM|dark_ore" \
  --source "EvilCraft-$MODERN.*\.jar" \
  --out "$OUT/evilcraft.map" $(sheet evilcraft)

want projecte && python3 "$TOOL" projecte \
  --old "$OLD"/ProjectE-1.12.2-*.jar \
  --new "$NEW"/ProjectE-1.2*.jar \
  --skip "$COMMON|$CTM|condenser" \
  --source "ProjectE-$MODERN.*\.jar" \
  --out "$OUT/projecte.map" $(sheet projecte)
