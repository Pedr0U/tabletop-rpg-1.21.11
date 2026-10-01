# 2026-10-01 - Pagina 3 da ficha: Skills e Magias

Commit: `1b44c21` (branch `main`).
Anterior: `a848898`, tag `checkpoint-20261001-0730-antes-da-pagina-3-skills-magias`.
Sem push, por decisão do usuário.

## Objetivo

Substituir a tela de Skills avulsa por uma aba da ficha com Skills e Magias lado a
lado, e corrigir os problemas de recorte e de leitura que o usuário só viu em jogo.

## Escopo

Aba 3 (`activeTab == 2`) do `StatusScreen`, modelo de dados do grimório, payload de
sync, dois formulários novos, e a remoção da tela antiga.

## Alterações

Modelo e dados:

- `SheetData.Spell` e `SheetData.Spellbook`: círculo, execução, custo, CD global,
  atributo de conjuração, filtro e ordenação. Codecs de stream e NBT.
- Saves antigos seguem abrindo: grimório ausente vaza um livro vazio.
- `SheetModel.aligned` preserva `sheet.spellbook()`, pelo mesmo motivo que preserva
  o inventário.
- `RpgNetworking.SheetSpellPayload`: C2S com permissão, persistência e broadcast.
- Limite de 60 magias; CD de 0 a 2048 em três tetos que concordam.

Interface:

- Divisão 50/50, coluna com scroll próprio, filtro de círculo, atributo de
  conjuração, `Modifier` derivado do atributo, CD.
- `SkillFormScreen` e `SpellFormScreen` novos; `SkillsScreen.java` (1408 linhas)
  removido, com o botão do menu e o atalho do Mestre.
- Nome obrigatório: `Save` desativado enquanto vazio, aviso sob a caixa.

## Problemas encontrados e causa raiz

O mais importante não apareceu no build: **widget não é recortado pela faixa da
lista.** O `ItemBox` é desenhado por esta tela e podia ser cortado com `max`/`min`,
mas `Button` e `EditBox` são desenhados pelo vanilla na posição Y que receberam,
sem saber da faixa. O fundo era recortado e o botão não, então vazava por cima do
`+ Skill` e da barra de abas.

Minha primeira tentativa de corrigir isso (`Column.prepare` antes do laço) arrumava
só a geometria, não o desenho, e o usuário voltou a reportar o estouro. Duas lições:

1. `build` verde não prova nada de layout. Nenhum dos dois problemas de recorte era
   detectável por build ou teste.
2. A correção certa foi criar o widget **recortado** na faixa, com altura mínima de
   5 px, em vez de pulá-lo. Pular resolve o vazamento e produz o efeito oposto ao
   pedido ("não deixe sumir instantaneamente") — as duas exigências Bram o mesmo
   mecanismo.

Outros dois corrigidos:

- `delPending` era zerado no `rebuildWidgets` que o **próprio primeiro clique** no
  `Del` dispara, então o segundo clique achava `-1` e nunca confirmava.
- `TextLine` cresce para a **direita** a partir do X que recebe. O `Modifier` recebia
  a largura do painel inteiro e transbordava para fora.

## Validações executadas

- `gradlew build test --no-daemon --console=plain` → `BUILD SUCCESSFUL` (9s na última).
- `scanEncoding` → 120 arquivos, 0 mojibake, 0 U+FFFD. Ele **falhou** duas vezes
  durante a sessão e foi justamente isso que impediu lixo na memória.
- Cliente aberto 5 vezes nesta rodada (`runclient` 1 a 5, logs em
  `%LOCALAPPDATA%\Temp\opencode`); o usuário testou e pediu cada ajuste.

## Não validado

Nenhuma correção visual foi confirmada por screenshot. Fica pendente de leitura do
usuário: o recorte dos dois lados, o pedaço de 5 px na rolagem, a proximidade da CD,
o `Modifier` completo e o `Save` desativado com aviso.

## Limitações e armadilhas registradas

- Gravar a memória com `edit`/`write` **trunca** `b` e `t` no início de palavra
  ("build" vira "uild" com byte `0x08`). Os bytes de controle não aparecem no `read`.
  `scanEncoding` pega e falha o build. Corrigir por índice com `WriteAllLines`.
- `[System.IO.File]::WriteAllLines` converte LF em CRLF. Isso transformou a memória
  de 111 linhas alteradas em 4869 no diff. Restaurado a LF antes de commitar; o
  `.gitattributes` do projeto não cobre `src/` nem `agent/`.
- O console do PowerShell exibe `?` onde há UTF-8 válido. Conferir bytes antes de
  "consertar" um arquivo que está correto.

## Aprendizados

- `scanEncoding` funciona como rede de segurança de verdade, mas só porque é parte
  de `check`. Deixá-lo no lugar é o que mantém a memória confiável.
- `Widget` do vanilla é desenhado fora do sistema de coordenadas da tela. Qualquer
  layout com rolagem precisa tratar widget como "criado ou recortado", nunca como
  "criado na posição Y original".

## Próximos passos

1. Sincronizar `FUNCIONALIDADES-E-COMANDOS.md` com a aba 3 (`catalogo-sync`).
2. Confirmar em jogo os itens da seção "Não validado".
3. Tag local do checkpoint, se o usuário quiser.
