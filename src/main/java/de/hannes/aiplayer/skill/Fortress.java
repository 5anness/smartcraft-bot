package de.hannes.aiplayer.skill;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;

/**
 * Netherfestungen beim Graben erkennen. Im KI-Lauf vom 15.10.2026 grub sich der Bot mit {@code tunnel} in die
 * Festung, quer durch ihre Gänge und wieder hinaus (rund 110 Netherziegel): Festungsblöcke baut deshalb weder
 * {@code tunnel} noch {@code goto} mit {@code allow_dig} ab.
 */
public final class Fortress {
	private Fortress() {
	}

	/** Netherziegel und alles daraus, nur im Nether (anderswo hat sie ein Spieler gebaut). */
	public static boolean isFortressBlock(ServerLevel level, BlockState state) {
		return level.dimension() == Level.NETHER
				&& (state.is(Blocks.NETHER_BRICKS) || state.is(Blocks.NETHER_BRICK_FENCE)
						|| state.is(Blocks.NETHER_BRICK_STAIRS) || state.is(Blocks.NETHER_BRICK_SLAB)
						|| state.is(Blocks.NETHER_BRICK_WALL) || state.is(Blocks.CRACKED_NETHER_BRICKS)
						|| state.is(Blocks.CHISELED_NETHER_BRICKS));
	}

	/** Ob die Stelle in einem Teil einer Festung liegt (Gang, Brücke, Raum), nach den Strukturdaten der Welt. */
	public static boolean contains(ServerLevel level, BlockPos pos) {
		return level.dimension() == Level.NETHER && level.hasChunkAt(pos)
				&& level.structureManager().getStructureWithPieceAt(pos, level.registryAccess()
						.lookupOrThrow(Registries.STRUCTURE).getValueOrThrow(BuiltinStructures.FORTRESS)).isValid();
	}
}
