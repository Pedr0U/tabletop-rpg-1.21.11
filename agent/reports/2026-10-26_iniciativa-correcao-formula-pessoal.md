# Iniciativa - correcao: iniciativa padrao pessoal (26/10/2026)

## O que mudou
- A formula de iniciativa padrao agora fica na ficha de cada jogador (campo Initiative, texto, limite 40 caracteres). Nao existe mais campo de iniciativa da mesa no Sheet Editor.
- Caixa "Usar iniciativa padrao" marcada: cada jogador rola a propria formula (ex.: 2d20+3). Vazia rola 1d20 seco. Espacos sao ignorados.
- Caixa desmarcada: continua a pericia marcada como iniciativa, sem mudanca.
- Formula nao reconhecida: a caixa nao envia ao servidor enquanto o texto e parcial; ao sair da caixa com texto invalido, o jogador ve aviso no chat e o campo volta ao valor gravado.
- Mobs com a caixa marcada rolam 1d20 seco (mob nao tem formula propria). Pergunta em aberto.
- Save antigo: o campo numerico "initiative" nao e mais lido; a ficha abre com formula vazia (1d20).

## Arquivos
- InitiativeRoller: regra pessoal; isValidFormula publico; compact().
- SheetData: Progress.initiativeFormula (string) no lugar do int; withField aceita "initiative" so se valido.
- SheetModel: campo da mesa removido (record, codecs, copias).
- RpgNetworking: validacao do save so pela pericia.
- SheetEditorScreen: caixa Table Initiative e validacoes de formula removidas.
- CharacterSheetScreen: caixa de iniciativa como texto, hint, envio so se valido, aviso ao sair.
- StatusScreen: desmarcada mostra pericia so leitura; marcada deixa editar.
- InitiativeScreen: lista visivel abaixo dos botoes (sem botao de lista); InitiativeListScreen removida.
- RpgMenuScreen: botao Iniciativa desabilitado no modo FREE.

## Validacao
- gradlew build exit 0; suite sem falhas.
- NAO validado em jogo.

## Fora desta correcao (ainda nao feito, por decisao)
- Stop Initiative, scoreboard no HUD, interacoes da lista (Fases 4, 5, 7).