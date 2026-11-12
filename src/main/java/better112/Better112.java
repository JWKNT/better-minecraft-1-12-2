package better112;

import java.io.File;

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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Better 1.12.2: quality-of-life for the version the big expert packs live on. Almost everything happens in the
 * game client (see ClientSetup), so a client with the mod can join any server, with the mod or without it. On a
 * dedicated server the mod adds /locatebiome for every player and nothing else; every integration with another
 * mod is optional.
 */
@Mod(modid = Better112.MODID, name = Better112.NAME, version = Better112.VERSION,
        acceptableRemoteVersions = "*", acceptedMinecraftVersions = "[1.12.2]", dependencies = "after:jei")
public class Better112 {

    public static final String MODID = "better112";
    public static final String NAME = "Better 1.12.2";
    public static final String VERSION = "0.1.0";
    public static final Logger LOG = LogManager.getLogger("Better112");

    @Mod.EventHandler
    public void construct(FMLConstructionEvent event) {
        // The patcher loads the config before Minecraft starts; this covers a dev workspace
        // where the mod is on the classpath without its coremod half, and dedicated servers.
        BetterConfig.loadIfNeeded(new File(Loader.instance().getConfigDir(), "better112.cfg"));
        if (event.getSide().isClient()) {
            ClientSetup.construct();
        }
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
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
