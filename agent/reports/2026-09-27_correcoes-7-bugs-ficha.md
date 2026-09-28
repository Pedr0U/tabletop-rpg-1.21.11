# Correcao dos 7 bugs da ficha e do Sheet Editor (27/09/2026)

## Objetivo

O usuario deu `pull` no commit `7777eb7` ("Item de Personalizacao da Ficha", 2902 linhas, com
`SheetModel` novo), testou o mod e reportou 7 defeitos. Esta rodada corrigiu os 7.

## Escopo

7 bugs reportados em teste no jogo. Nenhuma feature nova. Arquivos de codigo alterados:
`SheetModel.java`, `SheetData.java`, `CharacterSheetScreen.java`, `StatusScreen.java`,
`SheetEditorScreen.java`, `MasterCommands.java`. Documento: `FUNCIONALIDADES-E-COMANDOS.md`.

## Alteracoes, por bug

### 1. Toggle ON/OFF so mudava uma vez

**Causa raiz:** `toggleField` em `SheetEditorScreen.java` recebia `boolean current` como
parametro do metodo. O lambda do `Button.builder` capturava o **valor** por copia, entao
`boolean next = !current;` era uma constante. Todo clique enviava o mesmo booleano.

**Correcao:** holder mutavel `boolean[] on = {current}`, o mesmo padrao que `xpModeField`
ja usava no mesmo arquivo. O contraste confirma a causa: `xpModeField` ciclava corretamente
porque usava `SheetModel.XpMode[] mode`.

### 2. Nome do campo de XP bugado

**Causa raiz:** o switch `SheetModel.labelOf` nao tinha caso para `"xptext"`, e o `default`
devolvia a chave crua. A ficha desenhava literalmente `xptext`. So aparecia em
`XpMode.TEXT`, porque NUMBER e HIDDEN usam `"xp"`, que tem caso.

**Correcao (3 defeitos no mesmo ponto):**
- `SheetModel.labelOf` ganhou `case "xptext" -> xpLabel;`. Escolheu-se o mesmo rotulo de
  `"xp"` porque o modelo tem **um** so campo de XP e `XpMode` e o modo de **exibicao** desse
  mesmo campo; um rotulo proprio exigiria schema/NBT novo.
- `"xptext"` foi incluido em `SheetData.TEXT_FIELDS`. Sem isso a caixa recebia
  `Integer.toString(getNumeric("xptext"))`, que caia no `default` e mostrava **0** em vez do
  texto. Verificados os 3 usos de `TEXT_FIELDS`: nenhum toca NBT, codec ou migracao, entao
  nenhum dado existente e afetado.
- `StatusScreen.fieldLabelWidth` passou a iterar `SheetData.LABELLED_FIELDS` (lista nova, mais
  completa) em vez de `TEXT_FIELDS`, e os literais fixos `"Level"`/`"XP"` sairam da lista, que
  agora vem do modelo por `labelOf`.

### 3. Edicao do nome da pericia voltava ao nome antigo

**Causa raiz:** `originalName` era capturado no lambda do `setResponder`. Na 1a tecla o modelo
passava a ter `"A"`; da 2a tecla em diante `periciaByName("Acrobatics")` devolvia `null`,
`withPericiaText` caia no early-return `def == null -> return this`, e o codigo entrava no
ramo de reversao devolvendo o nome antigo na caixa. Restava so a primeira letra no modelo.

**Correcao:** identidade por **posicao na lista** (`periciaAt(pos)`), em vez do nome congelado.
As linhas de atributo no mesmo arquivo ja usavam id e nunca tiveram o bug. Com isso o botao
de atributo e o `X` de remover tambem voltaram a operar na linha renomeada, porque dependiam do
mesmo `originalName`.

### 4. Botoes inativos piscavam ao interagir

**Causa raiz:** dois autores escreviam em `button.active` em momentos diferentes do frame.
`applyExtraState` botava `active = canEdit` **sem olhar limite**, e rodava fora do render, via
`onSheetState` (eco do servidor) e no fim de `init()`. O valor com limite so era escrito no
render, que roda **depois** de `super.render()`. Como o desenho usa o `active` do frame
anterior: eco reativa tudo (inclusive o `-` travado em 0), o frame seguinte desativa, o botao
fica branco 1 frame e cinza no seguinte. Qualquer campo editado dispara o eco, por isso
"ao interagir em qualquer lugar".

**Correcao:** helper unico
`applyStepButtons(Button minus, Button plus, int value, int min, int max)`, chamado nos tres
caminhos (`applyExtraState` para atributos e pericias, `renderContent` para atributos,
`drawPericias` para pericias). As setas limitadas sairam da lista `arrowButtons`, que ficou so
com o que nao tem limite (HP/Mana e o botao de atributo), onde `active = canEdit` e a unica
regra.

### 5. Numero negativo nao cabia; 3 caracteres somem

**Causa raiz:** `valueBoxWidth()` media `Integer.toString(teto)`, isto e `"30"` = 12px + 4 = 16px.
A fonte vanilla da 6px por char, entao `"-30"` = 18px **nao cabia**. O atributo tinha a guarda
`plainSubstrByWidth`, que cortava para **`-3`**; a pericia **nao tinha guarda nenhuma**, entao um
valor negativo invadiria 1px de cada lado, colidindo com `-` e `+`.

**Correcao:** `valueBoxWidth()` agora mede o pior caso real, `Attributes.VALUE_MIN` contra
`VALUE_MAX` e `Pericia.VALUE_MAX`. Resultado 22px em vez de 16px, o que comporta o sinal. As
guardas de truncamento foram **mantidas** no atributo e **adicionadas** na pericia, porque o
valor pode vir de payload forjado e o codigo nao pode depender do piso 0.

### 6. Botao de menos do atributo nunca desligava

**Causa raiz:** `renderContent` so ajustava o `+`, e `stepNumeric`/`saturatingAdd` na superclasse
nao tem clamp. Era possivel pedir -31 e o servidor cortava para -30, fazendo o numero piscar.

**Correcao:** o `-` do atributo desativa no piso -30, pelo mesmo `applyStepButtons` do bug 4.

**Decisao do usuario (27/09/2026):** o piso -30 vale para o **atributo**. A **pericia** fica em
0 a 30. Perguntado explicitamente e confirmado.

### 7. Botoes do rodape do editor saiam da tela

**Causa raiz:** largura fixa `bw = 100`, `gap = 6`, 4 botoes = **418px fixos**, sem usar
`colW()`. Abaixo de 418px de largura GUI o `x` fica negativo: 1600x900 com escala GUI 4
(width 400) da `x = -9`, cortando o botao Save; 1920x1080 escala 5 (width 384) da `x = -17`.

**Correcao:** `span = Math.min(colW(), this.width)`, `gap = max(2, span/100)`,
`bw = (span - 3*gap)/4`, `x = max(0, ...)`. Verificado: 400px da bw 91 e fim 386; 320px da bw 72
e fim 307; 480px+ da bw 112, identico ao visual antigo. `x >= 0` e `x + total <= width` em toda
largura.

### 8. Botao Salvar nunca desativava

**Causa raiz:** o retorno de `addRenderableWidget` era descartado, sem campo para `active`. A
comparacao ja existia (`isDirty()`, `!staged.equals(baseline)`) e era usada so no Descartar e no
aviso de "unsaved".

**Correcao:** campos `saveButton` e `discardButton`, atualizados no override de `render` da
proria classe, **antes** de `super.render()`, para o botao ja ser desenhado no estado certo no
mesmo frame em vez de atrasar um. `SheetEditorScreen` estende `Screen` diretamente (nao
`CharacterSheetScreen`), entao nao existe `renderContent` ali; `Screen.render` nao e final e a
classe ja o sobrescrevia. Guarda `!= null` porque `render` pode rodar antes do primeiro `init()`.

`resetButton` foi tratado depois, por achado da revisao independente: ele tinha condicao alem de
"suja" (`!staged.equals(SheetModel.defaults())`), que foi preservada como estava, soenetrando no
`render` para nao ficar defasado enquanto o jogador digita.

## Arquivos

- `src/main/java/com/pedro/tabletoprpg/SheetModel.java`: caso `xptext` em `labelOf`.
- `src/main/java/com/pedro/tabletoprpg/SheetData.java`: `TEXT_FIELDS` com `xptext`, nova
  `LABELLED_FIELDS`, Javadoc do piso do atributo corrigido.
- `src/main/java/com/pedro/tabletoprpg/MasterCommands.java`: Javadoc de `rollSkill` ("sem piso" ->
  "piso -30").
- `src/client/java/com/pedro/tabletoprpg/client/CharacterSheetScreen.java`: Javadoc de
  `saturatingAdd` corrigido.
- `src/client/java/com/pedro/tabletoprpg/client/StatusScreen.java`: `applyStepButtons`,
  `valueBoxWidth`, guardas de truncamento, `LABELLED_FIELDS`, 2 blocos de Javadoc.
- `src/client/java/com/pedro/tabletoprpg/client/SheetEditorScreen.java`: `toggleField`,
  `periciaAt`, `buildFooter`, `saveButton`/`discardButton`/`resetButton` no `render`.
- `FUNCIONALIDADES-E-COMANDOS.md`: ver secao propria abaixo.

## Validacoes executadas

- `.\gradlew.bat build --no-daemon --console=plain` -> **BUILD SUCCESSFUL**.
- `[scanEncoding] OK: 86 arquivo(s), 0 mojibake, 0 ideograma, 0 U+FFFD.`
- `SheetModelCodecTest`: 8 testes, 0 falhas, 0 erros, 0 ignorados.
- `check-catalogo.ps1` -> `OK: nenhuma divergencia mecanica entre codigo e catalogo`, exit 0.
- Revisao independente por subagente: **0 bloqueadores**, 4 achados importantes.

**O que NAO foi validado:** nenhum dos 7 bugs foi revalidado em jogo. Todos vieram de teste em
jogo e so build + analise estatica foram feitos. build verde nao substitui o clique real,
especialmente nos bugs 1, 4, 5 e 7, que sao de estado por frame e de medida de pixel em runtime.

## Problemas encontrados e causa raiz

1. **Captura por valor de parametro em lambda de widget** (bugs 1 e 3). `current` e
   `originalName` sao parametros de metodo, entao o lambda congela o valor. A assinatura de
   metodo parecia innocent: o bug nao era logica de dominio, era escopo de Java. Sintoma
   enganoso: o widget responde ao clique, entao parece funcionar.
2. **Dois autores no mesmo campo, em frames diferentes** (bug 4). `active` era escrito por
   `applyExtraState` (fora do render) e pelo render, que roda depois de `super.render()`. Todo
   eco de servidor recria a divergencia.
3. **Largura calculada a partir de um literal em vez do pior caso real** (bug 5). `valueBoxWidth`
   media `"30"` e esqueca o sinal, que e 1 caractere. Sem fontProvider em teste, isso so aparece
   em jogo.
4. **Valor de retorno de `addRenderableWidget` descartado** (bug 8). Sem campo, nao ha onde
   atualizar `active`.
5. **Divergencia entre `render` da classe e `renderContent` da superclasse.** A superclasse
   escreve `active` no `renderContent`, que roda depois de `super.render()`; a subclasse
   sobrescreve `render`. Os dois pontos de atualizacao producezem um frame de atraso diferente,
   e a ordem importa.

## Catalogo funcional

`FUNCIONALIDADES-E-COMANDOS.md` estava muito atrasado, e nao por causa desta rodada: o commit
`7777eb7` adicionou o item, o editor e o `SheetModel` **sem tocar no catalogo**. Busca confirmou
zero ocorrencias de `SheetModel`, `sheet_editor` ou `Sheet Editor` no documento.

Corrigido nesta rodada:
- Linha da `StatusScreen`: piso -30 no atributo, `applyStepButtons` no lugar de `renderContent`,
  caixa de 3 caracteres, "18 pericias fixas" -> do modelo.
- `AttributePickerScreen`: "6 atributos" -> atributos do modelo (1 a 10, com scroll). O enum de
  6 foi removido.
- Nova linha `SheetEditorScreen` na tabela de telas (divergencia do detector).
- Regra dos atributos: teto 30 e piso -30, os dois botoes desligam.
- Regra das pericias: "fixa em codigo, ideia de fase futura" -> configuravel pelo Mestre.
- Secao 6: o modelo passa a constar em "O que persiste" e saiu de "O que nao persiste".
- Falsidades adicionais achadas na leitura: `SheetData.PERICIAS_PADRAO` **nao existe mais** (4
  citacoes apontam para simbolo removido; o padrao das 18 vive em `SheetModel.defaults`),
  "os 20 pericias" na secao de persistencia, e residuos de citacao solta (`: buildFooterExtra`)
  que apontavam para o arquivo errado.

## Limitacoes e pendencias

1. **Perda de dado ao renomear pericia (achado I3, NAO resolvido).** O bug 3 foi corrigido no
   cliente, mas a causa de fundo continua: `SheetModel.align` identifica a pericia por **nome**
   (`SheetModel.java: align`, usado por `SheetData.aligned()` e por
   `PlayerSheetPersistenceMixin`). Ao salvar um modelo com nome novo, `periciaByNameOrLegacy` nao
   casa, `found == null`, e a pericia **perde o valor e volta a 0**, alem de perder o atributo
   escolhido pelo Mestre. Isso e pre-existente e esta travado num teste que passa
   (`SheetModelCodecTest.renamingPericiaIsALossyIdentityChange`), documentado como limitacao
   conhecida. Nao e regressao desta rodada, mas o diff deixa a renomeacao mais confiavel, o que
   aumenta a chance de o Mestre renomear e salvar. **Decisao pendente do usuario.**
2. **`PericiaDef` nao tem id.** O conserto do bug 3 usou **posicao na lista** como identidade,
   que sobrevive a rename, remocao e reordenacao (verificado). Id de verdade seria schema + codec
   + compatibilidade de save, ou seja, mudanca de alto impacto. Ver o item 1.
3. **Piso garantido so pela UI.** `stepNumeric`/`saturatingAdd` na superclasse seguem sem clamp.
   Hoje nenhum `addField` aponta para atributo, entao o bug esta fechado; outro caminho que
   escrevesse atributo reabriria o piscamento. O servidor ainda e quem corta.
4. **426x240 continua estourando** na ficha, por decisao anterior do usuario. Nao foi tocado.
5. Nenhum commit foi feito. Working tree com 6 arquivos `.java` e o catalogo modificados.

## Aprendizados duraveis

- `active` de widget no Minecraft deve ser decidido em **um** lugar, e esse lugar tem de rodar
  **antes** do `super.render()` que desenha. Duas rotas de atualizacao produzem um frame de
  divergencia visivel, e o eco do servidor torna isso facivel de ver.
- Parametro de metodo capturado em lambda e **congelado por valor**. Para estado mutavel de
  widget, usar holder (`boolean[]`, `X[]`) ou campo da classe.
- `StreamCodec.composite` aceita 12 campos. Correcao registrada contra Javadoc antigo que dizia 6.
- `UseItemCallback` nesta versao tem assinatura `(Player, Level, InteractionHand)`: **nao ha
  `ItemStack`**, e o stack vem de `player.getItemInHand(hand)`. Assinatura com 3 params e stack
  no lugar do `Level` produz "Level cannot be converted to InteractionHand".
- Largura de texto em pixel deve ser medida do pior caso real, com `font.width()`, e nunca de um
  literal ("30" esquece o sinal). Guardas de truncamento continuam obrigatorias porque o valor
  pode vir de payload forjado.
- Catalogo precisa ser atualizado no **mesmo** commit da feature. O `7777eb7` trouxe 2902 linhas
  de codigo novo e zero linhas de catalogo.

## Proximos passos

1. **Decidir o achado I3** (perda de valor ao renomear pericia): id estavel em `PericiaDef` ou
   registrar como limitacao conhecida.
2. Teste em jogo dos 8 comportamentos, com atencao a: ciclo do toggle duas vezes, renomear e
   depois usar botao de atributo e `X` na linha, piscar de botao, `-30` completo no atributo,
   rodape em 1600x900 escala 4, e Salvar/Descartar/Restaurar ligando e desligando ao digitar.
3. Commit so com pedido explicito do usuario.
