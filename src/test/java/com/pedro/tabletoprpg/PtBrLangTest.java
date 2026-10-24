package com.pedro.tabletoprpg;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Confere que {@code pt_br.json} e uma traducao fiel de {@code en_us.json}.
 *
 * <p><b>Por que este teste existe (23/10/2026):</b> o lang foi externalizado e o
 * portugues entrou depois. Uma traducao pode facilmente perder uma chave, trocar a
 * quantidade de {@code %s} de uma mensagem ou derrubar um codigo {@code §} de cor.
 * Nenhum desses erros quebra o build: a chave some do jogo, o {@code String.format}
 * lanca excecao so na hora de desenhar no chat, e o texto perde a cor. Este teste
 * compara os dois arquivos no build.
 *
 * <p><b>O que e exigido igual:</b> o conjunto de chaves (nem falta nem sobra), a
 * contagem de {@code %s}, {@code %d} e {@code %%}, e a sequencia exata de codigos
 * {@code §} (mesmas cores e na mesma ordem). O texto em si pode (e deve) mudar.
 */
class PtBrLangTest {

    /** Entrada do lang: {@code "chave": "valor"}. */
    private static final Pattern LANG_ENTRY =
            Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

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

    private static Map<String, String> readLang(String fileName) throws IOException {
        Path lang = projectRoot().resolve("src/main/resources/assets/tabletop-rpg/lang/" + fileName);
        String text = Files.readString(lang, StandardCharsets.UTF_8);
        Map<String, String> entries = new LinkedHashMap<>();
        Matcher m = LANG_ENTRY.matcher(text);
        while (m.find()) {
            entries.put(m.group(1), m.group(2));
        }
        assertTrue(entries.size() > 10, fileName + " parece nao ter sido lido: só " + entries.size() + " chaves");
        return entries;
    }

    private static int countFormat(String value, String token) {
        int count = 0;
        int idx = value.indexOf(token);
        while (idx >= 0) {
            count++;
            idx = value.indexOf(token, idx + token.length());
        }
        return count;
    }

    /**
     * Devolve so os pares {@code §x} do valor, na ordem em que aparecem: e a cor
     * efetiva que o jogador ve, independente do texto no meio.
     */
    private static String colorSequence(String value) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == '§' && i + 1 < value.length()) {
                sb.append('§').append(value.charAt(i + 1));
                i++;
            }
        }
        return sb.toString();
    }

    @Test
    @DisplayName("pt_br.json tem as mesmas chaves, placeholders e cores do en_us.json")
    void ptBrParity() throws IOException {
        Map<String, String> en = readLang("en_us.json");
        Map<String, String> pt = readLang("pt_br.json");

        if (!en.keySet().equals(pt.keySet())) {
            StringBuilder sb = new StringBuilder();
            for (String k : en.keySet()) {
                if (!pt.containsKey(k)) {
                    sb.append("FALTA EM pt_br: ").append(k).append('\n');
                }
            }
            for (String k : pt.keySet()) {
                if (!en.containsKey(k)) {
                    sb.append("SOBRA EM pt_br: ").append(k).append('\n');
                }
            }
            fail("conjuntos de chaves diferentes:\n" + sb);
        }

        StringBuilder problems = new StringBuilder();
        for (Map.Entry<String, String> e : en.entrySet()) {
            String key = e.getKey();
            String enVal = e.getValue();
            String ptVal = pt.get(key);

            int enS = countFormat(enVal, "%s");
            int ptS = countFormat(ptVal, "%s");
            if (enS != ptS) {
                problems.append(key).append(": %s en=").append(enS).append(" pt=").append(ptS).append('\n');
            }

            int enD = countFormat(enVal, "%d");
            int ptD = countFormat(ptVal, "%d");
            if (enD != ptD) {
                problems.append(key).append(": %d en=").append(enD).append(" pt=").append(ptD).append('\n');
            }

            int enPct = countFormat(enVal, "%%");
            int ptPct = countFormat(ptVal, "%%");
            if (enPct != ptPct) {
                problems.append(key).append(": %% en=").append(enPct).append(" pt=").append(ptPct).append('\n');
            }

            String enColors = colorSequence(enVal);
            String ptColors = colorSequence(ptVal);
            if (!enColors.equals(ptColors)) {
                problems.append(key).append(": § en='").append(enColors).append("' pt='").append(ptColors).append("'\n");
            }
        }

        if (problems.length() > 0) {
            fail("paridade pt_br/en_us quebrada:\n  " + problems.toString().replace("\n", "\n  "));
        }

        assertEquals(en.size(), pt.size());
    }
}
