# Tabletop RPG Mod

Um mod para Minecraft (Fabric 1.21.11) focado em trazer a experiência de RPG de mesa para dentro do jogo.

## 📦 Requisitos

Antes de instalar o mod, você precisará ter o seguinte instalado no seu Minecraft:
* **Minecraft:** 1.21.11
* **Fabric Loader:** Compatível com a versão 1.21.11
* **Fabric API:** Essencial para o funcionamento de mods no Fabric.

## ⚙️ Como Instalar

Siga estes passos simples para instalar o mod no seu computador (Windows):

1. **Instale o Fabric:** Se você ainda não tem, baixe o [Instalador do Fabric](https://fabricmc.net/use/installer/) e instale o perfil cliente para a versão 1.21.11.
2. **Baixe o Fabric API:** Baixe o `.jar` do [Fabric API](https://modrinth.com/mod/fabric-api) compatível com a 1.21.11.
3. **Copie a build do Tabletop RPG Mod:** Copie o arquivo `.jar` deste mod na aba para `tabletop-rpg-template-1.21.11-main\build\libs`.
4. **Abra a pasta de mods:** No seu teclado, aperte `Windows + R`, digite `%appdata%\.minecraft\mods` e aperte Enter. (Se a pasta `mods` não existir, você pode criá-la).
5. **Coloque os arquivos:** Cole os arquivos `.jar` do Fabric API e do Tabletop RPG Mod para dentro da pasta `mods`.
6. **Jogue!** Abra o seu Minecraft Launcher, certifique-se de selecionar o perfil do **Fabric** e inicie o jogo.

## 🎲 Como Abrir e Jogar

Uma vez dentro do mundo ou servidor, o mod oferece ferramentas visuais e em texto para facilitar a sua campanha:

### Menu TableTop RPG
Para acessar a interface visual de rolagem de dados e controle da sessão, utilize o atalho de teclado `R`.
* Navegue até a aba **Rolls**.
* Clique nos botões para formar a sua equação (ex: `d20`, `+10`).
* Clique em **Roll!** para o servidor calcular e exibir o resultado no chat para todos os jogadores.

### Comandos de Chat
Se preferir rodar dados de forma rápida diretamente pelo chat, o mod possui um sistema integrado e seguro:

* `/rpg roll <expressão>` - Rola os dados informados. 
  * *Exemplo:* `/rpg roll d20+d10+9` 
  * O servidor anunciará o valor e os detalhes matemáticos no chat!

## 🛠️ Para Desenvolvedores

Se quiser clonar este projeto e editá-lo:
1. Clone o repositório: `git clone https://github.com/SeuUsuario/tabletop-rpg-template.git`
2. Abra a pasta no IntelliJ IDEA.
3. Aguarde o Gradle sincronizar (pode demorar alguns minutos na primeira vez).
4. Rode a task `runClient` para abrir o Minecraft em modo de teste.
