package better112.cosmetic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import better112.config.BetterConfig;
import net.minecraft.block.Block;
import net.minecraft.block.BlockDirt;
import net.minecraft.block.BlockGrass;
import net.minecraft.block.BlockMycelium;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockFaceUV;
import net.minecraft.client.renderer.block.model.BlockPartFace;
import net.minecraft.client.renderer.block.model.FaceBakery;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.renderer.block.model.ItemOverrideList;
import net.minecraft.client.renderer.block.model.ModelRotation;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import org.lwjgl.util.vector.Vector3f;

/**
 * OptiFine's Better Grass: where a grass block's side faces down a slope of more grass, the side is drawn as
 * grass all the way down instead of dirt with a grass fringe, so hillsides read as one green surface. Fast
 * does this on every side. Mycelium and podzol get the same treatment.
 */
public final class BetterGrass {

    private static final FaceBakery BAKERY = new FaceBakery();
    /** The side quads made from each top texture, by horizontal index. */
    private static final Map<TextureAtlasSprite, BakedQuad[]> SIDES = new ConcurrentHashMap<TextureAtlasSprite, BakedQuad[]>();

    private BetterGrass() {}

    public static void clear() {
        SIDES.clear();
    }

    /** The model to draw this block with: the usual one, or one whose sides down a slope are all top. */
    public static IBakedModel model(IBakedModel model, IBlockState state, BlockPos pos, IBlockAccess world) {
        int mode = BetterConfig.betterGrass;
        Block block = state.getBlock();
        if (mode <= 0 || model == null || world == null || pos == null) {
            return model;
        }
        boolean podzol = block == Blocks.DIRT && state.getValue(BlockDirt.VARIANT) == BlockDirt.DirtType.PODZOL;
        if (block != Blocks.GRASS && block != Blocks.MYCELIUM && !podzol) {
            return model;
        }
        if (block == Blocks.GRASS ? state.getValue(BlockGrass.SNOWY) : block == Blocks.MYCELIUM ? state.getValue(BlockMycelium.SNOWY)
                : state.getValue(BlockDirt.SNOWY)) {
            return model; // snowy sides stay as they are
        }
        int mask = 0;
        for (EnumFacing side : EnumFacing.HORIZONTALS) {
            if (mode == 1 || same(world.getBlockState(pos.offset(side).down()), block, podzol)) {
                mask |= 1 << side.getHorizontalIndex();
            }
        }
        return mask == 0 ? model : new Sides(model, mask);
    }

    private static boolean same(IBlockState other, Block block, boolean podzol) {
        if (other.getBlock() != block) {
            return false;
        }
        return !podzol || other.getValue(BlockDirt.VARIANT) == BlockDirt.DirtType.PODZOL;
    }

    /** The top's texture laid on a whole side, tinted as the top is. */
    static BakedQuad side(IBakedModel model, IBlockState state, EnumFacing side) {
        TextureAtlasSprite top = null;
        int tint = -1;
        for (BakedQuad quad : model.getQuads(state, EnumFacing.UP, 0L)) {
            top = quad.getSprite();
            tint = quad.getTintIndex();
            if (tint >= 0) {
                break; // the tinted layer is the grass itself
            }
        }
        if (top == null) {
            return null;
        }
        BakedQuad[] made = SIDES.get(top);
        if (made == null) {
            made = new BakedQuad[4];
            for (EnumFacing face : EnumFacing.HORIZONTALS) {
                BlockPartFace part = new BlockPartFace(face, tint, "#top", new BlockFaceUV(new float[] {0.0F, 0.0F, 16.0F, 16.0F}, 0));
                made[face.getHorizontalIndex()] = BAKERY.makeBakedQuad(new Vector3f(0.0F, 0.0F, 0.0F), new Vector3f(16.0F, 16.0F, 16.0F),
                        part, top, face, ModelRotation.X0_Y0, null, false, true);
            }
            SIDES.put(top, made);
        }
        return made[side.getHorizontalIndex()];
    }

    /** The block's own model, with the chosen sides swapped for the top. */
    static final class Sides implements IBakedModel {
        private final IBakedModel model;
        private final int mask;

        Sides(IBakedModel model, int mask) {
            this.model = model;
            this.mask = mask;
        }

        @Override
        public List<BakedQuad> getQuads(IBlockState state, EnumFacing side, long rand) {
            if (side != null && side.getHorizontalIndex() >= 0 && (mask & 1 << side.getHorizontalIndex()) != 0) {
                BakedQuad quad = side(model, state, side);
                if (quad != null) {
                    return Collections.singletonList(quad);
                }
            }
            return model.getQuads(state, side, rand);
        }

        @Override
        public boolean isAmbientOcclusion() {
            return model.isAmbientOcclusion();
        }

        @Override
        public boolean isAmbientOcclusion(IBlockState state) {
            return model.isAmbientOcclusion(state);
        }

        @Override
        public boolean isGui3d() {
            return model.isGui3d();
        }

        @Override
        public boolean isBuiltInRenderer() {
            return model.isBuiltInRenderer();
        }

        @Override
        public TextureAtlasSprite getParticleTexture() {
            return model.getParticleTexture();
        }

        @SuppressWarnings("deprecation")
        @Override
        public ItemCameraTransforms getItemCameraTransforms() {
            return model.getItemCameraTransforms();
        }

        @Override
        public ItemOverrideList getOverrides() {
            return model.getOverrides();
        }
    }

    /** Self-test: how the block at this place would be drawn. */
    public static String testAt(IBlockAccess world, BlockPos pos) {
        IBlockState state = world.getBlockState(pos).getActualState(world, pos);
        IBakedModel base = net.minecraft.client.Minecraft.getMinecraft().getBlockRendererDispatcher().getModelForState(state);
        IBakedModel made = model(base, state, pos, world);
        List<String> sides = new ArrayList<String>();
        if (made instanceof Sides) {
            for (EnumFacing side : EnumFacing.HORIZONTALS) {
                if ((((Sides) made).mask & 1 << side.getHorizontalIndex()) != 0) {
                    sides.add(side.getName());
                }
            }
        }
        return state.getBlock().getRegistryName() + " at " + pos + ": grass sides " + sides;
    }
}
