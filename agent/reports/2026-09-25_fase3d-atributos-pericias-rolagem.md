# Implementation Report

## Status
**COMPLETA** — build verde (`gradlew build` = BUILD SUCCESSFUL), revisada por subagente, 2 bugs [MÉDIO] encontrados na revisão e corrigidos, `.docx` atualizado e validado como XML.

## Objective
Fechar a rodada de feedback do usuário (7 itens) na ficha de personagem do mod Fabric 1.21.11 `tabletop-rpg-template-1.21.11-main`:

1. Personagem com HP 0 deitava e levantava em milésimos, ficando **em pé com 0 de vida**.
2. Vida/mana já corrigidos (mantidos da FASE 3c).
3. Atributo acima de 30 travava a digitação ("apago o 3 e o 3 volta").
4. Atributos devem aceitar **valor negativo**.
5. 20 perícias, cada uma com **valor + atributo**, roláveis via `/rpg roll`.
6. Na tela de Skills, o campo de **nome** deve ficar **acima** do de descrição.
7. O `.docx` é a fonte da spec e precisa acompanhar as decisões.

## Scope / Subtasks
Executados como subtasks atômicos, um a um, cada um validado antes do seguinte:

| # | Subtask | Validação |
|---|---------|-----------|
| 1 | Pose deitado no cliente + broadcast por UUID | `compileClientJava` |
| 2 | Edição numérica (filtro, valor incompleto, campo em foco) | `compileClientJava` |
| 3 | Atributos sem clamp, default 0, `withField` sem `parseStat` | `compileJava` |
| 4 | Enum `Attribute` + `Skill` com valor/atributo + codec | `compileJava` |
| 5 | 20 perícias padrão em `defaultSheet()` | `compileJava` |
| 6 | `SheetSkillPayload` com `SkillOp` + receptor | `compileJava` |
| 7 | `StatusScreen`: atributos como setas | `compileClientJava` |
| 8 | `SkillsScreen`: setas, dropdown, nome acima da descrição | `compileClientJava` |
| 9 | `/rpg roll` integrado | `build` |
| 10 | Correções da revisão (9 achados) | `build` |
| 11 | `.docx` atualizado e validado | XML parse OK |

## What Changed

### 1. Pose deitado com HP 0 (item 1)
**FACT — causa raiz:** a pose do jogador é recalculada em `Player.tick()` → `updatePlayerPose()`, e **não** em `aiStep()`. O `LocalPlayerMixin` cancelava `aiStep` e portanto não impedia nada — o próprio comentário dele afirmava o contrário e estava errado. O cliente sobrescrevia `SWIMMING` → `STANDING` a cada tick, e como o servidor não mudava mais a pose, não reenviava metadata. Resultado: personagem em pé com HP 0 permanente.

**FACT — segunda descoberta:** o vanilla recalcula a pose de **todos** os jogadores no cliente, não só do local. Com o estado indo só ao dono, o mestre veria os caídos em pé. Por isso `DownedStatePayload` carrega o **UUID** e é transmitido a todos.

- `ClientPlayerPoseMixin` (NOVO): corta `updatePlayerPose` e reaplica `SWIMMING`.
- `RpgNetworking.sendDownedState`: broadcast para todos os jogadores.
- `RpgNetworking.sendDownedSnapshotTo` (NOVO): no JOIN, manda o estado de todos para quem entra — sem isso, quem reconecta depois de alguém cair não saberia de ninguém e o vanilla levantaria o caído na frente dele.
- `TabletopRpgClient.downedPlayers` (Set por UUID) + limpeza no DISCONNECT.

### 2. Digitação numérica travada (itens 3 e 4)
**FACT — três defeitos somados:**
1. o filtro aceitava só dígitos — valor negativo era impossível;
2. o servidor devolvia a ficha a cada tecla e `applySheetToWidgets` sobrescrevia o campo em edição (eco);
3. texto vazio/incompleto caía no fallback do servidor, que mantinha o valor antigo.

Correções: filtro `-?\d{0,9}` com `setMaxLength(10)`, `isCompleteNumber()` impede envio de valores incompletos, e `applySheetToWidgets` pula a caixa com foco.

### 3. Modelo (itens 4 e 5)
- `Attribute` (enum, 6 valores) com `field()`, `abbr()`, `fullName()`, `find()`, `decode()`.
- `Skill` de 2 → 4 campos (`name, description, value, attribute`), valor clampado em 0..3, `rollBonus(SheetData)`.
- `SkillOp` (ADD/REMOVE/SET_VALUE/SET_ATTRIBUTE/INVALID) com codec manual validado.
- `Attributes` **sem clamp**; `defaults()` = tudo 0; `clampStat`/`parseStat` removidos, `MAX_STAT` depreciado.
- `withSkillValue`, `withSkillAttribute`, `mutateSkill` (tocam um campo só, preservando a descrição).
- 20 perícias: Luta 2/FOR, Acrobacia 3/DES, Diplomacia 1/CAR, Iniciativa 2/DES + "perícia 0".."perícia 15" com valor 0 e atributos alternando os 6.

### 4. UI
- `StatusScreen`: as 6 `EditBox` viraram linhas `rótulo [<] número [>]`; o número é desenhado em `renderContent` e cortado com `plainSubstrByWidth`.
- `SkillsScreen`: reescrito. Nome acima da descrição; 4 widgets por linha (remover, `<`, `>`, atributo); mapas otimistas `pendingValue`/`pendingAttribute`.
- `AttributePickerScreen` (NOVO): lista suspensa dos 6 atributos como tela separada, para o painel ficar acima dos widgets das outras linhas.

### 5. `/rpg roll`
- Sem argumento: **lista** as perícias (com valor, atributo e total). **Mudança de comportamento** — antes rolava `d20`.
- Com nome de perícia: `1d20 + valor + atributo`, desdobrado ("d20 (12) + 2 + 3 = 17"), RNG do servidor.
- Caso contrário: fórmula de antes (`d20`, `2d6`, `2d6+d4+3`).
- Autocompletar dos nomes; comparação ignora acentos ("perícia 0" ↔ "pericia 0").

## Files Changed
**Criados:**
- `src/client/java/com/pedro/tabletoprpg/client/mixin/ClientPlayerPoseMixin.java`
- `src/client/java/com/pedro/tabletoprpg/client/AttributePickerScreen.java`

**Modificados:**
- `src/main/java/com/pedro/tabletoprpg/SheetData.java`
- `src/main/java/com/pedro/tabletoprpg/RpgNetworking.java`
- `src/main/java/com/pedro/tabletoprpg/MasterCommands.java`
- `src/client/java/com/pedro/tabletoprpg/client/StatusScreen.java`
- `src/client/java/com/pedro/tabletoprpg/client/SkillsScreen.java`
- `src/client/java/com/pedro/tabletoprpg/client/CharacterSheetScreen.java`
- `src/client/java/com/pedro/tabletoprpg/client/TabletopRpgClient.java`
- `src/client/resources/tabletop-rpg.client.mixins.json`

**Fora do repositório:**
- `C:\Users\Pedro\Downloads\ModTableTop\Documento de Arquitetura e Relatório de Desenvolvimento.docx`

## Decisions
Decisões do **usuário** (perguntadas, não inventadas):
- 4 perícias iniciais: Luta 2/FOR, Acrobacia 3/DES, Diplomacia 1/CAR, Iniciativa 2/DES. Iniciativa é a mais importante (define os turnos no combate).
- Atributos sem limitação, por setas −/+ (passo 1), começando em 0, sem barra, só o número.
- As 16 padrão: todas com valor 0, atributos alternando os 6.
- Perícias editáveis por setas pequenas + botão de lista suspensa; **nome acima da descrição**.

Minhas, assumidas e registradas:
- **Precedência:** as mensagens mais recentes do usuário superam o `.docx` antigo. Divergências corrigidas no documento: Mana 5→**4**; atributos 2→**0**; "perícia 1..20"→**4 nomeadas + "perícia 0".."perícia 15"**; 5→**6** atributos (entra SAB/Sabedoria). **Racional:** o `.docx` é a spec, mas descreveria um estado que o usuário rejeitou explicitamente.
- **Perícia nova nasce com valor 0 e DES** (mesmo default do modelo); o ajuste fino é feito nas setas da lista, como pedido.
- **`/rpg roll` sem argumento virou lista** em vez de rolar `d20`; `d20` puro continua disponível como `/rpg roll d20`.
- **Dropdown como tela separada**, não popup desenhado na `SkillsScreen`: `Screen` desenha os widgets depois do conteúdo, então um popup ficaria sob os botões das outras linhas e os cliques precisariam ser interceptados à mão.
- **Aritmética das rolagens em `long`**: o atributo não tem teto, e `int` estouraria com valor absurdo.

## Validation
- `gradlew build` → **BUILD SUCCESSFUL** (executado 2×: após as correções de revisão; antes disso, `compileJava` e `compileClientJava` verdes separadamente).
- `test` = **NO-SOURCE** (o projeto não tem testes). Não há cobertura automatizada.
- Revisão por subagente `reviewer`: **aprovada com ressalvas** — 2 bugs [MÉDIO], 7 [BAIXO] corrigidos; 2 deliberadamente não corrigidos (ver pendências).
- `.docx`: zip com 12 entradas, `[Content_Types].xml` e `word/document.xml` presentes, `XmlDocument.LoadXml` **sem erro**, nenhum caractere de controle inválido, nenhum mojibake restante.
- **Não validado em runtime:** o jogo não foi executado. A pose deitado, a lista suspensa e o `/rpg roll` precisam de um teste manual no Minecraft.
**Validado por leitura de código (não por execução):** `withField` é à prova de payload malformado — `field` desconhecido cai em `default -> this` e `parseInt` captura `NumberFormatException`, portanto nenhum texto do cliente derruba o servidor. `sanitizeSkills` reconstrói cada `Skill` pelo construtor (reaplica o clamp 0..3 e converte atributo nulo em DES), deduplica por `equalsIgnoreCase` e corta em `MAX_SKILLS`.

## Problems Encountered

### Build falhou 4× seguidas ao usar `StringRepresentable.EnumCodec`
**Causa raiz (FACT, via `javap`):** `EnumCodec` **não é um `StreamCodec`**. A classe pai `StringRepresentable$StringRepresentableCodec` implementa apenas `com.mojang.serialization.Codec` (JSON/DataFixerUpper). O nome da classe engana.

**Erros em sequência:** (1) `fromEnum` exige `Supplier<E[]>`, eu passava `Supplier<List<E>>`; (2) falha de inferência de tipo; (3) com `<Attribute>` explícito, mensagem direta: `EnumCodec<Attribute> cannot be converted to StreamCodec<ByteBuf,Attribute>`; (4) `xmap` não existe em 1.21.11 — o método é **`map`**.

**Lição:** ajustar type witness não resolveria — a premissa (que `EnumCodec` fosse `StreamCodec`) estava errada. Quando o tipo não casa, parar de ajustar a inferência e confirmar com `javap` qual interface a classe implementa.

**Correção:** codec escrito à mão, `ByteBufCodecs.stringUtf8(16).map(Attribute::decode, Attribute::abbr)`. Bônus: aceita nome completo e cai em DES para entrada inválida, em vez de derrubar a conexão.

Outros erros de build: `readVarInt`/`writeVarInt` são do `FriendlyByteBuf` (não do `ByteBuf` puro) → `ByteBufCodecs.VAR_INT`; `CommandSourceStack#getRandom()` e `MinecraftServer#getRandom()` **não existem** em 1.21.11 → `Entity#getRandom()`; `@Override` em método estático por inserção de código na região errada.

### `.docx`: 5 edições falharam sem erro visível
**Duas causas somadas:**
1. **PowerShell 5.1 lê `.ps1` sem BOM como ANSI.** Todo acento dentro do script virava lixo, e o `String.Replace` apenas "não encontra" o texto. Sem mensagem de erro.
2. **O console também não renderiza acentos**, o que me deu uma leitura visual errada: o travessão do documento é **U+2014**, e o console o mostrava como `-`. Eu "corrigi" para hífen com base nessa leitura.

**Correção:** isolar o texto num `.json` lido com `[System.IO.File]::ReadAllText(..., UTF8)`, e extrair os codepoints reais para um arquivo, lido com a ferramenta correta. A seção 8 já havia sido gravada com mojibake e foi reparada com o caminho inverso (`UTF8.GetString(cp1252.GetBytes(...))`), **apenas naquele bloco** — passar o documento inteiro por ali corromperia o que já estava certo.

**Erro de processo meu:** rodei `gradlew build` duas vezes na mesma invocação, duplicando o log.

## Root Causes
1. **HP 0:** `updatePlayerPose()` roda a cada tick em `Player.tick()`; o corte estava em `aiStep()`, que nunca era chamado. Commentário existente afirmava o contrário — errado.
2. **Atributo travado em 30:** ausência de clamp no modelo **e** eco da ficha sobrescrevendo o campo em edição **e** valor incompleto interpretado como "mantém o anterior".
3. **`EnumCodec`:** suposição errada sobre a hierarquia de classes da API do Mojang.
4. **Edições no `.docx`:** codificação de script sem BOM + leitura visual via console que não renderiza acentos.

## Fixes
Além dos itens acima, os achados da revisão:
- **`sendDownedSnapshotTo` no JOIN** [MÉDIO] — quem entra depois de alguém cair via flicker.
- **"Add" com nome existente não apaga mais valor/atributo** [MÉDIO] — `findSkill()` no cliente. Era perda de dado silenciosa: corrigir a descrição de "Luta" (3, FOR) fazia tudo voltar a 0/DES.
- **`SkillOp.INVALID`** — índice corrompido não vira mais `ADD` destrutivo.
- **`isPauseScreen() → false`** no dropdown (o jogo pausava em singleplayer/LAN).
- **Hook `onSheetReceived()`** — o otimismo não morre mais ao fechar o dropdown, que dispara `init()`.
- **`saturatingAdd`** no `stepNumeric` — atributo em `Integer.MAX_VALUE` +1 virava `MIN_VALUE` e era gravado.
- **Clipping** do número do atributo, limpeza de `downedPlayers` no DISCONNECT, remoção do sombreamento `attrRows`, javadoc do `SheetData` corrigido (afirmava clamp onde não há).

## Remaining Issues
1. **Não validado em runtime.** O jogo não foi executado. Cenário manual obrigatório: mestre entrando/reconectando com jogador já caído; editando valor/atributo de uma perícia; `/rpg roll <perícia>` e `/rpg roll` sem argumento.
2. **Pacote de posição descartado enquanto deitado** — `ServerGamePacketListenerImplMixin` cancela o pacote inteiro. O revisor marcou como *hipótese não testada*: se o caído estiver no ar, o Y pode divergir. **Não corrigido de propósito** (mudança em pacote de movimento é arriscada sem teste em jogo; o padrão melhor seria projetar a posição do servidor, como o branch da aura faz logo abaixo).
3. **Nome ambíguo em `/rpg roll`** — "perícia 0" e "pericia 0" podem coexistir (a deduplicação do modelo compara com `equalsIgnoreCase`, sem normalizar acento); o comando devolve a primeira, em silêncio. Mudar a semântica de deduplicação é decisão do usuário.
4. **Sem testes automatizados.** O projeto não tem suíte; toda a validação é build + revisão de leitura. Roteiro mínimo sugerido ao revisor: `Skill` (clamp 0..3, atributo nulo), `Attribute.find/decode`, `SkillOp` com índice 99, `withSkillValue` preservando a descrição, `defaultSkills().size() == 20` com Luta 2/FOR e a rotação dos 6.
5. **Nada commitado.** HEAD `58aae44`; FASE 3, 3b, 3c e 3d estão todas no working tree.
6. **Não há persistência de ficha (lacuna pré-existente, não é regressão desta fase).** Verificado nesta rodada: `SessionManager.characterSheets` é um `static final Map<UUID, SheetData> = new HashMap<>()`, puramente em memória. Não existe `CompoundTag`, `saveAdditional`/`readAdditional`, NBT, nem escrita em arquivo em nenhum ponto do código do mod. **Consequência:** as fichas são perdidas ao fechar o servidor, e `Skill` ter passado de 2 para 4 campos **não tem nenhum impacto de desserialização** — o único codec é o de rede, e os dois lados rodam o mesmo build. Isso também elimina qualquer risco de migração de fichas antigas: toda sessão nova já nasce em `defaultSheet()`, que cria as 20 perícias novas. Se a intenção do produto for preservar a ficha entre sessões, isso ainda precisa ser construído (e aí sim exigirá versionamento do formato).

## Lessons / Memory
Gravadas em `agent/memory/fase3-ficha-checklist.md`:
- **`StringRepresentable.EnumCodec` não é `StreamCodec`** (é `Codec` de JSON); em 1.21.11 o método é `map`, não `xmap`; `readVarInt` só existe no `FriendlyByteBuf`.
- **PowerShell 5.1 lê `.ps1` sem BOM como ANSI** — não colocar acento em script; usar arquivo de dados com `ReadAllText(..., UTF8)`. E o console não renderiza acentos: extrair codepoints para um arquivo em vez de confiar no `Write-Host`.
- **Regra de escopo:** preferi corrigir as linhas da spec que ficaram factualmente erradas *e* anexar a seção 8. Deixar a spec errada e apenas anexar a seção criaria duas fontes em conflito; apagar o histórico apagaria o registro das rodadas anteriores.

## Next Steps
1. Teste manual no jogo dos 3 cenários acima.
2. Decidir sobre o pacote de movimento do caído (pendência 2) — requer teste com o jogador no ar.
3. Commit da FASE 3 + 3b + 3c + 3d em conjunto, com mensagem no estilo do repositório.
4. Montar a lista de iniciativa (HUD e comando `/rpg initiative set`), que agora pode usar `Skill.rollBonus()` e o `Iniciativa` 2/DES já configurado.
