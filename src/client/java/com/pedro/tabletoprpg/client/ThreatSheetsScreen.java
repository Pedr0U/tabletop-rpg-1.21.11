package com.pedro.tabletoprpg.client;

import com.pedro.tabletoprpg.RpgNetworking;
import com.pedro.tabletoprpg.SheetModelHolder;
import com.pedro.tabletoprpg.ThreatSheet;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Lista das <b>fichas de ameaca</b> do Mestre (02/10/2026).
 *
 * <p><b>Por que a lista vem do {@link ThreatSheetClientState} e nao de um pedido
 * na hora de desenhar:</b> a resposta do save chega com a tela da FICHA aberta, e o
 * receptor so a entrega a ela. Quem guarda a lista para as duas e o estado do
 * cliente; aqui a tela apenas pede e desenha o que tem.
 *
 * <p><b>Por que o pedido sai no {@code init} e nao em {@code applyList}:</b> e o
 * que garante a lista real mesmo com o cliente ainda sem estado (primeira abertura
 * da sessao). O desenho primeiro usa o estado e so depois a resposta substitui, para
 * nao piscar vazio.
 */
public class ThreatSheetsScreen extends Screen {

    private static final int COL_SCREEN_BG = 0x99000000;
    private static final int COL_PANEL_BG = 0xFF202020;
    private static final int COL_BORDER = 0xFF6B4A2A;
    private static final int COL_LIST_BG = 0xFF161616;
    private static final int COL_TITLE = 0xFFE0C080;
    private static final int COL_MUTED = CharacterSheetScreen.COL_MUTED;
    private static final int COL_ERROR = CharacterSheetScreen.COL_DOWNED;
    private static final int COL_OK = CharacterSheetScreen.COL_EDITABLE;
    private static final int COL_WARN = 0xFFFFA14D;

    private static final int PAD = 6;
    private static final int TITLE_H = 12;
    private static final int ROW_H = 20;
    private static final int GAP = 5;
    private static final int FOOTER_H = 20;
    private static final int STATUS_H = 10;
    private static final int ROW_BTN_W = 24;
    private static final int BAR_W = 4;
    private static final int PANEL_W = 300;

    private final Screen parentScreen;

    /** Widgets das linhas visiveis, para poder remove-los na rolagem. */
    private final List<AbstractWidget> listWidgets = new ArrayList<>();

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int listTop;
    private int listBottom;
    private int visibleRows;
    private int listScroll;
    private int footerY;
    private int statusY;
    private int dragging;

    /**
     * A linha do Del esperando o segundo clique, ou -1.
     *
     * <p><b>Por que e indice e nao nome:</b> o nome pode mudar entre os dois
     * cliques (o Mestre abre a ficha e edita no meio), e apagar a ficha errada e
     * pior do que nao apagar. O indice trava a linha.
     */
    private int deletePending = -1;

    private String status = "";
    private int statusColor = COL_MUTED;

    public ThreatSheetsScreen(Screen parentScreen) {
        super(Component.literal("Fichas de Ameaça"));
        this.parentScreen = parentScreen;
    }

    /** A tela que abriu esta, para o Voltar voltar para ela. */
    public Screen parentScreen() {
        return parentScreen;
    }

    @Override
    protected void init() {
        super.init();
        listWidgets.clear();

        panelW = Math.max(180, Math.min(this.width - 16, PANEL_W));
        panelX = (this.width - panelW) / 2;

        int footerBlock = FOOTER_H + STATUS_H + GAP;
        int chrome = 2 * PAD + TITLE_H + GAP + footerBlock;
        int room = Math.max(ROW_H, this.height - chrome);
        visibleRows = Math.max(1, Math.min(6, room / ROW_H));
        panelH = chrome + visibleRows * ROW_H;
        panelY = Math.max(PAD, (this.height - panelH) / 2);

        listTop = panelY + PAD + TITLE_H + GAP;
        listBottom = listTop + visibleRows * ROW_H;
        footerY = listBottom + GAP;
        statusY = footerY + FOOTER_H + 1;
        listScroll = Math.min(listScroll, maxScroll());

        // Desenha primeiro o que ja existe, para nao piscar vazio enquanto o
        // servidor responde ao pedido que sai logo abaixo.
        rebuildList();
        rebuildFooter();
        requestList();
    }

    /** As fichas do cliente: a fonte unica da lista (ver o javadoc da tela). */
    private List<ThreatSheet> sheets() {
        return ThreatSheetClientState.sheets();
    }

    private int maxScroll() {
        return Math.max(0, sheets().size() - visibleRows);
    }

    /** "Me manda a lista": o pedido e a propria tela, e nao o menu. */
    private void requestList() {
        if (this.minecraft == null || this.minecraft.getConnection() == null) {
            return;
        }
        ClientPlayNetworking.send(new RpgNetworking.ThreatSheetListRequestPayload());
    }

    private void rebuildFooter() {
        int btnW = Math.max(50, (panelW - 2 * PAD - GAP) / 2);
        addRenderableWidget(Button.builder(Component.literal("Nova Ficha"), b -> createNew())
                .bounds(panelX + PAD, footerY, btnW, FOOTER_H).build());
        addRenderableWidget(Button.builder(Component.literal("< Voltar"), b -> onClose())
                .bounds(panelX + panelW - PAD - btnW, footerY, btnW, FOOTER_H).build());
    }

    private void rebuildList() {
        for (AbstractWidget old : listWidgets) {
            removeWidget(old);
        }
        listWidgets.clear();

        List<ThreatSheet> sheets = sheets();
        int nameW = Math.max(40,
                panelW - 2 * PAD - ROW_BTN_W - GAP - BAR_W);
        int to = Math.min(sheets.size(), listScroll + visibleRows);
        // A linha marcada saiu de vista: o "Del?" nao esta mais na tela, e manter o
        // indice faria o proximo clique no Del APAGAR sem o Mestre ter visto a
        // confirmacao. Perdido o "Del?", o proximo clique comeca a confirmacao de novo.
        if (deletePending != -1 && (deletePending < listScroll || deletePending >= to)) {
            deletePending = -1;
        }
        for (int i = listScroll; i < to; i++) {
            ThreatSheet sheet = sheets.get(i);
            int y = listTop + (i - listScroll) * ROW_H;
            int index = i;

            // Mesmo corte dos botoes da ficha: o `Button` desenha o rotulo com
            // `drawCenteredString`, e um nome de 48 caracteres atravessaria a borda.
            Button name = Button.builder(
                            Component.literal(this.font.plainSubstrByWidth(sheet.listLabel(), nameW - 2)),
                            b -> open(sheet))
                    .bounds(panelX + PAD, y, nameW, ROW_H)
                    .tooltip(sheetTooltip(sheet))
                    .build();
            listWidgets.add(name);
            addRenderableWidget(name);

            boolean pending = deletePending == index;
            Button del = Button.builder(
                            Component.literal(pending ? "Del?" : "Del"),
                            b -> onDelete(index))
                    .bounds(name.getRight() + GAP, y, ROW_BTN_W, ROW_H)
                    .build();
            listWidgets.add(del);
            addRenderableWidget(del);
        }
    }

    /**
     * O que o Mestre ve sem abrir a ficha: o que o tooltip mostra.
     *
     * <p><b>Por que duas linhas e nao cinco:</b> o tooltip do jogo tem altura
     * propria e cinco linhas cobrindo o nome do botao escondem a ficha de baixo.
     */
    private Tooltip sheetTooltip(ThreatSheet sheet) {
        return Tooltip.create(
                Component.literal("Tipo: " + sheet.identity().type()
                        + "   Tamanho: " + sheet.identity().size()),
                Component.literal("Deslocamento: " + sheet.identity().speed()
                        + "   HP: " + sheet.vitals().hp() + " / " + sheet.vitals().hpMax()
                        + "   CA: " + sheet.vitals().ca()));
    }

    private void open(ThreatSheet sheet) {
        deletePending = -1;
        this.minecraft.setScreen(new ThreatSheetScreen(this, sheet, sheet.identity().name()));
    }

    private void createNew() {
        deletePending = -1;
        this.minecraft.setScreen(new ThreatSheetScreen(
                this, ThreatSheet.blank(SheetModelHolder.current()), ""));
    }

    /** O primeiro clique marca, o segundo na mesma linha apaga. */
    private void onDelete(int index) {
        List<ThreatSheet> sheets = sheets();
        if (index < 0 || index >= sheets.size()) {
            deletePending = -1;
            rebuildList();
            return;
        }
        if (deletePending != index) {
            deletePending = index;
            rebuildList();
            return;
        }
        deletePending = -1;
        ClientPlayNetworking.send(new RpgNetworking.ThreatSheetDeletePayload(
                sheets.get(index).identity().name()));
    }

    private int thumbHeight() {
        int total = sheets().size();
        if (total <= visibleRows) {
            return listBottom - listTop;
        }
        return Math.max(12, (listBottom - listTop) * visibleRows / total);
    }

    private int thumbTop() {
        int h = listBottom - listTop;
        return listTop + (h - thumbHeight()) * listScroll / Math.max(1, maxScroll());
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int barX = panelX + panelW - PAD - BAR_W - 4;
        int height = listBottom - listTop;
        if (maxScroll() > 0
                && event.x() >= barX && event.x() < barX + 3
                && event.y() >= thumbTop() && event.y() < thumbTop() + thumbHeight()) {
            dragging = 1;
            listScroll = offsetFromMouse(event.y());
            rebuildList();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (dragging != 0) {
            listScroll = offsetFromMouse(event.y());
            rebuildList();
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
        if (mouseY >= listTop && mouseY < listBottom && maxScroll() > 0) {
            // `scrollY` e positivo ao rolar para CIMA, entao descer e `-signum`.
            listScroll = Math.max(0, Math.min(maxScroll(),
                    listScroll + (int) -Math.signum(scrollY)));
            rebuildList();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private int offsetFromMouse(double mouseY) {
        int height = listBottom - listTop;
        if (height <= 0 || maxScroll() <= 0) {
            return 0;
        }
        double ratio = (mouseY - listTop) / height;
        return Math.max(0, Math.min(maxScroll(), (int) Math.round(ratio * maxScroll())));
    }

    // ------------------------------------------------------------------
    // RESPOSTA DO SERVIDOR
    // ------------------------------------------------------------------

    /**
     * A lista chegou; o estado global ja foi publicado pelo receptor.
     *
     * <p><b>Por que o metodo nao tem argumento:</b> quem chama e o receptor de
     * {@code ThreatSheetListPayload}, que escreve no {@link ThreatSheetClientState}
     * antes de chamar. A tela nao guarda uma segunda copia da lista.
     */
    public void applyList() {
        deletePending = -1;
        rebuildList();
    }

    /** O save ou o apag respondeu: a mensagem vai para o rodape. */
    public void applyResult(boolean ok, String message, List<ThreatSheet> sheets) {
        status = message == null ? "" : message;
        statusColor = statusColorFor(ok, status);
        deletePending = -1;
        rebuildList();
    }

    /**
     * A cor da linha de status.
     *
     * <p><b>Por que "inventário cheio" e aviso e nao erro:</b> nesse caso o save
     * foi aceito e a ficha existe; o que faltou foi a entrega do item. Mostrar
     * vermelho diria ao Mestre que o trabalho dele se perdeu.
     */
    static int statusColorFor(boolean ok, String message) {
        if (message == null || message.isEmpty()) {
            return COL_MUTED;
        }
        if (message.contains("inventário cheio")) {
            return COL_WARN;
        }
        return ok ? COL_OK : COL_ERROR;
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
        graphics.fill(panelX + PAD, listTop, panelX + panelW - PAD, listBottom, COL_LIST_BG);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);

        graphics.drawCenteredString(this.font, this.title,
                panelX + panelW / 2, panelY + PAD, COL_TITLE);

        if (sheets().isEmpty()) {
            graphics.drawCenteredString(this.font, "Nenhuma ficha ainda.",
                    panelX + panelW / 2, listTop + 6, COL_MUTED);
        }

        int barX = panelX + panelW - PAD - BAR_W - 4;
        if (maxScroll() > 0) {
            graphics.fill(barX, listTop, barX + 3, listBottom, 0xFF101010);
            graphics.fill(barX, thumbTop(), barX + 3, thumbTop() + thumbHeight(), 0xFF6B4A2A);
        }

        if (!status.isEmpty()) {
            graphics.drawCenteredString(this.font, this.font.plainSubstrByWidth(status,
                    panelW - 2 * PAD), panelX + panelW / 2, statusY, statusColor);
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