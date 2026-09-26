package com.pedro.tabletoprpg;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MasterCommands {

    private static final Random RANDOM = new Random();

    public static void register() {
        CommandRegistrationCallback.EVENT.register(MasterCommands::onRegister);
    }

    private static void onRegister(CommandDispatcher<CommandSourceStack> dispatcher,
                                    CommandBuildContext buildContext,
                                    Commands.CommandSelection selection) {

        dispatcher.register(
            Commands.literal("rpg")
                // Sem argumentos -> Abre o Menu Interativo
                .executes(MasterCommands::openMenu)

                .then(Commands.literal("menu")
                    .executes(MasterCommands::openMenu))

                // /rpg master <claim|release>
                .then(Commands.literal("master")
                    .then(Commands.literal("claim")
                        .executes(MasterCommands::claimMaster))
                    .then(Commands.literal("release")
                        .executes(MasterCommands::releaseMaster)))

                // /rpg mode <free|investigation|combat>
                .then(Commands.literal("mode")
                    .then(Commands.literal("free")
                        .executes(ctx -> setMode(ctx, SessionManager.GameMode.FREE)))
                    .then(Commands.literal("investigation")
                        .executes(ctx -> setMode(ctx, SessionManager.GameMode.INVESTIGATION)))
                    .then(Commands.literal("combat")
                        .executes(ctx -> setMode(ctx, SessionManager.GameMode.COMBAT))))

                // /rpg turn <give|revoke|finish>
                .then(Commands.literal("turn")
                    .then(Commands.literal("give")
                        // Autocompletar nativo de jogadores do Minecraft
                        .then(Commands.argument("player", EntityArgument.player())
                            .executes(MasterCommands::giveTurn)))
                    .then(Commands.literal("revoke")
                        .executes(MasterCommands::revokeTurn))
                    .then(Commands.literal("finish")
                        .executes(MasterCommands::finishTurn)))

                // /rpg roll                  -> lista as pericias da ficha
                // /rpg roll <pericia>         -> 1d20 + valor da pericia + atributo
                // /rpg roll <formula>         -> d20, 2d6, d20+10, 2d6+d4+3
                // Mestre: resultado privado. Jogador: resultado público.
                .then(Commands.literal("roll")
                    .executes(MasterCommands::listSkills)
                    .then(Commands.argument("formula", StringArgumentType.greedyString())
                        .suggests(MasterCommands::suggestSkills)
                        .executes(MasterCommands::rollOrSkill)))

                // /rpg openroll <formula>  (só o mestre) -> rolagem pública para todos
                .then(Commands.literal("openroll")
                    .executes(ctx -> openRoll(ctx, "d20"))
                    .then(Commands.argument("formula", StringArgumentType.greedyString())
                        .executes(ctx -> openRoll(ctx, StringArgumentType.getString(ctx, "formula")))))



                // /rpg time <0-24000>  (só o mestre) -> define o horário do mundo
                .then(Commands.literal("time")
                    .then(Commands.argument("value", IntegerArgumentType.integer(0, 24000))
                        .executes(MasterCommands::setWorldTime)))

                // /rpg hoverdistance <1-256>  (só o mestre) -> distância do highlight dos jogadores
                .then(Commands.literal("hoverdistance")
                    .then(Commands.argument("value", IntegerArgumentType.integer(1, 256))
                        .executes(MasterCommands::setHoverDistance)))

                // /rpg session set <nome>  (só o mestre) -> define o nome da sessão
                .then(Commands.literal("session")
                    .then(Commands.literal("set")
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                            .executes(MasterCommands::setSessionName))))

                // /rpg insert enemy <type> <cam_perm true|false>
                .then(Commands.literal("insert")
                    .then(Commands.literal("enemy")
                        .then(Commands.argument("type", StringArgumentType.string())
                            .suggests(MasterCommands::suggestEntityTypes)
                            .then(Commands.argument("cam_perm", BoolArgumentType.bool())
                                .executes(MasterCommands::insertEnemy)))))

                // /rpg remove enemy  (só o mestre) -> remove o mob selecionado
                // (clique direito nele primeiro). 1 mob por execução.
                .then(Commands.literal("remove")
                    .then(Commands.literal("enemy")
                        .executes(MasterCommands::removeEnemy))));
    }

    // --- COMANDOS E LÓGICA ---

    private static int openMenu(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
            ctx.getSource().sendFailure(Component.literal("Only players can open the RPG menu."));
            return 0;
        }

        boolean isMaster = SessionManager.isMaster(player);
        player.sendSystemMessage(buildAsciiMenu(player, isMaster));
        return 1;
    }

    private static int claimMaster(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            if (SessionManager.setMaster(player)) {
                broadcast(ctx, "§6§e" + player.getName().getString() + " §fis now the §bGame Master§f!");
                RpgNetworking.sendToAll(ctx.getSource().getServer());
                refreshChatMenu(ctx);
                return 1;
            } else {
                ctx.getSource().sendFailure(Component.literal("§c[RPG] A Game Master is already assigned (" + SessionManager.getMasterName() + ")."));
                return 0;
            }
        } catch (Exception e) {
            TabletopRpg.LOGGER.error("[TabletopRPG] Error claiming master: {}", e.getMessage());
            ctx.getSource().sendFailure(Component.literal("§c[RPG] Failed to claim Game Master role."));
            return 0;
        }
    }

    private static int releaseMaster(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            if (SessionManager.isMaster(player)) {
                SessionManager.releaseMaster();
                CombatController.reset(ctx.getSource().getServer());
                broadcast(ctx, "§6§e" + player.getName().getString() + " §fhas stepped down as Game Master.");
                RpgNetworking.sendToAll(ctx.getSource().getServer());
                RpgNetworking.sendAuraStateToAll(ctx.getSource().getServer());
                // O reset limpou os mobs com câmera: atualiza o carrossel.
                RpgNetworking.sendSpectatorTargetsToAll(ctx.getSource().getServer());
                refreshChatMenu(ctx);
                return 1;
            } else {
                ctx.getSource().sendFailure(Component.literal("§c[RPG] You are not the current Game Master."));
                return 0;
            }
        } catch (Exception e) {
            TabletopRpg.LOGGER.error("[TabletopRPG] Error releasing master: {}", e.getMessage());
            ctx.getSource().sendFailure(Component.literal("§c[RPG] Failed to release Game Master role."));
            return 0;
        }
    }

    private static int setMode(CommandContext<CommandSourceStack> ctx, SessionManager.GameMode mode) {
        if (!verifyMasterPermission(ctx)) return 0;

        SessionManager.setMode(mode);
        if (mode == SessionManager.GameMode.FREE) {
            // Fim do encontro: limpa seleção, âncoras e monstros controlados.
            CombatController.reset(ctx.getSource().getServer());
        }
        broadcast(ctx, "§6Game Mode changed to: §e" + mode.getDisplayName());
        RpgNetworking.sendToAll(ctx.getSource().getServer());
        RpgNetworking.sendAuraStateToAll(ctx.getSource().getServer());
        // Se o reset limpou os mobs com câmera, atualiza o carrossel.
        RpgNetworking.sendSpectatorTargetsToAll(ctx.getSource().getServer());
        refreshChatMenu(ctx);
        return 1;
    }

    private static int giveTurn(CommandContext<CommandSourceStack> ctx) {
        if (!verifyMasterPermission(ctx)) return 0;

        try {
            ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
            SessionManager.setActivePlayer(target);
            // Âncora do jogador ativo: onde ele começou o turno (limite da aura).
            CombatController.setPlayerAnchor(target);
            broadcast(ctx, "§6§aTurn granted to §e" + target.getName().getString() + "§a!");
            target.sendSystemMessage(Component.literal("§aIt is now YOUR TURN! Move and act freely."));
            RpgNetworking.sendToAll(ctx.getSource().getServer());
            RpgNetworking.sendAuraStateToAll(ctx.getSource().getServer());
            refreshChatMenu(ctx);
            return 1;
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("§cPlayer not found."));
            return 0;
        }
    }

    private static int revokeTurn(CommandContext<CommandSourceStack> ctx) {
        if (!verifyMasterPermission(ctx)) return 0;

        String prevPlayer = SessionManager.getActivePlayerName();
        UUID prevUuid = SessionManager.getActivePlayerUuid();
        SessionManager.clearActivePlayer();
        if (prevUuid != null) {
            CombatController.clearPlayerAnchor(prevUuid);
        }
        broadcast(ctx, "§6§cTurn for §e" + prevPlayer + " §chas been revoked by the Master.");
        RpgNetworking.sendToAll(ctx.getSource().getServer());
        RpgNetworking.sendAuraStateToAll(ctx.getSource().getServer());
        refreshChatMenu(ctx);
        return 1;
    }

    private static int finishTurn(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer player = ctx.getSource().getPlayerOrException();

            if (!SessionManager.isActivePlayer(player) && !SessionManager.isMaster(player)) {
                ctx.getSource().sendFailure(Component.literal("§c[RPG] It is not your turn to finish!"));
                return 0;
            }

            UUID prevUuid = SessionManager.getActivePlayerUuid();
            SessionManager.clearActivePlayer();
            if (prevUuid != null) {
                CombatController.clearPlayerAnchor(prevUuid);
            }
            broadcast(ctx, "§6§e" + player.getName().getString() + " §fhas finished their turn.");
            RpgNetworking.sendToAll(ctx.getSource().getServer());
            RpgNetworking.sendAuraStateToAll(ctx.getSource().getServer());
            refreshChatMenu(ctx);
            return 1;
        } catch (Exception e) {
            TabletopRpg.LOGGER.error("[TabletopRPG] Error finishing turn: {}", e.getMessage());
            ctx.getSource().sendFailure(Component.literal("§c[RPG] Failed to finish turn."));
            return 0;
        }
    }

    private static int setSessionName(CommandContext<CommandSourceStack> ctx) {
        if (!verifyMasterPermission(ctx)) return 0;

        String name = StringArgumentType.getString(ctx, "name").trim();
        if (name.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("§cSession name cannot be empty."));
            return 0;
        }
        if (name.length() > 64) {
            name = name.substring(0, 64);
        }

        SessionManager.setSessionName(name);
        broadcast(ctx, "§6Session name set to: §e\"" + name + "\"");
        RpgNetworking.sendToAll(ctx.getSource().getServer());
        refreshChatMenu(ctx);
        return 1;
    }

    /**
     * Sugere tipos de entidade do registro nativo (ex: "minecraft:zombie",
     * "minecraft:spider"). Como lê o registro, entidades adicionadas por
     * outros mods aparecem automaticamente nas sugestões.
     */
    private static CompletableFuture<Suggestions> suggestEntityTypes(CommandContext<CommandSourceStack> ctx,
                                                                     SuggestionsBuilder builder) {
        String remaining = builder.getRemainingLowerCase();
        for (Identifier id : BuiltInRegistries.ENTITY_TYPE.keySet()) {
            // Só monstros/NPCs de combate (vanilla + mods), nada de flecha/barco.
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(id);
            if (type == null || type.getCategory() != MobCategory.MONSTER) {
                continue;
            }
            // Entidades vanilla: sugere só o nome curto ("zombie"), pois o argumento
            // é string() e o jogador digitaria "minecraft:zombie" e falharia.
            String suggestion = "minecraft".equals(id.getNamespace())
                ? id.getPath()
                : id.toString();
            if (suggestion.startsWith(remaining)) {
                builder.suggest(suggestion);
            }
        }
        return builder.buildFuture();
    }

    private static int insertEnemy(CommandContext<CommandSourceStack> ctx) {
        if (!verifyMasterPermission(ctx)) return 0;

        String type = StringArgumentType.getString(ctx, "type").trim();
        boolean camPerm = BoolArgumentType.getBool(ctx, "cam_perm");

        try {
            ServerPlayer master = ctx.getSource().getPlayerOrException();
            ServerLevel serverLevel = (ServerLevel) master.level();

            // Lookup do tipo de entidade no registro nativo (aceita "zombie" ou "minecraft:zombie")
            Identifier id = Identifier.tryParse(type.contains(":") ? type : "minecraft:" + type);
            if (id == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
                ctx.getSource().sendFailure(Component.literal("§c[RPG] Unknown enemy type: " + type));
                return 0;
            }
            EntityType<?> entityType = BuiltInRegistries.ENTITY_TYPE.getValue(id);

            // Posicionar entidade na frente do mestre (2 blocos, na direção do olhar)
            double masterX = master.getX();
            double masterY = master.getY();
            double masterZ = master.getZ();
            float yaw = master.getYRot();
            double rad = Math.toRadians(yaw);
            double spawnX = masterX - Math.sin(rad) * 2.0;
            double spawnZ = masterZ + Math.cos(rad) * 2.0;

            // Cria a entidade com a API do 1.21.11 (EntitySpawnReason.COMMAND)
            Entity entity = entityType.create(serverLevel, EntitySpawnReason.COMMAND);
            if (entity == null) {
                ctx.getSource().sendFailure(Component.literal("§c[RPG] Failed to create entity."));
                return 0;
            }

            entity.setPos(spawnX, masterY, spawnZ);
            serverLevel.addFreshEntity(entity);

            // Se for um Mob: desativa a IA (não age sozinho), impede despawn
            // natural e o torna invulnerável (é uma "peça" da mesa, não um
            // mob de combate real — os jogadores não o atacam diretamente).
            if (entity instanceof Mob mob) {
                mob.setNoAi(true);
                mob.setPersistenceRequired();
                mob.setInvulnerable(true);
                // Marca NBT persistente: sobrevive ao reinício do servidor e
                // permite re-registrar o mob no carrossel/lookAt (o Set em
                // memória é perdido no restart). Tags vanilla são salvas no
                // NBT da entidade ("Tags") — não há getPersistentData() em
                // 1.21.11.
                mob.addTag("tabletoprpg_inserted");
                CombatController.addInsertedMob(mob.getUUID());
                // Com câmera (cam_perm=true): o mob entra no carrossel de
                // espectador dos jogadores travados (FASE 2).
                if (camPerm) {
                    TabletopRpg.LOGGER.info("[TabletopRPG] Enemy {} summoned with cam_perm=true by {}", type, master.getName().getString());
                    mob.addTag("tabletoprpg_camera");
                    CombatController.addCameraMob(mob.getUUID());
                    RpgNetworking.sendSpectatorTargetsToAll(serverLevel.getServer());
                }
            }

            // Mensagem de confirmação
            broadcast(ctx, "§6§e" + type + " §fsummoned by the Master at spawn position.");
            master.sendSystemMessage(Component.literal("§aAn enemy has been summoned!"));

            return 1;
        } catch (Exception e) {
            TabletopRpg.LOGGER.error("[TabletopRPG] Error summoning enemy: {}", e.getMessage());
            ctx.getSource().sendFailure(Component.literal("§c[RPG] Failed to summon enemy."));
            return 0;
        }
    }

    /**
     * /rpg remove enemy: remove o mob atualmente selecionado pelo mestre
     * (clique direito nele primeiro). 1 mob por execução — para remover
     * outro, o mestre precisa executar o comando de novo.
     */
    private static int removeEnemy(CommandContext<CommandSourceStack> ctx) {
        if (!verifyMasterPermission(ctx)) return 0;

        try {
            ServerPlayer master = ctx.getSource().getPlayerOrException();
            ServerLevel serverLevel = (ServerLevel) master.level();

            Mob selected = CombatController.getSelectedMonster(serverLevel);
            if (selected == null) {
                ctx.getSource().sendFailure(Component.literal(
                        "§c[RPG] No enemy selected. Right-click an enemy first, then run /rpg remove enemy."));
                return 0;
            }

            String name = selected.getName().getString();
            CombatController.removeSelectedMonster(serverLevel);
            broadcast(ctx, "§6§e" + name + " §fremoved by the Master.");
            RpgNetworking.sendAuraStateToAll(ctx.getSource().getServer());
            RpgNetworking.sendSpectatorTargetsToAll(ctx.getSource().getServer());
            return 1;
        } catch (Exception e) {
            TabletopRpg.LOGGER.error("[TabletopRPG] Error removing enemy: {}", e.getMessage());
            ctx.getSource().sendFailure(Component.literal("§c[RPG] Failed to remove enemy."));
            return 0;
        }
    }

    private static final Pattern DICE_TERM = Pattern.compile("^(\\d*)[dD](\\d+)$");
    private static final Pattern MODIFIER_TERM = Pattern.compile("^\\d+$");

    /**
     * Decide o que {@code /rpg roll <argumento>} quer dizer: o nome de uma
     * pericia da ficha, ou uma formula de dados.
     *
     * <p><b>Por que as duas coisas no mesmo comando:</b> o jogador ja tem
     * "/rpg roll" na mao e a nova regra de pericias ({@code 1d20 + valor +
     * atributo}) e a rolagem que ele mais vai fazer. Se exigisse um comando
     * novo, ele teria que aprender dois comandos. O nome da pericia so e
     * reconhecido quando casa <b>exatamente</b> com o nome guardado; qualquer
     * outra coisa cai na formula, entao {@code d20} continua funcionando.
     */
    private static int rollOrSkill(CommandContext<CommandSourceStack> ctx) {
        String raw = StringArgumentType.getString(ctx, "formula");
        ServerPlayer player = playerOf(ctx);
        if (player == null) {
            return 0;
        }
        SheetData.Skill skill = findSkill(player, raw);
        if (skill != null) {
            return rollSkill(ctx, player, skill);
        }
        return rollFormula(ctx, raw);
    }

    /** Sender como jogador, ou {@code null} (com a mensagem de erro ja enviada). */
    private static ServerPlayer playerOf(CommandContext<CommandSourceStack> ctx) {
        try {
            return ctx.getSource().getPlayerOrException();
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("§cOnly players can roll dice."));
            return null;
        }
    }

    /**
     * Procura uma pericia pelo nome, sem diferenciar maiusculas e espacos.
     *
     * <p>Os nomes padrao includem acento ("perícia 0"), e o jogador digita
     * "pericia 0" no teclado sem acento: por isso a comparacao tambem ignora
     * acentos. Devolve {@code null} se nao casar -- o chamador entao trata o
     * texto como formula.
     */
    private static SheetData.Skill findSkill(ServerPlayer player, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String wanted = normalize(raw);
        SheetData sheet = SessionManager.getSheet(player.getUUID());
        if (sheet == null) {
            return null;
        }
        for (SheetData.Skill skill : sheet.skills()) {
            if (normalize(skill.name()).equals(wanted)) {
                return skill;
            }
        }
        return null;
    }

    /** Minusculas, sem acento e sem espacos extras, so para comparar nomes. */
    private static String normalize(String text) {
        String plain = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", ""); // tira o acento, deixa a letra
        return plain.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Rola a pericia: {@code 1d20 + valor + atributo}.
     *
     * <p><b>Fórmula (pedido do usuario):</b> um d20, o valor da pericia (0-3) e
     * o atributo que ela soma. Ex.: "Luta 2 + FOR 3" = 1d20 + 5. O resultado
     * e separado em "1d20 (12) + 2 + 3 = 17" para o jogador ver de onde saiu.
     *
     * <p>A aritmética e feita em {@code long} e convertida no fim: o atributo
     * nao tem teto (decisao do usuario), e {@code int} estouraria com um
     * atributo absurdo e mostraria um numero negativo na rolagem.
     *
     * <p>Visibilidade igual a {@code /rpg roll} normal: o mestre ve so para si,
     * o jogador ve para todos.
     */
    private static int rollSkill(CommandContext<CommandSourceStack> ctx, ServerPlayer player,
                                 SheetData.Skill skill) {
        SheetData sheet = SessionManager.getSheet(player.getUUID());
        // RNG do servidor (exigido pelo .docx: o cliente nao pode prever o
        // resultado). CommandSourceStack#getRandom e MinecraftServer#getRandom
        // nao existem nesta versao; Entity#getRandom devolve o RandomSource do
        // level, que e exatamente o do servidor.
        int die = 1 + player.getRandom().nextInt(20);
        long attrValue = sheet.getNumeric(skill.attribute().field());
        long total = (long) die + skill.value() + attrValue;

        String message = "§6§l" + player.getName().getString() + " §frolled §6" + skill.name() + "§f: "
                + "§7d20 §f(§e" + die + "§f) + §7" + skill.value()
                + " + §7" + skill.attribute().abbr() + " " + attrValue
                + " = §e§l" + total;

        if (SessionManager.isMaster(player)) {
            player.sendSystemMessage(Component.literal(message));
        } else {
            broadcast(ctx, message);
        }
        return 1;
    }

    /**
     * {@code /rpg roll} sem argumento: lista as pericias com o quanto cada uma
     * soma, para o jogador lembrar o nome exato e o total.
     *
     * <p><b>Mudança de comportamento:</b> antes este comando rolava {@code d20}
     * fixo. Virou lista porque é o que o jogador precisa descobrir; quem quiser
     * o d20 puro escreve {@code /rpg roll d20}.
     */
    private static int listSkills(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = playerOf(ctx);
        if (player == null) {
            return 0;
        }
        SheetData sheet = SessionManager.getSheet(player.getUUID());
        if (sheet == null || sheet.skills().isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("§cNo skills on this sheet."));
            return 0;
        }

        ctx.getSource().sendSystemMessage(Component.literal(
                "§6Skills §7(§f/rpg roll <name>§7): " + sheet.skills().size()));
        for (SheetData.Skill skill : sheet.skills()) {
            long attr = sheet.getNumeric(skill.attribute().field());
            ctx.getSource().sendSystemMessage(Component.literal(
                    "§7- §f" + skill.name() + " §8| §e" + skill.value()
                            + " §7+ §e" + attr + " §8(" + skill.attribute().abbr() + ")"
                            + " §8= §e" + (skill.value() + attr)));
        }
        return sheet.skills().size();
    }

    /** Autocompletar os nomes de pericia da ficha, junto das fórmulas. */
    private static CompletableFuture<Suggestions> suggestSkills(CommandContext<CommandSourceStack> ctx,
                                                                SuggestionsBuilder builder) {
        String remaining = builder.getRemainingLowerCase();
        try {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            SheetData sheet = SessionManager.getSheet(player.getUUID());
            if (sheet != null) {
                for (SheetData.Skill skill : sheet.skills()) {
                    if (normalize(skill.name()).startsWith(remaining)) {
                        builder.suggest(skill.name());
                    }
                }
            }
        } catch (Exception e) {
            // Sem jogador (console / block command): só sugere fórmulas.
        }
        return builder.buildFuture();
    }

    /**
     * Rola uma fórmula de dados no formato "d20", "2d6", "d20+10",
     * "2d6+d4+3", etc. Termos são separados por "+". Cada termo é ou um
     * dado (NdM) ou um número fixo somado ao resultado.
     *
     * Regras de visibilidade:
     *  - O Mestre usando /rpg roll vê o resultado SOMENTE nele (rolagem secreta).
     *  - O Jogador usando /rpg roll envia o resultado para todos.
     *  - O Mestre usando /rpg openroll envia o resultado para todos.
     */
    private static int rollFormula(CommandContext<CommandSourceStack> ctx, String rawFormula) {
        ServerPlayer player;
        try {
            player = ctx.getSource().getPlayerOrException();
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("§cOnly players can roll dice."));
            return 0;
        }

        String message = rollDice(ctx, rawFormula);
        if (message == null) {
            return 0;
        }

        if (SessionManager.isMaster(player)) {
            // Rolagem secreta do mestre: aparece apenas para ele.
            player.sendSystemMessage(Component.literal(message));
        } else {
            // Rolagem do jogador: visível para todos.
            broadcast(ctx, message);
        }
        return 1;
    }

    /** Rolagem pública (só o mestre): todos veem. */
    private static int openRoll(CommandContext<CommandSourceStack> ctx, String rawFormula) {
        if (!verifyMasterPermission(ctx)) return 0;

        String message = rollDice(ctx, rawFormula);
        if (message == null) {
            return 0;
        }
        broadcast(ctx, message);
        return 1;
    }

/** Define o horário do mundo (0-24000 ticks). Só o mestre. */
    private static int setWorldTime(CommandContext<CommandSourceStack> ctx) {
        if (!verifyMasterPermission(ctx)) return 0;

        try {
            ServerPlayer master = ctx.getSource().getPlayerOrException();
            int time = IntegerArgumentType.getInteger(ctx, "value");
            ServerLevel level = (ServerLevel) master.level();
            level.setDayTime(time);
            broadcast(ctx, "§6World time set to: §e" + time);
            return 1;
        } catch (Exception e) {
            TabletopRpg.LOGGER.error("[TabletopRPG] Error setting world time: {}", e.getMessage());
            ctx.getSource().sendFailure(Component.literal("§c[RPG] Failed to set world time."));
            return 0;
        }
    }

    /**
     * Define a distância máxima do highlight (Glowing) para os jogadores
     * (não-mestre). O mestre sempre vê de qualquer distância. Só o mestre.
     */
    private static int setHoverDistance(CommandContext<CommandSourceStack> ctx) {
        if (!verifyMasterPermission(ctx)) return 0;

        int distance = IntegerArgumentType.getInteger(ctx, "value");
        SessionManager.setHoverDistance(distance);
        broadcast(ctx, "§6Player hover distance set to: §e" + SessionManager.getHoverDistance() + " blocks");
        RpgNetworking.sendHoverConfigToAll(ctx.getSource().getServer());
        return 1;
    }

    /**
     * Processa a fórmula de dados e devolve a mensagem pronta para o chat
     * (sem prefixo), ou {@code null} se a fórmula for inválida.
     */
    private static String rollDice(CommandContext<CommandSourceStack> ctx, String rawFormula) {
        ServerPlayer player;
        try {
            player = ctx.getSource().getPlayerOrException();
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("§cOnly players can roll dice."));
            return null;
        }

        String formula = rawFormula.replace(" ", "");
        if (formula.isEmpty()) {
            formula = "d20";
        }

        String[] rawTerms = formula.split("\\+");
        if (rawTerms.length == 0) {
            ctx.getSource().sendFailure(Component.literal("§cInvalid dice formula. Example: d20+10, 2d6+d4+3"));
            return null;
        }

        List<String> displayTerms = new ArrayList<>();
        int total = 0;

        for (String rawTerm : rawTerms) {
            if (rawTerm.isEmpty()) {
                continue;
            }

            Matcher diceMatcher = DICE_TERM.matcher(rawTerm);
            Matcher modMatcher = MODIFIER_TERM.matcher(rawTerm);

            if (diceMatcher.matches()) {
                // Termo é um dado: "d20" (contagem implícita 1) ou "2d6"
                int count = diceMatcher.group(1).isEmpty() ? 1 : Integer.parseInt(diceMatcher.group(1));
                int sides = Integer.parseInt(diceMatcher.group(2));

                if (count < 1 || count > 100 || sides < 1 || sides > 1000) {
                    ctx.getSource().sendFailure(Component.literal("§cDice count must be 1-100 and sides 1-1000."));
                    return null;
                }

                int[] rolls = new int[count];
                int termSum = 0;
                for (int i = 0; i < count; i++) {
                    rolls[i] = RANDOM.nextInt(sides) + 1;
                    termSum += rolls[i];
                }
                total += termSum;

                StringBuilder rollsStr = new StringBuilder();
                for (int i = 0; i < rolls.length; i++) {
                    if (i > 0) rollsStr.append(",");
                    rollsStr.append(rolls[i]);
                }

                // Não mostra o "1" quando é um único dado (ex: "d20", não "1d20")
                String countPrefix = count == 1 ? "" : String.valueOf(count);
                displayTerms.add(countPrefix + "d" + sides + " [" + rollsStr + "]");

            } else if (modMatcher.matches()) {
                // Termo é um número fixo somado ao total (ex: "+10")
                int mod = Integer.parseInt(rawTerm);
                total += mod;
                displayTerms.add(String.valueOf(mod));

            } else {
                ctx.getSource().sendFailure(Component.literal(
                    "§cInvalid term: '" + rawTerm + "'. Use format like d20, 2d6, or a number."));
                return null;
            }
        }

        if (displayTerms.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("§cInvalid dice formula. Example: d20+10, 2d6+d4+3"));
            return null;
        }

        String joined = String.join(" §f+ §b", displayTerms);

        return "§6§e" + player.getName().getString()
            + " §frolled a: §b" + joined + " §f= §l§a" + total;
    }

    private static boolean verifyMasterPermission(CommandContext<CommandSourceStack> ctx) {
        if (ctx.getSource().getEntity() instanceof ServerPlayer player) {
            if (SessionManager.isMaster(player)) {
                return true;
            }
        }
        ctx.getSource().sendFailure(Component.literal("§c[RPG] Only the Game Master can perform this action."));
        return false;
    }

    private static void broadcast(CommandContext<CommandSourceStack> ctx, String message) {
        PlayerList playerList = ctx.getSource().getServer().getPlayerList();
        playerList.broadcastSystemMessage(Component.literal(message), false);
    }

    /**
     * Reenvia o menu ASCII para o jogador que executou o comando, atualizando-o
     * automaticamente após ações que mudam o estado (claim master, modo, turno).
     * Assim não é preciso digitar /rpg menu de novo.
     */
    private static void refreshChatMenu(CommandContext<CommandSourceStack> ctx) {
        if (ctx.getSource().getEntity() instanceof ServerPlayer player) {
            player.sendSystemMessage(buildAsciiMenu(player, SessionManager.isMaster(player)));
        }
    }

    // --- CONSTRUÇÃO DO MENU ASCII INTERATIVO ---

    private static Component buildAsciiMenu(ServerPlayer player, boolean isMaster) {
        MutableComponent menu = Component.empty();

        menu.append(Component.literal("§8=========================================\n"));
        menu.append(Component.literal("§6§l           [ TableTop RPG ]\n"));
        menu.append(Component.literal("§7          \"" + SessionManager.getSessionName() + "\"\n"));
        
        if (isMaster) {
            menu.append(Component.literal("§e              [ ROLE: MASTER ]\n"));
            menu.append(Component.literal("§8-----------------------------------------\n"));
            
            // Seção de Modos
            menu.append(Component.literal("§f Modes: "));
            menu.append(createBtn("[FREE]", ChatFormatting.GREEN, "/rpg mode free", "Set mode to Free (everyone moves)"));
            menu.append(Component.literal(" "));
            menu.append(createBtn("[INVESTIGATION]", ChatFormatting.YELLOW, "/rpg mode investigation", "Set mode to Investigation"));
            menu.append(Component.literal(" "));
            menu.append(createBtn("[COMBAT]", ChatFormatting.RED, "/rpg mode combat", "Set mode to Combat"));
            menu.append(Component.literal("\n§f Current Mode: §e" + SessionManager.getMode().getDisplayName() + "\n\n"));

            // Seção de Turnos
            menu.append(Component.literal("§f Active Turn: §a" + SessionManager.getActivePlayerName() + "\n"));
            menu.append(Component.literal("§f Turn Control: "));
            menu.append(createSuggestBtn("[Give Turn]", ChatFormatting.LIGHT_PURPLE, "/rpg turn give ", "Click to choose a player for turn"));
            menu.append(Component.literal(" "));
            menu.append(createBtn("[Revoke Turn]", ChatFormatting.DARK_RED, "/rpg turn revoke", "Revoke active player's turn"));
            menu.append(Component.literal("\n\n"));

            // Ações Gerais
            menu.append(Component.literal("§f Actions: "));
            menu.append(createSuggestBtn("[Roll]", ChatFormatting.AQUA, "/rpg roll ", "Type a dice formula, ex: d20, 2d6+3"));
            menu.append(Component.literal(" "));
            menu.append(createBtn("[Step Down]", ChatFormatting.GRAY, "/rpg master release", "Release Master role"));
            menu.append(Component.literal("\n"));

        } else {
            menu.append(Component.literal("§b              [ ROLE: PLAYER ]\n"));
            menu.append(Component.literal("§8-----------------------------------------\n"));
            
            menu.append(Component.literal("§f Current Mode: §e" + SessionManager.getMode().getDisplayName() + "\n"));
            menu.append(Component.literal("§f Active Turn: §a" + SessionManager.getActivePlayerName() + "\n\n"));

            boolean isMyTurn = SessionManager.isActivePlayer(player);
            if (isMyTurn) {
                menu.append(Component.literal("§a§l >> YOUR TURN IS ACTIVE! <<\n\n"));
            }

            menu.append(Component.literal("§f Actions:\n "));
            if (isMyTurn) {
                menu.append(createBtn("[Finish Turn]", ChatFormatting.GREEN, "/rpg turn finish", "Click to end your turn"));
                menu.append(Component.literal(" "));
            }
            if (!SessionManager.hasMaster()) {
                menu.append(createBtn("[Claim Master]", ChatFormatting.GOLD, "/rpg master claim", "Claim Game Master role"));
                menu.append(Component.literal(" "));
            }
            menu.append(createSuggestBtn("[Roll]", ChatFormatting.AQUA, "/rpg roll ", "Type a dice formula, ex: d20, 2d6+3"));
            menu.append(Component.literal("\n"));
        }

        menu.append(Component.literal("§8========================================="));
        return menu;
    }

    private static Component createBtn(String text, ChatFormatting color, String command, String hover) {
        return Component.literal(text)
            .setStyle(Style.EMPTY
                .withColor(color)
                .withBold(true)
                .withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
    }

    private static Component createSuggestBtn(String text, ChatFormatting color, String command, String hover) {
        return Component.literal(text)
            .setStyle(Style.EMPTY
                .withColor(color)
                .withBold(true)
                .withClickEvent(new ClickEvent.SuggestCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
    }
}