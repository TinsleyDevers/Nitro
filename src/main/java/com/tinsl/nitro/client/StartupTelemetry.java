package com.tinsl.nitro.client;

import com.tinsl.nitro.Nitro;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.lang.management.ManagementFactory;

/**
 * Logs how long the phases the player actually feels take: time to main menu, time from
 * leaving the world/server selection until the player is in the level. Pure logging.
 */
public final class StartupTelemetry {
    private static boolean menuReported;
    private static boolean worldSelectionReported;
    private static long worldLoadStartNanos;
    private static Runnable onMainMenu;

    private StartupTelemetry() {}

    public static void beginWorldLoad() {
        worldLoadStartNanos = System.nanoTime();
        Nitro.LOGGER.info("Nitro report: world connection requested");
    }

    /** @param atMainMenu run once, the first time the title screen opens */
    public static void register(Runnable atMainMenu) {
        onMainMenu = atMainMenu;
        NeoForge.EVENT_BUS.addListener(StartupTelemetry::onScreenOpening);
        NeoForge.EVENT_BUS.addListener(StartupTelemetry::onClientTick);
        NeoForge.EVENT_BUS.addListener(StartupTelemetry::onLoggingIn);
        NeoForge.EVENT_BUS.addListener(StartupTelemetry::onLoggingOut);
    }

    private static void onScreenOpening(ScreenEvent.Opening event) {
        Screen next = event.getNewScreen();
        if (Minecraft.getInstance().level != null) {
            return;
        }
        boolean stayingInMenus = next instanceof TitleScreen || next instanceof SelectWorldScreen || next instanceof JoinMultiplayerScreen;
        if (stayingInMenus) worldLoadStartNanos = 0L;
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        if (menuReported && worldSelectionReported) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.getOverlay() != null || client.level != null || client.screen == null) {
            return;
        }
        if (!menuReported && worldLoadStartNanos == 0L) {
            menuReported = true;
            if (onMainMenu != null) {
                long uptime = ManagementFactory.getRuntimeMXBean().getUptime();
                Nitro.LOGGER.info("Nitro report: startup overlay cleared at {} ms after JVM start", uptime);
                Thread.ofVirtual().name("Nitro startup report").start(onMainMenu);
            }
        }
        if (!worldSelectionReported && client.screen instanceof SelectWorldScreen) {
            worldSelectionReported = true;
            long uptime = ManagementFactory.getRuntimeMXBean().getUptime();
            if (onMainMenu != null) {
                Nitro.LOGGER.info("Nitro report: world selection reached at {} ms after JVM start", uptime);
            }
        }
    }

    private static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        if (!menuReported) {
            // Launched straight into a world or a server (quick play, join on launch): the title
            // screen never opened, so this is the first point the report can be printed.
            menuReported = true;
            long uptime = ManagementFactory.getRuntimeMXBean().getUptime();
            Nitro.LOGGER.info("Nitro report: in the world {} s after JVM start, without stopping at the main menu",
                String.format("%.1f", uptime / 1000.0));
            if (onMainMenu != null) {
                onMainMenu.run();
            }
        }
        if (worldLoadStartNanos != 0L) {
            double seconds = (System.nanoTime() - worldLoadStartNanos) / 1_000_000_000.0;
            Nitro.LOGGER.info("Nitro report: player logged in {} s after world load started", String.format("%.1f", seconds));
            worldLoadStartNanos = 0L;
        }
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        worldLoadStartNanos = 0L;
    }
}
