package better112.content;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * Builds the resource pack that gives imported blocks their looks: blockstate files,
 * models, textures and names, taken from the modern mod's jar and, for the stock model
 * shapes the mod builds on ("a slab", "a door"), from a modern Minecraft jar.
 *
 * Everything lands under this mod's own namespace, in folders named after where it came
 * from (models/block/bop/..., models/block/mc/...), so nothing can collide with 1.12.2's
 * own files of the same name or with a 1.12.2 version of the same mod.
 */
final class ContentPack {

    static final String DOMAIN = "better112";

    /** The jar and the prefix of each mod that blocks are taken from, by the mod's namespace. */
    private final Map<String, ZipFile> mods = new LinkedHashMap<String, ZipFile>();
    private final Map<String, String> prefixes = new LinkedHashMap<String, String>();
    private final List<ZipFile> vanilla;
    private final Map<String, byte[]> files = new LinkedHashMap<String, byte[]>();
    private final Deque<String> modelQueue = new ArrayDeque<String>();
    private final Set<String> seenModels = new HashSet<String>();
    private final Set<String> textures = new HashSet<String>();
    private final StringBuilder lang = new StringBuilder();
    final Set<String> missing = new HashSet<String>();

    ContentPack(List<ZipFile> vanilla) {
        this.vanilla = vanilla;
    }

    void addMod(String namespace, String prefix, ZipFile jar) {
        mods.put(namespace, jar);
        prefixes.put(namespace, prefix);
    }

    JsonObject json(String namespace, String path) {
        byte[] bytes = read(namespace, path);
        return bytes == null ? null : new JsonParser().parse(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    /** A file of the mod (its namespace) or of modern Minecraft, by path under assets/NAMESPACE/. */
    byte[] read(String namespace, String path) {
        String name = "assets/" + namespace + "/" + path;
        try {
            if (mods.containsKey(namespace)) {
                return bytes(mods.get(namespace), name);
            }
            for (ZipFile zip : vanilla) {
                byte[] found = bytes(zip, name);
                if (found != null) {
                    return found;
                }
            }
        } catch (IOException e) {
            // unreadable is the same as absent here
        }
        return null;
    }

    private static byte[] bytes(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            return null;
        }
        try (InputStream in = zip.getInputStream(entry)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, (int) entry.getSize()));
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) > 0;) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    /** "biomesoplenty:block/fir_slab" or "block/slab" as {namespace, path}. */
    static String[] split(String reference) {
        int colon = reference.indexOf(':');
        return colon < 0 ? new String[] {"minecraft", reference} : new String[] {reference.substring(0, colon), reference.substring(colon + 1)};
    }

    /** Where a model or texture of the modern world lives in the pack: "better112:block/bop/fir_slab". */
    String moved(String reference) {
        String[] parts = split(reference);
        int slash = parts[1].indexOf('/');
        String kind = slash < 0 ? "block" : parts[1].substring(0, slash);
        String rest = slash < 0 ? parts[1] : parts[1].substring(slash + 1);
        return DOMAIN + ":" + kind + "/" + (prefixes.containsKey(parts[0]) ? prefixes.get(parts[0]) : "mc") + "/" + rest;
    }

    /** The blockstate file of an imported block, with its model names pointed at the pack. */
    void addBlockstate(String registryPath, JsonObject modern) {
        addBlockstate(registryPath, modern, 0);
    }

    /** As above; "parts" says how a list of conditional parts is fitted to the 1.12.2 block (see BlockDef.parts). */
    void addBlockstate(String registryPath, JsonObject modern, int parts) {
        JsonObject copy = new JsonParser().parse(modern.toString()).getAsJsonObject();
        if (parts != 0 && copy.has("multipart")) {
            JsonArray kept = new JsonArray();
            for (JsonElement part : copy.getAsJsonArray("multipart")) {
                JsonObject o = part.getAsJsonObject();
                if (!o.has("when")) {
                    kept.add(o);
                    continue;
                }
                if (parts == 3) {
                    // The piece that lies on the ground is the one that asks for "bottom"; the wall pieces are left out.
                    if (o.get("when").toString().contains("\"bottom\"") && !kept.toString().contains("\"model\"")) {
                        o.remove("when");
                        kept.add(o);
                    }
                    continue;
                }
                JsonObject when = o.getAsJsonObject("when"), fitted = new JsonObject();
                boolean keep = true;
                for (Map.Entry<String, JsonElement> condition : when.entrySet()) {
                    String value = condition.getValue().getAsString().toLowerCase(java.util.Locale.ROOT);
                    if (parts == 1) {
                        keep &= value.equals("true"); // a face that is shown; "false" parts are the inside of a mushroom
                    } else if (value.equals("tall")) {
                        keep = false; // 1.12.2 walls have one height
                    } else {
                        fitted.addProperty(condition.getKey(), value.equals("low") ? "true" : value.equals("none") ? "false" : value);
                    }
                }
                if (!keep) {
                    continue;
                }
                if (parts == 1) {
                    o.remove("when");
                } else {
                    o.add("when", fitted);
                }
                kept.add(o);
            }
            copy.add("multipart", kept);
        }
        if (copy.has("variants")) {
            JsonObject variants = copy.getAsJsonObject("variants"), renamed = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : variants.entrySet()) {
                models(entry.getValue());
                // 1.12.2 calls the variant of a block without properties "normal".
                renamed.add(entry.getKey().isEmpty() ? "normal" : entry.getKey(), entry.getValue());
            }
            copy.add("variants", renamed);
        }
        if (copy.has("multipart")) {
            for (JsonElement part : copy.getAsJsonArray("multipart")) {
                models(part.getAsJsonObject().get("apply"));
            }
        }
        files.put("assets/" + DOMAIN + "/blockstates/" + registryPath + ".json", copy.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void models(JsonElement applied) {
        if (applied == null) {
            return;
        }
        if (applied.isJsonArray()) {
            for (JsonElement e : (JsonArray) applied) {
                models(e);
            }
            return;
        }
        JsonObject o = applied.getAsJsonObject();
        if (o.has("model")) {
            String reference = o.get("model").getAsString();
            modelQueue.add(reference);
            // A 1.12.2 blockstate names its models without the leading "block/".
            String target = moved(reference);
            o.add("model", new JsonPrimitive(DOMAIN + ":" + target.substring(target.indexOf(':') + 1 + "block/".length())));
        }
    }

    /** The item model, stored under the item's registry name as 1.12.2 expects. */
    void addItemModel(String registryPath, String modernReference) {
        String[] parts = split(modernReference);
        JsonObject model = json(parts[0], "models/" + parts[1] + ".json");
        if (model == null) {
            missing.add(modernReference);
            return;
        }
        files.put("assets/" + DOMAIN + "/models/item/" + registryPath + ".json", rewriteModel(model).toString().getBytes(StandardCharsets.UTF_8));
    }

    /** An item model that is simply the block's own model: how Minecraft 1.21.4 and later show most blocks in the hand. */
    void addItemModelOf(String registryPath, String blockModel) {
        modelQueue.add(blockModel);
        JsonObject model = new JsonObject();
        model.addProperty("parent", moved(blockModel));
        files.put("assets/" + DOMAIN + "/models/item/" + registryPath + ".json", model.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** An item model that is a flat picture, for a plant that has no item of its own in the modern game. */
    void addItemModelFlat(String registryPath, String texture) {
        JsonObject model = new JsonObject(), layers = new JsonObject();
        model.addProperty("parent", "minecraft:item/generated");
        layers.addProperty("layer0", texture);
        model.add("textures", layers);
        files.put("assets/" + DOMAIN + "/models/item/" + registryPath + ".json", rewriteModel(model).toString().getBytes(StandardCharsets.UTF_8));
    }

    boolean has(String namespace, String path) {
        return read(namespace, path) != null;
    }

    /** A texture no model names (a sign's, which the game draws itself). */
    void addTexture(String reference) {
        textures.add(reference);
    }

    void addName(String key, String english) {
        lang.append(key).append('=').append(english).append('\n');
    }

    private JsonObject rewriteModel(JsonObject model) {
        JsonObject copy = new JsonParser().parse(model.toString()).getAsJsonObject();
        if (copy.has("parent")) {
            String parent = copy.get("parent").getAsString();
            if (!parent.startsWith("builtin/")) {
                modelQueue.add(parent);
                copy.add("parent", new JsonPrimitive(moved(parent)));
            }
        }
        if (copy.has("textures")) {
            JsonObject texturesIn = copy.getAsJsonObject("textures"), texturesOut = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : texturesIn.entrySet()) {
                String value = entry.getValue().getAsString();
                if (!value.startsWith("#")) {
                    textures.add(value);
                    value = moved(value);
                }
                texturesOut.add(entry.getKey(), new JsonPrimitive(value));
            }
            // Modern models may point one of their own textures at another ("particle": "#side"); 1.12.2 cannot
            // follow that within one model and shows the purple placeholder, so the name is filled in here.
            for (Map.Entry<String, JsonElement> entry : texturesIn.entrySet()) {
                String value = entry.getValue().getAsString();
                for (int hops = 0; hops < 4 && value.startsWith("#") && texturesOut.has(value.substring(1)); hops++) {
                    value = texturesOut.get(value.substring(1)).getAsString();
                }
                if (!value.startsWith("#") && entry.getValue().getAsString().startsWith("#")) {
                    texturesOut.add(entry.getKey(), new JsonPrimitive(value));
                }
            }
            copy.add("textures", texturesOut);
        }
        if (copy.has("overrides")) {
            for (JsonElement override : copy.getAsJsonArray("overrides")) {
                JsonObject o = override.getAsJsonObject();
                if (o.has("model")) {
                    modelQueue.add(o.get("model").getAsString());
                    o.add("model", new JsonPrimitive(moved(o.get("model").getAsString())));
                }
            }
        }
        return copy;
    }

    /** Pulls in every model the queued ones build on, and every texture they name. */
    private void resolve() {
        while (!modelQueue.isEmpty()) {
            String reference = modelQueue.poll();
            if (!seenModels.add(reference)) {
                continue;
            }
            String[] parts = split(reference);
            JsonObject model = json(parts[0], "models/" + parts[1] + ".json");
            if (model == null) {
                missing.add(reference);
                continue;
            }
            String target = moved(reference);
            files.put("assets/" + DOMAIN + "/models/" + target.substring(target.indexOf(':') + 1) + ".json",
                    rewriteModel(model).toString().getBytes(StandardCharsets.UTF_8));
        }
        for (String reference : textures) {
            String[] parts = split(reference);
            byte[] png = read(parts[0], "textures/" + parts[1] + ".png");
            if (png == null) {
                missing.add(reference + ".png");
                continue;
            }
            String target = moved(reference);
            String path = "assets/" + DOMAIN + "/textures/" + target.substring(target.indexOf(':') + 1) + ".png";
            files.put(path, png);
            byte[] meta = read(parts[0], "textures/" + parts[1] + ".png.mcmeta");
            if (meta != null) {
                files.put(path + ".mcmeta", meta);
            }
        }
    }

    int write(File target, String comment) throws IOException {
        resolve();
        if (lang.length() > 0) {
            files.put("assets/" + DOMAIN + "/lang/en_us.lang", lang.toString().getBytes(StandardCharsets.UTF_8));
        }
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(temp))) {
            out.setComment(comment);
            out.putNextEntry(new ZipEntry("pack.mcmeta"));
            out.write(new Gson().toJson(packMeta()).getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                out.putNextEntry(new ZipEntry(file.getKey()));
                out.write(file.getValue());
                out.closeEntry();
            }
        }
        Files.deleteIfExists(target.toPath());
        if (!temp.renameTo(target)) {
            throw new IOException("could not move " + temp + " into place");
        }
        return files.size();
    }

    private static JsonObject packMeta() {
        JsonObject pack = new JsonObject();
        pack.addProperty("pack_format", 3);
        pack.addProperty("description", "Blocks imported from modern mods by Better 1.12.2");
        JsonObject root = new JsonObject();
        root.add("pack", pack);
        return root;
    }

    /** Reads a JSON file of the mod that is not under assets (its language file is; tags and data are not). */
    static JsonObject jsonAt(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            return null;
        }
        try (InputStream in = zip.getInputStream(entry)) {
            return new JsonParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
