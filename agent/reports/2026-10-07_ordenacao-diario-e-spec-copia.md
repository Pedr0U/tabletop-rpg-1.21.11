# Implementation Report

## Status
PARCIAL. Quatro coisas fechadas e validadas; Bug C (copia de fichas) coletado em
especificacao mas nao iniciado. Nenhum commit foi feito -- a jogadora pediu so
checkpoint e ate agora nao foi perguntado sobre commit.

## Objective
Entregar os 6 bugs reportados em 05/10/2026 para o mod `tabletop-rpg-1.21.11`
(Fabric), mais o feedback e a nova spec de 07/10/2026, sem commitar sem
autorizacao.

## Scope / Subtasks

| Subtask | Estado |
|---|---|
| Bug A `4#2d6c4` | FECHADO (sessao anterior) |
| Bug B `/rpg mob` abre ficha | FECHADO (sessao anterior) |
| Bug D camera espectavel | FECHADO (sessao anterior) |
| Bug E fortitude/reflexos/vontade | ENCERRADO sem mudanca (decisao dela) |
| Bug F mob largo | FECHADO (sessao anterior) |
| Bug C copia de fichas | SPEC COLETADA, nao iniciado |
| Ordenacao "Modificado" do diario | **FECHADO NESTA SESSAO** |
| Escada de mob largo | AGUARDA PARTIDA (hipotese) |
| Aviso de payload do diario | APROVADO, nao implementado |
| Problema 3 (versao do pacote) | AUTORIZADO, nao implementado |

## What Changed

### 1. Ordenacao "Modificado" do diario (bug novo da jogadora)

Relato dela: "ao alterar algo na subsecao CARACTERISTICAS, a subsecao NPC 1 devia
ficar em primeiro na ordenacao das subsecoes da sessao NPCs".

Causa raiz (FACT): `DiaryStore:317` ordenava pelo `modifiedAt` **do proprio no**, e
as duas telas passavam so os **irmaos** -- `DiaryScreen:272` e `DiaryNodeScreen:343`
chamavam `sorted(childrenOf(...))`, entao os descendentes nunca chegavam ao metodo.
Uma edicao profunda so mexe no timestamp do no editado.

Correcao: `sorted` ganhou a assinatura `(shown, all, mode)`. O metodo calcula
`effectiveModified(all)`, que para cada no guarda o `modifiedAt` **mais novo da sua
subarvore**, e ordena cada nivel por esse valor.

Duas decisoes de implementacao:

- **Propagacao iterativa, nao recursao.** O diario nao tem teto de profundidade (o
  teste `profundidadeIlimitada` sobe 40 niveis). Recursao aqui estouraria a pilha num
  diario ja editado, que e o caso normal. A subida para quando o ancestral ja tem
  timestamp igual ou maior.
- **So de leitura.** Nada e regravado, o timestamp gravado de cada no continua sendo
  o dele. O botao Reverter nao recebe alteracao nenhuma por causa da ordenacao.

Custo: o mapa so e construido no modo `MODIFIED`; `ALPHABETICAL` passa `Map.of()`.
Isso importa porque `sorted` roda dentro do render, com a tela de diario aberta.

### 2. Medicao do limite de payload

FACT: `net.minecraft.network.PacketDecoder` contem a constante **1.048.576 bytes**.
Confirmado lendo as constantes inteiras do bytecode da classe no jar do Loom, nao por
suposicao.

FACT: o comentario de `RpgNetworking:2342-2343` diz "o pior caso e ~100 KB" e esta
ERRADO. `MAX_ENTRIES 2048 x (MAX_TITLE 40 + MAX_DESCRIPTION 2000)` = ~4,18 MB, acima
de 1 MB. O estouro acontece por volta de ~500 entradas no tamanho maximo.

### 3. Checkpoints

Dois checkpoints sem commit, ambos provados fiel com
`git apply --check --reverse` exit 0:

- `checkpoint-20261007-122632` -- 7 arquivos, 34.415 bytes
- `checkpoint-20261007-125224` -- 11 arquivos, 45.613 bytes

### 4. Coleta de especificacao do Bug C

Duas rodadas de perguntas. As respostas estao em
`agent/state/2026-10-05_6-bugs.md`, secao "Feedback da jogadora de 07/10/2026".

## Files Changed

- `src/main/java/com/pedro/tabletoprpg/DiaryStore.java` -- `sorted` delega de 2 para 3
  argumentos; novo `effectiveModified` privado.
- `src/client/java/com/pedro/tabletoprpg/client/DiaryScreen.java:272` -- repassa
  `entries`.
- `src/client/java/com/pedro/tabletoprpg/client/DiaryNodeScreen.java:343` -- repassa
  `entries`.
- `src/test/java/com/pedro/tabletoprpg/DiaryStoreTest.java` -- 3 testes novos.
- `agent/state/2026-10-05_6-bugs.md` -- decisoes de 07/10/2026 gravadas.

## Decisions

| Assunto | Decisao |
|---|---|
| Ordenacao do diario | leitura apenas, subarvore, sem tocar no Reverter |
| Escada | e escada de subida; **confirmar em partida antes de codar** |
| Lista da direita | so copias; titulo clicavel da mae para voltar |
| Com copia aberta | continua mostrando as irmas |
| Apagar mae | apaga mae e copias juntas |
| Copia nasce | sem vinculo, sem mob |
| Numeracao | menor numero livre |
| Renomear mae | copia criada mantem nome; copia nova apos renomear respeita o novo |
| `/rpg mob <nome>` | some; fica so `view` e `give` |
| Highlight | substitui o antigo, so mestre, sempre visivel |
| Payload | soma no salvar e aviso |
| Problema 3 | versao no pacote + erro claro de atualizar mod |

## Validation

- `gradlew build` exit 0.
- **218 testes, 0 falhas** (era 215 antes; 3 novos).
- `scanEncoding` OK: 182 arquivos, 0 mojibake, 0 ideograma, 0 U+FFFD.
- Checkpoints provados com `git apply --check --reverse` exit 0.

Teste novo que **falhava antes** da correcao: monta `Sessao B` primeiro na lista e com
timestamp maior que `Sessao A`, e so o maximo da subarvore coloca `Sessao A` na frente.
Usar o proprio no deixa `B` na frente; usar o maximo global empataria e a ordem
estavel manteria `B` na frente tambem.

## Problems Encountered

1. Duas falhas de teste durante o desenvolvimento.
2. Dois erros meus na verificacao do checkpoint.
3. Comentario incorreto sobre o tamanho do payload ja existente no codigo.

## Root Causes

1. **Causa raiz do bug de ordenacao (FACT):** o ordenador media o timestamp do no
   proprio, e os chamadores passavam so o nivel visivel. O dado para calcular a
   resposta certa (a subarvore) nunca chegava ao metodo.
2. **Causa dos erros de teste:** escrevi as assercoes antes de conferir o que
   `sorted` realmente recebe. `sorted` ordena a lista que ganha, nao filtra por nivel
   -- nos chamadores de verdade a lista ja chega so com os filhos. Nao e bug de
   producao.
3. **Causa do erro de checkpoint:** `Sort-Object Name` e lexical. `checkpoint-2709-*`
   vem depois de `checkpoint-20261007-*` porque `0 < 7` na quinta posicao. Usar
   `LastWriteTime`.

## Fixes

1. `sorted(shown, all, mode)` + `effectiveModified` por propagacao iterativa.
2. Checkpoints reordenados por `LastWriteTime` e re-provados com reverse-check.

## Remaining Issues

1. **Bug C nao iniciado.** Spec completa, codigo nenhum. E o unico que muda modelo de
   dado -- o mais arriscado.
2. **Aviso de payload aprovado e nao implementado.** Bloqueio real: nao ha canal de
   mensagem no cliente. `DiaryException` so e capturada no servidor
   (`RpgNetworking:2642, 2658, 2669`); nenhuma chamada a `getMessage()` no fonte
   client. Onde o aviso aparece e mudanca visual -> **perguntar antes**.
3. **Problema 3 autorizado e nao implementado.**
4. **Escada de mob largo nao verificada.** Hipotese: a parede atras da escada e celula
   vizinha e a correcao do Bug F ja a removeu. Precisa de uma partida.
5. Comentario errado de `RpgNetworking:2342` nao corrigido.
6. Risco antigo, nao medido antes e agora estimado: pacote do diario pode passar de
   1 MB com ~500 entradas grandes.
7. Assimetria destino/caminho do Bug F continua registrada e nao resolvida (decisao
   dela: ignorar).
8. `stash@{0}` e dois registros do agente continuam no repositorio.
9. Nenhum commit feito e nenhum pedido de commit ate agora.

## Lessons / Memory

- **Nao ordenar diretorio de checkpoint por nome.** Timestamps em formato
  `yyyyMMdd-HHmmss` nao ordenam lexicalmente quando ha nomes legais no mesmo pool.
  Ordenar por `LastWriteTime` e provar com `git apply --check --reverse`.
- **`sorted(list, mode)` tipo "ordena o que eu te der" e diferente de "ordena o nivel
  certo".** Antes de calcular ordem por dado hierarquico, confirmar o que o chamador
  realmente passa -- aqui ele passava so os irmaos.
- **Constante de limite do vanilla se prova no bytecode, nao em memoria.**
  `javap` nao estava no PATH; escanear as constantes `CONSTANT_Integer` direto no
  `.class` funcionou e e reproduzivel.
- **Comentario de desempenho envelhece e vira mentira.** O "~100 KB" de
  `RpgNetworking:2342` contradiz a propria conta das constantes do arquivo.

## Next Steps

1. Perguntar **onde** o aviso de payload aparece (e visual).
2. Implementar o aviso + corrigir o comentario de `RpgNetworking:2342`.
3. Problema 3: numero de versao em `DiaryStatePayload`.
4. Bug C em subtasks atomicas: modelo de dados -> comandos `view`/`give` -> vinculo
   1:1 com sobrescrita -> highlight -> lista lateral.
5. Uma partida de jogo fecha de uma vez: escada, bug D e o diario.
6. Perguntar sobre commit (regra dela: so no fim).
