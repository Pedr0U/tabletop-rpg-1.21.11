package com.pedro.tabletoprpg;

import com.pedro.tabletoprpg.item.ModItems;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Trava portas, baus e qualquer bloco de inventario (barris, fornalhas, baus de
 * mod) para que os <b>jogadores</b> nao possam mais interagir com eles. O mestre
 * nunca e bloqueado.
 *
 * <p><b>Duas formas de trancar, mesmo resultado:</b>
 * <ol>
 *   <li>o comando {@code /rpg block_lock} (ou {@code /rpg block_lock remove})
 *       arma o pedido no chat e o <b>proximo clique do mestre num bloco</b> e
 *       o que aplica;</li>
 *   <li>o item {@code Block Locker} ({@link ModItems#blockLock()}): clique direto
 *       num bloco <b>alterna</b> entre trancar e destrancar.</li>
 * </ol>
 *
 * <p><b>Por que o pedido e por comando, e nao por alvo olhado:</b> o mestre
 * precisa de uma confirmacao visivel de <i>qual</i> bloco vai ser trancado
 * antes de a acao valer. A troca comando-clique e o mesmo padrao que o
 * {@code /rpg remove enemy} ja usa neste projeto.
 *
 * <p><b>Por que o clique e consumido (nao abri o bau):</b> trancar e o que o
 * mestre pediu; abrir o bau no mesmo clique seria um efeito colateral
 * indesejado. Por isso o caminho do item devolve {@link InteractionResult#SUCCESS}
 * (que consome a acao, cancelando o {@code use} do bloco) em vez de
 * {@code PASS}.
 *
 * <p><b>Por que a implementacao do item esta aqui e nao no {@code Item}:</b>
 * o uso do item ({@code Item.useOn}, disparado pelo {@code UseItemCallback}) roda
 * <b>depois</b> do {@code useWithoutItem} do bloco
 * (porta, bau), ou seja o bau ja teria aberto. O
 * {@link UseBlockCallback} roda <b>antes</b> de o bloco interagir, que e o
 * unico ponto em que da para travar o clique.
 */
public final class BlockLockManager {

    /**
     * O que o proximo clique do mestre num bloco deve fazer.
     *
     * <p>Um {@link java.util.EnumSet} e nao um {@code boolean}, porque
     * trancar e destrancar sao acoes distintas e o erro de inverter as duas e o
     * tipo de defeito que so se ve em jogo.
     */
    public enum PendingAction {
        /** O mestre mandou travar. */
        LOCK,
        /** O mestre mandou destrancar. */
        UNLOCK
    }

    /**
     * Pedido armado por comando, por mestre. Um so: nao existe um segundo
     * Mestre na sessao ({@code SessionManager.setMaster} recusa duplicidade), e
     * um mapa por UUID continua correto se o cargo passar de mao.
     */
    private static final Map<UUID, PendingAction> pendingActions = new ConcurrentHashMap<>();

    /**
     * Quando foi o ultimo clique num bloco que este gerenciador respondeu.
     *
     * <p><b>Para que serve:</b> um clique do Mestre num bloco sem interacao
     * gera <b>duas</b> mensagens. A primeira vem daqui ("that block has no
     * interaction to lock"), e o cliente, que decide pela predicao local e nao
     * pelo retorno do servidor, ainda envia o pacote de uso de item, o que
     * dispara a dica "right-click a block" do {@code ModItems.onUseItem}. Os
     * dois pacotes chegam no mesmo tick, entao a diferenca de tempo separa os
     * casos: bloco (esta classe respondeu, dica e' omitida) de ar (so a dica).
     */
    private static volatile long lastBlockHandledGameTime = Long.MIN_VALUE;

    private BlockLockManager() {
    }

    public static void register() {
        UseBlockCallback.EVENT.register(BlockLockManager::onUseBlock);

        // O pedido e de um clique so. Ele e consumido pelo proximo clique do
        // mestre num bloco (sucesso **ou** recusa), pelo uso do item
        // (`clearPending`) e pela desconexao.
        //
        // <b>Sem prazo:</b> uma versao anterior deste comentario prometia um TTL
        // que o codigo nao tinha. Se o Mestre armar e depois ficar 20 minutos
        // no chat, o proximo bau que ele tocar e trancado -- por isso a
        // mensagem de armacao fica no chat e a de sucesso nomeia a acao, para
        // que ele veja e destranque com o item. Um TTL e uma decisao de produto
        // (nao foi pedida); se for desejado, e um timestamp no mapa, lido em
        // `onUseBlock`, sem custo por tick.

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                pendingActions.remove(handler.player.getUUID()));

        // Join: o cliente precisa do retrato antes de qualquer clique, e o
        // `isMaster` deste jogador. Sem isto, um Mestre que entrasse depois de
        // as trancas existirem so veria a aura depois da proxima mudanca.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                sendSnapshotTo(handler.player));

        // Regra do usuario em 30/09/2026: bloco trancado e indestrutivel para
        // jogador; so o Mestre quebra (e a quebra dele remove a tranca).
        //
        // Tudo num unico listener de BEFORE, e a limpeza no mesmo listener,
        // porque o `PlayerBlockBreakEvents.AFTER` **so e disparado se nenhum
        // listener de BEFORE cancelar** (confirmado no javadoc do Fabric API) e
        // o `PlayerControlHandler` cancela quando o jogador nao pode quebrar.
        // Com AFTER, um bloco quebrado pelo Mestre ficaria com tranca orfa, e o
        // bloco recolocado na mesma posicao nasceria trancado sem ninguem ter
        // pedido: foi exatamente o defeito relatado pelo usuario em 30/09/2026.
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
            if (!(level instanceof ServerLevel)) {
                return true;
            }
            BlockLockStore store = storeOf(level);
            if (!store.isLocked(level.dimension(), pos)) {
                return true;
            }
            if (player instanceof ServerPlayer breaker && SessionManager.isMaster(breaker)) {
                // Mestre quebra: a tranca some junto, porque o bloco deixa de
                // existir. Sem isto, o proximo bloco colocado aqui nasceria
                // trancado.
                dropLock(level, pos);
                broadcastSnapshot(level.getServer());
                return true;
            }
            // Jogador: recusa e a tranca permanece.
            if (player instanceof ServerPlayer breaker) {
                breaker.sendSystemMessage(
                        Component.translatable("message.tabletop-rpg.block_locked_cannot_break"));
            }
            return false;
        });
    }

    /**
     * Registra a intencao do comando. Proximo clique do mestre aplica.
     *
     * <p>Sobrepoe qualquer pedido anterior do mesmo mestre: o comando mais
     * recente e o que vale.
     */
    public static void arm(ServerPlayer master, PendingAction action) {
        pendingActions.put(master.getUUID(), action);
    }

    /** Cancela o pedido pendente (usado quando o item e usado no lugar). */
    public static void clearPending(ServerPlayer master) {
        pendingActions.remove(master.getUUID());
    }

    /**
     * O clique que acabo de chegar era num bloco e ja teve resposta?
     *
     * <p>Chamado pelo {@code ModItems} para nao repetir a dica "clique num
     * bloco" depois que esta classe ja respondeu ao mesmo clique.
     */
    public static boolean justHandledBlockClick(Level level) {
        return lastBlockHandledGameTime != Long.MIN_VALUE
                && level.getGameTime() - lastBlockHandledGameTime <= 1;
    }

    /**
     * Ponto unico de decisao do clique com o botao direito num bloco.
     *
     * <p>Ordem de checagem (importante):
     * <ol>
     *   <li><b>Mestre com o item</b> ou <b>com pedido armado</b>: resolve
     *       (tranca/destrava) e consome o clique.</li>
     *   <li><b>Jogador em bloco trancado</b>: recusa com mensagem.</li>
     *   <li>Demais: {@code PASS}, o jogo segue normal.</li>
     * </ol>
     *
     * <p><b>Por que a recusa vem antes do {@code PASS} do
     * {@link PlayerControlHandler}:</b> sao callbacks distintos, e ambos
     * consultam este metodo. A recusa por tranca e independente do turno -- vale
     * mesmo com o jogador sendo o do turno, porque a tranca e uma regra do
     * Mestre, nao uma consequencia da ordem de jogada.
     */
    private static InteractionResult onUseBlock(Player player,
                                                 Level level,
                                                 InteractionHand hand,
                                                 BlockHitResult hitResult) {
        // So o servidor decide. No cliente o jogador nunca e um ServerPlayer, e
        // devolver FAIL la cancelaria o pacote antes de ele chegar.
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }
        // Guarda de posicao: o callback tambem roda com hitResult de ar (quando o
        // jogador clica em nada, o hit e MISS e o BlockHitResult vem nulo).
        if (hitResult == null || hitResult.getType() != HitResult.Type.BLOCK) {
            return InteractionResult.PASS;
        }

        BlockPos pos = hitResult.getBlockPos();
        ItemStack held = player.getItemInHand(hand);
        boolean isMaster = SessionManager.isMaster(serverPlayer);
        boolean hasItem = ModItems.isBlockLock(held);
        lastBlockHandledGameTime = level.getGameTime();

        // Porta e bloco de 2 alturas: `pos` pode ser a metade de baixo ou a de
        // cima, e sao DUAS posicoes distintas no mundo. Verificar so `pos`
        // deixava a metade de cima destrancada, e clicar nela abria a porta
        // (foi o defeito reportado pelo usuario em 30/09/2026). Por isso toda
        // verificacao de "esta trancado" abaixo e feita no CONJUNTO de posicoes
        // que formam o mesmo bloco logico.
        List<BlockPos> parts = blockParts(level, pos);

        // 2. Nao-Mestre com o item Block Locker na mao: recusa.
        if (hasItem && !isMaster) {
            // O item nao e um atalho: e a ferramenta do Mestre. Sem esta
            // checagem, um jogador com o Block Locker na mao que clicasse num
            // bau abriria o bau e receberia silencio, sem entender que o
            // item e recusado (a negacao do `onUseItem` so aparece no clique no
            // ar, porque num clique em bloco o handler deste metodo consome o
            // pacote primeiro).
            clearPending(serverPlayer);
            serverPlayer.displayClientMessage(
                    Component.translatable("item.tabletop-rpg.block_lock.denied"), false);
            return InteractionResult.FAIL;
        }

        // 3. Mestre com o item Block Locker: alterna a tranca.
        if (isMaster && hasItem) {
            clearPending(serverPlayer);
            toggle(serverPlayer, level, pos, parts);
            // SUCCESS (e nao PASS) porque o clique precisa ser consumido: com
            // PASS o bloco abriria logo depois daqui.
            return InteractionResult.SUCCESS;
        }

        // 4. Mestre com pedido armado pelo comando.
        PendingAction action = isMaster ? pendingActions.remove(serverPlayer.getUUID()) : null;
        if (action != null) {
            apply(serverPlayer, level, pos, action, parts);
            return InteractionResult.SUCCESS;
        }

        // 5. Jogador (ou mestre sem item e sem pedido) em bloco trancado.
        //
        // `anyPartLocked` e nao `isLocked(level, pos)`: clicar na metade de
        // cima de uma porta trancada tem de falhar igual clicar na de baixo.
        if (!isMaster && anyPartLocked(level, parts)) {
            serverPlayer.displayClientMessage(
                    Component.translatable("message.tabletop-rpg.block_locked"), true);
            return InteractionResult.FAIL;
        }

        return InteractionResult.PASS;
    }

    /**
     * Re-sincroniza o bloco com o cliente depois de aplicar a tranca.
     *
     * <p>Por que e preciso: o cliente roda o {@code UseBlockCallback} tambem
     * (via {@code MultiPlayerGameModeMixin}), mas nao pode devolver nada
     * diferente de {@code PASS} -- ver o guard em {@link #onUseBlock}. Com
     * {@code PASS}, o cliente segue a predicao local e <b>abre a porta na
     * tela do Mestre</b> enquanto o servidor cancelou o {@code use}. O cliente
     * fica com o estado errado ate alguma atualizacao daquele bloco chegar.
     *
     * <p>Enviar a atualizacao resolve: o cliente sobrescreve o estado previsto
     * pelo estado autoritativo do servidor, que continua sendo "fechada". Sem
     * isso o Mestre abriria a porta e ela simplesmente nao abriria.
     */
    private static void resyncToClient(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        BlockState state = serverLevel.getBlockState(pos);
        serverLevel.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
    }

    /**
     * Envia a atualizacao de todas as partes de um bloco logico, e o retrato de
     * trancas para todo mundo.
     *
     * <p>O retrato vai <b>aqui</b> e nao dentro de {@code lock}/{@code unlock}
     * porque este e o ponto unico por onde passa toda mudanca de tranca feita
     * pela interface (travar, destrancar). A excecao e a quebra do bloco pelo
     * Mestre, que nao passa aqui e chama {@code broadcastSnapshot} direto.
     */
    private static void resyncToClient(Level level, List<BlockPos> parts) {
        for (BlockPos part : parts) {
            resyncToClient(level, part);
        }
        broadcastSnapshot(level.getServer());
    }

    // --- estado para o cliente (a aura do Mestre) ---

    /**
     * Manda o retrato de trancas para <b>um</b> jogador.
     *
     * <p>Enviada no join, para o cliente ja nascer com o estado certo sem
     * depender de nenhuma tranca mudar depois, e a cada mudanca. O
     * {@code isMaster} e deste jogador: e o que impede um jogador comum de ver
     * a aura, porque o cliente so desenha quando a flag e verdadeira.
     */
    public static void sendSnapshotTo(ServerPlayer player) {
        List<RpgNetworking.BlockLockEntry> entries = storeOf(player.level()).snapshot().stream()
                .map(locked -> new RpgNetworking.BlockLockEntry(
                        locked.dimension().identifier().toString(), locked.pos()))
                .toList();
        ServerPlayNetworking.send(player,
                new RpgNetworking.BlockLocksPayload(SessionManager.isMaster(player), entries));
    }

    /** Reenvia o retrato para todo mundo online. */
    private static void broadcastSnapshot(MinecraftServer server) {
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            sendSnapshotTo(online);
        }
    }

    /**
     * Item: alterna. Trancado -> destranca; destrancado -> tranca.
     *
     * <p>O estado lido e o do conjunto: uma porta trancada nas duas metades
     * destrava com um clique em qualquer uma delas.
     */
    private static void toggle(ServerPlayer master, Level level, BlockPos pos, List<BlockPos> parts) {
        if (anyPartLocked(level, parts)) {
            unlock(master, level, pos, parts);
        } else {
            lock(master, level, pos, parts);
        }
    }

    private static void lock(ServerPlayer master, Level level, BlockPos pos, List<BlockPos> parts) {
        // So blocos com interacao fazem sentido trancados: travar um bloco sem
        // uso nao muda nada para o jogador e so ocupa espaco no arquivo. A
        // recusa e explicita (decisao do usuario em 30/09/2026) para que o
        // Mestre nao ache que o comando quebrou.
        if (!isInteractable(level, pos)) {
            master.sendSystemMessage(Component.translatable("message.tabletop-rpg.block_not_interactable"));
            return;
        }
        // Trava TODAS as partes: uma porta e um bloco so, ocupa duas posicoes.
        // Travar so a clicada deixava a outra clicavel (defeito de 30/09/2026).
        BlockLockStore store = storeOf(level);
        boolean mudou = false;
        for (BlockPos part : parts) {
            if (store.lock(level.dimension(), part)) {
                mudou = true;
            }
        }
        if (mudou) {
            master.sendSystemMessage(Component.translatable("message.tabletop-rpg.block_locked_by_master"));
            resyncToClient(level, parts);
        } else {
            master.sendSystemMessage(Component.translatable("message.tabletop-rpg.block_already_locked"));
        }
    }

    private static void unlock(ServerPlayer master, Level level, BlockPos pos, List<BlockPos> parts) {
        boolean destravou = dropLocksOf(level, parts);
        if (destravou) {
            master.sendSystemMessage(Component.translatable("message.tabletop-rpg.block_unlocked_by_master"));
            resyncToClient(level, parts);
        } else {
            master.sendSystemMessage(Component.translatable("message.tabletop-rpg.block_not_locked"));
        }
    }

    /**
     * Comando armado: executa a acao pedida, <b>sem</b> alternar.
     *
     * <p>Por que o comando e o item se comportam diferente no bloco ja no estado
     * desejado: o comando e uma ordem ("tranque este"), e uma ordem repetida
     * sobre algo ja feito tem de dizer "ja estava trancado" em vez de
     * destrancar por acidente. O item e uma chave (alterna), que e o que o
     * Mestre espera de um item na mao. O usuario pediu exatamente esses dois
     * comportamentos.
     */
    private static void apply(ServerPlayer master, Level level, BlockPos pos,
                              PendingAction action, List<BlockPos> parts) {
        if (action == PendingAction.LOCK) {
            lock(master, level, pos, parts);
        } else {
            unlock(master, level, pos, parts);
        }
    }

    // --- partes de um bloco logico (porta) ---

    /**
     * As posicoes que formam o mesmo bloco logico que {@code pos}.
     *
     * <p><b>Por que isso importa (defeito real de 30/09/2026):</b> a porta
     * ocupa DUAS posicoes no mundo, marcadas por {@code DOUBLE_BLOCK_HALF}. O
     * Mestre trava a metade de baixo e o jogador clica na de cima: como sao
     * posicoes diferentes, a de cima nao estava na lista e a porta abria. Isso
     * chegou como "abre a porta mesmo trancada" e como "abre de longe ou de um
     * nivel abaixo", que nao era alcance: era a outra metade do bloco.
     *
     * <p>Usa a propriedade do <b>proprio estado</b>, e nao a classe do bloco: um
     * bloco de mod com a mesma propriedade e tratado igual, e um bloco de uma
     * posicao so devolve a propria lista.
     *
     * <p><b>So {@code DOUBLE_BLOCK_HALF}, nunca {@code HALF} (verificado com
     * {@code javap} no jar nomeado do 1.21.11):</b>
     * <ul>
     *   <li>{@code DoubleBlockHalf} tem <b>apenas</b> {@code UPPER} e
     *       {@code LOWER} -- nao existe valor "unico", entao a distincao entre
     *       bloco de uma posicao e de duas e sempre explicita.</li>
     *   <li>{@code HALF} existe, mas em slab, escada e armadilha ele significa
     *       <b>formato</b> (metade de cima/baixo do <i>mesmo</i> bloco), nao uma
     *       posicao vizinha. Uma versao anterior desta metodo usava
     *       {@code HALF} e, por isso, trancar uma escada trancava tambem o bloco
     *       de ar acima dela.</li>
     *   <li>A cama usa {@code BED_PART} ({@code BedPart}), e nao {@code HALF}.
     *       Ela nao entra aqui: e nao passa em {@code isInteractable}, porque nao
     *       declara interacao de bloco nem abre menu, entao nunca chega a ser
     *       trancada.</li>
     * </ul>
     */
    private static List<BlockPos> blockParts(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            return List.of(pos);
        }
        BlockPos other = state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER
                ? pos.below()
                : pos.above();
        // Inclui as duas, na ordem clicada primeiro: a lista serve tanto para
        // "alguma esta trancada" quanto para "travar/destravar todas".
        return List.of(pos, other);
    }

    /** Alguma parte do bloco logico esta trancada? */
    private static boolean anyPartLocked(Level level, List<BlockPos> parts) {
        BlockLockStore store = storeOf(level);
        for (BlockPos part : parts) {
            if (store.isLocked(level.dimension(), part)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Remove a tranca de todas as partes. Devolve {@code true} se removeu
     * alguma.
     *
     * <p>Existe para os dois chamadores que precisam do mesmo comportamento:
     * o destravamento manual e a quebra do bloco pelo Mestre.
     */
    private static boolean dropLocksOf(Level level, List<BlockPos> parts) {
        BlockLockStore store = storeOf(level);
        boolean removeu = false;
        for (BlockPos part : parts) {
            if (store.unlock(level.dimension(), part)) {
                removeu = true;
            }
        }
        return removeu;
    }

    /** Remove a tranca da posicao e da sua parte contraparia. */
    private static boolean dropLock(Level level, BlockPos pos) {
        return dropLocksOf(level, blockParts(level, pos));
    }

    // --- acesso ao dado gravado ---

    private static BlockLockStore storeOf(Level level) {
        // O dado mora no overworld (ver BlockLockStore), entao o nivel atual
        // so e usado para descobrir o servidor.
        ServerLevel serverLevel = (ServerLevel) level;
        return BlockLockStore.get(serverLevel.getServer());
    }

    /** O bloco da posicao esta trancado nesta dimensao? */
    public static boolean isLocked(Level level, BlockPos pos) {
        // Guarda de dimensao: este metodo e publico e o `Level` pode ser um
        // ClientLevel (chamado por outro mod ou por um client mixin futuro),
        // e o dado gravado so existe no servidor.
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        return BlockLockStore.get(serverLevel.getServer()).isLocked(level.dimension(), pos);
    }

    // --- o que conta como "bloco trancavel" ---

    /**
     * O bloco da posicao tem interacao? (bau, barril, fornalha, porta,
     * alavanca, inventario de mod).
     *
     * <p><b>Por que nao uma tag do vanilla:</b> verificado com {@code javap} no
     * jar nomeado do 1.21.11, {@code net.minecraft.tags.BlockTags} <b>nao tem</b>
     * {@code OPENABLE}, {@code CONTAINERS}, {@code SHOPIERS} nem {@code LEVERS}
     * (o enum de tag mudou de pacote e o conjunto util sumiu). Um gate por tag
     * deixaria de fora justamente os blocos de mod, que sao metade do motivo da
     * feature.
     *
     * <p><b>Por que dois criterios:</b> {@code getMenuProvider} cobre quem abre
     * menu (bau, barril, fornalha, e todo inventario de mod), e a sobrescrita de
     * {@code useWithoutItem}/{@code useItemOn} cobre quem age sem menu (porta,
     * alavanca, botao). Qualquer um dos dois ja basta.
     *
     * <p><b>Falsos positivos:</b> o criterio e "o bloco reescreve a
     * interacao", nao "o bloco tem conteudo a proteger". Blocos como a ancora
     * de respawn (que so interage carregada) e a camada de neve caem aqui e
     * podem ser trancados sem efeito visivel para o jogador. Isso e aceito de
     * proposito: trancar um bloco inofensivo nao prejudica ninguem, e errar para
     * o outro lado (recusar um bau de mod) seria pior para quem usa a feature.
     */
    private static boolean isInteractable(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        // getMenuProvider e publico em BlockStateBase nesta versao (BlockState
        // so expoe quatro membros) e nao tem efeito colateral: apenas devolve o
        // MenuProvider, sem abrir nada.
        if (state.getMenuProvider(level, pos) != null) {
            return true;
        }
        return overridesInteraction(state.getBlock());
    }

    /**
     * Cache classe -> o bloco sobrescreve a interacao do {@code BlockBehaviour}?
     *
     * <p>A reflexao e feita uma vez por classe de bloco (nao por clique): sao
     * poucas centenas de classes no jogo e o cache e estatico, entao nao ha
     * custo por jogador.
     */
    private static final Map<Class<?>, Boolean> INTERACTION_OVERRIDE_CACHE = new ConcurrentHashMap<>();

    private static boolean overridesInteraction(Block block) {
        return INTERACTION_OVERRIDE_CACHE.computeIfAbsent(block.getClass(), cls ->
                overridesUseWithoutItem(cls) || overridesUseItemOn(cls));
    }

    /** Assinatura de {@code useWithoutItem}: (estado, nivel, pos, jogador, acerto). */
    private static final Class<?>[] USE_WITHOUT_ITEM_PARAMS = {
            BlockState.class, Level.class, BlockPos.class, Player.class, BlockHitResult.class};

    /** Assinatura de {@code useItemOn}: a anterior mais o item e a mao. */
    private static final Class<?>[] USE_ITEM_ON_PARAMS = {
            ItemStack.class, BlockState.class, Level.class, BlockPos.class, Player.class,
            InteractionHand.class, BlockHitResult.class};

    /**
     * A classe declara {@code useWithoutItem} (porta, alavanca, botao) ou o
     * equivalente antigo {@code use}?
     *
     * <p><b>Por que assinatura e nao nome (correcao de 30/09/2026):</b> esta
     * checagem usava {@code getDeclaredMethod("useWithoutItem", ...)} e, em
     * teste em jogo, recusou uma porta com "esse bloco nao tem interacao para
     * travar". Nao reproduzi a recusa em teste automatico, mas a checagem por
     * nome tem um defeito estrutural, independente do caso: o nome do metodo e
     * um <b>literal de string</b>, e literais nao passam pelo remapeamento que o
     * carregador aplica as referencias de classe do Minecraft (nomeado ->
     * intermediario). Uma referencia como {@code BlockState.class} e reescrita; a
     * string {@code "useWithoutItem"} continua igual, o {@code
     * NoSuchMethodException} engole a sobrescrita em silencio, e o resultado fica
     * preso no cache, que nunca mais e reavaliado. Comparar so <b>tipos de
     * parametro</b> usa apenas referencias de classe, que sao remapeadas de
     * forma consistente, e assim a checagem vale nas duas situacoes de
     * mapeamento.
     */
    private static boolean overridesUseWithoutItem(Class<?> cls) {
        Class<?> declaring = declaringClassOf(cls, USE_WITHOUT_ITEM_PARAMS);
        return declaring != null && declaring != BlockBehaviour.class;
    }

    /** A classe declara {@code useItemOn} (bau, barril, fornalha, inventarios). */
    private static boolean overridesUseItemOn(Class<?> cls) {
        Class<?> declaring = declaringClassOf(cls, USE_ITEM_ON_PARAMS);
        return declaring != null && declaring != BlockBehaviour.class;
    }

    /**
     * Qual classe <b>primeiro</b> na hierarquia declara um metodo com esta
     * assinatura? Devolve {@code null} se nenhuma.
     *
     * <p>Sobe ate {@code BlockBehaviour}: e ela que diz se o bloco reescreve a
     * interacao ou so herdou o comportamento padrao.
     *
     * <p><b>Por que comparar assinatura basta:</b> o que interessa e se o
     * bloco <i>reescreveu</i> a interacao, e nao o nome do metodo. Um achado e
     * qualquer metodo declarado <b>neste</b> nivel com estes parametros, porque
     * o que o {@code BlockBehaviour} declara e justamente o negativo que se quer
     * detectar.
     *
     * <p><b>Limite conhecido:</b> um bloco cuja interacao venha de um
     * <i>mixin</i> injetado no {@code BlockBehaviour} nao aparece aqui, porque
     * mixin nao muda a classe que declara o metodo. O resultado e apenas
     * perder um caso de falso negativo, nunca travar um bloco valido.
     */
    private static Class<?> declaringClassOf(Class<?> start, Class<?>[] params) {
        for (Class<?> current = start; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (Arrays.equals(method.getParameterTypes(), params)) {
                    return current;
                }
            }
        }
        return null;
    }
}