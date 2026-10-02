package com.pedro.tabletoprpg.client;

import com.pedro.tabletoprpg.RollPreset;
import com.pedro.tabletoprpg.RollPresetColor;
import com.pedro.tabletoprpg.RpgNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * A tela de <b>Presets de Rolagem</b>: lista em cima, formulario embaixo (01/10/2026).
 *
 * <p><b>Por que uma tela so, e nao "criar" e "editar" separadas:</b> criar e editar
 * usam os mesmos tres campos e o mesmo botao. O que muda e o que ja vem preenchido:
 * clicar num preset da lista carrega os tres campos, e ai o mesmo Save grava por
 * cima. Uma tela para cada caso repetiria o formulario inteiro e o jogador teria duas
 * coisas parecidas na tela.
 *
 * <p><b>Por que o fundo e desenhado e nao uma textura:</b> decisao do usuario em
 * 01/10/2026. A lista precisa de altura variavel (a jogadora tem de 1 a
 * {@code RollPresetStore.MAX_PRESETS} presets) e nenhuma das texturas do mod tem uma
 * faixa de lista com tamanho variavel. Um retangulo escuro com as mesmas cores do
 * resto do mod resolve sem PNG novo.
 *
 * <p><b>Por que o formulario e separado da lista:</b> a lista e rolavel e o
 * formulario e fixo. Se os dois estivessem na mesma faixa, digitar no campo de nome
 * enquanto a lista rola seria ambiguo: o texto ia subir junto com os presets.
 */
public class PresetsScreen extends Screen {

    private final Screen parentScreen;

    /**
     * Os presets que a tela esta mostrando, na ordem em que o servidor mandou.
     *
     * <p><b>Por que a tela guarda a lista e nao le do store:</b> o store e do lado do
     * servidor. A tela precisa de uma copia para desenhar sem travar a cada quadro, e
     * a copia so e trocada quando o servidor responde.
     */
    private List<RollPreset> presets = new ArrayList<>();

    /**
     * O preset em edicao, ou {@code null} quando a jogadora esta criando um novo.
     *
     * <p>E o que decide o que o Save faz: com nome, o servidor edita; sem nome, cria.
     * Guardar o preset inteiro (e nao so o nome) permite saber se o Save precisa
     * avisar que a posicao dele na lista vai mudar, porque o nome novo e diferente do
     * velho.
     */
    private RollPreset editing;

    /**
     * A linha do Del esperando o segundo clique, ou -1.
     *
     * <p><b>Por que e indice e nao nome:</b> o nome pode mudar entre os dois cliques
     * (a jogadora edita no meio), e apagar o preset errado e pior do que nao apagar.
     * O indice trava a linha.
     */
    private int deletePending = -1;

    private EditBox nameBox;
    private EditBox formulaBox;
    private RollPresetColor selectedColor = RollPresetColor.WHITE;

    private int panelX, panelY, panelWidth;
    private int listTop, listBottom, listScroll, listRowHeight;
    private int swatchX, swatchY, swatchSize = 14, swatchGap = 3, swatchPerRow = 9;
    private int statusY;

    /** Os squares, na ordem do enum: a tela mostra a mesma ordem das cores. */
    private static final RollPresetColor[] SWATCHES = RollPresetColor.values();

    /** Quantas linhas a lista mostra antes de rolar. */
    private static final int VISIBLE_ROWS = 6;

    public PresetsScreen(Screen parentScreen) {
        super(Component.literal("Presets"));
        this.parentScreen = parentScreen;
    }

    /** A tela que abriu esta, para o receptor do servidor voltar para ela. */
    public Screen parentScreen() {
        return parentScreen;
    }

    @Override
    protected void init() {
        super.init();

        panelWidth = Math.min(this.width - 24, 280);
        panelX = (this.width - panelWidth) / 2;
        panelY = 12;

        listRowHeight = 22;
        int titleH = 20;
        int listTopLocal = panelY + titleH;
        int formH = 26 * 3          // Name + Formula + rotulo de cor
                + rowsOfSwatches() * (swatchSize + swatchGap)
                + 26                 // Save / Use
                + 20                 // status
                + 26;                // voltar para Rolls
        int listHeight = VISIBLE_ROWS * listRowHeight;
        listTop = listTopLocal;
        listBottom = listTopLocal + listHeight;

        int boxX = panelX + (panelWidth - 200) / 2;
        int labelW = 50;
        int boxW = 200;

        int y = listBottom + 8;
        nameBox = field("Name", boxX + labelW, y, boxW);
        nameBox.setMaxLength(RollPreset.MAX_NAME);

        y += 26;
        formulaBox = field("Formula", boxX + labelW, y, boxW);
        formulaBox.setMaxLength(RollPreset.MAX_FORMULA);

        // Amostras centralizadas, abaixo do rotulo "Color".
        int colorLabelY = y + 26;
        int swatchRows = rowsOfSwatches();
        int swatchesWidth = swatchPerRow * swatchSize + (swatchPerRow - 1) * swatchGap;
        swatchX = (this.width - swatchesWidth) / 2;
        swatchY = colorLabelY + 14;

        statusY = swatchY + swatchRows * (swatchSize + swatchGap) + 6 + 26;

        rebuildFooter();
        // A lista e desenhada linha a linha, com widget por linha. Sem
        // `rebuildWidgets` aqui: quem chama e o receptor do pacote, que pode chegar
        // a qualquer momento.
        rebuildListOnly();
    }

    private int rowsOfSwatches() {
        return (SWATCHES.length + swatchPerRow - 1) / swatchPerRow;
    }

    private EditBox field(String label, int x, int y, int w) {
        EditBox box = new EditBox(this.font, x, y, w, 18, Component.literal(label));
        // OBRIGATORIO: sem addRenderableWidget a caixa nao entra no ciclo de desenho
        // nem no de clique (mesmo cuidado anotado em InventoryItemScreen).
        addRenderableWidget(box);
        return box;
    }

    /**
     * Monta os widgets da lista: nome, setas e Del.
     *
     * <p><b>Por que recria os widgets em vez de repositionar:</b> a lista muda de
     * tamanho quando um preset e criado ou apagado, e o `children()` do Screen nao
     * tem como remover widget. Recriar e o caminho curto e e o que a tela de Skills e
     * Magias ja faz.
     */
    private void rebuildListButtons() {
        clearWidgets();
        // O `clearWidgets` leva os campos junto, entao eles voltam a ser criados --
        // com o texto que estava digitado, lido das caixas ANTES de recriar.
        pendingName = nameBox == null ? "" : nameBox.getValue();
        pendingFormula = formulaBox == null ? "" : formulaBox.getValue();

        int boxX = panelX + (panelWidth - 200) / 2;
        int labelW = 50;
        int y = listBottom + 8;
        nameBox = field("Name", boxX + labelW, y, 200);
        nameBox.setMaxLength(RollPreset.MAX_NAME);
        nameBox.setValue(pendingName);
        y += 26;
        formulaBox = field("Formula", boxX + labelW, y, 200);
        formulaBox.setMaxLength(RollPreset.MAX_FORMULA);
        formulaBox.setValue(pendingFormula);

        // O rodape (Save, Use, voltar) tambem foi limpo, entao volta aqui em vez de
        // ficar em init: e o que faz o formulario reaparecer depois de uma seta.
        rebuildFooter();
    }

    /** Recria Save, Use e o botao de voltar, que o {@code clearWidgets} levou. */
    private void rebuildFooter() {
        int btnY = swatchY + rowsOfSwatches() * (swatchSize + swatchGap) + 6;
        int btnW = (panelWidth - 20) / 2;
        this.addRenderableWidget(Button.builder(Component.literal("Save"), b -> save())
                .bounds(panelX + 10, btnY, btnW, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Use"), b -> usePreset())
                .bounds(panelX + 10 + btnW + 8, btnY, btnW, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("< Back to Rolls"), b -> onClose())
                .bounds(panelX, statusY + 14, 110, 20).build());
    }

    /** Recria so os widgets das linhas da lista, sem mexer no formulario. */
    private void rebuildListOnly() {
        // Os widgets da lista vivem entre `listTop` e `listBottom`; sao recriados
        // aqui e o formulario ja foi montado em init.
        int visibleFrom = listScroll;
        int visibleTo = Math.min(presets.size(), listScroll + VISIBLE_ROWS);
        int btnH = 18;
        int btnW = 20;
        int upX = panelX + panelWidth - 4 - btnW * 3 - 6;
        int delX = panelX + panelWidth - 4 - btnW - 2;

        for (int i = visibleFrom; i < visibleTo; i++) {
            RollPreset preset = presets.get(i);
            int rowY = listTop + (i - listScroll) * listRowHeight;
            int storedIndex = i;

            // Setas: uma casa por clique. A primeira e a ultima linha nao tem seta
            // naquela direcao, e nao e erro -- e so a borda da lista.
            if (i > 0) {
                this.addRenderableWidget(Button.builder(Component.literal("\u2191"), b -> move(storedIndex, true))
                        .bounds(upX, rowY, btnW, btnH)
                        .tooltip(Tooltip.create(Component.literal("Move up")))
                        .build());
            }
            if (i < presets.size() - 1) {
                this.addRenderableWidget(Button.builder(Component.literal("\u2193"), b -> move(storedIndex, false))
                        .bounds(upX + btnW + 2, rowY, btnW, btnH)
                        .tooltip(Tooltip.create(Component.literal("Move down")))
                        .build());
            }

            // O nome: abre para edicao, e mostra a formula no hover (pedido do
            // usuario). O tooltip e a resposta ao "ao passar o mouse aparecera a
            // formula".
            this.addRenderableWidget(Button.builder(
                            Component.literal(truncate(preset.name(), 120)),
                            b -> loadForEdit(storedIndex))
                    .bounds(panelX + 4, rowY, Math.max(20, upX - panelX - 8), btnH)
                    .tooltip(Tooltip.create(Component.literal(preset.formula()),
                            Component.literal("Color: " + preset.color().displayName())))
                    .build());

            // Del com confirmacao em dois cliques, igual a lista de skills e magias.
            boolean pending = deletePending == storedIndex;
            this.addRenderableWidget(Button.builder(
                            Component.literal(pending ? "Del?" : "Del"),
                            b -> onDelete(storedIndex))
                    .bounds(delX, rowY, btnW, btnH)
                    .tooltip(Tooltip.create(Component.literal(
                            pending ? "Click again to confirm" : "Delete")))
                    .build());
        }
    }

    private String truncate(String text, int maxWidth) {
        if (this.font.width(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "...";
        // String e nao int: `font.width` devolve pixel, e o recorte e por caractere
        // porque e o texto que a jogadora le que importa.
        String out = text;
        while (out.length() > 1 && this.font.width(out + ellipsis) > maxWidth) {
            out = out.substring(0, out.length() - 1);
        }
        return out + ellipsis;
    }

    // --- acoes ---

    /**
     * Clicou no nome de um preset: os campos passam a mostrar ele.
     *
     * <p>Limpar a selecao de cor tambem e parte disso: sem isso, abrir um preset
     * azul deixaria o campo mostrando o branco selecionado, e o Save gravaria branco
     * em cima do que a jogadora nao mexeu.
     */
    private void loadForEdit(int index) {
        if (index < 0 || index >= presets.size()) {
            return;
        }
        editing = presets.get(index);
        nameBox.setValue(editing.name());
        formulaBox.setValue(editing.formula());
        selectedColor = editing.color();
        deletePending = -1;
    }

    /** O botao Save: cria quando nao ha preset em edicao, edita quando ha. */
    private void save() {
        String original = editing == null ? "" : editing.name();
        ClientPlayNetworking.send(new RpgNetworking.PresetSavePayload(
                original, nameBox.getValue().trim(), formulaBox.getValue().trim(), selectedColor.id()));
    }

    /**
     * O botao Use: rola o preset em edicao.
     *
     * <p>Sem preset em edicao o botao nao faz nada: nao ha nome para rolar, e adivinhar
     * o que a jogadora quer rolar seria chute.
     */
    private void usePreset() {
        if (editing == null) {
            setStatus("click a preset in the list to use it");
            return;
        }
        // O preset ja foi validado quando foi salvo, entao rolar direto pelo nome e
        // o mesmo caminho do item: o servidor procura, rola e avisa se sumiu.
        if (this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.connection.sendCommand("rpg preset use " + editing.name());
        }
        onClose();
    }

    /** O primeiro clique marca, o segundo na mesma linha apaga. */
    private void onDelete(int index) {
        if (index < 0 || index >= presets.size()) {
            deletePending = -1;
            rebuildListOnly();
            return;
        }
        if (deletePending != index) {
            deletePending = index;
            rebuildListOnly();
            return;
        }
        deletePending = -1;
        RollPreset preset = presets.get(index);
        if (editing != null && editing.key().equals(preset.key())) {
            // Apagando o preset que esta nos campos: os campos nao podem continuar
            // mostrando algo que nao existe mais.
            editing = null;
            nameBox.setValue("");
            formulaBox.setValue("");
        }
        ClientPlayNetworking.send(new RpgNetworking.PresetDeletePayload(preset.name()));
    }

    /** Uma seta: manda para o servidor e espera a lista nova. */
    private void move(int index, boolean up) {
        if (index < 0 || index >= presets.size()) {
            return;
        }
        ClientPlayNetworking.send(new RpgNetworking.PresetMovePayload(presets.get(index).name(), up));
    }

    // --- entrada do jogador ---

    /**
     * Clique do mouse sobre as amostras de cor.
     *
     * <p><b>Por que o evento e um {@code MouseButtonEvent} e nao dois doubles:</b> nesta
     * versao (1.21.11) o {@code mouseClicked} da cadeia de GUI recebe o evento, com
     * {@code x()} e {@code y()}. A assinatura antiga com {@code double, double, int} nao
     * existe mais aqui.
     *
     * <p>As amostras sao desenho puro, sem widget: cada uma seria 17 widgets com rotulo
     * vazio, e nao ha tint de botao na API.
     */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int index = swatchAt(event.x(), event.y());
        if (index >= 0) {
            selectedColor = SWATCHES[index];
            // O mesmo som de botao do resto da interface; escolher cor nao e um botao
            // que "desce", entao o som de pressionar seria enganoso.
            if (this.minecraft != null) {
                net.minecraft.client.gui.components.AbstractWidget
                        .playButtonClickSound(this.minecraft.getSoundManager());
            }
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    /** O quadradinho da cor, ou -1 se o clique foi fora de todos. */
    private int swatchAt(double mouseX, double mouseY) {
        int rows = rowsOfSwatches();
        if (mouseY < swatchY || mouseY >= swatchY + rows * (swatchSize + swatchGap)) {
            return -1;
        }
        int row = (int) ((mouseY - swatchY) / (swatchSize + swatchGap));
        int col = (int) ((mouseX - swatchX) / (swatchSize + swatchGap));
        if (col < 0 || col >= swatchPerRow) {
            return -1;
        }
        int index = row * swatchPerRow + col;
        if (index < 0 || index >= SWATCHES.length) {
            return -1;
        }
        // A ultima linha tem menos de 9; o espaco sobrando nao e clicavel.
        double localX = mouseX - (swatchX + col * (swatchSize + swatchGap));
        double localY = mouseY - (swatchY + row * (swatchSize + swatchGap));
        if (localX > swatchSize || localY > swatchSize) {
            return -1;
        }
        return index;
    }

    /**
     * A roda do mouse rola a lista.
     *
     * <p><b>Por que a lista e a unica que rola:</b> o formulario tem tres campos e
     * nao cabe mais que isso na tela; rolar nele nao faria sentido. E o limite e
     * recalculado pelo tamanho da lista, entao uma lista de dois presets nao deixa
     * espaco morto para rolar.
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseY >= listTop && mouseY < listBottom && maxScroll() > 0) {
            listScroll = Math.max(0, Math.min(maxScroll(), listScroll - (int) -Math.signum(scrollY)));
            rebuildListOnly();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private int maxScroll() {
        return Math.max(0, presets.size() - VISIBLE_ROWS);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, this.width, this.height, 0x99000000);

        // O painel vai ate a linha de status mais o botao de voltar. Medido pelo
        // conteudo: o desenho e retangulo, entao a altura accompanies o que ha
        // dentro dele em vez de cortar o campo novo.
        int backY = statusY + 14;
        graphics.fill(panelX, panelY, panelX + panelWidth, backY + 20, 0xFF202020);
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + 1, 0xFF6B4A2A);
        graphics.fill(panelX, backY + 19, panelX + panelWidth, backY + 20, 0xFF6B4A2A);

        super.render(graphics, mouseX, mouseY, delta);

        graphics.drawCenteredString(this.font, this.title, this.width / 2, panelY + 6, 0xFFE0C080);

        drawList(graphics);
        drawSwatches(graphics);

        int boxX = nameBox.getX();
        graphics.drawString(this.font, "Name", boxX - 4 - this.font.width("Name"),
                nameBox.getY() + 5, 0xFFB0B0B0, false);
        graphics.drawString(this.font, "Formula", boxX - 4 - this.font.width("Formula"),
                formulaBox.getY() + 5, 0xFFB0B0B0, false);
        graphics.drawCenteredString(this.font, "Color", this.width / 2, swatchY - 16, 0xFFB0B0B0);
        graphics.drawString(this.font, selectedColor.displayName(),
                boxX - 4 - this.font.width(selectedColor.displayName()), swatchY, 0xFFB0B0B0, false);

        if (!pendingStatus.isEmpty()) {
            graphics.drawCenteredString(this.font, pendingStatus, this.width / 2, statusY, 0xFFFF6060);
        }
    }

    /**
     * A lista: fundo, linha selecionada e as formulas ao lado dos nomes.
     *
     * <p><b>Por que a formula aparece na linha e tambem no tooltip:</b> o tooltip so
     * aparece quando o mouse esta em cima, e a lista pode ter seis linhas. Ver a
     * formula de todos de uma vez e o que faz a tela ser util sem passar o mouse.
     */
    private void drawList(GuiGraphics graphics) {
        graphics.fill(panelX + 4, listTop, panelX + panelWidth - 4, listBottom, 0xFF161616);

        int visibleFrom = listScroll;
        int visibleTo = Math.min(presets.size(), listScroll + VISIBLE_ROWS);
        int nameRight = panelX + panelWidth - 4 - 20 * 3 - 10;

        for (int i = visibleFrom; i < visibleTo; i++) {
            RollPreset preset = presets.get(i);
            int rowY = listTop + (i - listScroll) * listRowHeight;
            boolean isEditing = editing != null && editing.key().equals(preset.key());

            if (isEditing) {
                // A linha em edicao fica marcada: e o que diz a jogadora qual preset o
                // Save vai sobrescrever.
                graphics.fill(panelX + 5, rowY, panelX + panelWidth - 5, rowY + listRowHeight - 2,
                        0xFF3A3A3A);
            }

            // A cor do preset como um quadradinho antes da formula: e a unica coisa
            // que distingue dois presets com o mesmo nome visualmente.
            graphics.fill(panelX + 6, rowY + 5, panelX + 14, rowY + 13, preset.color().argb());

            String formula = truncate(preset.formula(),
                    Math.max(10, nameRight - (panelX + 6) - 24));
            graphics.drawString(this.font, formula,
                    nameRight - this.font.width(formula), rowY + 5, 0xFF7F7F7F, false);
        }

        if (presets.isEmpty()) {
            graphics.drawCenteredString(this.font, "No presets yet. Fill the fields and Save.",
                    panelX + panelWidth / 2, listTop + 6, 0xFF808080);
        }
    }

    private void drawSwatches(GuiGraphics graphics) {
        int rows = rowsOfSwatches();
        for (int i = 0; i < SWATCHES.length; i++) {
            int row = i / swatchPerRow;
            int col = i % swatchPerRow;
            int x = swatchX + col * (swatchSize + swatchGap);
            int y = swatchY + row * (swatchSize + swatchGap);
            graphics.fill(x, y, x + swatchSize, y + swatchSize, SWATCHES[i].argb());
            // Borda clara em volta de quem esta escolhido, escura nas outras: a escolha
            // precisa ser visivel mesmo nas cores escuras (preto em preto sumiria).
            int border = SWATCHES[i] == selectedColor ? 0xFFFFFFFF : 0xFF101010;
            graphics.fill(x, y, x + swatchSize, y + 1, border);
            graphics.fill(x, y + swatchSize - 1, x + swatchSize, y + swatchSize, border);
            graphics.fill(x, y, x + 1, y + swatchSize, border);
            graphics.fill(x + swatchSize - 1, y, x + swatchSize, y + swatchSize, border);
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Ja desenhado em render: o fundo escuro e o painel sao filled, nao textura.
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parentScreen);
    }

    // --- estado que chega do servidor ---

    /**
     * Os valores dos campos, guardados para sobreviver a um {@code rebuildListOnly}.
     *
     * <p><b>Por que campo e nao o proprio {@code EditBox}:</b> recriar os widgets da
     * lista recria a lista inteira de widgets do Screen, e o texto que estava digitado
     * nas caixas se perderia. Guardando o texto, o que a jogadora digitou continua
     * depois de uma seta.
     */
    private String pendingName = "";
    private String pendingFormula = "";

    /**
     * A resposta do servidor para a tela aberta.
     *
     * <p><b>Por que estatico:</b> o receptor do pacote roda fora da tela, e a
     * resposta chega depois do clique. Um campo da tela exigiria a tela ainda viva, e
     * ela pode ter sido fechada.
     */
    private static String pendingStatus = "";

    /** Chamado pelo receptor do cliente com a lista nova e a mensagem do servidor. */
    public void applyResult(boolean ok, String message, List<RollPreset> updated) {
        if (updated != null && !updated.isEmpty()) {
            presets = new ArrayList<>(updated);
        } else if (ok) {
            // Ok com lista vazia = a jogadora deletou o ultimo preset.
            presets = new ArrayList<>();
        }
        pendingStatus = message == null ? "" : message;

        // O preset em edicao continua sendo o mesmo objeto da lista, entao a linha
        // segue marcada depois de salvar.
        if (editing != null) {
            RollPreset found = findByKey(editing.key());
            if (found == null) {
                // O preset em edicao sumiu (deletado, ou renomeado pelo servidor).
                editing = null;
            } else {
                editing = found;
            }
        }
        deletePending = -1;
        rebuildListOnly();
    }

    private RollPreset findByKey(String key) {
        for (RollPreset preset : presets) {
            if (preset.key().equals(key)) {
                return preset;
            }
        }
        return null;
    }

    /** Abre a tela ja pedindo a lista, para o cliente nao mostrar lista vazia. */
    public void requestList() {
        ClientPlayNetworking.send(new RpgNetworking.PresetListRequestPayload());
    }

    public void setStatus(String message) {
        pendingStatus = message == null ? "" : message;
    }
}