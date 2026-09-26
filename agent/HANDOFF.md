# HANDOFF - estado da sessao (26/09/2026, rodada 3)

Leia este arquivo primeiro ao retomar. Tudo aqui esta em disco.

## Status: 1 item validado em runtime, 1 item NAO validado (instrucao pronta)

- `.\gradlew.bat build` -> BUILD SUCCESSFUL (13s), agora **inclui** a varredura
  de encoding como gate (`scanEncoding`).
- `.\gradlew.bat runClient` -> **"Sound engine started" no `run/logs/latest.log`,
  0 crash reports, 0 erros de mixin.** Os dois crashes intermediarios
  (abaixo) foram REAIS e estao corrigidos.
- Jar: `build/libs/tabletop-rpg_TESTE_26-09-2026_0443.jar` (440.603 bytes,
  103 entradas, 85 classes). Caminho de classe conferido ANTES do marcador,
  e `DownedBodyAlignMixin` esta registrado no `tabletop-rpg.client.mixins.json`.
- Nenhum commit. Branch `main`, HEAD 9bfabbe.

## O QUE MUDOU NESTA RODADA

### 1. Barra da lista de skills -> borda direita (PENDENTE de confirmacao visual)

`SkillsScreen.java`, bloco de layout (ex-168..181):

- `listBarX` = `x0 + panelW - BAR_W - BAR_PAD` (era `removeX - BAR_PAD - BAR_W`).
- `barReserved` = 0, e `listaTransborda` foi removido (virou codigo morto).
  O nome volta a ter a largura inteira.
- `renderListScrollbar` e `onListScrollbar` ja tem o proprio teste de
  transbordo (linhas 301 e 320), por isso nao precisaram mudar.

**CONSEQUENCIA NAO RESOLVIDA, decideda pelo usuario e nao por mim:** a faixa
de clique da barra e `[listBarX-BAR_PAD, listBarX+BAR_W+BAR_PAD)` =
`[x0+panelW-12, x0+panelW)`, que cai DENTRO do retangulo do botao X
(`removeX = x0+panelW-16`, largura 16). Como `mouseClicked` testa a barra da
lista ANTES dos widgets, **o X deixa de responder enquanto a lista
transborda**. Isso e aritmetica, nao opiniao. Perguntar ao usuario se ele
prefere: (a) barra na borda e X clicavel -> e preciso mover `removeX` para
esquerda (rouba ~9px do nome), ou (b) barra na borda e X nao clicavel (estado
atual).

### 2. Braco do caido: INSTRUMENTADO, AGUARDANDO DADO DO USUARIO

Duas hipoteses minhas ja tinham falhado. Nao ha correcao nova aqui - ha
**medicao**.

O que foi feito:

- `DownedBodyAlignMixin`: reorganizado (mesma ordem de condicoes, nada e
  tocado quando nao aplica) para caber a instrumentacao. Uma linha de log a
  cada 10 chamadas de `Player`, com:
  `spec`, `down`, `rig`, `pT` (partialTick do render), `camYaw`, `bRotB`
  (bodyRot antes), `bRotW` (bodyRot que NOS escrevemos no frame anterior),
  `yRotB`, `camAw` (yaw que a camera REALMENTE aplicou), `camPT`, e `WROTE`
  ou `SKIP`.
- `CinematicCameraRig`: guarda o yaw/parcialTick realmente aplicado.
- `CameraMixin`: chama o gravador no mesmo ponto que aplica a rotacao.

**Log a procurar:** `run/logs/latest.log`, linhas `[DownAlign]`.

Como ler o resultado:

| Observado | Diagnostico |
|---|---|
| `SKIP` com `down=1 spec=1` | nunca escrevemos; o early-return e o problema |
| `bRotB != bRotW` | **outro codigo sobrescreve DEPOIS do nosso TAIL** (hipotese (b) CONFIRMADA) |
| `pT != camPT` | **render de entidade usa outro partialTick** (hipotese (c) CONFIRMADA) |
| `WROTE`, `bRotB == bRotW == camAw`, corpo ainda nao vira | o problema NAO e este mixin; e o que consome `bodyRot` adiante |

A comparacao `bRotB` vs `bRotW` substituiu a necessidade de um segundo ponto
de injecao: nao foi criado nenhum descriptor novo, porque o historico deste
projeto tem um crash real por descriptor errado em metodo generico.

**`DIAG_ENABLED = true` esta LIGADO.** Desligar exige build. Ligar log a cada
10 frames em jogo e inofensivo; ao terminar o diagnostico, remover os blocos
`DIAG_TEMP` dos 3 arquivos.

### 3. Mojibake real reparado (5 linhas de comentario)

`CombatController.java`: `D ncoras` x2, `D ncora` x2 (linhas 56, 59, 165,
175) e um fragmento corrompido na 210, que era `(+1)`. Reparo palavra a
palavra, **sem conversao global de encoding** (proibida: destroi acentos).

### 4. `scanEncoding`: varredura de encoding dentro do build (NOVO)

`build.gradle`, task `scanEncoding`, e `check` depende dela - logo roda em
**toda** `build`. Nao converte nada, so reporta.

- **FAIL:** CJK, hangul, kana, fullwidth, U+FFFD e marcadores de mojibake sem
  grafia portuguesa possivel (inclui 0xD0, o caso real do repo).
- **INFO:** todo o resto nao-ASCII (acentos latinos, travessao, aspas
  tipograficas), contado por arquivo.
- Uso isolado: `.\gradlew.bat scanEncoding --console=plain`.

Estado: 57 arquivos verificados, 0 falhas.

## CRASHES REAIS ENCONTRADOS NESTA RODADA (build NAO pegou, runClient pegou)

O `build` ficou VERDE nos dois casos abaixo. So o `runClient` pegou.

1. `InvalidMixinException: contains non-private static field DIAG_ENABLED:Z`
   - campo estatico `public` em mixin.
2. `InvalidMixinException: contains non-private static method
   tabletopRpg$diagAppliedYaw()F`
   - metodo estatico `public` em mixin tambem e recusado.
   - **Solucao:** estado de diagnostico entre classes mora em classe NORMAL
     (`CinematicCameraRig`), nunca em mixin.

**REGADURAO:** campo e metodo estatico de mixin tem que ser `private`.

## PERMANECEM SEM RESPOSTA (o usuario nao comentou; silencio nao e aprovacao)

1. O **botao "x"** de remover skill: bom? visivel? clicavel agora que a barra
   esta na borda?
2. O **layout do Status** (largura 600, PAD 8, FIELD_W_MAX 190): bom?
3. A **barra da lista** na borda direita: era isso que ele queria?
4. O conflito barra/X descrito no item 1: qual das duas opcoes?

## NAO VALIDADO / NAO FEITO (dizer textualmente, nunca "pronto para teste")

- O comportamento do braco do caido **nao** foi validado: depende de uma
  sessao de teste com o log em maos.
- O novo layout da barra **nao** foi visto em jogo.
- Nada foi commitado.
- Nao houve push.

## LEMBRETE OPERACIONAL (custou um corte do usuario nesta rodada)

One-liner longo de PowerShell com API errada dentro de laco imprime o erro
N vezes e o usuario precisa abortar. Varredura de arquivo: usar `grep`/`read`
ou a task `scanEncoding`. Detalhes em
`agent/memory/ai-operational-discipline.md`.
