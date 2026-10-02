# Pausa: scroll da aba 3, cards de skill/magia iguais e botoes de mover

**Data:** 01/10/2026
**Estado:** PAUSADO a pedido do usuario, com codigo escrito, compilando e
**nao commitado** em `src/client/java/com/pedro/tabletoprpg/client/StatusScreen.java`.
**HEAD no momento da pausa:** `d031a92` (Preset: nome do item sem prefixo).
**Ultimo commit do CA:** `7868f1e`.

## Aviso principal para quem retomar

Existe alteracao **nao commitada** em `StatusScreen.java` (+360/-40). Nao
descartar e nao dar `git checkout` no arquivo sem ler este relatorio: o
trabalho nao esta em nenhum commit.

O `StatusScreen.java` ja estava muito ahead do `d031a92` por causa das
rodadas anteriores (pagina 3, Camera Tool, merge remoto), entao o diff
atual mistura essas rodadas com esta. **Esta e a razao de o diff nao poder
ser lido como se fosse so esta tarefa.**

## Bug 1: scroll da 3a pagina continua desenhado na 2a

**Relatado pelo usuario:** com muitas skills na 3a pagina a barra aparece; ao
voltar para a 2a, ela continua la, sobreposta nas coisas da 2a.

**Causa raiz (FATO verificado no codigo):** em `render()`, as chamadas
`skillColumn.renderBar(graphics)` e `spellColumn.renderBar(graphics)` NAO
tinham guarda de aba. A geometria da barra (`contentH`, `barH`, `barX`,
`thumbY`, `thumbH`) so e recalculada por `layout()`, que roda DENTRO de
`addSkillColumn` / `addSpellColumn` — ou seja, so quando a aba 2 e montada.
Ao sair da aba 2, `buildInfoTab()` nao toca nessas colunas: sobram
`contentH` e `barH` antigos, `maxScroll()` continua > 0, e o guarda interno
de `Column.renderBar` (`barH <= 0 || maxScroll() <= 0`) deixa passar.

Ou seja: o bug NAO era "faltou esconder a barra". A barra ja era desenhada
por codigo, nao era widget. Faltava a **condicao de aba** no unico lugar que
fala com as duas colunas.

A interacao ja era filtrada por aba (`columnUnderBar()` e o bloco
`activeTab == 2` do `mouseScrolled`), entao antes o clique era barrado e o
desenho nao — a barra "parecia viva sem fazer nada".

**Correcao:** as duas chamadas agora estao dentro de `if (activeTab == 2)`.

## Mudanca 1: card de altura FIXA e igual em skill e magia

Pedido: padronizar o tamanho; a skill se basear na magia, que tem 3 linhas;
o botao de nome da skill mais grosso, como o da magia, e o Del tambem.

Constantes novas (StatusScreen):
- `LIST_DEL_BTN_H = LIST_NAME_BTN_H` = 16
- `LIST_LINE1_ADV = LIST_CIRCLE_ADV` = 14
- `LIST_LINE2_ADV = LIST_DESC_ADV` = 11
- `LIST_LINE3_ADV = LIST_DEL_BTN_H` = 16
- `LIST_ENTRY_H = INV_PAD*2 + LIST_NAME_BTN_H + L1 + L2 + L3 + LIST_ROW_GAP` = **71**
- `LIST_MOVE_W = 12`, `LIST_MOVE_GAP = 2`

`skillEntryHeight` e `spellEntryHeight` agora devolvem `LIST_ENTRY_H` (71),
sem condicional. As 3 linhas sao contadas sempre, mesmo vazias.

`LIST_LINE3_ADV` e 16 e nao 11 de proposito: e a linha do Del, e o Del tem
16 px. Com 11 o Del invadiria a folga do card e a linha seguinte.

Modelo final, igual nos dois:
```
[ ▲ ][ ▼ ][  Nome, largura toda            ]
linha 1  <- skill: 1a da descricao | magia: circulo
linha 2  <- skill: 2a da descricao | magia: execucao
linha 3 .................... [ Del ]  <- skill: vazia | magia: custo
```

O Del da skill desceu para a 3a linha (antes ficava na linha do nome, via
`right = ""`). Com isso o nome da skill passa a ter a largura toda, igual ao
da magia. O Del da MAGIA continua na ultima linha **com texto** — nao
regressou, conforme verificado na linha 1528.

## Mudanca 2: botoes ▲ / ▼ a esquerda das skills

Novo `addSkillMoveButtons(x, y, skillName, index, total)`. ▲ so se `index > 0`,
▼ so se `index < total - 1` (nascem Buttons nao, para nao existir botao morto).
Respeita `clippedHeight`. Envia `RpgNetworking.SheetSkillPayload.move(targetName, skillName, ±1)` — **que ja existia**; nenhum servidor foi tocado. `delPending = -1` junto, porque mover troca o que esta no indice guardado. Guarda `canEdit` antes de enviar.

Faixa reservada a esquerda: 28 px (`LIST_MOVE_W*2 + LIST_MOVE_GAP*2`),
`tx = x + INV_PAD_X + 28`, `tw = rightTextW(w - 28)`.

Nao existe mover para MAGIA, e proposital: a ordem exibida das magias nao e a
ordem guardada (`SheetData.Spellbook#visible` ordena por circulo e depois por
nome), entao nao ha indice guardado para mover.

## Validacao feita

- `compileClientJava` **BUILD SUCCESSFUL** (13s).
- Aritmética de `LIST_ENTRY_H` conferida a mao: 10+16+14+11+16+4 = 71.
- Nenhum `y +=` com constante antiga sobrou no caminho dos cards: em
  `addSpellEntry` (1470+) os avances sao L1/L2/L3, e em `addSkillEntry`
  o `adv = i == 0 ? LIST_LINE1_ADV : LIST_LINE2_ADV` com a 3a linha fixa.
- Glifos ▲/▼ **estao no arquivo** (codepoints 9650 e 9660, UTF-8 sem BOM).
  Nao houve troca por ASCII.

## NAO validado

- **Nada foi validado em jogo.** O build verde nao cobre geometria de layout,
  recorte na rolagem nem glifos. E a rodada em que mais bugshistoricos deste
  projeto apareceram.
- **Os glifos ▲ e ▼ nao tem garantia na fonte padron do Minecraft.** Se
  aparecerem como caixa vazia, a troca e por texto ASCII. Decisao do
  implementador: manter as setas (o usuario pediu) e registrar o risco.
- `addSkillMoveButtons` nao tem teste; a grade nao cobre clique em widget.

## Perigo registrado sobre subagente

`SkillOp.MOVE` e `withSkillMoved` **ja existiam** no servidor desde antes.
Um subagente klasico teria criado payload novo e editado `RpgNetworking.java`
duplicando o caminho. A leitura do codigo ANTES de delegar foi o que evitou
isso. Vale o mesmo para `SheetData`, `SheetModel` e `SheetModelCodecTest`.

**Licao:** delegar mudanca de UI com constante nova exige, no prompt, as
constantes JA CALCULADAS e a lista do que **nao** pode ser tocado. Sem isso o
subagente recalcula e diverge.

**Outro ponto:** o validador (`tcc-validador`) recebeu um checklist de 10
itens e DEVOLVEU o checklist reescrito como "defeito", sem abrir o arquivo.
Afirmou, por exemplo, que `renderBar` estava fora da guarda de aba — e eu
tinha acabado de confirmar que estava dentro (linha 3155). **Relatorio de
validador que nao cita arquivo:linha e suspecto e precisa ser conferido antes
de virar "defeito" no relatorio.** Nao registrei os 10 itens dele como
defeitos reais.

## Proximos passos

1. Rodar `.\gradlew.bat build --no-daemon --console=plain`.
2. Subir o cliente e testar: 3a -> 2a -> 3a (bug do scroll), card de skill vs
   magia lado a lado, ▲/▼ numa lista com varias skills.
3. Confirmar se ▲/▼ renderizam ou viram caixa.
4. Commitar + tag so depois da confirmacao do usuario em jogo.

---

# CONTINUACAO — arrasto da barra e responsividade das magias

Rodada seguinte, ainda **nao commitada**. Build verde (`build`, 17s, 12 tasks).

## Bug 3 (novo): arrastar a barra nao desce

**Sintoma do usuario:** "as barras de scroll nao funcionam o segurar e arrastar ao
vez do scroll do mouse. Quando eu seguro e arrasto ele nao desce".

**Causa raiz (FATO verificado):** `Column` **nao e viewport**. A lista e feita de
widgets recriados a cada montagem, entao atribuir o campo `scroll` sem remontar nao
move nada na tela. `scrollFromMouse(double)` so faz a atribuicao e `mouseDragged`
nao chamava `rebuildWidgets()`. A roda ja fazia certo (`scrollColumn`: `flushCd()` +
`delPending = -1` + `rebuildWidgets()`) — e por isso que uma funcionava e a outra nao.

**Correcao:** `flushCd()` + `delPending = -1` + `rebuildWidgets()` nos dois blocos de
`mouseDragged`, e **tambem em `mouseClicked`**. O primeiro clique tambem precisa: sem ele
a barra saltava e a lista ficava 1 frame atras, que e exatamente como "arrastar nao
funciona" comeca. (Pendencia que o subagente aceitou em vez de resolver; corrigi aqui.)

`draggingBar` sobrevive ao `rebuildWidgets` porque e field da `Column` e `buildPanel`
so reseta `delPendingBox`, `contentH` e `scroll` — conferido.

## Bug 4 (novo): magias nao descem em tela pequena

**Sintoma do usuario:** "nao da pra descer porque a parte de cima do menu nao desce".

**Causa raiz (FATO verificado):** `addSpellColumn` gastava 6 faixas verticais antes da
lista. Em janela pequena `y` passa de `bottom`, `prepare` faz `listBottom == listTop`:
**altura zero**. Mas `contentH` continuava com a soma de todos os cards, entao
`maxScroll() > 0` e a barra aparecia e respondia — sem nenhuma lista atras.

**Isso tambem explica o outro sintoma da rodada:** "quando eu mexo no scroll, e como
se ele tivesse descido mas nao mostrou" nao era falha de redesenho; era a barra se
movendo sobre uma lista de altura zero.

**Decisao do usuario (perguntada):** os filtros e o "+ Magia" passam a fazer parte da
coluna rolavel. Encolher o cabecalho foi descartado. Titulo da secao continua FIXO — e a
identidade da coluna, pelo mesmo motivo do nome da aba na barra.

**Implementado nas DUAS colunas:**
- magias: `headerH = rowH * 5`, `contentH = headerH + spellsH`, `listTop` logo abaixo do
  titulo fixo, cabecalho montado por `addSpellHeader(x, w, rowY)` com
  `rowY = listTop - scroll`, e as magias a partir de `rowY + headerH`.
- skills: `headerH = rowH`, "+ Skill" rolavel na mesma posicao. O subagente deixou a
  coluna de skills de fora; apliquei a mesma solucao porque ela tem o mesmo mal, so com
  1 linha em vez de 5.

**Descoberta util:** `addSection` devolve `top + rowH`, entao `listTop = Math.max(y, top)`
**ja** fica abaixo do titulo fixo. Cheguei a achar que `listTop` estava errado; o
comentario do codigo mostrava que nao. **Nao "conserte" isso.**

**Recorte (o que mantem o cabecalho nao invadindo o titulo ao rolar):** cada widget do
cabecalho nasce por `clippedHeight(...)` + `Math.max(y, listTop)`, e cada rotulo
(`TextLine`) guardado por `lineFits`. O `headerH` tem de entrar no `contentH`: sem ele a
rolagem para uma faixa antes do fim e o "+ Magia" nunca aparece ao descer.

## Erro que eu cometi

Ao mexer na coluna de skills, escrevi `addSection("Skills", x, top, bottom)` — `bottom`
no lugar de `w`. O build pegou; corrigi para `w`.

## Validacao

- `.\gradlew.bat build --no-daemon --console=plain` **BUILD SUCCESSFUL** (17s).
- Cliente subiu duas vezes; `Player166` e `Player122` entraram no mundo. Nas DUAS vezes
  o jogo **encerrou sozinho** ~1 a 2 min depois: `Stopping!` +
  `Stopping singleplayer server as player logged out`, **sem Exception nem ERROR**.
  Encerra limpo, nao quebra. **Nao validado em jogo: nao vi a tela.**

## PENDENCIAS REAIS

1. **A barra cobre 4 px da borda direita dos botoes do cabecalho** (aceito pelo
   subagente, nao corrigido). Quer a coluna do cabecalho mais estreita que a da lista?
2. **▲/▼ sem garantia na fonte padron do Minecraft** — se aparecerem como caixa vazia,
   trocar por texto ASCII.
3. O cliente encerrar sozinho duas vezes: se repetir e nao for voce fechando a janela,
   investigar.

## Sobre o subagente desta rodada

Aceitou duas pendencias em vez de resolver, rodou `javac` sem classpath (os "100 erros"
sao irrelevantes) e conferiu chaves/parenteses em vez de compilar. **Chaves balanceadas
nao provam que compila — rode o build.** O que ele acertou (arrasto, cabecalho rolavel,
`contentH` com `headerH`) conferi linha a linha e esta certo.
