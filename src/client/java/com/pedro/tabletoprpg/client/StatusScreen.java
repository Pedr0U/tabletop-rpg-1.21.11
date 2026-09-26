package com.pedro.tabletoprpg.client;

import com.pedro.tabletoprpg.SheetData;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

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

    @Override
    protected void buildPanel(int x0, int panelW, int topY, int bottomY) {
        arrowButtons.clear();
        attrRows.clear();

        // Duas colunas de atributos so quando o painel e largo o bastante para
        // os rotulos (FOR/DES/...) nao se chocarem com as duas setas.
        boolean twoColumns = panelW >= 260;
        int attrRowCount = twoColumns ? 3 : 6;
        int neededRows = 3 + 2 + 2 + attrRowCount + 4; // 4 titulos de secao
        rowH = fitRowHeight(neededRows, topY, bottomY);

        int labelW = Math.max(52, panelW / 5);
        int boxX = x0 + labelW;
        int boxW = panelW - labelW - 6;

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
            int colW = (panelW - 6) / 2;
            for (int i = 0; i < SheetData.Attribute.VALUES.size(); i++) {
                SheetData.Attribute attr = SheetData.Attribute.VALUES.get(i);
                int cx = x0 + (i % 2) * colW;
                int cy = y + (i / 2) * rowH;
                addAttributeRow(cx, cy, colW - 4, attr);
            }
            y += 3 * rowH;
        } else {
            for (SheetData.Attribute attr : SheetData.Attribute.VALUES) {
                addAttributeRow(x0, y, panelW - 6, attr);
                y += rowH;
            }
        }
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
        int labelW = Math.min(rowW - 2 * ARROW_SIZE - 12, this.font.width(label) + 6);

        textLines.add(new TextLine(label, x0, y + labelOffset(), COL_LABEL));

        int minusX = x0 + labelW;
        int plusX = x0 + rowW - ARROW_SIZE;
        int valueX = minusX + ARROW_SIZE + 4;
        int valueW = Math.max(10, plusX - 4 - valueX);

        Button minus = Button.builder(Component.literal("<"),
                b -> stepNumeric(attr.field(), -ARROW_STEP)).bounds(minusX, y, ARROW_SIZE, h).build();
        Button plus = Button.builder(Component.literal(">"),
                b -> stepNumeric(attr.field(), ARROW_STEP)).bounds(plusX, y, ARROW_SIZE, h).build();
        addRenderableWidget(minus);
        addRenderableWidget(plus);
        arrowButtons.add(minus);
        arrowButtons.add(plus);

        attrRows.add(new AttrRow(valueX, y, valueW, h, attr));
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
