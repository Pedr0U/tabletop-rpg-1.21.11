# Relatório de Implementação

## Status
**CONCLUÍDO.** Integração com o mod de shaders Iris implementada e validada em jogo pelo usuário.
Dois commits enviados: `6e82323` (implementação) e `7fe3f26` (correção da memória).

## Objetivo
Fazer a aura dos blocos trancados (visível só para o Mestre, que atravessa parede) funcionar
também quando há shaders do Iris ligados. Era a primeira integração do projeto com outro mod.

## Escopo
1. Descobrir por que a aura não aparecia com shaders ligados.
2. Diagnosticar com evidência, não com hipótese.
3. Corrigir sem perder o requisito original (atravessar parede).
4. Manter o Iris opcional — o mod não pode depender dele.

## O que mudou

### `src/client/java/com/pedro/tabletoprpg/client/IrisAuraSupport.java` (novo, 93 linhas)
Integração opcional. Se `FabricLoader.isModLoaded("iris")`, chama por reflexão
`IrisPipelines.copyPipeline(LINES_TRANSLUCENT.pipeline(), pipelineThroughWalls())` — a API do
próprio Iris para copiar a atribuição de shader de um pipeline para outro.

### `src/client/java/net/minecraft/client/rendertype/BlockLockAuraRenderType.java`
Expõe `pipelineThroughWalls()`, que devolve o `RenderPipeline` cru. O Iris precisa da identidade
do pipeline, não só do `RenderType`.

### `src/client/java/com/pedro/tabletoprpg/client/BlockLockAura.java`
Chama `ensurePipelineRegistered()` antes do desenho. Instrumentação de diagnóstico removida
(`heartbeat`, `recordDraw`, 8 campos de contagem, imports de `GL11`/`slf4j`).

## Causa raiz (a que importava)

**FACT.** A aura usa um `RenderPipeline` próprio com `NO_DEPTH_TEST`. Sem shader, esse pipeline é
válido e funciona. Com o Iris ligado, o Iris assume a ligação do shader e **não desenha** um
pipeline que ele não conhece. A aura sumia **sem erro de GL e sem exceção**, com o batch fechado
normalmente.

O aviso `Missing program ... in override list` era **sintoma**, nunca a causa.

### O que eu errei antes, e como corrigi

A primeira análiseYTES registrou que "o Iris lança exceção para qualquer programa fora da lista de
override" e que "um `RenderType` com pipeline próprio não é compatível com o Iris". **Ambas estão
erradas.**

`javap` de `MixinShaderManager_Overrides.redirectIrisProgram` mostra que **não há `athrow`**: o
`new Throwable()` serve só para a stack trace do log. O nível é decidido pelo namespace — `fatal`
para `minecraft`, `error` para namespace de mod como o nosso. E o guarda `missingShaders.add()`
faz o aviso sair **uma vez por pipeline**, não a cada frame.

A frase errada chegou a ser escrita no catálogo, no relatório e na memória. Corrigi nos três, e na
memória marquei explicitamente que ela **nunca foi verdade**, para não voltar a ser usada como
causa raiz numa sessão futura.

### Como o diagnóstico foi fechado

**Bisect de um frame.** A mesma geometria desenhada duas vezes no mesmo frame, com o pipeline do
mod em âmbar e `RenderTypes.linesTranslucent()` do jogo em verde. Resultado: **só o verde
apareceu.**

Isso matou de uma vez as hipóteses de alvo de render, timing do `END_MAIN` e geometria/câmera —
todas compartilhariam o mesmo frame, pose e coordenadas — e deixou o pipeline do mod como único
suspeito. Nenhuma leitura de código teria separado essas hipóteses.

## Validação

| Verificação | Resultado |
|---|---|
| `gradlew build` | BUILD SUCCESSFUL |
| Classes no jar (`build/libs/tabletop-rpg-1.0.0.jar`) | as 3 presentes |
| `build.gradle` com referência ao Iris | 0 — segue opcional |
| Auria de diagnóstico removida | 12 marcadores, todos 0 |
| `copyPipeline` e registro no lugar | 4 marcadores, todos OK |
| Encoding nos 5 arquivos | 0 caracteres suspeitos |
| **Aura com shaders (Complementary Unbound r5.5.1)** | **validado pelo usuário** |
| **Aura sem shaders** | **validado pelo usuário** |

## Problemas encontrados

**Um classpath errado no meu script de verificação.** Procurei
`net/minecraft/client/rendertype/BlockLockAuraRenderType.class` e recebi AUSENTE, alarmando que a
classe mais crítica não estava no artefato. O caminho real é
`net/minecraft/client/**renderer**/rendertype/`. Ela estava lá. Erro meu, não do build.

**Um edit meu que renomeou o método errado**, trocando o construtor do `RenderType` pelo getter
do `RenderPipeline`, causando duplicação e erro de tipo. O compilador pegou.

## Decisões

- **Origem da cópia é `LINES_TRANSLUCENT`**, não qualquer pipeline de linhas: é a base exata do
  nosso (mesmo vertex format, mesmo blend, mesma linha de shader), então o Iris atribui o shader
  de linhas sem divergir do que o pipeline espera.
- **Reflexão, nunca dependência de compilação.** `Class.forName` + `getMethod`. Transformar o Iris
  em dependência faria o mod não carregar em quem não o tem — o build local passaria, porque o jar
  está no cache do Gradle, e só o usuário descobriria.
- **Registro a cada frame em que a aura desenha**, e não uma vez na carga. O mapa de shaders do
  Iris é reconstruído a cada troca de shaderpack; `copyPipeline` é idempotente e barato (duas
  operações de mapa). Reaplicar no caminho do desenho cobre recarga sem custo quando ninguém está
  olhando a aura.
- **O atravessa-parede foi preservado** porque profundidade vive no pipeline e shader é o
  programa. Trocar o shader não mexe no `NO_DEPTH_TEST`. Este era o requisito original e a
  correção não o sacrificou.

## O que ficou em aberto

1. **Um shaderpack que não mapeie `LINES_TRANSLUCENT` no mapa do Iris** faria o `copyPipeline` ser
   um no-op silencioso e a aura não apareceria nele. Com o Complementary Unbound funciona.
   Não testado com outros packs.
2. `ShaderDefines` do pipeline de origem não são copiados. Sem efeito observado.
3. Só testado com o Iris 1.10.7 + Sodium 0.8.7. Não testado em outra combinação.

## Lições

**Bisect de um frame para "algo não aparece".** Quando existem duas implementações concorrentes,
desenhe as duas no mesmo frame com cores diferentes. Custa um build e vale mais que qualquer
leitura de código. Leia o resultado como tabela de decisão antes de deduzir causa.

**Causa raiz em documento vivo exige evidência, e a errada fica marcada.** Não apaguei a causa
errada: marquei como errada. É o que impediu que ela voltasse a ser tratada como verdade.

**Mod opcional é reflexão, nunca dependência de compilação.** O build local passa com o jar no
cache; só o usuário sem o mod quebraria.

**Build verde não prova que a feature existe.** Verifiquei as classes dentro do jar. E quando essa
verificação acusou AUSENTE, a culpa foi do meu caminho errado — mas eu só sabia que o caminho
estava errado porque conferi o conteúdo em vez de aceitar o resultado.

**`write` e `edit` podem reportar sucesso sem gravar.** Verifiquei cada remoção por `grep`.

## Checkpoint

`checkpoint-20260930-2243-antes-do-iris` → `3aeb09b`, criado no início desta fase e mantido.

## Próximos passos

- Testar a aura com um segundo shaderpack para fechar o item 1 acima.
- Considerar remover a tag de checkpoint quando a integração estiver consolidada.