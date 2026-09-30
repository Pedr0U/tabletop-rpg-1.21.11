# Relatório 2026-09-30 (fase 1) — 3 abas na ficha do jogador

## Objetivo

Primeira das 3 fases das abas na ficha. Esta fase e **so estrutura de abas**, sem
nenhum dado novo: 3 abas navegaveis por setinha embaixo, a aba 1 com a ficha atual
inteira, a aba 3 em branco. As abas 2 e 3 entram nas fases seguintes.

## Decisoes do usuario (30/09/2026)

1. Fase 1 agora, com validacao em jogo entre as fases.
2. **Aba 1 e a ficha atual** (as duas colunas de hoje). A tela de Skills **continua
   separada, sem mexer**, e a aba 3 fica em branco.
3. Navegacao por **duas setinhas embaixo**, mais o nome da aba ativa no meio.
4. Peso decimal com 2 casas e texto grande ate 2.000 caracteres: decisoes de fase 2,
   ja fixadas aqui para nao se perder.

## Alteracoes

Arquivo unico: `src/client/java/com/pedro/tabletoprpg/client/StatusScreen.java`
(+161 linhas, 0 remocoes).

- `activeTab` com `TAB_NAMES = {"Character", "Backstory", "Skills"}` e `tabCount()`.
- `buildPanel` virou despachante: aba 1 chama `buildCharacterTab` (o corpo antigo,
  **renomeado**, sem uma linha de logica alterada — o alinhamento `perTitleH = rowH`
  das pericias, pedido em 29/09, ficou intacto) com `bottomY - TAB_BAR_H`; abas 2 e 3
  nao criam widget; nos 3 casos chama `addTabBar`.
- `addTabBar`: dois `Button` de 20x20 em `bottomY - TAB_BAR_H`, texto `<` e `>`, tooltip
  "Aba anterior"/"Proxima aba", e `active = false` na ponta sem pagina.
- `changeTab`: clampa em `[0, tabCount()-1]`, salva e restaura o scroll das duas colunas
  por aba (`tabLeftScroll`/`tabPerScroll`) e so entao chama `rebuildWidgets()`.
- `render`: desenha o nome da aba **depois** de `super.render()`, fora de `textLines`.
- `mouseScrolled`: guarda `if (activeTab != 0)`.

**Nao alterado:** `CharacterSheetScreen` (base compartilhada com `SkillsScreen`),
`SheetData`, `RpgNetworking`, `SessionManager`, codecs, NBT, testes e documentos.

## Arquivos alterados

| Arquivo | Mudanca |
|---|---|
| `src/client/java/com/pedro/tabletoprpg/client/StatusScreen.java` | despachante de abas, barra de setinhas, scroll por aba, nome da aba |
| `FUNCIONALIDADES-E-COMANDOS.md` | secao da ficha descreve as 3 abas |
| `agent/memory/project-memory.md` | fatos da fase 1 |

## Validacoes

| Gate | Resultado |
|---|---|
| `.\gradlew.bat build --no-daemon --console=plain` | `BUILD SUCCESSFUL in 17s` |
| `scanEncoding` | OK: 103 arquivos, 0 mojibake, 0 ideograma, 0 U+FFFD |
| `check-catalogo.ps1` | 1 divergencia, pre-existente (`/rpg/roll/Pericia/0-2`, falso positivo de exemplo do jogador) |
| `runClient` | subiu sem crash, **mas nao serve como prova** |

**O `runClient` deste lote NAO valida a fase.** O jogo subiu, o log mostra
`Tecla R pressionada -> abrindo RpgMenuScreen` e depois `Stopping!` com
`All dimensions are saved`, sem nenhuma `Exception` — mas o cliente ficou **7 segundos**
na tela: o usuario abriu o menu e fechou. **Nao ha evidencia de que a aba tenha sido
vista.** O que o log prova e apenas que a classe nova **carrega sem crash**.

## Problemas encontrados

1. **`git diff --stat` nao serve para conferir trabalho de subagente.** E cumulativo
   desde o HEAD. A confericao que vale e `Select-String` no simbolo novo
   (`TAB_NAMES`, `addTabBar`, `changeTab`, guarda de `mouseScrolled`) e depois ler o
   trecho do arquivo.
2. **`Button.Builder.tooltip` nao recebe `Component`.** A assinatura real e
   `tooltip(Tooltip)`, com `Tooltip.create(Component)` para montar. Inferir "deve
   aceitar Component" compila como erro so no build; conferido no jar remapeado.
3. **O detector de catalogo nao esta no projeto.** `check-catalogo.ps1` fica na pasta da
   skill, e rodar com `-File .\scripts\check-catalogo.ps1` falha.

## Limitacoes

- As abas 2 e 3 estao **vazias** nesta fase, de proposito.
- Nao ha teste unitario de tela de cliente: layout, posicao das setinhas e volta do
  scroll so se vendo no jogo.
- A perda dos 20 px da barra pode empurrar mais linhas para o scroll da coluna esquerda
  do que antes.

## Aprendizados duraveis

1. `CharacterSheetScreen` e base **compartilhada** com `SkillsScreen`: mudanca de
   layout que so vale para a ficha do jogador vai em `StatusScreen`.
2. `init()` nao e lugar para guardar estado que precisa sobreviver a `rebuildWidgets()`:
   ele roda em resize, troca de modelo e rolagem.
3. Rótulo de aba desenhado dentro da lista de `textLines` rola com o conteudo.
4. `AbstractWidget.active` e campo publico; `Tooltip.create(...)` e a ponte para tooltip.

## Proximos passos

- **Ver a fase 1 em jogo**: abrir a ficha (tecla R -> ficha) e confirmar que a setinha
  `>` leva para `Backstory`, que `<` volta, que a ponta desabilitada e cinza, que o
  nome da aba nao se mistura com o conteudo e que o scroll da coluna esquerda volta
  como estava ao voltar para a aba 1.
- So depois: fase 2 (Character Appearance e Character Backstory como campos grandes com
  rolagem) e fase 3 (Inventory).
- Commit/tag **nao feitos**: o checkpoint `7ace0fd` /
  `checkpoint-20260930-1412-antes-das-abas-da-ficha` e de **antes** desta fase.