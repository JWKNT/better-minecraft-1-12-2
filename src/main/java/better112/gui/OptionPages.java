package better112.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Supplier;

import better112.Hooks;
import better112.config.BetterConfig;
import better112.core.PatchState;
import better112.cosmetic.WorldColors;
import better112.gui.Option.Impact;
import better112.perf.ParticleGroups;
import better112.textures.ModernTextures;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.GameSettings;
import net.minecraftforge.common.ForgeModContainer;
import net.minecraftforge.common.config.Configuration;

import org.lwjgl.opengl.Display;

/** Every page of the settings screen. Vanilla settings first, then the mod's own. */
public final class OptionPages {

    private OptionPages() {}

    public static List<OptionPage> build() {
        final Minecraft mc = Minecraft.getMinecraft();
        final GameSettings gs = mc.gameSettings;
        List<OptionPage> pages = new ArrayList<OptionPage>();

        // ---------------------------------------------------------------- General
        int maxScale = 1;
        while (maxScale < 12 && mc.displayWidth / (maxScale + 1) >= 320 && mc.displayHeight / (maxScale + 1) >= 240) {
            maxScale++;
        }
        final boolean halfSteps = !PatchState.hasFailed("gui_scale");
        pages.add(new OptionPage("general")
                .group(
                        slider("render_distance", 2, 32, 1, v -> I18n.format("better112.value.chunks", v),
                                () -> gs.renderDistanceChunks,
                                v -> {
                                    gs.renderDistanceChunks = v;
                                    mc.renderGlobal.setDisplayListEntitiesDirty();
                                }).impact(Impact.HIGH).flags(Option.VANILLA),
                        slider("brightness", 0, 100, 1,
                                v -> v == 0 ? I18n.format("options.gamma.min") : v == 100 ? I18n.format("options.gamma.max") : v + "%",
                                () -> Math.round(gs.gammaSetting * 100.0F), v -> gs.gammaSetting = v / 100.0F)
                                .flags(Option.VANILLA))
                .group(
                        // Slider steps: 0 is Auto, then 1x, 1.5x, 2x, ... in half steps.
                        slider("gui_scale", 0, halfSteps ? maxScale * 2 - 1 : maxScale, 1,
                                v -> v == 0 ? I18n.format("options.guiScale.auto")
                                        : !halfSteps ? v + "x" : (v + 1) % 2 == 0 ? (v + 1) / 2 + "x" : (v + 1) / 2 + ".5x",
                                () -> {
                                    if (gs.guiScale == 0) {
                                        return 0;
                                    }
                                    int whole = Math.min(gs.guiScale, 12);
                                    if (!halfSteps) {
                                        return whole;
                                    }
                                    int halves = BetterConfig.guiScaleHalves;
                                    boolean half = halves > 1 && (halves & 1) == 1 && (halves + 1) / 2 == whole;
                                    return (half ? halves : whole * 2) - 1;
                                },
                                v -> {
                                    if (v == 0 || !halfSteps) {
                                        gs.guiScale = v;
                                        BetterConfig.guiScaleHalves = 0;
                                    } else {
                                        int halves = v + 1;
                                        gs.guiScale = (halves + 1) / 2;
                                        BetterConfig.guiScaleHalves = (halves & 1) == 1 ? halves : 0;
                                    }
                                }).flags(Option.VANILLA | Option.BETTER | Option.RESIZE),
                        tick("gui_scale_sharp", () -> BetterConfig.guiScaleSharp, v -> BetterConfig.guiScaleSharp = v)
                                .flags(Option.BETTER | Option.RESIZE),
                        tick("fullscreen", () -> gs.fullScreen,
                                v -> {
                                    gs.fullScreen = v;
                                    if (mc.isFullScreen() != v) {
                                        mc.toggleFullscreen();
                                    }
                                }).flags(Option.VANILLA | Option.RESIZE),
                        tick("vsync", () -> gs.enableVsync,
                                v -> {
                                    gs.enableVsync = v;
                                    Display.setVSyncEnabled(v);
                                }).flags(Option.VANILLA),
                        slider("max_fps", 10, 260, 10,
                                v -> v >= 260 ? I18n.format("options.framerateLimit.max") : I18n.format("better112.value.fps", v),
                                () -> Math.max(10, Math.min(260, gs.limitFramerate)), v -> gs.limitFramerate = v)
                                .flags(Option.VANILLA),
                        slider("background_fps", 0, 60, 5,
                                v -> v == 0 ? I18n.format("options.off") : I18n.format("better112.value.fps", v),
                                () -> BetterConfig.backgroundFps, v -> BetterConfig.backgroundFps = v)
                                .flags(Option.BETTER),
                        slider("idle_fps", 0, 30, 5,
                                v -> v == 0 ? I18n.format("options.off") : I18n.format("better112.value.fps", v),
                                () -> BetterConfig.idleFps, v -> BetterConfig.idleFps = v)
                                .flags(Option.BETTER))
                .group(
                        tick("view_bobbing", () -> gs.viewBobbing, v -> gs.viewBobbing = v).flags(Option.VANILLA),
                        cycle("attack_indicator", () -> gs.attackIndicator, v -> gs.attackIndicator = v,
                                "options.off", "options.attack.crosshair", "options.attack.hotbar").flags(Option.VANILLA)));

        // ---------------------------------------------------------------- Quality
        pages.add(new OptionPage("quality")
                .group(
                        cycle("graphics", () -> gs.fancyGraphics ? 1 : 0, v -> gs.fancyGraphics = v == 1,
                                "options.graphics.fast", "options.graphics.fancy")
                                .impact(Impact.HIGH).flags(Option.VANILLA | Option.RELOAD_CHUNKS))
                .group(
                        cycle("clouds", () -> gs.clouds, v -> gs.clouds = v,
                                "options.off", "options.clouds.fast", "options.clouds.fancy")
                                .impact(Impact.LOW).flags(Option.VANILLA),
                        patched(cycle("weather_quality", () -> BetterConfig.weatherQuality, v -> BetterConfig.weatherQuality = v,
                                "better112.value.default", "options.graphics.fancy", "options.graphics.fast")
                                .impact(Impact.MEDIUM).flags(Option.BETTER), "weather_quality"),
                        patched(cycle("leaves_quality", () -> BetterConfig.leavesQuality, v -> BetterConfig.leavesQuality = v,
                                "better112.value.default", "options.graphics.fancy", "options.graphics.fast", "better112.value.smart")
                                .impact(Impact.MEDIUM).flags(Option.BETTER | Option.RELOAD_CHUNKS), "leaves", "leaves_cull", "leaves_layer"),
                        patched(cycle("dynamic_lights", () -> BetterConfig.dynamicLights, v -> BetterConfig.dynamicLights = v,
                                "options.off", "options.graphics.fast", "options.graphics.fancy")
                                .impact(Impact.MEDIUM).flags(Option.BETTER), "dynamic_light_chunks", "dynamic_light_world"),
                        patched(cycle("better_grass", () -> BetterConfig.betterGrass, v -> BetterConfig.betterGrass = v,
                                "options.off", "options.graphics.fast", "options.graphics.fancy")
                                .impact(Impact.LOW).flags(Option.BETTER | Option.RELOAD_CHUNKS), "block_model"),
                        cycle("falling_leaves", () -> BetterConfig.fallingLeaves, v -> BetterConfig.fallingLeaves = v,
                                "options.off", "better112.value.few", "better112.value.many")
                                .impact(Impact.LOW).flags(Option.BETTER),
                        cycle("fireflies", () -> BetterConfig.fireflies, v -> BetterConfig.fireflies = v,
                                "options.off", "better112.value.few", "better112.value.many")
                                .impact(Impact.LOW).flags(Option.BETTER),
                        tick("rain_ripples", () -> BetterConfig.rainRipples, v -> BetterConfig.rainRipples = v)
                                .impact(Impact.LOW).flags(Option.BETTER),
                        cycle("particles", () -> gs.particleSetting, v -> gs.particleSetting = v,
                                "options.particles.all", "options.particles.decreased", "options.particles.minimal")
                                .impact(Impact.MEDIUM).flags(Option.VANILLA),
                        cycle("smooth_lighting", () -> gs.ambientOcclusion, v -> gs.ambientOcclusion = v,
                                "options.ao.off", "options.ao.min", "options.ao.max")
                                .impact(Impact.LOW).flags(Option.VANILLA | Option.RELOAD_CHUNKS),
                        patched(slider("biome_blend", 0, 7, 1,
                                v -> v == 0 ? I18n.format("better112.value.none") : I18n.format("better112.value.blocks", v * 2 + 1, v * 2 + 1),
                                () -> BetterConfig.biomeBlend, v -> BetterConfig.biomeBlend = v)
                                .impact(Impact.LOW).flags(Option.BETTER | Option.RELOAD_CHUNKS), "biome_blend"),
                        slider("entity_distance", 50, 500, 25, v -> v + "%",
                                () -> BetterConfig.entityDistance, v -> BetterConfig.entityDistance = v)
                                .impact(Impact.MEDIUM).flags(Option.BETTER),
                        tick("entity_shadows", () -> gs.entityShadows, v -> gs.entityShadows = v)
                                .impact(Impact.LOW).flags(Option.VANILLA),
                        tick("vignette", () -> BetterConfig.vignette, v -> BetterConfig.vignette = v)
                                .impact(Impact.LOW).flags(Option.BETTER))
                .group(
                        slider("mipmap_levels", 0, 4, 1, v -> v == 0 ? I18n.format("options.off") : v + "x",
                                () -> gs.mipmapLevels, v -> gs.setOptionFloatValue(GameSettings.Options.MIPMAP_LEVELS, v))
                                .impact(Impact.MEDIUM).flags(Option.VANILLA | Option.MIPMAPS)));

        // ---------------------------------------------------------------- Shaders
        final Option<Integer> shaderPreset = cycle("shaders", () -> BetterConfig.shaders, v -> BetterConfig.shaders = v,
                "options.off", "better112.value.light", "better112.value.intense", "better112.value.custom")
                .impact(Impact.HIGH).flags(Option.BETTER | Option.RELOAD_CHUNKS)
                .availableWhen(() -> better112.shader.Shaders.possible() && !anyFailed("shader_layer", "shader_final"),
                        () -> anyFailed("shader_layer", "shader_final") ? I18n.format("better112.unavailable.patch")
                                : I18n.format("better112.unavailable.shaders", better112.shader.Shaders.problem))
                .note(better112.shader.Shaders::note);
        java.util.function.BooleanSupplier custom = () -> shaderPreset.get() == better112.shader.Shaders.CUSTOM;
        // Greyed out under Light or Intense, the rows show what that preset uses.
        java.util.function.IntFunction<Supplier<Boolean>> presetTick = i -> () -> better112.shader.Shaders.presetValue(shaderPreset.get(), i) != 0;
        java.util.function.IntFunction<Supplier<Integer>> presetInt = i -> () -> better112.shader.Shaders.presetValue(shaderPreset.get(), i);
        Supplier<String> pickCustom = () -> I18n.format("better112.unavailable.shaders_custom");
        pages.add(new OptionPage("shaders")
                .group(shaderPreset)
                .group(
                        tick("shader_lighting", () -> BetterConfig.shaderLighting, v -> BetterConfig.shaderLighting = v)
                                .impact(Impact.LOW).flags(Option.BETTER).availableWhen(custom, pickCustom).showWhenUnavailable(presetTick.apply(0)),
                        cycle("shader_shadows", () -> BetterConfig.shaderShadows, v -> BetterConfig.shaderShadows = v,
                                "options.off", "better112.value.near", "better112.value.far", "better112.value.very_far")
                                .impact(Impact.HIGH).flags(Option.BETTER).availableWhen(custom, pickCustom).showWhenUnavailable(presetInt.apply(1)),
                        tick("shader_waving", () -> BetterConfig.shaderWaving, v -> BetterConfig.shaderWaving = v)
                                .impact(Impact.LOW).flags(Option.BETTER | Option.RELOAD_CHUNKS).availableWhen(custom, pickCustom).showWhenUnavailable(presetTick.apply(2)),
                        cycle("shader_water", () -> BetterConfig.shaderWater, v -> BetterConfig.shaderWater = v,
                                "better112.value.default", "better112.value.waves", "better112.value.reflections")
                                .impact(Impact.MEDIUM).flags(Option.BETTER).availableWhen(custom, pickCustom).showWhenUnavailable(presetInt.apply(3)),
                        tick("shader_rays", () -> BetterConfig.shaderRays, v -> BetterConfig.shaderRays = v)
                                .impact(Impact.LOW).flags(Option.BETTER).availableWhen(custom, pickCustom).showWhenUnavailable(presetTick.apply(4)),
                        tick("shader_ao", () -> BetterConfig.shaderAo, v -> BetterConfig.shaderAo = v)
                                .impact(Impact.MEDIUM).flags(Option.BETTER).availableWhen(custom, pickCustom).showWhenUnavailable(presetTick.apply(5)),
                        slider("shader_bloom", 0, 100, 5, v -> v == 0 ? I18n.format("options.off") : v + "%",
                                () -> BetterConfig.shaderBloom, v -> BetterConfig.shaderBloom = v)
                                .impact(Impact.LOW).flags(Option.BETTER).availableWhen(custom, pickCustom).showWhenUnavailable(presetInt.apply(6)),
                        slider("shader_color", 0, 100, 5, v -> v == 0 ? I18n.format("options.off") : v + "%",
                                () -> BetterConfig.shaderColor, v -> BetterConfig.shaderColor = v)
                                .impact(Impact.LOW).flags(Option.BETTER).availableWhen(custom, pickCustom).showWhenUnavailable(presetInt.apply(7))));

        // ---------------------------------------------------------------- Performance
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        pages.add(new OptionPage("performance")
                .group(
                        patched(slider("chunk_threads", 0, cores, 1,
                                v -> v == 0 ? I18n.format("better112.value.default") : I18n.format("better112.value.threads", v),
                                () -> Math.min(cores, BetterConfig.chunkThreads), v -> BetterConfig.chunkThreads = v)
                                .impact(Impact.HIGH).flags(Option.BETTER | Option.RELOAD_DISPATCHER), "chunk_threads"),
                        tick("defer_chunk_updates", better112.perf.DynamicLights::userDefer,
                                v -> {
                                    better112.perf.DynamicLights.setUserDefer(v);
                                    Configuration config = ForgeModContainer.getConfig();
                                    if (config != null) {
                                        config.get(Configuration.CATEGORY_CLIENT, "alwaysSetupTerrainOffThread", false).set(v);
                                        config.save();
                                    }
                                }).impact(Impact.HIGH))
                .group(
                        patched(tick("entity_culling", () -> BetterConfig.entityCulling, v -> BetterConfig.entityCulling = v)
                                .impact(Impact.HIGH).flags(Option.BETTER), "entity_render", "tile_entity_render"),
                        patched(slider("block_entity_distance", 25, 100, 5, v -> v + "%",
                                () -> BetterConfig.blockEntityDistance, v -> BetterConfig.blockEntityDistance = v)
                                .impact(Impact.MEDIUM).flags(Option.BETTER), "tile_entity_render"),
                        patched(tick("particle_culling", () -> BetterConfig.particleCulling, v -> BetterConfig.particleCulling = v)
                                .impact(Impact.MEDIUM).flags(Option.BETTER), "particle_render", "camera"),
                        patched(tick("animate_visible_only", () -> BetterConfig.animateVisibleOnly, v -> BetterConfig.animateVisibleOnly = v)
                                .impact(Impact.HIGH).flags(Option.BETTER),
                                "sprite_animation", "sprite_getters", "sprite_quads", "sprite_chunks", "sprite_visible")
                                .availableWhen(
                                        () -> Hooks.animationsHandledBy == null && !anyFailed("sprite_animation", "sprite_getters",
                                                "sprite_quads", "sprite_chunks", "sprite_visible"),
                                        () -> Hooks.animationsHandledBy != null
                                                ? I18n.format("better112.unavailable.handled", Hooks.animationsHandledBy)
                                                : I18n.format("better112.unavailable.patch")))
                .group(
                        tick("use_vbo", () -> gs.useVbo, v -> gs.useVbo = v)
                                .impact(Impact.HIGH).flags(Option.VANILLA | Option.RELOAD_CHUNKS)));

        // ---------------------------------------------------------------- Animations
        pages.add(new OptionPage("animations")
                .group(patched(tick("animations", () -> BetterConfig.animations, v -> BetterConfig.animations = v)
                        .flags(Option.BETTER), "sprite_animation"))
                .group(
                        patched(tick("anim_water", () -> BetterConfig.animWater, v -> BetterConfig.animWater = v)
                                .flags(Option.BETTER), "sprite_animation"),
                        patched(tick("anim_lava", () -> BetterConfig.animLava, v -> BetterConfig.animLava = v)
                                .flags(Option.BETTER), "sprite_animation"),
                        patched(tick("anim_fire", () -> BetterConfig.animFire, v -> BetterConfig.animFire = v)
                                .flags(Option.BETTER), "sprite_animation"),
                        patched(tick("anim_portal", () -> BetterConfig.animPortal, v -> BetterConfig.animPortal = v)
                                .flags(Option.BETTER), "sprite_animation"),
                        patched(tick("anim_blocks", () -> BetterConfig.animBlocks, v -> BetterConfig.animBlocks = v)
                                .flags(Option.BETTER), "sprite_animation")));

        // ---------------------------------------------------------------- Particles
        OptionPage particles = new OptionPage("particles")
                .group(patched(tick("particles_all", () -> BetterConfig.particles, v -> BetterConfig.particles = v)
                        .flags(Option.BETTER), "particle_add"))
                .group(
                        patched(tick("rain_splash", () -> BetterConfig.rainSplash, v -> BetterConfig.rainSplash = v)
                                .flags(Option.BETTER), "rain_splash"),
                        patched(tick("block_break", () -> BetterConfig.blockBreak, v -> BetterConfig.blockBreak = v)
                                .flags(Option.BETTER), "particle_block_break"),
                        patched(tick("block_breaking", () -> BetterConfig.blockBreaking, v -> BetterConfig.blockBreaking = v)
                                .flags(Option.BETTER), "particle_block_hit"));
        List<Option<?>> groups = new ArrayList<Option<?>>();
        for (final String group : ParticleGroups.GROUPS.keySet()) {
            groups.add(patched(tick("particle." + group, () -> !BetterConfig.disabledParticles.contains(group),
                    v -> {
                        if (v) {
                            BetterConfig.disabledParticles.remove(group);
                        } else {
                            BetterConfig.disabledParticles.add(group);
                        }
                    }).flags(Option.BETTER), "particle_add"));
        }
        particles.group(groups.toArray(new Option<?>[0]));
        pages.add(particles);

        // ---------------------------------------------------------------- Details
        pages.add(new OptionPage("details")
                .group(
                        patched(tick("sky", () -> BetterConfig.sky, v -> BetterConfig.sky = v).flags(Option.BETTER), "sky"),
                        patched(tick("stars", () -> BetterConfig.stars, v -> BetterConfig.stars = v).flags(Option.BETTER), "stars"),
                        patched(tick("sun", () -> BetterConfig.sun, v -> BetterConfig.sun = v).flags(Option.BETTER), "texture_bind"),
                        patched(tick("moon", () -> BetterConfig.moon, v -> BetterConfig.moon = v).flags(Option.BETTER), "texture_bind"),
                        patched(tick("weather", () -> BetterConfig.weather, v -> BetterConfig.weather = v).flags(Option.BETTER), "weather"))
                .group(
                        cycle("fog", () -> BetterConfig.fog, v -> BetterConfig.fog = v,
                                "better112.value.default", "better112.value.reduced", "options.off").flags(Option.BETTER),
                        patched(slider("cloud_height", 0, 255, 1, v -> v == 128 ? I18n.format("better112.value.default") : String.valueOf(v),
                                () -> BetterConfig.cloudHeight, v -> BetterConfig.cloudHeight = v).flags(Option.BETTER), "cloud_height")));

        // ---------------------------------------------------------------- Render
        pages.add(new OptionPage("render")
                .group(
                        patched(tick("item_frames", () -> BetterConfig.itemFrames, v -> BetterConfig.itemFrames = v)
                                .flags(Option.BETTER), "entity_render"),
                        patched(tick("armor_stands", () -> BetterConfig.armorStands, v -> BetterConfig.armorStands = v)
                                .flags(Option.BETTER), "entity_render"),
                        patched(tick("paintings", () -> BetterConfig.paintings, v -> BetterConfig.paintings = v)
                                .flags(Option.BETTER), "entity_render"))
                .group(
                        patched(tick("pistons", () -> BetterConfig.pistons, v -> BetterConfig.pistons = v)
                                .flags(Option.BETTER), "tile_entity_render"),
                        patched(tick("beacon_beam", () -> BetterConfig.beaconBeam, v -> BetterConfig.beaconBeam = v)
                                .flags(Option.BETTER), "tile_entity_render"),
                        patched(tick("enchanting_book", () -> BetterConfig.enchantingBook, v -> BetterConfig.enchantingBook = v)
                                .flags(Option.BETTER), "tile_entity_render"))
                .group(
                        tick("player_name_tags", () -> BetterConfig.playerNameTags, v -> BetterConfig.playerNameTags = v)
                                .flags(Option.BETTER)));

        // ---------------------------------------------------------------- Accessibility
        pages.add(new OptionPage("accessibility")
                .group(
                        slider("fov_effects", 0, 100, 5, v -> v == 0 ? I18n.format("options.off") : v + "%",
                                () -> BetterConfig.fovEffects, v -> BetterConfig.fovEffects = v).flags(Option.BETTER),
                        patched(slider("damage_tilt", 0, 100, 5, v -> v == 0 ? I18n.format("options.off") : v + "%",
                                () -> BetterConfig.damageTilt, v -> BetterConfig.damageTilt = v).flags(Option.BETTER), "damage_tilt"),
                        patched(slider("distortion_effects", 0, 100, 5, v -> v == 0 ? I18n.format("options.off") : v + "%",
                                () -> BetterConfig.distortionEffects, v -> BetterConfig.distortionEffects = v).flags(Option.BETTER), "distortion"),
                        slider("menu_blur", 0, 10, 1, v -> v == 0 ? I18n.format("options.off") : String.valueOf(v),
                                () -> BetterConfig.menuBlur, v -> BetterConfig.menuBlur = v).flags(Option.BETTER),
                        patched(slider("glint_speed", 0, 200, 10, v -> v == 0 ? I18n.format("options.off") : v + "%",
                                () -> BetterConfig.glintSpeed, v -> BetterConfig.glintSpeed = v).flags(Option.BETTER), "glint_item"),
                        patched(slider("glint_strength", 0, 100, 5, v -> v == 0 ? I18n.format("options.off") : v + "%",
                                () -> BetterConfig.glintStrength, v -> BetterConfig.glintStrength = v).flags(Option.BETTER), "glint_item"))
                .group(
                        cycle("toggle_sprint", () -> BetterConfig.toggleSprint ? 1 : 0, v -> BetterConfig.toggleSprint = v == 1,
                                "better112.value.hold", "better112.value.toggle").flags(Option.BETTER),
                        cycle("toggle_sneak", () -> BetterConfig.toggleSneak ? 1 : 0, v -> BetterConfig.toggleSneak = v == 1,
                                "better112.value.hold", "better112.value.toggle").flags(Option.BETTER)));

        // ---------------------------------------------------------------- Colors
        String[] skyNames = new String[WorldColors.SKY_NAMES.length + 2];
        skyNames[0] = "better112.value.default";
        skyNames[1] = "better112.value.custom";
        for (int i = 0; i < WorldColors.SKY_NAMES.length; i++) {
            skyNames[i + 2] = "better112.sky." + WorldColors.SKY_NAMES[i];
        }
        String[] waterNames = new String[WorldColors.WATER_NAMES.length + 2];
        waterNames[0] = "better112.value.default";
        waterNames[1] = "better112.value.custom";
        for (int i = 0; i < WorldColors.WATER_NAMES.length; i++) {
            waterNames[i + 2] = "better112.water." + WorldColors.WATER_NAMES[i];
        }
        final Option<Integer> skyPreset = patched(cycle("sky_preset", () -> BetterConfig.skyPreset, v -> BetterConfig.skyPreset = v, skyNames)
                .flags(Option.BETTER), "sky_color");
        final Option<Integer> waterPreset = cycle("water_preset", () -> BetterConfig.waterPreset, v -> {
            BetterConfig.waterPreset = v;
            WorldColors.applyWaterSprites();
        }, waterNames).flags(Option.BETTER | Option.RELOAD_CHUNKS);
        pages.add(new OptionPage("colors")
                .group(
                        skyPreset,
                        colour("sky_custom", () -> BetterConfig.skyCustom, v -> BetterConfig.skyCustom = v).flags(Option.BETTER)
                                .availableWhen(() -> skyPreset.get() == WorldColors.CUSTOM && !anyFailed("sky_color"),
                                        () -> I18n.format("better112.unavailable.pick_custom")))
                .group(
                        waterPreset,
                        colour("water_custom", () -> BetterConfig.waterCustom, v -> BetterConfig.waterCustom = v)
                                .flags(Option.BETTER | Option.RELOAD_CHUNKS)
                                .availableWhen(() -> waterPreset.get() == WorldColors.CUSTOM,
                                        () -> I18n.format("better112.unavailable.pick_custom"))));

        // ---------------------------------------------------------------- Extras
        pages.add(new OptionPage("extras")
                .group(
                        tick("show_fps", () -> BetterConfig.showFps, v -> BetterConfig.showFps = v).flags(Option.BETTER),
                        tick("show_fps_extended", () -> BetterConfig.showFpsExtended, v -> BetterConfig.showFpsExtended = v)
                                .flags(Option.BETTER),
                        tick("show_coords", () -> BetterConfig.showCoords, v -> BetterConfig.showCoords = v).flags(Option.BETTER),
                        cycle("overlay_corner", () -> BetterConfig.overlayCorner, v -> BetterConfig.overlayCorner = v,
                                "better112.value.top_left", "better112.value.top_right", "better112.value.bottom_left",
                                "better112.value.bottom_right").flags(Option.BETTER))
                .group(
                        tick("pickup_notifier", () -> BetterConfig.pickupNotifier, v -> BetterConfig.pickupNotifier = v)
                                .flags(Option.BETTER),
                        tick("effect_timers", () -> BetterConfig.effectTimers, v -> BetterConfig.effectTimers = v)
                                .flags(Option.BETTER),
                        tick("equipment_hud", () -> BetterConfig.equipmentHud, v -> BetterConfig.equipmentHud = v)
                                .flags(Option.BETTER),
                        tick("durability_tooltip", () -> BetterConfig.durabilityTooltip, v -> BetterConfig.durabilityTooltip = v)
                                .flags(Option.BETTER),
                        tick("cell_contents", () -> BetterConfig.cellContents, v -> BetterConfig.cellContents = v)
                                .flags(Option.BETTER))
                .group(
                        tick("chat_emotes", () -> BetterConfig.chatEmotes, v -> BetterConfig.chatEmotes = v).flags(Option.BETTER),
                        tick("chat_item_links", () -> BetterConfig.chatItemLinks, v -> BetterConfig.chatItemLinks = v)
                                .flags(Option.BETTER))
                .group(
                        tick("recipe_bookmarks", () -> BetterConfig.recipeBookmarks, v -> BetterConfig.recipeBookmarks = v)
                                .flags(Option.BETTER)
                                .availableWhen(() -> net.minecraftforge.fml.common.Loader.isModLoaded("jei")
                                                && !net.minecraftforge.fml.common.Loader.isModLoaded("jeiutilities"),
                                        () -> I18n.format(net.minecraftforge.fml.common.Loader.isModLoaded("jei")
                                                ? "better112.unavailable.jei_utilities" : "better112.unavailable.no_jei")),
                        tick("command_suggestions", () -> BetterConfig.commandSuggestions, v -> BetterConfig.commandSuggestions = v)
                                .flags(Option.BETTER),
                        tick("debug_keys", () -> BetterConfig.debugKeys, v -> BetterConfig.debugKeys = v).flags(Option.BETTER),
                        tick("save_indicator", () -> BetterConfig.saveIndicator, v -> BetterConfig.saveIndicator = v)
                                .flags(Option.BETTER),
                        tick("offhand_swap", () -> BetterConfig.offhandSwapInGui, v -> BetterConfig.offhandSwapInGui = v)
                                .flags(Option.BETTER),
                        tick("game_rules_button", () -> BetterConfig.gameRulesButton, v -> BetterConfig.gameRulesButton = v)
                                .flags(Option.BETTER))
                .group(
                        tick("keybind_search", () -> BetterConfig.keybindSearch, v -> BetterConfig.keybindSearch = v).flags(Option.BETTER),
                        tick("equipment_compare", () -> BetterConfig.equipmentCompare, v -> BetterConfig.equipmentCompare = v)
                                .flags(Option.BETTER),
                        tick("durability_warning", () -> BetterConfig.durabilityWarning, v -> BetterConfig.durabilityWarning = v)
                                .flags(Option.BETTER),
                        tick("death_coords", () -> BetterConfig.deathCoords, v -> BetterConfig.deathCoords = v).flags(Option.BETTER))
                .group(
                        tick("freelook", () -> BetterConfig.freelook, v -> BetterConfig.freelook = v).flags(Option.BETTER),
                        tick("zoom_scroll", () -> BetterConfig.zoomScroll, v -> BetterConfig.zoomScroll = v).flags(Option.BETTER),
                        tick("new_item_marks", () -> BetterConfig.newItemMarks, v -> BetterConfig.newItemMarks = v).flags(Option.BETTER),
                        tick("world_search", () -> BetterConfig.worldSearch, v -> BetterConfig.worldSearch = v).flags(Option.BETTER),
                        tick("chat_timestamps", () -> BetterConfig.chatTimestamps, v -> BetterConfig.chatTimestamps = v).flags(Option.BETTER),
                        tick("chat_copy", () -> BetterConfig.chatCopy, v -> BetterConfig.chatCopy = v).flags(Option.BETTER))
                .group(
                        tick("biome_titles", () -> BetterConfig.biomeTitles, v -> BetterConfig.biomeTitles = v).flags(Option.BETTER),
                        tick("skip_front_view", () -> BetterConfig.skipFrontView, v -> BetterConfig.skipFrontView = v).flags(Option.BETTER),
                        tick("clear_lava_fog", () -> BetterConfig.clearLavaFog, v -> BetterConfig.clearLavaFog = v).flags(Option.BETTER),
                        slider("background_volume", 0, 100, 5, v -> v == 100 ? I18n.format("better112.value.unchanged") : v + "%",
                                () -> BetterConfig.backgroundVolume, v -> BetterConfig.backgroundVolume = v).flags(Option.BETTER))
                .group(
                        tick("jei_search_highlight", () -> BetterConfig.jeiSearchHighlight, v -> BetterConfig.jeiSearchHighlight = v)
                                .flags(Option.BETTER)
                                .availableWhen(() -> net.minecraftforge.fml.common.Loader.isModLoaded("jei"),
                                        () -> I18n.format("better112.unavailable.no_jei")),
                        tick("rarity_borders", () -> BetterConfig.rarityBorders, v -> BetterConfig.rarityBorders = v).flags(Option.BETTER),
                        tick("clearer_water", () -> BetterConfig.clearerWater, v -> BetterConfig.clearerWater = v).flags(Option.BETTER),
                        tick("debug_targets", () -> BetterConfig.debugTargets, v -> BetterConfig.debugTargets = v).flags(Option.BETTER))
                .group(
                        tick("item_zoom", () -> BetterConfig.itemZoom, v -> BetterConfig.itemZoom = v).flags(Option.BETTER),
                        tick("better_mount_hud", () -> BetterConfig.betterMountHud, v -> BetterConfig.betterMountHud = v).flags(Option.BETTER),
                        tick("hide_own_effect_particles", () -> BetterConfig.hideOwnEffectParticles, v -> BetterConfig.hideOwnEffectParticles = v)
                                .flags(Option.BETTER))
                .group(
                        tick("toasts", () -> BetterConfig.toasts, v -> BetterConfig.toasts = v).flags(Option.BETTER),
                        tick("ore_dict_tooltips", () -> BetterConfig.oreDictTooltips, v -> BetterConfig.oreDictTooltips = v)
                                .flags(Option.BETTER),
                        tick("ftb_quests", () -> BetterConfig.ftbQuests, v -> BetterConfig.ftbQuests = v)
                                .flags(Option.BETTER)
                                .availableWhen(() -> better112.ClientTweaks.QUESTS_LOADED,
                                        () -> I18n.format("better112.unavailable.no_quests")))
                .group(
                        tick("modern_textures", () -> BetterConfig.modernTextures, v -> BetterConfig.modernTextures = v)
                                .impact(Impact.LOW).flags(Option.BETTER | Option.RELOAD_ASSETS)
                                .availableWhen(ModernTextures::isAvailable, ModernTextures::statusText)
                                .note(() -> ModernTextures.statusText() + " " + ModernTextures.selfCheck()),
                        tick("replace_video_settings", () -> BetterConfig.replaceVideoSettings,
                                v -> BetterConfig.replaceVideoSettings = v).flags(Option.BETTER))
                .group(
                        tick("import_vanilla", () -> BetterConfig.importModernVanilla, v -> BetterConfig.importModernVanilla = v)
                                .flags(Option.BETTER),
                        tick("import_bop", () -> BetterConfig.importBiomesOPlenty, v -> BetterConfig.importBiomesOPlenty = v)
                                .flags(Option.BETTER)
                                .note(() -> better112.content.ModernContent.status() + ". " + better112.world.gen.ImportedBiomes.status() + ".")));

        return pages;
    }

    private static boolean anyFailed(String... patchIds) {
        for (String id : patchIds) {
            if (PatchState.hasFailed(id)) {
                return true;
            }
        }
        return false;
    }

    /** Greys the option out if any patch it relies on could not be applied. */
    private static <T> Option<T> patched(Option<T> option, final String... patchIds) {
        return option.availableWhen(() -> !anyFailed(patchIds), () -> I18n.format("better112.unavailable.patch"));
    }

    private static Option<Boolean> tick(String id, Supplier<Boolean> getter, Consumer<Boolean> setter) {
        return new Option<Boolean>(id, new Control.Tick(), getter, setter);
    }

    private static Option<Integer> colour(String id, Supplier<Integer> getter, Consumer<Integer> setter) {
        return new Option<Integer>(id, new Control.Colour(), getter, setter);
    }

    private static Option<Integer> cycle(String id, Supplier<Integer> getter, Consumer<Integer> setter, String... names) {
        return new Option<Integer>(id, new Control.Cycle(names), getter, setter);
    }

    private static Option<Integer> slider(String id, int min, int max, int step, IntFunction<String> format,
            Supplier<Integer> getter, Consumer<Integer> setter) {
        return new Option<Integer>(id, new Control.Slider(min, max, step, format), getter, setter);
    }
}
