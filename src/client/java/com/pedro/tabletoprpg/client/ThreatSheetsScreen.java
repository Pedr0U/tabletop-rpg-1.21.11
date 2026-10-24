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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    /** Altura da "pasta" de copias: menor que a linha, para nao parecer um botao normal. */
    private static final int TOGGLE_H = 14;
    /** Quanto a copia fica recuada em relacao a mae. */
    private static final int COPY_INDENT = 12;
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

    /**
     * Uma linha da lista: a ficha-mae, o botao que abre/fecha as copias dela (so quando
     * ela tem copias, logo abaixo) ou uma copia do grupo aberto.
     */
    private record Row(ThreatSheet sheet, RowKind kind) {}

    private enum RowKind { MOTHER, TOGGLE, COPY }

    /**
     * As maes cujo grupo de copias esta recolhido, pelo id. Vazio = tudo aberto, que
     * e o estado inicial de cada abertura da tela (22/10/2026).
     */
    private final Set<String> collapsedGroups = new HashSet<>();

    private String status = "";
    private int statusColor = COL_MUTED;

    public ThreatSheetsScreen(Screen parentScreen) {
        super(Component.translatable("screen.tabletoprpg.threat_sheets.title"));
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
        return Math.max(0, rows().size() - visibleRows);
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
        addRenderableWidget(Button.builder(Component.translatable("screen.tabletoprpg.threat_sheets.new_sheet"), b -> createNew())
                .bounds(panelX + PAD, footerY, btnW, FOOTER_H).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.tabletoprpg.threat_sheets.back"), b -> onClose())
                .bounds(panelX + panelW - PAD - btnW, footerY, btnW, FOOTER_H).build());
    }

    /** So as fichas MAES (07/10/2026). As copias entram na lista so como linhas do
     *  grupo da mae, em {@link #rows()}. */
    private List<ThreatSheet> mothers() {
        List<ThreatSheet> out = new ArrayList<>();
        for (ThreatSheet sheet : sheets()) {
            if (!sheet.isCopy()) {
                out.add(sheet);
            }
        }
        return out;
    }

    /**
     * As linhas da lista, na ordem em que aparecem: cada mae; logo abaixo dela, o
     * botao de abrir/fechar o grupo (so quando ha copias); e as copias, se o grupo
     * estiver aberto.
     */
    private List<Row> rows() {
        List<Row> out = new ArrayList<>();
        for (ThreatSheet mother : mothers()) {
            out.add(new Row(mother, RowKind.MOTHER));
            if (!mother.hasId()) {
                continue;
            }
            List<ThreatSheet> copies = copiesOf(mother);
            if (copies.isEmpty()) {
                continue;
            }
            out.add(new Row(mother, RowKind.TOGGLE));
            if (collapsedGroups.contains(mother.id())) {
                continue;
            }
            for (ThreatSheet copy : copies) {
                out.add(new Row(copy, RowKind.COPY));
            }
        }
        return out;
    }

    /** As copias da mae, na ordem da lista. */
    private List<ThreatSheet> copiesOf(ThreatSheet mother) {
        List<ThreatSheet> out = new ArrayList<>();
        for (ThreatSheet sheet : sheets()) {
            if (sheet.hasId() && mother.id().equals(sheet.copyOf())) {
                out.add(sheet);
            }
        }
        return out;
    }

    /**
     * Abre ou fecha o grupo de copias de uma mae. O "Del?" pendente e desarmado, porque
     * a linha que ele confirmava pode ter saido da tela.
     */
    private void toggleGroup(ThreatSheet mother) {
        if (collapsedGroups.contains(mother.id())) {
            collapsedGroups.remove(mother.id());
        } else {
            collapsedGroups.add(mother.id());
        }
        deletePending = -1;
        rebuildList();
    }

    private void rebuildList() {
        for (AbstractWidget old : listWidgets) {
            removeWidget(old);
        }
        listWidgets.clear();

        // O grupo fechado encurta a lista: a rolagem nao pode ficar alem do fim novo.
        listScroll = Math.min(listScroll, maxScroll());
        // Mae, botao de grupo e copia dividem o mesmo nome/Del; a bolinha amarela
        // vive DENTRO do botao do nome (o widget DotWidget).
        List<Row> rows = rows();
        int nameW = Math.max(40,
                panelW - 2 * PAD - ROW_BTN_W - GAP - BAR_W);
        int to = Math.min(rows.size(), listScroll + visibleRows);
        // A linha marcada saiu de vista: o "Del?" nao esta mais na tela, e manter o
        // indice faria o proximo clique no Del APAGAR sem o Mestre ter visto a
        // confirmacao. Perdido o "Del?", o proximo clique comeca a confirmacao de novo.
        if (deletePending != -1 && (deletePending < listScroll || deletePending >= to)) {
            deletePending = -1;
        }
        for (int i = listScroll; i < to; i++) {
            Row row = rows.get(i);
            ThreatSheet sheet = row.sheet();
            int y = listTop + (i - listScroll) * ROW_H;
            int index = i;

            // O botao de abrir/fechar o grupo e uma "pasta" compacta, alinhada a esquerda
            // sob a mae: a largura e so a do texto dele, e a altura e TOGGLE_H.
            if (row.kind() == RowKind.TOGGLE) {
                boolean collapsed = collapsedGroups.contains(sheet.id());
                Component label = Component.translatable(collapsed
                                ? "screen.tabletoprpg.threat_sheets.copies_collapsed"
                                : "screen.tabletoprpg.threat_sheets.copies_expanded",
                        copiesOf(sheet).size());
                Button toggle = Button.builder(label, b -> toggleGroup(sheet))
                        .bounds(panelX + PAD, y + (ROW_H - TOGGLE_H) / 2,
                                this.font.width(label) + 12, TOGGLE_H).build();
                listWidgets.add(toggle);
                addRenderableWidget(toggle);
                continue;
            }
            // A copia fica recuada; o Del dela cai na mesma coluna do Del da mae.
            int indent = row.kind() == RowKind.COPY ? COPY_INDENT : 0;

            // Mesmo corte dos botoes da ficha: o `Button` desenha o rotulo com
            // `drawCenteredString`, e um nome de 48 caracteres atravessaria a borda.
            Button name = Button.builder(
                            Component.literal(this.font.plainSubstrByWidth(sheet.listLabel(), nameW - indent - 2)),
                            b -> open(sheet))
                    .bounds(panelX + PAD + indent, y, nameW - indent, ROW_H)
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

            // Bolinha amarela de "edicao nao salva" DENTRO do botao do nome,
            // pintada POR CIMA dele (o desenho da tela acontece antes dos widgets).
            // O rascunho e guardado por id: a bolinha volta quando o Mestre sai da
            // ficha sem salvar (07/10/2026).
            DotWidget dot = new DotWidget(panelX + PAD + nameW - 8, y + ROW_H / 2 - 2,
                    () -> ThreatSheetClientState.hasDraft(sheet.id()));
            listWidgets.add(dot);
            addRenderableOnly(dot);
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
                Component.translatable("screen.tabletoprpg.threat_sheets.tooltip_identity",
                        sheet.identity().type(), sheet.identity().size()),
                Component.translatable("screen.tabletoprpg.threat_sheets.tooltip_vitals",
                        sheet.identity().speed(), sheet.vitals().hp(), sheet.vitals().hpMax(),
                        sheet.vitals().ca()));
    }

    private void open(ThreatSheet sheet) {
        deletePending = -1;
        this.minecraft.setScreen(new ThreatSheetScreen(this, sheet, sheet.identity().name()));
    }

    /**
     * "Nova Ficha" (07/10/2026): o servidor cria a ficha em branco "Ficha N" no
     * clique. O nome nasce no servidor para a lista mostrar a ficha mesmo quando o
     * Mestre fecha o editor sem salvar; a resposta abre o editor na ficha criada.
     */
    private void createNew() {
        deletePending = -1;
        if (this.minecraft == null || this.minecraft.getConnection() == null) {
            return;
        }
        status = "Criando ficha...";
        statusColor = COL_MUTED;
        ClientPlayNetworking.send(new RpgNetworking.ThreatSheetNewPayload());
    }

    /** O primeiro clique marca, o segundo na mesma linha apaga. */
    private void onDelete(int index) {
        List<Row> rows = rows();
        if (index < 0 || index >= rows.size()) {
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
                rows.get(index).sheet().identity().name()));
    }

    private int thumbHeight() {
        int total = rows().size();
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

    /**
     * O save, o apag ou o "Nova Ficha" respondeu.
     *
     * <p><b>O que o {@code createdName} faz:</b> quando o "Nova Ficha" cria a ficha
     * "Ficha N" no servidor, o nome criado volta aqui e a tela abre o editor nela.
     */
    public void applyResult(boolean ok, String message, List<ThreatSheet> sheets,
                            String createdName) {
        status = message == null ? "" : message;
        statusColor = statusColorFor(ok, status);
        deletePending = -1;
        if (ok && createdName != null && !createdName.isBlank()) {
            for (ThreatSheet sheet : sheets) {
                if (createdName.equals(sheet.identity().name())) {
                    open(sheet);
                    return;
                }
            }
        }
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

        if (mothers().isEmpty()) {
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