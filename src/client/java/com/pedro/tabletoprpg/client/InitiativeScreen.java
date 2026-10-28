package com.pedro.tabletoprpg.client;

import com.pedro.tabletoprpg.InitiativeSync;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Menu de iniciativa, aberto pelo botao "Iniciativa" do menu R.
 *
 * <p>Os botoes ficam em coluna e a lista de iniciativa aparece logo abaixo, sem
 * botao para abrir: e so visual nesta fase. Mestre: comecar/terminar, adicionar,
 * rolar, mostrar e configurar (desabilitado). Jogador: rolar e mostrar. Comecar,
 * terminar e mostrar executam direto; rolar e adicionar so preenchem o comando no
 * chat. Fora do modo Investigacao/Combate os botoes de acao ficam desabilitados.
 */
public class InitiativeScreen extends Screen {

    private static final int PANEL_W = 280;
    private static final int BUTTON_W = 200;
    private static final int BUTTON_H = 20;
    private static final int TOP = 56;
    private static final int GAP = 6;

    private final Screen parent;
    private final boolean isMaster;
    private final boolean allowed;
    private boolean builtCollecting;
    /** Y do titulo da lista, logo abaixo da ultima linha de botoes. */
    private int listTop;

    /**
     * @param allowed {@code false} no modo FREE: iniciativa so funciona em
     *                Investigacao e Combate, entao os botoes de acao ficam desabilitados
     */
    public InitiativeScreen(Screen parent, boolean isMaster, boolean allowed) {
        super(Component.translatable("screen.tabletoprpg.initiative.title"));
        this.parent = parent;
        this.isMaster = isMaster;
        this.allowed = allowed;
    }

    @Override
    protected void init() {
        builtCollecting = InitiativeClientState.isCollecting();
        int x = width / 2 - BUTTON_W / 2;
        int y = TOP;
        if (isMaster) {
            y = addMenuButton(x, y, Component.translatable(builtCollecting
                    ? "screen.tabletoprpg.initiative.end"
                    : "screen.tabletoprpg.initiative.start"), allowed, () -> sendCommand(builtCollecting
                    ? "rpg initiative end"
                    : "rpg initiative start"));
            y = addMenuButton(x, y, Component.translatable("screen.tabletoprpg.initiative.add"), allowed,
                    () -> openChat("/rpg initiative add "));
            y = addMenuButton(x, y, Component.translatable("screen.tabletoprpg.initiative.roll"), allowed,
                    () -> openChat("/rpg initiative roll"));
        } else {
            y = addMenuButton(x, y, Component.translatable("screen.tabletoprpg.initiative.roll"), allowed,
                    () -> openChat("/rpg initiative roll"));
        }
        y = addMenuButton(x, y, showPanelLabel(), true, () -> {
            InitiativeClientState.toggleShowPanel();
            rebuildWidgets();
        });
        if (isMaster) {
            y = addMenuButton(x, y, Component.translatable("screen.tabletoprpg.initiative.configure"), false, () -> {
            });
        }
        listTop = y + GAP;
        addRenderableWidget(Button.builder(Component.translatable("screen.tabletoprpg.initiative.back"),
                        b -> this.minecraft.setScreen(parent))
                .bounds(x, height - 28, BUTTON_W, BUTTON_H)
                .build());
    }

    @Override
    public void tick() {
        // O rotulo Comecar/Terminar depende do estado: recria os botoes quando ele muda.
        if (InitiativeClientState.isCollecting() != builtCollecting) {
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
        int bottom = height - 36;
        if (listTop + font.lineHeight >= bottom) {
            return;
        }
        int left = width / 2 - PANEL_W / 2 + 10;
        graphics.drawString(font, Component.translatable("screen.tabletoprpg.initiative.list_title").getString(),
                left, listTop, 0xFFFFD700, false);
        drawEntries(graphics, font, left, listTop + font.lineHeight + 6, bottom,
                InitiativeClientState.entries());
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

    /** Lista de participantes, do maior valor para o menor, cortada no limite de baixo. */
    private static void drawEntries(GuiGraphics graphics, Font font, int x, int y, int bottom,
                                    List<InitiativeSync.Entry> entries) {
        if (entries.isEmpty()) {
            graphics.drawString(font, Component.translatable("message.tabletoprpg.initiative.empty").getString(),
                    x, y, 0xFFAAAAAA, false);
            return;
        }
        int lineY = y;
        for (InitiativeSync.Entry entry : entries) {
            if (lineY + font.lineHeight > bottom) {
                break;
            }
            String line = Component.translatable("message.tabletoprpg.initiative.list_line",
                    entry.name(), entry.value()).getString();
            graphics.drawString(font, line, x, lineY, 0xFFFFFFFF, false);
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

    /** Adiciona um botao da coluna e devolve a proxima posicao Y. */
    private int addMenuButton(int x, int y, Component label, boolean enabled, Runnable onPress) {
        Button button = Button.builder(label, b -> onPress.run())
                .bounds(x, y, BUTTON_W, BUTTON_H)
                .build();
        button.active = enabled;
        addRenderableWidget(button);
        return y + BUTTON_H + GAP;
    }

    private void sendCommand(String command) {
        if (this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.connection.sendCommand(command);
        }
    }

    private void openChat(String text) {
        if (this.minecraft != null) {
            this.minecraft.setScreen(new ChatScreen(text, true));
        }
    }
}
