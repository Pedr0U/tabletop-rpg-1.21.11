package com.pedro.tabletoprpg.client;

import com.pedro.tabletoprpg.InitiativeSync;
import com.pedro.tabletoprpg.RpgNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Menu de dar turno a uma entrada da iniciativa (04/11/2026).
 *
 * <p>Abre pelo botao "Dar turno" da tela de iniciativa e pelo botao "Nao" da
 * confirmacao de proximo turno no chat. A lista e a propria iniciativa, que o
 * cliente ja tem sincronizada, na ordem em que ela vale; o clique manda o nome, o
 * tipo e a posicao ao servidor, que confere a posicao de novo antes de dar a vez
 * (a lista pode ter mudado enquanto o menu estava aberto), com o nome como
 * reserva. Mob tambem aparece: dar o turno a um mob
 * e dar a vez ao Mestre, como manda a regra da iniciativa.
 */
public class InitiativeTurnPickerScreen extends Screen {

    private static final int PANEL_W = 280;
    private static final int TOP = 48;
    private static final int ROW_H = 18;
    private static final int SIDE = 10;
    private static final int BOTTOM_RESERVED = 36;

    private final Screen parent;
    private final List<InitiativeSync.Entry> items = new ArrayList<>();
    private int scroll;
    /** Hash das entradas usadas para montar {@link #items}; muda quando o servidor reenvia. */
    private int builtEntriesHash;

    public InitiativeTurnPickerScreen(Screen parent) {
        super(Component.translatable("screen.tabletoprpg.initiative.turn_picker_title"));
        this.parent = parent;
        refreshItems();
    }

    @Override
    public void tick() {
        // O servidor pode reenviar a iniciativa com o menu aberto.
        if (InitiativeClientState.entries().hashCode() != builtEntriesHash) {
            refreshItems();
        }
    }

    private void refreshItems() {
        List<InitiativeSync.Entry> all = InitiativeClientState.entries();
        items.clear();
        items.addAll(all);
        builtEntriesHash = all.hashCode();
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.translatable("screen.tabletoprpg.initiative.back"),
                        b -> close())
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
        if (items.isEmpty()) {
            graphics.drawString(font, Component.translatable("message.tabletoprpg.initiative.empty")
                    .getString(), left, TOP, 0xFFAAAAAA, false);
            return;
        }

        int visible = visibleRows();
        int maxScroll = Math.max(0, items.size() - visible);
        scroll = Math.min(scroll, maxScroll);
        int y = TOP;
        for (int i = scroll; i < Math.min(items.size(), scroll + visible); i++) {
            InitiativeSync.Entry entry = items.get(i);
            boolean hover = mouseX >= left - 4 && mouseX < right + 4 && mouseY >= y && mouseY < y + ROW_H - 2;
            if (hover) {
                graphics.fill(left - 4, y - 1, right + 4, y + ROW_H - 3, 0x33FFFFFF);
            }
            // Mesmo formato da lista de iniciativa: posicao, nome e valor.
            String line = Component.translatable("screen.tabletoprpg.initiative.turn_picker_line",
                    i + 1, entry.name(), entry.value()).getString();
            graphics.drawString(font, font.plainSubstrByWidth(line, right - left), left, y + 3,
                    entry.mob() ? 0xFFCCCCCC : 0xFFFFFFFF, false);
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
                InitiativeSync.Entry entry = items.get(index);
                ClientPlayNetworking.send(new RpgNetworking.TurnGivePayload(
                        entry.name(), entry.mob(), index));
                close();
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
        return Math.max(0, items.size() - visibleRows());
    }

    /** Indice da entrada sob o mouse, ou -1. */
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
        return index < items.size() ? index : -1;
    }

    private void close() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }
}
