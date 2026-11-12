package better112.world.density;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import better112.world.noise.BlendedNoise;
import better112.world.noise.NormalNoise;
import better112.world.noise.Xoroshiro;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Builds density functions from the game's JSON. A function is a number, the name of
 * another function's file, or an object with a "type"; noises are named files under
 * worldgen/noise and each gets its own random stream derived from the world seed and its
 * name, as in the modern game.
 */
public final class DensityLoader {

    private final WorldData data;
    private final Xoroshiro.Positional random;
    private final Density.Grid grid;
    private final Map<String, Density> functions = new HashMap<String, Density>();
    private final Map<String, NormalNoise> noises = new HashMap<String, NormalNoise>();
    final List<Density.Interpolated> interpolated = new ArrayList<Density.Interpolated>();

    public DensityLoader(WorldData data, long seed, Density.Grid grid) {
        this.data = data;
        this.random = new Xoroshiro(seed).fork();
        this.grid = grid;
    }

    /** A random stream for a named purpose ("minecraft:aquifer", "minecraft:ore", ...). */
    public Xoroshiro randomFor(String name) {
        return random.named(name);
    }

    public NormalNoise noise(String id) {
        String key = WorldData.canonical(id);
        NormalNoise noise = noises.get(key);
        if (noise == null) {
            JsonObject json = data.read("noise", key).getAsJsonObject();
            JsonArray list = json.getAsJsonArray("amplitudes");
            double[] amplitudes = new double[list.size()];
            for (int i = 0; i < amplitudes.length; i++) {
                amplitudes[i] = list.get(i).getAsDouble();
            }
            noise = new NormalNoise(random.named(key), json.get("firstOctave").getAsInt(), amplitudes);
            noises.put(key, noise);
        }
        return noise;
    }

    public Density load(JsonElement json) {
        if (json == null) {
            throw new IllegalArgumentException("A density function is missing from the worldgen data");
        }
        if (json.isJsonPrimitive()) {
            if (json.getAsJsonPrimitive().isNumber()) {
                return new Density.Constant(json.getAsDouble());
            }
            String key = WorldData.canonical(json.getAsString());
            Density known = functions.get(key);
            if (known == null) {
                known = load(data.read("density_function", key));
                functions.put(key, known);
            }
            return known;
        }
        JsonObject o = json.getAsJsonObject();
        String type = o.get("type").getAsString();
        if (type.startsWith("minecraft:")) {
            type = type.substring(10);
        }
        switch (type) {
            case "constant":
                return new Density.Constant(o.get("argument").getAsDouble());
            case "add":
                return binary(Density.Binary.ADD, o);
            case "mul":
                return binary(Density.Binary.MUL, o);
            case "min":
                return binary(Density.Binary.MIN, o);
            case "max":
                return binary(Density.Binary.MAX, o);
            case "abs":
                return new Density.Unary(Density.Unary.ABS, arg(o));
            case "square":
                return new Density.Unary(Density.Unary.SQUARE, arg(o));
            case "cube":
                return new Density.Unary(Density.Unary.CUBE, arg(o));
            case "half_negative":
                return new Density.Unary(Density.Unary.HALF_NEGATIVE, arg(o));
            case "quarter_negative":
                return new Density.Unary(Density.Unary.QUARTER_NEGATIVE, arg(o));
            case "squeeze":
                return new Density.Unary(Density.Unary.SQUEEZE, arg(o));
            case "invert":
                return new Density.Unary(Density.Unary.INVERT, arg(o));
            case "find_top_surface":
                return new Density.FindTopSurface(load(o.get("density")), load(o.get("upper_bound")),
                        o.get("lower_bound").getAsInt(), o.get("cell_height").getAsInt());
            case "clamp":
                return new Density.Clamp(load(o.get("input")), o.get("min").getAsDouble(), o.get("max").getAsDouble());
            case "range_choice":
                return new Density.RangeChoice(load(o.get("input")), o.get("min_inclusive").getAsDouble(),
                        o.get("max_exclusive").getAsDouble(), load(o.get("when_in_range")), load(o.get("when_out_of_range")));
            case "y_clamped_gradient":
                return new Density.YGradient(o.get("from_y").getAsDouble(), o.get("to_y").getAsDouble(),
                        o.get("from_value").getAsDouble(), o.get("to_value").getAsDouble());
            case "noise":
                return new Density.Noise(noise(o.get("noise").getAsString()), number(o, "xz_scale", 1.0D), number(o, "y_scale", 1.0D));
            case "shifted_noise":
                return new Density.ShiftedNoise(noise(o.get("noise").getAsString()), number(o, "xz_scale", 1.0D), number(o, "y_scale", 1.0D),
                        load(o.get("shift_x")), load(o.get("shift_y")), load(o.get("shift_z")));
            case "shift_a":
                return new Density.Shift(noise(o.get("argument").getAsString()), 0);
            case "shift_b":
                return new Density.Shift(noise(o.get("argument").getAsString()), 1);
            case "shift":
                return new Density.Shift(noise(o.get("argument").getAsString()), 2);
            case "weird_scaled_sampler":
                return new Density.WeirdScaled(load(o.get("input")), noise(o.get("noise").getAsString()),
                        "type_1".equals(o.get("rarity_value_mapper").getAsString()));
            case "old_blended_noise":
                return new Density.OldBlended(new BlendedNoise(random.named("minecraft:terrain"), number(o, "xz_scale", 1.0D),
                        number(o, "y_scale", 1.0D), number(o, "xz_factor", 80.0D), number(o, "y_factor", 160.0D),
                        number(o, "smear_scale_multiplier", 8.0D)));
            case "spline":
                return spline(o.get("spline"));
            case "interpolated": {
                Density.Interpolated node = new Density.Interpolated(arg(o), grid);
                interpolated.add(node);
                return node;
            }
            case "flat_cache":
                return new Density.FlatCache(arg(o));
            case "cache_2d":
                return new Density.Cache2D(arg(o));
            case "cache_once":
                return new Density.CacheOnce(arg(o));
            case "cache_all_in_cell":
            case "blend_density":
                return arg(o);
            // Blending with chunks from older versions does not exist here: fully new terrain.
            case "blend_alpha":
                return new Density.Constant(1.0D);
            case "blend_offset":
            case "beardifier":
            case "end_islands":
                return new Density.Constant(0.0D);
            default:
                throw new IllegalArgumentException("Unknown density function type " + type);
        }
    }

    private Density arg(JsonObject o) {
        return load(o.get("argument"));
    }

    private Density binary(int op, JsonObject o) {
        return new Density.Binary(op, load(o.get("argument1")), load(o.get("argument2")));
    }

    private static double number(JsonObject o, String key, double fallback) {
        return o.has(key) ? o.get(key).getAsDouble() : fallback;
    }

    private Density spline(JsonElement json) {
        if (json.isJsonPrimitive()) {
            return new Density.Constant(json.getAsDouble());
        }
        JsonObject o = json.getAsJsonObject();
        JsonArray points = o.getAsJsonArray("points");
        int n = points.size();
        float[] locations = new float[n], derivatives = new float[n];
        Density[] values = new Density[n];
        for (int i = 0; i < n; i++) {
            JsonObject point = points.get(i).getAsJsonObject();
            locations[i] = point.get("location").getAsFloat();
            derivatives[i] = point.get("derivative").getAsFloat();
            values[i] = spline(point.get("value"));
        }
        return new Density.Spline(load(o.get("coordinate")), locations, values, derivatives);
    }
}
