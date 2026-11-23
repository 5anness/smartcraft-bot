package de.hannes.aiplayer.action;

import com.google.gson.JsonObject;
import de.hannes.aiplayer.perception.GameJson;
import de.hannes.aiplayer.skill.BlockBreaker;
import de.hannes.aiplayer.skill.Inv;
import de.hannes.aiplayer.skill.PathFollower;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.Map;

/**
 * {@code descend}: gräbt senkrecht nach unten bis zur Zielhöhe. Vor jedem Block prüft der Bot, was
 * darunter und daneben liegt, und hält an, bevor er in Lava, Wasser oder einen tiefen Hohlraum fällt.
 * Schema: {@code schemas/commands/descend.schema.json}.
 */
public final class DescendAction extends Action {
	/** Tiefer als so viele Blöcke Luft unter dem nächsten Block gilt als Hohlraum: Fallschaden, Gegner im Dunkeln. */
	private static final int MAX_SAFE_DROP = 3;
	private static final int LAND_WAIT_TICKS = 40;

	private final int toY;
	private Map<String, Integer> before;
	private BlockBreaker breaker;
	private int dug;
	private int landTicks;
	/** Der zuletzt abgebaute Block; weiter geht es erst, wenn der Bot darin gelandet ist. */
	private BlockPos lastBroken;

	public DescendAction(int toY) {
		this.toY = toY;
	}

	@Override
	public int timeoutTicks() {
		// Großzügig je Block: Deepslate braucht mit Steinspitzhacke über eine Sekunde.
		return 400 + 80 * Math.max(1, Math.abs(toY) + 320);
	}

	@Override
	public Outcome tick(ServerPlayer bot) {
		ServerLevel level = bot.level();
		if (before == null) {
			before = Inv.counts(bot);
			if (bot.getBlockY() <= toY) {
				JsonObject data = progress(bot);
				data.addProperty("hint", "already at or below y " + toY);
				return Outcome.failed("invalid_args", data);
			}
		}
		PathFollower.release(bot);

		if (breaker != null) {
			switch (breaker.tick(bot)) {
				case RUNNING -> {
					return null;
				}
				case DONE -> {
					lastBroken = breaker.pos();
					breaker = null;
					dug++;
					landTicks = 0;
				}
				case FAILED -> {
					JsonObject data = progress(bot);
					data.addProperty("hint", breaker.failure() + (breaker.hint() != null ? ": " + breaker.hint() : ""));
					breaker = null;
					return Outcome.failed("dig_failed", data);
				}
			}
		}

		// Erst weiter, wenn der Bot im Loch gelandet ist (direkt nach dem Abbau steht er noch einen Tick „auf“ Luft).
		boolean landed = lastBroken == null || bot.getY() <= lastBroken.getY() + 0.1;
		if (!bot.onGround() || !landed) {
			if (++landTicks > LAND_WAIT_TICKS && !bot.isInWater()) {
				return Outcome.failed("not_on_ground", progress(bot));
			}
			// Im Wasser ohne Boden nicht ewig warten: Drachenlauf 38 schwamm 8 Minuten über dem Portalraum, der Luft-Reflex holte
			// ihn alle 9 s hoch, descend wartete weiter.
			if (bot.isInWater() && landTicks > 100) {
				JsonObject data = progress(bot);
				data.addProperty("hint", "you are swimming, descend needs ground: go to the shore (or onto the sea floor with seal_water), "
						+ "then descend there and tunnel/goto with allow_dig to the target underground");
				return Outcome.failed("in_water", data);
			}
			return null;
		}
		BlockPos feet = bot.blockPosition();
		// Am Rand eines Blocks liegt unter der Körpermitte Luft: dann die Spalte des Blocks, auf dem er wirklich steht
		// (Hannes' Lauf 28.10.: auf dem eigenen Turm „no_block“, weil er halb daneben stand).
		if (bot.onGround() && level.getBlockState(feet.below()).getCollisionShape(level, feet.below()).isEmpty()) {
			feet = bot.getOnPos().above();
		}
		if (feet.getY() <= toY) {
			return Outcome.ok(progress(bot));
		}

		BlockPos below = feet.below();
		String danger = fallingAbove(level, feet);
		if (danger == null) {
			danger = dangerBelow(level, below);
		}
		if (danger != null) {
			return Outcome.failed(danger, foundData(bot, level));
		}
		String problem = BlockBreaker.precheck(bot, below);
		if (problem != null) {
			JsonObject data = progress(bot);
			if (problem.equals("wrong_tool")) {
				data.addProperty("hint", "needs " + BlockBreaker.requiredTool(level.getBlockState(below)));
				return Outcome.failed("wrong_tool", data);
			}
			data.addProperty("hint", problem + " at " + below.toShortString());
			return Outcome.failed("dig_failed", data);
		}
		breaker = new BlockBreaker(below);
		return null;
	}

	private BlockPos foundPos;
	private BlockState foundState;

	/**
	 * Prüft den nächsten Block und seine Umgebung.
	 *
	 * @return Fehlergrund oder {@code null}, wenn er gefahrlos abgebaut werden kann
	 */
	private String dangerBelow(ServerLevel level, BlockPos next) {
		// Der Block selbst, die vier daneben (sonst läuft es in den Schacht) und zwei darunter: Lava so früh
		// erkennen, dass der Bot gar nicht erst daneben steht.
		for (BlockPos pos : new BlockPos[]{next, next.below(), next.below(2), next.north(), next.east(), next.south(), next.west()}) {
			FluidState fluid = level.getFluidState(pos);
			if (!fluid.isEmpty()) {
				remember(level, pos);
				return GameJson.blockId(level.getBlockState(pos)).contains("lava") ? "lava_below" : "fluid_below";
			}
		}
		// Hohlraum: unter dem nächsten Block mehr als MAX_SAFE_DROP Felder ohne Boden.
		int air = 0;
		for (int depth = 1; depth <= MAX_SAFE_DROP + 1; depth++) {
			BlockPos pos = next.below(depth);
			BlockState state = level.getBlockState(pos);
			if (!state.getCollisionShape(level, pos).isEmpty()) {
				break;
			}
			if (!level.getFluidState(pos).isEmpty()) {
				remember(level, pos);
				return GameJson.blockId(state).contains("lava") ? "lava_below" : "fluid_below";
			}
			air++;
		}
		if (air > MAX_SAFE_DROP) {
			remember(level, next.below());
			return "cave_below";
		}
		return null;
	}

	/**
	 * Kies oder Sand über dem Kopf, ohne festen Block dazwischen: Hängt er (von der Weltgenerierung) frei,
	 * kann er beim Graben nachrutschen und den Bot im Schacht begraben.
	 *
	 * @return {@code falling_block_above} oder {@code null}
	 */
	private String fallingAbove(ServerLevel level, BlockPos feet) {
		for (int up = 2; up <= MAX_SAFE_DROP + 1; up++) {
			BlockPos pos = feet.above(up);
			BlockState state = level.getBlockState(pos);
			if (state.getBlock() instanceof FallingBlock) {
				remember(level, pos);
				return "falling_block_above";
			}
			if (!state.getCollisionShape(level, pos).isEmpty()) {
				return null;
			}
		}
		return null;
	}

	private void remember(ServerLevel level, BlockPos pos) {
		foundPos = pos.immutable();
		foundState = level.getBlockState(pos);
	}

	private JsonObject foundData(ServerPlayer bot, ServerLevel level) {
		JsonObject data = progress(bot);
		JsonObject found = new JsonObject();
		found.addProperty("block", GameJson.blockId(foundState));
		found.add("pos", GameJson.pos(foundPos));
		data.add("found", found);
		data.addProperty("hint", foundPos.getY() > bot.getY()
				? "loose sand or gravel above your head; mine it from the side or dig the shaft one block over"
				: "stopped above it; the shaft is safe where you stand");
		return data;
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
		data.addProperty("blocks_dug", dug);
		if (before != null) {
			data.add("gained", Inv.gained(before, bot));
		}
		data.add("held_item", Inv.heldJson(bot));
		return data;
	}
}
