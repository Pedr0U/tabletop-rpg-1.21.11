package com.pedro.tabletoprpg.client;

import com.pedro.tabletoprpg.DiaryEntry;
import com.pedro.tabletoprpg.DiaryStore;
import com.pedro.tabletoprpg.RpgNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>Tela 1</b> do Diario: a tela principal, que lista as Secoes e cria uma (02/10/2026).
 *
 * <p><b>Por que a busca e um filtro, e nao uma busca que abre:</b> a jogadora pediu que a
 * busca compare em tempo real com o titulo de cada secao criada. Abrir a secao direto do
 * texto digitado tiraria dela o passo de escolher, e "criar" e "achar" virariam o mesmo
 * gesto -- com o risco de ela criar duas secoes com nomes parecidos sem perceber.
 *
 * <p><b>Por que a busca olha so o titulo:</b> foi o que ela pediu. A descricao e um bloco
 * de texto longo, e comparar o campo inteiro em tempo real a cada tecla deixaria a lista
 * tremer sem parar. (Se um dia ela pedir busca na descricao, e uma linha em
 * {@link DiaryStore#filtered} -- nao uma segunda implementacao.)
 *
 * <p><b>Por que a lista e filtrada E ordenada aqui, e nao no servidor:</b> a busca e um
 * estado local da tela; mandar cada tecla ao servidor seria um pacote por caractere. O
 * criterio de ordenacao, esse sim, e a regra do {@link DiaryStore} -- aplicada sobre a copia
 * que o servidor mandou, para tela e servidor concordarem por construcao.
 */
public class DiaryScreen extends DiaryScreenBase {

    /** De onde a tela foi aberta. {@code null} significa "voltar para o jogo". */
    private EditBox searchBox;
    private EditBox titleBox;
    private MultiLineEditBox descBox;

    private Button sortButton;
    private Button closeButton;
    private Button createButton;
    private Button saveButton;

    // Rotulos posicionados no `init` e desenhados no `render`. Sao campos porque o desenho
    // acontece a cada quadro e nao pode refazer a conta do `layout`.
    private int legendY;
    private int searchLabelX;
    private int searchLabelY;
    private int titleLabelX;
    private int titleLabelY;
    private int descLabelX;
    private int descLabelY;

    /**
     * O texto de busca que a lista visivel ja reflete.
     *
     * <p><b>Por que guardar:</b> a lista e filtrada por quadro, e recriar os botoes dos
     * cards a cada quadro seria desperdicio. Comparar com o texto ja aplicado e o que faz o
     * filtro mudar "em tempo real" sem recriar nada enquanto ela nao digita.
     */
    private String appliedQuery = "";

    public DiaryScreen(Screen parentScreen, List<DiaryEntry> known) {
        super(parentScreen);
        if (known != null) {
            this.entries = known;
        }
    }

    /** Chamado pelo botao do menu. */
    public static DiaryScreen open(Screen parentScreen) {
        DiaryScreen screen = new DiaryScreen(parentScreen, null);
        screen.requestState();
        return screen;
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        // Tudo que nao e lista, medido de cima para baixo. A ordem e a da pilha vertical e
        // tem de bater com `layout()` abaixo, senao um campo cai em cima da lista.
        int chrome = TITLE_H + GAP                 // titulo + [X]
                + FIELD_H + GAP                    // Busca
                + LEGEND_H + TIGHT_GAP             // "Secoes:" + ordenar
                + TIGHT_GAP                        // folga antes da lista
                + LBL_ROW + FIELD_H + GAP    // Titulo (rotulo na linha de cima)
                + LBL_ROW + DESC_MIN + GAP + 12         // Descricao (rotulo na linha de cima)
                + ROW_H                             // rodape
                + PAD;                               // folga antes da borda
        // Esta conta e a MESMA do bloco de campos la embaixo. Os dois `LBL_ROW` sao o que
        // paga o rotulo em linha propria; sem eles no `chrome`, os campos caem em cima do
        // rodape. Ver `check-diary-layout.ps1`.
        layoutPanel(chrome);

        // `usable` DEPOIS de `layoutPanel`, nunca antes. `panelWidth` e zero na primeira
        // `init()` de uma tela nova, entao ler antes dava `usable = -12`: o campo de Titulo
        // caia no piso de 60 px, o `boxX` saia negativo (campo para fora da tela) e a caixa
        // de Descricao nascia com LARGURA NEGATIVA e nao aparecia. Numa segunda `init()`
        // da mesma tela o `panelWidth` ja estava certo, e era por isso que o campo de
        // Descricao aparecia "normalmente" depois de Esc. (02/10/2026)
        int usable = panelWidth - 2 * PAD;

        int y = panelY + PAD;

        // O [X] na mesma faixa do titulo. O titulo e centralizado e o botao fica na ponta
        // direita: em 340 px de painel os dois nao se tocam.
        closeButton = addRenderableWidget(Button.builder(Component.literal(LBL_CLOSE), b -> onClose())
                .bounds(panelX + panelWidth - PAD - CARD_BTN_W, y, CARD_BTN_W, TITLE_H + 2)
                .build());
        y += TITLE_H + GAP;

        // Busca: rotulo e campo centralizados como um BLOCO. Alinhar so o campo punha o
        // "Busca:" para fora do painel, porque o rotulo e mais largo que o espaco sobrando
        // de um lado (a mesma conta da tela de Presets).
        int labelW = this.font.width(LBL_SEARCH);
        int boxW = Math.max(60, usable - labelW - TIGHT_GAP);
        int boxX = panelX + PAD + (usable - (labelW + boxW)) / 2 + labelW;
        searchBox = new EditBox(this.font, boxX, y, boxW, FIELD_H, Component.literal(LBL_SEARCH));
        // OBRIGATORIO: sem `addRenderableWidget` a caixa nao entra no ciclo de desenho nem
        // no de clique.
        addRenderableWidget(searchBox);
        this.searchLabelX = boxX - TIGHT_GAP - labelW;
        this.searchLabelY = y + 5;
        y += FIELD_H + GAP;

        legendY = y;
        rebuildSortButton();
        // UM `GAP` entre a legenda e a lista, e nao dois. O segundo custava 6 px de altura
        // que a Tela 1 nao tinha: com o rodape de 4 botoes e a lista ja no minimo de uma
        // linha, o painel passava de 240 px. Ver `check-diary-layout.ps1`.
        y += LEGEND_H + TIGHT_GAP;

        placeList(y);
        // Espaço entre o fim da lista e o rótulo "Título": muito menor
        y = listBottom + LBL_ROW + FIELD_H - 20;

        // Titulo e Descricao com o ROTULO EM LINHA PROPRIA ACIMA, pedido pela jogadora, e
        // nao ao lado. E a unica disposicao em que o campo fica com a largura toda do quadro
        // (`usable`), sem nenhum espaco reservado para o rotulo. (02/10/2026)
        //
        // O rotulo NAO pode mais ser desenhado a uma distancia fixa acima da caixa: da
        // ultima vez ele era `descTop - STATUS_H`, com a caixa so `TIGHT_GAP` abaixo do
        // campo de cima, e o texto invadia o campo de cima. Aqui o rotulo tem uma LINHA
        // propria (`LBL_ROW`) e a caixa comeca logo abaixo dela, entao a sobreposicao e
        //geometricamente impossivel e nao depende de calibrar uma constante.
        this.titleLabelX = panelX + PAD;
        this.titleLabelY = y;
        titleBox = new EditBox(this.font, panelX + PAD, y + LBL_ROW, usable, FIELD_H,
                Component.literal(LBL_TITLE));
        titleBox.setMaxLength(DiaryEntry.MAX_TITLE);
        addRenderableWidget(titleBox);
        y += LBL_ROW + FIELD_H + GAP;

        this.descLabelX = panelX + PAD;
        this.descLabelY = y;
        // Margem a direita para o contador do MultiLineEditBox nao invadir o botao Ordenar
        int descW = usable - SB_W - TIGHT_GAP;
        descBox = MultiLineEditBox.builder()
                .setX(panelX + PAD)
                .setY(y + LBL_ROW)
                .setPlaceholder(Component.literal(LBL_DESC))
                .setTextColor(0xFFD0D0D0)
                .setTextShadow(false)
                .setCursorColor(0xFFD0D0D0)
                .setShowBackground(true)
                .setShowDecorations(true)
                .build(this.font, descW, descHeight, Component.literal(LBL_DESC));
        descBox.setCharacterLimit(DiaryEntry.MAX_DESCRIPTION);
        addRenderableWidget(descBox);
        y += LBL_ROW + descHeight + GAP + 12;

        footerY = y;
        rebuildFooter();

        clampScroll();
        rebuildList();
        logLayout("DiaryScreen");
    }

    private void rebuildSortButton() {
        if (sortButton != null) {
            removeWidget(sortButton);
        }
        int w = this.font.width(sortMode.label()) + BTN_TEXT_PAD;
        sortButton = Button.builder(Component.literal(sortMode.label()), b -> toggleSort())
                .bounds(panelX + panelWidth - PAD - w, legendY, w, LEGEND_H - 2)
                .build();
        addRenderableWidget(sortButton);
    }

    @Override
    protected void rebuildFooter() {
        clearFooterWidgets();
        if (sortButton != null) {
            // O rodape e recriado a cada estado do servidor, e o botao de ordenar fica na
            // MESMA linha da legenda: recria-lo junto evita deixar um widget orfao quando o
            // rodape inteiro muda.
            removeWidget(sortButton);
            rebuildSortButton();
        }

        int backW = this.font.width(LBL_BACK) + BTN_TEXT_PAD;
        int createW = this.font.width(LBL_NEW_SECTION) + BTN_TEXT_PAD;
        int saveW = this.font.width(LBL_SAVE) + BTN_TEXT_PAD;
        int undoW = podeReverter() ? this.font.width(LBL_UNDO) + BTN_TEXT_PAD : 0;

        addFooterButton(LBL_BACK, panelX + PAD, footerY, backW, this::onClose);

        int rightX = panelX + panelWidth - PAD - createW;
        createButton = addFooterButton(LBL_NEW_SECTION, rightX, footerY, createW, this::createSection);
        // O [Salvar] da Tela 1 e o MESMO botao da tela de no: aceitar rascunho. Aqui ele
        // nao tem texto proprio para enviar -- os campos desta tela sao o formulario de
        // CRIAR, e o que a jogadora editou esta em outra secao e ja foi gravado no
        // autosave. Ele existe so para dar o "esvazia o Reverter" em uma tela so, sem
        // obrigar a entrar numa secao.
        //
        // A spec original dizia que o rodape da Tela 1 era Voltar + Reverter + Criar. Com
        // rascunho acumulado, deixar o Reverter sem par na mesma linha seria um beco: dava
        // para desfazer um por um e nao havia como aceitar. Cabe: 266 px de 292 uteis a
        // 320x240, com os quatro botoes. (Eu ja tinha dito que nao cabia, basing numa
        // conta de tela de no -- que tem "Criar Subsecao", 7 px mais largo.)
        rightX -= GAP + saveW;
        saveButton = addFooterButton(LBL_SAVE, rightX, footerY, saveW, this::acceptDrafts);
        if (podeReverter()) {
            rightX -= GAP + undoW;
            addFooterButton(LBL_UNDO, rightX, footerY, undoW, this::reverter);
        }
        setBotaoReverterVisivel(podeReverter());
        updateCreateEnabled();
        updateSaveEnabled();
    }

    /**
     * Aceita os rascunhos: e o [Salvar] da Tela 1.
     *
     * <p>Os campos desta tela sao o formulario de CRIAR, entao nao ha texto de anotacao para
     * mandar daqui. O que este botao faz e o segundo momento de gravar: o texto que a
     * jogadora digitou nas telas de no esta no {@link DiaryDrafts} e vai para o servidor
     * agora, e o {@code accept} em seguida marca tudo como aceito, para o [Reverter] sumir.
     */
    private void acceptDrafts() {
        flushLocalDrafts();
        ClientPlayNetworking.send(new RpgNetworking.DiaryAcceptPayload());
        // Aceitou: a pilha do [Reverter] esvazia inteira, e e o botao sumindo que diz que
        // acabou. (03/10/2026)
        DiaryUndo.limpar();
    }

    /**
     * O botao acende com rascunho: os campos desta tela sao de criacao, nao de edicao.
     *
     * <p>{@code podeReverter} e o mesmo predicado do {@code [Reverter]} desta tela: sao os
     * dois lados do mesmo estado — ha algo nao aceito, entao ha o que salvar e o que
     * desfazer. Se divergissem, um dos dois botoes ficaria aceso sem efeito.
     */
    private void updateSaveEnabled() {
        if (saveButton != null) {
            saveButton.active = podeReverter();
        }
    }

    // ------------------------------------------------------------ lista e acoes

    @Override
    protected List<DiaryEntry> visibleEntries() {
        String query = searchBox == null ? "" : searchBox.getValue();
        return DiaryStore.sorted(
                DiaryStore.filtered(DiaryStore.childrenOf(entries, DiaryEntry.ROOT), query),
                sortMode);
    }

    @Override
    protected void openEntry(DiaryEntry entry) {
        // O `parentScreen` repassado e o MENU, nunca `this`. A jogadora definiu que o
        // `Esc` e o `[X]` fecham o diario inteiro e voltam ao menu, e so o `Voltar` sobe um
        // nivel. Passar `this` fazia o `Esc` da Tela 1 voltar para a pagina de no que a
        // abriu -- que e o defeito de "aparece o caminho de onde estamos". (02/10/2026)
        this.minecraft.setScreen(new DiaryNodeScreen(entry.id(), entries, parentScreen));
    }

    private void createSection() {
        String title = titleBox.getValue().trim();
        if (title.isEmpty()) {
            return;
        }
        ClientPlayNetworking.send(new RpgNetworking.DiaryCreatePayload(
                DiaryEntry.ROOT, title, descBox.getValue()));
        titleBox.setValue("");
        descBox.setValue("");
        titleBox.setFocused(false);
        descBox.setFocused(false);
        listScroll = 0;
    }

    /**
     * O botao Criar Secao so acende com um titulo valido.
     *
     * <p><b>Por que desabilitar em vez de avisar:</b> o servidor recusa titulo vazio, e o
     * efeito seria a secao nao aparecer sem nenhuma explicacao. O campo ja tem
     * {@link DiaryEntry#MAX_TITLE}, entao aqui so falta o caso do vazio.
     */
    private void updateCreateEnabled() {
        if (createButton != null) {
            createButton.active = titleBox != null && !titleBox.getValue().trim().isEmpty();
        }
    }

    // ---------------------------------------------------------------- desenho

    @Override
    protected void drawHeader(GuiGraphics graphics) {
        // Os rotulos ficam ACIMA dos campos: desenhados depois dos widgets, um rotulo
        // dentro da faixa da caixa seria coberto pelo fundo opaco do `EditBox`.
        graphics.drawString(this.font, LBL_SEARCH, searchLabelX, searchLabelY, 0xFFB0B0B0, false);
        graphics.drawString(this.font, LBL_TITLE, titleLabelX, titleLabelY, 0xFFB0B0B0, false);
        graphics.drawString(this.font, LBL_DESC, descLabelX, descLabelY, 0xFFB0B0B0, false);

        // "Seções:" na MESMA linha do botao de ordenar, alinhado pela esquerda. A
        // ALTURA e a do botao, e o texto e centralizado nela.
        graphics.drawString(this.font, LBL_SECTIONS, panelX + PAD, legendY + (LEGEND_H - 8) / 2,
                0xFFB0B0B0, false);

        if (isEmptyView()) {
            String hint = emptyListHint();
            graphics.drawCenteredString(this.font, hint, this.width / 2,
                    listTop + Math.max(0, (listHeight - 8) / 2), 0xFF8A8A8A);
        }
    }

    @Override
    protected String emptyListHint() {
        String query = searchBox == null ? "" : searchBox.getValue().trim();
        return query.isEmpty()
                ? "Nenhuma seção ainda. Escreva um título abaixo e clique em Criar Seção."
                : "Nenhuma seção tem esse título.";
    }

    // -------------------------------------------------------------- entrada

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        // Tudo que depende do texto digitado e reavaliado por quadro, e nao em
        // `charTyped`/`keyPressed`. Colar com Ctrl+V, arrastar texto e o autocomplete nao
        // passam por nenhum dos dois, e um filtro que so reage a tecla digitada deixaria a
        // lista mostrando a busca velha com o campo ja cheio.
        //
        // `String.equals` em um texto de busca de poucos caracteres e mais barato que recriar
        // os botoes dos cards, entao o `rebuildList` so roda quando o texto MUDOU.
        String query = searchBox == null ? "" : searchBox.getValue();
        if (!query.equals(appliedQuery)) {
            appliedQuery = query;
            listScroll = 0;
            rebuildList();
        }
        updateCreateEnabled();
        super.render(graphics, mouseX, mouseY, delta);
    }

    @Override
    public void onClose() {
        // Esc, [X] e o [Voltar] desta tela sao a MESMA coisa: sair do diario inteiro, e o
        // unico momento em que o texto digitado nas telas de no vai para o servidor sem
        // ela pedir (03/10/2026).
        boolean tinhaRascunho = hasLocalDrafts();
        flushLocalDrafts();
        // O `accept` e o que faz o aviso amarelo e o [Reverter] sumirem: ele marca o que
        // acabou de chegar como aceito, e limpa uma exclusao que estivesse pendente.
        if (tinhaRascunho || podeReverter()) {
            ClientPlayNetworking.send(new RpgNetworking.DiaryAcceptPayload());
            DiaryUndo.limpar();
        }
        this.minecraft.setScreen(parentScreen);
    }

    /** A tela pede o estado quando ainda nao o tem (primeira abertura pelo menu). */
    private void requestState() {
        ClientPlayNetworking.send(new RpgNetworking.DiaryRequestPayload());
    }
}