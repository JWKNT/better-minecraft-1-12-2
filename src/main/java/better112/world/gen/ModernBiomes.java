package better112.world.gen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.init.Biomes;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.common.BiomeDictionary;
import net.minecraftforge.common.BiomeManager;

/**
 * Which biome goes where in a modern world. Since 1.18 a biome is chosen from five climate
 * values (temperature, humidity, how far inland, how eroded, and a "weirdness" that makes
 * peaks and valleys), not from zoomed-up random patches. The game keeps that table in code
 * rather than in a data file, so it is written out again here.
 *
 * The result is the modern biome's name; {@link #toInstalled} then picks the closest biome
 * this 1.12.2 game has, until the modern biomes themselves are backported.
 */
final class ModernBiomes {

    private ModernBiomes() {}

    private static final String[][] OCEANS = {
        {"deep_frozen_ocean", "deep_cold_ocean", "deep_ocean", "deep_lukewarm_ocean", "warm_ocean"},
        {"frozen_ocean", "cold_ocean", "ocean", "lukewarm_ocean", "warm_ocean"}};
    private static final String[][] MIDDLE = {
        {"snowy_plains", "snowy_plains", "snowy_plains", "snowy_taiga", "taiga"},
        {"plains", "plains", "forest", "taiga", "old_growth_spruce_taiga"},
        {"flower_forest", "plains", "forest", "birch_forest", "dark_forest"},
        {"savanna", "savanna", "forest", "jungle", "jungle"},
        {"desert", "desert", "desert", "desert", "desert"}};
    private static final String[][] MIDDLE_VARIANT = {
        {"ice_spikes", null, "snowy_taiga", null, null},
        {null, null, null, null, "old_growth_pine_taiga"},
        {"sunflower_plains", null, null, "old_growth_birch_forest", null},
        {null, null, "plains", "sparse_jungle", "bamboo_jungle"},
        {null, null, null, null, null}};
    private static final String[][] PLATEAU = {
        {"snowy_plains", "snowy_plains", "snowy_plains", "snowy_taiga", "snowy_taiga"},
        {"meadow", "meadow", "forest", "taiga", "old_growth_spruce_taiga"},
        {"meadow", "meadow", "meadow", "meadow", "dark_forest"},
        {"savanna_plateau", "savanna_plateau", "forest", "forest", "jungle"},
        {"badlands", "badlands", "badlands", "wooded_badlands", "wooded_badlands"}};
    private static final String[][] PLATEAU_VARIANT = {
        {"ice_spikes", null, null, null, null},
        {"cherry_grove", null, "meadow", "meadow", "old_growth_pine_taiga"},
        {"cherry_grove", "cherry_grove", "forest", "birch_forest", "pale_garden"},
        {null, null, null, null, null},
        {"eroded_badlands", "eroded_badlands", null, null, null}};
    private static final String[][] SHATTERED = {
        {"windswept_gravelly_hills", "windswept_gravelly_hills", "windswept_hills", "windswept_forest", "windswept_forest"},
        {"windswept_gravelly_hills", "windswept_gravelly_hills", "windswept_hills", "windswept_forest", "windswept_forest"},
        {"windswept_hills", "windswept_hills", "windswept_hills", "windswept_forest", "windswept_forest"},
        {null, null, null, null, null},
        {null, null, null, null, null}};

    // How far inland: the first three are sea.
    private static final int MUSHROOM = 0, DEEP_OCEAN = 1, OCEAN = 2, COAST = 3, NEAR = 4, MID = 5, FAR = 6;

    /** The modern biome for a climate, without the "minecraft:" prefix. */
    static String pick(double temperature, double humidity, double continents, double erosion, double weirdness) {
        int t = temperature < -0.45D ? 0 : temperature < -0.15D ? 1 : temperature < 0.2D ? 2 : temperature < 0.55D ? 3 : 4;
        int h = humidity < -0.35D ? 0 : humidity < -0.1D ? 1 : humidity < 0.1D ? 2 : humidity < 0.3D ? 3 : 4;
        int e = erosion < -0.78D ? 0 : erosion < -0.375D ? 1 : erosion < -0.2225D ? 2 : erosion < 0.05D ? 3
                : erosion < 0.45D ? 4 : erosion < 0.55D ? 5 : 6;
        int c = continents < -1.05D ? MUSHROOM : continents < -0.455D ? DEEP_OCEAN : continents < -0.19D ? OCEAN
                : continents < -0.11D ? COAST : continents < 0.03D ? NEAR : continents < 0.3D ? MID : FAR;
        if (c == MUSHROOM) {
            return "mushroom_fields";
        }
        if (c == DEEP_OCEAN) {
            return OCEANS[0][t];
        }
        if (c == OCEAN) {
            return OCEANS[1][t];
        }
        double w = weirdness;
        boolean neg = w < -0.05D;
        double a = Math.abs(w);
        if (a < 0.05D) {
            return valley(t, h, c, e, neg);
        }
        if (a < 0.26666668D) {
            return low(t, h, c, e, neg);
        }
        if (a < 0.4D) {
            return mid(t, h, c, e, neg);
        }
        if (a < 0.56666666D) {
            return high(t, h, c, e, neg);
        }
        if (a < 0.7666667D) {
            return peaks(t, h, c, e, neg);
        }
        if (a < 0.93333334D) {
            return high(t, h, c, e, neg);
        }
        return mid(t, h, c, e, neg);
    }

    private static String peaks(int t, int h, int c, int e, boolean neg) {
        switch (e) {
            case 0:
                return peak(t, h, neg);
            case 1:
                return c <= NEAR ? middleOrBadlandsOrSlope(t, h, neg) : peak(t, h, neg);
            case 2:
                return c <= NEAR ? middle(t, h, neg) : plateau(t, h, neg);
            case 3:
                return c <= NEAR ? middle(t, h, neg) : c == MID ? middleOrBadlands(t, h, neg) : plateau(t, h, neg);
            case 5:
                return c <= NEAR ? windsweptSavannaOr(t, h, neg, shattered(t, h, neg)) : shattered(t, h, neg);
            default:
                return middle(t, h, neg);
        }
    }

    private static String high(int t, int h, int c, int e, boolean neg) {
        switch (e) {
            case 0:
                return c == COAST ? middle(t, h, neg) : c == NEAR ? slope(t, h, neg) : peak(t, h, neg);
            case 1:
                return c == COAST ? middle(t, h, neg) : c == NEAR ? middleOrBadlandsOrSlope(t, h, neg) : slope(t, h, neg);
            case 2:
                return c <= NEAR ? middle(t, h, neg) : plateau(t, h, neg);
            case 3:
                return c <= NEAR ? middle(t, h, neg) : c == MID ? middleOrBadlands(t, h, neg) : plateau(t, h, neg);
            case 5:
                return c <= NEAR ? windsweptSavannaOr(t, h, neg, shattered(t, h, neg)) : shattered(t, h, neg);
            default:
                return middle(t, h, neg);
        }
    }

    private static String mid(int t, int h, int c, int e, boolean neg) {
        if (c == COAST && e <= 2) {
            return "stony_shore";
        }
        switch (e) {
            case 0:
                return slope(t, h, neg);
            case 1:
                return c == FAR ? (t == 0 ? slope(t, h, neg) : plateau(t, h, neg)) : middleOrBadlandsOrSlope(t, h, neg);
            case 2:
                return c == NEAR ? middle(t, h, neg) : c == MID ? middleOrBadlands(t, h, neg) : plateau(t, h, neg);
            case 3:
                return c <= NEAR ? middle(t, h, neg) : middleOrBadlands(t, h, neg);
            case 4:
                return c == COAST && neg ? beach(t) : middle(t, h, neg);
            case 5:
                return c == COAST ? shatteredCoast(t, h, neg)
                        : c == NEAR ? windsweptSavannaOr(t, h, neg, shattered(t, h, neg)) : shattered(t, h, neg);
            default:
                if (c == COAST) {
                    return neg ? beach(t) : middle(t, h, neg);
                }
                return t == 0 ? middle(t, h, neg) : t <= 2 ? "swamp" : "mangrove_swamp";
        }
    }

    private static String low(int t, int h, int c, int e, boolean neg) {
        if (c == COAST && e <= 2) {
            return "stony_shore";
        }
        switch (e) {
            case 0:
            case 1:
                return c == NEAR ? middleOrBadlands(t, h, neg) : middleOrBadlandsOrSlope(t, h, neg);
            case 2:
            case 3:
                return c == COAST ? beach(t) : c == NEAR ? middle(t, h, neg) : middleOrBadlands(t, h, neg);
            case 4:
                return c == COAST ? beach(t) : middle(t, h, neg);
            case 5:
                return c == COAST ? shatteredCoast(t, h, neg)
                        : c == NEAR ? windsweptSavannaOr(t, h, neg, shattered(t, h, neg)) : middle(t, h, neg);
            default:
                if (c == COAST) {
                    return beach(t);
                }
                return t == 0 ? middle(t, h, neg) : t <= 2 ? "swamp" : "mangrove_swamp";
        }
    }

    private static String valley(int t, int h, int c, int e, boolean neg) {
        String river = t == 0 ? "frozen_river" : "river";
        if (e <= 1) {
            return c <= NEAR ? river : middleOrBadlands(t, h, neg);
        }
        if (e <= 5 || c == COAST || t == 0) {
            return river;
        }
        return t <= 2 ? "swamp" : "mangrove_swamp";
    }

    private static String middle(int t, int h, boolean neg) {
        if (!neg && MIDDLE_VARIANT[t][h] != null) {
            return MIDDLE_VARIANT[t][h];
        }
        return MIDDLE[t][h];
    }

    private static String middleOrBadlands(int t, int h, boolean neg) {
        return t == 4 ? badlands(h, neg) : middle(t, h, neg);
    }

    private static String middleOrBadlandsOrSlope(int t, int h, boolean neg) {
        return t == 0 ? slope(t, h, neg) : middleOrBadlands(t, h, neg);
    }

    private static String badlands(int h, boolean neg) {
        if (h < 2) {
            return neg ? "badlands" : "eroded_badlands";
        }
        return h < 3 ? "badlands" : "wooded_badlands";
    }

    private static String plateau(int t, int h, boolean neg) {
        if (!neg && PLATEAU_VARIANT[t][h] != null) {
            return PLATEAU_VARIANT[t][h];
        }
        return PLATEAU[t][h];
    }

    private static String peak(int t, int h, boolean neg) {
        if (t <= 2) {
            return neg ? "jagged_peaks" : "frozen_peaks";
        }
        return t == 3 ? "stony_peaks" : badlands(h, neg);
    }

    private static String slope(int t, int h, boolean neg) {
        if (t >= 3) {
            return plateau(t, h, neg);
        }
        return h <= 1 ? "snowy_slopes" : "grove";
    }

    private static String shattered(int t, int h, boolean neg) {
        return SHATTERED[t][h] != null ? SHATTERED[t][h] : middle(t, h, neg);
    }

    private static String windsweptSavannaOr(int t, int h, boolean neg, String otherwise) {
        return t > 1 && h < 4 && !neg ? "windswept_savanna" : otherwise;
    }

    private static String shatteredCoast(int t, int h, boolean neg) {
        return windsweptSavannaOr(t, h, neg, neg ? beach(t) : middle(t, h, neg));
    }

    private static String beach(int t) {
        return t == 0 ? "snowy_beach" : t == 4 ? "desert" : "beach";
    }

    // ------------------------------------------------------------------------------------
    // Modern name -> a biome this game has
    // ------------------------------------------------------------------------------------

    private static final Map<String, String> CLOSEST = new HashMap<String, String>();
    private static final Map<String, Biome> RESOLVED = new HashMap<String, Biome>();

    static {
        String[] pairs = {
            "ocean", "ocean", "deep_ocean", "deep_ocean", "cold_ocean", "ocean", "deep_cold_ocean", "deep_ocean",
            "lukewarm_ocean", "ocean", "deep_lukewarm_ocean", "deep_ocean", "warm_ocean", "ocean",
            "frozen_ocean", "frozen_ocean", "deep_frozen_ocean", "frozen_ocean", "mushroom_fields", "mushroom_island",
            "plains", "plains", "sunflower_plains", "mutated_plains", "snowy_plains", "ice_flats", "ice_spikes", "mutated_ice_flats",
            "desert", "desert", "swamp", "swampland", "mangrove_swamp", "swampland",
            "forest", "forest", "flower_forest", "mutated_forest", "birch_forest", "birch_forest", "dark_forest", "roofed_forest",
            "old_growth_birch_forest", "mutated_birch_forest", "old_growth_pine_taiga", "redwood_taiga",
            "old_growth_spruce_taiga", "mutated_redwood_taiga", "taiga", "taiga", "snowy_taiga", "taiga_cold",
            "savanna", "savanna", "savanna_plateau", "savanna_rock", "windswept_savanna", "mutated_savanna",
            "windswept_hills", "extreme_hills", "windswept_gravelly_hills", "mutated_extreme_hills",
            "windswept_forest", "extreme_hills_with_trees",
            "jungle", "jungle", "sparse_jungle", "jungle_edge", "bamboo_jungle", "jungle",
            "badlands", "mesa", "eroded_badlands", "mutated_mesa", "wooded_badlands", "mesa_rock",
            // No counterpart on 1.12.2: the nearest in look, used where the modern biome itself could not be brought over.
            "meadow", "mutated_plains", "cherry_grove", "mutated_forest", "grove", "taiga_cold",
            "snowy_slopes", "ice_mountains", "frozen_peaks", "ice_mountains", "jagged_peaks", "ice_mountains",
            "stony_peaks", "extreme_hills", "pale_garden", "roofed_forest",
            "river", "river", "frozen_river", "frozen_river", "beach", "beaches", "snowy_beach", "cold_beach",
            "stony_shore", "stone_beach"};
        for (int i = 0; i < pairs.length; i += 2) {
            CLOSEST.put(pairs[i], pairs[i + 1]);
        }
    }

    /** Minecraft's own newer biomes that were brought over, by modern name. */
    private static final Map<String, Biome> NATIVE = new HashMap<String, Biome>();

    static synchronized void addNative(String modern, Biome biome) {
        NATIVE.put(modern, biome);
        SLOTS.clear();
    }

    /** The biome a modern name is here: the modern biome itself if it was brought over, else the closest 1.12.2 biome. */
    static synchronized Biome toInstalled(String modern) {
        Biome own = NATIVE.get(modern);
        return own != null ? own : standIn(modern);
    }

    /** The closest biome 1.12.2 itself has. */
    static synchronized Biome standIn(String modern) {
        Biome biome = RESOLVED.get(modern);
        if (biome == null) {
            String name = CLOSEST.get(modern);
            biome = name == null ? null : Biome.REGISTRY.getObject(new ResourceLocation("minecraft", name));
            if (biome == null) {
                biome = Biomes.PLAINS;
            }
            RESOLVED.put(modern, biome);
        }
        return biome;
    }

    // ------------------------------------------------------------------------------------
    // Biomes from other mods
    // ------------------------------------------------------------------------------------

    /** The stand-in plus every mod biome that fits the same climate, with the share each gets. */
    private static final class Slot {
        final List<Biome> biomes = new ArrayList<Biome>();
        final List<Double> upTo = new ArrayList<Double>();
    }

    private static final Map<String, Slot> SLOTS = new HashMap<String, Slot>();

    /** What decides whether two biomes are "the same kind of place". */
    private static final BiomeDictionary.Type[] KIND = {
        BiomeDictionary.Type.FOREST, BiomeDictionary.Type.PLAINS, BiomeDictionary.Type.MOUNTAIN, BiomeDictionary.Type.HILLS,
        BiomeDictionary.Type.SWAMP, BiomeDictionary.Type.SANDY, BiomeDictionary.Type.JUNGLE, BiomeDictionary.Type.SAVANNA,
        BiomeDictionary.Type.MESA, BiomeDictionary.Type.CONIFEROUS, BiomeDictionary.Type.WASTELAND, BiomeDictionary.Type.SNOWY,
        BiomeDictionary.Type.HOT, BiomeDictionary.Type.COLD, BiomeDictionary.Type.WET, BiomeDictionary.Type.DRY};
    private static final BiomeDictionary.Type[] NOT_LAND = {
        BiomeDictionary.Type.OCEAN, BiomeDictionary.Type.RIVER, BiomeDictionary.Type.BEACH, BiomeDictionary.Type.NETHER,
        BiomeDictionary.Type.END, BiomeDictionary.Type.VOID, BiomeDictionary.Type.MUSHROOM, BiomeDictionary.Type.WATER};

    /**
     * The biome for a modern slot, with other mods' overworld biomes mixed in. A pack that
     * adds biomes (Traverse, Biomes O' Plenty) registers them with Forge for the old layered
     * generator; here each one joins the modern slots whose stand-in is the same kind of
     * place, and "roll" (0 to 1, smooth across the world) picks among them.
     */
    static synchronized Biome choose(String modern, double roll) {
        Slot slot = SLOTS.get(modern);
        if (slot == null) {
            slot = buildSlot(modern);
            SLOTS.put(modern, slot);
        }
        for (int i = 0; i < slot.biomes.size() - 1; i++) {
            if (roll < slot.upTo.get(i)) {
                return slot.biomes.get(i);
            }
        }
        return slot.biomes.get(slot.biomes.size() - 1);
    }

    /** Biomes brought in from modern mods; they are offered to every slot, as the mods' own placement is code we do not have. */
    private static final List<Biome> IMPORTED = new ArrayList<Biome>();
    /** Imported biomes that are like no vanilla stand-in closely enough: each goes to the one slot it is most like. */
    private static Map<String, List<Biome>> orphans;

    static synchronized void addImported(Biome biome) {
        IMPORTED.add(biome);
        SLOTS.clear();
        orphans = null;
    }

    private static Slot buildSlot(String modern) {
        // Mod biomes are matched against the 1.12.2 stand-in, whose kinds Forge and the mods agree on.
        Biome base = standIn(modern), shown = toInstalled(modern);
        Slot slot = new Slot();
        List<Biome> fits = new ArrayList<Biome>();
        List<Integer> weights = new ArrayList<Integer>();
        int total = 0;
        for (BiomeManager.BiomeType type : BiomeManager.BiomeType.values()) {
            List<BiomeManager.BiomeEntry> entries = BiomeManager.getBiomes(type);
            if (entries == null) {
                continue;
            }
            for (BiomeManager.BiomeEntry entry : entries) {
                Biome biome = entry.biome;
                ResourceLocation id = biome == null ? null : biome.getRegistryName();
                if (id == null || "minecraft".equals(id.getNamespace()) || fits.contains(biome)) {
                    continue;
                }
                boolean named = id.getPath().equals(modern);
                if (named || likeness(base, biome) >= 0.6D) {
                    fits.add(biome);
                    // A mod biome with the modern biome's own name (Traverse's meadow) is the better stand-in.
                    int weight = Math.max(1, entry.itemWeight) * (named ? 6 : 1);
                    weights.add(weight);
                    total += weight;
                }
            }
        }
        for (Biome biome : IMPORTED) {
            if (!fits.contains(biome) && likeness(base, biome) >= 0.6D) {
                fits.add(biome);
                weights.add(10);
                total += 10;
            }
        }
        List<Biome> adopted = orphans().get(modern);
        if (adopted != null) {
            for (Biome biome : adopted) {
                if (!fits.contains(biome)) {
                    fits.add(biome);
                    weights.add(10);
                    total += 10;
                }
            }
        }
        // The stand-in keeps half; the mod biomes share the rest by their own weights.
        double sum = 0.5D;
        slot.biomes.add(shown);
        slot.upTo.add(fits.isEmpty() ? 1.0D : sum);
        for (int i = 0; i < fits.size(); i++) {
            sum += 0.5D * weights.get(i) / total;
            slot.biomes.add(fits.get(i));
            slot.upTo.add(sum);
        }
        return slot;
    }

    private static Map<String, List<Biome>> orphans() {
        if (orphans == null) {
            orphans = new HashMap<String, List<Biome>>();
            for (Biome biome : IMPORTED) {
                String best = null;
                double bestScore = -1.0D;
                boolean placed = false;
                for (String modern : CLOSEST.keySet()) {
                    double score = likeness(standIn(modern), biome);
                    if (score >= 0.6D) {
                        placed = true;
                        break;
                    }
                    if (score > bestScore) {
                        bestScore = score;
                        best = modern;
                    }
                }
                if (!placed && best != null && bestScore >= 0.0D) {
                    List<Biome> list = orphans.get(best);
                    if (list == null) {
                        list = new ArrayList<Biome>();
                        orphans.put(best, list);
                    }
                    list.add(biome);
                }
            }
        }
        return orphans;
    }

    /**
     * How alike two biomes are as places, from 0 to 1; below 0 if one is land and the other
     * sea, river or beach. Land biomes are compared by their kind (forest, sandy, cold, ...).
     */
    private static double likeness(Biome base, Biome other) {
        Set<BiomeDictionary.Type> waterA = types(base, NOT_LAND), waterB = types(other, NOT_LAND);
        if (!waterA.equals(waterB)) {
            return -1.0D;
        }
        if (!waterA.isEmpty()) {
            // Two beaches, say: alike if both are snowy or neither is.
            return BiomeDictionary.hasType(base, BiomeDictionary.Type.SNOWY) == BiomeDictionary.hasType(other, BiomeDictionary.Type.SNOWY) ? 1.0D : 0.0D;
        }
        Set<BiomeDictionary.Type> a = kind(base), b = kind(other);
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0D;
        }
        int shared = 0;
        for (BiomeDictionary.Type type : a) {
            if (b.contains(type)) {
                shared++;
            }
        }
        return shared / (double) (a.size() + b.size() - shared);
    }

    private static Set<BiomeDictionary.Type> types(Biome biome, BiomeDictionary.Type[] among) {
        Set<BiomeDictionary.Type> found = new HashSet<BiomeDictionary.Type>();
        for (BiomeDictionary.Type type : among) {
            if (BiomeDictionary.hasType(biome, type)) {
                found.add(type);
            }
        }
        return found;
    }

    private static Set<BiomeDictionary.Type> kind(Biome biome) {
        Set<BiomeDictionary.Type> kind = new HashSet<BiomeDictionary.Type>();
        for (BiomeDictionary.Type type : KIND) {
            if (BiomeDictionary.hasType(biome, type)) {
                kind.add(type);
            }
        }
        return kind;
    }

    /** How many mod biomes joined the layout, for the log. */
    static synchronized String describeModBiomes() {
        Set<Biome> all = new HashSet<Biome>();
        for (String modern : CLOSEST.keySet()) {
            Slot slot = SLOTS.get(modern);
            if (slot == null) {
                slot = buildSlot(modern);
                SLOTS.put(modern, slot);
            }
            for (int i = 1; i < slot.biomes.size(); i++) {
                all.add(slot.biomes.get(i));
            }
        }
        StringBuilder names = new StringBuilder();
        for (Biome biome : all) {
            names.append(names.length() == 0 ? "" : ", ").append(biome.getRegistryName());
        }
        return all.size() + (all.isEmpty() ? "" : ": " + names);
    }
}
