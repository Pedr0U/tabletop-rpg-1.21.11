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
`src/client/java/com/pedro/tabletoprpg/client/`. Nas tabelas abaixo está apenas
`Arquivo.java: símbolo`, o **símbolo** onde o comportamento está implementado — nenhum número de
linha, pelo motivo explicado no aviso acima.

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

| Comando | Quem pode usar | O que faz | Arquivo:símbolo |
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
| `/rpg roll` | qualquer jogador | **Lista as perícias da ficha** com valor + atributo de cada uma (não rola nada). São as do modelo em uso: 18 no padrão, quantas o Mestre deixar | `MasterCommands.java: onRegister` (handler `listSkills: listSkills`) |
| `/rpg roll <perícia>` | qualquer jogador | Rola `1d20 + valor_da_perícia + atributo`, mostrando a conta (`d20 (12) + 2 + 3 = 17`) | `MasterCommands.java: onRegister` (handlers `rollOrSkill: rollOrSkill`, `rollSkill: rollSkill`) |
| `/rpg roll <fórmula>` | qualquer jogador | Rola uma fórmula de dados com o motor completo: `d20`, `2d6+3`, `2d6+d4+3`, keep/drop (`4d20kh2`, `4d6dl1`), explosiva (`d6!`, `d6!5`), incremento por dado (`4d6++2`), repetição da fórmula inteira (`4#(2d6+1d8+5)`, `2#d20+5`) e contagem (`10d6>>3`). **Perícia com cauda** (`/rpg roll Pericia 0-2`) também vale, e o sufixo se aplica só à cauda. O descartado aparece riscado. Ver [o parser de dados](#comandos-de-dados-e-ficha) | `MasterCommands.java: onRegister` (handler `rollFormula: rollFormula`); parser e aritmética em `DiceFormula.java`, formatação em `MasterCommands.java: joinParts`, `: appendTermBody`, `: repeatBody`, `: facesBody` |
| `/rpg openroll [fórmula]` | só o Mestre | Rolagem **pública** (todos veem). Sem argumento, rola `d20` | `MasterCommands.java: onRegister` (handler `openRoll: openRoll`) |
| `/rpg time <0-24000>` | só o Mestre | Define o horário do mundo em ticks (0 = amanhecer, 6000 = meio-dia, 18000 = meia-noite) | `MasterCommands.java: onRegister` (handler `setWorldTime: setWorldTime`) |
| `/rpg hoverdistance <1-256>` | só o Mestre | Define, **em blocos**, a distância máxima do destaque de mob para os jogadores | `MasterCommands.java: onRegister` (handler `setHoverDistance: setWorldTime`) |
| `/rpg session set <nome>` | só o Mestre | Define o nome da sessão; nomes vazios são recusados e o texto é cortado em 64 caracteres | `MasterCommands.java: onRegister` (handler `setSessionName: setSessionName`) |
| `/rpg insert enemy <tipo> <cam_perm>` | só o Mestre | Invoca um mob 2 blocos à frente do Mestre, na direção do olhar dele. `tipo` aceita `zombie` ou `minecraft:zombie`; a sugestão só oferece criaturas da categoria `MONSTER` (vanilla e de outros mods). `cam_perm` é `true`/`false` | `MasterCommands.java: onRegister` (sugestões `suggestEntityTypes: suggestEntityTypes`, handler `insertEnemy: insertEnemy`) |
| `/rpg remove enemy` | só o Mestre | Remove **1 mob por execução**: o que estiver selecionado no momento. Exige ter clicado com o botão direito no mob antes | `MasterCommands.java: onRegister` (handler `removeEnemy: removeEnemy`) |
| `/rpg block_lock` | só o Mestre | **Não tranca nada sozinho**: arma o pedido e responde `Click a block to LOCK it. Players will not be able to use it.` O **próximo clique do Mestre num bloco** tranca aquele bloco. O pedido vale para 1 clique | `MasterCommands.java: onRegister` (handler `blockLock: blockLock`, que chama `BlockLockManager.arm`) |
| `/rpg block_lock remove` | só o Mestre | O mesmo para **destrancar**: arma o pedido, e o próximo clique do Mestre no bloco o destrava | `MasterCommands.java: onRegister` (idem) (handler `blockUnlock: blockUnlock`) |

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
- **Trancar blocos (`/rpg block_lock`, `/rpg block_lock remove` e o item `Block Locker`)**: as trancas
  valem **para os jogadores** (não-mestre); o Mestre nunca é bloqueado, nem com o item na mão. A
  recusa vale **independente do turno**: mesmo o jogador do turno recebe `Block Locked`, porque a
  tranca é uma regra do Mestre e não uma consequência da ordem de jogada
  (`BlockLockManager.java: onUseBlock`).
  - **O comando arma, o clique aplica** (`BlockLockManager.arm` grava em `pendingActions`, e o handler
    consome com `pendingActions.remove`), na mesma troca comando-clique do `/rpg remove enemy`. O
    pedido é de **1 clique** e é cancelado se o Mestre usar o item antes (`BlockLockManager.clearPending`).
  - **Comando e item se comportam diferente no bloco já no estado desejado**: o comando executa a
    **ordem** e responde `That block is already locked.` / `That block is not locked.`; o item
    **alterna**. Inverter as duas coisas faria o comando destravar um baú por engano, e o item
    seria inútil (a mesma sequência de cliques daria o mesmo resultado).
  - **O clique é consumido**: trancar não abre o baú nem gira a porta
    (`BlockLockManager.java: onUseBlock` devolve `InteractionResult.SUCCESS`). É o mesmo motivo pelo
    qual a implementação está no `UseBlockCallback` e não no `Item#use`: o `Item#use` roda
    **depois** do `useWithoutItem` do bloco, então o baú já teria aberto
    (`BlockLockManager`, Javadoc de classe).
  - **O cliente nunca decide**: `onUseBlock` devolve `PASS` quando o jogador não é um `ServerPlayer`.
    Devolver algo diferente cancelaria o pacote **antes** de ele chegar ao servidor, e o Mestre
    ficaria sem trancar nada sem receber erro.
  - **As trancas sobrevivem ao reinício**: ficam num `SavedData` do overworld, em
    `data/tabletop_rpg_block_locks.dat` (`BlockLockStore.java: get`, que usa `computeIfAbsent` e nunca
    `get` — ver o Javadoc da classe e `SheetModelStore` para a armadilha do `null`). A chave é
    **dimensão + posição**, e não só a posição, porque Overworld/Nether/End compartilham o arquivo.
  - **O que é trancável: qualquer bloco com interação** — baú, barril, fornalha, porta, alavanca,
    botão, e inventário de qualquer mod. A verificação é `BlockState.getMenuProvider` (inventário) ou
    a classe do bloco sobrescrever `useWithoutItem`/`useItemOn` (`BlockLockManager.java: isInteractable`).
    **Não** foi usada tag do vanilla: nesta versão `BlockTags` **não tem** `OPENABLE`, `CONTAINERS`,
    `SHOPIERS` nem `LEVERS` (verificado com `javap` no jar nomeado 1.21.11), e depender delas
    deixaria de fora os blocos de mod.
    A sobrescrita é detectada por **assinatura** (`getDeclaredMethods` comparando os **tipos de
    parâmetro**), e não por nome: o nome do método é um literal de string, e literais não passam pelo
    remapeamento que o carregador aplica às referências de classe do Minecraft, então um
    `getDeclaredMethod("useWithoutItem", ...)` pode engolir a sobrescrita num `NoSuchMethodException`
    silencioso e guardar o resultado num cache que nunca é reavaliado. Isso foi observado em teste em
    jogo em 30/09/2026 (porta recusada como "sem interação para travar"); a assinatura usa só
    referências de classe e vale nas duas situações de mapeamento.
  - **A porta é uma unidade, não dois blocos** (30/09/2026): trancar/destravar/consultar opera no
    **conjunto** de posições ligado por `BlockStateProperties.DOUBLE_BLOCK_HALF`, não só na posição
    clicada (`BlockLockManager.java: blockParts`, `anyPartLocked`, `dropLocksOf`). Sem isso o jogador
    travava a metade de baixo, clicava na de cima — posição diferente, fora da lista — e a porta
    abria; foi o que chegou como "abre mesmo trancada" e "abre de longe ou de um nível abaixo", e
    não era alcance, era a outra metade. Só `DOUBLE_BLOCK_HALF` entra (verificado com `javap`:
    `DoubleBlockHalf` só tem `UPPER` e `LOWER`): `HALF` existe em slab, escada e armadilha mas
    significa **formato** do mesmo bloco, não uma posição vizinha — usá-lo trancava também o ar
    acima da escada. Cama usa `BED_PART`, e não passa em `isInteractable`.
  - **Bloco trancado é indestrutível para jogador; só o Mestre quebra** (30/09/2026, pedido do
    usuário). O jogador recebe `This block is locked. Only the Master can break it.` e a tranca
    permanece; o Mestre quebra normalmente e a tranca é removida junto, senão o próximo bloco
    colocado ali nasceria trancado. Tudo num único listener de `PlayerBlockBreakEvents.BEFORE`
    (`BlockLockManager.java: register`): o **`AFTER` não serve** porque só é disparado se nenhum
    listener de `BEFORE` cancelar (confirmado no Javadoc do Fabric API) e o `PlayerControlHandler`
    cancela quando o jogador não pode quebrar — com `AFTER` a limpeza não chegava a rodar, e era o
    defeito de "quebra e recoloca na mesma posição continua trancado".
  - **Não há botão no menu ASCII** (30/09/2026, decisão do usuário): o comando e o item cobrem a
    ação, e o botão seria uma terceira via para o mesmo efeito. Os botões `[Lock]`/`[Unlock]`
    existiram entre a entrega inicial e esta revisão e foram removidos de `buildAsciiMenu`
    (`MasterCommands.java`).
 - **O parser de dados é o `DiceFormula.java` (29/09/2026), uma classe nova, sem Minecraft.** Antes disso a
   rolagem vivia inteira em `MasterCommands.rollDice` e aceitava só termo simples (`4d20`) somado ou subtraído
   (`d8-1`). O motor antigo continua em uso apenas para o texto legado; **a aritmética é a do `DiceFormula`**.
   Um termo é `<contagem><d><faces>` seguido de sufixos opcionais; a fórmula é somada por `+` e `-`
   (`MasterCommands.java: joinParts`). Sintaxe suportada:
   - `4d20`, `d20`, `2d6+3`, `2d6+d4+3` — o básico, como antes;
   - `d20kh1`, `4d6dl1`, `4d6kh3dh1` — **keep/drop** de mais ou menos. `kh`/`kl` mantêm os maiores/menores
     `N` e o resto é descartado; `dh`/`dl` descartam os maiores/menores `N`;
   - `d6!` — **explosiva** (a face maxima vira outra vez); `d6!5` — explosa com **condição**, só explode acima
     de 5. A explosão **garantida** (`5d6!5`, onde a condição é a face maxima) é **recusada**;
   - `4d6++2` / `4d6--2` — **um** `+2`/`-2` **por dado** (soma `8+8+8+8`). A cadeia explosiva conta como
     **um** dado só, então `d6!++2` aplica `+2` uma vez no fim;
   - `6#4d6dl1` — **repete** a fórmula 6 vezes; `>>3` conta os 3 maiores, `<<3` os 3 menores.
    - **`X#` repete a fórmula inteira, não só o primeiro dado (30/09/2026, correção do jogador).**
      `2#d20+5` é `2 × (d20+5)`, então a constante vale nas duas voltas — antes a soma entrava uma vez só no
      total e o `+ 5` aparecia colado embaixo da última linha, que é o que parecia "somar só na última".
      O exemplo `4#(2d6+1d8+5)` também vale, e o **rótulo da linha é o trecho digitado**, sem os parênteses.
      Os parênteses são **agrupamento opcional**, com aninhamento (`2#(1#d6+2)`); sem parênteses o `#` consome
      o resto da fórmula no nível corrente **até o próximo `N#`**, o que faz `2#d20+3#d4` valer
      `2 × d20 + 3 × d4`. Com `repeat=1` os parênteses são transparentes e `(2d6+3)` mostra os termos soltos.
 - **O descarte fica no lugar, não some da lista** (29/09/2026, pedido do jogador). Cada sufixo `kh/kl/dh/dl`
   ordena **só os sobreviventes** e os devolve para os **mesmos slots** que ocupavam; o total e a soma só dos
   que sobraram. É essa regra que faz `4d20kh2` → `[18,17,14,10]` e `4d6dl1` → `[1,2,3,4]`, e não
   `[2,3,4,1]`. O comando mostra o descartado **vermelho e riscado** (`§c§m`…`§r§b`) e **não** escreve mais
   `dropped N`; quando todos caem, todos aparecem marcados e o total é `0` (o antigo `[none]` **saiu**).
 - **No `#`, o cabeçalho fica numa linha e cada repetição vira uma linha**, com o subtotal da volta:
   `6#4d6dl1:` seguido de ` 4d6dl1 [1,2,3,4] = 9` e assim por diante. **Quando a fórmula repetida tem mais de um tipo de dado, cada tipo fica no seu próprio colchete**, como na rolagem sem grade (`MasterCommands.java: repeatBody`, montando a linha com `appendRollTerm` e `appendTermBody`): `4#2d20+2d6` sai ` 2d20 [5, 12] + 2d6 [3, 6] = 26`, e a constante dentro da fórmula repetida aparece como termo (`4#(2d6+1d8+5)` sai ` 2d6 [3, 5] + 1d8 [7] + 5 = 20`). Repetição **só de dado** continua no formato antigo (`Round.parts()` vem vazio e a linha usa rótulo + faces achatadas). Limite de **20 repetições
   exibidas**, o resto vira `...+N more` (`DiceFormula.DISPLAY_ROUNDS`), porque `100#4d6` inundaria o chat.
Cada linha recomeça em `§b` porque **a quebra de linha não limpa a formatação no chat do Minecraft**:
    sem isso o `§a` do subtotal da volta anterior vazava e a volta seguinte saía verde.
    **A linha que teve crítico ganha `CRIT`** no fim (`§c§l CRIT`, `MasterCommands.java: repeatBody`, lendo
    `Round.critical()`), e o **dado crítico** da linha fica **amarelo e em negrito** (`§e§l`, `MasterCommands.java: facesBody`,
    lendo `Face.critical()`). Na volta que teve crítico, o **subtotal também vai em negrito**
    (`§a§l` em `MasterCommands.java: repeatBody`) e a linha fecha com `§r§b`, porque o `§r`
    desliga o **estilo** junto com a cor e sem isso o negrito vaza para o rótulo da linha seguinte.
    **Na grade o `CRIT` do fim da mensagem não é escrito** (30/09/2026, pedido do jogador): quem marca o
    crítico é a própria linha, e o `TOTAL = XX` fica limpo. Rolagem **sem** grade continua com o `CRIT`
    global depois do total (`MasterCommands.java: rollDice` e `: rollPericia`, com `!hasGrid`).
    **O total da grade vai para uma linha própria `TOTAL = XX`** (30/09/2026, pedido do jogador), e o
    `= XX` que ficava colado no fim da última linha saiu, porque ali se confundia com o subtotal dela. A
    grade é reconhecida por alguma peça repetida (`MasterCommands.java: hasGrid`), **não** por procurar o
    `#` no texto, e o mesmo vale quando a **cauda da perícia** tem grade (o `2#d20` depois do nome da
    perícia). Rolagem simples continua numa linha só, com o total colado.
 - **Crítico: sem o sufixo `cN`, o dado no valor máximo conta como crítico (30/09/2026, regra do jogador).**
    Antes o crítico **só** existia com `cN` (`2d20c18` = crítico 18 ou mais, `DiceFormula.java:
    Suffix.critAt`), e `d20` puro nunca era crítico — foi o que o jogador corrigiu. Hoje o critério é
    `critAt >= 0 ? value >= critAt : value >= sides` (`DiceFormula.java: rollOneDie`): **sem `cN` vale o
    máximo do dado** (o `20` de um `d20`), **com `cN` vale o `cN`**, e o `cN` sobrepõe o máximo.
    **Dado descartado nunca é crítico** (`DiceFormula.java: anyKeptCritical`), e o `Face.critical` guarda o
    dado cru enquanto o chat combina `discarded` primeiro, então um descartado continua saindo riscado.
  - A **cor vive toda em `MasterCommands`** (`appendTermBody`, `repeatBody`, `facesBody`). O `DiceFormula` não
   emite nenhum `§` e não importa Minecraft, e é por isso que dá para testar tudo com JUnit. O riscado é
   **estilo**, não cor: precisa de `§r` antes do `§b` para desligar, senão o dado cortado fica azul **e
   riscado**, que é o oposto do que o jogador precisa ver.
 - **Limites** (`DiceFormula.java`): `MAX_DICE=100`, `MAX_SIDES=1000`, `MAX_REPEAT=100`,
   `MAX_TOTAL_ROLLS=100000` (teto de segurança contra `100#100d1000`), `DISPLAY_LIMIT=20` faces mostradas
por dado, `DISPLAY_ROUNDS=20` repetições. O RNG é sorteado **depois** de validar a fórmula, para uma
    rolagem inválida não consumir sorteio. Com `X#` a fórmula inteira é contada `X` vezes contra
    `MAX_TOTAL_ROLLS`, **incluindo dentro de parênteses** (`100#(100#(100d6))` é recusado por orçamento antes
    de qualquer rolagem), porque o teto é de segurança e não pode ser contornado por aninhamento.
- **`/rpg roll` sem argumento lista as perícias da ficha, que já não são uma lista fixa em código.**
  O que sai no chat é `sheet.pericias()` do jogador que executou (`MasterCommands.java: listSkills`), e o
  cabeçalho mostra a contagem por `sheet.pericias().size()` — então ele já acompanha o modelo. Num mundo sem
  modelo salvo (ou antes do primeiro sync) vale o **padrão: 18 perícias**, exatamente as básicas de D&D 5e em
  inglês, cada uma com o atributo que o 5e define (`SheetModel.java: defaults`):
  `Acrobatics`/DES, `Animal Handling`/SAB, `Arcana`/INT, `Athletics`/FOR, `Deception`/CAR,
  `History`/INT, `Insight`/SAB, `Intimidation`/CAR, `Investigation`/INT, `Medicine`/SAB,
  `Nature`/INT, `Perception`/SAB, `Performance`/CAR, `Persuasion`/CAR, `Religion`/INT,
  `Stealth`/DES, `Survival`/SAB, `Thievery`/DES), todas com valor 0. `Initiative` e `Melee`,
  que o sistema tinha desde o começo, **saíram da lista em 27/09/2026 por decisão do usuário** —
  `Initiative` não era lida por nada no código e `Melee` só duplicava `Athletics`. **Quem cria,
  renomeia e remove perícias é o Mestre**, pelo item `sheet_editor` (ver
  [seção 4](#4-telas-menus) e a regra em [seção 5](#ficha-do-personagem)).
- **O valor de uma perícia vai de `0` a `30`** (`SheetData.java: Pericia`, `VALUE_MIN`/`VALUE_MAX`); o servidor
  recusa qualquer coisa fora dessa faixa, e na tela o `+` desliga em 30 e o `-` em 0
  (`StatusScreen.java: applyStepButtons`).
- **O nome da perícia é comparado sem acento e sem diferenciar maiúsculas**
  (`MasterCommands.java: normalize`), então `acrobatics` funciona igual a `Acrobatics`. O `/rpg roll`
  aceita **nome**, não id — o comando é interface humana. **Cada perícia tem um id próprio**
  (`pericia_1`, `pericia_2`, …), gerado uma vez (`SheetModel.java: PericiaDef`, `: freshPericiaId`):
  o id é a identidade, o nome virou só rótulo (`SheetData.java: Pericia`). Renomear uma perícia
  **preserva o valor e o atributo** dela, porque o alinhamento casa por id (`SheetModel.java: align`),
  não por nome. Fichas salvas antes de 28/09/2026 não têm id: na leitura o id é preenchido **por
  posição**, na ordem em que a perícia estava gravada — e o save antigo foi gravado na ordem do modelo,
  então posição N continua sendo `pericia_N` (`SheetData.java: sanitizePericias`, `: freshPericiaId`).
- **A migração de 27/09/2026 tem perdas conhecidas, avisadas e não corrigíveis:**
  - os antigos lugares reservados `perícia N` e `skill N` são **descartados sem log**. A tela
    antiga mostrava as 20 linhas com `-`/`+` editáveis, então quem ajustou `skill 3` para 5
    **perde os 5**: não existe linha nova que receba esse número;
  - `Melee`/`Luta` e `Initiative`/`Iniciativa` **saíram do padrão**, então o valor que tinham
    (2 por padrão) é descartado do mesmo jeito. Nenhum apelido resolve isso: resolveria
    inventando uma 19ª e 20ª linha que o usuário não pediu.
  **Renomear, hoje, não perde nada:** o alinhamento casa por id (`SheetModel.java: align`), então
  trocar o nome de uma perícia preserva o valor e o atributo dela em todas as fichas — apelido não é
  mais necessário.
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

| Tecla | Ação | Arquivo:símbolo |
|---|---|---|
| `R` | Abre o **menu gráfico** do TableTop RPG. O cliente pede os dados da sessão e o servidor responde abrindo a tela com papel, modo e turno corretos | `TabletopRpgClient.java: onInitializeClient` (consumo `: onInitializeClient`; abertura da tela `: registerNetworking`) |
| `V` | Cicla o modo de câmera: `3rd Person` → `1st Person` → `Top-Down` → `Free`. Fora do modo espectador, alterna entre a câmera normal do jogo e a câmera Livre. Mostra o modo na barra de ação (`§b[Camera] §f...`) | `TabletopRpgClient.java: onInitializeClient` (consumo `: onInitializeClient`; ciclo `SpectatorCameraController.java: cycleMode`) |
| `Tab` na aba `Tabletop RPG` | Aba própria em Opções → Controles, com as duas teclas acima | `TabletopRpgClient.java: onInitializeClient`, `en_us.json`, chave `key.category.tabletop-rpg.rpg` |

### Teclas vanilla consumidas pelo mod

Só valem **enquanto o jogador está travado** (modo Investigação/Combate fora do turno). Fora desse
estado, o vanilla funciona normalmente.

| Tecla vanilla | O que o mod faz com ela | Arquivo:símbolo |
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
| `RpgMenuScreen` — menu principal no pergaminho | Tecla `R` (`TabletopRpgClient.java: onInitializeClient`) | Mostra `Role: MASTER/PLAYER`, `Mode` e `Turn` (`RpgMenuScreen.java: render`) e navega: **Mestre** vê `Players`, `Rolls`, `Settings`; **Jogador** vê `Player Sheet`, `Skills`, `Rolls`, `Settings` e `End Turn` quando é a vez dele (`: buildMenu`). O rótulo do botão da ficha é o literal **`Player Sheet`** desde 29/09/2026 (antes `Status`), e é o **único** texto `Status` que o jogador via: a classe continua se chamando `StatusScreen` e o título desenhado no topo da ficha continua `Sheet: <jogador>`. **Não** existe botão de ficha própria para o Mestre |
| `PlayerListScreen` — `Players` | Botão `Players` do menu, só para o Mestre (`RpgMenuScreen.java: buildMenu`, `: openPlayers`) | Lista os jogadores conectados; clicar em um abre a `Player Sheet` **dela** (o rótulo do botão no menu, ver a linha do `RpgMenuScreen`; a tela continua sendo a `StatusScreen`). O Mestre não entra na lista (`RpgNetworking.java: sendMenuToPlayer`) |
| `StatusScreen` — `Status` | Jogador: botão `Player Sheet` do próprio menu. Mestre: clicando num jogador em `Players` (`RpgMenuScreen.java: buildMenu`, `: openStatus`; `PlayerListScreen.java: openSheet`) | Ficha do personagem: **Player** (o nome de quem joga; primeiro campo, **antes** do nome do personagem, com caixa de texto **estreita** — metade da largura dos outros campos, com piso de 60px, e até 32 caracteres; o campo é texto comum, sai pelo mesmo `SheetFieldPayload` dos outros e herda a permissão de **dono ou Mestre**; `SheetData.java: TEXT_FIELDS`, `: withField`, e o rótulo literal **Player** vem de `SheetModel.java: labelOf`, porque `SheetData.java: labelOf` pergunta ao modelo **primeiro** e o `default` do modelo devolve a própria chave, então um caso novo ali seria código morto; `defaultSheet` continua colocando o nome do jogador no campo de nome do personagem), depois nome, raça, classe, `Background`, o bloco de vida, nível/XP, os atributos e as perícias do **modelo** (por padrão 6 e 18; o Mestre decide, ver `SheetEditorScreen`). **O bloco de vida ocupa 3 linhas por recurso** (29/09/2026, decisão do usuário): antes era o cabeçalho `Vitals` mais uma linha por recurso, com o par `-`/`+` de passo 1 em volta de uma barra de ~110px. Agora, para o HP e — só quando `model.isEnabled("mana")` — para a Mana: **(1) o título vem do modelo**, `model.hpLabel()` e `model.manaLabel()` (defaults `HP` e `Mana`, já editáveis no Sheet Editor, `SheetEditorScreen.java: labelField` com `field_hp` e `field_mana`); o cabeçalho `Vitals` **sumiu**, porque o título do recurso o substitui, e **nada foi criado no modelo** para isso; **(2) a linha do teto e da barra**, com o literal `Max`, o `EditBox` numérico do teto (`SheetData.MAX_RESOURCE`, o único caminho de editá-lo) e a barra ocupando o resto da largura da coluna, com `rowH - 2` de altura (era `rowH - 6`); **(3) a linha dos 6 botões** `-10` `-5` `-1` `+1` `+5` `+10`, em larguras iguais na largura toda da coluna (`StatusScreen.java: RESOURCE_STEPS`, `: addResourceBlock`, `: addResourceStepRow`, com o par campo/delta guardado no record `ResourceStep`). O clique passa por `CharacterSheetScreen.stepNumeric`, que agora **clampa ao piso e ao teto do próprio campo** (`numericFloor`/`numericCeiling`, sobrescritos só para `hp` e `mana`) e **não grava nem envia** quando o passo não muda nada: sem isso o `-10` numa Mana 0 gravava um valor otimista que sobrevivia à reconciliação, porque `reconcilePendingNumeric` valida pendentes contra o intervalo de **atributo** — a tela ficava presa mostrando `-10` com a barra vazia. Os 6 botões ficam **cinza no limite**, reescritos no render por `StatusScreen.java: applyResourceStepButtons`; e, como o usuário decidiu, são `Button` comuns e **não** repetem ao segurar. **A coluna esquerda agora rola** (29/09/2026), replicando o padrão que a coluna de perícias já usava: `StatusScreen.java: leftScroll`, `: maxLeftScroll`, `: clampLeftScroll`, `: isOverLeftPanel` e o branch em `: mouseScrolled`, que faz `leftScroll = next; rebuildWidgets();`. O `leftScroll` é aplicado dentro de `buildPanel`, e `maxLeftScroll` é `linhas totais - linhas visíveis`, então com a rolagem no fim a última linha cai dentro de `leftBottom` e não invade o botão `Back`. Rodar recria os widgets, então **a rolagem fica travada enquanto um campo tiver o foco** (`StatusScreen.java: isEditingField`): clicar em outro lugar libera. Em janela em que tudo cabe, `maxLeftScroll` é 0 e a roda não é roubada. **As duas colunas começam e terminam juntas** (29/09/2026, escolha do usuário): o cabeçalho da coluna de perícias tem a **mesma altura de linha** da primeira linha da esquerda (`perTitleH = rowH`), e não mais uma estimativa independente, e a esquerda **estica** as linhas para preencher a área útil (`rowH = disponível / linhas visíveis`), que é o que a direita já fazia. Antes elas só coincidiam por acaso, quando ambas batiam no `MAX_ROW_H` de 20, e em janela baixa a direita entrava até **8px mais embaixo** — era o que o usuário via como a esquerda "colocada mais pra cima". O rodapé fica igual **a menos de poucos pixels**, porque as duas colunas têm alturas de linha diferentes por decisão de projeto (a direita comprime para caber até 30 perícias) e sobra um resto de menos de uma linha em cada uma. `perVisibleCount()` passou a contar com os limites **internos** e o `perRowH` real, como o `buildPanel`: com os externos sobravam 8px e a última perícia podia ficar fora do alcance da rolagem. Atributos e perícias continuam mexendo por botões **`-` e `+`** (não mais setas `>` e `<`), e o botão que chega no limite fica **cinza/desligado**: o `+` no teto 30 e o `-` no piso, que é **-30 nos atributos** e **0 nas perícias**. Quem escreve esse estado é `StatusScreen.java: applyStepButtons`, chamado tanto por `applyExtraState` quanto pelo render, para o botão no limite não piscar. **Segurar o `-` ou o `+` repete o passo com aceleração** — só nas setas de atributos e perícias, ver [seção 5](#ficha-do-personagem). A coluna de perícias tem o cabeçalho **`Bonus`**, porque o número ao lado do nome é o bônus investido, não o resultado da rolagem — que ainda soma o atributo. A largura da caixa de número mede o pior caso de verdade, agora de **três caracteres** (`StatusScreen.java: valueBoxWidth`): antes media só a string do teto (`"30"`, 16px) e cortava o sinal, então `-30` aparecia como `-3`; as guardas de truncamento continuam existindo, agora só para proteger contra valor forjado muito grande. **O teto digitável é por campo, não genérico** (27/09/2026): o filtro das caixas numéricas era o mesmo para todos (`-?\d{0,9}`, até 9 dígitos), e agora o teto vem no parâmetro `ceiling` de `CharacterSheetScreen.java: createFieldBox`. As linhas de HP e Mana passam `SheetData.MAX_RESOURCE` (**9999**), que é o teto que o servidor já usava — e ele é conferido por **valor**, em `CharacterSheetScreen.java: withinCeiling`, não por contagem de caracteres (o sinal não conta para o teto: `-999` é o número 999). Na prática o que tem caixa é a caixa do `Max` de cada recurso (`StatusScreen.java: addResourceBlock`); o valor atual de HP e Mana **não tem caixa nenhuma** e muda pelos **6 botões de passo** (`-10` `-5` `-1` `+1` `+5` `+10`), que são sem teto. `level` e `xp` seguem **sem teto no cliente** (`NO_CEILING`, o limite antigo de 9 dígitos), porque o usuário pediu para não mexer neles; 99 e 999999 continuam valendo no servidor (`SheetData.java: MAX_LEVEL`, `MAX_XP`). **O texto dentro da barra degrada o formato, nunca o número** (`StatusScreen.java: drawValue`): primeiro o par inteiro `hp / hpMax`; se não couber, **só o valor atual**; e o corte de algarismos é o último caso, alcançável só por valor forjado fora da faixa legal. Nenhum nível mostra metade de um formato — exibir `1234 /` é pior do que exibir `1234`, porque é uma leitura plausível e **errada** de um recurso. **Rótulo longo quebra em 2 linhas centralizadas, ou sai truncado com reticências** (`CharacterSheetScreen.java: addWrappedLabel`, `: truncateWithEllipsis`): a largura reservada é medida pelo rótulo real, e o texto é quebrado ou cortado **dentro dela**, então um nome grande não invade mais o `-`, a barra nem a caixa de valor. O **título** da tela é desenhado **antes** dos widgets, então ele não fica por cima deles (`CharacterSheetScreen.java: render`, que `Status` e `Skills` herdam sem sobrescrever). O detalhe e o limite em janela baixa estão em [Rótulos e texto nas telas](#rótulos-e-texto-nas-telas). Abre a `SkillsScreen` (`StatusScreen.java: buildFooterExtra (botao Skills)`) e a `AttributePickerScreen` (`StatusScreen.java: openPericiaAttribute`) |
| `SkillsScreen` — `Skills` | Botão `Skills` do menu ou o botão `Skills` dentro da `StatusScreen` (`RpgMenuScreen.java: buildMenu`, `: openSkills`; `StatusScreen.java: buildFooterExtra`) | Lista **livre** de skills (nome + descrição): adicionar, remover e reordenar com as setas. Máximo de 24 (`SheetData.java: MAX_SKILLS`). A skill não tem valor nem atributo — quem tem isso é a perícia. **Rodapé com `Edit` e `Add`** (28/09/2026): as duas caixas de nome e descrição e o botão `Add` já serviam só para criar; agora o `Edit` carrega a skill **selecionada** nessas caixas e o `Add` vira `Save`, que substitui a skill **no mesmo lugar** da lista, sem perder a ordem. O `Edit` fica **desligado** (visível, cinza) sem seleção ou sem permissão, e um segundo clique nele é o `Discard`: volta ao modo `Add` e limpa as caixas. O modo de edição **não sobrevive** a um `init()`, então resize devolve a tela ao `Add` — um `Save` sobre texto que o jogador não digitou seria pior do que voltar ao começo. A seleção é guardada por **nome** e o índice é **reancorado pelo nome** a cada ficha que chega (`SkillsScreen.java: onSheetReceived`): é isso que impede o `Edit` de carregar e o `Save` de gravar na skill que deslidou de posição depois de um add, remove ou move. A ação vai ao servidor como `update`, que **recusa em silêncio** se o índice estiver fora da faixa ou se **o nome em diante não bater** com a skill naquele índice (`RpgNetworking.java: updateSkill`) — a defesa contra quem reordena a lista entre o clique e o `Save`. Renomear para um nome já usado por outra skill também é recusado (`SheetData.java: withSkill`), porque `sanitizeSkills` mantém a primeira e o resultado viraria dependente da ordem. A recusa é silenciosa, sem aviso nem som, igual ao resto do receptor. **A área de descrição tem fundo próprio** (`SkillsScreen.java: renderDescBackdrop`, cor `COL_DESC_BG`): um quadrado mais escuro, com moldura, desenhado **sempre** — inclusive sem skill selecionada, porque é ele que separa lista e descrição na tela — e o popup é desenhado por cima. `COL_TOOLTIP_BG` **não** foi mexida, porque é compartilhada com o tooltip de hover |
| `AttributePickerScreen` — `Attribute` | Botão de atributo de uma perícia, dentro da `StatusScreen` (`StatusScreen.java: openPericiaAttribute`) | Escolhe com qual atributo a perícia soma. A lista vem do **modelo**, não de um enum: são os atributos que o Mestre deixou (1 a 10), com o atual marcado por `>` (`AttributePickerScreen.java: init`, `: options`; `SheetModelHolder.java: current`) |
| `SheetEditorScreen` — `Sheet Editor` | **Só o Mestre**, pelo botão direito no item `sheet_editor` (`ModItems.java: SHEET_EDITOR`, uso em `ModItems.java: onUseItem`, que confere `SessionManager.isMaster` e chama `RpgNetworking.java: sendOpenSheetEditor`; o servidor reconfere o Mestre em `sendOpenSheetEditor`). O item usa a textura **placeholder** `minecraft:item/writable_book` (livro com pena), a pedido do usuário (`models/item/sheet_editor.json`), e aparece com o nome **Sheet Editor**. Os **dois** bugs que impediam isso, corrigidos em 28/09/2026, tinham a mesma causa — namespace e modelo. **Sprite:** o 1.21.11 carrega o modelo de item **só** de `assets/<namespace>/items/<id>.json`, e `assets/tabletop-rpg/items/sheet_editor.json` **não existia**, então o item era desenhado com o modelo ausente; a ausência é **silenciosa**, sem warning no log, porque o arquivo que falta nunca chega ao parser, e o `models/item/sheet_editor.json` continuava correto, só nunca lido — agora existe o arquivo de `items/`, que aponta para o modelo. **Nome:** a chave de tradução automática do item inclui o **hífen** do namespace, ou seja `item.tabletop-rpg.sheet_editor`; a chave usada era `item.tabletoprpg.sheet_editor`, sem hífen, e o jogo mostrava a chave crua em vez de `Sheet Editor`. **Não** há botão para isso no `RpgMenuScreen` | Edita o **modelo** da ficha (o formato, não os valores de ninguém): rótulo de cada campo de texto, `Race` e `Mana` on/off, o **modo do XP** (`SheetModel.java: XpMode`: `Number`, `Free text`, `Hidden`), e as listas de **atributos** (sigla + nome, com `+ Attribute` e `X`) e de **perícias** (nome, atributo padrão, com `+ Pericia` e `X`) (`SheetEditorScreen.java: buildContent`, `: attributeRow`, `: periciaRow`). **Seção `Value limits`** (28/09/2026, `SheetEditorScreen.java: limitField`, entre a seção de XP e a de atributos): três campos numéricos — **Attribute min** (-30), **Attribute max** (30) e **Pericia max** (30) — que **substituem as constantes fixas** de `SheetData`. Eles viajam no `SheetModel`, no NBT (3 chaves `optionalFieldOf`) **e** no pacote (3 `VAR_INT` em encoder **e** decoder, que neste arquivo são lambdas separadas: mudar um lado só compila e quebra em runtime), e a **ficha** carrega os mesmos três números, com o clamp no **construtor compacto do record** (`SheetData.java: composite`), o que faz qualquer caminho que reconstrói a ficha respeitar o teto sem mexer em cada método. **Teto invertido** (`min` maior que `max`) vira `[min, min]` e não intervalo vazio, senão o clamp levaria todo valor para o piso. O **mínimo da perícia continua 0 fixo**, e o campo do máximo do atributo não aceita `-`, então um intervalo todo negativo não pode ser digitado no teto. **Nome de perícia repetido** (28/09/2026): antes a caixa **voltava sozinha** e o modelo recusava a troca (`SheetModel.java: withPericiaText`); agora o repetido **entra** na caixa e no rascunho, o que trava o `Save` passou a ser a tela (`SheetEditorScreen.java: hasDuplicateName`, comparação sem diferenciar maiúsculas, como o resto do projeto), o aviso `duplicate pericia name` entrou entre o de nome vazio e o de `unsaved changes`, e `canDiscard` passou a considerar o caso — sem isso o Mestre ficava preso na tela sem saída. A identidade continua sendo o **id**: dois nomes iguais com ids diferentes convivem no modelo, então `/rpg roll <perícia>` pega a **primeira** (`MasterCommands.java: findPericia`). Uma coluna só, rolando junto (`SheetEditorScreen.java: applyScroll`), e a janela de visibilidade dos widgets usa a **altura real do widget** (`slot.widget().getHeight()`, 16px) em vez da altura da linha (`ROW_H`, 20), com um **critério único** para controle e cabeçalho (`SheetEditorScreen.java: fitsInPanel`); o título da tela também é desenhado **antes** do `super.render()`, então o texto não fica na frente do widget. Efeito observável: com a lista rolada, a caixa de texto não sai mais para fora do painel nem some atrás do título. A edição é **em memória** e só grava no `Save` (`SheetEditorScreen.java: save`, que envia `RpgNetworking.java: SheetModelSavePayload`); `Discard` volta ao último salvo, `Reset` volta ao modelo padrão e `Close` fecha (`SheetEditorScreen.java: buildFooter`). **Fechar sem salvar não é cancelar** (decisão do usuário em 27/09/2026): fechar com `ESC` — ou pelo botão `Close`, ou abrindo outra tela como o inventário, porque o gancho é a **saída** da tela e não só o `ESC` — e reabrir o item mostra de novo o que estava na tela, mas **não aplicado** — o modelo do servidor continua sendo o de `baseline` e nada é enviado (`SheetEditorScreen.java: captureDraft`, chamado por `onClose` e por `removed`, que registra a saída da tela, e `: takeDraft`, que a consome na abertura seguinte). Só o `Discard` volta ao salvo e só o `Save` aplica; os dois apagam o rascunho (`: clearDraft`). O `Reset` faz o **contrário**: põe o modelo padrão no rascunho, mas **não** mexe no estado salvo, então a tela continua marcada como suja (`SheetEditorScreen.java: isDirty`) e fechar depois do `Reset` **preserva** o rascunho com o padrão, que ainda não foi gravado no servidor. O rascunho vive **na tela, não em disco**: não vai para o `SheetModel`, nem para o `SheetData`, nem para pacote, e é descartado ao desconectar (`TabletopRpgClient.java: registerConnectionCleanup`, que chama `SheetEditorScreen.java: discardTransientState`) — sem isso, entrar no mundo B abriria o editor com o rascunho do mundo A e o `Save` gravaria A no `SavedData` de B. **Nome de perícia vazio não entra no modelo:** a caixa **fica** com o que foi digitado, o modelo continua com o último nome válido, e a tela rastreia a pendência por **posição** na lista (`SheetEditorScreen.java: pendingNames`, `: hasPendingName`, `: captureDraft`). Enquanto houver pendência o `Save` fica **desabilitado** e aparece o aviso `screen.tabletoprpg.sheet_editor.pericia_no_name` (`pericia without a name`), que tem prioridade sobre o aviso de `unsaved changes` (`: render`). O botão `X` de remover **continua funcionando**, porque remove por posição e não pelo nome: é o único caminho para sair de um nome inválido (`: periciaRow`). O `Discard` **também** foi habilitado nesse caso, senão apagar o nome e não mexer em mais nada desligaria `Save`, `Discard` e `Reset` ao mesmo tempo e não sobraria saída (`: canDiscard`, que vale "sujo **ou** com nome pendente"). O cliente **não** é autoridade: o servidor reconstrói o `SheetModel` ao desserializar o payload, o que dispara o construtor compacto e saneia rótulos, duplicatas e os tetos de 10/30 (`SheetModel.java: SheetModel`) |
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

- **A ficha tem 3 abas**, com duas setinhas (`<` e `>`) **dentro do painel, na base**, e o nome da aba ativa no meio: `Character` (a ficha inteira de hoje — identidade, vida, mana, progresso, atributos e a coluna de perícias à direita), `Backstory` (história, características físicas e inventário) e `Skills`. A setinha da ponta que não tem página fica desabilitada (cinza), para o jogador ver que acabou (`StatusScreen.java: TAB_NAMES`, `: addTabBar`, `: changeTab`). A barra reserva 20 px (`TAB_BAR_H`) para o conteúdo não parar embaixo das setinhas, e as abas são montadas por um despachante: `buildPanel` chama `buildCharacterTab` só na aba 1 e não cria widget nenhum nas outras duas (`StatusScreen.java: buildPanel`).
- **Cada aba guarda o próprio scroll** das duas colunas (`tabLeftScroll`/`tabPerScroll`), e a troca é feita em `changeTab`, não em `init()`: `init()` também roda em resize e troca de modelo, e guardar a posição ali a perderia. A roda só rola na aba que tem coluna (`: mouseScrolled`). O nome da aba é o único texto desenhado **fora** da lista que rola com o conteúdo (`: render`), para o rótulo não subir e descer junto com a coluna.
- **A aba `Info/Inventory` tem dois campos de texto grandes, empilhados na metade esquerda**: `Character Appearance` (em cima) e `Character Backstory` (abaixo). São `MultiLineEditBox` — caixa de texto de **várias linhas com barra de rolagem de verdade** (herda `AbstractScrollArea`), não o `EditBox` de uma linha dos outros campos, que não tem wrap nem rolagem. Teto de **2.000 caracteres** cada (`SheetData.java: MAX_TEXT`), com `setCharacterLimit(2000)` no cliente. O fundo do quadro é o do próprio widget (`setShowBackground(true)`): não existe moldura reutilizável neste projeto.
- **O texto grande não é enviado a cada tecla.** `setValueListener` só marca o valor como pendente, e o envio (`SheetFieldPayload`) acontece em **4 pontos**: clique fora da caixa (o foco saiu), troca de aba, `removed()` (ESC, Back, inventário, queda de conexão) e `rebuildWidgets()` como rede de segurança — este último porque ele é o funil de todo caminho que destrói widgets (resize, scroll, troca de modelo do Mestre), e o texto mora dentro do widget. Enviar 2.000 caracteres por tecla seria flood de pacote.
- **`appearance` e `backstory` moram dentro de `SheetData.Identity`, e não como componente novo do `SheetData`** (decisão de 30/09/2026): o bloco já é o texto do personagem e já tem NBT com `optionalFieldOf` por chave, então não muda o codec de topo — que tem ordem sensível e ~8 pontos de construtor em `withField`. Fichas antigas **sem** essas chaves carregam com `""` (`SheetData.java: IDENTITY_CODEC`). Os dois campos têm teto próprio (`cleanText`, 2.000); os outros cinco continuam em `MAX_NAME` (32), e `cleanText` faz `trim()` — então espaço no fim não sobrevive ao eco do servidor.
- `SheetFieldPayload.value` passou de `stringUtf8(64)` para `stringUtf8(2048)` (`RpgNetworking.java`): 64 caracteres não comportavam 2.000, e a folga deixa o `cleanText` truncar no servidor **antes** do pacote estourar.
- O rótulo dos dois campos vem de um **fallback no topo de `SheetData.labelOf`**, antes de consultar o modelo: `SheetModel.labelOf` devolve o próprio nome do campo no `default`, então o `switch` da base nunca era alcançado e a tela desenharia "appearance" cru.
- **A coluna direita da aba `Info/Inventory` é o `Inventory`**, desenhado de cima para baixo (`StatusScreen.java: addInventoryColumn`): título `Inventory`, a linha do **`Max Weight`** (rótulo + caixa, com filtro que só aceita o padrão de peso), a linha de resumo **`Weight: X.XX / Y.YY`**, o botão **`+ Item`** e a lista de itens. A lista rola na coluna (`rightScroll`), porque 50 itens não cabem na tela.
- **Peso é decimal com 2 casas** e é **sempre visual**: `Inventory.overweight()` é o único lugar que decide o vermelho, a linha de resumo é pintada de vermelho quando o peso atual passa do máximo, e **a ficha continua salvando normalmente** — passar do peso nunca impede nada. O arredondamento é `Math.round(w * 100f) / 100f` no caminho do `float`, que é o mesmo da rede e do NBT; `totalWeight()` arredonda a soma **só no final**, senão 50 itens acumulando erro de float fariam o "passou do máximo" piscar.
- **Criar e editar item abre uma janela separada** (`InventoryItemScreen`), com `Name`, `Type`, `Weight` e `Description` — a descrição é `MultiLineEditBox` com teto de `MAX_TEXT` (2.000) e barra de rolagem. A lista mostra nome, tipo, peso e descrição (quebrada em 1-2 linhas), com `Edit` e `Del` por item; **`Del` pede confirmação em dois cliques** (o primeiro deixa o botão vermelho).
- **O inventário é dado da ficha, não do modelo.** Ele é o 10º componente de `SheetData`, guardado em NBT na chave `inventory`, com `optionalFieldOf` — **ficha salva antes desta fase carrega com inventário vazio**. E `SheetModel.align()` **copia** o inventário ao remontar a ficha: sem essa linha, trocar o modelo do Mestre apagaria o que o jogador carregava, porque o `align` roda no login e a cada edição do modelo.
- As ações chegam por `SheetItemPayload` (`RpgNetworking.java`), com `Op` = `ADD`/`UPDATE`/`REMOVE`: `ADD` vem com `index = -1`, `REMOVE` vem com o item vazio, e `UPDATE` vem com índice e item. O servidor checa `canEditSheet` como nos outros payloads de ficha e **rejeita índice inválido em silêncio**. O `Max Weight` **não** usa payload novo: entra pelo `SheetFieldPayload` como `"maxWeight"`.
- A ficha é uma **camada separada da vida do vanilla**: o HP e a Mana da ficha são a fonte da verdade
  do personagem, e o jogador já é imune a dano físico (`class DamageControlHandler (Javadoc da classe)`).
- **HP pode ser negativo** (piso `-999`) e pode **passar do máximo** (`12/10` = 10 permanentes + 2
  temporários). O piso existe só para não estourar o protocolo; o que importa é que `hp <= 0` é
  deitado (`SheetData.java: MAX_RESOURCE`, `: composite`).
- A Mana tem piso 0 e também pode passar do máximo (`SheetData.java: composite`).
- **HP e Mana têm teto de 9999** (`SheetData.java: MAX_RESOURCE`), e o teto é do **valor absoluto**,
  não do `hpMax`/`manaMax` — é por isso que `12/10` é legal. O servidor recusa em silêncio o que
  passar disso, então a **caixa da tela recusa o dígito que estouraria o teto** — e recusa **em
  silêncio**: nada muda na tela quando a tecla é engolida, sem aviso, som ou dica, porque a tecla
  nunca chega a ser inserida. Isso **não trava** a edição: apagar passa, já que o número que sobra é
  menor que o teto, então trocar `9999` por outro valor grande continua possível — apaga e digita de
  novo. O teto é **por campo** (`CharacterSheetScreen.java: withinCeiling`, recebido de
  `StatusScreen.java: addResourceBlock`), e `level` e `xp` continuam **sem teto no cliente**: 99 e
  999999 valem no servidor (`SheetData.java: MAX_LEVEL`, `MAX_XP`). O cliente continua **não** sendo
  autoridade — ele só evita a digitação que seria descartada.
- **Vida e mana mudam por 6 botões de passo, e o passo é limitado pelo próprio recurso**
  (29/09/2026, `StatusScreen.java: RESOURCE_STEPS`, `: addResourceStepRow`; o clamp fica em
  `CharacterSheetScreen.java: stepNumeric`, pelos ganchos `numericFloor`/`numericCeiling`, que
  são ilimitados por padrão e sobrescritos só para `hp` e `mana`): `-10` `-5` `-1` `+1` `+5`
  `+10`, em `Button` comum e **sem** repetição ao segurar — decisão reconfirmada pelo usuário
  quando a aceleração foi oferecida. O piso e o teto são os do recurso (HP de `-999` a `9999`,
  Mana de `0` a `9999`), e um passo que os cruzaria **não é enviado**: o clique vira no-op e o
  botão fica cinza. Vale tanto para o jogador na **própria** ficha quanto para o **Mestre**
  editando a de outro, porque é a **mesma tela**. **Não** existe campo digitável para o valor
  atual de vida ou mana: o único `EditBox` do bloco é o do **teto**, e ele é o único caminho de
  mudá-lo.
- Os **atributos têm piso -30 e teto 30 por padrão, e quem decide é o Mestre** (28/09/2026, `SheetModel.java: attributeValueMin`/`attributeValueMax`): antes eram constantes fixas em `SheetData` (`VALUE_MAX`/`VALUE_MIN`) lidas por uns 12 pontos da ficha. Agora os limites viajam no **modelo**, a ficha carrega os mesmos três números, e o clamp fica no **construtor compacto do record** (`SheetData.java: composite`) — então qualquer caminho que reconstrói a ficha respeita o teto, inclusive a leitura de NBT de um mundo salvo antes da mudança. Os records internos ficaram só com **teto absoluto** largo (-999 a 999). **Baixar o teto corta valores já salvos**, porque `SheetModel.java: align` reescreve os limites na ficha que reconstrói. Eles
  viraram modificadores somados às rolagens de perícia. Quem escreve o `active` dos botões é
  `StatusScreen.java: applyStepButtons`, chamado tanto por `applyExtraState` quanto pelo render, para o
  botão no limite não piscar branco por um frame a cada eco do servidor: **os dois botões desligam no
  limite**, o `+` em 30 e o `-` em -30. Quantos atributos existem é decisão do modelo (6 no padrão).
- **O valor da perícia é de 0 a 30 por padrão**, com **teto configurável** pelo Mestre no Sheet Editor e **piso 0 fixo** (`SheetModel.java: periciaValueMax`, padrão `DEFAULT_PERICIA_VALUE_MAX`; o `VALUE_MIN`/`VALUE_MAX` do record `SheetData.Pericia` viraram teto absoluto de 0 a 999).
- **Segurar o `-` ou o `+` repete o passo, começando devagar e acelerando** (28/09/2026,
  `HoldStepButton.java`, um `Button` que reproduz o `onPress` enquanto fica pressionado): o
  **primeiro passo é imediato** na pressionada, depois há **400 ms de silêncio** antes de repetir
  (`: HOLD_DELAY_TICKS`, 8 ticks a 20 Hz) para um clique com intenção não acelerar sem querer, e
  daí a repetição começa em **10 passos/s** (`: STEPS_PER_SECOND_MIN`) e **rampa** até o teto de
  **25 passos/s** (`: STEPS_PER_SECOND_MAX`), que ela alcança **2 s** depois do fim do silêncio
  (`: RAMP_TICKS`, 40 ticks) e de lá fica — **2,4 s** no total depois da pressionada. A rampa é
  **progressiva, não linear** no tempo: um clique solto dá um passo só, e o crescimento é
  gradual, não um fluxo constante desde o começo (`: stepsPerSecondAt`).
- **Vale apenas nas setas de ATRIBUTOS e PERÍCIAS** (`StatusScreen.java: addAttributeRow`,
  `: addPericiaRow`). **HP e Mana NÃO têm aceleração** — lá os botões são `Button` comuns
  (`StatusScreen.java: addResourceStepRow`) e um passo exige um clique — desde 29/09/2026 são
  **6 botões** (`-10` `-5` `-1` `+1` `+5` `+10`) em vez do par, mas a decisão de **não** acelerar
  vida e mana foi **reconfirmada** pelo usuário nessa mesma rodada, então vale como está. Isso é
  **decisão deliberada do
  usuário**, não esquecimento: o modelo de vida e mana muda em breve, e acelerar um campo que vai
  ser reescrito só produziria trabalho jogável descartável. Vale tanto para o jogador na **própria**
  ficha quanto para o **Mestre** editando a ficha de outro jogador, porque é a **mesma tela**.
- **Para no limite sozinho:** ao chegar no **piso ou no teto configurados** (por padrão **-30 e 30** nos atributos e **0 e 30** nas perícias — `SheetModel.java: attributeValueMin`, `: attributeValueMax`, `: periciaValueMax`; a ficha carrega esses números e o cliente cai no default enquanto ela não chega; na vida e na mana o limite é o do próprio recurso, e quem escreve o cinza dos 6 botões é `StatusScreen.java: applyResourceStepButtons`, com o passo calculado sem enviar nada em `canStepResource`) a
  rajada para e o botão fica cinza, sem passar do teto. A decisão vem do **valor otimista**, não do
  `active` do widget (`: canStepAttribute`, `: canStepPericia`), porque o cinza do limite só chega
  no frame seguinte e a rajada passaria 1 ou 2 passos do teto.
- **Continua valendo com o cursor fora do botão:** o 1.21.11 entrega o `mouseReleased` pelo **foco**
  do widget e não pela posição do mouse, então arrastar para fora antes de soltar **não** cancela a
  rajada. É o vanilla que faz isso, e vale a pena mencionar porque é o oposto do esperado num botão
  pequeno.
- A rajada roda pelo **`tick()` da tela a 20 Hz** (`CharacterSheetScreen.java: tick`, que percorre
  `children()` porque `AbstractWidget` não tem `tick()`), e o estado de segurada mora **dentro do
  botão**. Isso tem uma consequência boa: quando os widgets são reconstruídos — rolar a coluna de
  perícias, o Mestre salvar o modelo, redimensionar a janela — a rajada **morre em silêncio** em vez
  de ficar contando sozinha para sempre, já que não sobra ninguém para parar. Há ainda um watchdog
  (`HoldStepButton.java: tick`, que pergunta o botão esquerdo ao GLFW): se o evento de soltar se
  perder (soltar fora da janela, `alt+tab` no meio da rajada), a rajada para no mesmo tick em vez de
  correr sozinha até o teto.
- **O teclado não repete:** apertar e segurar Enter ou Espaço num botão focado dá **um** passo, sem
  rajada (`HoldStepButton.java: onPress` só abre a rajada quando o evento é um `MouseButtonEvent`;
  em 1.21.11 não existe rota de release por teclado que chegue ao botão, então a rajada nunca
  pararia). **Não existe Shift como passo grande.**
- **Reconciliação do valor otimista** (mecanismo interno, 28/09/2026). A ficha guarda um valor
  otimista local para o número não "voltar" atrás do servidor; com a rajada longa, cada passo manda
  um payload e o eco do servidor chega cerca de **3 passos atras**. O eco **só** apaga o valor
  otimista quando o valor autoritativo **iguala** o pendente (`CharacterSheetScreen.java:
  keepPending`): enquanto a rajada está à frente o pendente é mantido, e como o servidor aplica em
  ordem, o último eco iguala o último enviado e a reconciliação se resolve sozinha, sem timeout
  (`StatusScreen.java: reconcilePericiaValues` para as perícias). **Antes** cada eco **apagava** o
  valor otimista, então a rajada reenviava valores velhos, o número subia e voltava na tela e a
  aceleração não se materializava. O pendente também é **descartado se estiver fora da faixa legal
  do campo**, para o cliente nunca deixar um número inválido preso na tela.
- **A lista de perícias não é mais fixa em código**: quem decide é o **Mestre**, pelo item `sheet_editor`
  (`ModItems.java: SHEET_EDITOR`, uso em `ModItems.java: onUseItem`), na `SheetEditorScreen`. Por padrão
  uma ficha nasce com as 18 perícias básicas de D&D 5e (`SheetModel.java: defaults`) e 6 atributos, e o
  Mestre pode **criar, renomear e remover** perícias, trocar o atributo padrão de cada uma, **criar e
  renomear** atributos (sigla e nome por extenso), renomear os campos de texto, ligar/desligar `Race` e
  `Mana` e escolher o **modo de exibição do XP** — `Number` (botões `-`/`+`), `Free text` (caixa de texto) ou
  `Hidden` (a linha some) (`SheetModel.java: XpMode`).
  - **Limites do modelo:** de **1 a 10 atributos** (`SheetModel.java: MAX_ATTRIBUTES`, `MIN_ATTRIBUTES`) e
    de **1 a 30 perícias** (`SheetModel.java: MAX_PERICIAS`, `MIN_PERICIAS`); rótulo e nome com no máximo
    32 caracteres (`SheetModel.java: LABEL_MAX`). **Nome de perícia repetido** é recusado e a caixa
    volta ao texto anterior (`SheetModel.java: withPericiaText`). **Nome vazio, não** (decisão do
    usuário em 27/09/2026): a caixa **fica** com o que foi digitado, o modelo continua com o último
    nome válido, o `Save` fica desabilitado e o aviso `pericia without a name` aparece até o nome
    voltar a ser válido (`SheetEditorScreen.java: pendingNames`, `: hasPendingName`). Só espaços
    contam como vazio, porque o modelo faz `trim()`. O motivo de um caso ficar pendurado e o outro
    não: duas linhas repetidas penduradas não têm ordem de resolução, então o modelo recusa por
    identidade; o vazio não desloca as linhas seguintes e sai pelo `X`. **O `Discard` fica
    habilitado mesmo com a cópia editada igual à salva** — o nome pendente também conta como "o que há
    para descartar" (`SheetEditorScreen.java: canDiscard`). Sem isso, apagar um nome sem mexer em mais
    nada deixaria `Save`, `Discard` e `Reset` desligados juntos: um beco sem saída.
  - **O `id` do atributo não é editável, o rótulo é.** Ele é a chave com que a ficha do jogador guarda o
    valor, então renomear a sigla não perde nada; atributo criado pelo Mestre nasce com id gerado
    (`attr_1`, `attr_2`, …) que nunca é reciclado, para o atributo novo não herdar o valor do que saiu
    (`SheetModel.java: addAttribute`, `nextFreshAttributeIndex`).
  - **Quem remove um atributo** faz as perícias que somavam com ele passarem a somar com o primeiro da
    lista, em vez de ficarem apontando para um atributo que não existe mais
    (`SheetModel.java: removeAttribute`).
  - **Onde o modelo vive:** é do **mundo**, não de uma ficha — um `SavedData` do overworld
    (`SheetModelStore.java: get`, `update`, `SheetModelStore.java: TYPE`), então sobrevive a restart e a
    troca de Mestre. Ele é aplicado a **todas** as fichas: quando o Mestre salva, o servidor realinha as
    fichas já carregadas em memória (`SessionManager.java: realignAllSheets`, via `SheetModel.java: align`),
    que dá as perícias novas, tira as removidas e **preserva o valor das que sobreviveram pelo id**
    (`SheetModel.java: align`). O modelo em uso dos dois lados fica em `SheetModelHolder.java: current` e é
    enviado no login e a cada edição (`RpgNetworking.java: sendSheetModel`, `broadcastSheetModel`).
  - `SheetData.java: sanitizePericias` **continua existindo como rede de segurança**, mas não prende mais a
    ficha a uma lista: ele só descarta nulo, nome vazio e nome repetido, e corta no teto do modelo. Quem
    casa a ficha com a lista do Mestre é `SheetModel.java: align`, exposto por `SheetData.java: aligned`: a
    ficha nova já nasce pelo modelo (`SheetData.java: defaultPericias`), a carga do NBT alinha na hora
    (`mixin/PlayerSheetPersistenceMixin.java: tabletopRpg$loadSheet`) e cada edição do modelo realinha as
    fichas em memória.
- **Cada perícia tem um id próprio, gerado uma vez** (`pericia_1`, `pericia_2`, …,
  `SheetModel.java: PericiaDef`, `: freshPericiaId`; na ficha, `SheetData.java: Pericia`). O id é a
  identidade, o nome virou só rótulo. O que isso muda no uso:
  - **Renomear uma perícia preserva o valor e o atributo dela** (e vice-versa) — era o bug principal
    do alinhamento por nome. Trocar o atributo pelo dropdown também preserva o vínculo (ele já era por
    id de atributo, agora é consistente). Vale para o Mestre renomeando no Sheet Editor e para o
    jogador.
  - **O editor continua recusando nome repetido**, por decisão do usuário: o id resolveu o problema do
    rename, e a UX não mudou (`SheetModel.java: withPericiaText`).
  - **A migração das fichas antigas funciona por posição, não por nome.** Fichas gravadas antes de
    28/09/2026 não têm id; na leitura o id é preenchido por posição, antes de qualquer deduplicação
    (`SheetData.java: sanitizePericias`, `: freshPericiaId`). É seguro por construção: o save antigo
    foi gravado na ordem do modelo (o alinhamento antigo percorria a lista do modelo), então posição N
    continua sendo `pericia_N`. O casamento por nome com os apelidos antigos
    (`SheetData.java: periciaByNameOrLegacy`) **não é o que faz a migração funcionar hoje**: ele só
    dispara com a lista de perícias vazia, caso em que não acha nada — fica no código por princípio de
    defesa, inerte (`SheetModel.java: align`).
  - **Limitação residual:** remover uma perícia do modelo com o jogador offline e depois adicionar uma
    nova — a nova pega o menor número livre, que pode ser o da removida, e nasce **herdando o valor da
    removida** em todas as fichas, porque o contador é reusado (`SheetModel.java: addPericia`). Remover
    sem adicionar é seguro: os sobreviventes mantêm id e ordem, e o único valor perdido é o da
    removida, que é o efeito desejado da remoção. **Contorno hoje:** renomear em vez de remover e
    recriar — o rename preserva o id.
  - **O mesmo defeito existe nos atributos, e está documentado:** se o Mestre remove um atributo e
    adiciona outro, o novo pode nascer com o id do removido e herdar o valor dele nas fichas
    (`SheetModel.java: addAttribute`).
- **`Background` é um campo de texto livre da identidade** (`SheetData.java: Identity`), gravado no
  mesmo lugar dos outros campos de texto e editável na `StatusScreen` (`StatusScreen.java: buildPanel`).
  Não tem efeito mecânico: é só um rótulo descritivo que o Mestre consulta.
- **Quem vê e quem edita:** o dono vê e edita a própria ficha; o Mestre vê e edita a de qualquer
  jogador; **ninguém mais** consegue abrir a ficha de outro jogador nem por pacote forjado
  (`RpgNetworking.java: resolveSheetTarget`). Toda alteração é reenviada ao dono e ao Mestre, então a tela
  atualiza ao vivo dos dois lados (`: sendSheetTo`).

### Rótulos e texto nas telas

Os rótulos das telas de ficha são escritos pelo **Mestre** (`SheetEditorScreen`), então nome grande é
caso normal, não exceção. Antes a largura reservada ao rótulo era medida pelo rótulo real, mas
**limitada a `leftW / 3`** (`StatusScreen.java: fieldLabelWidth`), e o texto era desenhado **sem
nenhum corte**: com `Pontos de Determinação (PD)` no rótulo de Mana o texto media 140px numa coluna
reservada de 124px e invadia o botão `-` em 16px. O caminho agora é
`CharacterSheetScreen.java: addWrappedLabel`, e ele nunca deixa o texto passar da largura reservada ao
rótulo:

- **Cabe na largura?** Desenha como sempre, alinhado à esquerda e na linha de sempre — o layout
  aprovado não muda.
- **Não cabe?** Quebra em **2 linhas centralizadas** com `Font.split`, dentro da largura reservada, e
  o bloco de 2 linhas é centralizado na altura da linha. A caixa de valor **não se move**: ela já
  ocupa a linha inteira, então os centros batem e as colunas continuam alinhadas pixel a pixel.
- **Ainda não cabe em 2 linhas?** A 2ª linha vira o **resto** do texto, cortado na largura reservada.
- **Não cabe nem em 2 linhas?** Volta a **uma linha só, agora cortado com reticências** (`...`,
  `CharacterSheetScreen.java: truncateWithEllipsis`), que é três pontos em vez de reticências
  tipográfico: o ponto é o glifo mais barato da fonte padrão, a marca gasta 6px e sobra mais texto
  visível. A marca é paga **antes** de cortar o texto, para o resultado inteiro caber.

A marca de corte é o ponto, não um detalhe: **um rótulo cortado em silêncio é indistinguível de um
rótulo completo**, e no editor de modelo isso é o pior dos dois — o Mestre lê um nome de perícia
truncado como se fosse o nome inteiro. As reticências mostram a perda. O mesmo vale para o nome da
perícia na coluna da direita, que é justamente onde o Mestre confere o que escreveu
(`StatusScreen.java: drawPericias`, com `: truncateWithEllipsis`) — mas ali ele **nunca** quebra em 2
linhas como os outros rótulos: a coluna é compacta e a altura da linha pode chegar a 9px, onde a 2ª
linha cairia em cima da seguinte, então cortar é o único jeito de não invadir os botões.

**Limitação conhecida: o wrap de 2 linhas depende de janela alta.** O passo entre as duas linhas
encolhe junto com a altura da linha (`CharacterSheetScreen.java: labelBlockAdvance`), mas nunca abaixo
de `MIN_LABEL_ADVANCE`, porque duas linhas de tinta de 8px com passo menor se **sobrepõem** e viram
uma mancha — nenhum ganho de espaço justifica isso. O wrap só fecha com `rowH` 17 ou mais, e na
resolução de referência do projeto (480x270, **escala de GUI 4**) a linha da ficha cai para `rowH`
**12 ou 13** com as 18 perícias do padrão, onde duas linhas de 8px **não cabem fisicamente**. Nessas
alturas (**escala de GUI 3 ou 4**) o wrap **não acontece** e o rótulo **sai truncado
com reticências** — nunca invadindo a coluna da frente. É uma escolha deliberada: um rótulo truncado
e honesto vale mais do que um rótulo sobreposto ou ilegível.

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

- **A ficha do personagem por completo** — identidade, HP, Mana, nível/XP, os atributos, as perícias e as
  skills. É gravada no **NBT do próprio jogador**, que o vanilla já salva no logout e
  no autosave (`mixin/PlayerSheetPersistenceMixin.java: KEY`, chave `tabletoprpg_sheet`). Há ainda
  uma gravação explícita no encerramento do servidor, para o `Ctrl+C` não perder a última alteração
  (`SheetPersistenceEvents.java: register`).
- **O modelo da ficha** (rótulos, atributos e perícias que o Mestre configurou) — é do mundo, não de uma
  ficha: vive num `SavedData` do overworld (`SheetModelStore.java: get`, `SheetModelStore.java: TYPE`), então
  sobrevive a restart e à troca de Mestre. Quem entra recebe o modelo atual no login
  (`SheetModelStore.java: register`, que chama `RpgNetworking.java: sendSheetModel`).
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
- **O rascunho do editor de modelo também é descartado ao desconectar** (`TabletopRpgClient.java:
  registerConnectionCleanup`, que chama `SheetEditorScreen.java: discardTransientState`). Ele é
  estado da sessão de mundo, não da tela: sem o reset, entrar no mundo B abriria o editor com o
  rascunho do mundo A e o `Save` gravaria o modelo de A no `SavedData` de B — e as marcas de
  "perícia sem nome", que são chaveadas por posição, cairiam em linhas que talvez nem existam em B.
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

Os **dois** blocos de estado que **são** gravados em disco são a **ficha do personagem**, no NBT do jogador,
e o **modelo da ficha** (rótulos, atributos e perícias decidedos pelo Mestre), num `SavedData` do overworld
(`SheetModelStore.java: get`) — então o modelo **sobrevive ao restart** e não entra nesta lista. E vale
lembrar: **não existe lista de iniciativa nem ordem de turno** — o turno é um único jogador
ativo, então não há fila para se perder.

### Quem pode ver e editar a ficha de quem

| Quem | Lê a própria ficha | Lê a ficha de outro | Edita a própria | Edita a de outro |
|---|---|---|---|---|
| Mestre | sim | **sim** | sim | **sim** |
| Jogador comum | sim | **não** (nem por pacote forjado) | sim | não |

Implementação: `RpgNetworking.java: resolveSheetTarget`. A permissão é reconferida no momento do envio
(`: canViewSheet`), e não só quando a tela é aberta.
