# Iniciativa - Fase 1: estrutura e comandos

Data: 25/10/2026. Branch: `feature/iniciativa`. Base: tag `iniciativa-base` (ff9318e).

## Objetivo da fase
Estrutura da lista de iniciativa e os comandos `/rpg initiative start|end|roll|add|remove` e a listagem, com as regras de modo, permissao e estado.

## O que foi feito
- `InitiativeTracker` (NOVO, classe pura): estado IDLE/COLLECTING, participantes em ordem de entrada. Jogador nao entra duas vezes; mob entra quantas vezes o mestre quiser; `remove` tira todas as entradas do nome.
- `InitiativeCommands` (NOVO): comandos, validacoes e sugestoes de nome. Anexado ao `/rpg` em `MasterCommands`.
- `MasterCommands.setMode`: chama `InitiativeCommands.onModeChanged` antes de trocar o modo. Trocar de modo finaliza a iniciativa.
- Lang: 17 chaves `message.tabletoprpg.initiative.*` em `en_us.json` e `pt_br.json` (pt_br sem acentos nas chaves novas).
- Teste: `InitiativeTrackerTest` (6 casos).

## Regras implementadas
- Modo: so INVESTIGATION e COMBAT. Em FREE recusa com mensagem.
- `start` (mestre): abre a coleta e limpa a lista. Ja aberta = recusa.
- `end` (mestre): encerra e limpa. Sem iniciativa = recusa.
- `roll` (qualquer jogador): exige coleta aberta; rola 1d20 puro e entra na lista. Quem ja esta na lista recebe recusa.
- `roll <nome do mob>` (mestre): so mobs com camera e ficha vinculada. Mob com o mesmo nome compartilha o valor; se o grupo ja esta na lista, recusa.
- `add <nome>` (mestre, exige coleta): jogador online rola e entra (sem repetir); mob reaproveita o valor do grupo ou rola se o grupo nao existe. Mob pode ser repetido.
- `remove <nome>` (mestre): tira todas as entradas daquele nome.
- `initiative` (sem argumento): lista na ordem de entrada.
- Sugestoes de nome em `roll <mob>`, `add` (jogadores online + mobs elegiveis) e `remove` (quem esta na lista).

## Decisoes desta fase (nao estavam explicitas na especificacao)
- `add` de mob reaproveita o valor do grupo ja rolado (coerente com "mesmo valor" da resposta 3).
- `remove` nao exige coleta aberta, so mestre e modo.
- A lista nao e ordenada ainda: a ordem e a de entrada (ordenacao e Fase 2).
- Rolagem e 1d20 puro (formula da ficha e formulas extras sao Fase 2).

## Fora desta fase (previsto em fases posteriores)
- `begin`, `stop`, `give` (Fase 6 e 7), bloqueio/espectador (Fase 7), `show_panel`/`hide_panel` (Fase 5), menu R (Fase 3), lista interativa (Fase 4).
- O estado vive em memoria: reiniciar o servidor zera a iniciativa.
- Nada e enviado aos clientes ainda (sem payload de rede).

## Testes e validacao
- `gradlew build` exit 0 (baseline e depois da fase). `InitiativeTrackerTest`: 6 de 6 passando.
- Encoding: os 3 arquivos Java novos tem 0 caracteres nao-ASCII.
- NAO validado em jogo: os comandos, as sugestoes e as mensagens ainda nao foram executados num servidor. O build valida compilacao, nao o comportamento do Brigadier.

## Rollback
- Voltar ao estado anterior da fase: `git checkout iniciativa-base` (somente leitura) ou, no branch, `git revert <hash-do-commit-da-fase1>`.
- Nenhum arquivo existente da base foi removido; as alteracoes em `MasterCommands` sao 2 linhas e 1 chamada.

## Status
Fase 1 concluida em codigo e build. Aguarda teste em jogo.
