package com.pedro.tabletoprpg.client;

import com.pedro.tabletoprpg.RpgNetworking;
import com.pedro.tabletoprpg.SheetData;
import com.pedro.tabletoprpg.SheetModel;
import com.pedro.tabletoprpg.SheetModelHolder;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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

    // ------------------------------------------------------------------
    // ABAS DA FICHA (decisao do usuario em 30/09/2026)
    // ------------------------------------------------------------------

    /**
     * Altura reservada no rodape do conteudo para a barra de abas.
     *
     * <p>E uma altura <b>declarada</b>, e nao medida do botao, porque o
     * {@code bottomY} que a aba 1 recebe ja vem lessenado em um valor: e assim que
     * a ultima linha da coluna esquerda para de invadir a faixa das setinhas, sem
     * depender de onde o botao foi desenhado. A barra fica logo acima do
     * {@code contentBottom}, e o {@code Back} da base fica ainda mais abaixo (os
     * dois nao se tocam).
     */
    private static final int TAB_BAR_H = 20;

    /**
     * Largura de cada setinha da barra de abas.
     *
     * <p>Um botao estreito porque o par e so navegacao: a aba 1 e a ficha inteira
     * e nao sobrou largura para 3 botoes de verdade, e o nome da aba ativa no
     * meio e o que diz onde o jogador esta.
     */
    private static final int TAB_ARROW_W = 20;

    /**
     * Quantas linhas a aba 2 reserva ao calcular a altura de linha
     * ({@code fitRowHeight}).
     *
     * <p>Sao 2 titulos + 2 caixas; a altura das caixas nao entra na conta porque
     * elas ocupam o que sobrar do painel ({@link #buildInfoTab}). Serve so para
     * a altura de linha do titulo nao ficar maior do que o padrao da tela.
     */
    private static final int INFO_TAB_ROWS = 4;

    /**
     * Nome curto de cada aba, na ordem em que as setinhas percorrem.
     *
     * <p>Curto e em ingles por causa do resto do rodape ({@code Back}) e dos
     * titulos das secoes: o nome cabe no meio da barra sem invadir as setinhas, e
     * e o que o jogador le para saber em que pagina esta.
     */
    private static final String[] TAB_NAMES = {"Character", "Info/Inventory", "Skills/Spells"};

    /**
     * Aba visivel (0 = ficha, 1 = Info/Inventory, 2 = Skills).
     *
     * <p>30/09/2026: a aba 1 recebeu a coluna esquerda com os dois quadros de
     * texto livre (FASE 2A) e a aba 2 continua VAZIA -- o inventario e a fase
     * seguinte (2B), que vai reusar o que a 2A montou. A estrutura ja e a
     * definitiva, porque trocar de aba e o que vai mudar depois, nao o esqueleto.
     */
    private int activeTab;

    /**
     * Os dois quadros de texto com rolagem da aba 2, por nome de campo
     * (30/09/2026, FASE 2A).
     *
     * <p><b>Por que nao entram no {@code fieldBoxes} da base:</b> aquele mapa e
     * {@code Map<String, EditBox>} e e percorrido pelo
     * {@code CharacterSheetScreen.applySheetToWidgets}, que preenche a caixa com
     * {@code getText}/{@code getNumeric} e cria o responder que envia a cada
     * tecla. O {@code MultiLineEditBox} nao e um {@code EditBox} e nao tem
     * {@code setResponder}, entao o guardado aqui e o que permite ter os dois
     * lados: o preenchimento pelo servidor e o envio sob demanda.
     */
    private final Map<String, MultiLineEditBox> multiLineBoxes = new LinkedHashMap<>();

    /**
     * Campos cujo texto grande mudou e ainda nao foi enviado (30/09/2026).
     *
     * <p><b>Por que guardar e nao enviar a cada tecla (decisao do usuario):</b>
     * o limite e de {@link SheetData#MAX_TEXT} (2000) caracteres, e cada tecla
     * dispararia um pacote de ate 2 KB. O jogador soletra um paragrafo e saem
     * 800 pacotes -- o caminho de saida do texto e o de salvar: fim do campo,
     * troca de aba e fechamento da tela.
     */
    private final Set<String> pendingMultiLine = new LinkedHashSet<>();

    /**
     * Guarda do eco do servidor nas caixas grandes (30/09/2026).
     *
     * <p>O {@code setValue} do {@code MultiLineEditBox} chama o
     * {@code setValueListener} mesmo com o valor vindo de codigo (confirmado
     * no bytecode do 1.21.11: {@code setValue} sempre passa por
     * {@code onValueChange}). Sem esta guarda, preencher a caixa com o que o
     * servidor mandou marcaria o campo como pendente e reenviaria o eco para o
     * servidor, em vao.
     */
    private boolean suppressMultiLine;

    // ------------------------------------------------------------------
    // INVENTARIO DA COLUNA DIREITA DA ABA 1 (30/09/2026, FASE 2B)
    // ------------------------------------------------------------------

    /**
     * Alturas de uma linha de item (FASE 2B).
     *
     * <p><b>Por que altura fixa e nao {@code rowH}:</b> o bloco de um item tem
     * nome, tipo, ate 2 linhas de descricao e a linha dos botoes, e isso nao cabe
     * na altura de linha da tela (que no painel de referencia e 13px). Um bloco
     * por item mais alto que a linha e o que permite que a coluna mostre item
     * inteiro em vez de cortar a descricao; o preco e que sao poucos por vez, e e
     * o que a rolagem da coluna existe para resolver.
     */
    private static final int INV_NAME_H = 11;
    private static final int INV_DESC_ADV = 11;
    private static final int INV_DESC_LINES = 2;
    private static final int INV_BTN_H = 12;
    private static final int INV_ITEM_GAP = 4;

    /**
     * Folga entre a borda da caixa do item e o conteudo, em cima e embaixo
     * (pedido do usuario em 30/09/2026: as linhas estavam coladas na caixa).
     */
    private static final int INV_PAD = 5;

    /**
     * A mesma folga nas laterais.
     *
     * <p>Antes o texto comecava exatamente no {@code x} da caixa, entao a
     * primeira letra encostava na borda.
     */
    private static final int INV_PAD_X = 4;

    /**
     * Largura util do texto dentro da caixa: a coluna menos as duas folgas.
     *
     * <p><b>Por que e um metodo e nao uma constante:</b> a coluna tem largura
     * variavel (a tela pode ser estreita), e {@link #rightDescLines} e
     * {@link #addInventoryItem} precisam medir com o MESMO numero -- se um usar
     * a largura cheia e o outro a largura com folga, a contagem de linhas
     * discorda e {@code parts.get(i)} estoura.
     */
    private int rightTextW(int w) {
        return Math.max(8, w - INV_PAD_X * 2);
    }

    /**
     * A linha cabe inteira na faixa visivel da lista de itens?
     *
     * <p>Toda linha e todo botao do item passam por aqui. Um item cortado pela
     * borda mostra so o pedaco que cabe -- e um botao cortado **nao** e criado,
     * senao daria para clicar num item que o jogador nao ve.
     */
    private boolean lineInList(int y, int h) {
        return y >= rightListTop && y + h <= rightListBottom;
    }

    /**
     * Fundo da "caixinha" de cada item (pedido do usuario em 30/09/2026).
     *
     * <p>Mais escuro que {@code COL_PANEL_BG} ({@code 0xF216161C}) em 6/6/8
     * por canal: o suficiente para o item se destacar como bloco, sem virar
     * outra cor de fundo.
     */
    private static final int COL_ITEM_BG = 0xF2101016;

    /**
     * Barra de rolagem da lista (pedido do usuario em 30/09/2026: a rolagem
     * funcionava, mas nao havia nada mostrando que havia mais embaixo).
     *
     * <p><b>Mesmos numeros e cores do {@code SkillsScreen}:</b> duas telas com
     * listas ought a ter a mesma barra, e quem conhece uma reconhece a outra.
     *
     * <p>A largura e 4 porque a barra fica **na folga interna** da caixa
     * ({@link #INV_PAD_X}), e uma barra mais larga passaria por cima do texto do
     * peso, que e encostado na direita.
     */
    private static final int BAR_W = 4;
    private static final int BAR_PAD = 2;

    /** Folga entre a borda direita da caixa do item e a barra de rolagem. */
    private static final int BAR_GAP = 3;
    private static final int COL_SCROLL_TRACK = 0xFF303038;
    private static final int COL_SCROLL_THUMB = 0xFFE8E8EE;

    /** Largura dos botoes Edit/Del: o nome do botao com folga. */
    private static final int INV_BTN_W = 32;

    /** Geometria da coluna direita da aba 1 (a do inventario). */
    private int rightPanelX;
    private int rightPanelW;

    /** Faixa da lista de itens, e a mesma do clamp da rolagem. */
    private int rightListTop;
    private int rightListBottom;

    /** Altura do conteudo inteiro da lista, medida no {@code buildInfoTab}. */
    private int rightContentH;

    /**
     * Scroll da lista de itens, em pixels.
     *
     * <p><b>Em pixels e nao em linhas porque os blocos nao tem a mesma
     * altura</b>: um item com descricao de 2 linhas e 9px mais alto que um sem
     * descricao. Rolar por linhas socia as linhas de nome e de botao do bloco
     * vizinho.
     */
    private int rightScroll;
    /** Geometria da barra, montada em {@code addInventoryColumn}. */
    private int rightBarX;
    private int rightBarH;
    /**
     * Largura da caixa do item: a coluna MENOS a barra e a folga entre as duas.
     *
     * <p><b>Por que a caixa e menor que a coluna (pedido do usuario em
     * 30/09/2026):</b> com a caixa na largura toda, a barra de rolagem ficava
     * colada na borda dela. {@code rightDescLines} mede com este mesmo numero,
     * senao a contagem de linhas discorda da quebra.
     */
    private int rightBoxW;
    private int rightThumbY;
    private int rightThumbH;
    private boolean draggingRightBar;

    /**
     * Indice do item que espera o segundo clique no Del, ou {@code -1}.
     *
     * <p>O Del e irreversivel, e o item nao tem como voltar depois de apagado. O
     * primeiro clique so marca e redesenha o botao pedindo confirmacao; o segundo
     * clique <b>no mesmo indice</b> apaga. Qualquer outra coisa (trocar de aba,
     * rolar, abrir o form, chegar estado novo do servidor) zera a marcacao, para
     * que o segundo clique nao possa cair em um item diferente do que o jogador
     * viu marcado.
     */
    private int delPendingIndex = -1;

    // ------------------------------------------------------------------
    // ABA 3: SKILLS / MAGIAS (01/10/2026)
    // ------------------------------------------------------------------

    /**
     * Uma coluna com lista e rolagem propria, da aba 3.
     *
     * <p><b>Por que uma classe e nao mais um par de campos soltos:</b> a aba 3
     * tem <b>duas</b> listas (skills e magias), e os campos {@code right*} da
     * coluna do inventario sao atrelados a aba 2 por guarda ({@code activeTab != 1})
     * em quatro lugares. Espelhar os campos daria oito campos e um segundo jogo de
     * barras quase igual, e o botao de arrasto do mouse ({@link #draggingRightBar})
     * so tem um. Esta classe deixa as duas listas independentes sem mexer em nada
     * da aba do inventario.
     */
    private static final class Column {
        private int panelX;
        private int panelW;
        private int listTop;
        private int listBottom;
        private int contentH;
        private int scroll;
        private int barX;
        private int barH;
        private int boxW;
        private int thumbY;
        private int thumbH;
        private boolean draggingBar;
        /**
         * Altura do botao de nome desta coluna.
         *
         * <p>As duas colunas da aba 3 nao usam a mesma: a magia tem nome mais longo
         * e o botao mais alto (01/10/2026). A coluna guarda o valor para que
         * {@code addEntryNameButton} nao precise saber de qual lista esta falando.
         */
        private int nameBtnH = LIST_BTN_H;
        /** Indice (guardado) que espera o segundo clique no Del, ou -1. */
        private int delPending = -1;
        /** Retangulo do Del marcado, para a moldura vermelha. */
        private ItemBox delPendingBox;

        private int maxScroll() {
            return Math.max(0, contentH - Math.max(0, listBottom - listTop));
        }

        private int clampScroll(int value) {
            return Math.max(0, Math.min(value, maxScroll()));
        }

        /**
         * O cursor esta sobre a faixa da lista?
         *
         * <p><b>01/10/2026 -- o paragrafo antigo ("sem o cabecalho") foi
         * removido porque virou falso:</b> o cabecalho da coluna de magias
         * (filtro, atributo, CD e "+ Magia") passou a ser parte da faixa
         * rolavel, entao o cursor sobre ele TEM que rolar. E o que o jogador
         * pediu -- ele estava sobre o "+ Magia" e a roda nao fazia nada. O
         * titulo da secao, que continua fixo, esta ACIMA de {@code listTop} e
         * por isso segue de fora.
         */
        private boolean isOverList(double mouseX, double mouseY) {
            return mouseX >= panelX && mouseX < panelX + panelW
                    && mouseY >= listTop && mouseY < listBottom;
        }

        /**
         * Uma linha cabe INTEIRA na faixa visivel da lista?
         *
         * <p><b>Por que este guarda existe e o {@code ItemBox} nao resolve
         * (bug do usuario em 01/10/2026):</b> o fundo do item e um retangulo
         * desenhado por esta tela, e por isso o {@code addSkillEntry} o recorta na
         * borda. Mas um <b>widget</b> -- botao, caixa -- e desenhado pelo vanilla
         * na posicao Y que recebeu, sem nenhuma ideia da faixa da lista: um botao
         * cortado pela borda de cima aparecia por baixo do "+ Skill", e um cortado
         * pela de baixo invadia a barra de abas. O fundo era recortado e o botao
         * nao, e o jogador via o botao vazando.
         *
         * <p>E o mesmo {@code lineInList} que a coluna de inventario ja usa.
         */
        private boolean lineFits(int y, int h) {
            return y >= listTop && y + h <= listBottom;
        }

        /**
         * Recorta um widget na faixa da lista, devolvendo ate onde ele pode ir.
         *
         * <p><b>Por que recortar em vez de criar inteiro ou nao criar (01/10/2026):</b>
         * o jogador pediu que a linha nao sumisse de repente na rolagem. Criar o
         * widget inteiro transborda a borda; nao criar nada faz a linha
         * desaparecer de uma vez. O meio termo e CREAR O PEDACO: o widget nasce
         * com a altura que sobra dentro da faixa, entao ele nunca invade o
         * "+ Skill" nem a barra de abas, e mesmo assim da para ve-lo entrando e
         * saindo.
         *
         * <p><b>Por que o texto do botao nao e redesenhado mais para baixo:</b> o
         * vanilla centraliza o texto na altura do botao, entao um botao de 5 px
         * mostra o texto cortado. E aceitavel e ate desejado -- e o pedaco que o
         * jogador pediu.
         *
         * @return a altura usavel, ou 0 se nao ha nem {@link #LIST_PEEK_MIN}
         */
        private int clippedHeight(int y, int h) {
            int visible = Math.min(y + h, listBottom) - Math.max(y, listTop);
            return visible >= LIST_PEEK_MIN ? Math.min(h, visible) : 0;
        }

        private boolean onScrollbar(double mouseX, double mouseY) {
            return barH > 0 && maxScroll() > 0
                    && mouseX >= barX - BAR_PAD && mouseX < barX + BAR_W + BAR_PAD
                    && mouseY >= listTop && mouseY < listTop + barH;
        }

        private void scrollFromMouse(double mouseY) {
            int max = maxScroll();
            if (max <= 0) {
                scroll = 0;
                return;
            }
            int useful = Math.max(1, barH - thumbH);
            int delta = (int) (mouseY - listTop - thumbH / 2);
            scroll = clampScroll(delta * max / useful);
        }

        /**
         * Aplica a geometria da faixa da lista.
         *
         * <p><b>Por que e separado do {@link #layout}:</b> o recorte das linhas
         * (o guarda em {@code addSkillEntry}) precisa saber {@link #listTop} e
         * {@link #listBottom} <b>antes</b> de montar a primeira linha. Se a
         * geometria so fosse aplicada no fim, o guarda leria a geometria da
         * montagem ANTERIOR -- e, na primeira montagem, zeros. Era o que fazia a
         * skill de cima entrar por baixo do "+ Skill" (relato do usuario em
         * 01/10/2026).
         */
        private void prepare(int x, int w, int top, int bottom) {
            panelX = x;
            panelW = w;
            listTop = Math.max(top, 0);
            listBottom = Math.max(listTop, bottom);
        }

        /** Calcula a geometria da barra; quem chama ja mediu o conteudo. */
        private void layout(int x, int w, int top, int bottom) {
            prepare(x, w, top, bottom);
            barX = x + w - BAR_W;
            barH = Math.max(0, listBottom - listTop);
            scroll = clampScroll(scroll);
            thumbH = barH <= 0 ? 0 : Math.max(8, barH * barH / Math.max(1, contentH));
            if (thumbH > barH) {
                thumbH = barH;
            }
            int desloca = barH - thumbH;
            thumbY = listTop + (maxScroll() == 0 ? 0 : desloca * scroll / maxScroll());
        }

        /** Trilho e polegar. Geometria pronta, aqui so o desenho. */
        private void renderBar(GuiGraphics graphics) {
            if (barH <= 0 || maxScroll() <= 0) {
                return;
            }
            graphics.fill(barX, listTop, barX + BAR_W, listTop + barH, COL_SCROLL_TRACK);
            graphics.fill(barX, thumbY, barX + BAR_W, thumbY + thumbH, COL_SCROLL_THUMB);
        }
    }

    /** A coluna de skills, metade esquerda da aba 3. */
    private final Column skillColumn = new Column();

    /** A coluna de magias, metade direita da aba 3. */
    private final Column spellColumn = new Column();

    /**
     * Filtro de circulo das magias: {@code 0} = Todas, {@code 1..5} = um circulo.
     *
     * <p><b>E estado de TELA, e nao da ficha:</b> o ciclo Todas -&gt; 1o -&gt; ... -&gt;
     * 5o -&gt; Todas foi pedido pelo usuario, e o que a ficha guarda sao as
     * magias, nao a ordem em que cada tela as mostra. Por isso ele nao entra em
     * codec, nem em payload, nem no NBT.
     */
    private int spellFilter = SheetData.Spellbook.FILTER_ALL;

    /** A caixa da CD das magias. */
    private EditBox cdBox;

    /** Texto da CD ficou marcado por ainda nao parsear ("12" e, "12." nao e). */
    private boolean pendingCd;

    /** O {@code applyCdBox} esta escrevendo: o eco nao pode gerar novo envio. */
    private boolean suppressCd;

    /** Indice da linha do "Modificador" e da CD no {@code textLines}, ou -1. */
    private int spellHeaderIndex = -1;

    /**
     * Altura do botao de nome (e do Del) de uma linha da aba 3.
     *
     * <p>E o mesmo {@link #INV_BTN_H} do inventario, nao um numero novo: a linha
     * de skill tem o mesmo formato do item (caixinha, nome, acoes), e usar a mesma
     * altura faz a coluna nova ter o mesmo respiro das outras.
     */
    private static final int LIST_BTN_H = INV_BTN_H;

    /**
     * Altura do botao de nome de uma <b>magia</b>, maior que o da skill.
     *
     * <p><b>Por que so a magia (pedido de 01/10/2026):</b> "um pouco mais grosso".
     * O botao de nome e o acesso ao detalhe, e o nome de magia e o texto mais longo
     * das duas colunas ("Chama de7012o Infernal"), entao o botao mais alto ajuda o
     * jogador a mirar nele sem que a coluna inteira Precise crescer.
     */
    private static final int LIST_NAME_BTN_H = INV_BTN_H + 4;

    /**
     * Altura da linha do circulo da magia, onde o Del mora a direita.
     *
     * <p>E a altura do botao do Del mais um respiro: se fosse menor que o botao,
     * o Del invadiria a linha da acao; se fosse maior, sobraria um vao morto entre
     * o circulo e a acao.
     */
    private static final int LIST_CIRCLE_ADV = LIST_BTN_H + 2;

    /** Avanco de uma linha de descricao de skill, igual ao do item. */
    private static final int LIST_DESC_ADV = INV_DESC_ADV;

    /** Teto de linhas de descricao de skill (o item usa o mesmo). */
    private static final int LIST_DESC_LINES = INV_DESC_LINES;

    /** Folga entre duas entradas da lista. */
    private static final int LIST_ROW_GAP = INV_ITEM_GAP;

    /**
     * Pedao minimo de botao que ainda vale a pena criar na borda da rolagem.
     *
     * <p><b>Por que 5 px e nao 0 (pedido do usuario em 01/10/2026):</b> ele pediu
     * que a skill e a magia nao sumissem de repente ao rolar, e sim que desse para
     * ver um pedaco antes de sumir. Criar o botao inteiro (mesmo com 1 px) resolve,
     * mas um widget de 1 px e indistinguivel de nada e ainda rouba o clique do
     * "+ Skill". Com 5 px ja da para ver que a linha esta saindo.
     *
     * <p><b>Por que o fundo continua ate 1 px:</b> o {@code ItemBox} e desenhado
     * por esta tela, entao ele e recortado de verdade e pode mostrar 1 px. E ele
     * que faz a transicao parecer continua -- o retangulo do card encolhe dentro
     * da faixa, e o nome aparece e some com ele.
     */
    private static final int LIST_PEEK_MIN = 5;

    /** Largura do botao Edit/Del, igual a do item. */
    private static final int LIST_BTN_W = INV_BTN_W;

    /**
     * Largura do botao Del, igual a do Edit e a do item.
     *
     * <p><b>Por que um nome proprio se o numero e o mesmo de
     * {@link #LIST_BTN_W}:</b> em 01/10/2026 a ALTURA do Del deixou de ser a do
     * botao de nome ({@link #LIST_DEL_BTN_H}) e a largura continuou a mesma.
     * Nomear as duas dimensoes separadamente e o que avisa o proximo: se um dia
     * o Del ficar largo demais para a linha do custo, mexe aqui e nao no
     * {@code LIST_BTN_W} do item da aba 1.
     */
    private static final int LIST_DEL_BTN_W = LIST_BTN_W;

    /**
     * Altura do botao Del: a mesma do botao de nome, para skill e magia.
     *
     * <p><b>Por que 01/10/2026 (pedido do usuario: "o botao do nome da skill
     * pode ser mais grossinho igual ao da magia, assim como o delete"):</b> o Del
     * e o botao de um texto curto ("Del"/"Del?") numa linha de descricao, e ele
     * herdava a altura antiga da linha (12 px). Com o nome da skill agora do
     * mesmo tamanho do nome da magia (16 px), deixar o Del com 12 px faria o
     * card parecer ter duas espessuras diferentes.
     */
    private static final int LIST_DEL_BTN_H = LIST_NAME_BTN_H;

    /**
     * Avanco da 1a linha de texto do card: a mesma nos dois.
     *
     * <p><b>Por que o mesmo {@link #LIST_CIRCLE_ADV} da magia:</b> e a linha que
     * abre o corpo do card logo abaixo do nome. Nos dois colunados ela e a 1a
     * linha depois do botao de nome, entao ela avanca pelo mesmo numero -- se
     * divergisse, a linha do Del (que e a 3a) cairia em um Y diferente em skill
     * e em magia.
     */
    private static final int LIST_LINE1_ADV = LIST_CIRCLE_ADV;

    /**
     * Avanco da 2a linha de texto do card: a mesma nos dois.
     *
     * <p>E {@link #LIST_DESC_ADV} porque e uma linha de texto comum (a 2a linha
     * da descricao da skill, a execucao da magia): o corpo do card tem 11 px de
     * passo, e quem concorda com isso e o {@code lineFits} que recorta o texto
     * na borda da rolagem.
     */
    private static final int LIST_LINE2_ADV = LIST_DESC_ADV;

    /**
     * Avanco da 3a linha, que e a linha do Del.
     *
     * <p><b>Por que e {@link #LIST_DEL_BTN_H} e nao {@link #LIST_LINE2_ADV}:</b>
     * o Del tem 16 px e a linha 11 px. Num card com 11 px de avanco, um Del de
     * 16 px invadiria a folga do card ({@code LIST_ROW_GAP}, 4 px) e comecaria a
     * encostar na linha seguinte -- e o card seguinte do card. Como o card e de
     * altura FIXA (ver {@link #LIST_ENTRY_H}), a soma das tres linhas e a altura:
     * trocar este numero mudaria a conta toda.
     */
    private static final int LIST_LINE3_ADV = LIST_DEL_BTN_H;

    /**
     * Altura FIXA do card, igual em skill e em magia:
     * {@code 10 + 16 + 14 + 11 + 16 + 4 = 71} px.
     *
     * <p><b>Por que FIXA e nao "o que o texto ocupa" (pedido do usuario em
     * 01/10/2026: "o modelo do botao de magia vai ter espaco pras 3 linhas, e a
     * skill vai ficar do mesmo tamanho agora"):</b> antes a altura era medida
     * pelo conteudo, e por isso cada card era um retalho diferente -- skill sem
     * descricao com 21 px, magia sem execucao nem custo com 45 px -- e o olho
     * nao achava um embaixo do outro na mesma coluna. Fixando, (a) a coluna fica
     * regular, (b) o Del ganha a 3a linha reservada em vez de dividi-la com
     * texto, como o jogador pediu, e (c) a rolagem e uma conta de linhas
     * iguais, o que faz {@code skillEntryHeight} e {@code spellEntryHeight}
     * concordarem por construcao.
     *
     * <p>A conta: {@code INV_PAD * 2} (respiro em cima e embaixo) +
     * {@link #LIST_NAME_BTN_H} (botao de nome) + {@link #LIST_LINE1_ADV} +
     * {@link #LIST_LINE2_ADV} + {@link #LIST_LINE3_ADV} (a linha do Del) +
     * {@link #LIST_ROW_GAP} (folga entre um card e o seguinte).
     */
    private static final int LIST_ENTRY_H = INV_PAD * 2 + LIST_NAME_BTN_H
            + LIST_LINE1_ADV + LIST_LINE2_ADV + LIST_LINE3_ADV + LIST_ROW_GAP;

    /**
     * Largura de um botao de mover. Estreito de proposito.
     *
     * <p><b>Por que 12 px e nao a largura do nome:</b> sao dois botoes que
     * existem para mudar UM passo na lista, e a seta e um glifo de 5 px. Uma
     * largura cheia de 32 px tiraria do nome da skill mais da metade da linha, e
     * o nome e o acesso ao detalhe (e o texto mais comprido da coluna). 12 px
     * segura o glifo com folga e deixa o nome com o que sobra.
     */
    private static final int LIST_MOVE_W = 12;

    /**
     * Folga entre ▲ e ▼, e entre o par e o nome.
     *
     * <p><b>Por que uma folga so, e nao duas:</b> o par de setas e um unico
     * controle (subir/descer) e precisa parecer junto, enquanto a folga ate o
     * nome e o que impede o jogador de ler o nome como se fosse o rotulo do
     * botao. A faixa total reservada a esquerda e
     * {@code LIST_MOVE_W * 2 + LIST_MOVE_GAP * 2} = 28 px.
     */
    private static final int LIST_MOVE_GAP = 2;

    /**
     * Retangulo do botao Del marcado, para a moldura vermelha.
     *
     * <p>Guardado em vez de recalcular no render porque o botao so existe
     * quando o bloco dele coube na janela da lista: um item rolado para fora nao
     * tem widget, e nao tem o que contornar.
     */
    private ItemBox delPendingBox;

    /**
     * As caixinhas dos itens visiveis, redesenhadas a cada frame no
     * {@link #render} (e nao no {@code textLines}, que e so texto).
     *
     * <p>Enche em {@code buildInfoTab} e zera em {@code clearTransientState};
     * por isso so tem o que esta visivel na rolagem atual.
     */
    private final List<ItemBox> itemBoxes = new ArrayList<>();

    /**
     * A <b>caixa do limite de peso</b>, gerida por esta tela (FASE 2B).
     *
     * <p><b>Por que ela nao entra no {@code fieldBoxes} da base:</b> aquele mapa
     * alimenta um {@code int} ({@code getNumeric}) e cria um responder que envia
     * a cada tecla. O limite de peso e um {@code float} com 2 casas e o eco do
     * servidor volta formatado ("12.00"), entao reescrever a caixa a cada tecla
     * brigaria com o cursor e o filtro de digito impediria o texto do servidor.
     * Aqui o preenchimento e o envio sao separados, como nos quadros grandes.
     */
    private EditBox maxWeightBox;

    /** Guarda do eco do servidor na caixa do limite de peso. */
    private boolean suppressMaxWeight;

    /**
     * O limite de peso foi digitado em texto que ainda nao e numero e nao foi
     * enviado (FASE 2B).
     *
     * <p>E um valor so, e nao um conjunto como o {@link #pendingMultiLine}: a
     * coluna tem uma unica caixa de peso, entao a marca mais recente e a unica que
     * importa.
     */
    private boolean pendingMaxWeight;

    /** Indice da linha do resumo do peso dentro de {@code textLines}, ou {@code -1}. */
    private int weightSummaryIndex = -1;

    /**
     * O inventario do <b>ultimo estado desenhado</b> (FASE 2B).
     *
     * <p>E o que decide se a lista precisa ser remontada: o eco do servidor muda
     * {@code sheet}, e sem esta comparacao os widgets ficariam mostrando a lista
     * antiga -- o item apagado continuaria la e o novo nunca apareceria, ate o
     * jogador trocar de aba.
     */
    private SheetData.Inventory lastInventory;

    /**
     * O inventario mudou e a lista ainda nao foi remontada (FASE 2B).
     *
     * <p>O conserto e um {@code rebuildWidgets()}, que e exatamente o que se
     * precisa de uma lista de widgets. Ele <b>nao</b> roda dentro de
     * {@code onSheetReceived}: recriar os widgets ali destrói a caixa que o
     * jogador pode estar digitando e o texto que ele nao enviou ainda. O pedido
     * fica marcado e e cumprido no {@code renderContent}, quando nada da coluna
     * esta com o foco.
     */
    private boolean inventoryRebuildPending;

    /**
     * As skills ou magias mudaram e a lista ainda nao foi remontada (01/10/2026).
     *
     * <p>Mesma razao do {@link #inventoryRebuildPending}: so o
     * {@code rebuildWidgets} troca widgets, e o pedido sai no
     * {@link #renderContent} quando nada da aba esta com o foco -- na aba 3 o foco
     * que importa e o da caixa da CD.
     */
    private boolean spellbookRebuildPending;

    /** As skills do ultimo eco, para saber se a lista mudou ({@code equals}). */
    private List<SheetData.Skill> lastSkills;

    /** O grimorio do ultimo eco, para saber se a lista mudou ({@code equals}). */
    private SheetData.Spellbook lastSpellbook;

    /** Retangulo de um botao da coluna do inventario. */
    private record ItemBox(int x, int y, int w, int h) {
    }

    /**
     * Scroll da coluna esquerda guardado por aba, e o mesmo da coluna de pericias.
     *
     * <p>30/09/2026: {@link #leftScroll} e {@link #perScroll} sao o valor <b>atual</b>,
     * o que o {@code buildPanel} consome; o array e a memoria de cada aba. Sem
     * ela, voltar para a aba 1 com a coluna rolada voltaria ao topo, e o
     * jogador perderia a posicao a cada viagem de ida e volta entre paginas. A
     * troca de aba salva o valor antigo no indice da aba antiga e carrega o novo
     * ANTES do {@code rebuildWidgets()}, que refaz o layout.
     */
    private final int[] tabLeftScroll = new int[TAB_NAMES.length];
    private final int[] tabPerScroll = new int[TAB_NAMES.length];

    /** Quantas abas existem: o numero vive no {@link #TAB_NAMES}. */
    private int tabCount() {
        return TAB_NAMES.length;
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

    /**
     * Escolhe o que a aba visivel monta (30/09/2026).
     *
     * <p>A barra de abas entra nos <b>3</b> casos, inclusive nas abas vazias: sem
     * ela nao haveria como sair delas. A aba 1 e a 2 recebem o {@code bottomY}
     * lessenado em {@link #TAB_BAR_H} para o conteudo parar antes das setinhas.
     * A aba 3 (Skills) e a que fica dentro da aba 1 apos a coluna do inventario
     * (FASE 2B).
     *
     * <p><b>Por que o estado do inventario e zerado AQUI:</b> e o unico lugar por
     * onde toda montagem passa, e o indice da linha do resumo so vale para a lista
     * de {@code textLines} da aba que o criou. Sem o zerar, o indice da aba 1
     * continuaria valendo na aba 0 e o {@code applyWeightSummary} reescreveria uma
     * linha qualquer daquela aba (o indice e um numero pequeno, e quase sempre
     * cai dentro da lista nova).
     */
    @Override
    protected void buildPanel(int x0, int panelW, int topY, int bottomY) {
        maxWeightBox = null;
        weightSummaryIndex = -1;
        delPendingBox = null;
        itemBoxes.clear();
        spellHeaderIndex = -1;
        cdBox = null;
        if (activeTab == 0) {
            buildCharacterTab(x0, panelW, topY, bottomY - TAB_BAR_H);
        } else if (activeTab == 1) {
            buildInfoTab(x0, panelW, topY, bottomY - TAB_BAR_H);
        } else {
            buildSkillsTab(x0, panelW, topY, bottomY - TAB_BAR_H);
        }
        addTabBar(x0, panelW, bottomY);
    }

    /**
     * Aba 3: <b>metade skills, metade magias</b> (01/10/2026).
     *
     * <p><b>Por que duas colunas e nao abas dentro da aba:</b> o pedido foi
     * metade e metade, lado a lado. A conta de largura e a MESMA da aba 1
     * ({@code perW} com {@link #PER_W_RATIO} e {@link #PER_W_MAX}), para que a
     * coluna direita nasca na mesma posicao em todas as abas e o jogador veja a
     * coluna se mexer quando troca de aba.
     *
     * <p><b>A coluna de magias tem tres cabecalhos:</b> o botao de
     * filtro de circulo, a linha do atributo de conjuracao (botao + Modificador)
     * e a CD. O filtro e o atributo pediram "no topo da parte da direita", e a CD
     * fica na mesma linha do Modificador porque sao os dois numeros que a
     * conjuracao usa. <b>01/10/2026:</b> os tres (mais o "+ Magia") desceram
     * para dentro da faixa rolavel, e so o titulo da secao ficou fixo -- ver o
     * Javadoc do {@code addSpellColumn} para o por.
     *
     * <p><b>Por que o filtro e um botao que cicla e nao um dropdown:</b> o pedido
     * foi exatamente "Todas -&gt; 1o -&gt; ... -&gt; 5o -&gt; Todas", e um dropdown
     * exigiria uma tela a mais para um ciclo de seis estados.
     */
    private void buildSkillsTab(int x0, int panelW, int topY, int bottomY) {
        arrowButtons.clear();
        attrRows.clear();
        periciaRows.clear();
        resourceSteps.clear();
        hpBar = null;
        manaBar = null;
        multiLineBoxes.clear();
        rowH = fitRowHeight(INFO_TAB_ROWS, topY, bottomY);

        int innerX = x0 + PANEL_PAD;
        int innerW = Math.max(120, panelW - 2 * PANEL_PAD);
        int innerTop = topY + PANEL_PAD;
        int innerBottom = bottomY - PANEL_PAD;

        // 01/10/2026: divisao IGUAL, pedido do usuario. As duas colunas nascem
        // com a mesma largura -- o resto da folga, se sobrar (coluna muito
        // estreita para as duas metades), vai para a de skills, que tem o texto
        // de descricao mais longo. Antes era 42% para a direita (a razao da aba
        // 1, que existe porque o rodape de pericias e estreito).
        int each = (innerW - PER_GAP) / 2;
        int leftW = Math.max(120, each + (innerW - PER_GAP - each * 2));
        int rightW = Math.max(120, each);
        int rightX = innerX + leftW + PER_GAP;

        addSkillColumn(innerX, leftW, innerTop, innerBottom);
        addSpellColumn(rightX, rightW, innerTop, innerBottom);
    }

    /**
     * Metade esquerda: a lista de skills.
     *
     * <p><b>O que mudou em relacao a tela de Skills antiga:</b> quase nada. A
     * linha continua sendo nome + descricao, e o nome continua sendo o que abre
     * o formulario -- a edicao e o acesso ao detalhe sao o mesmo clique, que era o
     * pedido. O que muda e que ela virou coluna dentro da ficha, com o rodape e a
     * barra de abas em volta.
     */
    private void addSkillColumn(int x, int w, int top, int bottom) {
        // 01/10/2026 (mesma solucao que a coluna de magias): o titulo "Skills" fica
        // FIXO e o "+ Skill" passa a rolar junto com a lista. Antes ele era montado
        // antes do `prepare`, com `listTop` depois dele -- e em janela pequena a
        // linha do "+ Skill" sozinha ja comia a faixa da lista, deixando
        // `listBottom == listTop`: altura zero, barra aparecendo e nenhuma lista
        // atras. O cabecalho de magias sofre do mesmo mal com 5 linhas em vez de
        // 1, entao as duas colunas agora se comportam igual.
        int y = addSection("Skills", x, top, w);

        // 01/10/2026 (bug do usuario): a marcacao do Del NAO e zerada aqui. O
        // `rebuildWidgets` e justamente o que o primeiro clique no Del dispara
        // para redesenhar o botao como "Del?", e zerar aqui apagava a marcacao
        // no mesmo instante -- o segundo clique achava "-1" e nunca confirmava.
        // A marcacao so morre na troca de aba, na rolagem e no eco do servidor.
        skillColumn.delPendingBox = null;
        skillColumn.boxW = Math.max(20, w - BAR_W - BAR_GAP);
        // 01/10/2026 (pedido do usuario: "o botao do nome da skill pode ser mais
        // grossinho igual ao da magia"): e esta linha que faz o botao de nome da
        // skill nascer com os mesmos 16 px do da magia. Sem ela o
        // `Column.nameBtnH` continuaria no padrao (12 px, `LIST_BTN_H`) e o
        // card da skill nao bateria com o da magia -- e o pedido e justamente
        // que os dois sejam iguais. Mesma atribuicao que a coluna de magias faz
        // mais abaixo, pelo mesmo motivo.
        skillColumn.nameBtnH = LIST_NAME_BTN_H;
        // `addSection` devolve `top + rowH`: a faixa rolavel comeca logo abaixo do
        // titulo fixo, e o "+ Skill" entra nela. `headerH` e a UNICA faixa de
        // cabecalho desta coluna.
        int listTop = Math.max(y, top);
        int headerH = rowH;
        // A geometria entra ANTES de qualquer montagem, inclusive a do "+ Skill":
        // e dela que o `clippedHeight` do botao le a faixa. Medir depois daria
        // recorte com a geometria da montagem ANTERIOR (o mesmo motivo do
        // `prepare` separado do `layout`, 01/10/2026).
        skillColumn.prepare(x, w, listTop, bottom);

        List<SheetData.Skill> skills = skillList();
        // Mede o conteudo INTEIRO antes de montar: e o que o clamp da rolagem
        // precisa, e medir montando criaria widget para skill nenhuma visivel.
        int skillsH = 0;
        for (SheetData.Skill skill : skills) {
            skillsH += skillEntryHeight(x, skill);
        }
        // 01/10/2026: o `headerH` ENTRA na conta, pelo mesmo motivo da coluna de
        // magias -- o "+ Skill" rola junto com a lista, e sem ele no `contentH` a
        // rolagem pararia uma faixa antes do fim das skills.
        skillColumn.contentH = headerH + skillsH;
        skillColumn.scroll = skillColumn.clampScroll(skillColumn.scroll);

        int rowY = listTop - skillColumn.scroll;
        // O "+ Skill" na posicao ja deslocada pelo scroll, e recortado pela MESMA
        // regra dos demais widgets: nasce o pedaco visivel, nunca o botao inteiro
        // de uma faixa cortada, senao ele invade o titulo fixo ao rolar.
        int plusH = skillColumn.clippedHeight(rowY, rowH - 2);
        if (plusH > 0) {
            addRenderableWidget(Button.builder(Component.literal("+ Skill"), b -> openSkillForm(-1, null))
                    .bounds(x, Math.max(rowY, skillColumn.listTop), w, plusH)
                    .tooltip(Tooltip.create(Component.literal("Nova skill")))
                    .build());
        }
        rowY += headerH;
        for (int index = 0; index < skills.size(); index++) {
            SheetData.Skill skill = skills.get(index);
            int h = skillEntryHeight(x, skill);
            if (rowY + h > listTop && rowY < bottom) {
                addSkillEntry(x, rowY, skillColumn.boxW, skill, index);
            }
            rowY += h;
        }
        skillColumn.layout(x, w, listTop, bottom);
    }

    /**
     * Altura de uma skill: o botao de nome, ate 2 linhas de descricao e a linha
     * do Del. <b>FIXA</b>, a mesma do card da magia (01/10/2026).
     *
     * <p><b>Por que FIXA e nao medida:</b> ver {@link #LIST_ENTRY_H} -- e o pedido
     * do usuario de padronizar o tamanho do card de skill e de magia, e o que
     * faz a coluna ficar regular. Antes a altura era
     * {@code INV_PAD * 2 + LIST_BTN_H + skillDescLines(...) * LIST_DESC_ADV +
     * LIST_ROW_GAP}, ou seja, dependia de quantas linhas a descricao ocupava
     * (0, 1 ou 2). Agora e sempre {@link #LIST_ENTRY_H}: a 3a linha fica
     * reservada para o Del mesmo quando a descricao e curta ou vazia.
     *
     * <p><b>Por que a rolagem continua medindo antes de montar:</b> mesma regra
     * do {@link #rightItemHeight} -- {@code contentH} precisa do total exato
     * para o {@code clampScroll}, e medir montando criaria widget de skill
     * nenhuma visivel.
     *
     * @param x e {@code skill} nao entram mais na conta (a altura nao depende do
     *     texto nem da coluna); ficam por compatibilidade de assinatura com os
     *     dois chamadores, {@code addSkillColumn} e {@link #addSkillEntry}, que
     *     medem e montam com o mesmo par. Ver tambem {@link #skillDescLines},
     *     que agora conta as linhas da descricao e nao a altura.
     */
    private int skillEntryHeight(int x, SheetData.Skill skill) {
        return LIST_ENTRY_H;
    }

    /**
     * Quantas linhas a <b>descricao</b> da skill ocupa (0, 1 ou o teto de
     * {@link #LIST_DESC_LINES}).
     *
     * <p><b>Mudou de papel em 01/10/2026:</b> antes esta metodo media a ALTURA
     * do card, porque o card crescia com a descricao. Como a altura passou a ser
     * fixa ({@link #LIST_ENTRY_H}), ela nao decide mais nada -- ela descreve o
     * que a DESCRICAO ocupa, e o {@link #addSkillEntry} e que corta o texto em
     * {@link #LIST_DESC_LINES} linhas com reticencias na ultima. Ela continua
     * aqui (com o mesmo teto e a mesma largura de medida, o
     * {@code rightTextW(skillColumn.boxW)}) porque e a unicadefinicao do teto de
     * linhas de descricao: se a contagem e o corte divergirem,
     * {@code parts.get(i)} estoura.
     */
    private int skillDescLines(int x, SheetData.Skill skill) {
        if (skill.description().isEmpty()) {
            return 0;
        }
        return Math.min(LIST_DESC_LINES,
                this.font.split(Component.literal(skill.description()),
                        rightTextW(skillColumn.boxW)).size());
    }

    /**
     * Uma skill na lista: nome (que abre o formulario), Del e a descricao.
     *
     * <p><b>Por que o nome e o botao e nao ha um Edit:</b> o pedido foi o nome
     * como acesso ao detalhe, e editar e o mesmo caminho. Um segundo botao "Edit"
     * ao lado seria o mesmo destino duas vezes na mesma linha.
     *
     * <p><b>Por que o card e do mesmo tamanho do da magia (pedido do usuario em
     * 01/10/2026):</b> o modelo final e o mesmo dos dois lados -- nome em cima,
     * duas linhas de texto e o Del na 3a linha. A skill preenche as duas linhas
     * com a descricao (ate {@link #LIST_DESC_LINES}) e a magia com circulo e
     * execucao, entao quando a skill tem menos de 2 linhas de descricao a 3a
     * linha fica vazia: e o Del sozinho nela, e nao um Del grudado no texto.
     * Isso e proposital e e o que o jogador pediu ao padronizar com a magia --
     * nenhum dos dois divide a linha do Del com texto, para o Del nunca ser
     * lido como parte da descricao nem da execucao.
     */
    private void addSkillEntry(int x, int y, int w, SheetData.Skill skill, int index) {
        int h = skillEntryHeight(x, skill);
        int boxTop = Math.max(y, skillColumn.listTop);
        int boxBottom = Math.min(y + h - LIST_ROW_GAP, skillColumn.listBottom);
        if (boxBottom <= boxTop) {
            return;
        }
        itemBoxes.add(new ItemBox(x, boxTop, w, boxBottom - boxTop));
        y += INV_PAD;

        // 01/10/2026 (pedido do usuario: "inserir do lado esquerdo das skills, o
        // botao pra trocar de ordem, subir ou descer ela"): a faixa dos dois
        // botoes de mover e reservada a ESQUERDA do texto, e o nome continua
        // recebendo o que sobra da linha. 28 px fixos e o preco da
        // reordenacao -- e o nome da skill e o acesso ao detalhe, entao ele nao
        // pode ser o que encolhe; a alternativa (seta por cima do nome) faria o
        // jogador clicar na seta esperando abrir o detalhe.
        int moveW = LIST_MOVE_W * 2 + LIST_MOVE_GAP * 2;
        int tx = x + INV_PAD_X + moveW;
        int tw = rightTextW(w - moveW);
        addSkillMoveButtons(x + INV_PAD_X, y, skill.name(), index, skillList().size());
        // Nenhum guarda aqui: quem decide e `addEntryNameButton`, que cria o
        // PEDACO visivel em vez de pular a linha. Pular aqui e o que fazia a
        // skill sumir de repente (01/10/2026).
        // `null` e nao `""`: `null` e o que desliga o Del DENTRO do botao de nome,
        // e o pedido e que o Del da skill desca para a 3a linha, como o da magia
        // (ver `addDeleteButton` no fim deste metodo). O efeito colateral
        // desejado e o que o jogador pediu tambem: o nome passa a ter a largura
        // TODA da linha, igual ao da magia, em vez de deixar 32 px vazios para um
        // Del que nao esta mais ai.
        addEntryNameButton(skillColumn, EntryKind.SKILL, tx, y, tw, null, skill.name(), index);
        y += LIST_NAME_BTN_H;
        // Y da 1a linha de texto. Guardado porque a 3a linha (a do Del) tem Y
        // FIXO e nao pode ser a posicao em que o laco de descricao terminou: com
        // 0, 1 ou 2 linhas de descricao o Del tem de cair no mesmo lugar.
        int line1Y = y;

        List<FormattedCharSequence> parts =
                this.font.split(Component.literal(skill.description()), tw);
        int lines = Math.min(LIST_DESC_LINES, parts.size());
        for (int i = 0; i < lines; i++) {
            FormattedCharSequence part = parts.get(i);
            // Sobrou texto depois da ultima linha: as reticencias dizem ao jogador
            // que a descricao foi cortada e nao que acaba ali (mesmo cuidado do
            // inventario -- ver `plainText`).
            if (i == lines - 1 && parts.size() > LIST_DESC_LINES) {
                part = FormattedCharSequence.forward(plainText(part) + TRUNCATION_MARK,
                        Style.EMPTY);
            }
            // 01/10/2026: as duas linhas de texto NAO avancam pelo mesmo numero
            // (`LIST_LINE1_ADV` e `LIST_LINE2_ADV`). E assim que a soma das duas
            // linhas, com o nome, da a altura do card de magia -- ver
            // `LIST_ENTRY_H`. Com o mesmo avanco nas duas, a linha do Del cairia
            // 3 px abaixo do lugar dela.
            int adv = i == 0 ? LIST_LINE1_ADV : LIST_LINE2_ADV;
            // Uma linha so entra se couber INTEIRA: um texto cortado pela borda
            // subiria por cima do "+ Skill" da coluna.
            if (y >= skillColumn.listTop && y + adv <= skillColumn.listBottom) {
                textLines.add(new TextLine(part, tx, y, COL_MUTED));
            }
            y += adv;
        }

        // 01/10/2026: a 3a linha e a do Del, e ela e FIXA (nome + linha 1 +
        // linha 2), independente de quantas linhas de descricao a skill tem --
        // e o que faz o card bater com o da magia. O `delY` e o Y passado ao
        // `addDeleteButton`, que nasce recortado na borda (`clippedHeight`) como
        // todos os outros widgets da lista, entao ele nunca invade o rodape.
        addDeleteButton(skillColumn, EntryKind.SKILL, tx + tw - LIST_DEL_BTN_W,
                line1Y + LIST_LINE1_ADV + LIST_LINE2_ADV, index);
    }

    /**
     * Os dois botoes de mover (▲ e ▼) a esquerda do card da skill (pedido do
     * usuario em 01/10/2026: "inserir do lado esquerdo das skills, o botao pra
     * trocar de ordem, subir ou descer ela no caso").
     *
     * <p><b>Por que o servidor ja tem a operacao:</b> o {@code MOVE} da skill ja
     * existia (a tela de Skills antiga ja tinha as setas) e o que faltava era o
     * botao nesta tela. O MOVE e <b>por nome</b> e o passo
     * ({@code delta} -1 sobe, +1 desce) e vai no {@code description} do
     * {@code SheetSkillPayload}; nao foi criado payload novo nem mexido no
     * servidor.
     *
     * <p><b>Por que nao existem para as magias:</b> a ordem que a coluna de
     * magias mostra NAO e a ordem guardada -- {@code
     * SheetData.Spellbook#visible} devolve os indices guardados ordenados por
     * circulo e depois por nome, porque e assim que o filtro funciona. Nao ha
     * posicao guardada para mover, e o jogador veria "Chama de7012o Infernal"
     * descer e ela voltar, ja que a coluna reordena a cada redesenho. O MOVE da
     * skill e por posicao na lista, que e exatamente o que o jogador espera ver
     * mudar. Nao e um esquecimento: e a lista de magias nao ter ordem de leitura
     * propria.
     *
     * <p><b>Por que somem nas pontas:</b> o primeiro card nao sobe e o ultimo nao
     * desce. Criar o botao morto ali seria o jogador mirando, clicando e nada
     * acontecendo -- e, com o Del, ele ja aprendeu que botao que age tem
     * confirmacao; um botao que nunca age so ensina a desconfiar da linha.
     *
     * <p><b>PENDENCIA registrada de proposito (01/10/2026):</b> os glifos ▲
     * (U+25B2) e ▼ (U+25BC) nao tem garantia na fonte padrao do Minecraft. Se
     * aparecerem como caixa vazia no jogo, a correcao e trocar estes dois
     * literais por texto ASCII (por exemplo "+"/"-", ou "Acima"/"Abaixo") -- e
     * nao aumentar nem escolher outra fonte, porque a fonte do botao e a do
     * vanilla. A troca e de um literal por linha, entao e barata; fica
     * registrada aqui em vez de oculta, porque so quem joga ve o defeito.
     *
     * <p><b>Por que nao chama {@code rebuildWidgets} na acao:</b> quem redesenha
     * e o ECO do servidor ({@code broadcastSheet}), como em todas as outras
     * edicoes desta aba. Desenhar aqui mostraria a skill no lugar novo com os
     * widgets do lugar velho por um frame.
     */
    private void addSkillMoveButtons(int x, int y, String skillName, int index, int total) {
        // Mesma regra de recorte das demais linhas: nasce o PEDACO visivel, e nao
        // o botao inteiro de uma linha cortada pela borda da rolagem.
        int btnH = skillColumn.clippedHeight(y, LIST_NAME_BTN_H);
        if (btnH <= 0) {
            return;
        }
        // O Y tambem e empurrado para baixo quando a linha entra pela borda de
        // cima, pelo mesmo motivo do botao de nome.
        int btnY = Math.max(y, skillColumn.listTop);
        if (index > 0) {
            addRenderableWidget(Button.builder(Component.literal("▲"), b -> {
                // A marcacao do Del morre junto: o Del marcado aponta para um
                // indice guardado, e mover a skill troca o que esta neste indice.
                skillColumn.delPending = -1;
                if (!canEdit) {
                    return;
                }
                ClientPlayNetworking.send(RpgNetworking.SheetSkillPayload.move(
                        targetName, skillName, -1));
            })
                    .bounds(x, btnY, LIST_MOVE_W, btnH)
                    .tooltip(Tooltip.create(Component.literal("Subir na lista")))
                    .build());
        }
        if (index < total - 1) {
            // Mesma acao, passo oposto: ver o Javadoc do botao de cima.
            addRenderableWidget(Button.builder(Component.literal("▼"), b -> {
                skillColumn.delPending = -1;
                if (!canEdit) {
                    return;
                }
                ClientPlayNetworking.send(RpgNetworking.SheetSkillPayload.move(
                        targetName, skillName, 1));
            })
                    .bounds(x + LIST_MOVE_W + LIST_MOVE_GAP, btnY, LIST_MOVE_W, btnH)
                    .tooltip(Tooltip.create(Component.literal("Descer na lista")))
                    .build());
        }
    }

    /**
     * Metade direita: o cabecalho de conjuracao e a lista de magias.
     *
     * <p><b>O filtro vem ANTES da lista e nao depois:</b> ele e o que decide o
     * que a lista mostra, entao ele fica no cabecalho. E o filtro nao mexe na
     * lista guardada ({@link SheetData.Spellbook#visible(int)} devolve os indices
     * guardados), entao trocar o filtro nao pode trocar o indice que o proximo
     * UPDATE manda.
     *
     * <p><b>Mudou em 01/10/2026 (bug do usuario: "quando esta em uma tela
     * pequena, nao da pra descer porque a parte de cima do menu nao desce"):</b>
     * o cabecalho inteiro (filtro, atributo, CD e "+ Magia") passou a fazer parte
     * da <b>faixa rolavel</b>. Antes ele comia 5 linhas fora da lista, e em
     * janela pequena o {@code y} de depois do "+ Magia" passava do
     * {@code bottom}: o {@code prepare} fechava a faixa em zero
     * ({@code listBottom == listTop}), o {@code contentH} continuava cheio, a
     * barra aparecia e respondia -- e nao havia lista nenhuma atras dela. Era
     * exatamente o "nao da pra descer".
     *
     * <p><b>Por que o titulo da secao ficou FIXO:</b> ele e a identidade da
     * coluna, e e o mesmo raciocinio do {@code TAB_NAMES} no {@code renderContent}
     * -- um rotulo que sobe e desce com o conteudo deixa de dizer em que coluna o
     * jogador esta. Encolher o cabecalho ate ele caber sozinho nao foi o que o
     * usuario escolheu (01/10/2026): ele pediu que a parte de cima DESCESSE.
     */
    private void addSpellColumn(int x, int w, int top, int bottom) {
        int y = addSection("Spells", x, top, w);

        // Mesma razao da coluna de skills: a marcacao do Del sobrevive ao
        // `rebuildWidgets` do primeiro clique (01/10/2026). Estas tres linhas
        // subiram para ANTES da geometria porque o `prepare` e o que o recorte do
        // cabecalho rolavel le -- e o cabecalho e montado depois dele.
        spellColumn.delPendingBox = null;
        spellColumn.boxW = Math.max(20, w - BAR_W - BAR_GAP);
        spellColumn.nameBtnH = LIST_NAME_BTN_H;

        // 01/10/2026: a faixa comeca IMEDIATAMENTE depois do titulo fixo, e nao
        // depois do "+ Magia". `addSection` devolve `top + rowH`, entao este
        // `listTop` e `top` + a altura do titulo -- o `Math.max` continua aqui
        // pelo mesmo motivo de sempre: a coluna nao pode nascer acima do painel.
        int listTop = Math.max(y, top);
        // A geometria entra ANTES de qualquer montagem, inclusive a do cabecalho:
        // e dela que o `clippedHeight` de cada widget do cabecalho e do `lineFits`
        // dos rotulos leem a faixa. Medir depois daria recorte com a geometria
        // da montagem ANTERIOR -- o mesmo bug que o `prepare` separado do
        // `layout` ja resolve para as linhas (01/10/2026).
        spellColumn.prepare(x, w, listTop, bottom);

        // Altura do cabecalho rolavel, contada nas MESMAS faixas de `rowH` que
        // `addSpellHeader` monta, na mesma ordem:
        //   faixa 1  rotulo "Filtros de Magia"
        //   faixa 2  botao do filtro
        //   faixa 3  rotulo "Atributo de Conjuração"
        //   faixas 4 e 5  `addSpellHeaderRow`: botao do atributo + Modifier, e
        //            rotulo "CD" + caixa (e o que o `return y + rowH * 2` dela
        //            devolve)
        //   faixa 6  "+ Magia"
        //
        // <p><b>01/10/2026, bug do usuario: eram 5 faixas e o cabecalho ocupa
        // 6.</b> O rotulo "Filtros" e o botao estavam no mesmo `y` (o `labelOffset`
        // desce o texto alguns pixels, mas NAO abre uma faixa), entao o rotulo
        // caia dentro do botao. Pior: o `headerH` aqui era `rowH * 5`, uma faixa a
        // menos do que o cabecalho realmente usa, entao o `contentH` ficava curto e
        // a barra de rolagem sumia quando havia spells suficientes para gerar uma.
        // A correcao foi dar a faixa propria ao rotulo (em `addSpellHeader`) e
        // somar a 6a faixa aqui. **A conta e uma duplicata do `y += rowH` de la: se
        // mudar la, mude aqui.**
        //
        // <p><b>Por que e uma soma e nao o {@code y} medido depois:</b> o
        // `contentH` abaixo ja precisa deste numero para o `clampScroll`, e medir
        // o cabecalho exigiria monta-lo -- com o `scroll` ainda sem resolver. A
        // soma e o unico jeito de ter a conta antes do `scroll`; por isso ela esta
        // escrita faixa a faixa, para quem mexer no cabecalho ver onde somar e
        // onde tirar.
        int headerH = rowH * 6;

        // A lista a mostrar e a do FILTRO, mas os indices sao os GUARDADOS: e o
        // `stored` que vai no UPDATE, e nao a posicao na tela.
        List<Integer> visible = visibleSpells();
        List<SheetData.Spell> all = spellList();
        int spellsH = 0;
        for (Integer stored : visible) {
            spellsH += spellEntryHeight(x, all.get(stored));
        }
        // 01/10/2026: o `headerH` ENTRA na conta. O cabecalho agora rola junto com
        // a lista, e sem ele o `maxScroll` pararia antes do fim das magias -- o
        // "+ Magia", que e a ultima coisa do cabecalho, nunca apareceria ao
        // descer, e o mesmo bug voltaria pelo outro lado.
        spellColumn.contentH = headerH + spellsH;
        spellColumn.scroll = spellColumn.clampScroll(spellColumn.scroll);

        int rowY = listTop - spellColumn.scroll;
        addSpellHeader(x, w, rowY);
        // As magias comecam DEPOIS do cabecalho, e nao em `rowY`: o cabecalho e
        // rolavel e ocupa `headerH` da faixa.
        rowY += headerH;
        for (Integer stored : visible) {
            SheetData.Spell spell = all.get(stored);
            int h = spellEntryHeight(x, spell);
            if (rowY + h > listTop && rowY < bottom) {
                addSpellEntry(x, rowY, spellColumn.boxW, spell, stored);
            }
            rowY += h;
        }
        spellColumn.layout(x, w, listTop, bottom);
    }

    /**
     * O cabecalho rolavel das magias: rotulo e botao do filtro, rotulo e linha do
     * atributo (com a CD) e o "+ Magia".
     *
     * <p><b>Por que virou metodo (01/10/2026):</b> ele passou a ser montado
     * DEPOIS do {@code clampScroll}, com o {@code y} ja deslocado por
     * {@code -scroll} -- e sao cinco faixas dentro do {@code addSpellColumn},
     * onde era facil deixar uma delas no Y antigo. Aqui o Y deslocado e o
     * parametro, e cada faixa avanca pelo unico {@code y += rowH} (ou pelo
     * {@code return} do {@link #addSpellHeaderRow}), sem nenhum Y intermediario
     * que possa ficar sem o deslocamento.
     *
     * <p><b>Por que os widgets nascem recortados e os rotulos nao:</b> e o mesmo
     * tratamento do {@link #addEntryNameButton} e do {@link #addDeleteButton}
     * (01/10/2026) -- widget do vanilla e desenhado no Y que recebeu, entao
     * criado inteiro ele invadiria o titulo fixo da secao ao subir e a barra de
     * abas ao descer. {@code TextLine} e desenhado por esta tela e some e aparece
     * sem invadir nada, entao ele usa {@code lineFits} e nao {@code clippedHeight}.
     */
    private void addSpellHeader(int x, int w, int y) {
        // Rotulo ACIMA do botao, na FAIXA DE CIMA (pedido do usuario em
        // 01/10/2026): sem ele o botao dizia so "Todas" ou "3o Circulo", e o
        // jogador nao sabia o que aquela linha controlava.
        //
        // <p><b>01/10/2026, bug do usuario: o rotulo entrava DENTRO do botao.</b>
        // O rotulo e o botao estavam sendo montados no mesmo `y`: o `labelOffset`
        // desce o texto alguns pixels, mas issoNAO abre uma faixa nova, entao o
        // texto caia DENTRO do botao. O rotulo ganhou a propria faixa (um
        // `y += rowH` entre ele e o botao), que e como a coluna era antes de o
        // cabecalho virar rolavel — e e o que mantem a conta do `headerH` igual
        // ao que o cabecalho realmente ocupa.
        if (spellColumn.lineFits(y, rowH)) {
            textLines.add(new TextLine("Filtros de Magia", x, y + labelOffset(), COL_LABEL));
        }
        y += rowH;

        int filterH = spellColumn.clippedHeight(y, rowH - 2);
        if (filterH > 0) {
            addRenderableWidget(Button.builder(
                            Component.literal(SheetData.Spellbook.filterText(spellFilter)),
                            b -> {
                                spellFilter = SheetData.Spellbook.nextFilter(spellFilter);
                                rebuildWidgets();
                            })
                    .bounds(x, Math.max(y, spellColumn.listTop), w, filterH)
                    .tooltip(Tooltip.create(Component.literal(
                            "Mostrar todas as magias ou so um circulo")))
                    .build());
        }
        y += rowH;

        // Mesma regra para o atributo: o rotulo diz o que o botao faz, e tambem
        // ganha a faixa dele propria, pelos mesmos motivos.
        if (spellColumn.lineFits(y, rowH)) {
            textLines.add(new TextLine("Atributo de Conjuração",
                    x, y + labelOffset(), COL_LABEL));
        }
        y += rowH;

        y = addSpellHeaderRow(x, w, y);

        // O "+ Magia" e o ultimo item do cabecalho rolavel, entao ele e a prova de
        // que o `headerH` entrou no `contentH`: se faltasse, ele nunca apareceria
        // ao descer.
        int plusH = spellColumn.clippedHeight(y, rowH - 2);
        if (plusH > 0) {
            addRenderableWidget(Button.builder(Component.literal("+ Magia"),
                            b -> openSpellForm(-1, null))
                    .bounds(x, Math.max(y, spellColumn.listTop), w, plusH)
                    .tooltip(Tooltip.create(Component.literal("Nova magia")))
                    .build());
        }
        // Nao ha `y += rowH` depois do "+ Magia" de proposito: o fim do cabecalho e
        // medido pelo `headerH` do `addSpellColumn`, e um segundo lugar com a conta
        // seria um segundo lugar para ela divergir.
    }

    /**
     * Altura de uma magia: botao de nome em cima, circulo, execucao e custo, com
     * o Del na 3a linha. <b>FIXA</b>, a mesma do card da skill (01/10/2026).
     *
     * <p><b>Por que as linhas de texto contam duas vezes:</b> a altura e medida
     * aqui, antes de montar, e a mesma funcao e usada no {@link #addSpellEntry}.
     * Se as duas discordassem, o card seguinte bornaria sobre o texto -- que e
     * exatamente o estouro de borda que o jogador viu. Por isso
     * {@link #spellExecLines} e o unico lugar que fatia o texto.
     *
     * <p><b>Por que o custo entra sempre:</b> execucao e custo sao as duas
     * informacoes que diferenciam duas magias parecidas. Uma linha vazia
     * empurraria o Del para baixo sem dizer nada; o Del precisa de uma linha
     * propria na direita para nao achar que e parte do texto.
     *
     * <p><b>Por que a altura virou FIXA (01/10/2026):</b> antes ela era
     * {@code INV_PAD * 2 + LIST_NAME_BTN_H + LIST_CIRCLE_ADV} mais
     * {@link #LIST_DESC_ADV} para cada um dos DOIS campos que estivessem
     * preenchidos ({@code spell.execution().isEmpty()} e
     * {@code spell.cost().isEmpty()}), e era por ai que o card da magia variava de
     * tamanho. O pedido do usuario foi padronizar: card de tamanho fixo e igual
     * nos dois, e a linha do Del igual em ambos. Essa conta com
     * {@code isEmpty()} nao existe mais, e as LINHAS VAZIAS continuam
     * reservadas -- e o que faz os dois cards terem a mesma altura mesmo quando
     * a magia nao tem execucao nem custo: o espaco sobrando e o que garante que
     * um card nao "cresca" quando o jogador preenche o formulario. As tres
     * linhas ({@link #LIST_LINE1_ADV}, {@link #LIST_LINE2_ADV},
     * {@link #LIST_LINE3_ADV}) sao contadas sempre; ver {@link #LIST_ENTRY_H}.
     */
    private int spellEntryHeight(int x, SheetData.Spell spell) {
        return LIST_ENTRY_H;
    }

    /**
     * Uma magia na lista, na disposicao de 01/10/2026:
     *
     * <pre>
     * [  Nome da magia, largo                     ]
     * 1º círculo
     * Execucao da magia
     * Custo da magia                            [ Del ]
     * </pre>
     *
     * <p><b>Por que o nome ocupa a largura toda:</b> ele e o botao de acesso ao
     * detalhe, e nome de magia truncado ("Fogo Pr...") impede o jogador de
     * saber o que esta na ficha dele.
     *
     * <p><b>Por que circulo, execucao e custo sao TEXTO e nao botao:</b> nenhum
     * dos tres e uma acao. Sao o que o jogador compara entre linhas sem abrir
     * nada. O Del e a unica acao alem do nome.
     *
     * <p><b>Por que o Del desceu para a ultima linha:</b> pedido do jogador em
     * 01/10/2026. Na linha do circulo ele ficava no meio do card, longe das duas
     * informacoes que ele nao e, e o usuario leu como se fosse botao do circulo.
     * Na direita da linha do custo ele fecha a caixa e fica embaixo, como
     * pedido.
     *
     * <p><b>Por que o texto e guardado por {@code lineFits} e o botao nao:</b>
     * texto e um {@code TextLine} desenhado por esta tela, entao ele some e
     * aparece sem invadir nada. Botao e widget do vanilla, desenhado na posicao Y
     * que recebeu -- ele precisa nascer <b>recortado</b>
     * ({@link Column#clippedHeight}) em vez de ser pulado, senao o cartao
     * desaparece de repente na rolagem (01/10/2026).
     */
    private void addSpellEntry(int x, int y, int w, SheetData.Spell spell, int storedIndex) {
        int h = spellEntryHeight(x, spell);
        int boxTop = Math.max(y, spellColumn.listTop);
        int boxBottom = Math.min(y + h - LIST_ROW_GAP, spellColumn.listBottom);
        if (boxBottom <= boxTop) {
            return;
        }
        itemBoxes.add(new ItemBox(x, boxTop, w, boxBottom - boxTop));
        y += INV_PAD;

        int tx = x + INV_PAD_X;
        int tw = rightTextW(w);

        // Linha 1: o nome, na largura toda da caixa. Sem guarda de "cabe inteiro":
        // `addEntryNameButton` cria o pedaco visivel (01/10/2026).
        addEntryNameButton(spellColumn, EntryKind.SPELL, tx, y, tw, null, spell.name(),
                storedIndex);
        y += LIST_NAME_BTN_H;

        // Linha 2: o circulo, com o nome por extenso ("1º circulo") -- pedido do
        // usuario em 01/10/2026: o ordinal sozinho nao dizia o que era.
        // 01/10/2026: e a 1a linha de texto do card, entao o avanco e o mesmo da
        // skill (`LIST_LINE1_ADV`) -- ver `LIST_ENTRY_H`.
        if (spellColumn.lineFits(y, LIST_LINE1_ADV)) {
            textLines.add(new TextLine(spell.circleText(), tx, y + 2, COL_LABEL));
        }
        // Ultima linha com texto: e onde o Del entra. Nasce na linha do circulo
        // e desce conforme execucao e custo aparecem, entao o botao fica sempre
        // na ultima linha que a magia realmente ocupa.
        int delY = y;
        y += LIST_LINE1_ADV;

        // Linha 3: a execucao.
        if (!spell.execution().isEmpty()) {
            if (spellColumn.lineFits(y, LIST_LINE2_ADV)) {
                textLines.add(new TextLine(
                        truncateWithEllipsis(spell.execution(), tw), tx, y + 2, COL_MUTED));
            }
            delY = y;
            y += LIST_LINE2_ADV;
        }

        // Linha 4: o custo a esquerda, com o Del a direita fechando a caixa.
        if (!spell.cost().isEmpty()) {
            // A largura do texto perde a faixa do Del: sem isso o custo e o
            // botao escreveriam um sobre o outro.
            int costW = Math.max(12, tw - LIST_DEL_BTN_W - PER_GAP);
            if (spellColumn.lineFits(y, LIST_LINE3_ADV)) {
                textLines.add(new TextLine(
                        truncateWithEllipsis(spell.cost(), costW), tx, y + 2, COL_MUTED));
            }
            delY = y;
            y += LIST_LINE3_ADV;
        }
        // 01/10/2026: `delY` continua sendo a ultima linha COM TEXTO (comportamento
        // pedido pelo jogador), mas a ALTURA do botao e a do card -- 16 px, igual
        // a do botao de nome. E por isso que a 3a linha do card e
        // `LIST_LINE3_ADV`: um Del de 16 px em 11 px invadiria a folga do card.
        addDeleteButton(spellColumn, EntryKind.SPELL, tx + tw - LIST_DEL_BTN_W, delY, storedIndex);
    }

    /**
     * O botao Del de uma linha, sozinho.
     *
     * <p><b>Por que foi separado do {@link #addEntryNameButton}:</b> o nome
     * ocupa a linha toda e o Del mora numa das linhas de baixo -- na magia, na
     * ultima linha com texto; na skill, na 3a linha, que e fixa (01/10/2026).
     * O comportamento de confirmar (primeiro clique marca, segundo no mesmo
     * indice apaga) e o mesmo, entao so a posicao muda.
     */
    private void addDeleteButton(Column column, EntryKind kind, int x, int y,
                                 int storedIndex) {
        // Mesmo tratamento do nome: nasce o PEDACO visivel, nunca o botao inteiro
        // de uma linha cortada (01/10/2026).
        // 01/10/2026: a altura e `LIST_DEL_BTN_H` e nao mais a altura da linha
        // antiga (`LIST_BTN_H`), porque o pedido foi o Del com a mesma grossura do
        // botao de nome ("assim como o delete"). Sao os mesmos 16 px nos dois
        // cards, e o recorte continua valendo: o que sobra da faixa e o que o
        // widget recebe.
        int delH = column.clippedHeight(y, LIST_DEL_BTN_H);
        if (delH <= 0) {
            return;
        }
        int delY = Math.max(y, column.listTop);
        Button del = addRenderableWidget(Button.builder(
                        column.delPending == storedIndex
                                ? Component.literal("Del?")
                                : Component.literal("Del"),
                        b -> onDeleteEntry(column, kind, storedIndex))
                .bounds(x, delY, LIST_BTN_W, delH)
                .tooltip(Tooltip.create(Component.literal(
                        column.delPending == storedIndex
                                ? "Clique de novo para confirmar"
                                : "Apagar")))
                .build());
        if (column.delPending == storedIndex) {
            column.delPendingBox = new ItemBox(del.getX(), del.getY(),
                    del.getWidth(), del.getHeight());
        }
    }

    /**
     * O botao de nome de uma linha, com o texto da direita (o circulo da magia)
     * desenhado ao lado e o Del no fim.
     *
     * <p><b>Por que um metodo e nao duas copias:</b> skill e magia tem o mesmo
     * formato de linha -- nome que abre, texto opcional a direita, Del que
     * confirma. O que muda e so o que abre e o texto, entao o resto (larguras,
     * confirmacao, recorte do nome) seria duplicata.
     *
     * <p><b>Por que o Del fica FORA do botao de nome:</b> sao acoes diferentes com
     * consequencias diferentes, e um botao dentro do outro nao da para clicar no
     * de dentro sem acertar o de fora.
     */
    private void addEntryNameButton(Column column, EntryKind kind, int x, int y, int w,
                                    String right, String name, int storedIndex) {
        int delW = right == null ? 0 : LIST_BTN_W;
        int rightW = right == null || right.isEmpty() ? 0 : this.font.width(right) + 4;
        int nameW = Math.max(20, w - delW - 4 - rightW);
        // O botao nasce com a ALTURA que sobra na faixa visivel, nunca a altura
        // cheia de uma linha cortada: e o que da o pedaco na rolagem sem deixar o
        // widget invadir a borda (ver `Column#clippedHeight`, 01/10/2026).
        int nameH = column.clippedHeight(y, column.nameBtnH);
        if (nameH <= 0) {
            return;
        }
        // O Y tambem e empurrado para baixo quando a linha entra pela borda de
        // cima: sem isso o widget nasce acima da faixa e vaza para o cabecalho.
        int nameY = Math.max(y, column.listTop);
        addRenderableWidget(Button.builder(
                        Component.literal(truncateWithEllipsis(name, Math.max(8, nameW - 4))),
                        b -> {
                            if (kind == EntryKind.SKILL) {
                                openSkillForm(storedIndex, skillAt(storedIndex));
                            } else {
                                openSpellForm(storedIndex, spellAt(storedIndex));
                            }
                        })
                .bounds(x, nameY, nameW, nameH)
                .tooltip(Tooltip.create(Component.literal(
                        kind == EntryKind.SKILL
                                ? "Abrir esta skill"
                                : "Abrir esta magia")))
                .build());
        if (rightW > 0) {
            // 01/10/2026: o centramento usa `LIST_NAME_BTN_H` porque foi essa a
            // altura que o botao ganhou nos dois cards. Com o numero antigo
            // (`LIST_BTN_H`) o texto do `right` subiria 2 px em um botao 16 px.
            // Hoje nao ha mudanca visivel -- `right` so e nao nulo na magia, e a
            // magia ja usava a altura maior -- mas deixar o numero antigo aqui
            // seria um erro esperando a proxima coluna que usar `right`.
            textLines.add(new TextLine(right,
                    x + nameW + 4, y + Math.max(0, (LIST_NAME_BTN_H - 8) / 2), COL_MUTED));
        }
        if (delW > 0) {
            addDeleteButton(column, kind, x + w - delW, y, storedIndex);
        }
    }

    /**
     * A linha do cabecalho: o botao do atributo a esquerda e o texto do Modificador
     * no lugar que a CD ocupava; a CD desce para a linha de baixo, que era a do
     * Modificador (pedido do usuario em 01/10/2026).
     *
     * <p><b>Por que o Modificador e texto e nao widget:</b> ele e derivado do
     * atributo escolhido na pagina 1 ({@link SheetData#attributeValue}), nunca
     * digitado. Um {@code TextLine} nao so e mais barato como e o que nao pode
     * ficar 1 frame atras do eco do servidor.
     *
     * <p><b>Por que o texto nasce VAZIO:</b> ele e reescrito por
     * {@link #applySpellHeader} quando a ficha chega. Escrever aqui mostraria
     * "Modifier: 0" no primeiro frame, antes de existir atributo escolhido.
     *
     * <p><b>Mudou em 01/10/2026: esta linha virou parte da faixa ROLAVEL</b> (ver
     * {@link #addSpellColumn}), entao os DOIS widgets daqui -- o botao do
     * atributo e a caixa de CD -- passaram a nascer recortados na faixa, pelo
     * mesmo motivo do {@link #addEntryNameButton} e do {@link #addDeleteButton}:
     * criados inteiros, eles invadiriam o titulo fixo da secao ao subir e a barra
     * de abas ao descer. O {@code y} que chega ja vem deslocado por
     * {@code -scroll}, e o retorno continua sendo o mesmo {@code y + rowH * 2} --
     * as 2 faixas que o {@code headerH} do {@code addSpellColumn} conta para
     * esta linha. A assinatura nao mudou: so o Y que entra nela que mudou de
     * papel.
     */
    private int addSpellHeaderRow(int x, int w, int y) {
        int cdW = Math.max(36, Math.min(56, w / 4));
        // A largura do botao de atributo e calculada pelo que o Modifier precisa
        // (01/10/2026): antes ele tomava a linha toda e o texto nascia encostado
        // nele, sem espaco, entao "Modifier: +3" era cortado nas reticencias.
        // O pior caso do texto e o que dita a reserva.
        int modW = this.font.width("Modifier: -999") + PER_GAP;
        int attrW = Math.max(30, w - modW - PER_GAP);
        // O texto nasce depois do botao + folga, e nao na borda do painel: assim
        // ele tem `modW` de verdade e nunca depende do resto da linha.
        int modX = x + attrW + PER_GAP;

        // 01/10/2026: nasce o PEDACO visivel, nunca o botao inteiro de uma linha
        // cortada pela borda da rolagem -- e nasce no PEDACO, empurrado para baixo
        // quando a linha entra pela borda de cima.
        int attrH = spellColumn.clippedHeight(y, rowH - 2);
        if (attrH > 0) {
            addRenderableWidget(Button.builder(
                            Component.literal(castingAttributeLabel()),
                            b -> openCastingAttributePicker())
                    .bounds(x, Math.max(y, spellColumn.listTop), attrW, attrH)
                    .tooltip(Tooltip.create(Component.literal(
                            "Atributo de conjuração das magias")))
                    .build());
        }

        // O indice e guardado, nao o texto: `applySpellHeader` reescreve este
        // `TextLine` no eco do servidor, trocando x, y e cor junto.
        //
        // 01/10/2026: o Modifier e `TextLine` (desenhado por esta tela), entao ele
        // some e aparece na rolagem sem invadir nada -- e por isso que segue o
        // `lineFits` e nao o `clippedHeight` do botao ao lado.
        if (spellColumn.lineFits(y, rowH)) {
            spellHeaderIndex = textLines.size();
            textLines.add(new TextLine("", modX, y + labelOffset(), COL_BOX_TEXT));
        }

        // A CD vai para a ESQUERDA, na linha de baixo (01/10/2026): encostada a
        // direita ela entrava por cima da caixa de texto do Modifier.
        //
        // <p><b>Por que a caixa encosta no rotulo:</b> o pedido foi "mais perto".
        // O rotulo e medido pelo que a fonte desenha, e nao pela largura da CD
        // (que e o dobro), entao sobrava um vao enorme entre "CD" e a caixa. A
        // folga agora e a de um rotulo, nao a largura da caixa.
        int cdY = y + rowH;
        if (spellColumn.lineFits(cdY, rowH)) {
            addWrappedLabel("CD", x, cdY, cdW, COL_LABEL);
        }
        int cdLabelW = Math.max(8, this.font.width("CD") + 2);
        // 01/10/2026: a caixa e widget, entao e a ultima a ser recortada. O
        // `clippedHeight` tambem evita criar a caixa inteira de uma CD que o
        // jogador comecou a digitar e que so esta saindo pela borda de baixo --
        // sem isso ela transbordaria por cima da barra de abas.
        int cdH = spellColumn.clippedHeight(cdY, rowH - 2);
        if (cdH > 0) {
            cdBox = new EditBox(this.font, x + cdLabelW, Math.max(cdY, spellColumn.listTop),
                    cdW, cdH, Component.literal("CD"));
            // 4 digitos: o mesmo teto de Spellbook.CD_MAX, que por sua vez espelha o
            // `stringUtf8(2048)` do SheetFieldPayload. Os tres tetos concordam.
            cdBox.setMaxLength(4);
            cdBox.setFilter(text -> text.matches("\\d{0,4}"));
            cdBox.setResponder(this::onCdTyped);
            addRenderableWidget(cdBox);
        }
        return y + rowH * 2;
    }

    /** O nome do atributo escolhido, ou o texto que convida a escolher. */
    private String castingAttributeLabel() {
        String name = sheet == null ? "" : sheet.castingAttributeName();
        return name.isEmpty() ? "Attribute?" : name;
    }

    /** Qual lista a linha pertence: skills ou magias. */
    private enum EntryKind {
        SKILL,
        SPELL
    }

    /** Abre o formulario da skill: nova quando {@code index} e {@code -1}. */
    private void openSkillForm(int index, SheetData.Skill skill) {
        skillColumn.delPending = -1;
        this.minecraft.setScreen(new SkillFormScreen(this, targetName, index, skill));
    }

    /** Abre o formulario da magia: nova quando {@code index} e {@code -1}. */
    private void openSpellForm(int index, SheetData.Spell spell) {
        spellColumn.delPending = -1;
        this.minecraft.setScreen(new SpellFormScreen(this, targetName, index, spell));
    }

    /** As skills da ficha, ou lista vazia antes de a ficha chegar. */
    private List<SheetData.Skill> skillList() {
        return sheet == null ? List.of() : sheet.skills();
    }

    /** A skill do indice guardado, ou {@code null}. */
    private SheetData.Skill skillAt(int index) {
        List<SheetData.Skill> skills = skillList();
        return index < 0 || index >= skills.size() ? null : skills.get(index);
    }

    /** As magias da ficha, ou lista vazia antes de a ficha chegar. */
    private List<SheetData.Spell> spellList() {
        return sheet == null ? List.of() : sheet.spellbook().spells();
    }

    /** A magia do indice guardado, ou {@code null}. */
    private SheetData.Spell spellAt(int index) {
        List<SheetData.Spell> spells = spellList();
        return index < 0 || index >= spells.size() ? null : spells.get(index);
    }

    /**
     * Os indices <b>guardados</b> das magias que o filtro mostra, ja ordenados.
     *
     * <p><b>Por que o filtro nao guarda nada:</b> ele e escolha da tela. Trocar
     * "Todas" por "2o Circulo" e clicar em Edit tem de mandar o indice guardado
     * da magia, e nao a posicao dela na tela -- que e o que
     * {@link SheetData.Spellbook#visible(int)} devolve.
     */
    private List<Integer> visibleSpells() {
        return sheet == null ? List.of() : sheet.spellbook().visible(spellFilter);
    }

    /**
     * O clique no Del de uma das duas listas: o primeiro marca, o segundo no mesmo
     * indice apaga.
     *
     * <p><b>Por que confirmar:</b> apagar e o unico ato sem volta desta aba, e o
     * item nao tem como voltar depois.
     */
    private void onDeleteEntry(Column column, EntryKind kind, int index) {
        int size = kind == EntryKind.SKILL ? skillList().size() : spellList().size();
        if (index < 0 || index >= size) {
            column.delPending = -1;
            return;
        }
        if (column.delPending != index) {
            column.delPending = index;
            rebuildWidgets();
            return;
        }
        column.delPending = -1;
        if (!canEdit) {
            return;
        }
        ClientPlayNetworking.send(kind == EntryKind.SKILL
                ? RpgNetworking.SheetSkillPayload.remove(targetName,
                        skillAt(index) == null ? "" : skillAt(index).name())
                : RpgNetworking.SheetSpellPayload.remove(targetName, index));
    }

    /** Abre o seletor de atributos para escolher o de conjuracao das magias. */
    private void openCastingAttributePicker() {
        if (this.minecraft == null) {
            return;
        }
        String current = sheet == null ? "" : sheet.spellbook().castingAttribute();
        this.minecraft.setScreen(new AttributePickerScreen(this, "Spells", current, id -> {
            if (canEdit && id != null && !id.isEmpty()) {
                ClientPlayNetworking.send(
                        new RpgNetworking.SheetFieldPayload(targetName, "castingAttribute", id));
            }
            // Volta para a ficha e PEDE a ficha de novo: o eco do seletor chega
            // enquanto a ficha ainda esta embaixo dela e seria descartado.
            this.minecraft.setScreen(this);
            TabletopRpgClient.requestSheet(targetName);
        }));
    }

    /**
     * A CD foi digitada. So envia quando o texto ja e um numero inteiro.
     *
     * <p>Mesmo desenho do {@link #onMaxWeightTyped}: "12" parseia e vai, "12."
     * fica marcado e so sai no {@link #flushCd}, para nao gravar 12 e deixar o eco
     * reescrever a caixa com o cursor no meio.
     */
    private void onCdTyped(String value) {
        if (suppressCd || !canEdit) {
            return;
        }
        // 1 a 4 digitos: o texto e sempre um numero inteiro, entao nao existe o
        // caso intermediario do limite de peso ("12."), e o que fica para tras e so
        // a caixa vazia -- que e CD 0.
        if (value != null && value.matches("\\d{1,4}")) {
            pendingCd = false;
            ClientPlayNetworking.send(new RpgNetworking.SheetFieldPayload(targetName, "cd", value));
        } else {
            pendingCd = true;
        }
    }

    /**
     * Envia a CD que ficou marcada.
     *
     * <p><b>Mesmo funil do {@link #flushMaxWeight}:</b> o {@code rebuildWidgets}
     * destroi a caixa (troca de aba, resize, rolagem, estado novo do servidor), e o
     * texto pendente vive dentro dela.
     */
    private void flushCd() {
        if (!pendingCd) {
            return;
        }
        if (canEdit && cdBox != null) {
            String value = cdBox.getValue();
            ClientPlayNetworking.send(new RpgNetworking.SheetFieldPayload(targetName, "cd",
                    value.isEmpty() ? "0" : value));
        }
        pendingCd = false;
    }

    /**
     * Preenche a caixa da CD e a linha do Modificador com o que o servidor tem.
     *
     * <p><b>Nao sobrescreve a caixa com o foco:</b> mesma regra da base e do
     * {@link #applyMaxWeightBox} -- o eco chega enquanto o jogador ainda digita.
     */
    private void applySpellHeader() {
        if (sheet == null) {
            return;
        }
        if (spellHeaderIndex >= 0 && spellHeaderIndex < textLines.size()) {
            String id = sheet.spellbook().castingAttribute();
            int modifier = id.isEmpty() ? 0 : sheet.attributeValue(id);
            String label = id.isEmpty()
                    ? "Modifier: -"
                    : "Modifier: " + (modifier >= 0 ? "+" + modifier : Integer.toString(modifier));
            // O texto cresce para DIREITA a partir do X do `TextLine`, que e o fim
            // do botao de atributo (`cdX`). Entao a largura util e o que sobra ate a
            // borda do painel -- e nao `panelW`: usar a largura toda deixava o
            // "Modifier: -" transbordando para fora do painel (relato de
            // 01/10/2026).
            TextLine current = textLines.get(spellHeaderIndex);
            int avail = (spellColumn.panelX + spellColumn.panelW) - current.x();
            textLines.set(spellHeaderIndex, new TextLine(
                    truncateWithEllipsis(label, Math.max(12, avail)),
                    current.x(), current.y(), current.color()));
        }
        if (cdBox == null) {
            return;
        }
        cdBox.setEditable(canEdit);
        if (cdBox.isFocused()) {
            return;
        }
        String next = Integer.toString(sheet.spellbook().cd());
        if (!next.equals(cdBox.getValue())) {
            suppressCd = true;
            try {
                cdBox.setValue(next);
            } finally {
                suppressCd = false;
            }
        }
    }

    /**
     * Aba 2: a metade esquerda com os dois quadros de texto livre com rolagem
     * (FASE 2A, 30/09/2026).
     *
     * <p><b>O que decide o desenho:</b> sao dois {@code MultiLineEditBox}
     * empilhados, com o titulo de cada um acima ({@link #addSection}). A coluna
     * direita fica <b>VAZIA</b> nesta fase: e o espaco do inventario, que e da
     * fase 2B e vai reusar este layout.
     *
     * <p><b>Por que a coluna e a MESMA conta de largura da aba 1:</b> a largura
     * da esquerda ({@code leftW}) e calculada com a mesma fracao e o mesmo teto
     * de {@code buildCharacterTab}, para que as duas abas desenhem o texto
     * na mesma coluna quando o jogador alterna entre elas. O que sobra da conta
     * (a direita) e o que o inventario vai ocupar depois.
     *
     * <p><b>Por que cada lista do widget do {@code renderContent} e limpa:</b>
     * {@code renderContent} desenha as barras, {@code attrRows} e
     * {@code periciaRows} o que sobrou da <b>ultima aba montada</b>. Sem o
     * {@code clear()}, voltar para a aba 2 depois de passar pela aba 1 deixaria
     * a barra de HP e os numeros dos atributos desenhados por cima dos quadros.
     */
    private void buildInfoTab(int x0, int panelW, int topY, int bottomY) {
        arrowButtons.clear();
        attrRows.clear();
        periciaRows.clear();
        resourceSteps.clear();
        hpBar = null;
        manaBar = null;
        // Os widgets da aba anterior morreram com o rebuildWidgets(): as caixas
        // de texto nao podem sobreviver a ele, porque o texto que o jogador
        // escreveu nelas e o que o `flush` le (ver rebuildWidgets).
        multiLineBoxes.clear();

        rowH = fitRowHeight(INFO_TAB_ROWS, topY, bottomY);

        int innerX = x0 + PANEL_PAD;
        int innerW = Math.max(120, panelW - 2 * PANEL_PAD);
        int innerTop = topY + PANEL_PAD;
        int innerBottom = bottomY - PANEL_PAD;

        // Mesma conta de buildCharacterTab, so que aqui sobra so a metade
        // esquerda (a direita e o inventario da fase 2B).
        int perW = Math.max(140, Math.min(PER_W_MAX, (int) (innerW * PER_W_RATIO)));
        int leftW = Math.max(120, innerW - perW - PER_GAP);

        // Altura util dividida em 2: cada bloco e um titulo + a caixa dele, e o
        // resto (impar) fica no segundo, que e o de baixo.
        int usable = Math.max(2 * MIN_ROW_H, innerBottom - innerTop);
        int blockH = usable / 2;
        int split = innerTop + blockH;
        addMultiLineBlock("appearance", innerX, leftW, innerTop, split);
        addMultiLineBlock("backstory", innerX, leftW, split, innerBottom);

        // A direita e a coluna do inventario (FASE 2B). A largura e a MESMA conta
        // (`perW`) da aba 0, e nao o que sobrou: e o que faz os dois titulos
        // ("Character Appearance" e "Inventory") nascerem na mesma coluna quando
        // o jogador alterna entre as abas.
        addInventoryColumn(innerX + leftW + PER_GAP, perW, innerTop, innerBottom);
    }

    /**
     * Coluna direita da aba 1: o limite de peso, o resumo e a lista de itens
     * (30/09/2026, FASE 2B).
     *
     * <p><b>O desenho, de cima para baixo:</b> titulo {@code Inventory}, a linha
     * do {@code Max Weight} (rotulo + caixa), a linha do resumo
     * {@code Weight: X.XX / Y.YY}, o botao {@code + Item} e a lista. A lista e o
     * que sobra, e e a unica parte com rolagem.
     *
     * <p><b>Por que a lista comeca numa Y propria ({@link #rightListTop}) e nao no
     * fim do cabecalho:</b> e o mesmo par que {@link #maxRightScroll} usa, para
     * que o clamp e a area visivel concordem. Com o par errado, a ultima linha
     * ficaria alcancavel so ate metade, que e o tipo de bug que so aparece com
     * lista cheia.
     *
     * <p><b>Por que so o item inteiro e montado:</b> os botoes Edit e Del sao
     * widgets, e um widget cortado pela borda continuaria clicavel em cima da barra
     * de abas. O bloco inteiro que couber e montado, e o resto fica fora da tela
     * (o mesmo criterio de {@link #drawY}).
     */
    private void addInventoryColumn(int x, int w, int top, int bottom) {
        rightPanelX = x;
        rightPanelW = w;
        rightListTop = top;
        rightListBottom = bottom;

        int y = addSection("Inventory", x, top, w);

        // Linha do Max Weight: o rotulo a esquerda e a caixa encostada na
        // direita. A caixa e pequena (o valor tem 2 casas) e fica a direita
        // porque e o rotulo que cresce quando o Mestre trocar o rotulo do campo.
        int boxW = Math.max(40, Math.min(64, w / 3));
        int boxX = x + w - boxW;
        addWrappedLabel("Max Weight", x, y, Math.max(20, boxX - x - 4), COL_LABEL);
        maxWeightBox = new EditBox(this.font, boxX, y, boxW, rowH - 2,
                Component.literal("Max Weight"));
        maxWeightBox.setMaxLength(8);
        // Filtro de digito e UM ponto, com 2 casas (o mesmo do formulario do
        // item): e o que impede "1.2.3", que nao parseia e cairia em 0 ao
        // salvar. Confirmado no bytecode do 1.21.11 que EditBox.setFilter e
        // java.util.function.Predicate<String>, e nao o EditBox.Filter do
        // modded original.
        maxWeightBox.setFilter(text -> text.matches(SheetData.InventoryItem.WEIGHT_PATTERN));
        maxWeightBox.setResponder(this::onMaxWeightTyped);
        addRenderableWidget(maxWeightBox);
        y += rowH;

        // Linha do resumo. Nasce preenchida com o que a ficha tem, e reescrita no
        // applyExtraState (que e quem recebe o eco do servidor).
        weightSummaryIndex = textLines.size();
        textLines.add(new TextLine("", x, y + labelOffset(), COL_BOX_TEXT));
        y += rowH;

        addRenderableWidget(Button.builder(Component.literal("+ Item"), b -> openItemForm(-1, null))
                .bounds(x, y, w, rowH - 2)
                .tooltip(Tooltip.create(Component.literal("Novo item do inventario")))
                .build());
        y += rowH;

        rightListTop = Math.max(y, top);

        // Mede o conteudo inteiro ANTES de montar: e o que o clamp da rolagem
        // precisa, e medir montando criaria widget para todo item da lista.
        List<SheetData.InventoryItem> items = inventoryItems();
        rightContentH = 0;
        for (SheetData.InventoryItem item : items) {
            rightContentH += rightItemHeight(item);
        }
        rightScroll = clampRightScroll(rightScroll);

        int itemY = rightListTop - rightScroll;
        // A caixa e mais estreita que a coluna para a barra nao encostar nela.
        rightBoxW = Math.max(20, w - BAR_W - BAR_GAP);
        for (int index = 0; index < items.size(); index++) {
            int h = rightItemHeight(items.get(index));
            // "Pelo menos um pedaco visivel" e nao "inteiro visivel" (pedido do
            // usuario em 30/09/2026): com o corte antigo, um item sumia inteiro
            // da tela e a lista dava a impressao de buraco. Quem corta e recorta
            // e o {@link #addInventoryItem}.
            if (itemY + h > rightListTop && itemY < rightListBottom) {
                addInventoryItem(x, itemY, rightBoxW, items.get(index), index);
            }
            itemY += h;
        }

        // A barra ocupa a folga interna da borda direita da caixa dos itens. So
        // e montada aqui, com a altura VISIVEL da lista -- o trilho e o pedaco
        // que da para ver, e nao o conteudo inteiro.
        rightBarX = x + w - BAR_W;
        rightBarH = Math.max(0, rightListBottom - rightListTop);
    }

    /**
     * Monta um item da lista: nome + peso, tipo, ate 2 linhas de descricao e os
     * botoes Edit e Del.
     *
     * <p><b>O texto entra em {@code textLines} e nao no {@code render}:</b> o
     * conteudo so muda quando a ficha muda, e a ficha so chega pelo
     * {@code rebuildWidgets} (que recria {@code textLines}). Desenhar por item a
     * cada frame seria o mesmo preco do {@code drawPericias} sem nenhum ganho --
     * aqui nao ha valor otimista, so o que o servidor mandou.
     */
    private void addInventoryItem(int x, int y, int w, SheetData.InventoryItem item, int index) {
        // A caixa e recortada na faixa visivel da lista: o item pode entrar e
        // sair pela borda, mas o fundo dele nunca pinta em cima do cabecalho nem
        // do rodape. E o que faz o item aparecer "pela metade" em vez de sumir.
        int boxH = rightItemHeight(item) - INV_ITEM_GAP;
        int boxTop = Math.max(y, rightListTop);
        int boxBottom = Math.min(y + boxH, rightListBottom);
        if (boxBottom <= boxTop) {
            return;
        }
        itemBoxes.add(new ItemBox(x, boxTop, w, boxBottom - boxTop));
        y += INV_PAD;

        // Texto recuado da borda: `tx` e a margem esquerda e `tw` e a largura
        // que sobra dentro da caixa.
        int tx = x + INV_PAD_X;
        int tw = rightTextW(w);

        String weight = item.weightText();
        int weightW = this.font.width(weight);
        // Toda linha passa por `lineInList`: um item que entra pela borda de
        // cima tem as primeiras linhas ACIMA da lista, e sem o guarda esse texto
        // ia por cima do cabecalho e do botao "+ Item" -- o mesmo texto-por-cima-
        // do-botao que a coluna esquerda ja tinha sofrido (bug do usuario em
        // 30/09/2026).
        if (lineInList(y, INV_NAME_H)) {
            textLines.add(new TextLine(truncateWithEllipsis(item.name(), Math.max(8, tw - weightW - 4)),
                    tx, y, COL_LABEL));
            textLines.add(new TextLine(weight, x + w - INV_PAD_X - weightW, y, COL_BOX_TEXT));
        }
        y += INV_NAME_H;

        if (!item.type().isEmpty() && lineInList(y, INV_NAME_H)) {
            textLines.add(new TextLine(truncateWithEllipsis(item.type(), tw), tx, y, COL_MUTED));
        }
        y += INV_NAME_H;

        int lines = rightDescLines(item);
        if (lines > 0) {
            List<FormattedCharSequence> parts =
                    this.font.split(Component.literal(item.description()), tw);
            for (int i = 0; i < lines; i++) {
                FormattedCharSequence part = parts.get(i);
                // Sobrou texto depois da ultima linha desenhada: sinaliza com as
                // reticencias, para o jogador saber que a descricao esta cortada
                // e nao que acaba ali.
                if (i == lines - 1 && parts.size() > INV_DESC_LINES) {
                    // Nao usar `part + TRUNCATION_MARK`: `part` nao e String, e o
                    // `+` do Java resolve por String.valueOf(part), o que
                    // desenhava o objeto lambda do client no lugar do texto (bug
                    // do usuario em 30/09/2026).
                    part = FormattedCharSequence.forward(plainText(part) + TRUNCATION_MARK,
                            Style.EMPTY);
                }
                if (lineInList(y, INV_DESC_ADV)) {
                    textLines.add(new TextLine(part, tx, y, COL_MUTED));
                }
                y += INV_DESC_ADV;
            }
        }

        // Botao so quando a linha inteira cabe na faixa visivel: um botao cortado
        // pela borda seria clicavel sem o jogador ve-lo.
        if (lineInList(y, INV_BTN_H)) {
            addRenderableWidget(Button.builder(Component.literal("Edit"), b -> openItemForm(index, item))
                    .bounds(tx, y, INV_BTN_W, INV_BTN_H)
                    .tooltip(Tooltip.create(Component.literal("Editar este item")))
                    .build());
            Button del = addRenderableWidget(Button.builder(
                            delPendingIndex == index ? Component.literal("Del?") : Component.literal("Del"),
                            b -> onDeleteItem(index))
                    .bounds(tx + tw - INV_BTN_W, y, INV_BTN_W, INV_BTN_H)
                    .tooltip(Tooltip.create(Component.literal(
                            delPendingIndex == index
                                    ? "Clique de novo para confirmar"
                                    : "Apagar este item")))
                    .build());
            if (delPendingIndex == index) {
                delPendingBox = new ItemBox(del.getX(), del.getY(), del.getWidth(), del.getHeight());
            }
        }
    }

    /**
     * Achata um {@link FormattedCharSequence} em {@link String}.
     *
     * <p><b>Por que existe (bug do usuario em 30/09/2026):</b> o codigo fazia
     * {@code part + TRUNCATION_MARK} num {@code part} que e
     * {@code FormattedCharSequence}. O {@code +} do Java so aceita String de um
     * dos lados, entao ele chamava {@code String.valueOf(part)} e a lista
     * mostrava {@code net.minecraft.util.FormattedCharSequence$Lambda/0x...@...}
     * no lugar da ultima linha da descricao -- e, com isso, o resto do texto
     * sumia. Percorrer a sequencia e o jeito de ler os code points de verdade.
     */
    private static String plainText(FormattedCharSequence sequence) {
        StringBuilder out = new StringBuilder();
        sequence.accept((index, style, codePoint) -> {
            out.appendCodePoint(codePoint);
            return true;
        });
        return out.toString();
    }

    /** Quantas linhas de descricao o item ocupa (0, 1 ou o teto de 2). */
    private int rightDescLines(SheetData.InventoryItem item) {
        if (item.description().isEmpty()) {
            return 0;
        }
        List<FormattedCharSequence> parts =
                this.font.split(Component.literal(item.description()), rightTextW(rightBoxW));
        return Math.min(INV_DESC_LINES, parts.size());
    }

    /** Altura do bloco de um item, a mesma que o {@code buildInfoTab} mediu. */
    private int rightItemHeight(SheetData.InventoryItem item) {
        return INV_PAD * 2 + INV_NAME_H * 2 + rightDescLines(item) * INV_DESC_ADV
                + INV_BTN_H + INV_ITEM_GAP;
    }

    /**
     * Desenha trilho e polegar da lista, no mesmo estilo do {@code SkillsScreen}.
     *
     * <p><b>Proporcional ao conteudo, como no popup do SkillsScreen:</b> quanto
     * mais itens, menor o polegar, com minimo de 8 px para continuar clicavel.
     *
     * <p>Desenhado no {@link #render} e nao no {@code addInventoryColumn}, pelo
     * mesmo motivo do SkillsScreen: a montagem guarda a <b>geometria</b> do
     * trilho e o quadro o <b>pinta</b>. Roleira e grafico nao sao o mesmo dado.
     */
    private void renderRightScrollbar(GuiGraphics graphics) {
        rightThumbH = 0;
        if (activeTab != 1 || rightBarH <= 0 || maxRightScroll() <= 0) {
            return;
        }
        graphics.fill(rightBarX, rightListTop, rightBarX + BAR_W,
                rightListTop + rightBarH, COL_SCROLL_TRACK);
        rightThumbH = Math.max(8, rightBarH * rightBarH / Math.max(1, rightContentH));
        if (rightThumbH > rightBarH) {
            rightThumbH = rightBarH;
        }
        int desloca = rightBarH - rightThumbH;
        rightThumbY = rightListTop
                + (maxRightScroll() == 0 ? 0 : desloca * rightScroll / maxRightScroll());
        graphics.fill(rightBarX, rightThumbY, rightBarX + BAR_W,
                rightThumbY + rightThumbH, COL_SCROLL_THUMB);
    }

    /** O cursor esta sobre a barra da lista? */
    private boolean onRightScrollbar(double mouseX, double mouseY) {
        if (activeTab != 1 || rightBarH <= 0 || maxRightScroll() <= 0) {
            return false;
        }
        return mouseX >= rightBarX - BAR_PAD && mouseX < rightBarX + BAR_W + BAR_PAD
                && mouseY >= rightListTop && mouseY < rightListTop + rightBarH;
    }

    /** Converte a posicao do mouse no trilho para o valor de scroll. */
    private void setRightScrollFromMouse(double mouseY) {
        int maxScroll = maxRightScroll();
        if (maxScroll <= 0) {
            rightScroll = 0;
            return;
        }
        int useful = Math.max(1, rightBarH - rightThumbH);
        int delta = (int) (mouseY - rightListTop - rightThumbH / 2);
        int next = clampRightScroll(delta * maxScroll / useful);
        if (next != rightScroll) {
            rightScroll = next;
            // Mesma regra da roda: a rolagem recria os widgets, e a marcacao do
            // Del aponta para um item que pode ter saido da tela.
            delPendingIndex = -1;
            rebuildWidgets();
        }
    }

    /** Os itens da ficha, ou lista vazia antes de a ficha chegar. */
    private List<SheetData.InventoryItem> inventoryItems() {
        return sheet == null ? List.of() : sheet.inventory().items();
    }

    /** Maior rolagem da lista: o que sobra quando o ultimo item chega no fim. */
    private int maxRightScroll() {
        return Math.max(0, rightContentH - Math.max(0, rightListBottom - rightListTop));
    }

    private int clampRightScroll(int value) {
        return Math.max(0, Math.min(value, maxRightScroll()));
    }

    /**
     * O mouse esta sobre a coluna do inventario? So ai a roda rola esta lista.
     *
     * <p>A area e a mesma faixa de {@link #maxRightScroll} ({@link
     * #rightListTop} a {@link #rightListBottom}), e nao a da coluna inteira: o
     * cabecalho (titulo, Max Weight, resumo, + Item) nao rola, e aceitar a roda
     * la cima rolar uma lista que o jogador nem esta vendo seria confuso.
     */
    private boolean isOverRightPanel(double mouseX, double mouseY) {
        return mouseX >= rightPanelX && mouseX < rightPanelX + rightPanelW
                && mouseY >= rightListTop && mouseY < rightListBottom;
    }

    /**
     * Um bloco da aba 2: o titulo e, abaixo dele, o quadro de texto com rolagem
     * (FASE 2A).
     *
     * <p><b>Por que {@code setShowBackground(true)} e o quadro inteiro:</b> o
     * proprio widget desenha o fundo e a borda, entao um retangulo desenhado por
     * fora aqui seria um fundo por cima do fundo dele.
     *
     * <p><b>Por que {@code setCharacterLimit} e nao {@code setLineLimit}:</b> o
     * primeiro conta o TOTAL de caracteres (e o teto que o servidor aplica, o
     * {@link SheetData#MAX_TEXT}); o segundo conta as linhas visiveis, que sao
     * quantas cabem na altura do bloco -- usalo como teto cortaria o texto no
     * numero de linhas que cabem na tela, e nao no limite do campo.
     *
     * <p><b>Por que o listener so marca e nao envia:</b> ver
     * {@link #pendingMultiLine}.
     */
    private void addMultiLineBlock(String field, int x, int w, int top, int bottom) {
        String title = SheetData.labelOf(field);
        int y = addSection(title, x, top, w);
        int boxH = Math.max(MIN_ROW_H, bottom - y);
        MultiLineEditBox box = MultiLineEditBox.builder()
                .setX(x)
                .setY(y)
                .setPlaceholder(Component.literal("write here"))
                .setTextColor(COL_BOX_TEXT)
                .setTextShadow(false)
                .setCursorColor(COL_BOX_TEXT)
                .setShowBackground(true)
                .setShowDecorations(true)
                .build(this.font, w, boxH, Component.literal(title));
        box.setCharacterLimit(SheetData.MAX_TEXT);
        // O MultiLineEditBox nao tem setEditable: active = false faz
        // isMouseOver() responder false (confirmado no bytecode do 1.21.11), o
        // que impede clicar, focar e portanto digitar. Mesmo padrao do
        // SkillsScreen.
        box.active = canEdit;
        box.setValueListener(value -> {
            if (suppressMultiLine) {
                return;
            }
            pendingMultiLine.add(field);
        });
        // **OBRIGATORIO**: sem este registro a caixa nao entra em
        // Screen.children() e nao recebe clique, foco, teclado nem rolagem
        // (a roda so chega no que o `getChildAt` encontra por hit test).
        addRenderableWidget(box);
        multiLineBoxes.put(field, box);
    }

    /**
     * Aba 1: a ficha atual inteira, as duas colunas (esquerda com identidade,
     * vida, Mana, progresso e atributos; direita com as pericias).
     *
     * <p>Nada da logica de layout mudou em 30/09/2026 -- a unica coisa que a fase
     * das abas fez aqui foi trocar o nome do metodo e chamar por ele. O
     * {@code bottomY} que chega ja vem lessenado pela barra de abas.
     */
    private void buildCharacterTab(int x0, int panelW, int topY, int bottomY) {
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
        // Level e CA dividem a MESMA linha (01/10/2026, por pedido do usuario).
        // Cada metade tem o seu rotulo ("Level" e o rotulo do CA, que o Mestre
        // edita no Sheet Editor) e a sua caixa numerica.
        y = addFieldPair("level", "ca", x0, drawY(y), leftW, labelW, boxW);

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

    /**
     * As duas setinhas de navegacao, nas pontas da faixa reservada no rodape
     * (30/09/2026).
     *
     * <p>Sao as duas unicas coisas que trocam de aba: o nome da aba ativa vai no
     * meio e e texto, nao botao (ver o {@code render} desta tela), entao ele nao
     * entra aqui.
     *
     * <p><b>Por que {@code "<"} e {@code ">"} e nao setas:</b> a fonte padrao do
     * Minecraft nao tem glifo de seta, e o que apareceria no lugar seria um
     * quadradinho. O par e o mesmo dos botoes de passo que a ficha ja usa, entao
     * a tela inteira fala a mesma coisa.
     *
     * <p>A setinha da ponta que nao tem pagina ({@code active = false}) e o
     * feedback de que acabou: sem isso o jogador clica e nada acontece, sem
     * entender se o botao quebrou.
     */
    private void addTabBar(int x0, int panelW, int bottomY) {
        int y = bottomY - TAB_BAR_H;
        Button prev = addRenderableWidget(Button.builder(Component.literal("<"), b -> changeTab(-1))
                .bounds(x0, y, TAB_ARROW_W, TAB_BAR_H)
                .tooltip(Tooltip.create(Component.literal("Aba anterior")))
                .build());
        Button next = addRenderableWidget(Button.builder(Component.literal(">"), b -> changeTab(1))
                .bounds(x0 + panelW - TAB_ARROW_W, y, TAB_ARROW_W, TAB_BAR_H)
                .tooltip(Tooltip.create(Component.literal("Proxima aba")))
                .build());
        prev.active = activeTab > 0;
        next.active = activeTab < tabCount() - 1;
    }

    /**
     * Troca de aba, guardando o scroll de cada uma (30/09/2026).
     *
     * <p>A memoria das duas colunas ({@link #tabLeftScroll} e {@link #tabPerScroll})
     * e salva e carregada aqui, e nao no {@code buildPanel}: o {@code init()} e
     * chamado por motivos que nao tem nada a ver com troca de aba (resize, modelo
     * novo, rolagem), e fazer a troca dentro dele perderia a posicao de qualquer
     * um desses caminhos. O {@code clamp} de cada aba acontece no proprio
     * {@code buildPanel}, ja com a geometria da aba recem-montada.
     */
    private void changeTab(int delta) {
        int next = Math.max(0, Math.min(activeTab + delta, tabCount() - 1));
        if (next == activeTab) {
            return;
        }
        // Sai da aba 1 (ou 2): o texto grande que o jogador escreveu e enviado
        // AGORA, porque o rebuildWidgets() logo abaixo destroe as caixas e o
        // texto junto com elas.
        flushMultiLineBoxes();
        flushMaxWeight();
        flushCd();
        tabLeftScroll[activeTab] = leftScroll;
        tabPerScroll[activeTab] = perScroll;
        // A marcacao do Del e da ABA que esta saindo: o segundo clique que
        // confirmaria a exclusao nao pode sobreviver para a aba outra, onde o
        // mesmo indice e outro item. Vale para as DUAS listas da aba 3 tambem
        // (01/10/2026): o indice guardado e o mesmo numero em outra lista.
        delPendingIndex = -1;
        skillColumn.delPending = -1;
        spellColumn.delPending = -1;
        activeTab = next;
        leftScroll = tabLeftScroll[activeTab];
        perScroll = tabPerScroll[activeTab];
        rebuildWidgets();
    }

    /**
     * Envia o texto grande que ficou pendente e so depois recria os widgets
     * (FASE 2A, 30/09/2026).
     *
     * <p><b>Por que o flush e AQUI e nao so no {@link #changeTab}:</b> o
     * {@code rebuildWidgets()} e o funil de TODO caminho que destroi os widgets
     * desta tela -- troca de aba, resize, scroll das colunas, modelo novo do
     * Mestre. O texto pendente vive dentro do widget, entao qualquer um desses
     * caminhos perderia a ultima digitacao. O {@code changeTab} chama o flush
     * tambem, e a segunda chamada e um no-op: {@link #flushMultiLineBoxes}
     * limpa a marca de pendente, entao nao ha envio em duplicata.
     */
    @Override
    protected void rebuildWidgets() {
        flushMultiLineBoxes();
        flushMaxWeight();
        flushCd();
        super.rebuildWidgets();
    }

    /**
     * Qualquer saida de tela passa por aqui: ESC, botao Back, inventario, outra
     * tela do mod, queda da conexao (FASE 2A).
     *
     * <p><b>Por que {@code removed()} e nao {@code onClose()}:</b> o
     * {@code Minecraft.setScreen} chama {@code removed()} em <b>qualquer</b>
     * troca de tela, e nao so no caminho do ESC -- o mesmo motivo que fez o
     * {@code SheetEditorScreen} registrar o rascunho em {@code removed()} e nao
     * no botao. Com o flush aqui, fechar a ficha (qualquer jeito) nao perde a
     * ultima digitacao, e nao existe um caminho de fechamento paralelo.
     */
    @Override
    public void removed() {
        flushMultiLineBoxes();
        flushMaxWeight();
        // 01/10/2026: a CD da pagina 3 e o terceiro texto com o mesmo problema --
        // a caixa e destruida por QUALQUER saida de tela, nao so pela troca de aba.
        flushCd();
        super.removed();
    }

    /**
     * Envia o texto pendente dos quadros grandes (FASE 2A).
     *
     * <p>Soh este metodo envia, e so o que esta marcado em
     * {@link #pendingMultiLine}: digitar nao vira pacote (decisao do usuario, o
     * limite e 2000 caracteres). A marca e limpa sempre, mesmo sem permissao de
     * edicao, para que um texto marcado antes do {@code canEdit} virar {@code
     * false} nao fique esperando para sempre.
     */
    private void flushMultiLineBoxes() {
        if (pendingMultiLine.isEmpty()) {
            return;
        }
        if (canEdit) {
            for (String field : pendingMultiLine) {
                MultiLineEditBox box = multiLineBoxes.get(field);
                if (box != null) {
                    ClientPlayNetworking.send(new RpgNetworking.SheetFieldPayload(
                            targetName, field, box.getValue()));
                }
            }
        }
        pendingMultiLine.clear();
    }

    /**
     * O clique tira o foco das caixas grandes? Entao e hora de enviar (FASE 2A).
     *
     * <p>Um dos tres momentos de saida do texto: sair do campo, trocar de aba e
     * fechar a tela. O teste e o mesmo do roteamento do clique no vanilla
     * ({@code getChildAt} -> {@code isMouseOver}), entao "o clique foi na outra
     * caixa" tambem conta como saida: sair do Appearance para o Backstory
     * envia o Appearance.
     */
    private void flushMultiLineOnFocusLoss(double mouseX, double mouseY) {
        for (MultiLineEditBox box : multiLineBoxes.values()) {
            if (box.isFocused() && !box.isMouseOver(mouseX, mouseY)) {
                flushMultiLineBoxes();
                return;
            }
        }
    }

    /**
     * O clique e o terceiro momento de saida do texto grande (FASE 2A).
     *
     * <p><b>Por que o flush e ANTES do {@code super}:</b> e o {@code super} que
     * move o foco para o widget clicado, entao depois do {@code super} o quadro
     * ja perdeu o foco e nao daria para saber que era ele que estava sendo
     * digitado. Antes, o unico quadro que pode estar com o foco e o que o
     * clique nao acertou.
     */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // A barra tem prioridade sobre o `super`: ela e uma barra mesmo, e o
        // clique nela e arrasto de barra, nao clique em widget.
        if (onRightScrollbar(event.x(), event.y())) {
            flushMultiLineOnFocusLoss(event.x(), event.y());
            draggingRightBar = true;
            setRightScrollFromMouse(event.y());
            return true;
        }
        // 01/10/2026: as duas listas da aba 3 tem barra propria. A coluna da
        // skills vem primeiro porque e a da esquerda, entao e a que o jogador
        // alcança primeiro com o cursor.
        Column grabbed = columnUnderBar(event.x(), event.y());
        if (grabbed != null) {
            flushMultiLineOnFocusLoss(event.x(), event.y());
            grabbed.draggingBar = true;
            grabbed.scrollFromMouse(event.y());
            // 01/10/2026: o PRIMEIRO clique tambem remonta. Sem isto, apertar a
            // barra sem ainda mover o mouse deixava o `scroll` ja mudado com os
            // widgets ainda no lugar antigo: a barra saltava e a lista ficava um
            // frame atras dela. So o `mouseDragged` remontava, entao o erro
            // durava ate o primeiro pixel de arrasto -- e "arrastar nao funciona"
            // comeca exatamente assim, num clique que parece nao ter arrastado.
            flushCd();
            grabbed.delPending = -1;
            rebuildWidgets();
            return true;
        }
        flushMultiLineOnFocusLoss(event.x(), event.y());
        return super.mouseClicked(event, doubleClick);
    }

    /**
     * A lista da aba 3 cuja barra esta sob o cursor, ou {@code null}.
     *
     * <p><b>A INTERACAO ja era filtrada por aba, e o desenho nao (bug corrigido
     * em 01/10/2026):</b> este {@code activeTab != 2} ja existia, entao o clique e
     * o arrasto do polegar nunca valiam uma aba que nao estava na tela -- era so
     * o {@code render} que desenhava trilho e polegar por cima do conteudo de
     * outra aba, com a geometria herdada da ultima montagem da aba 3. Quem ler
     * depois nao deve concluir que "o clique funciona e o desenho nao": os dois
     * lados tem guarda de aba hoje, e ele existe justamente porque
     * {@code layout} so roda na montagem da aba 3.
     */
    private Column columnUnderBar(double mouseX, double mouseY) {
        if (activeTab != 2) {
            return null;
        }
        if (skillColumn.onScrollbar(mouseX, mouseY)) {
            return skillColumn;
        }
        return spellColumn.onScrollbar(mouseX, mouseY) ? spellColumn : null;
    }

    /**
     * Arrasto do polegar: o mesmo caminho de {@link #setRightScrollFromMouse}.
     *
     * <p><b>Por que as duas colunas da aba 3 remontam a tela (bug do usuario em
     * 01/10/2026: "as barras de scroll nao funcionam o segurar e arrastar ao
     * invés do scroll do mouse"):</b> a lista delas <b>nao e um viewport com
     * clip</b>, e um conjunto de widgets recriados a cada montagem. O
     * {@code scrollFromMouse} so ATRIBUI o campo {@code scroll}, e sem remontar
     * nada a tela continua com os widgets velhos nas mesmas posicoes -- o
     * polegar andava e a lista nao. A roda ja fazia certo por um motivo
     * explicito: o {@link #scrollColumn} chama o {@code rebuildWidgets}.
     *
     * <p><b>Por que o {@code flushCd} vem ANTES do {@code rebuildWidgets}:</b> e
     * o mesmo motivo do {@link #flushMultiLineBoxes} -- o {@code rebuildWidgets}
     * destroi a caixa da CD junto com o texto meio digitado que o jogador ainda
     * nao enviou. Aqui e por seguranca, e nao por correcao de um caso observavel:
     * a roda ja chamava o {@code flushCd}, e o arrasto recria widgets igual.
     *
     * <p><b>Por que o {@code draggingBar} sobrevive ao {@code rebuildWidgets}:</b>
     * ele e um campo da {@link Column}, e nao um widget. conferido no arquivo: o
     * unico lugar que escreve nele e o proprio arrasto -- o {@code true} no
     * {@code mouseClicked} e o {@code false} no {@code mouseReleased}. Nem a
     * {@code buildPanel}, nem a {@code addSkillColumn}, nem a
     * {@code addSpellColumn} o tocam, e o {@code scroll} so e reatribuido pelo
     * {@code clampScroll} delas. E o que mantem o arrasto vivo do primeiro pixel
     * ao ultimo.
     *
     * <p><b>Custo conhecido:</b> um {@code rebuildWidgets} por pixel de arrasto.
     * E o preco de a lista ser feita de widgets em vez de um viewport com clip, e
     * e o mesmo que a roda ja paga -- trocar isso por um viewport seria uma
     * mudanca de arquitetura bem maior do que o bug pede.
     */
    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (draggingRightBar) {
            setRightScrollFromMouse(event.y());
            return true;
        }
        if (skillColumn.draggingBar) {
            skillColumn.scrollFromMouse(event.y());
            flushCd();
            // A marcacao do Del aponta para um item que pode ter saido da tela.
            skillColumn.delPending = -1;
            rebuildWidgets();
            return true;
        }
        if (spellColumn.draggingBar) {
            spellColumn.scrollFromMouse(event.y());
            flushCd();
            // Mesma regra do bloco da skill acima.
            spellColumn.delPending = -1;
            rebuildWidgets();
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (draggingRightBar) {
            draggingRightBar = false;
            return true;
        }
        if (skillColumn.draggingBar) {
            skillColumn.draggingBar = false;
            return true;
        }
        if (spellColumn.draggingBar) {
            spellColumn.draggingBar = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    /**
     * Preenche os quadros grandes com o que o servidor tem (FASE 2A).
     *
     * <p><b>Por que nao sobrescreve a caixa com o foco:</b> mesma regra do
     * {@code EditBox} no {@code applySheetToWidgets} da base. O eco do servidor
     * chega enquanto o jogador ainda esta digitando (ele so envia ao sair do
     * campo), e escrever por cima do texto parcial brigaria com o cursor -- o
     * que apagaria o que esta sendo escrito. Quem esta digitando manda no
     * proprio campo; o servidor so preenche os outros.
     */
    private void applyMultiLineBoxes() {
        if (sheet == null) {
            return;
        }
        suppressMultiLine = true;
        try {
            for (Map.Entry<String, MultiLineEditBox> entry : multiLineBoxes.entrySet()) {
                MultiLineEditBox box = entry.getValue();
                box.active = canEdit;
                if (box.isFocused()) {
                    continue;
                }
                String next = sheet.getText(entry.getKey());
                // So escreve se mudou: evita reposicionar o cursor e a rolagem
                // a cada tecla digitada pelo proprio usuario.
                if (!next.equals(box.getValue())) {
                    box.setValue(next);
                }
            }
        } finally {
            suppressMultiLine = false;
        }
    }

    // ------------------------------------------------------------------
    // INVENTARIO: LIMITE DE PESO, LISTA E CONFIRMACAO DO DEL (FASE 2B)
    // ------------------------------------------------------------------

    /**
     * O jogador digitou na caixa do limite de peso.
     *
     * <p><b>Envia a cada tecla que ja e um numero, e marca como pendente o que
     * ainda nao e:</b> o texto "12." e o que existe entre digitar o ponto e o
     * proximo digito, e ele nao parseia. Marcar so ele e o que evita gravar o
     * limite antigo de novo e fazer o eco reescrever a caixa com o cursor no meio.
     *
     * <p><b>O flush e o {@link #flushMaxWeight()}, e nao este metodo</b> (ver o
     * Javadoc dele), porque o {@code rebuildWidgets} destroi a caixa com o texto
     * dentro.
     */
    private void onMaxWeightTyped(String value) {
        if (suppressMaxWeight || !canEdit) {
            return;
        }
        if (parseableWeight(value)) {
            sendMaxWeight(value);
        } else {
            pendingMaxWeight = true;
        }
    }

    /** O texto e um numero completo? "12." ainda nao e, e "12.5" e. */
    private boolean parseableWeight(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        try {
            Float.parseFloat(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void sendMaxWeight(String value) {
        pendingMaxWeight = false;
        ClientPlayNetworking.send(new RpgNetworking.SheetFieldPayload(targetName, "maxWeight", value));
    }

    /**
     * Envia o limite de peso que ficou marcado (FASE 2B).
     *
     * <p><b>E o mesmo funil do {@link #flushMultiLineBoxes}, e pelos mesmos
     * motivos:</b> o {@code rebuildWidgets} e o caminho que destroi a caixa (troca
     * de aba, resize, rolagem da lista, estado novo do servidor), e o texto
     * pendente vive dentro dela. Sem este flush, trocar de aba com "12." na caixa
     * perderia o que o jogador digitou.
     *
     * <p><b>E um flush PROPRIO, e nao uma entrada a mais em
     * {@code flushMultiLineBoxes}:</b> os dois guardam valores diferentes (a caixa
     * e um {@code EditBox} de uma linha, os quadros sao {@code MultiLineEditBox})
     * e a marca de pendente e de um valor so, nao de um conjunto.
     */
    private void flushMaxWeight() {
        if (!pendingMaxWeight) {
            return;
        }
        if (canEdit && maxWeightBox != null) {
            // O que nao parseia vai formatado a partir do que parseia: se o
            // jogador parou em "12.", o que ele quis dizer e 12, e nao 0.
            sendMaxWeight(SheetData.formatWeight(parseWeightOrZero(maxWeightBox.getValue())));
        }
        pendingMaxWeight = false;
    }

    private static float parseWeightOrZero(String value) {
        try {
            return Float.parseFloat(value == null ? "" : value.trim());
        } catch (NumberFormatException e) {
            return 0f;
        }
    }

    /**
     * Preenche a caixa do limite de peso com o que o servidor tem (FASE 2B).
     *
     * <p><b>Nao sobrescreve a caixa com o foco:</b> a mesma regra do
     * {@code EditBox} da base e dos quadros grandes. O eco chega enquanto o
     * jogador ainda esta digitando, e o texto do servidor vem formatado
     * ("12.00"), entao escrever por cima transformaria o "12." que ele esta
     * digitando num "12.00" -- que o filtro de digito do jogador rejeitaria de
     * volta.
     */
    private void applyMaxWeightBox() {
        if (maxWeightBox == null) {
            return;
        }
        maxWeightBox.setEditable(canEdit);
        if (sheet == null || maxWeightBox.isFocused()) {
            return;
        }
        String next = sheet.getText("maxWeight");
        if (!next.equals(maxWeightBox.getValue())) {
            suppressMaxWeight = true;
            try {
                maxWeightBox.setValue(next);
            } finally {
                suppressMaxWeight = false;
            }
        }
    }

    /**
     * Reescreve a linha do resumo do peso (FASE 2B).
     *
     * <p><b>O vermelho vem so do {@link SheetData.Inventory#overweight()}:</b> o
     * excesso e aviso, nunca bloqueio. Nenhuma regra de "carga maxima" existe no
     * codigo -- quem decide se o personagem pode carregar o que carrega e o Mestre,
     * em jogo, e o resumo existe para ele enxergar o numero.
     *
     * <p>E chamada no {@code applyExtraState} (que e quem recebe o eco) e nao no
     * render: o {@code textLines} ja foi desenhado quando o {@code renderContent}
     * roda, entao escrever la deixaria a cor e o total 1 frame atras.
     */
    private void applyWeightSummary() {
        if (weightSummaryIndex < 0 || weightSummaryIndex >= textLines.size() || sheet == null) {
            return;
        }
        SheetData.Inventory inventory = sheet.inventory();
        String text = "Weight: " + SheetData.formatWeight(inventory.totalWeight())
                + " / " + SheetData.formatWeight(inventory.maxWeight());
        TextLine current = textLines.get(weightSummaryIndex);
        textLines.set(weightSummaryIndex, new TextLine(text, current.x(), current.y(),
                inventory.overweight() ? COL_DOWNED : COL_BOX_TEXT));
    }

    /**
     * Abre o formulario do item: novo quando {@code index} e {@code -1}, edicao
     * quando nao (FASE 2B).
     */
    private void openItemForm(int index, SheetData.InventoryItem item) {
        delPendingIndex = -1;
        this.minecraft.setScreen(new InventoryItemScreen(this, targetName, index, item));
    }

    /**
     * O clique no Del: o primeiro so marca, o segundo no mesmo indice apaga
     * (FASE 2B).
     *
     * <p><b>Por que confirmar:</b> apagar e o unico desta coluna sem volta -- o
     * texto do item vai junto com ele -- e o botao fica do tamanho de "Del", ao
     * lado de outros da mesma coluna.
     */
    private void onDeleteItem(int index) {
        if (index < 0 || index >= inventoryItems().size()) {
            delPendingIndex = -1;
            return;
        }
        if (delPendingIndex != index) {
            delPendingIndex = index;
            rebuildWidgets();
            return;
        }
        delPendingIndex = -1;
        if (canEdit) {
            ClientPlayNetworking.send(RpgNetworking.SheetItemPayload.remove(targetName, index));
        }
    }

    /**
     * Desenha a moldura vermelha no Del que espera o segundo clique (FASE 2B).
     *
     * <p><b>Uma moldura, e nao o fundo:</b> o {@code renderContent} roda DEPOIS
     * de {@code super.render}, que e quem desenhou os widgets. PIntar o fundo do
     * botao aqui cobriria o texto "Del?"; a moldura de 1px marca o botao sem
     * passar por cima de nada.
     */
    private void drawDelConfirm(GuiGraphics graphics) {
        drawDelFrame(graphics, delPendingBox);
        // 01/10/2026: as duas listas da aba 3 tem a propria marcacao, e so uma
        // delas pode estar marcada por vez -- mas nao e uma exclusao mutua: cada
        // coluna se marca sozinha, e as duas molduras podem coexistir.
        drawDelFrame(graphics, skillColumn.delPendingBox);
        drawDelFrame(graphics, spellColumn.delPendingBox);
    }

    /** A moldura vermelha do Del marcado, ou nada se {@code box} for nulo. */
    private void drawDelFrame(GuiGraphics graphics, ItemBox box) {
        if (box == null) {
            return;
        }
        graphics.fill(box.x(), box.y(), box.x() + box.w(), box.y() + 1, COL_DOWNED);
        graphics.fill(box.x(), box.y() + box.h() - 1, box.x() + box.w(), box.y() + box.h(), COL_DOWNED);
        graphics.fill(box.x(), box.y(), box.x() + 1, box.y() + box.h(), COL_DOWNED);
        graphics.fill(box.x() + box.w() - 1, box.y(), box.x() + box.w(), box.y() + box.h(), COL_DOWNED);
    }

    /**
     * Cumpre o pedido de remontar a lista quando o inventario mudou (FASE 2B).
     *
     * <p><b>E AQUI, e nao no {@code onSheetReceived}:</b> remontar recria todos os
     * widgets da tela, e na aba 1 o jogador pode estar com o cursor num quadro de
     * texto grande -- o texto que ele ainda nao enviou morreria com a caixa. O
     * pedido fica marcado e e cumprido no primeiro frame em que nada da coluna
     * esta com o foco.
     *
     * <p>Por que o rebuild sai logo depois: o {@code textLines} deste frame ja foi
     * desenhado com a lista antiga (o {@code renderContent} roda no fim do
     * {@code render}), entao continuar desenhando aqui seria por cima da lista nova.
     * O proximo frame ja sai correto.
     */
    private void maybeRebuildInventory() {
        if (inventoryRebuildPending && activeTab == 1 && !isEditingInventoryText()) {
            inventoryRebuildPending = false;
            rebuildWidgets();
            return;
        }
        // 01/10/2026: a aba 3 tem o mesmo problema e a mesma solucao, com uma
        // caixa a mais que pode estar com o foco (a CD).
        if (spellbookRebuildPending && activeTab == 2 && cdBox != null && cdBox.isFocused()) {
            return;
        }
        if (spellbookRebuildPending) {
            spellbookRebuildPending = false;
            rebuildWidgets();
        }
    }

    /** O jogador esta digitando em alguma caixa da coluna do inventario? */
    private boolean isEditingInventoryText() {
        if (maxWeightBox != null && maxWeightBox.isFocused()) {
            return true;
        }
        for (MultiLineEditBox box : multiLineBoxes.values()) {
            if (box.isFocused()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        // As caixinhas ANTES de super.render(): e la dentro que o texto das
        // linhas e os widgets sao desenhados, entao so assim o texto fica por
        // cima da caixa. O fundo do painel ja foi pintado antes deste metodo
        // (renderBackground), entao a caixa aparece sobre ele e nao some.
        for (ItemBox box : itemBoxes) {
            graphics.fill(box.x(), box.y(), box.x() + box.w(), box.y() + box.h(), COL_ITEM_BG);
        }

        super.render(graphics, mouseX, mouseY, delta);

        // O nome da aba e o unico texto desta tela desenhado FORA de
        // `textLines`, e e depois de super.render() de proposito: dentro da lista
        // ele rolaria junto com o conteudo, e um rotulo que sobe e desce com a
        // coluna deixa de dizer em que pagina o jogador esta. Aqui ele fica
        // parado na barra. O `bottomY` do addTabBar e o `contentBottom` que a
        // base calculou, entao a mesma conta posiciona o texto e os botoes.
        String tab = TAB_NAMES[activeTab];
        int x = panelX + (panelW - this.font.width(tab)) / 2;
        int y = contentBottom - TAB_BAR_H + (TAB_BAR_H - 8) / 2;
        graphics.drawString(this.font, tab, x, y, COL_SECTION, false);

        // A barra vai DEPOIS do texto e dos widgets: ela mora na folga da borda
        // direita, mas um polegar sobre a caixa de um item tem de ficar por cima.
        renderRightScrollbar(graphics);
        // 01/10/2026: as duas listas da aba 3 tambem desenham a barra depois dos
        // widgets, pelo mesmo motivo.
        //
        // <p><b>Por que a guarda de aba e OBRIGATORIA (bug do usuario, mesma
        // data):</b> ate aqui so importava a ORDEM do desenho. A CAUSA RAIZ e
        // outra: a geometria da barra ({@code contentH}, {@code barH},
        // {@code thumbY}, {@code thumbH}) so e recalculada por
        // {@code skillColumn.layout} / {@code spellColumn.layout}, e esses dois
        // rodam DENTRO de {@code addSkillColumn} / {@code addSpellColumn} --
        // ou seja, somente quando a aba 3 e montada. {@code buildInfoTab} (abas
        // 1 e 2) nao toca nessas colunas: sobram {@code contentH} e
        // {@code barH} da ultima montagem, {@code maxScroll()} continua maior
        // que zero e {@code renderBar} desenhava trilho e polegar por cima do
        // conteudo da aba 1/2. Foi o relato do jogador: "a barra da 3a pagina
        // continuava desenhada sobre a 2a".
        //
        // <p><b>Por que guarda de ABA e nao de "a coluna foi montada":</b> e o
        // mesmo criterio que a INTERACAO ja usava ({@link #columnUnderBar} e o
        // bloco {@code activeTab == 2} do {@code mouseScrolled}), para desenho e
        // clique contarem a mesma historia. Sem a guarda aqui, o clique era
        // filtrado e o desenho nao: a barra parecia viva sem fazer nada.
        if (activeTab == 2) {
            skillColumn.renderBar(graphics);
            spellColumn.renderBar(graphics);
        }
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
     * Esta tela nao tem mais nenhum atalho no rodape (01/10/2026).
     *
     * <p><b>Por que o "Skills" do mestre sumiu:</b> ele existia porque as
     * habilidades viviam numa tela a parte, que o mestre so alcanzava por ali --
     * o jogador tinha o botao no menu. Agora as skills (e as magias) estao na
     * <b>terceira aba da ficha</b>, que o mestre alcanca pelas setinhas como
     * qualquer outra pagina. O atalho viraria um botao a mais levando a lugar
     * que a aba 3 ja ocupa, e ele abriria uma tela que vai ser apagada.
     */
    @Override
    protected Button buildFooterExtra(int x, int y, int w, int h) {
        return null;
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
        // Os quadros de texto da aba 2 nao estao no `fieldBoxes` da base, entao
        // nao seriam preenchidos pelo laco de cima: e aqui que eles recebem o
        // estado do servidor (FASE 2A).
        applyMultiLineBoxes();
        // Mesma coisa na coluna do inventario (FASE 2B): a caixa do limite de
        // peso e a linha do resumo tambem nao estao no `fieldBoxes`.
        applyMaxWeightBox();
        applyWeightSummary();
        // 01/10/2026 (pagina 3): a caixa da CD e a linha do Modificador tambem
        // nao estao no `fieldBoxes` da base, entao recebem o eco do servidor aqui.
        applySpellHeader();
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
        // 30/09/2026 (FASE 2B): o eco pode ter trazido uma lista de inventario
        // diferente, e so o `rebuildWidgets` consegue trocar widgets. O pedido
        // fica marcado e sai no `renderContent` ({@link #maybeRebuildInventory}).
        // A comparacao e por `equals` do record, e nao por referencia: o
        // servidor reconstroi a ficha a cada gravacao, entao sem o `equals`
        // TODO eco contaria como mudanca.
        SheetData.Inventory current = sheet == null ? null : sheet.inventory();
        if (!Objects.equals(current, lastInventory)) {
            lastInventory = current;
            // Estado novo do servidor e a morte natural da marcacao do Del: o
            // indice marcado pode ser outro item agora.
            delPendingIndex = -1;
            inventoryRebuildPending = true;
        }
        // 01/10/2026 (pagina 3): as DUAS listas da aba 3 mudam pelo mesmo motivo
        // e pelo mesmo caminho -- so o `rebuildWidgets` troca widgets. A
        // comparacao e por `equals` do record (o servidor reconstroi a ficha a
        // cada gravacao, entao sem `equals` TODO eco contaria como mudanca).
        List<SheetData.Skill> skills = sheet == null ? List.of() : sheet.skills();
        if (!Objects.equals(skills, lastSkills)) {
            lastSkills = skills;
            skillColumn.delPending = -1;
            spellbookRebuildPending = true;
        }
        SheetData.Spellbook book = sheet == null ? null : sheet.spellbook();
        if (!Objects.equals(book, lastSpellbook)) {
            lastSpellbook = book;
            spellColumn.delPending = -1;
            spellbookRebuildPending = true;
        }
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
        // 30/09/2026 (FASE 2B): primeiro mesmo, porque o `rebuildWidgets` refaz
        // o layout e o resto do desenho deste frame usaria a geometria antiga.
        maybeRebuildInventory();
        // A moldura do Del e desenhada aqui porque o `renderContent` roda depois
        // de `super.render`, que e quem desenhou o botao.
        drawDelConfirm(graphics);
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
        // 30/09/2026 (FASE 2A): o quadro de texto da aba 2 e um widget, entao
        // precisa receber a roda para rolar as linhas que nao cabem na altura
        // dele. Este e o mesmo padrao do SkillsScreen: super PRIMEIRO, e o que
        // sobrar da roda e nosso.
        //
        // <b>Por que isso nao muda o scroll da aba 1:</b> o `super` nao repassa a
        // roda para a tela, ele pergunta ao widget que esta SOB o cursor
        // (`getChildAt` faz hit test por `isMouseOver`) e so devolve true se
        // algum deles consumir. Na aba 1 nao ha nenhum `MultiLineEditBox`, e
        // `EditBox`/`Button` nao sobrescrevem `mouseScrolled` (confirmado no
        // bytecode do 1.21.11: devolvem o false padrao), entao a roda chega
        // inteira na logica de coluna logo abaixo, como antes.
        if (super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) {
            return true;
        }
        // Convencao do vanilla: deltaY NEGATIVO e rolar para BAIXO, e rolar para
        // baixo avanca o conteudo (o mesmo de SkillsScreen.mouseScrolled).
        int step = scrollY < 0 ? 1 : -1;
        // 30/09/2026 (FASE 2B): a coluna do inventario e da aba 1, e e a PRIMEIRA
        // das tres a testar a posicao do mouse. Ela e a unica das tres que e o
        // widget de rolagem mais da direita, e o `super` acima ja deu a roda ao
        // quadro de texto que estiver sob o cursor -- entao o que sobra da roda
        // aqui e de verdade da lista.
        if (activeTab == 1 && isOverRightPanel(mouseX, mouseY)) {
            int next = clampRightScroll(rightScroll + step * INV_DESC_ADV);
            if (next != rightScroll) {
                rightScroll = next;
                // A rolagem recria os widgets, entao o texto meio digitado de um
                // quadro morreria com a caixa -- e a marcacao do Del aponta para
                // um item que pode ter saído da tela.
                delPendingIndex = -1;
                rebuildWidgets();
            }
            return true;
        }
        // 01/10/2026: a aba 3 tem DUAS listas, e cada uma rola com o cursor
        // sobre ela. O passo e a altura de UMA linha de descricao, igual ao
        // inventario -- e o `flushCd` vem antes, porque a rolagem recria os
        // widgets e o texto meio digitado da CD morreria com a caixa.
        if (activeTab == 2) {
            return scrollColumn(skillColumn, mouseX, mouseY, step * LIST_DESC_ADV)
                    || scrollColumn(spellColumn, mouseX, mouseY, step * LIST_DESC_ADV);
        }
        // 30/09/2026 (abas): so a aba 0 tem coluna de pericias/rolagem propria.
        // Nas outras a geometria seria a da ultima aba montada, entao sem este
        // guarda a roda moveria o scroll de uma aba que nao esta na tela.
        if (activeTab != 0) {
            return false;
        }
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
     * Rola uma das listas da aba 3, se o cursor estiver sobre ela.
     *
     * <p><b>Por que o flush da CD vem ANTES do {@code rebuildWidgets}:</b> a
     * rolagem destroi a caixa (e o texto que o jogador ainda nao enviou com ela),
     * que e exatamente o motivo do {@link #flushMultiLineBoxes} existir.
     *
     * @return {@code true} quando a roda foi consumida por esta lista.
     */
    private boolean scrollColumn(Column column, double mouseX, double mouseY, int delta) {
        if (column.maxScroll() <= 0 || !column.isOverList(mouseX, mouseY)) {
            return false;
        }
        int next = column.clampScroll(column.scroll + delta);
        if (next == column.scroll) {
            return true;
        }
        flushCd();
        column.scroll = next;
        // A marcacao do Del aponta para um item que pode ter saido da tela.
        column.delPending = -1;
        rebuildWidgets();
        return true;
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
