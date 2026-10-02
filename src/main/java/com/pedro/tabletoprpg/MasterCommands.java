package com.pedro.tabletoprpg;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.pedro.tabletoprpg.item.ModItems;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
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
import java.util.Locale;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

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
                // /rpg insert camera -> arma o pedido; o proximo clique numa
                //   criatura a poe (ou tira) do carrossel de cameras.
                .then(Commands.literal("insert")
                    .then(Commands.literal("enemy")
                        .then(Commands.argument("type", StringArgumentType.string())
                            .suggests(MasterCommands::suggestEntityTypes)
                            .then(Commands.argument("cam_perm", BoolArgumentType.bool())
                                .executes(MasterCommands::insertEnemy))))
                    .then(Commands.literal("camera")
                        .executes(MasterCommands::insertCamera)))

                // /rpg mob <nome>  (só o mestre) -> aponta o monstro pelo NOME DE
                // EXIBIÇÃO da ficha e o deixa selecionado, que é o que o /rpg remove
                // enemy, o block_lock e o movimento usam. Existe porque não dá para
                // citar um monstro por nome em comando do vanilla: /tp aceita seletor,
                // e @e[name="..."] só acha quem já foi amarrado a uma ficha (02/10/2026).
                .then(Commands.literal("mob")
                    .then(Commands.argument("name", StringArgumentType.greedyString())
                        .suggests(MasterCommands::suggestBoundMobs)
                        .executes(MasterCommands::selectMobByName)))

                // /rpg remove enemy  (só o mestre) -> remove o mob selecionado
                // (clique direito nele primeiro). 1 mob por execução.
                .then(Commands.literal("remove")
                    .then(Commands.literal("enemy")
                        .executes(MasterCommands::removeEnemy)))

                // /rpg block_lock          -> arma o pedido de TRAVAR; o próximo
                //   clique do mestre num bloco tranca aquele bloco.
                // /rpg block_lock remove   -> o mesmo, para DESTRANCAR.
                .then(Commands.literal("block_lock")
                    .executes(MasterCommands::blockLock)
                    .then(Commands.literal("remove")
                        .executes(MasterCommands::blockUnlock)))

                // /rpg preset create <nome> <formula> <cor>  -> cria e entrega o item
                // /rpg preset use <nome>     -> rola o preset
                // /rpg preset delete <nome>  -> apaga o preset (o item fica no inventário)
                // /rpg preset list           -> lista os presets da jogadora
                //
                // Sem verifyMasterPermission, igual a /rpg roll: o preset é PESSOAL e
                // vive no NBT de quem o criou (decisão do usuário em 01/10/2026), então
                // qualquer jogador precisa poder criar e usar o seu.
                //
                // A formula é StringArgumentType.string() e NÃO greedyString: com
                // greedyString o texto engoliria a cor. O preço é não poder digitar
                // espaço na formula ("1d20 + 5" não entra), o que não atrapalha porque
                // o RollPreset já remove os espaços antes de validar.
                .then(Commands.literal("preset")
                    .then(Commands.literal("create")
                        .then(Commands.argument("name", StringArgumentType.string())
                            .then(Commands.argument("formula", StringArgumentType.string())
                                .then(Commands.argument("color", StringArgumentType.string())
                                    .suggests(MasterCommands::suggestPresetColors)
                                    .executes(MasterCommands::presetCreate)))))
                    .then(Commands.literal("use")
                        .then(Commands.argument("name", StringArgumentType.string())
                            .suggests(MasterCommands::suggestOwnRollPresets)
                            .executes(MasterCommands::presetUse)))
                    .then(Commands.literal("delete")
                        .then(Commands.argument("name", StringArgumentType.string())
                            .suggests(MasterCommands::suggestOwnRollPresets)
                            .executes(MasterCommands::presetDelete)))
                    .then(Commands.literal("give")
                        .then(Commands.argument("name", StringArgumentType.string())
                            .suggests(MasterCommands::suggestOwnRollPresets)
                            .executes(MasterCommands::presetGive)))
                    .then(Commands.literal("giveall")
                        .executes(MasterCommands::presetGiveAll))
                    .then(Commands.literal("edit")
                        .then(Commands.argument("name", StringArgumentType.string())
                            .suggests(MasterCommands::suggestOwnRollPresets)
                            .then(Commands.argument("formula", StringArgumentType.string())
                                .then(Commands.argument("color", StringArgumentType.string())
                                    .suggests(MasterCommands::suggestPresetColors)
                                    .executes(MasterCommands::presetEdit)))))
                    .then(Commands.literal("list")
                        .executes(MasterCommands::presetList))));
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
     * /rpg insert camera: arma o pedido de camera. O comando em si nao mexe em
     * ninguem -- quem aplica e o <b>proximo clique do Mestre numa criatura</b>, para
     * que ele veja qual vai ser afetada antes de confirmar. Se a criatura ja tiver
     * camera, o mesmo clique a remove (decidido em 01/10/2026).
     *
     * <p><b>Para que existe, se ja ha o Camera Tool:</b> o item exige o item na mao.
     * Este comando serve para quem nao quer largar o que esta segurando, e e o unico
     * caminho quando o Mestre nao tem o item no inventario. Os dois caminhos aplicam
     * exatamente a mesma regra -- os dois terminam em
     * {@code CameraToolManager.handleEntityClick}.
     */
    private static int insertCamera(CommandContext<CommandSourceStack> ctx) {
        if (!verifyMasterPermission(ctx)) return 0;
        try {
            ServerPlayer master = ctx.getSource().getPlayerOrException();
            CameraToolManager.arm(master);
            master.sendSystemMessage(
                    Component.translatable("message.tabletop-rpg.camera_arm"));
            return 1;
        } catch (Exception e) {
            TabletopRpg.LOGGER.error("[TabletopRPG] Error arming camera: {}", e.getMessage());
            ctx.getSource().sendFailure(Component.literal("§c[RPG] Failed to arm the camera."));
            return 0;
        }
    }

    /**
     * /rpg mob &lt;nome&gt;: aponta o monstro pelo nome de exibição da ficha e o deixa
     * selecionado.
     *
     * <p><b>Por que preciso deste comando:</b> o vanilla não cita criatura por nome em
     * comando nenhum. {@code /tp} aceita seletor, e o seletor {@code @e[name="..."]} só
     * acha quem tem nome customizado — ou seja, quem o Mestre já amarrou a uma ficha. Sem
 * * este comando, o caminho seria clicar com o botão direito, e isso não escala para
 * cima de uma mesa com dez ameaças (02/10/2026).
     */
    private static int selectMobByName(CommandContext<CommandSourceStack> ctx) {
        if (!verifyMasterPermission(ctx)) return 0;
        try {
            ServerPlayer master = ctx.getSource().getPlayerOrException();
            String wanted = RollPreset.normalizeKey(StringArgumentType.getString(ctx, "name"));
            if (wanted.isEmpty()) {
                ctx.getSource().sendFailure(Component.literal("§c[RPG] Diga o nome de exibição da ameaça."));
                return 0;
            }

            List<Mob> hits = new ArrayList<>();
            for (ServerLevel level : master.level().getServer().getAllLevels()) {
                for (Entity entity : level.getAllEntities()) {
                    if (!(entity instanceof Mob mob)) {
                        continue;
                    }
                    // Só quem tem ficha vale: sem ficha o nome é o do vanilla e não
                    // distingue duas criaturas iguais.
                    if (ThreatSheetBinding.sheetOf(mob).isEmpty() || mob.getCustomName() == null) {
                        continue;
                    }
                    if (RollPreset.normalizeKey(mob.getCustomName().getString()).equals(wanted)) {
                        hits.add(mob);
                    }
                }
            }

            if (hits.isEmpty()) {
                ctx.getSource().sendFailure(Component.literal("§c[RPG] Nenhuma ameaça amarrada com o nome \""
                        + StringArgumentType.getString(ctx, "name") + "\"."));
                return 0;
            }
            if (hits.size() > 1) {
                // Duas criaturas com o mesmo nome dão um @e[name=...] ambíguo. Dizer isso
                // é melhor do que escolher uma e o Mestre achar que escolheu.
                ctx.getSource().sendFailure(Component.literal("§c[RPG] " + hits.size()
                        + " ameaças usam esse nome. Deixe o nome de exibição delas diferente."));
                return 0;
            }

            Mob mob = hits.get(0);
            CombatController.selectMob((ServerLevel) mob.level(), master, mob);
            return 1;
        } catch (Exception e) {
            TabletopRpg.LOGGER.error("[TabletopRPG] /rpg mob falhou: {}", e.getMessage());
            ctx.getSource().sendFailure(Component.literal("§c[RPG] Failed to select the monster."));
            return 0;
        }
    }

    /** Sugere os nomes de exibição das fichas do Mestre: é o que ele digitou ao amarrar. */
    private static CompletableFuture<Suggestions> suggestBoundMobs(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
        try {
            ServerPlayer master = ctx.getSource().getPlayerOrException();
            for (ThreatSheet sheet : ThreatSheetStore.snapshot(master.getUUID())) {
                String name = sheet.identity().displayName();
                if (!name.isEmpty() && name.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                    builder.suggest(name);
                }
            }
        } catch (Exception ignored) {
            // Sugestao nunca recusa o comando: se o jogador nao for um jogador, o
            // autocomplete fica vazio e o comando segue dando o erro dele mesmo.
        }
        return builder.buildFuture();
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

    /**
     * /rpg block_lock: arma o pedido de travar. O comando em si nao tranca
     * nada — quem tranca e o <b>proximo clique do mestre num bloco</b>, para
     * que ele veja qual bloco vai ser afetado antes de confirmar.
     */
    private static int blockLock(CommandContext<CommandSourceStack> ctx) {
        if (!verifyMasterPermission(ctx)) return 0;
        try {
            ServerPlayer master = ctx.getSource().getPlayerOrException();
            BlockLockManager.arm(master, BlockLockManager.PendingAction.LOCK);
            master.sendSystemMessage(Component.translatable("message.tabletop-rpg.block_lock_arm_lock"));
            return 1;
        } catch (Exception e) {
            TabletopRpg.LOGGER.error("[TabletopRPG] Error arming block lock: {}", e.getMessage());
            ctx.getSource().sendFailure(Component.literal("§c[RPG] Failed to arm the block lock."));
            return 0;
        }
    }

    /**
     * /rpg block_lock remove: arma o pedido de destrancar. Mesma troca
     * comando-clique do {@code blockLock}, so que invertida.
     */
    private static int blockUnlock(CommandContext<CommandSourceStack> ctx) {
        if (!verifyMasterPermission(ctx)) return 0;
        try {
            ServerPlayer master = ctx.getSource().getPlayerOrException();
            BlockLockManager.arm(master, BlockLockManager.PendingAction.UNLOCK);
            master.sendSystemMessage(Component.translatable("message.tabletop-rpg.block_lock_arm_unlock"));
            return 1;
        } catch (Exception e) {
            TabletopRpg.LOGGER.error("[TabletopRPG] Error arming block unlock: {}", e.getMessage());
            ctx.getSource().sendFailure(Component.literal("§c[RPG] Failed to arm the block unlock."));
            return 0;
        }
    }

    /**
     * Decide o que {@code /rpg roll <argumento>} quer dizer: o nome de uma
     * <b>perícia</b> da ficha, uma perícia mais uma fórmula, ou só uma fórmula
     * de dados.
     *
     * <p><b>Por que as três coisas no mesmo comando:</b> o jogador já tem
     * "/rpg roll" na mão e a nova regra de perícias ({@code 1d20 + valor +
     * atributo}) é a rolagem que ele mais vai fazer. Se exigisse um comando
     * novo, ele teria que aprender dois comandos.
     *
     * <p><b>Precedencia:</b> (a) o texto inteiro casa com o nome guardado, a
     * rolagem e a de hoje; (b) um <b>prefixo</b> do texto casa com uma perícia e
     * o que sobra depois do "+"/"-" vira fórmula, porque e assim que o jogador
     * escreve "Fight+5+d20" na mao; (c) sem nome no inicio, o texto e fórmula
     * pura, entao {@code d20} continua funcionando.
     */
    private static int rollOrSkill(CommandContext<CommandSourceStack> ctx) {
        String raw = StringArgumentType.getString(ctx, "formula");
        ServerPlayer player = playerOf(ctx);
        if (player == null) {
            return 0;
        }
        SheetData.Pericia pericia = findPericia(player, raw);
        if (pericia != null) {
            return rollSkill(ctx, player, pericia);
        }
        MixedRoll mixed = splitPericiaPrefix(player, raw);
        if (mixed != null) {
            if (mixed.tail().isEmpty()) {
                ctx.getSource().sendFailure(Component.literal(
                        "§cInforme o que somar, por exemplo: " + mixed.pericia().name() + "+5"));
                return 0;
            }
            return rollPericia(ctx, player, mixed.pericia(), mixed.tail());
        }
        return rollFormula(ctx, raw);
    }

    /**
     * Perícia reconhecida no inicio do texto, e a fórmula que vem depois dela.
     *
     * <p><b>Por que o sinal ja vem dentro da cauda:</b> o separador digitado
     * ("+" ou "-") e o sinal do <b>primeiro termo</b> da cauda, nao um sinal
     * aplicado a cauda inteira. Se o comando guardasse o sinal separado e
     * multiplicasse o total, "Pericia 0-d6+2" imprimiria "- d6 + 2" mas faria
     * {@code pericia - (d6 + 2)}. Colocando o sinal na frente do texto que vai
     * para o {@link DiceFormula}, cada termo recebe o sinal que o jogador
     * digitou e o texto e o total não podem divergir.
     */
    private record MixedRoll(SheetData.Pericia pericia, String tail) {
    }

    /**
     * Procura uma perícia no <b>começo</b> do texto, separada do resto por
     * "+" ou "-", para "/rpg roll Fight+5+d20". Devolve {@code null} quando
     * nao ha nome de pericia no inicio, e o chamador trata tudo como formula.
     *
     * <p><b>Por que varrer os separadores de tras para frente:</b> o nome da
     * perícia pode ter espaco ("Pericia 0+5"), entao nao da para quebrar no
     * primeiro espaco; varrer de tras para frente devolve o nome mais longo que
     * casa, que e a leitura que o jogador digitou.
     *
     * <p>Quando o jogador digitou "-" e nao ha nada depois, o separador e
     * devolvido como cauda vazia, para o chamador pedir o que falta em vez de
     * tentar interpretar "-" sozinho.
     */
    private static MixedRoll splitPericiaPrefix(ServerPlayer player, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        SheetData sheet = SessionManager.getSheet(player.getUUID());
        if (sheet == null) {
            return null;
        }
        // A regra de escolher o nome mais longo e de texto puro, entao mora em
        // DiceFormula e tem teste proprio; aqui fica so a leitura da ficha.
        List<String> known = new ArrayList<>();
        for (SheetData.Pericia pericia : sheet.pericias()) {
            known.add(normalize(pericia.name()));
        }
        int at = DiceFormula.findNameSplitPoint(raw, known);
        if (at < 0) {
            return null;
        }
        String wanted = normalize(raw.substring(0, at));
        for (SheetData.Pericia pericia : sheet.pericias()) {
            if (normalize(pericia.name()).equals(wanted)) {
                String rest = raw.substring(at + 1).trim();
                if (rest.isEmpty()) {
                    return new MixedRoll(pericia, "");
                }
                return new MixedRoll(pericia, (raw.charAt(at) == '-' ? "-" : "") + rest);
            }
        }
        return null;
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
    private static SheetData.Pericia findPericia(ServerPlayer player, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String wanted = normalize(raw);
        SheetData sheet = SessionManager.getSheet(player.getUUID());
        if (sheet == null) {
            return null;
        }
        for (SheetData.Pericia pericia : sheet.pericias()) {
            if (normalize(pericia.name()).equals(wanted)) {
                return pericia;
            }
        }
        return null;
    }

    /**
     * Minusculas, sem acento e sem espacos extras, so para comparar nomes.
     *
     * <p>Delega para {@link DiceFormula#normalizeName} porque a divisao de
     * "Pericia 0-2" precisa do mesmo criterio, e dois criterios em dois
     * arquivos ja produziram nome que casava num lado e nao no outro.
     */
    private static String normalize(String text) {
        return DiceFormula.normalizeName(text);
    }

    /**
     * Rola a pericia: {@code 1d20 + valor + atributo}, e opcionalmente mais uma
     * fórmula escrita pelo jogador depois do "+"/"-".
     *
     * <p><b>Fórmula (pedido do usuario):</b> um d20, o valor da pericia (0-30) e
     * o atributo que ela soma. Ex.: "Melee 2 + FOR 3" = 1d20 + 5. O resultado
     * e separado em "1d20 (12) + 2 + 3 = 17" para o jogador ver de onde saiu.
     *
     * <p>A aritmética e feita em {@code long} e convertida no fim: o atributo
     * tem teto 30 e <b>piso -30</b> (decisao do usuario, 27/09/2026), e o
     * {@code long} evita que o conjunto estoure.
     *
     * <p>Visibilidade igual a {@code /rpg roll} normal: o mestre ve so para si,
     * o jogador ve para todos.
     *
     * <p><b>A cauda e lida antes de qualquer rolagem:</b> parsear depois do d20
     * gastava o RNG e so entao devolvia erro, entao o jogador recebia falha sem
     * rolagem nenhuma. A leitura da ficha e do atributo nao sorteia nada e pode
     * ficar onde esta.
     */
    private static int rollPericia(CommandContext<CommandSourceStack> ctx, ServerPlayer player,
                                   SheetData.Pericia pericia, String tail) {
        DiceFormula.Outcome outcome = null;
        if (!tail.isEmpty()) {
            try {
                outcome = DiceFormula.parse(tail).evaluate(sides -> RANDOM.nextInt(sides) + 1);
            } catch (DiceFormula.SyntaxException e) {
                ctx.getSource().sendFailure(Component.literal("§c" + e.getMessage()));
                return 0;
            }
        }

        SheetData sheet = SessionManager.getSheet(player.getUUID());
        int attrValue = sheet.attributeValue(pericia.attributeId());
        PericiaRoll roll = periciaTotal(player, pericia);
        long total = roll.total();
        int die = roll.die();
        String attrLabel = SheetModelHolder.current().attributeLabel(pericia.attributeId());

        String message = "§6§l" + player.getName().getString() + " §frolled §6" + pericia.name() + "§f: "
                + "§7d20 §f(§e" + die + "§f) + §7" + pericia.value()
                + " + §7" + attrLabel + " " + attrValue;

        if (outcome != null) {
            message += tailText(outcome);
            total += outcome.total();
            if (outcome.critical() && !hasGrid(outcome)) {
            message += " §c§l CRIT";
            }
        }

        message += outcome != null && hasGrid(outcome) ? "\n§6§eTOTAL §f= §l§a" + total : " = §e§l" + total;

        if (SessionManager.isMaster(player)) {
            player.sendSystemMessage(Component.literal(message));
        } else {
            broadcast(ctx, message);
        }
        return 1;
    }

    /**
     * O trecho da cauda: o primeiro termo com o sinal que o jogador digitou no
     * separador, e os termos seguintes com o proprio sinal.
     *
     * <p>O primeiro termo nao passa por {@link #appendRollTerm} porque aquele
     * metodo existe para resolver o primeiro termo <b>sem</b> separador, e aqui
     * o texto ja comeca em "1d20 (X) + valor + atributo". Os demais si usam ele.
     *
     * <p>Os sinais vem prontos do {@link DiceFormula}, porque o separador
     * digitado foi colocado na frente da cauda em
     * {@link #splitPericiaPrefix}. Multiplicar o total por um sinal guardado
     * aqui faria o texto e a conta discordarem em cauda com mais de um termo.
     */
    private static String tailText(DiceFormula.Outcome outcome) {
        StringBuilder joined = new StringBuilder();
        List<DiceFormula.Part> parts = outcome.parts();
        for (int i = 0; i < parts.size(); i++) {
            DiceFormula.Part part = parts.get(i);
            // O corpo inteiro do termo, com as varias linhas do '#' inclusas, vai
            // em UMA unica chamada: ver appendTermBody.
            if (i > 0) {
                appendRollTerm(joined, part.sign(), appendTermBody(part));
            } else {
                joined.append(part.sign() < 0 ? " §f- §b" : " §f+ §b").append(appendTermBody(part));
            }
        }
        return joined.toString();
    }

    /**
     * O d20 e o total da pericia, devolvidos juntos.
     *
     * <p>Devolver os dois separadamente, em vez de so o total, existe para
     * <b>nao</b> ter que recuperar a face por subtracao
     * ({@code total - pericia.value() - attrValue}) no chamador. A conta
     * resolveria, mas amarra dois metodos: mexer na soma quebraria a face em
     * silencio, e a face errada no chat e pior que uma excecao.
     */
    private record PericiaRoll(int die, long total) {
    }

    /**
     * Total da pericia: um d20 + valor da pericia + valor do atributo.
     *
     * <p>O RNG do servidor (exigido pelo .docx: o cliente nao pode prever o
     * resultado). CommandSourceStack#getRandom e MinecraftServer#getRandom
     * nao existem nesta versao; Entity#getRandom devolve o RandomSource do
     * level, que e exatamente o do servidor.
     */
    private static PericiaRoll periciaTotal(ServerPlayer player, SheetData.Pericia pericia) {
        SheetData sheet = SessionManager.getSheet(player.getUUID());
        // 27/09/2026: o bonus vem por id de atributo, e o rotulo vem do modelo.
        // Antes era pericia.attribute().field() e pericia.attribute().shortName(),
        // dois metodos do enum SheetData.Attribute, que nao existe mais.
        int die = 1 + player.getRandom().nextInt(20);
        int attrValue = sheet.attributeValue(pericia.attributeId());
        return new PericiaRoll(die, (long) die + pericia.value() + attrValue);
    }

    /** Perícia pura, sem fórmula depois: o caminho que existia antes. */
    private static int rollSkill(CommandContext<CommandSourceStack> ctx, ServerPlayer player,
                                 SheetData.Pericia pericia) {
        return rollPericia(ctx, player, pericia, "");
    }

    /**
     * {@code /rpg roll} sem argumento: lista as <b>perícias</b> com o quanto cada
     * uma soma, para o jogador lembrar o nome exato e o total.
     *
     * <p><b>Mudança de comportamento:</b> antes este comando rolava {@code d20}
     * fixo. Virou lista porque é o que o jogador precisa descobrir; quem quiser
     * o d20 puro escreve {@code /rpg roll d20}.
     *
     * <p><b>Rola perícia, não skill</b> (decisão do usuário em 25/09/2026): a
     * lista de skills é livre e não tem valor nem atributo, então não tem o que
     * somar. A rolagem usa a lista fixa de perícias.
     */
    private static int listSkills(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = playerOf(ctx);
        if (player == null) {
            return 0;
        }
        SheetData sheet = SessionManager.getSheet(player.getUUID());
        if (sheet == null || sheet.pericias().isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("§cNo skills on this sheet."));
            return 0;
        }

        ctx.getSource().sendSystemMessage(Component.literal(
                "§6Skills §7(§f/rpg roll <name>§7): " + sheet.pericias().size()));
        for (SheetData.Pericia pericia : sheet.pericias()) {
            int attr = sheet.attributeValue(pericia.attributeId());
            ctx.getSource().sendSystemMessage(Component.literal(
                    "§7- §f" + pericia.name() + " §8| §e" + pericia.value()
                            + " §7+ §e" + attr + " §8(" + SheetModelHolder.current().attributeLabel(pericia.attributeId()) + ")"
                            + " §8= §e" + (pericia.value() + attr)));
        }
        return sheet.pericias().size();
    }

    /** Autocompletar os nomes de <b>perícia</b> da ficha, junto das fórmulas. */
    private static CompletableFuture<Suggestions> suggestSkills(CommandContext<CommandSourceStack> ctx,
                                                                SuggestionsBuilder builder) {
        String remaining = builder.getRemainingLowerCase();
        try {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            SheetData sheet = SessionManager.getSheet(player.getUUID());
            if (sheet != null) {
                for (SheetData.Pericia pericia : sheet.pericias()) {
                    if (normalize(pericia.name()).startsWith(remaining)) {
                        builder.suggest(pericia.name());
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

    // --- Preset de Rolagem (01/10/2026) ---

    /**
     * Rola uma fórmula com a MESMA regra de visibilidade de {@code /rpg roll}: Mestre
     * vê só o resultado, jogador envia para todos.
     *
     * <p><b>Por que existe:</b> preset (por comando e por item) e rolagem manual
     * precisam cair nas mesmas regras, ou o mesmo dado fica secreto para um e público
     * para o outro. O texto também é o mesmo, para ninguém notar a diferença.
     *
     * @return {@code 1} se rolou, {@code 0} se a fórmula foi recusada
     */
    private static int rollAndPublish(CommandContext<CommandSourceStack> ctx, ServerPlayer player,
                                      String rawFormula) {
        String message = rollDice(ctx, rawFormula);
        if (message == null) {
            return 0;
        }
        if (SessionManager.isMaster(player)) {
            player.sendSystemMessage(Component.literal(message));
        } else {
            broadcast(ctx, message);
        }
        return 1;
    }

    /**
     * O jogador que está rodando o comando, ou {@code null} se quem chamou foi o console.
     *
     * <p>Preset é ação de jogador: o console não tem inventário para receber o item e
     * não tem preset próprio. Devolve {@code null} em vez de lançar, para o chamador
     * poder recusar com mensagem em vez de estourar a exceção para o jogador.
     */
    private static ServerPlayer presetPlayer(CommandContext<CommandSourceStack> ctx) {
        try {
            return ctx.getSource().getPlayerOrException();
        } catch (Exception e) {
            return null;
        }
    }

    /** /rpg preset create <nome> <formula> <cor> */
    private static int presetCreate(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = presetPlayer(ctx);
        if (player == null) {
            ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.preset_players_only"));
            return 0;
        }

        RollPreset preset;
        try {
            preset = RollPreset.create(
                    StringArgumentType.getString(ctx, "name"),
                    StringArgumentType.getString(ctx, "formula"),
                    StringArgumentType.getString(ctx, "color"));
        } catch (RollPreset.PresetException e) {
            // A mensagem do DiceFormula chega aqui crua e é a mesma que a jogadora
            // veria num /rpg roll inválido. É ela que diz onde a fórmula está errada.
            ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.preset_create_failed",
                    e.getMessage()));
            return 0;
        }

        UUID uuid = player.getUUID();
        RollPreset existing = RollPresetStore.find(uuid, preset.key()).orElse(null);
        if (existing != null) {
            // Decisao do usuario em 01/10/2026: create nao sobrescreve. Recusar e
            // dizer o que fazer e o que segura a surpresa de digitar "create ataque
            // 2d10 blue" para MUDAR o preset e receber o item do preset antigo como
            // se tivesse dado certo -- o erro so apareceria na hora de rolar.
            ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.preset_already_exists",
                    existing.name()));
            return 0;
        }
        if (RollPresetStore.count(uuid) >= RollPresetStore.MAX_PRESETS) {
            ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.preset_limit",
                    RollPresetStore.MAX_PRESETS));
            return 0;
        }

        RollPresetStore.put(uuid, preset);
        ModItems.giveRollPreset(player, preset);

        ctx.getSource().sendSuccess(() -> Component.translatable("message.tabletoprpg.preset_created",
                preset.name(), preset.formula(), preset.color().displayName()), true);
        return 1;
    }

    /**
     * /rpg preset edit <nome> <formula> <cor>
     *
     * <p><b>Por que existe:</b> com {@code create} recusando nome repetido, a unica
     * forma de mudar um preset seria apagar e recriar, o que entrega um item novo e
     * deixa o antigo orfao na mochila. Aqui a mudanca acontece no preset e o item que
     * a jogadora ja tem e atualizado no lugar (ver
     * {@link ModItems#refreshRollPresetItems}).
     */
    private static int presetEdit(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = presetPlayer(ctx);
        if (player == null) {
            ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.preset_players_only"));
            return 0;
        }

        String name = StringArgumentType.getString(ctx, "name");
        RollPreset existing = RollPresetStore.find(player.getUUID(), name).orElse(null);
        if (existing == null) {
            ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.preset_not_found", name));
            return 0;
        }

        RollPreset edited;
        try {
            // O nome vem do preset ja salvo, nao do argumento: o argumento e a chave de
            // busca e pode estar escrito de outra forma ("ATAQUE" acha "Ataque").
            // Trocar o nome no proprio create/edit faria o preset sumir de baixo do
            // item que ja existe no inventario.
            edited = RollPreset.create(existing.name(),
                    StringArgumentType.getString(ctx, "formula"),
                    StringArgumentType.getString(ctx, "color"));
        } catch (RollPreset.PresetException e) {
            ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.preset_create_failed",
                    e.getMessage()));
            return 0;
        }

        RollPresetStore.put(player.getUUID(), edited);
        ModItems.refreshRollPresetItems(player, edited);

        ctx.getSource().sendSuccess(() -> Component.translatable("message.tabletoprpg.preset_edited",
                edited.name(), edited.formula(), edited.color().displayName()), true);
        return 1;
    }

    /**
     * /rpg preset give &lt;nome&gt; -> reentrega o item de um preset.
     *
     * <p><b>Por que isso basta para "perdi o item":</b> o preset vive no NBT da
     * jogadora, entao item perdido nao e dado perdido. O item e so o atalho para rolar
     * com o botao direito.
     */
    private static int presetGive(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = presetPlayer(ctx);
        if (player == null) {
            ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.preset_players_only"));
            return 0;
        }

        String name = StringArgumentType.getString(ctx, "name");
        RollPreset preset = RollPresetStore.find(player.getUUID(), name).orElse(null);
        if (preset == null) {
            ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.preset_not_found", name));
            return 0;
        }

        if (ModItems.giveRollPreset(player, preset) == null) {
            // Sem espaco no inventario: o preset continua salvo, so nao foi entregue.
            ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.preset_no_room"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.translatable("message.tabletoprpg.preset_given",
                preset.name()), true);
        return 1;
    }

    /**
     * /rpg preset giveall -> reentrega o item de todos os presets.
     *
     * <p><b>Quando isso é usado:</b> depois de uma morte com drop de inventario, ou
     * quando a jogadora montou os itens numa hora e quer o conjunto de volta de uma vez.
     */
    private static int presetGiveAll(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = presetPlayer(ctx);
        if (player == null) {
            ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.preset_players_only"));
            return 0;
        }

        List<RollPreset> presets = RollPresetStore.list(player.getUUID());
        if (presets.isEmpty()) {
            ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.preset_list_empty"));
            return 0;
        }

        int given = 0;
        int skipped = 0;
        for (RollPreset preset : presets) {
            if (ModItems.giveRollPreset(player, preset) == null) {
                skipped++;
            } else {
                given++;
            }
        }
        // Cpias finais: given/skipped sao mutados no laco e nao servem "effectively
        // final", que e o que o Supplier do sendSuccess exige.
        int givenCount = given;
        int skippedCount = skipped;
        ctx.getSource().sendSuccess(() -> Component.translatable("message.tabletoprpg.preset_given_all",
                givenCount, skippedCount), true);
        return given;
    }

    /** /rpg preset use <nome> */
    private static int presetUse(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = presetPlayer(ctx);
        if (player == null) {
            ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.preset_players_only"));
            return 0;
        }

        String name = StringArgumentType.getString(ctx, "name");
        RollPreset preset = RollPresetStore.find(player.getUUID(), name).orElse(null);
        if (preset == null) {
            ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.preset_not_found", name));
            return 0;
        }
        return rollAndPublish(ctx, player, preset.formula());
    }

    /**
     * /rpg preset delete <nome>
     *
     * <p><b>O item NÃO é retirado do inventário</b> (decisão do usuário em 01/10/2026):
     * apagar o preset é um ato administrativo, e o item sai com o inventário quando a
     * jogadora quiser. Usar o item depois disso avisa que o preset não existe mais.
     */
    private static int presetDelete(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = presetPlayer(ctx);
        if (player == null) {
            ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.preset_players_only"));
            return 0;
        }

        String name = StringArgumentType.getString(ctx, "name");
        RollPreset removed = RollPresetStore.remove(player.getUUID(), name).orElse(null);
        if (removed == null) {
            ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.preset_not_found", name));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.translatable("message.tabletoprpg.preset_deleted",
                removed.name()), true);
        return 1;
    }

    /** /rpg preset list */
    private static int presetList(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = presetPlayer(ctx);
        if (player == null) {
            ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.preset_players_only"));
            return 0;
        }

        List<RollPreset> presets = RollPresetStore.list(player.getUUID());
        if (presets.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.translatable("message.tabletoprpg.preset_list_empty"), false);
            return 0;
        }
        for (RollPreset preset : presets) {
            // Copia final: a variavel do for nao e "effectively final" e o
            // sendSuccess recebe um Supplier que a captura.
            RollPreset entry = preset;
            ctx.getSource().sendSuccess(() -> Component.translatable("message.tabletoprpg.preset_list_entry",
                    entry.name(), entry.formula(), entry.color().displayName()), false);
        }
        return presets.size();
    }

    /**
     * Completa os nomes de preset da propria jogadora (use/delete/give/edit).
     *
     * <p><b>Por que sugerir {@link RollPreset#commandName()} e nao o nome:</b> o nome com
     * espaco nao entra no comando. {@code StringArgumentType.string()} do Brigadier 1.3.10
     * le uma palavra sem aspas ou uma frase entre aspas, e a sugestao que oferece "Dano
     * Espada" entrega um comando que falha no parse. A chave de busca ja ignora o
     * espaco, entao "Dano_Espada" acha o mesmo preset.
     */
    private static CompletableFuture<Suggestions> suggestOwnRollPresets(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        ServerPlayer player = presetPlayer(ctx);
        if (player == null) {
            return builder.buildFuture();
        }
        String remaining = builder.getRemainingLowerCase();
        for (RollPreset preset : RollPresetStore.list(player.getUUID())) {
            String commandName = preset.commandName();
            if (commandName.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                builder.suggest(commandName);
            }
        }
        return builder.buildFuture();
    }

    /** Completa as 17 cores de Bundle. */
    private static CompletableFuture<Suggestions> suggestPresetColors(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        String remaining = builder.getRemainingLowerCase();
        for (String id : RollPresetColor.ids()) {
            if (id.startsWith(remaining)) {
                builder.suggest(id);
            }
        }
        return builder.buildFuture();
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
     * Define a distância máxima do highlight (Glowing) para <b>todos</b> os
     * jogadores, inclusive o mestre — o valor vai por
     * {@link RpgNetworking#sendHoverConfigToPlayer} sem tratamento especial para o
     * mestre. Antes este texto afirmava que o mestre via de qualquer distância; não
     * existe 1024 em nenhum lugar do código. Decisão do usuário em 27/09/2026: o
     * comportamento foi mantido e só o texto corrigido. Só o mestre usa o comando.
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
     *
     * <p>O texto cru vai inteiro para {@link DiceFormula}, que valida a
     * gramatica e rola os dados; aqui fica so a traducao do resultado para cor
     * do chat. A mensagem da {@link DiceFormula.SyntaxException} ja sai no
     * formato do chat, por isso nao recebe prefixo de "formula invalida".
     */
    private static String rollDice(CommandContext<CommandSourceStack> ctx, String rawFormula) {
        ServerPlayer player;
        try {
            player = ctx.getSource().getPlayerOrException();
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("§cOnly players can roll dice."));
            return null;
        }

        try {
            return rollMessageFor(player, rawFormula);
        } catch (DiceFormula.SyntaxException e) {
            ctx.getSource().sendFailure(Component.literal("§c" + e.getMessage()));
            return null;
        }
    }

    /**
     * Rola e entrega o resultado a {@code player}, sem depender de comando.
     *
     * <p><b>Por que existe (01/10/2026):</b> o clique com o botao direito no item de
     * preset nao tem {@code CommandContext} (nao vem de comando nenhum), mas tem de
     * seguir exatamente a mesma regra de visibilidade do {@code /rpg roll}. Fica aqui,
     * ao lado do codigo de rolagem ja provado, em vez de duplicar a montagem da
     * mensagem em outro arquivo.
     *
     * @return {@code 1} se publicou o resultado, {@code 0} se a formula foi recusada
     *         (a recusa ja foi enviada para quem rollou)
     */
    public static int rollForPlayer(ServerPlayer player, String rawFormula) {
        String message;
        try {
            message = rollMessageFor(player, rawFormula);
        } catch (DiceFormula.SyntaxException e) {
            player.displayClientMessage(Component.literal("§c" + e.getMessage()), false);
            return 0;
        }
        if (SessionManager.isMaster(player)) {
            player.sendSystemMessage(Component.literal(message));
        } else {
            MinecraftServer server = player.level().getServer();
            if (server != null) {
                server.getPlayerList().broadcastSystemMessage(Component.literal(message), false);
            } else {
                player.sendSystemMessage(Component.literal(message));
            }
        }
        return 1;
    }

    /**
     * Monta a mensagem de resultado de uma rolagem.
     *
     * @throws DiceFormula.SyntaxException se a fórmula não for reconhecida; a mensagem
     *         da exceção já é uma frase pronta para o chat
     */
    private static String rollMessageFor(ServerPlayer player, String rawFormula)
            throws DiceFormula.SyntaxException {
        String formula = rawFormula.replace(" ", "");
        if (formula.isEmpty()) {
            formula = "d20";
        }

        // Nome de atributo/pericia vira numero AQUI, e nao quando o preset foi
        // criado (01/10/2026). E o unico lugar por onde passa toda rolagem: comando,
        // item de preset e botao da tela. Se a resolucao ficasse no `presetUse`, o
        // `/rpg roll 1d6+Strength` e o clique no item dariam numeros diferentes.
        //
        // A ficha e a da jogadora que esta rolando, e nao o preset: dois jogadores
        // usando o mesmo preset tem resultados diferentes, que e o ponto de um
        // bonus que acompanha a ficha.
        try {
            formula = FormulaResolver.resolve(formula,
                    SessionManager.getSheet(player.getUUID()), SheetModelHolder.current());
        } catch (FormulaResolver.ResolveException e) {
            // A SyntaxException e checked e este metodo ja promete uma; reaproveitar o
            // tipo mantem a assinatura sem inventar uma excecao nova para o chamador.
            throw new DiceFormula.SyntaxException(e.getMessage());
        }

        DiceFormula.Outcome outcome;
        // RANDOM e a unica fonte de aleatoriedade do comando: o cliente nao
        // pode prever a rolagem.
        outcome = DiceFormula.parse(formula).evaluate(sides -> RANDOM.nextInt(sides) + 1);

        // Com '#' o total vai para a linha propria: colado no fim da ultima volta
        // ele se confunde com o subtotal dela (30/09/2026, pedido do jogador).
        String totalPrefix = hasGrid(outcome) ? "\n§6§eTOTAL §f= §l§a" : " §f= §l§a";
        String message = "§6§e" + player.getName().getString()
                + " §frolled a: §b" + joinParts(outcome) + totalPrefix + outcome.total();
        if (outcome.critical() && !hasGrid(outcome)) {
            // O critico e so um marcador: o total ja saiu da somatoria.
            message += " §c§l CRIT";
        }
        return message;
    }

    /**
     * Joga um termo na mensagem de resultado com o separador correto (" §f+ §b"
     * ou " §f- §b"). O {@code text} deve vir sem sinal, porque o separador já
     * carrega o dele; no primeiro termo não existe separador, então o sinal é
     * posto aqui. Sem isso o chat mostraria "d8 [5] - -1".
     */
    private static void appendRollTerm(StringBuilder joined, int sign, String text) {
        if (joined.isEmpty()) {
            joined.append(sign < 0 ? "-" : "").append(text);
        } else {
            joined.append(sign < 0 ? " §f- §b" : " §f+ §b").append(text);
        }
    }

    /**
     * Monta a frase dos termos a partir das pecas do resultado, reaproveitando
     * {@link #appendRollTerm} para os separadores de cor.
     */
    private static String joinParts(DiceFormula.Outcome outcome) {
        StringBuilder joined = new StringBuilder();
        for (DiceFormula.Part part : outcome.parts()) {
            // Um termo, uma chamada: o corpo de um '#' tem varias linhas, e
            // appendRollTerm decide o separador olhando se o StringBuilder esta
            // vazio ANTES de escrever. Passar as linhas do '#' em chamadas
            // separadas faria a segunda linha receber um " + " no meio do
            // bloco, e o cabecalho do '#' nunca e a primeira coisa escrita.
            appendRollTerm(joined, part.sign(), appendTermBody(part));
        }
        return joined.toString();
    }

    /**
     * O corpo de um termo ja colorido, sem sinal: {@code "4d6dl1 [2,3,<s>1</s>]"},
     * {@code "6#4d6dl1:" + uma linha por volta} ou o numero puro.
     *
     * <p><b>A cor mora aqui e nao no {@link DiceFormula}</b> porque e coisa de
     * Minecraft. O que decide o que riscar e o {@code discarded} de cada face,
     * e nao um texto ja montado: com a String unica do motor nao dava para
     * saber onde cada face estava dentro dela.
     */
    private static String appendTermBody(DiceFormula.Part part) {
        if (part.faces().isEmpty() && part.rounds().isEmpty()) {
            return Long.toString(part.flat());
        }
        if (!part.rounds().isEmpty()) {
            return repeatBody(part);
        }
        return part.label() + ' ' + facesBody(part.faces());
    }

    /**
     * Termo com {@code N#}: o cabecalho numa linha e uma linha por volta.
     *
     * <p>O {@code \n} funciona em chat do Minecraft, entao o bloco sai inteiro
     * como uma String so: e o que deixa {@link #appendRollTerm} resolver o
     * separador uma vez, no cabecalho.
     *
     * <p><b>Por que cada linha recomeca com {@code §b}:</b> a quebra de linha
     * NAO limpa a formatacao no chat do Minecraft. O subtotal da volta anterior
     * termina em {@code §a}, e sem recolocar a cor o rotulo e as faces da volta
     * seguinte saem verdes em vez de azuis, ate o proximo {@code §r§b} passar
     * por cima. O {@code §b} logo depois do {@code \n} fixa a cor do termo,
     * que e o que o {@link #facesBody} pressupoe.
     */
    private static String repeatBody(DiceFormula.Part part) {
        StringBuilder sb = new StringBuilder();
        sb.append(part.repeat()).append('#').append(part.label()).append(':');
        List<DiceFormula.Round> rounds = part.rounds();
        int shown = Math.min(rounds.size(), DiceFormula.DISPLAY_ROUNDS);
        for (int i = 0; i < shown; i++) {
            DiceFormula.Round round = rounds.get(i);
            // Volta com formula dentro (4#2d20+2d6): os termos vao um por um,
            // com o mesmo separador de cor da rolagem sem grade, para cada tipo
            // de dado ficar no seu proprio colchete. Volta sem termo interno nao
            // tem o que separar, e continua no rotulo + faces achatadas.
            StringBuilder body = new StringBuilder();
            if (round.parts().isEmpty()) {
                body.append(round.label()).append(' ').append(facesBody(round.faces()));
            } else {
                for (DiceFormula.Part inner : round.parts()) {
                    appendRollTerm(body, inner.sign(), appendTermBody(inner));
                }
            }
            sb.append("\n§b ").append(body)
                    .append(" §f= §a").append(round.critical() ? "§l" : "").append(round.subtotal());
            if (round.critical()) {
                // A volta que teve dado critico ganha o subtotal em negrito e o
                // marcador CRIT. O §r§b fecha a volta porque o §r desliga o
                // estilo, e sem isso o negrito vaza para o rotulo da linha
                // seguinte. O CRIT do fim da mensagem NAO vale na grade, porque
                // quem marca o critico aqui e a propria linha.
                sb.append(" §c§l CRIT").append("§r§b");
            }
        }
        if (rounds.size() > shown) {
            sb.append("\n§7 ...+").append(rounds.size() - shown).append(" more");
        }
        return sb.toString();
    }

/**
     * A rolagem tem grade quando alguma peca foi repetida.
     *
     * <p>Nao procura o {@code #} no texto: quem carrega o {@code #} e a peca, e o
     * texto da cauda pode ter o caractere sem ser grade.
     *
     * @param outcome resultado da rolagem, que pode ser nulo quando nao ha cauda
     */
    private static boolean hasGrid(DiceFormula.Outcome outcome) {
        for (DiceFormula.Part part : outcome.parts()) {
            if (part.repeat() > 1) {
                return true;
            }
        }
        return false;
    }

    /**
     * As faces entre colchetes, com o descartado em vermelho e riscado e o
     * critico mantido em amarelo.
     *
     * <p><b>Por que {@code §r} antes do {@code §b}:</b> o riscado e um
     * <b>estilo</b>, independente da cor. So trocar a cor para aqua deixaria o
     * dado cortado em aqua <b>riscado ainda</b>, que e o oposto do que o
     * jogador precisa ver. O {@code §r} zera o estilo e devolve a cor de
     * contexto, que aqui dentro do termo ja e aqua. O mesmo vale para o
     * amarelo do critico, que precisa devolver o aqua para o proximo dado da
     * lista nao sair amarelo sem ser critico.
     */
    private static String facesBody(List<DiceFormula.Face> faces) {
        StringBuilder sb = new StringBuilder("[");
        int shown = Math.min(faces.size(), DiceFormula.DISPLAY_LIMIT);
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                sb.append(',');
            }
            DiceFormula.Face face = faces.get(i);
            if (face.discarded()) {
                sb.append("§c§m").append(face.text()).append("§r§b");
            } else if (face.critical()) {
                sb.append("§e§l").append(face.text()).append("§r§b");
            } else {
                sb.append(face.text());
            }
        }
        if (faces.size() > shown) {
            sb.append(",§7...+").append(faces.size() - shown).append(" more");
        }
        return sb.append(']').toString();
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
            //
            // Sem botao de tranca aqui (decisao do usuario em 30/09/2026): o
            // comando `/rpg block_lock` e o item ja cobrem, e o botao seria uma
            // terceira via para a mesma acao.
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