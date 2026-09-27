package com.pedro.tabletoprpg.client;

import com.pedro.tabletoprpg.SheetModel;
import com.pedro.tabletoprpg.SheetModelHolder;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;

/**
 * Lista suspensa dos atributos do <b>modelo</b>, aberta pelo botao de atributo de
 * uma pericia.
 *
 * <p><b>27/09/2026 (Sheet Editor):</b> antes esta tela recebia um
 * {@code SheetData.Attribute}, o enum de seis constantes, e desenhava
 * {@code Attribute.VALUES}. O enum foi removido: o Mestre pode ter de 1 a 10
 * atributos, renomeados, entao a lista vem de
 * {@link SheetModelHolder#current()} e o que viaja e o <b>id</b> do atributo,
 * nao um objeto.
 *
 * <p><b>Por que uma tela separada e nao um popup desenhado dentro da
 * {@link SkillsScreen}:</b> o painel de opcoes precisa ficar ACIMA dos botoes
 * das outras linhas. Desenhado dentro da SkillsScreen, ele sairia sob os
 * widgets (o {@code Screen} desenha os renderables depois do conteudo), e
 * cliques na area do popup ainda teriam de ser interceptados a mao. Como tela
 * nova, a ordem de desenho e os cliques sao resolvidos pelo proprio jogo.
 *
 * <p>Escolher uma opcao devolve o id por {@code onPick} e fecha; cancelar
 * (ESC ou o botao Cancel) fecha sem mudar nada.
 */
public class AttributePickerScreen extends Screen {

    private static final int BUTTON_W = 150;
    private static final int BUTTON_H = 20;
    private static final int GAP = 4;
    /** Margem acima da lista e abaixo do botao Cancel. */
    private static final int PAD = 40;

    private final Screen parent;
    private final String skillName;
    private final String current;
    private final Consumer<String> onPick;

    /**
     * Quantas opcoes cabem sem cortar. Repassado no {@code init}, porque
     * {@code this.height} so e valido depois que o pai montou a tela.
     */
    private int visible = 6;

    /**
     * Primeira opcao desenhada. Com ate 10 atributos a lista pode ser mais alta
     * que a tela, entao ela rola; {@code -1} ate o primeiro {@code init}.
     */
    private int scroll;

    public AttributePickerScreen(Screen parent, String skillName, String current,
                                 Consumer<String> onPick) {
        super(Component.literal("Attribute"));
        this.parent = parent;
        this.skillName = skillName;
        // Id vazio = a pericia ainda nao tem atributo escolhido. Nao existe mais
        // um "DEXTERITY" padrao para inventar: quem decide e o Mestre.
        this.current = current == null ? "" : current;
        this.onPick = onPick;
    }

    private List<SheetModel.AttributeDef> options() {
        return SheetModelHolder.current().attributes();
    }

    @Override
    protected void init() {
        List<SheetModel.AttributeDef> options = options();
        // Teto do que cabe entre o titulo e o Cancel. Minimo de 1 para que uma
        // janela minima ainda mostre algo em vez de_sumir com a lista.
        int room = this.height - PAD - BUTTON_H - 8;
        visible = Math.max(1, Math.min(options.size(), (room + GAP) / (BUTTON_H + GAP)));
        scroll = clampScroll(scroll, options.size());

        int x = (this.width - BUTTON_W) / 2;
        int listH = visible * (BUTTON_H + GAP) - GAP;
        int y = Math.max(PAD, (this.height - listH - BUTTON_H - 8) / 2);
        y = Math.min(y, Math.max(24, this.height - listH - BUTTON_H - 8));

        for (int i = scroll; i < Math.min(options.size(), scroll + visible); i++) {
            SheetModel.AttributeDef attr = options.get(i);
            // Marcador no atributo atual: sem ele o mestre nao saberia o que
            // trocar quando as opcoes tem rotulos parecidos.
            String mark = attr.id().equals(current) ? "> " : "  ";
            final String id = attr.id();
            addRenderableWidget(Button.builder(
                    Component.literal(mark + attr.label() + "  " + attr.name()),
                    b -> {
                        if (onPick != null) {
                            onPick.accept(id);
                        }
                        onClose();
                    }).bounds(x, y, BUTTON_W, BUTTON_H).build());
            y += BUTTON_H + GAP;
        }

        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(x, y + 4, BUTTON_W, BUTTON_H).build());
    }

    private int maxScroll(int total) {
        return Math.max(0, total - visible);
    }

    private int clampScroll(int value, int total) {
        return Math.max(0, Math.min(value, maxScroll(total)));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int total = options().size();
        if (total > visible) {
            scroll = clampScroll(scroll + (int) -Math.signum(scrollY), total);
            rebuildWidgets();
        }
        return true;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 16, 0xFFFFFFFF);
        graphics.drawCenteredString(this.font, skillName, this.width / 2, 28, 0xFF9A9AA2);
        int total = options().size();
        if (total > visible) {
            // Indicador de que a lista continua. Sem setas: o modelo aceita de
            // 1 a no maximo 10, e a dica de texto cabe sem poluir a tela.
            graphics.drawCenteredString(this.font,
                    scroll + 1 + "-" + Math.min(total, scroll + visible) + " / " + total,
                    this.width / 2, this.height - 26, 0xFF6A6A72);
        }
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
