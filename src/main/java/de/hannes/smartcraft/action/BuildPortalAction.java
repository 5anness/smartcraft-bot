package de.hannes.smartcraft.action;

import com.google.gson.JsonObject;
import de.hannes.smartcraft.nav.Goal;
import de.hannes.smartcraft.nav.Moves;
import de.hannes.smartcraft.nav.Pos;
import de.hannes.smartcraft.nav.WorldCells;
import de.hannes.smartcraft.perception.GameJson;
import de.hannes.smartcraft.skill.BlockBreaker;
import de.hannes.smartcraft.skill.BlockPlacer;
import de.hannes.smartcraft.skill.Inv;
import de.hannes.smartcraft.skill.PathFollower;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code build_portal}: baut ein Netherportal (4 breit, 5 hoch) auf einem freien Platz in der Nähe und zündet es an.
 * Rahmen aus 10 Obsidian, die vier Ecken aus Bausteinen (die zählen für das Portal nicht). Drachenlauf 5: Der Bot
 * baute das Portal Block für Block mit {@code place} in die Festungswand, ein Modellschritt je Block.
 * Schema: {@code schemas/commands/build_portal.schema.json}.
 */
public final class BuildPortalAction extends Action {
	private static final int SEARCH = 8;
	private static final int SPAWNER_DISTANCE = 20;
	private static final int PLACE_INTERVAL = 3;

	private BlockPos origin;
	private Direction along;
	private BlockPos stand;
	private PathFollower walker;
	private final List<BlockPos> obsidian = new ArrayList<>();
	private final List<BlockPos> order = new ArrayList<>();
	/** Felder im Rahmen, die erst frei gegraben werden (Netherrack, Stein; nie Festungsblöcke). */
	private final List<BlockPos> toDig = new ArrayList<>();
	private BlockBreaker breaker;
	private int dug;
	private int next;
	private int ticks;
	private int placed;
	private boolean lit;
	private final boolean force;

	public BuildPortalAction(boolean force) {
		this.force = force;
	}

	@Override
	public int timeoutTicks() {
		return 1200;
	}

	@Override
	public Outcome tick(ServerPlayer bot) {
		ticks++;
		ServerLevel level = bot.level();
		if (origin == null) {
			int have = Inv.count(bot, "minecraft:obsidian");
			if (have < 10) {
				JsonObject data = progress(bot);
				data.addProperty("hint", "needs 10 obsidian, has " + have);
				return Outcome.failed("missing_obsidian", data);
			}
			// Nether-Lauf 38: Portal neben dem Lohenspawner, 13 Versuche hineinzugehen scheiterten an Treffern.
			BlockPos spawner = nearSpawner(bot);
			if (spawner != null) {
				JsonObject data = progress(bot);
				data.add("spawner", de.hannes.smartcraft.perception.GameJson.pos(spawner));
				data.addProperty("hint", "move at least " + SPAWNER_DISTANCE + " blocks away from the spawner, then build_portal");
				return Outcome.failed("near_spawner", data);
			}
			// Nicht an einer Kante: Drachenlauf 61 baute sich eine Bretterplattform über einem 24 Blöcke tiefen Abgrund, beim
			// Herumlaufen für den Rahmen fiel er hinunter.
			if (de.hannes.smartcraft.skill.Fighter.nearDrop(bot)) {
				JsonObject data = progress(bot);
				data.addProperty("hint", "a deep drop is within 2 blocks: build_portal walks around the frame. goto wide solid ground "
						+ "(netherrack floor, no drop nearby; dig into a wall if needed), then build_portal");
				return Outcome.failed("unsafe_spot", data);
			}
			// In der Oberwelt erst bauen, wenn alles für den Nether da ist (Wunsch von Hannes, 28.10.2026): Sonst geht er
			// halb ausgerüstet hindurch. Der Rückweg aus dem Nether ist nie gesperrt.
			if (level.dimension() == net.minecraft.world.level.Level.OVERWORLD && !force) {
				com.google.gson.JsonArray missing = de.hannes.smartcraft.skill.NetherKit.missingAll(bot);
				if (!missing.isEmpty()) {
					JsonObject data = progress(bot);
					data.add("missing", missing);
					data.addProperty("hint", "gather everything in missing before building the portal; force: true only in an emergency");
					return Outcome.failed("not_prepared", data);
				}
			}
			if (Inv.findItem(bot, "minecraft:flint_and_steel") < 0 && Inv.findItem(bot, "minecraft:fire_charge") < 0) {
				return Outcome.failed("no_igniter", progress(bot));
			}
			if (Inv.countScaffold(bot) < 4) {
				JsonObject data = progress(bot);
				data.addProperty("hint", "needs 4 building blocks for the corners");
				return Outcome.failed("no_blocks", data);
			}
			if (!findSite(bot)) {
				return Outcome.failed("no_space", progress(bot));
			}
		}

		if (bot.position().distanceTo(Vec3.atBottomCenterOf(stand)) > 0.8 && next < order.size()) {
			if (walker == null) {
				walker = new PathFollower(Goal.near(new Pos(stand.getX(), stand.getY(), stand.getZ()), 0.5),
						new Moves.Options(true, true));
			}
			PathFollower.State state = walker.tick(bot);
			if (state == PathFollower.State.RUNNING) {
				return null;
			}
			walker = null;
			PathFollower.release(bot);
			if (state != PathFollower.State.ARRIVED) {
				JsonObject data = progress(bot);
				data.addProperty("hint", "cannot reach the building spot " + stand.toShortString());
				return Outcome.failed("out_of_reach", data);
			}
			return null;
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
					data.addProperty("hint", breaker.failure());
					breaker = null;
					return Outcome.failed("dig_failed", data);
				}
			}
		}
		while (!toDig.isEmpty() && level.getBlockState(toDig.get(0)).isAir()) {
			toDig.remove(0);
		}
		if (!toDig.isEmpty()) {
			breaker = new BlockBreaker(toDig.remove(0));
			return null;
		}

		if (next < order.size()) {
			if (ticks % PLACE_INTERVAL != 0) {
				return null;
			}
			BlockPos pos = order.get(next);
			boolean isObsidian = obsidian.contains(pos);
			BlockState there = level.getBlockState(pos);
			if (isObsidian ? there.is(Blocks.OBSIDIAN) : !there.canBeReplaced()) {
				next++;
				return null;
			}
			// Ecken nie aus Obsidian: Das reicht sonst für den Rahmen nicht.
			int slot = isObsidian ? Inv.findItem(bot, "minecraft:obsidian")
					: Inv.findSlot(bot, stack -> Inv.isScaffold(stack) && !stack.is(Items.OBSIDIAN));
			String problem = slot < 0 ? "no_blocks" : BlockPlacer.place(bot, slot, pos);
			if (problem != null) {
				JsonObject data = progress(bot);
				data.addProperty("hint", problem + " at " + pos.toShortString());
				return Outcome.failed("place_failed", data);
			}
			placed++;
			next++;
			return null;
		}

		// Anzünden: Feuerzeug auf die Oberseite eines unteren Obsidians im Rahmen.
		BlockPos bottom = origin.relative(along, 1);
		BlockPos inside = bottom.above();
		if (!lit) {
			int slot = Inv.findItem(bot, "minecraft:flint_and_steel");
			if (slot < 0) {
				slot = Inv.findItem(bot, "minecraft:fire_charge");
			}
			Inv.hold(bot, slot);
			Vec3 hit = Vec3.atCenterOf(bottom).add(0, 0.5, 0);
			bot.lookAt(EntityAnchorArgument.Anchor.EYES, hit);
			bot.gameMode.useItemOn(bot, level, bot.getMainHandItem(), InteractionHand.MAIN_HAND,
					new BlockHitResult(hit, Direction.UP, bottom, false));
			bot.swing(InteractionHand.MAIN_HAND, true);
			lit = true;
			return null;
		}
		JsonObject data = progress(bot);
		if (level.getBlockState(inside).is(Blocks.NETHER_PORTAL)) {
			data.addProperty("portal_open", true);
			return Outcome.ok(data);
		}
		data.addProperty("hint", "frame built but the portal did not light; use flint_and_steel on the bottom obsidian");
		return Outcome.failed("not_lit", data);
	}

	/** Nächster Platz mit festem Boden unter 4 Feldern, 5 Felder Luft darüber und einem Standplatz davor. */
	private boolean findSite(ServerPlayer bot) {
		ServerLevel level = bot.level();
		WorldCells cells = new WorldCells(level);
		BlockPos feet = bot.blockPosition();
		double best = Double.MAX_VALUE;
		for (int dy = -3; dy <= 3; dy++) {
			for (int dx = -SEARCH; dx <= SEARCH; dx++) {
				for (int dz = -SEARCH; dz <= SEARCH; dz++) {
					BlockPos o = feet.offset(dx, dy, dz);
					for (Direction axis : new Direction[]{Direction.EAST, Direction.SOUTH}) {
						int digs = digsNeeded(level, cells, o, axis);
						if (digs < 0) {
							continue;
						}
						for (Direction side : new Direction[]{axis.getClockWise(), axis.getCounterClockWise()}) {
							BlockPos s = o.relative(axis, 1).relative(side, 2);
							if (Moves.kind(cells, s.getX(), s.getY(), s.getZ()) != Moves.Kind.GROUND) {
								continue;
							}
							// Freie Plätze zuerst: jeder abzubauende Block zählt wie ein paar Schritte mehr.
							double d = s.distSqr(feet) + digs * 6;
							if (d < best) {
								best = d;
								origin = o;
								along = axis;
								stand = s;
							}
						}
					}
				}
			}
		}
		if (origin == null) {
			return false;
		}
		for (int i = 0; i < 4; i++) {
			// Von oben nach unten: Was nachrutscht, fällt nicht auf schon freie Felder.
			for (int y = 4; y >= 0; y--) {
				BlockPos pos = origin.relative(along, i).above(y);
				if (!level.getBlockState(pos).isAir()) {
					toDig.add(pos);
				}
			}
		}
		BlockPos left = origin;
		BlockPos right = origin.relative(along, 3);
		// Reihenfolge: untere Ecken, Boden, Säulen von unten, obere Ecken, Deckel. Jeder Block hat so einen Nachbarn.
		order.add(left);
		order.add(right);
		for (BlockPos pos : new BlockPos[]{left.relative(along, 1), left.relative(along, 2)}) {
			obsidian.add(pos);
			order.add(pos);
		}
		for (int y = 1; y <= 3; y++) {
			for (BlockPos pos : new BlockPos[]{left.above(y), right.above(y)}) {
				obsidian.add(pos);
				order.add(pos);
			}
		}
		order.add(left.above(4));
		order.add(right.above(4));
		for (BlockPos pos : new BlockPos[]{left.above(4).relative(along, 1), left.above(4).relative(along, 2)}) {
			obsidian.add(pos);
			order.add(pos);
		}
		return true;
	}

	/** Wie viele Blöcke im Rahmen erst weg müssen, oder -1, wenn der Platz nicht geht (kein Boden, Unabbaubares). */
	private static int digsNeeded(ServerLevel level, WorldCells cells, BlockPos origin, Direction axis) {
		int digs = 0;
		for (int i = 0; i < 4; i++) {
			BlockPos column = origin.relative(axis, i);
			if (!level.getBlockState(column.below()).isFaceSturdy(level, column.below(), Direction.UP)) {
				return -1;
			}
			for (int y = 0; y < 5; y++) {
				BlockPos pos = column.above(y);
				BlockState state = level.getBlockState(pos);
				if (state.isAir()) {
					continue;
				}
				// Gras, Blumen, Schnee: mit der Hand weg. Perlen-Lauf 4: Auf einer Wiese fand er an 9 Stellen keinen Platz.
				if (state.canBeReplaced() && state.getCollisionShape(level, pos).isEmpty() && state.getFluidState().isEmpty()) {
					digs++;
					continue;
				}
				if (!cells.diggable(pos.getX(), pos.getY(), pos.getZ())) {
					return -1;
				}
				digs++;
			}
		}
		return digs;
	}

	@Override
	public void release(ServerPlayer bot) {
		PathFollower.release(bot);
	}

	@Override
	public JsonObject progress(ServerPlayer bot) {
		JsonObject data = new JsonObject();
		if (origin != null) {
			data.add("portal_pos", GameJson.pos(origin.relative(along, 1).above()));
		}
		data.addProperty("blocks_placed", placed);
		data.addProperty("blocks_dug", dug);
		data.addProperty("obsidian_left", Inv.count(bot, "minecraft:obsidian"));
		return data;
	}

	/** Nächster Spawner bis {@link #SPAWNER_DISTANCE} Blöcke, sonst {@code null}. */
	private static BlockPos nearSpawner(ServerPlayer bot) {
		BlockPos feet = bot.blockPosition();
		int r = SPAWNER_DISTANCE;
		for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-r, -8, -r), feet.offset(r, 8, r))) {
			if (pos.distSqr(feet) <= r * r && bot.level().getBlockState(pos).is(net.minecraft.world.level.block.Blocks.SPAWNER)) {
				return pos.immutable();
			}
		}
		return null;
	}
}
