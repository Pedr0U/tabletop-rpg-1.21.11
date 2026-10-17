# Plano: highlight segurando o item de ficha (subtask do Bug C)

Data: 16/10/2026. Baseline: HEAD `3f3a025` (nada commitado).

## Regras de trabalho (da jogadora)
- NAO commitar sem autorizacao explicita; ela valida em jogo antes.
- Codigo e docs novos 100% ASCII.
- Perguntar em vez de adivinhar (ambiguidade visual = perguntar).

## Requisito (decisoes fechadas com a jogadora)
Enquanto a jogadora (mestre) segura o item `tabletop-rpg:threat_sheet` cujo id de
ficha esta VINCULADO a um mob:
- o mob vinculado recebe contorno (glow) SO para o mestre;
- vale com a ficha na mao principal OU na secundaria;
- contorno "sempre visivel": atravessa paredes e nao depende de distancia;
- enquanto segura a ficha, o highlight de MIRA (crosshair hover) some;
- so o mestre.

## Baseline / checkpoint (antes das edicoes)
- `git status --short`: 8 modificados + 3 untracked, NENHUM desta tarefa.
- Checkpoint local: `%TEMP%\opencode\sheet-highlight-tracked.patch` (git diff),
  `sheet-highlight-status.txt` (git status) e copias dos 3 untracked.
- Build verde antes de comecar.

## Implementado (16/10/2026)
- `RpgNetworking`: record `SheetHighlightPayload(List<Integer> entityIds)` (S2C,
  id `sheet_highlight`) + registro em `registerPayloads()`.
- `SheetHighlightManager` (NOVO): tick para o mestre, resolve as fichas das duas
  maos via `ModItems.threatSheetId` + `ThreatSheetBinding.mobOf` -> id da entidade
  -> envia `SheetHighlightPayload` SO quando muda; loga o envio no log do
  servidor; limpa o estado no DISCONNECT.
- `TabletopRpg`: `SheetHighlightManager.register()`.
- `TabletopRpgClient`: campo `sheetHighlightIds`, `isSheetHighlighted(int)`,
  `holdsThreatSheet(Player)`, receptor do payload, limpeza no DISCONNECT e o
  corte do hover dentro do `tickHover` quando a ficha esta na mao.
- `EntityRendererMixin`: o @Redirect do glow agora tambem devolve true para
  `TabletopRpgClient.isSheetHighlighted(entity.getId())`.

## Revisao (16/10) -- achados e tratamento
1. DEFEITO REAL (corrigido): ganhar o posto de Mestre JA com a ficha na mao so
   aparecia no proximo refresh (ate 1s). O posto passou a fazer parte do estado
   (`Sent.master`), entao a mudanca de posto recalcula na hora.
2. SEM LOG no servidor (corrigido): `sendHighlight` loga
   `[TabletopRPG] SheetHighlight para <nome>: N entidade(s) [ids]` a cada mudanca.
3. CAVEAT documentado: contorno "sem limite" vale dentro do
   `clientTrackingRange` do vanilla (~128 blocos) -- fora disso o mob nao existe
   no cliente e nao ha o que desenhar.
4. CASO TEORICO: um NAO-mestre segurando a ficha perde o hover de mira e nao
   ganha contorno. Nao ocorre pelo mod: `ModItems.giveThreatSheet` entrega a
   ficha ao PROPRIO mestre (MasterCommands:580). Fica documentado.
5. FACT verificado na fonte: `Entity.ENTITY_COUNTER` e um static final
   AtomicInteger (Entity.java:187) -> ids de entidade sao unicos no servidor
   inteiro; nao ha risco de o id antigo casar com outra entidade ao trocar de
   dimensao.

## Prova de ASCII (FACT)
O gate `scanEncoding` do projeto reportou EXATAMENTE 9771 caracteres nao-ASCII
antes e depois das edicoes -> o texto novo adicionou ZERO nao-ASCII. O arquivo
novo `SheetHighlightManager.java` tem 0 caracteres nao-ASCII.

## Prova do artefato (FACT)
`build/libs/tabletop-rpg-1.0.0.jar` contem:
`com/pedro/tabletoprpg/SheetHighlightManager.class`,
`com/pedro/tabletoprpg/SheetHighlightManager$Sent.class`,
`com/pedro/tabletoprpg/RpgNetworking$SheetHighlightPayload.class`,
`com/pedro/tabletoprpg/client/mixin/EntityRendererMixin.class`.

## VALIDADO EM JOGO (17/10/2026)
Resultado: a jogadora confirmou o comportamento em jogo ("esta funcionando bem").
Roteiro usado abaixo (fica registrado para regressao futura).
1. Mestre com ficha vinculada na mao -> mob contornado, mesmo atras de parede;
   log do cliente `SheetHighlight recebido: 1 entidade(s) -> [<id>]` e log do
   servidor `SheetHighlight para <nome>: 1 entidade(s) [<id>]`.
2. Passar a ficha para a mao secundaria -> contorno continua.
3. Mirar outro mob -> sem contorno de mira (o hover sumiu enquanto segura).
4. Guardar a ficha -> `0 entidade(s) -> []` nos dois logs e contorno apaga.
5. Outro jogador olhando o mesmo mob -> nada (sem contorno, sem log).

## Limitacao a avisar a jogadora (nao e bug)
O contorno e client-side: contorno visivel atraves de paredes SIM, mas o mob
precisa estar dentro do alcance de tracking do vanilla (~128 blocos) e dentro do
campo de visao -- atras das costas ou fora do render distance nao ha o que
desenhar. Fora do frustum o vanilla nao renderiza a entidade.
