package com.pedro.tabletoprpg.client;

import com.pedro.tabletoprpg.InitiativeSync;
import com.pedro.tabletoprpg.RpgNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Escolha de como a entidade entra na iniciativa (06/11/2026).
 *
 * <p>Aberta pelo clique num candidato do {@link InitiativePickerScreen}. Antes ela
 * mandava o comando e fechava; agora o Mestre escolhe entre <b>rolar da ficha</b>
 * (formula da mesa + formula da ficha, o caminho antigo) e <b>usar um valor dito
 * por ele</b>, que nao rola nada.
 *
 * <p>Quem decide de verdade e o servidor: o payload {@code initiative_add} leva o
 * nome e o valor opcional, e o receptor confere Mestre, modo, coleta e se o nome
 * ja esta na lista.
 */
public class InitiativeAddScreen extends Screen {

    private static final int PANEL_W = 320;
    private static final int PANEL_H = 148;
    private static final int SIDE = 20;

    private final Screen parent;
    private final String name;
    private final boolean mob;

    private EditBox valueBox;
    private Button useButton;
    private int panelX;
    private int panelY;
    private int panelW;

    public InitiativeAddScreen(Screen parent, InitiativeSync.Candidate candidate) {
        super(Component.translatable("screen.tabletoprpg.initiative.add_choice_title", candidate.name()));
        this.parent = parent;
        this.name = candidate.name();
        this.mob = candidate.mob();
    }

    @Override
    protected void init() {
        panelW = Math.min(PANEL_W, width - 20);
        panelX = (width - panelW) / 2;
        panelY = Math.max(10, (height - PANEL_H) / 2);
        int contentW = panelW - SIDE * 2;

        addRenderableWidget(Button.builder(Component.translatable("screen.tabletoprpg.initiative.add_roll_sheet"),
                        b -> send(null))
                .bounds(panelX + SIDE, panelY + 44, contentW, 20)
                .build());

        valueBox = new EditBox(font, panelX + SIDE, panelY + 84, 90, 20,
                Component.translatable("screen.tabletoprpg.initiative.add_value_hint"));
        valueBox.setMaxLength(4);
        // Numero inteiro, com sinal opcional: iniciativa pode ser negativa.
        valueBox.setFilter(text -> text.matches("-?[0-9]{0,4}"));
        valueBox.setHint(Component.translatable("screen.tabletoprpg.initiative.add_value_hint"));
        valueBox.setResponder(text -> updateUseButton());
        addRenderableWidget(valueBox);

        useButton = Button.builder(Component.translatable("screen.tabletoprpg.initiative.add_use_value"),
                        b -> send(parseValue()))
                .bounds(panelX + SIDE + 98, panelY + 84, contentW - 98, 20)
                .build();
        addRenderableWidget(useButton);

        addRenderableWidget(Button.builder(Component.translatable("screen.tabletoprpg.initiative.back"),
                        b -> close())
                .bounds(panelX + SIDE, panelY + PANEL_H - 30, contentW, 20)
                .build());
        updateUseButton();
    }

    private void updateUseButton() {
        if (useButton != null) {
            useButton.active = parseValue() != null;
        }
    }

    /** Valor digitado, ou {@code null} quando o campo esta vazio ou incompleto. */
    private Integer parseValue() {
        String text = valueBox == null ? "" : valueBox.getValue().trim();
        if (text.isEmpty() || "-".equals(text)) {
            return null;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void send(Integer value) {
        ClientPlayNetworking.send(new RpgNetworking.InitiativeAddPayload(name, value));
        close();
    }

    private void close() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0x99000000);
        graphics.fill(panelX - 1, panelY - 1, panelX + panelW + 1, panelY + PANEL_H + 1, 0xFF555555);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + PANEL_H, 0xF0141414);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);
        String title = getTitle().getString();
        graphics.drawString(font, title, width / 2 - font.width(title) / 2, panelY + 9, 0xFFFFD700, false);

        String hint = Component.translatable("screen.tabletoprpg.initiative.add_choice_hint").getString();
        graphics.drawString(font, hint, width / 2 - font.width(hint) / 2, panelY + 26, 0xFFAAAAAA, false);

        String label = Component.translatable(mob
                ? "screen.tabletoprpg.initiative.add_value_label_mob"
                : "screen.tabletoprpg.initiative.add_value_label").getString();
        graphics.drawString(font, label, panelX + SIDE, panelY + 72, 0xFFE8E8E8, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
