package better112.textures;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import better112.Better112;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.TextureStitchEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;

/**
 * Textures that VintageFix's fast model loading leaves out of the texture atlas. VintageFix finds
 * the textures to load by reading model files; a mod that names its textures in code (EvilCraft's
 * broom parts, for one) then gets the purple-and-black placeholder, and VintageFix logs "Texture
 * X was not discovered during texture pass". This puts those textures in the atlas itself: the ones
 * known from Divine Journey 2, and every one VintageFix names in a game, kept in a file for the
 * next start.
 */
public final class LateSprites {

    private LateSprites() {}

    private static final Pattern WARNING = Pattern.compile("Texture (\\S+) was not discovered during texture pass");
    /** Seen in Divine Journey 2 2.23.4 with VintageFix 0.7.0. */
    private static final String[] KNOWN = {
        "evilcraft:broom_parts/bare", "evilcraft:broom_parts/brush_feather", "evilcraft:broom_parts/brush_leaves",
        "evilcraft:broom_parts/brush_wheat", "evilcraft:broom_parts/cap_gem", "evilcraft:broom_parts/cap_head_creeper",
        "evilcraft:broom_parts/cap_head_skeleton", "evilcraft:broom_parts/cap_head_wither",
        "evilcraft:broom_parts/cap_head_witherskeleton", "evilcraft:broom_parts/cap_head_zombie", "evilcraft:broom_parts/cap_metal",
        "evilcraft:broom_parts/ring", "evilcraft:broom_parts/rod_blaze", "evilcraft:broom_parts/rod_bone", "evilcraft:broom_parts/rod_reed",
        "enderutilities:items/endertool_null_head_normal_1", "enderutilities:items/endertool_null_rod", "solarflux:blocks/example_base"};

    private static final Set<String> wanted = new LinkedHashSet<String>(Arrays.asList(KNOWN));
    private static File file;
    public static int added;

    /** Called while the mod is constructed: the first atlas is made before mods initialise. */
    public static void install(File gameDir) {
        file = new File(gameDir, "better112/late_sprites.txt");
        try {
            if (file.isFile()) {
                for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                    if (line.indexOf(':') > 0) {
                        wanted.add(line.trim());
                    }
                }
            }
        } catch (IOException e) {
            Better112.LOG.debug("Could not read {}", file, e);
        }
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new LateSprites.Events());
        listen();
    }

    /** Notes the textures VintageFix reports, for the next start. */
    private static void listen() {
        try {
            Logger root = (Logger) LogManager.getRootLogger();
            AbstractAppender appender = new AbstractAppender("Better112LateSprites", null, null, true) {
                @Override
                public void append(LogEvent event) {
                    String text = event.getMessage() == null ? null : event.getMessage().getFormattedMessage();
                    if (text == null || !text.startsWith("Texture ")) {
                        return;
                    }
                    Matcher m = WARNING.matcher(text);
                    if (m.find()) {
                        remember(m.group(1));
                    }
                }
            };
            appender.start();
            root.addAppender(appender);
        } catch (Throwable t) {
            Better112.LOG.debug("Could not watch the log for missed textures", t);
        }
    }

    private static synchronized void remember(String sprite) {
        if (!wanted.add(sprite) || file == null) {
            return;
        }
        try {
            file.getParentFile().mkdirs();
            Files.write(file.toPath(), (sprite + "\n").getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            Better112.LOG.debug("Could not write {}", file, e);
        }
    }

    public static final class Events {
        @SubscribeEvent
        public void stitch(TextureStitchEvent.Pre event) {
            if (!"textures".equals(event.getMap().getBasePath())) {
                return;
            }
            int count = 0;
            for (String name : wanted.toArray(new String[0])) {
                ResourceLocation id = new ResourceLocation(name);
                // Only textures that exist: a name from an older pack would otherwise be a placeholder of its own.
                try {
                    Minecraft.getMinecraft().getResourceManager()
                            .getResource(new ResourceLocation(id.getNamespace(), "textures/" + id.getPath() + ".png")).close();
                } catch (IOException e) {
                    continue;
                }
                event.getMap().registerSprite(id);
                count++;
            }
            added = count;
            if (count > 0) {
                Better112.LOG.info("Added {} textures that the model loader would have left out of the atlas", count);
            }
        }
    }
}
