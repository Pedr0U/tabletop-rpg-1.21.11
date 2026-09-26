# Implementation Report

## Status

PARCIAL. Build verde e scan de encoding limpo. Validacao visual e de runtime
pendentes, sempre do lado do usuario.

## Objective

Rodada pedida pelo usuario em 26/09/2026, com tres queixas e um pedido:

1. A barra de rolagem das skills aparecia "em cima" dos botoes de excluir.
2. Os botoes de excluir estavam sem o "X".
3. A descricao da skill so aceitava uma linha, e o campo devia ter cerca de
   3x a altura do campo de nome.
4. Depois: aumentar um pouco a altura do popup de descricao.

## Scope / Subtasks

- S1: espaco para a barra e botao de excluir redesenhado.
- S2: descricao multilinha de verdade.
- S3: popup mais alto.
- S4: build, scan e revisao do proprio diff.

## What Changed

- `REMOVE_W` (16) virou `REMOVE_SIZE` (20), e o recuo do botao passou a ser a
  constante nova `BAR_SLOT = 20`, aplicada so quando a lista transborda.
- O botao de excluir foi criado com mensagem VAZIA. O glifo passou a ser
  desenhado a mao por `renderRemoveIcon`, com `"X"` maiusculo na cor
  `COL_REMOVE_ICON` (0xFFFF7070), chamado no laco de `renderContent`, que roda
  depois de `super.render()`.
- `descInput` mudou de `EditBox` para o `MultiLineEditBox` vanilla, com altura
  `descH = 3 * nameH`, onde `nameH = rowH - 2`.
- `setMaxLength` virou `setCharacterLimit(SheetData.SKILL_DESC_MAX)`.
- `setEditable(canEdit)` virou `descInput.active = canEdit` (o
  `MultiLineEditBox` nao tem `setEditable`), em dois lugares.
- `POPUP_H` foi de 56 para 68.
- `mouseScrolled` passou a chamar `super.mouseScrolled(...)` antes de tratar
  popup e lista.

## Files Changed

- `src/client/java/com/pedro/tabletoprpg/client/SkillsScreen.java`
- `agent/reports/2026-09-26_skills-ui-multilinha.md` (este)

Nenhum outro arquivo de codigo foi tocado nesta rodada.

## Decisions

- DECISAO: usar o `MultiLineEditBox` vanilla em vez de escrever um widget de
  texto. Descoberto por `javap` que ele existe na 1.21.11 e ja faz exatamente o
  pedido. E subclasses nao seriam via: `textField` e `private final` e o
  construtor e package-private.
- DECISAO: o botao ficou com 20px de LARGURA e a altura da linha, e nao 20x20
  quadrado como o usuario descreveu. As linhas tem `rowH - 2`, ou seja 10 a 18px;
  um quadrado de 20px transbordaria para a linha vizinha. A largura e o que
  compete com a barra, entao foi a largura que recebeu os 20px.
- DECISAO: descricao 3x SEM aumentar a altura do painel. O usuario pediu as
  duas coisas, mas `skillRows` e `clamp(8, ...)` e a lista encolhe sozinha; o
  painel grew so custaria risco.
- DECISAO: `POPUP_H = 68` e nao 72. O usuario pediu "um pouco"; 68 da 5 linhas
  visiveis (56 dava 4) e 72 daria 6.
- DECISAO: `active = canEdit` no lugar de `setEditable`. Confirmado por
  bytecode que `isMouseOver` do `AbstractTextAreaWidget` exige `active`, e que
  `Screen.keyPressed` so despacha para o widget focado. Logo `active = false`
  impede clicar, focar e digitar.

## Validation

- `.\gradlew.bat build --no-daemon --console=plain`: `BUILD SUCCESSFUL in 12s`.
  `scanEncoding`: 59 arquivos, 0 mojibake, 0 ideograma, 0 U+FFFD.
- O compilador confirmou cada API usada, porque o build falha se a assinatura
  nao existir: `MultiLineEditBox.builder()`, `setX`, `setY`, `setPlaceholder`,
  `setTextColor`, `setTextShadow`, `setCursorColor`,
  `build(Font, int, int, Component)`, `setCharacterLimit(int)`, campo `active`.
- Geometria conferida a mao: botao em `[x0+panelW-40, x0+panelW-20]`, zona de
  clique da barra em `[x0+panelW-12, x0+panelW]`. 8px de respiro, sem
  intersecao.
- Linhas visiveis do popup: `(POPUP_H - 10) / 10`, entao 5 com 68.
- Enter quebra linha: FACT de bytecode. `MultilineTextField.keyPressed` tem
  cases 257 e 335 apontando para o mesmo offset, que chama
  `insertText("\n")` e devolve `true`.
- `getValue()` preserva `"\n"`: FACT de bytecode, o valor cru vai direto.
- NAO validado: nada disso foi visto em jogo. `runClient` nao foi executado.

## Problems Encountered

1. O codigo dizia que a barra NAO cobria o botao (3px de folga), mas o usuario
   viu "em cima". Nao havia evidencia para explicar. O screenshot (F2) foi
   pedido e nao chegou.
2. O "X" ausente nao tinha explicacao identificavel no codigo.
3. Regressao introduzida por mim: `mouseScrolled` da tela nunca chamava `super`
   e devolvia `true` sempre, entao o `MultiLineEditBox` nao recebia a roda.
   Com o campo de uma linha isso nao importava; com a descricao de 3x, um
   texto mais longo que o campo ficaria travado sem rolagem.
4. Escrevi "~344px" de folpa num comentario de codigo sem verificar. O numero
   depende de `this.height` e do GUI scale, entao nao e um fato. Substitui
   pelo mecanismo.

## Root Causes

1. FAT: a versao anterior reservava `BAR_W + BAR_PAD` (9px) enquanto a zona de
   clique da barra tem `BAR_PAD` dos DOIS lados, ou seja 12px. Os 3px da
   esquerda do botao ainda eram engolidos. Corrigido depois para 20px de
   reserva, por decisao do usuario.
2. NAO CONFIRMADO por que o glifo "x" nao aparecia. Em vez de investigar sem
   evidencia, a solucao removeu a dependencia do desenho padrão do `Button`:
   o glifo passou a ser desenhado pelo codigo da tela, que e verificavel por
   leitura. Se o problema era outro, o "X" agora aparece mesmo assim, e se
   NAO aparecer, a causa esta em `renderRemoveIcon` e nao no `Button`.
3. FAT: `SkillsScreen.mouseScrolled` consome a roda e nao repassa. O default
   de `ContainerEventHandler.mouseScrolled` roteia para o filho sob o cursor,
   mas um override que nao chama `super` impede isso.

## Fixes

- Reserva da barra passou a `BAR_SLOT = 20`, e o "X" passou a ser desenhado
  pela tela, com maiuscula e cor propria.
- `mouseScrolled` agora delega a `super` antes de tratar popup e lista. Os
  botoes devolvem `false`, e o popup nao e widget, entao o comportamento
  anterior da lista e do popup fica intacto.
- Comentario com numero nao verificado foi removido.

## Remaining Issues

- `runClient` nao rodado. E o risco maior desta rodada: a descricao multilinha
  nunca foi exercitada de ponta a ponta, nem a ida nem a volta pelo codec do
  servidor. O lado dos dados suporta `"\n"` (FACT: `cleanDescription` so faz
  `trim`, e o codec e `stringUtf8`), mas isso nao foi provado em execucao.
- A barra de rolagem interna do `MultiLineEditBox` e desenhada em
  `getRight()` (FACT de bytecode), ou seja 2px fora da largura do campo. Como o
  campo tem `panelW - 4`, deve ficar dentro da moldura do painel, mas isso
  ninguem viu.
- `AGENTS.md` global em 194 linhas, contra o maximo de 151 pedido. Pendente de
  uma rodada anterior, sem relacao com esta.
- Diagnostico do braco do caido continua esperando um trecho `[DownAlign]` do
  log gerado pelo usuario.
- Nada commitado. Arvore suja: 10 modificados, 1 nao rastreado preexistente
  (`agent/NEXT-SESSION-PROMPT.md`) e 2 relatorios.

## Lessons / Memory

- Antes de escrever um widget, procurar se o vanilla nao ja faz. `MultiLineEditBox`
  existe na 1.21.11 e resolve cursor, selecao, wrap, rolagem e barra interna.
  So nao tem `setEditable`, e nao tem `setCharacterLimit` com o mesmo nome.
- Um override de `mouseScrolled` que nao chama `super` tira a roda de todos os
  widgets filhos. Sintoma: campo de texto que aceita digitacao mas nao rola.
- `javap` no jar do loom e o oraculo para API. A 1.21.11 renomeou
  `CharEvent` para `CharacterEvent` e moveu `GuiEventListener` para o
  subpacote `.events`; adivinhar a assinatura teria custado um widget morto.
- `MultiLineEditBox` tem altura util de `9 * linhas` (o 9 e literal no
  bytecode, nao `font.lineHeight`), mais 8px de padding interno.

## Next Steps

1. Usuario roda `.\gradlew.bat runClient` e confirma: o "X" aparece e remove a
   skill com a lista transbordando; a lista ainda mostra as 8 skills; a
   descricao tem ~3x a altura do nome; Enter quebra linha; a roda rola o texto
   dentro do campo; o popup mostra 5 linhas.
2. Teste critico: digitar uma descricao de 3 linhas, dar Add, abrir a skill de
   outro jogador e ver se as 3 linhas voltaram. E o unico jeito de provar o
   ciclo do codec com `"\n"`.
3. Se a lista mostrar menos de 8, o lugar e `skillRows`/`POPUP_H`/`descH` em
   `buildPanel`, e a escolha volta a ser do usuario.
4. Condensar o `AGENTS.md` global para no maximo 151 linhas.
