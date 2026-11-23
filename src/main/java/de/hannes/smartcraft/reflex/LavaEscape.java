package de.hannes.smartcraft.reflex;

import de.hannes.smartcraft.nav.Moves;
import de.hannes.smartcraft.nav.WorldCells;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * Aus der Lava heraus: Breitensuche durch Lava und Luft zum nächsten Feld, auf dem der Bot sicher steht, und
 * dorthin steuern. Die Wegfindung findet in Lava keinen Startpunkt; im Drachenlauf vom 15.10.2026 verbrannte der
 * Bot so neben dem Ufer.
 */
final class LavaEscape {
	private static final int MAX_CELLS = 1500;
	private static final int REPLAN_INTERVAL = 5;
	private static final int[][] STEPS = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0}};

	private BlockPos next;
	private BlockPos shore;

	BlockPos shore() {
		return shore;
	}
	private int ticks;
	private int placedBlocks;
	private static final int BUILD_AFTER_TICKS = 30;
	private static final int BUILD_INTERVAL = 4;

	int placedBlocks() {
		return placedBlocks;
	}

	/**
	 * Setzt einen nicht brennbaren Baustein in Lava neben dem Bot auf Fußhöhe (darauf springt er dann aus der Lava). Wie ein Spieler nur an einen festen Nachbarblock: so wächst eine Insel vom nächsten Halt zu ihm hin.
	 * So nah wie möglich. @return gesetzte Stelle oder null
	 */
	private static BlockPos buildIsland(ServerPlayer bot) {
		int slot = de.hannes.smartcraft.skill.Inv.findSlot(bot, stack -> de.hannes.smartcraft.skill.Inv.isScaffold(stack)
				&& !de.hannes.smartcraft.skill.Inv.isFlammable(stack));
		if (slot < 0) {
			return null;
		}
		ServerLevel level = bot.level();
		BlockPos feet = BlockPos.containing(bot.getX(), bot.getY() + 0.2, bot.getZ());
		java.util.List<BlockPos> spots = new java.util.ArrayList<>();
		for (int dx = -3; dx <= 3; dx++) {
			for (int dz = -3; dz <= 3; dz++) {
				// Auf Höhe der Lavaoberfläche (Füße), nicht in seinem eigenen Feld: darauf springt er dann über die Lava.
				if (dx == 0 && dz == 0) {
					continue;
				}
				BlockPos pos = feet.offset(dx, 0, dz);
				if (level.getFluidState(pos).is(FluidTags.LAVA) && de.hannes.smartcraft.skill.BlockPlacer.hasSupport(level, pos)) {
					spots.add(pos);
				}
			}
		}
		spots.sort(java.util.Comparator.comparingDouble(pos -> pos.distToCenterSqr(bot.getX(), bot.getY(), bot.getZ())));
		for (BlockPos pos : spots) {
			if (de.hannes.smartcraft.skill.BlockPlacer.place(bot, slot, pos) == null) {
				return pos;
			}
		}
		return null;
	}

	void tick(ServerPlayer bot) {
		boolean inHazard = inHazard(bot);
		if (ticks++ % REPLAN_INTERVAL == 0 || next == null) {
			next = firstStep(bot);
			// Außerhalb der Lava nur zu einem Feld mit Boden bis 3 Blöcke darunter: Nether-Lauf 29 kam aus der Lava,
			// der Reflex schob ihn im Sprung weiter über eine 24 Blöcke tiefe Kante, er starb am Sturz.
			if (next != null && !inHazard && !landing(bot.level(), next)) {
				next = null;
			}
			if (next == null && inHazard) {
				// Kein Ufer in der Nähe: grob in Richtung des nächsten fernen Ufers schwimmen.
				BlockPos far = ticks % 40 == 1 || shore == null ? (shore = farShore(bot)) : shore;
				if (far != null) {
					int sx = Integer.signum(far.getX() - bot.getBlockX());
					int sz = Integer.signum(far.getZ() - bot.getBlockZ());
					next = bot.blockPosition().offset(sx, 0, sz);
				}
			}
		}
		bot.setSprinting(false);
		// Nach 1,5 s noch in der Lava und ohne Feuerresistenz: Enderperle zum nächsten Ufer.
		if (inHazard && ticks > PEARL_AFTER_TICKS && ticks >= nextPearlTick
				&& !bot.hasEffect(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE) && throwPearl(bot)) {
			nextPearlTick = ticks + PEARL_INTERVAL;
			pearlsThrown++;
			return;
		}
		// Kein Ufer in Reichweite oder schon über 1,5 s in der Lava: eine Insel bauen (Hannes' Wunsch, sein Lauf 17.10.:
		// 10 s im Lavasee, der Reflex kam nicht heraus).
		if (inHazard && (next == null || ticks > BUILD_AFTER_TICKS) && ticks % BUILD_INTERVAL == 0) {
			BlockPos placed = buildIsland(bot);
			if (placed != null) {
				placedBlocks++;
				next = null;
			}
		}
		if (next == null) {
			bot.zza = 0;
			bot.xxa = 0;
			bot.setJumping(inHazard);
			if (!inHazard) {
				Vec3 motion = bot.getDeltaMovement();
				bot.setDeltaMovement(0, motion.y, 0);
				bot.hurtMarked = true;
			}
			return;
		}
		double dx = next.getX() + 0.5 - bot.getX();
		double dz = next.getZ() + 0.5 - bot.getZ();
		float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0f;
		bot.setYRot(yaw);
		bot.setYHeadRot(yaw);
		bot.zza = 1.0f;
		bot.xxa = 0;
		// Springen hebt ihn in Lava an und über die Uferkante.
		bot.setJumping(true);
		// Die Eingabe allein reicht beim Fake Player in Lava nicht (im Test hüpfte er am Rand auf der Stelle):
		// Schub Richtung Ufer, an der Kante nach oben, wie ein Spieler beim Herausspringen.
		double length = Math.max(0.01, Math.sqrt(dx * dx + dz * dz));
		Vec3 motion = bot.getDeltaMovement();
		double up = bot.horizontalCollision || next.getY() > bot.getY() ? Math.max(motion.y, 0.3) : motion.y;
		bot.setDeltaMovement(dx / length * 0.12, up, dz / length * 0.12);
		bot.hurtMarked = true;
	}

	/** Lava oder Feuer (auch Seelenfeuer): Nether-Lauf 1 stand 20 s im Seelenfeuer und verbrannte. */
	private static boolean lava(ServerLevel level, BlockPos pos) {
		return level.getFluidState(pos).is(FluidTags.LAVA) || level.getBlockState(pos).is(net.minecraft.tags.BlockTags.FIRE);
	}

	/** Fester Boden höchstens 3 Blöcke unter {@code pos}, ohne Lava oder Feuer dazwischen. */
	static boolean landing(ServerLevel level, BlockPos pos) {
		for (int dy = 0; dy <= 3; dy++) {
			BlockPos below = pos.below(dy + 1);
			if (lava(level, pos.below(dy))) {
				return false;
			}
			if (level.getBlockState(below).isFaceSturdy(level, below, net.minecraft.core.Direction.UP)) {
				return !lava(level, below);
			}
		}
		return false;
	}

	/** Steht der Bot in Lava oder Feuer. */
	static boolean inHazard(ServerPlayer bot) {
		// Auf Seelensand sinkt er ein Stück ein: Die Füße liegen dann im Sandfeld, das Feuer im Feld darüber.
		return bot.isInLava() || lava(bot.level(), bot.blockPosition())
				|| lava(bot.level(), BlockPos.containing(bot.getX(), bot.getY() + 0.5, bot.getZ()));
	}

	/**
	 * Nächstes festes Ufer bis {@link #FAR_RADIUS} Blöcke waagrecht, nahe der Lavaoberfläche; {@code null}, wenn keins.
	 * Nether-Lauf 26: Mitten im Lavameer fand die Suche mit 1500 Feldern kein Ufer, er sprang 7 Minuten auf der Stelle.
	 */
	static BlockPos farShore(ServerPlayer bot) {
		ServerLevel level = bot.level();
		WorldCells cells = new WorldCells(level);
		BlockPos feet = BlockPos.containing(bot.getX(), bot.getY() + 0.5, bot.getZ());
		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;
		for (int dx = -FAR_RADIUS; dx <= FAR_RADIUS; dx += 2) {
			for (int dz = -FAR_RADIUS; dz <= FAR_RADIUS; dz += 2) {
				double distance = dx * dx + dz * dz;
				if (distance >= bestDistance || distance > FAR_RADIUS * FAR_RADIUS) {
					continue;
				}
				// Bis 7 über den Füßen: Tief eingesunken liegt das Ufer höher (Drachenlauf 19: 3 Blöcke unter der Oberfläche,
				// die Suche bis 3 fand kein Ufer, er starb mit 12 Enderperlen im Inventar).
				for (int dy = -1; dy <= 7; dy++) {
					BlockPos pos = feet.offset(dx, dy, dz);
					if (!level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
						break;
					}
					if (!lava(level, pos) && !lava(level, pos.above())
							&& Moves.kind(cells, pos.getX(), pos.getY(), pos.getZ()) == Moves.Kind.GROUND) {
						best = pos;
						bestDistance = distance;
						break;
					}
				}
			}
		}
		return best;
	}

	private static final int FAR_RADIUS = 48;
	private static final int PEARL_AFTER_TICKS = 30;
	private static final int PEARL_INTERVAL = 40;
	private static final double PEARL_RANGE = 40;
	private int nextPearlTick;
	private int pearlsThrown;

	int pearlsThrown() {
		return pearlsThrown;
	}

	/**
	 * Wirft eine Enderperle zum nächsten Ufer bis {@link #PEARL_RANGE} Blöcke: Flugbahn wie im Spiel (1,5 Blöcke je Tick,
	 * Schwerkraft 0,03, Luftwiderstand 0,99), flachster Winkel, der ankommt. @return ob geworfen wurde
	 */
	private boolean throwPearl(ServerPlayer bot) {
		int slot = de.hannes.smartcraft.skill.Inv.findItem(bot, "minecraft:ender_pearl");
		if (slot < 0 || bot.getCooldowns().isOnCooldown(bot.getInventory().getItem(slot))) {
			return false;
		}
		BlockPos target = shore != null ? shore : (shore = farShore(bot));
		if (target == null) {
			return false;
		}
		Vec3 eye = bot.getEyePosition();
		Vec3 goal = Vec3.atBottomCenterOf(target).add(0, 0.2, 0);
		double horizontal = Math.hypot(goal.x - eye.x, goal.z - eye.z);
		if (horizontal > PEARL_RANGE) {
			return false;
		}
		double dy = goal.y - eye.y;
		Double pitch = null;
		for (int deg = -20; deg <= 70; deg++) {
			double rad = Math.toRadians(deg);
			double vx = Math.cos(rad) * 1.5;
			double vy = Math.sin(rad) * 1.5;
			double x = 0;
			double y = 0;
			for (int t = 0; t < 200 && x < horizontal; t++) {
				x += vx;
				y += vy;
				vx *= 0.99;
				vy = vy * 0.99 - 0.03;
			}
			if (x >= horizontal && y >= dy - 0.5) {
				pitch = rad;
				break;
			}
		}
		if (pitch == null) {
			return false;
		}
		double yawRad = Math.atan2(goal.z - eye.z, goal.x - eye.x);
		float yaw = (float) (yawRad * Mth.RAD_TO_DEG) - 90.0f;
		bot.setYRot(yaw);
		bot.setYHeadRot(yaw);
		bot.setXRot((float) -Math.toDegrees(pitch));
		de.hannes.smartcraft.skill.Inv.hold(bot, slot);
		bot.gameMode.useItem(bot, bot.level(), bot.getMainHandItem(), net.minecraft.world.InteractionHand.MAIN_HAND);
		de.hannes.smartcraft.SmartCraftMod.LOGGER.info("lava_escape pearl to " + target.toShortString() + " from " + bot.blockPosition().toShortString());
		return true;
	}

	private static BlockPos firstStep(ServerPlayer bot) {
		ServerLevel level = bot.level();
		WorldCells cells = new WorldCells(level);
		BlockPos start = BlockPos.containing(bot.getX(), bot.getY() + 0.5, bot.getZ());
		Map<BlockPos, BlockPos> cameFrom = new HashMap<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		cameFrom.put(start, start);
		queue.add(start);
		while (!queue.isEmpty() && cameFrom.size() < MAX_CELLS) {
			BlockPos pos = queue.poll();
			if (!pos.equals(start) && !lava(level, pos) && !lava(level, pos.above())
					&& Moves.kind(cells, pos.getX(), pos.getY(), pos.getZ()) == Moves.Kind.GROUND) {
				BlockPos step = pos;
				while (!cameFrom.get(step).equals(start)) {
					step = cameFrom.get(step);
				}
				return step;
			}
			for (int[] s : STEPS) {
				BlockPos n = pos.offset(s[0], s[1], s[2]);
				if (cameFrom.containsKey(n) || Math.abs(n.getY() - start.getY()) > 2) {
					continue;
				}
				boolean feetFree = lava(level, n) || cells.at(n.getX(), n.getY(), n.getZ()).passable();
				boolean headFree = lava(level, n.above()) || cells.at(n.getX(), n.getY() + 1, n.getZ()).passable();
				if (feetFree && headFree) {
					cameFrom.put(n, pos);
					queue.add(n);
				}
			}
		}
		return null;
	}
}
