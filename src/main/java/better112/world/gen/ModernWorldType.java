package better112.world.gen;

import better112.Better112;
import better112.world.ModernTerrain;

import net.minecraft.world.World;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.BiomeProvider;
import net.minecraft.world.gen.IChunkGenerator;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * "Modern Terrain" in the world type list: 1.18+ terrain, caves and biome layout, made from
 * the worldgen data of a modern Minecraft installed on this computer. Only the overworld
 * changes; a world keeps this type for good, like any other world type.
 */
public final class ModernWorldType extends WorldType {

    public static final String NAME = "better112modern";

    public ModernWorldType() {
        super(NAME);
    }

    @Override
    public BiomeProvider getBiomeProvider(World world) {
        // Only the overworld, and only where chunks are made: a client reads biomes from the chunks it is sent.
        if (world.isRemote || world.provider.getDimension() != 0) {
            return super.getBiomeProvider(world);
        }
        return new ModernBiomeProvider(ModernWorld.terrain(world), world.getSeed());
    }

    @Override
    public IChunkGenerator getChunkGenerator(World world, String generatorOptions) {
        BiomeProvider provider = world.getBiomeProvider();
        if (world.provider.getDimension() != 0 || !(provider instanceof ModernBiomeProvider)) {
            return super.getChunkGenerator(world, generatorOptions);
        }
        ModernTerrain terrain = ((ModernBiomeProvider) provider).terrain;
        Better112.LOG.info("Modern terrain for {} (seed {}); biomes from other mods in the layout: {}",
                world.getWorldInfo().getWorldName(), world.getSeed(), ModernBiomes.describeModBiomes());
        return new ModernChunkGenerator(world, world.getSeed(), world.getWorldInfo().isMapFeaturesEnabled(), terrain);
    }

    /** Modern clouds float at 192; at the old 128 they would cut through every mountain. */
    @Override
    @SideOnly(Side.CLIENT)
    public float getCloudHeight() {
        return 192.0F;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public boolean hasInfoNotice() {
        return true;
    }

    /** Listed when creating a world only if the modern data was found. */
    @Override
    @SideOnly(Side.CLIENT)
    public boolean canBeCreated() {
        return ModernWorld.available();
    }
}
