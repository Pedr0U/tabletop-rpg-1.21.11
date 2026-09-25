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
- **Mojibake `┬º`**: 7 mensagens do `CombatController` tinham `┬º` no lugar de `§` (herança pasta-Net) — substituídas. Ao encontrar `┬º` em literais de chat, trocar por `§`.
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
- **Mojibake no `CombatController.java`**: comentários corrompidos ("at├®", "N├âO", "├óncora", "posi├º├úo", "s├│", "come├ºar", "ÔÇö") — edits falham se não casarem o texto exato lido do arquivo; editar em pedaços menores.

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