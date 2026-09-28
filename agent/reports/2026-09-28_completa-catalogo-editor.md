# Completa o catalogo com as regras da rodada 2 do editor

**Data:** 28/09/2026
**Commit:** `c663e5b` (branch `main`, sem push)
**Escopo:** `FUNCIONALIDADES-E-COMANDOS.md` e nada mais. Zero `.java` tocado.

## Objetivo

Fechar a pendencia que ficou depois do commit `988335c`: o catalogo funcional nao
registrava as regras de comportamento introduzidas na rodada 2 de correcoes do
Sheet Editor. O catalogo e o que o usuario consulta para saber o que o mod faz
sem abrir o codigo, entao uma regra implementada e nao documentada e uma regra que
o usuario nao conhece.

## Surpresa: a tarefa "cancelada" ja tinha escrito o catalogo

A tarefa anterior de sincronizacao do catalogo foi marcada como cancelada, e o
relatorio do checkpoint registrou "catalogo nao registra as regras da rodada 2"
como pendencia. **Isso estava errado.** O subagente ja tinha escrito a maior
parte do texto antes de a tarefa ser interrompida, e essas edicoes acabaram
versionadas em `988335c` junto com o resto. O cancelamento interrompeu a
execucao, nao o que ja estava no disco.

Consequencia pratica: das 7 mudancas originally pedidas, **5 ja estavam no
arquivo** e conferiam com o codigo. O que faltava de verdade eram 4 correcoes
menores, mais 2 afirmacoes que o proprio agente Delegado levantou e recusou
corrigir por estar fora da lista.

**Licao:** antes de registrar uma tarefa como pendente, conferir se a
interrupcao aconteceu antes ou depois do efeito. Um "cancelado" nao e um
"nao aconteceu".

## O que mudou

Nove pontos, todos confirmados no codigo antes de virar texto:

1. Aviso de "como ler as referencias": dizia `Arquivo.java:linha` com "a linha
   exata". Era falso e contrariava o aviso do topo do proprio documento. Passou
   a `Arquivo.java: simbolo`. Tres cabecalhos de tabela ajustados junto.
2. Filtro de teto do HP/Mana documentado de forma enganosa (ver abaixo).
3. Motivo do wrap: a largura do rotulo era limitada a `leftW / 3`
   (`StatusScreen.java: fieldLabelWidth`).
4. Limiar do wrap corrigido: so fecha com `rowH` 17 ou mais; a linha cai para
   `rowH` 12 ou 13 com as 18 pericias do padrao, nao 13 fixo.
5. `Discard` habilitado com nome pendente (`SheetEditorScreen.java: canDiscard`).
6. Janela de scroll por altura real do widget + titulo antes do `super.render()`
   (`SheetEditorScreen.java: fitsInPanel`).
7. `Reset` faz o contrario do que o catalogo dizia: preserva rascunho ao fechar.
8. A saida por `ESC` nao era a unica: tambem pelo botao `Close` e abrindo outra
   tela, via `Screen.removed()`.
9. `Reset` nao apaga de forma duravel as marcas de nome pendente.

## Problema encontrado: o filtro de 9999 nunca avisa

O catalogo dizia que a caixa "recusa o digito antes", o que se le como se
houvesse retorno visivel. Nao ha.

Verificado por `javap -c` no jar nomeado do 1.21.11: `EditBox.insertText` monta o
texto novo, chama `filter.test(...)` e, se voltar falso, retorna no offset 88,
**antes** do `putfield value`. Sem `onValueChange`, sem som, sem dica, sem
desenho. A tecla e engolida em silencio.

Correcao de raciocinio que vale registrar: eu repassei ao subagente que
"apagar funciona porque o caminho de apagar nao aplica filtro". **Isso estava
errado.** `deleteCharsToPos` aplica o mesmo `filter.test` com o mesmo retorno
cedo. O que faz apagar funcionar e o *criterio* do teto: `withinCeiling` so
recusa valor acima do teto, e o numero que sobra ao apagar e sempre menor. O
comportamento observavel era o mesmo, a causa estava errada, e um documento que
explica a causa errada ensina a coisa errada a quem for manter.

O texto final descreve o comportamento, sem afirmar o mecanismo.

## Validacoes

| Verificacao | Resultado |
|---|---|
| `gradlew build --no-daemon` | `BUILD SUCCESSFUL in 9s` |
| `scanEncoding` | `OK: 88 arquivo(s)`, 0 mojibake, 0 ideograma, 0 U+FFFD |
| `check-catalogo.ps1` | `OK: nenhuma divergencia mecanica` (23 literais, 17 executaveis, 19 citados, 2 teclas, 9 telas) |
| `git status` | limpo apos o commit |

O detector so confere o que regex alcanca. Permissao de comando, descricao de
comando e regra de sistema continuam sendo juizo de leitura, e o proprio script
imprime essa ressalva.

**NAO validado em jogo.** Nao ha risco de runtime novo, porque nada de `.java`
mudou. Tambem nao ha evidencia de que o texto ficou bom para o usuario ler.

## Limitacoes e pendencias

- **"Data de referencia" do cabecalho** aponta para a FASE 3 de 27/09/2026, nao
  para esta rodada. As datas estao espalhadas pelo documento e por comentarios do
  codigo. Corrigir so o cabecalho criaria inconsistencia nova; corrigir tudo e
  acima do escopo desta tarefa. Conversa pendente com o usuario.
- **Nenhum `.java` validado em runtime** desde `988335c`. As 13 correcoes
  continuam aguardando teste do usuario.
- **id estavel em `PericiaDef`** continua aprovado e nao executado, aguardando o
  mesmo teste em jogo. Sem ele, salvar um rename novo ainda zera valor e perde o
  atributo escolhido.
- O detector mora em `%USERPROFILE%\.config\opencode\skills\agente-tcc\catalogo-sync\`,
  **nao** dentro do repositorio. Dois subagentes seguidos falharam em acha-lo
  procurando no projeto. Vale a pena registrar o caminho na skill.

## Aprendizado

Subagente delegando trabalho de documentacao deve ser obrigado a **dizer o que
ele nao fez**. Aqui a omissao custou dois ciclos: o subagente omitiu de novo
varios simbolos que o briefing pedia e assim o commit ficou incompleto sem que
ninguem percebesse. Escrever "deixei de fora X, Y, Z" e obrigatorio.
