package com.pedro.tabletoprpg;

import com.pedro.tabletoprpg.mixin.MobAccessor;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Controla a movimentação de monstros pelo mestre e os limites de
 * movimentação (auras) da Fase 4.
 *
 * <p>Fluxo: o mestre clica com o botão direito em um monstro (seleciona),
 * aparece uma aura azul de {@value #AURA_RADIUS} blocos ao redor da âncora
 * do monstro e da âncora do jogador ativo, e o mestre clica com o botão
 * direito em um bloco para o monstro andar até lá. O monstro sempre olha
 * para o jogador mais próximo (não fica olhando para o nada).
 *
 * <p>Limites: o mestre NUNCA é limitado. Apenas o jogador que está no turno
 * e o monstro selecionado respeitam o limite de {@value #AURA_RADIUS} blocos
 * a partir de onde começaram (âncora). A aura não segue ninguém: ela fica
 * ancorada na posição inicial.
 */
public final class CombatController {

    /** Raio (em blocos) da aura de limite de movimentação. */
    public static final int AURA_RADIUS = 15;

    /** Monstro atualmente selecionado pelo mestre (UUID). */
    private static UUID selectedMonsterUuid = null;

    /** Âncoras dos monstros: UUID do monstro -> posição onde começou. */
    private static final Map<UUID, BlockPos> monsterAnchors = new HashMap<>();

    /** Âncoras dos jogadores: UUID do jogador -> posição onde começou. */
    private static final Map<UUID, BlockPos> playerAnchors = new HashMap<>();

    /** Monstros controlados (selecionados/movidos) que olham para o jogador mais próximo. */
    private static final Set<UUID> controlledMonsters = new HashSet<>();

    /** Hover atual por jogador: UUID do jogador -> UUID da entidade com Glowing. */
    private static final Map<UUID, UUID> hoveredEntities = new HashMap<>();

    /**
     * Debounce do toggle de seleção: UUID do mestre -> (UUID do mob, tempo).
     * O cliente envia vários pacotes por clique (interactAt + interact + useItem)
     * e o caminho "segurar o botão" do vanilla re-dispara startUseItem após
     * ~200ms. Sem debounce, um único clique alterna a seleção 2x (selected ->
     * deselected instantâneo).
     */
    private static final Map<UUID, ToggleStamp> lastToggles = new HashMap<>();

    /** Carimbo do último toggle: mob clicado + instante (ms). */
    private record ToggleStamp(UUID mobUuid, long time) {
    }

    private CombatController() {
    }

    public static void register() {
        // Mestre clica com o botão direito em um monstro -> seleciona/deseleciona.
        UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
            if (!(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResult.PASS;
            }
            if (!SessionManager.isMaster(serverPlayer)) {
                return InteractionResult.PASS;
            }
            if (entity instanceof Mob mob) {
                // O cliente envia 2 pacotes por clique (interactAt + interact).
                // O 2º pacote (interact, sem posição) chega com hitResult == null.
                // Ignoramos para o toggle disparar apenas 1x por clique.
                if (hitResult == null) {
                    return InteractionResult.PASS;
                }
                toggleSelection((ServerLevel) level, serverPlayer, mob);
                return InteractionResult.FAIL; // cancela a interação vanilla
            }
            return InteractionResult.PASS;
        });

        // Mestre clica com o botão direito em um bloco com seleção ativa -> move o monstro.
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (!(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResult.PASS;
            }
            if (!SessionManager.isMaster(serverPlayer)) {
                return InteractionResult.PASS;
            }
            if (!hasSelection()) {
                return InteractionResult.PASS;
            }
            moveSelectedMonster((ServerLevel) level, serverPlayer, hitResult.getBlockPos());
            return InteractionResult.FAIL; // cancela a interação vanilla com o bloco
        });

        // A cada tick, os monstros controlados olham para o jogador mais próximo.
        ServerTickEvents.END_SERVER_TICK.register(CombatController::tickControlledMonsters);
    }

    // ------------------------------------------------------------------
    // SELEÇÃO E MOVIMENTO
    // ------------------------------------------------------------------

    private static void toggleSelection(ServerLevel level, ServerPlayer master, Mob mob) {
        // Debounce: o mesmo mob clicado dentro de 400ms é pacote duplicado do
        // mesmo clique (interactAt + interact + useItem + hold path do vanilla).
        long now = System.currentTimeMillis();
        ToggleStamp stamp = lastToggles.get(master.getUUID());
        if (stamp != null && stamp.mobUuid().equals(mob.getUUID()) && now - stamp.time() < 400) {
            return;
        }
        lastToggles.put(master.getUUID(), new ToggleStamp(mob.getUUID(), now));

        UUID mobUuid = mob.getUUID();
        if (mobUuid.equals(selectedMonsterUuid)) {
            // Clicou no mesmo monstro -> deseleciona (a aura some e o monstro
            // volta a ser uma "peça" congelada da mesa).
            clearSelection(level);
            master.sendSystemMessage(Component.literal("§7[RPG] Monster deselected."));
        } else {
            selectedMonsterUuid = mobUuid;
            // Âncora do monstro: onde ele começou (persiste entre seleções).
            monsterAnchors.putIfAbsent(mobUuid, mob.blockPosition());
            controlledMonsters.add(mobUuid);
            prepareControlledMob(mob);

            // Âncora do jogador ativo (se houver): onde ele começou.
            ServerPlayer active = findActivePlayer(level);
            if (active != null) {
                setPlayerAnchor(active);
            }

            master.sendSystemMessage(Component.literal("§b[RPG] Monster selected: §e" + mob.getName().getString()
                    + "§b. §7Aura de " + AURA_RADIUS + " blocos ativa. Right-click a block to move it."));
        }
        RpgNetworking.sendAuraStateToAll(level.getServer());
    }

    private static void moveSelectedMonster(ServerLevel level, ServerPlayer master, BlockPos dest) {
        if (selectedMonsterUuid == null) {
            return;
        }
        Entity entity = level.getEntity(selectedMonsterUuid);
        if (!(entity instanceof Mob mob)) {
            master.sendSystemMessage(Component.literal("§c[RPG] Selected monster is no longer in the world."));
            clearSelection(level);
            RpgNetworking.sendAuraStateToAll(level.getServer());
            return;
        }

        // Valida o limite da aura (15 blocos da âncora do monstro).
        BlockPos anchor = monsterAnchors.get(selectedMonsterUuid);
        if (anchor != null && !isWithinAura(anchor, dest.getX() + 0.5, dest.getZ() + 0.5)) {
            master.sendSystemMessage(Component.literal("§c[RPG] Destination is beyond the monster's aura ("
                    + AURA_RADIUS + " blocks from its start)."));
            return;
        }

        prepareControlledMob(mob);
        boolean path = mob.getNavigation().moveTo(dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5, 1.0);
        if (!path) {
            master.sendSystemMessage(Component.literal("§c[RPG] No path found to the destination block."));
        } else {
            master.sendSystemMessage(Component.literal("§a[RPG] Monster moving to §e" + dest.getX() + ", "
                    + dest.getZ() + "§a."));
        }
    }

    /**
     * Prepara o mob para ser controlado: habilita o tick de IA (necessário
     * para navigation + look control funcionarem) e remove todos os goals
     * para ele não agir por conta própria (atacar, vagar, etc.).
     */
    private static void prepareControlledMob(Mob mob) {
        mob.setNoAi(false);
        ((MobAccessor) mob).getGoalSelector().removeAllGoals(goal -> true);
        ((MobAccessor) mob).getTargetSelector().removeAllGoals(goal -> true);
        mob.setTarget(null);
    }

    private static void tickControlledMonsters(MinecraftServer server) {
        if (controlledMonsters.isEmpty()) {
            return;
        }
        for (UUID uuid : new ArrayList<>(controlledMonsters)) {
            boolean found = false;
            for (ServerLevel level : server.getAllLevels()) {
                Entity entity = level.getEntity(uuid);
                if (entity instanceof Mob mob) {
                    found = true;
                    ServerPlayer nearest = findNearestPlayer(level, mob);
                    if (nearest != null) {
                        mob.getLookControl().setLookAt(nearest, 30.0F, 30.0F);
                    }
                    break;
                }
            }
            if (!found) {
                controlledMonsters.remove(uuid); // monstro não existe mais
            }
        }
    }

    // ------------------------------------------------------------------
    // LIMITES (AURAS)
    // ------------------------------------------------------------------

    /** Verifica se (x, z) está dentro do círculo de raio AURA_RADIUS ao redor da âncora. */
    public static boolean isWithinAura(BlockPos anchor, double x, double z) {
        if (anchor == null) {
            return true;
        }
        double dx = x - (anchor.getX() + 0.5);
        double dz = z - (anchor.getZ() + 0.5);
        return (dx * dx + dz * dz) <= (double) AURA_RADIUS * AURA_RADIUS;
    }

    /**
     * Verifica se o jogador está tentando sair da aura dele. O mestre nunca
     * é limitado; apenas jogadores com âncora (o jogador ativo) são.
     */
    public static boolean isPlayerBeyondAura(ServerPlayer player, double x, double z) {
        if (SessionManager.isMaster(player)) {
            return false; // o mestre nunca é limitado
        }
        BlockPos anchor = playerAnchors.get(player.getUUID());
        if (anchor == null) {
            return false; // sem âncora -> sem limite
        }
        return !isWithinAura(anchor, x, z);
    }

    /**
     * Projeta (x, z) de volta para dentro da aura do jogador (círculo de
     * {@value #AURA_RADIUS} blocos ao redor da âncora). Se já estiver dentro,
     * retorna a posição original. Usado para "barrar" o jogador na borda da
     * aura: em vez de só cancelar o pacote de movimento (o que deixa o cliente
     * continuar andando por predição), o servidor corrige a posição para a
     * borda e avisa o cliente.
     */
    public static Vec3 clampToAura(ServerPlayer player, double x, double z) {
        BlockPos anchor = playerAnchors.get(player.getUUID());
        if (anchor == null) {
            return new Vec3(x, player.getY(), z);
        }
        double cx = anchor.getX() + 0.5;
        double cz = anchor.getZ() + 0.5;
        double dx = x - cx;
        double dz = z - cz;
        double distSq = dx * dx + dz * dz;
        if (distSq <= (double) AURA_RADIUS * AURA_RADIUS) {
            return new Vec3(x, player.getY(), z);
        }
        double dist = Math.sqrt(distSq);
        double scale = AURA_RADIUS / dist;
        return new Vec3(cx + dx * scale, player.getY(), cz + dz * scale);
    }

    /** Posições das auras ativas (monstro selecionado + jogador ativo) para o payload. */
    public static List<RpgNetworking.AuraStatePayload.AuraData> getAuraData(MinecraftServer server) {
        List<RpgNetworking.AuraStatePayload.AuraData> list = new ArrayList<>();
        if (selectedMonsterUuid != null) {
            BlockPos anchor = monsterAnchors.get(selectedMonsterUuid);
            if (anchor != null) {
                list.add(new RpgNetworking.AuraStatePayload.AuraData(
                        anchor.getX() + 0.5, anchor.getY() + 0.05, anchor.getZ() + 0.5, AURA_RADIUS));
            }
        }
        if (server != null) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (SessionManager.isActivePlayer(p)) {
                    BlockPos anchor = playerAnchors.get(p.getUUID());
                    if (anchor != null) {
                        list.add(new RpgNetworking.AuraStatePayload.AuraData(
                                anchor.getX() + 0.5, anchor.getY() + 0.05, anchor.getZ() + 0.5, AURA_RADIUS));
                    }
                    break;
                }
            }
        }
        return list;
    }

    // ------------------------------------------------------------------
    // HOVER (HIGHLIGHT)
    // ------------------------------------------------------------------

    public static UUID getHoveredEntity(UUID playerUuid) {
        return hoveredEntities.get(playerUuid);
    }

    public static void setHoveredEntity(UUID playerUuid, UUID entityUuid) {
        hoveredEntities.put(playerUuid, entityUuid);
    }

    public static void clearHoveredEntity(UUID playerUuid) {
        hoveredEntities.remove(playerUuid);
    }

    // ------------------------------------------------------------------
    // AUXILIARES
    // ------------------------------------------------------------------

    public static boolean hasSelection() {
        return selectedMonsterUuid != null;
    }

    /**
     * Define a âncora do jogador (posição onde começou o turno). Chamado
     * quando o turno é concedido e quando um monstro é selecionado.
     * A âncora persiste até o fim do turno (não segue o jogador).
     */
    public static void setPlayerAnchor(ServerPlayer player) {
        playerAnchors.putIfAbsent(player.getUUID(), player.blockPosition());
    }

    /** Remove a âncora do jogador (fim do turno). */
    public static void clearPlayerAnchor(UUID playerUuid) {
        playerAnchors.remove(playerUuid);
    }

    /** Limpa seleção, âncoras e monstros controlados (fim do encontro). */
    public static void reset(MinecraftServer server) {
        // Restaura os monstros controlados: voltam a ser "peças" congeladas.
        if (server != null) {
            for (UUID uuid : new ArrayList<>(controlledMonsters)) {
                for (ServerLevel level : server.getAllLevels()) {
                    Entity entity = level.getEntity(uuid);
                    if (entity instanceof Mob mob) {
                        mob.setNoAi(true);
                        break;
                    }
                }
            }
        }
        selectedMonsterUuid = null;
        monsterAnchors.clear();
        playerAnchors.clear();
        controlledMonsters.clear();
        lastToggles.clear();
    }

    /**
     * Limpa a seleção e restaura o monstro deselecionado: congela de novo
     * (setNoAi(true)) e para de fazê-lo olhar para o jogador mais próximo.
     * A âncora do monstro persiste (o limite não muda entre seleções).
     */
    private static void clearSelection(ServerLevel level) {
        if (selectedMonsterUuid != null) {
            Entity entity = level.getEntity(selectedMonsterUuid);
            if (entity instanceof Mob mob) {
                mob.setNoAi(true);
            }
            controlledMonsters.remove(selectedMonsterUuid);
        }
        selectedMonsterUuid = null;
    }

    private static ServerPlayer findActivePlayer(ServerLevel level) {
        for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
            if (SessionManager.isActivePlayer(p)) {
                return p;
            }
        }
        return null;
    }

    private static ServerPlayer findNearestPlayer(ServerLevel level, Entity from) {
        ServerPlayer nearest = null;
        double best = Double.MAX_VALUE;
        for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
            double d = p.distanceToSqr(from);
            if (d < best) {
                best = d;
                nearest = p;
            }
        }
        return nearest;
    }
}