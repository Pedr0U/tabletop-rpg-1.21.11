package com.pedro.tabletoprpg.client;

import com.pedro.tabletoprpg.RpgNetworking;
import com.pedro.tabletoprpg.SheetModel;
import com.pedro.tabletoprpg.SheetModelHolder;
import com.pedro.tabletoprpg.ThreatSheet;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ficha de ameaca do Mestre (02/10/2026): duas abas, "Ficha" e "Habilidades".
 *
 * <p><b>Por que duas abas e nao uma tela comprida:</b> a aba 1 tem identidade, vida,
 * descricao, atributos, pericias e caracteristicas; a aba 2 tem passivas e ativas.
 * Numa tela so, uma delas empurraria o rodape (com o Salvar) para fora da janela em
 * tela baixa. A barra de abas e o mesmo padrao da ficha do jogador.
 *
 * <p><b>Por que os valores vivem em {@link #values} e nao nos widgets:</b> trocar de
 * aba recria TODOS os widgets (e o {@code Screen} nao repositiona os filhos, ele
 * chama {@code init} de novo). Se o texto estivesse na caixa, o que o Mestre tivesse
 * digitado sumiria ao trocar de aba. O mesmo vale para as listas, que sao recriadas a
 * cada rolagem.
 *
 * <p><b>Por que o Save nao valida quase nada:</b> o construtor compacto de
 * {@link ThreatSheet} ja corta texto longo e fora de faixa nos dois lados, e o
 * servidor alinha a ficha no modelo antes de gravar. Validar de novo aqui so
 * criaria duas verdades para a mesma regra.
 */
public class ThreatSheetScreen extends Screen {

    /** Um rotulo a desenhar no proximo render, em coordenadas absolutas. */
    private record Label(String text, int x, int y, int color) {
    }

    /** A geometria da barra de HP, montada no layout e desenhada no render. */
    private record Bar(int x, int y, int w, int h) {
    }

    /** Os 6 passos do HP, iguais aos do jogador. */
    private static final int[] RESOURCE_STEPS = {-10, -5, -1, 1, 5, 10};

    /**
     * Uma lista com rolagem dentro da tela.
     *
     * <p><b>Por que cada lista tem o seu offset e nao um so:</b> sao cinco listas
     * independentes (atributos, pericias, caracteristicas, passivas e ativas) e a roda
     * tem que rolar a que esta sob o mouse -- e o mesmo caminho das duas colunas da
     * ficha do jogador.
     */
    private static final class ListRegion {
        private final int kind;
        private final List<AbstractWidget> widgets = new ArrayList<>();
        private int x;
        private int y;
        private int w;
        private int h;
        private int rowH;
        private int total;
        private int visible = 1;
        private int offset;

        ListRegion(int kind) {
            this.kind = kind;
        }

        void set(int x, int y, int w, int h, int rowH, int total) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = Math.max(rowH, h);
            this.rowH = rowH;
            this.total = total;
            this.visible = Math.max(1, this.h / rowH);
        }

        int maxOffset() {
            return Math.max(0, total - visible);
        }

        boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        }

        int rowY(int index) {
            return y + (index - offset) * rowH;
        }

        int lastIndex() {
            return Math.min(total, offset + visible);
        }

        int thumbHeight() {
            return total <= visible ? h : Math.max(12, h * visible / total);
        }

        int thumbTop() {
            return y + (h - thumbHeight()) * offset / Math.max(1, maxOffset());
        }

        int offsetFromMouse(double mouseY) {
            if (h <= 0 || maxOffset() <= 0) {
                return 0;
            }
            double ratio = (mouseY - y) / h;
            return Math.max(0, Math.min(maxOffset(), (int) Math.round(ratio * maxOffset())));
        }

        boolean overBar(double mouseX, double mouseY, int barX) {
            return maxOffset() > 0 && mouseX >= barX && mouseX < barX + 3
                    && mouseY >= thumbTop() && mouseY < thumbTop() + thumbHeight();
        }
    }

    private static final int KIND_ATTR = 0;
    private static final int KIND_PERICIA = 1;
    private static final int KIND_FEATURE = 2;
    private static final int KIND_PASSIVE = 3;
    private static final int KIND_ACTION = 4;

    private static final int COL_SCREEN_BG = 0x99000000;
    private static final int COL_PANEL_BG = 0xFF202020;
    private static final int COL_BORDER = 0xFF6B4A2A;
    private static final int COL_LIST_BG = 0xFF161616;
    private static final int COL_TITLE = 0xFFE0C080;
    private static final int COL_TEXT = CharacterSheetScreen.COL_LABEL;
    private static final int COL_SECTION = CharacterSheetScreen.COL_SECTION;
    private static final int COL_MUTED = CharacterSheetScreen.COL_MUTED;
    private static final int COL_ERROR = CharacterSheetScreen.COL_DOWNED;
    private static final int COL_OK = CharacterSheetScreen.COL_EDITABLE;
    private static final int COL_WARN = 0xFFFFA14D;
    private static final int COL_HP = CharacterSheetScreen.COL_HP;
    private static final int COL_HP_BG = CharacterSheetScreen.COL_HP_BG;
    private static final int COL_BAR_EDGE = CharacterSheetScreen.COL_BAR_EDGE;

    private static final int PAD = 6;
    private static final int TITLE_H = 12;
    private static final int TAB_H = 18;
    private static final int LABEL_H = 9;
    private static final int SECTION_H = 10;
    private static final int FIELD_H = 18;
    private static final int GAP = 5;
    private static final int COL_GAP = 8;
    private static final int FOOTER_H = 20;
    private static final int STATUS_H = 10;
    private static final int ROW_H = 18;
    private static final int PER_ROW_H = 11;
    // A caixa do botao e a mesma em todas as listas (`FIELD_H`); a altura da linha
    // e a da caixa mais a folga entre linhas. Antes a linha de caracteristica usava
    // `FEATURE_ROW_H - 2`, que deixava a caixa menor que a das habilidades.
    private static final int FEATURE_ROW_H = FIELD_H + 4;
    /** Nome (18) + 3 linhas de descricao. */
    private static final int PASSIVE_ROW_H = 50;
    /** Nome (18) + linha do bonus (10) + 3 linhas de descricao. */
    private static final int ACTION_ROW_H = 60;
    private static final int DESC_LINE_H = 10;
    private static final int DESC_LINES = 3;
    private static final int DESC_MIN_H = 32;
    /** Altura da caixa "Exibir nome em cima da ameaça?". */
    private static final int CHECKBOX_H = 20;
    /**
     * Soma dos blocos da coluna esquerda que nunca encolhem: nome/ND, nome de
     * exibicao + caixa, tipo/tamanho/deslocamento e o bloco de HP de 3 linhas.
     *
     * <p><b>Por que e uma constante e nao a posicao do ultimo bloco:</b> a altura
     * da coluna precisa ser conhecida ANTES de qualquer widget existir, porque o
     * offset de rolagem ja entra na posicao do primeiro. So a soma permite decidir
     * a rolagem antes de montar o primeiro campo.
     */
    private static final int COL_FIXED_H = (LABEL_H + FIELD_H + GAP)
            + (LABEL_H + FIELD_H + CHECKBOX_H + GAP)
            + (LABEL_H + FIELD_H + GAP)
            + (SECTION_H + 2 * (FIELD_H + GAP));
    /** Rotulo da descricao + folga antes do bloco de atributos. */
    private static final int DESC_HEAD_H = LABEL_H + GAP;
    /** Rotulo "Atributos"; a lista comeca logo abaixo dele. */
    private static final int ATTR_HEAD_H = SECTION_H;
    private static final int STEP_GAP = 4;
    private static final int ATTR_VALUE_W = 48;
    private static final int BAR_W = 4;
    private static final int SMALL_BTN_H = 16;
    private static final int MAX_PANEL_W = 600;
    // Sem teto de altura: o painel ocupa a tela inteira, como a ficha do jogador,
    // que tambem deriva de `topY..bottomY`. Com teto, numa tela logica de 270 px
    // (1080p com GUI scale automatico) o painel ficava com 258 px e os atributos
    // nao cabiam.

    private final Screen parentScreen;
    private final ThreatSheet sheet;

    /**
     * O nome com que a ficha foi aberta, ou {@code ""} quando ela esta sendo criada.
     *
     * <p><b>Por que deixa de ser {@code final}:</b> o servidor responde a um save
     * nomeando a ficha que ele gravou, e e esse nome que o proximo save tem que
     * carregar. Com o campo congelado, o segundo Salvar de uma ficha nova voltava com
     * "Já existe uma ficha com esse nome", e o de uma ficha renomeada voltava com
     * "não existe mais" -- justamente as duas respostas que so aparecem quando o
     * servidor procura a ficha pelo nome antigo.
     */
    private String originalName;

    /** O que o Mestre digitou, por campo; sobrevive a recriacao dos widgets. */
    private final Map<String, String> values = new LinkedHashMap<>();
    /** As caixas de uma linha, por campo. */
    private final Map<String, EditBox> boxes = new LinkedHashMap<>();
    private final List<String> features = new ArrayList<>();
    private final List<ThreatSheet.Ability> passives = new ArrayList<>();
    private final List<ThreatSheet.Action> actions = new ArrayList<>();
    private final List<ThreatSheet.PericiaValue> pericias = new ArrayList<>();
    private final List<Label> labels = new ArrayList<>();

    /** Os widgets da coluna esquerda, para poder remove-los na rolagem. */
    private final List<AbstractWidget> colWidgets = new ArrayList<>();
    private final ListRegion attrRegion = new ListRegion(KIND_ATTR);
    private final ListRegion periciaRegion = new ListRegion(KIND_PERICIA);
    private final ListRegion featureRegion = new ListRegion(KIND_FEATURE);
    private final ListRegion passiveRegion = new ListRegion(KIND_PASSIVE);
    private final ListRegion actionRegion = new ListRegion(KIND_ACTION);

    private EditBox displayNameBox;
    private MultiLineEditBox descriptionBox;
    private Bar hpBar;
    private ListRegion dragging;

    private int hp;
    private boolean showDisplayName;
    private int activeTab;
    /** Quantos px da coluna esquerda estao rolados para cima. */
    private int colOffset;

    /**
     * Uma lista cujas entradas tem Del em dois cliques.
     *
     * <p><b>Por que um indice e nao um rotulo:</b> o indice pendente aponta para a
     * posicao na lista. Se o rotulo "Del?" fosse a Rememberanca, adicionar ou remover
     * uma entrada transformaria o "Del?" em confirmacao de outra habilidade -- que e
     * exatamente o dano que o Mestre nao aceita. O indice e limpo quando a lista muda
     * de outra forma, entao a confirmacao morre junto com a linha.
     */
    private final class PendingDelete {
        private int index = -1;

        boolean isPending(int candidate) {
            return index == candidate;
        }

        void arm(int candidate) {
            index = candidate;
        }

        void clear() {
            index = -1;
        }
    }

    private final PendingDelete pendingFeature = new PendingDelete();
    private final PendingDelete pendingPassive = new PendingDelete();
    private final PendingDelete pendingAction = new PendingDelete();
    /** Altura do conteudo inteiro da coluna esquerda (com o que encolhe). */
    private int colContentH;
    private boolean draggingCol;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int contentTop;
    private int contentBottom;
    private int leftX;
    private int rightX;
    private int colW;
    private int footerY;
    private int statusY;

    /** A ultima lista de fichas conhecida; serve so para saber quantas existem. */
    private List<ThreatSheet> knownSheets = List.of();
    /**
     * O nome <b>exatamente como foi enviado</b> no ultimo save.
     *
     * <p><b>Por que o enviado e nao o que esta na caixa agora:</b> o Mestre pode
     * digitar outro nome enquanto o pacote ainda esta na fila. Se o {@code originalName}
     * do proximo save saisse da caixa, esse save viraria criacao de ficha nova em vez
     * de edicao da que ele acabou de gravar.
     */
    private String pendingSaveName = "";
    private String status = "";
    private int statusColor = COL_MUTED;

    public ThreatSheetScreen(Screen parentScreen, ThreatSheet sheet, String originalName) {
        super(Component.literal(sheet == null ? "Ficha de Ameaça" : sheet.listLabel()));
        this.parentScreen = parentScreen;
        this.sheet = sheet;
        this.originalName = originalName == null ? "" : originalName;
        if (sheet != null) {
            values.put("name", sheet.identity().name());
            values.put("level", Integer.toString(sheet.identity().level()));
            values.put("displayName", sheet.identity().displayName());
            values.put("type", sheet.identity().type());
            values.put("size", sheet.identity().size());
            values.put("speed", sheet.identity().speed());
            values.put("hpMax", Integer.toString(sheet.vitals().hpMax()));
            values.put("ca", Integer.toString(sheet.vitals().ca()));
            values.put("description", sheet.description());
            this.hp = sheet.vitals().hp();
            this.showDisplayName = sheet.identity().showDisplayName();
            this.features.addAll(sheet.features());
            this.passives.addAll(sheet.passives());
            this.actions.addAll(sheet.actions());
            this.pericias.addAll(sheet.pericias());
            for (ThreatSheet.AttributeValue attribute : sheet.attributes()) {
                values.put(attrKey(attribute.id()), Integer.toString(attribute.value()));
            }
        }
    }

    private static String attrKey(String id) {
        return "attr:" + id;
    }

    private SheetModel model() {
        return SheetModelHolder.current();
    }

    private static int parseInt(String text, int fallback) {
        if (text == null) {
            return fallback;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            // "-", "+" e o que fica no meio da digitacao: o record da ficha corta
            // o valor, e nao vale travar o Mestre no meio do numero.
            return fallback;
        }
    }

    private int levelValue() {
        int level = parseInt(values.get("level"), 0);
        return Math.max(ThreatSheet.MIN_LEVEL, Math.min(ThreatSheet.MAX_LEVEL, level));
    }

    // ------------------------------------------------------------------
    // MONTAGEM
    // ------------------------------------------------------------------

    @Override
    protected void init() {
        super.init();
        // Antes de qualquer recriacao: o texto esta nas caixas que vao morrer.
        syncFromWidgets();
        boxes.clear();
        colWidgets.clear();
        labels.clear();
        hpBar = null;
        descriptionBox = null;

        layout();
        addTabs();

        if (activeTab == 0) {
            buildSheetTab();
        } else {
            buildAbilitiesTab();
        }
        rebuildFooter();
    }

    /** Calcula o painel, as colunas e as faixas de onde as listas vao comer espaco. */
    private void layout() {
        panelW = Math.max(200, Math.min(this.width - 16, MAX_PANEL_W));
        panelX = (this.width - panelW) / 2;
        int chrome = 2 * PAD + TITLE_H + GAP + TAB_H + GAP + FOOTER_H + STATUS_H + GAP;
        panelH = Math.max(120, this.height - 12);
        if (panelH < chrome + SECTION_H + ROW_H) {
            panelH = Math.min(this.height - 8, chrome + SECTION_H + ROW_H);
        }
        panelY = Math.max(PAD, (this.height - panelH) / 2);

        contentTop = panelY + PAD + TITLE_H + GAP + TAB_H + GAP;
        statusY = panelY + panelH - PAD - STATUS_H;
        footerY = statusY - GAP - FOOTER_H;
        contentBottom = footerY - GAP;

        // As duas colunas tem exatamente a mesma largura: e o que impede o
        // "Atributos" de invadir o CA (o defeito classico de dividir por resto).
        // A coluna esquerda tem barra de rolagem propria, e ela ficava em
        // `leftX - BAR_W`, DENTRO da margem do painel. Era isso que fazia a caixa da
        // esquerda parecer mais larga que a da direita. Agora a barra ocupa uma fatia
        // reservada entre as colunas e as duas caixas ficam com a mesma largura.
        colW = (panelW - 2 * PAD - COL_GAP - BAR_W - 2) / 2;
        leftX = panelX + PAD;
        rightX = leftX + colW + BAR_W + 2 + COL_GAP;
    }

    private void addTabs() {
        int btnW = Math.max(60, (panelW - 2 * PAD - GAP) / 2);
        int y = panelY + PAD + TITLE_H + GAP;
        addRenderableWidget(Button.builder(Component.literal("Principal - PG 1"), b -> setTab(0))
                .bounds(panelX + PAD, y, btnW, TAB_H).build());
        addRenderableWidget(Button.builder(Component.literal("Habilidades - PG 2"), b -> setTab(1))
                .bounds(panelX + panelW - PAD - btnW, y, btnW, TAB_H).build());
    }

    private void setTab(int tab) {
        if (activeTab == tab) {
            return;
        }
        activeTab = tab;
        // `rebuildWidgets` limpa os filhos e chama `init` de novo: e o caminho
        // que o projeto ja usa para trocar de aba (StatusScreen).
        rebuildWidgets();
    }

    private void addTabsLabel(String text, int x, int y) {
        labels.add(new Label(text, x, y, COL_SECTION));
    }

    private EditBox textField(String key, String label, int x, int y, int w, int maxLength) {
        EditBox box = new EditBox(this.font, x, y, w, FIELD_H, Component.literal(label));
        box.setMaxLength(maxLength);
        box.setValue(values.getOrDefault(key, ""));
        addRenderableWidget(box);
        boxes.put(key, box);
        return box;
    }

    /**
     * Caixa numerica com sinal.
     *
     * @param digits quantos caracteres aceita, sinal fora
     */
    private EditBox numericField(String key, String label, int x, int y, int w, int digits) {
        EditBox box = textField(key, label, x, y, w, digits + 1);
        // `-?` para o sinal; o piso e o teto sao do record da ficha, que corta o
        // que passar (ver `ThreatSheet.Vitals` e `ThreatSheet.AttributeValue`).
        box.setFilter(s -> s.matches("-?\\d{0," + digits + "}"));
        return box;
    }

    // ------------------------------------------------------------------
    // ABA 0: FICHA
    // ------------------------------------------------------------------

    private void buildSheetTab() {
        buildSheetLeftColumn();
        buildSheetRightColumn();
    }

    /** A altura que a coluna esquerda tem de verdade: do topo do conteudo ao rodape. */
    private int colViewH() {
        return contentBottom - contentTop;
    }

    /** Quanto da coluna esquerda esta fora da janela agora; e o que o offset cobre. */
    private int colMaxOffset() {
        return Math.max(0, colContentH - colViewH());
    }

    /**
     * O bloco cabe inteiro na janela da coluna?
     *
     * <p><b>Por que "inteiro" e nao "parcialmente":</b> e o mesmo criterio das
     * listas de linhas, e ele evita o pior defeito possível -- um widget meia
     * altura desenhado por cima do botão Salvar. Blocos que não cabem não são
     * criados, e aparecem quando o Mestre rola.
     */
    private boolean inColumn(int y, int h) {
        return y >= contentTop && y + h <= contentBottom;
    }

    /** Registra no rastreio da coluna E na tela: quem cria, tem que remover. */
    private void addColWidget(AbstractWidget widget) {
        colWidgets.add(widget);
        addRenderableWidget(widget);
    }

    private void clearColumnWidgets() {
        for (AbstractWidget old : colWidgets) {
            removeWidget(old);
        }
        colWidgets.clear();
    }

    /**
     * Recria a coluna esquerda depois de mudar o offset.
     *
     * <p><b>Por que recriar em vez de mover:</b> e o mesmo caminho das listas -- os
     * widgets morrem e nascem de novo, e o texto digitado vive no {@link #values},
     * que nao depende de nenhum deles. Recriar tambem garante que um bloco que
     * saiu da janela NAO fique clicavel invisivel.
     */
    private void rebuildLeftColumn() {
        syncFromWidgets();
        clearColumnWidgets();
        // Widgets de outro offset: sem isto o render continuaria desenhando a
        // barra do HP e o texto da descricao na posicao antiga.
        hpBar = null;
        descriptionBox = null;
        // Os rotulos da coluna velha ficariam na lista para sempre: cada rolagem
        // acrescentaria um conjunto novo. Eles sao sempre os ULTIMOS a entrar (a
        // coluna esquerda e montada antes da direita e o `init` comeca a lista
        // limpa), entao descartar o que passou da marca e o mesmo que recomecar.
        int mark = labels.size();
        buildSheetLeftColumn();
        labels.subList(mark, labels.size()).clear();
    }

    /**
     * A coluna esquerda: identidade, vida, descricao e atributos.
     *
     * <p><b>Por que ela rola:</b> o que nao encolhe mede {@link #COL_FIXED_H} e so
     * a descricao e a lista de atributos cedem. Em 240 logicos a janela tem 136 px
     * para 246 px de conteudo; sem rolagem o comec da coluna subiria por cima do
     * titulo e o fim desceria por cima do botao Salvar -- e os atributos nasciam
     * fora da tela.
     *
     * <p><b>Por que a janela vem de {@code contentBottom}:</b> quem limita a coluna
     * e o rodape, e ele e fixo. Uma altura solta no lugar disso e o que fazia a
     * coluna nascer fora da tela justamente nas resolucoes baixas.
     */
    private void buildSheetLeftColumn() {
        // 2) o que sobra: a descricao primeiro (o piso e DESC_MIN_H), e o resto vai
        // para a lista de atributos, com piso de uma linha visivel.
        int leftover = colViewH() - COL_FIXED_H - DESC_HEAD_H - ATTR_HEAD_H;
// A descricao cede espaco para TRES linhas de atributo, nunca menos: e o bloco
        // que o Mestre precisa para digitar. A caixa da descricao guarda 256
        // caracteres, entao 66 px ja e folgado.
        int descH = Math.max(DESC_MIN_H, Math.min(66, leftover - 3 * ROW_H));
        int attrH = Math.max(ROW_H, leftover - descH);
        colContentH = COL_FIXED_H + DESC_HEAD_H + descH + ATTR_HEAD_H + attrH;
        colOffset = Math.max(0, Math.min(colMaxOffset(), colOffset));

        int y = contentTop - colOffset;

        // 1) nome da ameaca e ND, na mesma linha: sao a identidade, e o ND e
        // estreito o bastante para nao roubar largura do nome.
        if (inColumn(y, LABEL_H + FIELD_H)) {
            int ndW = 44;
            labels.add(new Label("ND", leftX, y, COL_TEXT));
            addColWidget(numericField("level", "ND", leftX, y + LABEL_H, ndW,
                    ThreatSheet.LEVEL_CHARS));
            int nameX = leftX + ndW + GAP;
            labels.add(new Label("Nome da Ameaça", nameX, y, COL_TEXT));
            addColWidget(textField("name", "Nome da Ameaça", nameX, y + LABEL_H,
                    colW - ndW - GAP, ThreatSheet.MAX_NAME));
        }
        y += LABEL_H + FIELD_H + GAP;

        // 2) nome de exibicao: so e editavel com a caixa marcada. O par e do
        // pedido -- o nome em cima da ameaca nao faz sentido sem o texto.
        if (inColumn(y, LABEL_H + FIELD_H + CHECKBOX_H + 6)) {
            labels.add(new Label("Nome de Exibição", leftX, y, COL_TEXT));
            displayNameBox = textField("displayName", "Nome de Exibição", leftX, y + LABEL_H,
                    colW, ThreatSheet.MAX_DISPLAY_NAME);
            colWidgets.add(displayNameBox);
            Checkbox checkbox = Checkbox.builder(
                            Component.literal("Exibir nome em cima da ameaça?"), this.font)
                    // 6 px de folga abaixo do campo: antes a caixa nascia colada na borda de baixo do
            // "Nome de Exibicao" (relato do Mestre em 02/10/2026).
                    .pos(leftX + 2, y + LABEL_H + FIELD_H + 6)
                    .maxWidth(colW - 4)
                    .selected(showDisplayName)
                    .onValueChange((cb, checked) -> {
                        showDisplayName = checked;
                        if (displayNameBox != null) {
                            displayNameBox.setEditable(checked);
                        }
                    })
                    .build();
            checkbox.setSize(colW - 4, CHECKBOX_H);
            addColWidget(checkbox);
            displayNameBox.setEditable(showDisplayName);
        }
        y += LABEL_H + FIELD_H + CHECKBOX_H + 6 + GAP;

        // 3) tipo, tamanho e deslocamento.
        if (inColumn(y, LABEL_H + FIELD_H)) {
            int thirdW = Math.max(40, (colW - 2 * GAP) / 3);
            int sizeX = leftX + thirdW + GAP;
            int speedX = sizeX + thirdW + GAP;
            labels.add(new Label("Tipo", leftX, y, COL_TEXT));
            labels.add(new Label("Tamanho", sizeX, y, COL_TEXT));
            labels.add(new Label("Desl.", speedX, y, COL_TEXT));
            addColWidget(textField("type", "Tipo", leftX, y + LABEL_H, thirdW,
                    ThreatSheet.MAX_TYPE));
            addColWidget(textField("size", "Tamanho", sizeX, y + LABEL_H, thirdW,
                    ThreatSheet.MAX_SIZE));
            addColWidget(textField("speed", "Deslocamento", speedX, y + LABEL_H, thirdW,
                    ThreatSheet.MAX_SPEED));
        }
        y += LABEL_H + FIELD_H + GAP;

        // 4) HP em tres linhas, igual ao jogador: titulo, teto + barra, passos.
        if (inColumn(y, SECTION_H + 2 * (FIELD_H + GAP))) {
            addTabsLabel("HP", leftX, y);
            int hpY = y + SECTION_H;
            int maxLabelW = this.font.width("Max") + 4;
            int maxBoxW = Math.max(40, Math.min(56, colW / 5));
            labels.add(new Label("Max", leftX, hpY + 5, COL_MUTED));
            addColWidget(numericField("hpMax", "Max", leftX + maxLabelW, hpY, maxBoxW,
                    digitsOf(ThreatSheet.MAX_RESOURCE)));
            int barX = leftX + maxLabelW + maxBoxW + GAP;
            hpBar = new Bar(barX, hpY, Math.max(24, colW - maxLabelW - maxBoxW - GAP),
                    FIELD_H - 2);

            int stepY = hpY + FIELD_H + GAP;
            int steps = RESOURCE_STEPS.length;
            int stepW = Math.max(1, (colW - (steps - 1) * STEP_GAP) / steps);
            int lastStepW = colW - (steps - 1) * (stepW + STEP_GAP);
            int stepX = leftX;
            for (int i = 0; i < steps; i++) {
                int delta = RESOURCE_STEPS[i];
                addColWidget(Button.builder(
                                Component.literal(delta > 0 ? "+" + delta : Integer.toString(delta)),
                                b -> stepHp(delta))
                        .bounds(stepX, stepY, i == steps - 1 ? lastStepW : stepW, FIELD_H)
                        .build());
                stepX += stepW + STEP_GAP;
            }
        }
        y += SECTION_H + 2 * (FIELD_H + GAP);

        // 5) descricao: caixa grande, e o que sobra de altura e o que ela toma.
        if (inColumn(y, DESC_HEAD_H + descH)) {
            labels.add(new Label("Descrição", leftX, y, COL_TEXT));
            MultiLineEditBox box = MultiLineEditBox.builder()
                    .setX(leftX)
                    .setY(y + LABEL_H)
                    .setTextColor(CharacterSheetScreen.COL_BOX_TEXT)
                    .setTextShadow(false)
                    .setCursorColor(CharacterSheetScreen.COL_BOX_TEXT)
                    .setShowBackground(true)
                    .setShowDecorations(true)
                    .build(this.font, colW, descH, Component.literal("Descrição"));
            box.setCharacterLimit(ThreatSheet.MAX_DESCRIPTION);
            box.setValue(values.getOrDefault("description", ""));
            addColWidget(box);
            descriptionBox = box;
        }
        y += DESC_HEAD_H + descH;

        // 6) atributos, um por linha do modelo e na ordem dele. A lista recebe a
        // altura que sobrou e rola por dentro; fora da janela ela nem existe, para
        // nao deixar caixa invisivel e clicavel.
        // A guarda e "cabe pelo menos uma linha visivel", e nao "cabe a lista inteira".
        // Antes era a segunda, e ai os rotulos apareciam sem as caixas: eles sao
        // desenhados no `render` sempre que a linha existe, enquanto as caixas so
        // nascem quando o bloco entra na coluna (relato do Mestre em 02/10/2026).
        int attrY = y + ATTR_HEAD_H;
        int attrRoom = contentBottom - attrY;
        int attrVisible = attrRoom <= 0 ? 0 : Math.min(attrH, attrRoom);
        attrRegion.set(leftX, attrY, colW, attrVisible, ROW_H, model().attributes().size());
        if (attrVisible > 0) {
            addTabsLabel("Atributos", leftX, y);
            rebuildRegion(attrRegion);
        } else {
            clearRegionWidgets(attrRegion);
        }
    }

    private void buildSheetRightColumn() {
        int y = contentTop;

        labels.add(new Label("CA", rightX, y, COL_TEXT));
        numericField("ca", "CA", rightX, y + LABEL_H, 56,
                digitsOf(ThreatSheet.MAX_RESOURCE));
        y += LABEL_H + FIELD_H + GAP;

        // Pericias: somente leitura aqui. A edicao mora na tela propria porque
        // sao varias linhas, e esta coluna ja tem CA e caracteristicas.
        addTabsLabel("Perícias", rightX, y);
        addRenderableWidget(Button.builder(Component.literal("Editar"), b -> editPericias())
                .bounds(rightX + colW - 56, y - 3, 56, SMALL_BTN_H).build());
        // Titulo e botao dividem a faixa do cabecalho e a lista comeca DEPOIS dela, com
        // folga: o botao tem 16 px de altura em uma faixa de 10 px, entao antes ele
        // invadia a borda de cima da caixa.
        y += SECTION_H + SMALL_BTN_H + 4;
        int periciaHeadH = 16; // linha dos cabecalhos "Perícia" e "Bônus", com folga

        // O espaco das duas listas de baixo se divide: metade para as pericias,
        // metade para as caracteristicas, sempre deixando uma linha para cada.
        int featureHead = SECTION_H + SMALL_BTN_H + 4 + FEATURE_ROW_H;
        int room = Math.max(2 * ROW_H + featureHead, contentBottom - y - periciaHeadH);
        int periciaH = Math.max(2 * PER_ROW_H + periciaHeadH,
                (room - featureHead) / 2 + periciaHeadH);
        // Margem interna de proposito: a lista de pericias encostava no teto do painel
        // e o texto ficava colado no limite, sem ar. O recuo e de `PAD` dos quatro
        // lados, e o fundo da lista recua junto, entao o espaco fica visivel.
        periciaRegion.set(rightX + PAD, y + periciaHeadH, colW - 2 * PAD,
                periciaH - 2 * PAD - periciaHeadH, PER_ROW_H, pericias.size());
        rebuildRegion(periciaRegion);
        y = periciaRegion.y + periciaRegion.h + GAP;

        addTabsLabel("Características", rightX, y);
        addRenderableWidget(Button.builder(Component.literal("+ Característica"), b -> addFeature())
                .bounds(rightX + colW - 110, y - 3, 110, SMALL_BTN_H).build());
        y += SECTION_H + SMALL_BTN_H + 4;
        featureRegion.set(rightX, y, colW, Math.max(FEATURE_ROW_H, contentBottom - y),
                FEATURE_ROW_H, features.size());
        rebuildRegion(featureRegion);
    }

    /** Quantos caracteres de digito o teto permite, ja com o sinal fora da conta. */
    private static int digitsOf(int max) {
        return Integer.toString(Math.abs(max)).length();
    }

    /** Um passo do HP, sem acelerar ao segurar (mesma regra do jogador). */
    private void stepHp(int delta) {
        hp = Math.max(ThreatSheet.MIN_RESOURCE,
                Math.min(ThreatSheet.MAX_RESOURCE, hp + delta));
    }

    // ------------------------------------------------------------------
    // ABA 1: HABILIDADES
    // ------------------------------------------------------------------

    private void buildAbilitiesTab() {
        int y = contentTop;
        addTabsLabel("Habilidades Passivas", leftX, y);
        addRenderableWidget(Button.builder(Component.literal("+ Habilidade"), b -> addPassive())
                .bounds(leftX + colW - 100, y - 3, 100, SMALL_BTN_H).build());
        y += SECTION_H + SMALL_BTN_H + 4;
        passiveRegion.set(leftX, y, colW, Math.max(PASSIVE_ROW_H, contentBottom - y),
                PASSIVE_ROW_H, passives.size());
        rebuildRegion(passiveRegion);

        int y2 = contentTop;
        // Mesmo desenho da coluna de passivas: titulo e "+ Habilidade" na MESMA linha e
        // a lista comeca na linha seguinte. Eles tinham ficado em linhas separadas
        // quando o painel era estreito; com o painel de 600 px os dois cabem lado a
        // lado sem se tocar.
        addTabsLabel("Ataques e Habilidades Ativas", rightX, y2);
        addRenderableWidget(Button.builder(Component.literal("+ Habilidade"), b -> addAction())
                .bounds(rightX + colW - 100, y2 - 3, 100, SMALL_BTN_H).build());
        y2 += SECTION_H + SMALL_BTN_H + 4;
        actionRegion.set(rightX, y2, colW, Math.max(ACTION_ROW_H, contentBottom - y2),
                ACTION_ROW_H, actions.size());
        rebuildRegion(actionRegion);
    }

    // ------------------------------------------------------------------
    // LISTAS
    // ------------------------------------------------------------------

    /**
     * Recria os widgets de uma lista.
     *
     * <p><b>Por que sincroniza antes:</b> as listas de atributos e de caracteristicas
     * tem texto dentro de widgets, e o scroll destroi esses widgets. Sem a copia
     * para {@link #values}, o que o Mestre digitou sumiria na primeira rolagem.
     */
    private void rebuildRegion(ListRegion region) {
        syncFromWidgets();
        clearRegionWidgets(region);
        region.offset = Math.min(region.offset, region.maxOffset());

        for (int i = region.offset; i < region.lastIndex(); i++) {
            int rowY = region.rowY(i);
            switch (region.kind) {
                case KIND_ATTR -> addAttributeRow(region, i, rowY);
                case KIND_PERICIA -> {
                    // Somente leitura: o texto e desenhado no render, sem widget.
                }
                case KIND_FEATURE -> addFeatureRow(region, i, rowY);
                case KIND_PASSIVE -> addPassiveRow(region, i, rowY);
                case KIND_ACTION -> addActionRow(region, i, rowY);
                default -> {
                }
            }
        }
    }

    /** Tira da tela os widgets de uma regiao; o texto delas ja esta no {@link #values}. */
    private void clearRegionWidgets(ListRegion region) {
        for (AbstractWidget old : region.widgets) {
            removeWidget(old);
        }
        region.widgets.clear();
    }

    private void addAttributeRow(ListRegion region, int index, int y) {
        if (index >= model().attributes().size()) {
            return;
        }
        SheetModel.AttributeDef def = model().attributes().get(index);
        int boxW = Math.min(ATTR_VALUE_W, region.w / 3);
        // 8 px de folga da direita: a caixa nascia em `region.x + region.w`, ou seja
        // por cima da barra de rolagem da coluna (relato do Mestre em 02/10/2026).
        EditBox box = numericField(attrKey(def.id()), def.name(),
                region.x + region.w - boxW - 8, y, boxW, ThreatSheet.VALUE_CHARS);
        region.widgets.add(box);
    }

    /**
     * Uma linha de caracteristica: o proprio texto e o botao Editar, e o Del ao
     * lado. E o mesmo par das habilidades, porque e a mesma operacao de "abrir o
     * formulario preenchido" -- so muda o formulario.
     */
    private void addFeatureRow(ListRegion region, int index, int y) {
        int delW = 26;
        int editW = region.w - delW - GAP - 16;
        // `Button` desenha o rotulo com `drawCenteredString`, sem cortar: um texto de
        // 96 caracteres atravessaria a coluna vizinha, a borda e a barra de rolagem.
        String text = features.get(index).isEmpty() ? "(vazio)" : features.get(index);
        Button edit = Button.builder(
                        Component.literal(this.font.plainSubstrByWidth(text, editW - 2)),
                        b -> editFeature(index))
                .bounds(region.x + 4, y, editW, FIELD_H)
                .build();
        region.widgets.add(edit);
        addRenderableWidget(edit);

        Button del = Button.builder(
                        Component.literal(pendingFeature.isPending(index) ? "Del?" : "Del"),
                        b -> {
                            if (pendingFeature.isPending(index)) {
                                pendingFeature.clear();
                                if (index < features.size()) {
                                    features.remove(index);
                                }
                            } else {
                                pendingFeature.arm(index);
                            }
                            rebuildRegion(region);
                        })
                .bounds(edit.getRight() + GAP, y, delW, FIELD_H).build();
        region.widgets.add(del);
        addRenderableWidget(del);
    }

    private void addPassiveRow(ListRegion region, int index, int y) {
        int delW = 26;
        int editW = region.w - delW - GAP - 16;
        // Mesmo corte do botao de caracteristica: o `Button` nao recorta o rotulo.
        String name = passives.get(index).name();
        Button edit = Button.builder(
                        Component.literal(this.font.plainSubstrByWidth(
                                name.isEmpty() ? "(sem nome)" : name, editW - 2)),
                        b -> editPassive(index))
                .bounds(region.x + 4, y, editW, FIELD_H)
                .build();
        region.widgets.add(edit);
        addRenderableWidget(edit);

        Button del = Button.builder(
                        Component.literal(pendingPassive.isPending(index) ? "Del?" : "Del"),
                        b -> {
                            if (pendingPassive.isPending(index)) {
                                pendingPassive.clear();
                                if (index < passives.size()) {
                                    passives.remove(index);
                                }
                            } else {
                                pendingPassive.arm(index);
                            }
                            rebuildRegion(region);
                        })
                .bounds(edit.getRight() + GAP, y, delW, FIELD_H).build();
        region.widgets.add(del);
        addRenderableWidget(del);
    }

    private void addActionRow(ListRegion region, int index, int y) {
        int delW = 26;
        int editW = region.w - delW - GAP - 16;
        // Mesmo corte do botao de passiva.
        String name = actions.get(index).name();
        Button edit = Button.builder(
                        Component.literal(this.font.plainSubstrByWidth(
                                name.isEmpty() ? "(sem nome)" : name, editW - 2)),
                        b -> editAction(index))
                .bounds(region.x + 4, y, editW, FIELD_H)
                .build();
        region.widgets.add(edit);
        addRenderableWidget(edit);

        Button del = Button.builder(
                        Component.literal(pendingAction.isPending(index) ? "Del?" : "Del"),
                        b -> {
                            if (pendingAction.isPending(index)) {
                                pendingAction.clear();
                                if (index < actions.size()) {
                                    actions.remove(index);
                                }
                            } else {
                                pendingAction.arm(index);
                            }
                            rebuildRegion(region);
                        })
                .bounds(edit.getRight() + GAP, y, delW, FIELD_H).build();
        region.widgets.add(del);
        addRenderableWidget(del);
    }

    /** Todas as listas visiveis da aba atual, na ordem em que o mouse as encontra. */
    private List<ListRegion> regions() {
        return activeTab == 0
                ? List.of(attrRegion, periciaRegion, featureRegion)
                : List.of(passiveRegion, actionRegion);
    }

    /**
     * A lista que o mouse esta sobre, so se ela ainda esta dentro da janela da tela.
     *
     * <p><b>Por que o filtro:</b> a coluna esquerda rola, entao uma regiao pode estar
     * posicionada acima do titulo. Sem este teste, a roda do mouse sobre a barra de
     * abas rolaria a lista de atributos escondida la atras.
     */
    private ListRegion regionAt(double mouseX, double mouseY) {
        for (ListRegion region : regions()) {
            if (region.y + region.h > contentTop && region.y < contentBottom
                    && region.contains(mouseX, mouseY)) {
                return region;
            }
        }
        return null;
    }

    private int barX(ListRegion region) {
        return region.x + region.w - BAR_W - 2;
    }

    // ------------------------------------------------------------------
    // ROLAGEM DA COLUNA ESQUERDA
    // ------------------------------------------------------------------

    /** A barra fica na esquerda da coluna, entre a borda do painel e o primeiro campo. */
    private int colBarX() {
        return leftX + colW + 1;
    }

    private int colThumbH() {
        if (colMaxOffset() <= 0) {
            return colViewH();
        }
        return Math.max(12, colViewH() * colViewH() / Math.max(1, colContentH));
    }

    private int colThumbY() {
        return contentTop + (colViewH() - colThumbH()) * colOffset / Math.max(1, colMaxOffset());
    }

    private int colOffsetFromMouse(double mouseY) {
        if (colViewH() <= 0 || colMaxOffset() <= 0) {
            return 0;
        }
        double ratio = (mouseY - contentTop) / (double) colViewH();
        return Math.max(0, Math.min(colMaxOffset(), (int) Math.round(ratio * colMaxOffset())));
    }

    private boolean colOverBar(double mouseX, double mouseY) {
        return activeTab == 0 && colMaxOffset() > 0
                && mouseX >= colBarX() && mouseX < colBarX() + 3
                && mouseY >= colThumbY() && mouseY < colThumbY() + colThumbH();
    }

    /** O mouse esta sobre a coluna esquerda (e nao sobre uma lista com rolagem)? */
    private boolean overColumn(double mouseX, double mouseY) {
        return activeTab == 0 && colMaxOffset() > 0
                && mouseX >= leftX && mouseX < leftX + colW
                && mouseY >= contentTop && mouseY < contentBottom;
    }

    // ------------------------------------------------------------------
    // ENTRADA DO MOUSE
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // A barra tem prioridade sobre o `super`: e arrasto de barra, nao clique
        // em widget (o mesmo cuidado da StatusScreen).
        if (colOverBar(event.x(), event.y())) {
            draggingCol = true;
            colOffset = colOffsetFromMouse(event.y());
            rebuildLeftColumn();
            return true;
        }
        for (ListRegion region : regions()) {
            if (region.overBar(event.x(), event.y(), barX(region))) {
                dragging = region;
                region.offset = region.offsetFromMouse(event.y());
                rebuildRegion(region);
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (draggingCol) {
            colOffset = colOffsetFromMouse(event.y());
            rebuildLeftColumn();
            return true;
        }
        if (dragging != null) {
            dragging.offset = dragging.offsetFromMouse(event.y());
            rebuildRegion(dragging);
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (draggingCol) {
            draggingCol = false;
            return true;
        }
        if (dragging != null) {
            dragging = null;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // A COLUNA vem antes da lista, ao contrario de antes. Perguntar a lista primeiro
        // fazia a roda, com o mouse sobre os atributos, mover so a janela interna de
        // 18 px da lista de atributos: a coluna nao andava e o Mestre via "não rolou".
        // O Mestre pediu rolagem na coluna inteira, e a lista de atributos esta dentro
        // dela, entao a coluna e a unidade de rolagem ali. As listas de fora da coluna
        // (pericias, caracteristicas, passivas, ativas) seguem rolando por regiao.
        if (overColumn(mouseX, mouseY) && colMaxOffset() > 0) {
            // `scrollY` e positivo ao rolar para CIMA, entao descer e `-signum`.
            colOffset = Math.max(0, Math.min(colMaxOffset(),
                    colOffset + (int) -Math.signum(scrollY)));
            rebuildLeftColumn();
            return true;
        }
        ListRegion region = regionAt(mouseX, mouseY);
        if (region != null && region.maxOffset() > 0) {
            // `scrollY` e positivo ao rolar para CIMA, entao descer e `-signum`.
            region.offset = Math.max(0, Math.min(region.maxOffset(),
                    region.offset + (int) -Math.signum(scrollY)));
            rebuildRegion(region);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // ------------------------------------------------------------------
    // FORMULARIOS DAS ENTRADAS
    // ------------------------------------------------------------------

    private void editPericias() {
        syncFromWidgets();
        this.minecraft.setScreen(new ThreatPericiasScreen(this, List.copyOf(pericias), saved -> {
            pericias.clear();
            pericias.addAll(saved);
        }));
    }

    private void addFeature() {
        syncFromWidgets();
        this.minecraft.setScreen(new ThreatEntryFormScreen(this, "Nova Característica",
                List.of(new ThreatEntryFormScreen.Field("text", "Característica",
                        ThreatSheet.MAX_FEATURE, true)),
                Map.of(), map -> features.add(map.getOrDefault("text", ""))));
    }

    private void editFeature(int index) {
        if (index < 0 || index >= features.size()) {
            return;
        }
        syncFromWidgets();
        this.minecraft.setScreen(new ThreatEntryFormScreen(this, "Editar Característica",
                List.of(new ThreatEntryFormScreen.Field("text", "Característica",
                        ThreatSheet.MAX_FEATURE, true)),
                Map.of("text", features.get(index)),
                map -> features.set(index, map.getOrDefault("text", ""))));
    }

    private void addPassive() {
        syncFromWidgets();
        this.minecraft.setScreen(new ThreatEntryFormScreen(this, "Nova Habilidade Passiva",
                passiveFields(), Map.of(), map -> passives.add(new ThreatSheet.Ability(
                        map.getOrDefault("name", ""), map.getOrDefault("description", "")))));
    }

    private void editPassive(int index) {
        if (index < 0 || index >= passives.size()) {
            return;
        }
        syncFromWidgets();
        ThreatSheet.Ability current = passives.get(index);
        this.minecraft.setScreen(new ThreatEntryFormScreen(this, "Editar Habilidade Passiva",
                passiveFields(),
                Map.of("name", current.name(), "description", current.description()),
                map -> passives.set(index, new ThreatSheet.Ability(
                        map.getOrDefault("name", ""), map.getOrDefault("description", "")))));
    }

    private void addAction() {
        syncFromWidgets();
        this.minecraft.setScreen(new ThreatEntryFormScreen(this, "Nova Habilidade Ativa",
                actionFields(), Map.of(), map -> actions.add(new ThreatSheet.Action(
                        map.getOrDefault("name", ""), map.getOrDefault("attackBonus", ""),
                        map.getOrDefault("damage", ""), map.getOrDefault("description", "")))));
    }

    private void editAction(int index) {
        if (index < 0 || index >= actions.size()) {
            return;
        }
        syncFromWidgets();
        ThreatSheet.Action current = actions.get(index);
        this.minecraft.setScreen(new ThreatEntryFormScreen(this, "Editar Habilidade Ativa",
                actionFields(),
                Map.of("name", current.name(), "attackBonus", current.attackBonus(),
                        "damage", current.damage(), "description", current.description()),
                map -> actions.set(index, new ThreatSheet.Action(
                        map.getOrDefault("name", ""), map.getOrDefault("attackBonus", ""),
                        map.getOrDefault("damage", ""), map.getOrDefault("description", "")))));
    }

    private static List<ThreatEntryFormScreen.Field> passiveFields() {
        return List.of(
                new ThreatEntryFormScreen.Field("name", "Nome",
                        ThreatSheet.MAX_ABILITY_NAME, false),
                new ThreatEntryFormScreen.Field("description", "Descrição",
                        ThreatSheet.MAX_ABILITY_DESC, true));
    }

    private static List<ThreatEntryFormScreen.Field> actionFields() {
        return List.of(
                new ThreatEntryFormScreen.Field("name", "Nome",
                        ThreatSheet.MAX_ACTION_NAME, false),
                new ThreatEntryFormScreen.Field("attackBonus", "Bônus",
                        ThreatSheet.MAX_ACTION_BONUS, false),
                new ThreatEntryFormScreen.Field("damage", "Dano",
                        ThreatSheet.MAX_ACTION_DAMAGE, false),
                new ThreatEntryFormScreen.Field("description", "Descrição",
                        ThreatSheet.MAX_ACTION_DESC, true));
    }

    // ------------------------------------------------------------------
    // SALVAR
    // ------------------------------------------------------------------

    private void rebuildFooter() {
        int btnW = Math.max(50, (panelW - 2 * PAD - GAP) / 2);
        addRenderableWidget(Button.builder(Component.literal("Salvar"), b -> save())
                .bounds(panelX + panelW - PAD - btnW, footerY, btnW, FOOTER_H).build());
        addRenderableWidget(Button.builder(Component.literal("< Voltar"), b -> onClose())
                .bounds(panelX + PAD, footerY, btnW, FOOTER_H).build());
    }

    /** Copia o que esta nas caixas para {@link #values}. */
    private void syncFromWidgets() {
        for (Map.Entry<String, EditBox> entry : boxes.entrySet()) {
            values.put(entry.getKey(), entry.getValue().getValue());
        }
        if (descriptionBox != null) {
            values.put("description", descriptionBox.getValue());
        }
    }

    /** O botao Salvar: monta a ficha inteira e entrega ao servidor. */
    private void save() {
        syncFromWidgets();

        List<ThreatSheet.AttributeValue> attributes = new ArrayList<>();
        for (SheetModel.AttributeDef def : model().attributes()) {
            // Campo vazio e 0, como sempre; o que nao passa e o numero digitado
            // grande. O record cortaria para 999 em silencio, e o Mestre acharia que
            // salvou o valor que viu na tela.
            int value = parseInt(values.get(attrKey(def.id())), 0);
            if (value < ThreatSheet.VALUE_MIN || value > ThreatSheet.VALUE_MAX) {
                status = "Atributo " + def.name() + " fora do limite ("
                        + ThreatSheet.VALUE_MIN + " a " + ThreatSheet.VALUE_MAX + ").";
                statusColor = COL_ERROR;
                return;
            }
            attributes.add(new ThreatSheet.AttributeValue(def.id(), value));
        }

        String name = values.getOrDefault("name", "");

        ThreatSheet built = new ThreatSheet(
                new ThreatSheet.Identity(
                        name,
                        levelValue(),
                        values.getOrDefault("displayName", ""),
                        showDisplayName,
                        values.getOrDefault("type", ""),
                        values.getOrDefault("size", ""),
                        values.getOrDefault("speed", "")),
                new ThreatSheet.Vitals(
                        hp,
                        parseInt(values.get("hpMax"), 0),
                        parseInt(values.get("ca"), 0)),
                values.getOrDefault("description", ""),
                attributes,
                pericias,
                features,
                passives,
                actions);

        if (this.minecraft == null || this.minecraft.getConnection() == null) {
            return;
        }
        // Antes do envio: e este nome, e nao o que estiver na caixa no fim da espera, que
        // o servidor vai gravar. Se o save for recusado, o campo fica como estava e
        // o proximo envio volta a ser uma criacao -- que e o que o Mestre quer.
        pendingSaveName = name;
        ClientPlayNetworking.send(new RpgNetworking.ThreatSheetSavePayload(built, originalName));
        status = "Enviando a ficha...";
        statusColor = COL_MUTED;
    }

    // ------------------------------------------------------------------
    // RESPOSTA DO SERVIDOR
    // ------------------------------------------------------------------

    /**
     * O save ou o apag respondeu.
     *
     * <p><b>Por que o {@code originalName} passa a ser o nome enviado:</b> e o que
     * garante que o SEGUNDO Salvar caia em "edita a existente". O servidor escolhe o
     * caminho pelo nome que veio no pacote anterior: nome vazio cria, nome cheio
     * procura e sobrescreve. Com o campo congelado, salvar duas vezes seguidas era
     * criar duas fichas com o mesmo nome ("Já existe uma ficha com esse nome") ou, se
     * o nome tiver sido mudado, apagar nada e responder "não existe mais" -- e o
     * Mestre perdia a edicao sem nenhum aviso.
     *
     * <p><b>Por que só quando o servidor aceitou:</b> numa recusa o nome gravado
     * continua sendo o anterior, e trocar o campo ali faria o proximo Salvar procurar
     * uma ficha que nunca existiu.
     */
    public void applyResult(boolean ok, String message, List<ThreatSheet> sheets) {
        if (ok && !pendingSaveName.isEmpty()) {
            // A partir daqui esta ficha existe com esse nome, entao e ele que o
            // proximo save vai carregar como "a que estou editando".
            originalName = pendingSaveName;
        }
        if (sheets != null) {
            knownSheets = sheets;
        }
        status = message == null ? "" : message;
        statusColor = ThreatSheetsScreen.statusColorFor(ok, status);
    }

    /**
     * Chegou a lista de fichas.
     *
     * <p>A ficha aberta nao desenha nada com ela: o que interessa aqui e o
     * rodape, que ja mostra a resposta do save. A lista e guardada so para a tela
     * saber quantas fichas existem.
     */
    public void applyList(List<ThreatSheet> sheets) {
        if (sheets != null) {
            knownSheets = sheets;
        }
    }

    // ------------------------------------------------------------------
    // DESENHO
    // ------------------------------------------------------------------

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, COL_SCREEN_BG);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, COL_PANEL_BG);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + 1, COL_BORDER);
        graphics.fill(panelX, panelY + panelH - 1, panelX + panelW, panelY + panelH, COL_BORDER);
        for (ListRegion region : regions()) {
            graphics.fill(region.x - 2, region.y,
                    region.x + region.w - BAR_W - 2, region.y + region.h, COL_LIST_BG);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);

        // O desenho le `values`, e `values` so era atualizado em `save()`, nos formularios
        // e na reconstrucao da coluna. O Mestre digitava o HP maximo, a barra e o rotulo
        // mostravam o valor velho, e so aparecia o novo depois de trocar de aba. Aqui o
        // texto das caixas vira valor a cada quadro: e so `EditBox.getValue()`, entao
        // nao cria widget nem recria coluna no meio do desenho.
        syncFromWidgets();

        graphics.drawCenteredString(this.font, this.title,
                panelX + panelW / 2, panelY + PAD, COL_TITLE);

        for (Label label : labels) {
            // Rotulo que a rolagem da coluna levou para fora da faixa: nao desenha
            // (o widget dele nem existe, ver `inColumn`).
            if (label.y() + LABEL_H <= contentTop || label.y() >= contentBottom) {
                continue;
            }
            graphics.drawString(this.font, label.text(), label.x(), label.y(), label.color(), false);
        }

        if (activeTab == 0) {
            drawSheetTab(graphics);
        } else {
            drawAbilitiesTab(graphics);
        }

        for (ListRegion region : regions()) {
            if (region.maxOffset() <= 0) {
                continue;
            }
            int x = barX(region);
            graphics.fill(x, region.y, x + 3, region.y + region.h, 0xFF101010);
            graphics.fill(x, region.thumbTop(), x + 3,
                    region.thumbTop() + region.thumbHeight(), 0xFF6B4A2A);
        }

        if (activeTab == 0 && colMaxOffset() > 0) {
            int x = colBarX();
            graphics.fill(x, contentTop, x + 3, contentBottom, 0xFF101010);
            graphics.fill(x, colThumbY(), x + 3, colThumbY() + colThumbH(), 0xFF6B4A2A);
        }

        if (!status.isEmpty()) {
            graphics.drawCenteredString(this.font, this.font.plainSubstrByWidth(status,
                    panelW - 2 * PAD), panelX + panelW / 2, statusY, statusColor);
        }
    }

    private void drawSheetTab(GuiGraphics graphics) {
        drawHpBar(graphics);

        // Atributos: "STR Strength" como no StatusScreen -- o rotulo curto
        // ancora o olho e o nome por extenso diz o que e.
        List<SheetModel.AttributeDef> attributes = model().attributes();
        for (int i = attrRegion.offset; i < attrRegion.lastIndex() && i < attributes.size(); i++) {
            SheetModel.AttributeDef def = attributes.get(i);
            int boxW = Math.min(ATTR_VALUE_W, attrRegion.w / 3);
            String text = def.label() + " " + def.name();
            // 16 px de folga a direita: o rotulo encostava na caixa de valor e os dois pareciam
            // um texto so (relato do Mestre em 02/10/2026).
            int room = attrRegion.w - boxW - GAP - 16;
            graphics.drawString(this.font,
                    this.font.plainSubstrByWidth(text, Math.max(10, room)),
                    attrRegion.x + 6, attrRegion.rowY(i) + 5, COL_TEXT, false);
        }

        if (pericias.isEmpty()) {
            graphics.drawString(this.font, "Nenhuma perícia.",
                    periciaRegion.x + 2, periciaRegion.y + 1, COL_MUTED, false);
        }
        // Cabecalhos das duas colunas da lista de pericias, pedidos pelo Mestre: "Perícia"
        // sobre o nome e "Bônus" alinhado com a coluna do valor. Ficam na faixa de 10 px
        // que `buildSheetRightColumn` reservou acima da lista.
        graphics.drawString(this.font, "Perícia", periciaRegion.x + 6,
                periciaRegion.y - 13, COL_SECTION, false);
        graphics.drawString(this.font, "Bônus",
                periciaRegion.x + periciaRegion.w - BAR_W - 12 - this.font.width("Bônus"),
                periciaRegion.y - 13, COL_SECTION, false);
        for (int i = periciaRegion.offset; i < periciaRegion.lastIndex() && i < pericias.size(); i++) {
            ThreatSheet.PericiaValue pericia = pericias.get(i);
            SheetModel.PericiaDef def = model().periciaById(pericia.id());
            String name = def == null || def.name().isEmpty()
                    ? "[" + pericia.id() + "]" : def.name();
            // 12 px de folga da direita: o valor encostava na borda da caixa (relato do Mestre
            // em 02/10/2026). O `+ 2` abaixo e folga da borda de cima da linha.
            int y = periciaRegion.rowY(i) + 2;
            // O padding conta a partir da BORDA DO FUNDO (que comeca em
            // `periciaRegion.x - 2`) e nao a partir de `periciaRegion.x`: por isso o
            // texto vai em `x + 6` e nao em `x + 2`. Era esse o "colado na caixa".
            graphics.drawString(this.font, this.font.plainSubstrByWidth(name,
                    Math.max(10, periciaRegion.w - 52)), periciaRegion.x + 6, y, COL_TEXT, false);
            String value = formatValue(pericia.value());
            graphics.drawString(this.font, value,
                    periciaRegion.x + periciaRegion.w - BAR_W - 12 - this.font.width(value),
                    y, pericia.value() < 0 ? CharacterSheetScreen.COL_HP_OVER : COL_MUTED, false);
        }

        if (features.isEmpty()) {
            graphics.drawString(this.font, "Nenhuma característica.",
                    featureRegion.x + 2, featureRegion.y + 5, COL_MUTED, false);
        }
        // O texto da caracteristica e desenhado pelo proprio botao Editar: e ele
        // que ocupa a linha e o que abre o formulario preenchido.
    }

    private void drawAbilitiesTab(GuiGraphics graphics) {
        if (passives.isEmpty()) {
            graphics.drawString(this.font, "Nenhuma passiva.",
                    passiveRegion.x + 2, passiveRegion.y + 5, COL_MUTED, false);
        }
        for (int i = passiveRegion.offset; i < passiveRegion.lastIndex() && i < passives.size(); i++) {
            int y = passiveRegion.rowY(i) + FIELD_H + 2;
            drawWrapped(graphics, passives.get(i).description(), passiveRegion.x + 4, y,
                    passiveRegion.w - BAR_W - 10, DESC_LINES, COL_MUTED);
        }

        if (actions.isEmpty()) {
            graphics.drawString(this.font, "Nenhuma habilidade ativa.",
                    actionRegion.x + 2, actionRegion.y + 5, COL_MUTED, false);
        }
        for (int i = actionRegion.offset; i < actionRegion.lastIndex() && i < actions.size(); i++) {
            ThreatSheet.Action action = actions.get(i);
            int y = actionRegion.rowY(i) + FIELD_H + 2;
            // Os dois valores podem vir vazios: bonus e dano sao texto livre, e
            // "so o nome" e uma habilidade valida.
            graphics.drawString(this.font, this.font.plainSubstrByWidth(
                            "Bônus: " + action.attackBonus() + "  Dano: " + action.damage(),
                            Math.max(10, actionRegion.w - BAR_W - 10)),
                    actionRegion.x + 4, y, COL_SECTION, false);
            drawWrapped(graphics, action.description(), actionRegion.x + 4, y + DESC_LINE_H,
                    actionRegion.w - BAR_W - 10, DESC_LINES, COL_MUTED);
        }
    }

    /**
     * Texto quebrado por largura, com no maximo {@code maxLines} linhas.
     *
     * <p><b>Por que reticencias e nao corte a seco:</b> sem elas o Mestre nao sabe
     * se leu tudo. A quebra usa largura reduced em {@link #DESC_LINE_H} px porque a
     * ultima linha recebe as reticencias por cima.
     */
    /**
     * Valor de pericia como o Mestre pediu: positivo com "+" na frente, negativo com
     * o "-" de sempre.
     *
     * <p><b>Por que:</b> no olho rapido `12` e `-12` eram face a face na mesma coluna
     * e dava para ler o errado. O "+" marca o positivo sem mudar o dado salvo.
     */
    private static String formatValue(int value) {
        return value > 0 ? "+" + value : Integer.toString(value);
    }

    private void drawWrapped(GuiGraphics graphics, String text, int x, int y, int width,
                             int maxLines, int color) {
        if (text == null || text.isEmpty() || width <= 0 || maxLines <= 0) {
            return;
        }
        String ellipsis = "...";
        List<FormattedCharSequence> lines = this.font.split(Component.literal(text), width);
        if (lines.size() > maxLines) {
            // Rebraça mais estreito para a ultima linha caber com as reticencias.
            lines = this.font.split(Component.literal(text),
                    Math.max(10, width - this.font.width(ellipsis)));
        }
        for (int i = 0; i < lines.size() && i < maxLines; i++) {
            // `FormattedCharSequence.toString()` NAO e o texto: devolve o nome da classe
            // (`net.minecraft.util.FormattedCharSequence$$Lambda$...`). Era isso que
            // aparecia no lugar da descricao das habilidades passivas e ativas. O texto
            // se extrai percorrendo os code points da sequencia.
            String line = plainText(lines.get(i));
            if (i == maxLines - 1 && lines.size() > maxLines) {
                line = line + ellipsis;
            }
            graphics.drawString(this.font, line, x, y + i * DESC_LINE_H, color, false);
        }
    }

    /**
     * Extrai o texto puro de uma sequencia formatada.
     *
     * <p><b>Por que nao {@code seq.toString()}:</b> ver o comentário em
     * {@link #drawWrapped}. A sequencia carrega estilo por trecho, entao o texto so
     * existe percorrendo os code points com o consumidor do `accept`.
     */
    private static String plainText(FormattedCharSequence seq) {
        // `FormattedCharSequence` nao tem `length()` nesta versao: o tamanho so existe
        // percorrendo os code points, que e o que o `accept` faz.
        StringBuilder out = new StringBuilder();
        seq.accept((index, style, codePoint) -> {
            out.appendCodePoint(codePoint);
            return true;
        });
        return out.toString();
    }

    /**
     * A barra do HP, desenhada a mao como a do jogador.
     *
     * <p><b>Por que o denominador e {@code max(valor, teto)}:</b> o mesmo motivo do
     * jogador -- o HP pode passar do teto (excedente), e com o teto fixo como
     * denominador o excedente nao teria onde aparecer.
     */
    private void drawHpBar(GuiGraphics graphics) {
        if (hpBar == null) {
            return;
        }
        Bar bar = hpBar;
        int max = Math.max(0, parseInt(values.get("hpMax"), 0));
        double denom = Math.max(hp, max);
        double fraction = denom <= 0 ? 0.0 : Math.min(1.0, (double) Math.max(0, hp) / denom);

        graphics.fill(bar.x(), bar.y(), bar.x() + bar.w(), bar.y() + bar.h(), COL_HP_BG);
        // Excedente (HP acima do teto): a barra fica `COL_HP` ate o teto e o que passa
        // dele usa a cor de excedente do jogador, `COL_HP_OVER`.
        int uptoMax = max <= 0 ? 0
                : (int) Math.round(bar.w() * Math.min(1.0, (double) hp / max));
        if (uptoMax > 0) {
            graphics.fill(bar.x(), bar.y(), bar.x() + uptoMax, bar.y() + bar.h(), COL_HP);
        }
        int fillW = (int) Math.round(bar.w() * fraction);
        if (hp > max && fillW > uptoMax) {
            graphics.fill(bar.x() + uptoMax, bar.y(), bar.x() + fillW, bar.y() + bar.h(),
                    CharacterSheetScreen.COL_HP_OVER);
        }
        graphics.fill(bar.x(), bar.y(), bar.x() + bar.w(), bar.y() + 1, COL_BAR_EDGE);
        graphics.fill(bar.x(), bar.y() + bar.h() - 1, bar.x() + bar.w(), bar.y() + bar.h(), COL_BAR_EDGE);

        // "Sobrevida": com HP acima do teto aparece "12 / 10 (+2)", na cor de
        // excedente, para nao parecer que o HP foi digitado errado.
        boolean over = hp > max;
        String full = hp + " / " + max + (over ? " (+" + (hp - max) + ")" : "");
        String text = this.font.width(full) <= bar.w() ? full
                : this.font.plainSubstrByWidth(full, bar.w());
        graphics.drawString(this.font, text,
                bar.x() + (bar.w() - this.font.width(text)) / 2,
                bar.y() + (bar.h() - 8) / 2,
                over ? CharacterSheetScreen.COL_HP_OVER : 0xFFFFFFFF, true);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parentScreen);
    }
}