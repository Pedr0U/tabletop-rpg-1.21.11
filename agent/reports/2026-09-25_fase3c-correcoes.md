# Implementation Report

## Status

**COMPLETO** (build verde, revisto). Os 6 feedbacks foram implementados.
**Pendente de teste em jogo:** so o `gradlew build` valida isto; o comportamento
em runtime precisa ser confirmado pelo usuario no cliente.

## Objective

Corrigir e estender a ficha de personagem (FASE 3c) a partir de 6 feedbacks:

1. Personagem deitado "flickera" (tenta levantar, deita, repete).
2. Telas Status e Skills visualmente aprovadas (sem mudanca pedida).
3. Nao consegue editar atributo, nome, raca, origem; skills "nao estao la".
4. Mana com 0 MAX -> default 4.
5. HP e Mana podem ficar acima do maximo (ex.: 12/10 = 10 + 2 temporarios).
6. Skills: nome + descricao, com a descricao no tooltip ao passar o mouse.

## Scope / Subtasks

| # | Subtask | Resultado |
|---|---------|-----------|
| 1 | Registrar EditBoxes como widgets | OK |
| 2 | Mixin de pose contra o flicker | OK |
| 3 | Mana 4/4 + recurso acima do maximo | OK |
| 4 | Modelo/codec/payload de skill com descricao | OK |
| 5 | SkillsScreen: input de descricao + tooltip | OK |
| 6 | StatusScreen: barra com excedente | OK (corrigido na revisao) |
| 7 | Build + revisao + correcoes | OK |

## What Changed

### 1. Bug de edicao (causa raiz confirmada)

`createFieldBox` criava a `EditBox` e a guardava no mapa `fieldBoxes`, mas
**nunca a registrava** como widget. Sem estar em `Screen.children()`, a caixa
nao recebia clique, nem foco, nem teclado, e **nao era desenhada**.

Isso explicava de uma vez os dois sintomas ("nao consigo editar" + "os campos
nao estao la"). O defeito existia desde a FASE 3 -- o redesign nao o introduziu.
As skills funcionavam porque a caixa delas *e* registrada.

Correcao: `addRenderableWidget(box)` dentro de `createFieldBox`.

### 2. Flicker do deitado

`Player.updatePlayerPose()` roda a cada tick e recalcula a pose pelas condicoes
do jogo; como o jogador deitado nao esta nadando, ele volta a `STANDING` todo
tick. O mod aplicava `SWIMMING` no `END_SERVER_TICK` e os dois brigavam por
posse da pose a cada tick.

Correcao na origem: novo `PlayerPoseMixin` cancela `updatePlayerPose` no HEAD
quando `DamageControlHandler.isDowned(serverPlayer)`. A pose nunca e sobrescrita,
entao nao ha mais alternancia. A aplicacao no `END_SERVER_TICK` continua
necessaria para *definir* a pose na primeira vez.

### 3. Mana 4/4 e recurso acima do maximo

- `Vitals.defaults()`: `(10, 10, 0, 0)` -> `(10, 10, 4, 4)`. Com `manaMax = 0`
  o clamp `0..manaMax` travava a barra em zero e o `+` nao tinha para onde ir.
- Teto do valor atual: `hpMax`/`manaMax` -> `MAX_RESOURCE`. Com o teto no
  maximo, o excedente era truncado no construtor e o `+` nunca passaria de
  10/10. O *maximo* em si continua valendo `1..MAX_RESOURCE` (HP) e
  `0..MAX_RESOURCE` (Mana).
- `drawBarSplit` na base + cores `COL_HP_OVER` / `COL_MANA_OVER`.
- Denominador visual = `max(valor, maximo)`: a parte que cabe no maximo usa a
  cor normal e o excedente uma cor mais clara, lado a lado. Quando o temporario
  acaba, a barra volta a encher de verdade (100%).

### 4. Skill com descricao

- `SheetData`: `List<String> skills` -> `List<Skill>`, novo
  `record Skill(name, description)` com `STREAM_CODEC` proprio e
  `SKILL_DESC_MAX = 120`.
- `RpgNetworking.SheetSkillPayload` ganhou o campo `description`
  (4 campos, dentro do limite de 6 do `StreamCodec.composite`).
- `SkillsScreen`: segunda `EditBox` ("description (optional)") no rodape,
  marcador `*` nas skills com descricao e tooltip proprio.
- Assinatura de `renderContent` na base passou a receber `mouseX/mouseY`: o
  hover so pode ser decidido no render.

## Files Changed

- `src/main/java/com/pedro/tabletoprpg/SheetData.java`
- `src/main/java/com/pedro/tabletoprpg/RpgNetworking.java`
- `src/main/java/com/pedro/tabletoprpg/mixin/PlayerPoseMixin.java` (novo)
- `src/main/resources/tabletop-rpg.mixins.json`
- `src/client/java/com/pedro/tabletoprpg/client/CharacterSheetScreen.java`
- `src/client/java/com/pedro/tabletoprpg/client/StatusScreen.java`
- `src/client/java/com/pedro/tabletoprpg/client/SkillsScreen.java`

## Decisions

- **Skill duplicada atualiza a descricao** em vez de ser ignorada. Sem isso o
  mestre nao teria como corrigir um texto errado, e a tela nao mostra a
  descricao atual para copiar. A lista nao cresce (o limite de 24 e preservado).
- **Tooltip desenhado a mao** em vez da API do vanilla: em 1.21.11 ela virou
  `GuiGraphics.renderTooltip(Font, List<ClientTooltipComponent>, ...)` com um
  `ClientTooltipPositioner` obrigatorio e sem overload simples de `Component`.
  Um painel proprio usa `font.split` e nao depende dessa assinatura.
- **`PlayerPoseMixin` na lista `mixins` (comum), nao `server`.** No Fabric a
  lista `server` so vale para o servidor fisico dedicado; o servidor integrado
  do singleplayer/LAN nao conta, e o flicker voltaria la. O guard
  `instanceof ServerPlayer` mantem o cliente limpo.
- **Denominador da barra = `max(valor, maximo)`** para o excedente ter onde
  aparecer. O texto continua `valor / maximo` ("12 / 10").

## Validation

- `gradlew compileJava` -- sem erros.
- `gradlew build` -- **BUILD SUCCESSFUL** (6s) e novamente apos as correcoes
  da revisao (**BUILD SUCCESSFUL**, 5s). `test` = NO-SOURCE (o projeto nao tem
  testes automatizados).
- Matematica da barra verificada a mao: `5/10` -> 50%; `12/10` -> 83% vermelho
  + 17% claro; `10/10` -> 100%; `4/0` -> barra clara cheia; `0/10`, `0/0` e
  `-3/10` (deitado) -> vazia.
- Revisao por subagente (read-only): 1 bloqueador + 2 importantes + 2 menores.
  Os 3 acionaveis foram corrigidos e revalidados.

## Problems Encountered

| Problema | Causa | Correcao |
|----------|-------|----------|
| Flicker do deitado | vanilla reescreve a pose a cada tick | mixin cancela `updatePlayerPose` |
| Nada editavel / campos invisiveis | `EditBox` nunca registrada como widget | `addRenderableWidget` |
| Mana 0 MAX travada | `manaMax = 0` + clamp `0..manaMax` | default 4/4 e teto `MAX_RESOURCE` |
| Barra cheia em 5/10 | usei `max` em vez de `min(valor, max)` no trecho normal | `Math.min(value, max)` |
| `suppressNotify` duplicado | residuo de uma substituicao de texto na `SkillsScreen` | removido |

## Root Causes

- **FACT:** as caixas de texto nunca foram registradas. Um `EditBox` fora de
  `Screen.children()` e invisivel e inerte -- os dois sintomas vieram da mesma
  linha de codigo, nao de dois bugs.
- **FACT:** `updatePlayerPose` roda a cada tick no servidor e no cliente local.
  Aplicar a pose no fim do tick nao e suficiente: o vanilla reescreve no tick
  seguinte.
- **FACT (achado na revisao):** com denominador `max` no trecho normal, todo
  valor abaixo do maximo produzia `normal = 1.0` e barra cheia.

## Fixes

1. `addRenderableWidget(box)` em `createFieldBox`.
2. `PlayerPoseMixin` + registro no JSON de mixins (lista comum).
3. `Vitals.defaults()` = 4/4; teto do valor atual = `MAX_RESOURCE`.
4. `withSkill(nome, descricao)`; `Skill` record; payload com `description`.
5. `drawBarSplit` + `drawResourceBar` com `min`/`max` corretos e clamp de 1px
   no excedente (dois `Math.round` independentes podiam somar 1px).
6. `renderContent` recebe `mouseX/mouseY`; tooltip proprio de descricao.

## Remaining Issues

- **Teste em jogo pendente.** Build verde nao prova o comportamento em runtime.
  O usuario precisa confirmar: edicao dos campos, ausencia de flicker,
  tooltip da descricao e barra 12/10.
- **As "pericias nao estao la"** provavelmente nao e bug: as fichas vivem em
  memoria e somem quando o servidor reinicia. Nao havia reproocao do sintoma
  alem disso.
- **Compatibilidade de protocolo:** `SheetSkillPayload` ganhou um campo. Cliente
  e servidor precisam do mesmo jar. Aceitavel enquanto o mod nao e publicado,
  mas `gradle.properties` continua em `1.0.0` e nao ha handshake de versao.
- **void e `/kill` ainda matam** (decisao provisoria da FASE 3b, nao confirmada).
- **Persistencia em disco** continua fora de escopo.
- **Sem testes automatizados:** a aritmetica das barras e o codec da skill
  nonverbalizados estao sem cobertura de regressao.

## Lessons / Memory

- `EditBox`/qualquer widget so funciona (e so e desenhado) depois de
  `addWidget`/`addRenderableWidget`. Guardar num mapa nao registra.
- No Fabric, a lista `server` de um mixin JSON **nao** vale para o servidor
  integrado (singleplayer/LAN). Logica que deve rodar nos dois vai em `mixins`.
- Em barra com duas partes, a parte "normal" usa `min(valor, max)` e a de
  "excedente" usa `max(0, valor - max)`, com denominador `max(valor, max)`.
- `Screen.init()` limpa os widgets, entao re-registrar no `init()` (resize) e
  seguro e correto.

## Next Steps

1. Testar em jogo os 6 itens e devolver o resultado.
2. Confirmar as decisoes provisorias: `void`/`/kill` ainda matam?
3. Decidir persistencia das fichas (hoje em memoria).
4. Se o teste passar, commitar (nao houve commit nesta sessao).
