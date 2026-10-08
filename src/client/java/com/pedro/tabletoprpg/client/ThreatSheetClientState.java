package com.pedro.tabletoprpg.client;

import com.pedro.tabletoprpg.ThreatSheet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    /**
     * Rascunhos de ficha com edicao nao salva, por id (07/10/2026). A tela da ficha
     * escreve aqui todo quadro em que o formulario difere do ultimo save; a lista
     * mostra a bolinha amarela exatamente quando o id tem rascunho. Apagar a ficha
     * em outra tela limpa o rascunho junto (ver {@link #setSheets}).
     */
    private static final Map<String, ThreatSheetDraft> drafts = new HashMap<>();

    private ThreatSheetClientState() {
    }

    /** A ultima lista recebida. */
    public static List<ThreatSheet> sheets() {
        return sheets;
    }

    /** Guarda a lista vinda do servidor. */
    public static void setSheets(List<ThreatSheet> value) {
        sheets = value == null ? List.of() : new ArrayList<>(value);
        // Rascunho de ficha que nao existe mais (apagada por outra tela) nao pode
        // continuar mostrando bolinha. O editor aberto recoloca o dele no proximo
        // quadro, se ainda estiver sujo -- o rascunho vivo nunca passa fome.
        Set<String> alive = new HashSet<>();
        for (ThreatSheet sheet : sheets) {
            if (sheet != null && sheet.hasId()) {
                alive.add(sheet.id());
            }
        }
        drafts.keySet().retainAll(alive);
    }

    /** Ha edicao nao salva para esta ficha? E a bolinha amarela da lista. */
    public static boolean hasDraft(String id) {
        return id != null && !id.isEmpty() && drafts.containsKey(id);
    }

    /** O rascunho guardado, ou {@code null} quando nao ha edicao nao salva. */
    public static ThreatSheetDraft draftOf(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        return drafts.get(id);
    }

    /** Guarda (ou substitui) o rascunho de uma edicao nao salva. */
    public static void putDraft(String id, ThreatSheetDraft draft) {
        if (id == null || id.isEmpty() || draft == null) {
            return;
        }
        drafts.put(id, draft);
    }

    /** A edicao foi salva (ou descartada): some a bolinha. */
    public static void removeDraft(String id) {
        if (id != null) {
            drafts.remove(id);
        }
    }

    /** Saiu do mundo: os rascunhos eram daquele mundo (mesma regra do diario). */
    public static void clearDrafts() {
        drafts.clear();
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