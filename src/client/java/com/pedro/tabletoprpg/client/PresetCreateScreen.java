package com.pedro.tabletoprpg.client;

import com.pedro.tabletoprpg.RollPresetColor;
import com.pedro.tabletoprpg.RpgNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * Formulario de criacao de preset, aberto pelo botao "Create Preset" da tela de
 * rolagem (01/10/2026).
 *
 * <p><b>Por que a formula vem preenchida:</b> o botao so aparece quando a rolagem ja
 * tem valor, e esse valor e exatamente o que a jogadora quer transformar em preset.
 * Digitar de novo seria trabalho jogado fora.
 *
 * <p><b>Por que a formula e so conferida ao salvar:</b> a regra de "formula
 * desconhecida" mora no {@code RollPreset.create}, no servidor, e e a mesma do
 * comando. Acusar enquanto a jogadora digita faria a tela piscar recusa a cada tecla
 * de um {@code 2d6} ainda pela metade. O preco e que o erro so aparece no botao --
 * por isso a linha de status fica logo abaixo dos botoes, perto de onde o olho
 * esta quando aperta.
 */
public class PresetCreateScreen extends Screen {

    private final Screen parentScreen;
    /** Formula vinda da rolagem atual, ja sem espacos (e o que o servidor aceita). */
    private final String initialFormula;

    private EditBox nameBox;
    private EditBox formulaBox;
    // Cor inicial branca: a opcao "default" saiu em 01/10/2026.
    private RollPresetColor selectedColor = RollPresetColor.WHITE;

    private int panelX, panelY, panelWidth;
    private int swatchX, swatchY, swatchSize, swatchGap;
    private int statusY;

    /** Os 16 squares, na ordem do enum: a tela mostra a mesma ordem das cores. */
    private static final RollPresetColor[] SWATCHES = RollPresetColor.values();

    public PresetCreateScreen(Screen parentScreen, String initialFormula) {
        super(Component.literal("Create Roll Preset"));
        this.parentScreen = parentScreen;
        this.initialFormula = initialFormula;
    }

    /**
     * A tela que abriu esta.
     *
     * <p>Existe para o receptor do servidor voltar para a rolagem sem precisar que
     * o cliente guarde a referencia: a resposta chega depois do clique, e sem isso o
     * receptor so teria o {@code Screen} que estiver na frente -- que pode ser outro.
     */
    public Screen parentScreen() {
        return parentScreen;
    }

    @Override
    protected void init() {
        super.init();

        // O painel e dimensionado pelo que precisa caber: duas caixas, o rotulo de
        // cor, as 17 amostras e a linha de status. Nao ha textura para este formulario,
        // entao a medida vem do conteudo -- se amanha entrar mais campo, o painel
        // acompanha em vez de cortar o campo novo.
        int boxWidth = 200;
        panelWidth = Math.min(this.width - 32, boxWidth + 80);
        panelX = (this.width - panelWidth) / 2;

        swatchSize = 14;
        swatchGap = 3;
        // Duas linhas de 9 cabem 17 amostras com sobra de 1; centralizado pela largura.
        int perRow = 9;
        int rows = (SWATCHES.length + perRow - 1) / perRow;
        int swatchesWidth = perRow * swatchSize + (perRow - 1) * swatchGap;

        int contentH = 40          // titulo
                + 26 * 2            // Name + Formula
                + 22                // rotulo Color
                + rows * (swatchSize + swatchGap)
                + 26                // Save / Cancel
                + 20;               // linha de status
        panelY = (this.height - contentH) / 2;

        int boxX = panelX + (panelWidth - boxWidth) / 2;
        int labelW = 52;

        nameBox = field("Name", boxX + labelW, panelY + 40, boxWidth, "");
        nameBox.setMaxLength(64);

        formulaBox = field("Formula", boxX + labelW, panelY + 66, boxWidth, initialFormula);
        formulaBox.setMaxLength(128);

        // Amostras centralizadas, abaixo do rotulo "Color".
        int colorLabelY = panelY + 92;
        swatchX = (this.width - swatchesWidth) / 2;
        swatchY = colorLabelY + 14;

        int btnY = swatchY + rows * (swatchSize + swatchGap) + 6;
        int btnW = (panelWidth - 20) / 2;
        this.addRenderableWidget(Button.builder(Component.literal("Save"), b -> save())
                .bounds(panelX + 10, btnY, btnW, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(panelX + 10 + btnW + 8, btnY, btnW, 20).build());

        statusY = btnY + 26;
    }

    private EditBox field(String label, int x, int y, int w, String value) {
        EditBox box = new EditBox(this.font, x, y, w, 18, Component.literal(label));
        box.setValue(value);
        // OBRIGATORIO: sem addRenderableWidget a caixa nao entra no ciclo de
        // desenho nem no de clique (mesmo cuidado ja anotado em InventoryItemScreen).
        addRenderableWidget(box);
        return box;
    }

    /**
     * Clique do mouse sobre as amostras de cor.
     *
     * <p><b>Por que o evento e um {@code MouseButtonEvent} e nao dois doubles:</b>
     * nesta versao (1.21.11) o {@code mouseClicked} da cadeia de GUI recebe o
     * evento, com {@code x()} e {@code y()}. A assinatura antiga com {@code double,
     * double, int} nao existe mais aqui e o compilador rejeita.
     *
     * <p>As amostras sao desenho puro, sem widget: cada uma seria 17 widgets com
     * rotulo vazio e tint, e nao ha tint de botao na API. O preco e tratar o clique
     * aqui e continuar deixando o {@code super} cuidar das caixas e botoes.
     */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int index = swatchAt(event.x(), event.y());
        if (index >= 0) {
            selectedColor = SWATCHES[index];
            // O mesmo som de botao do resto da interface. Fica para o fim de proposito:
            // escolher cor nao e um botao que "desce", entao o som de pressionar
            // (playDownSound) seria enganoso.
            if (this.minecraft != null) {
                AbstractWidget.playButtonClickSound(this.minecraft.getSoundManager());
            }
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    /** O quadradinho da cor, ou -1 se o clique foi fora de todos. */
    private int swatchAt(double mouseX, double mouseY) {
        int perRow = 9;
        if (mouseY < swatchY || mouseY >= swatchY + 2 * (swatchSize + swatchGap)) {
            return -1;
        }
        int row = (int) ((mouseY - swatchY) / (swatchSize + swatchGap));
        int col = (int) ((mouseX - swatchX) / (swatchSize + swatchGap));
        if (col < 0 || col >= perRow) {
            return -1;
        }
        int index = row * perRow + col;
        if (index < 0 || index >= SWATCHES.length) {
            return -1;
        }
        // A ultima linha tem menos de 9; o espaco sobrando nao e clicavel.
        double localX = mouseX - (swatchX + col * (swatchSize + swatchGap));
        double localY = mouseY - (swatchY + row * (swatchSize + swatchGap));
        if (localX > swatchSize || localY > swatchSize) {
            return -1;
        }
        return index;
    }

    /**
     * Envia os tres campos crus e deixa a resposta fechar a tela.
     *
     * <p>A validacao e do servidor. Se a formula for recusada, a tela fica aberta e a
     * linha de status mostra o texto do {@link RpgNetworking#PresetCreateResultPayload},
     * que e a mesma frase que o comando mostraria.
     */
    private void save() {
        ClientPlayNetworking.send(new RpgNetworking.PresetCreatePayload(
                nameBox.getValue().trim(), formulaBox.getValue().trim(), selectedColor.id()));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, this.width, this.height, 0x99000000);
        graphics.fill(panelX, panelY, panelX + panelWidth,
                statusY + 12, 0xFF202020);
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + 1, 0xFF6B4A2A);
        graphics.fill(panelX, statusY + 11, panelX + panelWidth, statusY + 12, 0xFF6B4A2A);

        super.render(graphics, mouseX, mouseY, delta);

        graphics.drawCenteredString(this.font, this.title, this.width / 2, panelY + 12, 0xFFE0C080);

        int boxX = nameBox.getX();
        drawLabel(graphics, "Name", nameBox.getY());
        drawLabel(graphics, "Formula", formulaBox.getY());
        graphics.drawCenteredString(this.font, "Color", this.width / 2, swatchY - 16, 0xFFB0B0B0);
        graphics.drawString(this.font, selectedColor.displayName(),
                boxX - 4 - this.font.width(selectedColor.displayName()), swatchY, 0xFFB0B0B0, false);

        // As amostras vao DEPOIS do super.render: sao desenho puro, sem widget, e o
        // super.render limpa o fundo antes de desenhar os widgets.
        int perRow = 9;
        for (int i = 0; i < SWATCHES.length; i++) {
            int row = i / perRow;
            int col = i % perRow;
            int x = swatchX + col * (swatchSize + swatchGap);
            int y = swatchY + row * (swatchSize + swatchGap);
            // DEFAULT nao pinta componente no item, entao tambem aqui ele e o
            // marrom do Bundle sem tingir -- o mesmo desenho que a jogadora vera.
            graphics.fill(x, y, x + swatchSize, y + swatchSize, SWATCHES[i].argb());
            // Borda clara em volta de quem esta escolhido, escura nas outras: a escolha
            // precisa ser visivel mesmo nas cores escuras (preto em preto sumiria).
            int border = SWATCHES[i] == selectedColor ? 0xFFFFFFFF : 0xFF101010;
            graphics.fill(x, y, x + swatchSize, y + 1, border);
            graphics.fill(x, y + swatchSize - 1, x + swatchSize, y + swatchSize, border);
            graphics.fill(x, y, x + 1, y + swatchSize, border);
            graphics.fill(x + swatchSize - 1, y, x + swatchSize, y + swatchSize, border);
        }

        // Linha de status: a resposta do servidor aparece aqui (setada pelo receptor
        // em TabletopRpgClient) e some quando a jogadora volta a digitar.
        String status = PresetCreateScreen.pendingStatus;
        if (status != null && !status.isEmpty()) {
            graphics.drawCenteredString(this.font, status, this.width / 2, statusY, 0xFFFF6060);
        }
    }

    private void drawLabel(GuiGraphics graphics, String text, int boxY) {
        int right = nameBox.getX() - 4;
        graphics.drawString(this.font, text, right - this.font.width(text), boxY + 5, 0xFFB0B0B0, false);
    }

    /**
     * A resposta do servidor para a tela que esta aberta.
     *
     * <p><b>Por que estatico:</b> o receptor do pacote roda fora da tela, e o
     * servidor responde depois do clique. Guardar o texto num campo da tela exigiria
     * a tela ainda viva -- e ela pode ter sido fechada. Estatico e o preco de um
     * aviso que sobraria se a jogadora fechar a tela rapido demais; o texto e limpo
     * no proximo {@code init}, entao nao vaza para a tela seguinte.
     */
    private static String pendingStatus = "";

    /** Chamado pelo receptor do cliente com o texto da recusa. */
    public static void setStatus(String message) {
        pendingStatus = message == null ? "" : message;
    }

    /** Limpa o aviso: chamado ao abrir a tela, para nao herdar o da anterior. */
    public static void clearStatus() {
        pendingStatus = "";
    }

    @Override
    public void onClose() {
        // Abrir esta tela consome o aviso pendente: a resposta antiga nao deve
        // reaparecer quando a jogadora abrir o formulario de novo.
        clearStatus();
        this.minecraft.setScreen(parentScreen);
    }
}
