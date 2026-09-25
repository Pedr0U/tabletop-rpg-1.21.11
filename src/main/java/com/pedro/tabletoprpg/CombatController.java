package com.pedro.tabletoprpg;

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
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Controla a movimenta├º├úo de monstros pelo mestre e os limites de
 * movimenta├º├úo (auras) da Fase 4.
 *
 * <p>Fluxo: o mestre clica com o bot├úo direito em um monstro (seleciona),
 * aparece uma aura azul de {@value #AURA_RADIUS} blocos ao redor da ├óncora
 * do monstro e da ├óncora do jogador ativo, e o mestre clica com o bot├úo
 * direito em um bloco para o monstro andar at├® l├í.
 *
 * <p>Regra de ouro (FASE 0.6): o monstro NUNCA se move sozinho. Ele fica
 * congelado ({@code setNoAi(true)}) em todos os momentos ÔÇö selecionado ou
 * n├úo. O movimento ├® feito pelo servidor em linha reta at├® o destino
 * escolhido pelo mestre (sem IA, sem pathfinding), e a rota├º├úo (olhar para
 * o jogador mais pr├│ximo) tamb├®m ├® aplicada manualmente.
 *
 * <p>Limites: o mestre NUNCA ├® limitado. Apenas o jogador que est├í no turno
 * e o monstro selecionado respeitam o limite de {@value #AURA_RADIUS} blocos
 * a partir de onde come├ºaram (├óncora). A aura n├úo segue ningu├®m: ela fica
 * ancorada na posi├º├úo inicial.
 */
public final class CombatController {

    /** Raio (em blocos) da aura de limite de movimenta├º├úo. */
    public static final int AURA_RADIUS = 15;

    /** Velocidade do movimento direto do monstro controlado (blocos/tick). */
    private static final double MOVE_SPEED = 0.35;

    /** Monstro atualmente selecionado pelo mestre (UUID). */
    private static UUID selectedMonsterUuid = null;

    /** ├éncoras dos monstros: UUID do monstro -> posi├º├úo onde come├ºou. */
    private static final Map<UUID, BlockPos> monsterAnchors = new HashMap<>();

    /** ├éncoras dos jogadores: UUID do jogador -> posi├º├úo onde come├ºou. */
    private static final Map<UUID, BlockPos> playerAnchors = new HashMap<>();

    /** Monstros controlados (selecionados/movidos) que olham para o jogador mais pr├│ximo. */
    private static final Set<UUID> controlledMonsters = new HashSet<>();

    /** Destino atual do movimento direto: UUID do monstro -> posi├º├úo alvo. */
    private static final Map<UUID, Vec3> monsterDestinations = new HashMap<>();

    /** Hover atual por jogador: UUID do jogador -> UUID da entidade com Glowing. */
    private static final Map<UUID, UUID> hoveredEntities = new HashMap<>();

    /**
     * Mobs invocados com câmera (cam_perm=true): entram no carrossel de
     * espectador dos jogadores travados (FASE 2). UUID do mob -> presente.
     */
    private static final Set<UUID> cameraMobs = new HashSet<>();

    /**
     * Todos os mobs invocados pelo mestre (/rpg insert enemy), com ou sem
     * câmera. São "peças" da mesa: ficam congelados e olham para o jogador
     * mais próximo a cada tick (mesmo sem estarem selecionados).
     */
    private static final Set<UUID> insertedMobs = new HashSet<>();

    /**
     * Debounce do toggle de sele├º├úo: UUID do mestre -> (UUID do mob, tempo).
     * O cliente envia v├írios pacotes por clique (interactAt + interact + useItem)
     * e o caminho "segurar o bot├úo" do vanilla re-dispara startUseItem ap├│s
     * ~200ms. Sem debounce, um ├║nico clique alterna a sele├º├úo 2x (selected ->
     * deselected instant├óneo).
     */
    private static final Map<UUID, ToggleStamp> lastToggles = new HashMap<>();

    /** Carimbo do ├║ltimo toggle: mob clicado + instante (ms). */
    private record ToggleStamp(UUID mobUuid, long time) {
    }

    private CombatController() {
    }

    public static void register() {
        // Mestre clica com o bot├úo direito em um monstro -> seleciona/deseleciona.
        UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
            if (!(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResult.PASS;
            }
            if (!SessionManager.isMaster(serverPlayer)) {
                return InteractionResult.PASS;
            }
            if (entity instanceof Mob mob) {
                // O cliente envia 2 pacotes por clique (interactAt + interact).
                // O 2┬║ pacote (interact, sem posi├º├úo) chega com hitResult == null.
                // Ignoramos para o toggle disparar apenas 1x por clique.
                if (hitResult == null) {
                    return InteractionResult.PASS;
                }
                toggleSelection((ServerLevel) level, serverPlayer, mob);
                return InteractionResult.FAIL; // cancela a intera├º├úo vanilla
            }
            return InteractionResult.PASS;
        });

        // Mestre clica com o bot├úo direito em um bloco com sele├º├úo ativa -> move o monstro.
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
            return InteractionResult.FAIL; // cancela a intera├º├úo vanilla com o bloco
        });

        // A cada tick, os monstros controlados andam at├® o destino (se houver)
        // e olham para o jogador mais pr├│ximo; os mobs inseridos (pe├ºas da
        // mesa) olham para o jogador mais pr├│ximo mesmo sem sele├º├úo.
        ServerTickEvents.END_SERVER_TICK.register(CombatController::tickControlledMonsters);
    }

    // ------------------------------------------------------------------
    // SELE├ç├âO E MOVIMENTO
    // ------------------------------------------------------------------

    private static void toggleSelection(ServerLevel level, ServerPlayer master, Mob mob) {
        // Debounce: o mesmo mob clicado dentro de 400ms ├® pacote duplicado do
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
            // volta a ser uma "pe├ºa" congelada da mesa).
            clearSelection(level);
            master.sendSystemMessage(Component.literal("§7[RPG] Monster deselected."));
        } else {
            selectedMonsterUuid = mobUuid;
            // ├éncora do monstro: posi├º├úo atual do mob no momento da sele├º├úo.
            // A aura fica ancorada aqui durante a movimenta├º├úo (n├úo segue o
            // mob); ao re-selecionar, a ├óncora ├® atualizada para a posi├º├úo
            // atual do mob (equivale ao "in├¡cio do turno" do mob).
            monsterAnchors.put(mobUuid, mob.blockPosition());
            controlledMonsters.add(mobUuid);
            // Congela o mob: ele NUNCA age sozinho (FASE 0.6). Mesmo mobs
            // naturais (n├úo inseridos por comando) viram "pe├ºas" da mesa.
            mob.setNoAi(true);

            // ├éncora do jogador ativo (se houver): onde ele come├ºou.
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

        // Valida o limite da aura (15 blocos da ├óncora do monstro).
        BlockPos anchor = monsterAnchors.get(selectedMonsterUuid);
        if (anchor != null && !isWithinAura(anchor, dest.getX() + 0.5, dest.getZ() + 0.5)) {
            master.sendSystemMessage(Component.literal("§c[RPG] Destination is beyond the monster's aura ("
                    + AURA_RADIUS + " blocks from its start)."));
            return;
        }

        // Movimento direto (sem IA): o mob fica congelado (noAi=true) e o
        // servidor o move em linha reta at├® o destino. O Y do destino ├® a
        // superf├¡cie do terreno (Level.getHeight j├í retorna o primeiro Y
        // vazio acima do bloco mais alto ÔÇö N├âO somar +1, sen├úo o mob flutua).
        double groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, dest.getX(), dest.getZ());
        monsterDestinations.put(selectedMonsterUuid,
                new Vec3(dest.getX() + 0.5, groundY, dest.getZ() + 0.5));
        master.sendSystemMessage(Component.literal("§a[RPG] Monster moving to §e" + dest.getX() + ", "
                + dest.getZ() + "§a."));
    }

    private static void tickControlledMonsters(MinecraftServer server) {
        // Auto-recupera├º├úo: ap├│s reiniciar o servidor, os mobs inseridos
        // (marcados no NBT em insertEnemy) s├úo re-registrados.
        selfHealInsertedMobs(server);

        for (UUID uuid : new ArrayList<>(controlledMonsters)) {
            boolean found = false;
            for (ServerLevel level : server.getAllLevels()) {
                Entity entity = level.getEntity(uuid);
                if (entity instanceof Mob mob) {
                    found = true;
                    Vec3 pos = mob.position();
                    Vec3 dest = monsterDestinations.get(uuid);
                    if (dest != null) {
                        // Andando: move em linha reta at├® o destino e olha para ele.
                        Vec3 delta = dest.subtract(pos);
                        double dist = delta.horizontalDistance();
                        if (dist <= MOVE_SPEED) {
                            mob.setPos(dest.x, dest.y, dest.z);
                            monsterDestinations.remove(uuid);
                            // A aura N├âO segue o mob: ela permanece na ├óncora
                            // (posi├º├úo onde o mob estava ao ser selecionado).
                            // Assim o mestre pode corrigir o destino se errou
                            // ou mudou de ideia. A aura s├│ ir├í para a posi├º├úo
                            // atual do mob quando o turno dele come├ºar (sistema
                            // de turnos/iniciativa ÔÇö FASE futura). Ao
                            // re-selecionar o mob, a ├óncora volta a ser a
                            // posi├º├úo atual dele (toggleSelection).
                        } else {
                            Vec3 step = delta.normalize().scale(MOVE_SPEED);
                            mob.setPos(pos.x + step.x, pos.y + step.y, pos.z + step.z);
                        }
                        lookAt(mob, dest);
                    } else {
                        // Parado: olha para o jogador mais pr├│ximo.
                        ServerPlayer nearest = findNearestPlayer(level, mob);
                        if (nearest != null) {
                            lookAt(mob, nearest.getEyePosition());
                        }
                    }
                    break;
                }
            }
            if (!found) {
                controlledMonsters.remove(uuid); // monstro n├úo existe mais
                monsterDestinations.remove(uuid);
            }
        }

        // Mobs inseridos (pe├ºas da mesa): olham para o jogador mais pr├│ximo
        // mesmo sem estarem selecionados. Mobs em movimento (selecionados com
        // destino) s├úo pulados — o lookAt do movimento vale.
        for (UUID uuid : new ArrayList<>(insertedMobs)) {
            if (controlledMonsters.contains(uuid) && monsterDestinations.containsKey(uuid)) {
                continue; // andando: o lookAt do movimento vale
            }
            boolean found = false;
            for (ServerLevel level : server.getAllLevels()) {
                Entity entity = level.getEntity(uuid);
                if (entity instanceof Mob mob) {
                    found = true;
                    ServerPlayer nearest = findNearestPlayer(level, mob);
                    if (nearest != null) {
                        lookAt(mob, nearest.getEyePosition());
                    }
                    break;
                }
            }
            if (!found) {
                insertedMobs.remove(uuid); // mob n├úo existe mais
                cameraMobs.remove(uuid);
            }
        }
    }

    /**
     * Faz o mob olhar para um ponto (rota├º├úo manual, sem IA ÔÇö o mob est├í
     * congelado com noAi=true, ent├úo o lookControl vanilla n├úo roda).
     */
    private static void lookAt(Mob mob, Vec3 target) {
        Vec3 pos = mob.getEyePosition();
        double dx = target.x - pos.x;
        double dy = target.y - pos.y;
        double dz = target.z - pos.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(Math.atan2(dy, horizontal));
        mob.setYRot(yaw);
        mob.setXRot(pitch);
        mob.setYHeadRot(yaw);
    }

    // ------------------------------------------------------------------
    // LIMITES (AURAS)
    // ------------------------------------------------------------------

    /** Verifica se (x, z) est├í dentro do c├¡rculo de raio AURA_RADIUS ao redor da ├óncora. */
    public static boolean isWithinAura(BlockPos anchor, double x, double z) {
        if (anchor == null) {
            return true;
        }
        double dx = x - (anchor.getX() + 0.5);
        double dz = z - (anchor.getZ() + 0.5);
        return (dx * dx + dz * dz) <= (double) AURA_RADIUS * AURA_RADIUS;
    }

    /**
     * Verifica se o jogador est├í tentando sair da aura dele. O mestre nunca
     * ├® limitado; apenas jogadores com ├óncora (o jogador ativo) s├úo.
     */
    public static boolean isPlayerBeyondAura(ServerPlayer player, double x, double z) {
        if (SessionManager.isMaster(player)) {
            return false; // o mestre nunca ├® limitado
        }
        BlockPos anchor = playerAnchors.get(player.getUUID());
        if (anchor == null) {
            return false; // sem ├óncora -> sem limite
        }
        return !isWithinAura(anchor, x, z);
    }

    /**
     * Projeta (x, z) de volta para dentro da aura do jogador (c├¡rculo de
     * {@value #AURA_RADIUS} blocos ao redor da ├óncora). Se j├í estiver dentro,
     * retorna a posi├º├úo original. Usado para "barrar" o jogador na borda da
     * aura: em vez de s├│ cancelar o pacote de movimento (o que deixa o cliente
     * continuar andando por predi├º├úo), o servidor corrige a posi├º├úo para a
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

    /** Posi├º├Áes das auras ativas (monstro selecionado + jogador ativo) para o payload. */
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
    // MOBS COM CÂMERA (CARROSSEL DE ESPECTADOR)
    // ------------------------------------------------------------------

    /** Marca um mob como espectável (invocado com cam_perm=true). */
    public static void addCameraMob(UUID mobUuid) {
        cameraMobs.add(mobUuid);
    }

    /** Marca um mob como inserido pelo mestre (peça da mesa, olha o jogador). */
    public static void addInsertedMob(UUID mobUuid) {
        insertedMobs.add(mobUuid);
    }

    /** Remove um mob da lista de espectáveis (removido do mundo). */
    public static void removeCameraMob(UUID mobUuid) {
        cameraMobs.remove(mobUuid);
    }

    /** Mobs atualmente espectáveis (com câmera). */
    public static Set<UUID> getCameraMobs() {
        return cameraMobs;
    }

    /**
     * Auto-recuperação dos mobs com câmera após reiniciar o servidor: os mobs
     * persistem no mundo (com a marca NBT "tabletoprpg_camera" gravada em
     * insertEnemy), mas o Set em memória é perdido. Re-registra os que ainda
     * existem. Chamado ao montar a lista de alvos do carrossel.
     */
    public static void selfHealCameraMobs(MinecraftServer server) {
        if (!cameraMobs.isEmpty()) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity e : level.getAllEntities()) {
                if (e instanceof Mob mob && mob.getTags().contains("tabletoprpg_camera")) {
                    cameraMobs.add(mob.getUUID());
                    insertedMobs.add(mob.getUUID());
                }
            }
        }
    }

    /**
     * Auto-recuperação dos mobs inseridos após reiniciar o servidor (marca NBT
     * "tabletoprpg_inserted"). Chamado a cada tick (barato: só varre quando o
     * Set está vazio).
     */
    private static void selfHealInsertedMobs(MinecraftServer server) {
        if (!insertedMobs.isEmpty()) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity e : level.getAllEntities()) {
                if (e instanceof Mob mob && mob.getTags().contains("tabletoprpg_inserted")) {
                    insertedMobs.add(mob.getUUID());
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // REMOÇÃO DE INIMIGO (/rpg remove enemy)
    // ------------------------------------------------------------------

    /** O mob atualmente selecionado pelo mestre, ou null se não houver. */
    public static Mob getSelectedMonster(ServerLevel level) {
        if (selectedMonsterUuid == null) {
            return null;
        }
        Entity entity = level.getEntity(selectedMonsterUuid);
        return entity instanceof Mob mob ? mob : null;
    }

    /**
     * Remove o mob selecionado do mundo (1 mob por execução) e limpa a
     * seleção. Também remove da lista de espectáveis (câmera) e de inseridos.
     */
    public static void removeSelectedMonster(ServerLevel level) {
        if (selectedMonsterUuid == null) {
            return;
        }
        Entity entity = level.getEntity(selectedMonsterUuid);
        if (entity != null) {
            entity.discard();
        }
        cameraMobs.remove(selectedMonsterUuid);
        insertedMobs.remove(selectedMonsterUuid);
        clearSelection(level);
    }

    // ------------------------------------------------------------------
    // AUXILIARES
    // ------------------------------------------------------------------

    public static boolean hasSelection() {
        return selectedMonsterUuid != null;
    }

    /**
     * Define a ├óncora do jogador (posi├º├úo onde come├ºou o turno). Chamado
     * quando o turno ├® concedido e quando um monstro ├® selecionado.
     * A ├óncora persiste at├® o fim do turno (n├úo segue o jogador).
     */
    public static void setPlayerAnchor(ServerPlayer player) {
        playerAnchors.putIfAbsent(player.getUUID(), player.blockPosition());
    }

    /** Remove a ├óncora do jogador (fim do turno). */
    public static void clearPlayerAnchor(UUID playerUuid) {
        playerAnchors.remove(playerUuid);
    }

    /** Limpa sele├º├úo, ├óncoras e monstros controlados (fim do encontro). */
    public static void reset(MinecraftServer server) {
        // Os monstros controlados j├í est├úo congelados (noAi=true) ÔÇö nada a
        // restaurar. S├│ limpamos o estado de controle.
        selectedMonsterUuid = null;
        monsterAnchors.clear();
        playerAnchors.clear();
        controlledMonsters.clear();
        monsterDestinations.clear();
        hoveredEntities.clear();
        // cameraMobs e insertedMobs N├âO s├úo limpos: os mobs com c├ómera/inseridos
        // persistem no carrossel e no lookAt mesmo ao mudar de modo ou liberar
        // o mestre (feedback do usu├írio — os mobs sumiam do carrossel ao mudar
        // de modo de jogo).
        lastToggles.clear();
    }

    /**
     * Limpa a sele├º├úo: o monstro deselecionado continua congelado (noAi=true)
     * e para de olhar para o jogador mais pr├│ximo. A ├óncora do monstro
     * persiste (o limite n├úo muda entre sele├º├Áes).
     */
    private static void clearSelection(ServerLevel level) {
        controlledMonsters.remove(selectedMonsterUuid);
        monsterDestinations.remove(selectedMonsterUuid);
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
