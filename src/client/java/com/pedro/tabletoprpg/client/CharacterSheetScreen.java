package com.pedro.tabletoprpg.client;

import com.pedro.tabletoprpg.RpgNetworking;
import com.pedro.tabletoprpg.SheetData;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Base das telas de ficha do personagem (FASE 3b).
 *
 * <p>Subclasses: {@link StatusScreen} (Status) e {@link SkillsScreen} (Skills).
 * A base concentrationa o que as duas compartilham: fundo escuro, painel
 * responsivo, sincronia com o servidor, campos editaveis e o botao Back.
 *
 * <p><b>Feedback do usuario que motivou esta reescrita:</b>
 * <ul>
 *   <li>a ficha nao tinha fundo e as letras eram cinzas/pequenas;</li>
 *   <li>nao cabia numa janela 1920x1080 (layout fixo, botoes fora da tela);</li>
 *   <li>nao dava para editar os campos (a caixa nascia com
 *       {@code setEditable(false)} porque {@code canEdit} ainda era false no
 *       primeiro {@code init()}, e sem fundo/contraste o campo parecia texto
 *       estatico). Aqui o estado de edicao fica <b>explicito</b> (rotulo
 *       "Editable"/"Read-only") e o texto da caixa tem contraste alto.</li>
 * </ul>
 *
 * <p><b>Autoridade do servidor:</b> toda edicao vai como payload e o valor so
 * entra na tela quando o servidor devolve a ficha ({@link #onSheetState}).
 * As setas das barras usam {@link #pendingNumeric} para nao perder cliques
 * rapidos: o valor exibido e otimista, e e substituido pelo do servidor assim
 * que ele chegar.
 */
public abstract class CharacterSheetScreen extends Screen {

    // ------------------------------------------------------------------
    // CONSTANTES DE LAYOUT (FASE 3b: responsivo)
    // ------------------------------------------------------------------

    /** Margem lateral minima do painel. */
    protected static final int PAD = 8;
    /** Altura de linha: encolhe em janelas baixas, nunca cresce demais. */
    protected static final int MIN_ROW_H = 12;
    protected static final int MAX_ROW_H = 20;
    /** Largura do painel: acompanha a janela, com teto. */
    protected static final int MIN_PANEL_W = 200;
    protected static final int MAX_PANEL_W = 360;
    /**
     * <b>DECISAO PROVISORIA:</b> quanto cada clique da seta muda o valor.
     * O usuario nao especificou; 1 por clique foi a escolha inicial e esta
     * constante existe para ser trocada num so lugar.
     */
    public static final int ARROW_STEP = 1;
    /**
     * Altura da tinta de um glifo da fonte padrao (ascendente 7, 1 linha de
     * folga). E o que o bloco de 2 linhas gasta por linha de rotulo.
     */
    protected static final int LABEL_GLYPH_H = 8;
    /**
     * Passo entre as duas linhas de um rotulo quebrado (fonte de 8px + 1).
     *
     * <p>27/09/2026: passou a ser o <b>maior</b> passo, e nao um fixo. Com ele
     * fixo, o bloco so fechava com {@code rowH >= 18}, e {@code fitRowHeight}
     * chega ao piso de {@link #MIN_ROW_H} (12) em janela baixa -- a resolucao
     * de referencia do projeto (480x270, escala 4) fica em 13, e o wrap que o
     * usuario pediu simplesmente nao acontecia ali. Ver
     * {@link #labelBlockAdvance()}.
     */
    protected static final int LABEL_LINE_ADVANCE = 9;
    /**
     * Menor passo entre as duas linhas de um rotulo quebrado.
     *
     * <p>27/09/2026: 8px e a altura da tinta, entao com passo 8 a linha de baixo
     * comeca logo abaixo da de cima, sem compartilhar nenhum pixel. Abaixo
     * disso as duas linhas se sobrepoem e o rotulo vira uma mancha -- e nenhum
     * ganho de espaco justifica isso. Quando a linha da ficha e baixa demais ate
     * para este passo, o wrap <b>nao</b> e forcado: o rotulo volta a uma linha
     * so, agora cortado <b>com reticencias</b> ({@link #truncateWithEllipsis}).
     */
    protected static final int MIN_LABEL_ADVANCE = 8;
    /**
     * Altura do bloco de 2 linhas do rotulo com o passo cheio: o passo mais a
     * altura da fonte.
     *
     * <p>27/09/2026: e o pior caso do bloco. Com o passo apertado
     * ({@link #labelBlockAdvance()}) o bloco e menor que este, e o que decide
     * se o rotulo cabe na linha e o bloco <b>calculado</b>, mais 1px de folga
     * antes da linha de baixo (e nao a caixa de valor, que esta na horizontal).
     */
    protected static final int LABEL_BLOCK_H = LABEL_LINE_ADVANCE + LABEL_GLYPH_H;
    /**
     * Marca de texto cortado.
     *
     * <p>27/09/2026: tres pontos em vez de reticencias tipografico, porque o
     * ponto e o glifo mais barato da fonte padrao (2px): a marca gasta 6px e
     * sobra mais texto visivel do que um glifo unico largo -- e nao depende de
     * a fonte em uso ter aquele caractere.
     */
    protected static final String TRUNCATION_MARK = "...";
    /**
     * Sem teto de valor no filtro numerico da caixa (0).
     *
     * <p>27/09/2026: e o que mantem o limite antigo de 9 digitos em quem nao
     * recebe teto por campo ({@code level} e {@code xp}), que o usuario pediu
     * para NAO mexer.
     */
    protected static final int NO_CEILING = 0;

    // ------------------------------------------------------------------
    // CORES (painel escuro simples, feedback do usuario)
    // ------------------------------------------------------------------

    protected static final int COL_SCREEN_BG = 0xB0000000;
    protected static final int COL_PANEL_BG = 0xF216161C;
    protected static final int COL_TITLE = 0xFFFFFFFF;
    protected static final int COL_LABEL = 0xFFE6E6EE;
    protected static final int COL_SECTION = 0xFFFFC44D;
    protected static final int COL_MUTED = 0xFFA8A8B8;
    protected static final int COL_EDITABLE = 0xFF7FD48A;
    protected static final int COL_READONLY = 0xFFFF9A6B;
    protected static final int COL_BOX_TEXT = 0xFFFFFFFF;
    protected static final int COL_BOX_TEXT_OFF = 0xFF90909E;
    protected static final int COL_HP = 0xFFD43B3B;
    protected static final int COL_HP_BG = 0xFF431C1C;
    /**
     * Parte "temporaria" do HP (o que passa do maximo, ex.: 12/10). Mais clara
     * que {@link #COL_HP} para o jogador distinguir na hora o que e
     * permanente e o que e excedente.
     */
    protected static final int COL_HP_OVER = 0xFFFF8A8A;
    protected static final int COL_MANA = 0xFF3B7BD4;
    protected static final int COL_MANA_BG = 0xFF1B2A44;
    /** Parte "temporaria" da Mana (mesma ideia do {@link #COL_HP_OVER}). */
    protected static final int COL_MANA_OVER = 0xFF8AC4FF;
    protected static final int COL_BAR_EDGE = 0xFF55555F;
    protected static final int COL_DOWNED = 0xFFFF6060;

    /**
     * Um texto a desenhar no proximo {@code render} (montado em init).
     *
     * <p>27/09/2026: o texto e um {@link FormattedCharSequence} porque e ele que
     * {@code Font.split} devolve ao quebrar um rotulo em 2 linhas. Em 1.21.11
     * {@code FormattedText} <b>nao</b> estende {@code FormattedCharSequence}
     * (confirmado no jar), entao {@code Component} nao serve aqui: o texto
     * simples entra pelo construtor de {@link String}, que faz a conversao com
     * {@code FormattedCharSequence.forward}.
     */
    protected record TextLine(FormattedCharSequence text, int x, int y, int color) {
        /** Atalho para o texto simples, que e o caso comum. */
        TextLine(String text, int x, int y, int color) {
            this(FormattedCharSequence.forward(text, Style.EMPTY), x, y, color);
        }
    }

    /** Geometria de uma barra, calculada em init() e desenhada em render(). */
    protected record Bar(int x, int y, int w, int h) {

    }

    // ------------------------------------------------------------------
    // ESTADO
    // ------------------------------------------------------------------

    /** Nome do dono da ficha. Vazio = a propria ficha. */
    protected final String targetName;
    /** Tela para onde o Back volta. */
    private final Screen returnTo;

    /** Estado atual vindo do servidor; null enquanto nao chegou resposta. */
    protected SheetData sheet;
    /** Permissao de edicao decidida pelo SERVIDOR (a UI so reflete). */
    protected boolean canEdit;

    /** Caixas por campo, para atualizar valores sem recriar a tela. */
    protected final Map<String, EditBox> fieldBoxes = new LinkedHashMap<>();
    /** Textos a desenhar em render() (rotulos e titulos de secao). */
    protected final List<TextLine> textLines = new ArrayList<>();

    /**
     * Valores otimistas das barras (HP/Mana) e dos atributos. Clique rapido
     * antes da resposta do servidor geraria o mesmo valor duas vezes sem isto.
     *
     * <p>O eco do servidor <b>nao</b> apaga este mapa inteiro: ver
     * {@link #reconcilePendingNumeric} e {@link #keepPending}.
     */
    private final Map<String, Integer> pendingNumeric = new HashMap<>();

    /**
     * Quando true, alteracoes feitas pelos responders nao voltam ao servidor.
     * E o que impede o laco de eco: servidor devolve a ficha -> a tela preenche
     * as caixas -> setValue dispara o responder -> o cliente reenvia a edicao.
     */
    private boolean suppressNotify;

    // ------------------------------------------------------------------
    // GEOMETRIA (preenchida em init(), usada no render)
    // ------------------------------------------------------------------

    protected int panelX;
    protected int panelW;
    protected int rowH;
    protected int contentTop;
    protected int contentBottom;

    protected CharacterSheetScreen(String title, String targetName, Screen returnTo) {
        super(Component.literal(title));
        this.targetName = targetName == null ? "" : targetName;
        this.returnTo = returnTo;
    }

    /** true quando esta tela mostra a ficha do jogador local. */
    public boolean isOwnSheet() {
        return targetName.isEmpty();
    }

    /** Nome do dono da ficha exibida ("" = a local, ainda nao resolvida). */
    public String targetName() {
        return targetName;
    }

    // ------------------------------------------------------------------
    // SINCRONIA COM O SERVIDOR
    // ------------------------------------------------------------------

    /**
     * Chamado pelo receptor S2C quando o servidor devolve uma ficha. So
     * reaplica se for a ficha desta tela.
     *
     * <p>O filtro por {@code ownerUuid} importa no caso da ficha do proprio
     * jogador: como o mestre recebe tambem as fichas de todos os outros, uma
     * tela "minha ficha" sem este filtro exibiria a ficha de outro jogador
     * quando outro jogador edita o dele.
     */
    public void onSheetState(RpgNetworking.SheetStatePayload payload) {
        if (payload == null) {
            return;
        }
        if (targetName.isEmpty()) {
            var localPlayer = Minecraft.getInstance().player;
            if (localPlayer == null
                    || !localPlayer.getUUID().toString().equalsIgnoreCase(payload.ownerUuid())) {
                return;
            }
        } else if (!targetName.equalsIgnoreCase(payload.targetName())) {
            return;
        }
        this.sheet = payload.sheet();
        this.canEdit = payload.canEdit();
        // O servidor falou, e o autoritativo substitui o otimista -- mas so
        // quando ele ALCANCA o que foi enviado (ver reconcilePendingNumeric).
        reconcilePendingNumeric();
        onSheetReceived();
        applySheetToWidgets();
    }

    /**
     * Chamado <b>somente</b> quando uma ficha chega do servidor (e nao em cada
     * {@code init()}).
     *
     * <p><b>Por que o gancho existe:</b> a tela concilia o estado otimista
     * quando o servidor responde, para nao ficar um valor "fantasma" quando o
     * valor enviado e recusado ou corrigido pelo servidor (o que sobra e o que a
     * rajada ainda nao alcancou — {@link #keepPending}). Mas
     * {@code applyExtraState()} tambem roda no fim do {@code init()}, e
     * abrir/fechar uma tela filha (o dropdown de atributo) provoca um
     * {@code init()} <b>no mesmo instante</b> em que o otimismo foi
     * criado — o usuario veria o valor antigo ate a ficha voltar. Por isso o
     * "o servidor respondeu" e separado do "reconstrui os widgets".
     */
    protected void onSheetReceived() {
    }

    /**
     * Copia o estado do servidor para as caixas com a notificacao desligada
     * (para nao gerar payload de volta).
     */
    protected void applySheetToWidgets() {
        if (sheet == null) {
            return;
        }
        suppressNotify = true;
        try {
            for (Map.Entry<String, EditBox> entry : fieldBoxes.entrySet()) {
                String field = entry.getKey();
                EditBox box = entry.getValue();
                // <b>Nao sobrescreve o campo com foco.</b> Era esta linha a
                // causa do "apago o 3 e o 3 volta": o usuario digita, o
                // servidor responde com a ficha (o valor ainda e o antigo, ou
                // ja foi limitado) e a resposta escrevia por cima do texto
                // parcial, brigando com o cursor. Quem esta digitando manda no
                // proprio campo; o servidor so preenche os outros.
                if (box.isFocused()) {
                    applyEditable(box);
                    continue;
                }
                String next = SheetData.TEXT_FIELDS.contains(field)
                        ? sheet.getText(field)
                        : Integer.toString(sheet.getNumeric(field));
                // So escreve se mudou: evita reposicionar o cursor a cada tecla
                // digitada pelo proprio usuario.
                if (!next.equals(box.getValue())) {
                    box.setValue(next);
                }
                applyEditable(box);
            }
            applyExtraState();
        } finally {
            suppressNotify = false;
        }
    }

    /** Aplica {@code canEdit} numa caixa (usado tambem quando chega a ficha). */
    protected void applyEditable(EditBox box) {
        if (box == null) {
            return;
        }
        box.setEditable(canEdit);
    }

    /** Gancho para as subclasses ajustarem seus proprios widgets. */
    protected void applyExtraState() {
    }

    /**
     * Muda um numerico em {@code delta} e avisa o servidor.
     *
     * <p>O valor exibido e otimista ({@link #numericValue}) para que cliques
     * rapidos nao se percam; o servidor reenvia a ficha e o valor autoritativo
     * assume.
     *
     * <p><b>29/09/2026 (bug do valor otimista no HP/Mana):</b> o passo passou a
     * ser CLAMPADO ao piso e ao teto do <b>proprio campo</b>
     * ({@link #numericFloor}/{@link #numericCeiling}), e nao ao intervalo do
     * atributo. Sem isto, um passo grande tornava o bug alcancavel em UM clique:
     * com a Mana em 0, o botao de -10 gravava o pendente -10, o servidor cortava
     * para 0 e o eco trazia 0; como -10 != 0 e -10 estava dentro de [-30, 30], o
     * pendente sobrevivia a regra do eco e a tela ficava presa mostrando "-10"
     * com a barra vazia. O clamp no ponto de origem resolve: no limite o passo
     * vira no-op (nem pendente, nem envio), entao nao ha valor otimista invalido
     * para a regra do eco segurar.
     */
    protected void stepNumeric(String field, int delta) {
        if (!canEdit || sheet == null) {
            return;
        }
        int base = pendingNumeric.containsKey(field)
                ? pendingNumeric.get(field)
                : sheet.getNumeric(field);
        int next = Math.max(numericFloor(field),
                Math.min(numericCeiling(field), saturatingAdd(base, delta)));
        if (next == base) {
            return; // ja no limite do proprio campo: nao ha o que enviar
        }
        pendingNumeric.put(field, next);
        ClientPlayNetworking.send(new RpgNetworking.SheetFieldPayload(
                targetName, field, Integer.toString(next)));
    }

    /**
     * Piso legal do <b>proprio campo</b>, usado pelo clamp de
     * {@link #stepNumeric}.
     *
     * <p>29/09/2026: o padrao e "sem piso" de proposito. Quem ja tinha limite
     * proprio (o atributo, com piso e teto definidos pelo Mestre) e limitado em
     * outro lugar -- {@code StatusScreen} e o responsavel -- e nenhum outro
     * campo da base pode ser mudado aqui. A vida e a Mana sao os unicos com
     * piso fixo, e e o {@code StatusScreen} que sobrescreve este metodo (e o
     * do teto) para eles.
     */
    protected int numericFloor(String field) {
        return Integer.MIN_VALUE;
    }

    /** Teto legal do campo; ver {@link #numericFloor}. */
    protected int numericCeiling(String field) {
        return Integer.MAX_VALUE;
    }

    /** Valor a exibir: o otimista se houver, senao o do servidor. */
    protected int numericValue(String field) {
        Integer pending = pendingNumeric.get(field);
        if (pending != null) {
            return pending;
        }
        return sheet == null ? 0 : sheet.getNumeric(field);
    }

    /**
     * Regra do eco: um pendente so e descartado quando o autoritativo o
     * <b>alcancou</b> (iguais) ou quando ele e invalido para o campo.
     *
     * <p><b>28/09/2026, rajada por segurada — por que isto NAO pode ser um
     * {@code clear()}:</b> cada passo da rajada manda um payload, e cada payload
     * faz o servidor chamar {@code broadcastSheet}. Com 25 passos/s e uns 50 ms
     * de ida e volta, o eco do passo N chega quando o cliente ja esta no passo
     * N+3. O {@code clear()} apagava aqui o valor otimista ainda em uso: o
     * passo seguinte partia de um numero velho, reenviava um valor MENOR que o
     * ja gravado, e o numero subia e voltava na tela. O servidor nunca
     * corrompia (ele limita), mas a aceleracao prometida nao se materializava.
     *
     * <p><b>A regra:</b> pendente igual ao autoritativo = sincronizado, some.
     * Diferente = a rajada esta a frente do servidor, o pendente e o passo mais
     * recente e fica. Isso se auto-cura sem timeout e sem sinalizacao: o
     * servidor aplica em ordem, entao o <b>ultimo</b> eco traz o valor do
     * <b>ultimo</b> passo enviado -- que e exatamente o pendente -- e a
     * igualdade acontece sozinha.
     *
     * <p><b>Rede de seguranca:</b> um pendente FORA de
     * {@code [min, max]} e descartado mesmo sem igualdade. Sem isto, um eco que
     * nunca alcancasse o pendente (o servidor corrigindo por uma regra que o
     * cliente nao conhece) deixaria a tela mostrando um numero invalido
     * indefinidamente. Descartar volta ao valor autoritativo, que e o pior
     * caso aceitavel.
     *
     * @param pending       valor otimista guardado na tela
     * @param authoritative valor que veio do servidor neste eco
     * @param min           piso legal do campo
     * @param max           teto legal do campo
     * @return {@code true} se o pendente deve continuar valendo
     */
    protected static boolean keepPending(int pending, int authoritative, int min, int max) {
        if (pending == authoritative) {
            return false; // o servidor alcanco o ultimo passo enviado
        }
        return pending >= min && pending <= max;
    }

    /**
     * Aplica {@link #keepPending} a cada pendente de {@link #pendingNumeric},
     * campo a campo. E o eco do estado ({@link #onSheetState}); os resets
     * legitimos (modelo novo, tela recriada) limpam o mapa inteiro na mao.
     *
     * <p><b>28/09/2026:</b> a faixa legal deixou de ser a constante e passou a ser
     * <b>o intervalo que o Mestre definiu</b> e que veio na propria ficha
     * ({@code sheet.attributeValueMin/Max}). E o que mantem o eco coerente
     * depois de uma mudanca de limite: se o Mestre baixar o teto para 20 com um
     * valor otimista de 25 pendente, o 25 sai do mapa e o numero autoritativo
     * aparece, em vez de a tela ficar mostrando um valor que a ficha ja nao
     * aceita. Para os outros campos do mesmo mapa (HP e Mana, que nao tem teto
     * na tela) um pendente fora dessa faixa e simplesmente descartado: e a
     * direcao segura, e nao muda nada no uso normal, porque o eco do proprio
     * clique traz o mesmo numero.
     */
    private void reconcilePendingNumeric() {
        if (sheet == null) {
            pendingNumeric.clear();
            return;
        }
        pendingNumeric.entrySet().removeIf(entry -> !keepPending(entry.getValue(),
                sheet.getNumeric(entry.getKey()),
                sheet.attributeValueMin(), sheet.attributeValueMax()));
    }

    /**
     * Soma que trava no limite do {@code int} em vez de dar a volta.
     *
     * <p><b>Por que importa aqui:</b> os atributos tem teto e piso definidos pelo
     * Mestre (28/09/2026, vindos na ficha), e o valor de uma pericia tem piso 0.
     * Com {@code base + delta} simples, um valor em
     * {@code Integer.MAX_VALUE} mais um clique viraria {@code MIN_VALUE} e esse
     * numero negativo seria gravado na ficha do servidor.
     *
     * <p><b>Por que e {@code protected}:</b> e a <b>unica</b> conta desta tela
     * e {@code canStepAttribute} usa a mesma, para o botao e o passo nunca
     * discordarem sobre overflow ({@code ARROW_STEP} maior que 1 faria
     * {@code canStep} e {@code stepNumeric} divergirem). Nao ha duas politicas
     * de soma para conferir.
     */
    protected static int saturatingAdd(int base, int delta) {
        long sum = (long) base + delta;
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, sum));
    }

    // ------------------------------------------------------------------
    // LAYOUT
    // ------------------------------------------------------------------

    /**
     * O Mestre trocou o modelo e esta ficha esta aberta agora.
     *
     * <p>Precisa remontar tudo, e nao apenas redesenhar: o numero de campos,
     * de atributos e de pericias vem do modelo, entao os widgets velhos apontam
     * para linhas que podem nao existir mais. {@code rebuildWidgets()} limpa os
     * filhos e chama {@code init()}, que ja refaz o layout do zero.
     *
     * <p>Limpa o estado otimista inteiro, e nao so o que o eco alcancou
     * ({@link #keepPending}): aqui o valor pendente e do modelo ANTERIOR, e nao
     * de uma rajada em andamento. A rajada em si morre com os widgets
     * recriados.
     */
    public void onModelChanged() {
        pendingNumeric.clear();
        rebuildWidgets();
    }

    @Override
    protected void init() {
        super.init();

        // init() pode ser chamado de novo (resize): recomeca o layout limpo.
        textLines.clear();
        fieldBoxes.clear();

        // Painel centralizado que acompanha a janela (feedback: nao cabia em
        // 1920x1080). O teto evita um painel gigante em telas enormes.
        panelW = Math.max(MIN_PANEL_W, Math.min(this.width - 2 * PAD, maxPanelWidth()));
        if (panelW > this.width) {
            panelW = this.width;
        }
        panelX = (this.width - panelW) / 2;
        contentTop = 40;
        contentBottom = this.height - 30;

        buildPanel(panelX, panelW, contentTop, contentBottom);

        // Back: posicao fixa na base do painel (feedback: botao fora da tela
        // em janelas baixas). Se a tela quiser um botao extra no rodape (ex.:
        // Status -> Skills ao ver a ficha de OUTRO jogador), o Back encolhe.
        int backY = this.height - 24;
        Button extra = buildFooterExtra(panelX, backY, panelW, 20);
        if (extra != null) {
            addRenderableWidget(extra);
            addRenderableWidget(Button.builder(Component.literal("Back"), b -> close())
                    .bounds(panelX, backY, Math.max(60, panelW - 72), 20)
                    .build());
        } else {
            addRenderableWidget(Button.builder(Component.literal("Back"), b -> close())
                    .bounds(panelX, backY, panelW, 20)
                    .build());
        }

        // Preenche com o estado atual, se ja tiver chegado do servidor.
        applySheetToWidgets();
    }

    /**
     * Um tick da tela: e o que empurra o tempo para dentro dos
     * {@link HoldStepButton}, porque {@code AbstractWidget} nao tem
     * {@code tick()} (confirmado no jar do 1.21.11) e o estado de segurada mora
     * no widget, nao aqui.
     *
     * <p>So e preciso percorrer {@code children()}: e a lista onde
     * {@code addRenderableWidget} registra tudo, e o que o
     * {@code rebuildWidgets()} limpa. Por isso uma rajada que estava segurando
     * e cortada quando os widgets sao recriados (scroll da coluna de pericias,
     * troca de modelo, resize) — em vez de a tela ficar repetindo para sempre
     * sem ninguem para, que era o outro desfecho possivel.
     */
    @Override
    public void tick() {
        for (GuiEventListener child : children()) {
            if (child instanceof HoldStepButton hold) {
                hold.tick();
            }
        }
    }

    /**
     * Botao extra no rodape, a direita do Back. Padrao: nenhum (so o Back).
     * Quem implementa posiciona o botao dentro de {@code (x, y, w, h)}.
     */
    protected Button buildFooterExtra(int x, int y, int w, int h) {
        return null;
    }

    /**
     * Largura maxima do painel desta tela.
     *
     * <p>O padrao e {@link #MAX_PANEL_W}. A tela de Status <b>sobrescreve</b>
     * para caber a coluna de pericias ao lado: uma lista unica de 20 linhas
     * precisa de largura para o nome + as duas setas + o botao de atributo, e
     * estreitar o painel obrigaria a reduzir a fonte a ponto de o botao ficar
     * inutilizavel (decisao do usuario em 25/09/2026).
     */
    protected int maxPanelWidth() {
        return MAX_PANEL_W;
    }

    /**
     * Monta o conteudo da tela. As subclasses usam {@link #rowH},
     * {@link #contentTop} e {@link #contentBottom} (ja calculados) e devolvem
     * nada: {@code init()} cuida do Back e do preenchimento final.
     */
    protected abstract void buildPanel(int x0, int panelW, int topY, int bottomY);

    /** Altura de linha adaptativa: encolhe para caber na janela atual. */
    protected int fitRowHeight(int neededRows, int topY, int bottomY) {
        int available = Math.max(MIN_ROW_H, bottomY - topY);
        return Math.max(MIN_ROW_H, Math.min(MAX_ROW_H, available / Math.max(1, neededRows)));
    }

    /** Deslocamento vertical do rotulo para ficar centralizado na linha. */
    protected int labelOffset() {
        return Math.max(3, (rowH - 8) / 2);
    }

    /** Registra um titulo de secao e devolve o proximo Y. */
    protected int addSection(String title, int x0, int y) {
        // Sem largura reservada, o titulo pode ocupar a linha inteira da tela.
        return addSection(title, x0, y, this.width - x0);
    }

    /**
     * Titulo de secao limitado a {@code labelW}: o que for maior quebra em 2
     * linhas em vez de invadir o que esta a direita (a coluna de pericias, no
     * caso do Status).
     *
     * @see #addWrappedLabel
     */
    protected int addSection(String title, int x0, int y, int labelW) {
        addWrappedLabel(title, x0, y, labelW, COL_SECTION);
        return y + rowH;
    }

    /** Registra o rotulo de um campo, cria a caixa e devolve o proximo Y. */
    protected int addField(String field, int x0, int y, int boxX, int boxW,
                            int labelW, boolean numeric) {
        addWrappedLabel(SheetData.labelOf(field), x0, y,
                Math.min(labelW, Math.max(12, boxX - x0 - 4)), COL_LABEL);
        // A caixa NAO se move quando o rotulo quebra: ela ja ocupa a linha
        // inteira (rowH - 2) e o bloco de 2 linhas e centralizado nela, entao os
        // centros batem (ver addWrappedLabel) e a coluna de caixas continua
        // alinhada pixel a pixel -- mexer na Y aqui desalinharia as 4 caixas de
        // identidade quando so um rotulo quebra.
        fieldBoxes.put(field, createFieldBox(field, boxX, y, boxW, numeric, NO_CEILING));
        return y + rowH;
    }

    /**
     * Registra um rotulo e devolve quantas linhas ele ocupou (1 ou 2).
     *
     * <p><b>27/09/2026, bug do usuario:</b> "quando o nome de algo e muito
     * grande, ele atravessa os botoes/caixa de texto". Com "Pontos de
     * Determinacao (PD)" no rotulo de Mana, o texto media 140px numa coluna
     * reservada de 124px e invadia o botao "-" em 16px, porque o rotulo era
     * desenhado sem nenhum corte.
     *
     * <p><b>O que o usuario pediu:</b> quebrar para a linha de baixo e
     * <b>centralizar</b> conforme a caixa de texto a frente. Entao o caminho e:
     * <ol>
     *   <li>cabe na largura? desenha como antes, alinhado a esquerda e na
     *       mesma linha de sempre (layout aprovado, nao muda nada);</li>
     *   <li>quebra com {@code font.split}, que devolve
     *       {@link FormattedCharSequence} (String crua nao serve), e centraliza
     *       cada linha na largura reservada;</li>
     *   <li>ainda nao coube em 2 linhas? a 2a vira o <b>resto</b> do texto,
     *       cortado na largura reservada: e o ultimo recurso, e existe porque
     *       invadir o botao e pior do que cortar.</li>
     * </ol>
     *
     * <p><b>Altura do bloco:</b> as duas linhas sao centralizadas na linha
     * ({@code y + (rowH - blockH) / 2}), e nao ancoradas na caixa: com
     * {@code rowH} no maximo (20) e o passo cheio (9) o bloco ocupa de
     * {@code y+1} a {@code y+17}, o centro dele ({@code y+9,5}) e o mesmo do
     * texto da caixa (que o vanilla desenha em {@code y+6} numa caixa de 18px,
     * tambem com centro {@code y+9,5}), e a 2a linha para 3px antes da linha de
     * baixo. Ancorar a 1a linha no texto da caixa e correcto, mas a 2a vazava
     * para a linha seguinte.
     *
     * <p><b>27/09/2026, o wrap que o usuario pediu so funcionava em janela
     * alta:</b> o passo fixo de 9 com bloco de 17 so fechava com
     * {@code rowH >= 18}, e {@link #fitRowHeight} chega ao piso de
     * {@link #MIN_ROW_H} (12) -- a resolucao de referencia do projeto (480x270,
     * escala 4) fica em {@code rowH} 13, e ali o wrap nao acontecia e o rotulo
     * era cortado. O passo agora encolhe com a linha ({@link #labelBlockAdvance})
     * ate {@link #MIN_LABEL_ADVANCE}, e o bloco so entra quando ele fecha de
     * verdade na linha. Nao da para forcar o wrap em {@code rowH} 12 e 13: duas
     * linhas de tinta de 8px nao cabem em 13px sem se sobrepor. Nesse caso o
     * rotulo volta a uma linha so, agora com reticencias, para o Mestre VER
     * que o texto foi truncado em vez de ler um nome que parece completo.
     *
     * <p>A quebra e por palavra, como em {@code SkillsScreen} (popup da skill).
     *
     * @param labelW largura reservada ao rotulo (ate o que vem a direita)
     * @return 1 ou 2, quantas linhas foram desenhadas
     */
    protected int addWrappedLabel(String text, int x0, int y, int labelW, int color) {
        int maxW = Math.max(8, labelW);
        int advance = labelBlockAdvance();
        int blockH = advance + LABEL_GLYPH_H;
        // O bloco de 2 linhas so entra quando ele fecha na linha, mais 1px de
        // folga antes da linha de baixo (que e o que faltava no teste antigo,
        // fixo em LABEL_BLOCK_H). Fora disso o rotulo e cortado numa linha so,
        // com reticencias -- nunca invade a coluna da frente, que esta sempre
        // limitada por maxW.
        if (this.font.width(text) > maxW && blockH + 1 > rowH) {
            text = truncateWithEllipsis(text, maxW);
        }
        if (this.font.width(text) <= maxW) {
            textLines.add(new TextLine(text, x0, y + labelOffset(), color));
            return 1;
        }

        int blockTop = y + (rowH - blockH) / 2;
        Component literal = Component.literal(text);
        List<FormattedCharSequence> parts = this.font.split(literal, maxW);
        if (parts.size() > 2) {
            // 3 linhas ou mais nao cabem no bloco de 2. A 1a fica inteira e a 2a
            // recebe o resto do texto, cortado na largura reservada. A 1a linha
            // em texto simples sai do mesmo StringSplitter que o font.split usa
            // por tras (splitLines devolve FormattedText, que tem getString),
            // porque FormattedCharSequence nao tem length: so
            // accept(FormattedCharSink). O Math.min evita o_bounds quando o
            // rotulo tem caractere fora do plano basico, em que o indice do
            // codepoint nao bate com o indice UTF-16 do substring().
            String firstLine = this.font.getSplitter().splitLines(literal, maxW, Style.EMPTY)
                    .get(0).getString();
            String rest = text.substring(Math.min(firstLine.length(), text.length())).trim();
            parts = List.of(parts.get(0), FormattedCharSequence.forward(
                    truncateWithEllipsis(rest, maxW), Style.EMPTY));
        }
        for (int i = 0; i < parts.size(); i++) {
            FormattedCharSequence part = parts.get(i);
            textLines.add(new TextLine(part, x0 + (maxW - this.font.width(part)) / 2,
                    blockTop + i * advance, color));
        }
        return parts.size();
    }

    /**
     * Passo entre as duas linhas do rotulo quebrado, ja apertado na linha atual.
     *
     * <p>27/09/2026: era {@link #LABEL_LINE_ADVANCE} fixo, e o bloco de 2 linhas
     * so fechava com {@code rowH >= 18}. Em janela baixa ({@code rowH} 12 ou
     * 13, que e o que a resolucao de referencia do projeto, 480x270 com escala
     * 4, produz) o wrap nao acontecia e o rotulo era cortado em silencio. O
     * passo agora encolhe junto com a linha, ate {@link #MIN_LABEL_ADVANCE}, que
     * e o limite em que as duas linhas ainda nao se sobrepoem. O passo menor que
     * isso nao compra wrap: compra sobreposicao.
     */
    protected int labelBlockAdvance() {
        return Math.max(MIN_LABEL_ADVANCE,
                Math.min(LABEL_LINE_ADVANCE, rowH - LABEL_GLYPH_H - 1));
    }

    /**
     * Corta o texto na largura reservada e <b>marca o corte</b>.
     *
     * <p><b>27/09/2026:</b> o corte puro de {@code plainSubstrByWidth} e
     * silencioso, e um rotulo cortado e indistinguivel de um rotulo completo --
     * no editor de molde isso e o pior dos dois, porque o Mestre acha que o nome
     * da pericia e mesmo. A marca e o que mostra a perda, entao ela e paga
     * <b>antes</b> de cortar o texto: {@code plainSubstrByWidth} garante que o
     * que sobrou cabe em {@code maxW - markW}, e o resultado inteiro cabe em
     * {@code maxW}. Nao havendo corte, o texto volta intacto (sem marca).
     *
     * @param text texto a truncar, se preciso
     * @param maxW largura maxima
     */
    protected String truncateWithEllipsis(String text, int maxW) {
        if (this.font.width(text) <= maxW) {
            return text;
        }
        int markW = this.font.width(TRUNCATION_MARK);
        String cut = markW >= maxW ? "" : this.font.plainSubstrByWidth(text, maxW - markW);
        return cut + TRUNCATION_MARK;
    }

    /**
     * Cria uma caixa de edicao ligada ao servidor.
     *
     * <p>O contraste e o que faltava para o usuario perceber que o campo e
     * editavel: texto branco quando editavel, cinza apagado quando nao, e a
     * borda de foco nativa do vanilla quando o campo ganha foco.
     *
     * <p>27/09/2026 (bug do usuario): "o display da barra de HP/Mana so
     * suporta 4 caracteres, entao limite a insercao do valor maximo na caixa de
     * ate 9999 somente". O filtro era generico ({@code -?\d{0,9}}) e nao olhava
     * o teto do campo, entao dava para digitar {@code 99999} no teto de HP --
     * e o servidor recortava em silencio para 9999. Agora o teto vem por campo
     * em {@code ceiling} ({@link SheetData#MAX_RESOURCE} no teto de HP e de
     * Mana) e {@link #NO_CEILING} mantem o limite antigo de quem nao mudou
     * ({@code level} e {@code xp}). O servidor continua sendo a autoridade: isto
     * so evita a digitacao que seria descartada.
     */
    protected EditBox createFieldBox(String field, int x, int y, int w, boolean numeric, int ceiling) {
        EditBox box = new EditBox(this.font, x, y, w, rowH - 2,
                Component.literal(SheetData.labelOf(field)));
        if (numeric) {
            // Numeros com sinal: o "-" precisa passar, senao nao existe como
            // digitar valor negativo (os atributos vao para as setas, mas
            // nivel/xp e qualquer campo futuro continuam por aqui).
            // O limite de 9 digitos mantem o numero longe do overflow; o teto do
            // campo, quando existe, e mais apertado e decide antes disso (o sinal
            // nao conta para o teto: "-999" e o valor 999).
            box.setFilter(s -> s.matches("-?\\d{0,9}")
                    && (ceiling == NO_CEILING || withinCeiling(s, ceiling)));
            box.setMaxLength(10);
        } else {
            box.setMaxLength(SheetData.MAX_NAME);
        }
        box.setTextColor(COL_BOX_TEXT);
        box.setTextColorUneditable(COL_BOX_TEXT_OFF);
        box.setHint(Component.literal("click to edit"));
        applyEditable(box);
        box.setResponder(value -> {
            if (suppressNotify || !canEdit) {
                return;
            }
            // <b>Nao envia texto numerico incompleto.</b> Cada tecla dispara o
            // responder; ao apagar tudo, o servidor cairia no fallback (mantem
            // o valor antigo), reenviaria a ficha e o eco apagaria de novo o
            // campo. Sem envio nao ha eco, e a digitacao fica possivel.
            if (numeric && !isCompleteNumber(value)) {
                return;
            }
            ClientPlayNetworking.send(new RpgNetworking.SheetFieldPayload(
                    targetName, field, value));
        });
        // **OBRIGATORIO**: sem este registro a caixa nao entra em
        // Screen.children() e portanto nao recebe clique, nem foco, nem
        // teclado -- e nem e desenhada. (Causa raiz do "nao consigo editar
        // nenhum campo": a FASE 3 criava a caixa e so a guardava no mapa.)
        addRenderableWidget(box);
        return box;
    }

    /**
     * O numero cabe no teto do campo?
     *
     * <p>27/09/2026: o filtro so contava caracteres, e o usuario pediu o teto
     * de <b>valor</b> (9999 no HP/Mana). A regra nao pode ser "no maximo 4
     * caracteres", porque o sinal nao conta para o valor: {@code -999} sao 4
     * caracteres com sinal e o numero 999, que cabe no 9999. Entao quem decide e
     * o numero: mais digitos que o teto tem, recusa sem comparar; no mesmo
     * numero de digitos, compara o valor -- com teto 99, {@code 99} passa e
     * {@code 999} nao; com teto 9999, os 4 digitos sempre passam e o 5o ja e
     * recusado. So o sinal (e o campo vazio, enquanto ainda esta digitando)
     * passam sempre. O {@code parseLong} nunca ve mais de 9 digitos, porque o
     * filtro acima ja limitou.
     */
    private static boolean withinCeiling(String value, int ceiling) {
        String digits = value.startsWith("-") ? value.substring(1) : value;
        if (digits.isEmpty()) {
            return true;
        }
        if (digits.length() > Integer.toString(ceiling).length()) {
            return false;
        }
        return Long.parseLong(digits) <= ceiling;
    }

    /**
     * O texto e um numero completo (nao vazio, nao so o sinal)?
     *
     * <p>Usado para nao enviar valores numericos pela metade. Texto livre
     * (nome, raca, classe) nao passa por aqui: envio a cada tecla e o
     * comportamento esperado.
     */
    private static boolean isCompleteNumber(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        String digits = value.startsWith("-") ? value.substring(1) : value;
        return !digits.isEmpty() && digits.chars().allMatch(Character::isDigit);
    }

    // ------------------------------------------------------------------
    // DESENHO DAS BARRAS (usado pelo Status)
    // ------------------------------------------------------------------

    /** Desenha a moldura de uma barra. */
    protected void drawBar(GuiGraphics graphics, Bar bar, int fillColor, int bgColor, double fraction) {
        graphics.fill(bar.x(), bar.y(), bar.x() + bar.w(), bar.y() + bar.h(), bgColor);
        int fillW = (int) Math.round(bar.w() * Math.max(0.0, Math.min(1.0, fraction)));
        if (fillW > 0) {
            graphics.fill(bar.x(), bar.y(), bar.x() + fillW, bar.y() + bar.h(), fillColor);
        }
        graphics.fill(bar.x(), bar.y(), bar.x() + bar.w(), bar.y() + 1, COL_BAR_EDGE);
        graphics.fill(bar.x(), bar.y() + bar.h() - 1, bar.x() + bar.w(), bar.y() + bar.h(), COL_BAR_EDGE);
    }

    /**
     * Barra em duas partes: a normal (ate o maximo) e o excedente, pintado
     * com outra cor logo em seguida.
     *
     * <p>As duas fracoes usam o MESMO denominador (o chamador escolhe), e somam
     * no maximo 1.0. Com {@code normalFraction=1} e
     * {@code overflowFraction=0} o resultado e identico a {@link #drawBar}.
     */
    protected void drawBarSplit(GuiGraphics graphics, Bar bar,
                                int fillColor, int overflowColor, int bgColor,
                                double normalFraction, double overflowFraction) {
        graphics.fill(bar.x(), bar.y(), bar.x() + bar.w(), bar.y() + bar.h(), bgColor);
        double normal = Math.max(0.0, Math.min(1.0, normalFraction));
        int normalW = (int) Math.round(bar.w() * normal);
        if (normalW > 0) {
            graphics.fill(bar.x(), bar.y(), bar.x() + normalW, bar.y() + bar.h(), fillColor);
        }
        // Clamp em 1.0 - normal: a soma nunca passa da largura da barra. O
        // segundo Math.min existe porque dois arredondamentos independentes
        // podem somar 1 pixel a mais (ex.: normal=0.833 e overflow=0.167 em
        // uma barra de 149px) e o fill passaria da moldura.
        double overflow = Math.max(0.0, Math.min(1.0 - normal, overflowFraction));
        int overflowW = Math.min(bar.w() - normalW, (int) Math.round(bar.w() * overflow));
        if (overflowW > 0) {
            graphics.fill(bar.x() + normalW, bar.y(),
                    bar.x() + normalW + overflowW, bar.y() + bar.h(), overflowColor);
        }
        graphics.fill(bar.x(), bar.y(), bar.x() + bar.w(), bar.y() + 1, COL_BAR_EDGE);
        graphics.fill(bar.x(), bar.y() + bar.h() - 1, bar.x() + bar.w(), bar.y() + bar.h(), COL_BAR_EDGE);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        // O titulo vem ANTES de super.render() (27/09/2026): desenhado depois, ele
        // ficava POR CIMA dos widgets e do texto, a mesma armadilha do numero da
        // barra em StatusScreen.drawValue. O fundo da tela e do painel ja foi
        // desenhado por quem chama render -- em 1.21.11 e renderWithTooltipAnd-
        // Subtitles, que chama renderBackground antes de render, e Screen.render
        // so itera os elementos -- e o painel comeca em y = 20, entao o titulo em
        // y = 8 nao e coberto por nenhum dos dois.
        String title = titleText();
        graphics.drawString(this.font, title,
                (this.width - this.font.width(title)) / 2, 8, COL_TITLE, false);

        // super.render() desenha os widgets.
        super.render(graphics, mouseX, mouseY, delta);

        // Estado de edicao explicito: responde a duvida "da para editar?".
        String state = canEdit ? "Editable" : "Read-only";
        graphics.drawString(this.font, state,
                panelX + panelW - this.font.width(state) - 4, 24,
                canEdit ? COL_EDITABLE : COL_READONLY, false);

        // Faixa superior esquerda: avisos das subclasses. Fica ACIMA de
        // contentTop de proposito -- antes o aviso de "DOWNED" e o contador de
        // skills eram desenhados perto da base e colidiam com a ultima linha de
        // widgets em janelas baixas.
        renderTopLeft(graphics);

        if (sheet == null) {
            graphics.drawString(this.font, "Loading...",
                    (this.width - this.font.width("Loading...")) / 2, 48, COL_MUTED, false);
            return;
        }

        for (TextLine line : textLines) {
            graphics.drawString(this.font, line.text(), line.x(), line.y(), line.color(), false);
        }

        renderContent(graphics, mouseX, mouseY);
    }

    /** Titulo da tela (quem e o dono da ficha). */
    protected String titleText() {
        return "Sheet: " + (targetName.isEmpty() ? "me" : targetName);
    }

    /**
     * Desenha o conteudo que nao e widget (barras, avisos, tooltips).
     *
     * <p>Recebe as coordenadas do mouse porque o hover e parte do desenho: a
     * tela de Skills abre o tooltip da descricao da skill quando o cursor esta
     * sobre o botao, e isso so pode ser decidido no render.
     */
    protected void renderContent(GuiGraphics graphics, int mouseX, int mouseY) {
    }

    /**
     * Desenha avisos no canto superior esquerdo do painel (y = 24), na mesma
     * faixa do rotulo de edicao. Vazio por padrao.
     */
    protected void renderTopLeft(GuiGraphics graphics) {
    }

    // ------------------------------------------------------------------
    // FECHAMENTO
    // ------------------------------------------------------------------

    protected void close() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(returnTo);
        }
    }

    /** Tela para onde o botao Back volta. */
    protected Screen returnScreen() {
        return returnTo;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Fundo escuro simples (feedback do usuario: nada de pergaminho).
        graphics.fill(0, 0, this.width, this.height, COL_SCREEN_BG);
        // Painel da ficha, com margem para o titulo e o botao Back.
        graphics.fill(panelX - PAD, 20, panelX + panelW + PAD, this.height - 28, COL_PANEL_BG);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
