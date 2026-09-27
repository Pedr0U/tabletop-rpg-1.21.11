package com.pedro.tabletoprpg.client;

import com.pedro.tabletoprpg.SheetData;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Tela do Sheet Editor, aberta pelo item {@code Sheet Editor}.
 *
 * <p>27/09/2026, <b>primeira fatia: somente leitura</b>. Ela existe para provar o
 * caminho inteiro (item, aba, clique, permissao, abertura de tela) antes de
 * introduzir o modelo salvo no mundo. <b>Nao edita nada ainda</b>: a lista vem
 * direto de {@link SheetData#PERICIAS_PADRAO}, e o botao diz isso na tela para
 * ninguem achar que ja salva.
 *
 * <p>A parte arriscada (modelo em {@code SavedData}, payloads de salvar, rolagem
 * e adicionar/remover/renomear) fica para a proxima fatia. Ver
 * {@code agent/reports/2026-09-27_sheet-editor-item.md}.
 *
 * <p><b>So o Mestre chega aqui:</b> o servidor so envia o pacote de abrir depois
 * de {@code SessionManager.isMaster}. Esta tela nao refaz a checagem de proposito
 * — se ela aparecesse para um jogador comum, o problema seria de permissao no
 * servidor, e nao de UI. Revezar a checagem aqui esconderia o bug.
 */
public class SheetEditorScreen extends Screen {

    private static final int LIST_TOP = 40;
    private static final int ROW_H = 14;
    private static final int MAX_ROWS_ON_SCREEN = 12;

    public SheetEditorScreen() {
        super(Component.translatable("item.tabletoprpg.sheet_editor"));
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(cx - 50, this.height - 28, 100, 20)
                .build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawCenteredString(this.font, this.title, this.width / 2, 14, 0xFFFFFFFF);

        // Aviso de fatia incompleta. Preferi uma frase na tela a um botao morto:
        // um "Add" que nao faz nada e pior do que ele nao existir.
        graphics.drawCenteredString(this.font,
                Component.literal("Read-only for now - saving comes next"),
                this.width / 2, 26, 0xFFFFC44D);

        var pericias = SheetData.PERICIAS_PADRAO;
        int visiveis = Math.min(pericias.size(), MAX_ROWS_ON_SCREEN);
        for (int i = 0; i < visiveis; i++) {
            SheetData.Pericia p = pericias.get(i);
            int y = LIST_TOP + i * ROW_H;
            graphics.drawString(this.font, p.name(), 24, y, 0xFFE6E6EE, false);
            graphics.drawString(this.font,
                    Integer.toString(p.value()), this.width - 60, y, 0xFF9A9AA2, false);
            graphics.drawString(this.font,
                    p.attribute().abbr(), this.width - 34, y, 0xFF9A9AA2, false);
        }

        if (pericias.size() > MAX_ROWS_ON_SCREEN) {
            // A rolagem de verdade entra na proxima fatia, junto com a edicao. Por
            // enquanto a lista e curta o bastante para caber inteira: sao 18
            // pericias e cabem 12 por tela em altura de janela normal, entao este
            // aviso aparece so em janela baixa, e diz o total em vez de cortar
            // silenciosamente.
            graphics.drawCenteredString(this.font,
                    Component.literal("+ " + (pericias.size() - MAX_ROWS_ON_SCREEN) + " more (scroll next)"),
                    this.width / 2, LIST_TOP + visiveis * ROW_H + 4, 0xFF9A9AA2);
        }
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(null);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }
}
