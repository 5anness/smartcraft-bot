package de.hannes.smartcraft.reflex;

import net.minecraft.world.effect.MobEffects;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import de.hannes.smartcraft.perception.EventBus;
import de.hannes.smartcraft.perception.GameJson;
import de.hannes.smartcraft.protocol.Priority;
import de.hannes.smartcraft.skill.Combat;
import de.hannes.smartcraft.skill.Eater;
import de.hannes.smartcraft.skill.PathFollower;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraft.tags.FluidTags;

/**
 * Was der Bot ohne Befehl tut, weil es keinen Aufschub duldet oder ein Spieler es nebenbei täte:
 * im Notfall essen, bei fehlendem Leben nebenbei essen, unter Wasser Luft holen, keinen Enderman ansehen, den Schild oben halten. Entscheidungen bleiben
 * sonst beim Absender der Befehle. Läuft auf dem Server-Thread, um die Aktion des Ticks herum.
 */
public final class Reflexes {
	/** Bei so wenig Leben isst der Bot von selbst, wenn er etwas hat. */
	private static final float EMERGENCY_HEALTH = 4.0f;
	/** Auffüllen: Fehlt Leben und ist der Hunger nicht voll, isst er nebenbei (nur Unbedenkliches). */
	private static final int TOP_UP_COOLDOWN_TICKS = 100;
	/**
	 * Nicht mitten im Nahkampf: So nah darf kein Gegner sein. Weiter weg wird gegessen: Bei den Lohen ist immer einer
	 * in Sicht, und ohne Essen heilte der Bot dort kaum.
	 */
	private static final double TOP_UP_SAFE_DISTANCE = 4;
	private static final int EAT_COOLDOWN_TICKS = 200;
	private static final float COMBAT_APPLE_HEALTH = 10.0f;
	private static final double COMBAT_APPLE_RANGE = 8;
	/**
	 * Von 300 Ticks Luft: schon bei zwei Dritteln sucht der Bot Luft (vorher bei der Hälfte); so bleiben zehn Sekunden für
	 * den Weg. Läufe 39, 52, 60 (04.11.2026): unter Tage und unter dem Meer ertrunken, der Weg zur Luft war zu weit.
	 */
	private static final int LOW_AIR_TICKS = 200;
	/** Länger als die Luft reicht: Bis zum Ertrinken weiter Richtung Luft, statt aufzugeben und die Aktion weiterzulassen. */
	private static final int MAX_SURFACING_TICKS = 600;
	private static final int RETRY_SURFACING_TICKS = 40;
	/** So stark bremst das Spiel einen Spieler, der isst oder den Schild hält. */
	private static final float USING_ITEM_SLOWDOWN = 0.2f;
	private static final double SHIELD_RANGED_RANGE = 16;
	private static final double SHIELD_MELEE_RANGE = 6;

	private final EventBus bus;
	private final GazeGuard gazeGuard = new GazeGuard();
	private Eater eater;
	private boolean pausedAction;
	private long nextEatTick;
	private String eatReason;
	private boolean surfacing;
	private boolean surfacingPaused;
	private long surfacingSince;
	private long nextSurfacingTick;
	private AirSeeker airSeeker;
	private LavaEscape lavaEscape;
	private final DragonBreathGuard breathGuard = new DragonBreathGuard();
	private de.hannes.smartcraft.skill.BlockBreaker wallBreaker;
	private Eater drinker;
	private long lavaSince;
	private long nextAppleTick;
	private boolean lavaStuckReported;
	private static final int LAVA_STUCK_TICKS = 300;

	public Reflexes(EventBus bus) {
		this.bus = bus;
	}

	/**
	 * Vor der Aktion des Ticks.
	 *
	 * @param actionRunning ob gerade eine Aktion läuft
	 * @return {@code true}, solange ein Reflex die Hände braucht; die Aktion setzt dann diesen Tick aus
	 */
	public boolean before(ServerPlayer bot, long tick, boolean actionRunning) {
		return before(bot, tick, actionRunning, actionRunning);
	}

	/** @param selfDefenseBlocked ob eine Aktion läuft, die Notwehr nicht erlaubt (siehe Action#allowsSelfDefense) */
	public boolean before(ServerPlayer bot, long tick, boolean actionRunning, boolean selfDefenseBlocked) {
		if (bot.isDeadOrDying()) {
			eater = null;
			Combat.setShieldWanted(false);
			return false;
		}
		SelfDefense.tick(bot, tick, bus, selfDefenseBlocked);
		// Brennend nicht im Feuer stehen bleiben: Drachenlauf 41 brannte am Lohenspawner 37 s am Stück, weil Feuerbälle
		// Feuer um ihn legten und nach dem Abbruch von farm_spawner niemand es löschte. Ein Schlag löscht es, wie bei Spielern.
		if (bot.isOnFire() && tick % 5 == 0 && !bot.isInLava()) {
			Combat.putOutNearestFire(bot, 2.0);
		}
		boolean drinking = tickFireResistance(bot, tick);
		if (tickLava(bot, tick)) {
			return true;
		}
		if (tickWall(bot)) {
			return true;
		}
		if (breathGuard.tick(bot, tick, bus)) {
			return true;
		}
		// Während des Trinkens keine Aktion: Nether-Lauf 1 wechselte mit jedem use das Item, der Trank wurde nie fertig.
		if (drinking) {
			return true;
		}
		if (tickSurfacing(bot, tick, actionRunning)) {
			return true;
		}
		// Goldener Apfel im Kampf: Regeneration und Absorption wirken sofort. Nether-Lauf 3: Bei 9,5 Leben mit Wither
		// und Witherskeletten daneben floh er, statt einen seiner 8 Äpfel zu essen, und starb.
		if (eater == null && drinker == null && bot.getHealth() <= COMBAT_APPLE_HEALTH && tick >= nextAppleTick
				&& !bot.hasEffect(MobEffects.REGENERATION) && !Combat.hostiles(bot, COMBAT_APPLE_RANGE).isEmpty()) {
			int slot = de.hannes.smartcraft.skill.Inv.findItem(bot, "minecraft:golden_apple");
			if (slot < 0) {
				slot = de.hannes.smartcraft.skill.Inv.findItem(bot, "minecraft:enchanted_golden_apple");
			}
			if (slot >= 0) {
				Eater candidate = new Eater(slot);
				if (candidate.tick(bot) == Eater.State.RUNNING) {
					eater = candidate;
					eatReason = "combat_apple";
					pausedAction = actionRunning;
					PathFollower.release(bot);
					bot.setSprinting(false);
					nextAppleTick = tick + 200;
					return true;
				}
			}
			nextAppleTick = tick + 40;
		}
		boolean emergency = bot.getHealth() <= EMERGENCY_HEALTH;
		boolean topUp = !emergency && bot.getHealth() < bot.getMaxHealth() && bot.getFoodData().getFoodLevel() < 20
				&& !bot.isInWater() && !bot.isUsingItem() && Combat.hostiles(bot, TOP_UP_SAFE_DISTANCE).isEmpty();
		if (eater == null && drinker == null && (emergency || topUp) && tick >= nextEatTick && !bot.isSleeping()) {
			// Giftiges (verdorbenes Fleisch, rohes Huhn, Spinnenauge) nur im Notfall; goldene Äpfel nicht zum Auffüllen.
			int slot = emergency ? Eater.bestFoodSlot(bot, true) : Eater.bestFoodSlot(bot, false, false);
			eatReason = emergency ? "emergency" : "top_up";
			if (slot >= 0) {
				Eater candidate = new Eater(slot);
				// Der erste Tick prüft, ob der Bot überhaupt essen kann (satt geht nicht).
				if (candidate.tick(bot) == Eater.State.RUNNING) {
					eater = candidate;
					pausedAction = actionRunning;
					PathFollower.release(bot);
					bot.setSprinting(false);
				} else {
					nextEatTick = tick + (emergency ? EAT_COOLDOWN_TICKS : TOP_UP_COOLDOWN_TICKS);
				}
			} else {
				nextEatTick = tick + TOP_UP_COOLDOWN_TICKS;
			}
			return eater != null;
		}
		if (eater == null) {
			return false;
		}
		pausedAction |= actionRunning;
		Eater.State state = eater.tick(bot);
		if (state == Eater.State.RUNNING) {
			return true;
		}
		JsonObject data = new JsonObject();
		data.addProperty("item", eater.itemId());
		data.addProperty("reason", eatReason);
		data.addProperty("eaten", state == Eater.State.DONE);
		data.addProperty("health", GameJson.round1(bot.getHealth()));
		data.addProperty("food", bot.getFoodData().getFoodLevel());
		data.addProperty("paused_action", pausedAction);
		bus.emit("reflex_ate", tick, Priority.HIGH, data);
		eater = null;
		pausedAction = false;
		nextEatTick = tick + EAT_COOLDOWN_TICKS;
		return false;
	}

	/**
	 * Luft holen: Ist unter Wasser die halbe Luft verbraucht, schwimmt der Bot zum nächsten Ort mit
	 * Luft, bis er wieder atmet. Die laufende Aktion wartet so lange.
	 */
	private boolean tickSurfacing(ServerPlayer bot, long tick, boolean actionRunning) {
		boolean submerged = bot.isEyeInFluid(FluidTags.WATER);
		if (!surfacing) {
			if (!submerged || bot.getAirSupply() > LOW_AIR_TICKS || tick < nextSurfacingTick) {
				return false;
			}
			surfacing = true;
			airSeeker = new AirSeeker();
			surfacingSince = tick;
			surfacingPaused = actionRunning;
		}
		if (submerged && tick - surfacingSince < MAX_SURFACING_TICKS) {
			airSeeker.tick(bot);
			return true;
		}
		surfacing = false;
		PathFollower.release(bot);
		// Nach einem vergeblichen Versuch kurz die Aktion weiterlaufen lassen: vielleicht gräbt sie sich frei.
		nextSurfacingTick = submerged ? tick + RETRY_SURFACING_TICKS : 0;
		JsonObject data = new JsonObject();
		data.addProperty("surfaced", !submerged);
		data.addProperty("air", Math.max(0, bot.getAirSupply()));
		data.addProperty("health", GameJson.round1(bot.getHealth()));
		data.addProperty("paused_action", surfacingPaused);
		bus.emit("reflex_surfaced", tick, Priority.HIGH, data);
		return false;
	}

	/**
	 * Trank der Feuerresistenz: in Lava sofort, brennend ab 12 Leben. Läuft neben dem Lava-Reflex her (trinken und
	 * zum Ufer laufen zugleich). Drachenlauf 2: Ein Ghast warf den Bot von einer Brücke in einen Lavasee ohne Ufer.
	 */
	private boolean tickFireResistance(ServerPlayer bot, long tick) {
		if (drinker == null) {
			boolean need = (bot.isInLava() || bot.isOnFire() && bot.getHealth() <= 12)
					&& !bot.hasEffect(MobEffects.FIRE_RESISTANCE) && eater == null;
			int slot = need ? Eater.fireResistanceSlot(bot) : -1;
			if (slot < 0) {
				return false;
			}
			drinker = new Eater(slot, true);
		}
		if (drinker.tick(bot) == Eater.State.RUNNING) {
			return true;
		}
		JsonObject data = new JsonObject();
		data.addProperty("item", drinker.itemId());
		data.addProperty("reason", "fire_resistance");
		data.addProperty("eaten", drinker.failure() == null);
		data.addProperty("health", GameJson.round1(bot.getHealth()));
		data.addProperty("food", bot.getFoodData().getFoodLevel());
		data.addProperty("paused_action", false);
		bus.emit("reflex_ate", tick, Priority.HIGH, data);
		drinker = null;
		return false;
	}

	/**
	 * Raus aus der Lava: Steht der Bot darin, steuert der Reflex zum nächsten sicheren Feld, die Aktion wartet.
	 * Nach dem Herauskommen meldet er {@code reflex_left_lava}.
	 */
	private boolean tickLava(ServerPlayer bot, long tick) {
		// Bis er auf festem Boden außerhalb der Lava steht: Mitten im Sprung ist er kurz nicht „in Lava“ und fiele
		// ohne den Reflex zurück. Höchstens 10 Sekunden am Stück.
		boolean escaping = lavaEscape != null && tick - lavaSince < 200
				&& !(bot.onGround() && !LavaEscape.inHazard(bot));
		if (LavaEscape.inHazard(bot) || escaping) {
			if (lavaEscape == null) {
				lavaEscape = new LavaEscape();
				lavaSince = tick;
			}
			lavaEscape.tick(bot);
			// Nicht ewig sperren: Nach 15 s ohne Ausweg (mit Feuerresistenz) bekommt die KI die Lage gemeldet und die
			// Aktion läuft wieder (Nether-Lauf 26: 7 Minuten keine Antwort, weil der Reflex jede Aktion aussetzte).
			if (tick - lavaSince > LAVA_STUCK_TICKS && bot.hasEffect(MobEffects.FIRE_RESISTANCE)) {
				if (!lavaStuckReported) {
					lavaStuckReported = true;
					JsonObject data = new JsonObject();
					data.addProperty("ticks_in_lava", tick - lavaSince);
					BlockPos shore = lavaEscape.shore();
					if (shore != null) {
						data.add("nearest_shore", GameJson.pos(shore));
					}
					data.addProperty("hint", "no way out found; throw an ender_pearl toward nearest_shore (use with look_at) or keep swimming there");
					bus.emit("reflex_lava_stuck", tick, Priority.HIGH, data);
				}
				return false;
			}
			return true;
		}
		lavaStuckReported = false;
		if (lavaEscape != null) {
			PathFollower.release(bot);
			JsonObject data = new JsonObject();
			data.addProperty("ticks_in_lava", tick - lavaSince);
			data.addProperty("health", GameJson.round1(bot.getHealth()));
			data.addProperty("on_fire", bot.isOnFire());
			if (lavaEscape.pearlsThrown() > 0) {
				data.addProperty("pearls_thrown", lavaEscape.pearlsThrown());
			}
			if (lavaEscape.placedBlocks() > 0) {
				data.addProperty("blocks_placed", lavaEscape.placedBlocks());
			}
			bus.emit("reflex_left_lava", tick, Priority.HIGH, data);
			lavaEscape = null;
		}
		return false;
	}

	/**
	 * Steckt der Bot in einem Block, baut er ihn ab (erst am Kopf, dann an den Füßen). Nether-Lauf 34: Eine Enderperle
	 * setzte ihn in Basalt ab, er erstickte in 10 Sekunden, weil kein Befehl dazwischen kam.
	 */
	private boolean tickWall(ServerPlayer bot) {
		long tick = bot.level().getGameTime();
		if (wallBreaker == null) {
			if (!bot.isInWall()) {
				return false;
			}
			BlockPos eyes = BlockPos.containing(bot.getEyePosition());
			BlockPos feet = bot.blockPosition();
			BlockPos target = solid(bot, eyes) ? eyes : solid(bot, feet) ? feet : null;
			if (target == null) {
				return false;
			}
			PathFollower.release(bot);
			wallBreaker = new de.hannes.smartcraft.skill.BlockBreaker(target, 0);
		}
		BlockPos pos = wallBreaker.pos();
		de.hannes.smartcraft.skill.BlockBreaker.State state = wallBreaker.tick(bot);
		if (state == de.hannes.smartcraft.skill.BlockBreaker.State.RUNNING) {
			return true;
		}
		JsonObject data = new JsonObject();
		data.addProperty("ok", state == de.hannes.smartcraft.skill.BlockBreaker.State.DONE);
		data.addProperty("block", wallBreaker.block() == null ? "unknown"
				: net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(wallBreaker.block().getBlock()).toString());
		data.add("pos", GameJson.pos(pos));
		if (wallBreaker.failure() != null) {
			data.addProperty("reason", wallBreaker.failure());
		}
		data.addProperty("health", GameJson.round1(bot.getHealth()));
		bus.emit("reflex_dug_out", tick, Priority.HIGH, data);
		wallBreaker = null;
		// Bei Erfolg gleich prüfen, ob noch ein zweiter Block drückt; sonst darf die Aktion wieder ran.
		return false;
	}

	private static boolean solid(ServerPlayer bot, BlockPos pos) {
		net.minecraft.world.level.block.state.BlockState state = bot.level().getBlockState(pos);
		return !state.isAir() && state.isSuffocating(bot.level(), pos);
	}

	/** Gegner, gegen die sich der Schild lohnt: ein Schütze bis 16 Blöcke oder ein anderer Feind bis 6 Blöcke. */
	private static boolean shieldThreat(ServerPlayer bot) {
		// Nur bei echter Gefahr: Der Gegner hat den Bot im Visier und freie Sicht. Hannes' Lauf 28.10.: Ein Skelett
		// hinter Festungswänden hielt den Schild oben, der Bot lief im Schleichtempo, ohne dass ein Pfeil kommen konnte.
		for (net.minecraft.world.entity.LivingEntity enemy : Combat.hostiles(bot, SHIELD_RANGED_RANGE)) {
			boolean ranged = enemy instanceof net.minecraft.world.entity.monster.RangedAttackMob
					|| enemy instanceof net.minecraft.world.entity.monster.Blaze
					|| enemy instanceof net.minecraft.world.entity.monster.Ghast;
			boolean aiming = !(enemy instanceof net.minecraft.world.entity.Mob mob) || mob.getTarget() == bot;
			if ((ranged || enemy.distanceTo(bot) <= SHIELD_MELEE_RANGE) && aiming && bot.hasLineOfSight(enemy)) {
				return true;
			}
		}
		// Ein Geschoss, das auf ihn zufliegt, auch wenn der Schütze nicht zu sehen ist.
		for (net.minecraft.world.entity.projectile.Projectile shot : bot.level().getEntitiesOfClass(
				net.minecraft.world.entity.projectile.Projectile.class, bot.getBoundingBox().inflate(10),
				p -> p.isAlive() && p.getOwner() != bot && !p.onGround())) {
			Vec3 toBot = bot.getEyePosition().subtract(shot.position());
			if (shot.getDeltaMovement().lengthSqr() > 0.04 && shot.getDeltaMovement().normalize().dot(toBot.normalize()) > 0.9) {
				return true;
			}
		}
		return false;
	}

	/** Ob der Bot sprinten darf (Befehl {@code sprint}, Standard an). Wunsch von Hannes: Die KI soll das entscheiden. */
	public static volatile boolean sprintAllowed = true;
	private long lastSlowReport = -1000;

	/** Nach der Aktion des Ticks, bevor der Bot selbst tickt. */
	public void after(ServerPlayer bot, long tick) {
		if (bot.isDeadOrDying()) {
			return;
		}
		if (Combat.shieldWanted()) {
			if (!Combat.hasShieldReady(bot)) {
				Combat.setShieldWanted(false);
			} else if (shieldThreat(bot)) {
				Combat.raiseShield(bot);
			} else if (bot.isUsingItem() && bot.getUsedItemHand() == net.minecraft.world.InteractionHand.OFF_HAND) {
				// Kein Gegner nah: Schild senken, mit gehobenem Schild läuft der Bot nur im Schleichtempo.
				Combat.lowerShield(bot);
			}
		} else if (bot.isUsingItem() && bot.getUsedItemHand() == net.minecraft.world.InteractionHand.OFF_HAND
				&& bot.getOffhandItem().getItem() instanceof net.minecraft.world.item.ShieldItem && !shieldThreat(bot)) {
			// Schild oben, obwohl ihn niemand will: senken. Hannes' Lauf (alina, 03.11.): auf dem Weg zum Endportal lief der
			// Bot im Schleichtempo.
			Combat.lowerShield(bot);
		}
		// Geduckt ohne Grund: Jede Aktion, die duckt, setzt das im selben Aufruf zurück. Steht es hier noch, hängt es fest.
		// Hannes' Lauf (alina, 03.11.): „er schleicht allgemein“; Ursache unklar, daher Messung im Log (slow_state).
		if (bot.isShiftKeyDown() || bot.getPose() == net.minecraft.world.entity.Pose.CROUCHING
				|| (bot.isUsingItem() && !Combat.shieldWanted() && tick % 100 == 0)) {
			if (tick - lastSlowReport > 100) {
				lastSlowReport = tick;
				JsonObject data = new JsonObject();
				data.addProperty("shift", bot.isShiftKeyDown());
				data.addProperty("pose", bot.getPose().name());
				data.addProperty("using_item", bot.isUsingItem() ? bot.getUseItem().getItem().toString() : "none");
				data.addProperty("speed", bot.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED));
				bus.emit("slow_state", tick, Priority.NORMAL, data);
			}
			bot.setShiftKeyDown(false);
		}
		// Enderauge „benutzt“ ist nie gewollt (Werfen ist ein Klick): loslassen, sonst Schleichtempo.
		if (bot.isUsingItem() && bot.getUseItem().is(net.minecraft.world.item.Items.ENDER_EYE)) {
			bot.stopUsingItem();
		}
		if (!sprintAllowed) {
			// Die KI hat Sprinten ausgeschaltet (Befehl sprint).
			bot.setSprinting(false);
		}
		if (bot.isUsingItem()) {
			// Beim echten Spieler bremst der Client; der Bot hat keinen, also hier.
			bot.zza *= USING_ITEM_SLOWDOWN;
			bot.xxa *= USING_ITEM_SLOWDOWN;
			bot.setSprinting(false);
		}
		FireballGuard.tick(bot, tick, bus);
		JunkDropper.tick(bot, tick, bus);
		gazeGuard.apply(bot, tick);
		WaterGuard.tick(bot, tick, bus);
	}
}
