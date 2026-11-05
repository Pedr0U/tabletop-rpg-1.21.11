package com.pedro.tabletoprpg.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Modo de edicao da janela de iniciativa (03/11/2026).
 *
 * <p>Existe porque no jogo o cursor fica capturado no centro da tela: com a tela
 * aberta o mouse fica livre e da para arrastar a barra de titulo, redimensionar pelo
 * canto inferior direito e fechar no X. O layout vale imediatamente para a HUD, que e
 * desenhada pelo mesmo {@link InitiativeHud}.
 *
 * <p>Fechar no X desliga o interruptor "Mostrar iniciativas", o mesmo botao da tela de
 * iniciativa, e volta para a tela anterior.
 */
public class InitiativeHudScreen extends Screen {

    private static final int NONE = 0;
    private static final int MOVE = 1;
    private static final int RESIZE = 2;

    private final Screen parent;
    private int mode = NONE;
    /** Distancia do clique ate a origem da janela, para o arrasto nao pular. */
    private int grabX;
    private int grabY;

    public InitiativeHudScreen(Screen parent) {
        super(Component.translatable("screen.tabletoprpg.initiative.hud_edit_title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.translatable("screen.tabletoprpg.initiative.back"),
                        b -> back())
                .bounds(width / 2 - 100, height - 28, 200, 20)
                .build());
        addRenderableWidget(Button.builder(Component.translatable("screen.tabletoprpg.initiative.hud_reset"),
                        b -> InitiativeHud.resetLayout())
                .bounds(width / 2 + 104, height - 28, 100, 20)
                .build());
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0x66000000);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);
        InitiativeHud.drawPanel(graphics, this.minecraft, true);

        int handleX = InitiativeHud.x() + InitiativeHud.width() - InitiativeHud.RESIZE;
        int handleY = InitiativeHud.y() + InitiativeHud.height() - InitiativeHud.RESIZE;
        graphics.fill(handleX, handleY, handleX + InitiativeHud.RESIZE, handleY + InitiativeHud.RESIZE,
                0x55FFFFFF);
        for (int i = 2; i < InitiativeHud.RESIZE; i += 3) {
            graphics.fill(handleX + i, handleY + InitiativeHud.RESIZE - 1,
                    handleX + InitiativeHud.RESIZE - 1, handleY + InitiativeHud.RESIZE, 0xFFFFFFFF);
            graphics.fill(handleX + InitiativeHud.RESIZE - 1, handleY + i,
                    handleX + InitiativeHud.RESIZE, handleY + InitiativeHud.RESIZE, 0xFFFFFFFF);
        }

        String title = getTitle().getString();
        graphics.drawString(font, title, width / 2 - font.width(title) / 2, 8, 0xFFFFD700, false);
        String hint = Component.translatable("screen.tabletoprpg.initiative.hud_edit_hint").getString();
        graphics.drawString(font, hint, width / 2 - font.width(hint) / 2, 20, 0xFFAAAAAA, false);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) {
            if (InitiativeHud.closeHit(event.x(), event.y())) {
                InitiativeClientState.setShowPanel(false);
                back();
                return true;
            }
            if (InitiativeHud.resizeHit(event.x(), event.y())) {
                mode = RESIZE;
                return true;
            }
            if (InitiativeHud.titleHit(event.x(), event.y())) {
                mode = MOVE;
                grabX = (int) event.x() - InitiativeHud.x();
                grabY = (int) event.y() - InitiativeHud.y();
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (mode == MOVE) {
            InitiativeHud.setPosition((int) event.x() - grabX, (int) event.y() - grabY);
            return true;
        }
        if (mode == RESIZE) {
            InitiativeHud.setSize((int) event.x() - InitiativeHud.x(), (int) event.y() - InitiativeHud.y());
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // Rolar a lista da HUD: no jogo o cursor fica capturado, entao a roda so
        // chega aqui, na tela de edicao (06/11/2026).
        if (mouseX >= InitiativeHud.x() && mouseX < InitiativeHud.x() + InitiativeHud.width()
                && mouseY >= InitiativeHud.y() && mouseY < InitiativeHud.y() + InitiativeHud.height()
                && InitiativeHud.maxScroll() > 0) {
            InitiativeHud.scrollBy(scrollY < 0 ? 1 : -1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (mode != NONE) {
            mode = NONE;
            return true;
        }
        return super.mouseReleased(event);
    }

    private void back() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }
}
