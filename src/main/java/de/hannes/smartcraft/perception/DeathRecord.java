package de.hannes.smartcraft.perception;

import net.minecraft.core.BlockPos;

import java.util.Map;

/**
 * Der letzte Tod des Bots.
 *
 * @param cause     Schadensart, z. B. {@code minecraft:fall}
 * @param message   Todesnachricht des Spiels
 * @param attacker  Entity-Typ des Angreifers oder {@code null}
 * @param lostItems verlorene Items als ID → Anzahl
 */
public record DeathRecord(String cause, String message, String attacker, BlockPos pos, String dimension,
						  Map<String, Integer> lostItems, long tick) {
}
