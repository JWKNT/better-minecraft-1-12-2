package better112.textures;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;

import better112.Better112;
import better112.config.BetterConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.FileResourcePack;
import net.minecraft.client.resources.IResourcePack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

/**
 * Brings the current vanilla textures to 1.12.2.
 *
 * The textures are not shipped with the mod. They are read from a modern Minecraft jar that
 * is already installed on this computer (any launcher), renamed to their 1.12.2 names,
 * converted where the old renderer needs it, and cached as a small resource pack. The pack
 * sits directly above vanilla, so resource packs and mods that restyle vanilla still win.
 */
public final class ModernTextures {

    private ModernTextures() {}

    /** Bump when the conversion changes so existing caches are rebuilt. */
    private static final int FORMAT = 4;

    private static final String PREFIX = "assets/minecraft/textures/";

    private static volatile Pack pack;
    private static volatile String status = "";

    /** True when a converted pack is ready to use. */
    public static boolean isAvailable() {
        Pack p = pack;
        return p != null && !p.empty;
    }

    /** Why the option is unavailable, or what the pack was built from. */
    public static String statusText() {
        return status;
    }

    public static void install() {
        try {
            File gameDir = Minecraft.getMinecraft().gameDir;
            File dir = new File(gameDir, "better112");
            File zip = new File(dir, "modern_textures.zip");
            File stampFile = new File(dir, "modern_textures.stamp");

            List<SourceJar> sources = findSources(gameDir);
            String stamp = stamp(sources);
            boolean fresh = zip.isFile() && stampFile.isFile()
                    && stamp.equals(new String(Files.readAllBytes(stampFile.toPath()), StandardCharsets.UTF_8));
            if (!fresh && !sources.isEmpty()) {
                long start = System.currentTimeMillis();
                dir.mkdirs();
                int count = build(sources, zip);
                Files.write(stampFile.toPath(), stamp.getBytes(StandardCharsets.UTF_8));
                Better112.LOG.info("Converted {} modern textures from {} in {} ms", count, describe(sources),
                        System.currentTimeMillis() - start);
            }
            if (!zip.isFile()) {
                status = "No Minecraft 1.14 or newer was found on this computer. Install one in any launcher and restart; it is only read.";
                Better112.LOG.info("Modern textures: no source jar found");
                return;
            }
            Pack created = new Pack(zip, "Better 1.12.2 Modern Textures");
            pack = created;
            status = sources.isEmpty() ? "Using the cached vanilla textures." : "Vanilla textures from Minecraft " + sources.get(0).versionText() + ".";

            List<IResourcePack> defaults = ReflectionHelper.getPrivateValue(Minecraft.class, Minecraft.getMinecraft(),
                    "defaultResourcePacks", "field_110449_ao");
            defaults.add(Math.min(1, defaults.size()), created);

            // Mod textures have to sit above the mods' own resources, so they are a second pack at the end.
            try {
                String mods = ModTextures.install(gameDir, dir, defaults);
                if (!mods.isEmpty()) {
                    status += " " + mods;
                }
            } catch (Throwable t) {
                Better112.LOG.warn("Modern mod textures are unavailable", t);
            }
        } catch (Throwable t) {
            status = "Could not prepare the textures: " + t;
            Better112.LOG.warn("Modern textures are unavailable", t);
        }
    }

    // ------------------------------------------------------------------------------------
    // Self-check
    // ------------------------------------------------------------------------------------

    private static final String[] SAMPLES = {
        "textures/blocks/cobblestone.png", "textures/blocks/planks_oak.png", "textures/blocks/log_oak.png",
        "textures/blocks/dirt.png", "textures/blocks/crafting_table_front.png", "textures/items/iron_ingot.png",
        "textures/items/diamond.png", "textures/items/stick.png", "textures/entity/chest/normal.png",
        "textures/entity/zombie/zombie.png",
    };

    private static volatile String checkResult = "";

    /** What the last self-check found; empty until the game has finished loading. */
    public static String checkText() {
        return checkResult;
    }

    /**
     * Asks the game itself what it is using for a handful of well-known vanilla textures:
     * which resource pack supplies each file, and whether the pixels stitched into the block
     * atlas are the converted ones. A setting saying "on" proves nothing; this does.
     */
    public static String selfCheck() {
        Pack p = pack;
        if (p == null || p.empty) {
            return checkResult = "";
        }
        if (!BetterConfig.modernTextures) {
            return checkResult = "Switched off, so the game is using the 1.12.2 art.";
        }
        StringBuilder problems = new StringBuilder();
        int ours = 0, total = 0;
        try (ZipFile zip = new ZipFile(p.file)) {
            Minecraft mc = Minecraft.getMinecraft();
            for (String sample : SAMPLES) {
                ZipEntry entry = zip.getEntry("assets/minecraft/" + sample);
                if (entry == null) {
                    continue; // not a texture this pack converts
                }
                total++;
                byte[] wanted = readAll(zip.getInputStream(entry));
                ResourceLocation location = new ResourceLocation(sample);
                net.minecraft.client.resources.IResource resource = mc.getResourceManager().getResource(location);
                byte[] served;
                try {
                    served = readAll(resource.getInputStream());
                } finally {
                    resource.close();
                }
                if (java.util.Arrays.equals(wanted, served)) {
                    ours++;
                } else {
                    if (problems.length() > 0) {
                        problems.append("; ");
                    }
                    problems.append(sample.substring(sample.lastIndexOf('/') + 1)).append(" comes from ")
                            .append(resource.getResourcePackName());
                }
            }
            // The atlas is what blocks are actually drawn from.
            String atlas = "";
            ZipEntry cobble = zip.getEntry("assets/minecraft/textures/blocks/cobblestone.png");
            if (cobble != null) {
                BufferedImage image = ImageIO.read(zip.getInputStream(cobble));
                net.minecraft.client.renderer.texture.TextureAtlasSprite sprite =
                        mc.getTextureMapBlocks().getAtlasSprite("minecraft:blocks/cobblestone");
                if (sprite != null && sprite.getFrameCount() > 0 && sprite.getIconWidth() == image.getWidth()) {
                    int[] pixels = sprite.getFrameTextureData(0)[0];
                    int[] expected = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
                    boolean same = pixels != null && pixels.length == expected.length;
                    for (int i = 0; same && i < expected.length; i++) {
                        same = (pixels[i] & 0xFFFFFF) == (expected[i] & 0xFFFFFF);
                    }
                    atlas = same ? " The block atlas holds the modern cobblestone." : " The block atlas does NOT hold the modern cobblestone.";
                }
            }
            checkResult = "Check: " + ours + " of " + total + " sample vanilla textures are the modern ones."
                    + (problems.length() > 0 ? " " + problems + "." : "") + atlas;
        } catch (Throwable t) {
            checkResult = "Check failed: " + t;
        }
        Better112.LOG.info("Modern textures self-check. {}", checkResult);
        return checkResult;
    }

    // ------------------------------------------------------------------------------------
    // Finding a modern jar
    // ------------------------------------------------------------------------------------

    private static final class SourceJar {
        final File file;
        final int[] version;

        SourceJar(File file, int[] version) {
            this.file = file;
            this.version = version;
        }

        String versionText() {
            return version[2] == 0 ? version[0] + "." + version[1] : version[0] + "." + version[1] + "." + version[2];
        }
    }

    private static String describe(List<SourceJar> sources) {
        StringBuilder text = new StringBuilder();
        for (SourceJar source : sources) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(source.versionText());
        }
        return text.toString();
    }

    private static String stamp(List<SourceJar> sources) {
        StringBuilder text = new StringBuilder("format ").append(FORMAT).append('\n');
        for (SourceJar source : sources) {
            text.append(source.file.getAbsolutePath()).append('|').append(source.file.length()).append('|')
                    .append(source.file.lastModified()).append('\n');
        }
        return text.toString();
    }

    /** The mods folders of this launcher's other instances, where modern releases of mods may sit. */
    public static List<File> modernModFolders(File gameDir) {
        return Launchers.modFolders(gameDir);
    }

    /** Modern Minecraft jars installed on this computer, newest first. */
    public static List<File> modernGameJars(File gameDir) {
        List<File> files = new ArrayList<File>();
        for (SourceJar source : findSources(gameDir)) {
            files.add(source.file);
        }
        return files;
    }

    /** Release versions from 1.14 (the texture update) on, newest first. */
    private static List<SourceJar> findSources(File gameDir) {
        Set<File> roots = new LinkedHashSet<File>();
        String explicit = System.getProperty("better112.modernJar");
        List<SourceJar> found = new ArrayList<SourceJar>();
        if (explicit != null) {
            File file = new File(explicit);
            int[] version = parseVersion(file.getName().replaceAll("[^0-9.]", " ").trim().split(" ")[0]);
            if (file.isFile()) {
                found.add(new SourceJar(file, version != null ? version : new int[] {1, 99, 0}));
            }
        }
        roots.addAll(Launchers.roots(gameDir));
        Set<String> seen = new HashSet<String>();
        for (File root : roots) {
            // Official launcher layout: versions/<id>/<id>.jar
            File[] versions = new File(root, "versions").listFiles();
            if (versions != null) {
                for (File dir : versions) {
                    consider(found, seen, new File(dir, dir.getName() + ".jar"), dir.getName());
                }
            }
            // Prism / MultiMC layout: libraries/com/mojang/minecraft/<id>/minecraft-<id>-client.jar
            File[] libs = new File(root, "libraries/com/mojang/minecraft").listFiles();
            if (libs != null) {
                for (File dir : libs) {
                    consider(found, seen, new File(dir, "minecraft-" + dir.getName() + "-client.jar"), dir.getName());
                }
            }
        }
        Collections.sort(found, new Comparator<SourceJar>() {
            @Override
            public int compare(SourceJar a, SourceJar b) {
                for (int i = 0; i < 3; i++) {
                    if (a.version[i] != b.version[i]) {
                        return b.version[i] - a.version[i];
                    }
                }
                return 0;
            }
        });
        return found;
    }

    private static void consider(List<SourceJar> found, Set<String> seen, File jar, String id) {
        int[] version = parseVersion(id);
        if (version == null || !jar.isFile() || !seen.add(id)) {
            return;
        }
        if (version[0] == 1 && version[1] < 14) {
            return;
        }
        found.add(new SourceJar(jar, version));
    }

    /** Plain release ids only ("1.21.1"); snapshots and modded profiles are ignored. */
    private static int[] parseVersion(String id) {
        if (id == null || !id.matches("\\d+\\.\\d+(\\.\\d+)?")) {
            return null;
        }
        String[] parts = id.split("\\.");
        return new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), parts.length > 2 ? Integer.parseInt(parts[2]) : 0};
    }

    // ------------------------------------------------------------------------------------
    // Building the pack
    // ------------------------------------------------------------------------------------

    private static int build(List<SourceJar> sources, File target) throws IOException {
        List<ZipFile> jars = new ArrayList<ZipFile>();
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        int count = 0;
        try {
            for (SourceJar source : sources) {
                jars.add(new ZipFile(source.file));
            }
            try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(temp));
                    BufferedReader map = new BufferedReader(new InputStreamReader(
                            ModernTextures.class.getResourceAsStream("/assets/better112/modern_textures.map"),
                            StandardCharsets.UTF_8))) {
                out.putNextEntry(new ZipEntry("pack.mcmeta"));
                out.write(("{\"pack\":{\"pack_format\":3,\"description\":\"Modern vanilla textures, converted by Better 1.12.2 from Minecraft "
                        + describe(sources) + "\"}}").getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
                String line;
                while ((line = map.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    try {
                        if (convert(line, sources, jars, out)) {
                            count++;
                        }
                    } catch (Exception e) {
                        Better112.LOG.debug("Skipped texture {}: {}", line, e.toString());
                    }
                }
            }
        } finally {
            for (ZipFile jar : jars) {
                try {
                    jar.close();
                } catch (IOException ignored) {
                    // nothing useful to do
                }
            }
        }
        Files.deleteIfExists(target.toPath());
        if (!temp.renameTo(target)) {
            throw new IOException("could not move " + temp + " into place");
        }
        return count;
    }

    /** The last version that still used the 1.12.2 chest layout. */
    private static final int[] OLD_CHESTS = {1, 14, 4};

    private static boolean convert(String line, List<SourceJar> sources, List<ZipFile> jars, ZipOutputStream out) throws IOException {
        String[] parts = line.split(";");
        int eq = parts[0].indexOf('=');
        String oldName = parts[0].substring(0, eq);
        String[] newNames = parts[0].substring(eq + 1).split("\\|");
        int tint = -1;
        boolean sameSize = false, chest = false, chestDouble = false;
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].startsWith("tint=")) {
                tint = Integer.parseInt(parts[i].substring(5), 16);
            } else if (parts[i].equals("samesize")) {
                sameSize = true;
            } else if (parts[i].equals("chest")) {
                chest = true;
            } else if (parts[i].equals("chestdouble")) {
                chestDouble = true;
            }
        }

        int[] wanted = null;
        if (sameSize) {
            byte[] original = readVanilla(oldName + ".png");
            if (original == null) {
                return false;
            }
            wanted = Png.size(original);
        }

        for (int j = 0; j < jars.size(); j++) {
            ZipFile jar = jars.get(j);
            boolean rearranged = newer(sources.get(j).version, OLD_CHESTS);
            for (String newName : newNames) {
                if (chestDouble) {
                    BufferedImage image;
                    if (rearranged) {
                        BufferedImage left = read(jar, newName + "_left"), right = read(jar, newName + "_right");
                        if (left == null || right == null) {
                            continue;
                        }
                        image = ChestLayout.doubled(left, right);
                    } else {
                        image = read(jar, newName + "_double");
                        if (image == null) {
                            continue;
                        }
                    }
                    write(out, oldName, image);
                    return true;
                }
                ZipEntry entry = jar.getEntry(PREFIX + newName + ".png");
                if (entry == null) {
                    continue;
                }
                byte[] data = readAll(jar.getInputStream(entry));
                if (wanted != null) {
                    int[] size = Png.size(data);
                    if (size[0] != wanted[0] || size[1] != wanted[1]) {
                        continue;
                    }
                }
                BufferedImage image = Png.decode(data);
                if (chest && rearranged) {
                    image = ChestLayout.single(image);
                }
                if (tint >= 0) {
                    tint(image, tint);
                }
                write(out, oldName, image);

                ZipEntry meta = jar.getEntry(PREFIX + newName + ".png.mcmeta");
                if (meta != null) {
                    out.putNextEntry(new ZipEntry(PREFIX + oldName + ".png.mcmeta"));
                    out.write(readAll(jar.getInputStream(meta)));
                    out.closeEntry();
                }
                return true;
            }
        }
        return false;
    }

    private static BufferedImage read(ZipFile jar, String name) throws IOException {
        ZipEntry entry = jar.getEntry(PREFIX + name + ".png");
        return entry == null ? null : Png.decode(readAll(jar.getInputStream(entry)));
    }

    private static void write(ZipOutputStream out, String oldName, BufferedImage image) throws IOException {
        out.putNextEntry(new ZipEntry(PREFIX + oldName + ".png"));
        ByteArrayOutputStream png = new ByteArrayOutputStream(4096);
        ImageIO.write(image, "png", png);
        out.write(png.toByteArray());
        out.closeEntry();
    }

    private static boolean newer(int[] version, int[] limit) {
        for (int i = 0; i < 3; i++) {
            if (version[i] != limit[i]) {
                return version[i] > limit[i];
            }
        }
        return false;
    }

    private static void tint(BufferedImage image, int rgb) {
        int tr = (rgb >> 16) & 0xFF, tg = (rgb >> 8) & 0xFF, tb = rgb & 0xFF;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int p = image.getRGB(x, y);
                int r = ((p >> 16) & 0xFF) * tr / 255, g = ((p >> 8) & 0xFF) * tg / 255, b = (p & 0xFF) * tb / 255;
                image.setRGB(x, y, (p & 0xFF000000) | (r << 16) | (g << 8) | b);
            }
        }
    }

    /** The texture as 1.12.2 itself ships it. */
    private static byte[] readVanilla(String path) throws IOException {
        InputStream in = Minecraft.class.getResourceAsStream("/" + PREFIX + path);
        return in == null ? null : readAll(in);
    }

    static byte[] readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    // ------------------------------------------------------------------------------------
    // The resource pack
    // ------------------------------------------------------------------------------------

    /**
     * The cache is an ordinary resource pack zip, so this is an ordinary zip pack that can be
     * switched off. Staying a FileResourcePack matters: mods that list pack contents
     * (VintageFix, for one) only know how to look inside the standard pack classes.
     */
    static final class Pack extends FileResourcePack {
        final boolean empty;
        final File file;
        private final String name;

        Pack(File file, String name) throws IOException {
            super(file);
            this.file = file;
            this.name = name;
            boolean any = false;
            try (ZipFile zip = new ZipFile(file)) {
                for (java.util.Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements();) {
                    if (e.nextElement().getName().startsWith("assets/")) {
                        any = true;
                        break;
                    }
                }
            }
            this.empty = !any;
        }

        @Override
        public boolean resourceExists(ResourceLocation location) {
            return BetterConfig.modernTextures && super.resourceExists(location);
        }

        @Override
        public String getPackName() {
            return name;
        }
    }
}
