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
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
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
        if (FMLLaunchHandler.side() == Side.CLIENT) {
            registerPatches();
        }
    }

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
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
        PATCHES.put("net.minecraftforge.client.ForgeHooksClient", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                MethodNode m = find(c, "(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;)I", "getSkyBlendColour");
                return returnIntIf("sky_color", m, "hasSkyColor", "skyColor");
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
                return cancelIf("particle_render", m, "cullParticle", "(Lnet/minecraft/client/particle/Particle;)Z", 0);
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
                changed |= patchDamageTilt(find(c, "(F)V", "hurtCameraEffect", "func_78482_e"));
                changed |= patchDistortion(find(c, "(FI)V", "setupCameraTransform", "func_78479_a"));
                // The interface is drawn from here on: the in-game overlay, then whatever screen is open.
                changed |= callAround("gui_scale_begin", find(c, "(FJ)V", "updateCameraAndRender", "func_181560_a"), true, "guiPassBegin",
                        "net/minecraft/client/renderer/EntityRenderer", "setupOverlayRendering", "func_78478_c");
                return changed;
            }
        });

        PATCHES.put("net.minecraft.client.renderer.RenderGlobal", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                boolean changed = cancelIf("sky", find(c, "(FI)V", "renderSky", "func_174976_a"), "noSky", "()Z");
                changed |= callAtHead("camera",
                        find(c, "(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/renderer/culling/ICamera;F)V",
                                "renderEntities", "func_180446_a"),
                        "captureCamera", "(Lnet/minecraft/client/renderer/culling/ICamera;)V", 2);
                return changed;
            }
        });

        PATCHES.put("net.minecraft.world.World", new ClassPatch() {
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

        PATCHES.put("net.minecraft.client.renderer.chunk.RenderChunk", new ClassPatch() {
            @Override
            public boolean apply(ClassNode c) {
                return patchRenderChunk(c);
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
                return patchBlockModel(find(c, "(Lnet/minecraft/block/state/IBlockState;Lnet/minecraft/util/math/BlockPos;"
                        + "Lnet/minecraft/world/IBlockAccess;Lnet/minecraft/client/renderer/BufferBuilder;)Z", "renderBlock", "func_175018_a"));
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
        if (m == null || !PatchState.enabled(id) || (m.access & (ACC_ABSTRACT | ACC_NATIVE)) != 0) {
            PatchState.failed(id);
            return false;
        }
        InsnList test = loadArgs(hookDesc, vars);
        test.add(new MethodInsnNode(INVOKESTATIC, HOOKS, hook, hookDesc, false));

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
