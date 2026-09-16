package com.pedro.tabletoprpg.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Menu principal do TableTop RPG.
 *
 * <p>O painel (imagem {@code rpg_menu.png}, 408x612) é redimensionado
 * automaticamente para caber na tela, sem cortar e mantendo a proporção.
 *
 * <p>Layout limpo: mostra o papel (Mestre/Jogador), o modo e o turno ativo, e
 * três botões de navegação. Apenas o botão "Players" tem ação (abre a lista de
 * jogadores) e "End Turn" finaliza o turno do jogador quando é a vez dele.
 * Os botões "Rolls" e "Settings" são placeholders (sem ação por enquanto).
 */
public class RpgMenuScreen extends Screen {

    private static final Identifier TEXTURE =
        Identifier.fromNamespaceAndPath("tabletop-rpg", "textures/gui/rpg_menu.png");

    // Tamanho real do PNG (proporção 1:1.5).
    private static final int TEXTURE_WIDTH = 408;
    private static final int TEXTURE_HEIGHT = 612;

    // Margem entre a borda da tela e o painel.
    private static final int SCREEN_MARGIN = 16;
    // Margem entre a borda do painel e os botões.
    private static final int PANEL_MARGIN = 18;

    private static final int BUTTON_HEIGHT = 20;
    // Espaçamento vertical (folga clara entre os botões, sem empilhar).
    private static final int BUTTON_GAP = 28;

    // Dados vindos do servidor (RpgNetworking.MenuDataPayload).
    private final boolean isMaster;
    private final boolean isMyTurn;
    private final String sessionName;
    private final String modeName;
    private final String activePlayerName;
    private final List<String> playerNames;

    // Dimensões calculadas em init().
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int buttonWidth;

    public RpgMenuScreen(boolean isMaster, boolean isMyTurn, String sessionName,
                         String modeName, String activePlayerName, List<String> playerNames) {
        super(Component.literal("TableTop RPG Menu"));
        this.isMaster = isMaster;
        this.isMyTurn = isMyTurn;
        this.sessionName = sessionName;
        this.modeName = modeName;
        this.activePlayerName = activePlayerName;
        this.playerNames = playerNames;
    }

    @Override
    protected void init() {
        super.init();

        // Redimensiona o painel para caber na tela mantendo a proporção 1:1.5.
        int availW = this.width - SCREEN_MARGIN * 2;
        int availH = this.height - SCREEN_MARGIN * 2;
        float scale = Math.min(1f,
            Math.min((float) availW / TEXTURE_WIDTH, (float) availH / TEXTURE_HEIGHT));

        panelWidth = Math.max(1, (int) (TEXTURE_WIDTH * scale));
        panelHeight = Math.max(1, (int) (TEXTURE_HEIGHT * scale));
        panelX = (this.width - panelWidth) / 2;
        panelY = (this.height - panelHeight) / 2;

        buttonWidth = panelWidth - PANEL_MARGIN * 2;
        if (buttonWidth > 220) {
            buttonWidth = 220;
        }

        buildMenu();
    }

    // ------------------------------------------------------------------
    // CONSTRUÇÃO DOS ELEMENTOS
    // ------------------------------------------------------------------

    private void buildMenu() {
        int x = panelX + (panelWidth - buttonWidth) / 2;
        int y = panelY + 56;

        // Informações de status (apenas texto, sem ação).
        addLabel("Role: " + (isMaster ? "MASTER" : "PLAYER"), x, y);
        y += BUTTON_GAP;
        addLabel("Mode: " + modeName, x, y);
        y += BUTTON_GAP;
        addLabel("Turn: " + activePlayerName, x, y);
        y += 16;

        // Navegação.
        y = addButton("Players", x, y, () -> openPlayers());
        y = addButton("Rolls", x, y, () -> { /* placeholder */ });
        y = addButton("Settings", x, y, () -> { /* placeholder */ });

        // Finalizar turno (só para o jogador, quando for a vez dele).
        if (!isMaster && isMyTurn) {
            y += 16;
            addButton("End Turn", x, y, () -> {
                sendCommand("rpg turn finish");
                this.onClose();
            });
        }
    }

    /** Abre a tela de lista de jogadores. */
    private void openPlayers() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(new PlayerListScreen(playerNames, activePlayerName, sessionName, this));
        }
    }

    // ------------------------------------------------------------------
    // HELPERS
    // ------------------------------------------------------------------

    private void addLabel(String text, int x, int y) {
        this.addRenderableWidget(Button.builder(Component.literal("§l" + text), b -> {})
            .bounds(x, y, buttonWidth, BUTTON_HEIGHT)
            .build());
    }

    /** Adiciona um botão empilhado e retorna a próxima posição Y. */
    private int addButton(String label, int x, int y, Runnable onPress) {
        this.addRenderableWidget(Button.builder(Component.literal(label), b -> onPress.run())
            .bounds(x, y, buttonWidth, BUTTON_HEIGHT)
            .build());
        return y + BUTTON_GAP;
    }

    /** Envia um comando de chat (simples para o servidor executar). */
    private void sendCommand(String command) {
        if (this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.connection.sendCommand(command);
        }
    }

    // ------------------------------------------------------------------
    // RENDERIZAÇÃO
    // ------------------------------------------------------------------

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        // super.render() chama renderBackground() (nosso painel) e desenha os botões.
        super.render(graphics, mouseX, mouseY, delta);

        // Título: nome da sessão centralizado.
        String title = "\"" + sessionName + "\"";
        int titleX = panelX + (panelWidth - this.font.width(title)) / 2;
        graphics.drawString(this.font, title, titleX, panelY + 26, 0xFFFFAA, false);
    }

    /**
     * Desenha o fundo do menu (a imagem do painel).
     *
     * <p>Chamado automaticamente por {@code super.render()}. IMPORTANTE: não
     * devemos chamar {@code renderBackground} manualmente, pois isso faria o
     * blur do fundo rodar duas vezes por frame e o jogo crasharia com
     * "Can only blur once per frame".
     */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Escurece levemente toda a tela para dar contraste ao painel.
        graphics.fill(0, 0, this.width, this.height, 0x99000000);

        // Painel com a imagem do menu (em escala, sem cortar).
        graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE,
            panelX, panelY, 0, 0, panelWidth, panelHeight, TEXTURE_WIDTH, TEXTURE_HEIGHT);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}