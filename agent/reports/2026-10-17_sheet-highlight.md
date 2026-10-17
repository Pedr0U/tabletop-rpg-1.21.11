# Implementation Report

## Status
CONCLUIDO E VALIDADO EM JOGO (17/10/2026). Build verde, gate de encoding verde,
classe presente no jar, e a jogadora confirmou o comportamento em jogo
("esta funcionando bem"). Incluido no commit `a7b3b38`, junto com o lote de
08/10/2026 (escada / camera / comandos / diagnostico do bug D), que esteve
parado sem commit; push fast-forward para `origin/main` feito em 17/10/2026
(`2268338..a7b3b38`). Bug D (camera espectador no rejoin) permanece NAO
corrigido -- adiado por decisao da jogadora; a causa raiz esta provada no
relatorio de 08/10.

## Objective
Subtask pendente do Bug C: enquanto o mestre segura o item `tabletop-rpg:threat_sheet`
cujo id de ficha esta VINCULADO a um mob, esse mob deve receber contorno (glow)
somente para o mestre.

Decisoes visuais fechadas com a jogadora antes de implementar:
1. vale com a ficha em QUALQUER mao (principal ou secundaria);
2. o contorno deve ser visivel ATRAVES DE PAREDES e sem limite de distancia
   imposto pelo mod;
3. enquanto segura a ficha, o highlight de MIRA (crosshair hover) deve SUMIR --
   o novo substitui o antigo;
4. so o mestre.

## Scope / Subtasks
- Subtask 1: payload S2C novo + registro.
- Subtask 2: gerenciador server-side que decide e envia a lista de ids.
- Subtask 3: cliente -- campo, receptor, limpeza e desenho do contorno.
- Subtask 4: supressao do hover de mira enquanto a ficha esta na mao.

## What Changed
1. `RpgNetworking`: record `SheetHighlightPayload(List<Integer> entityIds)`
   (CustomPacketPayload, S2C, id `sheet_highlight`), com
   `ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list())`; registrado no fim de
   `registerPayloads()`.
2. `SheetHighlightManager` (NOVO): `register()` liga
   `ServerTickEvents.END_SERVER_TICK` e `ServerPlayConnectionEvents.DISCONNECT`.
   A cada tick, para cada jogador, calcula a lista desejada e envia o payload
   SO quando muda. Regras:
   - so o mestre recebe lista; qualquer outro recebe lista vazia (nada aparece);
   - assinatura da mao = `threatSheetId(principal) + "|" + threatSheetId(secundaria)`
     (`""` = nenhuma mao com ficha); stack mudar de contagem/dano NAO dispara recalculo;
   - vinculo resolvido por `ModItems.threatSheetId` -> `ThreatSheetBinding.mobOf`
     -> entidade no MESMO mundo do jogador (o cliente so conhece o proprio nivel);
   - recalculo pontual quando a mao muda ou o posto de Mestre muda, e um refresh
     periodico (1s) para pegar vinculo criado/mob morto depois;
   - `sendHighlight` loga no log do servidor a cada mudanca
     (`[TabletopRPG] SheetHighlight para <nome>: N entidade(s) [ids]`) -- e a
     unica forma de provar o envio sem um segundo cliente;
   - estado removido no DISCONNECT.
3. `TabletopRpg`: `SheetHighlightManager.register()` no `onInitialize`.
4. `TabletopRpgClient`: campo `volatile Set<Integer> sheetHighlightIds`,
   `isSheetHighlighted(int)`, `holdsThreatSheet(Player)`, receptor global do
   payload (dispatch no thread do cliente), limpeza no DISCONNECT, e um
   early-return novo dentro de `tickHover` que manda `HoverPayload(-1)` uma vez e
   para de mandar hover enquanto a ficha esta na mao.
5. `EntityRendererMixin`: o `@Redirect` de `Minecraft#shouldEntityAppearGlowing`
   passa a devolver `true` tambem para `isSheetHighlighted(entity.getId())` --
   mesmo mecanismo que ja desenhava o contorno do hover, logo o contorno
   atravessa paredes e nao depende de distancia de mira.

## Files Changed
- `src/main/java/com/pedro/tabletoprpg/RpgNetworking.java`
- `src/main/java/com/pedro/tabletoprpg/SheetHighlightManager.java` (novo)
- `src/main/java/com/pedro/tabletoprpg/TabletopRpg.java`
- `src/client/java/com/pedro/tabletoprpg/client/TabletopRpgClient.java`
- `src/client/java/com/pedro/tabletoprpg/client/mixin/EntityRendererMixin.java`
- `agent/state/2026-10-16_sheet-highlight.md` (novo)

## Decisions
- **Servidor decide, cliente desenha.** O vinculo ficha -> mob so existe no
  servidor; o cliente apenas obedece a lista. Isso tambem garante o "so o mestre":
  a lista so e montada para quem ocupa o posto.
- **Payload com LISTA, e nao um id unico:** a ficha pode estar nas duas maos e
  cada uma apontar para um mob diferente. Lista VAZIA = "nao destaque nada" e e o
  que limpa a tela quando a ficha e guardada.
- **Enviar so na mudanca**, com refresh de 1s: o payload e barato, mas enviar a
  20 Hz entupiria o canal com algo que o cliente ja sabe; e o recalculo involuntario
  e o unico custo relevante, porque `ThreatSheetBinding` pode chamar `selfHeal`
  (varredura de entidades). Refresh periodico cobre vinculo criado depois.
- **Supressao do hover e LOCAL** (checa a mao no cliente) -- decisao simples, sem
  acoplar o cliente ao estado de mestre do `BlockLockAura`. O caso "nao-mestre
  segurando a ficha" perderia o hover sem ganhar contorno, mas nao ocorre pelo
  mod: `ModItems.giveThreatSheet` entrega a ficha ao PROPRIO mestre
  (`MasterCommands.java:580`). Documentado no javadoc do gerenciador.
- Nao existe `todowrite` no catalogo desta sessao: o plano vive em
  `agent/state/2026-10-16_sheet-highlight.md`.

## Validation
Executado (FACT):
1. `gradlew build` -- BUILD SUCCESSFUL (7s na ultima rodada), 0 erros. O build
   roda tambem os testes existentes (`ThreatSheetStoreTest` etc.) e o
   `scanEncoding`.
2. ASCII: o gate do projeto reportou EXATAMENTE 9771 caracteres nao-ASCII antes e
   depois das edicoes -> o texto novo adicionou ZERO nao-ASCII; o arquivo novo tem
   0. `[scanEncoding] OK: 193 arquivo(s) ... 0 mojibake, 0 ideograma, 0 U+FFFD`.
3. Artefato: `build/libs/tabletop-rpg-1.0.0.jar` contem
   `com/pedro/tabletoprpg/SheetHighlightManager.class`,
   `com/pedro/tabletoprpg/SheetHighlightManager$Sent.class`,
   `com/pedro/tabletoprpg/RpgNetworking$SheetHighlightPayload.class` e
   `com/pedro/tabletoprpg/client/mixin/EntityRendererMixin.class` (conferido com
   zipfile; build verde sozinho nao prova que a classe entrou no jar).
4. EM JOGO (17/10/2026): validado pela jogadora -- "esta funcionando bem".

Nao validado: nao rodei cliente nem servidor nesta sessao. Comportamento em
runtime foi provado somente pelo teste em jogo da jogadora.

## Problems Encountered
1. Erro de compilacao real: `cannot find symbol: method serverLevel()` em
   `ServerPlayer`. Em 1.21.11 `ServerPlayer.level()` JA retorna `ServerLevel`
   (fonte vanilla `ServerPlayer.java:1708`); `serverLevel()` nao existe. Corrigido
   com `player.level().getEntity(uuid)`.
2. Achado da revisao (DEFEITO REAL): ganhar o posto de Mestre JA com a ficha na
   mao so aparecia no refresh seguinte (ate 1s de atraso). A deteccao de mudanca
   olhava apenas a assinatura da mao.
3. Achado da revisao: nenhuma evidencia no log do servidor -- a jogadora
   conseguiria validar so pelo log do cliente.
4. Diferenca de fim de linha no `project-memory.md` (worktree CRLF, HEAD LF,
   `autocrlf=true`): o diff cru parecia reescrever 4000 linhas.

## Root Causes
1. Eu assumi a API pelo nome mais comum em versoes anteriores; a fonte foi a
   autoridade.
2. A deteccao de mudanca precisa incluir TODA entrada que muda a resposta. A mao
   nao muda quando o posto muda, entao a resposta ficou velha.
3. Faltava o lado servidor da prova.
4. Normalizacao de fim de linha: as linhas que eu inseri ficaram LF dentro de um
   arquivo CRLF.

## Fixes
1. `player.level().getEntity(mobUuid)` (o `level()` do ServerPlayer ja e ServerLevel).
2. `Sent` passou a guardar `master`; a mudanca de posto entra no disparo de
   recalculo -> resposta imediata.
3. `sendHighlight` loga cada envio (so na mudanca).
4. Provado com `git diff --numstat --ignore-cr-at-eol`: 182 insercoes / 0 remocoes
   na memoria (so o append). O `git add` normaliza para LF, entao o commit NAO
   carrega ruido de fim de linha.

## Remaining Issues
- **Bug D (camera espectador no rejoin locked) NAO corrigido** -- adiado a pedido
  da jogadora. Causa raiz provada: `ChunkMap.isChunkTracked` exige
  `!chunkSender.isPending(chunkPos)`; no JOIN todos os chunks da vista ficam
  pendentes, entao o loop de join nao envia spawn nenhum, e o unico gatilho
  posterior e `ChunkMap.move(player)` via `handleMovePlayer`, que o mixin cancela
  enquanto ela esta presa. Candidato: chamar `player.level().getChunkSource().move(player)`
  antes do `ci.cancel()` no `ServerGamePacketListenerImplMixin`. Detalhes e o que
  ja falhou: `agent/reports/2026-10-08_feedback-escada-view-camera.md`.
- **Instrumentacao de diagnostico permanece no build** (rodada 4 do bug D):
  `ServerEntityMixin` e `ClientPacketListenerMixin` logam spawn/remocao de mobs de
  camera (servidor) e de creeper/iron_golem (cliente, `handleAddEntity` /
  `handleRemoveEntities`). Sao uteis para retomar o bug D, mas geram volume de log
  em jogo normal para qualquer jogador -- inclusive o amigo que trabalha no
  `pasta-Net`. Remover em commit separado quando o bug D for encerrado.
- **Limite fisico do contorno** (nao e bug): e desenhado no cliente, entao o mob
  precisa EXISTIR no cliente -- dentro do `clientTrackingRange` do vanilla
  (~128 blocos) -- e dentro do campo de visao/frustum. Atras das costas ou fora do
  render distance nao ha o que desenhar.
- Caso teorico de nao-mestre segurando a ficha (ver a secao Decisions).

## Lessons / Memory
Gravado em `agent/memory/project-memory.md` (secao "Highlight da ficha na mao"):
- o `EntityRendererMixin` (`@Redirect` em `shouldEntityAppearGlowing`) e o ponto
  unico de "quem brilha" no mod -- todo destaque per-player passa por ali;
- checklist para adicionar payload S2C no projeto;
- `ServerPlayer.level()` retorna `ServerLevel`; `serverLevel()` nao existe;
- `Entity.ENTITY_COUNTER` e `static final AtomicInteger` global (Entity.java:187)
  -> ids de entidade sao unicos no servidor inteiro, sem colisao entre mundos;
- a ficha so chega ao proprio mestre (`giveThreatSheet`);
- licao de revisao: cache de deteccao de mudanca deve listar TODAS as entradas que
  mudam a resposta;
- tecnica de prova de ASCII: comparar o contador do `scanEncoding` antes/depois;
- conferir o caminho de classe dentro do jar e barato e prova o artefato.

## Next Steps
1. Retomar o **bug D** com o fix de uma linha + build + um repro da jogadora
   (rejoin locked: esperado `DIAG spawn ENVIADO` no log do servidor no unlock).
2. Quando o bug D fechar, remover a instrumentacao de diagnostico (2 mixins + as
   duas entradas nos `mixins.json`) e commitar a parte.
3. Entrega feita: commit `a7b3b38` + push fast-forward para `origin/main`
   (`2268338..a7b3b38`, 17/10/2026). Nenhum conflito: o branch do outro
   desenvolvedor (`pasta-Net`, ultimo commit 24/09/2026) estava separado e sem
   nada novo.
4. Pendente de decisao: o relatorio de 08/10 previa a tag
   `checkpoint-20261008-feedback-batch3`; ela nao foi criada nem enviada.
