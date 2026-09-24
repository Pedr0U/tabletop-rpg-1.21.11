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
import net.minecraft.world.level.gamerules.GameRules;

import java.util.ArrayList;
import java.util.List;

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
}