package com.pedro.tabletoprpg;

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

    private InitiativeCommands() {
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
                                .executes(InitiativeCommands::remove)));
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
        server.getPlayerList().broadcastSystemMessage(
                Component.translatable("message.tabletoprpg.initiative.ended_by_mode"), false);
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
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
            ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.roll_players_only"));
            return 0;
        }
        String name = player.getName().getString();
        if (TRACKER.contains(name)) {
            ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.initiative.already_in", name));
            return 0;
        }
        int value;
        try {
            value = (int) (InitiativeRoller.playerTotal(player) + extraTotal(extra));
        } catch (DiceFormula.SyntaxException e) {
            ctx.getSource().sendFailure(e.toComponent());
            return 0;
        }
        TRACKER.add(name, false, value);
        broadcast(ctx, Component.translatable("message.tabletoprpg.initiative.rolled", name, value));
        return 1;
    }

    /** {@code /rpg initiative roll <arg>}: "+..." e formula extra; qualquer outro texto e nome de mob. */
    private static int rollWithArg(CommandContext<CommandSourceStack> ctx) {
        String arg = StringArgumentType.getString(ctx, "arg").trim();
        if (arg.startsWith("+") || arg.startsWith("-")) {
            return rollSelf(ctx, arg);
        }
        return rollMob(ctx, arg);
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
        int value;
        try {
            value = (int) InitiativeRoller.mobTotal(mob);
        } catch (DiceFormula.SyntaxException e) {
            ctx.getSource().sendFailure(e.toComponent());
            return 0;
        }
        TRACKER.add(name, true, value);
        broadcast(ctx, Component.translatable("message.tabletoprpg.initiative.rolled", name, value));
        return 1;
    }

    /**
     * Mestre adiciona um jogador ou um grupo de mobs. Jogador rola e nunca entra
     * duas vezes; mob reaproveita o valor do grupo se ele ja rolou, senao rola.
     */
    private static int add(CommandContext<CommandSourceStack> ctx) {
        if (!checkModeAndCollecting(ctx) || !checkMaster(ctx)) return 0;
        String name = StringArgumentType.getString(ctx, "nickname").trim();
        MinecraftServer server = ctx.getSource().getServer();

        ServerPlayer online = findOnlinePlayer(server, name);
        if (online != null) {
            if (TRACKER.contains(name)) {
                ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.initiative.already_in", name));
                return 0;
            }
            int value;
            try {
                value = (int) InitiativeRoller.playerTotal(online);
            } catch (DiceFormula.SyntaxException e) {
                ctx.getSource().sendFailure(e.toComponent());
                return 0;
            }
            TRACKER.add(name, false, value);
            broadcast(ctx, Component.translatable("message.tabletoprpg.initiative.added", name, value));
            return 1;
        }

        Mob mob = findEligibleMob(server, name);
        if (mob == null) {
            ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.initiative.target_not_found", name));
            return 0;
        }
        Integer groupValue = TRACKER.valueOf(name);
        int value;
        try {
            value = groupValue != null ? groupValue : (int) InitiativeRoller.mobTotal(mob);
        } catch (DiceFormula.SyntaxException e) {
            ctx.getSource().sendFailure(e.toComponent());
            return 0;
        }
        TRACKER.add(name, true, value);
        broadcast(ctx, Component.translatable("message.tabletoprpg.initiative.added", name, value));
        return 1;
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

    private static int list(CommandContext<CommandSourceStack> ctx) {
        if (!checkMode(ctx)) return 0;
        List<InitiativeTracker.Entry> entries = TRACKER.entries();
        if (entries.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.translatable("message.tabletoprpg.initiative.empty"), false);
            return 1;
        }
        ctx.getSource().sendSuccess(() -> Component.translatable("message.tabletoprpg.initiative.list_header"), false);
        for (InitiativeTracker.Entry entry : entries) {
            ctx.getSource().sendSuccess(() -> Component.translatable(
                    "message.tabletoprpg.initiative.list_line", entry.name(), entry.value()), false);
        }
        return 1;
    }

    /** Total da formula extra com sinal: "+1d20" soma, "-2" subtrai. Vazio e zero. */
    private static long extraTotal(String extra) throws DiceFormula.SyntaxException {
        if (extra.isEmpty()) {
            return 0;
        }
        long sign = extra.charAt(0) == '-' ? -1 : 1;
        return sign * InitiativeRoller.evaluate(extra.substring(1).trim());
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

    private static void broadcast(CommandContext<CommandSourceStack> ctx, Component message) {
        ctx.getSource().getServer().getPlayerList().broadcastSystemMessage(message, false);
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
