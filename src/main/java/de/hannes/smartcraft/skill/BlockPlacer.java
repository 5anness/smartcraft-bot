package de.hannes.smartcraft.skill;

import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Setzt einen Block so, wie ein Spieler es tut: Block in die Hand, auf die Seite eines
 * Nachbarblocks klicken. Der Server prüft das wie bei einem echten Client.
 */
public final class BlockPlacer {
	private static final Direction[] SUPPORT_ORDER = {
			Direction.DOWN, Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.UP};

	private static final int FIRE_DISTANCE = 2;

	private BlockPlacer() {
	}

	/**
	 * Setzt den Block aus {@code slot} an {@code pos}.
	 *
	 * @return {@code null} bei Erfolg (der Block steht nachweislich), sonst der Grund: {@code not_a_block},
	 * {@code occupied}, {@code no_support}, {@code out_of_reach}, {@code obstructed}, {@code flammable_near_fire},
	 * {@code place_failed}
	 */
	public static String place(ServerPlayer bot, int slot, BlockPos pos) {
		ServerLevel level = bot.level();
		ItemStack stack = bot.getInventory().getItem(slot);
		if (!(stack.getItem() instanceof BlockItem item)) {
			return "not_a_block";
		}
		if (!level.getBlockState(pos).canBeReplaced()) {
			return "occupied";
		}
		Direction supportSide = findSupport(level, pos);
		if (supportSide == null) {
			return "no_support";
		}
		BlockPos support = pos.relative(supportSide);
		// Der Klickpunkt: Mitte der Fläche des Nachbarblocks, die zum neuen Block zeigt.
		Direction face = supportSide.getOpposite();
		Vec3 hit = Vec3.atCenterOf(support).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
		if (bot.getEyePosition().distanceTo(hit) > BlockBreaker.REACH) {
			return "out_of_reach";
		}
		if (!level.getEntitiesOfClass(LivingEntity.class, new AABB(pos).deflate(0.01)).isEmpty()) {
			return "obstructed";
		}
		// Betten im Nether/End sind Sprengsätze und werden sofort gezündet: Feuer daneben ist egal (bed_cycle: nach der ersten
		// Explosion brennt es, jedes weitere Bett derselben Landung scheiterte sonst hier).
		boolean bomb = item.getBlock() instanceof net.minecraft.world.level.block.BedBlock
				&& level.dimension() != net.minecraft.world.level.Level.OVERWORLD;
		if (!bomb && item.getBlock().defaultBlockState().ignitedByLava() && fireNearby(level, pos)) {
			return "flammable_near_fire";
		}

		Inv.hold(bot, slot);
		bot.lookAt(EntityAnchorArgument.Anchor.EYES, hit);
		// Geduckt klicken: sonst öffnet ein Klick auf Truhe oder Werkbank deren Fenster, statt zu setzen.
		boolean wasSneaking = bot.isShiftKeyDown();
		bot.setShiftKeyDown(true);
		bot.gameMode.useItemOn(bot, level, bot.getMainHandItem(), InteractionHand.MAIN_HAND,
				new BlockHitResult(hit, face, support, false));
		bot.setShiftKeyDown(wasSneaking);
		bot.swing(InteractionHand.MAIN_HAND, true);

		// Über das Item vergleichen: Eine Fackel an der Wand ist ein anderer Block als eine auf dem Boden.
		return level.getBlockState(pos).getBlock().asItem() == item ? null : "place_failed";
	}

	/** Lava oder Feuer bis {@value #FIRE_DISTANCE} Felder um die Stelle: Dort fängt Holz Feuer. */
	public static boolean fireNearby(ServerLevel level, BlockPos pos) {
		for (BlockPos near : BlockPos.betweenClosed(pos.offset(-FIRE_DISTANCE, -FIRE_DISTANCE, -FIRE_DISTANCE),
				pos.offset(FIRE_DISTANCE, FIRE_DISTANCE, FIRE_DISTANCE))) {
			BlockState state = level.getBlockState(near);
			if (state.is(BlockTags.FIRE) || state.getFluidState().is(FluidTags.LAVA)) {
				return true;
			}
		}
		return false;
	}

	/** Ob es an dieser Stelle einen Nachbarblock gibt, an den sich ein Block setzen lässt. */
	public static boolean hasSupport(ServerLevel level, BlockPos pos) {
		return findSupport(level, pos) != null;
	}

	/** Ein Nachbar, an den sich der Block setzen lässt; bevorzugt der Block darunter. */
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
}
