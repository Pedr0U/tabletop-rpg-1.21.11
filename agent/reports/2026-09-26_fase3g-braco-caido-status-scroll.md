# Implementation Report — Fase 3G: braço do caído, Status e scroll

**Data:** 26/09/2026
**Branch:** `main`
**Status:** BUILD SUCCESSFUL — runtime PENDENTE
**Jar de teste:** `build/libs/tabletop-rpg_TESTE_26-09-2026_0004.jar`

## Feedback do usuário (texto literal, para contexto)

- "a câmera está funcionando perfeitamente... a única coisa que falta é a questão
  da mão quando caído, que conforme eu mexo para a direita a mão vai ficando
  para a esquerda e não segue a visão do player, e a mesma coisa quando mexo a
  câmera para a esquerda"
- "aumente mais um pouco a largura do menu, ainda estou achando muito colado com
  os textos de dentro do menu, inclusive o gap do número dos atributos está muito
  grande em relação os botões das setas"
- "A correção do scroll ainda não foi implementado"
- "a ficha mesmo após eu fechar o servidor e dar o comando `.\gradlew runServer`
  ela fica salva... está funcionando perfeitamente, parabéns dev"

## 1. Braço do caído — causa raiz reconsiderada

### Hipótese anterior (errada)

Setar `yBodyRot`/`yHeadRot` na entidade durante o tick da câmera. Não funciona,
por dois motivos identificados:

1. `Entity.turn` está cancelado no modo espectador (`EntityTurnMixin`). A rotação
   do jogador fica **congelada** no instante em que a câmera é ativada. Com o
   corpo congelado e a câmera orbitando, o corpo parece girar para o lado
   **oposto** ao movimento da câmera — que é exatamente o sintoma relatado.
   Efeito colateral relevante: se o sintoma é do corpo inteiro e não só da mão,
   a causa está na **rotação base**, não em animação de braço.
2. Para um caído que **não é** o jogador local, o `LivingEntity.tick()` do
   cliente reescreve `yBodyRot` a cada tick, anulando o valor setado.

### Correção

Novo mixin `DownedBodyAlignMixin`, em `extractRenderState` de
`LivingEntityRenderer` (`@At("TAIL")`). É o ponto em que o vanilla decide o que
será desenhado, logo antes da renderização: nenhum tick roda depois, então o
valor não pode ser sobrescrito. Aplica `yRot` (yaw do corpo, usado no torso e
nos braços pelo `HumanoidRenderer`) e `bodyRot`. `xRot` fica intacto para a
cabeça não acompanhar o pitch.

Novo accessor `SpectatorCameraController.getCurrentYaw()`, alimentado em
`ensureRigActive` — todos os caminhos de câmera passam por lá, então é sempre o
yaw que o usuário está vendo, incluindo a órbita automática (cujo ângulo bruto
`angle` tem origem diferente da câmera).

A chamada antiga por entidade foi mantida, para não regredir a cabeça.

### Risco conhecido

`extractRenderState` é genérico e o vanilla gera uma ponte sintética
`(Entity, EntityRenderState, float)`. O mixin aponta para essa ponte. Se o jogo
crashar na abertura, esta assinatura é a primeira coisa a conferir.
`defaultRequire: 1` foi mantido de propósito: falha barulhenta é melhor que
falha silenciosa.

## 2. Layout do Status

- `MAX_PANEL_W_STATUS`: 600 → **672**.
- Novo `PANEL_PAD = 14`: o conteúdo começa 14px para dentro da moldura, em vez de
  encostar na borda. A moldura e o botão de fechar continuam desenhados pelo
  `CharacterSheetScreen`, então só o conteúdo muda.
- Novo `VALUE_W = 20`, largura **fixa** da caixa do número do atributo.

**Causa do gap:** `valueW` era calculado como `plusX - 4 - valueX`, ou seja, o
número ocupava todo o vão entre as duas setas e ficava centralizado longe de
ambas. Agora o grupo fica `[<] N [>]` compacto: `valueX = minusX + ARROW_SIZE + 3`
e `plusX = valueX + VALUE_W + 3`.

Como `labelW` e `VALUE_W` são fixos e não dependem do rótulo nem do valor, as
duas setas continuam na mesma coluna em todas as linhas — o requisito anterior
se preserva.

## 3. Scroll

**Constatação:** a implementação **estava** no jar. Verificado por inspeção do
bytecode: `onScrollbar`, `draggingScrollbar`, `popupMaxScroll` e
`setPopupScrollFromMouse` presentes em `SkillsScreen.class`. O usuário testou um
build anterior (a verificação anterior que reportei como "não está no jar" usou
caminho de classe errado e foi erro meu).

Ainda assim reforçada a visibilidade, que era uma crítica legítima:
- `BAR_W`: 4 → **6**.
- Trilho `COL_SCROLL_TRACK` (escuro) e polegar `COL_SCROLL_THUMB` (claro), antes
  ambos usavam cores de baixo contraste.
- Hint explícito: `"N linhas abaixo (arraste a barra)"`, no lugar de `"+N"`.

## Validação

- `.\gradlew.bat build` → BUILD SUCCESSFUL.
- `DownedBodyAlignMixin`, `SheetPersistenceEvents` e `PlayerSheetPersistenceMixin`
  presentes no jar de teste.
- 0 marcadores de mojibake, 0 CJK em `src/` e `agent/`.

## Pendências

- Teste de runtime dos três itens, pelo usuário.

## Item fechado

**Ataque/uso na câmera livre.** Estava anotado como pendência, mas a evidência
nao sustenta. O usuario explicou "corpo parado" como a rotacao do corpo congelado
que faz a mão não acompanhar — isto é, o sintoma do item 1, não uma ação
bloqueada. Nenhum problema de ataque/uso foi relatado, e a câmera foi aprovada
como "funcionando perfeitamente". O guard do `MinecraftMixin` ter sido reduzido
a `locked` não teve impacto visível para o usuário.

Se surgir queixa de não conseguir atacar/usar no modo livre, o caminho é separar
os guards de `attack`/`use` do guard de `locked`, e não reverter esta mudança.

## Não verificado

Nenhuma das três correções foi validada visualmente. O agente não tem como
lançar o cliente com conta válida; `runServer` não carrega mixins de cliente.
O único proxy disponível foi a conferência do bytecode do jar.
