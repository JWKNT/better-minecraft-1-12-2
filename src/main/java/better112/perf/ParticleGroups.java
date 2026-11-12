package better112.perf;

import java.util.LinkedHashMap;
import java.util.Map;

import better112.config.BetterConfig;

/**
 * Particle toggles on the Particles page. Vanilla particles are grouped by what a player
 * would call them; everything a mod adds falls into one "modded" group.
 */
public final class ParticleGroups {

    private ParticleGroups() {}

    public static final String MODDED = "modded";

    private static final String VANILLA_PACKAGE = "net.minecraft.client.particle.";

    /** Group id to the simple names of the vanilla particle classes in it. */
    public static final Map<String, String[]> GROUPS = new LinkedHashMap<String, String[]>();

    private static final Map<String, String> BY_CLASS = new java.util.HashMap<String, String>();

    static {
        GROUPS.put("explosion", new String[] {"ParticleExplosion", "ParticleExplosionHuge", "ParticleExplosionLarge"});
        GROUPS.put("smoke", new String[] {"ParticleSmokeNormal", "ParticleSmokeLarge", "ParticleCloud"});
        GROUPS.put("flame", new String[] {"ParticleFlame", "ParticleLava"});
        GROUPS.put("water", new String[] {"ParticleBubble", "ParticleSplash", "ParticleWaterWake", "ParticleSuspend", "ParticleRain"});
        GROUPS.put("drip", new String[] {"ParticleDrip"});
        GROUPS.put("potion", new String[] {"ParticleSpell"});
        GROUPS.put("portal", new String[] {"ParticlePortal"});
        GROUPS.put("enchant", new String[] {"ParticleEnchantmentTable"});
        GROUPS.put("redstone", new String[] {"ParticleRedstone"});
        GROUPS.put("crit", new String[] {"ParticleCrit", "ParticleSweepAttack"});
        GROUPS.put("hearts", new String[] {"ParticleHeart"});
        GROUPS.put("note", new String[] {"ParticleNote"});
        GROUPS.put("firework", new String[] {"ParticleFirework"});
        GROUPS.put("dust", new String[] {"ParticleFallingDust", "ParticleBlockDust", "ParticleSuspendedTown", "ParticleFootStep"});
        GROUPS.put("item", new String[] {"ParticleBreaking", "ParticleSnowShovel"});
        GROUPS.put(MODDED, new String[0]);
        for (Map.Entry<String, String[]> e : GROUPS.entrySet()) {
            for (String name : e.getValue()) {
                BY_CLASS.put(name, e.getKey());
            }
        }
    }

    public static boolean isDisabled(Class<?> particleClass) {
        if (BetterConfig.disabledParticles.isEmpty()) {
            return false;
        }
        String group = groupOf(particleClass);
        return group != null && BetterConfig.disabledParticles.contains(group);
    }

    private static String groupOf(Class<?> particleClass) {
        String name = particleClass.getName();
        if (!name.startsWith(VANILLA_PACKAGE)) {
            return MODDED;
        }
        String simple = name.substring(VANILLA_PACKAGE.length());
        int inner = simple.indexOf('$');
        if (inner >= 0) {
            simple = simple.substring(0, inner);
        }
        return BY_CLASS.get(simple);
    }
}
