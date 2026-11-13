# Better 1.12.2

One Forge mod that brings modern Minecraft's quality of life, looks and frame rate back to
1.12.2, the version the big expert packs still live on. Built around Divine Journey 2, but it
depends on no other mod: every integration switches itself off when its partner is missing.

Download `better112-0.1.4.jar` from the
[releases](https://github.com/JWKNT/better-minecraft-1-12-2/releases) and put it in the game's
`mods` folder.

**Multiplayer.** A player with the mod can join any server. On a server without the mod, the
imported blocks and biomes are left out of the game while you play there. A server with the mod
has everything a single-player world has: the imported blocks and biomes, Modern Terrain and
`/locatebiome`. Every player who joins it needs the same version of the mod, and the server
turns away anyone without it. Tested with Divine Journey 2 2.23.4, on a server on the same
computer and on a hosted one.

**Setting up a server.** Put the jar in the server's `mods` folder. Make a `better112/modern_mods`
folder next to `mods` and put in it the two modern jars the players' games read:

- the Minecraft client jar of the version the games use (1.21.10 here: Mojang's `client.jar`
  for that version, under any name that ends in `.jar`), and
- `BiomesOPlenty-forge-1.20.1-19.0.0.96.jar` (Biomes O' Plenty for 1.20.1).

Set `level-type=better112modern` in `server.properties` before the world is first made.

**Joining a server.** Each player needs the mod and the same two jars as the server, in a
`better112/modern_mods` folder in the game folder, next to `mods`:

1. Put the same Minecraft client jar as the server in `better112/modern_mods` (tested:
   [Minecraft 1.21.10 client jar](https://piston-data.mojang.com/v1/objects/d3bdf582a7fa723ce199f3665588dcfe6bf9aca8/client.jar),
   any file name that ends in `.jar`).
2. Put the same Biomes O' Plenty jar there (tested:
   [BiomesOPlenty-forge-1.20.1-19.0.0.96.jar](https://cdn.modrinth.com/data/HXF82T3G/versions/jxUqRzSD/BiomesOPlenty-forge-1.20.1-19.0.0.96.jar)).
3. If the game used the mod with a different Minecraft version before, delete
   `better112/modern_worldgen.zip`. The game makes it again from the new jar.
4. Start the game and join from Multiplayer on the title screen. In Divine Journey 2, a
   launcher that joins the server as the game starts leaves "Logging in..." on the screen,
   because Universal Tweaks fails until the title screen has opened once.

Without its own Minecraft jar, a game reads the newest modern Minecraft in the launchers on
the computer, which may not be the server's version. Copying another player's `better112`
folder is not enough when that player's game reads Minecraft from a launcher. To check, compare
the line "Modern content: ..., signature ..." in the game's `logs/latest.log` with the same
line in the server's log. The signatures must be the same, or Forge refuses the player because
blocks are missing on one side.

## What it does

**Video settings, Sodium style.** The vanilla Video Settings screen is replaced by one laid
out like Sodium's: page tabs, flat option rows, a description and performance impact for the
hovered option, and Apply / Undo so nothing changes until you say so. Pages: General,
Quality, Performance, Animations, Particles, Details, Render, Accessibility, Colors, Extras. Shift+P opens the
vanilla screen; the replacement can be turned off under Extras. GUI Scale goes in half
steps (1.5x, 2.5x, ...) as far as the window allows, not just the four vanilla sizes.

The game itself only knows whole scales, where every pixel of the interface is 2, 3 or 4
screen pixels wide; at 2.5 a pixel would be 2 wide in one place and 3 in the next, which
makes text uneven. So a half size is drawn the way a screen with fractional scaling draws
("Even Half Sizes", on by default): the interface is drawn at twice the size, at what is
then a whole scale again (2.5 becomes a true 5), on top of an enlarged copy of the world,
and the picture is reduced 2:1 onto the window. Every interface pixel then covers exactly
2.5 screen pixels, and while the interface is drawn the game is told a window size, mouse
position and scale factor that agree, so clipping boxes and chat links land where they
should. The cost is two full-window copies a frame, measured at about 1.8 ms in a
1728x1000 window. One side effect: anything a mod sizes in raw screen pixels, such as
JourneyMap's minimap labels, comes out half as large (its own font setting makes up for
it).

**Frame rate.**

- Entity culling: mobs, items and block entities (chests, drawers, machines) that are fully
  hidden behind solid blocks are not drawn. Vanilla only culls by 16-block chunk section.
- Animate only visible textures, particle culling outside the view, a block entity distance
  slider, a frame cap while the window is in the background.
- Chunk builder thread count, always-deferred chunk updates, biome blend from off to 15x15,
  separate leaves and weather quality.
- Toggles for every kind of animation, particle group, sky element, fog, and for item
  frames, armor stands, paintings, piston animation, beacon beams and name tags.
- Smart leaves: the outer layers of a canopy are drawn see-through, one solid layer behind
  them, and nothing deeper. Looks like fancy leaves, costs much less in a dense forest.
- Better Grass, as in OptiFine (Quality page): grass, mycelium and podzol are drawn green
  down their sides where the slope carries on downward, so hillsides read as one surface;
  Fast does it on every side. On (Fancy) by default.
- Falling leaves (Quality page; Off, A Few, Many): scraps of each tree's own leaves, in its
  own colour, drift down and settle for a moment, as the Falling Leaves mod and 1.20's cherry
  trees show them. None when particles are set to minimal.
- Fireflies (Quality page; Off, A Few, Many): small glowing lights drift over grass and
  plants on warm, dry nights in forests, swamps, plains and jungles, as with 1.21.5's
  firefly bushes.
- Rain ripples (Quality page): rain makes small rings spread on open water, as the Effective
  and Particle Rain mods show it. The ring is the mod's own picture.
- An idle frame cap, as Dynamic FPS has it: once nothing has been pressed or moved for five
  minutes the game draws at most 10 frames a second (changeable, or off), while the world and
  its machines keep running at full speed. Any key or mouse movement ends it.
- Hold C to zoom; while zooming, the mouse wheel zooms further in or out (1.5 to 50 times)
  and the zoom eases in and out, as Ok Zoomer and Zoomify do. Optional FPS (with average and
  1% low) and coordinates in a corner.

**From modern Minecraft and modern packs.**

- Dynamic lights: a held torch or other glowing item lights the world around you for
  about eight blocks, as do glowing items on the ground and anything on fire (Quality page;
  Off, Fast, Fancy). Only the chunk sections the light reaches are redrawn, a couple of
  times a second on Fast.
- An Accessibility page with the options vanilla added later: FOV Effects, Damage Tilt,
  Distortion Effects (the wobble of nausea and Nether portals), Menu Background Blur (the
  world behind a menu or inventory is blurred, as in 1.21; it gives way to any screen
  effect another mod or the spectator view has put up), Glint Speed and Glint Strength (the
  shimmer on enchanted items and worn armor, as in 1.19), and hold or toggle for sprint and
  sneak.
- Heads-up helpers, each with a switch under Extras: a list of what you just picked up, the
  time left on every status effect icon, the durability of worn armor and held tools, and
  durability numbers in tooltips.
- Applied Energistics 2 storage cells list what they hold in their tooltip, as modern AE2
  does. The Extended Life fork already does this, so there the mod stays quiet.
- Command suggestions, as since 1.13: while a command is typed, the commands or arguments
  that fit are listed above the chat line, with the rest of the picked one greyed in after
  the cursor. Up and Down pick, Tab takes it, Escape puts the list away. On a server, some
  mods' commands answer every such question with a chat line (Tool Progression's `/tpr`:
  "this command can only be used in single player mode"); lines that arrive while a question
  is open are kept out of the chat.
- The newer F3 keys: F3+F4 opens the game mode picker (tap F4 to step through the modes,
  let go of F3 to switch), F3+C copies where you stand as a `/tp` command, F3+I copies the
  block or entity you look at as a `/setblock` or `/summon` command.
- `/locatebiome <biome>` in single player, and on a server that has the mod: the nearest place with that biome within 6400
  blocks, as coordinates to click. Imported biomes go by their modern names
  (`cherry_grove`, `mangrove_swamp`).
- A Game Rules button under More World Options when creating a world, so rules such as
  keeping the inventory are set before the world starts.
- The swap-hands key over a slot of your inventory swaps that slot with the off hand, in
  any container. It is skipped while a text box of the screen has the keyboard.
- "Saving world" in the bottom right corner whenever a single-player world is written to
  disk.
- A search box and a filter on the Controls screen, as the Controlling mod gives modern
  packs: search by name or category, or type `@` and a key (`@R`) to list everything set to
  that key; the filter shows only clashing, unbound or changed keys, with their count. A
  mod that brings its own Controls screen keeps it (EnderModpackTweaks does in Divine
  Journey 2).
- Equipment comparison: the tooltip of armor, a weapon or a tool is joined by the tooltip of
  what you wear or hold in its place. Tools are compared only with tools of the same kind.
- A sound and a message above the hotbar when a held tool or worn armor drops to a tenth of
  its durability, and again just before it breaks.
- Where you died, under the cause of death and in chat, where a click fills in a teleport
  command.
- Freelook: hold F4 (changeable in Controls) and the mouse swings the camera around you in
  third person while you keep walking and aiming the way you faced; letting go puts the view
  back. The camera stops short of walls behind it, as the game's own third-person view does.
- New items get a small gold sparkle in every inventory screen until the mouse passes over
  them, as the Item Highlighter mod does. Whatever lands in your inventory while no screen is
  open counts (picked up, handed over by a machine or a command); moving things around inside
  screens marks nothing.
- A search box on the Select World screen, as since 1.16: part of a world's name or folder
  name. Right click it to clear it.
- Arrival titles, as Traveler's Titles shows them: a dimension's name when you arrive in it
  (with the biome under it), and a biome's name once you have walked into it and stayed a
  moment, fade in above the middle of the screen.
- With Fire Resistance, or in spectator mode, you can see a few blocks into lava instead of
  under one, as since 1.17.
- Clearer water, as since 1.13: seeing under water gets better over the first half minute
  under, up to a good distance; Respiration and Water Breathing still help.
- JEI's search reaches into the open inventory, as in EMI and REI: while JEI's search box has
  the keyboard, items it does not match are dimmed and the ones it does are outlined, with
  JEI's own syntax (@mod, #tooltip, $ore).
- Rarity borders, as Item Borders and Legendary Tooltips show them: uncommon, rare and epic
  items (and items a mod names in a colour, such as Avaritia's) get a thin frame of that
  colour in slots and on the hotbar, and a tooltip border to match.
- Item Zoom: in an inventory or JEI, Z (changeable in Controls) switches on a large picture
  of the item under the mouse beside the screen, as the Item Zoom mod does; Z again switches
  it off.
- Better Mount HUD: riding keeps your own food bar on screen, with the mount's hearts above it,
  and a horse's jump bar only replaces the experience bar while a jump is charging.
- Your own potion and beacon swirls are not drawn in first person, so they no longer float in
  front of your eyes (Universal Tweaks does the same, and has it on in Divine Journey 2).
- F3+Q, which lists the F3 keys in chat, now also lists F3+F4, F3+C and F3+I.
- More F3 lines: the ore names of the block you look at (1.12's stand-in for tags), and the
  fluid (as since 1.13) and the entity (as since 1.14) you look at.
- The game turns its sound down while its window is in the background (Background Volume,
  50% by default, as the Dynamic FPS mod does it).
- Skip Front View (off by default): F5 goes from first person to the view from behind and
  straight back, leaving out the view of your own face.
- The Select World screen picks the most recently played world from the start; Up and Down
  move the choice and Enter plays it, as since 1.16.
- Chat, as Chat Patches has it: the time each message arrived in front of it (hover for the
  date), a right click on a message to copy it, and a message closed unsent with Escape is
  still there the next time the chat opens.

Universal Tweaks and Quark already bring armor swapping, sign editing, setting the spawn
point by day, flashing items before they despawn, hiding lightning flashes and swinging
through grass to a pack that has them, so the mod leaves those alone. Divine Journey 2 also
already has toast control, compact chat and a narrator switch (Universal Tweaks), map and
shulker box tooltips (Quark), mob health bars (EnderModpackTweaks) and enchantment
descriptions on books (Wawla), and fast leaf decay (Universal Tweaks), so those are not
repeated here. Any pack with Tinkers' Construct already colours extra rows of hearts (its
library Mantle does), so health bars are left alone too.

**Chat.**

- Emotes, written as on Discord: `:smile:`, `:joy:`, `:thumbsup:`, `:fire:` and about 80
  more are drawn as small pictures, and so is any item by name (`:diamond:`,
  `:iron_ingot:`). While typing, a colon and two letters list the matches above the chat
  box and Tab finishes the first. The message itself stays plain text, so players without
  the mod see the short code. The pictures are original pixel art drawn by
  `tools/make_emotes.py`.
- Items in chat: hover an item in any inventory or in JEI and press Shift+T, or write
  `[item]` in a message to show what you are holding. Players with the mod see the item's
  name in its rarity color and its full tooltip on hover; everyone else sees the name
  followed by a short code. Codes and emote names are still recognised after a server has
  cut the message into pieces or stripped underscores as markup (FTB Utilities does). Where Quark's own item linking is on and the server has Quark,
  inventory items are left to Quark, which sends a real item link.

**World colors.** The Colors page of the menu sets the color of the sky (with the horizon
haze to match) and of water: the game's own, a preset (Sunset, Dusk, Mint, Tropical,
Crystal and more), or any color from a picker with a hue bar and a hex field you can type
or paste into. Night, rain and thunder still darken a recolored sky. Water in tanks and
buckets keeps its normal look. Purely cosmetic.

**Recipes.** Hover any item and press T for a recipe tree: the goal at the top, each chosen
recipe underneath, and on the left the total raw materials (with what you already carry),
leftovers, and the crafting steps in order. Click an ingredient to choose its recipe; the
choice is remembered for later trees. Shift-click fills in a subtree automatically and
stops at ingots, dusts and gems. With JEI installed the tree knows every recipe JEI shows;
without it, crafting and smelting. JEI recipe slots that accept any of several items say
which ore dictionary entry they take, and Shift lists an item's ore dictionary names.
Opened from an Applied Energistics 2 terminal, the tree also counts what the ME network
holds and marks what the network can craft. Where a recipe takes any of several items, the
tree shows the one you have.

Recipes can be bookmarked, not only items: in JEI's recipe screen, hold Shift and press the
bookmark key (A) over a recipe. The bookmark carries a folded corner, opens exactly that
recipe, lists what it takes in its tooltip and shows the recipe in place while Shift is
held; the same keys over the recipe take the bookmark away again. JEI Utilities already
does this (bookmark an item from inside a recipe and it remembers the recipe), so where
that mod is installed, as in Divine Journey 2, this part stays off.

**Quest book.** With Better Questing installed, the quest book still opens on Better
Questing's home screen, with its Party and Theme buttons. Its Quests button opens the layout
FTB Quests uses, and Escape goes back to the home screen: chapters down the left edge (hover to see names and progress), the chapter's
quests as round nodes on a canvas you drag and zoom, lines for prerequisites, and a quest
opening as a window with tasks, rewards and description. Quests, progress, task widgets and
everything sent to the server are Better Questing's own. A click opens a quest anywhere in
its circle or the light ring around it. The Classic button opens the original quest lines,
and edit mode always uses them. Each chapter remembers which part of its
map you were looking at and how large, also across a window resize; the map cannot be
dragged out of sight; the chapter bar opens once the mouse rests on it and shows where
you are in a long list; a locked quest's state says what has to be finished first.

**Modern textures.** Vanilla blocks, items, mobs, chests and armor get the textures current
Minecraft uses. The textures are not in the jar: on first start the mod reads a Minecraft
1.14+ jar already installed on the computer (official launcher, Prism, MultiMC, ATLauncher,
GDLauncher, CurseForge), renames about 1,070 textures to their 1.12.2 names, converts the
few whose format changed, and caches the result in `better112/modern_textures.zip`. The
pack sits just above vanilla, so resource packs and mods that restyle vanilla still win.

To see whether it took, hover Modern Textures in the menu (Extras). The line under the
description is a live check: the mod asks the game which resource pack supplies ten
well-known vanilla textures and whether the block atlas holds the converted cobblestone,
and names the pack that is overriding them if one is. The same line is written to the log
at startup ("Modern textures self-check").

Mods work the same way. For each supported mod there is a table pairing its 1.12.2 texture
names with the modern release's; if a modern jar of that mod is found in another launcher
instance, or dropped into `better112/modern_mods/`, its textures are converted into
`better112/modern_mod_textures.zip`. Only textures whose shape is unchanged are taken (or a still picture that the modern
release animates), so blocks whose model was redesigned keep their old look, and textures
the modern release left unchanged are skipped. A texture that joins up with its neighbours
(Mekanism's casings, with the Connected Textures mod) keeps the 1.12.2 mod's own rules for
joining, and its joined-up sheet is replaced together with the plain one, or not at all.
The Modern Textures option in the menu says what was found.

**Textures VintageFix leaves out.** VintageFix (in DJ2) loads only the textures that model
files name, so a texture a mod names in its code shows the purple-and-black placeholder. In
DJ2 that was EvilCraft's broom parts. The mod puts those textures in the atlas itself: the
ones known from DJ2, and every one VintageFix reports as "not discovered during texture pass",
which it keeps in `better112/late_sprites.txt` for the next start.

| Mod | Textures that change | Notes |
| --- | --- | --- |
| DivineRPG | about 1,000 | redrawn throughout, names kept |
| Mystical Agriculture and Agradditions | 269 | every seed and essence, ingots, blocks, furnaces |
| Actually Additions | 191 | crystals (named Void, Palis and so on now), lamps, machine faces, batteries, food, crops; drill and laser upgrades and lenses keep their 1.12 look, as the modern ones are all the same chip |
| Atum | 189 | relics (Atum's became Atem's), brooches, rings, stained crystal and framed glass, doors, furnace |
| Mekanism | 165 | items and machine faces; factories and 3D-model machines left alone |
| Thermal series | 160 | materials, ores, blocks, machine faces, fluids |
| Applied Energistics 2 | 147 | cells, components, processors, cards, tools, machine faces; singularities and charged items animate as they do now |
| The Aether | 142 | blocks, tools, weapons, accessories, keys, buckets, food |
| Quark | 84 | stone variants (slate is shale now), bookshelves, ancient tomes, glowshrooms, soul sandstone; pipes left alone |
| Botania | 81 | tools, ingots, rods |
| Tinkers' Construct | 55 | smeltery blocks, casts, ingots; tools untouched |
| Storage Drawers | 48 | drawer fronts, sides and trims |
| Bewitchment | 43 | altar cloths, herbs, brooms, poppets |
| Ender IO | 41 | most art carried over unchanged |
| Extended Crafting | 41 | tables, catalysts, components, storage blocks |
| Extreme Reactors | 22 | ores, ingots, dusts, crystals, material blocks; the reactor and turbine casings stay, as they join up differently now |
| Twilight Forest | 12 | castle stairs, magic and maze maps, arctic armor, torchberries; nearly all of its art is unchanged, and its oversized item icons (32-pixel art padded for a double-size icon) keep the 1.12 art |
| Industrial Foregoing | 17 | addons, plastic, rubber |
| BetterNether, Traverse | 29 | Nether plants and blocks; fir and autumn trees |
| Avaritia, Chisels & Bits | 22 | Re:Avaritia's ingots and machine faces; bit tools |
| Project Red, Simply Jetpacks 2 | 18 | gems and tools; jetpacks and thrusters |
| Immersive Engineering, Astral Sorcery, EvilCraft | 19 | a few items each |
| Blood Magic, Draconic Evolution, ProjectE | 6 | their modern releases reuse the 1.12 art |

Avaritia has no modern release of its own; the table reads Re:Avaritia, which continues it. Astral
Sorcery and Atum stopped at 1.16.5, which is the version the tables read. Chisel was tried against
Chisel Reborn and left out: its patterned blocks pair up with plain ones there. Twilight Forest,
RFTools, Integrated Dynamics, Ender Storage, Iron Chests and AgriCraft kept their art (or redrew it
for models that changed), so there is little or nothing to take.

**Modern Terrain.** A world type, picked when creating a world, that shapes the overworld
the way Minecraft 1.18 and later do: continents with real coasts, tall peaks, river
valleys, and caves that are part of the terrain, with biomes laid out by climate so that
rivers lie in valleys and snowy peaks sit on mountains. The shapes are not copied by hand:
the mod reads the modern game's own worldgen data files (the same kind of files a modern
data pack overrides) from a Minecraft 1.19 or newer installed on the computer, keeps a
copy in `better112/modern_worldgen.zip`, and evaluates them for the world's seed. Biomes
1.12.2 already has keep their 1.12.2 selves, so packs' tweaks to them still apply, and
other mods add ores, trees and structures through the usual Forge hooks. Overworld biomes
that other mods add (Traverse, Biomes O' Plenty) join the layout wherever the climate
calls for the same kind of place.

What it does not have: the part of a modern world below y 0. Sea level stays at 63. A
dedicated server reads the same data from the jars in its `better112/modern_mods` (see
Setting up a server above).

**Modern Minecraft's own blocks and biomes.** The newer biomes are made of blocks 1.12.2
does not have, so those come first: 178 blocks read from the newest Minecraft installed
on the computer (1.21.10 here), registered as `better112:mc_NAME` with their own models,
textures and names and a creative tab. Nothing of them is in this jar.

- Blocks: the cherry, mangrove, pale oak and bamboo wood sets down to signs; deepslate,
  tuff, calcite, dripstone, smooth basalt and amethyst with their bricks, tiles, stairs,
  slabs and walls; mud, packed mud and mud bricks; moss and pale moss, as blocks and
  carpets; blue ice and powder snow; bamboo that grows; pointed dripstone that shapes
  itself into stalactites and stalagmites; glow lichen; cave vines with glow berries,
  spore blossoms, hanging roots and pale hanging moss; azaleas; mangrove roots; and the
  newer flowers and ground cover (cornflower, lily of the valley, torchflower, pitcher
  plant, pink petals, wildflowers, leaf litter, bushes, firefly bush, dry grass, cactus
  flower, sweet berry bush, eyeblossoms).
- Under water: seagrass and tall seagrass, kelp, sea pickles, and the five corals as
  blocks, plants and fans. 1.12.2 cannot keep a plant and water in one block, so each of
  these is a block that counts as water itself: you swim through it, the water around
  it stays put, and breaking it leaves water. They stand at least one block below the
  surface, because such a block draws no water surface of its own.
- Amethyst: buds and clusters that grow on budding amethyst in four steps, as in the
  modern game. A cluster breaks into amethyst shards, the one plain item brought over,
  and four shards make an amethyst block.
- Recipes: the game's own recipes for these blocks (91, read from the same jar), and
  furnace recipes such as deepslate from cobbled deepslate. Saplings and the mangrove
  propagule grow into their trees. An axe used on an imported log strips its bark (the
  Biomes O' Plenty woods too).
- Biomes, in Modern Terrain worlds, exactly where the modern game puts them: meadow,
  cherry grove, grove, snowy slopes, frozen, jagged and stony peaks, mangrove swamp,
  bamboo jungle, pale garden, and the warm, lukewarm, cold and frozen oceans. Each is read
  from the game's own biome file (climate, colors, creatures, what grows there). Cherry trees,
  mangroves on their arching roots, pale oaks hung with moss and azaleas are built the way
  the modern game builds them. The ground follows the modern rules too: snow and packed
  ice on the peaks with bare stone on steep faces, drifts of powder snow, bands of
  calcite, mud in the mangroves, sand under warm seas.
- Seas: coral reefs and sea pickles in warm oceans; kelp forests and seagrass in the
  others, and seagrass in rivers and swamps, including the oceans, rivers and swamps that
  stay 1.12.2's own biomes. Frozen oceans are the modern kind: ice in broad patches with
  open water between, icebergs of packed ice with snow on the tallest, blue ice under them.
- Underground, under every biome of a Modern Terrain world: glow lichen on the rock,
  amethyst geodes (a hollow of amethyst inside calcite and smooth basalt, about one in 24
  chunks, usually cracked open on one side), and the two cave biomes where the climate
  deep down calls for them. Lush caves have moss on floor and
  ceiling, cave vines, spore blossoms, clay pools and an azalea tree on the surface above
  with its roots reaching down; dripstone caves have clusters of stalactites and
  stalagmites.

Measured: decorating a chunk of these biomes takes about 1 ms in Divine Journey 2 (up to
3 ms in the thick of a mangrove swamp or cherry grove); what the generator adds to every
chunk (lichen, geodes, sea plants, cave biomes) about 0.3 ms for an ordinary chunk and 1
to 2 ms where there are lush or dripstone caves.

What is different from the modern game: powder snow is solid; berries are not picked from
bushes and vines; there are no bees' nests or hanging mangrove pods on trees; big dripstone
columns, the large sculpted icebergs and the deep dark are not made; kelp does not grow
and coral does not die out of water; water plants cannot stand in the top layer of water
or in water one block deep; a bucket used on a water plant scoops it up with the water.
"Modern Minecraft Blocks and Biomes" under Extras switches all of this off (after a
restart).

**Biomes O' Plenty from 1.20.** With a Biomes O' Plenty jar for Minecraft 1.19.3 or newer
in `better112/modern_mods/` (or in another instance of the same launcher), the mod reads
the modern release's own files and brings its overworld content into 1.12.2. Nothing of
Biomes O' Plenty is in this jar.

- Blocks: 352 of its 429, registered as `better112:bop_NAME` with their modern models,
  textures and names and a creative tab of their own. All thirteen wood sets down to
  signs (logs, stripped logs, wood, planks, stairs, slabs, fences, gates, doors,
  trapdoors, buttons, pressure plates, signs, leaves, saplings), the sands, sandstones and
  their walls, flowers, grasses, tall plants, petals and clover, leaf piles, the lily
  pad, willow vines and the giant-mushroom blocks. Leaves and plants take the biome's
  color where the modern mod tints them. Wood, leaves, saplings and sand are in the ore
  dictionary.
- Saplings grow into their trees, by themselves in the light or with bone meal.
- Recipes: 219 crafting recipes and the furnace recipes from the mod's recipe files
  (planks from logs, everything made of planks, sandstone, dyes from flowers), plus charcoal
  from its logs. A plank of an imported wood makes that wood's button, pressure plate and
  trapdoor, not the oak ones.
- Biomes: its 59 overworld biomes, in Modern Terrain worlds. Climate, grass, leaf, sky and
  water colors and creatures come from each biome's file, and so does the list of what
  grows there, which a reader for the modern "feature" files turns into trees, flowers,
  grass, fallen logs, leaf piles, boulders, mushrooms large and small, pumpkin patches and
  the rest. Each biome joins the climate slots of the vanilla biomes it is most like, and
  gets regions of its own a few hundred blocks across. The decoration costs about 1.5 ms
  per chunk.

What is filled in by hand. The mod keeps some things in code, not in files: its tree
kinds and its small decorations are rebuilt here from their names and numbers, closely
for the oak-, spruce- and palm-like trees and loosely for the rest; where a biome lies in
the world is by likeness, not the mod's own map; a dozen biomes have their ground set in
a short table.

What is left out. Hanging signs and potted plants (1.12.2 has neither a hanging sign nor
a flower pot that takes modded plants), the crystal buds, brambles, the huge lily pad and
clover, and the two fluids. Decoration that needs a block not brought over: dripleaf. The Nether, End and cave biomes: those
would change dimensions every existing world already has, so they wait for a yes.

Worlds that use these blocks need this mod and the jar to stay in place, as with any
modded block. "Biomes O' Plenty Import" under Extras switches the whole thing off (after a
restart).

## Settings and files

| File | What |
| --- | --- |
| `config/better112.cfg` | Every setting from the menu |
| `config/better112/recipe_defaults.txt` | The recipe chosen for each item |
| `config/better112/recipe_bookmarks.json` | What each bookmarked recipe stands for; JEI's own bookmark file keeps their order |
| `config/better112/disabled_patches.txt` | Optional. One patch id per line (or `*`) to switch a bytecode patch off |
| `better112/modern_textures.zip` | Converted vanilla texture cache; delete to rebuild |
| `better112/modern_mod_textures.zip` | Converted mod texture cache; delete to rebuild |
| `better112/modern_mods/` | Optional. Modern mod jars to take textures from |
| `better112/modern_worldgen.zip` | Worldgen data copied from the installed modern Minecraft, for the Modern Terrain world type |
| `better112/modern_content.zip` | Models, textures and names of the imported blocks (modern Minecraft's and Biomes O' Plenty's); delete to rebuild |

## How it hooks the game

`better112.core` is a small coremod with no Mixin dependency. Every patch adds a call at the
head of a method or swaps one value, never replaces code, so it coexists with Mixin mods
that target the same methods. A patch that does not find what it expects is skipped, and
the options that need it are greyed out with the reason. Texture animation culling stands
down when OptiFine or a CensoredASM-family mod already does it.

## Building

Needs a JDK 25 to run Gradle (RetroFuturaGradle 2) and a JDK 8 for the mod itself; Gradle
finds or downloads the JDK 8.

```bash
JAVA_HOME=/path/to/jdk-25 ./gradlew build
```

`libs/` holds jars that are only compiled against and are not part of this repository: put
`BetterQuesting-3.5.329.jar` there from a pack's `mods` folder first (see `libs/README`).

`tools/make_emotes.py` redraws the chat emote sheet and its list of names.

`tools/make_texture_map.py` regenerates the vanilla texture name table from the file lists
of a 1.12.2 jar and a modern jar. `tools/build_mod_maps.sh OLD_MODS NEW_MODS [REVIEW_DIR]`
regenerates the mod tables; with a review folder it also writes a sheet of every old/new
pair to look over, which is how a new mod gets added.

`better112.world.Preview` draws the Modern Terrain to pictures without starting the game
(a map from above and a vertical cut), from a modern jar and a seed:

```bash
java -cp build/classes/java/main:gson.jar better112.world.Preview MODERN.jar SEED X Z CHUNKS OUT_DIR
```

## Self-test

Starting the game with `-Dbetter112.autotest=<script file>` runs a scripted session: create
a world, open menu pages, take screenshots, run commands, measure FPS, then quit. Results
land in `better112-autotest/report.txt` and `screenshots/`. The step list is at the top of
`src/main/java/better112/test/AutoTest.java`.
