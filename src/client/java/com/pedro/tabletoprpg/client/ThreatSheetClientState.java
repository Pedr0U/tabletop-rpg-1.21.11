package com.pedro.tabletoprpg.client;

import com.pedro.tabletoprpg.ThreatSheet;

import java.util.ArrayList;
import java.util.List;

/**
 * Ultima lista de fichas de ameaça recebida do servidor, no cliente.
 *
 * <p><b>02/10/2026.</b> Existe porque a resposta do save chega com a tela da FICHA
 * aberta, e nao com a lista: o receptor so consegue entregar
 * {@code instanceof ThreatSheetScreen}, entao a lista de verdade se perderia no caminho.
 * Guardando aqui, a tela da lista abre com o estado real mesmo sem novo pedido.
 *
 * <p><b>Por que nao um pedido automatico ao abrir a lista:</b> daria o mesmo resultado
 * com um pacote a mais. A lista tambem pede, so que para pegar o que mudou enquanto a
 * ficha estava aberta em outra aba.
 */
public final class ThreatSheetClientState {

    private static volatile List<ThreatSheet> sheets = List.of();
    private static volatile String status = "";

    private ThreatSheetClientState() {
    }

    /** A ultima lista recebida. */
    public static List<ThreatSheet> sheets() {
        return sheets;
    }

    /** Guarda a lista vinda do servidor. */
    public static void setSheets(List<ThreatSheet> value) {
        sheets = value == null ? List.of() : new ArrayList<>(value);
    }

    /** A ultima mensagem do servidor (recusa de save, por exemplo). */
    public static String status() {
        return status;
    }

    /** Guarda a mensagem do servidor. */
    public static void setStatus(String value) {
        status = value == null ? "" : value;
    }
}