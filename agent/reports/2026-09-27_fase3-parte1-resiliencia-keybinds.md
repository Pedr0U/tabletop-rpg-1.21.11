# FASE 3 (parte 1): resiliência de conexão, aba de key binds e catálogo

**Data:** 27/09/2026, 12:20–13:10
**Partiu de:** commit `5fad0f8`, tag `checkpoint-20260927-1220-antes-da-fase3-resiliencia` (C04)
**Branch:** `main` — checkpoint **local, sem push**
**Estado final:** 4 arquivos modificados + 1 novo, **sem commit**

---

## Objetivo

Começar a FASE 3 ("Ajustes de Conforto, Conexão e Regras Extras"), parte 1, com três
itens: catalogar funcionalidades e comandos, dar uma aba própria das key binds ao mod
na tela de Controles, e revisar a resiliência de conexão quando um jogador cai no meio
do combate.

## Escopo aprovado

O agente principal leu as duas memórias, o `HANDOFF.md` e versionou o estado atual
antes de qualquer edição. Depois pesquisou o código (sem editar) e presented quatro
decisões, todas respondidas pelo usuário antes da implementação:

| Decisão | Escolha do usuário |
|---|---|
| Turno do jogador que cai | **Reservar a vez dele**; o Mestre pode pular |
| Mestre que cai | **Manter como está** (perde cargo, sessão vai a FREE, reset) |
| Key binds | **Aba própria no Controles** |
| Aura na reconexão | **Incluir nesta fase** |

## Descoberta que mudou o escopo

**Não existe lista de iniciativa/ordem de turno no código.** O turno é **um único
`UUID`** (`SessionManager.activePlayerUuid`, `SessionManager.java:31`), sem fila e sem
índice. "Preservar a ordem do turno" neste código significa preservar esse único UUID.
A iniciativa ordenada é item de FASE 3 ainda não implementado, e o documento criado
nesta sessão diz isso explicitamente para não gerar expectativa errada.

## Causa raiz do travamento de sessão

`RpgNetworking.java:699-703` (código anterior) fazia, na desconexão do jogador ativo:

```java
SessionManager.clearActivePlayer();
CombatController.clearPlayerAnchor(player.getUUID());
```

Como o modo da sessão **não** voltava para `FREE` nesse ramo (só no ramo do Mestre), o
resultado era: modo `COMBAT` com `activePlayerUuid == null` → `canPlayerAct` falso
para todos → **ninguém podia agir e ninguém tinha turno**. A sessão ficava travada até
o Mestre rodar `/rpg turn give` de novo. Esse era exatamente o defeito oposto ao
requisito do usuário ("ao desconectar, NÃO limpar o estado do jogador").

## Alterações

### 1. `src/main/java/com/pedro/tabletoprpg/RpgNetworking.java`

- **DISCONNECT:** removido o bloco `isActivePlayer → clearActivePlayer +
  clearPlayerAnchor + changed = true`. A vez fica reservada. O ramo do Mestre e o
  `if (changed) { sendToAll; sendAuraStateToAll; }` ficaram intactos, porque `changed`
  agora só é setado no ramo do Mestre.
- **DISCONNECT:** novo aviso público quando quem cai tinha o turno reservado:
  `[RPG] <nome> disconnected. Their turn is reserved; the Master can skip it with
  /rpg turn revoke.`
- **JOIN:** nova chamada `sendAuraStateToPlayer(server, handler.getPlayer())` depois de
  `sendActivePlayerToPlayer`.
- **Novo método** `sendAuraStateToPlayer(MinecraftServer, ServerPlayer)` acima do
  `sendAuraStateToAll`, mais um helper privado `sendAuraState(server, player, payload)`.

### 2. `src/client/java/com/pedro/tabletoprpg/client/TabletopRpgClient.java`

- **DISCONNECT:** antes limpava só `downedPlayers`, `downed` e `locked`. Agora também
  limpa `activePlayerUuid`, `gameModeOrdinal` (com sentinela `-1`), `auras`,
  `lastHoveredId`, `hoverMaxDistance`, `dayNightCycleEnabled`,
  `playersCanBreakBlocks`, `playersCanPlaceBlocks`, `weatherState` e chama
  `SpectatorCameraController.reset()`. Sem isso o cliente reconectava carregando lixo
  da sessão anterior.
- **Aba própria de key binds:** novo campo `rpgCategory`, registrado por
  `KeyMapping.Category.register(Identifier.fromNamespaceAndPath("tabletop-rpg","rpg"))`
  **fora** do `try { } catch (Throwable)` das teclas (esse catch mascararia a exceção e
  deixaria as teclas nulas). As duas teclas passaram de `KeyMapping.Category.MISC` para
  `rpgCategory`. **As translation keys não mudaram**, e isso é deliberado: o vanilla
  persiste o binding em `options.txt` pela chave `key_key.<translationKey>`, então
  mudar a translation key perderia o binding do usuário.

### 3. `src/client/java/com/pedro/tabletoprpg/client/SpectatorCameraController.java`

Novo `public static void reset()`: zera `targetIndex`, `active`, `wasLocked`,
`lastGameModeOrdinal`, `freeCamInitialized`, `thirdPersonLookInitialized` e
`freeCamPos`; **preserva** `mode`, `orbitalEnabled`, `topDownZoom` e
`transitionSpeed`, que são preferências do jogador. Não chama `deactivate` (exige
`Minecraft`): o `tick` já desliga quando `client.player == null`.

O motivo de `targetIndex` precisar zerar: ele é um **índice**, e o `entityId` do
jogador muda a cada login. O índice guardado apontava para outra pessoa ao reconectar.

### 4. `src/main/resources/assets/tabletop-rpg/lang/en_us.json`

Nova chave `"key.category.tabletop-rpg.rpg": "Tabletop RPG"`. Sem ela a aba nova
apareceria com a chave crua — o mesmo defeito que motivou a criação do lang em
26/09/2026.

### 5. `FUNCIONALIDADES-E-COMANDOS.md` (NOVO, raiz do projeto)

Catálogo de referência do estado atual: visão geral da sessão e dos 3 modos, os 16
comandos `/rpg` com permissão e `arquivo:linha`, teclas (do mod e as vanilla
consumidas enquanto travado), as 8 telas, as regras de jogo já decididas e a seção
**"Estado por conexão"** (o que persiste, o que se perde, o que não persiste).

Ficou na raiz do projeto, e não em `agent/`, porque `agent/` é reservado a memória de
engenharia e relatórios datados; um catálogo de funcionalidades não é nenhum dos dois.

## Correção feita durante a revisão do diff

A primeira versão (produzida pelo subagente) fazia `sendAuraStateToAll` delegar ao
método por jogador. Isso chamava `CombatController.getAuraData(server)` **uma vez por
jogador** num broadcast e perdia o log de quantas auras eram enviadas. O agente
principal reverteu para montar o payload **uma vez** e só trocar o `send`, com um
helper privado. Build refeito depois da correção.

## Divergências encontradas e corrigidas depois

O usuário achou os dois pontos finais do resumo opacos demais, e a conversa levou a duas
correções. Vale registrar o que mudou em relação à primeira versão deste relatório.

### Correção A — o texto do "mestre 1024 blocos"

**Decisão do usuário: manter o comportamento, corrigir só o texto.**

O código de fato envia `SessionManager.getHoverDistance()` para todos, inclusive o Mestre
(`RpgNetworking.java:1209`), com padrão 32 (`SessionManager.java:39`). Zero ocorrências
de `1024` em `src/`. Os Javadocs de `RpgNetworking.HoverConfigPayload` (`:234-243`) e de
`MasterCommands.setHoverDistance` (`:647-655`) afirmavam o contrário e foram reescritos
para dizer que o valor vale para todos, com a data da decisão no texto.

### Correção B — modificador negativo na rolagem

**Decisão do usuário: corrigir no servidor**, depois de eu explicar que "corrigir só na
tela" era impossível.

Por que era impossível: quem calcula e anuncia é o servidor. `rollDice` devolve a
mensagem por `return` e quem publica é o chamador (`rollFormula:607-613` ou `openRoll:625`),
via `broadcast` para todos ou `sendSystemMessage` para o Mestre. **O mod não tem nenhum
payload de rolagem** — os 20+ payloads de `RpgNetworking` são de menu, tempo, aura,
destaque, blocos, clima, câmera e ficha. O cliente não tem como ajustar um texto que o
servidor já transmitiu, e rolar local quebraria a autoridade do servidor e o
compartilhamento na mesa.

Antes de mexer, um diagnóstico descartou um medo maior: **o "atributo negativo" não
quebra nada.** `rollSkill` (`MasterCommands.java:511-513`) soma direto em `long` e nunca
monta string de fórmula nem chama `rollDice`. O bug dos botões era o único.

**O bug que eu quase entreguei.** A primeira implementação usou
`formula.split("([+-])", -1)` para conservar o operador, e **está errado**:
`Pattern.split` **não inclui os grupos capturados no resultado**, então `"d8-1"` produz
`["d8", "1"]` e o `-1` seria **descartado em silêncio** — a rolagem daria um número
errado sem mensagem de erro, pior que a recusa de hoje. A falha só apareceu porque a
lógica foi executada isolada antes de mexer no código real:

```
'd8-1'   split("([+-])",-1) -> [d8, 1]      <-- sinal perdido
```

A forma correta é `split("(?=[+-])", -1)` (lookahead), que deixa o operador no **começo**
do termo seguinte: `"d8-1"` → `["d8","-1"]`. Detalhe: o `Pattern.split` ignora match de
largura zero no índice 0, então `"-2"` fica `["-2"]` (sinal no primeiro termo) — tratar o
sinal com `startsWith` no início de cada termo cobre os dois casos sem posição especial.

A segunda falha apareceu na mesma bateria: com o sinal duplicado na exibição
(`d8 [5] - -1`). O `text` passado ao helper tem de vir **sem** sinal, porque o separador
`" §f- §b"` já carrega ele; só o primeiro termo, que não tem separador antes, recoloca o
sinal.

**Bateria final de validação da lógica** (executada, com 7 por dado para checar a
aritmética):

| Deve aceitar | Resultado | | Deve recusar | Resultado |
|---|---|---|---|---|
| `d20` | `d20 [7] = 7` | | `d20--1` | recusado (sinal duplo) |
| `d8-1` | `d8 [7] - 1 = 6` | | `d8--` | recusado (sinal duplo) |
| `2d6+d4+3` | `2d6 [7] + d4 [7] + 3 = 17` | | `d8+-1` | recusado (sinal duplo) |
| `d8-1-1` | `d8 [7] - 1 - 1 = 5` | | `d0`, `101d6`, `d1001` | recusado (limite) |
| `d4+2-1` | `d4 [7] + 2 - 1 = 8` | | `dX`, `abc`, `d8+x` | recusado (termo) |
| `3d6-2` | `3d6 [7] - 2 = 5` | | `-` | recusado (nenhum termo) |
| `1d20` | `d20 [7] = 7` | | | |
| `d100-5` | `d100 [7] - 5 = 2` | | | |
| `d8 + 2` | `d8 [7] + 2 = 9` | | | |
| `-2` | `-2 = -2` | | | |
| `+d8` | `d8 [7] = 7` | | | |
| `-2+10` | `-2 + 10 = 8` | | | |
| `d8+0` | `d8 [7] + 0 = 7` | | | |
| `5` | `5 = 5` | | | |
| `d8+2-1+3` | `d8 [7] + 2 - 1 + 3 = 11` | | | |

**Mudanças no parser** (`MasterCommands.java`, todas dentro de `rollDice`, que tem 2
chamadores e nenhum outro uso no projeto): o `split` em `:683`, o tratamento do sinal no
início do laço, o helper novo `appendRollTerm` (`:770-782`) no lugar do
`String.join(" §f+ §b", displayTerms)`. **`MODIFIER_TERM` e `DICE_TERM` não mudaram**,
porque o sinal é extraído antes de casar os padrões.

Ganho colateral: `/rpg roll d8-1` **digitado no chat** passa a funcionar, e não só pelos
botões da tela.

### Divergências que continuam abertas (não corrigidas)

1. **Detalhe cosmético na rolagem de perícia:** `rollSkill` imprime sempre `" + "` entre
   os termos, então com atributo negativo o chat mostra `... + STR -3 = 11` — sinal
   duplicado na tela, número correto. Não corrigido: é mudança de exibição fora do que
   foi pedido.
2. `RANDOM` em `rollDice` (`:709`, declarado em `:42`) é um `new Random()` estático, não
   o RNG do servidor — inconsistente com `rollSkill`, que usa `player.getRandom()`.
   Registrado como pendência, não corrigido.
3. `/rpg` e `/rpg menu` abrem um menu **ASCII no chat**; a tela gráfica `RpgMenuScreen`
   só abre pela tecla `R`. Documentado para evitar confusão.
4. `/rpg roll` sem argumento mostra o cabeçalho `Skills` no chat, mas lista as 20
   **perícias**. Documentado com as duas informações.

## Validações executadas

| Validação | Resultado |
|---|---|
| `.\gradlew.bat build --no-daemon --console=plain` | **BUILD SUCCESSFUL** (14s, e 13s de novo depois das correções) |
| `scanEncoding` (roda dentro de `check`, logo dentro de `build`) | 77 arquivos, 0 mojibake, 0 ideograma, 0 U+FFFD |
| `runClient` (lançado em segundo plano) | `Sound engine started` em `run/logs/latest.log` às 12:57:19, 2 teclas registradas, 0 crash reports, 0 erro de mixin |
| `git diff` revisado arquivo a arquivo pelo agente principal | 4 arquivos (+73/−14) na primeira revisão, que achou a regressão do broadcast; diff final são 6 arquivos, +192/−37 |
| `Select-String` por CJK/U+FFFD nos arquivos escritos | vazio |
| **Lógica do parser de rolagem executada isolada** (25 casos) | 15 aceitos e 10 recusados, todos como esperado — ver tabela em "Correção B" |

**Nenhum mixin foi criado ou alterado nesta sessão**, então o `runClient` não era
obrigatório pela armadilha de injeção conhecida — mas foi rodado mesmo assim, porque
a nova categoria de teclas só aparece na tela de Controles e o build não cobre isso.

## VALIDAÇÃO DO USUÁRIO EM JOGO — 27/09/2026

O usuário testou e confirmou: **"testei tudo e ta 100%"**. Isso encerra a pendência mais
importante deste relatório, que era a ausência de execução real. Registro separado porque
`gradlew build` e `runClient` **não** provam comportamento de reconexão, e o relatório
registrava honestamente que nada daquilo tinha sido executado.

Cobertura confirmada pelo usuário:

| Item | Status |
|---|---|
| Aba **"Tabletop RPG"** em Controles com as 2 teclas | **OK em jogo** |
| Atalho customizado preservado após a troca de categoria | **OK em jogo** |
| Jogador desconecta no meio do turno → aviso de chat e vez reservada | **OK em jogo** |
| Turno bloqueado para os outros até o Mestre liberar | **OK em jogo** |
| `/rpg turn revoke` destrava com o jogador offline | **OK em jogo** |
| Jogador reconecta → aura/barreira azul volta | **OK em jogo** |
| Mestre desconecta → perde cargo, sessão `FREE`, combate reseta | **OK em jogo** |
| Câmera de espectador limpa o estado na reconexão | **OK em jogo** |
| `/rpg roll` com subtração (`d8-1`, `d8-1-1`, `2d6+d4+3`, `-2`, alternados) | **OK em jogo** |
| Botões `-1` / `-10` da `DiceRollScreen` deixam de ser recusados | **OK em jogo** |
| Sinal duplo (`d8--1`, `d8+-1`) continua recusado | **OK em jogo** |

Consequência prática: a FASE 3 parte 1 está **funcional e validada**. Nada da lista abaixo
precisa ser re-testado em próximo ciclo.

## Troca de referências de linha por símbolo no catálogo (27/09/2026)

**Pedido do usuário:** o catálogo ia ficar desatualizado? Ao medir, `FUNCIONALIDADES-E-COMANDOS.md`
tinha **143 citações de linha em 27 arquivos, em 411 linhas** — uma a cada ~3 linhas. O usuário
escolheu trocar linha por símbolo.

**Por que a troca era necessária, e não só estética:** o problema já tinha se materializado
no mesmo dia. Ao corrigir o parser de rolagem, duas seções ficaram factualmente erradas
porque o código mudou embaixo delas. Esse é o caso real e verificado.

**Correção de um registro anterior deste relatório.** Este documento chegou a afirmar que as
refs de `PlayerListScreen.java` "apontavam para as linhas 290 e 448 num arquivo de 111
linhas", como se o catálogo estivesse errado. **Isso é falso.** O `:448-451` solto da tabela
pertencia a `StatusScreen`, que era o assunto da seção, e o documento estava correto; o erro
foi do meu script de verificação, que amarrou a referência solta ao nome de arquivo anterior
mais próximo (`PlayerListScreen.java`) e produziu uma linha fora do arquivo. A lição real é
dupla, e vai contra a minha própria ferramenta: (a) número de linha apodrece mesmo, mas
(b) **verificação por regex que "confere" a documentação produz falsos positivos** — as duas
minhas passaram a acusar 42 e depois 96 itens inexistentes, obrigando revisão manual.

### O que foi feito

**200 citações** (143 com nome de arquivo + 57 soltas, que o documento usa como
`:NNN` = "o arquivo citado antes") convertidas para `Arquivo.java: símbolo`, por script, com
o símbolo resolvido varrendo para trás até a declaração que contém a linha. **Zero número de
linha sobrou** no arquivo.

Casos que exigiram decisão manual, porque o backward-scan erra ou o código não ajuda:

| Referência original | Virou | Motivo |
|---|---|---|
| `MasterCommands.java:421` | `DICE_TERM` | campo; a primeira versão geradora devolveu `compile`, nome da fábrica `Pattern.compile` |
| `SheetData.java:654-676` | `PERICIAS_PADRAO` | o gerador devolveu `of` |
| `AuraRenderer.java:14-31` | `AuraRenderer` (Javadoc da classe) | a citação cobria o Javadoc de classe, acima de qualquer declaração |
| `GuiMixin.java:29-57` | `GuiMixin` | a citação começava no `@Mixin` |
| `StatusScreen.java:448-451` | `buildFooterExtra` | escrita por extenso: na tabela o `:448-451` solto vinha logo após `PlayerListScreen.java:89-90`, e o gerador atribuiu ao arquivo errado |
| `RpgNetworking.java` (caído) | `sendDownedState`, `sendDownedSnapshotTo` | o gerador devolveu `type` |

**6 linhas** tinham o mesmo símbolo citado 2+ vezes na mesma célula, porque pontos distintos
*dentro* do mesmo método colapsaram: a linha das teclas (`R`/`V`), a de `finishTurn`, a do
`DiceRollScreen` e a do `RpgSettingsScreen` (que tem os dois blocos do Mestre e do jogador
dentro do mesmo `init`). Foram escritas com qualificador curto ("bloco do Mestre",
"ainda em `init`, bloco `if (!isMaster)`") em vez de repetir o símbolo.

### Problema real deste trecho: sobrescrevi a fonte

**Erro meu, e ele invalida parte do que foi validado.** A primeira versão do gerador rodou
correta, mas **copiei o arquivo gerado sobre o catálogo antes de conferir**. Como
`FUNCIONALIDADES-E-COMANDOS.md` é **untracked** (`??` no `git status`, nunca commitado), o git
não tinha a versão com números de linha para restaurar. A partir daí o script passou a ler um
arquivo que já não tinha números, e **todos os diagnósticos de "quantas refs faltam" depois
disso foram enganosos** — a saída dizia "200 referências" quando o arquivo já estava todo
convertido.

A consequência prática foi concreta: **um símbolo errado sobreviveu até o fim**
(`MasterCommands.java: compile`), porque a correção que o eliminava só podia ser testada
contra a versão com números de linha. Foi conserto na mão depois, comparando com o código.

**Lição:** com arquivo **untracked** sendo gerado por script, o alvo do `Copy-Item` tem de ser
um **caminho novo**, nunca o arquivo-fonte. Escrever o resultado em
`FUNCIONALIDADES-E-COMANDOS.novo.md` e só Promotionar depois de conferir teria eliminado a
perda e os diagnósticos falsos.

### Verificação final

| Verificação | Resultado |
|---|---|
| Números de linha restantes (regex `` `\d `` e `\.java:\d`) | **0** e **0** |
| Citações soltas restantes (conteúdo legítimo: `1d20 + ...`, `3rd Person`, `12/10`) | 6 linhas, todas conteúdo real |
| Artefatos `, \`if\`` (o matcher tratava `if (...) {` como construtor) | 5, **todos removidos** |
| `register` em `DamageControlHandler`/`CombatController`/`PlayerControlHandler`/`SheetPersistenceEvents` | **mantido** — `register()` é método real nos quatro arquivos, a citagem está certa |
| BOM / mojibake / CJK / U+FFFD | sem BOM, **0** |
| Linhas totais | 411 (10 a mais pela nota de convenção) |

**Limitação honesta:** não existe conferência automática confiável de que cada símbolo aponta
para o comportamento descrito. Duas tentativas de verificador por regex produziram 42 e depois
96 "erros" que eram **falso positivo** — `Skills`, `Back`, `R`, `V` são conteúdo do documento,
não citações quebradas. A conversão foi conferida por amostragem e pelos casos que o gerador
não conseguia resolver; **uma leitura humana do catálogo ainda é a checagem definitiva.**

**Regra para o futuro:** o catálogo passa a ser atualizado **por símbolo**, então o
`scanEncoding` (que cobre `src/` e `agent/`) não pega erro de referência nele — ele está na
raiz. Conferência de referência é tarefa de leitura, não de build.

## O que NÃO foi validado

**Nada foi validado em jogo.** Nenhuma destas comportamentos foi executado por um
jogador; o `runClient` só provou que o cliente sobe sem crash:

- a aba **"Tabletop RPG"** aparecer em Controles com as duas teclas dentro;
- os bindings antigos (`R` e `V`) continuarem funcionando depois da troca de categoria;
- a **barreira azul** aparecer ao reconectar;
- o **turno reservado** e o aviso de chat quando o jogador cai no meio do turno;
- `/rpg turn revoke` destravar a sessão com o jogador offline;
- a limpeza de estado do cliente ao reconectar em outro mundo.

O `runClient` foi encerrado pelo agente ao fim da validação (só os processos cujo
linha de comando continha o caminho do projeto, para não matar processo do usuário).

## Limitações

- Não há como validar resiliência de conexão em singleplayer com um jogador só. O
  cenário exige `runServer` + `runClient` com duas contas.
- O servidor dedicado é `gradlew runServer` (a task `server` não existe).

## Aprendizados

- **O turno de quem cai não prende a sessão**, porque `/rpg turn revoke` e
  `/rpg turn finish` leem o UUID do servidor e não a entidade. A decisão do usuário
  ("reserva a vez, mas o mestre pode pular") saiu de graça em cima do comando que já
  existia.
- **`gameModeOrdinal = -1` como sentinela de "sem sessão"** é o que impede a câmera de
  espectador de cair quando o jogador só reconecta. Zerar faria o JOIN parecer troca de
  turno.
- **A translation key de um `KeyMapping` é parte da identidade persistida** pelo
  vanilla em `options.txt`. Separar "categoria" (mudável) de "translation key"
  (imutável) é o que permite criar aba própria sem quebrar o binding de ninguém.
- **Delegar um broadcast "para todos" a um método "para um"** é uma regressão
  silenciosa de custo: `getAuraData` passou a rodar uma vez por jogador sem erro nem
  aviso. Diff precisa ser lido pelo agente principal, não só aceito do subagente.
- **Fato antigo em memória precisa ser confrontado com o código.** O "mestre 1024
  blocos" estava em duas seções da memória do projeto e em dois Javadocs do código, e
  não existia em lugar nenhum de `src/`.
- **`Pattern.split` não inclui grupos capturados no array.** `"d8-1".split("([+-])", -1)`
  devolve `["d8","1"]`, e não `["d8","-","1"]`. Raciocinar o contrário é fácil e o
  defeito aqui seria **silencioso**: a subtração seria descartada e a rolagem mostraria
  um número errado, sem erro. Para conservar o operador use lookahead
  `split("(?=[+-])", -1)`, que deixa o sinal no começo do termo seguinte. Detalhe: o
  `Pattern.split` ignora match de largura zero no índice 0, então `"-2"` fica `["-2"]`.
- **Executar a lógica isolada antes de confiar no diff foi o que salvou a entrega.**
  A primeira versão do parser compilava, passava o build e estava errada; o erro só
  apareceu ao rodar a aritmética num `.java` solto. Regex com captura deserves esse
  teste sempre.
- **O primeiro termo de uma exibição montada por helper não tem separador antes.**
  Passar o valor já com sinal (`sign*mod`) para um helper que põe `" - "` no lugar
  duplica o sinal na tela (`d8 [5] - -1`); o helper recoloca o sinal só quando é o
  primeiro item.
- **Antes de culpar a fórmula, conferir quem soma.** O medo de "atributo negativo
  quebrado" era falso: `rollSkill` soma direto em `long` e nunca monta string de
  fórmula. `rollDice` é caminho separado, com 2 chamadores.

## Próximos passos

1. **Teste do usuário** dos seis itens da seção "O que NÃO foi validado".
2. **Teste do usuário** da rolagem com subtração: `/rpg roll d8-1`, `/rpg roll d8-1-1`,
   `/rpg roll 2d6+d4+3` e um botão `-1` na `DiceRollScreen`. A aritmética já foi
   validada isolada, mas **não em jogo**.
3. **Decidir** se o detalhe cosmético de `rollSkill` (sinal duplicado com atributo
   negativo) e se o `RANDOM` estático de `rollDice` devem ser corrigidos
   (divergências 1 e 2 em "Divergências que continuam abertas").
4. Continuar a FASE 3: sistema de iniciativa (a lista ordenada que ainda não existe),
   e os itens de conforto ainda não detalhados.
5. **Commit** fica sob demanda do usuário (decisão de 26/09/2026). Quando pedir, o
   escopo é código + `FUNCIONALIDADES-E-COMANDOS.md` + memória do projeto + este
   relatório, com tag e registro no `VERSIONAMENTOS.md`.
