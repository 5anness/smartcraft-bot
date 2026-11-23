package de.hannes.aiplayer.action;

import com.google.gson.JsonObject;
import de.hannes.aiplayer.perception.GameJson;
import de.hannes.aiplayer.skill.Eater;
import de.hannes.aiplayer.skill.Inv;
import de.hannes.aiplayer.skill.PathFollower;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code eat}: isst ein Item aus dem Inventar, ohne Angabe das beste Essen.
 * Schema: {@code schemas/commands/eat.schema.json}.
 */
public final class EatAction extends Action {
	private final String wanted;
	private Eater eater;
	private int foodBefore;
	private boolean harmful;

	/** @param item Item-ID oder {@code null} für „das beste Essen“ */
	public EatAction(String item) {
		this.wanted = item == null ? null : Inv.fullId(item);
	}

	@Override
	public int timeoutTicks() {
		return 200;
	}

	@Override
	public boolean cancelOnDamage() {
		return false;
	}

	@Override
	public boolean cancelOnDanger() {
		// Essen dauert 1,6 s; Lava, die dabei in Sicht kommt, ist kein Grund, hungrig zu bleiben (Drachenlauf 8).
		return false;
	}

	@Override
	public Outcome tick(ServerPlayer bot) {
		if (eater == null) {
			foodBefore = bot.getFoodData().getFoodLevel();
			int slot;
			if (wanted != null) {
				slot = Inv.findItem(bot, wanted);
				if (slot < 0) {
					return Outcome.failed("missing_item", progress(bot));
				}
			} else {
				slot = Eater.bestFoodSlot(bot, true);
				if (slot < 0) {
					return Outcome.failed("no_food", progress(bot));
				}
			}
			harmful = Eater.isHarmful(bot.getInventory().getItem(slot));
			PathFollower.release(bot);
			eater = new Eater(slot);
		}
		return switch (eater.tick(bot)) {
			case RUNNING -> null;
			case DONE -> Outcome.ok(progress(bot));
			case FAILED -> {
				JsonObject data = progress(bot);
				if (eater.failure().equals("not_hungry")) {
					data.addProperty("hint", "food is full; only items like golden apples can be eaten then");
				}
				yield Outcome.failed(eater.failure(), data);
			}
		};
	}

	@Override
	public void release(ServerPlayer bot) {
		if (eater != null) {
			eater.abort(bot);
		}
	}

	@Override
	public JsonObject progress(ServerPlayer bot) {
		JsonObject data = new JsonObject();
		String item = eater != null && eater.itemId() != null ? eater.itemId() : wanted;
		if (item != null) {
			data.addProperty("ate", item);
			data.addProperty("left", Inv.count(bot, item));
		}
		data.addProperty("food_before", foodBefore);
		data.addProperty("food", bot.getFoodData().getFoodLevel());
		data.addProperty("saturation", GameJson.round1(bot.getFoodData().getSaturationLevel()));
		data.addProperty("health", GameJson.round1(bot.getHealth()));
		if (harmful) {
			data.addProperty("harmful", true);
		}
		return data;
	}
}
