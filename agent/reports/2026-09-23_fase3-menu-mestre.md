# Implementation Report

## Status
CONCLUÍDO - Fase 3 (Menu do Mestre) completada. Build validado (BUILD SUCCESSFUL) e jar verificado.

## Objective
Completar a Fase 3 do TableTop RPG: os dois itens pendentes do diagnóstico anterior:
1. **Controle de horário do mundo** no menu do mestre (slider visual + payload + lógica servidor).
2. **Rolagem totalmente oculta** (resultado visível apenas para quem rolou, nunca no chat público).

## Scope / Subtasks
1. Payload `TimeSetPayload` (C2S) + receiver no servidor (valida mestre, aplica horário)
2. Comando `/rpg time <0-24000>` (só mestre)
3. Widget `TimeSlider` + integração no `RpgMenuScreen` (visível só para o mestre)
4. Comando `/rpg hiddenroll <formula>` + botão `[Hidden Roll]` no menu ASCII
5. Build + verificação do jar

## What Changed

### 1. `src/main/java/com/pedro/tabletoprpg/RpgNetworking.java`
- Novo payload `TimeSetPayload(int timeOfDay)` (C2S, `ByteBufCodecs.VAR_INT`), registrado em `registerPayloads()`.
- Novo receiver global: só o mestre pode mudar o horário; normaliza com `Math.floorMod(..., 24000)`; aplica `ServerLevel.setDayTime(time)`; confirma com mensagem ao mestre.
- Imports adicionados: `Component`, `ServerLevel`.

### 2. `src/main/java/com/pedro/tabletoprpg/MasterCommands.java`
- Novo comando `/rpg time <0-24000>` (só mestre) -> `setWorldTime()`: aplica `setDayTime` e faz broadcast.
- Novo comando `/rpg hiddenroll <formula>` -> `hiddenRoll()`: rola a fórmula e envia o resultado **apenas para quem rolou** (via `sendSystemMessage`), sem broadcast. Funciona para mestre e jogadores.
- Botão `[Hidden Roll]` (suggest) adicionado à seção Actions do menu ASCII do mestre.
- Import adicionado: `IntegerArgumentType`.

### 3. `src/client/java/com/pedro/tabletoprpg/client/TimeSlider.java` (NOVO)
- Slider baseado em `AbstractSliderButton` (API 1.21.11: construtor com valor 0.0-1.0, `updateMessage()`/`applyValue()` abstratos).
- Converte ticks do Minecraft (0-24000) <-> valor normalizado; rótulo mostra horário formatado ("06:00").
- Callback `IntConsumer` recebe o horário em ticks ao soltar o slider.

### 4. `src/client/java/com/pedro/tabletoprpg/client/RpgMenuScreen.java`
- Slider de horário adicionado ao `buildMenu()`, **apenas quando `isMaster`**, abaixo dos botões, inicializado com `minecraft.level.getDayTime()`.
- Novo método `sendTime(int)` envia `TimeSetPayload` via `ClientPlayNetworking.send(...)`.
- Imports adicionados: `ClientPlayNetworking`, `RpgNetworking`.

## Files Changed
| Arquivo | Ação |
|---|---|
| `src/main/java/com/pedro/tabletoprpg/RpgNetworking.java` | Payload + receiver de horário |
| `src/main/java/com/pedro/tabletoprpg/MasterCommands.java` | `/rpg time`, `/rpg hiddenroll`, botão menu |
| `src/client/java/com/pedro/tabletoprpg/client/TimeSlider.java` | Criado |
| `src/client/java/com/pedro/tabletoprpg/client/RpgMenuScreen.java` | Slider do mestre + sendTime |

## Decisions
1. **Slider só para o mestre**: jogadores não veem o controle de horário (não faz sentido para eles).
2. **`hiddenroll` para qualquer um** (não só mestre): permite ao mestre pedir um teste secreto ao jogador sem expor o resultado no chat público.
3. **`IntConsumer` em vez de `Runnable` no slider**: evita a referência circular (lambda capturando o widget no próprio inicializador) e simplifica o envio (`this::sendTime`).
4. **`Math.floorMod` para normalizar**: aceita qualquer int (inclusive negativos) e converte para 0-23999 com segurança.
5. **`/rpg time` também por chat**: além do slider, o mestre pode definir o horário digitando (consistente com o restante do mod e útil para testes).

## Validation
- `.\gradlew build --no-daemon --console=plain` -> **BUILD SUCCESSFUL in 9s** (exit 0).
- Jar `build/libs/tabletop-rpg-1.0.0.jar` contém: `TimeSlider.class`, `RpgNetworking$TimeSetPayload.class`, `MasterCommands.class`, `RpgMenuScreen.class`, `MinecraftMixin.class`.
- API verificada com javap antes de codificar: `AbstractSliderButton` (construtor + métodos abstratos), `Level.getDayTime()`, `ServerLevel.setDayTime(long)`, `ByteBufCodecs.VAR_INT`, `AbstractWidget.setMessage`.

## Problems Encountered
- **Build FAILED (1 erro)**: `variable timeSlider might not have been initialized` em `RpgMenuScreen.java:121` — a lambda `() -> sendTime(timeSlider.getTimeOfDay())` referenciava o slider dentro do próprio inicializador.
- **Correção**: `TimeSlider` passou a receber `IntConsumer` (o horário em ticks como argumento) em vez de `Runnable`; `RpgMenuScreen` usa `this::sendTime`. Build re-executado com sucesso.

## Root Causes
- FACT: Java não permite capturar uma variável local na lambda que a inicializa (não é effectively final naquele ponto).
- INFERENCE: o padrão "callback recebe o valor" é mais limpo que "callback captura o widget" para este caso.

## Fixes
- `TimeSlider` com `IntConsumer onApply`; `applyValue()` chama `onApply.accept(getTimeOfDay())`.
- `RpgMenuScreen` cria o slider inline com `this::sendTime`.

## Remaining Issues
- **Teste em jogo pendente** (usuário): arrastar o slider no menu do mestre e verificar o horário mudar; `/rpg time 6000`; `/rpg hiddenroll d20` (só quem rolou vê).
- Fase 4 (combate/ações) ainda não iniciada.

## Lessons / Memory
- Atualizado `agent/memory/project-memory.md`: arquitetura (TimeSetPayload, TimeSlider, novos comandos), API (AbstractSliderButton, getDayTime/setDayTime, VAR_INT, ClientPlayNetworking.send), padrão IntConsumer, e estado das fases (Fase 3 completa).

## Next Steps
1. Usuário testa: slider de horário (menu do mestre), `/rpg time`, `/rpg hiddenroll`.
2. Iniciar Fase 4 (Ações e alcance de combate) — próxima na ordem recomendada.