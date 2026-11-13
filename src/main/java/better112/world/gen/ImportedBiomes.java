package better112.world.gen;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import better112.Better112;
import better112.world.feature.Imported;
import better112.world.feature.Features;
import better112.world.feature.States;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.block.BlockDirt;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLiving;
import net.minecraft.init.Blocks;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.common.BiomeDictionary;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

/**
 * Biomes read from modern biome files: the ones Minecraft itself has gained since 1.12.2
 * (meadows, cherry groves, snowy peaks, mangrove swamps...), from the game's own worldgen
 * data, and the overworld biomes of Biomes O' Plenty 1.20, from the mod's jar. A file gives
 * a biome's climate, colours, creatures and the list of features that decorate it. They are
 * only placed in Modern Terrain worlds: the game's own exactly where the modern game puts
 * them, the mod's in the climate slots they suit.
 *
 * What is kept in code rather than data is filled in here: which ground a biome has (a
 * short table below, and Surfaces) and where a mod's biome lies in the world (by likeness
 * to the vanilla biome of each climate slot, see ModernBiomes).
 */
public final class ImportedBiomes {

    private ImportedBiomes() {}

    private static final String MOD = "biomesoplenty";
    /** 1.12.2 stores a biome in one byte per column; a few ids are left for mods that register later. */
    private static final int SPARE_IDS = 6;

    static final List<ModernBiome> BIOMES = new ArrayList<ModernBiome>();
    private static int vanillaCount, modCount;

    /**
     * Minecraft's own biomes that 1.12.2 has no true counterpart for, each with the kinds of
     * place Forge calls it. (Every other modern biome is an old one renamed, and stays the
     * 1.12.2 biome, so that packs' tweaks to those biomes keep working.)
     */
    private static final Object[] VANILLA = {
        "meadow", new BiomeDictionary.Type[] {BiomeDictionary.Type.PLAINS, BiomeDictionary.Type.MOUNTAIN},
        "cherry_grove", new BiomeDictionary.Type[] {BiomeDictionary.Type.FOREST, BiomeDictionary.Type.MOUNTAIN, BiomeDictionary.Type.LUSH},
        "grove", new BiomeDictionary.Type[] {BiomeDictionary.Type.COLD, BiomeDictionary.Type.CONIFEROUS, BiomeDictionary.Type.FOREST,
            BiomeDictionary.Type.SNOWY, BiomeDictionary.Type.MOUNTAIN},
        "snowy_slopes", new BiomeDictionary.Type[] {BiomeDictionary.Type.COLD, BiomeDictionary.Type.SPARSE, BiomeDictionary.Type.SNOWY,
            BiomeDictionary.Type.MOUNTAIN},
        "frozen_peaks", new BiomeDictionary.Type[] {BiomeDictionary.Type.COLD, BiomeDictionary.Type.SPARSE, BiomeDictionary.Type.SNOWY,
            BiomeDictionary.Type.MOUNTAIN},
        "jagged_peaks", new BiomeDictionary.Type[] {BiomeDictionary.Type.COLD, BiomeDictionary.Type.SPARSE, BiomeDictionary.Type.SNOWY,
            BiomeDictionary.Type.MOUNTAIN},
        "stony_peaks", new BiomeDictionary.Type[] {BiomeDictionary.Type.HOT, BiomeDictionary.Type.SPARSE, BiomeDictionary.Type.MOUNTAIN},
        "mangrove_swamp", new BiomeDictionary.Type[] {BiomeDictionary.Type.WET, BiomeDictionary.Type.HOT, BiomeDictionary.Type.SWAMP},
        "bamboo_jungle", new BiomeDictionary.Type[] {BiomeDictionary.Type.HOT, BiomeDictionary.Type.WET, BiomeDictionary.Type.JUNGLE,
            BiomeDictionary.Type.RARE},
        "pale_garden", new BiomeDictionary.Type[] {BiomeDictionary.Type.FOREST, BiomeDictionary.Type.DENSE, BiomeDictionary.Type.SPOOKY},
        "warm_ocean", new BiomeDictionary.Type[] {BiomeDictionary.Type.OCEAN, BiomeDictionary.Type.HOT},
        "lukewarm_ocean", new BiomeDictionary.Type[] {BiomeDictionary.Type.OCEAN},
        "deep_lukewarm_ocean", new BiomeDictionary.Type[] {BiomeDictionary.Type.OCEAN},
        "cold_ocean", new BiomeDictionary.Type[] {BiomeDictionary.Type.OCEAN, BiomeDictionary.Type.COLD},
        "deep_cold_ocean", new BiomeDictionary.Type[] {BiomeDictionary.Type.OCEAN, BiomeDictionary.Type.COLD},
        "frozen_ocean", new BiomeDictionary.Type[] {BiomeDictionary.Type.OCEAN, BiomeDictionary.Type.COLD, BiomeDictionary.Type.SNOWY},
        "deep_frozen_ocean", new BiomeDictionary.Type[] {BiomeDictionary.Type.OCEAN, BiomeDictionary.Type.COLD, BiomeDictionary.Type.SNOWY},
    };
    private static Features features;
    private static boolean featuresTried;
    private static String status = "";

    public static String status() {
        return status;
    }

    public static int count() {
        return BIOMES.size();
    }

    /** Biome tags of the modern world and the 1.12.2 biome type each one means. */
    private static final Object[] TYPES = {
        "forge:is_hot/overworld", BiomeDictionary.Type.HOT, "forge:is_cold/overworld", BiomeDictionary.Type.COLD,
        "forge:is_dense/overworld", BiomeDictionary.Type.DENSE, "forge:is_sparse/overworld", BiomeDictionary.Type.SPARSE,
        "forge:is_wet/overworld", BiomeDictionary.Type.WET, "forge:is_dry/overworld", BiomeDictionary.Type.DRY,
        "forge:is_coniferous", BiomeDictionary.Type.CONIFEROUS, "forge:is_desert", BiomeDictionary.Type.SANDY,
        "forge:is_lush", BiomeDictionary.Type.LUSH, "forge:is_magical", BiomeDictionary.Type.MAGICAL,
        "forge:is_mountain", BiomeDictionary.Type.MOUNTAIN, "forge:is_mushroom", BiomeDictionary.Type.MUSHROOM,
        "forge:is_plains", BiomeDictionary.Type.PLAINS, "forge:is_plateau", BiomeDictionary.Type.HILLS,
        "forge:is_rare", BiomeDictionary.Type.RARE, "forge:is_sandy", BiomeDictionary.Type.SANDY,
        "forge:is_snowy", BiomeDictionary.Type.SNOWY, "forge:is_spooky", BiomeDictionary.Type.SPOOKY,
        "forge:is_swamp", BiomeDictionary.Type.SWAMP, "forge:is_wasteland", BiomeDictionary.Type.WASTELAND,
        "minecraft:is_beach", BiomeDictionary.Type.BEACH, "minecraft:is_forest", BiomeDictionary.Type.FOREST,
        "minecraft:is_hill", BiomeDictionary.Type.HILLS, "minecraft:is_jungle", BiomeDictionary.Type.JUNGLE,
        "minecraft:is_mountain", BiomeDictionary.Type.MOUNTAIN, "minecraft:is_savanna", BiomeDictionary.Type.SAVANNA,
        "minecraft:is_taiga", BiomeDictionary.Type.CONIFEROUS,
    };

    /** A biome made from a modern biome file. */
    public static final class ModernBiome extends Biome {
        final String modernName;
        /** True for one of Minecraft's own biomes, false for a mod's. */
        final boolean vanilla;
        /** How the ground is laid where it is not plain grass over dirt; null for the usual. */
        Surfaces.Kind ground;
        /** A frozen sea whose water ices over in patches (its deep kind has icebergs but open water). */
        boolean icePatches;
        private final int grassColor, foliageColor, skyColor;
        private final List<String> featureIds = new ArrayList<String>();
        private List<Features.Placed> placed;
        /** A feature's "only in this biome" step, answered for this biome. */
        private final Features.Where here = new Features.Where() {
            @Override
            public boolean here(World world, BlockPos pos) {
                return world.getBiome(pos) == ModernBiome.this;
            }
        };

        ModernBiome(String modernName, boolean vanilla, BiomeProperties properties, JsonObject json) {
            super(properties);
            this.modernName = modernName;
            this.vanilla = vanilla;
            JsonObject effects = json.has("effects") ? json.getAsJsonObject("effects") : new JsonObject();
            // A swamp's grass has a colour of its own, which the file gives as a "modifier" rather than a number.
            this.grassColor = effects.has("grass_color") ? effects.get("grass_color").getAsInt()
                    : effects.has("grass_color_modifier") && "swamp".equals(effects.get("grass_color_modifier").getAsString()) ? 0x6A7039 : -1;
            this.foliageColor = effects.has("foliage_color") ? effects.get("foliage_color").getAsInt() : -1;
            this.skyColor = effects.has("sky_color") ? effects.get("sky_color").getAsInt() : -1;
            // The old decorator still lays ores, dirt and gravel pockets, sand and clay by water, and springs,
            // and fires the events packs hook into. Its own trees, flowers and grass are off: the modern features do those.
            this.decorator.treesPerChunk = -999;
            this.decorator.flowersPerChunk = 0;
            this.decorator.grassPerChunk = 0;
            this.decorator.mushroomsPerChunk = 0;
            if (json.has("features")) {
                JsonArray steps = json.getAsJsonArray("features");
                // Step 2 is "local modifications" (boulders and the like), step 9 is vegetation. Step 4 holds the blue ice
                // of frozen seas, and step 6 is ores, which the old decorator lays, but the game also files a swamp's
                // patches of grass there. Glow lichen and geodes are left to the generator, which makes them under
                // every biome of the world, not only these.
                for (int step : new int[] {2, 4, 6, 9}) {
                    if (step < steps.size()) {
                        for (JsonElement id : steps.get(step).getAsJsonArray()) {
                            String name = id.isJsonPrimitive() ? id.getAsString() : "";
                            boolean wanted = step == 4 ? name.endsWith("blue_ice") : step == 6 ? name.endsWith("disk_grass")
                                    : !name.endsWith("glow_lichen") && !name.endsWith("amethyst_geode");
                            if (!name.isEmpty() && wanted) {
                                featureIds.add(name);
                            }
                        }
                    }
                }
            }
            if (json.has("spawners")) {
                JsonObject spawners = json.getAsJsonObject("spawners");
                spawns(spawners, "creature", this.spawnableCreatureList);
                spawns(spawners, "monster", this.spawnableMonsterList);
                spawns(spawners, "ambient", this.spawnableCaveCreatureList);
                spawns(spawners, "water_creature", this.spawnableWaterCreatureList);
            }
        }

        @SuppressWarnings("unchecked")
        private static void spawns(JsonObject spawners, String group, List<SpawnListEntry> list) {
            if (!spawners.has(group)) {
                return;
            }
            List<SpawnListEntry> found = new ArrayList<SpawnListEntry>();
            for (JsonElement entry : spawners.getAsJsonArray(group)) {
                JsonObject e = entry.getAsJsonObject();
                Class<? extends Entity> type = EntityList.getClass(new ResourceLocation(e.get("type").getAsString()));
                if (type != null && EntityLiving.class.isAssignableFrom(type)) {
                    found.add(new SpawnListEntry((Class<? extends EntityLiving>) type, e.get("weight").getAsInt(), e.get("minCount").getAsInt(),
                            e.get("maxCount").getAsInt()));
                }
            }
            // A list naming only creatures this game lacks leaves the stock list in place.
            if (!found.isEmpty()) {
                list.clear();
                list.addAll(found);
            }
        }

        @Override
        public void genTerrainBlocks(World world, Random random, net.minecraft.world.chunk.ChunkPrimer primer, int x, int z, double noiseVal) {
            if (ground == null) {
                super.genTerrainBlocks(world, random, primer, x, z, noiseVal);
            } else {
                Surfaces.lay(ground, this, world, random, primer, x, z, noiseVal);
            }
        }

        @Override
        public int getGrassColorAtPos(BlockPos pos) {
            return grassColor >= 0 ? grassColor : super.getGrassColorAtPos(pos);
        }

        @Override
        public int getFoliageColorAtPos(BlockPos pos) {
            return foliageColor >= 0 ? foliageColor : super.getFoliageColorAtPos(pos);
        }

        @Override
        public int getSkyColorByTemp(float currentTemperature) {
            return skyColor >= 0 ? skyColor : super.getSkyColorByTemp(currentTemperature);
        }

        @Override
        public void decorate(World world, Random random, BlockPos pos) {
            super.decorate(world, random, pos);
            Features reader = features();
            if (reader == null) {
                return;
            }
            if (placed == null) {
                placed = new ArrayList<Features.Placed>();
                for (String id : featureIds) {
                    Features.Placed feature = reader.placed(new com.google.gson.JsonPrimitive(id));
                    if (feature != null && feature.usable()) {
                        placed.add(feature);
                    }
                }
            }
            // Decoration works on the block of 16x16 that straddles four chunks, as all 1.12.2 decoration does.
            BlockPos origin = pos.add(8, 0, 8);
            long start = System.nanoTime();
            for (Features.Placed feature : placed) {
                try {
                    feature.place(world, random, origin, here);
                } catch (RuntimeException e) {
                    Better112.LOG.debug("Feature failed in {}: {}", modernName, e.toString());
                }
            }
            decorateNanos += System.nanoTime() - start;
            decoratedChunks++;
        }
    }

    /** The feature reader over the modern game's worldgen data plus the mod's, or null if either is missing. */
    static synchronized Features features() {
        if (!featuresTried) {
            featuresTried = true;
            try {
                File jar = Imported.sourceJar();
                better112.world.density.WorldData data = ModernWorld.dataWith(jar);
                if (data != null) {
                    features = new Features(data);
                }
            } catch (Throwable t) {
                Better112.LOG.warn("Biome features are unavailable", t);
            }
        }
        return features;
    }

    /** Time spent on the modern features of imported biomes, for the self-test. */
    public static long decorateNanos;
    public static int decoratedChunks;

    private static Map<String, String> saplingTrees;

    /**
     * Grows the tree of a sapling, named as the block is here ("bop_fir_sapling",
     * "mc_cherry_sapling"). Which tree a sapling makes is in code; the data gives it away all
     * the same: the tree named after the sapling's wood.
     */
    public static boolean growSapling(String sapling, World world, BlockPos pos, Random random) {
        Features reader = features();
        if (reader == null) {
            return false;
        }
        if (saplingTrees == null) {
            saplingTrees = new HashMap<String, String>();
        }
        String id = saplingTrees.get(sapling);
        if (id == null) {
            id = "";
            String[] candidates;
            if (sapling.startsWith("mc_")) {
                // Minecraft's own: the tree is named after the wood.
                String wood = sapling.substring(3).replace("_sapling", "").replace("_propagule", "");
                candidates = new String[] {"minecraft:" + wood + "_bonemeal", "minecraft:" + wood};
            } else {
                String wood = sapling.substring(sapling.indexOf('_') + 1, sapling.length() - "_sapling".length());
                candidates = new String[] {MOD + ":" + wood + "_tree", MOD + ":small_" + wood + "_tree", MOD + ":" + wood + "_twiglet_tree"};
            }
            for (String candidate : candidates) {
                if (reader.has(candidate)) {
                    id = candidate;
                    break;
                }
            }
            saplingTrees.put(sapling, id);
        }
        return !id.isEmpty() && reader.generate(id, world, random, pos);
    }

    /** Self-test: where the world's climate puts a cave biome ("lush" or "dripstone") underground, or null. */
    public static BlockPos findCave(World world, String which) {
        return world.getBiomeProvider() instanceof ModernBiomeProvider ? CaveDecor.find(((ModernBiomeProvider) world.getBiomeProvider()).terrain, which) : null;
    }

    /** Self-test: how much cave, geode and sea-floor decoration ran and what it cost. */
    public static String caveReport() {
        return String.format("%d chunks, %.2f ms each, %d with lush caves, %d with dripstone caves, %d geodes, %d with sea plants", CaveDecor.chunks,
                CaveDecor.chunks == 0 ? 0.0D : CaveDecor.nanos / 1.0E6D / CaveDecor.chunks, CaveDecor.lushChunks, CaveDecor.dripstoneChunks,
                CaveDecor.geodes, CaveDecor.seaChunks);
    }

    /** What the feature reader met and could not do; for the log and the self-test. */
    public static Set<String> unknownFeatures() {
        return features == null ? new TreeSet<String>() : features.unknown();
    }

    // ------------------------------------------------------------------------------------

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void registerBiomes(RegistryEvent.Register<Biome> event) {
        List<String> notes = new ArrayList<String>();
        int[] skipped = new int[1];
        try {
            registerVanilla(event, skipped);
        } catch (Throwable t) {
            notes.add("could not read Minecraft's biomes: " + t);
            Better112.LOG.warn("Modern Minecraft biomes are unavailable", t);
        }
        try {
            registerMod(event, skipped);
        } catch (Throwable t) {
            notes.add("could not read the Biomes O' Plenty biomes: " + t);
            Better112.LOG.warn("Biomes O' Plenty biomes are unavailable", t);
        }
        StringBuilder text = new StringBuilder();
        if (vanillaCount > 0) {
            text.append(vanillaCount).append(" modern Minecraft biomes");
        }
        if (modCount > 0) {
            text.append(text.length() == 0 ? "" : ", ").append(modCount).append(" biomes from ").append(Imported.sourceJar().getName());
        }
        if (skipped[0] > 0) {
            text.append(" (").append(skipped[0]).append(" left out: this pack has almost used up the 256 biome ids 1.12.2 allows)");
        }
        for (String note : notes) {
            text.append(text.length() == 0 ? "" : "; ").append(note);
        }
        status = text.length() == 0 ? "No modern biomes" : text.toString();
        Better112.LOG.info("Modern biomes: {}", status);
    }

    private static int free() {
        return 256 - ForgeRegistries.BIOMES.getKeys().size() - SPARE_IDS;
    }

    /** Minecraft's own newer biomes, from the copy of the game's worldgen data. */
    private static void registerVanilla(RegistryEvent.Register<Biome> event, int[] skipped) throws java.io.IOException {
        File cache = better112.config.BetterConfig.importModernVanilla ? ModernWorld.cache() : null;
        if (cache == null) {
            return;
        }
        try (ZipFile zip = new ZipFile(cache)) {
            for (int i = 0; i < VANILLA.length; i += 2) {
                String name = (String) VANILLA[i];
                JsonObject biome = json(zip, "data/minecraft/worldgen/biome/" + name + ".json");
                if (biome == null) {
                    continue; // a biome newer than the installed game
                }
                if (free() <= 0) {
                    skipped[0]++;
                    continue;
                }
                StringBuilder english = new StringBuilder();
                for (String word : name.split("_")) {
                    english.append(english.length() == 0 ? "" : " ").append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
                }
                ModernBiome made = create(name, true, english.toString(), biome);
                made.setRegistryName(new ResourceLocation("better112", "mc_" + name));
                event.getRegistry().register(made);
                BIOMES.add(made);
                vanillaCount++;
                BiomeDictionary.addTypes(made, (BiomeDictionary.Type[]) VANILLA[i + 1]);
                ModernBiomes.addNative(name, made);
            }
        }
    }

    /** The overworld biomes of Biomes O' Plenty, from its jar. */
    private static void registerMod(RegistryEvent.Register<Biome> event, int[] skipped) throws java.io.IOException {
        File jar = Imported.sourceJar();
        if (jar == null) {
            return;
        }
        try (ZipFile zip = new ZipFile(jar)) {
            Set<String> overworld = tag(zip, "minecraft:is_overworld", new HashSet<String>());
            Set<String> underground = tag(zip, "forge:is_underground", new HashSet<String>());
            underground.addAll(tag(zip, "forge:is_cave", new HashSet<String>()));
            Map<BiomeDictionary.Type, Set<String>> byType = new LinkedHashMap<BiomeDictionary.Type, Set<String>>();
            for (int i = 0; i < TYPES.length; i += 2) {
                BiomeDictionary.Type type = (BiomeDictionary.Type) TYPES[i + 1];
                Set<String> members = byType.get(type);
                if (members == null) {
                    members = new HashSet<String>();
                    byType.put(type, members);
                }
                members.addAll(tag(zip, (String) TYPES[i], new HashSet<String>()));
            }
            JsonObject lang = json(zip, "assets/" + MOD + "/lang/en_us.json");
            List<String> names = new ArrayList<String>();
            String folder = "data/" + MOD + "/worldgen/biome/";
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements();) {
                String name = e.nextElement().getName();
                if (name.startsWith(folder) && name.endsWith(".json")) {
                    names.add(name.substring(folder.length(), name.length() - 5));
                }
            }
            java.util.Collections.sort(names);
            for (String name : names) {
                String id = MOD + ":" + name;
                if (!overworld.contains(id) || underground.contains(id)) {
                    continue;
                }
                if (free() <= 0) {
                    skipped[0]++;
                    continue;
                }
                JsonObject biome = json(zip, folder + name + ".json");
                String english = lang != null && lang.has("biome." + MOD + "." + name) ? lang.get("biome." + MOD + "." + name).getAsString() : name;
                ModernBiome made = create(name, false, english, biome);
                made.setRegistryName(new ResourceLocation("better112", "bop_" + name));
                event.getRegistry().register(made);
                BIOMES.add(made);
                modCount++;
                List<BiomeDictionary.Type> types = new ArrayList<BiomeDictionary.Type>();
                for (Map.Entry<BiomeDictionary.Type, Set<String>> entry : byType.entrySet()) {
                    if (entry.getValue().contains(id)) {
                        types.add(entry.getKey());
                    }
                }
                if (!types.isEmpty()) {
                    BiomeDictionary.addTypes(made, types.toArray(new BiomeDictionary.Type[0]));
                }
                ModernBiomes.addImported(made);
            }
        }
    }

    private static ModernBiome create(String name, boolean vanilla, String english, JsonObject json) {
        float temperature = json.has("temperature") ? json.get("temperature").getAsFloat() : 0.5F;
        // A "frozen" sea is only frozen in patches: most of it is just above freezing, where it rains and the water
        // stays open. 1.12.2 has one temperature per biome, so the biome gets the open-water one and the patches of
        // ice are laid with the ground (Surfaces).
        boolean frozenSea = json.has("temperature_modifier") && "frozen".equals(json.get("temperature_modifier").getAsString());
        boolean icePatches = frozenSea && temperature < 0.15F;
        if (frozenSea && temperature < 0.2F) {
            temperature = 0.2F;
        }
        // 1.12.2 refuses temperatures between 0.1 and 0.2, where snow would flicker with height.
        if (temperature > 0.1F && temperature < 0.2F) {
            temperature = temperature < 0.15F ? 0.1F : 0.2F;
        }
        boolean rain = !json.has("has_precipitation") || json.get("has_precipitation").getAsBoolean();
        Biome.BiomeProperties properties = new Biome.BiomeProperties(english).setTemperature(temperature)
                .setRainfall(Math.max(0.0F, Math.min(1.0F, json.has("downfall") ? json.get("downfall").getAsFloat() : 0.5F)));
        if (!rain) {
            properties.setRainDisabled();
        } else if (temperature < 0.15F) {
            properties.setSnowEnabled();
        }
        JsonObject effects = json.has("effects") ? json.getAsJsonObject("effects") : new JsonObject();
        if (effects.has("water_color")) {
            properties.setWaterColor(waterMultiplier(effects.get("water_color").getAsInt()));
        }
        if (vanilla && name.endsWith("ocean")) {
            properties.setBaseHeight(name.startsWith("deep") ? -1.8F : -1.0F).setHeightVariation(0.1F);
        }
        ModernBiome biome = new ModernBiome(name, vanilla, properties, json);
        biome.icePatches = icePatches;
        if (vanilla) {
            vanillaSurface(biome, name);
        } else {
            surface(biome, name);
        }
        return biome;
    }

    /**
     * Modern water colours are the colour itself; 1.12.2 multiplies its blue water texture by a
     * factor. The factor that turns the stock blue (0x3F76E4) into the wanted colour, as far as
     * a multiplier can.
     */
    private static int waterMultiplier(int color) {
        int r = Math.min(255, (color >> 16 & 255) * 255 / 0x3F), g = Math.min(255, (color >> 8 & 255) * 255 / 0x76),
                b = Math.min(255, (color & 255) * 255 / 0xE4);
        return r << 16 | g << 8 | b;
    }

    /** The ground of Minecraft's own newer biomes, where it is not plain grass over dirt. */
    private static void vanillaSurface(ModernBiome biome, String name) {
        switch (name) {
            case "frozen_peaks":
                biome.ground = Surfaces.Kind.FROZEN_PEAKS;
                break;
            case "jagged_peaks":
                biome.ground = Surfaces.Kind.JAGGED_PEAKS;
                break;
            case "snowy_slopes":
                biome.ground = Surfaces.Kind.SNOWY_SLOPES;
                break;
            case "grove":
                biome.ground = Surfaces.Kind.GROVE;
                break;
            case "stony_peaks":
                biome.ground = Surfaces.Kind.STONY_PEAKS;
                break;
            case "mangrove_swamp":
                biome.ground = Surfaces.Kind.MANGROVE;
                break;
            case "warm_ocean":
                biome.ground = Surfaces.Kind.SANDY;
                break;
            case "lukewarm_ocean":
            case "deep_lukewarm_ocean":
                biome.ground = Surfaces.Kind.SEA_SAND;
                break;
            case "cold_ocean":
            case "deep_cold_ocean":
                biome.ground = Surfaces.Kind.SEA_GRAVEL;
                break;
            case "frozen_ocean":
            case "deep_frozen_ocean":
                biome.ground = Surfaces.Kind.FROZEN_SEA;
                break;
            default:
                break;
        }
    }

    /** The ground of the biomes that are not plain grass over dirt. The mod sets this in code, not data. */
    private static void surface(ModernBiome biome, String name) {
        IBlockState top = null, filler = null;
        switch (name) {
            case "origin_valley":
            case "wintry_origin_valley":
                top = bop("origin_grass_block");
                break;
            case "wasteland":
                top = bop("dried_salt");
                filler = bop("dried_salt");
                break;
            case "volcano":
                top = bop("black_sandstone");
                filler = bop("black_sandstone");
                break;
            case "volcanic_plains":
                top = bop("black_sand");
                filler = bop("black_sandstone");
                break;
            case "lush_desert":
            case "dryland":
                top = bop("orange_sand");
                filler = bop("orange_sandstone");
                break;
            case "dune_beach":
                top = Blocks.SAND.getDefaultState();
                filler = Blocks.SANDSTONE.getDefaultState();
                break;
            case "gravel_beach":
            case "cold_desert":
                top = Blocks.GRAVEL.getDefaultState();
                filler = Blocks.GRAVEL.getDefaultState();
                break;
            case "crag":
                top = Blocks.STONE.getDefaultState();
                filler = Blocks.STONE.getDefaultState();
                break;
            case "redwood_forest":
                top = Blocks.DIRT.getDefaultState().withProperty(BlockDirt.VARIANT, BlockDirt.DirtType.PODZOL);
                break;
            case "fungal_jungle":
                top = Blocks.MYCELIUM.getDefaultState();
                break;
            default:
                break;
        }
        if (top != null) {
            biome.topBlock = top;
        }
        if (filler != null) {
            biome.fillerBlock = filler;
        }
    }

    private static IBlockState bop(String name) {
        return States.of(MOD + ":" + name, null);
    }

    private static JsonObject json(ZipFile zip, String name) throws java.io.IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            return null;
        }
        try (java.io.InputStream in = zip.getInputStream(entry)) {
            return new JsonParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    /** The biomes a tag names, following the tags it includes. "forge:is_wet/overworld" is data/forge/tags/worldgen/biome/is_wet/overworld.json. */
    private static Set<String> tag(ZipFile zip, String tag, Set<String> seen) throws java.io.IOException {
        Set<String> members = new HashSet<String>();
        if (!seen.add(tag)) {
            return members;
        }
        int colon = tag.indexOf(':');
        JsonObject json = json(zip, "data/" + tag.substring(0, colon) + "/tags/worldgen/biome/" + tag.substring(colon + 1) + ".json");
        if (json == null || !json.has("values")) {
            return members;
        }
        for (JsonElement value : json.getAsJsonArray("values")) {
            String id = value.isJsonObject() ? value.getAsJsonObject().get("id").getAsString() : value.getAsString();
            if (id.startsWith("#")) {
                members.addAll(tag(zip, id.substring(1), seen));
            } else {
                members.add(id);
            }
        }
        return members;
    }

    /** Self-test: the imported biomes by their modern name. */
    public static Map<String, Biome> byName() {
        Map<String, Biome> map = new HashMap<String, Biome>();
        for (ModernBiome biome : BIOMES) {
            map.put(biome.modernName, biome);
        }
        return map;
    }
}
