package com.pedro.tabletoprpg.client;

import com.pedro.tabletoprpg.RpgNetworking.SessionSummary;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Porta de entrada do jogador fora da sessão ativa, aberta pela tecla R. */
public class SessionGateScreen extends Screen {

    private static final int BUTTON_WIDTH = 200;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GAP = 28;

    private final Screen parent;
    private final boolean canClaimMaster;
    private final boolean hasActiveSession;
    private final List<SessionSummary> sessions;

    public SessionGateScreen(Screen parent, boolean canClaimMaster, boolean hasActiveSession,
                             List<SessionSummary> sessions) {
        super(Component.translatable("screen.tabletoprpg.session_gate.title"));
        this.parent = parent;
        this.canClaimMaster = canClaimMaster;
        this.hasActiveSession = hasActiveSession;
        this.sessions = sessions == null ? List.of() : sessions;
    }

    @Override
    protected void init() {
        super.init();

        int x = (this.width - BUTTON_WIDTH) / 2;
        int y = (this.height - (BUTTON_HEIGHT * 2 + BUTTON_GAP)) / 2;

        boolean claimIntoActive = hasActiveSession;
        Button claimButton = Button.builder(
                Component.translatable(claimIntoActive
                        ? "screen.tabletoprpg.rpg_menu.claim_master"
                        : "screen.tabletoprpg.rpg_menu.start_session"),
                b -> {
                    if (this.minecraft != null && this.minecraft.player != null) {
                        if (!claimIntoActive) {
                            TabletopRpgClient.pendingMasterSessionList = true;
                        }
                        this.minecraft.player.connection.sendCommand("rpg master claim");
                    }
                })
                .bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build();
        claimButton.active = canClaimMaster;
        this.addRenderableWidget(claimButton);

        Button enterButton = Button.builder(
                Component.translatable("screen.tabletoprpg.rpg_menu.enter_session"),
                b -> {
                    if (this.minecraft != null) {
                        this.minecraft.setScreen(new SessionListScreen(this, sessions, false));
                    }
                })
                .bounds(x, y + BUTTON_GAP, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build();
        enterButton.active = hasActiveSession;
        this.addRenderableWidget(enterButton);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 20, 0xFFFFFFFF);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
