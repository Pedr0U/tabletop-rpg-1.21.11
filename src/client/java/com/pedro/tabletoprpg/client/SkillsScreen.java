package com.pedro.tabletoprpg.client;

import com.pedro.tabletoprpg.RpgNetworking;
import com.pedro.tabletoprpg.SheetData;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * Tela "Skills" da ficha: a lista <b>live</b> de skills do personagem.
 *
 * <p>Menu separado do Status (feedback do usuario). O Back leva de volta para
 * o Status da mesma ficha, e nao para o menu, para navegar Status &lt;-&gt;
 * Skills sem passar pelo menu.
 *
 * <p><b>Skill e pericia sao coisas diferentes (decisao do usuario em
 * 25/09/2026):</b> aqui so existe <b>skills</b> -- nome + descricao, uma lista
 * que o jogador monta do zero e pode apagar. As <b>pericias</b> (que tem valor
 * e atributo, e sao fixas em codigo) foram para a aba Status, em
 * {@link StatusScreen}.
 *
 * <p><b>Descricao sem limite de uso (feedback do usuario):</b> a descricao e
 * digitada no campo de baixo e guardada inteira. O corte acontece so na
 * <b>exibicao</b>:
 * <ul>
 *   <li>passar o mouse sobre a linha mostra um <b>trecho</b>
 *       ({@link #HOVER_PREVIEW_CHARS} caracteres);</li>
 *   <li>clicar na linha abre um <b>popup com a descricao completa</b>, logo
 *       abaixo da lista, com rolagem propria quando o texto nao cabe.</li>
 * </ul>
 *
 * <p><b>Remover:</b> botao "X" no fim de cada linha. Antes o clique no nome
 * removia, mas o nome agora abre a descricao, entao a remocao ganhou botao
 * proprio.
 *
 * <p><b>Ordem dos campos no rodape (feedback do usuario):</b> o campo de
 * <b>nome fica ACIMA do de descricao</b>, porque e a ordem logica de
 * preenchimento.
 */
public class SkillsScreen extends CharacterSheetScreen {

    /** Numero maximo de skills exibidas por vez (o layout reduz em telas baixas). */
    private static final int MAX_SKILL_ROWS = 8;

    /** Largura do botao "X" de remocao no fim de cada linha. */
    private static final int REMOVE_W = 16;

    /**
     * Altura da faixa reservada ao popup de descricao, entre a lista e o rodape.
     *
     * <p>Fica reservada sempre (nao so quando ha popup) para o layout nao pular
     * quando o usuario abre e fecha a descricao.
     */
    private static final int POPUP_H = 56;

    /** Quantos caracteres o hover mostra antes de cortar. */
    private static final int HOVER_PREVIEW_CHARS = 100;

    /** Numero de linhas de texto que cabem na faixa do popup. */
    private static final int POPUP_LINE_H = 10;

    /** Cores do painel de tooltip/popup. */
    private static final int COL_TOOLTIP_BG = 0xF0101014;
    private static final int COL_TOOLTIP_EDGE = 0xFF6A6A72;
    /** Trilho da barra de rolagem: escuro, para contrastar com o polegar. */
    private static final int COL_SCROLL_TRACK = 0xFF303038;
    /** Polegar da barra de rolagem: claro, para ficar visivel de primeira. */
    private static final int COL_SCROLL_THUMB = 0xFFE8E8EE;
    /** Cor da barra que marca a skill selecionada. */
    private static final int COL_SELECTED = 0xFFFFC24D;

    /** Botao do nome de cada linha: clicar abre a descricao completa. */
    private final Button[] nameButtons = new Button[MAX_SKILL_ROWS];
    /** Botao "X" de remocao de cada linha. */
    private final Button[] removeButtons = new Button[MAX_SKILL_ROWS];

    /** Quantas linhas cabem na janela atual. */
    private int skillRows;
    /** Primeiro indice visivel da lista. */
    private int skillScroll;

    /** Retangulo da faixa do popup, guardado em init() para o render. */
    private int popupX;
    private int popupY;
    private int popupW;

    /**
     * Skill cuja descricao esta aberta no popup, ou {@code null}.
     *
     * <p>Guarda o <b>nome</b>, nao o objeto: a ficha chega de novo do servidor
     * a cada edicao, e um objeto velho mostraria descricao desatualizada.
     */
    private String selectedSkill;
    /** Rolagem do texto dentro do popup. */
    private int popupScroll;

    // ---------------------------------------------------------------
    // BARRA DE ROLAGEM DO POPUP (visivel, clicavel e arrastavel)
    // ---------------------------------------------------------------

    /** Trilho da barra, calculado no render e usado no clique/arrasto. */
    private int barTrackX;
    private int barTrackY;
    private int barTrackH;
    /** Polegar da barra. */
    private int barThumbY;
    private int barThumbH;
    private boolean draggingScrollbar;

    // ---- Barra de rolagem da LISTA de skills (sistema separado do popup) ----
    // O usuario pediu "a barrinha na lateral para eu arrastar igual tem na
    // descricao das skills", entao a lista ganhou o mesmo trilho/polegar e o
    // mesmo clique-e-arraste. Geometria e estado sao separados dos do popup
    // para nao interferir no que ja funciona.
    private int listBarX;
    private int listBarY;
    private int listBarH;
    private int listThumbY;
    private int listThumbH;
    private boolean draggingListBar;

    private static final int BAR_W = 6;
    private static final int BAR_PAD = 3;

    /** Caixa para digitar o nome de uma nova skill. */
    private EditBox skillInput;
    /** Caixa para digitar a descricao da nova skill (opcional). */
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

        // A lista para antes da faixa do popup, e o popup fica entre a lista e
        // o campo de nome -- ou seja, abaixo da lista, como pedido.
        int listBottom = nameY - 6 - POPUP_H;
        skillRows = Math.max(0, Math.min(MAX_SKILL_ROWS, (listBottom - y) / rowH));
        skillScroll = 0;

        popupX = x0;
        popupY = y + skillRows * rowH + 4;
        popupW = panelW - 4;
        popupScroll = 0;

        // Geometria das colunas: [ nome ..................... ][ X ]
        int removeX = x0 + panelW - REMOVE_W;
        // A barra da lista fica entre o nome e o botao X. So reserva espaco
        // quando a lista realmente transborda, para nao estreitar o nome sem
        // necessidade.
        boolean listaTransborda = sheet != null && skillRows > 0
                && sheet.skills().size() > skillRows;
        int barReserved = listaTransborda ? BAR_W + BAR_PAD : 0;
        int nameW = Math.max(24, removeX - 4 - barReserved - x0);

        listBarX = removeX - BAR_PAD - BAR_W;
        listBarY = y;
        listBarH = Math.max(1, skillRows * rowH);
        draggingListBar = false;

        for (int i = 0; i < MAX_SKILL_ROWS; i++) {
            final int index = i;
            int rowY = y + i * rowH;
            int h = rowH - 2;

            nameButtons[i] = Button.builder(Component.literal(""),
                    b -> selectSkill(index)).bounds(x0, rowY, nameW, h).build();
            removeButtons[i] = Button.builder(Component.literal("x"),
                    b -> removeSkillAt(index)).bounds(removeX, rowY, REMOVE_W, h).build();

            boolean visible = i < skillRows;
            for (Button button : rowWidgets(i)) {
                button.visible = visible;
                if (visible) {
                    addRenderableWidget(button);
                }
            }
        }

        // Adicionar skill: nome -> descricao -> Add (nesta ordem).
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

    /** Os 2 botoes de uma linha, na ordem: abrir descricao, remover. */
    private List<Button> rowWidgets(int row) {
        return List.of(nameButtons[row], removeButtons[row]);
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
     * Fecha o popup se a skill selecionada sumiu (foi removida por outra
     * pessoa, ou a lista encolheu), para nao sobrar um popup vazio.
     */
    @Override
    protected void onSheetReceived() {
        if (selectedSkill != null && findSkill(selectedSkill) == null) {
            selectedSkill = null;
            popupScroll = 0;
        }
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

    /** Indice da skill exibida na linha {@code row}, ou -1 se nao houver. */
    private int visibleSkillIndex(int row) {
        if (sheet == null || skillRows <= 0) {
            return -1;
        }
        int index = skillScroll + row;
        return index < sheet.skills().size() ? index : -1;
    }

    /**
     * Desenha o trilho e o polegar da LISTA, no mesmo estilo do popup.
     *
     * <p>Proporcional como no popup: quanto mais itens, menor o polegar, com
     * minimo de 8px para continuar clicavel.
     */
    private void renderListScrollbar(GuiGraphics graphics) {
        listThumbH = 0;
        if (sheet == null || skillRows <= 0 || sheet.skills().size() <= skillRows) {
            return;
        }
        graphics.fill(listBarX, listBarY, listBarX + BAR_W, listBarY + listBarH,
                COL_SCROLL_TRACK);
        int total = sheet.skills().size();
        int maxScroll = total - skillRows;
        listThumbH = Math.max(8, listBarH * skillRows / total);
        if (listThumbH > listBarH) {
            listThumbH = listBarH;
        }
        int desloca = listBarH - listThumbH;
        listThumbY = listBarY + (maxScroll == 0 ? 0 : desloca * skillScroll / maxScroll);
        graphics.fill(listBarX, listThumbY, listBarX + BAR_W, listThumbY + listThumbH,
                COL_SCROLL_THUMB);
    }

    /** O cursor esta sobre a barra da lista? */
    private boolean onListScrollbar(double mouseX, double mouseY) {
        if (sheet == null || skillRows <= 0 || sheet.skills().size() <= skillRows) {
            return false;
        }
        return mouseX >= listBarX - BAR_PAD && mouseX < listBarX + BAR_W + BAR_PAD
                && mouseY >= listBarY && mouseY < listBarY + listBarH;
    }

    /** Converte a posicao do mouse no trilho para o valor de scroll da lista. */
    private void setSkillScrollFromMouse(double mouseY) {
        if (sheet == null || skillRows <= 0) {
            return;
        }
        int maxScroll = Math.max(0, sheet.skills().size() - skillRows);
        if (maxScroll <= 0) {
            skillScroll = 0;
            return;
        }
        int Useful = Math.max(1, listBarH - listThumbH);
        int delta = (int) (mouseY - listBarY - listThumbH / 2);
        skillScroll = Math.max(0, Math.min(maxScroll, delta * maxScroll / Useful));
    }

    /** A skill da linha, ou {@code null} se a linha estiver vazia. */
    private SheetData.Skill skillAt(int row) {
        int index = visibleSkillIndex(row);
        return index < 0 ? null : sheet.skills().get(index);
    }

    /** Procura uma skill pelo nome (mesma comparacao do servidor, sem diferenciar caixa). */
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

    // ------------------------------------------------------------------
    // ACOES
    // ------------------------------------------------------------------

    private void addSkill() {
        if (!canEdit || skillInput == null) {
            return;
        }
        String name = skillInput.getValue().trim();
        if (name.isBlank()) {
            return;
        }
        String description = descInput == null ? "" : descInput.getValue();
        // Nome novo cria a skill; nome que ja existe ATUALIZA a descricao (o
        // servidor decide, comparando sem diferenciar caixa). A skill nao tem
        // valor nem atributo, entao nao ha mais campo para reaproveitar aqui.
        ClientPlayNetworking.send(RpgNetworking.SheetSkillPayload.add(targetName, name, description));
        // Limpa as caixas; os valores reais so entram quando o servidor
        // confirmar (a resposta pode recusar: lista cheia).
        suppressNotify = true;
        skillInput.setValue("");
        if (descInput != null) {
            descInput.setValue("");
        }
        suppressNotify = false;
    }

    /** Abre a descricao da linha, ou fecha se for a mesma skill (toggle). */
    private void selectSkill(int row) {
        SheetData.Skill skill = skillAt(row);
        if (skill == null) {
            return;
        }
        if (skill.name().equalsIgnoreCase(selectedSkill)) {
            selectedSkill = null;
            popupScroll = 0;
            return;
        }
        selectedSkill = skill.name();
        popupScroll = 0;
    }

    private void removeSkillAt(int row) {
        if (!canEdit) {
            return;
        }
        SheetData.Skill skill = skillAt(row);
        if (skill == null) {
            return;
        }
        // Se a skill removida era a do popup, fecha: senao o popup ficaria
        // aberto mostrando uma skill que acabou de sumir.
        if (skill.name().equalsIgnoreCase(selectedSkill)) {
            selectedSkill = null;
            popupScroll = 0;
        }
        // Na remocao so o nome importa: a descricao vai vazia de proposito (o
        // servidor remove pela comparacao de nome).
        ClientPlayNetworking.send(RpgNetworking.SheetSkillPayload.remove(targetName, skill.name()));
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

    /**
     * Rola a lista de skills, ou o texto do popup quando ele esta aberto.
     *
     * <p>Ordem importa: com o popup aberto, a roda rola a descricao (que e o
     * que o usuario esta lendo) e a lista so quando o popup ja chegou ao fim.
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        // Convencao do vanilla: deltaY NEGATIVO e rolar para BAIXO, e rolar
        // para baixo tem que AVANCAR o conteudo. Antes o passo era
        // `deltaY > 0 ? -1 : 1` combinado com `scroll - step`, o que dava
        // scroll - 1 ao rolar para baixo: voltava para o inicio. Invertido, e
        // como a expressao era a mesma nos dois sistemas, o popup tambem
        // estava invertido (o usuario validou o popup arrastando a barra, que
        // funciona por construcao, e nao pela roda).
        int step = deltaY < 0 ? 1 : -1;
        if (popupLines() != null) {
            int maxScroll = Math.max(0, popupLines().size() - popupVisibleLines());
            if (popupScroll < maxScroll) {
                popupScroll = Math.max(0, Math.min(maxScroll, popupScroll + step));
                return true;
            }
        }
        if (sheet != null && skillRows > 0 && sheet.skills().size() > skillRows) {
            int maxScroll = Math.max(0, sheet.skills().size() - skillRows);
            skillScroll = Math.max(0, Math.min(maxScroll, skillScroll + step));
        }
        return true;
    }

    @Override
    protected void renderContent(GuiGraphics graphics, int mouseX, int mouseY) {
        if (sheet == null) {
            return;
        }

        // Trecho da descricao da skill sob o mouse. Calculado aqui (e nao no
        // laco de botoes) para o tooltip ser desenhado por ultimo, acima de
        // tudo que ja foi pintado.
        String hoveredPreview = null;

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
            // Marcador quando a skill tem descricao: sem ele nao ha como saber
            // que clicar na linha abre alguma coisa.
            String mark = skill.description().isEmpty() ? "" : " *";
            nameButtons[i].setMessage(Component.literal(shown + mark));

            // Barra lateral na skill selecionada, desenhada depois dos widgets.
            if (skill.name().equalsIgnoreCase(selectedSkill)) {
                int bx = nameButtons[i].getX();
                int by = nameButtons[i].getY();
                graphics.fill(bx, by, bx + 2, by + nameButtons[i].getHeight(), COL_SELECTED);
            }

            if (buttonOver(nameButtons[i], mouseX, mouseY) && !skill.description().isEmpty()) {
                hoveredPreview = skill.description();
            }
        }

        renderListScrollbar(graphics);
        renderPreviewTooltip(graphics, mouseX, mouseY, hoveredPreview);
        renderPopup(graphics);

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
     * <p>{@code Button.isMouseOver} devolve {@code true} para um botao
     * invisivel se as coordenadas baterem, o que faria o tooltip aparecer
     * sobre uma linha que nao existe.
     */
    private boolean buttonOver(Button button, int mouseX, int mouseY) {
        return button.visible && button.active && button.isMouseOver(mouseX, mouseY);
    }

    /** Largura da coluna de nome, reconstruida a partir do botao da linha 0. */
    private int nameWidth() {
        return nameButtons[0] == null ? 60 : nameButtons[0].getWidth();
    }

    /** Quantas linhas de texto cabem na faixa do popup. */
    private int popupVisibleLines() {
        return Math.max(1, (POPUP_H - 10) / POPUP_LINE_H);
    }

    /**
     * Linhas do texto do popup, ou {@code null} se nao houver popup aberto.
     *
     * <p>Calculado sob demanda e nao guardado em campo: {@code init()} roda
     * antes de a ficha chegar, entao guardar as linhas em init() as congelaria
     * com a lista ainda vazia.
     */
    private List<FormattedCharSequence> popupLines() {
        if (selectedSkill == null) {
            return null;
        }
        SheetData.Skill skill = findSkill(selectedSkill);
        if (skill == null || skill.description().isEmpty()) {
            return null;
        }
        return this.font.split(Component.literal(skill.description()), Math.max(40, popupW - 10));
    }

    /**
     * Tooltip de hover: mostra so um <b>trecho</b> da descricao, para dar ideia
     * sem cobrir a tela inteira. O texto completo fica no popup.
     */
    private void renderPreviewTooltip(GuiGraphics graphics, int mouseX, int mouseY, String description) {
        if (description == null || description.isEmpty()) {
            return;
        }
        String preview = description.length() > HOVER_PREVIEW_CHARS
                ? description.substring(0, HOVER_PREVIEW_CHARS) + "..."
                : description;
        drawTooltipBox(graphics, mouseX, mouseY,
                this.font.split(Component.literal(preview), 180), 180);
    }

    /**
     * Popup de descricao completa, logo abaixo da lista.
     *
     * <p>Se a descricao for maior que a faixa, a ultima linha mostra quantas
     * faltam, e a roda do mouse rola o texto.
     */
    private void renderPopup(GuiGraphics graphics) {
        List<FormattedCharSequence> lines = popupLines();
        if (lines == null) {
            // Sem selecao: a faixa fica vazia, sem moldura, para o popup aparecer
            // e sumir sem "pulos" de layout.
            draggingScrollbar = false;
            return;
        }

        int boxH = Math.min(POPUP_H, lines.size() * POPUP_LINE_H + 10);
        graphics.fill(popupX, popupY, popupX + popupW, popupY + boxH, COL_TOOLTIP_BG);
        graphics.fill(popupX, popupY, popupX + popupW, popupY + 1, COL_TOOLTIP_EDGE);
        graphics.fill(popupX, popupY + boxH - 1, popupX + popupW, popupY + boxH, COL_TOOLTIP_EDGE);
        graphics.fill(popupX, popupY, popupX + 1, popupY + boxH, COL_TOOLTIP_EDGE);
        graphics.fill(popupX + popupW - 1, popupY, popupX + popupW, popupY + boxH, COL_TOOLTIP_EDGE);

        int visible = popupVisibleLines();
        int maxScroll = Math.max(0, lines.size() - visible);

        // Geometria da barra: trilho na direita, dentro da moldura. Calculada
        // aqui porque e o render que conhece boxH e maxScroll; o clique e o
        // arrasto apenas leem estes retangulos.
        barTrackX = popupX + popupW - BAR_W - BAR_PAD;
        barTrackY = popupY + BAR_PAD;
        barTrackH = Math.max(1, boxH - 2 * BAR_PAD);
        boolean overflowa = maxScroll > 0;
        if (overflowa) {
            // Trilho escuro + polegar claro, para a barra ficar visivel de
            // primeira (feedback do usuario: nao deu para ver que existia).
            graphics.fill(barTrackX, barTrackY, barTrackX + BAR_W, barTrackY + barTrackH,
                    COL_SCROLL_TRACK);
            // Proporcional: quanto maior o texto, menor o polegar. Minimo de
            // 8px para continuar clicavel.
            barThumbH = Math.max(8, barTrackH * visible / Math.max(1, lines.size()));
            if (barThumbH > barTrackH) {
                barThumbH = barTrackH;
            }
            int desloca = barTrackH - barThumbH;
            barThumbY = barTrackY + (maxScroll == 0 ? 0 : desloca * popupScroll / maxScroll);
            graphics.fill(barTrackX, barThumbY, barTrackX + BAR_W, barThumbY + barThumbH,
                    COL_SCROLL_THUMB);
        } else {
            barThumbH = 0;
            barThumbY = 0;
        }

        for (int i = 0; i < visible && popupScroll + i < lines.size(); i++) {
            graphics.drawString(this.font, lines.get(popupScroll + i),
                    popupX + 5, popupY + 5 + i * POPUP_LINE_H, COL_BOX_TEXT, false);
        }
        if (maxScroll > 0) {
            int restantes = lines.size() - popupScroll - visible;
            String more = restantes + " linhas abaixo (arraste a barra)";
            graphics.drawString(this.font, more,
                    popupX + popupW - this.font.width(more) - BAR_W - 2 * BAR_PAD - 2,
                    popupY + boxH - 11, COL_MUTED, false);
        }
    }

    /** Maior rolagem possivel do popup. */
    private int popupMaxScroll() {
        List<FormattedCharSequence> lines = popupLines();
        if (lines == null) {
            return 0;
        }
        return Math.max(0, lines.size() - popupVisibleLines());
    }

    /** Converte a posicao do mouse na rolagem do popup (clique e arrasto). */
    private void setPopupScrollFromMouse(double mouseY) {
        int maxScroll = popupMaxScroll();
        if (maxScroll <= 0 || barTrackH <= 0) {
            return;
        }
        int desloca = barTrackH - barThumbH;
        if (desloca <= 0) {
            popupScroll = 0;
            return;
        }
        int relativo = (int) (mouseY - barTrackY - barThumbH / 2);
        popupScroll = Math.max(0, Math.min(maxScroll, (int) Math.round(relativo * (double) maxScroll / desloca)));
    }

    /** O mouse esta sobre o trilho da barra? */
    private boolean onScrollbar(double mouseX, double mouseY) {
        return popupMaxScroll() > 0
                && mouseX >= barTrackX - 2 && mouseX <= barTrackX + BAR_W + 2
                && mouseY >= barTrackY && mouseY <= barTrackY + barTrackH;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // Botao esquerdo so: o direito esta reservado ao X de remover skill.
        if (event.button() == 0) {
            // A barra da lista e testada ANTES da do popup porque as duas
            // faixas podem se cruzar na tela: a do popup fica logo abaixo da
            // lista, entao sem esta ordem um clique perto da juncao seria
            // capturado pela barra errada.
            if (onListScrollbar(event.x(), event.y())) {
                draggingListBar = true;
                setSkillScrollFromMouse(event.y());
                return true;
            }
            if (onScrollbar(event.x(), event.y())) {
                draggingScrollbar = true;
                setPopupScrollFromMouse(event.y());
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (event.button() == 0) {
            if (draggingListBar) {
                setSkillScrollFromMouse(event.y());
                return true;
            }
            if (draggingScrollbar) {
                setPopupScrollFromMouse(event.y());
                return true;
            }
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == 0) {
            if (draggingListBar) {
                draggingListBar = false;
                return true;
            }
            if (draggingScrollbar) {
                draggingScrollbar = false;
                return true;
            }
        }
        return super.mouseReleased(event);
    }

    /**
     * Painel de tooltip generico, ao lado do cursor.
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
    private void drawTooltipBox(GuiGraphics graphics, int mouseX, int mouseY,
                                List<FormattedCharSequence> lines, int maxW) {
        if (lines.isEmpty()) {
            return;
        }
        final int pad = 4;
        final int lineH = 10;
        int textW = 0;
        for (FormattedCharSequence line : lines) {
            textW = Math.max(textW, (int) this.font.width(line));
        }
        int boxW = Math.min(maxW, textW) + pad * 2;
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
