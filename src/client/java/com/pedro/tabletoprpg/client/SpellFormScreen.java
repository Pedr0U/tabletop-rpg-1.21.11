package com.pedro.tabletoprpg.client;

import com.pedro.tabletoprpg.RpgNetworking;
import com.pedro.tabletoprpg.SheetData;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Formulario de <b>uma magia</b> (01/10/2026, pagina 3 da ficha).
 *
 * <p>Mesma forma do {@link InventoryItemScreen}: tela cheia, aberta pelo botao
 * "+ Magic" (nova) ou por "Edit" na lista, e o {@code Cancel} volta para a
 * ficha sem passar pelo menu.
 *
 * <p><b>Os cinco campos de Tormenta 20 sao texto livre:</b> execucao, alcance,
 * alvo, duracao e custo. O pedido foi "campo", e e o mesmo tratamento que o
 * inventario ja usa; travar em lista fechada impediria o jogador de escrever
 * "Acao + bonus" no custo, que e uso normal da tabela.
 *
 * <p><b>Por que o circulo e um botao e nao uma caixa:</b> e uma escolha entre
 * cinco valores, entao digitar eGUARDAR o numero seria trabalho sem ganho. O
 * botao cicla 1 -&gt; 2 -&gt; 3 -&gt; 4 -&gt; 5 e o rotulo mostra em extenso, que
 * e o que o jogador procura na tabela impressa.
 *
 * <p><b>Por que o indice e a magia viajam no construtor:</b> o mesmo motivo do
 * item: o {@code UPDATE} sai pelo indice da <b>lista guardada</b>, e um
 * UPDATE atrasado (a magia foi apagada entre o clique e o pacote) precisa poder
 * ser recusado pelo servidor em vez de recriar a magia.
 */
public class SpellFormScreen extends Screen {

    /** Altura de uma linha de campo (rotulo + caixa). */
    private static final int ROW_H = 22;

    /** Altura reservada aos botoes no rodape do painel. */
    private static final int FOOTER_H = 26;

    /** Espaco reservado ao rotulo a esquerda da caixa. */
    private static final int LABEL_W = 66;

    /** Os cinco campos de texto, na ordem em que saem no formulario. */
    private static final String[] FIELD_LABELS =
            {"Execution", "Range", "Target", "Duration", "Cost"};

    private final Screen parent;
    private final String targetName;

    /** Indice da magia editada, ou {@code -1} para uma magia nova. */
    private final int index;

    /** A magia original, como o pai leu da ficha; {@code null} quando e nova. */
    private final SheetData.Spell original;

    private EditBox nameBox;

    /** O botao Save, desativado enquanto o nome estiver vazio. 01/10/2026. */
    private Button saveButton;

    /**
     * Liga ou desliga o Save conforme o nome.
     *
     * <p><b>Por que so o cliente:</b> o servidor ja normaliza e limita o texto de
     * qualquer jeito; aqui e para o jogador ver que o botao esta indisponivel, e
     * nao descobrir isso so depois de clicar.
     */
    private void syncSaveEnabled() {
        if (saveButton != null) {
            saveButton.active = !nameBox.getValue().isBlank();
        }
    }
    private EditBox[] fieldBoxes = new EditBox[FIELD_LABELS.length];

    /** O circulo escolhido no formulario, de 1 a 5. */
    private int circle;

    /** O Y da linha do botao de circulo, para o rotulo seguir a caixa vizinha. */
    private int circleY;

    public SpellFormScreen(Screen parent, String targetName, int index, SheetData.Spell original) {
        super(Component.literal(index < 0 ? "New Magic" : "Edit Magic"));
        this.parent = parent;
        this.targetName = targetName == null ? "" : targetName;
        this.index = index;
        this.original = original;
    }

    /** A magia que a tela abre: a enviada pelo pai, ou uma magia vazia. */
    private SheetData.Spell currentSpell() {
        return original == null ? SheetData.Spell.EMPTY : original;
    }

    @Override
    protected void init() {
        SheetData.Spell current = currentSpell();
        circle = current.circle();

        int panelW = Math.max(220, Math.min(this.width - 20, 300));
        int panelX = (this.width - panelW) / 2;
        int boxX = panelX + 8 + labelW();
        int boxW = Math.max(40, panelW - 16 - labelW());

        int y = 40;

        // O circulo ganha a LINHA INTEIRA, acima do nome, e ocupa a mesma largura
        // e a mesma altura da caixa de texto.
        //
        // <p><b>Por que subiu de linha (01/10/2026):</b> ele vivia na coluna dos
        // rotulos, na altura da caixa do nome, com a largura {@code LABEL_W}. Como
        // {@code labelW()} e o rotulo mais largo entre os campos, um rotulo largo
        // empurrava {@code boxX} para a direita e o botao passava POR CIMA da caixa
        // do nome -- foi o que o usuario viu. Numa linha propria nao ha coluna
        // compartilhada, entao nao existe como invadir.
        //
        // <p><b>Por que do tamanho da caixa:</b> e o unico controle de valor
        // repetido (1 a 5), e um botao do tamanho da caixa de texto mostra o ciclo
        // inteiro como uma escolha, e nao como um botao espremido no canto.
        circleY = y;
        addRenderableWidget(Button.builder(
                        Component.literal(new SheetData.Spell("", circle, "", "", "", "", "").circleLabel()),
                        b -> {
                            circle = circle >= SheetData.Spell.CIRCLE_MAX
                                    ? SheetData.Spell.CIRCLE_MIN : circle + 1;
                            b.setMessage(Component.literal(
                                    new SheetData.Spell("", circle, "", "", "", "", "").circleLabel()));
                        })
                .bounds(boxX, y, boxW, ROW_H - 2)
                .tooltip(Tooltip.create(Component.literal("Círculo da magia (1 a 5)")))
                .build());
        y += ROW_H;

        nameBox = field("Name", boxX, y, boxW, current.name(), SheetData.Spell.SPELL_NAME_MAX);
        y += ROW_H;

        for (int i = 0; i < FIELD_LABELS.length; i++) {
            int maxLen = i == FIELD_LABELS.length - 1
                    ? SheetData.Spell.SPELL_COST_MAX : SheetData.Spell.SPELL_FIELD_MAX;
            fieldBoxes[i] = field(FIELD_LABELS[i], boxX, y, boxW, fieldValue(current, i), maxLen);
            y += ROW_H;
        }

        int buttonW = (panelW - 24) / 2;
        int buttonY = this.height - FOOTER_H;
        saveButton = addRenderableWidget(Button.builder(Component.literal("Save"), b -> save())
                .bounds(panelX + 8, buttonY, buttonW, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(panelX + panelW - 8 - buttonW, buttonY, buttonW, 20)
                .build());

        // O Save fica CINZA e sem clique enquanto o nome estiver vazio (01/10/2026),
        // pedido do usuario. Antes ele era clicavel e so nao fazia nada, o que
        // parecia botao quebrado. O `responder` refaz a conta a cada tecla, sem
        // rebuild, entao digitar o nome ja reativa o botao.
        syncSaveEnabled();
        nameBox.setResponder(text -> syncSaveEnabled());

        setInitialFocus(nameBox);
    }

    /** O valor do campo de indice dado, na ordem de {@link #FIELD_LABELS}. */
    private static String fieldValue(SheetData.Spell spell, int i) {
        return switch (i) {
            case 0 -> spell.execution();
            case 1 -> spell.range();
            case 2 -> spell.target();
            case 3 -> spell.duration();
            case 4 -> spell.cost();
            default -> "";
        };
    }

    /** Cria uma caixa de uma linha, com o rotulo desenhado a esquerda. */
    private EditBox field(String label, int x, int y, int w, String value, int maxLen) {
        EditBox box = new EditBox(this.font, x, y, w, 18, Component.literal(label));
        box.setMaxLength(maxLen);
        box.setValue(value == null ? "" : value);
        addRenderableWidget(box);
        return box;
    }

    /**
     * Rotulos das caixas: desenhados no render, ancorados na propria caixa (o
     * mesmo cuidado do {@link InventoryItemScreen#renderLabels}).
     */
    private void renderLabels(GuiGraphics graphics) {
        // O rotulo do circulo vem PRIMEIRO porque a linha dele e a de cima: e ele
        // que ancora a coluna de rotulos nesta tela (01/10/2026).
        drawLabel(graphics, "Círculo", circleY);
        drawLabel(graphics, "Name", nameBox.getY());
        for (int i = 0; i < FIELD_LABELS.length; i++) {
            drawLabel(graphics, FIELD_LABELS[i], fieldBoxes[i].getY());
        }
    }

    /** Um rotulo terminado 4 px antes da coluna das caixas. */
    private void drawLabel(GuiGraphics graphics, String text, int boxY) {
        int right = nameBox.getX() - 4;
        graphics.drawString(this.font, text, right - this.font.width(text),
                boxY + 5, CharacterSheetScreen.COL_LABEL, false);
    }

    /**
     * Largura da coluna dos rotulos: a maior das palavras, mais folga.
     *
     * <p><b>O rotulo do circulo entra na conta (01/10/2026):</b> ele vive na
     * propria linha agora, mas continua sendo o mais longo da coluna. Sem ele
     * aqui, {@code boxX} ficaria apertado e "Círculo" entraria por baixo do botao
     * que esta a direita dele.
     */
    private int labelW() {
        int widest = Math.max(LABEL_W, this.font.width("Círculo") + 6);
        for (String label : FIELD_LABELS) {
            widest = Math.max(widest, this.font.width(label) + 6);
        }
        return widest;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);
        graphics.drawString(this.font, this.title, (this.width - this.font.width(this.title)) / 2,
                8, CharacterSheetScreen.COL_TITLE, false);
        renderLabels(graphics);
        if (nameBox.getValue().isBlank()) {
            String warn = "Name required";
            graphics.drawString(this.font, warn, nameBox.getX(),
                    nameBox.getY() + nameBox.getHeight() + 3, 0xFF5555, false);
        }
    }

    /**
     * Monta a magia e envia.
     *
     * <p><b>Depois de salvar volta para a ficha e PEDE a ficha de novo:</b> o
     * mesmo motivo do {@link InventoryItemScreen#save} -- o eco deste pacote
     * chega enquanto esta tela ainda esta aberta e seria descartado, e a lista
     * ficaria mostrando a magia velha.
     */
    private void save() {
        // Nome obrigatorio (01/10/2026): o mesmo motivo e a mesma defesa da
        // SkillFormScreen#save -- sem nome, a magia vira um botao vazio na lista.
        // Esta guarda e a rede de seguranca: com o botao desativado nao se chega
        // aqui por clique, mas `Save` nao deve depender disso para ser correto.
        if (nameBox.getValue().isBlank()) {
            nameBox.setFocused(true);
            setInitialFocus(nameBox);
            return;
        }
        SheetData.Spell spell = new SheetData.Spell(
                nameBox.getValue(), circle,
                fieldBoxes[0].getValue(), fieldBoxes[1].getValue(),
                fieldBoxes[2].getValue(), fieldBoxes[3].getValue(),
                fieldBoxes[4].getValue());
        ClientPlayNetworking.send(index < 0 || original == null
                ? RpgNetworking.SheetSpellPayload.add(targetName, spell)
                : RpgNetworking.SheetSpellPayload.update(targetName, index, spell));
        onClose();
        TabletopRpgClient.requestSheet(targetName);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }
}
