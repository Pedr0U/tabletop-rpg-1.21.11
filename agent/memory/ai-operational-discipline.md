# Disciplina de turno do agente — lição durável (2026-09-25)

## Problema observado

O usuário percebeu que, após rodar o build (que **terminava com sucesso em ~7s**), o
agente ficava "travado pensando" por muito tempo, até ser interrompido com um
"continua". O gargalo **não era o Gradle**.

## Root cause (evidência)

- **FATO:** `.\gradlew.bat compileJava` → `BUILD SUCCESSFUL in 7s`. Build rápido.
- **FATO:** nesta sessão o contexto principal acumulou arquivos grandes lidos
  integralmente: `RpgNetworking.java` (804 linhas), `TabletopRpgClient.java` (332),
  `RpgMenuScreen.java` (211), `PlayerListScreen.java` (78), `SheetData.java` (novo),
  mais saídas de `javap` e de build.
- **FATO:** a parte anterior da sessão exibiu degeneração textual — frases
  repetidas, troca abrupta de idioma (pt/en/it), e bloco de tool-call XML
  duplicado/escapado no final da mensagem.
- **INFERÊNCIA:** degeneração de saída + contexto grande = contexto saturado.
  A partir daí, cada turno reprocessa tudo, a geração degrada em repetição e a
  perceived latency sobe muito. O "travamento" é o modelo tentando sair do loop
  de repetição, não uma espera por I/O.

## Gatilho composto (o que causar de novo)

1. Build/tool com saída grande **não filtrada** (`2>&1` sem filtro).
2. `read` de arquivo inteiro quando bastam `grep` pontual ou um subagente.
3. Turno longo com muitas edições sem `todowrite` e sem ponto de corte.
4. Nenhum passo de "gravação de estado" no meio da tarefa — se a sessão cair, o
   contexto perdido não está em disco.

## Regras para evitar (duráveis)

1. ** sempre usar `todowrite`** desde o primeiro passo em tarefas com >2 etapas.
   É o que mantém o plano fora do texto gerado.
2. **Contexto é recurso escasso.** Para descoberta em área desconhecida, delegar
   ao subagente `explorer` em vez de ler arquivos grandes no contexto principal.
   O subagente devolve evidência curta; o arquivo grande nunca entra aqui.
3. **Ler arquivos >300 linhas é último recurso.** Preferir `grep` com padrão
   preciso + `read` só do intervalo (`offset`/`limit`) relevante.
4. **Filtrar toda saída de terminal.** Usar `Select-String`/pipes para reduzir a
   saída; se a saída completa for necessária, mandar para arquivo temporário e
   só então ler o trecho. Nunca despejar log de build inteiro no contexto.
5. **"BUILD SUCCESSFUL" é um resultado terminal.** Não reexecutar, não
   second-guess, não re-rodar para "confirmar". Registrar no todo e seguir para a
   próxima etapa imediatamente.
6. **Ponto de corte a cada subtarefa.** Depois de cada validação, gravar o
   resultado (todo + eventual arquivo) antes de continuar. Assim a perda de
   contexto não perde trabalho.
7. **Sinal de degeneração = parar de falar.** Se eu começar a repetir frases,
   mudar de idioma ou gerar tool-call duplicada, o correto é: gravar o estado em
   disco, emitir uma linha de status e encerrar o turno. Nunca tentar "continuar
   falando" para sair do loop.
8. **Build longo = timeout generoso e silêncio.** Passar `timeout` alto e não
   poluir o meio do caminho com novos comandos.

## Como foi corrigido nesta sessão

- Contexto deixado leve: subagente `explorer` para descoberta, `read` só do
  necessário, saída de `javap` filtrada por padrão (só as assinaturas usadas).
- `todowrite`aito no início da FASE 3, com uma entrada `in_progress` por vez.
- Build tratado como resultado terminal e o próximo passo发射 imediatamente.

## DIRETIVA DO USUARIO (25/09/2026) - explicar toda permissao de pasta

> "Toda vez que pedir permissao para acessar uma pasta, explicar o porque quero
> permissao para aquelas pastas."

- Antes de (ou imediatamente apos) qualquer comando que acesse um caminho FORA
  do workspace, dizer em UMA FRASE: qual caminho, o que vou fazer la, e por que
  nao da para fazer dentro do repo.
- Nao esperar o usuario perguntar. Ele viu o aviso de `Program Files` e de
  `Temp` e so descobriu agora, o que significa que a explicacao falhou.
- Justificativas que valem para este projeto:
  - `C:\Program Files\Java` -> executar `javap.exe` (so LEITURA) para verificar a
    hierarquia real das classes do Minecraft. Erros do compilador sobre
    `StreamCodec`/`EnumCodec` sao ambigues; sem `javap` eu chutaria.
  - `%LOCALAPPDATA%\Temp\opencode` -> arquivos descartaveis: extracao de
    `word/document.xml` do .docx, scripts PowerShell, dumps do `javap` (200 KB a
    970 KB cada) e logs de build. Motivo: nao poluir o repo com arquivos de lixo
    e nao estourar contexto com log bruto.
- Regra: qualquer escrita em pasta do sistema e erro meu, mesmo pre-aprovada.
- Oferecer limpeza do que foi acumulado em Temp; apagar arquivo e acao
  destrutiva e exige confirmacao, mesmo em pasta temporaria.
