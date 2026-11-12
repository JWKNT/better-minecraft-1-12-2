package better112.content;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import better112.content.BlockDef.Kind;

import net.minecraft.block.Block;
import net.minecraft.block.BlockButton;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.client.renderer.block.statemap.StateMapperBase;
import net.minecraft.client.renderer.color.IBlockColor;
import net.minecraft.client.renderer.color.IItemColor;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.ColorizerFoliage;
import net.minecraft.world.ColorizerGrass;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.biome.BiomeColorHelper;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/** The look of imported blocks: which model each state uses, item models, and biome tints. */
public final class ContentClient {

    private ContentClient() {}

    /** Picks, for each state of a stand-in block, the variant of the modern blockstate file that describes it. */
    private static final class Mapper extends StateMapperBase {
        private final BlockDef def;

        Mapper(BlockDef def) {
            this.def = def;
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        protected ModelResourceLocation getModelResourceLocation(IBlockState state) {
            Map<String, String> properties = new HashMap<String, String>();
            for (IProperty property : state.getPropertyKeys()) {
                properties.put(property.getName(), property.getName(state.getValue(property)));
            }
            if (def.kind == Kind.BUTTON) {
                // 1.12.2 has one "facing" with six directions; modern buttons say floor, wall or ceiling and then which way.
                EnumFacing facing = state.getValue(BlockButton.FACING);
                properties.put("face", facing == EnumFacing.UP ? "floor" : facing == EnumFacing.DOWN ? "ceiling" : "wall");
                properties.put("facing", facing.getAxis() == EnumFacing.Axis.Y ? "north" : facing.getName());
            }
            String key = def.variantFor(properties);
            return new ModelResourceLocation(state.getBlock().getRegistryName(), key.isEmpty() ? "normal" : key);
        }
    }

    @SubscribeEvent
    public static void models(ModelRegistryEvent event) {
        for (BlockDef def : ModernContent.DEFS) {
            Block block = ModernContent.BLOCKS.get(def.key);
            Item item = ModernContent.ITEMS.get(def.key);
            if (block == null) {
                continue;
            }
            // A fence's file is a list of parts keyed on the same four properties the 1.12.2 fence has; it loads as it is.
            if (def.kind != Kind.FENCE && def.kind != Kind.PETALS && def.kind != Kind.WALL && def.kind != Kind.BAMBOO && def.kind != Kind.LICHEN
                    && def.parts == 0) {
                ModelLoader.setCustomStateMapper(block, new Mapper(def));
            }
            if (item != null) {
                ModelLoader.setCustomModelResourceLocation(item, 0, new ModelResourceLocation(item.getRegistryName(), "inventory"));
            }
        }
        for (String plain : ModernContent.PLAIN) {
            Item item = ModernContent.ITEMS.get(ModernContent.MC.key(plain));
            if (item != null) {
                ModelLoader.setCustomModelResourceLocation(item, 0, new ModelResourceLocation(item.getRegistryName(), "inventory"));
            }
        }
    }

    /** Draws an imported sign with the texture of its wood; everything else is the vanilla sign renderer. */
    private static final class SignRenderer extends net.minecraft.client.renderer.tileentity.TileEntitySignRenderer {
        private net.minecraft.util.ResourceLocation wood;

        @Override
        public void render(net.minecraft.tileentity.TileEntitySign sign, double x, double y, double z, float partialTicks, int destroyStage, float alpha) {
            String name = ModernBlocks.signWood(sign.getBlockType());
            wood = name == null ? null : new net.minecraft.util.ResourceLocation(ContentPack.DOMAIN, "textures/entity/" + name + ".png");
            super.render(sign, x, y, z, partialTicks, destroyStage, alpha);
        }

        @Override
        protected void bindTexture(net.minecraft.util.ResourceLocation location) {
            super.bindTexture(wood != null && location.getPath().endsWith("entity/sign.png") ? wood : location);
        }
    }

    public static void init() {
        Minecraft mc = Minecraft.getMinecraft();
        if (!ModernContent.DEFS.isEmpty()) {
            net.minecraftforge.fml.client.registry.ClientRegistry.bindTileEntitySpecialRenderer(ModernBlocks.SignTile.class, new SignRenderer());
        }
        List<Block> foliage = new ArrayList<Block>(), grass = new ArrayList<Block>();
        List<Item> foliageItems = new ArrayList<Item>(), grassItems = new ArrayList<Item>();
        for (BlockDef def : ModernContent.DEFS) {
            Block block = ModernContent.BLOCKS.get(def.key);
            Item item = ModernContent.ITEMS.get(def.key);
            if (block == null || def.tint == 0) {
                continue;
            }
            if (def.tint == 3) {
                // One colour everywhere: mangrove leaves, fallen leaves.
                final int colour = def.tintColor;
                mc.getBlockColors().registerBlockColorHandler(new IBlockColor() {
                    @Override
                    public int colorMultiplier(IBlockState state, IBlockAccess world, BlockPos pos, int tintIndex) {
                        return colour;
                    }
                }, block);
                if (item != null) {
                    mc.getItemColors().registerItemColorHandler(new IItemColor() {
                        @Override
                        public int colorMultiplier(ItemStack stack, int tintIndex) {
                            return colour;
                        }
                    }, item);
                }
                continue;
            }
            (def.tint == 1 ? foliage : grass).add(block);
            if (item != null) {
                (def.tint == 1 ? foliageItems : grassItems).add(item);
            }
        }
        if (!foliage.isEmpty()) {
            mc.getBlockColors().registerBlockColorHandler(new IBlockColor() {
                @Override
                public int colorMultiplier(IBlockState state, IBlockAccess world, BlockPos pos, int tintIndex) {
                    return world != null && pos != null ? BiomeColorHelper.getFoliageColorAtPos(world, pos) : ColorizerFoliage.getFoliageColorBasic();
                }
            }, foliage.toArray(new Block[0]));
            mc.getItemColors().registerItemColorHandler(new IItemColor() {
                @Override
                public int colorMultiplier(ItemStack stack, int tintIndex) {
                    return ColorizerFoliage.getFoliageColorBasic();
                }
            }, foliageItems.toArray(new Item[0]));
        }
        if (!grass.isEmpty()) {
            mc.getBlockColors().registerBlockColorHandler(new IBlockColor() {
                @Override
                public int colorMultiplier(IBlockState state, IBlockAccess world, BlockPos pos, int tintIndex) {
                    return world != null && pos != null ? BiomeColorHelper.getGrassColorAtPos(world, pos) : ColorizerGrass.getGrassColor(0.5D, 1.0D);
                }
            }, grass.toArray(new Block[0]));
            mc.getItemColors().registerItemColorHandler(new IItemColor() {
                @Override
                public int colorMultiplier(ItemStack stack, int tintIndex) {
                    return ColorizerGrass.getGrassColor(0.5D, 1.0D);
                }
            }, grassItems.toArray(new Item[0]));
        }
    }

    /** Self-test: block states whose model did not load, as "name (n states)". */
    public static List<String> missingModels() {
        Minecraft mc = Minecraft.getMinecraft();
        IBakedModel missing = mc.getBlockRendererDispatcher().getBlockModelShapes().getModelManager().getMissingModel();
        List<String> broken = new ArrayList<String>();
        for (Map.Entry<String, Block> entry : ModernContent.BLOCKS.entrySet()) {
            int bad = 0, all = 0;
            for (IBlockState state : entry.getValue().getBlockState().getValidStates()) {
                all++;
                if (mc.getBlockRendererDispatcher().getModelForState(state) == missing) {
                    bad++;
                }
            }
            if (bad > 0) {
                broken.add(entry.getKey() + " (" + bad + " of " + all + " states)");
            } else {
                // A model can load and still show the purple-and-black placeholder where a texture is missing.
                java.util.Set<String> lost = new java.util.TreeSet<String>();
                int faces = 0;
                for (IBlockState state : entry.getValue().getBlockState().getValidStates()) {
                    IBakedModel model = mc.getBlockRendererDispatcher().getModelForState(state);
                    List<net.minecraft.client.renderer.block.model.BakedQuad> quads = new ArrayList<net.minecraft.client.renderer.block.model.BakedQuad>();
                    try {
                        quads.addAll(model.getQuads(state, null, 0L));
                        for (EnumFacing side : EnumFacing.values()) {
                            quads.addAll(model.getQuads(state, side, 0L));
                        }
                    } catch (RuntimeException e) {
                        lost.add("error " + e);
                    }
                    for (net.minecraft.client.renderer.block.model.BakedQuad quad : quads) {
                        if (quad.getSprite() == null || quad.getSprite().getIconName().contains("missingno")) {
                            lost.add("placeholder texture");
                        }
                    }
                    faces += quads.size();
                }
                // A sign is drawn by the game itself, and a wall or fence has no sides until it stands somewhere:
                // only a block with no face in any state is a broken model.
                if (faces == 0 && !(entry.getValue() instanceof net.minecraft.block.BlockSign)) {
                    lost.add("no faces");
                }
                if (!lost.isEmpty()) {
                    broken.add(entry.getKey() + " " + lost);
                }
            }
        }
        for (Map.Entry<String, Item> entry : ModernContent.ITEMS.entrySet()) {
            if (mc.getRenderItem().getItemModelMesher().getItemModel(new ItemStack(entry.getValue())) == missing) {
                broken.add(entry.getKey() + " (item)");
            }
        }
        return broken;
    }

    private static boolean placeholder(net.minecraft.client.renderer.texture.TextureAtlasSprite sprite) {
        return sprite == null || sprite.getIconName().contains("missingno");
    }

    private static boolean placeholder(IBakedModel model, IBlockState state) {
        try {
            for (net.minecraft.client.renderer.block.model.BakedQuad quad : model.getQuads(state, null, 0L)) {
                if (placeholder(quad.getSprite())) {
                    return true;
                }
            }
            for (EnumFacing side : EnumFacing.values()) {
                for (net.minecraft.client.renderer.block.model.BakedQuad quad : model.getQuads(state, side, 0L)) {
                    if (placeholder(quad.getSprite())) {
                        return true;
                    }
                }
            }
        } catch (RuntimeException e) {
            return false; // a model that needs a world around it; not what this looks for
        }
        return false;
    }

    /**
     * Self-test: everything in the game that would show the purple-and-black placeholder: a
     * block (in its usual state, or in any state for the listed mods), the dust it breaks
     * into, or an item in the hand. Returns "mod: n" counts for every mod and the names for
     * the mods asked about.
     */
    public static List<String> placeholders(java.util.Set<String> detailed) {
        Minecraft mc = Minecraft.getMinecraft();
        java.util.Map<String, Integer> counts = new java.util.TreeMap<String, Integer>();
        java.util.Map<String, List<String>> names = new java.util.TreeMap<String, List<String>>();
        for (Block block : Block.REGISTRY) {
            if (block.getRegistryName() == null) {
                continue;
            }
            String mod = block.getRegistryName().getNamespace();
            boolean all = detailed.contains(mod);
            String found = null;
            try {
                List<IBlockState> states = all ? block.getBlockState().getValidStates() : java.util.Collections.singletonList(block.getDefaultState());
                for (IBlockState state : states) {
                    if (state.getRenderType() != net.minecraft.util.EnumBlockRenderType.MODEL) {
                        continue;
                    }
                    IBakedModel model = mc.getBlockRendererDispatcher().getModelForState(state);
                    if (placeholder(model, state)) {
                        found = "block";
                        break;
                    }
                    if (placeholder(model.getParticleTexture())) {
                        found = "its dust";
                    }
                }
            } catch (RuntimeException e) {
                continue;
            }
            if (found != null) {
                note(counts, names, mod, block.getRegistryName().getPath() + " (" + found + ")");
            }
        }
        for (Item item : Item.REGISTRY) {
            if (item.getRegistryName() == null) {
                continue;
            }
            String mod = item.getRegistryName().getNamespace();
            net.minecraft.util.NonNullList<ItemStack> stacks = net.minecraft.util.NonNullList.create();
            try {
                if (detailed.contains(mod)) {
                    item.getSubItems(net.minecraft.creativetab.CreativeTabs.SEARCH, stacks);
                }
            } catch (RuntimeException e) {
                stacks.clear();
            }
            if (stacks.isEmpty()) {
                stacks.add(new ItemStack(item));
            }
            for (ItemStack stack : stacks) {
                try {
                    IBakedModel model = mc.getRenderItem().getItemModelWithOverrides(stack, null, null);
                    if (!model.isBuiltInRenderer() && placeholder(model, null)) {
                        note(counts, names, mod, item.getRegistryName().getPath() + (stack.getMetadata() != 0 ? "@" + stack.getMetadata() : "") + " (item)");
                        break;
                    }
                } catch (RuntimeException e) {
                    break;
                }
            }
        }
        List<String> lines = new ArrayList<String>();
        StringBuilder summary = new StringBuilder();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            summary.append(summary.length() == 0 ? "" : ", ").append(entry.getKey()).append(' ').append(entry.getValue());
        }
        lines.add("placeholder textures by mod: " + (summary.length() == 0 ? "none" : summary));
        for (String mod : detailed) {
            if (names.containsKey(mod)) {
                lines.add("placeholder textures in " + mod + ": " + names.get(mod));
            }
        }
        return lines;
    }

    private static void note(java.util.Map<String, Integer> counts, java.util.Map<String, List<String>> names, String mod, String what) {
        Integer old = counts.get(mod);
        counts.put(mod, old == null ? 1 : old + 1);
        List<String> list = names.get(mod);
        if (list == null) {
            list = new ArrayList<String>();
            names.put(mod, list);
        }
        if (list.size() < 60) {
            list.add(what);
        }
    }
}
