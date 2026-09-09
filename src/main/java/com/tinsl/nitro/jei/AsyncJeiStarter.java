package com.tinsl.nitro.jei;

import com.google.common.collect.ImmutableSetMultimap;
import com.tinsl.nitro.Nitro;
import com.tinsl.nitro.common.NitroSettings;
import com.tinsl.nitro.mixin.jei.JeiStarterAccessor;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.helpers.IColorHelper;
import mezz.jei.api.recipe.transfer.IRecipeTransferManager;
import mezz.jei.api.runtime.IScreenHelper;
import mezz.jei.api.search.ISearchStorageBuilderFactory;
import mezz.jei.common.Internal;
import mezz.jei.common.config.ConfigManager;
import mezz.jei.common.config.IIngredientFilterConfig;
import mezz.jei.common.config.JeiClientConfigs;
import mezz.jei.common.platform.Services;
import mezz.jei.common.recipes.VanillaClientRecipeLoader;
import mezz.jei.common.util.LoggedTimer;
import mezz.jei.common.util.RegistryUtil;
import mezz.jei.library.color.ColorHelper;
import mezz.jei.library.config.EditModeConfig;
import mezz.jei.library.focus.FocusFactory;
import mezz.jei.library.helpers.CodecHelper;
import mezz.jei.library.ingredients.IngredientManager;
import mezz.jei.library.ingredients.subtypes.SubtypeManager;
import mezz.jei.library.load.PluginCaller;
import mezz.jei.library.load.PluginLoader;
import mezz.jei.library.load.registration.RuntimeRegistration;
import mezz.jei.library.plugins.vanilla.VanillaPlugin;
import mezz.jei.library.recipes.RecipeManager;
import mezz.jei.library.runtime.JeiHelpers;
import mezz.jei.library.runtime.JeiRuntime;
import mezz.jei.library.startup.IStopCallback;
import mezz.jei.library.startup.JeiStarter;
import mezz.jei.library.startup.PluginAwareJeiFeatures;
import mezz.jei.library.startup.StartData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Re-implementation of {@link JeiStarter#start()} that keeps only the pieces that must touch
 * client state on the render thread, and moves the expensive, self-contained work
 * (recipe plugin registration, ingredient list and search index construction) to a
 * background thread.
 *
 * Measured on a 1017-mod pack: JEI's start took 3.5 minutes on the render thread, split into
 * "Registering recipes" (1.5 min) and "Building ingredient filter" (1.4 min). Those two phases
 * blocked rendering entirely, which is what the player experiences as "the game freezes for a
 * while after joining" and "opening the inventory hangs the first time".
 *
 * Thread-safety notes:
 * <ul>
 *   <li>Ingredient registration is kept on the render thread because it rebuilds creative tabs,
 *       which vanilla also does on the render thread.</li>
 *   <li>The search index calls item tooltip code off-thread; vanilla itself does the same for the
 *       creative search tree since 1.20.5 ({@code SessionSearchTrees} uses the background executor).</li>
 *   <li>JEI's own main-thread assertion is relaxed for the startup thread only (see ErrorUtilMixin).</li>
 *   <li>{@code registerRuntime} of third-party plugins, {@code onRuntimeAvailable} and the final
 *       hand-off happen on the render thread; only JEI's own GUI plugin (the heavy one) registers
 *       its runtime in the background.</li>
 *   <li>If the world changes while the build is in flight, the result is discarded.</li>
 *   <li>Any failure falls back to JEI's normal synchronous start.</li>
 * </ul>
 */
public final class AsyncJeiStarter {
    private static final String THREAD_NAME = "Nitro JEI Startup";
    private static final AtomicInteger GENERATION = new AtomicInteger();
    private static final ThreadLocal<Boolean> BYPASS = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static volatile ExecutorService executor;
    private static volatile Thread workerThread;
    private static boolean startupPending;

    private AsyncJeiStarter() {}

    /** True while the synchronous fallback is running, so the mixin lets the original method through. */
    public static boolean isBypassed() {
        return BYPASS.get();
    }

    /** True when called from the Nitro JEI startup thread. */
    public static boolean isJeiStartupThread() {
        return Thread.currentThread() == workerThread;
    }

    /** Called from the JeiStarter.stop() hook: invalidates any startup still in flight. */
    public static void onStop() {
        GENERATION.incrementAndGet();
    }

    /**
     * Entry point from the mixin. Returns normally after scheduling the background work;
     * mirrors JeiStarter.start() up to and including ingredient registration.
     */
    public static boolean start(JeiStarter starter) {
        // Serialize restarts as well as worker jobs: registration touches JEI global state.
        if (startupPending) {
            int requestedGeneration = GENERATION.get();
            executor().execute(() -> Minecraft.getInstance().execute(() -> {
                if (requestedGeneration == GENERATION.get()) start(starter);
            }));
            return true;
        }
        JeiStarterAccessor acc = (JeiStarterAccessor) (Object) starter;
        if (acc.nitro$isRunning()) {
            Nitro.LOGGER.error("Failed to start JEI, it is already running.");
            return true;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            Nitro.LOGGER.error("Failed to start JEI, there is no Minecraft client level.");
            return true;
        }
        // Nothing has been touched yet, so this is the last point at which handing the whole job
        // back to JEI costs nothing. The config allows a range of JEI versions rather than the one
        // this was written against, so check that the pieces this re-implementation reaches into
        // are all still there before committing to it.
        if (!jeiInternalsPresent(starter.getClass().getClassLoader())) {
            return false;
        }
        RegistryAccess registryAccess = level.registryAccess();
        RegistryUtil.setRegistryAccess(registryAccess);
        if (!Internal.hasClientRecipes()) {
            List<RecipeHolder<?>> vanillaRecipes = VanillaClientRecipeLoader.getVanillaRecipes(registryAccess);
            if (!vanillaRecipes.isEmpty()) {
                Internal.setClientFallbackRecipes(vanillaRecipes);
                level.getRecipeManager().replaceRecipes(vanillaRecipes);
            }
        }

        final int generation = GENERATION.incrementAndGet();
        final StartData data = acc.nitro$getData();
        final List<IModPlugin> plugins = acc.nitro$getPlugins();
        final VanillaPlugin vanillaPlugin = acc.nitro$getVanillaPlugin();
        final ConfigManager configManager = acc.nitro$getConfigManager();
        final JeiClientConfigs jeiClientConfigs = acc.nitro$getJeiClientConfigs();

        final LoggedTimer totalTime = new LoggedTimer();
        totalTime.start("Starting JEI (Nitro: async)");
        final long renderThreadStart = System.nanoTime();

        configManager.onJeiStarted();
        PluginCaller.callOnPlugins("Configuring JEI", plugins, p -> p.configureJei(new PluginAwareJeiFeatures(Internal.getJeiFeatures(), p)));

        final IColorHelper colorHelper = new ColorHelper(acc.nitro$getColorNameConfig());
        final IIngredientFilterConfig ingredientFilterConfig = jeiClientConfigs.getIngredientFilterConfig();
        final SubtypeManager subtypeManager = PluginLoader.registerSubtypes(data);
        final IngredientManager ingredientManager = PluginLoader.registerIngredients(data, subtypeManager, colorHelper, ingredientFilterConfig);

        final List<IStopCallback> localStops = new ArrayList<>();
        localStops.add(ingredientManager::onRuntimeStopped);

        // JEI's own plugins (jei:*) do the heavy runtime work (ingredient list + search index) and are
        // safe off-thread; third-party plugins get their registerRuntime on the render thread.
        final List<IModPlugin> jeiPlugins = new ArrayList<>();
        final List<IModPlugin> modPlugins = new ArrayList<>();
        for (IModPlugin p : plugins) {
            if ("jei".equals(p.getPluginUid().getNamespace())) {
                jeiPlugins.add(p);
            } else {
                modPlugins.add(p);
            }
        }

        double renderMs = (System.nanoTime() - renderThreadStart) / 1_000_000.0;
        Nitro.LOGGER.info("Nitro: JEI render-thread part done in {} ms, continuing on background thread", String.format("%.0f", renderMs));

        startupPending = true;
        executor().execute(() -> {
            try {
                if (generation != GENERATION.get()) {
                    minecraft.execute(() -> { discard(localStops, "cancelled before build"); startupPending = false; });
                    return;
                }
                FocusFactory focusFactory = new FocusFactory(ingredientManager);
                CodecHelper codecHelper = new CodecHelper(ingredientManager, focusFactory);
                Path configDir = Services.PLATFORM.getConfigHelper().createJeiConfigDir();
                EditModeConfig.FileSerializer editModeSerializer = new EditModeConfig.FileSerializer(
                    configDir.resolve("blacklist.json"), registryAccess, codecHelper);
                EditModeConfig editModeConfig = new EditModeConfig(editModeSerializer, ingredientManager);

                ImmutableSetMultimap<String, String> modAliases = PluginLoader.registerModAliases(data, ingredientFilterConfig);
                JeiHelpers jeiHelpers = PluginLoader.createJeiHelpers(
                    modAliases, acc.nitro$getModIdFormatConfig(), colorHelper, editModeConfig,
                    focusFactory, codecHelper, ingredientManager, subtypeManager);
                localStops.add(jeiHelpers::onRuntimeStopped);

                ISearchStorageBuilderFactory searchStorageBuilderFactory = PluginLoader.createSearchStorageFactory(plugins);
                RecipeManager recipeManager = PluginLoader.createRecipeManager(
                    plugins, vanillaPlugin, acc.nitro$getRecipeCategorySortingConfig(), jeiHelpers, ingredientManager);
                if (generation != GENERATION.get()) {
                    minecraft.execute(() -> { discard(localStops, "world changed during recipe registration"); startupPending = false; });
                    return;
                }
                IRecipeTransferManager recipeTransferManager = PluginLoader.createRecipeTransferManager(
                    vanillaPlugin, plugins, jeiHelpers, data.serverConnection());

                LoggedTimer timer = new LoggedTimer();
                timer.start("Building runtime");
                IScreenHelper screenHelper = PluginLoader.createGuiScreenHelper(plugins, jeiHelpers, ingredientManager);
                RuntimeRegistration runtimeRegistration = new RuntimeRegistration(
                    recipeManager, jeiHelpers, editModeConfig, ingredientManager,
                    recipeTransferManager, screenHelper, searchStorageBuilderFactory);
                PluginCaller.callOnPlugins("Registering Runtime (JEI)", jeiPlugins, p -> p.registerRuntime(runtimeRegistration));
                timer.stop();

                minecraft.execute(() -> finish(starter, acc, generation, modPlugins, plugins, runtimeRegistration,
                    recipeManager, ingredientManager, jeiHelpers, screenHelper, recipeTransferManager, editModeConfig,
                    configManager, localStops, totalTime));
            } catch (Throwable t) {
                // A LinkageError here means the installed JEI is not shaped the way this
                // re-implementation expects, which the fallback handles like any other failure.
                // Anything else at Error level (out of memory, stack overflow) is not ours to swallow.
                if (t instanceof Error && !(t instanceof LinkageError)) {
                    minecraft.execute(() -> {
                        startupPending = false;
                        discard(localStops, "unrecoverable class transformation or JVM error");
                        throw (Error) t;
                    });
                    return;
                }
                Nitro.LOGGER.error("Nitro: asynchronous JEI startup failed, falling back to JEI's normal startup", t);
                minecraft.execute(() -> fallback(starter, acc, generation, localStops));
            }
        });
        return true;
    }

    /**
     * JEI internals this class constructs directly. The public JEI API is stable across a minor
     * line; these are not, so they are what decides whether the async path is safe to take on the
     * installed build. A missing one means Nitro hands the startup back to JEI untouched, which
     * costs the player the freeze but never a crash.
     */
    private static final String[] REQUIRED_JEI_CLASSES = {
        "mezz.jei.library.transfer.BasicRecipeTransferHandler",
        "mezz.jei.library.startup.StartData",
        "mezz.jei.library.startup.PluginAwareJeiFeatures",
        "mezz.jei.library.load.PluginLoader",
        "mezz.jei.library.load.PluginCaller",
        "mezz.jei.library.load.registration.RuntimeRegistration",
        "mezz.jei.library.config.EditModeConfig",
        "mezz.jei.library.helpers.CodecHelper",
        "mezz.jei.library.focus.FocusFactory",
        "mezz.jei.library.color.ColorHelper",
        "mezz.jei.library.ingredients.IngredientManager",
        "mezz.jei.library.ingredients.subtypes.SubtypeManager",
        "mezz.jei.library.recipes.RecipeManager",
        "mezz.jei.library.runtime.JeiHelpers",
        "mezz.jei.library.runtime.JeiRuntime",
        "mezz.jei.library.plugins.vanilla.VanillaPlugin",
        "mezz.jei.common.Internal",
        "mezz.jei.common.recipes.VanillaClientRecipeLoader",
        "mezz.jei.common.util.RegistryUtil",
        "mezz.jei.common.platform.Services",
        "mezz.jei.api.search.ISearchStorageBuilderFactory"
    };

    private static Boolean internalsPresent;

    private static boolean jeiInternalsPresent(ClassLoader loader) {
        Boolean known = internalsPresent;
        if (known != null) {
            return known;
        }
        for (String name : REQUIRED_JEI_CLASSES) {
            try {
                Class.forName(name, false, loader);
            } catch (Throwable t) {
                Nitro.LOGGER.warn("Nitro: the installed JEI does not have {}, so its startup is left alone. "
                    + "Nitro's async JEI startup targets the version range in jeiVersions "
                    + "(config/nitro.properties); this build is outside what it fits.", name);
                internalsPresent = Boolean.FALSE;
                return false;
            }
        }
        internalsPresent = Boolean.TRUE;
        return true;
    }

    private static void finish(JeiStarter starter, JeiStarterAccessor acc, int generation,
                               List<IModPlugin> modPlugins, List<IModPlugin> allPlugins,
                               RuntimeRegistration runtimeRegistration, RecipeManager recipeManager,
                               IngredientManager ingredientManager, JeiHelpers jeiHelpers, IScreenHelper screenHelper,
                               IRecipeTransferManager recipeTransferManager, EditModeConfig editModeConfig,
                               ConfigManager configManager, List<IStopCallback> localStops, LoggedTimer totalTime) {
        startupPending = false;
        if (generation != GENERATION.get() || acc.nitro$isRunning()) {
            discard(localStops, "world changed before hand-off");
            return;
        }
        try {
            PluginCaller.callOnPlugins("Registering Runtime (mods)", modPlugins, p -> p.registerRuntime(runtimeRegistration));
            JeiRuntime jeiRuntime = new JeiRuntime(
                recipeManager, ingredientManager, Internal.getKeyMappings(), jeiHelpers, screenHelper,
                recipeTransferManager, editModeConfig,
                runtimeRegistration.getIngredientListOverlay(),
                runtimeRegistration.getBookmarkOverlay(),
                runtimeRegistration.getBookmarkManager(),
                runtimeRegistration.getRecipesGui(),
                runtimeRegistration.getIngredientFilter(),
                configManager);
            PluginCaller.callOnPlugins("Sending Runtime", allPlugins, p -> p.onRuntimeAvailable(jeiRuntime));
            acc.nitro$getStopCallbacks().addAll(localStops);
            Internal.setRuntime(jeiRuntime);
            acc.nitro$setRunning(true);
            totalTime.stop();
            acc.nitro$verifyClientRecipes(Minecraft.getInstance());
        } catch (Exception t) {
            Nitro.LOGGER.error("Nitro: JEI runtime hand-off failed, falling back to JEI's normal startup", t);
            fallback(starter, acc, generation, localStops);
        }
    }

    private static void fallback(JeiStarter starter, JeiStarterAccessor acc, int generation, List<IStopCallback> localStops) {
        startupPending = false;
        discard(localStops, "fallback");
        if (generation != GENERATION.get() || acc.nitro$isRunning() || Minecraft.getInstance().level == null) {
            return;
        }
        BYPASS.set(Boolean.TRUE);
        try {
            starter.start();
        } finally {
            BYPASS.set(Boolean.FALSE);
        }
    }

    private static void discard(List<IStopCallback> localStops, String reason) {
        Nitro.LOGGER.info("Nitro: discarding partially built JEI runtime ({})", reason);
        for (IStopCallback callback : localStops) {
            try {
                callback.onRuntimeStopped();
            } catch (Throwable t) {
                Nitro.LOGGER.warn("Nitro: error while discarding JEI runtime", t);
            }
        }
        localStops.clear();
    }

    private static ExecutorService executor() {
        ExecutorService e = executor;
        if (e == null) {
            synchronized (AsyncJeiStarter.class) {
                e = executor;
                if (e == null) {
                    int priority = NitroSettings.load(FMLPaths.GAMEDIR.get()).asyncJeiPriority;
                    e = Executors.newSingleThreadExecutor(r -> {
                        Thread t = new Thread(r, THREAD_NAME);
                        t.setDaemon(true);
                        t.setPriority(priority);
                        workerThread = t;
                        return t;
                    });
                    executor = e;
                }
            }
        }
        return e;
    }
}
