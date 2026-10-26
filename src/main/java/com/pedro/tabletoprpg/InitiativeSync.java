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

import java.util.List;

/**
 * Estado da iniciativa enviado do servidor para todos os clientes (Fase 3).
 *
 * <p>O cliente so precisa saber se a coleta esta aberta e quem esta na lista, na
 * ordem de exibicao. Cada mudanca na lista manda o estado inteiro de novo: a lista
 * e pequena e assim o cliente nunca fica com uma copia parcial.
 */
public final class InitiativeSync {

    /** Limite do nome de um participante no pacote; mesmo teto da mensagem de chat. */
    static final int MAX_NAME = 128;

    private InitiativeSync() {
    }

    /** Um participante, ja na ordem de exibicao (maior valor primeiro). */
    public record Entry(String name, boolean mob, int value) {
        public static final StreamCodec<FriendlyByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(MAX_NAME), Entry::name,
                ByteBufCodecs.BOOL, Entry::mob,
                ByteBufCodecs.VAR_INT, Entry::value,
                Entry::new
        );
    }

    public record Payload(boolean collecting, List<Entry> entries) implements CustomPacketPayload {
        public static final Type<Payload> TYPE = new Type<>(TabletopRpg.id("initiative_sync"));
        public static final StreamCodec<FriendlyByteBuf, Payload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, Payload::collecting,
                Entry.STREAM_CODEC.apply(ByteBufCodecs.list()), Payload::entries,
                Payload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Registra o pacote e manda o estado atual para quem entra no servidor. */
    public static void register() {
        PayloadTypeRegistry.playS2C().register(Payload.TYPE, Payload.STREAM_CODEC);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> sendTo(handler.getPlayer()));
    }

    /** Manda o estado atual da iniciativa para todos os jogadores online. */
    public static void sendAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sendTo(player);
        }
    }

    public static void sendTo(ServerPlayer player) {
        ServerPlayNetworking.send(player, InitiativeCommands.snapshot());
    }
}
