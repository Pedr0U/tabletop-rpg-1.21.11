# Project Memory — TabletopRPG (Fabric 1.21.11, Mojang mappings)

## Rendering (1.21.11)
- **Linhas visíveis no mundo**: usar o sistema de gizmos do vanilla (`net.minecraft.gizmos.Gizmos`), package no jar `minecraft-common`. API: `Gizmos.circle(Vec3, float, GizmoStyle)`, `Gizmos.line(Vec3, Vec3, int colorARGB)`, `Gizmos.line(Vec3, Vec3, int, float width)`, `GizmoStyle.stroke(int colorARGB, float width)`, cor via `net.minecraft.util.ARGB.colorFromFloat(a, r, g, b)`. É o que o `DebugRenderer.emitGizmos` usa.
- **`WorldRenderEvents.AFTER_ENTITIES`**: o PoseStack é IDENTIDADE (o vanilla subtrai a câmera manualmente por vértice). Não transladar por -camera ao usar gizmos (eles esperam coordenadas de mundo).
- **Collector de gizmos ativo durante AFTER_ENTITIES**: setado em `Minecraft.runTick` (`collectPerFrameGizmos`), passes do frame graph executam síncronos no render thread, `finalizeGizmoCollection` roda DEPOIS do evento. `Gizmos.addGizmo` lança exceção sem collector registrado.
- **Gizmos não desvanecem/expirem por padrão** (só com `fadeOut()`/`persistForMillis()`). O `SimpleGizmoCollector` NUNCA limpa a lista (só remove expirados) → SEMPRE usar `persistForMillis(...)` ao adicionar gizmos por frame, senão a lista cresce sem limite (lag/memória).
- `RenderTypes.lines()` desenha no target ITEM_ENTITY que É composto na tela (teoria antiga de "target errado" estava errada — o gizmo usa o mesmo render type e funciona).
- **`WorldRenderContext` (Fabric) NÃO tem `world()`** — usar `Minecraft.getInstance().level` (ClientLevel).

## Aura (Fase 4)
- **Aura segue o relevo**: desenhar 60 segmentos de `Gizmos.line` amostrando `level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 1.05` em cada vértice (getHeight retorna o Y do bloco; topo = Y+1; +0.05 anti-z-fighting). `Gizmos.circle` usa Y fixo → entra no chão em desnível.
- `AuraData` = `(double x, double y, double z, int radius)`; enviado via `AuraStatePayload` (STREAM_CODEC com `ByteBufCodecs.DOUBLE`).
- Cor da aura: `ARGB.colorFromFloat(0.9F, 0.3F, 0.6F, 1.0F)` (azul semi-transparente), largura 2.0F.

## Eventos de clique (servidor) — DOUBLE-FIRE REAL (corrige conclusão antiga)
- **1 clique direito = até 3 pacotes**: `Minecraft.startUseItem` chama `interactAt` (pacote 1, com posição) → se não `consumesAction()` (sempre PASS em multiplayer) chama `interact` (pacote 2, sem posição) → e cai no fallthrough `useItem` (pacote 3, se a mão não estiver vazia).
- **Hold path do vanilla**: `Minecraft.handleKeybinds` re-dispara `startUseItem` pelo caminho `keyUse.isDown() && rightClickDelay == 0` (~200ms após o clique) → segundo batch de pacotes.
- **Servidor**: mixin `ServerPlayNetworkHandlerInteractEntityHandlerMixin` (Fabric, `fabric-events-interaction-v0`) injeta em `ServerGamePacketListenerImpl$1.onInteraction(hand, pos)` (hitResult não-nulo) e `onInteraction(hand)` (hitResult == null). O cliente também dispara `UseEntityCallback` via `MinecraftMixin` (client) em `startUseItem` — retornar PASS no cliente (LocalPlayer não é ServerPlayer).
- **Fix aplicado**: (1) ignorar `hitResult == null` no handler; (2) debounce de 400ms por mestre+mob em `toggleSelection` (mapa `lastToggles` + record `ToggleStamp`). Sem debounce, 1 clique = 2 toggles (selected → deselected instantâneo).

## Barreira da aura (jogador) — BUG CONHECIDO
- `ServerGamePacketListenerImplMixin.handleMovePlayer`: se `isPlayerBeyondAura`, projeta de volta (`CombatController.clampToAura`) e envia `connection.teleport(x, y, z, yRot, xRot)` (mecanismo nativo de desync) + cancela o pacote.
- **BUG REPORTADO PELO USUÁRIO**: às vezes o player fica PRESO na barreira — não mexe câmera nem personagem, nem `/tp` tira. Suspeita: flood de `ClientboundPlayerPositionPacket` (teleport a cada tick enquanto segura W na borda) deixa o cliente num estado de teleport pendente / desync irreversível. PRÓXIMA SESSÃO: investigar e corrigir (ex: teleport com cooldown, ou clamp sem teleport, ou só cancelar + deixar a correção de desync do vanilla rodar).
- `Entity.absMoveTo(double,double,double,float,float)` NÃO existe em 1.21.11 (removido; usar `setPos`/`teleportSetPosition`/`connection.teleport`).

## Fluxo do mestre (Fase 4)
- `/rpg master claim` → `/rpg insert enemy <mob> true` (noAi + persistence + invulnerable) → `/rpg turn give <jogador>` (player anchor) → clique direito no monstro (toggle select/deselect, move até 15 blocos) → `/rpg release master` (reset restaura noAi).
- `CombatController`: `toggleSelection` (com debounce), `moveSelectedMonster`, `clearSelection(ServerLevel)`, `reset(MinecraftServer)`, `clampToAura`, `AURA_RADIUS = 15`.
- `PlayerControlHandler` retorna PASS no cliente (servidor decide).
- Highlight glowing: mestre 1024 blocos, jogador `SessionManager.hoverDistance` (padrão 32, clamp 1-256, `/rpg hoverdistance`).

## Feedback do usuário (24/09/2026) — pendências para a próxima sessão
1. **Player preso na barreira da aura** (bug acima) — PRIORIDADE.
2. **Turno dos monstros não existe**: o mestre deve mover os monstros no turno do monstro específico (boss) ou da horda (vários mobs no mesmo turno). Hoje a aura do monstro fica ancorada onde ele estava quando o modo mudou para combate/investigação, mesmo depois do player finalizar o turno.
3. **Modo Investigação/Iniciativa**: ao mudar para o modo, rolar 1d20 para TODOS (por enquanto), ordem decrescente (maior primeiro, menor último). O mestre rola para a horda e/ou boss.
4. **Lista de monstros em campo para o mestre**: selecionar monstros para formar uma horda; monstros fora da horda têm rolagens separadas. A lista mostra APENAS os mobs inseridos por comando com câmera "true".

## Workflow do usuário
- O usuário roda `gradlew build` e testa em jogo, reportando o resultado. Não fatiar arquivos em dezenas de chamadas. Não procurar logs antigos repetidamente.
- Comando do servidor dedicado: `gradlew runServer` (NÃO `gradlew server` — task não existe).
- Para testar a aura (renderização no cliente): `gradlew runClient` (single-player) ou `runServer` + `runClient` conectado em localhost.

## FASE 1 (24/09/2026) — quebra de blocos configurável + clima no menu do GM
- **API de clima (verificada com javap no jar 1.21.11)**: `ServerLevel.setWeatherParameters(int clearTime, int rainTime, boolean raining, boolean thundering)` — 4 args em 1.21.11 (NÃO 5 como em versões antigas). Valores copiados do bytecode do `WeatherCommand` vanilla: sol=`(6000,0,false,false)`, chuva=`(0,6000,true,false)`, tempestade=`(0,6000,true,true)`.
- **Setting novo**: `SessionManager.playersCanBreakBlocks` (default false). `PlayerControlHandler.canBreakBlocks` = `isMaster || (canPlayersBreakBlocks && canPlayerAct)` — mestre sempre quebra; players só se liberado no menu E no turno.
- **Payloads novos** (RpgNetworking): `BlockBreakSettingPayload` (C2S, toggle), `BlockBreakSettingQueryPayload` (C2S, ao abrir Settings), `BlockBreakSettingStatePayload` (S2C, resposta/broadcast), `WeatherSetPayload` (C2S, 0=sol/1=chuva/2=tempestade). Todos os C2S validam `isMaster` no servidor.
- **Clima afeta só a dimensão atual do mestre** (`(ServerLevel) player.level()`) — mesmo comportamento do `/weather` vanilla; sem efeito visível no Nether/End.
- **Clima = 1 botão que cicla** Sol -> Chuva -> Tempestade (estado local do cliente `weatherState`; sem sync de clima — só o mestre muda). O clima persiste no servidor até o mestre mudar.
- **Layout Settings (mestre)**: slider +0, ciclo +28, quebra +56, clima +84, voltar +112.

## UNIFICAÇÃO DE BRANCHES (24/09/2026) — CRÍTICO PARA NÃO REPETIR
- **O projeto tem 2 branches divergentes**: `main` (HEAD 2234576) e `pasta-Net` (450d64c "Correção de Bugs e Adições", 18:48). O pasta-Net contém correções que o main NÃO tem.
- **O usuário roda o jogo com o jar copiado manualmente em `%APPDATA%\.minecraft\mods\tabletop-rpg-1.0.0.jar`** — SEMPRE copiar o jar novo do build para lá após buildar (o `gradlew runClient` usa o build, mas o jogo normal usa a pasta mods).
- **Correções que estavam SÓ no pasta-Net e foram unificadas no working tree do main** (24/09 19:32):
  - `DamageControlHandler` (NOVO): jogadores e mobs imunes a dano físico via `ServerLivingEntityEvents.ALLOW_DAMAGE` (exceções: `FELL_OUT_OF_WORLD` e `GENERIC_KILL`). Registrado em `TabletopRpg.onInitialize`.
  - `ServerGamePacketListenerImplAccessor` (NOVO) + `ServerGamePacketListenerImplMixin`: fix do player preso na barreira da aura — só teleporta se `awaitingPositionFromClient() == null` (sem flood de teleports pendentes). `MobAccessor` REMOVIDO do mixins.json.
  - `CombatController`: mob flutuando (movimento direto `MOVE_SPEED=0.35` blocos/tick, sem IA, `setNoAi(true)` sempre), `lookAt` manual, âncora atualizada na seleção (`monsterAnchors.put`), aura NÃO segue o mob, `reset` limpa tudo.
  - `PlayerControlHandler`: `canPlaceBlocks` + `isBlockItemInHand` (players NUNCA colocam blocos; só mestre).
  - `RpgNetworking`: handler `DISCONNECT` (mestre sai -> releaseMaster + FREE + reset + broadcast; jogador ativo sai -> limpa turno; limpa hover/Glowing e leak).
  - `sendHoverConfigToPlayer`: agora `getHoverDistance()` para TODOS (inclusive mestre) — fix do hoverdistance.
  - `TabletopRpgClient.tickHover`: linha de visão (raycast de blocos) — não destaca mob através de paredes.
  - `CinematicCameraController`: câmera não atravessa parede ao rotacionar.
- **LIÇÃO**: antes de implementar, SEMPRE verificar `git branch -a` e `git log --all --oneline` — pode haver outro branch com trabalho não mesclado. O resumo de sessão anterior registrou a "FASE 0.7" como aplicada no main, mas ela estava no pasta-Net (relatório feedback-fixes-2.md nunca existiu).