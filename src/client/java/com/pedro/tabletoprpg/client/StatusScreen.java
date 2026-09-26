package com.pedro.tabletoprpg.client;

import com.pedro.tabletoprpg.RpgNetworking;
import com.pedro.tabletoprpg.SheetData;
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
 * <p><b>Atributos sao setas, nao caixas de texto</b> (decisao do usuario): o
 * atributo virou modificador somado as pericias, entao o mestre so precisa
 * subir e descer -- e um numero solto nao limitava mais. Nao ha barra nem
 * caixa: apenas {@code rotulo  [<]  numero  [>]}. Como o atributo nao tem teto
 * e aceita valor negativo, a seta {@code <} continua funcionando em 0 e abaixo.
 */
public class StatusScreen extends CharacterSheetScreen {

    // ------------------------------------------------------------------
    // COLUNA DE PERICIAS (decisao do usuario em 25/09/2026)
    // ------------------------------------------------------------------

    /** Quantas pericias a coluna mostra: a lista e fixa, entao o total e' este. */
    private static final int PER_COUNT = 20;
    /** Painel mais largo que o das outras telas, para caber a coluna. */
    private static final int MAX_PANEL_W_STATUS = 600;
    /**
     * Teto da largura dos campos de texto (nome, raca, classe, nivel, xp).
     *
     * <p>Feedback do usuario: com a caixa esticando ate a borda, os campos
     * ficavam grandes demais e o menu parecia largo demais. O campo tem teto e
     * o espaco que sobra da linha e dividido em duas metades, para as duas
     * colunas ficarem simetricas.
     */
    private static final int FIELD_W_MAX = 190;
    /** Folga entre as pecas de um atributo, a mesma usada nas pericias. */
    private static final int WIDGET_GAP = 2;
    /** Largura da caixa do valor, a mesma usada nas pericias. */
    private static final int PERICIA_VALUE_W = 10;
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
    private final Map<String, SheetData.Attribute> pendingPericiaAttribute = new HashMap<>();

    /** Geometria de uma linha de pericia: nome (x, w), valor e o botao de atributo. */
    private record PericiaRow(int nameX, int y, int nameW, int valueX, int valueW, int h, Button attrButton) {
    }

    /** Geometria das barras, calculada em buildPanel(). */
    private Bar hpBar;
    private Bar manaBar;

    /** Setas: ficam cinzas quando a ficha e somente leitura. */
    private final List<Button> arrowButtons = new ArrayList<>();

    /** Onde o numero de cada atributo e desenhado (o widget e' o valor). */
    private final List<AttrRow> attrRows = new ArrayList<>();

    /** Area onde o numero do atributo e centralizado. */
    private record AttrRow(int x, int y, int w, int h, SheetData.Attribute attribute) {
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

        // Altura de linha da coluna de pericias: e' ela que decide se as 20
        // cabem sem scroll. Limitada para nunca passar da altura de uma linha
        // normal (senao a coluna ficaria igual ao resto e nao caberia).
        int perTitleH = rowHOrDefault(topY, bottomY);
        int perAvail = Math.max(MIN_PER_ROW_H * PER_COUNT, bottomY - topY - perTitleH);
        perRowH = Math.max(MIN_PER_ROW_H, Math.min(perRowHCap(topY, bottomY), perAvail / PER_COUNT));

        // Duas colunas de atributos so quando o painel e largo o bastante para
        // os rotulos (FOR/DES/...) nao se chocarem com as duas setas.
        boolean twoColumns = leftW >= 260;
        int attrRowCount = twoColumns ? 3 : 6;
        int neededRows = 3 + 2 + 2 + attrRowCount + 4; // 4 titulos de secao
        rowH = fitRowHeight(neededRows, topY, bottomY);

        int labelW = Math.max(52, leftW / 5);
        // Campo de tamanho MEDIO e centralizado (feedback do usuario: as caixas
        // de texto estavam grandes demais, ocupando todo o resto da linha).
        // Em vez de esticar ate a borda, a caixa tem teto e o sobra da linha
        // e dividido em duas metades, entando as duas colunas ficam simetricas.
        int fieldArea = Math.max(60, leftW - labelW - 6);
        int boxW = Math.max(60, Math.min(FIELD_W_MAX, fieldArea));
        int boxX = x0 + labelW + (fieldArea - boxW) / 2;

        int y = topY;

        // ---------------- Identity ----------------
        y = addSection("Identity", x0, y);
        y = addField("characterName", x0, y, boxX, boxW, false);
        y = addField("race", x0, y, boxX, boxW, false);
        y = addField("characterClass", x0, y, boxX, boxW, false);

        // ---------------- Vitals (barras) ----------------
        y = addSection("Vitals", x0, y);
        hpBar = addResourceRow(x0, boxX, y, boxW, "HP", "hp", "hpMax", COL_HP, COL_HP_BG);
        y += rowH;
        manaBar = addResourceRow(x0, boxX, y, boxW, "Mana", "mana", "manaMax", COL_MANA, COL_MANA_BG);
        y += rowH;

        // ---------------- Progress ----------------
        y = addSection("Progress", x0, y);
        y = addField("level", x0, y, boxX, boxW, true);
        y = addField("xp", x0, y, boxX, boxW, true);

        // ---------------- Attributes ----------------
        // Decisao do usuario: atributos sao setas -/+ (passo 1), sem caixa de
        // texto e sem barra, começando em 0 e sem limite (podem ser negativos).
        y = addSection("Attributes", x0, y);
        if (twoColumns) {
            int colW = (leftW - 6) / 2;
            for (int i = 0; i < SheetData.Attribute.VALUES.size(); i++) {
                SheetData.Attribute attr = SheetData.Attribute.VALUES.get(i);
                int cx = x0 + (i % 2) * colW;
                int cy = y + (i / 2) * rowH;
                addAttributeRow(cx, cy, colW - 4, attr);
            }
            y += 3 * rowH;
        } else {
            for (SheetData.Attribute attr : SheetData.Attribute.VALUES) {
                addAttributeRow(x0, y, leftW - 6, attr);
                y += rowH;
            }
        }

        // ---------------- Pericias (coluna da direita) ----------------
        // Lista FIXA definida em SheetData.PERICIAS_PADRAO: nao ha botao de
        // adicionar nem de remover, so as setas de valor e o botao de atributo.
        textLines.add(new TextLine("Pericias", perX, topY + perLabelOffset(), COL_SECTION));
        for (int i = 0; i < PER_COUNT; i++) {
            addPericiaRow(perX, topY + perTitleH + i * perRowH, perW, i);
        }
    }

    /** Altura de linha provisoria, so para estimar o titulo antes de {@code rowH}. */
    private int rowHOrDefault(int topY, int bottomY) {
        int available = Math.max(MIN_ROW_H, bottomY - topY);
        return Math.max(MIN_ROW_H, Math.min(MAX_ROW_H, available / 12));
    }

    /** Teto da altura de linha das pericias: nunca maior que uma linha normal. */
    private int perRowHCap(int topY, int bottomY) {
        return Math.min(MAX_ROW_H, (bottomY - topY) / (PER_COUNT + 1));
    }

    /** Centraliza o texto na linha compacta (fonte e 8px, linha pode ter 9). */
    private int perLabelOffset() {
        return Math.max(1, (perRowH - 8) / 2);
    }

    /**
     * Uma linha de pericia: {@code nome  [<]  valor  [>]  [atributo v]}.
     *
     * <p>Compacta de proposito: sao 20 linhas sem scroll, entao a altura
     * ({@link #perRowH}) pode chegar a 9px e as setas sao menores que as dos
     * atributos. O nome e o valor sao desenhados em {@link #renderContent} (o
     * widget e' so a moldura), o que mantem {@code applySheetToWidgets}
     * simples.
     */
    private void addPericiaRow(int x0, int y, int rowW, int index) {
        int h = Math.max(8, perRowH - 1);
        int attrW = Math.max(22, Math.min(32, rowW / 5));
        int arrow = Math.max(9, Math.min(13, h - 1));
        int valueW = 10;

        int attrX = x0 + rowW - attrW;
        int plusX = attrX - 2 - arrow;
        int valueX = plusX - 2 - valueW;
        int minusX = valueX - 2 - arrow;

        Button minus = Button.builder(Component.literal("<"),
                b -> stepPericiaValue(index, -1)).bounds(minusX, y, arrow, h).build();
        Button plus = Button.builder(Component.literal(">"),
                b -> stepPericiaValue(index, 1)).bounds(plusX, y, arrow, h).build();
        Button attr = Button.builder(Component.literal(""),
                b -> openPericiaAttribute(index)).bounds(attrX, y, attrW, h).build();
        addRenderableWidget(minus);
        addRenderableWidget(plus);
        addRenderableWidget(attr);
        arrowButtons.add(minus);
        arrowButtons.add(plus);
        arrowButtons.add(attr);

        // 4px de folga entre o nome e a seta esquerda: o usuario apontou que
        // "Constituição" encostava nela.
        periciaRows.add(new PericiaRow(x0, y, Math.max(16, minusX - 4 - x0), valueX, valueW, h, attr));
    }

    /** Abre a lista suspensa dos 6 atributos para a pericia da linha. */
    private void openPericiaAttribute(int index) {
        if (!canEdit || this.minecraft == null || sheet == null || index >= sheet.pericias().size()) {
            return;
        }
        SheetData.Pericia pericia = sheet.pericias().get(index);
        this.minecraft.setScreen(new AttributePickerScreen(
                this, pericia.name(), pericia.attribute(), chosen -> {
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

    /** Atributo a exibir: o otimista se houver, senao o do servidor. */
    private SheetData.Attribute displayPericiaAttribute(SheetData.Pericia pericia) {
        SheetData.Attribute pending = pendingPericiaAttribute.get(pericia.name());
        return pending != null ? pending : pericia.attribute();
    }

    /**
     * Uma linha de atributo: {@code rotulo  [<]  numero  [>]}.
     *
     * <p>Nao usa {@code createFieldBox} de proposito -- e' a unica secao da
     * ficha sem caixa de texto. O numero vive em {@link AttrRow} e e'
     * desenhado em {@link #renderContent}, o que mantem {@code applySheetToWidgets}
     * simples (ela so precisa atualizar as caixas de vida, mana e texto).
     *
     * <p>O rotulo abrevia (FOR, DES, ...) quando a coluna e estreita para
     * caber as duas setas, e mostra o nome por extenso quando ha espaco.
     */
    private void addAttributeRow(int x0, int y, int rowW, SheetData.Attribute attr) {
        int h = rowH - 2;
        boolean compact = rowW < 84;
        String label = compact ? attr.abbr() : attr.fullName();
        int labelW = fixedAttributeLabelWidth(rowW, compact);

        textLines.add(new TextLine(label, x0, y + labelOffset(), COL_LABEL));

        int minusX = x0 + labelW;
        // Mesma geometria da linha de pericia (addPericiaRow): seta dimensionada
        // pela altura da linha, valor com largura fixa e folga de 2px entre as
        // pecas. Pedido do usuario: "um gap igual os da pericia para os
        // atributos".
        // Antes o numero ocupava todo o vao entre as duas setas
        // (valueW = plusX - 4 - valueX), o que o centralizava longe de ambas.
        int arrow = Math.max(9, Math.min(13, h - 1));
        int valueW = PERICIA_VALUE_W;
        int valueX = minusX + arrow + WIDGET_GAP;
        int plusX = valueX + valueW + WIDGET_GAP;

        Button minus = Button.builder(Component.literal("<"),
                b -> stepNumeric(attr.field(), -ARROW_STEP)).bounds(minusX, y, arrow, h).build();
        Button plus = Button.builder(Component.literal(">"),
                b -> stepNumeric(attr.field(), ARROW_STEP)).bounds(plusX, y, arrow, h).build();
        addRenderableWidget(minus);
        addRenderableWidget(plus);
        arrowButtons.add(minus);
        arrowButtons.add(plus);

        attrRows.add(new AttrRow(valueX, y, valueW, h, attr));
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
     * <p>A correção é usar sempre a largura do <b>rótulo mais largo</b> dos
     * seis atributos, para que as setas fiquem na mesma coluna em todas as
     * linhas. O texto continua sendo o nome por extenso quando há espaço e a
     * sigla quando a coluna é estreita (o mesmo critério de antes).
     */
    private int fixedAttributeLabelWidth(int rowW, boolean compact) {
        int widest = 0;
        for (SheetData.Attribute attr : SheetData.Attribute.VALUES) {
            widest = Math.max(widest, this.font.width(compact ? attr.abbr() : attr.fullName()));
        }
        int arrow = Math.max(9, Math.min(13, rowH - 3));
        return Math.max(24, Math.min(rowW - 2 * arrow - PERICIA_VALUE_W - 2 * WIDGET_GAP - 8,
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
        textLines.add(new TextLine(label, x0, y + labelOffset(), COL_LABEL));

        int h = rowH - 2;
        int maxLabelW = 30;
        int maxBoxW = Math.max(40, Math.min(56, boxW / 5));
        int barW = Math.max(24, boxW - 2 * ARROW_SIZE - 3 * 4 - maxLabelW - maxBoxW);

        Button minus = Button.builder(Component.literal("<"), b -> stepNumeric(field, -ARROW_STEP))
                .bounds(boxX, y, ARROW_SIZE, h)
                .build();
        Button plus = Button.builder(Component.literal(">"), b -> stepNumeric(field, ARROW_STEP))
                .bounds(boxX + ARROW_SIZE + 4 + barW + 4, y, ARROW_SIZE, h)
                .build();
        addRenderableWidget(minus);
        addRenderableWidget(plus);
        arrowButtons.add(minus);
        arrowButtons.add(plus);

        int barX = boxX + ARROW_SIZE + 4;
        int maxLabelX = boxX + ARROW_SIZE + 4 + barW + 4 + ARROW_SIZE + 4;
        textLines.add(new TextLine("Max", maxLabelX, y + labelOffset(), COL_MUTED));
        fieldBoxes.put(maxField, createFieldBox(maxField, maxLabelX + maxLabelW, y, maxBoxW, true));

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

    @Override
    protected void applyExtraState() {
        for (Button arrow : arrowButtons) {
            arrow.active = canEdit;
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
        drawValue(graphics, hpBar, hp + " / " + hpMax, downed);

        // Mana: piso 0, sem regra especial, tambem pode passar do maximo.
        int mana = numericValue("mana");
        int manaMax = numericValue("manaMax");
        drawResourceBar(graphics, manaBar, COL_MANA, COL_MANA_OVER, COL_MANA_BG,
                mana, manaMax, false);
        drawValue(graphics, manaBar, mana + " / " + manaMax, false);

        // Atributos: apenas o numero, centralizado entre as duas setas.
        // 0 em cinza (o padrao) e qualquer outro valor na cor normal, para o
        // mestre bater o olho em "quem foi ajustado" sem ler os 6 numeros.
        for (AttrRow row : attrRows) {
            int value = numericValue(row.attribute().field());
            // Sem teto, o numero pode ficar largo demais e invadir o rotulo e a
            // seta ">". Cortar pela largura da coluna mantem o layout intacto
            // com 999999 sem ter de mentir sobre o valor guardado.
            String text = this.font.plainSubstrByWidth(Integer.toString(value), row.w());
            int tx = row.x() + (row.w() - this.font.width(text)) / 2;
            int ty = row.y() + (row.h() - 8) / 2;
            graphics.drawString(this.font, text, tx, ty,
                    value == 0 ? COL_MUTED : COL_BOX_TEXT, false);
        }

        drawPericias(graphics);
    }

    /**
     * Desenha as 20 linhas de pericia: nome, valor e a abreviacao do atributo.
     *
     * <p>O nome e cortado pela largura ({@code plainSubstrByWidth}) em vez de
     * estourar a coluna: "perícia 15" cabe, mas um nome personalizado pelo
     * sistema nao pode empurrar as setas para fora.
     *
     * <p>Se a ficha ainda nao chegou, ou vier com menos/mais de 20 pericias,
     * as linhas ficam vazias em vez de estourar a lista: a lista fixa e' uma
     * invariante do servidor, entao a diferenca aqui seria um bug de protocolo.
     */
    private void drawPericias(GuiGraphics graphics) {
        if (sheet == null) {
            return;
        }
        List<SheetData.Pericia> pericias = sheet.pericias();
        for (int i = 0; i < periciaRows.size() && i < pericias.size(); i++) {
            SheetData.Pericia pericia = pericias.get(i);
            PericiaRow row = periciaRows.get(i);
            int ty = row.y() + Math.max(1, (row.h() - 8) / 2);

            String name = this.font.plainSubstrByWidth(pericia.name(), row.nameW());
            graphics.drawString(this.font, name, row.nameX(), ty, COL_LABEL, false);

            String valueText = Integer.toString(displayPericiaValue(pericia));
            graphics.drawString(this.font, valueText,
                    row.valueX() + (row.valueW() - this.font.width(valueText)) / 2, ty,
                    COL_BOX_TEXT, false);

            // A abreviacao do atributo fica no botao, guardado direto no record
            // para nao depender de procurar o widget por coordenada.
            row.attrButton().setMessage(Component.literal(displayPericiaAttribute(pericia).abbr()));
        }
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

    /** Escreve "atual / max" no centro da barra. */
    private void drawValue(GuiGraphics graphics, Bar bar, String text, boolean downed) {
        if (bar == null) {
            return;
        }
        int x = bar.x() + (bar.w() - this.font.width(text)) / 2;
        int y = bar.y() + (bar.h() - 8) / 2;
        graphics.drawString(this.font, text, x, y, downed ? COL_DOWNED : COL_BOX_TEXT, true);
    }
}
