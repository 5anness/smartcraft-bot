package de.hannes.smartcraft.memory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.hannes.smartcraft.AiPlayerMod;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Ortsgedächtnis: Werkbänke, Öfen, Truhen und Betten, die der Bot gesetzt oder gesehen hat, dazu wichtige Orte als
 * Gebiete (Bäume, Netherportale, Lavaseen, Spawner, Netherfestungen, Dörfer, Endportalräume).
 * Gilt pro Welt und liegt als {@code aiplayer/places.json} im Weltordner, übersteht also Neustarts.
 * Nur auf dem Server-Thread benutzen.
 */
public final class Places {
	public enum Kind {
		CRAFTING_TABLE(Blocks.CRAFTING_TABLE, 0), FURNACE(Blocks.FURNACE, 0), CHEST(Blocks.CHEST, 0),
		/** Ein Bett (Kopf- oder Fußteil), das der Bot gesetzt oder gesehen hat. */
		BED(Blocks.BED.white(), 0),
		// Wichtige Orte: Gebiete statt einzelner Blöcke (ein Eintrag je Umkreis, siehe rememberArea), Abbauen trägt sie nicht aus.
		/** Eine Stelle mit Bäumen. */
		TREES(Blocks.OAK_LOG, 32),
		NETHER_PORTAL(Blocks.NETHER_PORTAL, 16),
		LAVA_LAKE(Blocks.LAVA, 48),
		SPAWNER(Blocks.SPAWNER, 16),
		FORTRESS(Blocks.NETHER_BRICKS, 96),
		VILLAGE(Blocks.BELL, 96),
		END_PORTAL(Blocks.END_PORTAL_FRAME, 64);

		private final Block block;
		private final int area;

		Kind(Block block, int area) {
			this.block = block;
			this.area = area;
		}

		/** Gebiets-Art: Umkreis, in dem nur ein Eintrag steht; 0 bei Arbeitsblöcken. */
		public int area() {
			return area;
		}

		public boolean isArea() {
			return area > 0;
		}

		public Block block() {
			return block;
		}

		/** Item-ID mit Namensraum, z. B. {@code minecraft:furnace}. */
		public String itemId() {
			return BuiltInRegistries.ITEM.getKey(block.asItem()).toString();
		}

		public static Kind of(BlockState state) {
			if (state.is(net.minecraft.tags.BlockTags.BEDS)) {
				return BED;
			}
			for (Kind kind : values()) {
				if (!kind.isArea() && state.is(kind.block)) {
					return kind;
				}
			}
			return null;
		}

		/** @return die Art zu einem Namen wie {@code furnace} oder {@code minecraft:chest}, sonst null */
		public static Kind byName(String name) {
			String plain = name.startsWith("minecraft:") ? name.substring("minecraft:".length()) : name;
			for (Kind kind : values()) {
				if (kind.name().equalsIgnoreCase(plain)) {
					return kind;
				}
			}
			return null;
		}
	}

	private record Place(Kind kind, String dimension, BlockPos pos) {
	}

	private static final List<Place> PLACES = new ArrayList<>();
	private static Path file;

	private Places() {
	}

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(Places::load);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			PLACES.clear();
			file = null;
		});
	}

	/** Merkt sich den Block an dieser Stelle, falls er ein Arbeitsblock oder Bett ist (beim Bett nur das Kopfteil). */
	public static void noteBlock(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		Kind kind = Kind.of(state);
		if (kind == Kind.BED && state.hasProperty(net.minecraft.world.level.block.BedBlock.PART)
				&& state.getValue(net.minecraft.world.level.block.BedBlock.PART) != net.minecraft.world.level.block.state.properties.BedPart.HEAD) {
			pos = pos.relative(state.getValue(net.minecraft.world.level.block.BedBlock.FACING));
		}
		if (kind != null) {
			remember(kind, level, pos);
		}
	}

	/** Merkt sich einen wichtigen Ort (Gebiets-Art) mit dem Umkreis seiner Art. */
	public static void rememberArea(Kind kind, Level level, BlockPos pos) {
		rememberArea(kind, level, pos, kind.area());
	}

	public static void remember(Kind kind, Level level, BlockPos pos) {
		String dimension = dimension(level);
		BlockPos fixed = pos.immutable();
		for (Place place : PLACES) {
			if (place.pos.equals(fixed) && place.dimension.equals(dimension)) {
				if (place.kind == kind) {
					return;
				}
				PLACES.remove(place);
				break;
			}
		}
		PLACES.add(new Place(kind, dimension, fixed));
		save();
	}

	/** Merkt sich ein Gebiet: nur wenn kein Ort derselben Art näher als {@code mergeRadius} liegt. */
	public static void rememberArea(Kind kind, Level level, BlockPos pos, int mergeRadius) {
		String dimension = dimension(level);
		for (Place place : PLACES) {
			if (place.kind == kind && place.dimension.equals(dimension)
					&& place.pos.distSqr(pos) <= (double) mergeRadius * mergeRadius) {
				return;
			}
		}
		PLACES.add(new Place(kind, dimension, pos.immutable()));
		save();
	}

	/** Trägt einen Ort aus, etwa weil der Block abgebaut wurde. Baumstellen bleiben (dort wachsen und stehen weitere). */
	public static void forget(Level level, BlockPos pos) {
		String dimension = dimension(level);
		if (PLACES.removeIf(place -> !place.kind.isArea() && place.pos.equals(pos) && place.dimension.equals(dimension))) {
			save();
		}
	}

	/** Bekannte Orte einer Art in dieser Dimension im Umkreis, die nächsten zuerst. */
	public static List<BlockPos> known(Kind kind, ServerLevel level, BlockPos from, int maxDistance) {
		String dimension = dimension(level);
		List<BlockPos> result = new ArrayList<>();
		for (Place place : PLACES) {
			if (place.kind == kind && place.dimension.equals(dimension)
					&& place.pos.distSqr(from) <= (double) maxDistance * maxDistance) {
				result.add(place.pos);
			}
		}
		result.sort(Comparator.comparingDouble(pos -> pos.distSqr(from)));
		return result;
	}

	/** Bis zu {@code limit} gemerkte Baumstellen in dieser Dimension weiter als {@code beyond} Blöcke, als
	 * {@code [{"pos":[x,y,z],"distance":n}]}, die nächsten zuerst (bis 512 Blöcke). */
	public static JsonArray treesJson(net.minecraft.server.level.ServerPlayer bot, int beyond, int limit) {
		JsonArray result = new JsonArray();
		for (BlockPos pos : known(Kind.TREES, bot.level(), bot.blockPosition(), 512)) {
			int distance = (int) Math.sqrt(pos.distSqr(bot.blockPosition()));
			if (distance <= beyond) {
				continue;
			}
			JsonObject json = new JsonObject();
			JsonArray xyz = new JsonArray();
			xyz.add(pos.getX());
			xyz.add(pos.getY());
			xyz.add(pos.getZ());
			json.add("pos", xyz);
			json.addProperty("distance", distance);
			result.add(json);
			if (result.size() >= limit) {
				break;
			}
		}
		return result;
	}

	/**
	 * Wichtige Orte für {@code look}: je Art der nächste in dieser Dimension (ohne Bäume, die stehen in
	 * {@code known_trees}), als {@code [{"kind","pos","distance"}]}, die nächsten zuerst.
	 */
	public static JsonArray nearestPerKind(net.minecraft.server.level.ServerPlayer bot, int maxDistance) {
		List<JsonObject> found = new ArrayList<>();
		for (Kind kind : Kind.values()) {
			if (kind == Kind.TREES) {
				continue;
			}
			List<BlockPos> list = known(kind, bot.level(), bot.blockPosition(), maxDistance);
			if (!list.isEmpty()) {
				found.add(entry(kind, list.get(0), bot.blockPosition()));
			}
		}
		found.sort(Comparator.comparingInt(json -> json.get("distance").getAsInt()));
		JsonArray result = new JsonArray();
		found.forEach(result::add);
		return result;
	}

	/** Alle Orte (optional nur einer Art) in allen Dimensionen; in der eigenen mit Entfernung, die nächsten zuerst. */
	public static JsonArray listJson(net.minecraft.server.level.ServerPlayer bot, Kind only, int limit) {
		String here = dimension(bot.level());
		List<JsonObject> found = new ArrayList<>();
		for (Place place : PLACES) {
			if (only != null && place.kind != only) {
				continue;
			}
			JsonObject json = entry(place.kind, place.pos, bot.blockPosition());
			if (!place.dimension.equals(here)) {
				json.remove("distance");
				json.addProperty("dim", place.dimension);
			}
			found.add(json);
		}
		found.sort(Comparator.comparingInt(json -> json.has("distance") ? json.get("distance").getAsInt() : Integer.MAX_VALUE));
		JsonArray result = new JsonArray();
		found.stream().limit(limit).forEach(result::add);
		return result;
	}

	private static JsonObject entry(Kind kind, BlockPos pos, BlockPos from) {
		JsonObject json = new JsonObject();
		json.addProperty("kind", kind.name().toLowerCase());
		JsonArray xyz = new JsonArray();
		xyz.add(pos.getX());
		xyz.add(pos.getY());
		xyz.add(pos.getZ());
		json.add("pos", xyz);
		json.addProperty("distance", (int) Math.sqrt(pos.distSqr(from)));
		return json;
	}

	public static int count() {
		return PLACES.size();
	}

	private static String dimension(Level level) {
		return level.dimension().identifier().toString();
	}

	private static void load(MinecraftServer server) {
		PLACES.clear();
		file = server.getWorldPath(LevelResource.ROOT).resolve("aiplayer").resolve("places.json");
		if (!Files.exists(file)) {
			return;
		}
		try {
			JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
			for (JsonElement element : root.getAsJsonArray("places")) {
				JsonObject json = element.getAsJsonObject();
				Kind kind = Kind.byName(json.get("kind").getAsString());
				JsonArray pos = json.getAsJsonArray("pos");
				if (kind != null) {
					PLACES.add(new Place(kind, json.get("dim").getAsString(),
							new BlockPos(pos.get(0).getAsInt(), pos.get(1).getAsInt(), pos.get(2).getAsInt())));
				}
			}
		} catch (IOException | RuntimeException e) {
			// Ein kaputtes Gedächtnis ist kein Grund, die Welt nicht zu starten: leer anfangen.
			AiPlayerMod.LOGGER.warn("Ortsgedächtnis {} nicht lesbar, beginne leer", file, e);
			PLACES.clear();
		}
	}

	private static void save() {
		if (file == null) {
			return;
		}
		JsonArray places = new JsonArray();
		for (Place place : PLACES) {
			JsonObject json = new JsonObject();
			json.addProperty("kind", place.kind.name().toLowerCase());
			json.addProperty("dim", place.dimension);
			JsonArray pos = new JsonArray();
			pos.add(place.pos.getX());
			pos.add(place.pos.getY());
			pos.add(place.pos.getZ());
			json.add("pos", pos);
			places.add(json);
		}
		JsonObject root = new JsonObject();
		root.addProperty("v", 1);
		root.add("places", places);
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, root.toString(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			AiPlayerMod.LOGGER.warn("Ortsgedächtnis {} nicht schreibbar", file, e);
		}
	}
}
