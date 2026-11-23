package de.hannes.aiplayer.action;

import com.google.gson.JsonObject;
import de.hannes.aiplayer.nav.Goal;
import de.hannes.aiplayer.nav.Moves;
import de.hannes.aiplayer.nav.Pos;
import de.hannes.aiplayer.perception.GameJson;
import de.hannes.aiplayer.skill.Inv;
import de.hannes.aiplayer.skill.PathFollower;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * {@code fill_portal}: setzt in jeden Endportalrahmen im Umkreis ein Enderauge, den nächsten zuerst.
 * Sind alle Rahmen voll, öffnet das Spiel das Portal (`portal_open`).
 * Schema: {@code schemas/commands/fill_portal.schema.json}.
 */
public final class FillPortalAction extends Action {
	private static final int RADIUS = 16;
	private static final double REACH = 3.5;
	/** Rahmen, die so oft nicht erreichbar waren, lässt er aus. */
	private static final int MAX_FAILED = 3;

	private BlockPos target;
	private PathFollower follower;
	private final List<BlockPos> unreachable = new ArrayList<>();
	private int filled;

	/** Im Portalraum liegt Lava unter dem Portal; sie ist erwartet und kein Grund abzubrechen. */
	@Override
	public boolean cancelOnDanger() {
		return false;
	}

	@Override
	public int timeoutTicks() {
		return 2400;
	}

	@Override
	public Outcome tick(ServerPlayer bot) {
		ServerLevel level = bot.level();
		if (target == null || hasEye(level, target)) {
			follower = null;
			List<BlockPos> empty = emptyFrames(bot);
			if (empty.isEmpty()) {
				JsonObject data = progress(bot);
				return filled == 0 && frames(bot).isEmpty() ? Outcome.failed("no_frames", data) : Outcome.ok(data);
			}
			if (Inv.findItem(bot, "minecraft:ender_eye") < 0) {
				return Outcome.failed("no_eye", progress(bot));
			}
			target = empty.get(0);
		}
		Goal goal = Goal.withinReach(new Pos(target.getX(), target.getY(), target.getZ()), REACH);
		if (goal.distance(bot.getX(), bot.getY(), bot.getZ()) > 0 || !bot.onGround()) {
			if (follower == null) {
				follower = new PathFollower(goal, Moves.Options.NONE);
			}
			PathFollower.State state = follower.tick(bot);
			if (state == PathFollower.State.FAILED) {
				unreachable.add(target);
				target = null;
				if (unreachable.size() >= MAX_FAILED && filled == 0) {
					JsonObject data = progress(bot);
					data.addProperty("hint", follower.hint());
					return Outcome.failed("out_of_reach", data);
				}
			}
			return null;
		}
		PathFollower.release(bot);
		Inv.hold(bot, Inv.findItem(bot, "minecraft:ender_eye"));
		Vec3 hit = Vec3.atCenterOf(target).add(0, 0.5, 0);
		bot.lookAt(EntityAnchorArgument.Anchor.EYES, hit);
		bot.setShiftKeyDown(false);
		bot.gameMode.useItemOn(bot, level, bot.getMainHandItem(), InteractionHand.MAIN_HAND,
				new BlockHitResult(hit, Direction.UP, target, false));
		bot.swing(InteractionHand.MAIN_HAND, true);
		if (hasEye(level, target)) {
			filled++;
		} else {
			unreachable.add(target);
		}
		target = null;
		return null;
	}

	private static boolean hasEye(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		return !state.is(Blocks.END_PORTAL_FRAME) || state.getValue(EndPortalFrameBlock.HAS_EYE);
	}

	private List<BlockPos> frames(ServerPlayer bot) {
		List<BlockPos> found = new ArrayList<>();
		BlockPos feet = bot.blockPosition();
		for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-RADIUS, -6, -RADIUS), feet.offset(RADIUS, 6, RADIUS))) {
			if (bot.level().getBlockState(pos).is(Blocks.END_PORTAL_FRAME)) {
				found.add(pos.immutable());
			}
		}
		return found;
	}

	private List<BlockPos> emptyFrames(ServerPlayer bot) {
		List<BlockPos> empty = new ArrayList<>(frames(bot).stream()
				.filter(pos -> !hasEye(bot.level(), pos) && !unreachable.contains(pos)).toList());
		empty.sort(Comparator.comparingDouble(pos -> pos.distSqr(bot.blockPosition())));
		return empty;
	}

	private static boolean portalOpen(ServerPlayer bot) {
		BlockPos feet = bot.blockPosition();
		for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-RADIUS, -6, -RADIUS), feet.offset(RADIUS, 6, RADIUS))) {
			if (bot.level().getBlockState(pos).is(Blocks.END_PORTAL)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public void release(ServerPlayer bot) {
		PathFollower.release(bot);
	}

	@Override
	public JsonObject progress(ServerPlayer bot) {
		JsonObject data = new JsonObject();
		List<BlockPos> frames = frames(bot);
		long withEye = frames.stream().filter(pos -> hasEye(bot.level(), pos)).count();
		data.addProperty("filled_now", filled);
		data.addProperty("frames", frames.size());
		data.addProperty("frames_with_eye", withEye);
		data.addProperty("eyes_needed", frames.size() - withEye);
		data.addProperty("eyes_left", Inv.count(bot, "minecraft:ender_eye"));
		data.addProperty("portal_open", portalOpen(bot));
		if (!unreachable.isEmpty()) {
			data.add("unreachable", GameJson.pos(unreachable.get(0)));
		}
		return data;
	}
}
