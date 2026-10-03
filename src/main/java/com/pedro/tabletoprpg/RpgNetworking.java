package com.pedro.tabletoprpg;

import com.pedro.tabletoprpg.item.ModItems;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
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
import net.minecraft.world.item.ItemStack;
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
 *   <li>{@link SheetSkillPayload} (C2S): adiciona/remove/reordena/edita uma skill da ficha.</li>
 *   <li>{@link SheetItemPayload} (C2S): cria/edita/apaga um item do inventario da ficha.</li>
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

    /**
     * Servidor -> Cliente: abre a tela do Sheet Editor.
     *
     * <p>27/09/2026, primeira fatia do editor. Nao leva <b>nenhum dado</b>: e
     * apenas o sinal de "abre a tela". A tela mostra a lista de pericias que ela
     * le do codigo, entao ainda nao existe modelo em trafego.
     *
     * <p><b>Alem do que parece:</b> o servidor so envia isto depois de conferir
     * {@code SessionManager.isMaster}. Sem isso, um cliente forjado abriria a tela
     * do Mestre So apertando a tecla que o pacote representa. O cliente tambem
     * pode chamar {@code setScreen} direto na mao, por isso o filtro <b>precisa
     * ser no servidor</b>, e nao no clique.
     */
    public record OpenSheetEditorPayload() implements CustomPacketPayload {
        public static final Type<OpenSheetEditorPayload> TYPE =
                new Type<>(TabletopRpg.id("open_sheet_editor"));
        public static final StreamCodec<FriendlyByteBuf, OpenSheetEditorPayload> STREAM_CODEC =
                StreamCodec.unit(new OpenSheetEditorPayload());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Servidor -&gt; Cliente: o <b>modelo</b> da ficha.
     *
     * <p>27/09/2026 (Sheet Editor). O modelo e global, entao vai para todo mundo
     * e nao so para o Mestre: e ele que desenha "Name", "Race", "Strength" e a
     * lista de pericias na ficha de cada jogador. Manda para todos, porque um
     * jogador comum tambem ve a ficha dos outros.
     *
     * <p><b>Nao substitui a ficha, substitui o MOLDE.</b> Os valores continuam
     * indo pelo {@code SheetStatePayload}: e por isso que este pacote pode ser
     * reenviado a cada edicao do Mestre sem perder o que o jogador digitou.
     *
     * <p>Vai no JOIN e de novo depois de cada edicao.
     */
    public record SheetModelPayload(SheetModel model) implements CustomPacketPayload {
        public static final Type<SheetModelPayload> TYPE =
                new Type<>(TabletopRpg.id("sheet_model"));
        public static final StreamCodec<FriendlyByteBuf, SheetModelPayload> STREAM_CODEC =
                StreamCodec.composite(SheetModel.STREAM_CODEC, SheetModelPayload::model, SheetModelPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Cliente (Mestre) -&gt; Servidor: o modelo inteiro, ja montado.
     *
     * <p><b>Por que o modelo inteiro e nao uma operacao ("renomear X"):</b> a tela
     * do Mestre tem um botao <b>Salvar</b>, entao o que o cliente faz e editar uma
     * copia local e enviar o resultado de uma vez. Mandar uma operacao por
     * mudanca seria varios pacotes, nenhum deles atomico: se o terceiro chegasse
     * depois de um comando do servidor, a tela mostraria um modelo hibrido que
     * ninguem salvou de fato.
     *
     * <p><b>Isso nao da poder de escrever no mundo.</b> O construtor compacto de
     * {@link SheetModel} limita cada rotulo a {@code LABEL_MAX}, deduplica
     * atributos e pericias e corta as listas nos maximos (10 e 30) — e ele roda
     * <b>antes</b> do servidor ver o objeto, porque o {@code record} e
     * reconstruido na desserializacao. Ou seja: um cliente forjado pode enviar
     * um modelo invalido, mas ele chega saneado. O servidor ainda confine a
     * checagem de Mestre, e ainda decide se grava.
     */
    public record SheetModelSavePayload(SheetModel model) implements CustomPacketPayload {
        public static final Type<SheetModelSavePayload> TYPE =
                new Type<>(TabletopRpg.id("sheet_model_save"));
        public static final StreamCodec<FriendlyByteBuf, SheetModelSavePayload> STREAM_CODEC =
                StreamCodec.composite(SheetModel.STREAM_CODEC, SheetModelSavePayload::model, SheetModelSavePayload::new);

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

    /**
     * Servidor -> Cliente: retrato de tudo que esta trancado, mais a flag de
     * Mestre <b>deste</b> jogador.
     *
     * <p><b>Por que a flag viaja aqui e nao numa payload so do Mestre:</b> o
     * cliente precisa saber se <i>ele</i> e o Mestre para decidir se desenha a
     * aura. Como o payload vai para cada jogador com o valor proprio, um
     * jogador comum recebe {@code false} e nunca ve nada.
     *
     * <p><b>Por que o retrato inteiro, e nao um delta:</b> trancas sao poucas e
     * mudam raramente. O estado completo evita o modo de falha caro: o cliente
     * ficar com uma posicao trancada que o servidor ja destravou (ou o
     * contrario) por causa de um estado que os dois lados discordam. Como o
     * transporte e ordenado e confiavel, reenviar tudo converge sem manutencao
     * de estado incremental.
     */
    public record BlockLocksPayload(boolean isMaster, List<BlockLockEntry> locks) implements CustomPacketPayload {
        public static final Type<BlockLocksPayload> TYPE = new Type<>(TabletopRpg.id("block_locks"));

        public static final StreamCodec<FriendlyByteBuf, BlockLocksPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, BlockLocksPayload::isMaster,
                BlockLockEntry.STREAM_CODEC.apply(ByteBufCodecs.list()), BlockLocksPayload::locks,
                BlockLocksPayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Uma posicao trancada como vai pela rede: id da dimensao em texto e a
     * posicao.
     *
     * <p><b>Por que dimensao em texto e nao o {@code ResourceKey}:</b> o cliente
     * so precisa comparar o id com o da dimensao em que esta. Serializar o
     * {@code ResourceKey} exigiria o codec dele no cliente tambem, sem ganho
     * nenhum.
     */
    public record BlockLockEntry(String dimension, BlockPos pos) {
        public static final StreamCodec<FriendlyByteBuf, BlockLockEntry> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, BlockLockEntry::dimension,
                BlockPos.STREAM_CODEC, BlockLockEntry::pos,
                BlockLockEntry::new
        );
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
     * jogador. <b>Todos recebem o mesmo valor</b>, inclusive o mestre: o que vale e
     * {@link SessionManager#getHoverDistance()}, ajustado por /rpg hoverdistance
     * (padrao 32). Antes este texto afirmava que o mestre recebia "um valor alto
     * (ilimitado na pratica)" e a memoria do projeto falava em 1024 blocos — nao
     * existe 1024 em lugar nenhum do codigo. Decisao do usuario em 27/09/2026: o
     * comportamento atual (mesmo valor para todos) foi mantido e so o texto foi
     * corrigido.
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
     *
     * <p><b>30/09/2026: o teto do {@code value} foi de 64 para 2048.</b> Os dois
     * textos longos da aba {@code Info/Inventory} ({@code appearance} e
     * {@code backstory}) tem teto de {@link SheetData#MAX_TEXT}, que sao 2000
     * caracteres, e {@code stringUtf8(64)} derrubaria a conexao ao decodificar.
     * <b>Por que 2048 e nao 2000:</b> o {@code stringUtf8} nao corta, ele lanca
     * excecao. O valor precisa caber inteiro no pacote <i>antes</i> do
     * {@code clean()} do servidor truncar, com folga para o {@code trim} e para
     * uma diferenca de contagem entre o widget e o servidor; com o teto colado no
     * limite, o pacote estouraria no servidor e a conexao cairia em vez de o
     * servidor apenas cortar. O {@code field} continua em 32: {@code appearance}
     * e {@code backstory} tem 9 letras cada.
     */
    public record SheetFieldPayload(String targetName, String field, String value) implements CustomPacketPayload {
        public static final Type<SheetFieldPayload> TYPE = new Type<>(TabletopRpg.id("sheet_field"));
        public static final StreamCodec<FriendlyByteBuf, SheetFieldPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(64), SheetFieldPayload::targetName,
                ByteBufCodecs.stringUtf8(32), SheetFieldPayload::field,
                ByteBufCodecs.stringUtf8(2048), SheetFieldPayload::value,
                SheetFieldPayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Cliente -&gt; Servidor: adiciona, remove, reordena ou <b>edita</b> uma
     * <b>skill</b> (nome + descrição).
     *
     * <p>A skill é a lista <b>live</b> da ficha: o jogador monta do zero o que
     * ele sabe fazer. Por isso este payload não viaja valor nem atributo — o que
     * uma perícia soma na rolagem é assunto da {@link SheetPericiaPayload}.
     * Os dois são pacotes diferentes justamente para que mexer na lista de
     * perícias não possa alterar a lista de skills (e vice-versa).
     *
     * <p><b>29/09/2026 - o campo {@code index}.</b> Ele e' o que o
     * {@link SheetData.SkillOp#UPDATE} usa: o botao Edit da tela de Skills
     * salva <b>no mesmo lugar</b> da lista, e "no mesmo lugar" so pode ser
     * dito por posicao - o nome sozinho nao sobrevive a uma reordenacao feita
     * entre a selecao e o "Save". Nas outras operacoes o campo e' ignorado e
     * vale 0.
     *
     * <p><b>Por que um {@code int} cru e nao um indice validado aqui:</b> o
     * payload nao valida nada; quem valida e' o servidor, no receptor (ver
     * {@code updateSkill}), que so grava quando o indice esta na faixa E o
     * nome em diante bate com a skill que esta la.
     */
    public record SheetSkillPayload(String targetName, String skill, SheetData.SkillOp op,
                                    String description, int index) implements CustomPacketPayload {
        public static final Type<SheetSkillPayload> TYPE = new Type<>(TabletopRpg.id("sheet_skill"));
        public static final StreamCodec<FriendlyByteBuf, SheetSkillPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(64), SheetSkillPayload::targetName,
                ByteBufCodecs.stringUtf8(SheetData.SKILL_MAX), SheetSkillPayload::skill,
                SheetData.SkillOp.STREAM_CODEC, SheetSkillPayload::op,
                ByteBufCodecs.stringUtf8(SheetData.SKILL_DESC_MAX), SheetSkillPayload::description,
                // O indice por ULTIMO, como os tres limites de SheetData: os
                // campos antigos ficam nas mesmas posicoes, e o decode
                // (StreamCodec.composite monta encoder e decoder juntos) le
                // nesta mesma ordem.
                ByteBufCodecs.VAR_INT, SheetSkillPayload::index,
                SheetSkillPayload::new
        );

        /** Atalho: criar a skill, ou atualizar a descrição se o nome já existir. */
        public static SheetSkillPayload add(String targetName, String skill, String description) {
            return new SheetSkillPayload(targetName, skill, SheetData.SkillOp.ADD, description, 0);
        }

        /** Atalho: remover a skill pelo nome (só o nome importa). */
        public static SheetSkillPayload remove(String targetName, String skill) {
            return new SheetSkillPayload(targetName, skill, SheetData.SkillOp.REMOVE, "", 0);
        }

        /**
         * Atalho: mover a skill um passo na lista (setas da tela de Skills).
         *
         * <p>O passo viaja no campo {@code description} porque ela e' o que sobra
         * livre nesta operacao: {@code delta = -1} sobe, {@code +1} desce.
         */
        public static SheetSkillPayload move(String targetName, String skill, int delta) {
            return new SheetSkillPayload(targetName, skill, SheetData.SkillOp.MOVE, Integer.toString(delta), 0);
        }

        /**
         * Atalho: salvar a edicao da skill que esta no indice, no mesmo lugar da
         * lista (botao "Save" do modo de edicao da tela de Skills).
         *
         * <p>Alem do indice, o nome em diante tambem viaja: e' a trava que
         * impede um "Save" atrasado de sobrescrever a skill que o jogador
         * passou a ter naquela posicao enquanto ele editava.
         */
        public static SheetSkillPayload update(String targetName, int index, String skill, String description) {
            return new SheetSkillPayload(targetName, skill, SheetData.SkillOp.UPDATE, description, index);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Cliente -&gt; Servidor: cria, edita ou apaga um <b>item do inventario</b>
     * (30/09/2026, FASE 2B).
     *
     * <p>O limite de peso do inventario <b>nao vem aqui</b>: ele e um campo de
     * texto da ficha e viaja no {@link SheetFieldPayload} com a chave
     * {@code maxWeight}, que o {@link SheetData#withField} ja resolve. Este
     * pacote so carrega a lista de itens, porque editar um item manda as quatro
     * colunas dele de uma vez e um payload de texto nao expressa isso.
     *
     * <p><b>O item viaja como record e nao como quatro strings</b> porque o
     * peso e um {@code float}: o {@code ByteBufCodecs.FLOAT} e' o mesmo
     * caminho do {@link SheetData.InventoryItem#STREAM_CODEC}, e um cliente
     * forjado nao escapa do teto porque o construtor compacto do record corta
     * o que passou.
     *
     * <p><b>O indice e' do {@code UPDATE} e do {@code REMOVE}</b>, pelo mesmo
     * motivo do {@link SheetSkillPayload}: enquanto o jogador edita, o item pode
     * ter sido apagado, e "o mesmo lugar" so pode ser dito por posicao. No
     * {@code ADD} o indice e' {@code -1} e ignorado.
     */
    public record SheetItemPayload(String targetName, SheetData.ItemOp op, int index,
                                   SheetData.InventoryItem item) implements CustomPacketPayload {
        public static final Type<SheetItemPayload> TYPE = new Type<>(TabletopRpg.id("sheet_item"));
        public static final StreamCodec<FriendlyByteBuf, SheetItemPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(64), SheetItemPayload::targetName,
                SheetData.ItemOp.STREAM_CODEC, SheetItemPayload::op,
                ByteBufCodecs.VAR_INT, SheetItemPayload::index,
                SheetData.InventoryItem.STREAM_CODEC, SheetItemPayload::item,
                SheetItemPayload::new
        );

        /** Atalho: criar um item novo (o indice nao importa no ADD). */
        public static SheetItemPayload add(String targetName, SheetData.InventoryItem item) {
            return new SheetItemPayload(targetName, SheetData.ItemOp.ADD, -1, item);
        }

        /**
         * Atalho: apagar o item do indice.
         *
         * <p>O {@code item} e' o {@link SheetData.InventoryItem#EMPTY}, e nao
         * {@code null}: o codec do record le os quatro campos sempre, e um
         * {@code null} aqui derrubaria o pacote inteiro no cliente em vez de
         * exigir o campo.
         */
        public static SheetItemPayload remove(String targetName, int index) {
            return new SheetItemPayload(targetName, SheetData.ItemOp.REMOVE, index, SheetData.InventoryItem.EMPTY);
        }

        /** Atalho: salvar a edicao do item que esta no indice, no mesmo lugar. */
        public static SheetItemPayload update(String targetName, int index, SheetData.InventoryItem item) {
            return new SheetItemPayload(targetName, SheetData.ItemOp.UPDATE, index, item);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Cliente -&gt; Servidor: cria/edita/apaga uma <b>magia</b> da pagina 3 da
     * ficha (01/10/2026).
     *
     * <p><b>Por que um pacote so, e nao um por magia:</b> a identidade de uma
     * magia e o <b>indice na lista guardada</b>, igual ao item do inventario
     * (ver {@link SheetItemPayload}). O indice nao sobrevive a reordenacao, e por
     * isso que {@link SheetData.Spellbook#visible(int)} devolve o indice
     * <b>guardado</b> e nao a posicao exibida: o filtro ordena a tela, nunca a
     * lista.
     *
     * <p><b>Nao existe {@code MOVE}:</b> a ordem exibida e sempreautomaticamente
     * (circulo crescente, depois alfabetica), entao o jogador nao tem o que
     * mover.
     *
     * <p><b>Por que o atributo de conjuracao e a CD nao vem aqui:</b> sao
     * globais da pagina e nao pertencem a uma magia, entao viajam pelo
     * {@link SheetFieldPayload} como os outros campos simples da ficha.
     */
    public record SheetSpellPayload(String targetName, SheetData.SpellOp op, int index,
                                    SheetData.Spell spell) implements CustomPacketPayload {
        public static final Type<SheetSpellPayload> TYPE = new Type<>(TabletopRpg.id("sheet_spell"));
        public static final StreamCodec<FriendlyByteBuf, SheetSpellPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(64), SheetSpellPayload::targetName,
                SheetData.SpellOp.STREAM_CODEC, SheetSpellPayload::op,
                ByteBufCodecs.VAR_INT, SheetSpellPayload::index,
                SheetData.Spell.STREAM_CODEC, SheetSpellPayload::spell,
                SheetSpellPayload::new
        );

        /** Atalho: criar uma magia nova (o indice nao importa no ADD). */
        public static SheetSpellPayload add(String targetName, SheetData.Spell spell) {
            return new SheetSpellPayload(targetName, SheetData.SpellOp.ADD, -1, spell);
        }

        /**
         * Atalho: apagar a magia do indice.
         *
         * <p>O {@code spell} e' o {@link SheetData.Spell#EMPTY}, e nao
         * {@code null}: o codec do record le os sete campos sempre, e um
         * {@code null} aqui derrubaria o pacote inteiro no cliente em vez de
         * exigir o campo. Mesmo motivo do {@link SheetItemPayload#remove}.
         */
        public static SheetSpellPayload remove(String targetName, int index) {
            return new SheetSpellPayload(targetName, SheetData.SpellOp.REMOVE, index, SheetData.Spell.EMPTY);
        }

        /** Atalho: salvar a edicao da magia que esta no indice, no mesmo lugar. */
        public static SheetSpellPayload update(String targetName, int index, SheetData.Spell spell) {
            return new SheetSpellPayload(targetName, SheetData.SpellOp.UPDATE, index, spell);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Cliente -&gt; Servidor: muda o <b>valor</b> ou o <b>atributo</b> de uma perícia.
     *
     * <p>Não existe "add" nem "remove" aqui, de propósito. A lista de perícias
     * pertence ao <b>modelo</b> (que o Mestre muda no Sheet Editor), e este
     * pacote existe só para o jogador ajustar o valor e a escolha de atributo de
     * uma perícia que <b>já existe</b> na ficha. Criar e remover é trabalho do
     * editor, e o servidor recusa uma perícia fora do modelo: se o id não casar,
     * {@code withPericiaValue} devolve a ficha intacta.
     *
     * <p><b>27/09/2026:</b> o campo {@code attribute} virou {@code attributeId}
     * do tipo {@code String}. Antes era o enum {@code SheetData.Attribute},
     * removido junto com os seis campos fixos da ficha. O id é a chave estável;
     * um id fora do modelo é ignorado.
     *
     * <p><b>28/09/2026:</b> o campo {@code pericia} virou {@code periciaId}.
     * Mesmo numero de campos, mesmo codec e mesma ordem - so o nome e o que
     * mudou - porque agora o que identifica a pericia e o {@code id} dela (o
     * nome pode ter sido alterado pelo Mestre, o id nao). Viajar o nome fazia o
     * servidor nao achar a pericia depois de um rename, e o valor zerava.
     */
    public record SheetPericiaPayload(String targetName, String periciaId, SheetData.PericiaOp op, int value,
                                      String attributeId) implements CustomPacketPayload {
        public static final Type<SheetPericiaPayload> TYPE = new Type<>(TabletopRpg.id("sheet_pericia"));
        public static final StreamCodec<FriendlyByteBuf, SheetPericiaPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(64), SheetPericiaPayload::targetName,
                ByteBufCodecs.stringUtf8(SheetData.SKILL_MAX), SheetPericiaPayload::periciaId,
                SheetData.PericiaOp.STREAM_CODEC, SheetPericiaPayload::op,
                ByteBufCodecs.VAR_INT, SheetPericiaPayload::value,
                ByteBufCodecs.stringUtf8(32), SheetPericiaPayload::attributeId,
                SheetPericiaPayload::new
        );

        /**
         * Atalho: mexer só no valor (setas da lista de perícias no Status).
         *
         * <p>Manda {@code attributeId} vazio porque, em {@code SET_VALUE}, o
         * servidor nem olha o atributo. Não existe mais um "DEXTERITY" padrão
         * para inventar: o atributo de uma perícia nova é escolhido pelo Mestre.
         */
        public static SheetPericiaPayload setValue(String targetName, String periciaId, int value) {
            return new SheetPericiaPayload(targetName, periciaId, SheetData.PericiaOp.SET_VALUE, value, "");
        }

        /** Atalho: mexer só no atributo (botão de lista suspensa). */
        public static SheetPericiaPayload setAttribute(String targetName, String periciaId, String attributeId) {
            return new SheetPericiaPayload(targetName, periciaId, SheetData.PericiaOp.SET_ATTRIBUTE, 0, attributeId);
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

    /**
     * Cliente -&gt; Servidor: cria um preset de rolagem a partir do botao da tela de
     * rolagem.
     *
     * <p><b>Por que um pacote e nao um comando montado pelo cliente:</b> o preset e
     * salvo no NBT da jogadora e a validacao da formula tem de rodar no servidor -- se
     * o cliente montasse o comando, a regra de "formula desconhecida" teria duas
     * implementacoes. Enviando os tres campos crus, o servidor chama exatamente o
     * {@link RollPreset#create} que o comando usa, entao as duas portas dizem a mesma
     * coisa na cara da mesma falha.
     *
     * <p>Os limites de tamanho batem com o {@code ByteBufCodecs} porque o construtor
     * do record corta no mesmo numero: se divergissem, o valor gravado seria rejeitado
     * na leitura do outro lado do pacote.
     */
    public record PresetCreatePayload(String name, String formula, String colorId)
            implements CustomPacketPayload {
        public static final Type<PresetCreatePayload> TYPE = new Type<>(TabletopRpg.id("preset_create"));
        public static final StreamCodec<FriendlyByteBuf, PresetCreatePayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.stringUtf8(64), PresetCreatePayload::name,
                        ByteBufCodecs.stringUtf8(128), PresetCreatePayload::formula,
                        ByteBufCodecs.stringUtf8(32), PresetCreatePayload::colorId,
                        PresetCreatePayload::new
                );

        public PresetCreatePayload {
            name = clamp(name, 64);
            formula = clamp(formula, 128);
            colorId = clamp(colorId, 32);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Servidor -&gt; Cliente: a lista de presets da jogadora, para a tela.
     *
     * <p><b>Por que o servidor manda a lista inteira e nao so um aviso:</b> a tela
     * precisa mostrar os nomes, as formulas e as cores, e a ordem em que a jogadora
     * montou. Sem isso, abrir a tela mostraria o que o cliente lembrava -- e o item do
     * preset pode ter sido editado por comando enquanto a tela estava fechada, ou o
     * preset pode ter sido criado em outra sessao.
     *
     * <p>Vem logo apos abrir a tela, entao a lista que ela mostra e a do servidor.
     */
    public record PresetListPayload(List<RollPreset> presets) implements CustomPacketPayload {
        public static final Type<PresetListPayload> TYPE = new Type<>(TabletopRpg.id("preset_list"));
        public static final StreamCodec<FriendlyByteBuf, PresetListPayload> STREAM_CODEC =
                new StreamCodec<>() {
                    @Override
                    public PresetListPayload decode(FriendlyByteBuf buf) {
                        int size = buf.readVarInt();
                        List<RollPreset> presets = new ArrayList<>();
                        for (int i = 0; i < size; i++) {
                            presets.add(RollPreset.STREAM_CODEC.decode(buf));
                        }
                        return new PresetListPayload(presets);
                    }

                    @Override
                    public void encode(FriendlyByteBuf buf, PresetListPayload payload) {
                        List<RollPreset> presets = payload.presets();
                        buf.writeVarInt(presets.size());
                        for (RollPreset preset : presets) {
                            RollPreset.STREAM_CODEC.encode(buf, preset);
                        }
                    }
                };

        public PresetListPayload {
            presets = presets == null ? List.of() : List.copyOf(presets);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Cliente -&gt; Servidor: salva um preset, criando ou editando.
     *
     * <p><b>Por que um pacote so para as duas coisas:</b> criar e editar tem o mesmo
     * corpo e a mesma resposta; o que muda e se {@code originalName} vem preenchido.
     * Um pacote por operacao duplicaria a valicao de nome, de formula e de cor, que e
     * justamente o que precisa ser igual nas duas.
     *
     * <p><b>Por que {@code originalName} viaja:</b> sem ele o servidor nao sabe qual
     * preset foi editado quando o nome mudou, e o preset velho continuaria na lista
     * com os dados novos por baixo da etiqueta antiga. E o que faz o item antigo ficar
     * orfao quando a jogadora renomeia.
     */
    public record PresetSavePayload(String originalName, String name, String formula, String colorId)
            implements CustomPacketPayload {
        public static final Type<PresetSavePayload> TYPE = new Type<>(TabletopRpg.id("preset_save"));
        public static final StreamCodec<FriendlyByteBuf, PresetSavePayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.stringUtf8(64), PresetSavePayload::originalName,
                        ByteBufCodecs.stringUtf8(64), PresetSavePayload::name,
                        ByteBufCodecs.stringUtf8(128), PresetSavePayload::formula,
                        ByteBufCodecs.stringUtf8(32), PresetSavePayload::colorId,
                        PresetSavePayload::new
                );

        public PresetSavePayload {
            originalName = clamp(originalName, 64);
            name = clamp(name, 64);
            formula = clamp(formula, 128);
            colorId = clamp(colorId, 32);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Cliente -&gt; Servidor: apaga o preset deste nome. O item fica na mochila. */
    public record PresetDeletePayload(String name) implements CustomPacketPayload {
        public static final Type<PresetDeletePayload> TYPE = new Type<>(TabletopRpg.id("preset_delete"));
        public static final StreamCodec<FriendlyByteBuf, PresetDeletePayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.stringUtf8(64), PresetDeletePayload::name,
                        PresetDeletePayload::new
                );

        public PresetDeletePayload {
            name = clamp(name, 64);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Cliente -&gt; Servidor: uma das setas de reordenar.
     *
     * <p><b>Por que so o sentido e nao os dois indices:</b> "moveu uma casa para cima"
     * e um evento, e um evento nao fica invalido se a lista mudou no meio do caminho.
     * Trocar por dois indices transformaria cada clique em uma operacao que depende
     * da lista que o cliente tinha, e um preset criado por comando no intervalo faria a
     * seta trocar o preset errado.
     */
    public record PresetMovePayload(String name, boolean up) implements CustomPacketPayload {
        public static final Type<PresetMovePayload> TYPE = new Type<>(TabletopRpg.id("preset_move"));
        public static final StreamCodec<FriendlyByteBuf, PresetMovePayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.stringUtf8(64), PresetMovePayload::name,
                        ByteBufCodecs.BOOL, PresetMovePayload::up,
                        PresetMovePayload::new
                );

        public PresetMovePayload {
            name = clamp(name, 64);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Servidor -&gt; Cliente: resposta de qualquer operacao da tela de presets.
     *
     * <p><b>Por que o save e o delete compartilham a mesma resposta:</b> os dois
     * precisam dizer a mesma coisa quando dá errado (nome vazio, formula invalida,
     * nome repetido) e carregar a lista nova para a tela se atualizar. Uma resposta so
     * reduz o numero de registros de pacote e deixa o tratame umto na tela.
     */
    public record PresetResultPayload(boolean ok, String message, List<RollPreset> presets)
            implements CustomPacketPayload {
        public static final Type<PresetResultPayload> TYPE =
                new Type<>(TabletopRpg.id("preset_result"));
        public static final StreamCodec<FriendlyByteBuf, PresetResultPayload> STREAM_CODEC =
                new StreamCodec<>() {
                    @Override
                    public PresetResultPayload decode(FriendlyByteBuf buf) {
                        boolean ok = buf.readBoolean();
                        String message = ByteBufCodecs.stringUtf8(512).decode(buf);
                        int size = buf.readVarInt();
                        List<RollPreset> presets = new ArrayList<>();
                        for (int i = 0; i < size; i++) {
                            presets.add(RollPreset.STREAM_CODEC.decode(buf));
                        }
                        return new PresetResultPayload(ok, message, presets);
                    }

                    @Override
                    public void encode(FriendlyByteBuf buf, PresetResultPayload payload) {
                        buf.writeBoolean(payload.ok());
                        ByteBufCodecs.stringUtf8(512).encode(buf, payload.message());
                        List<RollPreset> presets = payload.presets();
                        buf.writeVarInt(presets.size());
                        for (RollPreset preset : presets) {
                            RollPreset.STREAM_CODEC.encode(buf, preset);
                        }
                    }
                };

        public PresetResultPayload {
            message = clamp(message, 512);
            presets = presets == null ? List.of() : List.copyOf(presets);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Servidor -&gt; Cliente: resposta do {@link PresetCreatePayload}.
     *
     * <p><b>Por que a resposta volta em texto e nao em chave + argumentos:</b> a tela
     * mostra a mensagem numa linha de status e nao sabe traduzir sozinha. O mod tem um
     * unico arquivo de idioma ({@code en_us}), entao resolver a chave no servidor e
     * mandar o texto pronto da a mesma frase que o comando mostra, sem duplicar a
     * tabela de argumentos. Se um dia houver segundo idioma, este e o ponto a mudar:
     * passar a chave e os valores, e deixar o cliente montar o {@code Component}.
     *
     * <p>{@code ok} decide se a tela fecha. Com {@code false} a tela fica aberta e
     * mostra a frase, porque a jogadora ainda tem que corrigir o que digitou.
     */
    public record PresetCreateResultPayload(boolean ok, String message) implements CustomPacketPayload {
        public static final Type<PresetCreateResultPayload> TYPE =
                new Type<>(TabletopRpg.id("preset_create_result"));
        public static final StreamCodec<FriendlyByteBuf, PresetCreateResultPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.BOOL, PresetCreateResultPayload::ok,
                        ByteBufCodecs.stringUtf8(512), PresetCreateResultPayload::message,
                        PresetCreateResultPayload::new
                );

        public PresetCreateResultPayload {
            message = clamp(message, 512);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Cliente -&gt; Servidor: "me manda as fichas de ameaça". So o Mestre pede. */
    public record ThreatSheetListRequestPayload() implements CustomPacketPayload {
        public static final Type<ThreatSheetListRequestPayload> TYPE =
                new Type<>(TabletopRpg.id("threat_sheet_list_request"));
        /** Nao transporta nada; o proprio tipo do pacote e a mensagem. */
        public static final StreamCodec<FriendlyByteBuf, ThreatSheetListRequestPayload> STREAM_CODEC =
                StreamCodec.unit(new ThreatSheetListRequestPayload());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Servidor -&gt; Cliente: a lista de fichas do Mestre, na ordem em que ele salvou.
     *
     * <p><b>Por que a ficha inteira viaja e nao so o nome:</b> a lista e pequena e a
     * ficha salva e um clique a mais de abrir. Se no dia vier a coluna de ND e de tipo
     * na lista, o dado ja esta no cliente e nao ha segunda ida ao servidor.
     */
    public record ThreatSheetListPayload(List<ThreatSheet> sheets) implements CustomPacketPayload {
        public static final Type<ThreatSheetListPayload> TYPE =
                new Type<>(TabletopRpg.id("threat_sheet_list"));
        public static final StreamCodec<FriendlyByteBuf, ThreatSheetListPayload> STREAM_CODEC =
                new StreamCodec<>() {
                    @Override
                    public ThreatSheetListPayload decode(FriendlyByteBuf buf) {
                        int size = buf.readVarInt();
                        List<ThreatSheet> sheets = new ArrayList<>();
                        for (int i = 0; i < size; i++) {
                            sheets.add(ThreatSheet.STREAM_CODEC.decode(buf));
                        }
                        return new ThreatSheetListPayload(sheets);
                    }

                    @Override
                    public void encode(FriendlyByteBuf buf, ThreatSheetListPayload payload) {
                        List<ThreatSheet> sheets = payload.sheets();
                        buf.writeVarInt(sheets.size());
                        for (ThreatSheet sheet : sheets) {
                            ThreatSheet.STREAM_CODEC.encode(buf, sheet);
                        }
                    }
                };

        public ThreatSheetListPayload {
            sheets = sheets == null ? List.of() : List.copyOf(sheets);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Cliente -&gt; Servidor: salvar a ficha aberta na tela.
     *
     * <p>{@code deliverItem} separa os dois botoes. "Salvar" (menu) entrega outro item
     * de ficha, como decide o Mestre em 02/10/2026; "Atualizar" (aberta pelo item) grava
     * por cima e NAO entrega nada, senao cada edicao multiplicaria o item na mochila.
     */
    public record ThreatSheetSavePayload(ThreatSheet sheet, String originalName, boolean deliverItem)
            implements CustomPacketPayload {
        public static final Type<ThreatSheetSavePayload> TYPE =
                new Type<>(TabletopRpg.id("threat_sheet_save"));

        public static final StreamCodec<FriendlyByteBuf, ThreatSheetSavePayload> STREAM_CODEC =
                StreamCodec.composite(
                        ThreatSheet.STREAM_CODEC, ThreatSheetSavePayload::sheet,
                        ByteBufCodecs.stringUtf8(ThreatSheet.MAX_NAME),
                        ThreatSheetSavePayload::originalName,
                        ByteBufCodecs.BOOL, ThreatSheetSavePayload::deliverItem,
                        ThreatSheetSavePayload::new
                );

        public ThreatSheetSavePayload {
            originalName = clamp(originalName == null ? "" : originalName.trim(),
                    ThreatSheet.MAX_NAME);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Servidor -&gt; Cliente: "abra esta ficha em modo de atualizacao".
     *
     * <p>Existe porque o clique no ar no item nao tem cliente para abrir tela: o item
     * carrega so o id, e a ficha editada precisa ser a do SERVIDOR. O servidor envia a
     * ficha pronta, e o cliente abre a tela em modo atualizacao (botao "Atualizar").
     */
    public record ThreatSheetOpenPayload(ThreatSheet sheet) implements CustomPacketPayload {
        public static final Type<ThreatSheetOpenPayload> TYPE =
                new Type<>(TabletopRpg.id("threat_sheet_open"));

        public static final StreamCodec<FriendlyByteBuf, ThreatSheetOpenPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ThreatSheet.STREAM_CODEC, ThreatSheetOpenPayload::sheet,
                        ThreatSheetOpenPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Cliente -&gt; Servidor: apagar uma ficha pela chave do nome. */
    public record ThreatSheetDeletePayload(String name) implements CustomPacketPayload {
        public static final Type<ThreatSheetDeletePayload> TYPE =
                new Type<>(TabletopRpg.id("threat_sheet_delete"));
        public static final StreamCodec<FriendlyByteBuf, ThreatSheetDeletePayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.stringUtf8(ThreatSheet.MAX_NAME),
                        ThreatSheetDeletePayload::name,
                        ThreatSheetDeletePayload::new
                );

        public ThreatSheetDeletePayload {
            name = clamp(name == null ? "" : name.trim(), ThreatSheet.MAX_NAME);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Servidor -&gt; Cliente: resposta de save/apag, sempre com a lista nova.
     *
     * <p><b>Por que a lista vem junto da resposta:</b> e o que faz a tela do Mestre
     * mostrar o estado real sem um segundo pedido -- e o mesmo motivo do
     * {@link PresetResultPayload}.
     */
    public record ThreatSheetResultPayload(boolean ok, String message, List<ThreatSheet> sheets)
            implements CustomPacketPayload {
        public static final Type<ThreatSheetResultPayload> TYPE =
                new Type<>(TabletopRpg.id("threat_sheet_result"));
        public static final StreamCodec<FriendlyByteBuf, ThreatSheetResultPayload> STREAM_CODEC =
                new StreamCodec<>() {
                    @Override
                    public ThreatSheetResultPayload decode(FriendlyByteBuf buf) {
                        boolean ok = buf.readBoolean();
                        String message = ByteBufCodecs.stringUtf8(512).decode(buf);
                        int size = buf.readVarInt();
                        List<ThreatSheet> sheets = new ArrayList<>();
                        for (int i = 0; i < size; i++) {
                            sheets.add(ThreatSheet.STREAM_CODEC.decode(buf));
                        }
                        return new ThreatSheetResultPayload(ok, message, sheets);
                    }

                    @Override
                    public void encode(FriendlyByteBuf buf, ThreatSheetResultPayload payload) {
                        buf.writeBoolean(payload.ok());
                        ByteBufCodecs.stringUtf8(512).encode(buf, payload.message());
                        List<ThreatSheet> sheets = payload.sheets();
                        buf.writeVarInt(sheets.size());
                        for (ThreatSheet sheet : sheets) {
                            ThreatSheet.STREAM_CODEC.encode(buf, sheet);
                        }
                    }
                };

        public ThreatSheetResultPayload {
            message = clamp(message, 512);
            sheets = sheets == null ? List.of() : List.copyOf(sheets);
        }

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
        PayloadTypeRegistry.playS2C().register(OpenSheetEditorPayload.TYPE,
                OpenSheetEditorPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(PlayerLockPayload.TYPE, PlayerLockPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(BlockLocksPayload.TYPE, BlockLocksPayload.STREAM_CODEC);
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
        // 30/09/2026 (FASE 2B): a lista de itens do inventario da ficha.
        PayloadTypeRegistry.playC2S().register(SheetItemPayload.TYPE, SheetItemPayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(PresetCreatePayload.TYPE, PresetCreatePayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(PresetCreateResultPayload.TYPE,
                PresetCreateResultPayload.STREAM_CODEC);
        // 01/10/2026: a tela de Presets (criar, editar, apagar, reordenar).
        PayloadTypeRegistry.playS2C().register(PresetListPayload.TYPE, PresetListPayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(PresetSavePayload.TYPE, PresetSavePayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(PresetDeletePayload.TYPE, PresetDeletePayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(PresetMovePayload.TYPE, PresetMovePayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(PresetResultPayload.TYPE, PresetResultPayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(PresetListRequestPayload.TYPE,
                PresetListRequestPayload.STREAM_CODEC);
        // 02/10/2026: fichas de ameaça do Mestre. Todos os payloads tem checagem de
        // Mestre no handler do servidor, porque a lista e de leitura e o resto e escrita.
        PayloadTypeRegistry.playC2S().register(ThreatSheetListRequestPayload.TYPE,
                ThreatSheetListRequestPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(ThreatSheetListPayload.TYPE,
                ThreatSheetListPayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(ThreatSheetSavePayload.TYPE,
                ThreatSheetSavePayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(ThreatSheetDeletePayload.TYPE,
                ThreatSheetDeletePayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(ThreatSheetResultPayload.TYPE,
                ThreatSheetResultPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(ThreatSheetOpenPayload.TYPE,
                ThreatSheetOpenPayload.STREAM_CODEC);
        // 02/10/2026: o Diario (notas em arvore, uma por jogador).
        PayloadTypeRegistry.playC2S().register(DiaryRequestPayload.TYPE, DiaryRequestPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(DiaryStatePayload.TYPE, DiaryStatePayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(DiaryCreatePayload.TYPE, DiaryCreatePayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(DiaryCreateChildPayload.TYPE,
                DiaryCreateChildPayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(DiarySavePayload.TYPE, DiarySavePayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(DiaryAcceptPayload.TYPE, DiaryAcceptPayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(DiaryDeletePayload.TYPE, DiaryDeletePayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(DiaryUndoPayload.TYPE, DiaryUndoPayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(DiaryPinPayload.TYPE, DiaryPinPayload.STREAM_CODEC);
        // 01/10/2026 (pagina 3 da ficha): a lista de magias.
        PayloadTypeRegistry.playC2S().register(SheetSpellPayload.TYPE, SheetSpellPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(DownedStatePayload.TYPE, DownedStatePayload.STREAM_CODEC);
        // Modelo global da ficha (27/09/2026, Sheet Editor). S2C para todo mundo
        // porque o modelo desenha a ficha dos OUTROS tambem; C2S porque a edicao
        // e sempre do Mestre.
        PayloadTypeRegistry.playS2C().register(SheetModelPayload.TYPE, SheetModelPayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(SheetModelSavePayload.TYPE, SheetModelSavePayload.STREAM_CODEC);
    }

    /**
     * Corta o texto no limite e trata nulo.
     *
     * <p><b>Por que no construtor do record e nao no {@code ByteBufCodecs}:</b> o
     * codec de escrita aceita qualquer tamanho e so recusa na leitura. Um campo maior
     * que o limite, entao, era gravado pelo cliente e explodia no servidor. Cortando
     * aqui, o valor que sai e o mesmo que o codec aceita.
     */
    private static String clamp(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
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
            // A aura (barreira azul) NAO era reenviada no JOIN: quem reconectava
            // voltava sem a barreira ate o proximo /rpg turn.
            sendAuraStateToPlayer(server, handler.getPlayer());
            // A lista de alvos do carrossel mudou (um jogador entrou): atualiza
            // também os jogadores já conectados, senão o novo jogador só
            // apareceria no carrossel deles no próximo broadcast.
            sendSpectatorTargetsToAll(server);
        });

        // Ao desconectar: se era o mestre, libera o cargo e pausa a sessão
        // (modo volta para FREE, turno limpo, combate resetado). Evita estado
        // quebrado (jogadores travados sem mestre). O turno de quem caiu nao e
        // limpo: fica reservado para o mestre pular com /rpg turn revoke.
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
            // Limpa o hover deste jogador (entrada do mapa por jogador).
            CombatController.clearHoveredEntity(player.getUUID());
            // O turno do jogador que caiu NAO e limpo: a vez dele fica reservada
            // e o mestre pode pular com /rpg turn revoke (decisao do usuario em
            // 27/09/2026).
            if (!SessionManager.isMaster(player) && SessionManager.isActivePlayer(player)) {
                server.getPlayerList().broadcastSystemMessage(
                        Component.literal("§6[RPG] §e" + player.getName().getString()
                                + " §fdisconnected. Their turn is reserved; the Master can skip it with /rpg turn revoke."), false);
            }
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
                case MOVE -> moveSkill(current, payload);
                case UPDATE -> updateSkill(current, payload);
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

        // Cria/edita/apaga um ITEM DO INVENTARIO (30/09/2026, FASE 2B). Mesma
        // regra de permissao dos outros campos: o Mestre edita qualquer ficha, o
        // jogador so a propria.
        ServerPlayNetworking.registerGlobalReceiver(SheetItemPayload.TYPE, (payload, context) -> {
            ServerPlayer sender = context.player();
            ServerPlayer target = resolveSheetTarget(sender, payload.targetName());
            if (target == null || !canEditSheet(sender, target)) {
                return;
            }
            SheetData current = SessionManager.getOrCreateSheet(target.getUUID(), target.getName().getString());
            SheetData updated = switch (payload.op() == null ? SheetData.ItemOp.INVALID : payload.op()) {
                case ADD -> current.withInventory(current.inventory().addItem(payload.item()));
                case UPDATE -> updateItem(current, payload);
                case REMOVE -> current.withInventory(current.inventory().removeItem(payload.index()));
                // Pacote corrompido: melhor nao fazer nada do que transformar
                // uma op invalida em "criar item".
                case INVALID -> current;
            };
            if (updated == current) {
                return; // indice fora da lista, lista cheia, item sem nome, ou nada mudou
            }
            SessionManager.setSheet(target.getUUID(), updated);
            broadcastSheet(target);
        });

        // Cria/edita/apaga uma MAGIA da pagina 3 (01/10/2026). Mesma regra de
        // permissao do item do inventario: o Mestre edita qualquer ficha, o
        // jogador so a propria.
        ServerPlayNetworking.registerGlobalReceiver(SheetSpellPayload.TYPE, (payload, context) -> {
            ServerPlayer sender = context.player();
            ServerPlayer target = resolveSheetTarget(sender, payload.targetName());
            if (target == null || !canEditSheet(sender, target)) {
                return;
            }
            SheetData current = SessionManager.getOrCreateSheet(target.getUUID(), target.getName().getString());
            SheetData updated = switch (payload.op() == null ? SheetData.SpellOp.INVALID : payload.op()) {
                case ADD -> current.withSpellAdded(payload.spell());
                case UPDATE -> updateSpell(current, payload);
                case REMOVE -> current.withSpellRemoved(payload.index());
                // Pacote corrompido: melhor nao fazer nada do que transformar
                // uma op invalida em "criar magia".
                case INVALID -> current;
            };
            if (updated == current) {
                return; // indice fora da lista, lista cheia, magia sem nome, ou nada mudou
            }
            SessionManager.setSheet(target.getUUID(), updated);
            broadcastSheet(target);
        });

        // Muda o VALOR ou o ATRIBUTO de uma PERÍCIA já existente. Criar e
        // remover perícia é do Mestre, no Sheet Editor; este pacote só ajusta os
        // dois campos, e vale a mesma regra de permissão dos outros campos da
        // ficha.
        ServerPlayNetworking.registerGlobalReceiver(SheetPericiaPayload.TYPE, (payload, context) -> {
            ServerPlayer sender = context.player();
            ServerPlayer target = resolveSheetTarget(sender, payload.targetName());
            if (target == null || !canEditSheet(sender, target)) {
                return;
            }
            SheetData current = SessionManager.getOrCreateSheet(target.getUUID(), target.getName().getString());
            SheetData updated = switch (payload.op() == null ? SheetData.PericiaOp.INVALID : payload.op()) {
                case SET_VALUE -> current.withPericiaValue(payload.periciaId(), payload.value());
                case SET_ATTRIBUTE -> current.withPericiaAttribute(payload.periciaId(), payload.attributeId());
                case INVALID -> current;
            };
            if (updated == current) {
                return; // perícia fora do modelo, ou o valor não mudou
            }
            SessionManager.setSheet(target.getUUID(), updated);
            broadcastSheet(target);
        });

        // Salvar o MODELO global. So o Mestre edita, e a checagem e aqui, no
        // servidor: um cliente forjado pode chamar o pacote que quiser.
        //
        // O modelo que chega ja passou pelo construtor compacto de SheetModel
        // (a desserializacao reconstrói o record), entao ja vem saneado. O que o
        // servidor ainda precisa decidir e se vale a pena gravar: um modelo
        // identico ao que ja estava so causaria realinhamento e broadcast sem
        // motivo, entao ele sai cedo.
        ServerPlayNetworking.registerGlobalReceiver(SheetModelSavePayload.TYPE, (payload, context) -> {
            ServerPlayer sender = context.player();
            if (!SessionManager.isMaster(sender)) {
                return;
            }
            MinecraftServer server = sender.level().getServer();
            SheetModelStore store = SheetModelStore.get(server);
            SheetModel next = payload.model();
            if (next == null) {
                return;
            }
            if (next.equals(store.model())) {
                return; // nada mudou de fato
            }
            store.update(next);

            // As fichas que ja existem em memoria ainda tem a lista antiga.
            // Re-alinhar aqui e o que faz o valor de quem sobreviveu a edicao
            // continuar no lugar, em vez da ficha precisar recarregar.
            int realinhadas = SessionManager.realignAllSheets();
            // Os limites entram no log (28/09/2026) pelo mesmo motivo dos nomes:
            // e a unica pista que fica no console de QUANTO o Mestre autorizou,
            // depois que a tela ja fechou. Sem isso, um valor cortado e um bug
            // de clamp ficam indistinguiveis de um valor que o Mestre digitou.
            TabletopRpg.LOGGER.info("[TabletopRPG] Mestre {} editou o modelo: {} atributo(s), {} pericia(s), "
                            + "atributo {}..{}, pericia 0..{}, {} ficha(s) realinhada(s).",
                    sender.getName().getString(), next.attributeCount(), next.periciaCount(),
                    next.attributeValueMin(), next.attributeValueMax(), next.periciaValueMax(),
                    realinhadas);

            broadcastSheetModel(server, next);
            // As fichas mudaram de forma (podem ter gained/lost linhas), entao
            // quem estiver olhando precisa receber a ficha nova tambem.
            for (ServerPlayer online : server.getPlayerList().getPlayers()) {
                SheetData sheet = SessionManager.getSheet(online.getUUID());
                if (sheet != null) {
                    broadcastSheet(online);
                }
            }
        });

        // Registra o receptor de pausar/retomar ciclo dia e noite no servidor
        registerDayCycleReceiver();

        // Preset de rolagem criado pela tela de rolagem (01/10/2026).
        registerRollPresetReceiver();
        registerThreatSheetReceiver();
        registerDiaryReceiver();
    }

    // ------------------------------------------------------------------
    // FICHA DO PERSONAGEM — RESOLUÇÃO DE ALVO, PERMISSÃO E ENVIO
    // ------------------------------------------------------------------

    /**
     * Aplica o {@code SkillOp.MOVE}: move a skill um passo na lista.
     *
     * <p>O passo viaja dentro do campo {@code description} do payload ("-1" sobe,
     * "+1" desce). Qualquer valor fora de -1/+1, ou texto nao numerico, e'
     * descartado e devolve a ficha intacta — um pacote adulterado nao pode
     * deslocar a skill para o fim da lista.
     */
    private static SheetData moveSkill(SheetData current, SheetSkillPayload payload) {
        int delta;
        try {
            delta = Integer.parseInt(payload.description() == null ? "" : payload.description().trim());
        } catch (NumberFormatException ex) {
            return current;
        }
        if (delta != -1 && delta != 1) {
            return current;
        }
        return current.withSkillMoved(payload.skill(), delta);
    }

    /**
     * Aplica o {@code SkillOp.UPDATE}: troca nome e descricao da skill que esta
     * no indice do payload, <b>no mesmo lugar</b> da lista.
     *
     * <p><b>Quatro testes, e o quarto e' o que segura a lista inteira:</b>
     * <ul>
     *   <li>indice dentro da faixa {@code [0, skills.size())};</li>
     *   <li>nome nao vazio (a skill continua tendo nome) e dentro do teto
     *       {@link SheetData#SKILL_MAX};</li>
     *   <li>descricao dentro do teto {@link SheetData#SKILL_DESC_MAX} (descricao
     *       vazia e valida: a skill so deixa de abrir popup);</li>
     *   <li><b>o nome em diante tem de ser o nome da skill que esta naquele
     *       indice.</b> E' o teste que segura o caso "alguem reordenou ou
     *       removeu a lista enquanto eu editava": sem ele, o Save gravaria por
     *       cima da skill que passou a estar naquela posicao, e o jogador
     *       perderia a descricao dela sem nenhum aviso.</li>
     * </ul>
     *
     * <p>Qualquer teste reprovado devolve a ficha intacta: o receptor ve
     * {@code updated == current} e sai sem gravar nem transmitir, entao nao ha
     * erro nem estouro - o pedido simplesmente nao aconteceu. E o mesmo
     * tratamento que o receptor ja dava a "skill inexistente no REMOVE".
     *
     * <p>A comparacao de nome e' sem diferenciar caixa, igual a
     * {@link SheetData#withoutSkill} e {@link SheetData#withSkillMoved}.
     */
    private static SheetData updateSkill(SheetData current, SheetSkillPayload payload) {
        int index = payload.index();
        if (index < 0 || index >= current.skills().size()) {
            return current;
        }
        String name = payload.skill() == null ? "" : payload.skill().trim();
        String description = payload.description() == null ? "" : payload.description();
        if (name.isEmpty() || name.length() > SheetData.SKILL_MAX
                || description.length() > SheetData.SKILL_DESC_MAX) {
            return current;
        }
        if (!current.skills().get(index).name().equalsIgnoreCase(name)) {
            return current;
        }
        return current.withSkill(index, name, description);
    }

    /**
     * Aplica o {@code ItemOp.UPDATE}: troca o item do inventario que esta no
     * indice do payload, <b>no mesmo lugar</b> da lista (30/09/2026, FASE 2B).
     *
     * <p><b>Indice fora da lista e item sem nome devolvem a ficha intacta</b>, e o
     * receptor ve {@code updated == current} e sai sem gravar nem transmitir. E o
     * que segura o caso "o item que eu estava editando foi apagado entre o clique
     * e o pacote": sem o teste de faixa, o UPDATE viraria um ADD e o item
     * reapareceria sozinho, que e pior do que o pedido silenciosamente nao
     * acontecer.
     *
     * <p>Nao ha trava de nome como no {@code updateSkill}: dois itens com o mesmo
     * nome sao legitimos (o inventario nao deduplica), entao o indice e a
     * unica identidade valida. O teto de nome, tipo, peso e descricao e' do
     * construtor de {@link SheetData.InventoryItem}, e nao deste metodo.
     */
    private static SheetData updateItem(SheetData current, SheetItemPayload payload) {
        int index = payload.index();
        if (index < 0 || index >= current.inventory().items().size()) {
            return current;
        }
        SheetData.InventoryItem item = payload.item();
        if (item == null || item.name().isEmpty()) {
            return current;
        }
        return current.withInventory(current.inventory().withItem(index, item));
    }

    /**
     * Aplica o {@code SpellOp.UPDATE}: troca a magia que esta no indice do
     * payload, <b>no mesmo lugar</b> da lista (01/10/2026, pagina 3).
     *
     * <p><b>Indice fora da lista e magia sem nome devolvem a ficha intacta</b>, e o
     * receptor ve {@code updated == current} e sai sem gravar nem transmitir. E o
     * mesmo argumento do {@link #updateItem}: sem o teste de faixa, um UPDATE
     * atrasado (a magia foi apagada entre o clique e o pacote) viraria um ADD e a
     * magia reapareceria sozinha.
     *
     * <p><b>Nao ha trava de nome como no {@link #updateSkill}:</b> duas magias
     * com o mesmo nome sao legitimas ("Bola de Fogo" em duas schools), entao o
     * indice e a unica identidade valida. O teto de cada campo e' do construtor
     * de {@link SheetData.Spell}, e nao deste metodo.
     */
    private static SheetData updateSpell(SheetData current, SheetSpellPayload payload) {
        int index = payload.index();
        if (index < 0 || index >= current.spellbook().spells().size()) {
            return current;
        }
        SheetData.Spell spell = payload.spell();
        if (spell == null || spell.name().isEmpty()) {
            return current;
        }
        return current.withSpellUpdated(index, spell);
    }

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

    /**
     * Cria o preset pedido pela tela de rolagem.
     *
     * <p><b>Por que a tela e o comando usam o mesmo caminho:</b> as duas portas
     * chamam {@link RollPreset#create} emede a resposta sai da MESMA chave de
     * linguagem que o comando usaria, so que convertida em texto para a tela. Se as
     * duas tivessem regras proprias, "formula desconhecida" seria recusada num lugar e
     * aceita no outro, e a jogadora perderia a forma de descobrir o erro.
     *
     * <p><b>Por que nao ha checagem de Mestre:</b> o preset e pessoal (decisao do
     * usuario em 01/10/2026), igual ao {@code /rpg roll}.
     */
    private static void registerRollPresetReceiver() {
        ServerPlayNetworking.registerGlobalReceiver(PresetCreatePayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            ServerPlayNetworking.send(player, createPresetFromScreen(player, payload));
        });

        // --- tela de Presets (01/10/2026) ---

        ServerPlayNetworking.registerGlobalReceiver(PresetSavePayload.TYPE, (payload, context) ->
                ServerPlayNetworking.send(context.player(), savePresetFromScreen(context.player(), payload)));

        ServerPlayNetworking.registerGlobalReceiver(PresetDeletePayload.TYPE, (payload, context) ->
                ServerPlayNetworking.send(context.player(),
                        deletePresetFromScreen(context.player(), payload)));

        ServerPlayNetworking.registerGlobalReceiver(PresetListRequestPayload.TYPE, (payload, context) ->
                sendRollPresetList(context.player()));

        ServerPlayNetworking.registerGlobalReceiver(PresetMovePayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            movePresetFromScreen(player, payload);
            // Reenvia a lista: a seta muda a ordem, e a tela que mandou o clique so
            // tem a lista antiga. Sem este envio, a seta mexeria no servidor e a
            // tela mostraria a ordem antiga ate fechar e abrir de novo.
            ServerPlayNetworking.send(player, new PresetListPayload(RollPresetStore.list(player.getUUID())));
        });
    }

    /**
     * Cliente -&gt; Servidor: "me manda a lista".
     *
     * <p><b>Por que um pacote vazio em vez de a tela abrir com o que ela tem:</b> a
     * lista da jogadora pode ter mudado desde a ultima vez que a tela esteve aberta
     * (um preset criado por comando, ou editado e renomeado). O pedido e o que garante
     * que a tela abra mostrando o estado real.
     */
    public record PresetListRequestPayload() implements CustomPacketPayload {
        public static final Type<PresetListRequestPayload> TYPE =
                new Type<>(TabletopRpg.id("preset_list_request"));
        /** Nao transporta nada; o proprio tipo do pacote e a mensagem. */
        public static final StreamCodec<FriendlyByteBuf, PresetListRequestPayload> STREAM_CODEC =
                StreamCodec.unit(new PresetListRequestPayload());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Envia a lista de presets da jogadora, pedido pela abertura da tela. */
    public static void sendRollPresetList(ServerPlayer player) {
        if (player == null || player.connection == null) {
            return;
        }
        ServerPlayNetworking.send(player, new PresetListPayload(RollPresetStore.list(player.getUUID())));
    }

    // ------------------------------------------------------------------
    // FICHAS DE AMEACA (02/10/2026)
    // ------------------------------------------------------------------

    /**
     * Receptores das fichas de ameaça.
     *
     * <p><b>Por que aqui a checagem de Mestre e obrigatoria e nos presets nao:</b> o
     * preset e pessoal (decisao de 01/10/2026), a ficha de ameaça e do Mestre e describes
     * a mesa inteira. Esconder o botão no menu nao e permissão: qualquer jogador pode
     * mandar o pacote, entudo o handler é que recusa.
     */
    private static void registerThreatSheetReceiver() {
        ServerPlayNetworking.registerGlobalReceiver(ThreatSheetListRequestPayload.TYPE,
                (payload, context) -> sendThreatSheetList(context.player()));

        ServerPlayNetworking.registerGlobalReceiver(ThreatSheetSavePayload.TYPE, (payload, context) ->
                ServerPlayNetworking.send(context.player(),
                        saveThreatSheetFromScreen(context.player(), payload)));

        ServerPlayNetworking.registerGlobalReceiver(ThreatSheetDeletePayload.TYPE, (payload, context) ->
                ServerPlayNetworking.send(context.player(),
                        deleteThreatSheetFromScreen(context.player(), payload)));
    }

    /**
     * Envia a lista de fichas do Mestre.
     *
     * <p>Joga fora a resposta para quem nao e Mestre: nao ha lista publica de ameacas,
     * e a ficha guarda a descricao e as habilidades da criatura.
     */
    public static void sendThreatSheetList(ServerPlayer player) {
        if (player == null || player.connection == null || !SessionManager.isMaster(player)) {
            return;
        }
        ServerPlayNetworking.send(player, new ThreatSheetListPayload(
                ThreatSheetStore.snapshot(player.getUUID())));
    }

    /**
     * Salva a ficha pedida pela tela e entrega o item.
     *
     * <p><b>Por que o item e entregue a cada save (decisao do usuario em 02/10/2026):</b>
     * igual ao preset, o item carrega o nome e o ND no proprio stack. Reentregar e o
     * preco do item duplicado; a alternativa era reescrever os componentes dos stacks
     * iguais na mochila, e o Mestre pediu a entrega nova.
     *
     * <p>A ficha e alinhada ao modelo antes de gravar: atributo que o Mestre acabou de
     * criar no Sheet Editor aparece com valor 0, e atributo que ele removeu sai.
     */
    private static ThreatSheetResultPayload saveThreatSheetFromScreen(ServerPlayer player,
                                                                       ThreatSheetSavePayload payload) {
        if (!SessionManager.isMaster(player)) {
            return threatSheetRefuse(player, "Só o mestre pode editar fichas de ameaça.");
        }
        if (payload == null || payload.sheet() == null) {
            return threatSheetRefuse(player, "Ficha vazia.");
        }
        ThreatSheet aligned = payload.sheet().alignedTo(SheetModelHolder.current());
        ThreatSheetStore.SaveResult result = ThreatSheetStore.save(player, aligned, payload.originalName());
        if (!result.ok()) {
            return threatSheetRefuse(player, result.message());
        }

        // O item e a apresentacao da ficha: o save acontece MESMO com a mochila cheia,
        // porque perder a ficha seria pior do que o mestre receber o aviso. A mesma
        // escolha que o save de preset ja faz.
        //
        // "Atualizar" (aberta pelo item) NAO entrega item: cada edicao da ficha
        // multiplicaria o stack na mochila. Decisao do Mestre em 02/10/2026.
// O store e quem manda no id: ele reaproveita o id guardado ou sorteia um quando a
        // ficha e nova. A ficha que o cliente mandou (`aligned`) pode ter id vazio ou
        // desatualizado, e usar ela depois do save faz o mob vinculado nao ser
        // encontrado e o item nascer com id que nao existe. Relido do store.
        ThreatSheet saved = ThreatSheetStore.find(player.getUUID(), aligned.key());
        if (saved == null) {
            saved = aligned;
        }

        // Recem-salva: o mob amarrado precisa receber o nome novo, senao o Mestre edita
        // "Goblin Chefe" e o @e[name=...] continua achando o antigo. Vale para os dois
        // botoes -- salvar pelo menu tambem renomeia o mob.
        MinecraftServer server = player.level().getServer();
        ThreatSheetBinding.selfHeal(server);
        UUID bound = ThreatSheetBinding.mobOf(server, saved.id());
        if (bound != null && player.level().getEntity(bound) instanceof Mob linked) {
            ThreatSheetBinding.applyDisplayName(linked, saved);
        }

        if (!payload.deliverItem()) {
            // "Atualizar", aberta pelo item: grava por cima e NAO entrega item, senao
            // cada edicao multiplicaria o stack na mochila (decisao do Mestre em 02/10/2026).
            return new ThreatSheetResultPayload(true, result.message(),
                    ThreatSheetStore.snapshot(player.getUUID()));
        }
        ItemStack delivered = ModItems.giveThreatSheet(player, saved);
        String suffix = delivered == null
                ? " (inventário cheio: a ficha foi salva, mas o item não coube)"
                : "";
        return new ThreatSheetResultPayload(true, result.message() + suffix,
                ThreatSheetStore.snapshot(player.getUUID()));
    }

    /**
     * Abre no cliente a ficha que o item da mao aponta, em modo de atualizacao.
     *
     * <p>Quem resolve a ficha e o SERVIDOR, pelo id do item. O cliente nao tem o
     * {@link ThreatSheetStore} e o item pode estar com id velho (ficha renomeada ou
     * apagada), entao mandar a ficha pronta evita os dois desvios.
     */
    public static void sendThreatSheetOpen(ServerPlayer player, ThreatSheet sheet) {
        if (player == null || player.connection == null || !SessionManager.isMaster(player)) {
            return;
        }
        ServerPlayNetworking.send(player, new ThreatSheetOpenPayload(sheet));
    }

    /**
     * Apaga a ficha pedida pela tela.
     *
     * <p><b>Por que o item NAO some junto:</b> o item e apresentacao, como no preset. A
     * ficha continua valendo para o Mestre decidir; apagar a ficha e um ato explicito.
     */
    private static ThreatSheetResultPayload deleteThreatSheetFromScreen(ServerPlayer player,
                                                                        ThreatSheetDeletePayload payload) {
        if (!SessionManager.isMaster(player)) {
            return threatSheetRefuse(player, "Só o mestre pode apagar fichas de ameaça.");
        }
        ThreatSheet removed = ThreatSheetStore.remove(player.getUUID(), payload.name());
        if (removed == null) {
            return threatSheetRefuse(player, "Essa ficha de ameaça não existe mais.");
        }
        return new ThreatSheetResultPayload(true, "Ficha de ameaça apagada.",
                ThreatSheetStore.snapshot(player.getUUID()));
    }

    /** Recusa com a lista vazia: quem nao e Mestre nao recebe nem a lista nem o motivo. */
    private static ThreatSheetResultPayload threatSheetRefuse(ServerPlayer player, String message) {
        boolean master = SessionManager.isMaster(player);
        List<ThreatSheet> sheets = master ? ThreatSheetStore.snapshot(player.getUUID()) : List.of();
        return new ThreatSheetResultPayload(false, message, sheets);
    }

    // =====================================================================
    // Diario -- anotacoes em arvore, uma por jogador (02/10/2026)
    // =====================================================================

    /**
     * Cliente -> Servidor: "me manda o diario". Disparado ao abrir a tela.
     *
     * <p>Nao transporta nada: o proprio tipo do pacote e a mensagem.
     */
    public record DiaryRequestPayload() implements CustomPacketPayload {
        public static final Type<DiaryRequestPayload> TYPE = new Type<>(TabletopRpg.id("diary_request"));

        public static final StreamCodec<FriendlyByteBuf, DiaryRequestPayload> STREAM_CODEC =
                StreamCodec.unit(new DiaryRequestPayload());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Servidor -> Cliente: o diario inteiro, mais se ha algo para o botao Reverter.
     *
     * <p><b>Por que vai a lista inteira e nao so o nivel aberto:</b> o botao Reverter pode
     * devolver uma subarvore que estava num nivel que a tela nem estava mostrando, e o pino
     * de um card precisa saber o estado de um {@code pinSeq} que so existe no servidor. Enviar
     * um no por vez obrigaria a tela a manter cache proprio e a reconciliar; com o teto de
     * {@link DiaryStore#MAX_ENTRIES} o pacote continua pequeno (o pior caso e ~100 KB, e o
     * limite do vanilla e 1 MB por pacote).
     *
     * <p><b>Por que o {@code canUndo} vem no estado e nao a tela deduz:</b> o apagado vive
     * so na memoria do servidor. Se a tela deduzisse "deu para desfazer" por ter acabado de
     * clicar em Del?, ela mostraria o botao num estado que o servidor nao concorda, e o
     * clique seguinte seria um no-op silencioso.
     *
     * @param focusId id da anotacao que o servidor acabou de criar, ou 0 se nada foi criado.
     *                E o que faz a tela abrir a pagina da subsecao recem-criada: o id e
     *                do servidor, e a tela nao tem como deduzi-lo sem errar (o proximo id
     *                livre e um contador global do jogador, nao "quantas subsecoes tem").
     */
    public record DiaryStatePayload(List<DiaryEntry> entries, boolean canUndo, int focusId,
                                     List<Integer> pendingIds)
            implements CustomPacketPayload {
        public static final Type<DiaryStatePayload> TYPE = new Type<>(TabletopRpg.id("diary_state"));

        /**
         * Escrito a mao porque {@code composite} nao tem sobrecarga de lista, e um
         * {@code VarInt} de contagem antes de cada entrada e o que faz o vanilla conseguir
         * ler o pacote sem saber o tamanho de antemao.
         *
         * <p><b>A ordem de leitura tem de bater com a de escrita, campo a campo:</b> um
         * {@code composite} trocaria {@code canUndo} por {@code pendingIds} em silencio, e o
         * sintoma seria o botao [Reverter] acendendo e apagando sem ninguem mexer nele.
         */
        public static final StreamCodec<FriendlyByteBuf, DiaryStatePayload> STREAM_CODEC = new StreamCodec<>() {
            @Override
            public DiaryStatePayload decode(FriendlyByteBuf buf) {
                // Teto na leitura: um `VarInt` de tamanho vem do outro lado, e alocar a lista
                // pelo numero que chegou seria confiar em dado nao validado. Acima do teto do
                // store e lixo; o que vier a mais e descartado, e o store ja recusaria criar.
                int size = Math.min(buf.readVarInt(), DiaryStore.MAX_ENTRIES);
                List<DiaryEntry> entries = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    entries.add(DiaryEntry.STREAM_CODEC.decode(buf));
                }
                boolean canUndo = buf.readBoolean();
                int focus = buf.readVarInt();
                // O teto aqui e o de entradas: nao ha como haver mais pendentes do que nos.
                int pendingSize = Math.min(buf.readVarInt(), DiaryStore.MAX_ENTRIES);
                List<Integer> pendingIds = new ArrayList<>(pendingSize);
                for (int i = 0; i < pendingSize; i++) {
                    pendingIds.add(buf.readVarInt());
                }
                return new DiaryStatePayload(entries, canUndo, focus, pendingIds);
            }

            @Override
            public void encode(FriendlyByteBuf buf, DiaryStatePayload payload) {
                buf.writeVarInt(payload.entries().size());
                for (DiaryEntry entry : payload.entries()) {
                    DiaryEntry.STREAM_CODEC.encode(buf, entry);
                }
                buf.writeBoolean(payload.canUndo());
                buf.writeVarInt(payload.focusId());
                buf.writeVarInt(payload.pendingIds().size());
                for (Integer id : payload.pendingIds()) {
                    buf.writeVarInt(id);
                }
            }
        };

        public DiaryStatePayload {
            // `List.copyOf` trava a lista: o cliente guarda este payload em cache e nao
            // pode ver a colecao mudar debaixo dele.
            entries = entries == null ? List.of() : List.copyOf(entries);
            pendingIds = pendingIds == null ? List.of() : List.copyOf(pendingIds);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Cliente -> Servidor: criar anotacao.
     *
     * <p>{@code parentId} {@link DiaryEntry#ROOT} cria uma secao de raiz.
     *
     * <p><b>Por que o titulo viaja com 64 e nao com 30:</b> o {@link ByteBufCodecs#stringUtf8}
     * <b>lanca</b> ao decodificar uma string maior que o teto, e um cliente modificado
     * derrubaria a conexao em vez de receber um recusa. Mandando 64, um titulo de 40 chega
     * inteiro e o {@link DiaryStore#create} recusa com a contagem na mensagem -- o mesmo
     * desenho do {@code PresetCreatePayload}, cujo teto de rede (64) tambem e maior que o
     * {@link RollPreset#MAX_NAME} (32).
     */
    public record DiaryCreatePayload(int parentId, String title, String description)
            implements CustomPacketPayload {
        public static final Type<DiaryCreatePayload> TYPE = new Type<>(TabletopRpg.id("diary_create"));

        public static final StreamCodec<FriendlyByteBuf, DiaryCreatePayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, DiaryCreatePayload::parentId,
                        ByteBufCodecs.stringUtf8(64), DiaryCreatePayload::title,
                        ByteBufCodecs.stringUtf8(DiaryEntry.MAX_DESCRIPTION), DiaryCreatePayload::description,
                        DiaryCreatePayload::new
                );

        public DiaryCreatePayload {
            title = clamp(title, 64);
            description = clamp(description, DiaryEntry.MAX_DESCRIPTION);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Cliente -> Servidor: criar uma subsecao com o titulo automatico.
     *
     * <p><b>Por que um pacote so para isso, e nao {@code DiaryCreatePayload} com titulo
     * vazio:</b> o titulo e obrigatorio, e o {@link DiaryStore#create} recusa titulo vazio
     * justamente para a jogadora nao criar anotacao sem nome. O numero automatico
     * ("Subseção 1", "Subseção 2") depende de quantas subsecoes o PAI ja tem, e essa contagem
     * mora no servidor -- se o cliente mandasse o titulo, ele contaria sobre uma copia que
     * pode estar velha e criaria dois "Subseção 1" no mesmo pai. Pedir o numero ao servidor
     * elimina essa janela.
     *
     * <p>E o {@code focusId} da resposta e o que faz a tela abrir a pagina da subsecao
     * recem-criada, ja com o titulo preenchido e a descricao em branco.
     */
    public record DiaryCreateChildPayload(int parentId) implements CustomPacketPayload {
        public static final Type<DiaryCreateChildPayload> TYPE =
                new Type<>(TabletopRpg.id("diary_create_child"));

        public static final StreamCodec<FriendlyByteBuf, DiaryCreateChildPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, DiaryCreateChildPayload::parentId,
                        DiaryCreateChildPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Cliente -> Servidor: gravar o titulo e a descricao que a jogadora digitou.
     *
     * <p><b>Por que continua um pacote em vez de envio a cada tecla:</b> o campo e local da
     * jogadora enquanto ela escreve; mandar a cada caractere seria um pacote por tecla e a
     * escrita passaria a competir com a rede. E por isso que o salvamento e por tela, e nao
     * por tecla: quando ela sai da tela o rascunho vai em UM pacote.
     *
     * <p><b>O que e o {@code accept}:</b> a jogadora pediu que o texto entre no diario
     * sozinho ao trocar de tela, e que o [Salvar] seja o botao que "aplica os rascunhos".
     * Sao dois momentos diferentes sobre o mesmo texto: gravar ja aconteceu, e aceitar e
     * dizer "este e o bom, pode esquecer o rascunho". O {@code accept} e esse segundo
     * momento, e e ele que esvazia o [Reverter].
     */
    public record DiarySavePayload(int id, String title, String description, boolean accept)
            implements CustomPacketPayload {
        public static final Type<DiarySavePayload> TYPE = new Type<>(TabletopRpg.id("diary_save"));

        public static final StreamCodec<FriendlyByteBuf, DiarySavePayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, DiarySavePayload::id,
                        ByteBufCodecs.stringUtf8(64), DiarySavePayload::title,
                        ByteBufCodecs.stringUtf8(DiaryEntry.MAX_DESCRIPTION), DiarySavePayload::description,
                        ByteBufCodecs.BOOL, DiarySavePayload::accept,
                        DiarySavePayload::new
                );

        public DiarySavePayload {
            title = clamp(title, 64);
            description = clamp(description, DiaryEntry.MAX_DESCRIPTION);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Cliente -> Servidor: apagar a anotacao e tudo que estiver dentro dela.
     *
     * <p>A cascata e do {@link DiaryStore#delete}; o cliente nao manda a lista dos filhos
     * porque ele nao e a fonte da verdade e a lista dele pode estar velha.
     */
    public record DiaryDeletePayload(int id) implements CustomPacketPayload {
        public static final Type<DiaryDeletePayload> TYPE = new Type<>(TabletopRpg.id("diary_delete"));

        public static final StreamCodec<FriendlyByteBuf, DiaryDeletePayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, DiaryDeletePayload::id,
                        DiaryDeletePayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Cliente -> Servidor: o botao Reverter. Sem argumento: so existe a ultima delecao. */
    public record DiaryUndoPayload() implements CustomPacketPayload {
        public static final Type<DiaryUndoPayload> TYPE = new Type<>(TabletopRpg.id("diary_undo"));

        public static final StreamCodec<FriendlyByteBuf, DiaryUndoPayload> STREAM_CODEC =
                StreamCodec.unit(new DiaryUndoPayload());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Cliente -> Servidor: fixa ou desfixa o pino de um card. */
    public record DiaryPinPayload(int id, boolean pinned) implements CustomPacketPayload {
        public static final Type<DiaryPinPayload> TYPE = new Type<>(TabletopRpg.id("diary_pin"));

        public static final StreamCodec<FriendlyByteBuf, DiaryPinPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, DiaryPinPayload::id,
                        ByteBufCodecs.BOOL, DiaryPinPayload::pinned,
                        DiaryPinPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Cliente -> Servidor: "os rascunhos que estao na tela valem". E o botao [Salvar] da Tela 1,
     * onde nao ha texto para enviar -- os campos de la sao o formulario de CRIAR, e o que a
     * jogadora editou ja foi gravado no salvamento automatico.
     *
     * <p><b>Por que um pacote em vez de reaproveitar o {@link DiarySavePayload} com um id
     * qualquer:</b> aquele payload exige um id, um titulo e uma descricao, e aqui nenhum dos
     * tres tem o que ser. Apontar para o primeiro no da lista e mandar o texto dele seria
     * funcionar por acidente: o {@code save} nem alteraria nada porque o texto e o mesmo,
     * e o efeito viria so do {@code accept} logo depois. Um pacote cujo campo principal e
     * decorativo e um pacote que ninguem entende daqui a seis meses.
     * <p><b>Por que nao ha espaco para o texto:</b> por construcao ele ja esta no diario.
     * O que este botao muda e a linha de base do "aceito", e isso e do servidor.
     */
    public record DiaryAcceptPayload() implements CustomPacketPayload {
        public static final Type<DiaryAcceptPayload> TYPE = new Type<>(TabletopRpg.id("diary_accept"));

        public static final StreamCodec<FriendlyByteBuf, DiaryAcceptPayload> STREAM_CODEC =
                StreamCodec.unit(new DiaryAcceptPayload());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Envia o estado inteiro do diario da jogadora.
     *
     * <p>Chamada depois de <b>toda</b> mutacao, aceita ou recusada. E o preco de nao ter
     * estado parcial: um recusa de criacao (titulo vazio, diario cheio) volta como estado
     * identico ao anterior, e a tela fica correta sem nenhum caminho de volta a desenhar.
     */
    public static void sendDiaryState(ServerPlayer player) {
        sendDiaryState(player, 0);
    }

    /**
     * O mesmo estado, dizendo qual anotacao acabou de nascer.
     *
     * @param focusId id criado agora, ou 0. Ver {@link DiaryStatePayload#focusId()}.
     */
    public static void sendDiaryState(ServerPlayer player, int focusId) {
        if (player == null || player.connection == null) {
            return;
        }
        ServerPlayNetworking.send(player, new DiaryStatePayload(
                DiaryStore.entries(player.getUUID()),
                DiaryStore.canUndo(player.getUUID()),
                focusId,
                DiaryStore.pendingIds(player.getUUID())));
    }

    /**
     * Liga os receptores de cliente para servidor do Diario.
     *
     * <p><b>Por que todo handler termina em {@link #sendDiaryState}:</b> o servidor e a fonte
     * da verdade, e a unica forma de a tela saber o resultado real e o {@code pinSeq} que o
     * {@link DiaryStore} atribuiu. Devolver o objeto criado no proprio pacote ensinaria a
     * tela a trabalhar com um estado que nunca existiu no servidor.
     */
    private static void registerDiaryReceiver() {
        ServerPlayNetworking.registerGlobalReceiver(DiaryRequestPayload.TYPE, (payload, context) ->
                sendDiaryState(context.player()));

        ServerPlayNetworking.registerGlobalReceiver(DiaryCreatePayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            try {
                DiaryStore.create(player.getUUID(), payload.parentId(), payload.title(), payload.description());
            } catch (DiaryStore.DiaryException erro) {
                TabletopRpg.LOGGER.warn("[TabletopRPG] Diario: criação recusada para {}: {}",
                        player.getName().getString(), erro.getMessage());
            }
            sendDiaryState(player);
        });

        ServerPlayNetworking.registerGlobalReceiver(DiaryCreateChildPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            int created = 0;
            try {
                // O numero sai do servidor porque a contagem de subsecoes do pai mora aqui.
                // Ver `DiaryCreateChildPayload`.
                DiaryEntry entry = DiaryStore.create(player.getUUID(), payload.parentId(),
                        DiaryStore.defaultSubsectionTitle(player.getUUID(), payload.parentId()), "");
                created = entry.id();
            } catch (DiaryStore.DiaryException erro) {
                TabletopRpg.LOGGER.warn("[TabletopRPG] Diario: criação de subseção recusada para {}: {}",
                        player.getName().getString(), erro.getMessage());
            }
            sendDiaryState(player, created);
        });

        ServerPlayNetworking.registerGlobalReceiver(DiarySavePayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            try {
                DiaryStore.save(player.getUUID(), payload.id(), payload.title(), payload.description());
            } catch (DiaryStore.DiaryException erro) {
                TabletopRpg.LOGGER.warn("[TabletopRPG] Diario: gravação recusada para {}: {}",
                        player.getName().getString(), erro.getMessage());
            }
            // O `accept` vem DEPOIS do `save`, e nao junto: se o `save` foi recusado (titulo
            // vazio, por exemplo), aceitar em seguida esvaziaria a pilha de desfazer sem
            // que nada novo tivesse entrado, e a jogadora perderia o [Reverter] sem motivo.
            if (payload.accept()) {
                DiaryStore.accept(player.getUUID());
            }
            sendDiaryState(player);
        });

        ServerPlayNetworking.registerGlobalReceiver(DiaryAcceptPayload.TYPE, (payload, context) -> {
            // Sem `save` antes: nao ha texto vindo junto, entao nao ha o que gravar, e o
            // `accept` sozinho ja faz a parte que importa -- recopia o espelho e esvazia a
            // pilha de desfazer.
            DiaryStore.accept(context.player().getUUID());
            sendDiaryState(context.player());
        });

        ServerPlayNetworking.registerGlobalReceiver(DiaryDeletePayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            // `false` = id nao existe. Nao e erro: a tela pode ter clicado num card que o
            // servidor ja tinha apagado (desfazer chegou antes do delete, por exemplo).
            if (!DiaryStore.delete(player.getUUID(), payload.id())) {
                TabletopRpg.LOGGER.debug("[TabletopRPG] Diario: apagar id {} que não existe para {}",
                        payload.id(), player.getName().getString());
            }
            sendDiaryState(player);
        });

        ServerPlayNetworking.registerGlobalReceiver(DiaryUndoPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (!DiaryStore.undo(player.getUUID())) {
                // Mesma razao do id inexistente: um Reverter clicado duas vezes, ou um
                // clique que chegou depois de outra delecao.
                TabletopRpg.LOGGER.debug("[TabletopRPG] Diario: Reverter sem nada para desfazer para {}",
                        player.getName().getString());
            }
            sendDiaryState(player);
        });

        ServerPlayNetworking.registerGlobalReceiver(DiaryPinPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            DiaryStore.setPinned(player.getUUID(), payload.id(), payload.pinned());
            sendDiaryState(player);
        });
    }

    /**
     * Salva o preset pedido pela tela, criando ou editando.
     *
     * <p><b>Por que o rename devolve item novo e o item antigo fica:</b> decisao do
     * usuario em 01/10/2026. O item carrega o nome numa tag propria, entao editar o
     * preset nao muda o item que ja esta na mochila. A opcao escolhida foi reentregar
     * o item atualizado e avisar que o antigo continua la -- o preco e um item
     * duplicado, e o ganho e que o item novo nunca mostra o nome velho.
     *
     * <p>A posicao e mantida pelo {@link RollPresetStore#put}: renomear nao manda o
     * preset para o fim da lista.
     */
    private static PresetResultPayload savePresetFromScreen(ServerPlayer player, PresetSavePayload payload) {
        String name = payload.name().trim();
        if (name.isEmpty()) {
            return presetRefuse(player, "message.tabletoprpg.preset_name_required");
        }

        RollPreset preset;
        try {
            preset = RollPreset.create(name, payload.formula(), payload.colorId());
        } catch (RollPreset.PresetException e) {
            // Formula ou cor invalida: se acusa aqui, com o texto do RollPreset, que
            // e o mesmo que o comando mostraria.
            return presetRefuse(player, "message.tabletoprpg.preset_create_failed", e.getMessage());
        }

        UUID uuid = player.getUUID();
        boolean editing = payload.originalName() != null && !payload.originalName().isBlank();
        String originalKey = editing ? RollPreset.normalizeKey(payload.originalName()) : "";
        RollPreset original = editing
                ? RollPresetStore.find(uuid, payload.originalName()).orElse(null) : null;

        if (editing && original == null) {
            // A tela tinha um preset na tela e o servidor nao tem mais (deletado por
            // comando, por exemplo). Dizer isso e melhor que criar um preset novo
            // sem a jogadora pedir.
            return presetRefuse(player, "message.tabletoprpg.preset_not_found", payload.originalName());
        }

        // Colisao de nome: so recusa se o dono da chave for OUTRO preset. Editar sem
        // mudar o nome tem que funcionar, e e o caso comum de arrumar a cor.
        RollPreset conflict = RollPresetStore.find(uuid, preset.key()).orElse(null);
        if (conflict != null && (original == null || !conflict.key().equals(original.key()))) {
            return presetRefuse(player, "message.tabletoprpg.preset_already_exists", conflict.name());
        }
        if (original == null && RollPresetStore.count(uuid) >= RollPresetStore.MAX_PRESETS) {
            return presetRefuse(player, "message.tabletoprpg.preset_limit",
                    String.valueOf(RollPresetStore.MAX_PRESETS));
        }

        // Renomear e trocar a chave: o preset velho sai e o novo entra no lugar dele.
        // Sem este passo, o preset renomeado ficaria duplicado, com o nome novo em
        // cima do velho.
        if (original != null && !original.key().equals(preset.key())) {
            RollPresetStore.remove(uuid, original.name());
        }
        RollPresetStore.put(uuid, preset);

        // Item: sempre no save. Criar precisa (o comando cria e entrega), e renomear
        // precisa porque o item antigo mostra o nome velho. Editar sem renomear
        // entrega tambem, para o item pegar a cor nova.
        boolean gaveItem = ModItems.giveRollPreset(player, preset) != null;

        if (!gaveItem) {
            // Sem espaco: o preset foi SALVO, so o item nao coube. Recusa com o aviso
            // do inventario cheio e a lista atualizada, para a tela nao ficar
            // achando que perdeu o preset.
            return new PresetResultPayload(false,
                    translatableText("message.tabletoprpg.preset_no_room"),
                    RollPresetStore.list(uuid));
        }

        String message = original != null && !original.key().equals(preset.key())
                ? translatableText("message.tabletoprpg.preset_renamed",
                        original.name(), preset.name())
                : original != null
                ? translatableText("message.tabletoprpg.preset_updated",
                        preset.name(), preset.formula(), preset.color().displayName())
                : translatableText("message.tabletoprpg.preset_created",
                        preset.name(), preset.formula(), preset.color().displayName());
        return new PresetResultPayload(true, message, RollPresetStore.list(uuid));
    }

    /**
     * Apaga o preset pedido pela tela.
     *
     * <p><b>O item fica na mochila</b>: mesma decisao do comando e do comportamento que
     * a jogadora ja conhece desde 01/10/2026. Usar o item depois mostra "preset nao
     * existe".
     */
    private static PresetResultPayload deletePresetFromScreen(ServerPlayer player,
                                                              PresetDeletePayload payload) {
        UUID uuid = player.getUUID();
        RollPreset removed = RollPresetStore.remove(uuid, payload.name()).orElse(null);
        if (removed == null) {
            return presetRefuse(player, "message.tabletoprpg.preset_not_found", payload.name());
        }
        return new PresetResultPayload(true,
                translatableText("message.tabletoprpg.preset_deleted", removed.name()),
                RollPresetStore.list(uuid));
    }

    /**
     * Aplica uma seta de reordenar.
     *
     * <p>Indice fora da faixa e ignorar, nao falhar: o clique veio de uma lista que
     * o cliente tinha, e se a lista do servidor mudou no meio do caminho (um preset
     * criado por comando), mover o preset pela posicao dele por nome e o que mantem
     * a seta mexendo no preset certo.
     */
    private static void movePresetFromScreen(ServerPlayer player, PresetMovePayload payload) {
        int index = RollPresetStore.indexOf(player.getUUID(), payload.name());
        if (index < 0) {
            return;
        }
        int target = payload.up() ? index - 1 : index + 1;
        RollPresetStore.move(player.getUUID(), index, target);
    }

    /** Recusa com a lista atual: a tela recobra o estado sem reabrir. */
    private static PresetResultPayload presetRefuse(ServerPlayer player, String key, Object... args) {
        return new PresetResultPayload(false, translatableText(key, args),
                RollPresetStore.list(player.getUUID()));
    }

    /**
     * Cria o preset e monta a resposta para a tela.
     *
     * <p><b>Por que separado do receptor:</b> o receptor so envia. A regra toda esta
     * aqui, e aqui ela devolve <b>sempre</b> uma resposta -- sucesso ou recusa -- em
     * vez de sair pela metade. Isso importa porque a tela fica esperando: sem
     * resposta ela ficaria parada achando que salvou.
     */
    private static PresetCreateResultPayload createPresetFromScreen(ServerPlayer player,
                                                                    PresetCreatePayload payload) {
        String name = payload.name().trim();
        if (name.isEmpty()) {
            return refuse("message.tabletoprpg.preset_name_required");
        }

        RollPreset preset;
        try {
            preset = RollPreset.create(name, payload.formula(), payload.colorId());
        } catch (RollPreset.PresetException e) {
            // Formula nao reconhecida: e agora que a tela acusa, ao salvar.
            return refuse("message.tabletoprpg.preset_create_failed", e.getMessage());
        }

        UUID uuid = player.getUUID();
        RollPreset existing = RollPresetStore.find(uuid, preset.key()).orElse(null);
        if (existing != null) {
            // Mesma decisao do comando: create nao sobrescreve.
            return refuse("message.tabletoprpg.preset_already_exists", existing.name());
        }
        if (RollPresetStore.count(uuid) >= RollPresetStore.MAX_PRESETS) {
            return refuse("message.tabletoprpg.preset_limit", String.valueOf(RollPresetStore.MAX_PRESETS));
        }

        RollPresetStore.put(uuid, preset);
        if (ModItems.giveRollPreset(player, preset) == null) {
            // Sem espaco: o preset foi SALVO mesmo assim, so o item nao coube. Por
            // isso a resposta e recusa com o texto do inventario cheio -- a jogadora
            // ainda tem o preset, e `/rpg preset give` entrega o item depois.
            return refuse("message.tabletoprpg.preset_no_room");
        }
        return new PresetCreateResultPayload(true, translatableText("message.tabletoprpg.preset_created",
                preset.name(), preset.formula(), preset.color().displayName()));
    }

    /** Resposta de recusa: {@code ok} falso e o texto da chave. */
    private static PresetCreateResultPayload refuse(String key, Object... args) {
        return new PresetCreateResultPayload(false, translatableText(key, args));
    }

    /**
     * Monta a mensagem de lang ja como texto.
     *
     * <p>Existe para o handler do preset ter uma linha so por recusa. A resolucao
     * acontece com o idioma do servidor; o mod so tem {@code en_us}, entao o texto
     * que chega na tela e o mesmo que apareceria no chat.
     */
    private static String translatableText(String key, Object... args) {
        return Component.translatable(key, args).getString();
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

    /**
     * Manda o cliente abrir a tela do Sheet Editor.
     *
     * <p>Só o sinal de abrir: a tela ainda lê a lista de perícias do código, então
     * não há dado nenhum para mandar. A próxima fatia troca isto por um payload com
     * o modelo do mundo.
     *
     * <p>Confere o Mestre de novo aqui, mesmo que {@code ModItems} já tenha
     * conferido: a checagem no clique é de UI, e o servidor não pode depender de
     * quem chamou. Se amanhã outro lugar chamar este método, ele continua seguro.
     */
    public static void sendOpenSheetEditor(ServerPlayer player) {
        if (player == null || player.connection == null) {
            return;
        }
        if (!SessionManager.isMaster(player)) {
            return;
        }
        ServerPlayNetworking.send(player, new OpenSheetEditorPayload());
    }

    /**
     * Manda o modelo para UM jogador.
     *
     * <p>Sem checagem de Mestre: o modelo nao e segredo. Todo mundo precisa
     * dele para desenhar a ficha dos outros, e esconder isso so faria o cliente
     * mostrar o padrao enquanto o Mestre ve o modelo salvo.
     */
    public static void sendSheetModel(ServerPlayer player, SheetModel model) {
        if (player == null || player.connection == null) {
            return;
        }
        ServerPlayNetworking.send(player,
                new SheetModelPayload(model == null ? SheetModel.defaults() : model));
    }

    /**
     * Reenvia o modelo para todo mundo online.
     *
     * <p>Chamado depois de cada edicao do Mestre. E o que faz a mudanca
     * aparecer na ficha de quem nao tem a tela do editor aberta.
     */
    public static void broadcastSheetModel(MinecraftServer server, SheetModel model) {
        if (server == null) {
            return;
        }
        SheetModelPayload payload = new SheetModelPayload(model == null ? SheetModel.defaults() : model);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.connection != null) {
                ServerPlayNetworking.send(player, payload);
            }
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

    /** Envia o estado atual das auras de limite para um jogador especifico. */
    public static void sendAuraStateToPlayer(MinecraftServer server, ServerPlayer player) {
        if (server == null || player == null || player.connection == null) {
            return;
        }
        sendAuraState(server, player, new AuraStatePayload(CombatController.getAuraData(server)));
    }

    /** Envia o estado atual das auras de limite para todos os jogadores. */
    public static void sendAuraStateToAll(MinecraftServer server) {
        if (server == null) {
            return;
        }
        // Monta o payload UMA vez: getAuraData percorre ancoras e mobs selecionados,
        // e nao faz sentido refazer isso por jogador num broadcast.
        AuraStatePayload payload = new AuraStatePayload(CombatController.getAuraData(server));
        TabletopRpg.LOGGER.info("[TabletopRPG] sendAuraStateToAll: enviando {} aura(s)", payload.auras().size());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sendAuraState(server, player, payload);
        }
    }

    private static void sendAuraState(MinecraftServer server, ServerPlayer player, AuraStatePayload payload) {
        if (server == null || player == null || player.connection == null) {
            return;
        }
        ServerPlayNetworking.send(player, payload);
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
