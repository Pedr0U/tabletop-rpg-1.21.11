# Iniciativa - Fase 3: menu de iniciativa

Data: 26/10/2026. Branch: feature/iniciativa. Fase 2: tags iniciativa-fase2 e ajustes posteriores.

## O que foi feito
- Botao Iniciativa no menu R (RpgMenuScreen), logo abaixo de Rolagens. Desabilitado no modo FREE.
- InitiativeScreen (menu proprio, sem textura de pergaminho):
  - Mestre: Comecar/Terminar (executa direto, sem chat), Adicionar iniciativa (preenche /rpg initiative add no chat), Rolar iniciativa (preenche /rpg initiative roll no chat), Lista de iniciativa (abre InitiativeListScreen), Mostrar iniciativas ON/OFF, Configurar iniciativa (desabilitado, sem funcao).
  - Jogador: Rolar iniciativa (chat), Mostrar iniciativas ON/OFF, e a lista somente leitura na propria tela.
  - Estado da coleta na tela; rotulo Comecar/Terminar atualiza sozinho quando o estado muda.
- InitiativeListScreen: lista somente leitura (Fase 4 adiciona remover, dar turno e arrastar).
- Sincronizacao servidor -> cliente: InitiativeSync.Payload (coleta aberta + participantes na ordem de exibicao), enviado ao entrar no servidor e a cada mudanca (broadcast do InitiativeCommands).
- InitiativeClientState: copia do estado no cliente; "Mostrar iniciativas" so no cliente, sem gravar, volta a OFF ao reconectar.

## Decisoes
- Mostrar iniciativas nao muda nada visivel ainda (o painel no HUD e a Fase 5).
- Destaque do turno na lista do jogador: Fase 6.
- Sugestao de chat com ChatScreen(texto, true): o segundo argumento foi escolhido pelo nome do construtor; confirmar em jogo que o chat abre com o comando preenchido e com sugestoes.

## Validacao
- gradlew build exit 0 (compilacao cliente e servidor, suite de testes).
- NAO validado em jogo: layout das telas, botoes desabilitados, sincronizacao, chat preenchido, rotulo Comecar/Terminar.

## Rollback
- git revert <hash deste commit> ou git checkout iniciativa-fase2-add-repetido (leitura).