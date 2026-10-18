# Implementation Report

## Status
IMPLEMENTADO + build verde (2 rodadas). Aguardando validacao da jogadora em jogo e
autorizacao para commit. Arvore com 6 arquivos modificados (nao commitados):
4 do lote 1 (escada/comandos/carrossel) + RpgNetworking e TabletopRpgClient da
rodada 2 (fix de reconexao do carrossel + instrumentacao de diagnostico).

## Objective
Tres feedbacks novos da jogadora (08/10/2026), sobre o commit `3f3a025`:

1. BUG F (escada): mob com mais de 2 blocos de largura ainda nao se fixa na
   escada de mao (vanilla Ladder). Dava o mesmo erro de antes: "No room above
   that block to place the entity."
2. BUG D (camera): sair no meio do modo investigacao/combate e voltar fazia os
   mobs com camera sumirem do carrossel de espectador -- nao dava mais para
   trocar a camera para eles nem especta-los. (O fix de 05/10/2026 cobria
   respawn/troca de dimensao, nao a desconexao e reconexao.)
3. COMANDOS: `/rpg mob view` e `/rpg mob give` devem resolver pelo NOME DA
   FICHA (unico no store), nunca pelo nome de exibicao do mob (repetivel). O
   aviso de ambiguidade ("N ameacas usam esse nome") nao deve mais acontecer.

## Scope / Subtasks
- Subtask 1 (Bug F): excecao da lamina da escada na checagem de colisao do
  destino em `moveSelectedMonster`.
- Subtask 2 (comandos): `view` por nome de ficha; remocao dos helpers antigos
  (`findBoundMobByName`, `suggestBoundMobs`) e do caminho de ambiguidade.
- Subtask 3 (Bug D): re-arma da camera no primeiro tick de sessao nova +
  sanitizacao do `selfHealCameraMobs` (rescan sempre, com regra de duas
  categorias).

## What Changed
1. `CombatController.moveSelectedMonster`: quando o destino clicado e uma
   escada (0 colisao interna da propria escada), a lamina de 2px da escada
   (eixo fino da VoxelShape do bloco) e excluida do AABB de teste da coluna.
   O mob pode sobrepor a escada (decisao ja aceita em 05/10/2026); teto,
   cerca, bau e placa na coluna continuam recusando.
2. `MasterCommands.openMobSheetByName`: resolve `ThreatSheetStore.find(uuid,
   normalizeKey(nome))` e envia `sendThreatSheetOpen` direto. A ficha nao
   precisa estar amarrada a um mob. Sugestao de tab vira `suggestSheetNames`.
3. `SpectatorCameraController.tick`: a guarda de troca de entidade passou de
   `lastPlayerEntityId != 0 && id != last` para `lastPlayerEntityId == 0 ||
   id != last`. `onPlayerEntityChanged` (limpa targets + envia
   SpectatorTargetsRequestPayload) roda no primeiro tick de cada sessao nova.
4. `CombatController.selfHealCameraMobs`: removeu o early-return quando o Set
   nao esta vazio; varre sempre ao montar a lista. So adiciona a
   `insertedMobs` (lookAt) quando o mob TAMBEM tem a tag `tabletoprpg_inserted`
   (peca de `insertEnemy` com cam_perm=true); mob do camera tool (IA viva) fica
   so no carrossel.

## Files Changed
- `src/main/java/com/pedro/tabletoprpg/CombatController.java`
- `src/main/java/com/pedro/tabletoprpg/MasterCommands.java`
- `src/client/java/com/pedro/tabletoprpg/client/SpectatorCameraController.java`

## Decisions
- Bug F: manter a protecao da coluna (teto/cerca/bau/placa recusam) e excluir
  so a lamina da propria escada clicada -- nao a checagem inteira.
- Comandos: `view` abre a ficha pelo nome unico, sem iterar mobs; sem mob
  amarrado a ficha abre do mesmo jeito (a amarra nunca foi requisito).
- Bug D: reusar o mecanismo provado de 05/10 (pedido C2S da lista) em vez de
  criar caminho novo. O push do JOIN pode chegar duplicado e e inofensivo
  (`setServerTargets` substitui).
- `selfHealCameraMobs`: rescan sempre fecha a lacuna de chunk carregado
  tarde; a regra de duas categorias preserva a distincao peca-vs-IA-viva que o
  camera tool deixa explicita.

## Validation
- `gradlew build --console=plain -q` via `cmd /c`: EXIT 0 (compile + testes) em
  4 rodadas (apos cada grupo de edicoes).
- Scan de codepoints: todas as linhas novas em ASCII; nada alem dos `§` de cor
  e acentos pre-existentes.
- Reviewer (subagente) aprovou o codigo para os 3 objetivos; apontou 1 ponto
  de decisao (efeito colateral do rescan no insertedMobs) -- corrigido com a
  regra de duas categorias e revalidado no build.
- NAO validado em jogo (exige o cliente): fixacao do mob largo na escada
  (ficar fixo, sem deslizar), carrossel apos sair e voltar, comandos view/give
  por nome de ficha.

## Problems Encountered
- Meus comentarios novos entraram com acentos na primeira passada (violacao da
  disciplina ASCII) -- corrigidos byte a byte antes do build final.
- O find no javap no jar clientonly nao achou `LadderBlock` (classe esta no
  jar minecraft-common); a analise da forma da escada foi feita por logica da
  VoxelShape via `getCollisionShape` no proprio codigo, sem depender do javap.

## Root Causes
- BUG F (FATO): o mob largo tem caixa que cobre a coluna clicada inteira; a
  lamina de colisao da escada (2px encostada na parede) fica DENTRO dessa
  coluna e o `level.noCollision` recusava. Mob de 1 bloco nao encosta na lamina
  (ela fica fora da caixa dele) -- por isso "ainda dava o mesmo erro" so para
  os largos.
- BUG D (FATO): `reset()` zera `lastPlayerEntityId` na desconexao; a guarda
  antiga (`!= 0`) nunca disparava no tick da sessao nova, e o unico caminho de
  recuperacao era o push do JOIN, que o receiver descarta enquanto o
  `LocalPlayer` nao existe (TabletopRpgClient:612). Carrossel ficava com a
  lista velha (ids que nao resolvem) e os mobs sumiam.
- COMANDOS (FATO): `view` resolvia pelo nome de EXIBICAO do mob amarrado
  (`findBoundMobByName` + `@e[name=...]`), que pode repetir; o nome de ficha e
  unico no store (save recusa duplicado).
- Latente (FATO): `selfHealCameraMobs` com early-return perdia mobs com camera
  em chunk carregado depois do primeiro scan.

## Fixes
- Bug F: exclusao da lamina da escada do AABB de teste (eixo fino, lado
  correto via min < 0.5).
- Bug D: guarda do tick trata "0 -> id novo" como troca de entidade; re-arma
  no primeiro tick da sessao. `selfHealCameraMobs` varre sempre.
- Comandos: view por nome de ficha (sem ambiguidade possivel); helpers mortos
  removidos.

## Remaining Issues
- Validacao em jogo pendente (lista no prologo):
  1. Bug F: mob largo em escada fixa sem deslizar; mob de 1 bloco continua
     funcionando; obstaculo real na coluna continua recusando.
  2. Bug D: entrar em modo, espectar mob com camera, sair, voltar -> mob no
     carrossel e ciclo funcionando; camera tool desligado -> mob sai e NAO
     volta (rescan respeita a remocao da tag); `/rpg remove enemy` -> some e
     permanece sumido.
  3. Comandos: view/give pelo nome da ficha; sugerir nomes de ficha no tab.

## Lessons / Memory
- Reconexao e o caso que o fix de respawn (05/10) nao cobre: `reset()` zera o
  id e a guarda `!= 0` nunca dispara; tratar 0 -> id novo como troca de
  entidade fecha a lacuna. Gravado em project-memory.md.
- Tags do mod tem duas categorias: `tabletoprpg_inserted` (peca congelada,
  lookAt) e `tabletoprpg_camera` (visibilidade no carrossel). `insertEnemy`
  cam_perm=true grava as duas; o camera tool grava so a segunda (IA viva).
  Qualquer rescan/heal deve respeitar isso. Gravado em project-memory.md.

## Next Steps
1. Jogadora valida em jogo os tres itens (build atual da arvore).
2. Apos aval, `git add` dos arquivos + commit ASCII (mensagem via arquivo em
   %TEMP%) + tag `checkpoint-20261008-feedback-batch3`.
3. Se algo falhar em jogo: voltar a este checkpoint (arvore atual) e corrigir
   o item isolado.

---

# Rodada 2 (08/10/2026): Bug D - diagnostico aprofundado e fix de reconexao

## Status
Build verde (`BUILD SUCCESSFUL in 12s`), scan ASCII limpo (0 linhas novas fora
de ASCII). Aguardando o repro instrumentado da jogadora e autorizacao de commit.

## Objective
A jogadora informou: a correcao do lote 1 (escada + comandos + carrossel) foi
validada ("perfeito" nos dois primeiros), MAS o Bug D persiste -- depois de sair
do modo no meio da investigacao/combate e voltar, os mobs com camera continuam
sumidos do carrossel ate trocar de modo. Ela pediu diagnostico completo, o plano
de correcao e as possibilidades; e autorizou corrigir (dedicated server).

## Diagnostico (evidencia coletada por traca completa do codigo)
1. FATO: o fix do lote 1 trata o CLIENTE (guarda `lastPlayerEntityId == 0`
   re-pede a lista no primeiro tick). Isso garante que o pedido sai -- mas nao
   garante que a RESPOSTA venha cheia.
2. FATO: `getSpectatorTargets` (servidor) resolve cada UUID via
   `level.getEntity(uuid)`. No JOIN, os mobs da mesa ainda estao carregando
   (chunks/NBT assincronos) -> a resposta sai VAZIA. Nada mais re-envia a lista
   depois; so a troca de modo (que refaz o envio) corrige. Isso explica
   exatamente o sintoma "so volta ao trocar de modo".
3. FATO: nao existe nenhum mecanismo de esconder entidades no cliente
   (mixins de render so adicionam; JOIN/DISCONNECT limpam estado). Os mobs
   "sumindo" e a lista vazia sao o mesmo problema: lista sem alvos resolviveis.
4. CONTRADICAO EM ABERTO: a jogadora usa comandos que exigem master
   (`verifyMasterPermission`), mas o carrossel so roda com `locked=true`, e
   `canPlayerAct(master)` e sempre true (master nunca leva lock). Se ela e a
   master, deveria receber `locked=false` e o carrossel deveria estar bloqueado.
   O novo log de `sendLockToPlayer` (locked/modo/mestre) resolve isso com
   evidencia, nao com suposicao.

## What Changed (rodada 2)
1. `RpgNetworking.registerServerReceivers`: hook em
   `net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD`
   -- quando um `Mob` com a tag `tabletoprpg_camera` carrega no mundo, o
   servidor monta e reenvia `sendSpectatorTargetsToAll` para todos. A lista se
   autocorrige no momento em que as entidades existem, independente de JOIN ou
   de re pedido do cliente. (O import `fabric.api.entity.event.v1.ServerEntityEvents`
   nao existe na Fabric API 0.141.6; a classe certa e `fabric.api.event.lifecycle.v1`
   no modulo fabric-lifecycle-events-v1.)
2. Instrumentacao de diagnostico (1 repro instrumentado):
   - Servidor: log em `sendLockToPlayer` (locked, modo, mestre);
     `logSpectatorTargets` em `sendSpectatorTargetsToPlayer/All` (destino,
     tamanho bruto, quantos mobs); log no ENTITY_LOAD (uuid, quantos alvos).
   - Cliente: log no receiver de PlayerLockPayload (estado aceito) e de
     SpectatorTargetsPayload (tamanho recebido); logs em
     `onPlayerEntityChanged` (re-pedido) e em `cycleNext`/`cyclePrev` quando a
     lista esta vazia (prova que o clique chegou e a lista estava vazia).

## Files Changed (rodada 2)
- `src/main/java/com/pedro/tabletoprpg/RpgNetworking.java` (+42)
- `src/client/java/com/pedro/tabletoprpg/client/TabletopRpgClient.java` (+9)
- (SpectatorCameraController.java: +3 linhas de log nesta rodada, dentro do
  diff de +40 ja existente do lote 1)

## Decisions (rodada 2)
- Fix por ENTITY_LOAD em vez de re-broadcast periodico no END_SERVER_TICK:
  o evento e exato (dispara quando a entidade ja esta no mundo), nao gera
  tick inutil com servidor ocioso, e cobre o caso real (chunk recarrega no
  JOIN). Custo: uma iteracao de tag por entidade carregada, mesma coisa que
  `selfHealCameraMobs` ja faz todo tick.
- Instrumentacao mantida no commit pretendido: os logs sao de nivel INFO,
  volume baixo (uma linha por envio/recebimento) e sao a unica forma de fechar
  a contradicao master x locked sem suposicao.
- Nao adicionar re-pedido periodico no cliente ainda: o ENTITY_LOAD ja fecha
  o caminho servidor->cliente; se o repro mostrar lacuna restante, o log
  aponta exatamente onde.

## Validation (rodada 2)
- `gradlew build` via `cmd /c` com output em arquivo: BUILD SUCCESSFUL in 12s
  (a 1a tentativa falhou por import errado de ServerEntityEvents; corrigido e
  reconstruido).
- Script de codepoints (`%TEMP%\opencode\ascii_scan.ps1`, git diff -U0 das 3
  linhas de arquivo): `non-ascii-added-lines=0`.
- `git diff --stat`: escopo exato -- 4 arquivos do lote 1 + RpgNetworking +
  TabletopRpgClient; nenhum arquivo extra. (O diff gigante de
  `project-memory.md` e normalizacao de final de linha pre-existente no
  baseline, nao desta rodada.)
- NAO validado em jogo: comportamento em tempo real (repro abaixo).

## Problems Encountered (rodada 2)
- Import errado: `fabric.api.entity.event.v1.ServerEntityEvents` nao existe na
  Fabric API 0.141.6 (o modulo fabric-entity-events-v1 3.1.1 tem
  ServerLivingEntityEvents/ServerPlayerEvents/... mas nao ServerEntityEvents).
  A classe certa esta no modulo fabric-lifecycle-events-v1 2.6.15:
  `fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD`, assinatura
  `(Entity, ServerWorld)`. Verificado no sources jar antes de corrigir.

## Repro instrumentado pedido a jogadora
1. Entrar no modo (investigacao ou combate), espectar um mob com camera.
2. Sair do modo e desconectar NO MEIO da sessao (metade da mesa ainda misterio).
3. Reconectar e tentar ciclar o carrossel (1 clique para notar se responde).
4. Esperar ~30s sem fazer nada e anotar se os mobs aparecem sozinhos.
5. Trocar de modo e confirmar que voltam.
6. Enviar os logs do SERVIDOR e do CLIENTE desse intervalo.

## Remaining Issues (rodada 2)
- Validar em jogo o fix (mobs voltam ao carrossel apos reconexao, sem trocar
  de modo) e coletar os logs do repro.
- Resolver com a evidencia dos logs a contradicao master x locked (master
  deveria ter locked=false; se os logs mostrarem locked=false para a master,
  a jogadora nao deveria conseguir clicar no carrossel -- entender o fluxo
  real que ela usa antes de considerar o Bug D fechado).

## Lessons / Memory
- `ServerEntityEvents.ENTITY_LOAD` vive em
  `net.fabricmc.fabric.api.event.lifecycle.v1` (modulo lifecycle), NAO em
  `fabric.api.entity.event.v1` (modulo entity-events 3.1.1 nao tem essa
  classe). Gravado em project-memory.md.
- Responder a lista so no JOIN e fragil: entidades carregam depois da resposta.
  Evento de carga de entidade e o ponto certo para re-broadcast.

---

# Rodada 3 (08/10/2026): logs do repro -- fix de lista validado, nova fronteira

## Status
Build verde, scan ASCII limpo. Nova instrumentacao no cliente pronta; aguardando
1 repro da jogadora com o build atual.

## O que os logs dela provaram (FATO)
1. **O fix de ENTITY_LOAD funcionou.** No rejoin 11:45:07 o servidor logou os 5
   mobs carregando e reenviou a lista; o cliente RECEBEU "6 alvos (brutos)".
   A teoria "lista vazia no JOIN" esta morta -- a lista chega completa.
2. **O fluxo de lock dela esta claro** (contradicao master x locked resolvida):
   ela claim master -> troca modo -> step down -> locked=true -> carrossel.
   O estado quebrado e o rejoin JA estando locked=true; o estado funcional e o
   step down posterior. Enquanto master (locked=false) o carrossel fica bloqueado
   de proposito.
3. **Distancia dos mobs: 4 a 17 blocos do corpo dela** (extraido direto do save
   `run/world/entities/r.-2.0.mca` com parser NBT proprio, comparando as 5 UUIDs
   do log com o login pos -687.8/212.4). Teoria "fora do alcance de tracking"
   (H1) morta: a 16 blocos o servidor com certeza envia as entidades.
4. **Nenhum mecanismo de ocultacao de entidades existe no mod** (grep
   setInvisible/setSharedFlag/mixins de render: EntityRendererMixin so muda
   brilho de hover; CameraMixin so aplica a rig; GuiMixin esconde HUD).

## Contradicao restante (por que instrumentamos de novo)
A traca estatica diz que o carrossel DEVERIA funcionar no rejoin (lista certa,
ids certos, portao `locked=true` passa, entidades a 16 blocos), mas o sintoma
persiste. So o runtime decide. Ponto cego corrigido: cliques com sucesso eram
SILENCIOSOS (log so existia no caminho vazio).

## What Changed (rodada 3, so SpectatorCameraController.java)
1. `logCycle`: TODO clique do carrossel agora loga indice/tamanho/alvoId e se a
   entidade resolve no cliente (`resolvido=<tipo> pos=(x,y,z)` ou `NULO`).
2. Heartbeat a cada 5s dentro do `tick` (com `client.level != null`):
   `idx, alvos resolviveis=N/5, locked, active, modo` -- mostra se as entidades
   aparecem (ou nao) no cliente ao longo do tempo, inclusive durante o 1min
   parada.
3. Log na ativacao da camera: modo e indice no momento em que `active` liga.

## Validacao (rodada 3)
- `gradlew build`: BUILD SUCCESSFUL in 7s.
- Scan de codepoints das linhas adicionadas: 0 fora de ASCII.

## Repro pedido (build atual)
1. Entrar no modo, espectar, sair do modo, desconectar (mesmo fluxo de antes).
2. Reconectar; esperar ~10s parada; clicar esquerda 3x devagar; esperar mais
   10s; enviar o log do cliente.
3. Depois: claim master -> step down (SEM trocar modo); clicar 2x; enviar de
   novo.

## Como ler os logs novos (decisao por evidencia)
- `Clique carrossel ... NULO` ou `resolviveis=0/5` -> entidades nao existem no
  cliente apesar da distancia de 16 blocos (fronteira: pacotes de spawn /
  conflito com outro mod).
- Clique com `resolvido=...` OK e camera nao mexe -> bug de estado da camera
  (rig/transition/active), nao de entidades.
- Sem nenhuma linha `Clique carrossel` -> os cliques nao chegam ao mixin
  (caminho de input/gate) -- fronteira nova.

## Lessons / Memory (rodada 3)
- Logs de sucesso importam tanto quanto os de erro: o log so no caminho vazio
  deixou o caso "clicou, lista cheia, entidade nao resolve" invisivel.
- Posicao de entidades do mundo rodando pode ser lida do save com parser NBT
  proprio (region header + zlib + NBT stdlib python) quando o log nao tem a
  coordenada -- foi o que matou a teoria de tracking por distancia.

# Rodada 4 (16/10/2026): o elo que falta -- prova de envio de spawn (servidor x cliente)

## Status
**DIAGNOSTICO FECHADO no mesmo dia** (logs da sessao 16/10 22:55). Causa raiz do
bug D **PROVADA por evidencia**: o servidor nao envia o spawn dos mobs de camera
enquanto o jogador esta preso (locked). **FIX NAO IMPLEMENTADO** -- adiado a
pedido da jogadora ("deixe este bug para depois"); esta registrado aqui com o
caminho exato e os candidatos de correcao. A instrumentacao da rodada 4
(`ServerEntityMixin`, `ClientPacketListenerMixin`) foi **REMOVIDA em 17/10/2026**,
a pedido da jogadora -- ver a secao "Remocao da instrumentacao" no fim deste
relatorio. O codigo dela fica preservado no commit `a7b3b38`.
Build verde (`BUILD SUCCESSFUL in 41s`); ASCII limpo. Nada commitado (HEAD 3f3a025).

## O que a rodada 4 mudou de entendimento (FATO -- fontes vanilla lidas)
1. Gerei os sources do Minecraft (`gradlew genSources`) e li o caminho real de
   envio de spawn:
   - `ChunkMap.move(ServerPlayer)` (ChunkMap.java:1070) percorre TODAS as
     entidades rastreadas e chama `TrackedEntity.updatePlayer(player)`.
   - `updatePlayer` (1391) decide por 3 condicoes (distancia horizontal,
     `broadcastToPlayer` = true por padrao, `isChunkTracked`) e, se passar, faz
     `seenBy.add(...)` + `ServerEntity.addPairing(...)`, que envia o bundle com
     `ClientboundAddEntityPacket`.
   - `ChunkMap.move` e chamado de `handleMovePlayer` (linha 1132) -- ou seja, SO
     um pacote de movimento processado avalia o tracking dos outros.
2. O mixin `ServerGamePacketListenerImplMixin` cancela `handleMovePlayer` no HEAD
   quando `!canPlayerAct` (jogador preso). Logo: preso = nenhum `move()` =
   nenhuma reavaliacao de tracking. Ao virar mestre (destrava), o primeiro pacote
   de movimento dela dispara `move()` e os spawns saem -- e exatamente o `5/5`
   no mesmo segundo do claim (14:17:32).

## O que o log do servidor provou (FATO)
O log do servidor ja estava em disco: `run/logs/2026-10-08-3.log.gz` (sessao
11:43 -> 14:17:32):
- Rejoin 14:16:42: os 5 mobs recarregam (ENTITY_LOAD, tag tabletoprpg_camera)
  ANTES do login; posicao dela correta (-674.9, 67, 209.1) entre os mobs;
  `sendLockToPlayer locked=true`; lista 5 -> 6 alvos resolvida.
- Zero WARN/ERROR/Exception na sessao inteira.
- Fabric dispara `ServerEntityEvents.ENTITY_LOAD` no TAIL de `onTrackingStart`
  (verificado no codigo do fabric-lifecycle-events-v1) -- depois do
  `ChunkMap.addEntity` (entityMap.put). Os mobs ja estavam no entityMap antes do
  jogador ser adicionado.

## A contradicao e a inferencia
- A leitura estatica de `ChunkMap.addEntity(player)` diz que o loop sobre o
  entityMap (linhas 1160-1167) DEVERIA enviar os 5 spawns no join (mobs no mapa,
  distancia 2-17, mesmo chunk, view >= 2).
- Mas o cliente ficou `0/5` de 14:16:47 a 14:17:27 e virou `5/5` em 14:17:32, sem
  novo payload de lista (FATO).
- INFERENCIA (alta confianca): se o join tivesse emparelhado (`seenBy` com ela),
  nao haveria reenvio no claim; como o cliente so recebeu no claim, o join NAO
  emparelhou. "Cliente descartar o bundle" nao explicaria o reaparecimento (o
  servidor nao reenviaria). Ou seja: o elo que falha esta no SERVIDOR, entre
  `ChunkMap.addEntity(player)` e o envio do bundle.
- O que ainda nao sei: QUAL condicao falhou (ou se o loop sequer rodou). Por isso
  a rodada 4 e instrumentacao pura, nao fix.

## What Changed (rodada 4: 2 arquivos novos + 2 configs)
1. `src/main/java/com/pedro/tabletoprpg/mixin/ServerEntityMixin.java` (novo):
   injeta em `ServerEntity.addPairing`/`removePairing` (HEAD) e loga, so para
   mobs com tag `tabletoprpg_camera`:
   `DIAG spawn ENVIADO/REMOVIDO: jogador=... mob=... id=... pos=(...)`.
2. `src/client/java/com/pedro/tabletoprpg/client/mixin/ClientPacketListenerMixin.java`
   (novo): injeta em `handleAddEntity` (spawn de creeper/iron_golem com id e pos)
   e `handleRemoveEntities` (ids removidos).
3. `tabletop-rpg.mixins.json` (array `server`) e
   `tabletop-rpg.client.mixins.json` (array `client`): registram os mixins.

## Como ler os logs da rodada 4 (decisao por evidencia)
- Servidor SEM `DIAG spawn ENVIADO` no rejoin -> o loop do join nao enviou;
  proxima rodada instrumenta as 3 condicoes de `updatePlayer`. Candidato a fix:
  ao destravar (ou sempre que um jogador preso estiver online), forcar
  `level.getChunkSource().move(player)`.
- Servidor COM `DIAG spawn ENVIADO` no rejoin e cliente SEM `DIAG spawn RECEBIDO`
  -> pacote perdido/nao processado; fronteira passa para rede/cliente.
- Cliente com `RECEBIDO` seguido de `remocao RECEBIDA` -> algo remove as
  entidades; investigar quem manda a remocao e por que.
- Em qualquer caso, o fix definitivo deve garantir que o envio nao dependa do
  primeiro pacote de movimento do jogador preso.

## RESULTADO DO REPRO (16/10/2026, sessao 22:55) -- causa raiz PROVADA

Fontes: `run/logs/latest.log` (servidor) + `logs/latest.log` do cliente
(`%APPDATA%\.minecraft\logs`). Mesma sessao, relogio correlacionado.

| Hora | Servidor | Cliente |
|---|---|---|
| 22:55:17 | login `entity id 66`; `sendLock locked=false modo=FREE`; alvos 5 -> 6 | lista 5 e 6 alvos recebida |
| 22:55:19 | **DIAG spawn ENVIADO x5** (ids 36, 37, 1, 39, 38) | -- |
| 22:55:20 | -- | **DIAG spawn RECEBIDO** nos mesmos 5 ids |
| 22:55:23 | -- | heartbeat `resolviveis=5/5` (funciona) |
| 22:55:40 | **DIAG spawn REMOVIDO x5** (desconexao dela) | -- |
| 22:55:46 | login `entity id 215`; `sendLock locked=TRUE modo=INVESTIGATION`; alvos 5 -> 6; **NENHUM DIAG spawn ENVIADO** | camera ativa `idx=0/1`; os spawns recebidos sao de OUTROS creepers do mundo, nao dos alvos |
| 22:55:49-59 | (nada) | heartbeat `resolviveis=0/5`; cliques `alvoId=204/205/206/203/170 NULO` |
| 22:56:23 | `sendLock locked=false mestre=true` + **DIAG spawn ENVIADO x5** (206, 204, 203, 205, 170) NO MESMO SEGUNDO | -- |
| 22:56:24 | -- | heartbeat `resolviveis=5/5` |

### Causa raiz (FATO: fonte vanilla + os dois logs)
`ChunkMap.TrackedEntity.updatePlayer` (ChunkMap.java:1391-1401) so envia o spawn se
as TRES condicoes passarem:

```java
boolean bl = e <= f                                       // distancia
    && this.entity.broadcastToPlayer(serverPlayer)        // default true
    && ChunkMap.this.isChunkTracked(serverPlayer, chunkX, chunkZ);
```

E `isChunkTracked` (ChunkMap.java:230-232) tem UMA condicao que nao era conhecida:

```java
public boolean isChunkTracked(ServerPlayer p, int i, int j) {
    return p.getChunkTrackingView().contains(i, j)
        && !p.connection.chunkSender.isPending(ChunkPos.asLong(i, j));   // <<< ESTA
}
```

No JOIN, `updatePlayerStatus(player, true)` (1042) faz
`setChunkTrackingView(EMPTY)` (1052) e `updateChunkTracking(player)` (1053), que
marca **TODOS** os chunks da vista como pendentes de envio
(`markChunkPendingToSend`, 1127). Logo, no instante do join, os chunks dos mobs
de camera estao PENDENTES -> `isChunkTracked` = FALSE -> o loop de join
(1163-1167) chama `updatePlayer` e NAO envia nada.

Quando o cliente confirma os chunks, eles deixam de estar pendentes -- mas o
UNICO gatilho que reavalia e `ChunkMap.move(player)` (1070), chamado por
`handleMovePlayer` (pacote de movimento). O `ServerGamePacketListenerImplMixin`
cancela esses pacotes enquanto `!canPlayerAct` (jogador preso). Resultado: presa
= nenhum `move()` = nenhum spawn. Ao destravar (claim master), o 1o pacote de
movimento processado roda `move()` e envia os 5 no mesmo segundo.

Isso explica o join DES TRAVADO tambem: os spawns de 22:55:19 saem ~2s depois do
login, junto com o 1o pacote de movimento -- nao no join em si.

### O que ja foi tentado antes e NAO resolveu (para nao repetir)
1. **Rodada 1 - guarda de cliente** (`lastPlayerEntityId == 0` -> re-pede a
   lista no 1o tick): corrigiu o PEDIDO da lista; nao trouxe as entidades.
2. **Rodada 1/2 - re-broadcast da lista no ENTITY_LOAD**: a LISTA passou a chegar
   completa (5 -> 6 alvos, validado em jogo); as entidades continuaram ausentes.
   => Estava-se tratando o sintoma "lista", nao a causa "spawn".
3. Hipoteses descartadas COM evidencia:
   - "lista vazia no JOIN" -- ERRADA (a lista chega inteira).
   - "bug D e cliente-side" -- ERRADA (o servidor nunca envia enquanto locked).
   - "tracking por distancia / view-distance" -- ERRADA (mobs a 2-20 blocos,
     entidades em 1-2 chunks, `getPlayerViewDistance` clampa min 2).
   - "mecanismo de ocultacao/remocao no cliente" -- ERRADA (nao existe; as
     remocoes do log sao do DESCONEXAO dela, e o cliente recebeu normalmente os
     outros creepers).
   - "teleporte pendente bloqueando o envio" -- descartada (sem suporte no log).
   - "o cliente descarta o bundle no join" -- impossivel: se o servidor tivesse
     emparelhado (`seenBy`), nao haveria REENVIO no unlock -- e houve.
4. **Rodada 3 - instrumentacao so no cliente**: mostrou `0/5 -> 5/5` (evidencia,
   nao fix).
5. **Rodada 4 - instrumentacao servidor+cliente**: fechou a causa (o servidor nao
   chama `addPairing` enquanto locked).

### Fix proposto (NAO implementado -- adiado a pedido da jogadora)
- **Candidato A (minimo, recomendado):** no ramo `!canPlayerAct` do
  `ServerGamePacketListenerImplMixin`, antes do `ci.cancel()`, chamar
  `player.level().getChunkSource().move(player)`. `ChunkMap.move` (1070-1104)
  apenas reavalia o tracking (`updatePlayer`); NAO move o corpo, NAO aceita a
  posicao e NAO teleporta. `updatePlayerPos`/`updateChunkTracking`/distanceManager
  so rodam se a SECAO mudou -- irrelevante para um corpo congelado.
- **Candidato B:** aplicar o mesmo no ramo do jogador caido, por simetria.
- **Candidato C (alternativa):** no `sendLockToPlayer(locked=true)`, forcar/agendar
  um `move` para aquele jogador. Menos preciso: nao cobre chunks que ficam
  prontos DEPOIS do lock (o caso real do rejoin).

- Historico (17/10/2026): o plano era manter a instrumentacao no build ate a
  jogadora validar em jogo; ela pediu para remove-la antes disso -- ver a secao
  "Remocao da instrumentacao (17/10/2026)" no fim deste relatorio.

### Instrumentacao deixada no build (reutilizavel)
> REMOVIDA em 17/10/2026 (a pedido da jogadora). O texto abaixo descreve o que
> existia; o codigo esta preservado no commit `a7b3b38`.
- `ServerEntityMixin`: `net.minecraft.server.level.ServerEntity#addPairing` /
  `removePairing` (HEAD), loga so mobs com tag `tabletoprpg_camera`.
  `DIAG spawn ENVIADO/REMOVIDO`.
- `ClientPacketListenerMixin`: `handleAddEntity` (creeper/iron_golem) e
  `handleRemoveEntities`. `DIAG spawn RECEBIDO` / `DIAG remocao RECEBIDA`.

## Validacao (rodada 4)
- `gradlew build`: BUILD SUCCESSFUL in 41s.
- Scan de codepoints dos 4 arquivos: 0 fora de ASCII.

## Repro executado (16/10) -- resultado na secao "RESULTADO DO REPRO"
1. Entrar travada em Investigacao ou Combate (fluxo de sempre).
2. Sair do servidor e voltar (rejoin ja locked).
3. Esperar ~10s; clicar esquerda 2-3x (tentar trocar camera).
4. Claim master; clicar 2x; enviar o log do cliente.
   (O log do servidor eu leio direto em `run/logs`.)

## Lessons / Memory (rodada 4)
- O log do servidor fica gravado em disco (`run/logs/*.log.gz`, rotacionado);
  ler antes de pedir log a jogadora.
- `gradlew genSources` gera os sources legiveis do Minecraft no cache do loom
  (`.gradle/loom-cache/...-sources.jar`) -- usar antes de teorizar mecanica.
- Nesta versao do Fabric API (lifecycle-events 2.6.15) NAO existe
  `ServerEntityTrackingEvents`; para prova de tracking por jogador, mixin em
  `net.minecraft.server.level.ServerEntity#addPairing`.
- 3 camadas de log separam "nao enviou" de "enviou e perdeu": addPairing
  (servidor) -> handleAddEntity (cliente, entrada do pacote) -> getEntity no tick.
- **`isChunkTracked` tambem exige `!chunkSender.isPending(chunkPos)`** (FATO,
  ChunkMap:230). No join TODOS os chunks da vista ficam pendentes -> nenhum
  `updatePlayer` envia. O unico gatilho posterior e `ChunkMap.move(player)` (via
  `handleMovePlayer`). Cancelar pacotes de movimento congela o tracking junto com
  o corpo -- essa e a causa raiz do bug D.
- Quando o sintoma e "a lista chega mas as entidades nao existem no cliente", o
  suspeito nao e a lista: e o ENVIO de spawn no servidor. Logar `addPairing` foi o
  que fechou o caso em 1 repro (as rodadas 1-3 trataram o sintoma).

---

# Remocao da instrumentacao (17/10/2026)

## O que foi feito
A pedido da jogadora ("pode commitar e remover essas coisas que imprime no log"),
a instrumentacao da rodada 4 foi **apagada** -- nao desabilitada:

1. `src/main/java/com/pedro/tabletoprpg/mixin/ServerEntityMixin.java` -- removido.
2. `src/client/java/com/pedro/tabletoprpg/client/mixin/ClientPacketListenerMixin.java`
   -- removido.
3. `src/main/resources/tabletop-rpg.mixins.json`: `"ServerEntityMixin"` removido do
   array `server` (e a virgula da linha anterior ajustada).
4. `src/client/resources/tabletop-rpg.client.mixins.json`:
   `"ClientPacketListenerMixin"` removido do array `client` (idem).

Nada mais foi tocado: nao existe outra instrumentacao do bug D no codigo (busca por
`DIAG` em `src/` nao retorna nada depois da remocao). Os `LOGGER.info` de
`SpectatorCameraController` e de `RpgNetworking` sao operacionais, das rodadas 1-3
(parte dos fixes validados), e foram mantidos.

## Por que remover as 2 entradas de config junto com as 2 classes
`required: true` em ambos os `mixins.json`: uma classe listada e inexistente faz o
carregamento do mixin FALHAR (crash na inicializacao), nao um aviso. A remocao tem
que ser atomica: classe + entrada.

## Validacao
- `gradlew build`: `BUILD SUCCESSFUL in 15s`, 0 `error:`, gate de encoding
  `192 arquivo(s) ... 0 mojibake, 0 ideograma, 0 U+FFFD` (era 194: caem os 2 arquivos
  removidos).
- Artefato provado por leitura direta do jar (`build/libs/tabletop-rpg-1.0.0.jar`):
  - os dois `mixins.json` dentro do jar nao citam mais `ServerEntityMixin` nem
    `ClientPacketListenerMixin`;
  - nenhuma classe `*ServerEntityMixin*` / `*ClientPacketListenerMixin*` no jar;
  - **toda** classe listada nos dois configs existe no jar (18/18) -- prova de que
    nao sobrou entrada orfa apontando para classe inexistente;
  - `SheetHighlightManager(.class, $Sent.class)` continua presente.
- Os dois `.json` foram reparseados com `json.load` (sintaxe valida) e tem 0
  caractere nao-ASCII.

## Como restaurar (se o bug D voltar a precisar dela)
```
git show a7b3b38:src/main/java/com/pedro/tabletoprpg/mixin/ServerEntityMixin.java
git show a7b3b38:src/client/java/com/pedro/tabletoprpg/client/mixin/ClientPacketListenerMixin.java
git show a7b3b38:src/main/resources/tabletop-rpg.mixins.json
git show a7b3b38:src/client/resources/tabletop-rpg.client.mixins.json
```
A causa raiz ja esta provada, entao o fix candidato nao depende dela; ela serve
apenas para uma nova rodada de investigacao.

## Riscos remanescentes
- Esta remocao foi validada por build + inspecao do artefato, **nao executada em
  jogo**: o proximo boot do cliente/servidor pela jogadora e que confirma na pratica
  que nada quebrou. O risco e baixo (nao ha codigo novo, apenas remocao, com os
  configs coerentes com o conteudo do jar).
- A rotina de log da rodada 4 deixa de existir; um novo repro do bug D nao tera
  `DIAG spawn ENVIADO` no servidor.
