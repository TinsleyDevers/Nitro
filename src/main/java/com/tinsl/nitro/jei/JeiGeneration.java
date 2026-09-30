package com.tinsl.nitro.jei;

import com.google.common.collect.ImmutableSetMultimap;
import mezz.jei.api.helpers.IColorHelper;
import mezz.jei.library.ingredients.IngredientManager;
import mezz.jei.library.ingredients.subtypes.SubtypeManager;
import mezz.jei.library.load.PluginLoader;
import mezz.jei.library.recipes.RecipeManager;
import mezz.jei.library.runtime.JeiRuntime;
import mezz.jei.library.startup.IStopCallback;
import mezz.jei.library.startup.JeiStarter;
import mezz.jei.library.startup.StartData;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * The few places where {@link AsyncJeiStarter} has to call JEI differently depending on which JEI it
 * is running against.
 *
 * Between 19.53 and 19.57 JEI reworked its config system, and not all at once: in 19.55
 * {@code JeiStarter} stopped owning a {@code ConfigManager} (the configs now come in through
 * {@code StartData} and plugins get an {@code IJeiConfigManager} adapter), and in a later build two
 * {@code PluginLoader} calls lost their ingredient filter config argument and the recipe manager gained
 * a stop hook. Each of those is therefore probed on its own rather than inferred from a version
 * number. Everything else the async startup does is the same code across the whole range.
 *
 * Nitro compiles against the newest of these, so the newest calls are plain Java and the older ones go
 * through reflection. All of it is resolved once, when the first world is joined; a JEI that fits
 * none of the known shapes makes {@link #detect} fail, and Nitro then leaves JEI's startup alone.
 */
final class JeiGeneration {
    /** True when JeiStarter hands plugins the 19.55+ config manager adapter. */
    final boolean modernConfig;
    private final Field configManagerField;
    private final Field clientConfigsField;
    private final Method onJeiStarted;
    private final Method ingredientFilterConfig;
    private final Method registerIngredientsLegacy;
    private final Method registerModAliasesLegacy;
    private final Method recipeManagerStopped;
    private final Constructor<?> runtimeConstructor;

    private JeiGeneration(boolean modernConfig, Field configManagerField, Field clientConfigsField, Method onJeiStarted,
                          Method ingredientFilterConfig, Method registerIngredientsLegacy, Method registerModAliasesLegacy,
                          Method recipeManagerStopped, Constructor<?> runtimeConstructor) {
        this.modernConfig = modernConfig;
        this.configManagerField = configManagerField;
        this.clientConfigsField = clientConfigsField;
        this.onJeiStarted = onJeiStarted;
        this.ingredientFilterConfig = ingredientFilterConfig;
        this.registerIngredientsLegacy = registerIngredientsLegacy;
        this.registerModAliasesLegacy = registerModAliasesLegacy;
        this.recipeManagerStopped = recipeManagerStopped;
        this.runtimeConstructor = runtimeConstructor;
    }

    /** @throws ReflectiveOperationException when the installed JEI fits none of the known shapes */
    static JeiGeneration detect() throws ReflectiveOperationException {
        Class<?> starter = JeiStarter.class;
        Field clientConfigs = accessible(starter.getDeclaredField("jeiClientConfigs"));
        Field modernManager = field(starter, "configManagerForPlugins");
        Field manager = accessible(modernManager != null ? modernManager : starter.getDeclaredField("configManager"));
        Method started = method(manager.getType(), "onJeiStarted");

        Method ingredientsLegacy = null;
        Method aliasesLegacy = null;
        Method filter = null;
        boolean modernIngredients = method(PluginLoader.class, "registerIngredients",
            StartData.class, SubtypeManager.class, IColorHelper.class) != null;
        boolean modernAliases = method(PluginLoader.class, "registerModAliases", StartData.class) != null;
        if (!modernIngredients || !modernAliases) {
            Class<?> filterConfig = Class.forName("mezz.jei.common.config.IIngredientFilterConfig", false, starter.getClassLoader());
            filter = clientConfigs.getType().getMethod("getIngredientFilterConfig");
            if (!modernIngredients) {
                ingredientsLegacy = PluginLoader.class.getMethod("registerIngredients",
                    StartData.class, SubtypeManager.class, IColorHelper.class, filterConfig);
            }
            if (!modernAliases) {
                aliasesLegacy = PluginLoader.class.getMethod("registerModAliases", StartData.class, filterConfig);
            }
        }
        return new JeiGeneration(modernManager != null, manager, clientConfigs, started, filter,
            ingredientsLegacy, aliasesLegacy, method(RecipeManager.class, "onRuntimeStopped"), runtimeConstructor());
    }

    private static Field field(Class<?> owner, String name) {
        try {
            return owner.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    private static Method method(Class<?> owner, String name, Class<?>... params) {
        try {
            return owner.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static Field accessible(Field f) {
        f.setAccessible(true);
        return f;
    }

    /** JeiRuntime has one constructor across the range; only its last parameter's type differs. */
    private static Constructor<?> runtimeConstructor() throws NoSuchMethodException {
        Constructor<?>[] all = JeiRuntime.class.getConstructors();
        if (all.length != 1) {
            throw new NoSuchMethodException("JeiRuntime has " + all.length + " public constructors, expected 1");
        }
        return all[0];
    }

    /** The config manager object handed to plugins and to the runtime (whichever type this JEI uses). */
    Object configManager(JeiStarter starter) throws IllegalAccessException {
        return configManagerField.get(starter);
    }

    Object clientConfigs(JeiStarter starter) throws IllegalAccessException {
        return clientConfigsField.get(starter);
    }

    /** Before 19.55 JEI tells its config manager that it started; later ones have nothing to tell. */
    void onJeiStarted(Object configManager) throws ReflectiveOperationException {
        if (onJeiStarted != null) {
            onJeiStarted.invoke(configManager);
        }
    }

    IngredientManager registerIngredients(StartData data, SubtypeManager subtypes, IColorHelper colors, Object clientConfigs)
        throws ReflectiveOperationException {
        if (registerIngredientsLegacy == null) {
            return PluginLoader.registerIngredients(data, subtypes, colors);
        }
        return (IngredientManager) registerIngredientsLegacy.invoke(null, data, subtypes, colors, ingredientFilterConfig.invoke(clientConfigs));
    }

    @SuppressWarnings("unchecked")
    ImmutableSetMultimap<String, String> registerModAliases(StartData data, Object clientConfigs) throws ReflectiveOperationException {
        if (registerModAliasesLegacy == null) {
            return PluginLoader.registerModAliases(data);
        }
        return (ImmutableSetMultimap<String, String>) registerModAliasesLegacy.invoke(null, data, ingredientFilterConfig.invoke(clientConfigs));
    }

    /** JEI stops the recipe manager with the runtime in the builds where it has a stop hook; mirror that. */
    IStopCallback recipeManagerStop(RecipeManager recipeManager) {
        if (recipeManagerStopped == null) {
            return null;
        }
        return () -> {
            try {
                recipeManagerStopped.invoke(recipeManager);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        };
    }

    JeiRuntime newRuntime(Object... args) throws ReflectiveOperationException {
        return (JeiRuntime) runtimeConstructor.newInstance(args);
    }

    /** One line for the log: which of the shapes this JEI has. */
    String describe() {
        return (modernConfig ? "config adapter" : "config manager")
            + (registerIngredientsLegacy == null ? ", new loader calls" : ", filter-config loader calls")
            + (recipeManagerStopped != null ? ", recipe manager stop hook" : "");
    }
}
