package com.pedro.tabletoprpg.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Formulario de <b>uma entrada</b> da ficha de ameaca (02/10/2026): uma
 * caracteristica, uma habilidade passiva ou uma habilidade ativa.
 *
 * <p><b>Por que uma tela so para as tres coisas, e nao tres telas:</b> as tres sao
 * "um nome, uns campos de texto e Salvar/Cancelar", e o que muda e quantos campos
 * tem. Com {@link Field} descrevendo a linha, a tela monta o que veio e nao sabe o
 * que e uma passiva e o que e uma ativa -- e o Mestre aprende um formulario so.
 *
 * <p><b>Por que o mapa e a chave do que se salva:</b> o formulario nao conhece nem
 * {@code ThreatSheet.Ability} nem {@code ThreatSheet.Action}. Quem chamou e que le
 * o mapa e decide o que fazer com ele, entao uma tela nova aqui nunca precisa
 * conhecer a ficha.
 *
 * <p><b>Por que a descricao usa {@link MultiLineEditBox}:</b> o limite dela e de
 * 256 caracteres, e um {@link EditBox} de uma linha mostraria so o comeco. E o mesmo
 * widget que a ficha do jogador usa para os textos longos.
 */
public class ThreatEntryFormScreen extends Screen {

    /**
     * Uma linha do formulario.
     *
     * @param key        chave que volta no mapa de {@code onSave}
     * @param label      rotulo desenhado a esquerda da caixa
     * @param maxLength  limite de caracteres da caixa
     * @param multiline  {@code true} quando o texto e longo e precisa de caixa grande
     */
    public record Field(String key, String label, int maxLength, boolean multiline) {
    }

    /** Rotulo a desenhar no proximo render, ancorado na propria caixa. */
    private record Label(String text, int y) {
    }

    private static final int COL_SCREEN_BG = 0x99000000;
    private static final int COL_PANEL_BG = 0xFF202020;
    private static final int COL_BORDER = 0xFF6B4A2A;
    private static final int COL_TITLE = 0xFFE0C080;
    private static final int COL_LABEL = CharacterSheetScreen.COL_MUTED;
    private static final int COL_ERROR = CharacterSheetScreen.COL_DOWNED;

    private static final int PAD = 6;
    private static final int TITLE_H = 12;
    private static final int LABEL_H = 9;
    private static final int ROW_H = 18;
    private static final int GAP = 5;
    private static final int FOOTER_H = 20;
    private static final int STATUS_H = 10;
    /** Altura preferida da caixa de multiplas linhas. */
    private static final int MULTI_H = 58;
    /** Piso da caixa de multiplas linhas: abaixo disso nao ha texto visivel. */
    private static final int MULTI_MIN_H = 30;
    private static final int PANEL_W = 300;

    private final Screen parentScreen;
    private final String formTitle;
    private final List<Field> fields;
    private final Map<String, String> initial;
    private final Consumer<Map<String, String>> onSave;

    /** As caixas de uma linha, por chave. */
    private final Map<String, EditBox> boxes = new LinkedHashMap<>();
    /** As caixas grandes, por chave. */
    private final Map<String, MultiLineEditBox> multiBoxes = new LinkedHashMap<>();
    private final List<Label> labels = new ArrayList<>();

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int boxX;
    private int statusY;

    /** Recusa do ultimo Salvar; vazia quando nao ha nada a avisar. */
    private String error = "";

    public ThreatEntryFormScreen(Screen parentScreen, String title, List<Field> fields,
                                 Map<String, String> initial,
                                 Consumer<Map<String, String>> onSave) {
        super(Component.literal(title == null ? "" : title));
        this.parentScreen = parentScreen;
        this.formTitle = title == null ? "" : title;
        this.fields = fields == null ? List.of() : List.copyOf(fields);
        this.initial = initial == null ? Map.of() : initial;
        this.onSave = onSave;
    }

    @Override
    protected void init() {
        super.init();
        boxes.clear();
        multiBoxes.clear();
        labels.clear();

        panelW = Math.max(160, Math.min(this.width - 16, PANEL_W));
        panelX = (this.width - panelW) / 2;

        // O rotulo fica EM CIMA da caixa, e nao ao lado dela. Ao lado, o rotulo e a caixa
        // disputavam a mesma linha e o campo ficava estreito, porque a largura do
        // maior rotulo era descontada da caixa. Cima da caixa a caixa ganha a largura
        // toda do painel, que e o que o Mestre pediu.
        boxX = panelX + PAD;
        int boxW = boxWidth();

        int singleH = 0;
        int multiCount = 0;
        for (Field field : fields) {
            if (field.multiline()) {
                multiCount++;
            } else {
                singleH += LABEL_H + ROW_H;
            }
        }
        int multiH = MULTI_H;
        // **Os gaps contam por campo, nao um no total.** Antes o codigo somava UM `GAP`
        // para a lista inteira, enquanto o laco abaixo consome um `GAP` depois de cada
        // campo. Com varios campos a soma passava do `contentH`, a ultima caixa
        // invadia a faixa do rodape e o contador "12/40" aparecia em cima do botao
        // (relato do Mestre em 02/10/2026). O `+ GAP` final e o ar entre a ultima
        // caixa e os botoes.
        int contentH = fields.isEmpty() ? 0
                : singleH + multiCount * (LABEL_H + multiH) + fields.size() * GAP;
        panelH = contentH + TITLE_H + GAP + FOOTER_H + STATUS_H + 2 * PAD;
        if (panelH > this.height - 8) {
            // Janela baixa: a caixa grande e o que cede espaco, porque e a unica
            // que o Mestre nao precisa para ler o campo inteiro de uma vez.
            panelH = Math.max(80, this.height - 8);
            // `fields.size() * GAP` porque o rodape precisa de um gap alem do ultimo campo,
            // senao a ultima caixa nasce colada nele.
            int room = panelH - 2 * PAD - TITLE_H - GAP - FOOTER_H - STATUS_H
                    - (singleH + multiCount * LABEL_H + fields.size() * GAP);
            if (multiCount > 0) {
                multiH = Math.max(MULTI_MIN_H, room / multiCount);
            }
        }
        panelY = Math.max(PAD, (this.height - panelH) / 2);
        int footerY = panelY + panelH - PAD - FOOTER_H - STATUS_H;
        statusY = footerY + FOOTER_H + 1;

        int y = panelY + PAD + TITLE_H + GAP;
        for (Field field : fields) {
            String value = initial.getOrDefault(field.key(), "");
            labels.add(new Label(field.label(), y));
            if (field.multiline()) {
                MultiLineEditBox box = MultiLineEditBox.builder()
                        .setX(boxX)
                        .setY(y + LABEL_H)
                        .setTextColor(CharacterSheetScreen.COL_BOX_TEXT)
                        .setTextShadow(false)
                        .setCursorColor(CharacterSheetScreen.COL_BOX_TEXT)
                        .setShowBackground(true)
                        .setShowDecorations(true)
                        .build(this.font, boxW, multiH, Component.literal(field.label()));
                // **NAO** chamar `setCharacterLimit` aqui. O `MultiLineEditBox` do
                // vanilla 1.21.11 desenha sozinho o contador "usado/maximo" no canto
                // inferior direito da caixa quando ha limite, e o Mestre nao quer
                // esse numero na tela (pedido de 02/10/2026). O limite continua
                // valendo: quem barra agora e o `save`, que recusa com mensagem.
                box.setValue(value);
                // **OBRIGATORIO**: sem addRenderableWidget a caixa nao entra em
                // Screen.children() e nao recebe clique, foco nem digitacao.
                addRenderableWidget(box);
                multiBoxes.put(field.key(), box);
                y += LABEL_H + multiH + GAP;
            } else {
                EditBox box = new EditBox(this.font, boxX, y + LABEL_H, boxW, ROW_H,
                        Component.literal(field.label()));
                box.setMaxLength(Math.max(1, field.maxLength()));
                box.setValue(value);
                addRenderableWidget(box);
                boxes.put(field.key(), box);
                y += LABEL_H + ROW_H + GAP;
            }
        }

        int btnW = Math.max(40, (panelW - 2 * PAD - GAP) / 2);
        addRenderableWidget(Button.builder(Component.literal("Salvar"), b -> save())
                .bounds(panelX + PAD, footerY, btnW, FOOTER_H).build());
        addRenderableWidget(Button.builder(Component.literal("Cancelar"), b -> onClose())
                .bounds(panelX + panelW - PAD - btnW, footerY, btnW, FOOTER_H).build());

        // So o primeiro campo recebe o foco: entrar na tela com o cursor ja na caixa
        // e o que o Mestre espera. `setInitialFocus(null)` nao e seguro, entao o
        // caso sem nenhuma caixa de uma linha simplesmente nao foca nada.
        EditBox first = boxes.isEmpty() ? null : boxes.values().iterator().next();
        if (first != null) {
            setInitialFocus(first);
        }
    }

    /**
     * Largura do campo, a mesma no {@code init} e no {@code render}.
     *
     * <p><b>Por que uma funcao e nao um campo:</b> o contador de caracteres e
     * desenhado no {@code render}, e ele precisa da borda direita do campo. Com a
     * conta repetida nos dois lugares, uma delas mudaria sozinha e o contador
     * pararia de bater com a caixa.
     */
    private int boxWidth() {
        return Math.max(40, panelW - 2 * PAD);
    }

    /** O que esta na caixa da chave, comeca nas de uma linha. */
    private String readValue(Field field) {
        MultiLineEditBox multi = multiBoxes.get(field.key());
        if (multi != null) {
            return multi.getValue();
        }
        EditBox box = boxes.get(field.key());
        return box == null ? "" : box.getValue();
    }

    /**
     * Monta o mapa e devolve.
     *
     * <p><b>Por que so o campo {@code name} e obrigatorio:</b> e o unico que a
     * lista mostra como botao e o unico que o servidor usa como chave da ficha. Os
     * outros podem ficar vazios de proposito (uma caracteristica e um paragrafo, e
     * "ataque com bonus em branco" e uso valido).
     */
    private void save() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Field field : fields) {
            String value = readValue(field);
            if ("name".equals(field.key()) && value.isBlank()) {
                error = "O nome está vazio.";
                return;
            }
            // Substitui o `setCharacterLimit` do vanilla, que desenhava contador na
            // tela. Sem limite na caixa, o corte vem para ca, com aviso em vez de
            // numero colado no rodape.
            if (value.length() > field.maxLength()) {
                error = field.label() + ": máximo de " + field.maxLength()
                        + " caracteres (você escreveu " + value.length() + ").";
                return;
            }
            out.put(field.key(), value);
        }
        error = "";
        if (onSave != null) {
            onSave.accept(out);
        }
        onClose();
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Fundo e painel desenhados aqui (e nao em render) para ficarem ATRAS de
        // todo widget: sao fills opacos, e drawn depois eles tapariam os botoes.
        graphics.fill(0, 0, this.width, this.height, COL_SCREEN_BG);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, COL_PANEL_BG);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + 1, COL_BORDER);
        graphics.fill(panelX, panelY + panelH - 1, panelX + panelW, panelY + panelH, COL_BORDER);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);

        graphics.drawCenteredString(this.font, this.formTitle,
                panelX + panelW / 2, panelY + PAD, COL_TITLE);

        // Rotulo em cima da caixa, alinhado com a borda esquerda dela: desenhado depois
        // de `super.render`, o texto fica por cima do campo (mesmo cuidado do
        // SpellFormScreen#renderLabels).
        for (Label label : labels) {
            graphics.drawString(this.font,
                    this.font.plainSubstrByWidth(label.text(), Math.max(10, boxWidth())),
                    boxX, label.y(), COL_LABEL, false);
        }

        if (!error.isEmpty()) {
            graphics.drawCenteredString(this.font, error, panelX + panelW / 2, statusY, COL_ERROR);
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

    @Override
    public void onClose() {
        this.minecraft.setScreen(parentScreen);
    }
}