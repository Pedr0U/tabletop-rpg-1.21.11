# Relatorio 29/09/2026 - Bloco de vida/mana em 3 linhas e renome para Player Sheet

**Estado: implementado, revisado, com 1 BLOQUEADOR corrigido. Build verde, catalogo
atualizado, nada commitado e nada validado em jogo.** O ultimo checkpoint continua sendo o
C05 (`d29d5ee`): o usuario pediu que o commit so venha quando ele pedir. A rodada anterior
(5 itens de UI) continua sem commit tambem, entao o working tree tem as duas rodadas.

## Objetivo

Mudanca de disposicao do bloco de vida na ficha, mais um renome de rotulo no menu:

1. Titulo da vida (padrao `HP`, editavel no Sheet Editor).
2. Linha do teto e da barra, com a barra **maior** por causa do espaco.
3. Seis botoes de alteracao: `-10` `-5` `-1` `+1` `+5` `+10`. O mesmo para a Mana quando
   ligada.
4. O botao `Status` do menu principal passa a `Player Sheet`.

## Decisoes do usuario (tomadas antes de codar, com ele)

- **3 linhas por recurso**, com o teto **editavel** na linha do meio (a caixa numerica do
  teto e o unico caminho de muda-lo, entao ela fica).
- **Os 6 botoes nao repetem ao segurar.** A aceleracao foi oferecida e recusada: a decisao
  registrada de que vida e mana nao aceleram continua valendo, entao o catalogo nao mudou
  nessa parte.
- **Rolagem na coluna esquerda**, pelo mesmo padrao que a coluna de pericias ja usava.
- **Renome so no botao do menu.** O titulo desenhado no topo da ficha continua
  `Sheet: <jogador>`, e a classe continua `StatusScreen`.

## Implementado

### `StatusScreen.java`

- `addResourceRow` **saiu** (o metodo antigo, de uma linha por recurso) e foi trocado por
  `addResourceBlock` (linha do teto e da barra) + `addResourceStepRow` (linha dos 6 botoes).
  Os parametros de cor `fillColor`/`bgColor`, que **nunca foram usados** — as cores reais vem
  de `renderContent` — foram removidos junto, para nao sobrar parametro morto.
- O titulo do recurso vem do **modelo**: `model.hpLabel()` e `model.manaLabel()`, que ja
  existiam com default `HP`/`Mana` e ja eram editaveis no Sheet Editor (`field_hp`,
  `field_mana`). **Nenhum campo novo foi criado** em `SheetModel`, codec, NBT ou payload. O
  cabecalho `Vitals` **sumiu**: o titulo do recurso o substitui.
- Barra: altura de `rowH - 6` para `rowH - 2`, e largura ate o resto da coluna (era ~110px).
- Constantes novas: `RESOURCE_STEPS = {-10,-5,-1,1,5,10}`, `RESOURCE_STEP_GAP = 4`,
  `RESOURCE_BAR_H_MARGIN = 2`. Record `ResourceStep(field, delta, button)` + lista
  `resourceSteps`, e `canStepResource` + `applyResourceStepButtons` para o **cinza no
  limite**, reescrito no render como o `applyStepButtons` dos atributos.
- Rolagem da coluna esquerda: `leftScroll`, `maxLeftScroll`, `clampLeftScroll`,
  `isOverLeftPanel`, `isEditingField`, `drawY`, e um branch novo em `mouseScrolled` com a
  mesma convencao de sinal do `SkillsScreen` (`deltaY` negativo = rolar para baixo = avancar).
  O `leftScroll` e aplicado dentro de `buildPanel` e o rolamento chama `rebuildWidgets()`,
  igual ao que a coluna de pericias ja fazia.
- `numericFloor`/`numericCeiling` sobrescritos so para `hp` e `mana`.

### `CharacterSheetScreen.java`

- `stepNumeric` passou a clampar `next` ao piso/teto do proprio campo pelos ganchos
  `numericFloor`/`numericCeiling` (ilimitados por padrao) e a **nao gravar nem enviar**
  quando `next == base`.
- `ARROW_SIZE` (morta depois da mudanca) foi removida. A de `SkillsScreen` e propria e
  ficou como estava.

### `RpgMenuScreen.java`

- Literal do botao `Status` -> `Player Sheet`. O comentario da lista de botoes foi ajustado.

## Problema encontrado e corrigido (achado pela revisao independente)

**BLOQUEADOR: a conta de `neededRows` nunca somou as 3 linhas da Mana.** A base era
`3 + 5 + 3 + 2 + attrRowCount` e o bloco da Mana so aparecia em `optionalRows`, como
**subtracao** (`isEnabled("mana") ? 0 : 3`). Resultado: a conta ficava **3 linhas curta nos
dois casos** (com a Mana ligada, faltando somar; desligada, subtraindo o que nunca entrou).
Consequencia em 480x270: `rowH` no piso de 12, `maxLeftScroll` curto, os **ultimos
atributos fora do alcance da rolagem** e ainda desenhados fora do painel, disputando espaco
com o botao `Back`. **Build, 20 testes e `scanEncoding` aprovavam com esse defeito** — e o
`check-catalogo` tambem, porque so compara comandos, teclas e nomes de tela.

**Correcao:** base `3 + 5 + 3 + 3 + 2 + attrRowCount`, com a subtracao opcional mantida.
Com a conta certa, `maxLeftScroll = total - visiveis` faz a ultima linha cair dentro de
`leftBottom` e nao tocar o `Back`.

Nits corrigidos no mesmo passo: hit test da rolagem passou a usar `leftTop`/`leftBottom` (o
mesmo par do clamp) em vez de `contentTop`/`contentBottom`, para a faixa de respiro de 8px
nao aceitar roda sem conteudo; erro de portugues "o rajada" -> "a rajada"; e a remocao da
constante morta `ARROW_SIZE`.

## Correcao de bug que os botoes de -10 teriam tornado alcancavel

`CharacterSheetScreen.reconcilePendingNumeric` valida os pendentes de `pendingNumeric` contra
`sheet.attributeValueMin()/attributeValueMax()` — o intervalo do **atributo** — em vez do piso
do recurso. Com o `-10`, um clique bastava: Mana em 0 -> pendente `-10` -> o servidor corta
para 0 -> o eco traz 0 -> `-10 != 0` e `-10` esta em `[-30, 30]` -> o pendente sobrevivia e a
tela ficava presa mostrando `-10` com a barra vazia. Hoje isso exigia 7 cliques do `-` de 1
em 1. O clamp no `stepNumeric` resolve na origem: o passo que nao muda nada nao e gravado
nem enviado. `reconcilePendingNumeric` **nao** foi tocado.

## Validacoes executadas

- `.\gradlew.bat build --no-daemon --console=plain`: **BUILD SUCCESSFUL in 16s**;
  `scanEncoding` OK, **96 arquivos**, 0 mojibake, 0 ideograma, 0 U+FFFD; 19 testes do
  `SheetModelCodecTest` passando.
- Build completo com `--rerun-tasks` na revisao: aprovada, 20 testes, 0 falhas.
- `git diff --check`: limpo (so os avisos de LF/CRLF do Windows).
- `check-catalogo.ps1`: **OK, nenhuma divergencia mecanica**, antes e depois da edicao.
- Revisao independente: 1 bloqueador, 2 importantes, 5 nits. O bloqueador foi corrigido; um
  importante virou limitacao documentada (ver abaixo); os nits foram corrigidos.
- **Nada validado em jogo.** Nenhuma `runClient` nesta rodada.

## Limitacoes e riscos conhecidos

- **Nada foi visto em jogo.** A unica verificacao do comportamento de `rowH` no piso de 12, do
  alcance da rolagem e da legibilidade dos 6 botoes foi **aritmetica de constantes**, nao
  imagem. A correcao do bloqueador tambem e leitura de codigo.
- **A rolagem trava enquanto um campo de texto tem o foco** (`isEditingField`): rolar recria
  os widgets e mataria o que estivesse em digitacao. O `EditBox` do vanilla so perde o foco ao
  clicar em outro widget ou usar Tab, entao o jogador pode nao descobrir por que a roda parou.
  Decisao consciente, porque a alternativa e perder texto. Documentado em comentario.
- **Coluna muito estreita:** com `leftW` no minimo (120px) os 6 botoes ficam com ~16px e o
  ultimo com 20px, e o texto `-10` (~18px) pode encostar no vizinho. O rotulo aprovado pelo
  usuario foi mantido em vez de inventar um rotulo alternativo.
- **Risco teorico no atalho por id:** `numericFloor`/`numericCeiling` identificam o recurso
  pelo id, que funciona porque o editor gera ids `attr_N` e reserva `hp`/`mana`. Um modelo
  escrito a mao com um atributo de id `hp` receberia o clamp de vida.
- **Aceleracao em vida/mana segue desligada** por decisao do usuario nesta rodada; o catalogo
  foi atualizado para dizer que a decisao foi reconfirmada, e nao apenas herdada.

## Aprendizados

- **FATO:** um `neededRows` (ou qualquer contagem de linhas de layout) que so aparece em
  `optionalRows` como **subtracao**, e nunca na base, fica curto pelo valor subtraido — e o
  erro aparece **nos dois casos**, com a feature ligada e desligada. O sintoma foi conteudo
  desenhado fora do painel e widgets inalcancaveis, e **build, testes unitarios, scanEncoding e
  o detector de catalogo aprovavam**. So a revisao de codigo pegou.
- **FATO:** `reconcilePendingNumeric` valida pendentes contra o intervalo de **atributo**,
  que e o unico intervalo que existia quando foi escrito. Um clamp no **ponto de origem**
  (`stepNumeric`, antes de gravar e enviar) resolve a classe inteira do problema sem mexer na
  reconciliacao: passo que nao muda nada nao vira pendente. Ganchos `numericFloor`/
  `numericCeiling` com default ilimitado sao o jeito de nao endurecer o caso comum.
- **FATO:** o padrao de rolagem de `StatusScreen` (campo de offset + clamp + hit test +
  `rebuildWidgets()` no branch de `mouseScrolled`) ja existia para a coluna de pericias e foi
  replicado tal e qual para a coluna esquerda. Nao foi necessario inventar scroll novo, e nem
  barra visual: quem rola e so com o cursor em cima da coluna.
- **FATO:** `check-catalogo.ps1` so compara comando executavel, contagem de linhas da tabela de
  teclas e nome de tela. Ele **nao** pega referencia a simbolo morto nem texto descritivo
  desatualizado: as tres referencias a `addResourceRow` (metodo removido) so apareceram por
  leitura.
- **HIPOTESE:** `rowH` no piso de 12 continua legivel com tres linhas por recurso, e a barra
  com `rowH - 2` de altura comporta o texto de 8px sem sair das bordas. So o jogo confirma.

## Proximos passos

1. **Teste em jogo**, que e o portao que falta:
   - bloco de vida com 3 linhas: titulo `HP`, linha do teto e da barra, 6 botoes; e o mesmo
     para a Mana quando ligada, e o bloco inteiro sumindo quando a Mana e desligada;
   - os 6 botoes mudando de 1 em 1, 5 em 5 e 10 em 10, e **ficando cinza** no piso e no teto;
   - `-10` com Mana em 0 e `-10` com HP ja negativo, confirmando que **nao** fica preso
     mostrando valor invalido;
   - a coluna esquerda rolando com a roda, com o cursor nela, e alcancando o ultimo atributo;
   - o botao `Player Sheet` no menu, e a ficha abrindo por ele;
   - o titulo no topo da ficha continuando `Sheet: <jogador>`.
2. Se a coluna ficar ruim na resolucao do usuario, ajustar `rowH`, a altura da barra ou o
   numero de linhas antes de mexer em qualquer outra coisa.
3. Commit e tag so quando o usuario pedir; ai registrar o C06.

## Correcao depois do primeiro teste em jogo (29/09/2026)

**Sintoma relatado pelo usuario:** "na rolagem da coluna esquerda, o texto esta saindo pra
baixo, passando no botao de back". O resto da rodada foi dado como funcionando.

**Causa raiz:** `maxLeftScroll` limita o quanto da para rolar, mas **nao** esconde as linhas
que ficam **abaixo** da janela visivel. `StatusScreen.drawY` so mandava para fora da tela as
linhas acima do topo (`y + rowH <= contentTop`); as de baixo eram montadas no Y real, dentro
da faixa de respiro do painel e por cima do rodape. Com `leftScroll` em 0 isso ja acontecia,
e o texto aparecia sobre o `Back` mesmo sem rolar nada. Todos os rotulos e caixas saem do Y
que `drawY` devolve (`CharacterSheetScreen.addSection`/`addField` chamam `addWrappedLabel` e
`createFieldBox` com esse Y), entao o corte do `drawY` alcança o texto.

Ou seja: o bloqueador anterior (a conta curta de `neededRows`) e este bug sao o **mesmo
erro de novo**. A conta errada deixava `rowH` grande demais e o conteudo transbordando; a
conta certa deixou o transbordo correto, mas faltava o corte de baixo que esconderia o
transbordo. **Aritmetica de constantes nao substitui o corte no codigo.**

**Correcao:** `drawY` passou a cortar nas duas pontas:
`foraDoTopo = y + rowH <= contentTop` e `foraDoFundo = y + rowH > leftBottom`, mandando a
linha para `this.height + 64` quando qualquer um das duas e verdade. O corte de baixo usa
`leftBottom`, e nao `contentBottom`, porque `leftBottom` e o par que `maxLeftScroll` usa
para contar as linhas visiveis (`visible = (leftBottom - leftTop) / rowH`): com o mesmo
criterio nas duas contas, `maxLeftScroll = leftTotalRows - visiveis` garante que a ultima
linha cabe exata no fim da rolagem, e uma linha cortada pela borda e omitida em vez de
sangrar. O corte de cima continua em `contentTop`, de proposito, para nao cobrir o cabecalho.

**Validacao:** `.\gradlew.bat build --no-daemon --console=plain` -> **BUILD SUCCESSFUL in
26s**; `scanEncoding` OK, 97 arquivos, 0 mojibake, 0 ideograma, 0 U+FFFD. Nenhum teste
automatico cobre layout de tela, e **a correcao nao foi vista em jogo** -- e uma linha de
2 linhas de altura, entao a omissao de uma unica linha de conteudo ja produzia o report.

**Pendente de teste em jogo:** rolar a coluna esquerda ate o fim e confirmar que (1) nenhum
texto, barra ou botao aparece sobre o `Back` nem na faixa de respiro do painel, (2) o ultimo
atributo continua alcancavel, e (3) o cabecalho `Sheet: ...` continua visivel com a rolagem
no fim.

## Colunas alinhadas (29/09/2026, escolha do usuario)

**Sintoma:** "o posicionamento das colunas ta meio estranho [...] a coluna esquerda e colocada
mais pra cima em questao de distancia do topo/rodape do que a coluna da direita, ai ta meio
desigual". O usuario ja tinha achado o corte de baixo do `drawY` correto ("deu certo") e
apontou so a falta de simetria entre as colunas.

**As duas colunas nao tinham nem o topo nem o rodape em comum:**

1. **Topo:** a esquerda comeca em `topY`, e a primeira linha dela e o titulo `Identity`, com
   `rowH` de altura. A direita comeca em `topY + perTitleH`, e `perTitleH` era uma
   **estimativa independente** — `rowHOrDefault` dividia a area por **12 linhas fixas**, sem
   relacao com as ~19 linhas reais que `fitRowHeight` usa. As duas so coincidiam por acaso,
   quando ambas batiam no `MAX_ROW_H` (20). Em janela baixa as divergeiam: com 200px de area
   util, `perTitleH` dava 16 e `rowH` dava 12, e a coluna direita entrava 4px mais embaixo;
   o pior caso da faixa e 8px. **E por isso que o usuario via o problema so em janela baixa.**
2. **Rodape:** a coluna de pericias **estica** as linhas para preencher a altura toda
   (`perRowH = perAvail / perCount`) e termina colada no rodape; a esquerda terminava numa
   fronteira de `rowH` e deixava de 0 ate `rowH - 1` px (ate 11px) de vazio embaixo.

**Correcao** (tudo em `buildPanel`, mais dois helpers):

- O bloco de geometria das pericias foi **movido para depois** do `rowH`, e `perTitleH` passou
  a ser **exatamente `rowH`**. Com isso as duas colunas comecam no mesmo Y, por construcao e
  nao por coincidencia.
- A esquerda passou a fazer a mesma conta de preenchimento que a direita: divide a area util
  pelo numero de linhas que cabem nela (`janelaLeft / linhasVisiveis`) e usa o resultado como
  `rowH` quando ele fica dentro de `[MIN_ROW_H, MAX_ROW_H]`. O `rowH` final pode subir 1 ou
  2px, e o clamp da rolagem foi movido para **depois** desse ajuste para consumir o `rowH`
  novo. Nao ha risco de a ficha passar a rolar por causa disso: quando o conteudo cabe,
  `linhasVisiveis >= leftTotalRows` e o `rowHJusto` fica **menor ou igual** ao `rowH` de antes,
  entao o que cabia continua cabendo.

**Bug latente corrigido no mesmo caminho:** `perVisibleCount()` usava `contentTop`/
`contentBottom` (limites **externos**) enquanto o `buildPanel` desenha dentro dos limites
**internos** (`leftTop`/`leftBottom`, PANEL_PAD a menos). Sao 8px a mais de area, o que podia
estimar uma pericia visivel a mais do que a desenhada e deixar `maxPerScroll` curto demais
para alcancar a ultima pericia. **E o mesmo tipo de bug do `neededRows` curto**, e igualmente
invisivel para build, testes e detector. Agora a conta espelha a do `buildPanel`. O
`perVisibleCountFor` (usado so pelo teto de `perRowH`) tambem passou a usar `rowH`, para o
teto nao divergir do que e desenhado.

**Codigo morto removido:** `rowHOrDefault` ficou sem nenhum chamador depois da mudanca — foi
removido, pelo mesmo motivo que a `ARROW_SIZE` morta foi removida antes. Referencia a simbolo
morto no catalogo e o tipo de coisa que o `check-catalogo` **nao** pega.

**Validacao:** `.\gradlew.bat build --no-daemon --console=plain` -> **BUILD SUCCESSFUL in
18s**; `scanEncoding` OK, 97 arquivos, 0 mojibake, 0 ideograma, 0 U+FFFD. **Nada validado em
jogo.** A igualdade do topo e **por construcao** (mesma variavel), mas a igualdade do rodape e
so **aproximada**: as duas colunas tem alturas de linha diferentes por decisao de projeto (a
direita comprime para caber ate 30 pericias), entao sobra um resto de menos de uma linha em
cada uma, e os dois restos podem diferir em poucos pixels. Exatamente igual no rodape exigiria
forcar a altura da coluna de pericias, o que mudaria quantas pericias cabem.

**Pendente de teste em jogo:** as duas colunas devem comecar na mesma altura e terminar
praticamente na mesma linha de base, em janela normal **e** em janela baixa; as caixas, barras
e os 6 botoes da esquerda podem ter ficado 1-2px mais altos; e a ultima pericia da direita
deve continuar alcancavel com a rolagem.
**Confirmado pelo usuario:** "a questao da diferenca de altura esta normal agora".

## O "pisca" do botao de atributo (29/09/2026)

**Sintoma:** "quando uma coluna se mexe, o botao de atributos da coluna da direita pisca. Eu
entenderia se fosse so por mover a coluna da direita, mas mover a coluna da esquerda tambem ta
dando isso." (A coluna de pericias e a que tem, em cada linha, o botao que abre o seletor de
atributo — "FOR", "DES" etc.)

**Causa raiz — ordem de desenho dentro do `render`:** o `CharacterSheetScreen.render` (linha
917) desenha, **nesta ordem**: o titulo, depois `super.render()` (linha 930, que e quem itera e
desenha os widgets) e so entao `renderContent` (linha 954). A sigla do atributo era escrita em
`drawPericias`, que vive dentro de `renderContent` — portanto **um frame depois** de o botao
ja ter sido desenhado. E o botao nascia com `Component.literal("")`. Resultado: a cada
frame em que os widgets eram recriados, o botao era pintado **vazio** e so no frame seguinte
recebia a sigla. Um frame em branco, repetido a cada tique da roda: o pisca.

**Por que as DUAS colunas disparavam:** `mouseScrolled` chama `rebuildWidgets()` nos dois
ramais, e `rebuildWidgets()` (do `Screen`) limpa a lista de filhos e refaz o `init()` inteiro
— entao rolar a coluna esquerda tambem recriava os widgets da coluna de pericias. O usuario
 esperava que so a coluna que se mexe sofresse, e a expectativa estava certa: o flash nao
tinha a ver com a coluna, tinha a ver com a reconstrucao.

**Correcao:** a sigla passa a ser resolvida **na criacao do botao**, por um metodo novo
`attrLabelFor(int index)`, e o `drawPericias` passou a usar o mesmo metodo (fonte unica). O
render continua reescrevendo o rotulo — para o botao acompanhar a ficha — mas ja nao e o
responsavel por deixar um frame em branco. Sem `sheet` ou com indice fora da faixa, o metodo
devolve vazio, que e o que o botao exibia ate o primeiro render.

O `active` dos botoes ja era escrito antes do primeiro desenho, porque `applyExtraState()` roda
no fim do `init()` (via `applySheetToWidgets`, linha 318 do `CharacterSheetScreen`) — por isso
so a **sigla** piscava, e nao o botao inteiro.

**O que NAO foi feito, e por que:** a correcao estrutural seria nao reconstruir a tela toda ao
rolar a coluna esquerda (o `rebuildWidgets()` e do `Screen` e o `init()` monta as duas colunas
juntas, entao isolar a esquerda exigiria reestruturar o layout compartilhado com o
`SkillsScreen`). Isso e maior do que o sintoma pedido e traria outros beneficios — o texto em
digitacao e a rajada de `HoldStepButton` sobrevivem a rolagem — mas e mudanca de
arquitetura de tela, entao ficou para decisao do usuario. A correcao feita remove o flash sem
mexer nisso.

**Validacao:** `.\gradlew.bat build --no-daemon --console=plain` -> **BUILD SUCCESSFUL in
21s**; `scanEncoding` OK, 97 arquivos. **Nada validado em jogo** — o pisca e visual, e a
ordem de desenho foi confirmada por leitura do `render` e do `addPericiaRow`, nao por execucao.

**Pendente de teste em jogo:** rolar as DUAS colunas com o cursor sobre elas e confirmar que a
sigla do botao de atributo nao pisca mais em nenhuma das duas, e que a sigla continua correta
(é a do atributo escolhido para aquela perícia, pelo nome que o Mestre deu no Sheet Editor).

## Validação em jogo (29/09/2026) e checkpoint

O usuario testou em jogo, nesta ordem, e as tres correcoes passaram:

1. **Corte de baixo do `drawY`**: "deu certo, mas so uma coisa" (o texto nao passa mais pelo
   `Back`).
2. **Alinhamento das colunas**: "a questao da diferenca de altura esta normal agora".
3. **Pisca do botao de atributo**: "perfeito".

Com isso **nada desta rodada fica sem validacao em jogo**: o bloco de vida/mana em 3 linhas, os
6 botoes de passo, o `Player Sheet` do menu, o corte de transbordo, o alinhamento das colunas e
o fim do pisca foram todos conferidos por ele no cliente. As **hipoteses** que restaram em
memoria (legibilidade de `rowH` no piso de 12 e a barra com `rowH - 2`) tambem foram
confirmadas na pratica.

Estado no momento do checkpoint: build verde, `scanEncoding` OK em 97 arquivos,
`check-catalogo` sem divergencia, e a arvore de trabalho com o que sobrou das duas rodadas
(a de 5 itens de 28/09 e esta). **Sem push** — o repositorio remoto nao foi tocado.

**Limites do que a validacao em jogo cobre:** cobre o que o usuario exercitou com o modelo e a
resolucao dele (bloco de vida com Mana ligada, 6 botoes, rolagem das duas colunas, menu). Nao
cobre, por nao terem sido exercitados: Mana **desligada** pelo Mestre, modelo com 30 pericias
(uma coluna tao cheia quanto a compressao da coluna da direita permite), janela muito baixa
alem da que ele usou, e a persistencia de vida/mana depois de fechar e reabrir o jogo.
