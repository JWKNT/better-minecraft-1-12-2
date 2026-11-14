package better112.test;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.TreeSet;

import better112.Better112;
import better112.ClientTweaks;
import better112.Hooks;
import better112.config.BetterConfig;
import better112.core.PatchState;
import better112.gui.SodiumScreen;
import better112.perf.Occlusion;
import better112.textures.ModernTextures;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import org.lwjgl.opengl.Display;

/**
 * A scripted run of the game for checking the mod without a person at the keyboard. Off
 * unless the game is started with -Dbetter112.autotest=<script>, where the script is a
 * semicolon-separated list of steps:
 *
 *   wait:TICKS          world:flat|default      screen:PAGE        hover:ROW
 *   shot:NAME           close                   fps:SECONDS:LABEL  set:FIELD=VALUE
 *   look:YAW:PITCH      chat:TEXT               tree:ITEM_ID       gui:inventory
 *   opt:OPTION=VALUE    apply                   treeop:expand|choose|steps
 *   reload              note:TEXT               quit
 *   walk:TICKS          use:DX:DY:DZ            lightcheck:x:LABEL
 *   chatline:TEXT       linkitem:SLOT           suggest:TEXT       chatbox:TEXT     picker:OPTION
 *   world:modern:SEED   content                 showcase:WIDTH:bop|mc                biomecount:RADIUS
 *   gobiome:NAME:HEIGHT gocave:lush|dripstone   goblock:NAME:MAXY  growsapling:NAME:DX
 *   saplings            craft:ITEM              features           where:LABEL      view:CHUNKS
 *   marks:mark:ITEM|open|preview|state          cmdsuggest         modeswitch:TAPS|release
 *   copykeys            saved                   offhand:SLOT       hands:SLOT
 *   gamerule:NAME:VALUE rulesscreen             createworld        click:BUTTON_ID
 *   controls            ctlsearch:FILTER:TEXT   blur               compare:SLOT:X,Y compared
 *   warned              respawn                 itemgrid:DOMAIN,DOMAIN:PAGE
 *   freelook:on|off|turn:YAW,PITCH              zoomtest:hold|release|scroll:N|state  newitems
 *   worlds:TEXT         glint:SPEED:STRENGTH    chatcopy            chatdraft:TEXT
 *   titles              titleshow:TEXT:SUB      leaves:RADIUS       grass:RADIUS     persp:N  viewstate
 *   volume:bg|fg        worldkey:KEYCODE        rarity:ITEM         jeisearch:TEXT|off
 *   water:TICKS|state   fireflies:N             debugtargets
 *   zoom:ITEM|off       idle:MINUTES|state      ripples:N           mounthud   ride   ownfx
 *   join:HOST:PORT      online
 *
 * Results go to better112-autotest/report.txt and screenshots/ in the game folder.
 */
public final class AutoTest {

    private final Deque<String> steps = new ArrayDeque<String>();
    private final List<String> report = java.util.Collections.synchronizedList(new ArrayList<String>());
    private final Minecraft mc = Minecraft.getMinecraft();

    private int waitTicks;
    private boolean waitingForMenu = true;
    private boolean waitingForWorld;
    private int walkTicks;
    private final List<String> chatSeen = new ArrayList<String>();
    private final List<String> saplingResults = java.util.Collections.synchronizedList(new ArrayList<String>());
    private net.minecraft.util.math.Vec3d fpsStartPos;
    private int fpsStartMarked;
    private int screenWaitTicks;
    private String pendingShot;
    private int hudMode;
    private Object recording;
    private int shotDelayFrames;

    private long fpsEndNanos;
    private long fpsStartNanos;
    private String fpsLabel;
    private final List<Long> fpsFrames = new ArrayList<Long>();
    private long fpsLastFrame;

    /**
     * Shown whenever the script has no other screen up. With no screen at all the game grabs
     * the mouse, which is rude when a person is using the computer while a test runs.
     */
    private static final class IdleScreen extends GuiScreen {
        @Override
        public boolean doesGuiPauseGame() {
            return false;
        }

        @Override
        protected void keyTyped(char typedChar, int keyCode) {}
    }

    private void idle() {
        if (mc.world != null && mc.currentScreen == null) {
            mc.displayGuiScreen(new IdleScreen());
        }
    }

    private AutoTest(String script) {
        better112.hud.Hud.testScreen = IdleScreen.class;
        better112.qol.NewItems.testScreen = IdleScreen.class;
        steps.addAll(Arrays.asList(script.split(";")));
    }

    public static void start() {
        String script = System.getProperty("better112.autotest");
        if (script == null || script.trim().isEmpty()) {
            return;
        }
        File file = new File(script);
        if (file.isFile()) {
            try {
                script = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).replace('\n', ';');
            } catch (Exception e) {
                Better112.LOG.error("Could not read autotest script", e);
                return;
            }
        }
        Better112.LOG.info("Autotest script: {}", script);
        MinecraftForge.EVENT_BUS.register(new AutoTest(script));
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        try {
            tick();
        } catch (Throwable t) {
            Better112.LOG.error("Autotest step failed", t);
            report.add("ERROR " + t);
            finish();
        }
    }

    private void tick() throws Exception {
        if (waitingForMenu) {
            // Packs often replace the title screen, so wait for any screen with no world loaded.
            if (mc.currentScreen == null || mc.world != null) {
                return;
            }
            waitingForMenu = false;
            mc.gameSettings.pauseOnLostFocus = false;
            waitTicks = 20;
            report.add("patches applied: " + new TreeSet<String>(PatchState.appliedIds()));
            report.add("patches failed: " + new TreeSet<String>(PatchState.failedIds()));
            report.add("modern textures: " + ModernTextures.isAvailable() + " - " + ModernTextures.statusText());
        }
        if (waitingForWorld) {
            if (mc.player == null || mc.world == null) {
                return;
            }
            if (mc.currentScreen != null && !(mc.currentScreen instanceof IdleScreen)) {
                if (mc.currentScreen.getClass().getName().contains("GuiIngameMenu")) {
                    mc.displayGuiScreen(null);
                } else if (++screenWaitTicks < 200) {
                    return; // a loading or welcome screen; give it a moment
                }
            }
            waitingForWorld = false;
            waitTicks = 100;
        }
        idle();
        if (walkTicks > 0 && --walkTicks == 0 && mc.player != null) {
            mc.player.movementInput = new net.minecraft.util.MovementInputFromOptions(mc.gameSettings);
        }
        if (waitTicks > 0) {
            waitTicks--;
            return;
        }
        if (pendingShot != null || fpsLabel != null) {
            return;
        }
        if (steps.isEmpty()) {
            finish();
            return;
        }
        String step = steps.poll().trim();
        if (step.isEmpty()) {
            return;
        }
        Better112.LOG.info("Autotest: {}", step);
        better112.qol.Idle.keepAwake(); // a long script must not be measured at the idle frame cap
        String[] p = step.split(":", 3);
        String arg = p.length > 1 ? p[1] : "";
        switch (p[0]) {
            case "wait":
                waitTicks = Integer.parseInt(arg);
                break;
            case "world": {
                WorldType type = arg.equals("flat") ? WorldType.FLAT : WorldType.DEFAULT;
                if (arg.equals("modern")) {
                    type = WorldType.byName(better112.world.gen.ModernWorldType.NAME);
                    report.add("modern world type: " + (type == null ? "not registered" : type.getName()) + ", data "
                            + better112.world.gen.ModernWorld.source());
                    if (type == null) {
                        type = WorldType.DEFAULT;
                    }
                }
                long worldSeed = p.length > 2 ? Long.parseLong(p[2]) : 112L;
                WorldSettings settings = new WorldSettings(worldSeed, GameType.CREATIVE, true, false, type);
                settings.enableCommands();
                String name = "better112_" + (arg.isEmpty() ? "default" : arg);
                mc.launchIntegratedServer(name, name, settings);
                waitingForWorld = true;
                break;
            }
            case "screen": {
                SodiumScreen screen = new SodiumScreen(null);
                mc.displayGuiScreen(screen);
                if (!arg.isEmpty() && !screen.showPage(arg)) {
                    report.add("no such page: " + arg);
                }
                waitTicks = 4;
                break;
            }
            case "hover":
                SodiumScreen.testHoverRow = Integer.parseInt(arg);
                waitTicks = 2;
                break;
            case "shot":
                pendingShot = arg;
                shotDelayFrames = 3;
                break;
            case "close":
                SodiumScreen.testHoverRow = -1;
                mc.displayGuiScreen(mc.world != null ? new IdleScreen() : null);
                waitTicks = 4;
                break;
            case "fps":
                Display.setVSyncEnabled(false);
                mc.gameSettings.limitFramerate = 260;
                fpsLabel = p.length > 2 ? p[2] : "fps";
                fpsFrames.clear();
                fpsStartNanos = System.nanoTime();
                fpsLastFrame = fpsStartNanos;
                fpsEndNanos = fpsStartNanos + (long) (Double.parseDouble(arg) * 1.0E9D);
                fpsStartPos = mc.player == null ? null : mc.player.getPositionVector();
                fpsStartMarked = better112.perf.DynamicLights.sectionsMarked;
                break;
            case "set": {
                String[] kv = (p.length > 2 ? arg + ":" + p[2] : arg).split("=", 2);
                Field field = BetterConfig.class.getField(kv[0]);
                if (field.getType() == boolean.class) {
                    field.setBoolean(null, Boolean.parseBoolean(kv[1]));
                } else {
                    field.setInt(null, Integer.parseInt(kv[1]));
                }
                Hooks.refresh();
                ClientTweaks.applyConfig();
                better112.cosmetic.WorldColors.applyWaterSprites();
                break;
            }
            case "reload":
                mc.renderGlobal.loadRenderers();
                waitTicks = 60;
                break;
            case "look":
                if (mc.player != null) {
                    mc.player.rotationYaw = Float.parseFloat(arg);
                    mc.player.rotationPitch = p.length > 2 ? Float.parseFloat(p[2]) : 0.0F;
                }
                break;
            case "chat":
                if (mc.player != null) {
                    mc.player.sendChatMessage(p.length > 2 ? arg + ":" + p[2] : arg);
                }
                waitTicks = 5;
                break;
            case "tree":
                report.add("screen before tree: " + (mc.currentScreen == null ? "none" : mc.currentScreen.getClass().getName()));
                better112.tree.RecipeTreeKeys.openFor(p.length > 2 ? arg + ":" + p[2] : arg);
                if (mc.currentScreen instanceof better112.tree.RecipeTreeScreen) {
                    report.add("tree network: " + ((better112.tree.RecipeTreeScreen) mc.currentScreen).testNetwork());
                }
                waitTicks = 10;
                break;
            case "tooltip": {
                // tooltip:SLOT - the tooltip lines of the item in that inventory slot
                ItemStack held = mc.player == null ? ItemStack.EMPTY : mc.player.inventory.getStackInSlot(Integer.parseInt(arg));
                report.add("tooltip of " + held + ": " + (held.isEmpty() ? "" : held.getTooltip(mc.player,
                        net.minecraft.client.util.ITooltipFlag.TooltipFlags.NORMAL).toString().replaceAll("\u00a7.", "")));
                break;
            }
            case "quests":
                if (ClientTweaks.QUESTS_LOADED) {
                    mc.displayGuiScreen(better112.quests.QuestsBridge.create());
                } else {
                    report.add("Better Questing is not installed");
                }
                waitTicks = 10;
                break;
            case "questop":
                if (ClientTweaks.QUESTS_LOADED) {
                    if (arg.equals("open")) {
                        report.add("open first quest: " + better112.quests.QuestsBridge.testOpenFirstQuest(mc.currentScreen));
                    } else if (arg.equals("sidebar")) {
                        better112.quests.QuestsBridge.testExpandSidebar(mc.currentScreen, true);
                    } else if (arg.equals("chapter")) {
                        report.add("select chapter: " + better112.quests.QuestsBridge.testSelectChapter(mc.currentScreen,
                                Integer.parseInt(p.length > 2 ? p[2] : "0")));
                    } else {
                        // questop:press | questop:release | questop:escape | questop:close
                        report.add("quests " + arg + ": " + better112.quests.QuestsBridge.testOp(mc.currentScreen, arg));
                    }
                }
                // After a press, the release comes two ticks later, as in a quick click: frames are drawn
                // in between with the real button up.
                waitTicks = arg.equals("press") ? 1 : 10;
                break;
            case "questbook":
                // questbook:key - what the quest key opens; questbook:lines - the home screen's Quests button
                report.add("quest book " + arg + ": " + (ClientTweaks.QUESTS_LOADED
                        ? better112.quests.QuestsBridge.testOpen(arg) : "Better Questing is not installed"));
                waitTicks = 10;
                break;
            case "opt": {
                String[] kv = (p.length > 2 ? arg + ":" + p[2] : arg).split("=", 2);
                if (!(mc.currentScreen instanceof SodiumScreen) || !((SodiumScreen) mc.currentScreen).testSet(kv[0], kv[1])) {
                    report.add("could not set option " + kv[0]);
                }
                break;
            }
            case "apply":
                if (mc.currentScreen instanceof SodiumScreen) {
                    ((SodiumScreen) mc.currentScreen).testApply();
                }
                waitTicks = 10;
                break;
            case "gui":
                if (arg.equals("inventory") && mc.player != null) {
                    mc.displayGuiScreen(mc.playerController.isInCreativeMode()
                            ? new net.minecraft.client.gui.inventory.GuiContainerCreative(mc.player)
                            : new net.minecraft.client.gui.inventory.GuiInventory(mc.player));
                }
                waitTicks = 10;
                break;
            case "treeop": {
                if (mc.currentScreen instanceof better112.tree.RecipeTreeScreen) {
                    better112.tree.RecipeTreeScreen screen = (better112.tree.RecipeTreeScreen) mc.currentScreen;
                    if (arg.equals("choose")) {
                        screen.testChooseRoot();
                    } else if (arg.equals("expand")) {
                        screen.testExpandAll();
                    } else if (arg.equals("steps")) {
                        screen.testShowSteps(true);
                    }
                }
                waitTicks = 4;
                break;
            }
            case "walk": {
                // Walk forward for a number of ticks. Key presses do nothing while a screen is
                // open, so the player's movement input is swapped for one that just walks,
                // hops over single blocks and turns away from walls and trees.
                walkTicks = Integer.parseInt(arg);
                if (mc.player != null) {
                    mc.player.movementInput = new net.minecraft.util.MovementInput() {
                        private int blocked;

                        @Override
                        public void updatePlayerMoveState() {
                            moveForward = 1.0F;
                            forwardKeyDown = true;
                            jump = mc.player.collidedHorizontally || mc.player.isInWater();
                            blocked = mc.player.collidedHorizontally ? blocked + 1 : 0;
                            if (blocked > 8) {
                                mc.player.rotationYaw += 35.0F;
                                blocked = 0;
                            }
                        }
                    };
                }
                break;
            }
            case "use": {
                // use:DX:DY:DZ - right-click the block at that offset from the player, server side
                final String[] off = (p.length > 2 ? arg + ":" + p[2] : arg).split(":");
                final net.minecraft.server.integrated.IntegratedServer server = mc.getIntegratedServer();
                if (server != null && mc.player != null) {
                    final java.util.UUID id = mc.player.getUniqueID();
                    server.addScheduledTask(new Runnable() {
                        @Override
                        public void run() {
                            net.minecraft.entity.player.EntityPlayerMP sp = server.getPlayerList().getPlayerByUUID(id);
                            net.minecraft.util.math.BlockPos pos = new net.minecraft.util.math.BlockPos(sp)
                                    .add(Integer.parseInt(off[0]), Integer.parseInt(off[1]), Integer.parseInt(off[2]));
                            net.minecraft.block.state.IBlockState state = sp.world.getBlockState(pos);
                            boolean used = state.getBlock().onBlockActivated(sp.world, pos, state, sp,
                                    net.minecraft.util.EnumHand.MAIN_HAND, net.minecraft.util.EnumFacing.UP, 0.5F, 1.0F, 0.5F);
                            Better112.LOG.info("Self-test used {} at {}: {}", state.getBlock().getRegistryName(), pos, used);
                        }
                    });
                }
                waitTicks = 20;
                break;
            }
            case "view":
                // view:CHUNKS - render distance
                mc.gameSettings.renderDistanceChunks = Integer.parseInt(arg);
                mc.renderGlobal.setDisplayListEntitiesDirty();
                break;
            case "biomecount":
                // biomecount:RADIUS - which biomes the world's layout holds around the origin
                if (mc.getIntegratedServer() != null) {
                    net.minecraft.world.biome.BiomeProvider provider = mc.getIntegratedServer().getWorld(0).getBiomeProvider();
                    int radius = Integer.parseInt(arg) >> 2;
                    java.util.Map<String, Integer> counts = new java.util.HashMap<String, Integer>();
                    net.minecraft.world.biome.Biome[] row = null;
                    int samples = 0;
                    for (int qz = -radius; qz < radius; qz += 4) {
                        row = provider.getBiomesForGeneration(row, -radius, qz, radius * 2, 1);
                        for (int i = 0; i < radius * 2; i += 4) {
                            String id = String.valueOf(row[i].getRegistryName());
                            Integer old = counts.get(id);
                            counts.put(id, old == null ? 1 : old + 1);
                            samples++;
                        }
                    }
                    java.util.List<java.util.Map.Entry<String, Integer>> sorted = new ArrayList<java.util.Map.Entry<String, Integer>>(counts.entrySet());
                    java.util.Collections.sort(sorted, new java.util.Comparator<java.util.Map.Entry<String, Integer>>() {
                        @Override
                        public int compare(java.util.Map.Entry<String, Integer> a, java.util.Map.Entry<String, Integer> b) {
                            return b.getValue() - a.getValue();
                        }
                    });
                    StringBuilder text = new StringBuilder("biomes within " + arg + " blocks (" + counts.size() + " kinds):");
                    for (java.util.Map.Entry<String, Integer> entry : sorted) {
                        text.append(String.format(" %s %.1f%%,", entry.getKey().replace("minecraft:", ""), 100.0D * entry.getValue() / samples));
                    }
                    report.add(text.toString());
                }
                break;
            case "chatline": {
                // chatline:TEXT - a chat message from "Tester", through the same event a real one takes
                String said = p.length > 2 ? arg + ":" + p[2] : arg;
                net.minecraft.util.text.ITextComponent line = new net.minecraft.util.text.TextComponentTranslation("chat.type.text",
                        "Tester", new net.minecraft.util.text.TextComponentString(said));
                line = net.minecraftforge.event.ForgeEventFactory.onClientChat(net.minecraft.util.text.ChatType.CHAT, line);
                if (line != null) {
                    mc.ingameGUI.getChatGUI().printChatMessage(line);
                    StringBuilder shown = new StringBuilder();
                    for (char ch : line.getUnformattedText().toCharArray()) {
                        shown.append(ch >= '\uE000' && ch <= '\uF8FF' ? "<" + better112.chat.Emotes.nameOf(ch) + ">" : String.valueOf(ch));
                    }
                    report.add("chat line: " + shown);
                }
                break;
            }
            case "chatpieces": {
                // chatpieces:A^B^C - one chat line cut into separate pieces of text (every second one italic), as servers do
                net.minecraft.util.text.ITextComponent line = new net.minecraft.util.text.TextComponentString("<Tester> ");
                String[] cut = (p.length > 2 ? arg + ":" + p[2] : arg).split("\\^");
                for (int i = 0; i < cut.length; i++) {
                    net.minecraft.util.text.ITextComponent piece = new net.minecraft.util.text.TextComponentString(cut[i]);
                    piece.getStyle().setItalic(i % 2 == 1);
                    line.appendSibling(piece);
                }
                line = net.minecraftforge.event.ForgeEventFactory.onClientChat(net.minecraft.util.text.ChatType.CHAT, line);
                if (line != null) {
                    mc.ingameGUI.getChatGUI().printChatMessage(line);
                }
                break;
            }
            case "linkitem":
                report.add("item link sent: " + better112.chat.ItemLink.testSend(Integer.parseInt(arg)));
                waitTicks = 20;
                break;
            case "suggest": {
                String typed = p.length > 2 ? arg + ":" + p[2] : arg;
                report.add("suggest '" + typed + "': " + better112.chat.EmoteSuggest.suggestions(typed, typed.length()) + " -> '"
                        + better112.chat.EmoteSuggest.complete(typed, typed.length()) + "'");
                break;
            }
            case "chatbox":
                mc.displayGuiScreen(new net.minecraft.client.gui.GuiChat(p.length > 2 ? arg + ":" + p[2] : arg));
                waitTicks = 5;
                break;
            case "picker":
                if (!(mc.currentScreen instanceof SodiumScreen) || !((SodiumScreen) mc.currentScreen).testOpenPicker(arg)) {
                    report.add("could not open the picker of " + arg);
                }
                break;
            case "say": {
                // say:TEXT - typed into the chat box and sent, so outgoing-message handlers see it
                net.minecraft.client.gui.GuiChat box = new net.minecraft.client.gui.GuiChat();
                net.minecraft.client.gui.ScaledResolution res = new net.minecraft.client.gui.ScaledResolution(mc);
                box.setWorldAndResolution(mc, res.getScaledWidth(), res.getScaledHeight());
                box.sendChatMessage(p.length > 2 ? arg + ":" + p[2] : arg);
                waitTicks = 20;
                break;
            }
            case "chatlog":
                for (String seen : chatSeen) {
                    report.add("chat seen: " + seen);
                }
                chatSeen.clear();
                break;
            case "colorcheck": {
                if (mc.world != null && mc.getRenderViewEntity() != null) {
                    net.minecraft.util.math.Vec3d sky = mc.world.getSkyColor(mc.getRenderViewEntity(), 1.0F);
                    int water = mc.getBlockColors().colorMultiplier(net.minecraft.init.Blocks.WATER.getDefaultState(), mc.world,
                            new net.minecraft.util.math.BlockPos(mc.player), 0);
                    String sprite = better112.cosmetic.WorldColors.waterRenderers + " water renderer(s) set";
                    sprite += ", drawn by " + mc.getBlockRendererDispatcher().getClass().getName() + ", tint asked "
                            + better112.cosmetic.WorldColors.tintCalls + " times";
                    report.add(String.format("colors %s: sky drawn #%02X%02X%02X (setting %s), water tint #%06X (setting %s), water sprite %s",
                            arg, (int) (sky.x * 255), (int) (sky.y * 255), (int) (sky.z * 255),
                            better112.cosmetic.WorldColors.sky() < 0 ? "default" : String.format("#%06X", better112.cosmetic.WorldColors.sky()),
                            water & 0xFFFFFF,
                            better112.cosmetic.WorldColors.water() < 0 ? "default" : String.format("#%06X", better112.cosmetic.WorldColors.water()),
                            sprite));
                }
                break;
            }
            case "content": {
                // content - what was imported from modern mods and whether every block has its model
                report.add("content: " + better112.content.ModernContent.status());
                List<String> broken = better112.content.ContentClient.missingModels();
                report.add("content models missing: " + broken.size() + (broken.isEmpty() ? "" : " " + broken));
                break;
            }
            case "refresh":
                // refresh - reload every resource pack, as F3+T does
                mc.refreshResources();
                waitTicks = 100;
                break;
            case "guiscale": {
                // guiscale:HALVES - the GUI scale in half steps (4 is 2x, 5 is 2.5x; 0 is automatic)
                int halves = Integer.parseInt(arg);
                mc.gameSettings.guiScale = (halves + 1) / 2;
                BetterConfig.guiScaleHalves = (halves & 1) == 1 ? halves : 0;
                net.minecraft.client.gui.GuiScreen open = mc.currentScreen;
                mc.resize(mc.displayWidth, mc.displayHeight);
                net.minecraft.client.gui.ScaledResolution now = new net.minecraft.client.gui.ScaledResolution(mc);
                report.add("gui scale " + (halves / 2.0D) + ": the game reports factor " + now.getScaleFactor() + ", screen " + now.getScaledWidth() + "x"
                        + now.getScaledHeight() + " for a window of " + mc.displayWidth + "x" + mc.displayHeight + (open == null ? "" : ", with " + open.getClass().getSimpleName() + " open"));
                waitTicks = 10;
                break;
            }
            case "texscan": {
                // texscan:MOD,MOD - everything that shows the placeholder texture, counted per mod and named for the mods listed
                java.util.Set<String> detailed = new java.util.LinkedHashSet<String>(java.util.Arrays.asList(arg.split(",")));
                report.addAll(better112.content.ContentClient.placeholders(detailed));
                break;
            }
            case "showcase": {
                // showcase:WIDTH[:SOURCE] - every imported block of a source ("bop", "mc") in rows on the ground in front of the player
                final int rowLength = Integer.parseInt(arg);
                final String showSource = p.length > 2 ? p[2] : "bop";
                final net.minecraft.server.integrated.IntegratedServer server = mc.getIntegratedServer();
                if (server != null && mc.player != null) {
                    final net.minecraft.util.math.BlockPos origin = new net.minecraft.util.math.BlockPos(mc.player).add(-rowLength, 0, 4);
                    server.addScheduledTask(new Runnable() {
                        @Override
                        public void run() {
                            net.minecraft.world.World world = server.getWorld(0);
                            int i = 0;
                            for (net.minecraft.block.Block block : better112.content.ModernContent.showcaseBlocks(showSource)) {
                                net.minecraft.util.math.BlockPos at = origin.add((i % rowLength) * 2, 0, (i / rowLength) * 2);
                                for (int dy = 0; dy < 4; dy++) {
                                    world.setBlockState(at.up(dy), net.minecraft.init.Blocks.AIR.getDefaultState(), 2);
                                }
                                world.setBlockState(at.down(), net.minecraft.init.Blocks.GRASS.getDefaultState(), 2);
                                better112.content.ModernContent.placeForShow(world, at, block);
                                i++;
                            }
                        }
                    });
                }
                waitTicks = 40;
                break;
            }
            case "gobiome": {
                // gobiome:NAME:HEIGHT - find the nearest place of a biome (registry name contains NAME) and hover above it
                final String wanted = arg;
                final int above = p.length > 2 ? Integer.parseInt(p[2]) : 30;
                final net.minecraft.server.integrated.IntegratedServer server = mc.getIntegratedServer();
                if (server != null && mc.player != null) {
                    // On the server's own thread: its biome cache is not safe to read from here.
                    server.addScheduledTask(new Runnable() {
                        @Override
                        public void run() {
                            net.minecraft.world.World world = server.getWorld(0);
                            net.minecraft.world.biome.BiomeProvider provider = world.getBiomeProvider();
                            net.minecraft.util.math.BlockPos found = null;
                            String foundName = null;
                            search:
                            for (int ring = 0; ring <= 90; ring++) {
                                for (int dx = -ring; dx <= ring; dx++) {
                                    for (int dz = -ring; dz <= ring; dz++) {
                                        if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                                            continue;
                                        }
                                        net.minecraft.util.math.BlockPos at = new net.minecraft.util.math.BlockPos(dx * 96, 0, dz * 96);
                                        String name = String.valueOf(provider.getBiome(at).getRegistryName());
                                        // The middle of a patch, not its edge: the same biome 48 blocks on in each direction.
                                        if (name.contains(wanted) && name.equals(String.valueOf(provider.getBiome(at.add(48, 0, 0)).getRegistryName()))
                                                && name.equals(String.valueOf(provider.getBiome(at.add(0, 0, 48)).getRegistryName()))
                                                && name.equals(String.valueOf(provider.getBiome(at.add(-48, 0, -48)).getRegistryName()))) {
                                            found = at;
                                            foundName = name;
                                            break search;
                                        }
                                    }
                                }
                            }
                            // A biome that only comes in narrow strips (mountain peaks): any spot of it will do.
                            strips:
                            for (int ring = 0; ring <= 180 && found == null; ring++) {
                                for (int dx = -ring; dx <= ring; dx++) {
                                    for (int dz = -ring; dz <= ring; dz++) {
                                        if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                                            continue;
                                        }
                                        net.minecraft.util.math.BlockPos at = new net.minecraft.util.math.BlockPos(dx * 48, 0, dz * 48);
                                        String name = String.valueOf(provider.getBiome(at).getRegistryName());
                                        if (name.contains(wanted) && name.equals(String.valueOf(provider.getBiome(at.add(8, 0, 8)).getRegistryName()))) {
                                            found = at;
                                            foundName = name + " (a strip)";
                                            break strips;
                                        }
                                    }
                                }
                            }
                            if (found == null) {
                                report.add("gobiome " + wanted + ": none within 8,600 blocks");
                                return;
                            }
                            report.add("gobiome " + wanted + ": " + foundName + " at " + found.getX() + ", " + found.getZ());
                            net.minecraft.entity.player.EntityPlayerMP sp = server.getPlayerList().getPlayerByUUID(mc.player.getUniqueID());
                            world.getChunk(found); // generate it, to know how high the ground is
                            int ground = world.getTopSolidOrLiquidBlock(found).getY();
                            sp.connection.setPlayerLocation(found.getX() + 0.5D, ground + above, found.getZ() + 0.5D, sp.rotationYaw, sp.rotationPitch);
                        }
                    });
                }
                waitTicks = 40;
                break;
            }
            case "gocave": {
                // gocave:lush|dripstone - go above the nearest place where the climate puts that cave biome underground
                final String which = arg;
                final net.minecraft.server.integrated.IntegratedServer server = mc.getIntegratedServer();
                if (server != null && mc.player != null) {
                    server.addScheduledTask(new Runnable() {
                        @Override
                        public void run() {
                            net.minecraft.world.World world = server.getWorld(0);
                            net.minecraft.util.math.BlockPos found = better112.world.gen.ImportedBiomes.findCave(world, which);
                            if (found == null) {
                                report.add("gocave " + which + ": none within 7,600 blocks");
                                return;
                            }
                            report.add("gocave " + which + ": at " + found.getX() + ", " + found.getZ());
                            net.minecraft.entity.player.EntityPlayerMP sp = server.getPlayerList().getPlayerByUUID(mc.player.getUniqueID());
                            world.getChunk(found);
                            sp.connection.setPlayerLocation(found.getX() + 0.5D, world.getTopSolidOrLiquidBlock(found).getY() + 10, found.getZ() + 0.5D,
                                    sp.rotationYaw, sp.rotationPitch);
                        }
                    });
                }
                waitTicks = 40;
                break;
            }
            case "goblock": {
                // goblock:NAME:MAXY - go to the roomiest spot next to a block (registry name ends with NAME) at or below MAXY, in the chunks loaded around
                final String wanted = arg;
                final int maxY = p.length > 2 ? Integer.parseInt(p[2]) : 60;
                final net.minecraft.server.integrated.IntegratedServer server = mc.getIntegratedServer();
                if (server != null && mc.player != null) {
                    final net.minecraft.util.math.BlockPos center = new net.minecraft.util.math.BlockPos(mc.player);
                    server.addScheduledTask(new Runnable() {
                        @Override
                        public void run() {
                            net.minecraft.world.World world = server.getWorld(0);
                            net.minecraft.util.math.BlockPos best = null;
                            int bestRoom = -1, count = 0;
                            net.minecraft.util.math.BlockPos.MutableBlockPos at = new net.minecraft.util.math.BlockPos.MutableBlockPos();
                            for (int x = center.getX() - 96; x <= center.getX() + 96; x++) {
                                for (int z = center.getZ() - 96; z <= center.getZ() + 96; z++) {
                                    if (!world.isBlockLoaded(at.setPos(x, 0, z))) {
                                        continue;
                                    }
                                    for (int y = 4; y <= maxY; y++) {
                                        at.setPos(x, y, z);
                                        if (!String.valueOf(world.getBlockState(at).getBlock().getRegistryName()).endsWith(wanted)) {
                                            continue;
                                        }
                                        count++;
                                        if (count % 7 != 0 && best != null) {
                                            continue; // scoring every find would take long in a big cave
                                        }
                                        int room = 0;
                                        for (int dx = -3; dx <= 3; dx++) {
                                            for (int dy = -2; dy <= 2; dy++) {
                                                for (int dz = -3; dz <= 3; dz++) {
                                                    if (world.isAirBlock(at.add(dx, dy, dz))) {
                                                        room++;
                                                    }
                                                }
                                            }
                                        }
                                        if (room > bestRoom) {
                                            bestRoom = room;
                                            best = at.toImmutable();
                                        }
                                    }
                                }
                            }
                            if (best == null) {
                                report.add("goblock " + wanted + ": none at or below y " + maxY + " within 96 blocks");
                                return;
                            }
                            // Stand in the open air nearest the find.
                            net.minecraft.util.math.BlockPos stand = best;
                            search:
                            for (int r = 1; r <= 4; r++) {
                                for (int dx = -r; dx <= r; dx++) {
                                    for (int dy = -r; dy <= r; dy++) {
                                        for (int dz = -r; dz <= r; dz++) {
                                            net.minecraft.util.math.BlockPos spot = best.add(dx, dy, dz);
                                            if (world.isAirBlock(spot) && world.isAirBlock(spot.up())) {
                                                stand = spot;
                                                break search;
                                            }
                                        }
                                    }
                                }
                            }
                            report.add("goblock " + wanted + ": " + count + " within 96 blocks; standing at " + stand + " with " + bestRoom + " of 245 blocks around open");
                            net.minecraft.entity.player.EntityPlayerMP sp = server.getPlayerList().getPlayerByUUID(mc.player.getUniqueID());
                            sp.connection.setPlayerLocation(stand.getX() + 0.5D, stand.getY(), stand.getZ() + 0.5D, sp.rotationYaw, sp.rotationPitch);
                        }
                    });
                }
                waitTicks = 60;
                break;
            }
            case "craft": {
                // craft:ITEM - what one of this item alone in a crafting grid makes
                net.minecraft.item.Item input = net.minecraft.item.Item.REGISTRY.getObject(new net.minecraft.util.ResourceLocation(p.length > 2 ? arg + ":" + p[2] : arg));
                if (input == null || mc.world == null) {
                    report.add("craft: no such item " + arg);
                } else {
                    net.minecraft.inventory.InventoryCrafting grid = new net.minecraft.inventory.InventoryCrafting(new net.minecraft.inventory.Container() {
                        @Override
                        public boolean canInteractWith(net.minecraft.entity.player.EntityPlayer player) {
                            return false;
                        }
                    }, 3, 3);
                    grid.setInventorySlotContents(0, new ItemStack(input));
                    ItemStack made = net.minecraft.item.crafting.CraftingManager.findMatchingResult(grid, mc.world);
                    report.add("craft " + input.getRegistryName() + " -> " + made + ", smelts to "
                            + net.minecraft.item.crafting.FurnaceRecipes.instance().getSmeltingResult(new ItemStack(input)));
                }
                break;
            }
            case "growsapling": {
                // growsapling:NAME:DX - plant the sapling DX blocks east of the player and bone-meal it until it is a tree
                final String sapling = arg;
                final int dx = p.length > 2 ? Integer.parseInt(p[2]) : 0;
                final net.minecraft.server.integrated.IntegratedServer server = mc.getIntegratedServer();
                if (server != null && mc.player != null) {
                    final net.minecraft.util.math.BlockPos at = new net.minecraft.util.math.BlockPos(mc.player).add(dx, 0, 6);
                    server.addScheduledTask(new Runnable() {
                        @Override
                        public void run() {
                            net.minecraft.world.World world = server.getWorld(0);
                            net.minecraft.block.Block block = net.minecraft.block.Block.REGISTRY.getObject(new net.minecraft.util.ResourceLocation("better112",
                                    sapling.startsWith("mc_") || sapling.startsWith("bop_") ? sapling : "bop_" + sapling));
                            world.setBlockState(at, block.getDefaultState(), 3);
                            int uses = 0;
                            while (uses < 60 && world.getBlockState(at).getBlock() == block) {
                                net.minecraft.item.ItemDye.applyBonemeal(new ItemStack(net.minecraft.init.Items.DYE, 1, 15), world, at);
                                uses++;
                            }
                            Better112.LOG.info("Self-test sapling {}: after {} bone meal the spot holds {}", sapling, uses, world.getBlockState(at).getBlock().getRegistryName());
                            saplingResults.add(sapling + " -> " + world.getBlockState(at).getBlock().getRegistryName() + " after " + uses + " bone meal");
                        }
                    });
                }
                waitTicks = 20;
                break;
            }
            case "saplings":
                report.add("saplings: " + saplingResults);
                break;
            case "features":
                report.add("modern biomes: " + better112.world.gen.ImportedBiomes.status());
                report.add("features not understood: " + better112.world.gen.ImportedBiomes.unknownFeatures());
                report.add(String.format("imported biome decoration: %d chunks, %.2f ms each", better112.world.gen.ImportedBiomes.decoratedChunks,
                        better112.world.gen.ImportedBiomes.decoratedChunks == 0 ? 0.0D
                                : better112.world.gen.ImportedBiomes.decorateNanos / 1.0E6D / better112.world.gen.ImportedBiomes.decoratedChunks));
                report.add("cave decoration: " + better112.world.gen.ImportedBiomes.caveReport());
                break;
            case "where":
                // where:LABEL - position, ground height and biome, for worldgen checks
                if (mc.player != null && mc.getIntegratedServer() != null) {
                    net.minecraft.util.math.BlockPos at = new net.minecraft.util.math.BlockPos(mc.player);
                    net.minecraft.world.World sw = mc.getIntegratedServer().getWorld(0);
                    net.minecraft.world.biome.BiomeProvider bp = sw.getBiomeProvider();
                    int shaped = better112.world.gen.ModernChunkGenerator.shapedChunks;
                    report.add("where " + arg + ": " + at + ", ground " + sw.getHeight(at.getX(), at.getZ()) + ", biome "
                            + sw.getBiome(at).getRegistryName() + ", generator " + sw.getWorldType().getName() + ", provider "
                            + bp.getClass().getSimpleName() + (bp instanceof better112.world.gen.ModernBiomeProvider
                                    ? ", modern biome " + ((better112.world.gen.ModernBiomeProvider) bp).nameAt(at.getX(), at.getZ()) : "")
                            + String.format(", %d chunks shaped, %.1f ms each", shaped,
                                    shaped == 0 ? 0.0D : better112.world.gen.ModernChunkGenerator.shapeNanos / 1.0E6D / shaped));
                }
                break;
            case "lightcheck":
                if (mc.player != null) {
                    net.minecraft.util.math.BlockPos pos = new net.minecraft.util.math.BlockPos(mc.player).up();
                    int packed = mc.world.getCombinedLight(pos, 0);
                    int raw = mc.world.getLightFor(net.minecraft.world.EnumSkyBlock.BLOCK, pos);
                    report.add("light " + (p.length > 2 ? p[2] : arg) + ": sources " + better112.perf.DynamicLights.sourceCount()
                            + ", block light in world data " + raw + ", block light the renderer gets " + ((packed & 0xFFFF) / 16.0F));
                }
                break;
            case "freelook":
                // freelook:on | freelook:off | freelook:turn:YAW,PITCH - hold the freelook key, swing the camera
                if (arg.equals("on") || arg.equals("off")) {
                    better112.qol.Freelook.testHeld = arg.equals("on");
                    waitTicks = 5;
                } else {
                    String[] turn = p[2].split(",");
                    report.add(better112.qol.Freelook.testTurn(Float.parseFloat(turn[0]), Float.parseFloat(turn[1])));
                }
                break;
            case "zoomtest":
                // zoomtest:hold | zoomtest:release | zoomtest:scroll:STEPS | zoomtest:state
                if (arg.equals("hold") || arg.equals("release")) {
                    better112.ClientTweaks.testZoomHeld = arg.equals("hold");
                    waitTicks = 10;
                } else if (arg.equals("scroll")) {
                    better112.ClientTweaks.zoomBy(Integer.parseInt(p[2]));
                    waitTicks = 10;
                } else {
                    report.add("zoom: " + better112.ClientTweaks.testZoom());
                }
                break;
            case "newitems":
                report.add("new item marks: " + better112.qol.NewItems.testState());
                break;
            case "worlds":
                // worlds:TEXT - the Select World screen, searched
                mc.displayGuiScreen(new net.minecraft.client.gui.GuiWorldSelection(null));
                report.add("world search: " + (mc.currentScreen instanceof better112.qol.WorldSearch.Screen
                        ? ((better112.qol.WorldSearch.Screen) mc.currentScreen).test(p.length > 1 ? arg : "")
                        : "screen is " + (mc.currentScreen == null ? "none" : mc.currentScreen.getClass().getName())));
                waitTicks = 10;
                break;
            case "glint":
                // glint:SPEED:STRENGTH - the Accessibility glint sliders
                BetterConfig.glintSpeed = Integer.parseInt(arg);
                BetterConfig.glintStrength = Integer.parseInt(p[2]);
                waitTicks = 5;
                break;
            case "chatcopy":
                report.add("chat copy: " + better112.chat.ChatExtras.testState());
                break;
            case "chatdraft": {
                // chatdraft:TEXT - type into the chat, close it unsent, open it again with the chat key
                net.minecraft.client.gui.GuiChat box = new net.minecraft.client.gui.GuiChat();
                mc.displayGuiScreen(box);
                net.minecraft.client.gui.GuiTextField field = net.minecraftforge.fml.relauncher.ReflectionHelper.getPrivateValue(
                        net.minecraft.client.gui.GuiChat.class, box, "inputField", "field_146415_a");
                field.setText(p.length > 2 ? arg + ":" + p[2] : arg);
                mc.displayGuiScreen(null);
                net.minecraft.client.gui.GuiChat again = new net.minecraft.client.gui.GuiChat();
                mc.displayGuiScreen(again);
                net.minecraft.client.gui.GuiTextField field2 = net.minecraftforge.fml.relauncher.ReflectionHelper.getPrivateValue(
                        net.minecraft.client.gui.GuiChat.class, again, "inputField", "field_146415_a");
                report.add("chat draft: reopened with '" + field2.getText() + "'");
                waitTicks = 5;
                break;
            }
            case "join":
                // join:HOST:PORT - connect to a server, as the multiplayer screen would
                mc.displayGuiScreen(new net.minecraft.client.multiplayer.GuiConnecting(new net.minecraft.client.gui.GuiMainMenu(), mc,
                        new net.minecraft.client.multiplayer.ServerData("Test", p.length > 2 ? arg + ":" + p[2] : arg, false)));
                waitTicks = 100;
                break;
            case "online": {
                // online - whether the game is in a server's world now, or why it is not
                String state;
                if (mc.player != null && mc.world != null && !mc.isSingleplayer()) {
                    state = "in the world of " + (mc.getCurrentServerData() == null ? "?" : mc.getCurrentServerData().serverIP)
                            + " as " + mc.player.getName() + " at " + new net.minecraft.util.math.BlockPos(mc.player);
                } else if (mc.currentScreen instanceof net.minecraft.client.gui.GuiDisconnected) {
                    net.minecraft.util.text.ITextComponent why = null;
                    for (Field field : net.minecraft.client.gui.GuiDisconnected.class.getDeclaredFields()) {
                        if (net.minecraft.util.text.ITextComponent.class.isAssignableFrom(field.getType())) {
                            field.setAccessible(true);
                            why = (net.minecraft.util.text.ITextComponent) field.get(mc.currentScreen);
                        }
                    }
                    state = "disconnected: " + (why == null ? "?" : why.getUnformattedText());
                } else {
                    state = "not in a world; screen " + (mc.currentScreen == null ? "none" : mc.currentScreen.getClass().getName());
                }
                report.add("online: " + state);
                break;
            }
            case "zoom":
                // zoom:ITEM | zoom:off - Item Zoom on, showing this item as if hovered
                if (arg.equals("off")) {
                    better112.qol.ItemZoom.testSet(false);
                    better112.qol.ItemZoom.testStack = net.minecraft.item.ItemStack.EMPTY;
                } else {
                    net.minecraft.item.Item zoomed = net.minecraft.item.Item.getByNameOrId(p.length > 2 ? arg + ":" + p[2] : arg);
                    better112.qol.ItemZoom.testStack = zoomed == null ? net.minecraft.item.ItemStack.EMPTY : new net.minecraft.item.ItemStack(zoomed);
                    better112.qol.ItemZoom.testSet(true);
                }
                waitTicks = 5;
                break;
            case "idle":
                // idle:MINUTES - as if nothing had been touched for this long; idle:state reports
                if (!arg.equals("state")) {
                    better112.qol.Idle.testOffset = Long.parseLong(arg) * 60000L;
                }
                report.add("idle: " + better112.qol.Idle.testState());
                break;
            case "ripples":
                report.add("rain ripple sprite: " + better112.cosmetic.RainRipples.testSprite());
                report.add("rain ripples: made " + better112.cosmetic.RainRipples.testSplash(Integer.parseInt(arg))
                        + ", spawned so far " + better112.cosmetic.RainRipples.spawned);
                break;
            case "ride": {
                // ride - the player mounts the nearest horse or pig (on the server's thread, as a click would)
                final net.minecraft.server.integrated.IntegratedServer server = mc.getIntegratedServer();
                if (server != null) {
                    server.addScheduledTask(new Runnable() {
                        @Override
                        public void run() {
                            net.minecraft.entity.player.EntityPlayerMP rider = server.getPlayerList().getPlayers().get(0);
                            net.minecraft.entity.Entity best = null;
                            for (net.minecraft.entity.Entity e : rider.world.getEntitiesWithinAABBExcludingEntity(rider, rider.getEntityBoundingBox().grow(8.0))) {
                                if ((e instanceof net.minecraft.entity.passive.AbstractHorse || e instanceof net.minecraft.entity.passive.EntityPig)
                                        && (best == null || rider.getDistance(e) < rider.getDistance(best))) {
                                    best = e;
                                }
                            }
                            if (best != null) {
                                rider.startRiding(best, true);
                            }
                        }
                    });
                }
                waitTicks = 20;
                break;
            }
            case "ownfx":
                report.add("own effect swirls hidden so far: " + better112.Hooks.ownEffectsHidden);
                break;
            case "mounthud":
                report.add("mount HUD: " + better112.qol.ViewTweaks.testMountHud());
                break;
            case "rarity": {
                // rarity:ITEM_ID - the frame colour an item gets
                net.minecraft.item.Item found = net.minecraft.item.Item.getByNameOrId(p.length > 2 ? arg + ":" + p[2] : arg);
                report.add("rarity border: " + (found == null ? "no such item" : better112.qol.RarityBorders.testColor(new net.minecraft.item.ItemStack(found))));
                break;
            }
            case "jeisearch":
                // jeisearch:TEXT - typed into JEI's search with its box focused; jeisearch:off lets go
                report.add("JEI search highlight: " + (net.minecraftforge.fml.common.Loader.isModLoaded("jei")
                        ? better112.jei.JeiBridge.testSearch(arg, !arg.equals("off")) : "no JEI"));
                waitTicks = 5;
                break;
            case "water":
                // water:TICKS - as if the eyes had been under water this long; water:state reports
                if (!arg.equals("state")) {
                    better112.qol.ViewTweaks.testWaterTicks(Integer.parseInt(arg));
                }
                report.add("water: " + better112.qol.ViewTweaks.testWater());
                break;
            case "fireflies":
                report.add("fireflies: released " + better112.cosmetic.Fireflies.testRelease(Integer.parseInt(arg))
                        + ", spawned so far " + better112.cosmetic.Fireflies.spawned);
                break;
            case "debugtargets":
                report.add("F3 extra lines: " + better112.qol.DebugTargets.testLines());
                break;
            case "titles":
                report.add("arrival titles: " + better112.qol.Titles.testState());
                break;
            case "titleshow":
                better112.qol.Titles.testShow(arg, p.length > 2 ? p[2] : null);
                waitTicks = 20;
                break;
            case "leaves":
                // leaves:RADIUS - drop a leaf from about a third of the leaf blocks around, for a screenshot
                report.add("falling leaves: dropped " + better112.cosmetic.FallingLeaves.testDrop(Integer.parseInt(arg))
                        + ", spawned so far " + better112.cosmetic.FallingLeaves.spawned);
                break;
            case "grass": {
                // grass:RADIUS - how many grass blocks around get grass down a side, and one of them
                int radius = Integer.parseInt(arg), withSides = 0, grass = 0;
                String example = "";
                net.minecraft.util.math.BlockPos at = new net.minecraft.util.math.BlockPos(mc.player);
                for (net.minecraft.util.math.BlockPos pos : net.minecraft.util.math.BlockPos.getAllInBox(at.add(-radius, -8, -radius), at.add(radius, 8, radius))) {
                    if (mc.world.getBlockState(pos).getBlock() == net.minecraft.init.Blocks.GRASS) {
                        grass++;
                        String line = better112.cosmetic.BetterGrass.testAt(mc.world, pos);
                        if (!line.endsWith("[]")) {
                            withSides++;
                            if (example.isEmpty()) {
                                example = line;
                            }
                        }
                    }
                }
                report.add("better grass: " + withSides + " of " + grass + " grass blocks get grass sides; e.g. " + example);
                break;
            }
            case "persp":
                mc.gameSettings.thirdPersonView = Integer.parseInt(arg);
                waitTicks = 3;
                break;
            case "viewstate":
                report.add("perspective now " + mc.gameSettings.thirdPersonView);
                break;
            case "leave": {
                // leave - out of the world as the pause menu's Disconnect button does it
                report.add("leaving with " + better112.perf.DynamicLights.sourceCount() + " dynamic light source(s)");
                boolean integrated = mc.isIntegratedServerRunning();
                mc.world.sendQuittingDisconnectingPacket();
                mc.loadWorld(null);
                mc.displayGuiScreen(integrated ? new net.minecraft.client.gui.GuiMainMenu()
                        : new net.minecraft.client.gui.GuiMultiplayer(new net.minecraft.client.gui.GuiMainMenu()));
                waitTicks = 20;
                break;
            }
            case "jfr":
                // jfr:start | jfr:start:objects | jfr:stop:NAME - a Flight Recorder recording around the steps between,
                // written to better112-autotest/NAME.jfr (through reflection: the API is in the runtime, not in the build's classpath)
                try {
                    Class<?> recordingClass = Class.forName("jdk.jfr.Recording");
                    if (arg.equals("start")) {
                        Class<?> configClass = Class.forName("jdk.jfr.Configuration");
                        Object config = configClass.getMethod("getConfiguration", String.class).invoke(null, "profile");
                        recording = recordingClass.getConstructor(configClass).newInstance(config);
                        if (p.length > 2 && p[2].equals("objects")) {
                            // A class histogram of the heap at each collection: what is taking the memory.
                            Object settings = recordingClass.getMethod("enable", String.class).invoke(recording, "jdk.ObjectCount");
                            settings.getClass().getMethod("withThreshold", java.time.Duration.class).invoke(settings, java.time.Duration.ZERO);
                        }
                        recordingClass.getMethod("start").invoke(recording);
                        report.add("flight recording started");
                    } else if (recording != null) {
                        recordingClass.getMethod("stop").invoke(recording);
                        File out = new File(mc.gameDir, "better112-autotest/" + (p.length > 2 ? p[2] : "recording") + ".jfr");
                        out.getParentFile().mkdirs();
                        recordingClass.getMethod("dump", java.nio.file.Path.class).invoke(recording, out.toPath());
                        recordingClass.getMethod("close").invoke(recording);
                        recording = null;
                        report.add("flight recording written to " + out.getName());
                    }
                } catch (Throwable t) {
                    report.add("flight recording failed: " + t);
                }
                break;
            case "mem": {
                // mem:LABEL - heap in use after two full collections
                Runtime runtime = Runtime.getRuntime();
                System.gc();
                System.gc();
                long used = runtime.totalMemory() - runtime.freeMemory();
                report.add(String.format("heap %s: %d MB used after collection, %d MB committed, %d MB max", arg, used >> 20,
                        runtime.totalMemory() >> 20, runtime.maxMemory() >> 20));
                break;
            }
            case "bewitchsync":
                // bewitchsync - a Bewitchment player sync arriving now, as the server sends them (with or without a player)
                try {
                    Class<?> message = Class.forName("com.bewitchment.api.message.SyncExtendedPlayer");
                    Class<?> handler = Class.forName("com.bewitchment.api.message.SyncExtendedPlayer$Handler");
                    Object sync = message.getConstructor(net.minecraft.nbt.NBTTagCompound.class).newInstance(new net.minecraft.nbt.NBTTagCompound());
                    java.lang.reflect.Constructor<net.minecraftforge.fml.common.network.simpleimpl.MessageContext> context =
                            net.minecraftforge.fml.common.network.simpleimpl.MessageContext.class.getDeclaredConstructor(
                                    net.minecraft.network.INetHandler.class, net.minecraftforge.fml.relauncher.Side.class);
                    context.setAccessible(true);
                    handler.getMethod("onMessage", message, net.minecraftforge.fml.common.network.simpleimpl.MessageContext.class)
                            .invoke(handler.newInstance(), sync, context.newInstance(null, net.minecraftforge.fml.relauncher.Side.CLIENT));
                    report.add("Bewitchment sync delivered, player " + (mc.player == null ? "gone" : "present"));
                } catch (Throwable t) {
                    report.add("Bewitchment sync: " + t);
                }
                break;
            case "pause":
                mc.displayGuiScreen(new net.minecraft.client.gui.GuiIngameMenu());
                waitTicks = 5;
                break;
            case "f3":
                mc.gameSettings.showDebugInfo = arg.equals("on");
                waitTicks = 3;
                break;
            case "dim": {
                // dim:ID - the player moves to that dimension, onto the ground (no portal is built)
                final int target = Integer.parseInt(arg);
                final net.minecraft.server.integrated.IntegratedServer server = mc.getIntegratedServer();
                if (server != null && mc.player != null) {
                    final java.util.UUID id = mc.player.getUniqueID();
                    server.addScheduledTask(new Runnable() {
                        @Override
                        public void run() {
                            net.minecraft.entity.player.EntityPlayerMP sp = server.getPlayerList().getPlayerByUUID(id);
                            sp.changeDimension(target, new net.minecraftforge.common.util.ITeleporter() {
                                @Override
                                public void placeEntity(net.minecraft.world.World world, net.minecraft.entity.Entity entity, float yaw) {
                                    net.minecraft.util.math.BlockPos at = new net.minecraft.util.math.BlockPos(entity.posX, 0, entity.posZ);
                                    int y = target == -1 ? 64 : world.getTopSolidOrLiquidBlock(at).getY() + 1;
                                    if (target == -1) {
                                        // An air pocket on a floor, under the Nether's roof.
                                        for (net.minecraft.util.math.BlockPos p : net.minecraft.util.math.BlockPos.getAllInBox(at.add(-1, y, -1), at.add(1, y + 2, 1))) {
                                            world.setBlockToAir(p);
                                        }
                                        world.setBlockState(at.add(0, y - 1, 0), net.minecraft.init.Blocks.NETHERRACK.getDefaultState());
                                    }
                                    entity.setLocationAndAngles(at.getX() + 0.5D, y, at.getZ() + 0.5D, yaw, 0.0F);
                                    entity.motionX = entity.motionY = entity.motionZ = 0.0D;
                                }
                            });
                        }
                    });
                }
                waitTicks = 100;
                break;
            }
            case "screenname":
                report.add("screen now " + (mc.currentScreen == null ? "none" : mc.currentScreen.getClass().getSimpleName())
                        + ", world " + (mc.world == null ? "none" : "loaded, dimension " + mc.world.provider.getDimension())
                        + ", dynamic light sources " + better112.perf.DynamicLights.sourceCount());
                break;
            case "chatclear":
                mc.ingameGUI.getChatGUI().clearChatMessages(false);
                break;
            case "hud":
                // hud:off | hud:bare | hud:on - off is F1, for pictures of the world alone. The game draws
                // the overlay under any open screen, the idle screen too, so it is also cancelled here.
                // bare keeps only what is drawn after the whole overlay (arrival titles), without the hand.
                hudMode = arg.equals("off") ? 1 : arg.equals("bare") ? 2 : 0;
                mc.gameSettings.hideGUI = hudMode == 1;
                waitTicks = 3;
                break;
            case "fly":
                // fly:on | fly:off - a creative player stays in the air after a teleport
                if (mc.player != null) {
                    mc.player.capabilities.isFlying = arg.equals("on");
                    mc.player.sendPlayerAbilities();
                }
                waitTicks = 3;
                break;
            case "volume":
                better112.qol.ViewTweaks.testBackground = arg.equals("bg");
                waitTicks = 3;
                report.add("volume (" + arg + "): " + better112.qol.ViewTweaks.testVolume());
                break;
            case "worldkey":
                report.add("world list key " + arg + ": " + (mc.currentScreen instanceof better112.qol.WorldSearch.Screen
                        ? ((better112.qol.WorldSearch.Screen) mc.currentScreen).testKey(Integer.parseInt(arg)) : "no world list open"));
                break;
            case "itemgrid": {
                // itemgrid:DOMAIN,DOMAIN[:PAGE] - every item of those mods drawn in a grid, for a screenshot of their textures
                ItemGrid grid = new ItemGrid(arg, p.length > 2 ? Integer.parseInt(p[2]) : 0);
                mc.displayGuiScreen(grid);
                report.add("item grid " + arg + ": " + grid.count() + " items");
                waitTicks = 20;
                break;
            }
            case "texcheck":
                report.add("texture " + ModernTextures.selfCheck());
                break;
            case "note":
                report.add(p.length > 2 ? arg + ":" + p[2] : arg);
                break;
            case "marks":
                // marks:mark:ITEM | marks:open | marks:preview | marks:state - recipe bookmarks in JEI
                report.add("recipe bookmarks " + arg + ": " + (net.minecraftforge.fml.common.Loader.isModLoaded("jei")
                        ? better112.jei.JeiBridge.testMarks(arg, p.length > 2 ? p[2] : "") : "no JEI"));
                waitTicks = 10;
                break;
            case "cmdsuggest":
                report.add("command suggestions: " + better112.chat.CommandSuggest.testState());
                break;
            case "modeswitch":
                // modeswitch:TAPS opens the F3+F4 picker and taps F4; modeswitch:release lets go of F3
                if (arg.equals("release")) {
                    better112.qol.GameModeSwitcher.testRelease();
                } else {
                    report.add("game mode picker: " + better112.qol.GameModeSwitcher.testOpen(Integer.parseInt(arg)));
                }
                waitTicks = 10;
                break;
            case "copykeys": {
                String[] target = better112.qol.DebugKeys.targetCommand(mc);
                report.add("F3+C would copy: " + better112.qol.DebugKeys.locationCommand(mc));
                report.add("F3+I would copy: " + (target == null ? "nothing in reach" : target[1]));
                break;
            }
            case "saved":
                better112.qol.Conveniences.testSaved();
                break;
            case "offhand": {
                // offhand:INDEX - the off-hand key over that slot of the player's inventory, in the open container
                if (mc.currentScreen instanceof net.minecraft.client.gui.inventory.GuiContainer) {
                    net.minecraft.client.gui.inventory.GuiContainer open = (net.minecraft.client.gui.inventory.GuiContainer) mc.currentScreen;
                    int index = Integer.parseInt(arg);
                    net.minecraft.inventory.Slot slot = open.inventorySlots.getSlotFromInventory(mc.player.inventory, index);
                    String before = mc.player.inventory.getStackInSlot(index) + " | off " + mc.player.getHeldItemOffhand()
                            + " | held " + mc.player.getHeldItemMainhand();
                    boolean done = better112.qol.Conveniences.swap(mc, open, slot);
                    report.add("off-hand swap of slot " + index + " in " + open.getClass().getSimpleName() + ": " + done + "; " + before
                            + " -> " + mc.player.inventory.getStackInSlot(index) + " | off " + mc.player.getHeldItemOffhand()
                            + " | held " + mc.player.getHeldItemMainhand());
                } else {
                    report.add("off-hand swap: no container open");
                }
                waitTicks = 10;
                break;
            }
            case "hands":
                report.add("hands: held " + mc.player.getHeldItemMainhand() + " | off " + mc.player.getHeldItemOffhand()
                        + " | slot " + arg + " " + mc.player.inventory.getStackInSlot(Integer.parseInt(arg)));
                break;
            case "gamerule":
                // gamerule:NAME:VALUE - as if picked on the Game Rules screen before creating the world
                report.add("game rules waiting: " + better112.qol.GameRulesScreen.testChoose(arg, p[2]));
                break;
            case "rulesscreen":
                mc.displayGuiScreen(new better112.qol.GameRulesScreen(mc.currentScreen));
                waitTicks = 5;
                break;
            case "createworld":
                mc.displayGuiScreen(new net.minecraft.client.gui.GuiCreateWorld(mc.currentScreen));
                waitTicks = 5;
                break;
            case "click": {
                // click:ID - a real mouse click on the button with that id on the open screen
                try {
                    java.util.List<net.minecraft.client.gui.GuiButton> buttons = net.minecraftforge.fml.relauncher.ReflectionHelper
                            .getPrivateValue(net.minecraft.client.gui.GuiScreen.class, mc.currentScreen, "buttonList", "field_146292_n");
                    boolean hit = false;
                    for (net.minecraft.client.gui.GuiButton button : new java.util.ArrayList<>(buttons)) {
                        if (button.id == Integer.decode(arg) && button.visible) {
                            java.lang.reflect.Method click = net.minecraftforge.fml.relauncher.ReflectionHelper.findMethod(
                                    net.minecraft.client.gui.GuiScreen.class, "mouseClicked", "func_73864_a", int.class, int.class, int.class);
                            click.invoke(mc.currentScreen, button.x + button.width / 2, button.y + button.height / 2, 0);
                            hit = true;
                            break;
                        }
                    }
                    report.add("click " + arg + ": " + (hit ? "now on " + (mc.currentScreen == null ? "no screen" : mc.currentScreen.getClass().getSimpleName()) : "no such visible button"));
                } catch (Throwable t) {
                    report.add("click " + arg + " failed: " + t);
                }
                waitTicks = 5;
                break;
            }
            case "controls":
                mc.displayGuiScreen(new net.minecraft.client.gui.GuiControls(null, mc.gameSettings));
                report.add("controls screen: " + (mc.currentScreen == null ? "none" : mc.currentScreen.getClass().getName()));
                report.add(better112.qol.ControlsSearch.testConflicts());
                waitTicks = 5;
                break;
            case "ctlsearch":
                // ctlsearch:FILTER:TEXT - filter 0 all, 1 conflicts, 2 unbound, 3 changed
                report.add("controls search: " + (mc.currentScreen instanceof better112.qol.ControlsSearch.Screen
                        ? ((better112.qol.ControlsSearch.Screen) mc.currentScreen).test(p.length > 2 ? p[2] : "", Integer.parseInt(arg))
                        : "not the searchable screen"));
                waitTicks = 3;
                break;
            case "blur":
                report.add("menu blur: " + better112.qol.MenuBlur.testState());
                break;
            case "compare":
                // compare:SLOT:X,Y - hover that inventory slot's item at X,Y on the open screen
                String[] at = p.length > 2 ? p[2].split(",") : new String[] {"200", "120"};
                report.add("compare: " + better112.qol.EquipmentCompare.testHover(Integer.parseInt(arg), Integer.parseInt(at[0]), Integer.parseInt(at[1])));
                waitTicks = 2;
                break;
            case "compared":
                report.add("compare drawn: " + better112.qol.EquipmentCompare.testResult);
                break;
            case "warned":
                report.add("durability warning: '" + better112.qol.GearWatch.lastWarning + "'; main hand slot " + mc.player.inventory.currentItem
                        + " " + mc.player.getHeldItemMainhand() + " damage " + mc.player.getHeldItemMainhand().getItemDamage() + "/"
                        + mc.player.getHeldItemMainhand().getMaxDamage() + " damageable " + mc.player.getHeldItemMainhand().isItemStackDamageable());
                break;
            case "respawn":
                if (mc.player != null) {
                    mc.player.respawnPlayer();
                    mc.displayGuiScreen(null);
                }
                waitTicks = 20;
                break;
            case "quit":
                finish();
                break;
            default:
                report.add("unknown step: " + step);
                break;
        }
    }

    /** Every chat line as the player ends up seeing it, after all rewriting. */
    @SubscribeEvent(priority = net.minecraftforge.fml.common.eventhandler.EventPriority.LOWEST)
    public void onChatSeen(net.minecraftforge.client.event.ClientChatReceivedEvent event) {
        if (event.getMessage() == null) {
            return;
        }
        StringBuilder text = new StringBuilder();
        for (char ch : event.getMessage().getUnformattedText().toCharArray()) {
            text.append(ch >= '\uE000' && ch <= '\uF8FF' ? "<" + better112.chat.Emotes.nameOf(ch) + ">" : String.valueOf(ch));
        }
        for (net.minecraft.util.text.ITextComponent part : event.getMessage()) {
            net.minecraft.util.text.event.HoverEvent hover = part.getStyle().getHoverEvent();
            if (hover != null && hover.getAction() == net.minecraft.util.text.event.HoverEvent.Action.SHOW_ITEM) {
                String data = hover.getValue().getUnformattedText();
                text.append("  {hover shows item: ").append(data.length() > 110 ? data.substring(0, 110) + "..." : data).append('}');
                break;
            }
        }
        int pieces = 0;
        for (net.minecraft.util.text.ITextComponent part : event.getMessage()) {
            pieces++;
        }
        chatSeen.add(text + "  (" + pieces + " pieces)");
    }

    @SubscribeEvent
    public void onOverlay(net.minecraftforge.client.event.RenderGameOverlayEvent.Pre event) {
        boolean all = event.getType() == net.minecraftforge.client.event.RenderGameOverlayEvent.ElementType.ALL;
        if (hudMode == 1 && all || hudMode == 2 && !all) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onHand(net.minecraftforge.client.event.RenderHandEvent event) {
        if (hudMode != 0) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onRender(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            // A screenshot is of the last finished frame, taken before the next one is begun.
            if (pendingShot != null && --shotDelayFrames <= 0) {
                String name = pendingShot + ".png";
                pendingShot = null;
                GuiScreen screen = mc.currentScreen;
                ScreenShotHelper.saveScreenshot(mc.gameDir, name, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
                report.add("shot " + name + " screen=" + (screen == null ? "none" : screen.getClass().getSimpleName()));
            }
            return;
        }
        if (fpsLabel != null) {
            long now = System.nanoTime();
            fpsFrames.add(now - fpsLastFrame);
            fpsLastFrame = now;
            if (now >= fpsEndNanos) {
                long[] sorted = new long[fpsFrames.size()];
                for (int i = 0; i < sorted.length; i++) {
                    sorted[i] = fpsFrames.get(i);
                }
                Arrays.sort(sorted);
                double seconds = (now - fpsStartNanos) / 1.0E9D;
                long low = sorted[Math.max(0, (int) (sorted.length * 0.99D) - 1)];
                double moved = fpsStartPos == null || mc.player == null ? 0.0D : mc.player.getPositionVector().distanceTo(fpsStartPos);
                report.add(String.format("fps %s: avg %.1f, 1%% low %.1f, worst frame %.0f ms, frames %d, moved %.0f blocks, light rebuilds %d, hidden entities %d, hidden block entities %d",
                        fpsLabel, sorted.length / seconds, 1.0E9D / low, sorted[sorted.length - 1] / 1.0E6D, sorted.length, moved,
                        better112.perf.DynamicLights.sectionsMarked - fpsStartMarked, Occlusion.hiddenEntities, Occlusion.hiddenTiles));
                fpsLabel = null;
            }
            return;
        }
    }

    private void finish() {
        report.add("final patches applied: " + new TreeSet<String>(PatchState.appliedIds()));
        report.add("final patches failed: " + new TreeSet<String>(PatchState.failedIds()));
        report.add("tracking sprites: " + Hooks.isTrackingSprites() + ", animations handled by: " + Hooks.animationsHandledBy);
        try {
            File dir = new File(mc.gameDir, "better112-autotest");
            dir.mkdirs();
            Files.write(new File(dir, "report.txt").toPath(), report, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Better112.LOG.error("Could not write autotest report", e);
        }
        MinecraftForge.EVENT_BUS.unregister(this);
        mc.shutdown();
    }
}
