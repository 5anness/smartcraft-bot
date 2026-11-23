package de.hannes.aiplayer.action;

import com.google.gson.JsonObject;
import de.hannes.aiplayer.nav.Cell;
import de.hannes.aiplayer.nav.Goal;
import de.hannes.aiplayer.nav.Moves;
import de.hannes.aiplayer.nav.Pos;
import de.hannes.aiplayer.nav.WorldCells;
import de.hannes.aiplayer.perception.GameJson;
import de.hannes.aiplayer.skill.PathFollower;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/** {@code goto}: läuft zu einer Blockposition. Schema: {@code schemas/commands/goto.schema.json}. */
public final class GotoAction extends Action {
	@Override
	public boolean allowsSelfDefense() {
		return true;
	}

	private static final int TICKS_PER_BLOCK = 20;
	private static final int BASE_TIMEOUT_TICKS = 400;
	private static final int MAX_TIMEOUT_TICKS = 6000;
	/** Abbauen und Setzen kosten Zeit; mit diesen Optionen gibt es deutlich mehr davon. */
	private static final int EDIT_TIMEOUT_FACTOR = 4;

	private final Pos goal;
	private final double tolerance;
	private Moves.Options options;
	private PathFollower follower;
	private final int timeoutTicks;
	private boolean goalChecked;

	public GotoAction(ServerPlayer bot, Pos goal, double tolerance, Moves.Options options) {
		this.goal = goal;
		this.tolerance = tolerance;
		this.options = options;
		this.follower = new PathFollower(Goal.near(goal, tolerance), options);
		// Großzügig: Umwege und Schwimmen sind langsamer als die Luftlinie.
		double distance = Math.sqrt(goal.distanceSqr(bot.getBlockX(), bot.getBlockY(), bot.getBlockZ()));
		int ticks = (int) (BASE_TIMEOUT_TICKS + distance * TICKS_PER_BLOCK) * (options.any() ? EDIT_TIMEOUT_FACTOR : 1);
		this.timeoutTicks = Math.min(MAX_TIMEOUT_TICKS, ticks);
	}

	/** Wer im Nether baut, baut über Lava: Lava daneben ist dann der Normalfall, kein Grund zum Abbruch (Hannes' Lauf
	 * 17.10.: 10 Abbrüche mit Lava 1 Block neben der Brücke auf dem Weg zur Festung). Die Wegfindung meidet Lava selbst. */
	@Override
	public boolean cancelOnDangerInNether() {
		return !options.allowPlace();
	}

	/** Im Nether bricht Schaden ein goto mit Bauen nicht ab: Drachenlauf 24, ein Lohen-Feuerball brach den goto mitten im
	 * Brückenschritt ab, der Bot trat in die Lücke und fiel 22 Blöcke. Anhalten entscheidet der Begleitprozess (Gegner). */
	private boolean bridgingInNether;

	@Override
	public boolean cancelOnDamage() {
		return !bridgingInNether;
	}

	@Override
	public int timeoutTicks() {
		return timeoutTicks;
	}

	private static final int BLOCK_RESERVE = PathFollower.BLOCK_RESERVE;
	private int ticksRun;
	/** Bauen war erlaubt, aber die Bausteine lagen schon beim Start an der Reserve. */
	private boolean placingOff;

	/** Lohe oder Ghast bis 24 Blöcke mit freier Sicht auf den Bot, sonst {@code null}. */
	static net.minecraft.world.entity.LivingEntity shooterInSight(ServerPlayer bot) {
		for (net.minecraft.world.entity.LivingEntity e : bot.level().getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
				bot.getBoundingBox().inflate(24), e -> e.isAlive() && (e instanceof net.minecraft.world.entity.monster.Blaze
						|| e instanceof net.minecraft.world.entity.monster.Ghast
						// Skelette, die auf ihn zielen: Drachenlauf 45 wurde auf einem Grat (16 Blöcke tief zu drei Seiten) von
						// einem Pfeil in einen Lavasee gestoßen.
						|| e instanceof net.minecraft.world.entity.monster.skeleton.AbstractSkeleton skeleton
								&& !(skeleton instanceof net.minecraft.world.entity.monster.skeleton.WitherSkeleton)
								&& skeleton.getTarget() == bot))) {
			if (e.hasLineOfSight(bot)) {
				return e;
			}
		}
		return null;
	}
	private int startBlocks = -1;
	/** Bausteine beim Start, wenn sie im Nether nicht für eine Brücke bis zum Ziel reichten (sonst 0). */
	private int limitedBlocks;

	/**
	 * Wo die letzte eigene Brücke begann (fester Boden), je Bot und Dimension. Drachenlauf 65: Das erste goto baute 193 Blöcke
	 * über das Lavameer, endete mit few_blocks, und die KI hielt sich auf der Brückenspitze für eingeschlossen; den
	 * begehbaren Rückweg über die eigene Brücke kannte sie nicht.
	 */
	private record BridgeStart(String dimension, BlockPos pos) {
	}

	private static final java.util.Map<java.util.UUID, BridgeStart> BRIDGE_STARTS = new java.util.HashMap<>();
	private static final int BRIDGE_MIN_BLOCKS = 8;
	private BlockPos startPos;

	/** Rückweg zum Anfang der eigenen Brücke in die Daten schreiben (nur wenn er weiter als 8 Blöcke weg ist). */
	private void addWayBack(ServerPlayer bot, JsonObject data) {
		BridgeStart start = BRIDGE_STARTS.get(bot.getUUID());
		// Nur im Nether: In der Oberwelt nannte Lauf 75 eine 280 Blöcke entfernte Stelle in einer Höhle als Rückweg.
		if (start == null || bot.level().dimension() != net.minecraft.world.level.Level.NETHER || !start.dimension().equals(bot.level().dimension().identifier().toString())
				|| start.pos().distSqr(bot.blockPosition()) <= 64
				// Nur nahe Rückwege auf ähnlicher Höhe: Drachenlauf 88 baute sich zu einem way_back 70 Blöcke höher eine Säule
				// über einem Lavasee und stürzte hinein.
				|| Math.abs(start.pos().getY() - bot.getBlockY()) > 8 || start.pos().distSqr(bot.blockPosition()) > 150 * 150) {
			return;
		}
		data.add("way_back", GameJson.pos(start.pos()));
		data.addProperty("way_back_hint", "your own bridge leads back to way_back on firm ground: goto way_back without "
				+ "allow_place (needs no blocks), mine netherrack there (128+), then come back");
	}

	private void noteBridge(ServerPlayer bot) {
		if (startPos != null && follower.placedBlocks().size() >= BRIDGE_MIN_BLOCKS) {
			String dimension = bot.level().dimension().identifier().toString();
			BridgeStart old = BRIDGE_STARTS.get(bot.getUUID());
			if (old == null || !old.dimension().equals(dimension)) {
				BRIDGE_STARTS.put(bot.getUUID(), new BridgeStart(dimension, startPos));
			}
		}
	}

	/** Geländer an jeder offenen Seite (ohne Boden darunter): erst den Block darunter, dann auf Fuß- und Kopfhöhe. @return gesetzte Blöcke. */
	public static int rail(ServerPlayer bot) {
		var level = bot.level();
		BlockPos feet = bot.blockPosition();
		int placed = 0;
		for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
			// Die Reserve bleibt auch beim Geländer: Drachenlauf 85 stand am Lohenspawner ohne einen Baustein und konnte sich
			// weder decken noch einbauen.
			if (de.hannes.aiplayer.skill.Inv.countScaffold(bot) <= BLOCK_RESERVE) {
				break;
			}
			BlockPos cell = feet.relative(d);
			if (!level.getBlockState(cell).canBeReplaced() || !level.getFluidState(cell).isEmpty()) {
				continue;
			}
			BlockPos under = cell.below();
			if (level.getBlockState(under).canBeReplaced()) {
				int slot = de.hannes.aiplayer.skill.Inv.findScaffold(bot);
				if (slot < 0 || !de.hannes.aiplayer.skill.BlockPlacer.hasSupport(level, under)
						|| de.hannes.aiplayer.skill.BlockPlacer.place(bot, slot, under) != null) {
					continue;
				}
				placed++;
			}
			int slot = de.hannes.aiplayer.skill.Inv.findScaffold(bot);
			if (slot >= 0 && de.hannes.aiplayer.skill.BlockPlacer.hasSupport(level, cell)
					&& de.hannes.aiplayer.skill.BlockPlacer.place(bot, slot, cell) == null) {
				placed++;
				// Zweite Lage auf Kopfhöhe: Über ein Geländer auf Fußhöhe warf ein Lohen-Feuerball den Bot in Drachenlauf 77
				// (25 Blöcke Sturz).
				BlockPos head = cell.above();
				int headSlot = de.hannes.aiplayer.skill.Inv.findScaffold(bot);
				if (headSlot >= 0 && level.getBlockState(head).canBeReplaced() && level.getFluidState(head).isEmpty()
						&& de.hannes.aiplayer.skill.BlockPlacer.place(bot, headSlot, head) == null) {
					placed++;
				}
			}
		}
		return placed;
	}

	@Override
	public Outcome tick(ServerPlayer bot) {
		if (!goalChecked) {
			goalChecked = true;
			startPos = bot.onGround() ? bot.blockPosition().immutable() : null;
			// Zurück am Anfang der Brücke: vergessen.
			BridgeStart old = BRIDGE_STARTS.get(bot.getUUID());
			if (old != null && old.pos().distSqr(bot.blockPosition()) <= 64) {
				BRIDGE_STARTS.remove(bot.getUUID());
			}
			if (!options.any() && !canStandNearGoal(bot)) {
				JsonObject data = progress(bot);
				data.addProperty("hint", "goal_not_standable");
				data.addProperty("goal_block", GameJson.blockId(
						bot.level().getBlockState(new BlockPos(goal.x(), goal.y(), goal.z()))));
				return Outcome.failed("no_path", data);
			}
		}
		// Reserve an Bausteinen: Die letzten BLOCK_RESERVE verbaut goto nie. Drachenlauf 17 verbaute über einem Lavasee alle 225
		// Blöcke und stand am Brückenende ohne Ausweg; Lauf 23 bekam few_blocks, startete goto neu und verbaute den Rest.
		bridgingInNether = options.allowPlace() && bot.level().dimension() == net.minecraft.world.level.Level.NETHER;
		if (options.allowPlace()) {
			int blocks = de.hannes.aiplayer.skill.Inv.countScaffold(bot);
			if (startBlocks < 0) {
				startBlocks = blocks;
				double distance = Math.sqrt(goal.distanceSqr(bot.getBlockX(), bot.getBlockY(), bot.getBlockZ()));
				// Im Nether keine Brücke anfangen, die nicht ans Ziel reicht: Drachenläufe 62, 65, 68 bauten über einem Lavameer los
				// und strandeten auf der Brückenspitze. Reichen die verbaubaren Blöcke nicht für die Luftlinie, nur laufen und graben;
				// findet sich so kein Weg, holt die KI erst Netherrack.
				boolean shortForBridge = bot.level().dimension() == net.minecraft.world.level.Level.NETHER
						&& blocks - BLOCK_RESERVE < distance;
				if (shortForBridge && blocks > BLOCK_RESERVE) {
					limitedBlocks = blocks;
				}
				if (blocks <= BLOCK_RESERVE || shortForBridge) {
					// Schon an der Reserve: nur laufen und graben, keine Brücken.
					placingOff = blocks <= BLOCK_RESERVE;
					options = new Moves.Options(options.allowDig(), false, options.digFortress(), options.maxDrop(), options.allowPillar());
					follower = new PathFollower(Goal.near(goal, tolerance), options);
				}
			} else if (blocks <= BLOCK_RESERVE && bot.onGround()) {
				follower.abort(bot);
				JsonObject data = progress(bot);
				data.addProperty("blocks_left", blocks);
				noteBridge(bot);
				addWayBack(bot, data);
				data.addProperty("hint", "only " + blocks + " building blocks left, kept as reserve (lava, way back): collect at "
						+ "least 64 netherrack/cobblestone before bridging further");
				return Outcome.failed("few_blocks", data);
			}
		}
		// Unter Beschuss an der Kante nicht weiterbauen: Drachenläufe 24, 28 und 30 stießen Lohen-Feuerbälle von Brücken und
		// Kanten am Weg zum Spawner. Erst den Schützen erledigen.
		if (bridgingInNether && ticksRun++ % 10 == 0 && de.hannes.aiplayer.skill.Fighter.nearDrop(bot)) {
			net.minecraft.world.entity.LivingEntity shooter = shooterInSight(bot);
			if (shooter != null) {
				follower.abort(bot);
				JsonObject data = progress(bot);
				// Offene Seiten sichern, bevor die KI kämpft: Drachenlauf 52 stand auf seiner Brücke (24 tief zu drei Seiten),
				// begann defend gegen ein Skelett und stürzte. Je offene Seite: Block darunter (verbreitert) und Geländer auf Fußhöhe.
				data.addProperty("railing_placed", rail(bot));
				data.add("shooter", GameJson.ints(shooter.getBlockX(), shooter.getBlockY(), shooter.getBlockZ()));
				data.addProperty("shooter_type", GameJson.entityType(shooter));
				data.addProperty("hint", "a " + GameJson.entityType(shooter).replace("minecraft:", "") + " shoots at you while you stand "
						+ "at a drop: its hits knock you off. Open sides got a railing (railing_placed). shoot it from here (shoot), do not "
						+ "walk around (no defend/attack on a bridge), then goto again");
				return Outcome.failed("under_fire", data);
			}
		}
		return switch (follower.tick(bot)) {
			case RUNNING -> null;
			case ARRIVED -> {
				noteBridge(bot);
				yield Outcome.ok(progress(bot));
			}
			case FAILED -> {
				noteBridge(bot);
				JsonObject data = progress(bot);
				addWayBack(bot, data);
				data.addProperty("hint", follower.hint());
				// Sonst sieht die KI nur no_path trotz allow_place und weiß nicht, dass gar nicht gebaut wurde (test_blocks: Podest
				// und Insel mit wenigen Blöcken endeten so mit search_stalled).
				if (placingOff) {
					data.addProperty("bridging_off", "only " + startBlocks + " building blocks, the last " + BLOCK_RESERVE + " are never "
							+ "used: no bridge and no pillar on this way. collect 64 cobblestone/dirt/netherrack first, then goto again");
				}
				if (limitedBlocks > 0) {
					data.addProperty("bridging_off", "only " + limitedBlocks + " building blocks for " + Math.round(Math.sqrt(goal.distanceSqr(
							bot.getBlockX(), bot.getBlockY(), bot.getBlockZ()))) + " blocks distance: no bridge started (would strand you). "
							+ "Mine netherrack first (collect netherrack, count 128, allow_dig) or dig the way (tunnel)");
				}
				if (follower.closest() != null) {
					// Von hier kann die KI weiterdenken: näher kommt der Bot ohne Hilfe nicht heran.
					Pos closest = follower.closest();
					data.add("closest", GameJson.ints(closest.x(), closest.y(), closest.z()));
					data.addProperty("closest_distance", GameJson.round1(follower.closestDistance()));
				}
				yield Outcome.failed(follower.failure(), data);
			}
		};
	}

	/**
	 * Schneller Vorab-Test: Gibt es im Zielbereich überhaupt ein Feld zum Stehen? Spart die
	 * Suche, wenn das Ziel mitten im Fels oder in der Luft liegt. Ist der Bereich noch
	 * nicht geladen, wird losgelaufen und unterwegs neu geprüft.
	 */
	private boolean canStandNearGoal(ServerPlayer bot) {
		WorldCells cells = new WorldCells(bot.level());
		int radius = (int) Math.ceil(tolerance);
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dy = -radius; dy <= radius; dy++) {
				for (int dz = -radius; dz <= radius; dz++) {
					if (dx * dx + dy * dy + dz * dz > tolerance * tolerance) {
						continue;
					}
					int x = goal.x() + dx;
					int y = goal.y() + dy;
					int z = goal.z() + dz;
					if (cells.at(x, y, z) == Cell.UNLOADED || Moves.kind(cells, x, y, z) != Moves.Kind.NONE) {
						return true;
					}
				}
			}
		}
		return false;
	}

	@Override
	public void release(ServerPlayer bot) {
		noteBridge(bot);
		follower.abort(bot);
	}

	@Override
	public JsonObject progress(ServerPlayer bot) {
		JsonObject data = new JsonObject();
		data.addProperty("distance_left", GameJson.round1(
				Math.sqrt(goal.distanceSqr(bot.getBlockX(), bot.getBlockY(), bot.getBlockZ()))));
		data.addProperty("replans", follower.replans());
		data.addProperty("search_nodes", follower.searchedNodes());
		data.addProperty("search_ms_peak", GameJson.round1(follower.peakSearchMillis()));
		if (options.any()) {
			data.addProperty("blocks_dug", follower.dugBlocks());
			data.addProperty("blocks_placed", follower.placedBlocks().size());
		}
		return data;
	}
}
