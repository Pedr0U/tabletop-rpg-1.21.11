# FASE 3 / 3b — Ficha do Personagem (Character Sheet) — STATUS

**Data:** 2026-09-25 (FASE 3b: 2026-09-25)
**Projeto:** Tabletop RPG mod (Fabric 1.21.11), repo `tabletop-rpg-template-1.21.11-main`

> **FASE 3b (2026-09-25) — ATUALIZADO.** A ficha foi reescrita após feedback do
> usuário: fundo escuro, layout responsivo, **HP e Mana como barras com setas**,
> menus separados **Status** e **Skills**, e botões por papel (Jogador:
> Status/Skills/Rolls/Settings; Mestre: Players/Rolls/Settings, sem ficha
> própria). Nova regra de vida: **HP <= 0 = deitado, não morre** (o HP da ficha
> é a fonte da verdade; pode ser negativo). Ver
> `agent/reports/2026-09-25_fase3b-redesign-ficha.md`.
> `CharacterSheetScreen` agora é a **base abstrata**; `StatusScreen` e
> `SkillsScreen` são as telas concretas.

> **CORREÇÃO (2026-09-25).** A versão anterior deste arquivo afirmava que o
> `SessionManager` já tinha o mapa de fichas e que o build quebrava por causa
> disso. **Isso era falso.** Naquele momento a árvore Git estava limpa e
> nenhum código da FASE 3 existia — a alegação veio de uma leitura
> inconsistente de caminho durante a sessão anterior. A FASE 3 foi
> implementada do zero depois. Não confie neste arquivo para estado de código;
> use `git status` / `git diff`.

---

## OBJETIVO

Ficha do personagem por jogador, em tempo real e bidirecional:

- **Campos:** nome, raça, classe, nível, XP, HP/HP máx, Mana/Mana máx,
  atributos (STR/DEX/CON/INT/WIS/CHA) e lista de habilidades.
- **Acesso:** jogador abre a **própria** ficha ("My Sheet" no menu); o
  **mestre** abre a de **qualquer** jogador (menu → "Players" → clicar).
- **Sincronização:** mestre edita → jogador vê; jogador edita → mestre vê.
  O reenvio é feito só para o dono e o mestre.
- **Privacidade:** a ficha alheia é privada — jogador comum não abre a de
  outro, nem em modo somente-leitura.
- **Persistência:** em memória por enquanto (ver pendências).

---

## ESTADO ATUAL

**Código: CONCLUÍDO e compilando** (`gradlew build` = BUILD SUCCESSFUL).

| Arquivo | O que faz |
|---|---|
| `SheetData.java` (novo) | Record com 5 grupos (identity/vitals/progress/attributes/skills), codec próprio e limites aplicados nos construtores compactos. |
| `SessionManager.java` | `Map<UUID, SheetData> characterSheets` + `getSheet`/`getOrCreateSheet`/`setSheet`/`removeSheet`/`sheetCount`. |
| `RpgNetworking.java` | 4 payloads (`SheetQuery`, `SheetState`, `SheetField`, `SheetSkill`), registro, receptores com permissão, `canViewSheet`/`canEditSheet`, `resolveSheetTarget`, `sendSheetTo`, `broadcastSheet`. |
| `CharacterSheetScreen.java` (novo) | Tela de ficha: EditBoxes de texto e número, habilidades com adicionar/remover/rolar, rodapé fixo. |
| `TabletopRpgClient.java` | Receptor S2C de `SheetStatePayload` + `requestSheet(...)`. |
| `RpgMenuScreen.java` | Botão "My Sheet". |
| `PlayerListScreen.java` | Mestre clica num jogador e abre a ficha dele. |

Relatório completo (decisões, bugs, validação):
`agent/reports/2026-09-25_fase3-ficha-personagem.md`

---

## PENDÊNCIAS

0. **Confirmar com o usuário:** o **passo das setas** (hoje `ARROW_STEP = 1`) e
   se **void/`/kill`** ainda devem matar (hoje sim, por decisão documentada).
1. **Teste em jogo** (pendente): servidor + 2 clientes; mestre edita → jogador
   vê; jogador edita → mestre vê; jogador comum tentando abrir ficha alheia
   deve ser negado. Conferir também: deitado/levantar com HP negativo,
   navegação Status ↔ Skills, atalho Skills do mestre, janelas 854x480 e
   1920x1080.
2. **Persistência em disco**: hoje as fichas somem ao fechar o servidor.
   Salvar por jogador (JSON na pasta do mundo) e carregar em
   `getOrCreateSheet`. O estado "deitado" herda essa pendência.
3. **Ligar HP/Mana ao combate**: por decisão do usuário, **nada externo muda a
   ficha** (mob não mexe; só o mestre). O que falta é só areverse: o HP da
   ficha ainda não cura/atinge nada no jogo além do estado deitado.
4. **Testes unitários** para `SheetData.withField` (incluindo HP negativo) e
   para as regras de permissão (o projeto ainda não tem fonte de teste).
5. **Timeout na tela** quando o alvo desconecta antes de responder
   (hoje fica em "Loading..." sem crash).
6. **Pré-existente:** `RpgMenuScreen:101` usa `+ 60` sem escalar em `startY` —
   em janelas Altas com GUI scale grande o "Settings" pode sair da tela.

---

## ARMADILHAS ENCONTRADAS (não repetir)

- `StreamCodec.composite` aceita **no máximo 6 campos** por chamada. Modelos
  grandes precisam ser quebrados em sub-records com codec próprio.
- `EditBox.setValue` **dispara** `setResponder` nesta versão — preencher
  campos com dados do servidor sem desligar a notificação cria laço de eco.
- `ByteBufCodecs.stringUtf8(n)` lança exceção ao decodificar string maior que
  `n`: limitar o texto **antes** do codec, não depois.
- Em 1.21.11 `mouseClicked` usa `MouseButtonEvent`; `mouseScrolled` continua
  com 4 doubles.
- Desenhar texto em `Screen.init()` é inútil (sem contexto de render):
  acumular e desenhar em `render()`.
- `case` de switch em código que já aplicou `toLowerCase` precisa estar em
  minúsculas (o bug do `characterName` veio exatamente disso).
- **Tela aberta por código precisa pedir os dados DEPOIS de ser exibida** —
  inclusive quando ela substitui outra tela do mesmo grupo. O padrão
  "setScreen + requestSheet" do menu não cobre navegação interna: foi
  exatamente assim que o Back do Skills deixava o Status em "Loading" eterno.
- **Texto desenhado à mão em GUI precisa de faixa reservada.** `height - 40`
  cai dentro da área de widgets (`contentBottom = height - 30`) e cobre a última
  linha em janela baixa. Reservar uma faixa (aqui y=24) para avisos.
- **`Math.max(1, x)` no denominador quebra valores legítimos de zero.** Checar o
  invariante do modelo (`manaMax` pode ser 0) antes de "evitar divisão por zero".
- **O vanilla não tem pose "deitado de costas".** `Entity$Pose` só tem
  STANDING, SLEEPING (exige cama), SWIMMING (bruços), CROUCHING, etc. "Deitado
  de verdade" exigiria renderer próprio.
- **Argumentos não-ASCII em comandos de shell (PowerShell) chegam corrompidos**
  neste ambiente. Passe só ASCII pelo shell; `edit`/`write` com UTF-8 funcionam
  normalmente.
- **Widget fora de `Screen.children()` não existe.** `EditBox` criado e guardado
  num mapa, sem `addWidget`/`addRenderableWidget`, não recebe clique, não
  recebe foco, não recebe teclado **e não é desenhado**. Guardar em mapa não
  registra. (`Screen.init()` limpa os widgets, então re-registrar a cada
  `init()`/resize é correto.)
- **No Fabric, a lista `server` de um mixin JSON não vale para o servidor
  integrado** (singleplayer / quem hospeda LAN). Ela só cobre o servidor físico
  dedicado. Lógica que deve rodar nos dois vai na lista comum `mixins`, com
  guard (`instanceof ServerPlayer`) para não afetar o cliente.
- **Aplicar pose no `END_SERVER_TICK` não vence o vanilla.** `Player
  .updatePlayerPose()` roda a cada tick e sobrescreve. Para manter um estado de
  pose estável é preciso cortar o método (`@Inject` no HEAD + `cancel`).
- **Barra em duas partes:** a parte "normal" usa `min(valor, max)` e a de
  "excedente" usa `max(0, valor - max)`, com denominador `max(valor, max)`.
  Usar `max` no trecho normal faz `5/10` aparecer cheio. E dois `Math.round`
  independentes podem somar 1px além da barra — limitar o excedente por
  `bar.w() - normalW`.
- **Em 1.21.11 a API de tooltip do vanilla mudou:** não existe mais
  `renderComponentTooltip`; agora é
  `GuiGraphics.renderTooltip(Font, List<ClientTooltipComponent>, int, int, ClientTooltipPositioner, Identifier)`,
  e `ClientTooltipPositioner` não tem constante pronta. Para texto simples,
  desenhar o painel com `font.split` é mais barato que depender disso.

## Armadilhas de codec de rede (1.21.11) — achadas na FASE 3d

- **`StringRepresentable.EnumCodec` NAO e um `StreamCodec`.** O nome engana: a
  classe pai `StringRepresentable$StringRepresentableCodec` implementa apenas
  `com.mojang.serialization.Codec` (JSON/DataFixerUpper), nunca
  `net.minecraft.network.codec.StreamCodec`. Confirmado por `javap`:
  `javap -classpath <minecraft-common.jar> 'net.minecraft.util.StringRepresentable$StringRepresentableCodec'`
  → so aparece `implements com.mojang.serialization.Codec<S>`.
  **Como usar um enum no protocolo de rede:** escrever o codec a mao com
  `ByteBufCodecs.stringUtf8(n).map(E::decode, E::toWire)`. Bônus: permite
  aceitar sinônimos e cair num valor seguro em vez de estourar a conexão.
- **Em 1.21.11 o metodo de transformacao chama-se `map(para, de)`, nao
  `xmap`.** `StreamCodec.map(Function<O, ? super V> to, Function<? super V, O> from)`
  e bidirecional. `xmap` dá `cannot find symbol`.
  Evidencia: `javap ... net.minecraft.network.codec.StreamCodec` → `apply`,
  `map`, `mapStream`; nao existe `xmap`.
- **`readVarInt`/`writeVarInt` sao do `FriendlyByteBuf`, nao do `ByteBuf` puro.**
  Num `StreamCodec` generico use `ByteBufCodecs.VAR_INT.decode(buffer)` /
  `.encode(buffer, n)`.
- **`StringRepresentable.fromEnum` exige `Supplier<E[]>`, nao `Supplier<List<E>>`.**
  Passar uma `List` dá `method fromEnum cannot be applied to given types`.
- **Inferencia de tipo generico costuma falhar em codec composto:** mesmo
  fixando `<Attribute>`, `EnumCodec<Attribute>` continuou sem casar com
  `StreamCodec<ByteBuf, Attribute>`. **Licao:** quando o tipo do codec nao casa,
  parar de ajustar a inferencia e confirmar com `javap` qual interface a classe
  realmente implementa -- 3 tentativas de so mexer em type witness nao
  resolveriam, porque a premissa (que `EnumCodec` fosse `StreamCodec`) estava errada.
- **`StreamCodec.composite` aceita `StreamCodec<? super B, T1>`,** entao um codec
  declarado como `StreamCodec<ByteBuf, T>` serve dentro de um
  `StreamCodec<FriendlyByteBuf, ...>` (`FriendlyByteBuf extends ByteBuf`).

## Ambiente: PowerShell 5.1 le .ps1 SEM BOM como ANSI (causa de 2 falhas)

- **Sintoma:** um `.ps1` que contem acentos (ou um here-string UTF-8) grava
  lixo no arquivo de destino. Nao ha erro: o script roda, o `String.Replace`
  simplesmente "nao encontra" o texto e o arquivo fica pela metade.
  Exemplo real: `"—"` virou `a<U+0302><U+0080><U+0094>`, `"Força"` virou `ForÃ§a`.
- **Causa:** o PowerShell 5.1 assume o codepage do sistema para arquivos .ps1
  sem BOM. O Write tool grava UTF-8 sem BOM.
- **Correcao 1 (preferida):** NAO colocar texto acentuado no .ps1. Guardar os
  pares de texto num arquivo de dados e ler com codificacao explicita:
  `[System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)`.
- **Correcao 2 (reparo):** o caminho inverso recupera texto ja corrompido:
  `[Text.Encoding]::UTF8.GetString([Text.Encoding]::GetEncoding(1252).GetBytes($texto))`
  Aplique **apenas no bloco afetado** — passar o documento inteiro por ali
  corrompe o que estava certo.
- **ARMADILHA de diagnostico:** o console do PowerShell tambem nao renderiza
  acentos, entao o `Write-Host` de um .ps1 mostra `For?a` e da a impressao
  FALSA de que o .docx/arquivo usa caractere errado. **Confie no codepoint, nao
  no console:** extraia para um arquivo UTF-8 e leia com a ferramenta de leitura.
  O travessão do documento era U+2014 de verdade, so que o console showed como
  `-`, e eu "corrigi" para hifen com base numa leitura visual errada.
- **Nao usar `-f` com varios argumentos sem parentese.** Em
  `"... $c : U+{0:X4} '{1}'" -f $code, $joined[$c]` a interpolacao acontece
  antes e sobra format invalido ("indice fora da lista de argumentos").
  Use `('{0:X4}' -f $code)` com parenteses, ou concatenacao.

## A ficha NAO e persistida (verificado 25/09/2026)

- `SessionManager.characterSheets` e um `static final Map<UUID, SheetData> = new HashMap<>()`,
  so em memoria. Nao existe NBT (`CompoundTag`, `saveAdditional`/`readAdditional`), nem
  escrita em arquivo, em nenhum lugar do mod.
- **Consequencia 1:** toda ficha se perde ao fechar o servidor. `getOrCreateSheet`
  chama `defaultSheet()` de novo na proxima sessao. Nao ha migracao a fazer porque
  nao ha dado antigo em disco.
- **Consequencia 2:** mudar o numero de campos de um record como `Skill` (2 -> 4) nao
  tem impacto de desserializacao. O unico codec involved e o de rede, e os dois lados
  rodam o mesmo build. Mudanca de formato so passa a importar quando existir
  persistencia — e nesse dia vai exigir versionamento.
- **Antes de sugerir "cuidado com fichas antigas", verifique se ha persistencia.**
  Eu quase registrei um risco de migracao inexistente so porque assumi que a ficha
  era salva. Custa um grep por `CompoundTag`/`java.io` tirar a duvida.

## DIRETIVA DO USUARIO (25/09/2026) - perguntar, nunca inventar

> "Qualquer duvida me pergunte, e se tiver duvida durante o desenvolvimento
> tambem pode me perguntar a qualquer momento. Nao invente, pergunte para o
> usuario a qualquer momento."

- Vale para ESTE projeto (mod TabletopRpg). Se o comportamento desejado nao for
  dedutivel do codigo ou de uma decisao anterior do usuario, PERGUNTE.
- Nao escolha um valor, um nome, um criterio ou um comportamento "razoavel" por
  conta propria quando a especificacao nao determinar. "Razoavel" nao e "confirmado".
- Bloco a entrega ate a resposta, em vez de assumir e seguir. Custa uma pergunta;
  custa mais refazer um comportamento que o usuario nao pediu.
- Evidencia continua valendo: se o codigo ou o proprio usuario ja responderam a
  pergunta, nao pergunte de novo por formalidade.
