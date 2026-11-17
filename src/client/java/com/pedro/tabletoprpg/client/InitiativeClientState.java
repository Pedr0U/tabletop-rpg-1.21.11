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
    /** Rodada atual enviada pelo servidor (04/11/2026). */
    private static int round = 1;
    private static List<InitiativeSync.Entry> entries = List.of();
    private static List<InitiativeSync.Candidate> candidates = List.of();
    /** Posicao da entrada com a vez; -1 quando ninguem esta com a vez. */
    private static int activeIndex = -1;
    private static boolean showPanel = false;
    private static boolean panelSuppressedByMode = false;

    private InitiativeClientState() {
    }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(InitiativeSync.Payload.TYPE, (payload, context) ->
                context.client().execute(() -> apply(payload)));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
    }

    private static void apply(InitiativeSync.Payload payload) {
        collecting = payload.collecting();
        round = payload.round();
        entries = List.copyOf(payload.entries());
        candidates = List.copyOf(payload.candidates());
        activeIndex = payload.activeIndex();
    }

    private static void reset() {
        collecting = false;
        round = 1;
        entries = List.of();
        candidates = List.of();
        activeIndex = -1;
        showPanel = false;
        panelSuppressedByMode = false;
    }

    public static boolean isCollecting() {
        return collecting;
    }

    /** Rodada atual; vale quando a coleta esta aberta (04/11/2026). */
    public static int round() {
        return round;
    }

    public static List<InitiativeSync.Entry> entries() {
        return entries;
    }

    public static List<InitiativeSync.Candidate> candidates() {
        return candidates;
    }

    /** Indice da linha destacada como turno atual, ou -1. */
    public static int activeIndex() {
        return activeIndex;
    }

    public static boolean isShowPanel() {
        return showPanel && !panelSuppressedByMode;
    }

    public static void toggleShowPanel() {
        showPanel = !showPanel;
    }

    /** Ligado/desligado direto: o X da janela de HUD usa o mesmo interruptor da tela. */
    public static void setShowPanel(boolean visible) {
        showPanel = visible;
    }

    public static void setModeActive(boolean active) {
        panelSuppressedByMode = !active;
    }
}
