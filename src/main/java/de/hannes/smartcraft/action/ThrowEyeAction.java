package de.hannes.smartcraft.action;

import com.google.gson.JsonObject;
import de.hannes.smartcraft.perception.Compass;
import de.hannes.smartcraft.perception.GameJson;
import de.hannes.smartcraft.skill.Inv;
import de.hannes.smartcraft.skill.PathFollower;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.EyeOfEnder;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * {@code throw_eye}: wirft ein Enderauge und beobachtet seinen Flug. Meldet Richtung, wo es endete,
 * ob es abwärts flog (die Festung liegt dann unter etwa 12 Blöcken Umkreis) und ob es liegen blieb.
 * Aus zwei Würfen von verschiedenen Orten berechnet es den Schnittpunkt: die geschätzte Festung.
 * Schema: {@code schemas/commands/throw_eye.schema.json}.
 */
public final class ThrowEyeAction extends Action {
	/** Ein Auge fliegt 80 Ticks; danach fällt es als Item herunter oder zerbricht. */
	private static final int WATCH_TICKS = 100;
	/** Unterschied der Richtungen, ab dem zwei Würfe sich zum Peilen eignen. */
	private static final double MIN_ANGLE_DEGREES = 4;

	/** Frühere Würfe dieser Sitzung: Startpunkt und Flugrichtung (Einheitsvektor in x/z). */
	private record Throw(double x, double z, double dirX, double dirZ) {
	}

	private static final List<Throw> THROWS = new ArrayList<>();

	/**
	 * Höchstens so viele Augen dürfen bei der Suche verloren gehen (zerbrochen oder liegen gelassen) – Wunsch von Hannes
	 * (04.11.2026): Lauf 31 warf 9 Augen vom selben Strich aus, Lauf 35 ging danach für neue Augen zurück in den Nether.
	 */
	public static final int MAX_LOST = 4;
	/** Größter Augenbestand bei einem Wurf; verloren = dieser Wert minus aktueller Bestand. */
	private static int peakEyes = -1;

	/** Ob in dieser Sitzung schon geworfen wurde: dann verlangt {@code end_missing} keine 18 Augen mehr, nur 12. */
	public static boolean searchStarted() {
		return peakEyes >= 0;
	}

	public static int eyesLost(ServerPlayer bot) {
		return peakEyes < 0 ? 0 : Math.max(0, peakEyes - Inv.count(bot, "minecraft:ender_eye"));
	}

	private final boolean force;
	private boolean thrown;
	private EyeOfEnder eye;
	private Vec3 start;
	private Vec3 last;
	private double lowestY = Double.MAX_VALUE;
	private int watched;

	public ThrowEyeAction(boolean force) {
		this.force = force;
	}

	@Override
	public int timeoutTicks() {
		return WATCH_TICKS + 40;
	}

	@Override
	public Outcome tick(ServerPlayer bot) {
		PathFollower.release(bot);
		if (!thrown) {
			if (bot.level().dimension() != Level.OVERWORLD) {
				return Outcome.failed("wrong_dimension", progress(bot));
			}
			int slot = Inv.findItem(bot, "minecraft:ender_eye");
			if (slot < 0) {
				return Outcome.failed("no_eye", progress(bot));
			}
			peakEyes = Math.max(peakEyes, Inv.count(bot, "minecraft:ender_eye"));
			if (eyesLost(bot) >= MAX_LOST && !force) {
				JsonObject data = progress(bot);
				data.addProperty("eyes_lost", eyesLost(bot));
				data.addProperty("hint", "already " + MAX_LOST + " eyes lost: no more throws; go to estimated_stronghold or the last "
						+ "direction, dig down to y 20-40 and search with search_for [\"end_portal_frame\"] and tunnel (force throws anyway)");
				return Outcome.failed("eye_budget", data);
			}
			Inv.hold(bot, slot);
			// Waagerecht und leicht nach oben schauen; das Auge sucht sich seine Richtung selbst.
			bot.lookAt(EntityAnchorArgument.Anchor.EYES, bot.getEyePosition().add(bot.getLookAngle().x, 0.3, bot.getLookAngle().z));
			bot.gameMode.useItem(bot, bot.level(), bot.getMainHandItem(), InteractionHand.MAIN_HAND);
			// Das Enderauge startet beim Werfen „benutzen“ (wie Rechtsklick halten); ohne Client lässt der Bot nie los und lief
			// danach im Schleichtempo (Hannes' Lauf, alina, 03.11.: slow_state using_item ender_eye).
			if (bot.isUsingItem()) {
				bot.stopUsingItem();
			}
			bot.swing(InteractionHand.MAIN_HAND, true);
			thrown = true;
			eye = bot.level().getEntitiesOfClass(EyeOfEnder.class, bot.getBoundingBox().inflate(3)).stream()
					.min(Comparator.comparingInt(entity -> entity.tickCount)).orElse(null);
			if (eye == null) {
				// Ohne Festung in dieser Welt fliegt kein Auge los.
				return Outcome.failed("no_stronghold", progress(bot));
			}
			start = eye.position();
			last = start;
			return null;
		}
		if (!eye.isRemoved() && ++watched < WATCH_TICKS) {
			last = eye.position();
			lowestY = Math.min(lowestY, last.y);
			return null;
		}
		return Outcome.ok(result(bot));
	}

	private JsonObject result(ServerPlayer bot) {
		JsonObject data = progress(bot);
		double dx = last.x - start.x;
		double dz = last.z - start.z;
		double length = Math.sqrt(dx * dx + dz * dz);
		data.add("from", GameJson.ints((int) Math.floor(start.x), (int) Math.floor(start.y), (int) Math.floor(start.z)));
		data.add("ended_at", GameJson.ints((int) Math.floor(last.x), (int) Math.floor(last.y), (int) Math.floor(last.z)));
		// Fliegt das Auge kaum waagerecht und sinkt, steht der Bot fast über der Festung.
		boolean descending = lowestY < start.y - 1.0 && length < 12;
		data.addProperty("descending", descending);
		if (length > 0.5) {
			float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0f;
			data.addProperty("direction", Compass.facing(yaw));
			data.addProperty("yaw", Math.round(Mth.wrapDegrees(yaw)));
			Throw now = new Throw(start.x, start.z, dx / length, dz / length);
			JsonObject estimate = intersect(now);
			if (estimate != null) {
				data.add("estimated_stronghold", estimate);
			}
			THROWS.add(now);
		}
		boolean dropped = !bot.level().getEntitiesOfClass(ItemEntity.class, eyeArea(),
				item -> item.getItem().is(Items.ENDER_EYE)).isEmpty();
		data.addProperty("eye_dropped", dropped);
		// Ein liegendes Auge zählt noch nicht als verloren: es liegt zum Aufheben bereit.
		data.addProperty("eyes_lost", Math.max(0, eyesLost(bot) - (dropped ? 1 : 0)));
		data.addProperty("eyes_lost_max", MAX_LOST);
		if (dropped) {
			// Das Auge fällt vom Endpunkt in der Luft auf den Boden. Hannes' Lauf (xyz, 03.11.): goto zu ended_at baute einen
			// Turm in die Luft („warum baust du dich hoch zum werfen“). Deshalb die Bodenstelle nennen.
			BlockPos ground = bot.level().getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
					BlockPos.containing(last.x, 0, last.z));
			// Lauf 32 (04.11.): Das Auge fiel in einen See, der Bot tauchte hinterher und ertrank mit allen Augen.
			if (!bot.level().getFluidState(ground.below()).isEmpty()) {
				data.addProperty("eye_in_fluid", true);
				data.addProperty("hint", "the eye fell into water or lava: do NOT go after it, it is lost; walk 200-300 blocks in "
						+ "direction before the next throw");
			} else {
				data.add("pickup_at", GameJson.ints(ground.getX(), ground.getY(), ground.getZ()));
				data.addProperty("hint", "the eye falls to the ground at pickup_at: ALWAYS goto pickup_at (no allow_place) and "
						+ "pickup_items, then walk 200-300 blocks in direction before the next throw");
			}
		} else {
			data.addProperty("hint", "the eye broke; walk 200-300 blocks in direction before the next throw");
		}
		return data;
	}

	private net.minecraft.world.phys.AABB eyeArea() {
		return new net.minecraft.world.phys.AABB(last.x - 3, last.y - 20, last.z - 3, last.x + 3, last.y + 3, last.z + 3);
	}

	/** Schnittpunkt mit dem jüngsten früheren Wurf, dessen Richtung genug abweicht. */
	private static JsonObject intersect(Throw now) {
		for (int i = THROWS.size() - 1; i >= 0; i--) {
			Throw before = THROWS.get(i);
			double cross = before.dirX * now.dirZ - before.dirZ * now.dirX;
			double angle = Math.toDegrees(Math.asin(Math.min(1, Math.abs(cross))));
			if (angle < MIN_ANGLE_DEGREES) {
				continue;
			}
			// before + t * dirBefore = now + s * dirNow
			double px = now.x - before.x;
			double pz = now.z - before.z;
			double t = (px * now.dirZ - pz * now.dirX) / cross;
			double s = (px * before.dirZ - pz * before.dirX) / cross;
			if (t <= 0 || s <= 0) {
				continue;
			}
			JsonObject json = new JsonObject();
			json.addProperty("x", (int) Math.round(before.x + t * before.dirX));
			json.addProperty("z", (int) Math.round(before.z + t * before.dirZ));
			json.addProperty("distance", (int) Math.round(s));
			json.addProperty("angle_between_throws", (int) Math.round(angle));
			return json;
		}
		return null;
	}

	@Override
	public void release(ServerPlayer bot) {
		PathFollower.release(bot);
	}

	@Override
	public JsonObject progress(ServerPlayer bot) {
		JsonObject data = new JsonObject();
		data.addProperty("eyes_left", Inv.count(bot, "minecraft:ender_eye"));
		data.addProperty("throws_known", THROWS.size());
		return data;
	}
}
