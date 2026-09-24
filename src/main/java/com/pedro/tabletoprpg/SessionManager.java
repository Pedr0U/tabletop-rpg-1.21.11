package com.pedro.tabletoprpg;

import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public class SessionManager {

    public enum GameMode {
        FREE("Free"),
        INVESTIGATION("Investigation"),
        COMBAT("Combat");

        private final String displayName;

        GameMode(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    private static String sessionName = "Session Name";
    private static GameMode currentMode = GameMode.FREE;
    private static UUID masterUuid = null;
    private static String masterName = "None";
    private static UUID activePlayerUuid = null;
    private static String activePlayerName = "None";

    /**
     * Distância máxima (em blocos) do highlight (Glowing) para os jogadores
     * (não-mestre). O mestre sempre vê de qualquer distância. Configurável
     * pelo mestre via /rpg hoverdistance <n>.
     */
    private static int hoverDistance = 32;

    /**
     * Se os jogadores (não-mestre) podem quebrar blocos. O mestre sempre pode.
     * Configurável pelo mestre no menu de configurações (Settings).
     */
    private static boolean playersCanBreakBlocks = false;

    public static String getSessionName() {
        return sessionName;
    }

    public static void setSessionName(String name) {
        sessionName = name;
    }

    public static GameMode getMode() {
        return currentMode;
    }

    public static void setMode(GameMode mode) {
        currentMode = mode;
    }

    public static boolean hasMaster() {
        return masterUuid != null;
    }

    public static boolean isMaster(ServerPlayer player) {
        return masterUuid != null && masterUuid.equals(player.getUUID());
    }

    public static boolean setMaster(ServerPlayer player) {
        // Bloqueia duplicidade: só atribui se não houver mestre ou se for o próprio mestre
        if (hasMaster() && !isMaster(player)) {
            return false;
        }
        masterUuid = player.getUUID();
        masterName = player.getName().getString();
        return true;
    }

    public static void releaseMaster() {
        masterUuid = null;
        masterName = "None";
    }

    public static String getMasterName() {
        return masterName;
    }

    public static boolean isActivePlayer(ServerPlayer player) {
        return activePlayerUuid != null && activePlayerUuid.equals(player.getUUID());
    }

    public static void setActivePlayer(ServerPlayer player) {
        if (player != null) {
            activePlayerUuid = player.getUUID();
            activePlayerName = player.getName().getString();
        } else {
            clearActivePlayer();
        }
    }

    public static void clearActivePlayer() {
        activePlayerUuid = null;
        activePlayerName = "None";
    }

    public static String getActivePlayerName() {
        return activePlayerName;
    }

    public static UUID getActivePlayerUuid() {
        return activePlayerUuid;
    }

    public static int getHoverDistance() {
        return hoverDistance;
    }

    public static void setHoverDistance(int distance) {
        hoverDistance = Math.max(1, Math.min(distance, 256));
    }

    public static boolean canPlayersBreakBlocks() {
        return playersCanBreakBlocks;
    }

    public static void setPlayersCanBreakBlocks(boolean value) {
        playersCanBreakBlocks = value;
    }

    public static boolean canPlayerAct(ServerPlayer player) {
        // No modo Livre, todos agem
        if (currentMode == GameMode.FREE) {
            return true;
        }
        // O Mestre sempre pode agir
        if (isMaster(player)) {
            return true;
        }
        // Em Investigação ou Combate, só quem tem o turno ativo pode agir
        return isActivePlayer(player);
    }
}