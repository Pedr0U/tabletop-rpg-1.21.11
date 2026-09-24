# Implementation Report

## Status
IN PROGRESS — 3 fixes aplicados e compilando (`gradlew build` OK). Teste em jogo do usuário realizado: aura funcionando, mas 3 pendências registradas (player preso na barreira, turno dos monstros, modo iniciativa). Próxima sessão: corrigir e implementar.

## Resultado do teste em jogo (feedback do usuário, 24/09/2026)
- **Aura**: funcionando bem (aparece e segue o relevo). ✅
- **Mob**: seleção/movimento funcionando como deveria. ✅
- **BUG — player preso na barreira da aura**: às vezes, ao encostar na barreira, o player fica completamente preso — não mexe câmera nem personagem, e nem `/tp` tira. Suspeita: flood de teleports (`connection.teleport` a cada tick enquanto segura W na borda) → cliente em estado de teleport pendente/desync irreversível. PRÓXIMA SESSÃO: investigar e corrigir.
- **Turno dos monstros não existe**: o mestre deveria mover os monstros no turno do monstro específico (boss) ou da horda (vários mobs no mesmo turno). Hoje a aura do monstro fica ancorada onde ele estava quando o modo mudou para combate/investigação, mesmo depois do player finalizar o turno.
- **Modo Investigação/Iniciativa (novo)**: ao mudar para o modo, rolar 1d20 para todos (por enquanto), ordem decrescente (maior primeiro). O mestre rola para a horda e/ou boss.
- **Lista de monstros em campo (novo)**: o mestre precisa de uma lista dos mobs inseridos por comando com câmera "true", para selecionar os que formam uma horda; os de fora da horda têm rolagens separadas.

## Objective
Fase 4: fazer a aura azul (círculo de limite de movimentação) aparecer no chão para o jogador ativo e para o monstro selecionado, e confirmar o fluxo de seleção/movimento do mestre. Corrigir os 3 feedbacks do usuário após o primeiro teste em jogo:
1. Aura entra no chão em terreno com desnível (deve seguir a altura do bloco).
2. Jogador consegue passar pela aura (deve ser barrado na borda).
3. "Monster selected" seguido de "Monster deselected" com UM clique (double-fire real).

## Scope / Subtasks
1. Investigar o double-fire real (cliente envia múltiplos pacotes por clique + hold path do vanilla).
2. Fazer a aura seguir o relevo do terreno.
3. Barrar o jogador na borda da aura (clamp + teleport de volta).
4. Validar com build + teste em jogo.

## What Changed
- **Double-fire**: `toggleSelection` agora tem debounce por mestre+mob (400ms). O cliente envia até 3 pacotes por clique (`interactAt` + `interact` + `useItem`) e o `Minecraft.handleKeybinds` re-dispara `startUseItem` pelo caminho "segurar botão" (`keyUse.isDown() && rightClickDelay == 0`) ~200ms após o clique. Cada batch dispara o `UseEntityCallback` do servidor 1x (o 2º pacote com `hitResult == null` já era ignorado). O debounce elimina o toggle duplo.
- **Aura segue o relevo**: `AuraRenderer` reescrito — em vez de `Gizmos.circle` (Y fixo), desenha 60 segmentos de `Gizmos.line` amostrando `level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 1.05` em cada vértice.
- **Jogador barrado na aura**: `ServerGamePacketListenerImplMixin.handleMovePlayer` — em vez de só cancelar o pacote (que deixava o cliente andando por predição sem correção), projeta a posição de volta para a borda da aura (`CombatController.clampToAura`) e envia `connection.teleport(...)` (mesmo mecanismo da correção de desync do vanilla).

## Files Changed
- `src/main/java/com/pedro/tabletoprpg/CombatController.java` — debounce `lastToggles` + record `ToggleStamp`; novo método `clampToAura(ServerPlayer, double, double)`; `lastToggles.clear()` no `reset()`; import `Vec3`.
- `src/main/java/com/pedro/tabletoprpg/mixin/ServerGamePacketListenerImplMixin.java` — clamp + `connection.teleport` em vez de cancelar; import `Vec3`.
- `src/client/java/com/pedro/tabletoprpg/client/AuraRenderer.java` — reescrito: 60 segmentos de `Gizmos.line` seguindo o heightmap; `Minecraft.getInstance().level` (o `WorldRenderContext` NÃO tem `world()`); `persistForMillis(200)` mantido.
- (Anteriores, já validados) `RpgNetworking.java` — `AuraData(x, y, z, radius)` + log em `sendAuraStateToAll`; `TabletopRpgClient.java` — receiver com log.

## Decisions
- **Debounce (400ms, por mestre+mob)** em vez de mexer no cliente: o caminho "segurar" do vanilla é comportamento nativo; o debounce elimina o sintoma sem risco de quebrar outras interações. Permite trocar de mob rapidamente (chave é mestre+mob, não só mestre).
- **`connection.teleport` para barrar o jogador**: `absMoveTo` não existe mais em 1.21.11 (removido); o `connection.teleport(x, y, z, yRot, xRot)` envia `ClientboundPlayerPositionPacket` e o servidor atualiza a posição quando o cliente aceita — é o mecanismo nativo de correção de desync.
- **Heightmap para seguir o relevo**: `getHeight(MOTION_BLOCKING, x, z)` retorna o Y do bloco mais alto; topo = Y+1; +0.05 anti-z-fighting. Consistente com o Y antigo (`anchor.getY() + 0.05`) em terreno plano.
- **60 segmentos** (~1.5 bloco cada para raio 15): suave em relevo, custo baixo (60 gizmos/aura/frame com expiração de 200ms).

## Validation
- `gradlew build`: PASS (executado pelo agente; 9 tasks, BUILD SUCCESSFUL).
- Teste em jogo: PENDENTE (aguardando usuário).

## Problems Encountered
1. **Aura entra no chão em desnível** (feedback do usuário).
2. **Jogador passa pela aura** (feedback do usuário).
3. **"Monster selected" + "Monster deselected" com 1 clique** (feedback do usuário) — persistiu após o fix anterior de ignorar `hitResult == null`.
4. `absMoveTo(double,double,double,float,float)` não existe em 1.21.11 → erro de compilação → substituído por `connection.teleport`.

## Root Causes
1. **Aura no chão**: `Gizmos.circle` usa Y fixo (`anchor.getY() + 0.05`); em terreno com desnível o círculo fica abaixo da superfície local.
2. **Jogador passa pela aura**: o mixin só cancelava o pacote de movimento; o cliente continua andando por predição e o servidor nunca corrige (a correção de desync do vanilla fica dentro do método cancelado).
3. **Double-fire real (corrige a conclusão anterior "NÃO é double-fire")**: o cliente envia até 3 pacotes por clique (`startUseItem`: `interactAt` → se não `consumesAction()` → `interact` → fallthrough → `useItem`) E o `handleKeybinds` re-dispara `startUseItem` pelo caminho de segurar (`isDown && rightClickDelay == 0`, ~200ms). Cada `startUseItem` gera um batch de pacotes; cada batch dispara o toggle 1x (o pacote `interact` com `hitResult == null` já é ignorado). Resultado: 1 clique = 2 toggles (selected → deselected). O usuário estava certo: não é duplo clique do mouse.
4. **Erro de compilação**: `Entity.absMoveTo` foi removido em 1.21.11 (substituído por `teleportSetPosition`/`setPos`).

## Fixes
- Debounce de 400ms por mestre+mob em `toggleSelection`.
- `clampToAura` + `connection.teleport` no mixin de movimento.
- Renderer com 60 segmentos seguindo o heightmap.
- Removida a chamada a `absMoveTo` (não existe em 1.21.11).

## Remaining Issues
- **BUG: player preso na barreira da aura** (reportado pelo usuário) — PRIORIDADE da próxima sessão. Investigar o flood de `connection.teleport` (teleport a cada tick na borda) e corrigir (ex: cooldown de teleport, clamp sem teleport, ou só cancelar e deixar a correção de desync do vanilla rodar).
- **Turno dos monstros não implementado**: mover monstros apenas no turno do monstro/horda; aura do monstro deve ser re-ancorada por turno.
- **Modo Investigação/Iniciativa não implementado**: rolagem 1d20 para todos, ordem decrescente, mestre rola para horda/boss.
- **Lista de monstros em campo para o mestre**: seleção de horda; apenas mobs inseridos com câmera "true".
- Confirmar em jogo (após os fixes): clique no monstro → 1x "Monster selected" (sem "deselected" em seguida); aura acompanha o relevo; jogador barrado na borda sem travar.

## Lessons / Memory
- **Double-fire de interação em 1.21.11**: 1 clique direito = até 3 pacotes (`interactAt` + `interact` + `useItem`) + re-disparo pelo hold path (~200ms). Debounce no servidor é a correção robusta.
- `Entity.absMoveTo` NÃO existe em 1.21.11; usar `connection.teleport(x, y, z, yRot, xRot)` para corrigir posição do cliente (mecanismo nativo de desync).
- `WorldRenderContext` (Fabric) NÃO tem `world()`; usar `Minecraft.getInstance().level`.
- `Level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z)` retorna o Y do bloco; topo = Y+1.
- O sistema de gizmos do vanilla (`Gizmos.line/circle/rect`) é o jeito correto de desenhar linhas visíveis em 1.21.11; gizmos não expiram sem `persistForMillis()`.

## Next Steps
1. **Corrigir o bug do player preso na barreira da aura** (PRIORIDADE): investigar o flood de teleports; testar cooldown de teleport / clamp sem teleport / só cancelar.
2. **Turno dos monstros**: mover monstros apenas no turno do monstro/horda; re-ancorar a aura do monstro por turno.
3. **Modo Investigação/Iniciativa**: rolagem 1d20 para todos ao entrar no modo, ordem decrescente; mestre rola para horda/boss.
4. **Lista de monstros em campo para o mestre**: seleção de horda (apenas mobs com câmera "true"); rolagens separadas para os de fora.
5. Atualizar este relatório e o .docx com o resultado final.