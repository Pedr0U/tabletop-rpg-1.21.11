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

## Ajustes 24/09 (2ª rodada) — sync de clima + toggle de colocação
- **Sync de clima (fix do botão)**: o botão de clima agora reflete o clima REAL do mundo. Novos payloads: `WeatherQueryPayload` (C2S, ao abrir Settings) e `WeatherStatePayload` (S2C, resposta/broadcast). O servidor deriva o clima do estado real do nível: `currentWeather(level)` = `!isRaining()` → 0 (sol); `isRaining() && !isThundering()` → 1 (chuva); `isRaining() && isThundering()` → 2 (tempestade). API verificada com javap: `Level.isRaining()`/`Level.isThundering()` (na classe `Level`, NÃO em `ServerLevel` — em 1.21.11 foram movidos). O `WeatherSetPayload` receiver agora faz broadcast (`sendWeatherStateToAll`) após aplicar.
- **Toggle de colocação de blocos**: novo setting `SessionManager.playersCanPlaceBlocks` (default false). `PlayerControlHandler.canPlaceBlocks` = `isMaster || (canPlayersPlaceBlocks && canPlayerAct)` — mesmo padrão do quebrar. Payloads novos: `PlaceBlockSettingPayload` (C2S, toggle), `PlaceBlockSettingQueryPayload` (C2S), `PlaceBlockSettingStatePayload` (S2C, resposta/broadcast). Todos os C2S validam `isMaster`.
- **Layout Settings (mestre) ATUALIZADO**: slider +0, ciclo +28, quebra +56, **colocação +84**, clima +112, voltar +140.
- **Pendências anotadas (aguardando "iniciativa")**: turno dos monstros/horda, iniciativa 1d20, lista de monstros para o mestre — NÃO implementar até o usuário pedir.

## FASE 2 — Câmeras, Entidades e IA de Combate (planejamento do usuário, 24/09/2026)
> **IMPLEMENTADA em 24/09/2026** (relatório: `agent/reports/2026-09-24_fase2.md`). As seções abaixo registram o planejamento original; a seção "FASE 2 — IMPLEMENTAÇÃO" no fim resume o que foi feito.

### Carrossel de espectador (modos de câmera)
- **Clique esquerdo**: avança para o PRÓXIMO player/mob com câmera.
- **Clique direito**: retorna para o player/mob ANTERIOR que estava espectando.
- O carrossel navega apenas entre players/mobs que têm câmera (invocados com cam_perm true).

### Modos de câmera ao espectar
1. **3ª pessoa**: câmera orbitável com o mouse.
   - **Adendo**: a rotação da câmera (órbita) só existe para jogadores/mobs que estão PARADOS e NÃO estão no turno deles.
   - Para jogadores que ESTÃO no turno: a câmera de 3ª pessoa é movimentada pelo mouse do jogador que está ESPECTANDO (só para quem especta).
2. **1ª pessoa**: vê a visão do player/mob que está espectando.
3. **TopDown**: visão de cima do player espectado, mantendo o espectado no CENTRO da visão.
4. **Câmera livre**: equivalente ao `/gamemode spectator` do Minecraft, MAS o corpo do jogador fica parado no lugar — ele pode se ver (efeito "fantasma": saiu do próprio corpo, corpo ficou no local).

### Regra de turno (espectador)
- Quando o turno é dado ao player que está espectando: ele volta INSTANTANEAMENTE para 1ª pessoa (ou 3ª, dependendo de como estava jogando) e tem o controle normal da câmera para jogar o turno dele.

### Pendências da FASE 2 (a confirmar com o usuário)
- Invocação com câmeras: `/rpg insert enemy <mob> true` já funciona (noAi + persistence + invulnerable) — falta conectar com o sistema de câmera (ver pela entidade).
- Ações e comandos: GM comanda a entidade a andar/fazer ações — "ações" = ataque básico do mob (a definir com o usuário).
- O usuário ainda vai detalhar o restante da FASE 2 e as Fases 3 e 4.

### FASE 2 — respostas do usuário (5ª rodada, 24/09/2026)
- **Ativar espectador**: AUTOMÁTICO ao travar — quando a câmera orbital assume, o jogador já está no modo espectador e pode trocar para o próximo player com os cliques.
- **NOVO — Settings do jogador**: opção **"Câmera Orbital: SIM/NÃO"** no menu Settings do JOGADOR (não só do mestre). Se NÃO: a órbita desativa e o jogador toma controle da 3ª pessoa com o mouse.
- **Quem especta**: APENAS os jogadores TRAVADOS. O MESTRE NUNCA usa o carrossel de espectador nem os modos de câmera (ele está comandando tudo).
- **Alternar modos de câmera**: tecla **V** cicla (3ª pessoa → 1ª pessoa → TopDown → Livre); também acessível no menu.
- **`/rpg remove enemy`**: fluxo = executar o comando + CLIQUE no mob (esquerdo/direito) remove ele.

### FASE 2 — detalhamento do usuário (2ª rodada, 24/09/2026)
- **Invocação com câmeras**: está correto como está; adicionar apenas a possibilidade de **espectar o mob com câmera** (ver pela entidade invocada).
- **Novo comando `/rpg remove enemy`**: remove o mob sob o cursor (clique esquerdo OU direito — o que for mais fácil). Após remover, é preciso executar o comando de novo para remover outro inimigo (1 mob por execução).
- **FASE 4 (futuro)**: dois itens de menu — um para **remover mobs** e outro para **adicionar câmera em um mob/entidade** (substituirão o comando).
- **Ações e comandos (item 3 da FASE 2): IGNORAR** — não implementar.

### FASE 2 — IMPLEMENTAÇÃO (24/09/2026)
- **`SpectatorCameraController`** (cliente, NOVO) substitui o `CinematicCameraController` (deletado). Enum `Mode` (THIRD_PERSON/FIRST_PERSON/TOP_DOWN/FREE), `orbitalEnabled` (default true), carrossel (`targetIndex`, 0 = self), transição 50 ticks smoothstep, `setCameraEntity(target)` para renderizar o corpo do alvo.
- **Carrossel**: clique esquerdo = próximo, direito = anterior (mixin `MinecraftMixin` consome `keyAttack`/`keyUse` quando `locked`). Alvos = todos os jogadores + mobs com cam_perm=true (`SpectatorTargetsPayload` S2C). Mestre nunca fica locked → nunca usa.
- **Modos**: 3ª pessoa (órbita auto se orbital=true E alvo fora do turno; senão mouse do espectador via `activePlayerUuid`), 1ª pessoa (self=vanilla; outro=olhos+rotação do alvo, mão escondida via `GameRendererMixin`), TopDown (15 acima, pitch 90°), Livre (WASD+espaço/shift, mouse, corpo parado).
- **Tecla V** (`rpgCameraModeKey`) cicla modos; Settings tem botões "Câmera Orbital: Sim/Não" e "Modo de Câmera" para TODOS (mestre: +140/+168/voltar+196; jogador: +0/+28/voltar+56).
- **`/rpg remove enemy`**: remove o mob selecionado (clique direito nele primeiro), 1 mob/execução. `CombatController`: `cameraMobs` (Set<UUID>), `getSelectedMonster`, `removeSelectedMonster`.
- **Rede**: `SpectatorTargetsPayload`+`TargetData(entityId,name,isPlayer)` e `ActivePlayerPayload(uuidString)`; broadcast em JOIN/DISCONNECT/insert cam_perm/remove enemy/releaseMaster/setMode(FREE). Nome do mob truncado em 64 chars (codec lança exceção acima disso).
- **LIÇÕES**: (1) `CinematicCameraRig.update()` é no-op quando inativo → usar `ensureRigActive` (activate/update); (2) mouse look sobrevive ao freeze do `aiStep` (`turn()` é aplicado pelo `MouseHandler`); (3) `cameraEntity` só muda via `setCameraEntity`; (4) todo ponto onde a lista de alvos muda precisa de broadcast.
- **Pendências pós-FASE 2**: testes em jogo pelo usuário; TopDown sem clipToWall (pode entrar em blocos); rotações de alvos travados congeladas no servidor (FASE 1 exposto); `monsterAnchors` não limpo no remove.

### FASE 2 — feedbacks do usuário corrigidos (24/09/2026, ~22:00)
- **Filtros de visão de mobs**: o vanilla aplica post-effects ("creeper"/"spider"/"invert") via `GameRenderer.checkEntityPostEffect(Entity)`, chamado por `Minecraft.setCameraEntity` e `GameRenderer.handleKeybinds` (F5). Mixin `GameRendererMixin` cancela + `clearPostEffect()` quando `SpectatorCameraController.isActive()`. **LIÇÃO**: métodos do target do mixin não são visíveis em compile-time — usar `@Shadow` (ex.: `@Shadow public abstract void clearPostEffect();`).
- **3ª pessoa sem orbital**: o ramo do mouse usava `computeYaw/computePitch` (olhava PARA o centro → invertido/descentralizado). Corrigido para `client.player.getYRot()/getXRot()` (vanilla-like: câmera atrás do alvo na direção do look, alvo centralizado). A transição de 50 ticks também foi ajustada para ir direto ao estado final do modo (mouse ou órbita) — evita snap posicional+rotacional no fim.
- **Câmera livre atravessa paredes**: a posição já era livre (TAIL do `CameraMixin` vence a colisão vanilla do `Camera.setup`); o bloqueio era VISUAL — `LevelRenderer.cullTerrain(Camera, Frustum, boolean isSpectator)` usa `isSpectator` para desligar `smartCull` quando a câmera está dentro de bloco sólido (bytecode offset 265-287; `Minecraft.smartCull` default true). Novo `LevelRendererMixin` com `@ModifyVariable(method="cullTerrain", at=HEAD, ordinal=0, argsOnly=true)` força `isSpectator=true` quando modo FREE ativo. Registrado no mixins.json.
- **Menu ASCII**: removida a linha "(open roll - everyone sees)" do `MasterCommands` (entre [Roll] e [Step Down]).
- **Mojibake `§`**: 7 mensagens do `CombatController` tinham `§` no lugar de `§` (herança pasta-Net) — substituídas. Ao encontrar `§` em literais de chat, trocar por `§`.
- **Botões de câmera do mestre**: `RpgSettingsScreen` mostra "Câmera Orbital"/"Modo de Câmera" apenas para NÃO-mestres (mestre nunca fica travado). Layout mestre: slider +0, ciclo +28, quebra +56, colocação +84, clima +112, voltar +140. Não-mestre: orbital +0, modo +28, voltar +56.

### FASE 2 — feedbacks do usuário corrigidos (24/09/2026, ~22:30) — 8 correções
- **Clima (botão trava)**: em 1.21.11 `Level.isRaining()` = `canHaveWeather() && getRainLevel(1.0F) > 0.2D` e `isThundering()` = `canHaveWeather() && getThunderLevel(1.0F) > 0.9D` — valores SUAVIZADOS (mudam gradualmente). Durante a transição o estado real ≠ escolhido → botão "voltava". Fix: servidor guarda o estado ALVO (`SessionManager.weatherTarget`, 0/1/2); broadcast/query enviam o ALVO. `currentWeather(ServerLevel)` removido. **LIÇÃO**: para UI de botão de clima, usar estado alvo no servidor, não derivar do mundo.
- **HUD do espectador**: novo `GuiMixin` cancela `renderCrosshair`, `renderHotbarAndDecorations` (hotbar/vida/fome/armadura/ar/XP/item name) e `renderEffects` (poções) quando `SpectatorCameraController.isActive()`. **LIÇÃO**: em 1.21.11 o HUD do `Gui` está em métodos privados separados — canceláveis por inject HEAD.
- **Mestre no carrossel**: `getSpectatorTargets` pula o mestre (`SessionManager.isMaster(p)`).
- **Highlight no espectador**: `tickHover` retorna cedo quando espectador ativo + limpa hover anterior (`HoverPayload(-1)`).
- **Persistência dos mobs com câmera**: `reset()` NÃO limpa mais `cameraMobs`/`insertedMobs` (persistem ao mudar de modo/liberar mestre). Para restart: `insertEnemy` grava tags vanilla (`addTag("tabletoprpg_inserted")`/`addTag("tabletoprpg_camera")` — tags persistem no NBT "Tags" da entidade) e `selfHealCameraMobs` (em `getSpectatorTargets`) + `selfHealInsertedMobs` (no tick) re-registram via `server.getAllLevels()` → `getAllEntities()`. **LIÇÃO**: `Entity.getPersistentData()` NÃO existe em 1.21.11 (campo `customData` privado, sem getter — verificado via javap); usar tags vanilla.
- **Modo livre NÃO atravessa paredes (correção de rumo)**: o usuário NÃO quer noclip. `SpectatorCameraController.collideFreeCamera` — raycast `level.clip` (`ClipContext.Block.COLLIDER`) da posição anterior à nova; se houver bloco, para 0.1 antes. O `LevelRendererMixin` foi REESCRITO: hack do `cullTerrain` REMOVIDO, substituído pelo fix do corpo do jogador.
- **Corpo do jogador some ao espectar**: `LevelRenderer.extractVisibleEntities` (offsets 239-256) pula o `LocalPlayer` quando `camera.entity() != entity`. Há 4 chamadas a `Camera.entity()` no método; a do check do LocalPlayer é a **ordinal 3** (offset 248). Fix: `@Redirect` em `Camera.entity()` ordinal 3 retornando `Minecraft.getInstance().player` quando espectador ativo (null-guard). **LIÇÃO**: ordinal 3 = check do LocalPlayer em `extractVisibleEntities`.
- **Mobs não olham para o player**: `tickControlledMonsters` tinha early-return `if (controlledMonsters.isEmpty()) return;` e só olhava mobs SELECIONADOS. Fix: removido early-return; novo `Set<UUID> insertedMobs` (todos os mobs inseridos); loop extra — olham para o player mais próximo mesmo sem seleção (pulados quando em movimento); UUID removido se a entidade sumiu (também de `cameraMobs`). `removeSelectedMonster` limpa `insertedMobs`.
- **Mojibake no `CombatController.java`**: comentários corrompidos ("até", "NÃO", "âncora", "posição", "só", "começar", "ÔÇö") — edits falham se não casarem o texto exato lido do arquivo; editar em pedaços menores.

### FASE 2 — feedbacks do usuário corrigidos (24/09/2026, ~23:00) — jogador estático + 2 sliders
- **Jogador 100% estático ao espectar**: `MouseHandler.turnPlayer(double)` chama `minecraft.player.turn(d3, d5)` INCONDICIONALMENTE (sem check de cameraEntity — verificado via javap, offset 292) — o `LocalPlayer.turn` (herdado de `Entity`) roda fora do `aiStep` cancelado, então o jogador travado girava cabeça/corpo ao mexer o mouse. Fix: novo `EntityTurnMixin` (mixin em `Entity`, a classe declarante de `turn`, com check `instanceof LocalPlayer`) cancela `Entity.turn(DD)V` quando `SpectatorCameraController.isActive()` e repassa os deltas a `onMouseLook`. **LIÇÃO**: para travar a rotação do jogador, cancelar `Entity.turn` (mixin na classe declarante + instanceof) — o bloqueio do `aiStep` não cobre o mouse.
- **Câmera Livre com look próprio**: novos `freeCamYaw`/`freeCamPitch` (inicializados da rotação do jogador ao entrar no modo Livre); `onMouseLook` aplica os deltas com o MESMO fator 0.15 do `Entity.turn` vanilla (verificado no bytecode: `xRot += pitch*0.15`, `yRot += yaw*0.15`, pitch clampado ±90) — a câmera Livre olha com o mouse SEM girar o jogador.
- **Mão escondida sempre que espectando**: `GameRendererMixin.renderItemInHand` agora cancela quando `isActive()` (antes só quando cameraEntity != player) — a mão também some na 1ª pessoa do próprio jogador.
- **Sliders nas Settings dos players**: novo `RpgSlider` (genérico min..max, rótulo customizado, callback ao soltar — padrão do `TimeSlider`). **Zoom TopDown** (-100..100, padrão 0): altura = `TOP_DOWN_HEIGHT + zoom*0.1` → 5..25 blocos (base 15). **Velocidade de Transição** (0..100, padrão 50): ticks = `100 - speed` → 50 → 50 ticks (atual), 100 → 1 tick (instantâneo). Layout não-mestre: orbital +0, modo +28, zoom +56, transição +84, voltar +112, aviso +140.

### FASE 2 — fix pós-feedback (24/09/2026, ~23:20) — câmera 3ª pessoa sem controle
- **Regressão**: ao travar a rotação do jogador (EntityTurnMixin), o ramo do mouse da 3ª pessoa congelou — ele usava `client.player.getYRot()/getXRot()/getLookAngle()`. Fix: `thirdPersonYaw`/`thirdPersonPitch` próprios (inicializados da rotação do jogador; flag `thirdPersonLookInitialized` resetada na ativação/`resetTransition`), `onMouseLook` trata THIRD_PERSON, helper `lookFromYawPitch(yaw, pitch)` (mesma fórmula do `Entity.calculateViewVector(pitch, yaw)`). **LIÇÃO**: ao travar a rotação do jogador, TODA câmera que dependia da rotação dele congela — câmeras de espectador precisam de yaw/pitch próprios alimentados pelos deltas do turn cancelado.
- **Órbita automática inalterada** (orbital ON + alvo fora do turno): continua auto (cinemática, olhando o centro); o mouse não a controla (design original). Se o usuário quiser controlar a órbita com o mouse, unificar os modelos depois.

## FASE 3 — Ajustes de Conforto, Conexão e Regras Extras (planejamento do usuário, 24/09/2026)
> O usuário está detalhando a FASE 3 por partes. Esta seção registra o que ele já definiu.

### Resiliência de conexão — MUDANÇA DE COMPORTAMENTO
- **Ao desconectar, NÃO limpar o estado do jogador.** Manter TODOS os detalhes: se é o turno dele ou não, vida, itens, etc.
- Motivo: limpar pode bugar a lista de iniciativa mais pra frente; se o jogador cair no meio do combate sem querer, ele deve conseguir voltar para o combate no mesmo estado.
- **ATENÇÃO**: o comportamento ATUAL é o oposto — o handler `DISCONNECT` em `RpgNetworking` limpa o turno do jogador ativo (`clearActivePlayer`) e o mestre sai libera o cargo + pausa a sessão. A FASE 3 vai precisar REVERTER/ajustar isso (manter estado do jogador; decidir o que fazer com o mestre que cai).

### Sistema de Iniciativa — começa com Atributos e Perícias
- **Menu dos players**: novas opções **"Status"** e **"Habilidades"**.
- **Menu Status**:
  - **Vida do player**: barra de vida (NÃO corações).
  - **Fome: REMOVER toda a funcionalidade** (não existirá mais fome no RPG).
  - **Mana**: nova barra de mana para o player.
  - **Nome do player**: editável pelo próprio jogador.
  - **Origem**, **Classe**, **Nível**.
  - **CA** (Classe de Armadura).
  - **Deslocamento**: a distância de blocos da aura azul (limite de movimento).
- **Menu Habilidades**:
  - **Atributos**: Força, Destreza, Inteligência, Constituição, Carisma — cada um com um valor (ex: X de Força).
  - **Perícias**: 20 perícias, nomeadas "perícia 1", "perícia 2", ... até "perícia 20" — cada uma com um valor.
  - **Perícias somam com atributos**: ex. "Acrobacia" soma com Destreza, "Luta" soma com Força, "Diplomacia" soma com Carisma.
  - **NÃO permanente**: o jogador pode trocar com qual atributo cada perícia soma (ex: trocar "Luta" para somar com Destreza).
  - **Abreviações dos atributos nos botões**: FOR (Força), DES (Destreza), INT (Inteligência), CON (Constituição), CAR (Carisma).
- **Tudo personalizável pelo jogador**: nome, origem, nível, todos os valores de status e habilidades.

### FASE 3 — detalhamento do usuário (2ª rodada, 24/09/2026)
- **Lista de Iniciativa HUD**:
  - Dentro das perícias, haverá a perícia **"Iniciativa"**, que originalmente soma com **Destreza**.
  - Cálculo da rolagem: **d20 + Iniciativa + Destreza** (ex: 2 de iniciativa + 3 de destreza = 5; rola d20 e soma 5).
  - A lista é montada **automaticamente** com as rolagens de todos os players.
  - O **mestre rola para os Mobs** que estiverem em cena com câmeras dentro deles, da mesma forma; como ainda não temos status/atributos dos mobs, será **d20 puro** para cada mob OU para a **horda** que ele configurou.
  - **Sistema de horda**: será feito com um **item personalizado na FASE 4** (ANOTADO — pendente).
  - **HUD no lado ESQUERDO da tela**: ordem de todas as iniciativas com o nome dos players e mobs em **ordem decrescente** (maior primeiro, menor por último).
- **Pular turno / Atrasar ação**:
  - **Pular turno**: já temos (é o finalizar turno existente).
  - **Atrasar ação**: botão no menu do MESTRE que permite **mexer na ordem das iniciativas** — apenas isso.
- **Remover inimigo**: `/rpg remove enemy` (definido na FASE 2).
- **Travar portas/baús + itens à distância**:
  - O mestre terá um **item personalizado para travar** baús e portas (blocos não interagíveis pelos players).
  - Ao tentar interagir com um bloco trancado, o player vê a mensagem **"trancado"**.
  - O item personalizado, de começo, pode ter a **mesma textura do stick** do Minecraft.
  - (Colocar itens em baús à distância: ainda não detalhado pelo usuário — pendente.)

### FASE 3 — confirmações do usuário (3ª rodada, 24/09/2026)
- **Empate na iniciativa**: desempate = maior Destreza primeiro; se ainda empatar, ordem de chegada da rolagem. (CONFIRMADO)
- **HUD de iniciativa**: lista ordenada calculada no SERVIDOR (autoritativo) e enviada via payload S2C; HUD é só renderização no cliente. (CONFIRMADO)
- **Atrasar ação**: NÃO vai para o último direto. O jogador pede ao mestre "quero atrasar minha ação e jogar depois do jogador X" — o mestre tem a opção de **mover o jogador para depois de OUTRO jogador específico** na ordem das iniciativas.
- **Vida customizada**: players continuam IMUNES a dano externo (FASE 0), MAS a vida pode ser **diminuída/aumentada no menu "Status"** (pelo jogador ou pelo mestre). Esconder os corações do vanilla e **desativar a regeneração natural**.
- **Mana**: barra junto com a vida; **sem regeneração**; nada altera vida/mana exceto o jogador ou o mestre alterando nos Status.
- **Fome**: confirmado — esconder a barra + manter fome cheia.
- **Travar portas/baús**: confirmado.
- **Horda**: confirmado — item personalizado na FASE 4.

### FASE 3 — respostas do usuário (4ª rodada, 24/09/2026)
- **Atrasar ação — COMO FUNCIONA**: por comando `/rpg initiative set <nome1> <nome2> <nome3> ...` — o mestre monta a NOVA lista de iniciativa na ordem que quiser (o comando sugere os nomes disponíveis), e essa lista vale como a lista de iniciativa. **Só por comando por enquanto**; visual no menu depois.
- **Valores iniciais** (quando o jogador entra pela primeira vez): **Vida 10, Mana 5, atributos 2 cada, perícias 0**. Tudo alterável.
- **Nome do player**: é um **nome de personagem separado** (exibido no HUD/menu do RPG) — o nome da conta do Minecraft NÃO muda.
- **Deslocamento**: o valor de Deslocamento no Status **define o raio da aura azul** (hoje fixo em 15 blocos).

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

## Rotacao visual de personagem caido (26/09/2026)

- **Problema:** com a camera orbitando, o corpo do caido parecia girar para o
  lado OPOSTO ao movimento da camera, e a mao nao acompanhava a visao.
- **Causa:** `Entity.turn` esta cancelado no modo espectador, entao a rotacao do
  jogador fica CONGELADA. Setar `yBodyRot`/`yHeadRot` no tick da camera nao
  resolve, e para jogador remoto o `LivingEntity.tick()` do cliente sobrescreve
  logo depois.
- **Solucao:** mixin em `LivingEntityRenderer.extractRenderState` com
  `@At("TAIL")`, aplicando `yRot` e `bodyRot` do `LivingEntityRenderState`.
  Extrair o estado e o ultimo ponto antes de renderizar: nenhum tick roda
  depois. Para saber o yaw da camera em qualquer modo (inclusive orbita
  automatica, cujo `angle` tem origem diferente), usar
  `SpectatorCameraController.getCurrentYaw()`, alimentado em `ensureRigActive`.
- `extractRenderState` e generico e o vanilla gera ponte sintetica
  `(Entity, EntityRenderState, float)`; o mixin aponta para a ponte.

## Mixin em metodo generico: descriptor correto (26/09/2026)

- **FATO (crash real):** `DownedBodyAlignMixin` com
  `extractRenderState(Entity, EntityRenderState, float)` derrubou o jogo na
  abertura com InvalidInjectionException: Expected (LivingEntity,
  LivingEntityRenderState, float) but found (Entity, EntityRenderState,
  float).
- **FATO:** `javap` mostra uma ponte sintetica (Entity, EntityRenderState,
  float) em LivingEntityRenderer, mas o Mixin NAO injeta nela: resolve para o
  metodo generico e exige os tipos genericos concretos.
- **REGRA:** em metodo generico do Minecraft escrever o descriptor com os tipos
  concretos e de forma explicita:
  extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V
- **REGRA:** nunca deixar method sem descriptor quando o nome for sobrecarregado.
- **PROCEDIMENTO:** depois de criar ou alterar QUALQUER mixin, rodar
  `gradlew runClient` antes de entregar jar. Em ambiente de dev o jogo chega ao
  menu em ~20-25s e o marcador de sucesso e "Sound engine started"; um
  InvalidInjectionException aparece em run/crash-reports em segundos. Barato ao
  lado de um ciclo de teste do usuario.
  - **Armadilha do runClient:** com o jogo de pe, `runClient` NAO retorna (o
    task e longo). Isso e sinal de SUCESSO, nao de travamento. Confirmar
    lendo `run/logs/latest.log` procurando "Sound engine started" e contando
    `run/crash-reports`, em vez de esperar o comando terminar. Nos crashes o
    task falha em segundos e o comando retorna.

## Mixin: campo e metodo estatico tem que ser PRIVATE (26/09/2026)

- **FATO (crash real, 2x):** `public static final boolean DIAG_ENABLED` em um
  mixin derruba o jogo em `InvalidMixinException: contains non-private static
  field DIAG_ENABLED:Z`. O mesmo acontece com metodo estatico `public`:
  `contains non-private static method tabletopRpg$diagAppliedYaw()F`.
- **REGRA:** dentro de um mixin, todo campo estatico e todo metodo estatico
  que NAO seja injecao tem que ser `private`.
- **CONSEQUENCIA:** nao da para compartilhar estado estatico entre mixins por
  la. Colocar o estado em uma classe NORMAL (ex.: `CinematicCameraRig`) e
  chamar de la. Cross-mixin sem interface `@Accessor` nao funciona.

## Varredura de encoding: task Gradle `scanEncoding` (26/09/2026)

- **FATO:** existe em `build.gradle` e `check` depende dela, entao roda em toda
  `build`. Nao converte encoding, so reporta. Uso isolado:
  `.\gradlew.bat scanEncoding --console=plain`.
- **FALHA:** CJK/hangul/kana/fullwidth, U+FFFD e marcadores de mojibake sem
  grafia portuguesa possivel (0xD0 e o caso real do repo).
- **INFO:** acentos latinos, travessao e aspas tipograficas. NUNCA classificar
  esses como proibido: a primeira versao acusou 158 falsos positivos em JavaDoc
  legitimo ("3a pessoa", "CONSTRUCAO", "Nao") e teria quebrado todo o build.
- **ERRO CORRIGIDO:** `def minhaFn = { ... }` de script nao resolve dentro de
  `doLast` (NPE "closure is null"). Usar `ext.minhaFn = { ... }` e chamar
  `project.minhaFn(...)`. Relatorio cosmetico com `collect`/`sort`/`countBy`
  em acao diferida tambem estoura: escrever imperativo.

## Como o usuario TESTA: `runClient`, nunca jar instalado (26/09/2026)

- **FATO:** o usuario abre o jogo com `.\gradlew.bat runClient`, a partir da
  arvore de fontes. Nao instala jar em `build/libs` e nao usa o
  `mods/` de nenhuma instancia do Minecraft.
- **CONSEQUENCIA:** quando pedir para testar, escrever o comando `runClient` e
  o caminho do log. **Nao** mandar instalar `tabletop-rpg-1.0.0.jar` nem o
  `tabletop-rpg_TESTE_*.jar`. Isso ja aconteceu e foi errado: o jar e um
  produto do build, nao o alvo do teste.
- **LOG:** `run/logs/latest.log`. Crash reports em `run/crash-reports/`.
- **ESPERADO:** `runClient` nao retorna porque o jogo fica de pe. Isso e
  normal, nao e travamento. Para confirmar que subiu, ler
  `Sound engine started` no log e `run/crash-reports/` vazio.
- **INSTRUMENTACAO ATIVA:** `DIAG_TEMP` esta ligado e escreve linhas
  `[DownAlign]` a cada 10 chamadas de `Player`. Isso e de proposito, para o
  diagnostico do braco do caido. Se poluir o log, avisar antes de desligar.

## DIVISAO DA MEMORIA (26/09/2026) - este arquivo e a memoria de ENGENHARIA

Decisao do usuario em 26/09/2026: a memoria do TCC (`GitHub/agent/memory/project-memory.md`)
passou a guardar **so** o que e do TCC (requisito, decisoes do usuario, disciplina,
indice de relatorios). **Fato de engenharia deste codigo mora AQUI**, versionado
junto com o codigo, e nao nos dois lugares. Blocos gravados pelo agente principal
sao datados e marcados `FATO verificado` ou `HIPOTESE`.

Relatorios de sessao: `agent/reports/`. Memoria de proceso do TCC:
`GitHub/agent/memory/project-memory.md`.

## Setas de reordenar skills, traducao para ingles e camera livre (26/09/2026) - FATO verificado por build
- Relatorio: `GitHub/agent/reports/2026-09-26_setas-skills-ingles-camera-livre.md`. **Migrado de** `GitHub/agent/memory/project-memory.md` em 26/09/2026, quando as duas Casas foram separadas. **Commit `95737a5`** na `main` + tag `checkpoint-20260926-2005-setas-ingles-camera`; esse foi o primeiro uso de tag do projeto. `build --no-daemon` verde; **nada validado em jogo**.
- **ARMADILHA DE DADO (importante):** `SheetData.sanitizePericias` casa o NBT salvo com `PERICIAS_PADRAO` por **nome** e, sem match, substitui pelo padrao (valor 0, atributo padrao) **sem erro e sem log**. Renomear uma pericia do padrao **zera silenciosamente as pericias de toda ficha ja salva**. A solucao adotada e `LEGACY_PERICIA_NAMES` + `matchesPericiaName`, que aceitam o nome antigo e preservam valor e atributo. **Regra: qualquer renomeacao futura em `PERICIAS_PADRAO` precisa de apelido junto.**
- `Attribute` tem 4 nomes e eles nao sao intercambiaveis: `field` = chave do NBT, `abbr` = **valor do payload na rede** (`Attribute::abbr` no `STREAM_CODEC`), `shortName` = sigla curta so de exibicao (novo, 26/09: STR/DEX/CON/INT/WIS/CHA), `fullName` = por extenso. **Traduzir so `shortName` e `fullName`.** O antigo `DES`/`SAB` continua indo na rede de proposito.
- `SkillOp` ganhou `MOVE` (antes de `INVALID`). `SkillOp` **nao** e persistido em NBT, entao mudar o ordinal de `INVALID` (2->3) e inofensivo. O delta viaja no campo `description` do `SheetSkillPayload` (`"-1"`/`"+1"`); `RpgNetworking.moveSkill` descarta qualquer outro valor.
- **A ordem da lista de skills e a ordem da `List`** e e ela que o codec leva para o NBT - por isso reordenar e `withSkillMoved(nome, delta)` com `next.add(to, next.remove(from))`, sem indice novo por skill.
- **Camera:** `isFreeModeAvailable()` e `enforceModeForSession()` foram **removidos**; a camera livre vale nos 3 modos da sessao. Quem esta **travado** cicla as 4 pelo V; quem nao esta travado alterna jogo <-> livre. Regra do turno: `!locked && wasLocked` -> volta para `THIRD_PERSON` (actionbar `Normal camera`), mas a livre continua liberada no proprio turno.
- **O servidor reenvia `locked=false` para todos em duas situacoes distintas** (troca de turno **e** troca de modo da sessao). O cliente so distingue pelo `TabletopRpgClient.gameModeOrdinal`: mudou o modo, nao conta como turno. Sem isso, o mestre trocar Combate->Livre derrubava a camera de todo jogador travado.
- `ClientPlayConnectionEvents.DISCONNECT` limpa `downedPlayers`, `downed` e agora tambem `locked`; sem isso, quem desconectava travado perdia a camera livre ao reconectar.
- **UI de hover em `Screen`:** `AbstractWidget.render` e `final` e sai cedo se `!visible`; `isMouseOver` exige `isActive()` (`visible && active`); o dispatch de clique do `Screen` vai para o **primeiro** filho em ordem de insercao que casar. Widget que so existe no hover **nao pode** usar `isMouseOver` no teste de hover (nunca ficaria clicavel) - precisa de retangulo cru, como `SkillsScreen.rectOver`. E `visible`/`active` escritos no `renderContent` valem para o clique do **frame seguinte**.
- Termo: a lista livre chama **"Skills"** e a lista fixa de pericias chama **"Skill Checks"** (decisao do usuario), para nao haver dois rotulos iguais na mesma tela. Tudo visivel ao jogador esta em ingles desde 26/09/2026; `src/main/resources/assets/tabletop-rpg/lang/en_us.json` foi criado (nao havia pasta de lang, e as `KeyMapping` mostravam a chave crua em Controls).
- Pendencia conhecida: o rotulo em ingles das 4 pericias nomeadas ("Melee", "Acrobatics", "Diplomacy", "Initiative") foi escolha do agente, nao do usuario. **O usuario recusou em 26/09/2026** adicionar descricao as 20 fixas (mudaria protocolo e persistencia). Se o documento do TCC usar outro termo, trocar em `PERICIAS_PADRAO` **e** acrescentar o antigo em `legacyPericiaName`.

## Mover mob, worldtime e confirmacao de remocao (26/09/2026) - FATO verificado por build
- Relatorio: `GitHub/agent/reports/2026-09-26_worldtime-mob-abaixo-de-bloco-confirmacao-remover.md`. **Migrado de** `GitHub/agent/memory/project-memory.md` em 26/09/2026. **Nenhum commit**: as 2 alteracoes ficaram no working tree; HEAD = `95737a5`.
- **LEITURA DE BUG: o usuario relatava defeitos ja corrigidos (itens 1 e 3).** O aviso de "V" e o offset do worldtime ja estavam no codigo ha dias (`9bfabbe` e 25/09). **Antes de investigar bug de UI, confirmar em qual commit o jar do usuario foi construido** (`git log -S` no texto do sintoma). O proprio `TimeSlider` documenta o offset como "bug corrigido em 25/09/2026".
- **`Level.getHeight(Heightmap.MOTION_BLOCKING, x, z)` NAO e "chao disponivel para entidade":** devolve o topo do bloco que bloqueia movimento na **coluna**, e o predicado inclui **tronco e folha** (`blocksMotion() || fluido`). Era por isso que `CombatController.moveSelectedMonster` punha o monstro em cima da copa ao clicar no chao debaixo de uma arvore, e por isso o `dest.getY()` do bloco clicado era descartado. Agora `groundY = dest.getY() + 1`.
- **`BlockState#blocksMotion()` tambem NAO e teste de encaixe:** `isSolid()` da `false` para shape parcial/vazio, entao slab, cerca, camada de neve, bau, placa e tocha **passariam**; e `blocksMotion()` e `true` para folha e `false` para agua. Para "a entidade cabe ai?", use `level.noCollision(entity, aabb)` com a AABB que a entidade teria no destino - `CollisionGetter#noCollision(Entity, AABB)` existe no jar `minecraft-common-1.21.11-loom.mappings...jar` e ainda respeita a borda do mundo. Ela **nao** checa entidade-contra-entidade.
- **`Entity.setPos` so reposiciona e reconstroi a AABB** (confirmado por `javap`): nao resolve colisao, nao empurra. `noAi=true` **nao** desliga gravidade nem fisica. Ou seja, checagem de destino e sempre um *snapshot*; o trajeto intermediario do `tickControlledMonsters` ignora colisao.
- **`isWithinAura` e um cilindro SEM Y** (so `dx`/`dz` contra 15). Consequencia: o mestre pode mover o mob dezenas de blocos para cima no mesmo (x,z) e a aura nao reclama. Regra de jogo, **nao** corrigido - mudar exige aprovacao.
- **Ligar acao destrutiva por NOME sobrevive a reordenacao.** O botao X usa indice *visivel* (`skillAt(row) = skillScroll + row`) e a ficha e reordenavel, mas a guarda compara nome, entao um scroll entre os dois cliques **arma a skill visivel nova** em vez de remover a errada. Nomes sao unicos por ficha (`withSkill` atualiza a existente, `sanitize` dedupe, `withoutSkill` remove so a 1a ocorrencia).
- `pendingRemoval` (confirmacao de 2 cliques no X) desarma em `selectSkill`, `onSheetReceived` e `close()`. **ESC nao passa por `close()`** - a tela e descartada, e a arma morre com o objeto; so e seguro porque `StatusScreen`/`RpgMenuScreen` sempre constroem `new SkillsScreen(...)`.
- **`javap` nao esta no PATH** desta maquina: o `java` do PATH e o stub do Oracle em `Common Files\Oracle\Java\javapath` (que **nao** tem `javap.exe`). Usar o caminho completo de um JDK real: `C:\Program Files\Java\jdk-21.0.12\bin\javap.exe`.
- O jar de classes comum esta em `minecraft-common-1.21.11-loom.mappings...jar`; o `minecraft-clientonly` **nao** contem `CollisionGetter` nem `ServerLevel`.

## Placa de pressão: `.noCollision()` junto com `.forceSolidOn()` (26/09/2026) - FATO verificado em bytecode
- **A placa de pressão tem a forma de colisão VAZIA e mesmo assim É chão.** Em `Blocks.java` a placa é declarada com `.noCollision()` E `.forceSolidOn()` ao mesmo tempo. Consequências: `hasCollision == false`, então `getCollisionShape(...)` devolve `Shapes.empty()`; e `forceSolidOn` faz `calculateSolid()` responder `true`, então `blocksMotion()` é `true` e o heightmap `MOTION_BLOCKING_NO_LEAVES` **conta** a placa.
- **Por isso o ramo "forma de colisão não vazia -> topo dela" não pega a placa, e o fallback no heightmap devolve Y+1, que é o mob boiando.** O usuário viu isso no teste de 26/09/2026. A saída é um ramo intermediário com a **forma de SELEÇÃO** (`getShape(level,pos)`), que existe: `Block.column(14, 0, 1.0)` solta e `Block.column(14, 0, 0.5)` pressionada, ou seja topo em 1/16 e 8/16. O teste `blocksMotion()` é o que separa a placa de um decorativo como a grama, que também tem forma de seleção mas não é chão.
- **`VoxelShape.bounds()` LANÇA `UnsupportedOperationException("No bounds for empty shape.")`** em forma vazia. Todo `bounds()` tem de ser guardado por `!isEmpty()` antes, e o `&&` do Java faz o curto-circuito. Não é defensivo: é obrigatório, e a placa é justamente o caso que dispara.
- `PressurePlateBlock` e `WeightedPressurePlateBlock` **não sobrescrevem** `getCollisionShape`; herdam o default de `BlockBehaviour` que respeita `hasCollision`. Em 1.21.11 `SHAPE_NECK`/`SHAPE_BASE` não existem mais, são `SHAPE` e `SHAPE_PRESSED` em `BasePressurePlateBlock`. A placa de pedra usa `POWERED` booleano, não `POWER`.
- **LadderBlock**: não sobrescreve `getCollisionShape` e não tem `.noCollision()`, então a forma é a do `getShape` (`Shapes.rotateHorizontal(Block.boxZ(16,13,16))`), uma fatia de 3 px colada na parede, Y de 0 a 16. `FACING` aponta **para longe** da parede (para `NORTH`, a parede está a +Z, forma em z 13..16). A caixa de um mob de largura normal (0.2 a 0.8) não encosta na fatia (0.8125 a 1.0), então o check de AABB aceita; mob largo (ravager, ghast) é recusado, o que está correto.
- **Nada prende entidade numa escada com NoAI**: `Entity.collide` devolve o vetor intacto quando `lengthSqr()==0` e `LivingEntity.handleOnClimbable` só é chamado de dentro de `travel`, que não roda sem IA efetiva. Por isso o mob fica pendurado no ar sem `setNoGravity` nem `noPhysics`. E **não existe animação de escalada para mobs**: `Pose` não tem `CLIMBING` e `HumanoidModel.setupAnim` não menciona escalada. Fazer o mob levantar os braços exigiria mixin no renderizador.
- `ScaffoldingBlock.getCollisionShape` **não** é vazio: com `CollisionContext.empty()` o `isAbove()` retorna sempre `true`, então devolve `SHAPE_STABLE` (topo Y=16/16) e o mob fica em cima. O campo `withCollidingWithScaffolding` **não existe** em 1.21.11.
- `getCollisionShape` neste mappings exige **três** argumentos: `getCollisionShape(BlockGetter, BlockPos, CollisionContext)`. Com um só não compila. E `Blocks` fica em `net.minecraft.world.level.block`, não `net.minecraft.world.level`.

## 2026-09-26 — GitHub: publicacao, permissao de conta e envio de tags (FATO verificado)

Enviado ao GitHub com sucesso. `main` foi de `a53f26f` para `47e7577` e as tres tags
anotadas subiram.

**FATO verificado: `git push origin main` envia SO a branch, nunca as tags.** O push
reporta sucesso e as tags ficam locais, entao da para perder o versionamento no GitHub sem
perceber. Ao versionar, sempre acompanhar com `git push origin --tags` e conferir com
`git ls-remote --tags origin`. Confirmado com as tres tags `checkpoint-*` presentes no
remoto apos o segundo comando.

**FATO verificado: 403 que nomeia a conta e problema de PERMISSAO, nao de credencial.**
O erro real foi `remote: Permission to Pedr0U/tabletop-rpg-1.21.11.git denied to
Danylohcs`. O GitHub nomeia a conta que autenticou, logo a autenticacao funcionou; token
invalido ou expirado produz outra mensagem (`could not read Username`, `Invalid username
or password`). Nao adianta regenerar token nesse caso: o que falta e acesso de escrita.
Para conferir o proprio nivel: abrir o repositorio e olhar a aba People.

**FATO verificado: colaborador com permissao Read NAO envia commit.** O GitHub rotula
Read e Write igualmente como "colaborador", entao "sou colaborador" nao prova que o push
funciona. Read aparece como causa mais provavel do 403 deste projeto.

**FATO verificado: mudanca de permissao nao exige token novo.** Depois que o usuario
concedeu acesso, o push passou com a credencial antiga, sem reautenticar: a checagem de
permissao acontece a cada requisicao.

**Configuracao deste projeto.** Remoto `https://github.com/Pedr0U/tabletop-rpg-1.21.11.git`,
repositorio **PUBLICO** (verificado pela API anonima do GitHub em 26/09/2026). A conta que
faz o push e `Danylohcs`; o repositório pertence a conta `Pedr0U`, e `Danylohcs` precisou
ser adicionado como colaborador **com escrita**. A credencial fica no Windows via Git
Credential Manager (`credential.helper = manager`); `cmdkey /list` mostra o usuario
guardado. O GitHub Desktop tambem esta autenticado como `Danylohcs`, entao ele nao e
alternativa ao git CLI neste projeto. Nao extrair o token guardado: pedir ao usuario um
Personal Access Token digitado direto no terminal dele.

**FATO verificado: nao houve `push --force` em nenhum checkpoint.**

**Risco registrado:** como o repositorio e publico e o usuario autorizou publicar o
conteudo de `agent/`, o repositorio expoe o nome do usuario, o caminho local
`C:\Users\Danylo Henrique\Documents\GitHub\agent` e o processo do TCC. O e-mail
`danylohcs@gmail.com` ja consta como autor no historico enviado, o que e anterior a esta
sessao. Publicar `agent/` em repo publico deve ser decisao explicita do usuario, nunca
omissao por padrao.

**Pendente:** C02 e C03 estao no GitHub mas NAO foram validados em jogo. Placa de pressao,
escada, andaime e a mensagem de recusa seguem como "codigo pronto para testar". Correcao
de videira e camada de neve nao implementadas. `AuraRenderer.java:101` segue com
`getHeight(MOTION_BLOCKING, ...) + 1.05`, possivelmente ~1 bloco acima do mob apos a
mudanca de Y.

## FASE 3 parte 1 - resiliencia, aba de key binds e catalogo (27/09/2026) - FATO verificado por build + runClient

- Relatorio: `agent/reports/2026-09-27_fase3-parte1-resiliencia-keybinds.md`. Partiu do checkpoint `5fad0f8` / tag `checkpoint-20260927-1220-antes-da-fase3-resiliencia` (C04, **local, sem push**). Diff de 4 arquivos + 1 novo, sem commit.
- **NAO EXISTE lista de iniciativa/ordem de turno.** O turno e UM unico `SessionManager.activePlayerUuid` (`SessionManager.java:31`), sem fila e sem indice. "Preservar a ordem do turno" = preservar esse UUID. A iniciativa ordenada continua item de FASE 3 nao implementado.
- **MUDANCA DE COMPORTAMENTO no DISCONNECT (decisao do usuario em 27/09/2026):** removido o bloco que fazia `clearActivePlayer()` + `clearPlayerAnchor()` quando o jogador ativo caia. A vez fica **RESERVADA** e o servidor avisa no chat publico. O Mestre pula com `/rpg turn revoke` e passa a outro com `/rpg turn give`.
- **`/rpg turn revoke` e `/rpg turn finish` funcionam com o dono do turno OFFLINE:** leem `SessionManager.getActivePlayerUuid()` / `getActivePlayerName()` (`MasterCommands.java:232-237`, `:254-258`), nunca a entidade. Por isso "reservar a vez" nao prende a sessao: o Mestre sempre tem como destravar.
- O Mestre que desconecta **continua** perdendo o cargo, indo para `FREE` e resetando o `CombatController`. Decisao do usuario, mantida.
- **`RpgNetworking.sendAuraStateToPlayer(MinecraftServer, ServerPlayer)` (NOVO)**, chamado no JOIN. Antes a aura **nao** era reenviada e quem reconectava voltava sem a barreira azul ate o proximo `/rpg turn`.
- **ARMADILHA que eu introduzi e corrigi no mesmo dia:** fazer `sendAuraStateToAll` DELEGAR ao metodo por jogador chamava `CombatController.getAuraData(server)` uma vez **por jogador** num broadcast. O certo e montar o payload uma vez e so trocar o `send` por jogador (helper privado `sendAuraState`).
- **`gameModeOrdinal = -1` e sentinela de "sem sessao"** no DISCONNECT do cliente. `SpectatorCameraController.tick` compara `lastGameModeOrdinal != gameModeOrdinal` para distinguir "chegou meu turno" de "mestre trocou o modo da sessao"; **zerar faria o JOIN parecer troca de turno** e derrubaria a camera de quem so reconectou.
- **`SpectatorCameraController.reset()` (NOVO)** zera `targetIndex`, `active`, `wasLocked`, `lastGameModeOrdinal`, `freeCamInitialized`, `thirdPersonLookInitialized`, `freeCamPos`; **PRESERVA** `mode`, `orbitalEnabled`, `topDownZoom`, `transitionSpeed` (sao preferencia do jogador). **Por que `targetIndex` precisa zerar:** e um INDICE e o `entityId` do jogador muda a cada login, entao o indice guardado apontava para outra pessoa ao reconectar. Nao chama `deactivate` (exige `Minecraft`); o `tick` ja desliga quando `client.player == null`.
- **CORRECAO de fato antigo desta memoria:** **NAO existe `1024` em nenhum lugar de `src/`** (verificado com `Select-String`). As linhas "mestre 1024 blocos" das secoes "Aura (Fase 4)" e "Fluxo do mestre" estao **erradas**. O real: `sendHoverConfigToPlayer` (`RpgNetworking.java:1209`) manda `SessionManager.getHoverDistance()` **para todos, inclusive o Mestre**, e o padrao e **32** (`SessionManager.java:39`). O Javadoc em `RpgNetworking.java:234-238` e `MasterCommands.java:647-650` repete o "valor alto para o mestre" e segue desatualizado em relacao ao codigo.
- **KeyMapping com categoria propria (1.21.11, verificado com `javap`):** `net.minecraft.client.KeyMapping.Category` e `record Category(Identifier id)` e **`public static Category register(Identifier)`** e a API que cria a aba em Controles. Ela **lanca `IllegalArgumentException` em duplicata** e monta a ordem das abas. `label()` deriva a chave de lang como **`key.category.<namespace>.<path>`** — entao `Identifier.fromNamespaceAndPath("tabletop-rpg","rpg")` exige `key.category.tabletop-rpg.rpg` no `en_us.json`, senao a aba aparece com a chave crua (o mesmo problema do lang criado em 26/09).
- **Registre a categoria FORA do `try { } catch (Throwable)` que envolve as teclas:** esse catch mascararia a excecao e deixaria as duas teclas nulas.
- **A translation key de um KeyMapping e a chave com que o vanilla persiste o binding em `options.txt`** (`key_key.<translationKey>`). Mexer na translation key (`key.tabletoprpg.*`) **perde o binding do usuario**; por isso a aba nova trocou apenas o `Category`.
- `FUNCIONALIDADES-E-COMANDOS.md` na raiz do projeto: catalogo de referencia do estado atual (visao da sessao, 16 comandos, teclas, telas, regras, estado por conexao). **Nao e memoria nem relatorio**, por isso ficou fora de `agent/`.
- **BUG encontrado, documentado e NAO corrigido:** os botoes `-10`/`-1` da tela de rolagem montam expressoes como `d8-2`, e o parser do servidor so aceita `^(\d*)[dD](\d+)$` e `^\d+$` — **nao aceita modificador negativo** (`MasterCommands.java:422`, `:679`, `:731-733`). Modificador positivo funciona. Alem disso `/rpg` e `/rpg menu` abrem um menu **ASCII no chat**; a tela grafica `RpgMenuScreen` so abre pela tecla `R`.
- `/rpg roll` sem argumento mostra o cabecalho `Skills (...)` no chat, mas o conteudo sao as 20 **pericias** de `SheetData.PERICIAS_PADRAO`. Os 20 nomes no codigo sao em ingles (`Melee`, `Acrobatics`, `Diplomacy`, `Initiative`, `skill 0`...).
- **VALIDACAO:** `gradlew build --no-daemon` VERDE (14s, `scanEncoding` OK) e `runClient` VERDE ("Sound engine started" em `run/logs/latest.log`, as 2 teclas registradas, 0 crash reports, 0 erro de mixin). **NADA foi validado em jogo por mim:** a aba "Tabletop RPG" em Controles, a barreira azul ao reconectar, o turno reservado e o aviso de chat dependem do teste do usuario. O cliente foi encerrado ao fim da validacao.

### Catalogo: referencia por SIMBOLO, nao por linha (27/09/2026) - FATO

**O usuario decidiu:** `FUNCIONALIDADES-E-COMANDOS.md` passa a citar `Arquivo.java: simbolo`
(metodo, campo ou classe) e **nao** numero de linha. Medido antes da troca: **143 citacoes de
linha em 27 arquivos, em 411 linhas** - uma a cada ~3 linhas. As **200** citacoes (143 com
nome de arquivo + 57 soltas na forma `:NNN`) foram convertidas. **Zero numero de linha restou.**

**Por que isso importa (a armadilha ja tinha comprado duas feridas):** linha de codigo e
referencia que **decai sozinha** — qualquer edicao desloca a numeracao. No mesmo dia, (1) duas
secoes do catalogo ficaram factualmente erradas porque o parser de rolagem mudou embaixo delas,
e (2) as refs de `PlayerListScreen.java` apontavam para as linhas 290 e 448 num arquivo de
**111 linhas**. Nome de metodo/campo sobrevive a refatoracao; numero de linha nao.

**Armadilhas do gerador de simbolo (se isso for refeito):**
- `Get-SymbolName` tem de conferir o `=` **antes** do primeiro `(`. Se nao, em
  `private static final Pattern DICE_TERM = Pattern.compile(...)` devolve `compile` (o nome da
  fabrica) em vez de `DICE_TERM`. Esse bug vazou para o arquivo final e so apareceu na
  conferencia manual.
- Um matcher frouxo de "construtor" casa `if (...) {` e devolve `if`. **Blacklist de palavras
  chave** e obrigatorio. Produziu 5 artefatos `` , `if` `` que entraram no catalogo.
- A citacao solta `:NNN` significa "o arquivo citado antes" e o rastreamento por posicao
  **atribui ao arquivo errado** quando a celula cita dois arquivos. Exemplo real: na linha da
  `StatusScreen`, o `:448-451` solto vem logo apos `PlayerListScreen.java:89-90` e pertence a
  `StatusScreen.buildFooterExtra`. Onde isso atrapalha, escrever o nome do arquivo por extenso.
- **Varias citacoes dentro do mesmo metodo colapsam no mesmo simbolo.** Onde o documento
  distinguia pontos internos de um metodo, qualificar curto ("bloco do Mestre", "ainda em
  `init`, bloco `if (!isMaster)`") e melhor que repetir o simbolo.

**ERRO MEU QUE INVALIDA PARTE DESTA VALIDACAO:** eu **copiei o arquivo gerado sobre o
catalogo-fonte antes de conferir**. `FUNCIONALIDADES-E-COMANDOS.md` e **untracked** (nunca
commitado), entao o git nao restaurou a versao com numeros de linha. Depois disso o script leu
um arquivo ja convertido e passou a reportar "200 referencias" quando nao havia nenhuma
pendente — os diagnosticos seguintes estavam **falsos**. Um simbolo errado (`compile`) sobrou ate
o fim por isso.
**REGIAO:** ao gerar um arquivo **untracked** por script, escrever o resultado num **caminho
novo** e so promover depois de conferir. Nunca `Copy-Item` por cima da fonte.

**Verificacao automatica por regex NAO e confiavel neste documento:** duas tentativas deram 42 e
96 "erros" que eram falso positivo (o `:palavra` casava com prosa em portugues; `Skills`, `Back`,
`R`, `V` sao conteudo, nao citacao quebrada). **Conferencia de referencia aqui e tarefa de
leitura humana.** O `scanEncoding` tambem nao cobre este arquivo, porque ele esta na raiz do
projeto, fora de `src/` e `agent/`.

## Rolagem com subtracao e o texto do "mestre 1024" (27/09/2026) - FATO verificado por build + teste isolado

Relatorio: `agent/reports/2026-09-27_fase3-parte1-resiliencia-keybinds.md` (secao "CorrecoesZz depois do relatorio"). Decisoes do usuario em 27/09/2026: **manter** o comportamento do destaque (mesmo valor para todos) e corrigir **apenas o texto**; **corrigir no servidor** o bug do modificador negativo.

### ARMADILHA PRINCIPAL: `String.split` NAO inclui grupos capturados no resultado

- **FATO (verificado executando, nao de memoria):** `"d8-1".split("([+-])", -1)` devolve **`["d8", "1"]`**, e **nao** `["d8","-","1"]`. O `Pattern.split` **nao adiciona os grupos capturados ao array**. razonar o contrario e um erro natural, e a consequencia aqui seria silenciosa e grave: `d8-1` rolaria o dado e **descartaria o -1 sem dar erro nenhum** - resultado errado, que e pior que a recusa de hoje.
- **A forma que preserva o sinal** e `split("(?=[+-])", -1)` (lookahead): o operador fica no **comeco** do termo seguinte. `"d8-1"` -> `["d8","-1"]`; `"2d6+d4"` -> `["2d6","+d4"]`; `"d8--1"` -> `["d8","-","-1"]`. Detalhe: o `Pattern.split` **ignora match de largura zero no indice 0**, entao `"-2"` fica `["-2"]` (sinal no primeiro termo), e nao `["","-2"]`. Tratar o sinal com `startsWith` no inicio de cada termo cobre os dois casos sem posicao especial.
- **REGRA geral:** nunca assumir que o array devolvido por `split` contem os grupos capturados. Para conservar o operador, use lookahead (`(?=...)`), ou tokenize com `Matcher.find()`. **E valide a aritmetica com um programa isolado antes de confiar no diff** - foi exatamente assim que o bug apareceu.

### Parser de dados aceita subtracao (MasterCommands.rollDice)

- O `MODIFIER_TERM = "^\d+$"` (`:422`) e o `DICE_TERM` (`:421`) **nao mudaram**: o sinal e extraido antes de casar os padroes, entao so o `split` (`:683`) e o texto de exibicao mudaram.
- **`appendRollTerm(joined, sign, text)`** (`:770-782`) monta o separador `" §f- §b"` / `" §f+ §b"`. **O `text` tem de vir SEM sinal**, porque o separador ja carrega ele - passar `sign*mod` exibia `d8 [5] - -1`. **Excecao: o primeiro termo nao tem separador antes**, entao o helper recoloca o sinal (`"-2"` sozinho aparece `-2`).
- Sinal duplo e **recusado**: `d8--1`, `d8+-1` e `d8--` caem no erro de formula invalida, porque o termo fica vazio no meio da lista. `DICE_TERM`/`MODIFIER_TERM` seguem valendo `d0`, `101d6` e `d1001` como recusados.
- **O bug do "atributo negativo" NAO existe:** `rollSkill` (`:511-513`) soma direto em `long` (`die + pericia.value() + sheet.getNumeric(atributo)`) e **nunca monta string de formula nem chama `rollDice`**. Atributo negativo funciona. So ha um detalhe cosmetico: a linha do resultado imprime sempre `" + "`, entao sai `... + STR -3 = 11` (sinal duplicado na tela, numero certo). **Nao mexer sem o usuario pedir.**
- `rollDice` tem **2 chamadores** (`rollFormula:602` e `openRoll:621`) e nenhum outro uso no projeto. O resultado e devolvido por `return`; quem anuncia e o chamador, via `broadcast` (todos) ou `sendSystemMessage` (so o Mestre). **O mod nao tem payload de rolagem**, entao o cliente nao consegue ajustar o resultado - por isso "corrigir so na tela" era impossivel sem duplicar o RNG no cliente.

### VALIDADO EM JOGO pelo usuario em 27/09/2026 - FATO (evidencia: mensagem do usuario)

O usuario executou o teste e confirmou **"testei tudo e ta 100%"**. Portanto, a FASE 3
parte 1 (aba de teclas + resiliencia de reconexao) e a correcao do parser de subtracao
**estao funcionando de verdade**, nao so compilando:

- aba "Tabletop RPG" em Controles, e **atalho customizado preservado** apos mover a
  categoria - confirma na pratica a separacao "categoria mutavel / translation key
  imutavel";
- jogador desconecta no meio do turno: aviso de chat, **vez reservada** (outros ficam
  bloqueados) e liberacao por `/rpg turn revoke` **com o jogador offline**;
- **aura/barreira azul volta** quando o jogador reconecta - o bug original do JOIN;
- Mestre desconecta: perde cargo, sessao `FREE`, combate reseta;
- camera de espectador limpa o estado na reconexao;
- `/rpg roll` com subtracao, incluindo `-2` e sinais alternados, e os botoes `-1`/`-10`
  da `DiceRollScreen`; sinal duplo segue recusado.

**Consequencia para a proxima sessao:** nao reprotestar nada desta lista. O que sobrou
pendente de decisao do usuario sao **duis itens cosmeticos/arquiteturais** que ele nao
respondeu: (a) `rollSkill` imprime sempre `" + "`, entao com atributo negativo o chat
mostra `... + STR -3 = 11` (sinal duplicado, numero certo); (b) `rollDice` usa um
`new Random()` estatico (`:709`) em vez de `player.getRandom()` como o `rollSkill`. Os
dois foram registrados e **nao corrigidos**.

### Destaque: o "1024" era fiction do codigo e da memoria

- **Decisao do usuario: manter o comportamento** (todos, inclusive o Mestre, recebem `SessionManager.getHoverDistance()`, padrao 32) e **corrigir so o texto**. Os Javadocs de `RpgNetworking.HoverConfigPayload` (`:234-243`) e de `MasterCommands.setHoverDistance` (`:647-655`) foram reescritos e agora dizem que o valor vai para todos. A memoria ja tinha a correcao; aqui fica o registro de que os **comentarios do codigo tambem mentiam**.

---

## 2026-09-27 — CORRECAO de um registro anterior, e a skill `catalogo-sync`

### CORRECAO: a afirmacao sobre `PlayerListScreen.java` neste arquivo estava errada

Acima, na secao "Por que isso importa", este arquivo afirma que as refs de
`PlayerListScreen.java` "apontavam para as linhas 290 e 448 num arquivo de 111 linhas".
**Isso e falso.** O `:448-451` solto da tabela do catalogo pertence a `StatusScreen`, que era
o assunto da secao, e o catalogo estava **correto**. O erro foi do meu script de verificacao:
ele amarrou a citacao solta `:NNN` ao nome de arquivo anterior mais proximo
(`PlayerListScreen.java`) e fabricou uma linha fora do arquivo. O texto logo acima deste
bloco, na bullet "A citacao solta `:NNN` significa...", esta CORRETO e e a explicacao real.

**FATO verificado:** a bullet sobre `:NNN` atribuir ao arquivo errado e verdadeira e ja
explica o caso. O que estava errado era so a frase que apresentava isso como defeito do
catalogo em vez de defeito da verificacao.

**Licao duravel (vale mais que o erro):** regex que "confere" documentacao em prosa produz
falso positivo com mais seguranca do que gente. Minhas duas tentativas acusaram 42 e depois
96 itens inexistentes, e nearly-convenceu-me de apagar documentacao correta. **Nao apague
conteudo do catalogo com base em relatorio de regex; confirme com leitura.**

### Skill global `catalogo-sync` + detector

O usuario pediu para a manutencao do catalogo nao depender de esquecimento. Decisoes:
skill **global** (com as outras do TCC), e o detector **so avisa, nunca falha o build**.

- `C:\Users\Danylo Henrique\.config\opencode\skills\agente-tcc\catalogo-sync\SKILL.md`
- `C:\Users\Danylo Henrique\.config\opencode\skills\agente-tcc\catalogo-sync\check-catalogo.ps1`

**FATO verificado (números reais do código, 27/09/2026):** `onRegister` tem **23
`Commands.literal(`, 19 `.executes(` e apenas **17 caminhos executáveis distintos**. A
diferença entre 19 e 17 é que `.executes()` tambem aparece em ramos de
`Commands.argument(...)` (`/rpg time`, `/rpg hoverdistance`, `/rpg session set`).

**Regra que o detector aplica, e por que:** um literal sem `.executes()` e literal-**pai**
(`/rpg master`, `/rpg mode`, `/rpg turn`, `/rpg insert`, `/rpg remove`): serve so de
agrupador, nao e digitavel. O catalogo os documenta de proposito como cabecalho de grupo.
Portanto "comando obsoleto" = citado no catalogo e **ausente do codigo inteiro**; "nao
documentado" = executavel no codigo e ausente do catalogo. Filtrar so por `.executes()`
acusaria 4 falsos positivos; acusar so por `.executes()` acusaria 2 a mais.

**FATO verificado:** o catalogo tem **19 linhas de tabela de comando** = 17 executaveis + 2
literais-pai (`/rpg mode`, `/rpg turn`). Os numeros fecham.

**Armadilhas do detector (corrigidas na propria implementacao, nao por sorte):**
- O regex do literal precisa de `^\s*` senao falha no `register(` que quebra a linha, e a
  raiz `/rpg` nunca entra na pilha - todos os caminhos perdem o prefixo.
- Codigo produz caminho com `/` e o catalogo escreve com espaco; sem canonicalizar e
  normalizar, daham 51 divergencias fantasma (22 + 27).
- O catalogo esta em portugues e descreve a tecla pelo **efeito** ("Abre o menu grafico"),
  nunca pelo rotulo em ingles nem pela translation key. Buscar `key.tabletoprpg.menu` da
  falso positivo por natureza. O sinal confiavel de tecla e a **contagem** de linhas da
  tabela contra o numero de `new KeyMapping(`, nao busca de texto.
- Permissao nao e automatizavel: `MasterCommands.java` nao tem **nenhum** `.requires(`; a
  checagem de Mestre esta dentro dos handlers.

**Limitacao conhecida e aceita:** remover uma linha da tabela **nao** dispara alerta se o
comando continuar citado na prosa. O detector mede **cobertura** (o comando esta
documentado?), nao formatacao. Isso e proposital.

**HIPOTESE/limite:** o catalogo esta na raiz do repo, fora de `src/` e `agent/`, entao a
task `scanEncoding` (77 arquivos) **nao o verifica**. Conferido a parte: 33.160 bytes, sem
BOM, 0 U+FFFD. Se isso mudar, vale um `scan` explicito para o catalogo.
---

## 2026-09-27 — Background, pericias de D&D 5e e botoes -/+ (partes 1 e 2 da ficha)

**FATO verificado (build):** `.\gradlew.bat build --no-daemon --console=plain` = `BUILD SUCCESSFUL`
compilando **os dois** source sets. `scanEncoding` OK (77 arquivos, 0 mojibake). Detector de
catalogo `EXITCODE=0`. **Nada disso valida a tela:** render e clique so existem no jogo.

**FATO verificado (codigo):**
- `SheetData.Pericia` tem teto 30 e piso 0. `SheetData.Attributes` tem teto 30 e **sem piso**
  (`Integer.MIN_VALUE`), para o `-` nunca travar a volta de um valor muito negativo.
- `StatusScreen.PER_COUNT` deriva de `SheetData.PERICIAS_PADRAO.size()` — nao ha mais numero
  magico. A lista tem 20: `Initiative`, `Melee` e as 18 de D&D 5e.
- `Background` entrou como 4o campo de `SheetData.Identity` (record, `STREAM_CODEC` posicional,
  NBT opcional, `withField`, `labelOf`, `TEXT_FIELDS`). **Nao estourou o limite de 6 grupos do
  codec de topo de `SheetData`**, que era o risco. E um rotulo sem efeito mecanico.
- `StatusScreen.valueBoxWidth()` dimensiona a caixa pelo teto. **Corrigido bug real:** antes a
  largura era fixa e `font.plainSubstrByWidth` cortava o texto, escondendo o segundo digito
  (`10` aparecia como `1`). Nao voltar a cortar texto numerico nesta tela.
- `StatusScreen.fieldLabelWidth()` mede o rotulo **realmente mais largo** em vez de `leftW/5`.
  **Motivo:** `addField` desenha o rotulo sem cortar, e uma fracao fixa aceitava "Class" mas
  nao "Background", que invadia a caixa em janela estreita.
- Cabecalho `Bonus` em `addBonusHeader`, com `y = topY + perTitleH - 8`: a primeira linha de
  pericia comeca em `topY + perTitleH` e o texto dela fica ~1px abaixo, entao o cabecalho
  encosta em cima sem invadir.

**FATO verificado (leitura, nao execucao) — armadilha de colisao em `sanitizePericias`:**
`Diplomacy` e `Diplomacia` viram os dois `Persuasion`. Vence o **primeiro na lista salva**, nao
o primeiro apelido. Na pratica favorece o ingles, porque a lista padrao de 26/09/2026 gravou
`Diplomacy` antes de existir traducao. Perde o valor so quem editou `Diplomacy` em ingles E ainda
tinha `Diplomacia` salvo com outro numero. **Aceito de proposito:** a alternativa seria somar dois
numeros diferentes na mesma celula. Regra escrita em comentario no metodo.

**ARMADILHA CONFIRMADA (tenta de novo custou passos):** nao da para exercitar `SheetData` numa
JVM isolada. O `SheetData.<clinit>` monta `StreamCodec`, que precisa de
`net.minecraft.network.codec.ByteBufCodecs`, e o **jar mapeado do Minecraft nao existe como
arquivo** no cache do Gradle: em `.gradle/caches/fabric-loom/1.21.11` so ha
`minecraft-common.jar` / `minecraft-server.jar` / `minecraft-client.jar` **crus, com nomes
ofuscados**, e `ByteBufCodecs.class` nao foi achado em nenhum jar de `.gradle` nem de `build/`.
Logo, **teste unitario de `SheetData` so roda dentro do jogo** (ou com classpath montado a mao).
A alternativa de reescrever a logica num `main` isolado **testa uma copia, nao o codigo** — nao
serve como prova.

**DECISAO DO AGENTE, nao DO USUARIO:** manter `Melee` e `Initiative` alem das 18 de D&D
(lista = 20). As duas primeiras fases de gameplay dependem delas, mas o usuario pediu "as 18".
**Pendente de confirmacao.**

**FORA DO ESCOPO PEDIDO, FEITO DE PROPÓSITO:** HP e Mana tambem trocaram `>`/`<` por `-`/`+`,
por consistencia visual. O usuario pode rejeitar.

**HIPOTESE (nao verificada):** o `-` de atributo nunca desliga (piso e `Integer.MIN_VALUE`), o
que deixa o par de botoes assimetrico em atributo muito negativo. Foi escolha consciente para
nao travar a volta de `-25`. Confirmar com o usuario se ele prefere um piso visivel.

### CORRECOES do mesmo dia (revisao independente do diff)

**FATO verificado:** a lista ficou so com as **18 basicas de D&D 5e**. `Initiative` e `Melee`
**sairam**. Motivo conferido no codigo: `Initiative` nao e lida em **nenhum** lugar de `src/` (o
modo combate nao consulta a ficha para ordenar turnos) e `Melee` so duplicava `Athletics` (FOR).
**Decisao do usuario** entre "manter 20" e "so as 18": **so as 18**.

**FATO verificado — armadilha de layout (a mais importante desta sessao):**
espaco util da `Status` = `altura da tela - 86`; o total de linhas e `neededRows * rowH`; e
`fitRowHeight` tem **piso duro** (`MIN_ROW_H = 12`, em `CharacterSheetScreen`). Com o piso
ativo, **a coluna esquerda estoura o painel**. Em 480x270 (escala GUI automatica padrao em
1080p) eram 18 x 12 = 216 para 184: estouro de 32px e as **duas ultimas linhas caiam em cima
do botao `Back`**, que e desenhado depois. **Corrigido** removendo a "folga de 4" do
`neededRows`: a folga **nao e desenhada**, e como o espaco e dividido pelo total de linhas ela
so aumentava o total — que e exatamente o que estoura. Sem folga: 16 x 12 = 192, estouro de
8px, dentro do painel. Em janela normal `rowH` bate em `MAX_ROW_H` nos dois casos, entao nada
muda visualmente.
**Em 426x240 (escala padrao em 720p) continua estourando: 192px para 154px disponiveis.**
Decisao do usuario em 27/09/2026: **deixar como esta**. Ja estava quebrado antes (4px).
**NAO FAZER:** nao mexer no piso de `MIN_ROW_H` sem o usuario pedir — ele awareu as opcoes
(piso menor = linhas de 9px e botoes pequenos; ou rolar a coluna esquerda) e escolheu nao mexer.

**FATO verificado — `Attributes` trunca em silencio:** atributo legado > 30 vira 30 na carga e o
NBT e reescrito, sem log. Mesma doutrina de "mudar limite sem log e armadilha" que a memoria ja
registra. Tratar como pendencia se algum dia incomodar.

**FATO verificado:** `SheetData` **nao da para testar em JVM isolada.** O `<clinit>` monta
`StreamCodec`, que precisa de `net.minecraft.network.codec.ByteBufCodecs`, e o **jar mapeado do
Minecraft nao existe como arquivo**: em `.gradle/caches/fabric-loom/1.21.11` so ha
`minecraft-common.jar` / `minecraft-server.jar` / `minecraft-client.jar` **crus (ofuscados)**, e
`ByteBufCodecs.class` nao existe em nenhum jar de `.gradle` nem de `build/`. Logo **teste de
`SheetData` so roda dentro do jogo**. Reescrever a logica num `main` isolado **testa uma copia**,
nao serve de prova. Ja tentei e gastei passos com isso.

**ARMADILHA CONFIRMADA (o proprio agente caiu nela):** `Get-Content` do PowerShell 5.1 le
arquivo UTF-8 como ANSI e **mostra mojibake no terminal mesmo com o arquivo integro**. Use a
ferramenta `read` para obter texto exato com acento antes de editar com `edit`; se usar
`Get-Content` para conferir string, o `oldString` vai falhar.

**ERRO PROPRIO, duas vezes, para nao repetir:** ao editar o catalogo, dois ideogramas chineses
(U+8C03 e U+6574) entraram no meio de uma frase em portugues. O `scanEncoding` e o que pega —
ele conta ideogramas e **falha o build**. Rodar `.\gradlew.bat scanEncoding --no-daemon` depois
de editar markdown, nao so depois de codigo.
**Armadilha do proprio registro:** ao documentar esse erro aqui, copiei os caracteres de novo e
o build voltou a falhar. **Nao colar o caractere proibido em nenhum arquivo do repo**, nem
como exemplo. Descrever com o nome do code point.

**FATO verificado:** um subagente `tcc-validador` em modo somente leitura acha coisa que o
agente principal nao acha, **desde que o pedido mande explicitamente**: nao editar, nao rodar
build, nao escrever memoria, e pedir as 4 categorias com "se nao achou, diga". Ele achou a
folga falsa do `neededRows`, um bloco de documentacao falsa em 4 arquivos, e checou a
aritmetica de layout linha por linha. **Ele tambem errou** (supôs que `+` em 30 mostraria 31;
nao mostra, porque o botao ja esta inativo) — entao revisar o relatorio dele tambem.

**DOCUMENTACAO CORRIGIDA nesta sessao (todas as afirmacoes eram falsas depois da mudanca):**
`SheetData` "atributos nao tem faixa", `MAX_STAT` "o clamp foi removido", "identity (3)",
a justificativa de que `background` foi posto no fim "para nao quebrar cliente de versao
anterior" (**nao ha negociacao de versao; um cliente de 3 campos leria a 4a string como
`VAR_INT` de `Vitals` — o layout do pacote mudou de qualquer jeito, a posicao e indiferente**),
`rollSkill` "le VALUE_MAX" (**nao le**), "Migration sem perda" (**pode perder: `skill N` tinha
`-`/`+` editavel na tela antiga**), `MasterCommands` "atributo nao tem teto",
`CharacterSheetScreen` "atributos nao tem teto", `StatusScreen` "setas `>` `<`" e "sem limite".
Regra: **quando o comportamento muda, procurar o texto que descreve o comportamento antigo.**
Vários desses textos eram Javadoc de 26/09, nao deste diff.

## `SheetModelStore`: `get` vs `computeIfAbsent` (27/09/2026) - FATO verificado por crash real

- **FATO (crash real, `Exception in server tick loop`):** `SheetModelStore.get(MinecraftServer)`
  usava `server.overworld().getDataStorage().get(TYPE)`. `DimensionDataStorage` tem
  **DUAS** sobrecargas e elas diferem em **comportamento**, nao so em assinatura:
  - `get(SavedDataType)` e **so busca**: devolve `null` quando o dado nunca foi gravado.
  - `computeIfAbsent(SavedDataType)` **cria** o dado, usando a factory que o proprio
    `SavedDataType` carrega.
  Como `null` e um retorno legal para `get()`, o bug **nao aparece em tempo de compilacao**:
  build verde com 8/8 testes. O NPE estourava em `SERVER_STARTED`, primeira linha do handler,
  em **todo** boot de um mundo novo. Fix: `computeIfAbsent(TYPE)`.
- **COMO CONFIRMAR QUE O DADO NUNCA FOI GRAVADO:** procurar `tabletop*.dat` em
  `saves\<mundo>\data\`. Se nao existe, `get()` devolve `null`. Foi o que is confirmava:
  0 arquivos em 5 mundos. Se o `.dat` existir e o crash continuar, o problema e o codec,
  nao a obtencao do dado.
- **FATO:** neste mappings o `SavedDataType` recebe a factory no construtor
  (`new SavedDataType<>(id, Supplier, codec, DataFixType)`), entao a sobrecarga de **um**
  argumento do `computeIfAbsent` basta. Nao existe overload com `Supplier` separado.
- **REGRA GERAL:** `javap` prova que uma assinatura **compila**, nunca que a
  implementacao escolhe a sobrecarga com a semantica correta. Documentacao que registra
  *que* uma API foi checada, sem registrar *qual comportamento* ela garante, produz
  falsa confianca.
- **O Javadoc da propria classe afirmava "API verificada com javap no jar do Loom (1.21.11)"**
  e estava factualmente correto - mas so tinha conferido o TIPO do parametro. O Javadoc foi
  ampliado com a distincao `get`/`computeIfAbsent` e o motivo do crash, para o proximo nao
  cair no mesmo.
- **REGRA DE DIAGNOSTICO:** a mensagem do NPE dizia qual variavel era nula (`"store" is null`).
  Isso apontava a camada de **obtencao do dado**, nao o codec. A hypothesis anterior (codec
  assimetrico) era plausivel e explicava o sintoma errado. Mensagem de excecao vence hypothesis.

## Como saber QUAL jogo o usuario testou (27/09/2026) - corrige nota de 26/09

- **A NOTA de 26/09 ("o usuario testa com `runClient`, nunca jar instalado") esta
  DESATUALIZADA.** Em 27/09/2026 o crash report do usuario foi gravado em
  `%APPDATA%\.minecraft\crash-reports\crash-2026-09-27_20.17.26-server.txt`, o que prova
  que **aquele** jogo rodou com o JAR instalado em `%APPDATA%\.minecraft\mods\`. Havia
  tambem crashes anteriores em `run/crash-reports\` (20:16:26 e 20:17:06), ou seja o
  `runClient` tambem foi usado no mesmo dia. **Os dois caminhos estao em uso.**
- **COMO DESCUBRIR sem perguntar:** o diretorio onde o crash report e gravado identifica o
  game dir. `run/crash-reports/` = `runClient`. `%APPDATA%\.minecraft\crash-reports\` =
  jogo com jar instalado. Conferir ANTES de dizer "rode `runClient`".
- **CONSEQUENCIA:** copiar `build/libs/tabletop-rpg-1.0.0.jar` para
  `%APPDATA%\.minecraft\mods\` e o passo correto quando o crash veio de la. Quando o teste
  for `runClient` a copia e inofensiva, mas nao substitui rodar `runClient`.
- **FATO:** o mundo do crash de 27/09 se chamava `map_client.txt` e **nao** aparece em
  `%APPDATA%\.minecraft\saves` (que tem 'a mn vaitomarnocu', 'IAHSD LKJASGHRFLJKASHF',
  'New World', 'New World (1)'). Nao assumir que `saves/` lista o mundo do crash report.

## `?` no console NAO e mojibake (27/09/2026)
- Ao ler `agent/memory/project-memory.md`, acentos latinos legitimos aparecem como `?` no
  console (ex.: "Varios" -> `V?rios`). **Verificar por codepoint antes de "consertar".** Os
  caracteres realmente corrompidos sao U+FFFD ou bytes soltos na faixa 0xD0-0xDD. Nesta
  sessao o texto estava SAO e quase apaguei acentos legitimos. Regra ja esta no AGENTS.md;
  aqui so o caso concreto.

---

## 2026-09-27 - Correcao dos 7 bugs de ficha/editor (commit 7777eb7 + esta rodada)

Contexto: o usuario deu pull no commit `7777eb7` ("Item de Personalizacao da Ficha", 2902
linhas, com `SheetModel` novo) e reportou 7 defeitos em teste no jogo. Corrigidos aqui.

### FATO verificado - armadilhas de Java no Minecraft UI

**Parametro de metodo capturado em lambda e CONGELADO por valor.** Foi a causa de 2 bugs com
sintoma enganoso (o widget responde ao clique, entao "parece funcionar"):
- `toggleField` usava `boolean next = !current;` com `current` como parametro -> todo clique
  enviava o mesmo booleano. Sintoma: toggle "so muda 1 vez".
- `periciaRow` usava `String originalName` capturado no `setResponder` -> apos a 1a tecla
  `periciaByName(originalName)` devolvia `null`, o early-return revertia e o EditBox voltava ao
  nome antigo. Sintoma: "so aparece a primeira letra".
Padrao correto: holder mutavel (`boolean[] on = {current}`), igual ao `xpModeField` do mesmo
arquivo, que ja ciclava certo. Contraste dentro do proprio arquivo foi a prova.

**Valor de retorno de `addRenderableWidget` descartado = bug silencioso.** Sem campo para o
widget, nao ha onde atualizar `active`. O botao Salvar ficava sempre ligado.

### FATO verificado - `active` de widget e questao de ORDEM, nao so de logica

`button.active` precisa ser decidido em **UM** lugar, e esse lugar tem de rodar **antes** de
`super.render()`, que e quem desenha os widgets. Dois autores no mesmo campo, em frames
diferentes, produz 1 frame de divergencia visivel (botao branco e depois cinza).

Armadilha especifica deste projeto: a superclasse `CharacterSheetScreen` escreve `active` em
`renderContent`, que roda **DEPOIS** de `super.render()`. A subclasse `SheetEditorScreen` nao
extende ela (extende `Screen` direto) e sobrescreve `render`. Os dois pontos tem um frame de
atraso diferente, e a ordem importa. `Screen.render` **nao e final** em 1.21.11.

Piscar de botao era isto: eco do servidor -> `onSheetState` -> `applyExtraState` reativa tudo
(inclusive o `-` travado em 0) -> frame seguinte o render desativa. Como qualquer campo editado
dispara o eco, o usuario via piscar "ao interagir em qualquer lugar".

### FATO verificado - largura de texto em pixel

`valueBoxWidth()` media `Integer.toString(teto)` = `"30"` = 16px, e esqueca o sinal: `"-30"` =
18px nao cabia. Atributo tinha guarda `plainSubstrByWidth` e cortava para `-3`; pericia nao
tinha guarda e invadiria 1px. **Sempre medir o pior caso real com `font.width()`**, nunca
assumir um literal. Font vanilla = 6px por char. As guardas de truncamento continuam obrigatorias
porque o valor pode vir de payload forjado (o servidor recorta, mas o cliente precisa se
defender visualmente). So da para conferir isso em jogo: `font.width` e runtime.

### FATO verificado - `UseItemCallback` nesta versao

Assinatura e `(Player, Level, InteractionHand)`: **3 params, NAO ha `ItemStack`**. O stack vem
de `player.getItemInHand(hand)`. Assinatura com 3 params e `ItemStack` no lugar do `Level`
produz `incompatible types: Level cannot be converted to InteractionHand`.

### FATO verificado - `StreamCodec.composite` aceita 12 campos

Corrige Javadoc antigo que dizia 6 (achado na pesquisa do `SheetModel`). Nao ha limite 6.

### DECISAO do usuario (27/09/2026) - limites de valor

- **Atributo: teto 30 e PISO -30.** O piso ja existia no `7777eb7` (`SheetData.Attributes.VALUE_MIN`),
  mas o botao `-` nunca desligava, entao dava para pedir -31 e o servidor cortava, fazendo o
  numero piscar. Agora os DOIS botoes desligam no limite.
- **Pericia: 0 a 30.** Perguntado explicitamente; o usuario escolheu manter o piso 0.

Lembrar: antes do `7777eb7` o atributo era "sem piso" (`Integer.MIN_VALUE`). Drift de doc
corrigido nesta rodada em `SheetData.java`, `CharacterSheetScreen.java` (Javadoc de
`saturatingAdd`) e `MasterCommands.java` (Javadoc de `rollSkill`).

### FATO verificado - identidade de pericia: POSICAO, e o por que importa

`SheetModel.PericiaDef` e `record PericiaDef(String name, String attributeId)`: **nao tem id**.
O conserto do bug de rename usou **posicao na lista** (`periciaAt(pos)`) como identidade, e nao
o nome. Posicao sobrevive a `withPericiaText`, `removePericia`, `removeAttribute` e reordenacao
(verificado). Atributo tem id, poricia nao. Se um dia entrar id em `PericiaDef`, e schema + codec
+ compatibilidade de save: mudanca de alto impacto, precisa de aprovacao.

### PENDENTE, nao resolvido - perda de valor ao renomear pericia (achado I3)

`SheetModel.align` identifica a pericia por **NOME** (`periciaByNameOrLegacy`). Usado por
`SheetData.aligned()` e por `PlayerSheetPersistenceMixin`. Ao salvar modelo com nome novo: nao
casa -> `found == null` -> a pericia **perde o valor (vai a 0) e perde o atributo escolhido pelo
Mestre**. Pre-existente, travado num teste que passa
(`SheetModelCodecTest.renamingPericiaIsALossyIdentityChange`) e documentado como limitacao
conhecida. Nao e regressao, mas o conserto do bug 3 deixa o rename mais confiavel, o que aumenta
a chance de o Mestre renomear e salvar. **Aguardando decisao do usuario.**

Tambem: `stepNumeric`/`saturatingAdd` na superclasse seguem **sem clamp**. O piso -30 e
garantido so pela UI. Hoje nenhum `addField` aponta para atributo, entao esta fechado; outro
caminho que escrevesse atributo reabriria o piscamento.

### FATO verificado - o catalogo precisa entrar no mesmo commit da feature

`FUNCIONALIDADES-E-COMANDOS.md` nao era FINDO por causa desta rodada: o `7777eb7` trouxe 2902
linhas de codigo novo (item, `SheetEditorScreen`, `SheetModel`, `SavedData`) e **zero** linhas de
catalogo. Busca confirmou zero ocorrencias de `SheetModel`, `sheet_editor`, `Sheet Editor`.

Falsidades adicionais que a leitura do codigo revelou (alem das que a rodada já corrigia):
- **`SheetData.PERICIAS_PADRAO` NAO EXISTE MAIS.** 4 citacoes do catalogo apontam para simbolo
  removido. O padrao das 18 vive em `SheetModel.defaults()`.
- O enum de 6 atributos foi removido; `AttributePickerScreen` agora usa os atributos do modelo
  (1 a 10, com scroll).
- "o unico bloco gravado em disco e a ficha" era falso: `SheetModelStore` (SavedData do
  overworld) **sobrevive a restart**.
- O detector `check-catalogo.ps1` so acusa comando, tela e tecla. Ele **nao acusa regra de jogo
  nem feature nova**: os 4 itens acima passaram com `DIVERGENCIA: 0`. Regra e feature nova exigem
  leitura.

Regra da skill que evito erro: citar **simbolo**, nunca numero de linha. Os literais fixos
`"Level"`/`"XP"` na lista de `fieldLabelWidth` ignoravam o rename do Mestre; agora vem de
`SheetData.LABELLED_FIELDS` + `SheetModel.labelOf`.

### PAPEL do validador nesta rodada

Achou 0 bloqueadores e 4 importantes em 7 fixes. Dois que valem como metodo: ele refez uma
afirmacao minha (disse que os testes nao cobriam nada; `SheetModelCodecTest` fixa os invariantes
de modelo em que o bug 3 se apoia) e checou assinatura real com `javap` em vez de confiar no
relato do implementador. Sempre delegar verificacao apos alterar.

### Pendencia de rodape do editor

Largura fixa de 418px (4 botoes de 100 + 3 folgas de 6) quebrava abaixo de 418px de largura GUI:
1600x900 com escala 4 (width 400) dava `x = -9`; 1920x1080 escala 5 (width 384) dava `x = -17`.
Corrigido com `span = min(colW(), width)`, encolhendo `bw` e `gap`. **Lembrar: usar `colW()`,
nunca largura fixa em pixel, em widget de tela.**

### PLANO APROVADO, NAO EXECUTADO - id estavel em PericiaDef (decisao do usuario 27/09/2026)

O usuario APROVOU a correcao de perda de dado ao renomear pericia, mas pediu para executar
**depois** do teste em jogo dos 7 bugs. Nao comecar sem novo pedido. Resumo do plano para
retomar:

Variante aprovada (a-prime): `String id` em `SheetModel.PericiaDef` **e** em
`SheetData.Pericia`, espelhando o que `AttributeDef` ja faz. Derivar o id no construtor
compacto **so quando vier vazio**, a partir do nome normalizado e limitado a 32 chars (como
`attributeId` faz). A condicao "so quando vazio" e o que impede a recauda: `withPericiaText`
passa a repassar `def.id()`, entao o rename nao re-deriva nada do nome novo.

8 arquivos, ~30 pontos, uma rodada: `SheetModel.java`, `SheetData.java`, `RpgNetworking.java`,
`StatusScreen.java`, `SheetEditorScreen.java`, `AttributePickerScreen.java`,
`SheetModelCodecTest.java`, `FUNCIONALIDADES-E-COMANDOS.md`.

Call sites a trocar de nome para id: `SheetData.withPericiaValue`, `SheetData.withPericiaAttribute`,
`SheetModel.withPericiaText`, `SheetModel.removePericia`. **`MasterCommands.findPericia` fica por
nome de proposito** (e o design de `/rpg roll <nome>`).

Atalho que corta ~21 edicoes: como o id e derivado no construtor, as 18 linhas de
`SheetModel.defaults()`, o `"Skill N"` e o reparo de atributo ficam intocadas.

Riscos que sobraram (todos confirmados em leitura):
- **`optionalFieldOf("id","")` e obrigatorio, NUNCA `fieldOf`.** `Attributes.AttributeValue.CODEC`
  usa `fieldOf("id")` e, como o grupo tem alternativa legada com todos os campos opcionais, um
  registro ruim **nao da erro**: vira zeros em silencio. Nao copiar aquele padrao.
- Falha de parse do `SavedData` e engolida (`Failed to parse saved data for ...`) e cai no
  factory, o que dispara `realignAllSheets` e reescreve todas as fichas. Pior resultado possivel.
- **Id precisa ser deterministico entre boots**: nao ha `setDirty()` no `SheetModelStore.get()`,
  entao o modelo antigo em disco nao e reescrito no upgrade. Geracao variavel entre boots zera as
  fichas no proximo `align`.
- Quebra de layout de pacote: aceitavel porque o projeto ja exige cliente e servidor na mesma
  versao (codec posicional da identidade).
- `LEGACY_PERICIA_NAMES` **fica**: 6 fichas reais em `run\saves\New World\playerdata` usam
  `Diplomacia`/`Acrobacia`/`pericia N`.
- Nao existe `tabletop_rpg_sheet_model.dat` em nenhum mundo, entao a migracao do modelo precisa
  so de cobertura em teste, nao de plano de recuperacao de arquivo.
- Nenhum mixin muda de assinatura; `PlayerSheetPersistenceMixin` so chama `model.align(sheet)`,
  mas e mudanca de comportamento na carga e precisa de teste em jogo.

`SheetModelCodecTest.renamingPericiaIsALossyIdentityChange` (hoje trava a perda) precisa ser
**invertido** para verificar que valor e atributo sobrevivem ao rename. Adicionar 4 testes.

Validacao em jogo, obrigatoria e nao substituivel por build: valor 5 + atributo nao padrao ->
renomear no Sheet Editor -> Salvar -> conferir os dois -> **reiniciar o servidor e conferir de novo**
(testa o determinismo) -> conferir que `Failed to parse saved data` nao aparece e que
`N ficha(s) realinhada(s)` nao e o total de jogadores a cada boot -> `/rpg roll <nome novo>`
ainda respondendo.

### Estado da entrega em 27/09/2026

7 bugs corrigidos, build verde, aguardando **teste em jogo do usuario**. Diff nao commitado:
6 arquivos `.java` + `FUNCIONALIDADES-E-COMANDOS.md` + `agent/memory/project-memory.md` + relatorio
novo `agent/reports/2026-09-27_correcoes-7-bugs-ficha.md`. Sem commit, sem push.

## 2026-09-28 - Layout, validacao e rascunho do editor (rodada 2 de correcao de bug)

O usuario **confirmou** que os 7 bugs da rodada anterior estao corrigidos. Reportou 6 defeitos
novos, todos de layout/validacao. Achados pela revisao: 1 BLOQUEADOR + 3 IMPORTANTES, todos
corrigidos. Relatorio: `agent/reports/2026-09-28_layout-validacao-rascunho-editor.md`.

### FATO verificado - `removed()` e o hook de TODA saida de tela

`onClose()` cobre **so** ESC e o botao de fechar. Abrir o inventario (tecla E) ou qualquer outra
tela com um editor aberto e sujo passa por `Screen.removed()`, que `Minecraft.setScreen` chama em
qualquer troca. Sem sobrescrever `removed()`, fechar o item por outra rota **jogava o rascunho
fora**. `removed()` existe em 1.21.11 e **nao e final**.

### FATO verificado - `static` de tela precisa entrar no cleanup de desconexao

**Este foi o BLOQUEADOR da rodada.** `SheetEditorScreen.draft` e `pendingNames` sao `static`
(precisam ser: `TabletopRpgClient.java` faz `new SheetEditorScreen()` a cada abertura, entao
campo de instancia nao resolveria). Mas o cleanup de `ClientPlayConnectionEvents.DISCONNECT` em
`TabletopRpgClient.registerConnectionCleanup()` so zerava `SheetModelHolder`.

Cenario: edita no Mundo A, aperta ESC, desconecta, entra no Mundo B, abre o item -> `takeDraft()`
devolve o modelo do **Mundo A** e o `SheetModelSavePayload` faz o **`SheetModelStore` do Mundo B
passar a ser o modelo do Mundo A, persistido**. Sobrescrita de dados entre mundos.

**Regra que decorre disso:** sempre que criar um `static` de tela, procurar o cleanup de
desconexao existente e **se cadastrar nele**. Camada de estado nova nao entra sozinha. O
`static` de tela e por JVM de cliente (um jogador local), nao por servidor.

Correcao: `SheetEditorScreen.discardTransientState()` (publico, delega para `clearDraft()`, entao
os dois nao divergem) chamado no `DISCONNECT`, logo apos zerar o `SheetModelHolder`.

### FATO verificado - ordem entre `removed()` e DISCONNECT nao e garantida

Se `removed()` rodar **depois** do handler de desconexao, recria o rascunho que o cleanup
acabou de zerar, desfezendo a correcao acima. A API nao garante a ordem. Blindagem usada:
`captureDraft()` so registra com `this.minecraft.getConnection() != null`. Nao ha perda de
rascunho, porque ESC/Close/inventario acontecem com conexao viva.

### FATO verificado - nao degradar texto cortando caractere

`drawValue` ganhou guarda de truncamento e passou a **mentir**: com rotulo longo, `labelW`
cresce -> `boxW` encolhe -> a barra encolhe, e `"9999 / 9999"` (~61px) saia como `"1234 /"`.
Leitura plausivel e **errada** de um recurso. **Degradar o formato, nao cortar:**
`"hp / hpMax"` -> so o valor atual -> corte (so valor forjado fora da faixa legal).
Corte parcial de um formato e pior que corte: o usuario le numero plausivel e errado.

**Corte silencioso tambem e pior que corte com reticencias.** Sem a marca, o usuario le um
truncado como se fosse o nome completo. Usar `"..."` (3 pontos), que e o glifo mais barato da
fonte (6px) e nao depende da fonte ter aquele caractere.

### FATO verificado - API 1.21.11, corrigida por javap

Correcoes em relacao ao que se assume por memoria:
- **`FormattedCharSequence` esta em `net.minecraft.util`**, nao em `network.chat`.
- Ela **nao tem `length()`**, **nao estende `FormattedText`**, e so se percorre com
  `accept(FormattedCharSink)`. Por isso nao da para chamar `getString()` nela nem passar para
  `Component.literal`.
- `FormattedText` **tem** `getString()`.
- `Font.split(FormattedText,int)` chama `getSplitter().splitLines(text, maxWidth, Style.EMPTY)`,
  entao `getString()` da 1a linha do `splitLines` e um **prefixo real** do texto. E o jeito
  correto de descobrir onde a 1a linha termina, sem `while` decrementando `text.length()`.
- `Font.getSplitter()` e publico; `Font.width(FormattedCharSequence)` retorna `int`.

Ferramentas nesta maquina: `javap` **nao esta no PATH**, esta em
`C:\Program Files\Java\jdk-21.0.12\bin\javap.exe`. O jar mapped com as classes esta em
`~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-common/1.21.11-loom.mappings.*/...jar`.
`~/.gradle/caches/fabric-loom/1.21.11/minecraft-client-only.jar` **nao** contem essas classes, e
dar `class not found` com ele engana (fiz o erro de concluir que a classe nao existia).

### FATO verificado - `splitEnvironmentSourceSets()` e por que `static` de tela e seguro

`build.gradle` usa `splitEnvironmentSourceSets()`, entao `src/client/java` **nao entra** no
classpath do servidor dedicado, e a unica referencia a `SheetEditorScreen` e em
`TabletopRpgClient`. Logo `static` de tela = por JVM de cliente = **um jogador local**. Nao ha
compartilhamento entre jogadores nem no servidor dedicado. (A hipotese de que o servidor
carregasse a classe e carregaria o `static` foi refutada.)

### FATO verificado - janela estreita nao comporta 2 linhas, e isso e fisico

O wrap de rotulo em 2 linhas depende de `rowH >= 17`. Com 18 linhas, `rowH = max(12, min(20,
(altura-86)/neededRows))` chega a 12-13px em GUI 480x270 (escala 4), e duas linhas de tinta de
8px **se sobrepoem**. Nao e bug de codigo: e impossibilidade geometrica. La o rotulo sai
truncado com reticencias. Para ter wrap naquela resolucao, o caminho e reduzir linhas visiveis
(scroll na coluna) ou subir o piso de `rowH` a custo de conteudo visivel. **Decisao do usuario,
nao aplicada.**

### FATO verificado - `EditBox` filtra texto inserido mas nao texto apagado

`EditBox.insertText` aplica o `setFilter` e **retorna sem aplicar** quando o filtro recusa: a
tecla e engolida **sem feedback** visivel. `deleteCharsToPos` **nao** aplica filtro, entao apagar
funciona. Consequencia pratica: o filtro por teto e armadilha de UX (nada indica o porque), mas
**nao** trava edicao — trocar "9999" por outro valor grande continua possivel.

### FATO verificado - `sanitizePericias` e uma armadilha de identidade por posicao

`SheetModel.sanitizePericias` **descarta** pericia de nome vazio e, se a lista toda ficar
vazia, **restaura as 18 padrao**. Como a tela do editor identifica linha por **posicao**
(`periciaAt(pos)`), deixar um nome vazio **entrar no modelo** deslocaria todas as linhas
seguintes e o botao de atributo e o `X` passariam a operar na pericia errada. Por isso o
requisito "deixar apagar o nome" foi implementado com **`pendingNames` na tela** (posicao ->
texto), sem tocar no `SheetModel`. `SheetModel` deduplica por `name().toLowerCase()`, entao
`removePericia` remove exatamente 1 elemento e o shift das pendencias e exato.

### PAPEL do implementador: refutar premissa do briefing

Dois casos nesta rodada em que o implementador **corrigiu o briefing** em vez de obedecer, e
os dois estavam certos:
1. Disse que `reset()` (Restaurar) deixa `isDirty()` verdadeiro e que isso e o comportamento
   correto (o padrao nao esta no servidor e e o que o Mestre ve), em vez de "alinhar" o
   `baseline` como eu sugiri.
2. Encontrou que a ordem entre `removed()` e `DISCONNECT` nao e garantida e blindou com
   `getConnection() != null`, o que desfez a hypothese de defeito #1 no proprio conserto.
Sempre vale delegar com premissa explicita e pedir para confirmar lendo o codigo.

### PENDENTE - catalogo nao foi atualizado com as regras desta rodada

A tarefa de `FUNCIONALIDADES-E-COMANDOS.md` foi **cancelada pelo usuario** antes de comecar.
O catalogo reflete a rodada anterior, mas nao registra: teto de 9999 no cliente em HP/Mana,
degradacao de formato da barra, wrap + reticencias, nome de pericia vazio (Save desativado +
aviso) e o rascunho do ESC. **Proxima tarefa ao retomar.**

### PENDENTE - id estavel em PericiaDef (plano aprovado, nao executado)

Continua aprovado por decisao do usuario, para **depois** do teste em jogo. O plano completo esta
nesta memoria, na entrada de 27/09/2026. Nada foi implementado. Nao comecar sem novo pedido.
