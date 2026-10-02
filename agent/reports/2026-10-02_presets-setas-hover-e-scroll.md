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

## Validacao

| Verificacao | Resultado |
| --- | --- |
| `gradlew build` | BUILD SUCCESSFUL |
| `scanEncoding` | OK: 145 arquivos, 0 mojibake, 0 ideograma, 0 U+FFFD |
| Testes | **165 testes, 0 falhas, 0 erros** |
| Script de layout | 7 telas OK; so 240x180 reprova (preexistente) |
| Jar | `build/libs/tabletop-rpg-1.0.0.jar` 659KB, 01:58:35 |
| Jar contem a correcao | `javap` na classe `PresetsScreen` do jar: `showArrowsOnHoveredRow`, `addArrow`, `layoutStatus`, `statusLines`, `upArrows`, `downArrows`, `STATUS_LINES`, `ARROW_UP` |

O script de layout precisou ser atualizado junto (o `chrome` ganhou uma linha de
status). Sem isso ele imprimia os **numeros antigos com "OK"** -- falso negativo
silencioso.

## Nao validado

Nenhuma correcao visual desta rodada foi vista em jogo. `Screen` nao roda em JUnit e o
`runClient` do ambiente nao injeta clique nem hover de mouse. Os cinco pontos acima
dependem de pixel e de gesto, e so a jogadora fecha isso.

Em especial, **o sentido do hover nao tem teste**: a logica esta em
`showArrowsOnHoveredRow`, mas se a faixa de deteccao do mouse ficou pequena demais ou
grande demais para a percepcao dela, isso so aparece em jogo.

## Arquivos

- `src/client/java/com/pedro/tabletoprpg/client/PresetsScreen.java` -- todas as mudancas.
- `agent/memory/project-memory.md` -- 4 licoes novas.
- `C:\Users\Pedro\AppData\Local\Temp\opencode\check-presets-layout.ps1` -- atualizado
  (2 linhas de status). Fora do repositorio, entao nao versionado.
