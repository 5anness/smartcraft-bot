package de.hannes.aiplayer.bot;

import de.hannes.aiplayer.AiPlayerMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Testhilfe (30.10.2026, Idee „One-Cycle“ von Hannes): misst, wie viel Schaden Betten direkt unter dem Kopf des Drachen
 * machen und wie viele davon in kurzer Folge wirken. Alle {@code interval} Ticks setzt sie ein Bett {@code below} Blöcke
 * unter den Kopf (ohne Weg, ohne Reichweite: bester Fall) und lässt den Bot es anklicken. Jede Explosion steht als
 * {@code bedprobe}-Zeile im Log: Leben vorher/nachher, Phase, Kopfhöhe.
 */
final class BedProbe {
	private static int left;
	private static int interval;
	private static double below;
	private static int wait;
	private static String name;

	private BedProbe() {
	}

	private static boolean hooked;

	static void start(net.minecraft.server.MinecraftServer server, String botName, int count, int every, double offset) {
		if (!hooked) {
			hooked = true;
			net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(
					s -> tick(s.getPlayerList().getPlayerByName(name)));
		}
		name = botName;
		left = count;
		interval = Math.max(1, every);
		below = offset;
		wait = 0;
		AiPlayerMod.LOGGER.info("bedprobe start count=" + count + " interval=" + interval + " below=" + offset);
	}

	static void tick(ServerPlayer bot) {
		if (left <= 0) {
			return;
		}
		if (bot == null) {
			AiPlayerMod.LOGGER.info("bedprobe end no_bot");
			left = 0;
			return;
		}
		if (--wait > 0) {
			return;
		}
		ServerLevel level = (ServerLevel) bot.level();
		EnderDragon dragon = level.getEntitiesOfClass(EnderDragon.class, bot.getBoundingBox().inflate(400), LivingEntity::isAlive)
				.stream().findFirst().orElse(null);
		if (dragon == null) {
			AiPlayerMod.LOGGER.info("bedprobe end no_dragon");
			left = 0;
			return;
		}
		Entity head = dragon.getSubEntities()[0];
		BlockPos foot = BlockPos.containing(head.getX(), head.getY() - below, head.getZ());
		Direction facing = Direction.NORTH;
		BlockPos other = foot.relative(facing);
		BlockState state = Blocks.BED.white().defaultBlockState().setValue(BedBlock.FACING, facing);
		level.setBlock(foot, state.setValue(BedBlock.PART, BedPart.FOOT), 3);
		level.setBlock(other, state.setValue(BedBlock.PART, BedPart.HEAD), 3);
		float before = dragon.getHealth();
		String phase = dragon.getPhaseManager().getCurrentPhase().getPhase().toString();
		bot.gameMode.useItemOn(bot, level, ItemStack.EMPTY, InteractionHand.MAIN_HAND,
				new BlockHitResult(Vec3.atCenterOf(foot), Direction.UP, foot, false));
		AiPlayerMod.LOGGER.info(String.format(java.util.Locale.ROOT,
				"bedprobe boom phase=%s head=%.1f/%.1f/%.1f bot=%.1f/%.1f/%.1f bedY=%d health %.1f -> %.1f phaseAfter=%s",
				phase, head.getX(), head.getY(), head.getZ(), bot.getX(), bot.getY(), bot.getZ(), foot.getY(), before, dragon.getHealth(),
				dragon.getPhaseManager().getCurrentPhase().getPhase()));
		// Reste wegräumen, falls das Bett nicht explodiert ist.
		for (BlockPos pos : new BlockPos[] {foot, other}) {
			if (level.getBlockState(pos).getBlock() instanceof BedBlock) {
				level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
			}
		}
		left--;
		wait = interval;
		if (left == 0) {
			AiPlayerMod.LOGGER.info("bedprobe end done");
		}
	}
}
