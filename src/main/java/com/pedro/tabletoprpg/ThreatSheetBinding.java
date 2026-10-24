package com.pedro.tabletoprpg;

import com.pedro.tabletoprpg.item.ModItems;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Qual ficha de ameaca esta amarrada a qual mob. 02/10/2026.
 *
 * <p><b>Por que fica na tag do mob e nao no NBT do jogador:</b> a ficha e do Mestre e
 * esta no NBT dele, mas o vinculo pertence ao MOB: ele continua valendo se outro
 * jogador entrar, se o Mestre trocar de mundo e se o servidor reiniciar. E o proprio
 * {@code insertEnemy} ja marca os mobs inseridos com {@code addTag}, entao este
 * arquivo segue o mesmo caminho em vez de inventar armazenamento novo.
 *
 * <p><b>Por que dois mapas em memoria:</b> as perguntas sao nas duas direcoes -- "qual
 * mob e desta ficha" (iniciativa) e "qual ficha e deste mob" (item na mao) -- e varrer as
 * entidades a cada pergunta seria caro. Os mapas sao cache, nunca a fonte da verdade: a
 * verdade esta na tag, e {@link #selfHeal} refaz o cache varrendo as entidades carregadas,
 * como o {@code CombatController} ja faz com os mobs de camera.
 *
 * <p><b>Uma ficha, no maximo, um mob:</b> vincular substitui, decide do Mestre em
 * 02/10/2026. Se a ficha ja estava em outro mob, a tag sai de la -- senao dois mobs
 * responderiam pelo mesmo nome em comando.
 *
 * <p><b>Modo Horda (22/10/2026):</b> com {@code hordeMode} ligado, a MESMA ficha
 * pode valer para varios mobs ao mesmo tempo (1:N). A tag do mob ja permitia isso --
 * o que era 1:1 era so o cache e o "roubo" do {@link #bind}. No modo horda o roubo
 * nao acontece: o mob novo entra na ficha e os outros ficam onde estavam. Fora do
 * modo horda, a regra antiga continua valendo (uma ficha, no maximo, um mob).
 */
public final class ThreatSheetBinding {

    /** Prefixo da tag no NBT do mob. O sufixo e o id da ficha. */
    public static final String TAG_PREFIX = "tabletoprpg_sheet_";

    /** Alcance do ray trace do Mestre, em blocos. */
    private static final double MAX_REACH = 5.0;

    private static final Map<UUID, String> BY_MOB = new ConcurrentHashMap<>();
    /** Ficha -&gt; mobs. No modo horda a ficha tem mais de um mob. */
    private static final Map<String, Set<UUID>> BY_SHEET = new ConcurrentHashMap<>();

    /** O cache ja foi refeito pelo menos uma vez nesta sessao? */
    private static boolean healed = false;

    private ThreatSheetBinding() {
    }

    /** A tag que guarda o vinculo. */
    public static String tagOf(String sheetId) {
        return TAG_PREFIX + (sheetId == null ? "" : sheetId);
    }

    /** Amarra a ficha no mob, trocando o vinculo anterior. */
    public static void bind(ServerLevel level, Mob mob, ThreatSheet sheet) {
        if (mob == null || sheet == null || !sheet.hasId()) {
            return;
        }
        String previous = BY_MOB.get(mob.getUUID());
        if (previous != null && !previous.equals(sheet.id())) {
            mob.removeTag(tagOf(previous));
            // Sobrescrever (regra do Mestre, 07/10/2026): a ficha antiga fica LIVRE,
            // e o cache acompanha -- sem isso o mobOf da ficha antiga continuaria
            // devolvendo este mob, como se ela ainda estivesse amarrada.
            forgetMob(previous, mob.getUUID());
        }

        // A ficha ja estava em outro mob? Ele solta a tag, para o nome nao apontar duas
        // criaturas ao mesmo tempo.
        //
        // Modo Horda (22/10/2026): NAO e roubo. A ficha pode valer para varios mobs,
        // e o mob que ja estava amarrado continua amarrado.
        if (!sheet.hordeMode()) {
            Set<UUID> others = BY_SHEET.get(sheet.id());
            if (others != null) {
                for (UUID otherMob : List.copyOf(others)) {
                    if (otherMob.equals(mob.getUUID())) {
                        continue;
                    }
                    // Roubo (regra do Mestre, 07/10/2026): a ficha sai do mob antigo, que fica
                    // SEM ficha nenhuma. A tag sai e o cache acompanha -- senao o sheetOf do mob
                    // antigo continuaria devolvendo a ficha roubada.
                    BY_MOB.remove(otherMob);
                    forgetMob(sheet.id(), otherMob);
                    if (level != null) {
                        Entity previousMob = level.getEntity(otherMob);
                        if (previousMob != null) {
                            previousMob.removeTag(tagOf(sheet.id()));
                            // Feedback da jogadora (07/10/2026): sem isto o mob antigo
                            // CONTINUA com o nome de exibicao flutuando, e ficam dois mobs
                            // com o mesmo nome. O nome customizado so existe por causa do
                            // vinculo, entao o roubo o remove inteiro (o mob volta ao nome
                            // do vanilla).
                            previousMob.setCustomName(null);
                            previousMob.setCustomNameVisible(false);
                        }
                    }
                }
            }
        }

        String tag = tagOf(sheet.id());
        if (!mob.getTags().contains(tag)) {
            mob.addTag(tag);
        }
        BY_MOB.put(mob.getUUID(), sheet.id());
        mobs(sheet.id()).add(mob.getUUID());
        applyDisplayName(mob, sheet);
    }

    /** O conjunto de mobs de uma ficha no cache (criado se ainda nao existir). */
    private static Set<UUID> mobs(String sheetId) {
        return BY_SHEET.computeIfAbsent(sheetId, key -> ConcurrentHashMap.newKeySet());
    }

    /** Tira um mob do conjunto de mobs de uma ficha. */
    private static void forgetMob(String sheetId, UUID mobUuid) {
        Set<UUID> set = BY_SHEET.get(sheetId);
        if (set != null) {
            set.remove(mobUuid);
            if (set.isEmpty()) {
                BY_SHEET.remove(sheetId, set);
            }
        }
    }

    /**
     * Escreve (ou esconde) o nome da ameaca no mob.
     *
     * <p><b>Por que o nome e gravado mesmo com a caixa desmarcada:</b> e o nome customizado
     * que o vanilla usa em {@code @e[name="..."]}, e e assim que o Mestre aponta a
     * ameaca em {@code /tp} e nos outros comandos. Desmarcar a caixa nao apaga o nome:
     * so deixa de mostrar ele flutuando. Decisao do Mestre em 02/10/2026.
     */
    public static void applyDisplayName(Mob mob, ThreatSheet sheet) {
        if (mob == null || sheet == null) {
            return;
        }
        String name = sheet.identity().displayName();
        if (name.isBlank()) {
            return;
        }
        mob.setCustomName(Component.literal(name));
        TabletopRpg.LOGGER.info(
                "[TabletopRPG] applyDisplayName: ficha '{}' -> nome '{}' no mob {} (visivel={}).",
                sheet.identity().name(), name,
                EntityTargets.describe(mob, mob), sheet.identity().showDisplayName());
        mob.setCustomNameVisible(sheet.identity().showDisplayName());
    }

    /** Solta o vinculo do mob. Nada acontece se ele nao estava amarrado. */
    public static void unbind(Mob mob) {
        if (mob == null) {
            return;
        }
        String sheetId = BY_MOB.remove(mob.getUUID());
        if (sheetId != null) {
            forgetMob(sheetId, mob.getUUID());
        }
        // Varre as tags do proprio mob: cobre o caso em que o cache foi perdido
        // (reinicio do servidor) e o vinculo so existe na tag.
        for (String tag : mob.getTags()) {
            if (tag.startsWith(TAG_PREFIX)) {
                forgetMob(tag.substring(TAG_PREFIX.length()), mob.getUUID());
            }
        }
    }

    /** O id da ficha amarrada a este mob, ou {@code ""}. */
    public static String sheetOf(Mob mob) {
        if (mob == null) {
            return "";
        }
        String cached = BY_MOB.get(mob.getUUID());
        if (cached != null) {
            return cached;
        }
        for (String tag : mob.getTags()) {
            if (tag.startsWith(TAG_PREFIX)) {
                String id = tag.substring(TAG_PREFIX.length());
                if (!id.isEmpty()) {
                    BY_MOB.put(mob.getUUID(), id);
                    return id;
                }
            }
        }
        return "";
    }

    /** O mob amarrado a esta ficha, ou {@code null}. */
    public static UUID mobOf(MinecraftServer server, String sheetId) {
        if (sheetId == null || sheetId.isEmpty()) {
            return null;
        }
        Set<UUID> cached = BY_SHEET.get(sheetId);
        if (cached != null) {
            for (UUID uuid : cached) {
                if (isAlive(server, uuid)) {
                    return uuid;
                }
            }
        }
        selfHeal(server);
        Set<UUID> healed = BY_SHEET.get(sheetId);
        if (healed == null) {
            return null;
        }
        for (UUID uuid : healed) {
            if (isAlive(server, uuid)) {
                return uuid;
            }
        }
        return null;
    }

    /**
     * Todos os mobs amarrados a esta ficha (1:N no Modo Horda), ou lista vazia.
     *
     * <p>E a pergunta que o salvamento da ficha faz: o nome novo precisa chegar a
     * TODOS os monstros da ficha, e nao so a um.
     */
    public static List<UUID> mobsOf(MinecraftServer server, String sheetId) {
        if (sheetId == null || sheetId.isEmpty()) {
            return List.of();
        }
        Set<UUID> cached = BY_SHEET.get(sheetId);
        if (cached == null || cached.isEmpty()) {
            selfHeal(server);
            cached = BY_SHEET.get(sheetId);
        }
        if (cached == null || cached.isEmpty()) {
            return List.of();
        }
        return List.copyOf(cached);
    }

    /** O mob com esse UUID em algum mundo do servidor, ou {@code null}. */
    public static Mob mobEntity(MinecraftServer server, UUID uuid) {
        if (server == null || uuid == null) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getEntity(uuid) instanceof Mob mob) {
                return mob;
            }
        }
        return null;
    }

    /**
     * Todos os pares mob -&gt; ficha que o cache conhece.
     *
     * <p>E o que o tick do monstro deitado percorre: sao os mobs amarrados, e nao
     * todas as entidades do mundo.
     */
    public static Map<UUID, String> boundMobs() {
        return Collections.unmodifiableMap(BY_MOB);
    }

    private static boolean isAlive(MinecraftServer server, UUID uuid) {
        if (server == null) {
            return false;
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getEntity(uuid) instanceof Mob) {
                return true;
            }
        }
        return false;
    }

    /**
     * Refaz o cache varrendo as entidades carregadas.
     *
     * <p>Chamado quando o cache nao tem a resposta. No primeiro mob que aparecer com a
     * tag, a ficha fica apontada para ele -- dois mobs com a mesma tag e um estado que
     * {@link #bind} impede de criar, entao isso so acontece por edicao de NBT a mao.
     *
     * <p><b>Modo Horda (22/10/2026):</b> dois mobs com a MESMA tag sao legitimos
     * quando a ficha esta no modo horda, entao todos entram na ficha.
     */
    public static void selfHeal(MinecraftServer server) {
        if (server == null) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (!(entity instanceof Mob mob)) {
                    continue;
                }
                Set<String> tags = mob.getTags();
                for (String tag : tags) {
                    if (!tag.startsWith(TAG_PREFIX)) {
                        continue;
                    }
                    String id = tag.substring(TAG_PREFIX.length());
                    if (id.isEmpty()) {
                        continue;
                    }
                    BY_MOB.put(mob.getUUID(), id);
                    mobs(id).add(mob.getUUID());
                    // Um mob tem no maximo uma ficha. Duas tags aqui so acontecem se
                    // alguem editou o NBT a mao; o cache guarda a primeira.
                    break;
                }
            }
        }
    }

    /**
     * Refaz o cache uma vez por sessao.
     *
     * <p><b>Por que uma vez so:</b> refazer o cache e varrer TODAS as entidades
     * carregadas, e fazer isso a cada tick seria caro. O cache se mantem em dia
     * sozinho: o {@link #bind} atualiza ele, e o {@link #unbind} tira o mob.
     */
    public static void selfHealOnce(MinecraftServer server) {
        if (healed) {
            return;
        }
        healed = true;
        selfHeal(server);
    }

    /**
     * Responde ao clique do Mestre numa criatura com a Ficha de Ameaca na mao: amarra a
     * ficha naquele mob. 02/10/2026.
 *
 * <p><b>Por que roda antes da selecao de monstro:</b> o
 * {@link CombatController} consome o clique para alternar a selecao e devolve
 * {@code FAIL}. Quem chega antes ganha. Como o Camera Tool faz pelo mesmo motivo --
 * e ele e o caminho que o Mestre escolheu segurando o item -- a amarracao tem a
 * precedencia sobre a selecao, e nao o contrario.
 *
 * @return {@code true} se o clique foi consumido aqui
 */
public static boolean handleEntityClick(ServerPlayer player, ServerLevel level,
                                        InteractionHand hand, Entity entity, boolean hasHitResult) {
        if (!SessionManager.isMaster(player)) {
            return false;
        }
        ItemStack stack = player.getItemInHand(hand);
        if (!ModItems.isThreatSheet(stack)) {
            return false;
        }
        // O cliente manda 2 pacotes por clique (interactAt + interact). O 2o chega sem
        // posicao; amarrar duas vezes produciria duas mensagens e um bind inutil.
        if (!hasHitResult) {
            return false;
        }
        // O clique acerta a PARTE, nao a entidade: monstro de mod montado de partes
        // precisa ser desembrulhado antes do teste (mesmo caminho do CombatController).
        Entity target = EntityTargets.resolve(entity);
        if (!(target instanceof Mob mob)) {
            return false;
        }

        String sheetId = ModItems.threatSheetId(stack);
        ThreatSheet sheet = ThreatSheetStore.findById(player.getUUID(), sheetId);
        if (sheet == null) {
            player.displayClientMessage(
                    Component.translatable("message.tabletoprpg.threat_sheet_not_found", sheetId), true);
            return true;
        }
        if (!hasUsableName(sheet)) {
            refuseBlankName(player, sheet);
            return true;
        }

        bind(level, mob, sheet);
        TabletopRpg.LOGGER.info("[TabletopRPG] Ficha '{}' amarrada em {} via clique de entidade.",
                sheet.identity().name(), EntityTargets.describe(entity, mob));
        player.displayClientMessage(linkMessage(mob, sheet), false);
        return true;
    }

    /**
     * A ficha tem nome de exibicao para virar nome no mob?
     *
     * <p><b>Por que isso e uma recusa e nao um aviso silencioso:</b> o nome de exibicao e
     * o que o seletor {@code @e[name="..."]} casa. Amarrar uma ficha sem nome produz um
     * monstro que NAO responde a nenhum comando por nome, e a unica pista visivel seria o
     * seletor falhando -- longe do gesto que causou o problema (relato de 02/10/2026).
     */
    private static boolean hasUsableName(ThreatSheet sheet) {
        return !sheet.identity().displayName().isBlank();
    }

    private static void refuseBlankName(ServerPlayer player, ThreatSheet sheet) {
        player.displayClientMessage(Component.translatable(
                "message.tabletoprpg.threat_sheet_blank_display_name", sheet.identity().name()), true);
        TabletopRpg.LOGGER.info("[TabletopRPG] Amarracao recusada: ficha '{}' sem nome de exibicao.",
                sheet.identity().name());
    }

    /**
 * O mob que a mira do Mestre esta alcancando, ou {@code null}.
 *
 * <p><b>Por que ray trace no servidor:</b> o clique chega em dois pacotes e em ordem que
 * nao é garantida -- o de entidade ({@code UseEntityCallback}) e o de uso do item
 * ({@code UseItemCallback}) sao tratados por eventos diferentes. Depender so de um deles
 * fez a ficha abrir quando o Mestre jurava estar amarrando num mob. Aqui os dois
 * caminhos perguntam a MESMA coisa, e a resposta nao muda com a ordem.
 *
 * <p>O teste e por dot product contra o vetor de olhar, com uma folga de ~18 graus, e nao
 * por "primeira entidade da lista": perto demais de um mob grande, a lista perde o que o
 * Mestre esta mirando.
 */
public static Mob mobUnderCrosshair(ServerPlayer player) {
        if (player == null) {
            return null;
        }
        Vec3 start = player.getEyePosition();
        Vec3 look = player.getLookAngle().normalize();
        Vec3 end = start.add(look.scale(MAX_REACH));
        // Mesma chamada que o highlight do cliente usa (TabletopRpgClient): caixa do
        // raycast de start a end, e o ProjectileUtil escolhe o acerto mais proximo.
        AABB box = new AABB(start, end).inflate(1.0);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(player, start, end, box,
                entity -> {
                    if (!(EntityTargets.resolve(entity) instanceof Mob)) {
                        return false;
                    }
                    return !(entity instanceof net.minecraft.world.entity.player.Player)
                            && entity.isPickable();
                },
                MAX_REACH * MAX_REACH);
        if (hit == null) {
            return null;
        }
        return EntityTargets.resolve(hit.getEntity()) instanceof Mob mob ? mob : null;
    }

    /**
     * Amarra a ficha da mao no mob que a mira alcanca.
     *
     * <p>Mesmo trabalho de {@link #handleEntityClick}, so que pelo ray trace. Os dois
     * existem porque os dois eventos chegam em pacotes separados e a ordem entre eles nao e
     * garantida; nenhum dos dois abre a ficha.
     *
     * @return {@code true} se amarrou
     */
    public static boolean bindUnderCrosshair(ServerPlayer player) {
        Mob mob = mobUnderCrosshair(player);
        if (mob == null) {
            return false;
        }
        ItemStack stack = player.getMainHandItem();
        if (!ModItems.isThreatSheet(stack)) {
            return false;
        }
        String sheetId = ModItems.threatSheetId(stack);
        ThreatSheet sheet = ThreatSheetStore.findById(player.getUUID(), sheetId);
        if (sheet == null) {
            player.displayClientMessage(
                    Component.translatable("message.tabletoprpg.threat_sheet_not_found", sheetId), true);
            return true;
        }
        if (!hasUsableName(sheet)) {
            refuseBlankName(player, sheet);
            return true;
        }
        bind(player.level() instanceof ServerLevel level ? level : null, mob, sheet);
        TabletopRpg.LOGGER.info("[TabletopRPG] Ficha '{}' amarrada via uso no ar; nome no mob: '{}'.",
                sheet.identity().name(), sheet.identity().displayName());
        player.displayClientMessage(linkMessage(mob, sheet), false);
        return true;
    }

    /** A mensagem de vinculo, compartilhada pelos dois caminhos. */
    private static Component linkMessage(Mob mob, ThreatSheet sheet) {
        return Component.translatable("message.tabletoprpg.threat_sheet_linked",
                sheet.identity().name(), mob.getName().getString(), sheet.identity().displayName());
    }

    /** Esvazia o cache. Existe para o comando de depuracao e para teste. */
    public static void forgetAll() {
        BY_MOB.clear();
        BY_SHEET.clear();
        healed = false;
    }
}