package com.pedro.tabletoprpg;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.gamerules.GameRules;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Camada de rede do TableTop RPG.
 *
 * <p>Responsável por sincronizar o estado da sessão (papel do jogador,
 * se está no seu turno, nome da sessão, etc.) e o estado de "trava" de
 * movimento do cliente com o servidor.
 *
 * <p>Payloads:
 * <ul>
 *   <li>{@link MenuRequestPayload} (C2S): cliente pede os dados ao abrir o menu.</li>
 *   <li>{@link MenuDataPayload} (S2C): servidor responde com os dados da sessão.</li>
 *   <li>{@link PlayerLockPayload} (S2C): informa ao cliente se o jogador está
 *       "travado" (não pode se mover) por causa do modo investigação/combate.</li>
 *   <li>{@link TimeSetPayload} (C2S): mestre define o horário do mundo.</li>
 *   <li>{@link DayNightCycleSetPayload} (C2S): mestre pausa ou retoma o ciclo dia/noite.</li>
 *   <li>{@link DayNightCycleQueryPayload} (C2S): cliente pede o estado atual do ciclo (ao abrir as Settings).</li>
 *   <li>{@link DayNightCycleStatePayload} (S2C): servidor responde com o estado atual do ciclo.</li>
 *   <li>{@link AuraStatePayload} (S2C): posições das auras de limite de movimentação (círculos azuis).</li>
 *   <li>{@link HoverPayload} (C2S): entidade sob o crosshair do jogador (highlight Glowing).</li>
 *   <li>{@link HoverConfigPayload} (S2C): distância máxima do highlight para o jogador (mestre: ilimitado).</li>
 *   <li>{@link BlockBreakSettingPayload} (C2S): mestre libera/bloqueia a quebra de blocos pelos players.</li>
 *   <li>{@link BlockBreakSettingQueryPayload} (C2S): cliente pede o estado da permissão de quebra (ao abrir as Settings).</li>
 *   <li>{@link BlockBreakSettingStatePayload} (S2C): estado atual da permissão de quebra (resposta/broadcast).</li>
 *   <li>{@link PlaceBlockSettingPayload} (C2S): mestre libera/bloqueia a colocação de blocos pelos players.</li>
 *   <li>{@link PlaceBlockSettingQueryPayload} (C2S): cliente pede o estado da permissão de colocação (ao abrir as Settings).</li>
 *   <li>{@link PlaceBlockSettingStatePayload} (S2C): estado atual da permissão de colocação (resposta/broadcast).</li>
 *   <li>{@link WeatherSetPayload} (C2S): mestre define o clima (0=sol, 1=chuva, 2=tempestade).</li>
 *   <li>{@link WeatherQueryPayload} (C2S): cliente pede o clima atual (ao abrir as Settings).</li>
 *   <li>{@link WeatherStatePayload} (S2C): clima atual do mundo (resposta/broadcast).</li>
 *   <li>{@link SheetQueryPayload} (C2S): abre a ficha de um jogador (nome vazio = a própria).</li>
 *   <li>{@link SheetStatePayload} (S2C): conteúdo de uma ficha + se o destinatário pode editá-la.</li>
 *   <li>{@link SheetFieldPayload} (C2S): edição de um campo da ficha (texto ou número).</li>
 *   <li>{@link SheetSkillPayload} (C2S): adiciona/remove uma habilidade da ficha.</li>
 * </ul>
 */
public final class RpgNetworking {

    private RpgNetworking() {
    }

    // ------------------------------------------------------------------
    // PAYLOADS
    // ------------------------------------------------------------------

    /** Cliente -> Servidor: pede os dados atuais da sessão para abrir o menu. */
    public record MenuRequestPayload() implements CustomPacketPayload {
        public static final Type<MenuRequestPayload> TYPE = new Type<>(TabletopRpg.id("menu_request"));
        public static final StreamCodec<FriendlyByteBuf, MenuRequestPayload> STREAM_CODEC =
                StreamCodec.unit(new MenuRequestPayload());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Servidor -> Cliente: dados da sessão usados para montar o menu. */
    public record MenuDataPayload(
            boolean isMaster,
            boolean isMyTurn,
            String sessionName,
            String modeName,
            String activePlayerName,
            List<String> playerNames
    ) implements CustomPacketPayload {
        public static final Type<MenuDataPayload> TYPE = new Type<>(TabletopRpg.id("menu_data"));
        public static final StreamCodec<FriendlyByteBuf, MenuDataPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, MenuDataPayload::isMaster,
                ByteBufCodecs.BOOL, MenuDataPayload::isMyTurn,
                ByteBufCodecs.stringUtf8(256), MenuDataPayload::sessionName,
                ByteBufCodecs.stringUtf8(64), MenuDataPayload::modeName,
                ByteBufCodecs.stringUtf8(64), MenuDataPayload::activePlayerName,
                ByteBufCodecs.stringUtf8(16).apply(ByteBufCodecs.list()), MenuDataPayload::playerNames,
                MenuDataPayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Servidor -&gt; Cliente: informa se o jogador está travado (não pode se
     * mover) e em que modo de jogo a sessão está.
     *
     * <p><b>Por que o modo veio junto (25/09/2026):</b> o cliente só conhecia o
     * booleano {@code locked}, e a câmera precisa de uma informação diferente:
     * a câmera <b>livre</b> só pode ser usada no modo Livre — nos modos
     * Investigação e Combate valem apenas as câmeras de terceira, primeira e
     * de cima. " travado" e "modo Livre" sao coisas diferentes: no modo Livre
     * ninguem fica travado, e mesmo assim a câmera livre precisa existir.
     * Sem o modo no cliente, a distinção seria impossível.
     *
     * <p>O modo viaja como <b>ordinal</b> (VAR_INT) e nao como enum roteado,
     * para nao acoplar este payload ao {@code SessionManager.GameMode}: assim o
     * cliente valida o indice e cai em um valor seguro, em vez de lancar.
     */
    public record PlayerLockPayload(boolean locked, int modeOrdinal) implements CustomPacketPayload {
        public static final Type<PlayerLockPayload> TYPE = new Type<>(TabletopRpg.id("player_lock"));
        public static final StreamCodec<FriendlyByteBuf, PlayerLockPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, PlayerLockPayload::locked,
                ByteBufCodecs.VAR_INT, PlayerLockPayload::modeOrdinal,
                PlayerLockPayload::new
        );

        /** Atalho: monta o payload com o modo de sessão atual. */
        public static PlayerLockPayload of(boolean locked, SessionManager.GameMode mode) {
            return new PlayerLockPayload(locked, mode == null ? 0 : mode.ordinal());
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Cliente -> Servidor: o mestre define o horário do mundo (0-24000 ticks). */
    public record TimeSetPayload(int timeOfDay) implements CustomPacketPayload {
        public static final Type<TimeSetPayload> TYPE = new Type<>(TabletopRpg.id("time_set"));
        public static final StreamCodec<FriendlyByteBuf, TimeSetPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, TimeSetPayload::timeOfDay,
                TimeSetPayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Cliente -> Servidor: o mestre pausa (false) ou retoma (true) o ciclo dia/noite. */
    public record DayNightCycleSetPayload(boolean enabled) implements CustomPacketPayload {
        public static final Type<DayNightCycleSetPayload> TYPE = new Type<>(TabletopRpg.id("day_night_cycle_set"));
        public static final StreamCodec<FriendlyByteBuf, DayNightCycleSetPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, DayNightCycleSetPayload::enabled,
                DayNightCycleSetPayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Cliente -> Servidor: pede o estado atual do ciclo dia/noite (ao abrir as Settings). */
    public record DayNightCycleQueryPayload() implements CustomPacketPayload {
        public static final Type<DayNightCycleQueryPayload> TYPE = new Type<>(TabletopRpg.id("day_night_cycle_query"));
        public static final StreamCodec<FriendlyByteBuf, DayNightCycleQueryPayload> STREAM_CODEC =
                StreamCodec.unit(new DayNightCycleQueryPayload());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Servidor -> Cliente: estado atual do ciclo dia/noite (resposta à query). */
    public record DayNightCycleStatePayload(boolean enabled) implements CustomPacketPayload {
        public static final Type<DayNightCycleStatePayload> TYPE = new Type<>(TabletopRpg.id("day_night_cycle_state"));
        public static final StreamCodec<FriendlyByteBuf, DayNightCycleStatePayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, DayNightCycleStatePayload::enabled,
                DayNightCycleStatePayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Servidor -> Cliente: posições das auras de limite de movimentação (círculos azuis). */
    public record AuraStatePayload(List<AuraData> auras) implements CustomPacketPayload {
        public static final Type<AuraStatePayload> TYPE = new Type<>(TabletopRpg.id("aura_state"));
        public static final StreamCodec<FriendlyByteBuf, AuraStatePayload> STREAM_CODEC = StreamCodec.composite(
                AuraData.STREAM_CODEC.apply(ByteBufCodecs.list()), AuraStatePayload::auras,
                AuraStatePayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        /** Uma aura: centro (x, y, z) e raio em blocos. O y é a altura do chão (topo do bloco da âncora). */
        public record AuraData(double x, double y, double z, int radius) {
            public static final StreamCodec<FriendlyByteBuf, AuraData> STREAM_CODEC = StreamCodec.composite(
                    ByteBufCodecs.DOUBLE, AuraData::x,
                    ByteBufCodecs.DOUBLE, AuraData::y,
                    ByteBufCodecs.DOUBLE, AuraData::z,
                    ByteBufCodecs.VAR_INT, AuraData::radius,
                    AuraData::new
            );
        }
    }

    /** Cliente -> Servidor: entidade sob o crosshair (hover) para o highlight; -1 = nenhuma. */
    public record HoverPayload(int entityId) implements CustomPacketPayload {
        public static final Type<HoverPayload> TYPE = new Type<>(TabletopRpg.id("hover"));
        public static final StreamCodec<FriendlyByteBuf, HoverPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, HoverPayload::entityId,
                HoverPayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Servidor -> Cliente: distância máxima (em blocos) do highlight para este
     * jogador. O mestre recebe um valor alto (ilimitado na prática); os demais
     * recebem a distância configurada (/rpg hoverdistance).
     */
    public record HoverConfigPayload(int maxDistance) implements CustomPacketPayload {
        public static final Type<HoverConfigPayload> TYPE = new Type<>(TabletopRpg.id("hover_config"));
        public static final StreamCodec<FriendlyByteBuf, HoverConfigPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, HoverConfigPayload::maxDistance,
                HoverConfigPayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Cliente -> Servidor: o mestre libera (true) ou bloqueia (false) a quebra de blocos pelos players. */
    public record BlockBreakSettingPayload(boolean enabled) implements CustomPacketPayload {
        public static final Type<BlockBreakSettingPayload> TYPE = new Type<>(TabletopRpg.id("block_break_setting"));
        public static final StreamCodec<FriendlyByteBuf, BlockBreakSettingPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, BlockBreakSettingPayload::enabled,
                BlockBreakSettingPayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Cliente -> Servidor: pede o estado atual da permissão de quebra (ao abrir as Settings). */
    public record BlockBreakSettingQueryPayload() implements CustomPacketPayload {
        public static final Type<BlockBreakSettingQueryPayload> TYPE = new Type<>(TabletopRpg.id("block_break_setting_query"));
        public static final StreamCodec<FriendlyByteBuf, BlockBreakSettingQueryPayload> STREAM_CODEC =
                StreamCodec.unit(new BlockBreakSettingQueryPayload());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Servidor -> Cliente: estado atual da permissão de quebra de blocos (resposta à query / broadcast). */
    public record BlockBreakSettingStatePayload(boolean enabled) implements CustomPacketPayload {
        public static final Type<BlockBreakSettingStatePayload> TYPE = new Type<>(TabletopRpg.id("block_break_setting_state"));
        public static final StreamCodec<FriendlyByteBuf, BlockBreakSettingStatePayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, BlockBreakSettingStatePayload::enabled,
                BlockBreakSettingStatePayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Cliente -> Servidor: o mestre define o clima (0=sol, 1=chuva, 2=tempestade). */
    public record WeatherSetPayload(int weather) implements CustomPacketPayload {
        public static final Type<WeatherSetPayload> TYPE = new Type<>(TabletopRpg.id("weather_set"));
        public static final StreamCodec<FriendlyByteBuf, WeatherSetPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, WeatherSetPayload::weather,
                WeatherSetPayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Cliente -> Servidor: pede o clima atual do mundo (ao abrir as Settings). */
    public record WeatherQueryPayload() implements CustomPacketPayload {
        public static final Type<WeatherQueryPayload> TYPE = new Type<>(TabletopRpg.id("weather_query"));
        public static final StreamCodec<FriendlyByteBuf, WeatherQueryPayload> STREAM_CODEC =
                StreamCodec.unit(new WeatherQueryPayload());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Servidor -> Cliente: clima atual do mundo (0=sol, 1=chuva, 2=tempestade). Resposta à query / broadcast. */
    public record WeatherStatePayload(int weather) implements CustomPacketPayload {
        public static final Type<WeatherStatePayload> TYPE = new Type<>(TabletopRpg.id("weather_state"));
        public static final StreamCodec<FriendlyByteBuf, WeatherStatePayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, WeatherStatePayload::weather,
                WeatherStatePayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Cliente -> Servidor: o mestre libera (true) ou bloqueia (false) a colocação de blocos pelos players. */
    public record PlaceBlockSettingPayload(boolean enabled) implements CustomPacketPayload {
        public static final Type<PlaceBlockSettingPayload> TYPE = new Type<>(TabletopRpg.id("place_block_setting"));
        public static final StreamCodec<FriendlyByteBuf, PlaceBlockSettingPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, PlaceBlockSettingPayload::enabled,
                PlaceBlockSettingPayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Cliente -> Servidor: pede o estado atual da permissão de colocação (ao abrir as Settings). */
    public record PlaceBlockSettingQueryPayload() implements CustomPacketPayload {
        public static final Type<PlaceBlockSettingQueryPayload> TYPE = new Type<>(TabletopRpg.id("place_block_setting_query"));
        public static final StreamCodec<FriendlyByteBuf, PlaceBlockSettingQueryPayload> STREAM_CODEC =
                StreamCodec.unit(new PlaceBlockSettingQueryPayload());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Servidor -> Cliente: estado atual da permissão de colocação de blocos (resposta à query / broadcast). */
    public record PlaceBlockSettingStatePayload(boolean enabled) implements CustomPacketPayload {
        public static final Type<PlaceBlockSettingStatePayload> TYPE = new Type<>(TabletopRpg.id("place_block_setting_state"));
        public static final StreamCodec<FriendlyByteBuf, PlaceBlockSettingStatePayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, PlaceBlockSettingStatePayload::enabled,
                PlaceBlockSettingStatePayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Servidor -> Cliente: lista de alvos do carrossel de espectador (FASE 2).
     * Todos os jogadores conectados + mobs invocados com câmera (cam_perm=true).
     * O cliente adiciona a si mesmo como alvo inicial (índice 0).
     */
    public record SpectatorTargetsPayload(List<TargetData> targets) implements CustomPacketPayload {
        public static final Type<SpectatorTargetsPayload> TYPE = new Type<>(TabletopRpg.id("spectator_targets"));
        public static final StreamCodec<FriendlyByteBuf, SpectatorTargetsPayload> STREAM_CODEC = StreamCodec.composite(
                TargetData.STREAM_CODEC.apply(ByteBufCodecs.list()), SpectatorTargetsPayload::targets,
                SpectatorTargetsPayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        /** Um alvo do carrossel: entidade (id), nome e se é jogador. */
        public record TargetData(int entityId, String name, boolean isPlayer) {
            public static final StreamCodec<FriendlyByteBuf, TargetData> STREAM_CODEC = StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, TargetData::entityId,
                    ByteBufCodecs.stringUtf8(64), TargetData::name,
                    ByteBufCodecs.BOOL, TargetData::isPlayer,
                    TargetData::new
            );
        }
    }

    /**
     * Servidor -> Cliente: UUID do jogador ativo (turno atual) como string;
     * vazio ("") quando não há turno ativo. Usado pelo espectador para saber
     * se o alvo espectado está no turno dele (câmera 3ª pessoa = mouse do
     * espectador em vez da órbita automática).
     */
    public record ActivePlayerPayload(String activePlayerUuid) implements CustomPacketPayload {
        public static final Type<ActivePlayerPayload> TYPE = new Type<>(TabletopRpg.id("active_player"));
        public static final StreamCodec<FriendlyByteBuf, ActivePlayerPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(36), ActivePlayerPayload::activePlayerUuid,
                ActivePlayerPayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ------------------------------------------------------------------
    // PAYLOADS — FICHA DO PERSONAGEM (FASE 3)
    // ------------------------------------------------------------------

    /**
     * Cliente -> Servidor: abre a ficha de um jogador. {@code targetName} vazio
     * (ou igual ao próprio nome) significa "a minha ficha".
     *
     * <p>Usa <b>nome</b> e não UUID de propósito: a lista de jogadores já
     * chega ao cliente como nomes (MenuDataPayload) e o servidor sabe
     * resolver nome -> jogador com {@code getPlayerByName}. Assim o cliente
     * não precisa carregar UUIDs, e um cliente antigo/incompatível não quebra.
     */
    public record SheetQueryPayload(String targetName) implements CustomPacketPayload {
        public static final Type<SheetQueryPayload> TYPE = new Type<>(TabletopRpg.id("sheet_query"));
        public static final StreamCodec<FriendlyByteBuf, SheetQueryPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(64), SheetQueryPayload::targetName,
                SheetQueryPayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Servidor -> Cliente: o conteúdo de uma ficha.
     *
     * @param targetName nome do dono da ficha
     * @param ownerUuid  UUID do dono (string), para o cliente identificar a quem pertence
     * @param sheet      os dados da ficha
     * @param canEdit    se o destinatário pode editar esta ficha
     *                   (mestre: sempre; jogador: só a própria)
     */
    public record SheetStatePayload(
            String targetName,
            String ownerUuid,
            SheetData sheet,
            boolean canEdit
    ) implements CustomPacketPayload {
        public static final Type<SheetStatePayload> TYPE = new Type<>(TabletopRpg.id("sheet_state"));
        public static final StreamCodec<FriendlyByteBuf, SheetStatePayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(64), SheetStatePayload::targetName,
                ByteBufCodecs.stringUtf8(36), SheetStatePayload::ownerUuid,
                SheetData.STREAM_CODEC, SheetStatePayload::sheet,
                ByteBufCodecs.BOOL, SheetStatePayload::canEdit,
                SheetStatePayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Cliente -> Servidor: edição de um campo da ficha.
     *
     * <p>Um único payload para texto e número: o campo é um identificador
     * ("hp", "race", ...) e o valor chega como texto. O servidor converte e
     * limita em {@link SheetData#withField(String, String)} — o cliente nunca
     * escreve direto no estado.
     */
    public record SheetFieldPayload(String targetName, String field, String value) implements CustomPacketPayload {
        public static final Type<SheetFieldPayload> TYPE = new Type<>(TabletopRpg.id("sheet_field"));
        public static final StreamCodec<FriendlyByteBuf, SheetFieldPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(64), SheetFieldPayload::targetName,
                ByteBufCodecs.stringUtf8(32), SheetFieldPayload::field,
                ByteBufCodecs.stringUtf8(64), SheetFieldPayload::value,
                SheetFieldPayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Cliente -&gt; Servidor: adiciona ou remove uma <b>skill</b> (nome + descrição).
     *
     * <p>A skill é a lista <b>live</b> da ficha: o jogador monta do zero o que
     * ele sabe fazer. Por isso este payload não viaja valor nem atributo — o que
     * uma perícia soma na rolagem é assunto da {@link SheetPericiaPayload}.
     * Os dois são pacotes diferentes justamente para que mexer na lista de
     * perícias não possa alterar a lista de skills (e vice-versa).
     */
    public record SheetSkillPayload(String targetName, String skill, SheetData.SkillOp op,
                                    String description) implements CustomPacketPayload {
        public static final Type<SheetSkillPayload> TYPE = new Type<>(TabletopRpg.id("sheet_skill"));
        public static final StreamCodec<FriendlyByteBuf, SheetSkillPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(64), SheetSkillPayload::targetName,
                ByteBufCodecs.stringUtf8(SheetData.SKILL_MAX), SheetSkillPayload::skill,
                SheetData.SkillOp.STREAM_CODEC, SheetSkillPayload::op,
                ByteBufCodecs.stringUtf8(SheetData.SKILL_DESC_MAX), SheetSkillPayload::description,
                SheetSkillPayload::new
        );

        /** Atalho: criar a skill, ou atualizar a descrição se o nome já existir. */
        public static SheetSkillPayload add(String targetName, String skill, String description) {
            return new SheetSkillPayload(targetName, skill, SheetData.SkillOp.ADD, description);
        }

        /** Atalho: remover a skill pelo nome (só o nome importa). */
        public static SheetSkillPayload remove(String targetName, String skill) {
            return new SheetSkillPayload(targetName, skill, SheetData.SkillOp.REMOVE, "");
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Cliente -&gt; Servidor: muda o <b>valor</b> ou o <b>atributo</b> de uma perícia.
     *
     * <p>Não existe "add" nem "remove" aqui, de propósito: a lista de perícias
     * é fixa (definida em {@link SheetData#PERICIAS_PADRAO}) e o jogador só
     * ajusta estes dois campos. Se o nome não estiver na lista, o servidor
     * ignora — a lista não cresce nem encolhe pela rede.
     */
    public record SheetPericiaPayload(String targetName, String pericia, SheetData.PericiaOp op, int value,
                                      SheetData.Attribute attribute) implements CustomPacketPayload {
        public static final Type<SheetPericiaPayload> TYPE = new Type<>(TabletopRpg.id("sheet_pericia"));
        public static final StreamCodec<FriendlyByteBuf, SheetPericiaPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(64), SheetPericiaPayload::targetName,
                ByteBufCodecs.stringUtf8(SheetData.SKILL_MAX), SheetPericiaPayload::pericia,
                SheetData.PericiaOp.STREAM_CODEC, SheetPericiaPayload::op,
                ByteBufCodecs.VAR_INT, SheetPericiaPayload::value,
                SheetData.Attribute.STREAM_CODEC, SheetPericiaPayload::attribute,
                SheetPericiaPayload::new
        );

        /** Atalho: mexer só no valor (setas da lista de perícias no Status). */
        public static SheetPericiaPayload setValue(String targetName, String pericia, int value) {
            return new SheetPericiaPayload(targetName, pericia, SheetData.PericiaOp.SET_VALUE, value,
                    SheetData.Attribute.DEXTERITY);
        }

        /** Atalho: mexer só no atributo (botão de lista suspensa). */
        public static SheetPericiaPayload setAttribute(String targetName, String pericia, SheetData.Attribute attribute) {
            return new SheetPericiaPayload(targetName, pericia, SheetData.PericiaOp.SET_ATTRIBUTE, 0, attribute);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Servidor -&gt; Todos os clientes: o personagem deste UUID está deitado
     * (HP da ficha &lt;= 0).
     *
     * <p><b>FACT (por que o UUID no payload e broadcast):</b> o cliente
     * recalcula a pose de <b>todos</b> os jogadores a cada tick em
     * {@code Player.tick() -&gt; updatePlayerPose()}, não só do jogador local.
     * Se o servidor avisasse apenas o próprio jogador, o cliente do MESTRE
     * levantaria os personagens caídos dos outros e o mestre — que é quem
     * precisa avaliar quem está deitado — veria o estado errado. Por isso o
     * estado é transmitido a todos os clientes conectados.
     *
     * <p>Enviado só quando o estado MUDA (não a cada tick), para não gerar
     * tráfego. O HP em si não vem aqui: a barra da ficha mostra o valor pelo
     * {@link SheetStatePayload}.
     */
    public record DownedStatePayload(UUID playerId, boolean downed) implements CustomPacketPayload {
        public static final Type<DownedStatePayload> TYPE = new Type<>(TabletopRpg.id("downed_state"));
        public static final StreamCodec<FriendlyByteBuf, DownedStatePayload> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, DownedStatePayload::playerId,
                ByteBufCodecs.BOOL, DownedStatePayload::downed,
                DownedStatePayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ------------------------------------------------------------------
    // REGISTRO (comum a servidor e cliente)
    // ------------------------------------------------------------------
    /** Deve ser chamado no onInitialize() (lado comum). */
    public static void registerPayloads() {
        PayloadTypeRegistry.playC2S().register(MenuRequestPayload.TYPE, MenuRequestPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(MenuDataPayload.TYPE, MenuDataPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(PlayerLockPayload.TYPE, PlayerLockPayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(TimeSetPayload.TYPE, TimeSetPayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(DayNightCycleSetPayload.TYPE, DayNightCycleSetPayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(DayNightCycleQueryPayload.TYPE, DayNightCycleQueryPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(DayNightCycleStatePayload.TYPE, DayNightCycleStatePayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(AuraStatePayload.TYPE, AuraStatePayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(HoverPayload.TYPE, HoverPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(HoverConfigPayload.TYPE, HoverConfigPayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(BlockBreakSettingPayload.TYPE, BlockBreakSettingPayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(BlockBreakSettingQueryPayload.TYPE, BlockBreakSettingQueryPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(BlockBreakSettingStatePayload.TYPE, BlockBreakSettingStatePayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(PlaceBlockSettingPayload.TYPE, PlaceBlockSettingPayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(PlaceBlockSettingQueryPayload.TYPE, PlaceBlockSettingQueryPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(PlaceBlockSettingStatePayload.TYPE, PlaceBlockSettingStatePayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(WeatherSetPayload.TYPE, WeatherSetPayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(WeatherQueryPayload.TYPE, WeatherQueryPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(WeatherStatePayload.TYPE, WeatherStatePayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(SpectatorTargetsPayload.TYPE, SpectatorTargetsPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(ActivePlayerPayload.TYPE, ActivePlayerPayload.STREAM_CODEC);
        // Ficha do personagem (FASE 3)
        PayloadTypeRegistry.playC2S().register(SheetQueryPayload.TYPE, SheetQueryPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(SheetStatePayload.TYPE, SheetStatePayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(SheetFieldPayload.TYPE, SheetFieldPayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(SheetSkillPayload.TYPE, SheetSkillPayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(SheetPericiaPayload.TYPE, SheetPericiaPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(DownedStatePayload.TYPE, DownedStatePayload.STREAM_CODEC);
    }

    /** Registra os receptores no lado do servidor. */
    public static void registerServerReceivers() {
        ServerPlayNetworking.registerGlobalReceiver(MenuRequestPayload.TYPE, (payload, context) -> {
            TabletopRpg.LOGGER.info("[TabletopRPG] MenuRequestPayload recebido de {} -> enviando dados da sessão.",
                    context.player().getName().getString());
            // Abre o menu no cliente (resposta ao apertar R).
            sendMenuToPlayer(context.player());
        });

        // Ao entrar, o jogador só recebe o estado de "trava" (congelamento),
        // para não conseguir se mover em investigação/combate. NÃO enviamos o
        // MenuDataPayload aqui, pois isso abriria o menu automaticamente ao entrar.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            sendLockToPlayer(handler.getPlayer());
            // Estado "deitado" no JOIN: sem isto o cliente ficaria 1 tick com o
            // valor antigo de `downed` (que e estatico e sobrevive a troca de
            // mundo) e um personagem deitado poderia andar nesse intervalo.
            SheetData sheet = SessionManager.getSheet(handler.getPlayer().getUUID());
            sendDownedState(handler.getPlayer(), sheet != null && sheet.isDowned());
            // O estado deitado e por UUID e chega por broadcast quando MUDA, so
            // que quem entra depois do evento nao receberia nada e o vanilla
            // levantaria o caido na frente dele. Manda o retrato atual de todos
            // os jogadores conectados para quem acabou de entrar.
            sendDownedSnapshotTo(handler.getPlayer());
            sendHoverConfigToPlayer(handler.getPlayer());
            sendSpectatorTargetsToPlayer(handler.getPlayer());
            sendActivePlayerToPlayer(handler.getPlayer());
            // A lista de alvos do carrossel mudou (um jogador entrou): atualiza
            // também os jogadores já conectados, senão o novo jogador só
            // apareceria no carrossel deles no próximo broadcast.
            sendSpectatorTargetsToAll(server);
        });

        // Ao desconectar: se era o mestre, libera o cargo e pausa a sessão
        // (modo volta para FREE, turno limpo, combate resetado). Se era o
        // jogador ativo, limpa o turno dele. Evita estado quebrado (jogadores
        // travados sem mestre, ou turno preso num jogador que caiu).
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer player = handler.getPlayer();
            if (player == null) {
                return;
            }
            boolean changed = false;
            if (SessionManager.isMaster(player)) {
                SessionManager.releaseMaster();
                SessionManager.setMode(SessionManager.GameMode.FREE);
                CombatController.reset(server);
                changed = true;
                // Avisa todos que o mestre saiu (ninguém fica sem saber por
                // que a sessão "pausou").
                server.getPlayerList().broadcastSystemMessage(
                        Component.literal("§c[RPG] The master left the session. Mode set to FREE."), false);
            }
            if (SessionManager.isActivePlayer(player)) {
                SessionManager.clearActivePlayer();
                CombatController.clearPlayerAnchor(player.getUUID());
                changed = true;
            }
            // Limpa o hover deste jogador (entrada do mapa por jogador).
            CombatController.clearHoveredEntity(player.getUUID());
            if (changed) {
                sendToAll(server);
                sendAuraStateToAll(server);
            }
            // A lista de alvos do carrossel mudou (um jogador saiu): atualiza
            // todos os clientes conectados.
            sendSpectatorTargetsToAll(server);
        });

        // Mestre define o horário do mundo (vindo do slider do menu ou do comando).
        ServerPlayNetworking.registerGlobalReceiver(TimeSetPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (!SessionManager.isMaster(player)) {
                return; // só o mestre pode mudar o horário
            }
            int time = Math.floorMod(payload.timeOfDay(), 24000);
            ServerLevel level = (ServerLevel) player.level();
            level.setDayTime(time);
            // Aplicação silenciosa: não envia mensagem a cada movimento do slider
            // (evita spam no chat). O mestre vê o valor no próprio slider.
        });

        // Cliente pede o estado atual do ciclo dia/noite (ao abrir as Settings).
        ServerPlayNetworking.registerGlobalReceiver(DayNightCycleQueryPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            ServerLevel level = (ServerLevel) player.level();
            boolean enabled = level.getGameRules().get(GameRules.ADVANCE_TIME);
            ServerPlayNetworking.send(player, new DayNightCycleStatePayload(enabled));
        });

        // Mestre libera/bloqueia a quebra de blocos pelos players (menu de configurações).
        ServerPlayNetworking.registerGlobalReceiver(BlockBreakSettingPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (!SessionManager.isMaster(player)) {
                return; // só o mestre pode mudar
            }
            SessionManager.setPlayersCanBreakBlocks(payload.enabled());
            sendBlockBreakSettingStateToAll(player.level().getServer());
        });

        // Cliente pede o estado atual da permissão de quebra (ao abrir as Settings).
        ServerPlayNetworking.registerGlobalReceiver(BlockBreakSettingQueryPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            ServerPlayNetworking.send(player, new BlockBreakSettingStatePayload(SessionManager.canPlayersBreakBlocks()));
        });

        // Mestre define o clima (0=sol, 1=chuva, 2=tempestade) pelo menu de configurações.
        ServerPlayNetworking.registerGlobalReceiver(WeatherSetPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (!SessionManager.isMaster(player)) {
                return; // só o mestre pode mudar o clima
            }
            ServerLevel level = (ServerLevel) player.level();
            // API verificada com javap (1.21.11): setWeatherParameters(int clearTime,
            // int rainTime, boolean raining, boolean thundering). Mesmos valores que o
            // comando vanilla /weather usa (verificado no bytecode de WeatherCommand).
            switch (payload.weather()) {
                case 0 -> level.setWeatherParameters(6000, 0, false, false);   // sol
                case 1 -> level.setWeatherParameters(0, 6000, true, false);    // chuva
                case 2 -> level.setWeatherParameters(0, 6000, true, true);     // tempestade
                default -> { /* valor inválido: ignora */ }
            }
            // Guarda o estado ALVO e faz broadcast dele. Em 1.21.11 o clima muda
            // gradualmente (isRaining/isThundering derivam de rainLevel/thunderLevel
            // suavizados), então o estado real NÃO corresponde ao escolhido durante
            // a transição — se enviássemos o real, o botão de clima "voltaria" para
            // o estado antigo até a transição terminar (bug relatado pelo usuário).
            SessionManager.setWeatherTarget(payload.weather());
            sendWeatherStateToAll(level.getServer());
        });

        // Cliente pede o clima atual (ao abrir as Settings). Responde com o estado
        // ALVO escolhido pelo mestre (não o real, que muda gradualmente).
        ServerPlayNetworking.registerGlobalReceiver(WeatherQueryPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            ServerPlayNetworking.send(player, new WeatherStatePayload(SessionManager.getWeatherTarget()));
        });

        // Mestre libera/bloqueia a colocação de blocos pelos players (menu de configurações).
        ServerPlayNetworking.registerGlobalReceiver(PlaceBlockSettingPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (!SessionManager.isMaster(player)) {
                return; // só o mestre pode mudar
            }
            SessionManager.setPlayersCanPlaceBlocks(payload.enabled());
            sendPlaceBlockSettingStateToAll(player.level().getServer());
        });

        // Cliente pede o estado atual da permissão de colocação (ao abrir as Settings).
        ServerPlayNetworking.registerGlobalReceiver(PlaceBlockSettingQueryPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            ServerPlayNetworking.send(player, new PlaceBlockSettingStatePayload(SessionManager.canPlayersPlaceBlocks()));
        });

        // Cliente informa qual entidade está sob o crosshair (hover). O
        // highlight NÃO usa mais o efeito Glowing vanilla (que é GLOBAL —
        // todos os jogadores viam o contorno do mob hoverado por qualquer um):
        // o contorno agora é renderizado apenas no cliente de quem está
        // mirando (EntityRendererMixin). Aqui só registramos o hover atual
        // por jogador.
        ServerPlayNetworking.registerGlobalReceiver(HoverPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            ServerLevel level = (ServerLevel) player.level();

            if (payload.entityId() >= 0) {
                Entity target = level.getEntity(payload.entityId());
                if (target instanceof LivingEntity living) {
                    CombatController.setHoveredEntity(player.getUUID(), living.getUUID());
                } else {
                    CombatController.clearHoveredEntity(player.getUUID());
                }
            } else {
                CombatController.clearHoveredEntity(player.getUUID());
            }
        });

        // ------------------------------------------------------------------
        // Ficha do personagem (FASE 3)
        // ------------------------------------------------------------------

        // Cliente abre uma ficha: a própria, ou a de qualquer jogador se for o mestre.
        ServerPlayNetworking.registerGlobalReceiver(SheetQueryPayload.TYPE, (payload, context) -> {
            ServerPlayer sender = context.player();
            ServerPlayer target = resolveSheetTarget(sender, payload.targetName());
            if (target == null) {
                return; // alvo não está conectado: não há ficha para mostrar
            }
            // Leitura é mais restrita que edição: só o dono e o mestre.
            if (!canViewSheet(sender, target)) {
                TabletopRpg.LOGGER.warn("[TabletopRPG] {} tentou abrir a ficha de {} — negado (não é dono nem mestre).",
                        sender.getName().getString(), target.getName().getString());
                return;
            }
            sendSheetTo(sender, target);
        });

        // Edição de um campo. Permissão conferida no servidor: o mestre edita
        // qualquer ficha, o jogador só a própria. O cliente manda texto, e quem
        // converte/limita é o SheetData — o estado do servidor nunca é escrito
        // diretamente a partir do payload.
        ServerPlayNetworking.registerGlobalReceiver(SheetFieldPayload.TYPE, (payload, context) -> {
            ServerPlayer sender = context.player();
            ServerPlayer target = resolveSheetTarget(sender, payload.targetName());
            if (target == null || !canEditSheet(sender, target)) {
                return;
            }
            SheetData current = SessionManager.getOrCreateSheet(target.getUUID(), target.getName().getString());
            SheetData updated = current.withField(payload.field(), payload.value());
            if (updated == current) {
                return; // campo desconhecido ou valor não numérico: nada mudou
            }
            SessionManager.setSheet(target.getUUID(), updated);
            broadcastSheet(target);
        });

        // Cria/remove/altera uma pericia (mesma regra de permissão do campo).
        ServerPlayNetworking.registerGlobalReceiver(SheetSkillPayload.TYPE, (payload, context) -> {
            ServerPlayer sender = context.player();
            ServerPlayer target = resolveSheetTarget(sender, payload.targetName());
            if (target == null || !canEditSheet(sender, target)) {
                return;
            }
            SheetData current = SessionManager.getOrCreateSheet(target.getUUID(), target.getName().getString());
            // SkillOp.ADD cria OU atualiza a descrição, se o nome já existir.
            SheetData updated = switch (payload.op() == null ? SheetData.SkillOp.INVALID : payload.op()) {
                case ADD -> current.withSkill(payload.skill(), payload.description());
                case REMOVE -> current.withoutSkill(payload.skill());
                // Pacote corrompido: melhor não fazer nada do que transformar
                // um índice inválido em "criar skill".
                case INVALID -> current;
            };
            if (updated == current) {
                return; // skill inexistente no REMOVE, lista cheia, ou nada mudou
            }
            SessionManager.setSheet(target.getUUID(), updated);
            broadcastSheet(target);
        });

        // Muda o VALOR ou o ATRIBUTO de uma PERÍCIA. A lista de perícias é
        // fixa, então este pacote não cria nem remove nada — e vale a mesma
        // regra de permissão dos outros campos da ficha.
        ServerPlayNetworking.registerGlobalReceiver(SheetPericiaPayload.TYPE, (payload, context) -> {
            ServerPlayer sender = context.player();
            ServerPlayer target = resolveSheetTarget(sender, payload.targetName());
            if (target == null || !canEditSheet(sender, target)) {
                return;
            }
            SheetData current = SessionManager.getOrCreateSheet(target.getUUID(), target.getName().getString());
            SheetData updated = switch (payload.op() == null ? SheetData.PericiaOp.INVALID : payload.op()) {
                case SET_VALUE -> current.withPericiaValue(payload.pericia(), payload.value());
                case SET_ATTRIBUTE -> current.withPericiaAttribute(payload.pericia(), payload.attribute());
                case INVALID -> current;
            };
            if (updated == current) {
                return; // perícia fora da lista fixa, ou o valor não mudou
            }
            SessionManager.setSheet(target.getUUID(), updated);
            broadcastSheet(target);
        });

        // Registra o receptor de pausar/retomar ciclo dia e noite no servidor
        registerDayCycleReceiver();
    }

    // ------------------------------------------------------------------
    // FICHA DO PERSONAGEM — RESOLUÇÃO DE ALVO, PERMISSÃO E ENVIO
    // ------------------------------------------------------------------

    /**
     * Resolve o alvo de uma operação de ficha a partir do nome enviado.
     *
     * <p>Nome vazio, ou igual ao próprio nome, significa "a minha ficha".
     * Retorna null quando o nome não corresponde a ninguém conectado — o
     * chamador simplesmente ignora a operação.
     */
    private static ServerPlayer resolveSheetTarget(ServerPlayer sender, String targetName) {
        if (sender == null) {
            return null;
        }
        String name = targetName == null ? "" : targetName.trim();
        if (name.isEmpty() || name.equalsIgnoreCase(sender.getName().getString())) {
            return sender;
        }
        MinecraftServer server = sender.level().getServer();
        if (server == null) {
            return null;
        }
        return server.getPlayerList().getPlayerByName(name);
    }

    /**
     * Regra de edição da ficha: o mestre pode editar a de qualquer jogador;
     * um jogador comum só pode editar a própria.
     */
    private static boolean canEditSheet(ServerPlayer sender, ServerPlayer target) {
        return SessionManager.isMaster(sender) || sender.getUUID().equals(target.getUUID());
    }

    /**
     * Regra de <b>leitura</b> da ficha. É mais restrita que a de edição: a
     * ficha é dado do personagem, então nem o próprio dono de uma ficha
     * alheia consegue abri-la.
     *
     * <p>Sem esta checagem, um cliente comum poderia forjar um
     * {@link SheetQueryPayload} com o nome de outro jogador e receber a ficha
     * dele (não edita, mas vazaria a informação).
     */
    private static boolean canViewSheet(ServerPlayer viewer, ServerPlayer target) {
        return SessionManager.isMaster(viewer) || viewer.getUUID().equals(target.getUUID());
    }

    /**
     * Envia a ficha de {@code target} para um visualizador específico,
     * incluindo se ele pode editá-la.
     *
     * <p>A permissão de leitura é reconferida aqui (e não só no receptor da
     * query) para que nenhum caminho futuro de envio vaze a ficha de alguém.
     */
    public static void sendSheetTo(ServerPlayer viewer, ServerPlayer target) {
        if (viewer == null || target == null || viewer.connection == null) {
            return;
        }
        if (!canViewSheet(viewer, target)) {
            return; // sem permissão de leitura: não envia nada
        }
        SheetData sheet = SessionManager.getOrCreateSheet(target.getUUID(), target.getName().getString());
        ServerPlayNetworking.send(viewer, new SheetStatePayload(
                target.getName().getString(),
                target.getUUID().toString(),
                sheet,
                canEditSheet(viewer, target)
        ));
    }

    /**
     * Reenvia a ficha do alvo para quem tem legitimidade para vê-la: o próprio
     * jogador e o mestre. É este reenvio que torna a edição bidirecional — o
     * dono vê a alteração feita pelo mestre, e o mestre vê a feita pelo
     * jogador. Ninguém mais recebe nada.
     */
    public static void broadcastSheet(ServerPlayer target) {
        if (target == null) {
            return;
        }
        MinecraftServer server = target.level().getServer();
        if (server == null) {
            return;
        }
        // O dono vê a própria ficha (com permissão de edição).
        sendSheetTo(target, target);
        // O mestre acompanha a ficha, mesmo de outro jogador.
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (SessionManager.isMaster(player) && !player.getUUID().equals(target.getUUID())) {
                sendSheetTo(player, target);
            }
        }
    }

    // ------------------------------------------------------------------
    // RECEPTORES (REGISTRO) — continuação
    // ------------------------------------------------------------------

    /** Cliente -> Servidor: o mestre pausa (false) ou retoma (true) o ciclo dia/noite. */
    private static void registerDayCycleReceiver() {
        ServerPlayNetworking.registerGlobalReceiver(DayNightCycleSetPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (!SessionManager.isMaster(player)) {
                return; // só o mestre pode pausar/retomar o ciclo
            }
            // No 1.21.11 o gamerule é "advance_time" (doDaylightCycle foi renomeado).
            // API verificada com javap: GameRules.ADVANCE_TIME (GameRule<Boolean>),
            // GameRules.set(GameRule<T>, T, MinecraftServer).
            ServerLevel level = (ServerLevel) player.level();
            MinecraftServer server = level.getServer();
            if (server != null) {
                level.getGameRules().set(GameRules.ADVANCE_TIME, payload.enabled(), server);
                player.sendSystemMessage(Component.literal("§6Day/night cycle §e" + (payload.enabled() ? "resumed" : "paused")));
            }
        });
    }

    // ------------------------------------------------------------------
    // ENVIO
    // ------------------------------------------------------------------

    /**
     * Envia o estado de "trava" (congelamento) e o modo de sessão para um
     * jogador. Usado no JOIN e em mudanças de modo/turno.
     *
     * <p>O modo viaja junto porque a câmera do cliente decide se a câmera livre
     * pode ser usada — e isso depende do modo, não da trava.
     */
    public static void sendLockToPlayer(ServerPlayer player) {
        if (player == null || player.connection == null) {
            return;
        }
        ServerPlayNetworking.send(player,
                PlayerLockPayload.of(!SessionManager.canPlayerAct(player), SessionManager.getMode()));
    }

    /**
     * Envia o estado "deitado" (HP da ficha &lt;= 0) para o próprio jogador,
     * para o cliente congelar o movimento local.
     *
     * <p>Chamado por {@code DamageControlHandler} só quando o estado muda
     * (inclusive na primeira vez que o jogador é visto no tick), então não
     * gera tráfego por tick.
     */
    public static void sendDownedState(ServerPlayer player, boolean downed) {
        if (player == null || player.connection == null) {
            return;
        }
        // ServerPlayer.server e privado nesta versao: o caminho publico e
        // ServerLevel.getServer().
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return;
        }
        DownedStatePayload payload = new DownedStatePayload(player.getUUID(), downed);
        // Broadcast, e nao so para o dono: o vanilla recalcula a pose de TODOS os
        // jogadores no cliente, entao se so o dono soubesse, o mestre veria os
        // caidos em pe. Ver a justificativa em DownedStatePayload.
        for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
            ServerPlayNetworking.send(viewer, payload);
        }
    }

    /**
     * Manda para quem acabou de entrar o estado deitado de <b>todos</b> os
     * jogadores ja conectados.
     *
     * <p><b>Por que precisa existir:</b> {@link #sendDownedState} so dispara
     * quando o estado MUDA, entao um jogador que entra/reconecta depois de
     * alguem cair receberia informacao nenhuma sobre o caido -- e o
     * {@code ClientPlayerPoseMixin} dele nao seguraria a pose, deixando o
     * vanilla levantar o personagem na frente de quem acabou de entrar.
     *
     * @param viewer quem esta entrando (tambem recebe o proprio estado, inofensivo)
     */
    public static void sendDownedSnapshotTo(ServerPlayer viewer) {
        if (viewer == null || viewer.connection == null) {
            return;
        }
        MinecraftServer server = viewer.level().getServer();
        if (server == null) {
            return;
        }
        for (ServerPlayer other : server.getPlayerList().getPlayers()) {
            SheetData otherSheet = SessionManager.getSheet(other.getUUID());
            boolean downed = otherSheet != null && otherSheet.isDowned();
            ServerPlayNetworking.send(viewer, new DownedStatePayload(other.getUUID(), downed));
        }
    }

    /** Envia os dados da sessão + estado de trava, abrindo o menu no cliente (resposta ao apertar R). */
    public static void sendMenuToPlayer(ServerPlayer player) {
        if (player == null || player.connection == null) {
            return;
        }

        // Lista de jogadores conectados (para o mestre poder dar turno pelo menu).
        // O mestre NAO entra na lista: ele nao tem ficha propria (feedback do
        // usuario), entao clicar no proprio nome abriria uma ficha vazia sem
        // nenhuma utilidade.
        List<String> playerNames = new ArrayList<>();
        MinecraftServer server = player.level().getServer();
        if (server != null) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (SessionManager.isMaster(p)) {
                    continue;
                }
                playerNames.add(p.getName().getString());
            }
        }

        ServerPlayNetworking.send(player, new MenuDataPayload(
                SessionManager.isMaster(player),
                SessionManager.isActivePlayer(player),
                SessionManager.getSessionName(),
                SessionManager.getMode().getDisplayName(),
                SessionManager.getActivePlayerName(),
                playerNames
        ));
        sendLockToPlayer(player);
    }

    /**
     * Atualiza o estado de trava de todos os jogadores (mudanças de modo/turno).
     * NÃO envia o MenuDataPayload, para não abrir o menu automaticamente.
     * Também sincroniza o jogador ativo (turno) para o carrossel de espectador.
     */
    public static void sendToAll(MinecraftServer server) {
        if (server == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sendLockToPlayer(player);
            sendActivePlayerToPlayer(player);
        }
    }

    /** Envia o estado atual das auras de limite para todos os jogadores. */
    public static void sendAuraStateToAll(MinecraftServer server) {
        if (server == null) {
            return;
        }
        AuraStatePayload payload = new AuraStatePayload(CombatController.getAuraData(server));
        TabletopRpg.LOGGER.info("[TabletopRPG] sendAuraStateToAll: enviando {} aura(s)", payload.auras().size());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    /** Envia a distância do highlight para um jogador (vale para todos, inclusive o mestre). */
    public static void sendHoverConfigToPlayer(ServerPlayer player) {
        if (player == null || player.connection == null) {
            return;
        }
        int maxDistance = SessionManager.getHoverDistance();
        ServerPlayNetworking.send(player, new HoverConfigPayload(maxDistance));
    }

    /** Envia a distância do highlight para todos os jogadores. */
    public static void sendHoverConfigToAll(MinecraftServer server) {
        if (server == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sendHoverConfigToPlayer(player);
        }
    }

    /** Envia o estado atual da permissão de quebra de blocos para todos os jogadores. */
    public static void sendBlockBreakSettingStateToAll(MinecraftServer server) {
        if (server == null) {
            return;
        }
        BlockBreakSettingStatePayload payload = new BlockBreakSettingStatePayload(SessionManager.canPlayersBreakBlocks());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    /** Envia o estado atual da permissão de colocação de blocos para todos os jogadores. */
    public static void sendPlaceBlockSettingStateToAll(MinecraftServer server) {
        if (server == null) {
            return;
        }
        PlaceBlockSettingStatePayload payload = new PlaceBlockSettingStatePayload(SessionManager.canPlayersPlaceBlocks());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    /** Envia o clima atual (estado alvo escolhido pelo mestre) para todos os jogadores. */
    public static void sendWeatherStateToAll(MinecraftServer server) {
        if (server == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sendWeatherStateToPlayer(player);
        }
    }

    /** Envia o clima atual (estado alvo escolhido pelo mestre) para um jogador. */
    public static void sendWeatherStateToPlayer(ServerPlayer player) {
        if (player == null || player.connection == null) {
            return;
        }
        ServerPlayNetworking.send(player, new WeatherStatePayload(SessionManager.getWeatherTarget()));
    }

    /**
     * Monta a lista de alvos do carrossel de espectador: todos os jogadores
     * conectados (EXCETO o mestre — ele não pode ser espectado) + mobs
     * invocados com câmera (cam_perm=true) que ainda existem no mundo.
     */
    public static List<SpectatorTargetsPayload.TargetData> getSpectatorTargets(MinecraftServer server) {
        List<SpectatorTargetsPayload.TargetData> list = new ArrayList<>();
        if (server == null) {
            return list;
        }
        // Auto-recuperação: após reiniciar o servidor, os mobs com câmera
        // (marcados no NBT em insertEnemy) são re-registrados no carrossel.
        CombatController.selfHealCameraMobs(server);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (SessionManager.isMaster(p)) {
                continue; // o mestre não pode ser espectado
            }
            list.add(new SpectatorTargetsPayload.TargetData(p.getId(), p.getName().getString(), true));
        }
        for (UUID uuid : CombatController.getCameraMobs()) {
            for (ServerLevel level : server.getAllLevels()) {
                Entity entity = level.getEntity(uuid);
                if (entity instanceof Mob mob) {
                    // Trunca o nome em 64 chars: o codec stringUtf8(64) lança
                    // exceção se o nome for maior (nomes customizados via NBT
                    // podem passar de 64).
                    String name = mob.getName().getString();
                    if (name.length() > 64) {
                        name = name.substring(0, 64);
                    }
                    list.add(new SpectatorTargetsPayload.TargetData(mob.getId(), name, false));
                    break;
                }
            }
        }
        return list;
    }

    /** Envia a lista de alvos do carrossel para um jogador. */
    public static void sendSpectatorTargetsToPlayer(ServerPlayer player) {
        if (player == null || player.connection == null) {
            return;
        }
        MinecraftServer server = player.level().getServer();
        ServerPlayNetworking.send(player, new SpectatorTargetsPayload(getSpectatorTargets(server)));
    }

    /** Envia a lista de alvos do carrossel para todos os jogadores. */
    public static void sendSpectatorTargetsToAll(MinecraftServer server) {
        if (server == null) {
            return;
        }
        SpectatorTargetsPayload payload = new SpectatorTargetsPayload(getSpectatorTargets(server));
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    /** Envia o UUID do jogador ativo (turno) para um jogador. */
    public static void sendActivePlayerToPlayer(ServerPlayer player) {
        if (player == null || player.connection == null) {
            return;
        }
        UUID activeUuid = SessionManager.getActivePlayerUuid();
        ServerPlayNetworking.send(player, new ActivePlayerPayload(activeUuid == null ? "" : activeUuid.toString()));
    }
}