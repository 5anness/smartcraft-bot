package de.hannes.smartcraft.action;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.hannes.smartcraft.nav.Goal;
import de.hannes.smartcraft.nav.Moves;
import de.hannes.smartcraft.nav.Pos;
import de.hannes.smartcraft.perception.GameJson;
import de.hannes.smartcraft.skill.Combat;
import de.hannes.smartcraft.skill.Inv;
import de.hannes.smartcraft.skill.Menus;
import de.hannes.smartcraft.skill.PathFollower;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * {@code use}: Rechtsklick wie ein Spieler, mit einem Item aus dem Inventar oder leerer Hand.
 * Auf einen Block (Feuerzeug, Tür, Hebel, Knochenmehl, Hacke, Enderauge in den Rahmen …), auf ein
 * Wesen (Kuh melken, Schaf scheren, Tiere füttern, Boot besteigen …) oder in die Luft (Enderperle,
 * Enderauge, Bogen, Trank). Was sich geändert hat, steht im Ergebnis.
 * Schema: {@code schemas/commands/use.schema.json}.
 */
public final class UseAction extends Action {
	private static final double APPROACH_REACH = 3.5;
	private static final double ENTITY_REACH = 2.8;
	private static final int SEARCH_RADIUS = 32;
	/** Bogen und Dreizack: so lange spannen, dann loslassen. */
	private static final int CHARGE_TICKS = 25;
	/** Länger hält der Bot ein Item nicht gedrückt (Fernrohr, Horn …). */
	private static final int MAX_USE_TICKS = 100;
	private static final Set<String> CHARGED = Set.of("minecraft:bow", "minecraft:trident", "minecraft:crossbow");
	private static final Set<String> FIRE_STARTERS = Set.of("minecraft:flint_and_steel", "minecraft:fire_charge");
	private static final int MAX_LISTED = 30;

	private final String itemId;
	private final BlockPos pos;
	private final Direction requestedFace;
	private final String entitySelector;
	private final Vec3 lookAt;
	private final boolean sneak;

	private boolean prepared;
	private Entity entity;
	private PathFollower follower;
	private BlockPos followerAnchor;
	private Map<String, Integer> before;
	private int usingTicks = -1;
	private JsonObject result = new JsonObject();

	public UseAction(String itemId, BlockPos pos, Direction face, String entity, Vec3 lookAt, boolean sneak) {
		this.itemId = itemId == null ? null : Inv.fullId(itemId);
		this.pos = pos;
		this.requestedFace = face;
		this.entitySelector = entity;
		this.lookAt = lookAt;
		this.sneak = sneak;
	}

	@Override
	public int timeoutTicks() {
		return 1200;
	}

	@Override
	public Outcome tick(ServerPlayer bot) {
		if (!prepared) {
			prepared = true;
			if (itemId != null && Inv.findItem(bot, itemId) < 0) {
				return Outcome.failed("missing_item", progress(bot));
			}
			if ("minecraft:lava_bucket".equals(itemId)) {
				JsonObject data = progress(bot);
				data.addProperty("hint", "pour lava with bucket action empty; it checks for a safe spot");
				return Outcome.failed("use_bucket_command", data);
			}
			if (entitySelector != null) {
				entity = findEntity(bot, entitySelector);
				if (entity == null) {
					return Outcome.failed("entity_not_found", progress(bot));
				}
			}
		}

		// Ein Item wird noch benutzt (Bogen spannen, Trank trinken): weiterhalten bis fertig.
		if (usingTicks >= 0) {
			return continueUsing(bot);
		}

		if (pos != null) {
			Goal goal = Goal.withinReach(new Pos(pos.getX(), pos.getY(), pos.getZ()), APPROACH_REACH);
			PathFollower.State walking = walk(bot, goal);
			if (walking == PathFollower.State.FAILED) {
				return outOfReach(bot);
			}
			return walking == PathFollower.State.RUNNING ? null : useOnBlock(bot);
		}
		if (entity != null) {
			if (!entity.isAlive() || entity.isRemoved()) {
				return Outcome.failed("target_lost", progress(bot));
			}
			if (bot.getEyePosition().distanceTo(entity.getBoundingBox().getCenter()) > ENTITY_REACH) {
				BlockPos at = entity.blockPosition();
				if (followerAnchor != null && followerAnchor.distManhattan(at) > 2) {
					follower = null;
				}
				followerAnchor = at;
				PathFollower.State walking = walk(bot, Goal.near(new Pos(at.getX(), at.getY(), at.getZ()), 1.5));
				if (walking == PathFollower.State.FAILED) {
					return outOfReach(bot);
				}
				if (walking == PathFollower.State.RUNNING) {
					return null;
				}
				// Angekommen, aber das Wesen ist weitergelaufen: neu ansetzen.
				follower = null;
				if (bot.getEyePosition().distanceTo(entity.getBoundingBox().getCenter()) > ENTITY_REACH + 1.5) {
					return null;
				}
			}
			return useOnEntity(bot);
		}
		return useInAir(bot);
	}

	/** Läuft hin, bis das Ziel erreicht ist ({@code ARRIVED}). */
	private PathFollower.State walk(ServerPlayer bot, Goal goal) {
		if (goal.distance(bot.getX(), bot.getY(), bot.getZ()) <= 0 && bot.onGround()) {
			PathFollower.release(bot);
			return PathFollower.State.ARRIVED;
		}
		if (follower == null) {
			follower = new PathFollower(goal, Moves.Options.NONE);
		}
		PathFollower.State state = follower.tick(bot);
		if (state == PathFollower.State.ARRIVED) {
			PathFollower.release(bot);
		}
		return state;
	}

	private Outcome outOfReach(ServerPlayer bot) {
		JsonObject data = progress(bot);
		data.addProperty("hint", follower.hint());
		return Outcome.failed("out_of_reach", data);
	}

	private void holdItem(ServerPlayer bot) {
		if (itemId != null) {
			Inv.hold(bot, Inv.findItem(bot, itemId));
			return;
		}
		// Ohne Item mit leerer Hand: sonst setzt ein Klick auf einen gewöhnlichen Block den Block in der Hand.
		Inventory inventory = bot.getInventory();
		for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
			if (inventory.getItem(slot).isEmpty()) {
				inventory.setSelectedSlot(slot);
				return;
			}
		}
	}

	private Outcome useOnBlock(ServerPlayer bot) {
		ServerLevel level = bot.level();
		Direction face = requestedFace != null ? requestedFace : chooseFace(bot);
		BlockPos beside = pos.relative(face);
		if (itemId != null && FIRE_STARTERS.contains(itemId)) {
			BlockPos feet = bot.blockPosition();
			boolean touching = Math.abs(beside.getX() - feet.getX()) <= 1 && Math.abs(beside.getZ() - feet.getZ()) <= 1
					&& beside.getY() >= feet.getY() && beside.getY() <= feet.getY() + 1;
			// Am Portalrahmen steht der Bot zwangsläufig dicht davor; das Feuer wird dort sofort zum Portal.
			boolean portalFrame = level.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.OBSIDIAN);
			if (beside.equals(feet) || beside.equals(feet.above()) || touching && !portalFrame) {
				JsonObject data = progress(bot);
				data.addProperty("hint", "the fire would start where you stand or right next to you; step back or pick another spot");
				return Outcome.failed("unsafe", data);
			}
		}
		String blockBefore = GameJson.blockId(level.getBlockState(pos));
		String besideBefore = GameJson.blockId(level.getBlockState(beside));
		holdItem(bot);
		before = Inv.counts(bot);
		Vec3 hit = Vec3.atCenterOf(pos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
		bot.lookAt(EntityAnchorArgument.Anchor.EYES, hit);
		boolean wasSneaking = bot.isShiftKeyDown();
		bot.setShiftKeyDown(sneak);
		boolean accepted = bot.gameMode.useItemOn(bot, level, bot.getMainHandItem(), InteractionHand.MAIN_HAND,
				new BlockHitResult(hit, face, pos, false)).consumesAction();
		bot.setShiftKeyDown(wasSneaking);
		bot.swing(InteractionHand.MAIN_HAND, true);

		result.addProperty("target", "block");
		result.add("block_pos", GameJson.pos(pos));
		result.addProperty("face", face.getName());
		result.addProperty("block_before", blockBefore);
		result.addProperty("block_after", GameJson.blockId(level.getBlockState(pos)));
		result.add("beside_pos", GameJson.pos(beside));
		result.addProperty("beside_before", besideBefore);
		result.addProperty("beside_after", GameJson.blockId(level.getBlockState(beside)));
		describeMenu(bot);
		boolean changed = !blockBefore.equals(result.get("block_after").getAsString())
				|| !besideBefore.equals(result.get("beside_after").getAsString())
				|| !before.equals(Inv.counts(bot)) || result.has("opened");
		if (bot.isUsingItem()) {
			usingTicks = 0;
			return null;
		}
		return finish(bot, accepted || changed);
	}

	/** Die Seite, auf die geklickt wird: oben, wenn dort frei ist, sonst die zum Bot zeigende freie Seite. */
	private Direction chooseFace(ServerPlayer bot) {
		ServerLevel level = bot.level();
		if (level.getBlockState(pos.above()).canBeReplaced()) {
			return Direction.UP;
		}
		Vec3 eyes = bot.getEyePosition();
		Vec3 center = Vec3.atCenterOf(pos);
		Direction best = Direction.UP;
		double bestScore = Double.NEGATIVE_INFINITY;
		for (Direction direction : Direction.values()) {
			double score = (eyes.x - center.x) * direction.getStepX() + (eyes.y - center.y) * direction.getStepY()
					+ (eyes.z - center.z) * direction.getStepZ();
			if (level.getBlockState(pos.relative(direction)).canBeReplaced()) {
				score += 10;
			}
			if (score > bestScore) {
				bestScore = score;
				best = direction;
			}
		}
		return best;
	}

	private Outcome useOnEntity(ServerPlayer bot) {
		holdItem(bot);
		before = Inv.counts(bot);
		bot.lookAt(EntityAnchorArgument.Anchor.EYES, entity.getBoundingBox().getCenter());
		boolean wasSneaking = bot.isShiftKeyDown();
		bot.setShiftKeyDown(sneak);
		boolean accepted = bot.interactOn(entity, InteractionHand.MAIN_HAND, entity.getBoundingBox().getCenter()).consumesAction();
		bot.setShiftKeyDown(wasSneaking);
		bot.swing(InteractionHand.MAIN_HAND, true);
		result.addProperty("target", "entity");
		result.add("entity", Combat.describe(entity));
		if (bot.getVehicle() != null) {
			result.addProperty("riding", GameJson.entityType(bot.getVehicle()));
		}
		describeMenu(bot);
		boolean changed = !before.equals(Inv.counts(bot)) || bot.getVehicle() == entity || result.has("opened");
		return finish(bot, accepted || changed);
	}

	private Outcome useInAir(ServerPlayer bot) {
		holdItem(bot);
		before = Inv.counts(bot);
		if (lookAt != null) {
			bot.lookAt(EntityAnchorArgument.Anchor.EYES, lookAt);
		}
		ItemStack stack = bot.getMainHandItem();
		if (stack.isEmpty()) {
			return Outcome.failed("nothing_to_use", progress(bot));
		}
		boolean accepted = bot.gameMode.useItem(bot, bot.level(), stack, InteractionHand.MAIN_HAND).consumesAction();
		bot.swing(InteractionHand.MAIN_HAND, true);
		result.addProperty("target", "air");
		if (bot.isUsingItem()) {
			usingTicks = 0;
			return null;
		}
		describeMenu(bot);
		return finish(bot, accepted || !before.equals(Inv.counts(bot)));
	}

	private Outcome continueUsing(ServerPlayer bot) {
		usingTicks++;
		if (!bot.isUsingItem()) {
			return finish(bot, true);
		}
		String using = Inv.itemId(bot.getUseItem());
		if (CHARGED.contains(using) && usingTicks >= CHARGE_TICKS || usingTicks >= MAX_USE_TICKS) {
			if (lookAt != null) {
				bot.lookAt(EntityAnchorArgument.Anchor.EYES, lookAt);
			}
			bot.releaseUsingItem();
			return finish(bot, true);
		}
		return null;
	}

	/** Ein geöffnetes Fenster (Truhe, Fass, Händler …) beschreiben und wieder schließen. */
	private void describeMenu(ServerPlayer bot) {
		AbstractContainerMenu menu = bot.containerMenu;
		if (menu == bot.inventoryMenu) {
			return;
		}
		result.addProperty("opened", menu.getClass().getSimpleName());
		int containerSlots = menu.slots.size() - 36;
		if (containerSlots > 0) {
			Map<String, Integer> counts = new LinkedHashMap<>();
			for (int index = 0; index < containerSlots; index++) {
				ItemStack stack = menu.getSlot(index).getItem();
				if (!stack.isEmpty()) {
					counts.merge(Inv.itemId(stack), stack.getCount(), Integer::sum);
				}
			}
			JsonObject contents = new JsonObject();
			counts.entrySet().stream().limit(MAX_LISTED).forEach(entry -> contents.addProperty(entry.getKey(), entry.getValue()));
			result.add("contents", contents);
			result.addProperty("hint", "move items with store/take and pos");
		}
		Menus.close(bot);
	}

	private Outcome finish(ServerPlayer bot, boolean success) {
		JsonObject data = progress(bot);
		return success ? Outcome.ok(data) : Outcome.failed("nothing_happened", data);
	}

	private static Entity findEntity(ServerPlayer bot, String selector) {
		try {
			Entity found = bot.level().getEntity(Integer.parseInt(selector));
			return found != null && found != bot && found.distanceTo(bot) <= SEARCH_RADIUS ? found : null;
		} catch (NumberFormatException e) {
			String type = Inv.fullId(selector);
			return bot.level().getEntities(bot, bot.getBoundingBox().inflate(SEARCH_RADIUS),
							candidate -> candidate.isAlive() && !(candidate instanceof Player) && GameJson.entityType(candidate).equals(type)
									&& !PursueAction.shorn(candidate))
					.stream().min(Comparator.comparingDouble(bot::distanceToSqr)).orElse(null);
		}
	}

	@Override
	public void release(ServerPlayer bot) {
		PathFollower.release(bot);
		if (bot.isUsingItem()) {
			bot.releaseUsingItem();
		}
		Menus.close(bot);
	}

	@Override
	public JsonObject progress(ServerPlayer bot) {
		JsonObject data = result.deepCopy();
		if (itemId != null) {
			data.addProperty("item", itemId);
		}
		if (before != null) {
			data.add("gained", Inv.gained(before, bot));
			JsonArray used = new JsonArray();
			Map<String, Integer> now = Inv.counts(bot);
			before.forEach((id, count) -> {
				int less = count - now.getOrDefault(id, 0);
				if (less > 0) {
					used.add(GameJson.stack(id, less));
				}
			});
			data.add("used_up", used);
		}
		data.add("held_item", Inv.heldJson(bot));
		return data;
	}
}
