# Implementation Report

## Status
FEITO (build verde, sem commit): os 3 feedbacks da jogadora de 07/10/2026 + o
batch 2 (correcao: coluna de copias mais larga, menu inalterado) foram
implementados; a coluna de copias dentro do editor foi construida conforme ela
escolheu. Falta: subtask do highlight (segurando o item), uma partida real de
validacao e a autorizacao para commitar.

## Objective
Fechar o que a jogadora reportou/testou em 07/10/2026 sobre o Bug C (fichas de
ameaca e copias):
1. `/rpg mob give <nome>` deve funcionar a qualquer hora, vinculado ou nao.
2. Roubar uma ficha vinculada deve tirar o nome de exibicao flutuante do mob
   antigo (dois mobs mostravam o mesmo nome).
3. A lista de copias nao existia no build testado; ela confirmou o local: coluna
   fina na borda direita DO EDITOR, visivel nas duas abas, com o botao Copiar no
   cabecalho da coluna somente quando a ficha aberta e a mae.

## Scope / Subtasks
- Subtask A (dado): ja estava pronta da sessao anterior (modelo `copyOf`, store).
- Subtask B (comandos): `give` por nome de ficha + sugestoes de nomes de ficha.
- Subtask C (vinculo): roubo limpa o nome customizado do mob antigo.
- Subtask D (UI): coluna de copias no editor + payload de copiar no servidor.

## What Changed
1. **`/rpg mob give` por nome de ficha.** `MasterCommands.giveMobSheetByName`
   resolve pela FICHA do Mestre (`ThreatSheetStore.find(uuid, nome)`), esteja a
   ficha vinculada a um mob ou nao. Novo autocomplete `suggestSheetNames` sugere
   nomes de ficha (antes sugeria nomes de mob vinculado). `findBoundMobByName`
   continua servindo o `view`.
2. **Roubo limpa o nome.** `ThreatSheetBinding.bind` (ramo de roubo) agora chama
   `previousMob.setCustomName(null)` + `setCustomNameVisible(false)` alem de
   remover a tag. Comentario referencia o feedback de 07/10/2026.
3. **Coluna de copias no editor** (`ThreatSheetScreen`):
   - `KIND_COPIES` + `ListRegion copiesRegion` reutilizam a rolagem existente
     (roda + barra + clique); `regions()` ganha a regiao quando a coluna existe.
   - `copiesVisible = panelW >= 360`; com a coluna ativa, `colW` encolhe
     (`panelW - 2*PAD - (BAR_W+2) - 2*COL_GAP - COPIES_W)/2`) e
     `copiesX = rightX + colW + COL_GAP` (algebra: borda direita do painel).
   - Cabecalho: ficha aberta E copia -> botao com o nome da MAE (clicavel, abre a
     mae -> "voltar da copia" como titulo, sem botao novo). Ficha aberta E mae com
     id -> rotulo "Copias" + botao `Copiar`. Ficha nova (sem id) -> nada.
   - Linhas: copias da mae via `copiesList()` (`knownSheets` filtrando
     `copyOf == mother.id()`); a ficha ABERTA, quando e copia, aparece como texto
     apagado (sem botao, evitando widget duplicado); as outras abrem com
     `new ThreatSheetScreen(this, target, name)` (Voltar retorna ao editor).
   - Dados: o editor pede a lista no `init()` (`requestList` ->
     `ThreatSheetListRequestPayload`) porque `/rpg mob view` abre sem estado de
     lista; `applyList` e `applyResult` agora chamam `rebuildCopiesColumn()`.
4. **Servidor: `ThreatSheetCopyPayload(motherName)`** (C2S, espelho do delete,
   clamp `MAX_NAME`) + handler `copyThreatSheetFromScreen`: mestre? -> `find` por
   nome -> `createCopy(uuid, mother.id())` -> `ThreatSheetResultPayload` com
   snapshot. Mensagem de sucesso: "Copia criada: <nome da mae>".

## Files Changed
- `src/main/java/com/pedro/tabletoprpg/RpgNetworking.java`: record
  `ThreatSheetCopyPayload`, registro C2S, receptor global, handler
  `copyThreatSheetFromScreen`.
- `src/main/java/com/pedro/tabletoprpg/MasterCommands.java`: `giveMobSheetByName`
  (reescrito por nome de ficha), `suggestSheetNames`, comentario da arvore.
- `src/main/java/com/pedro/tabletoprpg/ThreatSheetBinding.java`: roubo limpa o
  nome customizado do mob.
- `src/client/java/com/pedro/tabletoprpg/client/ThreatSheetScreen.java`: coluna de
  copias (constantes, campos, `layout`, `init`, `regions`, `rebuildRegion`,
  metodos novos, `applyList`/`applyResult`, render).
- `agent/state/2026-10-05_6-bugs.md`: secao "Progresso de 07/10/2026".
- `agent/memory/project-memory.md`: licoes (git apply via cmd /c, SaveResult
  qualificado, padrao da coluna de listas).

## Decisions
- `give` por NOME DE FICHA (decisao final dela, registrada) - qualquer ficha,
  qualquer hora, vincular nao e requisito.
- Coluna de copias DENTRO do editor, borda direita, mesma rolagem do projeto;
  Copiar no cabecalho, so mae com id salvo; voltar da copia = titulo da mae
  clicavel; sisters continuam visiveis com a copia aberta (ficha aberta mutada).
- `panelW >= 360` como limiar: abaixo disso a coluna some (formulario nao vira
  frestas). Em 1080p (logical ~427) a coluna aparece com `colW` ~122.
- Codigo novo 100% ASCII; strings com acento do handler de DELETE pre-existente
  ficaram intactas; comentario da arvore do give reescrito em ASCII puro.

## Validation
- Build Gradle: `BUILD SUCCESSFUL`, 236 testes / 0 falhas (log
  `%TEMP%\opencode\build-copies3.log`).
- Erro de compilacao intermediario corrigido (`SaveResult` -> qualificado).
- Scan de codepoints (U+00XX > 127) nos 4 arquivos: hits restantes sao todos de
  texto PRE-EXISTENTE (delete, javadoc velho); codigo novo confirmado ASCII.
- Checkpoint `checkpoint-20261007-154524` (16 arquivos, tracked.patch 100.224
  bytes) com prova `git apply --check --reverse` exit 0 (via cmd /c). Nenhum
  commit criado; HEAD segue 2268338.

## Problems Encountered
1. Compile fail: `error: cannot find symbol SaveResult` em RpgNetworking:2347.
2. `git apply --check --reverse` via PowerShell retornava "No valid patches in
   input" (exit 128) para um patch valido.
3. Scan de encoding acusava acentos em linhas "novas" (2324/2328/2330 no
   networking, 1332/1333/1336 no editor).

## Root Causes
1. FATO: o handler de save ja usa `ThreatSheetStore.SaveResult` qualificado; o
   pacote nao importa a enum e o nome curto nao resolve. Eu usei o nome curto.
2. FATO: o pipeline nativo do PowerShell (`2>&1 |` em chamada nativa) corrompe a
   chamada; via `cmd /c` o mesmo patch passa (exit 0).
3. FATO: as linhas acusadas eram de codigo PRE-EXISTENTE (handler de delete,
   javadoc do applyResult); verificada por leitura do trecho, nada a corrigir.

## Fixes
1. `ThreatSheetStore.SaveResult result = ...` (qualificado).
2. Prova de checkpoint executada por `cmd /c`.
3. Nenhum fix necessario; o `give` comment line foi reescrito em ASCII puro
   ("INVENTARIO", "exibicao", "so o mestre") por disciplina de encoding.

## Remaining Issues
- **Highlight segurando o item** (ultima subtask do Bug C, especificada mas NAO
  implementada): `ThreatSheetHoverPayload` S2C + campo
  `boundSheetHoverEntityId` no cliente + gate por item segurado + master +
  cleanup no DISCONNECT.
- Validacao em partida real: coluna de copias (abrir, Copiar, voltar pela mae,
  sisters), give a qualquer hora, roubo sem nome duplo, cascata ao apagar a mae,
  limiar 360 da coluna, bug D no respawn, escada (Bug F), round-trip NBT do
  diario. Nada disso e comprovado por build verde.
- Commitar: aguarda autorizacao explicita da jogadora (nada foi commitado).

## Lessons / Memory
- Ver `agent/memory/project-memory.md` (07/10/2026): git apply via cmd /c;
  SaveResult qualificado; padrao de coluna lateral com ListRegion; lista nova no
  init() porque `/rpg mob view` abre sem estado de lista.

## Next Steps
1. Implementar o highlight (payload + estado cliente + cleanup) - subtask 4 do
   plano original, ainda pendente.
2. Uma partida real para validar tudo de uma vez (escada, bug D, diario,
   copias, give, highlight).
3. Atualizar estado e pedir autorizacao para commitar.

## Batch 2 - correcao do feedback (07/10/2026)

A jogadora corrigiu o pedido de largura: o MENU fica como esta (760); so a
coluna de copias cresceu 132 -> 165 (+25%) com o minimo em 430, e quem cedeu
foram as colunas do formulario (colW 299 -> 280; clip de "Ataques e
Habilidades Ativas" em 175px, folgado). Nesta leva tambem entrou:

- BOLINHA DENTRO DOS BOTOES: novo `DotWidget` (widget 3x3 via addRenderableOnly
  - o render da tela roda antes dos widgets, entao um traco solto sumiria atras
  do botao). Locais: botao Salvar (`rebuildFooter`, `() -> dirty`), nome na
  lista de maes (`hasDraft(id)`) e linhas da coluna de copias quando a copia
  tem rascunho e o Mestre saiu dela. A linha da ficha aberta (muted + Del) nao
  ganha bolinha - o editor que esta aberto ja mostra a sua no Salvar.
- SETAS: dos extremos da TELA para os cantos de baixo do MENU (panelX+6 /
  panelX+panelW-size-6, y = panelY+panelH-size-6).
- SALVAR RENOMEADO: `sheet` deixou de ser final; no applyResult ok a tela
  troca a ficha pela gravada pelo servidor (mesmo id, nome novo) - o Copiar
  funciona sem fechar e reabrir. O `title` do Screen e FINAL no 1.21.11, entao
  o cabecalho usa `currentTitle()` (nome gravado da ficha).
- VOLTAR DE UM NIVEL: `onClose` deterministico - copia abre a MAE (parente =
  `backParent()`, o primeiro pai nao-editor da cadeia) e mae volta para a lista.
- COPIAS FORA DA LISTA DE MAES: `mothers()` filtra `!isCopy()`; a lista, a
  rolagem e o thumb usam so maes. `/rpg mob give` intacto (lado servidor, por
  nome, no store - nenhum payload mudou).
- VALIDACAO: build + ThreatSheetStoreTest verdes; scan ASCII limpo (47 acentos
  pre-existentes, 0 novos); revisao read-only 0 bloqueante / 0 arriscado /
  2 cosmeticos (titulo corrigido; setas com 3px atras do rodape - imperceptivel,
  mantido).
- PENDENTE: partida real (bolinhas, Copiar apos renomear sem reabrir, setas,
  Voltar, lista so de maes, give para ficha e copia) e commit com autorizacao.