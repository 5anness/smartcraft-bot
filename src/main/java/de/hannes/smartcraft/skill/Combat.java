package de.hannes.smartcraft.skill;

import net.minecraft.world.entity.player.Player;
import com.google.gson.JsonObject;
import de.hannes.smartcraft.nav.Cell;
import de.hannes.smartcraft.nav.Moves;
import de.hannes.smartcraft.nav.WorldCells;
import de.hannes.smartcraft.perception.GameJson;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.item.ItemStack;

import java.util.Comparator;
import java.util.List;

/**
 * Handgriffe für Kampf und Flucht: Waffe wählen, Feinde erkennen, direkt steuern (ohne Wegsuche)
 * mit Prüfung, ob der nächste Schritt sicher ist, und den Schild heben.
 */
public final class Combat {
	/** Schaden und Tempo der bloßen Hand. */
	private static final double FIST_DAMAGE = 1.0;
	private static final double FIST_SPEED = 4.0;
	/** Erst ab diesem Wert (Schaden mal Schläge pro Sekunde) lohnt ein Item als Waffe; die Hand hat 4. */
	private static final double MIN_WEAPON_VALUE = 4.5;
	/** Unter diesem Hunger kann ein Spieler nicht sprinten. */
	private static final int MIN_FOOD_TO_SPRINT = 7;
	/** Ausweichrichtungen in Grad, wenn der gerade Weg nicht sicher ist. */
	private static final int[] TURNS = {0, 30, -30, 60, -60, 90, -90, 135, -135};

	/** Solange {@code true}, hält der Bot den Schild oben; gesetzt vom Befehl {@code shield}. */
	private static boolean shieldWanted;

	private Combat() {
	}

	// --- Waffe

	/** Schaden mal Schläge pro Sekunde eines Items in der Haupthand. */
	public static double weaponValue(ItemStack stack) {
		double[] values = {FIST_DAMAGE, FIST_SPEED};
		stack.forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
			if (modifier.operation() != AttributeModifier.Operation.ADD_VALUE) {
				return;
			}
			if (attribute.value() == Attributes.ATTACK_DAMAGE.value()) {
				values[0] += modifier.amount();
			} else if (attribute.value() == Attributes.ATTACK_SPEED.value()) {
				values[1] += modifier.amount();
			}
		});
		return values[0] * Math.max(0.1, values[1]);
	}

	/** @return Slot der besten Waffe oder -1, wenn nichts besser ist als die bloße Hand */
	public static int bestWeaponSlot(ServerPlayer bot) {
		int best = -1;
		double bestValue = MIN_WEAPON_VALUE;
		for (int slot = 0; slot < Inv.MAIN_SLOTS; slot++) {
			ItemStack stack = bot.getInventory().getItem(slot);
			if (stack.isEmpty()) {
				continue;
			}
			double value = weaponValue(stack);
			if (value > bestValue) {
				bestValue = value;
				best = slot;
			}
		}
		return best;
	}

	/** Nimmt die beste Waffe in die Hand, falls sie dort nicht schon ist. */
	public static void holdBestWeapon(ServerPlayer bot) {
		int slot = bestWeaponSlot(bot);
		if (slot >= 0 && slot != bot.getInventory().getSelectedSlot()) {
			Inv.hold(bot, slot);
		}
	}

	// --- Feinde

	/** Feindlich ist, was Spieler von sich aus angreift; neutrale Wesen nur, wenn sie den Bot im Visier haben. */
	public static boolean isHostile(LivingEntity entity, ServerPlayer bot) {
		if (!entity.isAlive() || entity == bot) {
			return false;
		}
		// Piglins (nicht Brutes) sind mit Goldrüstung friedlich: nur feindlich, wenn sie den Bot im Visier haben.
		// Drachenlauf 10: farm_spawner erschlug zwei Piglins und riskierte die ganze Gruppe.
		if (entity instanceof NeutralMob || entity instanceof net.minecraft.world.entity.monster.piglin.Piglin) {
			return entity instanceof Mob mob && mob.getTarget() == bot;
		}
		return entity instanceof Enemy;
	}

	/**
	 * Ein Schütze (Skelett, Pillager …) mehr als 3 Blöcke entfernt, während der Bot im Wasser steckt:
	 * Dort ist er langsam und ein leichtes Ziel. Erst raus aus dem Wasser, dann kämpfen.
	 */
	public static boolean rangedWhileInWater(ServerPlayer bot, LivingEntity enemy) {
		return bot.isInWater() && enemy instanceof RangedAttackMob && enemy.distanceTo(bot) > 3;
	}

	/**
	 * Zombie-Piglins und Piglins, die den Bot (noch) nicht angreifen: Ein Schlag hetzt die ganze Gruppe auf ihn.
	 * Perlen-Lauf 10: Im Kampf gegen einen Hoglin traf der Schwungschlag Piglins daneben, die Gruppe erschlug ihn.
	 */
	public static boolean isPeacefulPiglin(LivingEntity enemy, ServerPlayer bot) {
		String type = GameJson.entityType(enemy);
		return (type.equals("minecraft:zombified_piglin") || type.equals("minecraft:piglin"))
				&& !(enemy instanceof Mob mob && mob.getTarget() == bot);
	}

	/** Ein friedlicher Zombie-Piglin in Schwungweite eines Schlags auf {@code target} (großzügig: 3 Blöcke). */
	public static boolean peacefulPiglinNear(LivingEntity target, ServerPlayer bot) {
		return !bot.level().getEntitiesOfClass(LivingEntity.class, target.getBoundingBox().inflate(3.0),
				e -> e != target && isPeacefulPiglin(e, bot)).isEmpty();
	}

	/** Feindliche Mobs im Umkreis, der nächste zuerst. */
	public static List<LivingEntity> hostiles(ServerPlayer bot, double radius) {
		List<LivingEntity> found = bot.level().getEntitiesOfClass(LivingEntity.class, bot.getBoundingBox().inflate(radius),
				entity -> isHostile(entity, bot) && entity.distanceToSqr(bot) <= radius * radius);
		found.sort(Comparator.comparingDouble(bot::distanceToSqr));
		return found;
	}

	/**
	 * Sucht das Ziel eines Befehls: eine Entity-ID oder das nächste lebende Wesen eines Typs. Spieler sind nie Ziel.
	 *
	 * @return das Wesen oder {@code null}
	 */
	public static Entity find(ServerPlayer bot, String selector, double radius) {
		try {
			Entity entity = bot.level().getEntity(Integer.parseInt(selector));
			return entity != null && entity != bot && entity.isAlive() && entity.distanceTo(bot) <= radius * 2 ? entity : null;
		} catch (NumberFormatException e) {
			String type = Inv.fullId(selector);
			return bot.level().getEntitiesOfClass(LivingEntity.class, bot.getBoundingBox().inflate(radius),
							entity -> entity != bot && entity.isAlive() && !(entity instanceof Player)
									&& GameJson.entityType(entity).equals(type))
					.stream().min(Comparator.comparingDouble(bot::distanceToSqr)).orElse(null);
		}
	}

	/** Kurzbeschreibung eines Wesens für Ergebnisse. */
	public static JsonObject describe(Entity entity) {
		JsonObject json = new JsonObject();
		json.addProperty("id", entity.getId());
		json.addProperty("type", GameJson.entityType(entity));
		json.add("pos", GameJson.pos(entity.blockPosition()));
		if (entity instanceof LivingEntity living) {
			json.addProperty("health", GameJson.round1(Math.max(0, living.getHealth())));
		}
		json.addProperty("alive", entity.isAlive());
		return json;
	}

	// --- Steuern ohne Wegsuche

	/** Dreht den Bot zu einem Punkt, auch mit dem Blick nach oben oder unten. */
	public static void face(ServerPlayer bot, double x, double y, double z) {
		double dx = x - bot.getX();
		double dy = y - bot.getEyeY();
		double dz = z - bot.getZ();
		float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0f;
		float pitch = (float) -(Mth.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * Mth.RAD_TO_DEG);
		bot.setYRot(yaw);
		bot.setYHeadRot(yaw);
		bot.setXRot(Mth.clamp(pitch, -89.0f, 89.0f));
	}

	public static void face(ServerPlayer bot, Entity target) {
		face(bot, target.getX(), target.getY() + target.getBbHeight() * 0.6, target.getZ());
	}

	/**
	 * Läuft in eine Richtung, ohne die Blickrichtung zu ändern (vorwärts, seitwärts oder rückwärts
	 * wie mit den Tasten). Sprinten geht nur ungefähr geradeaus und mit genug Hunger.
	 */
	public static void move(ServerPlayer bot, double dirX, double dirZ, boolean sprint) {
		double yaw = bot.getYRot() * Mth.DEG_TO_RAD;
		double sin = Math.sin(yaw);
		double cos = Math.cos(yaw);
		float forward = (float) (-dirX * sin + dirZ * cos);
		float strafe = (float) (dirX * cos + dirZ * sin);
		bot.zza = forward;
		bot.xxa = strafe;
		bot.setSprinting(sprint && forward > 0.8f && bot.getFoodData().getFoodLevel() >= MIN_FOOD_TO_SPRINT && !bot.isUsingItem());
		// An einer Stufe hängen geblieben: springen.
		bot.setJumping(bot.horizontalCollision && bot.onGround() || bot.isInWater());
	}

	/** Wer den Bot in den letzten 2 Sekunden im Nahkampf getroffen hat und bis 6 Blöcke nah ist, sonst {@code null}. */
	public static LivingEntity recentAttacker(ServerPlayer bot) {
		LivingEntity attacker = bot.getLastHurtByMob();
		if (attacker == null || !attacker.isAlive() || bot.tickCount - bot.getLastHurtByMobTimestamp() > 40
				|| attacker.distanceTo(bot) > 6 || attacker instanceof net.minecraft.world.entity.player.Player) {
			return null;
		}
		return attacker;
	}

	/** Löscht das nächste Feuer bis {@code reach} Blöcke vom Kopf, wie ein Spieler mit einem Schlag darauf. */
	public static boolean putOutNearestFire(ServerPlayer bot, double reach) {
		net.minecraft.core.BlockPos fire = nearestFire(bot, reach);
		if (fire == null) {
			return false;
		}
		face(bot, fire.getX() + 0.5, fire.getY() + 0.2, fire.getZ() + 0.5);
		return bot.gameMode.destroyBlock(fire);
	}

	/** Nächstes Feuer bis {@code reach} Blöcke vom Kopf, oder {@code null}. */
	public static net.minecraft.core.BlockPos nearestFire(ServerPlayer bot, double reach) {
		net.minecraft.core.BlockPos feet = bot.blockPosition();
		net.minecraft.world.phys.Vec3 eyes = bot.getEyePosition();
		net.minecraft.core.BlockPos best = null;
		double bestDistance = reach * reach;
		int r = (int) Math.ceil(reach);
		for (net.minecraft.core.BlockPos pos : net.minecraft.core.BlockPos.betweenClosed(feet.offset(-r, -2, -r), feet.offset(r, 2, r))) {
			if (!bot.level().getBlockState(pos).is(net.minecraft.tags.BlockTags.FIRE)) {
				continue;
			}
			double distance = net.minecraft.world.phys.Vec3.atCenterOf(pos).distanceToSqr(eyes);
			if (distance <= bestDistance) {
				bestDistance = distance;
				best = pos.immutable();
			}
		}
		return best;
	}

	public static void halt(ServerPlayer bot) {
		bot.zza = 0;
		bot.xxa = 0;
		bot.setJumping(false);
		bot.setSprinting(false);
	}

	/**
	 * Sucht eine sichere Laufrichtung möglichst nah an der gewünschten: kein Abgrund, keine Lava,
	 * kein Wasser, keine Wand direkt voraus.
	 *
	 * @return {@code [x, z]} als Einheitsvektor oder {@code null}, wenn es ringsum nicht weitergeht
	 */
	public static double[] safeDirection(ServerPlayer bot, WorldCells cells, double dirX, double dirZ) {
		for (int turn : TURNS) {
			double angle = turn * Mth.DEG_TO_RAD;
			double x = dirX * Math.cos(angle) - dirZ * Math.sin(angle);
			double z = dirX * Math.sin(angle) + dirZ * Math.cos(angle);
			if (safeStep(bot, cells, x, z)) {
				return new double[]{x, z};
			}
		}
		return null;
	}

	/** Ob die nächsten anderthalb Blöcke in dieser Richtung begehbar sind. */
	public static boolean safeStep(ServerPlayer bot, WorldCells cells, double dirX, double dirZ) {
		int y = Mth.floor(bot.getY() + 0.2);
		int lastX = bot.getBlockX();
		int lastZ = bot.getBlockZ();
		for (double distance : new double[]{0.7, 1.5}) {
			int x = Mth.floor(bot.getX() + dirX * distance);
			int z = Mth.floor(bot.getZ() + dirZ * distance);
			if (x == lastX && z == lastZ) {
				continue;
			}
			int landing = landingY(cells, x, y, z);
			if (landing == Integer.MIN_VALUE) {
				return false;
			}
			y = landing;
			lastX = x;
			lastZ = z;
		}
		return true;
	}

	/** Auf welcher Höhe der Bot auf diesem Nachbarfeld stünde: gleiche Höhe, eine Stufe hinauf oder bis zu zwei hinunter. */
	private static int landingY(WorldCells cells, int x, int y, int z) {
		if (Moves.kind(cells, x, y, z) == Moves.Kind.GROUND) {
			return y;
		}
		if (Moves.kind(cells, x, y + 1, z) == Moves.Kind.GROUND && cells.at(x, y + 2, z).passable()) {
			return y + 1;
		}
		for (int drop = 1; drop <= 2; drop++) {
			Cell above = cells.at(x, y - drop + 2, z);
			if (!above.passable() || above == Cell.WATER) {
				return Integer.MIN_VALUE;
			}
			if (Moves.kind(cells, x, y - drop, z) == Moves.Kind.GROUND) {
				return y - drop;
			}
		}
		return Integer.MIN_VALUE;
	}

	// --- Schild

	public static boolean isShield(ItemStack stack) {
		return !stack.isEmpty() && stack.has(DataComponents.BLOCKS_ATTACKS);
	}

	public static boolean hasShieldReady(ServerPlayer bot) {
		return isShield(bot.getOffhandItem());
	}

	/** Hebt den Schild in der Zweithand, falls dort einer ist und die Hände frei sind. */
	public static void raiseShield(ServerPlayer bot) {
		if (hasShieldReady(bot) && !bot.isUsingItem()) {
			bot.startUsingItem(InteractionHand.OFF_HAND);
		}
	}

	public static void lowerShield(ServerPlayer bot) {
		if (bot.isUsingItem() && bot.getUsedItemHand() == InteractionHand.OFF_HAND) {
			bot.stopUsingItem();
		}
	}

	public static boolean shieldWanted() {
		return shieldWanted;
	}

	public static void setShieldWanted(boolean wanted) {
		shieldWanted = wanted;
	}
}
