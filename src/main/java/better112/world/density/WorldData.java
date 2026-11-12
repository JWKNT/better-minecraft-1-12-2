package better112.world.density;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

/**
 * The worldgen data files of modern Minecraft, read straight from jars and data packs on the
 * player's own machine. Later sources override earlier ones, the way data packs do in the
 * modern game: the Minecraft jar first, then mods and packs.
 */
public final class WorldData implements Closeable {

    private final List<ZipFile> sources = new ArrayList<ZipFile>();

    public WorldData(List<File> files) throws IOException {
        for (File file : files) {
            sources.add(new ZipFile(file));
        }
    }

    /** "minecraft:overworld/depth" of kind "density_function" is data/minecraft/worldgen/density_function/overworld/depth.json. */
    public JsonElement read(String kind, String id) {
        int colon = id.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : id.substring(0, colon);
        String path = colon < 0 ? id : id.substring(colon + 1);
        String name = "data/" + namespace + "/worldgen/" + kind + "/" + path + ".json";
        for (int i = sources.size() - 1; i >= 0; i--) {
            ZipFile zip = sources.get(i);
            ZipEntry entry = zip.getEntry(name);
            if (entry != null) {
                try (InputStream in = zip.getInputStream(entry)) {
                    return new JsonParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    throw new IllegalStateException("Could not read " + name + " from " + zip.getName(), e);
                }
            }
        }
        throw new IllegalArgumentException("No worldgen file " + name);
    }

    public static String canonical(String id) {
        return id.indexOf(':') < 0 ? "minecraft:" + id : id;
    }

    @Override
    public void close() throws IOException {
        for (ZipFile zip : sources) {
            zip.close();
        }
    }
}
