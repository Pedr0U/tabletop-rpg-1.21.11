package com.pedro.tabletoprpg.client;

import com.pedro.tabletoprpg.InitiativeSync;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Tela de "Atrasar Turno" (Parte 4, 03/11/2026).
 *
 * <p>O jogador com a vez escolhe a posicao para onde vai descer na iniciativa. So
 * aparecem posicoes posteriores a dele: a lista e montada a partir da copia
 * sincronizada da iniciativa, e o servidor valida de novo antes de mover.
 *
 * <p>Um clique envia {@code /rpg initiative delay <posicao>} e fecha a tela; o
 * servidor move a entrada e encerra a vez na hora.
 */
public class InitiativeDelayScreen extends Screen {

    private static final int PANEL_W = 280;
    private static final int TOP = 48;
    private static final int ROW_H = 18;
    private static final int SIDE = 10;
    private static final int BOTTOM_RESERVED = 36;
    /** Largura reservada para o numero da posicao na linha. */
    private static final int NUMBER_W = 24;

    private final Screen parent;
    /** Posicoes 1-based para onde o jogador pode ir (posteriores a dele). */
    private final List<Integer> positions = new ArrayList<>();
    private int scroll;
    /** Hash das entradas usadas para montar {@link #positions}; muda quando o servidor reenvia. */
    private int builtHash;
    /** Indice 0-based da entrada do proprio jogador, ou -1. */
    private int own = -1;

    public InitiativeDelayScreen(Screen parent) {
        super(Component.translatable("screen.tabletoprpg.initiative.delay_title"));
        this.parent = parent;
        refreshItems();
        // A lista pode ter mudado neste turno: pede o estado fresco ao abrir.
        if (Minecraft.getInstance().getConnection() != null) {
            ClientPlayNetworking.send(new InitiativeSync.RequestPayload());
        }
    }

    @Override
    public void tick() {
        if (InitiativeClientState.entries().hashCode() != builtHash) {
            refreshItems();
        }
    }

    /** Recalcula as posicoes validas a partir das entradas sincronizadas. */
    private void refreshItems() {
        List<InitiativeSync.Entry> entries = InitiativeClientState.entries();
        own = ownIndex(entries);
        positions.clear();
        if (own >= 0) {
            for (int pos = own + 2; pos <= entries.size(); pos++) {
                positions.add(pos);
            }
        }
        scroll = Math.min(scroll, Math.max(0, positions.size() - visibleRows()));
        builtHash = entries.hashCode();
    }

    /** Indice da primeira entrada de jogador com o nome do cliente. */
    private static int ownIndex(List<InitiativeSync.Entry> entries) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return -1;
        }
        String name = minecraft.player.getName().getString();
        for (int i = 0; i < entries.size(); i++) {
            InitiativeSync.Entry entry = entries.get(i);
            if (!entry.mob() && entry.name().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.translatable("screen.tabletoprpg.initiative.back"),
                        b -> onClose())
                .bounds(width / 2 - 100, height - 28, 200, 20)
                .build());
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0x99000000);
        int x = width / 2 - PANEL_W / 2;
        graphics.fill(x - 1, 9, x + PANEL_W + 1, height - 9, 0xFF555555);
        graphics.fill(x, 10, x + PANEL_W, height - 10, 0xE0101010);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);
        String title = getTitle().getString();
        graphics.drawString(font, title, width / 2 - font.width(title) / 2, 18, 0xFFFFD700, false);

        int left = width / 2 - PANEL_W / 2 + SIDE;
        int right = width / 2 + PANEL_W / 2 - SIDE;
        if (own < 0) {
            graphics.drawString(font, Component.translatable("screen.tabletoprpg.initiative.delay_no_entry")
                    .getString(), left, TOP, 0xFFAAAAAA, false);
            return;
        }
        if (positions.isEmpty()) {
            graphics.drawString(font, Component.translatable("screen.tabletoprpg.initiative.delay_no_later")
                    .getString(), left, TOP, 0xFFAAAAAA, false);
            return;
        }

        List<InitiativeSync.Entry> entries = InitiativeClientState.entries();
        int visible = visibleRows();
        int maxScroll = maxScroll();
        scroll = Math.min(scroll, maxScroll);
        int y = TOP;
        for (int i = scroll; i < Math.min(positions.size(), scroll + visible); i++) {
            int pos = positions.get(i);
            boolean hover = mouseX >= left - 4 && mouseX < right + 4 && mouseY >= y && mouseY < y + ROW_H - 2;
            if (hover) {
                graphics.fill(left - 4, y - 1, right + 4, y + ROW_H - 3, 0x33FFFFFF);
            }
            String number = pos + ".";
            graphics.drawString(font, number, left, y + 3, 0xFFFFD700, false);
            String line = "";
            if (pos - 1 < entries.size()) {
                InitiativeSync.Entry entry = entries.get(pos - 1);
                line = entry.mob()
                        ? Component.translatable("screen.tabletoprpg.initiative.picker_mob_line",
                        entry.name()).getString()
                        : entry.name();
            }
            graphics.drawString(font, line, left + NUMBER_W, y + 3, 0xFFFFFFFF, false);
            y += ROW_H;
        }
        if (maxScroll > 0) {
            String hint = Component.translatable("screen.tabletoprpg.initiative.picker_scroll").getString();
            graphics.drawString(font, hint, right - font.width(hint), TOP + visible * ROW_H + 2,
                    0xFFAAAAAA, false);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) {
            int index = itemAt(event.x(), event.y());
            if (index >= 0) {
                int pos = positions.get(index);
                if (this.minecraft != null && this.minecraft.player != null) {
                    this.minecraft.player.connection.sendCommand("rpg initiative delay " + pos);
                }
                onClose();
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseY >= TOP && mouseY < TOP + visibleRows() * ROW_H && maxScroll() > 0) {
            scroll = Math.max(0, Math.min(maxScroll(), scroll + (int) -Math.signum(scrollY)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private int visibleRows() {
        return Math.max(1, (height - BOTTOM_RESERVED - TOP) / ROW_H);
    }

    private int maxScroll() {
        return Math.max(0, positions.size() - visibleRows());
    }

    /** Indice da posicao sob o mouse, ou -1. */
    private int itemAt(double mouseX, double mouseY) {
        int left = width / 2 - PANEL_W / 2 + SIDE;
        int right = width / 2 + PANEL_W / 2 - SIDE;
        if (mouseX < left - 4 || mouseX >= right + 4 || mouseY < TOP) {
            return -1;
        }
        int row = (int) ((mouseY - TOP) / ROW_H);
        if (row < 0 || row >= visibleRows()) {
            return -1;
        }
        int index = scroll + row;
        return index < positions.size() ? index : -1;
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }
}
