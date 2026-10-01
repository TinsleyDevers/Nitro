package com.tinsl.nitro.compat;

import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.moddiscovery.ModFileInfo;
import net.neoforged.neoforgespi.language.IModInfo;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides whether a fix that patches another mod may be applied, and records why when it may not.
 *
 * Nitro's fixes reach into other mods' private fields and methods. Presence of the mod is not
 * enough: a mod that renames a field Nitro shadows makes the mixin fail to apply, and a failed
 * shadow is a hard error that takes the game down. So every such fix declares the members it needs,
 * and they are checked against the class as it actually sits in the installed jar, before Mixin
 * ever looks at it. A mismatch skips the fix and logs one line; the game still starts, just without
 * that fix.
 *
 * A version range is declared as well, but it is advisory by default: the shape check is what
 * prevents crashes, and refusing to help on a version that is only untested (rather than
 * incompatible) would make Nitro useless on any pack that is not the one it was written on. Fixes
 * whose correctness depends on behaviour rather than on class shape (the JEI startup, which
 * re-implements a method, and MEGA Cells, whose recipe semantics matter) mark their range as hard
 * and are skipped outside it. Setting strictVersions=true makes every range hard.
 */
public final class ModTargets {
    private static final Logger LOGGER = LogManager.getLogger("Nitro");

    /** Outcome of asking whether a fix may be applied. */
    public enum State {
        ACTIVE("active"),
        ACTIVE_UNTESTED("active, untested version"),
        OFF_CONFIG("off in config"),
        OFF_ABSENT("mod not installed"),
        OFF_SHAPE("mod changed, fix does not fit"),
        OFF_VERSION("untested mod version"),
        OFF_CONFLICT("another mod does this");

        public final String label;

        State(String label) {
            this.label = label;
        }
    }

    /** A decision plus the detail that explains it, for the log and the startup table. */
    public record Decision(State state, String detail) {
        public boolean active() {
            return state == State.ACTIVE || state == State.ACTIVE_UNTESTED;
        }
    }

    /**
     * What one fix needs from the mod it patches.
     *
     * @param modId    the mod that owns the class
     * @param className the class the mixin targets
     * @param versions  maven range of versions the fix was tested against
     * @param hardRange true when a version outside the range must skip the fix even in non-strict mode
     * @param fields    field names the fix shadows or accesses
     * @param methods   method names the fix injects into or shadows
     */
    private record Target(String modId, String className, String versions, boolean hardRange,
                          List<String> fields, List<String> methods) {}

    private static Target soft(String modId, String className, String versions, List<String> fields, List<String> methods) {
        return new Target(modId, className, versions, false, fields, methods);
    }

    private static Target hard(String modId, String className, String versions, List<String> fields, List<String> methods) {
        return new Target(modId, className, versions, true, fields, methods);
    }

    private static final List<String> NONE = List.of();

    /** Moonlight's mod version carries the Minecraft version in front of its own ("1.21.1-3.7.0"). */
    private static final String MOONLIGHT = "[1.21.1-3.5,1.21.2)";

    /**
     * Every fix that touches another mod, with the versions it was tested against on a 1017 mod
     * 1.21.1 pack and the members it needs to still exist.
     */
    private static final Map<String, Target> TARGETS = new LinkedHashMap<>();

    static {
        TARGETS.put("jaopca", soft("jaopca", "thelm.jaopca.client.models.ModelHandler", "[5.0,6.0)",
            List.of("BLOCK_MODEL_REMAPS", "ITEM_MODEL_REMAPS", "BLOCK_MODEL_REMAPS_REVERSE", "ITEM_MODEL_REMAPS_REVERSE"),
            List.of("remapModels")));

        TARGETS.put("modernfix.attributes", soft("modernfix", "org.embeddedt.modernfix.entity.AttributeInstanceTemplates$1",
            "[5.20,6.0)", NONE, List.of("hashCode")));
        TARGETS.put("modernfix.modelCache", soft("modernfix", "org.embeddedt.modernfix.neoforge.dynresources.ModelBakeEventHelper",
            "[5.20,6.0)", List.of("topLevelModelLocations", "namespacesWithModels"), List.of("<init>")));
        TARGETS.put("modernfix.modelVisibility", soft("modernfix", "org.embeddedt.modernfix.neoforge.dynresources.ModelBakeEventHelper",
            "[5.20,6.0)", NONE, List.of("wrapRegistry")));
        TARGETS.put("modernfix.visibilityIndex", soft("modernfix", "org.embeddedt.modernfix.neoforge.dynresources.ModelBakeEventHelper",
            "[5.20,6.0)", NONE, List.of("wrapRegistry", "computeVisibleModIds")));

        TARGETS.put("farmersdelight", soft("farmersdelight", "vectorwing.farmersdelight.common.crafting.ingredient.ItemAbilityIngredient",
            "[1.3,2.0)", List.of("itemAbility", "itemStacks"), List.of("dissolve")));

        TARGETS.put("moonlight.fastSearch", soft("moonlight", "net.mehvahdjukaar.moonlight.api.resources.pack.FastSearchPathPackResources",
            MOONLIGHT, List.of("root", "packType"), List.of("getResource", "listResources")));
        TARGETS.put("moonlight.cachedAccessor", soft("moonlight", "net.mehvahdjukaar.moonlight.api.resources.pack.AbstractCachedEditableResources",
            MOONLIGHT, List.of("path", "packType"), NONE));
        TARGETS.put("moonlight.cacheWrite", soft("moonlight", "net.mehvahdjukaar.moonlight.api.resources.pack.CachePathPackResources",
            MOONLIGHT, NONE, List.of("addResource", "clearAllResources")));

        TARGETS.put("elysium", soft("elysium_api", "net.jadenxgamer.elysium_api.impl.client.conditional_resource.ConditionalAssetsHelper",
            "[2.0,3.0)", NONE, List.of("isAssetHidden")));
        TARGETS.put("hexerei", soft("hexerei", "net.joefoxe.hexerei.data.recipes.WoodcutterRecipes",
            "[0.5,1.0)", NONE, List.of("createRecipes")));
        TARGETS.put("relics", soft("relics", "it.hurts.sskirillss.relics.init.RelicsCreativeTabs",
            "[0.12,1.0)", NONE, List.of("fillCreativeTabs")));
        TARGETS.put("sable", soft("sable", "dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertiesDefinitionLoader",
            "[2.0,3.0)", NONE, List.of("apply", "applyAll")));
        TARGETS.put("moreculling", soft("moreculling", "", "[1.0,2.0)", NONE, NONE));
        TARGETS.put("puzzleslib", soft("puzzleslib", "fuzs.puzzleslib.neoforge.impl.client.event.NeoForgeClientEventInvokers",
            "[21.1,21.2)", NONE, List.of("getTopLevelModelLocations")));
        TARGETS.put("c2me", soft("c2me_fixes_worldgen_threading_issues",
            "com.ishland.c2me.fixes.worldgen.threading_issues.common.CheckedThreadLocalRandom", "[0.3,1.0)",
            NONE, List.of("handleNotOwner")));
        TARGETS.put("createoreexcavation", soft("createoreexcavation", "com.tom.createores.kubejs.KubeJSExcavation",
            "[1.6,2.0)", NONE, List.of("registerTypeWrappers")));
        TARGETS.put("moogs", soft("moogs_paths", "com.finndog.moogs_paths.world.deferred.PathfindWorker",
            "[1.0,2.0)", NONE, List.of("start")));

        // Behaviour-bound, not just shape-bound: the recipe pairing semantics are what is being changed.
        TARGETS.put("megacells", hard("megacells", "gripe._90.megacells.misc.CompressionService", "[4.11,4.12)",
            NONE, List.of("loadRecipes", "isIrreversible")));
        // The async startup re-implements JeiStarter.start(), so it is tied to that method's contents.
        // The range comes from config (jeiVersions) so a user can widen it without a Nitro release.
        // Every field and method the JeiStarter accessor reaches, so a widened jeiVersions can at worst
        // switch async JEI off, never fail a mixin.
        TARGETS.put("jei", hard("jei", "mezz.jei.library.startup.JeiStarter", null,
            List.of("running", "data", "plugins", "vanillaPlugin", "modIdFormatConfig", "colorNameConfig",
                "recipeCategorySortingConfig", "stopCallbacks", "jeiClientConfigs"),
            List.of("start", "stop", "verifyClientRecipes")));
        TARGETS.put("jei.errorUtil", soft("jei", "mezz.jei.common.util.ErrorUtil", null, NONE, List.of("assertMainThread")));
    }

    /** Mods that already do what one of Nitro's optional fixes does; Nitro stands down for them. */
    private static final Map<String, String> CONFLICTS = Map.of(
        "parallelRecipes", "fastsuite",
        // Just Enough Threads cancels JeiStarter.start() and runs its own asynchronous startup, the
        // same takeover Nitro's async JEI does. Two of those on one method cannot both be right.
        "asyncJei", "justenoughthreads");

    private static final Map<String, Decision> DECISIONS = Collections.synchronizedMap(new LinkedHashMap<>());
    private static final Map<String, Set<String>[]> SHAPES = new java.util.HashMap<>();

    private static volatile boolean strictVersions;
    private static volatile String jeiRange = com.tinsl.nitro.common.NitroSettings.DEFAULT_JEI_VERSIONS;

    private ModTargets() {}

    /** Called once from the mixin plugin, before any check. */
    public static void configure(boolean strict, String jeiVersionRange) {
        strictVersions = strict;
        if (jeiVersionRange != null && !jeiVersionRange.isBlank()) {
            jeiRange = jeiVersionRange.trim();
        }
    }

    public static boolean present(String modId) {
        return FMLLoader.getLoadingModList() != null && FMLLoader.getLoadingModList().getModFileById(modId) != null;
    }

    /** Version of an installed mod, or null when it is not installed or declares no version. */
    public static ArtifactVersion version(String modId) {
        ModFileInfo file = FMLLoader.getLoadingModList() == null ? null : FMLLoader.getLoadingModList().getModFileById(modId);
        if (file == null) {
            return null;
        }
        for (IModInfo mod : file.getMods()) {
            if (mod.getModId().equals(modId)) {
                return mod.getVersion();
            }
        }
        return null;
    }

    /**
     * Whether the fix registered under {@code group} may be applied. Enabled is the caller's own
     * config check, so a group that is off in the config never pays for a class read.
     */
    public static Decision check(String group, boolean enabledInConfig) {
        Decision cached = DECISIONS.get(group);
        if (cached != null) {
            return cached;
        }
        Decision decision = decide(group, enabledInConfig);
        DECISIONS.put(group, decision);
        log(group, decision);
        return decision;
    }

    private static Decision decide(String group, boolean enabledInConfig) {
        Target target = TARGETS.get(group);
        if (target == null) {
            return new Decision(enabledInConfig ? State.ACTIVE : State.OFF_CONFIG, "");
        }
        // Absence is checked first so the report does not say "off in config" about a fix for a mod
        // that is not even installed, which reads as if turning the setting on would do something.
        if (!present(target.modId())) {
            return new Decision(State.OFF_ABSENT, target.modId());
        }
        if (!enabledInConfig) {
            return new Decision(State.OFF_CONFIG, "");
        }
        ArtifactVersion installed = version(target.modId());
        String shown = target.modId() + " " + (installed == null ? "?" : installed);

        if (!target.className().isEmpty()) {
            String missing = missingMembers(target);
            if (missing != null) {
                return new Decision(State.OFF_SHAPE, shown + " has no " + missing);
            }
        }

        String range = "jei".equals(group) ? jeiRange : target.versions();
        if (range == null || installed == null) {
            return new Decision(State.ACTIVE, shown);
        }
        Boolean inRange = inRange(installed, range);
        if (inRange == null) {
            // Unparseable range or version: the shape check already passed, so let it run.
            return new Decision(State.ACTIVE, shown);
        }
        if (inRange) {
            return new Decision(State.ACTIVE, shown);
        }
        if (target.hardRange() || strictVersions) {
            return new Decision(State.OFF_VERSION, shown + ", tested " + range);
        }
        return new Decision(State.ACTIVE_UNTESTED, shown + ", tested " + range);
    }

    /**
     * @return a description of the first member the fix needs and the installed class does not have,
     *         or null when everything is there (or the class could not be read, in which case the
     *         fix is left to Mixin, whose own failure mode is the one this check exists to avoid but
     *         which is still better than switching a fix off because a jar layout was unexpected).
     */
    private static String missingMembers(Target target) {
        Set<String>[] shape = shape(target.modId(), target.className());
        if (shape == null) {
            return null;
        }
        for (String field : target.fields()) {
            if (!shape[0].contains(field)) {
                return "field " + field;
            }
        }
        for (String method : target.methods()) {
            if (!shape[1].contains(method)) {
                return "method " + method;
            }
        }
        return null;
    }

    /** Field names and method names of a class, read straight out of the installed mod jar. */
    @SuppressWarnings("unchecked")
    private static Set<String>[] shape(String modId, String className) {
        String key = modId + "/" + className;
        synchronized (SHAPES) {
            if (SHAPES.containsKey(key)) {
                return SHAPES.get(key);
            }
        }
        Set<String>[] shape = readShape(modId, className);
        synchronized (SHAPES) {
            SHAPES.put(key, shape);
        }
        return shape;
    }

    @SuppressWarnings("unchecked")
    private static Set<String>[] readShape(String modId, String className) {
        try {
            ModFileInfo file = FMLLoader.getLoadingModList().getModFileById(modId);
            if (file == null) {
                return null;
            }
            Path path = file.getFile().findResource(className.replace('.', '/') + ".class");
            if (path == null || !Files.isRegularFile(path)) {
                return null;
            }
            Set<String> fields = new HashSet<>();
            Set<String> methods = new HashSet<>();
            ClassReader reader = new ClassReader(Files.readAllBytes(path));
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                    fields.add(name);
                    return null;
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    methods.add(name);
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return new Set[] {fields, methods};
        } catch (Throwable t) {
            LOGGER.debug("Nitro: could not read {} from {} to verify it: {}", className, modId, t.toString());
            return null;
        }
    }

    /** @return null when the range or the version cannot be understood. */
    private static Boolean inRange(ArtifactVersion version, String range) {
        try {
            return VersionRange.createFromVersionSpec(range).containsVersion(version);
        } catch (Throwable t) {
            return null;
        }
    }

    /** A mod that makes one of Nitro's optional fixes redundant, or null when there is none. */
    public static String conflictFor(String feature) {
        String other = CONFLICTS.get(feature);
        return other != null && present(other) ? other : null;
    }

    /** Records a decision made outside this class so it shows up in the startup table. */
    public static void record(String feature, State state, String detail) {
        DECISIONS.putIfAbsent(feature, new Decision(state, detail));
    }

    public static Map<String, Decision> decisions() {
        synchronized (DECISIONS) {
            return new LinkedHashMap<>(DECISIONS);
        }
    }

    private static void log(String group, Decision decision) {
        switch (decision.state()) {
            case OFF_SHAPE -> LOGGER.warn("Nitro: {} fix skipped, {}. The mod changed since this Nitro build; "
                + "the game is fine, that one fix is not applied.", group, decision.detail());
            case OFF_VERSION -> LOGGER.info("Nitro: {} fix skipped ({}). Set strictVersions=false in config/nitro.properties "
                + "to run it anyway.", group, decision.detail());
            case ACTIVE_UNTESTED -> LOGGER.info("Nitro: {} fix applied to an untested version ({}). "
                + "The class still has everything the fix needs.", group, decision.detail());
            default -> LOGGER.debug("Nitro: {} fix {} {}", group, decision.state().label, decision.detail());
        }
    }
}
