package de.hannes.smartcraft.skill;

import de.hannes.smartcraft.nav.Goal;
import de.hannes.smartcraft.nav.Moves;
import de.hannes.smartcraft.nav.Pos;
import de.hannes.smartcraft.nav.WorldCells;
import de.hannes.smartcraft.reflex.GazeGuard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.RangedAttackMob;

/**
 * Kämpft gegen ein einzelnes Wesen, bis es tot ist. Einmal pro Tick {@link #tick} aufrufen.
 *
 * <p>Der Bot schlägt wie ein Spieler: beste Waffe, nur volle Schläge, wenn möglich im Fallen
 * (kritischer Treffer). In der Nähe steuert er direkt auf das Ziel zu, weiter weg über die
 * Wegsuche. Creeper: zuschlagen und zurückweichen, solange er zischt. Schützen: im Zickzack heran.
 */
public final class Fighter {
	public enum State {
		RUNNING, KILLED, LOST, FAILED
	}

	private static final double MAX_DISTANCE = 48;
	/** Bis hierhin steuert der Bot direkt, wenn er das Ziel sieht; darüber sucht er einen Weg. */
	private static final double DIRECT_DISTANCE = 10;
	private static final int RETARGET_INTERVAL_TICKS = 10;
	private static final int MAX_PATH_FAILURES = 4;
	/** So nah gilt das Ziel als erreicht; kommt hier so lange kein Treffer zustande, gibt er auf. */
	private static final double CLOSE_DISTANCE = 3.0;
	private static final int MAX_CLOSE_NO_HIT_TICKS = 200;
	private static final int PATH_RETRY_TICKS = 20;
	/** Ohne Fortschritt beim direkten Steuern übernimmt so lange die Wegsuche. */
	private static final int STUCK_TICKS = 30;
	private static final int PATHFINDER_TICKS_AFTER_STUCK = 60;
	/** Ein Creeper hört erst auf zu zischen, wenn sein Ziel weiter als 7 Blöcke weg ist. */
	private static final double CREEPER_SAFE_DISTANCE = 8.0;
	/** Näher als so geht der Bot an einen Creeper nur mit vollem Schlag heran. */
	private static final double CREEPER_WAIT_DISTANCE = 4.5;
	private static final int ZIGZAG_TICKS = 10;
	private static final double ZIGZAG_ANGLE = 40 * Mth.DEG_TO_RAD;
	/** Ab hier ist der Schlag so weit geladen, dass sich der Absprung für einen kritischen Treffer lohnt. */
	private static final float JUMP_AT_CHARGE = 0.7f;
	private static final int MAX_AIR_TICKS = 14;

	private final LivingEntity target;
	private PathFollower follower;
	private int ticks;
	private int pathFailures;
	private int waitTicks;
	private int pathfinderTicks;
	private int stuckTicks;
	/** Ticks dicht am Ziel, ohne dass ein Treffer möglich war (Wand dazwischen, Ziel über der Decke). */
	private int closeNoHitTicks;
	private double lastX;
	private double lastZ;
	private int airTicks;
	private boolean jumped;
	private int backOffTicks;

	private int hits;
	private int crits;
	private String failure;
	private String hint;

	public Fighter(LivingEntity target) {
		this.target = target;
	}

	private Moves.Options moveOptions = Moves.Options.NONE;

	/**
	 * Auf dem Weg zum Ziel graben und bauen (Perlen-Lauf 14: im dichten Warped-Wald nie an einen Enderman heran), aber
	 * nicht senkrecht hochbauen: Hannes' Lauf 28.10.: auf einer 1×1-Säule zum Enderman hinauf stieß der ihn herunter, tot.
	 */
	public Fighter allowDig() {
		return allowDig(true);
	}

	/** @param place auch Blöcke setzen; im Nether aus: Drachenläufe 60, 64, 69 bauten sich im Kampf an Kanten, ein
	 * Enderman-Schlag warf sie 24 bis 40 Blöcke tief. Gereizte Endermen kommen von selbst heran. */
	public Fighter allowDig(boolean place) {
		moveOptions = new Moves.Options(true, place).withoutPillar();
		return this;
	}

	public LivingEntity target() {
		return target;
	}

	public int hits() {
		return hits;
	}

	public int crits() {
		return crits;
	}

	/** Bei {@link State#FAILED}: {@code no_path} oder {@code stuck}. */
	public String failure() {
		return failure;
	}

	public String hint() {
		return hint;
	}

	public State tick(ServerPlayer bot) {
		if (target.isDeadOrDying()) {
			return State.KILLED;
		}
		if (target.isRemoved() || target.level() != bot.level() || bot.distanceTo(target) > MAX_DISTANCE) {
			return State.LOST;
		}
		if (ticks++ % 20 == 0) {
			Combat.holdBestWeapon(bot);
		}
		if (target instanceof EnderMan) {
			// Wer einen Enderman angreift, darf ihn ansehen.
			GazeGuard.allow(target);
		}

		double dx = target.getX() - bot.getX();
		double dz = target.getZ() - bot.getZ();
		double distance = Math.max(0.001, Math.sqrt(dx * dx + dz * dz));
		double towardX = dx / distance;
		double towardZ = dz / distance;
		WorldCells cells = new WorldCells(bot.level());
		float charge = bot.getAttackStrengthScale(0.5f);
		boolean canHit = bot.isWithinAttackRange(bot.getMainHandItem(), target.getBoundingBox(), 0.0) && bot.hasLineOfSight(target);

		if (target instanceof Creeper creeper && tickCreeper(bot, creeper, cells, distance, towardX, towardZ, charge, canHit)) {
			return State.RUNNING;
		}

		// Hoglins, Zoglins und Brutes schleudern nach oben, über jedes Geländer. Ein abgeblockter Schlag schleudert nicht
		// (Vanilla wirft nur nach echtem Schaden). An einer Kante deshalb den Schild oben halten, bis zugeschlagen wird.
		// Drachenlauf 67: Während er Geländer setzte, warf ihn ein Hoglin hoch, 42 Blöcke Sturz; ebenso Lauf 58.
		boolean tosser = target instanceof net.minecraft.world.entity.monster.hoglin.Hoglin
				|| target instanceof net.minecraft.world.entity.monster.Zoglin
				|| target instanceof net.minecraft.world.entity.monster.piglin.PiglinBrute;
		// Witherskelette schlagen 8 je Treffer: auch auf dem Weg zu ihm den Schild oben halten, sobald es nah ist. Drachenlauf 80:
		// Er baute sich zu einem hin und bekam dabei vier Treffer in Folge, tot.
		if (target instanceof net.minecraft.world.entity.monster.skeleton.WitherSkeleton && !canHit && distance <= 3.5
				&& Combat.hasShieldReady(bot)) {
			Combat.face(bot, target);
			Combat.raiseShield(bot);
		}
		if (tosser && Combat.hasShieldReady(bot) && nearDrop(bot)) {
			Combat.face(bot, target);
			Combat.raiseShield(bot);
		}
		// Nicht an der Lava kämpfen: erst vom Rand weg (Drachenlauf 25: ein Hoglin stieß ihn in die Lava, 7 s darin, verbrannt).
		if (lavaRetreatTicks < MAX_LAVA_RETREAT_TICKS) {
			double[] lava = lavaNear(bot);
			// Hoglins, Zoglins und Brutes schleudern weit: auch von tiefen Kanten weg. Drachenlauf 46: defend gegen zwei Hoglins
			// auf seiner Säule neben einem Lavasee, ein Stoß warf ihn 20 Blöcke tief in die Lava.
			// Im Nether gegen jeden Gegner: Drachenlauf 51 wehrte sich am Spawner gegen Lohen, ein Feuerball stieß ihn 44 Blöcke tief
			// in einen Lavasee.
			if (lava == null && (bot.level().dimension() == net.minecraft.world.level.Level.NETHER
					|| target instanceof net.minecraft.world.entity.monster.hoglin.Hoglin
					|| target instanceof net.minecraft.world.entity.monster.Zoglin
					|| target instanceof net.minecraft.world.entity.monster.piglin.PiglinBrute)) {
				lava = dropNear(bot);
				// Zusätzlich Geländer an den offenen Seiten, einmal je Kampf und Standplatz. Drachenlauf 60: ein Enderman-Schlag warf
				// ihn im Warped-Wald über die Kante in einen Lavasee 40 Blöcke tiefer.
				if (lava != null && bot.onGround() && !bot.blockPosition().equals(railedAt)) {
					railedAt = bot.blockPosition();
					de.hannes.smartcraft.action.GotoAction.rail(bot);
				}
			}
			if (lava != null) {
				double[] away = Combat.safeDirection(bot, cells, -lava[0], -lava[1]);
				if (away != null) {
					lavaRetreatTicks++;
					follower = null;
					Combat.face(bot, target);
					Combat.move(bot, away[0], away[1], false);
					return State.RUNNING;
				}
			} else {
				lavaRetreatTicks = 0;
			}
		}
		if (canHit) {
			closeNoHitTicks = 0;
			follower = null;
			stuckTicks = 0;
			Combat.halt(bot);
			Combat.face(bot, target);
			// Kein Krit-Sprung an einer Kante: In der Luft trägt der Rückstoß weiter. Drachenlauf 18: Ein Witherskelett traf ihn
			// auf einer Festungsbrücke, er fiel 19 Blöcke (17 Schaden) und starb am Wither-Effekt.
			// Gegen Lohen kein Krit-Sprung: Zwischen den Schlägen bleibt so der Schild oben und hält Feuerbälle und Schläge ab
			// (Drachenläufe 30, 32: farm_spawner brach nach 15 bis 19 s mit health_low ab, oft ohne einen Kill).
			// Gegen Witherskelette ebenso: Drachenlauf 78, drei Treffer zu je 8 in Folge, während er für Krits sprang.
			strikeWhenReady(bot, charge, !(target instanceof Creeper) && !(target instanceof net.minecraft.world.entity.monster.Blaze)
					&& !(target instanceof net.minecraft.world.entity.monster.skeleton.WitherSkeleton) && !nearDrop(bot));
			return State.RUNNING;
		}
		jumped = false;
		if (distance < CLOSE_DISTANCE && ++closeNoHitTicks > MAX_CLOSE_NO_HIT_TICKS) {
			// Nah dran, aber nie treffbar: Weiterkreisen bis zum Zeitlimit brächte nichts.
			failure = "no_path";
			hint = "target is close but cannot be hit (wall or no line of sight)";
			return State.FAILED;
		}
		return approach(bot, cells, distance, towardX, towardZ);
	}

	/**
	 * Creeper zünden, sobald ihr Ziel näher als drei Blöcke ist, und hören erst jenseits von sieben
	 * wieder auf. Also: nur mit vollem Schlag heran, im Sprint treffen (mehr Rückstoß) und weg.
	 *
	 * @return {@code true}, wenn dieser Tick damit erledigt ist
	 */
	private boolean tickCreeper(ServerPlayer bot, Creeper creeper, WorldCells cells, double distance,
								double towardX, double towardZ, float charge, boolean canHit) {
		boolean hissing = creeper.getSwellDir() > 0 || creeper.isIgnited();
		if (hissing && distance < CREEPER_SAFE_DISTANCE || backOffTicks > 0) {
			backOffTicks = Math.max(0, backOffTicks - 1);
			follower = null;
			double[] away = Combat.safeDirection(bot, cells, -towardX, -towardZ);
			if (away == null) {
				// Kein Ausweg: dann wenigstens weiter zuschlagen, vielleicht stirbt er vorher.
				return false;
			}
			if (Combat.hasShieldReady(bot)) {
				// Mit Schild: dem Creeper zugewandt bleiben, der Schild hält die Explosion ab.
				Combat.face(bot, creeper);
				Combat.raiseShield(bot);
				Combat.move(bot, away[0], away[1], false);
			} else {
				Combat.face(bot, bot.getX() + away[0], bot.getEyeY(), bot.getZ() + away[1]);
				Combat.move(bot, away[0], away[1], true);
			}
			return true;
		}
		if (!canHit && charge < 0.9f && distance < CREEPER_WAIT_DISTANCE) {
			// Schlag noch nicht bereit: Abstand halten statt in seine Zündreichweite zu laufen.
			follower = null;
			double[] away = Combat.safeDirection(bot, cells, -towardX, -towardZ);
			Combat.face(bot, creeper);
			if (away != null) {
				Combat.move(bot, away[0], away[1], false);
			} else {
				Combat.halt(bot);
			}
			return true;
		}
		return false;
	}

	private static final int MAX_LAVA_RETREAT_TICKS = 40;
	private int lavaRetreatTicks;
	private net.minecraft.core.BlockPos railedAt;

	/** Richtung (x, z, normiert) zur nächsten Lava bis 2 Blöcke waagrecht und 1 darunter, sonst {@code null}. */
	private static double[] lavaNear(ServerPlayer bot) {
		net.minecraft.core.BlockPos feet = bot.blockPosition();
		double bestX = 0;
		double bestZ = 0;
		double best = Double.MAX_VALUE;
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				for (int dy = -1; dy <= 0; dy++) {
					net.minecraft.core.BlockPos pos = feet.offset(dx, dy, dz);
					if (bot.level().getFluidState(pos).is(net.minecraft.tags.FluidTags.LAVA)) {
						double d = dx * dx + dz * dz;
						if (d < best && d > 0) {
							best = d;
							bestX = dx;
							bestZ = dz;
						}
					}
				}
			}
		}
		if (best == Double.MAX_VALUE) {
			return null;
		}
		double len = Math.sqrt(best);
		return new double[] {bestX / len, bestZ / len};
	}

	/** Richtung (x, z, normiert) zur nächsten Stelle bis 2 Blöcke, unter der 4 Blöcke lang kein Boden kommt, sonst {@code null}. */
	private static double[] dropNear(ServerPlayer bot) {
		net.minecraft.core.BlockPos feet = bot.blockPosition();
		var level = bot.level();
		double bestX = 0;
		double bestZ = 0;
		double best = Double.MAX_VALUE;
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				double d = dx * dx + dz * dz;
				if (d == 0 || d >= best) {
					continue;
				}
				net.minecraft.core.BlockPos side = feet.offset(dx, 0, dz);
				if (!level.getBlockState(side).getCollisionShape(level, side).isEmpty()) {
					continue;
				}
				boolean ground = false;
				for (int down = 1; down <= 4 && !ground; down++) {
					net.minecraft.core.BlockPos pos = feet.offset(dx, -down, dz);
					ground = !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
				}
				if (!ground) {
					best = d;
					bestX = dx;
					bestZ = dz;
				}
			}
		}
		if (best == Double.MAX_VALUE) {
			return null;
		}
		double len = Math.sqrt(best);
		return new double[] {bestX / len, bestZ / len};
	}

	/** Ob bis 2 Blöcke neben dem Bot eine Stelle ist, unter der 4 Blöcke lang kein Boden kommt. */
	public static boolean nearDrop(ServerPlayer bot) {
		return nearDrop(bot.level(), bot.blockPosition());
	}

	/** Wie {@link #nearDrop(ServerPlayer)}, für eine beliebige Fußposition. */
	public static boolean nearDrop(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos feet) {
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				if (dx == 0 && dz == 0) {
					continue;
				}
				boolean ground = false;
				for (int down = 1; down <= 4 && !ground; down++) {
					net.minecraft.core.BlockPos pos = feet.offset(dx, -down, dz);
					ground = !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
				}
				net.minecraft.core.BlockPos side = feet.offset(dx, 0, dz);
				if (!ground && level.getBlockState(side).getCollisionShape(level, side).isEmpty()) {
					return true;
				}
			}
		}
		return false;
	}

	private void strikeWhenReady(ServerPlayer bot, float charge, boolean tryCrit) {
		if (!bot.onGround()) {
			airTicks++;
		} else {
			airTicks = 0;
		}
		boolean falling = !bot.onGround() && bot.fallDistance > 0 && !bot.isInWater() && !bot.onClimbable();
		if (charge < 0.98f) {
			if (tryCrit && charge >= JUMP_AT_CHARGE && bot.onGround() && !jumped && !bot.isInWater()) {
				bot.setJumping(true);
				jumped = true;
			} else {
				Combat.raiseShield(bot);
			}
			return;
		}
		// Voller Schlag: Wer gerade steigt, wartet kurz auf den Fall, aber nicht ewig (niedrige Decke).
		if (tryCrit && jumped && !bot.onGround() && !falling && airTicks < MAX_AIR_TICKS) {
			return;
		}
		Combat.lowerShield(bot);
		if (target instanceof Creeper) {
			// Im Sprint treffen: der zusätzliche Rückstoß bringt den Creeper aus seiner Zündreichweite.
			bot.setSprinting(true);
			backOffTicks = 8;
		}
		// Im Sprint gibt es keinen Schwungschlag. Drachenlauf 13: Der Schwung gegen einen Magmawürfel traf einen
		// Zombie-Piglin daneben, die ganze Gruppe griff an und erschlug ihn.
		if (Combat.peacefulPiglinNear(target, bot)) {
			bot.setSprinting(true);
		}
		bot.attack(target);
		bot.swing(InteractionHand.MAIN_HAND, true);
		bot.resetAttackStrengthTicker();
		bot.setSprinting(false);
		hits++;
		if (falling) {
			crits++;
		}
		jumped = false;
	}

	private State approach(ServerPlayer bot, WorldCells cells, double distance, double towardX, double towardZ) {
		boolean direct = pathfinderTicks <= 0 && distance < DIRECT_DISTANCE
				&& Math.abs(target.getY() - bot.getY()) < 1.6 && bot.hasLineOfSight(target);
		if (pathfinderTicks > 0) {
			pathfinderTicks--;
		}
		if (direct) {
			double x = towardX;
			double z = towardZ;
			if (target instanceof RangedAttackMob && distance > 3.5) {
				// Zickzack: alle halbe Sekunde die Seite wechseln, damit Pfeile vorbeigehen.
				double angle = (ticks / ZIGZAG_TICKS) % 2 == 0 ? ZIGZAG_ANGLE : -ZIGZAG_ANGLE;
				x = towardX * Math.cos(angle) - towardZ * Math.sin(angle);
				z = towardX * Math.sin(angle) + towardZ * Math.cos(angle);
			}
			double[] step = Combat.safeDirection(bot, cells, x, z);
			if (step != null && step[0] * towardX + step[1] * towardZ > 0.2) {
				follower = null;
				Combat.face(bot, bot.getX() + step[0], bot.getEyeY(), bot.getZ() + step[1]);
				Combat.move(bot, step[0], step[1], true);
				if (ticks % 5 == 0) {
					double moved = Math.abs(bot.getX() - lastX) + Math.abs(bot.getZ() - lastZ);
					stuckTicks = moved < 0.15 ? stuckTicks + 5 : 0;
					lastX = bot.getX();
					lastZ = bot.getZ();
					if (stuckTicks >= STUCK_TICKS) {
						stuckTicks = 0;
						pathfinderTicks = PATHFINDER_TICKS_AFTER_STUCK;
					}
				}
				return State.RUNNING;
			}
		}

		bot.setSprinting(false);
		if (waitTicks > 0) {
			waitTicks--;
			PathFollower.release(bot);
			return State.RUNNING;
		}
		Pos targetPos = new Pos(target.getBlockX(), Mth.floor(target.getY() + 0.2), target.getBlockZ());
		if (follower == null) {
			// Bauen nur mit Bausteinen im Inventar; sonst scheitert jeder geplante Weg mit no_blocks (Nachstellung
			// Perlen-Lauf 14: 10 von 13 Kämpfen im Warped-Wald).
			Moves.Options options = moveOptions.allowPlace() && Inv.countScaffold(bot) == 0
					? new Moves.Options(moveOptions.allowDig(), false, moveOptions.digFortress(), moveOptions.maxDrop(), false) : moveOptions;
			follower = new PathFollower(Goal.near(targetPos, 2.0), options);
		} else if (ticks % RETARGET_INTERVAL_TICKS == 0 && follower.goal().anchor().distanceTo(targetPos) > 2.0) {
			follower.retarget(Goal.near(targetPos, 2.0));
		}
		switch (follower.tick(bot)) {
			case RUNNING -> {
			}
			case ARRIVED -> follower = null;
			case FAILED -> {
				failure = follower.failure();
				hint = follower.hint();
				follower = null;
				if (++pathFailures > MAX_PATH_FAILURES) {
					return State.FAILED;
				}
				waitTicks = PATH_RETRY_TICKS;
			}
		}
		return State.RUNNING;
	}

	/** Tasten loslassen, Schild senken (außer er soll oben bleiben), angefangenen Weg beenden. */
	public void release(ServerPlayer bot) {
		if (follower != null) {
			follower.abort(bot);
		}
		Combat.halt(bot);
		if (!Combat.shieldWanted()) {
			Combat.lowerShield(bot);
		}
	}
}
