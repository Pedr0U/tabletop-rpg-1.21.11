package com.pedro.tabletoprpg;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Presets de Rolagem de cada jogador, em memoria.
 *
 * <p><b>01/10/2026.</b> A persistencia em disco e do
 * {@code PlayerRollPresetPersistenceMixin}, no padrao ja usado pela ficha
 * ({@code ValueOutput.store} / {@code ValueInput.read} com a chave
 * {@value #NBT_KEY}); aqui fica so o cache, como o {@link SessionManager} faz com a ficha.
 *
 * <p><b>Mudanca de 01/10/2026: o mapa virou lista.</b> Era
 * {@code Map<String, RollPreset>} ordenado por nome a cada leitura. Duas coisas
 * quebraram com isso: as setas de reordenar da tela de presets nao tinham onde gravar
 * (um mapa nao tem ordem), e um preset cuja formula mudou precisava ser reposicionado
 * por causa do alfabeto. Agora {@link #list} devolve na ordem que a jogadora montou, e
 * {@link #move} grava essa ordem.
 *
 * <p><b>Por que a busca varre a lista.</b> Trocar a lista por mapa teria resolvido a
 * ordem, mas a ordem e o que importa. Com teto de {@value #MAX_PRESETS} entradas, a
 * varredura e curta e o preco de manter um indice derivado (e remembera-lo em toda
 * escrita) nao se paga.
 *
 * <p><b>Por que a chave e o {@link RollPreset#key()} e nao o nome cru:</b> ver o metodo
 * {@code key}. E o que faz {@code Ataque}, {@code ataque} e {@code ATAQUE} acharem o
 * mesmo preset.
 *
 * <p><b>Como o preset salvo continua sendo lido:</b> o {@link #CODEC} le a lista nova
 * e, com {@code withAlternative}, o mapa antigo. Quem ja tinha preset gravado nao perde
 * nada: a migracao ordena por nome, que e a ordem que a versao anterior exibia.
 */
public final class RollPresetStore {

    /** Chave deste bloco dentro do NBT do jogador. */
    public static final String NBT_KEY = "tabletoprpg_roll_presets";

    /**
     * Teto por jogador. Nao e uma regra do jogo: e o que segura um NBT editado a mao de
     * meter 1 milhao de presets e travar a carga do jogador. O comando avisa quando bate.
     */
    public static final int MAX_PRESETS = 64;

    /**
     * A lista, na ordem da jogadora, com o mapa antigo como alternativa de leitura.
     *
     * <p><b>Por que a alternativa e a LISTA e nao o mapa:</b> o {@code withAlternative}
     * testa a alternativa so quando o codec principal falha. Como lista e mapa sao
     * distinguiveis pelo tipo de tag do NBT (lista contra compound), quem foi gravado
     * no formato novo le pela lista; quem estava no antigo, cujo mapa comeca por uma
     * compound, falha na lista e cai no mapa. Se o principal fosse o mapa, um preset
     * novo gravado como lista nunca voltaria, porque um compound falharia em lista e
     * cairia no mapa vazio.
     */
    public static final Codec<List<RollPreset>> CODEC =
            Codec.list(RollPreset.CODEC).withAlternative(mapAsListCodec());

    /**
     * O formato ANTIGO, que era um mapa, lido como lista.
     *
     * <p><b>Por que o mapa antigo e ordenado por nome na migração:</b> era a ordem que a
     * versao anterior mostrava na tela. Migrar para essa ordem preserva o que a jogadora
     * via, em vez de entregar uma lista embaralhada pela ordem do {@code HashMap} (que
     * muda com o hash da string, e portanto entre sessoes).
     */
    private static Codec<List<RollPreset>> mapAsListCodec() {
        return Codec.unboundedMap(Codec.STRING, RollPreset.CODEC).xmap(
                map -> {
                    List<RollPreset> out = new ArrayList<>(map.values());
                    out.sort(Comparator.comparing(p -> p.name().toLowerCase(Locale.ROOT)));
                    return out;
                },
                list -> {
                    Map<String, RollPreset> map = new LinkedHashMap<>();
                    for (RollPreset preset : list) {
                        map.put(preset.key(), preset);
                    }
                    return map;
                });
    }

    /** A ordem da lista. O indice e derivado dela, nunca a fonte da verdade. */
    private static final Map<UUID, List<RollPreset>> PRESETS = new HashMap<>();

    private RollPresetStore() {
    }

    /**
     * Todos os presets da jogadora, <b>na ordem em que ela montou</b>.
     *
     * <p>Copia defensiva: quem chama pode reordenar a lista devolvida (a tela faz isso
     * enquanto a jogadora arrasta as setas) sem mexer no cache antes do {@link #save}.
     */
    public static List<RollPreset> list(UUID playerUuid) {
        List<RollPreset> stored = playerUuid == null ? null : PRESETS.get(playerUuid);
        return stored == null ? new ArrayList<>() : new ArrayList<>(stored);
    }

    /** Procura pelo nome digitado, ja normalizado. */
    public static Optional<RollPreset> find(UUID playerUuid, String rawName) {
        if (playerUuid == null || rawName == null) {
            return Optional.empty();
        }
        List<RollPreset> stored = PRESETS.get(playerUuid);
        if (stored == null) {
            return Optional.empty();
        }
        String key = RollPreset.normalizeKey(rawName);
        for (RollPreset preset : stored) {
            if (preset.key().equals(key)) {
                return Optional.of(preset);
            }
        }
        return Optional.empty();
    }

    /** A posicao do preset na ordem da jogadora, ou -1 se nao existe. */
    public static int indexOf(UUID playerUuid, String rawName) {
        if (playerUuid == null || rawName == null) {
            return -1;
        }
        List<RollPreset> stored = PRESETS.get(playerUuid);
        if (stored == null) {
            return -1;
        }
        String key = RollPreset.normalizeKey(rawName);
        for (int i = 0; i < stored.size(); i++) {
            if (stored.get(i).key().equals(key)) {
                return i;
            }
        }
        return -1;
    }

    public static boolean exists(UUID playerUuid, String rawName) {
        return find(playerUuid, rawName).isPresent();
    }

    public static int count(UUID playerUuid) {
        List<RollPreset> stored = playerUuid == null ? null : PRESETS.get(playerUuid);
        return stored == null ? 0 : stored.size();
    }

    /**
     * Grava (ou substitui) um preset, mantendo a posicao dele quando ja existia.
     *
     * <p><b>Por que a posicao e mantida:</b> editar um preset e o caso comum de
     * corrigir uma cor ou uma formula. Se o preset voltasse para o fim da lista a cada
     * edicao, a jogadora perderia o lugar dela toda vez que arrumasse um erro de
     * digitacao. Renomear e o unico caso que muda a ordem, e so porque o nome e o que
     * a tela mostra.
     *
     * @return o preset anterior com a mesma chave, ou {@link Optional#empty()}
     */
    public static Optional<RollPreset> put(UUID playerUuid, RollPreset preset) {
        if (playerUuid == null || preset == null) {
            return Optional.empty();
        }
        List<RollPreset> stored = PRESETS.computeIfAbsent(playerUuid, uuid -> new ArrayList<>());
        String key = preset.key();
        for (int i = 0; i < stored.size(); i++) {
            if (stored.get(i).key().equals(key)) {
                return Optional.of(stored.set(i, preset));
            }
        }
        stored.add(preset);
        return Optional.empty();
    }

    /** @return o preset removido, ou {@link Optional#empty()} se nao havia nenhum */
    public static Optional<RollPreset> remove(UUID playerUuid, String rawName) {
        List<RollPreset> stored = playerUuid == null ? null : PRESETS.get(playerUuid);
        if (stored == null) {
            return Optional.empty();
        }
        int index = indexOf(playerUuid, rawName);
        if (index < 0) {
            return Optional.empty();
        }
        RollPreset removed = stored.remove(index);
        prune(playerUuid, stored);
        return Optional.of(removed);
    }

    /**
     * Troca dois presets de lugar, para as setas da tela.
     *
     * <p>Indices fora da faixa sao ignorados: quem chama e a GUI, que pode estar com
     * uma lista velha se o servidor respondeu no meio do clique. Trocar o preset
     * errado seria pior do que nao trocar.
     *
     * @return {@code true} se a troca aconteceu
     */
    public static boolean move(UUID playerUuid, int from, int to) {
        List<RollPreset> stored = playerUuid == null ? null : PRESETS.get(playerUuid);
        if (stored == null || from < 0 || to < 0 || from >= stored.size() || to >= stored.size()
                || from == to) {
            return false;
        }
        RollPreset moved = stored.remove(from);
        stored.add(to, moved);
        return true;
    }

    /**
     * Substitui a lista inteira de uma jogadora, usado na carga do NBT.
     *
     * <p>Duplicata por chave e um preset com o mesmo nome: a carga nao deveria
     * encontrar um, porque {@link #put} mantem uma chave so, mas um NBT editado a mao
     * pode. A primeira ocorrencia ganha, como em {@link #put}.
     */
    public static void replaceAll(UUID playerUuid, List<RollPreset> loaded) {
        if (playerUuid == null) {
            return;
        }
        if (loaded == null || loaded.isEmpty()) {
            PRESETS.remove(playerUuid);
            return;
        }
        List<RollPreset> clean = new ArrayList<>();
        Map<String, Boolean> seen = new HashMap<>();
        for (RollPreset preset : loaded) {
            if (preset == null || seen.putIfAbsent(preset.key(), Boolean.TRUE) != null) {
                continue;
            }
            clean.add(preset);
        }
        if (clean.isEmpty()) {
            PRESETS.remove(playerUuid);
            return;
        }
        PRESETS.put(playerUuid, clean);
    }

    /** Solta o cache de quem saiu. O NBT ja foi gravado pelo proprio logout do vanilla. */
    public static void forget(UUID playerUuid) {
        if (playerUuid != null) {
            PRESETS.remove(playerUuid);
        }
    }

    /** A lista pronta para o {@link #CODEC}, na ordem da jogadora. */
    public static List<RollPreset> snapshot(UUID playerUuid) {
        return list(playerUuid);
    }

    /** Esvazia a lista quando sobrou preset nenhum, para nao gravar cache vazio. */
    private static void prune(UUID playerUuid, List<RollPreset> stored) {
        if (stored.isEmpty()) {
            PRESETS.remove(playerUuid);
        }
    }
}