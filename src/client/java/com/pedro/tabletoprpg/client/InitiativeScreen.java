package com.pedro.tabletoprpg.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Menu de iniciativa, aberto pelo botao "Iniciativa" do menu R (Fase 3).
 *
 * <p>Mestre: comecar/terminar, adicionar, rolar, lista, mostrar e configurar.
 * Jogador: rolar, mostrar, e a lista somente leitura. Comecar/terminar e a lista
 * executam direto; rolar e adicionar so preenchem o comando no chat, para o jogador
 * completar. "Configurar" aparece desabilitado: ainda nao tem funcao.
 */
public class InitiativeScreen extends Screen {

    private static final int TOP = 56;
    private static final int GAP = 6;

    private final Screen parent;
    private final boolean isMaster;
    private final boolean allowed;
    private boolean builtCollecting;

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
        int x = width / 2 - InitiativeListScreen.BUTTON_W / 2;
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
            y = addMenuButton(x, y, Component.translatable("screen.tabletoprpg.initiative.list"), allowed,
                    () -> this.minecraft.setScreen(new InitiativeListScreen(this)));
        } else {
            y = addMenuButton(x, y, Component.translatable("screen.tabletoprpg.initiative.roll"), allowed,
                    () -> openChat("/rpg initiative roll"));
        }
        y = addMenuButton(x, y, showPanelLabel(), true, () -> {
            InitiativeClientState.toggleShowPanel();
            rebuildWidgets();
        });
        if (isMaster) {
            addMenuButton(x, y, Component.translatable("screen.tabletoprpg.initiative.configure"), false, () -> {
            });
        }
        addRenderableWidget(Button.builder(Component.translatable("screen.tabletoprpg.initiative.back"),
                        b -> this.minecraft.setScreen(parent))
                .bounds(x, height - 28, InitiativeListScreen.BUTTON_W, InitiativeListScreen.BUTTON_H)
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
        InitiativeListScreen.drawPanel(graphics, width, height);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);
        InitiativeListScreen.drawTitle(graphics, font, getTitle().getString(), width, 18);
        InitiativeListScreen.drawTitle(graphics, font, stateLabel().getString(), width, 32);
        if (!isMaster) {
            int bottom = height - 36;
            int y = TOP + 2 * (InitiativeListScreen.BUTTON_H + GAP) + GAP;
            InitiativeListScreen.drawEntries(graphics, font, width / 2 - InitiativeListScreen.PANEL_W / 2 + 10,
                    y, bottom, InitiativeClientState.entries());
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
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
                .bounds(x, y, InitiativeListScreen.BUTTON_W, InitiativeListScreen.BUTTON_H)
                .build();
        button.active = enabled;
        addRenderableWidget(button);
        return y + InitiativeListScreen.BUTTON_H + GAP;
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
