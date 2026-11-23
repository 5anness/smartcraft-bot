package de.hannes.aiplayer.bot;

import com.mojang.math.Transformation;
import de.hannes.aiplayer.mixin.DisplayAccessor;
import de.hannes.aiplayer.mixin.TextDisplayAccessor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import org.joml.Vector3f;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.BlankFormat;
import net.minecraft.network.chat.numbers.FixedFormat;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ScoreAccess;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.util.Locale;

/**
 * Leben und Hunger des Bots über seinem Namen ({@code /aiplayer stats on|off}), zwei Zeilen untereinander in einer
 * Vanilla-Textanzeige, die als Mitfahrer auf ihm sitzt: Clients ohne Mod sehen es auch. Wunsch von Hannes.
 */
final class Nameplate {
	private static final String OBJECTIVE = "aiplayer_stats";
	private static final String TAG = "aiplayer_stats";
	private static final int INTERVAL = 10;
	private static final net.minecraft.resources.Identifier GUI_ATLAS = net.minecraft.resources.Identifier.withDefaultNamespace("gui");
	/** Über dem Namensschild: zwei Zeilen (Herzen oben, Hunger darunter). */
	private static final float HEIGHT = 0.45f;

	private boolean enabled;
	private Component lastText;
	private Display.TextDisplay display;

	boolean enabled() {
		return enabled;
	}

	void set(MinecraftServer server, boolean on) {
		enabled = on;
		lastText = null;
		// Alte Anzeige unter dem Namen (Anzeigetafel) aufräumen, falls noch vorhanden.
		Scoreboard scoreboard = server.getScoreboard();
		Objective objective = scoreboard.getObjective(OBJECTIVE);
		if (objective != null) {
			scoreboard.removeObjective(objective);
		}
		if (!on) {
			removeDisplay(server);
		}
	}

	void tick(MinecraftServer server, ServerPlayer bot) {
		if (!enabled || bot == null) {
			if (display != null) {
				removeDisplay(server);
			}
			return;
		}
		if (display == null || display.isRemoved() || display.level() != bot.level()) {
			removeDisplay(server);
			display = create(bot);
			lastText = null;
			if (display == null) {
				return;
			}
		}
		if (display.getVehicle() != bot) {
			// Abgestiegen (Schlafen, Teleport, neuer Körper nach dem Tod): wieder aufsitzen statt neu anlegen, sonst
			// flackerte die Anzeige bei jedem Tick neu; klappt das nicht, wenigstens mitführen.
			if (display.getVehicle() != null) {
				display.stopRiding();
			}
			if (!display.startRiding(bot, true, false)) {
				display.setPos(bot.getX(), bot.getY() + bot.getBbHeight(), bot.getZ());
			}
		}
		if (server.getTickCount() % INTERVAL != 0 && lastText != null) {
			return;
		}
		// 20 Lebenspunkte = 10 Herzen, 20 Hungerpunkte = 10 Keulen, mit den Grafiken der Leisten im Spiel; untereinander
		Component text = bar((int) Math.ceil(bot.getHealth()), "hud/heart/full", "hud/heart/half", "hud/heart/container", "❤", false)
				.append(Component.literal(String.valueOf((char) 10)))
				.append(bar(bot.getFoodData().getFoodLevel(), "hud/food_full", "hud/food_half", "hud/food_empty", "●", true));
		if (text.equals(lastText)) {
			return;
		}
		lastText = text;
		((TextDisplayAccessor) display).aiplayer$setText(text);
	}

	/** Textanzeige über dem Bot, auch für Clients ohne Mod. */
	private static Display.TextDisplay create(ServerPlayer bot) {
		Display.TextDisplay created = net.minecraft.world.entity.EntityTypes.TEXT_DISPLAY.create(bot.level(), EntitySpawnReason.COMMAND);
		if (created == null) {
			return null;
		}
		created.addTag(TAG);
		created.setPos(bot.getX(), bot.getY() + bot.getBbHeight(), bot.getZ());
		((DisplayAccessor) created).aiplayer$setBillboardConstraints(Display.BillboardConstraints.CENTER);
		((DisplayAccessor) created).aiplayer$setTransformation(new Transformation(new Vector3f(0, HEIGHT, 0), null, null, null));
		// Durch Blöcke sichtbar: in 2 Blöcke hohen Gängen steckt die Anzeige in der Decke und war sonst verdeckt.
		created.setFlags((byte) (created.getFlags() | Display.TextDisplay.FLAG_SEE_THROUGH));
		// Volle Helligkeit: in Höhlen und nachts war sie sonst fast schwarz.
		created.setBrightnessOverride(new net.minecraft.util.Brightness(15, 15));
		bot.level().addFreshEntity(created);
		// Als Mitfahrer: Der Client bewegt die Anzeige genau mit dem Bot (vorher je Tick versetzt, beim Springen ruckelte sie
		// hinterher; Hannes 03.11.). Beim Dimensionswechsel fällt sie ab und wird neu angelegt.
		created.startRiding(bot, true, false);
		return created;
	}

	private void removeDisplay(MinecraftServer server) {
		if (display != null) {
			display.discard();
			display = null;
		}
		// Reste aus früheren Sitzungen (gleiche Markierung) entfernen.
		for (ServerLevel level : server.getAllLevels()) {
			for (Entity entity : level.getAllEntities()) {
				if (entity instanceof Display.TextDisplay && entity.entityTags().contains(TAG)) {
					entity.discard();
				}
			}
		}
	}

	/**
	 * Zehn Symbole für 0 bis 20 Punkte als Sprites aus dem GUI-Atlas (wie die Leisten im Spiel). Hunger läuft wie im Spiel
	 * von rechts nach links ({@code rightToLeft}). Ältere Clients sehen den Ersatztext.
	 */
	private static net.minecraft.network.chat.MutableComponent bar(int points, String full, String half, String empty,
			String fallback, boolean rightToLeft) {
		points = Math.max(0, Math.min(20, points));
		net.minecraft.network.chat.MutableComponent result = Component.empty();
		for (int i = 0; i < 10; i++) {
			int slot = rightToLeft ? 9 - i : i;
			int left = points - slot * 2;
			String sprite = left >= 2 ? full : left == 1 ? half : empty;
			result.append(Component.object(new net.minecraft.network.chat.contents.objects.AtlasSprite(GUI_ATLAS,
					net.minecraft.resources.Identifier.withDefaultNamespace(sprite)), Component.literal(fallback)));
		}
		return result;
	}
}
