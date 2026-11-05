package com.pedro.tabletoprpg.client;

import com.pedro.tabletoprpg.InitiativeSync;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.List;

/**
 * Janela de iniciativa na HUD (03/11/2026).
 *
 * <p>Mostra a mesma lista da tela de iniciativa: icone, nome e valor. A visibilidade
 * vem do interruptor "Mostrar iniciativas" ({@link InitiativeClientState#isShowPanel}),
 * o mesmo da tela de iniciativa, entao o botao dela e o X daqui fecham a mesma coisa.
 *
 * <p>Posicao e tamanho ficam so em memoria neste cliente (decisao do usuario em
 * 03/11/2026). Arrastar, redimensionar e fechar com o mouse acontecem na
 * {@link InitiativeHudScreen}: no jogo o cursor fica capturado no centro da tela, entao
 * a janela e passiva na HUD e editavel numa tela propria.
 */
public final class InitiativeHud {

    static final int TITLE_H = 14;
    static final int ROW_H = 14;
    /** Alca de redimensionar no canto inferior direito (so na tela de edicao). */
    static final int RESIZE = 10;
    static final int MIN_W = 120;
    static final int MIN_H = 54;
    private static final int DEFAULT_W = 190;
    private static final int DEFAULT_H = 124;
    private static final int MARGIN = 8;
    private static final int PAD = 4;
    private static final int ICON = 10;

    private static int x = -1;
    private static int y = -1;
    private static int width = DEFAULT_W;
    private static int height = DEFAULT_H;
    /**
     * Primeira entrada desenhada; a roda do mouse muda na tela de edicao da HUD
     * (06/11/2026). No jogo o cursor fica capturado, entao o resto continua
     * resumido em "+N".
     */
    private static int scroll;

    private InitiativeHud() {
    }

    static int x() {
        return x;
    }

    static int y() {
        return y;
    }

    static int width() {
        return width;
    }

    static int height() {
        return height;
    }

    /** Move a janela, presa dentro da tela atual. */
    static void setPosition(int newX, int newY) {
        setLayout(newX, newY, width, height);
    }

    /** Redimensiona a janela, respeitando o tamanho minimo e a tela atual. */
    static void setSize(int newW, int newH) {
        setLayout(x, y, newW, newH);
    }

    /** Volta ao canto superior direito com o tamanho padrao. */
    static void resetLayout() {
        x = -1;
        y = -1;
        width = DEFAULT_W;
        height = DEFAULT_H;
    }

    private static void setLayout(int newX, int newY, int newW, int newH) {
        Minecraft minecraft = Minecraft.getInstance();
        int screenW = minecraft.getWindow().getGuiScaledWidth();
        int screenH = minecraft.getWindow().getGuiScaledHeight();
        width = Math.max(MIN_W, Math.min(newW, screenW));
        height = Math.max(MIN_H, Math.min(newH, screenH));
        x = Math.max(0, Math.min(newX, screenW - width));
        y = Math.max(0, Math.min(newY, screenH - height));
    }

    /** Primeira posicao valida: canto superior direito, com margem. */
    private static void clampToScreen(Minecraft minecraft) {
        int screenW = minecraft.getWindow().getGuiScaledWidth();
        int screenH = minecraft.getWindow().getGuiScaledHeight();
        if (x < 0 || y < 0) {
            x = screenW - width - MARGIN;
            y = MARGIN;
        }
        width = Math.max(MIN_W, Math.min(width, screenW));
        height = Math.max(MIN_H, Math.min(height, screenH));
        x = Math.max(0, Math.min(x, screenW - width));
        y = Math.max(0, Math.min(y, screenH - height));
    }

    /** Retangulo do X de fechar, no canto direito do titulo. */
    private static int closeLeft() {
        return x + width - TITLE_H;
    }

    private static int closeTop() {
        return y + 2;
    }

    static boolean closeHit(double mouseX, double mouseY) {
        return mouseX >= closeLeft() && mouseX < closeLeft() + TITLE_H - 2
                && mouseY >= closeTop() && mouseY < closeTop() + TITLE_H - 4;
    }

    static boolean resizeHit(double mouseX, double mouseY) {
        return mouseX >= x + width - RESIZE && mouseX < x + width + 1
                && mouseY >= y + height - RESIZE && mouseY < y + height + 1;
    }

    /** Clique na barra de titulo (area de arrastar). */
    static boolean titleHit(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + TITLE_H;
    }

    /** Desenho da HUD: so quando o interruptor esta ligado. */
    public static void render(GuiGraphics graphics) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!InitiativeClientState.isShowPanel() || minecraft.player == null) {
            return;
        }
        clampToScreen(minecraft);
        drawPanel(graphics, minecraft, false);
    }

    /**
     * Desenho compartilhado com a tela de edicao. {@code editing} acrescenta o X de
     * fechar; fora da edicao a janela e apenas de leitura.
     */
    static void drawPanel(GuiGraphics graphics, Minecraft minecraft, boolean editing) {
        clampToScreen(minecraft);
        Font font = minecraft.font;

        graphics.fill(x - 1, y - 1, x + width + 1, y + height + 1, 0xFF4A4A4A);
        graphics.fill(x, y, x + width, y + height, 0xD0101010);
        graphics.fill(x, y, x + width, y + TITLE_H, 0xF0202020);
        graphics.fill(x, y + TITLE_H, x + width, y + TITLE_H + 1, 0xFFB8912E);

        String title = Component.translatable("screen.tabletoprpg.initiative.hud_title").getString();
        graphics.drawString(font, title, x + PAD, y + 3, 0xFFFFD700, false);
        // Rodada (04/11/2026): no canto direito da barra de titulo, antes do X.
        if (InitiativeClientState.isCollecting()) {
            String round = Component.translatable("screen.tabletoprpg.initiative.round",
                    InitiativeClientState.round()).getString();
            int roundX = closeLeft() - 4 - font.width(round);
            graphics.drawString(font, round, roundX, y + 3, 0xFFDDDDDD, false);
        }
        if (editing) {
            graphics.drawString(font, "x", closeLeft() + 3, closeTop(), 0xFFFF8080, false);
        }

        int top = y + TITLE_H + 3;
        int bottom = y + height - PAD;
        if (!InitiativeClientState.isCollecting()) {
            graphics.drawString(font, Component.translatable(
                    "screen.tabletoprpg.initiative.state_closed").getString(), x + PAD, top, 0xFFAAAAAA, false);
            return;
        }
        List<InitiativeSync.Entry> entries = InitiativeClientState.entries();
        if (entries.isEmpty()) {
            graphics.drawString(font, Component.translatable(
                    "screen.tabletoprpg.initiative.hud_empty").getString(), x + PAD, top, 0xFFAAAAAA, false);
            return;
        }

        int valueWidth = 0;
        for (InitiativeSync.Entry entry : entries) {
            valueWidth = Math.max(valueWidth, font.width(String.valueOf(entry.value())));
        }
        int nameLeft = x + PAD + ICON + 4;
        int nameWidth = Math.max(10, x + width - PAD - valueWidth - 4 - nameLeft);
        int rows = visibleRows();
        int maxScroll = maxScroll();
        if (scroll > maxScroll) {
            scroll = maxScroll;
        }
        if (scroll < 0) {
            scroll = 0;
        }
        for (int i = scroll; i < Math.min(entries.size(), scroll + rows); i++) {
            InitiativeSync.Entry entry = entries.get(i);
            int rowY = top + (i - scroll) * ROW_H;
            drawIcon(graphics, minecraft, entry, x + PAD, rowY);
            graphics.drawString(font, fit(font, entry.name(), nameWidth), nameLeft, rowY + 1,
                    entry.mob() ? 0xFFFFD0A0 : 0xFFFFFFFF, false);
            String value = String.valueOf(entry.value());
            graphics.drawString(font, value, x + width - PAD - font.width(value), rowY + 1,
                    0xFFE8E8E8, false);
        }
        if (maxScroll > 0) {
            // Barra fina a direita. No jogo o cursor fica capturado: o resto continua
            // resumido em "+N"; na tela de edicao da HUD a roda do mouse rola.
            int barX = x + width - 2;
            graphics.fill(barX, top, barX + 1, bottom, 0x40FFFFFF);
            int thumbH = Math.max(6, (bottom - top) * rows / entries.size());
            int travel = Math.max(0, bottom - top - thumbH);
            int thumbY = top + travel * scroll / maxScroll;
            graphics.fill(barX, thumbY, barX + 1, thumbY + thumbH, 0xFFB8912E);
            String more = "+" + (entries.size() - rows);
            graphics.drawString(font, more, x + width - PAD - font.width(more), bottom - 10,
                    0xFFAAAAAA, false);
        }
    }

    /** Linhas que cabem no corpo da janela, abaixo do titulo. */
    static int visibleRows() {
        int top = y + TITLE_H + 3;
        int bottom = y + height - PAD;
        return Math.max(0, (bottom - top) / ROW_H);
    }

    /** Entradas escondidas pela altura atual da janela. */
    static int maxScroll() {
        return Math.max(0, InitiativeClientState.entries().size() - visibleRows());
    }

    /** Rola a lista da HUD (so a tela de edicao chama: o jogo captura o cursor). */
    static void scrollBy(int delta) {
        scroll = Math.max(0, Math.min(maxScroll(), scroll + delta));
    }

    private static void drawIcon(GuiGraphics graphics, Minecraft minecraft, InitiativeSync.Entry entry,
                                 int iconX, int iconY) {
        if (entry.mob()) {
            drawMobIcon(graphics, iconX, iconY);
            return;
        }
        PlayerSkin skin = skinOf(minecraft, entry.name());
        if (skin != null) {
            PlayerFaceRenderer.draw(graphics, skin, iconX, iconY, ICON);
            return;
        }
        // Jogador fora do nivel do cliente (offline): silhueta em vez da cabeca.
        drawPlayerIcon(graphics, iconX, iconY);
    }

    private static PlayerSkin skinOf(Minecraft minecraft, String name) {
        if (minecraft.level == null) {
            return null;
        }
        for (AbstractClientPlayer player : minecraft.level.players()) {
            if (player.getName().getString().equals(name)) {
                return player.getSkin();
            }
        }
        return null;
    }

    /** Icone generico de criatura: sem textura e sem o tipo da entidade no payload. */
    private static void drawMobIcon(GuiGraphics graphics, int iconX, int iconY) {
        graphics.fill(iconX, iconY, iconX + ICON, iconY + ICON, 0xFF2F5D34);
        graphics.fill(iconX, iconY, iconX + ICON, iconY + 1, 0xFF4E8F56);
        graphics.fill(iconX + 2, iconY + 2, iconX + 4, iconY + 5, 0xFF101010);
        graphics.fill(iconX + 6, iconY + 2, iconX + 8, iconY + 5, 0xFF101010);
        graphics.fill(iconX + 3, iconY + 6, iconX + 7, iconY + 8, 0xFF101010);
    }

    private static void drawPlayerIcon(GuiGraphics graphics, int iconX, int iconY) {
        graphics.fill(iconX, iconY, iconX + ICON, iconY + ICON, 0xFF3A5A8C);
        graphics.fill(iconX + 3, iconY + 2, iconX + 7, iconY + 5, 0xFFDCDCDC);
        graphics.fill(iconX + 2, iconY + 6, iconX + 8, iconY + 9, 0xFFDCDCDC);
    }

    /** Corta o nome com "..." para nao invadir a coluna do valor. */
    private static String fit(Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        String suffix = "...";
        int limit = maxWidth - font.width(suffix);
        if (limit <= 0) {
            return suffix;
        }
        StringBuilder trimmed = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            if (font.width(trimmed.toString() + text.charAt(i)) > limit) {
                break;
            }
            trimmed.append(text.charAt(i));
        }
        return trimmed + suffix;
    }
}
