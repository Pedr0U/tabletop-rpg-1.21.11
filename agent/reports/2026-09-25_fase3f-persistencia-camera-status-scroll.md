# Implementation Report — Fase 3F: Persistência, Câmera, Status e Scroll

**Data:** 25/09/2026
**Branch:** `main`
**Base:** `fae83e9` (nada commitado nesta rodada)
**Status final:** BUILD SUCCESSFUL — validação de runtime PENDENTE

## Objetivo

Atender a cinco feedbacks de runtime do usuário, todos posteriores à Fase 3E:
persistência ao fechar com `Ctrl+C`, tecla V/F5 fora do modo espectador,
layout da tela de Status, braço do caído ao orbitar a câmera, e a rolagem
invertida da descrição de skills.

## Causas raiz (com evidência)

### 1. Perda ao fechar com Ctrl+C

**Evidência:** o log do cliente continha
`Ficha carregada do NBT de Player452 (1 skills, 20 pericias)`, ou seja, o NBT
grava e lê corretamente. Porém, uma varredura dos 60 arquivos `.dat` do mundo
não encontrou a chave `tabletoprpg_sheet` em nenhum deles. Não existia nenhum
registro de `ServerLifecycleEvents` no mod.

**INFERÊNCIA:** o contraste que o usuário descreveu (sair/entrar preserva, mas
reiniciar não) tem explicação simples — `SessionManager.characterSheets` é um
mapa estático em memória. Sair e entrar no mesmo processo nunca leu nada do
disco. O `Ctrl+C` encerra o processo, e sem um hook de desligamento o único
caminho de persistência era o NBT, que não era gravado nesse momento.

**Correção:** `SheetPersistenceEvents` registra `SERVER_STOPPING` e chama
`server.getPlayerList().saveAll()`.

### 2. Scroll invertido

**Evidência:** os dois caminhos de rolagem usavam sinais opostos com a mesma
convenção. Com `step = -1` ao rolar para baixo:

- lista: `skillScroll - step` → avança (comportamento padrão);
- popup: `popupScroll + step` → recua, e ao já estar em `0` ficava preso em `0`.

Isso reproduz exatamente o relato: rolar para cima adiantava o texto e rolar
para baixo não fazia nada.

**Correção:** o popup passou a usar `popupScroll - step`, igual à lista.

### 3. Setas dos atributos desalinhadas no Status

**Evidência:** `addAttributeRow` calculava a largura do rótulo por linha, como
`font.width(label) + 6`, e derivava `minusX = x0 + labelW`. Cada atributo tinha
uma coluna de setas diferente — "Constituição", por ter texto mais largo,
avançava sobre a linha de cima.

**Correção:** `fixedAttributeLabelWidth(...)` mede o maior rótulo dos seis
atributos, então todas as linhas compartilham a mesma coluna.

### 4. Cabeça do caído girando 360°

Hipótese anterior refutada por leitura do código vanilla: `isVisuallySwimming()`
retorna `hasPose(Pose.SWIMMING)` e `swimAmount` sobe `+0.09` por tick. Forçar a
pose já era necessário e correto. O sintoma restante era o braço ficar para trás
enquanto a câmera orbitava.

**Correção:** `alignDownedTarget(target, camYaw)` passa a alinhar `yBodyRot` e
`yHeadRot` de `Player` caídos pelo yaw real da câmera. Aplicado apenas no
cliente, apenas na renderização.

## O que mudou

| Arquivo | Mudança |
|---|---|
| `SheetPersistenceEvents.java` (novo) | `SERVER_STOPPING` → `playerList.saveAll()`, com log e `try/catch` deliberado (exceção aqui derrubaria o servidor e perderia todos os jogadores) |
| `TabletopRpg.java` | Registra `SheetPersistenceEvents.register()` no `onInitialize()` |
| `SpectatorCameraController.java` | `alignDownedTarget()` nos ramos mouse-controlled e de órbita; `cycleMode()` bifurca fora do espectador |
| `SkillsScreen.java` | Sinal do scroll do popup corrigido; barra de rolagem visível, clicável e arrastável; import de `MouseButtonEvent` |
| `StatusScreen.java` | Painel 520 → 600; `fixedAttributeLabelWidth()` |
| `MinecraftMixin.java` | Guard de F5/carrossel reduzido a `locked` |

### Detalhe da barra de rolagem

Trilho à direita dentro da moldura, polegar proporcional ao texto
(`visible / lines.size()`, mínimo de 8px), só desenhado quando há overflow.
Clique no trilho salta para a posição; arrasto com botão esquerdo acompanha o
polegar; `draggingScrollbar` é limpo em `mouseReleased` e quando o popup fecha.
Somente botão esquerdo — o direito é reservado ao X de remover skill.

## Problemas encontrados durante a execução

1. **Bug introduzido por mim:** ao editar o ramo de órbita automática,
   substitui `angle += ANGULAR_SPEED;` em vez de mantê-lo. Detectado na leitura
   seguinte e restaurado.
2. **`angle` é `double`:** `alignDownedTarget(target, angle)` não compilava
   (`possible lossy conversion`). Em vez de forçar cast, a chamada passou a
   usar `computeYaw(camPos)` — o mesmo yaw entregue ao rig, o que é mais correto
   que o ângulo bruto de órbita, que tem origem diferente.
3. **Assinaturas de mouse mudaram no 1.21.11:** `ContainerEventHandler` agora
   usa `mouseClicked(MouseButtonEvent, boolean)`, `mouseReleased(MouseButtonEvent)`
   e `mouseDragged(MouseButtonEvent, double, double)`. As assinaturas antigas com
   `int button` não existem mais. Confirmado com `javap`.
4. **Mojibake em `TabletopRpg.java`:** tres linhas de comentario com acentos
   corrompidos ("interacao", "sessao", "fisico", "selecao"). Repara palavra a palavra,
   pois um round-trip global de encoding destruiria acentos legitimos.
   0xE7 em cp1252, que é byte inválido ao ser lido como UTF-8.
5. **BOM introduzido:** `Set-Content -Encoding UTF8` no PowerShell 5.1 grava BOM.
   Detectado no `git diff` (`+﻿package`) e removido com `UTF8Encoding($false)`.

## Validação

- `.\gradlew.bat build --console=plain` → **BUILD SUCCESSFUL** (final, após todas
  as alterações).
- CJK/full-width nos arquivos tocados: **0 ocorrências**.
- `git diff` conferido: nenhuma alteração de acidental em lógica; o conjunto
  continua sendo só o desta rodada mais a Fase 3E.

### Testes de runtime que o usuário precisa fazer

1. **Persistência:** alterar a ficha, fechar com `Ctrl+C`, reabrir. Procurar no
   log a linha `Desligando: NBT de N jogador(es) gravado(s)`. Ela prova que a
   gravação no desligamento rodou.
2. **V/F5:** fora de espectador, V alterna entre livre e a câmera normal; F5
   escolhe a perspectiva; sair do livre restaura a perspectiva escolhida.
3. **Status:** painel mais largo, setas dos seis atributos alinhadas na mesma
   coluna.
4. **Braço do caído:** girar a câmera em terceira pessoa e conferir se o braço
   acompanha em vez de ficar para trás.
5. **Scroll:** descrever uma skill longa, rolar para baixo (deve avançar) e
   arrastar a barra.

## Limpeza de mojibake (concluída)

O mojibake pré-existente em 7 arquivos foi reparado. Diagnóstico correto: a
origem **nao** e cp1252, e **CP437** (o byte `0xC3` vira o caractere de moldura
U+251C). Cada caractere acentuado virava um par de 2 chars.

O que foi descartado no caminho, e por quê:

- **Round-trip global de encoding é proibido.** Testado em dry-run: aplicá-lo a
  `TabletopRpg.java` (já limpo) criaria 9 novos chars de dano, e em
  `CombatController.java` pioraria 228 → 269. Os arquivos são **mistos** — parte
  corrompida, parte íntegra. Round-trip é o oposto do que se quer.
- **Mapa de reversão derivado do CP437**, não escrito à mão. O mapa escrito à
  mão errou: `NÃO` é `Ã`, e eu tinha mapeado o par correspondente para `â`.
  Gerando o mapa a partir de CP437(0xC3, 0xNN) → caractere, os 112 pares de
  2 bytes saem corretos e mecânicos.
- **4 pares finais precisaram de mapa por contexto**, porque nao seguem o padrao CP437: o par de "e" acentuado, o de "o" acentuado, o de "C" cedilha maiuscula, e o
  ordinal masculino. Descritos por nome, e nao reproduzidos, para nao
  reintroduzir o caractere corrompido no proprio relatorio.
  (U+00BA) como marcador de corrupção, mas `º` é o ordinal masculino legítimo
  ("2º pacote"). Os 4 casos "residuais" eram texto correto.

Resultado: **0 marcadores de mojibake** e **0 CJK/full-width** em `src/` e
`agent/`. `git diff` confirma que **só comentários** mudaram — nenhuma linha de
código. Build verde.

Validação por codepoints (o console não renderiza encoding com fidelidade):
`Exceções`=U+00E7 U+00F3, `Expõe`=U+00F3, `seleções`=ç õ, `até`=U+00E9,
`lá`=U+00E1, `única`=U+00FA, `descrição`, `expiração`, `expõem`.

## Pendências conhecidas

1. **Ataque/uso na câmera livre.** `MinecraftMixin` foi reduzido a `locked` para
   liberar o F5, o que também deixou de consumir ataque/uso no modo livre. Se
   "corpo parado" incluir ações, separar os guards: F5/carrossel só com
   `locked`; ataque/uso com `locked || SpectatorCameraController.isFreeCameraActive()`.
2. **`SERVER_STOPPING` não cobre morte súbita.** Um `SIGKILL` real não executa o
   shutdown hook; nesse caso seria preciso storage sidecar.
3. **`case FREE` força `THIRD_PERSON_BACK` a cada tick**, então F5 durante a
   câmera livre é sobrescrito. Confirmar se o requisito é só fora do livre.
4. **Testes de runtime ainda não feitos pelo usuário** (ver lista acima).

## Notas para o próximo desenvolvedor

- `MinecraftMixin`, `LocalPlayerMixin` e `EntityTurnMixin` não usam campos
  estáticos; leem `TabletopRpgClient` para evitar cross-loader.
- Mixins na seção `server` do `mixins.json` não rodam no servidor integrado.
  A persistência está na lista comum, com guard `instanceof ServerPlayer`.
- O console deste ambiente não renderiza alguns caracteres de forma fiel: chegou
  a exibir `interação` numa linha cujos codepoints eram lixo. Para auditar
  encoding, sempre despejar os codepoints, nunca confiar na exibição.
- O `.docx` em `agent/reports/` é cópia não versionada, por decisão do usuário.
