package com.pedro.tabletoprpg;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
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
 *
 * <p><b>22/10/2026 (decisão do usuário):</b> no modo FREE (investigação
 * livre, sem combate) o monstro é LIVRE — a âncora e a aura não limitam o
 * movimento dele ({@link #isMonsterMovementFree}). Nos modos INVESTIGATION e
 * COMBAT o limite continua valendo, como antes.
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
            // Ficha de Ameaca na mao tem a PRIMEIRA palavra: o clique que amarra a ficha
            // no mob e o mesmo clique que alternaria a selecao, e quem chega antes
            // consome. O Mestre segurando a ficha escolheu a ficha, nao a selecao
            // (decisao de 02/10/2026).
            if (ThreatSheetBinding.handleEntityClick(serverPlayer, (ServerLevel) level, hand, entity,
                    hitResult != null)) {
                return InteractionResult.FAIL;
            }
            // Camera Tool responde ANTES da checagem de Mestre: um jogador comum com o
            // item na mao precisa receber a recusa, senao o item seria um botao morto e
            // silencioso. Se ela consumir o clique, a selecao normal nao roda -- no
            // mesmo clique nao pode haver camera E selecao.
            if (CameraToolManager.handleEntityClick(serverPlayer, (ServerLevel) level, hand, entity,
                    hitResult != null)) {
                return InteractionResult.FAIL;
            }
            if (!SessionManager.isMaster(serverPlayer)) {
                return InteractionResult.PASS;
            }
            // O clique acerta a PARTE, nao a entidade. O Ender Dragon e montado de
            // partes, e cada parte e uma entidade que NAO e um Mob -- entao o teste
            // abaixo reprovava e o Mestre nao conseguia selecionar o dragao de jeito
            // nenhum (bug relatado em 01/10/2026). Ver EntityTargets.
            Entity target = EntityTargets.resolve(entity);

            if (target instanceof Mob mob) {
                // O cliente envia 2 pacotes por clique (interactAt + interact).
                // O 2º pacote (interact, sem posição) chega com hitResult == null.
                // Ignoramos para o toggle disparar apenas 1x por clique.
                if (hitResult == null) {
                    return InteractionResult.PASS;
                }
                toggleSelection((ServerLevel) level, serverPlayer, mob);
                return InteractionResult.FAIL; // cancela a interação vanilla
            }

            // Diagnostico pedido em 01/10/2026: o clique chegou, o jogador e Mestre,
            // mas a entidade nao serve para selecao. E o unico rastro possivel quando
            // uma criatura de mod montada de partes falhar -- o 1.21.11 nao tem API
            // generica para desembrulhar partes, entao este log e a pista.
            // Jogador fica de fora de proposito: clicar num jogador e rotina.
            // hitResult != null limita a 1 linha por clique (o 2o pacote vem nulo).
            if (hitResult != null && !(target instanceof Player)) {
                TabletopRpg.LOGGER.info("[TabletopRPG] Clique do Mestre sem alvo valido: {}",
                        EntityTargets.describe(entity, target));
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
            master.sendSystemMessage(Component.translatable("message.tabletoprpg.monster_deselected"));
        } else {
            selectMob(level, master, mob);
        }
        RpgNetworking.sendAuraStateToAll(level.getServer());
    }

    /**
     * Seleciona um monstro. Caminho do clique direito (02/10/2026).
     *
     * <p><b>05/10/2026:</b> este metodo deixou de ser caminho compartilhado. O
     * {@code /rpg mob <nome>} tambem chamava aqui, e a jogadora pediu que aquele comando
     * abrisse a ficha em vez de selecionar. A unica porta de entrada da selecao e o
     * clique direito, em {@code toggleSelection} acima.
     */
    public static void selectMob(ServerLevel level, ServerPlayer master, Mob mob) {
        UUID mobUuid = mob.getUUID();
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

        master.sendSystemMessage(Component.translatable("message.tabletoprpg.monster_selected",
                mob.getName().getString(), AURA_RADIUS));
        selectedMonsterUuid = mobUuid;
    }

    private static void moveSelectedMonster(ServerLevel level, ServerPlayer master, BlockPos dest) {
        if (selectedMonsterUuid == null) {
            return;
        }
        Entity entity = level.getEntity(selectedMonsterUuid);
        if (!(entity instanceof Mob mob)) {
            master.sendSystemMessage(Component.translatable("message.tabletoprpg.monster_gone"));
            clearSelection(level);
            RpgNetworking.sendAuraStateToAll(level.getServer());
            return;
        }

        // Valida o limite da aura (15 blocos da âncora do monstro).
        // No modo FREE o monstro é LIVRE (decisão do usuário em 22/10/2026): a
        // âncora e a aura NÃO limitam o movimento, e o destino é aceito para
        // qualquer distância. Nos outros modos o limite continua como antes.
        BlockPos anchor = monsterAnchors.get(selectedMonsterUuid);
        if (!isMonsterMovementFree()
                && anchor != null
                && !isWithinAura(anchor, dest.getX() + 0.5, dest.getZ() + 0.5)) {
            master.sendSystemMessage(Component.translatable(
                    "message.tabletoprpg.monster_destination_beyond_aura", AURA_RADIUS));
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
        // 4. A placa de pressão é o caso traiçoeiro: em Blocks.java ela é
        //    declarada com .noCollision() E .forceSolidOn() ao mesmo tempo.
        //    Sem colisão, getCollisionShape devolve forma VAZIA, então o caso 2
        //    não pega; e como forceSolidOn faz blocksMotion() responder
        //    verdadeiro, o heightmap do caso 3 CONTA a placa e devolve Y + 1,
        //    que é o bug de boiar que o usuário viu no teste de 26/09/2026.
        //    A saída é a forma de SELEÇÃO, que existe e tem o topo certo:
        //    1/16 solta, 8/16 pressionada. O teste blocksMotion() é o que
        //    separa a placa de um bloco decorativo como a grama, que também
        //    tem forma de seleção mas não é chão.
        BlockState clicked = level.getBlockState(dest);
        VoxelShape clickedShape = clicked.getCollisionShape(level, dest, CollisionContext.empty());
        double groundY;
        if (clicked.is(Blocks.LADDER)) {
            groundY = dest.getY();
        } else if (!clickedShape.isEmpty() && clickedShape.bounds().maxY > 0.0) {
            groundY = dest.getY() + clickedShape.bounds().maxY;
        } else {
            VoxelShape selectionShape = clicked.getShape(level, dest);
            groundY = clicked.blocksMotion() && !selectionShape.isEmpty() && selectionShape.bounds().maxY > 0.0
                    ? dest.getY() + selectionShape.bounds().maxY
                    : level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, dest.getX(), dest.getZ());
        }
        // Sem esta checagem o vanilla empurra o mob para fora do bloco em que
        // ele foi posto, e ele volta a subir. O mestre é avisado e o
        // movimento é recusado, para o destino continuar sendo o que ele
        // clicou em vez de um lugar acima que ninguém pediu.
        //
        // Testa a AABB que o mob teria no destino, mas recortada na COLUNA do bloco
        // clicado. A parte vertical continua valendo inteira: teto, folha, cerca, bau e
        // placa no PRÓPRIO bloco ainda recusam, que é o que esta função protege.
        //
        // <b>Mudança de 05/10/2026, pedido da jogadora:</b> antes testava a AABB
        // inteira, e um mob com mais de dois blocos de largura era recusado sempre que
        // havia um bloco, slab ou carpete na célula vizinha -- porque a AABB dele invade
        // as vizinhas por construção. O destino continua sendo o bloco clicado; o que
        // deixou de contar foram as células ao lado. A jogadora aceitou o preço disso
        // explicitamente ("o mob pode encostar/sobrepor"), então um mob largo pode
        // terminar parcialmente dentro de um bloco vizinho. `noCollision` continua
        // sendo quem respeita a borda do mundo.
        double dx = dest.getX() + 0.5 - mob.getX();
        double dy = groundY - mob.getY();
        double dz = dest.getZ() + 0.5 - mob.getZ();
        AABB alvo = mob.getBoundingBox().move(dx, dy, dz);
        // A intersecao e calculada em double antes de virar AABB, e nao depois: um
        // recorte degenerado (pegada que nem toca a coluna) sairia com min maior que
        // max, e a checagem tem de decidir por ela mesma, sem depender do que o
        // `AABB` faz com caixa invertida.
        double x0 = Math.max(alvo.minX, dest.getX());
        double x1 = Math.min(alvo.maxX, dest.getX() + 1.0);
        double z0 = Math.max(alvo.minZ, dest.getZ());
        double z1 = Math.min(alvo.maxZ, dest.getZ() + 1.0);
        // ESCADA (caso 1 do comentario acima): o mob fica com os pes no FUNDO do bloco
        // da escada, ou seja, DENTRO da coluna clicada, e a escada tem uma lamina de
        // colisao de 2px encostada na parede. Um mob largo (mais de 2 blocos) tem caixa
        // que cobre a coluna inteira, e sem esta excecao a propria lamina recusava o
        // destino ("No room above that block to place the entity."), mesmo depois da
        // mudanca de 05/10/2026 que ja ignorava as celulas vizinhas. A jogadora aceitou
        // a sobreposicao ("o mob pode encostar/sobrepor"), entao a lamina da propria
        // escada nao conta como obstaculo: excluimos o intervalo fino dela do teste,
        // mantendo o resto da coluna coberto (teto, cerca, bau e placa continuam
        // recusando).
        if (clicked.is(Blocks.LADDER) && !clickedShape.isEmpty()) {
            AABB escada = clickedShape.bounds();
            if (escada.getXsize() < 1.0) {
                if (escada.minX < 0.5) {
                    x0 = Math.max(x0, dest.getX() + escada.maxX);
                } else {
                    x1 = Math.min(x1, dest.getX() + escada.minX);
                }
            }
            if (escada.getZsize() < 1.0) {
                if (escada.minZ < 0.5) {
                    z0 = Math.max(z0, dest.getZ() + escada.maxZ);
                } else {
                    z1 = Math.min(z1, dest.getZ() + escada.minZ);
                }
            }
        }
        boolean cobreColuna = x1 > x0 && z1 > z0;
        if (cobreColuna
                && !level.noCollision(mob, new AABB(x0, alvo.minY, z0, x1, alvo.maxY, z1))) {
            master.sendSystemMessage(Component.translatable(
                    "message.tabletoprpg.monster_no_room"));
            return;
        }
        monsterDestinations.put(selectedMonsterUuid,
                new Vec3(dest.getX() + 0.5, groundY, dest.getZ() + 0.5));
        master.sendSystemMessage(Component.translatable("message.tabletoprpg.monster_moving",
                dest.getX(), dest.getY(), dest.getZ()));
    }

    private static void tickControlledMonsters(MinecraftServer server) {
        // Monstro deitado: a ficha de ameaça com HP <= 0 deita o monstro amarrado
        // a ela (22/10/2026).
        tickDownedThreatMobs(server);

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
                        //
                        // Aqui NÃO existe clamp de aura: o limite de distância da
                        // âncora nunca foi aplicado por tick, e sim na escolha do
                        // destino (moveSelectedMonster) — que, no modo FREE, nem
                        // existe (isMonsterMovementFree). O monstro sempre chega
                        // onde o Mestre mandou, dentro ou fora da aura.
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

    // ------------------------------------------------------------------
    // MONSTRO DEITADO (HP DA FICHA <= 0)
    // ------------------------------------------------------------------

    /**
     * Tag que marca o mob como deitado por causa da ficha.
     *
     * <p>A pose e a flag de IA sao gravadas no NBT do mob pelo vanilla, mas a TAG e
     * o que diz "esse deitado e NOSSO": sem ela, o mob que a mesa ja mantinha
     * congelado (peça, monstro selecionado) seria acordado por engano quando o HP
     * da ficha voltasse a positivo.
     */
    private static final String TAG_DOWNED = "tabletoprpg_downed";

    /**
     * Prefixo da tag que guarda a vida do mob antes de deitar, com o valor no
     * final (ex.: "tabletoprpg_downed_hp=18.0"). A tag do Minecraft e texto, entao
     * o valor vai junto dela.
     */
    private static final String TAG_DOWNED_HP = "tabletoprpg_downed_hp=";

    /**
     * Mobs que acabaram de levantar. O reset visual vai no tick SEGUINTE: a vida
     * (setHealth) so chega ao cliente depois do tick atual; se o reset chegasse antes,
     * o cliente ainda veria isDeadOrDying e recontaria o deathTime (mob preso deitado).
     */
    private static final Set<UUID> PENDING_DOWNED_RESET = new HashSet<>();

    /**
     * Deita (ou levanta) os monstros amarrados a uma ficha de ameaça.
     *
     * <p><b>Regra do Mestre (22/10/2026):</b> ficha com HP &lt;= 0 = monstro DEITADO,
     * e nao morto. Ele fica no mundo, deitado, sem andar, sem atacar e sem vagar.
     * Com o HP de volta a positivo, ele levanta sozinho.
     *
     * <p><b>Por que no servidor:</b> a pose e a flag de IA sao estado do servidor, e o
     * cliente nao decide nada disso. E o mesmo caminho do personagem deitado do
     * jogador ({@link DamageControlHandler}).
     *
     * <p><b>Por que so os mobs amarrados:</b> o percuso e o cache do vinculo (mob ->
     * ficha), e nao todas as entidades do mundo.
     */
    private static void tickDownedThreatMobs(MinecraftServer server) {
        // Reset pendente de quem levantou no tick anterior: a vida ja foi sincronizada.
        for (UUID pending : new ArrayList<>(PENDING_DOWNED_RESET)) {
            PENDING_DOWNED_RESET.remove(pending);
            Mob mob = ThreatSheetBinding.mobEntity(server, pending);
            if (mob == null) {
                continue;
            }
            mob.deathTime = 0;
            for (ServerPlayer watcher : PlayerLookup.tracking(mob)) {
                ServerPlayNetworking.send(watcher, new RpgNetworking.DownedResetPayload(mob.getId()));
            }
        }
        // O cache do vinculo nasce vazio depois de um reinicio do servidor: e a tag
        // do mob que sabe do vinculo. Refazemos o cache uma vez por sessao.
        ThreatSheetBinding.selfHealOnce(server);
        for (Map.Entry<UUID, String> entry : ThreatSheetBinding.boundMobs().entrySet()) {
            Mob mob = ThreatSheetBinding.mobEntity(server, entry.getKey());
            if (mob == null) {
                continue; // o mob nao existe mais
            }
            if (mob.getTags().contains(TAG_DOWNED)) {
                // Roda depois do tick vanilla (END_SERVER_TICK): o vanilla conta o
                // deathTime de um mob morto e o remove em 20 ticks. O mob deitado
                // nao morreu, entao zeramos a contagem a cada tick.
                mob.deathTime = 0;
            }
            ThreatSheet sheet = ThreatSheetStore.findAnyById(entry.getValue());
            if (sheet == null) {
                // A ficha nao esta em memoria (Mestre fora da sessao, ou ficha apagada):
                // nao mexemos no mob. Quem manda no estado dele e a ficha, e ela nao
                // esta aqui.
                continue;
            }
            if (sheet.vitals().hp() <= 0) {
                applyDowned(mob);
            } else {
                clearDowned(mob);
            }
        }
    }

    /**
     * Deita o mob: vida 0 (a queda de morte e a animacao vanilla) e sem IA.
     *
     * <p><b>Por que vida 0 e nao pose:</b> o vanilla desenha o mob morto deitado e
     * vermelho sozinho, pelo {@code deathTime}. O mob nao morre de verdade: o
     * {@link #tickDownedThreatMobs} zera o {@code deathTime} a cada tick, para o
     * vanilla nao remover o mob em 20 ticks.
     *
     * <p><b>Por que a vida e guardada:</b> ela volta quando a ficha sobe acima de 0
     * ({@link #clearDowned}). Guardamos so na primeira vez: as proximas chamadas
     * veem a vida 0 e nao podem sobrescrever a vida real.
     */
    private static void applyDowned(Mob mob) {
        if (downedHpTag(mob) == null) {
            mob.addTag(TAG_DOWNED_HP + mob.getHealth());
        }
        mob.setHealth(0.0f);
        if (!mob.isNoAi()) {
            mob.setNoAi(true);
        }
        // Zera SO o movimento horizontal: zerar o eixo Y prenderia o mob parado no
        // ar caso ele estivesse caindo.
        Vec3 motion = mob.getDeltaMovement();
        if (motion.x != 0.0 || motion.z != 0.0) {
            mob.setDeltaMovement(0.0, motion.y, 0.0);
        }
        if (!mob.getTags().contains(TAG_DOWNED)) {
            mob.addTag(TAG_DOWNED);
        }
    }

    /** Levanta o mob: so mexe nele se foi NOS que deitou. */
    private static void clearDowned(Mob mob) {
        if (!mob.getTags().contains(TAG_DOWNED)) {
            return; // nao foi nos que deitou: nao mexe na pose nem na IA dele
        }
        // Volta com a vida que tinha antes de deitar. Sem ela (ou com valor invalido),
        // usa o maximo: o mob nao pode levantar com vida 0.
        String hpTag = downedHpTag(mob);
        float hp;
        try {
            hp = hpTag == null ? 0.0f : Float.parseFloat(hpTag.substring(TAG_DOWNED_HP.length()));
        } catch (NumberFormatException e) {
            hp = 0.0f; // tag editada a mao: cai no maximo abaixo
        }
        mob.setHealth(hp > 0.0f ? hp : mob.getMaxHealth());
        if (hpTag != null) {
            mob.removeTag(hpTag);
        }
        mob.removeTag(TAG_DOWNED);
        // Zera o deathTime do servidor e agenda o reset visual para o proximo tick
        // (ver PENDING_DOWNED_RESET): o deathTime nao e sincronizado.
        mob.deathTime = 0;
        PENDING_DOWNED_RESET.add(mob.getUUID());
        if (mob.getPose() == Pose.SWIMMING) {
            mob.setPose(Pose.STANDING); // limpa o SWIMMING de versoes antigas da queda
        }
        // A IA so volta se o mob nao esta congelado por outra regra da mesa (peça,
        // monstro selecionado, ancora) nem pela Camera Tool.
        if (!isFrozenByTable(mob.getUUID())
                && !mob.getTags().contains(CameraToolManager.CAMERA_TAG)) {
            mob.setNoAi(false);
        }
    }

    /** Tag que guarda a vida antes de deitar, ou null se ainda nao foi guardada. */
    private static String downedHpTag(Mob mob) {
        for (String tag : mob.getTags()) {
            if (tag.startsWith(TAG_DOWNED_HP)) {
                return tag;
            }
        }
        return null;
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
        float pitch = (float) Math.toDegrees(Math.atan2(-dy, horizontal));
        mob.setYRot(yaw);
        mob.setXRot(pitch);
        mob.setYHeadRot(yaw);
    }

    // ------------------------------------------------------------------
    // LIMITES (AURAS)
    // ------------------------------------------------------------------

    /**
     * O monstro pode se mover sem limite de distância da âncora?
     *
     * <p><b>22/10/2026 (decisão do usuário):</b> no modo FREE (investigação
     * livre, sem combate) os monstros são LIVRES — a âncora e a aura não
     * limitam o movimento deles. Nos modos INVESTIGATION e COMBAT o limite de
     * {@value #AURA_RADIUS} blocos continua valendo, como antes.
     *
     * <p>Vale para o monstro selecionado e para os mobs inseridos (peças da
     * mesa): no modo FREE nada prende a peça da mesa ao ponto onde ela foi
     * escolhida.
     */
    public static boolean isMonsterMovementFree() {
        return SessionManager.getMode() == SessionManager.GameMode.FREE;
    }

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

    /** Mobs inseridos pelo mestre (UUID), como estão em memória. */
    public static Set<UUID> getInsertedMobs() {
        return insertedMobs;
    }

    /** Este mob foi inserido pelo mestre (/rpg insert enemy)? */
    public static boolean isInsertedMob(UUID mobUuid) {
        return mobUuid != null && insertedMobs.contains(mobUuid);
    }

    /**
     * Esquece um mob inserido: ele saiu do mundo (morto ou removido), então
     * não pode continuar na lista de inseridos nem na de câmera.
     */
    public static void forgetInsertedMob(UUID mobUuid) {
        if (mobUuid == null) {
            return;
        }
        insertedMobs.remove(mobUuid);
        cameraMobs.remove(mobUuid);
    }

    /**
     * Aplica (ou tira) a invulnerabilidade dos mobs inseridos pelo mestre.
     *
     * <p><b>Por que mexer neles:</b> o {@code /rpg insert enemy} cria o mob
     * invulnerável (é uma "peça" da mesa, não um alvo). Com a opção "Mobs podem
     * levar dano" LIGADA, essa invulnerabilidade é o que impede o mob de
     * apanhar; com a opção DESLIGADA, ela volta.
     *
     * @return quantos mobs foram alterados
     */
    public static int applyInvulnerabilityToInsertedMobs(MinecraftServer server, boolean invulnerable) {
        if (server == null) {
            return 0;
        }
        int changed = 0;
        for (UUID uuid : new ArrayList<>(insertedMobs)) {
            for (ServerLevel level : server.getAllLevels()) {
                Entity entity = level.getEntity(uuid);
                if (entity instanceof Mob mob) {
                    mob.setInvulnerable(invulnerable);
                    changed++;
                    break;
                }
            }
        }
        // Mobs inseridos antes de um restart nao estao em insertedMobs (o Set nasce vazio):
        // varre os niveis pela tag NBT para que a invulnerabilidade tambem valha para eles.
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof Mob mob && mob.getTags().contains("tabletoprpg_inserted")
                        && mob.isInvulnerable() != invulnerable) {
                    mob.setInvulnerable(invulnerable);
                    changed++;
                }
            }
        }
        return changed;
    }

    /**
     * Auto-recuperacao dos mobs com camera (marca NBT "tabletoprpg_camera"): os mobs
     * persistem no mundo, mas o Set em memoria pode ser perdido (JVM nova) ou ficar
     * incompleto (chunk ainda nao carregado quando um scan rodou).
     *
     * <p><b>Por que re-varre sempre (08/10/2026):</b> antes a varredura so rodava com o
     * Set vazio; se o primeiro scan achava so os mobs dos chunks ja carregados, o Set
     * ficava cheio e os mobs com camera em chunk carregado depois nunca entravam no
     * carrossel. A varredura roda so ao montar a lista de alvos (JOIN, troca de modo,
     * toggle de camera) -- custo irrelevante para sessao de mesa.
     *
     * <p><b>Peca vs criatura viva:</b> so entram no lookAt (insertedMobs) os mobs que
     * TAMBEM tem a tag "tabletoprpg_inserted" (insertEnemy com cam_perm=true, peca
     * congelada). Os mobs do camera tool NAO ganham essa tag por design (IA viva) e
     * ficam so no carrossel. Remocoes legitimas nao voltam: a camera tool remove a
     * tag, e os mobs removidos (discard/morto) nao estao mais no mundo.
     */
    public static void selfHealCameraMobs(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity e : level.getAllEntities()) {
                if (e instanceof Mob mob && mob.getTags().contains("tabletoprpg_camera")) {
                    cameraMobs.add(mob.getUUID());
                    // A Camera Tool congela a criatura (PARTE 3): o mob com
                    // camera para de andar, de vagar e de atacar. O
                    // congelamento e re-aplicado aqui porque a flag de IA nao
                    // sobrevive ao reinicio do servidor -- a tag sim. Sem esta
                    // linha o mob voltaria a andar depois do restart.
                    mob.setNoAi(true);
                    if (mob.getTags().contains("tabletoprpg_inserted")) {
                        insertedMobs.add(mob.getUUID());
                    }
                }
            }
        }
    }

    /**
     * Este mob precisa continuar congelado ({@code noAi=true}) mesmo sem a
     * Camera Tool?
     *
     * <p><b>Por que isto existe (PARTE 3):</b> a Camera Tool congela a
     * criatura, e ao tirar a camera o monstro volta a se mover. Mas ha mobs
     * que o {@code CombatController} segura por outros motivos, e esses nao
     * podem voltar a andar:
     *
     * <ul>
     *   <li><b>Peça da mesa</b> ({@code insertedMobs}): mob inserido pelo
     *       Mestre, que nunca age sozinho.</li>
     *   <li><b>Monstro selecionado</b> ({@code controlledMonsters}): o
     *       Mestre esta movendo ele, e o movimento sem IA depende do
     *       congelamento.</li>
     *   <li><b>Ja selecionado antes</b> ({@code monsterAnchors}): a ancora
     *       do monstro sobrevive a deselecao, e o monstro deselecionado
     *       <b>continua congelado</b> (ver {@link #clearSelection}). E o
     *       registro que sobrou para saber que ele ja foi uma peça da mesa.
     *       </li>
     * </ul>
     *
     * @return true se o mob deve continuar congelado
     */
    public static boolean isFrozenByTable(UUID mobUuid) {
        if (mobUuid == null) {
            return false;
        }
        return insertedMobs.contains(mobUuid)
                || controlledMonsters.contains(mobUuid)
                || monsterAnchors.containsKey(mobUuid);
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
        if (entity instanceof Mob mob) {
            // Descartar e limpar as listas internas é o mesmo caminho do
            // /rpg remove enemy @e[name="..."] (removeMob).
            removeMob(mob);
        } else {
            if (entity != null) {
                entity.discard();
            }
            cameraMobs.remove(selectedMonsterUuid);
            insertedMobs.remove(selectedMonsterUuid);
        }
        clearSelection(level);
    }

    /**
     * Remove um mob do RPG do mundo: descarta a entidade e apaga o mob das
     * listas internas (inseridos, câmera, controlados, destino e âncora).
     *
     * <p>É o caminho usado tanto pelo {@code /rpg remove enemy} (o mob
     * selecionado) quanto pelo {@code /rpg remove enemy @e[name="..."]} (todos
     * os mobs do RPG com aquele nome). O vínculo da ficha de ameaça também é
     * solto, para o cache não continuar apontando para um mob que saiu do
     * mundo.
     */
    public static void removeMob(Mob mob) {
        if (mob == null) {
            return;
        }
        UUID uuid = mob.getUUID();
        ThreatSheetBinding.unbind(mob);
        mob.discard();
        cameraMobs.remove(uuid);
        insertedMobs.remove(uuid);
        controlledMonsters.remove(uuid);
        monsterDestinations.remove(uuid);
        monsterAnchors.remove(uuid);
        if (uuid.equals(selectedMonsterUuid)) {
            selectedMonsterUuid = null;
        }
    }

    /**
     * Este mob é uma peça da mesa do RPG (invocado pelo Mestre, marcado pela
     * câmera ou controlado por ele)?
     *
     * <p>É o filtro que o {@code /rpg remove enemy @e[name="..."]} usa: só
     * mobs do RPG são removidos, nunca uma criatura natural do mundo que
     * por acaso tenha o mesmo nome.
     *
     * <p>A marcação é conferida em memória (listas do servidor) e também nas
     * tags NBT ("tabletoprpg_inserted" / "tabletoprpg_camera"), que
     * sobrevivem ao reinício do servidor.
     */
    public static boolean isRpgMob(Mob mob) {
        if (mob == null) {
            return false;
        }
        UUID uuid = mob.getUUID();
        if (insertedMobs.contains(uuid) || cameraMobs.contains(uuid) || controlledMonsters.contains(uuid)) {
            return true;
        }
        Set<String> tags = mob.getTags();
        return tags.contains("tabletoprpg_inserted") || tags.contains(CameraToolManager.CAMERA_TAG);
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
