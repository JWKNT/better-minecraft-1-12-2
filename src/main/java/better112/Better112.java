package better112;

import java.io.File;
import java.util.Map;

import better112.config.BetterConfig;

import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLConstructionEvent;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLLoadCompleteEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartedEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.network.NetworkCheckHandler;
import net.minecraftforge.fml.relauncher.Side;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Better 1.12.2: quality-of-life for the version the big expert packs live on. Most of it happens in the game
 * client (see ClientSetup), so a client with the mod can join any server, with the mod or without it. A dedicated
 * server with the mod has /locatebiome, the Modern Terrain world type and the imported blocks and biomes (read
 * from the jars in better112/modern_mods), and then every player needs the mod; every integration with another
 * mod is optional.
 */
@Mod(modid = Better112.MODID, name = Better112.NAME, version = Better112.VERSION,
        acceptedMinecraftVersions = "[1.12.2]", dependencies = "after:jei")
public class Better112 {

    public static final String MODID = "better112";
    public static final String NAME = "Better 1.12.2";
    public static final String VERSION = "0.1.2";
    public static final Logger LOG = LogManager.getLogger("Better112");

    /**
     * Who may connect. A server with the mod has the imported blocks and biomes, so every player
     * who joins it needs the same version of the mod. A player with the mod may join any server:
     * one without the mod simply has none of those blocks.
     */
    @NetworkCheckHandler
    public boolean checkRemote(Map<String, String> mods, Side remote) {
        return remote != Side.CLIENT || VERSION.equals(mods.get(MODID));
    }

    @Mod.EventHandler
    public void construct(FMLConstructionEvent event) {
        // The patcher loads the config before Minecraft starts; this covers a dev workspace
        // where the mod is on the classpath without its coremod half, and dedicated servers.
        BetterConfig.loadIfNeeded(new File(Loader.instance().getConfigDir(), "better112.cfg"));
        if (event.getSide().isClient()) {
            ClientSetup.construct();
        } else {
            // The imported blocks and biomes, from the jars in better112/modern_mods: the same ones the players' games read.
            better112.content.ModernContent.prepare(Loader.instance().getConfigDir().getParentFile());
        }
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        // Modern Terrain and the imported content on both sides: single player, and a dedicated server.
        better112.world.gen.ModernWorld.init();
        better112.content.ModernContent.init();
        better112.content.ModernContent.setSaplingTrees(new better112.content.ModernContent.SaplingTrees() {
            @Override
            public boolean grow(String sapling, net.minecraft.world.World world, net.minecraft.util.math.BlockPos pos, java.util.Random random) {
                return better112.world.gen.ImportedBiomes.growSapling(sapling, world, pos, random);
            }
        });
        if (event.getSide().isClient()) {
            ClientSetup.init();
        }
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        if (event.getSide().isClient()) {
            ClientSetup.postInit();
        }
    }

    @Mod.EventHandler
    public void loadComplete(FMLLoadCompleteEvent event) {
        if (event.getSide().isClient()) {
            ClientSetup.loadComplete();
        }
    }

    /** Single player or a dedicated server: either way /locatebiome is offered. */
    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new better112.qol.LocateBiome());
    }

    @Mod.EventHandler
    public void serverStarted(FMLServerStartedEvent event) {
        if (FMLCommonHandler.instance().getSide().isClient()) {
            ClientSetup.serverStarted();
        }
    }
}
