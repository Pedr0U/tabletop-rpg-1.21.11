# 2026-10-23 - Pendencias apos pausa: mob travado ao levantar e nova regra de iniciativa

## Objetivo
Dois pedidos do usuario: (1) bug de HP 0 -> HP 1 (mob levanta travado e bugado); (2) nova regra
de iniciativa (sem campo de valor no Sheet Editor; campo editavel so na ficha do jogador).

## Feito nesta sessao
- Pesquisa de causa do bug (subagente tcc-pesquisa): FATO identificado. Nao foi feita correcao.

## Bug: mob levanta travado ao voltar de HP 0 para 1
- Causa (FATO, codigo + javap 1.21.11): `CombatController.clearDowned` chama `setHealth(hp)` e logo
  em seguida envia `DownedResetPayload` no MESMO tick. A vida sincronizada so sai no tick SEGUINTE
  (ServerEntity.sendChanges roda dentro de ChunkMap.tick, depois de tickServer). Logo, o cliente
  recebe o reset ANTES da vida > 0, continua com isDeadOrDying=true, conta deathTime +1 e o residual
  fica para sempre: mob de pe, vermelho e inclinado.
- Correcao proposta (nao aplicada): set de UUIDs pendentes; em clearDowned, registra o UUID; no
  proximo END_SERVER_TICK, reenvia DownedResetPayload e limpa o set. Ordem: vida (tick N+1) ->
  reset (END N+1). Sem pacote novo.
- HIPOTESE alternativa: `isFrozenByTable` mantem noAi=true por design (mob de mesa). Confirmar.

## Iniciativa: nova regra (decisao do usuario, NAO implementada - tarefa cancelada)
- No Sheet Editor: APENAS a caixa "Usar iniciativa padrao?" (marcada por padrao) + coluna de checkbox
  por pericia quando desmarcada (so uma marcada). REMOVER o campo numerico do editor.
- Caixa MARCADA: na ficha do jogador aparece campo EDITAVEL para digitar o valor da iniciativa.
- Caixa DESMARCADA: na ficha do jogador sem campo; iniciativa DERIVADA e somente leitura (pericia
  marcada; 0 se nenhuma).
- `SheetModel.defaultInitiative` deixa de ter uso; nao remover do record (risco de quebrar save/testes).

## Pendente
1. Corrigir o bug de HP 0 -> HP 1 (reenvio de DownedResetPayload atrasado 1 tick).
2. Implementar a nova regra de iniciativa (SheetEditorScreen, StatusScreen, SheetData, lang).
3. Relatorio de fim de bloco + memoria.
4. Validacao independente (tcc-validador).
5. Textos em ingles para pt_br (por ultimo).

## Nao validado em jogo
- Queda vermelha, revive, multi-cliente, restart, botao de copias, coluna de pericia, campo de
  iniciativa editavel.
