package de.hannes.smartcraft.skill;

import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Baut einen Block ab, so wie ein Spieler: bestes Werkzeug nehmen, hinsehen, die nötige Zeit
 * „gedrückt halten“. Der Server prüft Reichweite und Dauer wie bei einem echten Client.
 * Einmal pro Tick {@link #tick} aufrufen; der Bot muss dabei stehen.
 */
public final class BlockBreaker {
	public enum State {
		RUNNING, DONE, FAILED
	}

	/** Reichweite der Hand in Blöcken, gemessen von den Augen. */
	public static final double REACH = 4.5;
	private static final int SWING_INTERVAL_TICKS = 5;
	/** So lange darf der Server nach dem Loslassen brauchen, bis der Block wirklich weg ist. */
	private static final int CONFIRM_TICKS = 40;

	private final BlockPos pos;
	private State state = State.RUNNING;
	private String failure;
	private String hint;

	private BlockState original;
	private boolean started;
	private boolean released;
	private float progress;
	private int ticks;
	private int ticksSinceRelease;

	public BlockBreaker(BlockPos pos) {
		this(pos, 3);
	}

	/** @param maxFall wie tief der Bot fallen darf, wenn der Block ihn trägt (Weg mit erlaubter Fallhöhe) */
	public BlockBreaker(BlockPos pos, int maxFall) {
		this.pos = pos;
		this.maxFall = maxFall;
	}

	private final int maxFall;
	/** Lava direkt unter dem Block erlauben (fließt nicht nach oben); für {@code make_obsidian}. */
	private boolean allowLavaBelow;

	public BlockBreaker allowLavaBelow() {
		allowLavaBelow = true;
		return this;
	}

	/**
	 * Zugedeckte Lava freilegen (make_obsidian): Lava darunter und daneben erlauben, solange der Block unter den Füßen des
	 * Bots liegt (Lava fließt nicht nach oben) und keine Lava darüber ist. Hannes' Lauf 28.10.: ein zugebauter Lavasee,
	 * jeder Deckenblock hatte Lava daneben, make_obsidian scheiterte sechsmal mit blocked_unsafe.
	 */
	private boolean allowLavaAround;

	public BlockBreaker allowLavaAround() {
		allowLavaBelow = true;
		allowLavaAround = true;
		return this;
	}

	public BlockPos pos() {
		return pos;
	}

	private BlockPos blocker;
	private BlockBreaker clearing;
	private int clears;
	private static final int MAX_CLEARS = 4;

	/** Bei {@code no_line_of_sight}: der Block, der die Sicht versperrt. */
	public BlockPos blocker() {
		return blocker;
	}

	/** Grund aus der festen Liste: {@code no_block}, {@code unbreakable}, {@code wrong_tool}, {@code out_of_reach}, {@code no_line_of_sight}, {@code blocked_unsafe}, {@code not_broken}. */
	public String failure() {
		return failure;
	}

	public String hint() {
		return hint;
	}

	/** Der Block, der abgebaut wurde bzw. werden sollte; {@code null} vor dem ersten Tick. */
	public BlockState block() {
		return original;
	}

	/**
	 * Prüft ohne Nebenwirkung, ob der Block mit dem vorhandenen Werkzeug abbaubar wäre.
	 *
	 * @return Grund oder {@code null}, wenn nichts dagegen spricht
	 */
	public static String precheck(ServerPlayer bot, BlockPos pos) {
		BlockState state = bot.level().getBlockState(pos);
		if (state.isAir() || !state.getFluidState().isEmpty() && state.getCollisionShape(bot.level(), pos).isEmpty()
				&& state.canBeReplaced()) {
			return "no_block";
		}
		if (state.getDestroySpeed(bot.level(), pos) < 0) {
			return "unbreakable";
		}
		if (Inv.bestToolSlot(bot, state) == Inv.NO_USABLE_TOOL) {
			return "wrong_tool";
		}
		return null;
	}

	/** Welches Werkzeug der Block verlangt, z. B. {@code stone pickaxe or better}; für den Hinweis bei {@code wrong_tool}. */
	public static String requiredTool(BlockState state) {
		String tool = state.is(BlockTags.MINEABLE_WITH_PICKAXE) ? "pickaxe"
				: state.is(BlockTags.MINEABLE_WITH_AXE) ? "axe"
				: state.is(BlockTags.MINEABLE_WITH_SHOVEL) ? "shovel"
				: state.is(BlockTags.MINEABLE_WITH_HOE) ? "hoe" : "tool";
		String tier = state.is(BlockTags.NEEDS_DIAMOND_TOOL) ? "diamond"
				: state.is(BlockTags.NEEDS_IRON_TOOL) ? "iron"
				: state.is(BlockTags.NEEDS_STONE_TOOL) ? "stone" : "wooden";
		return tier + " " + tool + " or better";
	}

	public static boolean inReach(ServerPlayer bot, BlockPos pos) {
		return bot.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) <= REACH;
	}

	/**
	 * Sichtlinie wie bei einem Spieler: Ein Strahl von den Augen zur Mitte oder zu einer Seitenmitte muss diesen
	 * Block als ersten festen Block treffen.
	 *
	 * @return {@code null} bei freier Sicht, sonst der Block, der im Weg ist (vom Strahl zur Mitte)
	 */
	public static BlockPos obstruction(ServerPlayer bot, BlockPos pos) {
		Vec3 eye = bot.getEyePosition();
		BlockPos first = null;
		Vec3 center = Vec3.atCenterOf(pos);
		List<Vec3> aims = new ArrayList<>();
		aims.add(center);
		for (Direction side : Direction.values()) {
			aims.add(center.add(side.getStepX() * 0.45, side.getStepY() * 0.45, side.getStepZ() * 0.45));
		}
		for (Vec3 aim : aims) {
			BlockHitResult hit = bot.level().clip(new ClipContext(eye, aim, ClipContext.Block.COLLIDER,
					ClipContext.Fluid.NONE, bot));
			if (hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(pos)) {
				return null;
			}
			if (first == null) {
				first = hit.getBlockPos().immutable();
			}
		}
		return first;
	}

	public State tick(ServerPlayer bot) {
		if (state != State.RUNNING) {
			return state;
		}
		ServerLevel level = bot.level();
		BlockState now = level.getBlockState(pos);

		if (clearing != null) {
			switch (clearing.tick(bot)) {
				case RUNNING -> {
					return state;
				}
				case DONE -> clearing = null;
				case FAILED -> {
					this.blocker = clearing.pos();
					return fail("no_line_of_sight", "blocked by " + clearing.pos().toShortString() + ": " + clearing.failure());
				}
			}
			return state;
		}
		if (!started) {
			original = now;
			String problem = precheck(bot, pos);
			if (problem != null) {
				return fail(problem, problem.equals("wrong_tool") ? "needs " + requiredTool(now) : null);
			}
			if (!inReach(bot, pos)) {
				return fail("out_of_reach", null);
			}
			BlockPos blocker = obstruction(bot, pos);
			// Wie ein Spieler: Was die Sicht versperrt, zuerst abbauen (höchstens 4 Blöcke). Drachenlauf 69: build_portal
			// scheiterte sonst zweimal mit no_line_of_sight.
			if (blocker != null && clears < MAX_CLEARS && precheck(bot, blocker) == null) {
				clears++;
				clearing = new BlockBreaker(blocker, maxFall);
				if (allowLavaAround) {
					clearing.allowLavaAround();
				}
				return state;
			}
			if (blocker != null) {
				this.blocker = blocker;
				return fail("no_line_of_sight", "blocked by " + BuiltInRegistries.BLOCK.getKey(level.getBlockState(blocker).getBlock()).getPath()
						+ " at " + blocker.getX() + " " + blocker.getY() + " " + blocker.getZ());
			}
			String danger = unsafeToBreak(bot, level);
			if (danger != null) {
				return fail("blocked_unsafe", danger);
			}

			int tool = Inv.bestToolSlot(bot, now);
			if (tool >= 0) {
				Inv.hold(bot, tool);
			}
			bot.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(pos));
			bot.gameMode.handleBlockBreakAction(pos, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
					Direction.UP, level.getMinY() + level.getHeight(), 0);
			started = true;
			// Weiche Blöcke und der Kreativmodus brechen sofort.
			if (level.getBlockState(pos) != original) {
				return finishIfGone(level);
			}
			return state;
		}

		if (now != original) {
			return finishIfGone(level);
		}

		ticks++;
		if (released) {
			// Der Server hat das Loslassen bekommen und beendet den Abbau selbst, sobald genug Zeit um ist.
			if (++ticksSinceRelease > CONFIRM_TICKS) {
				return fail("not_broken", "server did not confirm the break");
			}
			return state;
		}

		bot.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(pos));
		if (ticks % SWING_INTERVAL_TICKS == 0) {
			bot.swing(InteractionHand.MAIN_HAND, true);
		}
		// Fortschritt pro Tick neu vom Spiel holen: in der Luft und unter Wasser geht es langsamer.
		progress += now.getDestroyProgress(bot, level, pos);
		if (progress >= 1.0f) {
			bot.gameMode.handleBlockBreakAction(pos, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
					Direction.UP, level.getMinY() + level.getHeight(), 0);
			released = true;
			if (level.getBlockState(pos) != original) {
				return finishIfGone(level);
			}
		}
		return state;
	}

	/** Bricht einen angefangenen Abbau ab, damit der Block nicht halb zerstört angezeigt bleibt. */
	public void abort(ServerPlayer bot) {
		if (clearing != null) {
			clearing.abort(bot);
		}
		if (started && state == State.RUNNING) {
			bot.gameMode.handleBlockBreakAction(pos, ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
					Direction.UP, bot.level().getMinY() + bot.level().getHeight(), 0);
		}
	}

	private State finishIfGone(ServerLevel level) {
		BlockState now = level.getBlockState(pos);
		if (now.getBlock() == original.getBlock()) {
			return fail("not_broken", "block still there");
		}
		state = State.DONE;
		return state;
	}

	private State fail(String reason, String detail) {
		state = State.FAILED;
		failure = reason;
		hint = detail;
		return state;
	}

	/**
	 * Sicherheitsnetz: nichts abbauen, was den Bot gefährdet.
	 *
	 * @return was droht, oder {@code null}
	 */
	private String unsafeToBreak(ServerPlayer bot, ServerLevel level) {
		for (Direction direction : Direction.values()) {
			if (allowLavaBelow && direction == Direction.DOWN) {
				continue;
			}
			if (allowLavaAround && direction != Direction.UP && pos.getY() < bot.getBlockY()) {
				continue;
			}
			if (level.getFluidState(pos.relative(direction)).is(FluidTags.LAVA)) {
				return "lava_next_to_block";
			}
		}

		// Trägt der Block den Bot? Dann muss darunter fester Boden in sicherer Tiefe sein.
		boolean supportsBot = pos.getX() == bot.getBlockX() && pos.getZ() == bot.getBlockZ()
				&& pos.getY() < bot.getY() && pos.getY() >= bot.getY() - 1.5;
		if (supportsBot) {
			for (int depth = 1; depth <= maxFall; depth++) {
				BlockPos below = pos.below(depth);
				BlockState state = level.getBlockState(below);
				if (state.getFluidState().is(FluidTags.LAVA) || state.is(BlockTags.FIRE)) {
					return "lava_below";
				}
				if (!state.getCollisionShape(level, below).isEmpty()) {
					return null;
				}
				if (state.getFluidState().is(FluidTags.WATER)) {
					return null;
				}
			}
			return "would_fall";
		}

		// Steht der Bot direkt unter dem Block, darf nichts nachrutschen, das ihn begräbt.
		boolean aboveBot = pos.getX() == bot.getBlockX() && pos.getZ() == bot.getBlockZ() && pos.getY() > bot.getY();
		if (aboveBot && level.getBlockState(pos.above()).getBlock() instanceof FallingBlock) {
			return "falling_block_above";
		}
		return null;
	}
}
