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
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
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
    private static final int KIND_COPIES = 5;

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
     * exibicao + caixa, modo horda + caixa, tipo/tamanho/deslocamento e o bloco de
     * HP de 3 linhas.
     *
     * <p><b>Por que e uma constante e nao a posicao do ultimo bloco:</b> a altura
     * da coluna precisa ser conhecida ANTES de qualquer widget existir, porque o
     * offset de rolagem ja entra na posicao do primeiro. So a soma permite decidir
     * a rolagem antes de montar o primeiro campo.
     */
    private static final int COL_FIXED_H = (LABEL_H + FIELD_H + GAP)
            + (LABEL_H + FIELD_H + CHECKBOX_H + GAP)
            + (CHECKBOX_H + GAP)
            + (LABEL_H + FIELD_H + GAP)
            + (SECTION_H + 2 * (FIELD_H + GAP));
    /** Rotulo da descricao + folga antes do bloco de atributos. */
    private static final int DESC_HEAD_H = LABEL_H + GAP;
    /** Rotulo "Atributos"; a lista comeca logo abaixo dele. */
    private static final int ATTR_HEAD_H = SECTION_H;
    private static final int STEP_GAP = 4;
    private static final int ATTR_VALUE_W = 48;
    private static final int BAR_W = 4;
    /**
     * Largura da coluna de copias (07/10/2026) dentro do editor.
     *
     * <p><b>Por que so entra acima de uma largura minima de painel:</b> a coluna
     * come 132 px das duas colunas do formulario. Em painel estreito o formulario
     * viraria duas frestas; abaixo de {@value #COPIES_MIN_PANEL_W} de painel a
     * coluna some e o editor volta a ser como era antes.
     */
    // 132 -> 165 (+25%) e o minimo 360 -> 430 no feedback de 07/10/2026: o botao
    // Copiar e a coluna eram estreitos demais. A largura do MENU nao mudou; quem
    // cedeu foram as duas colunas do formulario (colW), e o minimo subiu junto
    // para a coluna nao se espremer dentro do painel.
    private static final int COPIES_W = 165;
    private static final int COPIES_MIN_PANEL_W = 430;
    private static final int SMALL_BTN_H = 16;
    // 760 px: o relato de 07/10/2026 foi que a coluna de copias espremeu o titulo
    // "Ataques e Habilidades Ativas" contra o "+ Habilidade". A tela ficou mais
    // larga nessa rodada; o corte do titulo e a rede de segurança (ver
    // `addTabsLabel(String, int, int, int)`).
    private static final int MAX_PANEL_W = 760;
    /** Teto de passos do Reverter: memorizar mais que isso e sobra de memoria. */
    private static final int MAX_UNDO = 64;
    // Sem teto de altura: o painel ocupa a tela inteira, como a ficha do jogador,
    // que tambem deriva de `topY..bottomY`. Com teto, numa tela logica de 270 px
    // (1080p com GUI scale automatico) o painel ficava com 258 px e os atributos
    // nao cabiam.

    private final Screen parentScreen;
    // Nao-final desde 07/10/2026: ao salvar com OUTRO nome a tela troca a ficha
    // que esta editando pela gravada no servidor (mesmo id, nome novo), para o
    // Copiar funcionar sem fechar e reabrir.
    private ThreatSheet sheet;

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
    /** A ficha vale para varios mobs ao mesmo tempo (1:N). 22/10/2026. */
    private boolean hordeMode;
    private int activeTab;
    /** Quantos px da coluna esquerda estao rolados para cima. */
    private int colOffset;

    /**
     * Estado salvo (abertura ou ultimo save aceito): e contra ele que a bolinha
     * amarela compara (07/10/2026).
     */
    private ThreatSheetDraft savedState;
    /** O estado de antes dos inputs do quadro: e o que o Reverter restaura. */
    private ThreatSheetDraft preFrame;
    /** Passos do Reverter, um por sessao de edicao ou acao, teto de {@link #MAX_UNDO}. */
    private final ArrayDeque<ThreatSheetDraft> undoStack = new ArrayDeque<>();
    /** Cada box em sessao de digitacao (a primeira tecla empurra, as outras nao). */
    private final Map<String, Boolean> typing = new HashMap<>();
    /** O ultimo texto visto de cada box, para reconhecer a primeira tecla. */
    private final Map<String, String> lastSeen = new HashMap<>();
    /** O estado mandado no ultimo save; vira a base da bolinha se o servidor aceitar. */
    private ThreatSheetDraft pendingSaved;
    /** Ha edicao nao salva? E a bolinha amarela ao lado do Salvar. */
    private boolean dirty;

    private Button saveButton;
    private Button reverterButton;
    private Button copiarButton;
    /** A linha de copia esperando o segundo clique do Del, ou -1. */
    private int pendingCopyDelete = -1;
    /** Largura do Del de uma linha de copia (igual aos outros menus). */
    private static final int COPIES_DEL_W = 30;

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
    private final ListRegion copiesRegion = new ListRegion(KIND_COPIES);
    private final List<AbstractWidget> copiesWidgets = new ArrayList<>();
    private boolean copiesVisible;
    /**
     * O subgrupo de copias comeca recolhido (22/10/2026, PARTE 6): o cabecalho
     * continua visivel e clicavel, mas as linhas so aparecem quando o Mestre
     * abre o grupo.
     */
    private boolean copiesExpanded;
    private int copiesX;
    private int copiesRowsTop;

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
    /**
     * A ficha foi aberta pelo item, e nao pelo menu?
     *
     * <p>Só muda duas coisas: o rótulo do botão e se o save entrega item. O resto da tela
     * edita igual nos dois caminhos -- o Mestre pediu que abrir pelo item ja pudesse
     * editar (02/10/2026).
     */
    private final boolean updateMode;
    private String status = "";
    private int statusColor = COL_MUTED;

    public ThreatSheetScreen(Screen parentScreen, ThreatSheet sheet, String originalName) {
        this(parentScreen, sheet, originalName, false);
    }

    /**
     * @param updateMode a ficha foi aberta pelo item, nao pelo menu: o botao vira
     *                   "Atualizar" e o save NAO entrega outro item (decisao do Mestre
     *                   em 02/10/2026).
     */
    public ThreatSheetScreen(Screen parentScreen, ThreatSheet sheet, String originalName,
                             boolean updateMode) {
        super(Component.literal(sheet == null ? "Ficha de Ameaça" : sheet.listLabel()));
        this.parentScreen = parentScreen;
        this.sheet = sheet;
        this.originalName = originalName == null ? "" : originalName;
        this.updateMode = updateMode;
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
            this.hordeMode = sheet.hordeMode();
            this.features.addAll(sheet.features());
            this.passives.addAll(sheet.passives());
            this.actions.addAll(sheet.actions());
            this.pericias.addAll(sheet.pericias());
            for (ThreatSheet.AttributeValue attribute : sheet.attributes()) {
                values.put(attrKey(attribute.id()), Integer.toString(attribute.value()));
            }
        }

        // Rascunho da sessao anterior (07/10/2026): se o Mestre fechou sem salvar,
        // a edicao volta exatamente como estava e a lista mostra a bolinha ate salvar.
        ThreatSheetDraft draft = sheet != null && sheet.hasId()
                ? ThreatSheetClientState.draftOf(sheet.id()) : null;
        if (draft != null) {
            values.putAll(draft.values());
            features.clear();
            features.addAll(draft.features());
            passives.clear();
            passives.addAll(draft.passives());
            actions.clear();
            actions.addAll(draft.actions());
            pericias.clear();
            pericias.addAll(draft.pericias());
            this.showDisplayName = draft.showDisplayName();
            this.hordeMode = draft.hordeMode();
            this.hp = draft.hp();
        }
        // A base da bolinha e o estado da ABERTURA (ou do ultimo save aceito).
        // Precisou ser aqui e nao no init: o init roda a cada troca de aba e
        // recriacao, e capturar ali apagaria a sujeira a cada rebuild.
        this.savedState = capture();
        this.preFrame = capture();
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
        addArrows();

        if (activeTab == 0) {
            buildSheetTab();
        } else {
            buildAbilitiesTab();
        }
        rebuildFooter();
        // A lista de copias da direita (07/10/2026): pede a lista fresca e monta a
        // coluna; a resposta chega em `applyList` e a remonta.
        requestList();
        rebuildCopiesColumn();
        // A sessao de digitacao comeca do zero a cada recriacao: a primeira tecla
        // de cada box empurra o estado de AGORA, nao o de uma sessao morta.
        rearmTyping();
    }

    /** Calcula o painel, as colunas e as faixas de onde as listas vao comer espaco. */
    private void layout() {
        panelW = Math.max(200, Math.min(this.width - 16, MAX_PANEL_W));
        panelX = (this.width - panelW) / 2;
        // Sem as abas de cima (07/10/2026: viraram setas nos cantos da tela), a
        // faixa do titulo respira mais: 18 px de TAB_H + folga voltaram para o conteudo.
        int chrome = 2 * PAD + TITLE_H + GAP + FOOTER_H + STATUS_H + GAP;
        panelH = Math.max(120, this.height - 12);
        if (panelH < chrome + SECTION_H + ROW_H) {
            panelH = Math.min(this.height - 8, chrome + SECTION_H + ROW_H);
        }
        panelY = Math.max(PAD, (this.height - panelH) / 2);

        contentTop = panelY + PAD + TITLE_H + GAP;
        statusY = panelY + panelH - PAD - STATUS_H;
        footerY = statusY - GAP - FOOTER_H;
        contentBottom = footerY - GAP;

        // As duas colunas tem exatamente a mesma largura: e o que impede o
        // "Atributos" de invadir o CA (o defeito classico de dividir por resto).
        // A coluna esquerda tem barra de rolagem propria, e ela ficava em
        // `leftX - BAR_W`, DENTRO da margem do painel. Era isso que fazia a caixa da
        // esquerda parecer mais larga que a da direita. Agora a barra ocupa uma fatia
        // reservada entre as colunas e as duas caixas ficam com a mesma largura.
        // A coluna de copias (07/10/2026) fica na borda direita e COME o que ela mede
        // das duas colunas do formulario: so entra quando ha espaco de sobra.
        copiesVisible = panelW >= COPIES_MIN_PANEL_W;
        if (copiesVisible) {
            colW = (panelW - 2 * PAD - BAR_W - 2 - 2 * COL_GAP - COPIES_W) / 2;
        } else {
            colW = (panelW - 2 * PAD - COL_GAP - BAR_W - 2) / 2;
        }
        leftX = panelX + PAD;
        rightX = leftX + colW + BAR_W + 2 + COL_GAP;
        copiesX = rightX + colW + COL_GAP;
    }

    /**
     * As setinhas de trocar de pagina nos cantos inferiores da TELA (07/10/2026):
     * a direita avanca para Habilidades, a esquerda volta para a ficha. Cada uma
     * so existe na pagina em que faz sentido.
     */
    private void addArrows() {
        // 07/10/2026: as setas ficavam nas extremidades da TELA e quase sumiam.
        // Agora elas colam nos cantos de baixo do MENU.
        int size = 18;
        int y = panelY + panelH - size - 6;
        if (activeTab == 0) {
            addRenderableWidget(Button.builder(Component.literal("\u25B6"), b -> setTab(1))
                    .bounds(panelX + panelW - size - 6, y, size, size)
                    .tooltip(Tooltip.create(Component.translatable("screen.tabletoprpg.threat_sheet.go_abilities")))
                    .build());
        } else {
            addRenderableWidget(Button.builder(Component.literal("\u25C0"), b -> setTab(0))
                    .bounds(panelX + 6, y, size, size)
                    .tooltip(Tooltip.create(Component.translatable("screen.tabletoprpg.threat_sheet.back_to_sheet")))
                    .build());
        }
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

    /**
     * Titulo de secao cortado para nunca invadir o botao da mesma linha: e a rede
     * de seguranca do titulo "Ataques e Habilidades Ativas" contra o "+ Habilidade"
     * quando o painel volta a ficar estreito (relato de 07/10/2026).
     */
    private void addTabsLabel(String text, int x, int y, int maxW) {
        labels.add(new Label(this.font.plainSubstrByWidth(text, Math.max(10, maxW)),
                x, y, COL_SECTION));
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
                            Component.translatable("screen.tabletoprpg.threat_sheet.show_name"), this.font)
                    // 6 px de folga abaixo do campo: antes a caixa nascia colada na borda de baixo do
            // "Nome de Exibicao" (relato do Mestre em 02/10/2026).
                    .pos(leftX + 2, y + LABEL_H + FIELD_H + 6)
                    .maxWidth(colW - 4)
                    .selected(showDisplayName)
                    .onValueChange((cb, checked) -> {
                        pushUndo(); // um clique e um passo: o estado de antes vira o Reverter
                        showDisplayName = checked;
                    })
                    .build();
            checkbox.setSize(colW - 4, CHECKBOX_H);
            addColWidget(checkbox);
            // A caixa do nome NAO e mais trancada pela caixa de marcar: o nome e
            // obrigatorio (decisao do Mestre em 02/10/2026) e a caixa so decide se ele
            // aparece flutuando em cima do mob. Antes, destravar o campo dependia de uma
            // coisa que nao tem nada a ver com poder digitar nele.
        }
        y += LABEL_H + FIELD_H + CHECKBOX_H + 6 + GAP;

        // 2b) Modo Horda: a MESMA ficha vale para varios monstros (1:N). Fora
        // dela, a regra e a antiga: uma ficha, no maximo, um monstro.
        if (inColumn(y, CHECKBOX_H + GAP)) {
            Checkbox horde = Checkbox.builder(
                            Component.translatable("screen.tabletoprpg.threat_sheet.horde_mode"), this.font)
                    .pos(leftX + 2, y)
                    .maxWidth(colW - 4)
                    .selected(hordeMode)
                    .onValueChange((cb, checked) -> {
                        pushUndo(); // um clique e um passo: o estado de antes vira o Reverter
                        hordeMode = checked;
                    })
                    .build();
            horde.setSize(colW - 4, CHECKBOX_H);
            addColWidget(horde);
        }
        y += CHECKBOX_H + GAP;

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
                    .build(this.font, colW, descH, Component.translatable("screen.tabletoprpg.threat_sheet.description"));
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
        addRenderableWidget(Button.builder(Component.translatable("screen.tabletoprpg.threat_sheet.edit_pericias"), b -> editPericias())
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
        addRenderableWidget(Button.builder(Component.translatable("screen.tabletoprpg.threat_sheet.add_feature"), b -> addFeature())
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
        pushUndo(); // cada clique de HP e uma acao: um passo do Reverter
        hp = Math.max(ThreatSheet.MIN_RESOURCE,
                Math.min(ThreatSheet.MAX_RESOURCE, hp + delta));
    }

    // ------------------------------------------------------------------
    // ABA 1: HABILIDADES
    // ------------------------------------------------------------------

    private void buildAbilitiesTab() {
        int y = contentTop;
        addTabsLabel("Habilidades Passivas", leftX, y, colW - 105);
        addRenderableWidget(Button.builder(Component.translatable("screen.tabletoprpg.threat_sheet.add_ability"), b -> addPassive())
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
        addTabsLabel("Ataques e Habilidades Ativas", rightX, y2, colW - 105);
        addRenderableWidget(Button.builder(Component.translatable("screen.tabletoprpg.threat_sheet.add_ability"), b -> addAction())
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
        if (region.kind == KIND_COPIES) {
            rebuildCopiesColumn();
            return;
        }
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
        List<ListRegion> base = activeTab == 0
                ? List.of(attrRegion, periciaRegion, featureRegion)
                : List.of(passiveRegion, actionRegion);
        if (!copiesVisible || !copiesExpanded) {
            return base;
        }
        List<ListRegion> all = new ArrayList<>(base);
        all.add(copiesRegion);
        return all;
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
        pushUndo(); // abrir a tela de pericias e um passo do Reverter
        syncFromWidgets();
        this.minecraft.setScreen(new ThreatPericiasScreen(this, List.copyOf(pericias), saved -> {
            pericias.clear();
            pericias.addAll(saved);
        }));
    }

    private void addFeature() {
        pushUndo(); // criar uma entrada e um passo do Reverter
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
        pushUndo(); // editar uma entrada existente: um passo do Reverter
        syncFromWidgets();
        this.minecraft.setScreen(new ThreatEntryFormScreen(this, "Editar Característica",
                List.of(new ThreatEntryFormScreen.Field("text", "Característica",
                        ThreatSheet.MAX_FEATURE, true)),
                Map.of("text", features.get(index)),
                map -> features.set(index, map.getOrDefault("text", ""))));
    }

    private void addPassive() {
        pushUndo(); // criar uma entrada e um passo do Reverter
        syncFromWidgets();
        this.minecraft.setScreen(new ThreatEntryFormScreen(this, "Nova Habilidade Passiva",
                passiveFields(), Map.of(), map -> passives.add(new ThreatSheet.Ability(
                        map.getOrDefault("name", ""), map.getOrDefault("description", "")))));
    }

    private void editPassive(int index) {
        if (index < 0 || index >= passives.size()) {
            return;
        }
        pushUndo(); // editar uma entrada existente: um passo do Reverter
        syncFromWidgets();
        ThreatSheet.Ability current = passives.get(index);
        this.minecraft.setScreen(new ThreatEntryFormScreen(this, "Editar Habilidade Passiva",
                passiveFields(),
                Map.of("name", current.name(), "description", current.description()),
                map -> passives.set(index, new ThreatSheet.Ability(
                        map.getOrDefault("name", ""), map.getOrDefault("description", "")))));
    }

    private void addAction() {
        pushUndo(); // criar uma entrada e um passo do Reverter
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
        pushUndo(); // editar uma entrada existente: um passo do Reverter
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

    /**
     * Rodape com tres botoes (07/10/2026): Reverter, Salvar/Atualizar e Voltar,
     * dividindo a largura em tres. O Reverter desfaz a ultima sessao, como o diario.
     */
    private void rebuildFooter() {
        int btnW = Math.max(50, (panelW - 2 * PAD - 2 * GAP) / 3);
        reverterButton = Button.builder(Component.translatable("screen.tabletoprpg.threat_sheet.revert"), b -> undo())
                .bounds(panelX + PAD, footerY, btnW, FOOTER_H).build();
        addRenderableWidget(reverterButton);
        saveButton = Button.builder(
                        Component.literal(updateMode ? "Atualizar" : "Salvar"), b -> save())
                .bounds(panelX + PAD + btnW + GAP, footerY, btnW, FOOTER_H).build();
        addRenderableWidget(saveButton);
        // 07/10/2026: a bolinha do "nao salvo" fica DENTRO do botao, no canto
        // direito, atualizada a cada quadro (o desenho da tela acontece antes dos
        // widgets, e uma bolinha pintada ali sumiria atras do botao).
        addRenderableOnly(new DotWidget(saveButton.getX() + btnW - 8,
                saveButton.getY() + FOOTER_H / 2 - 2, () -> dirty));
        addRenderableWidget(Button.builder(Component.translatable("screen.tabletoprpg.threat_sheet.back"), b -> onClose())
                .bounds(panelX + panelW - PAD - btnW, footerY, btnW, FOOTER_H).build());
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

    /**
     * Fotografa o formulario inteiro em um {@link ThreatSheetDraft}: e o que o
     * Reverter compara e o que a bolinha amarela compara com o ultimo save.
     */
    private ThreatSheetDraft capture() {
        syncFromWidgets();
        return new ThreatSheetDraft(values, features, passives, actions, pericias,
                showDisplayName, hordeMode, hp);
    }

    /**
     * Empurra para a pilha do Reverter o estado de ANTES do input deste quadro.
     *
     * <p><b>Por que nao empurra toda tecla:</b> o diario desfaz em sessoes (a edicao
     * inteira de um campo e um passo, nao uma tecla). Aqui a sessao e reconhecida na
     * primeira tecla de cada box com foco (ver {@link #sweepBox}); os botoes de acao
     * (HP, checkbox e listas) empurram no proprio clique.
     */
    private void pushUndo() {
        if (preFrame == null) {
            return;
        }
        if (preFrame.equals(capture())) {
            return; // o input nao mudou nada (clique de lugar neutro): sem passo
        }
        if (!undoStack.isEmpty() && undoStack.peekFirst().equals(preFrame)) {
            return; // o mesmo estado ja esta no topo: passos iguais seguidos sao inuteis
        }
        undoStack.addFirst(preFrame);
        while (undoStack.size() > MAX_UNDO) {
            undoStack.removeLast();
        }
    }

    /** O botao Reverter: desfaz a ultima sessao de edicao ou acao (07/10/2026). */
    private void undo() {
        ThreatSheetDraft step = undoStack.pollFirst();
        if (step == null) {
            return;
        }
        // Antes de recriar os widgets o init sincroniza caixas -> values; entao as
        // caixas precisam ja refletir o estado restaurado, senao o sync engoliria o
        // undo e os campos renasceriam com o texto antigo (07/10/2026).
        values.clear();
        values.putAll(step.values());
        features.clear();
        features.addAll(step.features());
        passives.clear();
        passives.addAll(step.passives());
        actions.clear();
        actions.addAll(step.actions());
        pericias.clear();
        pericias.addAll(step.pericias());
        this.showDisplayName = step.showDisplayName();
        this.hordeMode = step.hordeMode();
        this.hp = step.hp();
        for (String key : values.keySet()) {
            EditBox box = boxes.get(key);
            if (box != null) {
                box.setValue(values.getOrDefault(key, ""));
            }
        }
        if (descriptionBox != null) {
            descriptionBox.setValue(values.getOrDefault("description", ""));
        }
        typing.clear();
        lastSeen.clear();
        rebuildWidgets();
        status = "Desfeita a ultima alteracao.";
        statusColor = ThreatSheetsScreen.statusColorFor(true, status);
    }

    /** A sessao de digitacao recomeca: a primeira tecla de cada box empurra de novo. */
    private void rearmTyping() {
        typing.clear();
        lastSeen.clear();
        for (Map.Entry<String, EditBox> entry : boxes.entrySet()) {
            typing.put(entry.getKey(), false);
            lastSeen.put(entry.getKey(), entry.getValue().getValue());
        }
        if (descriptionBox != null) {
            typing.put("description", false);
            lastSeen.put("description", descriptionBox.getValue());
        }
    }

    /** Procura no quadro as caixas que comecaram (ou morreram) uma sessao de edicao. */
    private void sweepTyping() {
        for (Map.Entry<String, EditBox> entry : boxes.entrySet()) {
            sweepBox(entry.getKey(), entry.getValue().getValue(), entry.getValue().isFocused());
        }
        if (descriptionBox != null) {
            sweepBox("description", descriptionBox.getValue(), descriptionBox.isFocused());
        }
    }

    private void sweepBox(String key, String text, boolean focused) {
        String seen = lastSeen.get(key);
        Boolean active = typing.get(key);
        if (!text.equals(seen)) {
            // Digitou: a PRIMEIRA tecla da sessao empurra o estado anterior; o resto
            // da sessao ja e parte do mesmo passo ate a sessao morrer (foco caiu).
            if (active == null || !active) {
                pushUndo();
                typing.put(key, true);
            }
            lastSeen.put(key, text);
        } else if (active != null && active && !focused) {
            // Perdeu o foco sem digitar nada novo: a sessao morre.
            typing.put(key, false);
        }
    }

    /**
     * O fim do quadro: sessao de digitacao, bolinha amarela da edicao nao salva e o
     * estado de antes do proximo input. A bolinha de mudanca nao salva e o
     * DotWidget do botao Salvar que a desenha, DENTRO do botao (07/10/2026).
     */
    private void finishFrame(GuiGraphics graphics) {
        ThreatSheetDraft current = capture();
        sweepTyping();
        dirty = (sheet != null && sheet.hasId() && ThreatSheetClientState.hasDraft(sheet.id()))
                || !current.equals(savedState);
        if (sheet != null && sheet.hasId()) {
            if (dirty) {
                ThreatSheetClientState.putDraft(sheet.id(), current);
            } else {
                ThreatSheetClientState.removeDraft(sheet.id());
            }
        }
        if (reverterButton != null) {
            reverterButton.active = !undoStack.isEmpty();
        }
        if (copiarButton != null) {
            copiarButton.active = !dirty && sheet != null && !sheet.isCopy() && sheet.hasId();
        }
        // A bolinha do "nao salvo" eh o DotWidget do rodape que a pinta, dentro do
        // botao Salvar, no canto interno direito, a cada quadro (07/10/2026).
        preFrame = current;
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
        String displayName = values.getOrDefault("displayName", "");
        int level = levelValue();

        // Campos obrigatorios: Nome, Nome de exibicao e ND (decisao do Mestre em
        // 02/10/2026). O aviso sai ANTES de montar a ficha, para o Mestre ver qual
        // campo falta e nao perder o que ja digitou.
        if (name.isBlank()) {
            status = "Nome da ameaça obrigatório.";
            statusColor = COL_ERROR;
            return;
        }
        // O nome de exibicao nao e cosmetico: e por ele que a ameaca e apontada em
        // comando (@e[name=...]).
        if (displayName.isBlank()) {
            status = "Nome de exibição obrigatório: é por ele que a ameaça é apontada em comando.";
            statusColor = COL_ERROR;
            return;
        }
        // ND vazio chega aqui como 0: e o unico jeito de distinguir "nao preenchido"
        // de um numero, porque o campo e inteiro. Ameaca de ND 0 nao existe na regra.
        if (level == 0) {
            status = "ND obrigatório: preencha o nível da ameaça.";
            statusColor = COL_ERROR;
            return;
        }

        ThreatSheet built = new ThreatSheet(
                // O cliente nunca sorteia id: devolve o que veio do servidor. O store
                // repõe o id guardado, que é o que mantem o vinculo do mob vivo.
                sheet == null ? "" : sheet.id(),
                new ThreatSheet.Identity(
                        name,
                        level,
                        displayName,
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
                actions,
                // O copyOf e vinculo com a mae, como o id e com o mob: o servidor e dono,
                // e o cliente devolve o que recebeu para nao desancorar a copia num save.
                sheet == null ? "" : sheet.copyOf(),
                // Modo Horda: a ficha vale para varios monstros ao mesmo tempo. O
                // servidor e quem decide o efeito do vinculo; o cliente so manda o
                // que o Mestre marcou na tela.
                hordeMode);

        if (this.minecraft == null || this.minecraft.getConnection() == null) {
            return;
        }
        // Antes do envio: e este nome, e nao o que estiver na caixa no fim da espera, que
        // o servidor vai gravar. Se o save for recusado, o campo fica como estava e
        // o proximo envio volta a ser uma criacao -- que e o que o Mestre quer.
        pendingSaveName = name;
        // O que foi enviado vira a base da bolinha se o servidor aceitar: o que o
        // Mestre digitar DURANTE a espera continua sujo (07/10/2026).
        pendingSaved = capture();
        ClientPlayNetworking.send(new RpgNetworking.ThreatSheetSavePayload(
                built, originalName, !updateMode));
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
    public void applyResult(boolean ok, String message, List<ThreatSheet> sheets,
                            String createdName) {
        ThreatSheetDraft sent = pendingSaved;
        pendingSaved = null;
        if (ok && !pendingSaveName.isEmpty()) {
            // A partir daqui esta ficha existe com esse nome, entao e ele que o
            // proximo save vai carregar como "a que estou editando".
            originalName = pendingSaveName;
        }
        if (ok && sent != null && sheet != null && sheet.hasId()) {
            // Save aceito: o estado enviado e a nova base da bolinha, o rascunho
            // some e os passos do Reverter da sessao anterior morrem junto (nao ha
            // mais o que desfazer daquela sessao).
            savedState = sent;
            ThreatSheetClientState.removeDraft(sheet.id());
            undoStack.clear();
            rearmTyping();
            // Salvou com OUTRO nome: esta tela passa a editar a ficha que o
            // servidor gravou (mesmo id, nome novo). Sem isso o Copiar mandava o
            // nome antigo e o servidor respondia "nao existe" (relato 07/10/2026).
            if (sheets != null) {
                for (ThreatSheet stored : sheets) {
                    if (stored.hasId() && stored.id().equals(sheet.id())) {
                        this.sheet = stored;
                        break;
                    }
                }
            }
        }
        if (sheets != null) {
            knownSheets = sheets;
        }
        status = message == null ? "" : message;
        statusColor = ThreatSheetsScreen.statusColorFor(ok, status);
        // Copiar uma ficha responde aqui com a lista nova: a coluna da direita
        // remonta para mostrar a copia que acabou de nascer.
        rebuildCopiesColumn();
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
        // A lista chegou (pedido desta tela ou resposta de criar/apagar): a coluna
        // de copias pode ter mudado de ordem, ganho ou perdido linhas.
        rebuildCopiesColumn();
    }

    // ------------------------------------------------------------------
    // COLUNA DE COPIAS (07/10/2026): a lista da direita no editor.
    // ------------------------------------------------------------------

    /** "Me manda a lista": o pedido e a propria tela, e nao so a lista de fichas. */
    private void requestList() {
        if (this.minecraft == null || this.minecraft.getConnection() == null) {
            return;
        }
        ClientPlayNetworking.send(new RpgNetworking.ThreatSheetListRequestPayload());
    }

    /**
     * A mae da ficha aberta, ou a propria ficha quando ela E a mae, ou {@code null}
     * quando nao ha id (ficha nova, ainda nao salva) ou a mae nao esta na lista.
     */
    private ThreatSheet copiesMother() {
        if (sheet == null) {
            return null;
        }
        if (sheet.isCopy()) {
            for (ThreatSheet s : knownSheets) {
                if (s.hasId() && s.id().equals(sheet.copyOf())) {
                    return s;
                }
            }
            return null;
        }
        return sheet.hasId() ? sheet : null;
    }

    /** As copias da mae (as irmas da ficha aberta, quando ela e copia), na ordem da lista. */
    private List<ThreatSheet> copiesList() {
        ThreatSheet mother = copiesMother();
        if (mother == null) {
            return List.of();
        }
        List<ThreatSheet> out = new ArrayList<>();
        for (ThreatSheet s : knownSheets) {
            if (s.hasId() && mother.id().equals(s.copyOf())) {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * Abre ou fecha o subgrupo de copias (22/10/2026, PARTE 6).
     *
     * <p>O cabecalho continua no lugar; so as linhas somem. Ao fechar, o "Del?"
     * pendente e desarmado, porque a linha que ele confirmava deixou de existir
     * na tela.
     */
    private void toggleCopies() {
        copiesExpanded = !copiesExpanded;
        if (!copiesExpanded) {
            pendingCopyDelete = -1;
        }
        rebuildCopiesColumn();
    }

    /**
     * Monta a coluna da direita: cabecalho (o titulo da MAE, clicavel, quando a
     * ficha aberta e uma copia; "Copias" + botao Copiar quando ela e a mae) e as
     * linhas das copias com a rolagem propria do projeto (a roda e a barra).
     */
    private void rebuildCopiesColumn() {
        for (AbstractWidget widget : copiesWidgets) {
            removeWidget(widget);
        }
        copiesWidgets.clear();
        copiarButton = null; // a coluna remonta e redecide: so a mae salva tem o botao
        if (!copiesVisible || sheet == null) {
            return;
        }

        ThreatSheet mother = copiesMother();
        List<ThreatSheet> copies = copiesList();
        int rowW = COPIES_W - BAR_W - 3;
        int headerH = GAP;
        if (sheet.isCopy()) {
            if (mother != null) {
                headerH = ROW_H + GAP;
                Button toggle = Button.builder(
                                Component.literal(copiesExpanded ? "\u25BC" : "\u25B6"),
                                b -> toggleCopies())
                        .bounds(copiesX, contentTop, 18, ROW_H).build();
                copiesWidgets.add(toggle);
                addRenderableWidget(toggle);
                Button titulo = Button.builder(
                                Component.literal(font.plainSubstrByWidth(
                                        mother.identity().name(), rowW - 20)),
                                b -> openSheet(mother))
                        .bounds(copiesX + 20, contentTop, rowW - 20, ROW_H).build();
                copiesWidgets.add(titulo);
                addRenderableWidget(titulo);
            }
        } else if (sheet.hasId()) {
            headerH = ROW_H + GAP;
            Button toggle = Button.builder(
                            Component.literal((copiesExpanded ? "\u25BC " : "\u25B6 ") + "Copias"),
                            b -> toggleCopies())
                    .bounds(copiesX, contentTop, Math.max(20, rowW - 48 - GAP), ROW_H).build();
            copiesWidgets.add(toggle);
            addRenderableWidget(toggle);
            this.copiarButton = Button.builder(Component.translatable("screen.tabletoprpg.threat_sheet.copy"), b -> copyThis())
                    .bounds(copiesX + rowW - 48, contentTop, 48, ROW_H).build();
            copiesWidgets.add(copiarButton);
            addRenderableWidget(copiarButton);
        }
        copiesRowsTop = contentTop + headerH;
        if (!copiesExpanded) {
            pendingCopyDelete = -1;
            return;
        }

        int rowsH = contentBottom - copiesRowsTop;
        copiesRegion.set(copiesX, copiesRowsTop, COPIES_W, rowsH, ROW_H, copies.size());
        copiesRegion.offset = Math.max(0, Math.min(copiesRegion.maxOffset(), copiesRegion.offset));
        if (pendingCopyDelete != -1
                && (pendingCopyDelete < copiesRegion.offset
                || pendingCopyDelete >= copiesRegion.lastIndex())) {
            pendingCopyDelete = -1; // saiu da janela: desarma o "Del?", como na lista do menu
        }
        for (int i = copiesRegion.offset; i < copiesRegion.lastIndex() && i < copies.size(); i++) {
            ThreatSheet copia = copies.get(i);
            int y = copiesRegion.rowY(i);
            if (copia.hasId() && copia.id().equals(sheet.id())) {
                // A ficha aberta: o nome sai apagado do render e a linha so tem o
                // Del -- nunca um botao que reabriria a ficha sobre ela mesma.
                Button del = copyDelButton(i, y, rowW);
                copiesWidgets.add(del);
                addRenderableWidget(del);
                continue;
            }
            Button linha = Button.builder(
                            Component.literal(font.plainSubstrByWidth(
                                    copia.identity().name(), rowW - COPIES_DEL_W - 4)),
                            b -> openSheet(copia))
                    .bounds(copiesX, y, rowW - COPIES_DEL_W - 2, ROW_H).build();
            copiesWidgets.add(linha);
            addRenderableWidget(linha);
            Button del = copyDelButton(i, y, rowW);
            copiesWidgets.add(del);
            addRenderableWidget(del);
            // 07/10/2026: a bolinha amarela de "copia com edicao nao salva" fica
            // DENTRO do botao do nome, e aparece quando o Mestre sai da ficha da
            // copia e volta para a coluna (o rascunho sobrevive ao Voltar).
            DotWidget dot = new DotWidget(copiesX + rowW - COPIES_DEL_W - 2 - 8,
                    y + ROW_H / 2 - 2,
                    () -> ThreatSheetClientState.hasDraft(copia.id()));
            copiesWidgets.add(dot);
            addRenderableOnly(dot);
        }
    }

    /** O Del de uma linha de copia: primeiro clique arma "Del?", segundo apaga. */
    private Button copyDelButton(int index, int y, int rowW) {
        boolean pending = pendingCopyDelete == index;
        return Button.builder(Component.literal(pending ? "Del?" : "Del"),
                        b -> onCopyDelete(index))
                .bounds(copiesX + rowW - COPIES_DEL_W, y, COPIES_DEL_W, ROW_H).build();
    }

    /**
     * Del em dois cliques da coluna de copias, igual aos outros menus (07/10/2026).
     * Nao entra no Reverter: apagou no servidor, e o undo deste editor so cobre o
     * que ainda nao foi salvo.
     */
    private void onCopyDelete(int index) {
        List<ThreatSheet> copies = copiesList();
        if (index < 0 || index >= copies.size()) {
            pendingCopyDelete = -1;
            rebuildCopiesColumn();
            return;
        }
        if (pendingCopyDelete != index) {
            pendingCopyDelete = index;
            rebuildCopiesColumn();
            return;
        }
        pendingCopyDelete = -1;
        ThreatSheet copia = copies.get(index);
        if (this.minecraft == null || this.minecraft.getConnection() == null) {
            return;
        }
        ClientPlayNetworking.send(new RpgNetworking.ThreatSheetDeletePayload(
                copia.identity().name()));
    }

    /** Abre outra ficha da coluna, com esta tela como pai (o Voltar volta para ca). */
    private void openSheet(ThreatSheet target) {
        if (target == null || this.minecraft == null) {
            return;
        }
        this.minecraft.setScreen(new ThreatSheetScreen(this, target, target.identity().name()));
    }

    /** Pede ao servidor uma copia da ficha aberta (so maes com id, salvas, tem o botao). */
    private void copyThis() {
        if (sheet == null || sheet.isCopy() || !sheet.hasId() || dirty) {
            return; // edicao nao salva: o button Copiar ja desliga, isto e a segunda guarda
        }
        if (this.minecraft == null || this.minecraft.getConnection() == null) {
            return;
        }
        ClientPlayNetworking.send(new RpgNetworking.ThreatSheetCopyPayload(sheet.identity().name()));
        status = "Copiando...";
        statusColor = COL_MUTED;
    }

    /**
     * Textos da coluna que nao sao widgets: o rotulo "Copias" sobre o botao Copiar,
     * e a FICHA ABERTA (quando ela e copia) como linha apagada na lista.
     */
    private void drawCopiesColumnTexts(GuiGraphics graphics) {
        if (!copiesVisible || !copiesExpanded || sheet == null) {
            return;
        }
        List<ThreatSheet> copies = copiesList();
        for (int i = copiesRegion.offset; i < copiesRegion.lastIndex() && i < copies.size(); i++) {
            ThreatSheet copia = copies.get(i);
            if (copia.hasId() && copia.id().equals(sheet.id())) {
                graphics.drawString(this.font,
                        this.font.plainSubstrByWidth(copia.identity().name(), COPIES_W - COPIES_DEL_W - 9),
                        copiesX + 3, copiesRegion.rowY(i) + 5, COL_MUTED, false);
            }
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
        if (copiesVisible) {
            // A area do cabecalho (titulo da mae / botao Copiar) fica ACIMA da
            // regiao de linhas; a regiao so pinta o fundo das linhas.
            graphics.fill(copiesX - 2, contentTop,
                    copiesX + COPIES_W - BAR_W - 2, copiesRowsTop, COL_LIST_BG);
        }
    }

    /** Titulo da tela: acompanha o nome GRAVADO da ficha -- apos salvar com outro
     *  nome o {@link #sheet} e trocado e o titulo muda junto (07/10/2026). */
    private Component currentTitle() {
        if (sheet != null && !sheet.identity().name().isEmpty()) {
            return Component.literal(sheet.listLabel());
        }
        return this.title;
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

        graphics.drawCenteredString(this.font, currentTitle(),
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

        drawCopiesColumnTexts(graphics);

        if (!status.isEmpty()) {
            graphics.drawCenteredString(this.font, this.font.plainSubstrByWidth(status,
                    panelW - 2 * PAD), panelX + panelW / 2, statusY, statusColor);
        }

        // Cauda do quadro: sessao de digitacao, bolinha, estado dos botoes e o
        // "antes" que o proximo input usa (07/10/2026).
        finishFrame(graphics);
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
        if (this.minecraft == null) {
            return;
        }
        if (this.sheet != null && this.sheet.isCopy()) {
            // 07/10/2026: Voltar de UMA COPIA abre a MAE (parente certo = a lista),
            // e nao a pilha de telas -- o Voltar e de um nivel so.
            ThreatSheet mother = copiesMother();
            if (mother != null) {
                this.minecraft.setScreen(new ThreatSheetScreen(backParent(), mother,
                        mother.identity().name(), this.updateMode));
                return;
            }
        }
        // Ficha mae: volta para a lista. O backParent pula telas de ficha na
        // cadeia, entao editor aberto por editor nao vira degrau do Voltar.
        this.minecraft.setScreen(backParent());
    }

    /**
     * O pai certo do Voltar: o primeiro pai da cadeia que NAO e um editor de
     * ficha. A cadeia editor -&gt; editor -&gt; ... termina na lista (07/10/2026).
     */
    private Screen backParent() {
        Screen p = this.parentScreen;
        while (p instanceof ThreatSheetScreen editor) {
            p = editor.parentScreen;
        }
        return p;
    }
}