package com.pedro.tabletoprpg;

import net.fabricmc.api.ModInitializer;

import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TabletopRpg implements ModInitializer {
	public static final String MOD_ID = "tabletop-rpg";

	// This logger is used to write text to the console and the log file.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("[TabletopRPG] Inicializando o mod...");

		// Registra os handlers de intera├º├úo (ataque, uso de bloco/item/entidade)
		PlayerControlHandler.register();

		// Registra as regras de dano da sess├úo (jogadores e mobs imunes a dano f├¡sico)
		DamageControlHandler.register();

		// Registra o controle de combate (sele├º├úo/movimento de monstros, auras, highlight)
		CombatController.register();

		// Registra os comandos do Mestre (/rpg ...)
		MasterCommands.register();

		// Registra os payloads de rede (comum) e os receptores do lado servidor
		RpgNetworking.registerPayloads();
		RpgNetworking.registerServerReceivers();

		LOGGER.info("[TabletopRPG] Mod inicializado com sucesso.");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
