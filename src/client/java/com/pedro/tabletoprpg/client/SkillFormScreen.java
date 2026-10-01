package com.pedro.tabletoprpg.client;

import com.pedro.tabletoprpg.RpgNetworking;
import com.pedro.tabletoprpg.SheetData;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Formulario de <b>uma skill</b> (01/10/2026, pagina 3 da ficha).
 *
 * <p>E a tela que o jogador abria pela antiga {@code SkillsScreen}, trazida para
 * dentro da aba da ficha. O pedido era o detalhe ser "uma aba assim como a do
 * inventario", entao o formato e o do {@link InventoryItemScreen}: tela cheia,
 * nome + descricao, {@code Save} e {@code Cancel}, e o {@code Cancel} volta
 * direto para a ficha.
 *
 * <p><b>Continua com dois campos, sem valor:</b> skill neste projeto e habilidade
 * narrativa, e quem rola e a <b>pericia</b> (a aritmetica e
 * {@code 1d20 + valor_da_pericia + atributo}). O que muda na pagina 3 e onde a
 * skill e editada, nao o que ela guarda.
 *
 * <p><b>Por que o indice e a skill viajam no construtor:</b> o mesmo motivo do
 * item e da magia -- o {@code UPDATE} sai pelo indice, e um UPDATE atrasado
 * precisa poder ser recusado pelo servidor.
 */
public class SkillFormScreen extends Screen {

    /** Altura de uma linha de campo (rotulo + caixa). */
    private static final int ROW_H = 22;

    /** Altura reservada aos botoes no rodape do painel. */
    private static final int FOOTER_H = 26;

    /** Espaco reservado ao rotulo a esquerda da caixa. */
    private static final int LABEL_W = 66;

    private final Screen parent;
    private final String targetName;

    /** Indice da skill editada, ou {@code -1} para uma skill nova. */
    private final int index;

    /** A skill original, como o pai leu da ficha; {@code null} quando e nova. */
    private final SheetData.Skill original;

    private EditBox nameBox;

    /** O botao Save, desativado enquanto o nome estiver vazio. 01/10/2026. */
    private Button saveButton;

    /**
     * Liga ou desliga o Save conforme o nome.
     *
     * <p><b>Por que o aviso continua existindo:</b> com o botao cinza o jogador
     * ja sabe que algo falta, mas nao sabe <b>o que</b>. O texto embaixo da caixa
     * diz. Ele nasce do mesmo estado do botao -- nao de um clique que agora nao
     * acontece -- entao os dois nunca discordam.
     */
    private void syncSaveEnabled() {
        if (saveButton != null) {
            saveButton.active = !nameBox.getValue().isBlank();
        }
    }
    private MultiLineEditBox descriptionBox;

    public SkillFormScreen(Screen parent, String targetName, int index, SheetData.Skill original) {
        super(Component.literal(index < 0 ? "New Skill" : "Edit Skill"));
        this.parent = parent;
        this.targetName = targetName == null ? "" : targetName;
        this.index = index;
        this.original = original;
    }

    /** A skill que a tela abre: a enviada pelo pai, ou uma skill vazia. */
    private SheetData.Skill currentSkill() {
        // 01/10/2026: `Skill` nao tem constante `EMPTY` (ao contrario de
        // `InventoryItem`), entao o vazio e construido aqui. E o MESMO caminho do
        // construtor de `SheetData`, que corta o nome e a descricao.
        return original == null ? new SheetData.Skill("", "") : original;
    }

    @Override
    protected void init() {
        SheetData.Skill current = currentSkill();

        int panelW = Math.max(200, Math.min(this.width - 20, 280));
        int panelX = (this.width - panelW) / 2;
        int boxX = panelX + 8 + labelW();
        int boxW = Math.max(40, panelW - 16 - labelW());

        int y = 40;
        nameBox = new EditBox(this.font, boxX, y, boxW, 18, Component.literal("Name"));
        nameBox.setMaxLength(SheetData.SKILL_MAX);
        nameBox.setValue(current.name());
        addRenderableWidget(nameBox);
        y += ROW_H;

        // A descricao cresce ate o rodape. Sem `setLineLimit`, que contaria as
        // linhas VISIVEIS (o teto do servidor e o `setCharacterLimit`).
        int descTop = y;
        int descBottom = Math.max(descTop + 20, this.height - FOOTER_H - 26);
        descriptionBox = MultiLineEditBox.builder()
                .setX(boxX)
                .setY(descTop)
                .setPlaceholder(Component.literal("description"))
                .setTextColor(CharacterSheetScreen.COL_BOX_TEXT)
                .setTextShadow(false)
                .setCursorColor(CharacterSheetScreen.COL_BOX_TEXT)
                .setShowBackground(true)
                .setShowDecorations(true)
                .build(this.font, boxW, descBottom - descTop, Component.literal("Description"));
        descriptionBox.setCharacterLimit(SheetData.SKILL_DESC_MAX);
        descriptionBox.setValue(current.description());
        // **OBRIGATORIO**: sem `addRenderableWidget` a caixa nao entra em
        // Screen.children() e nao recebe clique, foco, teclado nem rolagem.
        addRenderableWidget(descriptionBox);

        int buttonW = (panelW - 24) / 2;
        int buttonY = this.height - FOOTER_H;
        saveButton = addRenderableWidget(Button.builder(Component.literal("Save"), b -> save())
                .bounds(panelX + 8, buttonY, buttonW, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(panelX + panelW - 8 - buttonW, buttonY, buttonW, 20)
                .build());

        // Save CINZA e sem clique enquanto o nome estiver vazio (01/10/2026).
        syncSaveEnabled();
        nameBox.setResponder(text -> syncSaveEnabled());

        setInitialFocus(nameBox);
    }

    /** Rotulos das caixas, ancorados na propria caixa. */
    private void renderLabels(GuiGraphics graphics) {
        drawLabel(graphics, "Name", nameBox.getY());
        drawLabel(graphics, "Description", descriptionBox.getY());
    }

    /** Um rotulo terminado 4 px antes da coluna das caixas. */
    private void drawLabel(GuiGraphics graphics, String text, int boxY) {
        int right = nameBox.getX() - 4;
        graphics.drawString(this.font, text, right - this.font.width(text),
                boxY + 5, CharacterSheetScreen.COL_LABEL, false);
    }

    /** Largura da coluna dos rotulos: a maior das duas palavras, mais folga. */
    private int labelW() {
        return Math.max(LABEL_W, this.font.width("Description") + 6);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);
        graphics.drawString(this.font, this.title, (this.width - this.font.width(this.title)) / 2,
                8, CharacterSheetScreen.COL_TITLE, false);
        renderLabels(graphics);
        if (nameBox.getValue().isBlank()) {
            String warn = "Name required";
            // Sob a caixa de nome, e nao em cima dela: o texto do campo continuaria
            // visivel e o jogador veria onde esta o problema.
            graphics.drawString(this.font, warn, nameBox.getX(),
                    nameBox.getY() + nameBox.getHeight() + 3, 0xFF5555, false);
        }
    }

    /**
     * Monta a skill e envia.
     *
     * <p><b>Depois de salvar volta para a ficha e PEDE a ficha de novo:</b> o
     * mesmo motivo do {@link InventoryItemScreen#save} -- o eco deste pacote
     * chega enquanto esta tela ainda esta aberta e seria descartado.
     */
    private void save() {
        // Nome obrigatorio (01/10/2026): o pedido do usuario. Sem isso dava para
        // salvar uma skill sem nome, que aparecia na lista como um botao vazio e
        // ainda ocupava espaco e uma linha do sumario.
        //
        // <p><b>Por que so o cliente:</b> a validacao e de COMFORT e nao de
        // seguranca. O servidor continua sendo a autoridade e ja normaliza e
        // limita o texto; o cliente evita apenas o ida-e-volta de um pacote
        // desnecessario e o primeiro erro que o jogador veria.
        if (nameBox.getValue().isBlank()) {
            nameBox.setFocused(true);
            setInitialFocus(nameBox);
            return;
        }
        String name = nameBox.getValue();
        String description = descriptionBox.getValue();
        // Skill nao tem `add` por indice: o `ADD` do SheetSkillPayload e por
        // NOME (cria, ou atualiza a descricao se o nome ja existir), e o
        // `UPDATE` e por indice + nome, porque a trava contra um "Save" atrasado
        // precisa dos dois.
        ClientPlayNetworking.send(index < 0 || original == null
                ? RpgNetworking.SheetSkillPayload.add(targetName, name, description)
                : RpgNetworking.SheetSkillPayload.update(targetName, index, name, description));
        onClose();
        TabletopRpgClient.requestSheet(targetName);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }
}
