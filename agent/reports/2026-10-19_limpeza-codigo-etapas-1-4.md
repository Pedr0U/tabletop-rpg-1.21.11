# Limpeza de codigo com o agente Code Cleaner — etapas 1 a 4 (2026-10-19)

## Objetivo

Executar a limpeza/organizacao proposta pelo agente **Code Cleaner** (criado e instalado nesta
mesma data) no projeto TableTop RPG, **uma etapa por vez**, com build de validacao a cada etapa e
aprovacao do usuario antes de seguir. Nenhum commit foi feito.

## Escopo

- Projeto: `C:\Users\Danylo Henrique\Documents\GitHub\tabletop-rpg-1.21.11` (Fabric 1.21.11, Java 21,
  mappings Mojang, pacote `com.pedro.tabletoprpg`).
- Somente limpeza: imports mortos, duplicatas triviais, codigo morto e um bloco de desenho
  duplicado. **Sem** mudanca de arquitetura, rede, persistencia, permissao, mixin, Gradle ou
  `fabric.mod.json`.
- Auditoria previa (somente leitura, delegada ao subagente `code-cleaner`): 91 arquivos Java
  (35 main, 45 client, 11 test); 18 mixins (7 main, 11 client) conferem com as classes fisicas;
  entrypoints `TabletopRpg` (main) e `TabletopRpgClient` (client).

## Etapas executadas

### Etapa 1 — imports e duplicata trivial

- `DiaryNodeScreen.java` e `DiaryScreen.java`: removido o import morto
  `net.minecraft.client.input.KeyEvent`.
- `StatusScreen.java`: removido um `/**` duplicado (abertura de Javadoc repetida).
- Ressalva: `InventoryItemScreen.java` tambem importa `KeyEvent`, mas **tem uso real** — nao tocado.

### Etapa 2 — helpers de rotulo e titulo

- `CharacterSheetScreen.java` ganhou `protected static void drawLabel(GuiGraphics, Font, int boxX,
  String, int boxY)` e `protected static void drawCenteredTitle(GuiGraphics, Font, int screenWidth,
  String|Component)`, mais o import de `net.minecraft.client.gui.Font`.
- Removidos os **3** `private void drawLabel(...)` identicos (Skill, Spell, InventoryItem) e os 3
  blocos de titulo inline; 9 call sites passaram a chamar o helper qualificado.
- O proprio `CharacterSheetScreen.render()` usa o helper.
- **Falha na 1a tentativa (12 erros `cannot find symbol method drawLabel(...)`)**: os helpers foram
  escritos como metodos de instancia, assumindo heranca. Causa raiz: as tres telas estendem
  `Screen` **direto**, nao `CharacterSheetScreen` — de la, o unico acesso possivel e a helper
  `static` com chamada qualificada (`CharacterSheetScreen.drawX(...)`). Corrigido tornando os
  helpers `static`.

### Etapa 3 — codigo morto

Removidos 4 membros privados sem nenhum uso, cada um com a Javadoc orfa correspondente:

| Arquivo | Membro removido |
|---|---|
| `StatusScreen.java` | `private static final int PER_COUNT_DEFAULT = 18;` |
| `DiaryNodeScreen.java` | `private static final int MAX_CRUMBS = 16;` |
| `SheetData.java` | `private static final List<String> LEGACY_ATTRIBUTE_IDS = ...;` |
| `DiceRollScreen.java` | `private boolean hasRollValue() { ... }` |

- Confirmacao de morte: busca literal pelos 4 nomes em todo `src` devolveu **exatamente 4 matches**
  (so as proprias declaracoes).
- Nota: as Javadocs removidas carregavam justificativa de design (scroll da coluna de pericias,
  teto das "migalhas de pao" do diario, migracao de NBT dos ids legados, teste unico de "tem valor"
  da tela de dados). O texto continua recuperavel no git.

### Etapa 4 — aviso de nome duplicado

- `CharacterSheetScreen.java` ganhou `protected static final int COL_NAME_WARN = 0xFF5555;` e
  `protected static void drawNameWarningIfBlank(GuiGraphics, Font, EditBox)`, que concentra a
  condicao `nameBox.getValue().isBlank()` e o desenho do texto "Name required".
- `SkillFormScreen.java` e `SpellFormScreen.java` passaram a chamar o helper (2 linhas cada).
- Verificado: `"Name required"` e `0xFF5555` agora aparecem **uma unica vez** no cliente, dentro de
  `CharacterSheetScreen`.
- **Nao extraido (decisao registrada):** a guarda de 4 linhas no inicio de `save()` dos dois
  formularios (`nameBox.setFocused(true); setInitialFocus(nameBox); return;`) continua duplicada.
  `Screen.setInitialFocus` e `protected` e nao pode ser chamado de um helper estatico fora da
  hierarquia; extrair exigiria lambda/`Consumer` ou uma classe base comum — mais complexo que a
  duplicacao de 4 linhas.

## Arquivos alterados (9, diff total +53 / −102)

`CharacterSheetScreen.java` (+42/−…), `SkillFormScreen.java`, `SpellFormScreen.java`,
`InventoryItemScreen.java`, `StatusScreen.java`, `DiaryNodeScreen.java`, `DiaryScreen.java`,
`DiceRollScreen.java` (client) e `SheetData.java` (main).

## Validacao executada

- `.\gradlew.bat build --no-daemon --console=plain`, com log redirecionado para
  `%TEMP%\opencode\build-etapa3.log` / `build-etapa4.log`:
  **BUILD SUCCESSFUL** em 25s (Etapa 1), 18s (Etapa 2, apos a correcao), 21s (Etapa 3) e 19s (Etapa 4).
- `git diff` revisado apos cada etapa; nada fora do previsto.
- Avisos `LF will be replaced by CRLF` sao do `core.autocrlf` e nao indicam mudanca de conteudo.

## Limitacoes / pendencias

1. **NAO validado em jogo.** Pendencia de teste do usuario: abrir a ficha e as telas de formulario
   (Skill, Spell, Item) e conferir (a) rotulos alinhados 4 px antes da coluna das caixas,
   (b) titulo centralizado no topo (y = 8), (c) aviso "Name required" vermelho logo **abaixo** da
   caixa de nome quando ela esta vazia, (d) capacidade de salvar normalmente.
2. `gradlew build` valida call sites e compilacao, **nao** comportamento visual nem injecao de mixin.
   Nesta sessao nenhum mixin foi tocado.
3. Achado fora do escopo (nao tocado): `agent/memory/project-memory.md` deste projeto contem um
   conflito de stash **nao resolvido** — linha 3903 `<<<<<<< Updated upstream` e linha 4246
   `>>>>>>> Stashed changes`, sem `=======` visivel. O conteudo entre os marcadores pode estar
   duplicado. Como a regra da memoria aqui e append-only, nada foi alterado; precisa de decisao do
   usuario.

## Aprendizados duraveis

- As telas de formulario (`SkillFormScreen`, `SpellFormScreen`, `InventoryItemScreen`) estendem
  `Screen` **direto** e usavam `CharacterSheetScreen` apenas como namespace de constantes
  (`COL_LABEL`, `COL_TITLE`). Para compartilhar comportamento, o helper tem de ser `static` e a
  chamada, qualificada.
- `rg` **nao existe** no PATH desta maquina (PowerShell) — as buscas foram feitas com a ferramenta
  de busca interna do agente.
- `Get-Content | Measure-Object -Line` **nao conta linhas vazias**: relatou 3547 linhas para o
  arquivo de memoria de engenharia, que na verdade tem ~4246. Ao citar tamanho de arquivo, use
  contagem honesta.
- Remover codigo morto e barato e seguro quando cada nome aparece uma unica vez em `src` — mas as
  Javadocs orfas costumam ser a unica fonte do **porque**; registrar o que saiu antes de apagar.

## Proximos passos sugeridos

- Teste em jogo dos 4 pontos da secao "Limitacoes".
- Decidir sobre o conflito de stash na memoria de engenharia (itens 1 e 3).
- Candidatos de limpeza ainda nao executados, se o usuario quiser: arquivos muito grandes
  (`StatusScreen` 4350, `RpgNetworking` 3125, `SheetData` 2803, `ThreatSheetScreen` 1999,
  `MasterCommands` 1624 linhas) — cada um exige etapa propria, com aprovacao e build.
