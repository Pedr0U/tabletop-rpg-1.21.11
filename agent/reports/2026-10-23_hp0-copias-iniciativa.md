# 2026-10-23 - HP 0 (queda vanilla sem sumir), botao de copias menor, iniciativa no Sheet Editor

## Objetivo
Pedidos do usuario apos o teste: (1) HP 0 deve deixar o mob caido, vermelho e sem sumir (opcao A, sem mixin); (2) botao de copias menor no menu; (3) iniciativa com caixa no Sheet Editor e coluna de pericia, sem caixa na ficha do personagem. Preset ficou de lado (usuario testou e funcionou).

## Feito

1. **HP 0 (opcao A)**. `CombatController.java`:
   - `applyDowned` guarda a vida atual em tag de texto `tabletoprpg_downed_hp=<valor>` (so na primeira vez), depois `setHealth(0)`. Pose SWIMMING removida; a queda vem do vanilla. Mantem `setNoAi(true)` e a tag `tabletoprpg_downed`.
   - `tickDownedThreatMobs` zera `deathTime` a cada tick do servidor, para o vanilla nao remover o mob aos 20 ticks.
   - `clearDowned` restaura a vida guardada (ou o maximo se nao houver ou se for invalida), zera `deathTime` e envia `DownedResetPayload` aos jogadores que rastreiam o mob.
   - `RpgNetworking.java`: novo payload S2C `DownedResetPayload(int entityId)`, registrado em `playS2C`.
   - `TabletopRpgClient.java`: receptor que zera `deathTime` no cliente.
   - Nao foi preciso mixin. `DownedBodyAlignMixin` so atua em `Player`, nao interfere.
2. **Botao de copias menor**. `ThreatSheetsScreen.java`: botao do grupo com altura 14 px e largura pelo texto (+12 px), alinhado a esquerda sob a mae. Usa as chaves `copies_expanded`/`copies_collapsed` ja existentes.
3. **Iniciativa**. `SheetModel.java`: campos novos `initiativeUseDefault` (padrao true) e `initiativePericiaId` (padrao vazio), com NBT e STREAM_CODEC. `SheetEditorScreen.java`: caixa "Usar iniciativa padrao?" acima do campo; desmarcada, o campo fica inativo e aparece uma coluna de checkbox por pericia (so uma marcada). `StatusScreen.java`: caixa e campo por personagem removidos; a iniciativa e mostrada como valor derivado, somente leitura.
4. **Correcao do validador**. `CombatController.clearDowned`: tag de vida malformada (ex.: editada com /tag) lancava `NumberFormatException` a cada tick; agora cai no maximo.

## Validacao
- `gradlew build --no-daemon` EXIT:0, BUILD SUCCESSFUL, 240 testes, 0 falhas (apos a correcao 4).
- `tcc-validador` (antes da correcao 4): APROVADO COM RESSALVAS, sem bloqueante.
- Nada disso foi testado em jogo.

## Ressalvas do validador (abertas)
- Iniciativa: `SheetData.java` (`Progress.effectiveInitiative` ~806-811 e `getNumeric("initiative")` ~2506) ainda usa a regra antiga. A tela esta certa; o getter do servidor nao. Nenhum uso de iniciativa em combate ou rolagem foi encontrado, entao o impacto e baixo. Corrigir numa tarefa propria.
- Recusa do clique de entidade (nome em branco, ficha nao encontrada) retorna antes de marcar o jogador, entao o clique no ar ainda repete a mensagem de recusa. A dedupe cobre so o vinculo bem-sucedido. Confirmar em jogo.
- Mob caido com `setHealth(0)` fica imune a dano (`isDeadOrDying`), como um morto no Minecraft. Essa era a minha suposicao: o usuario nao respondeu a pergunta de imunidade, so escolheu a opcao A. Confirmar.
- Rotulo do botao de copias pode passar 1 px da largura do botao (`ThreatSheetScreen.java:1754`), mas esse arquivo nao e mais o do menu; conferir.
- Nenhum teste novo cobre NBT/STREAM da iniciativa, `applyDowned`/`clearDowned` nem `rows()` do menu.

## Para testar em jogo
- Caido: mob de HP 0 deita, fica vermelho, nao some apos 20 ticks, nao anda nem ataca.
- Revive: ficha volta a HP > 0; mob levanta, com a vida anterior, sem resto de vermelho ou inclinacao no cliente.
- Multiplayer: outro jogador ve a queda e o levantar.
- Restart com mob caido: continua caido; a tag de vida persiste.
- Ataque no mob caido: sem efeito (confirmar se e o desejado).
- Copias: botao menor, aberto por padrao, abre e fecha, Del na copia e na mae.
- Iniciativa: caixa marcada por padrao no Sheet Editor; desmarcar mostra a coluna de pericia; so uma marcada; ficha do personagem mostra o valor derivado sem caixa; save antigo abre marcado.
- Confirmar: a barra de iniciativa desmarcada mostra o valor padrao inativo no editor, e a ficha usa a pericia marcada.

## Pendente
- Textos em ingles para pt_br (por ultimo, como pedido).
- Ressalvas acima.
