package de.hannes.smartcraft.action;

import com.google.gson.JsonObject;
import de.hannes.smartcraft.nav.Goal;
import de.hannes.smartcraft.nav.Moves;
import de.hannes.smartcraft.nav.Pos;
import de.hannes.smartcraft.perception.GameJson;
import de.hannes.smartcraft.skill.Inv;
import de.hannes.smartcraft.skill.PathFollower;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import de.hannes.smartcraft.skill.BlockBreaker;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemUtils;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * {@code bucket}: füllt einen leeren Eimer an einer Lava- oder Wasserquelle oder gießt einen vollen
 * aus. Benutzt den Eimer wie ein Spieler (hinschauen, rechtsklicken), der Server prüft den Rest.
 * Schema: {@code schemas/commands/bucket.schema.json}.
 */
public final class BucketAction extends Action {
	private static final double APPROACH_REACH = 3.5;
	/** Lava gießt er aus größerem Abstand aus, damit sie ihn auf gleicher Höhe nicht erreicht. */
	private static final double LAVA_REACH = 4.2;
	/** Lava fließt auf ebenem Boden 3 Felder weit; auf gleicher Höhe steht der Bot mindestens 4 entfernt. */
	private static final int LAVA_SAME_LEVEL_DISTANCE = 4;
	/** So viele Quellen probiert {@code fill} ohne {@code pos} nacheinander, bevor es aufgibt. */
	private static final int MAX_CANDIDATES = 6;
	private static final int OBSIDIAN_WAIT_TICKS = 40;
	private static final Direction[] SUPPORT_ORDER = {
			Direction.DOWN, Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.UP};

	private final boolean fill;
	/** {@code lava} oder {@code water}; beim Ausgießen {@code null} = was im Eimer ist. */
	private String fluid;
	private final BlockPos requestedPos;
	private final int radius;
	/** Wasser auch ohne Lava daneben ausgießen. */
	private final boolean anywhere;

	private List<BlockPos> candidates;
	private int candidateIndex;
	private BlockPos target;
	private PathFollower follower;
	private String lastProblem;
	/** Beim Wasser auf Lava: die Lavaquelle, die zu Obsidian werden soll. */
	private BlockPos lavaPos;
	private int pouredTicks = -1;

	public BucketAction(boolean fill, String fluid, BlockPos pos, int radius, boolean anywhere) {
		this.anywhere = anywhere;
		this.fill = fill;
		this.fluid = fluid;
		this.requestedPos = pos;
		this.radius = radius;
	}

	@Override
	public int timeoutTicks() {
		return 1200;
	}

	/** Die Lava ist das Ziel: Dass sie in Sicht kommt, ist kein Grund abzubrechen. */
	@Override
	public boolean cancelOnDanger() {
		return false;
	}

	@Override
	public Outcome tick(ServerPlayer bot) {
		// Wasser fließt erst nach ein paar Ticks über die Lava: warten, bis sie Obsidian ist.
		if (pouredTicks >= 0) {
			boolean converted = !bot.level().getFluidState(lavaPos).is(FluidTags.LAVA);
			return converted || ++pouredTicks > OBSIDIAN_WAIT_TICKS ? Outcome.ok(progress(bot)) : null;
		}
		if (candidates == null) {
			Outcome problem = prepare(bot);
			if (problem != null) {
				return problem;
			}
		}

		while (true) {
			if (target == null) {
				if (candidateIndex >= candidates.size()) {
					JsonObject data = progress(bot);
					String reason = lastProblem == null ? "out_of_reach" : lastProblem;
					return Outcome.failed(candidates.size() > 1 && reason.equals("out_of_reach") ? "none_reachable" : reason, data);
				}
				target = candidates.get(candidateIndex++);
				follower = null;
			}

			Goal goal = approachGoal();
			boolean inPosition = goal.distance(bot.getX(), bot.getY(), bot.getZ()) <= 0 && bot.onGround();
			if (!inPosition) {
				if (follower == null) {
					follower = new PathFollower(goal, Moves.Options.NONE);
				}
				PathFollower.State state = follower.tick(bot);
				if (state == PathFollower.State.RUNNING) {
					return null;
				}
				if (state == PathFollower.State.FAILED) {
					lastProblem = "out_of_reach";
					target = null;
					continue;
				}
			}

			PathFollower.release(bot);
			String problem = fill ? fill(bot) : empty(bot);
			if (problem == null && lavaPos != null) {
				pouredTicks = 0;
				return null;
			}
			if (problem == null) {
				return Outcome.ok(progress(bot));
			}
			// Ausgießen und eine genannte Quelle haben nur einen Versuch, die Suche probiert die nächste.
			if (!fill || requestedPos != null || problem.equals("unsafe")) {
				return Outcome.failed(problem, progress(bot));
			}
			lastProblem = problem;
			target = null;
		}
	}

	/** Prüft Eimer und Ziel und legt die Kandidaten fest; gibt bei einem Fehler das Ergebnis zurück. */
	private Outcome prepare(ServerPlayer bot) {
		ServerLevel level = bot.level();
		candidates = new ArrayList<>();
		if (fill) {
			if (Inv.findItem(bot, "minecraft:bucket") < 0) {
				return Outcome.failed("no_bucket", progress(bot));
			}
			if (requestedPos != null) {
				FluidState state = level.getFluidState(requestedPos);
				if (fluid == null) {
					fluid = state.is(FluidTags.LAVA) ? "lava" : state.is(FluidTags.WATER) ? "water" : null;
				}
				if (fluid == null || !state.is(tag()) || !state.isSource()) {
					JsonObject data = progress(bot);
					data.addProperty("hint", "there is " + GameJson.blockId(level.getBlockState(requestedPos))
							+ (state.isEmpty() || state.isSource() ? "" : " (flowing, not a source)"));
					return Outcome.failed("not_a_source", data);
				}
				candidates.add(requestedPos);
			} else {
				candidates.addAll(findSources(bot));
				if (candidates.isEmpty()) {
					return Outcome.failed("none_found", progress(bot));
				}
			}
			return null;
		}

		// Ausgießen: Was im Eimer ist, bestimmt die Flüssigkeit.
		if (fluid == null) {
			fluid = Inv.findItem(bot, "minecraft:water_bucket") >= 0 ? "water"
					: Inv.findItem(bot, "minecraft:lava_bucket") >= 0 ? "lava" : null;
		}
		if (fluid == null || Inv.findItem(bot, filledId()) < 0) {
			return Outcome.failed("no_filled_bucket", progress(bot));
		}
		// Im Nether verdampft Wasser. Perlen-Lauf 6: Der Bot goss es mehrmals aus, das Ergebnis meldete trotzdem ok.
		if (fluid.equals("water") && Boolean.TRUE.equals(level.environmentAttributes()
				.getValue(net.minecraft.world.attribute.EnvironmentAttributes.WATER_EVAPORATES, requestedPos))) {
			JsonObject data = progress(bot);
			data.addProperty("hint", "water evaporates in this dimension; no obsidian here, use build_portal with obsidian you carry");
			return Outcome.failed("evaporates", data);
		}
		// Wasser in eine Lavaquelle hinein ersetzt die Lava nur. Obsidian entsteht, wenn das Wasser
		// über oder neben der Quelle landet: dann dort hingießen und die Lavaquelle merken.
		FluidState atTarget = level.getFluidState(requestedPos);
		if (fluid.equals("water") && atTarget.is(FluidTags.LAVA) && atTarget.isSource()) {
			BlockPos beside = besideLava(level, requestedPos);
			if (beside == null) {
				JsonObject data = progress(bot);
				data.addProperty("hint", "no free cell above or next to the lava source to pour the water into");
				return Outcome.failed("no_support", data);
			}
			lavaPos = requestedPos;
			candidates.add(beside);
			return null;
		}
		// Wasser ohne Lava daneben: verschenkt den Eimer (im KI-Lauf gegossen, ohne dass Obsidian entstand).
		// Nur mit anywhere: true, etwa um ein Feuer zu löschen oder einen Fall abzufangen.
		if (fluid.equals("water") && !anywhere && !lavaAround(level, requestedPos)) {
			JsonObject data = progress(bot);
			data.addProperty("hint", "no lava source at or next to pos; give the lava source itself as pos (from fluid_spotted or look), "
					+ "or anywhere: true to pour water elsewhere");
			return Outcome.failed("no_lava_here", data);
		}
		// Nennt die KI einen festen Block, gießt sie oben drauf, wie ein Klick auf seine Oberseite.
		BlockPos spot = requestedPos;
		if (!level.getBlockState(spot).canBeReplaced()) {
			spot = requestedPos.above();
		}
		BlockState there = level.getBlockState(spot);
		if (!there.canBeReplaced()) {
			JsonObject data = progress(bot);
			data.addProperty("hint", "there is " + GameJson.blockId(level.getBlockState(requestedPos)) + " and above it "
					+ GameJson.blockId(there));
			return Outcome.failed("occupied", data);
		}
		if (findSupport(level, spot) == null) {
			return Outcome.failed("no_support", progress(bot));
		}
		candidates.add(spot);
		return null;
	}

	/** Lava an der Stelle, darüber oder direkt daneben. */
	private static boolean lavaAround(ServerLevel level, BlockPos pos) {
		for (BlockPos near : BlockPos.betweenClosed(pos.offset(-1, -1, -1), pos.offset(1, 1, 1))) {
			if (level.getFluidState(near).is(FluidTags.LAVA)) {
				return true;
			}
		}
		return false;
	}

	/** Ein freies Feld über (bevorzugt) oder neben der Lavaquelle, in das sich Wasser gießen lässt. */
	static BlockPos besideLava(ServerLevel level, BlockPos lava) {
		List<BlockPos> cells = new ArrayList<>();
		for (Direction direction : new Direction[]{Direction.UP, Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
			cells.add(lava.relative(direction));
		}
		// Liegt die Lava in einer Mulde, landet das Wasser neben dem Feld über ihr und fließt hinüber.
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			cells.add(lava.above().relative(direction));
		}
		for (BlockPos cell : cells) {
			if (level.getBlockState(cell).isAir() && findSupport(level, cell) != null) {
				return cell;
			}
		}
		return null;
	}

	/** Quellen der Flüssigkeit im Umkreis, die nächsten zuerst; nur solche mit freier Seite zum Hinschauen. */
	private List<BlockPos> findSources(ServerPlayer bot) {
		ServerLevel level = bot.level();
		BlockPos center = bot.blockPosition();
		List<BlockPos> found = new ArrayList<>();
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dy = -radius; dy <= radius; dy++) {
				for (int dz = -radius; dz <= radius; dz++) {
					cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
					FluidState state = level.getFluidState(cursor);
					if (state.is(tag()) && state.isSource() && hasOpenSide(level, cursor)) {
						found.add(cursor.immutable());
					}
				}
			}
		}
		found.sort(Comparator.comparingDouble(pos -> pos.distSqr(center)));
		return found.size() > MAX_CANDIDATES ? found.subList(0, MAX_CANDIDATES) : found;
	}

	private static boolean hasOpenSide(ServerLevel level, BlockPos pos) {
		for (Direction direction : new Direction[]{Direction.UP, Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
			BlockPos neighbour = pos.relative(direction);
			// Fließwasser, Gras oder Schnee daneben zählen als frei: Eine einzelne Quelle am Hang ist rundum von
			// ihrem eigenen Fließwasser umgeben (KI-Lauf 19.10.: viermal none_found neben einer Quelle).
			if (level.getBlockState(neighbour).canBeReplaced()) {
				return true;
			}
		}
		return false;
	}

	/** Schaut auf die Quelle und benutzt den leeren Eimer. */
	private String fill(ServerPlayer bot) {
		ServerLevel level = bot.level();
		FluidState state = level.getFluidState(target);
		if (!state.is(tag()) || !state.isSource()) {
			return "not_a_source";
		}
		int slot = Inv.findItem(bot, "minecraft:bucket");
		if (slot < 0) {
			return "no_bucket";
		}
		Vec3 aim = new Vec3(target.getX() + 0.5, target.getY() + 0.7, target.getZ() + 0.5);
		if (bot.getEyePosition().distanceTo(aim) > BlockBreaker.REACH) {
			return "out_of_reach";
		}
		Inv.hold(bot, slot);
		bot.lookAt(EntityAnchorArgument.Anchor.EYES, aim);
		// Direkt an target schöpfen statt über den Blickstrahl: Der traf im Gelände oft einen Block davor
		// (im KI-Lauf fünfmal obstructed an einer erreichbaren Quelle).
		BlockState block = level.getBlockState(target);
		if (!(block.getBlock() instanceof BucketPickup pickup)) {
			return "not_a_source";
		}
		ItemStack filled = pickup.pickupBlock(bot, level, target, block);
		if (filled.isEmpty()) {
			return "obstructed";
		}
		bot.setItemInHand(InteractionHand.MAIN_HAND, ItemUtils.createFilledResult(bot.getMainHandItem(), bot, filled));
		bot.swing(InteractionHand.MAIN_HAND, true);
		return null;
	}

	/** Schaut auf die Seite eines Nachbarblocks, sodass die Flüssigkeit genau an {@code target} landet. */
	private String empty(ServerPlayer bot) {
		ServerLevel level = bot.level();
		if (fluid.equals("lava") && !lavaSafe(bot)) {
			return "unsafe";
		}
		int slot = Inv.findItem(bot, filledId());
		if (slot < 0) {
			return "no_filled_bucket";
		}
		Direction supportSide = findSupport(level, target);
		if (supportSide == null) {
			return "no_support";
		}
		BlockPos support = target.relative(supportSide);
		Direction face = supportSide.getOpposite();
		Vec3 hit = Vec3.atCenterOf(support).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
		Inv.hold(bot, slot);
		bot.lookAt(EntityAnchorArgument.Anchor.EYES, hit);
		// Nicht über den Blickstrahl wie bei einem Klick: Der trifft bei flachem Winkel eine Kante davor, und
		// die Flüssigkeit landet woanders, als das Sicherheitsnetz geprüft hat. Darum genau an target gießen.
		ItemStack stack = bot.getMainHandItem();
		if (!(stack.getItem() instanceof BucketItem bucket)
				|| !bucket.emptyContents(bot, level, target, new BlockHitResult(hit, face, support, false))) {
			return "pour_failed";
		}
		bucket.checkExtraContent(bot, level, stack, target);
		// Eigenes Wasser nicht gleich wieder zubauen lassen (make_obsidian holt es zurück).
		de.hannes.smartcraft.reflex.WaterGuard.pause(level.getGameTime(), target);
		bot.setItemInHand(InteractionHand.MAIN_HAND, BucketItem.getEmptySuccessItem(stack, bot));
		bot.swing(InteractionHand.MAIN_HAND, true);
		return level.getFluidState(target).isEmpty() && lavaPos == null ? "pour_failed" : null;
	}

	/** Wohin der Bot zum Ausgießen geht: in Reichweite und, bei Lava, auf einen sicheren Platz. */
	private Goal approachGoal() {
		Pos anchor = new Pos(target.getX(), target.getY(), target.getZ());
		boolean lava = !fill && "lava".equals(fluid);
		Goal reach = Goal.withinReach(anchor, lava ? LAVA_REACH : APPROACH_REACH);
		if (!lava) {
			return reach;
		}
		return new Goal() {
			@Override
			public double distance(double x, double y, double z) {
				double distance = reach.distance(x, y, z);
				return lavaSafe((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)) ? distance : Math.max(distance, 1.0);
			}

			@Override
			public Pos anchor() {
				return anchor;
			}
		};
	}

	private boolean lavaSafe(ServerPlayer bot) {
		BlockPos feet = bot.blockPosition();
		return lavaSafe(feet.getX(), feet.getY(), feet.getZ());
	}

	/**
	 * Lava nie über die Füße (sie liefe herab) und nie in die eigene Spalte. Tiefer als die Füße
	 * genügt eine Spalte Abstand, auf gleicher Höhe müssen es 4 sein: so weit fließt sie.
	 */
	private boolean lavaSafe(int feetX, int feetY, int feetZ) {
		int horizontal = Math.max(Math.abs(target.getX() - feetX), Math.abs(target.getZ() - feetZ));
		if (target.getY() > feetY) {
			return false;
		}
		return target.getY() < feetY ? horizontal >= 1 : horizontal >= LAVA_SAME_LEVEL_DISTANCE;
	}

	/** Ein fester Nachbar, an dessen Seite der Eimer angesetzt wird; bevorzugt der Block darunter. */
	private static Direction findSupport(ServerLevel level, BlockPos pos) {
		for (Direction direction : SUPPORT_ORDER) {
			BlockPos neighbour = pos.relative(direction);
			BlockState state = level.getBlockState(neighbour);
			if (!state.canBeReplaced() && !state.getCollisionShape(level, neighbour).isEmpty()) {
				return direction;
			}
		}
		return null;
	}

	private TagKey<Fluid> tag() {
		return "lava".equals(fluid) ? FluidTags.LAVA : FluidTags.WATER;
	}

	private String filledId() {
		return "minecraft:" + fluid + "_bucket";
	}

	@Override
	public void release(ServerPlayer bot) {
		PathFollower.release(bot);
	}

	@Override
	public JsonObject progress(ServerPlayer bot) {
		JsonObject data = new JsonObject();
		data.addProperty("action", fill ? "fill" : "empty");
		if (fluid != null) {
			data.addProperty("fluid", fluid);
		}
		if (target != null) {
			data.add("block_pos", GameJson.pos(target));
			data.addProperty("now_there", GameJson.blockId(bot.level().getBlockState(target)));
		}
		if (lavaPos != null) {
			data.add("lava_pos", GameJson.pos(lavaPos));
			data.addProperty("lava_now", GameJson.blockId(bot.level().getBlockState(lavaPos)));
		}
		data.addProperty("buckets", Inv.count(bot, "minecraft:bucket"));
		data.addProperty("water_buckets", Inv.count(bot, "minecraft:water_bucket"));
		data.addProperty("lava_buckets", Inv.count(bot, "minecraft:lava_bucket"));
		data.add("held_item", Inv.heldJson(bot));
		return data;
	}
}
