package com.pedro.tabletoprpg;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
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
 *   <li>{@link WeatherSetPayload} (C2S): mestre define o clima (0=sol, 1=chuva, 2=tempestade).</li>
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

    /** Servidor -> Cliente: informa se o jogador está travado (não pode se mover). */
    public record PlayerLockPayload(boolean locked) implements CustomPacketPayload {
        public static final Type<PlayerLockPayload> TYPE = new Type<>(TabletopRpg.id("player_lock"));
        public static final StreamCodec<FriendlyByteBuf, PlayerLockPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, PlayerLockPayload::locked,
                PlayerLockPayload::new
        );

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
        PayloadTypeRegistry.playC2S().register(WeatherSetPayload.TYPE, WeatherSetPayload.STREAM_CODEC);
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
            sendHoverConfigToPlayer(handler.getPlayer());
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
            // Limpa o hover (Glowing) deste jogador, se houver: sem isso, a
            // entidade hoverada continuava brilhando por até 60 ticks e a
            // entrada ficava para sempre no mapa (leak).
            UUID hovered = CombatController.getHoveredEntity(player.getUUID());
            if (hovered != null) {
                Entity hoveredEntity = player.level().getEntity(hovered);
                if (hoveredEntity instanceof LivingEntity living) {
                    living.removeEffect(MobEffects.GLOWING);
                }
                CombatController.clearHoveredEntity(player.getUUID());
            }
            if (changed) {
                sendToAll(server);
                sendAuraStateToAll(server);
            }
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
        });

        // Cliente informa qual entidade está sob o crosshair (hover) -> aplica/remove Glowing.
        ServerPlayNetworking.registerGlobalReceiver(HoverPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            ServerLevel level = (ServerLevel) player.level();

            // Remove o Glowing do hover anterior deste jogador.
            UUID prev = CombatController.getHoveredEntity(player.getUUID());
            if (prev != null) {
                Entity prevEntity = level.getEntity(prev);
                if (prevEntity instanceof LivingEntity living) {
                    living.removeEffect(MobEffects.GLOWING);
                }
            }

            if (payload.entityId() >= 0) {
                Entity target = level.getEntity(payload.entityId());
                if (target instanceof LivingEntity living) {
                    // Duração curta: o cliente reenvia o hover a cada 10 ticks,
                    // então o efeito é renovado enquanto o jogador mantiver o mouse em cima.
                    living.addEffect(new MobEffectInstance(MobEffects.GLOWING, 60, 0, false, false));
                    CombatController.setHoveredEntity(player.getUUID(), living.getUUID());
                } else {
                    CombatController.clearHoveredEntity(player.getUUID());
                }
            } else {
                CombatController.clearHoveredEntity(player.getUUID());
            }
        });

        // Registra o receptor de pausar/retomar ciclo dia e noite no servidor
        registerDayCycleReceiver();
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

    /** Envia o estado de "trava" (congelamento) para um jogador. Usado no JOIN e em mudanças de modo/turno. */
    public static void sendLockToPlayer(ServerPlayer player) {
        if (player == null || player.connection == null) {
            return;
        }
        ServerPlayNetworking.send(player, new PlayerLockPayload(!SessionManager.canPlayerAct(player)));
    }

    /** Envia os dados da sessão + estado de trava, abrindo o menu no cliente (resposta ao apertar R). */
    public static void sendMenuToPlayer(ServerPlayer player) {
        if (player == null || player.connection == null) {
            return;
        }

        // Lista de jogadores conectados (para o mestre poder dar turno pelo menu).
        List<String> playerNames = new ArrayList<>();
        MinecraftServer server = player.level().getServer();
        if (server != null) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
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
     */
    public static void sendToAll(MinecraftServer server) {
        if (server == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sendLockToPlayer(player);
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
}