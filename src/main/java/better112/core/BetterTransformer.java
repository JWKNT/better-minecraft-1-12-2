package better112.core;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraftforge.fml.relauncher.FMLLaunchHandler;
import net.minecraftforge.fml.relauncher.Side;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Small, dependency-free bytecode patches. Each one only ever adds code at the head of a
 * method (or swaps a single value), so they sit alongside Mixin mods that target the same
 * methods. A patch that does not find what it expects is skipped and reported, never forced.
 */
public final class BetterTransformer implements IClassTransformer, Opcodes {

    private static final String HOOKS = "better112/Hooks";

    private interface ClassPatch {
        boolean apply(ClassNode node);
    }

    private static final Map<String, ClassPatch> PATCHES = new HashMap<String, ClassPatch>();

    static {
        ForgeFastPath.touch();
        registerCommonPatches();
        if (FMLLaunchHandler.side() == Side.CLIENT) {
            registerPatches();
        }
    }

    /** Adds a patch for a class; a class patched both for both sides and for the client gets both. */
    private static void addPatch(String className, final ClassPatch patch) {
        final ClassPatch before = PATCHES.get(className);
        if (before == null) {
            PATCHES.put(className, patch);
            return;
        }
        PATCHES.put(className, new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                boolean changed = before.apply(c);
                changed |= patch.apply(c);
                return changed;
            }
        });
    }

    /** Patches for code that runs on both sides: the server thread of a dedicated server or of single player. */
    private static void registerCommonPatches() {
        addPatch("net.minecraft.world.World", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                // Ray tracing through blocks without a new point object per block crossed or a new "missed" result
                // per block passed (perf.RayTrace): Mekanism traces 40 blocks every frame, mobs trace to see.
                MethodNode m = find(c, "(Lnet/minecraft/util/math/Vec3d;Lnet/minecraft/util/math/Vec3d;ZZZ)Lnet/minecraft/util/math/RayTraceResult;",
                        "rayTraceBlocks", "func_147447_a");
                if (m == null || !PatchState.enabled("ray_trace")) {
                    PatchState.failed("ray_trace");
                    return false;
                }
                InsnList tail = new InsnList();
                LabelNode resume = resumeLabel(m, tail);
                InsnList code = new InsnList();
                code.add(new MethodInsnNode(INVOKESTATIC, "better112/perf/RayTrace", "enabled", "()Z", false));
                code.add(new JumpInsnNode(IFEQ, resume));
                code.add(new VarInsnNode(ALOAD, 0));
                code.add(new VarInsnNode(ALOAD, 1));
                code.add(new VarInsnNode(ALOAD, 2));
                code.add(new VarInsnNode(ILOAD, 3));
                code.add(new VarInsnNode(ILOAD, 4));
                code.add(new VarInsnNode(ILOAD, 5));
                code.add(new MethodInsnNode(INVOKESTATIC, "better112/perf/RayTrace", "trace", "(Lnet/minecraft/world/World;"
                        + "Lnet/minecraft/util/math/Vec3d;Lnet/minecraft/util/math/Vec3d;ZZZ)Lnet/minecraft/util/math/RayTraceResult;", false));
                code.add(new InsnNode(ARETURN));
                code.add(tail);
                m.instructions.insert(code);
                PatchState.applied("ray_trace");
                return true;
            }
        });

        addPatch("net.minecraft.world.World", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                // Every block a mob or item looks at goes through getChunk, the chunk provider and its map, mostly
                // for the chunk asked for a moment before. On the server thread the last chunk is kept (perf.ModFixes).
                String id = "chunk_lookup";
                MethodNode m = find(c, "(II)Lnet/minecraft/world/chunk/Chunk;", "getChunk", "func_72964_e");
                if (m == null || !PatchState.enabled(id) || c.interfaces.contains("better112/core/ChunkKeeper")) {
                    PatchState.failed(id);
                    return false;
                }
                AbstractInsnNode answer = null;
                int returns = 0;
                for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
                    if (n.getOpcode() == ARETURN) {
                        answer = n;
                        returns++;
                    }
                }
                if (returns != 1) {
                    PatchState.failed(id);
                    return false;
                }
                String field = "better112_keptChunk";
                c.fields.add(new FieldNode(ACC_PUBLIC, field, "Ljava/lang/Object;", null, null));
                c.interfaces.add("better112/core/ChunkKeeper");
                MethodNode getter = new MethodNode(ACC_PUBLIC, "better112_keptChunk", "()Ljava/lang/Object;", null, null);
                getter.instructions.add(new VarInsnNode(ALOAD, 0));
                getter.instructions.add(new FieldInsnNode(GETFIELD, c.name, field, "Ljava/lang/Object;"));
                getter.instructions.add(new InsnNode(ARETURN));
                c.methods.add(getter);
                MethodNode setter = new MethodNode(ACC_PUBLIC, "better112_keepChunk", "(Ljava/lang/Object;)V", null, null);
                setter.instructions.add(new VarInsnNode(ALOAD, 0));
                setter.instructions.add(new VarInsnNode(ALOAD, 1));
                setter.instructions.add(new FieldInsnNode(PUTFIELD, c.name, field, "Ljava/lang/Object;"));
                setter.instructions.add(new InsnNode(RETURN));
                c.methods.add(setter);

                // kept = ModFixes.keptChunk(this, x, z); if (kept != null) return kept; ... the old body ... keepChunk(chunk, this)
                int kept = m.maxLocals;
                m.maxLocals++;
                InsnList tail = new InsnList();
                LabelNode miss = resumeLabel(m, tail);
                InsnList code = new InsnList();
                code.add(new VarInsnNode(ALOAD, 0));
                code.add(new VarInsnNode(ILOAD, 1));
                code.add(new VarInsnNode(ILOAD, 2));
                code.add(new MethodInsnNode(INVOKESTATIC, "better112/perf/ModFixes", "keptChunk",
                        "(Lnet/minecraft/world/World;II)Lnet/minecraft/world/chunk/Chunk;", false));
                code.add(new VarInsnNode(ASTORE, kept));
                code.add(new VarInsnNode(ALOAD, kept));
                code.add(new JumpInsnNode(IFNULL, miss));
                code.add(new VarInsnNode(ALOAD, kept));
                code.add(new InsnNode(ARETURN));
                code.add(tail);
                m.instructions.insert(code);
                InsnList store = new InsnList();
                store.add(new InsnNode(DUP));
                store.add(new VarInsnNode(ALOAD, 0));
                store.add(new MethodInsnNode(INVOKESTATIC, "better112/perf/ModFixes", "keepChunk",
                        "(Lnet/minecraft/world/chunk/Chunk;Lnet/minecraft/world/World;)V", false));
                m.instructions.insertBefore(answer, store);
                PatchState.applied(id);
                return true;
            }
        });

        addPatch("net.minecraft.block.Block", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                // Moving things ask each block around them for its collision box, placed at the block: a new box per
                // block, most of them thrown away at once. The largest single source of garbage on the server thread.
                MethodNode m = find(c, "(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/math/AxisAlignedBB;Ljava/util/List;"
                        + "Lnet/minecraft/util/math/AxisAlignedBB;)V", "addCollisionBoxToList", "func_185492_a");
                return cancelIfIn("collision_boxes", m, "better112/perf/Collisions", "add", "(Lnet/minecraft/util/math/BlockPos;"
                        + "Lnet/minecraft/util/math/AxisAlignedBB;Ljava/util/List;Lnet/minecraft/util/math/AxisAlignedBB;)Z", 0, 1, 2, 3);
            }
        });

        PATCHES.put("org.embeddedt.vintagefix.chunk.VintageChunkMap", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                // VintageFix puts its own table of loaded chunks in place of the game's, with the same fixed start of
                // 8,192 chunks (16,384 slots) that never shrinks, and walks all of it twice a frame for entities.
                return replaceIntConstant("chunk_table_vintagefix", find(c, "()V", "<init>"), 8192, 512);
            }
        });

        PATCHES.put("net.minecraft.entity.player.InventoryPlayer", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                // getStackInSlot walks the three inventory lists with a new iterator on every call; mods read every
                // slot of the player's inventory each frame (Botania's HUD) or each tick. Same walk, by index.
                return patchInventorySlot(find(c, "(I)Lnet/minecraft/item/ItemStack;", "getStackInSlot", "func_70301_a"));
            }
        });

        PATCHES.put("exterminatorjeff.undergroundbiomes.world.UBStoneReplacer", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                // ...and turns the name of every block (and every column's biome) into text to compare it.
                return replaceCallIn("ub_names", find(c, null, "replaceStoneInChunk"), INVOKEVIRTUAL, "net/minecraft/util/ResourceLocation",
                        "toString", "()Ljava/lang/String;", "better112/perf/ModFixes", "locationText", "(Ljava/lang/Object;)Ljava/lang/String;");
            }
        });

        PATCHES.put("thaumcraft.common.lib.crafting.ThaumcraftCraftingManager", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                // Thaumcraft works out an item's aspects from the recipes that make it, walking every recipe in the
                // game for each item: seconds of each game and server start. It is handed only the recipes that
                // make that item, in the same order.
                String id = "thaumcraft_recipes";
                MethodNode m = find(c, "(Lnet/minecraft/item/ItemStack;Ljava/util/ArrayList;)Lthaumcraft/api/aspects/AspectList;",
                        "generateTagsFromCraftingRecipes");
                MethodInsnNode keys = null;
                int found = 0;
                if (m != null && (m.access & ACC_STATIC) != 0) {
                    for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
                        if (n.getOpcode() == INVOKEVIRTUAL && ((MethodInsnNode) n).owner.equals("net/minecraft/util/registry/RegistryNamespaced")
                                && (((MethodInsnNode) n).name.equals("func_148742_b") || ((MethodInsnNode) n).name.equals("getKeys"))
                                && ((MethodInsnNode) n).desc.equals("()Ljava/util/Set;")) {
                            keys = (MethodInsnNode) n;
                            found++;
                        }
                    }
                }
                if (found != 1 || !PatchState.enabled(id)) {
                    PatchState.failed(id);
                    return false;
                }
                m.instructions.insertBefore(keys, new VarInsnNode(ALOAD, 0));
                m.instructions.set(keys, new MethodInsnNode(INVOKESTATIC, "better112/perf/ModFixes", "thaumcraftRecipeNames",
                        "(Lnet/minecraft/util/registry/RegistryNamespaced;Lnet/minecraft/item/ItemStack;)Ljava/util/Set;", false));
                PatchState.applied(id);
                return true;
            }
        });

        PATCHES.put("net.minecraft.stats.StatList", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                // On every registry remap (each world load and leave, and server start) Forge's reinit takes every
                // block and item stat out of the list of all stats one at a time, walking the list for each one.
                String id = "stat_removal";
                MethodNode m = find(c, "()V", "reinit");
                if (m == null || (m.access & ACC_STATIC) == 0 || !PatchState.enabled(id)) {
                    PatchState.failed(id);
                    return false;
                }
                InsnList code = new InsnList();
                code.add(new MethodInsnNode(INVOKESTATIC, "better112/perf/ModFixes", "removeStatsAtOnce", "()Z", false));
                code.add(new InsnNode(POP));
                m.instructions.insert(code);
                PatchState.applied(id);
                return true;
            }
        });

        addPatch("net.minecraft.world.chunk.Chunk", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                // The height rain and snow fall to, worked out per column by walking down from the top of the chunk with a
                // new position object per block (perf.ModFixes.precipitationHeight walks with one).
                String id = "precipitation_height";
                MethodNode m = find(c, "(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/util/math/BlockPos;", "getPrecipitationHeight",
                        "func_177440_h");
                FieldNode heights = null;
                for (FieldNode f : c.fields) {
                    if (f.desc.equals("[I") && (f.name.equals("field_76638_b") || f.name.equals("precipitationHeightMap"))) {
                        heights = f;
                    }
                }
                if (m == null || heights == null || (m.access & ACC_STATIC) != 0 || !PatchState.enabled(id)) {
                    PatchState.failed(id);
                    return false;
                }
                InsnList tail = new InsnList();
                resumeLabel(m, tail);
                InsnList code = new InsnList();
                code.add(new VarInsnNode(ALOAD, 0));
                code.add(new VarInsnNode(ALOAD, 1));
                code.add(new VarInsnNode(ALOAD, 0));
                code.add(new FieldInsnNode(GETFIELD, c.name, heights.name, heights.desc));
                code.add(new MethodInsnNode(INVOKESTATIC, "better112/perf/ModFixes", "precipitationHeight",
                        "(Lnet/minecraft/world/chunk/Chunk;Lnet/minecraft/util/math/BlockPos;[I)Lnet/minecraft/util/math/BlockPos;", false));
                code.add(new InsnNode(ARETURN));
                code.add(tail);
                m.instructions.insert(code);
                PatchState.applied(id);
                return true;
            }
        });

        PATCHES.put("net.minecraft.util.datafix.walkers.Filtered", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                // The data fixers check every block entity and entity of each chunk and structure loaded against each
                // fixer by making a name from its id and comparing it with the fixer's. The comparison is done
                // without making the name (perf.ModFixes.idIs gives the same answer).
                String id = "datafix_ids";
                MethodNode m = find(c, "(Lnet/minecraft/util/datafix/IDataFixer;Lnet/minecraft/nbt/NBTTagCompound;I)Lnet/minecraft/nbt/NBTTagCompound;",
                        "process", "func_188266_a");
                String rl = "net/minecraft/util/ResourceLocation";
                AbstractInsnNode made = null, init = null, equals = null;
                int news = 0, inits = 0;
                if (m != null) {
                    for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
                        if (n.getOpcode() == NEW && ((TypeInsnNode) n).desc.equals(rl)) {
                            made = n;
                            news++;
                        } else if (n.getOpcode() == INVOKESPECIAL && ((MethodInsnNode) n).owner.equals(rl)) {
                            init = n;
                            inits++;
                        }
                    }
                }
                if (news == 1 && inits == 1 && ((MethodInsnNode) init).desc.equals("(Ljava/lang/String;)V")) {
                    AbstractInsnNode self = init.getNext(), key = self == null ? null : self.getNext();
                    equals = key == null ? null : key.getNext();
                    boolean shape = made.getNext() != null && made.getNext().getOpcode() == DUP
                            && self.getOpcode() == ALOAD && ((VarInsnNode) self).var == 0
                            && key.getOpcode() == GETFIELD && ((FieldInsnNode) key).desc.equals("L" + rl + ";")
                            && equals != null && equals.getOpcode() == INVOKEVIRTUAL && ((MethodInsnNode) equals).owner.equals(rl)
                            && ((MethodInsnNode) equals).name.equals("equals") && ((MethodInsnNode) equals).desc.equals("(Ljava/lang/Object;)Z");
                    if (!shape) {
                        equals = null;
                    }
                }
                if (equals == null || !PatchState.enabled(id)) {
                    PatchState.failed(id);
                    return false;
                }
                m.instructions.remove(made.getNext());
                m.instructions.remove(made);
                m.instructions.remove(init);
                m.instructions.set(equals, new MethodInsnNode(INVOKESTATIC, "better112/perf/ModFixes", "idIs",
                        "(Ljava/lang/String;Lnet/minecraft/util/ResourceLocation;)Z", false));
                PatchState.applied(id);
                return true;
            }
        });

        PATCHES.put("mirror.normalasm.common.java.JavaFixes", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                // FermiumASM goes through every protection domain of the class loader (one per class loaded) on every
                // world load, once per dimension, to make equal permission names share one text. The same is done for
                // the domains made since the last run only (perf.ModFixes.sharePermissionNames).
                return cancelIfIn("fermium_permissions", find(c, "()V", "run"), "better112/perf/ModFixes", "sharePermissionNames", "()Z");
            }
        });

        addPatch("net.minecraft.world.biome.Biome", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                MethodNode m = find(c, "(I)Lnet/minecraft/world/biome/Biome;", "getBiome", "func_185357_a");
                if (m == null || !PatchState.enabled("biome_ids")) {
                    PatchState.failed("biome_ids");
                    return false;
                }
                InsnList tail = new InsnList();
                resumeLabel(m, tail);
                InsnList code = new InsnList();
                code.add(new VarInsnNode(ILOAD, 0));
                code.add(new MethodInsnNode(INVOKESTATIC, "better112/perf/ModFixes", "biomeById", "(I)Lnet/minecraft/world/biome/Biome;", false));
                code.add(new InsnNode(ARETURN));
                code.add(tail);
                m.instructions.insert(code);
                PatchState.applied("biome_ids");
                return true;
            }
        });

        PATCHES.put("hellfirepvp.astralsorcery.common.starlight.WorldNetworkHandler", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                // Astral Sorcery looks up the 27 chunk sections around every changed block in its starlight network
                // (27 new chunk positions and a list per block, for every block world generation places), though
                // almost everywhere no chunk nearby has any network: then it stops at once.
                String id = "astral_block_change";
                MethodNode m = find(c, "(Lnet/minecraft/util/math/BlockPos;)V", "informBlockChange");
                FieldNode buffer = null;
                for (FieldNode f : c.fields) {
                    if (f.desc.endsWith("/LightNetworkBuffer;")) {
                        buffer = f;
                    }
                }
                if (m == null || buffer == null || !PatchState.enabled(id) || (m.access & ACC_STATIC) != 0) {
                    PatchState.failed(id);
                    return false;
                }
                InsnList tail = new InsnList();
                LabelNode resume = resumeLabel(m, tail);
                InsnList code = new InsnList();
                code.add(new VarInsnNode(ALOAD, 0));
                code.add(new FieldInsnNode(GETFIELD, c.name, buffer.name, buffer.desc));
                code.add(new VarInsnNode(ALOAD, 1));
                code.add(new MethodInsnNode(INVOKESTATIC, "better112/perf/ModFixes", "astralNothingNear",
                        "(Ljava/lang/Object;Lnet/minecraft/util/math/BlockPos;)Z", false));
                code.add(new JumpInsnNode(IFEQ, resume));
                code.add(new InsnNode(RETURN));
                code.add(tail);
                m.instructions.insert(code);
                PatchState.applied(id);
                return true;
            }
        });

        PATCHES.put("crazypants.enderio.base.handler.ServerTickHandler", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                // EnderIO copies its table of tick listeners into a new hashed set twice every server tick (so that
                // listeners may come and go while it walks them); in a quiet base that copy was a third of the server
                // thread's work. The copy is kept until EnderIO adds, removes or clears a listener.
                String id = "enderio_tick_listeners";
                boolean changed = replaceCallIn(id, find(c, null, "onServerTick"), INVOKESTATIC, "com/google/common/collect/ImmutableSet",
                        "copyOf", "(Ljava/util/Collection;)Lcom/google/common/collect/ImmutableSet;", "better112/perf/ModFixes", "enderioListeners",
                        "(Ljava/util/Collection;)Lcom/google/common/collect/ImmutableSet;");
                if (!changed) {
                    return false;
                }
                int marked = 0;
                for (MethodNode m : c.methods) {
                    boolean touches = false;
                    for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
                        if (n.getOpcode() == INVOKEVIRTUAL && ((MethodInsnNode) n).owner.equals("java/util/IdentityHashMap")
                                && n.getPrevious() != null) {
                            String name = ((MethodInsnNode) n).name;
                            if (name.equals("put") || name.equals("remove") || name.equals("clear")) {
                                touches = true;
                            }
                        }
                    }
                    if (touches && !m.name.startsWith("lambda$") && !m.name.equals("<clinit>")) {
                        m.instructions.insert(new MethodInsnNode(INVOKESTATIC, "better112/perf/ModFixes", "enderioListenersChanged", "()V", false));
                        marked++;
                    }
                }
                if (marked == 0) {
                    PatchState.failed(id);
                }
                return true;
            }
        });

        PATCHES.put("net.minecraft.util.LowerStringMap", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                // Mob attributes are kept in a map that lower-cases every name it is asked about ("generic.maxHealth"
                // becomes a new text each time), several times per mob per tick. The lower-case text of a name is kept.
                boolean changed = false;
                for (MethodNode m : c.methods) {
                    boolean bridge = (m.access & (ACC_BRIDGE | ACC_SYNTHETIC)) != 0;
                    if (!bridge && (m.name.equals("get") || m.name.equals("containsKey") || m.name.equals("put") || m.name.equals("remove"))) {
                        changed |= replaceCallIn("attribute_names", m, INVOKEVIRTUAL, "java/lang/String", "toLowerCase",
                                "(Ljava/util/Locale;)Ljava/lang/String;", "better112/perf/ModFixes", "lowerCase",
                                "(Ljava/lang/String;Ljava/util/Locale;)Ljava/lang/String;");
                    }
                }
                return changed;
            }
        });

        PATCHES.put("net.minecraft.pathfinding.WalkNodeProcessor", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                // Mobs finding a path make a new position for every block they look at; the method only reads it.
                String id = "path_positions";
                MethodNode m = find(c, "(Lnet/minecraft/world/IBlockAccess;III)Lnet/minecraft/pathfinding/PathNodeType;",
                        "getPathNodeTypeRaw", "func_189553_b");
                if (m == null || !PatchState.enabled(id)) {
                    PatchState.failed(id);
                    return false;
                }
                AbstractInsnNode n = m.instructions.getFirst();
                while (n != null && n.getOpcode() < 0) {
                    n = n.getNext();
                }
                AbstractInsnNode dup = n == null ? null : n.getNext();
                AbstractInsnNode a = dup == null ? null : dup.getNext(), b = a == null ? null : a.getNext(), d = b == null ? null : b.getNext();
                AbstractInsnNode init = d == null ? null : d.getNext();
                if (n == null || n.getOpcode() != NEW || !((TypeInsnNode) n).desc.equals("net/minecraft/util/math/BlockPos")
                        || dup.getOpcode() != DUP || a.getOpcode() != ILOAD || b.getOpcode() != ILOAD || d.getOpcode() != ILOAD
                        || init.getOpcode() != INVOKESPECIAL || !((MethodInsnNode) init).desc.equals("(III)V")) {
                    PatchState.failed(id);
                    return false;
                }
                m.instructions.remove(n);
                m.instructions.remove(dup);
                m.instructions.set(init, new MethodInsnNode(INVOKESTATIC, "better112/perf/ModFixes", "pathPos",
                        "(III)Lnet/minecraft/util/math/BlockPos;", false));
                PatchState.applied(id);
                return true;
            }
        });

        ClassPatch alfheimNeighbours = new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                // Alfheim reads the light of each neighbour of blocks that take their light from their neighbours
                // (slabs, stairs, water...) through a new position object per neighbour: on chunk-building threads
                // and for every particle drawn. The light read only reads the position, so one is reused.
                MethodNode m = find(c, null, "alfheim$getLightFor");
                if (m == null) {
                    return false;
                }
                return replaceCallIn("alfheim_neighbours", m, INVOKEVIRTUAL, "net/minecraft/util/math/BlockPos", "func_177972_a|offset",
                        "(Lnet/minecraft/util/EnumFacing;)Lnet/minecraft/util/math/BlockPos;", "better112/perf/ModFixes", "neighbour",
                        "(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/EnumFacing;)Lnet/minecraft/util/math/BlockPos;");
            }
        };
        addPatch("dev.redstudio.alfheim.mixin.BlockMixin", alfheimNeighbours);
        addPatch("net.minecraft.block.Block", alfheimNeighbours);

        PATCHES.put("exterminatorjeff.undergroundbiomes.intermod.OresRegistry", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                // Underground Biomes asks "is this an ore I re-texture in this stone?" for every block of every new
                // chunk, by building a text key and looking it up in a map of texts: about 7% of the server thread
                // while players explore. The answer for a block state and a stone never changes, so it is kept;
                // the same goes for which of its ore blocks replaces an ore.
                boolean changed = memoizeUbOre(find(c, "(Lexterminatorjeff/undergroundbiomes/common/block/UBStone;I"
                        + "Lnet/minecraft/block/state/IBlockState;)Lnet/minecraft/block/state/IBlockState;", "getUBifiedOre"));
                changed |= memoizeUbified(find(c, "(Lnet/minecraft/block/Block;Lnet/minecraft/block/state/IBlockState;)Z", "isUBified"));
                return changed;
            }
        });
    }

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        ForgeFastPath.install();
        if (basicClass == null) {
            return null;
        }
        ClassPatch patch = PATCHES.get(transformedName);
        if (patch == null) {
            return basicClass;
        }
        try {
            ClassNode node = new ClassNode();
            new ClassReader(basicClass).accept(node, 0);
            if (!patch.apply(node)) {
                return basicClass;
            }
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            return writer.toByteArray();
        } catch (Throwable t) {
            System.err.println("[Better112] Patch for " + transformedName + " failed, leaving the class untouched: " + t);
            return basicClass;
        }
    }

    // ------------------------------------------------------------------------------------
    // Patch table
    // ------------------------------------------------------------------------------------

    private static void registerPatches() {
        PATCHES.put("net.minecraft.client.renderer.texture.TextureUtil", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                // Loading a texture from a picture file makes a 16 MB buffer whatever the picture's size; the buffer is
                // made only as large as the upload uses (perf.RenderArrays.uploadBufferSize).
                String id = "texture_upload_buffer";
                MethodNode m = find(c, "(Ljava/awt/image/BufferedImage;IIZZ)V", "uploadTextureImageSubImpl", "func_110993_a");
                AbstractInsnNode multiply = null;
                int arrays = 0;
                if (m != null) {
                    for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
                        if (n.getOpcode() == NEWARRAY && ((IntInsnNode) n).operand == T_INT) {
                            arrays++;
                            AbstractInsnNode before = n.getPrevious();
                            multiply = before != null && before.getOpcode() == IMUL ? before : null;
                        }
                    }
                }
                if (arrays != 1 || multiply == null || (m.access & ACC_STATIC) == 0 || !PatchState.enabled(id)) {
                    PatchState.failed(id);
                    return false;
                }
                m.instructions.insertBefore(multiply, new VarInsnNode(ALOAD, 0));
                m.instructions.set(multiply, new MethodInsnNode(INVOKESTATIC, "better112/perf/RenderArrays", "uploadBufferSize",
                        "(IILjava/awt/image/BufferedImage;)I", false));
                PatchState.applied(id);
                return true;
            }
        });

        addPatch("net.minecraftforge.client.ForgeHooksClient", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                MethodNode m = find(c, "(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;)I", "getSkyBlendColour");
                return returnIntIf("sky_color", m, "hasSkyColor", "skyColor");
            }
        });

        addPatch("net.minecraftforge.client.ForgeHooksClient", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                // Every item drawn (slots, hotbar, JEI's list, every frame) goes through renderLitItem, which gathers the
                // model's quads into a new list and each run of equally lit quads into another; both are kept and
                // emptied instead (perf.RenderArrays.litList).
                String id = "lit_item_lists";
                MethodNode m = find(c, "(Lnet/minecraft/client/renderer/RenderItem;Lnet/minecraft/client/renderer/block/model/IBakedModel;"
                        + "ILnet/minecraft/item/ItemStack;)V", "renderLitItem");
                java.util.List<AbstractInsnNode> made = new java.util.ArrayList<AbstractInsnNode>();
                java.util.List<AbstractInsnNode> returns = new java.util.ArrayList<AbstractInsnNode>();
                boolean shape = m != null;
                if (m != null) {
                    for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
                        if (n.getOpcode() == NEW && ((TypeInsnNode) n).desc.equals("java/util/ArrayList")) {
                            AbstractInsnNode dup = n.getNext(), init = dup == null ? null : dup.getNext();
                            shape &= dup != null && dup.getOpcode() == DUP && init != null && init.getOpcode() == INVOKESPECIAL
                                    && ((MethodInsnNode) init).owner.equals("java/util/ArrayList") && ((MethodInsnNode) init).desc.equals("()V");
                            made.add(n);
                        } else if (n.getOpcode() == RETURN) {
                            returns.add(n);
                        }
                    }
                }
                if (!shape || made.size() != 2 || returns.isEmpty() || !PatchState.enabled(id)) {
                    PatchState.failed(id);
                    return false;
                }
                for (int i = 0; i < 2; i++) {
                    AbstractInsnNode n = made.get(i), dup = n.getNext(), init = dup.getNext();
                    m.instructions.insertBefore(n, new InsnNode(i == 0 ? ICONST_0 : ICONST_1));
                    m.instructions.remove(n);
                    m.instructions.remove(dup);
                    m.instructions.set(init, new MethodInsnNode(INVOKESTATIC, "better112/perf/RenderArrays", "litList", "(I)Ljava/util/ArrayList;", false));
                }
                for (AbstractInsnNode r : returns) {
                    m.instructions.insertBefore(r, new MethodInsnNode(INVOKESTATIC, "better112/perf/RenderArrays", "litDone", "()V", false));
                }
                PatchState.applied(id);
                return true;
            }
        });

        PATCHES.put("net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                MethodNode m = find(c, "(Lnet/minecraft/tileentity/TileEntity;FI)V", "render", "func_180546_a");
                return cancelIf("tile_entity_render", m, "skipTileEntity", "(Lnet/minecraft/tileentity/TileEntity;)Z", 1);
            }
        });

        PATCHES.put("net.minecraft.client.renderer.entity.RenderManager", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                MethodNode m = find(c, "(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/renderer/culling/ICamera;DDD)Z",
                        "shouldRender", "func_178635_a");
                return cancelIf("entity_render", m, "cullEntity", "(Lnet/minecraft/entity/Entity;)Z", 1);
            }
        });

        PATCHES.put("net.minecraft.client.particle.Particle", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                MethodNode m = find(c, "(Lnet/minecraft/client/renderer/BufferBuilder;Lnet/minecraft/entity/Entity;FFFFFF)V",
                        "renderParticle", "func_180434_a");
                // Drawn without four new corner objects per particle per frame; inserted first so that the culling
                // check below ends up ahead of it.
                boolean changed = cancelIfIn("particle_corners", m, "net/minecraft/client/particle/Better112ParticleDraw", "draw",
                        "(Lnet/minecraft/client/particle/Particle;Lnet/minecraft/client/renderer/BufferBuilder;"
                                + "Lnet/minecraft/entity/Entity;FFFFFF)Z", 0, 1, 2, 3, 4, 5, 6, 7, 8);
                changed |= cancelIf("particle_render", m, "cullParticle", "(Lnet/minecraft/client/particle/Particle;)Z", 0);
                changed |= patchParticleStill(find(c, "(DDD)V", "move", "func_187110_a"));
                MethodNode light = find(c, "(F)I", "getBrightnessForRender", "func_189214_a");
                if (light != null && PatchState.enabled("particle_light")) {
                    InsnList tail = new InsnList();
                    resumeLabel(light, tail);
                    InsnList code = new InsnList();
                    code.add(new VarInsnNode(ALOAD, 0));
                    code.add(new MethodInsnNode(INVOKESTATIC, "net/minecraft/client/particle/Better112ParticleDraw", "light",
                            "(Lnet/minecraft/client/particle/Particle;)I", false));
                    code.add(new InsnNode(IRETURN));
                    code.add(tail);
                    light.instructions.insert(code);
                    PatchState.applied("particle_light");
                    changed = true;
                } else {
                    PatchState.failed("particle_light");
                }
                return changed;
            }
        });

        PATCHES.put("net.minecraft.client.particle.ParticleManager", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                boolean changed = cancelIf("particle_add",
                        find(c, "(Lnet/minecraft/client/particle/Particle;)V", "addEffect", "func_78873_a"),
                        "blockParticle", "(Lnet/minecraft/client/particle/Particle;)Z", 1);
                changed |= cancelIf("particle_block_break",
                        find(c, "(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/state/IBlockState;)V",
                                "addBlockDestroyEffects", "func_180533_a"),
                        "noBlockBreakParticles", "()Z");
                changed |= cancelIf("particle_block_hit",
                        find(c, "(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/EnumFacing;)V",
                                "addBlockHitEffects", "func_180532_a"),
                        "noBlockHitParticles", "()Z");
                return changed;
            }
        });

        PATCHES.put("net.minecraft.client.renderer.EntityRenderer", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                MethodNode rain = find(c, "(F)V", "renderRainSnow", "func_78474_d");
                boolean changed = cancelIf("weather", rain, "noWeather", "()Z");
                changed |= cancelIf("rain_splash", find(c, "()V", "addRainParticles", "func_78484_h"), "noRainSplash", "()Z");
                changed |= replaceFieldRead("weather_quality", rain, "net/minecraft/client/settings/GameSettings",
                        "fancyWeather", "(Lnet/minecraft/client/settings/GameSettings;)Z", "fancyGraphics", "field_74347_j");
                // While the quest book is open it paints over the whole window: the world behind it is not drawn.
                changed |= cancelIf("covered_world", find(c, "(FJ)V", "renderWorld", "func_78471_a"), "worldCovered", "()Z");
                changed |= patchDamageTilt(find(c, "(F)V", "hurtCameraEffect", "func_78482_e"));
                changed |= patchDistortion(find(c, "(FI)V", "setupCameraTransform", "func_78479_a"));
                // The interface is drawn from here on: the in-game overlay, then whatever screen is open.
                changed |= callAround("gui_scale_begin", find(c, "(FJ)V", "updateCameraAndRender", "func_181560_a"), true, "guiPassBegin",
                        "net/minecraft/client/renderer/EntityRenderer", "setupOverlayRendering", "func_78478_c");
                // The mod's shaders: light shafts once the world is drawn, before the hand.
                changed |= callAround("shader_hand", find(c, "(IFJ)V", "renderWorldPass", "func_175068_a"), false, "shaderBeforeHand",
                        "net/minecraftforge/client/ForgeHooksClient", "dispatchRenderLast");
                // The mod's shaders: bloom and the colour grade over the finished world, before the interface.
                changed |= callAround("shader_final", find(c, "(FJ)V", "updateCameraAndRender", "func_181560_a"), false, "shaderAfterWorld",
                        "net/minecraft/client/renderer/EntityRenderer", "renderWorld", "func_78471_a");
                return changed;
            }
        });

        PATCHES.put("net.minecraft.client.renderer.RenderGlobal", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                boolean changed = cancelIf("sky", find(c, "(FI)V", "renderSky", "func_174976_a"), "noSky", "()Z");
                // VintageFix's entity lister, when its mixin is already merged in by the time this patch runs.
                MethodNode lister = find(c, "()Ljava/util/Iterator;", "getLoadedEntityIterator");
                if (lister != null) {
                    changed |= replaceCallIn("entity_lists", lister, INVOKEVIRTUAL, "net/minecraft/world/chunk/Chunk",
                            "func_177429_s|getEntityLists", "()[Lnet/minecraft/util/ClassInheritanceMultiMap;", HOOKS, "entityListsToDraw",
                            "(Lnet/minecraft/world/chunk/Chunk;)[Lnet/minecraft/util/ClassInheritanceMultiMap;");
                }
                changed |= callAtHead("camera",
                        find(c, "(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/renderer/culling/ICamera;F)V",
                                "renderEntities", "func_180446_a"),
                        "captureCamera", "(Lnet/minecraft/client/renderer/culling/ICamera;)V", 2);
                // The mod's shaders take over the drawing of each terrain layer.
                MethodNode layer = find(c, "(Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I", "renderBlockLayer", "func_174977_a");
                if (callAtHead("shader_layer", layer, "shaderLayer", "(Lnet/minecraft/util/BlockRenderLayer;DLnet/minecraft/entity/Entity;)V", 1, 2, 5)) {
                    changed = true;
                    callBeforeReturns(layer, "shaderLayerEnd", "(Lnet/minecraft/util/BlockRenderLayer;)V", 1);
                }
                changed |= patchSkyTilt(find(c, "(FI)V", "renderSky", "func_174976_a"));
                return changed;
            }
        });

        addPatch("net.minecraft.world.World", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                boolean changed = cancelIf("stars", find(c, "(F)F", "getStarBrightness", "func_72880_h"), "noStars", "()Z");
                changed |= patchCombinedLight(c, "dynamic_light_world");
                return changed;
            }
        });

        PATCHES.put("net.minecraft.world.WorldProvider", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                MethodNode m = find(c, "()F", "getCloudHeight", "func_76571_f");
                if (m == null || !PatchState.enabled("cloud_height")) {
                    PatchState.failed("cloud_height");
                    return false;
                }
                InsnList tail = new InsnList();
                LabelNode resume = resumeLabel(m, tail);
                InsnList code = new InsnList();
                code.add(new MethodInsnNode(INVOKESTATIC, HOOKS, "overrideCloudHeight", "()Z", false));
                code.add(new JumpInsnNode(IFEQ, resume));
                code.add(new MethodInsnNode(INVOKESTATIC, HOOKS, "cloudHeight", "()F", false));
                code.add(new InsnNode(FRETURN));
                code.add(tail);
                m.instructions.insert(code);
                PatchState.applied("cloud_height");
                return true;
            }
        });

        PATCHES.put("net.minecraft.client.renderer.texture.TextureManager", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                MethodNode m = find(c, "(Lnet/minecraft/util/ResourceLocation;)V", "bindTexture", "func_110577_a");
                return rewriteArg("texture_bind", m, "remapTexture",
                        "(Lnet/minecraft/util/ResourceLocation;)Lnet/minecraft/util/ResourceLocation;", 1);
            }
        });

        PATCHES.put("net.minecraft.block.BlockLeaves", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                boolean changed = rewriteArg("leaves", find(c, "(Z)V", "setGraphicsLevel", "func_150122_b"), "leavesFancy", "(Z)Z", 1);
                changed |= cancelIf("leaves_cull",
                        find(c, "(Lnet/minecraft/block/state/IBlockState;Lnet/minecraft/world/IBlockAccess;"
                                + "Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/EnumFacing;)Z",
                                "shouldSideBeRendered", "func_176225_a"),
                        "cullLeafFace",
                        "(Lnet/minecraft/block/Block;Lnet/minecraft/world/IBlockAccess;Lnet/minecraft/util/math/BlockPos;"
                                + "Lnet/minecraft/util/EnumFacing;)Z",
                        0, 2, 3, 4);
                changed |= addLeavesLayer(c);
                return changed;
            }
        });

        PATCHES.put("net.minecraft.world.ChunkCache", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                return patchCombinedLight(c, "dynamic_light_chunks");
            }
        });

        PATCHES.put("net.minecraft.client.renderer.texture.TextureAtlasSprite", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                return patchSprite(c);
            }
        });

        PATCHES.put("net.minecraft.client.renderer.block.model.BakedQuad", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                return callAtHead("sprite_quads", find(c, "()[I", "getVertexData", "func_178209_a"),
                        "quadUsed", "(Lnet/minecraft/client/renderer/block/model/BakedQuad;)V", 0);
            }
        });

        PATCHES.put("net.minecraft.client.renderer.VboRenderList", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                // While a shader program draws the terrain, it places each chunk section itself (no matrix change per section).
                return cancelIf("shader_chunks", find(c, "(Lnet/minecraft/util/BlockRenderLayer;)V", "renderChunkLayer", "func_178001_a"),
                        "shaderDrawChunks", "(Lnet/minecraft/client/renderer/ChunkRenderContainer;Lnet/minecraft/util/BlockRenderLayer;)Z", 0, 1);
            }
        });

        PATCHES.put("net.minecraft.client.renderer.chunk.RenderChunk", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                boolean changed = patchRenderChunk(c);
                // A section with a new mesh may change what the shadow picture shows.
                changed |= callAtHead("shader_chunk_dirty", find(c, "(Lnet/minecraft/client/renderer/chunk/CompiledChunk;)V", "setCompiledChunk",
                        "func_178580_a"), "chunkCompiled", "(Lnet/minecraft/client/renderer/chunk/RenderChunk;)V", 0);
                // The section builder asks for BlockRenderLayer.values() for every block, and each call copies the array.
                changed |= cachedValues("chunk_layers", find(c, "(FFFLnet/minecraft/client/renderer/chunk/ChunkCompileTaskGenerator;)V",
                        "rebuildChunk", "func_178581_b"), "net/minecraft/util/BlockRenderLayer", "RENDER_LAYERS");
                return changed;
            }
        });

        PATCHES.put("net.minecraftforge.client.model.pipeline.ForgeBlockModelRenderer", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                // Forge's block model renderer goes through EnumFacing.values() for every block it draws.
                boolean changed = false;
                for (MethodNode m : c.methods) {
                    if (m.name.equals("render") && (m.access & ACC_STATIC) != 0) {
                        changed |= cachedValues("model_facings", m, "net/minecraft/util/EnumFacing", "FACINGS");
                    }
                }
                return changed;
            }
        });

        PATCHES.put("mcp.mobius.waila.overlay.DisplayUtil", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                // Hwyla holds its patterns compiled, but hands their text to String.replaceAll, which compiles them
                // again on every call: several times each tick while a tooltip is up.
                boolean changed = replaceCall("waila_patterns", find(c, "(Ljava/lang/String;)Ljava/lang/String;", "stripWailaSymbols"), INVOKEVIRTUAL,
                        "java/lang/String", "replaceAll", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", "replaceAll",
                        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
                // Its tooltip width splits each line with String.split, which also compiles the expression every frame.
                changed |= replaceCall("waila_split", find(c, null, "getDisplayWidth"), INVOKEVIRTUAL, "java/lang/String", "split",
                        "(Ljava/lang/String;)[Ljava/lang/String;", "split", "(Ljava/lang/String;Ljava/lang/String;)[Ljava/lang/String;");
                return changed;
            }
        });

        PATCHES.put("mezz.jei.suffixtree.Node", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                // JEI's search tree asks each node whether it already holds an ingredient by walking all of the
                // node's list: seconds of the game's start go to that while JEI builds its search.
                String id = "jei_search_tree";
                MethodNode m = find(c, "(I)Z", "contains");
                FieldNode data = null;
                for (FieldNode f : c.fields) {
                    if (f.name.equals("data") && f.desc.equals("Lit/unimi/dsi/fastutil/ints/IntList;")) {
                        data = f;
                    }
                }
                if (m == null || data == null || !PatchState.enabled(id) || (m.access & ACC_STATIC) != 0) {
                    PatchState.failed(id);
                    return false;
                }
                InsnList tail = new InsnList();
                resumeLabel(m, tail);
                InsnList code = new InsnList();
                code.add(new VarInsnNode(ALOAD, 0));
                code.add(new FieldInsnNode(GETFIELD, c.name, data.name, data.desc));
                code.add(new VarInsnNode(ILOAD, 1));
                code.add(new MethodInsnNode(INVOKESTATIC, "better112/perf/ModFixes", "jeiNodeHas", "(Lit/unimi/dsi/fastutil/ints/IntList;I)Z", false));
                code.add(new InsnNode(IRETURN));
                code.add(tail);
                m.instructions.insert(code);
                PatchState.applied(id);
                return true;
            }
        });

        PATCHES.put("net.minecraft.client.multiplayer.WorldClient", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                // Each tick the client sets doDaylightCycle again to the value it already has; the rule then tries
                // to read "true" as a number twice and throws two exceptions doing it.
                return replaceCall("daylight_rule", find(c, "(J)V", "setWorldTime", "func_72877_b"), INVOKEVIRTUAL,
                        "net/minecraft/world/GameRules", "func_82764_b|setOrCreateGameRule", "(Ljava/lang/String;Ljava/lang/String;)V",
                        "setRuleIfChanged", "(Lnet/minecraft/world/GameRules;Ljava/lang/String;Ljava/lang/String;)V");
            }
        });

        PATCHES.put("com.bewitchment.api.message.SyncExtendedPlayer$Handler", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                // Bewitchment applies a player sync on the client without checking that there is a player: one that
                // arrives just as you leave a server crashes the game. Without a player there is nothing to sync.
                MethodNode m = null;
                for (MethodNode each : c.methods) {
                    if (each.name.startsWith("lambda$onMessage$") && each.desc.equals("(Lcom/bewitchment/api/message/SyncExtendedPlayer;)V")) {
                        m = each;
                    }
                }
                return cancelIf("bewitchment_sync", m, "noClientPlayer", "()Z");
            }
        });

        PATCHES.put("journeymap.client.model.RegionImageSet", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                // JourneyMap copies a chunk's 16x16 piece of a 512x512 map image with getData(), which in Java 8
                // allocates a buffer as large as the whole image (about 1 MB) for a piece; three times per chunk drawn.
                MethodNode m = find(c, null, "getChunkImage");
                return replaceCall("journeymap_chunk_image", m, INVOKEVIRTUAL, "java/awt/image/BufferedImage", "getData",
                        "()Ljava/awt/image/Raster;", "pieceRaster", "(Ljava/awt/image/BufferedImage;)Ljava/awt/image/Raster;");
            }
        });

        PATCHES.put("com.zeitheron.hammercore.client.render.Render3D", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                // HammerLib looks for its authors' names in the last ten chat lines every frame by cutting a new piece
                // of text out at every letter: over a gigabyte of garbage in 90 seconds of play on a server.
                // It also rebuilds each line's text from its pieces every frame; a line's text does not change.
                MethodNode m = find(c, null, "rne");
                boolean changed = substringEquals("hammerlib_chat", m);
                changed |= replaceCallIn("hammerlib_chat_text", m, INVOKEINTERFACE, "net/minecraft/util/text/ITextComponent",
                        "func_150260_c|getUnformattedText", "()Ljava/lang/String;", "better112/perf/ModFixes", "unformattedText",
                        "(Ljava/lang/Object;)Ljava/lang/String;");
                return changed;
            }
        });

        PATCHES.put("vazkii.quark.client.feature.RenderItemsInChat", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                // Quark looks for item links in the twenty newest chat lines every frame, stripping the colour codes
                // from each piece of text with a regular expression; the pieces are the same texts frame after frame.
                return replaceCallIn("quark_chat", find(c, null, "renderSymbols"), INVOKESTATIC, "net/minecraft/util/text/TextFormatting",
                        "func_110646_a|getTextWithoutFormattingCodes", "(Ljava/lang/String;)Ljava/lang/String;", "better112/perf/ModFixes",
                        "stripFormatting", "(Ljava/lang/String;)Ljava/lang/String;");
            }
        });

        addPatch("net.minecraft.world.chunk.Chunk", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                // A public reading of the chunk's own "has ever held an entity" flag, for the entity lister below.
                String id = "chunk_entity_flag";
                FieldNode flag = null;
                for (FieldNode f : c.fields) {
                    if (f.desc.equals("Z") && (f.name.equals("field_76644_m") || f.name.equals("hasEntities"))) {
                        flag = f;
                    }
                }
                if (flag == null || !PatchState.enabled(id) || c.interfaces.contains("better112/core/EntityPresence")) {
                    PatchState.failed(id);
                    return false;
                }
                c.interfaces.add("better112/core/EntityPresence");
                MethodNode getter = new MethodNode(ACC_PUBLIC, "better112_hasEntities", "()Z", null, null);
                getter.instructions.add(new VarInsnNode(ALOAD, 0));
                getter.instructions.add(new FieldInsnNode(GETFIELD, c.name, flag.name, "Z"));
                getter.instructions.add(new InsnNode(IRETURN));
                c.methods.add(getter);
                PatchState.applied(id);
                return true;
            }
        });

        ClassPatch entityLister = new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                // VintageFix draws entities by listing every entity list of every loaded chunk (16 per chunk, twice a
                // frame); most chunks have never held an entity, and their lists are skipped without reading them.
                MethodNode m = find(c, "()Ljava/util/Iterator;", "getLoadedEntityIterator");
                if (m == null) {
                    return false;
                }
                return replaceCallIn("entity_lists", m, INVOKEVIRTUAL, "net/minecraft/world/chunk/Chunk", "func_177429_s|getEntityLists",
                        "()[Lnet/minecraft/util/ClassInheritanceMultiMap;", HOOKS, "entityListsToDraw",
                        "(Lnet/minecraft/world/chunk/Chunk;)[Lnet/minecraft/util/ClassInheritanceMultiMap;");
            }
        };
        PATCHES.put("org.embeddedt.vintagefix.mixin.bugfix.entity_disappearing.MixinRenderGlobal", entityLister);

        PATCHES.put("net.minecraftforge.client.model.PerspectiveMapWrapper", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                // Every item drawn (hundreds per frame with JEI's list open) recomputes its model's view transform
                // from the block-corner form, several new matrices each time; the answer per transform never changes.
                boolean changed = false;
                for (MethodNode m : c.methods) {
                    if (m.name.equals("handlePerspective") && (m.access & ACC_STATIC) != 0) {
                        changed |= replaceCallIn("item_transforms", m, INVOKESTATIC, "net/minecraftforge/common/model/TRSRTransformation",
                                "blockCornerToCenter", "(Lnet/minecraftforge/common/model/TRSRTransformation;)Lnet/minecraftforge/common/model/TRSRTransformation;",
                                HOOKS, "blockCornerToCenter",
                                "(Lnet/minecraftforge/common/model/TRSRTransformation;)Lnet/minecraftforge/common/model/TRSRTransformation;");
                    }
                }
                return changed;
            }
        });

        PATCHES.put("com.buuz135.industrial.item.infinity.OneThreeFiveHandler", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                // Industrial Foregoing turns the id of every mob within 32 blocks into text every other tick to look for
                // "135135" in it; an id's text never changes, so it is kept.
                return replaceCallIn("if_entity_ids", find(c, null, "lambda$onClientTick$0"), INVOKEVIRTUAL, "java/util/UUID", "toString",
                        "()Ljava/lang/String;", "better112/perf/ModFixes", "uuidText", "(Ljava/util/UUID;)Ljava/lang/String;");
            }
        });

        PATCHES.put("net.minecraft.tileentity.TileEntity", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                // Forge's default render box of a block entity (used to cull its special renderer) asks the block for
                // its shape and makes a new box, for every such block entity in view, every frame: with a wall of
                // Storage Drawers that was a tenth of the game thread. The box is kept while the block state, block
                // type and position it came from are unchanged.
                String id = "render_boxes";
                MethodNode m = find(c, "()Lnet/minecraft/util/math/AxisAlignedBB;", "getRenderBoundingBox");
                if (m == null || !PatchState.enabled(id) || c.interfaces.contains("better112/core/RenderBoxCache")) {
                    PatchState.failed(id);
                    return false;
                }
                AbstractInsnNode answer = null;
                int returns = 0;
                for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
                    if (n.getOpcode() == ARETURN) {
                        answer = n;
                        returns++;
                    }
                }
                if (returns != 1) {
                    PatchState.failed(id);
                    return false;
                }
                String field = "better112_renderBox";
                c.fields.add(new FieldNode(ACC_PUBLIC, field, "[Ljava/lang/Object;", null, null));
                c.interfaces.add("better112/core/RenderBoxCache");
                MethodNode getter = new MethodNode(ACC_PUBLIC, "better112_getRenderBox", "()[Ljava/lang/Object;", null, null);
                getter.instructions.add(new VarInsnNode(ALOAD, 0));
                getter.instructions.add(new FieldInsnNode(GETFIELD, c.name, field, "[Ljava/lang/Object;"));
                getter.instructions.add(new InsnNode(ARETURN));
                c.methods.add(getter);
                MethodNode setter = new MethodNode(ACC_PUBLIC, "better112_setRenderBox", "([Ljava/lang/Object;)V", null, null);
                setter.instructions.add(new VarInsnNode(ALOAD, 0));
                setter.instructions.add(new VarInsnNode(ALOAD, 1));
                setter.instructions.add(new FieldInsnNode(PUTFIELD, c.name, field, "[Ljava/lang/Object;"));
                setter.instructions.add(new InsnNode(RETURN));
                c.methods.add(setter);

                // kept = Hooks.keptRenderBox(this); if (kept != null) return kept; ... the old body ... keepRenderBox(box, this)
                int kept = m.maxLocals;
                m.maxLocals++;
                InsnList tail = new InsnList();
                LabelNode miss = resumeLabel(m, tail);
                InsnList code = new InsnList();
                code.add(new VarInsnNode(ALOAD, 0));
                code.add(new MethodInsnNode(INVOKESTATIC, HOOKS, "keptRenderBox", "(Lnet/minecraft/tileentity/TileEntity;)Lnet/minecraft/util/math/AxisAlignedBB;", false));
                code.add(new VarInsnNode(ASTORE, kept));
                code.add(new VarInsnNode(ALOAD, kept));
                code.add(new JumpInsnNode(IFNULL, miss));
                code.add(new VarInsnNode(ALOAD, kept));
                code.add(new InsnNode(ARETURN));
                code.add(tail);
                m.instructions.insert(code);
                InsnList store = new InsnList();
                store.add(new InsnNode(DUP));
                store.add(new VarInsnNode(ALOAD, 0));
                store.add(new MethodInsnNode(INVOKESTATIC, HOOKS, "keepRenderBox", "(Lnet/minecraft/util/math/AxisAlignedBB;Lnet/minecraft/tileentity/TileEntity;)V", false));
                m.instructions.insertBefore(answer, store);
                PatchState.applied(id);
                return true;
            }
        });

        PATCHES.put("net.minecraft.client.renderer.culling.ClippingHelper", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                // The view test of every entity, block entity and chunk tries all eight corners of a box against each
                // side of the view; the corner furthest along a side decides it, so only that one is tried.
                String id = "frustum_corners";
                MethodNode m = find(c, "(DDDDDD)Z", "isBoxInFrustum", "func_78553_b");
                FieldNode planes = null;
                for (FieldNode f : c.fields) {
                    if (f.desc.equals("[[F")) {
                        planes = f;
                    }
                }
                if (m == null || planes == null || !PatchState.enabled(id)) {
                    PatchState.failed(id);
                    return false;
                }
                InsnList tail = new InsnList();
                LabelNode resume = resumeLabel(m, tail);
                InsnList code = new InsnList();
                code.add(new FieldInsnNode(GETSTATIC, HOOKS, "frustumBypass", "Z"));
                code.add(new JumpInsnNode(IFNE, resume));
                code.add(new VarInsnNode(ALOAD, 0));
                code.add(new FieldInsnNode(GETFIELD, c.name, planes.name, "[[F"));
                for (int v = 1; v <= 11; v += 2) {
                    code.add(new VarInsnNode(DLOAD, v));
                }
                code.add(new MethodInsnNode(INVOKESTATIC, HOOKS, "boxInFrustum", "([[FDDDDDD)Z", false));
                code.add(new InsnNode(IRETURN));
                code.add(tail);
                m.instructions.insert(code);
                PatchState.applied(id);
                return true;
            }
        });

        PATCHES.put("net.minecraft.client.multiplayer.ChunkProviderClient", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                // The client's table of loaded chunks is made for 8,192 chunks (16,384 slots) and never shrinks, while
                // a render distance of 10 to 16 holds 400 to 1,100. VintageFix lists entities to draw by walking every
                // slot of it twice a frame. Started at 512 it grows to fit, as before, and walks a tenth of the slots.
                return replaceIntConstant("chunk_table", find(c, null, "<init>"), 8192, 512);
            }
        });

        PATCHES.put("net.minecraft.client.renderer.ChunkRenderContainer", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                MethodNode m = find(c, "(Lnet/minecraft/client/renderer/chunk/RenderChunk;Lnet/minecraft/util/BlockRenderLayer;)V",
                        "addRenderChunk", "func_178002_a");
                return callAtHead("sprite_visible", m, "chunkVisible",
                        "(Lnet/minecraft/client/renderer/chunk/RenderChunk;)V", 1);
            }
        });

        PATCHES.put("net.minecraft.client.renderer.chunk.ChunkRenderDispatcher", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                return patchChunkThreads(c);
            }
        });

        PATCHES.put("net.minecraft.client.gui.ScaledResolution", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                return patchGuiScale(c);
            }
        });

        PATCHES.put("net.minecraft.client.Minecraft", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                // ...and it is finished before the end-of-frame event, so every listener of that event sees
                // the window's real size. Tips re-hooks the loading screen whenever the size it sees changes
                // (one more framebuffer each time); seeing the doubled size there made that happen twice a
                // frame, and the chain overflowed the stack on the next loading screen (leaving a server).
                return callAround("gui_scale_end", find(c, "()V", "runGameLoop", "func_71411_J"), true, "guiPassEnd",
                        "net/minecraftforge/fml/common/FMLCommonHandler", "onRenderTickEnd");
            }
        });

        PATCHES.put("net.minecraft.client.gui.GuiNewChat", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                MethodNode m = find(c, "(II)Lnet/minecraft/util/text/ITextComponent;", "getChatComponent", "func_146236_a");
                boolean changed = rewriteArg("gui_scale_chat_x", m, "chatMouse", "(I)I", 1);
                changed |= rewriteArg("gui_scale_chat_y", m, "chatMouse", "(I)I", 2);
                return changed;
            }
        });

        PATCHES.put("net.minecraft.client.renderer.BlockRendererDispatcher", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                MethodNode render = find(c, "(Lnet/minecraft/block/state/IBlockState;Lnet/minecraft/util/math/BlockPos;"
                        + "Lnet/minecraft/world/IBlockAccess;Lnet/minecraft/client/renderer/BufferBuilder;)Z", "renderBlock", "func_175018_a");
                boolean changed = patchBlockModel(render);
                // The shaders' swaying plants: each block's vertices are marked with how far they may move.
                if (callAtHead("shader_sway", render, "swayBegin", "(Lnet/minecraft/client/renderer/BufferBuilder;)V", 4)) {
                    callBeforeReturns(render, "swayEnd", "(Lnet/minecraft/block/state/IBlockState;Lnet/minecraft/util/math/BlockPos;"
                            + "Lnet/minecraft/client/renderer/BufferBuilder;)V", 1, 2, 4);
                    changed = true;
                }
                return changed;
            }
        });

        PATCHES.put("net.minecraft.client.renderer.RenderItem", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                return patchItemGlint(find(c, "(Lnet/minecraft/client/renderer/block/model/IBakedModel;)V", "renderEffect", "func_191966_a"));
            }
        });

        PATCHES.put("net.minecraft.client.renderer.entity.layers.LayerArmorBase", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                return patchArmorGlint(find(c, null, "renderEnchantedGlint", "func_188364_a"));
            }
        });

        PATCHES.put("net.minecraft.world.biome.BiomeColorHelper", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                return patchBiomeBlend(c);
            }
        });
    }

    // ------------------------------------------------------------------------------------
    // Class-specific patches
    // ------------------------------------------------------------------------------------

    /** Gives every sprite a "last used" stamp and lets unused animations skip their upload. */
    private static boolean patchSprite(ClassNode c) {
        final String id = "sprite_animation";
        final String field = "better112_lastUse";
        MethodNode update = find(c, "()V", "updateAnimation", "func_94219_l");
        if (update == null || !PatchState.enabled(id)) {
            PatchState.failed(id);
            return false;
        }
        for (FieldNode f : c.fields) {
            if (f.name.equals(field)) {
                return false;
            }
        }
        c.fields.add(new FieldNode(ACC_PUBLIC, field, "I", null, null));
        c.interfaces.add("better112/core/SpriteAccess");

        MethodNode getter = new MethodNode(ACC_PUBLIC, "better112_getLastUse", "()I", null, null);
        getter.instructions.add(new VarInsnNode(ALOAD, 0));
        getter.instructions.add(new FieldInsnNode(GETFIELD, c.name, field, "I"));
        getter.instructions.add(new InsnNode(IRETURN));
        c.methods.add(getter);

        MethodNode setter = new MethodNode(ACC_PUBLIC, "better112_setLastUse", "(I)V", null, null);
        setter.instructions.add(new VarInsnNode(ALOAD, 0));
        setter.instructions.add(new VarInsnNode(ILOAD, 1));
        setter.instructions.add(new FieldInsnNode(PUTFIELD, c.name, field, "I"));
        setter.instructions.add(new InsnNode(RETURN));
        c.methods.add(setter);

        cancelIf(id, update, "skipAnimation", "(Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;)Z", 0);

        String[][] getters = {
            {"()F", "getMinU", "func_94209_e"}, {"()F", "getMaxU", "func_94212_f"},
            {"()F", "getMinV", "func_94206_g"}, {"()F", "getMaxV", "func_94210_h"},
            {"(D)F", "getInterpolatedU", "func_94214_a"}, {"(D)F", "getInterpolatedV", "func_94207_b"},
        };
        boolean all = true;
        for (String[] g : getters) {
            MethodNode m = find(c, g[0], g[1], g[2]);
            if (m == null) {
                all = false;
                continue;
            }
            InsnList code = new InsnList();
            code.add(new VarInsnNode(ALOAD, 0));
            code.add(new MethodInsnNode(INVOKESTATIC, HOOKS, "spriteUsed",
                    "(Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;)V", false));
            m.instructions.insert(code);
        }
        if (all) {
            PatchState.applied("sprite_getters");
        } else {
            PatchState.failed("sprite_getters");
        }
        return true;
    }

    /** Records which animated sprites each chunk mesh uses, so only visible ones animate. */
    private static boolean patchRenderChunk(ClassNode c) {
        final String id = "sprite_chunks";
        final String field = "better112_sprites";
        MethodNode rebuild = find(c, "(FFFLnet/minecraft/client/renderer/chunk/ChunkCompileTaskGenerator;)V",
                "rebuildChunk", "func_178581_b");
        if (rebuild == null || !PatchState.enabled(id)) {
            PatchState.failed(id);
            return false;
        }
        for (FieldNode f : c.fields) {
            if (f.name.equals(field)) {
                return false;
            }
        }
        c.fields.add(new FieldNode(ACC_PUBLIC | ACC_VOLATILE, field, "[Ljava/lang/Object;", null, null));
        c.interfaces.add("better112/core/ChunkAccess");

        MethodNode getter = new MethodNode(ACC_PUBLIC, "better112_getSprites", "()[Ljava/lang/Object;", null, null);
        getter.instructions.add(new VarInsnNode(ALOAD, 0));
        getter.instructions.add(new FieldInsnNode(GETFIELD, c.name, field, "[Ljava/lang/Object;"));
        getter.instructions.add(new InsnNode(ARETURN));
        c.methods.add(getter);

        MethodNode setter = new MethodNode(ACC_PUBLIC, "better112_setSprites", "([Ljava/lang/Object;)V", null, null);
        setter.instructions.add(new VarInsnNode(ALOAD, 0));
        setter.instructions.add(new VarInsnNode(ALOAD, 1));
        setter.instructions.add(new FieldInsnNode(PUTFIELD, c.name, field, "[Ljava/lang/Object;"));
        setter.instructions.add(new InsnNode(RETURN));
        c.methods.add(setter);

        final String desc = "(Lnet/minecraft/client/renderer/chunk/RenderChunk;)V";
        for (AbstractInsnNode n = rebuild.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n.getOpcode() == RETURN) {
                InsnList end = new InsnList();
                end.add(new VarInsnNode(ALOAD, 0));
                end.add(new MethodInsnNode(INVOKESTATIC, HOOKS, "endChunkBuild", desc, false));
                rebuild.instructions.insertBefore(n, end);
            }
        }
        InsnList begin = new InsnList();
        begin.add(new VarInsnNode(ALOAD, 0));
        begin.add(new MethodInsnNode(INVOKESTATIC, HOOKS, "beginChunkBuild", desc, false));
        rebuild.instructions.insert(begin);
        PatchState.applied(id);
        return true;
    }

    /**
     * Lets leaves draw in two layers at once (see-through outside, solid behind), which smart
     * leaves need. Block.canRenderInLayer is a Forge method, so its name is the same everywhere.
     */
    private static boolean addLeavesLayer(ClassNode c) {
        final String id = "leaves_layer";
        final String desc = "(Lnet/minecraft/block/state/IBlockState;Lnet/minecraft/util/BlockRenderLayer;)Z";
        if (!PatchState.enabled(id) || find(c, desc, "canRenderInLayer") != null) {
            PatchState.failed(id);
            return false;
        }
        MethodNode m = new MethodNode(ACC_PUBLIC, "canRenderInLayer", desc, null, null);
        m.instructions.add(new VarInsnNode(ALOAD, 0));
        m.instructions.add(new VarInsnNode(ALOAD, 1));
        m.instructions.add(new VarInsnNode(ALOAD, 2));
        m.instructions.add(new MethodInsnNode(INVOKESTATIC, HOOKS, "leavesInLayer",
                "(Lnet/minecraft/block/Block;Lnet/minecraft/block/state/IBlockState;Lnet/minecraft/util/BlockRenderLayer;)Z", false));
        m.instructions.add(new InsnNode(IRETURN));
        c.methods.add(m);
        PatchState.applied(id);
        return true;
    }

    /** Adds light from held and dropped light sources to every combined-light lookup. */
    private static boolean patchCombinedLight(ClassNode c, String id) {
        MethodNode m = find(c, "(Lnet/minecraft/util/math/BlockPos;I)I", "getCombinedLight", "func_175626_b");
        if (m == null || !PatchState.enabled(id) || (m.access & (ACC_ABSTRACT | ACC_NATIVE)) != 0) {
            PatchState.failed(id);
            return false;
        }
        boolean changed = false;
        for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n.getOpcode() == IRETURN) {
                InsnList code = new InsnList();
                code.add(new VarInsnNode(ALOAD, 1));
                code.add(new MethodInsnNode(INVOKESTATIC, HOOKS, "dynamicLight", "(ILnet/minecraft/util/math/BlockPos;)I", false));
                m.instructions.insertBefore(n, code);
                changed = true;
            }
        }
        if (changed) {
            PatchState.applied(id);
        } else {
            PatchState.failed(id);
        }
        return changed;
    }

    /** Makes the strength of the camera roll when hurt a setting. */
    private static boolean patchDamageTilt(MethodNode m) {
        final String id = "damage_tilt";
        if (m == null || !PatchState.enabled(id)) {
            PatchState.failed(id);
            return false;
        }
        for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n instanceof org.objectweb.asm.tree.LdcInsnNode && Float.valueOf(14.0F).equals(((org.objectweb.asm.tree.LdcInsnNode) n).cst)) {
                m.instructions.set(n, new MethodInsnNode(INVOKESTATIC, HOOKS, "damageTilt", "()F", false));
                PatchState.applied(id);
                return true;
            }
        }
        PatchState.failed(id);
        return false;
    }

    /** Makes the wobble of nausea and of standing in a Nether portal a setting (1.16's Distortion Effects). */
    private static boolean patchDistortion(MethodNode m) {
        final String id = "distortion";
        if (m == null || !PatchState.enabled(id)) {
            PatchState.failed(id);
            return false;
        }
        boolean read = false;
        for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n.getOpcode() == GETFIELD) {
                String name = ((FieldInsnNode) n).name;
                read |= name.equals("prevTimeInPortal") || name.equals("field_71080_cy");
            } else if (read && n.getOpcode() == FSTORE) {
                // The time in the portal, as the wobble reads it, goes through the setting first.
                m.instructions.insertBefore(n, new MethodInsnNode(INVOKESTATIC, HOOKS, "distortion", "(F)F", false));
                PatchState.applied(id);
                return true;
            }
        }
        PatchState.failed(id);
        return false;
    }

    /** Lets a block's model be swapped for the one place it is drawn at (Better Grass): the model goes through a hook. */
    private static boolean patchBlockModel(MethodNode m) {
        final String id = "block_model";
        if (m == null || !PatchState.enabled(id)) {
            PatchState.failed(id);
            return false;
        }
        for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n.getOpcode() == INVOKEVIRTUAL && ((MethodInsnNode) n).desc.equals(
                    "(Lnet/minecraft/block/state/IBlockState;)Lnet/minecraft/client/renderer/block/model/IBakedModel;")) {
                InsnList code = new InsnList();
                code.add(new VarInsnNode(ALOAD, 1)); // the block's actual state
                code.add(new VarInsnNode(ALOAD, 2)); // where it is
                code.add(new VarInsnNode(ALOAD, 3)); // the chunk being built
                code.add(new MethodInsnNode(INVOKESTATIC, HOOKS, "blockModel", "(Lnet/minecraft/client/renderer/block/model/IBakedModel;"
                        + "Lnet/minecraft/block/state/IBlockState;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/world/IBlockAccess;)"
                        + "Lnet/minecraft/client/renderer/block/model/IBakedModel;", false));
                m.instructions.insert(n, code);
                PatchState.applied(id);
                return true;
            }
        }
        PatchState.failed(id);
        return false;
    }

    /** 1.19's Glint Speed and Glint Strength for items: the glint's clock and colour go through the settings. */
    private static boolean patchItemGlint(MethodNode m) {
        final String id = "glint_item";
        if (m == null || !PatchState.enabled(id)) {
            PatchState.failed(id);
            return false;
        }
        int clocks = 0, colors = 0;
        for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n.getOpcode() == INVOKESTATIC && ((MethodInsnNode) n).desc.equals("()J")
                    && ((MethodInsnNode) n).owner.equals("net/minecraft/client/Minecraft")) {
                m.instructions.insert(n, new MethodInsnNode(INVOKESTATIC, HOOKS, "glintTime", "(J)J", false));
                n = n.getNext();
                clocks++;
            } else if (n instanceof org.objectweb.asm.tree.LdcInsnNode && Integer.valueOf(-8372020).equals(((org.objectweb.asm.tree.LdcInsnNode) n).cst)) {
                m.instructions.insert(n, new MethodInsnNode(INVOKESTATIC, HOOKS, "glintColor", "(I)I", false));
                n = n.getNext();
                colors++;
            }
        }
        if (clocks == 0 && colors == 0) {
            PatchState.failed(id);
            return false;
        }
        PatchState.applied(id);
        return true;
    }

    /** The same for the glint on worn armor, which runs on the wearer's age in ticks and has its own colour. */
    private static boolean patchArmorGlint(MethodNode m) {
        final String id = "glint_armor";
        if (m == null || !PatchState.enabled(id)) {
            PatchState.failed(id);
            return false;
        }
        boolean clock = false;
        int channels = 0;
        java.util.Set<Float> colour = new java.util.HashSet<Float>(java.util.Arrays.asList(0.38F, 0.19F, 0.608F));
        for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
            if (!clock && n.getOpcode() == FSTORE) {
                // The first float the method stores is its clock: ticks lived plus the partial tick.
                m.instructions.insertBefore(n, new MethodInsnNode(INVOKESTATIC, HOOKS, "glintArmorTime", "(F)F", false));
                clock = true;
            } else if (n instanceof org.objectweb.asm.tree.LdcInsnNode && colour.contains(((org.objectweb.asm.tree.LdcInsnNode) n).cst)) {
                m.instructions.insert(n, new MethodInsnNode(INVOKESTATIC, HOOKS, "glintArmorChannel", "(F)F", false));
                n = n.getNext();
                channels++;
            }
        }
        if (!clock && channels == 0) {
            PatchState.failed(id);
            return false;
        }
        PatchState.applied(id);
        return true;
    }

    /** Lets the menu choose how many chunk builder threads the game starts. */
    private static boolean patchChunkThreads(ClassNode c) {
        final String id = "chunk_threads";
        MethodNode init = find(c, "(I)V", "<init>");
        if (init == null || !PatchState.enabled(id)) {
            PatchState.failed(id);
            return false;
        }
        for (AbstractInsnNode n = init.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) n;
                if (call.owner.equals("java/lang/Runtime") && call.name.equals("availableProcessors")) {
                    init.instructions.set(n, new MethodInsnNode(INVOKESTATIC, HOOKS, "chunkThreads", "(Ljava/lang/Runtime;)I", false));
                    PatchState.applied(id);
                    return true;
                }
            }
        }
        PatchState.failed(id);
        return false;
    }

    /**
     * The game computes its GUI scale as a whole number. At the end of that computation the
     * mod is asked what the numbers should really be: for a half step, the width and height
     * of the screen in interface pixels at that scale, and the scale factor to report.
     */
    private static boolean patchGuiScale(ClassNode c) {
        final String id = "gui_scale";
        MethodNode init = find(c, "(Lnet/minecraft/client/Minecraft;)V", "<init>");
        if (init == null || !PatchState.enabled(id)) {
            PatchState.failed(id);
            return false;
        }
        // The class has two decimal fields (width, height) and three whole ones (width, height, factor), in that order.
        java.util.List<FieldNode> decimals = new java.util.ArrayList<FieldNode>(), wholes = new java.util.ArrayList<FieldNode>();
        for (FieldNode field : c.fields) {
            if ((field.access & ACC_STATIC) == 0 && field.desc.equals("D")) {
                decimals.add(field);
            } else if ((field.access & ACC_STATIC) == 0 && field.desc.equals("I")) {
                wholes.add(field);
            }
        }
        java.util.List<AbstractInsnNode> returns = new java.util.ArrayList<AbstractInsnNode>();
        for (AbstractInsnNode n = init.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n.getOpcode() == RETURN) {
                returns.add(n);
            }
        }
        if (decimals.size() != 2 || wholes.size() != 3 || returns.isEmpty()) {
            PatchState.failed(id);
            return false;
        }
        int values = init.maxLocals;
        init.maxLocals += 1;
        for (AbstractInsnNode ret : returns) {
            InsnList code = new InsnList();
            // Only "this" is read here: by the end of the constructor the game's own argument is no longer
            // a variable the class file vouches for, so the hook asks the game for the window size itself.
            code.add(new VarInsnNode(ALOAD, 0));
            code.add(new FieldInsnNode(GETFIELD, c.name, wholes.get(2).name, "I"));
            code.add(new MethodInsnNode(INVOKESTATIC, HOOKS, "guiScaleValues", "(I)[D", false));
            code.add(new VarInsnNode(ASTORE, values));
            FieldNode[] targets = {wholes.get(2), decimals.get(0), decimals.get(1), wholes.get(0), wholes.get(1)};
            for (int i = 0; i < targets.length; i++) {
                code.add(new VarInsnNode(ALOAD, 0));
                code.add(new VarInsnNode(ALOAD, values));
                code.add(new IntInsnNode(BIPUSH, i));
                code.add(new InsnNode(DALOAD));
                if (targets[i].desc.equals("I")) {
                    code.add(new InsnNode(D2I));
                }
                code.add(new FieldInsnNode(PUTFIELD, c.name, targets[i].name, targets[i].desc));
            }
            init.instructions.insertBefore(ret, code);
        }
        PatchState.applied(id);
        return true;
    }

    /** Inserts a call to a hook that takes nothing and gives nothing, before or after every call of one method. */
    private static boolean callAround(String id, MethodNode m, boolean before, String hook, String owner, String... names) {
        if (m == null || !PatchState.enabled(id)) {
            PatchState.failed(id);
            return false;
        }
        java.util.List<AbstractInsnNode> sites = new java.util.ArrayList<AbstractInsnNode>();
        for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n instanceof MethodInsnNode && ((MethodInsnNode) n).owner.equals(owner) && java.util.Arrays.asList(names).contains(((MethodInsnNode) n).name)) {
                sites.add(n);
            }
        }
        if (sites.isEmpty()) {
            PatchState.failed(id);
            return false;
        }
        for (AbstractInsnNode site : sites) {
            MethodInsnNode call = new MethodInsnNode(INVOKESTATIC, HOOKS, hook, "()V", false);
            if (before) {
                m.instructions.insertBefore(site, call);
            } else {
                m.instructions.insert(site, call);
            }
        }
        PatchState.applied(id);
        return true;
    }

    /** Before every return of a method: {@code Hooks.hook(args);} (no patch id of its own: it goes with a head patch). */
    private static void callBeforeReturns(MethodNode m, String hook, String hookDesc, int... vars) {
        for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
            int op = n.getOpcode();
            if (op >= IRETURN && op <= RETURN) {
                InsnList code = loadArgs(hookDesc, vars);
                code.add(new MethodInsnNode(INVOKESTATIC, HOOKS, hook, hookDesc, false));
                m.instructions.insertBefore(n, code);
            }
        }
    }

    /**
     * RenderGlobal.renderSky turns the sky by -90 degrees about Y, then by the time of day about X, before drawing the sun and
     * moon. A call in front of the first lets the shaders lean the sun's path (as their shadows do) by turning about X first.
     */
    private static boolean patchSkyTilt(MethodNode m) {
        String id = "sky_tilt";
        if (m == null || !PatchState.enabled(id)) {
            PatchState.failed(id);
            return false;
        }
        for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
            if (!(n instanceof LdcInsnNode) || !Float.valueOf(-90.0F).equals(((LdcInsnNode) n).cst)) {
                continue;
            }
            AbstractInsnNode a = n.getNext(), b = a == null ? null : a.getNext(), d = b == null ? null : b.getNext(), call = d == null ? null : d.getNext();
            if (a != null && a.getOpcode() == FCONST_0 && b.getOpcode() == FCONST_1 && d.getOpcode() == FCONST_0 && call instanceof MethodInsnNode
                    && ((MethodInsnNode) call).owner.equals("net/minecraft/client/renderer/GlStateManager")
                    && ((MethodInsnNode) call).desc.equals("(FFFF)V")) {
                m.instructions.insertBefore(n, new MethodInsnNode(INVOKESTATIC, HOOKS, "skyTilt", "()V", false));
                PatchState.applied(id);
                return true;
            }
        }
        PatchState.failed(id);
        return false;
    }

    /** Makes the fixed 3x3 biome colour blend a setting (1x1 up to 15x15). */
    private static boolean patchBiomeBlend(ClassNode c) {
        final String id = "biome_blend";
        MethodNode m = find(c, null, "getColorAtPos", "func_180285_a");
        if (m == null || !PatchState.enabled(id)) {
            PatchState.failed(id);
            return false;
        }
        java.util.List<AbstractInsnNode> neg = new java.util.ArrayList<AbstractInsnNode>();
        java.util.List<AbstractInsnNode> pos = new java.util.ArrayList<AbstractInsnNode>();
        java.util.List<AbstractInsnNode> area = new java.util.ArrayList<AbstractInsnNode>();
        boolean beforeBox = true;
        for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n instanceof MethodInsnNode && n.getOpcode() == INVOKESTATIC
                    && ((MethodInsnNode) n).owner.equals("net/minecraft/util/math/BlockPos")) {
                beforeBox = false;
            } else if (beforeBox && n.getOpcode() == ICONST_M1) {
                neg.add(n);
            } else if (beforeBox && n.getOpcode() == ICONST_1) {
                pos.add(n);
            } else if (!beforeBox && n.getOpcode() == BIPUSH && ((IntInsnNode) n).operand == 9) {
                area.add(n);
            }
        }
        if (neg.size() != 2 || pos.size() != 2 || area.size() != 3) {
            PatchState.failed(id);
            return false;
        }
        for (AbstractInsnNode n : neg) {
            m.instructions.set(n, new MethodInsnNode(INVOKESTATIC, HOOKS, "blendMin", "()I", false));
        }
        for (AbstractInsnNode n : pos) {
            m.instructions.set(n, new MethodInsnNode(INVOKESTATIC, HOOKS, "blendMax", "()I", false));
        }
        for (AbstractInsnNode n : area) {
            m.instructions.set(n, new MethodInsnNode(INVOKESTATIC, HOOKS, "blendArea", "()I", false));
        }
        PatchState.applied(id);
        return true;
    }

    // ------------------------------------------------------------------------------------
    // Building blocks
    // ------------------------------------------------------------------------------------

    private static MethodNode find(ClassNode c, String desc, String... names) {
        for (MethodNode m : c.methods) {
            if (desc != null && !m.desc.equals(desc)) {
                continue;
            }
            for (String name : names) {
                if (m.name.equals(name)) {
                    return m;
                }
            }
        }
        return null;
    }

    private static InsnList loadArgs(String hookDesc, int... vars) {
        InsnList code = new InsnList();
        Type[] args = Type.getArgumentTypes(hookDesc);
        for (int i = 0; i < args.length; i++) {
            code.add(new VarInsnNode(args[i].getOpcode(ILOAD), vars[i]));
        }
        return code;
    }

    /**
     * Appends a label to {@code code} that marks where the original method body resumes,
     * and returns it. Java 7+ class files need a stack map frame at every jump target; at the
     * head of a method that frame is simply "same as the method's initial frame".
     */
    private static LabelNode resumeLabel(MethodNode m, InsnList code) {
        AbstractInsnNode n = m.instructions.getFirst();
        LabelNode lastLabel = null;
        while (n != null && n.getOpcode() < 0) {
            if (n instanceof LabelNode) {
                lastLabel = (LabelNode) n;
            } else if (n instanceof FrameNode) {
                // The body already starts with a jump target; reuse its label and frame.
                if (lastLabel == null) {
                    lastLabel = new LabelNode();
                    m.instructions.insertBefore(n, lastLabel);
                }
                return lastLabel;
            }
            n = n.getNext();
        }
        LabelNode resume = new LabelNode();
        code.add(resume);
        code.add(new FrameNode(F_SAME, 0, null, 0, null));
        return resume;
    }

    /** Head of method: {@code if (Hooks.hook(args)) return <zero value>;} */
    private static boolean cancelIf(String id, MethodNode m, String hook, String hookDesc, int... vars) {
        return cancelIfIn(id, m, HOOKS, hook, hookDesc, vars);
    }

    /** As {@link #cancelIf}, with the hook in another class (one that is safe to load on a dedicated server). */
    private static boolean cancelIfIn(String id, MethodNode m, String owner, String hook, String hookDesc, int... vars) {
        if (m == null || !PatchState.enabled(id) || (m.access & (ACC_ABSTRACT | ACC_NATIVE)) != 0) {
            PatchState.failed(id);
            return false;
        }
        InsnList test = loadArgs(hookDesc, vars);
        test.add(new MethodInsnNode(INVOKESTATIC, owner, hook, hookDesc, false));

        InsnList tail = new InsnList();
        LabelNode resume = resumeLabel(m, tail);

        InsnList code = new InsnList();
        code.add(test);
        code.add(new JumpInsnNode(IFEQ, resume));
        Type ret = Type.getReturnType(m.desc);
        switch (ret.getSort()) {
            case Type.VOID:
                code.add(new InsnNode(RETURN));
                break;
            case Type.FLOAT:
                code.add(new InsnNode(FCONST_0));
                code.add(new InsnNode(FRETURN));
                break;
            case Type.DOUBLE:
                code.add(new InsnNode(DCONST_0));
                code.add(new InsnNode(DRETURN));
                break;
            case Type.LONG:
                code.add(new InsnNode(LCONST_0));
                code.add(new InsnNode(LRETURN));
                break;
            case Type.OBJECT:
            case Type.ARRAY:
                code.add(new InsnNode(ACONST_NULL));
                code.add(new InsnNode(ARETURN));
                break;
            default:
                code.add(new InsnNode(ICONST_0));
                code.add(new InsnNode(IRETURN));
                break;
        }
        code.add(tail);
        m.instructions.insert(code);
        PatchState.applied(id);
        return true;
    }

    /** Head of an int method: {@code if (Hooks.test()) return Hooks.value();} */
    private static boolean returnIntIf(String id, MethodNode m, String test, String value) {
        if (m == null || !PatchState.enabled(id) || (m.access & (ACC_ABSTRACT | ACC_NATIVE)) != 0) {
            PatchState.failed(id);
            return false;
        }
        InsnList tail = new InsnList();
        LabelNode resume = resumeLabel(m, tail);
        InsnList code = new InsnList();
        code.add(new MethodInsnNode(INVOKESTATIC, HOOKS, test, "()Z", false));
        code.add(new JumpInsnNode(IFEQ, resume));
        code.add(new MethodInsnNode(INVOKESTATIC, HOOKS, value, "()I", false));
        code.add(new InsnNode(IRETURN));
        code.add(tail);
        m.instructions.insert(code);
        PatchState.applied(id);
        return true;
    }

    /** Head of method: {@code Hooks.hook(args);} */
    private static boolean callAtHead(String id, MethodNode m, String hook, String hookDesc, int... vars) {
        if (m == null || !PatchState.enabled(id) || (m.access & (ACC_ABSTRACT | ACC_NATIVE)) != 0) {
            PatchState.failed(id);
            return false;
        }
        InsnList code = loadArgs(hookDesc, vars);
        code.add(new MethodInsnNode(INVOKESTATIC, HOOKS, hook, hookDesc, false));
        m.instructions.insert(code);
        PatchState.applied(id);
        return true;
    }

    /** Head of method: {@code arg = Hooks.hook(arg);} */
    private static boolean rewriteArg(String id, MethodNode m, String hook, String hookDesc, int var) {
        if (m == null || !PatchState.enabled(id) || (m.access & (ACC_ABSTRACT | ACC_NATIVE)) != 0) {
            PatchState.failed(id);
            return false;
        }
        Type type = Type.getReturnType(hookDesc);
        InsnList code = new InsnList();
        code.add(new VarInsnNode(type.getOpcode(ILOAD), var));
        code.add(new MethodInsnNode(INVOKESTATIC, HOOKS, hook, hookDesc, false));
        code.add(new VarInsnNode(type.getOpcode(ISTORE), var));
        m.instructions.insert(code);
        PatchState.applied(id);
        return true;
    }

    /** Swaps reads of one instance field for {@code Hooks.hook(owner)}. */
    /**
     * Every call of an enum's values() in the method becomes a read of a RenderArrays field that holds the same
     * array once: values() hands out a fresh copy on each call. Only for client methods that just read the array.
     */
    private static boolean cachedValues(String id, MethodNode m, String owner, String field) {
        if (m == null || !PatchState.enabled(id)) {
            PatchState.failed(id);
            return false;
        }
        String desc = "()[L" + owner + ";";
        boolean changed = false;
        for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n.getOpcode() == INVOKESTATIC && ((MethodInsnNode) n).owner.equals(owner) && ((MethodInsnNode) n).name.equals("values")
                    && ((MethodInsnNode) n).desc.equals(desc)) {
                FieldInsnNode read = new FieldInsnNode(GETSTATIC, "better112/perf/RenderArrays", field, "[L" + owner + ";");
                m.instructions.set(n, read);
                n = read;
                changed = true;
            }
        }
        if (changed) {
            PatchState.applied(id);
        } else {
            PatchState.failed(id);
        }
        return changed;
    }

    /**
     * Underground Biomes' isUBified(stone, state): after its "ore textures on?" setting check, answers from a table
     * kept in ModFixes when it can; the answer of the map lookup at its end is stored there on the way out.
     */
    private static boolean memoizeUbified(MethodNode m) {
        String id = "ub_ores";
        if (m == null || !PatchState.enabled(id)) {
            PatchState.failed(id);
            return false;
        }
        String owner = "better112/perf/ModFixes";
        // Where the setting check jumps to when it is on: the lookup starts there.
        LabelNode start = null;
        AbstractInsnNode answer = null;
        for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
            if (start == null && n.getOpcode() == IFNE && n.getPrevious() instanceof MethodInsnNode
                    && ((MethodInsnNode) n.getPrevious()).name.equals("ubifyOres")) {
                start = ((JumpInsnNode) n).label;
            }
            if (n.getOpcode() == IRETURN && n.getPrevious() instanceof MethodInsnNode
                    && ((MethodInsnNode) n.getPrevious()).name.equals("containsKey")) {
                answer = n;
            }
        }
        if (start == null || answer == null) {
            PatchState.failed(id);
            return false;
        }
        AbstractInsnNode at = start;
        while (at.getNext() != null && at.getNext().getOpcode() < 0) {
            at = at.getNext(); // past the label's line number and stack map frame
        }
        InsnList check = new InsnList();
        LabelNode miss = new LabelNode();
        check.add(new VarInsnNode(ALOAD, 1));
        check.add(new VarInsnNode(ALOAD, 2));
        check.add(new MethodInsnNode(INVOKESTATIC, owner, "ubKnown", "(Ljava/lang/Object;Ljava/lang/Object;)Z", false));
        check.add(new JumpInsnNode(IFEQ, miss));
        check.add(new VarInsnNode(ALOAD, 1));
        check.add(new VarInsnNode(ALOAD, 2));
        check.add(new MethodInsnNode(INVOKESTATIC, owner, "ubAnswer", "(Ljava/lang/Object;Ljava/lang/Object;)Z", false));
        check.add(new InsnNode(IRETURN));
        check.add(miss);
        check.add(new FrameNode(F_SAME, 0, null, 0, null));
        m.instructions.insert(at, check);
        InsnList store = new InsnList();
        store.add(new InsnNode(DUP));
        store.add(new VarInsnNode(ALOAD, 1));
        store.add(new VarInsnNode(ALOAD, 2));
        store.add(new MethodInsnNode(INVOKESTATIC, owner, "ubStore", "(ZLjava/lang/Object;Ljava/lang/Object;)V", false));
        m.instructions.insertBefore(answer, store);
        PatchState.applied(id);
        return true;
    }

    /** Underground Biomes' getUBifiedOre(stone, meta, oreState): answers kept in ModFixes, stored on the way out. */
    private static boolean memoizeUbOre(MethodNode m) {
        String id = "ub_ore_states";
        if (m == null || !PatchState.enabled(id)) {
            PatchState.failed(id);
            return false;
        }
        AbstractInsnNode answer = null;
        int returns = 0;
        for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n.getOpcode() == ARETURN) {
                answer = n;
                returns++;
            }
        }
        if (returns != 1) {
            PatchState.failed(id);
            return false;
        }
        String owner = "better112/perf/ModFixes", desc = "(Ljava/lang/Object;ILjava/lang/Object;)Ljava/lang/Object;";
        InsnList tail = new InsnList();
        LabelNode miss = resumeLabel(m, tail);
        InsnList code = new InsnList();
        code.add(new VarInsnNode(ALOAD, 1));
        code.add(new VarInsnNode(ILOAD, 2));
        code.add(new VarInsnNode(ALOAD, 3));
        code.add(new MethodInsnNode(INVOKESTATIC, owner, "ubOre", desc, false));
        code.add(new JumpInsnNode(IFNULL, miss));
        code.add(new VarInsnNode(ALOAD, 1));
        code.add(new VarInsnNode(ILOAD, 2));
        code.add(new VarInsnNode(ALOAD, 3));
        code.add(new MethodInsnNode(INVOKESTATIC, owner, "ubOre", desc, false));
        code.add(new TypeInsnNode(CHECKCAST, "net/minecraft/block/state/IBlockState"));
        code.add(new InsnNode(ARETURN));
        code.add(tail);
        m.instructions.insert(code);
        InsnList store = new InsnList();
        store.add(new InsnNode(DUP));
        store.add(new VarInsnNode(ALOAD, 1));
        store.add(new VarInsnNode(ILOAD, 2));
        store.add(new VarInsnNode(ALOAD, 3));
        store.add(new MethodInsnNode(INVOKESTATIC, owner, "ubOreStore", "(Ljava/lang/Object;Ljava/lang/Object;ILjava/lang/Object;)V", false));
        m.instructions.insertBefore(answer, store);
        PatchState.applied(id);
        return true;
    }

    /**
     * {@code a.substring(i, j).equals(b)}, where b is an argument read by one getter, becomes
     * {@code ModFixes.regionEquals(a, i, j, b)}: the same answer without copying the text.
     */
    private static boolean substringEquals(String id, MethodNode m) {
        if (m == null || !PatchState.enabled(id)) {
            PatchState.failed(id);
            return false;
        }
        boolean changed = false;
        for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
            if (!(n instanceof MethodInsnNode) || n.getOpcode() != INVOKEVIRTUAL) {
                continue;
            }
            MethodInsnNode sub = (MethodInsnNode) n;
            if (!sub.owner.equals("java/lang/String") || !sub.name.equals("substring") || !sub.desc.equals("(II)Ljava/lang/String;")) {
                continue;
            }
            AbstractInsnNode load = sub.getNext(), getter = load == null ? null : load.getNext(), eq = getter == null ? null : getter.getNext();
            if (load == null || load.getOpcode() != ALOAD || !(getter instanceof MethodInsnNode) || getter.getOpcode() != INVOKEVIRTUAL
                    || !((MethodInsnNode) getter).desc.equals("()Ljava/lang/String;") || !(eq instanceof MethodInsnNode)
                    || !((MethodInsnNode) eq).owner.equals("java/lang/String") || !((MethodInsnNode) eq).name.equals("equals")) {
                continue;
            }
            MethodInsnNode region = new MethodInsnNode(INVOKESTATIC, "better112/perf/ModFixes", "regionEquals",
                    "(Ljava/lang/String;IILjava/lang/Object;)Z", false);
            m.instructions.set(eq, region);
            m.instructions.remove(sub);
            n = region;
            changed = true;
        }
        if (changed) {
            PatchState.applied(id);
        } else {
            PatchState.failed(id);
        }
        return changed;
    }

    /**
     * Particle.move(0, 0, 0): the game still gathers the collision boxes around the particle and makes three moved
     * copies of its box, to move it by nothing. Underwater ambient particles never move (their speed is multiplied
     * by zero), and near water there are thousands, every tick. With all three distances exactly zero, the end of
     * move() is what remains: the position is read back from the box and the particle is not on the ground.
     * The two names are read from move() itself, which calls and sets nothing else of that shape.
     */
    private static boolean patchParticleStill(MethodNode m) {
        String id = "particle_still";
        if (m == null || !PatchState.enabled(id)) {
            PatchState.failed(id);
            return false;
        }
        String particle = "net/minecraft/client/particle/Particle";
        MethodInsnNode reset = null;
        FieldInsnNode ground = null;
        int resets = 0, grounds = 0;
        for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n.getOpcode() == INVOKEVIRTUAL && ((MethodInsnNode) n).owner.equals(particle) && ((MethodInsnNode) n).desc.equals("()V")) {
                reset = (MethodInsnNode) n;
                resets++;
            }
            if (n.getOpcode() == PUTFIELD && ((FieldInsnNode) n).owner.equals(particle) && ((FieldInsnNode) n).desc.equals("Z")) {
                ground = (FieldInsnNode) n;
                grounds++;
            }
        }
        if (resets != 1 || grounds != 1) {
            PatchState.failed(id);
            return false;
        }
        InsnList tail = new InsnList();
        LabelNode resume = resumeLabel(m, tail);
        InsnList code = new InsnList();
        for (int var = 1; var <= 5; var += 2) {
            code.add(new VarInsnNode(DLOAD, var));
            code.add(new InsnNode(DCONST_0));
            code.add(new InsnNode(DCMPL));
            code.add(new JumpInsnNode(IFNE, resume));
        }
        code.add(new VarInsnNode(ALOAD, 0));
        code.add(new MethodInsnNode(INVOKEVIRTUAL, particle, reset.name, "()V", false));
        code.add(new VarInsnNode(ALOAD, 0));
        code.add(new InsnNode(ICONST_0));
        code.add(new FieldInsnNode(PUTFIELD, particle, ground.name, "Z"));
        code.add(new InsnNode(RETURN));
        code.add(tail);
        m.instructions.insert(code);
        PatchState.applied(id);
        return true;
    }

    /** InventoryPlayer.getStackInSlot: {@code return ModFixes.slotOf(this.allInventories, index);} at its head. */
    private static boolean patchInventorySlot(MethodNode m) {
        String id = "inventory_slot";
        if (m == null || !PatchState.enabled(id)) {
            PatchState.failed(id);
            return false;
        }
        FieldInsnNode lists = null;
        int reads = 0;
        for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n.getOpcode() == GETFIELD && ((FieldInsnNode) n).desc.equals("Ljava/util/List;")
                    && ((FieldInsnNode) n).owner.equals("net/minecraft/entity/player/InventoryPlayer")) {
                lists = (FieldInsnNode) n;
                reads++;
            }
        }
        if (reads != 1) {
            PatchState.failed(id);
            return false;
        }
        // The old body stays behind the return; code after a return needs a stack map frame, which this provides.
        InsnList tail = new InsnList();
        resumeLabel(m, tail);
        InsnList code = new InsnList();
        code.add(new VarInsnNode(ALOAD, 0));
        code.add(new FieldInsnNode(GETFIELD, lists.owner, lists.name, lists.desc));
        code.add(new VarInsnNode(ILOAD, 1));
        code.add(new MethodInsnNode(INVOKESTATIC, "better112/perf/ModFixes", "slotOf",
                "(Ljava/util/List;I)Lnet/minecraft/item/ItemStack;", false));
        code.add(new InsnNode(ARETURN));
        code.add(tail);
        m.instructions.insert(code);
        PatchState.applied(id);
        return true;
    }

    /** Swaps a whole-number constant the method pushes (sipush or ldc) for another. */
    private static boolean replaceIntConstant(String id, MethodNode m, int from, int to) {
        if (m == null || !PatchState.enabled(id)) {
            PatchState.failed(id);
            return false;
        }
        boolean changed = false;
        for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
            boolean match = n.getOpcode() == SIPUSH && ((IntInsnNode) n).operand == from
                    || n.getOpcode() == LDC && Integer.valueOf(from).equals(((LdcInsnNode) n).cst);
            if (match) {
                AbstractInsnNode push = new LdcInsnNode(Integer.valueOf(to));
                m.instructions.set(n, push);
                n = push;
                changed = true;
            }
        }
        if (changed) {
            PatchState.applied(id);
        } else {
            PatchState.failed(id);
        }
        return changed;
    }

    /**
     * Every call of owner.name+desc in the method becomes a call of a static Hooks method that takes the same values.
     * The name may list the SRG and MCP names of a method, joined with |.
     */
    private static boolean replaceCall(String id, MethodNode m, int opcode, String owner, String name, String desc, String hook, String hookDesc) {
        return replaceCallIn(id, m, opcode, owner, name, desc, HOOKS, hook, hookDesc);
    }

    /** As {@link #replaceCall}, with the replacement in another class. */
    private static boolean replaceCallIn(String id, MethodNode m, int opcode, String owner, String name, String desc, String hookOwner,
            String hook, String hookDesc) {
        if (m == null || !PatchState.enabled(id)) {
            PatchState.failed(id);
            return false;
        }
        java.util.List<String> names = java.util.Arrays.asList(name.split("\\|"));
        boolean changed = false;
        for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n.getOpcode() == opcode && ((MethodInsnNode) n).owner.equals(owner) && names.contains(((MethodInsnNode) n).name)
                    && ((MethodInsnNode) n).desc.equals(desc)) {
                MethodInsnNode call = new MethodInsnNode(INVOKESTATIC, hookOwner, hook, hookDesc, false);
                m.instructions.set(n, call);
                n = call;
                changed = true;
            }
        }
        if (changed) {
            PatchState.applied(id);
        } else {
            PatchState.failed(id);
        }
        return changed;
    }

    private static boolean replaceFieldRead(String id, MethodNode m, String owner, String hook, String hookDesc, String... fieldNames) {
        if (m == null || !PatchState.enabled(id)) {
            PatchState.failed(id);
            return false;
        }
        boolean changed = false;
        for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n.getOpcode() != GETFIELD) {
                continue;
            }
            FieldInsnNode read = (FieldInsnNode) n;
            if (!read.owner.equals(owner)) {
                continue;
            }
            for (String name : fieldNames) {
                if (read.name.equals(name)) {
                    MethodInsnNode call = new MethodInsnNode(INVOKESTATIC, HOOKS, hook, hookDesc, false);
                    m.instructions.set(n, call);
                    n = call;
                    changed = true;
                    break;
                }
            }
        }
        if (changed) {
            PatchState.applied(id);
        } else {
            PatchState.failed(id);
        }
        return changed;
    }
}
