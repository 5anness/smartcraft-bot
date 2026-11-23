package de.hannes.smartcraft.action;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.hannes.smartcraft.nav.Goal;
import de.hannes.smartcraft.nav.Moves;
import de.hannes.smartcraft.nav.Pos;
import de.hannes.smartcraft.perception.GameJson;
import de.hannes.smartcraft.skill.BlockBreaker;
import de.hannes.smartcraft.skill.BlockPlacer;
import de.hannes.smartcraft.skill.Fortress;
import de.hannes.smartcraft.skill.Inv;
import de.hannes.smartcraft.skill.PathFollower;
import net.fabricmc.fabric.api.tag.convention.v2.ConventionalBlockTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code tunnel}: gräbt einen Gang, 1 breit und 2 hoch, geradeaus in eine Himmelsrichtung. Vor jedem
 * Schritt prüft der Bot den nächsten Abschnitt und seine Wände auf Lava und Wasser, überbrückt
 * Löcher im Boden mit einem Block und meldet Erze, an denen er vorbeikommt.
 * Schema: {@code schemas/commands/tunnel.schema.json}.
 */
public final class TunnelAction extends Action {
	private static final int MAX_LISTED_ORES = 20;
	/** So weit um den Gang herum schaut er nach Erz (sichtbar in den Wänden oder knapp dahinter). */
	private static final int ORE_SCAN = 2;
	private static final int STEP_TIMEOUT_TICKS = 100;

	private final Direction direction;
	private final int length;
	private final boolean stopAtOre;

	private Map<String, Integer> before;
	private BlockPos start;
	private BlockBreaker breaker;
	private PathFollower walker;
	private int walkTicks;
	private int steps;
	private int dug;
	private int bridged;
	private final java.util.Set<BlockPos> bridgedFloors = new java.util.HashSet<>();
	private final java.util.ArrayDeque<BlockPos> railQueue = new java.util.ArrayDeque<>();
	private BlockPos railsFor;
	private final Map<BlockPos, String> ores = new LinkedHashMap<>();
	private JsonObject found;
	/** Ob der Gang in einer Festung beginnt: Dann endet er, wo die Festung endet. */
	private boolean inFortressAtStart;

	public TunnelAction(Direction direction, int length, boolean stopAtOre) {
		this.direction = direction;
		this.length = length;
		this.stopAtOre = stopAtOre;
	}

	@Override
	public int timeoutTicks() {
		// Je Block großzügig: zwei Blöcke abbauen (Deepslate, Netherrack) und einen Schritt gehen.
		return 400 + 200 * length;
	}

	private int fireCheck;

	@Override
	public Outcome tick(ServerPlayer bot) {
		ServerLevel level = bot.level();
		// Unter Beschuss an der Kante anhalten, Geländer setzen (wie goto). Drachenlauf 50: tunnel baute 80 Blöcke Brücke im
		// Freien, ein Lohen-Feuerball warf ihn 30 Blöcke tief.
		if (before != null && bridged > 0 && level.dimension() == net.minecraft.world.level.Level.NETHER && bot.onGround()
				&& ++fireCheck % 10 == 0 && de.hannes.smartcraft.skill.Fighter.nearDrop(bot)) {
			net.minecraft.world.entity.LivingEntity shooter = GotoAction.shooterInSight(bot);
			if (shooter != null) {
				PathFollower.release(bot);
				JsonObject data = progress(bot);
				data.addProperty("railing_placed", GotoAction.rail(bot));
				data.add("shooter", de.hannes.smartcraft.perception.GameJson.ints(shooter.getBlockX(), shooter.getBlockY(), shooter.getBlockZ()));
				data.addProperty("shooter_type", de.hannes.smartcraft.perception.GameJson.entityType(shooter));
				data.addProperty("hint", "a shooter aims at you while you stand at a drop on your bridge: shoot it from here (railing placed), "
						+ "then tunnel again");
				return Outcome.failed("under_fire", data);
			}
		}
		if (before == null) {
			before = Inv.counts(bot);
			start = bot.blockPosition();
			if (!bot.onGround()) {
				return Outcome.failed("not_on_ground", progress(bot));
			}
			scanOres(level, start);
			inFortressAtStart = Fortress.contains(level, start);
		}

		if (breaker != null) {
			switch (breaker.tick(bot)) {
				case RUNNING -> {
					return null;
				}
				case DONE -> {
					breaker = null;
					dug++;
				}
				case FAILED -> {
					JsonObject data = progress(bot);
					data.addProperty("hint", breaker.failure() + (breaker.hint() != null ? ": " + breaker.hint() : ""));
					breaker = null;
					return Outcome.failed("dig_failed", data);
				}
			}
		}

		BlockPos feet = bot.blockPosition();
		BlockPos next = feet.relative(direction);
		BlockPos head = next.above();

		if (walker != null) {
			PathFollower.State state = walker.tick(bot);
			if (state == PathFollower.State.RUNNING && ++walkTicks < STEP_TIMEOUT_TICKS) {
				return null;
			}
			walker = null;
			PathFollower.release(bot);
			if (state != PathFollower.State.ARRIVED) {
				JsonObject data = progress(bot);
				data.addProperty("hint", "could not step forward");
				return Outcome.failed("stuck", data);
			}
			steps++;
			boolean inFortress = Fortress.contains(level, bot.blockPosition());
			if (inFortress != inFortressAtStart) {
				JsonObject data = progress(bot);
				data.addProperty("stopped_because", inFortress ? "fortress_reached" : "left_fortress");
				return Outcome.ok(data);
			}
			int known = ores.size();
			scanOres(level, bot.blockPosition());
			// Nur neu entdecktes Erz hält an; was schon am Start zu sehen war, kennt die KI.
			if (stopAtOre && ores.size() > known) {
				JsonObject data = progress(bot);
				data.addProperty("stopped_because", "ore_found");
				return Outcome.ok(data);
			}
			return null;
		}

		if (steps >= length) {
			return Outcome.ok(progress(bot));
		}

		String danger = fluidAround(level, next, head);
		if (danger != null) {
			JsonObject data = progress(bot);
			data.add("found", found);
			data.addProperty("hint", "stopped before it; the tunnel behind you is safe");
			return Outcome.failed(danger, data);
		}

		// Erst der obere Block: Fällt Kies nach, landet er nicht auf dem Bot.
		for (BlockPos pos : new BlockPos[]{head, next}) {
			BlockState state = level.getBlockState(pos);
			if (!state.getCollisionShape(level, pos).isEmpty()) {
				if (Fortress.isFortressBlock(level, state)) {
					// Eine Festung gräbt er nicht an: Draußen ist er am Ziel, drinnen wäre es eine Wand.
					JsonObject data = progress(bot);
					data.addProperty("stopped_because", inFortressAtStart ? "fortress_wall" : "fortress_reached");
					data.add("fortress_block", GameJson.pos(pos));
					return Outcome.ok(data);
				}
				String problem = BlockBreaker.precheck(bot, pos);
				if (problem != null) {
					JsonObject data = progress(bot);
					if (problem.equals("wrong_tool")) {
						data.addProperty("hint", "needs " + BlockBreaker.requiredTool(state));
						return Outcome.failed("wrong_tool", data);
					}
					data.addProperty("hint", problem + " at " + pos.toShortString());
					return Outcome.failed(problem.equals("unbreakable") ? "unbreakable" : "dig_failed", data);
				}
				// Die letzte Spitzhacke nicht im Tunnel verbrauchen. Nether-Lauf 13: Zwei Tunnel (573 Blöcke) brachen die
				// Diamantspitzhacke, danach kam er weder durch den Fels noch zurück.
				if (pickaxeLeft(bot) < MIN_PICKAXE_LEFT) {
					JsonObject data = progress(bot);
					data.addProperty("hint", "pickaxe almost broken (" + pickaxeLeft(bot) + " uses left); keep it for the portal and emergencies");
					return Outcome.failed("tool_worn", data);
				}
				breaker = new BlockBreaker(pos);
				return null;
			}
		}

		// Loch im Boden: mit einem Block überbrücken, sonst anhalten.
		BlockPos floor = next.below();
		if (level.getBlockState(floor).getCollisionShape(level, floor).isEmpty()) {
			// Reserve wie in der Wegführung (Drachenlauf 29: tunnel verbaute die letzten 32 Blöcke als Boden in der Luft).
			int slot = Inv.countScaffold(bot) <= de.hannes.smartcraft.skill.PathFollower.BLOCK_RESERVE ? -1 : Inv.findScaffold(bot);
			String problem = slot < 0 ? "no_blocks" : BlockPlacer.place(bot, slot, floor);
			if (problem != null) {
				JsonObject data = progress(bot);
				data.addProperty("hint", "hole in the floor at " + floor.toShortString() + " (" + problem + ")");
				return Outcome.failed("no_floor", data);
			}
			bridged++;
			bridgedFloors.add(floor.immutable());
			return null;
		}
		// Geländer auf Brücken über einem Abgrund. Drachenlauf 11: Ein Feuerball stieß den Bot von der 1 Block breiten
		// Brücke, er fiel 22 Blöcke tief.
		if (!railQueue.isEmpty()) {
			BlockPos rail = railQueue.poll();
			int slot = Inv.findScaffold(bot);
			if (slot >= 0 && Inv.countScaffold(bot) > de.hannes.smartcraft.skill.PathFollower.BLOCK_RESERVE
					&& BlockPlacer.place(bot, slot, rail) == null) {
				bridged++;
			}
			return null;
		}
		if (railsFor == null || !railsFor.equals(next)) {
			railsFor = next;
			if (bridgedFloor(floor)) {
				PathFollower.queueRails(bot, next, railQueue, java.util.List.of());
				if (!railQueue.isEmpty()) {
					return null;
				}
			}
		}
		walker = new PathFollower(Goal.near(new Pos(next.getX(), next.getY(), next.getZ()), 0), Moves.Options.NONE);
		walkTicks = 0;
		return null;
	}

	/**
	 * Lava oder Wasser im nächsten Abschnitt, in seinen Wänden, darüber, darunter oder einen Block weiter:
	 * Abbauen würde es in den Gang laufen lassen.
	 *
	 * @return {@code lava_ahead}, {@code fluid_ahead} oder {@code null}
	 */
	private static final int MIN_PICKAXE_LEFT = 50;

	/** Haltbarkeit der besten Spitzhacke im Inventar (Summe nicht, die beste zählt); 0 ohne Spitzhacke. */
	private static int pickaxeLeft(ServerPlayer bot) {
		int best = 0;
		for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
			net.minecraft.world.item.ItemStack stack = bot.getInventory().getItem(i);
			if (stack.is(net.minecraft.tags.ItemTags.PICKAXES)) {
				best = Math.max(best, stack.getMaxDamage() - stack.getDamageValue());
			}
		}
		return best;
	}

	private boolean bridgedFloor(BlockPos floor) {
		return bridgedFloors.contains(floor);
	}

	private String fluidAround(ServerLevel level, BlockPos next, BlockPos head) {
		Direction left = direction.getCounterClockWise();
		Direction right = direction.getClockWise();
		BlockPos[] cells = {next, head, next.below(), head.above(), next.relative(direction), head.relative(direction),
				next.relative(left), next.relative(right), head.relative(left), head.relative(right)};
		for (BlockPos pos : cells) {
			FluidState fluid = level.getFluidState(pos);
			if (!fluid.isEmpty()) {
				found = new JsonObject();
				found.addProperty("block", GameJson.blockId(level.getBlockState(pos)));
				found.add("pos", GameJson.pos(pos));
				return GameJson.blockId(level.getBlockState(pos)).contains("lava") ? "lava_ahead" : "fluid_ahead";
			}
		}
		return null;
	}

	private void scanOres(ServerLevel level, BlockPos feet) {
		for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-ORE_SCAN, -ORE_SCAN, -ORE_SCAN),
				feet.offset(ORE_SCAN, ORE_SCAN + 1, ORE_SCAN))) {
			BlockState state = level.getBlockState(pos);
			if ((state.is(ConventionalBlockTags.ORES) || state.is(Blocks.ANCIENT_DEBRIS)) && ores.size() < MAX_LISTED_ORES) {
				ores.putIfAbsent(pos.immutable(), GameJson.blockId(state));
			}
		}
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
		data.addProperty("direction", direction.getName());
		data.addProperty("length_done", steps);
		data.addProperty("blocks_dug", dug);
		if (bridged > 0) {
			data.addProperty("blocks_placed", bridged);
		}
		JsonArray oreList = new JsonArray();
		ores.forEach((pos, block) -> {
			JsonObject ore = new JsonObject();
			ore.addProperty("block", block);
			ore.add("pos", GameJson.pos(pos));
			oreList.add(ore);
		});
		data.add("ores_seen", oreList);
		if (before != null) {
			data.add("gained", Inv.gained(before, bot));
		}
		data.add("held_item", Inv.heldJson(bot));
		return data;
	}
}
