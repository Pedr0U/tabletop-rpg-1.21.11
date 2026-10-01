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
1. ~~**COM O IRIS INSTALADO A AURA NAO APARECE.**~~ **RESOLVIDO em 30/09/2026.**
   Este item substitui a avaliacao de risco original desta secao, escrita antes de o
   problema ser descoberto.
   SINTOMA: com shaders ligados nada e desenhado. O log recebia
   `Missing program tabletop-rpg:block_lock_aura_lines in override list.` junto de um
   `java.lang.Throwable` cujo stack trace aponta para `redirectIrisProgram`.
   CAUSA RAIZ **ERRADA** (registrada aqui de proposito, ver abaixo): a primeira versao
   deste relatorio afirmava que o Iris "lanca excecao para qualquer programa fora da lista
   de override" e que por isso a aura nao desenhava. **Isso e FALSO.**
   FATO (javap de `MixinShaderManager_Overrides`, 30/09/2026) - o metodo e:
   ```java
   if (pipeline == COMPOSITE_PIPELINE) return;
   if (pipeline == field_64220 || pipeline == field_64221) return;
   WorldRenderingPipeline w = Iris.getPipelineManager().getPipelineNullable();
   if (w instanceof IrisRenderingPipeline iris && iris.shouldOverrideShaders()
           && !ImmediateState.bypass) {
       ShaderInstance s = override(iris, pipeline);   // IrisPipelines + ShaderMap
       if (s != null) { cir.setReturnValue(new Program(pipeline, s)); return; }
       if (missingShaders.add(pipeline)) {              // <- so na primeira vez
           if (namespace.equals("minecraft")) Iris.logger.fatal(msg, new Throwable());
           else                              Iris.logger.error(msg, new Throwable());
       }
   }
   return;   // <- vanilla continua normalmente
   ```
   Tres consequencias que a analise anterior errou: (a) **nao ha `athrow`**, o
   `Throwable` serve so para a stack trace; (b) o log sai no nivel `error`, nao `fatal`,
   porque o namespace e `tabletop-rpg`; (c) o guarda `missingShaders.add` faz o log
   sair **uma vez por pipeline**, nao a cada frame.
   CAUSA RAIZ REAL (FACT, confirmado por bisect em jogo): o Iris **nao tinha shader
   atribuido** ao pipeline do mod, e por isso nao desenhava as linhas. Sem shader o pipeline
   do mod e valido e funciona; com shader ligado, o Iris assume a ligacao do shader e um
   pipeline desconhecido simplesmente nao sai na tela — sem erro de GL, sem excecao, batch
   fechado normalmente. O log `Missing program` era o **sintoma** disso, nunca a causa.
   COMO FOI ISOLADO (bisect, 30/09/2026): a mesma geometria foi desenhada duas vezes no
   mesmo frame, com o pipeline do mod (ambar) e com `RenderTypes.linesTranslucent()` do
   jogo (verde). Resultado: **so o verde apareceu**. Isso matou de uma vez as hipoteses de
   alvo de render, timing do END_MAIN e geometria/camara — todas compartilhariam o mesmo
   frame e as mesmas coordenadas — e deixou o pipeline do mod como unico suspeito.
   CORRECAO APLICADA: `IrisAuraSupport.ensurePipelineRegistered()` chama
   `IrisPipelines.copyPipeline(LINES_TRANSLUCENT.pipeline(), pipelineThroughWalls())` por
   reflexao, antes do desenho, so quando `FabricLoader.isModLoaded("iris")`. O atravessa-
   parede e preservado porque profundidade vive no pipeline e o shader e o programa.
   **VALIDADO EM JOGO pelo usuario em 30/09/2026**, com o Complementary Unbound ligado.
2. `ShaderDefines` do pipeline de origem nao sao copiados. Sem efeito observado ate aqui.
3. O log de diagnostico (`diagnose`, `heartbeat`, `recordDraw`) foi removido em 30/09/2026,
   depois de servir ao seu proposito. O padrao ficou na memoria do projeto.
   POR QUE O DEV NAO VIU: o ambiente `run/` nao tem Iris (pasta `mods` vazia, so Fabric
   API), entao build e `runClient` nao reproduzem; so a instancia do usuario reproduz.
   DADOS NOVOS DO DIA: (a) `RenderPipelines` tem 15 pipelines com
   `DepthTestFunction.NO_DEPTH_TEST` e **nenhum** deles e de LINHAS - sao QUADS texturizados
   de `OUTLINE_SNIPPET` (o contorno de selecao). Portanto **nao existe rota vanilla para
   "linhas + atravessa parede"** e o pipeline proprio continua necessario.
   (b) A superficie de integracao do Iris e `IrisApi.assignPipeline(RenderPipeline,
   IrisProgram)` (API publica, `net.irisshaders.iris.api.v0`) mais
   `IrisPipelines.copyPipeline(to, from)` (interna, copia a associacao de um pipeline para
   outro). Nenhuma usada ainda. RISCO CONHECIDO: ambas escrevem em um mapa reconstruido a
   cada carga de shaderpack, entao um registro feito uma vez pode nao sobreviver a uma troca
   de pack em jogo.
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