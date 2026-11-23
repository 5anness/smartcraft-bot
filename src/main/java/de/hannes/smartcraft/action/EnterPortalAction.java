package de.hannes.smartcraft.action;

import com.google.gson.JsonObject;
import de.hannes.smartcraft.nav.Goal;
import de.hannes.smartcraft.nav.Moves;
import de.hannes.smartcraft.nav.Pos;
import de.hannes.smartcraft.perception.GameJson;
import de.hannes.smartcraft.skill.Combat;
import de.hannes.smartcraft.skill.PathFollower;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * {@code enter_portal}: geht zum nächsten Nether- oder Endportal und hinein, bis der Bot in der anderen
 * Dimension ist. Ins Endportal steigt man von der Seite (vom Rahmen), deshalb läuft der Bot erst an den
 * Rand und dann auf die Mitte zu. Schema: {@code schemas/commands/enter_portal.schema.json}.
 */
public final class EnterPortalAction extends Action {
	private static final int RADIUS = 16;
	private static final double EDGE_DISTANCE = 2.5;
	/** Ein Netherportal braucht 4 Sekunden Stehen, bevor es den Spieler mitnimmt. */
	private static final int MAX_INSIDE_TICKS = 160;
	/** Ab hier setzt der Mod den letzten Schritt selbst, wenn der Bot noch am Rand steht. */
	private static final int STEP_IN_TICKS = 60;

	private ResourceKey<Level> startDimension;
	private BlockPos portal;
	private PathFollower follower;
	private int stepTicks;
	private double[] center;
	private final boolean force;

	public EnterPortalAction(boolean force) {
		this.force = force;
	}

	/** Im Portalraum liegt Lava unter dem Portal; sie ist erwartet und kein Grund abzubrechen. */
	@Override
	public boolean cancelOnDanger() {
		return false;
	}

	/** Treffer brechen nicht ab: Nether-Lauf 38 kam neben dem Spawner nie durchs Portal, jede Lohe stoppte ihn. */
	@Override
	public boolean cancelOnDamage() {
		return false;
	}

	@Override
	public int timeoutTicks() {
		return 1200;
	}

	@Override
	public Outcome tick(ServerPlayer bot) {
		if (startDimension == null) {
			startDimension = bot.level().dimension();
			portal = nearestPortal(bot);
			if (portal == null) {
				return Outcome.failed("no_portal", progress(bot));
			}
			// In den Nether nur mit Diamant und einem Goldteil; angezogen wird hier selbst (Wunsch von Hannes).
			if (startDimension == Level.OVERWORLD && bot.level().getBlockState(portal).is(Blocks.NETHER_PORTAL)) {
				com.google.gson.JsonArray missing = de.hannes.smartcraft.skill.NetherKit.missingAll(bot);
				if (missing.isEmpty()) {
					de.hannes.smartcraft.skill.NetherKit.equip(bot);
				} else if (!force) {
					JsonObject data = progress(bot);
					data.add("missing", missing);
					data.addProperty("hint", "gather everything in missing first (piglins attack without a golden piece), or force: true in an emergency");
					return Outcome.failed("not_prepared", data);
				}
			}
			// Ins End nur mit allem für den Drachen (Betten, Pfeile, Bausteine): Drachenlauf 74 ging mit 6 statt 12 Betten hinein,
			// der Drache fiel auf 57 und heilte an den stehenden Kristallen zurück auf 200. Hinaus kommt man erst nach dem Sieg.
			if (startDimension == Level.OVERWORLD && bot.level().getBlockState(portal).is(Blocks.END_PORTAL) && !force) {
				com.google.gson.JsonArray missing = de.hannes.smartcraft.skill.NetherKit.endMissing(bot);
				if (!missing.isEmpty()) {
					JsonObject data = progress(bot);
					data.add("missing", missing);
					data.addProperty("hint", "there is no way back from the End before the dragon dies: gather everything in missing "
							+ "first (beds above all), or force: true");
					return Outcome.failed("not_prepared", data);
				}
			}
		}
		if (bot.level().dimension() != startDimension) {
			PathFollower.release(bot);
			return Outcome.ok(progress(bot));
		}
		if (stepTicks == 0) {
			Goal goal = Goal.near(new Pos(portal.getX(), portal.getY(), portal.getZ()), EDGE_DISTANCE);
			if (goal.distance(bot.getX(), bot.getY(), bot.getZ()) > 0) {
				if (follower == null) {
					follower = new PathFollower(goal, Moves.Options.NONE);
				}
				PathFollower.State state = follower.tick(bot);
				if (state == PathFollower.State.FAILED) {
					JsonObject data = progress(bot);
					data.addProperty("hint", follower.hint());
					return Outcome.failed("out_of_reach", data);
				}
				// Die Wegfindung rechnet mit Feldmitten; meldet sie „angekommen“, obwohl der genaue Abstand
				// knapp darüber liegt, geht es trotzdem zu Fuß weiter (sonst wartet der Bot ewig).
				if (state != PathFollower.State.ARRIVED) {
					return null;
				}
			}
			PathFollower.release(bot);
		}
		// Letzte Schritte ohne Wegfindung: auf die Mitte der ganzen Portalfläche zu (nicht auf die nächste Kachel: von
		// der Ecke zwischen zwei Endportalrahmen aus ging es nicht weiter, Festungsläufe 15 und 18), über die Rahmen
		// springen, bis die Dimension wechselt.
		if (++stepTicks > MAX_INSIDE_TICKS) {
			return Outcome.failed("not_teleported", progress(bot));
		}
		if (center == null) {
			center = portalCenter(bot);
		}
		double dx = center[0] - bot.getX();
		double dz = center[1] - bot.getZ();
		double distance = dx * dx + dz * dz;
		// Rückfall: Nach 3 Sekunden direkt am Rand der Portalfläche den letzten Schritt setzen. Festungslauf 19 stand
		// trotz Anlauf und Sprung in der Ecke zwischen zwei Rahmen (Stufen, Silberfischchen), im Servertest ging es.
		if (stepTicks == STEP_IN_TICKS && distance < 9.0) {
			bot.teleportTo(center[0], portal.getY() + 0.1, center[1]);
			return null;
		}
		if (distance > 0.04) {
			Combat.move(bot, dx, dz, false);
			if (distance < 6.25 && bot.onGround()) {
				bot.setJumping(true);
			}
		} else {
			PathFollower.release(bot);
		}
		return null;
	}

	/** Mitte (x, z) aller Portalblöcke auf der Höhe von {@link #portal} bis 4 Blöcke darum. */
	private double[] portalCenter(ServerPlayer bot) {
		double x = 0;
		double z = 0;
		int n = 0;
		for (BlockPos pos : BlockPos.betweenClosed(portal.offset(-4, 0, -4), portal.offset(4, 0, 4))) {
			if (isPortal(bot.level().getBlockState(pos))) {
				x += pos.getX() + 0.5;
				z += pos.getZ() + 0.5;
				n++;
			}
		}
		return new double[] {x / n, z / n};
	}

	private static boolean isPortal(BlockState state) {
		return state.is(Blocks.END_PORTAL) || state.is(Blocks.NETHER_PORTAL);
	}

	private static BlockPos nearestPortal(ServerPlayer bot) {
		BlockPos feet = bot.blockPosition();
		BlockPos best = null;
		for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-RADIUS, -6, -RADIUS), feet.offset(RADIUS, 6, RADIUS))) {
			if (isPortal(bot.level().getBlockState(pos)) && (best == null || pos.distSqr(feet) < best.distSqr(feet))) {
				best = pos.immutable();
			}
		}
		return best;
	}

	@Override
	public void release(ServerPlayer bot) {
		PathFollower.release(bot);
	}

	@Override
	public JsonObject progress(ServerPlayer bot) {
		JsonObject data = new JsonObject();
		data.addProperty("dim", bot.level().dimension().identifier().toString());
		if (portal != null) {
			data.add("portal", GameJson.pos(portal));
		}
		return data;
	}
}
