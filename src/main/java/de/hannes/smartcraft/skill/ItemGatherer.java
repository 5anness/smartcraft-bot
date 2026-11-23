package de.hannes.smartcraft.skill;

import de.hannes.smartcraft.nav.Goal;
import de.hannes.smartcraft.nav.Moves;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Sammelt herumliegende Items ein: läuft der Reihe nach zum nächsten, bis keins mehr erreichbar
 * ist. Aufheben selbst macht das Spiel, sobald der Bot das Item berührt.
 */
public final class ItemGatherer {
	public enum State {
		RUNNING, DONE
	}

	/** Bleibt ein Item so lange liegen, obwohl der Bot daneben steht, wird es übersprungen. */
	private static final int WAIT_AT_ITEM_TICKS = 50;
	private static final int MAX_LANDING_TICKS = 40;
	private static final Moves.Options DIG = new Moves.Options(true, false);

	private final List<BlockPos> anchors;
	private final double radius;
	private final int maxTicks;
	private final Set<Integer> skipped = new HashSet<>();

	private ItemEntity target;
	private PathFollower follower;
	private int waitedAtItem;
	private int waitedForLanding;
	private int ticks;
	private boolean inventoryFull;
	private State state = State.RUNNING;
	private String skipReason;
	private boolean digging;

	/**
	 * @param anchor   Mittelpunkt der Suche
	 * @param maxTicks danach hört der Sammler auf, egal was noch liegt
	 */
	public ItemGatherer(BlockPos anchor, double radius, int maxTicks) {
		this(List.of(anchor), radius, maxTicks);
	}

	/** Sucht um mehrere Punkte herum, etwa um alle Stellen, an denen abgebaut wurde. */
	public ItemGatherer(List<BlockPos> anchors, double radius, int maxTicks) {
		this.anchors = List.copyOf(anchors);
		this.radius = radius;
		this.maxTicks = maxTicks;
	}

	/** Diese Items nicht ansteuern (z. B. Gold, das für einen Piglin reserviert ist). */
	public ItemGatherer skipping(Set<Integer> ids) {
		skipped.addAll(ids);
		return this;
	}

	/** Ob ein Item liegen blieb, weil im Inventar kein Platz mehr war. */
	public boolean inventoryFull() {
		return inventoryFull;
	}

	/** Items, an die der Bot nicht herankam. */
	public int leftBehind() {
		return skipped.size();
	}

	/** Warum zuletzt ein Item liegen blieb ({@code no_path}, {@code not_picked_up}, …), sonst null. */
	public String leftBehindReason() {
		return skipReason;
	}

	public State tick(ServerPlayer bot) {
		if (state == State.DONE) {
			return state;
		}
		if (++ticks > maxTicks) {
			return done(bot);
		}

		if (target == null || !target.isAlive()) {
			target = nextTarget(bot);
			follower = null;
			waitedAtItem = 0;
			if (target == null) {
				return done(bot);
			}
		}

		if (follower == null) {
			// Ein Item im Flug (gerade geworfen oder herausgefallen) hat noch keinen Platz, zu dem man laufen könnte.
			if (!target.onGround() && !target.isInWater() && ++waitedForLanding < MAX_LANDING_TICKS) {
				PathFollower.release(bot);
				return state;
			}
			waitedForLanding = 0;
			follower = new PathFollower(Goal.pickup(target.getX(), target.getY(), target.getZ()),
					digging ? DIG : Moves.Options.NONE);
		}
		switch (follower.tick(bot)) {
			case RUNNING -> {
			}
			case ARRIVED -> {
				// Frisch gefallene Items lassen sich erst nach einer halben Sekunde aufheben.
				if (++waitedAtItem > WAIT_AT_ITEM_TICKS) {
					boolean full = !Inv.hasRoomFor(bot, target.getItem());
					inventoryFull |= full;
					skip(full ? "inventory_full" : "not_picked_up");
				} else if (bot.distanceTo(target) > 3.0) {
					follower = null;
				}
			}
			case FAILED -> {
				// Kein freier Weg (oft Laub): ein zweiter Versuch, bei dem der Bot sich durchgraben darf.
				if (!digging && "no_path".equals(follower.failure())) {
					digging = true;
					follower = null;
				} else {
					skip(follower.failure());
				}
			}
		}
		return state;
	}

	public void release(ServerPlayer bot) {
		if (follower != null) {
			follower.abort(bot);
		}
	}

	private void skip(String reason) {
		skipReason = reason;
		skipped.add(target.getId());
		target = null;
		digging = false;
	}

	private State done(ServerPlayer bot) {
		PathFollower.release(bot);
		state = State.DONE;
		return state;
	}

	private ItemEntity nextTarget(ServerPlayer bot) {
		ItemEntity nearest = null;
		for (BlockPos anchor : anchors) {
			List<ItemEntity> items = bot.level().getEntitiesOfClass(ItemEntity.class, new AABB(anchor).inflate(radius),
					item -> item.isAlive() && !skipped.contains(item.getId()));
			for (ItemEntity item : items) {
				if (nearest == null || bot.distanceToSqr(item) < bot.distanceToSqr(nearest)) {
					nearest = item;
				}
			}
		}
		return nearest;
	}
}
