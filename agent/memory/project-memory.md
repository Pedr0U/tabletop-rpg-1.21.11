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

## PowerShell 5.1 le `.ps1` como ANSI: script com acento nao parseia (30/09/2026) - FATO

- **FATO:** nesta maquina, um `.ps1` gravado em UTF-8 **sem BOM** e lido pelo
  PowerShell 5.1 como Windows-1252. Um unico caractere acentuado no arquivo
  (ex: uma regex com `[áàâã...]`) vira mojibake e o script morre com
  `MissingEndParenthesisInMethodCall` em uma linha que esta correta no editor.
  O mesmo `.ps1` com BOM funciona.
- **REGRA:** script de verificacao em `%TEMP%\opencode` e **ASCII puro**.
  Montar lista de caracteres por codepoint (`0x00E1`, `[char]0x00FA`), nunca
  por literal. Vale para qualquer script temporario, nao so para encoding.
- **ERRO REAL desta sessao:** o script de auditoria de encoding falhou com 6
  erros de parser e eu quase culpei o arquivo do projeto. A causa era o
  proprio script.

## Licao: `UseBlockCallback` e array-backed e PARA no primeiro resultado diferente de PASS (30/09/2026) - FATO verificado na fonte do Fabric API

- **FATO:** `UseBlockCallback.EVENT = EventFactory.createArrayBacked(...)`, com a
  semantica documentada de que `PASS` segue para os proximos handlers. Ordem de
  registro = ordem de decisao.
- **CONSEQUENCIA (bug real, corrigido no mesmo dia):** `CombatController`
  devolve `FAIL` quando ha monstro selecionado (`CombatController.java`, ramo
  do `UseBlockCallback`). Como o `BlockLockManager` era registrado **depois**,
  ele **nunca executava** com monstro selecionado: o Mestre movia o monstro em
  vez de trancar o bau, sem mensagem de erro nenhuma.
- **REGRA:** ao registrar um handler novo de `UseBlockCallback`, perguntar
  "quem ja esta registrado e devolve FAIL antes de mim?" e escolher a posicao
  conscientemente. Registrar por ultimo nao e o mesmo coisa por padrao.

## Cliente NUNCA pode devolver diferente de PASS no `UseBlockCallback` (30/09/2026) - FATO verificado em bytecode

- **FATO (javap em `fabric-events-interaction-v0`, `MultiPlayerGameModeMixin.interactBlock`):**
  o metodo mixado contem `hasMissTime`, a invocacao do evento, um `predict` e um
  `setReturnValue`. **Nao ha `send` nem `ServerboundUseItemOnPacket` no corpo.**
- **CONSEQUENCIA:** devolver `SUCCESS`/`FAIL` no cliente cancela
  `MultiPlayerGameMode.useItemOn` **antes** do envio do pacote, entao o servidor
  **nunca recebe o clique**. Um handler server-side que devolva `PASS` no
  cliente funciona; o mesmo handler devolvendo `SUCCESS` no cliente some em
  silencio.
- **EFEITO COLATERAL ACEITO:** com `PASS` no cliente, a predicao local ainda
  roda, e `DoorBlock.useWithoutItem` faz `setBlock` sem guarda de cliente: a
  porta **abre na tela do Mestre** enquanto o servidor cancelou. Mitigado com
  `sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS)` depois de
  aplicar a tranca, que sobrescreve o estado previsto pelo autoritativo.
- **CORRECAO A UMA REVISAO:** um revisor afirmou que o mixin cliente envia o
  pacote quando o resultado consome a acao. **Falso** -- o bytecode nao tem
  `send`. Conferir bytecode antes de aceitar esse tipo de afirmacao.

## Bloco trancavel sem tag do vanilla (30/09/2026) - FATO verificado com javap

- **FATO:** em 1.21.11 `net.minecraft.tags.BlockTags` **nao tem** `OPENABLE`,
  `CONTAINERS`, `SHOPIERS` nem `LEVERS`. Filtrar por tag deixaria de fora os
  blocos de mod, que sao metade do motivo da feature.
- **USADO:** `BlockState.getMenuProvider(level, pos) != null` (inventario) **ou**
  a classe do bloco sobrescrever `useWithoutItem`/`useItemOn` (porta, alavanca,
  botao, e qualquer mod que nao abre menu).
- **ARMADILHA:** os dois metodos sao **protected** em `BlockBehaviour`; as
  versoes publicas ficam em `BlockBehaviour$BlockStateBase`. Usar `getMethod`
  (que so ve public) lancaria `NoSuchMethodException` para **todos** os blocos e
  o teste viraria "tudo e interagivel" em silencio. Usar `getDeclaredMethod`
  subindo a hierarquia e comparar com `BlockBehaviour.class`, tratando `null`
  como "nao sobrescreve".

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

### FATO verificado - checkpoint 28/09/2026

Commit `988335cfcef46e642a23a8532459ea73f27dd6d7` na branch `main`, tag anotada
`checkpoint-20260928-0635-layout-validacao-rascunho` (objeto de tag
`dfa3fe974b7a4cb35a86ff541c199fa65f9670a0`). 13 arquivos, +1872/-153. **Sem push.**

Este commit versiona **as duas** rodadas de correcao de uma vez, porque a rodada de 27/09/2026
nunca tinha sido commitada. Estado de partida anterior: `7777eb7`.

Incluidos: `CharacterSheetScreen.java`, `SheetEditorScreen.java`, `StatusScreen.java`,
`TabletopRpgClient.java`, `MasterCommands.java`, `SheetData.java`, `SheetModel.java`,
`en_us.json`, `sheet_editor.json`, `FUNCIONALIDADES-E-COMANDOS.md`, `project-memory.md` e os
dois relatorios novos.

`logs/latest.log` e `build/` ficaram de fora (sem alteracao). Sem segredo no diff: o unico match
do scanner de padroes foi a palavra "segredo" no catalogo, significando **rolagem secreta** do
jogo, nao credencial.

Estado da entrega no commit: validacao automatica verde, **13 defeitos aguardando teste em jogo**,
catalogo da rodada 2 pendente e id estavel em `PericiaDef` pendente.

### FATO verificado - 28/09/2026, catalogo do editor completado (c663e5b)

- `FUNCIONALIDADES-E-COMANDOS.md` estava **parcialmente** desatualizado, nao inteiramente. A
  tarefa de sincronizacao que o agente principal marcou como "cancelada" **tinha escrito** a maior
  parte antes de ser interrompida, e o texto foi versionado em `988335c`. Conclusao: "cancelado"
  nao significa "nao aconteceu". Conferir o arquivo antes de registrar pendencia.
- O filtro de teto do `EditBox` **nao da retorno visivel**: `insertText` chama `filter.test`, e
  se vier falso retorna antes do `putfield value`, sem `onValueChange`, sem som, sem aviso.
  Confirmado por `javap -c` no jar nomeado do 1.21.11.
- CORRECAO DE RACIOCINIO: `deleteCharsToPos` **tambem** aplica `filter.test` com o mesmo retorno
  cedo. Apagar funciona por causa do *criterio* de `withinCeiling` (so recusa valor acima do teto,
  e o numero que sobra ao apagar e menor), e nao porque o caminho de apagar seja sem filtro.
- O wrap de rotulo so fecha com `rowH` 17 ou mais. Com as 18 pericias do padrao, a linha cai para
  `rowH` 12 ou 13 em 480x270 escala 4, e o rotulo sai truncado com reticencias.
- `Reset` (Restaurar) **preserva rascunho**: poe o padrao no rascunho, nao mexe no estado salvo,
  entao `isDirty()` continua verdadeiro e `captureDraft` guarda. So `Discard` volta ao salvo e so
  `Save` aplica.
- `Status` e `Skills` **herdam** `CharacterSheetScreen.render` sem sobrescrever, entao o titulo
  desenhado antes do `super.render()` vale para as tres telas.
- O detector `check-catalogo.ps1` mora em
  `%USERPROFILE%\.config\opencode\skills\agente-tcc\catalogo-sync\`, **nao** dentro do repositorio.
  Dois subagentes seguidos falharam em acha-lo procurando no projeto. O caminho precisa estar
  escrito dentro da skill `catalogo-sync`, e nao discovery em runtime.
  catalogo-sync estar explicito.

### HIPOTESE - 28/09/2026, pendente de confirmacao em jogo

- O texto do catalogo Newly reescrito pode conter erro de leitura, ja que nenhum humano revisou.
  Revisar com o usuario na proxima sessao e o caminho mais barato.
- `javap -c` foi usado em jar do cache do Loom. Bytecode e evidencia forte, mas so para o 1.21.11
  nomeado; reconferir se a versao mudar.

### FATO verificado - 28/09/2026, item sheet_editor corrigido (sprite e nome)

Causa raiz confirmada por bytecode do jar nomeado 1.21.11:

- O 1.21.11 carrega modelo de item **SO** de `assets/<ns>/items/<id>.json`.
  `ClientItemInfoLoader` usa `FileToIdConverter.json("items")`; **nao ha fallback
  legado** em runtime. `bakedItemStackModels` so e populado dali, com
  `getOrDefault(id, missingModels.item())`. `assets/tabletop-rpg/items/sheet_editor.json`
  nunca existiu, entao o item era desenhado com modelo ausente.
- A ausencia e **SILENCIOSA**: o loader so loga warning quando o arquivo existe e
  falha ao parsear; arquivo faltante nunca chega ao parser. Nao ha pista no log.
- A translation key automatica do item **preserva o hifen** do namespace
  (`Util.makeDescriptionId` concatena namespace + "." + path): a chave real e
  `item.tabletop-rpg.sheet_editor`. `en_us.json` tinha `item.tabletoprpg.sheet_editor`,
  sem hifen, e o jogo mostrava a chave crua.
- `en_us.json` tem **dois padroes** de chave, e **ambos estao corretos**:
  derivados do id (`item.tabletop-rpg.*`, `key.category.tabletop-rpg.rpg`) e
  passados como literal no codigo (`screen.tabletoprpg.*`, `key.tabletoprpg.*`,
  `itemGroup.tabletoprpg.*`). `item.tabletoprpg.sheet_editor.denied` **precisa
  continuar sem hifen**, porque `ModItems.java:119` passa a string como literal
  para `Component.translatable`. Nao "arrumar" essa linha.
- `key.category` e **singular**, nao `key.categories`. Ja estava certo.
- ERRO MEU: alterei `models/item/sheet_editor.json`, que o jogo nunca le, e
  declarei corrigido. Alterar o arquivo certo e o jogo ler aquele arquivo sao
  afirmacoes diferentes; a segunda exige evidencia do jar.

### FATO verificado - 28/09/2026, API de widget na 1.21.11

- `net.minecraft.client.gui.components.Button` e **`abstract`**, nao `final`.
- `AbstractButton.renderWidget` e **`protected final`** e chama `renderContents`
  + `handleCursor`. Para customizar visual, so da para implementar
  `renderContents` (copiar o corpo de `Button$Plain`).
- `AbstractWidget` **nao tem** `tick()`. Para temporizar por widget, a tela
  precisa de `tick()` percorrendo `children()`.
- `Screen` **nao tem** `hasShiftDown()`. Existe em `InputWithModifiers`.
  `MouseButtonEvent` e `KeyEvent` implementam `InputWithModifiers`.
- O `mouseReleased` **nao testa `isMouseOver`**: e roteado pelo **foco**
  (`ContainerEventHandler` so entrega ao `getFocused()` se `button==0 && isDragging()`).
  "Continua valendo com o cursor fora do botao" vem de graca do vanilla.
- `AbstractButton.keyPressed` tambem chama `onPress`, e **nao ha rota de release
  por teclado** que chegue ao widget. Segurar Enter/Espaco em botao focado ramparia
  para sempre sem guarda `input instanceof MouseButtonEvent`.
- `playDownSound` e chamado por `AbstractWidget.mouseClicked`, nao por `onPress`.
  Repeticao disparada de `tick()` e silenciosa de graca.
- `MouseHandler.isLeftPressed()` **existe mas NAO serve** para detectar segurada
  com tela aberta: o bytecode de `onButton` so escreve o campo quando
  `screen == null && getOverlay() == null`. Usar
  `GLFW.glfwGetMouseButton(Window.handle(), 0)` (botao 0 = left, `GLFW_PRESS = 1`).
- `StreamCodec.composite` tem teto de **6 campos**, nao 12. O record de 13 campos
  (`SheetModel`) contorna com `StreamCodec.of(ENCODER, DECODER)`.

### FATO verificado - 28/09/2026, valor otimista da ficha

O eco do servidor (`broadcastSheet`) chega ~50 ms atras. Com rajada a 25 passos/s,
`pendingNumeric.clear()` / `pendingPericiaValue.clear()` no eco apagavam o valor
otimista a cada passo, o cliente recalculava de valor velho e **reenviava valor
MENOR** que o ja gravado: numero subia e voltava, e a aceleracao nao se materializava.

Regra nova: o eco so limpa o pendente quando o autoritativo **iguala** o pendente
(`CharacterSheetScreen.keepPending`, `StatusScreen.reconcilePericiaValues`).
Pendente fora da faixa legal e descartado, para o cliente nao mostrar numero invalido
preso. A reconciliacao e **autossincronizante**: o servidor aplica em ordem, entao o
ultimo eco iguala o ultimo enviado. Limpeza total continua em `onModelChanged`.
Desconexao nao tem `clear()` explicito porque `targetName` e `final` e a tela e
destruida; adicionar seria codigo morto.

### HIPOTESE - 28/09/2026, a confirmar em jogo (rajada de valores na coluna)

- O `canStep` de atributos e pericias usando valor otimista deve parar a rajada no
  teto, mas isso so foi verificado por leitura.
- A rajada morrer em silencio ao rolar a coluna de pericias (que chama
  `rebuildWidgets`) e o desfecho aceitavel, mas o usuario pode achar estranho.
- Reconciliacao aplica a faixa de atributos a **todo** `pendingNumeric`, HP/Mana
  incluidos. Como HP/Mana nao tem rajada, nao ha pendente a frente para sustentar e
  a igualdade normal resolve. Reavaliar quando o modelo de vida/mana mudar.

### FATO verificado - 28/09/2026, id estavel de pericia (migration aplicada)

Cada pericia agora tem id `pericia_N` (N >= 1), gerado uma vez e congelado, espelhando
`attr_N` dos atributos. `SheetModel.PericiaDef(String id, String name, String attributeId)` e
`SheetData.Pericia(String id, String name, int value, String attributeId)`, id como primeiro
componente. O NOME virou so rotulo. Renomear preserva valor e atributo.

**ORDEM OBRIGATORIA que impede perda silenciosa:** preencher o id ANTES de deduplicar, e NUNCA
deduplicar por id vazio. Sem isso, uma ficha antiga (18 pericias, todas com id `""`) colapsa
para 1, sem erro, sem warning, sem log. O `Codec` do DataFixerUpper omite campo igual ao default
(`Codec.java:303-308` do datafixerupper-9.0.19), o que torna esse cuidado obrigatorio.

- `PERICIA_CODEC` usa `optionalFieldOf("id", "")`, **nao** `fieldOf("id")`: um `fieldOf` faria
  o `Codec.list` derrubar o `SheetData.CODEC` inteiro e perder a **ficha completa**, nao so as
  pericias. O `fieldOf` de `AttributeValue.id` e obrigatorio de proposito (leia o comentario em
  `SheetData.java:227-230`) e **nao** se aplica a pericia.
- O primeiro `seen.add` vence: a primeira ocorrencia e preservada, a segunda recebe id novo.

**O MECHANISMO REAL da migracao e POSICIONAL, e nao por nome.** O id e preenchido na ordem da
lista, e o NBT antigo foi gravado na ordem do modelo (o `align` antigo percorria a lista do
modelo), entao casar por id == casar por posicao reproduz o que casar por nome reproduzia.

**O fallback por nome virou CODIGO MORTO:** `hasPericiaIds()` so devolve `false` com a lista
VAZIA (o construtor compacto preenche o id de toda entrada retida, e nao existe estado misturo),
e sobre lista vazia `periciaByNameOrLegacy` devolve `null`. `LEGACY_PERICIA_NAMES` virou inerte.
Mantido por principio de defesa, com o Javadoc honesto.

**Limitacoes residuais (documentadas no codigo, no catalogo e no relatorio):**
- Remover pericia com jogador offline, **sem** adicionar: seguro, so perde o valor da removida.
- Remover e **depois adicionar**: `addPericia` pega o menor N livre, que pode ser o da removida,
  e a nova **herda o valor** da removida em todas as fichas. Contorno: renomear em vez de
  remover e recriar.
- O mesmo vale para ATRIBUTOS, e **nao foi possivel corrigir**: `SessionManager` nao expoe iteracao
  sobre as fichas em memoria. O Javadoc de `addAttribute` foi corrigido para prometer so o que o
  codigo garante ("nunca colide com um atributo que o modelo ainda tem"). O defeito original era
  exatamente um Javadoc que prometia mais do que o codigo fazia.

- `SheetPericiaPayload` foi de `pericia` para `periciaId`: 5 campos, mesma ordem, mesmo codec. O
  `composite` tem teto de 6, entao nao ha risco de formato de fio aqui. Mas `SheetData.Pericia` foi
  de **3 para 4 campos** no `STREAM_CODEC`: **reinstalar o jar nos dois lados** em versoes misturadas.
- `AttributePickerScreen` **nao** precisou mudar: recebe `pericia.name()` so como rotulo e devolve o
  id do **atributo**. A memoria do projeto dizia que este arquivo mudaria, e estava errado.
- `MasterCommands.findPericia` continua por nome normalizado: e interface humana, nao armazenamento.

**FIO / saves:** backup de 52 arquivos `.dat` em `%LOCALAPPDATA%\Temp\opencode\backup-saves-20260928-1010`
(45355 bytes, conferido identico). 11 fichas reais em disco, sendo 6 pre-migration e 1 com 10
pericias customizadas pelo Mestre (o caso que perdia dados).

### FATO verificado - 28/09/2026, nao validado em jogo

17 testes unitarios passam (antes eram 8), mas **a prova da migracao e empirica**: e preciso
entrar em jogo com uma das 6 fichas pre-migration e conferir **cada** pericia, nao so o total.
`PlayerSheetPersistenceMixin` alinha com `SheetModelHolder.current()`, que ainda vale o **default**
se a carga rodar antes do `SERVER_STARTED` (singleplayer): `realignAllSheets` restaura lista e
valores, mas **nao** o vinculo pericia->atributo quando o id do atributo padrao tambem existe no
modelo real. Defeito pre-existente, nao desta migration.

### HIPOTESE - 28/09/2026, a confirmar em jogo (migracao de ficha)

- A migracao por posicao segura para as 6 fichas pre-migration, por analise. So um login real
  prova.
- O fallback posicional e seguro **se** o mundo nao tiver o Mestre removendo pericia do meio com o
  jogador offline.
- `sanitizePericias` deduplica por id em minusculas, mas `periciaById` compara exato: um `.dat`
  editado a mao com `PERICIA_1` passaria pelo sanitize e nunca casaria (valor 0). Mesmo padrao ja
  existe nos atributos. Inconsistencia conhecida, nao bug no fluxo normal.

### FATO verificado - 28/09/2026, validacao em jogo da migracao de id de pericia

O usuario testou em jogo e confirmou: teste 2 (renomear preserva valor e atributo),
teste 3 (dropdown de atributo preserva o vinculo) e teste 4 (nome repetido recusado)
passaram; o teste 1 (ficha pre-migration) ficou para depois e, ao final, o usuario
confirmou "deu tudo certo" - a migracao posicional esta VALIDADA empiricamente, nao
so por teste unitario. O teste 5 (atributo customizado sobrevive ao reinicio do
mundo) nao foi mencionado: opcional, so relevante com atributos customizados; o
defeito pre-existente do PlayerSheetPersistenceMixin (carga antes do SERVER_STARTED)
segue como pendencia documentada, nem confirmado nem descartado.

### FATO verificado - 28/09/2026, teste 5 (atributo customizado apos reinicio do mundo)

O usuario testou em singleplayer: fechou o mundo e abriu de novo com atributos
customizados e "tava tudo normal" - o vinculo pericia->atributo customizado
sobreviveu ao reinicio. O defeito pre-existente do PlayerSheetPersistenceMixin
(carga antes do SERVER_STARTED) NAO reproduziu nesse cenario. Segue documentado
como risco dependente de timing, mas com evidencia de que nao se manifesta no
fluxo normal de singleplayer.

## 2026-09-28 - Camera livre (input, nao aiStep), mao do caido e scroll do popup

Rodada de correcao de 3 bugs reportados pelo usuario. Relatorio:
`agent/reports/2026-09-28_camera-livre-mao-caido-scroll-popup.md`. **Sem commit** (decisao
do usuario: commit so quando ele pedir). Validado por `build --no-daemon` (22s,
`scanEncoding` 0 falhas) e `runClient` ate o menu; **nada validado em jogo**.

### Camera livre: congelamento era o cancelamento de `aiStep` (FATO verificado)

- **FACT:** em 1.21.11 e o `LivingEntity.aiStep()` que chama **`travel()`** (movimento,
  gravidade, colisao) **e** `calculateEntityAnimation()` (animacao das pernas). O
  `LocalPlayerMixin` cancelava `aiStep()` quando `isFreeCameraActive()`, o que tirava a
  gravidade (personagem congelado no ar apos pular) e deixava o `walkAnimation` preso no
  ultimo valor (pose de corrida parada).
- **FACT:** `walkAnimation` **congela** com o `aiStep` cancelado (nao acelera). Com o
  cancelamento, os unicos chiamers de `calculateEntityAnimation` no jogo sao
  `LivingEntity.aiStep()` e `RemotePlayer.tick()`; nao existe `stop()` em lugar nenhum do
  mod. Consequencia: ao sair do congelamento a animacao retoma de um `position` obsoleto.
- **Decisao do usuario (28/09/2026):** na camera livre o **corpo fica parado, mas assenta
  no chao**, e a animacao volta ao idle. O WASD continua movendo so a camera.
- **Solucao:** `ClientInputMixin` (novo, so cliente) zera o movimento **no input**, sem
  cancelar nada; `aiStep()` volta a rodar inteiro (gravidade, colisao e animacao vanilla).
  O `LocalPlayerMixin` agora cancela so por `locked` e `downed`.
- **FACT de API:** `net.minecraft.client.player.ClientInput` tem `public Input keyPresses`,
  `protected Vec2 moveVector` e `public void tick()` **VAZIO**; `KeyboardInput` e que
  sobrescreve `tick()`. `LocalPlayer.input` e **sempre** um `KeyboardInput` (criado no
  `ClientPacketListener`). Portanto o `@Inject` tem que ser em **`KeyboardInput.tick()`** -
  injetar no `ClientInput.tick` seria codigo morto (a chamada e `invokevirtual`).
- **FACT de API:** `net.minecraft.world.entity.player.Input` e um **record imutavel** com 7
  campos booleanos na ordem `(forward, backward, left, right, jump, shift, sprint)` e
  acessores `forward()`...`sprint()`. **Nao existe setter nem `jump(boolean)`.** Para
  zerar pulo/agachar preservando o resto, tem que **reconstruir o record** - o mesmo
  truque que o proprio vanilla faz em `ClientInput.makeJump()`. O record esta no jar
  **common**, nao no `minecraft-clientonly`.
- **PADRAO DE MIXIN UTIL (ainda NAO validado em jogo):** para alcancar membro da classe-pai
  (`ClientInput.moveVector`, `ClientInput.keyPresses`) a partir de um mixin de
  `KeyboardInput`, o mixin **estende a classe-pai direta do alvo**
  (`class XMixin extends ClientInput`) e usa o acesso normal do Java. O `@Shadow` de campo
  herdado **estoura** `InvalidMixinException: @Shadow field ... was not located in the
  target class`, porque o Mixin procura o alvo do shadow so nos campos do proprio alvo.
- **ARMADILHA DE VALIDACAO:** `KeyboardInput` so e carregada ao **entrar num mundo**. Um
  `runClient` que so chega ao menu ("Sound engine started", 0 crash-reports) **nao prova**
  que esse mixin aplica. A prova vem de entrar num mundo.
- **Efeito colateral assumido:** com a camera livre ligada, **pular e agachar deixam de
  responder** (o corpo fica realmente parado); ataque, uso de item e corrida continuam.

### Mao do caido: era a mao em 1a pessoa, nao o corpo (FATO verificado)

- **FACT:** quando a camera de espectador esta **desligada** (`isActive()` falso), nenhum
  dos mixins de alinhamento age, e o `EntityTurnMixin`/`GameRendererMixin` tambem nao age
  (ambos sao condicionados a `isActive()`). O corpo do caido continua girando normal com o
  mouse (vanilla), entao **nao ha desalinhamento** nesse caminho: o que aparece e a mao em
  **primeira pessoa** do vanilla, num jogador deitado na pose `SWIMMING` forcada por
  `ClientPlayerPoseMixin`.
- **FATO (reforca decisao de 26/09/2026):** a condicao `!spectator` do
  `DownedBodyAlignMixin` esta **correta** e nao deve ser removida. O alinhamento so e
  necessario quando a camera de espectador congela o yaw do corpo.
- **Solucao:** `GameRendererMixin.renderItemInHand` passou a cancelar tambem por
  `TabletopRpgClient.downed` - o mesmo mecanismo ja usado em 26/09 para esconder a mao ao
  espectar a si mesmo. 1 linha.
- **Pendente:** a instrumentacao `DIAG_TEMP` (`[DownAlign]`, log a cada 10 renders) foi
  removida de `DownedBodyAlignMixin`, `CinematicCameraRig` e `CameraMixin`, conforme o
  plano da rodada de 26/09 que nunca usou o log. As hipoteses (a), (b) e (c) que ela media
  nao chegaram a ser testadas e seguem **nao verificadas**.

### Scroll do popup de skills: o fall-through era a causa (FATO verificado)

- **FACT:** nao existe `AbstractScrollArea` no mod; as duas barras (lista e popup) sao
  desenhadas a mao em `SkillsScreen`. A barra da **debaixo** e a do **popup** de descricao
  (confirmado com o usuario em 28/09/2026). A unica barra vanilla da tela e a do campo de
  descricao no rodape (`MultiLineEditBox`), invisivel enquanto o texto digitado couber.
- **CAUSA do sintoma "a de cima desce junto":** `mouseScrolled` rolava o popup e, quando o
  popup ja tinha chegado ao fim (`popupScroll < maxScroll` falso), **cai no bloco da lista**
  e rola a lista com a MESMA roda. Era a ordem arbitrada e descrita no Javadoc do metodo.
- **Correcao:** a roda passa a rolar **so a regiao sob o cursor** (`pointerOverPopup` /
  `pointerOverSkillRows`), sem passar para a outra; e a barra do popup aceita o arrasto na
  **margem direita de 12 px** do popup (`BAR_W 6 + 2*BAR_PAD 3`), que antes so aceitava o
  trilho de 6 px mais 2 px de cada lado - um clique 2 px fora nao pegava nada e nao dava
  nenhuma resposta, o que o usuario leu como "arrastando nao sobe".
- **FACT de layout:** neste arquivo `BAR_SLOT` vale **32**, e **nao** 12: a faixa util de
  12 px do respiro e `BAR_W + 2*BAR_PAD`. `popupY = y + skillRows*rowH + 16`, entao as
  faixas de Y de lista e popup sao disjuntas na pratica; so o X cruza (7 px). A ordem de
  teste no `mouseClicked` foi invertida (popup primeiro) porque ele e desenhado depois.
- `mouseScrolled` passou a retornar `false` quando nao rolou nada (antes `true` fixo):
  afirmar que o evento foi tratado sem tratamento e contrato errado; a tela e modal, entao
  nao ha consumidor acima dela.

### CORRECAO de 28/09/2026: a aplicacao do `ClientInputMixin` JA ESTA VALIDADA

O item "ARMADILHA DE VALIDACAO" acima falava em hipotese; e o proprio usuario ja tinha
resolvido, sem eu ter pedido. `run/logs/latest.log` vai ate 21:14 (o build novo foi feito
as 20:21 e o `runClient` subiu as 20:22): o mundo `New World` foi carregado e jogado por
~50 min, com 0 crash-reports e nenhum `InvalidMixin`/`InvalidInjection`/`NoSuchMethodError`.
**FATO: entrar num mundo e o que carrega `KeyboardInput`, e o `ClientInputMixin` rodou sem
falhar.** A aposta do padrao "mixin estende a classe-pai do alvo" esta confirmada em
runtime.

**TECNICA REUTILIZAVEL (FATO):** para descobrir **qual build o cliente do usuario rodou**,
contar no `run/logs/latest.log` um marcador que existe **so** no build novo. Aqui o
`[DownAlign]` foi removido no mesmo round, e o log tem **0** linhas dele -> o cliente
obrigatoriamente rodou o build novo. Vale mais que perguntar ao usuario e que o timestamp
do jar, e e a forma correta de fechar "será que ele testou isto mesmo?".

### FATO verificado - 28/09/2026, rodada da camera livre VALIDADA EM JOGO

O usuario testou em jogo e respondeu **"eu testei e esta tudo certo"**. As tres correcoes
desta rodada (camera livre assentando no chao, mao do caido escondida na 1a pessoa, scroll
do popup separado por regiao) estao **aprovadas pelo usuario**, e o efeito colateral de
pulo/agachar deixar de responder na camera livre foi aceito como esta. A confirmacao e
geral: ele nao detalhou qual dos quatro itens da lista de teste percorreu, entao o registro
e "aprovado pelo usuario", e nao "cada item conferido individualmente". Relatorio:
`agent/reports/2026-09-28_camera-livre-mao-caido-scroll-popup.md`; commit e tag desta
rodada estao registrados em `GitHub/agent/VERSIONAMENTOS.md`.

### FATO verificado - 28/09/2026: limites de valor viraram dado do modelo (rodada dos 5 itens)

Rodada dos cinco itens de UI que o usuario pediu. Relatorio completo, com o que ficou
pendente: `agent/reports/2026-09-28_cinco-mudancas-ficha-skills-sheet-editor.md`. Estado
ao pausar: **itens 2, 4 e 5 prontos e com build verde; itens 1 e 3 NAO implementados; nada
commitado** (o C05 continua sendo o ultimo checkpoint). Antes de mexer nesses arquivos,
ler o relatorio: os itens 1 e 3 tem especificacao pronta.

Duraveis desta rodada:

- **FATO:** `StreamCodec.composite` tem **teto de 6 campos**. `SheetData` passou de 6 para 9
  componentes e virou encoder/decoder manual, igual ao `SheetModel`, que ja era manual
  porque tem 16. O Javadoc do `SheetModel` avisa que encoder e decoder sao lambdas
  independentes: acrescentar em um lado so **compila** e quebra em runtime. Conferir os dois
  lados com grep (`VAR_INT`) depois de mexer.
- **FATO:** o clamp mais barato num record do Minecraft fica no **construtor compacto do
  record EXTERNO**, nao no de cada record interno. Como os limites (`attributeValueMin`,
  `attributeValueMax`, `periciaValueMax`) agora sao campos do `SheetData`, todo caminho que
  reconstroi a ficha fica limitado de uma vez: `withField`, `withPericiaValue`,
  `mutatePericia`, `align` e a leitura de NBT de mundo velho. Os records internos
  (`AttributeValue`, `Pericia`) ficaram so com teto absoluto largo (-999..999 e 0..999).
- **FATO:** baixar o teto no editor **corta valores ja salvos**, porque `SheetModel.align`
  reescreve os limites na ficha que reconstroi e o construtor do `SheetData` aplica o clamp.
  Os limites tambem passam a ser gravados no NBT de cada jogador (3 chaves
  `optionalFieldOf`), entao o modelo e a fonte e o NBT da ficha e uma copia.
- **FATO:** `SheetModel.sanitizePericias` deduplica por **id**, nao por nome. Dois nomes
  iguais com ids diferentes ja coexistiam; o unico obstaculo era a recusa em
  `withPericiaText` mais a reversao da caixa em `SheetEditorScreen.periciaRow`. Por isso
  "deixar digitar nome repetido e travar o Save" foi mudanca pequena.
- **FATO:** com dois nomes de pericia iguais, `/rpg roll <pericia>` rola **a primeira** das
  duas, porque `MasterCommands.findPericia` (`MasterCommands.java:466-481`, circa de
  466-481 antes desta rodada) faz o proprio laco e devolve o primeiro nome normalizado.
  Consequencia aceita, nao corrigida.
- **FATO:** as telas da ficha (`StatusScreen`, `CharacterSheetScreen`) **nao usam chave de
  traducao**: todo texto e literal em ingles no Java. So o `SheetEditorScreen` tem chaves
  `screen.tabletoprpg.sheet_editor.*`, em `assets/tabletop-rpg/lang/en_us.json` (o unico
  arquivo de lang do projeto).
- **FATO:** a ficha **nao tem `EditBox` de nome de pericia nem de atributo**. O primeiro
  campo e o nome do personagem, com rotulo configuravel (default "Name"); o bloco se chama
  `Identity` no `SheetData`, e `SheetData.defaultSheet(playerName)` ja coloca o **nome do
  jogador** no campo de nome do personagem. Atributo e pericia usam botoes `-`/`+`.
- **FATO:** `Codec.encodeStart` devolve `DataResult<Tag>`, e so o `parse` aceita `Tag`.
  Declarar `CompoundTag` no resultado de `encodeStart` nao compila; o `CompoundTag` e o
  tipo concreto do NBT gravado, nao o tipo do retorno.
- **HIPOTESE:** a cor nova `COL_DESC_BG = 0xF2090A0E` contra o painel `0xF216161C` deve
  ficar perceptivel (mais escuro no canal azul). So o jogo confirma; se ficar chapado,
  desce para perto de `0xF2090A0E` com mais alpha ou apaga a moldura.

### 2026-09-28 (tarde) - itens 1 e 3 implementados, catalogo e build verdes

**CORRECAO do bloco anterior:** os itens **1** (campo Player) e **3** (botao Edit na skill)
tambem foram implementados depois do corte do turno. O estado "3 de 5" que ficou escrito
no relatorio e na memoria **nao e mais o estado atual**. Build final com os 5 itens:
**BUILD SUCCESSFUL em 19s**, `scanEncoding` OK em 96 arquivos, 20 testes sem falha, e
`check-catalogo.ps1` sem divergencia. Nada commitado: o C05 (`d29d5ee`) continua sendo o
ultimo checkpoint, por decisao do usuario.

- **FATO:** acrescentar um campo **no fim** de um `StreamCodec.composite` e simetrico por
  construcao, porque `composite` pareia encoder e decoder posicao a posicao. O aviso de
  "encoder e decoder sao lambdas independentes" vale para os `VAR_INT` manuais do
  `SheetModel`/`SheetData`, **nao** para o `composite` do `SheetSkillPayload` nem para o
  `STREAM_CODEC` do `Identity`. Ainda assim, campo no fim e a unica ordem que sobrevive a
  uma base ja em uso: qualquer campo no meio desloca os seguintes.
- **FATO:** `Identity.playerName` e o **quinto** componente do record, no fim, com
  `clean(..., MAX_NAME, "")`; vazio e valor legitimo. Persistencia por
  `optionalFieldOf(..., "")`, que mantem a leitura das fichas salvas antes da mudanca.
- **FATO:** `SheetData.labelOf` pergunta ao **modelo** primeiro, e o `default` do
  `SheetModel.labelOf` devolve a **propria chave** (`playername` -> "playername"). Como a
  ficha nao usa chave de traducao, o caso novo devolve o literal "Player" **no modelo** e
  nao no `SheetData`: um caso novo em `SheetData.labelOf` seria codigo morto.
- **FATO:** o `SkillsScreen` guarda a selecao por **nome** e reencora o **indice** pelo
  nome a cada ficha recebida (`onSheetReceived`). Sem isso, apos um add/remove/move o
  `Edit` carregava e o `Save` gravava na skill que deslizou de posicao.
- **FATO:** o servidor recusa em silencio o `update` de skill quando o indice esta fora da
  faixa **ou** quando o nome em diante nao bate com a skill naquele indice
  (`RpgNetworking.updateSkill`). E a defesa contra reordenacao entre o clique e o Save; a
  recusa silenciosa segue o padrao do resto do receptor.
- **FATO:** `SheetData.withSkill(int, String, String)` **recusa** renomear para um nome que
  outra skill ja usa, porque `sanitizePericias`/`sanitizeSkills` mantem a primeira e o
  resultado viraria dependente da ordem da lista.
- **FATO:** o modo de edicao do `SkillsScreen` **nao sobrevive a um `init()`**. E
  deliberado: um `Save` sobre texto que o jogador nao digitou e pior do que voltar ao modo
  `Add`. O `Save` em modo edicao tambem nao limpa as caixas, porque a saida vem do estado
  autoritativo do servidor.
- **FATO (corrige a skill `catalogo-sync`):** `FUNCIONALIDADES-E-COMANDOS.md` **esta
  versionado** neste repositorio (`git status` mostra ` M` nas edicoes desta rodada). A
  skill afirma que o arquivo nao esta no git e que nao ha como recuperar a versao anterior.
  Aqui da para recuperar pelo git, entao o cuidado e menor do que a skill sugere.
- **HIPOTESE:** o campo `Player` com meia largura e piso de 60px (`max(60, boxW / 2)`) fica
  legivel, e o `neededRows + 1` so aumenta a rolagem da coluna, sem quebrar layout, mesmo
  em resolucao baixa. So o jogo confirma.

### 2026-09-29 - teste em jogo dos 5 itens e o titulo da secao

- **FATO:** o usuario **testou em jogo os 5 itens** desta rodada e disse que "o restante ta
  funcionando normal". O unico problema relatado foi o titulo da secao da ficha, que
  aparecia como `Name` e ele queria `Identity`.
- **FATO (corrige o relato, nao o codigo):** **nao existe literal `"Identity"` no codigo**,
  nem no `HEAD`. O titulo da primeira secao da ficha era
  `addSection(model.nameLabel(), ...)` em `StatusScreen.buildPanel`, e o default de
  `nameLabel` e `"Name"` (`SheetModel.java: SheetModel`, tanto no construtor compacto quanto
  no `optionalFieldOf("nameLabel", "Name")` do codec). **A linha nao estava no diff desta
  rodada**, entao o campo Player nao causou a troca. Os outros tres titulos (`Vitals`,
  `Progress`, `Attributes`) ja eram literais: aquele era o unico que punha o rotulo do
  **campo** no lugar do nome da **secao**, e com modelo padrao a ficha mostrava `Name` como
  titulo e `Name:` como primeiro campo.
- **FATO (correcao aplicada):** o titulo virou o literal `"Identity"`, igual aos outros tres.
  O que continua vindo do modelo e o rotulo do **campo** (`SheetData.labelOf`), que e o que o
  Mestre renomeia no Sheet Editor. **Titulo de secao e rotulo de campo nao sao a mesma
  coisa** — essa confusao ja produziu um relato de bug com causa attribution errada.
- **FATO:** `model.nameLabel()` segue em uso na ficha para o **rotulo do campo**
  `characterName` (`StatusScreen.java: addField`, via `SheetData.labelOf`). Se um dia o titulo
  da secao virar configuravel, o caminho e um rotulo novo no `SheetModel`, e nao reaproveitar
  o `nameLabel`.
- **Pendente de teste em jogo:** a correcao do titulo (mudanca de literal, build verde, risco
  baixo) e a lista da secao "Proximos passos" deste bloco. O catalogo nao precisou mudar,
  porque nao documentava a origem do titulo.

### 2026-09-29 - bloco de vida/mana em 3 linhas e renome para Player Sheet

O usuario pediu mudanca maior de layout: o bloco de vida passou a 3 linhas por recurso
(titulo do modelo | teto + barra maior | 6 botoes de passo) e o botao `Status` do menu virou
`Player Sheet`. Nada commitado; aprovado por ele, validado so por build, testes e revisao.

- **FATO (o bug mais importante da rodada):** uma contagem de linhas de layout que so aparece
  em `optionalRows` como **subtracao**, e nunca na base, fica curta **pelo valor subtraido** —
  e o erro vale **nos dois casos**, com a feature ligada e desligada. Em `StatusScreen.buildPanel`
  as 3 linhas da Mana entravam so como `isEnabled("mana") ? 0 : 3`, entao `neededRows` era 3
  linhas curto, `maxLeftScroll` ficava curto, e os ultimos atributos ficavam **inalcancaveis** e
  ainda desenhados fora do painel, disputando espaco com o `Back`. **Build, 20 testes,
  `scanEncoding` e `check-catalogo.ps1` aprovavam com o defeito.** So a revisao de codigo achou.
  Conta correta: `3 + 5 + 3 + 3 + 2 + attrRowCount` (3 titulos + 5 campos de identidade +
  3 de HP + 3 de Mana + 2 de Progress + `attrRowCount`).
- **FATO:** `CharacterSheetScreen.reconcilePendingNumeric` valida os pendentes de
  `pendingNumeric` contra `sheet.attributeValueMin()/attributeValueMax()`, o intervalo do
  **ATRIBUTO**, e nao contra o piso do recurso. Com um botao de passo de 10 isso vira
  alcancavel em **um clique**: Mana 0 -> pendente -10 -> servidor corta para 0 -> eco traz 0 ->
  `-10 != 0` e -10 esta em [-30,30] -> o pendente sobrevive e a tela fica presa mostrando
  "-10" com a barra vazia. **A correcao certa e clampar na origem** (`stepNumeric`, antes de
  gravar e enviar, com `if (next == base) return;`), nao mexer na reconciliacao: passo que nao
  muda nada nao vira pendente. Os ganchos `numericFloor`/`numericCeiling` com default
  ilimitado evitam endurecer o caso comum.
- **FATO:** o padrao de rolagem da `StatusScreen` ja existia para a coluna de pericias —
  `perScroll` + `clampPerScroll` + `isOverPericiaColumn` + `rebuildWidgets()` dentro de
  `mouseScrolled`, com `int step = scrollY < 0 ? 1 : -1;`. A coluna esquerda replicou o mesmo
  (`leftScroll`, `maxLeftScroll`, `clampLeftScroll`, `isOverLeftPanel`) e **nao** foi preciso
  inventar scroll nem barra visual: quem rola e so com o cursor em cima da coluna, e o offset
  so e construido quando `maxLeftScroll > 0`. Rolar recria widgets, entao ha um guarda de
  campo com foco (`isEditingField`) que trava a rolagem para nao matar texto em digitacao.
- **FATO:** `check-catalogo.ps1` **nao** pega referencia a simbolo morto nem texto
  descritivo desatualizado. Ele compara comando executavel, contagem de linhas da tabela de
  teclas e nome de tela. As tres referencias a `addResourceRow` (metodo removido nesta rodada)
  so apareceram por leitura do documento.
- **FATO:** o titulo do bloco de vida vem do modelo (`model.hpLabel()` / `model.manaLabel()`,
  defaults `HP`/`Mana`, ja editaveis no Sheet Editor), entao **nenhum campo novo** foi criado
  em `SheetModel`, codec, NBT ou payload. O cabecalho `Vitals` foi **removido** e substituido
  pelo titulo do recurso.
- **FATO (corrige o rotulo):** `RpgMenuScreen.java: buildMenu` tem o literal do botao da
  ficha; era `Status` e virou `Player Sheet` em 29/09/2026. A **classe** continua
  `StatusScreen` e o titulo desenhado no topo da ficha continua `Sheet: <jogador>`
  (`CharacterSheetScreen.java: titleText`) — o usuario decided renomear **so** o botao. Nao ha
  chave de traducao envolvida: `en_us.json` nao tem nenhuma ocorrencia de "status".
- **HIPOTESE:** `rowH` no piso de 12 continua legivel com 3 linhas por recurso, e a barra com
  `rowH - 2` de altura comporta o texto de 8px sem sair das bordas. So o jogo confirma.
  **29/09/2026: confirmado pelo usuario** — a legibilidade passou, o bloco de 3 linhas e os 6
  botoes funcionaram.
- **FATO (bug achado pelo usuario no primeiro teste, 29/09/2026):** `drawY` so cortava as
  linhas **acima** do topo (`y + rowH <= contentTop`); as linhas **abaixo** da janela visivel
  eram montadas no Y real e apareciam sobre o rodape e o botao `Back`. `maxLeftScroll` limita
  o quanto rola, mas nao esconde o que esta fora da janela — as duas metades do problema. O
  corte de baixo tem que usar **`leftBottom`** (e nao `contentBottom`), porque e o mesmo par
  que `maxLeftScroll` usa em `visible = (leftBottom - leftTop) / rowH`: com o mesmo criterio
  nas duas contas, `maxLeftScroll = leftTotalRows - visiveis` garante a ultima linha exata.
  Corrigido para `foraDoTopo || foraDoFundo` mandando a linha para `this.height + 64`.
- **FATO (padrao que vale para qualquer rolagem por linhas):** a coluna de pericias nunca teve
  esse bug porque **nao constroi** a linha que nao cabe — o laco vai ate `perVisibleCount`. A
  coluna esquerda constroi tudo e esconde o que nao cabe. Prefira **nao construir** a
  esconder; quando nao der, o corte tem que ser nas **duas** pontas e com a **mesma** conta do
  `maxScroll`. O sintoma (texto sobre o `Back`) e indistinguivel do bug de `neededRows` curto:
  nos dois casos e transbordo de layout, e nenhum build, teste unitario ou detector de
  catalogo pega transbordo — so o jogo. **Nao valide layout por aritmetica de constantes.**
- **FATO (29/09/2026, asimetria entre as colunas):** duas colunas no mesmo painel so parecem
  iguais se **o topo vem da mesma variavel** e **o rodape e o mesmo resto de linha**. A
  `StatusScreen` tinha as duas falhas: `perTitleH` era `rowHOrDefault` (divisao por **12 linhas
  fixas**) contra o `rowH` real de `fitRowHeight` (divisao pelas ~19 linhas reais), e a coluna
  de pericias **estica** (`perAvail / perCount`) enquanto a esquerda terminava numa fronteira
  de `rowH`. As duas so coincidiam por acaso, quando ambas batiam no `MAX_ROW_H` de 20 — por
  isso o desalinhamento so aparecia em janela baixa, e valia ate **8px** no topo e **11px** no
  rodape. Correcao: `perTitleH = rowH` (topo igual **por construcao**) e
  `rowH = disponivel / linhasVisiveis` na esquerda (mesma conta de preenchimento da direita),
  com o clamp da rolagem **depois** do ajuste. Sem risco de passar a rolar: quando cabe,
  `linhasVisiveis >= leftTotalRows` e o novo `rowH` fica <= o antigo.
- **FATO (mesmo caminho, bug latente):** `perVisibleCount()` usava `contentTop`/
  `contentBottom` (externos, PANEL_PAD a mais) enquanto o `buildPanel` desenha em
  `leftTop`/`leftBottom` (internos). A conta superestimava as pericias visiveis, `maxPerScroll`
  ficava curto e a ultima pericia podia ficar inalcancavel. **E a terceira vez** que a mesma
  classe de bug aparece nesta tela: `neededRows` curto, `drawY` sem corte de baixo, e contagem
  com os limites errados. **Nenhum dos tres foi pego por build, teste unitario, `scanEncoding`
  ou `check-catalogo`** — todos os tres so apareceram em revisao de codigo ou no jogo.
- **FATO (aprendizado de metodo):** "as colunas estao desigual" e um sintoma de **duas**
  assimetrias possiveis (topo e rodape), e cada uma tem uma correcao diferente. Vale ler a
  geometria dos dois lados antes de propor ajuste, e perguntar o alvo quando as correcoes tem
  consequencias visuais diferentes — aqui grow de `rowH` em 1-2px. O que ajudou a decidir foi
  numeros: `rowHOrDefault`/`12` vs `fitRowHeight`/`19` com `MIN_ROW_H=12` e `MAX_ROW_H=20`
  dao a diferenca maxima exata, e ela so aparece abaixo da faixa em que as duas batem no teto.
  **CONFIRMADO EM JOGO pelo usuario em 29/09/2026:** a diferenca de altura ficou normal.
- **FATO (29/09/2026, ORDEM DE DESENHO — o mais facil de errar desta tela):** em
  `CharacterSheetScreen.render` a ordem e titulo -> `super.render()` (linha 930, **desenha os
  widgets**) -> "Editable" -> `renderTopLeft` -> `textLines` -> `renderContent` (linha 954).
  Portanto **qualquer coisa que um WIDGET mostra tem que estar pronta antes do primeiro
  `super.render()`**, ou seja no `init()`/`buildPanel`/`applyExtraState` — nunca no
  `renderContent`. O sintoma e um **frame em branco** que so aparece quando os widgets sao
  recriados (scroll, resize, troca de modelo), entao parece "pisca" e e facil de culpar a coluna
  errada. Foi exatamente assim com a sigla do botao de atributo da pericia: nascia
  `Component.literal("")` e era escrita em `drawPericias` (dentro de `renderContent`), um frame
  atras. `applyExtraState()` roda no fim do `init()` (via `applySheetToWidgets`), e e por isso
  que o `active` dos botoes nunca piscou — so o `message` piscava. **Regra:** `active` e
  `message` de um widget tem origens diferentes no ciclo de vida; tratar as duas juntas.
- **FATO (mesmo caminho, por que as DUAS colunas piscavam):** `mouseScrolled` chama
  `rebuildWidgets()` nos dois ramos, e ele limpa a lista de filhos e refaz o `init()` inteiro —
  entao rolar a coluna esquerda recria os widgets da coluna de pericias tambem. **Consequencia
  mais grave que o pisca:** o texto em digitacao e a rajada de `HoldStepButton` morrem a cada
  rolagem (da para a rolagem estar bloqueada com um campo em foco, e nao com rajada ativa).
  Isolar as colunas exigiria mexer no `init()` compartilhado com o `SkillsScreen` — mudanca de
  arquitetura de tela, **nao feita**; o usuario so pediu o sintoma e ele foi corrigido na origem.

### 2026-09-29 - rodada fechada em jogo e checkpoint

O usuario testou em jogo, nesta ordem, e as tres correcoes passaram: o corte de baixo do
`drawY` ("deu certo, mas so uma coisa"), o alinhamento das duas colunas ("a questao da
diferenca de altura esta normal agora") e o pisca do botao de atributo ("perfeito"). **As
hipoteses que sobraram nesta tela tambem foram confirmadas na pratica**: `rowH` no piso de 12
fica legivel com 3 linhas por recurso, e a barra com `rowH - 2` comporta o texto de 8px.

**Nao exercitado por ele, e portanto sem validacao em jogo:** Mana **desligada** pelo Mestre,
modelo com 30 pericias, janela mais baixa que a que ele usou, e persistencia de vida/mana
depois de fechar e reabrir o jogo. Nada disso quebrou build ou catalogo; sao lacunas de
teste, nao bugs conhecidos.

**Checkpoint:** tag `checkpoint-20260929-1030-bloco-vida-mana-ficha`, na branch `main`, sem
push. O commit fecha as **duas** rodadas acumuladas (a de 5 itens de 28/09 e esta), porque o
protocolo e nao commitar em partes. O hash esta em `GitHub\agent\VERSIONAMENTOS.md` (fora do
repo, de proposito: escrever o hash na memoria do projeto deixaria a arvore suja de novo).

## 2026-09-29 - "a build travou" era o AGENTE, nao o Gradle (FATO verificado duas vezes)

O usuariointerruptou de novo com "vc esta travado na build". **Investigacao por evidencia:**

- `.\gradlew.bat compileJava --console=plain --offline -q` devolveu `exit=0`.
- `build/classes/java/main/com/pedro/tabletoprpg/DiceFormula.class` existe, escrito as 15:10:04.

Ou seja: **a build rodou em segundos e passou.** O gradlew ja tinha terminado quando
o usuario percebeu o atraso. O que travou foi a **emissao do proximo turno** depois
que a ferramenta retornou, que e o modo de falha ja descrito em `AGENTS.md`
("turn latency and context discipline"), e nao I/O lento.

**O que dispara isso, medido nesta rodada:** escrever um arquivo de ~830 linhas em
uma unica chamada e depois fazer 6 ciclos seguidos de read/edit sobre o MESMO arquivo
grande, lendo faixas de 80-130 linhas cada. A soma disso satura o contexto, e o
custo de produzir o turno seguinte passa a ser o gargalo, nao a build.

**Como nao repetir (regra pratica, nao e teoria):**
- Apos qualquer comando gradlew, trate o resultado como TERMINAL. Nao releia, nao
  reexecute, nao reconfirme. `-q` sem saida **e sucesso**; so investigue se
  `exit` for diferente de zero.
- Para arquivo novo grande: escrever, compilar, e entregar a verificacao a um
  subagente. Nao voltar a ler o proprio arquivo em faixas para conferir.
- Lote as edicoes: escreva todas as edicoes de um arquivo antes de compilar, em
  vez de compilar entre elas.
- Se a sensacao de travamento voltar: gravar o estado em disco e encerrar o turno
  com UMA linha de status. Retomar no turno seguinte, com contexto pequeno, e
  mais barato do que insistir no mesmo turno.

Checkpointer em `agent/reports/2026-09-29_checkpoint-motor-formulas.md`.

## 2026-09-30 — `X#` passa a repetir a formula inteira, e critico tem padrao

**FATO verificado** (80 testes + log de jogo `run/logs/latest.log`): `N#` nao repete mais
so o dado seguinte, repete **a formula inteira**. Regra da gramatica, tal como ficou em
`DiceFormula.java: parseGroup` / `parseTerms` / `parseParenthesized`:

- com parenteses: `4#(2d6+1d8+5)` repete a subformula entre parenteses (aninhamento
  permitidos, ex.: `2#(1#d6+2)`);
- sem parenteses: repete **todo o resto da formula no nivel corrente**, parando **antes
  do proximo `N#`** — por isso `2#d20+3#d4` vale `2 x d20 + 3 x d4`;
- o `N#` **nao fecha o grupo no primeiro termo**; e exatamente isso que permite aninhar;
- `repeat == 1` deixa o parenteses transparente: `(2d6+3)` mostra os termos soltos;
- o rotulo da linha do grid e o **trecho digitado** (`Group.formulaLabel`), sem remontar
  a formula.

Consequencia economica: antes `2#d20+5` era `(d20+d20)+5` (constante avaliada fora do
laco, em `rollTerm`) e o `+ 5` aparecia colado na ultima linha por concatenacao em
`MasterCommands.repeatBody`, o que parecia "somou so na ultima". No jogo, `2#d20+5`
deu `[6] = 11` e `[19] = 24`, total 35.

**FATO verificado — regra de critico (decisao do usuario em 30/09/2026):**
`DiceFormula.java: rollOneDie` agora faz `critAt() >= 0 ? value >= critAt() : value >=
sides()`. Sem `cN` o critico e o **valor maximo do dado** (`d20` = 20); com `cN` vale o
`cN`, que **sobrepoe** o maximo. Antes o critico so existia com `cN` (o teste
`noCriticalWithoutSuffix` virou `criticalWithoutSuffixUsesMaxFaces`). Dado descartado
nunca e critico.

**FATO verificado — onde a cor mora:** `Face` e `Round` carregam **booleanos**
(`Face.critical`, `Round.critical`); `§` so em `MasterCommands.java: facesBody`
(`§e` no dado critico, `§r§b` para devolver o aqua) e `: repeatBody` (`§c§l CRIT` no fim
da linha). `checkCatalogo` invariant: `DiceFormula.java` nao emite `§` e nao importa
Minecraft — e o que mantem o motor testavel com JUnit. `scanEncoding` reprovaria `§`
nesse arquivo.

**FATO verificado — armadilha do `Budget`:** para dizer **qual** linha foi critica, olhe
as faces da propria volta (`anyKeptCritical(List<Face>)`), **nao** `Budget.groupCritical`,
que e acumulado da rolagem inteira e serve para o `CRIT` global do fim da mensagem.

**FATO verificado — catalogo:** `cN` nao estava documentado em
`FUNCIONALIDADES-E-COMANDOS.md` antes desta rodada; a regra de `#` dizia "repete a
formula" quando so repetia o primeiro dado. Corrigido em 5 trechos. O catalogo e
**untracked**, entao nao ha rede de seguranca do git: edicao por ancora, nunca
sobrescrita.

**FATO verificado — `check-catalogo.ps1`:** (1) precisa de `-ProjectRoot` explicito
quando o diretorio de trabalho da sessao nao e a raiz do mod; (2) ele acusa
`/rpg/roll/Pericia/0-2` como "comando que nao existe mais", e isso e **falso positivo**:
o script compara literais registrados no codigo, e esse texto e exemplo de **entrada do
jogador**. O caminho de cauda existe (`MasterCommands.java: splitPericiaPrefix`, devolve
`MixedRoll`). Divergencia pre-existente de 30/09/2026, nao corrigida.

**HIPOTESE (nao testado):** `Part.repeated` nao expoe faces, entao um `#` aninhado
dentro de outro nao mostra os dados internos entre colchetes na linha de fora (o subtotal
esta certo). Nao foi pedido e nao foi corrigido.

Relatorio: `agent/reports/2026-09-30_grade-X-hash-A-e-critico-por-linha.md`.
Commit/tag **nao feitos** (so mediante pedido explicito).

## 2026-09-30 (2a rodada) — total da grade em linha propria

**FATO verificado:** com `#`, o total sai em linha propria `TOTAL = XX`
(`§6§eTOTAL §f= §l§a`), e o `= XX` que ficava colado no fim da ultima volta **saiu**,
porque ali se confundia com o subtotal dela (`MasterCommands.java: rollDice`, com o
prefixo montado em `totalPrefix`). **So na grade** (decisao do usuario): rolagem simples
continua numa linha, com o total inline. O mesmo vale no caminho da pericia com cauda
(`rollPericia`), protegido por `outcome != null` porque ali o `outcome` e nulo quando
nao ha cauda.

**FATO verificado:** grade se detecta por **peca repetida** (`MasterCommands.java:
hasGrid`, `part.repeat() > 1`), nunca procurando `#` no texto — o texto da cauda pode
ter o caractere sem ser grade.

**FATO verificado — armadilha de edicao:** ancorar um `edit` numa linha interna de
javadoc (por exemplo ` * As faces entre colchetes...`) insere o texto novo **depois** do
`/**` que abre o bloco, deixando um `/**` orfao e o resto do javadoc sem dono. O build
**nao pega** isso: `/**` orfao vira comentario e o codigo seguinte some do fonte. Conferir
a regiao depois de editar dentro de javadoc, ou ancorar na linha `/**` completa.

**FATO verificado — scanEncoding pega o que o olho nao pega:** um caracterere cirilico
(U+043A) e tres ideogramas CJK que escapei num relatorio novo reprovaram o build
inteiro (`[scanEncoding] FAIL: 3 caractere(s) proibido(s)`), enquanto o `MasterCommands.java`
era limpo. O relatorio nao existed no build anterior, entao a falha parecia nao ter causa.
Como os caracteres proibidos estavam no meio da frase, o `edit` exigia digitá-los; a
reparacao foi por codepoint em PowerShell
(`[char]0x043A`, etc. + `[System.IO.File]::WriteAllText` com `UTF8Encoding($false)`),
**sem converter o encoding do arquivo inteiro**. Para achar, varrer por faixa de
codepoint em vez de procurar o texto no console (que nao renderiza UTF-8 fiel).

**HIPOTESE (nao testado em jogo):** o layout exato da linha `TOTAL = XX` (cor, posicao do
`CRIT` global, e a rolagem de pericia com cauda) so foi conferido por codigo e build; o
usuario ainda nao viu a saida. Confirmado pelo usuario em 30/09/2026: **funcionou**.

## 2026-09-30 (rodada 3) — dado critico em negrito e CRIT fora do TOTAL

**FATO verificado:** o dado critico no chat e **amarelo e em negrito** (`§e§l` em
`MasterCommands.java: facesBody`). O `§r§b` de volta continua obrigatorio porque `§r`
zera o **estilo** junto com a cor: sem ele o proximo dado da lista sairia negrito tambem.

**FATO verificado:** **na grade o `CRIT` global nao e mais escrito** (decisao do usuario).
Quem marca o critico e a propria linha, e o `TOTAL = XX` fica limpo. O `CRIT` global
continua nas rolagens **sem** grade: `rollDice` e `rollPericia` ganharam
`outcome.critical() && !hasGrid(outcome)`.

**FATO verificado — armadilha do `edit`:** um `oldString` com indentacao menor casa
como **substring** dentro da indentacao maior, e o `edit` aplica no bloco errado. Numa
das tres edicoes do mesmo pedido, a de 8 espacos mirou `rollPericia` (16 espacos) em vez
de `rollDice`. Ancore sempre com contexto unico e identico (linha vizinha propria) e
confira com `Select-String` qual bloco mudou.

**FATO verificado — limitacao da ferramenta:** o `edit` **normaliza o espaco inicial do
parametro**, entao nao da para corrigir indentacao colocando os espacos no comeco da
`newString` (o `oldString` e a `newString` chegam identicos e a edicao e recusada como
"no changes to apply"). Saida: `oldString`/`newString` de varias linhas, ou PowerShell
com replace por codepoint.

## 2026-09-30 (rodada 4) — subtotal da volta critica em negrito

**FATO verificado:** o pedido era "negrito no resultado do dado que critou **tambem**", e o
que faltava era o **subtotal da volta**: `MasterCommands.java: repeatBody` escreve
`" §f= §a" + (round.critical() ? "§l" : "") + subtotal`. A face ja era `§e§l` e o `CRIT`
ja era `§c§l`. A rolagem **sem** grade ja entregava o total em negrito (`§l§a` no
`totalPrefix`), entao a diferenca so aparecia na grade.

**FATO verificado — negrito vaza no chat do Minecraft:** `§l` e estilo e **nao** cor, e
`§r` zera estilo **e** cor. Como a proxima linha da grade comeca com `\n§b ` (que so
muda a cor), o negrito da volta critica continuava no rotulo da linha seguinte. Por isso
a volta critica agora fecha com `§r§b`, e o mesmo motivo exige `§r§b` em `facesBody`
depois do dado critico.

**FATO verificado:** `§b` neste codigo e **azul (aqua)**, nao negrito; negrito e `§l`.

**FATO verificado:** comentario de codigo **envelhece** quando a regra em volta muda. O
comentario do `CRIT` de linha ainda afirmava que "o CRIT do fim da mensagem segue
valendo", depois de a grade ter parado de escreve-lo. Alteracao de comportamento exige
reler o comentario vizinho, nao so o codigo.

## 2026-09-30 (rodada 5) — cada tipo de dado no seu colchete dentro da grade

**FATO verificado:** a linha da volta na grade **achatava** todos os tipos de dado num
colchete so, porque `rollFormulaGroup` montava a linha a partir de `facesOf(roundParts)`,
que junta as faces de todos os termos. Agora `DiceFormula.Round` carrega `parts` (a lista
de `Part` daquela volta) e `MasterCommands.java: repeatBody` monta a linha com
`appendRollTerm(body, inner.sign(), appendTermBody(inner))` — os **mesmos** metodos que
`joinParts` usa na rolagem sem grade. Resultado: `4#2d20+2d6` sai
` 2d20 [5, 12] + 2d6 [3, 6] = 26`. A constante dentro da formula repetida aparece como
termo (`+ 5`), e `6#4d6dl1` **nao muda**: volta so de dado recebe `List.of()` em `parts` e
continua no rotulo + faces achatadas. `Round.faces()` (a lista achatada) foi **mantida**,
porque e ela que alimenta `anyKeptCritical` e varios testes.

**FATO verificado — correcao de memoria:** a limitacao "faces de um `#` aninhado nao
aparecem na linha de fora" (HIPOTESE de 30/09/2026) esta **resolvida**: o bloco interno
passa a ser renderizado por `appendTermBody` -> `repeatBody`, com quebras de linha.

**FATO verificado — subagente pode devolver vazio:** a implementacao delegada voltou sem
texto nenhum, sem erro. O codigo **estava** pronto. `git diff --stat` nao prova nada
nesse caso (e cumulativo desde o HEAD); confirmei com `Select-String` procurando o simbolo
novo (`Round.parts`, `round.parts()`) e so depois compilei. **Delegar nao substitui
conferir o simbolo novo no arquivo.**


---

## 30/09/2026 - Block Lock, rodada 2 (correcoes de teste em jogo)

**FACT (block parts): `DoubleBlockHalf` so tem `UPPER` e `LOWER`.** Nao existe valor
"unico". `BlockStateProperties.DOUBLE_BLOCK_HALF` e o sinalizador correto de bloco de DUAS
posicoes. Verificado com `javap` em `DoubleBlockHalf` e em `DoorBlock`, `TrapDoorBlock`.

**FACT (block parts): `HALF` NAO significa segunda posicao.** Em slab, escada e armadilha
`Half` e **formato** do mesmo bloco. Uma versao de `blockParts` que aceitava `HALF` trancava
tambem o bloco de ar acima de uma escada. Cama usa `BED_PART` (`BedPart`), nao `HALF`.
Conclusao: pare a deteccao de "outra metade" em `DOUBLE_BLOCK_HALF` e so.

**FACT (API): `StateHolder` nao tem `hasValue`.** O nome do metodo e `hasProperty(Property<?>)`;
`getValue(Property<T>)` existe. `hasValue` nem compila -- erro de symbol, nao de runtime.

**FATO (Fabric API): `PlayerBlockBreakEvents.AFTER` so e disparado se NENHUM listener de
`BEFORE` cancelar.** Confirmado no Javadoc da propria API. Consequencia pratica: nao use
`AFTER` para limpar estado de um bloco, porque qualquer outro mod (ou este proprio
`PlayerControlHandler`, que cancela quando o jogador nao pode quebrar) pode cancelar o
`BEFORE` e o seu `AFTER` nunca roda. Limpou o estado no `BEFORE`, depois da checagem.

**FATO (remapeamento): literal de string nao e remapeado.** O carregador reescreve
referencias de classe do Minecraft (nomeado -> intermediario), mas o nome do metodo numa
string literal continua literal. Portanto `getDeclaredMethod("useWithoutItem", ...)` pode
lancar `NoSuchMethodException` num metodo que **existe**, e o resultado fica preso num
cache estatico que nunca e reavaliado. Detecte sobrescrita por **assinatura**
(`getDeclaredMethods` + comparar `getParameterTypes()`), que so usa referencias de classe.

**FATO (do proprio relatorio do usuario): porta recusada como "sem interacao para
travar".** `javap` mostra que `DoorBlock` **declara** `useWithoutItem` com a assinatura
exata, ou seja o caminho por nome *deveria* funcionar; a recusa nao foi reproduzida em
teste automatico. O defeito estrutural acima justifica a troca mesmo sem reproduzir o caso.

**FATO (diagnostico dos 3 defeitos): porta de 2 alturas.** Trancar so a posicao clicada
deixava a metade contraparia destrancada; clicar nela abria a porta. Isso explicou de uma
vez "abre mesmo trancada", "abre a 5 blocos" e "abre de um nivel abaixo": **nao era
alcance**, era outra posicao do mesmo bloco.

**FATO (achado em arquivo pre-existente): homografo cirilico.** `TabletopRpgClient.java`
linha 108 tinha "CYRILLIC ve + i + te" dentro de um comentario (`servidor` + `marcou`).
Aparece como "vitou" quando o console renderiza. Encontrado por varredura de codepoint
(CJK + cirilico + U+FFFD) em 104 arquivos. Reparo **byte a byte**: os bytes UTF-8 da
sequencia, nunca um round trip do arquivo inteiro.

**PowerShell 5.1 (relembrado):** `Get-ChildItem -Recurse -Include` com varios caminhos
de uma vez, em paths relativos, devolveu 10 arquivos em vez de 104. Use caminho absoluto
e filtre por `Extension` com `Where-Object`. E slicing de array de bytes (`[byte[]](...)[0..6]`)
**perde o ultimo elemento**: use array de tamanho correto ou `Set-StrictMode`/validacao de
comprimento antes de escrever.

## 2026-09-30 - Block Lock: aura do Mestre (rodada 3)

### FATO - write e edit podem reportar SUCESSO sem gravar
Dois arquivos novos criados com `write` retornaram "Created" e NAO existiam no disco.
Busca recursiva em todo o repo: zero resultados. Uma edicao de memoria tambem nao
gravou. Conclusao: **o retorno da ferramenta nao e prova de gravacao**.
REGRA: depois de criar arquivo, verificar com Test-Path + contagem de bytes.
Depois de editar, verificar com grep do simbolo procurado.

### FATO - build verde NAO prova que a feature existe
O gradle so compila o que esta no disco; ele nao sabe o que DEVERIA existir. Compilei
com sucesso um codigo que referenciava uma classe inexistente. Verificar que a classe
esta DENTRO do jar (build/libs/*.jar) antes de dizer que a feature esta pronta.

### FATO - cannot find symbol no consumidor costuma ser erro no DECLARANTE
`TabletopRpgClient.java` acusava `cannot find symbol: variable BlockLockAura` (3x),
mas a causa era `BlockLockAura.java` nao existir. Procurar o erro no arquivo apontado
perde tempo.

### FATO verificado por javap - API 1.21.11 (corrige suposicoes erradas desta rodada)
- `WorldRenderEvents` e `WorldRenderContext`: pacote
  `net.fabricmc.fabric.api.client.rendering.v1.world` (NAO `...rendering.v1`).
- `Camera`: metodo `position()` (NAO `getPosition()`).
- `MultiBufferSource`: pacote `net.minecraft.client.renderer`. A interface tem SO
  `getBuffer(RenderType)`; `endBatch(RenderType)` esta em `MultiBufferSource.BufferSource`
  (o nested chama-se `BufferSource`, NAO `Immediate`).
- Classe de recurso: `Identifier` (NAO `ResourceLocation`).

### FATO verificado - profundidade em 1.21.11 vive no RenderPipeline
`RenderSystem.disableDepthTest()` nao sobrevive ao bind do pipeline no draw. Nenhum
evento do Fabric entrega RenderType que atravessa parede. `RenderType.create` e
package-private: a saida sem mixin e um split package em
`net.minecraft.client.renderer.rendertype`.
DECISAO DO USUARIO: split package + contorno (nao preenchimento).
`ShapeRenderer.renderShape` desenha ARESTAS (`forAllEdges` + `setLineWidth`); o `float`
e largura de linha, nao alpha.

### PENDENTE - aura nunca validada em jogo
O `RenderType` customizado e criado sob demanda, na primeira vez que a aura desenha.
Se for invalido, estoura em RUNTIME, nao em build. Primeiro suspeito se estourar:
`ShaderDefines` (nao copiados) ou `withColorLogic`.

## 2026-09-30 - Block Lock: aura nao aparecia (causa raiz)

### FATO - a aura nao desenhava porque o passe nunca foi registrado
`onInitializeClient()` chamava `registerNetworking()` e `registerConnectionCleanup()`,
mas NAO `BlockLockAura.register()`. Sem essa chamada, `WorldRenderEvents.END_MAIN`
nunca recebia o listener: o estado das trancas chegava no cliente e ninguem lia.
Build passava, item na mao nao fazia nada, e nao havia pista no jogo.

### LICAO - feature que so existe no cliente precisa de prova de que foi LIGADA
Compilar nao prova que algo roda. Para qualquer listener novo (render, evento, tick),
confirmar com grep que o metodo de registro e CHAMADO em algum lugar, e nao apenas que
existe. Sintoma tipico: "nao faz nada, sem erro" = registro faltando.

### FATO - diagnostico barato para cliente invisivel
`BlockLockAura` agora tem `diagnose(String)`: loga uma linha unica por motivo de
bloqueio (isMaster falso / sem tranca na dimensao / sem item na mao / desenhando N).
Deduplicado por `lastDiagnostic` para nao spammar a cada frame. Quando uma feature de
cliente nao aparece, o log diz em qual etapa parou, sem precisar de adivinhacao.

## 2026-09-30 (fase 1 das abas) — 3 abas na ficha, so UI

**FATO verificado:** as abas vivem em `StatusScreen`, **nao** em `CharacterSheetScreen`.
A base e compartilhada com `SkillsScreen` (`CharacterSheetScreen.java:46`), entao mexer
na base teria quebrado a tela de Skills. O gancho ja existia: `buildPanel(x0, panelW,
topY, bottomY)` e abstrato, e `init()` so entrega as coordenadas e cuida do rodape
(`:509-546`). `buildPanel` virou despachante e o corpo antigo virou
`buildCharacterTab`; as abas 2 e 3 nao criam widget nenhum nesta fase.

**FATO verificado:** guardar scroll por aba **nao** pode ser feito em `init()`, porque
`init()` tambem roda em resize, troca de modelo e rolagem (`rebuildWidgets()` chama
`init()`, `CharacterSheetScreen.java:505`). A troca mora em `changeTab`, que salva o valor
antigo no array da aba antiga e carrega o da nova antes do `rebuildWidgets()`.

**FATO verificado (API 1.21.11, conferido no jar remapeado):** `Button.Builder.tooltip`
recebe **`Tooltip`**, nao `Component` — o certo e `Tooltip.create(Component.literal(...))`.
`AbstractWidget.active` e um **campo publico** (mesmo padrao ja usado em
`SheetEditorScreen`), e e assim que a setinha da ponta fica desabilitada.

**FATO verificado:** o nome da aba precisa ser desenhado **fora** de `textLines` (a lista
da base rola com o conteudo) e **depois** de `super.render()`. Dentro da lista, o rotulo
sobe e desce com a coluna e deixa de dizer em que pagina o jogador esta.

**FATO verificado:** a barra de abas **nao** colide com o `Back`. `contentBottom =
height - 30`, a barra ocupa `contentBottom-20 .. contentBottom`, e o `Back` comeca em
`height - 24` (`= contentBottom + 6`). Confirmado por conta, **nao** por imagem.

**FATO verificado — o cliente subiu e nao deu crash:** `BUILD SUCCESSFUL in 1m 20s`,
log sem `Exception`, `Tecla R pressionada -> abrindo RpgMenuScreen` e `Stopping!` com
`All dimensions are saved`. **Mas ele ficou 7 segundos**: o usuario abriu o menu e fechou.
Ou seja, **a aba em si ainda nao foi vista por ninguem** — nao afirmar que a fase 1 foi
validada em jogo.

**FATO verificado — caminho do detector de catalogo:** `check-catalogo.ps1` **nao esta no
projeto nem na pasta `agent`**. Ele fica na pasta da skill:
`C:\Users\Danylo Henrique\.config\opencode\skills\agente-tcc\catalogo-sync\check-catalogo.ps1`.
Rodar com `-File .\scripts\check-catalogo.ps1` falha com "o argumento nao existe".
Resultado atual: **1 divergencia**, `/rpg/roll/Pericia/0-2`, que e exemplo de entrada do
jogador e nao literal do codigo — falso positivo conhecido.

## 2026-09-30 (fase 2A das abas) — Character Appearance e Character Backstory

**CORRECAO de um FATO que eu mesmo escrevi errado:** eu disse que
`StreamCodec.composite` "tem teto de 6 pares" e que por isso o `Identity` de 7 campos
precisaria de `StreamCodec.of`. **Falso**: `javap` no
`minecraft-common-...layered+hash.2198-v2.jar` mostra sobrecargas de `composite` **ate
12 pares** — havia uma de 7 que aceitaria `Identity::new`. A troca para `of` foi feita
assim mesmo (bate com o `of` ja usado no topo do `SheetData` e deixa encoder/decoder
visiveis lado a lado), mas **nao por impossibilidade de compilar**. Nao repita o limite
de 6: ele nao existe.

**FATO verificado:** `appearance` e `backstory` entraram no sub-record
`SheetData.Identity` (7 `String`), **nao** como componente novo do `SheetData`. O motivo
e risco: o NBT do `Identity` ja usa `optionalFieldOf` por chave (ficha antiga sem a chave
carrega com `""`), enquanto o codec de topo do `SheetData` tem ordem sensivel e ~8
pontos de construtor em `withField`. Teto novo `MAX_TEXT = 2_000` com `cleanText`, ao
lado do `clean` de `MAX_NAME` (32); o `Identity` compacto escolhe o teto **pelo nome do
componente**, e cada `withX` reconstroi passando os outros 6 preservados. `String` de
2.000 no `STREAM_CODEC` usa `stringUtf8(MAX_TEXT)` no encoder e no decoder, nessa ordem:
`appearance` antes de `backstory`.

**FATO verificado:** `SheetFieldPayload.value` era `stringUtf8(64)` — **64 caracteres nao
comportam 2.000**. Virou `stringUtf8(2048)`, com folga para o `cleanText` truncar no
servidor antes do pacote estourar. O limite maior vale para **todos** os campos do
payload, nao so para os novos.

**FATO verificado:** `SheetModel.labelOf` tem `default` que devolve o proprio nome do
campo, entao `SheetData.labelOf` (que so alcança o `switch` quando o modelo devolve
**vazio**) nunca era chamado para campo novo, e a tela desenharia "appearance" cru. O
fallback dos rotulos novos foi posto **no topo** de `SheetData.labelOf`, antes de
consultar o modelo.

**FATO verificado:** `MultiLineEditBox.setValue(String)` chama o `setValueListener`
**mesmo quando quem chamou e o codigo** (o `boolean` da sobrecarga so controla overflow
de `setLineLimit`). Logo, preencher a caixa no eco do servidor marca o campo como
pendente e reenvia em vao: precisa de guarda (`suppressMultiLine`) e pular a caixa com
foco.

**FATO verificado:** o texto pendente mora **dentro do widget**, e `rebuildWidgets()` e o
funil de todo caminho que destroi widgets (resize, scroll, troca de modelo). Por isso o
`flush` esta tambem no `rebuildWidgets()`, alem de clique fora (antes do `super`, que e
quem muda o foco), `changeTab` e `removed()` — e nao `onClose()`, para nao criar caminho
de fechamento paralelo.

**FATO verificado:** `AbstractWidget` e `EditBox` **nao** sobrescrevem `mouseScrolled`
(devolvem `false`), e `ContainerEventHandler.mouseScrolled` faz hit test nos filhos. Entao
`if (super.mouseScrolled(...)) return true;` no topo **nao altera** o scroll da aba 1:
la nao ha `MultiLineEditBox`. Isso e o padrao que `SkillsScreen.java:929` ja usava.

**FATO verificado:** `MultiLineEditBox` nao tem `setEditable`; `active = false` e o que
bloqueia digitacao (o `isMouseOver` do `AbstractTextAreaWidget` testa `active && visible`).

**FATO verificado:** `cleanText` faz `trim()`. Digitar espaco no fim e perder o espaco no
eco do servidor e **esperado**, nao bug.

**FATO verificado — o relatorio do subagente pode vir com caractere estranho e o codigo
continuar limpo.** O texto do relatorio da fase 2A saiu com ideograma e palavra em
ingles no meio. O `scanEncoding` do build reprovou **zero** arquivos: o codigo estava
limpo e o defeito era so do texto do relatorio. Nao tratar o relatorio do subagente como
fonte de verdade para o catalogo nem para a memoria — confira o codigo.

**Armadilha minha, ja aconteceu duas vezes:** **nao copie o trecho defeituoso literal
paraillustrates o problema.** Citar "um par de ideogramas chineses" e seguro; colar os
caracteres dentro da aspas leva o defeito para o arquivo novo e reprova o
`scanEncoding`. Descreva, nunca transcreva.

## 2026-09-30 - PAUSA no meio da fase 2B (estado para retomar)

**FATO verificado:** a fase 2A (Character Appearance e Character Backstory) foi
**validada em jogo pelo usuario**: os dois quadrados grandes funcionam, com rolagem. O
relatorio da fase 2A (`agent/reports/2026-09-30_abas-da-ficha-fase2a-texto-grande.md`)
ainda diz "nao validado em jogo" porque foi escrito antes do teste; **esta entrada e a
correcao**.

**FATO verificado - a fase 2B foi CANCELADA no meio e deixou a arvore suja.** A
delegacao do `Inventory` foi interrompida pelo usuario antes de devolver relatorio, e ela
**ja tinha escrito arquivos** nesse instante. Estado da working tree depois do cancelamento:

- esperados da 2B: `InventoryItemScreen.java` (novo) e `SheetDataInventoryTest.java` (novo);
- **inesperado: `SheetModel.java` e `SheetModelCodecTest.java` foram alterados, e os dois
  estavam explicitamente FORA da lista de arquivos que eu autorizei.** Nao aceite sem ler
  o diff: pode ser ajuste legitimo ou pode ter sido o subagente cortando caminho;
- `SheetData.java`, `RpgNetworking.java` e `StatusScreen.java` tem o diff grande da 2B
  **misturado com o da 2A no mesmo arquivo**, entao **nao da para reverter a 2B sem
  passar por cima da 2A**.

**O build NAO rodou depois dessas mudancas: nao se sabe se a arvore compila.** O primeiro
passo ao retomar e `.\gradlew.bat build --no-daemon --console=plain`, para saber em que
estado o cancelamento deixou o codigo, **antes** de revisar ou escrever mais nada.
**Nada foi revertido**: reverter parte seria apagar trabalho, e a 2A esta no mesmo diff.

**FATO verificado:** cancelar uma delegacao **nao desfaz** o que o subagente ja
escreveu. Conferir `git status --short` logo apos o cancelamento e barato e teria dito
isto em segundos.

## 2026-09-30 (fase 2B) — Inventory: itens, peso, e o bug de `<clinit>` no codec

**FATO verificado — o bug mais serio que ja apareceu neste projeto.** Ler
`Inventory.EMPTY` como padrao de `optionalFieldOf` **dentro de `SheetData.CODEC`** devolve
`null` quando um save antigo chega enquanto o `<clinit>` de `Inventory`/`InventoryItem`
ainda esta rodando: `InventoryItem.EMPTY` monta a ficha ao ser construido, o ciclo fecha, e
o JVM devolve o `EMPTY` ainda nulo. O DataFixerUpper explode em `Optional.of(null)`. Como
`SheetData.CODEC` e o codec do NBT da ficha (`PlayerSheetPersistenceMixin`), isso derrubava
**login e salvamento da ficha**, nao so um teste. **Correcao:** construir o padrao na hora
(`new Inventory(List.of(), 0f)`) em vez de ler a constante de outra classe.
**Licao:** em codec com `optionalFieldOf`, o padrao tem de ser **construido**, nunca lido de
uma classe que possa estar em `<clinit>`.

**CORRECAO de FATO meu:** eu disse varias vezes que codec do `SheetData` **nao roda em JVM
isolada** porque `SheetData.<clinit>` precisa do registro vanilla. **Falso.**
`SheetModelCodecTest` faz round-trip de `SheetData.STREAM_CODEC` com `FriendlyByteBuf` e
passa, e codec do DataFixerUpper roda em teste comum. **Exija teste de codec** — inclusive de
NBT e de **ficha antiga sem a chave nova**, que e a garantia de compatibilidade. Esse FATO
falso foi o que me fez escrever "nao tente escrever teste de codec" no briefing, e o
trabalho veio sem a cobertura que teria pego o bug.

**FATO verificado:** eu **proibi** o subagente de editar `SheetModel.java` e
`SheetModelCodecTest.java`, e a restricao estava **errada**: `SheetModel.align()` monta
`new SheetData(...)`, entao o 10o componente e **obrigatorio** ali. Restringir arquivo por
nome quando a mudanca e mecanica e inevitavel so cria conflito com a tarefa.

**FATO verificado:** peso com semantica de `float`: `Math.round(w * 100f) / 100f`.
`1.005f` vale `1.00499999523`, entao arredonda para **1.0**, e nao 1.01 como daria
`BigDecimal`. `totalWeight()` arredonda a soma **so no final**, senao 50 itens acumulando
erro de float fariam o "passou do maximo" piscar. **Nao** teste peso em fronteira de float
(`1.005`): o teste que falhou falhou por causa do valor escolhido, nao do codigo.

**Risco latente, NAO corrigido (30/09/2026):** o lado oposto do mesmo ciclo. Se
`InventoryItem` for tocada antes de `SheetData`, o `<clinit>` de `Inventory` roda enquanto
`InventoryItem.STREAM_CODEC` ainda e `null` -> `ExceptionInInitializerError`. O suite do
Gradle nao faz isso hoje. Consertar exige mexer na ordem das estaticas ou tornar
`InventoryItem.EMPTY`/`Inventory.STREAM_CODEC` preguiçosos, o que muda a semantica de
inicializacao de classe: e decisao de desenho, deixada em aberto.

**FATO verificado:** a UI da fase 2B (`addInventoryColumn` + `InventoryItemScreen`) nao tem
teste nenhum. Compilar e o unico gate ate a primeira vez que o jogador abrir a tela.

### Armadilha de `FormattedCharSequence` (bug real, achado pelo usuario em 30/09/2026)

**FATO verificado:** em 1.21.11 `FormattedCharSequence` **nao estende `CharSequence`** e
**nao tem `length()`**. Duas consequencias, ambas vividas na pratica:

1. `part + TRUNCATION_MARK` **compila** — o `+` do Java aceita um lado `String`
   (`TRUNCATION_MARK` e String) e converte o outro com `String.valueOf(part)`. O que
   aparece na tela e `net.minecraft.util.FormattedCharSequence$Lambda/0x...@...`, e o
   texto some. **Nunca junte texto vindo de `font.split` com `+`:** o resultado e
   `FormattedCharSequence`, nao `String`. Para achatar, percorrer
   `accept((index, style, codePoint) -> { ...; return true; })` devolvendo `true` para
   continuar. `StringBuilder(sequence.length())` tambem nao compila.
2. `StringBuilder(sequence.length())` quebra a compilacao — use `new StringBuilder()`.

**FATO verificado:** `renderBackground` pinta o fundo **antes** de `render`, e o texto das
`textLines` e desenhado **dentro** de `super.render()`. Logo um fundo desenhado no topo do
`render` da subclasse fica **sob** o texto e **sobre** o painel, sem mexer na classe base —
foi assim que a caixinha do item entrou sem tocar `CharacterSheetScreen`.

**Pedido do usuario (30/09/2026):** item de inventario desenhado **dentro de uma caixinha
um pouco mais escura**, e nao solto na lista. Virou `COL_ITEM_BG` (0xF2101016, contra
0xF216161C do painel) + `INV_PAD` (3) de folga interna.

**Armadilhas de layout achadas no mesmo dia (valem para qualquer tela com lista):**

- **Rotulo com largura fixa invade o widget.** `LABEL_W = 52` era fixo e
  "Description" e a palavra mais larga do formulario: com `x = caixa - LABEL_W`, o
  rotulo transbordava para dentro da caixa. **Meça o maior rotulo**
  (`font.width("Description") + 6`) e **alinhe pela direita** (`direita - font.width(texto)`),
  assim uma palavra larga cresce para a esquerda, nunca para dentro do campo.
- **Rotulo tem que sair do `getY()` da caixa que ele nomeia, nao de uma Y de
  formulario.** A caixa de descricao cresce ate o rodape, entao ela **nao** esta na
  4a linha do formulario: o rotulo ficava uma linha acima da caixa que nomeava.
- **Quem mede a quebra de linha e quem conta as linhas tem que usar a MESMA largura.**
  Aqui o texto recuado dentro da caixa (`rightTextW(w)`) e a contagem de linhas
  (`rightDescLines`) medem com larguras diferentes; se divergirem, `parts.get(i)`
  estoura a lista. Por isso `rightTextW` e um metodo, e nao uma constante repetida.

**Barra de rolagem (30/09/2026):** o projeto **ja tinha** o padrao, em `SkillsScreen.java`
(`renderListScrollbar`, `onListScrollbar`, `setSkillScrollFromMouse`, campo
`draggingScrollbar`): trilho `0xFF303038`, polegar `0xFFE8E8EE`, polegar
proporcional ao conteudo com minimo de 8 px, e trilho do tamanho da area **visivel**
(nao do conteudo). **Reutilize esse idiomado em vez de inventar outro.**

- **API de mouse do 1.21.11 usa `MouseButtonEvent`**, nao os `double` antigos:
  `mouseClicked(MouseButtonEvent, boolean)`, `mouseDragged(MouseButtonEvent, double, double)`
  e `mouseReleased(MouseButtonEvent)`.
- **A largura da barra e limitada pela folga interna da lista.** Com `INV_PAD_X` = 4 e o
  texto do peso encostado na direita, uma barra de 6 px (a do SkillsScreen) passaria por
  cima dele: a barra da lista de itens ficou com **4 px**, na propria folga.

**Recorte de lista (30/09/2026):** o filtro "o item inteiro cabe na faixa" sumia com o item
inteiro e a lista dava impressao de buraco. Agora o filtro e "**tem algum pedaco visivel**",
e quem recorta e o proprio item:

- **Fundo:** a caixa e a **intersecao** do bloco do item com a faixa visivel, entao o fundo
  nunca pinta em cima do cabecalho nem do rodape.
- **Texto e botao:** cada linha e cada botao passam por `lineInList(y, h)` e so existem
  se couberem **inteiros**. Botao cortado **nao** pode existir: seria clicavel sem o jogador
  ve-lo — que e exatamente o bug que o corte "linha inteira" da coluna esquerda ja tinha
 bishado antes (texto por cima do botao `Back`).

**Licao:** o filtro de visibilidade de uma lista com widgets tem **duas** camadas — o que e
desenhado (pode ser recortado pela faixa) e o que e clicavel (nao pode ser recortado). Tratar
os dois com a mesma regra ou esconde conteudo ou cria clique invisivel.

**FATO verificado (processo, 30/09/2026) — edicao em lote no mesmo arquivo mente.** Varias
`edit` no mesmo arquivo numa unica mensagem devolveram "Edit applied successfully" **sem ter
aplicado um dos trechos**, e outra reportou "nao encontrado" **tendo aplicado**. O guarda de
`textLines` que impedia o texto de sair da lista simplesmente nao entrou, e o build passou
verde: o bug so apareceu em jogo (texto por cima do botao `+ Item`). **Regra:** edicao em
lote so no mesmo arquivo e com trechos bem distintos; **sempre** confirmar com `grep` do
`if (guarda)`/simbolo depois, e aplicar **uma de cada vez** quando o arquivo for critico.
Nao confie no "applied" nem no "error" da ferramenta para garantir o estado do arquivo.

**FATO verificado (Iris, 30/09/2026) - o Iris NAO lanca excecao por pipeline desconhecido.** A
primeira analise registrou o contrario, e a causa errada foi parar no catalogo e no relatorio.
O metodo e `MixinShaderManager_Overrides.redirectIrisProgram` e, por `javap`, faz: se
`override(...)` devolver um shader, usa ele; senao, **apenas loga** — `fatal` quando o namespace do
pipeline e `minecraft`, `error` caso contrario (nosso e `tabletop-rpg`), e o guarda
`missingShaders.add(pipeline)` faz o log sair **uma vez por pipeline**, nao a cada frame. O
`new Throwable()` e so para a stack trace. Depois disso o metodo **retorna e o vanilla segue
normal**, ou seja o bind do pipeline acontece e o desenho roda.

**Licao (a mais importante desta sessao): causa raiz precisa de `javap`, nao de plausibilidade.**
Achamos a frase `"in override list"` dentro do jar do Iris, o log trazia um `Throwable`, e a
conclusao "o Iris lanca excecao" parecia tao segura que foi para o catalogo, para o relatorio e
para a memoria. Era falsa. **Regra:** antes de escrever "X faz Y" em documento vivo, abrir o
bytecode e ler o metodo. Um `Throwable` no log **nao** prova que algo foi lancado — e um
`Mixin` no stack trace **nao** prova que houve excecao. E quando uma causa raiz for corrigida,
corrogir em **todos** os lugares onde ela foi escrita, marcando a versao errada como errada em vez de
apaga-la, para ninguem reusar.

**FATO (1.21.11) - nao existe pipeline de LINHAS sem teste de profundidade.** Varri os 15 pipelines
de `RenderPipelines` que usam `DepthTestFunction.NO_DEPTH_TEST`: **todos** sao QUADS texturizados
vindos de `OUTLINE_SNIPPET` (o contorno de selecao, shader `core/rendertype_outline`, vertex format
`POSITION_TEX_COLOR`). Os de linhas (`LINES`, `LINES_TRANSLUCENT`, `SECONDARY_BLOCK_OUTLINE`) usam
`LINES_SNIPPET` (shader `core/rendertype_lines`, `POSITION_COLOR_NORMAL_LINE_WIDTH`) e **nao** tocam
em `withDepthTestFunction`. Conclusao: nao ha rota vanilla para "linhas + atravessa parede"; um
pipeline proprio continua obrigatorio.

**FATO (superficie de integracao do Iris) - existe API publica para mods:**
`IrisApi.assignPipeline(RenderPipeline, IrisProgram)` em `net.irisshaders.iris.api.v0` (e
`IrisApi.getInstance()`), alem da interna `IrisPipelines.copyPipeline(to, from)`, que copia a
associacao pipeline->shader de um pipeline para outro. `IrisPipelines.getPipeline` consulta um mapa
por identidade do pipeline com `getOrDefault(pipeline, FAKE_FUNCTION)`, e por isso devolve `null`
para qualquer pipeline que o mod criou. **RISCO:** esse mapa e reconstruido a cada carga de
shaderpack, entao registrar uma vez pode nao sobreviver a uma troca de pack em jogo.

**FATO (ambiente, ferramenta) - dois-adjustedo que custaram tempo.** (a) `javap` **nao esta no
PATH** nesta maquina; o executavel e `C:\Program Files\Java\jdk-25\bin\javap.exe`.
(b) PowerShell 5.1 **nao tem** `[System.Text.Encoding]::Latin1` (e' .NET Core) — usar
`[System.Text.Encoding]::GetEncoding(28591)`. (c) O `>` do PowerShell grava **UTF-16**: sempre
`Out-File -Encoding utf8`. (d) `Select-String` e case-insensitive, entao um "texto velho
sobrou: 1" pode ser o proprio trecho que marca o texto velho como errado.

**FATO CONFIRMADO EM JOGO (30/09/2026) - a integracao com o Iris FUNCIONA.** A aura do Mestre
atravessa parede **com e sem** shaders. Validado pelo usuario com o Complementary Unbound r5.5.1.

**O QUE ERA (causa raiz real, e nao a que a analise anterior affirmou):** a aura usa um
`RenderPipeline` proprio com `NO_DEPTH_TEST`. Sem shader esse pipeline e valido e funciona. Com
o Iris ligado, o Iris **assume a ligacao do shader** e um pipeline que ele nao conhece simplesmente
**nao e desenhado** — sem erro de GL, sem excecao, batch fechado normalmente. O log
`Missing program ... in override list` era o SINTOMA disso, nunca a causa.

**A CORRECAO (`IrisAuraSupport`):** antes do desenho, se `isModLoaded("iris")`, chamar
`IrisPipelines.copyPipeline(LINES_TRANSLUCENT.pipeline(), pipelineThroughWalls())`. A **origem tem
de ser `LINES_TRANSLUCENT`** porque e a base exata do pipeline da aura (mesmo vertex format, mesmo
blend, mesma linha de shader); o Iris atribui o shader de linhas sem divergir do que o pipeline
espera. O atravessa-parede sobrevive porque **profundidade vive no pipeline e shader e o
programa** — trocar o shader nao mexe no `NO_DEPTH_TEST`.

**LICAO — O BISECT DE UM FRAME COMO METODO.** A causa raiz anterior estava errada eso nao
descobri lendo codigo: descobri **desenhando a mesma geometria duas vezes no mesmo frame**, com o
pipeline do mod em uma cor e `RenderTypes.linesTranslucent()` do jogo em outra. Resultado: **so a
do jogo apareceu**. Isso matou de uma vez alvo de render, timing do evento e geometria/camara
(todas compartilhariam frame, pose e coordenadas) e deixou um so suspeito. **Regra:** quando o
defeito e "algo nao aparece" e voce tem duas implementacoes concorrentes, **desenhe as duas no
mesmo frame, com cores diferentes**. Custa um build e vale mais que qualquer leitura de codigo.
Leia o resultado como tabela de decisao antes dedeeduzir causa.

**LICAO — CAUSA RAIZ EM DOCUMENTO VIVO PRECISA DE EVIDENCIA, E A ERRADA FICA MARCADA.**
Neste projeto a causa errada ("o Iris lanca excecao") foi escrita no catalogo, no relatorio e na
memoria, e So foi corrigida quando o bisect trouxe evidencia. Vale para qualquer causa raiz: antes
de escrever, tenha a evidencia; ao corrigir, **marque a versao errada como errada em vez de
apaga-la** — foi o que impediu que ela voltasse a ser usada como "causa" numa sessao futura.

**LICAO — MOD OPCIONAL = REFLEXAO, NUNCA DEPENDENCIA DE COMPILACAO.** O Iris entrou por
`Class.forName` + `getMethod`, dentro de `if (FabricLoader.isModLoaded("iris"))`. Transformar um
mod opcional em dependencia de compilacao faria o nosso mod nao carregar em quem nao o tem — o
build local passaria (o jar esta no cache) e so o usuario descobriria. `build.gradle` continua sem
nenhuma referencia ao Iris: isso e proposito e verificado no build.

**APIs do Iris 1.10.7 (mc1.21.11), nomes confirmados por `javap`:** `IrisPipelines.copyPipeline(de,
para)` — copia a atribuicao de shader de um pipeline para outro; so copia se a **origem** ja
estiver no mapa, entao um shaderpack que nao mapeie `LINES_TRANSLUCENT` faz a copia virar no-op
silencioso. `IrisApi.assignPipeline(RenderPipeline, IrisProgram)` e a API publica para mods. O mapa
e **reconstruido a cada carga de shaderpack** — por isso o registro e reaplicado no caminho de
desenho, todo frame em que a aura desenha (idempotente e barato), e nao uma vez na carga.

**FATO (1.21.11, ja registrado acima, confirmado):** nao existe pipeline de LINHAS do jogo sem
teste de profundidade — os 15 pipelines com `NO_DEPTH_TEST` sao todos QUADS texturizados de
`OUTLINE_SNIPPET`. Logo o pipeline proprio da aura continua obrigatorio.

**CORRECAO IMPORTANTE — o registro de "Iris lanca excecao" que ficou mais acima NUNCA foi verdade.**
O que este arquivo afirma em um trecho anterior e **errado** e nao deve ser usado como causa raiz.
Se voce leu aqui que o Iris "lanca excecao para qualquer programa fora da lista de override", ou
que a aura "nao e compativel com o Iris", ou que o `Missing program` loga "a cada frame", ignore.

O que e verdade, com evidencia de jogo (30/09/2026):

1. O Iris **nao lanca excecao**. `MixinShaderManager_Overrides.redirectIrisProgram` nao tem
   `athrow`; o `new Throwable()` serve so para a stack trace do log.
2. O aviso sai **uma vez por pipeline**, nao a cada frame (guarda `missingShaders.add`).
3. O nivel depende do namespace: `fatal` + "This is likely an Iris bug!!!" para `minecraft`;
   `error` + "This is not a critical problem..." para namespace de mod, como o nosso.
4. **A causa real** da aura sumir com shader ligado: o Iris **nao tinha shader atribuido** ao
   pipeline do mod, e por isso nao desenhava as linhas. O aviso era sintoma, nao causa.
5. **Resolvido** com `IrisAuraSupport.ensurePipelineRegistered()` (ver a secao "FATO CONFIRMADO
   EM JOGO" adiante no arquivo). A aura atravessa parede com e sem shaders.

Este paragrafo existe para que a frase errada, que ja foi copiada para o catalogo e para o
relatorio, **nao volte a ser tratada como verdade** numa sessao futura.

## Clique em entidade: o alvo nao e o que foi clicado (01/10/2026)

**FATO (`javap` no `minecraft-common` 1.21.11):** `EnderDragon extends Mob`, mas
`EnderDragonPart extends Entity`. O cliente manda o clique na **parte**, nao no corpo. Um teste
`entity instanceof Mob` feito sobre a entidade **crua** do clique falha para o dragao, e o handler
devolve `PASS` **em silencio**.

- Foi essa a causa do "o Mestre nao consegue selecionar nem mover o dragao do Fim". Nao era
  hitbox, nao era distancia, nao era permissao.
- **FATO:** 1.21.11 **nao tem** API generica de partes. `net.minecraft.world.entity.EntityPart`
  nao existe e `Entity` nao tem `getParts()`. O unico desembrulho e por tipo:
  `EnderDragonPart.parentMob` (public final).
- **REGRA:** desembrulhar a entidade clicada **antes** de testar o tipo. Ver
  `EntityTargets.resolve`. Mob de outro mod montado em partes precisa do caso dele ali.
- **REGRA:** caminho de clique recusado **merece log**. Um `PASS` silencioso esconde o defeito por
  semanas. Ver `Clique do Mestre sem alvo valido` no `CombatController`.

## O vanilla re-dispara o uso com o botao SEGURADO (~200 ms) (01/10/2026)

Descoberto por revisao propria **antes** de entregar; o build nao acusa.

- **FATO de codigo:** com o botao direito pressionado, o cliente re-dispara o uso do item a cada
  ~200 ms (o mesmo fenomeno que o `CombatController` ja documentava para bloco).
- **O defeito que isso cria:** um handler que consome estado de uso **unico** (ex.: "pedido armado
  pelo comando") e o **limpa** no primeiro pacote deixa o re-disparo cair no caminho **seguinte**.
  No caso da camera armada, o resultado era aplicar a camera **e** selecionar o mob para mover no
  mesmo gesto.
- **REGRA:** handler de clique com estado de uso unico precisa de **janela de debounce** *e* de
  **renovacao do carimbo a cada re-disparo**. Sem a renovacao, o carimbo envelhece com o botao ainda
  apertado, a janela cai no meio do gesto e o caminho seguinte assume o clique.
- **REGRA:** usar a **mesma** janela dos caminhos vizinhos que disputam o mesmo gesto
  (`RECENT_MS = 400`, igual a selecao de monstros), para os dois concordarem sobre "o mesmo clique".

## Sprite temporario: copiar para dentro do mod, nao referenciar (01/10/2026)

Decisao do usuario para o `Camera Tool`, e vale como regra geral para sprite de rascunho.

- **FATO:** a textura e copia da luneta do jogo (`minecraft:textures/item/spyglass.png`, 16x16),
  extraida do jar do cliente para `assets/tabletop-rpg/textures/item/camera_tool.png`.
- **Motivo:** o sprite e **temporario** e vai ser editado depois. Com a copia, trocar o visual e
  sobrescrever o PNG. Referenciando `minecraft:item/spyglass`, o item ficaria preso a textura do
  jogo -- e uma mudanca de textura do vanilla mudaria o item sem ninguem pedir.
- **Assinatura de PNG valido:** `89 50 4E 47 0D 0A 1A 0A`; largura/altura nos bytes 16..23
  (big-endian). Conferir depois de extrair do jar.
**FATO verificado (30/09/2026, fim da sessao):** as tres fases da ficha (abas, campos de
texto grandes, inventario) e todas as correcoes de layout foram **validadas em jogo** pelo
usuario e commitadas em **`a16b3b7`** (12 arquivos, +3204/-47, branch `main`, sem tag e sem
push). `origin/main` continua em `5c39f00`. O ultimo **checkpoint** segue sendo `7ace0fd` /
`checkpoint-20260930-1412-antes-das-abas-da-ficha`, que e anterior a tudo isso.

### Trabalho em paralelo de outra pessoa no mesmo repositorio (30/09/2026)

**FATO verificado:** o repositorio nao e shallow, e o remoto e
`https://github.com/Pedr0U/tabletop-rpg-1.21.11.git`. Alem da `main`, existe a branch
**`pasta-Net`** (`450d64c` "Correcao de Bugs e Adicoes") — e onde o outro desenvolvedor
trabalha. **REGRA (01/10/2026): este paragrafo esta ERRADO quanto a consequence.**

**FATO corrigido em 01/10/2026:** o outro desenvolvedor **tambem** da push direto na `main`
(`7868f1e`, pagina 3 da ficha + campo CA, 4 commits). A `pasta-Net` existe, mas nao e o
unico caminho dele. **REGRA:** `git fetch` + comparar `HEAD..origin/main` **sempre** antes
de comecar trabalho longo, e nao assumir que `pull` na `main` nao traz nada dele. Commitou-se
local, depois `git merge origin/main`: assim o historico da Camera Tool nao e reescrito
quando ha merge conflict, e a resolucao fica num commit so.

Conflitos sao mais provaveis em `FUNCIONALIDADES-E-COMANDOS.md`,
`agent/memory/project-memory.md` e `assets/tabletop-rpg/lang/en_us.json` (chaves diferentes
no mesmo arquivo). JSON de lang mescla bem sozinho quando as chaves sao distintas, mas
**sempre validar com `ConvertFrom-Json` e rodar `gradlew build`**, porque o build tambem roda
`scanEncoding`.

**FATO verificado:** nesta maquina `pull.rebase = false`, entao `git pull` faz **merge**
(gera commit de merge), nao rebase. Consequencia pratica: se houver conflito, o pull
**para** e deixa a arvore em estado de conflito para resolver — nada se perde, mas precisa
de atencao.

**Risco especifico deste projeto, e o que vale avisar:** os dois mexem em
`SheetData.java`. Conflictos ali sao praticamente Certainos (o diff da fase 2B sozinho tem
+634 linhas), e o perigo **silencioso** e a **ordem dos campos no codec de NBT**: se uma
resolucao manual deixar o `group(...)`/`forGetter` do `CODEC` em ordem diferente da do
construtor do record, os testes de round-trip **continuam passando** (encoder e decoder
concordam entre si) mas uma ficha salva pela outra versao e lida com campos trocados.
Depois de qualquer pull que toque `SheetData`, conferir a ordem e testar com uma ficha
salva **antes** do merge.

**Procedimento seguro para o usuario:** `git fetch origin` -> `git log --oneline HEAD..origin/main`
para ver o que veio -> `git pull` **so com a arvore limpa** (commit ou stash antes) ->
`.\gradlew.bat build --no-daemon --console=plain` antes de testar em jogo.

**FATO verificado (01/10/2026) - conflito de merge orfao foi resolvido antes de commitar.**
O `agent/memory/project-memory.md` estava com marcadores `<<<<<<< Updated upstream` /
`>>>>>>> Stashed changes` e status `UU`, **sem** `MERGE_HEAD` e **sem** entrada em
`git stash list`: sobra de um `git stash pop` anterior que conflitou e foi abandonado.
Resolvido **mantendo os dois lados** (os dois blocos eram blocos de memoria distintos e
append-only, um do Iris e outro do fim da sessao das abas), na ordem upstream -> stashed.
Nenhum conteudo foi descartado.

---

## 2026-10-01 - Pagina 3 (Skills/Magias): widget nao e recortado pela faixa da lista

**FATO verificado (causa raiz do estouro de borda, por leitura de codigo em
`StatusScreen.java`, confirmado pelo relato do usuario em 01/10/2026):** `ItemBox`
e um retangulo desenhado pela propria tela, entao o `addSkillEntry`/`addSpellEntry`
recorta o fundo na borda com `Math.max`/`Math.min`. Mas um **widget** (`Button`,
`EditBox`) e desenhado pelo vanilla na posicao Y que recebeu, sem nenhuma ideia da
faixa da lista. Resultado: o fundo era recortado e o botao nao, entao o jogador via o
botao vazando por cima do `+ Skill`/`+ Magia` ou por cima da barra de abas.

**FATO verificado:** a coluna de inventario ja resolvia isso com um guarda
`lineInList`/equivalente; a coluna nova nao replicou o guarda. Agora existe
`Column.lineFits(int y, int h)` em `StatusScreen`, usado pelo botao de nome de skill,
pelo botao de nome de magia e por `addDeleteButton`. Texto continua guardado pela
mesma regra. Correcao de geometria (`Column.prepare` antes do laco) sozinha **nao**
resolve: ela arruma a faixa, nao o desenho do widget.

**FATO verificado (armadilha de API do projeto):** em `addEntryNameButton` o
parametro `right` controla DUAS coisas: `right == null` desliga o texto do circulo
**e** o botao `Del` (`delW = right == null ? 0 : LIST_BTN_W`). Passar `null` para
tirar o circulo apaga o `Del` junto. Para skill, que nao tem circulo, o valor correto
e `""` (texto vazio, `Del` presente).

**FATO verificado (01/10/2026):** `TextLine` cresce para DIREITA a partir do X que
recebeu. No cabecalho de conjuracao o `Modifier` nasce em `cdX` (fim do botao de
atributo), entao a largura util e `(panelX + panelW) - x`, nao `panelW`. Usar a
largura total do painel fazia o `"Modifier: -"` transbordar para fora do painel.

**Decisao do usuario (01/10/2026):** CD foi para a ESQUERDA na linha de baixo do
cabecalho (rotulo `CD` + caixa encostados a esquerda); antes encostava a direita e
invadia a area do `Modifier`. Cartao de magia na nova disposicao: nome largo e mais
alto, `1º círculo` ate `5º círculo` (nome por extenso, minusculo, na linha), execucao
abaixo, custo abaixo da execucao, e `Del` na direita da ultima linha com texto. O
`Del` desceu porque na linha do circulo o usuario lia como se fosse botao do circulo.

**HIPOTESE:** o `Del` sempre na ultima linha com texto (desce conforme execucao e custo
aparecem) e o que resolve a leitura ambigua; se o usuario preferir Del sempre na mesma
altura, e uma linha de altura fixa.

**Validacao:** `gradlew build test --no-daemon --console=plain` -> `BUILD SUCCESSFUL`.
**NAO validado:** o recorte visual em jogo -- build nao prova o desenho do widget na tela.
Precisa do teste visual do usuario com rolagem nas duas colunas.
### 2026-10-01 (tarde) - Pecao visivel na rolagem + validacao de nome obrigatorio

**FATO verificado (por que o widget e recortado e nao pulado):** o primeiro corte
usava so um guarda "cabe inteiro?". Isso matava o estouro de borda, mas o jogador
passou a ver a linha **sumir de uma vez** ao rolar. A solucao nao e voltar a criar
o widget inteiro: e criar o widget **com a altura que sobra na faixa**
(Column.clippedHeight, minimo LIST_PEEK_MIN = 5) e empurrar o Y para
listTop. O vanilla entao desenha um botao de 5 px, que e o "pedaco" pedido.
Texto continua com o guarda "cabe inteiro?" (lineFits), porque TextLine e
desenhado por nos e nao precisa de recorte.

**FATO verificado:** o ItemBox (fundo do card) e desenhado por esta tela e
encolhe ate 1 px -- e ele que faz a transicao parecer continua. O botao some aos
5 px. Os dois juntos dao o efeito pedido.

**Decisao do usuario (01/10/2026):** nome **obrigatorio** em Skill e Magia. Sem
nome, save() marca showNameError, foca a caixa e **nao** envia pacote; o
aviso "Name required" aparece em 0xFF5555 sob a caixa de nome. Validado so no
cliente: e conforto, nao seguranca -- o servidor ja normaliza e limita o texto.

**FATO verificado (cabecalho):** o rotulo "CD" e medido por font.width("CD") + 2,
nao pela largura da caixa (que e o dobro) -- era o vao enorme entre o rotulo e a
caixa. O botao de atributo agora e w - font.width("Modifier: -") - 2*PER_GAP, e
o Modifier nasce em modX = x + attrW + PER_GAP, entao ele tem largura reservada
e nao depende do resto da linha.

**Validacao:** build + test -> BUILD SUCCESSFUL (18s).
**NAO validado em jogo:** o efeito do pedaco visivel na rolagem, a proximidade da CD e o
aviso de nome vazio -- build nao prova desenho nem interacao de widget.

### 2026-10-01 (noite) - Botao Save desativado + armadilha de gravacao da memoria

**Decisao do usuario (01/10/2026):** o aviso de "nome obrigatorio" vira botao
**desabilitado**. saveButton.active = !nameBox.getValue().isBlank(), com

ameBox.setResponder(text -> syncSaveEnabled()) para reavaliar a cada tecla, sem
rebuild. O aviso "Name required" continua, mas agora nasce do mesmo estado do
botao (
ameBox.getValue().isBlank()) e nao de um clique -- que deixou de acontecer.
Os dois nunca discordam porque leem a mesma expressao. A guarda em save() foi
mantida como rede de seguranca: Save nao deve depender da UI para ser correto.

**FATO verificado (armadilha REAL deste projeto):** gravar a memoria com
[System.IO.File]::AppendAllText e seguro, mas o edit/write com texto em que
`b` e `t` vem no inicio de palavra **trunca as letras**: "build" vira "uild" com um
0x08 (backspace) no lugar do `b`, e "test" vira "est" com 0x09 (tab). Esses
bytes de controle nao aparecem no 
ead como caracteres visiveis, entao parece
apenas palavra faltando letra. **scanEncoding do build.gradle pega isso e faz o
`build` FALHAR** (`throw GradleException` em build.gradle:218), o que e bom: e a rede
que impediu lixo silencioso na memoria.

**Como diagnosticar e corrigir:** localizar com -match "\uFFFD" e inspecionar bytes
com [System.Text.Encoding]::UTF8.GetBytes(). Quando o edit falha em casar
por causa desses bytes, reescrever a linha **por indice**
($lines[i] = ... + WriteAllLines) e o caminho que funciona. Ao validar este
arquivo, procure tambem [\u0008\u0009], nao so \uFFFD: o scanEncoding
acusa os tres, mas um olho humano so ve o U+FFFD.

**FATO verificado:** Button.active = false e o jeito nativo de desabilitar sem
remover o widget: ele continua desenhado (cinza, quando o Widget respeita o campo
`active`) e o `onPress` deixa de rodar. Removê-lo do `children()` exigiria

**Validacao:** `build` + `test` -> BUILD SUCCESSFUL (11s), `scanEncoding OK: 120
arquivo(s). **NAO validado em jogo:** o botao cinza, o aviso e a reativacao ao
digitar.

---

## 2026-10-01 — Campo CA na ficha (FATO verificado)

### `RecordCodecBuilder.group()` tem LIMITE DURO de 16 campos (FATO verificado)
`SheetModel` tinha **exatamente** 16 componentes. Adicionar o 17o (`caLabel`) fez o compilador recusar com
`no suitable method found for group(...)`. Nao ha como contornar mantendo o builder: **16 e o maximo**, nao um
acidente da sobrecarga escolhida.

**Como resolver (decisao do usuario, 01/10/2026): "codec manual, chaves iguais".** O `SheetModel.CODEC`
deixou de ser `RecordCodecBuilder` e passou a ser `Codec.of(Encoder, Decoder)` **escrito a mao**, em
`CODEC_MANUAL`. O formato gravado **nao muda**: as chaves continuam planas e iguais
(`nameLabel`, `raceLabel`, ..., `caLabel`) dentro de `model`. Nao e migracao.

### APIs do DFU 9.0.19 verificadas com `javap` (FATO verificado)
Preferi confirmar no jar a adivinhar. `javap.exe` esta em `C:\Program Files\Java\jdk-21.0.12\bin\` (**nao**
esta no PATH deste ambiente) e o jar em
`~/.gradle/caches/modules-2/files-2.1/com.mojang/datafixerupper/9.0.19/*/datafixerupper-9.0.19.jar`.

- `Codec.parse(Object)` **NAO EXISTE**. `parse` so existe em `Decoder.parse(Dynamic<T>)` e
  `Decoder.parse(DynamicOps<T>, T)`. Por isso nao da para decodificar um elemento cru de `Map<String,Object>`.
- `MapCodec.of(MapEncoder, MapDecoder)`: **nao da para usar com lambda** — `MapEncoder` tem 2 metodos
  abstratos (`encode` e `compressor`) e `MapDecoder` tambem (`decode` e `compressor`). Lambdas nao compilam.
- `Encoder` e `Decoder` tem **1 metodo abstrato cada** → `Codec.of(Encoder, Decoder)` **aceita classe
  anonima** (nao lambda: a assinatura e `<T>` e **lambda nao pode declarar parametro de tipo**, da
  `cannot find symbol: class T`).
- `RecordBuilder.add(String, E, Encoder<E>)`: a ordem e **(chave, VALOR, ENCODER)**. Com a ordem invertida
  da `cannot infer type-variable(s) E`.
- `MapLike.get(String)` devolve **`T`, nao `DataResult<T>`** → nao existe `flatMap` nisso (da
  `flatMap ... location: class Object`).

### Risco proprio do codec manual e o teste que o cobre (FATO verificado)
Diferente do builder, **nao ha verificacao em tempo de compilacao** que amarre chave a campo. `"calabel"`
vs `"caLabel"` compila, grava e volta como padrao, **em silencio**. Por isso
`SheetModelCodecTest.java: caLabelSurvivesNbtAndNetworkAndLegacyNbtFallsBack` grava um rotulo **NAO padrao**
("ARMADURA"): com o padrao "CA" o teste passaria mesmo com a chave errada, porque o padrao e a saida do
caminho certo E do errado — ele nao distingue as duas falhas. Cobre NBT e `STREAM_CODEC`, que sao dois
codigos manuais separados com ordens proprias.

### `Progress` e a armadilha do construtor de conveniência (FATO verificado, BUG CORRIGIDO)
Ao adicionar `ca` ao record `Progress`, criei um construtor **sobrecarregado de 3 campos** que delega com
`ca = 0`. Isso resolveu as 7 chamadas espalhadas, mas **criou bug**: os ramos de `withField` de `level`,
`xp` e `xptext` reconstroem o `Progress` inteiro para trocar **um** campo, e usavam esse construtor — entao
**mexer no XP apagava o CA e vice-versa**. Como os dois campos estao na **mesma linha** da ficha, era a uma
tecla de distancia. Corrigido repassando `progress.ca()` explicito em cada ramo.
**Licao: construtor de conveniencia que tem valor default esconde a passagem de um campo novo.** O
mesmo padrao foi usado no `SheetModel` (construtor de 16 sem `caLabel`) e ali **nao** ha o risco, porque
todas as copias internas realmente devem manter o rotulo padrao.

### CA: onde mora e por que (FATO verificado)
Valor em `SheetData.java: Progress` (campo `ca`), **nao** nos atributos: CA nao entra em rolagem e nao tem
piso/teto do Mestre. **Piso 0, teto 9999** (`SheetData.MAX_RESOURCE`, o mesmo do HP e da Mana) — CA
negativo nao tem leitura, modificador negativo vive no atributo (que vai a -30).
Rotulo em `SheetModel.java: caLabel` (padrao "CA", configuravel no Sheet Editor via `field_ca`, **sem
toggle**). NBT: `Codec.INT.optionalFieldOf("ca", 0)` — ficha antiga abre com CA 0, igual a recem-criada.
`LABELLED_FIELDS` ganhou `"ca"` (o `fieldLabelWidth` do StatusScreen mede por essa lista, entao o rotulo do
CA entra na conta da largura reservada).

### `addFieldPair` (FATO verificado)
`CharacterSheetScreen.java: addFieldPair(first, second, x0, y, leftW, labelW, boxW)` com `PAIR_GAP = 8`.
Cada metade reserva a **mesma** largura de rotulo das linhas de campo unico, de proposito: encolher faria um
rotulo configurado ("Armadura") quebrar e invadir a caixa vizinha (o bug de 27/09/2026). Os rotulos quebram,
as caixas nao se movem. A soma das metades fecha em `leftW`.

---

## Presets de rolagem (02/10/2026)

### `rollMessageFor` e o funil de TODA rolagem (FATO)
`MasterCommands.rollMessageFor` (`:1308`) e por onde passam `/rpg roll`, `/rpg openroll`,
`/rpg preset use` e o clique no item. Qualquer coisa que mude o **resultado** de uma rolagem entra
la e **nao** em `presetUse`: em `presetUse`, `/rpg roll 1d6+Strength` e o clique no item dariam
numeros diferentes. A resolucao de atributo/pericia foi posta logo depois do `formula.isEmpty()`, antes
do `DiceFormula.parse`.

### Formula aceita nome de atributo e de pericia (FATO)
`FormulaResolver` (novo, `src/main/java/.../FormulaResolver.java`) troca `+Strength` pelo **valor
cru** da ficha de quem rola. Decisoes do usuario em 02/10/2026:
- **valor cru**, nao modificador D&D (14 soma 14). O codigo nunca calculou modificador.
- pericia e **token separado**: `+Athletics` soma o valor dela, `+Athletics+Strength` soma os dois.
- nomes aceitos: `id`, `label` e `name` do `SheetModel` (atributo e pericia), casados sem acento,
  sem diferenciar maiuscula e **sem espaco**. `+STR` == `+strength` == `+Strength`.
- **colisao: atributo vence pericia.** Sem regra fixa o mesmo preset resolveria valores diferentes
  conforme a ordem da tabela.
- sem ficha, ou sem o valor na ficha → **recusa**, nunca zero silencioso.

O scanner casa o **maior trecho primeiro** (`+Animal Handling` funciona) e filtra `d20`/`D20` por
`d` seguido de digito, antes de qualquer tentativa de nome.

### Teto de nomes tem que existir em `resolve`, nao so em `tokens` (LICAO)
O primeiro `MAX_TOKENS` foi posto so em `tokens()`. `resolve()` rodava sem teto: uma formula com mil
nomes passava e cada nome virava uma busca na ficha. **Achado por teste, nao por revisao.** Toda
fronteira de recurso precisa existir em **todos** os caminhos que recebem entrada do jogador, e nao
no que parece o principal.

### Validacao de nome no `create`, resolucao na rolagem (DECISAO)
`RollPreset.create` chama `FormulaResolver.tokens(formula, SheetModelHolder.current())` e recusa
nome desconhecido. O `sheet` e `null` de proposito: ali so se confere o **nome**; se o id existe no
modelo mas a ficha da jogadora nao tem o valor, isso e problema da ficha e a resolucao recusa na
rolagem. Sem isso o preset pareceria valido na tela e falharia so ao rolar.

### `RollPresetStore` e lista ordenada, e o NBT antigo migra (FATO)
Era `Map<String, RollPreset>` reordenado por nome a cada leitura. As setas da tela nao tinham onde
gravar e um preset editado mudava de lugar por causa do alfabeto. Agora:
- `list` na ordem gravada; `put` **mantem a posicao** ao editar (arrumar um erro de digitacao nao
  custa a posicao na tela); `move(from,to)`; `indexOf(name)`; `list` e **copia defensiva**.
- `CODEC = Codec.list(...).withAlternative(codecDoMapaAntigo)`. O `withAlternative` testa a
  alternativa so quando o **principal** falha, entao o principal tem de ser o formato **novo**, e a
  distincao tem de ser do tipo de tag (`ListTag` contra `CompoundTag`). A migracao ordena por nome,
  que e a ordem que a versao anterior exibia.
- `withAlternative` aqui tem **um** argumento: a assinatura de dois (`codec`, `String`) nao existe
  nesta versao do DFU.

### Tela: `Screen` nao remove widget individual (LICAO, BUG CORRIGIDO)
`rebuildListOnly()` era chamado a cada seta e a cada clique do `Del`, e **somava** widgets novos.
`Screen` nao tem como remover um widget especifico, entao 6 linhas viravam 12 botoes no mesmo lugar
com indices velhos presos nos antigos, e a seta movia o preset errado. `clearWidgets()` resolveria,
mas leva o texto digitado dos `EditBox` junto. Solucao: `List<Button> listWidgets` + `addListWidget`
que registra + `removeWidget` antes de recriar. **Achado revisando o codigo, nao rodando.**

Outras tres da mesma tela: `EditBox` **exige** `addRenderableWidget`; em 1.21.11 `mouseClicked`
recebe `MouseButtonEvent` e nao `(double,double,int)`; o `Del` guarda **indice** e nao nome, porque
o nome pode mudar entre o primeiro e o segundo clique e apagar o preset errado e pior do que nao
apagar.

### Resposta de operacao: na tela, e no chat so se a tela fechou (DECISAO)
A resposta do servidor aparecia na linha de status **e** no chat, mostrando a mesma frase duas
vezes. Agora: com `PresetsScreen` na frente, so na linha de status; sem tela (a jogadora fechou no
meio do caminho), so no chat, senao a recusa sumiria sem ela ver.

### Item apos renomear preset (DECISAO do usuario, 02/10/2026)
O item guarda o nome numa tag propria, entao editar o preset nao muda o item que ja esta na mochila.
Escolha: **reentregar o item atualizado e avisar que o antigo continua la**. O preco e um item
duplicado com o nome velho; o ganho e que o item novo nunca mostra nome errado. No servidor,
renomear **remove o preset viejo e poe o novo no lugar dele** — sem isso o renomeado ficaria
duplicado, com o nome novo em cima do velho. Aviso em `message.tabletoprpg.preset_renamed`.

### Validacao de formula com nome: o parser tem de ver o placeholder (LICAO, BUG CORRIGIDO 02/10/2026)
`RollPreset.create` rodava `DiceFormula.parse(formula)` **antes** da checagem de nome. O
`DiceFormula` so entende numeros e dados, entao `1d6+Strength` era recusado como sintaxe invalida e
o `FormulaResolver` **nunca era chamado** -- o recurso inteiro ficava inalcancavel pela tela e pelo
comando, e o build continuava verde. Sintoma do usuario: "nao consegui criar um preset com
1d6+Strength". **Nenhum teste cobria**: `RollPresetTest` so usava `1d20+5` e `banana`.
Correcao: os nomes sao conferidos primeiro e o parser recebe `FormulaResolver.placeholderFormula`,
que troca cada nome por `0`. O preset guarda a formula **com** o nome; o placeholder so existe para
o parser. E a mensagem do parser tem de trocar o placeholder de volta (`replace(forParsing, formula)`),
senao a jogadora le "unexpected '+' in '1d6+0+'" e procura um zero que nunca escreveu.
**Licao geral: validador em ordem errada passa o build e some no produto.** Toda validação que
depende de uma transformacao precisa de um teste que monte a entrada ja transformada.

### Layout de GUI: meca a parte variavel, nao some constantes (LICAO, BUG CORRIGIDO 02/10/2026)
O painel da `PresetsScreen` media **326px num painel de 240px**: 6 linhas de 22px mais o formulario
somados a mao. Tudo abaixo das amostras de cor (Save, Use, status, voltar) ficava fora da tela, e
**sem erro nem aviso** -- o Minecraft simplesmente nao desenhou. Duas causas somadas:
1. altura fixa para a lista, que e a unica parte que deveria encolher;
2. o orcamento descontava `2 * PAD` quando o `panelY` nunca e menor que `PAD` -- faltava um `PAD`.
Correcao: `chrome` (tudo menos a lista) tem altura fixa; a lista recebe o que sobrar, entre 1 e
`MAX_ROWS` linhas; o painel e centralizado nos dois eixos. Verificado por script nas dimensoes
reais (427x240, 480x270, 640x360, 960x540, 854x480) -- todas OK. Em 240x180 ainda estoura 10px,
abaixo do que o proprio Minecraft suporta.
**Regra: o que a GUI desenhar fora da tela nao gera erro nenhum.** Layout precisa de verificacao
**externa** (script que espelha as formulas), porque nao ha como testar `Screen` em JUnit.

### Centralize o rotulo com o campo, nunca o campo sozinho (LICAO, 02/10/2026)
Centralizar so a caixa de texto punha o rotulo "Formula" para fora do painel, porque o rotulo e
`boxX - 4 - font.width(rotulo)` e sobra menos espaco de um lado do que o rotulo ocupa. O bloco
`rotulo + campo` tem de ser centralizado como uma unidade, com a largura do rotulo reservada.

### Tela: log e a unica evidencia de que a tela abriu (LICAO, 02/10/2026)
Sem linha de log na abertura da tela, o `runClient.log` de uma sessao inteira **nao distingue**
"a tela abriu e estava quebrada" de "ninguem clicou no botao". Isso gastou uma sessao de teste.
Adicionado: log na abertura (com `guiScaledWidth/Height`), log do layout calculado dentro do
`layout()`, e log dos tres pacotes de preset com `ok`, mensagem e se a tela estava aberta.

### `scanEncoding` pega ideograma em qualquer `.md` do `agent/` (LICAO, 02/10/2026)
Dois ideogramas chineses (U+6EDA e U+52A8) num **relatorio** derrubaram o `build`. O detector varre
`agent/` tambem, e com razao: texto em portugues nao usa ideograma. **A falha e sempre do lado do
agente**, e o detector estava certo. Vale para a mensagem de commit tambem -- um par de ideogramas
(U+516C e U+5F0F, "formula") entrou num titulo e so foi visto depois de amendado.
**Armadilha desta propria licao:** citar o caractere proibido para documentar o defeito o repõe no
arquivo. Descrever por codepoint. Aconteceu duas vezes na mesma sessao.
Um byte NUL (U+0000, digitado no lugar de `0` num literal de cor `0xFF5555`) tambem estava no
`project-memory.md` desde uma sessao anterior e **fazia o arquivo ser lido como binario** por
qualquer ferramenta. Repara byte a byte, nunca conversao global.
.ToCharArray() | Where-Object { [int]\ -gt 127 })
`
Reparar por palavra, nunca conversao global (ver a licao do byte NUL, acima).
### Sentido do scroll: scrollY e POSITIVO ao rolar para CIMA (LICAO, 02/10/2026)
Conferido no bytecode do MouseHandler.onScroll (1.21.11), que repassa o offset vertical do
GLFW direto para Screen.mouseScrolled. Logo a forma CORRETA de mover um offset de lista e
offset + (int) -Math.signum(scrollY): scrollY negativo (roda para baixo) somado com +1.
O PresetsScreen era o **unico** do projeto com offset - (int) -Math.signum(scrollY), que e
exatamente o oposto; StatusScreen, AttributePickerScreen e SheetEditorScreen ja estavam
corretos. **Como provar sem depender de memoria:** comparar com outra lista do proprio projeto e
ler o bytecode. Ver a armadilha da repeticao adiante.

### Widget invisivel AINDA RECEBE CLIQUE (FATO verificado em bytecode, 02/10/2026)
AbstractWidget.mouseClicked testa isActive() e isMouseOver(), e **nao** testa isVisible().
Entao um botao so no hover precisa de visible = false **e** active = false juntos: so o
visible deixa um botao invisivel reordenando a lista num clique cego.
Conferir com: javap -p -c -cp <minecraft-clientonly.jar> net.minecraft.client.gui.components.AbstractWidget.

### Texto que NAO pode ser cortado e ainda precisa caber no painel (LICAO, 02/10/2026)
Quando a tela esta aberta, o aviso do servidor vai **so** para a linha de status e **nao** tambem
para o chat (o receptor escolhe tela OU chat). Entao cortar o texto esconderia a recusa. A saida
e quebrar em ate N linhas com Font.plainSubstrByWidth.
**Armadilha de API:** plainSubstrByWidth(String, int) devolve a **String** que cabe, nao um
indice. A assinatura que devolve indice e a de 3 argumentos com boolean; tratar o retorno como
int nem compila (erro "incompatible types: String cannot be converted to int").

### O script de layout envelhece junto com o codigo (LICAO, 02/10/2026)
O script espelha as formulas do layout() na mao. Ao mudar o chrome (duas linhas de status
custaram STATUS_H), o script continuou rodando e imprimiu **numeros antigos com "OK"** -- um
falso negativo silencioso, pior que falhar. Mudou no layout(), muda no script, e compara os
numeros antes de acreditar neles.

### arrowsRight() usado como se fosse a borda ESQUERDA (BUG, 02/10/2026)
A formula terminava em arrowsRight() - GAP. arrowsRight() e a borda **DIREITA** do grupo
das duas setas, e o botao da seta de baixo tem 20px de largura: a formula acabava 6px antes
dela, ou seja **14px DENTRO do botao**. Como a formula e desenhada DEPOIS dos widgets, o
que aparecia era a seta com o texto da formula atravessado em cima -- exatamente o "as setas
estao em cima da formula". Agora existe arrowsLeft() e a formula para em
arrowsLeft() - GAP.
**Licao geral:** um metodo que devolve uma borda lateral e nomeado pelo LADO que ele
representa, e nao pelo lado que o CODIGO ANTIGO usava. Nome herdado de geometria errada
vira mais um bug, porque parece descriptive e ninguem reexamina.
**Como isso foi pegado:** o script de layout passou a medir a linha da lista e a
**calibrar o detector** -- rodar o script com a geometria antiga e ver reprovar. Sem
calibrar, um check que sempre passa nao prova nada. Ver a licao do script que envelhece,
duas acima.

### A MESMA subtracao em dois lugares = erro de 42px (BUG, 02/10/2026)
arrowsRight() ja subtraia ROW_BTN_W * 2 + ROW_BTN_GAP para dar a borda direita do
grupo das setas, e upX = arrowsRight() - (ROW_BTN_W * 2 + ROW_BTN_GAP) subtraia de novo
para achar a esquerda. Duas subtrai em sequencia: sobraram 44px de vazio entre a seta de
baixo e o Del. Passou por **duas** rodadas de feedback da jogadora porque eu mexia no
**vao** perto do problema (6px -> 2px) em vez de medir as duas pontas do grupo.
**Licao:** quando dois metodosDividem a mesma subtracao, um deles esta subtraindo duas
vezes. E quando o defeito e "esta longe do vizinho", medir a distancia absoluta antes de
mexer no vao -- mexer no vao nao muda distancia nenhuma quando o buraco e de 42px.
**Como pegar:** o script de layout mede vao seta->Del e tem de ser exatamente
ROW_BTN_GAP. Calibrado contra a conta antiga, que reprova com
vao seta->Del fora do esperado: 44 (esperado 2).

### Diminuir um texto para compensar espaco morto piora o problema (LICAO, 02/10/2026)
Com os 42px vazios na linha, "corrigi" a formula estreitando o nome do preset (120px ->
65px). O nome ficou menor **para sempre**, mesmo depois de o buraco ser arrumado, porque
ninguem tinha ligado as duas coisas. **Nao aperte um elemento para caber num espaco que
voce ainda nao auditou: ache de onde vem o espaco primeiro.**

### Brigadier 1.3.10: StringArgumentType.string() NAO e mais guloso (LICAO, 02/10/2026)
Em brigadier-1.3.10, string() devolve StringType.QUOTABLE_PHRASE e parse chama

eader.readString(): uma palavra sem aspas, ou uma frase **entre aspas**. Espaco sem aspas falha
no parse do Brigadier, antes de qualquer codigo do mod ver o argumento. greedyString() continua
existindo se algum comando precisar mesmo do resto da linha. Sintoma: build verde, comando com
espaco nao funciona, e a mesma coisa sem espaco funciona.
**Armadilha:** a **sugestao** (SuggestionsBuilder.suggest(preset.name())) entregava o nome com
espaco, ou seja, montava um comando que o Brigadier recusa. Sugestao de nome digitado tem de ir
pela forma de comando. Neste mod: RollPreset.commandName() (espaco -> `_`, preserva caixa e
acento). **Nao** devolver RollPreset.key(): ela e minuscula e sem acento porque serve para
comparar, e o texto volta para o chat da jogadora.
Fonte da verdade: StringArgumentType.java dentro do brigadier-1.3.10-sources.jar no cache do
Gradle (~/.gradle/caches/modules-2/files-2.1/com.mojang/brigadier/). Nao existe

et.minecraft.commands.arguments.StringArgumentType no 1.21.11.

### Screen: fill opaco DEPOIS de super.render esconde widget sem tirar o clique (LICAO, 02/10/2026)
Screen.mouseClicked acha o widget pelo retangulo, nao pelo pixel desenhado. Entao o sintoma de
"o botao nao aparece mas da para clicar nele" e de ordem de desenho, nao de posicao nem de
cor. Foi exatamente a leitura errada que a jogadora fez ("a escrita esta mais para a direita"):
o unico texto que sobrevivia era a formula, desenhada depois do fill e alinhada pela direita.
Regra: fundo e realce de fundo **antes** de super.render; o que o widget nao desenha
(quadradinho de cor, formula, texto nao centralizado no botao) **depois**.
**Consequencia a assumir:** o realce da linha em edicao foi para tras dos widgets das linhas e
parou de aparecer em cima deles. A compensacao foi um risco na faixa `rowY + listRowHeight - 2`,
que nenhum botao da linha ocupa (todos tem `ROW_H - 2` dentro de uma linha de `ROW_H`).
Nao ha mudanca de geometria nesse caminho, e o script de layout continua dando os mesmos numeros.

### O console do PowerShell mostra `?` onde o arquivo tem acento (LICAO, 02/10/2026)
Ao ler um `.md` e imprimir no console, um caractere acentuado correto aparece como `?` ou
como `<caráter de substituicao>`, e da a impressao de que o arquivo esta corrompido. Confirmar
sempre por codepoint antes de "reparar" um arquivo que esta inteiro. O mesmo vale no contrario:
um `U+FFFD` de verdade **nao** aparece assim se o console sabe mostrar, entao a ausencia de
`?` na tela tambem nao e prova. Leitura por codepoint:
`powershell
\# Project Memory — TabletopRPG (Fabric 1.21.11, Mojang mappings)

## 01/10/2026 — Aba 3 (Skills/Magias): scroll vazando, card fixo, ▲▼ (FATO verificado)

**PAUSADO com codigo nao commitado.** `StatusScreen.java` +360/-40, HEAD `d031a92`. Relatorio:
`agent/reports/2026-10-01-pausa-skill-magia-layout.md`. `compileClientJava` verde; **nada validado em jogo**.

### Bug: scroll da aba 3 desenhado na aba 2 (causa raiz)
Nao era "faltou esconder a barra" — a barra **nunca foi widget**, e desenhada a mao por
`Column.renderBar(GuiGraphics)` com guarda so `barH <= 0 || maxScroll() <= 0`. A geometria (`contentH`, `barH`,
`thumbY`) so e recalculada por `Column.layout()`, que roda DENTRO de `addSkillColumn`/`addSpellColumn`, **so na
aba 2**. Saindo da aba, `buildInfoTab()` nao toca nessas colunas: sobra `contentH` antigo, `maxScroll() > 0`, e a
barra antiga sai por cima. **Correcao: as duas chamadas de `renderBar` dentro de `if (activeTab == 2)`.**
A interacao ja era filtrada por aba (`columnUnderBar`, `mouseScrolled`), entao antes o clique parava e o desenho
nao: barra "viva sem fazer nada". **Licao: `Column` e estado de tela, nao widget — guarda de aba e obrigatoria
para TODO desenho que use a geometria de uma coluna.**

### Card de altura FIXA, igual em skill e magia
`LIST_ENTRY_H = INV_PAD*2 + LIST_NAME_BTN_H + LIST_LINE1_ADV + LIST_LINE2_ADV + LIST_LINE3_ADV + LIST_ROW_GAP`
= **71**. `LIST_DEL_BTN_H=16`, `LIST_LINE1_ADV=14`, `LIST_LINE2_ADV=11`, `LIST_LINE3_ADV=16`.
`skillEntryHeight` e `spellEntryHeight` devolvem `LIST_ENTRY_H` sem condicional — **as 3 linhas contam sempre,
mesmo vazias**, e e isso que faz os dois cards terem a mesma altura.
`LIST_LINE3_ADV = 16` e nao 11 porque e a **linha do Del** e o Del tem 16 px; com 11 invadiria a folga do card.
`addDeleteButton` passou a usar `LIST_DEL_BTN_H`. `skillColumn.nameBtnH = LIST_NAME_BTN_H` (antes ficava 12).
O Del da skill desceu para a 3a linha (`right = null` no `addEntryNameButton`), dando ao nome a largura toda; o
Del da **magia continua na ultima linha COM TEXTO** (pedido do usuario em 01/10/2026, nao regrediu).

### ▲▼ a esquerda das skills
`addSkillMoveButtons(x, y, skillName, index, total)`: ▲ so se `index > 0`, ▼ so se `index < total - 1` (sem botao
morto). Respeita `clippedHeight`. `LIST_MOVE_W=12`, `LIST_MOVE_GAP=2`, faixa de 28 px. Zera `delPending` junto
(mover troca o indice guardado). Envia `SheetSkillPayload.move(targetName, skill, ±1)`.
**Magias nao tem mover, e proposital:** a ordem exibida nao e a guardada (`Spellbook#visible` ordena por circulo
e depois por nome), entao nao existe indice guardado para mover.

### `SkillOp.MOVE` ja existia (FATO verificado)
`RpgNetworking.SheetSkillPayload.move(targetName, skill, delta)` + `moveSkill` (valida `delta` em `{-1,+1}`) +
`SheetData.withSkillMoved` estavam prontos. **Delegar UI sem ler o servidor antes leva o subagente a criar payload
duplicado.** Ler o caminho de rede ANTES de delegar e o que evitou tocar `RpgNetworking.java`.

### Delegar UI com constante nova
O prompt tem que trazer as constantes **ja calculadas** e a lista do que **nao** pode ser tocado. Sem isso o
subagente recalcula e diverge da conta que voce ja fechou.

### Validador que nao abriu o arquivo (FATO verificado)
`tcc-validador` recebeu um checklist de 10 itens e devolveu o **checklist reescrito como "defeito"**, sem abrir o
arquivo. Afirmou que `renderBar` estava fora da guarda de aba — eu tinha acabado de confirmar que estava dentro
(linha 3155). **Regra: relatorio de validador sem `arquivo:linha` e suspecto; confirme antes de virar "defeito"
no relatorio.** Os 10 itens dele NAO foram registrados como defeitos reais.

### ▲▼ e a fonte do Minecraft (HIPOTESE, nao verificada)
Codepoints 9650/9660 estao no arquivo (UTF-8 sem BOM, verificado), mas a fonte padrao do jogo **pode nao ter os
glifos**, e nesse caso o botao sai como caixa vazia. Nao troquei por ASCII porque o usuario pediu setas; se
aparecerem vazios em jogo, a troca e por texto.

### Terminal: glifo ausente e nao glifo apagado (FATO verificado)
`Select-String` imprimiu `Component.literal("")` para `literal("▲")`. O arquivo estava correto: o console do
PowerShell nao renderiza o caractere. **Confirme os codepoints (`[int]$_.Groups[1].Value.ToCharArray()`) antes de
"consertar" um bug de codificacao que nao existe.**

## 01/10/2026 — Arrastar a barra e lista com altura zero (FATO verificado, NAO validado em jogo)

Rodada seguinte, ainda **nao commitada** em `StatusScreen.java`. Build verde (`build`, 17s).

### Arrastar nao desce: faltava `rebuildWidgets()` (FATO verificado)
`Column` **nao e viewport**: a lista e feita de widgets recriados a cada montagem. Mudar o campo `scroll` sem
remontar **nao move nada na tela**. `scrollFromMouse(double)` so atribui `scroll`; `mouseDragged` nao remontava.
A roda ja fazia certo (`scrollColumn`: `flushCd()` + `delPending = -1` + `rebuildWidgets()`), e e por isso que
uma funcionava e a outra nao. **Corrigido nos dois blocos de `mouseDragged`, e tambem em `mouseClicked`** (o
primeiro clique saltava a barra e deixava a lista 1 frame atras: e assim que "arrastar nao funciona" comeca).
`draggingBar` sobrevive ao rebuild porque e field da `Column` e `buildPanel` so reseta `delPendingBox`/`contentH`/`scroll`.

### "Roda desce mas nao mostra" era o bug de altura zero (FATO verificado)
Nao era problema de redesenho. Em janela pequena `listBottom == listTop` (altura zero), mas `contentH` continuava
soma de todos os cards, entao `maxScroll() > 0`: **a barra se movia sem haver lista atras**. Mesmo sintoma do
"nao da pra descer" das magias.

### Cabecalho dentro da faixa rolavel (decisao do usuario, 01/10/2026)
O usuario escolheu **rolar o cabecalho** (nao encolher). Nas DUAS colunas agora: secao FIXA, e o resto do
cabecalho rola com a lista. Magias: `headerH = rowH * 5`, `contentH = headerH + spellsH`, `listTop` logo abaixo
do titulo, cabecalho montado por `addSpellHeader(x, w, rowY)` com `rowY = listTop - scroll`. Skills: `headerH = rowH`,
"+ Skill" rolavel.
`addSection` devolve `top + rowH`, entao `listTop = Math.max(y, top)` **ja** fica abaixo do titulo fixo — nao e
preciso de `top` cru. **Cada widget do cabecalho tem de nascer por `clippedHeight` + `Math.max(y, listTop)`, e cada
rotulo (`TextLine`) guardado por `lineFits`**; sem isso o cabecalho invade o titulo ao rolar.
O `headerH` **tem de entrar** no `contentH`: sem ele a rolagem para uma faixa antes do fim e o "+ Magia" nunca
aparece ao descer.

### O cliente encerra sozinho, sem crash (FATO verificado, 2x)
Duas vezes: `Player joined the game` e, ~1 a 2 min depois, `Stopping!` + `Stopping singleplayer server as player
logged out`, **sem `Exception` nem `ERROR` no log**. Encerra limpo, nao quebra. Se voltar a acontecer e nao for
voce fechando a janela, e coisa para investigar (pedir o `runclient-*.log` da hora).

### Cuidado com subagente que reporta pendencia como entregue
O implementador aceitou duas pendencias em vez de resolver: (1) `mouseClicked` nao remontava (1 frame
inconsistente) — corrigi; (2) a faixa da barra cobre 4 px da borda direita dos botoes do cabecalho — **ainda
assim**. Ele tambem rodou `javac` sem classpath e contou "100 erros" irrelevantes, e checou chaves/parenteses em
vez de compilar de verdade. **Chaves balanceadas nao provam que compila: rode o build.**

### Scanner de nome precisa conhecer os OPERADORES do parser (BUG, 02/10/2026)
FormulaResolver.scanWords andava com isDiceAt ate o fim do d6 e caia no dl do
dl1: nao achava atributo chamado dl, devolvia a palavra e o preset era recusado com
unknown attribute 'dl'. **isDiceAt so olha o que vem DEPOIS do d, e depois do dado
vem o operador** (kh, dl). **Licao:** quem faz scanner de texto sobre a grammatica de
outro precisa ter uma lista de OPERADORES, nao so de dados e de nomes. dl, kh, ++
sao palavra que parece atributo. **Como pegar:** teste de preset com a formula mais
complicada que a jogadora realmente usa, nao com 1d6+Forca.
**Prova:** o teste createAcceptsRepeatAndKeepDrop falhou com unknown attribute 'dl'
antes do isKeepDropAt existir.

### O commit do colega nao e a fonte do bug que ele disse ter arrumado (02/10/2026)
A jogadora avisou que o commit do amigo resolvia 6#2d6dl1 no preset. git show --stat
do commit mostrou so StatusScreen.java: **verificar a afirmacao no diff antes de
assumir**. O bug era de um commit meu, tres fases antes. Se eu tivesse confiado na
frase, o preset continuaria quebrado no push.

### O transporte do comando shell COME a barra invertida (CAUSA RAIZ, 02/10/2026)

17 palavras do `project-memory.md` estavam comidas, com a primeira letra faltando.
**A causa nao e PowerShell nem here-string: e o transporte do comando `shell`.** Backslash
seguido de letra vira escape antes de chegar ao PowerShell: barra+a vira BEL (U+0007),
barra+b vira BS (U+0008), barra+v vira VT (U+000B), barra+f vira FF (U+000C).

**Por isso as 17 estavam sempre dentro de crase, escrevendo um identificador:** a crase
oferece `\`fill\``, e o transporte come o `f`. O arquivo inteiro e' lido depois e a palavra
aparece corrompida sem ninguem ter perceivedido. **Confirmado ao vivo:** a licao que eu
escrevi sobre esse bug recriou o bug no mesmo commit, porque o texto dela falava em
`fill` e `arrowsRight`.

**Licao:** nunca escreva `\`seguido de letra` dentro de um comando `shell` passando texto
para arquivo. Escreva o bloco com a ferramenta de escrita, ou duplique a barra.

**Como pegar:** varrer `char < 32 && != 9` no arquivo e conferir o contexto. A regra
controle -> letra vale para as 17: 7 vira a, 8 vira b, 11 vira v, 12 vira f.

### `scanEncoding` NAO acha caractere de controle (FALHA DA TASK, 02/10/2026)
O build passou verde com as 17 palavras comidas. A task caça mojibake, ideograma e
U+FFFD -- tres falhas de TRANSCODIFICACAO, nao de ESCAPE. Nenhuma delas e U+0007.
**Licao:** check de encoding so prova o que ele olha; arquivo que passa o `scanEncoding`
ainda pode ter palavra quebrada. Falta um check de caractere de controle na task, e essa
correcao NAO foi feita ate 02/10/2026.

### Deduplicar arquivo de memoria: copia "mais nova" NAO e superset (02/10/2026)
project-memory.md tinha 3 copias (8408 linhas). A 3a, mais nova, tinha **menos**
conteudo em uma secao: 3 licoes (Brigadier 1.3.10, Screen: fill opaco, O console do
PowerShell) so existiam na 1a. Apagar as copias velhas por "manter a mais nova" teria
perdido as tres. **Licao:** antes de escolher a copia a manter, comparar o conjunto de
**titulos** e nao o de linhas: as 3 copias tinham os mesmos titulos ## e mesmo
numero de linhas, e a diferenca so apareceu em ### dentro de uma secao.
**Como pegar:** diff de conjunto de linhas normalizado, com cada perda pareada com uma
ganha, e cada par justificado.

### Comparacao de texto que cruza processo precisa de encoding explicito (02/10/2026)
Checar perda de conteudo com git show dentro do PowerShell deu **328 linhas perdidas
falsas**: git show escreve UTF-8 e o PowerShell decodificava pelo codigo de pagina do
console, trocando todo acento por ?. As duas pontas da comparacao estavam em encodings
diferentes, entao o script media o console e nao o arquivo. Com
[Console]::OutputEncoding = UTF8 a conta caiu para 19, todas intencionais.
**Licao:** diff de conteudo depois de um git show precisa de encoding fixado nas DUAS
pontas, senao o resultado e um relatorio de mojibake.

### Guarda por substring que e sufixo de palavra acusa erro inexistente (02/10/2026)
Checar if ( -match 'ill opaco') para provar "U+000C reparado" reprovou num arquivo
sem nenhum U+000C: 'fill opaco' -like '*ill opaco*' e verdadeiro. O texto estava
correto e o teste estava errado. **Licao:** guarda de verificacao tem que ancorar no
caractere que ela procura (.Contains([char]12)), nunca numa fatia de palavra que pode
ser parte de outra palavra.

### StreamCodec.composite: escrever o codec a mao acima de 2 campos (02/10/2026)
FATO verificado. `ThreatSheet.Action` (4 campos) nao compilou com `composite`: erro
"no suitable method found" numa chamada de **3 pares** aninhada dentro de um composite de
2 pares. Os composites de 2 e 3 pares continuam valendo (o de 3 do `RollPreset` compila
ate hoje). A solucao aplicada e mais barata que a causa raiz: **record com mais de 2
campos escreve `StreamCodec` a mao**, com `ByteBufCodecs.*` dentro de `decode`/`encode`.
Foi assim em `ThreatSheet.Identity`, `ThreatSheet.Action` e `ThreatSheet.Vitals`.
HIPOTESE (nao investigada, e nao precisa ser): o overload de 3 nao faz inferencia
quando o record e aninhado. Nao gastar tempo nisso.

### Pacote do MouseButtonEvent nesta versao (02/10/2026)
FATO verificado. `net.minecraft.client.gui.input.MouseButtonEvent` **nao existe** em
1.21.11; o pacote e `net.minecraft.client.input.MouseButtonEvent`
(confirmado em `PresetsScreen.java:11` e `StatusScreen.java:12`). Build verde com o
pacote errado da a mesma cara de "classe nao encontrada" que API inventada.

### Checkbox e Tooltip so tem construtor por builder nesta versao (02/10/2026)
FATO verificado com `javap`. `Checkbox` nao tem construtor publico direto: usar
`Checkbox.builder(...).pos(...).maxWidth(...).selected(...).onValueChange(...).build()`
e depois `setSize(w, h)`. `Tooltip.create` aceita **1 ou 2** `Component` — passar 4
(argumentos de texto soltos) da "no suitable method".

### GUI scale automatico torna 240 e 270 px logicos o caso comum (02/10/2026)
FATO medido por aritmetic de layout nesta rodada. Em 1080p com GUI scale automatico a
tela logica fica em **270**; em 720p, em **240**. Uma coluna de tela com altura **fixa**
de 246 px de conteudo (ficha de ameaca: 3 linhas de texto + bloco de HP de 3 linhas +
descricao + atributos) estourava o painel e deixava a lista de atributos **inteiramente
fora da tela**, sem erro nenhum e com build verde. **Licao que vale para qualquer tela
nova deste projeto:** nunca dimensionar coluna por altura fixa; derivar do espaco real
(`panelY + panelH` menos rodapé) e **rolar** o excedente, com bloco que precisa caber
inteiro nao sendo criado. Isso e a mesma classe de risco do `StatusScreen`, que tambem
tem colunas longas.

### Gradle "UP-TO-DATE" no build nao prova que o codigo novo compila (02/10/2026)
FATO verificado. Um `gradlew build` que reporta `> Task :compileJava UP-TO-DATE` e
`:compileClientJava UP-TO-DATE` so prova que **nada mudou** desde a ultima compilacao.
A prova honesta e a execucao em que o Gradle recompilou depois da edicao. Ler o
`BUILD SUCCESSFUL` do build como confirmacao de codigo novo e um erro de validacao.

### SheetModelHolder.current() no servidor e seguro (02/10/2026)
FATO verificado por leitura. `SheetModelStore` publica o modelo em `SERVER_STARTED` e
em `JOIN` (`SheetModelStore.java:121`, `:137-159`), entao o handler de servidor pode usar
`SheetModelHolder.current()` para alinhar a ficha antes de gravar, sem races de startup.

### FormattedCharSequence.toString() devolve o NOME DA CLASSE, nao o texto (02/10/2026)
FATO verificado em jogo. `font.split(...)` devolve `List<FormattedCharSequence>`, e
`seq.toString()` NAO e o texto: devolve algo como
`net.minecraft.util.FormattedCharSequence$$Lambda$1234/0x00007f...`. Foi o que apareceu
na tela no lugar da descricao das habilidades da ficha de ameaca
(`ThreatSheetScreen.drawWrapped`, linha 1335 na epoca). O texto se extrai percorrendo os
code points:

```java
seq.accept((index, style, codePoint) -> { out.appendCodePoint(codePoint); return true; });
```

E `FormattedCharSequence` **nao tem `length()`** nesta versao (compilador acusa), entao
`new StringBuilder(seq.length())` tambem nao compila.

### Tela que le de um MODELO, nao do widget, so enxerga o velho (02/10/2026)
FATO verificado em jogo. A ficha de ameaca desenhava HP, CA e atributos lendo um mapa
`values`, e esse mapa so era atualizado em `save()`, nos formularios e ao reconstruir a
coluna. O Mestre digitava o HP maximo e a barra mostrava o valor velho ate ele trocar de
aba. **Licao geral:** quando a tela tem uma copia intermediaria do estado dos widgets, ou
ela e sincronizada no `render()`, ou o usuario ve dado velho sem nenhum erro. O custo e
trivial: `syncFromWidgets()` no topo do `render` chama so `EditBox.getValue()`.

### Tamanho de referencia da ficha do jogador (02/10/2026)
FATO verificado por leitura. `StatusScreen.java:104` tem
`MAX_PANEL_W_STATUS = 600`, e a altura **nao** tem teto: vem de `buildPanel(x0, panelW,
topY, bottomY)`, ou seja, a tela inteira. A ficha de ameaca tinha `MAX_PANEL_W = 430` e
`MAX_PANEL_H = 360` fixo, que e o que espremia os atributos. Regua: quando o Mestre pedir
"do tamanho da ficha do jogador", a resposta e 600 de largura e altura sem teto.

### Padding de lista e largura de coluna na ficha de ameaca (02/10/2026)
FATO verificado no codigo. O fundo de toda lista de `ThreatSheetScreen` e desenhado em
`render` com `fill(region.x - 2, region.y, region.x + region.w - BAR_W - 2, ...)`,
enquanto o conteudo vai em `region.x + 2`: a distancia real da borda do fundo ate o
texto e de **4 px**. Mexer no `region.x` do `set()` NAO aumenta o padding, porque leva
fundo e texto juntos -- foi exatamente por isso que dois patches de padding anteriores
nao mudaram nada na tela. O que o Mestre pede como "colado na caixa" se corrige no `+ 2`
do texto, no `render`, e nao no layout.

FATO verificado. `colBarX()` era `leftX - BAR_W`, entao a barra da coluna esquerda ficava
dentro da margem do painel e nao dentro da coluna, e a caixa da esquerda parecia mais
larga. Agora `colW = (panelW - 2*PAD - COL_GAP - BAR_W - 2) / 2` reserva a barra entre as
colunas e `colBarX() = leftX + colW + 1`.

FATO verificado. `descH` era `max(DESC_MIN_H, colViewH() - COL_FIXED_H - DESC_HEAD_H -
ATTR_HEAD_H - ROW_H)`: a descricao ficava com todo o espaco sobrante e os atributos ficavam
com uma unica linha visivel, o que obrigava a rolar a coluna. Agora o sobrante e dividido
e a descricao fica com no maximo metade.

FATO verificado. O filtro do valor de pericia era `-?\\d{0,4}`: colar o "-" depois dos
digitos ("5-") era rejeitado, entao negativo so funcionava digitando o sinal primeiro.
`VALUE_MIN` ja era `-999`, ou seja o modelo nunca recusou negativo -- o defeito era so de
digitacao. O filtro agora aceita o sinal nas duas pontas e o `save` normaliza (o "-" vale
em qualquer ponta, o "+" e ignorado).

FATO verificado. `COL_HP_OVER = 0xFFFF8A8A` existe em `CharacterSheetScreen` como cor de
excedente do jogador, e e acessivel sem import porque as duas telas estao no mesmo
pacote `com.pedro.tabletoprpg.client`. O `drawHpBar` da ameaca ja usava
`max(hp, max)` como denominador, mas pintava a barra toda de `COL_HP`; agora a parte que
passa do teto usa `COL_HP_OVER` e o texto mostra `12 / 10 (+2)`.

REGRA DE PROCESSO (erro meu, repetido duas vezes): item de layout "colado", "estourando"
ou "maior" nao se corrige por leitura de layout. A unica medida valida e a distancia
entre a borda do fundo e o conteudo dentro do `render`.

### "Colado" na ficha de ameaca e, quase sempre, sobreposicao (02/10/2026)
FATO verificado. Tres rodadas seguidas do mesmo sintoma, com uma causa que nao era falta
de espaco e sim **espelhamento de posicao**:

- `SECTION_H = 10` era a faixa do titulo, e o botao ao lado tem `SMALL_BTN_H = 16`
  desenhado em `y - 3`. O botao terminava em `y + 13` e a lista comecava em `y + 10`:
  **invadia 3 px da caixa**. A faixa do cabecalho virou `SECTION_H + SMALL_BTN_H + 4`.
- A caixa de valor do atributo era criada em `region.x + region.w - boxW`, encostando
  na barra de rolagem da coluna, que ocupa os ultimos `BAR_W = 4` px da regiao.
- O `Del` das tres listas e posicionado por `edit.getRight() + GAP`, entao mexer so em
  `editW` reposiciona os dois botoes juntos. A folga certa e 16 px, nao 8.
- Linhas de pericia tem 11 px; o texto em `rowY + 1` nascia colado no topo.

REGRA (terceira ocorrencia, agora com metodo): quando o Mestre escrever "colado", "sem
espaco" ou "feio", **medir** a distancia entre a borda da caixa e o elemento no codigo de
`render` antes de escolher um numero. Nenhuma das tres rodadas falhou por falta de espaco;
falharam por posicao. E o numero nunca sai "a olho": cada valor aqui veio de subtrair a
posicao real da borda.

### Dois caminhos de desenho para o mesmo dado, com guardas diferentes (02/10/2026)
FATO verificado. Os rotulos dos atributos eram desenhados no `render`, sem nenhuma
checagem de espaco vertical, e as caixas de valor nasciam em `rebuildRegion`, sob a
guarda `inColumn(y, ATTR_HEAD_H + attrH)` que exige a lista **inteira** cabendo. Com a
lista maior que o espaco, os nomes apareciam e as caixas nao. Agora a guarda e "cabe pelo
menos uma linha visivel" e o `attrRegion` recebe a altura que sobra de verdade.

REGRA: quando um mesmo dado aparece na tela, perguntar **quem desenha cada parte e sob qual
condicao**. Divergencia entre duas guardas e a causa mais comum de "aparece o texto, mas a
caixa nao".

### `MultiLineEditBox` do vanilla desenha o proprio contador (02/10/2026)
FATO verificado por inspecao do `.class` dentro de
`minecraft-clientonly-1.21.11-loom.mappings.1_21_11.layered+hash.2198-v2.jar`: a classe
`net.minecraft.client.gui.components.MultiLineEditBox` tem os campos `count` e `limit` e
**desenha "usado/maximo" no canto inferior direito da caixa sozinha**, assim que
`setCharacterLimit` e chamado. Por isso remover um contador desenhado a mao nao tira
numero nenhum da tela.

Consequencia pratica: em qualquer `MultiLineEditBox` deste projeto, **nao chame
`setCharacterLimit`** se o Mestre nao quer numero na tela. O limite passa a ser
verificado no `save`, com mensagem de erro, em vez de corte silencioso.

Como descobrir rapido (o que confirma que nada nosso desenha aquilo): `run/mods` vazio, sem
mixin do widget em `tabletop-rpg.mixins.json`, `build.gradle` sem dependencia de texto, e
todos os imports do widget sendo `net.minecraft.*`.

ERRO MEU, REGISTRADO: afirmei duas vezes seguidas que o contador tinha sido removido,
com o bytecode conferido, sem levar em conta que o numero na tela podia vir de fora do
projeto. Contra "ainda aparece", a pergunta util nao e "meu codigo esta limpo", e sim
"**outra coisa esta desenhando isto**". Antes de fechar um bug visual como resolvido,
perguntar de onde pode vir cada pixel na tela.

### Quatro ideogramas escapados na memoria (02/10/2026, corrigido no mesmo dia)
FATO verificado pelo proprio `scanEncoding`: duas frases minhas entraram com palavras em
ideograma no meio do portugues ("Como<ideograma> rapido", "cada pixel<ideograma> tela").
O `scanEncoding` reprova ideograma, mojibake e U+FFFD em `src/` e `agent/`, e ele esta
ligado ao `check`, ou seja, isso quebrava **build inteiro** -- nao so a tarefa.

REGRA: nunca deixar passar caractere nao-latino em texto aqui. Antes de commitar
`agent/`, o `build` ja teria falhado; se falhar nele, a causa quase sempre e digitacao
estranha e nao o arquivo que o log acusa em primeiro lugar. O log da tarefa lista
`arquivo:linha` e o codigo hexadecimal do caractere, que e a linha exata do culpado.

**E nao cole o caractere culpado ao descrever o defeito**, nem numa citacao e nem num
trecho de exemplo. Em 02/10/2026 o relatorio que explicava os 4 ideogramasescapados da
memoria **os reproduziu**, e reprovar o build de novo por causa do texto que existe para
registrar o problema. Descreva ("quatro ideogramas", "duas frases") e cite arquivo e
linha, nunca o caractere.

### `scanEncoding` reprova o BUILD INTEIRO, e filtra de saida esconde a causa (02/10/2026)
FATO verificado. A tarefa esta ligada ao `check`, entao um ideograma em `agent/` derruba
`gradlew build` -- nao so a `scanEncoding`. E o detalheimportante: eu filtrei a saida com
`Select-String -Pattern 'error:|BUILD'`, que **nao casa** com as linhas
`[scanEncoding] FAIL:  ...` porque o Gradle imprime `logger.error` sem o prefixo de
erro. O log dizia so "9104 caracteres nao-ASCII... legitimos" e eu quase li isso como
"e so cosmatico".

REGRA: quando o build falha numa tarefa, rode a tarefa SO, filtrando pelo nome do
prefixo dela (`'FAIL'`), e nao pelos marcadores genericos de erro.

### Vínculo persistente de ficha: tag no mob, cache em memória, selfHeal (02/10/2026)
FATO verificado. `ThreatSheetBinding` guarda o vinculo ficha -> mob na **tag do NBT do
mob** (`tabletoprpg_sheet_<id>`), nunca no NBT do jogador: o vinculo pertence ao monstro e
precisa valer com o Mestre fora e depois do restart. Dois `ConcurrentHashMap` sao cache, e
`selfHeal(server)` refaz varrendo `level.getAllEntities()`, exatamente como o
`selfHealCameraMobs` ja fazia com os mobs de camera.

O `id` da ficha e UUID sorteado no servidor e reaproveitado em toda edicao
(`ThreatSheetStore.save` faz `sheet.withId(current.get(index).id())`). O cliente NUNCA
sorteia id e so devolve o que o servidor mandou. **Por que:** o `key()` da ficha sai do
NOME, e renomear mudava a chave -- item da mochila e tag do mob ficavam orfaos.

### `UseEntityCallback`: quem chega antes consome (02/10/2026)
FATO verificado. O `CombatController` consome o clique direito em criatura e devolve
`FAIL`; o `PlayerControlHandler` so devolve `FAIL` para quem nao pode interagir, e o
Mestre sempre pode. Portanto qualquer item que queira responder ao clique em criatura tem
que entrar **dentro** do callback do `CombatController`, antes do `toggleSelection` --
registrar um `UseEntityCallback` novo nao garante ordem.

Consequencia aceita pelo Mestre: com a ficha na mao, o clique em monstro **amarra** e nao
seleciona. Para selecionar, o caminho e `/rpg mob <nome>`.

### `@e[name="..."]` acha monstro com nome customizado, mesmo invisivel (02/10/2026)
FATO verificado. E o mecanismo vanilla que o Mestre pediu para "mencionar o mob em
comandos como /tp": `/tp @e[name="Goblin"] x y z` funciona sem mod nenhum. Por isso o
nome de exibicao virou **obrigatorio** na ficha (recusa no cliente e no servidor), e por
isso `applyDisplayName` grava `setCustomName` **sempre**, deixando a **visibilidade** para
a caixa "Exibir nome em cima da ameaca?". Desmarcar a caixa esconde o nome; nao o apaga.

## 02/10/2026 --amarra por clique no item: eventos e autoridade do id

### CORRECAO: `FAIL` no `UseEntityCallback` NAO impede o `UseItemCallback`
FATO verificado em jogo, e contraria o bloco acima. Com a ficha na mao, clicar no monstro
**amarrava e abria a ficha ao mesmo tempo**. O clique de entidade e o uso do item chegam em
pacotes separados e a ordem nao e garantida; devolver `FAIL` no `CombatController` nao segura
o item. Os dois caminhos precisam consultar a **mesma** decisao -- se ha criatura sob a mira,
amarrar; se nao ha, abrir. Assim a ordem dos eventos deixa de importar.

### Ray trace de mira no servidor: `ProjectileUtil`, nao maths propria
FATO verificado. O unico padrao que compila e acerta e o mesmo do highlight do cliente:
`ProjectileUtil.getEntityHitResult(player, start, end, new AABB(start, end).inflate(1.0),
predicado, maxDist * maxDist)`, com `start = player.getEyePosition()` e `end` na direcao do
olhar. **Nao existem** `AABB.expandTowards(Vec3,double)` nem `Vec3.clamp(Vec3)` em 1.21.11;
escrever a maths a mao quebra o build.

### Depois do save, quem manda no id e o store -- nao o cliente
FATO verificado. `RpgNetworking` usava a ficha que o cliente mandou depois de gravar. O store
reaproveita o id guardado ou sorteia um quando a ficha e nova, entao o id do cliente pode estar
vazio ou desatualizado. Efeito: o mob leave de ser encontrado e **nao e renomeado**, e o item
entregue nasce com id que nao existe em lugar nenhum. Correcao: reler a ficha do store pelo
`key()` e usar essa em todos os caminhos.(build verde; validacao em jogo pendente)

### `name=` e identificador humano; a tag e o identificador estavel
FATO verificado. `@e[name="..."]` casa com o custom name, entao quebra quando a ficha e
renomeada e colide com homonimos. A **scoreboard tag** `tabletoprpg_sheet_<id>` ja e gravada na
amarra e nao muda quando a ficha e editada: `@e[tag=tabletoprpg_sheet_<uuid>]` e a chave confiavel
para comandos. O UUID e comprido; o Mestre decidiu por enquanto nao encurtar.

### Falha silenciosa vira recusa explicita
FATO verificado. Amarrar ficha com nome de exibicao vazio deixava o monstro sem nome, e o unico
sintoma era `@e[name="..."]` falhando longe do gesto que causou o problema. Agora a amarra e
recusada com mensagem dizendo para preencher o nome e clicar em Atualizar.
