package com.pedro.tabletoprpg;

import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /**
     * Mestres da sessão. Pode haver mais de um (decisão do usuário em 22/10/2026):
     * o limite é {@link #maxMasters} (1..5) e TODOS têm os mesmos poderes —
     * menu de configurações, turnos, mover monstros, fichas de ameaça.
     *
     * <p>A ordem de inserção importa: o primeiro a clamar é o mestre principal
     * (é o nome exibido no chat e nos menus).
     */
    private static final Set<UUID> masterUuids = new LinkedHashSet<>();
    private static final Map<UUID, String> masterNames = new HashMap<>();

    /** Quantos jogadores podem clamar o cargo de mestre ao mesmo tempo (1..5). */
    private static int maxMasters = 1;

    /** Teto do número de mestres permitidos na sessão. */
    public static final int MAX_MASTERS = 5;

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
     * Se os mobs podem spawnar sozinhos no mundo. Padrão: TRUE (o comportamento
     * vanilla). Quando o mestre desliga, o servidor aplica o gamerule vanilla
     * de spawn em TODAS as dimensões (o Fabric API 3.1.1 não tem evento de
     * spawn, e o gamerule é o caminho oficial).
     *
     * <p>Configurável pelo mestre no menu de configurações (Settings).
     */
    private static boolean mobsCanSpawn = true;

    /**
     * Se os mobs podem levar dano (de jogadores e do mestre). Padrão: FALSE --
     * hoje os mobs são imunes a dano físico (ver {@code DamageControlHandler}).
     *
     * <p>Configurável pelo mestre no menu de configurações (Settings).
     */
    private static boolean mobsCanBeDamaged = false;

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
        return !masterUuids.isEmpty();
    }

    public static boolean isMaster(ServerPlayer player) {
        return player != null && masterUuids.contains(player.getUUID());
    }

    /**
     * Tenta dar o cargo de mestre ao jogador.
     *
     * <p>22/10/2026: pode haver mais de um mestre. As vagas do cargo são
     * limitadas por {@link #maxMasters}; quem já é mestre continua sendo,
     * sem consumir vaga.
     *
     * @return true se o jogador é mestre depois da chamada
     */
    public static boolean setMaster(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        if (isMaster(player)) {
            return true;
        }
        if (masterUuids.size() >= maxMasters) {
            return false;
        }
        masterUuids.add(player.getUUID());
        masterNames.put(player.getUUID(), player.getName().getString());
        return true;
    }

    /**
     * Libera o cargo de mestre de UM jogador.
     *
     * <p>Usado por {@code /rpg master release} e pela desconexão: com vários
     * mestres, sair um não pode derrubar a sessão. Quem chama decide o que
     * fazer quando sobrou nenhum mestre.
     *
     * @return true se o jogador era mestre e foi removido
     */
    public static boolean releaseMaster(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        masterNames.remove(player.getUUID());
        return masterUuids.remove(player.getUUID());
    }

    /** Libera TODOS os cargos de mestre (fim de sessão). */
    public static void releaseMaster() {
        masterUuids.clear();
        masterNames.clear();
    }

    /** Nome do mestre principal (o primeiro a clamar). */
    public static String getMasterName() {
        for (UUID id : masterUuids) {
            String name = masterNames.get(id);
            if (name != null) {
                return name;
            }
        }
        return "None";
    }

    /** Nomes de todos os mestres, na ordem em que clamaram. */
    public static List<String> getMasterNames() {
        List<String> names = new ArrayList<>();
        for (UUID id : masterUuids) {
            String name = masterNames.get(id);
            if (name != null) {
                names.add(name);
            }
        }
        return names;
    }

    /** Quantos jogadores ocupam o cargo de mestre agora. */
    public static int getMasterCount() {
        return masterUuids.size();
    }

    /** Quantos jogadores PODEM ocupar o cargo de mestre (1..5). */
    public static int getMaxMasters() {
        return maxMasters;
    }

    /**
     * Define quantos jogadores podem clamar o cargo de mestre.
     *
     * <p>22/10/2026: o valor é limitado a {@link #MAX_MASTERS} (5). Baixar o
     * limite NÃO expulsa ninguém: os mestres que já estão no cargo continuam,
     * e a vaga liberada só passa a valer para novos claimantes.
     */
    public static void setMaxMasters(int value) {
        maxMasters = Math.max(1, Math.min(value, MAX_MASTERS));
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

    /** Os mobs podem spawnar sozinhos no mundo? (padrão: TRUE) */
    public static boolean canMobsSpawn() {
        return mobsCanSpawn;
    }

    /**
     * Ao subir o servidor: o gamerule de spawn persiste no mundo, mas o estado da sessao volta ao
     * padrao. Le o valor real do mundo para a UI nao mostrar "pode spawnar" com o spawn bloqueado.
     * Nao reescreve o gamerule. Tambem esvazia o cache de vinculos (o selfHeal reconstroi pelas tags)
     * para um segundo mundo na mesma JVM nao herdar UUIDs antigos.
     */
    public static void onServerStarted(net.minecraft.server.MinecraftServer server) {
        mobsCanSpawn = server.overworld().getGameRules().get(net.minecraft.world.level.gamerules.GameRules.SPAWN_MOBS);
        ThreatSheetBinding.forgetAll();
    }

    public static void setMobsCanSpawn(boolean value) {
        mobsCanSpawn = value;
    }

    /** Os mobs podem levar dano? (padrão: FALSE = mobs imunes) */
    public static boolean canMobsBeDamaged() {
        return mobsCanBeDamaged;
    }

    public static void setMobsCanBeDamaged(boolean value) {
        mobsCanBeDamaged = value;
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
     *
     * <p><b>27/09/2026 (Sheet Editor):</b> a ficha criada ja sai alinhada com o
     * modelo, porque {@link SheetData#defaultSheet} ja le a lista de atributos e
     * de perícias do {@link SheetModelHolder}.
     */
    public static SheetData getOrCreateSheet(UUID playerUuid, String playerName) {
        if (playerUuid == null) {
            return SheetData.defaultSheet("");
        }
        return characterSheets.computeIfAbsent(playerUuid, uuid -> SheetData.defaultSheet(playerName));
    }

    /**
     * Re-alinha <b>todas</b> as fichas carregadas com o modelo atual.
     *
     * <p><b>Por que existe:</b> o modelo e global e muda em tempo de jogo
     * (o Mestre edita pelo Sheet Editor). Quem tem uma cópia da ficha em memória
     * precisa ser reconciliado com a lista nova: ganha as perícias e os atributos
     * que passaram a existir, perde os que saíram, e mantém o valor dos que
     * sobreviveram pelo id/nome.
     *
     * <p><b>Por que não alinhar em {@link #getSheet}:</b> {@code getSheet} é
     * chamado por handlers de dano e de tique. Alinhar ali alocaria uma ficha
     * nova por chamada, mesmo quando nada mudou. Alinhar só na criação, na
     * carga do NBT e aqui, neste método, cobre todos os momentos em que o
     * modelo pode ter mudado.
     *
     * @return quantas fichas mudaram de fato (as demais já estavam em dia)
     */
    public static int realignAllSheets() {
        int changed = 0;
        for (Map.Entry<UUID, SheetData> entry : new ArrayList<>(characterSheets.entrySet())) {
            SheetData current = entry.getValue();
            if (current == null) {
                continue;
            }
            SheetData aligned = current.aligned();
            // Compara por CONTEUDO, e nao por identidade. O align() sempre
            // devolve uma ficha nova, entao um "aligned != current" seria
            // sempre verdadeiro e o contador contaria todas as fichas em
            // memoria, nunca quantas mudaram de fato -- que e o que o
            // Javadoc acima promete.
            if (!aligned.equals(current)) {
                characterSheets.put(entry.getKey(), aligned);
                changed++;
            }
        }
        return changed;
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