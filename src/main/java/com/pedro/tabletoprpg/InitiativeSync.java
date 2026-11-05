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

    /** Um nome que o mestre pode adicionar ou rolar pela tela (jogador online ou mob elegivel). */
    public record Candidate(String name, boolean mob) {
        public static final StreamCodec<FriendlyByteBuf, Candidate> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(MAX_NAME), Candidate::name,
                ByteBufCodecs.BOOL, Candidate::mob,
                Candidate::new
        );
    }

    public record Payload(boolean collecting, int round, List<Entry> entries, List<Candidate> candidates)
            implements CustomPacketPayload {
        public static final Type<Payload> TYPE = new Type<>(TabletopRpg.id("initiative_sync"));
        public static final StreamCodec<FriendlyByteBuf, Payload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, Payload::collecting,
                // Rodada atual (04/11/2026): mostrada na HUD e na tela de iniciativa.
                ByteBufCodecs.VAR_INT, Payload::round,
                Entry.STREAM_CODEC.apply(ByteBufCodecs.list()), Payload::entries,
                Candidate.STREAM_CODEC.apply(ByteBufCodecs.list()), Payload::candidates,
                Payload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Pedido do cliente para receber o estado atual da iniciativa (03/11/2026).
     *
     * <p>A tela de iniciativa manda isto ao abrir porque a lista de candidatos muda
     * sem comando de iniciativa (ficha vinculada a um mob novo, mob que entrou no
     * carrossel, jogador que entrou), e o ultimo broadcast pode estar velho.
     */
    public record RequestPayload() implements CustomPacketPayload {
        public static final Type<RequestPayload> TYPE = new Type<>(TabletopRpg.id("initiative_request"));
        public static final StreamCodec<FriendlyByteBuf, RequestPayload> STREAM_CODEC =
                StreamCodec.unit(new RequestPayload());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Abre a tela de "Atrasar Turno" no cliente do jogador com a vez (Parte 4,
     * 03/11/2026).
     *
     * <p>A lista de posicoes validas sai do estado de iniciativa que o cliente ja
     * tem; o servidor so pede a abertura depois de validar modo, coleta e turno.
     */
    public record OpenDelayPayload() implements CustomPacketPayload {
        public static final Type<OpenDelayPayload> TYPE = new Type<>(TabletopRpg.id("initiative_open_delay"));
        public static final StreamCodec<FriendlyByteBuf, OpenDelayPayload> STREAM_CODEC =
                StreamCodec.unit(new OpenDelayPayload());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Registra os pacotes e manda o estado atual para quem entra no servidor. */
    public static void register() {
        PayloadTypeRegistry.playS2C().register(Payload.TYPE, Payload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(OpenDelayPayload.TYPE, OpenDelayPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(OpenTurnPickerPayload.TYPE, OpenTurnPickerPayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(RequestPayload.TYPE, RequestPayload.STREAM_CODEC);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> sendTo(handler.getPlayer()));
        // A resposta usa a mesma checagem de Mestre do envio normal: candidatos so
        // vao para quem pode usa-los.
        ServerPlayNetworking.registerGlobalReceiver(RequestPayload.TYPE,
                (payload, context) -> sendTo(context.player()));
    }

    /**
     * Manda o estado atual da iniciativa para todos os jogadores online. So o
     * mestre recebe a lista de candidatos: ela serve a tela de adicionar/rolar e
     * nao precisa sair do servidor para quem nao pode usar.
     */
    public static void sendAll(MinecraftServer server) {
        InitiativeSync.Payload master = InitiativeCommands.snapshot(server, true);
        InitiativeSync.Payload viewer = InitiativeCommands.snapshot(server, false);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerPlayNetworking.send(player, SessionManager.isMaster(player) ? master : viewer);
        }
    }

    public static void sendTo(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return;
        }
        ServerPlayNetworking.send(player, InitiativeCommands.snapshot(server, SessionManager.isMaster(player)));
    }

    /** Pede ao cliente do jogador que abra a tela de atrasar turno. */
    public static void sendOpenDelay(ServerPlayer player) {
        ServerPlayNetworking.send(player, new OpenDelayPayload());
    }

    /**
     * Abre o menu de dar turno no cliente do Mestre (04/11/2026).
     *
     * <p>A lista de quem pode receber a vez e a propria lista de iniciativa, que o
     * cliente ja tem sincronizada; o servidor so manda o sinal depois de checar a
     * permissao. Chega tanto pelo botao "Dar turno" da tela quanto pelo botao
     * "Nao" da confirmacao de proximo turno no chat.
     */
    public record OpenTurnPickerPayload() implements CustomPacketPayload {
        public static final Type<OpenTurnPickerPayload> TYPE =
                new Type<>(TabletopRpg.id("initiative_open_turn_picker"));
        public static final StreamCodec<FriendlyByteBuf, OpenTurnPickerPayload> STREAM_CODEC =
                StreamCodec.unit(new OpenTurnPickerPayload());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Pede ao cliente do Mestre que abra o menu de dar turno. */
    public static void sendOpenTurnPicker(ServerPlayer player) {
        ServerPlayNetworking.send(player, new OpenTurnPickerPayload());
    }
}
