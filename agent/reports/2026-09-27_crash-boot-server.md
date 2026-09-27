# Implementation Report

## Status
BUILD VERDE. Correcao aplicada. **Validacao de runtime PENDENTE** — o usuario
ainda nao entrou no mundo com este JAR.

## Objective
Diagnosticar o crash do servidor integrado ao entrar no mundo, identificar a causa
com evidencia antes de editar, e aplicar a correcao minima.

## What Changed
Uma linha de codigo, mais documentacao:

`src/main/java/com/pedro/tabletoprpg/SheetModelStore.java`, metodo `get(MinecraftServer)`:

```java
- .getDataStorage().get(TYPE)
+ .getDataStorage().computeIfAbsent(TYPE)
```

O Javadoc da classe foi ampliado para documentar a distincao entre `get` e
`computeIfAbsent` e o motivo pelo qual ela causou um crash silencioso para o
compilador. Nenhuma outra mudanca de codigo foi feita neste ciclo.

## Root Cause (FACT)
O crash report `crash-2026-09-27_20.17.26-server.txt` aponta:

```
NullPointerException: Cannot invoke "SheetModelStore.model()" because "store" is null
  at SheetModelStore.get(SheetModelStore.java:95)
  at SheetModelStore.lambda$register$0(SheetModelStore.java:113)   // SERVER_STARTED
```

`SheetModelStore.get` usava `DimensionDataStorage.get(SavedDataType)`, que e
**apenas busca** e devolve `null` quando o dado nunca foi gravado. A linha
seguinte desreferenciava o resultado.

Inspecao da classe real com `javap` no jar do Loom
(`minecraft-common-...-1.21.11-loom.mappings...jar`) confirmou as duas sobrecargas:

```java
public <T extends SavedData> T computeIfAbsent(SavedDataType<T>);  // cria
public <T extends SavedData> T get(SavedDataType<T>);              // so busca
```

Confirmacao independente: nenhum dos 5 mundos do projeto
(`%APPDATA%\.minecraft\saves` e `run\saves`) possuia um arquivo `tabletop*.dat`
em `data/`. O store nunca existiu, portanto `get()` sempre devolvia `null` e o
servidor caia em todo primeiro boot de um mundo novo. O bug era deterministico,
nao intermitente.

O desenho original ja indicava a chamada correta: `TYPE` carrega a factory
`SheetModelStore::new` dentro do proprio `SavedDataType`, e o construtor sem
argumento existe documentado como "o que o vanilla pede para criar um store
vazio". O que faltava era a chamada `computeIfAbsent`.

## Validation
- `./gradlew build` — BUILD SUCCESSFUL, 8/8 testes JUnit5 passando.
- Encoding do arquivo alterado verificado por codepoint: sem acentos latin1, sem
  CJK/fullwidth, sem caracteres de controle.
- Artefato copiado para `%APPDATA%\.minecraft\mods\tabletop-rpg-1.0.0.jar`;
  SHA-256 conferido com o de `build/libs/`.
- **Nao validado:** o jogo nao foi executado. Build verde prova compilacao, nao
  comportamento de runtime.

## Problems Encountered
Nenhum erro de build durante a correcao. O problema real foi de diagnostico:
por varios ciclos a analise apontava para o codec `STREAM_CODEC` e para o
lifecycle de rede, porque o stack trace era lido como falha de serializacao.
A pista decisiva foi a mensagem do proprio NPE: o variavel `store` era **null**,
o que aponta para a camada de obtencao do dado, nao para o codec.

## Lessons / Memory
1. **`null` e um retorno legal.** `get()` compilava sem reclamar. Compilacao
   verde nunca foi evidencia de que o dado existia.
2. **Verificar assinatura nao e verificar semantica.** O Javadoc da classe
   afirmava "API verificada com javap" e estava factualmente correto: `get`
   recebe um `SavedDataType`, nao uma String. A verificacao parou no tipo do
   parametro e nao notou que a classe tem duas sobrecargas com comportamentos
   opostos. Documentacao que registra *que* uma API foi checada, sem registrar
   *qual comportamento ela garante*, produz falsa confianca.
3. **Mensagem de excecao beats hypothesis.** O NPE dizia exatamente qual variavel
   era nula. A hypothesis anterior (codec assimetrico) era plausivel e nao
   explicava um null no objeto obtido do storage.
4. **Nunca declarar pronto com risco aberto conhecido.** A regra se pagou: o
   usuario encontrou o crash que o build nao podia mostrar.

## Remaining Issues
Nao Related to this fix, ainda em aberto:
- 3 alteracoes compiladas mas nunca executadas no jogo: `case "xptext"` em
  `SheetData.withField`, `SheetModel.nextFreshAttributeIndex()` em
  `addAttribute`, e `SheetData.periciaByNameOrLegacy` usado em `SheetModel.align`.
- `StatusScreen` nao remonta quando a quantidade de pericias muda.
- `AttributePickerScreen` nao participa do despacho `onModelChanged`.
- Deduplicacao de IDs de atributo e sensivel a caixa.
- Renomear uma pericia perde o valor dela (pericias sao identificadas por nome).
  Requer decisao do usuario, nao deve ser alterado sem ela.

## Next Steps
1. Usuario entra no mundo com o JAR atual e reporta o primeiro sintoma, se houver.
2. Se entrar sem crash, confirmar a criacao de
   `saves\<mundo>\data\tabletop_rpg_sheet_model.dat`.
3. Depois, exercitar o Sheet Editor em runtime, um passo por vez, para
   validar o receptor S2C e o `onModelChanged` — que nunca rodaram.
