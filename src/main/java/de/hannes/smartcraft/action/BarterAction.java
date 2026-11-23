package de.hannes.smartcraft.action;

import com.google.gson.JsonObject;
import de.hannes.smartcraft.nav.Goal;
import de.hannes.smartcraft.nav.Moves;
import de.hannes.smartcraft.nav.Pos;
import de.hannes.smartcraft.skill.Combat;
import de.hannes.smartcraft.skill.Inv;
import de.hannes.smartcraft.skill.ItemGatherer;
import de.hannes.smartcraft.skill.PathFollower;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code barter}: tauscht Goldbarren mit Piglins in der Nähe. Jeder Barren wird einem freien Piglin zugeworfen und
 * nur für ihn reserviert ({@link ItemEntity#setTarget}); vorher fiel er vor die Füße des Bots, und der hob ihn
 * selbst wieder auf (Perlen-Lauf 2: 15 Würfe, 1 Perle). Schema: {@code schemas/commands/barter.schema.json}.
 */
public final class BarterAction extends Action {
	private static final String GOLD = "minecraft:gold_ingot";
	private static final double RADIUS = 16;
	private static final double THROW_DISTANCE = 3.5;
	/** So lange gilt ein Piglin nach einem Wurf als beschäftigt (Bewundern dauert etwa 6 s). */
	private static final int BUSY_TICKS = 200;
	private static final int MAX_TICKS = 20 * 300;
	/** So lange wartet der Bot am Ende auf die letzte Ware. */
	private static final int FINISH_TICKS = 160;
	private static final int LOOT_RADIUS = 8;
	private static final double BRUTE_RADIUS = 24;
	/** Graben und Bauen erlaubt: Perlen-Lauf 11 kam viermal mit piglins_unreachable nicht an Piglins 7 Blöcke tiefer heran. */
	private static final Moves.Options REACH = new Moves.Options(true, true);

	private final int count;
	private final Map<Integer, Integer> busySince = new HashMap<>();
	private final Set<Integer> unreachable = new HashSet<>();
	private final Set<Integer> thrown = new HashSet<>();
	private Map<String, Integer> before;
	private int given;
	private int ticks;
	private int doneSince = -1;
	private Piglin target;
	private PathFollower follower;
	private ItemGatherer gatherer;

	public BarterAction(int count) {
		this.count = count;
	}

	@Override
	public boolean cancelOnDangerInNether() {
		return false;
	}

	@Override
	public int timeoutTicks() {
		return MAX_TICKS + 200;
	}

	@Override
	public Outcome tick(ServerPlayer bot) {
		ticks++;
		if (before == null) {
			before = Inv.counts(bot);
			if (Inv.count(bot, GOLD) == 0) {
				return Outcome.failed("missing_item", progress(bot));
			}
		}
		if (ticks > MAX_TICKS) {
			return Outcome.ok(progress(bot));
		}
		if (gatherer != null) {
			if (gatherer.tick(bot) != ItemGatherer.State.DONE) {
				return null;
			}
			gatherer = null;
		}
		List<Piglin> piglins = bot.level().getEntitiesOfClass(Piglin.class, bot.getBoundingBox().inflate(RADIUS),
				p -> p.isAlive() && !p.isBaby());
		// Brutes greifen immer an; wo einer ist, ist eine Bastion. Perlen-Lauf 11: Ein Brute erschlug ihn beim Tauschen.
		var brute = bot.level().getEntitiesOfClass(net.minecraft.world.entity.monster.piglin.PiglinBrute.class,
				bot.getBoundingBox().inflate(BRUTE_RADIUS), net.minecraft.world.entity.LivingEntity::isAlive);
		if (!brute.isEmpty()) {
			JsonObject data = progress(bot);
			data.add("brute", de.hannes.smartcraft.skill.Combat.describe(brute.get(0)));
			data.addProperty("hint", "a piglin brute is near (bastion); do not fight it: shelter or leave 40+ blocks, then barter elsewhere");
			return Outcome.failed("brute_nearby", data);
		}
		for (Piglin piglin : piglins) {
			if (piglin.getTarget() == bot) {
				JsonObject data = progress(bot);
				data.addProperty("hint", "piglins attack players without gold armor; wear a golden piece (equip), then barter");
				return Outcome.failed("piglins_angry", data);
			}
		}
		if (piglins.isEmpty() && busy() == 0) {
			if (given > 0) {
				return Outcome.ok(progress(bot));
			}
			// Perlen-Lauf 7: Ein Piglin stand daneben, es war ein Baby; die KI verstand das no_piglin nicht.
			JsonObject data = progress(bot);
			int babies = bot.level().getEntitiesOfClass(Piglin.class, bot.getBoundingBox().inflate(RADIUS),
					p -> p.isAlive() && p.isBaby()).size();
			data.addProperty("babies_nearby", babies);
			data.addProperty("hint", "no adult piglin within " + (int) RADIUS + " blocks" + (babies > 0 ? " (babies do not barter)" : "")
					+ "; locate crimson_forest or nether_wastes and go there");
			return Outcome.failed("no_piglin", data);
		}

		boolean canGive = given < count && Inv.count(bot, GOLD) > 0;
		Piglin free = canGive ? piglins.stream().filter(this::isFree)
				.min(Comparator.comparingDouble(bot::distanceToSqr)).orElse(null) : null;
		if (free != null) {
			doneSince = -1;
			return approachAndThrow(bot, free);
		}
		PathFollower.release(bot);
		if (canGive && !piglins.isEmpty() && busy() == 0
				&& piglins.stream().allMatch(p -> unreachable.contains(p.getId()))) {
			return given == 0 ? Outcome.failed("piglins_unreachable", progress(bot)) : Outcome.ok(progress(bot));
		}
		// Ware einsammeln: Piglins werfen sie in Richtung des Spielers.
		if (ticks % 20 == 0 && lootNearby(bot)) {
			gatherer = new ItemGatherer(bot.blockPosition(), LOOT_RADIUS, 200).skipping(thrown);
			return null;
		}
		if (!canGive && busy() == 0) {
			if (doneSince < 0) {
				doneSince = ticks;
			}
			if (ticks - doneSince > FINISH_TICKS && !lootNearby(bot)) {
				return Outcome.ok(progress(bot));
			}
		}
		return null;
	}

	private Outcome approachAndThrow(ServerPlayer bot, Piglin piglin) {
		if (bot.distanceTo(piglin) > THROW_DISTANCE) {
			if (follower == null || target != piglin || ticks % 40 == 0) {
				target = piglin;
				follower = new PathFollower(Goal.near(new Pos(piglin.getBlockX(), piglin.getBlockY(), piglin.getBlockZ()), 2.5),
						REACH);
			}
			if (follower.tick(bot) == PathFollower.State.FAILED) {
				unreachable.add(piglin.getId());
				follower = null;
			}
			return null;
		}
		PathFollower.release(bot);
		follower = null;
		Combat.face(bot, piglin);
		int slot = Inv.findItem(bot, GOLD);
		if (slot < 0) {
			return null;
		}
		ItemStack one = bot.getInventory().removeItem(slot, 1);
		ItemEntity item = bot.drop(one, false, true);
		if (item != null) {
			// Nur dieser Piglin darf ihn aufheben; der Bot selbst nicht mehr.
			item.setTarget(piglin.getUUID());
			item.setPickUpDelay(10);
			Vec3 toward = piglin.position().subtract(bot.position());
			double length = Math.max(0.1, toward.horizontalDistance());
			item.setDeltaMovement(toward.x / length * 0.2, 0.2, toward.z / length * 0.2);
			thrown.add(item.getId());
		}
		busySince.put(piglin.getId(), ticks);
		given++;
		return null;
	}

	/** Hält kein Gold, hat keinen Barren unterwegs und ist erreichbar. */
	private boolean isFree(Piglin piglin) {
		Integer since = busySince.get(piglin.getId());
		return piglin.getOffhandItem().isEmpty() && !unreachable.contains(piglin.getId())
				&& (since == null || ticks - since > BUSY_TICKS);
	}

	private int busy() {
		return (int) busySince.values().stream().filter(since -> ticks - since <= BUSY_TICKS).count();
	}

	private boolean lootNearby(ServerPlayer bot) {
		return !bot.level().getEntitiesOfClass(ItemEntity.class, new AABB(bot.blockPosition()).inflate(LOOT_RADIUS),
				item -> item.isAlive() && !thrown.contains(item.getId()) && item.onGround()).isEmpty();
	}

	@Override
	public void release(ServerPlayer bot) {
		PathFollower.release(bot);
		if (gatherer != null) {
			gatherer.release(bot);
		}
	}

	@Override
	public JsonObject progress(ServerPlayer bot) {
		JsonObject data = new JsonObject();
		data.addProperty("gold_given", given);
		data.addProperty("gold_left", Inv.count(bot, GOLD));
		data.addProperty("ender_pearls", Inv.count(bot, "minecraft:ender_pearl"));
		if (before != null) {
			data.add("gained", Inv.gained(before, bot));
		}
		return data;
	}
}
