package de.hannes.smartcraft.action;

import de.hannes.smartcraft.skill.Combat;
import de.hannes.smartcraft.skill.Eater;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import java.util.Comparator;
import java.util.ArrayList;
import de.hannes.smartcraft.memory.Places;
import de.hannes.smartcraft.skill.Recipes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import java.util.List;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import de.hannes.smartcraft.SmartCraftMod;
import de.hannes.smartcraft.bot.BotManager;
import de.hannes.smartcraft.bridge.BridgeServer;
import de.hannes.smartcraft.debug.DebugLog;
import de.hannes.smartcraft.nav.Moves;
import de.hannes.smartcraft.nav.Pos;
import de.hannes.smartcraft.perception.GameJson;
import de.hannes.smartcraft.perception.EventBus;
import de.hannes.smartcraft.perception.Snapshot;
import de.hannes.smartcraft.protocol.Command;
import de.hannes.smartcraft.protocol.Event.Status;
import de.hannes.smartcraft.protocol.Priority;
import de.hannes.smartcraft.skill.Inv;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import java.util.function.Predicate;

/**
 * Nimmt Befehle vom Begleitprozess an, prüft die Argumente und beantwortet jeden Befehl mit
 * genau einem {@code <befehl>_result}. Aktionen mit Dauer gehen an den {@link ActionRunner}.
 * Läuft auf dem Server-Thread. Die Befehle und ihre Fehlergründe stehen in ARCHITECTURE.md.
 */
public final class CommandDispatcher {
	/** Wird nur über {@code /smartcraft locate on} gesetzt; nach einem Neustart wieder aus. */
	public static boolean locateEnabled = false;
	/** Suchradius von {@code locate} in Chunks (1.600 Blöcke), so weit wie es {@code /locate} sinnvoll schafft. */
	private static final int LOCATE_CHUNKS = 100;

	private record LocateTarget(ResourceKey<Structure> key, ResourceKey<Level> dimension, boolean underground) {
	}

	private static final Map<String, LocateTarget> LOCATE_TARGETS = Map.of(
			"fortress", new LocateTarget(BuiltinStructures.FORTRESS, Level.NETHER, true),
			"bastion", new LocateTarget(BuiltinStructures.BASTION_REMNANT, Level.NETHER, false),
			"stronghold", new LocateTarget(BuiltinStructures.STRONGHOLD, Level.OVERWORLD, true),
			"end_city", new LocateTarget(BuiltinStructures.END_CITY, Level.END, false));

	/** Nether-Biome für {@code locate}: Piglins (Karmesinwald, Ödland), Endermen (Wirrwald). Perlen-Lauf 5: 15 min ohne Piglin. */
	private static final Map<String, net.minecraft.resources.ResourceKey<net.minecraft.world.level.biome.Biome>> LOCATE_BIOMES = Map.of(
			"crimson_forest", net.minecraft.world.level.biome.Biomes.CRIMSON_FOREST,
			"warped_forest", net.minecraft.world.level.biome.Biomes.WARPED_FOREST,
			"nether_wastes", net.minecraft.world.level.biome.Biomes.NETHER_WASTES);
	private static final int LOCATE_BIOME_RADIUS = 800;

	private static final int MAX_LOGGED_LINE = 200;
	private static final double MAX_TOLERANCE = 16;
	/** goto: weite Ziele (Festung, Wald) bekommen oft eine grobe Toleranz; 16 lehnte die KI in den Festungsläufen ständig ab. */
	private static final double MAX_GOTO_TOLERANCE = 64;
	/** use mit pos: weiter weg wird abgelehnt (der Befehl läuft sonst selbst hin). */
	private static final double MAX_USE_DISTANCE = 24;
	private static final int DEFAULT_FOLLOW_SECONDS = 120;
	private static final int MAX_FOLLOW_SECONDS = 600;
	private static final int MAX_COLLECT_COUNT = 64;
	private static final int MAX_COLLECT_RADIUS = 48;
	private static final int MAX_BUCKET_RADIUS = 32;
	private static final int MAX_TUNNEL_LENGTH = 200;
	private static final int MAX_SHOTS = 32;
	private static final int MAX_PICKUP_RADIUS = 32;
	private static final int MAX_CHAT_LENGTH = 256;

	private static final Map<String, EquipmentSlot> EQUIP_SLOTS = Map.of(
			"hand", EquipmentSlot.MAINHAND, "offhand", EquipmentSlot.OFFHAND,
			"head", EquipmentSlot.HEAD, "chest", EquipmentSlot.CHEST,
			"legs", EquipmentSlot.LEGS, "feet", EquipmentSlot.FEET);

	/** Ein Argument fehlt oder passt nicht; {@link #getMessage()} sagt welches. */
	private static final int MAX_CRAFT_COUNT = 256;
	private static final int ATTACK_SEARCH_RADIUS = 32;
	/** So weit sucht {@code attack} nach einem Hinweis, wenn im Angriffsradius nichts ist (nur geladene Welt). */
	private static final int ATTACK_HINT_RADIUS = 128;
	private static final double DEFAULT_MIN_HEALTH = 6;
	private static final EquipmentSlot[] ARMOR_SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
	private static final int MAX_RECIPES_LISTED = 2;
	private static final int MAX_SMELT_SOURCES_LISTED = 6;

	private static final class InvalidArgs extends Exception {
		InvalidArgs(String hint) {
			super(hint);
		}
	}

	private final BridgeServer bridge;
	private final BotManager bots;
	private final EventBus bus;
	private final DebugLog debugLog;
	private final ActionRunner runner;

	public CommandDispatcher(BridgeServer bridge, BotManager bots, EventBus bus, DebugLog debugLog, ActionRunner runner) {
		this.bridge = bridge;
		this.bots = bots;
		this.bus = bus;
		this.debugLog = debugLog;
		this.runner = runner;
	}

	public void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> bridge.start());
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> bridge.stop());
		ServerTickEvents.START_SERVER_TICK.register(this::tick);
		bus.subscribe(event -> bridge.send(event.toJson().toString()));
	}

	private void tick(MinecraftServer server) {
		long tick = server.overworld().getGameTime();

		if (bridge.consumeClientJustConnected()) {
			ServerPlayer bot = bots.bot();
			JsonObject data = new JsonObject();
			data.addProperty("mod_version", FabricLoader.getInstance().getModContainer(SmartCraftMod.MOD_ID)
					.map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("?"));
			if (bot != null) {
				data.addProperty("bot", bot.getGameProfile().name());
			} else {
				data.add("bot", JsonNull.INSTANCE);
			}
			bus.emit("bridge_connected", tick, Priority.NORMAL, data);
		}

		String line;
		while ((line = bridge.poll()) != null) {
			handle(line, tick);
		}
	}

	private void handle(String line, long tick) {
		Command command;
		try {
			command = Command.parse(line);
		} catch (Command.InvalidCommandException e) {
			JsonObject data = new JsonObject();
			data.addProperty("reason", e.getMessage());
			data.addProperty("line", line.length() > MAX_LOGGED_LINE ? line.substring(0, MAX_LOGGED_LINE) : line);
			bus.emit("protocol_error", tick, Priority.NORMAL, data);
			return;
		}

		JsonObject logged = new JsonObject();
		logged.addProperty("id", command.id());
		logged.addProperty("name", command.name());
		logged.add("args", command.args());
		debugLog.write("command_in", tick, logged);

		if (command.name().equals("ping")) {
			bus.emitResult(command, tick, Status.OK, null, new JsonObject());
			return;
		}
		// Einstellungen gehen auch ohne Bot: Der Begleitprozess holt sie beim Verbinden (und setzt Startwerte aus seiner
		// Umgebung, etwa SMARTCRAFT_TTS).
		if (command.name().equals("settings")) {
			try {
				de.hannes.smartcraft.bot.AiSettings.apply(command.args());
			} catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException e) {
				fail(command, tick, "invalid_args", e.getMessage());
				return;
			}
			JsonObject settings = de.hannes.smartcraft.bot.AiSettings.toJson();
			var pending = de.hannes.smartcraft.bot.AiSettings.pendingApiKeys;
			if (!pending.isEmpty()) {
				// Vor dem Verbinden gesetzt (/smartcraft apikey): einmal mitgeben; das Log maskiert sie (EventBus).
				JsonObject keys = new JsonObject();
				pending.forEach((provider, key) -> keys.addProperty(provider.id(), key));
				settings.add("api_keys", keys);
				pending.clear();
			}
			bus.emitResult(command, tick, Status.OK, null, settings);
			return;
		}

		ServerPlayer bot = bots.bot();
		if (bot == null) {
			fail(command, tick, "no_bot", null);
			return;
		}

		try {
			execute(command, bot, tick);
		} catch (InvalidArgs e) {
			fail(command, tick, "invalid_args", e.getMessage());
		}
	}

	/**
	 * In der Oberwelt: was für den Nether noch fehlt (dieselbe Prüfung wie build_portal/enter_portal). Hannes' Lauf 28.10.:
	 * Der Bot hatte alles bis auf den Bogen, schmolz aber 16 Eisen und wusste nicht, was der Mod verlangt.
	 */
	private static JsonObject withNetherCheck(ServerPlayer bot, JsonObject data) {
		if (bot.level().dimension() == net.minecraft.world.level.Level.OVERWORLD) {
			com.google.gson.JsonArray missing = de.hannes.smartcraft.skill.NetherKit.missingAll(bot);
			if (de.hannes.smartcraft.skill.Inv.count(bot, "minecraft:obsidian") < 10) {
				missing.add("obsidian for the portal (10; make_obsidian)");
			}
			if (missing.isEmpty()) {
				data.addProperty("nether_ready", true);
			} else {
				data.add("nether_missing", missing);
			}
		}
		// Vor dem End (Oberwelt und Nether): was zum Drachen fehlt.
		if (bot.level().dimension() != net.minecraft.world.level.Level.END) {
			com.google.gson.JsonArray end = de.hannes.smartcraft.skill.NetherKit.endMissing(bot);
			if (end.isEmpty()) {
				data.addProperty("end_ready", true);
			} else {
				data.add("end_missing", end);
			}
		}
		return data;
	}

	private void execute(Command command, ServerPlayer bot, long tick) throws InvalidArgs {
		JsonObject args = command.args();
		switch (command.name()) {
			case "look" -> bus.emitResult(command, tick, Status.OK, null, withNetherCheck(bot, Snapshot.of(bot)));

			case "stop" -> {
				String stopped = runner.stop(bot, tick);
				JsonObject data = new JsonObject();
				if (stopped != null) {
					data.addProperty("stopped", stopped);
				} else {
					data.add("stopped", JsonNull.INSTANCE);
				}
				bus.emitResult(command, tick, Status.OK, null, data);
			}

			case "usage" -> {
				// Verbrauch der KI für die XP-Leiste (Wunsch von Hannes); kein Spielzug, sofort fertig.
				de.hannes.smartcraft.bot.UsageDisplay.update(Math.max(0, args.get("tokens").getAsLong()),
						Math.max(0, args.get("cost").getAsDouble()));
				bus.emitResult(command, tick, Status.OK, null, new JsonObject());
			}
			case "chat" -> {
				// Eine Zeile, ohne Steuerzeichen: Der Chat nimmt sonst nichts an.
				String text = string(args, "text").replaceAll("[\\p{Cntrl}§]", " ").strip();
				if (text.isEmpty() || text.length() > MAX_CHAT_LENGTH) {
					throw new InvalidArgs("text must have 1 to " + MAX_CHAT_LENGTH + " characters");
				}
				// Als Systemnachricht im Format „<Claude> Text“: Eine unsignierte Spieler-Nachricht zeigt der Client bei
				// online-mode/enforce-secure-profile als „Chatübertragungsfehler“ (Hannes, 01.11.2026), der Bot hat keine Signatur.
				bot.level().getServer().getPlayerList().broadcastSystemMessage(
						net.minecraft.network.chat.Component.translatable("chat.type.text", bot.getDisplayName(),
								net.minecraft.network.chat.Component.literal(text)), false);
				JsonObject data = new JsonObject();
				data.addProperty("text", text);
				bus.emitResult(command, tick, Status.OK, null, data);
			}

			case "goto" -> {
				Pos goal = pos(args, "pos");
				double tolerance = number(args, "tolerance", 1, 0, MAX_GOTO_TOLERANCE);
				Moves.Options options = new Moves.Options(flag(args, "allow_dig"), flag(args, "allow_place"));
				if (bot.isDeadOrDying()) {
					fail(command, tick, "bot_died", null);
					return;
				}
				runner.start(command, new GotoAction(bot, goal, tolerance, options), bot, tick);
			}

			case "mine" -> startIfAlive(command, bot, tick, new MineAction(blockPos(args, "pos")));

			case "place" -> startIfAlive(command, bot, tick, new PlaceAction(string(args, "block"), blockPos(args, "pos")));

			case "bucket" -> {
				String action = string(args, "action");
				if (!action.equals("fill") && !action.equals("empty")) {
					throw new InvalidArgs("action must be fill or empty");
				}
				String fluid = args.has("fluid") ? string(args, "fluid") : null;
				if (fluid != null && !fluid.equals("lava") && !fluid.equals("water")) {
					throw new InvalidArgs("fluid must be lava or water");
				}
				BlockPos target = args.has("pos") ? blockPos(args, "pos") : null;
				boolean fill = action.equals("fill");
				if (target == null && (!fill || fluid == null)) {
					throw new InvalidArgs(fill ? "fill needs pos or fluid" : "empty needs pos");
				}
				startIfAlive(command, bot, tick, new BucketAction(fill, fluid, target,
						(int) number(args, "radius", 16, 1, MAX_BUCKET_RADIUS), flag(args, "anywhere")));
			}

			case "use" -> {
				// Essen und Trinken brauchen gedrückt halten; ein Rechtsklick mit use tat nichts und meldete trotzdem ok
				// (Nether-Lauf 1: viermal use golden_apple im Feuer, nichts gegessen). Deshalb wie eat behandeln.
				if (!args.has("pos") && !args.has("entity") && args.has("item")) {
					net.minecraft.world.item.Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM
							.getValue(net.minecraft.resources.Identifier.parse(Inv.fullId(string(args, "item"))));
					if (new net.minecraft.world.item.ItemStack(item).has(net.minecraft.core.component.DataComponents.CONSUMABLE)) {
						startIfAlive(command, bot, tick, new EatAction(string(args, "item")));
						return;
					}
				}
				BlockPos target = args.has("pos") ? blockPos(args, "pos") : null;
				String entity = args.has("entity") ? string(args, "entity") : null;
				if (target != null && entity != null) {
					throw new InvalidArgs("give pos or entity, not both");
				}
				// Festungslauf 34: use mit pos [-4,-1,4] (gemeint relativ) lief 180 Blöcke zum Weltursprung ins Meer, der Bot
				// ertrank. pos sind Weltkoordinaten; weit weg heißt fast immer ein Rechenfehler: erst hingehen.
				if (target != null && Math.sqrt(target.distToCenterSqr(bot.position())) > MAX_USE_DISTANCE) {
					throw new InvalidArgs("pos is " + (int) Math.sqrt(target.distToCenterSqr(bot.position()))
							+ " blocks away; pos is in world coordinates (you are at " + bot.blockPosition().toShortString()
							+ "); goto it first if you really mean it");
				}
				Direction face = null;
				if (args.has("face")) {
					face = Direction.byName(string(args, "face"));
					if (face == null) {
						throw new InvalidArgs("face must be up, down, north, south, east or west");
					}
				}
				Vec3 lookAt = null;
				if (args.has("look_at")) {
					Pos look = pos(args, "look_at");
					lookAt = new Vec3(look.x() + 0.5, look.y() + 0.5, look.z() + 0.5);
				}
				// Drachenlauf 62: Enderperle von einer Nether-Brücke ins Ungewisse geworfen, Landung im Lavasee, tot. Ohne festen
				// Boden (bis 12 Blöcke unter dem Zielpunkt, keine Lava) wird nicht geworfen; force wirft trotzdem.
				if (args.has("item") && Inv.fullId(string(args, "item")).equals("minecraft:ender_pearl") && !flag(args, "force")) {
					String unsafe = lookAt == null ? "give look_at: the spot where the pearl should land"
							: pearlFlight(bot, lookAt);
					if (unsafe != null) {
						fail(command, tick, "unsafe_landing", unsafe + "; force throws anyway (only to flee certain death)");
						return;
					}
				}
				startIfAlive(command, bot, tick, new UseAction(args.has("item") ? string(args, "item") : null,
						target, face, entity, lookAt, flag(args, "sneak")));
			}

			case "make_obsidian" -> startIfAlive(command, bot, tick, new MakeObsidianAction(
					(int) number(args, "count", 10, 1, 64), (int) number(args, "radius", 16, 4, 24)));

			case "farm_endermen" -> startIfAlive(command, bot, tick, new FarmEndermenAction(
					(int) number(args, "count", 12, 1, 24), (float) number(args, "min_health", 8, 0, 20)));

			case "barter" -> startIfAlive(command, bot, tick, new BarterAction((int) number(args, "count", 16, 1, 128)));

			case "pickup_items" -> startIfAlive(command, bot, tick,
					new PickupAction(number(args, "radius", 8, 1, MAX_PICKUP_RADIUS)));

			case "collect" -> {
				String spec = string(args, "block");
				int count = (int) number(args, "count", 1, 1, MAX_COLLECT_COUNT);
				int radius = (int) number(args, "radius", 32, 1, MAX_COLLECT_RADIUS);
				Predicate<BlockState> matches = blockMatcher(spec);
				if (matches == null) {
					fail(command, tick, "unknown_block", "no block or tag named '" + spec + "'; tags start with #, e.g. #logs");
					return;
				}
				startIfAlive(command, bot, tick, new CollectAction(matches, spec, count, radius,
						new Moves.Options(flag(args, "allow_dig"), flag(args, "allow_place"))));
			}

			case "craft" -> startIfAlive(command, bot, tick,
					new CraftAction(string(args, "item"), (int) number(args, "count", 1, 1, MAX_CRAFT_COUNT)));

			case "recipe" -> recipe(command, bot, tick, string(args, "item"));

			case "locate" -> locate(command, bot, tick, string(args, "structure"));

			case "places" -> places(command, bot, tick, args.has("kind") ? string(args, "kind") : null);

			case "smelt" -> startIfAlive(command, bot, tick, new SmeltAction(string(args, "item"),
					(int) number(args, "count", 1, 1, 64), args.has("fuel") ? string(args, "fuel") : null));

			case "smelt_collect" -> startIfAlive(command, bot, tick, new SmeltCollectAction());

			case "place_utility" -> {
				Places.Kind kind = Places.Kind.byName(string(args, "block"));
				if (kind == null) {
					throw new InvalidArgs("block must be crafting_table, furnace or chest");
				}
				startIfAlive(command, bot, tick, new PlaceUtilityAction(kind, args.has("near") ? blockPos(args, "near") : null));
			}

			case "store", "take" -> {
				boolean store = command.name().equals("store");
				String item = string(args, "item");
				boolean all = item.equals("all");
				if (all && !store) {
					throw new InvalidArgs("take needs an item id; all only works for store");
				}
				startIfAlive(command, bot, tick, new ChestAction(store, all ? null : item,
						(int) number(args, "count", Integer.MAX_VALUE, 1, Integer.MAX_VALUE),
						args.has("pos") ? blockPos(args, "pos") : null));
			}

			case "eat" -> startIfAlive(command, bot, tick, new EatAction(args.has("item") ? string(args, "item") : null));

			case "cook" -> cook(command, bot, tick, string(args, "item"), (int) number(args, "count", 1, 1, 64));

			case "attack" -> {
				float minHealth = (float) number(args, "min_health", DEFAULT_MIN_HEALTH, 0, 19);
				Entity target = Combat.find(bot, selector(args, "target"), ATTACK_SEARCH_RADIUS);
				if (target == null) {
					// Weiter weg suchen, damit die KI ein echtes Ziel bekommt statt zu raten.
					Entity far = Combat.find(bot, selector(args, "target"), ATTACK_HINT_RADIUS);
					JsonObject data = new JsonObject();
					if (far != null) {
						data.add("nearest", GameJson.pos(far.blockPosition()));
						data.addProperty("nearest_dist", Math.round(far.distanceTo(bot)));
					}
					data.addProperty("hint", "no living entity of that type or id within " + ATTACK_SEARCH_RADIUS
							+ " blocks; players are never a target; "
							+ (far != null ? "goto nearest first" : "explore 100+ blocks in one direction"));
					bus.emitResult(command, tick, Status.FAILED, "entity_not_found", data);
					return;
				}
				if (!(target instanceof LivingEntity living)) {
					fail(command, tick, "not_attackable", GameJson.entityType(target) + " is not a living entity");
					return;
				}
				startIfAlive(command, bot, tick, new AttackAction(living, minHealth));
			}

			case "defend" -> startIfAlive(command, bot, tick, new DefendAction(number(args, "radius", 12, 2, 32),
					(float) number(args, "min_health", DEFAULT_MIN_HEALTH, 0, 19)));

			case "flee" -> {
				double distance = number(args, "distance", 16, 4, 64);
				Entity from = null;
				if (args.has("from")) {
					from = Combat.find(bot, selector(args, "from"), 48);
					if (from == null) {
						fail(command, tick, "entity_not_found", null);
						return;
					}
				}
				startIfAlive(command, bot, tick, new FleeAction(from, distance));
			}

			case "sprint" -> {
				de.hannes.smartcraft.reflex.Reflexes.sprintAllowed = !args.has("on") || flag(args, "on");
				JsonObject sprint = new JsonObject();
				sprint.addProperty("sprint", de.hannes.smartcraft.reflex.Reflexes.sprintAllowed);
				bus.emitResult(command, tick, Status.OK, null, sprint);
			}
			case "shield" -> shield(command, bot, tick, !args.has("on") || flag(args, "on"));

			case "equip_best_armor" -> equipBestArmor(command, bot, tick);

			case "sleep" -> startIfAlive(command, bot, tick, new SleepAction());

			case "fight_dragon" -> startIfAlive(command, bot, tick, new FightDragonAction());
			case "bed_bomb" -> startIfAlive(command, bot, tick, new BedBombAction());
			case "bed_cycle" -> startIfAlive(command, bot, tick, new BedCycleAction(args.has("mode") ? string(args, "mode") : "combo"));
			case "destroy_crystals" -> startIfAlive(command, bot, tick, new DestroyCrystalsAction(flag(args, "ground_only")));
			case "shelter" -> startIfAlive(command, bot, tick, new ShelterAction(flag(args, "force")));

			case "shoot" -> startIfAlive(command, bot, tick, new ShootAction(string(args, "target"),
					(int) number(args, "shots", 5, 1, MAX_SHOTS)));

			case "throw_eye" -> {
				// Mit freigeschaltetem locate kostet jeder Wurf nur ein Auge (Festungsläufe 1, 5, 12: am Portal fehlten Augen).
				if (locateEnabled && !flag(args, "force")) {
					JsonObject data = new JsonObject();
					data.addProperty("hint", "locate end_portal nennt den Portalraum direkt; Augen fürs Portal aufheben");
					bus.emitResult(command, tick, Status.FAILED, "use_locate", data);
				} else {
					startIfAlive(command, bot, tick, new ThrowEyeAction(flag(args, "force")));
				}
			}

			case "fill_portal" -> startIfAlive(command, bot, tick, new FillPortalAction());

			case "enter_portal" -> startIfAlive(command, bot, tick, new EnterPortalAction(flag(args, "force")));

			case "seal_water" -> startIfAlive(command, bot, tick, new SealWaterAction((int) number(args, "radius", 2, 1, 4)));

			case "seal_lava" -> startIfAlive(command, bot, tick,
					new SealWaterAction((int) number(args, "radius", 3, 1, 5), net.minecraft.tags.FluidTags.LAVA));

			case "enclose_spawner" -> startIfAlive(command, bot, tick, new EncloseSpawnerAction());

			case "build_portal" -> startIfAlive(command, bot, tick, new BuildPortalAction(flag(args, "force")));

			case "farm_spawner" -> startIfAlive(command, bot, tick, new FarmSpawnerAction(
					args.has("item") ? string(args, "item") : "blaze_rod", (int) number(args, "count", 10, 1, 64),
					(float) number(args, "min_health", 12, 0, 19), (int) number(args, "seconds", 600, 10, 1800)));

			case "tunnel" -> {
				Direction direction = Direction.byName(string(args, "direction"));
				if (direction == null || direction.getAxis().isVertical()) {
					throw new InvalidArgs("direction must be north, south, east or west");
				}
				startIfAlive(command, bot, tick, new TunnelAction(direction, (int) number(args, "length", 16, 1, MAX_TUNNEL_LENGTH),
						!args.has("stop_at_ore") || flag(args, "stop_at_ore")));
			}

			case "descend" -> startIfAlive(command, bot, tick, new DescendAction((int) number(args, "to_y", 0, -64, 320)));

			case "light" -> startIfAlive(command, bot, tick, new LightAction(args.has("pos") ? blockPos(args, "pos") : null));

			case "inventory" -> bus.emitResult(command, tick, Status.OK, null, withNetherCheck(bot, inventory(bot)));

			case "equip" -> equip(command, bot, tick, string(args, "item"),
					args.has("slot") ? string(args, "slot") : "hand");

			case "drop" -> drop(command, bot, tick, string(args, "item"),
					(int) number(args, "count", Integer.MAX_VALUE, 1, Integer.MAX_VALUE));

			case "goto_entity", "follow" -> {
				boolean follow = command.name().equals("follow");
				String selector = string(args, "target");
				double distance = number(args, "distance", follow ? 3 : 2, 1, MAX_TOLERANCE);
				int seconds = (int) number(args, "seconds", DEFAULT_FOLLOW_SECONDS, 1, MAX_FOLLOW_SECONDS);
				if (bot.isDeadOrDying()) {
					fail(command, tick, "bot_died", null);
					return;
				}
				Entity target = PursueAction.findTarget(bot, selector);
				if (target == null) {
					int shorn = selector.endsWith("sheep") ? PursueAction.shornNearby(bot) : 0;
					fail(command, tick, "entity_not_found", shorn > 0
							? "only sheared sheep (" + shorn + ") within " + PursueAction.SEARCH_RADIUS
									+ " blocks; sheared sheep regrow wool when they eat grass (about 1 to 2 minutes): with 3 or more on grass, "
									+ "wait 60 s next to them and shear them again; with fewer, walk 100+ blocks to other sheep"
							: "no player named or entity of type '" + selector + "' within " + PursueAction.SEARCH_RADIUS + " blocks");
					return;
				}
				runner.start(command, new PursueAction(target, distance, follow, seconds * 20), bot, tick);
			}

			default -> fail(command, tick, "unknown_command", null);
		}
	}

	private void startIfAlive(Command command, ServerPlayer bot, long tick, Action action) {
		if (bot.isDeadOrDying()) {
			fail(command, tick, "bot_died", null);
			return;
		}
		runner.start(command, action, bot, tick);
	}

	/** Das ganze Inventar. Nur auf Abruf, weil es viele Tokens kostet. */
	private static JsonObject inventory(ServerPlayer bot) {
		Inventory inventory = bot.getInventory();
		JsonArray items = new JsonArray();
		int free = 0;
		for (int slot = 0; slot < Inv.MAIN_SLOTS; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.isEmpty()) {
				free++;
				continue;
			}
			JsonObject item = Inv.describe(stack).getAsJsonObject();
			item.addProperty("slot", slot);
			items.add(item);
		}
		JsonObject worn = new JsonObject();
		EQUIP_SLOTS.forEach((name, slot) -> {
			if (slot != EquipmentSlot.MAINHAND && !bot.getItemBySlot(slot).isEmpty()) {
				worn.add(name, Inv.describe(bot.getItemBySlot(slot)));
			}
		});

		JsonObject data = new JsonObject();
		data.add("items", items);
		data.add("worn", worn);
		data.addProperty("held_slot", inventory.getSelectedSlot());
		data.addProperty("free_slots", free);
		return data;
	}

	private void equip(Command command, ServerPlayer bot, long tick, String item, String slotName) throws InvalidArgs {
		EquipmentSlot target = EQUIP_SLOTS.get(slotName);
		if (target == null) {
			throw new InvalidArgs("slot must be one of hand, offhand, head, chest, legs, feet");
		}
		int slot = Inv.findItem(bot, item);
		if (slot < 0) {
			fail(command, tick, "missing_item", null);
			return;
		}
		ItemStack stack = bot.getInventory().getItem(slot);
		if (target == EquipmentSlot.MAINHAND) {
			Inv.hold(bot, slot);
		} else {
			EquipmentSlot natural = bot.getEquipmentSlotForItem(stack);
			if (target != EquipmentSlot.OFFHAND && natural != target) {
				fail(command, tick, "wrong_slot", Inv.itemId(stack) + " belongs in " + slotNameOf(natural));
				return;
			}
			// Tauschen: was bisher dort war, kommt an den Platz des neuen Items.
			ItemStack previous = bot.getItemBySlot(target).copy();
			bot.setItemSlot(target, stack.copy());
			bot.getInventory().setItem(slot, previous);
		}
		JsonObject data = new JsonObject();
		data.addProperty("slot", slotName);
		data.add("equipped", Inv.describe(bot.getItemBySlot(target)));
		data.add("held_item", Inv.heldJson(bot));
		bus.emitResult(command, tick, Status.OK, null, data);
	}

	private static String slotNameOf(EquipmentSlot slot) {
		return EQUIP_SLOTS.entrySet().stream().filter(entry -> entry.getValue() == slot)
				.map(Map.Entry::getKey).findFirst().orElse(slot.getName());
	}

	/** Eine Minute: so lange liegt Weggeworfenes für den Bot gesperrt, er ist bis dahin weitergezogen. */
	private static final int DROP_PICKUP_DELAY = 1200;

	private void drop(Command command, ServerPlayer bot, long tick, String item, int count) {
		String id = Inv.fullId(item);
		int dropped = 0;
		Inventory inventory = bot.getInventory();
		for (int slot = 0; slot < Inv.MAIN_SLOTS && dropped < count; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.isEmpty() || !Inv.itemId(stack).equals(id)) {
				continue;
			}
			ItemStack thrown = inventory.removeItem(slot, Math.min(stack.getCount(), count - dropped));
			dropped += thrown.getCount();
			// Kit-Lauf 5: Weggeworfene Steine hob er 2 s später selbst wieder auf und warf sie erneut weg.
			net.minecraft.world.entity.item.ItemEntity entity = bot.drop(thrown, false, true);
			if (entity != null) {
				entity.setPickUpDelay(DROP_PICKUP_DELAY);
			}
		}
		if (dropped == 0) {
			fail(command, tick, "missing_item", null);
			return;
		}
		JsonObject data = new JsonObject();
		data.add("dropped", GameJson.stack(id, dropped));
		data.addProperty("left_in_inventory", Inv.count(bot, id));
		data.add("held_item", Inv.heldJson(bot));
		bus.emitResult(command, tick, Status.OK, null, data);
	}

	/**
	 * Übersetzt {@code oak_log}, {@code minecraft:stone} oder ein Tag wie {@code #logs} in einen Test.
	 *
	 * @return {@code null}, wenn es weder den Block noch das Tag gibt
	 */
	private static Predicate<BlockState> blockMatcher(String spec) {
		boolean isTag = spec.startsWith("#");
		Identifier id = Identifier.tryParse(Inv.fullId(isTag ? spec.substring(1) : spec));
		if (id == null) {
			return null;
		}
		if (isTag) {
			TagKey<Block> tag = TagKey.create(Registries.BLOCK, id);
			return BuiltInRegistries.BLOCK.get(tag).isPresent() ? state -> state.is(tag) : null;
		}
		if (!BuiltInRegistries.BLOCK.containsKey(id)) {
			return null;
		}
		Block block = BuiltInRegistries.BLOCK.getValue(id);
		return state -> state.getBlock() == block;
	}

	/**
	 * Nächste Struktur über die Weltdaten, wie {@code /locate}. Nur nach {@code /smartcraft locate on}: Das ist
	 * Wissen, das ein Spieler nicht hat, also entscheidet der Mensch, ob die KI es bekommt. Schema: {@code locate}.
	 */
	/** {@code places}: das Ortsgedächtnis abfragen (Antwort sofort, keine Aktion). */
	private void places(Command command, ServerPlayer bot, long tick, String kindName) throws InvalidArgs {
		de.hannes.smartcraft.memory.Places.Kind kind = null;
		if (kindName != null) {
			kind = de.hannes.smartcraft.memory.Places.Kind.byName(kindName);
			if (kind == null) {
				throw new InvalidArgs("unknown kind '" + kindName + "'");
			}
		}
		JsonObject data = new JsonObject();
		data.add("places", de.hannes.smartcraft.memory.Places.listJson(bot, kind, 20));
		data.addProperty("total", de.hannes.smartcraft.memory.Places.count());
		bus.emitResult(command, tick, Status.OK, null, data);
	}

	private void locate(Command command, ServerPlayer bot, long tick, String name) throws InvalidArgs {
		if (LOCATE_BIOMES.containsKey(name)) {
			locateBiome(command, bot, tick, name);
			return;
		}
		LocateTarget target = LOCATE_TARGETS.get(name.equals("blaze_spawner") ? "fortress" : name.equals("end_portal") ? "stronghold" : name);
		if (target == null) {
			throw new InvalidArgs("structure must be one of blaze_spawner, end_portal, " + String.join(", ", LOCATE_TARGETS.keySet())
					+ ", " + String.join(", ", LOCATE_BIOMES.keySet()));
		}
		JsonObject data = new JsonObject();
		data.addProperty("structure", name);
		if (!locateEnabled) {
			bus.emitResult(command, tick, Status.FAILED, "locate_disabled", data);
			return;
		}
		ServerLevel level = bot.level();
		if (level.dimension() != target.dimension()) {
			data.addProperty("hint", "only in " + target.dimension().identifier().getPath());
			bus.emitResult(command, tick, Status.FAILED, "wrong_dimension", data);
			return;
		}
		BlockPos feet = bot.blockPosition();
		BlockPos at = name.equals("blaze_spawner") ? Snapshot.blazeSpawnerRoom(level, feet, LOCATE_CHUNKS)
				: name.equals("end_portal") ? Snapshot.endPortalRoom(level, feet, LOCATE_CHUNKS)
				: Snapshot.nearestPart(level, feet, target.key(), LOCATE_CHUNKS, target.underground());
		if (at == null) {
			bus.emitResult(command, tick, Status.FAILED, "not_found", data);
			return;
		}
		int dx = at.getX() - feet.getX();
		int dz = at.getZ() - feet.getZ();
		data.add("approx_pos", GameJson.pos(at));
		data.addProperty("distance", (int) Math.round(Math.sqrt((double) dx * dx + (double) dz * dz)));
		data.addProperty("direction", Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? "east" : "west") : (dz > 0 ? "south" : "north"));
		bus.emitResult(command, tick, Status.OK, null, data);
	}

	/** {@code locate} für ein Nether-Biom: nächster Punkt bis {@link #LOCATE_BIOME_RADIUS} Blöcke (Höhe des Bots). */
	private void locateBiome(Command command, ServerPlayer bot, long tick, String name) {
		JsonObject data = new JsonObject();
		data.addProperty("structure", name);
		if (!locateEnabled) {
			bus.emitResult(command, tick, Status.FAILED, "locate_disabled", data);
			return;
		}
		ServerLevel level = bot.level();
		if (level.dimension() != Level.NETHER) {
			data.addProperty("hint", "only in the_nether");
			bus.emitResult(command, tick, Status.FAILED, "wrong_dimension", data);
			return;
		}
		BlockPos feet = bot.blockPosition();
		var key = LOCATE_BIOMES.get(name);
		var found = level.findClosestBiome3d(biome -> biome.is(key), feet, LOCATE_BIOME_RADIUS, 16, 64);
		if (found == null) {
			bus.emitResult(command, tick, Status.FAILED, "not_found", data);
			return;
		}
		BlockPos at = new BlockPos(found.getFirst().getX(), feet.getY(), found.getFirst().getZ());
		int dx = at.getX() - feet.getX();
		int dz = at.getZ() - feet.getZ();
		data.add("approx_pos", GameJson.pos(at));
		data.addProperty("distance", (int) Math.round(Math.sqrt((double) dx * dx + (double) dz * dz)));
		data.addProperty("direction", Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? "east" : "west") : (dz > 0 ? "south" : "north"));
		bus.emitResult(command, tick, Status.OK, null, data);
	}

	/** Rezept eines Items und was davon fehlt, ohne etwas herzustellen. Schema: {@code recipe}. */
	private void recipe(Command command, ServerPlayer bot, long tick, String itemName) {
		Item item = Recipes.item(itemName);
		JsonObject data = new JsonObject();
		data.addProperty("item", Inv.fullId(itemName));
		if (item == null) {
			bus.emitResult(command, tick, Status.FAILED, "unknown_item", data);
			return;
		}
		data.addProperty("in_inventory", Inv.count(bot, Inv.fullId(itemName)));
		List<Recipes.Crafting> recipes = Recipes.ranked(bot, item);
		JsonArray craft = new JsonArray();
		for (Recipes.Crafting recipe : recipes.subList(0, Math.min(MAX_RECIPES_LISTED, recipes.size()))) {
			List<Recipes.Need> needs = Recipes.needs(bot, recipe.holder().value());
			JsonObject json = new JsonObject();
			json.addProperty("makes", recipe.result().getCount());
			json.addProperty("needs_crafting_table", recipe.needsTable());
			json.add("ingredients", Recipes.needsJson(needs, 1));
			json.add("missing", Recipes.missingJson(needs, 1));
			json.addProperty("can_craft_now", Recipes.craftable(bot, recipe.holder().value()));
			craft.add(json);
		}
		data.add("craft", craft);
		if (recipes.size() > MAX_RECIPES_LISTED) {
			data.addProperty("more_recipes", recipes.size() - MAX_RECIPES_LISTED);
		}
		JsonArray smelt = new JsonArray();
		// Was der Bot schon hat, zuerst; Rohstoffe gibt es oft mehrere (Erz, Tiefenschiefererz, Rohmetall).
		List<JsonObject> sources = new ArrayList<>();
		for (RecipeHolder<SmeltingRecipe> holder : Recipes.smeltingFor(bot.level(), item)) {
			List<Recipes.Need> needs = Recipes.needs(bot, holder.value());
			JsonObject json = new JsonObject();
			json.addProperty("from", Recipes.name(needs.get(0).ingredient()));
			json.addProperty("have", needs.get(0).have());
			json.addProperty("ticks", holder.value().cookingTime());
			sources.add(json);
		}
		sources.sort(Comparator.comparingInt((JsonObject json) -> -json.get("have").getAsInt()));
		sources.stream().limit(MAX_SMELT_SOURCES_LISTED).forEach(smelt::add);
		data.add("smelt", smelt);
		if (craft.isEmpty() && smelt.isEmpty()) {
			bus.emitResult(command, tick, Status.FAILED, "no_recipe", data);
			return;
		}
		bus.emitResult(command, tick, Status.OK, null, data);
	}

	/** {@code cook}: wie {@code smelt}, aber nur für Rohes, das gegart etwas Essbares ergibt. */
	private void cook(Command command, ServerPlayer bot, long tick, String itemName, int count) {
		Item item = Recipes.item(itemName);
		if (item != null) {
			ServerLevel level = bot.level();
			SingleRecipeInput input = new SingleRecipeInput(new ItemStack(item));
			boolean cookable = level.recipeAccess().getRecipeFor(RecipeType.SMELTING, input, level)
					.map(recipe -> Eater.isFood(recipe.value().assemble(input))).orElse(false);
			if (!cookable) {
				fail(command, tick, "not_cookable", Inv.fullId(itemName) + " does not turn into food in a furnace");
				return;
			}
		}
		startIfAlive(command, bot, tick, new SmeltAction(itemName, count, null));
	}

	/** {@code shield}: Schild heben oder senken; er bleibt oben, bis er gesenkt wird. */
	/** Ob am Zielpunkt einer Enderperle fester Boden ohne Lava ist; null wenn ja, sonst der Grund. */
	/**
	 * Flugbahn der Perle wie im Spiel (Tempo 1,5, je Tick Luftwiderstand 0,99 und Schwerkraft 0,03) in Blickrichtung auf
	 * look_at: Wo schlägt sie ein, und ist dort fester Boden ohne Lava? Drachenlauf 63: Wurf auf einen Punkt 50 Blöcke weit,
	 * geprüft wurde nur der Zielpunkt; die Perle flog 30 Blöcke darüber hinaus auf eine Lavafläche, der Bot verbrannte.
	 */
	private static String pearlFlight(ServerPlayer bot, Vec3 lookAt) {
		var level = bot.level();
		Vec3 pos = bot.getEyePosition();
		Vec3 velocity = lookAt.subtract(pos).normalize().scale(1.5).add(bot.getDeltaMovement().x, bot.onGround() ? 0 : bot.getDeltaMovement().y, bot.getDeltaMovement().z);
		for (int tick = 0; tick < 300; tick++) {
			Vec3 next = pos.add(velocity);
			var hit = level.clip(new net.minecraft.world.level.ClipContext(pos, next, net.minecraft.world.level.ClipContext.Block.COLLIDER,
					net.minecraft.world.level.ClipContext.Fluid.ANY, bot));
			if (hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
				BlockPos at = hit.getBlockPos();
				if (level.getFluidState(at).is(net.minecraft.tags.FluidTags.LAVA)) {
					return "the pearl would land in lava at " + at.toShortString() + " (flight path checked, not only look_at); "
							+ "pick a closer spot on firm ground";
				}
				// Eingeschlagen wird über dem getroffenen Block (bei einer Wand davor): dort Boden prüfen.
				BlockPos landing = hit.getDirection() == Direction.UP ? at : at.relative(hit.getDirection());
				String unsafe = pearlLanding(bot, landing);
				if (unsafe != null) {
					return unsafe + " (where the pearl would really land: " + landing.toShortString() + ")";
				}
				if (Math.sqrt(landing.distSqr(BlockPos.containing(lookAt))) > 8) {
					return "the pearl would land at " + landing.toShortString() + ", not near look_at (too far for a straight "
							+ "throw); pick a spot within about 20 blocks";
				}
				return null;
			}
			pos = next;
			velocity = velocity.scale(0.99).subtract(0, 0.03, 0);
			if (pos.y < level.getMinY()) {
				return "the pearl would fall into the void";
			}
		}
		return "no landing found on the flight path";
	}

	private static String pearlLanding(ServerPlayer bot, BlockPos at) {
		var level = bot.level();
		if (!level.isLoaded(at)) {
			return "landing spot not loaded";
		}
		for (int dy = 4; dy >= -12; dy--) {
			BlockPos pos = at.offset(0, dy, 0);
			var state = level.getBlockState(pos);
			if (state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) {
				return "lava at the landing spot " + pos.toShortString();
			}
			if (dy <= 0 && state.isFaceSturdy(level, pos, Direction.UP)) {
				BlockPos above = pos.above();
				if (level.getBlockState(above).getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) {
					return "lava at the landing spot " + above.toShortString();
				}
				return null;
			}
		}
		return "no ground within 12 blocks below the landing spot";
	}

	private void shield(Command command, ServerPlayer bot, long tick, boolean on) {
		if (bot.isDeadOrDying()) {
			fail(command, tick, "bot_died", null);
			return;
		}
		if (on && !Combat.hasShieldReady(bot)) {
			int slot = Inv.findSlot(bot, Combat::isShield);
			if (slot < 0) {
				fail(command, tick, "no_shield", "no shield in inventory; crafting one needs 6 planks and 1 iron ingot");
				return;
			}
			swapInto(bot, EquipmentSlot.OFFHAND, slot);
		}
		Combat.setShieldWanted(on);
		if (on) {
			Combat.raiseShield(bot);
		} else {
			Combat.lowerShield(bot);
		}
		JsonObject data = new JsonObject();
		data.addProperty("raised", on);
		data.add("shield", Combat.hasShieldReady(bot) ? Inv.describe(bot.getOffhandItem()) : JsonNull.INSTANCE);
		bus.emitResult(command, tick, Status.OK, null, data);
	}

	/** {@code equip_best_armor}: je Platz das Teil mit dem höchsten Rüstungswert, dazu der Schild in die Zweithand. */
	private void equipBestArmor(Command command, ServerPlayer bot, long tick) {
		if (bot.isDeadOrDying()) {
			fail(command, tick, "bot_died", null);
			return;
		}
		JsonArray changed = new JsonArray();
		boolean anyArmor = false;
		for (EquipmentSlot target : ARMOR_SLOTS) {
			// Im Nether bleibt das einzige Goldteil an (sonst greifen Piglins an).
			if (bot.level().dimension() == Level.NETHER && de.hannes.smartcraft.skill.NetherKit.isGold(bot, bot.getItemBySlot(target))
					&& de.hannes.smartcraft.skill.NetherKit.goldWorn(bot) == 1) {
				continue;
			}
			double bestValue = armorValue(bot.getItemBySlot(target), target, true);
			anyArmor |= bestValue > 0;
			int best = -1;
			for (int slot = 0; slot < Inv.MAIN_SLOTS; slot++) {
				ItemStack stack = bot.getInventory().getItem(slot);
				if (stack.isEmpty() || bot.getEquipmentSlotForItem(stack) != target) {
					continue;
				}
				double value = armorValue(stack, target, true);
				if (value > bestValue) {
					bestValue = value;
					best = slot;
				}
			}
			if (best >= 0) {
				anyArmor = true;
				swapInto(bot, target, best);
				changed.add(changedEntry(target, bot));
			}
		}
		if (!Combat.hasShieldReady(bot)) {
			int slot = Inv.findSlot(bot, Combat::isShield);
			if (slot >= 0) {
				swapInto(bot, EquipmentSlot.OFFHAND, slot);
				changed.add(changedEntry(EquipmentSlot.OFFHAND, bot));
			}
		}
		if (!anyArmor && changed.isEmpty()) {
			fail(command, tick, "no_armor", "no armor worn or in inventory");
			return;
		}
		JsonObject worn = new JsonObject();
		EQUIP_SLOTS.forEach((name, slot) -> {
			if (slot != EquipmentSlot.MAINHAND && !bot.getItemBySlot(slot).isEmpty()) {
				worn.add(name, Inv.describe(bot.getItemBySlot(slot)));
			}
		});
		JsonObject data = new JsonObject();
		data.add("changed", changed);
		data.add("worn", worn);
		// Selbst zusammenzählen: Das Spiel rechnet neu angelegte Teile erst im nächsten Tick ein.
		double armor = 0;
		for (EquipmentSlot target : ARMOR_SLOTS) {
			armor += armorValue(bot.getItemBySlot(target), target, false);
		}
		data.addProperty("armor", (int) armor);
		bus.emitResult(command, tick, Status.OK, null, data);
	}

	private static JsonObject changedEntry(EquipmentSlot slot, ServerPlayer bot) {
		JsonObject json = new JsonObject();
		json.addProperty("slot", slotNameOf(slot));
		json.addProperty("item", Inv.itemId(bot.getItemBySlot(slot)));
		return json;
	}

	/** Rüstungspunkte, die ein Item an diesem Platz bringt, auf Wunsch plus Härte. */
	private static double armorValue(ItemStack stack, EquipmentSlot slot, boolean withToughness) {
		double[] value = {0};
		stack.forEachModifier(slot, (attribute, modifier) -> {
			if (attribute.value() == Attributes.ARMOR.value()
					|| withToughness && attribute.value() == Attributes.ARMOR_TOUGHNESS.value()) {
				value[0] += modifier.amount();
			}
		});
		return value[0];
	}

	/** Tauscht: Das Item aus dem Inventar-Slot kommt an den Platz, was dort war, in den Slot. */
	private static void swapInto(ServerPlayer bot, EquipmentSlot target, int slot) {
		ItemStack incoming = bot.getInventory().getItem(slot).copy();
		ItemStack previous = bot.getItemBySlot(target).copy();
		bot.setItemSlot(target, incoming);
		bot.getInventory().setItem(slot, previous);
	}

	/** Ein Ziel als Typ ({@code zombie}) oder als Entity-ID (Zahl). */
	private static String selector(JsonObject args, String key) throws InvalidArgs {
		JsonElement element = args.get(key);
		if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
			return String.valueOf(element.getAsInt());
		}
		return string(args, key);
	}

	private void fail(Command command, long tick, String reason, String hint) {
		JsonObject data = new JsonObject();
		if (hint != null) {
			data.addProperty("hint", hint);
		}
		bus.emitResult(command, tick, Status.FAILED, reason, data);
	}

	private static Pos pos(JsonObject args, String key) throws InvalidArgs {
		JsonElement element = args.get(key);
		if (element == null || !element.isJsonArray() || element.getAsJsonArray().size() != 3) {
			throw new InvalidArgs(key + " must be [x, y, z]");
		}
		JsonArray array = element.getAsJsonArray();
		int[] values = new int[3];
		for (int i = 0; i < 3; i++) {
			JsonElement value = array.get(i);
			if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
				throw new InvalidArgs(key + " must be [x, y, z] with numbers");
			}
			values[i] = (int) Math.floor(value.getAsDouble());
		}
		return new Pos(values[0], values[1], values[2]);
	}

	private static BlockPos blockPos(JsonObject args, String key) throws InvalidArgs {
		Pos pos = pos(args, key);
		return new BlockPos(pos.x(), pos.y(), pos.z());
	}

	private static boolean flag(JsonObject args, String key) throws InvalidArgs {
		JsonElement element = args.get(key);
		if (element == null) {
			return false;
		}
		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
			throw new InvalidArgs(key + " must be true or false");
		}
		return element.getAsBoolean();
	}

	private static double number(JsonObject args, String key, double fallback, double min, double max) throws InvalidArgs {
		JsonElement element = args.get(key);
		if (element == null) {
			return fallback;
		}
		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
			throw new InvalidArgs(key + " must be a number");
		}
		double value = element.getAsDouble();
		if (value < min || value > max) {
			throw new InvalidArgs(key + " must be between " + min + " and " + max);
		}
		return value;
	}

	private static String string(JsonObject args, String key) throws InvalidArgs {
		JsonElement element = args.get(key);
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()
				|| element.getAsString().isBlank()) {
			throw new InvalidArgs(key + " must be a non-empty string");
		}
		return element.getAsString();
	}
}
