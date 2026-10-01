package com.pedro.tabletoprpg.client;

import com.pedro.tabletoprpg.RpgNetworking;
import com.pedro.tabletoprpg.SheetData;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/**
 * Formulario de <b>um item do inventario</b> (30/09/2026, FASE 2B).
 *
 * <p>Tela separada da ficha, como o pedido do jogador para o inventario: a coluna
 * da aba 1 e curta (200px) e um formulario com quatro campos nao cabe nela sem
 * ficar inutilizavel. O {@code Cancel} e o {@code Back} voltam para a ficha da
 * mesma pessoa, sem passar pelo menu.
 *
 * <p><b>Por que o indice e o item viajam no construtor:</b> o {@code UPDATE} do
 * item sai pelo indice da lista, e "o mesmo item" so pode ser dito por posicao --
 * enquanto o jogador edita, o item pode ter sido apagado, e o nome sozinho nao
 * sobrevive a isso (o mesmo motivo do {@code index} do {@code SheetSkillPayload}).
 * Com {@code index = -1} a tela cria um item novo, que nao tem posicao nenhuma.
 * O item original e passado pelo pai (a ficha), e nao lido aqui: a tela nao tem
 * nem o indice valido nem a ficha a mao.
 *
 * <p><b>Por que o peso e lido com 2 casas e nunca com virgula:</b> o
 * {@link SheetData#InventoryItem#WEIGHT_PATTERN} e o mesmo filtro das duas telas
 * que escrevem peso, e o texto do servidor volta formatado com ponto
 * ({@link SheetData#formatWeight}). Uma virgula passaria no filtro e viraria 0
 * depois do parse.
 */
public class InventoryItemScreen extends Screen {

    /** Altura de uma linha de campo (rotulo + caixa). */
    private static final int ROW_H = 22;

    /** Altura reservada aos botoes no rodape do painel. */
    private static final int FOOTER_H = 26;

    /** Espaco reservado ao rotulo a esquerda da caixa. */
    private static final int LABEL_W = 52;

    /** Teto de caracteres do campo de peso: 4 digitos, ponto e 2 casas. */
    private static final int WEIGHT_MAX_LEN = 8;

    private final Screen parent;
    private final String targetName;

    /** Indice do item editado, ou {@code -1} para um item novo. */
    private final int index;

    /** O item original, como o pai leu da ficha; {@code null} quando e novo. */
    private final SheetData.InventoryItem original;

    private EditBox nameBox;
    private EditBox typeBox;
    private EditBox weightBox;
    private MultiLineEditBox descriptionBox;

    public InventoryItemScreen(Screen parent, String targetName, int index, SheetData.InventoryItem original) {
        super(Component.literal(index < 0 ? "New Item" : "Edit Item"));
        this.parent = parent;
        this.targetName = targetName == null ? "" : targetName;
        this.index = index;
        this.original = original;
    }

    @Override
    protected void init() {
        SheetData.InventoryItem current = original == null ? SheetData.InventoryItem.EMPTY : original;

        int panelW = Math.max(200, Math.min(this.width - 20, 280));
        int panelX = (this.width - panelW) / 2;
        int boxX = panelX + 8 + labelW();
        int boxW = Math.max(40, panelW - 16 - labelW());

        int y = 40;
        nameBox = field("Name", boxX, y, boxW, current.name());
        y += ROW_H;
        typeBox = field("Type", boxX, y, boxW, current.type());
        y += ROW_H;
        weightBox = field("Weight", boxX, y, boxW, SheetData.formatWeight(current.weight()));
        // Digito e UM ponto, com 2 casas: o mesmo texto que o construtor da ficha
        // aceita depois. Um filtro de digito E ponto soltos deixaria passar
        // "1.2.3", que nao parseia e entraria como 0.
        weightBox.setMaxLength(WEIGHT_MAX_LEN);
        weightBox.setFilter(text -> text.matches(SheetData.InventoryItem.WEIGHT_PATTERN));

        // A descricao e o unico campo que cresce: ela fica entre a ultima linha e
        // os botoes. Sem `setLineLimit`, que contaria as linhas VISIVEIS (o teto do
        // servidor e o `setCharacterLimit`, em 2000).
        int descTop = y + ROW_H;
        int descBottom = Math.max(descTop + 20, this.height - FOOTER_H - 26);
        descriptionBox = MultiLineEditBox.builder()
                .setX(boxX)
                .setY(descTop)
                .setPlaceholder(Component.literal("description"))
                .setTextColor(CharacterSheetScreen.COL_BOX_TEXT)
                .setTextShadow(false)
                .setCursorColor(CharacterSheetScreen.COL_BOX_TEXT)
                .setShowBackground(true)
                .setShowDecorations(true)
                .build(this.font, boxW, descBottom - descTop, Component.literal("Description"));
        descriptionBox.setCharacterLimit(SheetData.MAX_TEXT);
        descriptionBox.setValue(current.description());
        // **OBRIGATORIO**: sem `addRenderableWidget` a caixa nao entra em
        // Screen.children() e nao recebe clique, foco, teclado nem rolagem.
        addRenderableWidget(descriptionBox);

        int buttonW = (panelW - 24) / 2;
        int buttonY = this.height - FOOTER_H;
        addRenderableWidget(Button.builder(Component.literal("Save"), b -> save())
                .bounds(panelX + 8, buttonY, buttonW, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(panelX + panelW - 8 - buttonW, buttonY, buttonW, 20)
                .build());

        setInitialFocus(nameBox);
    }

    /** O item que a tela abre: o enviado pelo pai, ou um item vazio. */
    private SheetData.InventoryItem currentItem() {
        return original == null ? SheetData.InventoryItem.EMPTY : original;
    }

    /** Cria uma caixa de uma linha com o rotulo desenhado a esquerda. */
    private EditBox field(String label, int x, int y, int w, String value) {
        EditBox box = new EditBox(this.font, x, y, w, 18, Component.literal(label));
        box.setMaxLength(SheetData.MAX_NAME);
        box.setValue(value);
        addRenderableWidget(box);
        return box;
    }

    /**
     * Rotulos das caixas: desenhados no render, como os titulos da ficha.
     *
     * <p><b>Ancorado na propria caixa, e nao numa Y fixa (bug do usuario em
     * 30/09/2026):</b> a caixa de descricao nao ocupa a 4a linha do formulario
     * -- ela cresce ate o rodape -- e o rotulo "Description" era desenhado na 4a
     * linha, uma acima da caixa. Cada rotulo agora sai do {@code getY()} da
     * caixa que ele nomeia.
     */
    private void renderLabels(GuiGraphics graphics) {
        drawLabel(graphics, "Name", nameBox.getY());
        drawLabel(graphics, "Type", typeBox.getY());
        drawLabel(graphics, "Weight", weightBox.getY());
        drawLabel(graphics, "Description", descriptionBox.getY());
    }

    /**
     * Um rotulo terminado 4 px antes da coluna das caixas.
     *
     * <p><b>Alinhado pela direita:</b> assim um rotulo mais largo cresce para a
     * esquerda e nunca invade a caixa -- que era o bug de "Description", a
     * palavra mais larga do formulario.
     */
    private void drawLabel(GuiGraphics graphics, String text, int boxY) {
        int right = nameBox.getX() - 4;
        graphics.drawString(this.font, text, right - this.font.width(text),
                boxY + 5, CharacterSheetScreen.COL_LABEL, false);
    }

    /**
     * Largura da coluna dos rotulos: a maior das quatro palavras, mais folga.
     *
     * <p>Com {@link #LABEL_W} fixo, "Description" (a mais larga) transbordava
     * para dentro da caixa de texto. Medir o rotulo de verdade deixa o layout
     * correto mesmo se a fonte mudar.
     */
    private int labelW() {
        return Math.max(LABEL_W, this.font.width("Description") + 6);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);
        // O rotulo do titulo vem antes dos widgets (o mesmo cuidado da base: o
        // `super.render` desenha os widgets e o texto de cima do que vier depois).
        graphics.drawString(this.font, this.title, (this.width - this.font.width(this.title)) / 2,
                8, CharacterSheetScreen.COL_TITLE, false);
        renderLabels(graphics);
    }

    /**
     * Monta o item e envia.
     *
     * <p><b>O peso vai como texto e o servidor e quem converte</b>, com o mesmo
     * caminho de qualquer outro campo: o texto que nao parseia mantem o valor
     * anterior, e nao zera o peso. Aqui o caso e o contrario do que a caixa
     * permite -- o filtro garante um texto numerico, e um texto so com "." ainda
     * nao parseia, e esse vira 0.
     *
     * <p><b>Depois de salvar volta para a ficha e PEDE a ficha de novo:</b> o eco
     * do servidor so e entregue quando a tela visivel e a
     * {@code CharacterSheetScreen} (ver o receptor em {@code TabletopRpgClient}),
     * entao o eco deste pacote chega enquanto esta tela ainda esta aberta e seria
     * descartado. Sem o pedido, a lista da coluna ficaria mostrando o item velho
     * ate o jogador trocar de aba.
     */
    private void save() {
        float weight = parseWeight();
        SheetData.InventoryItem item = new SheetData.InventoryItem(
                nameBox.getValue(), typeBox.getValue(), weight, descriptionBox.getValue());
        ClientPlayNetworking.send(index < 0 || original == null
                ? RpgNetworking.SheetItemPayload.add(targetName, item)
                : RpgNetworking.SheetItemPayload.update(targetName, index, item));
        onClose();
        TabletopRpgClient.requestSheet(targetName);
    }

    /** O peso digitado, ou 0 quando o texto ainda nao e um numero. */
    private float parseWeight() {
        try {
            return Float.parseFloat(weightBox.getValue().trim());
        } catch (NumberFormatException e) {
            return 0f;
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    /** ESC fecha o formulario, e nao a ficha inteira. */
    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }
}