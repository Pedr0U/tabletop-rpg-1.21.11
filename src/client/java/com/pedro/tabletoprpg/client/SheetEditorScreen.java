package com.pedro.tabletoprpg.client;

import com.pedro.tabletoprpg.RpgNetworking;
import com.pedro.tabletoprpg.SheetModel;
import com.pedro.tabletoprpg.SheetModelHolder;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Tela do Sheet Editor, aberta pelo item {@code Sheet Editor}.
 *
 * <p>27/09/2026. O Mestre edita aqui o <b>molde</b> da ficha: os rotulos dos
 * campos, o que aparece, o modo do XP, e as listas de atributos e pericias. Os
 * <b>valores</b> continuam sendo de cada jogador e sao preservados: o que muda
 * aqui e o nome das coisas, nao o que o jogador preencheu.
 *
 * <p><b>Estrutura (decisao do usuario, 27/09/2026):</b> uma coluna so, com tudo
 * rolando junto. Uma coluna e mais facil de acertar do que duas areas de rolagem,
 * e o conteudo cabe em uma lista unica e previsivel.
 *
 * <p><b>Edicao em memoria, gravacao no botao Salvar (decisao do usuario):</b> a
 * tela mexe numa copia ({@code staged}) e so envia quando o Mestre aperta Salvar.
 * Descartar joga fora a copia e volta ao que o servidor tem. O preco e que nao
 * existe "desfazer" para uma edicao ja gravada; o preco da alternativa (gravar a
 * cada tecla) seria reescrever o modelo do mundo a cada tecla.
 *
 * <p><b>So o Mestre chega aqui:</b> o servidor so envia o pacote de abrir depois
 * de {@code SessionManager.isMaster}. Esta tela nao refaz a checagem de proposito.
 * Se ela aparecesse para um jogador comum, o problema seria de permissao no
 * servidor, e nao de UI; revezar a checagem aqui esconderia o bug.
 *
 * <p><b>O cliente nao e autoridade.</b> A tela manda o modelo inteiro, mas o
 * servidor reconstroi o {@code SheetModel} na desserializacao, o que dispara o
 * construtor compacto e saneia rotulos, duplicatas e os tetos de 10/30. Um
 * pacote forjado chega limpo, e o servidor ainda exige Mestre antes de gravar.
 */
public class SheetEditorScreen extends Screen {

    // ------------------------------------------------------------------
    // LAYOUT
    // ------------------------------------------------------------------

    private static final int TITLE_Y = 10;
    private static final int CONTENT_TOP = 28;
    private static final int FOOTER_H = 26;
    /** Largura maxima da coluna; acima disso a linha fica desconfortavel de ler. */
    private static final int MAX_COL_W = 460;
    private static final int COL_PADDING = 12;
    /** Altura de uma linha de controle. */
    private static final int ROW_H = 20;
    /** Altura de um cabecalho de secao. */
    private static final int HEADER_H = 24;
    /** Espaco reservado ao texto do campo, a esquerda do controle. */
    private static final int CAP_W = 104;
    private static final int GAP = 4;
    private static final int BTN_W = 24;
    /** Largura do botao que cicla o atributo padrao de uma pericia. */
    private static final int ATTR_BTN_W = 56;

    private static final int COL_TITLE = 0xFFFFFFFF;
    private static final int COL_HEADER = 0xFFFFC44D;
    private static final int COL_CAPTION = 0xFFB8B8C4;
    private static final int COL_HINT = 0xFF8A8A94;

    // ------------------------------------------------------------------
    // ESTADO
    // ------------------------------------------------------------------

    /** A copia em edicao. Nunca null. */
    private SheetModel staged = SheetModelHolder.current();
    /** O que o servidor tinha quando a tela montou, ou o ultimo que foi salvo. */
    private SheetModel baseline = SheetModelHolder.current();

    private int scrollPx;

    /**
     * Impede o eco ao reescrever o texto de uma caixa por codigo.
     *
     * <p>Precisa porque {@code setValue} dispara o responder, e sem esta guarda a
     * correcao do texto (ver {@link #periciaRow}) chamaria o modelo de novo, que
     * recusaria de novo, e o resultado seria um laco.
     */
    private boolean suppressNotify;

    /** Controles de conteudo, com a posicao que teriam sem rolagem. */
    private final List<Slot> slots = new ArrayList<>();
    /** Textos desenhados a mao (cabecalhos e contadores), tambem sem rolagem. */
    private final List<Caption> captions = new ArrayList<>();
    private int contentHeight;

    private record Slot(AbstractWidget widget, int layoutY) {
    }

    private record Caption(int layoutY, Component text, int color) {
    }

    public SheetEditorScreen() {
        super(tr("title"));
    }

    // ------------------------------------------------------------------
    // GEOMETRIA DA COLUNA
    // ------------------------------------------------------------------

    private int colX() {
        return (this.width - colW()) / 2;
    }

    private int colW() {
        return Math.max(260, Math.min(MAX_COL_W, this.width - 2 * COL_PADDING));
    }

    /** X onde comecam os controles (a direita do texto do campo). */
    private int ctrlX() {
        return colX() + CAP_W + GAP;
    }

    /** Largura util dos controles, ate a borda direita da coluna. */
    private int ctrlW() {
        return colW() - CAP_W - GAP;
    }

    private int contentBottom() {
        return this.height - FOOTER_H;
    }

    // ------------------------------------------------------------------
    // MONTAGEM
    // ------------------------------------------------------------------

    @Override
    protected void init() {
        super.init();
        slots.clear();
        captions.clear();
        buildContent();
        buildFooter();
        applyScroll();
    }

    /** Constroi a coluna unica, na ordem em que a ficha desenha os campos. */
    private void buildContent() {
        int x = ctrlX();
        int w = ctrlW();
        int y = 0;

        y = header(y, "fields");
        y = labelField(y, "field_name", staged.nameLabel(),
                v -> staged = staged.withLabel("characterName", v));
        y = labelField(y, "field_race", staged.raceLabel(),
                v -> staged = staged.withLabel("race", v));
        y = toggleField(y, "field_race", staged.raceEnabled(),
                v -> staged = staged.withEnabled("race", v));
        y = labelField(y, "field_class", staged.classLabel(),
                v -> staged = staged.withLabel("characterClass", v));
        y = labelField(y, "field_background", staged.backgroundLabel(),
                v -> staged = staged.withLabel("background", v));
        y = labelField(y, "field_hp", staged.hpLabel(),
                v -> staged = staged.withLabel("hp", v));
        y = labelField(y, "field_mana", staged.manaLabel(),
                v -> staged = staged.withLabel("mana", v));
        y = toggleField(y, "field_mana", staged.manaEnabled(),
                v -> staged = staged.withEnabled("mana", v));
        y = labelField(y, "field_level", staged.levelLabel(),
                v -> staged = staged.withLabel("level", v));
        y = labelField(y, "field_xp", staged.xpLabel(),
                v -> staged = staged.withLabel("xp", v));
        y = xpModeField(y);

        y = header(y, "attributes");
        for (SheetModel.AttributeDef def : staged.attributes()) {
            y = attributeRow(y, def);
        }
        y = addButton(y, "add_attribute", staged.attributeCount() < SheetModel.MAX_ATTRIBUTES,
                staged.attributeCount(), SheetModel.MAX_ATTRIBUTES,
                () -> {
                    staged = staged.addAttribute();
                    rebuildWidgets();
                });

        y = header(y, "pericias");
        for (SheetModel.PericiaDef def : staged.pericias()) {
            y = periciaRow(y, def);
        }
        y = addButton(y, "add_pericia", staged.periciaCount() < SheetModel.MAX_PERICIAS,
                staged.periciaCount(), SheetModel.MAX_PERICIAS,
                () -> {
                    staged = staged.addPericia();
                    rebuildWidgets();
                });

        contentHeight = y;
    }

    private int header(int y, String key) {
        captions.add(new Caption(y + 6, tr(key), COL_HEADER));
        return y + HEADER_H;
    }

    /** Rotulo de um campo de sistema: texto a esquerda, caixa a direita. */
    private int labelField(int y, String key, String current, Consumer<String> apply) {
        captions.add(new Caption(y + 6, tr(key), COL_CAPTION));
        EditBox box = new EditBox(this.font, ctrlX(), y, ctrlW(), ROW_H - 4, tr(key));
        box.setMaxLength(SheetModel.LABEL_MAX);
        box.setValue(current);
        box.setResponder(value -> {
            if (!suppressNotify) {
                apply.accept(value);
            }
        });
        addRenderableWidget(box);
        slots.add(new Slot(box, y));
        return y + ROW_H;
    }

    private int toggleField(int y, String key, boolean current, Consumer<Boolean> apply) {
        captions.add(new Caption(y + 6, tr(key), COL_CAPTION));
        Button button = Button.builder(toggleText(current), b -> {
            boolean next = !current;
            apply.accept(next);
            b.setMessage(toggleText(next));
        }).bounds(ctrlX(), y, 60, ROW_H - 4).build();
        addRenderableWidget(button);
        slots.add(new Slot(button, y));
        return y + ROW_H;
    }

    /** XP e um modo de tres estados, nao um interruptor de dois. */
    private int xpModeField(int y) {
        captions.add(new Caption(y + 6, tr("field_xp_mode"), COL_CAPTION));
        SheetModel.XpMode[] mode = {staged.xp()};
        Button button = Button.builder(xpModeText(mode[0]), b -> {
            mode[0] = mode[0] == SheetModel.XpMode.NUMBER
                    ? SheetModel.XpMode.TEXT
                    : mode[0] == SheetModel.XpMode.TEXT
                            ? SheetModel.XpMode.HIDDEN
                            : SheetModel.XpMode.NUMBER;
            staged = staged.withXpMode(mode[0]);
            b.setMessage(xpModeText(mode[0]));
        }).bounds(ctrlX(), y, ctrlW(), ROW_H - 4).build();
        addRenderableWidget(button);
        slots.add(new Slot(button, y));
        return y + ROW_H;
    }

    /**
     * Uma linha por atributo: sigla editavel, nome editavel e o botao de tirar.
     *
     * <p><b>O id nao e editavel, de proposito.</b> O id e a identidade do
     * atributo: e por ele que o valor guardado na ficha do jogador e reencontrado.
     * Se o Mestre pudesse trocar o id, renomear a sigla de um atributo apagaria o
     * valor dele em todas as fichas ja salvas. Os ids gerados sao {@code attr_1},
     * {@code attr_2} e assim por diante.
     */
    private int attributeRow(int y, SheetModel.AttributeDef def) {
        String id = def.id();
        int half = (ctrlW() - GAP - BTN_W - GAP) / 2;
        int rightX = colX() + colW() - BTN_W;

        EditBox label = new EditBox(this.font, ctrlX(), y, half, ROW_H - 4, tr("abbreviation"));
        label.setMaxLength(SheetModel.LABEL_MAX);
        label.setValue(def.label());
        label.setResponder(value -> {
            if (!suppressNotify) {
                // Le o nome de {@code staged} no momento da tecla, e nao a copia
                // congelada quando a linha foi montada: assim editar a sigla e o
                // nome na mesma sessao nao faz um sobrescrever o outro.
                SheetModel.AttributeDef live = staged.attribute(id);
                staged = staged.withAttributeText(id, value, live == null ? def.name() : live.name());
            }
        });

        EditBox name = new EditBox(this.font, ctrlX() + half + GAP, y, half, ROW_H - 4, tr("name"));
        name.setMaxLength(SheetModel.LABEL_MAX);
        name.setValue(def.name());
        name.setResponder(value -> {
            if (!suppressNotify) {
                SheetModel.AttributeDef live = staged.attribute(id);
                staged = staged.withAttributeText(id, live == null ? def.label() : live.label(), value);
            }
        });

        Button remove = Button.builder(Component.literal("X"), b -> {
            staged = staged.removeAttribute(id);
            rebuildWidgets();
        }).bounds(rightX, y, BTN_W, ROW_H - 4).build();
        remove.active = staged.attributeCount() > SheetModel.MIN_ATTRIBUTES;

        addRenderableWidget(label);
        addRenderableWidget(name);
        addRenderableWidget(remove);
        slots.add(new Slot(label, y));
        slots.add(new Slot(name, y));
        slots.add(new Slot(remove, y));
        return y + ROW_H;
    }

    /**
     * Uma linha por pericia: nome editavel, atributo padrao e o botao de tirar.
     *
     * <p><b>O nome recusa vazio e duplicado, e a caixa volta ao texto antigo.</b>
     * {@code withPericiaText} recusa essas duas entradas, porque duas pericias com
     * o mesmo nome fazem uma sobrescrever a outra em qualquer busca por nome.
     * Devolver o texto para a caixa evita que o usuario veja o nome novo na tela
     * enquanto o modelo por tras continua com o antigo -- e que o Salvar grave o
     * antigo sem ele perceber.
     */
    private int periciaRow(int y, SheetModel.PericiaDef def) {
        String originalName = def.name();
        int rightX = colX() + colW() - BTN_W;
        int nameW = ctrlW() - GAP - ATTR_BTN_W - GAP - BTN_W;

        EditBox name = new EditBox(this.font, ctrlX(), y, nameW, ROW_H - 4, tr("name"));
        name.setMaxLength(SheetModel.LABEL_MAX);
        name.setValue(originalName);
        name.setResponder(value -> {
            if (suppressNotify) {
                return;
            }
            SheetModel.PericiaDef live = staged.periciaByName(originalName);
            String current = live == null ? originalName : live.name();
            SheetModel next = staged.withPericiaText(current, value,
                    live == null ? def.attributeId() : live.attributeId());
            if (next == staged) {
                SheetModel.PericiaDef after = staged.periciaByName(current);
                suppressNotify = true;
                name.setValue(after == null ? originalName : after.name());
                suppressNotify = false;
                return;
            }
            staged = next;
        });

        Button attr = Button.builder(Component.literal(staged.attributeLabel(def.attributeId())), b -> {
            SheetModel.PericiaDef live = staged.periciaByName(originalName);
            if (live == null || staged.attributes().isEmpty()) {
                return;
            }
            int index = attributeIndexOf(live.attributeId());
            int next = ((index < 0 ? 0 : index) + 1) % staged.attributes().size();
            String id = staged.attributes().get(next).id();
            staged = staged.withPericiaText(live.name(), live.name(), id);
            b.setMessage(Component.literal(staged.attributeLabel(id)));
        }).bounds(ctrlX() + nameW + GAP, y, ATTR_BTN_W, ROW_H - 4).build();
        attr.active = attributeIndexOf(def.attributeId()) >= 0;

        Button remove = Button.builder(Component.literal("X"), b -> {
            staged = staged.removePericia(originalName);
            rebuildWidgets();
        }).bounds(rightX, y, BTN_W, ROW_H - 4).build();
        remove.active = staged.periciaCount() > SheetModel.MIN_PERICIAS;

        addRenderableWidget(name);
        addRenderableWidget(attr);
        addRenderableWidget(remove);
        slots.add(new Slot(name, y));
        slots.add(new Slot(attr, y));
        slots.add(new Slot(remove, y));
        return y + ROW_H;
    }

    private int addButton(int y, String key, boolean canAdd, int count, int max, Runnable action) {
        Button button = Button.builder(tr(key), b -> action.run())
                .bounds(ctrlX(), y, 120, ROW_H - 4).build();
        button.active = canAdd;
        addRenderableWidget(button);
        slots.add(new Slot(button, y));
        captions.add(new Caption(y + 6, tr("count", count, max), COL_HINT));
        return y + ROW_H;
    }

    private void buildFooter() {
        int y = this.height - FOOTER_H + 4;
        int bw = 100;
        int gap = 6;
        int x = (this.width - (4 * bw + 3 * gap)) / 2;

        addRenderableWidget(Button.builder(tr("save"), b -> save())
                .bounds(x, y, bw, ROW_H - 2).build());
        Button discard = addRenderableWidget(Button.builder(tr("discard"), b -> discard())
                .bounds(x + (bw + gap), y, bw, ROW_H - 2).build());
        Button reset = addRenderableWidget(Button.builder(tr("reset"), b -> {
            staged = SheetModel.defaults();
            rebuildWidgets();
        }).bounds(x + 2 * (bw + gap), y, bw, ROW_H - 2).build());
        addRenderableWidget(Button.builder(tr("close"), b -> onClose())
                .bounds(x + 3 * (bw + gap), y, bw, ROW_H - 2).build());

        // Descartar so faz sentido com algo para descartar.
        discard.active = isDirty();
        reset.active = !staged.equals(SheetModel.defaults());
    }

    // ------------------------------------------------------------------
    // ROLAGEM
    // ------------------------------------------------------------------

    private int maxScroll() {
        return Math.max(0, contentHeight - (contentBottom() - CONTENT_TOP));
    }

    /**
     * Reposiciona os controles conforme a rolagem e esconde os que ficaram fora.
     *
     * <p><b>Esconder em vez de recortar:</b> recortar exigiria scissor e entails
     * desenhar os filhos a mao, porque o {@code super.render()} do vanilla desenha
     * todos de uma vez, incluindo o rodape. Marcar {@code visible = false} usa o
     * caminho ja testado do vanilla e deixa o rodape sempre visivel.
     */
    private void applyScroll() {
        scrollPx = Math.max(0, Math.min(scrollPx, maxScroll()));
        int top = CONTENT_TOP;
        int bottom = contentBottom();
        for (Slot slot : slots) {
            int y = top + slot.layoutY() - scrollPx;
            slot.widget().setY(y);
            slot.widget().visible = y + ROW_H > top && y < bottom;
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseY >= CONTENT_TOP && mouseY < contentBottom() && maxScroll() > 0) {
            int before = scrollPx;
            scrollPx -= (int) Math.signum(scrollY) * ROW_H;
            if (scrollPx != before) {
                applyScroll();
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // ------------------------------------------------------------------
    // ACOES
    // ------------------------------------------------------------------

    private boolean isDirty() {
        return !staged.equals(baseline);
    }

    /**
     * Envia o modelo inteiro.
     *
     * <p>Depois de enviar, {@code baseline} passa a ser o que foi enviado: assim o
     * botao Descartar para de oferecer voltar a um estado que ja nao existe. O
     * modelo oficial so volta pelo payload que o servidor reenvia depois de
     * gravar.
     */
    private void save() {
        ClientPlayNetworking.send(new RpgNetworking.SheetModelSavePayload(staged));
        baseline = staged;
        rebuildWidgets();
    }

    private void discard() {
        staged = baseline;
        rebuildWidgets();
    }

    /**
     * O servidor trocou o modelo: ele gravou o nosso, ou outro Mestre editou.
     *
     * <p>Se nao ha nada em edicao, o que chegou substitui a copia. Se ha, a tela
     * <b>nao</b> sobrescreve, porque isso perderia o que o Mestre estava
     * digitando; o aviso de "nao salvo" continua e o proximo Salvar manda a copia
     * local por cima do modelo novo.
     */
    public void onModelChanged() {
        if (!isDirty()) {
            staged = SheetModelHolder.current();
            baseline = staged;
        }
        rebuildWidgets();
    }

    // ------------------------------------------------------------------
    // DESENHO
    // ------------------------------------------------------------------

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawCenteredString(this.font, this.title, this.width / 2, TITLE_Y, COL_TITLE);

        int top = CONTENT_TOP;
        int bottom = contentBottom();
        for (Caption caption : captions) {
            int y = top + caption.layoutY() - scrollPx;
            if (y + this.font.lineHeight < top || y > bottom) {
                continue;
            }
            graphics.drawString(this.font, caption.text(), colX(), y, caption.color(), false);
        }

        if (maxScroll() > 0) {
            graphics.drawString(this.font, tr("scroll_hint"), COL_PADDING, TITLE_Y + 2, COL_HINT, false);
        }
        if (isDirty()) {
            Component warn = tr("unsaved");
            graphics.drawString(this.font, warn, this.width - COL_PADDING - this.font.width(warn),
                    TITLE_Y, COL_HEADER, false);
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        int x = colX();
        graphics.fill(x - 4, CONTENT_TOP - 2, x + colW() + 4, contentBottom(), 0xC0000000);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(null);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    // ------------------------------------------------------------------
    // TEXTOS
    // ------------------------------------------------------------------

    private static Component tr(String key) {
        return Component.translatable("screen.tabletoprpg.sheet_editor." + key);
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable("screen.tabletoprpg.sheet_editor." + key, args);
    }

    private static Component toggleText(boolean on) {
        return tr(on ? "on" : "off");
    }

    private static Component xpModeText(SheetModel.XpMode mode) {
        return tr("xp_mode_" + mode.name().toLowerCase(Locale.ROOT));
    }

    private int attributeIndexOf(String attributeId) {
        for (int i = 0; i < staged.attributes().size(); i++) {
            if (staged.attributes().get(i).id().equals(attributeId)) {
                return i;
            }
        }
        return -1;
    }
}
