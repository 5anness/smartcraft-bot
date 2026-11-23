package de.hannes.aiplayer.action;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.hannes.aiplayer.nav.Moves;
import de.hannes.aiplayer.nav.Pos;
import de.hannes.aiplayer.perception.GameJson;
import de.hannes.aiplayer.protocol.Event.Status;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Zerstört ALLE Endkristalle der Hauptinsel nacheinander. Seit 29.10.2026 wieder mit Türmen (Weg A, Entscheidung von
 * Hannes): Ein Bett macht je Landung etwa 50 Schaden, und stehende Kristalle heilten den Drachen bis zur nächsten Landung
 * voll (End-Läufe 71–74). Ohne Kristalle bleibt der Schaden.
 * <p>
 * Je Kristall: (1) frei mit Standplatz am Boden ({@code shoot_from}): hingehen und schießen. (2) sonst (Käfig oder kein
 * Platz am Boden): ein eigener Turm auf Kristallhöhe {@link #TOWER_DISTANCE} Blöcke daneben zur Inselmitte hin, senkrecht
 * hochbauen mit Geländer je Stufe, oben Wand und Dach mit einer Schießscharte; bei Käfig erst näher heran, die Gitter in
 * der Schusslinie abbauen, zurück auf den Turm. Danach die eigene Säule hinab. Höchstens 90 s je Kristall.
 */
public final class DestroyCrystalsAction extends Action {
	private static final int SEARCH_RADIUS = 128;
	/** Nur vom Boden schießen, alles andere überspringen (vor bed_cycle: weniger Kristalle, öfter Landungen). */
	private final boolean groundOnly;

	public DestroyCrystalsAction() {
		this(false);
	}

	public DestroyCrystalsAction(boolean groundOnly) {
		this.groundOnly = groundOnly;
	}
	/** Abstand des eigenen Turms zum Kristall: außerhalb der Explosion (Stärke 6, Schaden bis 12 Blöcke; End-Lauf 12). */
	private static final int TOWER_DISTANCE = 13;
	/** Von hier aus erreicht er die Gitter (Käfig etwa 2 Blöcke um den Kristall, Reichweite 4,5). */
	private static final int BAR_DISTANCE = 5;
	private static final int MAX_ATTEMPTS = 2;
	private static final int SHOTS = 8;
	private static final int TICKS_PER_CRYSTAL = 20 * 90;
	private static final int MIN_BLOCKS = 160;
	private static final int GATHER_COUNT = 192;
	/** Türme aus (End-Läufe 12–65 und 75–82: auf jedem eigenen Turm gestorben). */
	private static final boolean TOWERS = false;

	private enum Phase { NEXT, TO_SPOT, SHOOT, TO_TOWER_BASE, TO_TOWER, TO_CAGE, BREAK_BARS, BACK_TO_TOWER, DOWN, FIGHT, FORTIFY, GATHER, TO_GATHER, EXIT }

	private Phase phase = Phase.NEXT;
	private Action step;
	private EndCrystal crystal;
	private BlockPos tower;
	private BlockPos near;
	private BlockPos towerBase;
	private final List<BlockPos> bars = new ArrayList<>();
	private final List<BlockPos> rails = new ArrayList<>();
	private final List<BlockPos> walls = new ArrayList<>();
	private final List<BlockPos> exits = new ArrayList<>();
	private final Map<Integer, Integer> attempts = new HashMap<>();
	private final JsonArray skipped = new JsonArray();
	private de.hannes.aiplayer.skill.PillarUp pillar;
	private int destroyed;
	private int arrows;
	private boolean onTower;
	private boolean climbing;
	private int ticksOnCrystal;
	private int digDown;
	private int gathered;
	private Phase loggedPhase;

	@Override
	public int timeoutTicks() {
		return 20 * 60 * 15;
	}

	/** Schaden bricht nicht ab: Der Drache trifft immer wieder; aus Säurewolken holt ihn der Reflex. */
	@Override
	public boolean cancelOnDamage() {
		return false;
	}

	@Override
	public boolean cancelOnDanger() {
		return false;
	}

	@Override
	public Outcome tick(ServerPlayer bot) {
		if (bot.level().dimension() != Level.END) {
			return Outcome.failed("not_in_end", progress(bot));
		}
		if (bot.getHealth() <= 6) {
			JsonObject data = progress(bot);
			data.addProperty("hint", "health low: eat (golden_apple), step away from the edge, then destroy_crystals again");
			return Outcome.cancelled("health_low", data);
		}
		// Wer ihn trifft (Enderman, Endermite), wird zuerst bekämpft (End-Lauf 13).
		// Auch auf dem Weg zum Turmfuß (onTower ist dann schon gesetzt; End-Lauf 90: elf Enderman-Treffer ohne Gegenwehr).
		if (phase != Phase.FIGHT && (!onTower || phase == Phase.TO_TOWER_BASE)) {
			net.minecraft.world.entity.LivingEntity attacker = bot.getLastHurtByMob();
			if (attacker != null && attacker.isAlive() && !(attacker instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon)
					&& bot.tickCount - bot.getLastHurtByMobTimestamp() < 40 && attacker.distanceTo(bot) < 8) {
				if (step != null) {
					step.release(bot);
				}
				step = new AttackAction(attacker, 4);
				if (phase == Phase.TO_TOWER_BASE) {
					onTower = false;
					bedMode = false;
				}
				phase = Phase.FIGHT;
				return null;
			}
		}
		// Höchstens 90 s je Kristall (End-Lauf 62: 14 Minuten Türme ohne Schuss); auf dem Turm erst hinab.
		// Endstein sammeln zählt nicht (End-Lauf 92: zwei Kristalle nach dem Sammeln mit too_long übersprungen).
		if (phase != Phase.GATHER && phase != Phase.TO_GATHER) {
			ticksOnCrystal++;
		}
		if (crystal != null && ticksOnCrystal > TICKS_PER_CRYSTAL && !onTower) {
			if (step != null) {
				step.release(bot);
				step = null;
			}
			climbing = false;
			pillar = null;
			attempts.put(crystal.getId(), MAX_ATTEMPTS);
			ticksOnCrystal = 0;
			return skip(bot, "too_long");
		}
		if (phase != loggedPhase) {
			de.hannes.aiplayer.AiPlayerMod.LOGGER.info("destroy_crystals phase " + loggedPhase + " -> " + phase + " pos=" + bot.blockPosition()
					+ " tower=" + tower + " onTower=" + onTower);
			loggedPhase = phase;
		}
		if (climbing) {
			return climb(bot);
		}
		if (bedStage > 0) {
			return bedStep(bot);
		}
		// Auf dem Turm (Abstieg, Wand bauen) nicht weitermachen, solange der Drache nah anfliegt (End-Lauf 81: beim Abstieg
		// in Höhe 77 trotz Geländer 46 Blöcke weit geschleudert).
		if (onTower && step != null && dragonNear(bot)) {
			return null;
		}
		if (step != null) {
			Outcome done = step.tick(bot);
			if (done == null) {
				return null;
			}
			step.release(bot);
			step = null;
			return after(bot, done);
		}
		return next(bot);
	}

	/** Senkrecht hoch bis Kristallhöhe, nach jeder Stufe Geländer rundum (End-Lauf 58: ohne Geländer vom Turm gestoßen). */
	private Outcome climb(ServerPlayer bot) {
		if (bot.getBlockY() >= tower.getY()) {
			climbing = false;
			pillar = null;
			return after(bot, Outcome.ok(new JsonObject()));
		}
		if (!rails.isEmpty()) {
			BlockPos rail = rails.remove(0);
			int slot = de.hannes.aiplayer.skill.Inv.findScaffold(bot);
			if (slot >= 0 && bot.level().getBlockState(rail).getCollisionShape(bot.level(), rail).isEmpty()) {
				de.hannes.aiplayer.skill.BlockPlacer.place(bot, slot, rail);
			}
			return null;
		}
		// Nicht klettern, solange der Drache nah anfliegt: Beim Sprung über die Stufe warf ihn ein Treffer trotz Geländer
		// hinaus (End-Lauf 79, Höhe 82). Im Geländer warten, bis er weg ist oder hockt.
		if (pillar == null) {
			BlockPos above = bot.blockPosition().above(2);
			if (dragonNear(bot)) {
				// Deckel über dem Kopf: In der offenen Röhre schleuderte ein Treffer ihn nach oben hinaus (End-Lauf 91,
				// Höhe 86, Sturztod). Mit Deckel ist die Zelle rundum zu.
				if (bot.onGround() && bot.level().getBlockState(above).canBeReplaced()) {
					int slot = de.hannes.aiplayer.skill.Inv.findScaffold(bot);
					if (slot >= 0 && de.hannes.aiplayer.skill.BlockPlacer.place(bot, slot, above) == null) {
						cap = above;
					}
				}
				return null;
			}
			if (cap != null) {
				if (!bot.level().getBlockState(cap).canBeReplaced()) {
					if (capBreaker == null) {
						capBreaker = new de.hannes.aiplayer.skill.BlockBreaker(cap, 3);
					}
					if (capBreaker.tick(bot) == de.hannes.aiplayer.skill.BlockBreaker.State.RUNNING) {
						return null;
					}
				}
				capBreaker = null;
				cap = null;
			}
			pillar = new de.hannes.aiplayer.skill.PillarUp();
		}
		de.hannes.aiplayer.skill.PillarUp.State state = pillar.tick(bot);
		if (state == de.hannes.aiplayer.skill.PillarUp.State.FAILED) {
			JsonObject data = new JsonObject();
			data.addProperty("hint", pillar.failure());
			climbing = false;
			pillar = null;
			return after(bot, Outcome.failed("no_path", data));
		}
		if (state == de.hannes.aiplayer.skill.PillarUp.State.DONE) {
			BlockPos feet = pillar.placed().above();
			pillar = null;
			// Fuß- und Kopfhöhe: eine Röhre (End-Lauf 76: ein Treffer warf ihn über das Geländer auf Fußhöhe, Sturztod).
			for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
				// Erst der Block darunter an die Säule: Auf Fußhöhe allein hat das Geländer keinen Halt (End-Lauf 78: auf 37
				// Stufen kein einziger Geländerblock, vom Treffer heruntergeworfen).
				rails.add(feet.relative(dir).below());
				rails.add(feet.relative(dir));
				rails.add(feet.relative(dir).above());
			}
		}
		return null;
	}

	private BlockPos cap;
	private de.hannes.aiplayer.skill.BlockBreaker capBreaker;
	private boolean bedMode;
	private int bedStage;
	private int bedTicks;

	/**
	 * Feld neben dem Obsidianturm auf der Seite zur Inselmitte, auf Höhe knapp unter der Spitze (Füße 2 unter dem Kristall).
	 */
	private static BlockPos besideTower(ServerPlayer bot, BlockPos crystalPos) {
		Vec3 in = new Vec3(-crystalPos.getX(), 0, -crystalPos.getZ());
		in = in.lengthSqr() < 0.01 ? new Vec3(1, 0, 0) : in.normalize();
		int feetY = crystalPos.getY() - 2;
		for (int d = 1; d <= 9; d++) {
			BlockPos cell = BlockPos.containing(crystalPos.getX() + 0.5 + in.x * d, feetY, crystalPos.getZ() + 0.5 + in.z * d);
			var state = bot.level().getBlockState(cell);
			if (!state.is(Blocks.OBSIDIAN) && !state.is(Blocks.BEDROCK) && !state.is(Blocks.IRON_BARS)) {
				return cell;
			}
		}
		return null;
	}

	/** Oben neben dem Turm: Dach (2 Blöcke), Bett darauf, zünden, dann hinab. */
	private Outcome bedStep(ServerPlayer bot) {
		if (dragonNear(bot) && bedStage < 4) {
			return null;
		}
		BlockPos feet = bot.blockPosition();
		int slot = de.hannes.aiplayer.skill.Inv.findScaffold(bot);
		switch (bedStage) {
			case 1, 2 -> {
				BlockPos roof = feet.above(bedStage + 1);
				if (bot.level().getBlockState(roof).canBeReplaced()) {
					if (slot < 0) {
						return finishBed(bot, "roof_failed");
					}
					String why = de.hannes.aiplayer.skill.BlockPlacer.place(bot, slot, roof);
					if (why != null) {
						StringBuilder around = new StringBuilder();
						for (int dy = -1; dy <= 3; dy++) {
							for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
								around.append(dy).append(dir.getName().charAt(0)).append('=')
										.append(bot.level().getBlockState(feet.above(dy).relative(dir)).getBlock().getDescriptionId()
												.replace("block.minecraft.", "")).append(' ');
							}
						}
						de.hannes.aiplayer.AiPlayerMod.LOGGER.info("destroy_crystals roof {} stage={} feet={} {}", why, bedStage, feet, around);
						// Über dem Kopf fehlt ein Halt (End-Lauf 85): erst einen Block auf ein Geländer daneben setzen.
						boolean propped = false;
						for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
							BlockPos side = roof.relative(dir);
							if (bot.level().getBlockState(side).canBeReplaced() && !bot.level().getBlockState(side.below()).canBeReplaced()
									&& de.hannes.aiplayer.skill.BlockPlacer.place(bot, slot, side) == null) {
								propped = true;
								break;
							}
						}
						// Auch kein Geländer auf Kopfhöhe (End-Lauf 88): erst das setzen, nächster Tick versucht das Dach erneut.
						for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
							if (propped) {
								break;
							}
							BlockPos side = feet.above().relative(dir);
							if (bot.level().getBlockState(side).canBeReplaced() && !bot.level().getBlockState(side.below()).canBeReplaced()
									&& de.hannes.aiplayer.skill.BlockPlacer.place(bot, slot, side) == null) {
								propped = true;
							}
						}
						if (!propped) {
							return finishBed(bot, "roof_failed");
						}
						return null;
					}
				}
				bedStage++;
				return null;
			}
			case 3 -> {
				int bedSlot = -1;
				for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
					if (bot.getInventory().getItem(i).is(net.minecraft.tags.ItemTags.BEDS)) {
						bedSlot = i;
						break;
					}
				}
				if (bedSlot < 0) {
					return finishBed(bot, "no_beds");
				}
				BlockPos roofTop = feet.above(3);
				// Blickrichtung weg vom Kristall: Das Kopfteil des Betts zeigt dorthin, wo Platz ist.
				net.minecraft.core.Direction out = net.minecraft.core.Direction.getApproximateNearest(
						bot.getX() - crystal.getX(), 0, bot.getZ() - crystal.getZ());
				BlockPos bedPos = roofTop.above();
				// Andere Richtung, wenn weg vom Kristall kein Platz ist (End-Lauf 92: no_bed_space in einer Turmecke).
				if (bot.level().getBlockState(bedPos).canBeReplaced() && !bot.level().getBlockState(bedPos.relative(out)).canBeReplaced()) {
					for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
						if (bot.level().getBlockState(bedPos.relative(dir)).canBeReplaced()) {
							out = dir;
							break;
						}
					}
				}
				if (!bot.level().getBlockState(bedPos).canBeReplaced() || !bot.level().getBlockState(bedPos.relative(out)).canBeReplaced()) {
					de.hannes.aiplayer.AiPlayerMod.LOGGER.info("destroy_crystals no_bed_space feet=" + feet + " roofTop="
							+ bot.level().getBlockState(roofTop).getBlock() + " bedPos=" + bot.level().getBlockState(bedPos).getBlock());
					return finishBed(bot, "no_bed_space");
				}
				de.hannes.aiplayer.skill.Inv.hold(bot, bedSlot);
				bot.setYRot(out.toYRot());
				bot.setXRot(-80);
				bot.gameMode.useItemOn(bot, bot.level(), bot.getMainHandItem(), net.minecraft.world.InteractionHand.MAIN_HAND,
						new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(roofTop).add(0, 0.5, 0),
								net.minecraft.core.Direction.UP, roofTop, false));
				if (!(bot.level().getBlockState(bedPos).getBlock() instanceof net.minecraft.world.level.block.BedBlock)) {
					return finishBed(bot, "bed_place_failed");
				}
				placedBed = bedPos;
				bedStage = 4;
				bedTicks = 0;
				return null;
			}
			case 4 -> {
				// Gemerkte Stelle statt Füße+4: Der Bot sackte zwischen Setzen und Zünden einen Block ab und klickte
				// den Dachblock (End-Lauf 87: crystal_survived, Bett nie explodiert).
				BlockPos bedPos = placedBed != null ? placedBed : feet.above(4);
				if (bot.level().getBlockState(bedPos).getBlock() instanceof net.minecraft.world.level.block.BedBlock) {
					de.hannes.aiplayer.skill.Combat.holdBestWeapon(bot);
					bot.gameMode.useItemOn(bot, bot.level(), bot.getMainHandItem(), net.minecraft.world.InteractionHand.MAIN_HAND,
							new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(bedPos), net.minecraft.core.Direction.UP, bedPos, false));
				}
				bedStage = 5;
				return null;
			}
			default -> {
				// Kurz warten, bis die Explosionen durch sind, dann hinab.
				if (++bedTicks < 20) {
					return null;
				}
				return finishBed(bot, crystal != null && crystal.isAlive() ? "crystal_survived" : null);
			}
		}
	}

	private BlockPos placedBed;

	private Outcome finishBed(ServerPlayer bot, String failure) {
		if (placedBed != null && crystal != null) {
			de.hannes.aiplayer.AiPlayerMod.LOGGER.info("destroy_crystals bed at " + placedBed + " still_bed="
					+ (bot.level().getBlockState(placedBed).getBlock() instanceof net.minecraft.world.level.block.BedBlock)
					+ " crystal=" + crystal.blockPosition());
		}
		placedBed = null;
		de.hannes.aiplayer.AiPlayerMod.LOGGER.info("destroy_crystals bed " + (failure == null ? "ok" : failure) + " crystal_alive="
				+ (crystal != null && crystal.isAlive()));
		bedStage = 0;
		bedMode = false;
		if (failure != null && crystal != null && crystal.isAlive()) {
			attempts.put(crystal.getId(), MAX_ATTEMPTS);
			JsonObject entry = new JsonObject();
			entry.add("pos", GameJson.pos(crystal.blockPosition()));
			entry.addProperty("reason", failure);
			skipped.add(entry);
		}
		phase = Phase.NEXT;
		return null;
	}

	/** Drache fliegt bis 30 Blöcke heran (hockend zählt nicht). */
	private static boolean dragonNear(ServerPlayer bot) {
		for (net.minecraft.world.entity.boss.enderdragon.EnderDragon dragon : bot.level().getEntitiesOfClass(
				net.minecraft.world.entity.boss.enderdragon.EnderDragon.class, bot.getBoundingBox().inflate(30),
				net.minecraft.world.entity.LivingEntity::isAlive)) {
			if (!dragon.getPhaseManager().getCurrentPhase().isSitting()) {
				return true;
			}
		}
		return false;
	}

	private Outcome next(ServerPlayer bot) {
		if (crystal != null && !crystal.isAlive()) {
			destroyed++;
			crystal = null;
		}
		if (onTower) {
			return down(bot);
		}
		crystal = pick(bot);
		if (crystal == null) {
			JsonObject data = progress(bot);
			data.addProperty("outcome", skipped.isEmpty() ? "all_destroyed" : "some_left");
			// Hannes' Lauf (xyz, 03.11.): 0 zerstört, 7 übrig, Status ok, das Modell sagte „sieben Kristalle sind hinüber“.
			if (destroyed == 0 && !skipped.isEmpty()) {
				data.addProperty("hint", "NO crystal destroyed, all still heal the dragon; walk onto the main island near the "
						+ "towers (goto with allow_place from the spawn platform) and run destroy_crystals again");
				return Outcome.failed("none_destroyed", data);
			}
			data.addProperty("hint", "crystals done; the rest (skipped) still heal the dragon between perches. Now bed_cycle");
			return Outcome.ok(data);
		}
		attempts.merge(crystal.getId(), 1, Integer::sum);
		ticksOnCrystal = 0;
		BlockPos at = crystal.blockPosition();
		boolean caged = ShootAction.caged(bot, at);
		if (!caged && (ShootAction.seesPoint(bot, bot.getEyePosition(), ShootAction.crystalAware(crystal))
						|| ShootAction.lobClear(bot, bot.getEyePosition(), ShootAction.crystalAware(crystal)))
				&& bot.distanceTo(crystal) >= 12 && bot.distanceTo(crystal) <= 48) {
			return shoot();
		}
		BlockPos spot = caged ? null : ShootAction.shootFrom(bot, crystal);
		if (spot != null && spot.distSqr(at) <= 48 * 48) {
			step = new GotoAction(bot, new Pos(spot.getX(), spot.getY(), spot.getZ()), 1, new Moves.Options(true, true));
			phase = Phase.TO_SPOT;
			return null;
		}
		if (groundOnly) {
			attempts.put(crystal.getId(), MAX_ATTEMPTS);
			return skip(bot, caged ? "caged" : "no_ground_spot");
		}
		// Keine eigenen Türme mehr (Weg C, 29.10.2026): End-Läufe 75–82 starben alle an oder auf den Türmen. Kristalle ohne
		// Platz am Boden (auch auf Hügeln bis 10 Blöcke höher, siehe shoot_from) bleiben stehen; bed_bomb gleicht das aus.
		// Röhre: Säule plus Geländer auf Kopfhöhe, etwa 5 Blöcke je Stufe (End-Lauf 89: Klettern brach bei Höhe 68 bis 100
		// ohne Bausteine ab).
		int need = Math.max(64, (at.getY() - bot.getBlockY()) * 5 + 16);
		if (!TOWERS && de.hannes.aiplayer.skill.Inv.scaffoldCount(bot) < need && gathered < 6) {
			// Zu wenig für die Röhre: erst Endstein abbauen (End-Lauf 85: fünf Kristalle mit few_blocks übersprungen).
			gathered++;
			attempts.merge(crystal.getId(), -1, Integer::sum);
			Vec3 out = new Vec3(bot.getX(), 0, bot.getZ());
			out = out.lengthSqr() < 1 ? new Vec3(40, 0, 0) : out.normalize().scale(40);
			BlockPos ring = bot.level().getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
					BlockPos.containing(out.x, 0, out.z));
			step = new GotoAction(bot, new Pos(ring.getX(), ring.getY(), ring.getZ()), 4, Moves.Options.NONE);
			phase = Phase.TO_GATHER;
			return null;
		}
		if (!TOWERS) {
			// Weg D: Röhre direkt an den Obsidianturm bis knapp unter die Spitze, Dach aus 2 Blöcken, Bett darauf, aus der
			// Deckung zünden: Die Explosion sprengt den Kristall auch im Käfig (End-Läufe 72–83: stehende Kristalle heilten
			// den Drachen zwischen zwei Landungen voll, eigene Türme mit Abstand waren tödlich).
			BlockPos column = besideTower(bot, at);
			if (column == null || de.hannes.aiplayer.skill.Inv.scaffoldCount(bot) < need) {
				attempts.put(crystal.getId(), MAX_ATTEMPTS);
				return skip(bot, column == null ? "no_spot_beside_tower" : "few_blocks");
			}
			tower = column;
			towerBase = bot.level().getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column);
			step = new GotoAction(bot, new Pos(towerBase.getX(), towerBase.getY(), towerBase.getZ()), 0.5, new Moves.Options(true, true));
			phase = Phase.TO_TOWER_BASE;
			onTower = true;
			bedMode = true;
			bars.clear();
			return null;
		}		// Für einen Turm reichen die Bausteine nicht: erst Endstein abbauen, am Ring 40 Blöcke von der Mitte (End-Läufe 15, 21).
		if (de.hannes.aiplayer.skill.Inv.scaffoldCount(bot) < MIN_BLOCKS && gathered < 2) {
			gathered++;
			attempts.merge(crystal.getId(), -1, Integer::sum);
			Vec3 out = new Vec3(bot.getX(), 0, bot.getZ());
			out = out.lengthSqr() < 1 ? new Vec3(40, 0, 0) : out.normalize().scale(40);
			BlockPos ring = bot.level().getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
					BlockPos.containing(out.x, 0, out.z));
			step = new GotoAction(bot, new Pos(ring.getX(), ring.getY(), ring.getZ()), 4, Moves.Options.NONE);
			phase = Phase.TO_GATHER;
			return null;
		}
		// Eigener Turm auf Kristallhöhe zur Inselmitte hin (End-Lauf 18: Turm außen, über den Rand gestoßen).
		Vec3 away = new Vec3(-crystal.getX(), 0, -crystal.getZ());
		away = away.lengthSqr() < 0.01 ? new Vec3(1, 0, 0) : away.normalize();
		tower = BlockPos.containing(crystal.getX() + away.x * TOWER_DISTANCE, at.getY(), crystal.getZ() + away.z * TOWER_DISTANCE);
		near = BlockPos.containing(crystal.getX() + away.x * BAR_DISTANCE, at.getY(), crystal.getZ() + away.z * BAR_DISTANCE);
		towerBase = bot.level().getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, tower);
		step = new GotoAction(bot, new Pos(towerBase.getX(), towerBase.getY(), towerBase.getZ()), 1.5, new Moves.Options(true, true));
		phase = Phase.TO_TOWER_BASE;
		onTower = true;
		bars.clear();
		if (caged) {
			bars.add(BlockPos.ZERO); // Merker: nach dem Turm zum Käfig
		}
		return null;
	}

	private Outcome after(ServerPlayer bot, Outcome done) {
		boolean ok = done.status() == Status.OK;
		switch (phase) {
			case FIGHT -> {
				phase = Phase.NEXT;
				if (crystal != null) {
					attempts.merge(crystal.getId(), -1, Integer::sum);
				}
				return null;
			}
			case EXIT -> {
				return nextExit();
			}
			case TO_GATHER -> {
				step = new CollectAction(state -> state.is(Blocks.END_STONE), "minecraft:end_stone", GATHER_COUNT, 10, Moves.Options.NONE);
				phase = Phase.GATHER;
				return null;
			}
			case TO_TOWER_BASE -> {
				if (!ok) {
					return skip(bot, "tower_base_" + done.reason());
				}
				climbing = true;
				phase = Phase.TO_TOWER;
				return null;
			}
			case TO_SPOT -> {
				return ok ? shoot() : skip(bot, "spot_" + done.reason());
			}
			case TO_TOWER -> {
				if (!ok) {
					return skip(bot, "tower_" + done.reason());
				}
				if (bedMode) {
					bedStage = 1;
					return null;
				}
				if (!bars.isEmpty()) {
					bars.clear();
					step = new GotoAction(bot, new Pos(near.getX(), near.getY(), near.getZ()), 0.5, new Moves.Options(false, true));
					phase = Phase.TO_CAGE;
					return null;
				}
				return fortify(bot);
			}
			case FORTIFY -> {
				return nextWall(bot);
			}
			case TO_CAGE -> {
				if (!ok) {
					return skip(bot, "cage_" + done.reason());
				}
				collectBars(bot);
				return breakNextBar(bot);
			}
			case BREAK_BARS -> {
				return breakNextBar(bot);
			}
			case BACK_TO_TOWER -> {
				return ok ? fortify(bot) : skip(bot, "back_" + done.reason());
			}
			case SHOOT -> {
				arrows += done.data() != null && done.data().has("arrows_shot") ? done.data().get("arrows_shot").getAsInt() : 0;
				if (crystal != null && crystal.isAlive() && attempts.getOrDefault(crystal.getId(), 0) >= MAX_ATTEMPTS) {
					return skip(bot, "missed");
				}
				phase = Phase.NEXT;
				return null;
			}
			case DOWN -> {
				onTower = false;
				phase = Phase.NEXT;
				// descend scheitert manchmal auf dem eigenen Turm (End-Lauf 43): dann Block für Block abbauen.
				if (towerBase != null && bot.getY() > towerBase.getY() + 1 && digDown++ < 60) {
					step = new MineAction(bot.blockPosition().below());
					phase = Phase.DOWN;
					onTower = true;
					return null;
				}
				if (!ok && towerBase != null && bot.getY() > towerBase.getY() + 3) {
					JsonObject data = progress(bot);
					data.addProperty("hint", "could not climb down from the own tower; mine the block below you step by step");
					return Outcome.failed("stuck_on_tower", data);
				}
				// Unten im Schacht aus Geländer: zur Inselmitte hin Fuß- und Kopfblock weg (End-Lauf 26).
				net.minecraft.core.Direction in = net.minecraft.core.Direction.getApproximateNearest(-bot.getX(), 0, -bot.getZ());
				BlockPos side = bot.blockPosition().relative(in);
				exits.clear();
				for (BlockPos cell : new BlockPos[]{side, side.above()}) {
					if (!bot.level().getBlockState(cell).getCollisionShape(bot.level(), cell).isEmpty()) {
						exits.add(cell);
					}
				}
				return nextExit();
			}
			default -> {
				phase = Phase.NEXT;
				return null;
			}
		}
	}

	private Outcome nextExit() {
		if (exits.isEmpty()) {
			phase = Phase.NEXT;
			return null;
		}
		step = new MineAction(exits.remove(0));
		phase = Phase.EXIT;
		return null;
	}

	/**
	 * Oben auf dem Turm: Wand auf Fuß- und Kopfhöhe rundum, zum Kristall hin auf Kopfhöhe ein Block offen (Schießscharte),
	 * dazu ein Dach (End-Läufe 14, 59: über Geländer und Wände geschleudert).
	 */
	private Outcome fortify(ServerPlayer bot) {
		walls.clear();
		BlockPos feet = bot.blockPosition();
		net.minecraft.core.Direction toward = net.minecraft.core.Direction.getApproximateNearest(
				crystal.getX() - bot.getX(), 0, crystal.getZ() - bot.getZ());
		for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
			BlockPos side = feet.relative(dir);
			if (bot.level().getBlockState(side).getCollisionShape(bot.level(), side).isEmpty()) {
				walls.add(side);
			}
			BlockPos head = side.above();
			if (dir != toward && bot.level().getBlockState(head).getCollisionShape(bot.level(), head).isEmpty()) {
				walls.add(head);
			}
		}
		BlockPos roof = feet.above(2);
		if (bot.level().getBlockState(roof).getCollisionShape(bot.level(), roof).isEmpty()) {
			walls.add(roof);
		}
		return nextWall(bot);
	}

	private Outcome nextWall(ServerPlayer bot) {
		int slot = de.hannes.aiplayer.skill.Inv.findScaffold(bot);
		if (walls.isEmpty() || slot < 0) {
			return shoot();
		}
		step = new PlaceAction(de.hannes.aiplayer.skill.Inv.itemId(bot.getInventory().getItem(slot)), walls.remove(0));
		phase = Phase.FORTIFY;
		return null;
	}

	private Outcome shoot() {
		step = new ShootAction(Integer.toString(crystal.getId()), SHOTS);
		phase = Phase.SHOOT;
		return null;
	}

	/** Gitter in der Linie vom Standplatz am Käfig zum Kristall. */
	private void collectBars(ServerPlayer bot) {
		bars.clear();
		Vec3 eye = bot.getEyePosition();
		Vec3 aim = ShootAction.crystalAware(crystal);
		for (int i = 0; i <= 40; i++) {
			Vec3 point = eye.lerp(aim, i / 40.0);
			for (int dy = -1; dy <= 1; dy++) {
				BlockPos cell = BlockPos.containing(point).above(dy);
				if (bot.level().getBlockState(cell).is(Blocks.IRON_BARS) && !bars.contains(cell)) {
					bars.add(cell);
				}
			}
		}
	}

	private Outcome breakNextBar(ServerPlayer bot) {
		if (bars.isEmpty()) {
			step = new GotoAction(bot, new Pos(tower.getX(), tower.getY(), tower.getZ()), 0.5, new Moves.Options(false, true));
			phase = Phase.BACK_TO_TOWER;
			return null;
		}
		step = new MineAction(bars.remove(0));
		phase = Phase.BREAK_BARS;
		return null;
	}

	/** Die eigene Säule hinab (descend; End-Lauf 15: goto fand vom Turm oft keinen Weg). */
	private Outcome down(ServerPlayer bot) {
		BlockPos ground = bot.level().getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
				bot.blockPosition().offset(3, 0, 3));
		step = new DescendAction(towerBase != null ? towerBase.getY() : ground.getY());
		phase = Phase.DOWN;
		return null;
	}

	private Outcome skip(ServerPlayer bot, String why) {
		de.hannes.aiplayer.AiPlayerMod.LOGGER.info("destroy_crystals skip " + why + " pos=" + bot.blockPosition());
		if (crystal != null && attempts.getOrDefault(crystal.getId(), 0) >= MAX_ATTEMPTS) {
			JsonObject entry = new JsonObject();
			entry.add("pos", GameJson.pos(crystal.blockPosition()));
			entry.addProperty("reason", why);
			skipped.add(entry);
		}
		phase = Phase.NEXT;
		return null;
	}

	/** Nächster lebender Kristall, freie vor Käfigen, ohne die übersprungenen. */
	private EndCrystal pick(ServerPlayer bot) {
		return bot.level().getEntitiesOfClass(EndCrystal.class, bot.getBoundingBox().inflate(SEARCH_RADIUS), EndCrystal::isAlive)
				.stream()
				.filter(c -> attempts.getOrDefault(c.getId(), 0) < MAX_ATTEMPTS)
				.min(Comparator.comparingDouble((EndCrystal c) -> ShootAction.caged(bot, c.blockPosition()) ? 1 : 0)
						.thenComparingDouble(bot::distanceToSqr))
				.orElse(null);
	}

	@Override
	public void release(ServerPlayer bot) {
		if (step != null) {
			step.release(bot);
			step = null;
		}
	}

	@Override
	public JsonObject progress(ServerPlayer bot) {
		JsonObject data = new JsonObject();
		data.addProperty("destroyed", destroyed);
		data.addProperty("arrows_shot", arrows);
		data.addProperty("left", bot.level().getEntitiesOfClass(EndCrystal.class, bot.getBoundingBox().inflate(SEARCH_RADIUS),
				EndCrystal::isAlive).size());
		if (!skipped.isEmpty()) {
			data.add("skipped", skipped);
		}
		data.addProperty("health", GameJson.round1(bot.getHealth()));
		data.add("pos", GameJson.pos(bot.blockPosition()));
		return data;
	}
}
