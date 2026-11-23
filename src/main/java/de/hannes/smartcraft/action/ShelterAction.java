package de.hannes.smartcraft.action;

import com.google.gson.JsonObject;
import de.hannes.smartcraft.nav.Cell;
import de.hannes.smartcraft.nav.WorldCells;
import de.hannes.smartcraft.perception.GameJson;
import de.hannes.smartcraft.skill.BlockBreaker;
import de.hannes.smartcraft.skill.BlockPlacer;
import de.hannes.smartcraft.skill.Combat;
import de.hannes.smartcraft.skill.Inv;
import de.hannes.smartcraft.skill.PathFollower;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * {@code shelter}: Notunterkunft für die Nacht. Mit genug Blöcken eine Hütte um den Bot herum
 * (vier Wände zwei hoch, Dach), sonst gräbt er sich drei Blöcke tief ein und verschließt das Loch.
 * Schema: {@code schemas/commands/shelter.schema.json}.
 */
public final class ShelterAction extends Action {
	@Override
	public boolean allowsSelfDefense() {
		return true;
	}

	private enum Phase {
		SETTLE, CENTER, CLEAR, BUILD, DIG, SEAL
	}

	private static final Direction[] SIDES = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
	private static final int BURROW_DEPTH = 3;
	private static final int SETTLE_TICKS = 20;
	private static final int CENTER_TICKS = 30;
	private static final double CENTERED = 0.12;
	private static final int PLACE_INTERVAL_TICKS = 3;
	/** So lange wartet der Bot, wenn ein Wesen im Weg steht oder der Block zum Verschließen noch fällt. */
	private static final int MAX_WAIT_TICKS = 60;
	/** Härter als das (Stein liegt bei 1,5) räumt er für die Hütte nichts aus dem Weg. */
	private static final float MAX_CLEAR_HARDNESS = 2.0f;

	private Phase phase = Phase.SETTLE;
	private int phaseTicks;
	private BlockPos base;
	private String kind;
	private final Deque<BlockPos> toClear = new ArrayDeque<>();
	private final List<BlockPos> toPlace = new ArrayList<>();
	private BlockBreaker breaker;
	private int depth;
	private int waitTicks;
	private int placed;
	private int dug;
	private int needed;
	private int available;
	private String lastProblem;
	/** Warum die Hütte nicht ging, wenn der Bot sich stattdessen eingegraben hat. */
	private String hutFailed;

	@Override
	public int timeoutTicks() {
		return 1200;
	}

	/** Ab diesem Rüstungswert gilt der Bot als voll gerüstet (volle Eisenrüstung = 15). */
	private static final int WELL_ARMORED = 15;
	/** Notfall trotz Ausrüstung: so wenig Leben oder so viele Gegner bis 16 Blöcke. */
	private static final float EMERGENCY_HEALTH = 10;
	private static final int EMERGENCY_ENEMIES = 3;

	private final boolean force;

	public ShelterAction() {
		this(false);
	}

	public ShelterAction(boolean force) {
		this.force = force;
	}

	/**
	 * Nachts in der Oberwelt baut sich ein voll ausgerüsteter Bot nicht ein, außer im Notfall (Wunsch von Hannes,
	 * 28.10.2026): Mit Eisen- oder Diamantrüstung und Schwert ist die Nacht Arbeitszeit, kein Grund zum Warten.
	 * Liefert die Begründung für die Absage, sonst {@code null}.
	 */
	private JsonObject refuseAtNight(ServerPlayer bot) {
		ServerLevel level = bot.level();
		long time = Math.floorMod(level.getOverworldClockTime(), 24000L);
		boolean night = time >= 12542 && time <= 23460;
		if (force || level.dimension() != net.minecraft.world.level.Level.OVERWORLD || !night) {
			return null;
		}
		boolean sword = false;
		for (String id : new String[]{"minecraft:iron_sword", "minecraft:diamond_sword", "minecraft:netherite_sword"}) {
			sword |= de.hannes.smartcraft.skill.Inv.findItem(bot, id) >= 0;
		}
		if (bot.getArmorValue() < WELL_ARMORED || !sword) {
			return null;
		}
		int enemies = Combat.hostiles(bot, 16).size();
		if (bot.getHealth() <= EMERGENCY_HEALTH || enemies >= EMERGENCY_ENEMIES) {
			return null;
		}
		JsonObject data = progress(bot);
		data.addProperty("armor", bot.getArmorValue());
		data.addProperty("health", bot.getHealth());
		data.addProperty("enemies_near", enemies);
		data.addProperty("hint", "fully equipped: keep working through the night and fight what comes (defend, attack); "
				+ "shelter only in an emergency (health <= 10, 3+ enemies) or with force: true");
		return data;
	}

	@Override
	public boolean cancelOnDamage() {
		return false;
	}

	@Override
	public Outcome tick(ServerPlayer bot) {
		ServerLevel level = bot.level();
		phaseTicks++;
		// Feuer im künftigen Innenraum zuerst löschen. KI-Lauf 15.10.: Die Hütte schloss den Bot mit dem Feuer
		// unter seinen Füßen ein, und er verbrannte darin.
		if (Combat.putOutNearestFire(bot, 1.5)) {
			return null;
		}
		switch (phase) {
			case SETTLE -> {
				PathFollower.release(bot);
				if (phaseTicks == 1) {
					JsonObject refused = refuseAtNight(bot);
					if (refused != null) {
						return Outcome.failed("well_equipped", refused);
					}
				}
				// Ohne Spitzhacke kommt er aus Stein nicht wieder heraus: Drachenlauf 42 mauerte sich nach dem Verschleiß beider
				// Spitzhacken nachts ein (mit 12 Augen und Betten), fand drinnen kein Holz und ertrank zuletzt am eigenen Wasser.
				if (phaseTicks == 1 && !hasPickaxe(bot)) {
					JsonObject data = progress(bot);
					data.addProperty("hint", "no pickaxe: you could not dig out of a stone shelter. craft a pickaxe first "
							+ "(wood: logs -> planks -> sticks), or defend/flee instead");
					return Outcome.failed("no_pickaxe", data);
				}
				// Im Wasser eingebaut ertrinkt der Bot: erst an Land.
				if (bot.isInWater() || !level.getFluidState(bot.blockPosition()).isEmpty()
						|| !level.getFluidState(bot.blockPosition().above()).isEmpty()) {
					JsonObject data = progress(bot);
					data.addProperty("hint", "standing in water; goto dry land first, a shelter here would drown you");
					return Outcome.failed("in_water", data);
				}
				if (!bot.onGround()) {
					return phaseTicks > SETTLE_TICKS ? Outcome.failed("not_on_ground", progress(bot)) : null;
				}
				base = bot.blockPosition();
				Outcome problem = plan(bot, level);
				if (problem != null) {
					return problem;
				}
				enter(Phase.CENTER);
			}
			case CENTER -> {
				double dx = base.getX() + 0.5 - bot.getX();
				double dz = base.getZ() + 0.5 - bot.getZ();
				double distance = Math.sqrt(dx * dx + dz * dz);
				if (distance < CENTERED || phaseTicks > CENTER_TICKS) {
					Combat.halt(bot);
					enter(kind.equals("hut") ? Phase.CLEAR : Phase.DIG);
					return null;
				}
				// Langsam auf die Feldmitte: sonst ragt der Bot in ein Nachbarfeld und verstellt die eigene Wand.
				Combat.move(bot, dx / distance, dz / distance, false);
				float scale = (float) Math.min(1.0, distance * 2.0);
				bot.zza *= scale;
				bot.xxa *= scale;
				bot.setJumping(false);
			}
			case CLEAR -> {
				if (breaker == null) {
					if (toClear.isEmpty()) {
						enter(Phase.BUILD);
						return null;
					}
					breaker = new BlockBreaker(toClear.poll());
				}
				switch (breaker.tick(bot)) {
					case RUNNING -> {
					}
					case DONE -> breaker = null;
					case FAILED -> {
						String reason = breaker.failure() + " at " + breaker.pos().toShortString();
						breaker = null;
						if (switchToBurrow(bot, level, reason)) {
							return null;
						}
						JsonObject data = progress(bot);
						data.addProperty("hint", reason);
						return Outcome.failed("no_space", data);
					}
				}
			}
			case BUILD -> {
				if (phaseTicks % PLACE_INTERVAL_TICKS != 0) {
					return null;
				}
				toPlace.removeIf(pos -> isWall(level, pos));
				if (toPlace.isEmpty()) {
					return finish(bot);
				}
				int slot = Inv.findScaffold(bot);
				if (slot < 0) {
					if (switchToBurrow(bot, level, "no blocks left")) {
						return null;
					}
					return Outcome.failed("no_blocks", progress(bot));
				}
				// Die erste Stelle nehmen, die sich gerade setzen lässt: Manche brauchen erst den Nachbarn als Halt.
				boolean obstructed = false;
				for (BlockPos pos : toPlace) {
					String problem = BlockPlacer.place(bot, slot, pos);
					if (problem == null) {
						placed++;
						toPlace.remove(pos);
						waitTicks = 0;
						return null;
					}
					lastProblem = problem + " at " + pos.toShortString();
					obstructed |= problem.equals("obstructed");
				}
				if ((waitTicks += PLACE_INTERVAL_TICKS) > MAX_WAIT_TICKS || !obstructed) {
					// Hütte geht nicht (z. B. am Hang ohne Halt): stattdessen eingraben.
					if (switchToBurrow(bot, level, lastProblem)) {
						return null;
					}
					JsonObject data = progress(bot);
					data.addProperty("hint", lastProblem);
					return Outcome.failed(obstructed ? "obstructed" : "place_failed", data);
				}
			}
			case DIG -> {
				if (breaker == null) {
					// Erst weitergraben, wenn der Bot im Loch gelandet ist.
					if (!bot.onGround() || bot.getY() > base.getY() - depth + 0.1) {
						return null;
					}
					if (depth == BURROW_DEPTH) {
						enter(Phase.SEAL);
						return null;
					}
					breaker = new BlockBreaker(base.below(depth + 1));
				}
				switch (breaker.tick(bot)) {
					case RUNNING -> {
					}
					case DONE -> {
						breaker = null;
						depth++;
						dug++;
					}
					case FAILED -> {
						JsonObject data = progress(bot);
						data.addProperty("hint", breaker.failure() + (breaker.hint() != null ? ": " + breaker.hint() : ""));
						return Outcome.failed("dig_failed", data);
					}
				}
			}
			case SEAL -> {
				BlockPos lid = base.below();
				if (isWall(level, lid)) {
					return finish(bot);
				}
				int slot = Inv.findScaffold(bot);
				String problem = slot < 0 ? "no_blocks" : BlockPlacer.place(bot, slot, lid);
				if (problem == null) {
					placed++;
					return finish(bot);
				}
				// Der Block aus dem Aushub fällt vielleicht noch ins Loch.
				if (++waitTicks > MAX_WAIT_TICKS) {
					JsonObject data = progress(bot);
					data.addProperty("hint", "in a hole " + BURROW_DEPTH + " deep but could not close it: " + problem);
					return Outcome.failed(problem.equals("no_blocks") ? "no_blocks" : "place_failed", data);
				}
			}
		}
		return null;
	}

	/** Entscheidet zwischen Hütte und Eingraben und legt fest, was zu tun ist. */
	private Outcome plan(ServerPlayer bot, ServerLevel level) {
		List<BlockPos> cells = new ArrayList<>();
		for (Direction side : SIDES) {
			BlockPos wall = base.relative(side);
			// Am Hang hängt das untere Wandfeld in der Luft: erst ein Stützblock darunter.
			if (!isWall(level, wall) && !BlockPlacer.hasSupport(level, wall)) {
				cells.add(wall.below());
			}
			cells.add(wall);
		}
		for (Direction side : SIDES) {
			cells.add(base.relative(side).above());
		}
		BlockPos roof = base.above(2);
		if (!isWall(level, roof)) {
			if (!BlockPlacer.hasSupport(level, roof)) {
				// Das Dach braucht einen Nachbarn auf seiner Höhe: eine Wand wird drei hoch.
				cells.add(base.north().above(2));
			}
			cells.add(roof);
		}

		String blocked = null;
		for (BlockPos pos : cells) {
			BlockState state = level.getBlockState(pos);
			if (isWall(level, pos)) {
				continue;
			}
			if (!state.canBeReplaced()) {
				// Blume, Fackel, Zaun: erst wegräumen. Truhen und Hartes bleiben stehen.
				float hardness = state.getDestroySpeed(level, pos);
				if (state.hasBlockEntity() || hardness < 0 || hardness > MAX_CLEAR_HARDNESS) {
					blocked = GameJson.blockId(state) + " at " + pos.toShortString();
				}
				toClear.add(pos);
			}
			toPlace.add(pos);
		}
		needed = toPlace.size();
		available = Inv.countScaffold(bot);
		if (blocked == null && available >= needed) {
			kind = "hut";
			return null;
		}

		String burrowProblem = burrowProblem(bot, level);
		if (burrowProblem == null) {
			kind = "burrow";
			toClear.clear();
			toPlace.clear();
			return null;
		}
		JsonObject data = progress(bot);
		if (blocked != null && available >= needed) {
			data.addProperty("hint", blocked + " is in the way, and digging in is not possible: " + burrowProblem);
			return Outcome.failed("no_space", data);
		}
		data.addProperty("hint", "hut needs " + needed + " blocks (dirt, cobblestone, planks), have " + available
				+ "; digging in is not possible: " + burrowProblem);
		return Outcome.failed(burrowProblem.startsWith("wrong_tool") ? "no_blocks" : "unsafe_ground", data);
	}

	/** Wechselt nach einer gescheiterten Hütte aufs Eingraben, wenn das hier geht. */
	private boolean switchToBurrow(ServerPlayer bot, ServerLevel level, String reason) {
		if (!"hut".equals(kind) || !bot.onGround() || burrowProblem(bot, level) != null) {
			return false;
		}
		kind = "burrow";
		hutFailed = reason;
		toClear.clear();
		toPlace.clear();
		enter(Phase.DIG);
		return true;
	}

	/** @return warum Eingraben hier nicht geht, oder {@code null} */
	private String burrowProblem(ServerPlayer bot, ServerLevel level) {
		WorldCells cells = new WorldCells(level);
		for (int down = 1; down <= BURROW_DEPTH; down++) {
			BlockPos pos = base.below(down);
			if (!cells.diggable(pos.getX(), pos.getY(), pos.getZ())) {
				return "cannot dig " + GameJson.blockId(level.getBlockState(pos)) + " below (too hard, fluid or loose blocks next to it)";
			}
			String tool = BlockBreaker.precheck(bot, pos);
			if (tool != null) {
				return tool + ": " + GameJson.blockId(level.getBlockState(pos)) + " below needs " + BlockBreaker.requiredTool(level.getBlockState(pos));
			}
			// Die Wände des Lochs müssen dicht sein, sonst gräbt er sich in eine Höhle.
			if (down >= 2) {
				for (Direction side : SIDES) {
					BlockPos wall = pos.relative(side);
					if (cells.at(wall.getX(), wall.getY(), wall.getZ()) != Cell.FLOOR) {
						return "open space next to the hole";
					}
				}
			}
		}
		BlockPos floor = base.below(BURROW_DEPTH + 1);
		if (cells.at(floor.getX(), floor.getY(), floor.getZ()) != Cell.FLOOR) {
			return "no solid floor under the hole";
		}
		return BlockPlacer.hasSupport(level, base.below()) ? null : "nothing to attach the lid to";
	}

	private Outcome finish(ServerPlayer bot) {
		JsonObject data = progress(bot);
		boolean enclosed = data.get("enclosed").getAsBoolean();
		if (!enclosed) {
			data.addProperty("hint", "built, but a side is still open");
			return Outcome.failed("place_failed", data);
		}
		BlockPos feet = bot.blockPosition();
		data.addProperty("exit", kind.equals("hut")
				? "mine " + feet.north().above().toShortString() + " and " + feet.north().toShortString() + ", then walk out"
				: "goto a position outside with allow_dig and allow_place");
		return Outcome.ok(data);
	}

	private static boolean isWall(ServerLevel level, BlockPos pos) {
		return level.getBlockState(pos).isCollisionShapeFullBlock(level, pos);
	}

	/** Ob um den Bot herum und über ihm alles zu ist. */
	private static boolean enclosed(ServerPlayer bot) {
		ServerLevel level = bot.level();
		BlockPos feet = bot.blockPosition();
		for (Direction side : SIDES) {
			if (!isWall(level, feet.relative(side)) || !isWall(level, feet.relative(side).above())) {
				return false;
			}
		}
		return isWall(level, feet.above(2)) && isWall(level, feet.below());
	}

	private void enter(Phase next) {
		phase = next;
		phaseTicks = 0;
		waitTicks = 0;
	}

	@Override
	public void release(ServerPlayer bot) {
		if (breaker != null) {
			breaker.abort(bot);
		}
		Combat.halt(bot);
	}

	@Override
	public JsonObject progress(ServerPlayer bot) {
		JsonObject data = new JsonObject();
		if (kind != null) {
			data.addProperty("kind", kind);
		}
		if (hutFailed != null) {
			data.addProperty("hut_failed", hutFailed);
		}
		data.addProperty("enclosed", enclosed(bot));
		data.addProperty("blocks_placed", placed);
		data.addProperty("blocks_dug", dug);
		data.addProperty("blocks_needed", needed);
		data.addProperty("blocks_available", available);
		return data;
	}

	private static boolean hasPickaxe(ServerPlayer bot) {
		for (int slot = 0; slot < bot.getInventory().getContainerSize(); slot++) {
			if (bot.getInventory().getItem(slot).is(net.minecraft.tags.ItemTags.PICKAXES)) {
				return true;
			}
		}
		return false;
	}
}
