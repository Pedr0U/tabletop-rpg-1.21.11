package com.pedro.tabletoprpg.client;

import com.pedro.tabletoprpg.InitiativeRoller;
import com.pedro.tabletoprpg.RpgNetworking;
import com.pedro.tabletoprpg.SheetModel;
import com.pedro.tabletoprpg.SheetModelHolder;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Configuracao da iniciativa da mesa (04/11/2026).
 *
 * <p>Aberta pelo botao "Configurar iniciativa" da tela de iniciativa, so para o
 * Mestre. Tem duas regras: a formula padrao da mesa (jogadores e mobs rolam essa
 * formula mais a formula de iniciativa da propria ficha) e a liberacao da
 * <b>formula propria</b> na rolagem dos jogadores, que decide se
 * {@code /rpg initiative roll 2d6} funciona para quem nao e Mestre (adicoes com
 * sinal, como {@code +2d6}, continuam liberadas sempre).
 *
 * <p>A tela tem painel proprio com fundo (06/11/2026): as dicas sao desenhadas
 * <b>dentro</b> dele, com quebra de linha, para nao passarem por cima dos botoes.
 *
 * <p>O servidor e a autoridade: a tela so valida para nao mandar lixo, e o
 * receptor de {@link RpgNetworking.InitiativeFormulaPayload} confere de novo a
 * permissao e o parser antes de gravar no mundo.
 */
public class InitiativeConfigScreen extends Screen {

    private static final int PANEL_W = 360;
    private static final int PANEL_H = 236;
    private static final int SIDE = 20;
    private static final int BOX_H = 20;
    private static final int GAP = 8;

    private final Screen parent;
    private EditBox formulaBox;
    private Button saveButton;
    private Button playerFormulaButton;
    private boolean invalid;
    /** Valor da tela para a liberacao da formula propria; o Salvar envia junto. */
    private boolean playerFormulaRoll;

    private int panelX;
    private int panelY;
    private int panelW;
    private int boxY;
    private int toggleY;
    private int saveY;

    public InitiativeConfigScreen(Screen parent) {
        super(Component.translatable("screen.tabletoprpg.initiative.config_title"));
        this.parent = parent;
        this.playerFormulaRoll = SheetModelHolder.current().initiativePlayerFormulaRoll();
    }

    @Override
    protected void init() {
        panelW = Math.min(PANEL_W, width - 20);
        panelX = (width - panelW) / 2;
        panelY = Math.max(10, (height - PANEL_H) / 2);
        boxY = panelY + 54;
        toggleY = boxY + BOX_H + 28;
        saveY = panelY + PANEL_H - 30;

        int contentW = panelW - SIDE * 2;
        int boxW = Math.min(220, contentW);

        formulaBox = new EditBox(font, width / 2 - boxW / 2, boxY, boxW, BOX_H,
                Component.translatable("screen.tabletoprpg.initiative.config_formula"));
        formulaBox.setMaxLength(SheetModel.MAX_INITIATIVE_FORMULA);
        // Aceita so o que o parser entende (dados, numeros e soma). A validacao
        // completa roda no responder, porque o parser tem regras demais para um
        // filtro de caractere (ex.: "d" sozinho passa no filtro e nao e formula).
        formulaBox.setFilter(value -> value.matches("[0-9dD+]{0," + SheetModel.MAX_INITIATIVE_FORMULA + "}"));
        formulaBox.setValue(SheetModelHolder.current().initiativeFormula());
        formulaBox.setHint(Component.translatable("screen.tabletoprpg.initiative.formula_hint"));
        formulaBox.setResponder(this::onFormulaChanged);
        addRenderableWidget(formulaBox);

        playerFormulaButton = Button.builder(playerFormulaLabel(),
                        b -> togglePlayerFormula())
                .bounds(width / 2 - contentW / 2, toggleY, contentW, BOX_H)
                .build();
        addRenderableWidget(playerFormulaButton);

        int buttonW = (contentW - GAP) / 2;
        saveButton = Button.builder(Component.translatable("screen.tabletoprpg.initiative.config_save"),
                        b -> save())
                .bounds(width / 2 - contentW / 2, saveY, buttonW, BOX_H)
                .build();
        addRenderableWidget(saveButton);
        addRenderableWidget(Button.builder(Component.translatable("screen.tabletoprpg.initiative.back"),
                        b -> back())
                .bounds(width / 2 - contentW / 2 + buttonW + GAP, saveY, buttonW, BOX_H)
                .build());
        onFormulaChanged(formulaBox.getValue());
    }

    private void onFormulaChanged(String value) {
        // Vazia vale: o modelo troca por 1d20 (mesma regra do servidor).
        invalid = !InitiativeRoller.isValidPlainFormula(value);
        if (saveButton != null) {
            saveButton.active = !invalid;
        }
    }

    /** Interruptor local: nada e enviado ate o Salvar, igual a formula. */
    private void togglePlayerFormula() {
        playerFormulaRoll = !playerFormulaRoll;
        if (playerFormulaButton != null) {
            playerFormulaButton.setMessage(playerFormulaLabel());
        }
    }

    private Component playerFormulaLabel() {
        return Component.translatable("screen.tabletoprpg.initiative.config_player_formula",
                playerFormulaRoll ? "ON" : "OFF");
    }

    private void save() {
        if (invalid) {
            return;
        }
        String formula = InitiativeRoller.compact(formulaBox.getValue());
        SheetModel current = SheetModelHolder.current();
        if (!formula.equals(current.initiativeFormula())
                || playerFormulaRoll != current.initiativePlayerFormulaRoll()) {
            ClientPlayNetworking.send(new RpgNetworking.InitiativeFormulaPayload(formula, playerFormulaRoll));
        }
        back();
    }

    private void back() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0x99000000);
        graphics.fill(panelX - 1, panelY - 1, panelX + panelW + 1, panelY + PANEL_H + 1, 0xFF555555);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + PANEL_H, 0xF0141414);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);
        int contentX = width / 2 - (panelW - SIDE * 2) / 2;
        int contentW = panelW - SIDE * 2;

        String title = getTitle().getString();
        graphics.drawString(font, title, width / 2 - font.width(title) / 2, panelY + 10, 0xFFFFD700, false);

        int hintY = drawWrapped(graphics, Component.translatable(
                "screen.tabletoprpg.initiative.config_hint").getString(),
                contentX, panelY + 28, contentW, 0xFFAAAAAA);

        int playerHintY = Math.max(hintY, toggleY + BOX_H + 4);
        drawWrapped(graphics, Component.translatable(
                "screen.tabletoprpg.initiative.config_player_hint").getString(),
                contentX, playerHintY, contentW, 0xFFAAAAAA);

        if (invalid) {
            String error = Component.translatable(
                    "screen.tabletoprpg.initiative.formula_invalid").getString();
            drawWrapped(graphics, error, contentX, saveY - font.lineHeight - 6, contentW, 0xFFFF6060);
        }
    }

    /**
     * Desenha o texto quebrando em linhas na largura do painel e devolve o Y
     * seguinte, para a proxima dica nunca entrar por cima da anterior.
     */
    private int drawWrapped(GuiGraphics graphics, String text, int x, int y, int maxWidth, int color) {
        Font font = this.font;
        int lineY = y;
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (font.width(candidate) > maxWidth && !line.isEmpty()) {
                graphics.drawString(font, line.toString(), x, lineY, color, false);
                lineY += font.lineHeight + 2;
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (!line.isEmpty()) {
            graphics.drawString(font, line.toString(), x, lineY, color, false);
            lineY += font.lineHeight + 2;
        }
        return lineY;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
