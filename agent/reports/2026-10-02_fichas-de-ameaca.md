# Fichas de ameaça — lista, editor, persistência e item (02/10/2026)

## Objetivo

O Mestre passa a poder criar, editar e apagar **fichas de ameaça** dentro do mod, com
persistência em NBT do próprio Mestre, item próprio do mod a cada salvamento, editor em
duas abas e duas colunas, e textos novos em português.

## Escopo entregue

- Lista de fichas no menu do Mestre, botão **"Fichas de Ameaça"**.
- Editor com 2 abas (**Ficha** / **Habilidades**) e 2 colunas em cada.
- Perícias em aba própria, com editor de linhas e paleta de escolha.
- Formulário pequeno reutilizável para característica / passiva / ativa.
- Persistência por mixin no NBT do jogador.
- 5 payloads novo e 2 receptores de cliente.
- Item `threat_sheet` do mod, com placeholder de mapa branco.
- Português literal em tudo que é novo; os botões antigos continuam em inglês.

**Fora do escopo, por decisão:** uso do item pelo Mestre (spawn no mundo), nametag em
cima da ameaça, comando novo, gamerew, `FUNCIONALIDADES-E-COMANDOS.md`.

## Requisitos x veredito (revisão independente, 02/10/2026)

| # | Requisito | Veredito |
|---|---|---|
| 1 | Botão só do Mestre; só ele lista, salva e apaga | OK |
| 2 | Persistência no NBT do Mestre, sobrevive a logout | OK por código, **não testado em jogo** |
| 3 | HP igual ao bloco de 3 linhas do jogador | OK |
| 4 | Item do mod; cada save entrega outro e mantém o anterior | OK |
| 5 | Sem Iniciativa/Percepção no topo, só na lista de perícias | OK |
| 6 | Características e habilidades: criar, apagar, editar | OK |
| 7 | Atributos do modelo, −999..999, 4 caracteres | OK |
| 8 | Aba 1 e aba 2 com 2 colunas na ordem pedida | OK depois da correção de layout |
| 9 | Textos novos em português | OK |

## Arquivos

Criados:
- `src/main/java/com/pedro/tabletoprpg/ThreatSheet.java`
- `src/main/java/com/pedro/tabletoprpg/ThreatSheetStore.java`
- `src/main/java/com/pedro/tabletoprpg/mixin/PlayerThreatSheetPersistenceMixin.java`
- `src/client/java/com/pedro/tabletoprpg/client/ThreatSheetClientState.java`
- `src/client/java/com/pedro/tabletoprpg/client/ThreatSheetsScreen.java`
- `src/client/java/com/pedro/tabletoprpg/client/ThreatSheetScreen.java`
- `src/client/java/com/pedro/tabletoprpg/client/ThreatPericiasScreen.java`
- `src/client/java/com/pedro/tabletoprpg/client/ThreatEntryFormScreen.java`
- `src/main/resources/assets/tabletop-rpg/items/threat_sheet.json`
- `src/main/resources/assets/tabletop-rpg/models/item/threat_sheet.json`
- `src/main/resources/assets/tabletop-rpg/textures/item/threat_sheet.png`

Alterados: `RpgNetworking.java`, `ModItems.java`, `RpgMenuScreen.java`,
`TabletopRpgClient.java`, `tabletop-rpg.mixins.json`, `lang/en_us.json`.

## Validações executadas

- `.\gradlew.bat compileJava --no-daemon --console=plain` → BUILD SUCCESSFUL.
- `.\gradlew.bat compileClientJava --no-daemon --console=plain` → BUILD SUCCESSFUL,
  repetido depois de cada correção.
- `.\gradlew.bat build --no-daemon --console=plain` → BUILD SUCCESSFUL (14s).
- Revisão independente do diff por subagente validador.

## Problemas encontrados, causa raiz e solução

1. **Codec de `Action` não compilava.** `StreamCodec.composite` deu "no suitable method
   found" numa chamada de 3 pares aninhada. **Causa raiz** não investigada; **solução**:
   codec escrito à mão. Mesmo para `Identity` e `Vitals`.
2. **`MAX_ACTION_DESC` não existia.** Constante faltando; adicionada.
3. **Renomeação nunca era detectada.** Em `ThreatSheetStore.save`, a chave antiga era
   lida **depois** de `current.set`, então comparava a ficha nova com ela mesma e
   `SaveResult.RENAMED` era inalcançável. **Solução**: ler `previousKey` antes da troca.
4. **Segundo "Salvar" sempre falhava.** `originalName` era `final` e nunca acompanhava o
   que o servidor gravou: o 2º save de uma ficha nova voltava `NAME_TAKEN` e o de uma
   renomeada voltava `NOT_FOUND`. **Causa raiz: erro de spec minha**, que mandou não
   atualizar o campo. **Solução**: `originalName` mutável + `pendingSaveName` com o nome
   **exatamente enviado**, aplicado só quando o save volta `ok`.
5. **Características não tinham edição.** `editFeature(int)` existia sem chamador;
   o requisito era criar, apagar **e editar**. **Solução**: botão "Editar" na linha.
6. **Texto de botão vazava do painel.** `Button` desenha com `drawCenteredString`, sem
   clip; nome de 96 caracteres invadia coluna vizinha e barra. **Solução**:
   `font.plainSubstrByWidth` nos rótulos de característica, passiva e ativa.
7. **Atributo aceitava "9999" e era cortado em silêncio.** As perícias validavam e
   avisavam, os atributos não. **Solução**: validação no save com mensagem no rodapé.
8. **"Del?" cancelava sozinho na rolagem.** O índice absoluto era certo, mas o rótulo
   "Del?" não era reaplicado ao recriar a lista. **Solução**: reaplicar quando a linha
   está visível; zerar quando sai de vista.
9. **Estouro de painel que impedia editar atributos — gravidade ALTA.** A coluna
   esquerda tinha altura fixa de 246 px, mas em 1080p com GUI scale automático a tela
   lógica é **270** e em 720p é **240**: a lista de atributos nascia **fora da tela** e o
   Mestre não editava atributo nenhum, com build verde e sem erro. **Solução**: coluna
   esquerda inteira rolável, com altura derivada do espaço real e rodapé fixo; bloco
   que não cabe inteiro não é criado. Conferido por aritmética em 240, 270 e 360 lógicos.

## Limitações conhecidas

- Nada foi testado em jogo. `build` não valida injeção de mixin.
- `ThreatSheetClientState` não é limpo na desconexão: a primeira abertura da lista numa
  sessão nova pode piscar as fichas da sessão anterior até o servidor responder.
- `ThreatSheetListPayload` e `ThreatSheetResultPayload` leem coleções sem teto no
  servidor→cliente; os caminhos cliente→servidor têm teto. Com 200 fichas de tamanho
  máximo o pacote poderia passar do limite do canal. Não corrigido.
- `alignedTo` descarta em silêncio valor de atributo ou perícia que sumiu do modelo.
- A barra de rolagem encosta em ~3 px do botão "Del" em listas com 6+ itens.
- `en_us.json` tem agora uma entrada em português, por decisão do requisito 9.
- Validação de atributo é só no cliente; o record continua cortando em silêncio se
  outro cliente enviar valor fora de faixa.

## Aprendizados

Gravados em `agent/memory/project-memory.md` no mesmo dia: `StreamCodec.composite`
acima de 2 campos, pacote real do `MouseButtonEvent`, `Checkbox`/`Tooltip` por builder,
**GUI scale automático torna 240/270 px lógicos o caso comum e coluna de altura fixa
quebra**, `UP-TO-DATE` não prova compilação, e `SheetModelHolder.current()` no servidor.

## Próximos passos

1. **Teste do Mestre em jogo**: mixin, persistência em logout/relog, uso do item,
   entrega com inventário cheio, layout em 1080p e 720p, scroll das listas.
2. **Uso do item pelo Mestre** — próxima etapa pedida, ainda não implementada.
3. `FUNCIONALIDADES-E-COMANDOS.md` quando houver uso de item implementada.

## Observação de escopo

`HANDOFF.md` **não** foi alterado: a regra de escrita da pasta `agent/` o declara
intocável. O estado de entrega que iria para lá está nesta seção e na memória.
