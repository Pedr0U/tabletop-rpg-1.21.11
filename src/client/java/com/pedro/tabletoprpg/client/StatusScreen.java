package com.pedro.tabletoprpg.client;

import com.pedro.tabletoprpg.RpgNetworking;
import com.pedro.tabletoprpg.SheetData;
import com.pedro.tabletoprpg.SheetModel;
import com.pedro.tabletoprpg.SheetModelHolder;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tela "Status" da ficha: identidade, recursos (barras), progresso e atributos.
 *
 * <p><b>Vida e Mana sao barras</b> (feedback do usuario): seta esquerda
 * diminui, seta direita aumenta, {@link CharacterSheetScreen#ARROW_STEP} por
 * clique. O valor so substitui o do servidor quando o servidor devolve a ficha.
 *
 * <p><b>HP pode ser negativo</b> (decisao do usuario): a barra esvazia em
 * {@code hp <= 0} e a tela avisa "DOWNED". O jogo aplica o estado deitado no
 * servidor (ver {@code DamageControlHandler}).
 *
 * <p><b>HP e Mana podem passar do maximo</b> (decisao do usuario): o excedente
 * aparece na barra numa cor mais clara, entao {@code 12/10} se le como "10
 * permanentes + 2 temporarios" e nao como "barra estourada". Ver
 * {@code drawResourceBar}.
 *
 * <p>O campo <b>Max</b> continua como caixa de texto editavel ao lado de cada
 * barra: sem ele nao haveria como mudar o teto do recurso.
 *
 * <p><b>Atributos sao botoes, nao caixas de texto</b> (decisao do usuario): o
 * atributo virou modificador somado as pericias, entao o mestre so precisa
 * subir e descer -- e um numero solto nao limitava mais. Nao ha barra nem
 * caixa: apenas {@code rotulo  [-]  numero  [+]}. 27/09/2026: os glifos
 * {@code <} e {@code >} viraram {@code -} e {@code +}, para o par de botoes
 * da tela inteira falar a mesma coisa. O atributo tem teto
 * {@link SheetData.Attributes#VALUE_MAX} (30) e piso
 * {@link SheetData.Attributes#VALUE_MIN} (-30) -- decisao do usuario em
 * 27/09/2026 -- entao <b>os dois</b> botoes desligam no limite, e o cinza e o
 * feedback de "voce chegou aqui" dos dois lados.
 */
public class StatusScreen extends CharacterSheetScreen {

    // ------------------------------------------------------------------
    // COLUNA DE PERICIAS (decisao do usuario em 25/09/2026)
    // ------------------------------------------------------------------

    /**
     * Quantas pericias a coluna mostra por padrao.
     *
     * <p><b>27/09/2026 (Sheet Editor):</b> era a constante {@code 20}, duplicada
     * a mao, e depois {@code SheetData.PERICIAS_PADRAO.size()}, que deixou de
     * existir. O total agora vem do <b>modelo</b>, em tempo de execucao, e pode
     * ser qualquer valor de 1 a {@code MAX_PERICIAS} porque e o Mestre quem
     * decide. Por isso a coluna tem scroll proprio: antes, com 18 fixas, a
     * altura cabia e nao havia o que rolar.
     */
    private static final int PER_COUNT_DEFAULT = 18;
    /** Painel mais largo que o das outras telas, para caber a coluna. */
    private static final int MAX_PANEL_W_STATUS = 600;
    /**
    /**
     * Teto da largura dos campos de texto (nome, raca, classe, nivel, xp) E das
     * barras de HP e Mana: as duas coisas consomem o mesmo {@code boxW}.
     *
     * <p>Historico do valor: sem teto, a caixa encostava na borda e os campos
     * ficavam grandes demais (190). Depois 190 ficou estreito demais para o
     * nome dos valores e para as barras, e o usuario pediu "um pouco" mais em
     * 26/09/2026, sem voltar ao encostar na borda.
     *
     * <p>AJUSTE FINO: este e o unico numero que controla essa largura.
     * O teto real e {@code fieldArea} (cerca de 306 com o painel de 600),
     * porque {@code boxW} faz {@code Math.min} deste valor com a area
     * disponivel na linha. Acima de ~306 este numero deixa de ter efeito e as
     * caixas voltam a encostar na borda, que era o que se queria evitar.
     */
    private static final int FIELD_W_MAX = 240;
    /** Folga entre as pecas de um atributo, a mesma usada nas pericias. */
    private static final int WIDGET_GAP = 2;
    /** Respiro interno do painel, para o texto nao encostar na moldura. */
    private static final int PANEL_PAD = 8;
    /** Largura da coluna como fracao do painel (o resto fica com a ficha). */
    private static final float PER_W_RATIO = 0.42f;
    /** Teto da largura da coluna, para ela nao virar a tela inteira. */
    private static final int PER_W_MAX = 200;
    /** Separacao entre a ficha (esquerda) e as pericias (direita). */
    private static final int PER_GAP = 10;
    /** Altura minima de uma linha de pericia: abaixo disso o botao nao e' clicavel. */
    private static final int MIN_PER_ROW_H = 9;

    /** Altura de linha da coluna de pericias, calculada em buildPanel(). */
    private int perRowH;
    /** Geometria das 20 linhas (o widget e' a moldura; nome/valor sao desenhados). */
    private final List<PericiaRow> periciaRows = new ArrayList<>();

    /** Valor otimista por pericia, para cliques rapidos nao se perderem. */
    private final Map<String, Integer> pendingPericiaValue = new HashMap<>();
    /** Atributo otimista por pericia, mesmo proposito de {@link #pendingPericiaValue}. */
    private final Map<String, String> pendingPericiaAttribute = new HashMap<>();

    /**
     * Primeira pericia visivel na coluna (scroll da coluna de pericias).
     *
     * <p>27/09/2026 (Sheet Editor): antes a lista tinha 18 itens fixos que
     * cabiam na tela, entao nao havia o que rolar. O Mestre pode por ate 30
     * pericias, e a coluna rola sozinha com a roda do mouse -- <b>somente</b>
     * quando o ponteiro esta sobre ela, para a rolagem nao conflicted com a da
     * tela inteira.
     */
    private int perScroll;

    /** Retangulo da coluna de pericias, guardado para o teste de "o mouse esta aqui". */
    private int perPanelX;
    private int perPanelW;

    /**
     * Geometria de uma linha de pericia: nome (x, w), valor e o botao de atributo.
     *
     * <p>Os dois botoes de passo ({@code -} e {@code +}) ficam guardados porque
     * {@link #renderContent} desliga o {@code +} quando o valor chega no teto:
     * um botao desativado ({@code active = false}) e desenhado cinza, que e o
     * feedback de "voce ja chegou no maximo" pedido pelo usuario.
     */
    private record PericiaRow(int nameX, int y, int nameW, int valueX, int valueW, int h,
                             Button attrButton, Button minusButton, Button plusButton) {
    }

    /** Geometria das barras, calculada em buildPanel(). */
    private Bar hpBar;
    private Bar manaBar;

    /**
     * Setas <b>sem limite de valor</b>: as de HP/Mana e o botao de atributo da
     * pericia. Ficam cinzas quando a ficha e somente leitura.
     *
     * <p>27/09/2026: as setas de atributo e de pericia <b>nao</b> entram aqui,
     * porque dependem tambem do valor (piso/teto). Elas sao escritas por
     * {@link #applyStepButtons}, o mesmo caminho do render, para
     * {@code applyExtraState} nao religar um botao travado no limite.
     */
    private final List<Button> arrowButtons = new ArrayList<>();

    /** Onde o numero de cada atributo e desenhado (o widget e' o valor). */
    private final List<AttrRow> attrRows = new ArrayList<>();

    /**
     * Area onde o numero do atributo e centralizado.
     *
     * <p>Como {@link PericiaRow}, guarda os dois botoes de passo para o "+"
     * poder escurecer no teto. O {@code attributeId} e o id do modelo (27/09/2026
     * substituiu o enum {@code SheetData.Attribute}, que nao existe mais).
     */
    private record AttrRow(int x, int y, int w, int h, String attributeId,
                           Button minusButton, Button plusButton) {
    }

    public StatusScreen(String targetName, Screen returnTo) {
        super("Character Status", targetName, returnTo);
    }

    /**
     * Painel mais largo que o das outras telas da ficha, para caber a coluna de
     * perícias ao lado (decisao do usuario em 25/09/2026: lista única de 20
     * linhas, todas visíveis sem scroll).
     *
     * <p>{@code this.width - 2 * PAD} continua valendo dentro de
     * {@code init()}, entao em janela estreita o painel encolhe sozinho e a
     * coluna de perícias apenas fica mais apertada.
     */
    @Override
    protected int maxPanelWidth() {
        return MAX_PANEL_W_STATUS;
    }

    @Override
    protected void buildPanel(int x0, int panelW, int topY, int bottomY) {
        arrowButtons.clear();
        attrRows.clear();
        periciaRows.clear();
        // Zera as barras antes de recriar. Sem isso, um modelo novo que DESLIGUE
        // "mana" deixaria a Bar antiga apontando para a linha que passou a ser
        // outra, e o desenho (que so ignora null) pintaria a barra por cima do
        // campo vizinho.
        hpBar = null;
        manaBar = null;

        // 27/09/2026 (Sheet Editor): o modelo vem do holder e e lido UMA vez
        // aqui. Antes os rotulos, a quantidade de atributos e o que aparecia
        // eram constantes no codigo; agora sao do modelo salvo pelo Mestre, e
        // reler o holder a cada linha arriscaria ler dois modelos diferentes se
        // um pacote chegasse no meio da montagem.
        SheetModel model = model();

        // Respiro interno (feedback do usuario: "muito colado com os textos de
        // dentro do menu"): o conteudo comeca PANEL_PAD para dentro da moldura,
        // em vez de encostar na borda. A moldura e o botao de fechar continuam
        // desenhados pelo CharacterSheetScreen, entao so o conteudo muda.
        int innerX = x0 + PANEL_PAD;
        int innerW = Math.max(120, panelW - 2 * PANEL_PAD);
        int innerTop = topY + PANEL_PAD;
        int innerBottom = bottomY - PANEL_PAD;
        x0 = innerX;
        panelW = innerW;
        topY = innerTop;
        bottomY = innerBottom;

        // Coluna de pericias a direita; o resto da ficha fica a esquerda. A
        // largura e uma fracao do painel para a proporcionalidade se manter em
        // qualquer tamanho de janela.
        // Respiro interno (feedback do usuario: "muito colado com os textos de
        // dentro do menu"). O painel cresce em PANEL_PAD de cada lado, e o
        // conteudo comeca PANEL_PAD para dentro da moldura.
        int perW = Math.max(140, Math.min(PER_W_MAX, (int) (panelW * PER_W_RATIO)));
        int leftW = Math.max(120, panelW - perW - PER_GAP);
        int perX = x0 + leftW + PER_GAP;
        perPanelX = perX;
        perPanelW = perW;

        // Altura de linha da coluna de pericias: e' ela que decide quantas
        // cabem sem scroll. Limitada para nunca passar da altura de uma linha
        // normal (senao a coluna ficaria igual ao resto e nao caberia).
        //
        // 27/09/2026 (Sheet Editor): o total vem do modelo, em tempo de
        // execucao, e pode chegar a 30. A coluna tem scroll proprio, entao o que
        // importa aqui e o espaco disponivel -- nao o total.
        int perCount = periciaCount();
        int perTitleH = rowHOrDefault(topY, bottomY);
        int perAvail = Math.max(MIN_PER_ROW_H, bottomY - topY - perTitleH);
        perRowH = Math.max(MIN_PER_ROW_H,
                Math.min(perRowHCap(topY, bottomY), perAvail / Math.max(1, perCount)));

        // Duas colunas de atributos so quando o painel e largo o bastante para
        // os rotulos (FOR/DES/...) nao se chocarem com os dois botoes.
        boolean twoColumns = leftW >= 260;
        // 27/09/2026: o numero de linhas de atributo vem do modelo (1 a 10), e
        // nao mais das constantes 3/6. A altura reservada precisa acompanhar,
        // senao os atributos de baixo caem em cima da secao seguinte.
        int attrRowCount = twoColumns ? attrRowCount(2) : attrRowCount(1);
        // 4 titulos de secao (Identity, Vitals, Progress, Attributes) + 4 campos
        // de identidade (characterName, race, characterClass, background) + 2
        // vitais + 2 de progresso + as linhas de atributo. Sem folga no fim.
        //
        // 27/09/2026, a folga era 4. Como o espaco disponivel e dividido pelo
        // total de linhas, a folga nao "reserva" nada: ela so encolhe a altura de
        // linha. O que ela faz de verdade e aumentar o TOTAL, e o total so vira
        // problema quando a altura de linha esta no piso de MIN_ROW_H (12) —
        // caso das janelas baixas. Com a folga, 18 linhas x 12 estouravam 32px e
        // as duas ultimas caíam em cima do botao Back, que e desenhado depois e
        // por cima. Sem folga cabem 16 linhas e o estouro cai para 8px, dentro do
        // painel. Em janela normal a altura de linha bate em MAX_ROW_H nos dois
        // casos, entao nada muda visualmente la.
        int neededRows = 4 + 4 + 2 + 2 + attrRowCount;
        // 27/09/2026 (Sheet Editor): o Mestre pode desligar a raca, a mana e o
        // XP, e ai as linhas abaixo somem. Sem descontar, a altura de linha
        // seria calculada para linhas que nao existem e sobraria espaco vazio
        // no fim do painel.
        int optionalRows = (model.isEnabled("race") ? 0 : 1)
                + (model.isEnabled("mana") ? 0 : 1)
                + (model.xp() == SheetModel.XpMode.HIDDEN ? 1 : 0);
        neededRows -= optionalRows;
        rowH = fitRowHeight(neededRows, topY, bottomY);

        int labelW = fieldLabelWidth(leftW);
        // Campo de tamanho MEDIO e centralizado (feedback do usuario: as caixas
        // de texto estavam grandes demais, ocupando todo o resto da linha).
        // Em vez de esticar ate a borda, a caixa tem teto e o sobra da linha
        // e dividido em duas metades, entando as duas colunas ficam simetricas.
        int fieldArea = Math.max(60, leftW - labelW - 6);
        int boxW = Math.max(60, Math.min(FIELD_W_MAX, fieldArea));
        int boxX = x0 + labelW + (fieldArea - boxW) / 2;

        int y = topY;

        // ---------------- Identity ----------------
        // 27/09/2026 (Sheet Editor): os titulos vem do modelo e a raca pode ter
        // sido desligada pelo Mestre. Titulo de secao e campo andam juntos, para
        // nao sobrar um rotulo sem a caixa embaixo.
        y = addSection(model.nameLabel(), x0, y, leftW);
        y = addField("characterName", x0, y, boxX, boxW, labelW, false);
        if (model.isEnabled("race")) {
            y = addField("race", x0, y, boxX, boxW, labelW, false);
        }
        y = addField("characterClass", x0, y, boxX, boxW, labelW, false);
        y = addField("background", x0, y, boxX, boxW, labelW, false);

        // ---------------- Vitals (barras) ----------------
        y = addSection("Vitals", x0, y, leftW);
        hpBar = addResourceRow(x0, boxX, y, boxW, model.hpLabel(), "hp", "hpMax", COL_HP, COL_HP_BG);
        y += rowH;
        if (model.isEnabled("mana")) {
            manaBar = addResourceRow(x0, boxX, y, boxW, model.manaLabel(), "mana", "manaMax", COL_MANA, COL_MANA_BG);
            y += rowH;
        }

        // ---------------- Progress ----------------
        y = addSection("Progress", x0, y, leftW);
        y = addField("level", x0, y, boxX, boxW, labelW, true);
        // XP em modo TEXT vira caixa de texto (o campo "xpText"); em NUMBER
        // continua numerico; em HIDDEN a linha inteira nao existe.
        if (model.xp() == SheetModel.XpMode.TEXT) {
            y = addField("xptext", x0, y, boxX, boxW, labelW, false);
        } else if (model.xp() != SheetModel.XpMode.HIDDEN) {
            y = addField("xp", x0, y, boxX, boxW, labelW, true);
        }

        // ---------------- Attributes ----------------
        // Decisao do usuario: atributos sao botoes -/+ (passo 1), sem caixa de
        // texto e sem barra, começando em 0, com teto 30 e PISO -30 (podem ser
        // negativos, decisao do usuario em 27/09/2026).
        y = addSection("Attributes", x0, y, leftW);
        List<SheetModel.AttributeDef> attrs = model.attributes();
        if (twoColumns) {
            int colW = (leftW - 6) / 2;
            for (int i = 0; i < attrs.size(); i++) {
                int cx = x0 + (i % 2) * colW;
                int cy = y + (i / 2) * rowH;
                addAttributeRow(cx, cy, colW - 4, attrs.get(i).id());
            }
            y += attrRowCount(2) * rowH;
        } else {
            for (SheetModel.AttributeDef attr : attrs) {
                addAttributeRow(x0, y, leftW - 6, attr.id());
                y += rowH;
            }
        }

        // ---------------- Pericias (coluna da direita) ----------------
        // 27/09/2026 (Sheet Editor): a lista vem do modelo e tem scroll PROPRIO
        // desta coluna, sem mexer no resto da ficha. Antes eram 18 fixas que
        // cabiam; agora o Mestre pode por 30, e o que nao couber e alcancavel
        // pela roda do mouse sobre a coluna. Nao ha botao de adicionar nem de
        // remover aqui: quem cria e remove pericia e o editor do Mestre.
        textLines.add(new TextLine("Skill Checks", perX, topY + perLabelOffset(), COL_SECTION));
        perScroll = clampPerScroll(perScroll);
        int visible = perVisibleCount();
        for (int i = 0; i < visible; i++) {
            addPericiaRow(perX, topY + perTitleH + i * perRowH, perW, perScroll + i);
        }
        addBonusHeader(topY, perTitleH);
    }

    /**
     * Cabecalho "Bonus" em cima da coluna de valores das pericias.
     *
     * <p>27/09/2026, pedido do usuario: o numero ao lado do nome da pericia
     * e o <b>bonus</b>, e nao o valor final da rolagem (que ainda soma o
     * atributo). Sem rotulo, a coluna parecia ser "o quanto essa pericia rende",
     * e nao "quanto o jogador investiu nela".
     *
     * <p>Usa a MESMA geometria da primeira linha ({@link #periciaRows}), em vez
     * de recalcular: se a largura da coluna mudar, o texto acompanha sozinho.
     * O texto e mais largo que a caixa de 2 digitos e nao esta centralizado nela,
     * e sim sobre a faixa, entao ele invade uns pixels para a esquerda. Por isso
     * o {@code -9} e nao {@code -8}: com fonte 1px mais alta (resource pack), o
     * cabecalho encostaria na linha 0 em vez de ficar com 1px de folga.
     */
    private void addBonusHeader(int topY, int perTitleH) {
        if (periciaRows.isEmpty()) {
            return;
        }
        PericiaRow first = periciaRows.get(0);
        String label = "Bonus";
        int w = this.font.width(label);
        int x = first.valueX() + (first.valueW() - w) / 2;
        int y = topY + perTitleH - 9;
        textLines.add(new TextLine(label, x, y, COL_MUTED));
    }

    /**
     * Largura reservada aos rotulos dos campos de texto.
     *
     * <p>27/09/2026: era {@code max(52, leftW/5)}, uma fracao fixa que
     * aceitava "Name", "Race" e "Class", mas nao "Background" -- o rotulo mais
     * longo que ja entrou na ficha. Com a coluna estreita ele invadia a caixa
     * de texto, porque {@code addField} desenha o rotulo sem cortar.
     *
     * <p>Agora a largura vem do rotulo <b>realmente mais largo</b> entre os
     * campos que a tela usa, e nao de uma fracao chutada. O teto de um terco da
     * coluna existe para a caixa nao sumir quando o rotulo for enorme demais:
     * nesse caso o rotulo quebra em 2 linhas ({@code addWrappedLabel}), e e
     * <b>melhor</b> do que um rotulo invadindo a caixa ou um campo sem espaco
     * para digitar.
     *
     * <p>27/09/2026 (bug do usuario): com "Pontos de Determinacao (PD)" no
     * rotulo de Mana, o rotulo media 140px e a coluna reservava 124px, e o texto
     * atravessava o botao "-" e a caixa de valor.
     *
     * <p>27/09/2026: a lista veio para {@link SheetData#LABELLED_FIELDS}, que
     * e a lista completa do que tem rotulo desenhado na ficha -- antes o laco
     * usava {@code TEXT_FIELDS}, que nao tem "level" nem "xp", e o rotulo mais
     * largo vinha de uma lista fixa de literais ("Level", "XP"). Como "Level" e
     * "XP" ja vem do modelo por {@code labelOf}, so sobraram os literais que
     * nao sao campo: "Max" (o das caixas de teto).
     */
    private int fieldLabelWidth(int leftW) {
        int widest = 0;
        for (String field : SheetData.LABELLED_FIELDS) {
            widest = Math.max(widest, this.font.width(SheetData.labelOf(field)));
        }
        // "Max" e o rotulo das caixas de teto, e "HP"/"Mana" os das barras.
        for (String label : List.of("Max", "HP", "Mana")) {
            widest = Math.max(widest, this.font.width(label));
        }
        return Math.max(52, Math.min(widest + 6, leftW / 3));
    }

    /** Altura de linha provisoria, so para estimar o titulo antes de {@code rowH}. */
    private int rowHOrDefault(int topY, int bottomY) {
        int available = Math.max(MIN_ROW_H, bottomY - topY);
        return Math.max(MIN_ROW_H, Math.min(MAX_ROW_H, available / 12));
    }

    /** Teto da altura de linha das pericias: nunca maior que uma linha normal. */
    private int perRowHCap(int topY, int bottomY) {
        // 27/09/2026: usa o total que CABE na coluna, nao o total do modelo.
        // Com 30 pericias e um total grande, esta divisao daria uma altura de
        // linha minuscula; a coluna rola, entao o que decide a altura e o
        // espaco disponivel, nao quantas linhas existem.
        int visible = Math.max(1, perVisibleCountFor(topY, bottomY));
        return Math.min(MAX_ROW_H, (bottomY - topY) / (visible + 1));
    }

    // ------------------------------------------------------------------
    // MODELO GLOBAL (27/09/2026 - Sheet Editor)
    // ------------------------------------------------------------------

    /**
     * O modelo em uso, lido do holder.
     *
     * <p>Antes desta mudanca nao havia holder nenhum: rotulo, quantidade e o
     * ligar/desligar de um campo eram constantes no codigo. A tela agora le do
     * modelo, que o servidor manda no login e o cliente guarda.
     */
    private SheetModel model() {
        return SheetModelHolder.current();
    }

    /** Quantas linhas de atributo o modelo ocupa em {@code columns} colunas. */
    private int attrRowCount(int columns) {
        int n = Math.max(0, model().attributeCount());
        return columns <= 1 ? n : (n + columns - 1) / columns;
    }

    /** Quantas pericias existem agora: as da ficha, ou o total do modelo. */
    private int periciaCount() {
        if (sheet != null) {
            return sheet.pericias().size();
        }
        return model().periciaCount();
    }

    /**
     * Quantas linhas de pericia cabem na coluna com a altura de linha atual.
     *
     * <p>E o que define o tamanho da janela de scroll. Se sobrar espaco, cabem
     * todas e o scroll simplesmente nao aparece.
     */
    private int perVisibleCount() {
        int total = periciaCount();
        if (total <= 0) {
            return 0;
        }
        int perTitleH = rowHOrDefault(contentTop, contentBottom);
        int avail = Math.max(MIN_PER_ROW_H, contentBottom - contentTop - perTitleH);
        return Math.max(1, Math.min(total, avail / Math.max(1, perRowH)));
    }

    /** Mesma conta de {@link #perVisibleCount}, sem usar {@link #perRowH}. */
    private int perVisibleCountFor(int topY, int bottomY) {
        int total = periciaCount();
        if (total <= 0) {
            return 1;
        }
        int perTitleH = rowHOrDefault(topY, bottomY);
        int avail = Math.max(MIN_PER_ROW_H, bottomY - topY - perTitleH);
        int rowH = Math.max(MIN_PER_ROW_H,
                Math.min(MAX_ROW_H, (bottomY - topY) / (total + 1)));
        return Math.max(1, Math.min(total, avail / Math.max(1, rowH)));
    }

    /** Maior scroll valido: o que sobra quando a ultima pericia chega no fim. */
    private int maxPerScroll() {
        return Math.max(0, periciaCount() - perVisibleCount());
    }

    private int clampPerScroll(int value) {
        return Math.max(0, Math.min(value, maxPerScroll()));
    }

    /** O mouse esta sobre a coluna de pericias? So ai a roda rola esta coluna. */
    private boolean isOverPericiaColumn(double mouseX, double mouseY) {
        return mouseX >= perPanelX && mouseX < perPanelX + perPanelW
                && mouseY >= contentTop && mouseY < contentBottom;
    }

    /** Centraliza o texto na linha compacta (fonte e 8px, linha pode ter 9). */
    private int perLabelOffset() {
        return Math.max(1, (perRowH - 8) / 2);
    }

    /**
     * Largura da caixa do NUMERO, calculada a partir dos limites do valor.
     *
     * <p><b>27/09/2026, bug relatado:</b> a largura era fixa em 10px e o
     * numero era cortado com {@code plainSubstrByWidth}, entao ao passar de um
     * algarismo o segundo era <b>oculto</b> -- "1" + "0" virava "1". Depois a
     * largura passou a ser medida, mas so do <b>teto</b> ({@code "30"}, 12px),
     * o que nao comportava o sinal: o atributo tem piso -30 e a fonte da 6px por
     * caractere, entao {@code "-30"} mede 18px e nao cabia em 16px. O atributo
     * virava "-3" (o segundo algarismo sumia) e a pericia, que nao tem
     * nenhuma guarda de truncamento, invadiria 1px de cada lado.
     *
     * <p>Agora e o <b>maior texto que pode aparecer de verdade</b>: os dois
     * extremos do atributo (o piso negativo e o teto) e o teto da pericia,
     * medidos com {@code font.width} -- e nao um pixels chutado, entao a caixa
     * acompanha a fonte. O piso e o teto vem do modelo
     * ({@link SheetData.Attributes#VALUE_MIN}/{@code VALUE_MAX}); a pericia tem
     * piso 0, entao o texto mais largo dela e o teto, ja coberto aqui.
     *
     * <p>Guarde de truncamento: a caixa cobre o valor <b>legitimo</b>. Um
     * payload forjado pode gravar um numero muito maior, e nesse caso quem corta
     * e o desenho (veja {@code renderContent} e {@code drawPericias}).
     */
    private int valueBoxWidth() {
        int attr = Math.max(this.font.width(Integer.toString(SheetData.Attributes.VALUE_MIN)),
                this.font.width(Integer.toString(SheetData.Attributes.VALUE_MAX)));
        int per = this.font.width(Integer.toString(SheetData.Pericia.VALUE_MAX));
        return Math.max(attr, per) + 4;
    }

    /**
     * Uma linha de pericia: {@code nome  [-]  valor  [+]  [atributo v]}.
     *
     * <p>Compacta de proposito: sao 20 linhas sem scroll, entao a altura
     * ({@link #perRowH}) pode chegar a 9px e os botoes sao menores que os dos
     * atributos. O nome e o valor sao desenhados em {@link #renderContent} (o
     * widget e' so a moldura), o que mantem {@code applySheetToWidgets}
     * simples.
     */
    private void addPericiaRow(int x0, int y, int rowW, int index) {
        int h = Math.max(8, perRowH - 1);
        int attrW = Math.max(22, Math.min(32, rowW / 5));
        int arrow = Math.max(9, Math.min(13, h - 1));
        int valueW = valueBoxWidth();

        int attrX = x0 + rowW - attrW;
        int plusX = attrX - 2 - arrow;
        int valueX = plusX - 2 - valueW;
        int minusX = valueX - 2 - arrow;

        Button minus = Button.builder(Component.literal("-"),
                b -> stepPericiaValue(index, -1)).bounds(minusX, y, arrow, h).build();
        Button plus = Button.builder(Component.literal("+"),
                b -> stepPericiaValue(index, 1)).bounds(plusX, y, arrow, h).build();
        Button attr = Button.builder(Component.literal(""),
                b -> openPericiaAttribute(index)).bounds(attrX, y, attrW, h).build();
        addRenderableWidget(minus);
        addRenderableWidget(plus);
        addRenderableWidget(attr);
        // So o botao de atributo entra na lista: o "-" e o "+" da pericia tem
        // limite de valor e vao por applyStepButtons (ver applyExtraState).
        arrowButtons.add(attr);

        // 4px de folga entre o nome e o botao esquerdo: o usuario apontou que
        // "Constituição" encostava nela.
        periciaRows.add(new PericiaRow(x0, y, Math.max(16, minusX - 4 - x0), valueX, valueW, h,
                attr, minus, plus));
    }

    /** Abre a lista suspensa dos atributos do MODELO para a pericia da linha. */
    private void openPericiaAttribute(int index) {
        if (!canEdit || this.minecraft == null || sheet == null || index >= sheet.pericias().size()) {
            return;
        }
        SheetData.Pericia pericia = sheet.pericias().get(index);
        this.minecraft.setScreen(new AttributePickerScreen(
                this, pericia.name(), pericia.attributeId(), chosen -> {
                    pendingPericiaAttribute.put(pericia.name(), chosen);
                    ClientPlayNetworking.send(RpgNetworking.SheetPericiaPayload.setAttribute(
                            targetName, pericia.name(), chosen));
                }));
    }

    /**
     * Muda o valor (0-3) da pericia e avisa o servidor.
     *
     * <p>Envia so o valor ({@code SET_VALUE}): o pacote nao leva o atributo,
     * entao um clique de seta nunca sobrescreve o atributo escolhido.
     */
    private void stepPericiaValue(int index, int delta) {
        if (!canEdit || sheet == null || index >= sheet.pericias().size()) {
            return;
        }
        SheetData.Pericia pericia = sheet.pericias().get(index);
        int current = displayPericiaValue(pericia);
        int next = Math.max(SheetData.Pericia.VALUE_MIN,
                Math.min(SheetData.Pericia.VALUE_MAX, current + delta));
        if (next == current) {
            return; // ja no limite: nao envia nada
        }
        pendingPericiaValue.put(pericia.name(), next);
        ClientPlayNetworking.send(RpgNetworking.SheetPericiaPayload.setValue(
                targetName, pericia.name(), next));
    }

    /** Valor a exibir: o otimista se houver, senao o do servidor. */
    private int displayPericiaValue(SheetData.Pericia pericia) {
        Integer pending = pendingPericiaValue.get(pericia.name());
        return pending != null ? pending : pericia.value();
    }

    /** Id do atributo a exibir: o otimista se houver, senao o do servidor. */
    private String displayPericiaAttributeId(SheetData.Pericia pericia) {
        String pending = pendingPericiaAttribute.get(pericia.name());
        return pending != null ? pending : pericia.attributeId();
    }

    /**
     * Uma linha de atributo: {@code rotulo  [-]  numero  [+]}.
     *
     * <p>Nao usa {@code createFieldBox} de proposito -- e' a unica secao da
     * ficha sem caixa de texto. O numero vive em {@link AttrRow} e e'
     * desenhado em {@link #renderContent}, o que mantem {@code applySheetToWidgets}
     * simples (ela so precisa atualizar as caixas de vida, mana e texto).
     *
     * <p>O rotulo abrevia (FOR, DES, ...) quando a coluna e estreita para
     * caber os dois botoes, e mostra o nome por extenso quando ha espaco.
     *
     * <p>27/09/2026 (Sheet Editor): recebe o <b>id</b> do atributo, nao um
     * objeto. Os dois textos (sigla e nome por extenso) vem do modelo, e sao
     * diferentes para cada atributo porque o Mestre pode renomear qualquer um.
     */
    private void addAttributeRow(int x0, int y, int rowW, String attributeId) {
        int h = rowH - 2;
        boolean compact = rowW < 84;
        SheetModel.AttributeDef def = model().attribute(attributeId);
        String shortLabel = def == null ? attributeId : def.label();
        String fullLabel = def == null ? attributeId : def.name();
        String label = compact ? shortLabel : fullLabel;
        int labelW = fixedAttributeLabelWidth(rowW, compact);

        // 27/09/2026 (bug do usuario): o rotulo quebrava o botao "-" quando o
        // Mestre dava um nome longo ao atributo, porque o teto de
        // fixedAttributeLabelWidth era menor que o texto. Agora o rotulo quebra
        // em 2 linhas centralizadas na largura da coluna dos botoes.
        addWrappedLabel(label, x0, y, labelW - WIDGET_GAP, COL_LABEL);

        int minusX = x0 + labelW;
        // Mesma geometria da linha de pericia (addPericiaRow): botao dimensionado
        // pela altura da linha, valor com largura calculada e folga de 2px entre
        // as pecas. Pedido do usuario: "um gap igual os da pericia para os
        // atributos".
        // Antes o numero ocupava todo o vao entre os dois botoes
        // (valueW = plusX - 4 - valueX), o que o centralizava longe de ambos.
        int arrow = Math.max(9, Math.min(13, h - 1));
        int valueW = valueBoxWidth();
        int valueX = minusX + arrow + WIDGET_GAP;
        int plusX = valueX + valueW + WIDGET_GAP;

        Button minus = Button.builder(Component.literal("-"),
                b -> stepNumeric(attributeId, -ARROW_STEP)).bounds(minusX, y, arrow, h).build();
        Button plus = Button.builder(Component.literal("+"),
                b -> stepNumeric(attributeId, ARROW_STEP)).bounds(plusX, y, arrow, h).build();
        addRenderableWidget(minus);
        addRenderableWidget(plus);

        attrRows.add(new AttrRow(valueX, y, valueW, h, attributeId, minus, plus));
    }

    /**
     * Largura FIXA da coluna de rótulos dos atributos.
     *
     * <p><b>FACT (bug relatado em 25/09/2026):</b> a largura era calculada por
     * linha, {@code font.width(label) + 6}, e {@code minusX = x0 + labelW}.
     * Como cada atributo tem um texto de tamanho diferente, cada linha colocava
     * as setas num X diferente — a seta esquerda ficava colada em "Força" e a
     * de "Constituição" (texto maior) avançava sobre a linha de cima, e as
     * colunas de setas não alinhavam entre si.
     *
     * <p>A correção é usar sempre a largura do <b>rótulo mais largo</b> de todos
     * os atributos do modelo, para que as setas fiquem na mesma coluna em todas as
     * linhas. O texto continua sendo o nome por extenso quando há espaço e a
     * sigla quando a coluna é estreita (o mesmo critério de antes).
     *
     * <p>27/09/2026 (Sheet Editor): o laço passou de {@code Attribute.VALUES}
     * (seis constantes) para {@link SheetModelHolder#current()}, porque a lista
     * agora e do Mestre e muda de tamanho. Com um rotulo muito largo, o
     * {@code Math.min} abaixo limita pela largura disponivel, e o rotulo quebra
     * em 2 linhas dentro dela (ver {@link #addAttributeRow}) em vez de invadir
     * o botao.
     *
     * <p>27/09/2026: {@code valueBoxWidth} passou a medir o sinal e foi de 16px
     * para 22px, e e subtraido aqui -- esta coluna perde os mesmos 6px. Com
     * rotulo normal nada muda, porque quem manda e o {@code widest + 8}: o
     * limite {@code rowW - ...} so aperta quando o rotulo e largo de verdade ou
     * a coluna e estreita, e o piso de 24px continua valendo. O rotulo so e
     * cortado no ultimo recurso, quando nem em 2 linhas cabe.
     */
    private int fixedAttributeLabelWidth(int rowW, boolean compact) {
        int widest = 0;
        for (SheetModel.AttributeDef def : model().attributes()) {
            widest = Math.max(widest, this.font.width(compact ? def.label() : def.name()));
        }
        int arrow = Math.max(9, Math.min(13, rowH - 3));
        int valueW = valueBoxWidth();
        return Math.max(24, Math.min(rowW - 2 * arrow - valueW - 2 * WIDGET_GAP - 8,
                widest + 8));
    }

    /**
     * Uma linha de recurso: {@code [<] [barra] [>] Max [caixa]}.
     *
     * @param field   campo que as setas alteram ("hp"/"mana")
     * @param maxField campo do teto, que continua editavel por texto
     * @return a geometria da barra (desenhada em render)
     */
    private Bar addResourceRow(int x0, int boxX, int y, int boxW, String label,
                               String field, String maxField, int fillColor, int bgColor) {
        // 27/09/2026: o rotulo vem do modelo (o Mestre escreve), entao quebra em
        // 2 linhas como os outros. A largura e a vao ate o primeiro botao, que e
        // o mesmo inicio da caixa dos campos.
        addWrappedLabel(label, x0, y, boxX - x0 - WIDGET_GAP, COL_LABEL);

        int h = rowH - 2;
        int maxLabelW = 30;
        int maxBoxW = Math.max(40, Math.min(56, boxW / 5));
        int barW = Math.max(24, boxW - 2 * ARROW_SIZE - 3 * 4 - maxLabelW - maxBoxW);

        Button minus = Button.builder(Component.literal("-"), b -> stepNumeric(field, -ARROW_STEP))
                .bounds(boxX, y, ARROW_SIZE, h)
                .build();
        Button plus = Button.builder(Component.literal("+"), b -> stepNumeric(field, ARROW_STEP))
                .bounds(boxX + ARROW_SIZE + 4 + barW + 4, y, ARROW_SIZE, h)
                .build();
        addRenderableWidget(minus);
        addRenderableWidget(plus);
        arrowButtons.add(minus);
        arrowButtons.add(plus);

        int barX = boxX + ARROW_SIZE + 4;
        int maxLabelX = boxX + ARROW_SIZE + 4 + barW + 4 + ARROW_SIZE + 4;
        textLines.add(new TextLine("Max", maxLabelX, y + labelOffset(), COL_MUTED));
        // O teto do recurso e MAX_RESOURCE (9999) no servidor (Vitals); aqui o
        // filtro recusa o digito que passaria dele, para o valor digitado ser o
        // que o servidor vai gravar em vez de um 9999 silencioso.
        fieldBoxes.put(maxField, createFieldBox(maxField, maxLabelX + maxLabelW, y, maxBoxW,
                true, SheetData.MAX_RESOURCE));

        return new Bar(barX, y + 2, barW, rowH - 6);
    }

    /**
     * Atalho "Skills" para quem esta vendo a ficha de OUTRO jogador (o mestre).
     *
     * <p>O jogador chega aqui pelo menu, que ja tem o botao Skills; o mestre so
     * tem "Players", entao sem este atalho ele nao conseguiria editar as
     * habilidades de ninguem — o requisito e ver/editar a ficha inteira.
     */
    @Override
    protected Button buildFooterExtra(int x, int y, int w, int h) {
        if (isOwnSheet()) {
            return null;
        }
        int bw = 64;
        return Button.builder(Component.literal("Skills"), b -> {
            if (this.minecraft != null) {
                this.minecraft.setScreen(new SkillsScreen(targetName, returnScreen()));
                TabletopRpgClient.requestSheet(targetName);
            }
        }).bounds(x + w - bw, y, bw, h).build();
    }

    /**
     * Escreve o {@code active} dos dois botoes de passo de uma linha, ja com o
     * limite do valor: o {@code -} desliga no piso e o {@code +} no teto.
     *
     * <p><b>Por que um metodo so (27/09/2026):</b> o limite era escrito
     * <b>somente</b> no render ({@code renderContent} e {@code drawPericias}), e
     * o {@code applyExtraState} fazia {@code active = canEdit} em TODOS os
     * botoes. Como o desenho do widget acontece em {@code super.render()}, que
     * roda <b>antes</b> do {@code renderContent}, cada eco do servidor religava o
     * botao travado no limite (o {@code -} da pericia em 0, o {@code -} do
     * atributo em -30) e o frame seguinte o escurecia de novo: o botao piscava
     * branco por 1 frame. Passando pelo mesmo metodo nos dois lugares, os dois
     * sempre concordam com o que o proximo render vai desenhar.
     *
     * @param minus botao de menos
     * @param plus  botao de mais
     * @param value valor a exibir (ja otimista)
     * @param min   piso do valor
     * @param max   teto do valor
     */
    private void applyStepButtons(Button minus, Button plus, int value, int min, int max) {
        minus.active = canEdit && value > min;
        plus.active = canEdit && value < max;
    }

    @Override
    protected void applyExtraState() {
        // Setas sem limite de valor (HP, Mana e o botao de atributo): so canEdit.
        for (Button arrow : arrowButtons) {
            arrow.active = canEdit;
        }
        // Atributo e pericia tem limite, entao vao pelo MESMO metodo do render.
        for (AttrRow row : attrRows) {
            applyStepButtons(row.minusButton(), row.plusButton(),
                    numericValue(row.attributeId()),
                    SheetData.Attributes.VALUE_MIN, SheetData.Attributes.VALUE_MAX);
        }
        if (sheet != null) {
            List<SheetData.Pericia> pericias = sheet.pericias();
            int scroll = clampPerScroll(perScroll);
            for (int i = 0; i < periciaRows.size() && scroll + i < pericias.size(); i++) {
                applyStepButtons(periciaRows.get(i).minusButton(), periciaRows.get(i).plusButton(),
                        displayPericiaValue(pericias.get(scroll + i)),
                        SheetData.Pericia.VALUE_MIN, SheetData.Pericia.VALUE_MAX);
            }
        }
    }

    /**
     * O servidor respondeu: o valor autoritativo substitui o otimista das
     * setas e do dropdown de atributo.
     *
     * <p>Fica em {@code onSheetReceived} e nao em {@code applyExtraState}
     * porque abrir o dropdown recria os widgets ({@code init()}) no mesmo
     * instante em que o novo valor foi escolhido — limpando aqui, o usuario
     * veria o valor antigo ate a ficha voltar.
     */
    @Override
    protected void onSheetReceived() {
        pendingPericiaValue.clear();
        pendingPericiaAttribute.clear();
    }

    @Override
    protected void renderContent(GuiGraphics graphics, int mouseX, int mouseY) {
        // HP: negativo esvazia a barra (o servidor aplica o estado deitado).
        // O denominador NAO e forcado para 1: uma ficha pode legitimately ter
        // manaMax = 0 e precisa mostrar "0 / 0", nao "0 / 1".
        int hp = numericValue("hp");
        int hpMax = numericValue("hpMax");
        boolean downed = hp <= 0;
        drawResourceBar(graphics, hpBar, COL_HP, COL_HP_OVER, COL_HP_BG, hp, hpMax, downed);
        drawValue(graphics, hpBar, hp, hpMax, downed);

        // Mana: piso 0, sem regra especial, tambem pode passar do maximo.
        int mana = numericValue("mana");
        int manaMax = numericValue("manaMax");
        drawResourceBar(graphics, manaBar, COL_MANA, COL_MANA_OVER, COL_MANA_BG,
                mana, manaMax, false);
        drawValue(graphics, manaBar, mana, manaMax, false);

        // Atributos: apenas o numero, centralizado entre os dois botoes.
        // 0 em cinza (o padrao) e qualquer outro valor na cor normal, para o
        // mestre bater o olho em "quem foi ajustado" sem ler os 6 numeros.
        for (AttrRow row : attrRows) {
            int value = numericValue(row.attributeId());
            // A caixa foi dimensionada pelos extremos (valueBoxWidth), entao
            // "-30" e "30" cabem inteiros -- inclusive o sinal do piso -30, que
            // antes nao cabia e virava "-3" (o segundo algarismo sumia) porque a
            // largura media so o teto.
            // A guarda so volta a cortar no caso que os limites nao cobrem: um
            // payload forjado pode gravar "-2000000000", que com 62px de
            // largura invadiria o rotulo e os dois botoes. O clique normal nunca
            // chega nesse caso, porque o botao ja desliga no piso.
            String text = Integer.toString(value);
            if (this.font.width(text) > row.w()) {
                text = this.font.plainSubstrByWidth(text, row.w());
            }
            int tx = row.x() + (row.w() - this.font.width(text)) / 2;
            int ty = row.y() + (row.h() - 8) / 2;
            graphics.drawString(this.font, text, tx, ty,
                    value == 0 ? COL_MUTED : COL_BOX_TEXT, false);

            // "+" desliga no teto (30) e "-" no piso (-30): active = false desenha
            // o botao cinza, que e o feedback pedido. O servidor tambem limita
            // (SheetData.Attributes), entao isto e so a cara do limite, nao a sua
            // unica garantia. E o MESMO metodo que applyExtraState usa, para os
            // dois concordarem (ver applyStepButtons).
            applyStepButtons(row.minusButton(), row.plusButton(), value,
                    SheetData.Attributes.VALUE_MIN, SheetData.Attributes.VALUE_MAX);
        }

        drawPericias(graphics);
    }

    /**
     * Desenha as linhas de pericia visiveis: nome, valor e a sigla do atributo.
     *
     * <p>O nome e cortado pela largura ({@code truncateWithEllipsis}) em vez de
     * estourar a coluna: o maior nome da lista padrao e "Animal Handling" (15
     * caracteres), que cabe, mas um nome novo ou maior nao pode empurrar os
     * botoes para fora. O corte leva <b>reticencias</b> (27/09/2026) pelo mesmo
     * motivo do rotulo quebrado: um nome cortado em silencio e indistinguivel de
     * um nome inteiro, e nesta coluna -- que e onde o Mestre confere o que
     * escreveu -- ler "Resistenci" como nome completo e pior do que perder o fim
     * do texto.
     *
     * <p>27/09/2026: aqui o nome <b>nao</b> quebra em 2 linhas como os outros
     * rotulos da ficha. A coluna e compacta e a altura da linha pode chegar a
     * 9px ({@link #MIN_PER_ROW_H}), onde duas linhas de 8px nao cabem e a 2a
     * cairia em cima da linha seguinte. Cortar e o unico jeito de nao invadir os
     * botoes nessa coluna.
     *
     * <p>27/09/2026 (Sheet Editor): {@code periciaRows} guarda so a JANELA
     * visivel (o que cabe na coluna), nao a lista inteira. Por isso o indice da
     * pericia e {@code perScroll + i}. Sem o {@code + perScroll}, a tela
     * mostraria a pericia errada em cada linha apos rolar -- e compilaria
     * normalmente, que e o motivo de o comentario existir.
     */
    private void drawPericias(GuiGraphics graphics) {
        if (sheet == null) {
            return;
        }
        List<SheetData.Pericia> pericias = sheet.pericias();
        int scroll = clampPerScroll(perScroll);
        for (int i = 0; i < periciaRows.size(); i++) {
            int index = scroll + i;
            if (index >= pericias.size()) {
                break;
            }
            SheetData.Pericia pericia = pericias.get(index);
            PericiaRow row = periciaRows.get(i);
            int ty = row.y() + Math.max(1, (row.h() - 8) / 2);

            String name = truncateWithEllipsis(pericia.name(), row.nameW());
            graphics.drawString(this.font, name, row.nameX(), ty, COL_LABEL, false);

            int value = displayPericiaValue(pericia);
            // Mesma guarda do atributo: o valor legitimo cabe (valueBoxWidth mede
            // o pior caso, sinal incluido), mas um numero forjado e maior do que a
            // caixa e cortado, em vez de invadir o "-" e o "+". Nao depende do
            // piso 0 da pericia para o numero caber.
            String valueText = Integer.toString(value);
            if (this.font.width(valueText) > row.valueW()) {
                valueText = this.font.plainSubstrByWidth(valueText, row.valueW());
            }
            graphics.drawString(this.font, valueText,
                    row.valueX() + (row.valueW() - this.font.width(valueText)) / 2, ty,
                    COL_BOX_TEXT, false);

            // "+" desliga no teto (30) e "-" no piso (0), pelo mesmo motivo dos
            // atributos: o botao cinza diz "voce chegou no limite". Pelo mesmo
            // metodo do applyExtraState, para nao haver frame de divergencia
            // (ver applyStepButtons).
            applyStepButtons(row.minusButton(), row.plusButton(), value,
                    SheetData.Pericia.VALUE_MIN, SheetData.Pericia.VALUE_MAX);

            // A sigla do atributo fica no botao e vem do MODELO (27/09/2026):
            // antes era o shortName() do enum, e o Mestre nao podia renomear.
            row.attrButton().setMessage(
                    Component.literal(model().attributeLabel(displayPericiaAttributeId(pericia))));
        }
    }

    /**
     * Scroll da COLUNA de pericias, e so dela.
     *
     * <p>27/09/2026: o pedido foi "scroll so no painel de pericias", entao a
     * roda so rola quando o ponteiro esta em cima desta coluna. Fora dela, o
     * comportamento normal da tela continua valendo.
     *
     * <p>Rolar recria os widgets ({@code rebuildWidgets()} refaz o
     * {@code init()}) porque as linhas da coluna sao botoes de verdade, e nao
     * texto desenhado: e o unico jeito de mover o conjunto deles.
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (isOverPericiaColumn(mouseX, mouseY)) {
            int next = clampPerScroll(perScroll + (int) -Math.signum(scrollY));
            if (next != perScroll) {
                perScroll = next;
                rebuildWidgets();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /**
     * Barra de recurso que pode passar do maximo (feedback do usuario:
     * {@code 12/10} = 10 permanentes + 2 temporarios).
     *
     * <p><b>Decisao de desenho:</b> o denominador visual e
     * {@code max(valor, maximo)}, nao o maximo. Assim as duas parcelas ficam
     * lado a lado -- a parte que cabe no maximo na cor normal e o excedente
     * numa cor mais clara -- e quando o temporario acaba a barra volta a
     * encher de verdade (100%). Se o denominador fosse o maximo fixo, 12/10
     * seria 100% e o "+2" nao teria onde aparecer.
     *
     * <p><b>Caso geral (abaixo do maximo):</b> a parte normal e
     * {@code min(valor, maximo)}, nao {@code maximo} -- usar o maximo aqui
     * faria {@code 5/10} aparecer com a barra cheia. E {@code valor > 0} com
     * {@code maximo == 0} (transitorio enquanto o mestre edita o teto) e
     * desenhado como excedente, porque existe recurso: uma barra vazia
     * afirmaria "sem mana" quando ha 4.
     */
    private void drawResourceBar(GuiGraphics graphics, Bar bar,
                                 int fillColor, int overflowColor, int bgColor,
                                 int value, int max, boolean downed) {
        if (bar == null) {
            return;
        }
        if (downed || value <= 0) {
            drawBar(graphics, bar, fillColor, bgColor, 0.0);
            return;
        }
        int denom = Math.max(value, max);
        drawBarSplit(graphics, bar, fillColor, overflowColor, bgColor,
                (double) Math.min(value, max) / denom,
                (double) Math.max(0, value - max) / denom);
    }

    /** Aviso de "deitado" na faixa superior (fora da area dos widgets). */
    @Override
    protected void renderTopLeft(GuiGraphics graphics) {
        // sheet == null ainda esta carregando: nao afirmar "DOWNED" antes.
        if (sheet == null || numericValue("hp") > 0) {
            return;
        }
        graphics.drawString(this.font, "DOWNED (HP <= 0)", panelX + 4, 24, COL_DOWNED, false);
    }

    /**
     * Escreve o recurso dentro da barra, degradando o <b>formato</b> e nunca o
     * numero.
     *
     * <p><b>27/09/2026 (bug do usuario):</b> a barra e estreita e depende do
     * rotulo de um jeito perverso: com o rotulo longo de Mana o
     * {@code labelW} cresce, o {@code boxW} encolhe (ele faz {@code min} da
     * area disponivel), e a barra herda o encolhimento -- cai para ~39px. Como
     * {@code drawValue} roda DEPOIS de {@code super.render()}, um texto maior
     * que a barra ficava por cima do "-", do "+" e do rotulo "Max", e o
     * conserto de nao invadir foi {@code plainSubstrByWidth}. Mas cortar o
     * <b>texto</b> <b>mente</b>: "9999 / 9999" (~61px) virava algo como
     * "1234 /", que e uma leitura plausivel e errada de um recurso --
     * justamente nos valores de 4 digitos que o filtro de teto
     * ({@link SheetData#MAX_RESOURCE}) passou a legalizar.
     *
     * <p>Por isso a degradacao e de <b>formato</b>, nesta ordem: o par inteiro
     * ("9999 / 9999"), depois so o valor atual ("9999"), e so no ultimo caso o
     * numero perde os algarismos que nao cabem. Nenhum nivel mostra metade de um
     * formato, e nenhum nivel passa da barra. O ultimo caso so e alcancavel por
     * valor fora do intervalo legal (um payload forjado), que e a mesma coisa
     * que o guarda do atributo e da pericia ja aceitam cortar.
     */
    private void drawValue(GuiGraphics graphics, Bar bar, int value, int max, boolean downed) {
        if (bar == null) {
            return;
        }
        String current = Integer.toString(value);
        String full = current + " / " + max;
        String text;
        if (this.font.width(full) <= bar.w()) {
            text = full;
        } else if (this.font.width(current) <= bar.w()) {
            text = current;
        } else {
            text = this.font.plainSubstrByWidth(current, bar.w());
        }
        int x = bar.x() + (bar.w() - this.font.width(text)) / 2;
        int y = bar.y() + (bar.h() - 8) / 2;
        graphics.drawString(this.font, text, x, y, downed ? COL_DOWNED : COL_BOX_TEXT, true);
    }
}
