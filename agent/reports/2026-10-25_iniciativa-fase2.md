# Iniciativa - Fase 2: rolagem e ordenacao

Data: 25/10/2026. Branch: `feature/iniciativa`. Fase 1: tag `iniciativa-fase1`.

## O que foi feito
- **Bonus padrao da mesa (texto)**: `SheetModel.defaultInitiative` (int) virou `initiativeFormula` (String, ate 40 caracteres). Codec, codec de rede e copias atualizados. Saves antigos caem em vazio (o campo numerico nunca teve interface, entao nao ha valor de usuario a converter).
- **Sheet Editor**: caixa de texto "Bonus padrao da mesa" logo abaixo da caixa "Usar iniciativa padrao?".
- **Ficha do jogador (StatusScreen)**: com a caixa marcada, o campo de iniciativa mostra a formula do bonus, so leitura (decisao confirmada). Desmarcada: mostra a pericia marcada, so leitura, como antes.
- **Rolagem do jogador** (`InitiativeRoller.playerTotal`):
  - Marcada: `1d20 + bonus da mesa` (formula resolvida pela ficha).
  - Desmarcada: mesma conta do `/rpg roll <pericia>` (d20 + pericia + atributo), usando a pericia marcada como iniciativa.
  - Sem pericia marcada: 1d20.
- **Rolagem do mob** (`InitiativeRoller.mobTotal`): marcada = 1d20 + bonus; desmarcada = 1d20 + pericia marcada na ficha do mob (se ela tiver); senao 1d20.
- **Formula extra**: `/rpg initiative roll +1d20` ou `-2` soma/subtrai ao total. Argumento comecando com `+` ou `-` e formula extra; qualquer outro texto e nome de mob (mestre).
- **Rolagem da pericia entra na lista**: com a caixa desmarcada, `/rpg roll <pericia marcada>` durante a coleta coloca o resultado na lista (um por jogador; ja estando, nao entra de novo).
- **Ordenacao**: `InitiativeTracker.entries()` devolve do maior para o menor; empate mantem a ordem de entrada.
- **Duplicidade**: jogador nao entra duas vezes (ja era a regra da Fase 1, agora vale tambem para a rolagem da pericia).

## Pendente nesta fase (NAO feito)
- **Sigla de atributo na formula (ex.: `AGId20` = 3d20 com AGI 3)**: nao implementado. O `FormulaResolver` resolve nome de atributo como numero, mas nao trata sigla seguida de dado. Precisa de decisao antes de mexer na resolucao geral de rolagens (afeta `/rpg roll` tambem).

## Decisoes desta fase
- Bonus com sinal ("-2") mantem o sinal: `1d20-2`.
- Formula extra precisa de sinal inicial; sem sinal, o texto e tratado como nome de mob.
- Mob desmarcada usa so a pericia da ficha dele (sem atributo), porque a ficha de mob nao tem a mesma conta de atributo do jogador.

## Testes e validacao
- `gradlew build` exit 0. Suite completa sem falhas (0 falhas/erros).
- `InitiativeRollerTest`: 4/4 (formatos da formula do bonus, incluindo `1d20+1d20+3`).
- `InitiativeTrackerTest`: 7/7 (inclui ordenacao descendente e empate).
- Encoding: 312 linhas adicionadas, 0 nao-ASCII.
- NAO validado em jogo: rolagens reais, o campo de texto no editor, a leitura somente-leitura na ficha e o gancho de `/rpg roll <pericia>`.

## Rollback
- Fase 2 inteira: `git revert <hash-da-fase2>` ou `git checkout iniciativa-fase1` (somente leitura).
- Mudanca de dado: o bonus da mesa agora fica na chave `initiativeFormula`. Se a fase for revertida depois de o mestre digitar um bonus, a build antiga ignora essa chave e o bonus volta ao padrao vazio (nao ha erro ao abrir o mundo).

## Ajustes de feedback (25/10/2026, depois da revisao em jogo)
- Mensagem da rolagem de iniciativa agora segue o formato do /rpg roll: a rolagem de pericia usa a mesma mensagem do /rpg roll <pericia>; a formula da mesa usa a mesma mensagem do /rpg roll <formula>. Depois da rolagem, aparece "X entrou na iniciativa com N".
- Semantica da formula da mesa: a formula digitada e a rolagem INTEIRA ("1d20 + 3"), nao mais um bonus somado a 1d20. Vazia vira 1d20. Consequencia: "3" sozinho rola 3 (valor fixo), nao 1d20+3.
- Sheet Editor: rotulo "Table Initiative" / "Iniciativa da mesa"; placeholder "Ex: 1d20 + 3" dentro da caixa.
- Formula nao reconhecida: o salvamento e bloqueado no cliente com a mensagem "Nao foi possivel reconhecer a formula de iniciativa." e o servidor tambem recusa a gravacao. A validacao so vale com a caixa marcada (com ela desmarcada, a caixa fica desabilitada e a formula nao e usada).
- Caixa desmarcada: campo de texto desabilitado (cinza). StatusScreen mostra 1d20 quando a formula esta vazia.
- Refatoracao: MasterCommands.periciaResult e formulaMessage, sem mudar o texto do /rpg roll.
- Build exit 0; InitiativeRollerTest 3/3 (formula vazia, formula digitada, sinal). Nao validado em jogo.
