package de.hannes.aiplayer.action;

import com.google.gson.JsonObject;
import de.hannes.aiplayer.nav.Goal;
import de.hannes.aiplayer.nav.Moves;
import de.hannes.aiplayer.nav.Pos;
import de.hannes.aiplayer.perception.GameJson;
import de.hannes.aiplayer.skill.BlockBreaker;
import de.hannes.aiplayer.skill.Inv;
import de.hannes.aiplayer.skill.ItemGatherer;
import de.hannes.aiplayer.skill.PathFollower;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;

/**
 * {@code mine}: baut einen Block mit dem besten vorhandenen Werkzeug ab, läuft vorher in
 * Reichweite und hebt danach auf, was herausfällt. Schema: {@code schemas/commands/mine.schema.json}.
 */
public final class MineAction extends Action {
	private static final double APPROACH_REACH = 4.0;
	private static final double GATHER_RADIUS = 3.5;
	private static final int GATHER_TICKS = 80;

	private enum Phase {
		CHECK, APPROACH, BREAK, GATHER
	}

	private final BlockPos pos;
	private Phase phase = Phase.CHECK;
	private Map<String, Integer> before;
	private String blockId;
	private PathFollower follower;
	private BlockBreaker breaker;
	private ItemGatherer gatherer;
	private boolean broken;
	/** Wie viele Blöcke in der Sichtlinie schon weggeräumt wurden (höchstens {@link #MAX_CLEAR}). */
	private int cleared;
	private boolean clearing;
	private static final int MAX_CLEAR = 4;

	public MineAction(BlockPos pos) {
		this(pos, false);
	}

	/** @param allowLavaBelow Lava direkt unter dem Block ist kein Hindernis (make_obsidian: Abdeckung und Obsidian über Lava) */
	/** Zum Freilegen zugedeckter Lava: Lava auch neben dem Block erlauben (siehe BlockBreaker#allowLavaAround). */
	public MineAction uncoverLava() {
		lavaAround = true;
		return this;
	}

	private boolean lavaAround;

	public MineAction(BlockPos pos, boolean allowLavaBelow) {
		this.pos = pos;
		this.allowLavaBelow = allowLavaBelow;
	}

	private final boolean allowLavaBelow;

	@Override
	public int timeoutTicks() {
		return 2400;
	}

	@Override
	public Outcome tick(ServerPlayer bot) {
		switch (phase) {
			case CHECK -> {
				before = Inv.counts(bot);
				blockId = GameJson.blockId(bot.level().getBlockState(pos));
				String problem = BlockBreaker.precheck(bot, pos);
				if (problem != null) {
					JsonObject data = progress(bot);
					if (problem.equals("wrong_tool")) {
						data.addProperty("hint", "needs " + BlockBreaker.requiredTool(bot.level().getBlockState(pos)));
					}
					return Outcome.failed(problem, data);
				}
				phase = Phase.APPROACH;
			}
			case APPROACH -> {
				if (BlockBreaker.inReach(bot, pos) && (bot.onGround() || bot.isInWater() || bot.onClimbable())) {
					PathFollower.release(bot);
					phase = Phase.BREAK;
					return null;
				}
				if (follower == null) {
					follower = new PathFollower(Goal.withinReach(new Pos(pos.getX(), pos.getY(), pos.getZ()), APPROACH_REACH),
							Moves.Options.NONE);
				}
				switch (follower.tick(bot)) {
					case RUNNING -> {
					}
					case ARRIVED -> phase = Phase.BREAK;
					case FAILED -> {
						JsonObject data = progress(bot);
						data.addProperty("hint", follower.hint());
						if (follower.closest() != null) {
							Pos closest = follower.closest();
							data.add("closest", GameJson.ints(closest.x(), closest.y(), closest.z()));
						}
						return Outcome.failed("out_of_reach", data);
					}
				}
			}
			case BREAK -> {
				PathFollower.release(bot);
				if (breaker == null) {
					breaker = new BlockBreaker(pos);
					if (lavaAround) {
						breaker.allowLavaAround();
					} else if (allowLavaBelow) {
						breaker.allowLavaBelow();
					}
				}
				switch (breaker.tick(bot)) {
					case RUNNING -> {
					}
					case DONE -> {
						if (clearing) {
							// Sichtlinie frei geräumt: jetzt der eigentliche Block.
							clearing = false;
							breaker = null;
							return null;
						}
						broken = true;
						gatherer = new ItemGatherer(pos, GATHER_RADIUS, GATHER_TICKS);
						phase = Phase.GATHER;
					}
					case FAILED -> {
						// Wie ein Spieler: Was die Sicht versperrt, zuerst abbauen.
						if (!clearing && "no_line_of_sight".equals(breaker.failure()) && cleared < MAX_CLEAR
								&& BlockBreaker.precheck(bot, breaker.blocker()) == null) {
							breaker = new BlockBreaker(breaker.blocker());
							clearing = true;
							cleared++;
							return null;
						}
						JsonObject data = progress(bot);
						if (breaker.hint() != null) {
							data.addProperty("hint", breaker.hint());
						}
						return Outcome.failed(breaker.failure(), data);
					}
				}
			}
			case GATHER -> {
				if (gatherer.tick(bot) == ItemGatherer.State.DONE) {
					JsonObject data = progress(bot);
					// Abgebaut, aber die Beute liegt noch da: das Ziel „Item haben“ ist verfehlt.
					return gatherer.inventoryFull() ? Outcome.failed("inventory_full", data) : Outcome.ok(data);
				}
			}
		}
		return null;
	}

	@Override
	public void release(ServerPlayer bot) {
		if (breaker != null) {
			breaker.abort(bot);
		}
		if (follower != null) {
			follower.abort(bot);
		}
		PathFollower.release(bot);
	}

	@Override
	public JsonObject progress(ServerPlayer bot) {
		JsonObject data = new JsonObject();
		data.addProperty("block", blockId);
		data.add("block_pos", GameJson.pos(pos));
		data.addProperty("broken", broken);
		if (before != null) {
			data.add("gained", Inv.gained(before, bot));
		}
		data.add("held_item", Inv.heldJson(bot));
		return data;
	}
}
