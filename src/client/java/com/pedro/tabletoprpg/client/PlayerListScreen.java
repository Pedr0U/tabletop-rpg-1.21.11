package com.pedro.tabletoprpg.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Lista os jogadores conectados (aberto pelo botão "Players" do menu).
 *
 * <p>Mostra cada jogador e marca o que está no turno ativo. Tem um botão
 * "Back" para voltar ao menu principal.
 */
public class PlayerListScreen extends Screen {

    private final List<String> playerNames;
    private final String activePlayerName;
    private final String sessionName;
    private final Screen returnTo;

    public PlayerListScreen(List<String> playerNames, String activePlayerName,
                            String sessionName, Screen returnTo) {
        super(Component.literal("Players"));
        this.playerNames = playerNames;
        this.activePlayerName = activePlayerName;
        this.sessionName = sessionName;
        this.returnTo = returnTo;
    }

    @Override
    protected void init() {
        super.init();

        // Calcula largura do botão: 200px no máximo, mas escala com largura da tela
        // com margem de 30px total (15px de cada lado), com mínimo de 100px.
        int buttonWidth = Math.max(100, Math.min(200, this.width - 60));
        int x = (this.width - buttonWidth) / 2;
        // Altura inicial com margem superior de 40px em vez de 46, para melhor espaçamento
        int y = 40;

        if (playerNames.isEmpty()) {
            this.addRenderableWidget(Button.builder(Component.literal("(No players connected)"), b -> {})
                .bounds(x, y, buttonWidth, 20).build());
            y += 30;
        } else {
            for (String name : playerNames) {
                String label = name.equals(activePlayerName) ? name + " (turn)" : name;
                this.addRenderableWidget(Button.builder(Component.literal(label), b -> {})
                    .bounds(x, y, buttonWidth, 20).build());
                y += 30;
            }
        }

        // Botão Back posicionado com margem inferior de 20px da base calculada.
        // Usamos y em vez de y + 12 para posicionamento mais consistente.
        this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> {
            if (this.minecraft != null) {
                this.minecraft.setScreen(returnTo != null ? returnTo : null);
            }
        }).bounds(x, y + 15, buttonWidth, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        // super.render() desenha o fundo e os botões (sem duplicar o blur).
        super.render(graphics, mouseX, mouseY, delta);
        String title = "\"" + sessionName + "\" - Players";
        int titleX = (this.width - this.font.width(title)) / 2;
        graphics.drawString(this.font, title, titleX, 20, 0xFFFFFF, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}