package de.hannes.smartcraft.action;

import com.google.gson.JsonObject;
import de.hannes.smartcraft.skill.BlockBreaker;
import de.hannes.smartcraft.skill.BlockPlacer;
import de.hannes.smartcraft.skill.Inv;
import de.hannes.smartcraft.skill.PathFollower;
import de.hannes.smartcraft.nav.Goal;
import de.hannes.smartcraft.nav.Moves;
import de.hannes.smartcraft.nav.Pos;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * {@code seal_water}: baut das Wasser um den Bot mit Blöcken zu, Quellen zuerst, die nächsten zuerst.
 * Danach ist es trocken: Abbauen geht wieder in normalem Tempo, und der Bot kommt aus einer Wassertasche
 * heraus. Das eigene Feld (Füße, Kopf) lässt er frei. Schema: {@code schemas/commands/seal_water.schema.json}.
 */
public final class SealWaterAction extends Action {
	/** Fließendes Wasser braucht ein paar Ticks, bis es abläuft, wenn seine Quelle zu ist. */
	private static final int SETTLE_TICKS = 20;

	private final int radius;
	/** Was zugebaut wird: Wasser ({@code seal_water}) oder Lava ({@code seal_lava}). */
	private final TagKey<Fluid> fluidTag;
	private int placed;
	private int quietTicks;
	private String lastProblem;

	public SealWaterAction(int radius) {
		this(radius, FluidTags.WATER);
	}

	public SealWaterAction(int radius, TagKey<Fluid> fluidTag) {
		this.fluidTag = fluidTag;
		this.radius = radius;
	}

	@Override
	public int timeoutTicks() {
		return 1600;
	}

	/** Lava zubauen heißt, neben Lava zu stehen: kein Grund abzubrechen. */
	@Override
	public boolean cancelOnDanger() {
		return fluidTag != FluidTags.LAVA;
	}

	/** Ertrinken unterbricht nicht: Zubauen ist gerade der Weg hinaus. */
	@Override
	public boolean cancelOnDamage() {
		return false;
	}

	@Override
	public Outcome tick(ServerPlayer bot) {
		// Kopf unter Wasser und die Luft wird knapp: aufhören, damit der Luft-Reflex ihn hochbringt. Festungslauf 36
		// verbaute unter dem Ozean 131 Blöcke, bis er ertrank.
		if (fluidTag == FluidTags.WATER && bot.isEyeInFluid(FluidTags.WATER) && bot.getAirSupply() < bot.getMaxAirSupply() / 3) {
			PathFollower.release(bot);
			JsonObject data = progress(bot);
			data.addProperty("hint", "out of air under water; swim up (goto the surface) before sealing again");
			return Outcome.failed("low_air", data);
		}
		if (breaker != null || walker != null) {
			Outcome stepping = stepOut(bot);
			return stepping == CONTINUE ? null : stepping;
		}
		PathFollower.release(bot);
		List<BlockPos> water = waterAround(bot);
		BlockPos next = water.stream().filter(pos -> canSeal(bot, pos)).findFirst().orElse(null);
		if (next == null) {
			// Nichts mehr zu setzen: kurz warten, bis Fließwasser abgelaufen ist, dann Bilanz.
			if (++quietTicks < SETTLE_TICKS && placed > 0) {
				return null;
			}
			// Steht er selbst noch in einer Quelle: einen Schritt ins Trockene graben und hinter sich zubauen.
			if (placed > 0 && fluidTag == FluidTags.WATER && ownCellWet(bot) && stepsOut < MAX_STEP_OUTS) {
				Outcome stepping = stepOut(bot);
				if (stepping != CONTINUE) {
					return stepping;
				}
				return null;
			}
			JsonObject data = progress(bot);
			if (placed == 0) {
				if (water.isEmpty()) {
					return Outcome.failed("no_water", data);
				}
				if (Inv.findScaffold(bot) < 0) {
					return Outcome.failed("no_blocks", data);
				}
				data.addProperty("hint", lastProblem != null ? lastProblem : "water out of reach or without a neighbour to place against");
				return Outcome.failed("nothing_sealed", data);
			}
			return Outcome.ok(data);
		}
		quietTicks = 0;
		int slot = Inv.findScaffold(bot);
		if (slot < 0) {
			return placed > 0 ? Outcome.ok(progress(bot)) : Outcome.failed("no_blocks", progress(bot));
		}
		String problem = BlockPlacer.place(bot, slot, next);
		if (problem == null) {
			placed++;
		} else {
			lastProblem = problem + " at " + next.toShortString();
			skipped.add(next);
		}
		return null;
	}

	private static final Outcome CONTINUE = Outcome.ok(new JsonObject());
	private static final int MAX_STEP_OUTS = 2;
	private static final int STEP_TICKS = 100;
	private int stepsOut;
	private Direction stepDirection;
	private BlockPos oldFeet;
	private BlockBreaker breaker;
	private PathFollower walker;
	private int walkTicks;

	private boolean ownCellWet(ServerPlayer bot) {
		BlockPos feet = bot.blockPosition();
		return !bot.level().getFluidState(feet).isEmpty() || !bot.level().getFluidState(feet.above()).isEmpty();
	}

	/**
	 * Ein Schritt zur Seite: Kopf- und Fußblock daneben abbauen, hineingehen, dann die alten Felder
	 * zubauen. Das Wasser, das nachläuft, hat danach keine Quelle mehr und läuft ab.
	 *
	 * @return {@link #CONTINUE}, solange er dabei ist; sonst ein Ergebnis
	 */
	private Outcome stepOut(ServerPlayer bot) {
		ServerLevel level = bot.level();
		if (breaker != null) {
			BlockBreaker.State state = breaker.tick(bot);
			if (state == BlockBreaker.State.RUNNING) {
				return CONTINUE;
			}
			breaker = null;
			if (state == BlockBreaker.State.FAILED) {
				return Outcome.failed("step_out_failed", progress(bot));
			}
		}
		if (stepDirection == null) {
			oldFeet = bot.blockPosition();
			for (Direction direction : Direction.Plane.HORIZONTAL) {
				BlockPos next = oldFeet.relative(direction);
				boolean floor = !level.getBlockState(next.below()).getCollisionShape(level, next.below()).isEmpty();
				if (floor && passable(bot, next) && passable(bot, next.above())) {
					stepDirection = direction;
					break;
				}
			}
			if (stepDirection == null) {
				JsonObject data = progress(bot);
				data.addProperty("hint", "no side to step out to; dig out with goto allow_dig");
				return Outcome.ok(data);
			}
		}
		BlockPos next = oldFeet.relative(stepDirection);
		if (walker == null) {
			for (BlockPos pos : new BlockPos[]{next.above(), next}) {
				if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
					breaker = new BlockBreaker(pos);
					return CONTINUE;
				}
			}
			walker = new PathFollower(Goal.near(new Pos(next.getX(), next.getY(), next.getZ()), 0), Moves.Options.NONE);
			walkTicks = 0;
		}
		if (!bot.blockPosition().equals(next)) {
			if (walker.tick(bot) == PathFollower.State.FAILED || ++walkTicks > STEP_TICKS) {
				return Outcome.failed("step_out_failed", progress(bot));
			}
			return CONTINUE;
		}
		PathFollower.release(bot);
		// Hinter sich zubauen; der Rest der Schleife setzt im nächsten Tick, was noch nass ist.
		stepsOut++;
		stepDirection = null;
		walker = null;
		quietTicks = 0;
		skipped.clear();
		return CONTINUE;
	}

	private static boolean passable(ServerPlayer bot, BlockPos pos) {
		ServerLevel level = bot.level();
		if (level.getFluidState(pos).is(FluidTags.LAVA)) {
			return false;
		}
		return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty() || BlockBreaker.precheck(bot, pos) == null;
	}

	/** Felder, an denen das Setzen schon einmal scheiterte; sonst probiert er sie jeden Tick erneut. */
	private final List<BlockPos> skipped = new ArrayList<>();

	private boolean canSeal(ServerPlayer bot, BlockPos pos) {
		return !skipped.contains(pos) && BlockBreaker.inReach(bot, pos) && BlockPlacer.hasSupport(bot.level(), pos);
	}

	/** Wasser im Umkreis ohne das eigene Feld; Quellen zuerst, dann die nächsten. */
	private List<BlockPos> waterAround(ServerPlayer bot) {
		ServerLevel level = bot.level();
		BlockPos feet = bot.blockPosition();
		Vec3 eyes = bot.getEyePosition();
		List<BlockPos> found = new ArrayList<>();
		for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-radius, -radius, -radius), feet.offset(radius, radius + 1, radius))) {
			FluidState fluid = level.getFluidState(pos);
			if (fluid.is(fluidTag) && !pos.equals(feet) && !pos.equals(feet.above())) {
				found.add(pos.immutable());
			}
		}
		found.sort(Comparator.<BlockPos>comparingInt(pos -> level.getFluidState(pos).isSource() ? 0 : 1)
				.thenComparingDouble(pos -> eyes.distanceToSqr(Vec3.atCenterOf(pos))));
		return found;
	}

	@Override
	public void release(ServerPlayer bot) {
		if (breaker != null) {
			breaker.abort(bot);
		}
		PathFollower.release(bot);
	}

	@Override
	public JsonObject progress(ServerPlayer bot) {
		JsonObject data = new JsonObject();
		data.addProperty("blocks_placed", placed);
		data.addProperty("water_left", waterAround(bot).size());
		data.addProperty("in_water", bot.isInWater());
		data.addProperty("blocks_left", Inv.countScaffold(bot));
		data.add("held_item", Inv.heldJson(bot));
		return data;
	}
}
