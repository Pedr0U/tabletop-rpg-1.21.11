# Fichas de Ameaça — atributos sem caixa e contador do vanilla (02/10/2026)

## Objetivo
Dois defeitos que sobreviveram a varias rodadas de correcao por deducao: a caixa de
inserir valor dos atributos que nao aparecia, e o contador de caracteres que nao saia da
tela de criacao de habilidade e de caracteristica.

## Escopo
- `src/client/java/com/pedro/tabletoprpg/client/ThreatSheetScreen.java`
- `src/client/java/com/pedro/tabletoprpg/client/ThreatEntryFormScreen.java`

Nenhum arquivo de `src/main`. Nenhuma classe nova.

## 1. Atributos: rotulo aparecia, caixa nao

**Causa raiz: dois caminhos de desenho com guardas diferentes.** O mesmo dado era
desenhado por dois lugares, e so um deles checava se o bloco cabia na coluna.

- O **rotulo** ("STR Strength") e desenhado no `render`, dentro de um laco que so
  limita pelo `lastIndex()` da regiao. Nao ha nenhuma verificacao de espaco vertical.
- A **caixa de valor** so nasce em `rebuildRegion`, e `rebuildRegion` era chamado sob a
  guarda `if (inColumn(y, ATTR_HEAD_H + attrH))`, que exige que a lista **inteira** coubesse.

Com a lista de atributos maior que o espaco restante, a guarda falhava, nenhuma caixa
nascia e a coluna de valores ficava vazia, enquanto os nomes apareciam normalmente.

**Correcao:** a guarda passou a ser "cabe pelo menos uma linha visivel", e o `attrRegion`
recebe a altura que realmente sobra (`contentBottom - attrY`), o que zera o numero de
linhas desenhadas quando nao ha espaco. Rotulo e caixa passam a nascer e morrer juntos.

**Ajuste de espaco:** a descricao agora cede lugar para **tres** linhas de atributo
(`min(66, leftover - 3 * ROW_H)`) em vez de uma. A caixa da descricao guarda 256
caracteres, entao 66 px ja e folgado; antes ela tomava metade de um painel alto e
empurrava a lista para baixo.

## 2. Contador de caracteres: era do vanilla

**Por que duas rodadas de "voce nao removeu" com o codigo ja limpo.** O contador que
aparecia embaixo da caixa de descricao, no canto inferior direito, **nao era desenhado por
este projeto**. A classe `net.minecraft.client.gui.components.MultiLineEditBox` do
Minecraft 1.21.11 tem os campos `count` e `limit` e desenha o proprio "usado/maximo"
sempre que `setCharacterLimit` e chamado. Remover o contador nosso nao alterava nada,
porque havia um segundo contador, do vanilla, exatamente na posicao descrita.

Como isso foi confirmado: `run/mods` vazio (sem jar antigo carregando por cima),
`tabletop-rpg.mixins.json` sem mixin de `EditBox`, `build.gradle` sem dependencia de texto,
imports do formulario todos vanilla. A classe vanilla foi localizada dentro de
`minecraft-clientonly-1.21.11-loom...jar` e o seu `.class` contem as strings `count` e
`limit`.

**Correcao:** `ThreatEntryFormScreen` nao chama mais `setCharacterLimit`. O limite
continua valendo e passou a ser decidido no `save`, com mensagem em vez de corte
silencioso: "Descricao: maximo de 256 caracteres (voce escreveu 300)".

**Erro meu, registrado:** afirmei duas vezes que o contador tinha sido removido, com
bytecode conferido, sem considerar que o numero na tela podia vir de fora do projeto. A
pergunta correta, diante de "ainda aparece", nao e "meu codigo esta limpo" e sim "outra
coisa esta desenhando isto".

## Pendencia conocida
A caixa de descricao da **ficha principal** (`ThreatSheetScreen`, `setCharacterLimit` em
`MAX_DESCRIPTION`) tambem mostra o contador do vanilla. O Mestre nao reclamou dela e ela
nao foi mexida.

## Validacao
- `.\gradlew.bat compileClientJava --no-daemon --console=plain` → **BUILD SUCCESSFUL em 9s**.
- Bytecode de `ThreatEntryFormScreen.class` conferido: `setCharacterLimit` ausente.
- `compileJava` nao foi reexecutado: `src/main` nao foi tocado.
- **Validado pelo Mestre em jogo: o contador saiu ("Perfeito, agora deu certo").**
- A caixa de valor dos atributos foi corrigida na mesma rodada e **nao foi confirmada em
  jogo** — a confirmacao do Mestre chegou para o contador.

## Proximos passos
- Item `threat_sheet`: uso com botao direito para abrir a ficha, e vinculacao do item a
  um mob, com nome de exibicao acima dele e uso em iniciativa.
- Confirmar em jogo a caixa de valor dos atributos.
- `FUNCIONALIDADES-E-COMANDOS.md` desatualizado e `HANDOFF.md` intocado, por decisao do
  Mestre.