# Implementation Report

## Status
**CONCLUÍDO COM RESSALVAS** — FASE 3b implementada e compilando (`BUILD SUCCESSFUL`).
Pendências abertas e decisões provisórias listadas em *Remaining Issues*.

## Objective
Reescrever a ficha do personagem (FASE 3) a partir de 7 pontos de feedback do usuário,
na mesma entrega, e implementar a regra de vida "HP <= 0 = deitado, não morre".

## Scope / Subtasks
| # | Subtask | Estado |
|---|---------|--------|
| 1 | Modelo: HP negativo com piso + `isDowned()`; Mana 0..max | OK |
| 2 | Servidor: cancelar morte do vanilla | **Não feito por decisão** (ver *Decisions*) |
| 3 | Servidor: estado deitado por tick (pose, sem movimento, levantar) | OK |
| 4 | Rede: `DownedStatePayload` + flag no cliente + mixins | OK |
| 5 | Cliente: `StatusScreen` com barras e setas, fundo escuro, responsivo | OK |
| 6 | Cliente: `SkillsScreen` separada | OK |
| 7 | Menu por papel (Status/Skills x Players) | OK |
| 8 | Corrigir edição dos campos | OK (ver *Problems Encountered*) |
| 9 | Build + revisão + este relatório | OK |

## What Changed

### Modelo (`SheetData.java`)
- `MAX_HP_FLOOR = -999`; `Vitals` passou a `clamp(hp, MAX_HP_FLOOR, hpMax)`.
  **HP pode ser negativo** (decisão do usuário). Mana segue `0..manaMax`.
- `Vitals.downed()` e `SheetData.isDowned()` (`hp <= 0`).
- Codecs inalterados — nenhum limite de bytes precisou mudar, porque o piso já
  cabe em `VAR_INT`.

### Vida / estado deitado (`DamageControlHandler.java`)
- `ServerTickEvents.END_SERVER_TICK` por jogador: pose `SWIMMING`, sem sprint,
  zera **só** o movimento horizontal (zerar o eixo Y prenderia o jogador no ar).
- Envia `DownedStatePayload` **só quando o estado muda** (`Map<UUID, Boolean>`),
  incluindo na primeira observação do jogador e limpando quem sai do mundo.
- Levanta para `STANDING` só se a pose ainda for a que o mod aplicou (não
  sobrescreve sono/nado).
- `isDowned(ServerPlayer)` público, usado pelo mixin de movimento.

### Rede (`RpgNetworking.java`)
- `DownedStatePayload(boolean)` S2C + `sendDownedState(...)`.
- Estado deitado também enviado no `JOIN` (o campo `downed` do cliente é
  estático e sobrevive à troca de mundo).
- **Mestre removido da lista de `Players`**: ele não tem ficha própria.

### Cliente
- `CharacterSheetScreen` virou **base abstrata** (fundo escuro, painel
  responsivo, sincronia, `Editable`/`Read-only`, botão Back).
- `StatusScreen` (novo): identidade, **barras de HP e Mana com setas**,
  progresso, atributos em 2 colunas (1 coluna em painel estreito).
- `SkillsScreen` (novo): lista de habilidades, add/remove, scroll.
- `RpgMenuScreen`: Jogador = Status/Skills/Rolls/Settings (+End Turn);
  Mestre = Players/Rolls/Settings. "My Sheet" saiu.
- `PlayerListScreen`: abre `StatusScreen`; dentro dele há o atalho **Skills**
  (única forma do mestre editar as habilidades de alguém).
- `LocalPlayerMixin`: congela com `locked || downed`.
- `ServerGamePacketListenerImplMixin`: cancela pacote de movimento **com
  posição** quando deitado; **rotação continua passando**.

## Files Changed
Modificados: `SheetData.java` (novo), `DamageControlHandler.java`,
`RpgNetworking.java`, `SessionManager.java`, `ServerGamePacketListenerImplMixin.java`,
`CharacterSheetScreen.java` (reescrito como base), `TabletopRpgClient.java`,
`LocalPlayerMixin.java`, `RpgMenuScreen.java`, `PlayerListScreen.java`.
Novos: `StatusScreen.java`, `SkillsScreen.java`.
(FASE 3 anterior: `SheetData.java`, `SessionManager.java`, `CharacterSheetScreen.java`.)

## Decisions
1. **HP da ficha é a fonte da verdade** (Opção B). O usuário pediu para eu
   comparar as opções antes de escolher; a alternativa A (sincronizar com os
   corações do vanilla) foi **descartada** porque no vanilla 0 = morte
   instantânea, e "não morrer" exigiria manter a vida real em ~1 enquanto a tela
   mostraria 0 (dois valores divergentes). A Opção C (espelhar) foi descartada
   por ter duas fontes da verdade.
2. **Nenhum `ALLOW_DEATH` global foi registrado.** A API existe (verificada com
   `javap` em `fabric-entity-events-v1`), mas cancelar toda morte quebraria as
   duas válvulas de segurança que já existiam em `DamageControlHandler`: o void
   (senão softlock) e o `/kill` (o mestre precisa desfazer erros). Como o
   jogador **já é imune a todo o resto** (`ALLOW_DAMAGE` da FASE 0.2), não há
   outra fonte de morte em jogo. **Exceção consciente:** void e `/kill` ainda
   matam. Se o usuário quiser matar proibido mesmo nesses casos, é uma linha a
   acrescentar — decisão deixada em aberto.
3. **Deitado = pose vanilla `SWIMMING`** (deitado de bruços), pedido
   explicitamente pelo usuário. O vanilla não tem pose "deitado de costas"
   (confirmado em `Entity$Pose`); o visual "deitado de verdade" exigiria um
   renderer próprio.
4. **HP editável só pelas setas**, e o teto (`Max HP`/`Max Mana`) continua como
   caixa de texto — sem ela não haveria como mudar o máximo.
5. **Passo das setas = 1 por clique.** ⚠️ **DECISÃO PROVISÓRIA:** o usuário não
   especificou. Está em `CharacterSheetScreen.ARROW_STEP` para troca num só lugar.
6. **Rótulos mantidos em inglês** (`Identity`, `Name`, `HP`, `STR`…), porque já
   eram assim e o usuário não pediu troca de idioma.
7. **Persistência em disco fora do escopo**: a ficha continua em memória e some
   ao reiniciar o servidor (já era assim na FASE 3).

## Validation
- `.\gradlew.bat build` → **BUILD SUCCESSFUL** (rodadas: 8s, 4s, 4s).
- `test` → `NO-SOURCE` (o projeto não tem testes automatizados).
- APIs usadas foram verificadas com `javap` antes de serem usadas:
  `ServerLivingEntityEvents.ALLOW_DAMAGE/ALLOW_DEATH`,
  `Entity$Pose` (lista de poses), `EditBox.setTextColorUneditable/setHint/
  setTextColor/setResponder/setFilter/setMaxLength/setEditable/setValue`.
- **NÃO validado em runtime** (não há como abrir o jogo aqui): navegação real,
  aparência das barras, comportamento deitado/levantar, redimensionamento.

## Problems Encountered
1. **Campos não editáveis (feedback do usuário).** Causa **não reproduzida**:
   não há como abrir o cliente aqui. O que o código mostra: as caixas nascem com
   `setEditable(canEdit)` e `canEdit` é `false` no primeiro `init()` (a ficha
   ainda não chegou do servidor), só virando editável em `applySheetToWidgets()`.
   Combinado com fundo transparente e texto cinza, o campo **parecia** texto
   estático.    **Mitigações** (sem alegar causa raiz): texto branco quando
   editável, cinza explícito quando não, dica "click to edit", e o rótulo
   **Editable / Read-only** sempre visível. Se o bug persistir, o próximo passo
   é log no cliente ao enviar a edição.
2. **Colisão de widgets em janela baixa** — encontrada pelo revisor, não por mim:
   os avisos "DOWNED" e o contador de skills eram desenhados perto da base e
   cobriam a última linha. Movidos para a faixa superior (y=24), que é reservada.
3. **Back do Skills deixava o Status em "Loading" para sempre** — encontrada pelo
   revisor: a tela nova nasce sem estado e o pedido da ficha não era refeito.
4. **Mana inicial exibida como `0 / 1`** — encontrada pelo revisor: o denominador
   era forçado a 1, mas uma ficha nova tem `manaMax = 0` (o modelo permite).
5. **Caracteres corrompidos em comentário** durante uma edição (corrigido na hora).

## Root Causes
- #2: os avisos foram colocados em `height - 40`, dentro da área útil
  (`contentBottom = height - 30`), sem faixa reservada.
- #3: o `requestSheet` estava só nos pontos de entrada do menu; a navegação
  interna entre telas não o refazia.
- #4: `Math.max(1, ...)` no denominador, que não respeita `manaMax = 0` legal.
- #1: **HYPOTHESIS** (não confirmada) — combinação de timing + falta de
  contraste, não de lógica de permissão (o caminho do servidor está correto:
  `canEditSheet` libera o dono da própria ficha).

## Fixes
Todos os 4 bugs do revisor foram corrigidos e o build refeito (`SUCCESSFUL`):
- `SkillsScreen.close()` passou a pedir a ficha de novo;
- avisos movidos para a faixa superior (`renderTopLeft`);
- `StatusScreen.buildFooterExtra` dá o atalho **Skills** ao mestre;
- denominador de HP/Mana deixou de ser forçado;
- mestre fora da lista de `Players`; estado deitado enviado no `JOIN`.

## Remaining Issues
1. **Void e `/kill` ainda matam** (decisão documentada, item 2 de *Decisions*).
2. **Passo das setas = 1** é provisório.
3. **Sem persistência**: ficha e estado deitado se perdem ao reiniciar.
4. **Sem teste em runtime** — o visual (barras, fundo, colunas) e o ciclo
   deitado → levantar precisam de conferência do usuário no jogo.
5. **Pré-existente, fora do escopo:** `RpgMenuScreen` usa `+ 60` sem escalar em
   `startY` (linha 101), então o botão "Settings" pode sair da tela em janelas
   Altas com GUI scale grande. É do menu de pergaminho, não das telas novas.
6. **Sem testes automatizados** para os clamps de `Vitals` e para as permissões.

## Lessons / Memory
- **Regra reutilizável:** uma tela aberta por código precisa pedir os dados
  **depois** de ser exibida — inclusive quando ela substitui outra tela do
  mesmo grupo. O "pedir antes de abrir" (padrão do menu) não cobre navegação
  interna.
- **Regra reutilizável:** avisos desenhados à mão em telas de GUI precisam de
  uma faixa reservada; `height - 40` cai dentro da área de widgets.
- **Regra reutilizável:** verificadores de divisão com `Math.max(1, ...)`
  quebram valores legítimos de zero — checar o invariante do modelo, não só
  evitar divisão por zero.
- **Regra reutilizável:** argumentos não-ASCII em comandos de shell (PowerShell)
  chegam corrompidos neste ambiente; passar pelo shell só ASCII. Edit/write
  com UTF-8 funcionam normalmente.

## Next Steps
1. Usuário testar no jogo: abrir Status/Skills, mexer nas setas, chegar em 0 e
   confirmar deitado + levantar.
2. Confirmar o **passo das setas** e se **void/`/kill`** devem matar.
3. Persistência da ficha em JSON (pendência da FASE 3).
4. Testes automatizados para `SheetData` (clamp de HP negativo) e permissões.
5. Corrigir o `+ 60` não escalado de `RpgMenuScreen` (pré-existente).
6. Commit — **não commitado** (aguardando pedido explícito).
