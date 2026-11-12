package better112.world.feature;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;

/** The small value types worldgen files are full of: random whole numbers, heights, and "which block". */
public final class Values {

    private Values() {}

    /** A whole number, fixed or drawn at random. */
    public abstract static class Int {
        public abstract int get(Random random);

        public static Int of(final int value) {
            return new Int() {
                @Override
                public int get(Random random) {
                    return value;
                }
            };
        }

        public static Int parse(JsonElement json, int fallback) {
            if (json == null) {
                return of(fallback);
            }
            if (json.isJsonPrimitive()) {
                return of(json.getAsInt());
            }
            JsonObject o = json.getAsJsonObject();
            String type = o.get("type").getAsString().replace("minecraft:", "");
            // Newer files put the numbers directly in the object, older ones under "value".
            final JsonObject v = o.has("value") && o.get("value").isJsonObject() ? o.getAsJsonObject("value") : o;
            switch (type) {
                case "constant":
                    return of(o.get("value").getAsInt());
                case "uniform":
                case "biased_to_bottom":
                case "clamped_normal":
                case "trapezoid": {
                    final int min = v.has("min_inclusive") ? v.get("min_inclusive").getAsInt() : 0;
                    final int max = v.has("max_inclusive") ? v.get("max_inclusive").getAsInt() : min;
                    final boolean low = type.equals("biased_to_bottom");
                    return new Int() {
                        @Override
                        public int get(Random random) {
                            int span = Math.max(1, max - min + 1);
                            return min + (low ? random.nextInt(random.nextInt(span) + 1) : random.nextInt(span));
                        }
                    };
                }
                case "clamped": {
                    final Int source = parse(v.get("source"), fallback);
                    final int min = v.get("min_inclusive").getAsInt(), max = v.get("max_inclusive").getAsInt();
                    return new Int() {
                        @Override
                        public int get(Random random) {
                            return Math.max(min, Math.min(max, source.get(random)));
                        }
                    };
                }
                case "weighted_list": {
                    final List<Int> values = new ArrayList<Int>();
                    final List<Integer> weights = new ArrayList<Integer>();
                    int sum = 0;
                    for (JsonElement entry : o.getAsJsonArray("distribution")) {
                        JsonObject e = entry.getAsJsonObject();
                        values.add(parse(e.get("data"), fallback));
                        weights.add(e.get("weight").getAsInt());
                        sum += e.get("weight").getAsInt();
                    }
                    final int total = Math.max(1, sum);
                    return new Int() {
                        @Override
                        public int get(Random random) {
                            int roll = random.nextInt(total);
                            for (int i = 0; i < values.size(); i++) {
                                roll -= weights.get(i);
                                if (roll < 0) {
                                    return values.get(i).get(random);
                                }
                            }
                            return values.get(values.size() - 1).get(random);
                        }
                    };
                }
                default:
                    return of(fallback);
            }
        }
    }

    /**
     * A height: {"absolute": 64}, {"above_bottom": 8} or {"below_top": 8}. The modern world
     * runs from -64 to 320 and this one from 0 to 256, so the ends are measured from 0 and 256.
     */
    static int anchor(JsonElement json) {
        if (json == null) {
            return 64;
        }
        if (json.isJsonPrimitive()) {
            return json.getAsInt();
        }
        JsonObject o = json.getAsJsonObject();
        if (o.has("absolute")) {
            return o.get("absolute").getAsInt();
        }
        if (o.has("above_bottom")) {
            return o.get("above_bottom").getAsInt();
        }
        return o.has("below_top") ? 255 - o.get("below_top").getAsInt() : 64;
    }

    /** Which block: one fixed state, or one of several by weight. Null from get() means "nothing this game has". */
    public abstract static class State {
        public abstract IBlockState get(Random random);

        /** True if every block it can give is air: "no vines", "no fruit". */
        public boolean isNothing() {
            return false;
        }

        public static State parse(JsonElement json) {
            if (json == null || !json.isJsonObject()) {
                return fixed(null);
            }
            JsonObject o = json.getAsJsonObject();
            if (!o.has("type")) {
                return fixed(States.of(o));
            }
            String type = o.get("type").getAsString().replace("minecraft:", "");
            if (o.has("state")) {
                return fixed(States.of(o.get("state")));
            }
            final List<IBlockState> states = new ArrayList<IBlockState>();
            final List<Integer> weights = new ArrayList<Integer>();
            int sum = 0;
            if (o.has("entries")) {
                for (JsonElement entry : o.getAsJsonArray("entries")) {
                    JsonObject e = entry.getAsJsonObject();
                    states.add(States.of(e.get("data")));
                    weights.add(e.get("weight").getAsInt());
                    sum += e.get("weight").getAsInt();
                }
            } else if (o.has("states")) {
                // Chosen by a noise in the modern game; here each state simply gets an equal share.
                for (JsonElement state : o.getAsJsonArray("states")) {
                    states.add(States.of(state));
                    weights.add(1);
                    sum++;
                }
            } else if (o.has("source")) {
                return parse(o.get("source"));
            } else {
                for (String key : new String[] {"default_state", "low_states", "high_states"}) {
                    if (o.has(key) && o.get(key).isJsonObject()) {
                        states.add(States.of(o.get(key)));
                        weights.add(1);
                        sum++;
                    } else if (o.has(key) && o.get(key).isJsonArray()) {
                        for (JsonElement state : o.getAsJsonArray(key)) {
                            states.add(States.of(state));
                            weights.add(1);
                            sum++;
                        }
                    }
                }
            }
            if (states.isEmpty()) {
                return fixed(null);
            }
            final int total = Math.max(1, sum);
            return new State() {
                @Override
                public IBlockState get(Random random) {
                    int roll = random.nextInt(total);
                    for (int i = 0; i < states.size(); i++) {
                        roll -= weights.get(i);
                        if (roll < 0) {
                            return states.get(i);
                        }
                    }
                    return states.get(states.size() - 1);
                }
            };
        }

        static State fixed(final IBlockState state) {
            return new State() {
                @Override
                public IBlockState get(Random random) {
                    return state;
                }

                @Override
                public boolean isNothing() {
                    return state == null || state.getBlock() == Blocks.AIR;
                }
            };
        }
    }
}
