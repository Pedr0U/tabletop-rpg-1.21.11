package com.pedro.tabletoprpg.client;

import com.pedro.tabletoprpg.RpgNetworking;
import com.pedro.tabletoprpg.SheetData;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Base das telas de ficha do personagem (FASE 3b).
 *
 * <p>Subclasses: {@link StatusScreen} (Status) e {@link SkillsScreen} (Skills).
 * A base concentrationa o que as duas compartilham: fundo escuro, painel
 * responsivo, sincronia com o servidor, campos editaveis e o botao Back.
 *
 * <p><b>Feedback do usuario que motivou esta reescrita:</b>
 * <ul>
 *   <li>a ficha nao tinha fundo e as letras eram cinzas/pequenas;</li>
 *   <li>nao cabia numa janela 1920x1080 (layout fixo, botoes fora da tela);</li>
 *   <li>nao dava para editar os campos (a caixa nascia com
 *       {@code setEditable(false)} porque {@code canEdit} ainda era false no
 *       primeiro {@code init()}, e sem fundo/contraste o campo parecia texto
 *       estatico). Aqui o estado de edicao fica <b>explicito</b> (rotulo
 *       "Editable"/"Read-only") e o texto da caixa tem contraste alto.</li>
 * </ul>
 *
 * <p><b>Autoridade do servidor:</b> toda edicao vai como payload e o valor so
 * entra na tela quando o servidor devolve a ficha ({@link #onSheetState}).
 * As setas das barras usam {@link #pendingNumeric} para nao perder cliques
 * rapidos: o valor exibido e otimista, e e substituido pelo do servidor assim
 * que ele chegar.
 */
public abstract class CharacterSheetScreen extends Screen {

    // ------------------------------------------------------------------
    // CONSTANTES DE LAYOUT (FASE 3b: responsivo)
    // ------------------------------------------------------------------

    /** Margem lateral minima do painel. */
    protected static final int PAD = 8;
    /** Altura de linha: encolhe em janelas baixas, nunca cresce demais. */
    protected static final int MIN_ROW_H = 12;
    protected static final int MAX_ROW_H = 20;
    /** Largura do painel: acompanha a janela, com teto. */
    protected static final int MIN_PANEL_W = 200;
    protected static final int MAX_PANEL_W = 360;
    /** Lado dos botoes de seta. */
    protected static final int ARROW_SIZE = 20;
    /**
     * <b>DECISAO PROVISORIA:</b> quanto cada clique da seta muda o valor.
     * O usuario nao especificou; 1 por clique foi a escolha inicial e esta
     * constante existe para ser trocada num so lugar.
     */
    public static final int ARROW_STEP = 1;

    // ------------------------------------------------------------------
    // CORES (painel escuro simples, feedback do usuario)
    // ------------------------------------------------------------------

    protected static final int COL_SCREEN_BG = 0xB0000000;
    protected static final int COL_PANEL_BG = 0xF216161C;
    protected static final int COL_TITLE = 0xFFFFFFFF;
    protected static final int COL_LABEL = 0xFFE6E6EE;
    protected static final int COL_SECTION = 0xFFFFC44D;
    protected static final int COL_MUTED = 0xFFA8A8B8;
    protected static final int COL_EDITABLE = 0xFF7FD48A;
    protected static final int COL_READONLY = 0xFFFF9A6B;
    protected static final int COL_BOX_TEXT = 0xFFFFFFFF;
    protected static final int COL_BOX_TEXT_OFF = 0xFF90909E;
    protected static final int COL_HP = 0xFFD43B3B;
    protected static final int COL_HP_BG = 0xFF431C1C;
    /**
     * Parte "temporaria" do HP (o que passa do maximo, ex.: 12/10). Mais clara
     * que {@link #COL_HP} para o jogador distinguir na hora o que e
     * permanente e o que e excedente.
     */
    protected static final int COL_HP_OVER = 0xFFFF8A8A;
    protected static final int COL_MANA = 0xFF3B7BD4;
    protected static final int COL_MANA_BG = 0xFF1B2A44;
    /** Parte "temporaria" da Mana (mesma ideia do {@link #COL_HP_OVER}). */
    protected static final int COL_MANA_OVER = 0xFF8AC4FF;
    protected static final int COL_BAR_EDGE = 0xFF55555F;
    protected static final int COL_DOWNED = 0xFFFF6060;

    /** Um texto a desenhar no proximo {@code render} (montado em init). */
    protected record TextLine(String text, int x, int y, int color) {
    }

    /** Geometria de uma barra, calculada em init() e desenhada em render(). */
    protected record Bar(int x, int y, int w, int h) {
    }

    // ------------------------------------------------------------------
    // ESTADO
    // ------------------------------------------------------------------

    /** Nome do dono da ficha. Vazio = a propria ficha. */
    protected final String targetName;
    /** Tela para onde o Back volta. */
    private final Screen returnTo;

    /** Estado atual vindo do servidor; null enquanto nao chegou resposta. */
    protected SheetData sheet;
    /** Permissao de edicao decidida pelo SERVIDOR (a UI so reflete). */
    protected boolean canEdit;

    /** Caixas por campo, para atualizar valores sem recriar a tela. */
    protected final Map<String, EditBox> fieldBoxes = new LinkedHashMap<>();
    /** Textos a desenhar em render() (rotulos e titulos de secao). */
    protected final List<TextLine> textLines = new ArrayList<>();

    /**
     * Valores otimistas das barras (HP/Mana). Clique rapido antes da resposta
     * do servidor geraria o mesmo valor duas vezes sem isto.
     */
    private final Map<String, Integer> pendingNumeric = new HashMap<>();

    /**
     * Quando true, alteracoes feitas pelos responders nao voltam ao servidor.
     * E o que impede o laco de eco: servidor devolve a ficha -> a tela preenche
     * as caixas -> setValue dispara o responder -> o cliente reenvia a edicao.
     */
    private boolean suppressNotify;

    // ------------------------------------------------------------------
    // GEOMETRIA (preenchida em init(), usada no render)
    // ------------------------------------------------------------------

    protected int panelX;
    protected int panelW;
    protected int rowH;
    protected int contentTop;
    protected int contentBottom;

    protected CharacterSheetScreen(String title, String targetName, Screen returnTo) {
        super(Component.literal(title));
        this.targetName = targetName == null ? "" : targetName;
        this.returnTo = returnTo;
    }

    /** true quando esta tela mostra a ficha do jogador local. */
    public boolean isOwnSheet() {
        return targetName.isEmpty();
    }

    /** Nome do dono da ficha exibida ("" = a local, ainda nao resolvida). */
    public String targetName() {
        return targetName;
    }

    // ------------------------------------------------------------------
    // SINCRONIA COM O SERVIDOR
    // ------------------------------------------------------------------

    /**
     * Chamado pelo receptor S2C quando o servidor devolve uma ficha. So
     * reaplica se for a ficha desta tela.
     *
     * <p>O filtro por {@code ownerUuid} importa no caso da ficha do proprio
     * jogador: como o mestre recebe tambem as fichas de todos os outros, uma
     * tela "minha ficha" sem este filtro exibiria a ficha de outro jogador
     * quando outro jogador edita o dele.
     */
    public void onSheetState(RpgNetworking.SheetStatePayload payload) {
        if (payload == null) {
            return;
        }
        if (targetName.isEmpty()) {
            var localPlayer = Minecraft.getInstance().player;
            if (localPlayer == null
                    || !localPlayer.getUUID().toString().equalsIgnoreCase(payload.ownerUuid())) {
                return;
            }
        } else if (!targetName.equalsIgnoreCase(payload.targetName())) {
            return;
        }
        this.sheet = payload.sheet();
        this.canEdit = payload.canEdit();
        // O servidor falou: o valor autoritativo substitui o otimista.
        pendingNumeric.clear();
        onSheetReceived();
        applySheetToWidgets();
    }

    /**
     * Chamado <b>somente</b> quando uma ficha chega do servidor (e nao em cada
     * {@code init()}).
     *
     * <p><b>Por que o gancho existe:</b> a tela limpa o estado otimista
     * quando o servidor responde, para nao ficar um valor "fantasma" se a
     * operacao for recusada. Mas {@code applyExtraState()} tambem roda no fim
     * do {@code init()}, e abrir/fechar uma tela filha (o dropdown de atributo)
     * provoca um {@code init()} <b>no mesmo instante</b> em que o otimismo foi
     * criado — o usuario veria o valor antigo ate a ficha voltar. Por isso o
     * "o servidor respondeu" e separado do "reconstrui os widgets".
     */
    protected void onSheetReceived() {
    }

    /**
     * Copia o estado do servidor para as caixas com a notificacao desligada
     * (para nao gerar payload de volta).
     */
    protected void applySheetToWidgets() {
        if (sheet == null) {
            return;
        }
        suppressNotify = true;
        try {
            for (Map.Entry<String, EditBox> entry : fieldBoxes.entrySet()) {
                String field = entry.getKey();
                EditBox box = entry.getValue();
                // <b>Nao sobrescreve o campo com foco.</b> Era esta linha a
                // causa do "apago o 3 e o 3 volta": o usuario digita, o
                // servidor responde com a ficha (o valor ainda e o antigo, ou
                // ja foi limitado) e a resposta escrevia por cima do texto
                // parcial, brigando com o cursor. Quem esta digitando manda no
                // proprio campo; o servidor so preenche os outros.
                if (box.isFocused()) {
                    applyEditable(box);
                    continue;
                }
                String next = SheetData.TEXT_FIELDS.contains(field)
                        ? sheet.getText(field)
                        : Integer.toString(sheet.getNumeric(field));
                // So escreve se mudou: evita reposicionar o cursor a cada tecla
                // digitada pelo proprio usuario.
                if (!next.equals(box.getValue())) {
                    box.setValue(next);
                }
                applyEditable(box);
            }
            applyExtraState();
        } finally {
            suppressNotify = false;
        }
    }

    /** Aplica {@code canEdit} numa caixa (usado tambem quando chega a ficha). */
    protected void applyEditable(EditBox box) {
        if (box == null) {
            return;
        }
        box.setEditable(canEdit);
    }

    /** Gancho para as subclasses ajustarem seus proprios widgets. */
    protected void applyExtraState() {
    }

    /**
     * Muda um numerico em {@code delta} e avisa o servidor.
     *
     * <p>O valor exibido e otimista ({@link #numericValue}) para que cliques
     * rapidos nao se percam; o servidor reenvia a ficha e o valor autoritativo
     * assume.
     */
    protected void stepNumeric(String field, int delta) {
        if (!canEdit || sheet == null) {
            return;
        }
        int base = pendingNumeric.containsKey(field)
                ? pendingNumeric.get(field)
                : sheet.getNumeric(field);
        int next = saturatingAdd(base, delta);
        pendingNumeric.put(field, next);
        ClientPlayNetworking.send(new RpgNetworking.SheetFieldPayload(
                targetName, field, Integer.toString(next)));
    }

    /** Valor a exibir: o otimista se houver, senao o do servidor. */
    protected int numericValue(String field) {
        Integer pending = pendingNumeric.get(field);
        if (pending != null) {
            return pending;
        }
        return sheet == null ? 0 : sheet.getNumeric(field);
    }

    /**
     * Soma que trava no limite do {@code int} em vez de dar a volta.
     *
     * <p><b>Por que importa aqui:</b> os atributos nao tem teto (decisao do
     * usuario). Com {@code base + delta} simples, um atributo em
     * {@code Integer.MAX_VALUE} mais um clique viraria {@code MIN_VALUE} e esse
     * numero negativo seria gravado na ficha do servidor.
     */
    private static int saturatingAdd(int base, int delta) {
        long sum = (long) base + delta;
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, sum));
    }

    // ------------------------------------------------------------------
    // LAYOUT
    // ------------------------------------------------------------------

    @Override
    protected void init() {
        super.init();

        // init() pode ser chamado de novo (resize): recomeca o layout limpo.
        textLines.clear();
        fieldBoxes.clear();

        // Painel centralizado que acompanha a janela (feedback: nao cabia em
        // 1920x1080). O teto evita um painel gigante em telas enormes.
        panelW = Math.max(MIN_PANEL_W, Math.min(this.width - 2 * PAD, MAX_PANEL_W));
        if (panelW > this.width) {
            panelW = this.width;
        }
        panelX = (this.width - panelW) / 2;
        contentTop = 40;
        contentBottom = this.height - 30;

        buildPanel(panelX, panelW, contentTop, contentBottom);

        // Back: posicao fixa na base do painel (feedback: botao fora da tela
        // em janelas baixas). Se a tela quiser um botao extra no rodape (ex.:
        // Status -> Skills ao ver a ficha de OUTRO jogador), o Back encolhe.
        int backY = this.height - 24;
        Button extra = buildFooterExtra(panelX, backY, panelW, 20);
        if (extra != null) {
            addRenderableWidget(extra);
            addRenderableWidget(Button.builder(Component.literal("Back"), b -> close())
                    .bounds(panelX, backY, Math.max(60, panelW - 72), 20)
                    .build());
        } else {
            addRenderableWidget(Button.builder(Component.literal("Back"), b -> close())
                    .bounds(panelX, backY, panelW, 20)
                    .build());
        }

        // Preenche com o estado atual, se ja tiver chegado do servidor.
        applySheetToWidgets();
    }

    /**
     * Botao extra no rodape, a direita do Back. Padrao: nenhum (so o Back).
     * Quem implementa posiciona o botao dentro de {@code (x, y, w, h)}.
     */
    protected Button buildFooterExtra(int x, int y, int w, int h) {
        return null;
    }

    /**
     * Monta o conteudo da tela. As subclasses usam {@link #rowH},
     * {@link #contentTop} e {@link #contentBottom} (ja calculados) e devolvem
     * nada: {@code init()} cuida do Back e do preenchimento final.
     */
    protected abstract void buildPanel(int x0, int panelW, int topY, int bottomY);

    /** Altura de linha adaptativa: encolhe para caber na janela atual. */
    protected int fitRowHeight(int neededRows, int topY, int bottomY) {
        int available = Math.max(MIN_ROW_H, bottomY - topY);
        return Math.max(MIN_ROW_H, Math.min(MAX_ROW_H, available / Math.max(1, neededRows)));
    }

    /** Deslocamento vertical do rotulo para ficar centralizado na linha. */
    protected int labelOffset() {
        return Math.max(3, (rowH - 8) / 2);
    }

    /** Registra um titulo de secao e devolve o proximo Y. */
    protected int addSection(String title, int x0, int y) {
        textLines.add(new TextLine(title, x0, y + labelOffset(), COL_SECTION));
        return y + rowH;
    }

    /** Registra o rotulo de um campo, cria a caixa e devolve o proximo Y. */
    protected int addField(String field, int x0, int y, int boxX, int boxW, boolean numeric) {
        textLines.add(new TextLine(SheetData.labelOf(field), x0, y + labelOffset(), COL_LABEL));
        fieldBoxes.put(field, createFieldBox(field, boxX, y, boxW, numeric));
        return y + rowH;
    }

    /**
     * Cria uma caixa de edicao ligada ao servidor.
     *
     * <p>O contraste e o que faltava para o usuario perceber que o campo e
     * editavel: texto branco quando editavel, cinza apagado quando nao, e a
     * borda de foco nativa do vanilla quando o campo ganha foco.
     */
    protected EditBox createFieldBox(String field, int x, int y, int w, boolean numeric) {
        EditBox box = new EditBox(this.font, x, y, w, rowH - 2,
                Component.literal(SheetData.labelOf(field)));
        if (numeric) {
            // Numeros com sinal: o "-" precisa passar, senao nao existe como
            // digitar valor negativo (os atributos vao para as setas, mas
            // nivel/xp e qualquer campo futuro continuam por aqui).
            // O limite de 9 digitos mantem o numero longe do overflow.
            box.setFilter(s -> s.matches("-?\\d{0,9}"));
            box.setMaxLength(10);
        } else {
            box.setMaxLength(SheetData.MAX_NAME);
        }
        box.setTextColor(COL_BOX_TEXT);
        box.setTextColorUneditable(COL_BOX_TEXT_OFF);
        box.setHint(Component.literal("click to edit"));
        applyEditable(box);
        box.setResponder(value -> {
            if (suppressNotify || !canEdit) {
                return;
            }
            // <b>Nao envia texto numerico incompleto.</b> Cada tecla dispara o
            // responder; ao apagar tudo, o servidor cairia no fallback (mantem
            // o valor antigo), reenviaria a ficha e o eco apagaria de novo o
            // campo. Sem envio nao ha eco, e a digitacao fica possivel.
            if (numeric && !isCompleteNumber(value)) {
                return;
            }
            ClientPlayNetworking.send(new RpgNetworking.SheetFieldPayload(
                    targetName, field, value));
        });
        // **OBRIGATORIO**: sem este registro a caixa nao entra em
        // Screen.children() e portanto nao recebe clique, nem foco, nem
        // teclado -- e nem e desenhada. (Causa raiz do "nao consigo editar
        // nenhum campo": a FASE 3 criava a caixa e so a guardava no mapa.)
        addRenderableWidget(box);
        return box;
    }

    /**
     * O texto e um numero completo (nao vazio, nao so o sinal)?
     *
     * <p>Usado para nao enviar valores numericos pela metade. Texto livre
     * (nome, raca, classe) nao passa por aqui: envio a cada tecla e o
     * comportamento esperado.
     */
    private static boolean isCompleteNumber(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        String digits = value.startsWith("-") ? value.substring(1) : value;
        return !digits.isEmpty() && digits.chars().allMatch(Character::isDigit);
    }

    // ------------------------------------------------------------------
    // DESENHO DAS BARRAS (usado pelo Status)
    // ------------------------------------------------------------------

    /** Desenha a moldura de uma barra. */
    protected void drawBar(GuiGraphics graphics, Bar bar, int fillColor, int bgColor, double fraction) {
        graphics.fill(bar.x(), bar.y(), bar.x() + bar.w(), bar.y() + bar.h(), bgColor);
        int fillW = (int) Math.round(bar.w() * Math.max(0.0, Math.min(1.0, fraction)));
        if (fillW > 0) {
            graphics.fill(bar.x(), bar.y(), bar.x() + fillW, bar.y() + bar.h(), fillColor);
        }
        graphics.fill(bar.x(), bar.y(), bar.x() + bar.w(), bar.y() + 1, COL_BAR_EDGE);
        graphics.fill(bar.x(), bar.y() + bar.h() - 1, bar.x() + bar.w(), bar.y() + bar.h(), COL_BAR_EDGE);
    }

    /**
     * Barra em duas partes: a normal (ate o maximo) e o excedente, pintado
     * com outra cor logo em seguida.
     *
     * <p>As duas fracoes usam o MESMO denominador (o chamador escolhe), e somam
     * no maximo 1.0. Com {@code normalFraction=1} e
     * {@code overflowFraction=0} o resultado e identico a {@link #drawBar}.
     */
    protected void drawBarSplit(GuiGraphics graphics, Bar bar,
                                int fillColor, int overflowColor, int bgColor,
                                double normalFraction, double overflowFraction) {
        graphics.fill(bar.x(), bar.y(), bar.x() + bar.w(), bar.y() + bar.h(), bgColor);
        double normal = Math.max(0.0, Math.min(1.0, normalFraction));
        int normalW = (int) Math.round(bar.w() * normal);
        if (normalW > 0) {
            graphics.fill(bar.x(), bar.y(), bar.x() + normalW, bar.y() + bar.h(), fillColor);
        }
        // Clamp em 1.0 - normal: a soma nunca passa da largura da barra. O
        // segundo Math.min existe porque dois arredondamentos independentes
        // podem somar 1 pixel a mais (ex.: normal=0.833 e overflow=0.167 em
        // uma barra de 149px) e o fill passaria da moldura.
        double overflow = Math.max(0.0, Math.min(1.0 - normal, overflowFraction));
        int overflowW = Math.min(bar.w() - normalW, (int) Math.round(bar.w() * overflow));
        if (overflowW > 0) {
            graphics.fill(bar.x() + normalW, bar.y(),
                    bar.x() + normalW + overflowW, bar.y() + bar.h(), overflowColor);
        }
        graphics.fill(bar.x(), bar.y(), bar.x() + bar.w(), bar.y() + 1, COL_BAR_EDGE);
        graphics.fill(bar.x(), bar.y() + bar.h() - 1, bar.x() + bar.w(), bar.y() + bar.h(), COL_BAR_EDGE);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        // super.render() desenha o fundo (renderBackground) e os widgets.
        super.render(graphics, mouseX, mouseY, delta);

        String title = titleText();
        graphics.drawString(this.font, title,
                (this.width - this.font.width(title)) / 2, 8, COL_TITLE, false);

        // Estado de edicao explicito: responde a duvida "da para editar?".
        String state = canEdit ? "Editable" : "Read-only";
        graphics.drawString(this.font, state,
                panelX + panelW - this.font.width(state) - 4, 24,
                canEdit ? COL_EDITABLE : COL_READONLY, false);

        // Faixa superior esquerda: avisos das subclasses. Fica ACIMA de
        // contentTop de proposito -- antes o aviso de "DOWNED" e o contador de
        // skills eram desenhados perto da base e colidiam com a ultima linha de
        // widgets em janelas baixas.
        renderTopLeft(graphics);

        if (sheet == null) {
            graphics.drawString(this.font, "Loading...",
                    (this.width - this.font.width("Loading...")) / 2, 48, COL_MUTED, false);
            return;
        }

        for (TextLine line : textLines) {
            graphics.drawString(this.font, line.text(), line.x(), line.y(), line.color(), false);
        }

        renderContent(graphics, mouseX, mouseY);
    }

    /** Titulo da tela (quem e o dono da ficha). */
    protected String titleText() {
        return "Sheet: " + (targetName.isEmpty() ? "me" : targetName);
    }

    /**
     * Desenha o conteudo que nao e widget (barras, avisos, tooltips).
     *
     * <p>Recebe as coordenadas do mouse porque o hover e parte do desenho: a
     * tela de Skills abre o tooltip da descricao da skill quando o cursor esta
     * sobre o botao, e isso so pode ser decidido no render.
     */
    protected void renderContent(GuiGraphics graphics, int mouseX, int mouseY) {
    }

    /**
     * Desenha avisos no canto superior esquerdo do painel (y = 24), na mesma
     * faixa do rotulo de edicao. Vazio por padrao.
     */
    protected void renderTopLeft(GuiGraphics graphics) {
    }

    // ------------------------------------------------------------------
    // FECHAMENTO
    // ------------------------------------------------------------------

    protected void close() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(returnTo);
        }
    }

    /** Tela para onde o botao Back volta. */
    protected Screen returnScreen() {
        return returnTo;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Fundo escuro simples (feedback do usuario: nada de pergaminho).
        graphics.fill(0, 0, this.width, this.height, COL_SCREEN_BG);
        // Painel da ficha, com margem para o titulo e o botao Back.
        graphics.fill(panelX - PAD, 20, panelX + panelW + PAD, this.height - 28, COL_PANEL_BG);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
