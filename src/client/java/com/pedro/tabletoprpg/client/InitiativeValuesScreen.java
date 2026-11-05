package com.pedro.tabletoprpg.client;

import com.pedro.tabletoprpg.InitiativeSync;
import com.pedro.tabletoprpg.RpgNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Edicao dos valores da iniciativa (06/11/2026).
 *
 * <p>Aberta pelo botao "Editar valores" da tela de iniciativa, so para o Mestre.
 * Cada linha mostra o nome e um campo com o valor atual: digitar outro numero troca
 * o valor, <b>deixar o campo vazio remove a entrada</b>. O <b>Salvar</b> manda so as
 * linhas que mudaram, com posicao + nome; o servidor confere se a posicao ainda
 * aponta para aquele nome antes de aplicar (a lista pode ter mudado com a tela
 * aberta).
 *
 * <p>A lista aqui e um retrato tirado ao abrir. O texto digitado vive em
 * {@link #texts} por posicao, entao rolar a lista remonta os campos sem perder o
 * que ja foi digitado.
 */
public class InitiativeValuesScreen extends Screen {

    private static final int PANEL_W = 360;
    private static final int LIST_TOP = 54;
    private static final int ROW_H = 24;
    private static final int SIDE = 14;
    private static final int BOX_W = 60;

    private final Screen parent;
    private final List<InitiativeSync.Entry> entries = new ArrayList<>();
    /** Texto por posicao da lista; sobrevive ao rebuild da rolagem. */
    private final Map<Integer, String> texts = new HashMap<>();

    private int scroll;
    private int panelX;
    private int panelY;
    private int panelW;

    public InitiativeValuesScreen(Screen parent) {
        super(Component.translatable("screen.tabletoprpg.initiative.values_title"));
        this.parent = parent;
        this.entries.addAll(InitiativeClientState.entries());
        for (int i = 0; i < entries.size(); i++) {
            texts.put(i, String.valueOf(entries.get(i).value()));
        }
    }

    @Override
    protected void init() {
        panelW = Math.min(PANEL_W, width - 20);
        panelX = (width - panelW) / 2;
        panelY = 10;
        scroll = Math.max(0, Math.min(scroll, maxScroll()));

        int boxX = panelX + panelW - SIDE - BOX_W;
        int last = Math.min(entries.size(), scroll + visibleRows());
        for (int i = scroll; i < last; i++) {
            int position = i;
            int y = LIST_TOP + (i - scroll) * ROW_H + 2;
            EditBox box = new EditBox(font, boxX, y, BOX_W, 18,
                    Component.translatable("screen.tabletoprpg.initiative.values_box"));
            box.setMaxLength(4);
            box.setFilter(InitiativeValuesScreen::isValueText);
            box.setValue(texts.getOrDefault(position, ""));
            box.setResponder(text -> texts.put(position, text));
            addRenderableWidget(box);
        }

        int buttonW = (panelW - SIDE * 2 - 8) / 2;
        addRenderableWidget(Button.builder(Component.translatable("screen.tabletoprpg.initiative.values_save"),
                        b -> save())
                .bounds(panelX + SIDE, height - 28, buttonW, 20)
                .build());
        addRenderableWidget(Button.builder(Component.translatable("screen.tabletoprpg.initiative.back"),
                        b -> close())
                .bounds(panelX + SIDE + buttonW + 8, height - 28, buttonW, 20)
                .build());
    }

    private static boolean isValueText(String text) {
        return text.matches("-?[0-9]{0,4}");
    }

    /** Linhas que cabem entre o cabecalho e os botoes. */
    private int visibleRows() {
        int bottom = height - 36;
        return Math.max(0, (bottom - LIST_TOP) / ROW_H);
    }

    private int maxScroll() {
        return Math.max(0, entries.size() - visibleRows());
    }

    /**
     * Monta a edicao com o que mudou. Campo vazio remove; texto que nao vira
     * numero (por exemplo "-") e ignorado em vez de apagar a entrada.
     */
    private void save() {
        List<RpgNetworking.InitiativeEntryEdit> edits = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            InitiativeSync.Entry entry = entries.get(i);
            String raw = texts.getOrDefault(i, String.valueOf(entry.value())).trim();
            if (raw.equals(String.valueOf(entry.value()))) {
                continue;
            }
            if (raw.isEmpty()) {
                edits.add(new RpgNetworking.InitiativeEntryEdit(i, entry.name(), null));
                continue;
            }
            try {
                edits.add(new RpgNetworking.InitiativeEntryEdit(i, entry.name(), Integer.parseInt(raw)));
            } catch (NumberFormatException ignored) {
                // Campo incompleto nao altera nada.
            }
        }
        if (!edits.isEmpty()) {
            ClientPlayNetworking.send(new RpgNetworking.InitiativeSetValuesPayload(edits));
        }
        close();
    }

    private void close() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxScroll() > 0 && mouseY >= LIST_TOP && mouseY < height - 36) {
            int next = Math.max(0, Math.min(maxScroll(), scroll + (scrollY < 0 ? 1 : -1)));
            if (next != scroll) {
                scroll = next;
                // Os campos sao widgets: precisam ser remontados para as linhas que
                // a rolagem trouxe. Os textos ficam em {@link #texts}.
                rebuildWidgets();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0x99000000);
        graphics.fill(panelX - 1, panelY - 1, panelX + panelW + 1, height - 9, 0xFF555555);
        graphics.fill(panelX, panelY, panelX + panelW, height - 10, 0xF0141414);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);
        String title = getTitle().getString();
        graphics.drawString(font, title, width / 2 - font.width(title) / 2, 18, 0xFFFFD700, false);

        int left = panelX + SIDE;
        if (entries.isEmpty()) {
            graphics.drawString(font, Component.translatable("message.tabletoprpg.initiative.empty").getString(),
                    left, LIST_TOP + 4, 0xFFAAAAAA, false);
            return;
        }
        String hint = Component.translatable("screen.tabletoprpg.initiative.values_hint").getString();
        graphics.drawString(font, hint, width / 2 - font.width(hint) / 2, 32, 0xFFAAAAAA, false);

        int nameWidth = panelW - SIDE * 2 - BOX_W - 8;
        int last = Math.min(entries.size(), scroll + visibleRows());
        for (int i = scroll; i < last; i++) {
            InitiativeSync.Entry entry = entries.get(i);
            int y = LIST_TOP + (i - scroll) * ROW_H + 6;
            String line = (i + 1) + ". " + entry.name();
            graphics.drawString(font, font.plainSubstrByWidth(line, nameWidth), left, y,
                    entry.mob() ? 0xFFFFD0A0 : 0xFFFFFFFF, false);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
