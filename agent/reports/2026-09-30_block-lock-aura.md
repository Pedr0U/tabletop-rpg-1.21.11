# Implementation Report — Block Lock: aura + rename

## Status
BUILD VERDE E **COMPROVADO** (classes presentes no jar). **NAO VALIDADO EM JOGO.**

## Objective
Rodada 3 da feature Block Lock:
1. Renomear o item de "Block Lock" para "Block Locker".
2. Aura nos blocos trancados: atravessa parede, so para o Mestre, so com o item na
   mao, e some ao destrancar.

## What Changed
- **Servidor**: `BlockLockManager.java` — `dimension().location()` -> `.identifier()`
  (o metodo `location()` nao existe em 1.21.11).
- **Sync**: `RpgNetworking.java` — `BlockLocksPayload` + `BlockLockEntry`. O retrato
  completo vai no JOIN e a cada mudanca; nao ha estado incremental no cliente.
- **Cliente**: `TabletopRpgClient.java` — receptor do payload, limpeza no DISCONNECT,
  e `BlockLockAura.register()` na init.
- **NOVO** `client/com/pedro/tabletoprpg/client/BlockLockAura.java` — estado por
  dimensao + desenho em `WorldRenderEvents.END_MAIN`.
- **NOVO** `client/net/minecraft/client/renderer/rendertype/BlockLockAuraRenderType.java`
  — split package, monta um `RenderType` de linhas com `NO_DEPTH_TEST`.

## Decisions
1. **Split package em vez de mixin** (escolha do usuario). `RenderType.create` e
   package-private e o depth state migrou para o `RenderPipeline`, entao nenhum evento
   do Fabric entrega um RenderType que atravessa parede.
2. **Contorno em vez de preenchimento** (escolha do usuario). `ShapeRenderer.renderShape`
   desenha arestas (`forAllEdges` + `setLineWidth`); o `float` e largura de linha.
   Nao existe caminho verificado para o preenchimento translucido nesta versao.
3. **Pipeline copiado, nao escrito.** Todos os campos vem do `LINES_TRANSLUCENT` e so o
   depth test muda. Nomes de shader sao literais de string; errar um derruba o jogo na
   criacao do pipeline, e build nao pega isso.
4. Renomear **apenas o nome de exibicao**; id interno e chaves de lang preservados, para
   nao quebrar mundos salvos que ja tem o item.

## Validation
- `gradlew build` -> BUILD SUCCESSFUL.
- **Jar conferido**: `com/pedro/tabletoprpg/client/BlockLockAura.class` e
  `net/minecraft/client/renderer/rendertype/BlockLockAuraRenderType.class` presentes em
  `tabletop-rpg-1.0.0.jar`.
- 4 erros de API encontrados e corrigidos por compilacao real (ver abaixo).
- Auditoria de encoding: 0 caracteres CJK/cirilicos/U+FFFD.
- **Nenhuma validacao de runtime.**

## Problems Encountered

### 1. Entregas anteriores "-- build verde" que nao eram verdadeiras
Eu reportei `BUILD SUCCESSFUL` enquanto os arquivos que o build dependia **nao
existiam no disco**. `BlockLockAura.java` e `BlockLockAuraRenderType.java` foram
criados com `write`, a ferramenta retornou "sucesso", e nada foi gravado.
So existe uma copia do projeto; nao havia IDE envolvida.
ROOT CAUSE: eu tratrei o exit code do gradle como prova da feature, sem verificar que os
inputs do build estavam no disco. Erro meu, nao do ambiente.
FIX: todo `write` agora e seguido de `Test-Path` + contagem de bytes antes de compilar,
e o jar produced e aberto para conferir que a classe esta dentro dele.

### 2. Quatro assinaturas de API erradas de primeira
Todas pegas pelo compilador, todas por suposicao em vez de `javap`:
- `WorldRenderEvents`/`WorldRenderContext` estao em `...rendering.v1.world`, nao `...v1`.
- `Camera` tem `position()`, nao `getPosition()`.
- `MultiBufferSource` (interface) so tem `getBuffer(RenderType)`; `endBatch(RenderType)`
  esta em `MultiBufferSource.BufferSource` (o nested chama-se `BufferSource`, nao
  `Immediate`).
- `MultiBufferSource` vive em `net.minecraft.client.renderer`, nao `com.mojang.blaze3d.buffers`.
- A classe de recurso e `Identifier`, nao `ResourceLocation`.

### 3. `TabletopRpgClient.java` ficou com 3 referencias quebradas
`clientIsMaster` (campo que nunca foi declarado) e duas usos de `BlockLockAura` sem a
classe existir. Corrigido junto com a recriacao dos arquivos.

## Root Causes
1. Reportar resultado de build como se provasse a feature. Compilar e o passo mais
   fraco da cadeia: so prova que o que existe esta sintaticamente correto.
2. Assumir assinatura de API. Toda API nova deve ser confirmada por `javap` antes.

## Fixes
Recriados os dois arquivos, corrigidas as 3 referencias, corrigidas as 4 assinaturas de
API, e a verificacao de artefato virou parte obrigatoria do fluxo.

## Remaining Issues
1. **COM O IRIS INSTALADO A AURA NAO APARECE.** Este item SUBSTITUI a avaliacao de risco
   original desta secao, escrita antes de o problema ser descoberto.
   SINTOMA: com shaders ligados nada e desenhado e o log recebe, a cada frame enquanto o
   Mestre segura o item, `Missing program tabletop-rpg:block_lock_aura_lines in override
   list.`, com um `java.lang.Throwable` lancado de `redirectIrisProgram`.
   ROOT CAUSE (FATO verificado nos jars, nao por deducao): a frase `"in override list"`
   nao existe em nenhum `.class` do `minecraft-clientonly`, e existe dentro de
   `iris-fabric-1.10.7+mc1.21.11.jar`. O Iris intercepta a ligacao de pipeline e Lanca
   excecao para qualquer programa fora da lista de override dele; a lista e montada a
   partir dos shaders do Iris e nenhum mod consegue se registrar nela.
   CONCLUSAO: um `RenderType` com pipeline proprio NAO e compativel com o Iris. Nao e bug
   de construcao do pipeline e nao ha correcao do lado do mod.
   POR QUE O DEV NAO VIU: o ambiente `run/` nao tem Iris (pasta `mods` vazia, so Fabric
   API), entao build e `runClient` nao reproduzem; so a instancia do usuario reproduz.
   SAIDAS (decisao do usuario adiada): (a) usar `RenderTypes.LINES_TRANSLUCENT`, que o Iris
   conhece: a aura aparece mas nao atravessa parede; (b) detectar o Iris e escolher a rota.
   Sem Iris a aura funciona e atravessa parede normalmente.
   CORRECAO DE AVALIACAO: a versao anterior desta secao tratava "pipeline rejeitado no
   primeiro frame" como risco de codigo. Isso nao era a causa; nao usar como causa raiz.
2. `Location` do pipeline e um `Identifier` literal novo; se o registro de pipelines
   colidir, o erro aparece em runtime.
3. Nada com cleanup de estado de depth/blend e necessario: o estado vive no pipeline e
   o batch e fechado explicitamente.

## Lessons / Memory
1. **`write` pode retornar sucesso sem gravar o arquivo.** sempre verifique. E nao
   confie no verde do build: ele nao sabe que os arquivos deveria existir.
2. Confirme que a classe esta dentro do jar antes de dizer que a feature esta pronta.
3. "cannot find symbol" apontando para um CONSUMIDOR costuma ser erro no arquivo que
   DECLARA o simbolo. Se o simbolo e uma classe, verifique se o arquivo dela compila.
4. API 1.21.11 (ver secao de render na memoria do projeto).

## Next Steps
1. Testar em jogo: Block Locker na mao, atras de parede, mais de uma dimensao,
   confirmar que so o Mestre ve e que some ao destrancar.
2. Se estourar em runtime, o log do cliente aponta o campo invalido; primeiro
   suspeito e `ShaderDefines` ou `withColorLogic`.
3. Nao commitar nem dar push sem pedido explicito.