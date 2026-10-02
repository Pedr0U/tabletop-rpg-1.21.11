# Estado - Botoes da lista, selecao e nome com espaco (02/10/2026)

## Baseline
- branch `main`, arvore limpa na entrada.
- HEAD na entrada: `26bdfdf` (relatorio e licoes da fase formula/layout).
- HEAD na saida: `c01f33b`.
- `runClient.log` (~249 linhas) na raiz, ja no `.gitignore`. **Nao apagado** sem
  confirmacao.

## Objetivo
Quatro feedbacks da jogadora depois de testar o jar em jogo:
1. Botao de Del invisivel mas clicavel.
2. Botao do preset invisivel e a escrita "mais para a direita".
3. Selecao de preset nao sai sem fechar a tela.
4. `/rpg preset use Dano Espada` nao funciona; pedido de sugestao com `_`.

## Decisoes do usuario (02/10/2026)
1. Sugestao de comando mostra `_` no lugar do espaco; item e lista mantem o espaco.
2. Clicar na linha ja selecionada **desmarca**.
3. Botao `Use` manda o nome ja com `_`.

## Causas (todas com evidencia)
1. `PresetsScreen.render` desenhava o `fill` opaco do fundo da lista **depois** de
   `super.render`. Clique nao depende de pixel desenhado, dai "invisivel mas
   clicavel". O texto que a jogadora via mais a direita era a **formula** (alinhada
   pela direita), nao o nome deslocado.
2. O Brigadier **1.3.10** mudou `StringArgumentType.string()` de guloso para
   `QUOTABLE_PHRASE` (`readString()`): palavra sem aspas, ou frase entre aspas. O
   espaco sem aspas falha no parse antes do mod ver o nome. Confirmado no
   `brigadier-1.3.10-sources.jar` do cache do Gradle.
3. A sugestao `suggestOwnRollPresets` usava `preset.name()`, ou seja, entregava um
   comando que o Brigadier recusa. Este era metade do defeito, e nao um detalhe.

## Mudancas
1. `drawList` dividido em `drawListPanel` (fundo, antes dos widgets) e
   `drawListOverlay` (quadradinho, risco e formula, depois dos widgets).
2. `loadForEdit` desmarca quando a linha ja esta em edicao. Sem `rebuildListOnly`:
   nenhum widget depende de `editing`.
3. `RollPreset.commandName()` (espaco -> `_`, preserva caixa e acento), usado na
   sugestao e no botao `Use`. **Nao** devolve `key()`.
4. `rowTexts` reserva `ROW_CHIP_W` para o quadradinho da cor nao cobrir o nome.

## Efeito colateral assumido
O realce da linha em edicao foi para tras dos widgets (fundo da lista). Ele ainda
aparece nos vaos entre os botoes e na coluna da formula, mas nao basta. A linha em
edicao ganhou um risco na faixa `rowY + listRowHeight - 2`, que nenhum botao ocupa
(todos tem `ROW_H - 2` em linha de `ROW_H`). Sem mudanca de geometria.

## Validacao
- `.\gradlew.bat build` -> verde, `scanEncoding` incluso.
- **165 testes, 0 falhas** (`RollPresetTest` 27 -> 30). Os 3 novos:
  `commandNameReplacesSpaces`, `commandNameFindsTheStoredPreset` (create -> put ->
  find pela forma underscored, o caminho real do comando),
  `commandNameCollapsesWhitespace`.
- Script de layout: **numeros identicos** aos da fase anterior, porque nenhuma funcao
  de geometria foi tocada. Continua reprovando so 240x180.
- Jar: `build/libs/tabletop-rpg-1.0.0.jar` 658 KB 02/10 00:45. Caminho das classes
  conferido antes do marcador: `RollPreset.class` e `MasterCommands.class` com
  `commandName`, `PresetsScreen.class` com `drawListPanel`/`drawListOverlay`.

## Relatorio
`agent/reports/2026-10-02_presets-botoes-e-nome-com-espaco.md`
(atualizado tambem o `2026-10-02_formula-atributo-e-layout-presets.md`, cuja secao
de validacao continuava marcando como "nao feita" uma validacao de jogo ja feita).

## Pendente
- **A correcao visual nao tem prova automatica.** Nenhum log distingue "botao
  desenhado" de "botao coberto"; `Screen` nao roda em JUnit e `runClient` nao injeta
  clique. Confirma o pixels a jogadora, no Minecraft dela.
- `/rpg preset create "Dano Espada" 2d6 red` (frase entre aspas) **nao foi testado**.
  O usuario nao pediu. `formula` nao aceita espaco desde sempre e o `RollPreset` ja
  remove espacos antes de validar.
- `runClient.log` na raiz continua pendente de confirmacao para apagar.

## Commits
- `42a23c1` as quatro correcoes.
- `c01f33b` risco na linha em edicao.