# 2026-10-01 — Campo CA na ficha, ao lado do Level

## Objetivo
O usuario pediu um campo **CA (Classe de Armadura)** na ficha do personagem, **na mesma linha do Level**,
com caixa numerica propria. Decisoes confirmadas com ele antes de codar: rotulo configuravel no Sheet
Editor, piso 0 e teto 9999.

## Escopo
`Progress` (SheetData) + `SheetModel` + codec de NBT + layout da ficha + Sheet Editor + traducao +
catalogo + 2 testes. **Nenhum** comando, tecla, payload novo ou mudanca de gameplay.

## Alteracoes
- `SheetData.java`: `Progress` ganhou o campo `ca` (stream codec, NBT `optionalFieldOf("ca", 0)`,
  clamp 0..`MAX_RESOURCE`); `withField` e `getNumeric` ganharam o caso `"ca"`; `LABELLED_FIELDS` ganhou
  `"ca"`; `defaults()` com CA 0. Construtor de 3 campos mantido como sobrecarga.
- `SheetModel.java`: novo componente `caLabel` (padrao "CA"), `labelOf`, `withLabel`, `defaults`, codec de
  rede (encoder + decoder) e **codec de NBT reescrito a mao** (ver abaixo).
- `CharacterSheetScreen.java`: novo `addFieldPair` + `PAIR_GAP` + `boxXFor`.
- `StatusScreen.java`: a linha do Level virou `addFieldPair("level", "ca", ...)`.
- `SheetEditorScreen.java`: campo de rotulo do CA (`field_ca`).
- `en_us.json`: `screen.tabletoprpg.sheet_editor.field_ca`.
- `FUNCIONALIDADES-E-COMANDOS.md`: secao 5 (regras) e a linha da `StatusScreen`/`SheetEditorScreen`.

## Decisao de arquitetura (perguntada ao usuario)
`RecordCodecBuilder.group()` aceita **no maximo 16 campos** e o `SheetModel` tem 17 com o `caLabel`. O
compilador recusa; nao ha contorno mantendo o builder. O usuario escolheu **"codec manual, chaves iguais"**:
reescrever o `CODEC` como `Codec.of(Encoder, Decoder)` **sem mudar o formato gravado** (as chaves
continuam as mesmas e planas). A alternativa rejeitada foi agrupar os 9 rotulos num sub-record, que exigiria
migracao do NBT ja salvo.

## Problemas encontrados e causa raiz
1. **Codec**: limite de 16 do builder (detalhado acima).
2. **APIs do DFU 9.0.19**: quatro erros de compilacao seguidos, todos resolvidos **conferindo o jar com
   `javap`**, nao adivinhando — `Codec.parse(Object)` nao existe; `MapEncoder`/`MapDecoder` nao aceitam
   lambda (2 metodos abstratos); lambda nao pode declarar `<T>` (da `class T`); `RecordBuilder.add` e
   `(chave, valor, encoder)`; `MapLike.get` devolve `T`, nao `DataResult<T>`.
3. **Erro meu, corrigido**: escrevi uma primeira versao do decoder com uma cadeia de 17 `flatMap` e um
   helper incoerente. Reescrito para ler cada campo uma vez e construir o modelo uma vez.
4. **Erro meu, corrigido**: inseri `addFieldPair` no lugar errado em `StatusScreen`, duplicando o campo
   `level` e quebrando um comentario. Revertido e reaplicado.
5. **BUG REAL, corrigido**: o construtor de convenience de 3 campos do `Progress` (que deixa `ca = 0`) fez
   os ramos de `withField` de `level`/`xp`/`xptext` **apagarem o CA**. Como Level e CA estao na mesma
   linha, era a uma tecla de distancia. Corrigido repassando `progress.ca()` em cada ramo.

## Validacoes executadas
- `.\gradlew.bat build --no-daemon --console=plain` → **BUILD SUCCESSFUL**.
- Testes novos, ambos **PASSED**: `caLabelSurvivesNbtAndNetworkAndLegacyNbtFallsBack` e
  `editingLevelOrXpKeepsCa`.
- Suíte existente de `SheetModelCodecTest` + `SheetData*Test`: passa, incluindo
  `Limites de valor sobrevivem a rede e o NBT antigo abre no padrao`, que comprova que o NBT antigo ainda
  abre depois da reescrita do codec.
- Detector de catalogo rodado: os 3 itens que ele acusa (`/rpg/roll/Pericia/0-2`, `SkillFormScreen`,
  `SpellFormScreen`) sao **divergencias preexistentes**, nao deste trabalho (conferido por leitura).

## Limitacoes / NAO validado
- **Nao validado em jogo.** Nao rodei o cliente. Build e teste nao cobrem layout: se as duas caixas da
  linha(Level|CA) ficarem apertadas ou desalinhadas num rotulo longo, so aparece na tela. O usuario
  precisa abrir a ficha e conferir.
- O CA **nao tem toggle** no Sheet Editor (decisao minha, por nao ter sido pedido): uma ficha sem CA
  continua valida. Se o Mestre quiser esconder a linha, e preciso decidir o que o campo assume.
- Nao ha migracao porque nao houve mudança de formato — mas isso **nao** foi conferido contra um mundo
  real com Sheet Model salvo; so contra `CompoundTag` vazio e round-trip em teste.

## Proximos passos
1. **Usuario testar em jogo**: a linha Level|CA, editar o CA, conferir se editar Level/XP preserva o CA.
2. Se Master quiser o CA escondido ou um teto diferente, sao mudancas pequenas.
3. Correr o detector de catalogo periodicamente — as 3 divergencias preexistentes continuam la.