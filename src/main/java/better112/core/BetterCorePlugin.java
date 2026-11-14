package better112.core;

import java.io.File;
import java.util.Map;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;

/**
 * Loads the bytecode patches. Runs after FML's deobfuscation (sorting index above 1000) so
 * the patcher always sees SRG names in a real game and MCP names in a dev workspace.
 */
@IFMLLoadingPlugin.Name("Better112Core")
@IFMLLoadingPlugin.MCVersion("1.12.2")
@IFMLLoadingPlugin.SortingIndex(1001)
@IFMLLoadingPlugin.TransformerExclusions({"better112.core."})
public class BetterCorePlugin implements IFMLLoadingPlugin {

    public BetterCorePlugin() {
        // Before any class patcher runs: TickCentral's class check is put in place now or never (SnifferFix).
        if (PatchState.gameDir() == null && net.minecraft.launchwrapper.Launch.minecraftHome != null) {
            PatchState.init(net.minecraft.launchwrapper.Launch.minecraftHome);
        }
        SnifferFix.apply();
    }

    @Override
    public String[] getASMTransformerClass() {
        return new String[] {"better112.core.BetterTransformer"};
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
        Object location = data.get("mcLocation");
        if (location instanceof File) {
            if (PatchState.gameDir() == null) {
                PatchState.init((File) location);
            }
            better112.config.BetterConfig.load(new File((File) location, "config/better112.cfg"));
        }
        SnifferFix.apply();
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
