package better112.perf;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Small speed fixes for other mods' code, called from bytecode patches. Safe to load on a dedicated server: it
 * names no client classes.
 */
public final class ModFixes {

    private ModFixes() {}

    // ---------------------------------------------------------------- Underground Biomes

    /** isUBified answers per stone block, then per block state. Entries are never changed or removed. */
    private static final ConcurrentHashMap<Object, ConcurrentHashMap<Object, Boolean>> UBIFIED =
            new ConcurrentHashMap<Object, ConcurrentHashMap<Object, Boolean>>();
    private static volatile int ubifiedCount;

    public static boolean ubKnown(Object stone, Object state) {
        ConcurrentHashMap<Object, Boolean> byState = UBIFIED.get(stone);
        return byState != null && byState.containsKey(state);
    }

    public static boolean ubAnswer(Object stone, Object state) {
        return UBIFIED.get(stone).get(state);
    }

    public static void ubStore(boolean answer, Object stone, Object state) {
        if (ubifiedCount > 1 << 16) {
            return; // only block states that already exist reach here; this is far more than any pack has
        }
        ConcurrentHashMap<Object, Boolean> byState = UBIFIED.get(stone);
        if (byState == null) {
            ConcurrentHashMap<Object, Boolean> made = new ConcurrentHashMap<Object, Boolean>();
            byState = UBIFIED.putIfAbsent(stone, made);
            if (byState == null) {
                byState = made;
            }
        }
        if (byState.putIfAbsent(state, answer) == null) {
            ubifiedCount++;
        }
    }

    // ---------------------------------------------------------------- Underground Biomes, more

    /** getUBifiedOre answers per stone block, then ore state, then stone meta. Entries are never changed or removed. */
    private static final ConcurrentHashMap<Object, ConcurrentHashMap<Object, Object[]>> UB_ORES =
            new ConcurrentHashMap<Object, ConcurrentHashMap<Object, Object[]>>();

    public static Object ubOre(Object stone, int meta, Object oreState) {
        if (meta < 0 || meta > 15) {
            return null;
        }
        ConcurrentHashMap<Object, Object[]> byState = UB_ORES.get(stone);
        Object[] byMeta = byState == null ? null : byState.get(oreState);
        return byMeta == null ? null : byMeta[meta];
    }

    public static void ubOreStore(Object answer, Object stone, int meta, Object oreState) {
        if (answer == null || meta < 0 || meta > 15 || ubifiedCount > 1 << 16) {
            return;
        }
        ConcurrentHashMap<Object, Object[]> byState = UB_ORES.get(stone);
        if (byState == null) {
            ConcurrentHashMap<Object, Object[]> made = new ConcurrentHashMap<Object, Object[]>();
            byState = UB_ORES.putIfAbsent(stone, made);
            if (byState == null) {
                byState = made;
            }
        }
        Object[] byMeta = byState.get(oreState);
        if (byMeta == null) {
            Object[] made = new Object[16];
            byMeta = byState.putIfAbsent(oreState, made);
            if (byMeta == null) {
                byMeta = made;
                ubifiedCount++;
            }
        }
        byMeta[meta] = answer; // the same answer whichever thread writes it
    }

    /** Names of registry entries as text, kept: a block's or biome's name never changes. */
    private static final ConcurrentHashMap<Object, String> NAMES = new ConcurrentHashMap<Object, String>();

    public static String locationText(Object location) {
        String text = NAMES.get(location);
        if (text == null) {
            text = location.toString();
            if (NAMES.size() < 8192) {
                NAMES.put(location, text);
            }
        }
        return text;
    }

    // ---------------------------------------------------------------- biomes by number

    private static final net.minecraft.world.biome.Biome[] BIOMES = new net.minecraft.world.biome.Biome[256];

    /**
     * Biome.getBiome(id): the registry boxes the number for every biome above 127 (all mod and imported biomes);
     * chunks ask this for every biome lookup. Kept per number until the game remaps its ids.
     */
    public static net.minecraft.world.biome.Biome biomeById(int id) {
        if (id >= 0 && id < 256) {
            net.minecraft.world.biome.Biome biome = BIOMES[id];
            if (biome == null) {
                biome = net.minecraft.world.biome.Biome.REGISTRY.getObjectById(id);
                BIOMES[id] = biome;
            }
            return biome;
        }
        return net.minecraft.world.biome.Biome.REGISTRY.getObjectById(id);
    }

    /** The game remapped its ids (joining or leaving a world): biome numbers may mean other biomes now. */
    public static void idsRemapped() {
        java.util.Arrays.fill(BIOMES, null);
    }

    // ---------------------------------------------------------------- Astral Sorcery

    private static java.lang.reflect.Field astralChunks;
    private static boolean astralFailed;

    /**
     * True when no chunk next to the block's chunk (or the chunk itself) has starlight network data, so Astral's
     * search of the 27 sections around the block would find nothing. Any doubt answers false (Astral searches).
     */
    public static boolean astralNothingNear(Object buffer, net.minecraft.util.math.BlockPos pos) {
        if (buffer == null || pos == null || astralFailed) {
            return false;
        }
        try {
            java.lang.reflect.Field field = astralChunks;
            if (field == null) {
                field = buffer.getClass().getDeclaredField("chunkSortedData");
                field.setAccessible(true);
                astralChunks = field;
            }
            java.util.Map<?, ?> chunks = (java.util.Map<?, ?>) field.get(buffer);
            if (chunks == null) {
                return false;
            }
            if (chunks.isEmpty()) {
                return true;
            }
            if (chunks.size() > 64) {
                return false;
            }
            int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
            for (Object key : chunks.keySet()) {
                net.minecraft.util.math.ChunkPos chunk = (net.minecraft.util.math.ChunkPos) key;
                if (chunk.x >= cx - 1 && chunk.x <= cx + 1 && chunk.z >= cz - 1 && chunk.z <= cz + 1) {
                    return false;
                }
            }
            return true;
        } catch (java.util.ConcurrentModificationException e) {
            return false; // changed while being read: let Astral search this once
        } catch (Throwable t) {
            astralFailed = true;
            return false;
        }
    }

    // ---------------------------------------------------------------- names

    private static final ConcurrentHashMap<String, String> LOWER = new ConcurrentHashMap<String, String>();

    /** text.toLowerCase(locale) for the game's attribute map, kept per name (always asked with Locale.ROOT). */
    public static String lowerCase(String text, java.util.Locale locale) {
        if (locale != java.util.Locale.ROOT) {
            return text.toLowerCase(locale);
        }
        String lower = LOWER.get(text);
        if (lower == null) {
            lower = text.toLowerCase(locale);
            if (LOWER.size() < 4096) {
                LOWER.put(text, lower);
            }
        }
        return lower;
    }

    // ---------------------------------------------------------------- Industrial Foregoing

    private static final ConcurrentHashMap<java.util.UUID, String> UUID_TEXT = new ConcurrentHashMap<java.util.UUID, String>();

    /** uuid.toString(), kept: the same id always gives the same text. */
    public static String uuidText(java.util.UUID uuid) {
        String text = UUID_TEXT.get(uuid);
        if (text == null) {
            text = uuid.toString();
            if (UUID_TEXT.size() >= 8192) {
                UUID_TEXT.clear();
            }
            UUID_TEXT.put(uuid, text);
        }
        return text;
    }

    // ---------------------------------------------------------------- EnderIO

    private static volatile com.google.common.collect.ImmutableSet<?> enderioListeners;

    /** ImmutableSet.copyOf(EnderIO's tick listener entries), kept until the table changes. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static com.google.common.collect.ImmutableSet enderioListeners(java.util.Collection entries) {
        com.google.common.collect.ImmutableSet<?> copy = enderioListeners;
        if (copy == null) {
            copy = com.google.common.collect.ImmutableSet.copyOf(entries);
            enderioListeners = copy;
        }
        return copy;
    }

    /** Called at the head of every EnderIO method that adds, removes or clears tick listeners. */
    public static void enderioListenersChanged() {
        enderioListeners = null;
    }

    // ---------------------------------------------------------------- Alfheim

    /** Per thread, a ring of positions handed out in turn: a lookup nested up to 15 deep cannot reuse one in use. */
    private static final class NeighbourRing {
        final net.minecraft.util.math.BlockPos.MutableBlockPos[] positions = new net.minecraft.util.math.BlockPos.MutableBlockPos[16];
        int next;

        NeighbourRing() {
            for (int i = 0; i < positions.length; i++) {
                positions[i] = new net.minecraft.util.math.BlockPos.MutableBlockPos();
            }
        }
    }

    private static final ThreadLocal<NeighbourRing> NEIGHBOURS = new ThreadLocal<NeighbourRing>() {
        @Override
        protected NeighbourRing initialValue() {
            return new NeighbourRing();
        }
    };

    /** pos.offset(facing) for a light read that only reads the position (Alfheim's neighbour light). */
    public static net.minecraft.util.math.BlockPos neighbour(net.minecraft.util.math.BlockPos pos, net.minecraft.util.EnumFacing facing) {
        NeighbourRing ring = NEIGHBOURS.get();
        net.minecraft.util.math.BlockPos.MutableBlockPos at = ring.positions[ring.next];
        ring.next = (ring.next + 1) & 15;
        return at.setPos(pos.getX() + facing.getXOffset(), pos.getY() + facing.getYOffset(), pos.getZ() + facing.getZOffset());
    }

    // ---------------------------------------------------------------- pathfinding

    /** A ring of its own: nothing else takes from it, so the one position a path check holds is never handed out twice. */
    private static final ThreadLocal<NeighbourRing> PATH_POSITIONS = new ThreadLocal<NeighbourRing>() {
        @Override
        protected NeighbourRing initialValue() {
            return new NeighbourRing();
        }
    };

    /** new BlockPos(x, y, z) for WalkNodeProcessor.getPathNodeTypeRaw, which only reads it. */
    public static net.minecraft.util.math.BlockPos pathPos(int x, int y, int z) {
        NeighbourRing ring = PATH_POSITIONS.get();
        net.minecraft.util.math.BlockPos.MutableBlockPos at = ring.positions[ring.next];
        ring.next = (ring.next + 1) & 15;
        return at.setPos(x, y, z);
    }

    // ---------------------------------------------------------------- player inventory

    /**
     * InventoryPlayer.getStackInSlot over its list of inventories (main, armour, off hand): the same walk by index
     * instead of with a new iterator. Lists that cannot be read by index are walked as before.
     */
    @SuppressWarnings("unchecked")
    public static net.minecraft.item.ItemStack slotOf(java.util.List<?> inventories, int index) {
        java.util.List<net.minecraft.item.ItemStack> found = null;
        if (inventories instanceof java.util.RandomAccess) {
            for (int i = 0, n = inventories.size(); i < n; i++) {
                java.util.List<net.minecraft.item.ItemStack> list = (java.util.List<net.minecraft.item.ItemStack>) inventories.get(i);
                if (index < list.size()) {
                    found = list;
                    break;
                }
                index -= list.size();
            }
        } else {
            for (Object each : inventories) {
                java.util.List<net.minecraft.item.ItemStack> list = (java.util.List<net.minecraft.item.ItemStack>) each;
                if (index < list.size()) {
                    found = list;
                    break;
                }
                index -= list.size();
            }
        }
        return found == null ? net.minecraft.item.ItemStack.EMPTY : found.get(index);
    }

    // ---------------------------------------------------------------- text

    private static final int TEXTS = 128;
    private static final Object[] TEXT_KEYS = new Object[TEXTS];
    private static final String[] TEXT_VALUES = new String[TEXTS];
    private static int nextText;

    /**
     * A chat line's plain text, kept for the last 128 lines asked about. Chat lines are not changed once shown.
     * HammerLib asks for the last ten every frame. Called on the game's own thread only.
     */
    public static String unformattedText(Object component) {
        for (int i = 0; i < TEXTS; i++) {
            if (TEXT_KEYS[i] == component) {
                return TEXT_VALUES[i];
            }
        }
        String text = ((net.minecraft.util.text.ITextComponent) component).getUnformattedText();
        TEXT_KEYS[nextText] = component;
        TEXT_VALUES[nextText] = text;
        nextText = (nextText + 1) & (TEXTS - 1);
        return text;
    }

    private static final Object[] PLAIN_KEYS = new Object[TEXTS];
    private static final String[] PLAIN_VALUES = new String[TEXTS];
    private static int nextPlain;

    /**
     * TextFormatting.getTextWithoutFormattingCodes for the last 128 texts asked about, by the text object itself (a
     * text never changes, so the same object gives the same answer). Game thread only.
     */
    public static String stripFormatting(String text) {
        if (text == null) {
            return null;
        }
        for (int i = 0; i < TEXTS; i++) {
            if (PLAIN_KEYS[i] == text) {
                return PLAIN_VALUES[i];
            }
        }
        String plain = net.minecraft.util.text.TextFormatting.getTextWithoutFormattingCodes(text);
        PLAIN_KEYS[nextPlain] = text;
        PLAIN_VALUES[nextPlain] = plain;
        nextPlain = (nextPlain + 1) & (TEXTS - 1);
        return plain;
    }

    /** {@code text.substring(from, to).equals(other)} without copying the piece. */
    public static boolean regionEquals(String text, int from, int to, Object other) {
        if (from < 0 || to > text.length() || from > to) {
            return text.substring(from, to).equals(other); // throws as before
        }
        if (!(other instanceof String)) {
            return false;
        }
        String s = (String) other;
        return s.length() == to - from && text.regionMatches(from, s, 0, to - from);
    }

    /**
     * World.getChunk on the server thread, before the chunk provider's map: the chunk this world handed out last,
     * if it is the one asked for and still loaded. Mobs and items ask for the chunk of every block they look at,
     * mostly the one they asked for a moment before. As the provider's own lookup does, the chunk's queued
     * unload is called off.
     */
    public static net.minecraft.world.chunk.Chunk keptChunk(net.minecraft.world.World world, int x, int z) {
        Object kept = ((better112.core.ChunkKeeper) world).better112_keptChunk();
        if (kept == null) {
            return null;
        }
        net.minecraft.world.chunk.Chunk chunk = (net.minecraft.world.chunk.Chunk) kept;
        if (chunk.x != x || chunk.z != z || !chunk.isLoaded()) {
            return null;
        }
        net.minecraft.server.MinecraftServer server = world.getMinecraftServer();
        if (server == null || !server.isCallingFromMinecraftThread()) {
            return null;
        }
        chunk.unloadQueued = false;
        return chunk;
    }

    /** After World.getChunk: the chunk is kept for the next call, on a server world's own thread only. */
    public static void keepChunk(net.minecraft.world.chunk.Chunk chunk, net.minecraft.world.World world) {
        if (chunk == null || !(world instanceof net.minecraft.world.WorldServer) || !chunk.isLoaded()) {
            return;
        }
        net.minecraft.server.MinecraftServer server = world.getMinecraftServer();
        if (server != null && server.isCallingFromMinecraftThread()) {
            ((better112.core.ChunkKeeper) world).better112_keepChunk(chunk);
        }
    }

    private static java.lang.reflect.Field[] statArrays;

    /** The game's per-block and per-item stat tables (StatList's private arrays), or null if they cannot be read. */
    private static net.minecraft.stats.StatBase[][] statTables() {
        try {
            if (statArrays == null) {
                String[] names = {"field_188065_ae", "field_188066_af", "field_75929_E", "field_75930_F", "field_188067_ai", "field_188068_aj"};
                java.lang.reflect.Field[] fields = new java.lang.reflect.Field[names.length];
                for (int i = 0; i < names.length; i++) {
                    fields[i] = net.minecraftforge.fml.common.ObfuscationReflectionHelper.findField(net.minecraft.stats.StatList.class, names[i]);
                }
                statArrays = fields;
            }
            net.minecraft.stats.StatBase[][] tables = new net.minecraft.stats.StatBase[statArrays.length][];
            for (int i = 0; i < tables.length; i++) {
                tables[i] = (net.minecraft.stats.StatBase[]) statArrays[i].get(null);
            }
            return tables;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Forge's StatList.reinit, run on every registry remap (each world load and leave, and server start), takes
     * every per-block and per-item stat out of the list of all stats one at a time: a walk of the whole list for each,
     * tens of thousands times over in an expert pack. This takes them all out in one walk instead and empties the
     * tables, so the game's own loop that follows finds nothing left to do. A stat equals another of the same class
     * and name, so the first that many equal stats are removed, as one call after another removes them. Returns
     * false, and changes nothing, where a stat class compares itself some other way.
     */
    public static boolean removeStatsAtOnce() {
        net.minecraft.stats.StatBase[][] tables = statTables();
        if (tables == null) {
            return false;
        }
        java.util.List<net.minecraft.stats.StatBase> kept = statsRemoved(net.minecraft.stats.StatList.ALL_STATS, tables);
        if (kept == null) {
            return false;
        }
        net.minecraft.stats.StatList.ALL_STATS.clear();
        net.minecraft.stats.StatList.ALL_STATS.addAll(kept);
        for (net.minecraft.stats.StatBase[] table : tables) {
            java.util.Arrays.fill(table, null);
        }
        return true;
    }

    /** The list with the tables' stats taken out as ArrayList.remove would, one after another; null if that cannot be told. */
    public static java.util.List<net.minecraft.stats.StatBase> statsRemoved(java.util.List<net.minecraft.stats.StatBase> all,
            net.minecraft.stats.StatBase[][] tables) {
        java.util.Map<String, int[]> counts = new java.util.HashMap<String, int[]>();
        java.util.Map<Class<?>, Boolean> plain = new java.util.HashMap<Class<?>, Boolean>();
        for (net.minecraft.stats.StatBase[] table : tables) {
            for (net.minecraft.stats.StatBase stat : table) {
                if (stat == null) {
                    continue;
                }
                Boolean ok = plain.get(stat.getClass());
                if (ok == null) {
                    try {
                        ok = stat.getClass().getMethod("equals", Object.class).getDeclaringClass() == net.minecraft.stats.StatBase.class;
                    } catch (NoSuchMethodException e) {
                        ok = false;
                    }
                    plain.put(stat.getClass(), ok);
                }
                if (!ok) {
                    return null;
                }
                String key = stat.getClass().getName() + '\0' + stat.statId;
                int[] count = counts.get(key);
                if (count == null) {
                    counts.put(key, new int[] {1});
                } else {
                    count[0]++;
                }
            }
        }
        java.util.List<net.minecraft.stats.StatBase> kept = new java.util.ArrayList<net.minecraft.stats.StatBase>(all.size());
        for (net.minecraft.stats.StatBase stat : all) {
            int[] count = stat == null ? null : counts.get(stat.getClass().getName() + '\0' + stat.statId);
            if (count != null && count[0] > 0) {
                count[0]--;
            } else {
                kept.add(stat);
            }
        }
        return kept;
    }

    /** Self-test: the one-walk removal against the game's one-at-a-time removal, on copies; null when they agree. */
    public static String checkStatRemoval() {
        net.minecraft.stats.StatBase[][] tables = statTables();
        if (tables == null) {
            return "tables not read";
        }
        java.util.List<net.minecraft.stats.StatBase> slow = new java.util.ArrayList<net.minecraft.stats.StatBase>(net.minecraft.stats.StatList.ALL_STATS);
        int removed = 0;
        for (net.minecraft.stats.StatBase[] table : tables) {
            for (net.minecraft.stats.StatBase stat : table) {
                if (stat != null) {
                    slow.remove(stat);
                    removed++;
                }
            }
        }
        java.util.List<net.minecraft.stats.StatBase> fast = statsRemoved(net.minecraft.stats.StatList.ALL_STATS, tables);
        if (fast == null) {
            return "a stat class compares itself its own way";
        }
        if (fast.size() != slow.size()) {
            return "sizes " + fast.size() + " and " + slow.size();
        }
        for (int i = 0; i < fast.size(); i++) {
            if (fast.get(i) != slow.get(i)) {
                return "differ at " + i;
            }
        }
        return null;
    }

    private static Object recipeIndexOf;
    private static long recipeIndexPrint;
    private static java.util.Map<Object, java.util.Set<Object>> recipeIndex;

    /**
     * The crafting recipe names Thaumcraft looks through for an item's aspects. It walks every recipe in the game
     * (tens of thousands in an expert pack) for each item it works out, and uses only those that make that item:
     * these are those recipes' names, in the same order. The list is made again whenever the set of recipes changes.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static synchronized java.util.Set<?> thaumcraftRecipeNames(net.minecraft.util.registry.RegistryNamespaced registry,
            net.minecraft.item.ItemStack stack) {
        java.util.Set<?> all = registry.getKeys();
        if (stack == null) {
            return all;
        }
        try {
            long print = all.size();
            for (Object recipe : registry) {
                print = print * 31 + System.identityHashCode(recipe);
            }
            if (recipeIndex == null || recipeIndexOf != registry || recipeIndexPrint != print) {
                java.util.Map<Object, java.util.Set<Object>> index = new java.util.IdentityHashMap<Object, java.util.Set<Object>>();
                for (Object name : all) {
                    Object recipe = registry.getObject(name);
                    if (!(recipe instanceof net.minecraft.item.crafting.IRecipe)) {
                        continue;
                    }
                    net.minecraft.item.ItemStack out = ((net.minecraft.item.crafting.IRecipe) recipe).getRecipeOutput();
                    if (out == null || out.getItem() == null) {
                        continue;
                    }
                    java.util.Set<Object> names = index.get(out.getItem());
                    if (names == null) {
                        names = new java.util.LinkedHashSet<Object>();
                        index.put(out.getItem(), names);
                    }
                    names.add(name);
                }
                recipeIndex = index;
                recipeIndexOf = registry;
                recipeIndexPrint = print;
            }
            java.util.Set<Object> names = recipeIndex.get(stack.getItem());
            return names == null ? java.util.Collections.emptySet() : java.util.Collections.unmodifiableSet(names);
        } catch (RuntimeException e) {
            recipeIndex = null;
            return all;
        }
    }

    /**
     * Whether a node of JEI's search tree already holds an ingredient's number. JEI walks the whole list of the
     * node to find out, and a node for a common letter holds most of the 30,000 ingredients, so building the
     * search at game start takes seconds. The tree refuses a number smaller than one it already has, so each
     * list only grows: a number not smaller than the last can only be the last.
     */
    public static boolean jeiNodeHas(it.unimi.dsi.fastutil.ints.IntList data, int index) {
        int n = data.size();
        if (n == 0) {
            return false;
        }
        int last = data.getInt(n - 1);
        if (last <= index) {
            return last == index;
        }
        return data.contains(index);
    }

    // ---------------------------------------------------------------- data fixers

    /**
     * Same answer as {@code new ResourceLocation(id).equals(key)}, without making the name. Forge's data fixers make
     * one for every block entity, entity and fixer they check, for each chunk and structure loaded, and in a pack
     * with FermiumASM each new name also goes through a locked, game-wide text pool. Names with letters outside
     * ASCII take the original path, since lower-casing those can change their length.
     */
    public static boolean idIs(String id, net.minecraft.util.ResourceLocation key) {
        if (key == null) {
            return false;
        }
        int n = id.length();
        for (int i = 0; i < n; i++) {
            if (id.charAt(i) >= 128) {
                return new net.minecraft.util.ResourceLocation(id).equals(key);
            }
        }
        // as ResourceLocation.splitObjectName: "minecraft" unless a colon comes after at least two characters
        int colon = id.indexOf(':');
        int pathFrom = colon < 0 ? 0 : colon + 1;
        boolean space = colon > 1 ? sameLowerCase(id, 0, colon, key.getNamespace()) : "minecraft".equals(key.getNamespace());
        return space && sameLowerCase(id, pathFrom, n, key.getPath());
    }

    /** Self-test: idIs against the original comparison, for every entity and block entity name and variants of them. */
    public static String checkIds() {
        java.util.List<net.minecraft.util.ResourceLocation> keys = new java.util.ArrayList<net.minecraft.util.ResourceLocation>();
        keys.addAll(net.minecraft.entity.EntityList.getEntityNameList());
        for (java.lang.reflect.Field f : net.minecraft.tileentity.TileEntity.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) && net.minecraft.util.registry.RegistryNamespaced.class.isAssignableFrom(f.getType())) {
                try {
                    f.setAccessible(true);
                    for (Object key : ((net.minecraft.util.registry.RegistryNamespaced<?, ?>) f.get(null)).getKeys()) {
                        keys.add((net.minecraft.util.ResourceLocation) key);
                    }
                } catch (IllegalAccessException e) {
                    return "block entity names not read: " + e;
                }
            }
        }
        keys.add(null);
        java.util.List<String> ids = new java.util.ArrayList<String>(java.util.Arrays.asList("", ":", "::", "a:", ":b", "a:b", "ab:c",
                "minecraft:", "Minecraft:Chest", "x:minecraft:chest", "İtem", "chesté", " chest", "chest ", "Chest", "MobSpawner",
                "Furnace", "EntityHorse", "Item", "XPOrb", "MINECRAFT:CHEST", "minecraft:chest", "minecraft:Chest", "Σ", "minecraft:pig"));
        for (net.minecraft.util.ResourceLocation key : keys) {
            if (key == null) {
                continue;
            }
            String full = key.toString();
            ids.add(full);
            ids.add(key.getPath());
            ids.add(full.toUpperCase(java.util.Locale.ROOT));
            ids.add(key.getNamespace().toUpperCase(java.util.Locale.ROOT) + ":" + key.getPath());
            ids.add(key.getNamespace() + ":" + key.getPath() + "x");
            ids.add("x" + key.getNamespace() + ":" + key.getPath());
        }
        long pairs = 0, matches = 0, differ = 0;
        String first = null;
        for (String id : ids) {
            net.minecraft.util.ResourceLocation made = new net.minecraft.util.ResourceLocation(id);
            for (net.minecraft.util.ResourceLocation key : keys) {
                boolean old = made.equals(key);
                boolean now = idIs(id, key);
                pairs++;
                if (old) {
                    matches++;
                }
                if (old != now) {
                    differ++;
                    if (first == null) {
                        first = "'" + id + "' vs " + key + ": " + old + "/" + now;
                    }
                }
            }
        }
        return ids.size() + " ids x " + keys.size() + " names = " + pairs + " pairs, " + matches + " equal, " + differ + " differ"
                + (first == null ? "" : " (first: " + first + ")");
    }

    private static boolean sameLowerCase(String text, int from, int to, String other) {
        if (other == null || other.length() != to - from) {
            return false;
        }
        for (int i = from; i < to; i++) {
            char c = text.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                c += 'a' - 'A';
            }
            if (c != other.charAt(i - from)) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- precipitation height

    /**
     * Chunk.getPrecipitationHeight, with one position object for the walk down instead of a new one per block. The height
     * of a column is worked out the first time it is asked (and again after a block change there) by walking down from
     * the top of the chunk to the first block that stops movement or is liquid; rain and snow ask for every column near
     * the player every frame, and world generation for every column it places snow or ice on.
     */
    public static net.minecraft.util.math.BlockPos precipitationHeight(net.minecraft.world.chunk.Chunk chunk,
            net.minecraft.util.math.BlockPos pos, int[] heights) {
        int x = pos.getX(), z = pos.getZ();
        int column = (x & 15) | (z & 15) << 4;
        if (heights[column] == -999) {
            net.minecraft.util.math.BlockPos.MutableBlockPos at =
                    new net.minecraft.util.math.BlockPos.MutableBlockPos(x, chunk.getTopFilledSegment() + 15, z);
            int found = -1;
            while (at.getY() > 0 && found == -1) {
                net.minecraft.block.material.Material material = chunk.getBlockState(at).getMaterial();
                if (!material.blocksMovement() && !material.isLiquid()) {
                    at.setY(at.getY() - 1);
                } else {
                    found = at.getY() + 1;
                }
            }
            heights[column] = found;
        }
        return new net.minecraft.util.math.BlockPos(x, heights[column], z);
    }

    // ---------------------------------------------------------------- FermiumASM

    private static java.lang.reflect.Field domains, permissionName;
    private static final java.util.Set<Object> DOMAINS_SEEN = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Object, Boolean>());
    private static final java.util.Map<String, String> PERMISSION_NAMES = new java.util.HashMap<String, String>();

    /**
     * Does what FermiumASM's JavaFixes.run does, for the protection domains made since the last run only, and answers
     * true; false (when the class loader's domains cannot be read) leaves it to FermiumASM. FermiumASM goes through
     * every domain of the game's class loader on every world load, once per dimension, to make equal permission names
     * share one text; the loader makes a domain for every class it loads, so in Divine Journey 2 each run took about
     * 15 ms. Domains are only ever added and their permissions cannot change, so a domain once done stays done; the
     * names are shared through a table kept between runs.
     */
    public static synchronized boolean sharePermissionNames() {
        try {
            if (domains == null) {
                java.lang.reflect.Field f = java.security.SecureClassLoader.class.getDeclaredField("pdcache");
                f.setAccessible(true);
                java.lang.reflect.Field n = java.security.Permission.class.getDeclaredField("name");
                n.setAccessible(true);
                permissionName = n;
                domains = f;
            }
            Object map = domains.get(net.minecraft.launchwrapper.Launch.classLoader);
            if (!(map instanceof java.util.Map)) {
                return false;
            }
            java.util.List<Object> fresh = new java.util.ArrayList<Object>();
            synchronized (map) {
                for (Object domain : ((java.util.Map<?, ?>) map).values()) {
                    if (domain instanceof java.security.ProtectionDomain && DOMAINS_SEEN.add(domain)) {
                        fresh.add(domain);
                    }
                }
            }
            for (Object domain : fresh) {
                java.security.PermissionCollection permissions = ((java.security.ProtectionDomain) domain).getPermissions();
                if (permissions == null) {
                    continue;
                }
                for (java.util.Enumeration<java.security.Permission> e = permissions.elements(); e.hasMoreElements(); ) {
                    java.security.Permission permission = e.nextElement();
                    String name = permission.getName();
                    if (name == null) {
                        continue;
                    }
                    String shared = PERMISSION_NAMES.get(name);
                    if (shared == null) {
                        PERMISSION_NAMES.put(name, name);
                    } else if (shared != name) {
                        permissionName.set(permission, shared);
                    }
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Self-test: how many domains there are, and how many permission names exist as more than one copy of the text. */
    public static synchronized String permissionNamesInfo() {
        try {
            java.lang.reflect.Field f = java.security.SecureClassLoader.class.getDeclaredField("pdcache");
            f.setAccessible(true);
            Object map = f.get(net.minecraft.launchwrapper.Launch.classLoader);
            java.util.Map<String, java.util.Set<Object>> copies = new java.util.HashMap<String, java.util.Set<Object>>();
            int count = 0;
            synchronized (map) {
                for (Object domain : ((java.util.Map<?, ?>) map).values()) {
                    count++;
                    java.security.PermissionCollection permissions = ((java.security.ProtectionDomain) domain).getPermissions();
                    if (permissions == null) {
                        continue;
                    }
                    for (java.util.Enumeration<java.security.Permission> e = permissions.elements(); e.hasMoreElements(); ) {
                        String name = e.nextElement().getName();
                        if (name != null) {
                            java.util.Set<Object> same = copies.get(name);
                            if (same == null) {
                                copies.put(name, same = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Object, Boolean>()));
                            }
                            same.add(name);
                        }
                    }
                }
            }
            int extra = 0;
            for (java.util.Set<Object> same : copies.values()) {
                extra += same.size() - 1;
            }
            return count + " domains, " + copies.size() + " distinct names, " + extra + " extra copies";
        } catch (Throwable t) {
            return "not read: " + t;
        }
    }
}
