package com.pedro.tabletoprpg;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Comandos {@code /rpg initiative ...} e o estado da lista de iniciativa.
 *
 * <p>Fase 2: a rolagem segue a ficha (bonus da mesa ou pericia marcada), aceita
 * uma formula extra com sinal ({@code /rpg initiative roll +1d20}), impede rolagem
 * duplicada e lista do maior para o menor. Begin, stop, give, o painel e o menu
 * entram nas fases seguintes.
 *
 * <p>Iniciativa so funciona nos modos INVESTIGATION e COMBAT. Trocar de modo
 * finaliza a iniciativa (ver {@link #onModeChanged}).
 */
public final class InitiativeCommands {

    private static final InitiativeTracker TRACKER = new InitiativeTracker();

    /** Teto de nomes num lote de rolagem de mobs; o menu nao seleciona mais que isso. */
    static final int MAX_BULK_ROLL = 64;

    private InitiativeCommands() {
    }

    // ------------------------------------------------------------------
    // ACESSO PARA O TURNO (TurnManager) E PARA OS TESTES
    // ------------------------------------------------------------------

    static List<InitiativeTracker.Entry> entries() {
        return TRACKER.entries();
    }

    static long version() {
        return TRACKER.version();
    }

    /** Rodada atual (04/11/2026). */
    static int round() {
        return TRACKER.round();
    }

    /**
     * Fim do turno do ultimo da lista: entra a proxima rodada. Reenvia o estado
     * porque a rodada muda sem mudar a lista, e o cliente nao teria como saber.
     */
    static void advanceRound(MinecraftServer server) {
        TRACKER.advanceRound();
        InitiativeSync.sendAll(server);
    }

    static boolean isCollecting() {
        return TRACKER.isCollecting();
    }

    static int indexOf(String name, boolean mob) {
        return TRACKER.indexOf(name, mob);
    }

    /** O jogador esta na lista como jogador: e a entrada que o atraso move. */
    static boolean hasDelayEntry(ServerPlayer player) {
        return TRACKER.indexOf(player.getName().getString(), false) >= 0;
    }

    /** Raiz {@code initiative}, para ser anexada ao {@code /rpg} em {@link MasterCommands}. */
    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("initiative")
                .executes(InitiativeCommands::list)
                .then(Commands.literal("start")
                        .executes(InitiativeCommands::start))
                .then(Commands.literal("end")
                        .executes(InitiativeCommands::end))
                .then(Commands.literal("roll")
                        .executes(InitiativeCommands::rollSelf)
                        .then(Commands.argument("arg", StringArgumentType.greedyString())
                                .suggests(InitiativeCommands::suggestRollableMobs)
                                .executes(InitiativeCommands::rollWithArg)))
                .then(Commands.literal("add")
                        .then(Commands.argument("nickname", StringArgumentType.greedyString())
                                .suggests(InitiativeCommands::suggestAddable)
                                .executes(InitiativeCommands::add)))
                .then(Commands.literal("remove")
                        .then(Commands.argument("nickname", StringArgumentType.greedyString())
                                .suggests(InitiativeCommands::suggestInList)
                                .executes(InitiativeCommands::remove)))
                .then(Commands.literal("move")
                        .then(Commands.argument("from", IntegerArgumentType.integer(1))
                                .then(Commands.argument("to", IntegerArgumentType.integer(1))
                                        .executes(InitiativeCommands::move))))
                // /rpg initiative delay [posicao]: o jogador com a vez escolhe uma
                // posicao posterior a dele e perde a vez (Parte 4, 03/11/2026).
                .then(Commands.literal("delay")
                        .executes(InitiativeCommands::delayOpen)
                        .then(Commands.argument("posicao", IntegerArgumentType.integer(1))
                                .executes(InitiativeCommands::delay)));
    }

    /**
     * Chamado antes de qualquer troca de modo. Trocar de modo finaliza a iniciativa
     * (lista limpa), e o bloqueio dos jogadores segue o modo novo (Fase 7).
     */
    public static void onModeChanged(MinecraftServer server, SessionManager.GameMode newMode) {
        if (SessionManager.getMode() == newMode || TRACKER.state() == InitiativeTracker.State.IDLE) {
            return;
        }
        TRACKER.end();
        TurnManager.reset();
        server.getPlayerList().broadcastSystemMessage(
                Component.translatable("message.tabletoprpg.initiative.ended_by_mode"), false);
        InitiativeSync.sendAll(server);
    }

    /**
     * Chamado quando alguem rola a pericia (sem formula extra). Com a caixa
     * "usar iniciativa padrao" desmarcada e a pericia marcada como iniciativa, o
     * resultado entra na lista, se a coleta estiver aberta e o jogador ainda nao
     * estiver nela.
     */
    public static void onPericiaRolled(MinecraftServer server, ServerPlayer player, String periciaId, long total) {
        if (!TRACKER.isCollecting()) {
            return;
        }
        SheetModel model = SheetModelHolder.current();
        if (model.initiativeUseDefault() || !periciaId.equals(model.initiativePericiaId())) {
            return;
        }
        String name = player.getName().getString();
        if (TRACKER.contains(name)) {
            return;
        }
        TRACKER.add(name, false, (int) total);
        server.getPlayerList().broadcastSystemMessage(
                Component.translatable("message.tabletoprpg.initiative.added", name, (int) total), false);
        InitiativeSync.sendAll(server);
    }

    private static int start(CommandContext<CommandSourceStack> ctx) {
        if (!checkModeAndMaster(ctx)) return 0;
        if (TRACKER.isCollecting()) {
            ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.initiative.already_started"));
            return 0;
        }
        TRACKER.start();
        broadcast(ctx, Component.translatable("message.tabletoprpg.initiative.started"));
        return 1;
    }

    private static int end(CommandContext<CommandSourceStack> ctx) {
        if (!checkModeAndMaster(ctx)) return 0;
        if (TRACKER.state() == InitiativeTracker.State.IDLE) {
            ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.initiative.not_started"));
            return 0;
        }
        TRACKER.end();
        TurnManager.reset();
        broadcast(ctx, Component.translatable("message.tabletoprpg.initiative.ended"));
        return 1;
    }

    /** {@code /rpg initiative roll}: sem argumento. */
    private static int rollSelf(CommandContext<CommandSourceStack> ctx) {
        return rollSelf(ctx, "");
    }

    /**
     * Rolagem do proprio jogador. {@code extra} e a formula opcional com sinal
     * ("+1d20", "-2"), somada ao total da ficha.
     */
    private static int rollSelf(CommandContext<CommandSourceStack> ctx, String extra) {
        if (!checkModeAndCollecting(ctx)) return 0;
        if (!checkSkillRollRequired(ctx)) return 0;
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
            ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.roll_players_only"));
            return 0;
        }
        String name = player.getName().getString();
        if (TRACKER.contains(name)) {
            ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.initiative.already_in", name));
            return 0;
        }
        InitiativeRoller.Roll roll;
        try {
            roll = InitiativeRoller.playerRoll(player, extra);
        } catch (DiceFormula.SyntaxException e) {
            ctx.getSource().sendFailure(e.toComponent());
            return 0;
        }
        TRACKER.add(name, false, (int) roll.total());
        announceEntry(ctx, roll, name);
        return 1;
    }

    /**
     * {@code /rpg initiative roll <arg>}: "+..." e formula extra; nome de mob elegivel
     * tem prioridade; qualquer outra formula reconhecida e a rolagem inteira do proprio
     * jogador.
     */
    private static int rollWithArg(CommandContext<CommandSourceStack> ctx) {
        String arg = StringArgumentType.getString(ctx, "arg").trim();
        if (arg.startsWith("+") || arg.startsWith("-")) {
            return rollSelf(ctx, arg);
        }
        if (findEligibleMob(ctx.getSource().getServer(), arg) != null) {
            return rollMob(ctx, arg);
        }
        if (InitiativeRoller.isValidFormula(arg, SheetModelHolder.current())) {
            return rollSelfFormula(ctx, arg);
        }
        return rollMob(ctx, arg);
    }

    /** Rolagem do proprio jogador usando uma formula completa digitada no comando. */
    private static int rollSelfFormula(CommandContext<CommandSourceStack> ctx, String formula) {
        if (!checkModeAndCollecting(ctx)) return 0;
        if (!checkSkillRollRequired(ctx)) return 0;
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
            ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.roll_players_only"));
            return 0;
        }
        // Trocar a formula padrao na rolagem (04/11/2026): o Mestre decide se o
        // jogador pode. Adicao com sinal ("+2d6") NAO passa por aqui -- ela cai em
        // rollSelf(ctx, extra) -- entao a trava vale so para a troca completa.
        if (!SessionManager.isMaster(player) && !SheetModelHolder.current().initiativePlayerFormulaRoll()) {
            ctx.getSource().sendFailure(Component.translatable(
                    "message.tabletoprpg.initiative.formula_locked"));
            return 0;
        }
        String name = player.getName().getString();
        if (TRACKER.contains(name)) {
            ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.initiative.already_in", name));
            return 0;
        }
        InitiativeRoller.Roll roll;
        try {
            roll = InitiativeRoller.playerFormulaRoll(player, formula);
        } catch (DiceFormula.SyntaxException e) {
            ctx.getSource().sendFailure(e.toComponent());
            return 0;
        }
        TRACKER.add(name, false, (int) roll.total());
        announceEntry(ctx, roll, name);
        return 1;
    }

    private static int rollMob(CommandContext<CommandSourceStack> ctx, String name) {
        if (!checkModeAndCollecting(ctx) || !checkMaster(ctx)) return 0;
        Mob mob = findEligibleMob(ctx.getSource().getServer(), name);
        if (mob == null) {
            ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.initiative.mob_not_found", name));
            return 0;
        }
        if (TRACKER.contains(name)) {
            ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.initiative.already_in", name));
            return 0;
        }
        InitiativeRoller.Roll roll;
        try {
            roll = InitiativeRoller.mobRoll(mob);
        } catch (DiceFormula.SyntaxException e) {
            ctx.getSource().sendFailure(e.toComponent());
            return 0;
        }
        TRACKER.add(name, true, (int) roll.total());
        announceEntry(ctx, roll, name);
        return 1;
    }

    /**
     * Rola varios mobs de uma vez: o menu de rolar iniciativa de mob aceita marcar
     * mais de um (06/11/2026). Nome desconhecido, mob sem ficha ou entrada que ja
     * esta na lista sao pulados em silencio; quem chamou mostra o resumo. Sai uma
     * unica sincronizacao no fim, em vez de um pacote por mob.
     */
    static int rollMobBulk(MinecraftServer server, Collection<String> names) {
        List<Component> messages = new ArrayList<>();
        int rolled = 0;
        for (String raw : names) {
            String name = raw == null ? "" : raw.trim();
            if (name.isEmpty() || TRACKER.contains(name)) {
                continue;
            }
            Mob mob = findEligibleMob(server, name);
            if (mob == null) {
                continue;
            }
            InitiativeRoller.Roll roll;
            try {
                roll = InitiativeRoller.mobRoll(mob);
            } catch (DiceFormula.SyntaxException e) {
                continue;
            }
            TRACKER.add(name, true, (int) roll.total());
            messages.add(roll.message());
            messages.add(Component.translatable("message.tabletoprpg.initiative.added", name,
                    (int) roll.total()));
            rolled++;
        }
        if (rolled == 0) {
            return 0;
        }
        for (Component message : messages) {
            server.getPlayerList().broadcastSystemMessage(message, false);
        }
        InitiativeSync.sendAll(server);
        MasterCommands.refreshTurnMenu(server);
        return rolled;
    }

    /**
     * Modo e coleta, sem {@link CommandContext}: as acoes da tela (lote de mobs,
     * adicao e edicao de valores) respondem ao cliente direto. Devolve a mensagem
     * de recusa ou {@code null} quando a acao pode seguir.
     */
    static Component actionBlockReason(MinecraftServer server) {
        SessionManager.GameMode mode = SessionManager.getMode();
        if (mode != SessionManager.GameMode.INVESTIGATION && mode != SessionManager.GameMode.COMBAT) {
            return Component.translatable("message.tabletoprpg.initiative.mode_required");
        }
        if (!TRACKER.isCollecting()) {
            return Component.translatable("message.tabletoprpg.initiative.collecting_required");
        }
        return null;
    }

    /**
     * Mestre adiciona um jogador ou um grupo de mobs. Jogador rola e nunca entra
     * duas vezes; mob reaproveita o valor do grupo se ele ja rolou, senao rola.
     */
    private static int add(CommandContext<CommandSourceStack> ctx) {
        if (!checkModeAndCollecting(ctx) || !checkMaster(ctx)) return 0;
        String name = StringArgumentType.getString(ctx, "nickname").trim();
        Component error = addEntry(ctx.getSource().getServer(), name, null);
        if (error != null) {
            ctx.getSource().sendFailure(error);
            return 0;
        }
        return 1;
    }

    /**
     * Adicao vinda da tela de iniciativa (06/11/2026). O nome e resolvido como
     * jogador online ou mob elegivel, e **nome que ja esta na lista e recusado**:
     * uma entrada por nome, representando o grupo todo (5 zumbis = 1 entrada).
     *
     * <p>{@code value} nulo rola a iniciativa pela ficha (formula da mesa + ficha);
     * valor preenchido e o numero ditado pelo Mestre e nao rola nada. Devolve a
     * mensagem de recusa, ou {@code null} quando entrou.
     */
    static Component addEntry(MinecraftServer server, String name, Integer value) {
        String target = name == null ? "" : name.trim();
        if (target.isEmpty()) {
            return Component.translatable("message.tabletoprpg.initiative.target_not_found", target);
        }
        ServerPlayer online = findOnlinePlayer(server, target);
        Mob mob = online == null ? findEligibleMob(server, target) : null;
        if (online == null && mob == null) {
            return Component.translatable("message.tabletoprpg.initiative.target_not_found", target);
        }
        if (TRACKER.contains(target)) {
            return Component.translatable("message.tabletoprpg.initiative.already_in", target);
        }
        boolean isMob = online == null;
        int total;
        if (value != null) {
            total = value;
            TRACKER.add(target, isMob, total);
        } else {
            InitiativeRoller.Roll roll;
            try {
                roll = isMob ? InitiativeRoller.mobRoll(mob) : InitiativeRoller.playerRoll(online, "");
            } catch (DiceFormula.SyntaxException e) {
                return e.toComponent();
            }
            total = (int) roll.total();
            TRACKER.add(target, isMob, total);
            server.getPlayerList().broadcastSystemMessage(roll.message(), false);
        }
        server.getPlayerList().broadcastSystemMessage(
                Component.translatable("message.tabletoprpg.initiative.added", target, total), false);
        InitiativeSync.sendAll(server);
        MasterCommands.refreshTurnMenu(server);
        return null;
    }

    /**
     * Aplica a edicao da tela "Editar valores" (06/11/2026). Cada item traz a
     * posicao que a tela viu, o nome daquela linha e o valor novo (nulo =
     * remover). A posicao so vale se ainda aponta para o mesmo nome; a lista e
     * aplicada de tras para frente para as remocoes nao deslocarem os indices
     * seguintes. Devolve quantas linhas mudaram.
     */
    static int setValues(MinecraftServer server, List<RpgNetworking.InitiativeEntryEdit> edits) {
        if (edits == null || edits.isEmpty()) {
            return 0;
        }
        List<RpgNetworking.InitiativeEntryEdit> ordered = new ArrayList<>(edits);
        ordered.sort((a, b) -> Integer.compare(b.position(), a.position()));
        int changed = 0;
        for (RpgNetworking.InitiativeEntryEdit edit : ordered) {
            List<InitiativeTracker.Entry> entries = TRACKER.entries();
            int position = edit.position();
            if (position < 0 || position >= entries.size()) {
                continue;
            }
            if (!entries.get(position).name().equals(edit.name())) {
                continue;
            }
            boolean ok = edit.value() == null
                    ? TRACKER.removeAt(position)
                    : TRACKER.setValue(position, edit.value());
            if (ok) {
                changed++;
            }
        }
        if (changed == 0) {
            return 0;
        }
        InitiativeSync.sendAll(server);
        MasterCommands.refreshTurnMenu(server);
        return changed;
    }

    private static int remove(CommandContext<CommandSourceStack> ctx) {
        if (!checkModeAndMaster(ctx)) return 0;
        String name = StringArgumentType.getString(ctx, "nickname").trim();
        int removed = TRACKER.remove(name);
        if (removed == 0) {
            ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.initiative.not_in_list", name));
            return 0;
        }
        broadcast(ctx, Component.translatable("message.tabletoprpg.initiative.removed", name, removed));
        return 1;
    }

    /**
     * {@code /rpg initiative move <de> <para>}: o mestre troca a posicao de um
     * participante na ordem de exibicao. As posicoes sao 1-based, como na lista.
     */
    private static int move(CommandContext<CommandSourceStack> ctx) {
        if (!checkModeAndMaster(ctx)) return 0;
        List<InitiativeTracker.Entry> entries = TRACKER.entries();
        int from = IntegerArgumentType.getInteger(ctx, "from");
        int to = IntegerArgumentType.getInteger(ctx, "to");
        if (from > entries.size() || to > entries.size()) {
            ctx.getSource().sendFailure(Component.translatable(
                    "message.tabletoprpg.initiative.move_invalid", entries.size()));
            return 0;
        }
        String name = entries.get(from - 1).name();
        TRACKER.move(from - 1, to - 1);
        if (from != to) {
            broadcast(ctx, Component.translatable("message.tabletoprpg.initiative.moved", name, to, from));
        }
        return 1;
    }

    /**
     * {@code /rpg initiative delay}: abre a tela para o jogador com a vez escolher a
     * nova posicao dele. So posicoes posteriores aparecem na tela.
     */
    private static int delayOpen(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = delayPlayer(ctx);
        if (player == null) return 0;
        int own = ownPosition(player);
        if (own >= TRACKER.entries().size() - 1) {
            ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.initiative.delay_no_later"));
            return 0;
        }
        // Estado fresco na tela antes de abrir: a lista pode ter mudado neste turno.
        InitiativeSync.sendTo(player);
        InitiativeSync.sendOpenDelay(player);
        return 1;
    }

    /**
     * {@code /rpg initiative delay <posicao>}: move a propria entrada para uma
     * posicao posterior e encerra a vez na hora (Passar a vez + alterar a iniciativa).
     */
    private static int delay(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = delayPlayer(ctx);
        if (player == null) return 0;
        List<InitiativeTracker.Entry> entries = TRACKER.entries();
        int own = ownPosition(player);
        int target = IntegerArgumentType.getInteger(ctx, "posicao");
        if (target > entries.size()) {
            ctx.getSource().sendFailure(Component.translatable(
                    "message.tabletoprpg.initiative.move_invalid", entries.size()));
            return 0;
        }
        if (target - 1 <= own) {
            ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.initiative.delay_no_later"));
            return 0;
        }
        String name = entries.get(own).name();
        TRACKER.move(own, target - 1);
        broadcast(ctx, Component.translatable("message.tabletoprpg.initiative.delayed", name, target), false);
        // A posicao que ficou vaga e a proxima da fila depois da troca.
        TurnManager.endTurn(ctx.getSource().getServer(), player, own);
        return 1;
    }

    /**
     * Jogador que pode atrasar a propria entrada: modo valido, coleta aberta, e ele
     * e o dono da vez na lista. Mestre sem a vez usa {@code /rpg initiative move}.
     */
    private static ServerPlayer delayPlayer(CommandContext<CommandSourceStack> ctx) {
        if (!checkModeAndCollecting(ctx)) return null;
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
            ctx.getSource().sendFailure(Component.translatable(
                    "message.tabletoprpg.initiative.delay_players_only"));
            return null;
        }
        if (!SessionManager.isActivePlayer(player) || ownPosition(player) < 0) {
            ctx.getSource().sendFailure(Component.translatable(
                    "message.tabletoprpg.initiative.delay_not_your_turn"));
            return null;
        }
        return player;
    }

    /** Posicao 0-based da entrada de jogador com esse nome, ou -1. */
    private static int ownPosition(ServerPlayer player) {
        return TRACKER.indexOf(player.getName().getString(), false);
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        if (!checkMode(ctx)) return 0;
        List<InitiativeTracker.Entry> entries = TRACKER.entries();
        if (entries.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.translatable("message.tabletoprpg.initiative.empty"), false);
            return 1;
        }
        ctx.getSource().sendSuccess(() -> Component.translatable(
                "message.tabletoprpg.initiative.list_header", TRACKER.round()), false);
        for (InitiativeTracker.Entry entry : entries) {
            ctx.getSource().sendSuccess(() -> Component.translatable(
                    "message.tabletoprpg.initiative.list_line", entry.name(), entry.value()), false);
        }
        return 1;
    }

    // ------------------------------------------------------------------
    // VALIDACAO
    // ------------------------------------------------------------------

    private static boolean checkMode(CommandContext<CommandSourceStack> ctx) {
        SessionManager.GameMode mode = SessionManager.getMode();
        if (mode == SessionManager.GameMode.INVESTIGATION || mode == SessionManager.GameMode.COMBAT) {
            return true;
        }
        ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.initiative.mode_required"));
        return false;
    }

    private static boolean checkModeAndMaster(CommandContext<CommandSourceStack> ctx) {
        return checkMode(ctx) && checkMaster(ctx);
    }

    private static boolean checkModeAndCollecting(CommandContext<CommandSourceStack> ctx) {
        if (!checkMode(ctx)) return false;
        if (TRACKER.isCollecting()) return true;
        ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.initiative.collecting_required"));
        return false;
    }

    /**
     * Com a caixa "usar iniciativa padrao da mesa" desmarcada, o jogador rola a
     * iniciativa com a propria pericia ({@code /rpg roll <pericia>}), e o comando
     * {@code /rpg initiative roll} nao rola por ele.
     */
    private static boolean checkSkillRollRequired(CommandContext<CommandSourceStack> ctx) {
        SheetModel model = SheetModelHolder.current();
        if (model.initiativeUseDefault()) {
            return true;
        }
        SheetModel.PericiaDef skill = model.periciaById(model.initiativePericiaId());
        if (skill == null) {
            ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.initiative.no_skill"));
        } else {
            ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.initiative.skill_roll", skill.name()));
        }
        return false;
    }

    private static boolean checkMaster(CommandContext<CommandSourceStack> ctx) {
        if (ctx.getSource().getEntity() instanceof ServerPlayer player && SessionManager.isMaster(player)) {
            return true;
        }
        ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.master_only_action"));
        return false;
    }

    // ------------------------------------------------------------------
    // MUNDO
    // ------------------------------------------------------------------

    /** Mobs com camera e ficha vinculada: os unicos que entram na iniciativa. */
    private static List<Mob> eligibleMobs(MinecraftServer server) {
        List<Mob> mobs = new ArrayList<>();
        for (UUID uuid : CombatController.getCameraMobs()) {
            for (ServerLevel level : server.getAllLevels()) {
                Entity entity = level.getEntity(uuid);
                if (entity instanceof Mob mob) {
                    if (!ThreatSheetBinding.sheetOf(mob).isEmpty()) {
                        mobs.add(mob);
                    }
                    break;
                }
            }
        }
        return mobs;
    }

    /** Primeiro mob elegivel com aquele nome de exibicao, ou {@code null}. */
    private static Mob findEligibleMob(MinecraftServer server, String name) {
        for (Mob mob : eligibleMobs(server)) {
            if (mob.getName().getString().equals(name)) {
                return mob;
            }
        }
        return null;
    }

    private static ServerPlayer findOnlinePlayer(MinecraftServer server, String name) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.getName().getString().equals(name)) {
                return player;
            }
        }
        return null;
    }

    /** Mensagem da rolagem (mesmo formato do /rpg roll) e, logo depois, a entrada na lista. */
    private static void announceEntry(CommandContext<CommandSourceStack> ctx, InitiativeRoller.Roll roll, String name) {
        broadcast(ctx, roll.message());
        broadcast(ctx, Component.translatable("message.tabletoprpg.initiative.added", name, (int) roll.total()));
    }

    /** Toda mensagem de mudanca da iniciativa tambem atualiza a lista nos clientes. */
    private static void broadcast(CommandContext<CommandSourceStack> ctx, Component message) {
        broadcast(ctx, message, true);
    }

    /**
     * {@code refreshMenu == false} no atraso de turno: o menu de quem esta saindo
     * seria reenviado com acoes que ele ja perdeu.
     */
    private static void broadcast(CommandContext<CommandSourceStack> ctx, Component message,
                                  boolean refreshMenu) {
        MinecraftServer server = ctx.getSource().getServer();
        server.getPlayerList().broadcastSystemMessage(message, false);
        InitiativeSync.sendAll(server);
        if (refreshMenu) {
            MasterCommands.refreshTurnMenu(server);
        }
    }

    /**
     * Estado atual para os clientes: coleta aberta, participantes e, so para o
     * mestre, os candidatos que a tela usa para adicionar ou rolar.
     */
    public static InitiativeSync.Payload snapshot(MinecraftServer server, boolean withCandidates) {
        List<InitiativeSync.Entry> entries = new ArrayList<>();
        for (InitiativeTracker.Entry entry : TRACKER.entries()) {
            entries.add(new InitiativeSync.Entry(entry.name(), entry.mob(), entry.value()));
        }
        if (!withCandidates) {
            return new InitiativeSync.Payload(TRACKER.isCollecting(), TRACKER.round(), entries, List.of());
        }
        Set<InitiativeSync.Candidate> candidates = new LinkedHashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            candidates.add(new InitiativeSync.Candidate(player.getName().getString(), false));
        }
        for (Mob mob : eligibleMobs(server)) {
            candidates.add(new InitiativeSync.Candidate(mob.getName().getString(), true));
        }
        return new InitiativeSync.Payload(TRACKER.isCollecting(), TRACKER.round(), entries,
                List.copyOf(candidates));
    }

    // ------------------------------------------------------------------
    // SUGESTOES
    // ------------------------------------------------------------------

    private static CompletableFuture<Suggestions> suggestRollableMobs(CommandContext<CommandSourceStack> ctx,
                                                                      SuggestionsBuilder builder) {
        List<String> names = new ArrayList<>();
        for (Mob mob : eligibleMobs(ctx.getSource().getServer())) {
            names.add(mob.getName().getString());
        }
        return suggestNames(builder, names);
    }

    private static CompletableFuture<Suggestions> suggestAddable(CommandContext<CommandSourceStack> ctx,
                                                                 SuggestionsBuilder builder) {
        MinecraftServer server = ctx.getSource().getServer();
        List<String> names = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            names.add(player.getName().getString());
        }
        for (Mob mob : eligibleMobs(server)) {
            names.add(mob.getName().getString());
        }
        return suggestNames(builder, names);
    }

    private static CompletableFuture<Suggestions> suggestInList(CommandContext<CommandSourceStack> ctx,
                                                                SuggestionsBuilder builder) {
        List<String> names = new ArrayList<>();
        for (InitiativeTracker.Entry entry : TRACKER.entries()) {
            names.add(entry.name());
        }
        return suggestNames(builder, names);
    }

    private static CompletableFuture<Suggestions> suggestNames(SuggestionsBuilder builder, Collection<String> names) {
        String remaining = builder.getRemainingLowerCase();
        for (String name : new LinkedHashSet<>(names)) {
            if (name.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                builder.suggest(name);
            }
        }
        return builder.buildFuture();
    }
}
