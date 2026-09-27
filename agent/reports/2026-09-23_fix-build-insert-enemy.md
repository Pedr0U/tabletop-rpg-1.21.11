# Implementation Report

## Status
CONCLUÍDO - Build do projeto corrigido e validado (BUILD SUCCESSFUL). O mod agora compila e gera o jar `tabletop-rpg-1.0.0.jar` pronto para teste.

## Objective
Corrigir os problemas que impediam a compilação do mod TableTop RPG (Minecraft Fabric 1.21.11), herdados de uma implementação anterior de outra IA, para que o usuário possa testar o mod. Fases futuras (2/4/5 e demais funcionalidades) ficaram para depois, conforme solicitado.

## Scope / Subtasks
1. Corrigir `MasterCommands.java` (comando `/rpg insert enemy` com API incorreta do 1.21.11 + imports duplicados)
2. Corrigir chamadas de `translate`/`scale` nas telas do cliente
3. Remover código morto (mixins placeholder não registrados)
4. Validar com `gradlew build` até BUILD SUCCESSFUL
5. Gerar relatório e memória durável

## What Changed

### 1. MasterCommands.java (src/main/java/com/pedro/tabletoprpg/MasterCommands.java)
- **Imports**: removido import duplicado de `PlayerList`; removidos `LivingEntity` e `Level` (não usados); adicionados `BoolArgumentType`, `BuiltInRegistries`, `Identifier`, `ServerLevel`, `Entity`, `EntitySpawnReason`, `Mob`.
- **Registro do comando**: `/rpg insert enemy <type> <cam_perm>` agora registra os DOIS argumentos (`type` como `word()`, `cam_perm` como `BoolArgumentType.bool()`). Antes só existia `type` (greedyString), mas o código lia `cam_perm` em runtime -> IllegalArgumentException.
- **insertEnemy reescrito** com a API real do 1.21.11 (verificada via javap no jar do Loom):
  - Lookup de tipo via `BuiltInRegistries.ENTITY_TYPE` + `Identifier.tryParse` (aceita "zombie" ou "minecraft:zombie"). Antes: `getEntityType()` retornava `null` sempre.
  - Spawn via `entityType.create(serverLevel, EntitySpawnReason.COMMAND)` + `setPos` + `serverLevel.addFreshEntity(entity)`. Antes: `level.addFreshEntity(entityType, x, y, z)` (overload inexistente -> erro de compilação).
  - IA desativada via `mob.setNoAi(true)` e persistência via `mob.setPersistenceRequired()` (ambos em `Mob`). Antes: `entity.setAI(false)` e `entity.setPersistenceRequired(false)` (métodos inexistentes em `LivingEntity` -> erros de compilação).
  - Posição de spawn: `master.getY()` (pés) em vez de `getEyeHeight() + 1.0` (que deixava a entidade flutuando).
- **Indentação** de `setSessionName` corrigida (quebrada pela IA anterior).

### 2. RpgMenuScreen.java (src/client/java/com/pedro/tabletoprpg/client/RpgMenuScreen.java)
- Apenas comentários atualizados. As chamadas `translate(panelX, panelY)` e `scale(scale, scale)` (2 args) estavam CORRETAS para o 1.21.11: `GuiGraphics.pose()` retorna `org.joml.Matrix3x2fStack`, que tem `translate(float, float)` e `scale(float, float)`. (Uma tentativa intermediária de "corrigir" para 3 args foi revertida após verificação da API.)

### 3. Arquivos removidos (código morto)
- `src/main/java/com/pedro/tabletoprpg/mixin/ExampleMixin.java` (não registrado no `tabletop-rpg.mixins.json`)
- `src/client/java/com/pedro/tabletoprpg/client/mixin/ExampleClientMixin.java` (não registrado no `tabletop-rpg.client.mixins.json`)

### 4. Não alterado (pré-existente, da IA anterior)
- `src/client/resources/tabletop-rpg.client.mixins.json` (remoção de ExampleClientMixin da lista)
- Logs `build.log.txt`, `build_output.txt`, `output.txt` (untracked, desatualizados)

## Files Changed
| Arquivo | Ação |
|---|---|
| `src/main/java/com/pedro/tabletoprpg/MasterCommands.java` | Corrigido (imports, comando, insertEnemy) |
| `src/client/java/com/pedro/tabletoprpg/client/RpgMenuScreen.java` | Comentários atualizados |
| `src/main/java/com/pedro/tabletoprpg/mixin/ExampleMixin.java` | Removido |
| `src/client/java/com/pedro/tabletoprpg/client/mixin/ExampleClientMixin.java` | Removido |

## Decisions
1. **Manter o comando `/rpg insert enemy`** (em vez de removê-lo) e implementá-lo corretamente com a API do 1.21.11 — o usuário quer testar e o comando faz parte da Fase 2.
2. **`cam_perm` como `BoolArgumentType`** (autocomplete true/false nativo do Brigadier) em vez de string.
3. **`setPersistenceRequired()`** (sem argumento, API 1.21.11) para impedir despawn natural do inimigo summonado.
4. **Spawn no nível dos pés** do mestre (`getY()`) para a entidade não nascer flutuando.
5. **Remover os mixins placeholder** — não registrados, sem função, e fonte de confusão (a IA anterior acreditou que `ExampleClientMixin` "não existia" quando o arquivo existia).

## Validation
- `.\gradlew build --no-daemon --console=plain` -> **BUILD SUCCESSFUL in 10s** (exit 0)
- Jar gerado: `build/libs/tabletop-rpg-1.0.0.jar` (279.240 bytes) + sources jar
- Conteúdo do jar verificado: contém `MasterCommands`, `RpgMenuScreen`, `DiceRollScreen`, `CameraMixin`, `LocalPlayerMixin`, `ServerGamePacketListenerImplMixin` e os 2 mixins JSON; NÃO contém classes dos mixins removidos.
- API do Minecraft 1.21.11 verificada com `javap` no jar do Loom (não por suposição):
  - `EntityType.create(Level, EntitySpawnReason)` existe; `create(Level)` NÃO existe
  - `Mob.setNoAi(boolean)` e `Mob.setPersistenceRequired()` existem; `LivingEntity.setAI`/`setPersistenceRequired(boolean)` NÃO existem
  - `LevelWriter.addFreshEntity(Entity)` só aceita Entity
  - `GuiGraphics.pose()` retorna `org.joml.Matrix3x2fStack` com `translate(float,float)`/`scale(float,float)`

## Problems Encountered
1. **Build falhava com 3 erros** em `MasterCommands.java` (addFreshEntity com 4 args, setAI, setPersistenceRequired(boolean)) — código da IA anterior que tentou usar API de versões antigas do Minecraft.
2. **Tentativa intermediária errada**: ao corrigir as telas, troquei `translate/scale` de 2 para 3 argumentos, gerando novos erros (`int cannot be converted to Matrix3x2f`). A verificação com javap revelou que o código ORIGINAL de 2 args estava correto para o 1.21.11 (Matrix3x2fStack). Revertido.
3. **Logs de build antigos na raiz** (`output.txt`, `build.log.txt`) referenciam caminho `Projeto Mod TableTop` e código que não existe mais (`CameraTargetPayload`) — não refletem o estado atual e devem ser ignorados/removidos.

## Root Causes
- FACT: A IA anterior escreveu código com API de versões antigas do Minecraft (1.18/1.20) e envolveu chamadas inexistentes em try/catch, acreditando que isso evitaria erros. try/catch NÃO protege contra erros de compilação.
- FACT: O comando foi registrado com assinatura diferente da documentada (`<type>` vs `<type> <cam_perm>`), causando falha em runtime.
- FACT: `getEntityType()` era um placeholder que sempre retornava null — o comando nunca funcionaria mesmo compilando.
- FACT: Nenhum build foi executado após as mudanças da IA anterior; os logs na raiz eram de código antigo.

## Fixes
- Reescrito `insertEnemy` com API verificada do 1.21.11 (javap no jar do Loom).
- Registro do comando alinhado com a documentação (`<type> <cam_perm>`).
- Removido placeholder `getEntityType`; lookup real via `BuiltInRegistries.ENTITY_TYPE`.
- Removidos mixins placeholder e import duplicado.
- Build validado: SUCCESSFUL.

## Remaining Issues
- **Teste em runtime pendente**: o build compila, mas o comportamento em jogo (câmera, travamento, comando insert enemy) não foi testado com o Minecraft rodando. O usuário deve testar com `gradlew runClient` ou instalando o jar.
- **Fases 2 (completa), 4 e 5** ainda não implementadas (fora do escopo desta tarefa).
- **Logs antigos na raiz** (`output.txt`, `build_output.txt`, `build.log.txt`) são lixo de builds anteriores — candidatos a remoção (não removidos por serem pré-existentes).
- **Riscos de runtime dos mixins** (required:true + defaultRequire 1): se algum target divergir em runtime, o jogo crasha na inicialização. Só o teste em jogo confirma.

## Lessons / Memory
- **Lição 1**: Em Minecraft 1.21.11, `GuiGraphics.pose()` retorna `org.joml.Matrix3x2fStack` (não `PoseStack`): `translate(float,float)` e `scale(float,float)` com 2 args.
- **Lição 2**: API de entidades 1.21.11: `EntityType.create(Level, EntitySpawnReason)`, `Mob.setNoAi(boolean)`, `Mob.setPersistenceRequired()` (sem arg), `LevelWriter.addFreshEntity(Entity)`.
- **Lição 3**: try/catch não protege contra erros de compilação — sempre validar com build após mudanças.
- **Lição 4**: Sempre verificar a API real com javap no jar do Loom (`~/.gradle/caches/fabric-loom/minecraftMaven/...`) antes de escrever código contra classes do Minecraft.
- **Lição 5**: Logs de build antigos na raiz do projeto podem referenciar código que não existe mais — não confiar neles como estado atual.

## Next Steps
1. Usuário testa o mod (runClient ou jar na pasta mods).
2. Se o teste em jogo revelar problemas de runtime, diagnosticar com evidências.
3. Implementar fases pendentes (3: horário; 2: entidades; 4: combate; 5: portas/baús) em subtarefas atômicas com build verde a cada passo.
4. Considerar remover os logs antigos da raiz e adicionar `.gitignore` para eles.