package de.hannes.aiplayer.perception;

import de.hannes.aiplayer.skill.Fortress;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.hannes.aiplayer.protocol.Protocol;
import net.fabricmc.fabric.api.tag.convention.v2.ConventionalBlockTags;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.structures.NetherFortressPieces;
import net.minecraft.world.level.levelgen.structure.structures.StrongholdPieces;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Kompakter Zustand des Bots auf Abruf, so wie ihn später das Modell bekommt.
 * Schema: {@code schemas/snapshot.schema.json}.
 */
public final class Snapshot {
	private static final int RADIUS_HORIZONTAL = 8;
	private static final int RADIUS_VERTICAL = 5;
	private static final int NEAREST_PER_BLOCK = 3;
	private static final int FORTRESS_SIGHT_CHUNKS = 7;
	private static final int FORTRESS_SIGHT_BLOCKS = 100;
	private static final int DAY_LENGTH = 24000;
	/** Ab hier können Monster an der Oberfläche spawnen, bis kurz vor Sonnenaufgang. */
	private static final int NIGHT_START = 13000;
	private static final int NIGHT_END = 23000;

	private Snapshot() {
	}

	public static JsonObject of(ServerPlayer bot) {
		ServerLevel level = bot.level();
		BlockPos feet = bot.blockPosition();
		int time = (int) Math.floorMod(level.getOverworldClockTime(), (long) DAY_LENGTH);

		JsonObject json = new JsonObject();
		json.addProperty("v", Protocol.VERSION);
		json.addProperty("type", "snapshot");
		json.addProperty("tick", level.getGameTime());
		json.add("pos", GameJson.pos(feet));
		json.addProperty("dim", level.dimension().identifier().toString());
		json.addProperty("world", level.getServer().getWorldData().getLevelName());
		json.addProperty("seed", level.getSeed());
		json.addProperty("yaw", Math.round(Mth.wrapDegrees(bot.getYRot())));
		json.addProperty("pitch", Math.round(bot.getXRot()));
		json.addProperty("facing", Compass.facing(bot.getYRot()));
		json.addProperty("health", GameJson.round1(bot.getHealth()));
		json.addProperty("food", bot.getFoodData().getFoodLevel());
		json.addProperty("time", time);
		json.addProperty("daytime", time >= NIGHT_START && time < NIGHT_END ? "night" : "day");
		json.add("held", GameJson.stack(bot.getMainHandItem()));
		json.add("ground", ground(level, feet));
		json.add("blocks", interestingBlocks(level, feet));
		// Gemerkte Baumstellen (nur die nächsten zwei, nur in der Oberwelt): Holz ohne Suchen wiederfinden.
		if (level.dimension() == net.minecraft.world.level.Level.OVERWORLD) {
			com.google.gson.JsonArray trees = de.hannes.aiplayer.memory.Places.treesJson(bot, 0, 2);
			if (!trees.isEmpty()) {
				json.add("known_trees", trees);
			}
		}
		// Wichtige Orte aus dem Gedächtnis: je Art der nächste in dieser Dimension bis 2000 Blöcke.
		com.google.gson.JsonArray places = de.hannes.aiplayer.memory.Places.nearestPerKind(bot, 2000);
		if (!places.isEmpty()) {
			json.add("known_places", places);
		}
		if (Fortress.contains(level, feet)) {
			json.addProperty("in_fortress", true);
		}
		JsonObject fortress = fortressInSight(level, feet);
		if (fortress != null) {
			json.add("fortress_in_sight", fortress);
		}
		JsonObject room = portalRoomNear(level, feet);
		if (room != null) {
			json.add("portal_room_near", room);
		}
		if (de.hannes.aiplayer.skill.NetherKit.bedsMissing(bot)) {
			JsonObject village = villageInSight(level, feet);
			if (village != null) {
				json.add("village_in_sight", village);
			}
		}
		return json;
	}

	/** Grobe Schätzung ohne Tokenizer: JSON mit vielen IDs und Zahlen liegt bei etwa 3,5 Zeichen pro Token. */
	public static int estimateTokens(String text) {
		return (int) Math.ceil(text.length() / 3.5);
	}

	private static JsonArray ground(ServerLevel level, BlockPos feet) {
		JsonArray rows = new JsonArray();
		for (int dz = -1; dz <= 1; dz++) {
			JsonArray row = new JsonArray();
			for (int dx = -1; dx <= 1; dx++) {
				row.add(GameJson.blockId(level.getBlockState(feet.offset(dx, -1, dz))));
			}
			rows.add(row);
		}
		return rows;
	}

	private static JsonObject interestingBlocks(ServerLevel level, BlockPos feet) {
		// Sortiert nach ID, damit gleiche Umgebung gleiches JSON ergibt.
		Map<String, List<int[]>> found = new TreeMap<>();
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int dy = -RADIUS_VERTICAL; dy <= RADIUS_VERTICAL; dy++) {
			for (int dx = -RADIUS_HORIZONTAL; dx <= RADIUS_HORIZONTAL; dx++) {
				for (int dz = -RADIUS_HORIZONTAL; dz <= RADIUS_HORIZONTAL; dz++) {
					cursor.set(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz);
					BlockState state = level.getBlockState(cursor);
					if (!state.isAir() && isInteresting(state)) {
						found.computeIfAbsent(GameJson.blockId(state), id -> new ArrayList<>()).add(new int[]{dx, dy, dz});
					}
				}
			}
		}

		JsonObject json = new JsonObject();
		for (Map.Entry<String, List<int[]>> entry : found.entrySet()) {
			List<int[]> offsets = entry.getValue();
			offsets.sort(Comparator.comparingInt(o -> o[0] * o[0] + o[1] * o[1] + o[2] * o[2]));

			JsonArray near = new JsonArray();
			for (int[] o : offsets.subList(0, Math.min(offsets.size(), NEAREST_PER_BLOCK))) {
				near.add(GameJson.ints(o[0], o[1], o[2]));
			}
			JsonObject block = new JsonObject();
			block.addProperty("n", offsets.size());
			block.add("near", near);
			json.add(entry.getKey(), block);
		}
		return json;
	}

	/**
	 * Nächste Struktur der Art im Umkreis von {@code chunks} Chunks, über die Platzierungsregeln der Welt (wie
	 * {@code /locate}). Gemessen bis zum nächsten Teil der Struktur, nicht bis zu ihrem Startpunkt: Festungen sind
	 * über 100 Blöcke groß, und im KI-Lauf stand der Bot an einem Gang, während der Startpunkt noch als zu weit galt.
	 * Mit {@code withHeight} liegt auch die Höhe im Teil (für Strukturen unter der Erde), sonst die Höhe des Bots.
	 */
	public static BlockPos nearestPart(ServerLevel level, BlockPos feet, ResourceKey<Structure> key, int chunks,
			boolean withHeight) {
		Holder<Structure> structure = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(key);
		Pair<BlockPos, Holder<Structure>> found = level.getChunkSource().getGenerator()
				.findNearestMapStructure(level, HolderSet.direct(structure), feet, chunks, false);
		if (found == null) {
			return null;
		}
		BlockPos at = null;
		for (StructureStart start : level.structureManager().startsForStructure(ChunkPos.containing(found.getFirst()),
				s -> s == structure.value())) {
			if (!start.isValid()) {
				continue;
			}
			for (StructurePiece piece : start.getPieces()) {
				BoundingBox box = piece.getBoundingBox();
				BlockPos nearest = new BlockPos(Math.clamp(feet.getX(), box.minX(), box.maxX()),
						withHeight ? Math.clamp(feet.getY(), box.minY(), box.maxY()) : feet.getY(),
						Math.clamp(feet.getZ(), box.minZ(), box.maxZ()));
				if (at == null || nearest.distSqr(feet) < at.distSqr(feet)) {
					at = nearest;
				}
			}
		}
		return at != null ? at : found.getFirst().atY(withHeight ? found.getFirst().getY() : feet.getY());
	}

	/**
	 * Raum mit dem Lohenspawner (Festungsteil „MonsterThrone“) in der nächsten Festung bis {@code chunks}: Mitte
	 * des Raums, oder {@code null}. Drachenlauf 3: Der Bot suchte 20 Minuten in der Festung nach Lohen.
	 */
	public static BlockPos blazeSpawnerRoom(ServerLevel level, BlockPos feet, int chunks) {
		return structureRoom(level, feet, chunks, BuiltinStructures.FORTRESS, NetherFortressPieces.MonsterThrone.class);
	}

	/**
	 * Portalraum (Teil „PortalRoom“) der nächsten Festung der Oberwelt bis {@code chunks}: Mitte des Raums, oder
	 * {@code null}. Festungsläufe 1–12: Der Bot stand über der Festung und fand den Portalraum oft nicht.
	 */
	public static BlockPos endPortalRoom(ServerLevel level, BlockPos feet, int chunks) {
		return structureRoom(level, feet, chunks, BuiltinStructures.STRONGHOLD, StrongholdPieces.PortalRoom.class);
	}

	private static BlockPos structureRoom(ServerLevel level, BlockPos feet, int chunks,
			net.minecraft.resources.ResourceKey<Structure> key, Class<? extends StructurePiece> room) {
		Holder<Structure> structure = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(key);
		Pair<BlockPos, Holder<Structure>> found = level.getChunkSource().getGenerator()
				.findNearestMapStructure(level, HolderSet.direct(structure), feet, chunks, false);
		if (found == null) {
			return null;
		}
		BlockPos best = null;
		for (StructureStart start : level.structureManager().startsForStructure(ChunkPos.containing(found.getFirst()),
				s -> s == structure.value())) {
			for (StructurePiece piece : start.getPieces()) {
				if (room.isInstance(piece)) {
					BlockPos center = piece.getBoundingBox().getCenter();
					if (best == null || center.distSqr(feet) < best.distSqr(feet)) {
						best = center;
					}
				}
			}
		}
		return best;
	}

	/**
	 * Im Nether: Ist eine Festung so nah, dass ein Spieler sie über die offenen Flächen sehen würde
	 * (etwa 100 Blöcke), nennt der Snapshot Richtung und Entfernung. Der Block-Umkreis von 8 reicht dafür
	 * nicht, und ohne den Hinweis lief der Bot an Festungen vorbei.
	 */
	private static final List<ResourceKey<Structure>> VILLAGES = List.of(BuiltinStructures.VILLAGE_PLAINS,
			BuiltinStructures.VILLAGE_DESERT, BuiltinStructures.VILLAGE_SAVANNA, BuiltinStructures.VILLAGE_SNOWY,
			BuiltinStructures.VILLAGE_TAIGA);
	private static final int VILLAGE_SIGHT_CHUNKS = 8;
	private static final int VILLAGE_SIGHT_BLOCKS = 128;

	/**
	 * Nächstes Dorf bis {@link #VILLAGE_SIGHT_BLOCKS} Blöcke in der Oberwelt, nach den Strukturdaten; {@code null}, wenn keins.
	 * Wunsch von Hannes: Betten aus Dörfern abbauen statt Wolle zu suchen (Drachenlauf 40: 75 min für 4 Betten Wolle).
	 */
	public static JsonObject villageInSight(ServerLevel level, BlockPos feet) {
		if (level.dimension() != Level.OVERWORLD) {
			return null;
		}
		// Mitte des Dorfs (Startpunkt der Struktur), nicht der nächste Teil: Drachenlauf 52 stand am Rand (ein Wegende),
		// collect #beds fand bis 30 Blöcke kein Bett.
		BlockPos best = null;
		for (ResourceKey<Structure> key : VILLAGES) {
			Holder<Structure> structure = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(key);
			Pair<BlockPos, Holder<Structure>> found = level.getChunkSource().getGenerator()
					.findNearestMapStructure(level, HolderSet.direct(structure), feet, VILLAGE_SIGHT_CHUNKS, false);
			BlockPos at = found == null ? null : found.getFirst();
			if (at != null && (best == null || at.distSqr(feet) < best.distSqr(feet))) {
				best = at;
			}
		}
		if (best == null) {
			return null;
		}
		int dx = best.getX() - feet.getX();
		int dz = best.getZ() - feet.getZ();
		int distance = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
		if (distance > VILLAGE_SIGHT_BLOCKS) {
			return null;
		}
		JsonObject json = new JsonObject();
		json.add("approx_pos", GameJson.ints(best.getX(), feet.getY(), best.getZ()));
		json.addProperty("distance", distance);
		json.addProperty("direction", Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? "east" : "west") : (dz > 0 ? "south" : "north"));
		json.addProperty("hint", "approx_pos is the village center; beds stand inside the houses: goto there, then collect #beds with radius 48 (a mined bed drops whole)");
		return json;
	}

	/** Ab dieser Entfernung (waagerecht) zum Portalraum nennt der Snapshot ihn: der Bot steht dann in der Festung. */
	private static final int PORTAL_ROOM_NEAR_BLOCKS = 96;

	/**
	 * Oberwelt: Steht der Bot in oder an einer Festung (Portalraum bis {@link #PORTAL_ROOM_NEAR_BLOCKS} Blöcke), nennt der
	 * Snapshot Lage und Höhe des Portalraums aus den Strukturdaten – wie ein Spieler, der den Gängen folgt. Wunsch von Hannes
	 * (05.11.2026): In 6 von 15 Läufen fand der Bot die Festung, aber den Portalraum nicht, und verbrauchte beim Tunneln beide
	 * Spitzhacken.
	 */
	public static JsonObject portalRoomNear(ServerLevel level, BlockPos feet) {
		if (level.dimension() != Level.OVERWORLD) {
			return null;
		}
		BlockPos room = endPortalRoom(level, feet, 7);
		if (room == null) {
			return null;
		}
		int dx = room.getX() - feet.getX();
		int dz = room.getZ() - feet.getZ();
		int distance = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
		if (distance > PORTAL_ROOM_NEAR_BLOCKS) {
			return null;
		}
		JsonObject json = new JsonObject();
		json.add("pos", GameJson.ints(room.getX(), room.getY(), room.getZ()));
		json.addProperty("distance", distance);
		json.addProperty("dy", room.getY() - feet.getY());
		json.addProperty("direction", Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? "east" : "west") : (dz > 0 ? "south" : "north"));
		json.addProperty("hint", "end portal room of this stronghold: goto pos with allow_dig (descend first if dy is far below), "
				+ "then fill_portal; no long tunnels");
		return json;
	}

	static JsonObject fortressInSight(ServerLevel level, BlockPos feet) {
		if (level.dimension() != Level.NETHER) {
			return null;
		}
		BlockPos at = nearestPart(level, feet, BuiltinStructures.FORTRESS, FORTRESS_SIGHT_CHUNKS, false);
		if (at == null) {
			return null;
		}
		int dx = at.getX() - feet.getX();
		int dz = at.getZ() - feet.getZ();
		int distance = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
		if (distance > FORTRESS_SIGHT_BLOCKS) {
			return null;
		}
		JsonObject json = new JsonObject();
		json.add("approx_pos", GameJson.ints(at.getX(), feet.getY(), at.getZ()));
		json.addProperty("distance", distance);
		json.addProperty("direction", Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? "east" : "west") : (dz > 0 ? "south" : "north"));
		return json;
	}

	private static boolean isInteresting(BlockState state) {
		return state.is(ConventionalBlockTags.ORES)
				|| state.is(BlockTags.LOGS)
				|| state.is(ConventionalBlockTags.CHESTS)
				|| state.is(ConventionalBlockTags.BARRELS)
				|| state.is(ConventionalBlockTags.PLAYER_WORKSTATIONS_CRAFTING_TABLES)
				|| state.is(ConventionalBlockTags.PLAYER_WORKSTATIONS_FURNACES)
				|| state.getFluidState().is(FluidTags.WATER)
				|| state.getFluidState().is(FluidTags.LAVA)
				|| state.is(Blocks.NETHER_BRICKS)
				|| state.is(Blocks.SPAWNER)
				|| state.is(Blocks.NETHER_WART)
				|| state.is(Blocks.OBSIDIAN)
				|| state.is(Blocks.NETHER_PORTAL);
	}
}
