package com.pedro.tabletoprpg.client;

import com.pedro.tabletoprpg.InitiativeSync;
import com.pedro.tabletoprpg.RpgNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Lista de nomes que o mestre pode adicionar ou rolar sem digitar comando.
 *
 * <p>Abre a partir do menu de iniciativa. Os candidatos vem do servidor no payload
 * de sincronizacao (jogadores online e mobs elegiveis), porque o cliente nao conhece
 * os mobs com ficha vinculada.
 *
 * <p><b>Add:</b> um clique no nome envia o comando e volta ao menu.
 * <b>Rolar mob:</b> cada linha tem uma caixa de marcacao (06/11/2026); o botao
 * "Rolar selecionados" manda todos os marcados num pacote so, e "Selecionar todos"
 * marca/desmarca a lista inteira. A tela continua aberta depois de rolar, para
 * marcar outro grupo.
 */
public class InitiativePickerScreen extends Screen {

    /** O que fazer com o nome escolhido. */
    public enum Action {
        /** {@code /rpg initiative add <nome>}: jogadores e mobs. */
        ADD,
        /** {@code /rpg initiative roll <nome>}: so mobs. */
        ROLL_MOB
    }

    private static final int PANEL_W = 320;
    private static final int TOP = 48;
    private static final int ROW_H = 18;
    private static final int SIDE = 10;
    private static final int BOTTOM_RESERVED = 36;
    private static final int CHECK = 9;
    /** Mesmo teto de {@code InitiativeCommands.MAX_BULK_ROLL}, que e package-private. */
    private static final int MAX_BULK = 64;

    private final Screen parent;
    private final Action action;
    private final List<InitiativeSync.Candidate> items = new ArrayList<>();
    /** Nomes marcados na lista de rolar mob. */
    private final Set<String> selected = new LinkedHashSet<>();
    private int scroll;
    /** Hash dos candidatos usados para montar {@link #items}; muda quando o servidor reenvia. */
    private int builtCandidatesHash;
    private Button selectAllButton;
    private Button rollButton;

    public InitiativePickerScreen(Screen parent, Action action) {
        super(Component.translatable(action == Action.ADD
                ? "screen.tabletoprpg.initiative.picker_add_title"
                : "screen.tabletoprpg.initiative.picker_roll_title"));
        this.parent = parent;
        this.action = action;
        refreshItems();
        // A lista de candidatos muda sem comando de iniciativa (ficha vinculada a um
        // mob novo, mob que entrou no carrossel): pede o estado fresco ao abrir.
        if (Minecraft.getInstance().getConnection() != null) {
            ClientPlayNetworking.send(new InitiativeSync.RequestPayload());
        }
    }

    @Override
    public void tick() {
        // Resposta do servidor pode chegar depois desta tela abrir.
        if (InitiativeClientState.candidates().hashCode() != builtCandidatesHash) {
            refreshItems();
            updateButtons();
        }
    }

    /** Recalcula as linhas a partir dos candidatos sincronizados. */
    private void refreshItems() {
        List<InitiativeSync.Candidate> all = InitiativeClientState.candidates();
        Set<String> playerNames = new LinkedHashSet<>();
        for (InitiativeSync.Candidate candidate : all) {
            if (!candidate.mob()) {
                playerNames.add(candidate.name());
            }
        }
        List<InitiativeSync.Candidate> next = new ArrayList<>();
        for (InitiativeSync.Candidate candidate : all) {
            if (action == Action.ROLL_MOB) {
                if (candidate.mob()) {
                    next.add(candidate);
                }
            } else if (!candidate.mob() || !playerNames.contains(candidate.name())) {
                // Nome de mob igual ao de um jogador nao e enderecavel: /rpg initiative
                // add resolve o jogador primeiro, entao a linha de mob seria enganosa.
                next.add(candidate);
            }
        }
        items.clear();
        items.addAll(next);
        // Marca so o que ainda esta na lista: o servidor pode remover um mob entre
        // duas sincronizacoes.
        Set<String> remaining = new LinkedHashSet<>();
        for (InitiativeSync.Candidate candidate : items) {
            if (selected.contains(candidate.name())) {
                remaining.add(candidate.name());
            }
        }
        selected.clear();
        selected.addAll(remaining);
        builtCandidatesHash = all.hashCode();
    }

    @Override
    protected void init() {
        if (action == Action.ROLL_MOB) {
            // Tres botoes na mesma linha dentro dos 300 px uteis (06/11/2026):
            // 96 + 6 + 122 + 6 + 60 = 290, sem sobreposicao.
            int left = width / 2 - PANEL_W / 2 + SIDE;
            selectAllButton = Button.builder(selectAllLabel(), b -> toggleAll())
                    .bounds(left, height - 28, 96, 20)
                    .build();
            addRenderableWidget(selectAllButton);
            rollButton = Button.builder(Component.translatable(
                            "screen.tabletoprpg.initiative.picker_roll_selected"),
                            b -> sendSelected())
                    .bounds(left + 102, height - 28, 122, 20)
                    .build();
            addRenderableWidget(rollButton);
            addRenderableWidget(Button.builder(Component.translatable("screen.tabletoprpg.initiative.back"),
                            b -> close())
                    .bounds(left + 230, height - 28, 60, 20)
                    .build());
        } else {
            addRenderableWidget(Button.builder(Component.translatable("screen.tabletoprpg.initiative.back"),
                            b -> close())
                    .bounds(width / 2 - 100, height - 28, 200, 20)
                    .build());
        }
        updateButtons();
    }

    private void updateButtons() {
        if (rollButton != null) {
            rollButton.active = !selected.isEmpty();
        }
        if (selectAllButton != null) {
            selectAllButton.setMessage(selectAllLabel());
        }
    }

    private Component selectAllLabel() {
        boolean all = !items.isEmpty() && selected.size() == items.size();
        return Component.translatable(all
                ? "screen.tabletoprpg.initiative.picker_unselect_all"
                : "screen.tabletoprpg.initiative.picker_select_all");
    }

    /** Marca tudo ou desmarca tudo, conforme o estado atual. */
    private void toggleAll() {
        boolean all = !items.isEmpty() && selected.size() == items.size();
        selected.clear();
        if (!all) {
            for (InitiativeSync.Candidate candidate : items) {
                selected.add(candidate.name());
            }
        }
        updateButtons();
    }

    /** Manda todas as marcadas; a tela continua aberta para marcar outro grupo. */
    private void sendSelected() {
        if (selected.isEmpty()) {
            return;
        }
        ClientPlayNetworking.send(new RpgNetworking.InitiativeRollMobsPayload(List.copyOf(selected)));
        selected.clear();
        updateButtons();
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
            graphics.drawString(font, Component.translatable("screen.tabletoprpg.initiative.picker_empty")
                    .getString(), left, TOP, 0xFFAAAAAA, false);
            return;
        }

        int visible = visibleRows();
        int maxScroll = Math.max(0, items.size() - visible);
        scroll = Math.min(scroll, maxScroll);
        int y = TOP;
        for (int i = scroll; i < Math.min(items.size(), scroll + visible); i++) {
            InitiativeSync.Candidate candidate = items.get(i);
            boolean hover = mouseX >= left - 4 && mouseX < right + 4 && mouseY >= y && mouseY < y + ROW_H - 2
                    && !(action == Action.ADD && inInitiative(candidate.name()));
            if (hover) {
                graphics.fill(left - 4, y - 1, right + 4, y + ROW_H - 3, 0x33FFFFFF);
            }
            int textX = left;
            boolean inList = action == Action.ADD && inInitiative(candidate.name());
            if (action == Action.ROLL_MOB) {
                drawCheckbox(graphics, left, y + 3, selected.contains(candidate.name()));
                textX = left + CHECK + 6;
            }
            String line = candidate.mob()
                    ? Component.translatable("screen.tabletoprpg.initiative.picker_mob_line",
                    candidate.name()).getString()
                    : candidate.name();
            if (inList) {
                // Uma entrada por nome (06/11/2026): quem ja esta na lista aparece
                // apagado e com o aviso, em vez de reentrar em duplicidade.
                line = line + " " + Component.translatable(
                        "screen.tabletoprpg.initiative.picker_in_list").getString();
            }
            graphics.drawString(font, line, textX, y + 3, inList ? 0xFF777777 : 0xFFFFFFFF, false);
            y += ROW_H;
        }
        if (maxScroll > 0) {
            String hint = Component.translatable("screen.tabletoprpg.initiative.picker_scroll").getString();
            graphics.drawString(font, hint, right - font.width(hint), TOP + visible * ROW_H + 2,
                    0xFFAAAAAA, false);
        }
    }

    /** Caixa de marcacao desenhada a mao: sem textura extra e no tamanho do texto. */
    private static void drawCheckbox(GuiGraphics graphics, int x, int y, boolean checked) {
        graphics.fill(x, y, x + CHECK, y + CHECK, 0xFFAAAAAA);
        graphics.fill(x + 1, y + 1, x + CHECK - 1, y + CHECK - 1, 0xFF101010);
        if (checked) {
            graphics.fill(x + 2, y + 2, x + CHECK - 2, y + CHECK - 2, 0xFFFFD700);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) {
            int index = itemAt(event.x(), event.y());
            if (index >= 0) {
                InitiativeSync.Candidate candidate = items.get(index);
                if (action == Action.ROLL_MOB) {
                    // Marcar/desmarcar; rolar e um botao separado, para dar tempo de
                    // escolher varios.
                    if (!selected.remove(candidate.name())) {
                        if (selected.size() < MAX_BULK) {
                            selected.add(candidate.name());
                        }
                    }
                    updateButtons();
                    return true;
                }
                if (inInitiative(candidate.name())) {
                    return true;
                }
                // Escolha antes de entrar: rolar a ficha ou usar o valor do Mestre
                // (06/11/2026). A tela antiga mandava o comando e fechava direto.
                this.minecraft.setScreen(new InitiativeAddScreen(this, candidate));
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    /** Nome que ja esta na iniciativa: nao entra de novo. */
    private static boolean inInitiative(String name) {
        for (InitiativeSync.Entry entry : InitiativeClientState.entries()) {
            if (entry.name().equals(name)) {
                return true;
            }
        }
        return false;
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

    /** Indice do candidato sob o mouse, ou -1. */
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
