package de.hannes.smartcraft.skill;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;

/**
 * Baut sich um einen Block nach oben: springen und im höchsten Punkt einen Block unter die
 * Füße setzen. Einmal pro Tick {@link #tick} aufrufen.
 */
public final class PillarUp {
	public enum State {
		RUNNING, DONE, FAILED
	}

	private static final int TIMEOUT_TICKS = 40;
	/** So hoch muss der Bot über dem Feld sein, damit der Block nicht in ihm steckt. */
	private static final double CLEARANCE = 1.02;

	private State state = State.RUNNING;
	private String failure;
	private BlockPos cell;
	private BlockPos placed;
	private int ticks;

	/** Grund: {@code no_blocks}, {@code not_on_ground}, {@code blocked_above} oder ein Grund aus {@link BlockPlacer}. */
	public String failure() {
		return failure;
	}

	/** Der gesetzte Block oder {@code null}. */
	public BlockPos placed() {
		return placed;
	}

	public State tick(ServerPlayer bot) {
		if (state != State.RUNNING) {
			return state;
		}
		ticks++;

		if (cell == null) {
			if (!bot.onGround()) {
				return ticks > 10 ? fail("not_on_ground") : state;
			}
			if (Inv.findScaffold(bot) < 0) {
				return fail("no_blocks");
			}
			BlockPos feet = BlockPos.containing(bot.getX(), bot.getY() + 0.2, bot.getZ());
			// Erst in die Mitte des Feldes: von der Kante aus landet der Bot neben seinem Block.
			double dx = feet.getX() + 0.5 - bot.getX();
			double dz = feet.getZ() + 0.5 - bot.getZ();
			if (dx * dx + dz * dz > 0.04) {
				float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0f;
				bot.setYRot(yaw);
				bot.setYHeadRot(yaw);
				bot.zza = 0.4f;
				return ticks > TIMEOUT_TICKS ? fail("not_on_ground") : state;
			}
			bot.zza = 0;
			cell = feet;
			ticks = 0;
			bot.setJumping(true);
			return state;
		}

		if (placed == null) {
			bot.setJumping(false);
			bot.zza = 0;
			if (bot.getY() >= cell.getY() + CLEARANCE) {
				int slot = Inv.findScaffold(bot);
				String problem = slot < 0 ? "no_blocks" : BlockPlacer.place(bot, slot, cell);
				if (problem != null) {
					return fail(problem);
				}
				placed = cell;
			} else if (ticks > 12 && bot.onGround()) {
				// Gesprungen, aber nicht hoch genug gekommen: über dem Kopf ist etwas im Weg.
				return fail("blocked_above");
			}
			return ticks > TIMEOUT_TICKS ? fail("blocked_above") : state;
		}

		if (bot.onGround()) {
			state = State.DONE;
		}
		return ticks > TIMEOUT_TICKS ? fail("not_on_ground") : state;
	}

	private State fail(String reason) {
		state = State.FAILED;
		failure = reason;
		return state;
	}
}
