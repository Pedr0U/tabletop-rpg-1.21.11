package com.pedro.tabletoprpg.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

public class DiceRollScreen extends Screen {

    private static final Identifier TEXTURE =
            Identifier.fromNamespaceAndPath("tabletop-rpg", "textures/gui/rpg_menu.png");

    private static final int TEXTURE_WIDTH = 408;
    private static final int TEXTURE_HEIGHT = 612;
    private static final int SCREEN_MARGIN = 16;
    private static final int MIN_BUTTON_WIDTH = 100;

    private final Screen parentScreen;
    private int panelX, panelY, panelWidth, panelHeight, buttonWidth;
    private float scale;

    /**
     * O botao "Presets" (01/10/2026).
     *
     * <p>Guardado em campo por causa do uso no {@link #render}: e ele quem sabe a
     * rolagem atual, e a rolagem e o que a tela de presets recebe como formula inicial.
     */
    private Button presetsButton;

    // --- ESTADO DA ROLAGEM ---
    private final List<String> selectedDice = new ArrayList<>();
    private int modifier = 0;

    public DiceRollScreen(Screen parentScreen) {
        super(Component.literal("Dice Roll"));
        this.parentScreen = parentScreen; // Guarda a tela anterior para podermos voltar
    }

    @Override
    protected void init() {
        super.init();
        int availW = this.width - SCREEN_MARGIN * 2;
        int availH = this.height - SCREEN_MARGIN * 2;
        this.scale = Math.min((float) availW / TEXTURE_WIDTH, (float) availH / TEXTURE_HEIGHT);

        panelWidth = Math.max(1, (int) (TEXTURE_WIDTH * scale));
        panelHeight = Math.max(1, (int) (TEXTURE_HEIGHT * scale));
        panelX = (this.width - panelWidth) / 2;
        panelY = (this.height - panelHeight) / 2;

        buttonWidth = (int) (panelWidth * 0.65f);
        if (buttonWidth > 220) buttonWidth = 220;
        if (buttonWidth < MIN_BUTTON_WIDTH) buttonWidth = MIN_BUTTON_WIDTH;

        buildUI();
    }

    private void buildUI() {
        int centerX = panelX + panelWidth / 2;

        // --- CALCULANDO POSIÇÕES BASEADAS NA ESCALA ---
        int row1Y = panelY + (int)(225 * scale);
        int row2Y = panelY + (int)(255 * scale);
        int row3Y = panelY + (int)(285 * scale);
        int actionY = panelY + (int)(340 * scale);
        int bottomY = panelY + (int)(370 * scale);

        // --- LINHA 1: d4, d6, d8, d10 ---
        int btnW = 32;
        int gap = 4;
        int row1X = centerX - (btnW * 4 + gap * 3) / 2;
        addDiceButton("d4", row1X, row1Y, btnW);
        addDiceButton("d6", row1X + btnW + gap, row1Y, btnW);
        addDiceButton("d8", row1X + (btnW + gap) * 2, row1Y, btnW);
        addDiceButton("d10", row1X + (btnW + gap) * 3, row1Y, btnW);

        // --- LINHA 2: d12, d20, d100 ---
        int row2X = centerX - (btnW * 3 + gap * 2) / 2;
        addDiceButton("d12", row2X, row2Y, btnW);
        addDiceButton("d20", row2X + btnW + gap, row2Y, btnW);
        addDiceButton("d100", row2X + (btnW + gap) * 2, row2Y, btnW);

        // --- LINHA 3: Modificadores (-10, -1, +1, +10) ---
        int modX = centerX - (btnW * 4 + gap * 3) / 2;
        addModButton("-10", modX, row3Y, btnW, -10);
        addModButton("-1", modX + btnW + gap, row3Y, btnW, -1);
        addModButton("+1", modX + (btnW + gap) * 2, row3Y, btnW, 1);
        addModButton("+10", modX + (btnW + gap) * 3, row3Y, btnW, 10);

        // --- BOTÕES DE AÇÃO ---
        int actionX = panelX + (panelWidth - buttonWidth) / 2;

        this.addRenderableWidget(Button.builder(Component.literal("Roll!"), b -> rollDice())
                .bounds(actionX, actionY, buttonWidth, 20).build());

        int halfW = (buttonWidth - gap) / 2;
        this.addRenderableWidget(Button.builder(Component.literal("Clear"), b -> clearRoll())
                .bounds(actionX, bottomY, halfW, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> this.minecraft.setScreen(parentScreen))
                .bounds(actionX + halfW + gap, bottomY, halfW, 20).build());

        // "Presets", ABAIXO da linha Clear/Back e centralizado (01/10/2026).
        // A linha 400 da textura e o primeiro espaco livre depois dos botoes: o
        // desenho do painel vai ate a linha 534, entao sobra sem mexer na arte.
        //
        // O botao e SEMPRE visivel agora: a tela nova tambem cria preset do zero, e
        // esconder sem valor de rolagem tiraria da jogadora a unica forma de abrir
        // a lista quando ela so quer reordenar ou apagar.
        int presetY = panelY + (int) (400 * scale);
        this.presetsButton = Button.builder(Component.literal("Presets"), b -> openPresets())
                .bounds(actionX, presetY, buttonWidth, 20).build();
        this.addRenderableWidget(presetsButton);
    }

    /**
     * Abre a tela de presets, levando a rolagem atual como formula inicial.
     *
     * <p><b>Por que leva a formula:</b> quando a jogadora monta um preset a partir de
     * uma rolagem, essa rolagem e o que ela quer transformar em preset; digitar de novo
     * seria trabalho jogado fora. Sem valor na rolagem, o campo começa vazio e a tela
     * serve para criar do zero.
     */
    private void openPresets() {
        PresetsScreen screen = new PresetsScreen(this);
        this.minecraft.setScreen(screen);
        // Uma linha por abertura, com o tamanho da janela: e o que permite confirmar,
        // pelo log, que a tela foi aberta E com quanto espaco ela foi desenhada. Um
        // layout estourado so e visivel aqui (02/10/2026).
        TabletopRpgClient.LOGGER.info(
                "[TabletopRPG] PresetsScreen aberta ({}x{}).",
                this.minecraft.getWindow().getGuiScaledWidth(),
                this.minecraft.getWindow().getGuiScaledHeight());
        // Pede a lista depois de trocar a tela: o receptor procura uma PresetsScreen
        // na frente, e quem responde e o servidor.
        screen.requestList();
    }

    private void addDiceButton(String dice, int x, int y, int w) {
        this.addRenderableWidget(Button.builder(Component.literal(dice), b -> {
            selectedDice.add(dice);
        }).bounds(x, y, w, 20).build());
    }

    private void addModButton(String label, int x, int y, int w, int value) {
        this.addRenderableWidget(Button.builder(Component.literal(label), b -> {
            modifier += value; // Acumula o valor (+10, -1, etc)
        }).bounds(x, y, w, 20).build());
    }

    private void clearRoll() {
        selectedDice.clear();
        modifier = 0;
    }

    /**
     * Ha valor de rolagem? (01/10/2026)
     *
     * <p>Unico teste de "tem valor" da tela. O {@link #getRollExpression()}Returns
     * "Select dice..." nesse caso, mas comparar com esse texto espalha uma constante
     * magica pela tela; aqui a condicao esta escrita uma vez so.
     */
    private boolean hasRollValue() {
        return !selectedDice.isEmpty() || modifier != 0;
    }

    /** Constrói o texto visual, ex: "d20 + d10 + 9" ou "d8 - 2" */
    private String getRollExpression() {
        if (selectedDice.isEmpty() && modifier == 0) return "Select dice...";

        StringBuilder sb = new StringBuilder();
        if (!selectedDice.isEmpty()) {
            sb.append(String.join(" + ", selectedDice));
        }

        if (modifier > 0) {
            if (sb.length() > 0) sb.append(" + ");
            sb.append(modifier);
        } else if (modifier < 0) {
            if (sb.length() > 0) sb.append(" - ");
            else sb.append("-"); // Caso o jogador clique num modificador antes do dado
            sb.append(Math.abs(modifier));
        }

        return sb.toString();
    }

    private void rollDice() {
        String expr = getRollExpression();
        if (expr.equals("Select dice...")) return;

        // Limpa os espaços da string (ex: "d20+d10+9") e envia para o servidor
        String cmd = "rpg roll " + expr.replace(" ", "");
        if (this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.connection.sendCommand(cmd);
        }

        // Fecha o menu após rolar
        this.minecraft.setScreen(null);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);

        // Título alinhado com o da outra tela
        int titleY = panelY + (int)(210 * this.scale);
        drawCenteredText(graphics, "§lDice Roller", titleY, 0xFF000000);

        // Texto da rolagem fica no espaço vazio antes do botão Roll
        int exprY = panelY + (int)(320 * this.scale);
        drawCenteredText(graphics, getRollExpression(), exprY, 0xFF8B0000);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0x99000000);
        graphics.pose().pushMatrix();
        graphics.pose().translate(panelX, panelY);
        graphics.pose().scale(this.scale, this.scale);
        graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, 0, 0, 0, 0, TEXTURE_WIDTH, TEXTURE_HEIGHT, TEXTURE_WIDTH, TEXTURE_HEIGHT);
        graphics.pose().popMatrix();
    }

    private void drawCenteredText(GuiGraphics graphics, String text, int y, int color) {
        int x = panelX + (panelWidth - this.font.width(text)) / 2;
        graphics.drawString(this.font, text, x, y, color, false);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}