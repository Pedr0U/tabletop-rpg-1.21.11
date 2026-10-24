# 2026-10-23 - Mudancas: aura em FREE, mensagem duplicada e copias no menu

## Objetivo
Corrigir 3 pedidos do usuario apos o teste de checklist, e registrar o estado dos demais itens.

## Feito

1. **Aura oculta em FREE** (`AuraRenderer.java`, cliente). Antes de ler `TabletopRpgClient.auras`, retorna se `isFreeMode()`. So o desenho muda; o servidor continua enviando a aura e o limite de movimento nao mudou.
2. **Mensagem de vinculo duplicada -> 1 vez** (`ThreatSheetBinding.java`). Causa: o mesmo clique chega como `UseEntityCallback` (handleEntityClick) e como `UseItemCallback` (bindUnderCrosshair). Correcao: `BOUND_BY_ENTITY_TICK` marca jogador + tick quando o clique em criatura vincula; o caminho do ar, no mesmo tick (janela <= 1 tick), retorna sem bind e sem mensagem. Nao foi alterada a regra de roubo/horda.
3. **Copias no menu Fichas de Ameaca** (`ThreatSheetsScreen.java`, `ThreatSheetScreen.java`, `en_us.json`, `pt_br.json`). Removido o botao de expandir de dentro da ficha; reposto o cabecalho original. No menu, abaixo de cada ficha mae com copias, ha um botao "Copias (n)" que abre/fecha a lista; estado inicial aberto.

## Validacao
- `gradlew build --no-daemon` EXIT:0, BUILD SUCCESSFUL (apos todas as edicoes deste bloco), PtBrLangTest PASSED.
- NAO testado em jogo: aura em FREE; clique duplo com ficha na mao (ordem dos pacotes nao confirmada); botoes de copias (glifos > e v na fonte do Minecraft); rolagem com grupo recolhido.

## Observacoes
- HEAD local esta em `76bb4b2`, que tinha misturado o toggle de copias dentro da ficha; foi revertido agora.
- Em FREE a barreira do servidor que impede o jogador de passar da aura (`ServerGamePacketListenerImplMixin`) nao foi alterada. Nao esta claro se deve valer em FREE.

## Pendente (aguarda decisao do usuario)
- Preset: qual caminho e a mensagem exata do erro da formula `2#2d6` / `2d6+2d4`.
- HP 0: levantar com pacote de reset (A) ou mixin (B); caido imune ou nao; HP ao levantar.
- Iniciativa: confirmar o desenho (caixa global + coluna de pericia; remover a caixa da ficha do personagem).
- Textos em ingles para pt_br: aguardando a implementacao (depende da iniciativa, que mexe nos mesmos arquivos de idioma).
