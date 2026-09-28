# Da identidade por nome a identidade por id nas pericias

**Data:** 28/09/2026
**Escopo:** 6 arquivos de codigo + 1 de teste + catalogo. Migracao de dado existente.

## Objetivo

A pericia era identificada pelo **NOME** em toda parte. Renomear uma pericia
fazia o servidor nao acha-la: o **valor zerava** e o **atributo escolhido se
perdia**. O usuario reproduziu em jogo, em mais de uma ficha.

A solucao era dar a cada pericia um **id proprio**, espelhando o padrao que os
**atributos ja usavam** (`attr_N`).

## A armadilha que quase destruiu tudo

O 1.21.11 nao tem, hoje, a garantia de que `sanitizePericias` deduplicava por
**nome**. Se a deduplicacao passasse a ser por **id** sem trocar a ordem, uma
ficha antiga - 18 pericias, todas sem id, todas com id `""` - **colapsaria
para 1 pericia**. Sem erro, sem warning, sem log.

E a ordem importa porque o `Codec` do DataFixerUpper **omite** campo igual ao
default (fonte conferida: `Codec.java:303-308` do `datafixerupper-9.0.19`).

Por isso a regra fixada no briefing, e repetida aqui porque e o coracao da
mudanca:

> **Preencher o id ANTES de deduplicar, e NUNCA deduplicar por id vazio.**

O primeiro `seen.add` vence (a **primeira** ocorrencia e preservada, a segunda
descarta e recebe id novo) - a direcao correta. `MAX_PERICIAS` conta itens ja
aceitos, nao o indice do crud, entao nao trunca antes do preenchimento.

A revisao independente rastreou o caminho completo do NBT antigo ate a gravacao
e **nao achou nenhum caminho em que as 18 colapsem para 1**. A garantia e
estrutural (ordem das etapas), nao sorte de teste.

## O mecanismo real da migracao - e o que o codigo dizia (errado)

A revisao encontrou o achado mais importante, e ele e **documentacao que mente**:

`align` tem um fallback que casa a pericia por nome quando a ficha nao tem id
(`sheetWithoutIds` + `periciaByNameOrLegacy`). A analise mostrou que:

- `hasPericiaIds()` so devolve `false` quando a lista de pericias esta **vazia**,
  porque o construtor compacto preenche o id de **toda** entrada que retida.
- Nao existe estado "misturado" (alguns com id, outros sem): o `Codec` e
  todo-optional e o sanitize preenche todos.
- Com lista vazia, `periciaByNameOrLegacy` itera lista vazia e devolve `null`.

**O ramo e 100% inalcançavel.** O `LEGACY_PERICIA_NAMES` instalado em
27/09/2026 virou codigo inerte.

**O que realmente preserva os valores e o preenchimento POSICIONAL**: o id e
preenchido na ordem da lista, e o NBT antigo foi gravado na ordem do modelo
(porque o `align` antigo percorria a lista do modelo). Ordem do save == ordem
do modelo, entao casar por id (== posicao) reproduz exatamente o que casar por
nome reproduzia - inclusive para as entradas com nome antigo em portugues
("Acrobacia" na posicao 1 continua indo para `pericia_1`).

O Javadoc foi reescrito para descrever isso. **Nao** fiz um Javadoc que promete
mais do que o codigo garante, porque foi exatamente um Javadoc assim que causou
o defeito de reciclagem de id de atributo que encontramos no caminho.

## O defeito de reciclagem (achado no caminho, nao era da migration)

`nextFreshAttributeIndex` prometia no Javadoc subir "ate um id que ainda nao foi
usado **nesta sessao**", mas so consultava a **lista atual**. Resultado: o Mestre
remove `attr_1`, adiciona um atributo, e o novo nasce com `attr_1` e **herda o
valor do removido em todas as fichas do mundo**. O log da sessao de 28/09 mostra
o Mestre removendo atributos, entao isso pode ter acontecido.

**Nao foi possivel corrigir**: `SessionManager` nao expoe iteracao sobre as
fichas em memoria, e criar essa API seria arquitetura nova (fora do escopo). O
que foi feito: **corrigir o Javadoc para dizer o que e verdade**. A garantia
real e "nunca colide com um atributo que o modelo ainda tem". O defeito
estrutural continua, agora descrito sem mentir, com o contorno indicado
(renomear em vez de remover e recriar).

## Limitacoes residuais, documentadas

| Caso | Efeito |
|---|---|
| Remover pericia, jogador offline, **sem** adicionar | **Seguro.** Os sobreviventes mantem id e ordem. So o valor da removida se perde, que e o desejado. |
| Remover pericia, jogador offline, **depois adicionar** | **Perigoso.** `addPericia` pega o menor N livre, que pode ser o da removida, e a nova **herda o valor** da removida em todas as fichas. |
| Atributo: remover e adicionar | Mesmo defeito, mesma limitacao. |
| Contorno | **Renomear** em vez de remover e recriar: o rename preserva o id. |

## Arquivos

| Arquivo | Mudanca |
|---|---|
| `SheetModel.java` | `PericiaDef(String id, String name, String attributeId)`; `freshPericiaId`; `removePericia` e `withPericiaText` por id; `periciaById`; `align` por id; `sanitizePericias` com backfill; `removeAttribute` preserva id; Javadocs honestos |
| `SheetData.java` | `Pericia(String id, String name, int value, String attributeId)`; `PERICIA_CODEC` com `optionalFieldOf("id","")`; `freshPericiaId`; `periciaById`; `hasPericiaIds`; `mutatePericia`; `withPericiaValue`/`withPericiaAttribute` |
| `RpgNetworking.java` | `SheetPericiaPayload.pericia` -> `periciaId`; 5 campos, mesma ordem, mesmo codec |
| `StatusScreen.java` | `pendingPericiaValue` e `pendingPericiaAttribute` chaveados por id |
| `SheetEditorScreen.java` | `withPericiaText`/`removePericia` por id; `pendingNames` segue por posicao |
| `SheetModelCodecTest.java` | 8 -> 17 testes |

**Nao mudaram:** `AttributePickerScreen` (recebe o nome so como rotulo e devolve o
id do **atributo**; a memoria do projeto dizia que mudaria, e estava errado),
`SheetModelStore`, `SessionManager`, `PlayerSheetPersistenceMixin`,
`TabletopRpgClient`, `MasterCommands` (o `/rpg roll` continua por nome, porque
e interface humana).

## Decisoes do usuario

1. **Id por contador** (`pericia_1`, `pericia_2`), espelhando `attr_N`. Gerado uma
   vez e congelado.
2. **Continuar barrando nome repetido.** O id resolveu o problema do rename; a UX
   nao mudou.
3. **Corrigir o defeito de reciclagem de atributo junto** - virou correcao de
   Javadoc, porque a correcao de logica exigiria API nova no `SessionManager`.
4. **Backup dos saves antes de executar.** 52 arquivos `.dat` (11 fichas + modelos
   de mundo) copiados para
   `%LOCALAPPDATA%\Temp\opencode\backup-saves-20260928-1010`, tamanho conferido
   byte a byte (45355 bytes, identico).

## O dado real em risco

**11 arquivos de ficha** em `New World` e `New World (1)`:
- 4 em formato atual, 1 delas com **10 pericias customizadas pelo Mestre** - e
  exatamente o caso que perdia dados a cada rename;
- 6 em formato **pre-migration** (o layout antigo de 20 slots);
- 1 em formato pre-migration com nomes em **portugues** ("Luta", "Acrobacia").

## Validacao

| Verificacao | Resultado |
|---|---|
| `gradlew build --no-daemon` | `BUILD SUCCESSFUL` |
| Testes | **17 testes, 0 falhas, 0 erros** (antes eram 8) |
| `scanEncoding` | `OK: 92 arquivo(s)`, 0 mojibake, 0 ideograma, 0 U+FFFD |
| Detector de catalogo | `OK: nenhuma divergencia mecanica` |
| Revisao independente | **0 bloqueadores**, 3 importantes, 4 nits |

Os 4 testes novos que fecham a migracao:
- `Pericias sem id nao colapsam` - o pior modo de falha possivel, travado.
- `NBT antigo sem id de pericia: os valores sobrevivem`.
- `NBT antigo com nomes de lixo: os valores sobrevivem por posicao, nao por
  nome` - trava o comportamento posicional, e impede que uma limpeza futura
  reintroduza o casamento por nome.
- `Ficha com id desconhecido: o valor e 0 e o align nao casa pelo nome` - fecha o
  achado do fallback inerte.

**Erro meu:** o teste `renamingTwice` falhou de primeira. A assercao comparava um
**nome** ("Arcana") com um **id** ("pericia_2") - tipos diferentes. O codigo
estava certo; o teste é que estava errado. Corrigido e o import faltante de
`assertNotEquals` adicionado.

**NAO validado em jogo:** nada. Nenhuma das 11 fichas foi carregada, nenhum
rename foi testado, nenhum mundo foi reiniciado. A prova da migracao e empirica:
precisa de um login em jogo de uma das 6 fichas pre-migration e conferir **cada**
pericia, nao so o total.

## Pendencias

- **Teste em jogo da migracao** (checklist na secao de validacao do validador).
- **Reinstalar o jar nos dois lados**: `Pericia` foi de 3 para 4 campos no
  `STREAM_CODEC`. Em singleplayer e o mesmo jar, entao nao ha risco pratico, mas
  em LAN com versoes misturadas a dessincronizacao e silenciosa.
- **Defeito pre-existente, achado pela revisao e nao desta migration:**
  `PlayerSheetPersistenceMixin` alinha com `SheetModelHolder.current()`, que ainda
  vale o **default** se a carga rodar antes do `SERVER_STARTED` (singleplayer). O
  `realignAllSheets` restaura lista e valores, mas **nao** o vinculo
  pericia->atributo, porque o id do atributo padrao tambem existe no modelo real.
  Se o mundo tem atributos customizados, vale testar: jogador com pericia
  apontando para atributo customizado, reiniciar o mundo, conferir o vinculo.
- **Id de atributo ainda recicla** (limitacao aceita, documentada).
- **Data de referencia do catalogo** continua apontando para 27/09/2026.
- **`agent/reports/2026-09-27_sheet-editor-item.md`** continua faltando.
- **Funcao de segurada e item** continuam sem teste em jogo do usuario.

## Aprendizado

Revisar a migracao **antes** de commitar pagou: os 3 "importantes" da revisao
eram documentacao que mentia, e um deles (o fallback inerte) e o tipo de coisa
que so se enxerga quando alguem tenta refazer o caminho de dados com a
perspectiva de "e se eu quisesse quebrar isso?". A pergunta "quando exatamente
esta condicao pode ser verdadeira?" e o que desenterra um ramo inalcancavel.

## Atualizacao: validacao em jogo (28/09/2026, apos o commit 9d3d154)

O usuario testou em jogo e confirmou:

- **Teste 2** (renomear pericia preserva valor e atributo): passou.
- **Teste 3** (trocar atributo pelo dropdown preserva o vinculo): passou.
- **Teste 4** (nome repetido recusado): passou.
- **Teste 1** (ficha pre-migration carrega com valores e atributos): o usuario
  disse que faria depois e, ao final, confirmou "deu tudo certo" - interpretado
  como validado junto.

**A migracao posicional esta validada empiricamente**, nao so por teste
unitario. A secao "NAO validado em jogo" acima fica superada por esta.

**Teste 5** (atributo customizado sobrevive ao reinicio do mundo) nao foi
mencionado pelo usuario - e opcional, so relevante em mundo com atributos
customizados. O defeito pre-existente do `PlayerSheetPersistenceMixin` (carga
antes do `SERVER_STARTED` em singleplayer) segue como pendencia documentada,
nao confirmado nem descartado.
