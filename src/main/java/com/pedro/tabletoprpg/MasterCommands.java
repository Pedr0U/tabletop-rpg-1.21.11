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
import net.minecraft.world.item.ItemStack;

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

                // /rpg initiative <start|end|roll|add|remove> -> lista de iniciativa (Fase 1)
                .then(InitiativeCommands.node())

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

                // /rpg mob view <nome>  (so o mestre) -> ABRE A FICHA com aquele NOME DE FICHA.
                // O nome da ficha e unico no store; o nome de exibicao (do mob amarrado)
                // pode se repetir e causava conflito (feedback da jogadora em 08/10/2026).
                // /rpg mob give <nome>  (so o mestre) -> ENTREGA NO INVENTARIO o item da
                // ficha do Mestre com aquele NOME DE FICHA (funciona mesmo sem mob no
                // mundo, a qualquer hora -- feedback da jogadora em 07/10/2026).
                //
                // Em 05/10/2026 o comando era /rpg mob <nome> e abria a ficha. A jogadora
                // pediu em 07/10/2026 que o formato SEM subcomando sumisse: /rpg mob so
                // existe com view ou give, para o mestre não depender da ficha estar aberta
                // para pegar o item dela.
                //
                // A consequência, registrada aqui de propósito: a seleção por NOME
                // deixou de existir. Os consumidores dela — /rpg remove enemy, block_lock
                // e o movimento — passam a depender só do clique direito no mob, que é a
                // única porta de seleção que sobrou. Nenhum deles quebrou: o
                // `removeEnemy` já respondia "Right-click an enemy first".
                //
                // Continua existindo porque não dá para citar um monstro por nome em
                // comando do vanilla: /tp aceita seletor, e @e[name="..."] só acha quem
                // já foi amarrado a uma ficha (02/10/2026).
                .then(Commands.literal("mob")
                    .then(Commands.literal("view")
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                            .suggests(MasterCommands::suggestSheetNames)
                            .executes(MasterCommands::openMobSheetByName)))
                    .then(Commands.literal("give")
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                            .suggests(MasterCommands::suggestSheetNames)
                            .executes(MasterCommands::giveMobSheetByName))))

                // /rpg remove enemy  (só o mestre) -> remove o mob selecionado
                // (clique direito nele primeiro). 1 mob por execução.
                // /rpg remove enemy @e[name="Nome Exato"] -> remove TODOS os
                // mobs do RPG com aquele nome exato (22/10/2026).
                .then(Commands.literal("remove")
                    .then(Commands.literal("enemy")
                        .executes(MasterCommands::removeEnemy)
                        .then(Commands.argument("selector", StringArgumentType.greedyString())
                            .executes(MasterCommands::removeEnemyBySelector))))

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
                            .suggests(MasterCommands::suggestUsableRollPresets)
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
            ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.only_players_menu"));
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
                broadcast(ctx, Component.translatable("message.tabletoprpg.master_claimed_broadcast", player.getName().getString()));
                RpgNetworking.sendToAll(ctx.getSource().getServer());
                BlockLockManager.sendSnapshotTo(player);
                refreshChatMenu(ctx);
                return 1;
            } else {
                ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.master_slots_in_use",
                        SessionManager.getMasterCount(), SessionManager.getMaxMasters(),
                        SessionManager.getMasterName()));
                return 0;
            }
        } catch (Exception e) {
            TabletopRpg.LOGGER.error("[TabletopRPG] Error claiming master: {}", e.getMessage());
            ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.master_claim_failed"));
            return 0;
        }
    }

    private static int releaseMaster(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            if (SessionManager.isMaster(player)) {
                // Com varios mestres, sair um NAO derruba a sessao: so o ultimo mestre que sai reseta o combate (22/10/2026).
                boolean lastMaster = SessionManager.getMasterCount() <= 1;
                SessionManager.releaseMaster(player);
                if (lastMaster) {
                    CombatController.reset(ctx.getSource().getServer());
                }
                broadcast(ctx, Component.translatable("message.tabletoprpg.master_released_broadcast", player.getName().getString()));
                RpgNetworking.sendToAll(ctx.getSource().getServer());
                BlockLockManager.sendSnapshotTo(player);
                RpgNetworking.sendAuraStateToAll(ctx.getSource().getServer());
                // O reset limpou os mobs com cǽmera: atualiza o carrossel.
                RpgNetworking.sendSpectatorTargetsToAll(ctx.getSource().getServer());
                BlockLockManager.sendSnapshotTo(player);
                refreshChatMenu(ctx);
                return 1;
            } else {
                ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.master_not_current"));
                return 0;
            }
        } catch (Exception e) {
            TabletopRpg.LOGGER.error("[TabletopRPG] Error releasing master: {}", e.getMessage());
            ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.master_release_failed"));
            return 0;
        }
    }

    private static int setMode(CommandContext<CommandSourceStack> ctx, SessionManager.GameMode mode) {
        if (!verifyMasterPermission(ctx)) return 0;

        InitiativeCommands.onModeChanged(ctx.getSource().getServer(), mode);
        SessionManager.setMode(mode);
        if (mode == SessionManager.GameMode.FREE) {
            // Fim do encontro: limpa seleção, âncoras e monstros controlados.
            CombatController.reset(ctx.getSource().getServer());
        }
        broadcast(ctx, Component.translatable("message.tabletoprpg.mode_changed", mode.getDisplayName()));
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
            broadcast(ctx, Component.translatable("message.tabletoprpg.turn_granted", target.getName().getString()));
            target.sendSystemMessage(Component.translatable("message.tabletoprpg.turn_your_turn"));
            RpgNetworking.sendToAll(ctx.getSource().getServer());
            RpgNetworking.sendAuraStateToAll(ctx.getSource().getServer());
            refreshChatMenu(ctx);
            return 1;
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.turn_player_not_found"));
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
        broadcast(ctx, Component.translatable("message.tabletoprpg.turn_revoked", prevPlayer));
        RpgNetworking.sendToAll(ctx.getSource().getServer());
        RpgNetworking.sendAuraStateToAll(ctx.getSource().getServer());
        refreshChatMenu(ctx);
        return 1;
    }

    private static int finishTurn(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer player = ctx.getSource().getPlayerOrException();

            if (!SessionManager.isActivePlayer(player) && !SessionManager.isMaster(player)) {
                ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.turn_not_yours_finish"));
                return 0;
            }

            UUID prevUuid = SessionManager.getActivePlayerUuid();
            SessionManager.clearActivePlayer();
            if (prevUuid != null) {
                CombatController.clearPlayerAnchor(prevUuid);
            }
            broadcast(ctx, Component.translatable("message.tabletoprpg.turn_finished", player.getName().getString()));
            RpgNetworking.sendToAll(ctx.getSource().getServer());
            RpgNetworking.sendAuraStateToAll(ctx.getSource().getServer());
            refreshChatMenu(ctx);
            return 1;
        } catch (Exception e) {
            TabletopRpg.LOGGER.error("[TabletopRPG] Error finishing turn: {}", e.getMessage());
            ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.turn_finish_failed"));
            return 0;
        }
    }

    private static int setSessionName(CommandContext<CommandSourceStack> ctx) {
        if (!verifyMasterPermission(ctx)) return 0;

        String name = StringArgumentType.getString(ctx, "name").trim();
        if (name.isEmpty()) {
            ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.session_name_empty"));
            return 0;
        }
        if (name.length() > 64) {
            name = name.substring(0, 64);
        }

        SessionManager.setSessionName(name);
        broadcast(ctx, Component.translatable("message.tabletoprpg.session_name_set", name));
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
                ctx.getSource().sendFailure(Component.translatable(
                        "message.tabletoprpg.enemy_unknown_type", type));
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
                ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.enemy_create_failed"));
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
                // Invulnerável só quando a opção "Mobs podem levar dano" está
                // DESLIGADA: com a opção LIGADA, o mob invocado é um alvo de
                // verdade (o mestre e os jogadores precisam poder machucá-lo).
                mob.setInvulnerable(!SessionManager.canMobsBeDamaged());
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
            broadcast(ctx, Component.translatable("message.tabletoprpg.enemy_summoned_broadcast", type));
            master.sendSystemMessage(Component.translatable("message.tabletoprpg.enemy_summoned"));

            return 1;
        } catch (Exception e) {
            TabletopRpg.LOGGER.error("[TabletopRPG] Error summoning enemy: {}", e.getMessage());
            ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.enemy_summon_failed"));
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
            ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.camera_arm_failed"));
            return 0;
        }
    }

    /**
     * /rpg mob view &lt;nome&gt;: ABRE a ficha de ameaca com aquele NOME DE FICHA.
     *
     * <p><b>Mudanca de 08/10/2026, feedback da jogadora:</b> antes o comando resolvia
     * pelo NOME DE EXIBICAO do mob amarrado, e nomes de exibicao podem se repetir --
     * dois mobs com o mesmo nome davam o aviso de ambiguidade. O nome da FICHA e unico
     * no store (o save recusa duplicado), entao o view passou a resolver por ele,
     * exatamente como o give. A ficha nem precisa estar amarrada a um mob para ser
     * aberta; se estiver armada mas o mob nao estiver no mundo, ela abre do mesmo jeito
     * (a amarra e so o caminho de abrir pelo mundo, nao um requisito).
     *
     * <p><b>Mudanca de 05/10/2026, pedido da jogadora:</b> este comando antes
     * SELECIONAVA o mob (congelava com {@code setNoAi} e armava o teletransporte do
     * clique direito). Agora abre a ficha. Selecionar continua existindo, mas so pelo
     * clique direito no mob, em {@code CombatController.toggleSelection}: a selecao por
     * nome saiu de proposito, nao por esquecimento.
     *
     * <p><b>Por que preciso deste comando:</b> o vanilla nao cita criatura por nome em
     * comando nenhum. {@code /tp} aceita seletor, e o seletor {@code @e[name="..."]} so
     * acha quem tem nome customizado -- ou seja, quem o Mestre ja amarrou a uma ficha. Sem
     * este comando, o caminho seria clicar com o botao direito, e isso nao escala para
     * cima de uma mesa com dez ameacas (02/10/2026).
     */
    private static int openMobSheetByName(CommandContext<CommandSourceStack> ctx) {
        if (!verifyMasterPermission(ctx)) return 0;
        try {
            ServerPlayer master = ctx.getSource().getPlayerOrException();
            String rawName = StringArgumentType.getString(ctx, "name");
            String wanted = RollPreset.normalizeKey(rawName);
            if (wanted.isEmpty()) {
                ctx.getSource().sendFailure(Component.translatable(
                        "message.tabletoprpg.threat_sheet_name_required"));
                return 0;
            }
            ThreatSheet sheet = ThreatSheetStore.find(master.getUUID(), wanted);
            if (sheet == null) {
                ctx.getSource().sendFailure(Component.translatable(
                        "message.tabletoprpg.threat_sheet_not_found_by_name", rawName));
                return 0;
            }
            RpgNetworking.sendThreatSheetOpen(master, sheet);
            TabletopRpg.LOGGER.info("[TabletopRPG] Ficha '{}' aberta por /rpg mob view.",
                    sheet.identity().name());
            return 1;
        } catch (Exception e) {
            TabletopRpg.LOGGER.error("[TabletopRPG] /rpg mob view falhou: {}", e.getMessage());
            ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.threat_sheet_open_failed"));
            return 0;
        }
    }

    /**
     * /rpg mob give <nome> (07/10/2026): entrega no inventario o ITEM da ficha do
     * Mestre com aquele NOME DE FICHA (ameaca), esteja ela vinculada a um mob ou nao.
     *
     * <p><b>Por que por nome de ficha e nao por nome de mob:</b> feedback da jogadora
     * em 07/10/2026 -- o give tem que funcionar a qualquer hora, e nao so quando a
     * ficha esta amarrada a um mob do mundo. E a ficha nova, recem-criada (sem mob),
     * a que o Mestre mais quer transformar em item para o inventario.
     */
    private static int giveMobSheetByName(CommandContext<CommandSourceStack> ctx) {
        if (!verifyMasterPermission(ctx)) return 0;
        try {
            ServerPlayer master = ctx.getSource().getPlayerOrException();
            String rawName = StringArgumentType.getString(ctx, "name");
            String wanted = RollPreset.normalizeKey(rawName);
            if (wanted.isEmpty()) {
                ctx.getSource().sendFailure(Component.translatable(
                        "message.tabletoprpg.threat_sheet_name_required"));
                return 0;
            }
            ThreatSheet sheet = ThreatSheetStore.find(master.getUUID(), wanted);
            if (sheet == null) {
                ctx.getSource().sendFailure(Component.translatable(
                        "message.tabletoprpg.threat_sheet_not_found_by_name", rawName));
                return 0;
            }
            ItemStack stack = ModItems.giveThreatSheet(master, sheet);
            if (stack == null) {
                ctx.getSource().sendFailure(
                        Component.translatable("message.tabletoprpg.threat_sheet_inventory_full"));
                return 0;
            }
            TabletopRpg.LOGGER.info("[TabletopRPG] Item da ficha '{}' entregue por /rpg mob give.",
                    sheet.identity().name());
            return 1;
        } catch (Exception e) {
            TabletopRpg.LOGGER.error("[TabletopRPG] /rpg mob give falhou: {}", e.getMessage());
            ctx.getSource().sendFailure(
                    Component.translatable("message.tabletoprpg.threat_sheet_give_failed"));
            return 0;
        }
    }

    /**
     * Sugere os nomes de FICHA do Mestre (nao de mob): e o que o give usa para achar
     * a ficha e entregar o item, esteja ela vinculada a um mob ou nao.
     */
    private static CompletableFuture<Suggestions> suggestSheetNames(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
        try {
            ServerPlayer master = ctx.getSource().getPlayerOrException();
            for (ThreatSheet sheet : ThreatSheetStore.snapshot(master.getUUID())) {
                String name = sheet.identity().name();
                if (!name.isEmpty() && name.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                    builder.suggest(name);
                }
            }
        } catch (Exception ignored) {
            // Sugestao nunca recusa o comando (mesma regra do view, que tambem usa nomes de ficha).
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
                ctx.getSource().sendFailure(Component.translatable(
                        "message.tabletoprpg.enemy_none_selected"));
                return 0;
            }

            String name = selected.getName().getString();
            CombatController.removeSelectedMonster(serverLevel);
            broadcast(ctx, Component.translatable("message.tabletoprpg.enemy_removed_broadcast", name));
            RpgNetworking.sendAuraStateToAll(ctx.getSource().getServer());
            RpgNetworking.sendSpectatorTargetsToAll(ctx.getSource().getServer());
            return 1;
        } catch (Exception e) {
            TabletopRpg.LOGGER.error("[TabletopRPG] Error removing enemy: {}", e.getMessage());
            ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.enemy_remove_failed"));
            return 0;
        }
    }

    /**
     * /rpg remove enemy @e[name="Nome Exato"]: remove TODOS os mobs do RPG com
     * aquele nome exato (decisão do usuário em 22/10/2026).
     *
     * <p><b>Por que o seletor do vanilla não é usado:</b> o {@code @e} do
     * vanilla só funciona para quem tem permissão de op, e o Mestre do RPG
     * não precisa ser op. Então o nome é lido do texto do seletor e a busca é
     * feita aqui, com DOIS filtros: nome exato e mob do RPG
     * ({@link CombatController#isRpgMob}). Uma criatura natural do mundo que
     * por acaso tenha o mesmo nome nunca é removida.
     *
     * <p>Se nenhum mob casar, o chat avisa que nada foi encontrado — o
     * comando não fica em silêncio depois de não ter feito nada.
     */
    private static int removeEnemyBySelector(CommandContext<CommandSourceStack> ctx) {
        if (!verifyMasterPermission(ctx)) return 0;

        try {
            String selector = StringArgumentType.getString(ctx, "selector");
            String name = selectorName(selector);
            if (name == null) {
                ctx.getSource().sendFailure(Component.translatable(
                        "message.tabletoprpg.enemy_selector_usage"));
                return 0;
            }

            List<Mob> targets = new ArrayList<>();
            for (ServerLevel level : ctx.getSource().getServer().getAllLevels()) {
                for (Entity entity : level.getAllEntities()) {
                    if (!(entity instanceof Mob mob)) {
                        continue;
                    }
                    if (!name.equals(mob.getName().getString())) {
                        continue;
                    }
                    if (!CombatController.isRpgMob(mob)) {
                        continue;
                    }
                    targets.add(mob);
                }
            }

            if (targets.isEmpty()) {
                ctx.getSource().sendFailure(Component.translatable(
                        "message.tabletoprpg.enemy_not_found_by_name", name));
                return 0;
            }

            for (Mob mob : targets) {
                CombatController.removeMob(mob);
            }
            broadcast(ctx, Component.translatable("message.tabletoprpg.enemy_removed_selector_broadcast",
                    name, targets.size()));
            RpgNetworking.sendAuraStateToAll(ctx.getSource().getServer());
            RpgNetworking.sendSpectatorTargetsToAll(ctx.getSource().getServer());
            return 1;
        } catch (Exception e) {
            TabletopRpg.LOGGER.error("[TabletopRPG] Error removing enemies by selector: {}", e.getMessage());
            ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.enemy_remove_failed"));
            return 0;
        }
    }

    /**
     * Lê o nome de um seletor de entidade do vanilla no formato
     * {@code @e[name="Nome Exato"]}. Devolve {@code null} quando o texto não é
     * um seletor de entidade ou quando ele não traz a opção {@code name=}.
     *
     * <p>O nome entre aspas é lido até a próxima aspa, e o nome sem aspas até o
     * fim da opção — as duas formas que o vanilla aceita. Só a opção
     * {@code name=} é usada: as demais opções do seletor são ignoradas, porque
     * o filtro de mob do RPG já garante o resto.
     */
    private static String selectorName(String selector) {
        if (selector == null) {
            return null;
        }
        String text = selector.trim();
        if (text.length() < 4 || !text.regionMatches(true, 0, "@e[", 0, 3) || !text.endsWith("]")) {
            return null;
        }
        String body = text.substring(3, text.length() - 1);
        for (String option : splitSelectorOptions(body)) {
            int equal = option.indexOf('=');
            if (equal < 0) {
                continue;
            }
            // A chave é comparada depois de tirar os espaços: o vanilla aceita
            // "name = ...". E "nickname=" NÃO casa, porque a chave não é "name".
            if (!option.substring(0, equal).trim().equalsIgnoreCase("name")) {
                continue;
            }
            String value = option.substring(equal + 1).trim();
            if (value.isEmpty()) {
                return null;
            }
            if (value.charAt(0) == '"') {
                int end = value.indexOf('"', 1);
                if (end < 0) {
                    return null;
                }
                value = value.substring(1, end);
            }
            return value.isEmpty() ? null : value;
        }
        return null;
    }

    /**
     * Divide o corpo do seletor em opções (separadas por vírgula), sem quebrar
     * dentro de aspas — assim um nome com vírgula ({@code name="Bob, o Chefe"})
     * continua inteiro.
     */
    private static List<String> splitSelectorOptions(String body) {
        List<String> options = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            }
            if (c == ',' && !quoted) {
                options.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        options.add(current.toString());
        return options;
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
            ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.block_lock_arm_failed"));
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
            ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.block_unlock_arm_failed"));
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
                ctx.getSource().sendFailure(Component.translatable(
                        "message.tabletoprpg.roll_inform_bonus", mixed.pericia().name()));
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
            ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.roll_players_only"));
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
                ctx.getSource().sendFailure(Component.empty().append(e.toComponent()).withStyle(ChatFormatting.RED));
                return 0;
            }
        }

        SheetData sheet = SessionManager.getSheet(player.getUUID());
        int attrValue = sheet.attributeValue(pericia.attributeId());
        PericiaRoll roll = periciaTotal(player, pericia);
        long total = roll.total();
        int die = roll.die();
        String attrLabel = SheetModelHolder.current().attributeLabel(pericia.attributeId());

        String breakdown = "§7d20 §f(§e" + die + "§f) + §7" + pericia.value()
                + " + §7" + attrLabel + " " + attrValue;

        if (outcome != null) {
            breakdown += tailText(outcome);
            total += outcome.total();
        }

        if (outcome == null) {
            InitiativeCommands.onPericiaRolled(ctx.getSource().getServer(), player, pericia.id(), total);
        }
        MutableComponent message = Component.translatable("message.tabletoprpg.roll.skill",
                "§6§l" + player.getName().getString(), "§6" + pericia.name(), breakdown);
        if (outcome != null && outcome.critical() && !hasGrid(outcome)) {
            message.append(Component.translatable("message.tabletoprpg.roll.crit"));
        }
        if (outcome != null && hasGrid(outcome)) {
            message.append(Component.literal("\n"))
                    .append(Component.translatable("message.tabletoprpg.roll.total").withStyle(ChatFormatting.YELLOW))
                    .append(Component.literal(" §f= §l§a" + total));
        } else {
            message.append(Component.literal(" = §e§l" + total));
        }

        if (SessionManager.isMaster(player)) {
            player.sendSystemMessage(message);
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

    /** Total de uma rolagem de pericia (d20 + pericia + atributo), usado pela iniciativa. */
    static long periciaRollTotal(ServerPlayer player, SheetData.Pericia pericia) {
        return periciaTotal(player, pericia).total();
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
            ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.roll_no_skills"));
            return 0;
        }

        ctx.getSource().sendSystemMessage(Component.translatable(
                "message.tabletoprpg.roll_skill_list_header", sheet.pericias().size()));
        for (SheetData.Pericia pericia : sheet.pericias()) {
            int attr = sheet.attributeValue(pericia.attributeId());
            ctx.getSource().sendSystemMessage(Component.translatable(
                    "message.tabletoprpg.roll_skill_list_entry", pericia.name(), pericia.value(), attr,
                    SheetModelHolder.current().attributeLabel(pericia.attributeId()),
                    pericia.value() + attr));
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
            ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.roll_players_only"));
            return 0;
        }

        Component message = rollDice(ctx, rawFormula);
        if (message == null) {
            return 0;
        }

        if (SessionManager.isMaster(player)) {
            // Rolagem secreta do mestre: aparece apenas para ele.
            player.sendSystemMessage(message);
        } else {
            // Rolagem do jogador: visível para todos.
            broadcast(ctx, message);
        }
        return 1;
    }

    /** Rolagem pública (só o mestre): todos veem. */
    private static int openRoll(CommandContext<CommandSourceStack> ctx, String rawFormula) {
        if (!verifyMasterPermission(ctx)) return 0;

        Component message = rollDice(ctx, rawFormula);
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
        return rollAndPublish(ctx, player, rawFormula, false);
    }

    /**
     * Rola uma fórmula com a MESMA regra de visibilidade de {@code /rpg roll}: Mestre
     * vê só o resultado, jogador envia para todos.
     *
     * <p><b>Por que existe:</b> preset (por comando e por item) e rolagem manual
     * precisam cair nas mesmas regras, ou o mesmo dado fica secreto para um e público
     * para o outro. O texto também é o mesmo, para ninguém notar a diferença.
     *
     * <p><b>Rolagem publica (22/10/2026):</b> com {@code open} o resultado vai
     * para todo mundo, Mestre incluido.
     *
     * @return {@code 1} se rolou, {@code 0} se a fórmula foi recusada
     */
    private static int rollAndPublish(CommandContext<CommandSourceStack> ctx, ServerPlayer player,
                                      String rawFormula, boolean open) {
        Component message = rollDice(ctx, rawFormula);
        if (message == null) {
            return 0;
        }
        if (open || !SessionManager.isMaster(player)) {
            broadcast(ctx, message);
        } else {
            player.sendSystemMessage(message);
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
                    e.toComponent()));
            return 0;
        }

        UUID uuid = player.getUUID();
        // A busca e SO entre os presets da propria jogadora: um preset compartilhado
        // de outra pessoa nao impede a jogadora de ter um preset com o mesmo nome.
        RollPreset existing = RollPresetStore.findOwn(uuid, preset.key()).orElse(null);
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
        // Editar e apagar sao atos de DONO: a busca e so entre os presets da propria
        // jogadora. Um preset compartilhado de outra pessoa existe, mas nao pode
        // ser editado por quem nao o criou.
        RollPreset existing = RollPresetStore.findOwn(player.getUUID(), name).orElse(null);
        if (existing == null) {
            if (RollPresetStore.findShared(name).isPresent()) {
                ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.preset_not_owner", name));
                return 0;
            }
            ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.preset_not_found", name));
            return 0;
        }

        RollPreset edited;
        try {
            // O nome vem do preset ja salvo, nao do argumento: o argumento e a chave de
            // busca e pode estar escrito de outra forma ("ATAQUE" acha "Ataque").
            // Trocar o nome no proprio create/edit faria o preset sumir de baixo do
            // item que ja existe no inventario.
            //
            // A flag de rolagem publica e a do preset que ja existia: o comando
            // `/rpg preset edit` mexe na formula e na cor, nao na visibilidade.
            edited = RollPreset.create(existing.name(),
                    StringArgumentType.getString(ctx, "formula"),
                    StringArgumentType.getString(ctx, "color"),
                    existing.openRoll());
        } catch (RollPreset.PresetException e) {
            ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.preset_create_failed",
                    e.toComponent()));
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
        return rollAndPublish(ctx, player, preset.formula(), preset.openRoll());
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
            // Apagar e ato de DONO: um preset compartilhado de outra pessoa nao pode
            // ser apagado por quem nao o criou.
            if (RollPresetStore.findShared(name).isPresent()) {
                ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.preset_not_owner", name));
                return 0;
            }
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
        List<RollPresetStore.SharedPreset> shared = RollPresetStore.listShared(player.getUUID());
        if (presets.isEmpty() && shared.isEmpty()) {
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
        // Os compartilhados entram depois, com a marca de que vem de outra pessoa:
        // eles podem ser usados, mas nao editados nem apagados por quem nao e dono.
        for (RollPresetStore.SharedPreset sharedEntry : shared) {
            RollPreset entry = sharedEntry.preset();
            ctx.getSource().sendSuccess(() -> Component.translatable("message.tabletoprpg.preset_list_entry_shared",
                    entry.name(), entry.formula(), entry.color().displayName()), false);
        }
        return presets.size() + shared.size();
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

    /**
     * Completa os nomes de preset que a jogadora pode <b>usar</b>: os dela e os que a
     * mesa compartilhou.
     *
     * <p><b>Por que uma lista a parte:</b> os comandos de editar e apagar so
     * aceitam preset da propria jogadora, e sugerir ali um preset de outra pessoa
     * seria sugerir um comando que vai recusar. No {@code use} o preset de outra
     * pessoa e valido, entao ele entra na sugestao.
     */
    private static CompletableFuture<Suggestions> suggestUsableRollPresets(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        ServerPlayer player = presetPlayer(ctx);
        if (player == null) {
            return builder.buildFuture();
        }
        String remaining = builder.getRemainingLowerCase();
        for (RollPreset preset : RollPresetStore.list(player.getUUID())) {
            addPresetSuggestion(builder, preset.commandName(), remaining);
        }
        for (RollPresetStore.SharedPreset shared : RollPresetStore.listShared(player.getUUID())) {
            addPresetSuggestion(builder, shared.preset().commandName(), remaining);
        }
        return builder.buildFuture();
    }

    /** Acrescenta a sugestao, se o nome comeca com o que ja foi digitado. */
    private static void addPresetSuggestion(SuggestionsBuilder builder, String commandName,
            String remaining) {
        if (commandName.toLowerCase(Locale.ROOT).startsWith(remaining)) {
            builder.suggest(commandName);
        }
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
            broadcast(ctx, Component.translatable("message.tabletoprpg.world_time_set", time));
            return 1;
        } catch (Exception e) {
            TabletopRpg.LOGGER.error("[TabletopRPG] Error setting world time: {}", e.getMessage());
            ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.world_time_failed"));
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
        broadcast(ctx, Component.translatable("message.tabletoprpg.hover_distance_set", SessionManager.getHoverDistance()));
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
    private static Component rollDice(CommandContext<CommandSourceStack> ctx, String rawFormula) {
        ServerPlayer player;
        try {
            player = ctx.getSource().getPlayerOrException();
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.roll_players_only"));
            return null;
        }

        try {
            return rollMessageFor(player, rawFormula);
        } catch (DiceFormula.SyntaxException e) {
            ctx.getSource().sendFailure(Component.empty().append(e.toComponent()).withStyle(ChatFormatting.RED));
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
        return rollForPlayer(player, rawFormula, false);
    }

    /**
     * Rola e entrega o resultado a {@code player}, sem depender de comando.
     *
     * <p><b>Rolagem publica (22/10/2026):</b> com {@code open} o resultado vai
     * para a mesa inteira, mesmo quando quem rola e o Mestre. E o que a flag
     * "Open Roll" do preset significa: o dado e compartilhado com a mesa, e nao
     * um segredo do Mestre.
     *
     * @param open {@code true} para o resultado ir para todo mundo
     * @return {@code 1} se publicou o resultado, {@code 0} se a formula foi recusada
     *         (a recusa ja foi enviada para quem rollou)
     */
    public static int rollForPlayer(ServerPlayer player, String rawFormula, boolean open) {
        Component message;
        try {
            message = rollMessageFor(player, rawFormula);
        } catch (DiceFormula.SyntaxException e) {
            player.displayClientMessage(Component.empty().append(e.toComponent()).withStyle(ChatFormatting.RED), false);
            return 0;
        }
        if (open || !SessionManager.isMaster(player)) {
            MinecraftServer server = player.level().getServer();
            if (server != null) {
                server.getPlayerList().broadcastSystemMessage(message, false);
            } else {
                player.sendSystemMessage(message);
            }
        } else {
            player.sendSystemMessage(message);
        }
        return 1;
    }

    /**
     * Monta a mensagem de resultado de uma rolagem.
     *
     * @throws DiceFormula.SyntaxException se a fórmula não for reconhecida; a mensagem
     *         da exceção já é uma frase pronta para o chat
     */
    private static Component rollMessageFor(ServerPlayer player, String rawFormula)
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
            throw new DiceFormula.SyntaxException(e.getMessage(), e.getKey(), e.getArgs());
        }

        DiceFormula.Outcome outcome;
        // RANDOM e a unica fonte de aleatoriedade do comando: o cliente nao
        // pode prever a rolagem.
        outcome = DiceFormula.parse(formula).evaluate(sides -> RANDOM.nextInt(sides) + 1);

        // Com '#' o total vai para a linha propria: colado no fim da ultima volta
        // ele se confunde com o subtotal dela (30/09/2026, pedido do jogador).
        String name = "§6§e" + player.getName().getString();
        String parts = "§b" + joinParts(outcome);
        String total = "§l§a" + outcome.total();
        if (hasGrid(outcome)) {
            return Component.translatable("message.tabletoprpg.roll.result_grid", name, parts,
                    Component.translatable("message.tabletoprpg.roll.total").withStyle(ChatFormatting.YELLOW), total);
        }
        MutableComponent message = Component.translatable("message.tabletoprpg.roll.result", name, parts, total);
        if (outcome.critical()) {
            // O critico e so um marcador: o total ja saiu da somatoria.
            message.append(Component.translatable("message.tabletoprpg.roll.crit"));
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
        ctx.getSource().sendFailure(Component.translatable("message.tabletoprpg.master_only_action"));
        return false;
    }

    private static void broadcast(CommandContext<CommandSourceStack> ctx, String message) {
        broadcast(ctx, Component.literal(message));
    }

    private static void broadcast(CommandContext<CommandSourceStack> ctx, Component message) {
        PlayerList playerList = ctx.getSource().getServer().getPlayerList();
        playerList.broadcastSystemMessage(message, false);
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
        menu.append(Component.translatable("message.tabletoprpg.menu_title"));
        menu.append(Component.translatable("message.tabletoprpg.menu_session_name",
                SessionManager.getSessionName()));
        
        if (isMaster) {
            menu.append(Component.translatable("message.tabletoprpg.menu_role_master"));
            menu.append(Component.literal("§8-----------------------------------------\n"));
            
            // Seção de Modos
            menu.append(Component.translatable("message.tabletoprpg.menu_modes"));
            menu.append(createBtn(Component.translatable("message.tabletoprpg.menu.free"), ChatFormatting.GREEN, "/rpg mode free", Component.translatable("message.tabletoprpg.menu.hover.free")));
            menu.append(Component.literal(" "));
            menu.append(createBtn(Component.translatable("message.tabletoprpg.menu.investigation"), ChatFormatting.YELLOW, "/rpg mode investigation", Component.translatable("message.tabletoprpg.menu.hover.investigation")));
            menu.append(Component.literal(" "));
            menu.append(createBtn(Component.translatable("message.tabletoprpg.menu.combat"), ChatFormatting.RED, "/rpg mode combat", Component.translatable("message.tabletoprpg.menu.hover.combat")));
            menu.append(Component.translatable("message.tabletoprpg.menu_current_mode",
                    SessionManager.getMode().getDisplayName()));

            // Seção de Turnos
            menu.append(Component.translatable("message.tabletoprpg.menu_active_turn",
                    SessionManager.getActivePlayerName()));
            menu.append(Component.translatable("message.tabletoprpg.menu_turn_control"));
            menu.append(createSuggestBtn(Component.translatable("message.tabletoprpg.menu.give_turn"), ChatFormatting.LIGHT_PURPLE, "/rpg turn give ", Component.translatable("message.tabletoprpg.menu.hover.give_turn")));
            menu.append(Component.literal(" "));
            menu.append(createBtn(Component.translatable("message.tabletoprpg.menu.revoke_turn"), ChatFormatting.DARK_RED, "/rpg turn revoke", Component.translatable("message.tabletoprpg.menu.hover.revoke_turn")));
            menu.append(Component.literal("\n\n"));

            // Ações Gerais
            //
            // Sem botao de tranca aqui (decisao do usuario em 30/09/2026): o
            // comando `/rpg block_lock` e o item ja cobrem, e o botao seria uma
            // terceira via para a mesma acao.
            menu.append(Component.translatable("message.tabletoprpg.menu_actions"));
            menu.append(createSuggestBtn(Component.translatable("message.tabletoprpg.menu.roll"), ChatFormatting.AQUA, "/rpg roll ", Component.translatable("message.tabletoprpg.menu.hover.roll")));
            menu.append(Component.literal(" "));
            menu.append(createBtn(Component.translatable("message.tabletoprpg.menu.step_down"), ChatFormatting.GRAY, "/rpg master release", Component.translatable("message.tabletoprpg.menu.hover.step_down")));
            menu.append(Component.literal("\n"));

        } else {
            menu.append(Component.translatable("message.tabletoprpg.menu_role_player"));
            menu.append(Component.literal("§8-----------------------------------------\n"));
            
            menu.append(Component.translatable("message.tabletoprpg.menu_current_mode_short",
                    SessionManager.getMode().getDisplayName()));
            menu.append(Component.translatable("message.tabletoprpg.menu_active_turn_long",
                    SessionManager.getActivePlayerName()));

            boolean isMyTurn = SessionManager.isActivePlayer(player);
            if (isMyTurn) {
                menu.append(Component.translatable("message.tabletoprpg.menu_your_turn_active"));
            }

            menu.append(Component.translatable("message.tabletoprpg.menu_actions_player"));
            if (isMyTurn) {
                menu.append(createBtn(Component.translatable("message.tabletoprpg.menu.finish_turn"), ChatFormatting.GREEN, "/rpg turn finish", Component.translatable("message.tabletoprpg.menu.hover.finish_turn")));
                menu.append(Component.literal(" "));
            }
            if (!SessionManager.hasMaster()) {
                menu.append(createBtn(Component.translatable("message.tabletoprpg.menu.claim_master"), ChatFormatting.GOLD, "/rpg master claim", Component.translatable("message.tabletoprpg.menu.hover.claim_master")));
                menu.append(Component.literal(" "));
            }
            menu.append(createSuggestBtn(Component.translatable("message.tabletoprpg.menu.roll"), ChatFormatting.AQUA, "/rpg roll ", Component.translatable("message.tabletoprpg.menu.hover.roll")));
            menu.append(Component.literal("\n"));
        }

        menu.append(Component.literal("§8========================================="));
        return menu;
    }

    private static Component createBtn(Component text, ChatFormatting color, String command, Component hover) {
        return Component.empty().append(text)
            .setStyle(Style.EMPTY
                .withColor(color)
                .withBold(true)
                .withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(hover)));
    }

    private static Component createSuggestBtn(Component text, ChatFormatting color, String command, Component hover) {
        return Component.empty().append(text)
            .setStyle(Style.EMPTY
                .withColor(color)
                .withBold(true)
                .withClickEvent(new ClickEvent.SuggestCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(hover)));
    }
}