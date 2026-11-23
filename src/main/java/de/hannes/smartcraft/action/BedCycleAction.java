package de.hannes.smartcraft.action;

import com.google.gson.JsonObject;
import de.hannes.smartcraft.AiPlayerMod;
import de.hannes.smartcraft.nav.Moves;
import de.hannes.smartcraft.nav.Pos;
import de.hannes.smartcraft.perception.GameJson;
import de.hannes.smartcraft.skill.BlockPlacer;
import de.hannes.smartcraft.skill.Inv;
import de.hannes.smartcraft.skill.PathFollower;
import de.hannes.smartcraft.skill.PillarUp;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
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
 * Drache in einem Durchgang mit Betten besiegen („One-Cycle“, Idee von Hannes nach einem Speedrun-Video, 30.10.2026).
 * Messung ({@code test_bed_cycle.py}): Ein Bett 0 bis 2 Blöcke unter dem Kopf macht etwa 50 Schaden, auch beim Abheben und im
 * Flug; der Drache ist danach 10 Ticks unverwundbar; die Kristalle heilen nur etwa 2 Leben pro Sekunde. Vier Betten im Takt
 * von 10 Ticks töten ihn also, ohne einen Kristall zu zerstören.
 * <p>
 * Ablauf: Lauerposten {@link #POST_R} Blöcke vom Brunnen (Richtung, aus der der Bot kommt), Säule bis Fußhöhe
 * {@link #POST_FEET_Y}, Geländer auf drei Seiten. Hockend dreht der Drache den Kopf zum nächsten Spieler, also zum Posten.
 * Jeden Tick: Ist der Kopf so nah, dass ein Bett 0 bis 3 Blöcke darunter in Reichweite liegt, setzt der Bot (wenn nötig erst
 * einen Stützblock, dann) das Bett und zündet es sofort. Danach {@link #COOLDOWN} Ticks Pause.
 */
public final class BedCycleAction extends Action {
	private static final double POST_R = 8;
	private static final int POST_FEET_Y = 67;
	private static final int COOLDOWN = 10;
	private static final float MIN_HEALTH = 6;
	private static final double REACH = 4.4;

	/** {@code cage}: Käfig, zündet nur bei geschätzt bis 4 Schaden; {@code combo}: Käfig, bis 10; {@code climb}: offene Säule. */
	private final String mode;
	/** Fußhöhe im Käfig (beim Hocken hängt der Kopf auf 67, das Bett auf dem Dach 2 darunter). */
	private static final int CAGE_FEET_Y = 62;
	/** Abstand Käfig–Brunnen: Dort hängt der Kopf, wenn der Drache hockt und zum Bot schaut (Messung: 8,2 bis 8,4). */
	private static final double CAGE_R = 10.3; // 8.3 bis Lauf 105: Käfig lag im Atem des hockenden Drachen; zum Kopf führt approach()
	private static final int CAGE_LIFT = 0;
	private int cageFeet = Integer.MIN_VALUE;
	private int climbed;

	public BedCycleAction(String mode) {
		this.mode = mode == null ? "combo" : mode;
	}

	private boolean caged() {
		return !"climb".equals(mode);
	}

	private Vec3 postDir;
	private int shift;
	private GotoAction walk;
	/** Weg unter den Kopf des hockenden Drachen (nur Käfig-Modi), danach zurück in den Käfig. */
	private GotoAction approach;
	private PillarUp pillar;
	private int pillarFailures;
	private int cooldown;
	private int beds;
	private int hits;
	private float dragonStart = -1;
	private int rest;
	private int lastBoomTick = -10000;

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
			data.addProperty("hint", "health low: eat (golden_apple), then bed_cycle again");
			return Outcome.cancelled("health_low", data);
		}
		if (postDir == null) {
			postDir = choosePostDir(bot);
		}
		if (cooldown > 0) {
			cooldown--;
		}
		// Bomben geht vor allem anderen, auch unterwegs zum Posten: jede Gelegenheit zählt.
		if (cooldown == 0 && tryBomb(bot, dragon)) {
			return null;
		}
		// Angreifer in Schlagweite (Endermen) vom Posten aus schlagen, ohne ihn zu verlassen.
		LivingEntity attacker = bot.getLastHurtByMob();
		if (attacker != null && attacker.isAlive() && !(attacker instanceof EnderDragon)
				&& bot.tickCount - bot.getLastHurtByMobTimestamp() < 60 && attacker.distanceTo(bot) < 3.5
				&& bot.getAttackStrengthScale(0.5f) > 0.9f) {
			de.hannes.smartcraft.skill.Combat.holdBestWeapon(bot);
			bot.attack(attacker);
			bot.swing(InteractionHand.MAIN_HAND, true);
		}
		if (approach(bot, dragon)) {
			return null;
		}
		if ("climb".equals(mode) && climbFollow(bot, dragon)) {
			return null;
		}
		return tickPost(bot);
	}

	/**
	 * Hockt der Drache und hängt der Kopf vom Käfig aus außer Reichweite (Läufe 16–22: das erste Bett ging erst beim Abheben
	 * hoch, danach war der Kopf weg), geht der Bot bis etwa 2,5 Blöcke vor den Kopf; {@link #tryBomb} zündet unterwegs, sobald
	 * ein Feld erreichbar ist. Hebt der Drache ab, kehrt er zum Käfig zurück.
	 */
	private boolean approach(ServerPlayer bot, EnderDragon dragon) {
		if (!caged()) {
			return false;
		}
		boolean sitting = dragon.getPhaseManager().getCurrentPhase().isSitting();
		if (approach != null) {
			if (!sitting) {
				approach.release(bot);
				approach = null;
				return false;
			}
			if (breathNear(bot, bot.getX(), bot.getZ()) || breathNear(bot, approachX, approachZ)) {
				AiPlayerMod.LOGGER.info("bed_cycle approach_aborted breath");
				approach.release(bot);
				approach = null;
				approachBlockedUntil = bot.tickCount + 100;
				return false;
			}
			if (approach.tick(bot) != null) {
				approach.release(bot);
				approach = null;
			}
			return true;
		}
		if (!sitting || bot.tickCount < approachBlockedUntil) {
			return false;
		}
		Entity head = dragon.getSubEntities()[0];
		double hx = bot.getX() - head.getX();
		double hz = bot.getZ() - head.getZ();
		double horiz = Math.sqrt(hx * hx + hz * hz);
		if (horiz < 3.5 || horiz > 14 || !bot.onGround()) {
			return false;
		}
		double tx = head.getX() + hx / horiz * 2.5;
		double tz = head.getZ() + hz / horiz * 2.5;
		// Nie in den Atem: Lauf 108 lief beim hockenden Drachen immer wieder vor den Kopf in die Wolke, der Reflex zog ihn
		// 30-mal heraus, bis drei Treffer zu 6 Schaden ihn töteten.
		if (breathNear(bot, tx, tz) || breathNear(bot, bot.getX(), bot.getZ())) {
			return false;
		}
		BlockPos ground = bot.level().getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, BlockPos.containing(tx, 0, tz));
		stopWalk(bot);
		approachX = tx;
		approachZ = tz;
		AiPlayerMod.LOGGER.info("bed_cycle approach to " + ground.toShortString() + " from " + bot.blockPosition().toShortString());
		approach = new GotoAction(bot, new Pos(ground.getX(), ground.getY(), ground.getZ()), 0.8, new Moves.Options(true, true));
		return true;
	}

	/**
	 * Richtung des Postens: dort, wo am wenigsten stehende Kristalle den Drachen heilen können. Das Spiel heilt ihn nur über
	 * einen Kristall in einem Würfel von 32 Blöcken um den Drachen; in Lauf 107 heilten 6 Kristalle zwischen den Vorbeiflügen
	 * je etwa 40 Leben zurück, 12 Betten reichten nicht. Bei Gleichstand die Richtung, aus der der Bot kommt.
	 */
	private static Vec3 choosePostDir(ServerPlayer bot) {
		Vec3 own = new Vec3(bot.getX(), 0, bot.getZ());
		own = own.lengthSqr() < 1 ? new Vec3(1, 0, 0) : own.normalize();
		java.util.List<net.minecraft.world.entity.boss.enderdragon.EndCrystal> crystals = bot.level().getEntitiesOfClass(
				net.minecraft.world.entity.boss.enderdragon.EndCrystal.class, new net.minecraft.world.phys.AABB(-120, 0, -120, 120, 160, 120),
				Entity::isAlive);
		Vec3 best = own;
		double bestScore = Double.MAX_VALUE;
		for (int i = 0; i < 32; i++) {
			double a = Math.PI * 2 * i / 32;
			Vec3 dir = new Vec3(Math.cos(a), 0, Math.sin(a));
			int count = 0;
			// Wo sich der Drache beim Bomben aufhält: über dem Käfig und auf dem Weg dorthin.
			for (double r : new double[] {CAGE_R, CAGE_R + 6}) {
				Vec3 at = dir.scale(r);
				for (var c : crystals) {
					if (Math.abs(c.getX() - at.x) < 33 && Math.abs(c.getZ() - at.z) < 33 && Math.abs(c.getY() - 70) < 33) {
						count++;
					}
				}
			}
			double score = count * 10 - dir.dot(own);
			if (score < bestScore) {
				bestScore = score;
				best = dir;
			}
		}
		AiPlayerMod.LOGGER.info("bed_cycle post_dir " + String.format("%.2f/%.2f", best.x, best.z) + " crystals=" + crystals.size()
				+ " score=" + String.format("%.1f", bestScore));
		return best;
	}

	private double approachX;
	private double approachZ;
	private int approachBlockedUntil;

	/** Atemwolke des Drachen (Radius mindestens 7, plus 2 Rand) über dieser Stelle; Atem des hockenden Drachen zählt auch. */
	private static boolean breathNear(ServerPlayer bot, double x, double z) {
		for (var cloud : bot.level().getEntitiesOfClass(net.minecraft.world.entity.AreaEffectCloud.class,
				bot.getBoundingBox().inflate(32), Entity::isAlive)) {
			if (!(cloud.getOwner() instanceof EnderDragon)) {
				continue;
			}
			double r = Math.max(cloud.getRadius(), 7) + 2;
			if ((x - cloud.getX()) * (x - cloud.getX()) + (z - cloud.getZ()) * (z - cloud.getZ()) < r * r) {
				return true;
			}
		}
		for (EnderDragon dragon : bot.level().getEntitiesOfClass(EnderDragon.class, bot.getBoundingBox().inflate(64), Entity::isAlive)) {
			var phase = dragon.getPhaseManager().getCurrentPhase().getPhase();
			if (phase == net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase.SITTING_FLAMING) {
				Entity head = dragon.getSubEntities()[0];
				if ((x - head.getX()) * (x - head.getX()) + (z - head.getZ()) * (z - head.getZ()) < 81) {
					return true;
				}
			}
		}
		return false;
	}

	/** Fußhöhe auf dem Posten: Käfig {@link #CAGE_FEET_Y} (nie unter dem Boden), offene Säule {@link #POST_FEET_Y} plus Mitgeklettertes. */
	private int feetY(ServerPlayer bot, BlockPos column) {
		if (!caged()) {
			return POST_FEET_Y + climbed;
		}
		if (cageFeet == Integer.MIN_VALUE) {
			int ground = bot.level().getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column).getY();
			// Direkt auf dem Endstein: eine Säule aus Bruchstein frisst der Drache weg (Käfig-Läufe, Bot fiel im Käfig einen Block).
			// CAGE_LIFT 2 (Läufe 23–26) war schlechter: Reichweite passte, aber Bettfelder ohne Halt, Bot sackte ab, 0 Betten.
			cageFeet = ground + CAGE_LIFT;
		}
		return cageFeet;
	}

	/**
	 * Mitklettern (Modus climb): Hebt der Drache ab und steigt der Kopf über die Augen, eine Stufe höher (bis 8), damit das
	 * nächste Bett in Reichweite bleibt. Hockt er wieder oder ist er weit weg, bleibt die Säule, wie sie ist.
	 */
	private boolean climbFollow(ServerPlayer bot, EnderDragon dragon) {
		if (pillar != null || climbed >= 8 || walk != null) {
			return false;
		}
		Entity head = dragon.getSubEntities()[0];
		var phase = dragon.getPhaseManager().getCurrentPhase().getPhase();
		boolean rising = phase == net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase.TAKEOFF
				|| phase == net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase.HOLDING_PATTERN;
		if (!rising || head.getY() - bot.getEyeY() < 1.5 || head.distanceTo(bot) > 9 || !bot.onGround()) {
			return false;
		}
		climbed++;
		pillar = new PillarUp();
		return true;
	}

	/**
	 * Käfig um den Posten: Ring auf Fuß- und Kopfhöhe (darunter eine Stütze, falls die Säule frei steht), außen ein Block auf
	 * Dachhöhe als Halt, dann das Dach. Die Explosion eines Betts auf dem Dach trifft den Bot darunter kaum.
	 */
	private boolean cage(ServerPlayer bot, BlockPos column, int feetY) {
		Direction out = Direction.getApproximateNearest(postDir.x, 0, postDir.z);
		BlockPos feet = new BlockPos(column.getX(), feetY, column.getZ());
		java.util.List<BlockPos> cells = new java.util.ArrayList<>();
		for (Direction d : Direction.Plane.HORIZONTAL) {
			BlockPos side = feet.relative(d);
			if (bot.level().getBlockState(side.below()).canBeReplaced()) {
				cells.add(side.below());
			}
			cells.add(side);
			cells.add(side.above());
		}
		cells.add(feet.relative(out).above(2));
		cells.add(feet.above(2));
		for (BlockPos cell : cells) {
			if (!bot.level().getBlockState(cell).canBeReplaced()) {
				continue;
			}
			int slot = sturdySlot(bot);
			if (slot < 0) {
				return false;
			}
			String failure = BlockPlacer.place(bot, slot, cell);
			if (failure == null) {
				return true;
			}
			AiPlayerMod.LOGGER.info("bed_cycle cage_failed " + failure + " at " + cell.toShortString());
		}
		return false;
	}

	/** Drachenfeste Blöcke zuerst (Obsidian, Endstein): Bruchstein räumt der Drache beim Durchfliegen weg (Käfig-Lauf 0). */
	private static int sturdySlot(ServerPlayer bot) {
		for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
			var stack = bot.getInventory().getItem(i);
			if (stack.getItem() instanceof net.minecraft.world.item.BlockItem block
					&& block.getBlock().defaultBlockState().is(net.minecraft.tags.BlockTags.DRAGON_IMMUNE)
					&& block.getBlock().defaultBlockState().isCollisionShapeFullBlock(bot.level(), BlockPos.ZERO)) {
				return i;
			}
		}
		return Inv.findScaffold(bot);
	}

	private int debugTicks;

	/** Geschätzter Schaden für den Bot durch ein Bett an dieser Stelle (Spielformel, Stärke 5, Diamantrüstung etwa halbiert). */
	private static double selfDamage(ServerPlayer bot, BlockPos cell) {
		Vec3 center = Vec3.atCenterOf(cell);
		double dist = Math.sqrt(bot.distanceToSqr(center)) / 10.0;
		if (dist > 1) {
			return 0;
		}
		double impact = (1 - dist) * net.minecraft.world.level.ServerExplosion.getSeenPercent(center, bot);
		return ((impact * impact + impact) / 2 * 7 * 10 + 1) * 0.5;
	}

	private double maxSelfDamage(ServerPlayer bot) {
		double allowed = switch (mode) {
			case "cage" -> 4;
			case "combo" -> 10;
			default -> 99;
		};
		return Math.min(allowed, bot.getHealth() - 7);
	}

	/** Zum Posten und hinauf; oben stehen bleiben und das Geländer setzen. */
	private Outcome tickPost(ServerPlayer bot) {
		BlockPos column = postColumn(bot);
		int feetY = feetY(bot, column);
		boolean onTop = Math.abs(bot.getX() - column.getX() - 0.5) < 0.8 && Math.abs(bot.getZ() - column.getZ() - 0.5) < 0.8
				&& bot.getY() >= feetY - 0.1;
		if (onTop) {
			stopWalk(bot);
			pillar = null;
			// Erst in die Feldmitte: Am Rand blockiert der eigene Körper den Ringblock daneben (Käfig-Läufe 5 und 6:
			// „obstructed“, darüber „no_support“, der Käfig wurde nie fertig).
			double cx = column.getX() + 0.5 - bot.getX();
			double cz = column.getZ() + 0.5 - bot.getZ();
			if (caged() && bot.onGround() && (Math.abs(cx) > 0.2 || Math.abs(cz) > 0.2)) {
				bot.setDeltaMovement(cx * 0.4, bot.getDeltaMovement().y, cz * 0.4);
				bot.hurtMarked = true;
				return null;
			}
			if (bot.onGround() && (caged() ? cage(bot, column, feetY) : rail(bot, column))) {
				return null;
			}
			if (rest++ % 100 == 0) {
				AiPlayerMod.LOGGER.info("bed_cycle on_post at " + bot.blockPosition());
			}
			return null;
		}
		if (pillar != null) {
			PillarUp.State state = pillar.tick(bot);
			if (state == PillarUp.State.RUNNING) {
				return null;
			}
			if (state == PillarUp.State.FAILED) {
				AiPlayerMod.LOGGER.info("bed_cycle pillar_failed " + pillar.failure());
				if ("no_blocks".equals(pillar.failure())) {
					JsonObject data = progress(bot);
					data.addProperty("hint", "need building blocks (cobblestone/end_stone) for the post");
					return Outcome.failed("no_blocks", data);
				}
				if (++pillarFailures % 3 == 0) {
					shift++;
				}
			}
			pillar = null;
			return null;
		}
		boolean atColumn = Math.abs(bot.getX() - column.getX() - 0.5) < 0.6 && Math.abs(bot.getZ() - column.getZ() - 0.5) < 0.6
				&& bot.onGround();
		if (atColumn && bot.getY() < feetY) {
			stopWalk(bot);
			pillar = new PillarUp();
			return null;
		}
		if (walk == null) {
			BlockPos ground = bot.level().getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column);
			walk = new GotoAction(bot, new Pos(ground.getX(), ground.getY(), ground.getZ()), 0.4, new Moves.Options(true, true));
		}
		Outcome done = walk.tick(bot);
		if (done != null) {
			walk.release(bot);
			walk = null;
			if (done.status() != de.hannes.smartcraft.protocol.Event.Status.OK) {
				AiPlayerMod.LOGGER.info("bed_cycle walk_failed " + done.reason());
				shift++;
			}
		}
		return null;
	}

	/**
	 * Säulenfeld: {@link #POST_R} Blöcke vom Brunnen. Steht dort schon eine fremde Säule (der Bot wurde heruntergestoßen),
	 * ein Feld zur Seite: die alte dient dann als Halt für Betten.
	 */
	private BlockPos postColumn(ServerPlayer bot) {
		Vec3 side = new Vec3(-postDir.z, 0, postDir.x);
		Vec3 at = postDir.scale(caged() ? CAGE_R : POST_R).add(side.scale(shift));
		return BlockPos.containing(at.x, 0, at.z);
	}

	/** Geländer auf Fußhöhe außen und seitlich (nicht zum Brunnen): fängt Stöße ab und dient Betten als Halt. */
	private boolean rail(ServerPlayer bot, BlockPos column) {
		Direction inward = Direction.getApproximateNearest(-postDir.x, 0, -postDir.z);
		BlockPos feet = BlockPos.containing(column.getX(), bot.getY() + 0.2, column.getZ());
		for (Direction d : Direction.Plane.HORIZONTAL) {
			if (d == inward) {
				continue;
			}
			BlockPos cell = feet.relative(d);
			if (bot.level().getBlockState(cell).canBeReplaced() && BlockPlacer.hasSupport(bot.level(), cell)) {
				int slot = Inv.findScaffold(bot);
				if (slot >= 0 && BlockPlacer.place(bot, slot, cell) == null) {
					return true;
				}
			}
		}
		return false;
	}

	/** Bett 0 bis 3 Blöcke unter dem Kopf in Reichweite setzen und zünden. @return ob gesprengt wurde. */
	private boolean tryBomb(ServerPlayer bot, EnderDragon dragon) {
		Entity head = dragon.getSubEntities()[0];
		Vec3 eye = bot.getEyePosition();
		if (eye.distanceTo(head.position()) > REACH + 4) {
			if (bot.tickCount - lastBoomTick <= 40) {
				AiPlayerMod.LOGGER.info(String.format(java.util.Locale.ROOT, "bed_cycle far +%d phase=%s head=%.1f/%.1f/%.1f eye=%.1f/%.1f/%.1f",
						bot.tickCount - lastBoomTick, dragon.getPhaseManager().getCurrentPhase().getPhase(), head.getX(), head.getY(), head.getZ(), eye.x, eye.y, eye.z));
			}
			return false;
		}
		ServerLevel level = bot.level();
		BlockPos feet = bot.blockPosition();
		boolean afterBoom = bot.tickCount - lastBoomTick <= 40;
		if (afterBoom || debugTicks++ % 10 == 0) {
			AiPlayerMod.LOGGER.info(String.format(java.util.Locale.ROOT, "bed_cycle near phase=%s head=%.1f/%.1f/%.1f eye=%.1f/%.1f/%.1f",
					dragon.getPhaseManager().getCurrentPhase().getPhase(), head.getX(), head.getY(), head.getZ(), eye.x, eye.y, eye.z));
		}
		java.util.Map<String, Integer> why = new java.util.TreeMap<>();
		for (int below : new int[] {2, 1, 3, 0}) {
			int y = (int) Math.floor(head.getY() - below);
			BlockPos best = null;
			double bestDist = Double.MAX_VALUE;
			for (int dx = -2; dx <= 2; dx++) {
				for (int dz = -2; dz <= 2; dz++) {
					BlockPos cell = new BlockPos((int) Math.floor(head.getX()) + dx, y, (int) Math.floor(head.getZ()) + dz);
					double hx = cell.getX() + 0.5 - head.getX();
					double hz = cell.getZ() + 0.5 - head.getZ();
					double horiz = hx * hx + hz * hz;
					if (horiz > 2.2 * 2.2 || horiz >= bestDist) {
						continue;
					}
					if (cell.equals(feet) || cell.equals(feet.above())) {
						why.merge("self", 1, Integer::sum);
						continue;
					}
					if (eye.distanceTo(Vec3.atCenterOf(cell)) > REACH) {
						why.merge("reach", 1, Integer::sum);
						continue;
					}
					if (!level.getBlockState(cell).canBeReplaced()) {
						why.merge("occupied", 1, Integer::sum);
						continue;
					}
					// Kopfteil des Betts zeigt in Blickrichtung des Bots: das Feld dahinter muss frei sein.
					Direction facing = Direction.getApproximateNearest(cell.getX() + 0.5 - bot.getX(), 0, cell.getZ() + 0.5 - bot.getZ());
					if (!level.getBlockState(cell.relative(facing)).canBeReplaced()) {
						why.merge("bed_head", 1, Integer::sum);
						continue;
					}
					boolean direct = BlockPlacer.hasSupport(level, cell);
					boolean viaSupport = !direct && supportChain(level, cell, feet, eye) != null;
					if (!direct && !viaSupport) {
						why.merge("support", 1, Integer::sum);
						continue;
					}
					if (selfDamage(bot, cell) > maxSelfDamage(bot)) {
						why.merge("unsafe", 1, Integer::sum);
						continue;
					}
					{
						best = cell;
						bestDist = horiz;
					}
				}
			}
			if (best != null && bomb(bot, dragon, best)) {
				return true;
			}
		}
		if (afterBoom || debugTicks % 10 == 1) {
			AiPlayerMod.LOGGER.info("bed_cycle why " + why);
		}
		return false;
	}

	/**
	 * Stütze unter dem Bettplatz: 1 oder 2 Blöcke senkrecht darunter, der unterste muss an etwas Festem hängen. Liefert die
	 * zu setzenden Felder von unten nach oben, oder {@code null}.
	 */
	private static java.util.List<BlockPos> supportChain(ServerLevel level, BlockPos cell, BlockPos feet, Vec3 eye) {
		// Breitensuche vom Feld unter dem Bett aus durch freie Felder in Reichweite, bis eines an etwas Festem hängt (höchstens
		// 3 Blöcke): Der Kopf hängt beim Hocken bis 5 Blöcke neben dem Käfig, dorthin führt eine kurze Brücke vom Dach.
		BlockPos start = cell.below();
		java.util.Map<BlockPos, BlockPos> parent = new java.util.HashMap<>();
		java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
		java.util.Map<BlockPos, Integer> depth = new java.util.HashMap<>();
		if (!placeable(level, start, cell, feet, eye)) {
			return null;
		}
		queue.add(start);
		depth.put(start, 1);
		while (!queue.isEmpty()) {
			BlockPos at = queue.poll();
			if (BlockPlacer.hasSupport(level, at)) {
				java.util.List<BlockPos> chain = new java.util.ArrayList<>();
				for (BlockPos p = at; p != null; p = parent.get(p)) {
					chain.add(p);
				}
				return chain;
			}
			if (depth.get(at) >= 3) {
				continue;
			}
			for (Direction d : Direction.values()) {
				BlockPos next = at.relative(d);
				if (d == Direction.UP || depth.containsKey(next) || !placeable(level, next, cell, feet, eye)) {
					continue;
				}
				parent.put(next, at);
				depth.put(next, depth.get(at) + 1);
				queue.add(next);
			}
		}
		return null;
	}

	private static boolean placeable(ServerLevel level, BlockPos pos, BlockPos bed, BlockPos feet, Vec3 eye) {
		return level.getBlockState(pos).canBeReplaced() && !pos.equals(feet) && !pos.equals(feet.above()) && !pos.equals(bed)
				&& eye.distanceTo(Vec3.atCenterOf(pos)) <= REACH;
	}

	private boolean bomb(ServerPlayer bot, EnderDragon dragon, BlockPos cell) {
		ServerLevel level = bot.level();
		if (!BlockPlacer.hasSupport(level, cell)) {
			java.util.List<BlockPos> chain = supportChain(level, cell, bot.blockPosition(), bot.getEyePosition());
			if (chain == null) {
				return false;
			}
			for (BlockPos support : chain) {
				int slot = Inv.findScaffold(bot);
				if (slot < 0 || BlockPlacer.place(bot, slot, support) != null) {
					return false;
				}
			}
		}
		String failure = BlockPlacer.place(bot, bedSlot(bot), cell);
		if (failure != null || !(level.getBlockState(cell).getBlock() instanceof BedBlock)) {
			AiPlayerMod.LOGGER.info("bed_cycle place_failed " + failure + " at " + cell);
			cooldown = 2;
			return false;
		}
		float before = dragon.getHealth();
		bot.gameMode.useItemOn(bot, level, bot.getMainHandItem(), InteractionHand.MAIN_HAND,
				new BlockHitResult(Vec3.atCenterOf(cell).add(0, 0.06, 0), Direction.UP, cell, false));
		bot.swing(InteractionHand.MAIN_HAND, true);
		beds++;
		float after = dragon.isAlive() ? dragon.getHealth() : 0;
		if (after < before) {
			hits++;
		}
		AiPlayerMod.LOGGER.info(String.format(java.util.Locale.ROOT, "bed_cycle boom mode=%s phase=%s headY=%.1f bed=%s self=%.1f dragon %.1f -> %.1f", mode,
				dragon.getPhaseManager().getCurrentPhase().getPhase(), dragon.getSubEntities()[0].getY(), cell.toShortString(), selfDamage(bot, cell), before, after));
		cooldown = COOLDOWN;
		lastBoomTick = bot.tickCount;
		return true;
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
		if (approach != null) {
			approach.release(bot);
			approach = null;
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
		data.addProperty("hits", hits);
		data.addProperty("health", GameJson.round1(bot.getHealth()));
		data.add("pos", GameJson.pos(bot.blockPosition()));
		return data;
	}
}
