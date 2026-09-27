# Funcionalidades e Comandos — TableTop RPG

Catálogo de referência do que o mod **faz hoje**: os modos de sessão, os comandos `/rpg`, as teclas,
as telas, as regras de jogo e o que sobrevive (ou não) a uma desconexão. Serve para consultar o
comportamento real sem precisar abrir o código.

> Este documento é um **catálogo do estado atual do código**. Data de referência:
> **FASE 3, parte 1 — 27/09/2026**. Não contém planos, planos futuros nem sistemas que não
> existem: se algo está no projeto de papel mas não no código, não está aqui.
> Ao alterar o código, atualize este arquivo.
>
> **Como ler as referências.** As citações usam `Arquivo.java: símbolo`, e **não** número de
> linha. Antes de 27/09/2026 este arquivo citava linhas, e elas apodreceram: cada edição de
> código deslocava a numeração e a referência passava a apontar para o lugar errado. Os
> 200 pontos foram convertidos para símbolo (método, campo ou classe), que sobrevive a
> refatoração. A conversão foi feita por script e conferida; se achar alguma citação estranha,
> ela é resíduo da conversão.
>
> Símbolo sozinho, sem o nome do arquivo, significa **o arquivo citado imediatamente antes**
> na mesma frase — é a convenção original do documento. Onde essa ambiguidade atrapalhava
> (a linha da `StatusScreen` cedia a `PlayerListScreen`), o nome do arquivo foi escrito por
> inteiro.

**Como ler as referências de arquivo:** os arquivos do servidor ficam em
`src/main/java/com/pedro/tabletoprpg/` e os do cliente em
`src/client/java/com/pedro/tabletoprpg/client/`. Nas tabelas abaixo está apenas `Arquivo.java:linha`,
com a linha exata onde o comportamento está implementado.

---

## 1. Visão geral da sessão

A sessão é controlada pelo **Mestre** (*Game Master*). Um jogador assume o papel com
`/rpg master claim` (só um por vez) e abre mão com `/rpg master release`.

### Os três modos de jogo

Definidos em `SessionManager.java: GameMode` (enum `GameMode`) e trocados com `/rpg mode <modo>`.

| Modo | O que faz | Quem fica travado | O que o Mestre vê |
|---|---|---|---|
| `FREE` | Todo mundo age ao mesmo tempo, sem controle de turno. É também o modo em que a sessão entra e o modo para onde ela volta quando o Mestre sai. | **Ninguém.** `SessionManager.canPlayerAct` retorna `true` para todos (`SessionManager.java: canPlayerAct`). | Nada de especial: anda, quebra, interage e nunca é limitado pela aura. |
| `INVESTIGATION` | Investigação: o Mestre conduz e passa a vez de um em um. | Todos, **exceto** o Mestre e o jogador com o turno ativo. | Nunca fica travado e nunca é limitado pela aura. É quem dá e revoga turnos. |
| `COMBAT` | Combate: mesma trava de turno do modo Investigação, com o Mestre no comando dos monstros. | Todos, **exceto** o Mestre e o jogador com o turno ativo. | Idem: nunca travado, nunca limitado, e é quem seleciona e move os monstros. |

O estado de trava ("travado" / `locked`) é derivado, não guardado: o servidor envia
`PlayerLockPayload` com `!SessionManager.canPlayerAct(player)` para cada jogador, em cada mudança de
modo ou de turno (`RpgNetworking.java: registerDayCycleReceiver`).

### Três conceitos que aparecem o tempo todo

- **Travado (`locked`)** — o jogador está no modo Investigação ou Combate e não é o dono do turno
  ativo. Efeito no cliente: o movimento local é congelado (`LocalPlayerMixin.java: LocalPlayerMixin`), o HUD
  some (`GuiMixin.java: GuiMixin`) e o servidor descarta os pacotes de movimento
  (`ServerGamePacketListenerImplMixin.java: ServerGamePacketListenerImplMixin`). O congelamento do lado do cliente existe para que
  ele pare de se mover **antes** de o servidor corrigir a posição — sem isso o jogador veria o
  rubber-banding do vanilla.
- **Turno ativo** — **um único jogador por vez**, sem fila e sem ordem. É um UUID e um nome em
  memória (`SessionManager.java: activePlayerUuid`, `setActivePlayer`). **Não existe lista de iniciativa nem ordem de
  turno no código**: o Mestre simplesmente dá o turno para quem ele quiser, na ordem que quiser.
- **Mestre** — o jogador cujo UUID está em `SessionManager.masterUuid`
  (`SessionManager.java: masterUuid`, `isMaster`). Ele nunca é travado, nunca tem a aura aplicada e nunca tem
  a emissão do destaque limitada por distância de jogador.

---

## 2. Comandos `/rpg`

Raiz única `/rpg`, **sem aliases**. Todos registrados em
`MasterCommands.java: onRegister`. A permissão é conferida por `verifyMasterPermission`
(`MasterCommands.java: rollDice`), que exige `SessionManager.isMaster(player)` e, se faltar, responde
`§c[RPG] Only the Game Master can perform this action.`

| Comando | Quem pode usar | O que faz | Arquivo:linha |
|---|---|---|---|
| `/rpg` | qualquer jogador | Abre o **menu ASCII da sessão no chat** (não é a tela gráfica): papel, modo, turno ativo e botões clicáveis | `MasterCommands.java: onRegister` (handler `openMenu: openMenu`, menu `: rollDice`) |
| `/rpg menu` | qualquer jogador | Abre exatamente o mesmo menu ASCII | `MasterCommands.java: onRegister` |
| `/rpg master claim` | qualquer jogador — **falha se já houver um Mestre** | Torna quem executou o Mestre e anuncia para todos | `MasterCommands.java: onRegister` (handler `claimMaster: claimMaster`) |
| `/rpg master release` | só o Mestre | Abre mão do cargo e reseta o `CombatController` (seleção, âncoras) | `MasterCommands.java: onRegister` (handler `releaseMaster: releaseMaster`) |
| `/rpg mode free` | só o Mestre | Coloca a sessão em **Free**; também limpa seleção, âncoras e monstros controlados | `MasterCommands.java: onRegister` (handler `setMode: setMode`) |
| `/rpg mode investigation` | só o Mestre | Coloca a sessão em **Investigation** | `MasterCommands.java: onRegister` (idem) |
| `/rpg mode combat` | só o Mestre | Coloca a sessão em **Combat** | `MasterCommands.java: onRegister` (idem) |
| `/rpg turn give <player>` | só o Mestre | Dá o turno ao jogador, fixa a âncora dele (é a origem da aura) e anuncia. O nome do jogador é completado pelo próprio Minecraft | `MasterCommands.java: onRegister` (handler `giveTurn: giveTurn`) |
| `/rpg turn revoke` | só o Mestre | Cancela o turno ativo, limpa a âncora e anuncia | `MasterCommands.java: onRegister` (handler `revokeTurn: revokeTurn`) |
| `/rpg turn finish` | **dono do turno ou o Mestre** | Encerra o turno. Quem não for o dono nem o Mestre recebe `§c[RPG] It is not your turn to finish!` | `MasterCommands.java: onRegister` (`MasterCommands.java: finishTurn`, incluindo a checagem de dono) |
| `/rpg roll` | qualquer jogador | **Lista as 18 perícias** da ficha com valor + atributo de cada uma (não rola nada) | `MasterCommands.java: onRegister` (handler `listSkills: listSkills`) |
| `/rpg roll <perícia>` | qualquer jogador | Rola `1d20 + valor_da_perícia + atributo`, mostrando a conta (`d20 (12) + 2 + 3 = 17`) | `MasterCommands.java: onRegister` (handlers `rollOrSkill: rollOrSkill`, `rollSkill: rollSkill`) |
| `/rpg roll <fórmula>` | qualquer jogador | Rola uma fórmula de dados (`d20`, `2d6+3`, `2d6+d4+3`) | `MasterCommands.java: onRegister` (handler `rollFormula: rollFormula`, parser `rollDice: setHoverDistance`) |
| `/rpg openroll [fórmula]` | só o Mestre | Rolagem **pública** (todos veem). Sem argumento, rola `d20` | `MasterCommands.java: onRegister` (handler `openRoll: openRoll`) |
| `/rpg time <0-24000>` | só o Mestre | Define o horário do mundo em ticks (0 = amanhecer, 6000 = meio-dia, 18000 = meia-noite) | `MasterCommands.java: onRegister` (handler `setWorldTime: setWorldTime`) |
| `/rpg hoverdistance <1-256>` | só o Mestre | Define, **em blocos**, a distância máxima do destaque de mob para os jogadores | `MasterCommands.java: onRegister` (handler `setHoverDistance: setWorldTime`) |
| `/rpg session set <nome>` | só o Mestre | Define o nome da sessão; nomes vazios são recusados e o texto é cortado em 64 caracteres | `MasterCommands.java: onRegister` (handler `setSessionName: setSessionName`) |
| `/rpg insert enemy <tipo> <cam_perm>` | só o Mestre | Invoca um mob 2 blocos à frente do Mestre, na direção do olhar dele. `tipo` aceita `zombie` ou `minecraft:zombie`; a sugestão só oferece criaturas da categoria `MONSTER` (vanilla e de outros mods). `cam_perm` é `true`/`false` | `MasterCommands.java: onRegister` (sugestões `suggestEntityTypes: suggestEntityTypes`, handler `insertEnemy: insertEnemy`) |
| `/rpg remove enemy` | só o Mestre | Remove **1 mob por execução**: o que estiver selecionado no momento. Exige ter clicado com o botão direito no mob antes | `MasterCommands.java: onRegister` (handler `removeEnemy: removeEnemy`) |

### Detalhes que valem uma linha cada

- **`/rpg turn revoke` e `/rpg turn finish` funcionam com o dono do turno offline.** Eles leem o
  UUID guardado no servidor (`SessionManager.getActivePlayerUuid()`), não a entidade do jogador
  (`MasterCommands.java: revokeTurn`, `finishTurn`). É isso que permite ao Mestre pular a vez de quem caiu de
  conexão: o turno fica reservado (ver [seção 6](#6-estado-por-conexão-o-que-sobrevive-e-o-que-se-perde)).
- **`/rpg insert enemy <tipo> <cam_perm>`**: o mob nasce com IA desligada, sem despawn natural e
  **invulnerável** — é uma peça de mesa, não um inimigo de combate (`MasterCommands.java: insertEnemy`).
  Fica marcado no NBT com `tabletoprpg_inserted`, e com `cam_perm=true` também com
  `tabletoprpg_camera`, que o faz entrar no carrossel de espectador dos jogadores travados
  (`MasterCommands.java: insertEnemy`).
- **`/rpg remove enemy`** sem seleção devolve
  `§c[RPG] No enemy selected. Right-click an enemy first, then run /rpg remove enemy.`
  (`MasterCommands.java: removeEnemy`).
- **O parser de dados aceita somas e subtrações.** Um termo é um dado (`^(\d*)[dD](\d+)$`,
  `MasterCommands.java: DICE_TERM`) ou um número inteiro positivo (`^\d+$`, `: MODIFIER_TERM`); a fórmula é
  quebrada por `+` e por `-` (`: rollDice`, `split("(?=[+-])", -1)`). O sinal fica no início do
  termo seguinte, então `d8-1` vale **dado menos 1**. O texto do resultado usa `" §f+ §b"`
  ou `" §f- §b"` conforme o sinal, então aparece `d8 [5] - 1 = 4` (`appendRollTerm`, `: rollDice`).
  **Sinal duplo é recusado** (`d8--1`, `d8+-1`).
  Limites: 1 a 100 dados por termo e 1 a 1000 faces (`: rollDice`).
- **`/rpg roll` sem argumento lista 18 perícias.** Elas vêm da lista fixa `PERICIAS_PADRAO`
  (`SheetData.java: PERICIAS_PADRAO`): **18 fixas**, exatamente as perícias básicas
  de D&D 5e em inglês, cada uma com o atributo que o D&D 5e define para ela
  (`Acrobatics`/DES, `Animal Handling`/SAB, `Arcana`/INT, `Athletics`/FOR, `Deception`/CAR,
  `History`/INT, `Insight`/SAB, `Intimidation`/CAR, `Investigation`/INT, `Medicine`/SAB,
  `Nature`/INT, `Perception`/SAB, `Performance`/CAR, `Persuasion`/CAR, `Religion`/INT,
  `Stealth`/DES, `Survival`/SAB, `Thievery`/DES), todas com valor 0. `Initiative` e `Melee`,
  que o sistema tinha desde o começo, **saíram da lista em 27/09/2026 por decisão do usuário** —
  `Initiative` não era lida por nada no código e `Melee` só duplicava `Athletics`. Não há botão
  de adicionar nem de remover: a lista é fixa no código. O cabeçalho da lista no chat mostra a
  contagem por `sheet.pericias().size()` (`MasterCommands.java: listSkills`), então ele já diz 18.
- **O valor de uma perícia vai de `0` a `30`** (`SheetData.java: Pericia`); o servidor recusa
  qualquer coisa fora dessa faixa, e o `+` da tela desliga em 30 e o `-` em 0
  (`StatusScreen.java: renderContent`).
- **O nome da perícia é comparado sem acento e sem diferenciar maiúsculas**
  (`MasterCommands.java: normalize`), então `acrobatics` funciona igual a `Acrobatics`. Fichas salvas
  antes de 27/09/2026 migram na hora de carregar pelos apelidos de
  `SheetData.java: LEGACY_PERICIA_NAMES`: `Acrobacia`→`Acrobatics`,
  `Diplomacy`/`Diplomacia`→`Persuasion`. **Regra: qualquer renomeação futura em
  `PERICIAS_PADRAO` precisa de apelido junto** — sem ele a troca zera a perícia silenciosamente,
  já que `sanitizePericias` cai no padrão sem logar nada.
- **A migração de 27/09/2026 tem perdas conhecidas, avisadas e não corrigíveis:**
  - os antigos lugares reservados `perícia N` e `skill N` são **descartados sem log**. A tela
    antiga mostrava as 20 linhas com `-`/`+` editáveis, então quem ajustou `skill 3` para 5
    **perde os 5**: não existe linha nova que receba esse número;
  - `Melee`/`Luta` e `Initiative`/`Iniciativa` **saíram do padrão**, então o valor que tinham
    (2 por padrão) é descartado do mesmo jeito. Nenhum apelido resolve isso: resolveria
    inventando uma 19ª e 20ª linha que o usuário não pediu.
  **Regra: qualquer renomeação futura em `PERICIAS_PADRAO` precisa de apelido junto** — sem ele a
  troca zera a perícia silenciosamente, já que `sanitizePericias` cai no padrão sem logar nada.
- **A fórmula de perícia é `1d20 + valor_da_perícia + atributo`**, em aritmética `long` para não
  estourar com atributos grandes (`MasterCommands.java: rollSkill`).
- **Visibilidade da rolagem:** o Mestre vê o resultado **só para si** (rolagem secreta); o jogador
  comum envia para o chat público (`MasterCommands.java: rollSkill`). O `/rpg openroll` é
  sempre público.
- **`/rpg hoverdistance` vale para todo mundo, inclusive para o Mestre.** O servidor envia o mesmo
  valor para cada jogador conectado, sem tratamento especial para o Mestre
  (`RpgNetworking.java: sendAuraState`); o padrão é **32 blocos** (`SessionManager.java: hoverDistance`) e o valor é
  limitado a 1..256 (`: setHoverDistance`).

### O que o `/rpg` mostra no chat

O menu ASCII é montado em `MasterCommands.java: rollDice` e muda conforme o papel:

- **Para o Mestre** (`§e [ ROLE: MASTER ]`): botões de modo (`[FREE]`, `[INVESTIGATION]`, `[COMBAT]`),
  `Active Turn`, `[Give Turn]` (autocompleta o nome do jogador), `[Revoke Turn]`, `[Roll]` e
  `[Step Down]`.
- **Para o jogador** (`§b [ ROLE: PLAYER ]`): `Current Mode`, `Active Turn`, `[Finish Turn]`
  (só quando é a vez dele), `[Claim Master]` (só quando ainda não há Mestre) e `[Roll]`.

Os botões são `ClickEvent.RunCommand`/`SuggestCommand`: clicar já executa ou preenche o comando.
Depois de `claim`, `release`, mudança de modo e troca de turno, o menu é reenviado sozinho a quem
executou o comando (`refreshChatMenu: rollDice`), sem precisar digitar `/rpg menu` de novo.

---

## 3. Teclas

As duas teclas do mod estão em uma **aba própria** na tela de Controles, chamada **`Tabletop RPG`**
(categoria registrada em `TabletopRpgClient.java: onInitializeClient`, rótulo em `en_us.json`, chave `key.category.tabletop-rpg.rpg`). Antes elas
caíam em `Miscellaneous`.

| Tecla | Ação | Arquivo:linha |
|---|---|---|
| `R` | Abre o **menu gráfico** do TableTop RPG. O cliente pede os dados da sessão e o servidor responde abrindo a tela com papel, modo e turno corretos | `TabletopRpgClient.java: onInitializeClient` (consumo `: onInitializeClient`; abertura da tela `: registerNetworking`) |
| `V` | Cicla o modo de câmera: `3rd Person` → `1st Person` → `Top-Down` → `Free`. Fora do modo espectador, alterna entre a câmera normal do jogo e a câmera Livre. Mostra o modo na barra de ação (`§b[Camera] §f...`) | `TabletopRpgClient.java: onInitializeClient` (consumo `: onInitializeClient`; ciclo `SpectatorCameraController.java: cycleMode`) |
| `Tab` na aba `Tabletop RPG` | Aba própria em Opções → Controles, com as duas teclas acima | `TabletopRpgClient.java: onInitializeClient`, `en_us.json`, chave `key.category.tabletop-rpg.rpg` |

### Teclas vanilla consumidas pelo mod

Só valem **enquanto o jogador está travado** (modo Investigação/Combate fora do turno). Fora desse
estado, o vanilla funciona normalmente.

| Tecla vanilla | O que o mod faz com ela | Arquivo:linha |
|---|---|---|
| Clique esquerdo (`keyAttack`) | Troca para o **próximo alvo** do carrossel de espectador, em vez de atacar | `MinecraftMixin.java: tabletopRpg$spectatorControls` (ação `cycleNext` em `SpectatorCameraController.java: cycleNext`) |
| Clique direito (`keyUse`) | Volta para o **alvo anterior** do carrossel, em vez de usar item/bloco | `MinecraftMixin.java: tabletopRpg$spectatorControls` (ação `cyclePrev` em `SpectatorCameraController.java: cyclePrev`) |
| `F5` (trocar perspectiva) | O clique é **engolido**: a perspectiva passa a ser controlada pelos modos de câmera do `V` | `MinecraftMixin.java: tabletopRpg$spectatorControls` |
| `WASD`, `Espaço`, `Shift` | Movem a **câmera Livre** (não o corpo). O corpo fica parado e a câmera não atravessa parede | `SpectatorCameraController.java: tickFreeCamera`, colisão `: collideFreeCamera` |
| Mouse | Olha com a câmera; o **corpo do jogador não gira** enquanto a câmera de espectador está ativa | `EntityTurnMixin.java: EntityTurnMixin` (yaw/pitch próprios da câmera em `SpectatorCameraController.java: onMouseLook`) |

O `V` também está disponível como botão `Camera Mode: ...` na tela de Settings
(`RpgSettingsScreen.java: init`), com o mesmo efeito.

---

## 4. Telas (menus)

| Tela | Quem abre | Para que serve |
|---|---|---|
| Menu ASCII da sessão (no chat) | `/rpg` ou `/rpg menu` (`MasterCommands.java: onRegister`) | Resumo em texto: papel, modo, turno ativo e botões clicáveis de comando |
| `RpgMenuScreen` — menu principal no pergaminho | Tecla `R` (`TabletopRpgClient.java: onInitializeClient`) | Mostra `Role: MASTER/PLAYER`, `Mode` e `Turn` (`RpgMenuScreen.java: render`) e navega: **Mestre** vê `Players`, `Rolls`, `Settings`; **Jogador** vê `Status`, `Skills`, `Rolls`, `Settings` e `End Turn` quando é a vez dele (`: buildMenu`). **Não** existe botão de ficha própria para o Mestre |
| `PlayerListScreen` — `Players` | Botão `Players` do menu, só para o Mestre (`RpgMenuScreen.java: buildMenu`, `: openPlayers`) | Lista os jogadores conectados; clicar em um abre a `Status` **dela**. O Mestre não entra na lista (`RpgNetworking.java: sendMenuToPlayer`) |
| `StatusScreen` — `Status` | Jogador: botão `Status` do próprio menu. Mestre: clicando num jogador em `Players` (`RpgMenuScreen.java: buildMenu`, `: openStatus`; `PlayerListScreen.java: openSheet`) | Ficha do personagem: nome, raça, classe, `Background`, HP/Mana, nível/XP, os 6 atributos e as 18 perícias fixas. Valores mexem por botões **`-` e `+`** (não mais setas `>` e `<`), e o `+` fica **cinza/desligado ao chegar no teto** (30 nos atributos e nas perícias) e o `-` no piso 0 das perícias. A coluna de perícias tem o cabeçalho **`Bonus`**, porque o número ao lado do nome é o bônus investido, não o resultado da rolagem — que ainda soma o atributo. A largura da caixa de número acompanha o teto, então dois dígitos aparecem (antes o texto era cortado e o `10` virava `1`). Abre a `SkillsScreen` (`: buildFooterExtra (botao Skills)`) e a `AttributePickerScreen` (`: openPericiaAttribute`) |
| `SkillsScreen` — `Skills` | Botão `Skills` do menu ou o botão `Skills` dentro da `StatusScreen` (`RpgMenuScreen.java: buildMenu`, `: openSkills`; `StatusScreen.java: buildFooterExtra`) | Lista **livre** de skills (nome + descrição): adicionar, remover e reordenar com as setas. Máximo de 24 (`SheetData.java: MAX_SKILLS`). A skill não tem valor nem atributo — quem tem isso é a perícia |
| `AttributePickerScreen` — `Attribute` | Botão de atributo de uma perícia, dentro da `StatusScreen` (`StatusScreen.java: openPericiaAttribute`) | Escolhe com qual dos 6 atributos a perícia soma |
| `DiceRollScreen` — `Rolls` | Botão `Rolls` do menu (`RpgMenuScreen.java: buildMenu`) | Monta a rolagem clicando em dados (`d4 d6 d8 d10 d12 d20 d100`, `DiceRollScreen.java: buildUI`) e modificadores (`-10 -1 +1 +10`, `: buildUI`), mostra a expressão e envia `/rpg roll <expressão>` ao servidor (`: rollDice`) |
| `RpgSettingsScreen` — `Settings` | Botão `Settings` do menu (`RpgMenuScreen.java: buildMenu`) | **Mestre:** slider de horário, botão `Day/Night Cycle: On/Paused`, `Players break blocks: Yes/No`, `Players place blocks: Yes/No`, `Weather: Clear/Rain/Thunderstorm` e `Back` (`RpgSettingsScreen.java: init`, bloco do Mestre). **Jogador:** `Orbital Camera: Yes/No`, `Camera Mode: ...`, `Zoom TopDown` e `Transition Speed`, mais `Back` (`: init`) |
| `CharacterSheetScreen` | — | Classe base de `StatusScreen` e `SkillsScreen`; não é aberta sozinha (`CharacterSheetScreen.java: CharacterSheetScreen`) |

Os botões `-10` e `-1` da `DiceRollScreen` montam expressões como `d8-2` e **funcionam**:
o parser do servidor passou a aceitar subtração em 27/09/2026 (ver `rollDice`,
`MasterCommands.java: rollDice`). Antes a rolagem era recusada com
`§cInvalid term: 'd8-2'. Use format like d20, 2d6, or a number.`

---

## 5. Regras do sistema

### Imunidade a dano

Jogadores (inclusive o Mestre) e mobs **nunca tomam dano físico** — queda, lava, fogo, explosão e
ataques de mob. Existem duas exceções de segurança, que não são "dano de mesa":
`DamageTypes.FELL_OUT_OF_WORLD` (queda no vazio, para não criar travamento permanente) e
`DamageTypes.GENERIC_KILL` (`/kill`, para o Mestre desfazer erros de sessão).
`DamageControlHandler.java: register`.

### HP menor ou igual a zero = deitado, não morto

Quando o **HP da ficha** chega a 0 (ou fica negativo), o personagem **deita e não morre**:
`DamageControlHandler.java: tickDownedPlayers`, `SheetData.java: composite`.

- O servidor aplica a pose `SWIMMING` (a única pose vanilla que deixa o personagem no chão sem
  cama), desliga o sprint e zera **só** o movimento horizontal — zerar o eixo Y prenderia quem
  estivesse caindo (`CombatController.java: clampToAura`).
- O vanilla não sobrescreve a pose: `PlayerPoseMixin` cancela `updatePlayerPose` enquanto o
  personagem está deitado, o que elimina o "flickering" de levantar/deitar todo tick
  (`PlayerPoseMixin.java: PlayerPoseMixin`).
- O servidor bloqueia os pacotes de **posição**, mas deixa a **rotação** passar: um personagem
  deitado ainda olha para os lados (`ServerGamePacketListenerImplMixin.java: tabletopRpg$cancelMoveIfLocked`).
- O cliente congela o movimento local (`LocalPlayerMixin.java: tabletopRpg$freezeWhenLocked`) e o estado é transmitido a
  **todos** os clientes conectados, porque o vanilla recalcula a pose de todos os jogadores a cada
  tick — o Mestre precisa ver quem está caído (`RpgNetworking.java: sendDownedState`, `sendDownedSnapshotTo`).
- Com HP maior que zero o personagem levanta sozinho (`DamageControlHandler.java: tickDownedPlayer`).

### Turno

Um único jogador ativo, sem fila e sem ordem (`SessionManager.java: activePlayerUuid`, `isActivePlayer`). Quem pode agir
é decidido por `canPlayerAct` (`SessionManager.java: canPlayerAct`): no modo `FREE` todos; em
`INVESTIGATION` e `COMBAT` só o Mestre e o dono do turno.

### Aura de limite de movimentação

- Raio de **15 blocos** (`CombatController.java: AURA_RADIUS`).
- A aura é **ancorada** na posição onde o jogador começou o turno (`setPlayerAnchor: setPlayerAnchor`, chamada
  em `giveTurn`, `MasterCommands.java: giveTurn`) ou onde o monstro foi selecionado
  (`CombatController.java: toggleSelection`). **Ela não segue ninguém** (`: tickControlledMonsters`) e é removida no fim do
  turno (`clearPlayerAnchor: clearPlayerAnchor`).
- O **Mestre nunca é limitado** (`CombatController.java: isPlayerBeyondAura`), e jogador sem âncora também não.
- Ao ultrapassar a borda, o jogador é **projetado de volta para a borda** e o cliente recebe um
  teleporte, em vez de só ter o pacote cancelado — é o que barra o jogador no limite sem deixá-lo
  preso em dessincronia (`CombatController.java: clampToAura`, `ServerGamePacketListenerImplMixin.java: tabletopRpg$cancelMoveIfLocked`).
- O círculo azul é desenhado no chão, seguindo o relevo do terreno (`class AuraRenderer (Javadoc da classe)`).
- Quem entra no servidor **recebe a aura atual** na entrada, sem esperar o próximo turno
  (`RpgNetworking.java: registerServerReceivers`).

### Quem pode quebrar e colocar blocos

| Quem | Quebrar blocos | Colocar blocos |
|---|---|---|
| Mestre | **Sempre** (inclusive fora do turno) | **Sempre** |
| Jogador | Só se o Mestre ligou `Players break blocks` **e** for a vez dele | Só se o Mestre ligou `Players place blocks` **e** for a vez dele |

Implementação: `PlayerControlHandler.java: canBreakBlocks`. Os dois padrões começam **desligados**
(`SessionManager.java: playersCanBreakBlocks`, `: playersCanPlaceBlocks`) e o Mestre os liga em `Settings`. A colocação é checada pelo item na
mão (`BlockItem`), então abrir baú, porta ou alavanca continua sendo uma interação normal
(`PlayerControlHandler.java: canPlaceBlocks`, `isBlockItemInHand`).

As outras interações (atacar entidades, usar itens, usar blocos, clicar em entidades) seguem a regra
de turno: no modo `FREE` todos podem; em `INVESTIGATION`/`COMBAT` só o Mestre e o dono do turno
(`PlayerControlHandler.java: canInteract`). A decisão é sempre do **servidor**; no cliente essas
checagens devolvem "deixa passar" para não cancelar o clique antes do pacote ir ao servidor
(`PlayerControlHandler.java: canBreakBlocks`, `canPlaceBlocks`, `canInteract`).

### Clima

O Mestre escolhe entre **0 = sol, 1 = chuva, 2 = tempestade** (`RpgSettingsScreen.java: init`) e o
servidor aplica `setWeatherParameters` com os mesmos valores do comando vanilla `/weather`
(`RpgNetworking.java: registerServerReceivers`).

O que fica guardado é o **clima alvo** escolhido, não o clima real: em 1.21.11 a transição é gradual,
então durante ela o estado real não corresponde ao escolhido, e o botão de clima reflete o alvo para
não "voltar" sozinho durante a transição (`SessionManager.java: playersCanPlaceBlocks`, `RpgNetworking.java: registerServerReceivers`).

### Dia e noite

- Horário: `/rpg time <0-24000>` (`MasterCommands.java: onRegister`) ou o slider de `Settings`
  (`RpgSettingsScreen.java: init`, que envia `TimeSetPayload`).
- Ciclo: o botão `Day/Night Cycle: On/Paused` liga ou desliga a regra do mundo `advance_time`
  (`RpgNetworking.java: broadcastSheet`).
- Mover o slider não gera mensagem no chat, para não spammar a cada passo
  (`RpgNetworking.java: registerServerReceivers`).

### Destaque (highlight)

- O efeito vanilla `Glowing` **não** é mais usado. Ele é global: o contorno de um mob que um jogador
  estava mirando aparecia na tela de todos os outros.
- O contorno é renderizado **só no cliente de quem está com o mouse em cima do mob**
  (`EntityRendererMixin.java: EntityRendererMixin`).
- O raycast sai da **câmera** (não do corpo), o que faz funcionar inclusive ao espectar outro alvo
  (`TabletopRpgClient.java: tickHover`).
- O mob **precisa estar visível**: se houver qualquer bloco no caminho entre a câmera e ele, não é
  destacado (`TabletopRpgClient.java: tickHover`).
- Só funciona para **mobs**, dentro da distância definida por `/rpg hoverdistance`
  (`TabletopRpgClient.java: tickHover`), e é **desligado enquanto a câmera de espectador está
  ativa** (`: tickHover`).

### Monstros da mesa

- Mobs invocados por `/rpg insert enemy` nascem com **IA desligada, sem despawn e invulneráveis**, e
  nunca agem sozinhos (`MasterCommands.java: insertEnemy`).
- Todo mob inserido — mesmo sem estar selecionado — **olha para o jogador mais próximo** a cada tick
  (`CombatController.java: tickControlledMonsters`).
- O Mestre seleciona um mob com o **botão direito** e o move com o **botão direito em um bloco**
  (`CombatController.java: register`). O movimento é em linha reta, sem IA e sem pathfinding, a
  0,35 bloco por tick (`CombatController.java: MOVE_SPEED`, `moveSelectedMonster`).
- O destino precisa estar **dentro da aura do monstro**; fora dela o movimento é recusado
  (`CombatController.java: moveSelectedMonster`), assim como quando não há espaço para a entidade ficar em cima
  do bloco (`: moveSelectedMonster`).
- Ao selecionar o mesmo mob de novo, ele é **desselecionado** e volta a ser uma peça congelada
  (`CombatController.java: toggleSelection`).

### Ficha do personagem

- A ficha é uma **camada separada da vida do vanilla**: o HP e a Mana da ficha são a fonte da verdade
  do personagem, e o jogador já é imune a dano físico (`class DamageControlHandler (Javadoc da classe)`).
- **HP pode ser negativo** (piso `-999`) e pode **passar do máximo** (`12/10` = 10 permanentes + 2
  temporários). O piso existe só para não estourar o protocolo; o que importa é que `hp <= 0` é
  deitado (`SheetData.java: MAX_RESOURCE`, `: composite`).
- A Mana tem piso 0 e também pode passar do máximo (`SheetData.java: composite`).
- Os **6 atributos têm teto de 30 e podem ser negativos** — eles viraram modificadores somados às
  rolagens de perícia (`SheetData.java: Attributes`). O `+` da tela desliga em 30
  (`StatusScreen.java: renderContent`).
- **O valor da perícia é de 0 a 30** (`SheetData.java: Pericia`).
- A lista de perícias é **fixa em código**: rodando em todo construtor de `SheetData`, é impossível
  uma ficha ter uma perícia a mais, a menos, ou com um nome que o sistema não conhece
  (`SheetData.java: sanitizePericias`). Adicionar ou remover perícias é uma **ideia de fase futura**,
  ainda não implementada.
- **`Background` é um campo de texto livre da identidade** (`SheetData.java: Identity`), gravado no
  mesmo lugar dos outros campos de texto e editável na `StatusScreen` (`StatusScreen.java: buildPanel`).
  Não tem efeito mecânico: é só um rótulo descritivo que o Mestre consulta.
- **Quem vê e quem edita:** o dono vê e edita a própria ficha; o Mestre vê e edita a de qualquer
  jogador; **ninguém mais** consegue abrir a ficha de outro jogador nem por pacote forjado
  (`RpgNetworking.java: resolveSheetTarget`). Toda alteração é reenviada ao dono e ao Mestre, então a tela
  atualiza ao vivo dos dois lados (`: sendSheetTo`).

### Câmera de espectador

Enquanto o jogador está travado, a câmera assume o controle (`SpectatorCameraController.java: tick`):

- **4 modos:** `3rd Person` (órbita), `1st Person` (olhos do alvo), `Top-Down` (15 blocos acima,
  ajustável de 5 a 25 pelo zoom) e `Free` (câmera solta) (`: Mode`, `: TOP_DOWN_HEIGHT`).
- O jogador **travado** alterna pelos quatro modos; quem está **no turno** alterna só entre a câmera
  do jogo e a câmera Livre (`: SpectatorCameraController`, `: setServerTargets`).
- Ao receber o turno, a câmera volta sozinha para a câmera do jogo (`: tick`).
- Durante a câmera de espectador o **HUD some** — mira, hotbar, vida, fome, ar, experiência e efeitos
  (chat, scoreboard e boss bar continuam) (`class GuiMixin`).
- O **corpo do jogador continua visível** ao espectar outro alvo, o que o vanilla não faria
  (`class LevelRendererMixin`).
- A mão/arma é escondida na 1ª pessoa e os **filtros de visão dos mobs** são removidos
  (`GameRendererMixin.java: clearPostEffect`).
- Trocar de alvo ou de modo tem **transição suave**, cuja duração é configurável em `Settings`
  (`SpectatorCameraController.java: advanceTransition`; `RpgSettingsScreen.java: init`).
- A câmera Livre tem colisão: ela **não atravessa parede** (`SpectatorCameraController.java: collideFreeCamera`).

---

## 6. Estado por conexão: o que sobrevive e o que se perde

### O que **persiste** (sobrevive a sair e voltar, e até a recarregar o mundo)

- **A ficha do personagem por completo** — identidade, HP, Mana, nível/XP, os 6 atributos, as 20
  perícias e as skills. É gravada no **NBT do próprio jogador**, que o vanilla já salva no logout e
  no autosave (`mixin/PlayerSheetPersistenceMixin.java: KEY`, chave `tabletoprpg_sheet`). Há ainda
  uma gravação explícita no encerramento do servidor, para o `Ctrl+C` não perder a última alteração
  (`SheetPersistenceEvents.java: register`).
- **A aura (barreira azul)** é reenviada quando o jogador entra, então quem reconecta volta com a
  barreira. Antes disso ela só voltaria no próximo `/rpg turn` (`RpgNetworking.java: registerServerReceivers`).
- **A lista de alvos do carrossel** é reconstruída na entrada, incluindo os mobs com câmera que
  ainda existem no mundo (`RpgNetworking.java: registerServerReceivers`, `RpgNetworking.java: sendWeatherStateToPlayer`).
- **Os mobs invocados** em si: eles ficam gravados no mundo com a marca `tabletoprpg_inserted` /
  `tabletoprpg_camera` e são re-registrados automaticamente depois de reiniciar o servidor
  (`CombatController.java: selfHealCameraMobs`).

### O que é limpo ao desconectar (27/09/2026)

- **O turno de quem desconecta NÃO é mais limpo.** A vez fica **reservada** e o servidor avisa no
  chat público que a vez foi reservada e que o Mestre pode pular com `/rpg turn revoke`. Decisão
  explícita do usuário em 27/09/2026 (`RpgNetworking.java: registerServerReceivers`).
- **O Mestre que desconecta perde o cargo**: a sessão volta para `FREE` e o `CombatController` é
  resetado, para não deixar jogadores travados sem Mestre. Todos recebem
  `§c[RPG] The master left the session. Mode set to FREE.` (`RpgNetworking.java: registerServerReceivers`).
- **No cliente, a desconexão limpa todo o estado derivado da sessão**, para o cliente não reconectar
  carregando lixo da sessão anterior: personagens deitados, flag de deitado, trava, jogador ativo,
  modo da sessão (vira `-1`, um sentinela de "sem sessão"), auras, entidade em destaque, distância do
  destaque, ciclo dia/noite, permissões de quebra e colocação, clima e o índice do carrossel de
  câmera. As preferências de câmera do jogador (modo, órbita, zoom, velocidade de transição) são
  preservadas de propósito (`TabletopRpgClient.java: hoverMaxDistance`, `SpectatorCameraController.java: SpectatorCameraController`).
- Na entrada, o servidor reenvia o estado de trava, o estado de deitado de todos os jogadores, a
  distância do destaque, o carrossel, o jogador ativo e a aura
  (`RpgNetworking.java: registerServerReceivers`).

### O que **não** persiste — limitação conhecida

Todo o estado da sessão vive em **campos estáticos na memória do servidor**
(`SessionManager.java: sessionName`, `CombatController.java: selectedMonsterUuid`). Isso significa que, **se o servidor
reiniciar**, o seguinte é perdido e precisa ser refeito:

| Perdido no restart | Como recomeçar |
|---|---|
| Nome da sessão | `/rpg session set <nome>` |
| Quem é o Mestre | `/rpg master claim` |
| Modo de jogo | `/rpg mode <modo>` |
| Turno ativo | `/rpg turn give <jogador>` |
| Distância do destaque | `/rpg hoverdistance <n>` (padrão: 32) |
| Permissão de quebra de blocos | botão `Players break blocks` em `Settings` (padrão: desligado) |
| Permissão de colocação de blocos | botão `Players place blocks` em `Settings` (padrão: desligado) |
| Clima alvo | botão `Weather` em `Settings` (padrão: sol) |
| Estado de combate: seleção de monstro, âncoras, monstros controlados, destinos | o Mestre precisa selecionar o mob de novo com o botão direito |

O único bloco de estado que **é** gravado em disco é a **ficha do personagem**, no NBT do jogador. E
vale lembrar: **não existe lista de iniciativa nem ordem de turno** — o turno é um único jogador
ativo, então não há fila para se perder.

### Quem pode ver e editar a ficha de quem

| Quem | Lê a própria ficha | Lê a ficha de outro | Edita a própria | Edita a de outro |
|---|---|---|---|---|
| Mestre | sim | **sim** | sim | **sim** |
| Jogador comum | sim | **não** (nem por pacote forjado) | sim | não |

Implementação: `RpgNetworking.java: resolveSheetTarget`. A permissão é reconferida no momento do envio
(`: canViewSheet`), e não só quando a tela é aberta.
