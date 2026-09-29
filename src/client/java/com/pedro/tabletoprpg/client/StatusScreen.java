package com.pedro.tabletoprpg.client;

import com.pedro.tabletoprpg.RpgNetworking;
import com.pedro.tabletoprpg.SheetData;
import com.pedro.tabletoprpg.SheetModel;
import com.pedro.tabletoprpg.SheetModelHolder;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tela "Status" da ficha: identidade, recursos (barras), progresso e atributos.
 *
 * <p><b>Vida e Mana sao barras</b> (feedback do usuario): o valor muda pelos 6
 * botoes de passo da propria linha ({@code -10 -5 -1 +1 +5 +10}, decisao do
 * usuario em 29/09/2026), em {@code Button} COMUM: vida e Mana <b>nao</b> tem
 * repeticao ao segurar, ao contrario do atributo e da pericia, porque um passo
 * de 1 ja resolve o ajuste fino e a rajada de -10/+10 cobre o resto. O valor so
 * substitui o do servidor quando o servidor devolve a ficha.
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
 * <p>O campo <b>Max</b> continua como caixa de texto editavel na linha de cada
 * barra: sem ele nao haveria como mudar o teto do recurso.
 *
 * <p><b>29/09/2026, bloco de vida em 3 linhas por recurso:</b> o titulo da
 * secao e o proprio rotulo do recurso (vem do modelo, {@code hpLabel} e
 * {@code manaLabel}, que o Mestre ja edita no Sheet Editor) e o titulo antigo
 * "Vitals" sumiu; a linha do meio tem "Max", a caixa do teto e a barra, agora
 * bem mais larga (o resto da coluna esquerda, em vez de ~110px) e 4px mais
 * alta; a linha de baixo sao os 6 botoes de passo, com a largura toda da
 * coluna e gap igual. O que a barra e a caixa dividem nao e mais nada, entao o
 * numero do recurso ({@code drawValue}) tem bem mais espaco para nao ser
 * cortado -- que era o defeito da barra estreita.
 *
 * <p><b>Atributos sao botoes, nao caixas de texto</b> (decisao do usuario): o
 * atributo virou modificador somado as pericias, entao o mestre so precisa
 * subir e descer -- e um numero solto nao limitava mais. Nao ha barra nem
 * caixa: apenas {@code rotulo  [-]  numero  [+]}. 27/09/2026: os glifos
 * {@code <} e {@code >} viraram {@code -} e {@code +}, para o par de botoes
 * da tela inteira falar a mesma coisa. O piso e o teto do atributo, e o teto da
 * pericia, <b>sao configurados pelo Mestre no Sheet Editor (28/09/2026)</b> e
 * chegam junto da ficha em {@code sheet.attributeValueMin/Max} e
 * {@code sheet.periciaValueMax}; enquanto a ficha nao chegou, vale o padrao do
 * {@link SheetModel} (-30, 30 e 30) -- ver {@link #attributeValueMin},
 * {@link #attributeValueMax} e {@link #periciaValueMax}. Por isso <b>os dois</b>
 * botoes de atributo desligam no limite, e o cinza e o feedback de "voce chegou
 * aqui" dos dois lados.
 *
 * <p>29/09/2026: o mesmo feedback vale para os 6 botoes de vida/mana, com o
 * piso e o teto do <b>recurso</b> ({@link SheetData#MAX_HP_FLOOR} a
 * {@link SheetData#MAX_RESOURCE} no HP, 0 a {@link SheetData#MAX_RESOURCE} na
 * Mana) e nao com o intervalo do atributo -- ver {@link #canStepResource}.
 *
 * <p><b>29/09/2026, a coluna esquerda tem scroll:</b> com 3 linhas por recurso
 * o conteudo estourou a altura da janela baixa (a resolucao de referencia do
 * projeto), e antes nao havia rolagem nenhuma na esquerda. O padrao copiado e o
 * da coluna de pericias ({@link #leftScroll}, {@link #clampLeftScroll},
 * {@link #isOverLeftPanel}): a roda rola SO quando o ponteiro esta sobre a
 * coluna e SO quando o offset muda.
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

    /**
     * Passos dos 6 botoes de vida/mana, em pixels (29/09/2026).
     *
     * <p>Decisao do usuario: sem repeticao ao segurar (ao contrario do
     * atributo e da pericia, que usam {@link HoldStepButton}), porque o par
     * -1/+1 ja da o ajuste fino e -10/+10 cobrem o resto sem obrigar o jogador
     * a apertar duas vezes.
     */
    private static final int[] RESOURCE_STEPS = {-10, -5, -1, 1, 5, 10};
    /** Folga entre os 6 botoes de vida/mana. */
    private static final int RESOURCE_STEP_GAP = 4;
    /**
     * Altura da barra do recurso, em pixels, abaixo da linha.
     *
     * <p>29/09/2026: era {@code rowH - 6}, o que deixava a barra com uma faixa
     * vazia em cima e embaixo dentro da linha. A barra agora ocupa a MESMA
     * altura e o mesmo Y da caixa do teto ({@code rowH - 2}), o que da os 4px
     * de ganho pedidos e ainda deixa o numero desenhado na barra na mesma
     * altura do texto da caixa.
     */
    private static final int RESOURCE_BAR_H_MARGIN = 2;

    /** Altura de linha da coluna de pericias, calculada em buildPanel(). */
    private int perRowH;
    /**
     * Primeira linha da coluna ESQUERDA visivel (scroll da coluna esquerda).
     *
     * <p>29/09/2026: o bloco de vida passou a 3 linhas por recurso e a coluna
     * esquerda passou a estourar a janela baixa, que nao tinha rolagem nenhuma.
     * O offset e em <b>linhas</b> (multiplicado por {@code rowH} em
     * {@code buildPanel}), e nao em pixels: assim tudo continua alinhado no
     * mesmo ritmo de linha de antes, e a barra, a caixa e os botoes de um
     * recurso nunca se separam ao rolar.
     */
    private int leftScroll;

    /**
     * Onde a coluna esquerda comeca e quantas linhas ela tem, medido dentro do
     * painel (ja com o respiro de {@link #PANEL_PAD}).
     *
     * <p>29/09/2026: sao as tres medidas de que {@link #maxLeftScroll} precisa,
     * e elas so existem depois do calculo de {@code rowH} em
     * {@code buildPanel}. {@code leftBottom} e o que garante que, no fim da
     * rolagem, a ultima linha fica dentro do painel e nao em cima do botao Back.
     */
    private int leftTop;
    private int leftBottom;
    private int leftTotalRows;

    /** Retangulo da coluna esquerda, guardado para o teste de "o mouse esta aqui". */
    private int leftPanelX;
    private int leftPanelW;

    /** Geometria das 20 linhas (o widget e' a moldura; nome/valor sao desenhados). */
    private final List<PericiaRow> periciaRows = new ArrayList<>();

    /** Valor otimista por <b>id</b> de pericia, para cliques rapidos nao se perderem. */
    private final Map<String, Integer> pendingPericiaValue = new HashMap<>();
    /** Atributo otimista por <b>id</b> de pericia, mesmo proposito de {@link #pendingPericiaValue}. */
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
     * Os 6 botoes de passo de vida/mana, com o <b>campo</b> e o <b>delta</b> de
     * cada um (29/09/2026).
     *
     * <p>Guardar o par e obrigatorio porque o botao e um {@code Button} comum,
     * que so sabe apertar: quem decide se ele ainda anda (o cinza no limite) e
     * quem escreve o {@code active} nao tem como perguntar "qual campo?". E a
     * lista e o caminho que espelha o que {@code canStepAttribute} faz com o
     * atributo e o que {@code applyStepButtons} faz com a pericia -- ver
     * {@link #applyResourceStepButtons}.
     */
    private final List<ResourceStep> resourceSteps = new ArrayList<>();

    /**
     * Um botao de passo de recurso: o campo que ele mexe e o quanto.
     *
     * @param field  recurso ("hp"/"mana")
     * @param delta  passo, em pixels (veja {@link #RESOURCE_STEPS})
     * @param button o widget, para escrever o {@code active}
     */
    private record ResourceStep(String field, int delta, Button button) {
    }

    /**
     * Setas <b>sem limite de valor</b>: hoje e so o botao de atributo da pericia.
     * Fica cinza quando a ficha e somente leitura.
     *
     * <p>27/09/2026: as setas de atributo e de pericia <b>nao</b> entram aqui,
     * porque dependem tambem do valor (piso/teto). Elas sao escritas por
     * {@link #applyStepButtons}, o mesmo caminho do render, para
     * {@code applyExtraState} nao religar um botao travado no limite. 29/09/2026:
     * as de HP/Mana tambem sairam daqui, porque os 6 botoes de passo do recurso
     * tem limite proprio ({@link #canStepResource}).
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
        resourceSteps.clear();
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
        // 29/09/2026 (scroll da esquerda): o retangulo do painel esquerdo e
        // guardado para o teste de "o mouse esta sobre a coluna", que decide se
        // a roda rola a esquerda. E a MESMA area do hit test da coluna de
        // pericias, so que pelo outro lado do painel.
        leftPanelX = x0;
        leftPanelW = leftW;

        // Altura de linha da coluna de pericias: e' ela que decide quantas
        // cabem sem scroll. Limitada para nunca passar da altura de uma linha
        // normal (senao a coluna ficaria igual ao resto e nao caberia).
        //
        // 27/09/2026 (Sheet Editor): o total vem do modelo, em tempo de
        // execucao, e pode chegar a 30. A coluna tem scroll proprio, entao o que
        // importa aqui e o espaco disponivel -- nao o total.
        //
        // 29/09/2026 (colunas alinhadas, escolha do usuario): a geometria das
        // pericias saiu daqui e passou a ser calculada DEPOIS do `rowH`, porque o
        // cabecalho delas passou a usar a altura real de linha (ver o bloco logo
        // depois de `leftScroll`). Com a estimativa antiga -- `rowHOrDefault`,
        // que dividia a area por 12 linhas FIXAS, sem relacao com as ~19 linhas
        // reais da ficha -- as duas colunas so coincidiam por acaso, quando ambas
        // batiam no MAX_ROW_H; em janela baixa a direita entrava ate 8px mais
        // embaixo que a esquerda, que era o que o usuario via como "a coluna
        // esquerda colocada mais pra cima".

        // Duas colunas de atributos so quando o painel e largo o bastante para
        // os rotulos (FOR/DES/...) nao se chocarem com os dois botoes.
        boolean twoColumns = leftW >= 260;
        // 27/09/2026: o numero de linhas de atributo vem do modelo (1 a 10), e
        // nao mais das constantes 3/6. A altura reservada precisa acompanhar,
        // senao os atributos de baixo caem em cima da secao seguinte.
        int attrRowCount = twoColumns ? attrRowCount(2) : attrRowCount(1);
        // Aritmetica da conta (29/09/2026), na ordem em que as linhas sao
        // desenhadas abaixo:
        //   3 titulos fixos (Identity, Progress, Attributes)
        // + 5 campos de identidade (playerName, characterName, race,
        //   characterClass, background)
        // + 3 linhas de HP (titulo, teto+barra larga, os 6 botoes de passo)
        // + 3 linhas de Mana (o mesmo bloco de 3, so que opcional)
        // + 2 de progresso (level e XP; o titulo "Progress" ja esta nos 3 de
        //   cima, e a linha do XP sai pelo opcional quando o modelo a esconde)
        // + attrRowCount
        // Sem folga no fim.
        //
        // 29/09/2026: o "Vitals" saiu e cada recurso virou o titulo da SUA
        // secao (o rotulo vem do modelo), com 3 linhas cada: titulo, teto+barra
        // larga e os 6 botoes de passo. As 3 da Mana entram na BASE porque sao
        // desenhadas de verdade quando o Mestre liga a Mana -- por isso o
        // opcional da Mana abaixo desconta essas mesmas 3 (e nao 1) quando ela
        // esta desligada.
        //
        // 29/09/2026 (correcao): as 3 linhas da Mana faltavam na base. Sem elas
        // a conta ficava 3 linhas curta nos DOIS casos (ligada: faltava
        // contar o que existe; desligada: a base nao tinha as 3 e a subtracao
        // tirava 3 que nunca entraram). Com a conta curta, {@code rowH} batia
        // no piso de MIN_ROW_H cedo demais, {@code maxLeftScroll} era curto e
        // os ULTIMOS atributos ficavam fora do alcance da rolagem -- e ainda
        // eram desenhados abaixo de {@code leftBottom}, disputando espaco com o
        // botao Back. A soma agora bate com {@code leftTotalRows}, que e o que
        // {@code maxLeftScroll} consome, entao a ultima linha visivel cai
        // dentro de leftTop/leftBottom.
        //
        // 29/09/2026: o campo "playerName" entrou no total. A contagem e o que
        // define a altura de linha, entao deixar de fora aqui encolheria a
        // linha e a ultima linha de atributo cairia em cima da secao seguinte.
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
        //
        // 29/09/2026: o que sobra desse estouro na janela baixa e resolvido pela
        // rolagem da coluna esquerda ({@link #leftScroll}), e nao por mais uma
        // folga na conta.
        int neededRows = 3 + 5 + 3 + 3 + 2 + attrRowCount;
        // 27/09/2026 (Sheet Editor): o Mestre pode desligar a raca, a mana e o
        // XP, e ai as linhas abaixo somem. Sem descontar, a altura de linha
        // seria calculada para linhas que nao existem e sobraria espaco vazio
        // no fim do painel. 29/09/2026: a Mana desligada leva 3 linhas com ela
        // (titulo, barra e botoes), e nao 1.
        int optionalRows = (model.isEnabled("race") ? 0 : 1)
                + (model.isEnabled("mana") ? 0 : 3)
                + (model.xp() == SheetModel.XpMode.HIDDEN ? 1 : 0);
        neededRows -= optionalRows;
        rowH = fitRowHeight(neededRows, topY, bottomY);
        // 29/09/2026 (scroll da esquerda): o total e a area util da coluna
        // esquerda sao medidos aqui, depois do rowH, porque sao eles que
        // definem quantas linhas cabem sem rolar (ver maxLeftScroll).
        leftTop = topY;
        leftBottom = bottomY;
        leftTotalRows = neededRows;
        // 29/09/2026 (colunas alinhadas, escolha do usuario): a coluna de
        // pericias ESTICA as linhas para preencher a altura toda
        // (`perAvail / perCount`), e a esquerda nao estirava: terminava numa
        // fronteira de linha, deixando ate `rowH - 1` px de vazio no rodape --
        // metade da "distancia do topo/rodape" desigual que o usuario apontou.
        // Aqui a esquerda faz a mesma conta que a direita: divide a area util
        // pelo numero de linhas que cabem nela. O `rowH` final pode subir 1 ou
        // 2px (nunca descer, e nunca sair de MIN/MAX_ROW_H), e o clamp da
        // rolagem vem DEPOIS para consumir o `rowH` novo.
        int janelaLeft = leftBottom - leftTop;
        int linhasVisiveis = Math.max(1, janelaLeft / Math.max(1, rowH));
        int rowHJusto = janelaLeft / linhasVisiveis;
        if (rowHJusto >= MIN_ROW_H && rowHJusto <= MAX_ROW_H) {
            rowH = rowHJusto;
        }
        leftScroll = clampLeftScroll(leftScroll);

        // Geometria da coluna de pericias, agora com o `rowH` ja resolvido: o
        // cabecalho (`perTitleH`) tem a MESMA altura de linha da primeira linha
        // da esquerda, para as duas colunas comecarem no mesmo Y.
        int perCount = periciaCount();
        int perTitleH = rowH;
        int perAvail = Math.max(MIN_PER_ROW_H, bottomY - topY - perTitleH);
        perRowH = Math.max(MIN_PER_ROW_H,
                Math.min(perRowHCap(topY, bottomY), perAvail / Math.max(1, perCount)));

        int labelW = fieldLabelWidth(leftW);
        // Campo de tamanho MEDIO e centralizado (feedback do usuario: as caixas
        // de texto estavam grandes demais, ocupando todo o resto da linha).
        // Em vez de esticar ate a borda, a caixa tem teto e o sobra da linha
        // e dividido em duas metades, entando as duas colunas ficam simetricas.
        int fieldArea = Math.max(60, leftW - labelW - 6);
        int boxW = Math.max(60, Math.min(FIELD_W_MAX, fieldArea));
        int boxX = x0 + labelW + (fieldArea - boxW) / 2;

        // 29/09/2026 (campo "Player"): a caixa deste campo e visivelmente mais
        // estreita que as de texto normais, como o usuario pediu ("pode ser
        // pequena"). Metade do `boxW` das outras: 120 no painel de referencia
        // (boxW 240), o que cabe um nome curto sem sobrar faixa vazia, e o piso
        // de 60 e o mesmo que o `fieldArea` ja usa, para o campo nao sumir em
        // janela estreita. O `boxX` e o mesmo, entao a coluna de caixas continua
        // alinhada.
        int playerBoxW = Math.max(60, boxW / 2);

        // 29/09/2026 (scroll da esquerda): o conteudo da coluna esquerda comeca
        // `leftScroll` linhas ACIMA do topo util, e as linhas que a rolagem ja
        // escondeu sao montadas fora da janela (ver drawY). O deslocamento e em
        // linhas inteiras, e nao em pixels, para a barra, a caixa do teto e os 6
        // botoes de um recurso continuarem na mesma linha quando a coluna rola.
        //
        // Repare que a posicao do drawY e o que vai para o add*, e o avanco do Y
        // e sempre `y += rowH` no `y` REAL: os add* devolvem o proximo Y a partir
        // do que receberam, e um Y de "estacionario" (o de uma linha ja rolada
        // para fora) arrastaria o resto do painel para baixo da tela.
        int y = topY - leftScroll * rowH;

        // ---------------- Identity ----------------
        // 29/09/2026: o titulo era `model.nameLabel()`, que com o modelo padrao
        // desenha "Name" — o mesmo texto do primeiro campo logo abaixo. Os outros
        // tres titulos ("Progress", "Attributes") ja eram literais, entao
        // este era o unico que mostrava o rotulo do campo no lugar do nome da
        // secao. Agora e literal como os outros. O que continua vindo do modelo e
        // o rotulo do CAMPO (`SheetData.labelOf`), que e o que o Mestre renomeia
        // no Sheet Editor: sao coisas diferentes.
        addSection("Identity", x0, drawY(y), leftW);
        y += rowH;
        // 29/09/2026: primeiro campo do bloco, na frente do nome do personagem.
        addField("playerName", x0, drawY(y), boxX, playerBoxW, labelW, false);
        y += rowH;
        addField("characterName", x0, drawY(y), boxX, boxW, labelW, false);
        y += rowH;
        if (model.isEnabled("race")) {
            addField("race", x0, drawY(y), boxX, boxW, labelW, false);
            y += rowH;
        }
        addField("characterClass", x0, drawY(y), boxX, boxW, labelW, false);
        y += rowH;
        addField("background", x0, drawY(y), boxX, boxW, labelW, false);
        y += rowH;

        // ---------------- Vida e Mana (3 linhas por recurso) ----------------
        // 29/09/2026: o titulo "Vitals" sumiu. Cada recurso e o titulo da SUA
        // secao, com o rotulo que o Mestre ja edita no Sheet Editor
        // (hpLabel/manaLabel) -- nao ha rotulo novo no modelo. Abaixo do titulo
        // vao 2 linhas: o teto com a sua caixa e a barra larga, e os 6 botoes de
        // passo com a largura toda da coluna.
        addSection(model.hpLabel(), x0, drawY(y), leftW);
        y += rowH;
        hpBar = addResourceBlock(x0, leftW, drawY(y), "hpMax");
        y += rowH;
        addResourceStepRow(x0, drawY(y), leftW, "hp");
        y += rowH;
        if (model.isEnabled("mana")) {
            addSection(model.manaLabel(), x0, drawY(y), leftW);
            y += rowH;
            manaBar = addResourceBlock(x0, leftW, drawY(y), "manaMax");
            y += rowH;
            addResourceStepRow(x0, drawY(y), leftW, "mana");
            y += rowH;
        }

        // ---------------- Progress ----------------
        addSection("Progress", x0, drawY(y), leftW);
        y += rowH;
        addField("level", x0, drawY(y), boxX, boxW, labelW, true);
        y += rowH;
        // XP em modo TEXT vira caixa de texto (o campo "xpText"); em NUMBER
        // continua numerico; em HIDDEN a linha inteira nao existe.
        if (model.xp() == SheetModel.XpMode.TEXT) {
            addField("xptext", x0, drawY(y), boxX, boxW, labelW, false);
            y += rowH;
        } else if (model.xp() != SheetModel.XpMode.HIDDEN) {
            addField("xp", x0, drawY(y), boxX, boxW, labelW, true);
            y += rowH;
        }

        // ---------------- Attributes ----------------
        // Decisao do usuario: atributos sao botoes -/+ (passo 1), sem caixa de
        // texto e sem barra, começando em 0, com teto 30 e PISO -30 (podem ser
        // negativos, decisao do usuario em 27/09/2026).
        addSection("Attributes", x0, drawY(y), leftW);
        y += rowH;
        List<SheetModel.AttributeDef> attrs = model.attributes();
        if (twoColumns) {
            int colW = (leftW - 6) / 2;
            for (int i = 0; i < attrs.size(); i++) {
                int cx = x0 + (i % 2) * colW;
                int cy = drawY(y) + (i / 2) * rowH;
                addAttributeRow(cx, cy, colW - 4, attrs.get(i).id());
            }
            y += attrRowCount(2) * rowH;
        } else {
            for (SheetModel.AttributeDef attr : attrs) {
                addAttributeRow(x0, drawY(y), leftW - 6, attr.id());
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

    // ------------------------------------------------------------------
    // SCROLL DA COLUNA ESQUERDA (29/09/2026)
    // ------------------------------------------------------------------

    /**
     * Onde uma linha da coluna esquerda pode ser desenhada.
     *
     * <p><b>Por que existe:</b> a rolagem ({@link #leftScroll}) desloca o
     * conteudo para cima, e as primeiras linhas ficam com o Y acima do topo da
     * tela. Desenhadas la, elas cobririam o cabecalho -- o titulo "Sheet: ..." e
     * a faixa "Editable"/"DOWNED" -- que sao desenhados FORA da area de conteudo
     * pelo {@code CharacterSheetScreen}. Entao a linha que a rolagem ja escondeu
     * e montada <b>abaixo da janela</b>: ela existe (o valor do servidor continua
     * chegando nela e o clique continua barrado pelo Y), mas nao aparece em
     * lugar nenhum, e o unico custo e o desenho de um widget fora da tela.
     *
     * <p>O corte de cima e em {@code contentTop} e nao em {@link #leftTop} (o
     * topo interno do painel) de proposito: sobra uma faixa de poucos pixels
     * entre os dois, e a linha que ficar ali e desenhada no painel, sem cobrir o
     * cabecalho.
     *
     * <p>29/09/2026 (bug relatado pelo usuario depois do primeiro teste em
     * jogo): o corte de BAIXO tambem faltava, e e ele que deixava o texto da
     * coluna passar por cima do botao {@code Back}. {@code maxLeftScroll}
     * limita o quanto da para rolar, mas nao esconde as linhas que ficam
     * <b>abaixo</b> da janela visivel: com {@code leftScroll} em 0 elas eram
     * montadas no Y real e apareciam na faixa de respiro do painel e em cima do
     * rodape. Agora a linha so e desenhada se couber inteira entre
     * {@link #leftTop} e {@link #leftBottom} -- o mesmo par que
     * {@link #maxLeftScroll} usa para contar as linhas visiveis, entao as duas
     * contas concordam. Uma linha cortada pela borda e omitida em vez de sangrar,
     * e com a rolagem no fim a ultima linha cabe exata, porque
     * {@code maxLeftScroll} e {@code leftTotalRows - visiveis}.
     *
     * @param y Y real da linha, ja com o deslocamento da rolagem
     * @return o Y a passar para o {@code add*}
     */
    private int drawY(int y) {
        boolean foraDoTopo = y + rowH <= contentTop;
        boolean foraDoFundo = y + rowH > leftBottom;
        return (foraDoTopo || foraDoFundo) ? this.height + 64 : y;
    }

    /**
     * Maior rolagem valida da coluna esquerda: quantas linhas sobram quando a
     * ultima chega no fim da area util.
     *
     * <p>E o mesmo raciocinio de {@link #maxPerScroll}, com a contagem de
     * linhas da esquerda ({@link #leftTotalRows}) e a altura util ja sem o
     * respiro do painel ({@code leftBottom - leftTop}). Com 0 (janela grande,
     * cabe tudo) nao existe o que rolar, e {@link #mouseScrolled} nem chega
     * aqui -- a roda passa para a tela.
     */
    private int maxLeftScroll() {
        int available = Math.max(1, leftBottom - leftTop);
        int visible = Math.max(1, available / Math.max(1, rowH));
        return Math.max(0, leftTotalRows - visible);
    }

    private int clampLeftScroll(int value) {
        return Math.max(0, Math.min(value, maxLeftScroll()));
    }

    /**
     * O mouse esta sobre a coluna esquerda? So ai a roda rola esta coluna.
     *
     * <p>A area e a do painel esquerdo, e a faixa vertical e a mesma do clamp da
     * rolagem ({@link #leftTop} a {@link #leftBottom}), e nao
     * {@code contentTop}/{@code contentBottom}: sao eles que ficam PANEL_PAD
     * para dentro, e aceitar a roda nessa faixa de respiro seria aceitar scroll
     * onde nao ha conteudo -- e sem o mesmo par do clamp o teste e o limite
     * discordariam de onde a coluna comeca e termina. E consultada
     * <b>depois</b> do teste da coluna de pericias em {@link #mouseScrolled}: as
     * duas regioes nao se cruzam, e a preferencia continua sendo da coluna que
     * ja tinha rolagem.
     */
    private boolean isOverLeftPanel(double mouseX, double mouseY) {
        return mouseX >= leftPanelX && mouseX < leftPanelX + leftPanelW
                && mouseY >= leftTop && mouseY < leftBottom;
    }

    /**
     * O jogador esta digitando em alguma caixa da ficha?
     *
     * <p>29/09/2026: rolar recria os widgets ({@code rebuildWidgets()} refaz o
     * {@code init()}), e as caixas nascem vazias e so recebem de novo o que o
     * servidor ja tem ({@code applySheetToWidgets}). O que o jogador ja ENVIOU
     * nao se perde -- volta com o eco da ficha --, mas o texto ainda em digitar
     * (metade de um nome, o "-" de um numero negativo) morre com a caixa antiga.
     * Perder o que o jogador escreveu e pior do que a rolagem nao responder
     * naquele instante, entao a coluna esquerda fica travada enquanto um campo
     * estiver com o foco, e e preciso clicar em outro lugar para voltar a rolar.
     * Nao ha como rolar no meio da digitacao sem um
     * estado a mais na tela (e o texto em edicao ainda nao tem valor no
     * servidor), e o ganho seria pequeno.
     */
    private boolean isEditingField() {
        for (EditBox box : fieldBoxes.values()) {
            if (box.isFocused()) {
                return true;
            }
        }
        return false;
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
        // 29/09/2026 (bug latente, corrigido junto com o alinhamento): isto usava
        // `contentTop`/`contentBottom`, os limites EXTERNOS do painel, enquanto o
        // `buildPanel` desenha dentro dos limites internos (`leftTop`/`leftBottom`,
        // PANEL_PAD a menos). Sao 8px a mais de altura aqui, o que podia estimar
        // uma pericia visivel a mais do que a desenhada e deixar `maxPerScroll`
        // curto demais para alcancar a ultima -- o mesmo tipo de bug do
        // `neededRows` curto, e tambem invisivel para build e testes. Agora a
        // conta e a mesma do `buildPanel`: cabecalho de altura `rowH` e o
        // `perRowH` real.
        int perTitleH = rowH;
        int avail = Math.max(MIN_PER_ROW_H, leftBottom - leftTop - perTitleH);
        return Math.max(1, Math.min(total, avail / Math.max(1, perRowH)));
    }

    /** Mesma conta de {@link #perVisibleCount}, sem usar {@link #perRowH}. */
    private int perVisibleCountFor(int topY, int bottomY) {
        int total = periciaCount();
        if (total <= 0) {
            return 1;
        }
        int perTitleH = rowH;
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
     * acompanha a fonte. <b>28/09/2026:</b> os extremos nao sao mais as
     * constantes, e sim o intervalo que o Mestre definiu, lido da ficha
     * ({@link #attributeValueMin}/{@link #attributeValueMax} e
     * {@link #periciaValueMax}, que caem no padrao do {@link SheetModel} antes
     * de a ficha chegar); a pericia tem piso 0, entao o texto mais largo dela e o
     * teto, ja coberto aqui. Medir o intervalo real, e nao um teto de 999, e o
     * que mantem a caixa com a largura de antes em vez de estourar a coluna.
     *
     * <p>Guarde de truncamento: a caixa cobre o valor <b>legitimo</b>. Um
     * payload forjado pode gravar um numero muito grande, e nesse caso quem corta
     * e o desenho (veja {@code renderContent} e {@code drawPericias}).
     */
    private int valueBoxWidth() {
        int attr = Math.max(this.font.width(Integer.toString(attributeValueMin())),
                this.font.width(Integer.toString(attributeValueMax())));
        int per = this.font.width(Integer.toString(periciaValueMax()));
        return Math.max(attr, per) + 4;
    }

    // ------------------------------------------------------------------
    // LIMITES DE VALOR (28/09/2026: configuraveis pelo Mestre no Sheet Editor)
    // ------------------------------------------------------------------

    /**
     * Piso do atributo, da propria ficha; o padrao do {@link SheetModel} antes
     * dela chegar.
     *
     * <p><b>Por que a ficha e nao o modelo:</b> o modelo e o que o Mestre salvou,
     * e ele muda no meio da sessao. A ficha ja vem com o intervalo que estava em
     * vigor quando o servidor a montou, entao e ela que descreve o limite de forma
     * coerente com os valores que a tela esta exibindo. O fallback existe so
     * para o primeiro render, antes do primeiro pacote: mostrar o padrao por
     * um frame e melhor do que mostrar 0 e deixar as setas todas desligadas.
     */
    private int attributeValueMin() {
        return sheet == null ? SheetModel.DEFAULT_ATTRIBUTE_VALUE_MIN : sheet.attributeValueMin();
    }

    /** Teto do atributo; ver {@link #attributeValueMin}. */
    private int attributeValueMax() {
        return sheet == null ? SheetModel.DEFAULT_ATTRIBUTE_VALUE_MAX : sheet.attributeValueMax();
    }

    /** Teto da pericia; ver {@link #attributeValueMin}. */
    private int periciaValueMax() {
        return sheet == null ? SheetModel.DEFAULT_PERICIA_VALUE_MAX : sheet.periciaValueMax();
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

        // "-" e "+" com segurada (28/09/2026): segurar acelera. O canStep
        // responde pelo valor OTIMISTA (pendingPericiaValue) e pelo limite, e
        // nao pelo `active` do botao -- que so e reescrito no render, um frame
        // atras, e deixaria a rajada estourar 1 ou 2 passos alem do teto.
        HoldStepButton minus = new HoldStepButton(minusX, y, arrow, h, Component.literal("-"),
                b -> stepPericiaValue(index, -1), () -> canStepPericia(index, -1));
        HoldStepButton plus = new HoldStepButton(plusX, y, arrow, h, Component.literal("+"),
                b -> stepPericiaValue(index, 1), () -> canStepPericia(index, 1));
        // 29/09/2026 (bug do usuario: "o botao de atributo da coluna da direita
        // pisca"): a sigla nascia VAZIA e era escrita no render, em
        // `drawPericias` -- que roda em `renderContent`, DEPOIS de
        // `super.render()`, que e quem desenha os widgets. Cada reconstrucao
        // (rodar a coluna esquerda OU a direita chama `rebuildWidgets`) pintava o
        // botao vazio por um frame, e e isso que era o pisca. Agora a sigla ja
        // nasce preenchida; o render continua reescrevendo, para o rotulo
        // acompanhar a ficha.
        Button attr = Button.builder(Component.literal(attrLabelFor(index)),
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

    /**
     * Sigla do atributo da pericia, para o botao nascer ja preenchido.
     *
     * <p>29/09/2026: o botao nascia com {@code Component.literal("")} e a sigla
     * era escrita no render, um frame depois de o botao ja ter sido desenhado --
     * o "pisca" que o usuario viu ao rolar qualquer uma das colunas. Aqui a
     * sigla e resolvida na criacao, e o render so atualiza. Sem ficha ainda
     * ({@code sheet == null}) ou com indice fora da faixa, devolve vazio, que e o
     * que o botao mostrava ate o primeiro render.
     */
    private String attrLabelFor(int index) {
        if (sheet == null || index < 0 || index >= sheet.pericias().size()) {
            return "";
        }
        return model().attributeLabel(
                displayPericiaAttributeId(sheet.pericias().get(index)));
    }

    /** Abre a lista suspensa dos atributos do MODELO para a pericia da linha. */
    private void openPericiaAttribute(int index) {
        if (!canEdit || this.minecraft == null || sheet == null || index >= sheet.pericias().size()) {
            return;
        }
        SheetData.Pericia pericia = sheet.pericias().get(index);
        this.minecraft.setScreen(new AttributePickerScreen(
                this, pericia.name(), pericia.attributeId(), chosen -> {
                    // 28/09/2026: a chave e o ID da pericia, nao o nome. O nome
                    // e' o rotulo e pode mudar no editor; o id e' o que o
                    // servidor usa para achar a linha.
                    pendingPericiaAttribute.put(pericia.id(), chosen);
                    ClientPlayNetworking.send(RpgNetworking.SheetPericiaPayload.setAttribute(
                            targetName, pericia.id(), chosen));
                }));
    }

    /**
     * Muda o valor da pericia (0 ate {@link #periciaValueMax}) e avisa o servidor.
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
                Math.min(periciaValueMax(), current + delta));
        if (next == current) {
            return; // ja no limite: nao envia nada
        }
        pendingPericiaValue.put(pericia.id(), next);
        ClientPlayNetworking.send(RpgNetworking.SheetPericiaPayload.setValue(
                targetName, pericia.id(), next));
    }

    /**
     * A seta ainda anda? Mesmo calculo de {@link #stepPericiaValue}, sem
     * enviar nada.
     *
     * <p><b>Por que o loop de segurada nao pode usar o {@code active} do
     * botao:</b> {@code applyStepButtons} escreve o {@code active} no render
     * (e depois de {@code super.render()}), entao o cinza do limite so chega no
     * frame seguinte. Confiar nele deixaria a rajada passar 1 ou 2 passos do
     * teto; o servidor segura, e o numero piscaria enquanto o valor otimista
     * estivesse a frente do eco ({@code keepPending}).
     *
     * <p>Por isso a resposta vem do valor <b>otimista</b>
     * ({@link #displayPericiaValue}, que le {@code pendingPericiaValue}) e dos
     * limites — igual ao clamp de {@code stepPericiaValue}, que e o mesmo
     * {@code next != current}: os dois concordam, e nao ha overflow porque o
     * valor gravado ja passa pelo clamp de 0 ate {@link #periciaValueMax}. O
     * {@code canEdit} vem junto, entao ficha somente-leitura nao envia nada.
     */
    private boolean canStepPericia(int index, int delta) {
        if (!canEdit || sheet == null || index >= sheet.pericias().size()) {
            return false;
        }
        SheetData.Pericia pericia = sheet.pericias().get(index);
        int current = displayPericiaValue(pericia);
        int next = Math.max(SheetData.Pericia.VALUE_MIN,
                Math.min(periciaValueMax(), current + delta));
        return next != current;
    }

    /**
     * Valor a exibir: o otimista se houver, senao o do servidor.
     *
     * <p>28/09/2026: a chave e o <b>id</b> da pericia, e nao o nome. O nome e'
     * rotulo e o Mestre pode troca-lo no editor; com o nome na chave, um rename
     * traria a linha de volta com o valor antigo e o numero piscaria na tela.
     * Esta e' a unica leitura do otimista: {@link #stepPericiaValue},
     * {@link #canStepPericia} e {@link #applyStepButtons} passam por aqui.
     */
    private int displayPericiaValue(SheetData.Pericia pericia) {
        Integer pending = pendingPericiaValue.get(pericia.id());
        return pending != null ? pending : pericia.value();
    }

    /** Id do atributo a exibir: o otimista se houver, senao o do servidor. */
    private String displayPericiaAttributeId(SheetData.Pericia pericia) {
        String pending = pendingPericiaAttribute.get(pericia.id());
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

        // "-" e "+" com segurada (28/09/2026): segurar acelera. O canStep
        // responde pelo valor OTIMISTA (numericValue -> pendingNumeric) e pelo
        // piso/teto, e nao pelo `active` do botao -- que so e reescrito no
        // render, um frame atras, e deixaria a rajada estourar o limite.
        HoldStepButton minus = new HoldStepButton(minusX, y, arrow, h, Component.literal("-"),
                b -> stepNumeric(attributeId, -ARROW_STEP),
                () -> canStepAttribute(attributeId, -ARROW_STEP));
        HoldStepButton plus = new HoldStepButton(plusX, y, arrow, h, Component.literal("+"),
                b -> stepNumeric(attributeId, ARROW_STEP),
                () -> canStepAttribute(attributeId, ARROW_STEP));
        addRenderableWidget(minus);
        addRenderableWidget(plus);

        attrRows.add(new AttrRow(valueX, y, valueW, h, attributeId, minus, plus));
    }

    /**
     * A seta do atributo ainda anda? Mesmo piso/teto de {@link #applyStepButtons}
     * ({@link #attributeValueMin}/{@link #attributeValueMax}), sem enviar nada.
     *
     * <p><b>Por que o loop de segurada nao pode usar o {@code active} do
     * botao:</b> {@code applyStepButtons} escreve o {@code active} no render
     * (e depois de {@code super.render()}), entao o cinza do limite so chega no
     * frame seguinte. Confiar nele deixaria a rajada passar 1 ou 2 passos do
     * teto; o servidor segura, e o numero piscaria enquanto o valor otimista
     * estivesse a frente do eco ({@code keepPending}).
     *
     * <p>Por isso a resposta vem do valor <b>otimista</b>
     * ({@link #numericValue}, que le {@code pendingNumeric}) e do
     * {@code canEdit} — o mesmo par que {@code applyStepButtons} usa, so que
     * sem o atraso de um frame. Ficha somente-leitura nao envia nada.
     *
     * <p>A soma e a <b>mesma</b> de {@link #stepNumeric}
     * ({@link CharacterSheetScreen#saturatingAdd}), para os dois nunca
     * discordarem sobre overflow; o clamp do teto e o daqui, igual ao de
     * {@link #applyStepButtons}.
     */
    private boolean canStepAttribute(String attributeId, int delta) {
        if (!canEdit || sheet == null) {
            return false;
        }
        int current = numericValue(attributeId);
        int next = Math.max(attributeValueMin(),
                Math.min(attributeValueMax(), saturatingAdd(current, delta)));
        return next != current;
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
     * Linha do teto e da barra de um recurso: {@code Max [caixa] [barra larga]}.
     *
     * <p><b>29/09/2026:</b> a linha mudou de dentro para fora. Antes era
     * {@code [rotulo] [-] [barra] [+] Max [caixa]}, com a barra espremida entre
     * as duas setas e sobrando ~110px -- o numero do recurso era desenhado por
     * cima dela e ainda tinha que ser degradado para caber. Agora as setas
     * viraram os 6 botoes da linha de baixo ({@link #addResourceStepRow}) e o
     * que sobra da coluna esquerda e a barra, ate o fim de {@code leftW}, com 4px
     * a mais de altura ({@code rowH - 2}, o mesmo Y e a mesma altura da caixa do
     * teto, para o numero sair na altura do texto da caixa).
     *
     * <p><b>Por que "Max" e a caixa continuam no comeco da linha:</b> e o unico
     * caminho de editar o teto do recurso, entao eles sao o bloco fixo e a barra
     * e o que se adapta ao resto. O {@code createFieldBox} segue sendo o caminho
     * (nada de teto em botao), com {@link SheetData#MAX_RESOURCE} como teto do
     * filtro, para o valor digitado ser o que o servidor vai gravar em vez de um
     * 9999 silencioso.
     *
     * <p>Os parametros de cor que o metodo antigo recebia foram removidos: as
     * cores da barra nunca sairam daqui, e sim de {@link #renderContent}
     * ({@code COL_HP}/{@code COL_MANA} e o fundo de cada uma), porque a barra e
     * desenhada no render e nao no montage. Pelo mesmo motivo o recurso tambem
     * nao e parametro: quem sabe de qual barra se trata e quem a guarda
     * ({@link #hpBar} e {@link #manaBar}) e quem a desenha.
     *
     * @param maxField campo do teto, que continua editavel por texto
     * @return a geometria da barra (desenhada em render)
     */
    private Bar addResourceBlock(int x0, int leftW, int y, String maxField) {
        int maxLabelW = 30;
        // A caixa do teto e dimensionada pela COLUNA, e nao mais pelo `boxW` dos
        // campos de texto: o `boxW` nao existe mais nesta linha, e o teto do
        // recurso precisa caber "9999" com folga em qualquer largura de coluna.
        int maxBoxW = Math.max(40, Math.min(56, leftW / 5));
        int gap = 4;
        textLines.add(new TextLine("Max", x0, y + labelOffset(), COL_MUTED));
        fieldBoxes.put(maxField, createFieldBox(maxField, x0 + maxLabelW, y, maxBoxW,
                true, SheetData.MAX_RESOURCE));

        int barX = x0 + maxLabelW + maxBoxW + gap;
        int barW = Math.max(24, leftW - maxLabelW - maxBoxW - gap);
        return new Bar(barX, y, barW, rowH - RESOURCE_BAR_H_MARGIN);
    }

    /**
     * Linha dos 6 botoes de passo do recurso: {@code -10 -5 -1 +1 +5 +10}.
     *
     * <p>29/09/2026, decisao do usuario: {@code Button} COMUM, e nao
     * {@link HoldStepButton}. Vida e Mana nao tem aceleracao continua: o par
     * -1/+1 da o ajuste fino que a rajada fazia e -10/+10 cobrem o resto, sem
     * obrigar o jogador a segurar o botao. O que a rajada trazia de melhor --
     * parar sozinho no limite -- continua, porque quem escreve o {@code active} e
     * {@link #applyResourceStepButtons}, com o mesmo {@code canStepResource} do
     * clique.
     *
     * <p>Os 6 botoes tomam a largura toda da coluna esquerda, com larguras iguais
     * e o mesmo gap entre eles. A sobra da divisao inteira vai para o ultimo,
     * senao a linha terminaria alguns pixels antes do fim da coluna.
     *
     * @param field recurso que a linha mexe ("hp"/"mana")
     */
    private void addResourceStepRow(int x0, int y, int leftW, String field) {
        int h = rowH - 2;
        int n = RESOURCE_STEPS.length;
        int w = Math.max(1, (leftW - (n - 1) * RESOURCE_STEP_GAP) / n);
        int lastW = leftW - (n - 1) * (w + RESOURCE_STEP_GAP);
        int x = x0;
        for (int i = 0; i < n; i++) {
            int delta = RESOURCE_STEPS[i];
            Button b = Button.builder(
                            Component.literal(delta > 0 ? "+" + delta : Integer.toString(delta)),
                            btn -> stepNumeric(field, delta))
                    .bounds(x, y, i == n - 1 ? lastW : w, h)
                    .build();
            addRenderableWidget(b);
            resourceSteps.add(new ResourceStep(field, delta, b));
            x += w + RESOURCE_STEP_GAP;
        }
    }

    /**
     * Piso do HP e da Mana, e so deles (29/09/2026).
     *
     * <p>Sao as mesmas faixas que o servidor aplica em {@code SheetData.Vitals}:
     * o HP pode ser negativo ({@link SheetData#MAX_HP_FLOOR}) e pode passar do
     * maximo, e a Mana comeca em 0. Sao fixas, nao vem do modelo -- por isso vivem
     * aqui e nao em {@code SheetData}, que nao pode mudar.
     */
    @Override
    protected int numericFloor(String field) {
        if ("mana".equals(field)) {
            return 0;
        }
        return "hp".equals(field) ? SheetData.MAX_HP_FLOOR : super.numericFloor(field);
    }

    /** Teto do HP e da Mana; ver {@link #numericFloor}. */
    @Override
    protected int numericCeiling(String field) {
        return "hp".equals(field) || "mana".equals(field)
                ? SheetData.MAX_RESOURCE : super.numericCeiling(field);
    }

    /**
     * O botao de passo do recurso ainda anda?
     *
     * <p>Mesmo calculo do clamp de {@link CharacterSheetScreen#stepNumeric},
     * sem enviar nada, e pela MESMA razao de {@link #canStepAttribute}: a
     * resposta vem do valor otimista ({@link #numericValue}) e do piso/teto do
     * recurso, e nao do {@code active} do botao, que so e reescrito no render e
     * deixaria o clique passar do limite em um frame.
     *
     * <p>O par {@code canEdit} + {@code next != current} e o que da o cinza nos
     * dois sentidos: no piso, no teto e em ficha somente-leitura.
     */
    private boolean canStepResource(String field, int delta) {
        if (!canEdit || sheet == null) {
            return false;
        }
        int current = numericValue(field);
        int next = Math.max(numericFloor(field),
                Math.min(numericCeiling(field), saturatingAdd(current, delta)));
        return next != current;
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
        // Setas sem limite de valor (hoje so o botao de atributo da pericia):
        // so canEdit.
        for (Button arrow : arrowButtons) {
            arrow.active = canEdit;
        }
        // Os 6 botoes de vida/mana tem limite proprio (o do recurso), entao vao
        // pelo MESMO caminho do atributo e da pericia, e nao por canEdit puro.
        applyResourceStepButtons();
        // Atributo e pericia tem limite, entao vao pelo MESMO metodo do render.
        for (AttrRow row : attrRows) {
            applyStepButtons(row.minusButton(), row.plusButton(),
                    numericValue(row.attributeId()),
                    attributeValueMin(), attributeValueMax());
        }
        if (sheet != null) {
            List<SheetData.Pericia> pericias = sheet.pericias();
            int scroll = clampPerScroll(perScroll);
            for (int i = 0; i < periciaRows.size() && scroll + i < pericias.size(); i++) {
                applyStepButtons(periciaRows.get(i).minusButton(), periciaRows.get(i).plusButton(),
                        displayPericiaValue(pericias.get(scroll + i)),
                        SheetData.Pericia.VALUE_MIN, periciaValueMax());
            }
        }
    }

    /**
     * Escreve o {@code active} dos 6 botoes de passo de vida/mana.
     *
     * <p><b>Por que eles entram no mecanismo do limite (29/09/2026):</b> sem
     * isto, no piso e no teto do recurso a linha continuaria mostrando 12 botoes
     * brancos, e o jogador nao teria como saber que ja chegou no limite -- que e
     * o feedback que o usuario pediu para o atributo e a pericia. Como os botoes
     * guardam o campo e o delta ({@link ResourceStep}), a pergunta "este botao
     * ainda anda?" e a mesma do clique ({@link #canStepResource}), entao o cinza
     * e o clique nunca discordam.
     *
     * <p>Chamado nos DOIS lugares, como {@link #applyStepButtons}: no
     * {@code applyExtraState} (recriacao de widgets e eco do servidor) e no
     * {@code renderContent} (o valor otimista muda no clique, sem eco). Se
     * ficasse so no primeiro, o botao ficaria 1 frame atras do valor.
     */
    private void applyResourceStepButtons() {
        for (ResourceStep step : resourceSteps) {
            step.button().active = canStepResource(step.field(), step.delta());
        }
    }

    /**
     * O servidor respondeu: o valor autoritativo substitui o otimista do
     * dropdown de atributo, e o valor das pericias so quando o eco o alcança.
     *
     * <p>Fica em {@code onSheetReceived} e nao em {@code applyExtraState}
     * porque abrir o dropdown recria os widgets ({@code init()}) no mesmo
     * instante em que o novo valor foi escolhido — limpando aqui, o usuario
     * veria o valor antigo ate a ficha voltar.
     */
    @Override
    protected void onSheetReceived() {
        reconcilePericiaValues();
        pendingPericiaAttribute.clear();
    }

    /**
     * Regra do eco ({@code CharacterSheetScreen.keepPending}) aplicada ao valor
     * das pericias: sai o que o servidor alcançou e o que estiver fora de
     * [0, {@link #periciaValueMax}]. Uma pericia que saiu do modelo conta como
     * descartada, porque a ficha nova nao tem valor autoritativo para alcancar.
     */
    private void reconcilePericiaValues() {
        if (sheet == null) {
            pendingPericiaValue.clear();
            return;
        }
        pendingPericiaValue.entrySet().removeIf(entry -> {
            SheetData.Pericia pericia = sheet.periciaById(entry.getKey());
            return pericia == null || !keepPending(entry.getValue(), pericia.value(),
                    SheetData.Pericia.VALUE_MIN, periciaValueMax());
        });
    }

    /**
     * O Mestre trocou o modelo: o valor otimista das pericias e do dropdown e
     * do modelo ANTERIOR, entao sai inteiro (a regra do eco e para a rajada, que
     * aqui ja morreu com os widgets recriados).
     */
    @Override
    public void onModelChanged() {
        pendingPericiaValue.clear();
        pendingPericiaAttribute.clear();
        super.onModelChanged();
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

        // 29/09/2026: o cinza dos 6 botoes de cada recurso, pelo mesmo caminho do
        // applyExtraState (ver applyResourceStepButtons). Aqui ele pega o clique
        // no mesmo frame, sem esperar o eco do servidor.
        applyResourceStepButtons();

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

            // "+" desliga no teto e "-" no piso, que sao os do Mestre (28/09/2026,
            // ver attributeValueMax/attributeValueMin): active = false desenha
            // o botao cinza, que e o feedback pedido. O servidor tambem limita
            // (SheetData), entao isto e so a cara do limite, nao a sua unica
            // garantia. E o MESMO metodo que applyExtraState usa, para os dois
            // concordarem (ver applyStepButtons).
            applyStepButtons(row.minusButton(), row.plusButton(), value,
                    attributeValueMin(), attributeValueMax());
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

            // "+" desliga no teto (periciaValueMax) e "-" no piso (0), pelo mesmo
            // motivo dos atributos: o botao cinza diz "voce chegou no limite".
            // Pelo mesmo metodo do applyExtraState, para nao haver frame de
            // divergencia (ver applyStepButtons).
            applyStepButtons(row.minusButton(), row.plusButton(), value,
                    SheetData.Pericia.VALUE_MIN, periciaValueMax());

            // A sigla do atributo fica no botao e vem do MODELO (27/09/2026):
            // antes era o shortName() do enum, e o Mestre nao podia renomear.
            row.attrButton().setMessage(Component.literal(attrLabelFor(index)));
        }
    }

    /**
     * Scroll da COLUNA ESQUERDA e da coluna de pericias, cada uma no seu
     * territorio.
     *
     * <p>27/09/2026: o pedido foi "scroll so no painel de pericias", entao a
     * roda so rola quando o ponteiro esta em cima desta coluna. Fora dela, o
     * comportamento normal da tela continua valendo.
     *
     * <p>29/09/2026: a coluna esquerda ganhou o mesmo tratamento, porque o
     * bloco de vida em 3 linhas a fez estourar a janela baixa. A ordem dos dois
     * testes e o que mantem a preferencia antiga: o da coluna de pericias vem
     * primeiro, entao a esquerda so rola com o ponteiro do outro lado do painel.
     *
     * <p><b>Por que a coluna esquerda so rola se o offset muda:</b> quando ela ja
     * esta no fim (ou a ficha inteira cabe e {@code maxLeftScroll} e 0), a roda
     * nao e consumida e segue para o {@code super} -- como a coluna de pericias,
     * que tambem nao deixa a roda passar. Com o conteudo cabe na janela
     * ({@code maxLeftScroll == 0}) a coluna nem e testada, para nao roubar a
     * rolagem da tela.
     *
     * <p><b>Por que a convencao de sinal e a do {@code SkillsScreen}:</b>
     * {@code scrollY} negativo e rolar para BAIXO, e rolar para baixo tem que
     * AVANCAR o conteudo. A coluna de pericias acima usa a conta equivalente
     * ({@code -Math.signum(scrollY)} somado ao offset, que comeca em 0 e desce),
     * e as duas precisam concordar para a roda nao parecer invertida em uma e
     * normal na outra.
     *
     * <p>Rolar recria os widgets ({@code rebuildWidgets()} refaz o
     * {@code init()}) porque as linhas sao botoes e caixas de verdade, e nao texto
     * desenhado: e o unico jeito de mover o conjunto deles. Por isso a rolagem
     * da esquerda e bloqueada enquanto ha digitacao ({@link #isEditingField}).
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // Convencao do vanilla: deltaY NEGATIVO e rolar para BAIXO, e rolar para
        // baixo avanca o conteudo (o mesmo de SkillsScreen.mouseScrolled).
        int step = scrollY < 0 ? 1 : -1;
        // A coluna de pericias tem preferencia: e dela que o scroll veio antes.
        if (isOverPericiaColumn(mouseX, mouseY)) {
            int next = clampPerScroll(perScroll + (int) -Math.signum(scrollY));
            if (next != perScroll) {
                perScroll = next;
                rebuildWidgets();
            }
            return true;
        }
        if (maxLeftScroll() > 0 && isOverLeftPanel(mouseX, mouseY)) {
            int next = clampLeftScroll(leftScroll + step);
            if (next != leftScroll && !isEditingField()) {
                leftScroll = next;
                rebuildWidgets();
                return true;
            }
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
