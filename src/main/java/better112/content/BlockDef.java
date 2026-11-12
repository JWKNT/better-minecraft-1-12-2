package better112.content;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * One block of a modern mod, as far as its data files describe it: its name, which kind of
 * 1.12.2 block can stand in for it, and the list of model variants its blockstate file has.
 *
 * Modern mods say in code what a block is (a stair, a sapling) but its blockstate file
 * gives it away: the set of properties is different for every kind.
 */
final class BlockDef {

    enum Kind {
        CUBE, FALLING, PILLAR, LEAVES, PLANT, SAPLING, TALL_PLANT, STAIRS, SLAB, FENCE, FENCE_GATE, DOOR, TRAPDOOR, BUTTON, PLATE,
        FLAT, LILY, VINE, PETALS, WALL, SIGN, WALL_SIGN, HANGING, BAMBOO, DRIPSTONE, LICHEN,
        AQUATIC, AQUATIC_TALL, AQUATIC_WALL, PICKLE, BUD
    }

    final Source source;
    /** Name in the modern mod ("fir_planks"). */
    final String name;
    /** Name here, after "better112:" ("bop_fir_planks"). */
    final String key;
    final Kind kind;
    /** The blockstate file's variant keys in file order ("facing=east,half=lower"), empty for a multipart file. */
    final List<Map<String, String>> variants = new ArrayList<Map<String, String>>();
    final List<String> variantKeys = new ArrayList<String>();
    /** Values for properties the stand-in block does not have ("snowy" -> "false"). */
    final Map<String, String> fixed = new HashMap<String, String>();
    /** 0 no tint, 1 the biome's foliage colour, 2 its grass colour, 3 one fixed colour (tintColor). */
    int tint;
    int tintColor = 0xFFFFFF;
    /** Drawn with see-through parts although it is a whole block (a tangle of roots). */
    boolean cutout;
    /** The wood this block belongs to ("fir"), or null. */
    String wood;
    /** English name from the mod's language file. */
    String english;
    /**
     * How the blockstate file is adapted: 0 as it is; 1 a list of parts keyed on six "is this
     * face shown" switches, kept with every face shown (giant mushroom blocks); 2 a wall, whose
     * modern "low" and "tall" sides become the one kind of side 1.12.2 has; 3 a carpet that can
     * climb walls, kept as the flat piece alone.
     */
    int parts;

    BlockDef(Source source, String name, Kind kind, JsonObject state) {
        this.source = source;
        this.name = name;
        this.key = source.key(name);
        this.kind = kind;
        if (state.has("variants")) {
            for (Map.Entry<String, JsonElement> entry : state.getAsJsonObject("variants").entrySet()) {
                variantKeys.add(entry.getKey());
                variants.add(parseKey(entry.getKey()));
            }
        }
    }

    static Map<String, String> parseKey(String key) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        for (String pair : key.split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                map.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        return map;
    }

    /** The variant key of the blockstate file that fits a set of property values; the first one if none does. */
    String variantFor(Map<String, String> properties) {
        for (int i = 0; i < variants.size(); i++) {
            boolean fits = true;
            for (Map.Entry<String, String> want : variants.get(i).entrySet()) {
                String have = properties.get(want.getKey());
                if (have == null) {
                    have = fixed.get(want.getKey());
                }
                if (!want.getValue().equals(have)) {
                    fits = false;
                    break;
                }
            }
            if (fits) {
                return variantKeys.get(i);
            }
        }
        return variantKeys.isEmpty() ? "" : variantKeys.get(0);
    }

    /** Every property a blockstate file mentions, with the values it uses. */
    static Map<String, Set<String>> properties(JsonObject state) {
        Map<String, Set<String>> props = new HashMap<String, Set<String>>();
        if (state.has("variants")) {
            for (Map.Entry<String, JsonElement> entry : state.getAsJsonObject("variants").entrySet()) {
                for (Map.Entry<String, String> pair : parseKey(entry.getKey()).entrySet()) {
                    add(props, pair.getKey(), pair.getValue());
                }
            }
        } else if (state.has("multipart")) {
            for (JsonElement part : state.getAsJsonArray("multipart")) {
                JsonObject o = part.getAsJsonObject();
                if (o.has("when")) {
                    conditions(o.get("when"), props);
                }
            }
        }
        return props;
    }

    private static void conditions(JsonElement when, Map<String, Set<String>> props) {
        if (when.isJsonArray()) {
            for (JsonElement e : (JsonArray) when) {
                conditions(e, props);
            }
        } else if (when.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : when.getAsJsonObject().entrySet()) {
                if (entry.getKey().equals("OR") || entry.getKey().equals("AND")) {
                    conditions(entry.getValue(), props);
                } else {
                    for (String value : entry.getValue().getAsString().split("\\|")) {
                        add(props, entry.getKey(), value);
                    }
                }
            }
        }
    }

    private static void add(Map<String, Set<String>> props, String key, String value) {
        Set<String> values = props.get(key);
        if (values == null) {
            values = new TreeSet<String>();
            props.put(key, values);
        }
        values.add(value);
    }
}
