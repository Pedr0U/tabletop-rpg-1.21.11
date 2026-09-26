package com.pedro.tabletoprpg.client;

import com.pedro.tabletoprpg.SheetData;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * Lista suspensa dos 6 atributos, aberta pelo botao de atributo de uma pericia.
 *
 * <p><b>Por que uma tela separada e nao um popup desenhado dentro da
 * {@link SkillsScreen}:</b> o painel de opcoes precisa ficar ACIMA dos botoes
 * das outras linhas. Desenhado dentro da SkillsScreen, ele sairia sob os
 * widgets (o {@code Screen} desenha os renderables depois do conteudo), e
 * cliques na area do popup ainda teriam de ser interceptados a mao. Como tela
 * nova, a ordem de desenho e os cliques sao resolvidos pelo proprio jogo.
 *
 * <p>Escolher uma opcao devolve o valor por {@code onPick} e fecha; cancelar
 * (ESC ou o botao Cancel) fecha sem mudar nada.
 */
public class AttributePickerScreen extends Screen {

    private static final int BUTTON_W = 150;
    private static final int BUTTON_H = 20;
    private static final int GAP = 4;

    private final Screen parent;
    private final String skillName;
    private final SheetData.Attribute current;
    private final Consumer<SheetData.Attribute> onPick;

    public AttributePickerScreen(Screen parent, String skillName, SheetData.Attribute current,
                                 Consumer<SheetData.Attribute> onPick) {
        super(Component.literal("Attribute"));
        this.parent = parent;
        this.skillName = skillName;
        this.current = current == null ? SheetData.Attribute.DEXTERITY : current;
        this.onPick = onPick;
    }

    @Override
    protected void init() {
        int listH = SheetData.Attribute.VALUES.size() * (BUTTON_H + GAP) - GAP;
        int x = (this.width - BUTTON_W) / 2;
        int y = Math.max(40, (this.height - listH - BUTTON_H - 8) / 2);
        // Os 6 botoes sempre cabem, mas em janela baixa o Cancel (abaixo da
        // lista) saia parcialmente fora da tela. Empurra a lista para cima o
        // suficiente para o Cancel caber, em vez de deixar o botao invisivel.
        y = Math.min(y, Math.max(24, this.height - listH - BUTTON_H - 8));

        for (SheetData.Attribute attr : SheetData.Attribute.VALUES) {
            // Marcador no atributo atual: sem ele o mestre nao saberia o que
            // trocar quando a lista tem as 6 opcoes parecidas.
            String mark = attr == current ? "> " : "  ";
            addRenderableWidget(Button.builder(
                    Component.literal(mark + attr.abbr() + "  " + attr.fullName()),
                    b -> {
                        if (onPick != null) {
                            onPick.accept(attr);
                        }
                        onClose();
                    }).bounds(x, y, BUTTON_W, BUTTON_H).build());
            y += BUTTON_H + GAP;
        }

        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(x, y + 4, BUTTON_W, BUTTON_H).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 16, 0xFFFFFFFF);
        graphics.drawCenteredString(this.font, skillName, this.width / 2, 28, 0xFF9A9AA2);
    }

    /** Volta para a tela de Skills sem escolher nada. */
    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    /**
     * O dropdown NAO pausa o jogo.
     *
     * <p>{@code Screen#isPauseScreen} devolve {@code true} por padrao, e num
     * mundo em singleplayer/LAN isso congelaria o jogo (e a LAN) so por causa de
     * um clique numa opcao. A classe base {@code CharacterSheetScreen} ja
     * resolve isso para a ficha inteira; aqui e o mesmo cuidado.
     */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }
}
