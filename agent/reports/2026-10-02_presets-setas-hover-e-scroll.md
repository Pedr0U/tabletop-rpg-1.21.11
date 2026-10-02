# 02/10/2026 -- Setas so no hover, travadas em cinza, scroll invertido e aviso de duas linhas

## Objetivo

Cinco feedbacks da jogadora depois do teste em jogo do jar anterior. Os comandos e a
lista funcionam; sao ajustes visuais e de-scroll da tela de Presets:

1. Os botoes de subir/descer estão invadindo o lugar da formula. Deveriam ficar mais
   proximos do `Del`, **na mesma ordem de hoje**.
2. As setas devem aparecer **so quando o mouse esta em cima do preset**.
3. Quando nao ha mais para subir ou descer, a seta correspondente deve ficar **mais
   escura**, em vez de sumir.
4. A borda esquerda da formula esta invadindo o botao do nome (pouco, mas invade).
5. O scroll do mouse esta invertido. E: o aviso vermelho de baixo deve ser **branco**,
   e ele esta **saindo do quadro** dos presets.

Duas dessas perguntas eram decisao visual de gosto e foram perguntas antes de
implementar: a posicao das setas e o tratamento do texto de status. A resposta foi:
manter a ordem `[nome] ... [↑] [↓] [Del]` com as setas mais coladas no `Del`, e quebrar
o aviso em duas linhas.

## Mudancas (um arquivo)

Tudo em `src/client/java/com/pedro/tabletoprpg/client/PresetsScreen.java`.

### 1 e 3. Setas mais proximas do `Del`, sempre presentes, travadas em cinza

`arrowsRight()` usava `GAP` (6px) entre o grupo das setas e o `Del`. Passou a usar
`ROW_BTN_GAP` (2px), o mesmo vao que ja existe **entre as duas setas**. O grupo das
setas e o `Del` ficam colados, e a formula ganha 4px.

`rebuildListOnly` criava a seta de cima so quando `i > 0` e a de baixo so quando
`i < size - 1`; as ausentes simplesmente nao existiam. Agora as **duas** sao criadas em
toda linha por `addArrow(...)`:

- seta que tem para onde ir: texto `ChatFormatting.WHITE`, `active = true`;
- seta travada (primeira ou ultima linha): texto `ChatFormatting.DARK_GRAY`,
  `active = false`, e o tooltip diz `Already first` / `Already last`.

### 2. Setas so no hover

Novo `showArrowsOnHoveredRow(mouseX, mouseY)`, chamado em `render` **antes** de
`super.render` (e o `render` dos widgets que le `visible`). Acha a linha sob o mouse e
liga `visible` so nela.

**Ponto que mudou o desenho da correcao.** Verifiquei no bytecode do
`AbstractWidget.mouseClicked` (1.21.11): ele testa `isActive()` e `isMouseOver()` e
**nao** testa `isVisible()`. So esconder a seta deixaria um botao invisivel
reordenando a lista num clique cego. Por isso o `visible` vai junto do `active`.

E o `active` nao volta a `true` quando o mouse entra na linha: quem decide e
`index > 0` / `index < size - 1`, recalculado do indice. Sem isso a seta travada do topo
voltaria a funcionar com o mouse em cima dela.

### 4. Formula invadindo o botao do nome

Causa: `rowTexts` limitava a formula a uma **fracao** da largura toda da linha
(`available * 2 / 5`) enquanto o nome vivia dentro do botao, que ocupa `3/5` da mesma
faixa. Com a conta da fracao, no painel de 300px a formula comecava 3px **dentro** do
botao do nome.

Agora os dois orcamentos saem da geometria real:

- `formulaMax = formulaRight() - (rowLeft() + rowNameWidth() + GAP)` -- a formula nunca
  comeca antes da borda direita do botao mais um vao (79px onde antes eram 88);
- `nameMax = rowNameWidth() - ROW_CHIP_W - GAP` -- o nome e confinado ao **proprio**
  botao, menos a faixa do quadradinho da cor (119px onde a conta antiga dava ~120).

### 5a. Scroll invertido

Causa: `listScroll - (int) -Math.signum(scrollY)`, que vale `listScroll + signum(scrollY)`.
Com `scrollY` negativo ao rolar para baixo, isso **reduzia** o offset: a lista andava ao
contrario do gesto.

Correcao: `listScroll + (int) -Math.signum(scrollY)`.

Evidencia (duas, independentes):

- `MouseHandler.onScroll` no bytecode repassa o offset vertical do GLFW direto para
  `Screen.mouseScrolled`, e o GLFW e positivo ao rolar **para cima**.
- **As outras tres listas do projeto ja estavam certas**: `StatusScreen`,
  `AttributePickerScreen` e `SheetEditorScreen` usam todas `offset - signum(scrollY)`.
  A de Presets era a unica com o sinal trocado, o que explica o "ja tinha acontecido
  com as nossas listas" -- mas **nao ha como afirmar** que as outras queiram dizer a
  mesma coisa; elas nao foram tocadas porque o codigo esta certo.

### 5b. Aviso branco, em duas linhas, dentro do quadro

O aviso era uma linha so, `drawCenteredString`, sem limite de largura e em
`0xFFFF6060`. Qualquer mensagem um pouco longa vazia do painel.

Novo `layoutStatus()` quebra a mensagem em ate `STATUS_LINES` (2) linhas, cada uma
cabendo em `panelWidth - 2 * PAD`, usando `font.plainSubstrByWidth`. Se ainda sobrar
texto, a ultima linha recebe reticencias. Cor em `0xFFFFFFFF`.

**Por que quebrar e nao cortar:** com a tela aberta o aviso do servidor **nao** vai
tambem para o chat -- em `TabletopRpgClient` o receptor escolhe tela OU chat. Cortar
esconderia a recusa.

A quebra e montada em `layoutStatus()`, chamada do `layout()` e de `setStatus`/
`applyResult` (guardados por `panelWidth > 0`, porque o aviso pode chegar antes do
`init`), e nao no `render`: `plainSubstrByWidth` precisa da largura do painel.

## Custo aceito

Reservar a segunda linha tira `STATUS_H` (10px) da lista em janela baixa.

| Tela | Antes | Agora |
| --- | --- | --- |
| 427x240 | 4 linhas | **3 linhas** |
| 320x200 | 2 linhas | **1 linha** |
| 480x270, 640x360, 960x540, 640x480, 854x480, 320x240 | 6 linhas | 6 linhas |

427x240 e a resolucao do teste da jogadora. Nas telas normais nada muda. Em 240x180 o
painel estoura 10px, como ja estourava antes (limite conhecido, GUI scale >= 5).

Em troca: a opcao era afastar as setas para o outro lado do `Del` ou reduzir a fonte do
aviso; nenhuma das duas foi escolhida, e a alternativa de reservar a segunda linha **so
quando a lista ja tem folga** nao foi implementada por estar fora do escopo.

## Segunda volta: "as setas ainda estao em cima da formula"

A jogadora testou o jar e o conjunto passou, mas as setas continuam sobre a formula.
**Nao era gosto: e um bug de geometria, confirmado por conta.**

**Causa raiz.** `formulaRight()` era `arrowsRight() - GAP`. `arrowsRight()` e a borda
**DIREITA** do grupo das duas setas, e o botao da seta de baixo tem 20px de largura. A
formula terminava 6px antes dessa borda, ou seja **6px DENTRO do botao da seta de
baixo**. Como a formula e desenhada em `drawListOverlay`, depois de `super.render`, o que
aparecia na tela era a seta com o texto da formula atravessado por cima -- lido pelo
lado da jogadora como "a seta esta em cima da formula".

**Correcao.** Novo `arrowsLeft()`, e `formulaRight()` passa em `arrowsLeft() - GAP`.

O nome ficou mais apertado nessa rodada (120px -> 65px), e isso estava **errado**: foi
uma compensacao pela causa real, descoberta depois. Ver a terceira volta.

**Como foi provado, e nao afirmado.** O script de layout passou a medir a geometria da
linha da lista. E, para o check valer alguma coisa, ele foi **calibrado**: rodar o
script com a geometria antiga e ver reprovar. Resultado com a geometria antiga:

```
FALHA  427x240  ...
        linha: nome=120px formula=78px setas=252-294 Del=338
        -> LINHA: formula invade a seta: 288 > 246
```

Com a geometria nova, as mesmas telas passam. Um check que nunca falhou nao prova nada;
este falhou quando deveria.

## Terceira volta: as setas estavam a 44px do `Del`, e o nome pagou por isso

A jogadora testou de novo. A formula deixou de invade as setas (correto), mas: as setas
continuavam **longe** do `Del`, e o nome do preset ficou **pequeno demais**. Ela pediu
para desfazer a diminuicao do nome.

**Causa raiz, e eu tinha passado por ela duas vezes.** `arrowsRight()` era

```java
delX() - ROW_BTN_GAP - (ROW_BTN_W * 2 + ROW_BTN_GAP)
```

e `rebuildListOnly` calcula a borda ESQUERDA do grupo com

```java
int upX = arrowsRight() - (ROW_BTN_W * 2 + ROW_BTN_GAP);
```

A largura do grupo entrava **duas vezes em sequencia**. `arrowsRight()` ja entregava a
borda direita recuada 42px, e `upX` recuava mais 42px para achar a esquerda. Resultado:
44px de vazio entre a seta de baixo e o `Del`.

Por que isso nao apareceu antes: o codigo **parecia** correto. `arrowsRight()` e mesmo a
borda direita, e a expressao e a mesma que produz a esquerda. O erro so e visivel na
medida -- e o sintoma que a jogadora descreveu ("distantes demais do Del na direita do
quadro") era exatamente esse buraco.

**Por que eu nao vi em duas voltas:** nas duas eu mexi no *vao* entre setas e `Del`
(`GAP` -> `ROW_BTN_GAP`) em vez de conferir a posicao absoluta das duas pontas do grupo.
So um numero medido revela um erro de 42px; mexer em 6px perto dele nao pode.

**Correcao.** `arrowsRight()` = `delX() - ROW_BTN_GAP`. A largura do grupo entra uma vez
so, em `arrowsLeft()`.

O nome voltou sozinho: os 42px recuperados vao para a faixa de texto.

| Linha da lista, painel 300px | Antes | Agora |
| --- | --- | --- |
| vao seta -> `Del` | 44px | **2px** |
| nome | 65px | **103px** |
| formula | 62px | **79px** |

O nome nao volta aos 128px originais porque a formula agora ocupa a faixa dela sem
invadir a seta -- antes essa faixa era conquistada invadindo.

**A divisao.** O que sobra entre o botao do nome e o fim da formula e dividido em 3/5 e
2/5, como a primeira versao fazia. Nome = 3/5 porque e o alvo de clique da linha. Com a
fonte vanilla (6px por caractere, ja registrado na memoria do projeto):

| Texto | Largura | Cabe? |
| --- | --- | --- |
| `Dano Espada` (10 letras + espaco) | 64px | sim, em 103px |
| `2d6+Strength` (12 caracteres) | 72px | sim, em 79px |

**Como foi provado.** O script de layout ganhou duas checagens novas -- o vao
seta->`Del` tem de ser exatamente `ROW_BTN_GAP`, e o nome/formula tem de caber os textos
reais da jogadora. E foi **calibrado** de novo: com a conta antiga ele reprova com

```
-> LINHA: vao seta->Del fora do esperado: 44 (esperado 2)
-> LINHA: formula nao cabe '2d6+Strength' (72px): 62
```

Com a conta nova, as 9 telas passam, e `Dano Espada`/`2d6+Strength` cabem com folga em
todas elas.

## Validacao

| Verificacao | Resultado |
| --- | --- |
| `gradlew build` | BUILD SUCCESSFUL |
| `scanEncoding` | OK: 147 arquivos, 0 mojibake, 0 ideograma, 0 U+FFFD |
| Testes | **165 testes, 0 falhas, 0 erros** |
| Script de layout | 9 telas OK; so 240x180 reprova (preexistente) |
| Calibracao do check da linha | conta antiga reprova (vao 44px, formula 62px), nova passa |
| Jar | `build/libs/tabletop-rpg-1.0.0.jar` 659KB, 02:22:00 |
| Jar contem a correcao | `javap` em `PresetsScreen`: `arrowsRight`, `arrowsLeft`, `nameButtonX`, `rowNameWidth`, `formulaRight`, `formulaMax` |

O script de layout precisou ser atualizado duas vezes: quando o `chrome` ganhou a segunda
linha de status, e quando a geometria da linha da lista entrou na medicao. Sem isso ele
imprimia os **numeros antigos com "OK"** -- falso negativo silencioso. E um check novo
precisa ser **calibrado**: se nunca falhou, ainda nao prova nada.

## Nao validado

Nenhuma correcao visual desta rodada foi vista em jogo. `Screen` nao roda em JUnit e o
`runClient` do ambiente nao injeta clique nem hover de mouse. Os sete pontos acima
dependem de pixel e de gesto, e so a jogadora fecha isso.

Em especial, **o sentido do hover nao tem teste**: a logica esta em
`showArrowsOnHoveredRow`, mas se a faixa de deteccao do mouse ficou pequena demais ou
grande demais para a percepcao dela, isso so aparece em jogo.

## Arquivos

- `src/client/java/com/pedro/tabletoprpg/client/PresetsScreen.java` -- todas as mudancas.
- `agent/memory/project-memory.md` -- 5 licoes novas.
- `C:\Users\Pedro\AppData\Local\Temp\opencode\check-presets-layout.ps1` -- atualizado
  (2 linhas de status e medicao da linha da lista). Fora do repositorio, entao nao
  versionado.
