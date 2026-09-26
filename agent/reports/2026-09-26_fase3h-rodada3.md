# Implementation Report

## Status
PARTIAL. Build, encoding gate, mixin injection and jar are VALIDATED. Two items
are implemented but NOT seen in game and depend on a user test session.

## Objective
Rodada 3 do projeto Tabletop RPG (mod Fabric para Minecraft 1.21.11), conforme o
plano em `agent/HANDOFF.md`: mover a barra da lista de skills para a borda
direita, instrumentar o mixin do braco do caido em vez de continuar especulando,
transformar a varredura de encoding em parte do build, e condensar o AGENTS.md
global com a regra anti-ideograma.

## Scope / Subtasks
1. Testar um subagente em uma chamada (quota da sessao anterior).
2. Barra da lista de skills na borda direita + `barReserved` de volta a 0.
3. Instrumentar `DownedBodyAlignMixin` para obter evidencia em runtime.
4. Criar varredura de encoding dentro do laco de build.
5. Condensar `C:\Users\Pedro\.config\opencode\AGENTS.md` + regra anti-ideograma.
6. Perguntar ao usuario sobre o botao "x" e o layout do Status.

## What Changed

### 1. Barra da lista de skills (SkillsScreen.java)
- `listBarX`: `removeX - BAR_PAD - BAR_W` -> `x0 + panelW - BAR_W - BAR_PAD`.
- `barReserved` fixado em 0; a variavel `listaTransborda` foi removida por ter
  ficado sem uso.
- `renderListScrollbar` e `onListScrollbar` nao mudaram: ambas ja testam
  transbordo da lista nas suas proprias linhas (301 e 320), entao a barra
  continua aparecendo so quando necessario.

### 2. Instrumentacao do braco do caido (sem correcao de comportamento)
Nenhuma correcao foi tentada nesta rodada, porque as duas hipoteses anteriores
falharam. O que existe agora e medicao:
- `DownedBodyAlignMixin` reorganizado (mesma ordem de condicoes; nada e tocado
  quando nao aplica) com uma linha de log a cada 10 chamadas de `Player`.
- `CinematicCameraRig` guarda o yaw e o partialTick realmente aplicados.
- `CameraMixin` chama o gravador no mesmo ponto onde aplica a rotacao.

Campos do log: `spec`, `down`, `rig`, `pT`, `camYaw`, `bRotB` (bodyRot antes),
`bRotW` (bodyRot que o mixin escreveu no frame anterior), `yRotB`, `camAw`,
`camPT`, e `WROTE` ou `SKIP`.

### 3. Mojibake real reparado
`CombatController.java`, 5 linhas de comentario: `D ncoras` (x2), `D ncora` (x2)
e um fragmento corrompido que era `(+1)`. Reparo palavra a palavra, sem
conversao global de encoding.

### 4. `scanEncoding` (build.gradle, novo)
Task Gradle de varredura; `check` depende dela, logo roda em toda `build`. Nao
converte encoding, apenas reporta. FALHA em CJK/hangul/kana/fullwidth, U+FFFD e
marcadores de mojibake sem grafia portuguesa possivel. INFO para acentos e
tipografia.

### 5. AGENTS.md global
279 -> 176 linhas. Regra anti-ideograma adicionada, mais duas secoes que estao
sendo aprendidas nesta rodada: "What a green build does not prove" e "Shell
discipline". ASCII puro, sem BOM.

## Files Changed
- `src/client/java/com/pedro/tabletoprpg/client/SkillsScreen.java`
- `src/client/java/com/pedro/tabletoprpg/client/CinematicCameraRig.java`
- `src/client/java/com/pedro/tabletoprpg/client/mixin/DownedBodyAlignMixin.java`
- `src/client/java/com/pedro/tabletoprpg/client/mixin/CameraMixin.java`
- `src/main/java/com/pedro/tabletoprpg/CombatController.java`
- `build.gradle`
- `agent/HANDOFF.md`
- `agent/memory/ai-operational-discipline.md`
- `agent/memory/project-memory.md`
- (fora do repo) `C:\Users\Pedro\.config\opencode\AGENTS.md`

## Decisions
- **Nao chutar o braco do caido.** Duas hipoteses ja falharam; a rodada mede.
- **Nenhum descriptor de mixin novo.** A comparacao `bRotB` vs `bRotW`
  discrimina a hipotese de sobrescrita sem criar um segundo ponto de injecao,
  o que evita repetir o crash real de descriptor errado em metodo generico.
- **Calibrar o detector por evidencia, nao por palpite.** Ver abaixo.
- **Escopo:** o reparo de mojibake em `CombatController.java` estava fora do
  escopo dos subtarefas 1-3, mas e o que torna o gate novo utilizavel sem
  quebrar o build. Sao 5 linhas de comentario, risco de runtime zero, e o
  usuario pediu a varredura de todo o projeto.

## Validation
| Validacao | Resultado |
|---|---|
| `gradlew build` | BUILD SUCCESSFUL (13s) |
| `scanEncoding` (dentro do build) | 57 arquivos, 0 mojibake, 0 ideograma, 0 U+FFFD |
| `gradlew runClient` | `run/logs/latest.log` contem "Sound engine started"; 0 crash reports; 0 erros de mixin |
| Jar | `build/libs/tabletop-rpg_TESTE_26-09-2026_0443.jar`, 440.603 bytes, 103 entradas, 85 classes; caminho de classe conferido antes do marcador |
| Registro do mixin no jar | `DownedBodyAlignMixin.class` existe e consta do `tabletop-rpg.client.mixins.json` |
| `AGENTS.md` global | 176 linhas, 9.240 bytes, 0 nao-ASCII, sem BOM |
| Subagente | `debugger` respondeu corretamente; a quota da sessao anterior nao era persistente |

## Problems Encountered

### 1. Loop de one-liner de PowerShell (o usuario abortou o comando)
Um one-liner para agregar codepoints usou duas APIs inexistentes
(`Encoding::ConvertToUtf32`, `RealIsChar::IsSurrogatePair`) dentro de um `while`
sobre ~50 arquivos. Cada arquivo repetiu `MethodNotFound`; o console saturou e o
usuario abortou. Nao foi loop do modelo, foi comando infinitamente repetidor.

### 2. Detector de encoding errado, que teria quebrado o build
A primeira versao classificava travessao, ordinal "3a" e "A" com til como
caracteres proibidos e acusou 158 ocorrencias em `src/`. Todas eram portugues
legitimo em JavaDoc existente. O detector estava errado, nao o codigo.

### 3. Dois crashes reais de mixin que o `build` nao pegou
- `contains non-private static field DIAG_ENABLED:Z`
- `contains non-private static method tabletopRpg$diagAppliedYaw()F`

Em ambos, `build` ficou verde. So `runClient` pegou.

### 4. Gradle: closure de script invisivel dentro de `doLast`
`def minhaFn = { ... }` chamada dentro de `doLast`.resultou em
`NullPointerException: Cannot invoke "groovy.lang.Closure.call(Object)" because
"closure" is null`. Resolvido com `ext.` + `project.minhaFn(...)`.

## Root Causes

| Sintoma | Causa raiz (evidencia) |
|---|---|
| Loop de PS | Duas APIs inexistentes dentro de laco; uma linha de log por iteracao |
| 158 falsos positivos | Heuristica classify caracteres legitimos como proibidos, sem prova de impossibilidade |
| 2 crashes de mixin | Campo e metodo estatico de mixin tem que ser `private`; o `build` nao valida injecao |
| NPE no Gradle | Variavel local `def` de script nao e visivel em acao diferida |

## Fixes
- Varredura de arquivo: proibida em one-liner de PS; usar `grep`/`read` ou a
  task `scanEncoding`. Registrado em `ai-operational-discipline.md`.
- Detector recalibrado: FALHA so no que e impossivel em portugues correto.
- Campos e metodos estaticos de mixin movidos para `private`; o estado
  compartilhado foi para `CinematicCameraRig`, que e classe normal.
- `ext.minhaFn` no lugar de `def minhaFn`; relatorio cosmetico reescrito
  imperativo.
- **Ideograma em texto portugues: 2 ocorrencias nesta sessao**, ambas corrigidas
  e verificadas por codepoint. Uma delas foi em
  `ai-operational-discipline.md`, o proprio arquivo que documenta a regra.

## Remaining Issues
1. **Braco do caido**: instrumentado, sem dado. Depende de uma sessao de teste.
   `DIAG_ENABLED = true` esta ligado; remover os blocos `DIAG_TEMP` dos 3
   arquivos ao concluir o diagnostico.
2. **Barra da lista**: implementada, nao vista em jogo.
3. **Conflito barra/X, nao resolvido e aritmetico**: a faixa de clique da barra
   e `[x0+panelW-12, x0+panelW)`, que cai dentro do retangulo do botao X
   (`[x0+panelW-16, x0+panelW)`). Como `mouseClicked` testa a barra antes dos
   widgets, o X deixa de responder enquanto a lista transborda. O usuario pediu
   a barra na borda; ninguem pediu perder o X. Decisao pendente dele.
4. **Botao "x" e layout do Status nunca foram confirmados** pelo usuario. O
   silencio nao e aprovacao.
5. Nada foi commitado. Nao houve push.
6. `build.gradle` nao esta coberto pela varredura (extensao `gradle` fora da
   lista), entao o proprio detector nao se autoverifica.

## Lessons / Memory
- Um one-liner com laco e API errada imprime o erro N vezes; perguntar quantas
  copias antes de disparar.
- Calibrar detector por evidencia: provar que o caractere e IMPOSSIVEL em texto
  correto, nao que ele "parece estranho".
- `build` verde nao valida injecao de mixin nem registro em framework.
- `runClient` que nao retorna e sinal de sucesso; confirmar pelo log.
- Em mixin, estatico e `private`.
- A regra anti-ideograma violated e o arquivo que a documenta. Checar encoding
  depois de cada escrita, nunca so no fim.

## Next Steps
1. Testar em jogo e enviar o trecho `[DownAlign]` do `run/logs/latest.log`.
   Ler a tabela do `HANDOFF.md` para diagnosticar.
2. Responder: o botao "x" aparece e e clicavel? O layout do Status esta bom?
   A barra na borda era o desejado?
3. Decidir o conflito barra/X: mover o X 9px para a esquerda (o nome perde 9px)
   ou manter a barra sobre o botao.
4. Remover os blocos `DIAG_TEMP` apos o diagnostico.
5. Commit so depois das respostas acima.
