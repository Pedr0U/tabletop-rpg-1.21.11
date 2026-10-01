# Implementation Report — Block Lock

## Status

**Implementado e compilando. Nao validado dentro do jogo.**

O que esta verificado: `gradlew build` verde (inclui `scanEncoding` com 0 mojibake e
0 ideograma), boot do `runClient` sem crash-report, e as 13 referencias de chave de
traducao do codigo novo resolvendo para chaves existentes em `en_us.json`.

O que **nao** esta verificado: que trancar um bau de fato bloqueia o jogador, que a
porta nao abre, que a mensagem aparece e que a tranca sobrevive a um restart. Isso
exige um mundo, um Mestre e dois jogadores, e depende do teste do usuario.

Branch `main`, HEAD `7ace0fd` na inicio da sessao, arvore limpa (nenhum trabalho
pre-existdo do usuario para preservar). **Nada foi commitado nem pushado.**

## Objective

Trancar portas, baus e qualquer bloco de inventario interagivel (barris, fornalhas,
inventarios de mod) para que os jogadores nao possam mais interagir com eles, por
comando do Mestre e por um item customizado.

## Scope / Subtasks

1. Desenho e verificacao de API (bytecode do Fabric API, `javap` no jar nomeado)
2. `BlockLockStore` — persistencia das trancas
3. `BlockLockManager` — regra de decisao do clique
4. Item `Block Lock` (registro, aba criativa, modelo, sprite, traducao)
5. Comandos `/rpg block_lock` e `/rpg block_lock remove` + botoes no menu ASCII
6. Catalogo (`FUNCIONALIDADES-E-COMANDOS.md`)
7. Revisao independente, correcoes, revalidacao
8. Relatorio e memoria

## What Changed

**Persistencia.** `BlockLockStore` e um `SavedData` do overworld
(`data/tabletop_rpg_block_locks.dat`), com chave **dimensao + posicao**. O motivo
da dimensao na chave: Overworld, Nether e End compartilham o mesmo arquivo, e
`BlockPos` sozinho trancaria o bau de mesma posicao em outra dimensao.

**Decisao do clique** (`BlockLockManager.onUseBlock`, um unico handler), na ordem:

1. Nao-Mestre com o item na mao → recusa com mensagem, `FAIL`.
2. Mestre com o item → **alterna** (tranca/destrava), `SUCCESS`.
3. Mestre com pedido armado pelo comando → executa a ordem (nao alterna), `SUCCESS`.
4. Jogador em bloco trancado → `Block Locked`, `FAIL`.
5. Demais → `PASS`.

O clique e **consumido** (`SUCCESS`), que e o requisito "trancar sem abrir o bau".

**O que e trancavel.** `getMenuProvider(level, pos) != null` (inventario) **ou** a
classe do bloco sobrescrever `useWithoutItem`/`useItemOn` (porta, alavanca, botao).
Bloco sem interacao e recusado com mensagem explicativa.

**Item.** `tabletop-rpg:block_lock`, aba criativa e `/give`, sprite provisorio
`minecraft:item/trial_key`. Nao-Mestre recebe recusa no clique em bloco e no clique
no ar.

## Files Changed

**Novos**
- `src/main/java/com/pedro/tabletoprpg/BlockLockStore.java`
- `src/main/java/com/pedro/tabletoprpg/BlockLockManager.java`
- `src/main/resources/assets/tabletop-rpg/items/block_lock.json`
- `src/main/resources/assets/tabletop-rpg/models/item/block_lock.json`

**Modificados**
- `src/main/java/com/pedro/tabletoprpg/TabletopRpg.java` — registro do manager
- `src/main/java/com/pedro/tabletoprpg/MasterCommands.java` — 2 comandos, 2 handlers, 2 botoes
- `src/main/java/com/pedro/tabletoprpg/item/ModItems.java` — item, aba criativa, caminho do clique no ar
- `src/main/resources/assets/tabletop-rpg/lang/en_us.json` — 11 chaves
- `FUNCIONALIDADES-E-COMANDOS.md` — 2 linhas de comando + secao de detalhe
- `agent/memory/project-memory.md` — 4 licoes duraveis

## Decisions

| Decisao | Origem |
|---|---|
| Persistir em `SavedData`, nao em memoria | usuario |
| Recusar bloco sem interacao, com mensagem | usuario |
| O clique do Mestre so aplica a tranca (nao abre o bau) | usuario |
| Item pela aba criativa + `/give`, sem entrega automatica | usuario |
| Mensagens em ingles | projeto e English-facing desde 26/09/2026 |
| Interceptar em `UseBlockCallback`, nao `UseItemCallback` | verificacao de bytecode |
| Comando executa ordem; item alterna | usuario |
| Sem TTL no pedido armado | nao inventar comportamento nao pedido; registrado como decisao em aberto |

## Validation

| Verificacao | Resultado |
|---|---|
| `gradlew build --no-daemon` | BUILD SUCCESSFUL |
| `scanEncoding` (task do build) | 0 mojibake, 0 ideograma, 0 U+FFFD em 107 arquivos |
| Auditoria CJK/cirilico nos 9 arquivos tocados | 0 ocorrencias |
| `BlockLockStore.java` ASCII puro | sim (4 acentos removidos) |
| `en_us.json` parseia + 13 chaves resolvem | ok |
| `runClient` | ok: `Mod inicializado com sucesso`, `Sound engine started`, sem `crash-reports/`, sem `InvalidMixin`/`InvalidInjection`/`ClassCastException` |
| Comportamento em jogo | **nao testado** |

Um build verde prova compilacao, nao comportamento. O `UseBlockCallback` cancelando
o pacote errado era o risco principal desta feature, e por isso o bytecode do
Fabric API foi lido antes de escrever a linha de decisao, e o boot do jogo foi
conferido no build final — mas o clique de verdade em um bau continua sem teste.

## Problems Encountered

**1. Feature inteira inoperante por ordem de registro.** `UseBlockCallback` e
array-backed e para no primeiro resultado diferente de `PASS`. O
`CombatController` devolve `FAIL` sempre que ha monstro selecionado, e o
`BlockLockManager` estava registrado depois dele.

**2. Chave de traducao com hifen faltando.** O JSON tinha
`item.tabletoprpg.block_lock.denied` e o codigo usava
`item.tabletop-rpg.block_lock.denied`: a negacao apareceria como chave crua.

**3. `destrajado` escrito como `destra` + outra letra na declaracao.** Compilador
acusou simbolo inexistente em uma linha que *parecia* identica.

**4. Chave de blocos desbalanceada** na edicao que adicionou `isInteractable`.

**5. Imports errados** em duas chamadas novas: `PlayerBlockBreakEvents` esta em
`event.player` (nao `event.lifecycle.v1`) e `BlockEntity` esta em
`world.level.block.entity`.

**6. Comentario prometendo TTL inexistente**, que se autocontradizia no paragrafo
seguinte — exatamente o defeito que ele dizia evitar.

**7. Duas mensagens para um clique** em bloco sem interacao.

**8. Script de auditoria com acento nao parseava.** PowerShell 5.1 le `.ps1` como
ANSI; as acentos viraram mojibake e o script morreu com erro de parser.

**9. Caractere de outro sistema de escrita no meu proprio texto.** Um caractere CJK
num prompt de revisao e um nome japoneses na memoria, apontados pelo usuario. Corrigidos.

## Root Causes

**1 — FATAL. FACT (fonte do Fabric API):** `UseBlockCallback.EVENT =
EventFactory.createArrayBacked(...)`, documentado como "PASS falls back to further
processing". Ordem de registro e ordem de decisao. Como o `CombatController`
(`CombatController.java`, ramo do `UseBlockCallback`) devolve `FAIL` com selecao
ativa, o handler registrado depois nunca rodava: o Mestre movia o monstro em vez de
trancar o bau, sem erro. Corrigido movendo o registro para antes do `CombatController`.

**2 — FACT:** a chave automatica de traducao inclui o hifen do namespace
(`tabletop-rpg`), enquanto subchaves escritas a mao seguiam o padrao antigo sem
hifen (`item.tabletoprpg.sheet_editor.denied`, que **continua com o bug** —
pre-existente, nao tocado).

**3 — HYPOTHESIS -> resolvido:** digitacao em dois eventos separados, nao encoding.
Conferido por codepoint: a declaracao tinha `v` (118) e o uso `j` (106). O `edit`
normaliza a entrada e nao permitia corrigir pelo caminho normal; resolvido por
script byte-a-byte, reescrevendo so a palavra.

**6 — DECISAO minha, nao do usuario:** escrevi um comentario descrevendo um TTL que
nao implementei. A contradicao interna do texto foi o sinal.

**8 — FACT (PowerShell 5.1):** `.ps1` sem BOM e lido como Windows-1252.

## Fixes

- Registro do `BlockLockManager` movido para antes do `CombatController`.
- `en_us.json`: hifen corrigido em `item.tabletop-rpg.block_lock.denied`.
- `BlockLockStore.java` 100% ASCII.
- Comentario do TTL reescrito para descrever o comportamento real (pedido de 1
  clique, consumido por clique, item e desconexao) e apontar o TTL como decisao
  em aberto.
- Mensagem duplicada: `lastBlockHandledGameTime` marca o clique de bloco e
  `justHandledBlockClick` omite a dica redundante.
- Nao-Mestre com o item em clique de bloco passa a receber recusa explicita.
- `PlayerBlockBreakEvents.AFTER` remove a tranca da posicao quando o bloco e
  quebrado (bau novo na posicao nascia trancado sem ninguem pedir).
- `isLocked` deixou de fazer cast cego de `Level` para `ServerLevel`.
- `sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS)` depois de aplicar a
  tranca, para o cliente sobrescrever a predicao local.
- Codigo morto removido (`countLocked`) e nome de API corrigido no Javadoc
  (`Item.useOn` via `UseItemCallback`, nao `Item#use`).
- Script de auditoria reescrito em ASCII puro, montando listas por codepoint.

## Remaining Issues

1. **Teste em jogo pendente.** Nada garante ainda que trancar um bau bloqueia o
   jogador, que a porta nao abre, ou que a tranca sobrevive a restart. E o teste
   que falta.
2. **Pedido armado sem prazo.** Se o Mestre armar e ficar longo tempo no chat, o
   proximo bau em que clicar sera trancado. Recuperavel (mensagem nomeia a acao,
   item destrava), mas e uma decisao de produto em aberto.
3. **Item so pela aba criativa/`/give`.** Nao ha `/rpg give`; em survival o Mestre
   precisa do `/give` vanilla.
4. **Tranca orfa so e limpa na quebra por jogador.** Explosao e piston removem o
   bloco sem passar pelo evento.
5. **Bug pre-existente nao tocado:** `item.tabletoprpg.sheet_editor.denied` no JSON
   (sem hifen) continua divergindo do codigo.
6. **Falsos positivos do criterio de interagibilidade** (ancora de respawn, neve):
   podem ser trancados sem efeito. Aceito de proposito.

## Lessons / Memory

Gravadas em `agent/memory/project-memory.md`:

- `UseBlockCallback` e array-backed e para no primeiro resultado diferente de `PASS`.
- Cliente **nunca** pode devolver diferente de `PASS` nesse callback: o mixin
  cliente nao envia pacote nenhum, e cancelar impede o servidor de receber o clique.
  (Um revisor afirmou o contrario; o bytecode mostra que nao ha `send` no corpo.)
- PowerShell 5.1 le `.ps1` como ANSI: script temporario tem de ser ASCII puro.
- Bloco trancavel sem tag do vanilla, e o cuidado de `getDeclaredMethod` vs
  `getMethod` em metodo protected.

## Next Steps

1. Testar em jogo com `.\gradlew.bat runClient`: `/rpg master claim`,
   `/give @s tabletop-rpg:block_lock`, travar um bau e um jogador tentando abrir.
2. Reiniciar o mundo e conferir `data/tabletop_rpg_block_locks.dat`.
3. Decidir sobre o TTL do pedido armado.
4. Corrigir `item.tabletoprpg.sheet_editor.denied` (fora do escopo desta feature).
5. Commit somente depois do teste em jogo; ate la, working tree e o registro.