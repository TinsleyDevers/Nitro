package com.tinsl.nitro;

import com.tinsl.nitro.client.StartupTelemetry;
import com.tinsl.nitro.common.NitroSettings;
import com.tinsl.nitro.compat.NitroReport;
import com.tinsl.zambielib.util.Mods;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.management.ManagementFactory;

/**
 * Nitro - performance fixes for very high mod counts.
 *
 * <ul>
 *   <li>Launch: hot-patches the JDK 21 module resolver (see {@code com.tinsl.nitro.launch}).</li>
 *   <li>World join: JEI's startup runs on a background thread (see {@code com.tinsl.nitro.jei}).</li>
 *   <li>Data pack load: recipe JSON is decoded in parallel (see {@code com.tinsl.nitro.mixin.RecipeManagerMixin}).</li>
 * </ul>
 */
@Mod(Nitro.MOD_ID)
public final class Nitro {
    public static final String MOD_ID = "nitro";
    public static final Logger LOGGER = LogManager.getLogger("Nitro");

    public Nitro(IEventBus modBus, ModContainer container) {
        NitroSettings settings = NitroSettings.load(FMLPaths.GAMEDIR.get());
        long uptime = ManagementFactory.getRuntimeMXBean().getUptime();
        LOGGER.info("Nitro {} constructed at JVM uptime {} s with ZambieLib {} (resolverPatch={}, asyncJei={}, parallelRecipes={})",
            container.getModInfo().getVersion(), uptime / 1000, Mods.version("zambielib"), resolverStatus(), settings.asyncJei, settings.parallelRecipes);
        String version = container.getModInfo().getVersion().toString();
        boolean client = FMLEnvironment.dist.isClient();
        if (client) {
            com.tinsl.nitro.client.AgentNotice.register(settings);
        }
        if (client && settings.startupReport) {
            // On a client the report waits for the main menu: the resource reload is still running
            // when loading "completes", and its numbers are half the point of the report.
            StartupTelemetry.register(() -> report(settings, version));
        }
        modBus.addListener(net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent.class, event -> {
            markLoadComplete();
            if (!client && settings.startupReport) {
                report(settings, version);
            }
        });
    }

    private static void report(NitroSettings settings, String version) {
        try {
            NitroReport.print(settings, version);
        } catch (Throwable t) {
            LOGGER.warn("Nitro: could not print the startup report", t);
        }
    }

    /**
     * Tells the launcher-side class transform cache that this launch reached a working state. The
     * hook class lives on the bootstrap class path (it is installed by the JVM agent), so it is
     * looked up by name; when the agent is not loaded there is nothing to tell.
     */
    private static void markLoadComplete() {
        try {
            Class<?> hooks = Class.forName("nitro.agent.boot.NitroHooks", false, null);
            hooks.getMethod("classCacheMarkClean").invoke(null);
        } catch (ClassNotFoundException ignored) {
            // agent not loaded: no cache to update
        } catch (ReflectiveOperationException e) {
            LOGGER.debug("Nitro: could not report load completion to the class cache: {}", e.toString());
        }
    }

    private static String resolverStatus() {
        if (Boolean.getBoolean("nitro.resolverPatched")) {
            return "applied";
        }
        String err = System.getProperty("nitro.resolverPatchError");
        return err == null ? "not-applied" : "failed: " + err;
    }
}
