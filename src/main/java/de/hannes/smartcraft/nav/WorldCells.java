package de.hannes.smartcraft.nav;

import de.hannes.smartcraft.skill.Fortress;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Liest die Welt für die Wegfindung und merkt sich jedes Ergebnis. Eine Instanz gilt für einen
 * Moment (eine Suche oder einen Tick); danach wegwerfen, weil sich die Welt ändert.
 */
public final class WorldCells implements CellSource {
	private static final Cell[] CELLS = Cell.values();
	/** Bis zu dieser Höhe steigt ein Spieler ohne Sprung auf einen Block. */
	private static final double MAX_LOW_HEIGHT = 0.6;
	/** Ab dieser Höhe zählt ein Block als Boden für das Feld darüber (Ackerboden, Trampelpfad, Treppe). */
	private static final double MIN_FLOOR_HEIGHT = 0.8;
	/** Blöcke, die von Hand ewig dauern (Obsidian), gräbt der Bot unterwegs nicht. */
	private static final float MAX_DIG_HARDNESS = 10.0f;

	private final ServerLevel level;
	private final Long2ByteOpenHashMap cache = new Long2ByteOpenHashMap();
	private final Long2ByteOpenHashMap diggableCache = new Long2ByteOpenHashMap();
	private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

	private boolean digFortress;

	/** Erlaubt das Abbauen von Festungsblöcken (Netherziegel); sonst sind sie tabu. */
	public WorldCells digFortress(boolean allowed) {
		digFortress = allowed;
		return this;
	}

	public WorldCells(ServerLevel level) {
		this.level = level;
		cache.defaultReturnValue((byte) -1);
		diggableCache.defaultReturnValue((byte) -1);
	}

	@Override
	public Cell at(int x, int y, int z) {
		long key = BlockPos.asLong(x, y, z);
		byte cached = cache.get(key);
		if (cached >= 0) {
			return CELLS[cached];
		}
		Cell cell = classify(x, y, z);
		cache.put(key, (byte) cell.ordinal());
		return cell;
	}

	@Override
	public boolean diggable(int x, int y, int z) {
		long key = BlockPos.asLong(x, y, z);
		byte cached = diggableCache.get(key);
		if (cached < 0) {
			cached = (byte) (computeDiggable(x, y, z) ? 1 : 0);
			diggableCache.put(key, cached);
		}
		return cached == 1;
	}

	private boolean computeDiggable(int x, int y, int z) {
		Cell cell = at(x, y, z);
		if (cell != Cell.FLOOR && cell != Cell.OBSTACLE && cell != Cell.LOW) {
			return false;
		}
		BlockPos pos = new BlockPos(x, y, z);
		BlockState state = level.getBlockState(pos);
		float hardness = state.getDestroySpeed(level, pos);
		if (hardness < 0 || hardness > MAX_DIG_HARDNESS || state.hasBlockEntity()) {
			return false;
		}
		// Festungen nicht anbohren: Ihre Gänge sind der Weg, ihre Wände halten Lohen und Witherskelette auf.
		if (!digFortress && Fortress.isFortressBlock(level, state)) {
			return false;
		}
		// Nichts anstechen, aus dem etwas nachläuft oder nachfällt.
		for (Direction direction : Direction.values()) {
			if (!level.getFluidState(pos.relative(direction)).isEmpty()) {
				return false;
			}
		}
		return !(level.getBlockState(pos.above()).getBlock() instanceof FallingBlock);
	}

	private Cell classify(int x, int y, int z) {
		// Ungeladene Chunks nie anfassen: getBlockState würde sie mitten im Tick laden oder erzeugen.
		if (!level.hasChunk(x >> 4, z >> 4)) {
			return Cell.UNLOADED;
		}
		BlockState state = level.getBlockState(cursor.set(x, y, z));
		if (state.isAir()) {
			return Cell.OPEN;
		}
		FluidState fluid = state.getFluidState();
		if (fluid.is(FluidTags.LAVA) || state.is(BlockTags.FIRE) || state.is(BlockTags.CAMPFIRES)
				|| state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CACTUS) || state.is(Blocks.SWEET_BERRY_BUSH)
				|| state.is(Blocks.POWDER_SNOW) || state.is(Blocks.WITHER_ROSE)) {
			return Cell.DANGER;
		}
		// Holztüren und Zauntore öffnet der Bot im Vorbeigehen (PathFollower): für die Suche sind sie offen.
		// Eisentüren brauchen Knopf oder Hebel und bleiben ein Hindernis.
		if (Doors.isOpenable(state)) {
			return Cell.OPEN;
		}
		// Leitern haben eine dünne Kollision an der Wand, sind aber begehbar. Gerüste sind auch
		// „kletterbar“, verhalten sich aber je nach Richtung wie ein fester Block: als Hindernis werten.
		if (state.is(BlockTags.CLIMBABLE) && !state.is(Blocks.SCAFFOLDING)) {
			return Cell.CLIMB;
		}
		VoxelShape shape = state.getCollisionShape(level, cursor);
		if (shape.isEmpty()) {
			return fluid.is(FluidTags.WATER) ? Cell.WATER : Cell.OPEN;
		}
		if (state.isCollisionShapeFullBlock(level, cursor)) {
			return Cell.FLOOR;
		}

		// Teilblöcke nach ihrer Form einordnen: Nur was die ganze Grundfläche bedeckt, trägt den Bot.
		// Türen, Zäune, Scheiben und Truhen fallen damit heraus.
		boolean coversFootprint = shape.min(Direction.Axis.X) <= 0.01 && shape.max(Direction.Axis.X) >= 0.99
				&& shape.min(Direction.Axis.Z) <= 0.01 && shape.max(Direction.Axis.Z) >= 0.99;
		double top = shape.max(Direction.Axis.Y);
		if (coversFootprint && top >= MIN_FLOOR_HEIGHT && top <= 1.0) {
			return Cell.FLOOR;
		}
		if (coversFootprint && top <= MAX_LOW_HEIGHT && shape.min(Direction.Axis.Y) <= 0.01) {
			return Cell.LOW;
		}
		return Cell.OBSTACLE;
	}
}
