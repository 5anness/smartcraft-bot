package de.hannes.smartcraft.skill;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Sucht Blöcke einer Art im Umkreis. Ein großer Umkreis sind über hunderttausend Blöcke, deshalb
 * läuft die Suche in Portionen mit Zeitbudget, verteilt über mehrere Ticks.
 */
public final class BlockScan {
	private static final int MAX_VERTICAL = 20;
	private static final int MAX_RESULTS = 4000;

	private final BlockPos center;
	private final int radius;
	private final int vertical;
	private final Predicate<BlockState> matches;
	private final List<BlockPos> found = new ArrayList<>();
	private int dx;
	private boolean done;

	public BlockScan(BlockPos center, int radius, Predicate<BlockState> matches) {
		this.center = center;
		this.radius = radius;
		this.vertical = Math.min(radius, MAX_VERTICAL);
		this.matches = matches;
		this.dx = -radius;
	}

	public List<BlockPos> found() {
		return found;
	}

	/** @return {@code true}, sobald der ganze Umkreis abgesucht ist */
	public boolean advance(ServerLevel level, long budgetNanos) {
		long deadline = System.nanoTime() + budgetNanos;
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		while (!done) {
			int x = center.getX() + dx;
			for (int dz = -radius; dz <= radius; dz++) {
				int z = center.getZ() + dz;
				if (dx * dx + dz * dz > radius * radius || !level.hasChunk(x >> 4, z >> 4)) {
					continue;
				}
				for (int dy = -vertical; dy <= vertical; dy++) {
					if (matches.test(level.getBlockState(cursor.set(x, center.getY() + dy, z)))) {
						found.add(cursor.immutable());
					}
				}
			}
			if (++dx > radius || found.size() >= MAX_RESULTS) {
				done = true;
			}
			// Nach jeder Scheibe auf die Uhr sehen: eine Scheibe sind höchstens ein paar tausend Blöcke.
			if (System.nanoTime() >= deadline) {
				break;
			}
		}
		return done;
	}
}
