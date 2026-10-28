package com.pedro.tabletoprpg.client;

import com.pedro.tabletoprpg.InitiativeSync;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.util.List;

/**
 * Copia no cliente do estado da iniciativa (Fase 3).
 *
 * <p>O estado vem do servidor e sobrescreve a copia inteira a cada mudanca. A
 * escolha "Mostrar iniciativas" fica so aqui, no cliente: nao e gravada em arquivo
 * e volta ao padrao (desligada) ao reconectar.
 */
public final class InitiativeClientState {

    private static boolean collecting = false;
    private static List<InitiativeSync.Entry> entries = List.of();
    private static boolean showPanel = false;

    private InitiativeClientState() {
    }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(InitiativeSync.Payload.TYPE, (payload, context) ->
                context.client().execute(() -> apply(payload)));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
    }

    private static void apply(InitiativeSync.Payload payload) {
        collecting = payload.collecting();
        entries = List.copyOf(payload.entries());
    }

    private static void reset() {
        collecting = false;
        entries = List.of();
        showPanel = false;
    }

    public static boolean isCollecting() {
        return collecting;
    }

    public static List<InitiativeSync.Entry> entries() {
        return entries;
    }

    public static boolean isShowPanel() {
        return showPanel;
    }

    public static void toggleShowPanel() {
        showPanel = !showPanel;
    }
}
