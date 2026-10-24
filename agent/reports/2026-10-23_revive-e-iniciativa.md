# 2026-10-23 - Revive do mob caido e regra final da iniciativa

## Objetivo
Corrigir o bug em que o mob, ao sair de HP 0 (ficha volta a HP > 0), levantava travado e
deformado, e fechar a nova regra da iniciativa (sem campo de valor no Sheet Editor).

## Causa do revive (FATO, codigo + javap 1.21.11)
- `clearDowned` gravava `setHealth(hp)` e enviava `DownedResetPayload` no MESMO tick.
- A vida sincronizada so sai no tick seguinte (`ServerEntity.sendChanges`, dentro de `ChunkMap.tick`).
- O cliente recebia o reset antes da vida > 0: ainda via `isDeadOrDying()`, recontava `deathTime`
  e o residual ficava para sempre (mob de pe, vermelho e inclinado).

## Alteracoes
1. `CombatController.java`: novo `PENDING_DOWNED_RESET` (Set<UUID>). `clearDowned` agenda o UUID
   em vez de enviar o reset. `tickDownedThreatMobs` envia o reset no INICIO do tick seguinte (zera
   `deathTime` e envia para `PlayerLookup.tracking`). Mob descarregado: descartado sem erro.
2. `SheetModel.java`: javadoc de `withInitiativeUseDefault` corrigido (dizia que a iniciativa era o
   `defaultInitiative` global; agora cada personagem tem o proprio valor).
3. Iniciativa (ja presente no commit `704bd0a`, nao alterada aqui): caixa "Usar iniciativa padrao?"
   no Sheet Editor sem campo numerico; caixa marcada = campo editavel na ficha do jogador; desmarcada
   = valor derivado da pericia marcada (somente leitura). Servidor: `SheetData.initiativeShown()`.

## Validacao
- `gradlew build --no-daemon` EXIT:0, BUILD SUCCESSFUL, 240 testes, 0 falhas (apos o ajuste de javadoc).
- `tcc-validador`: APROVADO COM RESSALVAS, sem bloqueante. Respostas: o reset pendente roda antes de
  nova queda no mesmo tick; sem NPE; set nao cresce sem limite.
- Sem teste automatizado cobre `clearDowned`, `applyDowned`, `tickDownedThreatMobs` nem o set pendente.

## Ressalvas (baixa)
- `SheetModel.withDefaultInitiative` sem chamador (codigo morto). `defaultInitiative` ainda persistido,
  sem uso de comportamento.
- Tela usa `displayPericiaValue` (valor otimista dos botoes), servidor usa `pericia.value()`; corrige no eco.
- `PENDING_DOWNED_RESET` nao e limpo no shutdown (sem vazamento real).

## Nao validado em jogo
- Revive visual (levanta de pe, sem resto de vermelho/inclinacao), multiplayer (outro jogador ve o
  levantar), iniciativa na tela (caixa, coluna, eco do servidor).
