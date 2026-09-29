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
        if (!settings.agentNotice) {
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
            event.setNewScreen(new NoticeScreen(title, argument));
        });
    }

    private static final class NoticeScreen extends Screen {
        private static final int PANEL_WIDTH = 300;
        private static final Component BODY = Component.literal(body(Runtime.version().feature()));

        static String body(int java) {
            String why = java >= 24
                ? "Java " + java + " already skips the long module pause, but Nitro's other launch patches run "
                    + "before any mod loads, and Java only allows that for an agent you add to the JVM arguments yourself. "
                : "Nitro's biggest launch speedups run before any mod loads, and Java only allows that for an "
                    + "agent you add to the JVM arguments yourself. ";
            return why + "Add this line to this instance's JVM arguments in your launcher. It stays valid when Nitro updates.";
        }
        private static final Component FOOTER = Component.literal("Everything else in Nitro is already running.");

        private final Screen parent;
        private final Component argument;
        private final String argumentText;
        private Component status = Component.empty();
        private int bodyHeight;
        private int argumentHeight;
        private int panelHeight;

        NoticeScreen(Screen parent, String argument) {
            super(Component.literal("Nitro: one step left"));
            this.parent = parent;
            this.argumentText = argument;
            this.argument = Component.literal(argument);
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
            bodyHeight = GuiKit.drawWrapped(null, font, BODY, 0, 0, PANEL_WIDTH - 20, 0, false);
            argumentHeight = GuiKit.drawWrapped(null, font, argument, 0, 0, PANEL_WIDTH - 28, 0, false);
            int buttonsTop = argumentTop() + argumentHeight + 8 + 6 + 10 + 8;
            panelHeight = buttonsTop + 20 + 10;

            int x = panelX() + 10;
            int y = panelY() + buttonsTop;
            int w = (PANEL_WIDTH - 30) / 3;
            addRenderableWidget(Button.builder(Component.literal("Copy argument"), b -> {
                Minecraft.getInstance().keyboardHandler.setClipboard(argumentText);
                status = Component.literal("Copied. Paste it into the JVM arguments, then restart.");
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
            GuiKit.drawWrapped(g, font, BODY, x + 10, y + 22, PANEL_WIDTH - 20, GuiKit.DARK_GRAY, true);
            int boxTop = y + argumentTop();
            GuiKit.drawInset(g, x + 10, boxTop, PANEL_WIDTH - 20, argumentHeight + 8);
            GuiKit.drawWrapped(g, font, argument, x + 14, boxTop + 4, PANEL_WIDTH - 28, GuiKit.WHITE, true);
            GuiKit.drawWrapped(g, font, FOOTER, x + 10, boxTop + argumentHeight + 8 + 6, PANEL_WIDTH - 20, GuiKit.DARK_GRAY, true);
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
