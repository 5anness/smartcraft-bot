package de.hannes.aiplayer.reflex;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.EnderMan;

import java.util.List;

/**
 * Verhindert, dass der Bot aus Versehen einen Enderman ansieht: Würde sein Blick einen treffen,
 * schaut er stattdessen auf den Boden. Die Laufrichtung bleibt, nur der Kopf neigt sich.
 * Wer einen Enderman gezielt angreift, meldet ihn mit {@link #allow} an.
 */
public final class GazeGuard {
	private static final int RADIUS = 48;
	private static final int RESCAN_INTERVAL = 10;
	/** Der Enderman prüft mit 0,025; etwas mehr Abstand, damit eine Kopfbewegung nichts auslöst. */
	private static final double STARE_TOLERANCE = 0.06;
	private static final float LOOK_DOWN = 70.0f;

	private static int allowedId = -1;

	private List<EnderMan> nearby = List.of();

	/** Diesen Enderman darf der Bot in diesem Tick ansehen. */
	public static void allow(Entity enderman) {
		allowedId = enderman.getId();
	}

	/** Nach der Aktion und vor dem Tick des Bots aufrufen. */
	void apply(ServerPlayer bot, long tick) {
		int allowed = allowedId;
		allowedId = -1;
		if (tick % RESCAN_INTERVAL == 0) {
			nearby = bot.level().getEntitiesOfClass(EnderMan.class, bot.getBoundingBox().inflate(RADIUS), Entity::isAlive);
		}
		for (EnderMan enderman : nearby) {
			if (enderman.getId() == allowed || !enderman.isAlive()) {
				continue;
			}
			if (enderman.isLookingAtMe(bot, STARE_TOLERANCE, true, false, enderman.getEyeY())) {
				bot.setXRot(LOOK_DOWN);
				if (enderman.isLookingAtMe(bot, STARE_TOLERANCE, true, false, enderman.getEyeY())) {
					// Er steht unter dem Bot: dann eben nach oben.
					bot.setXRot(-LOOK_DOWN);
				}
				return;
			}
		}
	}
}
