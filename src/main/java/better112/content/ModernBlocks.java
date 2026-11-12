package better112.content;

import java.util.Random;

import net.minecraft.block.Block;
import net.minecraft.block.BlockBush;
import net.minecraft.block.BlockButtonWood;
import net.minecraft.block.BlockDoor;
import net.minecraft.block.BlockFalling;
import net.minecraft.block.BlockFence;
import net.minecraft.block.BlockFenceGate;
import net.minecraft.block.BlockLeaves;
import net.minecraft.block.BlockPlanks;
import net.minecraft.block.BlockPressurePlate;
import net.minecraft.block.BlockRotatedPillar;
import net.minecraft.block.BlockStairs;
import net.minecraft.block.BlockTrapDoor;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.MapColor;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyEnum;
import net.minecraft.block.state.BlockFaceShape;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.IStringSerializable;
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * The 1.12.2 blocks that stand in for a modern mod's blocks. Where the old game has a class
 * for the job (stairs, doors, fences) it is used, so the block behaves exactly like its
 * vanilla cousin; slabs and tall plants get classes shaped like the modern ones, because
 * the modern model files expect those properties.
 */
final class ModernBlocks {

    private ModernBlocks() {}

    /** What a block is made of and how it handles: not in the modern data files, so judged from its kind and name. */
    static final class Traits {
        Material material = Material.ROCK;
        float hardness = 1.5F, resistance = 10.0F;
        SoundType sound = SoundType.STONE;
        String tool = "pickaxe";
        int light;
        /** Plants: also grows on sand, or on bare rock. */
        boolean sandPlant, rockPlant;
        /** Plants that stack on their own kind share a family name. */
        String family;
        /** How slippery to walk on, if not the usual (ice). */
        float slipperiness;
        /** Fallen leaves: lie on any firm ground, not only soil. */
        boolean litter;
    }

    private static void apply(Block block, Traits t) {
        block.setHardness(t.hardness);
        block.setResistance(t.resistance);
        block.setLightLevel(t.light / 15.0F);
        if (t.tool != null) {
            block.setHarvestLevel(t.tool, 0);
        }
    }

    // ------------------------------------------------------------------------------------

    static final class Cube extends Block {
        Cube(Traits t) {
            super(t.material);
            setSoundType(t.sound);
            apply(this, t);
        }
    }

    /** Sand: falls like sand. */
    static final class Falling extends BlockFalling {
        Falling(Traits t) {
            super(t.material);
            setSoundType(t.sound);
            apply(this, t);
        }
    }

    /** A log, or another block with a grain that follows how it was placed. */
    static final class Pillar extends BlockRotatedPillar {
        private final boolean wood;

        Pillar(Traits t) {
            super(t.material);
            this.wood = t.material == Material.WOOD;
            setSoundType(t.sound);
            apply(this, t);
        }

        // To the game a log is whatever says it holds leaves up; without this the leaves of every imported tree would wither.
        @Override
        public boolean canSustainLeaves(IBlockState state, IBlockAccess world, BlockPos pos) {
            return wood;
        }

        @Override
        public boolean isWood(IBlockAccess world, BlockPos pos) {
            return wood;
        }

        /** As a vanilla log does: tell the leaves around that their tree may be gone. */
        @Override
        public void breakBlock(World world, BlockPos pos, IBlockState state) {
            if (wood && world.isAreaLoaded(pos.add(-5, -5, -5), pos.add(5, 5, 5))) {
                for (BlockPos near : BlockPos.getAllInBox(pos.add(-4, -4, -4), pos.add(4, 4, 4))) {
                    IBlockState there = world.getBlockState(near);
                    if (there.getBlock().isLeaves(there, world, near)) {
                        there.getBlock().beginLeavesDecay(there, world, near);
                    }
                }
            }
            super.breakBlock(world, pos, state);
        }
    }

    static final class Leaves extends BlockLeaves {
        Item sapling;

        Leaves() {
            setDefaultState(blockState.getBaseState().withProperty(CHECK_DECAY, Boolean.TRUE).withProperty(DECAYABLE, Boolean.TRUE));
        }

        @Override
        protected BlockStateContainer createBlockState() {
            return new BlockStateContainer(this, CHECK_DECAY, DECAYABLE);
        }

        @Override
        public IBlockState getStateFromMeta(int meta) {
            return getDefaultState().withProperty(DECAYABLE, (meta & 4) == 0).withProperty(CHECK_DECAY, (meta & 8) > 0);
        }

        @Override
        public int getMetaFromState(IBlockState state) {
            return (state.getValue(DECAYABLE) ? 0 : 4) | (state.getValue(CHECK_DECAY) ? 8 : 0);
        }

        @Override
        public BlockPlanks.EnumType getWoodType(int meta) {
            return BlockPlanks.EnumType.OAK;
        }

        @Override
        public NonNullList<ItemStack> onSheared(ItemStack item, IBlockAccess world, BlockPos pos, int fortune) {
            return NonNullList.withSize(1, new ItemStack(this));
        }

        @Override
        public Item getItemDropped(IBlockState state, Random rand, int fortune) {
            return sapling != null ? sapling : Items.AIR;
        }

        @Override
        public int damageDropped(IBlockState state) {
            return 0;
        }

        // Fancy or fast follows the game's setting, which only the vanilla leaf blocks are told about.
        @Override
        public boolean isOpaqueCube(IBlockState state) {
            return Blocks.LEAVES.isOpaqueCube(Blocks.LEAVES.getDefaultState());
        }

        @Override
        @SideOnly(Side.CLIENT)
        public BlockRenderLayer getRenderLayer() {
            return Blocks.LEAVES.getRenderLayer();
        }

        @Override
        @SideOnly(Side.CLIENT)
        public boolean shouldSideBeRendered(IBlockState state, IBlockAccess world, BlockPos pos, EnumFacing side) {
            this.leavesFancy = !Blocks.LEAVES.isOpaqueCube(Blocks.LEAVES.getDefaultState());
            return super.shouldSideBeRendered(state, world, pos, side);
        }
    }

    /** A flower or grass tuft. */
    static class Plant extends BlockBush {
        private final Traits traits;
        private final boolean scatter;

        Plant(Traits t, boolean scatter) {
            super(Material.PLANTS);
            this.traits = t;
            this.scatter = scatter;
            setSoundType(SoundType.PLANT);
            setHardness(0.0F);
            setLightLevel(t.light / 15.0F);
        }

        @Override
        protected boolean canSustainBush(IBlockState below) {
            // A plant that grows in a column (high grass) also stands on another piece of itself.
            if (traits.family != null && below.getBlock() instanceof Plant && traits.family.equals(((Plant) below.getBlock()).traits.family)) {
                return true;
            }
            return soil(below, traits);
        }

        @Override
        public EnumOffsetType getOffsetType() {
            return scatter ? EnumOffsetType.XZ : EnumOffsetType.NONE;
        }
    }

    static boolean soil(IBlockState below, Traits t) {
        Material material = below.getMaterial();
        if (material == Material.GRASS || material == Material.GROUND || below.getBlock() == Blocks.FARMLAND) {
            return true;
        }
        if (t.sandPlant && material == Material.SAND) {
            return true;
        }
        return t.rockPlant && below.isFullCube() && (material == Material.ROCK || material == Material.SAND);
    }

    /** A plant two blocks tall. */
    static final class TallPlant extends BlockBush {
        enum Half implements IStringSerializable {
            LOWER, UPPER;

            @Override
            public String getName() {
                return this == LOWER ? "lower" : "upper";
            }
        }

        static final PropertyEnum<Half> HALF = PropertyEnum.create("half", Half.class);
        private final Traits traits;

        TallPlant(Traits t) {
            super(Material.PLANTS);
            this.traits = t;
            setSoundType(SoundType.PLANT);
            setHardness(0.0F);
            setLightLevel(t.light / 15.0F);
            setDefaultState(blockState.getBaseState().withProperty(HALF, Half.LOWER));
        }

        @Override
        protected BlockStateContainer createBlockState() {
            return new BlockStateContainer(this, HALF);
        }

        @Override
        public IBlockState getStateFromMeta(int meta) {
            return getDefaultState().withProperty(HALF, (meta & 1) == 1 ? Half.UPPER : Half.LOWER);
        }

        @Override
        public int getMetaFromState(IBlockState state) {
            return state.getValue(HALF) == Half.UPPER ? 1 : 0;
        }

        @Override
        public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess source, BlockPos pos) {
            return FULL_BLOCK_AABB;
        }

        @Override
        protected boolean canSustainBush(IBlockState below) {
            return soil(below, traits);
        }

        @Override
        public boolean canPlaceBlockAt(World world, BlockPos pos) {
            return super.canPlaceBlockAt(world, pos) && world.isAirBlock(pos.up());
        }

        @Override
        public boolean canBlockStay(World world, BlockPos pos, IBlockState state) {
            if (state.getBlock() != this) {
                return super.canBlockStay(world, pos, state);
            }
            if (state.getValue(HALF) == Half.UPPER) {
                return world.getBlockState(pos.down()).getBlock() == this;
            }
            return world.getBlockState(pos.up()).getBlock() == this && super.canBlockStay(world, pos, state);
        }

        @Override
        protected void checkAndDropBlock(World world, BlockPos pos, IBlockState state) {
            if (!canBlockStay(world, pos, state)) {
                boolean upper = state.getValue(HALF) == Half.UPPER;
                BlockPos top = upper ? pos : pos.up(), bottom = upper ? pos.down() : pos;
                if (world.getBlockState(top).getBlock() == this) {
                    world.setBlockState(top, Blocks.AIR.getDefaultState(), 2);
                }
                if (world.getBlockState(bottom).getBlock() == this) {
                    if (!upper) {
                        dropBlockAsItem(world, bottom, state, 0);
                    }
                    world.setBlockState(bottom, Blocks.AIR.getDefaultState(), 3);
                }
            }
        }

        /** Both halves at once, without the lower one noticing that the upper is not there yet. */
        void placeAt(World world, BlockPos lower, int flags) {
            world.setBlockState(lower, getDefaultState().withProperty(HALF, Half.LOWER), flags);
            world.setBlockState(lower.up(), getDefaultState().withProperty(HALF, Half.UPPER), flags);
        }

        @Override
        public void onBlockPlacedBy(World world, BlockPos pos, IBlockState state, EntityLivingBase placer, ItemStack stack) {
            world.setBlockState(pos.up(), getDefaultState().withProperty(HALF, Half.UPPER), 2);
        }

        @Override
        public void onBlockHarvested(World world, BlockPos pos, IBlockState state, EntityPlayer player) {
            if (state.getValue(HALF) == Half.UPPER && world.getBlockState(pos.down()).getBlock() == this) {
                // Breaking the top takes the bottom with it, and the bottom is the half that drops the plant.
                world.destroyBlock(pos.down(), !player.capabilities.isCreativeMode);
            }
            super.onBlockHarvested(world, pos, state, player);
        }

        @Override
        public Item getItemDropped(IBlockState state, Random rand, int fortune) {
            return state.getValue(HALF) == Half.UPPER ? Items.AIR : super.getItemDropped(state, rand, fortune);
        }
    }

    static final class Stairs extends BlockStairs {
        Stairs(IBlockState base, Traits t) {
            super(base);
            setSoundType(t.sound);
            apply(this, t);
        }
    }

    /**
     * A slab the way modern versions have it: one block that is a bottom half, a top half or
     * both. (1.12.2's own slabs are two blocks, and the modern model files would not fit them.)
     */
    static final class Slab extends Block {
        enum Type implements IStringSerializable {
            BOTTOM, TOP, DOUBLE;

            @Override
            public String getName() {
                return name().toLowerCase(java.util.Locale.ROOT);
            }
        }

        static final PropertyEnum<Type> TYPE = PropertyEnum.create("type", Type.class);
        private static final AxisAlignedBB BOTTOM_BOX = new AxisAlignedBB(0.0D, 0.0D, 0.0D, 1.0D, 0.5D, 1.0D);
        private static final AxisAlignedBB TOP_BOX = new AxisAlignedBB(0.0D, 0.5D, 0.0D, 1.0D, 1.0D, 1.0D);

        Slab(Traits t) {
            super(t.material);
            setSoundType(t.sound);
            apply(this, t);
            this.useNeighborBrightness = true;
            setLightOpacity(0);
            setDefaultState(blockState.getBaseState().withProperty(TYPE, Type.BOTTOM));
        }

        @Override
        protected BlockStateContainer createBlockState() {
            return new BlockStateContainer(this, TYPE);
        }

        @Override
        public IBlockState getStateFromMeta(int meta) {
            return getDefaultState().withProperty(TYPE, Type.values()[Math.min(2, meta & 3)]);
        }

        @Override
        public int getMetaFromState(IBlockState state) {
            return state.getValue(TYPE).ordinal();
        }

        private static boolean whole(IBlockState state) {
            return state.getValue(TYPE) == Type.DOUBLE;
        }

        @Override
        public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess source, BlockPos pos) {
            Type type = state.getValue(TYPE);
            return type == Type.DOUBLE ? FULL_BLOCK_AABB : type == Type.TOP ? TOP_BOX : BOTTOM_BOX;
        }

        @Override
        public boolean isOpaqueCube(IBlockState state) {
            // Asked once before the block has its states (by Block's constructor), when it has to say "no".
            return state.getBlock() == this && state.getPropertyKeys().contains(TYPE) && whole(state);
        }

        @Override
        public boolean isFullCube(IBlockState state) {
            return whole(state);
        }

        @Override
        public int getLightOpacity(IBlockState state, IBlockAccess world, BlockPos pos) {
            return whole(state) ? 255 : 0;
        }

        @Override
        public boolean isTopSolid(IBlockState state) {
            return state.getValue(TYPE) != Type.BOTTOM;
        }

        @Override
        public boolean isSideSolid(IBlockState state, IBlockAccess world, BlockPos pos, EnumFacing side) {
            Type type = state.getValue(TYPE);
            return type == Type.DOUBLE || (type == Type.TOP && side == EnumFacing.UP) || (type == Type.BOTTOM && side == EnumFacing.DOWN);
        }

        @Override
        public boolean doesSideBlockRendering(IBlockState state, IBlockAccess world, BlockPos pos, EnumFacing face) {
            return isSideSolid(state, world, pos, face);
        }

        @Override
        public BlockFaceShape getBlockFaceShape(IBlockAccess world, IBlockState state, BlockPos pos, EnumFacing face) {
            return isSideSolid(state, world, pos, face) ? BlockFaceShape.SOLID : BlockFaceShape.UNDEFINED;
        }

        @Override
        public IBlockState getStateForPlacement(World world, BlockPos pos, EnumFacing facing, float hitX, float hitY, float hitZ, int meta,
                EntityLivingBase placer) {
            boolean top = facing == EnumFacing.DOWN || (facing != EnumFacing.UP && hitY > 0.5F);
            return getDefaultState().withProperty(TYPE, top ? Type.TOP : Type.BOTTOM);
        }

        @Override
        public int quantityDropped(IBlockState state, int fortune, Random random) {
            return whole(state) ? 2 : 1;
        }
    }

    static final class Fence extends BlockFence {
        Fence(Traits t) {
            super(t.material, t.material == Material.WOOD ? MapColor.WOOD : MapColor.STONE);
            setSoundType(t.sound);
            apply(this, t);
        }
    }

    static final class FenceGate extends BlockFenceGate {
        FenceGate(Traits t) {
            super(BlockPlanks.EnumType.OAK);
            setSoundType(t.sound);
            apply(this, t);
        }
    }

    static final class Door extends BlockDoor {
        Item item;

        Door(Traits t) {
            super(t.material);
            setSoundType(t.sound);
            apply(this, t);
        }

        @Override
        public Item getItemDropped(IBlockState state, Random rand, int fortune) {
            return state.getValue(HALF) == EnumDoorHalf.UPPER || item == null ? Items.AIR : item;
        }

        @Override
        public ItemStack getItem(World world, BlockPos pos, IBlockState state) {
            return item == null ? ItemStack.EMPTY : new ItemStack(item);
        }
    }

    static final class TrapDoor extends BlockTrapDoor {
        TrapDoor(Traits t) {
            super(t.material);
            setSoundType(t.sound);
            apply(this, t);
        }
    }

    static final class Button extends BlockButtonWood {
        Button(Traits t) {
            setSoundType(t.sound);
            apply(this, t);
        }
    }

    static final class Plate extends BlockPressurePlate {
        Plate(Traits t) {
            super(t.material, t.material == Material.WOOD ? Sensitivity.EVERYTHING : Sensitivity.MOBS);
            setSoundType(t.sound);
            apply(this, t);
        }
    }

    /** Grows a tree where a sapling stands; set by the worldgen side, which knows the mod's trees. */
    interface TreeGrower {
        boolean grow(String sapling, World world, BlockPos pos, Random random);
    }

    /** A sapling: waits for light, then for a second nudge, then becomes its tree. Bone meal hurries it. */
    static final class Sapling extends BlockBush implements net.minecraft.block.IGrowable {
        static final net.minecraft.block.properties.PropertyInteger STAGE = net.minecraft.block.BlockSapling.STAGE;
        private static final AxisAlignedBB BOX = new AxisAlignedBB(0.1D, 0.0D, 0.1D, 0.9D, 0.8D, 0.9D);
        private final String name;
        private final Traits traits;

        Sapling(String name, Traits t) {
            super(Material.PLANTS);
            this.name = name;
            this.traits = t;
            setSoundType(SoundType.PLANT);
            setHardness(0.0F);
            setDefaultState(blockState.getBaseState().withProperty(STAGE, 0));
        }

        @Override
        protected BlockStateContainer createBlockState() {
            return new BlockStateContainer(this, STAGE);
        }

        @Override
        public IBlockState getStateFromMeta(int meta) {
            return getDefaultState().withProperty(STAGE, (meta & 8) >> 3);
        }

        @Override
        public int getMetaFromState(IBlockState state) {
            return state.getValue(STAGE) << 3;
        }

        @Override
        public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess source, BlockPos pos) {
            return BOX;
        }

        @Override
        protected boolean canSustainBush(IBlockState below) {
            return soil(below, traits);
        }

        @Override
        public void updateTick(World world, BlockPos pos, IBlockState state, Random random) {
            if (world.isRemote) {
                return;
            }
            super.updateTick(world, pos, state, random);
            if (world.isAreaLoaded(pos, 1) && world.getBlockState(pos).getBlock() == this && world.getLightFromNeighbors(pos.up()) >= 9
                    && random.nextInt(7) == 0) {
                grow(world, random, pos, state);
            }
        }

        @Override
        public boolean canGrow(World world, BlockPos pos, IBlockState state, boolean isClient) {
            return true;
        }

        @Override
        public boolean canUseBonemeal(World world, Random random, BlockPos pos, IBlockState state) {
            return world.rand.nextFloat() < 0.45D;
        }

        @Override
        public void grow(World world, Random random, BlockPos pos, IBlockState state) {
            if (state.getValue(STAGE) == 0) {
                world.setBlockState(pos, state.cycleProperty(STAGE), 4);
                return;
            }
            TreeGrower grower = ModernContent.treeGrower;
            if (grower == null || !net.minecraftforge.event.terraingen.TerrainGen.saplingGrowTree(world, random, pos)) {
                return;
            }
            world.setBlockState(pos, Blocks.AIR.getDefaultState(), 4);
            if (!grower.grow(name, world, pos, random)) {
                world.setBlockState(pos, state, 4); // no room: the sapling stays and tries again
            }
        }
    }

    /** Something lying flat on the ground: a pile of leaves, a carpet of moss. */
    static final class Flat extends Block {
        private static final AxisAlignedBB BOX = new AxisAlignedBB(0.0D, 0.0D, 0.0D, 1.0D, 0.0625D, 1.0D);

        Flat(Traits t) {
            super(Material.CARPET);
            setSoundType(SoundType.PLANT);
            setHardness(0.1F);
            setLightLevel(t.light / 15.0F);
        }

        @Override
        public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess source, BlockPos pos) {
            return BOX;
        }

        @Override
        public AxisAlignedBB getCollisionBoundingBox(IBlockState state, IBlockAccess world, BlockPos pos) {
            return NULL_AABB;
        }

        @Override
        public boolean isOpaqueCube(IBlockState state) {
            return false;
        }

        @Override
        public boolean isFullCube(IBlockState state) {
            return false;
        }

        @Override
        @SideOnly(Side.CLIENT)
        public BlockRenderLayer getRenderLayer() {
            return BlockRenderLayer.CUTOUT;
        }

        @Override
        public BlockFaceShape getBlockFaceShape(IBlockAccess world, IBlockState state, BlockPos pos, EnumFacing face) {
            return BlockFaceShape.UNDEFINED;
        }

        private static boolean footing(World world, BlockPos pos) {
            IBlockState below = world.getBlockState(pos.down());
            return below.isSideSolid(world, pos.down(), EnumFacing.UP) || below.getMaterial() == Material.LEAVES;
        }

        @Override
        public boolean canPlaceBlockAt(World world, BlockPos pos) {
            return super.canPlaceBlockAt(world, pos) && footing(world, pos);
        }

        @Override
        public void neighborChanged(IBlockState state, World world, BlockPos pos, Block changed, BlockPos from) {
            if (!footing(world, pos)) {
                dropBlockAsItem(world, pos, state, 0);
                world.setBlockToAir(pos);
            }
        }
    }

    static final class Lily extends net.minecraft.block.BlockLilyPad {
        Lily() {
            setSoundType(SoundType.PLANT);
            setHardness(0.0F);
        }
    }

    static final class Vine extends net.minecraft.block.BlockVine {
        Vine() {
            setSoundType(SoundType.PLANT);
            setHardness(0.2F);
        }
    }

    /** Petals or clover on the ground: one to four to a block, facing one of four ways. Using more on a patch thickens it. */
    static class Petals extends BlockBush {
        static final net.minecraft.block.properties.PropertyDirection FACING = net.minecraft.block.BlockHorizontal.FACING;
        static final net.minecraft.block.properties.PropertyInteger AMOUNT = net.minecraft.block.properties.PropertyInteger.create("flower_amount", 1, 4);
        private static final AxisAlignedBB BOX = new AxisAlignedBB(0.0D, 0.0D, 0.0D, 1.0D, 0.1875D, 1.0D);
        private final Traits traits;

        Petals(Traits t) {
            super(Material.PLANTS);
            this.traits = t;
            setSoundType(SoundType.PLANT);
            setHardness(0.0F);
            setDefaultState(blockState.getBaseState().withProperty(FACING, EnumFacing.NORTH).withProperty(amount(), 1));
        }

        /** The property that counts the pieces; the modern files name it differently for petals and for fallen leaves. */
        net.minecraft.block.properties.PropertyInteger amount() {
            return AMOUNT;
        }

        @Override
        protected BlockStateContainer createBlockState() {
            return new BlockStateContainer(this, FACING, amount());
        }

        @Override
        public IBlockState getStateFromMeta(int meta) {
            return getDefaultState().withProperty(amount(), (meta & 3) + 1).withProperty(FACING, EnumFacing.byHorizontalIndex(meta >> 2 & 3));
        }

        @Override
        public int getMetaFromState(IBlockState state) {
            return (state.getValue(amount()) - 1) | state.getValue(FACING).getHorizontalIndex() << 2;
        }

        @Override
        public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess source, BlockPos pos) {
            return BOX;
        }

        @Override
        protected boolean canSustainBush(IBlockState below) {
            return soil(below, traits) || (traits.litter && below.isTopSolid());
        }

        @Override
        public IBlockState getStateForPlacement(World world, BlockPos pos, EnumFacing facing, float hitX, float hitY, float hitZ, int meta,
                EntityLivingBase placer) {
            return getDefaultState().withProperty(FACING, placer.getHorizontalFacing().getOpposite());
        }

        @Override
        public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player, net.minecraft.util.EnumHand hand,
                EnumFacing facing, float hitX, float hitY, float hitZ) {
            ItemStack held = player.getHeldItem(hand);
            if (held.getItem() != Item.getItemFromBlock(this) || state.getValue(amount()) >= 4) {
                return false;
            }
            if (!world.isRemote) {
                world.setBlockState(pos, state.withProperty(amount(), state.getValue(amount()) + 1), 3);
                if (!player.capabilities.isCreativeMode) {
                    held.shrink(1);
                }
            }
            return true;
        }

        @Override
        public int quantityDropped(IBlockState state, int fortune, Random random) {
            return state.getValue(amount());
        }
    }

    /** Fallen leaves: petals by another name. */
    static final class Litter extends Petals {
        static final net.minecraft.block.properties.PropertyInteger SEGMENTS = net.minecraft.block.properties.PropertyInteger.create("segment_amount", 1, 4);

        Litter(Traits t) {
            super(t);
        }

        @Override
        net.minecraft.block.properties.PropertyInteger amount() {
            return SEGMENTS;
        }
    }

    /** A whole block drawn with gaps in it: a tangle of roots. */
    static final class CutoutCube extends Block {
        CutoutCube(Traits t) {
            super(t.material);
            setSoundType(t.sound);
            apply(this, t);
            setLightOpacity(1);
        }

        @Override
        public boolean isOpaqueCube(IBlockState state) {
            return false;
        }

        @Override
        @SideOnly(Side.CLIENT)
        public BlockRenderLayer getRenderLayer() {
            return BlockRenderLayer.CUTOUT_MIPPED;
        }
    }

    /**
     * A plant that hangs from what is above it: roots, moss, a spore blossom, a vine with
     * berries. Pieces of the same family hang from each other; the lowest knows it is the tip.
     */
    static final class Hanging extends Block {
        static final net.minecraft.block.properties.PropertyBool TIP = net.minecraft.block.properties.PropertyBool.create("tip");
        static final net.minecraft.block.properties.PropertyBool BERRIES = net.minecraft.block.properties.PropertyBool.create("berries");
        private static final AxisAlignedBB BOX = new AxisAlignedBB(0.125D, 0.0D, 0.125D, 0.875D, 1.0D, 0.875D);
        final String family;
        private final int light;

        Hanging(Traits t, String family) {
            super(Material.PLANTS);
            this.family = family;
            this.light = t.light;
            setSoundType(SoundType.PLANT);
            setHardness(0.0F);
            setDefaultState(blockState.getBaseState().withProperty(TIP, Boolean.FALSE).withProperty(BERRIES, Boolean.FALSE));
        }

        @Override
        protected BlockStateContainer createBlockState() {
            return new BlockStateContainer(this, TIP, BERRIES);
        }

        @Override
        public IBlockState getStateFromMeta(int meta) {
            return getDefaultState().withProperty(BERRIES, (meta & 1) == 1);
        }

        @Override
        public int getMetaFromState(IBlockState state) {
            return state.getValue(BERRIES) ? 1 : 0;
        }

        private boolean kin(IBlockState other) {
            return other.getBlock() instanceof Hanging && ((Hanging) other.getBlock()).family.equals(family);
        }

        @Override
        public IBlockState getActualState(IBlockState state, IBlockAccess world, BlockPos pos) {
            return state.withProperty(TIP, !kin(world.getBlockState(pos.down())));
        }

        @Override
        public int getLightValue(IBlockState state, IBlockAccess world, BlockPos pos) {
            return state.getBlock() == this && state.getValue(BERRIES) ? 14 : light;
        }

        @Override
        public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess source, BlockPos pos) {
            return BOX;
        }

        @Override
        public AxisAlignedBB getCollisionBoundingBox(IBlockState state, IBlockAccess world, BlockPos pos) {
            return NULL_AABB;
        }

        @Override
        public boolean isOpaqueCube(IBlockState state) {
            return false;
        }

        @Override
        public boolean isFullCube(IBlockState state) {
            return false;
        }

        @Override
        @SideOnly(Side.CLIENT)
        public BlockRenderLayer getRenderLayer() {
            return BlockRenderLayer.CUTOUT;
        }

        @Override
        public BlockFaceShape getBlockFaceShape(IBlockAccess world, IBlockState state, BlockPos pos, EnumFacing face) {
            return BlockFaceShape.UNDEFINED;
        }

        boolean held(World world, BlockPos pos) {
            IBlockState above = world.getBlockState(pos.up());
            return kin(above) || above.isSideSolid(world, pos.up(), EnumFacing.DOWN) || above.getMaterial() == Material.LEAVES;
        }

        @Override
        public boolean canPlaceBlockAt(World world, BlockPos pos) {
            return super.canPlaceBlockAt(world, pos) && held(world, pos);
        }

        @Override
        public void neighborChanged(IBlockState state, World world, BlockPos pos, Block changed, BlockPos from) {
            if (!held(world, pos)) {
                dropBlockAsItem(world, pos, state, 0);
                world.setBlockToAir(pos);
            }
        }

        @Override
        public int damageDropped(IBlockState state) {
            return 0;
        }
    }

    /** Bamboo: a thin stalk that grows upward a piece at a time, with leaves near its top. */
    static final class Bamboo extends Block {
        enum Leaf implements IStringSerializable {
            NONE, SMALL, LARGE;

            @Override
            public String getName() {
                return name().toLowerCase(java.util.Locale.ROOT);
            }
        }

        static final net.minecraft.block.properties.PropertyInteger AGE = net.minecraft.block.properties.PropertyInteger.create("age", 0, 1);
        static final PropertyEnum<Leaf> LEAVES = PropertyEnum.create("leaves", Leaf.class);
        static final net.minecraft.block.properties.PropertyInteger STAGE = net.minecraft.block.properties.PropertyInteger.create("stage", 0, 1);
        private static final AxisAlignedBB THIN = new AxisAlignedBB(0.40625D, 0.0D, 0.40625D, 0.59375D, 1.0D, 0.59375D);
        private static final AxisAlignedBB LEAFY = new AxisAlignedBB(0.1875D, 0.0D, 0.1875D, 0.8125D, 1.0D, 0.8125D);
        private static final int TALLEST = 16;

        Bamboo(Traits t) {
            super(Material.WOOD);
            setSoundType(SoundType.WOOD);
            apply(this, t);
            setTickRandomly(true);
            setDefaultState(blockState.getBaseState().withProperty(AGE, 0).withProperty(LEAVES, Leaf.NONE).withProperty(STAGE, 0));
        }

        @Override
        protected BlockStateContainer createBlockState() {
            return new BlockStateContainer(this, AGE, LEAVES, STAGE);
        }

        @Override
        public IBlockState getStateFromMeta(int meta) {
            return getDefaultState().withProperty(AGE, meta & 1).withProperty(LEAVES, Leaf.values()[Math.min(2, meta >> 1 & 3)]).withProperty(STAGE, meta >> 3 & 1);
        }

        @Override
        public int getMetaFromState(IBlockState state) {
            return state.getValue(AGE) | state.getValue(LEAVES).ordinal() << 1 | state.getValue(STAGE) << 3;
        }

        @Override
        public EnumOffsetType getOffsetType() {
            return EnumOffsetType.XZ;
        }

        @Override
        public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess world, BlockPos pos) {
            net.minecraft.util.math.Vec3d offset = state.getOffset(world, pos);
            return (state.getValue(LEAVES) == Leaf.LARGE ? LEAFY : THIN).offset(offset);
        }

        @Override
        public AxisAlignedBB getCollisionBoundingBox(IBlockState state, IBlockAccess world, BlockPos pos) {
            return THIN.offset(state.getOffset(world, pos));
        }

        @Override
        public boolean isOpaqueCube(IBlockState state) {
            return false;
        }

        @Override
        public boolean isFullCube(IBlockState state) {
            return false;
        }

        @Override
        @SideOnly(Side.CLIENT)
        public BlockRenderLayer getRenderLayer() {
            return BlockRenderLayer.CUTOUT;
        }

        @Override
        public BlockFaceShape getBlockFaceShape(IBlockAccess world, IBlockState state, BlockPos pos, EnumFacing face) {
            return BlockFaceShape.UNDEFINED;
        }

        private boolean footing(World world, BlockPos pos) {
            IBlockState below = world.getBlockState(pos.down());
            Material material = below.getMaterial();
            return below.getBlock() == this || material == Material.GRASS || material == Material.GROUND || material == Material.SAND
                    || below.getBlock() == Blocks.GRAVEL;
        }

        @Override
        public boolean canPlaceBlockAt(World world, BlockPos pos) {
            return super.canPlaceBlockAt(world, pos) && footing(world, pos);
        }

        @Override
        public void neighborChanged(IBlockState state, World world, BlockPos pos, Block changed, BlockPos from) {
            if (!footing(world, pos)) {
                world.destroyBlock(pos, true);
            }
        }

        @Override
        public IBlockState getStateForPlacement(World world, BlockPos pos, EnumFacing facing, float hitX, float hitY, float hitZ, int meta,
                EntityLivingBase placer) {
            // Set on top of a stalk it joins it, as thick as the stalk is.
            IBlockState below = world.getBlockState(pos.down());
            return below.getBlock() == this ? getDefaultState().withProperty(AGE, below.getValue(AGE)) : getDefaultState();
        }

        @Override
        public void updateTick(World world, BlockPos pos, IBlockState state, Random random) {
            if (world.isRemote || state.getValue(STAGE) != 0 || random.nextInt(3) != 0 || !world.isAirBlock(pos.up())
                    || world.getLightFromNeighbors(pos.up()) < 9) {
                return;
            }
            int below = 0;
            while (below < TALLEST && world.getBlockState(pos.down(below + 1)).getBlock() == this) {
                below++;
            }
            if (below + 1 < TALLEST) {
                grow(world, pos, state, random, below + 1);
            }
        }

        /** Adds the next piece on top, the way the modern game does: leaves gather at the top as the stalk rises. */
        private void grow(World world, BlockPos pos, IBlockState state, Random random, int height) {
            IBlockState below = world.getBlockState(pos.down()), twoBelow = world.getBlockState(pos.down(2));
            Leaf leaves = Leaf.NONE;
            if (height >= 1) {
                if (below.getBlock() != this || below.getValue(LEAVES) == Leaf.NONE) {
                    leaves = Leaf.SMALL;
                } else {
                    leaves = Leaf.LARGE;
                    if (twoBelow.getBlock() == this) {
                        world.setBlockState(pos.down(), below.withProperty(LEAVES, Leaf.SMALL), 3);
                        world.setBlockState(pos.down(2), twoBelow.withProperty(LEAVES, Leaf.NONE), 3);
                    }
                }
            }
            int age = state.getValue(AGE) == 1 || twoBelow.getBlock() == this ? 1 : 0;
            int stage = (height >= 11 && random.nextFloat() < 0.25F) || height == TALLEST - 1 ? 1 : 0;
            world.setBlockState(pos.up(), getDefaultState().withProperty(AGE, age).withProperty(LEAVES, leaves).withProperty(STAGE, stage), 3);
        }

        @Override
        public int damageDropped(IBlockState state) {
            return 0;
        }
    }

    /** Pointed dripstone: stalagmites and stalactites, each piece shaped by its place in the column. */
    static final class Dripstone extends Block {
        enum Thickness implements IStringSerializable {
            TIP_MERGE, TIP, FRUSTUM, MIDDLE, BASE;

            @Override
            public String getName() {
                return name().toLowerCase(java.util.Locale.ROOT);
            }
        }

        enum Way implements IStringSerializable {
            UP, DOWN;

            @Override
            public String getName() {
                return name().toLowerCase(java.util.Locale.ROOT);
            }
        }

        static final PropertyEnum<Thickness> THICKNESS = PropertyEnum.create("thickness", Thickness.class);
        static final PropertyEnum<Way> WAY = PropertyEnum.create("vertical_direction", Way.class);
        private static final AxisAlignedBB TIP_BOX = new AxisAlignedBB(0.3125D, 0.0D, 0.3125D, 0.6875D, 1.0D, 0.6875D);
        private static final AxisAlignedBB MIDDLE_BOX = new AxisAlignedBB(0.1875D, 0.0D, 0.1875D, 0.8125D, 1.0D, 0.8125D);

        Dripstone(Traits t) {
            super(Material.ROCK);
            setSoundType(SoundType.STONE);
            apply(this, t);
            setDefaultState(blockState.getBaseState().withProperty(THICKNESS, Thickness.TIP).withProperty(WAY, Way.UP));
        }

        @Override
        protected BlockStateContainer createBlockState() {
            return new BlockStateContainer(this, THICKNESS, WAY);
        }

        @Override
        public IBlockState getStateFromMeta(int meta) {
            return getDefaultState().withProperty(WAY, (meta & 1) == 1 ? Way.DOWN : Way.UP);
        }

        @Override
        public int getMetaFromState(IBlockState state) {
            return state.getValue(WAY) == Way.DOWN ? 1 : 0;
        }

        private boolean same(IBlockState other, Way way) {
            return other.getBlock() == this && other.getValue(WAY) == way;
        }

        @Override
        public IBlockState getActualState(IBlockState state, IBlockAccess world, BlockPos pos) {
            Way way = state.getValue(WAY);
            EnumFacing toward = way == Way.UP ? EnumFacing.UP : EnumFacing.DOWN;
            IBlockState next = world.getBlockState(pos.offset(toward));
            Thickness thickness;
            if (!same(next, way)) {
                // The point; where a stalactite meets a stalagmite the two points join.
                thickness = next.getBlock() == this ? Thickness.TIP_MERGE : Thickness.TIP;
            } else if (!same(world.getBlockState(pos.offset(toward, 2)), way)) {
                thickness = Thickness.FRUSTUM;
            } else {
                thickness = same(world.getBlockState(pos.offset(toward.getOpposite())), way) ? Thickness.MIDDLE : Thickness.BASE;
            }
            return state.withProperty(THICKNESS, thickness);
        }

        @Override
        public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess world, BlockPos pos) {
            Thickness thickness = getActualState(state, world, pos).getValue(THICKNESS);
            return thickness == Thickness.TIP || thickness == Thickness.TIP_MERGE ? TIP_BOX : MIDDLE_BOX;
        }

        @Override
        public boolean isOpaqueCube(IBlockState state) {
            return false;
        }

        @Override
        public boolean isFullCube(IBlockState state) {
            return false;
        }

        @Override
        @SideOnly(Side.CLIENT)
        public BlockRenderLayer getRenderLayer() {
            return BlockRenderLayer.CUTOUT;
        }

        @Override
        public BlockFaceShape getBlockFaceShape(IBlockAccess world, IBlockState state, BlockPos pos, EnumFacing face) {
            return BlockFaceShape.UNDEFINED;
        }

        private boolean held(World world, BlockPos pos, Way way) {
            BlockPos root = way == Way.UP ? pos.down() : pos.up();
            IBlockState at = world.getBlockState(root);
            return same(at, way) || at.isSideSolid(world, root, way == Way.UP ? EnumFacing.UP : EnumFacing.DOWN);
        }

        @Override
        public boolean canPlaceBlockAt(World world, BlockPos pos) {
            return super.canPlaceBlockAt(world, pos) && (held(world, pos, Way.UP) || held(world, pos, Way.DOWN));
        }

        @Override
        public IBlockState getStateForPlacement(World world, BlockPos pos, EnumFacing facing, float hitX, float hitY, float hitZ, int meta,
                EntityLivingBase placer) {
            Way way = facing == EnumFacing.DOWN || !held(world, pos, Way.UP) ? Way.DOWN : Way.UP;
            return getDefaultState().withProperty(WAY, way);
        }

        @Override
        public void neighborChanged(IBlockState state, World world, BlockPos pos, Block changed, BlockPos from) {
            if (!held(world, pos, state.getValue(WAY))) {
                world.destroyBlock(pos, true);
            }
        }

        @Override
        public int damageDropped(IBlockState state) {
            return 0;
        }
    }

    /**
     * A plant that grows under water: seagrass, kelp, coral. 1.12.2 cannot keep a plant and
     * water in one block, so the plant's block counts as water itself: it is made of water
     * (things swim and float in it, the water around does not run into it or show a wall
     * toward it) and it draws the plant. Taking it away leaves water behind. One always
     * stands a block below the surface, because the block draws no water surface of its own.
     */
    static class Aquatic extends Block {
        static final net.minecraft.block.properties.PropertyInteger LEVEL = net.minecraft.block.BlockLiquid.LEVEL;
        private static final AxisAlignedBB BOX = new AxisAlignedBB(0.125D, 0.0D, 0.125D, 0.875D, 0.875D, 0.875D);
        /** Pieces of the same family stand on each other (kelp). */
        final String family;
        /** What breaking it gives, if not itself. */
        Item dropAs;

        Aquatic(Traits t, String family) {
            super(Material.WATER);
            this.family = family;
            setSoundType(SoundType.PLANT);
            setHardness(0.0F);
            setLightLevel(t.light / 15.0F);
            setLightOpacity(3);
            setDefaultState(blockState.getBaseState());
        }

        /** The properties a kind of water plant has besides the water level every water block carries. */
        net.minecraft.block.properties.IProperty<?>[] extra() {
            return new net.minecraft.block.properties.IProperty<?>[0];
        }

        @Override
        protected BlockStateContainer createBlockState() {
            net.minecraft.block.properties.IProperty<?>[] own = extra();
            net.minecraft.block.properties.IProperty<?>[] all = java.util.Arrays.copyOf(own, own.length + 1);
            all[own.length] = LEVEL;
            return new BlockStateContainer(this, all);
        }

        @Override
        public IBlockState getStateFromMeta(int meta) {
            return getDefaultState();
        }

        @Override
        public int getMetaFromState(IBlockState state) {
            return 0;
        }

        /** A full block of still water, the only thing a water plant can be set into. */
        static boolean water(IBlockState state) {
            return (state.getBlock() == Blocks.WATER || state.getBlock() == Blocks.FLOWING_WATER) && state.getValue(LEVEL) == 0;
        }

        static boolean wet(IBlockAccess world, BlockPos pos) {
            return world.getBlockState(pos).getMaterial() == Material.WATER;
        }

        boolean footing(World world, BlockPos pos) {
            IBlockState below = world.getBlockState(pos.down());
            return below.isSideSolid(world, pos.down(), EnumFacing.UP)
                    || (family != null && below.getBlock() instanceof Aquatic && family.equals(((Aquatic) below.getBlock()).family));
        }

        @Override
        public boolean canPlaceBlockAt(World world, BlockPos pos) {
            return water(world.getBlockState(pos)) && wet(world, pos.up()) && footing(world, pos);
        }

        /** Water is where it was, and water it becomes again. */
        void toWater(World world, BlockPos pos) {
            world.setBlockState(pos, Blocks.WATER.getDefaultState(), 3);
        }

        @Override
        public void neighborChanged(IBlockState state, World world, BlockPos pos, Block changed, BlockPos from) {
            if (!footing(world, pos)) {
                dropBlockAsItem(world, pos, state, 0);
                toWater(world, pos);
            }
        }

        @Override
        public boolean removedByPlayer(IBlockState state, World world, BlockPos pos, EntityPlayer player, boolean willHarvest) {
            onBlockHarvested(world, pos, state, player);
            return world.setBlockState(pos, Blocks.WATER.getDefaultState(), world.isRemote ? 11 : 3);
        }

        @Override
        public void onBlockExploded(World world, BlockPos pos, net.minecraft.world.Explosion explosion) {
            toWater(world, pos);
        }

        @Override
        public Item getItemDropped(IBlockState state, Random rand, int fortune) {
            return dropAs != null ? dropAs : super.getItemDropped(state, rand, fortune);
        }

        @Override
        public int damageDropped(IBlockState state) {
            return 0;
        }

        @Override
        public boolean isReplaceable(IBlockAccess world, BlockPos pos) {
            return false;
        }

        @Override
        public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess source, BlockPos pos) {
            return BOX;
        }

        @Override
        public AxisAlignedBB getCollisionBoundingBox(IBlockState state, IBlockAccess world, BlockPos pos) {
            return NULL_AABB;
        }

        @Override
        public boolean isOpaqueCube(IBlockState state) {
            return false;
        }

        @Override
        public boolean isFullCube(IBlockState state) {
            return false;
        }

        @Override
        @SideOnly(Side.CLIENT)
        public BlockRenderLayer getRenderLayer() {
            return BlockRenderLayer.CUTOUT;
        }

        @Override
        public BlockFaceShape getBlockFaceShape(IBlockAccess world, IBlockState state, BlockPos pos, EnumFacing face) {
            return BlockFaceShape.UNDEFINED;
        }

        @Override
        public net.minecraft.block.material.EnumPushReaction getPushReaction(IBlockState state) {
            return net.minecraft.block.material.EnumPushReaction.DESTROY;
        }
    }

    /** Tall seagrass: two blocks of water plant. */
    static final class AquaticTall extends Aquatic {
        static final PropertyEnum<TallPlant.Half> HALF = TallPlant.HALF;

        AquaticTall(Traits t) {
            super(t, null);
        }

        @Override
        net.minecraft.block.properties.IProperty<?>[] extra() {
            return new net.minecraft.block.properties.IProperty<?>[] {TallPlant.HALF};
        }

        @Override
        public IBlockState getStateFromMeta(int meta) {
            return getDefaultState().withProperty(HALF, (meta & 1) == 1 ? TallPlant.Half.UPPER : TallPlant.Half.LOWER);
        }

        @Override
        public int getMetaFromState(IBlockState state) {
            return state.getValue(HALF) == TallPlant.Half.UPPER ? 1 : 0;
        }

        @Override
        public boolean canPlaceBlockAt(World world, BlockPos pos) {
            return super.canPlaceBlockAt(world, pos) && water(world.getBlockState(pos.up())) && wet(world, pos.up(2));
        }

        @Override
        public void onBlockPlacedBy(World world, BlockPos pos, IBlockState state, EntityLivingBase placer, ItemStack stack) {
            world.setBlockState(pos.up(), getDefaultState().withProperty(HALF, TallPlant.Half.UPPER), 2);
        }

        void placeAt(World world, BlockPos lower, int flags) {
            world.setBlockState(lower, getDefaultState(), flags);
            world.setBlockState(lower.up(), getDefaultState().withProperty(HALF, TallPlant.Half.UPPER), flags);
        }

        @Override
        public void neighborChanged(IBlockState state, World world, BlockPos pos, Block changed, BlockPos from) {
            boolean upper = state.getValue(HALF) == TallPlant.Half.UPPER;
            boolean whole = upper ? world.getBlockState(pos.down()).getBlock() == this : world.getBlockState(pos.up()).getBlock() == this && footing(world, pos);
            if (!whole) {
                if (!upper) {
                    dropBlockAsItem(world, pos, state, 0);
                }
                toWater(world, pos);
            }
        }

        @Override
        public Item getItemDropped(IBlockState state, Random rand, int fortune) {
            return state.getValue(HALF) == TallPlant.Half.UPPER ? Items.AIR : super.getItemDropped(state, rand, fortune);
        }
    }

    /** A coral fan growing out of the side of a block. */
    static final class AquaticWall extends Aquatic {
        static final net.minecraft.block.properties.PropertyDirection FACING = net.minecraft.block.BlockHorizontal.FACING;

        AquaticWall(Traits t) {
            super(t, null);
        }

        @Override
        net.minecraft.block.properties.IProperty<?>[] extra() {
            return new net.minecraft.block.properties.IProperty<?>[] {net.minecraft.block.BlockHorizontal.FACING};
        }

        @Override
        public IBlockState getStateFromMeta(int meta) {
            return getDefaultState().withProperty(FACING, EnumFacing.byHorizontalIndex(meta & 3));
        }

        @Override
        public int getMetaFromState(IBlockState state) {
            return state.getValue(FACING).getHorizontalIndex();
        }

        private static boolean held(World world, BlockPos pos, EnumFacing facing) {
            BlockPos behind = pos.offset(facing.getOpposite());
            return world.getBlockState(behind).isSideSolid(world, behind, facing);
        }

        @Override
        public boolean canPlaceBlockAt(World world, BlockPos pos) {
            if (!water(world.getBlockState(pos)) || !wet(world, pos.up())) {
                return false;
            }
            for (EnumFacing side : EnumFacing.HORIZONTALS) {
                if (held(world, pos, side)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void neighborChanged(IBlockState state, World world, BlockPos pos, Block changed, BlockPos from) {
            if (!held(world, pos, state.getValue(FACING))) {
                dropBlockAsItem(world, pos, state, 0);
                toWater(world, pos);
            }
        }
    }

    /** Sea pickles: one to four on a block, glowing more the more there are. */
    static final class Pickle extends Aquatic {
        static final net.minecraft.block.properties.PropertyInteger PICKLES = net.minecraft.block.properties.PropertyInteger.create("pickles", 1, 4);

        Pickle(Traits t) {
            super(t, null);
        }

        @Override
        net.minecraft.block.properties.IProperty<?>[] extra() {
            return new net.minecraft.block.properties.IProperty<?>[] {PICKLES};
        }

        @Override
        public IBlockState getStateFromMeta(int meta) {
            return getDefaultState().withProperty(PICKLES, (meta & 3) + 1);
        }

        @Override
        public int getMetaFromState(IBlockState state) {
            return state.getValue(PICKLES) - 1;
        }

        @Override
        public int getLightValue(IBlockState state, IBlockAccess world, BlockPos pos) {
            return state.getBlock() == this ? 3 + 3 * state.getValue(PICKLES) : 6;
        }

        @Override
        public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player, net.minecraft.util.EnumHand hand,
                EnumFacing facing, float hitX, float hitY, float hitZ) {
            ItemStack held = player.getHeldItem(hand);
            if (held.getItem() != Item.getItemFromBlock(this) || state.getValue(PICKLES) >= 4) {
                return false;
            }
            if (!world.isRemote) {
                world.setBlockState(pos, state.withProperty(PICKLES, state.getValue(PICKLES) + 1), 3);
                if (!player.capabilities.isCreativeMode) {
                    held.shrink(1);
                }
            }
            return true;
        }

        @Override
        public int quantityDropped(IBlockState state, int fortune, Random random) {
            return state.getValue(PICKLES);
        }
    }

    /** An amethyst bud or cluster: a crystal growing out of whichever face it sits on. */
    static final class Bud extends Block {
        static final net.minecraft.block.properties.PropertyDirection FACING = net.minecraft.block.BlockDirectional.FACING;
        private static final AxisAlignedBB BOX = new AxisAlignedBB(0.1875D, 0.1875D, 0.1875D, 0.8125D, 0.8125D, 0.8125D);
        /** What a full-grown cluster breaks into; a bud gives nothing unless taken whole with silk touch. */
        Item shards;

        Bud(Traits t) {
            super(Material.GLASS);
            setSoundType(SoundType.GLASS);
            apply(this, t);
            setDefaultState(blockState.getBaseState().withProperty(FACING, EnumFacing.UP));
        }

        @Override
        protected BlockStateContainer createBlockState() {
            return new BlockStateContainer(this, FACING);
        }

        @Override
        public IBlockState getStateFromMeta(int meta) {
            return getDefaultState().withProperty(FACING, EnumFacing.byIndex(meta % 6));
        }

        @Override
        public int getMetaFromState(IBlockState state) {
            return state.getValue(FACING).getIndex();
        }

        private static boolean held(World world, BlockPos pos, EnumFacing facing) {
            BlockPos behind = pos.offset(facing.getOpposite());
            return world.getBlockState(behind).isSideSolid(world, behind, facing);
        }

        @Override
        public boolean canPlaceBlockOnSide(World world, BlockPos pos, EnumFacing side) {
            return super.canPlaceBlockAt(world, pos) && held(world, pos, side);
        }

        @Override
        public IBlockState getStateForPlacement(World world, BlockPos pos, EnumFacing facing, float hitX, float hitY, float hitZ, int meta,
                EntityLivingBase placer) {
            return getDefaultState().withProperty(FACING, facing);
        }

        @Override
        public void neighborChanged(IBlockState state, World world, BlockPos pos, Block changed, BlockPos from) {
            if (!held(world, pos, state.getValue(FACING))) {
                world.destroyBlock(pos, true);
            }
        }

        @Override
        public Item getItemDropped(IBlockState state, Random rand, int fortune) {
            return shards != null ? shards : Items.AIR;
        }

        @Override
        public int quantityDropped(IBlockState state, int fortune, Random random) {
            return shards == null ? 0 : 4 + (fortune > 0 ? random.nextInt(fortune * 2 + 1) : 0);
        }

        @Override
        public int damageDropped(IBlockState state) {
            return 0;
        }

        @Override
        public boolean canSilkHarvest(World world, BlockPos pos, IBlockState state, EntityPlayer player) {
            return true;
        }

        @Override
        protected ItemStack getSilkTouchDrop(IBlockState state) {
            return new ItemStack(this);
        }

        @Override
        public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess source, BlockPos pos) {
            return BOX;
        }

        @Override
        public AxisAlignedBB getCollisionBoundingBox(IBlockState state, IBlockAccess world, BlockPos pos) {
            return NULL_AABB;
        }

        @Override
        public boolean isOpaqueCube(IBlockState state) {
            return false;
        }

        @Override
        public boolean isFullCube(IBlockState state) {
            return false;
        }

        @Override
        @SideOnly(Side.CLIENT)
        public BlockRenderLayer getRenderLayer() {
            return BlockRenderLayer.CUTOUT;
        }

        @Override
        public BlockFaceShape getBlockFaceShape(IBlockAccess world, IBlockState state, BlockPos pos, EnumFacing face) {
            return BlockFaceShape.UNDEFINED;
        }
    }

    /** Budding amethyst: slowly grows buds on its open faces, each bud growing in four steps into a cluster. */
    static final class Budding extends Block {
        /** The four stages, smallest first; set once every block is registered. */
        Block[] stages;

        Budding(Traits t) {
            super(t.material);
            setSoundType(t.sound);
            apply(this, t);
            setTickRandomly(true);
        }

        @Override
        public void updateTick(World world, BlockPos pos, IBlockState state, Random random) {
            if (world.isRemote || stages == null || random.nextInt(5) != 0) {
                return;
            }
            EnumFacing side = EnumFacing.VALUES[random.nextInt(6)];
            BlockPos at = pos.offset(side);
            if (!world.isBlockLoaded(at)) {
                return;
            }
            IBlockState there = world.getBlockState(at);
            Block next = null;
            if (there.getBlock().isAir(there, world, at)) {
                next = stages[0];
            } else {
                for (int i = 0; i < stages.length - 1; i++) {
                    if (there.getBlock() == stages[i] && there.getValue(Bud.FACING) == side) {
                        next = stages[i + 1];
                    }
                }
            }
            if (next != null) {
                world.setBlockState(at, next.getDefaultState().withProperty(Bud.FACING, side), 3);
            }
        }

        // The modern game gives nothing for breaking one, silk touch or not: the crystal farm stays where the geode is.
        @Override
        public Item getItemDropped(IBlockState state, Random rand, int fortune) {
            return Items.AIR;
        }

        @Override
        public boolean canSilkHarvest(World world, BlockPos pos, IBlockState state, EntityPlayer player) {
            return false;
        }
    }

    /** Glow lichen: a thin growth that clings to every firm face around it and glows a little. */
    static final class Lichen extends Block {
        static final net.minecraft.block.properties.PropertyBool[] SIDES = new net.minecraft.block.properties.PropertyBool[6];

        static {
            for (EnumFacing side : EnumFacing.VALUES) {
                SIDES[side.getIndex()] = net.minecraft.block.properties.PropertyBool.create(side.getName());
            }
        }

        Lichen(Traits t) {
            super(Material.VINE);
            setSoundType(SoundType.PLANT);
            setHardness(0.2F);
            setLightLevel(t.light / 15.0F);
            IBlockState state = blockState.getBaseState();
            for (net.minecraft.block.properties.PropertyBool side : SIDES) {
                state = state.withProperty(side, Boolean.FALSE);
            }
            setDefaultState(state);
        }

        @Override
        protected BlockStateContainer createBlockState() {
            return new BlockStateContainer(this, SIDES);
        }

        @Override
        public IBlockState getStateFromMeta(int meta) {
            return getDefaultState();
        }

        @Override
        public int getMetaFromState(IBlockState state) {
            return 0;
        }

        private static boolean clings(IBlockAccess world, BlockPos pos, EnumFacing side) {
            BlockPos at = pos.offset(side);
            IBlockState there = world.getBlockState(at);
            return there.getBlockFaceShape(world, at, side.getOpposite()) == BlockFaceShape.SOLID && there.getMaterial().isSolid();
        }

        @Override
        public IBlockState getActualState(IBlockState state, IBlockAccess world, BlockPos pos) {
            for (EnumFacing side : EnumFacing.VALUES) {
                state = state.withProperty(SIDES[side.getIndex()], clings(world, pos, side));
            }
            return state;
        }

        private static boolean anywhere(IBlockAccess world, BlockPos pos) {
            for (EnumFacing side : EnumFacing.VALUES) {
                if (clings(world, pos, side)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public AxisAlignedBB getCollisionBoundingBox(IBlockState state, IBlockAccess world, BlockPos pos) {
            return NULL_AABB;
        }

        @Override
        public boolean isOpaqueCube(IBlockState state) {
            return false;
        }

        @Override
        public boolean isFullCube(IBlockState state) {
            return false;
        }

        @Override
        @SideOnly(Side.CLIENT)
        public BlockRenderLayer getRenderLayer() {
            return BlockRenderLayer.CUTOUT;
        }

        @Override
        public BlockFaceShape getBlockFaceShape(IBlockAccess world, IBlockState state, BlockPos pos, EnumFacing face) {
            return BlockFaceShape.UNDEFINED;
        }

        @Override
        public boolean canPlaceBlockAt(World world, BlockPos pos) {
            return super.canPlaceBlockAt(world, pos) && anywhere(world, pos);
        }

        @Override
        public void neighborChanged(IBlockState state, World world, BlockPos pos, Block changed, BlockPos from) {
            if (!anywhere(world, pos)) {
                dropBlockAsItem(world, pos, state, 0);
                world.setBlockToAir(pos);
            }
        }
    }

    static final class Wall extends net.minecraft.block.BlockWall {
        Wall(Block cutFrom, Traits t) {
            super(cutFrom);
            setSoundType(t.sound);
            apply(this, t);
        }

        // The vanilla wall lists a plain and a mossy variant; these have one.
        @Override
        public void getSubBlocks(net.minecraft.creativetab.CreativeTabs tab, NonNullList<ItemStack> items) {
            items.add(new ItemStack(this));
        }

        @Override
        public int damageDropped(IBlockState state) {
            return 0;
        }
    }

    /** The sign's writing lives in a tile of its own class, so the renderer can give it its wood's texture. */
    public static final class SignTile extends net.minecraft.tileentity.TileEntitySign {
    }

    static final class StandingSign extends net.minecraft.block.BlockStandingSign {
        Item item;
        final String wood;

        /** "wood" is where the sign's texture lies under textures/entity: "bop/signs/fir". */
        StandingSign(String wood) {
            this.wood = wood;
            setHardness(1.0F);
            setSoundType(SoundType.WOOD);
        }

        @Override
        public net.minecraft.tileentity.TileEntity createNewTileEntity(World world, int meta) {
            return new SignTile();
        }

        @Override
        public Item getItemDropped(IBlockState state, Random rand, int fortune) {
            return item == null ? Items.SIGN : item;
        }

        @Override
        public ItemStack getItem(World world, BlockPos pos, IBlockState state) {
            return new ItemStack(item == null ? Items.SIGN : item);
        }
    }

    static final class WallSign extends net.minecraft.block.BlockWallSign {
        Item item;
        final String wood;

        WallSign(String wood) {
            this.wood = wood;
            setHardness(1.0F);
            setSoundType(SoundType.WOOD);
        }

        @Override
        public net.minecraft.tileentity.TileEntity createNewTileEntity(World world, int meta) {
            return new SignTile();
        }

        @Override
        public Item getItemDropped(IBlockState state, Random rand, int fortune) {
            return item == null ? Items.SIGN : item;
        }

        @Override
        public ItemStack getItem(World world, BlockPos pos, IBlockState state) {
            return new ItemStack(item == null ? Items.SIGN : item);
        }
    }

    /** The wood a sign block is made of, for its texture; null for any other block. */
    static String signWood(Block block) {
        return block instanceof StandingSign ? ((StandingSign) block).wood : block instanceof WallSign ? ((WallSign) block).wood : null;
    }

    /** The sign item: stands on the ground or hangs on a wall, then opens for writing. */
    static final class SignItem extends Item {
        private final Block standing, wall;

        SignItem(Block standing, Block wall) {
            this.standing = standing;
            this.wall = wall;
            this.maxStackSize = 16;
        }

        @Override
        public net.minecraft.util.EnumActionResult onItemUse(EntityPlayer player, World world, BlockPos pos, net.minecraft.util.EnumHand hand,
                EnumFacing facing, float hitX, float hitY, float hitZ) {
            IBlockState clicked = world.getBlockState(pos);
            boolean replace = clicked.getBlock().isReplaceable(world, pos);
            if (facing == EnumFacing.DOWN || !(clicked.getMaterial().isSolid() || replace) || (replace && facing != EnumFacing.UP)) {
                return net.minecraft.util.EnumActionResult.FAIL;
            }
            pos = pos.offset(facing);
            ItemStack stack = player.getHeldItem(hand);
            if (!player.canPlayerEdit(pos, facing, stack) || !standing.canPlaceBlockAt(world, pos) || (facing != EnumFacing.UP && wall == null)) {
                return net.minecraft.util.EnumActionResult.FAIL;
            }
            if (world.isRemote) {
                return net.minecraft.util.EnumActionResult.SUCCESS;
            }
            pos = replace ? pos.down() : pos;
            if (facing == EnumFacing.UP) {
                int rotation = net.minecraft.util.math.MathHelper.floor((player.rotationYaw + 180.0F) * 16.0F / 360.0F + 0.5D) & 15;
                world.setBlockState(pos, standing.getDefaultState().withProperty(net.minecraft.block.BlockStandingSign.ROTATION, rotation), 11);
            } else {
                world.setBlockState(pos, wall.getDefaultState().withProperty(net.minecraft.block.BlockWallSign.FACING, facing), 11);
            }
            net.minecraft.tileentity.TileEntity tile = world.getTileEntity(pos);
            if (tile instanceof net.minecraft.tileentity.TileEntitySign && !net.minecraft.item.ItemBlock.setTileEntityNBT(world, player, pos, stack)) {
                player.openEditSign((net.minecraft.tileentity.TileEntitySign) tile);
            }
            stack.shrink(1);
            return net.minecraft.util.EnumActionResult.SUCCESS;
        }
    }
}
