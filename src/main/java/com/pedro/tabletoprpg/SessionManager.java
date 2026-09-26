package com.pedro.tabletoprpg;

import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
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

    /**
     * Se os jogadores (não-mestre) podem colocar blocos. O mestre sempre pode.
     * Configurável pelo mestre no menu de configurações (Settings).
     */
    private static boolean playersCanPlaceBlocks = false;

    /**
     * Clima ALVO escolhido pelo mestre (0=sol, 1=chuva, 2=tempestade).
     *
     * <p>Não é o clima real do mundo: em 1.21.11 o clima muda gradualmente
     * (isRaining/isThundering derivam de rainLevel/thunderLevel suavizados),
     * então durante a transição o estado real não corresponde ao escolhido.
     * O botão de clima reflete este alvo, não o estado real — assim o botão
     * mostra o que o mestre escolheu e não "volta" durante a transição.
     */
    private static int weatherTarget = 0;

    /**
     * Fichas de personagem (FASE 3), uma por jogador, em memória do servidor.
     *
     * <p>A chave é o UUID do dono da ficha, e não o nome: o nome pode mudar
     * (ou colidir em LAN) enquanto a sessão acontece, e as permissões de
     * edição dependem da identidade, não do rótulo.
     *
     * <p><b>PERSISTÊNCIA (pendente, fase futura):</b> este mapa é volátil —
     * as fichas se perdem quando o servidor fecha. O próximo passo é salvar
     * por-jogador em disco (JSON na pasta do mundo) e carregar em
     * {@link #getOrCreateSheet(UUID, String)}.
     */
    private static final Map<UUID, SheetData> characterSheets = new HashMap<>();

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

    public static boolean canPlayersPlaceBlocks() {
        return playersCanPlaceBlocks;
    }

    public static void setPlayersCanPlaceBlocks(boolean value) {
        playersCanPlaceBlocks = value;
    }

    public static int getWeatherTarget() {
        return weatherTarget;
    }

    public static void setWeatherTarget(int weather) {
        weatherTarget = Math.max(0, Math.min(weather, 2));
    }

    // ------------------------------------------------------------------
    // FICHAS DE PERSONAGEM (FASE 3)
    // ------------------------------------------------------------------

    /**
     * Ficha de um jogador, ou null se ele ainda não tiver uma.
     *
     * <p>Use {@link #getOrCreateSheet(UUID, String)} quando a intenção for
     * "me dá a ficha, criando se preciso" (abrir a tela). Use este quando a
     * ausência é relevante (ex.: não criar ficha por mera checagem).
     */
    public static SheetData getSheet(UUID playerUuid) {
        return playerUuid == null ? null : characterSheets.get(playerUuid);
    }

    /**
     * Ficha de um jogador, criando a ficha padrão na primeira vez.
     * O nome da conta é usado como nome inicial do personagem.
     */
    public static SheetData getOrCreateSheet(UUID playerUuid, String playerName) {
        if (playerUuid == null) {
            return SheetData.defaultSheet("");
        }
        return characterSheets.computeIfAbsent(playerUuid, uuid -> SheetData.defaultSheet(playerName));
    }

    /** Substitui a ficha de um jogador. */
    public static void setSheet(UUID playerUuid, SheetData sheet) {
        if (playerUuid == null || sheet == null) {
            return;
        }
        characterSheets.put(playerUuid, sheet);
    }

    /** Remove a ficha de um jogador (ex.: desconexão definitiva). */
    public static void removeSheet(UUID playerUuid) {
        if (playerUuid != null) {
            characterSheets.remove(playerUuid);
        }
    }

    /** Quantas fichas existem na sessão (usado no log de diagnóstico). */
    public static int sheetCount() {
        return characterSheets.size();
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