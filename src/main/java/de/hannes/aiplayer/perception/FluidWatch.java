package de.hannes.aiplayer.perception;

import com.google.gson.JsonObject;
import de.hannes.aiplayer.memory.Places;
import de.hannes.aiplayer.protocol.Priority;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Schaut alle 5 Sekunden weiter um sich als der Snapshot (Umkreis 24 statt 8) nach Wasser- und
 * Lavaquellen sowie Obsidian (zerfallene Portale), Spawnern und Lohen und meldet eine Art, sobald sie neu in
 * Sicht kommt. Im Nether ist der Umkreis 32.
 * So läuft der Bot auf der Suche danach nicht vorbei. Der Scan ist auf mehrere Ticks verteilt (eine
 * Höhenebene je Tick), sonst kostete er einen Tick bis zu 25 ms. Schema: {@code fluid_spotted}.
 */
final class FluidWatch {
	static final int INTERVAL_TICKS = 100;
	private static final int RADIUS = 24;
	/** Im Nether weiter: Festungen sind groß, Lohen und ihr Spawner liegen oft außerhalb von 24 Blöcken. */
	private static final int NETHER_RADIUS = 32;
	private int radius = RADIUS;
	private static final int VERTICAL = 10;
	private static final int MAX_SIZE = 64;
	/** So viele Lavaquellen braucht ein Portal (10 Obsidian). */
	private static final int PORTAL_LAVA = 10;
	private static final int MAX_LAVA_SOURCES = 2000;
	private final List<BlockPos> lavaSources = new ArrayList<>();
	private int reportedLavaSize;

	private boolean sawWater;
	private boolean sawLava;
	private boolean sawObsidian;
	private boolean sawSpawner;
	private boolean sawBlaze;
	private final Found spawner = new Found();
	private final Found portal = new Found();
	private final Found frame = new Found();
	private boolean sawFrame;
	private boolean sawFortress;
	private boolean sawVillage;
	private boolean sawPortalRoom;
	private int fortressCountdown;
	private static final int FORTRESS_EVERY = 4;

	/** Laufender Durchgang: Mittelpunkt und nächste Ebene; {@code center == null} zwischen den Durchgängen. */
	private BlockPos center;
	private int layer;
	private final Found water = new Found();
	private final Found lava = new Found();
	private final Found obsidian = new Found();

	private static final class Found {
		BlockPos pos;
		double distance = Double.MAX_VALUE;

		void offer(BlockPos.MutableBlockPos cursor, double distance) {
			if (distance < this.distance) {
				this.distance = distance;
				pos = cursor.immutable();
			}
		}

		void clear() {
			pos = null;
			distance = Double.MAX_VALUE;
		}
	}

	void reset() {
		sawWater = false;
		sawLava = false;
		sawObsidian = false;
		sawSpawner = false;
		sawBlaze = false;
		sawFrame = false;
		sawFortress = false;
		center = null;
	}

	void tick(ServerPlayer bot, long tick, EventBus bus) {
		if (center == null) {
			if (tick % INTERVAL_TICKS != 0) {
				return;
			}
			center = bot.blockPosition();
			radius = bot.level().dimension() == Level.NETHER ? NETHER_RADIUS : RADIUS;
			layer = -VERTICAL;
			spawner.clear();
			portal.clear();
			frame.clear();
			water.clear();
			lava.clear();
			lavaSources.clear();
			obsidian.clear();
		}
		scanLayer(bot.level(), layer);
		if (++layer <= VERTICAL) {
			return;
		}
		center = null;
		ServerLevel level = bot.level();
		if (water.pos != null && !sawWater) {
			emit(bus, tick, "water", water, size(level, water.pos, FluidWatch::isWaterSource));
		}
		// Lava: die größte Fläche im Umkreis, nicht die nächste; ein See weiter weg lohnt mehr als ein Einzelblock.
		BlockPos bestLava = null;
		int bestSize = 0;
		Set<BlockPos> counted = new HashSet<>();
		for (BlockPos pos : lavaSources) {
			if (counted.contains(pos)) {
				continue;
			}
			Set<BlockPos> cluster = cluster(level, pos, FluidWatch::isLavaSource);
			counted.addAll(cluster);
			if (cluster.size() > bestSize) {
				bestSize = cluster.size();
				bestLava = pos;
			}
		}
		// Neu melden, wenn Lava neu in Sicht ist oder eine Fläche, die für ein Portal reicht, die erste ist.
		if (bestLava != null && (!sawLava || bestSize >= PORTAL_LAVA && reportedLavaSize < PORTAL_LAVA)) {
			Found best = new Found();
			best.pos = bestLava;
			best.distance = bestLava.distSqr(bot.blockPosition());
			emit(bus, tick, "lava", best, Math.min(bestSize, MAX_SIZE));
			if (bestSize >= PORTAL_LAVA) {
				Places.rememberArea(Places.Kind.LAVA_LAKE, level, bestLava);
			}
			reportedLavaSize = bestSize;
		}
		if (bestLava == null) {
			reportedLavaSize = 0;
		}
		if (obsidian.pos != null && !sawObsidian) {
			emit(bus, tick, "obsidian", obsidian, size(level, obsidian.pos, FluidWatch::isObsidian));
		}
		if (frame.pos != null) {
			Places.rememberArea(Places.Kind.END_PORTAL, level, frame.pos);
		}
		if (spawner.pos != null) {
			Places.rememberArea(Places.Kind.SPAWNER, level, spawner.pos);
		}
		if (portal.pos != null) {
			Places.rememberArea(Places.Kind.NETHER_PORTAL, level, portal.pos);
		}
		if (frame.pos != null && !sawFrame) {
			emit(bus, tick, "end_portal_frame", frame, 1);
		}
		sawFrame = frame.pos != null;
		if (spawner.pos != null && !sawSpawner) {
			emit(bus, tick, "spawner", spawner, 1);
		}
		sawSpawner = spawner.pos != null;
		// Lohen selbst: Wesen statt Blöcke, deshalb einmal je Durchgang über die Entity-Suche.
		Blaze blaze = level.getEntitiesOfClass(Blaze.class, bot.getBoundingBox().inflate(radius, VERTICAL, radius))
				.stream().min(Comparator.comparingDouble(bot::distanceToSqr)).orElse(null);
		if (blaze != null && !sawBlaze) {
			Found found = new Found();
			found.pos = blaze.blockPosition();
			found.distance = blaze.distanceToSqr(bot);
			emit(bus, tick, "blaze", found, 1);
		}
		sawBlaze = blaze != null;
		sawWater = water.pos != null;
		sawLava = lava.pos != null;
		sawObsidian = obsidian.pos != null;
		// Netherfestung: regelmäßig prüfen, ob eine bis 100 Blöcke weit ist, damit der Bot auf dem Weg nicht daran
		// vorbeigräbt; sonst sähe er sie nur, wenn er selbst look aufruft.
		// Die Strukturabfrage kostet bis 28 ms: nur jeden vierten Durchgang (alle 20 Sekunden).
		if (++fortressCountdown % FORTRESS_EVERY != 0) {
			return;
		}
		// Dorf in der Oberwelt, solange Betten fürs End fehlen (Wunsch von Hannes): dort die Betten abbauen.
		if (level.dimension() == Level.OVERWORLD) {
			JsonObject village = de.hannes.aiplayer.skill.NetherKit.bedsMissing(bot) ? Snapshot.villageInSight(level, bot.blockPosition()) : null;
			if (village != null) {
				Places.rememberArea(Places.Kind.VILLAGE, level, GameJson.toBlockPos(village.getAsJsonArray("approx_pos")));
			}
			if (village != null && !sawVillage) {
				JsonObject data = new JsonObject();
				data.addProperty("kind", "village");
				data.addProperty("size", 1);
				data.add("pos", village.get("approx_pos"));
				data.addProperty("distance", village.get("distance").getAsInt());
				data.addProperty("direction", village.get("direction").getAsString());
				data.add("hint", village.get("hint"));
				bus.emit("fluid_spotted", tick, Priority.NORMAL, data);
			}
			sawVillage = village != null;
			JsonObject room = Snapshot.portalRoomNear(level, bot.blockPosition());
			if (room != null) {
				Places.rememberArea(Places.Kind.END_PORTAL, level, GameJson.toBlockPos(room.getAsJsonArray("pos")));
			}
			if (room != null && !sawPortalRoom) {
				JsonObject data = new JsonObject();
				data.addProperty("kind", "end_portal_room");
				data.addProperty("size", 1);
				data.add("pos", room.get("pos"));
				data.addProperty("distance", room.get("distance").getAsInt());
				data.addProperty("direction", room.get("direction").getAsString());
				data.add("hint", room.get("hint"));
				bus.emit("fluid_spotted", tick, Priority.NORMAL, data);
			}
			sawPortalRoom = room != null;
			return;
		}
		if (level.dimension() != Level.NETHER) {
			return;
		}
		JsonObject fortress = Snapshot.fortressInSight(level, bot.blockPosition());
		if (fortress != null) {
			Places.rememberArea(Places.Kind.FORTRESS, level, GameJson.toBlockPos(fortress.getAsJsonArray("approx_pos")));
		}
		if (fortress != null && !sawFortress) {
			JsonObject data = new JsonObject();
			data.addProperty("kind", "fortress");
			data.addProperty("size", 1);
			data.add("pos", fortress.get("approx_pos"));
			data.addProperty("distance", fortress.get("distance").getAsInt());
			data.addProperty("direction", fortress.get("direction").getAsString());
			bus.emit("fluid_spotted", tick, Priority.NORMAL, data);
		}
		sawFortress = fortress != null;
	}

	private void scanLayer(ServerLevel level, int dy) {
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
				// Ungeladene Chunks nicht anfassen: getBlockState würde sie sofort laden (im Test bis 80 ms).
				if (!level.hasChunkAt(cursor)) {
					continue;
				}
				double distance = dx * dx + dy * dy + dz * dz;
				BlockState state = level.getBlockState(cursor);
				if (state.is(Blocks.END_PORTAL_FRAME)) {
					frame.offer(cursor, distance);
					continue;
				}
				if (state.is(Blocks.NETHER_PORTAL)) {
					portal.offer(cursor, distance);
					continue;
				}
				if (state.is(Blocks.SPAWNER)) {
					spawner.offer(cursor, distance);
					continue;
				}
				if (state.is(Blocks.OBSIDIAN) || state.is(Blocks.CRYING_OBSIDIAN)) {
					obsidian.offer(cursor, distance);
					continue;
				}
				FluidState fluid = state.getFluidState();
				if (fluid.isEmpty() || !fluid.isSource()) {
					continue;
				}
				if (fluid.is(FluidTags.WATER)) {
					water.offer(cursor, distance);
				} else if (fluid.is(FluidTags.LAVA)) {
					lava.offer(cursor, distance);
					if (lavaSources.size() < MAX_LAVA_SOURCES) {
						lavaSources.add(cursor.immutable());
					}
				}
			}
		}
	}

	private static boolean isWaterSource(BlockState state) {
		return state.getFluidState().is(FluidTags.WATER) && state.getFluidState().isSource();
	}

	private static boolean isLavaSource(BlockState state) {
		return state.getFluidState().is(FluidTags.LAVA) && state.getFluidState().isSource();
	}

	private static boolean isObsidian(BlockState state) {
		return state.is(Blocks.OBSIDIAN) || state.is(Blocks.CRYING_OBSIDIAN);
	}

	/**
	 * Wie viele gleichartige Blöcke zusammenhängend an der Fundstelle liegen, gezählt bis {@value #MAX_SIZE}.
	 * Für Obsidian zählt ein einzelner Lavablock wenig; ein See lohnt den Weg.
	 */
	private static int size(ServerLevel level, BlockPos start, Predicate<BlockState> matches) {
		return Math.min(cluster(level, start, matches).size(), MAX_SIZE);
	}

	private static Set<BlockPos> cluster(ServerLevel level, BlockPos start, Predicate<BlockState> matches) {
		Set<BlockPos> seen = new HashSet<>();
		ArrayDeque<BlockPos> open = new ArrayDeque<>();
		open.add(start);
		seen.add(start);
		while (!open.isEmpty() && seen.size() < MAX_SIZE) {
			BlockPos pos = open.poll();
			for (Direction direction : Direction.values()) {
				BlockPos next = pos.relative(direction);
				if (!seen.contains(next) && level.isLoaded(next) && matches.test(level.getBlockState(next))) {
					seen.add(next);
					open.add(next);
				}
			}
		}
		return seen;
	}

	private static void emit(EventBus bus, long tick, String kind, Found found, int size) {
		JsonObject data = new JsonObject();
		data.addProperty("kind", kind);
		data.addProperty("size", size);
		data.add("pos", GameJson.pos(found.pos));
		data.addProperty("distance", (int) Math.round(Math.sqrt(found.distance)));
		bus.emit("fluid_spotted", tick, Priority.NORMAL, data);
	}
}
