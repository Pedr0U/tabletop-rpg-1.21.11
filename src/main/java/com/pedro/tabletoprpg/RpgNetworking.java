package com.pedro.tabletoprpg;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

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

    // ------------------------------------------------------------------
    // REGISTRO (comum a servidor e cliente)
    // ------------------------------------------------------------------

    /** Deve ser chamado no onInitialize() (lado comum). */
    public static void registerPayloads() {
        PayloadTypeRegistry.playC2S().register(MenuRequestPayload.TYPE, MenuRequestPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(MenuDataPayload.TYPE, MenuDataPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(PlayerLockPayload.TYPE, PlayerLockPayload.STREAM_CODEC);
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