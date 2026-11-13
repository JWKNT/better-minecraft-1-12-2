package better112.content;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.imageio.ImageIO;

import better112.Better112;
import better112.config.BetterConfig;
import better112.content.BlockDef.Kind;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.block.Block;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemDoor;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.oredict.OreDictionary;

/**
 * Brings modern blocks into 1.12.2 from the player's own modern jars: the blocks of Biomes
 * O' Plenty 1.20, and the blocks Minecraft itself has gained since 1.12.2 that modern
 * biomes are made of (cherry and mangrove wood, deepslate, moss, mud and so on). Nothing of
 * either is shipped here. At startup the jars are looked for in better112/modern_mods and
 * in the launchers on this computer; what each block is, how it looks and what it is called
 * are read from the jar's data files, and the looks are copied into a resource pack next to
 * the texture caches.
 *
 * Blocks are registered as better112:bop_NAME and better112:mc_NAME. A world that uses them
 * needs this mod and the jars to stay where they are, like any world with modded blocks.
 */
public final class ModernContent {

    private ModernContent() {}

    static final Source BOP = new Source("biomesoplenty", "bop", "Biomes O' Plenty (from 1.20)");
    static final Source MC = new Source("minecraft", "mc", "Modern Minecraft");
    private static final Source[] ALL = {MC, BOP};
    /** Bump when the pack's layout changes, so stale packs are rebuilt. */
    private static final int FORMAT = 4;
    private static final Pattern JAR = Pattern.compile("(?i)biomesoplenty.*-1\\.(19\\.[3-9]|2[0-9])[.-].*\\.jar");

    /**
     * The blocks of modern Minecraft worth having here: the ones modern biomes and caves are
     * built from, with the building blocks made from them. (Not: anything 1.12.2 already has,
     * the Nether and End updates, copper, redstone parts, and blocks that only make sense
     * with water inside them, which 1.12.2 cannot do.)
     */
    private static final Pattern VANILLA = Pattern.compile(
            "(stripped_)?(cherry|mangrove|pale_oak|bamboo)_(planks|log|wood|block|leaves|sapling|stairs|slab|fence|fence_gate|door|trapdoor"
                    + "|button|pressure_plate|sign|wall_sign|mosaic|mosaic_slab|mosaic_stairs|roots|propagule)"
                    + "|muddy_mangrove_roots|bamboo"
                    + "|(cobbled_|polished_|chiseled_)?deepslate|(cobbled_deepslate|polished_deepslate|deepslate_brick|deepslate_tile)_(slab|stairs|wall)"
                    + "|(cracked_)?deepslate_(bricks|tiles)"
                    + "|(polished_|chiseled_)?tuff|(tuff|polished_tuff|tuff_brick)_(slab|stairs|wall)|(chiseled_)?tuff_bricks"
                    + "|calcite|dripstone_block|pointed_dripstone|smooth_basalt|amethyst_block|budding_amethyst"
                    + "|mud|packed_mud|mud_bricks|mud_brick_(slab|stairs|wall)|rooted_dirt"
                    + "|(pale_)?moss_(block|carpet)|pale_hanging_moss|(flowering_)?azalea(_leaves)?|spore_blossom|hanging_roots"
                    + "|cave_vines(_plant)?|glow_lichen|blue_ice|powder_snow"
                    + "|cornflower|lily_of_the_valley|torchflower|pitcher_plant|pink_petals|wildflowers|leaf_litter|bush|firefly_bush"
                    + "|(short|tall)_dry_grass|cactus_flower|sweet_berry_bush|(open|closed)_eyeblossom"
                    + "|seagrass|tall_seagrass|kelp|kelp_plant|sea_pickle|(tube|brain|bubble|fire|horn)_coral(_block|_fan|_wall_fan)?"
                    + "|(small|medium|large)_amethyst_bud|amethyst_cluster");

    /** Things of modern Minecraft that are only items, wanted because an imported block breaks into them. */
    private static final String[] PLAIN_ITEMS = {"amethyst_shard"};
    static final List<String> PLAIN = new ArrayList<String>();

    static final List<BlockDef> DEFS = new ArrayList<BlockDef>();
    /** Name here ("bop_fir_planks", "mc_cherry_log") to the registered stand-in. */
    static final Map<String, Block> BLOCKS = new LinkedHashMap<String, Block>();
    static final Map<String, Item> ITEMS = new LinkedHashMap<String, Item>();
    private static final Map<String, ModernBlocks.Traits> TRAITS = new LinkedHashMap<String, ModernBlocks.Traits>();
    private static final Map<Source, List<String>> SKIPPED = new LinkedHashMap<Source, List<String>>();
    private static String status = "";
    /** Modern Minecraft jars, newest first: the first is where the game's own new blocks are read from. */
    private static List<File> games = new ArrayList<File>();

    /** Grows the tree of a sapling; set once the worldgen side has read the trees. */
    static ModernBlocks.TreeGrower treeGrower;

    /** For the worldgen side: how to grow a tree from an imported sapling ("bop_fir_sapling", "mc_cherry_sapling"). */
    public interface SaplingTrees {
        boolean grow(String sapling, net.minecraft.world.World world, net.minecraft.util.math.BlockPos pos, java.util.Random random);
    }

    public static void setSaplingTrees(final SaplingTrees trees) {
        treeGrower = new ModernBlocks.TreeGrower() {
            @Override
            public boolean grow(String sapling, net.minecraft.world.World world, net.minecraft.util.math.BlockPos pos, java.util.Random random) {
                return trees.grow(sapling, world, pos, random);
            }
        };
    }

    /** What was imported, for the log and the menu. */
    public static String status() {
        return status + (ContentRecipes.crafting > 0 ? "; " + ContentRecipes.crafting + " crafting and " + ContentRecipes.smelting + " furnace recipes" : "");
    }

    public static int blockCount() {
        return BLOCKS.size();
    }

    /** How many blocks came from one source ("bop", "mc"). */
    public static int blockCount(String prefix) {
        for (Source source : ALL) {
            if (source.prefix.equals(prefix)) {
                return source.blocks;
            }
        }
        return 0;
    }

    static Source source(String namespace) {
        for (Source source : ALL) {
            if (source.namespace.equals(namespace)) {
                return source;
            }
        }
        return null;
    }

    /** The stand-in for a modern block id such as "biomesoplenty:fir_log" or "minecraft:cherry_log", or null. */
    public static Block block(String modernId) {
        int colon = modernId.indexOf(':');
        Source source = source(colon < 0 ? "minecraft" : modernId.substring(0, colon));
        return source == null ? null : BLOCKS.get(source.key(modernId.substring(colon + 1)));
    }

    static Item item(String modernId) {
        int colon = modernId.indexOf(':');
        Source source = source(colon < 0 ? "minecraft" : modernId.substring(0, colon));
        return source == null ? null : ITEMS.get(source.key(modernId.substring(colon + 1)));
    }

    /** The Biomes O' Plenty jar the content came from, or null: the biome reader uses the same one. */
    public static File sourceJar() {
        return BOP.jar;
    }

    /** The modern Minecraft jar the game's own new blocks came from, or null. */
    public static File gameJar() {
        return MC.jar;
    }

    // ------------------------------------------------------------------------------------
    // Startup: read the jars, build the pack
    // ------------------------------------------------------------------------------------

    /**
     * Called while the mod is constructed, on both sides: before the registries open, and in the
     * game before it reads its resource packs. Returns the resource pack with the looks of the
     * blocks, for the game client to add, or null.
     */
    public static File prepare(File gameDir) {
        boolean client = FMLCommonHandler.instance().getSide().isClient();
        List<String> notes = new ArrayList<String>();
        try {
            games = GameJars.games(gameDir);
        } catch (RuntimeException e) {
            games = new ArrayList<File>();
        }
        File bop = BetterConfig.importBiomesOPlenty ? findJar(gameDir) : null;
        File game = BetterConfig.importModernVanilla && !games.isEmpty() ? games.get(0) : null;
        if (!BetterConfig.importBiomesOPlenty) {
            notes.add("Biomes O' Plenty import is switched off");
        } else if (bop == null) {
            notes.add("no Biomes O' Plenty jar for Minecraft 1.19.3 or newer was found (better112/modern_mods"
                    + (client ? " or another launcher instance)" : ")"));
        }
        if (!BetterConfig.importModernVanilla) {
            notes.add("modern Minecraft blocks are switched off");
        } else if (game == null) {
            notes.add(client ? "no modern Minecraft was found on this computer" : "no modern Minecraft client jar in better112/modern_mods");
        }
        if (bop == null && game == null) {
            status = join(notes);
            Better112.LOG.info("Modern content: {}", status);
            if (client) {
                better112.world.feature.Imported.set(LOOKUP);
            }
            return null;
        }
        File packFile = null;
        List<ZipFile> vanilla = new ArrayList<ZipFile>();
        ZipFile mod = null;
        try {
            long start = System.currentTimeMillis();
            File dir = new File(gameDir, "better112");
            File zip = new File(dir, "modern_content.zip"), stampFile = new File(dir, "modern_content.stamp");
            StringBuilder stampText = new StringBuilder("format " + FORMAT + "\n");
            for (File jar : new File[] {game, bop}) {
                if (jar != null) {
                    stampText.append(jar.getAbsolutePath()).append('|').append(jar.length()).append('|').append(jar.lastModified()).append('\n');
                }
            }
            for (File jar : games) {
                try {
                    vanilla.add(new ZipFile(jar));
                } catch (Exception ignored) {
                    // an unreadable jar is skipped
                }
            }
            ContentPack pack = new ContentPack(vanilla);
            if (game != null && !vanilla.isEmpty()) {
                try {
                    read(MC, vanilla.get(0), pack);
                    MC.jar = game;
                } catch (Throwable t) {
                    drop(MC);
                    notes.add("could not read " + game.getName() + ": " + t);
                    Better112.LOG.warn("Modern Minecraft blocks are unavailable", t);
                }
            }
            if (bop != null) {
                try {
                    mod = new ZipFile(bop);
                    pack.addMod(BOP.namespace, BOP.prefix, mod);
                    read(BOP, mod, pack);
                    BOP.jar = bop;
                } catch (Throwable t) {
                    drop(BOP);
                    notes.add("could not read " + bop.getName() + ": " + t);
                    Better112.LOG.warn("Biomes O' Plenty blocks are unavailable", t);
                }
            }
            if (DEFS.isEmpty()) {
                notes.add("the jars held no blocks this mod can read");
                status = join(notes);
                return null;
            }
            // The pack is rebuilt when a jar changes and when this mod reads the same jars differently than before.
            long reading = 17L;
            for (BlockDef def : DEFS) {
                reading = reading * 31L + (def.key + def.kind + def.parts + def.tint + def.cutout).hashCode();
            }
            String stamp = stampText.append("blocks ").append(DEFS.size()).append(' ').append(reading).append('\n').toString();
            boolean fresh = zip.isFile() && stampFile.isFile()
                    && stamp.equals(new String(Files.readAllBytes(stampFile.toPath()), StandardCharsets.UTF_8));
            // A server needs the blocks but not their looks.
            if (client && !fresh) {
                dir.mkdirs();
                int files = pack.write(zip, "from " + (game == null ? "" : game.getName() + " ") + (bop == null ? "" : bop.getName()));
                Files.write(stampFile.toPath(), stamp.getBytes(StandardCharsets.UTF_8));
                Better112.LOG.info("Modern content pack: {} files", files);
                if (!pack.missing.isEmpty()) {
                    Better112.LOG.warn("Modern content pack: not found in the jars: {}", pack.missing);
                }
            }
            packFile = client ? zip : null;
            MinecraftForge.EVENT_BUS.register(ModernContent.class);
            List<String> parts = new ArrayList<String>();
            for (Source source : ALL) {
                if (source.jar != null) {
                    List<String> skipped = SKIPPED.get(source);
                    parts.add(source.blocks + " blocks from " + source.jar.getName()
                            + (source.vanilla() || skipped == null || skipped.isEmpty() ? "" : " (" + skipped.size() + " kinds of block not brought over)"));
                }
            }
            parts.addAll(notes);
            status = join(parts);
            Better112.LOG.info("Modern content: {} ({} ms), signature {}. Not brought over: {}", status, System.currentTimeMillis() - start,
                    Long.toHexString(reading), SKIPPED);
        } catch (Throwable t) {
            DEFS.clear();
            BOP.jar = null;
            MC.jar = null;
            status = "Could not read the modern jars: " + t;
            Better112.LOG.warn("Modern content is unavailable", t);
        } finally {
            for (ZipFile each : vanilla) {
                try {
                    each.close();
                } catch (Exception ignored) {
                    // nothing to do
                }
            }
            if (mod != null) {
                try {
                    mod.close();
                } catch (Exception ignored) {
                    // nothing to do
                }
            }
        }
        // The biomes are registered even if no block could be read: they fall back on 1.12.2 blocks.
        MinecraftForge.EVENT_BUS.register(better112.world.gen.ImportedBiomes.class);
        better112.world.feature.Imported.set(LOOKUP);
        return packFile;
    }

    private static String join(List<String> parts) {
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            out.append(out.length() == 0 ? "" : "; ").append(part);
        }
        return out.toString();
    }

    /** Forgets a source whose jar turned out unreadable half way. */
    private static void drop(Source source) {
        for (java.util.Iterator<BlockDef> it = DEFS.iterator(); it.hasNext();) {
            if (it.next().source == source) {
                it.remove();
            }
        }
        source.jar = null;
        source.blocks = 0;
    }

    private static File findJar(File gameDir) {
        List<File> folders = GameJars.modFolders(gameDir);
        File best = null;
        for (File folder : folders) {
            File[] files = folder.listFiles();
            if (files == null) {
                continue;
            }
            for (File file : files) {
                if (file.isFile() && JAR.matcher(file.getName()).matches() && (best == null || file.getName().compareTo(best.getName()) > 0)) {
                    best = file;
                }
            }
            if (best != null) {
                return best; // the drop folder is first and wins
            }
        }
        return null;
    }

    /** Decides what every block of a source is and queues its looks for the pack. */
    private static void read(Source source, ZipFile jar, ContentPack pack) throws Exception {
        String ns = source.namespace;
        JsonObject lang = ContentPack.jsonAt(jar, "assets/" + ns + "/lang/en_us.json");
        List<String> names = new ArrayList<String>();
        String folder = "assets/" + ns + "/blockstates/";
        for (Enumeration<? extends ZipEntry> e = jar.entries(); e.hasMoreElements();) {
            String name = e.nextElement().getName();
            if (name.startsWith(folder) && name.endsWith(".json")) {
                String block = name.substring(folder.length(), name.length() - 5);
                // Biomes O' Plenty's "null" blocks are drawn as the missing-texture checker on purpose; here they would look broken.
                if (source.vanilla() ? VANILLA.matcher(block).matches() : !block.startsWith("null_")) {
                    names.add(block);
                }
            }
        }
        Collections.sort(names);
        for (String name : names) {
            if (name.endsWith("_planks")) {
                source.woods.add(name.substring(0, name.length() - 7));
            }
        }
        List<String> skipped = new ArrayList<String>();
        SKIPPED.put(source, skipped);
        for (String name : names) {
            JsonObject state = pack.json(ns, "blockstates/" + name + ".json");
            BlockDef def = classify(source, name, state, pack);
            if (def == null) {
                skipped.add(name);
                continue;
            }
            for (String wood : source.woods) {
                if (name.startsWith(wood + "_") || name.startsWith("stripped_" + wood + "_")) {
                    def.wood = wood;
                }
            }
            def.english = lang != null && lang.has("block." + ns + "." + name) ? lang.get("block." + ns + "." + name).getAsString() : title(name);
            DEFS.add(def);
            source.blocks++;
            String registry = def.key;
            pack.addBlockstate(registry, state, def.parts);
            if (hasItem(def)) {
                itemModel(source, def, state, pack);
            }
            pack.addName("tile." + ContentPack.DOMAIN + "." + registry + ".name", def.english);
            if (def.kind == Kind.DOOR || def.kind == Kind.SIGN) {
                pack.addName("item." + ContentPack.DOMAIN + "." + registry + ".name", def.english);
            }
            if (def.kind == Kind.SIGN) {
                // The game draws a sign from a texture sheet of its own, which no model mentions.
                pack.addTexture(ns + ":entity/signs/" + name.substring(0, name.length() - "_sign".length()));
            }
        }
        if (source.vanilla()) {
            for (String item : PLAIN_ITEMS) {
                if (pack.has(ns, "models/item/" + item + ".json")) {
                    PLAIN.add(item);
                    pack.addItemModel(source.key(item), ns + ":item/" + item);
                    pack.addName("item." + ContentPack.DOMAIN + "." + source.key(item) + ".name",
                            lang != null && lang.has("item." + ns + "." + item) ? lang.get("item." + ns + "." + item).getAsString() : title(item));
                }
            }
        }
        pack.addName("itemGroup.better112_" + source.prefix, source.title);
    }

    /** Whether a block is also an item. A sign's or coral fan's wall form is placed by the standing one's item; kelp's stalk comes with kelp. */
    static boolean hasItem(BlockDef def) {
        return def.kind != Kind.WALL_SIGN && def.kind != Kind.AQUATIC_WALL && !def.name.equals("kelp_plant");
    }

    /**
     * The model an imported block shows in the hand. Mods and Minecraft up to 1.21.3 keep one
     * per item under models/item; later versions say in an "items" file which model to show,
     * most often the block's own; a plant that is no item at all in the modern game (berry
     * bushes, vines) gets a flat picture of itself.
     */
    private static void itemModel(Source source, BlockDef def, JsonObject state, ContentPack pack) {
        String ns = source.namespace, name = def.name;
        JsonObject shown = pack.json(ns, "items/" + name + ".json");
        if (shown != null && shown.has("model") && shown.getAsJsonObject("model").has("model")
                && shown.getAsJsonObject("model").get("model").isJsonPrimitive()) {
            String model = shown.getAsJsonObject("model").get("model").getAsString();
            if (ContentPack.split(model)[1].startsWith("item/")) {
                pack.addItemModel(def.key, model);
            } else {
                pack.addItemModelOf(def.key, model);
            }
            return;
        }
        if (pack.has(ns, "models/item/" + name + ".json")) {
            pack.addItemModel(def.key, ns + ":item/" + name);
            return;
        }
        String model = firstModel(state, def);
        if (model == null) {
            return;
        }
        List<String> chain = chain(model, pack);
        boolean cross = false;
        for (String link : chain) {
            cross |= link.startsWith("minecraft:block/cross") || link.equals("minecraft:block/tinted_cross");
        }
        String texture = cross ? texture(chain, pack) : null;
        if (texture != null) {
            pack.addItemModelFlat(def.key, texture);
        } else {
            pack.addItemModelOf(def.key, model);
        }
    }

    /** The model of the state a block is usually seen in: the one its fixed values pick, else the file's first. */
    private static String firstModel(JsonObject state, BlockDef def) {
        JsonElement found = null;
        if (state.has("variants")) {
            JsonObject variants = state.getAsJsonObject("variants");
            String key = def == null ? null : def.variantFor(new java.util.HashMap<String, String>());
            found = key != null && variants.has(key) ? variants.get(key) : variants.entrySet().iterator().next().getValue();
        } else if (state.has("multipart") && state.getAsJsonArray("multipart").size() > 0) {
            found = state.getAsJsonArray("multipart").get(0).getAsJsonObject().get("apply");
        }
        if (found == null) {
            return null;
        }
        JsonObject one = found.isJsonArray() ? found.getAsJsonArray().get(0).getAsJsonObject() : found.getAsJsonObject();
        return one.has("model") ? one.get("model").getAsString() : null;
    }

    /** The picture a simple model shows: its "cross", "all" or "leaves" texture. */
    private static String texture(List<String> chain, ContentPack pack) {
        for (String link : chain) {
            String[] parts = ContentPack.split(link);
            JsonObject model = pack.json(parts[0], "models/" + parts[1] + ".json");
            if (model != null && model.has("textures")) {
                JsonObject textures = model.getAsJsonObject("textures");
                for (String key : new String[] {"cross", "all", "leaves"}) {
                    if (textures.has(key) && !textures.get(key).getAsString().startsWith("#")) {
                        return textures.get(key).getAsString();
                    }
                }
            }
        }
        return null;
    }

    private static String title(String name) {
        StringBuilder out = new StringBuilder();
        for (String word : name.split("_")) {
            out.append(out.length() == 0 ? "" : " ").append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    private static BlockDef classify(Source source, String name, JsonObject state, ContentPack pack) {
        Map<String, Set<String>> props = BlockDef.properties(state);
        Set<String> keys = props.keySet();
        if (source.vanilla()) {
            // The few modern Minecraft blocks whose files do not give them away.
            BlockDef special = null;
            switch (name) {
                case "bamboo":
                    special = new BlockDef(source, name, Kind.BAMBOO, state);
                    break;
                case "pointed_dripstone":
                    special = new BlockDef(source, name, Kind.DRIPSTONE, state);
                    break;
                case "glow_lichen":
                    special = new BlockDef(source, name, Kind.LICHEN, state);
                    break;
                case "spore_blossom":
                case "hanging_roots":
                case "pale_hanging_moss":
                case "cave_vines":
                case "cave_vines_plant":
                    special = new BlockDef(source, name, Kind.HANGING, state);
                    break;
                case "azalea":
                case "flowering_azalea":
                    special = new BlockDef(source, name, Kind.PLANT, state);
                    break;
                case "mangrove_propagule":
                    special = new BlockDef(source, name, Kind.SAPLING, state);
                    special.fixed.put("hanging", "false");
                    special.fixed.put("age", "4");
                    break;
                case "sweet_berry_bush":
                    special = new BlockDef(source, name, Kind.PLANT, state);
                    special.fixed.put("age", "3");
                    break;
                case "pale_moss_carpet":
                    special = new BlockDef(source, name, Kind.FLAT, state);
                    special.parts = 3;
                    break;
                case "mangrove_roots":
                    // Whole blocks whose models are built from separate sheets rather than one cube.
                    special = new BlockDef(source, name, Kind.CUBE, state);
                    special.cutout = true;
                    break;
                case "powder_snow":
                    special = new BlockDef(source, name, Kind.CUBE, state);
                    break;
                default:
                    break;
            }
            if (special == null) {
                // What lives under water: 1.12.2 has no plant-in-water, so these are blocks of their own kind.
                if (name.equals("seagrass") || name.equals("kelp") || name.equals("kelp_plant") || name.endsWith("_coral") || name.endsWith("_coral_fan")) {
                    special = new BlockDef(source, name, Kind.AQUATIC, state);
                } else if (name.equals("tall_seagrass")) {
                    special = new BlockDef(source, name, Kind.AQUATIC_TALL, state);
                } else if (name.endsWith("_coral_wall_fan")) {
                    special = new BlockDef(source, name, Kind.AQUATIC_WALL, state);
                } else if (name.equals("sea_pickle")) {
                    special = new BlockDef(source, name, Kind.PICKLE, state);
                    special.fixed.put("waterlogged", "true");
                } else if (name.endsWith("_amethyst_bud") || name.equals("amethyst_cluster")) {
                    special = new BlockDef(source, name, Kind.BUD, state);
                }
            }
            if (special != null) {
                vanillaTint(special);
                return special;
            }
        }
        if (state.has("multipart")) {
            if (keys.equals(set("north", "east", "south", "west"))) {
                return new BlockDef(source, name, Kind.FENCE, state);
            }
            if (keys.equals(set("up", "north", "east", "south", "west")) && props.get("north").contains("low")) {
                BlockDef wall = new BlockDef(source, name, Kind.WALL, state);
                wall.parts = 2;
                return wall;
            }
            if (keys.equals(set("facing", "flower_amount")) || keys.equals(set("facing", "segment_amount"))) {
                BlockDef petals = new BlockDef(source, name, Kind.PETALS, state);
                petals.tint = 2; // stems and clover leaves take the grass colour; petals have no tint mark and keep their own
                if (source.vanilla()) {
                    vanillaTint(petals);
                }
                return petals;
            }
            if (keys.equals(set("north", "east", "south", "west", "up", "down"))) {
                BlockDef cap = new BlockDef(source, name, Kind.CUBE, state); // a giant mushroom's block
                cap.parts = 1;
                return cap;
            }
            return null;
        }
        if (keys.isEmpty() && name.endsWith("_sign") && !name.contains("hanging")) {
            return new BlockDef(source, name, name.endsWith("_wall_sign") ? Kind.WALL_SIGN : Kind.SIGN, state);
        }
        if (keys.isEmpty() && (name.endsWith("_leaf_pile") || name.endsWith("_carpet"))) {
            return new BlockDef(source, name, Kind.FLAT, state);
        }
        if (keys.isEmpty() && name.equals("waterlily")) {
            return new BlockDef(source, name, Kind.LILY, state);
        }
        if (keys.isEmpty() && name.equals("pink_daffodil")) {
            return new BlockDef(source, name, Kind.PLANT, state); // a flower with a model of its own rather than the stock cross
        }
        if (keys.equals(set("east", "north", "south", "up", "west")) && state.has("variants")) {
            BlockDef vine = new BlockDef(source, name, Kind.VINE, state);
            vine.tint = 1;
            return vine;
        }
        if (keys.isEmpty() || keys.equals(set("snowy")) || keys.equals(set("mossy")) || (keys.equals(set("distance")) && name.equals("thermal_calcite"))) {
            JsonObject variants = state.getAsJsonObject("variants");
            JsonElement first = variants.entrySet().iterator().next().getValue();
            JsonObject variant = first.isJsonArray() ? first.getAsJsonArray().get(0).getAsJsonObject() : first.getAsJsonObject();
            List<String> chain = chain(variant.get("model").getAsString(), pack);
            // The stock shape a block's model builds on. A mod's own models come first in the chain and are passed over;
            // in Minecraft's own files every model is "minecraft:", so there it is whatever the block's model names as its parent.
            String template = null;
            for (int i = 0; i < chain.size(); i++) {
                String link = chain.get(i);
                boolean own = source.vanilla() ? i == 0 : link.startsWith(source.namespace + ":");
                if (!own) {
                    template = link.startsWith("minecraft:") ? link.substring(10) : link;
                    break;
                }
            }
            Kind kind = null;
            boolean cubeShaped = template != null && (template.startsWith("block/cube") || ("block/block".equals(template) && fullCube(chain, pack)));
            if ("block/leaves".equals(template) || (name.endsWith("_leaves") && cubeShaped)) {
                // Leaves by their model, or by their name where the mod uses a leaf model of its own (leaves with blossoms).
                kind = Kind.LEAVES;
            } else if (template != null && (template.startsWith("block/cross") || "block/tinted_cross".equals(template))) {
                kind = name.endsWith("_sapling") ? Kind.SAPLING : Kind.PLANT;
            } else if (template != null && template.startsWith("block/cube")) {
                kind = name.endsWith("_sand") && !name.startsWith("mossy") ? Kind.FALLING : Kind.CUBE;
            } else if ("block/block".equals(template) && fullCube(chain, pack)) {
                kind = Kind.CUBE;
            }
            if (kind == null) {
                return null;
            }
            BlockDef def = new BlockDef(source, name, kind, state);
            def.fixed.put("snowy", "false");
            def.fixed.put("mossy", "false");
            def.fixed.put("distance", "1");
            if (source.vanilla()) {
                vanillaTint(def);
                def.cutout = name.equals("mangrove_roots");
            } else if (kind == Kind.LEAVES || kind == Kind.PLANT || kind == Kind.CUBE) {
                def.tint = tint(source, chain, pack, kind);
            }
            return def;
        }
        if (keys.equals(set("axis"))) {
            return new BlockDef(source, name, Kind.PILLAR, state);
        }
        if (keys.equals(set("type")) && props.get("type").contains("double")) {
            return new BlockDef(source, name, Kind.SLAB, state);
        }
        if (keys.equals(set("facing", "half", "shape"))) {
            return new BlockDef(source, name, Kind.STAIRS, state);
        }
        if (keys.equals(set("face", "facing", "powered"))) {
            return new BlockDef(source, name, Kind.BUTTON, state);
        }
        if (keys.equals(set("facing", "half", "hinge", "open"))) {
            return new BlockDef(source, name, Kind.DOOR, state);
        }
        if (keys.equals(set("facing", "in_wall", "open"))) {
            return new BlockDef(source, name, Kind.FENCE_GATE, state);
        }
        if (keys.equals(set("powered"))) {
            return new BlockDef(source, name, Kind.PLATE, state);
        }
        if (keys.equals(set("facing", "half", "open"))) {
            return new BlockDef(source, name, Kind.TRAPDOOR, state);
        }
        if (keys.equals(set("half")) && props.get("half").contains("upper")) {
            BlockDef def = new BlockDef(source, name, Kind.TALL_PLANT, state);
            JsonElement lower = state.getAsJsonObject("variants").get("half=lower");
            if (lower != null && !source.vanilla()) {
                // A variant may be a list of models to pick from at random; the first speaks for all.
                JsonObject one = lower.isJsonArray() ? lower.getAsJsonArray().get(0).getAsJsonObject() : lower.getAsJsonObject();
                def.tint = tint(source, chain(one.get("model").getAsString(), pack), pack, Kind.PLANT);
            }
            return def;
        }
        return null;
    }

    /**
     * Which of Minecraft's own new blocks take a colour. The game decides that in code; it is a
     * short list. (Its grey-textured leaves, cherry and pale oak, are meant to stay grey and pink.)
     */
    private static void vanillaTint(BlockDef def) {
        switch (def.name) {
            case "mangrove_leaves":
                def.tint = 3;
                def.tintColor = 0x92C648;
                break;
            case "leaf_litter":
                def.tint = 3;
                def.tintColor = 0x7B5334;
                break;
            case "bush":
            case "pink_petals":
            case "wildflowers":
                def.tint = 2;
                break;
            default:
                def.tint = 0;
                break;
        }
    }

    private static Set<String> set(String... values) {
        return new HashSet<String>(Arrays.asList(values));
    }

    /** A model and the models it builds on, nearest first. */
    private static List<String> chain(String reference, ContentPack pack) {
        List<String> chain = new ArrayList<String>();
        String at = reference;
        while (at != null && chain.size() < 12 && !at.startsWith("builtin/")) {
            String[] parts = ContentPack.split(at);
            chain.add(parts[0] + ":" + parts[1]);
            JsonObject model = pack.json(parts[0], "models/" + parts[1] + ".json");
            at = model != null && model.has("parent") ? model.get("parent").getAsString() : null;
        }
        return chain;
    }

    private static boolean fullCube(List<String> chain, ContentPack pack) {
        for (String link : chain) {
            String[] parts = ContentPack.split(link);
            JsonObject model = pack.json(parts[0], "models/" + parts[1] + ".json");
            if (model != null && model.has("elements")) {
                for (JsonElement element : model.getAsJsonArray("elements")) {
                    JsonObject o = element.getAsJsonObject();
                    if (o.get("from").toString().equals("[0,0,0]") && o.get("to").toString().equals("[16,16,16]")) {
                        return true;
                    }
                }
                return false;
            }
        }
        return false;
    }

    /**
     * Whether a leaf or plant takes the biome's colour. The modern mod decides that in code;
     * the giveaway in its files is a model that allows tinting together with a grey texture.
     */
    private static int tint(Source source, List<String> chain, ContentPack pack, Kind kind) {
        boolean vanillaTint = false, ownTint = false;
        String texture = null;
        for (String link : chain) {
            if (link.equals("minecraft:block/leaves") || link.equals("minecraft:block/tinted_cross")) {
                vanillaTint = true;
            }
            String[] parts = ContentPack.split(link);
            JsonObject model = pack.json(parts[0], "models/" + parts[1] + ".json");
            if (model == null) {
                continue;
            }
            if (parts[0].equals(source.namespace) && model.toString().contains("tintindex")) {
                // A model of the mod's own that marks faces for tinting (leaves under blossoms, moss on sand): the mod means it.
                ownTint = true;
            }
            if (texture == null && model.has("textures")) {
                JsonObject textures = model.getAsJsonObject("textures");
                for (String key : new String[] {"all", "cross", "leaves"}) {
                    if (textures.has(key) && !textures.get(key).getAsString().startsWith("#")) {
                        texture = textures.get(key).getAsString();
                    }
                }
            }
        }
        int colour = kind == Kind.LEAVES ? 1 : 2;
        if (ownTint) {
            return colour;
        }
        if (!vanillaTint || texture == null || kind == Kind.CUBE) {
            return 0;
        }
        // The stock leaf and tuft models allow tinting whether the mod uses it or not; a grey texture says it does.
        try {
            String[] parts = ContentPack.split(texture);
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(pack.read(parts[0], "textures/" + parts[1] + ".png")));
            long spread = 0, count = 0;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int argb = image.getRGB(x, y);
                    if ((argb >>> 24) > 16) {
                        int r = argb >> 16 & 255, g = argb >> 8 & 255, b = argb & 255;
                        spread += Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
                        count++;
                    }
                }
            }
            return count > 0 && spread / count < 14 ? colour : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------------------------
    // Registration
    // ------------------------------------------------------------------------------------

    private static ModernBlocks.Traits traits(BlockDef def) {
        ModernBlocks.Traits t = new ModernBlocks.Traits();
        String n = def.name;
        if (def.wood != null && def.kind != Kind.LEAVES && def.kind != Kind.SAPLING) {
            t.material = Material.WOOD;
            t.sound = SoundType.WOOD;
            t.tool = "axe";
            t.hardness = def.kind == Kind.DOOR || def.kind == Kind.TRAPDOOR ? 3.0F : def.kind == Kind.BUTTON || def.kind == Kind.PLATE ? 0.5F : 2.0F;
            t.resistance = def.kind == Kind.PILLAR ? 10.0F : 15.0F;
        } else if (def.kind == Kind.LEAVES) {
            t.material = Material.LEAVES;
            t.sound = SoundType.PLANT;
            t.hardness = 0.2F;
            t.tool = null;
        } else if (def.kind == Kind.PLANT || def.kind == Kind.SAPLING || def.kind == Kind.TALL_PLANT || def.kind == Kind.PETALS) {
            t.material = Material.PLANTS;
            t.sound = SoundType.PLANT;
            t.hardness = 0.0F;
            t.tool = null;
            t.sandPlant = n.contains("desert") || n.contains("dune") || n.contains("cactus") || n.equals("sea_oats") || n.equals("dead_grass");
            t.family = n.startsWith("high_grass") ? "high_grass" : null;
            t.rockPlant = n.startsWith("blackstone") || n.startsWith("brimstone") || n.contains("ender") || n.equals("endbloom")
                    || n.contains("glowshroom") || n.equals("toadstool") || n.equals("burning_blossom") || n.equals("eyebulb")
                    || n.equals("dead_grass") || n.contains("null");
        } else if (def.kind == Kind.FALLING || n.equals("mossy_black_sand")) {
            t.material = Material.SAND;
            t.sound = SoundType.SAND;
            t.hardness = 0.5F;
            t.resistance = 2.5F;
            t.tool = "shovel";
        } else if (n.contains("grass_block") || n.contains("moss_block")) {
            t.material = Material.GRASS;
            t.sound = SoundType.PLANT;
            t.hardness = 0.6F;
            t.resistance = 3.0F;
            t.tool = "shovel";
        } else if (n.contains("flesh")) {
            t.material = Material.GROUND;
            t.sound = SoundType.SLIME;
            t.hardness = 0.4F;
            t.resistance = 2.0F;
            t.tool = null;
        } else if (n.contains("shroom_block") || n.contains("toadstool_block")) {
            t.material = Material.WOOD;
            t.sound = SoundType.WOOD;
            t.hardness = 0.2F;
            t.resistance = 1.0F;
            t.tool = "axe";
        } else if (n.contains("sandstone")) {
            t.hardness = 0.8F;
            t.resistance = 4.0F;
        } else if (n.contains("rose_quartz")) {
            t.sound = SoundType.GLASS;
        }
        if (def.source.vanilla()) {
            vanillaTraits(def, t);
        }
        if (n.contains("glowing_moss") || n.equals("glowflower") || n.contains("glowshroom") || n.equals("glowworm_silk")) {
            t.light = 9;
        } else if (n.contains("rose_quartz") && !n.endsWith("stairs") && !n.endsWith("slab")) {
            t.light = 8;
        } else if (n.equals("burning_blossom") || n.contains("brimstone_bud")) {
            t.light = 6;
        }
        return t;
    }

    /** How Minecraft's own new blocks handle; the game keeps this in code. */
    private static void vanillaTraits(BlockDef def, ModernBlocks.Traits t) {
        String n = def.name;
        boolean cut = def.kind == Kind.STAIRS || def.kind == Kind.SLAB || def.kind == Kind.WALL;
        if (n.contains("deepslate")) {
            t.hardness = n.equals("deepslate") ? 3.0F : 3.5F;
            t.resistance = 10.0F;
        } else if (n.contains("tuff")) {
            t.hardness = 1.5F;
            t.resistance = 10.0F;
        } else if (n.equals("calcite")) {
            t.hardness = 0.75F;
            t.resistance = 1.25F;
        } else if (n.equals("dripstone_block") || n.equals("pointed_dripstone")) {
            t.hardness = 1.5F;
            t.resistance = 2.0F;
        } else if (n.equals("smooth_basalt")) {
            t.hardness = 1.25F;
            t.resistance = 7.0F;
        } else if (n.contains("amethyst")) {
            t.hardness = 1.5F;
            t.resistance = 2.5F;
            t.sound = SoundType.GLASS;
        } else if (n.equals("mud") || n.equals("rooted_dirt") || n.equals("muddy_mangrove_roots")) {
            t.material = Material.GROUND;
            t.sound = SoundType.GROUND;
            t.hardness = n.equals("muddy_mangrove_roots") ? 0.7F : 0.5F;
            t.resistance = 1.0F;
            t.tool = "shovel";
        } else if (n.equals("packed_mud") || n.startsWith("mud_brick")) {
            t.hardness = n.equals("packed_mud") ? 1.0F : 1.5F;
            t.resistance = 5.0F;
        } else if (n.endsWith("moss_block")) {
            t.material = Material.GRASS;
            t.sound = SoundType.PLANT;
            t.hardness = 0.1F;
            t.resistance = 0.2F;
            t.tool = null;
        } else if (n.equals("blue_ice")) {
            t.material = Material.PACKED_ICE;
            t.sound = SoundType.GLASS;
            t.hardness = 2.8F;
            t.resistance = 4.5F;
            t.slipperiness = 0.989F;
        } else if (n.equals("powder_snow")) {
            t.material = Material.CRAFTED_SNOW;
            t.sound = SoundType.SNOW;
            t.hardness = 0.25F;
            t.resistance = 0.4F;
            t.tool = "shovel";
        } else if (n.equals("mangrove_roots")) {
            t.hardness = 0.7F;
            t.resistance = 1.2F;
        } else if (n.equals("bamboo")) {
            t.material = Material.WOOD;
            t.sound = SoundType.WOOD;
            t.hardness = 1.0F;
            t.resistance = 1.0F;
            t.tool = "axe";
        }
        if (def.kind == Kind.PLANT || def.kind == Kind.TALL_PLANT || def.kind == Kind.PETALS || def.kind == Kind.SAPLING) {
            t.sandPlant = n.endsWith("dry_grass") || n.equals("cactus_flower") || n.equals("bush");
            // Moss, mud and clay are soil to the modern game.
            t.rockPlant = false;
            t.litter = n.equals("leaf_litter");
        }
        if (n.equals("small_amethyst_bud")) {
            t.light = 1;
        } else if (n.equals("medium_amethyst_bud")) {
            t.light = 2;
        } else if (n.equals("large_amethyst_bud")) {
            t.light = 4;
        } else if (n.equals("amethyst_cluster")) {
            t.light = 5;
        } else if (n.endsWith("_coral_block")) {
            t.hardness = 1.5F;
            t.resistance = 10.0F;
        }
        if (n.equals("glow_lichen")) {
            t.light = 7;
        } else if (n.equals("firefly_bush")) {
            t.light = 2;
        }
        if (cut && t.hardness < 1.5F) {
            t.hardness = 1.5F;
        }
    }

    @SubscribeEvent
    public static void registerBlocks(RegistryEvent.Register<Block> event) {
        for (final Source source : ALL) {
            if (source.jar == null) {
                continue;
            }
            source.tab = new CreativeTabs("better112_" + source.prefix) {
                @Override
                @SideOnly(Side.CLIENT)
                public ItemStack createIcon() {
                    Item icon = ITEMS.get(source.key(source.vanilla() ? "cherry_leaves" : "origin_grass_block"));
                    if (icon == null) {
                        for (Map.Entry<String, Item> entry : ITEMS.entrySet()) {
                            if (entry.getKey().startsWith(source.prefix + "_")) {
                                icon = entry.getValue();
                                break;
                            }
                        }
                    }
                    return new ItemStack(icon == null ? Items.WHEAT_SEEDS : icon);
                }
            };
        }
        // Full blocks first: stairs take their feel from the block they are cut from.
        for (int pass = 0; pass < 2; pass++) {
            for (BlockDef def : DEFS) {
                if ((def.kind == Kind.STAIRS || def.kind == Kind.WALL) != (pass == 1)) {
                    continue;
                }
                ModernBlocks.Traits t = traits(def);
                TRAITS.put(def.key, t);
                Block block = create(def, t);
                block.setRegistryName(new ResourceLocation(ContentPack.DOMAIN, def.key));
                block.setTranslationKey(ContentPack.DOMAIN + "." + def.key);
                block.setCreativeTab(def.source.tab);
                if (t.slipperiness > 0.0F) {
                    block.setDefaultSlipperiness(t.slipperiness);
                }
                BLOCKS.put(def.key, block);
                event.getRegistry().register(block);
            }
        }
    }

    private static Block create(BlockDef def, ModernBlocks.Traits t) {
        switch (def.kind) {
            case FALLING:
                return new ModernBlocks.Falling(t);
            case PILLAR:
                return new ModernBlocks.Pillar(t);
            case LEAVES:
                return new ModernBlocks.Leaves();
            case PLANT:
                return new ModernBlocks.Plant(t, !def.name.endsWith("azalea"));
            case SAPLING:
                return new ModernBlocks.Sapling(def.key, t);
            case HANGING:
                return new ModernBlocks.Hanging(t, def.name.startsWith("cave_vines") ? "cave_vines" : def.name);
            case BAMBOO:
                return new ModernBlocks.Bamboo(t);
            case DRIPSTONE:
                return new ModernBlocks.Dripstone(t);
            case LICHEN:
                return new ModernBlocks.Lichen(t);
            case AQUATIC:
                return new ModernBlocks.Aquatic(t, def.name.startsWith("kelp") ? "kelp" : null);
            case AQUATIC_TALL:
                return new ModernBlocks.AquaticTall(t);
            case AQUATIC_WALL:
                return new ModernBlocks.AquaticWall(t);
            case PICKLE:
                return new ModernBlocks.Pickle(t);
            case BUD:
                return new ModernBlocks.Bud(t);
            case FLAT:
                return new ModernBlocks.Flat(t);
            case LILY:
                return new ModernBlocks.Lily();
            case VINE:
                return new ModernBlocks.Vine();
            case PETALS:
                return t.litter ? new ModernBlocks.Litter(t) : new ModernBlocks.Petals(t);
            case WALL: {
                String base = def.name.substring(0, def.name.length() - "_wall".length());
                Block from = BLOCKS.get(def.source.key(base));
                if (from == null) {
                    from = BLOCKS.get(def.source.key(base + "s"));
                }
                return new ModernBlocks.Wall(from != null ? from : Blocks.COBBLESTONE, t);
            }
            case SIGN:
                return new ModernBlocks.StandingSign(def.source.prefix + "/signs/" + def.name.substring(0, def.name.length() - "_sign".length()));
            case WALL_SIGN:
                return new ModernBlocks.WallSign(def.source.prefix + "/signs/" + def.name.substring(0, def.name.length() - "_wall_sign".length()));
            case TALL_PLANT:
                return new ModernBlocks.TallPlant(t);
            case STAIRS: {
                // "fir_stairs" is cut from "fir_planks", "brimstone_brick_stairs" from "brimstone_bricks".
                String base = def.name.substring(0, def.name.length() - "_stairs".length());
                Block from = BLOCKS.get(def.source.key(base + "_planks"));
                for (String candidate : new String[] {base, base + "s"}) {
                    if (from == null) {
                        from = BLOCKS.get(def.source.key(candidate));
                    }
                }
                IBlockState state = from != null ? from.getDefaultState() : t.material == Material.WOOD ? Blocks.PLANKS.getDefaultState() : Blocks.STONE.getDefaultState();
                return new ModernBlocks.Stairs(state, t);
            }
            case SLAB:
                return new ModernBlocks.Slab(t);
            case FENCE:
                return new ModernBlocks.Fence(t);
            case FENCE_GATE:
                return new ModernBlocks.FenceGate(t);
            case DOOR:
                return new ModernBlocks.Door(t);
            case TRAPDOOR:
                return new ModernBlocks.TrapDoor(t);
            case BUTTON:
                return new ModernBlocks.Button(t);
            case PLATE:
                return new ModernBlocks.Plate(t);
            default:
                if (def.source.vanilla() && def.name.equals("budding_amethyst")) {
                    return new ModernBlocks.Budding(t);
                }
                return def.cutout ? new ModernBlocks.CutoutCube(t) : new ModernBlocks.Cube(t);
        }
    }

    @SubscribeEvent
    public static void registerItems(RegistryEvent.Register<Item> event) {
        for (BlockDef def : DEFS) {
            final Block block = BLOCKS.get(def.key);
            if (block == null) {
                continue;
            }
            Item item;
            if (!hasItem(def)) {
                continue; // the standing sign's item places both; kelp's stalk has no item of its own
            }
            if (def.kind == Kind.SIGN) {
                Block wall = BLOCKS.get(def.key.replace("_sign", "_wall_sign"));
                item = new ModernBlocks.SignItem(block, wall);
                item.setTranslationKey(ContentPack.DOMAIN + "." + def.key);
                item.setCreativeTab(def.source.tab);
                ((ModernBlocks.StandingSign) block).item = item;
                if (wall instanceof ModernBlocks.WallSign) {
                    ((ModernBlocks.WallSign) wall).item = item;
                }
            } else if (def.kind == Kind.LILY) {
                item = new net.minecraft.item.ItemLilyPad(block);
            } else if (def.kind == Kind.DOOR) {
                item = new ItemDoor(block);
                item.setTranslationKey(ContentPack.DOMAIN + "." + def.key);
                item.setCreativeTab(def.source.tab);
                ((ModernBlocks.Door) block).item = item;
            } else if (def.kind == Kind.SLAB) {
                item = new SlabItem(block);
            } else if (def.kind == Kind.LEAVES) {
                // A leaf block set down by hand must not wither away: that is the "4" bit, as on vanilla leaves.
                item = new ItemBlock(block) {
                    @Override
                    public int getMetadata(int damage) {
                        return 4;
                    }
                };
            } else {
                item = new ItemBlock(block);
            }
            item.setRegistryName(block.getRegistryName());
            ITEMS.put(def.key, item);
            event.getRegistry().register(item);
        }
        for (String name : PLAIN) {
            Item item = new Item();
            item.setRegistryName(new ResourceLocation(ContentPack.DOMAIN, MC.key(name)));
            item.setTranslationKey(ContentPack.DOMAIN + "." + MC.key(name));
            item.setCreativeTab(MC.tab);
            ITEMS.put(MC.key(name), item);
            event.getRegistry().register(item);
        }
        // What some blocks break into, and what budding amethyst grows.
        Block cluster = BLOCKS.get("mc_amethyst_cluster"), budding = BLOCKS.get("mc_budding_amethyst");
        if (cluster instanceof ModernBlocks.Bud) {
            ((ModernBlocks.Bud) cluster).shards = ITEMS.get("mc_amethyst_shard");
        }
        if (budding instanceof ModernBlocks.Budding) {
            Block[] stages = {BLOCKS.get("mc_small_amethyst_bud"), BLOCKS.get("mc_medium_amethyst_bud"), BLOCKS.get("mc_large_amethyst_bud"), cluster};
            boolean whole = true;
            for (Block stage : stages) {
                whole &= stage instanceof ModernBlocks.Bud;
            }
            if (whole) {
                ((ModernBlocks.Budding) budding).stages = stages;
            }
        }
        if (BLOCKS.get("mc_kelp_plant") instanceof ModernBlocks.Aquatic) {
            ((ModernBlocks.Aquatic) BLOCKS.get("mc_kelp_plant")).dropAs = ITEMS.get("mc_kelp");
        }
        for (BlockDef def : DEFS) {
            if (def.kind == Kind.AQUATIC_WALL) {
                ((ModernBlocks.Aquatic) BLOCKS.get(def.key)).dropAs = ITEMS.get(def.key.replace("_wall_fan", "_fan"));
            }
        }
        for (BlockDef def : DEFS) {
            if (def.kind == Kind.LEAVES) {
                // What falls from the leaves: the sapling of the same name, or what the modern game grows that tree from.
                String sapling = def.name.equals("mangrove_leaves") ? "mangrove_propagule"
                        : def.name.endsWith("azalea_leaves") ? def.name.replace("_leaves", "") : def.name.replace("_leaves", "_sapling");
                ((ModernBlocks.Leaves) BLOCKS.get(def.key)).sapling = ITEMS.get(def.source.key(sapling));
            }
        }
    }

    /**
     * An axe used on an imported log or piece of wood strips its bark, as in the modern game.
     * (Only the imported woods have stripped blocks to turn into; 1.12.2's own logs are left alone.)
     */
    @SubscribeEvent
    public static void stripBark(net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickBlock event) {
        ItemStack held = event.getItemStack();
        if (held.isEmpty() || !held.getItem().getToolClasses(held).contains("axe")) {
            return;
        }
        net.minecraft.world.World world = event.getWorld();
        net.minecraft.util.math.BlockPos pos = event.getPos();
        IBlockState state = world.getBlockState(pos);
        ResourceLocation id = state.getBlock().getRegistryName();
        if (id == null || !id.getNamespace().equals(ContentPack.DOMAIN) || !(state.getBlock() instanceof ModernBlocks.Pillar)) {
            return;
        }
        // "bop_fir_log" becomes "bop_stripped_fir_log".
        int cut = id.getPath().indexOf('_');
        Block bare = cut < 0 || id.getPath().startsWith("stripped_", cut + 1) ? null
                : BLOCKS.get(id.getPath().substring(0, cut + 1) + "stripped_" + id.getPath().substring(cut + 1));
        if (!(bare instanceof ModernBlocks.Pillar)) {
            return;
        }
        if (!world.isRemote) {
            world.setBlockState(pos, bare.getDefaultState().withProperty(net.minecraft.block.BlockRotatedPillar.AXIS,
                    state.getValue(net.minecraft.block.BlockRotatedPillar.AXIS)), 11);
            held.damageItem(1, event.getEntityPlayer());
        }
        world.playSound(event.getEntityPlayer(), pos, net.minecraft.init.SoundEvents.BLOCK_WOOD_HIT, net.minecraft.util.SoundCategory.BLOCKS, 1.0F, 0.9F);
        event.getEntityPlayer().swingArm(event.getHand());
        event.setCancellationResult(net.minecraft.util.EnumActionResult.SUCCESS);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void registerRecipes(RegistryEvent.Register<net.minecraft.item.crafting.IRecipe> event) {
        ContentRecipes.register(event);
        ContentRecipes.yieldVanilla(event);
    }

    /** After registration: what other mods and the game should know about the blocks. */
    public static void init() {
        if (!DEFS.isEmpty()) {
            ContentRecipes.smelt();
            net.minecraftforge.fml.common.registry.GameRegistry.registerTileEntity(ModernBlocks.SignTile.class,
                    new ResourceLocation(ContentPack.DOMAIN, "bop_sign"));
        }
        for (BlockDef def : DEFS) {
            Block block = BLOCKS.get(def.key);
            Item item = ITEMS.get(def.key);
            if (block == null || item == null) {
                continue;
            }
            ItemStack stack = new ItemStack(item);
            String n = def.name;
            if (def.wood != null) {
                switch (def.kind) {
                    case CUBE:
                        if (n.endsWith("_planks")) {
                            OreDictionary.registerOre("plankWood", stack);
                        }
                        Blocks.FIRE.setFireInfo(block, 5, 20);
                        break;
                    case PILLAR:
                        if (!n.contains("bamboo")) {
                            OreDictionary.registerOre("logWood", stack); // a block of bamboo is not a log to the modern game either
                        }
                        Blocks.FIRE.setFireInfo(block, 5, 5);
                        break;
                    case STAIRS:
                        OreDictionary.registerOre("stairWood", stack);
                        Blocks.FIRE.setFireInfo(block, 5, 20);
                        break;
                    case SLAB:
                        OreDictionary.registerOre("slabWood", stack);
                        Blocks.FIRE.setFireInfo(block, 5, 20);
                        break;
                    case FENCE:
                        OreDictionary.registerOre("fenceWood", stack);
                        Blocks.FIRE.setFireInfo(block, 5, 20);
                        break;
                    case FENCE_GATE:
                        OreDictionary.registerOre("fenceGateWood", stack);
                        Blocks.FIRE.setFireInfo(block, 5, 20);
                        break;
                    case DOOR:
                        OreDictionary.registerOre("doorWood", stack);
                        break;
                    case TRAPDOOR:
                        OreDictionary.registerOre("trapdoorWood", stack);
                        break;
                    default:
                        break;
                }
            }
            if (def.kind == Kind.LEAVES) {
                OreDictionary.registerOre("treeLeaves", stack);
                Blocks.FIRE.setFireInfo(block, 30, 60);
            } else if (def.kind == Kind.SAPLING) {
                OreDictionary.registerOre("treeSapling", stack);
            } else if (def.kind == Kind.PLANT || def.kind == Kind.TALL_PLANT) {
                Blocks.FIRE.setFireInfo(block, 60, 100);
            } else if (def.kind == Kind.FALLING) {
                OreDictionary.registerOre("sand", stack);
            } else if (def.kind == Kind.CUBE && n.endsWith("sandstone")) {
                OreDictionary.registerOre("sandstone", stack);
            }
        }
    }

    public static String modNamespace() {
        return BOP.namespace;
    }

    /** What the worldgen side may use of the imported blocks (see better112.world.feature.Imported). */
    public static final better112.world.feature.Imported.Lookup LOOKUP = new better112.world.feature.Imported.Lookup() {
        @Override
        public Block block(String modernId) {
            return ModernContent.block(modernId);
        }

        @Override
        public boolean isTallPlant(Block block) {
            return ModernContent.isTallPlant(block);
        }

        @Override
        public boolean placeTall(net.minecraft.world.World world, net.minecraft.util.math.BlockPos pos, Block block, int flags) {
            return ModernContent.placeTall(world, pos, block, flags);
        }

        @Override
        public boolean isAquatic(Block block) {
            return ModernContent.isAquatic(block);
        }

        @Override
        public boolean placeAquatic(net.minecraft.world.World world, net.minecraft.util.math.BlockPos pos, IBlockState state, int flags) {
            return ModernContent.placeAquatic(world, pos, state, flags);
        }

        @Override
        public File sourceJar() {
            return ModernContent.sourceJar();
        }
    };

    /** Self-test: the imported blocks of one source ("bop", "mc"), in the order of its files. */
    public static List<Block> showcaseBlocks(String prefix) {
        List<Block> blocks = new ArrayList<Block>();
        for (Map.Entry<String, Block> entry : BLOCKS.entrySet()) {
            if (entry.getKey().startsWith(prefix + "_")) {
                blocks.add(entry.getValue());
            }
        }
        return blocks;
    }

    /** True for a block that is a water plant (and so counts as water). */
    public static boolean isAquatic(Block block) {
        return block instanceof ModernBlocks.Aquatic;
    }

    /** Sets a water plant into still water if it could stand there; tall ones with both halves. */
    public static boolean placeAquatic(net.minecraft.world.World world, net.minecraft.util.math.BlockPos pos, IBlockState state, int flags) {
        if (!(state.getBlock() instanceof ModernBlocks.Aquatic) || !state.getBlock().canPlaceBlockAt(world, pos)) {
            return false;
        }
        if (state.getBlock() instanceof ModernBlocks.AquaticTall) {
            ((ModernBlocks.AquaticTall) state.getBlock()).placeAt(world, pos, flags);
        } else {
            world.setBlockState(pos, state, flags);
        }
        return true;
    }

    public static boolean isTallPlant(Block block) {
        return block instanceof ModernBlocks.TallPlant;
    }

    /** Both halves of a tall plant, if there is room and footing. */
    public static boolean placeTall(net.minecraft.world.World world, net.minecraft.util.math.BlockPos pos, Block block, int flags) {
        if (!(block instanceof ModernBlocks.TallPlant) || !world.isAirBlock(pos) || !world.isAirBlock(pos.up()) || !block.canPlaceBlockAt(world, pos)) {
            return false;
        }
        ((ModernBlocks.TallPlant) block).placeAt(world, pos, flags);
        return true;
    }

    /** Self-test: sets a block down the way a player would, tall plants and doors with both halves. */
    public static void placeForShow(net.minecraft.world.World world, net.minecraft.util.math.BlockPos pos, Block block) {
        if (block instanceof ModernBlocks.TallPlant) {
            ((ModernBlocks.TallPlant) block).placeAt(world, pos, 2);
        } else if (block instanceof ModernBlocks.Door) {
            ItemDoor.placeDoor(world, pos, net.minecraft.util.EnumFacing.NORTH, block, false);
        } else if (block instanceof ModernBlocks.Leaves) {
            world.setBlockState(pos, block.getStateFromMeta(4), 2);
        } else if (block instanceof ModernBlocks.Lily) {
            world.setBlockState(pos.down(), Blocks.WATER.getDefaultState(), 2);
            world.setBlockState(pos, block.getDefaultState(), 2);
        } else if (block instanceof ModernBlocks.Vine) {
            world.setBlockState(pos.north(), Blocks.STONE.getDefaultState(), 2);
            world.setBlockState(pos, block.getDefaultState().withProperty(net.minecraft.block.BlockVine.NORTH, Boolean.TRUE), 2);
        } else if (block instanceof ModernBlocks.WallSign) {
            world.setBlockState(pos.north(), Blocks.STONE.getDefaultState(), 2);
            world.setBlockState(pos, block.getDefaultState().withProperty(net.minecraft.block.BlockWallSign.FACING, net.minecraft.util.EnumFacing.SOUTH), 2);
        } else if (block instanceof ModernBlocks.Aquatic) {
            // In a small glass tank, so the water stays put.
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dy = 0; dy < 3; dy++) {
                        boolean inside = dx == 0 && dz == 0;
                        world.setBlockState(pos.add(dx, dy, dz), inside ? Blocks.WATER.getDefaultState() : Blocks.GLASS.getDefaultState(), 2);
                    }
                }
            }
            if (block instanceof ModernBlocks.AquaticTall) {
                ((ModernBlocks.AquaticTall) block).placeAt(world, pos, 2);
            } else if (block instanceof ModernBlocks.AquaticWall) {
                world.setBlockState(pos, block.getDefaultState().withProperty(ModernBlocks.AquaticWall.FACING, net.minecraft.util.EnumFacing.SOUTH), 2);
            } else {
                world.setBlockState(pos, block.getDefaultState(), 2);
            }
        } else if (block instanceof ModernBlocks.Bud) {
            world.setBlockState(pos, block.getDefaultState(), 2);
        } else if (block instanceof ModernBlocks.Hanging || block instanceof ModernBlocks.Lichen) {
            world.setBlockState(pos.up(), Blocks.STONE.getDefaultState(), 2);
            world.setBlockState(pos, block.getDefaultState(), 2);
        } else if (block instanceof ModernBlocks.Bamboo) {
            world.setBlockState(pos, block.getDefaultState().withProperty(ModernBlocks.Bamboo.AGE, 1), 2);
            world.setBlockState(pos.up(), block.getDefaultState().withProperty(ModernBlocks.Bamboo.AGE, 1)
                    .withProperty(ModernBlocks.Bamboo.LEAVES, ModernBlocks.Bamboo.Leaf.SMALL), 2);
            world.setBlockState(pos.up(2), block.getDefaultState().withProperty(ModernBlocks.Bamboo.AGE, 1)
                    .withProperty(ModernBlocks.Bamboo.LEAVES, ModernBlocks.Bamboo.Leaf.LARGE), 2);
        } else if (block instanceof ModernBlocks.Dripstone) {
            for (int i = 0; i < 3; i++) {
                world.setBlockState(pos.up(i), block.getDefaultState(), 2);
            }
        } else {
            world.setBlockState(pos, block.getDefaultState(), 2);
        }
    }

    /** Places a slab, or makes a whole block out of two halves. */
    private static final class SlabItem extends ItemBlock {
        SlabItem(Block block) {
            super(block);
        }

        @Override
        public net.minecraft.util.EnumActionResult onItemUse(net.minecraft.entity.player.EntityPlayer player, net.minecraft.world.World world,
                net.minecraft.util.math.BlockPos pos, net.minecraft.util.EnumHand hand, net.minecraft.util.EnumFacing facing, float hitX,
                float hitY, float hitZ) {
            ItemStack stack = player.getHeldItem(hand);
            if (!stack.isEmpty() && player.canPlayerEdit(pos.offset(facing), facing, stack)) {
                // Onto the open face of a half slab of the same kind, or into the block next to it.
                for (net.minecraft.util.math.BlockPos at : new net.minecraft.util.math.BlockPos[] {pos, pos.offset(facing)}) {
                    IBlockState there = world.getBlockState(at);
                    if (there.getBlock() != block) {
                        continue;
                    }
                    ModernBlocks.Slab.Type type = there.getValue(ModernBlocks.Slab.TYPE);
                    boolean fits = at != pos || (type == ModernBlocks.Slab.Type.BOTTOM && facing == net.minecraft.util.EnumFacing.UP)
                            || (type == ModernBlocks.Slab.Type.TOP && facing == net.minecraft.util.EnumFacing.DOWN);
                    if (type != ModernBlocks.Slab.Type.DOUBLE && fits) {
                        IBlockState whole = there.withProperty(ModernBlocks.Slab.TYPE, ModernBlocks.Slab.Type.DOUBLE);
                        if (world.checkNoEntityCollision(net.minecraft.block.Block.FULL_BLOCK_AABB.offset(at)) && world.setBlockState(at, whole, 11)) {
                            SoundType sound = block.getSoundType(whole, world, at, player);
                            world.playSound(player, at, sound.getPlaceSound(), net.minecraft.util.SoundCategory.BLOCKS,
                                    (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
                            stack.shrink(1);
                        }
                        return net.minecraft.util.EnumActionResult.SUCCESS;
                    }
                }
            }
            return super.onItemUse(player, world, pos, hand, facing, hitX, hitY, hitZ);
        }
    }
}
