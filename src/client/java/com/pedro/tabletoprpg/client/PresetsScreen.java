package com.pedro.tabletoprpg.client;

import com.pedro.tabletoprpg.RollPreset;
import com.pedro.tabletoprpg.RollPresetColor;
import com.pedro.tabletoprpg.RpgNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * A tela de <b>Presets de Rolagem</b>: lista em cima, formulario embaixo (01/10/2026).
 *
 * <p><b>Por que uma tela so, e nao "criar" e "editar" separadas:</b> criar e editar
 * usam os mesmos tres campos e o mesmo botao. O que muda e o que ja vem preenchido:
 * clicar num preset da lista carrega os tres campos, e ai o mesmo Save grava por
 * cima. Uma tela para cada caso repetiria o formulario inteiro e o jogador teria duas
 * coisas parecidas na tela.
 *
 * <p><b>Por que o fundo e desenhado e nao uma textura:</b> decisao do usuario em
 * 01/10/2026. A lista precisa de altura variavel (a jogadora tem de 1 a
 * {@code RollPresetStore.MAX_PRESETS} presets) e nenhuma das texturas do mod tem uma
 * faixa de lista com tamanho variavel. Um retangulo escuro com as mesmas cores do
 * resto do mod resolve sem PNG novo.
 *
 * <p><b>Por que o formulario e separado da lista:</b> a lista e rolavel e o
 * formulario e fixo. Se os dois estivessem na mesma faixa, digitar no campo de nome
 * enquanto a lista rola seria ambiguo: o texto ia subir junto com os presets.
 */
public class PresetsScreen extends Screen {

    private final Screen parentScreen;

    /**
     * Os presets que a tela esta mostrando, na ordem em que o servidor mandou.
     *
     * <p><b>Por que a tela guarda a lista e nao le do store:</b> o store e do lado do
     * servidor. A tela precisa de uma copia para desenhar sem travar a cada quadro, e
     * a copia so e trocada quando o servidor responde.
     */
    private List<RollPreset> presets = new ArrayList<>();

    /**
     * O preset em edicao, ou {@code null} quando a jogadora esta criando um novo.
     *
     * <p>E o que decide o que o Save faz: com nome, o servidor edita; sem nome, cria.
     * Guardar o preset inteiro (e nao so o nome) permite saber se o Save precisa
     * avisar que a posicao dele na lista vai mudar, porque o nome novo e diferente do
     * velho.
     */
    private RollPreset editing;

    /**
     * A linha do Del esperando o segundo clique, ou -1.
     *
     * <p><b>Por que e indice e nao nome:</b> o nome pode mudar entre os dois cliques
     * (a jogadora edita no meio), e apagar o preset errado e pior do que nao apagar.
     * O indice trava a linha.
     */
    private int deletePending = -1;

    private EditBox nameBox;
    private EditBox formulaBox;
    private RollPresetColor selectedColor = RollPresetColor.WHITE;

    private int panelX, panelY, panelWidth, panelHeight;
    private int listTop, listBottom, listScroll, listRowHeight, visibleRows;
    private int swatchX, swatchY, swatchSize, swatchGap, swatchPerRow, swatchBlockHeight;
    private int colorLabelY, footerY, statusY;

    /** Os squares, na ordem do enum: a tela mostra a mesma ordem das cores. */
    private static final RollPresetColor[] SWATCHES = RollPresetColor.values();

    // --- medidas do layout (02/10/2026) ---
    //
    // <b>Por que sao constantes e nao numeros soltos no {@code init}:</b> o layout e
    // inteiro calculado a partir da altura da tela (ver `layout`). Espalhar 20 e 6
    // pelo codigo fazia a soma nao fechar, e foi exatamente o que mandou o painel
    // para fora da tela: 6 linhas de 22px mais o formulario davam 326px num painel de
    // 240px, e Save, Use e o botao de voltar ficavam abaixo da dobra.

    /** Borda interna do painel. */
    private static final int PAD = 6;
    /** Altura reservada ao titulo. */
    private static final int TITLE_H = 14;
    /** Altura de um campo de texto. */
    private static final int FIELD_H = 18;
    /** Altura de uma linha da lista e dos botoes do rodape. */
    private static final int ROW_H = 20;
    /** Espaco entre um bloco e o seguinte. */
    private static final int GAP = 6;
    /** Espaco menor, entre o rotulo e o campo que ele nomeia. */
    private static final int TIGHT_GAP = 4;
    /** Altura da linha de status (a fonte tem 9px). */
    private static final int STATUS_H = 10;
    /**
     * Quantas linhas o aviso de status pode ocupar.
     *
     * <p>Reservar duas custa {@code STATUS_H} a mais de altura do painel, e esse pixel
     * sai da lista em janela baixa. Uma linha era pouco: "Preset 'Dano Espada' created
     * (2d6+Strength, Yellow)" nao cabe em 288px de uma vez.
     */
    private static final int STATUS_LINES = 2;
    /** Lado do quadradinho de cor. */
    private static final int SWATCH_SIZE = 12;
    /** Vaos entre quadradinhos. */
    private static final int SWATCH_GAP = 2;
    /** Teto de linhas da lista, quando sobra altura. */
    private static final int MAX_ROWS = 6;

    /** Botao e vao das setas e do Del na linha da lista. */
    private static final int ROW_BTN_W = 20;
    private static final int ROW_BTN_GAP = 2;

    /**
     * Ate onde vai o quadradinho da cor no comeco da linha.
     *
     * <p><b>Por que e um numero e nao um offset solto:</b> o quadradinho e desenhado por
     * cima do botao do nome (o botao e opaco), e o nome dentro dele e centralizado. Sem
     * reservar esta faixa, um nome longo passa por baixo do quadradinho.
     */
    private static final int ROW_CHIP_W = 10;

    /**
     * As setas de reordenar.
     *
     * <p><b>Por que escapes e nao o caractere:</b> fonte e dados do projeto sao
     * ASCII (ver {@code scanEncoding}); o caractere de seta e um caractere nao-ASCII
     * que ja passou por reescrita de encoding nesta sessao.
     */
    private static final String ARROW_UP = "\u2191";
    private static final String ARROW_DOWN = "\u2193";

    public PresetsScreen(Screen parentScreen) {
        super(Component.literal("Presets"));
        this.parentScreen = parentScreen;
    }

    /** A tela que abriu esta, para o receptor do servidor voltar para ela. */
    public Screen parentScreen() {
        return parentScreen;
    }

    @Override
    protected void init() {
        super.init();

        layout();
        rebuildFooter();
        // A lista e desenhada linha a linha, com widget por linha. Sem
        // `rebuildWidgets` aqui: quem chama e o receptor do pacote, que pode chegar
        // a qualquer momento.
        rebuildListOnly();
    }

    /**
     * Calcula todas as coordenadas da tela a partir do tamanho da janela.
     *
     * <p><b>Por que a lista e medida e as outras coisas nao (02/10/2026):</b> a lista e
     * a unica parte que pode encolher. Titulo, campos, amostras e botoes tem altura
     * fixa, porque o que a jogadora precisa ver e o formulario inteiro; se a lista
     * tivesse altura fixa tambem, o painel inteiro passaria da tela. Entao a ordem e
     * Measure primeiro, lista no meio, e o resto em volta.
     *
     * <p><b>Por que centralizar na vertical:</b> sobra de altura num monitor grande
     * deixaria o painel colado no topo, e o centro da tela e onde o olho ja esta.
     */
    private void layout() {
        panelWidth = Math.min(this.width - 24, 300);
        panelX = (this.width - panelWidth) / 2;
        listRowHeight = ROW_H;

        // Amostras: quantas cabem numa linha, sem estourar o painel.
        swatchSize = SWATCH_SIZE;
        swatchGap = SWATCH_GAP;
        int usable = panelWidth - 2 * PAD;
        swatchPerRow = Math.max(1, Math.min(SWATCHES.length, usable / (swatchSize + swatchGap)));
        swatchBlockHeight = rowsOfSwatches() * swatchSize
                + (rowsOfSwatches() - 1) * swatchGap;

        // Tudo que nao e lista. A lista recebe o que sobrar da altura.
        //
        // O PAD do fim entra aqui, e nao so o do comeco: a linha de status termina
        // logo abaixo do rodape, e sem este PAD ela encostava na borda inferior do
        // painel.
        int chrome = TITLE_H + GAP
                + FIELD_H + TIGHT_GAP
                + FIELD_H + GAP
                + STATUS_H + TIGHT_GAP          // rotulo "Color: <nome>"
                + swatchBlockHeight + GAP
                + ROW_H + TIGHT_GAP             // rodape: Back | Save | Use
                + 2 * STATUS_H                  // aviso, em duas linhas
                + PAD;                           // folga antes da borda

        // <b>Por que 4 PAD e nao 2 (02/10/2026):</b> a altura do painel e
        // `2 * PAD + chrome + listHeight`, e `panelY` nunca e menor que PAD. Logo o
        // espaco REAL que a lista pode tomar e `height - 4 * PAD - chrome`: contar
        // so as duas margens do painel fazia o painel passar da tela em 427x240
        // (244px num painel de 240), e o que sobrava embaixo era o Save, o Use e a
        // linha de status. E o tamanho de tela em que a jogadora estava testando.
        int available = this.height - 4 * PAD - chrome;
        // Uma linha e o piso absoluto. Duas era o piso desejado, mas numa janela tao
        // baixa quanto esta nao cabem nem duas, e forcar as duas punha o rodape fora
        // da tela -- o mesmo defeito que isto corrige, so que agora no rodape.
        int listHeight = Math.min(MAX_ROWS * ROW_H, Math.max(ROW_H, available));

        visibleRows = Math.max(1, listHeight / ROW_H);
        // A altura volta a ser multipla da linha: sobra de meio pixel de folga ficaria
        // como uma faixa vazia no fim da lista.
        listHeight = visibleRows * ROW_H;

        panelHeight = 2 * PAD + chrome + listHeight;
        panelY = Math.max(PAD, (this.height - panelHeight) / 2);

        // A posicao da lista e do formulario sai do topo do painel, para as duas
        // metades concordarem em onde uma acaba e a outra comeca.
        int y = panelY + PAD + TITLE_H + GAP;
        listTop = y;
        listBottom = listTop + listHeight;

        // O campo e o rotulo dele sao centralizados como um BLOCO. Centralizar so o
        // campo punha o rotulo "Formula" para fora do painel, porque o rotulo e mais
        // largo que o espaco sobrando de um lado.
        int labelW = 46;
        int boxW = Math.max(60, Math.min(200, usable - labelW - TIGHT_GAP));
        int boxX = panelX + (panelWidth - (labelW + boxW)) / 2 + labelW;

        nameBox = field("Name", boxX, listBottom + GAP, boxW);
        nameBox.setMaxLength(RollPreset.MAX_NAME);

        formulaBox = field("Formula", boxX, nameBox.getY() + FIELD_H + TIGHT_GAP, boxW);
        formulaBox.setMaxLength(RollPreset.MAX_FORMULA);

        colorLabelY = formulaBox.getY() + FIELD_H + GAP;
        int swatchRows = rowsOfSwatches();
        int swatchesWidth = swatchPerRow * swatchSize + (swatchPerRow - 1) * swatchGap;
        swatchX = panelX + (panelWidth - swatchesWidth) / 2;
        swatchY = colorLabelY + STATUS_H + TIGHT_GAP;

        footerY = swatchY + swatchBlockHeight + GAP;
        statusY = footerY + ROW_H + TIGHT_GAP;

        // A rolagem pode ter ficado invalida: a lista encolheu com a janela e o
        // `listScroll` antigo apontaria para uma linha que nao existe mais.
        listScroll = Math.min(listScroll, maxScroll());

        // O aviso e quebrado depois do painel ter a largura definitiva: a quebra usa
        // `font.plainSubstrByWidth` com a largura do painel, que so existe aqui.
        layoutStatus();

        // O layout inteiro em uma linha (02/10/2026). Estourar a tela nao da erro nem
        // aviso: os botoes simplesmente nao aparecem, e o log do jogo nao diz nada.
        // Com estas medidas no log, um layout quebrado aparece como numero, e nao
        // como "a jogadora jurou que nao tinha visto o botao".
        TabletopRpgClient.LOGGER.info(
                "[TabletopRPG] PresetsScreen layout: tela={}x{} painel={}x{} em ({},{}) "
                        + "lista={}..{} ({} linha(s) de {}) campo={}x{} campoX={} "
                        + "amostras={} linha(s) de {} px cor={} rodapeY={} statusY={}",
                this.width, this.height, panelWidth, panelHeight, panelX, panelY,
                listTop, listBottom, visibleRows, listRowHeight,
                nameBox.getWidth(), nameBox.getHeight(), nameBox.getX(),
                rowsOfSwatches(), swatchPerRow, selectedColor.id(),
                footerY, statusY);
    }

    private int rowsOfSwatches() {
        return (SWATCHES.length + swatchPerRow - 1) / swatchPerRow;
    }

    private EditBox field(String label, int x, int y, int w) {
        EditBox box = new EditBox(this.font, x, y, w, FIELD_H, Component.literal(label));
        // OBRIGATORIO: sem addRenderableWidget a caixa nao entra no ciclo de desenho
        // nem no de clique (mesmo cuidado anotado em InventoryItemScreen).
        addRenderableWidget(box);
        return box;
    }

    /**
     * Recria Save, Use e o botao de voltar.
     *
     * <p><b>Por que os tres na MESMA linha:</b> antes eles ocupavam duas linhas (Save e
     * Use, depois o de voltar), e essa linha extra era justamente a que empurrava o
     * rodape para fora da tela. Voltar continua no canto inferior esquerdo, que e onde
     * a jogadora procura saida, e Save/Use continuam abaixo dos campos.
     *
     * <p><b>Por que a largura do botao de voltar sai da fonte:</b> um numero fixo
     * deixava "< Back to Rolls" estourando o botao quando a fonte era maior, ou sobrava
     * espaco quando era menor.
     */
    private void rebuildFooter() {
        String backLabel = "< Back to Rolls";
        int usable = panelWidth - 2 * PAD;
        // O botao de voltar nunca passa da metade do painel: se a fonte estiver grande
        // demais para o espaco, e melhor o texto apertar do que empurrar Save e Use
        // para fora do painel.
        int backW = Math.min(Math.max(60, this.font.width(backLabel) + 10), usable / 2);
        int btnW = Math.max(40, (usable - backW - GAP - TIGHT_GAP) / 2);

        int backX = panelX + PAD;
        int useX = panelX + panelWidth - PAD - btnW;
        int saveX = useX - btnW - TIGHT_GAP;
        // A largura real do botao de voltar e o que sobra ate o Save. Calcular a
        // posicao a partir da largura desejada e nao do espaco disponivel e o que
        // permite a sobreposicao quando os dois números nao batem.
        int backActualW = Math.max(20, saveX - GAP - backX);

        this.addRenderableWidget(Button.builder(Component.literal(backLabel), b -> onClose())
                .bounds(backX, footerY, backActualW, ROW_H).build());
        this.addRenderableWidget(Button.builder(Component.literal("Save"), b -> save())
                .bounds(saveX, footerY, btnW, ROW_H).build());
        this.addRenderableWidget(Button.builder(Component.literal("Use"), b -> usePreset())
                .bounds(useX, footerY, btnW, ROW_H).build());
    }

    /**
     * Os widgets das linhas da lista, para poder remove-los.
     *
     * <p><b>Por que este campo existe:</b> o {@code Screen} nao tem como remover um
     * widget especifico -- {@code clearWidgets} leva tudo junto, e o formulario sumiria
     * com o texto que a jogadora estava digitando. Guardando os widgets da lista,
     * eles sao removidos um a um e o resto da tela fica como esta.
     *
     * <p>Sem isso, cada seta e cada clique do Del somaria widgets novos por cima dos
     * antigos, e o clique passaria a acionar o indice de uma lista que nao existe mais.
     */
    private final List<Button> listWidgets = new ArrayList<>();
    /**
     * As setas de cada linha visivel, na ordem das linhas.
     *
     * <p>Separadas de {@link #listWidgets} porque o {@code render} precisa ligar e
     * desligar a visibilidade delas conforme o mouse entra e sai da linha, e nao faz
     * sentido ficar procurando seta dentro de uma lista que tambem tem nome e Del.
     */
    private final List<Button> upArrows = new ArrayList<>();
    private final List<Button> downArrows = new ArrayList<>();

    /** O aviso de status quebrado em linhas, montado por {@link #layoutStatus()}. */
    private final List<String> statusLines = new ArrayList<>();

    /**
     * Um widget da lista, ja registrado para a proxima remocao.
     *
     * <p>Passar por este metodo em vez de chamar {@code addRenderableWidget} direto e o
     * que impede o vazamento: um widget adicionado sem ser registrado nunca seria
     * removido.
     */
    private Button addListWidget(Button button) {
        listWidgets.add(button);
        addRenderableWidget(button);
        return button;
    }

    /**
     * Onde comeca e termina cada coluna da linha da lista.
     *
     * <p><b>Por que metodos e nao numeros no meio do codigo:</b> o desenho da linha
     * ({@code drawListOverlay}) e os botoes ({@code rebuildListOnly}) precisam concordar
     * exatamente, e um numero escrito em cada um vira divergencia na primeira
     * alteracao de largura. A ordem das colunas, da esquerda para a direita:
     * cor, nome, formula, setas, Del.
     */
    private int rowLeft() {
        return panelX + 4;
    }

    private int delX() {
        return panelX + panelWidth - 4 - ROW_BTN_W - ROW_BTN_GAP;
    }

    /**
     * Onde terminam as duas setas.
     *
     * <p><b>Por que o vao ate o Del e {@code ROW_BTN_GAP} e nao {@code GAP}
     * (02/10/2026):</b> pedido da jogadora para as setas ficarem "ao lado do Del". Com
     * {@code GAP} as setas pareciam um bloco solto no meio da linha e a formula perdia
     * espaco para um vao que nao separava nada. As setas e o Del formam um grupo unico,
     * do mesmo jeito que as duas setas ja se tocavam entre si.
     */
    private int arrowsRight() {
        return delX() - ROW_BTN_GAP - (ROW_BTN_W * 2 + ROW_BTN_GAP);
    }

    /**
     * Onde comeca a seta da esquerda, isto e, a borda ESQUERDA do grupo das duas.
     *
     * <p>Estrutura do grupo, da esquerda para a direita: seta de cima, seta de baixo,
     * {@code ROW_BTN_GAP}, {@code Del}.
     */
    private int arrowsLeft() {
        return arrowsRight() - (ROW_BTN_W * 2 + ROW_BTN_GAP);
    }

    /**
     * A largura da coluna do nome: metade do que sobra da linha.
     *
     * <p><b>Por que metade, e nao tres quintos (02/10/2026):</b> tres quintos eram
     * calculados sobre {@code arrowsRight()}, que e a borda DIREITA do grupo das setas,
     * e nao a esquerda. Com esse numero o nome recebia 43px que ninguem desenhava, e a
     * formula ficava com 37px -- poco mais que um "...". Agora os dois textos dividem a
     * faixa que vai ate {@link #formulaRight()}, que e onde a formula realmente pode
     * chegar.
     */
    private int rowNameWidth() {
        return Math.max(20, (formulaRight() - rowLeft() - ROW_CHIP_W - GAP) / 2);
    }

    /**
     * Onde termina a formula: na borda ESQUERDA do grupo das setas, menos um vao.
     *
     * <p><b>Por que a esquerda e nao a direita (02/10/2026):</b> este era o bug das
     * "setas em cima da formula". {@code arrowsRight()} e a borda DIREITA do grupo, e
     * a formula terminava 6px antes dela -- ou seja, 14px DENTRO do botao da seta de
     * baixo, que tem 20px de largura. O texto da formula e desenhado depois dos
     * widgets, entao o que aparecia era a seta com a formula escrita atravessada em
     * cima. A formula nunca pode passar de {@link #arrowsLeft()}.
     */
    private int formulaRight() {
        return arrowsLeft() - GAP;
    }

    /**
     * Os dois textos de uma linha, ja cortados para nao se atropelarem.
     *
     * <p>O nome e a formula sao cortados com o mesmo criterio aqui, e nao em cada
     * lugar: o {@code drawListOverlay} desenha a formula e o {@code rebuildListOnly} põe o
     * nome no botao, e dois cortes independentes dao uma linha onde um texto entra em
     * cima do outro.
     *
     * @return {@code [nome, formula]}, na ordem em que a linha mostra
     */
    private String[] rowTexts(RollPreset preset) {
        // A formula e desenhada por cima do botao do nome, entao o que manda e a borda
        // DIREITA do botao: a formula precisa caber entre ela e o `formulaRight`.
        // Antes a conta era uma fracao da largura toda, e sobrava por volta de 3px
        // -- era o "a esquerda da formula esta invadindo o botao do nome".
        int formulaMax = formulaRight() - (rowLeft() + rowNameWidth() + GAP);
        String formula = truncate(preset.formula(), Math.max(20, formulaMax));

        // O nome vive dentro do botao: a faixa do quadradinho da cor e um vao saem
        // da largura do botao, nao da largura toda da linha.
        int nameMax = rowNameWidth() - ROW_CHIP_W - GAP;
        return new String[]{truncate(preset.name(), Math.max(16, nameMax)), formula};
    }

    /** Recria so os widgets das linhas da lista, sem mexer no formulario. */
    private void rebuildListOnly() {
        // Remove os widgets da lista ANTES de criar os novos: e o que impede o
        // acúmulo a cada clique de seta ou de Del.
        for (Button old : listWidgets) {
            removeWidget(old);
        }
        listWidgets.clear();
        // Sem isto, `addArrow` acrescentaria as setas do scroll antigo em cima das
        // novas, e o `render` passaria a esconder as setas da linha errada.
        upArrows.clear();
        downArrows.clear();

        int visibleFrom = listScroll;
        int visibleTo = Math.min(presets.size(), listScroll + visibleRows);
        int btnH = ROW_H - 2;
        int upX = arrowsRight() - (ROW_BTN_W * 2 + ROW_BTN_GAP);

        for (int i = visibleFrom; i < visibleTo; i++) {
            RollPreset preset = presets.get(i);
            int rowY = listTop + (i - listScroll) * listRowHeight;
            int storedIndex = i;

            // Setas: uma casa por clique. As DUAS existem em toda linha, mesmo na
            // primeira e na ultima: quem nao tem para onde ir fica escura e travada
            // em vez de sumir (pedido da jogadora). Sumir fazia a borda da lista
            // parecer um botao quebrado, e ela nao descobria que o preset ja estava
            // no topo ou no fim da lista.
            addArrow(rowY, upX, btnH, storedIndex, true);
            addArrow(rowY, upX + ROW_BTN_W + ROW_BTN_GAP, btnH, storedIndex, false);

            // O nome: abre para edicao, e mostra a formula no hover (pedido do
            // usuario). O tooltip e a resposta ao "ao passar o mouse aparecera a
            // formula".
            addListWidget(Button.builder(
                            Component.literal(rowTexts(preset)[0]),
                            b -> loadForEdit(storedIndex))
                    .bounds(rowLeft(), rowY, rowNameWidth(), btnH)
                    .tooltip(Tooltip.create(Component.literal(preset.formula()),
                            Component.literal("Color: " + preset.color().displayName())))
                    .build());

            // Del com confirmacao em dois cliques, igual a lista de skills e magias.
            boolean pending = deletePending == storedIndex;
            addListWidget(Button.builder(
                            Component.literal(pending ? "Del?" : "Del"),
                            b -> onDelete(storedIndex))
                    .bounds(delX(), rowY, ROW_BTN_W, btnH)
                    .tooltip(Tooltip.create(Component.literal(
                            pending ? "Click again to confirm" : "Delete")))
                    .build());
        }
    }

    /**
     * Uma seta de reordenar, na posicao pedida.
     *
     * <p><b>Por que a seta travada existe em vez de nao existir:</b> pedido da
     * jogadora. A seta que nao tem para onde ir e desenhada escura e nao faz nada,
     * em vez de sumir. Sumir deixava a borda da lista com um buraco que parecia botao
     * quebrado, e nao dizia nada sobre o preset ja estar no topo ou no fim.
     *
     * <p><b>Por que o widget fica em lista propria:</b> o {@code render} precisa
     * saber quais botoes sao setas para mostrar so as da linha sob o mouse. Guardar a
     * posicao no indice da lista seria fragil: {@code listScroll} muda e o indice
     * deixa de valer.
     *
     * @param up {@code true} = seta para cima
     */
    private void addArrow(int rowY, int x, int btnH, int index, boolean up) {
        boolean canMove = up ? index > 0 : index < presets.size() - 1;
        Button arrow = Button.builder(
                        Component.literal(up ? ARROW_UP : ARROW_DOWN)
                                .withStyle(canMove ? ChatFormatting.WHITE : ChatFormatting.DARK_GRAY),
                        b -> move(index, up))
                .bounds(x, rowY, ROW_BTN_W, btnH)
                .tooltip(Tooltip.create(Component.literal(
                        !canMove ? (up ? "Already first" : "Already last")
                                : (up ? "Move up" : "Move down"))))
                .build();
        // `active = false` impede o clique E escurece o botao inteiro no vanilla.
        // Sem isto, a seta travada continuaria reordenando a lista se clicada.
        arrow.active = canMove;
        addListWidget(arrow);
        (up ? upArrows : downArrows).add(arrow);
    }

    private String truncate(String text, int maxWidth) {
        if (this.font.width(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "...";
        // String e nao int: `font.width` devolve pixel, e o recorte e por caractere
        // porque e o texto que a jogadora le que importa.
        String out = text;
        while (out.length() > 1 && this.font.width(out + ellipsis) > maxWidth) {
            out = out.substring(0, out.length() - 1);
        }
        return out + ellipsis;
    }

    // --- acoes ---

    /**
     * Clicou no nome de um preset: os campos passam a mostrar ele.
     *
     * <p>Limpar a selecao de cor tambem e parte disso: sem isso, abrir um preset
     * azul deixaria o campo mostrando o branco selecionado, e o Save gravaria branco
     * em cima do que a jogadora nao mexeu.
     */
    private void loadForEdit(int index) {
        if (index < 0 || index >= presets.size()) {
            return;
        }
        RollPreset chosen = presets.get(index);

        // Clicar na linha que ja esta em edicao desmarca (pedido da jogadora): sem
        // isso o unico jeito de limpar a selecao era fechar e abrir a tela de novo.
        // E o mesmo dois-cliques do Del, sem a confirmacao: nao apaga nada.
        if (editing != null && editing.key().equals(chosen.key())) {
            editing = null;
            nameBox.setValue("");
            formulaBox.setValue("");
            deletePending = -1;
            return;
        }

        editing = chosen;
        nameBox.setValue(editing.name());
        formulaBox.setValue(editing.formula());
        selectedColor = editing.color();
        deletePending = -1;
    }

    /** O botao Save: cria quando nao ha preset em edicao, edita quando ha. */
    private void save() {
        String original = editing == null ? "" : editing.name();
        ClientPlayNetworking.send(new RpgNetworking.PresetSavePayload(
                original, nameBox.getValue().trim(), formulaBox.getValue().trim(), selectedColor.id()));
    }

    /**
     * O botao Use: rola o preset em edicao.
     *
     * <p>Sem preset em edicao o botao nao faz nada: nao ha nome para rolar, e adivinhar
     * o que a jogadora quer rolar seria chute.
     */
    private void usePreset() {
        if (editing == null) {
            setStatus("click a preset in the list to use it");
            return;
        }
        // O preset ja foi validado quando foi salvo, entao rolar direto pelo nome e
        // o mesmo caminho do item: o servidor procura, rola e avisa se sumiu.
        // O nome vai na forma de comando porque o chat nao aceita espaco sem aspas
        // nesse argumento: ver RollPreset.commandName().
        if (this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.connection.sendCommand("rpg preset use " + editing.commandName());
        }
        onClose();
    }

    /** O primeiro clique marca, o segundo na mesma linha apaga. */
    private void onDelete(int index) {
        if (index < 0 || index >= presets.size()) {
            deletePending = -1;
            rebuildListOnly();
            return;
        }
        if (deletePending != index) {
            deletePending = index;
            rebuildListOnly();
            return;
        }
        deletePending = -1;
        RollPreset preset = presets.get(index);
        if (editing != null && editing.key().equals(preset.key())) {
            // Apagando o preset que esta nos campos: os campos nao podem continuar
            // mostrando algo que nao existe mais.
            editing = null;
            nameBox.setValue("");
            formulaBox.setValue("");
        }
        ClientPlayNetworking.send(new RpgNetworking.PresetDeletePayload(preset.name()));
    }

    /** Uma seta: manda para o servidor e espera a lista nova. */
    private void move(int index, boolean up) {
        if (index < 0 || index >= presets.size()) {
            return;
        }
        ClientPlayNetworking.send(new RpgNetworking.PresetMovePayload(presets.get(index).name(), up));
    }

    // --- entrada do jogador ---

    /**
     * Clique do mouse sobre as amostras de cor.
     *
     * <p><b>Por que o evento e um {@code MouseButtonEvent} e nao dois doubles:</b> nesta
     * versao (1.21.11) o {@code mouseClicked} da cadeia de GUI recebe o evento, com
     * {@code x()} e {@code y()}. A assinatura antiga com {@code double, double, int} nao
     * existe mais aqui.
     *
     * <p>As amostras sao desenho puro, sem widget: cada uma seria 17 widgets com rotulo
     * vazio, e nao ha tint de botao na API.
     */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int index = swatchAt(event.x(), event.y());
        if (index >= 0) {
            selectedColor = SWATCHES[index];
            // O mesmo som de botao do resto da interface; escolher cor nao e um botao
            // que "desce", entao o som de pressionar seria enganoso.
            if (this.minecraft != null) {
                net.minecraft.client.gui.components.AbstractWidget
                        .playButtonClickSound(this.minecraft.getSoundManager());
            }
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    /** O quadradinho da cor, ou -1 se o clique foi fora de todos. */
    private int swatchAt(double mouseX, double mouseY) {
        if (mouseY < swatchY || mouseY >= swatchY + swatchBlockHeight) {
            return -1;
        }
        int step = swatchSize + swatchGap;
        int row = (int) ((mouseY - swatchY) / step);
        int col = (int) ((mouseX - swatchX) / step);
        if (col < 0 || col >= swatchPerRow) {
            return -1;
        }
        int index = row * swatchPerRow + col;
        if (index < 0 || index >= SWATCHES.length) {
            return -1;
        }
        // A ultima linha pode ter menos que `swatchPerRow`; o espaco sobrando nao e
        // clicavel, senao o clique num canto vazio escolheria a cor da linha de baixo.
        double localX = mouseX - (swatchX + col * step);
        double localY = mouseY - (swatchY + row * step);
        if (localX > swatchSize || localY > swatchSize) {
            return -1;
        }
        return index;
    }

    /**
     * A roda do mouse rola a lista.
     *
     * <p><b>Por que a lista e a unica que rola:</b> o formulario tem tres campos e
     * nao cabe mais que isso na tela; rolar nele nao faria sentido. E o limite e
     * recalculado pelo tamanho da lista, entao uma lista de dois presets nao deixa
     * espaco morto para rolar.
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseY >= listTop && mouseY < listBottom && maxScroll() > 0) {
            // `scrollY` e o offset vertical do GLFW e vale POSITIVO ao rolar para CIMA
            // (conferido no bytecode do `MouseHandler.onScroll`, que o repassa direto
            // para `mouseScrolled`). Logo, descer a lista e `scrollY` negativo, que tem
            // que AUMENTAR o offset: `- (int) -Math.signum(scrollY)` = `+1`.
            //
            // <b>Por que o menos duplo e o sinal certo (02/10/2026):</b> o gesto estava
            // invertido, e a forma correta ja e a das outras tres listas deste projeto
            // (`StatusScreen`, `AttributePickerScreen`, `SheetEditorScreen`), todas com
            // `offset - signum(scrollY)`. Esta era a unica com `offset + signum(...)`.
            listScroll = Math.max(0, Math.min(maxScroll(), listScroll + (int) -Math.signum(scrollY)));
            rebuildListOnly();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private int maxScroll() {
        return Math.max(0, presets.size() - visibleRows);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, this.width, this.height, 0x99000000);

        // O painel usa a altura calculada no `layout`, e nao uma soma de"Y ate onde
        // estava o botao". A soma dava 326px num painel de 240px, e o que passasse
        // disso saia da tela sem nenhuma pista de que era para ter saido.
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0xFF202020);
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + 1, 0xFF6B4A2A);
        graphics.fill(panelX, panelY + panelHeight - 1, panelX + panelWidth, panelY + panelHeight,
                0xFF6B4A2A);

        // As setas dependem do mouse, entao a visibilidade delas e resolvida ANTES de
        // `super.render`: e o `render` dos widgets que le `visible`.
        showArrowsOnHoveredRow(mouseX, mouseY);

        // Fundo da lista ANTES de `super.render`. O `fill` e opaco: desenhado depois, ele
        // tapa os botoes das linhas, que continuam clicaveis porque clique nao depende
        // de ordem de desenho. Era esse o bug de "o botao do Del nao aparece mas da
        // para clicar nele".
        drawListPanel(graphics);

        super.render(graphics, mouseX, mouseY, delta);

        graphics.drawCenteredString(this.font, this.title, this.width / 2, panelY + PAD, 0xFFE0C080);

        drawListOverlay(graphics);
        drawSwatches(graphics);

        // Rotulo a direita do campo: e a coluna que o bloco centralizado no `layout`
        // abriu para ele. Alinhar pela direita do campo e o que mantem "Name" e
        // "Formula" na mesma coluna, mesmo com nomes de largura diferente.
        graphics.drawString(this.font, "Name",
                nameBox.getX() - TIGHT_GAP - this.font.width("Name"),
                nameBox.getY() + 5, 0xFFB0B0B0, false);
        graphics.drawString(this.font, "Formula",
                formulaBox.getX() - TIGHT_GAP - this.font.width("Formula"),
                formulaBox.getY() + 5, 0xFFB0B0B0, false);

        // "Color: <nome>" centrado como um par. O nome da cor sozinho, encostado no
        // campo, era mais largo que a coluna do rotulo e saia pela esquerda do painel
        // em cores como "Light Gray".
        String colorLabel = "Color";
        String colorValue = selectedColor.displayName();
        int pairW = this.font.width(colorLabel) + GAP + this.font.width(colorValue);
        int pairX = panelX + (panelWidth - pairW) / 2;
        graphics.drawString(this.font, colorLabel, pairX, colorLabelY, 0xFFB0B0B0, false);
        graphics.drawString(this.font, colorValue, pairX + this.font.width(colorLabel) + GAP,
                colorLabelY, 0xFFE0C080, false);

        // O aviso ocupa ate duas linhas, quebradas na largura do painel. Com tela
        // aberta ele NAO vai tambem para o chat (ver o receptor em
        // `TabletopRpgClient`), entao cortar o texto esconderia a mensagem. A cor e
        // branca: o vermelho era leitura de "erro", e a linha carrega sucesso e
        // recusa do mesmo jeito.
        for (int i = 0; i < statusLines.size(); i++) {
            graphics.drawCenteredString(this.font, statusLines.get(i),
                    this.width / 2, statusY + i * STATUS_H, 0xFFFFFFFF);
        }
    }

    /**
     * O aviso quebrado em ate {@link #STATUS_LINES} linhas, cada uma cabendo no painel.
     *
     * <p><b>Por que quebrar e nao cortar:</b> com a tela aberta o aviso do servidor nao
     * vai tambem para o chat -- o receptor em {@code TabletopRpgClient} manda para a
     * tela OU para o chat, nunca para os dois. Cortar esconderia a recusa. Se ainda
     * assim sobrar texto, a ultima linha recebe reticencias, para o jogador saber que
     * ha mais em vez de achar que leu tudo.
     */
    private void layoutStatus() {
        statusLines.clear();
        if (pendingStatus.isEmpty()) {
            return;
        }

        int maxWidth = panelWidth - 2 * PAD;
        String rest = pendingStatus;
        while (statusLines.size() < STATUS_LINES && !rest.isEmpty()) {
            // `plainSubstrByWidth` devolve o pedaco que CABE, e nao onde ele acaba: o
            // que importa e o tamanho da proxima linha, nao o indice. Cortar no meio
            // de uma palavra e permitido, porque um nome de preset nao tem espaco e
            // partir "Dano_Espada" em duas linhas e melhor que esconder metade.
            String head = font.plainSubstrByWidth(rest, maxWidth);
            if (head.isEmpty()) {
                // Nem um caractere cabe na largura (so aconteceria com painel
                // degenerado). Mostra um so, porque devolver a palavra inteira
                // estouraria o painel -- que e exatamente o defeito que esta funcao
                // existe para impedir.
                head = rest.substring(0, 1);
            }
            statusLines.add(head.trim());
            rest = rest.substring(head.length()).trim();
        }
        if (!rest.isEmpty() && !statusLines.isEmpty()) {
            int last = statusLines.size() - 1;
            statusLines.set(last, truncate(statusLines.get(last) + " ...", maxWidth));
        }
    }

    /**
     * Mostra as setas so da linha que esta sob o mouse (pedido da jogadora).
     *
     * <p><b>Por que tambem desligar o {@code active}:</b> conferido no bytecode do
     * {@code AbstractWidget.mouseClicked}, que testa {@code isActive()} e
     * {@code isMouseOver()} e <b>nao</b> testa {@code isVisible()}. Esconder a seta
     * sem desativar deixaria um botao invisivel reordenando a lista num clique cego.
     *
     * <p><b>Por que o {@code active} nao volta a {@code true} aqui:</b> a seta travada
     * do topo e do fim precisa continuar inativa mesmo com o mouse em cima dela. Por
     * isso quem decide e {@code canMove}, recalculado do indice da linha, e nao o
     * {@code active} que o botao nasceu com.
     */
    private void showArrowsOnHoveredRow(int mouseX, int mouseY) {
        int hoveredRow = -1;
        for (int j = 0; j < visibleRows; j++) {
            int rowY = listTop + j * listRowHeight;
            if (mouseX >= panelX && mouseX < panelX + panelWidth
                    && mouseY >= rowY && mouseY < rowY + listRowHeight) {
                hoveredRow = j;
                break;
            }
        }
        for (int j = 0; j < upArrows.size(); j++) {
            int index = listScroll + j;
            boolean canUp = index > 0;
            boolean canDown = index < presets.size() - 1;
            Button up = upArrows.get(j);
            Button down = downArrows.get(j);
            up.visible = j == hoveredRow;
            down.visible = j == hoveredRow;
            up.active = canUp && j == hoveredRow;
            down.active = canDown && j == hoveredRow;
        }
    }

    /**
     * O fundo da lista e a marca da linha em edicao.
     *
     * <p><b>Por que vai antes dos widgets:</b> sao {@code fill} opacos, e o
     * {@link #render} desenha os botoes depois. Desenhar isto por cima do que ja foi
     * desenhado esconde o botao sem tirar o clique dele.
     */
    private void drawListPanel(GuiGraphics graphics) {
        graphics.fill(panelX + 4, listTop, panelX + panelWidth - 4, listBottom, 0xFF161616);

        int visibleFrom = listScroll;
        int visibleTo = Math.min(presets.size(), listScroll + visibleRows);

        for (int i = visibleFrom; i < visibleTo; i++) {
            RollPreset preset = presets.get(i);
            int rowY = listTop + (i - listScroll) * listRowHeight;
            boolean isEditing = editing != null && editing.key().equals(preset.key());

            if (isEditing) {
                // A linha em edicao fica marcada: e o que diz a jogadora qual preset o
                // Save vai sobrescrever.
                graphics.fill(panelX + 5, rowY, panelX + panelWidth - 5, rowY + listRowHeight - 2,
                        0xFF3A3A3A);
            }
        }
    }

    /**
     * O que fica por cima dos botoes da lista: o quadradinho da cor e a formula.
     *
     * <p><b>Por que a formula aparece na linha e tambem no tooltip:</b> o tooltip so
     * aparece quando o mouse esta em cima, e a lista pode ter seis linhas. Ver a
     * formula de todos de uma vez e o que faz a tela ser util sem passar o mouse.
     *
     * <p><b>Por que estes dois vao DEPOIS dos widgets e o fundo nao:</b> o botao do
     * nome e opaco, entao o quadradinho so aparece se for desenhado depois. A formula e
     * o caso oposto: e o unico texto da linha que o botao nao desenha, porque o botao
     * mostra so o nome, e precisa ficar na frente do botao para nao ser coberta pelo
     * texto dele. {@code rowTexts} corta os dois com o mesmo criterio, entao nome e
     * formula nunca se sobrepoem.
     */
    private void drawListOverlay(GuiGraphics graphics) {
        int visibleFrom = listScroll;
        int visibleTo = Math.min(presets.size(), listScroll + visibleRows);

        for (int i = visibleFrom; i < visibleTo; i++) {
            RollPreset preset = presets.get(i);
            int rowY = listTop + (i - listScroll) * listRowHeight;

            // A cor do preset como um quadradinho antes do nome: e a unica coisa
            // que distingue dois presets com o mesmo nome visualmente.
            graphics.fill(rowLeft() + 2, rowY + 6, rowLeft() + 10, rowY + 14, preset.color().argb());

            if (editing != null && editing.key().equals(preset.key())) {
                // A linha em edicao ganha um risco por baixo. O realce de fundo esta
                // no `drawListPanel`, atras dos botoes, e nesta altura ele fica so nos
                // vaos entre eles; o risco e desenhado na faixa de baixo da linha
                // (listRowHeight - 2), que nenhum botao da linha ocupa, porque todos
                // eles tem ROW_H - 2 de altura. Sem ele, a unica pista de qual preset o
                // Save vai sobrescrever e o campo Name la em cima.
                graphics.fill(panelX + 5, rowY + listRowHeight - 2,
                        panelX + panelWidth - 5, rowY + listRowHeight - 1, 0xFFE0C080);
            }

            // O mesmo texto que o botao da linha mostra: `rowTexts` corta os dois de
            // uma vez, entao nome e formula nunca se sobrepoem aqui.
            String formula = rowTexts(preset)[1];
            graphics.drawString(this.font, formula,
                    formulaRight() - this.font.width(formula), rowY + 6, 0xFF7F7F7F, false);
        }

        if (presets.isEmpty()) {
            graphics.drawCenteredString(this.font, "No presets yet. Fill the fields and Save.",
                    panelX + panelWidth / 2, listTop + 6, 0xFF808080);
        }
    }

    private void drawSwatches(GuiGraphics graphics) {
        int rows = rowsOfSwatches();
        for (int i = 0; i < SWATCHES.length; i++) {
            int row = i / swatchPerRow;
            int col = i % swatchPerRow;
            int x = swatchX + col * (swatchSize + swatchGap);
            int y = swatchY + row * (swatchSize + swatchGap);
            graphics.fill(x, y, x + swatchSize, y + swatchSize, SWATCHES[i].argb());
            // Borda clara em volta de quem esta escolhido, escura nas outras: a escolha
            // precisa ser visivel mesmo nas cores escuras (preto em preto sumiria).
            int border = SWATCHES[i] == selectedColor ? 0xFFFFFFFF : 0xFF101010;
            graphics.fill(x, y, x + swatchSize, y + 1, border);
            graphics.fill(x, y + swatchSize - 1, x + swatchSize, y + swatchSize, border);
            graphics.fill(x, y, x + 1, y + swatchSize, border);
            graphics.fill(x + swatchSize - 1, y, x + swatchSize, y + swatchSize, border);
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Ja desenhado em render: o fundo escuro e o painel sao filled, nao textura.
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parentScreen);
    }

    // --- estado que chega do servidor ---

    /**
     * A resposta do servidor para a tela aberta.
     *
     * <p><b>Por que estatico:</b> o receptor do pacote roda fora da tela, e a
     * resposta chega depois do clique. Um campo da tela exigiria a tela ainda viva, e
     * ela pode ter sido fechada.
     */
    private static String pendingStatus = "";

    /** Chamado pelo receptor do cliente com a lista nova e a mensagem do servidor. */
    public void applyResult(boolean ok, String message, List<RollPreset> updated) {
        if (updated != null && !updated.isEmpty()) {
            presets = new ArrayList<>(updated);
        } else if (ok) {
            // Ok com lista vazia = a jogadora deletou o ultimo preset.
            presets = new ArrayList<>();
        }
        pendingStatus = message == null ? "" : message;
        if (panelWidth > 0) {
            layoutStatus();
        }

        // O preset em edicao continua sendo o mesmo objeto da lista, entao a linha
        // segue marcada depois de salvar.
        if (editing != null) {
            RollPreset found = findByKey(editing.key());
            if (found == null) {
                // O preset em edicao sumiu (deletado, ou renomeado pelo servidor).
                editing = null;
            } else {
                editing = found;
            }
        }
        deletePending = -1;
        rebuildListOnly();
    }

    private RollPreset findByKey(String key) {
        for (RollPreset preset : presets) {
            if (preset.key().equals(key)) {
                return preset;
            }
        }
        return null;
    }

    /** Abre a tela ja pedindo a lista, para o cliente nao mostrar lista vazia. */
    public void requestList() {
        ClientPlayNetworking.send(new RpgNetworking.PresetListRequestPayload());
    }

    public void setStatus(String message) {
        pendingStatus = message == null ? "" : message;
        // A tela pode nem estar montada ainda (`setStatus` e chamado antes do `init`).
        // `layoutStatus` so depende da largura do painel, que ainda e 0 nesse ponto, e
        // quem chama de novo e o `layout`.
        if (panelWidth > 0) {
            layoutStatus();
        }
    }
}
