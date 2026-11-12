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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;

import better112.Better112;

import net.minecraft.client.resources.IResourcePack;

/**
 * Modern textures for mods, the same way as for vanilla: nothing is shipped, the textures
 * come from a modern release of the mod that is already on this computer.
 *
 * Each supported mod has a table (assets/better112/mods/NAME.map) pairing its 1.12.2 texture
 * names with the modern ones. A table is used only if the 1.12.2 mod is installed here and a
 * modern jar matching one of the table's @source patterns is found, either in the mods
 * folder of another launcher instance or dropped into better112/modern_mods/.
 */
final class ModTextures {

    private ModTextures() {}

    private static final int FORMAT = 7;

    /** Tables shipped with the mod. */
    private static final String[] TABLES = {
        "thermal", "enderio", "mekanism", "ae2", "tconstruct", "botania", "immersiveengineering", "bloodmagic",
        "draconicevolution", "industrialforegoing", "astralsorcery", "divinerpg",
        "twilightforest", "aether", "mysticalagriculture", "atum", "quark", "actuallyadditions", "bigreactors",
        "extendedcrafting", "storagedrawers", "bewitchment", "betternether", "avaritia", "chiselsandbits", "traverse",
        "projectred", "simplyjetpacks", "evilcraft", "projecte",
    };

    private static final class Table {
        final String name;
        final List<Pattern> sources = new ArrayList<Pattern>();
        /** old "domain:path" to new "domain:path". */
        final List<String[]> entries = new ArrayList<String[]>();
        final List<File> jars = new ArrayList<File>();
        int textHash;

        Table(String name) {
            this.name = name;
        }
    }

    /** Builds the mod texture pack if needed and adds it to the pack list. Returns a status line. */
    static String install(File gameDir, File cacheDir, List<IResourcePack> defaults) throws IOException {
        List<Table> tables = new ArrayList<Table>();
        for (String name : TABLES) {
            Table table = read(name);
            if (table != null && isInstalled(table)) {
                tables.add(table);
            }
        }
        if (tables.isEmpty()) {
            return "";
        }

        List<File> folders = new ArrayList<File>();
        File drop = new File(cacheDir, "modern_mods");
        folders.add(drop);
        folders.addAll(Launchers.modFolders(gameDir));
        StringBuilder stamp = new StringBuilder("format ").append(FORMAT).append('\n');
        List<String> missing = new ArrayList<String>();
        for (Table table : tables) {
            findJars(table, folders);
            stamp.append(table.name).append(' ').append(table.textHash).append('\n');
            for (File jar : table.jars) {
                stamp.append(jar.getAbsolutePath()).append('|').append(jar.length()).append('|').append(jar.lastModified()).append('\n');
            }
            if (table.jars.isEmpty()) {
                missing.add(table.name);
            }
        }

        File zip = new File(cacheDir, "modern_mod_textures.zip");
        File stampFile = new File(cacheDir, "modern_mod_textures.stamp");
        File countFile = new File(cacheDir, "modern_mod_textures.txt");
        boolean fresh = zip.isFile() && stampFile.isFile()
                && stamp.toString().equals(new String(Files.readAllBytes(stampFile.toPath()), StandardCharsets.UTF_8));
        if (!fresh) {
            long start = System.currentTimeMillis();
            cacheDir.mkdirs();
            String summary = build(tables, zip);
            Files.write(stampFile.toPath(), stamp.toString().getBytes(StandardCharsets.UTF_8));
            Files.write(countFile.toPath(), summary.getBytes(StandardCharsets.UTF_8));
            Better112.LOG.info("Converted modern mod textures ({}) in {} ms", summary.isEmpty() ? "none found" : summary,
                    System.currentTimeMillis() - start);
        }
        String summary = countFile.isFile() ? new String(Files.readAllBytes(countFile.toPath()), StandardCharsets.UTF_8) : "";
        if (zip.isFile()) {
            ModernTextures.Pack pack = new ModernTextures.Pack(zip, "Better 1.12.2 Modern Mod Textures");
            if (!pack.empty) {
                defaults.add(pack);
            }
        }
        StringBuilder status = new StringBuilder();
        if (!summary.isEmpty()) {
            status.append("Mods: ").append(summary).append('.');
        }
        if (!missing.isEmpty()) {
            if (status.length() > 0) {
                status.append(' ');
            }
            status.append("No modern jar found for: ").append(String.join(", ", missing))
                    .append(" (put one in better112/modern_mods).");
        }
        return status.toString();
    }

    private static Table read(String name) {
        InputStream in = ModTextures.class.getResourceAsStream("/assets/better112/mods/" + name + ".map");
        if (in == null) {
            return null;
        }
        Table table = new Table(name);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                table.textHash = table.textHash * 31 + line.hashCode();
                if (line.startsWith("@source ")) {
                    table.sources.add(Pattern.compile(line.substring(8).trim()));
                } else if (!line.isEmpty() && !line.startsWith("#")) {
                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        table.entries.add(new String[] {line.substring(0, eq), line.substring(eq + 1)});
                    }
                }
            }
        } catch (Exception e) {
            Better112.LOG.warn("Bad texture table {}", name, e);
            return null;
        }
        return table.entries.isEmpty() ? null : table;
    }

    /** True if the 1.12.2 mod the table is for is in this pack. */
    private static boolean isInstalled(Table table) {
        int tried = 0;
        for (String[] entry : table.entries) {
            if (installedTexture(entry[0]) != null) {
                return true;
            }
            if (++tried >= 12) {
                break;
            }
        }
        return false;
    }

    private static String resourcePath(String domainAndPath) {
        int colon = domainAndPath.indexOf(':');
        return "assets/" + domainAndPath.substring(0, colon) + "/textures/" + domainAndPath.substring(colon + 1) + ".png";
    }

    /** The texture as the installed 1.12.2 mod ships it, or null. */
    private static byte[] installedTexture(String domainAndPath) {
        return installedFile(resourcePath(domainAndPath));
    }

    private static byte[] installedFile(String path) {
        try {
            InputStream in = ModTextures.class.getResourceAsStream("/" + path);
            return in == null ? null : ModernTextures.readAll(in);
        } catch (IOException e) {
            return null;
        }
    }

    private static void findJars(Table table, List<File> folders) {
        for (Pattern pattern : table.sources) {
            File best = null;
            for (File folder : folders) {
                File[] files = folder.listFiles();
                if (files == null) {
                    continue;
                }
                for (File file : files) {
                    if (file.isFile() && pattern.matcher(file.getName()).matches() && isModern(file)) {
                        // The drop folder comes first in the list and wins; otherwise the newest file.
                        if (best == null || (best.getParentFile().equals(folder) && file.lastModified() > best.lastModified())) {
                            best = file;
                        }
                    }
                }
                if (best != null) {
                    break;
                }
            }
            if (best != null && !table.jars.contains(best)) {
                table.jars.add(best);
            }
        }
        Collections.sort(table.jars, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareTo(b.getName());
            }
        });
    }

    /** A jar for Minecraft 1.13 or later: those describe themselves with a .toml or Fabric file. */
    private static boolean isModern(File jar) {
        try (ZipFile zip = new ZipFile(jar)) {
            return zip.getEntry("META-INF/mods.toml") != null || zip.getEntry("META-INF/neoforge.mods.toml") != null
                    || zip.getEntry("fabric.mod.json") != null || zip.getEntry("quilt.mod.json") != null;
        } catch (IOException e) {
            return false;
        }
    }

    /** The wanted textures from a jar and from the jars nested inside it. */
    private static void load(File jar, java.util.Set<String> wanted, Map<String, byte[]> textures) throws IOException {
        try (ZipFile zip = new ZipFile(jar)) {
            for (java.util.Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements();) {
                ZipEntry entry = e.nextElement();
                String name = entry.getName();
                if (wanted.contains(name)) {
                    if (!textures.containsKey(name)) {
                        textures.put(name, ModernTextures.readAll(zip.getInputStream(entry)));
                    }
                } else if ((name.startsWith("META-INF/jarjar/") || name.startsWith("META-INF/jars/")) && name.endsWith(".jar")) {
                    // Forge nests jars under jarjar/, Fabric under jars/ (Traverse keeps its art in one).
                    try (ZipInputStream nested = new ZipInputStream(zip.getInputStream(entry))) {
                        ZipEntry inner;
                        while ((inner = nested.getNextEntry()) != null) {
                            if (wanted.contains(inner.getName()) && !textures.containsKey(inner.getName())) {
                                ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
                                byte[] buffer = new byte[4096];
                                int n;
                                while ((n = nested.read(buffer)) > 0) {
                                    out.write(buffer, 0, n);
                                }
                                textures.put(inner.getName(), out.toByteArray());
                            }
                        }
                    }
                }
            }
        }
    }

    private static String build(List<Table> tables, File target) throws IOException {
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        StringBuilder summary = new StringBuilder();
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(temp))) {
            out.putNextEntry(new ZipEntry("pack.mcmeta"));
            out.write("{\"pack\":{\"pack_format\":3,\"description\":\"Modern mod textures, converted by Better 1.12.2\"}}"
                    .getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            for (Table table : tables) {
                if (table.jars.isEmpty()) {
                    continue;
                }
                java.util.Set<String> wanted = new java.util.HashSet<String>();
                for (String[] entry : table.entries) {
                    wanted.add(resourcePath(entry[1]));
                    wanted.add(resourcePath(entry[1]) + ".mcmeta");
                }
                Map<String, byte[]> modern = new HashMap<String, byte[]>();
                for (File jar : table.jars) {
                    try {
                        load(jar, wanted, modern);
                    } catch (IOException e) {
                        Better112.LOG.warn("Could not read {}", jar, e);
                    }
                }
                // Textures that join up with their neighbours name further sheets in their .mcmeta; those are wanted too.
                java.util.Set<String> sheets = new java.util.HashSet<String>();
                for (String[] entry : table.entries) {
                    for (String sheet : connectedSheets(parse(modern.get(resourcePath(entry[1]) + ".mcmeta")))) {
                        sheets.add(resourcePath(sheet));
                    }
                }
                if (!sheets.isEmpty()) {
                    for (File jar : table.jars) {
                        try {
                            load(jar, sheets, modern);
                        } catch (IOException e) {
                            Better112.LOG.warn("Could not read {}", jar, e);
                        }
                    }
                }
                java.util.Set<String> written = new java.util.HashSet<String>();
                int count = 0;
                for (String[] entry : table.entries) {
                    try {
                        if (convert(entry[0], entry[1], modern, out, written)) {
                            count++;
                        }
                    } catch (Exception e) {
                        Better112.LOG.debug("Skipped {}: {}", entry[0], e.toString());
                    }
                }
                if (count > 0) {
                    if (summary.length() > 0) {
                        summary.append(", ");
                    }
                    summary.append(table.name).append(' ').append(count);
                }
            }
        }
        Files.deleteIfExists(target.toPath());
        if (!temp.renameTo(target)) {
            throw new IOException("could not move " + temp + " into place");
        }
        return summary.toString();
    }

    private static com.google.gson.JsonObject parse(byte[] meta) {
        if (meta == null) {
            return null;
        }
        try {
            return new com.google.gson.JsonParser().parse(new String(meta, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The extra sheets a connected texture's .mcmeta names ("mekanism:blocks/boiler_casing-ctm"), in order. */
    private static List<String> connectedSheets(com.google.gson.JsonObject meta) {
        List<String> sheets = new ArrayList<String>();
        if (meta != null && meta.has("ctm") && meta.get("ctm").isJsonObject() && meta.getAsJsonObject("ctm").has("textures")
                && meta.getAsJsonObject("ctm").get("textures").isJsonArray()) {
            for (com.google.gson.JsonElement sheet : meta.getAsJsonObject("ctm").getAsJsonArray("textures")) {
                if (sheet.isJsonPrimitive() && sheet.getAsString().indexOf(':') > 0) {
                    sheets.add(sheet.getAsString());
                }
            }
        }
        return sheets;
    }

    private static byte[] animationOnly(com.google.gson.JsonObject meta) {
        if (meta == null || !meta.has("animation")) {
            return null;
        }
        com.google.gson.JsonObject only = new com.google.gson.JsonObject();
        only.add("animation", meta.get("animation"));
        return only.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void write(ZipOutputStream out, java.util.Set<String> written, String path, byte[] data) throws IOException {
        if (data != null && written.add(path)) {
            out.putNextEntry(new ZipEntry(path));
            out.write(data);
            out.closeEntry();
        }
    }

    private static byte[] reencode(byte[] png) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(4096);
        ImageIO.write(Png.decode(png), "png", bytes);
        return bytes.toByteArray();
    }

    private static boolean convert(String oldName, String newName, Map<String, byte[]> modern, ZipOutputStream out, java.util.Set<String> written)
            throws IOException {
        byte[] installed = installedTexture(oldName);
        byte[] replacement = modern.get(resourcePath(newName));
        if (installed == null || replacement == null) {
            return false;
        }
        // Same shape only: a texture whose proportions changed belongs to a model that changed
        // too. A different resolution is fine (DivineRPG went from 32 pixels to 16). The one
        // exception is a square still that the modern release animates: a strip of frames
        // with its .mcmeta works on 1.12.2 as it is.
        int[] want = Png.size(installed), have = Png.size(replacement);
        com.google.gson.JsonObject newMeta = parse(modern.get(resourcePath(newName) + ".mcmeta"));
        byte[] oldMetaBytes = installedFile(resourcePath(oldName) + ".mcmeta");
        com.google.gson.JsonObject oldMeta = parse(oldMetaBytes);
        boolean sameShape = (long) want[0] * have[1] == (long) want[1] * have[0];
        String path = resourcePath(oldName);

        // A texture's .mcmeta is read from the pack its image comes from, so replacing an image means supplying
        // the .mcmeta as well. Two things live there: an animation, and (with the Connected Textures mod) how
        // the texture joins up with its neighbours. The second names blocks and images of this version of the
        // mod, so it is always the installed one that is kept, never the modern one.
        List<String> oldSheets = connectedSheets(oldMeta);
        if (oldMeta != null && oldMeta.has("ctm")) {
            // The joined-up look is drawn from further sheets. They are replaced along with the plain image, or
            // a block would change its art the moment it has a neighbour; if that cannot be done the block keeps
            // its 1.12.2 look entirely.
            List<String> newSheets = connectedSheets(newMeta);
            if (!sameShape || newSheets.size() != oldSheets.size()) {
                return false;
            }
            List<byte[]> sheetImages = new ArrayList<byte[]>();
            for (int i = 0; i < oldSheets.size(); i++) {
                byte[] oldSheet = installedTexture(oldSheets.get(i)), newSheet = modern.get(resourcePath(newSheets.get(i)));
                if (oldSheet == null || newSheet == null) {
                    return false;
                }
                int[] a = Png.size(oldSheet), b = Png.size(newSheet);
                if ((long) a[0] * b[1] != (long) a[1] * b[0]) {
                    return false;
                }
                sheetImages.add(newSheet);
            }
            if (written.contains(path)) {
                return false;
            }
            write(out, written, path, reencode(replacement));
            write(out, written, path + ".mcmeta", oldMetaBytes);
            for (int i = 0; i < oldSheets.size(); i++) {
                String sheetPath = resourcePath(oldSheets.get(i));
                if (!written.contains(sheetPath)) {
                    write(out, written, sheetPath, reencode(sheetImages.get(i)));
                    write(out, written, sheetPath + ".mcmeta", installedFile(sheetPath + ".mcmeta"));
                }
            }
            return true;
        }

        byte[] meta = animationOnly(newMeta);
        boolean nowAnimated = meta != null && want[0] == want[1] && have[1] % have[0] == 0;
        if (!sameShape && !nowAnimated) {
            return false;
        }
        if (meta == null && have[0] != have[1]) {
            // A strip without its own frame list keeps the installed one, or the atlas rejects it.
            meta = animationOnly(oldMeta);
            if (meta == null) {
                return false;
            }
        }
        if (written.contains(path)) {
            return false;
        }
        write(out, written, path, reencode(replacement));
        write(out, written, path + ".mcmeta", meta);
        return true;
    }
}
