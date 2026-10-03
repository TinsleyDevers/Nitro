package com.tinsl.nitro.client;

import com.tinsl.nitro.Nitro;
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

import java.util.ArrayList;
import java.util.List;

/**
 * Tells the player, once, on the main menu, how to get rid of the long pause before mods load.
 *
 * On Java 21 that pause is Java's module resolver, and there are two fixes: run the instance on
 * Java 25, which has the resolver fix built in and needs nothing pasted anywhere, or keep Java 21
 * and add Nitro's agent to the JVM arguments. Java 25 measured faster (101 s to the menu on a 1017
 * mod pack, against 134 s with the agent and 273 s with neither), so it is the first tab. When Nitro
 * found a Java 25 already on the computer, the screen shows its path with a copy button, which turns
 * "download Java and work out where it went" into one paste.
 *
 * Most players never open a log, so a log line alone would leave them wondering why nothing got faster.
 */
public final class AgentNotice {
    private static final String JAVA_25_DOWNLOAD = "https://adoptium.net/temurin/releases/?version=25";
    private static boolean shown;

    private AgentNotice() {}

    public static void register(NitroSettings settings) {
        // On Java 24+ the module resolver fix is built in, and the agent's remaining loader patches
        // measured within a second of nothing on a 1017 mod pack (100 s to the menu with it, 101 s
        // without). Not worth a screen asking the player to change anything.
        if (!settings.agentNotice || Runtime.version().feature() >= 24) {
            return;
        }
        // Lowest priority so the startup timing sees the real title screen before this swaps it out.
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, (ScreenEvent.Opening event) -> {
            if (shown || !(event.getNewScreen() instanceof TitleScreen title)) {
                return;
            }
            shown = true;
            String argument = System.getProperty("nitro.agentArgument");
            // Only when the agent is absent. When it is present but failed, the log says why, and this
            // screen's advice (add the argument) would be wrong.
            if (argument == null || Boolean.getBoolean("nitro.agentLoaded") || System.getProperty("nitro.resolverPatchError") != null) {
                return;
            }
            String launcher = System.getProperty("nitro.agentLauncher", "your launcher");
            String steps = System.getProperty("nitro.agentSteps", "Add it to this instance's JVM arguments and restart.");
            event.setNewScreen(new NoticeScreen(title, argument, launcher, steps, System.getProperty("nitro.javaSteps"),
                System.getProperty("nitro.java25"), pauseSeconds()));
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
        private final List<Component> javaPage = new ArrayList<>();
        private final List<Component> argumentPage = new ArrayList<>();
        /** Index into the page of the line shown in a dark box (the path or the argument), or -1. */
        private int javaBoxLine = -1;
        private int argumentBoxLine;
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
        NoticeScreen(Screen parent, String argument, String launcher, String steps, String javaSteps, String java25, long pause) {
            super(Component.literal("Nitro: this pack can start much faster"));
            this.parent = parent;
            this.argument = argument;
            this.java25 = java25;
            this.javaTab = javaSteps != null;
            this.onJavaTab = javaTab;
            // Capitalised for the start of a sentence ("the CurseForge app" -> "The CurseForge app").
            String who = launcher.isEmpty() ? launcher : Character.toUpperCase(launcher.charAt(0)) + launcher.substring(1);
            int java = Runtime.version().feature();
            String waited = pause >= 10
                ? "This launch spent " + pause + " s waiting on Java " + java + " before any mod loaded."
                : "Before any mod loads, Java " + java + " pauses for a long time on a pack this size.";

            if (javaTab) {
                javaPage.add(Component.literal(waited + " Java 25 has that fixed, so running this instance on it "
                    + "removes the wait with nothing to paste. This is the fastest option."));
                javaPage.add(Component.literal(who + ": " + javaSteps));
                if (java25 != null) {
                    javaPage.add(Component.literal("Java 25 is already on this computer. Pick it in the list, or use this path:"));
                    javaBoxLine = javaPage.size();
                    javaPage.add(Component.literal(java25));
                } else {
                    javaPage.add(Component.literal("No Java 25 was found on this computer. Your launcher may be able to "
                        + "download one for you; if not, the button below opens the free Temurin download page."));
                }
            }
            argumentPage.add(Component.literal((javaTab ? "Or keep Java " + java + ": " : waited + " ")
                + "add this line to the JVM arguments once. It turns on Nitro's agent, which fixes the pause on this "
                + "Java and stays valid when Nitro updates."));
            argumentBoxLine = argumentPage.size();
            argumentPage.add(Component.literal(argument));
            argumentPage.add(Component.literal(who + ": " + steps));
            argumentPage.add(Component.literal("Everything else in Nitro is already running."));
        }

        private List<Component> page() {
            return onJavaTab ? javaPage : argumentPage;
        }

        private int boxLine() {
            return onJavaTab ? javaBoxLine : argumentBoxLine;
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
            if (onJavaTab) {
                if (java25 != null) {
                    addRenderableWidget(Button.builder(Component.literal("Copy Java 25 path"), b -> {
                        Minecraft.getInstance().keyboardHandler.setClipboard(java25);
                        status = Component.literal("Copied. Paste it as the Java path, then restart the game.");
                    }).bounds(x, y, inner, 20).build());
                } else {
                    addRenderableWidget(Button.builder(Component.literal("Get Java 25 (opens your browser)"),
                        b -> ConfirmLinkScreen.confirmLinkNow(this, JAVA_25_DOWNLOAD)).bounds(x, y, inner, 20).build());
                }
            } else {
                addRenderableWidget(Button.builder(Component.literal("Copy argument"), b -> {
                    Minecraft.getInstance().keyboardHandler.setClipboard(argument);
                    status = Component.literal("Copied. Paste it where described above, then restart.");
                }).bounds(x, y, inner, 20).build());
            }

            int w = (inner - 5) / 2;
            int bottom = y + 24;
            addRenderableWidget(Button.builder(Component.literal("Later"), b -> onClose()).bounds(x, bottom, w, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Don't show again"), b -> {
                try {
                    NitroSettings.save(FMLPaths.GAMEDIR.get(), "agentNotice", "false");
                } catch (Exception e) {
                    Nitro.LOGGER.warn("Nitro: could not save agentNotice=false", e);
                }
                onClose();
            }).bounds(x + w + 5, bottom, inner - w - 5, 20).build());
        }

        private void showTab(boolean java) {
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
