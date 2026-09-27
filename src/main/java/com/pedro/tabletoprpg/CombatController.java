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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

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
 * direito em um bloco para o monstro andar até lá.
 *
 * <p>Regra de ouro (FASE 0.6): o monstro NUNCA se move sozinho. Ele fica
 * congelado ({@code setNoAi(true)}) em todos os momentos ÔÇö selecionado ou
 * não. O movimento é feito pelo servidor em linha reta até o destino
 * escolhido pelo mestre (sem IA, sem pathfinding), e a rotação (olhar para
 * o jogador mais próximo) também é aplicada manualmente.
 *
 * <p>Limites: o mestre NUNCA é limitado. Apenas o jogador que está no turno
 * e o monstro selecionado respeitam o limite de {@value #AURA_RADIUS} blocos
 * a partir de onde começaram (âncora). A aura não segue ninguém: ela fica
 * ancorada na posição inicial.
 */
public final class CombatController {

    /** Raio (em blocos) da aura de limite de movimentação. */
    public static final int AURA_RADIUS = 15;

    /** Velocidade do movimento direto do monstro controlado (blocos/tick). */
    private static final double MOVE_SPEED = 0.35;

    /** Monstro atualmente selecionado pelo mestre (UUID). */
    private static UUID selectedMonsterUuid = null;

    /** Âncoras dos monstros: UUID do monstro -> posição onde começou. */
    private static final Map<UUID, BlockPos> monsterAnchors = new HashMap<>();

    /** Âncoras dos jogadores: UUID do jogador -> posição onde começou. */
    private static final Map<UUID, BlockPos> playerAnchors = new HashMap<>();

    /** Monstros controlados (selecionados/movidos) que olham para o jogador mais próximo. */
    private static final Set<UUID> controlledMonsters = new HashSet<>();

    /** Destino atual do movimento direto: UUID do monstro -> posição alvo. */
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

        // A cada tick, os monstros controlados andam até o destino (se houver)
        // e olham para o jogador mais próximo; os mobs inseridos (peças da
        // mesa) olham para o jogador mais próximo mesmo sem seleção.
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
            // Âncora do monstro: posição atual do mob no momento da seleção.
            // A aura fica ancorada aqui durante a movimentação (não segue o
            // mob); ao re-selecionar, a âncora é atualizada para a posição
            // atual do mob (equivale ao "início do turno" do mob).
            monsterAnchors.put(mobUuid, mob.blockPosition());
            controlledMonsters.add(mobUuid);
            // Congela o mob: ele NUNCA age sozinho (FASE 0.6). Mesmo mobs
            // naturais (não inseridos por comando) viram "peças" da mesa.
            mob.setNoAi(true);

            // Âncora do jogador ativo (se houver): onde ele começou.
            ServerPlayer active = findActivePlayer(level);
            if (active != null) {
                setPlayerAnchor(active);
            }

            master.sendSystemMessage(Component.literal("§b[RPG] Monster selected: §e" + mob.getName().getString()
                    + "§b. §7Aura of " + AURA_RADIUS + " blocks active. Right-click a block to move it."));
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

        // Movimento direto (sem IA): o mob fica congelado (noAi=true) e o
        // servidor o move em linha reta até o destino.
        //
        // O Y do destino é o TOPO DA SUPERFÍCIE onde o mob vai se segurar,
        // e esse topo não é sempre o topo do bloco clicado. Três situações:
        //
        // 1. ESCADA. O clique é num bloco de escada, que fica no ar encostado
        //    na parede e não tem chão embaixo. O mob é posto com os pés no
        //    fundo do bloco da escada, ou seja, na altura dela, e FICA NO AR:
        //    com NoAI a gravidade não roda (LivingEntity.travel só é chamado
        //    com IA efetiva) e nada prende a entidade, então ele permanece
        //    segurando a escada até o mestre mandar outro lugar
        //    (bug pedido em 26/09/2026).
        //
        // 2. O bloco tem superfície real. Aí se usa o topo da FORMA DE COLISÃO
        //    dele, e não dest.getY() + 1. Isso importa porque isSolid() é um
        //    teste grosseiro de "é mais ou menos um bloco inteiro"
        //    (getSize() >= 0.729 OU altura >= 1), então meio bloco, placa de
        //    pressão e moldura de portal do fim, que têm menos de 1 bloco de
        //    altura, passam por ele. Com +1 fixo o mob ficava boiando acima
        //    dessas formas (bug relatado em 26/09/2026). Andaime entra
        //    aqui: ScaffoldingBlock devolve SHAPE_STABLE mesmo com
        //    CollisionContext vazio, então o mob fica em cima dele.
        //
        // 3. O bloco é atravessável (grama, samambaia, fio de redstone, teia,
        //    camada de neve, luz, videira): a forma de colisão é vazia, logo
        //    não há superfície onde o mob se segure, e o +1 o deixava 1 bloco
        //    acima do chão real, apoiado no vazio (bug relatado em 26/09/2026).
        //    Aqui usa a superfície sólida da coluna em MOTION_BLOCKING_NO_LEAVES,
        //    que ignora o bloco atravessável e também a folha — preserva a
        //    correção da copa sem reintroduzir o buraco da grama.
        //
        // Antes, para o caso 2, vinha Level.getHeight(MOTION_BLOCKING, x, z):
        // o heightmap guarda o Y do bloco que bloqueia movimento na COLUNA, e
        // esse predicado inclui tronco e folha. Debaixo de uma árvore o topo
        // era a copa, e o mob era posto em cima dela em vez de ficar embaixo
        // (bug relatado em 26/09/2026). Por isso a forma de colisão do bloco
        // clicado tem precedência sobre o heightmap.
        BlockState clicked = level.getBlockState(dest);
        VoxelShape clickedShape = clicked.getCollisionShape(level, dest, CollisionContext.empty());
        double groundY;
        if (clicked.is(Blocks.LADDER)) {
            groundY = dest.getY();
        } else if (!clickedShape.isEmpty() && clickedShape.bounds().maxY > 0.0) {
            groundY = dest.getY() + clickedShape.bounds().maxY;
        } else {
            groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, dest.getX(), dest.getZ());
        }
        // Sem esta checagem o vanilla empurra o mob para fora do bloco em que
        // ele foi posto, e ele volta a subir. O mestre é avisado e o
        // movimento é recusado, para o destino continuar sendo o que ele
        // clicou em vez de um lugar acima que ninguém pediu.
        //
        // Testa a AABB que o mob teria no destino, e não só a coluna 1x1 acima
        // do bloco: assim folha, slab, cerca, bau e placa bloqueiam, e um mob
        // largo (ravager, ghast) tambem e recusado. `noCollision` tambem
        // respeita a borda do mundo.
        double dx = dest.getX() + 0.5 - mob.getX();
        double dy = groundY - mob.getY();
        double dz = dest.getZ() + 0.5 - mob.getZ();
        if (!level.noCollision(mob, mob.getBoundingBox().move(dx, dy, dz))) {
            master.sendSystemMessage(Component.literal(
                    "§c[RPG] No room above that block to place the entity."));
            return;
        }
        monsterDestinations.put(selectedMonsterUuid,
                new Vec3(dest.getX() + 0.5, groundY, dest.getZ() + 0.5));
        master.sendSystemMessage(Component.literal("§a[RPG] Monster moving to §e" + dest.getX() + ", "
                + dest.getY() + ", " + dest.getZ() + "§a."));
    }

    private static void tickControlledMonsters(MinecraftServer server) {
        // Auto-recuperação: após reiniciar o servidor, os mobs inseridos
        // (marcados no NBT em insertEnemy) são re-registrados.
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
                        // Andando: move em linha reta até o destino e olha para ele.
                        Vec3 delta = dest.subtract(pos);
                        double dist = delta.horizontalDistance();
                        if (dist <= MOVE_SPEED) {
                            mob.setPos(dest.x, dest.y, dest.z);
                            monsterDestinations.remove(uuid);
                            // A aura NÃO segue o mob: ela permanece na âncora
                            // (posição onde o mob estava ao ser selecionado).
                            // Assim o mestre pode corrigir o destino se errou
                            // ou mudou de ideia. A aura só irá para a posição
                            // atual do mob quando o turno dele começar (sistema
                            // de turnos/iniciativa ÔÇö FASE futura). Ao
                            // re-selecionar o mob, a âncora volta a ser a
                            // posição atual dele (toggleSelection).
                        } else {
                            Vec3 step = delta.normalize().scale(MOVE_SPEED);
                            mob.setPos(pos.x + step.x, pos.y + step.y, pos.z + step.z);
                        }
                        lookAt(mob, dest);
                    } else {
                        // Parado: olha para o jogador mais próximo.
                        ServerPlayer nearest = findNearestPlayer(level, mob);
                        if (nearest != null) {
                            lookAt(mob, nearest.getEyePosition());
                        }
                    }
                    break;
                }
            }
            if (!found) {
                controlledMonsters.remove(uuid); // monstro não existe mais
                monsterDestinations.remove(uuid);
            }
        }

        // Mobs inseridos (peças da mesa): olham para o jogador mais próximo
        // mesmo sem estarem selecionados. Mobs em movimento (selecionados com
        // destino) são pulados — o lookAt do movimento vale.
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
                insertedMobs.remove(uuid); // mob não existe mais
                cameraMobs.remove(uuid);
            }
        }
    }

    /**
     * Faz o mob olhar para um ponto (rotação manual, sem IA ÔÇö o mob está
     * congelado com noAi=true, então o lookControl vanilla não roda).
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

    /** Posiçóes das auras ativas (monstro selecionado + jogador ativo) para o payload. */
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
        // Os monstros controlados já estão congelados (noAi=true) ÔÇö nada a
        // restaurar. Só limpamos o estado de controle.
        selectedMonsterUuid = null;
        monsterAnchors.clear();
        playerAnchors.clear();
        controlledMonsters.clear();
        monsterDestinations.clear();
        hoveredEntities.clear();
        // cameraMobs e insertedMobs NÃO são limpos: os mobs com câmera/inseridos
        // persistem no carrossel e no lookAt mesmo ao mudar de modo ou liberar
        // o mestre (feedback do usuário — os mobs sumiam do carrossel ao mudar
        // de modo de jogo).
        lastToggles.clear();
    }

    /**
     * Limpa a seleção: o monstro deselecionado continua congelado (noAi=true)
     * e para de olhar para o jogador mais próximo. A âncora do monstro
     * persiste (o limite não muda entre seleçóes).
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
