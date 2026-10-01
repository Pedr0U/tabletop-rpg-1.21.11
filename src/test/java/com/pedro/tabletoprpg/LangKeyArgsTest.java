package com.pedro.tabletoprpg;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Confere que toda {@code Component.translatable("chave", ...)} passa a quantidade
 * de argumentos que a mensagem em {@code en_us.json} realmente usa.
 *
 * <p><b>Por que este teste existe (01/10/2026):</b> o preset de rolagem trouxe a
 * mensagem {@code preset_already_exists} com tres {@code %s}, e a chamada passava um
 * argumento so. Isso <b>nao falha a compilacao nem o build</b>: o erro so aparece quando
 * alguem digita o comando, porque e o {@code TranslatableFormatException}, lancada na
 * hora de desenhar a mensagem no chat. Ou seja, um build verde escondia um comando
 * quebrado. Este teste varre o fonte e falha no build.
 *
 * <p><b>Por que contar argumentos olhando o texto e seguro:</b> a contagem ignora
 * virgulas dentro de literais, de arrays e de chamadas aninhadas, contando apenas as
 * virgulas no nivel mais externo da chamada. E so checa chave de literal: quando a
 * chave vem de uma variavel nao ha como saber, e o teste nao reclama.
 *
 * <p><b>Argumentos a mais nao sao erro:</b> {@code String.format} ignora o que sobra.
 * Ja argumentos a menos lancam excecao, e e esse o caso que este teste vigia.
 */
class LangKeyArgsTest {

    /** Entrada do lang: {@code "chave": "valor"}. */
    private static final Pattern LANG_ENTRY =
            Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private static final Pattern TRANSLATABLE =
            Pattern.compile("translatable\\s*\\(");

    /** Ache a raiz do projeto subindo ate existir {@code src/main/resources}. */
    private static Path projectRoot() {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            if (Files.isRegularFile(dir.resolve("src/main/resources/assets/tabletop-rpg/lang/en_us.json"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("raiz do projeto nao encontrada a partir de " + System.getProperty("user.dir"));
    }

    private static Map<String, Integer> percentCounts() throws IOException {
        Path lang = projectRoot().resolve("src/main/resources/assets/tabletop-rpg/lang/en_us.json");
        String text = Files.readString(lang, StandardCharsets.UTF_8);
        Map<String, Integer> counts = new LinkedHashMap<>();
        Matcher m = LANG_ENTRY.matcher(text);
        while (m.find()) {
            String key = m.group(1);
            String value = m.group(2);
            int count = 0;
            int idx = value.indexOf("%s");
            while (idx >= 0) {
                count++;
                idx = value.indexOf("%s", idx + 2);
            }
            counts.put(key, count);
        }
        assertTrue(counts.size() > 10, "en_us.json parece nao ter sido lido: só " + counts.size() + " chaves");
        return counts;
    }

    /** Quantos argumentos de nivel externo a chamada que comeca logo apos o {@code open} tem. */
    private static int countTopLevelArgs(String source, int openIndex) {
        int depth = 1;
        int commas = 0;
        boolean inString = false;
        boolean inChar = false;
        for (int i = openIndex + 1; i < source.length(); i++) {
            char c = source.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (inChar) {
                if (c == '\\') {
                    i++;
                } else if (c == '\'') {
                    inChar = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '\'') {
                inChar = true;
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
                if (depth == 0) {
                    return commas + 1;
                }
            } else if (c == ',' && depth == 1) {
                commas++;
            }
        }
        return commas + 1;
    }

    /**
     * O primeiro argumento e um literal de texto usado como chave completa?
     *
     * <p>Devolve {@code null} quando a chave vem de uma variavel ou quando o literal
     * e so o comeco de uma concatenacao ({@code "chave." + suffixo}): nesses casos a
     * chave real so existe em tempo de execucao e nao ha o que conferir.
     */
    private static String firstStringArg(String source, int openIndex) {
        int i = openIndex + 1;
        while (i < source.length() && Character.isWhitespace(source.charAt(i))) {
            i++;
        }
        if (i >= source.length() || source.charAt(i) != '"') {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int j = i + 1; j < source.length(); j++) {
            char c = source.charAt(j);
            if (c == '\\') {
                sb.append(source.charAt(j + 1));
                j++;
            } else if (c == '"') {
                int k = j + 1;
                while (k < source.length() && Character.isWhitespace(source.charAt(k))) {
                    k++;
                }
                if (k < source.length() && source.charAt(k) == '+') {
                    return null; // concatenacao: a chave completa e dinamica
                }
                return sb.toString();
            } else {
                sb.append(c);
            }
        }
        return null;
    }

    @Test
    @DisplayName("toda Component.translatable passa pelo menos os %s que a mensagem usa")
    void translatableCallsHaveEnoughArguments() throws IOException {
        Map<String, Integer> percentCounts = percentCounts();
        Path root = projectRoot();

        List<String> problems = new ArrayList<>();
        int checked = 0;
        int literalCalls = 0;

        // Só o fonte do mod: varrer src/test faria este proprio arquivo se
        // reportar pelas chamadas `translatable(` citadas nos comentarios dele.
        List<Path> sourceRoots = List.of(root.resolve("src/main/java"), root.resolve("src/client/java"))
                .stream()
                .filter(Files::isDirectory)
                .toList();
        try (Stream<Path> files = sourceRoots.stream().flatMap(r -> {
            try {
                return Files.walk(r);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        })) {
            List<Path> javaFiles = files.filter(p -> p.toString().endsWith(".java")).toList();
            for (Path file : javaFiles) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                Matcher m = TRANSLATABLE.matcher(source);
                while (m.find()) {
                    checked++;
                    String key = firstStringArg(source, m.end() - 1);
                    if (key == null) {
                        continue; // chave dinamica: nao ha como conferir
                    }
                    literalCalls++;
                    Integer expected = percentCounts.get(key);
                    if (expected == null) {
                        problems.add(file + ": chave nao existe no en_us.json: " + key);
                        continue;
                    }
                    int args = countTopLevelArgs(source, m.end() - 1);
                    // A chave entra na contagem de argumentos, entao uma mensagem
                    // com N %s precisa de N + 1 argumentos na chamada (a chave mais N
                    // valores). Comparar direto com N daria uma folga de um valor.
                    int required = expected + 1;
                    if (args < required) {
                        problems.add(file + ": " + key + " usa " + expected
                                + " x %s mas a chamada tem " + args + " argumento(s) (chave + "
                                + (args - 1) + " valor(es)) -> TranslatableFormatException ao desenhar no chat");
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        assertTrue(checked > 50, "varredura vazia: só " + checked + " chamadas de translatable");
        if (!problems.isEmpty()) {
            fail("chave de lang com argumentos errados:\n  " + String.join("\n  ", problems)
                    + "\n(" + literalCalls + " chamadas com chave literal de " + checked + " no total)");
        }
    }

    @Test
    @DisplayName("toda chave nova do preset de rolagem existe no en_us.json")
    void presetLangKeysExist() throws IOException {
        Map<String, Integer> percentCounts = percentCounts();
        List<String> required = List.of(
                "item.tabletop-rpg.roll_preset",
                // "item.tabletop-rpg.roll_preset.named" saiu em 01/10/2026: o nome
                // do item passou a ser o nome escolhido pela jogadora, escrito
                // literal em ModItems.buildRollPresetStack, sem prefixo e sem lang.
                "item.tabletop-rpg.roll_preset.formula",
                "item.tabletop-rpg.roll_preset.color",
                "item.tabletop-rpg.roll_preset.no_preset",
                "message.tabletoprpg.preset_created",
                "message.tabletoprpg.preset_already_exists",
                "message.tabletoprpg.preset_edited",
                "message.tabletoprpg.preset_deleted",
                "message.tabletoprpg.preset_not_found",
                "message.tabletoprpg.preset_no_preset_on_item",
                "message.tabletoprpg.preset_create_failed",
                "message.tabletoprpg.preset_players_only",
                "message.tabletoprpg.preset_limit",
                "message.tabletoprpg.preset_no_room",
                "message.tabletoprpg.preset_given",
                "message.tabletoprpg.preset_given_all",
                "message.tabletoprpg.preset_list_empty",
                "message.tabletoprpg.preset_list_entry",
                "message.tabletoprpg.preset_name_required",
                "message.tabletoprpg.preset_no_data_on_item");
        List<String> missing = required.stream().filter(k -> !percentCounts.containsKey(k)).toList();
        if (!missing.isEmpty()) {
            fail("chaves de lang do preset que faltam no en_us.json: " + missing);
        }
    }
}
