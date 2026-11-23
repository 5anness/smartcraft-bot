package de.hannes.smartcraft.action;

import com.google.gson.JsonObject;
import de.hannes.smartcraft.perception.GameJson;
import de.hannes.smartcraft.skill.Combat;
import de.hannes.smartcraft.skill.Fighter;
import de.hannes.smartcraft.skill.Inv;
import de.hannes.smartcraft.skill.PathFollower;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;

/**
 * {@code shoot}: schießt mit dem Bogen auf ein Ziel, voll gespannt und mit Vorhalt für Bewegung und
 * Pfeilfall. Auch auf Ziele, die kein Lebewesen sind (Endkristall), und auf fliegende (Lohe, Ghast, Drache).
 * Schema: {@code schemas/commands/shoot.schema.json}.
 */
public final class ShootAction extends Action {
	private static final int SEARCH_RADIUS = 96; // Standplätze für Kristalle liegen bis 80 Blöcke weit
	/** Voll gespannt nach 20 Ticks; etwas Puffer für den ersten Tick. */
	private static final int CHARGE_TICKS = 22;
	private static final int PAUSE_TICKS = 4;
	/** Voll gespannter Pfeil: etwa 3 Blöcke pro Tick, Schwerkraft 0,05 pro Tick². */
	private static final double ARROW_SPEED = 3.0;
	private static final double ARROW_GRAVITY = 0.05;
	private static final double ARROW_DRAG = 0.99;
	private static final double EFFECTIVE_RANGE = 32;
	private static final int MAX_HOLD_TICKS = 100;
	/** So viele Fehlschüsse in Folge, dann bei nahem Ziel lieber zum Schwert (Wunsch von Hannes). */
	private static final int MISSES_FOR_MELEE = 3;
	private static final double MELEE_SWITCH_DISTANCE = 14;
	private static final double CRYSTAL_MIN_DISTANCE = 12; // Explosion Stärke 6 schadet bis 12 Blöcke

	private final String selector;
	private final int shots;
	private Entity target;
	private int fired;
	private int charging = -1;
	private int pause;
	private float healthBefore = -1;
	private String targetType;
	private boolean shieldWasUp;
	private int arrowsBefore;
	private int misfires;
	/** Leben des Ziels beim letzten Schuss; -1, solange kein Schuss auf Auswertung wartet. */
	private float healthAtShot = -1;
	private int missesInRow;
	private Fighter melee;
	/** Typ des Angreifers, gegen den shoot zum Schwert gewechselt hat; sonst null. */
	private String defendedAgainst;
	private int gazeBlockedTicks;
	/** Weg von der Kante, bevor auf Lohe oder Ghast geschossen wird; null = nicht nötig oder erledigt. */
	private PathFollower retreat;
	private boolean edgeChecked;
	private int retreatTicks;

	public ShootAction(String selector, int shots) {
		this.selector = selector;
		this.shots = shots;
	}

	@Override
	public int timeoutTicks() {
		// Nach dem Wechsel zum Schwert bekommt der Nahkampf eine volle Minute dazu.
		// Mit Haltezeit: Bei fernen fliegenden Zielen wartet jeder Schuss bis zu MAX_HOLD_TICKS (End-Lauf 2: nach
		// 3 bis 4 Pfeilen „timeout“, weil das Limit das Halten nicht kannte).
		return 200 + shots * (CHARGE_TICKS + PAUSE_TICKS + 10 + MAX_HOLD_TICKS) + (melee != null ? 1200 : 0);
	}

	@Override
	public boolean cancelOnDamage() {
		return false;
	}

	@Override
	public Outcome tick(ServerPlayer bot) {
		if (retreat != null) {
			if (++retreatTicks < 100 && retreat.tick(bot) == PathFollower.State.RUNNING) {
				return null;
			}
			retreat.abort(bot);
			retreat = null;
		}
		PathFollower.release(bot);
		if (target == null) {
			target = find(bot, selector);
			if (target == null) {
				// Wo die übrigen stehen, auch ohne Sichtlinie: End-Lauf 3 gab die Kristalle nach 3 von 10 auf, weil von
				// seinem Platz aus keiner mehr zu sehen war, und schoss auf den Drachen, den die übrigen sofort heilten.
				JsonObject data = progress(bot);
				com.google.gson.JsonArray hidden = new com.google.gson.JsonArray();
				String type = Inv.fullId(selector);
				bot.level().getEntities(bot, bot.getBoundingBox().inflate(128),
								entity -> entity.isAlive() && !(entity instanceof Player) && GameJson.entityType(entity).equals(type))
						.stream().sorted(Comparator.comparingDouble(bot::distanceToSqr)).limit(5)
						.forEach(entity -> {
							// Käfig aus Eisengittern: Sicht und Pfeile kommen nicht durch (End-Lauf 4: fünfmal geraten).
							JsonObject entry = new JsonObject();
							entry.add("pos", GameJson.pos(entity.blockPosition()));
							boolean caged = caged(bot, entity.blockPosition());
							entry.addProperty("caged", caged);
							// Standplatz am Boden mit freier Sicht: End-Läufe 4 und 6 bauten sich für die Sicht 12 bis 41
							// Blöcke hoch, ein Stoß des Drachen warf sie herunter.
							if (!caged) {
								net.minecraft.core.BlockPos spot = shootFrom(bot, entity);
								if (spot != null) {
									entry.add("shoot_from", GameJson.pos(spot));
								}
							}
							hidden.add(entry);
						});
				if (!hidden.isEmpty()) {
					data.add("not_in_sight", hidden);
					data.addProperty("hint", "goto a shoot_from position (on the ground, clear sight), then shoot again; do not pillar up");
				}
				return Outcome.failed("entity_not_found", data);
			}
			targetType = GameJson.entityType(target);
			// Feuerbälle stoßen von Kanten: Drachenlauf 39 schoss an einer 9 Blöcke tiefen Kante auf Lohen, ein Treffer warf ihn
			// hinunter, Sturztod. Erst auf breiten Boden bis 6 Blöcke weg.
			if (!edgeChecked && (target instanceof net.minecraft.world.entity.monster.Blaze
					|| target instanceof net.minecraft.world.entity.monster.Ghast
					|| target instanceof net.minecraft.world.entity.monster.skeleton.AbstractSkeleton) && bot.onGround() && Fighter.nearDrop(bot)) {
				edgeChecked = true;
				net.minecraft.core.BlockPos spot = safeSpot(bot);
				// Kein breiter Boden in der Nähe (eigene Brücke): Geländer an den offenen Seiten. Drachenlauf 53 schoss von seiner
				// Brücke über der Festung auf Lohen, ein Feuerball warf ihn 12 Blöcke tief zwischen zwei Witherskelette.
				if (spot == null) {
					GotoAction.rail(bot);
				}
				if (spot != null) {
					retreat = new PathFollower(de.hannes.smartcraft.nav.Goal.near(
							new de.hannes.smartcraft.nav.Pos(spot.getX(), spot.getY(), spot.getZ()), 0.5),
							de.hannes.smartcraft.nav.Moves.Options.NONE);
					return null;
				}
			}
			edgeChecked = true;
			if (target instanceof LivingEntity living) {
				healthBefore = living.getHealth();
			}
		}
		if (gone() && defendedAgainst == null) {
			JsonObject data = progress(bot);
			data.addProperty("outcome", melee != null ? "killed_melee" : "destroyed");
			return Outcome.ok(data);
		}
		if (melee != null) {
			return tickMelee(bot);
		}
		// Wer ihn im Nahkampf trifft, wird zuerst mit dem Schwert bekämpft (wie farm_spawner und defend). Drachenlauf 72: beim
		// Schießen auf eine Lohe traf ihn ein Witherskelett achtmal mit 8 Schaden von hinten, shoot schoss weiter, tot.
		LivingEntity attacker = Combat.recentAttacker(bot);
		if (attacker != null && attacker != target && attacker.distanceTo(bot) <= 4) {
			if (bot.isUsingItem()) {
				bot.stopUsingItem();
			}
			defendedAgainst = GameJson.entityType(attacker);
			melee = new Fighter(attacker);
			return null;
		}
		if (Inv.findItem(bot, "minecraft:bow") < 0) {
			return Outcome.failed("no_bow", progress(bot));
		}
		if (charging < 0 && Inv.count(bot, "minecraft:arrow") == 0 && !bot.getAbilities().instabuild) {
			return fired > 0 ? Outcome.ok(withOutcome(bot, "out_of_arrows")) : Outcome.failed("no_arrows", progress(bot));
		}
		Vec3 aim = aimPoint(bot);
		// Nicht durch einen Enderman hindurchzielen: Der Blick in sein Gesicht macht ihn wütend (End-Lauf 8: beim
		// Schießen auf Kristalle zwischen Endermen, zwei griffen an, tot). Gespannt halten und warten, bis er weg ist.
		if (!(target instanceof net.minecraft.world.entity.monster.EnderMan) && endermanInGaze(bot, aim)) {
			if (++gazeBlockedTicks > MAX_HOLD_TICKS) {
				if (bot.isUsingItem()) {
					bot.stopUsingItem();
				}
				return fired > 0 ? Outcome.ok(withOutcome(bot, "enderman_in_line")) : Outcome.failed("enderman_in_line", progress(bot));
			}
			return null;
		}
		gazeBlockedTicks = 0;
		bot.lookAt(EntityAnchorArgument.Anchor.EYES, aim);
		if (pause > 0) {
			pause--;
			return null;
		}
		if (charging < 0) {
			// Den letzten Schuss auswerten: Nach der Pause ist ein Pfeil auf 14 Blöcke längst angekommen.
			if (healthAtShot >= 0 && target instanceof LivingEntity living) {
				missesInRow = living.getHealth() < healthAtShot ? 0 : missesInRow + 1;
				healthAtShot = -1;
				if (missesInRow >= MISSES_FOR_MELEE && bot.distanceTo(target) <= MELEE_SWITCH_DISTANCE) {
					if (shieldWasUp) {
						Combat.setShieldWanted(true);
						shieldWasUp = false;
					}
					melee = new Fighter(living);
					return null;
				}
			}
			if (fired >= shots) {
				return Outcome.ok(withOutcome(bot, "shots_done"));
			}
			// Ein Endkristall explodiert beim Treffer (Stärke 6): aus der Nähe nicht schießen (End-Lauf 10 stand nach
			// 113 Blöcken Turm 5 Blöcke neben einem).
			if (target instanceof net.minecraft.world.entity.boss.enderdragon.EndCrystal && bot.distanceTo(target) < CRYSTAL_MIN_DISTANCE) {
				JsonObject data = progress(bot);
				data.addProperty("hint", "too close to an end crystal, it explodes when hit: move at least "
						+ (int) CRYSTAL_MIN_DISTANCE + " blocks away (keep sight), then shoot");
				return fired > 0 ? Outcome.ok(withOutcome(bot, "too_close")) : Outcome.failed("too_close", data);
			}
			lob = false;
			if (!bot.hasLineOfSight(target) && !seesPoint(bot, bot.getEyePosition(), crystalAware(target))) {
				lob = target instanceof net.minecraft.world.entity.boss.enderdragon.EndCrystal
						&& lobClear(bot, bot.getEyePosition(), crystalAware(target));
			}
			if (!lob && !bot.hasLineOfSight(target) && !seesPoint(bot, bot.getEyePosition(), crystalAware(target))) {
				return fired > 0 ? Outcome.ok(withOutcome(bot, "lost_sight")) : Outcome.failed("no_line_of_sight", progress(bot));
			}
			// Ein gehobener Schild blockiert den Bogen: Der Reflex hielte ihn jeden Tick oben, und kein Pfeil flöge
			// (im KI-Lauf 10 „Schüsse“ ohne verbrauchten Pfeil). Für die Dauer des Schießens senken.
			if (Combat.shieldWanted()) {
				shieldWasUp = true;
				Combat.setShieldWanted(false);
			}
			if (bot.isUsingItem()) {
				bot.releaseUsingItem();
			}
			Inv.hold(bot, Inv.findItem(bot, "minecraft:bow"));
			bot.gameMode.useItem(bot, bot.level(), bot.getMainHandItem(), InteractionHand.MAIN_HAND);
			if (!bot.isUsingItem()) {
				return fired > 0 ? Outcome.ok(withOutcome(bot, "cannot_draw")) : Outcome.failed("cannot_draw", progress(bot));
			}
			arrowsBefore = Inv.count(bot, "minecraft:arrow");
			charging = 0;
			return null;
		}
		if (++charging < CHARGE_TICKS) {
			return null;
		}
		// Gespannt halten, bis das Ziel nah genug ist: Auf 50 Blöcke dreht ein fliegendes Ziel ab, bevor der Pfeil ankommt.
		// Nur bei Zielen, die sich bewegen: Ein Endkristall kommt nie näher, das Warten kostete nur Zeit (End-Lauf 2).
		if (bot.distanceTo(target) > EFFECTIVE_RANGE && moves() && charging < CHARGE_TICKS + MAX_HOLD_TICKS) {
			return null;
		}
		// Kein Schuss, solange ein friedlicher Zombie-Piglin in der Schusslinie steht. Nether-Lauf 19: Ein Fehlschuss
		// traf einen, die Gruppe erschlug ihn.
		if (piglinInLine(bot, aim)) {
			if (charging < CHARGE_TICKS + MAX_HOLD_TICKS) {
				return null;
			}
			// stopUsingItem bricht das Spannen ab, ohne den Pfeil abzuschießen (releaseUsingItem würde schießen).
			bot.stopUsingItem();
			charging = -1;
			pause = PAUSE_TICKS;
			return fired > 0 ? Outcome.ok(withOutcome(bot, "line_blocked")) : Outcome.failed("line_blocked", progress(bot));
		}
		bot.releaseUsingItem();
		// Nur zählen, was wirklich flog.
		if (Inv.count(bot, "minecraft:arrow") < arrowsBefore || bot.getAbilities().instabuild) {
			fired++;
			if (target instanceof LivingEntity living) {
				healthAtShot = living.getHealth();
			}
		} else {
			misfires++;
			if (misfires >= 3) {
				return fired > 0 ? Outcome.ok(withOutcome(bot, "cannot_draw")) : Outcome.failed("cannot_draw", progress(bot));
			}
		}
		charging = -1;
		pause = PAUSE_TICKS;
		return null;
	}

	private Outcome tickMelee(ServerPlayer bot) {
		return switch (melee.tick(bot)) {
			case RUNNING -> null;
			case KILLED -> Outcome.ok(withOutcome(bot, "killed_melee"));
			case LOST -> Outcome.failed("target_lost", progress(bot));
			case FAILED -> {
				JsonObject data = progress(bot);
				data.addProperty("hint", melee.hint());
				yield Outcome.failed(melee.failure(), data);
			}
		};
	}

	/** Wie das Spiel prüft, ob ein Spieler einen Enderman ansieht (EnderMan.isBeingStaredBy), für die Blickrichtung zu {@code aim}. */
	private static boolean endermanInGaze(ServerPlayer bot, Vec3 aim) {
		Vec3 eye = bot.getEyePosition();
		Vec3 look = aim.subtract(eye).normalize();
		for (net.minecraft.world.entity.monster.EnderMan enderman : bot.level().getEntitiesOfClass(
				net.minecraft.world.entity.monster.EnderMan.class, bot.getBoundingBox().inflate(64),
				e -> e.isAlive() && e.getTarget() != bot)) {
			Vec3 toHead = new Vec3(enderman.getX() - bot.getX(), enderman.getEyeY() - bot.getEyeY(), enderman.getZ() - bot.getZ());
			double distance = toHead.length();
			// Mit Puffer (0,05 statt 0,025): Der Blick bewegt sich beim Spannen, und das Ziel ändert sich.
			if (look.dot(toHead.normalize()) > 1.0 - 0.05 / distance && bot.hasLineOfSight(enderman)) {
				return true;
			}
		}
		return false;
	}

	/** Ob ein friedlicher Zombie-Piglin nah an der Linie vom Auge zum Zielpunkt oder am Ziel steht. */
	private boolean piglinInLine(ServerPlayer bot, Vec3 aim) {
		Vec3 eye = bot.getEyePosition();
		net.minecraft.world.phys.AABB area = new net.minecraft.world.phys.AABB(eye, aim).inflate(3.0);
		for (LivingEntity other : bot.level().getEntitiesOfClass(LivingEntity.class, area,
				e -> e != target && Combat.isPeacefulPiglin(e, bot))) {
			// Großzügig: Der Pfeil fliegt im Bogen und braucht Zeit, ein Piglin kann hineinlaufen (Nether-Lauf 23 starb
			// trotz der engen Prüfung aus Lauf 19 an einer aufgehetzten Gruppe).
			if (other.getBoundingBox().inflate(1.2).clip(eye, aim).isPresent() || other.distanceTo(target) < 3) {
				return true;
			}
		}
		return false;
	}

	private boolean gone() {
		return target.isRemoved() || !target.isAlive() || target instanceof LivingEntity living && living.isDeadOrDying();
	}

	/**
	 * Zielpunkt: Mitte, beim Endkristall der obere Teil. Er sitzt nur 1 Block über der Turmspitze; von unten verdeckt die
	 * Turmkante die Mitte bis auf große Entfernung (End-Lauf 8: kein Standplatz für einen Kristall auf Höhe 95).
	 */
	static Vec3 crystalAware(Entity target) {
		net.minecraft.world.phys.AABB box = target.getBoundingBox();
		if (target instanceof net.minecraft.world.entity.boss.enderdragon.EndCrystal) {
			return new Vec3(box.getCenter().x, box.maxY - 0.3, box.getCenter().z);
		}
		return box.getCenter();
	}

	/** Freie Sicht von den Augen auf den Zielpunkt (nur Blöcke zählen). */
	static boolean seesPoint(ServerPlayer bot, Vec3 eye, Vec3 point) {
		var hit = bot.level().clip(new net.minecraft.world.level.ClipContext(eye, point,
				net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, bot));
		return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS || hit.getLocation().distanceToSqr(point) < 0.25;
	}

	/** Nächster begehbarer Platz am Boden (12 bis 80 Blöcke vom Ziel), von dem aus die Augen das Ziel sehen. */
	static net.minecraft.core.BlockPos shootFrom(ServerPlayer bot, Entity target) {
		net.minecraft.server.level.ServerLevel level = bot.level();
		Vec3 aim = crystalAware(target);
		net.minecraft.core.BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;
		// Erst ab einem Abstand, bei dem der Schuss höchstens 55 Grad steil ist: steile Bahnen streuen stark.
		int minRadius = Math.max(12, (int) Math.ceil((aim.y - bot.getEyeY()) * 0.7));
		for (int radius = minRadius; radius <= 80; radius += 4) {
			for (int step = 0; step < 24; step++) {
				double angle = step * Math.PI / 12;
				int x = (int) Math.floor(aim.x + radius * Math.cos(angle));
				int z = (int) Math.floor(aim.z + radius * Math.sin(angle));
				if (!level.hasChunk(x >> 4, z >> 4)) {
					continue;
				}
				int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z);
				net.minecraft.core.BlockPos feet = new net.minecraft.core.BlockPos(x, y, z);
				net.minecraft.world.level.block.state.BlockState ground = level.getBlockState(feet.below());
				// Nur der Inselboden: End-Lauf 7 bekam die Spitze eines anderen Obsidianturms (Höhe 91) genannt, baute
				// 93 Blöcke hoch und stürzte ab.
				if (y <= level.getMinY() || !ground.isFaceSturdy(level, feet.below(), net.minecraft.core.Direction.UP)
						|| ground.is(net.minecraft.world.level.block.Blocks.BEDROCK)
						|| ground.is(net.minecraft.world.level.block.Blocks.OBSIDIAN)
						|| Math.abs(y - bot.getBlockY()) > 10) {
					continue;
				}
				// Im End nicht am Brunnen: dort atmet der Drache (End-Lauf 84: Schussplatz 2 Blöcke neben der Mitte, tot).
				if (level.dimension() == net.minecraft.world.level.Level.END && x * x + z * z < 14 * 14) {
					continue;
				}
				// Nicht auf einem Turm oder an der Kante: Boden bis 3 Blöcke unter den Nachbarn.
				Vec3 eye = new Vec3(x + 0.5, y + 1.62, z + 0.5);
				// Kristall auf hohem Turm: im hohen Bogen von oben (End-Läufe 107–109: je 4 bis 5 Kristalle no_ground_spot,
				// weil die Turmkante die gerade Sicht verdeckt; die übrigen heilten den Drachen zwischen den Betten zurück).
				if (!seesPoint(bot, eye, aim) && !(target instanceof net.minecraft.world.entity.boss.enderdragon.EndCrystal
						&& radius <= 48 && lobClear(bot, eye, aim))) {
					continue;
				}
				if (!solidAround(level, feet)) {
					continue;
				}
				double distance = bot.position().distanceToSqr(eye);
				if (distance < bestDistance) {
					bestDistance = distance;
					best = feet;
				}
			}
			// Der engste Ring mit Sicht gewinnt: Aus 70 Blöcken traf End-Lauf 11 mit 12 Pfeilen keinen Kristall, weil der
			// Platz nah am Bot statt nah am Ziel gewählt war. Innerhalb des Rings der für den Bot nächste.
			if (best != null) {
				return best;
			}
		}
		return null;
	}

	/** Ob rundum Boden auf gleicher Höhe (±2) liegt: kein Turm, keine Kante zur Leere. */
	private static boolean solidAround(net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos feet) {
		for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
			net.minecraft.core.BlockPos side = feet.relative(dir, 2);
			int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, side.getX(), side.getZ());
			if (Math.abs(y - feet.getY()) > 2) {
				return false;
			}
		}
		return true;
	}

	static boolean caged(ServerPlayer bot, net.minecraft.core.BlockPos pos) {
		for (net.minecraft.core.BlockPos p : net.minecraft.core.BlockPos.betweenClosed(pos.offset(-2, -1, -2), pos.offset(2, 2, 2))) {
			if (bot.level().getBlockState(p).is(net.minecraft.world.level.block.Blocks.IRON_BARS)) {
				return true;
			}
		}
		return false;
	}

	private boolean moves() {
		return !(target instanceof net.minecraft.world.entity.boss.enderdragon.EndCrystal)
				&& target.position().distanceToSqr(target.xo, target.yo, target.zo) > 1.0E-4;
	}

	/**
	 * Wohin zielen: Mitte des Ziels, wo es bei Ankunft des Pfeils sein wird. Die Höhe kommt aus einer Simulation der
	 * Flugbahn (Luftwiderstand und Schwerkraft wie im Spiel), nicht aus einer Näherung: Endkristalle 30 Blöcke höher
	 * und 40 Blöcke entfernt traf die alte Formel nicht (End-Lauf 2).
	 */
	private Vec3 aimPoint(ServerPlayer bot) {
		Vec3 center = crystalAware(target);
		if (target instanceof EnderDragon dragon) {
			// Treffbar sind nur die Teile (eigene Hitboxen). Der Körper ist das größte Ziel; der Kopf nimmt
			// mehr Schaden, ist aber schwer zu treffen.
			center = dragon.getSubEntities()[2].getBoundingBox().getCenter();
		}
		Vec3 eye = bot.getEyePosition();
		// Tatsächliche Bewegung seit dem letzten Tick: Beim Drachen ist getDeltaMovement unzuverlässig.
		Vec3 velocity = new Vec3(target.getX() - target.xo, target.getY() - target.yo, target.getZ() - target.zo);
		Vec3 goal = center;
		double pitch = 0;
		if (lob) {
			double horizontal = Math.max(0.01, Math.hypot(center.x - eye.x, center.z - eye.z));
			double high = lobPitch(horizontal, center.y - eye.y);
			if (!Double.isNaN(high)) {
				return new Vec3(center.x, eye.y + horizontal * Math.tan(high), center.z);
			}
		}
		for (int round = 0; round < 3; round++) {
			double horizontal = Math.hypot(goal.x - eye.x, goal.z - eye.z);
			pitch = solvePitch(horizontal, goal.y - eye.y);
			goal = center.add(velocity.scale(flightTicks(horizontal, pitch)));
		}
		double horizontal = Math.max(0.01, Math.hypot(goal.x - eye.x, goal.z - eye.z));
		// Punkt in Schussrichtung: gleiche Richtung, Höhe aus dem Abschusswinkel.
		return new Vec3(goal.x, eye.y + horizontal * Math.tan(pitch), goal.z);
	}

	/** Schießt der laufende Schuss im hohen Bogen (Kristall ohne gerade Sicht). */
	private boolean lob;

	/** Steiler Abschusswinkel (über dem Winkel größter Höhe, bis 85 Grad), mit dem der Pfeil von oben ankommt; NaN, wenn keiner reicht. */
	static double lobPitch(double horizontal, double dy) {
		double best = 0;
		double bestHeight = -1.0E9;
		for (int deg = 0; deg <= 85; deg++) {
			double h = heightAt(horizontal, Math.toRadians(deg));
			if (h > bestHeight) {
				bestHeight = h;
				best = Math.toRadians(deg);
			}
		}
		if (bestHeight < dy + 0.5) {
			return Double.NaN;
		}
		double low = best;
		double high = Math.toRadians(85);
		if (heightAt(horizontal, high) > dy) {
			return Double.NaN;
		}
		for (int i = 0; i < 40; i++) {
			double mid = (low + high) / 2;
			if (heightAt(horizontal, mid) > dy) {
				low = mid;
			} else {
				high = mid;
			}
		}
		return (low + high) / 2;
	}

	/** Ob ein Pfeil im hohen Bogen vom Auge bis zum Ziel frei fliegt (Bahn Tick für Tick gegen Blöcke geprüft). */
	static boolean lobClear(ServerPlayer bot, Vec3 eye, Vec3 point) {
		double horizontal = Math.hypot(point.x - eye.x, point.z - eye.z);
		if (horizontal < 4) {
			return false;
		}
		double pitch = lobPitch(horizontal, point.y - eye.y);
		if (Double.isNaN(pitch)) {
			return false;
		}
		double ux = (point.x - eye.x) / horizontal;
		double uz = (point.z - eye.z) / horizontal;
		double vh = Math.cos(pitch) * ARROW_SPEED;
		double vy = Math.sin(pitch) * ARROW_SPEED;
		Vec3 pos = eye;
		double travelled = 0;
		for (int tick = 0; tick < 300; tick++) {
			double stepH = vh;
			boolean last = travelled + vh >= horizontal;
			if (last) {
				stepH = horizontal - travelled;
			}
			double stepY = vy * stepH / vh;
			Vec3 next = pos.add(ux * stepH, stepY, uz * stepH);
			// Das letzte Stück endet am Zielpunkt: Treffer auf den Kristall selbst zählt nicht als Hindernis.
			if (!seesPoint(bot, pos, next)) {
				return false;
			}
			if (last) {
				return true;
			}
			pos = next;
			travelled += vh;
			vh *= ARROW_DRAG;
			vy = vy * ARROW_DRAG - ARROW_GRAVITY;
		}
		return false;
	}

	/** Abschusswinkel (Bogenmaß, aufwärts positiv), mit dem der Pfeil in `horizontal` Abstand die Höhe `dy` hat. */
	private static double solvePitch(double horizontal, double dy) {
		double low = -Math.PI / 2 + 0.01;
		// Bis 80 Grad: Vom Boden nah an einem Turm liegt der Kristall steiler als 45 Grad (End-Lauf 28: 36 Pfeile, kein Treffer).
		double high = Math.toRadians(80);
		for (int i = 0; i < 40; i++) {
			double mid = (low + high) / 2;
			if (heightAt(horizontal, mid) < dy) {
				low = mid;
			} else {
				high = mid;
			}
		}
		return (low + high) / 2;
	}

	/** Höhe des Pfeils relativ zum Auge, wenn er `horizontal` Blöcke weit geflogen ist (NaN-frei: fällt er vorher zu tief, sehr klein). */
	private static double heightAt(double horizontal, double pitch) {
		double vx = Math.cos(pitch) * ARROW_SPEED;
		double vy = Math.sin(pitch) * ARROW_SPEED;
		double x = 0;
		double y = 0;
		for (int tick = 0; tick < 200; tick++) {
			if (x + vx >= horizontal) {
				return y + vy * (horizontal - x) / vx;
			}
			x += vx;
			y += vy;
			vx *= ARROW_DRAG;
			vy = vy * ARROW_DRAG - ARROW_GRAVITY;
		}
		return -1.0E9;
	}

	private static double flightTicks(double horizontal, double pitch) {
		double vx = Math.cos(pitch) * ARROW_SPEED;
		double x = 0;
		int tick = 0;
		while (x < horizontal && tick < 200) {
			x += vx;
			vx *= ARROW_DRAG;
			tick++;
		}
		return tick;
	}

	private static Entity find(ServerPlayer bot, String selector) {
		try {
			Entity entity = bot.level().getEntity(Integer.parseInt(selector));
			return entity != null && entity != bot && entity.distanceTo(bot) <= SEARCH_RADIUS ? entity : null;
		} catch (NumberFormatException e) {
			String type = Inv.fullId(selector);
			return bot.level().getEntities(bot, bot.getBoundingBox().inflate(SEARCH_RADIUS),
							entity -> entity.isAlive() && !(entity instanceof Player) && GameJson.entityType(entity).equals(type)
									&& (bot.hasLineOfSight(entity) || seesPoint(bot, bot.getEyePosition(), crystalAware(entity))))
					.stream().min(Comparator.comparingDouble(bot::distanceToSqr)).orElse(null);
		}
	}

	private JsonObject withOutcome(ServerPlayer bot, String outcome) {
		JsonObject data = progress(bot);
		data.addProperty("outcome", outcome);
		return data;
	}

	@Override
	public void release(ServerPlayer bot) {
		if (melee != null) {
			melee.release(bot);
		}
		if (bot.isUsingItem()) {
			// Beim Abbruch keinen Pfeil ins Blaue schießen.
			bot.stopUsingItem();
		}
		if (shieldWasUp) {
			Combat.setShieldWanted(true);
		}
		PathFollower.release(bot);
	}

	@Override
	public JsonObject progress(ServerPlayer bot) {
		JsonObject data = new JsonObject();
		if (target != null) {
			data.add("target", Combat.describe(target));
			if (target instanceof LivingEntity living && healthBefore >= 0) {
				data.addProperty("damage_dealt", GameJson.round1(healthBefore - Math.max(0, living.getHealth())));
			}
		} else if (targetType != null) {
			data.addProperty("target_type", targetType);
		}
		data.addProperty("arrows_shot", fired);
		data.addProperty("arrows_left", Inv.count(bot, "minecraft:arrow"));
		if (defendedAgainst != null) {
			data.addProperty("defended_against", defendedAgainst);
			data.addProperty("hint", "a " + defendedAgainst.replace("minecraft:", "") + " hit you in melee, shoot fought it with the sword"
					+ " first; your bow target may still be alive, shoot again");
		}
		if (melee != null) {
			data.addProperty("switched_to_melee", true);
			data.addProperty("hits", melee.hits());
		}
		return data;
	}

	/** Nächster Stehplatz bis 6 Blöcke (Höhe ±1) mit festem Boden, 2 Blöcken Luft und ohne Abgrund daneben. */
	private static net.minecraft.core.BlockPos safeSpot(ServerPlayer bot) {
		var level = bot.level();
		net.minecraft.core.BlockPos feet = bot.blockPosition();
		net.minecraft.core.BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (int dx = -6; dx <= 6; dx++) {
			for (int dz = -6; dz <= 6; dz++) {
				for (int dy = -1; dy <= 1; dy++) {
					net.minecraft.core.BlockPos pos = feet.offset(dx, dy, dz);
					double dist = dx * dx + dy * dy + dz * dz;
					if (dist >= bestDist || dist < 1) {
						continue;
					}
					net.minecraft.core.BlockPos below = pos.below();
					var under = level.getBlockState(below);
					if (under.getCollisionShape(level, below).isEmpty() || !under.getFluidState().isEmpty()
							|| under.is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK)
							|| !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
							|| !level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()
							|| !level.getFluidState(pos).isEmpty() || Fighter.nearDrop(level, pos)) {
						continue;
					}
					best = pos;
					bestDist = dist;
				}
			}
		}
		return best;
	}
}
