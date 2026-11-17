package better112.config;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every setting the mod owns, as plain static fields so the render hooks can read them at
 * the cost of a field load. Stored as key=value lines in config/better112.cfg.
 *
 * Pure Java on purpose: this class is loaded before Minecraft's own classes exist.
 */
public final class BetterConfig {

    private BetterConfig() {}

    // --- Menu ---
    public static boolean replaceVideoSettings = true;

    /** GUI scale times two when it is a half step (5 is 2.5x); 0 when it is a whole number. */
    public static int guiScaleHalves = 0;
    /** Half GUI scales are drawn at twice the size at a whole scale and reduced, which keeps every pixel of the interface the same width. */
    public static boolean guiScaleSharp = true;

    // --- Quality ---
    /** 0 follows Graphics, 1 fancy, 2 fast. */
    public static int weatherQuality = 0;
    /** 0 follows Graphics, 1 fancy, 2 fast, 3 smart (fancy with hidden inner faces). */
    public static int leavesQuality = 0;
    /** Held and dropped light sources light the world: 0 off, 1 fast, 2 fancy. */
    public static int dynamicLights = 1;
    /** Biome colour blend radius in blocks: 0 is off, 1 is the vanilla 3x3. */
    public static int biomeBlend = 1;
    /** Percent of the normal entity render distance. */
    public static int entityDistance = 100;
    public static boolean vignette = true;

    // --- Shaders (better112.shader) ---
    /** 0 off, 1 light, 2 intense, 3 custom (the settings below). */
    public static int shaders = 0;
    /** Custom: sun light colour and the light map read for shade. */
    public static boolean shaderLighting = true;
    /** Custom: sun shadows, 0 off, 1 near, 2 far, 3 very far. */
    public static int shaderShadows = 2;
    /** Custom: leaves and plants sway. */
    public static boolean shaderWaving = true;
    /** Custom: water, 0 the game's own, 1 waves and the sky in it, 2 also the world reflected. */
    public static int shaderWater = 2;
    /** Custom: light shafts from the sun. */
    public static boolean shaderRays = true;
    /** Custom: ambient occlusion from the depth of the world. */
    public static boolean shaderAo = true;
    /** Custom: bloom strength, percent. */
    public static int shaderBloom = 60;
    /** Custom: colour grade strength, percent. */
    public static int shaderColor = 70;

    // --- Performance ---
    /** Chunk builder threads, 0 keeps the game's own choice. */
    public static int chunkThreads = 0;
    public static boolean entityCulling = true;
    public static boolean animateVisibleOnly = true;
    public static boolean particleCulling = true;
    /** Percent of each block entity's own render distance. */
    public static int blockEntityDistance = 100;
    /** Frame cap while the window is in the background, 0 is no cap. */
    public static int backgroundFps = 0;

    // --- Animations ---
    public static boolean animations = true;
    public static boolean animWater = true;
    public static boolean animLava = true;
    public static boolean animFire = true;
    public static boolean animPortal = true;
    public static boolean animBlocks = true;

    // --- Particles ---
    public static boolean particles = true;
    public static boolean rainSplash = true;
    public static boolean blockBreak = true;
    public static boolean blockBreaking = true;
    /** Particle groups switched off on the Particles page (see ParticleGroups). */
    public static final Set<String> disabledParticles =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    // --- Details ---
    public static boolean sky = true;
    public static boolean stars = true;
    public static boolean sun = true;
    public static boolean moon = true;
    public static boolean weather = true;
    /** 0 vanilla, 1 pushed back, 2 off. */
    public static int fog = 0;
    /** Cloud layer height; 128 is vanilla. */
    public static int cloudHeight = 128;

    // --- Render ---
    public static boolean itemFrames = true;
    public static boolean armorStands = true;
    public static boolean paintings = true;
    public static boolean pistons = true;
    public static boolean beaconBeam = true;
    public static boolean enchantingBook = true;
    public static boolean playerNameTags = true;

    // --- Extras ---
    public static boolean showFps = false;
    public static boolean showFpsExtended = false;
    public static boolean showCoords = false;
    /** 0 top left, 1 top right, 2 bottom left, 3 bottom right. */
    public static int overlayCorner = 0;
    public static boolean toasts = true;

    // --- Textures ---
    public static boolean modernTextures = true;

    // --- Accessibility (options vanilla gained after 1.12) ---
    /** Percent of the field-of-view change from sprinting, speed effects and drawing a bow. */
    public static int fovEffects = 100;
    /** Percent of the camera roll when hurt. */
    public static int damageTilt = 100;
    /** Percent of the screen wobble from nausea and Nether portals. */
    public static int distortionEffects = 100;
    /** Strength of the blur behind menus opened over the world, 0 for none (1.21's Menu Background Blur). */
    public static int menuBlur = 5;
    public static boolean toggleSprint = false;
    public static boolean toggleSneak = false;
    /** Percent of the normal speed of the enchantment glint (1.19's Glint Speed). */
    public static int glintSpeed = 100;
    /** Percent of the normal brightness of the enchantment glint (1.19's Glint Strength). */
    public static int glintStrength = 100;

    // --- HUD ---
    public static boolean pickupNotifier = true;
    public static boolean effectTimers = true;
    public static boolean equipmentHud = false;
    public static boolean durabilityTooltip = true;
    /** Lists what an AE2 storage cell holds in its tooltip. */
    public static boolean cellContents = true;

    // --- Modern content ---
    /** Blocks (and, in Modern Terrain worlds, biomes) read from a Biomes O' Plenty 1.20 jar on this computer. */
    public static boolean importBiomesOPlenty = true;
    /** The blocks and biomes Minecraft has gained since 1.12.2 (cherry groves, mangrove swamps, deepslate...), read from a modern Minecraft on this computer. */
    public static boolean importModernVanilla = true;

    // --- Chat ---
    /** :smile: and friends are drawn as pictures in chat. */
    public static boolean chatEmotes = true;
    /** Shift+T on an item, or [item] in a message, shows the item in chat. */
    public static boolean chatItemLinks = true;

    // --- Conveniences later versions of the game gained ---
    /** Shift + JEI's bookmark key on a recipe bookmarks the recipe itself (left to JEI Utilities when that is installed). */
    public static boolean recipeBookmarks = true;
    /** A list of matching commands and arguments appears while a command is typed. */
    public static boolean commandSuggestions = true;
    /** F3+F4 picks the game mode; F3+C copies where you stand; F3+I copies the block or entity looked at. */
    public static boolean debugKeys = true;
    /** "Saving world" in the corner while a single-player world is being saved. */
    public static boolean saveIndicator = true;
    /** The swap-hands key over an inventory slot swaps that slot with the off hand. */
    public static boolean offhandSwapInGui = true;
    /** A Game Rules button among the world options when creating a world. */
    public static boolean gameRulesButton = true;
    /** A search box and a conflicts filter on the Controls screen. */
    public static boolean keybindSearch = true;
    /** Armor, weapons and tools show the tooltip of what is worn or held in their place beside their own. */
    public static boolean equipmentCompare = true;
    /** A sound and a message when held or worn gear is close to breaking. */
    public static boolean durabilityWarning = true;
    /** Where you died, on the death screen and in chat. */
    public static boolean deathCoords = true;
    /** Holding the freelook key looks around the player in third person without turning them. */
    public static boolean freelook = true;
    /** The mouse wheel changes how far the zoom key zooms, and the zoom eases in and out. */
    public static boolean zoomScroll = true;
    /** A star on items newly picked up, in any inventory, until the mouse passes over them. */
    public static boolean newItemMarks = true;
    /** A search box on the Select World screen. */
    public static boolean worldSearch = true;
    /** The time each chat message arrived, in front of it. */
    public static boolean chatTimestamps = true;
    /** A right click on a chat line copies the message; chat closed with Escape keeps what was typed. */
    public static boolean chatCopy = true;
    /** The name of a biome or dimension fades in when you arrive, as Traveler's Titles shows it. */
    public static boolean biomeTitles = true;
    /** F5 goes from first person to the back view and straight back, skipping the view of your own face. */
    public static boolean skipFrontView = false;
    /** Lava is easier to see through with Fire Resistance or in spectator mode, as since 1.17. */
    public static boolean clearLavaFog = true;
    /** Percent of the game's volume while its window is in the background (100 leaves it as it is). */
    public static int backgroundVolume = 50;
    /** Leaves drift down from trees: 0 none, 1 a few, 2 many. */
    public static int fallingLeaves = 1;
    /** Grass blocks show grass down their sides where the slope continues: 0 off, 1 fast (every side), 2 fancy. */
    public static int betterGrass = 2;
    /** While JEI's search box has the keyboard, items in the open inventory that the search does not match are dimmed. */
    public static boolean jeiSearchHighlight = true;
    /** Items of uncommon, rare and epic rarity get a frame of their colour in slots and on their tooltip. */
    public static boolean rarityBorders = true;
    /** Seeing under water improves the longer you stay under, up to a good distance, as since 1.13. */
    public static boolean clearerWater = true;
    /** Fireflies over grass on warm nights: 0 none, 1 a few, 2 many. */
    public static int fireflies = 1;
    /** The F3 screen also names the fluid and the entity you are looking at, and the block's ore names. */
    public static boolean debugTargets = true;
    /** A key (Z) in inventories shows the hovered item large beside the screen, as the Item Zoom mod does. */
    public static boolean itemZoom = true;
    /** Frames per second once nothing has been pressed or moved for {@link #idleMinutes} minutes (0 for no cap), as Dynamic FPS does. */
    public static int idleFps = 10;
    public static int idleMinutes = 5;
    /** Rain makes small rings on water. */
    public static boolean rainRipples = true;
    /** Riding shows your own food bar as well as the mount's health, and the experience bar unless a horse jump is charging. */
    public static boolean betterMountHud = true;
    /** In first person, the swirls of your own potion effects are not drawn in front of your eyes. */
    public static boolean hideOwnEffectParticles = true;

    // --- World colours (cosmetic) ---
    /** 0 the game's own sky, 1 the picked colour, 2 and up the presets in WorldColors. */
    public static int skyPreset = 0;
    public static int skyCustom = 0x78A7FF;
    public static int waterPreset = 0;
    public static int waterCustom = 0x3F76E4;

    // --- Quests ---
    public static boolean ftbQuests = true;

    // --- Recipes ---
    public static boolean oreDictTooltips = true;

    private static File file;

    public static void loadIfNeeded(File configFile) {
        if (file == null) {
            load(configFile);
        }
    }

    public static void load(File configFile) {
        file = configFile;
        if (!configFile.isFile()) {
            return;
        }
        try {
            for (String line : Files.readAllLines(configFile.toPath(), StandardCharsets.UTF_8)) {
                int eq = line.indexOf('=');
                if (eq <= 0 || line.startsWith("#")) {
                    continue;
                }
                set(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        } catch (Exception e) {
            System.err.println("[Better112] Could not read " + configFile + ": " + e);
        }
    }

    private static void set(String key, String value) {
        try {
            if (key.equals("disabledParticles")) {
                disabledParticles.clear();
                for (String part : value.split(",")) {
                    if (!part.trim().isEmpty()) {
                        disabledParticles.add(part.trim());
                    }
                }
                return;
            }
            Field field = BetterConfig.class.getField(key);
            if (!Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())) {
                return;
            }
            if (field.getType() == boolean.class) {
                field.setBoolean(null, Boolean.parseBoolean(value));
            } else if (field.getType() == int.class) {
                field.setInt(null, Integer.parseInt(value));
            }
        } catch (NoSuchFieldException ignored) {
            // A setting from another version of the mod.
        } catch (Exception e) {
            System.err.println("[Better112] Bad config value " + key + "=" + value);
        }
    }

    public static void save() {
        if (file == null) {
            return;
        }
        try {
            List<String> lines = new ArrayList<String>();
            for (Field field : BetterConfig.class.getFields()) {
                int mod = field.getModifiers();
                if (!Modifier.isStatic(mod) || Modifier.isFinal(mod)) {
                    continue;
                }
                lines.add(field.getName() + "=" + field.get(null));
            }
            StringBuilder particles = new StringBuilder();
            for (String name : new TreeSet<String>(disabledParticles)) {
                if (particles.length() > 0) {
                    particles.append(',');
                }
                particles.append(name);
            }
            lines.add("disabledParticles=" + particles);
            File parent = file.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Better112] Could not save " + file + ": " + e);
        }
    }
}
