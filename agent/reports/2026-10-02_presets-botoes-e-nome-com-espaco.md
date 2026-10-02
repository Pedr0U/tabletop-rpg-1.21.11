# 02/10/2026 -- Botoes invisiveis, selecao que nao saia e nome com espaco

## Objetivo

Quatro feedbacks da jogadora sobre a tela de Presets, todos reports depois de testar
o jar em jogo:

1. O botao de Del nao aparece, mas da para clicar onde ele fica.
2. O botao do preset criado nao aparece e a escrita dele esta mais para a direita do
   lugar.
3. Nao da para tirar a selecao de um preset: e preciso sair da tela e voltar.
4. `/rpg preset use Dano Espada` nao funciona. O pedido foi: a **sugestao** de
   `/rpg preset use|delete|...` deve listar os presets com `_` no lugar do espaco,
   o item e a lista continuam com o espaco que a pessoa digitou, e o botao `Use`
   ja manda com `_`.

## O que mudou

### 1. Botoes da lista invisiveis mas clicaveis (BUG, causa confirmada)

**Causa raiz.** Ordem de desenho, em `PresetsScreen.render`: o `fill` opaco do fundo
da lista (`0xFF161616`) era desenhado **depois** de `super.render`, que e quem
desenha os widgets. O `fill` tapava o botao inteiro.

**Por que o clique continuava funcionando.** `Screen.mouseClicked` acha o widget pelo
retangulo, nao pelo pixel desenhado. O botao estava la, coberto.

**Por que a escrita parecia estar "mais para a direita".** O unico texto que sobrevive
a ordem antiga era a **formula**, desenhada por `drawList` **depois** do `fill` e
alinhada pela direita (`formulaRight()`). A jogadora via a formula no lugar onde
esperava o nome. Nao era um deslocamento: era outro texto, no lugar certo para ele.

**Correcao.** `drawList` virou dois metodos, e a ordem do `render` Segue quem precisa
ficar na frente:

| Metodo | Quando | O que desenha |
| --- | --- | --- |
| `drawListPanel` | antes de `super.render` | fundo da lista, realce da linha em edicao |
| `drawListOverlay` | depois de `super.render` | quadradinho da cor, risco da linha em edicao, formula, "No presets yet" |

O quadradinho da cor e a formula **precisam** ir depois: o botao do nome e opaco e
nao desenha a formula, entao qualquer um dos dois nao apareceria se viesse antes.

**Efeito colateral assumido:** com o realce da linha em edicao atras dos widgets, ele
so aparece nos vaos entre os botoes e na coluna da formula. Para nao perder a
pista de qual preset o `Save` vai sobrescrever, a linha em edicao ganhou um **risco
por baixo**, desenhado no overlay na faixa `rowY + listRowHeight - 2`. Nenhum botao
da linha ocupa essa faixa: todos tem `ROW_H - 2` de altura dentro de uma linha de
`ROW_H`. Sem mudanca de geometria.

### 2. Selecao que nao saia

`loadForEdit` sempre sobrescrevia `editing`. Agora, clicar na linha que **ja** esta em
edicao limpa `editing`, os dois campos e o `deletePending`. Nao apaga nada: e o mesmo
dois-cliques do `Del`, sem a confirmacao.

`rebuildListOnly()` **nao** foi chamado nesse caminho. Nenhum widget depende de
`editing` (nenhum `.active =` na tela, e o texto do botao da linha so depende do
preset), entao recriar os widgets durante o clique seria trabalho sem efeito.

### 3. Nome com espaco no comando (causa fora do mod, confirmada na fonte)

**Causa raiz.** `MasterCommands` importa `com.mojang.brigadier.arguments.StringArgumentType`
(Brigadier 1.3.10). Nele, `string()` **nao e mais guloso**: devolve
`StringType.QUOTABLE_PHRASE`, e `parse` chama `reader.readString()` -- uma palavra sem
aspas ou uma frase **entre aspas**. Espaco sem aspas falha no parse do Brigadier,
antes de qualquer codigo deste mod ver o nome.

Confirmacao: `StringArgumentType.java` do `brigadier-1.3.10-sources.jar` no cache do
Gradle. Antes disso o build ficou verde a ponto inteiro do problema, e o mesmo sintoma
explicaria `dano_espada` funcionando: sem espaco, `readString()` le a palavra toda.

**Por que a sugesto era o metade do defeito.** `suggestOwnRollPresets` sugeria
`preset.name()`, ou seja, "Dano Espada". Aceitar a sugestao montava um comando que o
Brigadier recusa. O provedor agora sugere `preset.commandName()` e filtra por ela,
o que cobre `use`, `delete`, `give` e `edit` de uma vez (os quatro ja usavam esse
mesmo provedor).

**Correcao.** `RollPreset.commandName()` troca espaco por `_` e **preserva caixa e
acento**. Nao pode devolver `key()`: a chave e minuscula e sem acento porque serve
para comparar, e o texto volta para o chat da jogadora.

O item e a lista **nao** foram tocados: continuam com o espaco que a pessoa digitou.
`commandName` tem um unico outro uso, `PresetsScreen.usePreset`, que monta
`"rpg preset use " + editing.commandName()`.

`normalizeKey` ja trocava espaco por `_`, entao `Dano_Espada` acha o mesmo preset sem
nenhuma mudanca no servidor.

### 4. Nome passando por baixo do quadradinho da cor

O quadradinho e desenhado em cima do botao do nome (opaco) e o nome dentro dele e
centralizado. `rowTexts` agora reserva `ROW_CHIP_W` para essa faixa, entao nome e
quadradinho nunca se sobrepoem. Nenhuma posicao de widget mudou.

## Validacao

**Codigo:**

- `gradlew build` verde, incluindo `scanEncoding`.
- **165 testes, 0 falhas** (`RollPresetTest` subiu de 27 para 30):
  - `commandNameReplacesSpaces` -- `Dano Espada` vira `Dano_Espada`, caixa mantida.
  - `commandNameFindsTheStoredPreset` -- `create` -> `put` -> `find(commandName())`
    encontra, e a chave da forma underscored e igual a do nome com espaco. Este e o
    caminho real do comando.
  - `commandNameCollapsesWhitespace` -- espaco sobrando/duplo nao vira dois `_`.
- Script de layout rodado de novo: **numeros identicos** aos da fase anterior, porque
  nenhuma funcao de geometria (`rowLeft`, `delX`, `arrowsRight`, `rowNameWidth`,
  `formulaRight`) foi tocada. Continua reprovando so 240x180.
- Artefato: `build/libs/tabletop-rpg-1.0.0.jar` (658 KB, 02/10 00:45). Conferido o
  caminho das classes **antes** de procurar o marcador:
  `RollPreset.class` e `MasterCommands.class` com `commandName`,
  `PresetsScreen.class` com `drawListPanel` e `drawListOverlay`.

**Nao feita, e nao da para fazer sem olho humano:**

O defeito principal desta fase e **visual**: nenhum log distingue "botao desenhado" de
"botao coberto por um `fill` depois". O `Screen` nao roda em JUnit, e o `runClient` nao
injeta clique. A correcao esta no codigo e o build esta verde, mas **a confirmacao de
que os botoes aparecem e da jogadora**, no Minecraft dela.

## Limite conhecido

- Em 240x180 de GUI o painel estoura 10px embaixo (GUI scale 5 ou mais). Nao foi
  mexido nisso nesta fase.
- `/rpg preset create` continua com `StringArgumentType.string()` para `name` e
  `formula`. `formula` nao aceita espaco desde sempre, e o `RollPreset` ja remove os
  espacos antes de validar. `name` aceita uma palavra **ou** uma frase entre aspas.
  **Nao verificado** se `/rpg preset create "Dano Espada" 2d6 red` funciona de fato;
  o usuario nao pediu e o item nao depende disso.

## Decisoes do usuario (02/10/2026)

- Sugestao de comando com `_`; item e lista com o espaco.
- Clicar na linha ja selecionada **desmarca**.
- Formato dos campos empilhado (Name em cima, Formula embaixo), mantido da fase
  anterior.

## Estado final

Branch `main`, arvore limpa, `c01f33b`. Nada pushado para `origin/main`.
Commits desta fase: `42a23c1` (as quatro correcoes) e `c01f33b` (risco da linha).

## Notas para o proximo desenvolvedor

- **Brigadier 1.3.10 mudou `StringArgumentType.string()`.** Qualquer comando novo deste
  mod que aceite nome digitado pela jogadora vai falhar com espaco sem aspas. A forma
  segura e `RollPreset.commandName()` na sugestao e em tudo que manda `sendCommand`.
- **Em `Screen`, `fill` opaco depois de `super.render` esconde widget sem tirar o
  clique.** Se a tela ficar estranha e algo "funcionar so no clique", olhe a ordem de
  desenho primeiro.
- **Nao da para validar "algo aparece" com log.** Vale a pena continuar com o log de
  abertura/layout, porque ele serviu para provar o layout medido, mas ele nao diz nada
  sobre pixel.