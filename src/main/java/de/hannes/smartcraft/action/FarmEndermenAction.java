package de.hannes.smartcraft.action;

import de.hannes.smartcraft.SmartCraftMod;

import com.google.gson.JsonObject;
import de.hannes.smartcraft.protocol.Event.Status;
import de.hannes.smartcraft.skill.Inv;
import de.hannes.smartcraft.skill.ItemGatherer;
import de.hannes.smartcraft.skill.PathFollower;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.EnderMan;

import java.util.Comparator;

/**
 * {@code farm_endermen}: tötet Endermen in der Nähe nacheinander und hebt die Perlen auf, bis {@code count} Perlen da
 * sind. Jeder Kampf läuft wie {@code attack} (ansehen lockt ihn an, dann kommt er selbst). Am besten im Warped-Wald.
 * Wunsch von Hannes nach Perlen-Lauf 8: am Tauschen fehlte das Gold. Schema: {@code schemas/commands/farm_endermen.schema.json}.
 */
public final class FarmEndermenAction extends Action {
	private static final String PEARL = "minecraft:ender_pearl";
	private static final double RADIUS = 48;
	private static final int MAX_TICKS = 20 * 600;
	/** Ein Kampf, der so lange dauert, wird aufgegeben (der nächste Enderman ist dran). */
	private static final int FIGHT_TICKS = 1200;
	/** So lange zieht der Bot umher, ohne einen Enderman zu sehen, bevor er aufgibt. */
	private static final int IDLE_TICKS = 20 * 180;
	/** Endermen bis hierhin steuert der Bot an; Perlen-Lauf 9: nachts keiner bis 48 Blöcke, der Bot stand nur da. */
	private static final double FAR_RADIUS = 128;
	private static final int ROAM_STEP = 40;
	private static final int[][] ROAM_DIRS = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};

	private final int count;
	private final float minHealth;
	private static final int STALL_TICKS = 20 * 180;
	private static final float MULTI_MIN_HEALTH = 14;
	private net.minecraft.world.phys.Vec3 stallPos;
	private int stallKilled;
	private int stallTick;
	/** Enderman-ID -> Tick, ab dem er wieder dran ist (Perlen-Lauf 13: ein unerreichbarer 6 Blöcke tiefer band ihn 8 Minuten). */
	private final java.util.Map<Integer, Integer> skipped = new java.util.HashMap<>();
	private static final int SKIP_TICKS = 1200;
	/** Wo das aktuelle Ziel zuletzt stand: Dort liegt nach dem Tod die Perle. */
	private net.minecraft.core.BlockPos targetPos;
	private int startPearls = -1;
	private int ticks;
	private int idle;
	private int fightTicks;
	/** Ticks, die ein Enderman-Ziel schon weiter als {@link #FAR_AWAY} weg ist. */
	private int farTicks;
	private static final double FAR_AWAY = 20;
	private int killed;
	/** Ausgänge der einzelnen Kämpfe (ok, no_path, target_lost, timeout …), fürs Ergebnis und die Fehlersuche. */
	private final java.util.Map<String, Integer> fights = new java.util.TreeMap<>();
	private AttackAction attack;
	private net.minecraft.world.entity.LivingEntity attackTarget;
	private ItemGatherer gatherer;
	private PathFollower roam;
	/** Weg von einer Kante zu einem sicheren Platz, bevor es losgeht. */
	private PathFollower toSafeSpot;
	private int safeSpotTicks;
	private boolean movedToSafeSpot;
	private boolean spotChecked;
	private static final int SAFE_SPOT_TICKS = 600;
	private static final int SAFE_SPOT_RADIUS = 20;
	/** Kämpfe in Folge ohne Kill; ab {@link #MAX_FAILED_FIGHTS} zieht der Bot eine Etappe weiter. */
	private int failedFights;
	private static final int MAX_FAILED_FIGHTS = 5;
	private PathFollower relocate;
	private int relocateTicks;
	private int relocations;
	private static final int MAX_RELOCATIONS = 2;
	private int roamDir;
	private int roamTicks;

	public FarmEndermenAction(int count, float minHealth) {
		this.count = count;
		this.minHealth = minHealth;
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
	public int timeoutTicks() {
		return MAX_TICKS + 200;
	}

	@Override
	public Outcome tick(ServerPlayer bot) {
		ticks++;
		if (startPearls < 0) {
			startPearls = Inv.count(bot, PEARL);
			// Nur im Nether (Warped-Wald), Entscheidung von Hannes 02.11.2026: In der Oberwelt sind Endermen zu selten, Drachenlauf 44
			// bekam nachts in einer Stunde 1 Perle und starb an einem Creeper.
			if (bot.level().dimension() != net.minecraft.world.level.Level.NETHER) {
				JsonObject data = progress(bot);
				data.addProperty("hint", "farm endermen only in the nether: go through a portal, locate warped_forest, farm_endermen there");
				return Outcome.failed("not_in_nether", data);
			}
			// Hannes' Lauf 17.10.: Im Warped-Wald baute er mit den letzten Brettern über einen 24 Blöcke tiefen Abgrund
			// und landete danach im Lavasee. Im Nether erst Bausteine holen.
			if (bot.level().dimension() == net.minecraft.world.level.Level.NETHER && fireproofBlocks(bot) < MIN_NETHER_BLOCKS) {
				JsonObject data = progress(bot);
				data.addProperty("blocks", fireproofBlocks(bot));
				data.addProperty("hint", "fewer than " + MIN_NETHER_BLOCKS + " non-flammable blocks; collect netherrack (count 64, allow_dig) first");
				return Outcome.failed("few_blocks", data);
			}
			// Drachenlauf 64: gestartet am Ende der eigenen Brücke (20 Blöcke tief ringsum), ein Enderman-Schlag stieß ihn in den
			// Lavasee darunter. Endermen schlagen mit Rückstoß: nur auf festem, breitem Boden farmen.
			// Erst selbst einen sicheren Platz suchen und hingehen. Log-Auswertung 13.11.: 96-mal unsafe_spot, die KI rief den
			// Befehl wiederholt von derselben Kante auf.
			if (de.hannes.smartcraft.skill.Fighter.nearDrop(bot)) {
				net.minecraft.core.BlockPos safe = safeSpot(bot);
				if (safe != null) {
					toSafeSpot = new PathFollower(de.hannes.smartcraft.nav.Goal.near(
							new de.hannes.smartcraft.nav.Pos(safe.getX(), safe.getY(), safe.getZ()), 0),
							new de.hannes.smartcraft.nav.Moves.Options(true, false));
					return null;
				}
			}
		}
		if (toSafeSpot != null) {
			PathFollower.State state = toSafeSpot.tick(bot);
			if (state == PathFollower.State.RUNNING && ++safeSpotTicks < SAFE_SPOT_TICKS) {
				return null;
			}
			toSafeSpot.abort(bot);
			toSafeSpot = null;
			movedToSafeSpot = true;
		}
		if (!spotChecked) {
			spotChecked = true;
			if (de.hannes.smartcraft.skill.Fighter.nearDrop(bot)) {
				JsonObject data = progress(bot);
				data.addProperty("moved_to_safe_spot", movedToSafeSpot);
				data.addProperty("hint", "you stand at a drop (bridge, ledge): enderman hits knock you off. goto solid wide ground "
						+ "(e.g. the warped forest floor, allow_dig) first, then farm_endermen");
				return Outcome.failed("unsafe_spot", data);
			}
		}
		if (gained(bot) >= count) {
			return Outcome.ok(progress(bot));
		}
		if (ticks > MAX_TICKS) {
			// Ohne einen Kill ist das kein Erfolg: Drachenläufe 73 und 83 bekamen nach 10 Minuten ok mit 0 Kills und blieben an
			// derselben Stelle.
			if (killed == 0 && gained(bot) == 0) {
				JsonObject data = progress(bot);
				data.addProperty("hint", "no enderman killed here in 10 minutes (they teleport away or cannot be reached): move to "
						+ "another part of the warped forest (open flat floor, 50+ blocks away) or try again later");
				return Outcome.failed("no_kills", data);
			}
			return Outcome.ok(progress(bot));
		}
		// Stillstand: 3 Minuten ohne Kill und ohne 4 Blöcke Bewegung. Drachenlauf 26: 30 Minuten an derselben Stelle im Fels,
		// 18 Kämpfe mit timeout, kein Kill.
		if (stallPos == null || bot.position().distanceTo(stallPos) > 4 || killed != stallKilled) {
			stallPos = bot.position();
			stallKilled = killed;
			stallTick = ticks;
		} else if (ticks - stallTick > STALL_TICKS) {
			if (attack != null) {
				attack.release(bot);
			}
			JsonObject data = progress(bot);
			data.addProperty("hint", "no kill and no movement for 3 minutes: the endermen cannot be reached from here. goto open ground "
					+ "(at least 20 blocks away, same height as the endermen), then farm_endermen again");
			return Outcome.failed("stalled", data);
		}
		// Leben auch außerhalb eines Kampfs prüfen (Drachenlauf 11: beim Perlensammeln von 17,5 auf 2,9 geschlagen).
		// Mehrere wütende Endermen: früher aufhören. Drachenlauf 31: zwei Endermen gleichzeitig, je 7 Schaden, Abbruch erst bei 8,
		// danach tot. Ab zwei Angreifern gilt mindestens 14.
		long angry = bot.level().getEntitiesOfClass(EnderMan.class, bot.getBoundingBox().inflate(16),
				e -> e.isAlive() && e.getTarget() == bot).size();
		if (angry >= 2 && bot.getHealth() <= Math.max(minHealth, MULTI_MIN_HEALTH)) {
			if (attack != null) {
				attack.release(bot);
				attack = null;
			}
			JsonObject data = progress(bot);
			data.addProperty("angry_endermen", angry);
			data.addProperty("hint", angry + " endermen attack you at once: get under a 2 block high roof (shelter) where they "
					+ "cannot follow, eat, then farm_endermen again");
			return Outcome.cancelled("health_low", data);
		}
		if (attack == null && bot.getHealth() <= minHealth) {
			JsonObject data = progress(bot);
			data.addProperty("hint", "health low; eat, then farm_endermen again");
			return Outcome.cancelled("health_low", data);
		}
		// Angreifer gehen vor dem Einsammeln: Drachenlauf 11, ein Enderman traf ihn beim Perlensammeln zehnmal, ohne Gegenwehr.
		if (gatherer != null && de.hannes.smartcraft.skill.Combat.recentAttacker(bot) == null) {
			if (gatherer.tick(bot) != ItemGatherer.State.DONE) {
				return null;
			}
			gatherer = null;
		}
		// Wer ihn gerade schlägt, kommt zuerst dran. Perlen-Lauf 12: Ein Witherskelett traf ihn zehnmal von hinten,
		// während er Endermen suchte, und er schlug nie zurück.
		net.minecraft.world.entity.LivingEntity attacker = de.hannes.smartcraft.skill.Combat.recentAttacker(bot);
		// Creeper schlagen nie zu, sie sprengen: Drachenlauf 44 lief nachts beim Umherziehen in einen Creeper (14 auf 3 Blöcke
		// in 2 s) und starb mit 18,7 Leben. Nahe Creeper gehen vor (Fighter weicht aus und schlägt im Sprint).
		if (attacker == null && !(attackTarget instanceof net.minecraft.world.entity.monster.Creeper)) {
			attacker = bot.level().getEntitiesOfClass(net.minecraft.world.entity.monster.Creeper.class,
							bot.getBoundingBox().inflate(7), e -> e.isAlive() && e.hasLineOfSight(bot))
					.stream().min(Comparator.comparingDouble(bot::distanceToSqr)).orElse(null);
		}
		if (attacker != null && attacker != attackTarget) {
			if (attack != null) {
				attack.release(bot);
			}
			if (roam != null) {
				roam.abort(bot);
				roam = null;
			}
			if (relocate != null) {
				relocate.abort(bot);
				relocate = null;
			}
			gatherer = null;
			attack = new AttackAction(attacker, minHealth);
			attackTarget = attacker;
			fightTicks = 0;
		}
		if (attack != null && attackTarget != null && attackTarget.isAlive()) {
			targetPos = attackTarget.blockPosition();
		}
		if (attack != null) {
			if (fightTicks + 1 > FIGHT_TICKS && attackTarget != null) {
				SmartCraftMod.LOGGER.info("farm_endermen fight_timeout bot=" + bot.blockPosition().toShortString() + " target="
						+ attackTarget.blockPosition().toShortString() + " dist=" + String.format("%.1f", attackTarget.distanceTo(bot)));
			}
			// Weggeteleportierte Endermen nicht verfolgen. Drachenlauf 38: Kämpfe liefen 60 s gegen Endermen 27–29 Blöcke weit weg
			// bis zum Timeout, kein Kill in 8 Minuten.
			farTicks = attackTarget instanceof EnderMan && attackTarget.isAlive() && attackTarget.distanceTo(bot) > FAR_AWAY ? farTicks + 1 : 0;
			Outcome fight = ++fightTicks > FIGHT_TICKS ? Outcome.failed("timeout", new JsonObject())
					: farTicks > 100 ? Outcome.failed("teleported_away", new JsonObject()) : attack.tick(bot);
			if (fight == null) {
				return null;
			}
			attack.release(bot);
			attack = null;
			farTicks = 0;
			boolean wasEnderman = attackTarget instanceof EnderMan;
			fights.merge(fight.status() == Status.OK ? "killed" : String.valueOf(fight.reason()), 1, Integer::sum);
			attackTarget = null;
			if (wasEnderman) {
				failedFights = fight.status() == Status.OK ? 0 : failedFights + 1;
			}
			if (fight.status() == Status.OK) {
				if (wasEnderman) {
					killed++;
					relocations = 0;
				}
				// Perlen um die Todesstelle einsammeln, nicht nur um den Bot. Perlen-Lauf 13: 7 Endermen, 1 Perle.
				java.util.List<net.minecraft.core.BlockPos> anchors = targetPos == null ? java.util.List.of(bot.blockPosition())
						: java.util.List.of(bot.blockPosition(), targetPos);
				gatherer = new ItemGatherer(anchors, 8, 400);
			} else if ("health_low".equals(fight.reason())) {
				JsonObject data = progress(bot);
				data.addProperty("hint", "health low; eat, then farm_endermen again");
				return Outcome.cancelled("health_low", data);
			}
			return null;
		}
		// Mehrere Kämpfe in Folge ohne Kill (weggeteleportiert, unerreichbar): Hier wird es nichts, selbst eine Etappe weiterziehen
		// statt 3 Minuten bis stalled zu bleiben. Log-Auswertung 13.11.: 111-mal stalled, 18 % der Spielzeit seit dem 03.11.
		if (relocate == null && failedFights >= MAX_FAILED_FIGHTS) {
			failedFights = 0;
			if (++relocations > MAX_RELOCATIONS) {
				JsonObject data = progress(bot);
				data.addProperty("hint", "moved on " + MAX_RELOCATIONS + " times, still no kill: the endermen here teleport away or cannot be "
						+ "reached. goto another part of the warped forest (open flat floor, 80+ blocks away), then farm_endermen again");
				return Outcome.failed("stalled", data);
			}
			for (int tries = 0; tries < ROAM_DIRS.length; tries++) {
				roamDir = (roamDir + 1) % ROAM_DIRS.length;
				if (safeRoamTarget(bot, ROAM_DIRS[roamDir])) {
					break;
				}
			}
			int[] dir = ROAM_DIRS[roamDir];
			relocate = new PathFollower(de.hannes.smartcraft.nav.Goal.near(new de.hannes.smartcraft.nav.Pos(
					bot.getBlockX() + dir[0] * ROAM_STEP, bot.getBlockY(), bot.getBlockZ() + dir[1] * ROAM_STEP), 8),
					de.hannes.smartcraft.nav.Moves.Options.NONE);
			relocateTicks = 0;
		}
		if (relocate != null) {
			if (relocate.tick(bot) == PathFollower.State.RUNNING && ++relocateTicks < 600) {
				return null;
			}
			relocate.abort(bot);
			relocate = null;
		}
		EnderMan next = bot.level().getEntitiesOfClass(EnderMan.class, bot.getBoundingBox().inflate(RADIUS),
						e -> e.isAlive() && !isSkipped(e) && !e.isInWater())
				.stream().min(Comparator.comparingDouble(bot::distanceToSqr)).orElse(null);
		if (next == null) {
			if (++idle > IDLE_TICKS) {
				PathFollower.release(bot);
				if (gained(bot) > 0) {
					return Outcome.ok(progress(bot));
				}
				JsonObject data = progress(bot);
				data.addProperty("hint", "no enderman within " + (int) RADIUS + " blocks; locate warped_forest and go there");
				return Outcome.failed("no_enderman", data);
			}
			roam(bot);
			return null;
		}
		if (roam != null) {
			roam.abort(bot);
			roam = null;
		}
		idle = 0;
		fightTicks = 0;
		skipped.put(next.getId(), ticks + SKIP_TICKS);
		// Nur graben (ohne Bauen) im Nether war ein Versuch nach Drachenlauf 69: Läufe 70/71 kamen damit in 10 Minuten auf 1 Kill
		// (68-mal target_lost, 81-mal teleported_away). Deshalb wieder mit Bauen.
		attack = new AttackAction(next, minHealth).allowDig();
		attackTarget = next;
		return null;
	}

	/** Zum nächsten Enderman bis {@link #FAR_RADIUS}, sonst in Etappen von {@link #ROAM_STEP} Blöcken umherziehen. */
	private void roam(ServerPlayer bot) {
		if (roam != null && ++roamTicks < 600) {
			PathFollower.State state = roam.tick(bot);
			if (state == PathFollower.State.RUNNING) {
				return;
			}
			if (state == PathFollower.State.FAILED) {
				roamDir = (roamDir + 1) % ROAM_DIRS.length;
			}
		}
		roamTicks = 0;
		EnderMan far = bot.level().getEntitiesOfClass(EnderMan.class, bot.getBoundingBox().inflate(FAR_RADIUS, 32, FAR_RADIUS),
						e -> e.isAlive() && !e.isInWater() && !isSkipped(e))
				.stream().min(Comparator.comparingDouble(bot::distanceToSqr)).orElse(null);
		de.hannes.smartcraft.nav.Pos goal;
		if (far != null) {
			goal = new de.hannes.smartcraft.nav.Pos(far.getBlockX(), far.getBlockY(), far.getBlockZ());
		} else {
			// Hannes' Lauf 17.10.: Beim Umherziehen im Warped-Wald landete er auf Höhe 32 im Lavasee und verbrannte.
			// Keine Etappe, deren Ziel über Lava oder tief unter ihm liegt.
			for (int tries = 0; tries < ROAM_DIRS.length && !safeRoamTarget(bot, ROAM_DIRS[roamDir]); tries++) {
				roamDir = (roamDir + 1) % ROAM_DIRS.length;
			}
			int[] dir = ROAM_DIRS[roamDir];
			goal = new de.hannes.smartcraft.nav.Pos(bot.getBlockX() + dir[0] * ROAM_STEP, bot.getBlockY(), bot.getBlockZ() + dir[1] * ROAM_STEP);
		}
		roam = new PathFollower(de.hannes.smartcraft.nav.Goal.near(goal, far != null ? 24 : 8),
				de.hannes.smartcraft.nav.Moves.Options.NONE);
	}

	/** Etappenziel in Richtung {@code dir}: der erste feste Block unter Augenhöhe ist kein Lava und liegt höchstens
	 * {@link #ROAM_MAX_DROP} Blöcke unter dem Bot. Ungeladen gilt als sicher (der Weg dorthin prüft selbst). */
	private static final int MIN_NETHER_BLOCKS = 16;

	/** Bausteine, die im Nether nicht brennen (Netherrack, Schwarzstein, Bruchstein …). */
	private static int fireproofBlocks(ServerPlayer bot) {
		int total = 0;
		for (int slot = 0; slot < Inv.MAIN_SLOTS; slot++) {
			var stack = bot.getInventory().getItem(slot);
			if (!stack.isEmpty() && Inv.isScaffold(stack) && !Inv.isFlammable(stack)) {
				total += stack.getCount();
			}
		}
		return total;
	}

	/**
	 * Der nächste Platz zum Stehen bis {@link #SAFE_SPOT_RADIUS} Blöcke (höchstens 4 höher oder tiefer), um den herum kein
	 * Abgrund liegt; {@code null}, wenn es keinen gibt.
	 */
	private static net.minecraft.core.BlockPos safeSpot(ServerPlayer bot) {
		var level = bot.level();
		var cells = new de.hannes.smartcraft.nav.WorldCells(level);
		net.minecraft.core.BlockPos feet = bot.blockPosition();
		net.minecraft.core.BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;
		for (int dx = -SAFE_SPOT_RADIUS; dx <= SAFE_SPOT_RADIUS; dx++) {
			for (int dz = -SAFE_SPOT_RADIUS; dz <= SAFE_SPOT_RADIUS; dz++) {
				for (int dy = -4; dy <= 4; dy++) {
					double distance = dx * dx + dz * dz + dy * dy * 4;
					if (distance >= bestDistance) {
						continue;
					}
					net.minecraft.core.BlockPos pos = feet.offset(dx, dy, dz);
					if (de.hannes.smartcraft.nav.Moves.kind(cells, pos.getX(), pos.getY(), pos.getZ()) == de.hannes.smartcraft.nav.Moves.Kind.GROUND
							&& !de.hannes.smartcraft.skill.Fighter.nearDrop(level, pos)) {
						best = pos;
						bestDistance = distance;
					}
				}
			}
		}
		return best;
	}

	private static boolean safeRoamTarget(ServerPlayer bot, int[] dir) {
		var level = bot.level();
		int x = bot.getBlockX() + dir[0] * ROAM_STEP, z = bot.getBlockZ() + dir[1] * ROAM_STEP;
		if (!level.hasChunk(x >> 4, z >> 4)) {
			return true;
		}
		for (int y = bot.getBlockY() + 6; y >= bot.getBlockY() - ROAM_MAX_DROP; y--) {
			var state = level.getBlockState(new net.minecraft.core.BlockPos(x, y, z));
			if (state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) {
				return false;
			}
			if (state.blocksMotion()) {
				return true;
			}
		}
		return false;
	}

	private static final int ROAM_MAX_DROP = 12;

	private boolean isSkipped(EnderMan enderman) {
		Integer until = skipped.get(enderman.getId());
		return until != null && ticks < until;
	}

	private int gained(ServerPlayer bot) {
		return Inv.count(bot, PEARL) - startPearls;
	}

	@Override
	public void release(ServerPlayer bot) {
		if (attack != null) {
			attack.release(bot);
		}
		if (gatherer != null) {
			gatherer.release(bot);
		}
		if (roam != null) {
			roam.abort(bot);
		}
		if (relocate != null) {
			relocate.abort(bot);
		}
		if (toSafeSpot != null) {
			toSafeSpot.abort(bot);
		}
		PathFollower.release(bot);
	}

	@Override
	public JsonObject progress(ServerPlayer bot) {
		JsonObject data = new JsonObject();
		data.addProperty("killed", killed);
		JsonObject outcomes = new JsonObject();
		fights.forEach(outcomes::addProperty);
		data.add("fights", outcomes);
		data.addProperty("pearls_gained", Math.max(0, startPearls < 0 ? 0 : gained(bot)));
		data.addProperty("ender_pearls", Inv.count(bot, PEARL));
		data.addProperty("health", de.hannes.smartcraft.perception.GameJson.round1(bot.getHealth()));
		return data;
	}
}
