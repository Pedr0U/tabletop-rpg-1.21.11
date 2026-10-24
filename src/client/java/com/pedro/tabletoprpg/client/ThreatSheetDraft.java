package com.pedro.tabletoprpg.client;

import com.pedro.tabletoprpg.ThreatSheet;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * O que a tela da ficha guarda de uma edicao nao salva (07/10/2026): as caixas em
 * {@code values} mais as listas, o nome de exibicao e o HP.
 *
 * <p><b>Para que serve:</b> e o que o editor compara com o ultimo estado salvo para
 * saber se ha mudanca nao salva (a bolinha amarela), e e o que sobrevive quando o
 * Mestre fecha o editor sem salvar -- o mesmo papel do rascunho do diario. A bolinha
 * da lista e exatamente "existe rascunho para este id".
 *
 * <p><b>Modo Horda (22/10/2026):</b> {@code hordeMode} entra no rascunho como
 * qualquer outro campo do formulario: a caixa desmarcada depois de marcada tem que
 * voltar ao estado anterior com o Reverter, e o rascunho de uma sessão fechada sem
 * salvar tem que lembrar do Modo Horda ligado.
 */
public record ThreatSheetDraft(
        Map<String, String> values,
        List<String> features,
        List<ThreatSheet.Ability> passives,
        List<ThreatSheet.Action> actions,
        List<ThreatSheet.PericiaValue> pericias,
        boolean showDisplayName,
        boolean hordeMode,
        int hp) {

    /** Copia defensiva: o snapshot nunca aponta para as listas vivas do editor. */
    public ThreatSheetDraft {
        values = values == null ? new LinkedHashMap<>() : new LinkedHashMap<>(values);
        features = features == null ? List.of() : List.copyOf(features);
        passives = passives == null ? List.of() : List.copyOf(passives);
        actions = actions == null ? List.of() : List.copyOf(actions);
        pericias = pericias == null ? List.of() : List.copyOf(pericias);
    }
}