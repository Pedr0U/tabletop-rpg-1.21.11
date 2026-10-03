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
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * <b>Telas 2 e 3</b> do Diario: a pagina de uma Secao ou de uma Subsecao (02/10/2026).
 *
 * <p><b>Por que uma tela so para os dois casos:</b> a jogadora confirmou que a Tela 3 e "a
 * mesma coisa que a tela 2". A unica diferenca real entre elas e QUEMnasceu a anotacao: a
 * Tela 3 aparece logo depois de "Criar Subsecao", com o titulo ja numerado pelo servidor e a
 * descricao em branco. O que faz a tela ser a mesma e o que ela mostra: o caminho dos
 * ancestrais, o titulo da anotacao num campo, a descricao num campo, e a lista de subsecoes
 * com os botoes de sempre.
 *
 * <p><b>Por que o caminho mostra os ANCestrais e nao a anotacao atual:</b> o titulo da
 * anotacao ja esta no campo logo abaixo, e repetir o mesmo texto em duas linhas da mesma
 * tela seria ruido. No primeiro nivel o caminho e so o nome da secao pai, como pedido; a
 * partir do segundo ele vira "Seção 1 > Subseção 1 > Subseção 2".
 *
 * <p><b>Por que o {@code [Salvar]} compara com o que foi carregado, e nao depende de evento
 * de tecla:</b> a jogadora pediu que ele acenda "no momento exato em que o jogador digitar,
 * apagar ou modificar qualquer caractere". Comparar os valores a cada quadro cobre isso e
 * cobre tambem colar com Ctrl+V, arrastar texto e o autocomplete do navegador -- nenhum
 * deles passa por `charTyped`, e um botao que so reage a tecla digitada ficaria cinza com o
 * texto ja escrito.
 */
public class DiaryNodeScreen extends DiaryScreenBase {

    // A altura da caixa de Descricao NAO mora aqui: e `descHeight`, que o `layoutPanel`
    // escolhe conforme a altura da tela (ver `DESC_MIN`/`DESC_MAX` na base). O `chrome`
    // abaixo e medido com o MINIMO, e o que sobra da tela e acrescentado pelo `layoutPanel`
    // -- por isso os dois precisam usar o mesmo minimo, e nao um numero solto cada um.

    /**
     * Quantas etapas do caminho cabem no pior caso.
     *
     * <p><b>Por que um teto:</b> a profundidade e livre (a jogadora pediu "infinitas"), e um
     * numero de fatias fixo aqui estouraria a tela num diario com 40 niveis. O corte e
     * pela LARGURA em pixels, medido de tras para frente; o teto aqui e so o pior caso de um
     * texto com {@link DiaryEntry#MAX_TITLE} caracteres por etapa.
     */
    private static final int MAX_CRUMBS = 16;

    private final int nodeId;

    /**
     * Para onde o {@code [X]} e o {@code Esc} voltam: a tela que estava aberta antes do
     * diario (o menu). O {@code parentScreen} mora na {@link DiaryScreenBase}.
     *
     * <p><b>Por que nao e "a tela anterior dentro do diario":</b> o {@code [X]} e o botao de
     * FECHAR o diario. Se ele voltasse para a pagina anterior, fechar no meio da arvore
     * seria "subir um nivel" -- que e o que o {@code [Voltar]} faz, e e para isso que ele
     * existe. Sao dois botoes com destino diferente e nao podem compartilhar o campo.
     */

    private EditBox titleBox;
    private MultiLineEditBox descBox;
    private Button sortButton;
    private Button closeButton;
    private Button saveButton;

    /** O titulo como veio do servidor, para saber se a jogadora mudou alguma coisa. */
    private String savedTitle = "";

    /** A descricao como veio do servidor. Ver {@link #savedTitle}. */
    private String savedDescription = "";

    // --- caminho: as etapas desenhadas, com a posicao de cada uma, para o clique ---

    /**
     * Uma etapa do caminho, com a posicao ja medida.
     *
     * <p><b>Por que guardar a posicao da SETA dentro do crumb:</b> antes, o desenho usava
     * {@code x + width + TIGHT_GAP / 2} e o layout usava {@code x += width + arrowW}. Sao
     * duas contas diferentes para a mesma coisa, e o resultado era um vao de ~10 px entre a
     * seta e a etapa seguinte que nao existia em lugar nenhum — nem no desenho, nem na area
     * clicavel. A jogadora mirava o nome, batia no vao, e o nome da esquerda ficava com o
     * clique. Agora a seta e o vao sao o mesmo objeto medido uma vez so.
     */
    private record Crumb(int id, String label, int x, int width, int arrowX) {
    }

    /**
     * Folga de clique em volta de cada etapa do caminho.
     *
     * <p><b>Por que existe:</b> o retangulo de clique era exatamente a caixa do glifo. O
     * nome da esquerda terminava colado no vao da seta, entao errar 2 px para a esquerda
     * entrava na etapa anterior. O desenho do nome e o retangulo de clique sao a mesma
     * conta a partir de agora; esta folga cobre so a imprecisao da mira.
     */
    private static final int CRUMB_PAD = TIGHT_GAP;

    private final List<Crumb> crumbs = new ArrayList<>();
    private int crumbY;
    private int legendY;
    private int titleLabelX;
    private int titleLabelY;
    private int descLabelX;
    private int descLabelY;

    public DiaryNodeScreen(int nodeId, List<DiaryEntry> known) {
        this(nodeId, known, null);
    }

    public DiaryNodeScreen(int nodeId, List<DiaryEntry> known, Screen parentScreen) {
        super(parentScreen);
        this.nodeId = nodeId;
        if (known != null) {
            this.entries = known;
        }
    }

    /** A anotacao que esta tela edita. */
    private DiaryEntry node() {
        return find(nodeId);
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        DiaryEntry entry = node();
        // O texto que o SERVIDOR tem. E contra isto que `isDirty` compara, para saber se a
        // jogadora mudou alguma coisa.
        savedTitle = entry == null ? "" : entry.title();
        savedDescription = entry == null ? "" : entry.description();

        // O que a jogadora digitou antes e nao aceitou. Vem antes do servidor de proposito:
        // se houvesse rascunho, ele e o que a caixa mostra, e o servidor so volta a valer
        // depois que ela aceitar (03/10/2026).
        String[] rascunho = DiaryDrafts.get(nodeId);
        String abrirTitle = rascunho == null ? savedTitle : rascunho[0];
        String abrirDesc = rascunho == null ? savedDescription : rascunho[1];

        // O caminho ocupa linha SEMPRE que houver caminho a mostrar — inclusive numa secao de RAIZ,
        // onde ele e so o proprio nome ("Secao 1"), pedido da jogadora. Antes a condicao era
        // `size() - 1 > 0`, ou seja "tem ancestral", e a secao de raiz nao reservava a linha.
        int pathLen = entry == null ? 0 : DiaryStore.ancestryOf(entries, nodeId).size();
        int crumbH = pathLen > 0 ? STATUS_H + TIGHT_GAP + GAP : 0;

        int chrome = TITLE_H + GAP
                + crumbH
                + LBL_ROW + FIELD_H
                + LBL_ROW + DESC_MIN + 12
                + LEGEND_H + TIGHT_GAP
                + TIGHT_GAP
                + ROW_H
                + PAD;
        // Os dois `LBL_ROW` sao o rotulo do Titulo e o da Descricao, cada um na sua linha
        // propria ACIMA do campo (pedido da jogadora: e o que da largura toda ao campo).
        // Eles precisam aparecer aqui e no bloco de campos la embaixo; se so um dos dois
        // mudar, o campo de baixo cai em cima do rodape. Ver `check-diary-layout.ps1`.
        layoutPanel(chrome);

        // `usable` DEPOIS de `layoutPanel`, nunca antes. `panelWidth` e zero na primeira
        // `init()` de uma tela nova, entao ler antes dava `usable = -12`: o campo de Titulo
        // caia no piso de 60 px, o `boxX` saia negativo (campo para fora da tela) e a caixa
        // de Descricao nascia com LARGURA NEGATIVA e nao aparecia. Numa segunda `init()`
        // da mesma tela o `panelWidth` ja estava certo, e era por isso que o campo de
        // Descricao aparecia "normalmente" depois de Esc. (02/10/2026)
        int usable = panelWidth - 2 * PAD;

        int y = panelY + PAD;
        closeButton = addRenderableWidget(Button.builder(Component.literal(LBL_CLOSE), b -> onClose())
                .bounds(panelX + panelWidth - PAD - CARD_BTN_W, y, CARD_BTN_W, TITLE_H + 2)
                .build());
        y += TITLE_H + GAP;

        if (pathLen > 0) {
            crumbY = y;
            layoutCrumbs(usable);
            y += STATUS_H + TIGHT_GAP + GAP;
        }

        // Titulo e Descricao com o ROTULO EM LINHA PROPRIA ACIMA, pedido pela jogadora, e nao ao
        // lado: e a unica disposicao em que o campo fica com a largura toda do quadro
        // (`usable`), sem espaco reservado para o rotulo. (02/10/2026)
        //
        // O rotulo tem uma LINHA propria (`LBL_ROW`) e a caixa comeca logo abaixo dela. A
        // versao anterior o desenhava a `STATUS_H` px acima da caixa, com a caixa so
        // `TIGHT_GAP` abaixo do campo de cima, e o texto invadia o campo de cima. Aqui a
        // sobreposicao e geometricamente impossivel: nao ha constante para calibrar.
        titleLabelX = panelX + PAD;
        titleLabelY = y;
        titleBox = new EditBox(this.font, panelX + PAD, y + LBL_ROW, usable, FIELD_H,
                Component.literal(LBL_TITLE));
        titleBox.setMaxLength(DiaryEntry.MAX_TITLE);
        titleBox.setValue(abrirTitle);
        addRenderableWidget(titleBox);
        y += LBL_ROW + FIELD_H + TIGHT_GAP;

        descLabelX = panelX + PAD;
        descLabelY = y;
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
        descBox.setValue(abrirDesc);
        addRenderableWidget(descBox);
        y += LBL_ROW + descHeight + TIGHT_GAP + 12;

        legendY = y;
        rebuildSortButton();
        // UM `GAP` entre a legenda e a lista, e nao dois. Ver o mesmo comentario na
        // `DiaryScreen`: a conta e a das duas telas, e o script de layout cobre as duas.
        y += LEGEND_H + TIGHT_GAP;

        placeList(y);
        y = listBottom + 2;

        footerY = y;
        rebuildFooter();

        clampScroll();
        rebuildList();
        logLayout("DiaryNodeScreen");
    }

    /**
     * Mede as etapas do caminho de tras para frente ate a primeira nao caber.
     *
     * <p><b>Por que de tras para frente:</b> e o que a jogadora pediu ("... &gt; subseção 3
     * &gt; subseção 4 &gt; subseção 5"): o que fica e a parte que importa, que e onde a
     * jogadora esta agora. Medindo da esquerda para a direita, um caminho de 12 etapas
     * mostraria as 3 primeiras e esconderia a que ela estava editando.
     */
    private void layoutCrumbs(int usable) {
        crumbs.clear();
        DiaryEntry entry = node();
        if (entry == null) {
            return;
        }
        List<DiaryEntry> path = DiaryStore.ancestryOf(entries, nodeId);
        int textX = panelX + PAD;
        // O caminho INCLUI a propria anotacao (`ancestryOf` comeca por ela), e e o que a
        // jogadora pediu: dentro da Subsecao 1 o endereco e "Secao 1 > Subsecao 1", e
        // dentro da Secao 1 (raiz, sem ancestral) e so "Secao 1".
        //
        // Antes era `size() - 2`, que excluia a propria anotacao. Numa secao de RAIZ isso
        // dava `size() = 1`, o indice virava -1 e o laco nao rodava uma vez sequer: nenhum
        // breadcrumb aparecia. E na Subsecao 1 aparecia so "Secao 1", sem a Subsecao 1.
        int lastAnc = path.size() - 1;
        int arrowW = this.font.width(" > ") + TIGHT_GAP;
        String ellipsis = "... >";
        boolean truncated = false;
        List<Integer> selected = new ArrayList<>();
        {
            // O "..." e reservado desde o inicio. Medir sem ele e so depois descontar faz a
            // ultima etapa caber no orcamento e estourar a linha quando o prefixo e
            // desenhado -- que era o sintoma de "aparece so ate a Subsecao 3".
            int budget = usable - (this.font.width(ellipsis) + TIGHT_GAP);
            int used = 0;
            for (int i = lastAnc; i >= 0; i--) {
                int w = this.font.width(path.get(i).title());
                if (used + w > budget && !selected.isEmpty()) {
                    truncated = true;
                    break;
                }
                used += w + arrowW;
                selected.add(i);
            }
            if (!truncated && selected.size() < lastAnc + 1) {
                truncated = true;
            }
        }
        Collections.reverse(selected);
        int x = textX + (truncated ? this.font.width(ellipsis) + TIGHT_GAP : 0);
        for (int i : selected) {
            DiaryEntry crumb = path.get(i);
            int w = this.font.width(crumb.title());
            int arrowX = x + w + (arrowW - this.font.width(">")) / 2;
            crumbs.add(new Crumb(crumb.id(), crumb.title(), x, w, arrowX));
            x += w + arrowW;
        }
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
            removeWidget(sortButton);
            rebuildSortButton();
        }

        int backW = this.font.width(LBL_BACK) + BTN_TEXT_PAD;
        int saveW = this.font.width(LBL_SAVE) + BTN_TEXT_PAD;
        int newW = this.font.width(LBL_NEW_SUB) + BTN_TEXT_PAD;
        int undoW = podeReverter() ? this.font.width(LBL_UNDO) + BTN_TEXT_PAD : 0;

        addFooterButton(LBL_BACK, panelX + PAD, footerY, backW, this::goBack);

        int rightX = panelX + panelWidth - PAD - newW;
        addFooterButton(LBL_NEW_SUB, rightX, footerY, newW, this::createSubsection);
        rightX -= GAP + saveW;
        saveButton = addFooterButton(LBL_SAVE, rightX, footerY, saveW, this::save);

        if (podeReverter()) {
            rightX -= GAP + undoW;
            addFooterButton(LBL_UNDO, rightX, footerY, undoW, this::reverter);
        }
        setBotaoReverterVisivel(podeReverter());
        updateSaveEnabled();
    }

    // ------------------------------------------------------------ lista e acoes

    @Override
    protected List<DiaryEntry> visibleEntries() {
        return DiaryStore.sorted(DiaryStore.childrenOf(entries, nodeId), sortMode);
    }

    @Override
    protected void openEntry(DiaryEntry entry) {
        this.minecraft.setScreen(new DiaryNodeScreen(entry.id(), entries, parentScreen));
    }

    /**
     * Voltar sobe um nivel; da raiz, volta para a Tela 1.
     *
     * <p><b>Por que o {@code parentScreen} repassado e o MENU e nunca {@code this}:</b> o
     * `Voltar` sobe um nivel e ja estava certo. O que estava errado era o {@code Esc}: ele
     * usava o mesmo campo, e com `this` repassado o `Esc` da Tela 1 voltava para a pagina
     * de no que a abriu, mostrando o caminho de onde ela estava em vez de fechar. Como o
     * `Esc` e o `Voltar` nao podem compartilhar o mesmo destino, as duas telas repassam o
     * MENU adiante e cada uma aplica a sua regra em cima dele. (02/10/2026)
     */
    private void goBack() {
        DiaryEntry entry = node();
        int parentId = entry == null ? DiaryEntry.ROOT : entry.parentId();
        if (parentId == DiaryEntry.ROOT) {
            // Este [Voltar] sobe para a Tela 1, que ainda e DENTRO do diario: a jogadora
            // pediu explicitamente que ele nao salve. O texto digitado aqui continua no
            // `DiaryDrafts`, que o `cacheDraft` de cada quadro ja atualizou, e so vai para
            // o servidor no [Salvar] ou na saida (Esc/X). (03/10/2026)
            this.minecraft.setScreen(new DiaryScreen(parentScreen, entries));
        } else {
            // Este sobe UM nivel: continua dentro do diario. Nada e gravado, pelos mesmos
            // motivos do caso acima.
            this.minecraft.setScreen(new DiaryNodeScreen(parentId, entries, parentScreen));
        }
    }

    /**
     * Grava o rascunho e marca como aceito: e o botao [Salvar].
     *
     * <p>E o mesmo contrato do {@link #onClose()} — gravar e aceitar — porque para a
     * jogadora e o mesmo gesto: "ficou assim". A diferenca e que aqui o texto e o desta
     * anotacao, enquanto o {@code onClose} de cada tela cuida do resto (03/10/2026).
     */
    private void save() {
        sendText();
        // O `accept` sai INCONDICIONALMENTE, mesmo sem texto novo: e o [Salvar] que "aplica
        // meus rascunhos", e uma das coisas que ele tem que limpar e uma exclusao pendente,
        // que nao passa por `sendText`. Sem isto, apagar uma secao e apertar [Salvar] sem
        // mexer em texto nenhum deixaria o [Reverter] oferecendo desfazer o que ela ja
        // aceitou.
        ClientPlayNetworking.send(new RpgNetworking.DiaryAcceptPayload());
        DiaryDrafts.clear(nodeId);
        // Aceitou: a pilha inteira do [Reverter] esvazia, nao so o passo deste no. Uma
        // exclusao feita antes tambem foi aceita, e ela sumir do botao e o que o [Salvar]
        // promete. (03/10/2026)
        DiaryUndo.limpar();
        updateSaveEnabled();
    }

    /**
     * Envia o texto desta anotacao para o servidor.
     *
     * <p>Grava e nao aceita: quem aceita e o {@code DiaryAcceptPayload} que o chamador
     * manda logo em seguida, o mesmo par que a Tela 1 monta no {@code flushLocalDrafts}. Um
     * pacote por assunto deixa explicito qual dos dois aconteceu — o texto novo entrou, ou
     * foi aceito.
     *
     * @return {@code false} se nao havia nada a gravar (o titulo sumiu, ou nada mudou)
     */
    private boolean sendText() {
        String title = titleBox.getValue().trim();
        if (title.isEmpty()) {
            // Mudar para vazio deixaria a anotacao sem nome, e o servidor recusaria. O
            // rascunho com titulo vazio fica so no `DiaryDrafts` -- e e o que a jogadora
            // digitou -- mas nao entra no diario.
            return false;
        }
        String desc = descBox.getValue();
        if (title.equals(savedTitle) && desc.equals(savedDescription)) {
            // Nada mudou desde que esta tela abriu: um pacote aqui seria um no empilhado
            // no [Reverter] que a jogadora nunca fez, e ela teria um "alteracao para
            // salvar" que nao existe.
            return false;
        }
        ClientPlayNetworking.send(new RpgNetworking.DiarySavePayload(nodeId, title, desc, false));
        savedTitle = title;
        savedDescription = desc;
        return true;
    }

    /**
     * Nao existe "gravar ao sair da tela": a jogadora pediu que a gravacao aconteca na
     * SAIDA DO DIARIO, nao na troca de tela. (03/10/2026)
     *
     * <p><b>Por que nada e gravado aqui:</b> enquanto ela navega, o texto vive no
     * {@link DiaryDrafts}, que o {@link #cacheDraft()} de cada quadro mantem atualizado, e
     * reaparece na caixa quando ela volta a abrir a secao. O texto so vai para o servidor em
     * dois momentos, ambos em {@link #save()} e {@link #onClose()}.
     *
     * <p><b>Por que o {@code removed()} foi descartado:</b> as duas formas ja falharam, por
     * motivos opostos, e nenhuma delas era culpa da jogadora.
     *
     * <ul>
     *   <li>Decidir dentro do {@code removed()} olhando {@code minecraft.screen} nao
     *       funciona: {@code setScreen()} chama {@code removed()} ANTES de reatribuir
     *       {@code this.screen}, entao la dentro ainda e {@code this}. A guarda
     *       {@code if (next == this) return;} caia sempre e o rascunho nunca era gravado.
     *   <li>Gravar incondicionalmente no {@code removed()} tambem nao serve: ela pediu que
     *       navegar DENTRO do diario nao salve, e o {@code removed()} roda em toda troca de
     *       tela, inclusive no Voltar que sobe um nivel.
     * </ul>
     *
     * <p>A solucao foi inverter a decisao: em vez de o gancho perguntar "esta saida e uma
     * saida?", sao os <b>pontos de saida</b> que gravam, e a saida do diario inteiro e um
     * lugar explicito ({@code Esc}, {@code [X]}, e o {@code [Voltar]} da Tela 1).
     */

    private void createSubsection() {
        // Sem titulo: o numero ("Subseção 1", "Subseção 2") sai do servidor, que e quem sabe
        // quantas subsecoes o pai ja tem. Ver `DiaryCreateChildPayload`.
        ClientPlayNetworking.send(new RpgNetworking.DiaryCreateChildPayload(nodeId));
    }

    private boolean isDirty() {
        if (titleBox == null || descBox == null) {
            return false;
        }
        return !titleBox.getValue().trim().equals(savedTitle)
                || !descBox.getValue().equals(savedDescription);
    }

    private void updateSaveEnabled() {
        if (saveButton != null) {
            // O [Salvar] e "aplicar os rascunhos", e rascunho nao e so o desta tela: ha
            // alteracao pendente em qualquer secao que ela ja editou nesta sessao. Um botao
            // que so acende quando o campo local mudou deixaria o [Salvar] morto na tela
            // onde ela foi parar justamente para aplicar.
            saveButton.active = isDirty() || podeReverter();
        }
    }

    // ---------------------------------------------------------------- desenho

    @Override
    protected void drawHeader(GuiGraphics graphics) {
        if (!crumbs.isEmpty()) {
            boolean truncated = crumbs.get(0).x > panelX + PAD;
            if (truncated) {
                // Com a seta: "... > Subsecao 4". Sem ela, o prefixo encostava na primeira
                // etapa e o corte parecia parte do nome.
                graphics.drawString(this.font, "... >", panelX + PAD, crumbY, 0xFFB0B0B0, false);
            }
            for (int i = 0; i < crumbs.size(); i++) {
                Crumb crumb = crumbs.get(i);
                // `hasPending`, e nao `hasPendingInSubtree(..., pendingIds)` direto (03/10/2026).
                // A chamada antiga so olhava o que o SERVIDOR tem e nao aceitou, e desde que
                // a gravacao passou a acontecer so no [Salvar] e na saida, o texto novo vive
                // no `DiaryDrafts`, no cliente: o ponto do endereco ficava apagado quase
                // sempre — que foi o que a jogadora reportou. Passando por `hasPending`, o
                // cartao, o ponto e o endereco passam a ler exatamente a mesma regra, e uma
                // marcacao que acende num lugar e nao no outro e impossivel por construcao.
                boolean pending = hasPending(find(crumb.id()));
                // A etapa mais proxima de voce fica mais clara: e um caminho, e a ultima
                // posicao e a que a jogadora esta. Com pendencia, o texto fica AMARELO e
                // ganha um ponto: o caminho e onde a marcacao de "nao salvei" aparece, e
                // ela pediu exatamente isso.
                int color = pending ? 0xFFFFE24B
                        : (i == crumbs.size() - 1 ? 0xFFE0C080 : 0xFFB0B0B0);
                graphics.drawString(this.font, crumb.label(), crumb.x, crumbY, color, false);
                if (pending) {
                    graphics.fill(crumb.x + this.font.width(crumb.label()) + 2, crumbY + 3,
                            crumb.x + this.font.width(crumb.label()) + 5, crumbY + 6, 0xFFFFE24B);
                }
                if (i < crumbs.size() - 1) {
                    // `arrowX` veio do layout, nao de uma conta nova aqui: era exatamente
                    // essa a divergencia que fazia o clique cair na etapa errada.
                    graphics.drawString(this.font, ">", crumb.arrowX(), crumbY, 0xFF808080, false);
                }
            }
        }

        graphics.drawString(this.font, LBL_TITLE, titleLabelX, titleLabelY, 0xFFB0B0B0, false);
        graphics.drawString(this.font, LBL_DESC, descLabelX, descLabelY, 0xFFB0B0B0, false);
        graphics.drawString(this.font, LBL_SUBSECTIONS, panelX + PAD, legendY + (LEGEND_H - 8) / 2,
                0xFFB0B0B0, false);

        if (isEmptyView()) {
            graphics.drawCenteredString(this.font, emptyListHint(), this.width / 2,
                    listTop + Math.max(0, (listHeight - 8) / 2), 0xFF8A8A8A);
        }
    }

    @Override
    protected String emptyListHint() {
        return "Nenhuma subseção ainda. Clique em Criar Subseção para abrir uma.";
    }

    // ---------------------------------------------------------------- entrada

    /**
     * O estado chegou. Recarrega os campos <b>somente</b> quando o servidor mexeu no texto
     * desta secao.
     *
     * <p><b>Por que recarregar aqui e o inverso da regra "nunca recarregar os campos":</b> essa
     * regra protege o rascunho de uma mudanca que NAO e desta secao -- acender o pino de um
     * vizinho, criar outra, desfazer uma delecao de outro ramo. Nenhum desses casos muda o
     * texto deste no, e a comparacao abaixo nao dispara.
     *
     * <p>Um caso dispara: a jogadora edita o titulo, aperta [Reverter], e o servidor devolve o
     * texto antigo. Sem recarregar, a caixa continuaria mostrando o rascunho que ela acabou de
     * cancelar, e o proximo [Salvar] reaplicaria por cima do desfazer. O criterio e
     * "o que o servidor tem para este id e diferente do que eu sei que ele tem", que e
     * exatamente a condicao para a caixa estar mentindo.
     */
    @Override
    public void applyState(List<DiaryEntry> newEntries, boolean newCanUndo,
                           List<Integer> newPendingIds) {
        DiaryEntry antes = find(nodeId);
        super.applyState(newEntries, newCanUndo, newPendingIds);
        DiaryEntry agora = find(nodeId);
        if (titleBox == null || descBox == null || agora == null) {
            return;
        }
        if (antes == null || !antes.title().equals(agora.title())
                || !antes.description().equals(agora.description())) {
            savedTitle = agora.title();
            savedDescription = agora.description();
            // So recarrega as caixas se NAO houver rascunho local (03/10/2026). O estado
            // chega depois de qualquer acao — inclusive de outro ramo — e sobrescrever a
            // caixa aqui apagaria o texto que a jogadora digitou e ainda nao aceitou.
            if (!DiaryDrafts.has(nodeId)) {
                titleBox.setValue(savedTitle);
                descBox.setValue(savedDescription);
            }
            updateSaveEnabled();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        // `Salvar` e reavaliado por quadro, e nao em `charTyped`/`keyPressed`: e o que acende
        // no instante em que QUALQUER caractere muda, e o unico jeito de cobrir tambem colar
        // com Ctrl+V e arrastar texto, que nao passam por nenhum dos dois eventos.
        updateSaveEnabled();

        // Guarda o rascunho a cada quadro, por causa do mesmo motivo: um `charTyped` nao
        // ve colar com Ctrl+V nem arraste. So neste cliente, nada vai pro servidor aqui
        // (03/10/2026).
        cacheDraft();
        super.render(graphics, mouseX, mouseY, delta);
    }

    /**
     * Copia as caixas para o {@link DiaryDrafts}, para sobreviver a navegacao.
     *
     * <p><b>Por que a cada quadro e nao ao sair:</b> o texto precisa sobreviver a troca de
     * tela, e o unico instante em que se tem certeza do conteudo das caixas e agora. Guardar
     * no `removed()` seria tarde demais para o caso real: a tela ja foi destruida.
     *
     * <p>So grava quando ha diferenca contra o servidor: um rascunho igual ao que o servidor
     * ja tem e lixo, e deixaria o aviso amarelo aceso sem motivo.
     */
    private void cacheDraft() {
        if (titleBox == null || descBox == null) {
            return;
        }
        String t = titleBox.getValue();
        String d = descBox.getValue();
        if (t.equals(savedTitle) && d.equals(savedDescription)) {
            DiaryDrafts.clear(nodeId);
            // Ela digitou e depois apagou tudo: o rascunho sumiu, entao o passo do
            // [Reverter] tem que sumir junto (ver `removerEdicao`).
            DiaryUndo.removerEdicao(nodeId);
        } else if (DiaryDrafts.put(nodeId, t, d)) {
            // Este no RECEN gained um rascunho: e a primeira vez que a jogadora mexeu nele,
            // e o que entra na pilha do [Reverter]. As teclas seguintes reescrevem o mesmo
            // rascunho e nao empilham nada — senao um titulo de tres segundos atrasaria o
            // botao e o primeiro clique desfaria uma letra. (03/10/2026)
            DiaryUndo.registrar(DiaryUndo.Tipo.EDICAO, nodeId);
        }
    }

    @Override
    protected void aoReverterRascunho(int nodeIdDesfeito) {
        if (nodeIdDesfeito != nodeId || titleBox == null || descBox == null) {
            return;
        }
        // A caixa e reescrita a cada quadro por `cacheDraft`, e ela compara com
        // `savedTitle`/`savedDescription`. Enquanto a caixa continuar suja, o rascunho
        // voltaria no quadro seguinte e o clique do [Reverter] nao teria feito nada.
        titleBox.setValue(savedTitle);
        descBox.setValue(savedDescription);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int mouseX = (int) event.x();
        int mouseY = (int) event.y();

        // O caminho e clicavel ANTES do `super`, que entrega o clique aos widgets. Nao ha
        // widget para o caminho (ele e so texto desenhado), entao nao existe outro lugar
        // onde o clique poderia ser resolvido.
        //
        // <b>De tras para frente, com {@link #CRUMB_PAD}:</b> as etapas sao vizinhas com
        // `CRUMB_PAD` de folga de cada lado, entao os retangulos se tocam. Varrendo da
        // esquerda para a direita, a etapa da ESQUERDA ficaria com a fronteira e a
        // jogadora acabaria na etapa anterior; varrendo de tras para frente, quem fica com
        // ela e a da direita, que e a que ela mirou.
        if (mouseY >= crumbY && mouseY < crumbY + STATUS_H) {
            for (int i = crumbs.size() - 1; i >= 0; i--) {
                Crumb crumb = crumbs.get(i);
                if (mouseX >= crumb.x - CRUMB_PAD && mouseX < crumb.x + crumb.width + CRUMB_PAD) {
                    this.minecraft.setScreen(new DiaryNodeScreen(crumb.id(), entries, parentScreen));
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public void onClose() {
        // Esc e o [X]: saida do diario inteiro. E o segundo momento de gravar, junto com o
        // [Salvar] — a jogadora pediu que a gravacao so aconteca nestes dois e em nenhum
        // outro. E o mesmo par de pacotes das duas telas: o texto desta anotacao, e o
        // `accept` que esvazia o [Reverter].
        if (titleBox != null && descBox != null) {
            sendText();
        }
        // O rascunho local deste no deixa de existir: o texto ja foi para o servidor, ou
        // nao podia ir (titulo vazio). Nos dois casos ele nao pode reaparecer na caixa nem
        // acender o aviso na proxima visita. (03/10/2026)
        DiaryDrafts.clear(nodeId);
        // O `accept` vai SEMPRE, mesmo sem texto novo: e ele que faz o aviso amarelo e o
        // [Reverter] sumirem quando ela desiste de revisar e so quer sair. Sem isto, uma
        // exclusao feita antes continuaria pendente ate a proxima vez que ela abrir o diario.
        ClientPlayNetworking.send(new RpgNetworking.DiaryAcceptPayload());
        // Sair e aceitar: a pilha inteira do [Reverter] esvazia, nao so o passo deste no.
        // Uma exclusao feita antes tambem foi aceita, e e o aviso sumir sozinho que diz a
        // ela que pode fechar o jogo sem medo. (03/10/2026)
        DiaryUndo.limpar();
        this.minecraft.setScreen(parentScreen);
    }
}