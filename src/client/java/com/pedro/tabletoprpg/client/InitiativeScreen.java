package com.pedro.tabletoprpg.client;

import com.pedro.tabletoprpg.InitiativeSync;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Menu de iniciativa, aberto pelo botao "Iniciativa" do menu R.
 *
 * <p>Os botoes ficam em coluna e a lista de iniciativa aparece logo abaixo. Mestre:
 * comecar/terminar, adicionar, rolar, rolar mob, mostrar e configurar (abre a
 * {@link InitiativeConfigScreen}, onde ele troca a formula padrao da mesa).
 * Jogador: rolar e mostrar. Comecar, terminar, rolar, remover e reordenar executam
 * direto; adicionar e rolar mob abrem a lista de candidatos. Fora do modo
 * Investigacao/Combate os botoes de acao ficam desabilitados.
 */
public class InitiativeScreen extends Screen {

    private static final int PANEL_W = 320;
    private static final int COL_GAP = 8;
    private static final int BUTTON_W = (PANEL_W - 20 - COL_GAP) / 2;
    private static final int BUTTON_H = 20;
    private static final int TOP = 56;
    private static final int GAP = 6;
    /** Gutter do numero da linha na lista (1., 2., ...), igual a posicao do `move`. */
    private static final int NUMBER_W = 24;

    private final Screen parent;
    private final boolean allowed;
    /**
     * Valor de Mestre no momento em que os widgets foram montados. A fonte viva
     * e {@link TabletopRpgClient#isMaster}: virar ou deixar de ser Mestre com a
     * tela aberta reconstroi os botoes (03/11/2026).
     */
    private boolean builtMaster;
    private boolean builtCollecting;
    private int builtEntriesHash;
    /** Rodada no momento em que os widgets foram montados (04/11/2026). */
    private int builtRound;
    /** Y do titulo da lista, logo abaixo da ultima linha de botoes. */
    private int listTop;
    /** Y da dica "posicoes de 1 a N" da linha de mover; 0 quando nao e Mestre. */
    private int moveNoteY;
    /** Dica da linha de mover em vermelho depois de uma posicao fora da faixa. */
    private boolean moveNoteError;
    /** Ja pediu o estado ao servidor nesta abertura de tela (evita repetir no rebuild). */
    private boolean requestedState;
    /** Primeira entrada desenhada na lista; a roda do mouse muda (06/11/2026). */
    private int scroll;

    /**
     * @param allowed {@code false} no modo FREE: iniciativa so funciona em
     *                Investigacao e Combate, entao os botoes de acao ficam desabilitados
     */
    public InitiativeScreen(Screen parent, boolean allowed) {
        super(Component.translatable("screen.tabletoprpg.initiative.title"));
        this.parent = parent;
        this.allowed = allowed;
    }

    /** O papel de Mestre e lido do estado vivo do cliente, nao congelado na construcao. */
    private static boolean isMaster() {
        return TabletopRpgClient.isMaster;
    }

    @Override
    protected void init() {
        builtMaster = isMaster();
        builtCollecting = InitiativeClientState.isCollecting();
        builtRound = InitiativeClientState.round();
        builtEntriesHash = InitiativeClientState.entries().hashCode();
        moveNoteY = 0;
        // Pedido de estado fresco: a lista de candidatos muda sem comando de
        // iniciativa (ficha vinculada a um mob novo, mob no carrossel), entao o
        // ultimo broadcast pode estar velho quando a tela abre (03/11/2026).
        if (!requestedState && minecraft != null && minecraft.getConnection() != null) {
            requestedState = true;
            ClientPlayNetworking.send(new InitiativeSync.RequestPayload());
        }
        int x = width / 2 - PANEL_W / 2 + 10;
        int y = TOP;
        boolean canAct = allowed && builtCollecting;
        // Duas colunas (06/11/2026): metade dos botoes numa linha para o menu ficar
        // mais baixo e sobrar espaco para a lista. A linha "de / para / Mover"
        // continua no meio, sozinha, porque tem campos de texto.
        if (isMaster()) {
            addMenuButton(x, y, BUTTON_W, Component.translatable(builtCollecting
                    ? "screen.tabletoprpg.initiative.end"
                    : "screen.tabletoprpg.initiative.start"), allowed, () -> sendCommand(builtCollecting
                    ? "rpg initiative end"
                    : "rpg initiative start"));
            addMenuButton(x + BUTTON_W + COL_GAP, y, BUTTON_W,
                    Component.translatable("screen.tabletoprpg.initiative.add"), canAct,
                    () -> openPicker(InitiativePickerScreen.Action.ADD));
            y += BUTTON_H + GAP;

            addMenuButton(x, y, BUTTON_W, Component.translatable("screen.tabletoprpg.initiative.roll"), canAct,
                    () -> sendCommand("rpg initiative roll"));
            addMenuButton(x + BUTTON_W + COL_GAP, y, BUTTON_W,
                    Component.translatable("screen.tabletoprpg.initiative.roll_mob"), canAct,
                    () -> openPicker(InitiativePickerScreen.Action.ROLL_MOB));
            y += BUTTON_H + GAP;

            // Menu de dar turno (04/11/2026): mesma tela do botao "Nao" da
            // confirmacao de proximo turno. O comando existe para o servidor
            // reenviar a lista fresca antes de abrir.
            addMenuButton(x, y, BUTTON_W, Component.translatable("screen.tabletoprpg.initiative.give_turn"), canAct,
                    () -> sendCommand("rpg turn pick"));
            // Edicao dos valores em lote (06/11/2026): cada linha vira um campo e
            // campo vazio remove a entrada.
            addMenuButton(x + BUTTON_W + COL_GAP, y, BUTTON_W,
                    Component.translatable("screen.tabletoprpg.initiative.values"), canAct,
                    () -> this.minecraft.setScreen(new InitiativeValuesScreen(this)));
            y += BUTTON_H + GAP;

            addMenuButton(x, y, BUTTON_W, showPanelLabel(), true, () -> {
                InitiativeClientState.toggleShowPanel();
                rebuildWidgets();
            });
            addMenuButton(x + BUTTON_W + COL_GAP, y, BUTTON_W, Component.translatable("screen.tabletoprpg.initiative.hud_edit"), true, () -> {
                // Editar ja liga a janela: sem isso a tela de edicao abriria com a HUD
                // desligada e o jogador nao veria o resultado ao voltar ao jogo.
                InitiativeClientState.setShowPanel(true);
                this.minecraft.setScreen(new InitiativeHudScreen(this));
            });
            y += BUTTON_H + GAP;

            addMenuButton(x, y, PANEL_W - 20, Component.translatable("screen.tabletoprpg.initiative.configure"),
                    true, () -> this.minecraft.setScreen(new InitiativeConfigScreen(this)));
            y += BUTTON_H + GAP;

            y = addMoveRow(y);
        } else {
            addMenuButton(x, y, BUTTON_W, Component.translatable("screen.tabletoprpg.initiative.roll"), canAct,
                    () -> sendCommand("rpg initiative roll"));
            addMenuButton(x + BUTTON_W + COL_GAP, y, BUTTON_W, showPanelLabel(), true, () -> {
                InitiativeClientState.toggleShowPanel();
                rebuildWidgets();
            });
            y += BUTTON_H + GAP;

            addMenuButton(x, y, PANEL_W - 20, Component.translatable("screen.tabletoprpg.initiative.hud_edit"),
                    true, () -> {
                        InitiativeClientState.setShowPanel(true);
                        this.minecraft.setScreen(new InitiativeHudScreen(this));
                    });
            y += BUTTON_H + GAP;
        }
        listTop = y + GAP;
        scroll = Math.min(scroll, maxScroll());
        addRowButtons();
        addRenderableWidget(Button.builder(Component.translatable("screen.tabletoprpg.initiative.back"),
                        b -> this.minecraft.setScreen(parent))
                .bounds(width / 2 - 100, height - 28, 200, BUTTON_H)
                .build());
    }

    /**
     * Botoes por linha: subir (▲), descer (▼) e remover (X). O desenho da lista e
     * feito em {@link #drawEntries}, que usa o mesmo passo vertical; mudar um sem o
     * outro desalinha os cliques.
     *
     * <p>A primeira linha nao sobe e a ultima nao desce: as posicoes do comando
     * {@code /rpg initiative move} sao 1-based, entao subir a linha {@code i} e
     * mandar {@code i + 1} para {@code i}.
     */
    private void addRowButtons() {
        if (!isMaster()) {
            return;
        }
        List<InitiativeSync.Entry> entries = InitiativeClientState.entries();
        int right = width / 2 + PANEL_W / 2 - 10;
        int rowY = listTop + font.lineHeight + 6;
        int last = Math.min(entries.size(), scroll + visibleRows());
        for (int i = scroll; i < last; i++) {
            InitiativeSync.Entry entry = entries.get(i);
            int position = i + 1;
            Button up = Button.builder(Component.literal("▲"),
                            b -> sendCommand("rpg initiative move " + position + " " + (position - 1)))
                    .bounds(right - 46, rowY - 2, 14, 14)
                    .build();
            up.active = allowed && i > 0;
            addRenderableWidget(up);
            Button down = Button.builder(Component.literal("▼"),
                            b -> sendCommand("rpg initiative move " + position + " " + (position + 1)))
                    .bounds(right - 30, rowY - 2, 14, 14)
                    .build();
            down.active = allowed && i < entries.size() - 1;
            addRenderableWidget(down);
            Button remove = Button.builder(Component.literal("X"),
                            b -> sendCommand("rpg initiative remove " + entry.name()))
                    .bounds(right - 14, rowY - 2, 14, 14)
                    .build();
            remove.active = allowed;
            addRenderableWidget(remove);
            rowY += font.lineHeight + 4;
        }
    }

    private void openPicker(InitiativePickerScreen.Action action) {
        if (this.minecraft != null) {
            this.minecraft.setScreen(new InitiativePickerScreen(this, action));
        }
    }

    @Override
    public void tick() {
        // Rotulo Comecar/Terminar, botoes por linha e o conjunto de botoes de
        // Mestre dependem do estado sincronizado.
        if (isMaster() != builtMaster
                || InitiativeClientState.isCollecting() != builtCollecting
                || InitiativeClientState.round() != builtRound
                || InitiativeClientState.entries().hashCode() != builtEntriesHash) {
            rebuildWidgets();
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        drawPanel(graphics, width, height);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);
        drawCentered(graphics, getTitle().getString(), 18, 0xFFFFD700);
        drawCentered(graphics, stateLabel().getString(), 32, 0xFFDDDDDD);
        // Rodada (04/11/2026): so com a coleta aberta, que e quando ela existe.
        if (InitiativeClientState.isCollecting()) {
            drawCentered(graphics, Component.translatable("screen.tabletoprpg.initiative.round",
                    InitiativeClientState.round()).getString(), 44, 0xFFFFD700);
        }
        if (isMaster() && moveNoteY > 0) {
            String hint = Component.translatable("screen.tabletoprpg.initiative.move_hint",
                    InitiativeClientState.entries().size()).getString();
            drawCentered(graphics, hint, moveNoteY, moveNoteError ? 0xFFFF5555 : 0xFFAAAAAA);
        }
        int bottom = height - 36;
        if (listTop + font.lineHeight >= bottom) {
            return;
        }
        int left = width / 2 - PANEL_W / 2 + 10;
        int right = width / 2 + PANEL_W / 2 - 10;
        graphics.drawString(font, Component.translatable("screen.tabletoprpg.initiative.list_title").getString(),
                left, listTop, 0xFFFFD700, false);
        List<InitiativeSync.Entry> entries = InitiativeClientState.entries();
        int first = Math.min(scroll, maxScroll());
        drawEntries(graphics, font, left, listTop + font.lineHeight + 6, bottom,
                right - left - (isMaster() ? 54 : 0), entries, first);
        drawScrollBar(graphics, left, listTop + font.lineHeight + 6, bottom, first);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxScroll() > 0 && mouseY >= listTop && mouseY < height - 36) {
            int next = Math.max(0, Math.min(maxScroll(), scroll + (scrollY < 0 ? 1 : -1)));
            if (next != scroll) {
                scroll = next;
                // Os botoes de cada linha (▲▼X) sao widgets: precisam ser remontados
                // para acompanhar as linhas que a rolagem trouxe.
                rebuildWidgets();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /** Altura de uma linha da lista; os botoes e o desenho usam o mesmo passo. */
    private int rowStep() {
        return font.lineHeight + 4;
    }

    /** Linhas que cabem entre o titulo da lista e o botao Voltar. */
    private int visibleRows() {
        int top = listTop + font.lineHeight + 6;
        int bottom = height - 36;
        return Math.max(0, (bottom - top) / rowStep());
    }

    private int maxScroll() {
        return Math.max(0, InitiativeClientState.entries().size() - visibleRows());
    }

    /** Barra fina a esquerda da lista, so quando ha mais entradas do que linhas. */
    private void drawScrollBar(GuiGraphics graphics, int x, int top, int bottom, int first) {
        int entries = InitiativeClientState.entries().size();
        int area = bottom - top;
        int max = maxScroll();
        if (area <= 0 || entries == 0 || max <= 0) {
            return;
        }
        int barX = x - 6;
        graphics.fill(barX, top, barX + 3, bottom, 0x40FFFFFF);
        int thumbH = Math.max(10, area * visibleRows() / entries);
        int travel = Math.max(0, area - thumbH);
        int thumbY = top + travel * first / max;
        graphics.fill(barX, thumbY, barX + 3, thumbY + thumbH, 0xFFFFD700);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Fundo escuro e painel central, sem textura de pergaminho. */
    private static void drawPanel(GuiGraphics graphics, int screenW, int screenH) {
        graphics.fill(0, 0, screenW, screenH, 0x99000000);
        int x = screenW / 2 - PANEL_W / 2;
        graphics.fill(x - 1, 9, x + PANEL_W + 1, screenH - 9, 0xFF555555);
        graphics.fill(x, 10, x + PANEL_W, screenH - 10, 0xE0101010);
    }

    private void drawCentered(GuiGraphics graphics, String text, int y, int color) {
        graphics.drawString(font, text, width / 2 - font.width(text) / 2, y, color, false);
    }

    /**
     * Lista de participantes, do maior valor para o menor, cortada no limite de baixo.
     *
     * <p>O numero a esquerda e a posicao (1-based) usada por {@code /rpg initiative move}
     * e pelas setas ▲▼, por isso fica num gutter fixo em dourado.
     */
    private static void drawEntries(GuiGraphics graphics, Font font, int x, int y, int bottom, int textWidth,
                                    List<InitiativeSync.Entry> entries, int from) {
        if (entries.isEmpty()) {
            graphics.drawString(font, Component.translatable("message.tabletoprpg.initiative.empty").getString(),
                    x, y, 0xFFAAAAAA, false);
            return;
        }
        int lineY = y;
        for (int i = from; i < entries.size(); i++) {
            if (lineY + font.lineHeight > bottom) {
                break;
            }
            InitiativeSync.Entry entry = entries.get(i);
            graphics.drawString(font, (i + 1) + ".", x, lineY, 0xFFFFD700, false);
            String line = Component.translatable("message.tabletoprpg.initiative.list_line",
                    entry.name(), entry.value()).getString();
            graphics.drawString(font, font.plainSubstrByWidth(line, textWidth - NUMBER_W),
                    x + NUMBER_W, lineY, 0xFFFFFFFF, false);
            lineY += font.lineHeight + 4;
        }
    }

    private Component stateLabel() {
        if (!allowed) {
            return Component.translatable("screen.tabletoprpg.initiative.state_free");
        }
        return Component.translatable(InitiativeClientState.isCollecting()
                ? "screen.tabletoprpg.initiative.state_open"
                : "screen.tabletoprpg.initiative.state_closed");
    }

    private Component showPanelLabel() {
        return Component.translatable("screen.tabletoprpg.initiative.show_panel",
                InitiativeClientState.isShowPanel() ? "ON" : "OFF");
    }

    /** Adiciona um botao na coluna, na posicao e largura pedidas. */
    private void addMenuButton(int x, int y, int w, Component label, boolean enabled, Runnable onPress) {
        Button button = Button.builder(label, b -> onPress.run())
                .bounds(x, y, w, BUTTON_H)
                .build();
        button.active = enabled;
        addRenderableWidget(button);
    }

    /**
     * Linha "de / para / Mover": manda uma entrada direto para uma posicao, sem
     * clicar nas setas varias vezes. As posicoes sao 1-based, como no comando
     * {@code /rpg initiative move}; faixa e permissao continuam validadas no servidor.
     */
    private int addMoveRow(int y) {
        int boxW = 40;
        int buttonW = 68;
        int left = width / 2 - (boxW * 2 + GAP * 2 + buttonW) / 2;
        EditBox from = positionBox(left, y, boxW, "screen.tabletoprpg.initiative.move_from");
        EditBox to = positionBox(left + boxW + GAP, y, boxW, "screen.tabletoprpg.initiative.move_to");
        addRenderableWidget(from);
        addRenderableWidget(to);
        Button move = Button.builder(Component.translatable("screen.tabletoprpg.initiative.move"),
                        b -> moveEntry(from, to))
                .bounds(left + (boxW + GAP) * 2, y, buttonW, BUTTON_H)
                .build();
        move.active = allowed;
        addRenderableWidget(move);
        moveNoteY = y + BUTTON_H + 2;
        return moveNoteY + font.lineHeight + 4;
    }

    private EditBox positionBox(int x, int y, int width, String hintKey) {
        EditBox box = new EditBox(font, x, y, width, BUTTON_H, Component.translatable(hintKey));
        box.setMaxLength(3);
        box.setHint(Component.translatable(hintKey));
        box.setFilter(InitiativeScreen::isPositionText);
        return box;
    }

    /** Envia o {@code move} dos campos; posicao fora da faixa so acende a dica em vermelho. */
    private void moveEntry(EditBox from, EditBox to) {
        int size = InitiativeClientState.entries().size();
        int fromPos = parsePosition(from.getValue(), size);
        int toPos = parsePosition(to.getValue(), size);
        if (fromPos == 0 || toPos == 0) {
            moveNoteError = true;
            return;
        }
        if (fromPos != toPos) {
            sendCommand("rpg initiative move " + fromPos + " " + toPos);
        }
        from.setValue("");
        to.setValue("");
        moveNoteError = false;
    }

    /** Posicao 1-based dentro de {@code 1..size}, ou 0 para texto vazio/invalido/fora da faixa. */
    private static int parsePosition(String text, int size) {
        try {
            int value = Integer.parseInt(text.trim());
            return value >= 1 && value <= size ? value : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean isPositionText(String text) {
        return text.chars().allMatch(Character::isDigit);
    }

    private void sendCommand(String command) {
        if (this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.connection.sendCommand(command);
        }
    }
}
