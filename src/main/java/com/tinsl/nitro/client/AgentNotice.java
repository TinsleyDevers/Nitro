package com.tinsl.nitro.client;

import com.tinsl.nitro.Nitro;
import cpw.mods.modlauncher.Launcher;
import cpw.mods.modlauncher.api.IModuleLayerManager;
import com.tinsl.nitro.common.NitroSettings;
import com.tinsl.zambielib.client.GuiKit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Offers agent setup when the early startup agent was unavailable. */
public final class AgentNotice {
    private static final String JAVA_25_DOWNLOAD = "https://adoptium.net/temurin/releases/?version=25";
    private static boolean shown;

    private AgentNotice() {}

    public static void register(NitroSettings settings) {
        if (!settings.agentNotice) {
            return;
        }
        boolean canEnable = !settings.autoAttachAgent
            && settings.overriddenKeys.stream().noneMatch(key -> key.startsWith("autoAttachAgent="));
        // Lowest priority so the startup timing sees the real title screen before this swaps it out.
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, (ScreenEvent.Opening event) -> {
            if (shown || !(event.getNewScreen() instanceof TitleScreen title)) {
                return;
            }
            shown = true;
            String argument = System.getProperty("nitro.agentArgument");
            if (argument == null || Boolean.getBoolean("nitro.agentLoaded")) {
                return;
            }
            String launcher = System.getProperty("nitro.agentLauncher", "your launcher");
            String steps = System.getProperty("nitro.agentSteps", "Add it to this instance's JVM arguments and restart.");
            event.setNewScreen(new NoticeScreen(title, argument, launcher, steps, System.getProperty("nitro.javaSteps"),
                System.getProperty("nitro.java25"), pauseSeconds(), canEnable));
        });
    }

    /** How long this launch's module layer took, from Nitro's two marks around it; -1 when unknown. */
    private static long pauseSeconds() {
        try {
            return Long.parseLong(System.getProperty("nitro.layerPauseMillis", "-1000")) / 1000L;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static final class NoticeScreen extends Screen {
        private final Screen parent;
        private final String argument;
        private final String java25;
        private final boolean javaTab;
        private final boolean automaticJavaAvailable;
        private boolean manualJava;
        private boolean javaSetupScheduled;
        private final boolean canEnable;
        private boolean enabledForNextLaunch;
        private final List<Component> javaPage = new ArrayList<>();
        private final List<Component> manualJavaPage = new ArrayList<>();
        private final List<Component> argumentPage = new ArrayList<>();
        /** Index into the page of the line shown in a dark box (the path or the argument), or -1. */
        private int javaBoxLine = -1;
        private int argumentBoxLine = -1;
        private boolean onJavaTab;
        private Component status = Component.empty();
        private int panelWidth;
        private int panelHeight;
        private final List<Integer> lineHeights = new ArrayList<>();

        /**
         * @param javaSteps where to switch this instance to Java 25 in the detected launcher, or null when
         *                  that is not an option (a launcher that picks Java itself)
         * @param java25    a Java 25 program found on this computer, or null
         * @param pause     seconds this launch spent building the module layer, or negative when unknown
         */
        NoticeScreen(Screen parent, String argument, String launcher, String steps, String javaSteps, String java25, long pause, boolean canEnable) {
            super(Component.literal("Nitro: this pack can start much faster"));
            this.parent = parent;
            this.argument = argument;
            this.java25 = java25;
            this.javaTab = javaSteps != null;
            this.automaticJavaAvailable = "Prism Launcher".equals(launcher) && java25 != null;
            this.canEnable = canEnable;
            this.onJavaTab = javaTab && !canEnable;
            // Capitalised for the start of a sentence ("the CurseForge app" -> "The CurseForge app").
            String who = launcher.isEmpty() ? launcher : Character.toUpperCase(launcher.charAt(0)) + launcher.substring(1);
            if (javaTab) {
                javaPage.add(Component.literal(pause >= 10
                    ? "Java " + Runtime.version().feature() + " spent " + pause + " s linking mods. Java 25 includes the faster resolver."
                    : "Java 25 includes a faster resolver for large modpacks."));
                javaPage.add(Component.literal("How would you like to set up Java 25?"));
                if (!automaticJavaAvailable) {
                    javaPage.add(Component.literal(!"Prism Launcher".equals(launcher)
                        ? "This launcher uses manual setup."
                        : "Automatic setup needs an installed Java 25."));
                }
                manualJavaPage.add(Component.literal(who + ": " + javaSteps));
                if (java25 != null) {
                    javaBoxLine = manualJavaPage.size();
                    manualJavaPage.add(Component.literal(java25));
                }
            }
            if (canEnable) {
                argumentPage.add(Component.literal("Automatic attach is off for this instance. Enable it here, "
                    + "then restart Minecraft to apply Nitro's early launch patches."));
            } else {
                argumentPage.add(Component.literal("Nitro could not attach automatically. To load its agent at startup, "
                    + "add this line to the JVM arguments and restart Minecraft."));
                argumentBoxLine = argumentPage.size();
                argumentPage.add(Component.literal(argument));
                argumentPage.add(Component.literal(who + ": " + steps));
            }
        }

        private List<Component> page() {
            return !onJavaTab ? argumentPage : manualJava ? manualJavaPage : javaPage;
        }

        private int boxLine() {
            return !onJavaTab ? argumentBoxLine : manualJava ? javaBoxLine : -1;
        }

        private int panelX() {
            return (width - panelWidth) / 2;
        }

        private int panelY() {
            return Math.max(4, (height - panelHeight) / 2);
        }

        /** The text width of a line; lines in a box are inset a little further. */
        private int textWidth(int line) {
            return line == boxLine() ? panelWidth - 28 : panelWidth - 20;
        }

        @Override
        protected void init() {
            panelWidth = Math.min(360, width - 16);
            lineHeights.clear();
            int contentHeight = 0;
            List<Component> page = page();
            for (int i = 0; i < page.size(); i++) {
                int h = GuiKit.drawWrapped(null, font, page.get(i), 0, 0, textWidth(i), 0, false) + (i == boxLine() ? 8 : 0);
                lineHeights.add(h);
                contentHeight += h + 6;
            }
            int top = javaTab ? 46 : 22;
            int buttonsTop = top + contentHeight + 2;
            panelHeight = buttonsTop + 20 + 4 + 20 + 10;

            int x = panelX() + 10;
            int inner = panelWidth - 20;
            if (javaTab) {
                int half = (inner - 5) / 2;
                int tabY = panelY() + 20;
                Button java = Button.builder(Component.literal("Switch to Java 25"), b -> showTab(true))
                    .bounds(x, tabY, half, 20).build();
                Button keep = Button.builder(Component.literal("Keep Java " + Runtime.version().feature()), b -> showTab(false))
                    .bounds(x + half + 5, tabY, inner - half - 5, 20).build();
                java.active = !onJavaTab;
                keep.active = onJavaTab;
                addRenderableWidget(java);
                addRenderableWidget(keep);
            }

            int y = panelY() + buttonsTop;
            if (onJavaTab && !manualJava) {
                int half = (inner - 5) / 2;
                Button automatic = Button.builder(Component.literal("Set up automatically"), b -> scheduleJavaSetup())
                    .bounds(x, y, half, 20).build();
                automatic.active = automaticJavaAvailable && !javaSetupScheduled;
                addRenderableWidget(automatic);
                addRenderableWidget(Button.builder(Component.literal("Set up manually"), b -> {
                    manualJava = true;
                    status = Component.empty();
                    rebuildWidgets();
                }).bounds(x + half + 5, y, inner - half - 5, 20).build());
            } else if (onJavaTab) {
                if (java25 != null) {
                    addRenderableWidget(Button.builder(Component.literal("Copy Java 25 path"), b -> {
                        Minecraft.getInstance().keyboardHandler.setClipboard(java25);
                        status = Component.literal("Copied. Paste it as the Java path, then restart the game.");
                    }).bounds(x, y, inner, 20).build());
                } else {
                    addRenderableWidget(Button.builder(Component.literal("Get Java 25 (opens your browser)"),
                        b -> ConfirmLinkScreen.confirmLinkNow(this, JAVA_25_DOWNLOAD)).bounds(x, y, inner, 20).build());
                }
            } else if (canEnable) {
                Button enable = Button.builder(Component.literal("Enable Nitro agent on next launch"),
                    this::enableAutoAttach).bounds(x, y, inner, 20).build();
                enable.active = !enabledForNextLaunch;
                addRenderableWidget(enable);
            } else {
                addRenderableWidget(Button.builder(Component.literal("Copy argument"), b -> {
                    Minecraft.getInstance().keyboardHandler.setClipboard(argument);
                    status = Component.literal("Copied. Paste it where described above, then restart.");
                }).bounds(x, y, inner, 20).build());
            }

            int w = (inner - 5) / 2;
            int bottom = y + 24;
            addRenderableWidget(Button.builder(Component.literal(onJavaTab && manualJava ? "Back" : "Later"), b -> {
                if (onJavaTab && manualJava) {
                    showTab(true);
                } else {
                    onClose();
                }
            }).bounds(x, bottom, w, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Don't show again"), b -> {
                try {
                    NitroSettings.save(FMLPaths.GAMEDIR.get(), "agentNotice", "false");
                } catch (Exception e) {
                    Nitro.LOGGER.warn("Nitro: could not save agentNotice=false", e);
                }
                onClose();
            }).bounds(x + w + 5, bottom, inner - w - 5, 20).build());
        }

        private void enableAutoAttach(Button button) {
            try {
                NitroSettings.save(FMLPaths.GAMEDIR.get(), "autoAttachAgent", "true");
                enabledForNextLaunch = true;
                button.active = false;
                status = Component.literal("Enabled. Restart Minecraft to apply it.");
            } catch (java.io.IOException e) {
                Nitro.LOGGER.warn("Nitro: could not save automatic attach", e);
                status = Component.literal("Could not save the setting. See latest.log.");
            }
        }

        private void scheduleJavaSetup() {
            try {
                var manager = Launcher.INSTANCE.findLayerManager().orElseThrow();
                var layer = manager.getLayer(IModuleLayerManager.Layer.SERVICE).orElseThrow();
                var module = layer.findModule("nitro.launch").orElseThrow();
                Class<?> setup = Class.forName(module, "com.tinsl.nitro.launch.JavaSetupLauncher");
                if (setup == null) {
                    throw new ClassNotFoundException("Nitro Java setup");
                }
                setup.getMethod("schedule", Path.class).invoke(null, FMLPaths.GAMEDIR.get());
                javaSetupScheduled = true;
                javaPage.add(Component.literal("Setup queued. Quit Minecraft and Prism completely. "
                    + "Wait a moment, then reopen Prism and launch this instance."));
                rebuildWidgets();
            } catch (ReflectiveOperationException | RuntimeException exception) {
                Nitro.LOGGER.warn("Nitro: could not start automatic Java setup", exception);
                status = Component.literal("Could not queue setup. See latest.log.");
            }
        }

        private void showTab(boolean java) {
            manualJava = false;
            onJavaTab = java;
            status = Component.empty();
            rebuildWidgets();
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            // Background and widgets first; the 1.21 background blur would otherwise cover the panel.
            super.render(g, mouseX, mouseY, partialTick);
            int x = panelX();
            int y = panelY();
            GuiKit.drawRaisedPanel(g, x, y, panelWidth, panelHeight);
            g.drawString(font, title, x + 10, y + 8, GuiKit.TITLE_GRAY, false);
            int lineY = y + (javaTab ? 46 : 22);
            List<Component> page = page();
            for (int i = 0; i < page.size(); i++) {
                if (i == boxLine()) {
                    GuiKit.drawInset(g, x + 10, lineY, panelWidth - 20, lineHeights.get(i));
                    GuiKit.drawWrapped(g, font, page.get(i), x + 14, lineY + 4, textWidth(i), GuiKit.WHITE, true);
                } else {
                    GuiKit.drawWrapped(g, font, page.get(i), x + 10, lineY, textWidth(i), GuiKit.DARK_GRAY, true);
                }
                lineY += lineHeights.get(i) + 6;
            }
            // The widgets were drawn under the panel by super.render; draw them again on top of it.
            for (var child : children()) {
                if (child instanceof Button button) {
                    button.render(g, mouseX, mouseY, partialTick);
                }
            }
            if (!status.getString().isEmpty()) {
                g.drawCenteredString(font, status, width / 2, Math.min(height - 10, y + panelHeight + 6), GuiKit.YELLOW);
            }
        }

        @Override
        public void onClose() {
            minecraft.setScreen(parent);
        }
    }
}
