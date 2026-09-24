# Implementation Report — Unificação de branches + Ajustes FASE 1

**Data:** 24/09/2026
**Status:** COMPLETA — build OK, jar copiado para a pasta de mods

## Objective
Resolver o relato do usuário: "voltaram todas as coisas que havíamos alterado e corrigido antes" (player colocando blocos, dano voltando, hoverdistance sem funcionar) + pedidos novos (toggle de quebra no menu, clima em 1 botão).

## Root Cause (FATO, verificado)
O projeto tem **2 branches divergentes**:
- `main` (HEAD `2234576`): onde a FASE 1 foi implementada (toggle de quebra + clima).
- `pasta-Net` (commit `450d64c` "Correção de Bugs e Adições", 18:48): contém TODAS as correções que o usuário viu funcionando — `DamageControlHandler` (dano), fix do hoverdistance, bloqueio de colocação de blocos, fix do player preso na aura, câmera, mob flutuando.

O build que o usuário testou (19:20) era do `main`, que **não tinha** as correções do `pasta-Net`. Por isso "voltaram": dano, hoverdistance, colocação de blocos. O resumo de sessão anterior registrou a "FASE 0.7" como aplicada no main, mas ela estava no pasta-Net (o relatório `feedback-fixes-2.md` nunca existiu e o `PlayerControlHandler`/`CombatController` do main não tinham as mudanças).

Além disso, o usuário roda o jogo com o jar copiado manualmente em `%APPDATA%\.minecraft\mods\tabletop-rpg-1.0.0.jar` (estava com um jar de 01:22, antigo).

## What Changed (unificação pasta-Net -> working tree do main)
Copiados do pasta-Net (via `git show pasta-Net:<path>`):
- `DamageControlHandler.java` (NOVO): jogadores e mobs imunes a dano físico (`ServerLivingEntityEvents.ALLOW_DAMAGE`; exceções: `FELL_OUT_OF_WORLD` e `GENERIC_KILL`).
- `ServerGamePacketListenerImplAccessor.java` (NOVO) + `ServerGamePacketListenerImplMixin.java`: fix do player preso na barreira da aura (só teleporta se não houver teleporte pendente).
- `CombatController.java`: mob flutuando (movimento direto, sem IA), `lookAt` manual, âncora na seleção, aura não segue o mob, `reset` limpo.
- `CinematicCameraController.java`: câmera não atravessa parede.
- `TabletopRpg.java`: registra `DamageControlHandler.register()`.
- `tabletop-rpg.mixins.json`: `MobAccessor` -> `ServerGamePacketListenerImplAccessor`.
- `MobAccessor.java` REMOVIDO.

Mesclados manualmente (pasta-Net + FASE 1):
- `PlayerControlHandler.java`: fix de colocação do pasta-Net (`canPlaceBlocks` + `isBlockItemInHand` no `UseBlockCallback`) + toggle de quebra da FASE 1 (`canBreakBlocks` com `playersCanBreakBlocks`).
- `RpgNetworking.java`: handler `DISCONNECT` (mestre sai -> pausa sessão; limpa hover/leak) + fix do hoverdistance (`getHoverDistance()` para todos) + payloads da FASE 1 (BlockBreakSetting/WeatherSet).
- `TabletopRpgClient.java`: linha de visão no hover (não destaca através de paredes) + campo/receiver da FASE 1.

Ajustes pedidos pelo usuário:
- `RpgSettingsScreen.java`: clima agora é **1 botão** que cicla Sol -> Chuva -> Tempestade (estado local `weatherState`; o clima persiste no servidor até o mestre mudar). Layout: slider +0, ciclo +28, quebra +56, clima +84, voltar +112.

## Files Changed
- `src/main/java/com/pedro/tabletoprpg/DamageControlHandler.java` (novo)
- `src/main/java/com/pedro/tabletoprpg/mixin/ServerGamePacketListenerImplAccessor.java` (novo)
- `src/main/java/com/pedro/tabletoprpg/mixin/MobAccessor.java` (removido)
- `src/main/java/com/pedro/tabletoprpg/TabletopRpg.java`
- `src/main/java/com/pedro/tabletoprpg/CombatController.java`
- `src/main/java/com/pedro/tabletoprpg/PlayerControlHandler.java`
- `src/main/java/com/pedro/tabletoprpg/RpgNetworking.java`
- `src/main/java/com/pedro/tabletoprpg/SessionManager.java`
- `src/main/java/com/pedro/tabletoprpg/mixin/ServerGamePacketListenerImplMixin.java`
- `src/main/resources/tabletop-rpg.mixins.json`
- `src/client/java/com/pedro/tabletoprpg/client/CinematicCameraController.java`
- `src/client/java/com/pedro/tabletoprpg/client/TabletopRpgClient.java`
- `src/client/java/com/pedro/tabletoprpg/client/RpgSettingsScreen.java`

## Decisions
- **Unificar SEM commit** (regra: só commitar com pedido explícito): arquivos copiados do pasta-Net via `git show` + mesclagem manual. O working tree agora contém a união; o usuário pode commitar quando quiser.
- **`CombatController` do pasta-Net é superset da FASE 0.7**: já tem a correção da aura (âncora na seleção, não segue o mob) + mob flutuando. Copiado integralmente.
- **Toggle de quebra controla QUEBRA; colocação continua sempre bloqueada para não-mestre** (como o pasta-Net).
- **Clima em 1 botão com estado local**: sem sync de clima no cliente (só o mestre muda; o estado local é confiável).
- **Jar copiado para a pasta de mods** (`%APPDATA%\.minecraft\mods\`): o usuário roda o jogo de lá.

## Validation
- `.\gradlew.bat build --console=plain` -> **BUILD SUCCESSFUL** (19:32).
- Jar verificado na pasta de mods: contém `DamageControlHandler`, `BlockBreakSettingPayload/Query/State`, `WeatherSetPayload`, `ServerGamePacketListenerImplAccessor`; `MobAccessor` ausente (removido).
- `git status`: 12 modificados + 2 novos + 1 removido — exatamente o esperado.

## Problems Encountered
- O resumo de sessão anterior registrava a "FASE 0.7" como aplicada no main, mas ela estava no pasta-Net (relatório inexistente, arquivos sem as mudanças). Causa da confusão do usuário.
- Jar da pasta de mods estava desatualizado (01:22) — o usuário rodava uma versão antiga.

## Root Causes
- Branches divergentes (`main` vs `pasta-Net`) sem unificação.
- Sessão anterior trabalhou no branch errado (main) sem verificar `git branch -a`/`git log --all`.

## Fixes
- Unificação completa no working tree do main (cópia + mesclagem manual).
- Clima em 1 botão ciclável.
- Jar novo copiado para a pasta de mods.

## Remaining Issues
- Nenhum conhecido. Teste em jogo recomendado: dano (players/mobs imunes), hoverdistance, colocação de blocos (só mestre), toggle de quebra, clima ciclável, player preso na aura.

## Lessons / Memory
- **SEMPRE verificar `git branch -a` e `git log --all --oneline` antes de implementar** — pode haver outro branch com trabalho não mesclado.
- **O usuário roda o jogo com o jar da pasta de mods** (`%APPDATA%\.minecraft\mods\`) — copiar o jar novo após cada build.
- Memória do projeto atualizada com a seção "UNIFICAÇÃO DE BRANCHES".

## Next Steps
- Usuário testa em jogo (o jar já está na pasta de mods).
- Pendências da sessão anterior: turno dos monstros/horda, iniciativa 1d20, lista de monstros para o mestre.