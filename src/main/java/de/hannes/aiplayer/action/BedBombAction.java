package de.hannes.aiplayer.action;

import com.google.gson.JsonObject;
import de.hannes.aiplayer.nav.Moves;
import de.hannes.aiplayer.nav.Pos;
import de.hannes.aiplayer.perception.GameJson;
import de.hannes.aiplayer.skill.BlockPlacer;
import de.hannes.aiplayer.skill.Combat;
import de.hannes.aiplayer.skill.Inv;
import de.hannes.aiplayer.skill.PathFollower;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Drache mit Betten sprengen (Strategie wie im Speedrun, Wunsch von Hannes 29.10.2026): Im End explodiert ein Bett beim
 * Benutzen (Stärke 5). Der Bot wartet am Boden {@link #WAIT_RADIUS} Blöcke vom Brunnen; hockt der Drache, geht er bis
 * {@link #STAND_OFF} Blöcke an den Kopf, stellt eine Wand aus zwei Blöcken vor sich, setzt das Bett unter den Kopf und
 * klickt es an. Wiederholt das, solange der Drache hockt und Betten da sind. Endet mit dem Tod des Drachen, {@code no_beds}
 * oder {@code health_low}. End-Läufe 29–65: Mit Schwert und Bogen kam der Drache nie unter 172 Leben.
 */
public final class BedBombAction extends Action {
	private static final double WAIT_RADIUS = 18;
	private static final int STAND_OFF = 3;
	private static final float MIN_HEALTH = 6;

	private GotoAction walk;
	private boolean approaching;
	private int beds;
	private int cooldown;
	private float dragonStart = -1;

	@Override
	public int timeoutTicks() {
		return 20 * 60 * 30;
	}

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
		EnderDragon dragon = dragon(bot);
		if (dragon == null) {
			JsonObject data = progress(bot);
			data.addProperty("outcome", "dragon_dead");
			data.addProperty("hint", "the dragon is dead: the exit portal opened in the fountain");
			return Outcome.ok(data);
		}
		if (dragonStart < 0) {
			dragonStart = dragon.getHealth();
		}
		if (bedSlot(bot) < 0) {
			JsonObject data = progress(bot);
			data.addProperty("hint", "no beds left: craft beds (3 wool + 3 planks) or fight_dragon");
			return Outcome.failed("no_beds", data);
		}
		if (bot.getHealth() <= MIN_HEALTH) {
			JsonObject data = progress(bot);
			data.addProperty("hint", "health low: eat (golden_apple), then bed_bomb again");
			return Outcome.cancelled("health_low", data);
		}
		// Erst zurückschlagen, wer ihn trifft (End-Lauf 66: Endermen schlugen ihn von 20 auf 4 Leben, kein Gegenschlag).
		LivingEntity attacker = bot.getLastHurtByMob();
		if (attacker != null && attacker.isAlive() && !(attacker instanceof EnderDragon)
				&& bot.tickCount - bot.getLastHurtByMobTimestamp() < 60 && attacker.distanceTo(bot) < 8) {
			if (fight == null) {
				stopWalk(bot);
				fight = new AttackAction(attacker, 4);
			}
		}
		if (fight != null) {
			if (fight.tick(bot) == null) {
				return null;
			}
			fight.release(bot);
			fight = null;
		}
		Entity head = dragon.getSubEntities()[0];
		// Biss oder Hals berührt (End-Lauf 69: fünfmal 10 Schaden am Kopf, tot ohne ein Bett): 4 s zurück zum Warteplatz.
		boolean bitten = bot.getLastHurtByMob() == dragon && bot.tickCount - bot.getLastHurtByMobTimestamp() < 5;
		boolean touching = bot.getBoundingBox().intersects(head.getBoundingBox().inflate(1.5))
				|| bot.getBoundingBox().intersects(dragon.getSubEntities()[1].getBoundingBox().inflate(1.5));
		if (bitten || touching) {
			retreat = 80;
			lockBed = null;
		}
		if (retreat > 0) {
			retreat--;
			if (approaching || walk == null) {
				stopWalk(bot);
				walk = new GotoAction(bot, ground(bot, away(bot, head.position()).scale(12).add(head.position())), 2, new Moves.Options(true, true));
				approaching = false;
			}
			return tickWalk(bot);
		}
		if (cooldown > 0) {
			cooldown--;
			return null;
		}
		// Beim Abheben hängt der Kopf noch Sekunden über dem Brunnen: weiter sprengen (End-Lauf 72: je Landung nur ein
		// Bett, Phase beim Klick schon Takeoff, bis zur nächsten Landung heilten die Kristalle ihn voll).
		var phase = dragon.getPhaseManager().getCurrentPhase().getPhase();
		boolean sitting = dragon.getPhaseManager().getCurrentPhase().isSitting()
				|| phase == net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase.TAKEOFF
				&& head.getX() * head.getX() + head.getZ() * head.getZ() < 10 * 10 && head.getY() < 72;
		if (!sitting) {
			if (!armed.isEmpty()) {
				return detonate(bot, dragon);
			}
			lockBed = null;
			// Vorbauen, solange der Drache weit weg fliegt: Säule mit Bett dort, wo der Kopf beim Hocken hängt (etwa 6 Blöcke
			// vom Brunnen, Kopf auf Höhe 67, Bett 2 darunter). Landet er, zündet der Bot sofort statt erst zu bauen (Weg C;
			// End-Läufe 72–74: je Landung nur ein Bett, weil das Bauen die kurze Hockzeit fraß).
			if (dragon.distanceToSqr(0, dragon.getY(), 0) > 40 * 40 && prebuild(bot)) {
				return null;
			}
			if (approaching || walk == null) {
				stopWalk(bot);
				walk = new GotoAction(bot, ground(bot, away(bot, Vec3.ZERO).scale(WAIT_RADIUS)), 3, Moves.Options.NONE);
				approaching = false;
			}
			return tickWalk(bot);
		}
		// Kopf über dem vorgebauten Bett: dieses nehmen.
		if (preBed != null && bot.level().getBlockState(preBed).getBlock() instanceof BedBlock
				&& Math.pow(head.getX() - preBed.getX() - 0.5, 2) + Math.pow(head.getZ() - preBed.getZ() - 0.5, 2) < 3.5 * 3.5) {
			lockBed = preBed;
			lockFacing = preFacing;
		}
		// Hockt: Bettplatz unter dem Kopf, Standplatz STAND_OFF Blöcke davor auf der Seite des Bots.
		// Einmal je Landung festlegen: Der Kopf pendelt beim Hocken, mit jedem Tick neuem Ziel lief er nur hinterher
		// (End-Lauf 67: zweimal bis an den Kopf, kein Bett gesetzt, Biss mit 10 Schaden).
		if (lockBed == null || !bot.level().getBlockState(lockBed).canBeReplaced() && !(bot.level().getBlockState(lockBed).getBlock() instanceof BedBlock)) {
			Vec3 dir = away(bot, head.position());
			lockFacing = Direction.getApproximateNearest(-dir.x, 0, -dir.z);
			lockBed = bedSpot(bot.level(), head.position(), lockFacing);
		}
		Direction facing = lockFacing;
		BlockPos bed = lockBed;
		if (bed == null) {
			return tickWalk(bot);
		}
		BlockPos stand = bot.level().getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, BlockPos.containing(
				bed.getX() + 0.5 - facing.getStepX() * STAND_OFF, 0, bed.getZ() + 0.5 - facing.getStepZ() * STAND_OFF));
		if (bot.getEyePosition().distanceTo(Vec3.atBottomCenterOf(bed)) > 4.3 || bot.blockPosition().distManhattan(bed) < 2) {
			if (!approaching || walk == null) {
				stopWalk(bot);
				walk = new GotoAction(bot, new Pos(stand.getX(), stand.getY(), stand.getZ()), 0.5, Moves.Options.NONE);
				approaching = true;
			}
			Outcome done = walk.tick(bot);
			if (done != null) {
				walk.release(bot);
				walk = null;
			}
			return null;
		}
		stopWalk(bot);
		// Wand auf Fuß- und Kopfhöhe zwischen Bot und Bett: Sie fängt den Großteil der Explosion ab.
		BlockPos wall = bot.blockPosition().relative(facing);
		for (BlockPos cell : new BlockPos[]{wall, wall.above()}) {
			if (bot.level().getBlockState(cell).canBeReplaced() && !cell.equals(bed)) {
				int slot = Inv.findScaffold(bot);
				if (slot >= 0) {
					BlockPlacer.place(bot, slot, cell);
					return null;
				}
			}
		}
		// Ein Bett je Zündung: Mehrere nebeneinander im selben Tick machten nicht mehr Schaden (die erste Explosion zerstört die anderen). Ab etwa 50 Schaden in
		// einer Landung hebt der Drache ab (End-Läufe 72, 73: je Landung ein Bett, 50 Schaden, bis zur nächsten Landung
		// heilten ihn die Kristalle voll).
		double dx = head.getX() - (bed.getX() + 0.5);
		double dz = head.getZ() - (bed.getZ() + 0.5);
		if (dx * dx + dz * dz > 3.0 * 3.0 || head.getY() - bed.getY() > 5) {
			// Kopf weitergezogen (End-Lauf 70: Bett explodierte abseits, 2,7 Schaden): gestellte Betten sofort zünden.
			if (!armed.isEmpty()) {
				return detonate(bot, dragon);
			}
			lockBed = null;
			return null;
		}
		for (BlockPos target : new BlockPos[]{bed}) {
			if (armed.contains(target) || failed.contains(target)) {
				continue;
			}
			if (bot.level().getBlockState(target).getBlock() instanceof BedBlock) {
				armed.add(target);
				continue;
			}
			if (bedSlot(bot) < 0 || bot.getEyePosition().distanceTo(Vec3.atCenterOf(target)) > 4.4
					|| !bot.level().getBlockState(target).canBeReplaced()
					|| !bot.level().getBlockState(target.relative(facing)).canBeReplaced()) {
				failed.add(target);
				continue;
			}
			// Säule unter dem Bett von unten nach oben: Das Bett soll dicht unter dem Kopf explodieren.
			BlockPos ground = bot.level().getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, target);
			for (BlockPos cell = ground; cell.getY() < target.getY(); cell = cell.above()) {
				if (bot.level().getBlockState(cell).canBeReplaced()) {
					int slot = Inv.findScaffold(bot);
					if (slot < 0 || BlockPlacer.place(bot, slot, cell) != null) {
						failed.add(target);
					}
					return null;
				}
			}
			if (BlockPlacer.place(bot, bedSlot(bot), target) == null) {
				armed.add(target);
			} else {
				failed.add(target);
			}
			return null;
		}
		if (armed.isEmpty()) {
			lockBed = null;
			failed.clear();
			cooldown = 10;
			return null;
		}
		return detonate(bot, dragon);
	}

	/** Alle gestellten Betten im selben Tick anklicken (nicht geduckt): Im End explodieren sie. */
	private Outcome detonate(ServerPlayer bot, EnderDragon dragon) {
		Combat.holdBestWeapon(bot);
		float before = dragon.getHealth();
		for (BlockPos bed : armed) {
			if (bot.level().getBlockState(bed).getBlock() instanceof BedBlock) {
				bot.gameMode.useItemOn(bot, bot.level(), bot.getMainHandItem(), InteractionHand.MAIN_HAND,
						new BlockHitResult(Vec3.atCenterOf(bed).add(0, 0.06, 0), Direction.UP, bed, false));
				beds++;
			}
		}
		bot.swing(InteractionHand.MAIN_HAND, true);
		de.hannes.aiplayer.AiPlayerMod.LOGGER.info("bed_bomb boom beds=" + armed.size() + " dragon " + before + " -> " + dragon.getHealth());
		armed.clear();
		failed.clear();
		// Die Krater nehmen den Plätzen den Halt: neu suchen.
		lockBed = null;
		cooldown = 2;
		return null;
	}

	private final java.util.List<BlockPos> armed = new java.util.ArrayList<>();
	private final java.util.Set<BlockPos> failed = new java.util.HashSet<>();	private BlockPos placed;
	private BlockPos preBed;
	private Direction preFacing;
	private int preFailures;

	/**
	 * Ein Schritt Vorbauen. @return {@code true}, solange gebaut wird; {@code false}, wenn das Bett steht oder Vorbauen
	 * nicht geht (dann wartet der Bot normal).
	 */
	private boolean prebuild(ServerPlayer bot) {
		if (preFailures >= 3 || bedSlot(bot) < 0) {
			return false;
		}
		if (preBed != null && bot.level().getBlockState(preBed).getBlock() instanceof BedBlock) {
			return false;
		}
		if (preBed == null) {
			Vec3 dir = away(bot, Vec3.ZERO);
			preFacing = Direction.getApproximateNearest(-dir.x, 0, -dir.z);
			BlockPos ground = bot.level().getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
					BlockPos.containing(dir.x * 6, 0, dir.z * 6));
			preBed = ground.above(Math.max(0, Math.min(MAX_RAISE, 65 - ground.getY())));
		}
		if (bot.getEyePosition().distanceTo(Vec3.atCenterOf(preBed)) > 4.3 || bot.blockPosition().distManhattan(preBed) < 2) {
			BlockPos stand = bot.level().getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, BlockPos.containing(
					preBed.getX() + 0.5 - preFacing.getStepX() * STAND_OFF, 0, preBed.getZ() + 0.5 - preFacing.getStepZ() * STAND_OFF));
			if (!approaching || walk == null) {
				stopWalk(bot);
				walk = new GotoAction(bot, new Pos(stand.getX(), stand.getY(), stand.getZ()), 0.5, Moves.Options.NONE);
				approaching = true;
			}
			Outcome done = walk.tick(bot);
			if (done != null) {
				walk.release(bot);
				walk = null;
				if (done.status() != de.hannes.aiplayer.protocol.Event.Status.OK) {
					preFailures++;
					preBed = null;
				}
			}
			return true;
		}
		stopWalk(bot);
		BlockPos ground = bot.level().getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, preBed);
		for (BlockPos cell = ground; cell.getY() < preBed.getY(); cell = cell.above()) {
			if (bot.level().getBlockState(cell).canBeReplaced()) {
				int slot = Inv.findScaffold(bot);
				if (slot < 0 || BlockPlacer.place(bot, slot, cell) != null) {
					preFailures++;
					preBed = null;
				}
				return true;
			}
		}
		if (BlockPlacer.place(bot, bedSlot(bot), preBed) != null) {
			preFailures++;
			preBed = null;
		}
		return true;
	}
	private int retreat;
	private BlockPos lockBed;
	private Direction lockFacing;
	private Action fight;

	private Outcome tickWalk(ServerPlayer bot) {
		if (walk != null) {
			Outcome done = walk.tick(bot);
			if (done != null) {
				walk.release(bot);
				walk = null;
				cooldown = 40;
			}
		}
		return null;
	}

	/**
	 * Fußteil des Betts dicht unter dem Kopf: Die Säule darunter (bis {@link #MAX_RAISE} Blöcke) baut der Bot, das Bett
	 * explodiert dann etwa 2 Blöcke unter dem Kopf wie bei Speedrunnern. Am Boden, 6 Blöcke tiefer, machte ein Bett nur
	 * 6 Schaden (End-Lauf 68). Gesucht vom Kopf aus Richtung Bot.
	 */
	private static BlockPos bedSpot(ServerLevel level, Vec3 head, Direction facing) {
		for (int back = 0; back <= 4; back++) {
			BlockPos column = BlockPos.containing(head.x - facing.getStepX() * back, 0, head.z - facing.getStepZ() * back);
			BlockPos ground = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column);
			if (!level.getBlockState(ground.below()).isFaceSturdy(level, ground.below(), Direction.UP)) {
				continue;
			}
			int raise = Math.max(0, Math.min(MAX_RAISE, Mth.floor(head.y) - 2 - ground.getY()));
			BlockPos foot = ground.above(raise);
			boolean free = level.getBlockState(foot).canBeReplaced() && level.getBlockState(foot.relative(facing)).canBeReplaced()
					&& level.getBlockState(foot.above()).canBeReplaced();
			if (free && head.y - foot.getY() < 5) {
				return foot;
			}
		}
		return null;
	}

	private static final int MAX_RAISE = 4;

	/** Waagrechte Richtung vom Punkt {@code from} zum Bot. */
	private static Vec3 away(ServerPlayer bot, Vec3 from) {
		Vec3 dir = new Vec3(bot.getX() - from.x, 0, bot.getZ() - from.z);
		return dir.lengthSqr() < 0.01 ? new Vec3(1, 0, 0) : dir.normalize();
	}

	private static Pos ground(ServerPlayer bot, Vec3 at) {
		BlockPos pos = bot.level().getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, BlockPos.containing(at.x, 0, at.z));
		return new Pos(pos.getX(), pos.getY(), pos.getZ());
	}

	private static int bedSlot(ServerPlayer bot) {
		for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
			if (bot.getInventory().getItem(i).is(ItemTags.BEDS)) {
				return i;
			}
		}
		return -1;
	}

	private void stopWalk(ServerPlayer bot) {
		if (walk != null) {
			walk.release(bot);
			walk = null;
		}
		PathFollower.release(bot);
	}

	private static EnderDragon dragon(ServerPlayer bot) {
		return bot.level().getEntitiesOfClass(EnderDragon.class, bot.getBoundingBox().inflate(300), LivingEntity::isAlive)
				.stream().findFirst().orElse(null);
	}

	@Override
	public void release(ServerPlayer bot) {
		if (fight != null) {
			fight.release(bot);
			fight = null;
		}
		stopWalk(bot);
	}

	@Override
	public JsonObject progress(ServerPlayer bot) {
		JsonObject data = new JsonObject();
		EnderDragon dragon = dragon(bot);
		data.addProperty("dragon_health", dragon == null ? 0 : GameJson.round1(dragon.getHealth()));
		data.addProperty("dragon_start", GameJson.round1(dragonStart));
		data.addProperty("beds_used", beds);
		data.addProperty("health", GameJson.round1(bot.getHealth()));
		data.add("pos", GameJson.pos(bot.blockPosition()));
		return data;
	}
}
