package com.pedro.tabletoprpg.client;

import com.pedro.tabletoprpg.RpgNetworking;
import com.pedro.tabletoprpg.SheetData;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tela "Skills" da ficha: lista de pericias, adicionar e remover.
 *
 * <p>Menu separado do Status (feedback do usuario). O Back leva de volta para
 * o Status da mesma ficha, e nao para o menu, para navegar Status &lt;-&gt;
 * Skills sem passar pelo menu.
 *
 * <p><b>Descricao da skill (feedback do usuario):</b> cada pericia tem
 * nome + descricao. A descricao e digitada junto com o nome e aparece num
 * tooltip quando o mouse passa sobre o botao da skill. O tooltip e desenhado
 * por conta propria ({@code renderDescriptionTooltip}) em vez de usar a API
 * do vanilla: em 1.21.11 ela virou
 * {@code GuiGraphics.renderTooltip(Font, List<ClientTooltipComponent>, ...)}
 * com um {@code ClientTooltipPositioner} obrigatorio, sem overload simples
 * para {@code Component} -- um painel proprio usa {@code font.split} (que ja
 * quebra o texto em linhas) e evita depender dessa assinatura.
 *
 * <p><b>Valor e atributo por pericia (feedback do usuario):</b> cada linha
 * tem {@code [<] valor [>]} e um botao de lista suspensa com o atributo que a
 * pericia soma. Sao as mesmas setas dos atributos no Status, para o mestre
 * editar tudo do mesmo jeito.
 *
 * <p><b>Ordem dos campos no rodape (feedback do usuario):</b> o campo de
 * <b>nome fica ACIMA do de descricao</b>. Antes era o contrario (descricao em
 * cima, nome ao lado do Add), o que invertia a ordem logica de preenchimento.
 */
public class SkillsScreen extends CharacterSheetScreen {

    /** Numero maximo de pericias exibidas por vez (o layout reduz em telas baixas). */
    private static final int MAX_SKILL_ROWS = 8;

    /** Lado das setas de valor: menores que as de recurso, que dividem a linha com a barra. */
    private static final int ARROW_SMALL = 14;
    /** Largura reservada ao numero do valor entre as duas setas. */
    private static final int VALUE_W = 22;
    /** Largura do botao de lista suspensa do atributo (cabe "FOR"). */
    private static final int DROPDOWN_W = 46;

    /** Largura maxima do tooltip de descricao antes de quebrar o texto. */
    private static final int TOOLTIP_MAX_W = 180;
    /** Cores do painel de tooltip. */
    private static final int COL_TOOLTIP_BG = 0xF0101014;
    private static final int COL_TOOLTIP_EDGE = 0xFF6A6A72;

    /** Botao de remocao de cada linha (clicar no nome remove, como antes). */
    private final Button[] skillButtons = new Button[MAX_SKILL_ROWS];
    /** Seta "-" do valor de cada linha. */
    private final Button[] minusButtons = new Button[MAX_SKILL_ROWS];
    /** Seta "+" do valor de cada linha. */
    private final Button[] plusButtons = new Button[MAX_SKILL_ROWS];
    /** Botao de lista suspensa do atributo de cada linha. */
    private final Button[] attrButtons = new Button[MAX_SKILL_ROWS];
    /** X onde o numero do valor e desenhado (o widget e' o valor). */
    private final int[] valueXs = new int[MAX_SKILL_ROWS];

    /**
     * Valor otimista por pericia, para cliques rapidos nao se perderem.
     *
     * <p>Espelha o {@code pendingNumeric} da classe base: enquanto o servidor
     * nao responde, a tela ja mostra o proximo valor. Limpo a cada ficha
     * recebida, quando o valor autoritativo assume.
     */
    private final Map<String, Integer> pendingValue = new HashMap<>();
    /** Atributo otimista por pericia, mesmo proposito de {@link #pendingValue}. */
    private final Map<String, SheetData.Attribute> pendingAttribute = new HashMap<>();

    /** Quantas linhas cabem na janela atual. */
    private int skillRows;
    /** Primeiro indice visivel da lista. */
    private int skillScroll;
    /** Caixa para digitar o nome de uma nova pericia. */
    private EditBox skillInput;
    /** Caixa para digitar a descricao da nova pericia (opcional). */
    private EditBox descInput;

    private boolean suppressNotify;

    public SkillsScreen(String targetName, Screen menuReturn) {
        super("Character Skills", targetName, menuReturn);
    }

    @Override
    protected void buildPanel(int x0, int panelW, int topY, int bottomY) {
        // 3 linhas fixas no rodape: nome, descricao e (via botao) Add.
        rowH = fitRowHeight(3, topY, bottomY);

        int y = addSection("Skills", x0, topY);

        // Rodape de cima para baixo: nome -> descricao -> Add. O nome ACIMA da
        // descricao e o pedido do usuario; Add continua no fim, colado no Back.
        int backY = this.height - 24;
        int addY = backY - rowH - 2;
        int descY = addY - rowH - 2;
        int nameY = descY - rowH - 2;
        skillRows = Math.max(0, Math.min(MAX_SKILL_ROWS, (nameY - y) / rowH));
        skillScroll = 0;

        // Geometria das colunas da direita, compartilhada por todas as linhas:
        // [ nome ][<] valor [>][ atributo v ]
        int attrX = x0 + panelW - DROPDOWN_W;
        int plusX = attrX - 4 - ARROW_SMALL;
        int valX = plusX - 4 - VALUE_W;
        int minusX = valX - 4 - ARROW_SMALL;
        int nameW = Math.max(24, minusX - 4 - x0);

        for (int i = 0; i < MAX_SKILL_ROWS; i++) {
            final int index = i;
            int rowY = y + i * rowH;
            int h = rowH - 2;

            skillButtons[i] = Button.builder(Component.literal(""), b -> removeSkillAt(index))
                    .bounds(x0, rowY, nameW, h)
                    .build();
            minusButtons[i] = Button.builder(Component.literal("<"),
                    b -> stepSkillValue(index, -1)).bounds(minusX, rowY, ARROW_SMALL, h).build();
            plusButtons[i] = Button.builder(Component.literal(">"),
                    b -> stepSkillValue(index, 1)).bounds(plusX, rowY, ARROW_SMALL, h).build();
            attrButtons[i] = Button.builder(Component.literal(""),
                    b -> openAttributePicker(index)).bounds(attrX, rowY, DROPDOWN_W, h).build();
            valueXs[i] = valX;

            boolean visible = i < skillRows;
            for (Button button : rowWidgets(i)) {
                button.visible = visible;
                if (visible) {
                    addRenderableWidget(button);
                }
            }
        }

        // Adicionar pericia: nome -> descricao -> Add (nesta ordem).
        skillInput = new EditBox(this.font, x0, nameY, panelW - 4, rowH - 2,
                Component.literal("new skill"));
        skillInput.setMaxLength(SheetData.SKILL_MAX);
        skillInput.setTextColor(COL_BOX_TEXT);
        skillInput.setTextColorUneditable(COL_BOX_TEXT_OFF);
        skillInput.setHint(Component.literal("name"));
        skillInput.setEditable(canEdit);
        addRenderableWidget(skillInput);

        descInput = new EditBox(this.font, x0, descY, panelW - 4, rowH - 2,
                Component.literal("description"));
        // Teto igual ao do modelo: acima disso o codec do servidor lancaria
        // excecao ao decodificar.
        descInput.setMaxLength(SheetData.SKILL_DESC_MAX);
        descInput.setTextColor(COL_BOX_TEXT);
        descInput.setTextColorUneditable(COL_BOX_TEXT_OFF);
        descInput.setHint(Component.literal("description (optional)"));
        descInput.setEditable(canEdit);
        addRenderableWidget(descInput);

        addRenderableWidget(Button.builder(Component.literal("Add"), b -> addSkill())
                .bounds(x0, addY, panelW - 4, rowH - 2)
                .build());
    }

    /** Os 4 botoes de uma linha, na ordem: remover, -, +, atributo. */
    private List<Button> rowWidgets(int row) {
        return List.of(skillButtons[row], minusButtons[row], plusButtons[row], attrButtons[row]);
    }

    // ------------------------------------------------------------------
    // SINCRONIA
    // ------------------------------------------------------------------

    @Override
    protected void applyExtraState() {
        if (skillInput != null) {
            skillInput.setEditable(canEdit);
        }
        if (descInput != null) {
            descInput.setEditable(canEdit);
        }
        for (int i = 0; i < MAX_SKILL_ROWS; i++) {
            for (Button button : rowWidgets(i)) {
                if (button != null) {
                    button.active = canEdit;
                }
            }
        }
        clampSkillScroll();
    }

    /**
     * O servidor respondeu: o valor autoritativo substitui o otimista das
     * setas e do dropdown.
     *
     * <p>Fica em {@code onSheetReceived} e nao em {@code applyExtraState}
     * porque abrir o dropdown recria os widgets ({@code init()}) no mesmo
     * instante em que o novo valor foi escolhido — limpando aqui, o usuario
     * veria o valor antigo ate a ficha voltar.
     */
    @Override
    protected void onSheetReceived() {
        pendingValue.clear();
        pendingAttribute.clear();
    }

    /**
     * Mantem o scroll dentro da lista depois de uma atualizacao remota: se a
     * lista encolheu e o scroll apontava alem do fim, a tela mostraria linhas
     * vazias sem forma de corrigir.
     */
    private void clampSkillScroll() {
        if (sheet == null || skillRows <= 0) {
            skillScroll = 0;
            return;
        }
        int maxScroll = Math.max(0, sheet.skills().size() - skillRows);
        skillScroll = Math.max(0, Math.min(maxScroll, skillScroll));
    }

    /** Indice da pericia exibida na linha {@code row}, ou -1 se nao houver. */
    private int visibleSkillIndex(int row) {
        if (sheet == null || skillRows <= 0) {
            return -1;
        }
        int index = skillScroll + row;
        return index < sheet.skills().size() ? index : -1;
    }

    /** A pericia da linha, ou {@code null} se a linha estiver vazia. */
    private SheetData.Skill skillAt(int row) {
        int index = visibleSkillIndex(row);
        return index < 0 ? null : sheet.skills().get(index);
    }

    /** Procura uma pericia pelo nome (mesma comparacao do servidor, sem diferenciar caixa). */
    private SheetData.Skill findSkill(String name) {
        if (sheet == null || name == null || name.isBlank()) {
            return null;
        }
        for (SheetData.Skill skill : sheet.skills()) {
            if (skill.name().equalsIgnoreCase(name)) {
                return skill;
            }
        }
        return null;
    }

    /** Valor a exibir: o otimista se houver, senao o do servidor. */
    private int displayValue(SheetData.Skill skill) {
        Integer pending = pendingValue.get(skill.name());
        return pending != null ? pending : skill.value();
    }

    /** Atributo a exibir: o otimista se houver, senao o do servidor. */
    private SheetData.Attribute displayAttribute(SheetData.Skill skill) {
        SheetData.Attribute pending = pendingAttribute.get(skill.name());
        return pending != null ? pending : skill.attribute();
    }

    // ------------------------------------------------------------------
    // ACOES
    // ------------------------------------------------------------------

    private void addSkill() {
        if (!canEdit || skillInput == null) {
            return;
        }
        String value = skillInput.getValue();
        if (value.isBlank()) {
            return;
        }
        String description = descInput == null ? "" : descInput.getValue();
        // Nome que JAI existe = o mestre esta editando. O servidor atualiza os
        // 4 campos, entao mandar valor minimo e DES aqui apagaria o ajuste das
        // setas sem avisar. Reaproveita o valor/atributo atuais.
        SheetData.Skill existing = findSkill(value.trim());
        ClientPlayNetworking.send(RpgNetworking.SheetSkillPayload.add(
                targetName, value.trim(), description,
                existing == null ? SheetData.Skill.VALUE_MIN : existing.value(),
                existing == null ? SheetData.Attribute.DEXTERITY : existing.attribute()));
        // Limpa as caixas; os valores reais so entram quando o servidor
        // confirmar (a resposta pode recusar: nome duplicado, lista cheia).
        suppressNotify = true;
        skillInput.setValue("");
        if (descInput != null) {
            descInput.setValue("");
        }
        suppressNotify = false;
    }

    private void removeSkillAt(int row) {
        if (!canEdit) {
            return;
        }
        SheetData.Skill skill = skillAt(row);
        if (skill == null) {
            return;
        }
        // Na remocao so o nome importa: a descricao e o resto vao vazios de
        // proposito (o servidor remove pela comparacao de nome, sem diferenciar
        // caixa) e o cliente limpa o otimismo local para nao desenhar valor de
        // uma pericia que sumiu.
        pendingValue.remove(skill.name());
        pendingAttribute.remove(skill.name());
        ClientPlayNetworking.send(RpgNetworking.SheetSkillPayload.remove(targetName, skill.name()));
    }

    /**
     * Muda o valor (0-3) da pericia da linha e avisa o servidor.
     *
     * <p>Envia apenas o valor ({@code SET_VALUE}): se o payload mandasse a
     * descricao de volta, ela poderia sobrescrever o que o mestre digitou na
     * ficha.
     */
    private void stepSkillValue(int row, int delta) {
        if (!canEdit) {
            return;
        }
        SheetData.Skill skill = skillAt(row);
        if (skill == null) {
            return;
        }
        int next = Math.max(SheetData.Skill.VALUE_MIN,
                Math.min(SheetData.Skill.VALUE_MAX, displayValue(skill) + delta));
        if (next == displayValue(skill)) {
            return; // ja no limite: nao envia nada
        }
        pendingValue.put(skill.name(), next);
        ClientPlayNetworking.send(RpgNetworking.SheetSkillPayload.setValue(
                targetName, skill.name(), next));
    }

    /** Abre a lista suspensa dos 6 atributos para a pericia da linha. */
    private void openAttributePicker(int row) {
        if (!canEdit || this.minecraft == null) {
            return;
        }
        SheetData.Skill skill = skillAt(row);
        if (skill == null) {
            return;
        }
        this.minecraft.setScreen(new AttributePickerScreen(
                this, skill.name(), displayAttribute(skill), chosen -> {
                    pendingAttribute.put(skill.name(), chosen);
                    ClientPlayNetworking.send(RpgNetworking.SheetSkillPayload.setAttribute(
                            targetName, skill.name(), chosen));
                }));
    }

    /**
     * Back vai para o Status da mesma ficha (nao para o menu).
     *
     * <p>Precisa pedir a ficha de novo: a tela nova nasce sem estado, e sem o
     * pedido ela ficaria presa em "Loading" (a resposta antiga ja foi
     * consumida por esta tela).
     */
    @Override
    protected void close() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(new StatusScreen(targetName, returnScreen()));
            TabletopRpgClient.requestSheet(targetName);
        }
    }

    // ------------------------------------------------------------------
    // SCROLL E DESENHO
    // ------------------------------------------------------------------

    /** Rola a lista de pericias (assinatura estavel de 4 doubles). */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        if (sheet != null && skillRows > 0 && sheet.skills().size() > skillRows) {
            int maxScroll = Math.max(0, sheet.skills().size() - skillRows);
            skillScroll = Math.max(0, Math.min(maxScroll, skillScroll + (deltaY > 0 ? 1 : -1)));
        }
        return true;
    }

    @Override
    protected void renderContent(GuiGraphics graphics, int mouseX, int mouseY) {
        if (sheet == null) {
            return;
        }

        // Descricao em foco: a pericia sob o mouse. Calculado aqui (e nao no
        // laco de botoes) para o tooltip ser desenhado por ultimo, acima de
        // tudo que ja foi pintado.
        String hoveredDescription = null;

        for (int i = 0; i < skillRows; i++) {
            SheetData.Skill skill = skillAt(i);
            if (skill == null) {
                for (Button button : rowWidgets(i)) {
                    button.visible = false;
                    button.setMessage(Component.literal(""));
                }
                continue;
            }

            for (Button button : rowWidgets(i)) {
                button.visible = true;
                button.active = canEdit;
            }

            String name = skill.name();
            int maxChars = Math.max(6, (nameWidth() - 12) / 6);
            String shown = name.length() > maxChars ? name.substring(0, maxChars) + "..." : name;
            // Marcador quando a pericia tem descricao: sem ele nao ha como
            // saber que passar o mouse abre alguma coisa.
            String mark = skill.description().isEmpty() ? "" : " *";
            skillButtons[i].setMessage(Component.literal("- " + shown + mark));

            // Valor numerico desenhado aqui (entre as setas), nao como widget.
            String valueText = Integer.toString(displayValue(skill));
            int vy = skillButtons[i].getY() + (skillButtons[i].getHeight() - 8) / 2;
            graphics.drawString(this.font, valueText,
                    valueXs[i] + (VALUE_W - this.font.width(valueText)) / 2, vy, COL_BOX_TEXT, false);

            // Botao de atributo mostra a abreviacao do atributo escolhido.
            SheetData.Attribute attr = displayAttribute(skill);
            attrButtons[i].setMessage(Component.literal(attr.abbr()));

            if (buttonOver(skillButtons[i], mouseX, mouseY) && !skill.description().isEmpty()) {
                hoveredDescription = skill.description();
            }
        }

        renderDescriptionTooltip(graphics, mouseX, mouseY, hoveredDescription);

        if (skillRows == 0) {
            graphics.drawString(this.font, "No room for skills",
                    panelX + 4, contentTop + rowH, COL_MUTED, false);
        } else if (sheet.skills().isEmpty()) {
            graphics.drawString(this.font, "No skills yet",
                    panelX + 4, contentTop + rowH, COL_MUTED, false);
        }
    }

    /**
     * Testa o mouse sobre um botao, mas so quando ele esta visivel.
     *
     * <p>{@code Button.isMouseOver} devolve {@code true} para um botae
     * invisivel se as coordenadas baterem, o que faria o tooltip de descricao
     * aparecer sobre uma linha que nao existe.
     */
    private boolean buttonOver(Button button, int mouseX, int mouseY) {
        return button.visible && button.active && button.isMouseOver(mouseX, mouseY);
    }

    /** Largura da coluna de nome, reconstruida a partir do botao da linha 0. */
    private int nameWidth() {
        return skillButtons[0] == null ? 60 : skillButtons[0].getWidth();
    }

    /**
     * Painel de tooltip com a descricao da pericia sob o mouse.
     *
     * <p>Faz o proprio desenho em vez de chamar a API de tooltip do vanilla:
     * em 1.21.11 ela exige montar {@code ClientTooltipComponent} e um
     * {@code ClientTooltipPositioner}, e nao ha overload simples para
     * {@code Component}. {@code font.split} ja devolve as linhas quebradas,
     * entao o trabalho aqui e so a moldura + o posicionamento.
     *
     * <p>Fica ao lado do cursor e se vira para o lado de dentro quando nao
     * cabe (mesmo comportamento do tooltip vanilla, sem depender dele).
     */
    private void renderDescriptionTooltip(GuiGraphics graphics, int mouseX, int mouseY,
                                          String description) {
        if (description == null || description.isEmpty()) {
            return;
        }
        List<FormattedCharSequence> lines =
                this.font.split(Component.literal(description), TOOLTIP_MAX_W);
        if (lines.isEmpty()) {
            return;
        }
        final int pad = 4;
        final int lineH = 10;
        int textW = 0;
        for (FormattedCharSequence line : lines) {
            textW = Math.max(textW, (int) this.font.width(line));
        }
        int boxW = Math.min(TOOLTIP_MAX_W, textW) + pad * 2;
        int boxH = lines.size() * lineH + pad * 2;

        int x = mouseX + 12;
        int y = mouseY + 12;
        if (x + boxW > this.width - 2) {
            x = Math.max(2, mouseX - 12 - boxW);
        }
        if (y + boxH > this.height - 2) {
            y = Math.max(2, this.height - 2 - boxH);
        }

        graphics.fill(x, y, x + boxW, y + boxH, COL_TOOLTIP_BG);
        graphics.fill(x, y, x + boxW, y + 1, COL_TOOLTIP_EDGE);
        graphics.fill(x, y + boxH - 1, x + boxW, y + boxH, COL_TOOLTIP_EDGE);
        graphics.fill(x, y, x + 1, y + boxH, COL_TOOLTIP_EDGE);
        graphics.fill(x + boxW - 1, y, x + boxW, y + boxH, COL_TOOLTIP_EDGE);
        for (int i = 0; i < lines.size(); i++) {
            graphics.drawString(this.font, lines.get(i),
                    x + pad, y + pad + i * lineH, COL_BOX_TEXT, false);
        }
    }

    /**
     * Contador de rolagem na faixa superior: antes ficava a {@code height - 40}
     * e colidia com a linha "Add" em janelas baixas.
     */
    @Override
    protected void renderTopLeft(GuiGraphics graphics) {
        if (sheet == null || skillRows <= 0) {
            return;
        }
        if (sheet.skills().isEmpty()) {
            graphics.drawString(this.font, "0 skills", panelX + 4, 24, COL_MUTED, false);
            return;
        }
        if (sheet.skills().size() > skillRows) {
            int first = skillScroll + 1;
            int last = Math.min(skillScroll + skillRows, sheet.skills().size());
            graphics.drawString(this.font,
                    "skills " + first + "-" + last + " / " + sheet.skills().size() + " (scroll)",
                    panelX + 4, 24, COL_MUTED, false);
        } else {
            graphics.drawString(this.font,
                    sheet.skills().size() + " skills", panelX + 4, 24, COL_MUTED, false);
        }
    }
}
