package com.pedro.tabletoprpg.client;

import com.pedro.tabletoprpg.SheetModel;
import com.pedro.tabletoprpg.SheetModelHolder;
import com.pedro.tabletoprpg.ThreatSheet;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Editor das <b>pericias</b> da ficha de ameaca (02/10/2026).
 *
 * <p><b>Por que uma tela so, e nao um campo na ficha:</b> o par "pericia + valor" se
 * repete um numero indefinido de vezes (ate {@link ThreatSheet#MAX_PERICIAS}), e a
 * tela da ficha ja tem duas colunas cheias. Uma linha a menos na ficha e uma lista
 * com rolagem aqui.
 *
 * <p><b>Por que a escolha e por botao e nao digitando:</b> o id da pericia e um
 * identificador interno ({@code pericia_7}); digitar isso seria pedir para o Mestre
 * acertar uma chave que ele nunca vai ver. O botao mostra o nome que ele reconhece.
 *
 * <p><b>Por que o valor vive numa caixa e nao em setas:</b> o valor vai de -999 a
 * 999 (pedido de 02/10/2026) e o ajuste fino importa: setas de 1 em 1 levariam
 * mil cliques.
 */
public class ThreatPericiasScreen extends Screen {

    /**
     * Uma linha em edicao.
     *
     * <p><b>Por que {@code id} e texto e nao {@code null}:</b> o id nunca e null, e
     * a linha ainda sem escolha e a que o botao mostra como
     * {@code *Escolha a Perícia*}.
     */
    private static final class Row {
        private String id = "";
        private String value = "0";

        Row(String id, int value) {
            this.id = id == null ? "" : id;
            this.value = Integer.toString(value);
        }
    }

    private static final int COL_SCREEN_BG = 0x99000000;
    private static final int COL_PANEL_BG = 0xFF202020;
    private static final int COL_BORDER = 0xFF6B4A2A;
    private static final int COL_LIST_BG = 0xFF161616;
    private static final int COL_TITLE = 0xFFE0C080;
    private static final int COL_SECTION = CharacterSheetScreen.COL_SECTION;
    private static final int COL_MUTED = CharacterSheetScreen.COL_MUTED;
    private static final int COL_ERROR = CharacterSheetScreen.COL_DOWNED;

    private static final int PAD = 6;
    private static final int TITLE_H = 12;
    private static final int ROW_H = 18;
    private static final int GAP = 5;
    private static final int FOOTER_H = 20;
    private static final int STATUS_H = 10;
    private static final int VALUE_W = 46;
    private static final int DEL_W = 24;
    private static final int BAR_W = 4;
    /** Altura da linha de rotulo acima da paleta de pericias. */
    private static final int PALETTE_LABEL_H = 10;
    /** Quantas linhas de botoes a paleta de pericias mostra de uma vez. */
    private static final int PALETTE_LINES = 4;
    private static final int PANEL_W = 300;

    private final Screen parentScreen;
    private final Consumer<List<ThreatSheet.PericiaValue>> onSave;

    private final List<Row> rows = new ArrayList<>();
    /** Widgets das linhas visiveis, para poder remove-los na rolagem. */
    private final List<AbstractWidget> rowWidgets = new ArrayList<>();
    /** As caixas de valor, na ordem das linhas visiveis. */
    private final List<EditBox> valueBoxes = new ArrayList<>();
    /** Widgets da paleta aberta, pelo mesmo motivo. */
    private final List<AbstractWidget> paletteWidgets = new ArrayList<>();

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int listTop;
    private int listBottom;
    private int visibleRows;
    private int listScroll;
    private int paletteTop;
    private int paletteBottom;
    private int palettePerRow = 1;
    private int paletteScroll;
    /** A linha cuja paleta esta aberta, ou -1. */
    private int paletteFor = -1;
    /** Qual regiao esta sendo arrastada: 0 nenhuma, 1 lista, 2 paleta. */
    private int dragging;
    private int footerY;
    private int statusY;

    private String error = "";

    public ThreatPericiasScreen(Screen parentScreen, List<ThreatSheet.PericiaValue> current,
                                Consumer<List<ThreatSheet.PericiaValue>> onSave) {
        super(Component.literal("Perícias da Ameaça"));
        this.parentScreen = parentScreen;
        this.onSave = onSave;
        if (current != null) {
            for (ThreatSheet.PericiaValue value : current) {
                rows.add(new Row(value.id(), value.value()));
            }
        }
    }

    @Override
    protected void init() {
        super.init();
        rowWidgets.clear();
        valueBoxes.clear();
        paletteWidgets.clear();

        panelW = Math.max(180, Math.min(this.width - 16, PANEL_W));
        panelX = (this.width - panelW) / 2;

        int paletteH = PALETTE_LABEL_H + PALETTE_LINES * (ROW_H + GAP);
        int footerBlock = FOOTER_H + STATUS_H + GAP;
        int chrome = 2 * PAD + TITLE_H + GAP + paletteH + GAP + footerBlock;
        // A lista e a unica parte que encolhe: titulo, paleta e rodape tem
        // altura fixa, porque sao eles que o Mestre precisa ver inteiros.
        int room = Math.max(ROW_H, this.height - chrome);
        visibleRows = Math.max(1, room / ROW_H);
        panelH = chrome + visibleRows * ROW_H;
        panelY = Math.max(PAD, (this.height - panelH) / 2);

        listTop = panelY + PAD + TITLE_H + GAP;
        listBottom = listTop + visibleRows * ROW_H;
        paletteTop = listBottom + GAP;
        paletteBottom = paletteTop + paletteH;
        // 6 px alem do `GAP`: a ultima caixa da paleta nascia colada nos botoes do rodape
        // (relato do Mestre em 02/10/2026).
        footerY = paletteBottom + GAP + 6;
        statusY = footerY + FOOTER_H + 1;

        palettePerRow = Math.max(1, (panelW - 2 * PAD) / 110);
        listScroll = Math.min(listScroll, listMaxScroll());
        paletteScroll = Math.min(paletteScroll, paletteMaxScroll());
        if (paletteFor >= rows.size()) {
            paletteFor = -1;
        }

        rebuildList();
        rebuildPalette();

        int addW = 84;
        int btnW = Math.max(40, (panelW - 2 * PAD - 2 * GAP - addW) / 2);
        addRenderableWidget(Button.builder(Component.literal("+ Adicionar"), b -> addRow())
                .bounds(panelX + PAD, footerY, addW, FOOTER_H).build());
        addRenderableWidget(Button.builder(Component.literal("Salvar"), b -> save())
                .bounds(panelX + PAD + addW + GAP, footerY, btnW, FOOTER_H).build());
        addRenderableWidget(Button.builder(Component.literal("< Voltar"), b -> onClose())
                .bounds(panelX + panelW - PAD - btnW, footerY, btnW, FOOTER_H).build());
    }

    private SheetModel model() {
        return SheetModelHolder.current();
    }

    private int listMaxScroll() {
        return Math.max(0, rows.size() - visibleRows);
    }

    private int paletteMaxScroll() {
        int lines = (model().pericias().size() + palettePerRow - 1) / palettePerRow;
        return Math.max(0, lines - PALETTE_LINES);
    }

    /**
     * O nome que o Mestre le da pericia, ou o id entre colchetes.
     *
     * <p><b>Por que o id entre colchetes e nao o id cru:</b> se o Mestre apagou a
     * pericia no Sheet Editor depois de usar na ficha, mostrar {@code pericia_7} nu
     * parece bug de tela. Entre colchetes diz "isto nao existe mais" e a linha ainda
     * pode ser apagada.
     */
    private String labelOf(String id) {
        SheetModel.PericiaDef def = model().periciaById(id);
        if (def != null && !def.name().isEmpty()) {
            return def.name();
        }
        return "[" + id + "]";
    }

    /** Guarda o texto digitado nas caixas antes de recriar os widgets. */
    private void syncRows() {
        for (int i = 0; i < valueBoxes.size(); i++) {
            int index = listScroll + i;
            if (index < rows.size()) {
                rows.get(index).value = valueBoxes.get(i).getValue();
            }
        }
    }

    private void rebuildList() {
        syncRows();
        for (AbstractWidget old : rowWidgets) {
            removeWidget(old);
        }
        rowWidgets.clear();
        valueBoxes.clear();

        int nameW = Math.max(30,
                panelW - 2 * PAD - VALUE_W - DEL_W - 2 * GAP - BAR_W);
        int to = Math.min(rows.size(), listScroll + visibleRows);
        for (int i = listScroll; i < to; i++) {
            Row row = rows.get(i);
            int y = listTop + (i - listScroll) * ROW_H;
            int index = i;

            addRowWidget(Button.builder(
                            Component.literal(row.id.isEmpty()
                                    ? "*Escolha a Perícia*" : labelOf(row.id)),
                            b -> togglePalette(index))
                    .bounds(panelX + PAD, y, nameW, ROW_H)
                    .build());

            EditBox box = new EditBox(this.font, panelX + PAD + nameW + GAP, y, VALUE_W, ROW_H,
                    Component.literal("Valor"));
            box.setMaxLength(ThreatSheet.VALUE_CHARS);
            // `-?\d{0,4}` e o mesmo limite do dado: 4 caracteres com sinal, que e
            // o que cabe em -999..999. O sinal tambem e aceito COLADO no fim ("5-"),
            // porque antes o filtro devolvia o sinal e o Mestre entendia que numero
            // negativo nao era aceito.
            box.setFilter(s -> s.matches("-?\\d{0,4}-?"));
            box.setValue(row.value);
            addRowWidget(box);
            valueBoxes.add(box);

            addRowWidget(Button.builder(Component.literal("Del"), b -> removeRow(index))
                    .bounds(box.getRight() + GAP, y, DEL_W, ROW_H).build());
        }
    }

    private void addRowWidget(AbstractWidget widget) {
        rowWidgets.add(widget);
        addRenderableWidget(widget);
    }

    private void rebuildPalette() {
        for (AbstractWidget old : paletteWidgets) {
            removeWidget(old);
        }
        paletteWidgets.clear();
        if (paletteFor < 0) {
            return;
        }
        List<SheetModel.PericiaDef> options = model().pericias();
        int btnW = Math.max(40, (panelW - 2 * PAD - (palettePerRow - 1) * GAP) / palettePerRow);
        int from = paletteScroll * palettePerRow;
        int to = Math.min(options.size(), from + PALETTE_LINES * palettePerRow);
        for (int i = from; i < to; i++) {
            int line = i / palettePerRow - paletteScroll;
            int col = i % palettePerRow;
            int chosen = paletteFor;
            String id = options.get(i).id();
            Button button = Button.builder(Component.literal(options.get(i).name()),
                            b -> choose(chosen, id))
                    .bounds(panelX + PAD + col * (btnW + GAP),
                            paletteTop + PALETTE_LABEL_H + line * (ROW_H + GAP),
                            btnW, ROW_H)
                    .build();
            paletteWidgets.add(button);
            addRenderableWidget(button);
        }
    }

    private void togglePalette(int index) {
        paletteFor = paletteFor == index ? -1 : index;
        paletteScroll = 0;
        rebuildPalette();
    }

    private void choose(int rowIndex, String id) {
        if (rowIndex >= 0 && rowIndex < rows.size()) {
            rows.get(rowIndex).id = id;
        }
        paletteFor = -1;
        rebuildPalette();
        rebuildList();
    }

    private void removeRow(int index) {
        if (index < 0 || index >= rows.size()) {
            return;
        }
        rows.remove(index);
        if (paletteFor > index || paletteFor >= rows.size()) {
            paletteFor = -1;
        }
        rebuildList();
        rebuildPalette();
    }

    private void addRow() {
        if (rows.size() >= ThreatSheet.MAX_PERICIAS) {
            error = "A ficha chegou no limite de perícias.";
            return;
        }
        Row row = new Row("", 0);
        // A linha recem-criada comeca vazia, e nao com 0: o Mestre ainda nao
        // escolheu a pericia, e um 0 ja salvo parece um valor confirmado.
        row.value = "";
        rows.add(row);
        rebuildList();
    }

    /**
     * Confere as linhas e devolve.
     *
     * <p><b>Por que conferir no cliente:</b> a linha sem pericia escolhida nao tem
     * id, e um id vazio passaria pelo record e viraria uma pericia invisivel na
     * ficha salva. O servidor nao tem como adivinhar qual das duas linhas o Mestre
     * queria.
     */
    private void save() {
        // As caixas viram texto aqui, ANTES de ler `rows`. Sem isso o `row.value` ainda
        // era o valor antigo: o Mestre digitava, clicava Salvar e o numero nao chegava
        // no `onSave` -- so voltava depois de alguma rolagem, que e quando
        // `rebuildList()` chama `syncRows()`. Ler o modelo antes de ler a tela era
        // trocar a fonte da verdade.
        syncRows();
        List<ThreatSheet.PericiaValue> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (row.id.isBlank()) {
                error = "Escolha a perícia da linha " + (i + 1) + ".";
                return;
            }
            int value;
            try {
                // O filtro aceita o sinal no fim ("5-") e o Mestre pode colar o "+"
                // que a ficha mostra nos positivos. Normaliza aqui: o "-" vale em
                // qualquer ponta, o "+" so e ignorado.
                String raw = row.value.trim();
                boolean negative = raw.startsWith("-") || raw.endsWith("-");
                String digits = raw.replaceAll("[^0-9]", "");
                value = Integer.parseInt((negative ? "-" : "") + (digits.isEmpty() ? "0" : digits));
            } catch (NumberFormatException e) {
                error = "Valor inválido na linha " + (i + 1) + ".";
                return;
            }
            if (value < ThreatSheet.VALUE_MIN || value > ThreatSheet.VALUE_MAX) {
                error = "Valor fora do limite na linha " + (i + 1) + ".";
                return;
            }
            out.add(new ThreatSheet.PericiaValue(row.id, value));
        }
        error = "";
        if (onSave != null) {
            onSave.accept(out);
        }
        onClose();
    }

    // ------------------------------------------------------------------
    // ROLAGEM
    // ------------------------------------------------------------------

    /** Altura do polegar de uma regiao com {@code total} linhas e {@code visible} visiveis. */
    private static int thumbHeight(int height, int total, int visible) {
        if (height <= 0 || total <= visible) {
            return height;
        }
        return Math.max(12, height * visible / total);
    }

    private int thumbY(int top, int height, int thumbH, int offset, int max) {
        return top + (height - thumbH) * offset / Math.max(1, max);
    }

    /** O trilho e o polegar: e o que diz ao Mestre que a lista continua. */
    private void drawBar(GuiGraphics graphics, int x, int top, int height,
                         int offset, int max, int total, int visible) {
        if (max <= 0 || height <= 0) {
            return;
        }
        graphics.fill(x, top, x + 3, top + height, 0xFF101010);
        int thumbH = thumbHeight(height, total, visible);
        int thumbTop = thumbY(top, height, thumbH, offset, max);
        graphics.fill(x, thumbTop, x + 3, thumbTop + thumbH, 0xFF6B4A2A);
    }

    private boolean overBar(double mouseX, double mouseY, int x, int top, int height,
                            int offset, int max, int total, int visible) {
        if (max <= 0 || height <= 0) {
            return false;
        }
        int thumbH = thumbHeight(height, total, visible);
        int thumbTop = thumbY(top, height, thumbH, offset, max);
        return mouseX >= x && mouseX < x + 3 && mouseY >= thumbTop && mouseY < thumbTop + thumbH;
    }

    /** O offset que o mouse indica: mesma conta do polegar, invertida. */
    private int offsetFromMouse(double mouseY, int top, int height, int max) {
        if (max <= 0 || height <= 0) {
            return 0;
        }
        double ratio = (mouseY - top) / height;
        return Math.max(0, Math.min(max, (int) Math.round(ratio * max)));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int listH = listBottom - listTop;
        int barX = panelX + panelW - PAD - BAR_W - 4;
        if (overBar(event.x(), event.y(), barX, listTop, listH,
                listScroll, listMaxScroll(), rows.size(), visibleRows)) {
            dragging = 1;
            listScroll = offsetFromMouse(event.y(), listTop, listH, listMaxScroll());
            rebuildList();
            return true;
        }
        int paletteH = paletteBottom - paletteTop - PALETTE_LABEL_H;
        if (paletteFor >= 0 && overBar(event.x(), event.y(), barX,
                paletteTop + PALETTE_LABEL_H, paletteH, paletteScroll, paletteMaxScroll(),
                (model().pericias().size() + palettePerRow - 1) / palettePerRow, PALETTE_LINES)) {
            dragging = 2;
            paletteScroll = offsetFromMouse(event.y(), paletteTop + PALETTE_LABEL_H,
                    paletteH, paletteMaxScroll());
            rebuildPalette();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (dragging == 1) {
            listScroll = offsetFromMouse(event.y(), listTop, listBottom - listTop, listMaxScroll());
            rebuildList();
            return true;
        }
        if (dragging == 2) {
            paletteScroll = offsetFromMouse(event.y(), paletteTop + PALETTE_LABEL_H,
                    paletteBottom - paletteTop - PALETTE_LABEL_H, paletteMaxScroll());
            rebuildPalette();
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging != 0) {
            dragging = 0;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (paletteFor >= 0 && mouseY >= paletteTop && mouseY < paletteBottom
                && paletteMaxScroll() > 0) {
            paletteScroll = Math.max(0, Math.min(paletteMaxScroll(),
                    paletteScroll + (int) -Math.signum(scrollY)));
            rebuildPalette();
            return true;
        }
        if (mouseY >= listTop && mouseY < listBottom && listMaxScroll() > 0) {
            listScroll = Math.max(0,
                    Math.min(listMaxScroll(), listScroll + (int) -Math.signum(scrollY)));
            rebuildList();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // ------------------------------------------------------------------
    // DESENHO
    // ------------------------------------------------------------------

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, COL_SCREEN_BG);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, COL_PANEL_BG);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + 1, COL_BORDER);
        graphics.fill(panelX, panelY + panelH - 1, panelX + panelW, panelY + panelH, COL_BORDER);
        // O fundo das listas ANTES dos widgets: `fill` e opaco, e desenhado depois
        // taparia os botoes, que continuariam clicaveis e invisiveis.
        graphics.fill(panelX + PAD, listTop, panelX + panelW - PAD, listBottom, COL_LIST_BG);
        if (paletteFor >= 0) {
            graphics.fill(panelX + PAD, paletteTop, panelX + panelW - PAD, paletteBottom, COL_LIST_BG);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);

        graphics.drawCenteredString(this.font, this.title,
                panelX + panelW / 2, panelY + PAD, COL_TITLE);

        if (rows.isEmpty()) {
            graphics.drawCenteredString(this.font, "Nenhuma perícia ainda.",
                    panelX + panelW / 2, listTop + 5, COL_MUTED);
        }
        if (paletteFor >= 0 && paletteFor < rows.size()) {
            graphics.drawString(this.font, "Perícia da linha " + (paletteFor + 1) + ":",
                    panelX + PAD + 2, paletteTop + 1, COL_SECTION, false);
        }

        int barX = panelX + panelW - PAD - BAR_W - 4;
        drawBar(graphics, barX, listTop, listBottom - listTop,
                listScroll, listMaxScroll(), rows.size(), visibleRows);
        if (paletteFor >= 0) {
            drawBar(graphics, barX, paletteTop + PALETTE_LABEL_H,
                    paletteBottom - paletteTop - PALETTE_LABEL_H,
                    paletteScroll, paletteMaxScroll(),
                    (model().pericias().size() + palettePerRow - 1) / palettePerRow,
                    PALETTE_LINES);
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