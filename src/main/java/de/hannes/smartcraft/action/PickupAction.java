package de.hannes.smartcraft.action;

import com.google.gson.JsonObject;
import de.hannes.smartcraft.skill.Inv;
import de.hannes.smartcraft.skill.ItemGatherer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;

/** {@code pickup_items}: sammelt herumliegende Items im Umkreis ein. Schema: {@code schemas/commands/pickup_items.schema.json}. */
public final class PickupAction extends Action {
	private static final int MAX_TICKS = 1200;

	private final double radius;
	private ItemGatherer gatherer;
	private Map<String, Integer> before;

	public PickupAction(double radius) {
		this.radius = radius;
	}

	@Override
	public int timeoutTicks() {
		return MAX_TICKS + 100;
	}

	@Override
	public Outcome tick(ServerPlayer bot) {
		if (gatherer == null) {
			before = Inv.counts(bot);
			gatherer = new ItemGatherer(bot.blockPosition(), radius, MAX_TICKS);
		}
		if (gatherer.tick(bot) != ItemGatherer.State.DONE) {
			return null;
		}
		return gatherer.inventoryFull() ? Outcome.failed("inventory_full", progress(bot)) : Outcome.ok(progress(bot));
	}

	@Override
	public void release(ServerPlayer bot) {
		if (gatherer != null) {
			gatherer.release(bot);
		}
	}

	@Override
	public JsonObject progress(ServerPlayer bot) {
		JsonObject data = new JsonObject();
		if (before != null) {
			data.add("gained", Inv.gained(before, bot));
			data.addProperty("left_behind", gatherer.leftBehind());
		}
		return data;
	}
}
