# HANDOFF — estado da sessao (26/09/2026 00:54, atualizado c/ feedback round 2)

Leia este arquivo primeiro ao retomar. Tudo aqui ja esta em disco e compilado.

## Status: PRONTO PARA TESTE DO USUARIO

- `.\gradlew.bat build` -> BUILD SUCCESSFUL.
- `gradlew runClient` -> **menu principal alcancado, nenhum crash** (valida o
  mixin novo; foi o que faltou na rodada anterior).
- Jar de teste: `build/libs/tabletop-rpg_TESTE_26-09-2026_0054.jar`
- Encoding: 0 mojibake, 0 CJK, 0 U+FFFD em `src/` e `agent/`.
- Nenhum commit. Branch `main`.

## Mudancas desta rodada (4 feedbacks do usuario)

### 1. Braco do caido com atraso — CORRIGIDO

Causa raiz (evidencia, nao hypothesis): `currentYaw` nao era suavizado, mas era
reamostrado **1x por tick (20 Hz)** dentro de `ensureRigActive`, enquanto a camera
renderiza **por frame** com `Mth.rotLerp(partialTick, previousYaw, currentYaw)`.
O corpo virava degrau, a camera virava rampa: o corpo arrastava meio tick.

Correcao em `DownedBodyAlignMixin`: usar
`CinematicCameraRig.getInterpolatedYaw(partialTick)` — exatamente o valor que o
`CameraMixin:39` passa para a camera. Fallback para
`SpectatorCameraController.getCurrentYaw()` quando o rig esta inativo
(FIRST_PERSON do proprio jogador desativa o rig mas mantem `isActive()` true).

**Correcao de comentario factualmente errado:** `yRot` e usado SO pela cabeca
(offset relativo ao corpo). Quem gira torso e bracos e `bodyRot`, na raiz do
modelo (`LivingEntityRenderer.setupRotations` ->
`mulPose(Axis.YP.rotationDegrees(180 - bodyRot))`). Antes o mixin fazia
`yRot = camYaw` E `bodyRot = camYaw`, o que travava a cabeca em 180 graus
contra-rotacionando. Agora `bodyRot = camYaw` e `yRot = 0f`.

### 2. Scroll invertido em Skills — CORRIGIDO (lista E popup)

A hipoteese "popup certo, lista errada" **nao se sustenta** no codigo: as duas
ramas usavam a mesma expressao `scroll - step` com a mesma convencao de sinal,
logo as duas estavam invertidas. O usuario validou o popup **arrastando** a
barra (que funciona por construcao) e nao pela roda do mouse; como a descricao
costuma caber em 1 tela, `maxScroll` ficava 0 e o erro nao aparecia.

`deltaY < 0` e rolar para BAIXO e precisa AVANCAR. O codigo fazia `step = +1`
e `scroll - 1`, voltando ao inicio. Agora `int step = deltaY < 0 ? 1 : -1;` e
`scroll + step` nas duas ramas.

### 3. Barra lateral na LISTA de skills — NOVO

Sistema separado do popup, para nao interferir no que ja funciona. Campos
`listBarX/Y/H`, `listThumbY/H`, `draggingListBar`; metodos `renderListScrollbar`,
`onListScrollbar`, `setSkillScrollFromMouse`. Mesmo estilo do popup (trilho
`COL_SCROLL_TRACK`, polegar `COL_SCROLL_THUMB`, polegar minimo de 8px).
Reserva espaco entre o nome e o botao X **so quando a lista transborda**.
A barra da lista e testada ANTES da do popup em `mouseClicked`, porque as faixas
podem se cruzar perto da juncao.

### 4. Botao remover skill — ajuste

O botao **ja** tinha `"X"`; troquei para `"x"` minusculo. `REMOVE_W = 16` nao foi
alterado. Se o X ainda nao aparecer, o problema e de tamanho/posicao, nao de
ausencia — o campo ja existe em `removeButtons[i]`.

### 5. Status — AJUSTADO (reversao do excesso da rodada anterior)

- `MAX_PANEL_W_STATUS`: 672 -> **600** (o usuario disse que a largura estava boa).
- `PANEL_PAD`: 14 -> **8** (era o "muito colado" original, agora moderado).
- Novo `FIELD_W_MAX = 190`: os campos de texto nao esticam mais ate a borda; o
  espaco que sobra da linha e dividido em duas metades, para centralizar.
- Atributos agora espelham a geometria de `addPericiaRow`: seta dimensionada pela
  altura da linha (`max(9, min(13, h-1))`), `WIDGET_GAP = 2`,
  `PERICIA_VALUE_W = 10`. Valor e setas ficam na mesma coluna em todas as linhas
  porque `labelW` e `valueW` sao fixos.

## ROUND 2 — resultado do teste do usuario (26/09/2026)

### CONFIRMADO RESOLVIDO

- **Scroll do mouse em Habilidades: funcionando.** A correcao do sinal
  (`int step = deltaY < 0 ? 1 : -1;` com `scroll + step`) esta certa, tanto no
  popup quanto na lista. NAO mexer mais nisso.
- O usuario nao reclamou mais do `X` do remover nem do layout do Status nesta
  rodada. **Confirmar com ele explicitamente** antes de considerar encerrado:
  ele pode apenas nao ter commenters, e o `X`/`Status` nunca foram
  confirmados como bons.

### PENDENTE 1 — posicao da barrinha da lista

Sintoma: a barra ficou **a ESQUERDA do botao de excluir** (entre o nome e o X).
O usuario quer a barra na **direita do menu**, ou seja, depois do botao X, na
borda extrema.

Causa exata, uma linha, em `SkillsScreen.java` no layout (perto de `removeX`):

    int removeX = x0 + panelW - REMOVE_W;
    int listBarX = removeX - BAR_PAD - BAR_W;   // <-- ERRADO: fica antes do X

Correcao: `int listBarX = x0 + panelW - BAR_W - BAR_PAD;` (borda direita, apos o
X). Como a barra passa a ocupar o canto direito em vez do espaco entre o nome e
o X, **`nameW` nao precisa mais ceder espaco**: trocar
`barReserved` de volta para 0 para o nome voltar a ter a largura cheia.

Verificar tambem se as duas barras (lista e popup) ainda se cruzam; se passarem
a se cruzar de novo, manter a ordem de teste lista-antes-popup em `mouseClicked`.

### PENDENTE 2 — a mao do caido: MINHA HIPOTESE ESTAVA ERRADA

Sintoma (literal): "a mao continua com o mesmo problema, eu mexo o mouse para
direita ou esquerda e a mao do personagem nao acompanha a tela do player".

**Duas hipoteses minhas ja falharam, naoerija continuar especulando sobre
bytecode:**

1. Hipotese 1 (errada): setar `yBodyRot`/`yHeadRot` no tick. Causa: tick reescreve
   o valor e `Entity.turn` esta cancelado.
2. Hipotese 2 (plausivel mas **nao resolveu**): quantizacao de 20 Hz. Usei
   `CinematicCameraRig.getInterpolatedYaw(partialTick)`. O mixin aplica sem
   crash, mas o sintoma permanece.

### Abordagem recomendada: obter evidencia em runtime, nao mais raciocinio estatico

Instrumentar e ler os valores REAIS em jogo. Adicionar temporariamente um
overlay/`LOGGER` em `DownedBodyAlignMixin` que imprima, por frame (ou a cada
10 frames, para nao afogar o log):

- `SpectatorCameraController.isActive()`
- se `entity instanceof Player` passou
- `TabletopRpgClient.isDowned(player)`
- `CinematicCameraRig.isActive()`
- `partialTick` recebido
- `camYaw` calculado
- `bodyRot` ANTES da atribuicao (o valor do vanilla)
- `bodyRot` DEPOIS

Isso responde de uma vez as perguntas que estao em aberto:

- **Se o early-return dispara**, `bodyRot` nunca e escrito e a busca por
  `bodyRot` e(The) outra coisa esta sem motivo. Suspeita numero 1:
  `TabletopRpgClient.isDowned(player)` talvez seja verdadeiro so para o
  jogador local, e o usuario esta espectando OUTRO jogador — nesse caso o
  `instanceof Player player` nunca satisfaz a condicao de downed.
- **Se `bodyRot` antes e depois sao iguais**, outro codigo esta sobrescrevendo
  DEPOIS do nosso TAIL (contradizendo a leitura de bytecode do `AvatarRenderer`,
  que precisa ser reconferida em runtime, nao no `.class`).
- **Se `camYaw` esta parado enquanto a camera gira**, a origem do valor esta
  errada de novo, e `partialTick` do render de entidade pode ser DIFERENTE do
  da camera (essa igualdade era INFERENCIA, nunca verificada).

Instrumentar, testar UMA vez, ler o log. Nao mudar hypothesis sobre hypothesis.


## PENDENTE: duas tarefas de manutencao (ainda nao feitas)

- **Varredura de erros de digitacao e ideogramas** em todos os documentos e
  codigos do projeto (`.java`, `.json`, `.md`). O usuario pediu explicitamente.
  Nao ha ainda um script dedicado: hoje a varredura e um comando PowerShell
  ad-hoc em `Select-String`. Vale criar um script no repo.
- **Condensar `C:\Users\Pedro\.config\opencode\AGENTS.md`** (151 linhas) e
  adicionar a regra anti-ideograma. O usuario associateu os ideogramas a excesso
  de contexto.

## REGRA que quase custou um ciclo de teste

`gradlew build` **nao** valida injecao de mixin — so a carga real de classes
valida. Rodar `gradlew runClient` antes de entregar jar. O jogo chega ao menu
em ~25s em dev e o marcador de sucesso e `Sound engine started`; um
`InvalidInjectionException` aparece em `run/crash-reports` em segundos.
