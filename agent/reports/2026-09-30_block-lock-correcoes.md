# Implementation Report — Block Lock, rodada 2 (correcoes de teste em jogo)

## Status

**Implementado e compilando. 3 dos 4 defeitos corrigidos. Nao validado dentro do jogo.**

Verificado nesta rodada: `gradlew build` verde; auditoria de codepoint em 104 arquivos de
`src/` e `agent/` com **0** caractere CJK, cirilico ou U+FFFD; simbolos de API confirmados
por `javap` antes de usar (ver *Evidence*).

**Nao** verificado: nada disso foi exercitado em um mundo real. Depende do teste do usuario.

Decisao do usuario em 30/09/2026: **nao** mexer na animacao da porta que abre por
milissegundos (ver *Remaining Issues*, com o risco explicitado).

Branch `main`. **Nada foi commitado nem pushado.**

## Objective

Responder a 3 defeitos reportados pelo usuario em teste em jogo, mais 2 pedidos de
escopo: remover os botoes `[Lock]`/`[Unlock]` do menu ASCII, e nao emitir caracteres de
outro sistema de escrita.

## Scope / Subtasks

1. Remover os botoes de tranca do menu ASCII
2. Recusa de quebra de bloco trancado por jogador (+ limpeza no break do Mestre)
3. Porta de 2 alturas como unidade logica (travar/consultar/destravar as duas metades)
4. Deteccao de bloco interagivel por assinatura, em vez de por nome de metodo
5. Auditoria de encoding em arquivo pre-existente
6. Catalogo, memoria e relatorio

## What Changed

**1. Menu ASCII sem botao de tranca** (`MasterCommands.java: buildAsciiMenu`).
`[Lock]` e `[Unlock]` removidos; comando e item continuam. Decisao do usuario.

**2. Bloco trancado indestrutivel para jogador** (`BlockLockManager.java: register`).
Um unico listener de `PlayerBlockBreakEvents.BEFORE` faz as duas coisas:
- jogador em bloco trancado -> `false` (recusa) + `This block is locked. Only the Master can break it.`
- Mestre -> `true` e a tranca e removida (bloco deixa de existir; senao o proximo bloco
  colocado na posicao nasceria trancado).

Novo `PlayerBlockBreakEvents.BEFORE` substituiu o antigo `AFTER` (`dropLockOnBreak`,
removido por ficar sem uso).

**3. Bloco logico de N posicoes** (`BlockLockManager.java`). `blockParts` devolve as
posicoes que formam o mesmo bloco; `anyPartLocked` e `dropLocksOf` operam no conjunto.
`lock`, `unlock`, `toggle`, `apply` e o `resyncToClient` passaram a receber a lista.
So `DOUBLE_BLOCK_HALF` e usado.

**4. Deteccao de interagibilidade por assinatura** (`BlockLockManager.java:
declaringClassOf`). De `getDeclaredMethod("nome", ...)` para `getDeclaredMethods()` +
`Arrays.equals(getParameterTypes(), ...)`. Efeito colateral desejado: passa a detectar
bloco de mod que ainda sobrescreve o `use` antigo, que tem a mesma assinatura do
`useWithoutItem`.

**5. Encoding.** `TabletopRpgClient.java:108` tinha tres caracteres cirilicos
homografos ("CYRILLIC ve + i + te") no lugar de `marcou`, dentro de um comentario. Nao
foi desta rodada; foi encontrado pela varredura de codepoint e corrigido byte a byte.

**6. Documentacao.** `FUNCIONALIDADES-E-COMANDOS.md` ganhou 3 topicos novos (assinatura
em vez de nome, porta como unidade, regra de quebra) e o registro da remocao do botao.
Nova chave de traducao `message.tabletop-rpg.block_locked_cannot_break`.

## Files Changed

- `src/main/java/com/pedro/tabletoprpg/BlockLockManager.java` (corpo da rodada 2)
- `src/main/java/com/pedro/tabletoprpg/MasterCommands.java` (menu ASCII)
- `src/main/resources/assets/tabletop-rpg/lang/en_us.json` (+1 chave)
- `src/client/java/com/pedro/tabletoprpg/client/TabletopRpgClient.java` (1 palavra em comentario)
- `FUNCIONALIDADES-E-COMANDOS.md`, `agent/memory/project-memory.md`, este relatorio

## Decisions

- **Nao implementar o sync de trancas ao cliente.** O usuario decidiu em 30/09/2026 que o
  risco nao compensa. O plano era: payload S2C com `isMaster` + mapa dimensao->posicoes,
  `UseBlockCallback` no cliente devolvendo `FAIL`. Registrado aqui para nao se perder.
- **Nao criar tag de interagibilidade.** `BlockTags` em 1.21.11 nao tem `OPENABLE`,
  `CONTAINERS`, `SHOPIERS` nem `LEVERS`, e tag deixaria de fora bloco de mod, que e
  metade do motivo da feature.
- **`After` -> `Before` para limpeza de estado**, em vez de um segundo mecanismo.

## Validation

Executado:

| Checagem | Resultado |
|---|---|
| `gradlew build` | BUILD SUCCESSFUL |
| `scanEncoding` do Gradle | 8121 nao-ASCII em 84 arquivos, so acentos/-tipografia |
| Varredura de codepoint (CJK + cirilico + U+FFFD), 104 arquivos | **0** |
| UTF-8 estrito do arquivo editado byte a byte | valido, 27503 bytes |

Nao executado: teste dentro do jogo. Nao ha automacao que monte um mundo com um Mestre e
um jogador e faca o segundo clicar num bau trancado.

## Problems Encountered

| Sintoma | Causa (com evidencia) | Correcao |
|---|---|---|
| Porta abre mesmo trancada; "a 5 blocos"; "de um nivel abaixo" | tranca so na posicao clicada; a metade contraparia e outra posicao | `blockParts` + `anyPartLocked` |
| Quebra e recoloca continua trancado | `PlayerBlockBreakEvents.AFTER` so roda se nenhum `BEFORE` cancelar, e o `PlayerControlHandler` cancela | limpeza no `BEFORE` |
| Porta recusada como "sem interacao" | `javap` mostra `DoorBlock` **declara** `useWithoutItem` com a assinatura exata, entao o caminho por nome *deveria* funcionar. **Nao reproduzi a recusa.** O defeito estrutural (literal de string nao e remapeado) justifica a troca | deteccao por assinatura |
| `cannot find symbol` em `blockParts` | escrevi `hasValue`; o metodo e `hasProperty` (confirmado em `StateHolder`) | corrigido |
| Rastreamento de escada trancando o ar acima | usei `HALF`, que em slab/escada/armadilha e **formato**, nao posicao | removido o ramo `HALF` |
| `"servidormarcouu"` depois do reparo do cirilico | slicing de array de bytes perdeu o ultimo byte e faltava o espaco | 2 correcoes pontuais, verificado byte a byte |

## Root Causes

FACT (javap, jar nomeado 1.21.11): `DoorBlock` declara
`protected InteractionResult useWithoutItem(BlockState, Level, BlockPos, Player, BlockHitResult)`.

FACT (javap): `DoubleBlockHalf` tem **apenas** `UPPER` e `LOWER`. `BedBlock` usa
`BedPart`, nao `Half`.

FACT (Javadoc do Fabric API): `PlayerBlockBreakEvents.AFTER` so e disparado se nenhum
listener de `BEFORE` cancelar.

INFERENCE (alta confianca): o defeito "abre mesmo trancada", "a 5 blocos" e "de um nivel
abaixo" tem **uma** causa so -- as tres descricoes sao a mesma coisa observada de angulos
diferentes: clicar na metade de cima da porta. "5 blocos" e o alcance do modo criativo do
vanilla (5.0), e nao um bug do mod.

HIPOTESE (nao confirmada, por isso a troca foi feita mesmo assim): o remapeamento
nomeado->intermediario do carregador reescreve referencias de classe mas nao literais de
string, entao `getDeclaredMethod("useWithoutItem", ...)` falha em runtime apesar de o
metodo existir, e o `NoSuchMethodException` e engolido num cache estatico permanente.
Nao reproduzi em dev nem em producao; a correcao (assinatura) e valida nos dois casos.

## Fixes

Ver *What Changed*. Todos em `BlockLockManager.java`, exceto o item 1, a chave de lang e o
comentario do cliente.

## Remaining Issues

1. **A porta ainda anima por milissegundos ao clicar** (predicao do cliente). O cliente
   devolve `PASS` para o pacote chegar ao servidor, entao ele aplica o `use` localmente e
   o servidor corrige um tick depois. **Decisao do usuario: nao arriscar.** Se um dia for
   feito, o caminho e o plano da secao *Decisions*.
2. **Cama nao entra no conjunto.** Cama usa `BED_PART`, nao `DOUBLE_BLOCK_HALF`, e nao
   passa em `isInteractable`, entao nunca chega a ser trancada. Se alguem trancar cama no
   futuro, isso precisa de uma linha a mais em `blockParts`.
3. **Nenhuma correcao foi testada em jogo.** Ver *Next Steps*.

## Lessons / Memory

Gravados em `agent/memory/project-memory.md`, secao 30/09/2026. Os que mais importam
para quem mexer neste codigo:

- `HALF` nao e "segunda metade"; `DOUBLE_BLOCK_HALF` e, e so tem `UPPER`/`LOWER`.
- Nao use `PlayerBlockBreakEvents.AFTER` para limpar estado.
- Literal de string nao sobrevive ao remapeamento; compare assinatura.

## Next Steps

Roteiro de teste, na ordem, porque cada passo depende do anterior:

1. `runClient`, entrar num mundo, `/rpg block_lock`, clicar numa **porta na metade de
   baixo**. Resposta esperada: `Block locked. Players can no longer use it.`
2. Com um segundo jogador (ou trocando de conta), clicar na **metade de cima** da mesma
   porta, de longe e de baixo. Esperado: `Block Locked`, e a porta **nao abre**.
3. Jogador tenta **quebrar** a porta. Esperado: `This block is locked. Only the Master can break it.`
4. Mestre **quebra** a porta, **coloca outra** na mesma posicao, jogador clica. Esperado:
   a nova porta abre normalmente (a tranca foi removida junto com o bloco).
5. Mestre trava um **bau**, jogador clica. Esperado: `Block Locked` (este caminho ja
   funcionava antes desta rodada; serve de controle).
6. Conferir que **nao** ha `[Lock]`/`[Unlock]` no menu ASCII.
7. Reiniciar o mundo e repetir o passo 1: a tranca tem de continuar.
