package com.pedro.tabletoprpg.client;

import com.pedro.tabletoprpg.RpgNetworking;
import com.pedro.tabletoprpg.SheetData;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
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
 * <p><b>Campos de texto livre:</b> alcance, alvo e duracao. A descricao e uma
 * caixa de tres linhas, com teto proprio ({@code SPELL_DESC_MAX}).
 *
 * <p><b>Custo so numeros:</b> a caixa aceita digitos e o servidor limpa o texto
 * antigo ("3 PM" vira "3") ao salvar ou carregar.
 *
 * <p><b>Por que o circulo e um botao e nao uma caixa:</b> e uma escolha entre
 * cinco valores, entao digitar eGUARDAR o numero seria trabalho sem ganho. O
 * botao cicla 1 -&gt; 2 -&gt; 3 -&gt; 4 -&gt; 5 e o rotulo mostra em extenso, que
 * e o que o jogador procura na tabela impressa. A execucao segue o mesmo padrao:
 * seis valores fixos, com "Acao Padrao" como ponto de partida de uma magia nova.
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

    /** Chaves de traducao das linhas do formulario, na ordem em que aparecem. */
    private static final String[] FIELD_LABEL_KEYS = {
            "screen.tabletoprpg.spell_form.execution",
            "screen.tabletoprpg.spell_form.range",
            "screen.tabletoprpg.spell_form.target",
            "screen.tabletoprpg.spell_form.duration",
            "screen.tabletoprpg.spell_form.cost",
            "screen.tabletoprpg.spell_form.description"};

    /** Chaves de traducao do rotulo de cada execucao, na ordem do ciclo. */
    private static final String[] EXECUTION_KEYS = {
            "screen.tabletoprpg.spell_form.execution.free",
            "screen.tabletoprpg.spell_form.execution.move",
            "screen.tabletoprpg.spell_form.execution.standard",
            "screen.tabletoprpg.spell_form.execution.full",
            "screen.tabletoprpg.spell_form.execution.bonus",
            "screen.tabletoprpg.spell_form.execution.reaction"};

    /**
     * IDs estaveis gravados na ficha, na MESMA ordem de {@link #EXECUTION_KEYS}.
     *
     * <p><b>Por que ID e nao o texto traduzido (17/11/2026):</b> gravar
     * {@code Component.translatable(...).getString()} congelava o idioma do
     * cliente no momento da edicao. Com o ID, o rotulo e traduzido so na exibicao
     * e a magia nao muda de significado se o idioma mudar.
     */
    private static final String[] EXECUTION_IDS = {
            "free", "move", "standard", "full", "bonus", "reaction"};

    /** Execucao de uma magia nova: "Acao Padrao" (indice 2 da lista). */
    private static final int EXECUTION_DEFAULT_INDEX = 2;

    /** Indice da linha de execucao, que tem botao em vez de caixa de texto. */
    private static final int EXECUTION_ROW = 0;

    /** Indice da linha de custo, a unica que aceita somente digitos. */
    private static final int COST_ROW = 4;

    /** Indice da linha da descricao, que usa caixa de varias linhas. */
    private static final int DESCRIPTION_ROW = 5;

    /** Altura alvo da caixa da descricao: cerca de tres linhas. */
    private static final int DESC_H = 36;

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
    /**
     * Caixas de texto de uma linha: execucao e descricao nao entram (botao e
     * caixa multilinha), entao sobra uma por alcance, alvo, duracao e custo.
     */
    private EditBox[] fieldBoxes = new EditBox[FIELD_LABEL_KEYS.length - 2];

    /** O botao de execucao, na linha do primeiro rotulo. */
    private Button executionButton;

    /** A caixa multilinha da descricao, na ultima linha do formulario. */
    private MultiLineEditBox descriptionBox;

    /** Valor da execucao, preservado como texto ate o botao ser clicado. */
    private String execution;

    /** O circulo escolhido no formulario, de 1 a 5. */
    private int circle;

    /** O Y da linha do botao de circulo, para o rotulo seguir a caixa vizinha. */
    private int circleY;

    public SpellFormScreen(Screen parent, String targetName, int index, SheetData.Spell original) {
        super(Component.translatable(index < 0 ? "screen.tabletoprpg.spell_form.title_new" : "screen.tabletoprpg.spell_form.title_edit"));
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
        execution = original == null ? executionValue(EXECUTION_DEFAULT_INDEX) : normalizeExecution(current.execution());

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
                        Component.literal(new SheetData.Spell("", circle, "", "", "", "", "", "").circleLabel()),
                        b -> {
                            circle = circle >= SheetData.Spell.CIRCLE_MAX
                                    ? SheetData.Spell.CIRCLE_MIN : circle + 1;
                            b.setMessage(Component.literal(
                                    new SheetData.Spell("", circle, "", "", "", "", "", "").circleLabel()));
                        })
                .bounds(boxX, y, boxW, ROW_H - 2)
                .tooltip(Tooltip.create(Component.translatable("screen.tabletoprpg.spell_form.circle_tooltip")))
                .build());
        y += ROW_H;

        nameBox = field(Component.translatable("screen.tabletoprpg.sheet_editor.name").getString(), boxX, y, boxW, current.name(), SheetData.Spell.SPELL_NAME_MAX);
        y += ROW_H;

        for (int i = 0; i < FIELD_LABEL_KEYS.length; i++) {
            if (i == EXECUTION_ROW) {
                executionButton = addRenderableWidget(Button.builder(Component.literal(executionLabel(execution)),
                                b -> cycleExecution(b))
                        .bounds(boxX, y, boxW, 18)
                        .build());
                y += ROW_H;
                continue;
            }
            if (i == DESCRIPTION_ROW) {
                // A descricao e a ultima linha: cresce ate DESC_H, mas para acima
                // do rodape para nao cobrir o Save/Cancel em janela baixa.
                int descTop = y;
                int descBottom = Math.max(descTop + 20,
                        Math.min(descTop + DESC_H, this.height - FOOTER_H - 6));
                descriptionBox = MultiLineEditBox.builder()
                        .setX(boxX)
                        .setY(descTop)
                        .setTextColor(CharacterSheetScreen.COL_BOX_TEXT)
                        .setTextShadow(false)
                        .setCursorColor(CharacterSheetScreen.COL_BOX_TEXT)
                        .setShowBackground(true)
                        .setShowDecorations(true)
                        .build(this.font, boxW, descBottom - descTop,
                                Component.translatable(FIELD_LABEL_KEYS[i]));
                descriptionBox.setCharacterLimit(SheetData.Spell.SPELL_DESC_MAX);
                descriptionBox.setValue(fieldValue(current, i));
                addRenderableWidget(descriptionBox);
                y = descBottom;
                continue;
            }
            int maxLen = i == COST_ROW
                    ? SheetData.Spell.SPELL_COST_MAX : SheetData.Spell.SPELL_FIELD_MAX;
            fieldBoxes[i - 1] = field(Component.translatable(FIELD_LABEL_KEYS[i]).getString(),
                    boxX, y, boxW, fieldValue(current, i), maxLen);
            if (i == COST_ROW) {
                fieldBoxes[i - 1].setFilter(text -> text.matches("\\d{0,3}"));
            }
            y += ROW_H;
        }

        int buttonW = (panelW - 24) / 2;
        int buttonY = this.height - FOOTER_H;
        saveButton = addRenderableWidget(Button.builder(Component.translatable("screen.tabletoprpg.spell_form.save"), b -> save())
                .bounds(panelX + 8, buttonY, buttonW, 20)
                .build());
        addRenderableWidget(Button.builder(Component.translatable("screen.tabletoprpg.spell_form.cancel"), b -> onClose())
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

    /** O valor da caixa da linha dada, na ordem de {@link #FIELD_LABEL_KEYS}. */
    private static String fieldValue(SheetData.Spell spell, int i) {
        return switch (i) {
            case 1 -> spell.range();
            case 2 -> spell.target();
            case 3 -> spell.duration();
            case 4 -> spell.cost();
            case 5 -> spell.description();
            default -> "";
        };
    }

    /** ID estavel de um dos valores fixos de execucao. */
    private static String executionValue(int index) {
        return EXECUTION_IDS[index];
    }

    /** Rotulo traduzido de um ID de execucao, com o proprio ID como fallback. */
    private static String executionLabel(String id) {
        for (int i = 0; i < EXECUTION_IDS.length; i++) {
            if (EXECUTION_IDS[i].equals(id)) {
                return Component.translatable(EXECUTION_KEYS[i]).getString();
            }
        }
        return id;
    }

    /**
     * Converte um valor gravado para um ID estavel.
     *
     * <p>Aceita tanto um ID quanto o rotulo traduzido de uma versao anterior que
     * gravou o texto; valores desconhecidos (inclusive vazio) passam direto, para
     * nao apagar informacao que este formulario nao entende.
     */
    private static String normalizeExecution(String stored) {
        for (int i = 0; i < EXECUTION_IDS.length; i++) {
            if (EXECUTION_IDS[i].equals(stored)
                    || Component.translatable(EXECUTION_KEYS[i]).getString().equals(stored)) {
                return EXECUTION_IDS[i];
            }
        }
        return stored;
    }

    /**
     * Avanca para o proximo valor de execucao.
     *
     * <p>Um texto vindo de uma versao anterior que nao esteja na lista e
     * preservado ate este clique; ai o botao comeca pelo primeiro valor.
     */
    private void cycleExecution(Button button) {
        int index = -1;
        for (int i = 0; i < EXECUTION_IDS.length; i++) {
            if (EXECUTION_IDS[i].equals(execution)) {
                index = i;
                break;
            }
        }
        execution = executionValue(index < 0 ? 0 : (index + 1) % EXECUTION_IDS.length);
        button.setMessage(Component.literal(executionLabel(execution)));
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
        CharacterSheetScreen.drawLabel(graphics, this.font, nameBox.getX(), Component.translatable("screen.tabletoprpg.spell_form.circle").getString(), circleY);
        CharacterSheetScreen.drawLabel(graphics, this.font, nameBox.getX(), Component.translatable("screen.tabletoprpg.sheet_editor.name").getString(), nameBox.getY());
        for (int i = 0; i < FIELD_LABEL_KEYS.length; i++) {
            int rowY = i == EXECUTION_ROW ? executionButton.getY()
                    : i == DESCRIPTION_ROW ? descriptionBox.getY()
                    : fieldBoxes[i - 1].getY();
            CharacterSheetScreen.drawLabel(graphics, this.font, nameBox.getX(), Component.translatable(FIELD_LABEL_KEYS[i]).getString(), rowY);
        }
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
        int widest = Math.max(LABEL_W, this.font.width(Component.translatable("screen.tabletoprpg.spell_form.circle").getString()) + 6);
        for (String key : FIELD_LABEL_KEYS) {
            widest = Math.max(widest, this.font.width(Component.translatable(key).getString()) + 6);
        }
        return widest;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);
        CharacterSheetScreen.drawCenteredTitle(graphics, this.font, this.width, this.title);
        renderLabels(graphics);
        CharacterSheetScreen.drawNameWarningIfBlank(graphics, this.font, nameBox);
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
                nameBox.getValue(), circle, execution,
                fieldBoxes[0].getValue(), fieldBoxes[1].getValue(),
                fieldBoxes[2].getValue(), fieldBoxes[3].getValue(),
                descriptionBox.getValue());
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
