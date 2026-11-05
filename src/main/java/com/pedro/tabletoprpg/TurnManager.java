package com.pedro.tabletoprpg;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;

/**
 * Ligacao entre o turno e a lista de iniciativa (Parte 4, 03/11/2026).
 *
 * <p>Antes desta classe o turno era um UUID solto que o mestre dava com
 * {@code /rpg turn give}; nao existia "proximo". Agora o turno guarda a posicao da
 * entrada na lista de iniciativa e a versao da lista no inicio do turno, para:
 *
 * <ul>
 *   <li>passar a vez para a proxima entrada quando o turno termina (fim da lista
 *       volta ao topo, avisando "nova rodada");</li>
 *   <li>pedir confirmacao ao mestre quando a iniciativa mudou durante o turno
 *       (entrada adicionada, removida ou reordenada);</li>
 *   <li>dar a vez ao mestre quando a proxima entrada e um mob (decisao do usuario
 *       em 03/11/2026: mob nao tem turno proprio).</li>
 * </ul>
 *
 * <p>Sem iniciativa ativa o comportamento antigo permanece: encerrar o turno so
 * limpa o turno. Estado so em memoria, como o resto do turno e da iniciativa.
 */
public final class TurnManager {

    /** Posicao 0-based da entrada com a vez; -1 quando o turno nao veio da iniciativa. */
    private static int position = -1;
    /** Versao da iniciativa no inicio do turno; -1 sem turno. */
    private static long startVersion = -1;
    /** Confirmacao pendente: entrada sugerida para o proximo turno. */
    private static int pendingPosition = -1;
    private static String pendingName = null;
    private static boolean pendingMob;

    private TurnManager() {
    }

    /** Marca o inicio de um turno dado manualmente pelo mestre. */
    public static void onTurnGiven(ServerPlayer player) {
        clearPending();
        position = InitiativeCommands.indexOf(player.getName().getString(), false);
        startVersion = InitiativeCommands.version();
    }

    /** O turno foi revogado ou a iniciativa acabou: esquece cursor e confirmacao. */
    public static void reset() {
        position = -1;
        startVersion = -1;
        clearPending();
    }

    /**
     * Fim de turno pelo dono da vez ou pelo mestre: limpa o turno, anuncia e decide
     * o proximo (confirmacao do mestre quando a iniciativa mudou neste turno).
     */
    public static void endTurn(MinecraftServer server, ServerPlayer finisher) {
        endTurn(server, finisher, -1);
    }

    /**
     * Fim de turno com a proxima entrada ja conhecida ({@code nextIndex} 0-based):
     * usado pelo atraso de turno, em que a posicao que ficou vaga e a proxima da
     * fila. {@code -1} volta a usar o cursor do turno.
     */
    public static void endTurn(MinecraftServer server, ServerPlayer finisher, int nextIndex) {
        int finishedPosition = position;
        long start = startVersion;
        UUID prevUuid = SessionManager.getActivePlayerUuid();

        SessionManager.clearActivePlayer();
        if (prevUuid != null) {
            CombatController.clearPlayerAnchor(prevUuid);
        }
        reset();

        server.getPlayerList().broadcastSystemMessage(
                Component.translatable("message.tabletoprpg.turn_finished", finisher.getName().getString()), false);
        syncTurn(server);

        if (!InitiativeCommands.isCollecting() || (finishedPosition < 0 && nextIndex < 0)) {
            return;
        }
        int size = InitiativeCommands.entries().size();
        if (size == 0) {
            return;
        }
        boolean changed = start >= 0 && InitiativeCommands.version() != start;
        int next;
        if (nextIndex >= 0 && nextIndex < size) {
            next = nextIndex;
        } else {
            next = (finishedPosition + 1) % size;
            if (next == 0) {
                // Ultimo da lista passou o turno: nova rodada (04/11/2026). A ordem
                // e: conta a rodada e so depois anuncia, para a mensagem sair com o
                // numero novo. O atraso de turno nunca cai aqui -- quem atrasa nao
                // pode ser o ultimo (ver InitiativeCommands.delayOpen).
                InitiativeCommands.advanceRound(server);
                server.getPlayerList().broadcastSystemMessage(Component.translatable(
                        "message.tabletoprpg.turn.new_round", InitiativeCommands.round()), false);
            }
        }
        int target = firstPlayable(server, next);
        if (target < 0) {
            return;
        }
        if (changed) {
            askConfirm(server, target);
            return;
        }
        grant(server, target);
    }

    /** Confirma o proximo turno sugerido ao mestre. */
    public static boolean confirm(MinecraftServer server) {
        if (pendingName == null) {
            return false;
        }
        String name = pendingName;
        boolean mob = pendingMob;
        int index = pendingPosition;
        clearPending();

        List<InitiativeTracker.Entry> entries = InitiativeCommands.entries();
        int fresh = InitiativeCommands.indexOf(name, mob);
        if (fresh >= 0) {
            index = fresh;
        }
        if (index < 0 || index >= entries.size()) {
            return false;
        }
        server.getPlayerList().broadcastSystemMessage(
                Component.translatable("message.tabletoprpg.turn.confirmed", name), false);
        grant(server, index);
        return true;
    }

    /**
     * Da a vez para a entrada {@code index} da iniciativa: jogador online recebe o
     * turno; mob vira vez do primeiro mestre online.
     */
    public static void grant(MinecraftServer server, int index) {
        List<InitiativeTracker.Entry> entries = InitiativeCommands.entries();
        if (index < 0 || index >= entries.size()) {
            return;
        }
        // Dar a vez a mao desfaz a confirmacao pendente: ela falava de outra
        // entrada e, sem isto, o "Sim" do chat daria um segundo turno.
        clearPending();
        InitiativeTracker.Entry entry = entries.get(index);
        if (entry.mob()) {
            ServerPlayer master = firstMaster(server);
            if (master == null) {
                return;
            }
            position = index;
            startVersion = InitiativeCommands.version();
            SessionManager.setActivePlayer(master);
            CombatController.setPlayerAnchor(master);
            server.getPlayerList().broadcastSystemMessage(Component.translatable(
                    "message.tabletoprpg.turn.mob_turn", entry.name(), master.getName().getString()), false);
            syncTurn(server);
            MasterCommands.sendMenu(master);
            return;
        }
        ServerPlayer player = findOnline(server, entry.name());
        if (player == null) {
            return;
        }
        position = index;
        startVersion = InitiativeCommands.version();
        SessionManager.setActivePlayer(player);
        CombatController.setPlayerAnchor(player);
        server.getPlayerList().broadcastSystemMessage(
                Component.translatable("message.tabletoprpg.turn_granted", player.getName().getString()), false);
        player.sendSystemMessage(Component.translatable("message.tabletoprpg.turn_your_turn"));
        syncTurn(server);
        MasterCommands.sendMenu(player);
    }

    /**
     * Da a vez para a entrada escolhida no menu (04/11/2026). A posicao clicada
     * tem prioridade, mas so vale se ainda aponta para a mesma entrada; senao o
     * servidor procura pelo nome. A checagem existe porque a lista pode mudar com
     * o menu aberto e porque mobs repetidos tem o mesmo nome de exibicao -- sem a
     * posicao, clicar na terceira repeticao daria o turno a primeira. Devolve
     * {@code false} quando a entrada ja nao esta na lista.
     */
    public static boolean grantByName(MinecraftServer server, String name, boolean mob, int position) {
        List<InitiativeTracker.Entry> entries = InitiativeCommands.entries();
        if (position >= 0 && position < entries.size()) {
            InitiativeTracker.Entry at = entries.get(position);
            if (at.name().equals(name) && at.mob() == mob) {
                grant(server, position);
                return true;
            }
        }
        int index = InitiativeCommands.indexOf(name, mob);
        if (index < 0) {
            return false;
        }
        grant(server, index);
        return true;
    }

    /** Posicao da proxima entrada jogavel a partir de {@code start}, dando a volta. */
    private static int firstPlayable(MinecraftServer server, int start) {
        List<InitiativeTracker.Entry> entries = InitiativeCommands.entries();
        int size = entries.size();
        if (size == 0) {
            return -1;
        }
        boolean hasMaster = firstMaster(server) != null;
        for (int step = 0; step < size; step++) {
            int index = (start + step) % size;
            InitiativeTracker.Entry entry = entries.get(index);
            if (entry.mob()) {
                if (hasMaster) {
                    return index;
                }
            } else if (findOnline(server, entry.name()) != null) {
                return index;
            }
        }
        return -1;
    }

    private static void askConfirm(MinecraftServer server, int index) {
        InitiativeTracker.Entry entry = InitiativeCommands.entries().get(index);
        pendingPosition = index;
        pendingName = entry.name();
        pendingMob = entry.mob();
        server.getPlayerList().broadcastSystemMessage(
                Component.translatable("message.tabletoprpg.turn.awaiting_master"), false);
        for (ServerPlayer master : server.getPlayerList().getPlayers()) {
            if (!SessionManager.isMaster(master)) {
                continue;
            }
            master.sendSystemMessage(Component.translatable(
                    "message.tabletoprpg.turn.confirm_next", entry.name()));
            MutableComponent buttons = Component.empty()
                    .append(button(Component.translatable("message.tabletoprpg.turn.confirm_yes"),
                            ChatFormatting.GREEN, "/rpg turn confirm",
                            Component.translatable("message.tabletoprpg.turn.confirm_hover_yes", entry.name())))
                    .append(Component.literal(" "))
                    .append(button(Component.translatable("message.tabletoprpg.turn.confirm_no"),
                            ChatFormatting.RED, "/rpg turn pick",
                            Component.translatable("message.tabletoprpg.turn.confirm_hover_no")));
            master.sendSystemMessage(buttons);
        }
    }

    private static void clearPending() {
        pendingPosition = -1;
        pendingName = null;
        pendingMob = false;
    }

    private static void syncTurn(MinecraftServer server) {
        RpgNetworking.sendToAll(server);
        RpgNetworking.sendAuraStateToAll(server);
    }

    private static ServerPlayer findOnline(MinecraftServer server, String name) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.getName().getString().equals(name)) {
                return player;
            }
        }
        return null;
    }

    private static ServerPlayer firstMaster(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (SessionManager.isMaster(player)) {
                return player;
            }
        }
        return null;
    }

    private static Component button(Component text, ChatFormatting color, String command, Component hover) {
        return Component.empty().append(text)
                .setStyle(Style.EMPTY
                        .withColor(color)
                        .withBold(true)
                        .withClickEvent(new ClickEvent.RunCommand(command))
                        .withHoverEvent(new HoverEvent.ShowText(hover)));
    }
}
