package better112.qol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.event.ClickEvent;
import net.minecraft.util.text.event.HoverEvent;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeProvider;

/**
 * /locatebiome, the 1.16 command: finds the nearest place with the named biome and prints coordinates that
 * can be clicked to go there. Registered on the single-player server only, since this mod is client-side.
 */
public final class LocateBiome extends CommandBase {

    private static final int STEP = 64, RADIUS = 6400;

    @Override
    public String getName() {
        return "locatebiome";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/locatebiome <biome>";
    }

    /** The short name a biome goes by here: its id without the namespace and without this mod's import prefix. */
    private static String shortName(ResourceLocation id) {
        String path = id.getPath();
        if ("better112".equals(id.getNamespace())) {
            if (path.startsWith("mc_")) {
                return path.substring(3);
            }
            if (path.startsWith("bop_")) {
                return path.substring(4);
            }
        }
        return path;
    }

    private static List<Biome> named(String name) {
        String wanted = name.toLowerCase(Locale.ROOT);
        List<Biome> found = new ArrayList<>();
        for (Biome biome : Biome.REGISTRY) {
            ResourceLocation id = biome.getRegistryName();
            if (id != null && (id.toString().equals(wanted) || id.getPath().equals(wanted) || shortName(id).equals(wanted))) {
                found.add(biome);
            }
        }
        return found;
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, BlockPos target) {
        if (args.length != 1) {
            return Collections.emptyList();
        }
        TreeSet<String> names = new TreeSet<>();
        for (Biome biome : Biome.REGISTRY) {
            if (biome.getRegistryName() != null) {
                names.add(shortName(biome.getRegistryName()));
            }
        }
        return getListOfStringsMatchingLastWord(args, names);
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length != 1) {
            throw new WrongUsageException(getUsage(sender));
        }
        List<Biome> wanted = named(args[0]);
        if (wanted.isEmpty()) {
            // Plain English rather than this mod's lang keys: on a server, players without the mod read these too.
            throw new CommandException("There is no biome called %s", args[0]);
        }
        BlockPos from = sender.getPosition();
        int[] at = find(sender.getEntityWorld().getBiomeProvider(), wanted, from.getX(), from.getZ());
        if (at == null) {
            throw new CommandException("Could not find a %s within %s blocks", nameOf(wanted.get(0)), RADIUS);
        }
        ITextComponent place = new TextComponentString("[" + at[0] + ", ~, " + at[1] + "]");
        place.getStyle().setColor(TextFormatting.GREEN)
                .setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/tp @p " + at[0] + " ~ " + at[1]))
                .setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new TextComponentTranslation("chat.coordinates.tooltip")));
        int away = (int) Math.sqrt((double) (at[0] - from.getX()) * (at[0] - from.getX()) + (double) (at[1] - from.getZ()) * (at[1] - from.getZ()));
        sender.sendMessage(new TextComponentTranslation("The nearest %s is at %s (%s blocks away)", nameOf(wanted.get(0)), place, away));
    }

    /** A biome's display name. Biome.getBiomeName exists only in the client's build of the game, so the field is read. */
    static String nameOf(Biome biome) {
        try {
            String name = net.minecraftforge.fml.relauncher.ReflectionHelper.getPrivateValue(Biome.class, biome, "biomeName", "field_76791_y");
            if (name != null) {
                return name;
            }
        } catch (Throwable t) {
            // fall back to the id
        }
        return biome.getRegistryName() == null ? "biome" : biome.getRegistryName().getPath();
    }

    /** Looks outward ring by ring on a grid, so the first hit is about the nearest. Returns x and z, or null. */
    public static int[] find(BiomeProvider provider, List<Biome> wanted, int fromX, int fromZ) {
        Biome[] one = new Biome[1];
        for (int ring = 0; ring <= RADIUS / STEP; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                boolean edge = Math.abs(dx) == ring;
                for (int dz = -ring; dz <= ring; dz += edge || ring == 0 ? 1 : 2 * ring) {
                    int x = fromX + dx * STEP, z = fromZ + dz * STEP;
                    one = provider.getBiomes(one, x, z, 1, 1, false);
                    if (wanted.contains(one[0])) {
                        return new int[] {x, z};
                    }
                }
            }
        }
        return null;
    }
}
