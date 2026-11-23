package de.hannes.smartcraft.perception;

import com.google.gson.JsonNull;
import de.hannes.smartcraft.skill.Eater;
import de.hannes.smartcraft.skill.Inv;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.hannes.smartcraft.bot.BotManager;
import de.hannes.smartcraft.memory.Places;
import de.hannes.smartcraft.protocol.Priority;
import de.hannes.smartcraft.reflex.WaterGuard;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Die Sinne des Bots: hängt sich in die Spiel-Hooks, macht daraus Events und schickt sie
 * über den {@link EventBus}. Läuft komplett auf dem Server-Thread.
 */
public final class Perception {
	private static final int BLOCK_EVENT_RADIUS = 16;
	private static final int TOOL_CHECK_INTERVAL = 10;
	/** Unter diesem Anteil der Haltbarkeit gilt ein Werkzeug als fast kaputt. */
	private static final double LOW_DURABILITY = 0.1;
	/** Spätestens bei so vielen übrigen Schlägen warnen (Wunsch von Hannes: Ersatz craften, bevor es knapp wird). */
	private static final int LOW_DURABILITY_LEFT = 50;
	/** Mit 6 Hunger oder weniger kann ein Spieler nicht mehr sprinten. */
	private static final int LOW_FOOD = 6;
	private static final float LOW_HEALTH = 6.0f;
	/** Erst wenn das Leben wieder darüber war, wird erneut gewarnt; sonst käme im Kampf Event auf Event. */
	private static final float LOW_HEALTH_RESET = 8.0f;
	private static final int DAY_LENGTH = 24000;
	/** Sonnenuntergang beginnt; ab {@link #MONSTERS_START} erscheinen Monster an der Oberfläche. */
	private static final int EVENING_START = 11500;
	private static final int MONSTERS_START = 13000;
	private static final int SLEEP_START = 12542;
	private static final int GIFT_QUIET_TICKS = 20;

	// Für den Hook aus BlockItemMixin; Mixins können keine Instanzen übergeben bekommen.
	private static Perception instance;

	private final BotManager bots;
	private final EventBus bus;
	private final EventCoalescer coalescer = new EventCoalescer();
	private final SurroundingsScanner scanner = new SurroundingsScanner();
	private final FluidWatch fluidWatch = new FluidWatch();

	private Map<String, Integer> inventoryBeforeDeath = Map.of();
	/** Das Werkzeug, für das schon gewarnt wurde, damit die Warnung nur einmal kommt. */
	private ItemStack warnedTool = ItemStack.EMPTY;

	/** Zugeworfene Items, die noch gemeldet werden: Item-ID → Anzahl. */
	private final Map<String, Integer> gifts = new LinkedHashMap<>();
	private String giftFrom;
	private long giftTick;

	private boolean hungerWarned;
	private boolean healthWarned;
	private long nightAnnouncedDay = -1;

	private long measuredTicks;
	private long totalNanos;
	private long maxNanos;

	public Perception(BotManager bots, EventBus bus) {
		this.bots = bots;
		this.bus = bus;
	}

	public void register() {
		instance = this;

		// Läuft, nachdem der Server den Abbau bestätigt hat.
		PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
			// Abgebaute Werkbänke, Öfen und Truhen aus dem Ortsgedächtnis austragen.
			Places.forget(level, pos);
			if (player == bots.bot()) {
				WaterGuard.noteBroken(pos, level.getGameTime());
			}
			onBlockChanged("block_broken", level, player, pos, state);
		});

		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damageTaken, blocked) -> {
			if (entity == bots.bot() && damageTaken > 0) {
				onDamage(bots.bot(), source, damageTaken);
			}
		});
		ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
			if (entity == bots.bot()) {
				inventoryBeforeDeath = countItems(bots.bot());
			}
			return true;
		});
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity == bots.bot()) {
				onDeath(bots.bot(), source);
			}
		});

		// Chat von Spielern und /say (auch von der Konsole). Die eigenen Zeilen des Bots laufen hier ebenfalls durch.
		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, boundChatType) ->
				onChat(sender, sender.getGameProfile().name(), message.signedContent()));
		ServerMessageEvents.COMMAND_MESSAGE.register((message, source, boundChatType) ->
				onChat(source.getPlayer(), source.getTextName(), message.signedContent()));

		ServerTickEvents.END_SERVER_TICK.register(this::tick);
	}

	private void onChat(ServerPlayer sender, String from, String text) {
		ServerPlayer bot = bots.bot();
		if (bot == null || sender == bot || text.isBlank()) {
			return;
		}
		String botName = bot.getGameProfile().name().toLowerCase(Locale.ROOT);
		// Der Bot zählt mit: Bei höchstens einem weiteren Spieler kann nur er gemeint sein.
		boolean alone = bot.level().getServer().getPlayerList().getPlayers().size() <= 2;
		JsonObject data = new JsonObject();
		data.addProperty("from", from);
		data.addProperty("text", text.strip());
		data.addProperty("direct", alone || text.toLowerCase(Locale.ROOT).contains(botName));
		bus.emit("chat_received", bot.level().getGameTime(), Priority.NORMAL, data);
	}

	/** Aufgerufen von {@code BlockItemMixin}, nachdem ein Spieler einen Block gesetzt hat. */
	public static void onBlockPlaced(Level level, Player player, BlockPos pos) {
		Places.noteBlock(level, pos);
		if (instance != null && player != null) {
			instance.onBlockChanged("block_placed", level, player, pos, level.getBlockState(pos));
		}
	}

	/**
	 * Aufgerufen von {@code ItemEntityMixin}, wenn ein Spieler Items aufgehoben hat. Zählt nur, was ein
	 * anderer Spieler dem Bot zugeworfen hat; mehrere Würfe kurz nacheinander werden ein Event.
	 */
	public static void onItemPickedUp(Player player, Entity owner, String itemId, int count) {
		if (instance == null || player != instance.bots.bot() || !(owner instanceof Player giver) || giver == player) {
			return;
		}
		instance.giftFrom = giver.getGameProfile().name();
		instance.gifts.merge(itemId, count, Integer::sum);
		instance.giftTick = player.level().getGameTime();
	}

	/** Schema: {@code items_received}. Erst melden, wenn eine Sekunde lang nichts mehr dazukam. */
	private void flushGifts(long tick) {
		if (gifts.isEmpty() || tick - giftTick < GIFT_QUIET_TICKS) {
			return;
		}
		JsonObject data = new JsonObject();
		data.addProperty("from", giftFrom);
		JsonArray items = new JsonArray();
		gifts.forEach((id, count) -> items.add(GameJson.stack(id, count)));
		data.add("items", items);
		gifts.clear();
		bus.emit("items_received", tick, Priority.NORMAL, data);
	}

	/** Durchschnittliche Kosten der Wahrnehmung pro Server-Tick in Mikrosekunden. */
	public double averageMicros() {
		return measuredTicks == 0 ? 0 : totalNanos / 1000.0 / measuredTicks;
	}

	public double maxMicros() {
		return maxNanos / 1000.0;
	}

	private void tick(MinecraftServer server) {
		ServerPlayer bot = bots.bot();
		if (bot == null) {
			scanner.reset();
			fluidWatch.reset();
			coalescer.clear();
			return;
		}
		if (server.isPaused()) {
			return;
		}

		long start = System.nanoTime();
		long tick = bot.level().getGameTime();
		scanner.tick(bot, tick, bus);
		fluidWatch.tick(bot, tick, bus);
		if (tick % TOOL_CHECK_INTERVAL == 0) {
			checkTool(bot, tick);
			checkPickaxe(bot, tick);
			checkGoldArmor(bot, tick);
			checkShield(bot, tick);
			checkVitals(bot, tick);
			checkEvening(bot, tick);
		}
		coalescer.flush(tick, bus);
		flushGifts(tick);
		long nanos = System.nanoTime() - start;

		measuredTicks++;
		totalNanos += nanos;
		maxNanos = Math.max(maxNanos, nanos);
	}

	private ItemStack warnedShield = ItemStack.EMPTY;
	private static final int SHIELD_LOW_LEFT = 40;

	/**
	 * Meldet einmal, wenn der Schild in der zweiten Hand fast kaputt ist (als {@code tool_low_durability}). Drachenlauf 81: am
	 * Lohenspawner mit 18 von 336 Haltbarkeit, niemand wusste es, kurz darauf erschlagen.
	 */
	private void checkShield(ServerPlayer bot, long tick) {
		ItemStack shield = bot.getOffhandItem();
		if (!shield.is(net.minecraft.world.item.Items.SHIELD) || bot.isDeadOrDying()) {
			return;
		}
		int left = shield.getMaxDamage() - shield.getDamageValue();
		if (left > SHIELD_LOW_LEFT || shield == warnedShield) {
			return;
		}
		warnedShield = shield;
		JsonObject data = new JsonObject();
		data.addProperty("item", "minecraft:shield");
		data.addProperty("left", left);
		data.addProperty("max", shield.getMaxDamage());
		data.addProperty("hint", "shield almost broken: craft a new one (6 planks + 1 iron ingot) and equip it in the offhand");
		bus.emit("tool_low_durability", tick, Priority.HIGH, data);
	}

	private ItemStack warnedGold = ItemStack.EMPTY;
	private boolean woreGold;
	private static final int GOLD_LOW_LEFT = 20;

	/**
	 * Im Nether: Meldet einmal, wenn das getragene Goldteil fast kaputt oder zerbrochen ist (ohne Gold greifen Piglins an).
	 * Drachenlauf 66: nach 2 Stunden im Nether waren die Goldstiefel (91 Haltbarkeit) wohl hin, ein Piglin schlug viermal mit 8
	 * zu und stieß ihn von der Brücke. Kommt als {@code tool_low_durability} mit {@code gold_armor: true}.
	 */
	private void checkGoldArmor(ServerPlayer bot, long tick) {
		if (bot.level().dimension() != net.minecraft.world.level.Level.NETHER || bot.isDeadOrDying()) {
			woreGold = false;
			return;
		}
		ItemStack gold = ItemStack.EMPTY;
		for (net.minecraft.world.entity.EquipmentSlot slot : new net.minecraft.world.entity.EquipmentSlot[] {
				net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.entity.EquipmentSlot.CHEST,
				net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET}) {
			ItemStack worn = bot.getItemBySlot(slot);
			if (!worn.isEmpty() && BuiltInRegistries.ITEM.getKey(worn.getItem()).getPath().startsWith("golden_")) {
				gold = worn;
			}
		}
		int left = gold.isEmpty() ? 0 : gold.getMaxDamage() - gold.getDamageValue();
		boolean broken = gold.isEmpty() && woreGold;
		woreGold = !gold.isEmpty();
		if (!broken && (gold.isEmpty() || left > GOLD_LOW_LEFT || gold == warnedGold)) {
			return;
		}
		warnedGold = gold;
		JsonObject data = new JsonObject();
		data.addProperty("item", gold.isEmpty() ? "golden armor (broken)" : BuiltInRegistries.ITEM.getKey(gold.getItem()).toString());
		data.addProperty("left", left);
		data.addProperty("max", gold.isEmpty() ? 0 : gold.getMaxDamage());
		data.addProperty("gold_armor", true);
		data.addProperty("hint", "without a worn gold piece piglins attack (8 per hit). Put on a spare golden piece now (equip), or craft "
				+ "golden_helmet (5 gold) / golden_boots (4 gold); no gold: keep away from piglins and leave the Nether soon");
		bus.emit("tool_low_durability", tick, Priority.HIGH, data);
	}

	private boolean hadPickaxe;

	/**
	 * Meldet einmal, wenn die letzte Spitzhacke weg ist (zerbrochen, weggeworfen, nach dem Tod verloren), mit dem Rezept, das
	 * mit dem Inventar jetzt geht. Schema: {@code no_pickaxe}. Log-Auswertung 13.11.: In 31 von 169 Läufen scheiterten Befehle
	 * mit wrong_tool ohne Spitzhacke im Inventar; die KI versuchte es weiter mit mine und saß in Steinkammern fest.
	 */
	private void checkPickaxe(ServerPlayer bot, long tick) {
		if (bot.isDeadOrDying()) {
			return;
		}
		Inventory inventory = bot.getInventory();
		int planks = 0, logs = 0, sticks = 0, stone = 0, iron = 0, diamonds = 0;
		boolean pickaxe = false;
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.isEmpty()) {
				continue;
			}
			pickaxe |= stack.is(net.minecraft.tags.ItemTags.PICKAXES);
			int count = stack.getCount();
			if (stack.is(net.minecraft.tags.ItemTags.PLANKS)) {
				planks += count;
			} else if (stack.is(net.minecraft.tags.ItemTags.LOGS)) {
				logs += count;
			} else if (stack.is(net.minecraft.world.item.Items.STICK)) {
				sticks += count;
			} else if (stack.is(net.minecraft.tags.ItemTags.STONE_TOOL_MATERIALS)) {
				stone += count;
			} else if (stack.is(net.minecraft.world.item.Items.IRON_INGOT)) {
				iron += count;
			} else if (stack.is(net.minecraft.world.item.Items.DIAMOND)) {
				diamonds += count;
			}
		}
		boolean lost = hadPickaxe && !pickaxe;
		hadPickaxe = pickaxe;
		if (!lost) {
			return;
		}
		// Holz in Brettern gerechnet: 2 Stöcke brauchen 2 Bretter, eine Werkbank 4, eine Holzspitzhacke 3 dazu.
		int wood = planks + logs * 4 + sticks / 2;
		String best = diamonds >= 3 ? "diamond_pickaxe" : iron >= 3 ? "iron_pickaxe" : stone >= 3 ? "stone_pickaxe" : "wooden_pickaxe";
		int woodNeeded = best.equals("wooden_pickaxe") ? 5 : 2;
		String hint;
		if (wood >= woodNeeded) {
			hint = "no pickaxe left: craft " + best + " now (craft makes sticks and planks itself; needs a crafting table, "
					+ "place_utility crafting_table). Without a pickaxe stone cannot be mined and goto/tunnel with allow_dig fail";
		} else {
			hint = "no pickaxe left and not enough wood for " + best + ": collect logs first (count 3, wood needs no tool), "
					+ "then craft " + best + ". Without a pickaxe stone cannot be mined and goto/tunnel with allow_dig fail";
		}
		JsonObject data = new JsonObject();
		data.addProperty("craft", "minecraft:" + best);
		data.addProperty("enough_wood", wood >= woodNeeded);
		data.addProperty("hint", hint);
		bus.emit("no_pickaxe", tick, Priority.HIGH, data);
	}

	/** Meldet einmal, wenn das Werkzeug in der Hand fast kaputt ist. Schema: {@code tool_low_durability}. */
	private void checkTool(ServerPlayer bot, long tick) {
		ItemStack held = bot.getMainHandItem();
		if (!held.isDamageableItem()) {
			return;
		}
		int left = held.getMaxDamage() - held.getDamageValue();
		if (left > Math.max(Math.min(LOW_DURABILITY_LEFT, held.getMaxDamage() / 2), held.getMaxDamage() * LOW_DURABILITY)) {
			if (held == warnedTool) {
				warnedTool = ItemStack.EMPTY;
			}
			return;
		}
		if (held == warnedTool) {
			return;
		}
		warnedTool = held;
		JsonObject data = new JsonObject();
		data.addProperty("item", BuiltInRegistries.ITEM.getKey(held.getItem()).toString());
		data.addProperty("left", left);
		data.addProperty("max", held.getMaxDamage());
		bus.emit("tool_low_durability", tick, Priority.NORMAL, data);
	}

	/** Meldet einmal, wenn Hunger oder Leben niedrig sind. Schemas: {@code hunger_low}, {@code health_low}. */
	private void checkVitals(ServerPlayer bot, long tick) {
		if (bot.isDeadOrDying()) {
			hungerWarned = false;
			healthWarned = false;
			return;
		}
		int food = bot.getFoodData().getFoodLevel();
		float health = bot.getHealth();
		if (food > LOW_FOOD) {
			hungerWarned = false;
		} else if (!hungerWarned) {
			hungerWarned = true;
			bus.emit("hunger_low", tick, Priority.HIGH, vitals(bot));
		}
		if (health > LOW_HEALTH_RESET) {
			healthWarned = false;
		} else if (health <= LOW_HEALTH && !healthWarned) {
			healthWarned = true;
			bus.emit("health_low", tick, Priority.HIGH, vitals(bot));
		}
	}

	private static JsonObject vitals(ServerPlayer bot) {
		JsonObject data = new JsonObject();
		data.addProperty("health", GameJson.round1(bot.getHealth()));
		data.addProperty("food", bot.getFoodData().getFoodLevel());
		int slot = Eater.bestFoodSlot(bot, false);
		if (slot >= 0) {
			data.addProperty("best_food", Inv.itemId(bot.getInventory().getItem(slot)));
		} else {
			data.add("best_food", JsonNull.INSTANCE);
		}
		return data;
	}

	/** Meldet einmal je Abend, dass die Nacht kommt. Schema: {@code night_falling}. */
	private void checkEvening(ServerPlayer bot, long tick) {
		if (bot.level().dimension() != Level.OVERWORLD) {
			return;
		}
		long clock = bot.level().getOverworldClockTime();
		long day = Math.floorDiv(clock, (long) DAY_LENGTH);
		int time = (int) Math.floorMod(clock, (long) DAY_LENGTH);
		if (time < EVENING_START || time >= MONSTERS_START || day == nightAnnouncedDay) {
			return;
		}
		nightAnnouncedDay = day;
		JsonObject data = new JsonObject();
		data.addProperty("time", time);
		data.addProperty("monsters_in_ticks", MONSTERS_START - time);
		data.addProperty("can_sleep_in_ticks", Math.max(0, SLEEP_START - time));
		// Unter der Erde ändert die Nacht nichts; die KI soll sich dort nicht einbauen.
		data.addProperty("underground", !bot.level().canSeeSky(bot.blockPosition().above()));
		bus.emit("night_falling", tick, Priority.NORMAL, data);
	}

	private void onBlockChanged(String name, Level level, Player player, BlockPos pos, BlockState state) {
		ServerPlayer bot = bots.bot();
		if (bot == null || level != bot.level()) {
			return;
		}
		boolean self = player == bot;
		if (!self && !pos.closerThan(bot.blockPosition(), BLOCK_EVENT_RADIUS)) {
			return;
		}

		String block = GameJson.blockId(state);
		JsonObject data = new JsonObject();
		data.addProperty("block", block);
		data.add("pos", GameJson.pos(pos));
		data.addProperty("by", self ? "self" : player.getGameProfile().name());

		long tick = level.getGameTime();
		if (self) {
			// Eigene Blöcke sind Rückmeldung auf eine Aktion und gehen immer einzeln raus.
			data.add("held_item", GameJson.stack(bot.getMainHandItem()));
			bus.emit(name, tick, Priority.NORMAL, data);
		} else if (coalescer.admit(name + '|' + data.get("by").getAsString() + '|' + block, tick, name, Priority.NORMAL, data, 0)) {
			bus.emit(name, tick, Priority.NORMAL, data);
		}
	}

	private void onDamage(ServerPlayer bot, DamageSource source, float amount) {
		String cause = damageType(source);
		JsonObject data = new JsonObject();
		data.addProperty("amount", GameJson.round1(amount));
		data.addProperty("source", cause);
		Entity attacker = source.getEntity();
		if (attacker != null) {
			data.addProperty("attacker", GameJson.entityType(attacker));
			data.addProperty("attacker_dir", Compass.towards(attacker.getX() - bot.getX(), attacker.getZ() - bot.getZ()));
		}
		data.addProperty("health", GameJson.round1(bot.getHealth()));

		long tick = bot.level().getGameTime();
		if (coalescer.admit("damage_taken|" + cause, tick, "damage_taken", Priority.HIGH, data, amount)) {
			bus.emit("damage_taken", tick, Priority.HIGH, data);
		}
	}

	private void onDeath(ServerPlayer bot, DamageSource source) {
		Map<String, Integer> lost = new LinkedHashMap<>(inventoryBeforeDeath);
		countItems(bot).forEach((id, count) -> lost.computeIfPresent(id, (key, before) -> before > count ? before - count : null));
		inventoryBeforeDeath = Map.of();

		Entity attacker = source.getEntity();
		DeathRecord death = new DeathRecord(
				damageType(source),
				source.getLocalizedDeathMessage(bot).getString(),
				attacker != null ? GameJson.entityType(attacker) : null,
				bot.blockPosition(),
				bot.level().dimension().identifier().toString(),
				lost,
				bot.level().getGameTime());
		bots.recordDeath(death);

		JsonObject data = new JsonObject();
		data.addProperty("cause", death.cause());
		data.addProperty("message", death.message());
		if (death.attacker() != null) {
			data.addProperty("attacker", death.attacker());
		}
		data.add("pos", GameJson.pos(death.pos()));
		data.addProperty("dimension", death.dimension());
		JsonArray items = new JsonArray();
		lost.forEach((id, count) -> items.add(GameJson.stack(id, count)));
		data.add("lost_items", items);
		bus.emit("player_died", death.tick(), Priority.HIGH, data);
	}

	private static String damageType(DamageSource source) {
		return source.typeHolder().unwrapKey()
				.map(key -> key.identifier().toString())
				.orElseGet(source::getMsgId);
	}

	private static Map<String, Integer> countItems(ServerPlayer player) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		Inventory inventory = player.getInventory();
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (!stack.isEmpty()) {
				counts.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
			}
		}
		return counts;
	}
}
