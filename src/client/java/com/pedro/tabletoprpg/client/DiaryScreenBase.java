package com.pedro.tabletoprpg.client;

import com.pedro.tabletoprpg.DiaryEntry;
import com.pedro.tabletoprpg.DiaryStore;
import com.pedro.tabletoprpg.RpgNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Base das telas do <b>Diario</b>: geometria do card, rolagem, ordenacao, pino, apagar
 * e desfazer (02/10/2026).
 *
 * <p><b>Por que uma base e nao duas telas independentes:</b> a Tela 1 (raiz) e a Tela 2/3
 * (dentro de uma secao) tem <b>exatamente</b> a mesma anatomia de card -- pino, titulo,
 * Del? --, o mesmo scroll, o mesmo botao de ordenar e o mesmo dois-cliques do Del. Tudo
 * isso em cada arquivo daria duas implementacoes da mesma regra, e elas divergiriam no
 * primeiro ajuste. O que muda entre as telas e o que fica ACIMA da lista (campo de busca e
 * area de criacao, contra caminho e campos da anotacao), e isso fica nas subclasses.
 *
 * <p><b>Por que o {@link DiaryStore} e usado no cliente:</b> so os metodos puros
 * ({@code sorted}, {@code filtered}, {@code childrenOf}, {@code ancestryOf}), que sao
 * listas em, listas fora. A regra de "fixado no topo, o ultimo fixado por cima" e a
 * comparacao sem acento da busca ficam escritas UMA vez no {@link DiaryStore}; o cliente
 * as aplica sobre a copia que o servidor mandou. Se a tela ordenasse por conta propria, a
 * lista mostrada e a lista do servidor divergiriam assim que uma das duas mudasse -- e a
 * jogadora veria o card de outro lugar.
 *
 * <p><b>Nada aqui escreve no store:</b> toda mutacao da jogadora vira pacote. O store do
 * cliente esta vazio, e ele nao e a fonte da verdade em nenhum momento.
 *
 * <p><b>Por que o fundo e desenhado e nao uma textura:</b> mesma decisao da tela de
 * Presets. A lista precisa de altura variavel e nenhuma textura do mod tem faixa de lista
 * com tamanho variavel.
 */
public abstract class DiaryScreenBase extends Screen {

    // --- medidas (as mesmas da tela de Presets, para as duas telas terem a mesma cara) ---

    protected static final int PAD = 6;
    protected static final int TITLE_H = 14;
    protected static final int FIELD_H = 18;
    protected static final int ROW_H = 20;
    protected static final int GAP = 6;
    protected static final int TIGHT_GAP = 4;
    protected static final int STATUS_H = 10;

    /**
     * Altura da LINHA DO ROTULO de um campo com o rotulo em linha propria acima.
     *
     * <p><b>Por que o rotulo ganha uma linha inteira:</b> a jogadora pediu que o campo de
     * Titulo e o de Descricao ocupem a largura toda do quadro. So ha duas disposicoes
     * possiveis: rotulo ao lado (rouba ~26 px da largura do campo) ou rotulo acima. Com o
     * rotulo acima e uma LINHA reservada, a sobreposicao com o campo de cima e
     * geometricamente impossivel; a versao anterior media o rotulo a partir da caixa e
     * invadia o campo de cima em 4 px.
     *
     * <p>Vale {@link #STATUS_H} porque e a mesma conta: glifo de 9 px do vanilla mais 1 px
     * de folga. Mudar esta constante exige mudar o `chrome` dos dois {@code init()}.
     */
    protected static final int LBL_ROW = STATUS_H;

    /**
     * Altura da linha "Seções:" + botao de ordenar.
     *
     * <p><b>Por que {@link #ROW_H} e nao {@link #STATUS_H}:</b> a jogadora pediu o botao de
     * ordenar na MESMA linha da legenda. Um botao com 10 px de altura existe, mas e uma
     * faixa que quase nao se clica; a linha inteira com 20 px e o que faz o alvo do clique
     * ter o tamanho das outras linhas.
     */
    protected static final int LEGEND_H = ROW_H;

    /**
     * Teto de linhas visiveis. A lista rola alem disso.
     *
     * <p>Era 6 quando a altura era fixa. Subiu para 10 porque o painel agora cresce com a
     * tela: em 960x540 cabem 10 cartoes sem que a anotacao perca espaco.
     */
    protected static final int MAX_ROWS = 8;

    /**
     * Altura da anotacao, do menor valor ao maior.
     *
     * <p><b>Por que um intervalo e nao um numero:</b> ver {@link #layoutPanel}. O minimo e o
     * que cabe em 320x240 com a lista ainda com uma linha -- abaixo dele o rodape sai da
     * tela. O maximo e o que a tela grande aguenta antes de a anotacao virar um painel
     * branco com 12 linhas de texto e o diario virar um editor de texto em vez de um diario.
     */
    protected static final int DESC_MIN = 50;
    protected static final int DESC_MAX = 100;

    /**
     * A altura da anotacao que este layout escolheu. Medida em {@link #layoutPanel}; as
     * subclasses leem DEPOIS de chamar o metodo, para o `chrome` que elas mediram antes e a
     * caixa que elas constroem depois usarem o mesmo numero.
     */
    protected int descHeight = DESC_MIN;

    /** Largura dos botoes pequenos do card: pino e Del. */
    protected static final int CARD_BTN_W = 20;

    /** Vai-o entre o pino e o titulo, e entre o titulo e o Del. */
    protected static final int CARD_BTN_GAP = 2;

    /**
     * Vai-o reservado a direita de cada linha, para a barra de rolagem.
     *
     * <p><b>Por que reservar espaco e nao desenhar por cima:</b> desenhar a barra sobre o
     * botao Del deixaria o Del coberto justo nas linhas onde ele aparece (no hover), que
     * e quando a jogadora mais precisa ver. Reservar tira 3 px do titulo em TODAS as linhas,
     * o que nao balanca nada.
     */
    protected static final int SB_W = 3;

    /** Largura maxima do painel. Acima disso a linha fica larga demais para ler. */
    protected static final int PANEL_MAX = 340;

    // --- rotulos (acentos de proposito: e o texto que a jogadora le na tela) ---

    protected static final String LBL_TITLE = "Título";
    protected static final String LBL_DESC = "Descrição";
    protected static final String LBL_BACK = "Voltar";
    protected static final String LBL_UNDO = "Reverter";
    protected static final String LBL_SAVE = "Salvar";
    protected static final String LBL_SEARCH = "Busca:";
    protected static final String LBL_SECTIONS = "Seções:";
    protected static final String LBL_SUBSECTIONS = "Subseções:";
    protected static final String LBL_NEW_SECTION = "Criar Seção";
    protected static final String LBL_NEW_SUB = "Criar Subseção";
    protected static final String LBL_CLOSE = "X";

    /** Margem lateral dentro de um botao, somada a largura do texto ao medir o botao. */
    protected static final int BTN_TEXT_PAD = 16;

    /**
     * O titulo fixo no topo de todas as telas do Diario.
     *
     * <p><b>Por que uma constante e nao {@code this.title}:</b> {@code Screen} so aceita o
     * titulo no construtor, e o {@link #render} desenha {@code this.title} -- que nao e
     * gravavel. Sem esta constante o texto do titulo estaria escrito duas vezes, e trocar
     * "Diário" exigiria acertar as duas.
     */
    protected static final String SCREEN_TITLE = "Diário";

    // --- geometria calculada no layout ---

    protected int panelX;
    protected int panelY;
    protected int panelWidth;
    protected int panelHeight;
    protected int listTop;
    protected int listBottom;
    protected int listHeight;
    protected int listRowHeight;
    protected int visibleRows;
    protected int listScroll;

    // --- estado ---

    /**
     * O diario inteiro, como o servidor mandou.
     *
     * <p><b>Por que a guarda a lista e nao le do store:</b> o store e do servidor. A tela
     * desenha a partir de uma copia e so troca quando o servidor responde.
     */
    protected List<DiaryEntry> entries = new ArrayList<>();

    /**
     * Ha algo para o {@code [Reverter]} desfazer?
     *
     * <p><b>Por que a pilha do cliente e nao o {@code canUndo} do servidor:</b> desde que a
     * gravacao passou a acontecer so no [Salvar] e na saida, uma edicao nunca fica
     * *pendente* no servidor, entao o `canUndo` dele so enxerga exclusoes. Usar ele
     * esconderia o botao exatamente quando a jogadora so editou texto — que foi o defeito
     * que ela reportou. A pilha do {@link DiaryUndo} ve as duas coisas e na ordem.
     */
    protected boolean podeReverter() {
        return DiaryUndo.tem();
    }

    /**
     * O {@code [Reverter]} esta no rodape neste momento?
     *
     * <p>O botao e criado e destruido conforme a pilha enche e esvazia, e nao apenas
     * mudado de cor: e o que a jogadora pediu ("aparece so quando ha algo para reverter") e
     * o que mantem o rodape estreito nas telas em que nao ha nada para desfazer. Ver o
     * gancho no {@link #render}.
     */
    private boolean botaoReverterVisivel;

    /**
     * Os ids cujo texto esta diferente do que o [Salvar] aceitou. Vem do servidor: o texto
     * aceito nao viaja, entao o cliente nao tem como saber o que e "aceito" sem perguntar.
     *
     * <p><b>Por que os ids crus e nao a lista de ids para marcar:</b> o servidor so sabe
     * quais nos mudaram; quem sabe se o ancestral de um deles tem que acender e a tela, e
     * ela tem a arvore completa. Marcar no servidor daria o mesmo resultado com um payload
     * maior e nenhuma vantagem.
     */
    protected final Set<Integer> pendingIds = new HashSet<>();

    protected DiaryStore.SortMode sortMode = DiaryStore.SortMode.MODIFIED;

    /**
     * A tela que estava aberta antes do diario -- no fluxo normal, o menu.
     *
     * <p><b>Por que ela mora AQUI e nao em cada tela:</b> as duas telas se repassam entre si
     * quando a jogadora navega (Tela 1 -> no -> no...), e cada uma precisa levar adiante o
     * menu, e nao a pagina anterior. Com o campo duplicado, uma delas passou a repassar
     * {@code this} e o `Esc` passou a descer um nivel em vez de fechar. Com o campo aqui, o
     * `Voltar` recalcula o destino a cada salto e o `Esc` sempre le o mesmo valor.
     *
     * <p>E o receptor do pacote precisa deste valor tambem: a Tela 3 (criada pelo servidor,
     * que responde com o {@code focusId}) nasce sem nenhuma referencia aonde voltar, e sem
     * isso o `Esc` dela jogava a jogadora no jogo em vez do menu.
     */
    protected final Screen parentScreen;

    /** A anotacao do card em "Del?", esperando o segundo clique, ou -1. */
    private int delPending = -1;

    // --- widgets dos cards ---

    private final List<Button> cardWidgets = new ArrayList<>();

    /** Um pino por linha visivel, na MESMA ordem de {@link #drawnRows}. */
    private final List<Button> pinButtons = new ArrayList<>();

    /** Um Del por linha visivel, na MESMA ordem de {@link #drawnRows}. */
    private final List<Button> delButtons = new ArrayList<>();

    /**
     * As anotacoes dos cards visiveis, do primeiro ao ultimo.
     *
     * <p><b>Por que uma lista a parte:</b> quem decide o que aparece no hover
     * ({@link #updateCardHover}) precisa saber se o card da linha esta fixado, e o widget
     * sozinho nao sabe -- o botao foi criado com rotulo vazio e o estado mora na anotacao.
     */
    private final List<DiaryEntry> drawnRows = new ArrayList<>();

    protected DiaryScreenBase(Screen parentScreen) {
        super(Component.literal(SCREEN_TITLE));
        this.parentScreen = parentScreen;
    }

    /**
     * A tela que estava aberta antes do diario.
     *
     * <p>Publico porque o {@code Screen} que o servidor manda criar (a Tela 3, via
     * {@code focusId}) precisa descobrir de onde o diario veio, e ela nao tem a tela
     * anterior na maos -- ela so tem o id do no.
     */
    public final Screen originScreen() {
        return parentScreen;
    }

    // ------------------------------------------------------------------ layout

    /**
     * Mede o painel, menos a posicao vertical da lista.
     *
     * <p><b>Por que {@code 4 * PAD} e nao {@code 2 * PAD} no calculo da altura:</b> a
     * altura do painel e {@code 2 * PAD + chrome + listHeight} e o {@code panelY} nunca e
     * menor que {@code PAD}. Logo o espaco REAL que a lista pode tomar e
     * {@code height - 4 * PAD - chrome}. Contar so as duas margens do painel fazia o painel
     * passar da tela em 427x240 -- 244 px num painel de 240 -- e o que sobrava embaixo era o
     * rodape. (Cópia da conta da tela de Presets, com o mesmo comentário lá.)
     *
     * <p><b>Por que a anotacao e o numero de linhas crescem com a tela:</b> a jogadora
     * pediu um quadro mais alto "aumentando as listas e as descricoes". Altura fixa
     * significava ou painel miudo em tela grande ou rodape fora da tela em tela pequena --
     * e o minimo que o jogo produz (320x240) ja ocupa a tela toda com os numeros antigos.
     * A sobra depois de fechar o minimo vai primeiro para a anotacao, ate {@link #DESC_MAX},
     * e o que ainda sobrar volta para a lista, ate {@link #MAX_ROWS}.
     *
     * @param chromeH tudo que nao e lista <b>medido com {@link #DESC_MIN}</b>, pelas
     *                subclasses. O que sobra e acrescentado aqui, em {@link #descHeight}.
     */
    protected void layoutPanel(int chromeH) {
        panelWidth = Math.min(this.width - 2 * PAD - 4, PANEL_MAX);
        panelX = (this.width - panelWidth) / 2;
        listRowHeight = ROW_H;

        int available = this.height - 4 * PAD - chromeH;
        // Uma linha e o piso absoluto. Forcar mais punha o rodape fora da tela, que e o
        // mesmo defeito que este calculo corrige.
        int rows = Math.min(MAX_ROWS, Math.max(1, available / ROW_H));
        int spare = available - rows * ROW_H;

        // A sobra vai para a anotacao primeiro, ate o teto. `Math.max(0, ...)`: em tela
        // pequena `spare` e NEGATIVO (uma unica linha nao cabe em sobra nenhuma), e sem o
        // piso a anotacao encolheria abaixo do minimo justamente onde ela precisa caber.
        int descExtra = Math.max(0, Math.min(spare, DESC_MAX - DESC_MIN));
        descHeight = DESC_MIN + descExtra;

        // E o que ainda sobrou depois da anotacao volta para a lista. O piso de uma linha
        // se repete aqui: com `spare` muito negativo (abaixo do minimo declarado) a divisao
        // trunca para -1 e a lista ficaria com ZERO linhas, com o rodape colado nela.
        rows = Math.max(1, Math.min(MAX_ROWS, rows + (spare - descExtra) / ROW_H));
        visibleRows = rows;
        // A altura volta a ser multipla da linha: sobra de meio pixel viraria uma faixa
        // vazia no fim da lista.
        listHeight = visibleRows * ROW_H;

        panelHeight = 2 * PAD + chromeH + descExtra + listHeight;
        panelY = Math.max(PAD, (this.height - panelHeight) / 2);

        // A lista pode ter encolhido com a janela e o `listScroll` antigo apontaria para
        // uma linha que nao existe mais.
        listScroll = Math.min(listScroll, maxScroll());
    }

    /** Poe a lista na vertical. As subclasses montam o que esta acima dela. */
    protected void placeList(int top) {
        listTop = top;
        listBottom = top + listHeight;
    }

    /**
     * Mede o layout inteiro em uma linha e joga no log.
     *
     * <p><b>Por que logar:</b> estourar a tela nao da erro nem aviso: os botoes
     * simplesmente nao aparecem, e o log do jogo nao diz nada. Com estas medidas no log um
     * layout quebrado aparece como numero.
     */
    protected void logLayout(String screenName) {
        TabletopRpgClient.LOGGER.info(
                "[TabletopRPG] {} layout: tela={}x{} painel={}x{} em ({},{}) lista={}..{} "
                        + "({} linha(s) de {}) tituloX={} tituloW={} delX={} rodapeY={}",
                screenName, this.width, this.height, panelWidth, panelHeight, panelX, panelY,
                listTop, listBottom, visibleRows, listRowHeight,
                titleX(), titleWidth(), delX(), footerY);
    }

    /** A base nao sabe o rodape; cada tela o posiciona no seu layout. */
    protected int footerY;

    /** Os botoes do rodape, para poder recria-los quando o Reverter aparece ou some. */
    protected final List<Button> footerWidgets = new ArrayList<>();

    /**
     * Um botao do rodape, ja registrado para ser removido na proxima reconstrucao.
     *
     * <p><b>Por que a largura sai da fonte e nao e fixa:</b> "Reverter" e "Voltar" tem
     * larguras muito diferentes, e um numero fixo deixaria um deles estourando o botao ou
     * sobrando espaco por dentro.
     */
    protected Button addFooterButton(String label, int x, int y, int w, Runnable onPress) {
        Button button = Button.builder(Component.literal(label), b -> onPress.run())
                .bounds(x, y, Math.max(24, w), ROW_H - 2)
                .build();
        addRenderableWidget(button);
        footerWidgets.add(button);
        return button;
    }

    protected void clearFooterWidgets() {
        for (Button old : footerWidgets) {
            removeWidget(old);
        }
        footerWidgets.clear();
    }

    // ---------------------------------------------------------- geometria do card

    protected int cardLeft() {
        return panelX + PAD + 2;
    }

    protected int pinX() {
        return cardLeft();
    }

    /**
     * O Del e o ultimo botao da linha, com a barra de rolagem reservada a direita.
     *
     * <p><b>Por que a posicao e fixa:</b> o pino e o Del somem no hover, e se o titulo
     * usasse a largura que sobrava entre eles, ele PULARIA 20 px para a esquerda toda vez
     * que o mouse entrasse na linha. O titulo e sempre desenhado na mesma caixa, e o que
     * aparece e o que muda.
     */
    protected int delX() {
        return panelX + panelWidth - PAD - SB_W - TIGHT_GAP - CARD_BTN_W;
    }

    protected int titleX() {
        return pinX() + CARD_BTN_W + CARD_BTN_GAP;
    }

    /**
     * A faixa reservada a direita do titulo para o ponto de "tem alteracao para salvar".
     *
     * <p><b>Por que sempre reservada, mesmo sem pendencia:</b> se a faixa so existisse
     * quando o ponto aparecesse, o titulo util ganharia 6 px e perderia 6 px a cada marcacao
     * -- e o texto do card saltaria exatamente quando a jogadora esta lendo para conferir o
     * que ela acabou de mudar.
     */
    protected static final int PENDING_DOT_W = 6;

    protected int pendingDotX() {
        return titleX() + titleWidth() - PENDING_DOT_W;
    }

    protected int titleWidth() {
        return Math.max(30, delX() - GAP - titleX());
    }

    /**
     * O titulo do card, cortado com reticencias para caber no botao.
     *
     * <p><b>Por que aqui e nao no dado:</b> {@link DiaryEntry#fit} corta por numero de
     * caractere, e o que decide o corte real e a LARGURA em pixels do botao -- que muda com a
     * resolucao. A regua e a largura; o "..." e o que a jogadora pediu.
     *
     * <p><b>Por que {@code plainSubstrByWidth}:</b> ele devolve o pedaco que CABE (uma
     * {@code String}), e nao o indice onde o texto acaba. Sao dois retornos diferentes e
     * trocar um pelo outro da um indice crudo na tela.
     *
     * <p><b>Por que descontar a faixa do pendente:</b> o ponto e desenhado por cima da
     * area util do titulo. Cortar o texto um pouco antes e o que impede um titulo longo de
     * ir por baixo do ponto.
     */
    protected String fitTitle(DiaryEntry entry) {
        int inner = titleWidth() - 6 - PENDING_DOT_W; // 6 px: a margem interna do vanilla
        String text = entry.title();
        if (this.font.width(text) <= inner) {
            return text;
        }
        String head = this.font.plainSubstrByWidth(text, Math.max(0, inner - this.font.width("...")));
        return head + "...";
    }

    // ------------------------------------------------------------ lista e cards

    /** Os cards que esta tela mostra, ja filtrados e ordenados. */
    protected abstract List<DiaryEntry> visibleEntries();

    /** O que fazer quando a jogadora aperta o titulo de um card. */
    protected abstract void openEntry(DiaryEntry entry);

    /** Refaz o rodape depois de uma mudanca de estado (o Reverter e condicional). */
    protected abstract void rebuildFooter();

    /**
     * Registra se o {@code [Reverter]} foi criado nesta montagem do rodape.
     *
     * <p>Chamado pelo {@code rebuildFooter} das duas telas, nao pelo {@code render}: o
     * rodape e quem cria o botao, entao e ele que sabe. O {@code render} so compara este
     * valor com a pilha para decidir se precisa remontar.
     */
    protected void setBotaoReverterVisivel(boolean visivel) {
        botaoReverterVisivel = visivel;
    }

    protected int totalRows() {
        return visibleEntries().size();
    }

    protected int maxScroll() {
        return Math.max(0, totalRows() - visibleRows);
    }

    protected void clampScroll() {
        listScroll = Math.max(0, Math.min(maxScroll(), listScroll));
    }

    /**
     * Recria so os widgets dos cards visiveis.
     *
     * <p><b>Por que remover antes de criar:</b> sem isso os botoes se acumulam a cada
     * clique de pino, de Del ou de ordenacao, e o {@link #render} passa a resolver o hover
     * sobre widgets que nao estao mais na tela.
     */
    protected void rebuildList() {
        for (Button old : cardWidgets) {
            removeWidget(old);
        }
        cardWidgets.clear();
        pinButtons.clear();
        delButtons.clear();
        drawnRows.clear();

        List<DiaryEntry> list = visibleEntries();
        int from = listScroll;
        int to = Math.min(list.size(), listScroll + visibleRows);
        int btnH = listRowHeight - 2;

        for (int i = from; i < to; i++) {
            DiaryEntry entry = list.get(i);
            int rowY = listTop + (i - listScroll) * listRowHeight;
            drawnRows.add(entry);

            // O pino: um botao com rotulo VAZIO. O desenho do pino e feito no
            // `drawListOverlay`, por cima; um texto nao caberia nos 20 px, e um emoji
            // dependeria da fonte da jogadora.
            Button pin = Button.builder(Component.empty(), b -> togglePin(entry))
                    .bounds(pinX(), rowY, CARD_BTN_W, btnH)
                    .tooltip(Tooltip.create(Component.literal(entry.pinned() ? "Desfixar" : "Fixar")))
                    .build();
            addCard(pin);
            pinButtons.add(pin);

            addCard(Button.builder(Component.literal(fitTitle(entry)), b -> openEntry(entry))
                    .bounds(titleX(), rowY, titleWidth(), btnH)
                    .build());

            boolean pending = delPending == entry.id();
            Button del = Button.builder(Component.literal(pending ? "Del?" : "Del"), b -> onDelete(entry))
                    .bounds(delX(), rowY, CARD_BTN_W, btnH)
                    .tooltip(Tooltip.create(Component.literal(pending
                            ? "Clique de novo para confirmar"
                            : "Apagar esta anotação e tudo que estiver dentro dela")))
                    .build();
            addCard(del);
            delButtons.add(del);
        }
    }

    private void addCard(Button button) {
        addRenderableWidget(button);
        cardWidgets.add(button);
    }

    /**
     * Apagar com confirmacao em dois cliques, igual a lista de skills, magias e presets.
     *
     * <p>O primeiro clique so marca; o segundo envia. O estado e o id da anotacao, e nao a
     * linha: {@code listScroll} muda e o indice da linha deixaria de valer.
     */
    private void onDelete(DiaryEntry entry) {
        if (delPending == entry.id()) {
            delPending = -1;
            ClientPlayNetworking.send(new RpgNetworking.DiaryDeletePayload(entry.id()));
            // A exclusao entra na pilha do [Reverter] no MESMO instante do pacote, e nao
            // quando o servidor responde: a ordem que a jogadora fez as coisas e a ordem
            // dos cliques, e ela pode editar e apagar no mesmo quadro. (03/10/2026)
            DiaryUndo.registrar(DiaryUndo.Tipo.EXCLUSAO, entry.id());
        } else {
            delPending = entry.id();
        }
        rebuildList();
    }

    private void togglePin(DiaryEntry entry) {
        ClientPlayNetworking.send(new RpgNetworking.DiaryPinPayload(entry.id(), !entry.pinned()));
    }

    /**
     * O {@code [Reverter]}: desfaz o ultimo passo, e so o ultimo.
     *
     * <p><b>Por que dois destinos, e nao um:</b> o passo do topo pode ser de dois tipos, que
     * vivem em lugares diferentes. Uma edicao esta so no cliente (navegar nao grava), e
     * desfaze-la e apagar o rascunho — o texto do servidor volta a ser o que a caixa mostra.
     * Uma exclusao foi aplicada no servidor, e desfaze-la e pedir o
     * {@code DiaryUndoPayload}, que recoloca a anotacao e as descendentes.
     *
     * <p><b>Por que a ordem e garantida:</b> as duas pilhas (a daqui e a do servidor) so
     * crescem por acao da jogadora, e so a do servidor recebe exclusoes. Topo aqui e topo
     * la, sempre que o passo for uma exclusao.
     *
     * <p><b>Por que o passo e removido depois, e nao antes:</b> um passo que falhou ao ser
     * executado nao pode sumir da pilha — a jogadora perderia o {@code [Reverter]} sem que
     * nada tivesse sido desfeito.
     */
    protected void reverter() {
        DiaryUndo.Passo passo = DiaryUndo.proximo();
        if (passo == null) {
            return;
        }
        if (passo.tipo() == DiaryUndo.Tipo.EDICAO) {
            DiaryDrafts.clear(passo.nodeId());
            // A caixa da tela aberta e reescrita a cada quadro pelo `cacheDraft`: sem
            // devolver o texto do servidor aqui, o rascunho recriado em um quadro e o
            // desfazer não teria efeito nenhum.
            aoReverterRascunho(passo.nodeId());
            rebuildList();
        } else {
            ClientPlayNetworking.send(new RpgNetworking.DiaryUndoPayload());
        }
        DiaryUndo.remover(passo);
    }

    /**
     * O texto voltou ao servidor: a tela que estiver mostrando aquele no precisa mostrar o
     * texto antigo.
     *
     * <p>Vazio por padrao, porque a Tela 1 nao tem caixa de edicao. A tela de no
     * sobrescreve.
     */
    protected void aoReverterRascunho(int nodeId) {
    }

    /**
     * Manda ao servidor tudo o que a jogadora digitou e ainda nao saiu do diario.
     *
     * <p><b>Por que este metodo existe:</b> a jogadora pediu (03/10/2026) que navegar DENTRO
     * do diario nao grave, e que a gravacao so aconteca no [Salvar] ou na saida. Como
     * consequence, enquanto ela navega o texto novo existe so no {@link DiaryDrafts}, no
     * cliente. Sem esta etapa, sair do diario com um rascunho perderia a alteracao — o
     * defeito que ela reportou antes.
     *
     * <p><b>Por que com {@code accept = false}:</b> o {@code accept} limpa a pilha do
     * {@code [Reverter]} INTEIRA, e um pacote com {@code accept} aqui limparia sozinho, antes
     * dos demais textos. Quem aceita e o chamador, num pacote so no fim — assim o texto novo
     * e uma exclusao pendente entram juntos e a pilha esvazia uma unica vez.
     *
     * <p>Os ids sao percorridos sem ordem definida ({@code HashMap}). Isso e proposital:
     * nenhuma gravacao depende de outra, e o servidor aceita qualquer ordem.
     */
    protected void flushLocalDrafts() {
        for (Map.Entry<Integer, String[]> pending : DiaryDrafts.all().entrySet()) {
            int id = pending.getKey();
            String[] text = pending.getValue();
            if (text == null || text[0].trim().isEmpty()) {
                // Titulo vazio: o servidor recusa (ver `DiaryStore.save`). O rascunho e
                // descartado no `clearAll` do fim, para nao reaparecer na caixa.
                continue;
            }
            if (entryById(id) == null) {
                // A anotacao foi apagada durante a sessao (Del? em duplo clique). Nao ha
                // o que gravar, e o servidor recusaria com "entry does not exist".
                continue;
            }
            ClientPlayNetworking.send(new RpgNetworking.DiarySavePayload(
                    id, text[0].trim(), text[1], false));
        }
        DiaryDrafts.clearAll();
    }

    /** A anotacao de um id na copia que o servidor mandou, ou {@code null} se sumiu. */
    protected DiaryEntry entryById(int id) {
        for (DiaryEntry entry : entries) {
            if (entry.id() == id) {
                return entry;
            }
        }
        return null;
    }

    /** Ha texto digitado que ainda nao foi aceito? E o que acende o ponto amarelo. */
    protected boolean hasLocalDrafts() {
        return DiaryDrafts.size() > 0;
    }

    /**
     * O pino e o Del aparecem só no hover, e o pino de um card fixado fica sempre.
     *
     * <p><b>Por que antes de {@code super.render}:</b> a visibilidade e lida no desenho dos
     * widgets, entao resolver depois esconderia os botoes por um quadro.
     *
     * <p><b>Por que o pino fixado nao some:</b> o card fixado e o unico que esta fora da
     * ordem do criterio escolhido. Se o pino so aparecesse no hover, descobrir o que estava
     * fixado exigiria passar o mouse por todos os cards da lista.
     */
    protected void updateCardHover(int mouseX, int mouseY) {
        int hovered = -1;
        for (int j = 0; j < visibleRows; j++) {
            int rowY = listTop + j * listRowHeight;
            if (mouseX >= panelX && mouseX < panelX + panelWidth
                    && mouseY >= rowY && mouseY < rowY + listRowHeight) {
                hovered = j;
                break;
            }
        }
        for (int j = 0; j < pinButtons.size(); j++) {
            boolean pinned = j < drawnRows.size() && drawnRows.get(j).pinned();
            pinButtons.get(j).visible = pinned || j == hovered;
            delButtons.get(j).visible = j == hovered;
        }
    }

    /**
     * Troca o criterio e redesenha.
     *
     * <p><b>Por que volta o scroll para o topo:</b> trocar o criterio muda o que esta em
     * cada linha. Manter o offset deixaria a jogadora no meio de uma lista reordenada, sem
     * saber o que mudou.
     */
    protected void toggleSort() {
        sortMode = sortMode.next();
        listScroll = 0;
        rebuildFooter();
        rebuildList();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // O hover e resolvido AQUI, antes de delegar, e nao so no `render`.
        //
        // <b>Por que:</b> o vanilla so entrega o clique a um widget que esteja `visible`, e a
        // visibilidade do pino e do "Del?" e escrita no `render`. Um clique que chega antes
        // do proximo frame -- o mouse acabou de entrar na linha e a jogadora clicou -- era
        // descartado em silencio, e o sintoma era "alguns fixam e outros nao", sem nenhuma
        // regra que a jogadora conseguisse deduzir. Resolvendo antes do `super`, o clique ve
        // sempre a visibilidade correta. (02/10/2026)
        updateCardHover((int) event.x(), (int) event.y());
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseY >= listTop && mouseY < listBottom && maxScroll() > 0) {
            // `scrollY` e o offset vertical do GLFW e vale POSITIVO ao rolar para CIMA, e o
            // vanilla o repassa direto. Descer a lista e `scrollY` negativo, que tem que
            // AUMENTAR o offset: `- (int) -Math.signum(scrollY)` = `+1`.
            //
            // Esta e a MESMA linha da tela de Presets, de proposito: o gesto invertido ja
            // aconteceu uma vez neste projeto e a jogadora avisou para nao se repetir.
            listScroll = Math.max(0, Math.min(maxScroll(), listScroll + (int) -Math.signum(scrollY)));
            rebuildList();
            return true;
        }
        // Fora da lista, quem rola e o campo de descricao, e nao a tela: devolver `false`
        // deixa o `MultiLineEditBox` tratar as suas proprias linhas.
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // ------------------------------------------------------------------ desenho

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, this.width, this.height, 0x99000000);

        // O painel usa a altura do `layout`, e nao uma soma de "y ate onde estava o botao".
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0xFF202020);
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + 1, 0xFF6B4A2A);
        graphics.fill(panelX, panelY + panelHeight - 1, panelX + panelWidth, panelY + panelHeight,
                0xFF6B4A2A);

        updateCardHover(mouseX, mouseY);

        // O [Reverter] nasce quando a pilha deixa de estar vazia e morre quando esvazia.
        // O servidor so avisa por `applyState`, e uma edicao nao chega ao servidor ate o
        // [Salvar] ou a saida: sem este gancho, digitar deixaria o aviso aceso sem nenhum
        // botao para desfazer. So dispara NA VIRADA, e nao por quadro, para nao remontar o
        // rodape 60 vezes por segundo. (03/10/2026)
        if (DiaryUndo.tem() != botaoReverterVisivel) {
            rebuildFooter();
        }

        // O fundo da lista ANTES de `super.render`: o `fill` e opaco, desenhado depois
        // taparia os botoes das linhas, que continuariam clicaveis porque clique nao
        // depende de ordem de desenho. Era esse o bug de "o Del nao aparece mas da para
        // clicar nele".
        graphics.fill(panelX + 2, listTop, panelX + panelWidth - PAD - SB_W, listBottom, 0xFF161616);

        super.render(graphics, mouseX, mouseY, delta);

        // `SCREEN_TITLE` e nao `this.title`: o `Screen` so aceita o titulo no construtor e nao o
        // expoe. Ver a constante.
        graphics.drawCenteredString(this.font, SCREEN_TITLE, this.width / 2, panelY + PAD, 0xFFE0C080);
        drawHeader(graphics);
        drawListOverlay(graphics);
        drawScrollbar(graphics);
    }

    /** O que a tela escreve sobre a lista: caminho, legendas. Vazio na Tela 1 alem do campo. */
    protected void drawHeader(GuiGraphics graphics) {
    }

    /**
     * O pino desenhado por cima do botao vazio.
     *
     * <p><b>Por que desenhado e nao escrito:</b> 20 px de botao nao cabem texto nenhum, e
     * um emoji depende da fonte instalada na maquina da jogadora. Tres retangulos formam a
     * cabeca e a haste, e funcionam em qualquer resolucao e qualquer fonte.
     */
    private void drawListOverlay(GuiGraphics graphics) {
        for (int j = 0; j < drawnRows.size(); j++) {
            DiaryEntry entry = drawnRows.get(j);
            int rowY = listTop + j * listRowHeight;
            int midY = rowY + (listRowHeight - 2) / 2;

            // O ponto de pendente NAO e condicional a hover: e a unica pista de que existe
            // rascunho em outra secao, e esconder no mouse deixaria a jogadora sem como
            // saber o que ainda falta aplicar.
            //
            // Houve uma moldura amarela em volta da linha aqui (03/10/2026), a pedido dela,
            // e voltou a ser so o ponto: "fica melhor so com a bolinha mesmo". Nao ha
            // geometria de moldura sobrando no arquivo — o que ela pidiu foi revertido por
            // completo, nao escondido atras de um flag.
            if (hasPending(entry)) {
                drawPendingDot(graphics, pendingDotX(), midY + 1);
            }

            if (!pinButtons.get(j).visible) {
                continue;
            }
            int cx = pinX() + CARD_BTN_W / 2;
            int cy = midY;
            int color = entry.pinned() ? 0xFFE0C080 : 0xFF9A9A9A;
            graphics.fill(cx - 2, cy - 4, cx + 3, cy - 3, color);   // cabeca
            graphics.fill(cx - 3, cy - 3, cx + 4, cy - 2, color);
            graphics.fill(cx - 2, cy - 2, cx + 3, cy - 1, color);   // afunila
            graphics.fill(cx, cy - 1, cx + 1, cy + 4, color);       // haste
        }
    }

    /**
     * A barra de rolagem, na faixa reservada a direita da lista.
     *
     * <p><b>Por que a proporcao e por linha e nao por pixel:</b> o polegar tem de ocupar a
     * mesma fracao da lista que as linhas visiveis ocupam do total. Com a lista de 20 linhas
     * mostrando 6, o polegar e um terco da faixa; com 8 linhas o polegar e quase a faixa
     * inteira, e por isso tem um piso de 12 px para continuar clicavel.
     */
    private void drawScrollbar(GuiGraphics graphics) {
        if (maxScroll() <= 0) {
            return;
        }
        int trackX = panelX + panelWidth - PAD - SB_W;
        graphics.fill(trackX, listTop, trackX + SB_W, listBottom, 0xFF101010);

        int total = totalRows();
        int thumbH = Math.max(12, listHeight * visibleRows / Math.max(visibleRows, total));
        // O polegar anda na faixa MENOS a propria altura: e o que faz ele nunca sair da
        // faixa quando a lista esta quase toda visivel.
        int travel = Math.max(1, listHeight - thumbH);
        int thumbY = listTop + travel * listScroll / Math.max(1, maxScroll());
        graphics.fill(trackX, thumbY, trackX + SB_W, thumbY + thumbH, 0xFF6B4A2A);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Ja desenhado em `render`: fundo escuro e painel sao `fill`, nao textura.
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---------------------------------------------- estado que chega do servidor

    /**
     * A resposta do servidor para a tela aberta.
     *
     * <p><b>Por que NAO recarrega os campos:</b> o estado chega depois de QUALQUER acao,
     * inclusive de um clique num card vizinho. Recarregar o titulo e a descricao a cada
     * estado apagaria o que a jogadora estava digitando -- e o pior caso (criar subsecao
     * enquanto se edita) nao apareceria em log nenhum.
     */
    public void applyState(List<DiaryEntry> newEntries, boolean newCanUndo,
                            List<Integer> newPendingIds) {
        entries = newEntries == null ? new ArrayList<>() : newEntries;
        if (!newCanUndo) {
            // O servidor diz que nao tem nada na pilha dele. As exclusoes do cliente so
            // existem para pedir um `DiaryUndoPayload`, e sem pilha la esse pedido nao
            // desfaz nada — o botao ficaria acionado em um no-op. As edicoes nao sao
            // tocadas: elas vivem so aqui e o servidor nao tem como saber que existem.
            //
            // Na pratica so acontece se o servidor reiniciar com o diario aberto, e mesmo
            // assim e melhor um botao que sumiu do que um que nao desfaz. (03/10/2026)
            DiaryUndo.removerExclusoes();
        }
        pendingIds.clear();
        if (newPendingIds != null) {
            pendingIds.addAll(newPendingIds);
        }
        if (delPending != -1 && find(delPending) == null) {
            // O card em "Del?" saiu da lista (apagado, ou por um Reverter). Confirmar depois
            // deixaria a confirmacao presa num id que nao existe mais.
            delPending = -1;
        }
        clampScroll();
        rebuildFooter();
        rebuildList();
    }

    /**
     * Este no, ou algum descendente dele, tem texto para salvar?
     *
     * <p>A chamada e sobe por enquanto e desce ate achar pendente. O caminho que ela
     * percorre e a arvore de ancestrais do no, e nada mais: um irmao que tambem tem
     * rascunho NAO acende este cartao. E o que a jogadora pediu ("todos que vao ate o
     * caminho do que foi alterado").
     */
    protected boolean hasPending(DiaryEntry entry) {
        if (entry == null) {
            return false;
        }
        // OU entre as duas fontes de alteracao nao aceita (03/10/2026):
        //
        //  - `pendingIds`: o que o SERVIDOR tem e ainda nao foi aceito. E o texto que ja
        //    chegou ate la por um [Salvar] ou por saida de uma sessao anterior.
        //  - `DiaryDrafts`: o que esta NA CAIXA e a jogadora ainda nao aceitou. Navegar
        //    dentro do diario nao grava, entao este e o unico lugar onde o texto novo
        //    existe enquanto ela estaindo.
        //
        // Sem o segundo, o aviso so apareceria DEPOIS de gravar — que e o contrario do que
        // ela pediu ("aparece o aviso visual corretamente" ao digitar, antes de salvar).
        return DiaryStore.hasPendingInSubtree(entries, entry.id(), pendingIds)
                || DiaryDrafts.hasInSubtree(entries, entry.id());
    }

    /**
     * Desenha o ponto amarelo de "tem alteracao para salvar", no canto direito do cartao.
     *
     * <p><b>Por que dentro do botao do titulo e nao ao lado dele:</b> ao lado custaria os
     * 2 px do pino e do Del, e o texto do titulo mudaria de largura conforme a marcacao
     * aparecia e sumia. Dentro, com a faixa reservada sempre, o titulo nunca salta.
     *
     * <p><b>Por que nao decide sozinha se ha pendencia:</b> quem chama ja consultou
     * `hasPending(entry)` para saber se desenha, e o metodo repetiria a busca por `find`.
     * Um metodo que "consulta e desenha" obriga o chamador a saber a ordem das duas coisas.
     */
    protected void drawPendingDot(GuiGraphics graphics, int dotX, int midY) {
        graphics.fill(dotX, midY - 2, dotX + 3, midY + 1, 0xFFFFE24B);
    }

    protected DiaryEntry find(int id) {
        for (DiaryEntry entry : entries) {
            if (entry.id() == id) {
                return entry;
            }
        }
        return null;
    }

    protected boolean isEmptyView() {
        return totalRows() == 0;
    }

    /** Texto quando a lista esta vazia, que depende de se e busca sem resultado. */
    protected abstract String emptyListHint();
}