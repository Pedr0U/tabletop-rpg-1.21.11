package com.pedro.tabletoprpg.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;

/**
 * Bolinha amarela de "edicao nao salva" desenhada POR CIMA dos botoes, no canto
 * direito interno (07/10/2026).
 *
 * <p>Por que um widget separado e nao um traco no render da tela: o desenho de
 * conteudo do Screen acontece ANTES dos widgets; a bolinha pintada ali sumiria
 * atras do botao. E por que e adicionada com {@code addRenderableOnly}: ela pinta
 * por cima e nao precisa de clique.
 */
final class DotWidget extends AbstractWidget {
    private final BooleanSupplier dot;

    DotWidget(int x, int y, BooleanSupplier dot) {
        super(x, y, 3, 3, Component.empty());
        this.dot = dot == null ? () -> false : dot;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (dot.getAsBoolean()) {
            graphics.fill(this.getX(), this.getY(), this.getX() + 3, this.getY() + 3, 0xFFFFE24B);
        }
    }

    @Override
    public void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        // Sem nome narravel: a bolinha e decorativa.
    }
}