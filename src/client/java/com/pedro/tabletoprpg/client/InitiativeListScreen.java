package com.pedro.tabletoprpg.client;

import com.pedro.tabletoprpg.InitiativeSync;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Lista de iniciativa somente leitura (Fase 3).
 *
 * <p>Na Fase 4 ela ganha os botoes de remover, dar turno e arrastar para reordenar.
 * Ate la, o mestre ve a mesma lista que o jogador.
 */
public class InitiativeListScreen extends Screen {

    /** Largura do painel central; os botoes e a lista usam a mesma coluna. */
    static final int PANEL_W = 280;
    static final int BUTTON_W = 200;
    static final int BUTTON_H = 20;

    private final Screen parent;

    public InitiativeListScreen(Screen parent) {
        super(Component.translatable("screen.tabletoprpg.initiative.list_title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.translatable("screen.tabletoprpg.initiative.back"),
                        b -> this.minecraft.setScreen(parent))
                .bounds(width / 2 - BUTTON_W / 2, height - 28, BUTTON_W, BUTTON_H)
                .build());
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        drawPanel(graphics, width, height);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);
        drawTitle(graphics, font, getTitle().getString(), width, 18);
        drawEntries(graphics, font, width / 2 - PANEL_W / 2 + 10, 44, height - 36,
                InitiativeClientState.entries());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Fundo escuro e painel central, sem textura de pergaminho. */
    static void drawPanel(GuiGraphics graphics, int screenW, int screenH) {
        graphics.fill(0, 0, screenW, screenH, 0x99000000);
        int x = screenW / 2 - PANEL_W / 2;
        graphics.fill(x - 1, 9, x + PANEL_W + 1, screenH - 9, 0xFF555555);
        graphics.fill(x, 10, x + PANEL_W, screenH - 10, 0xE0101010);
    }

    static void drawTitle(GuiGraphics graphics, Font font, String text, int screenW, int y) {
        graphics.drawString(font, text, screenW / 2 - font.width(text) / 2, y, 0xFFFFD700, false);
    }

    /** Lista de participantes, do maior valor para o menor, cortada no limite de baixo. */
    static void drawEntries(GuiGraphics graphics, Font font, int x, int y, int bottom,
                            List<InitiativeSync.Entry> entries) {
        if (entries.isEmpty()) {
            graphics.drawString(font, Component.translatable("message.tabletoprpg.initiative.empty").getString(),
                    x, y, 0xFFAAAAAA, false);
            return;
        }
        int lineY = y;
        for (InitiativeSync.Entry entry : entries) {
            if (lineY + font.lineHeight > bottom) {
                break;
            }
            String line = Component.translatable("message.tabletoprpg.initiative.list_line",
                    entry.name(), entry.value()).getString();
            graphics.drawString(font, line, x, lineY, 0xFFFFFFFF, false);
            lineY += font.lineHeight + 4;
        }
    }
}
