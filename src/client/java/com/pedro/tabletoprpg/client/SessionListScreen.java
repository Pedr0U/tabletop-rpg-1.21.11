package com.pedro.tabletoprpg.client;

import com.pedro.tabletoprpg.RpgNetworking;
import com.pedro.tabletoprpg.RpgNetworking.SessionSummary;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Lista de sessoes salvas, aberta pelo menu para iniciar ou entrar numa sessao. */
public class SessionListScreen extends Screen {

    private static final int ROW_H = 20;
    private static final int GAP = 4;
    private static final int TOP = 34;
    private static final int BOTTOM_RESERVED = 76;
    private static final int MAX_NAME = 64;

    private final Screen parent;
    private final List<SessionSummary> sessions;
    private final boolean masterMode;
    private final boolean releaseMasterOnCancel;

    private EditBox nameBox;
    private String nameText = "";
    private int visible;
    private int scroll;
    private boolean committed = false;

    public SessionListScreen(Screen parent, List<SessionSummary> sessions, boolean masterMode) {
        this(parent, sessions, masterMode, false);
    }

    public SessionListScreen(Screen parent, List<SessionSummary> sessions, boolean masterMode,
                             boolean releaseMasterOnCancel) {
        super(Component.translatable("screen.tabletoprpg.session_list.title"));
        this.parent = parent;
        this.sessions = sessions == null ? List.of() : sessions;
        this.masterMode = masterMode;
        this.releaseMasterOnCancel = releaseMasterOnCancel;
    }

    public Screen getParentScreen() {
        return parent;
    }

    @Override
    protected void init() {
        super.init();

        String typed = nameBox == null ? nameText : nameBox.getValue();
        int panelWidth = Math.max(80, Math.min(this.width - 24, 320));
        int x = (this.width - panelWidth) / 2;
        int listBottom = masterMode ? this.height - BOTTOM_RESERVED : this.height - 16;
        int room = listBottom - TOP;
        visible = Math.max(0, Math.min(sessions.size(), (room + GAP) / (ROW_H + GAP)));
        scroll = clampScroll(scroll);

        int y = TOP;
        for (int i = scroll; i < Math.min(sessions.size(), scroll + visible); i++) {
            SessionSummary session = sessions.get(i);
            String status = Component.translatable(session.started()
                    ? "screen.tabletoprpg.session_list.started"
                    : "screen.tabletoprpg.session_list.saved").getString();
            String members = Component.translatable("screen.tabletoprpg.session_list.members",
                    session.members()).getString();
            String details = session.id() + " | " + session.name() + " | " + status + " | " + members;
            String command = (masterMode ? "rpg session start " : "rpg session join ") + session.id();
            addRenderableWidget(Button.builder(
                    Component.translatable(masterMode
                            ? "screen.tabletoprpg.session_list.start"
                            : "screen.tabletoprpg.session_list.join", details),
                    b -> {
                        committed = true;
                        sendCommand(command);
                        onClose();
                        requestMenu();
                    }).bounds(x, y, panelWidth, ROW_H).build());
            y += ROW_H + GAP;
        }

        if (masterMode) {
            nameBox = new EditBox(this.font, x, this.height - 58, panelWidth, ROW_H,
                    Component.translatable("screen.tabletoprpg.session_list.name_hint"));
            nameBox.setMaxLength(MAX_NAME);
            nameBox.setValue(typed);
            this.addRenderableWidget(nameBox);
            nameText = typed;

            addRenderableWidget(Button.builder(
                    Component.translatable("screen.tabletoprpg.session_list.create"),
                    b -> createSession())
                    .bounds(x, this.height - 32, panelWidth, ROW_H).build());
        }
    }

    private void createSession() {
        if (nameBox == null) {
            return;
        }
        String name = nameBox.getValue().replace("\r", "").replace("\n", "").trim();
        if (name.isEmpty()) {
            return;
        }
        if (name.length() > MAX_NAME) {
            name = name.substring(0, MAX_NAME).trim();
        }
        if (name.isEmpty()) {
            return;
        }
        sendCommand("rpg session create " + name);
        committed = true;
        onClose();
        requestMenu();
    }

    private int maxScroll() {
        return visible <= 0 ? 0 : Math.max(0, sessions.size() - visible);
    }

    private int clampScroll(int value) {
        return Math.max(0, Math.min(value, maxScroll()));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (sessions.size() > visible) {
            int next = clampScroll(scroll + (int) -Math.signum(scrollY));
            if (next != scroll) {
                scroll = next;
                rebuildWidgets();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);
        if (sessions.size() > visible) {
            graphics.drawCenteredString(this.font,
                    scroll + 1 + "-" + Math.min(sessions.size(), scroll + visible) + " / " + sessions.size(),
                    this.width / 2, 22, 0xFF9A9AA2);
        }
    }

    private void sendCommand(String command) {
        if (this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.connection.sendCommand(command);
        }
    }

    private void requestMenu() {
        if (this.minecraft != null && this.minecraft.player != null) {
            ClientPlayNetworking.send(new RpgNetworking.MenuRequestPayload());
        }
    }

    @Override
    public void onClose() {
        if (releaseMasterOnCancel && !committed && this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.connection.sendCommand("rpg master release");
            this.minecraft.player.connection.sendCommand("rpg session leave");
        }
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
