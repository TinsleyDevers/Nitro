package com.tinsl.nitro.client;

import com.tinsl.nitro.Nitro;
import com.tinsl.nitro.common.NitroSettings;
import com.tinsl.zambielib.client.GuiKit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Tells the player, once, on the main menu, that Nitro's launch patches need the agent in the JVM
 * arguments, and gives them the exact line with a button that copies it. Most players never open a
 * log, and the launch patches are the bigger half of what Nitro does on a large pack, so a log line
 * alone would leave most of them wondering why nothing got faster.
 */
public final class AgentNotice {
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
            event.setNewScreen(new NoticeScreen(title, argument, launcher, steps, System.getProperty("nitro.javaSteps")));
        });
    }

    private static final class NoticeScreen extends Screen {
        private static final int PANEL_WIDTH = 300;
        static String body(int java) {
            String why = java >= 24
                ? "Java " + java + " already skips the long module pause, but Nitro's other launch patches run "
                    + "before any mod loads, and Java only allows that for an agent you add to the JVM arguments yourself. "
                : "Nitro's biggest launch speedups run before any mod loads, and Java only allows that for an "
                    + "agent you add to the JVM arguments yourself. ";
            return why + "Add this line once; it stays valid when Nitro updates.";
        }

        private final Screen parent;
        private final Component argument;
        private final String argumentText;
        private Component status = Component.empty();
        private final Component steps;
        private final Component body;
        private int bodyHeight;
        private int argumentHeight;
        private int stepsHeight;
        private int panelHeight;

        /**
         * @param javaSteps where to switch this instance to Java 25 in the detected launcher, or null when
         *                  that is not an option (already on Java 24+, or a launcher that picks Java itself)
         */
        NoticeScreen(Screen parent, String argument, String launcher, String steps, String javaSteps) {
            super(Component.literal("Nitro: faster launches"));
            this.parent = parent;
            this.argumentText = argument;
            this.argument = Component.literal(argument);
            // Capitalised for the start of the sentence ("the CurseForge app" -> "The CurseForge app").
            String who = launcher.isEmpty() ? launcher : Character.toUpperCase(launcher.charAt(0)) + launcher.substring(1);
            int java = Runtime.version().feature();
            if (javaSteps != null) {
                // Java 25 first: it removes the module resolver pause with nothing to add, and measured faster
                // than Java 21 with the agent. The argument stays as the option for keeping this Java.
                this.body = Component.literal("Before any mod loaded, this launch waited on Java " + java + "'s module "
                    + "resolver, which is slow with hundreds of mods. Fastest fix: run this instance on Java 25, which has "
                    + "the fix built in. " + who + ": " + javaSteps + " Or keep Java " + java + " and add this line to the "
                    + "JVM arguments once:");
            } else {
                this.body = Component.literal(body(java));
            }
            this.steps = Component.literal(who + ": " + steps + " Everything else in Nitro is already running.");
        }

        private int panelX() {
            return (width - PANEL_WIDTH) / 2;
        }

        private int panelY() {
            return Math.max(4, (height - panelHeight) / 2);
        }

        /** Top of the argument box, relative to the panel. */
        private int argumentTop() {
            return 22 + bodyHeight + 6;
        }

        @Override
        protected void init() {
            bodyHeight = GuiKit.drawWrapped(null, font, body, 0, 0, PANEL_WIDTH - 20, 0, false);
            argumentHeight = GuiKit.drawWrapped(null, font, argument, 0, 0, PANEL_WIDTH - 28, 0, false);
            stepsHeight = GuiKit.drawWrapped(null, font, steps, 0, 0, PANEL_WIDTH - 20, 0, false);
            int buttonsTop = argumentTop() + argumentHeight + 8 + 6 + stepsHeight + 8;
            panelHeight = buttonsTop + 20 + 10;

            int x = panelX() + 10;
            int y = panelY() + buttonsTop;
            int w = (PANEL_WIDTH - 30) / 3;
            addRenderableWidget(Button.builder(Component.literal("Copy argument"), b -> {
                Minecraft.getInstance().keyboardHandler.setClipboard(argumentText);
                status = Component.literal("Copied. Paste it where described above, then restart.");
            }).bounds(x, y, w, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Later"), b -> onClose())
                .bounds(x + w + 5, y, w, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Don't show again"), b -> {
                try {
                    NitroSettings.save(FMLPaths.GAMEDIR.get(), "agentNotice", "false");
                } catch (Exception e) {
                    Nitro.LOGGER.warn("Nitro: could not save agentNotice=false", e);
                }
                onClose();
            }).bounds(x + 2 * (w + 5), y, w, 20).build());
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            // Background and widgets first; the 1.21 background blur would otherwise cover the panel.
            super.render(g, mouseX, mouseY, partialTick);
            int x = panelX();
            int y = panelY();
            GuiKit.drawRaisedPanel(g, x, y, PANEL_WIDTH, panelHeight);
            g.drawString(font, title, x + 10, y + 8, GuiKit.TITLE_GRAY, false);
            GuiKit.drawWrapped(g, font, body, x + 10, y + 22, PANEL_WIDTH - 20, GuiKit.DARK_GRAY, true);
            int boxTop = y + argumentTop();
            GuiKit.drawInset(g, x + 10, boxTop, PANEL_WIDTH - 20, argumentHeight + 8);
            GuiKit.drawWrapped(g, font, argument, x + 14, boxTop + 4, PANEL_WIDTH - 28, GuiKit.WHITE, true);
            GuiKit.drawWrapped(g, font, steps, x + 10, boxTop + argumentHeight + 8 + 6, PANEL_WIDTH - 20, GuiKit.DARK_GRAY, true);
            // The widgets were drawn under the panel by super.render; draw them again on top of it.
            for (var child : children()) {
                if (child instanceof Button button) {
                    button.render(g, mouseX, mouseY, partialTick);
                }
            }
            if (!status.getString().isEmpty()) {
                g.drawCenteredString(font, status, width / 2, y + panelHeight + 6, GuiKit.YELLOW);
            }
        }

        @Override
        public void onClose() {
            minecraft.setScreen(parent);
        }
    }
}
