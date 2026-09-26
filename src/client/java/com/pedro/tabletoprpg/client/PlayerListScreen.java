package com.pedro.tabletoprpg.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Lista os jogadores conectados (aberto pelo botão "Players" do menu).
 *
 * <p>Mostra cada jogador e marca o que está no turno ativo.
 *
 * <p><b>FASE 3:</b> para o <b>mestre</b>, clicar num jogador abre a ficha
 * desse jogador (e o mestre pode editá-la). Para um jogador comum a lista é
 * apenas informativa: ele abre a própria ficha pelo botão "My Sheet" do menu.
 * A permissão real é conferida no servidor de qualquer forma — aqui só
 * decidimos qual botão monta.
 */
public class PlayerListScreen extends Screen {

    private final List<String> playerNames;
    private final String activePlayerName;
    private final String sessionName;
    private final boolean isMaster;
    private final Screen returnTo;

    public PlayerListScreen(List<String> playerNames, String activePlayerName,
                            String sessionName, boolean isMaster, Screen returnTo) {
        super(Component.literal("Players"));
        this.playerNames = playerNames;
        this.activePlayerName = activePlayerName;
        this.sessionName = sessionName;
        this.isMaster = isMaster;
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
                final String playerName = name;
                if (isMaster) {
                    // Mestre: clicar abre (e permite editar) a ficha do jogador.
                    this.addRenderableWidget(Button.builder(Component.literal(label), b -> openSheet(playerName))
                        .bounds(x, y, buttonWidth, 20).build());
                } else {
                    // Jogador comum: a lista é informativa.
                    this.addRenderableWidget(Button.builder(Component.literal(label), b -> {})
                        .bounds(x, y, buttonWidth, 20).build());
                }
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

    /**
     * Abre a ficha de um jogador. Assim como no menu, a tela é aberta antes do
     * pedido: se a resposta do servidor chegasse antes de a tela existir, o
     * receptor não teria onde entregá-la.
     */
    private void openSheet(String playerName) {
        if (this.minecraft == null) {
            return;
        }
        this.minecraft.setScreen(new StatusScreen(playerName, this));
        TabletopRpgClient.requestSheet(playerName);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        // super.render() desenha o fundo e os botões (sem duplicar o blur).
        super.render(graphics, mouseX, mouseY, delta);
        String title = "\"" + sessionName + "\" - Players";
        int titleX = (this.width - this.font.width(title)) / 2;
        graphics.drawString(this.font, title, titleX, 20, 0xFFFFFF, false);
        if (isMaster) {
            String hint = "click a player to open their sheet";
            graphics.drawString(this.font, hint,
                    (this.width - this.font.width(hint)) / 2, this.height - 24, 0xAAAAAA, false);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
