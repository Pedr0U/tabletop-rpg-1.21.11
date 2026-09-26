# Implementation Report — FASE 3: Ficha do Personagem

## Status

**CONCLUÍDA (código) — validação de runtime PENDENTE.**
Compila e empacota com sucesso. Não há teste automatizado no repositório e a
ficha ainda não foi testada dentro do jogo (precisa de servidor + 2 clientes).

## Objective

Menu de status/habilidades do RPG tabuleiro: ficha por jogador com
nome, raça, classe, nível, XP, HP/Mana e atributos, mais lista de
habilidades. O mestre abre a ficha de qualquer jogador; cada jogador abre a
própria; a edição é bidirecional e em tempo real.

## Scope / Subtasks

1. Modelo de dados `SheetData` com codec e validação por construção.
2. Estado das fichas por jogador no `SessionManager`.
3. Payloads + receptores de rede com permissão e broadcast.
4. Validação do servidor (`compileJava`).
5. Tela de ficha (`CharacterSheetScreen`) + receptor S2C no cliente.
6. Integração no menu ("My Sheet") e na lista de jogadores (mestre).
7. Build completo + revisão de código + correções.

## What Changed

- Novo modelo `SheetData` (record) dividido em sub-records para caber no
  limite de 6 campos do `StreamCodec.composite`.
- Todos os construtores compactos **limitam** os valores: uma instância
  inválida não pode existir, independentemente de quem a construiu.
- Quatro payloads novos (query, state, field, skill) e seus receptores.
- Leitura de ficha restrita a dono + mestre; escrita a dono + mestre.
- Tela de ficha com campos de texto, campos numéricos e lista de
  habilidades com adicionar/remover/rolar.
- Botão "My Sheet" no menu; na lista de jogadores o mestre abre a ficha de
  quem clicar.

## Files Changed

| Arquivo | Tipo | Linhas |
|---|---|---|
| `src/main/java/com/pedro/tabletoprpg/SheetData.java` | novo | ~400 |
| `src/main/java/com/pedro/tabletoprpg/SessionManager.java` | alterado | +62 |
| `src/main/java/com/pedro/tabletoprpg/RpgNetworking.java` | alterado | +230 |
| `src/client/java/com/pedro/tabletoprpg/client/CharacterSheetScreen.java` | novo | ~380 |
| `src/client/java/com/pedro/tabletoprpg/client/TabletopRpgClient.java` | alterado | +30 |
| `src/client/java/com/pedro/tabletoprpg/client/RpgMenuScreen.java` | alterado | +17 |
| `src/client/java/com/pedro/tabletoprpg/client/PlayerListScreen.java` | alterado | +45 |

## Decisions

- **Nome, não UUID, para identificar o alvo.** A lista de jogadores já chega
  ao cliente como nomes e o servidor resolve nome → jogador. Evita espalhar
  UUIDs pela UI e mantém compatibilidade com clientes antigos.
- **Um payload genérico de campo** (`field` + `value` em texto) em vez de um
  payload por campo. Menos código de rede; a conversão e o clamp ficam
  concentrados em `SheetData.withField`.
- **Invariantes no record, não no receptor.** Os construtores compactos
  limitam tudo, então o servidor não depende de lembrar de validar.
- **UI por `EditBox` para texto e número**, com filtro de dígitos nos
  numéricos. Evita a complexidade de botões +/- por campo.
- **A ficha é privada.** Leitura só para o dono e o mestre — mais restrito
  que a edição. Um jogador comum não abre a ficha de outro nem em modo
  somente-leitura.
- **Escrita por tecla digitada** (cada alteração vai ao servidor) para dar
  a sensação de tempo real. O servidor é a autoridade: a UI só substitui
  valores quando a ficha volta.
- **Estado em memória.** A ficha se perde ao fechar o servidor (ver
  "Remaining Issues").

## Validation

| Comando | Resultado |
|---|---|
| `gradlew compileJava` (servidor) | BUILD SUCCESSFUL in 7s |
| `gradlew build` (antes da revisão) | BUILD SUCCESSFUL in 5s |
| `gradlew build` (após as correções) | BUILD SUCCESSFUL in 5s |

- Assinaturas de `EditBox`, `Screen`, `GuiGraphics`, `Font` e
  `PlayerList.getPlayerByName` conferidas com `javap` no jar mapped do
  Minecraft 1.21.11 antes de escrever o código.
- **Não executado:** teste dentro do jogo, cliente com 2 jogadores,
  `gradlew test` (o projeto não tem fonte de teste: `test NO-SOURCE`).

## Problems Encountered

1. **Rótulos desenhados em `init()`.** A primeira versão da tela chamava
   `font.drawString` dentro de `init()`, que não tem contexto de render — os
   rótulos e títulos das seções simplesmente não apareceriam. Corrigido
   acumulando os textos numa lista e desenhando em `render()`.
2. **Revisão encontrou 5 defeitos** (detalhados abaixo), sendo 3 de
   severidade alta. Todos corrigidos antes da entrega.
3. **Débito próprio de contexto:** o checkpoint anterior da FASE 3 afirmava
   que o código já estava implementado. Não estava — a árvore Git estava
   limpa. O arquivo foi corrigido para não induzir a próxima sessão ao erro.

## Root Causes

Causas com evidência direta:

1. **`characterName` nunca era salvo.** Em `SheetData.withField` a chave é
   convertida com `toLowerCase`, mas o `case` do nome estava escrito em
   camelCase (`"characterName"`), então caía no `default` e a edição era
   descartada. `getText`/`labelOf` usavam minúsculas corretamente — a
   inconsistência era só nesse `case`.
2. **Vazamento de ficha alheia.** O receptor de `SheetQueryPayload` resolvia
   o alvo e respondia sem checar permissão de leitura. Um cliente comum
   poderia forjar o payload com o nome de outro jogador e receber a ficha
   dele (não editava, mas vaziava o dado).
3. **Tela da própria ficha aceitando ficha alheia.** O filtro de
   `onSheetState` só valia quando a tela tinha alvo nomeado. Como o mestre
   também recebe as fichas dos outros, a tela "minha ficha" do mestre
   exibiria a ficha de outro jogador quando outro jogador editasse a dele —
   e as edições seguintes do mestre iriam para a ficha errada.
4. **Layout fixo.** A posição de "Add" e "Back" era calculada a partir do
   conteúdo, então em janelas baixas ambos podiam ficar fora da área
   visível. Pior: `Math.max(y, height - 28)` empurrava o botão *para baixo*,
   para fora da tela.
5. **Scroll órfão.** `skillScroll` não era re-limitado quando a lista
   encolhia, deixando linhas vazias sem como corrigir.

## Fixes

1. `case "charactername"` em minúsculas, alinhado com `getText`/`labelOf`.
2. Novo `canViewSheet` (dono ou mestre), checado no receptor da query **e**
   dentro de `sendSheetTo` (defesa em profundidade, para que nenhum envio
   futuro vaze a ficha). Negação registra log no servidor.
3. `onSheetState` passou a conferir `ownerUuid` contra o UUID do jogador
   local quando a tela é da própria ficha.
4. Rodapé com posição fixa: "Back" em `height - 24`, "Add" logo acima, e a
   lista de habilidades ocupa só o espaço que sobrar (`skillRows` calculado).
5. `clampSkillScroll()` chamado a cada atualização remota.
6. `requestSheet` limita o nome a 64 caracteres, o mesmo teto do codec.

## Remaining Issues

1. **Sem persistência.** As fichas vivem em `HashMap` estático: somem ao
   fechar o servidor. Próxima fase: salvar por jogador em disco (JSON na
   pasta do mundo) e carregar em `getOrCreateSheet`.
2. **Sem vínculo com o combate.** HP/Mana são dados de ficha, não afetam
   dano real, cura ou Mana no jogo. A ficha ainda não está ligada ao sistema
   de combate.
3. **Sem testes automatizados.** O projeto não tem fonte de teste; a
   cobertura é de compilação. Os casos de autorização e o clamp mereceriam
   testes unitários quando houver infraestrutura.
4. **Não validado em runtime.** Falta o teste com servidor e dois clientes:
   mestre edita → jogador vê; jogador edita → mestre vê; e a tentativa de
   um jogador abrir a ficha de outro deve ser negada.
5. **Tela "Loading..." sem timeout.** Se o alvo desconectar antes de
   responder, a tela fica em "Loading" indefinidamente (sem crash). Poderia
   virar uma mensagem "jogador indisponível".

## Lessons / Memory

- `StreamCodec.composite` aceita no máximo 6 campos por chamada. Modelos de
  rede maiores devem ser **quebrados em sub-records**, cada um com seu
  codec — é o que manteve a ficha com 16 campos sem codificação manual.
- `EditBox.setValue` **dispara** o `setResponder` nesta versão. Preencher
  campos a partir do servidor sem desligar a notificação cria um laço de
  eco; a flag `suppressNotify` resolve.
- `ByteBufCodecs.stringUtf8(n)` lança exceção ao decodificar uma string
  maior que `n`. Todo texto vindo da rede precisa ser limitado **antes** de
  entrar no codec, não só depois.
- Em 1.21.11 `mouseClicked` usa `MouseButtonEvent` (quebrou a API antiga de
  dois doubles). `mouseScrolled(double,double,double,double)` continua igual.
- Desenhar texto dentro de `init()` é silenciosamente inútil em `Screen`:
  não há contexto de render nesse ponto.
- Registrar rótulos de widget e desenhar em `render()` é o padrão para
  telas com layout calculado.
- Lições de disciplina de turno em `agent/memory/ai-operational-discipline.md`.

## Next Steps

1. Testar em jogo com dois clientes (mestre + jogador) e conferir a
   sincronização nos dois sentidos.
2. Conectar HP/Mana da ficha ao `CombatController` (dano/cura reais).
3. Persistir as fichas por jogador em disco.
4. Adicionar testes unitários para `SheetData.withField` e para as regras de
   permissão.
5. Commit da FASE 3 (aguardando pedido explícito).
