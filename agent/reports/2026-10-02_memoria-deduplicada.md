# Memoria do projeto: deduplicada, e 17 palavras reparadas

## Objetivo

A jogadora confirmou que o jar esta rodando bem e mandou arrumar o que estivesse
repetido em `agent/memory/project-memory.md`. O arquivo da fase anterior estava com
conteudo em triplicado.

## O que era

**FATO:** 8408 linhas, 167 titulos `##` e 334 `###`. O arquivo era tres copias do
mesmo conteudo:

| Copia | Linhas | Secoes `##` |
| --- | --- | --- |
| A | 3-2770 | 55 |
| B | 2771-5501 | 55 |
| C | 5502-8408 | 57 |

A copia C e a mais nova: as licoes de 02/10/2026 estao nela (linhas 8243 em diante), e
as duas ultimas fases que escrevi -- o bug do `dl` e o scanner de operadores -- estao
nas ultimas linhas dela.

**FATO:** os commits `26bdfdf` (+2659/-2611) e `1bb88ad` (+8087/-2586) reescreveram o
arquivo inteiro. Nao era separador de linha: base, HEAD e origin/main estao todos em
CRLF puro.

## Por que nao era so "jogar fora duas copias"

A copia C **nao** era superset da A. Um unico titulo divergia --
`Presets de rolagem (02/10/2026)` -- e nele a C tinha **menos**:

| Secao | A | B | C |
| --- | --- | --- | --- |
| linhas | 157 | 120 | 184 |
| subtitulos `###` | 16 | 13 | 20 |

Tres licoes que a A tinha **sumiam** se eu simplesmente apagasse A e B. Verificado por
busca em todo o arquivo: so apareciam nas linhas 2733 e 2762, nunca na copia C.

1. `Brigadier 1.3.10: StringArgumentType.string() NAO e mais guloso`
2. `Screen: fill opaco DEPOIS de super.render esconde widget sem tirar o clique`
3. `O console do PowerShell mostra ? onde o arquivo tem acento`

**Decisao:** manter a copia C inteira e resgatar essas tres licoes da copia A, em vez de
escolher uma copia e aceitar a perda.

## Como ficou

```
preambulo (2 linhas)
+ copia C inteira (5502-8296)
+ as 3 licoes resgatadas da copia A (2733-2770), no fim da secao Presets
+ resto da copia C (8297-8408)
```

8408 -> **2948** linhas. `git diff --stat`: 53 insercoes, 5513 remocoes.

## Defeito novo: 17 palavras comidas -- e a origem e o transporte do shell

Enquanto inspecionava, achei caracteres de controle no arquivo. **FATO:** 17 ocorrencias
de escape de string interpretadas como BEL, BS, VT e FF, com a primeira letra da
palavra seguinte faltando:

| Escape | Virava | Palavra comida | Onde |
| --- | --- | --- | --- |
| barra + `a` | U+0007 | `active`, `arrowsRight`, `arrowsLeft` | 7 |
| barra + `b` | U+0008 | `brigadier`, `boolean` | 3 |
| barra + `v` | U+000B | `visible`, `vao` | 4 |
| barra + `f` | U+000C | `fill`, `font` | 3 |

A regra `controle -> letra` vale para as 17, verificadas uma a uma. O titulo
`Screen: ill opaco` era na verdade `Screen: fill opaco`: o `f` tinha virado U+000C.

**Causa raiz, e nao e o que parece:** nao e PowerShell nem here-string. **E o transporte
do comando `shell`**, que interpreta barra seguida de letra como escape antes de a
string chegar ao PowerShell.

**FATO:** as 17 ocorrencias estavam sempre dentro de crase, escrevendo um identificador --
a crase oferece barra + `fill`, e o transporte come o `f`. E o mesmo transporte nao mexe
em texto escrito pela ferramenta de escrita: o bloco substituto passou limpo pelos dois
caminhos, e o mesmo vale para o bloco da memoria.

**Reproduzido ao vivo, na mesma fase:** a licao que escrevi sobre esse bug recriou o bug
no mesmo commit, porque o texto dela falava em `fill`, `arrowsRight` e `vao`. Foi o
`verify` de caractere de controle que pegou. Um bug assim se propaga pelo proprio
relatorio dele.

**`scanEncoding` nao pegou nenhum deles.** A task caça mojibake, ideograma e U+FFFD --
falha de TRANSCODIFICACAO, nao de ESCAPE de string. U+0007 nao e nenhum dos tres. Um
arquivo pode passar o build inteiro com palavras corrompidas.

## Como provei que nada se perdeu

Diff de conjunto de linhas: normalizei o arquivo antigo e o novo (controle vira `?` dos
dois lados, para a comparacao nao depender do escape) e comparei linha de conteudo
**distinta**, nao por contagem.

```
antes : 2466 linhas de conteudo distintas
depois: 2465
perdidas: 19   ganhas: 18
```

Todas as 19 perdidas tem uma linha correspondente entre as 18 ganhas, e cada uma e uma
edicao que eu fiz de proposito:

- 15 reparos de caractere de controle;
- 1 titulo que estava partido em duas linhas, com `##` nas duas
  (`UseBlockCallback ... PARA no primeiro resultado` / `## diferente de PASS ...`),
  unido num so;
- 2 titulos `### HIPOTESE - 28/09/2026, a confirmar em jogo` que colidiam no nome com
  conteudos diferentes, desambiguados como `(rajada de valores na coluna)` e
  `(migracao de ficha)`;
- 1 linha de lixo: `=[System.IO.File]::ReadAllText(...)`. Um comando de PowerShell que
  entrou no arquivo, e que so existia na **copia B** (linha 5500, a ultima dela). A
  deduplicacao levou junto.

Nada mais. Nenhuma licao, nenhum FATO e nenhuma HIPOTESE sumiram.

## Erros que eu cometi nesta fase

**A guarda do script me confundiu duas vezes, e as duas vezes por culpa do script, nao do
arquivo.**

1. `-notmatch '^## 01/10/2026 - Aba 3'` reprovou num titulo que existia. O titulo tem
   em dash (U+2014), e a guarda exigia hifen. Guardas de texto que casam titulo
   inteiro sao frageis: um travessao muda e o script acusa corrupcao onde nao ha.
2. `if ($txt -match 'ill opaco')` acusou "U+000C nao reparado" num arquivo **sem
   nenhum** U+000C -- porque `'fill opaco' -like '*ill opaco*'` e verdadeiro. Um teste
   por substring que e sufixo de outro termo-proprio acusa erro onde o texto esta certo.

**A primeira comparacao de perda deu 328 linhas perdidas.** Nenhuma era real: `git show`
escreve UTF-8 e o PowerShell decodificava pelo codigo de pagina do console, trocando
todo acento por `?`. As duas pontas da comparacao estavam em encodings diferentes. A
conta certaina so depois de `[Console]::OutputEncoding = UTF8`, e caiu para 19 -- que sao
as 19 intencionais. **Comparacao de texto que cruza fronteira de processo precisa de
encoding explicito**, ou ela mede o console e nao o arquivo.

## Validacao

| O que | Resultado |
| --- | --- |
| Titulos `##` repetidos | 0 |
| Subtitulos `###` repetidos | 0 |
| Caracteres de controle | 0 |
| Titulos `##` preservados | 56 de 57 (o unico "perdido" e o titulo partido, que virou 1 linha) |
| Secoes sem linha em branco antes | 0 de 56 |
| Build | `BUILD SUCCESSFUL`, `scanEncoding` OK (149 arquivos, 0 mojibake, 0 ideograma, 0 U+FFFD) |
| Testes | 166, 0 falhas |

Backup antes da edicao: commit `35b399e` e tag `checkpoint/memoria-antes-dedup`.

## Licao que fica

**Arquivo de memoria com duplicacao nao e problema de espaco, e problema de leitura.**
Grep devolvia 3 resultados e o primeiro nao era o mais recente, entao eu lia versao
velha de licao sem saber. Vale mais descobrir "o que se repete" do que medir
"quanto custa".

E **`scanEncoding` nao acha caractere de controle**. Ele pega mojibake, ideograma e
U+FFFD -- tresFAULTS de transcode, nao de escape de string. Quem escrevia com `\a` e
`\f` num here-string produzia arquivo que passa o build com a palavra comida. Falta um
check de controle no `scanEncoding`, e essa correcao nao foi feita aqui.
