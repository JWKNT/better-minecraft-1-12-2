package better112;

import better112.textures.ModernTextures;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.FMLClientHandler;
import net.minecraftforge.fml.common.Loader;

/**
 * Everything the mod does in a game client, which is nearly all of it. Better112 calls in here only on the
 * client, so a dedicated server never loads a class that needs a game window.
 */
final class ClientSetup {

    private ClientSetup() {}

    static void construct() {
        Hooks.setMainThread(Thread.currentThread());
        // Resource packs are read right after construction, so the texture pack goes in now.
        ModernTextures.install();
        // The first texture atlas is stitched before mods initialise; the colourless water sprites join it here.
        better112.cosmetic.WorldColors.registerEarly();
        // So does the ring rain makes on water.
        MinecraftForge.EVENT_BUS.register(new better112.cosmetic.RainRipples());
        // And the textures VintageFix would leave out of it (EvilCraft's broom parts in DJ2).
        better112.textures.LateSprites.install(net.minecraft.client.Minecraft.getMinecraft().gameDir);
        // Blocks of modern mods have to be known before the registries open, and their looks before resources load.
        // A dedicated server reads the same jars in Better112.construct, so that both sides have the same blocks.
        better112.content.ContentClient.addPack(better112.content.ModernContent.prepare(net.minecraft.client.Minecraft.getMinecraft().gameDir));
        MinecraftForge.EVENT_BUS.register(better112.content.ContentClient.class);
    }

    static void init() {
        better112.content.ContentClient.init();
        ClientTweaks.init();
        better112.chat.Emotes.init();
        MinecraftForge.EVENT_BUS.register(new better112.chat.ChatRewriter());
        MinecraftForge.EVENT_BUS.register(new better112.chat.ItemLink());
        MinecraftForge.EVENT_BUS.register(new better112.chat.EmoteSuggest());
        net.minecraftforge.fml.client.registry.ClientRegistry.registerKeyBinding(better112.chat.ItemLink.KEY_LINK);
        MinecraftForge.EVENT_BUS.register(new better112.chat.CommandSuggest());
        MinecraftForge.EVENT_BUS.register(new better112.qol.DebugKeys());
        MinecraftForge.EVENT_BUS.register(new better112.qol.Conveniences());
        MinecraftForge.EVENT_BUS.register(new better112.qol.GameRulesScreen.Events());
        MinecraftForge.EVENT_BUS.register(new better112.qol.ControlsSearch());
        MinecraftForge.EVENT_BUS.register(new better112.qol.MenuBlur());
        MinecraftForge.EVENT_BUS.register(new better112.qol.EquipmentCompare());
        MinecraftForge.EVENT_BUS.register(new better112.qol.GearWatch());
        MinecraftForge.EVENT_BUS.register(new better112.qol.Freelook());
        better112.qol.Freelook.register();
        MinecraftForge.EVENT_BUS.register(new better112.qol.NewItems());
        MinecraftForge.EVENT_BUS.register(new better112.qol.WorldSearch());
        MinecraftForge.EVENT_BUS.register(new better112.chat.ChatExtras());
        MinecraftForge.EVENT_BUS.register(new better112.qol.Titles());
        MinecraftForge.EVENT_BUS.register(new better112.qol.ViewTweaks());
        MinecraftForge.EVENT_BUS.register(new better112.cosmetic.FallingLeaves());
        MinecraftForge.EVENT_BUS.register(new better112.qol.RarityBorders());
        MinecraftForge.EVENT_BUS.register(new better112.cosmetic.Fireflies());
        MinecraftForge.EVENT_BUS.register(new better112.qol.DebugTargets());
        MinecraftForge.EVENT_BUS.register(new better112.qol.ItemZoom());
        better112.qol.ItemZoom.register();
        MinecraftForge.EVENT_BUS.register(new better112.qol.Idle());
        if (Loader.isModLoaded("jei")) {
            MinecraftForge.EVENT_BUS.register(better112.jei.JeiBridge.recipeMarkEvents());
            MinecraftForge.EVENT_BUS.register(better112.jei.JeiBridge.searchHighlight());
        }
        Hooks.refresh();
    }

    static void postInit() {
        Hooks.animationsHandledBy = findAnimationCuller();
        Hooks.refresh();
        better112.tree.RecipeTreeKeys.init();
        // After every mod has had its say about how water is tinted.
        better112.cosmetic.WorldColors.init();
    }

    static void loadComplete() {
        Hooks.refresh();
        ModernTextures.selfCheck();
        better112.test.AutoTest.start();
    }

    /** A single-player world has started: the game rules picked when it was created are applied. */
    static void serverStarted() {
        better112.qol.GameRulesScreen.worldStarted(net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance());
    }

    /** Names the mod, if any, that already animates only the textures in view. */
    private static String findAnimationCuller() {
        if (FMLClientHandler.instance().hasOptifine()) {
            return "OptiFine";
        }
        for (Class<?> type : TextureAtlasSprite.class.getInterfaces()) {
            String name = type.getName();
            if (name.endsWith(".ondemand.IAnimatedSpriteActivator")) {
                if (name.contains("normalasm")) {
                    return "FermiumASM";
                }
                if (name.contains("loliasm")) {
                    return "LoliASM";
                }
                if (name.contains("blahaj")) {
                    return "BlahajASM";
                }
                return "CensoredASM";
            }
        }
        return null;
    }
}
