package com.endraider.modules;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalXZ;
import com.endraider.EndRaiderAddon;
import com.endraider.util.HumanLook;
import com.endraider.util.IslandScanner;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.pathing.BaritoneUtils;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.SwingMode;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.meteorclient.utils.world.Dimension;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public class EndRaider extends Module {
    private enum State {
        PLAN, TRAVEL, TO_EDGE, BRIDGE, COLLECT,
        FARM_PILLAR, FARM_RING, FARM_DESCEND, FARM_CLEAR, FARM_CENTER, FARMING,
        BOXING, DONE
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgBlocks = settings.createGroup("Blocks");
    private final SettingGroup sgFarm = settings.createGroup("Enderman Farm");
    private final SettingGroup sgLook = settings.createGroup("Look");

    private final Setting<Integer> scanRadius = sgGeneral.add(new IntSetting.Builder()
        .name("scan-radius").description("Chunk radius scanned for islands / cities.")
        .defaultValue(8).range(2, 32).sliderRange(2, 16).build());

    private final Setting<Integer> maxGap = sgGeneral.add(new IntSetting.Builder()
        .name("max-bridge-gap").description("Longest void gap (blocks) to bridge towards a visible island.")
        .defaultValue(60).range(4, 256).sliderRange(8, 128).build());

    private final Setting<Integer> bridgeStep = sgGeneral.add(new IntSetting.Builder()
        .name("bridge-step").description("How far to bridge straight when no island is visible ahead.")
        .defaultValue(40).range(8, 128).build());

    private final Setting<Double> placePenalty = sgGeneral.add(new DoubleSetting.Builder()
        .name("place-penalty").description("Baritone block placement penalty. Higher = walk around instead of bridging.")
        .defaultValue(40).range(1, 200).sliderRange(1, 100).build());

    private final Setting<Boolean> goToCities = sgGeneral.add(new BoolSetting.Builder()
        .name("go-to-cities").description("Head to purpur blocks (end cities) when seen.")
        .defaultValue(true).build());

    private final Setting<List<Item>> throwaway = sgBlocks.add(new ItemListSetting.Builder()
        .name("junk-blocks").description("Blocks Baritone may place.")
        .defaultValue(Items.END_STONE, Items.NETHERRACK, Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.DIRT)
        .filter(i -> i instanceof BlockItem).build());

    private final Setting<Integer> minBlocks = sgBlocks.add(new IntSetting.Builder()
        .name("min-blocks").description("Below this many junk blocks, mine end stone first.")
        .defaultValue(64).range(0, 2304).sliderRange(0, 512).build());

    private final Setting<Integer> collectTo = sgBlocks.add(new IntSetting.Builder()
        .name("collect-to").description("Mine end stone until you have this many junk blocks.")
        .defaultValue(256).range(16, 2304).sliderRange(16, 1024).build());

    private final Setting<Integer> lowDurability = sgFarm.add(new IntSetting.Builder()
        .name("low-durability-%").description("Build the enderman farm when pickaxe durability drops to this %.")
        .defaultValue(10).range(1, 90).build());

    private final Setting<Integer> resumeDurability = sgFarm.add(new IntSetting.Builder()
        .name("resume-durability-%").description("Continue raiding when the pickaxe is repaired to this %.")
        .defaultValue(90).range(10, 100).build());

    private final Setting<Double> reach = sgFarm.add(new DoubleSetting.Builder()
        .name("attack-range").description("Enderman attack range.")
        .defaultValue(3.6).range(2, 4.5).build());

    private final Setting<Double> lookSpeed = sgLook.add(new DoubleSetting.Builder()
        .name("max-turn-speed").description("Max degrees per tick when turning.")
        .defaultValue(14).range(3, 60).build());

    private final HumanLook look = new HumanLook();
    private final IslandScanner scanner = new IslandScanner();

    private State state;
    private double hx, hz;
    private BlockPos farmCenter;
    private Goal goal;
    private int timer, stuckTicks, failCount, pickaxeSlotBeforeFarm = -1;
    private double lastProgress;
    private final List<BlockPos> placeQueue = new ArrayList<>();
    private boolean announcedCity;

    private static long confirmUntil;

    // saved baritone settings
    private boolean sAllowPlace, sAllowBreak, sAllowInventory, sAllowParkour;
    private double sPlacePenalty;
    private List<Item> sThrowaway;

    public EndRaider() {
        super(EndRaiderAddon.CATEGORY, "end-raider", "Raids the End with Baritone: bridges island to island, repairs its pickaxe on endermen and boxes in when it finds elytra.");
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void onActivate() {
        if (!BaritoneUtils.IS_AVAILABLE) {
            error("Baritone is not installed.");
            toggle();
            return;
        }
        if (mc.player == null || PlayerUtils.getDimension() != Dimension.End) {
            error("You must be in the End (on the outer islands).");
            toggle();
            return;
        }
        if (!hasMendingPickaxe()) {
            if (System.currentTimeMillis() > confirmUntil) {
                warning("Your pickaxe has no Mending! You probably won't make it far.");
                warning("Enable End Raider again within 20 seconds to continue anyway.");
                confirmUntil = System.currentTimeMillis() + 20_000;
                toggle();
                return;
            }
            warning("Continuing without Mending.");
        }
        confirmUntil = 0;

        // heading: straight away from the main island
        double x = mc.player.getX(), z = mc.player.getZ();
        double len = Math.sqrt(x * x + z * z);
        if (len < 100) {
            float yaw = (float) Math.toRadians(mc.player.getYRot());
            hx = -Math.sin(yaw);
            hz = Math.cos(yaw);
        } else {
            hx = x / len;
            hz = z / len;
        }

        saveBaritone();
        applyBaritone();
        state = State.PLAN;
        timer = 0;
        failCount = 0;
        announcedCity = false;
        info("Started. Heading %.0f°, junk blocks: %d.", Math.toDegrees(Math.atan2(-hx, hz)), junkCount());
    }

    @Override
    public void onDeactivate() {
        if (!BaritoneUtils.IS_AVAILABLE) return;
        baritone().getPathingBehavior().cancelEverything();
        if (sThrowaway != null) restoreBaritone();
        restorePickaxe();
    }

    @EventHandler
    private void onLeave(GameLeftEvent event) {
        if (isActive()) toggle();
    }

    // ---------------------------------------------------------------- main loop

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;
        if (PlayerUtils.getDimension() != Dimension.End) {
            warning("Left the End, stopping.");
            toggle();
            return;
        }

        if (state != State.BOXING && state != State.DONE) {
            Entity frame = findElytraFrame();
            if (frame != null) {
                baritone().getPathingBehavior().cancelEverything();
                info("§aFOUND ELYTRA at %d %d %d! Boxing in.", frame.blockPosition().getX(), frame.blockPosition().getY(), frame.blockPosition().getZ());
                queueBox();
                state = State.BOXING;
                return;
            }
        }

        // pickaxe is about to break -> farm endermen to repair it
        if (state.ordinal() < State.FARM_PILLAR.ordinal() && pickaxePercent() <= lowDurability.get()) {
            if (!mc.player.onGround()) return;
            if (junkCount() < 12) {
                warning("Pickaxe is low but there are not enough blocks for the farm (need 12).");
            } else {
                baritone().getPathingBehavior().cancelEverything();
                info("Pickaxe at %d%%, building an enderman farm.", pickaxePercent());
                farmCenter = mc.player.blockPosition();
                setGoal(new GoalBlock(farmCenter.above(3)));
                state = State.FARM_PILLAR;
                timer = 0;
                return;
            }
        }

        timer++;
        switch (state) {
            case PLAN -> plan();
            case TRAVEL, TO_EDGE, BRIDGE -> travel();
            case COLLECT -> collect();
            case FARM_PILLAR -> farmPillar();
            case FARM_RING -> farmRing();
            case FARM_DESCEND -> farmDescend();
            case FARM_CLEAR -> farmClear();
            case FARM_CENTER -> farmCenter();
            case FARMING -> farming();
            case BOXING -> boxing();
            case DONE -> { }
        }
    }

    // ---------------------------------------------------------------- travel

    private void plan() {
        if (junkCount() < minBlocks.get()) {
            info("Low on blocks (%d), mining end stone.", junkCount());
            baritone().getMineProcess().mine(collectTo.get() - junkCount() + 16, Blocks.END_STONE);
            state = State.COLLECT;
            return;
        }

        scanner.scan(Math.min(scanRadius.get(), mc.options.renderDistance().get()));

        if (goToCities.get() && scanner.cityBlock != null) {
            if (!announcedCity) {
                BlockPos c = scanner.cityBlock;
                info("End city spotted near %d %d %d.", c.getX(), c.getY(), c.getZ());
                announcedCity = true;
            }
            if (!scanner.cityBlock.closerToCenterThan(mc.player.position(), 24)) {
                setGoal(new GoalNear(scanner.cityBlock, 12));
                state = State.TRAVEL;
                return;
            }
        }

        IslandScanner.Plan p = scanner.plan(hx, hz, maxGap.get(), 8);
        if (p == null) {
            // standing on a bridge in the void: keep going straight
            bridgeStraight(mc.player.blockPosition());
            return;
        }
        if (p.bridgeStraight()) {
            if (p.target().closerToCenterThan(mc.player.position(), 4)) bridgeStraight(p.target());
            else {
                setGoal(new GoalBlock(p.target()));
                state = State.TO_EDGE;
            }
        } else {
            setGoal(new GoalBlock(p.target()));
            state = State.TRAVEL;
        }
    }

    private void bridgeStraight(BlockPos from) {
        int x = (int) Math.round(from.getX() + hx * bridgeStep.get());
        int z = (int) Math.round(from.getZ() + hz * bridgeStep.get());
        setGoal(new GoalXZ(x, z));
        state = State.BRIDGE;
    }

    private void travel() {
        double progress = mc.player.getX() * hx + mc.player.getZ() * hz;
        if (Math.abs(progress - lastProgress) > 1 || !baritone().getPathingBehavior().isPathing()) {
            lastProgress = progress;
            stuckTicks = 0;
        } else if (++stuckTicks > 600) {
            warning("Stuck, re-planning.");
            stuckTicks = 0;
            failed();
            return;
        }

        if (baritone().getPathingBehavior().isPathing() || timer < 10) return;

        if (goal != null && goal.isInGoal(mc.player.blockPosition())) {
            failCount = 0;
            if (state == State.TO_EDGE) bridgeStraight(mc.player.blockPosition());
            else state = State.PLAN;
        } else failed();
    }

    private void failed() {
        baritone().getPathingBehavior().cancelEverything();
        if (++failCount > 6) {
            error("Baritone can't find a way, stopping.");
            toggle();
            return;
        }
        // nudge heading a bit and try again
        double a = Math.toRadians((failCount % 2 == 0 ? 1 : -1) * 15 * ((failCount + 1) / 2));
        double nx = hx * Math.cos(a) - hz * Math.sin(a), nz = hx * Math.sin(a) + hz * Math.cos(a);
        if (failCount >= 3) { hx = nx; hz = nz; }
        state = State.PLAN;
    }

    private void collect() {
        if (junkCount() >= collectTo.get() || (timer > 20 && !baritone().getPathingBehavior().isPathing())) {
            baritone().getPathingBehavior().cancelEverything();
            info("Blocks: %d.", junkCount());
            state = State.PLAN;
        }
    }

    // ---------------------------------------------------------------- enderman farm

    // 1) pillar up 3 blocks (stand on placed blocks, no airplace)
    private void farmPillar() {
        if (mc.player.blockPosition().equals(farmCenter.above(3)) && mc.player.onGround()) {
            baritone().getPathingBehavior().cancelEverything();
            placeQueue.clear();
            BlockPos ring = farmCenter.above(2);
            for (Direction d : Direction.Plane.HORIZONTAL) placeQueue.add(ring.relative(d));
            placeQueue.add(ring.offset(1, 0, 1));
            placeQueue.add(ring.offset(1, 0, -1));
            placeQueue.add(ring.offset(-1, 0, 1));
            placeQueue.add(ring.offset(-1, 0, -1));
            state = State.FARM_RING;
            timer = 0;
        } else if (timer > 20 && !baritone().getPathingBehavior().isPathing()) {
            if (timer > 400) { warning("Couldn't pillar up, retrying farm build."); abortFarm(); }
            else setGoal(new GoalBlock(farmCenter.above(3)));
        }
    }

    // 2) ring of 8 blocks around the top of the pillar -> 3x3 roof 2 blocks above the floor
    private void farmRing() {
        if (placeNext()) {
            // 3) walk off the roof (no breaking so Baritone doesn't dig through it)
            BaritoneAPI.getSettings().allowBreak.value = false;
            BaritoneAPI.getSettings().allowPlace.value = false;
            setGoal(new GoalBlock(farmCenter.offset(1, 0, 0)));
            state = State.FARM_DESCEND;
            timer = 0;
        } else if (timer > 200) abortFarm();
    }

    private void farmDescend() {
        if (mc.player.blockPosition().equals(farmCenter.offset(1, 0, 0)) && mc.player.onGround()) {
            baritone().getPathingBehavior().cancelEverything();
            state = State.FARM_CLEAR;
            timer = 0;
        } else if (timer > 20 && !baritone().getPathingBehavior().isPathing()) {
            if (timer > 400) abortFarm();
            else setGoal(new GoalBlock(farmCenter.offset(1, 0, 0)));
        }
    }

    // 4) break the two support blocks under the roof
    private void farmClear() {
        BlockPos[] support = {farmCenter.above(1), farmCenter};
        for (BlockPos p : support) {
            if (!mc.level.getBlockState(p).isAir()) {
                FindItemResult pick = InvUtils.findInHotbar(s -> s.is(ItemTags.PICKAXES));
                if (pick.found()) InvUtils.swap(pick.slot(), false);
                look.lookAt(Vec3.atCenterOf(p));
                look.tick(lookSpeed.get().floatValue());
                BlockUtils.breakBlock(p, true);
                if (timer > 300) abortFarm();
                return;
            }
        }
        BaritoneAPI.getSettings().allowBreak.value = true;
        setGoal(new GoalBlock(farmCenter));
        state = State.FARM_CENTER;
        timer = 0;
    }

    // 5) stand in the middle, pickaxe in offhand (mending), weapon in main hand
    private void farmCenter() {
        if (mc.player.blockPosition().equals(farmCenter)) {
            baritone().getPathingBehavior().cancelEverything();
            moveTools();
            info("Farm ready, waiting for endermen.");
            state = State.FARMING;
            timer = 0;
        } else if (timer > 20 && !baritone().getPathingBehavior().isPathing()) {
            if (timer > 300) abortFarm();
            else setGoal(new GoalBlock(farmCenter));
        }
    }

    private void farming() {
        if (pickaxePercent() >= resumeDurability.get()) {
            restorePickaxe();
            applyBaritone();
            info("Pickaxe repaired (%d%%), continuing the raid.", pickaxePercent());
            state = State.PLAN;
            return;
        }

        Enderman target = null;
        double best = reach.get();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof Enderman em) || !em.isAlive()) continue;
            double d = mc.player.getEyePosition().distanceTo(em.getBoundingBox().getCenter());
            if (d < best + 1) {
                best = d;
                target = em;
            }
        }

        if (target != null) {
            look.lookAt(target.getBoundingBox().getCenter().add(0, -0.3, 0));
            look.tick(lookSpeed.get().floatValue() * 1.5f);
            if (look.error() < 12 && mc.player.getAttackStrengthScale(0.5f) >= 0.95f
                && mc.player.getEyePosition().distanceTo(target.getBoundingBox().getCenter()) <= reach.get()) {
                mc.gameMode.attack(mc.player, target);
                SwingMode.Both.attackSwing(InteractionHand.MAIN_HAND);
            }
        } else {
            look.idle();
            look.tick(lookSpeed.get().floatValue() * 0.5f);
        }
    }

    private void abortFarm() {
        warning("Farm build failed, trying again here.");
        applyBaritone();
        baritone().getPathingBehavior().cancelEverything();
        farmCenter = mc.player.blockPosition();
        setGoal(new GoalBlock(farmCenter.above(3)));
        state = State.FARM_PILLAR;
        timer = 0;
    }

    // ---------------------------------------------------------------- elytra found

    private Entity findElytraFrame() {
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof ItemFrame f && f.getItem().is(Items.ELYTRA)) return e;
        }
        return null;
    }

    private void queueBox() {
        placeQueue.clear();
        BlockPos feet = mc.player.blockPosition();
        placeQueue.add(feet.below());
        for (Direction d : Direction.Plane.HORIZONTAL) placeQueue.add(feet.relative(d));
        for (Direction d : Direction.Plane.HORIZONTAL) placeQueue.add(feet.above().relative(d));
        placeQueue.add(feet.above(2));
        timer = 0;
    }

    private void boxing() {
        if (placeNext() || timer > 200) {
            BlockPos p = mc.player.blockPosition();
            info("§aElytra found! You are boxed in at %d %d %d. Disable End Raider to take over.", p.getX(), p.getY(), p.getZ());
            state = State.DONE;
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Places the next queued block. @return true when the queue is done. */
    private boolean placeNext() {
        placeQueue.removeIf(p -> !mc.level.getBlockState(p).canBeReplaced());
        if (placeQueue.isEmpty()) return true;

        FindItemResult block = InvUtils.findInHotbar(s -> throwaway.get().contains(s.getItem()));
        if (!block.found()) {
            FindItemResult any = InvUtils.find(s -> throwaway.get().contains(s.getItem()));
            if (!any.found()) return true;
            InvUtils.move().from(any.slot()).toHotbar(8);
            return false;
        }
        for (BlockPos p : placeQueue) {
            if (BlockUtils.place(p, block, true, 50)) break;
        }
        return false;
    }

    private void moveTools() {
        // pickaxe to offhand so Mending repairs it from enderman XP
        if (!mc.player.getOffhandItem().is(ItemTags.PICKAXES)) {
            FindItemResult pick = findPickaxe();
            if (pick.found()) {
                pickaxeSlotBeforeFarm = pick.slot();
                InvUtils.move().from(pick.slot()).toOffhand();
            }
        }
        FindItemResult sword = InvUtils.findInHotbar(s -> s.is(ItemTags.SWORDS));
        if (sword.found()) InvUtils.swap(sword.slot(), false);
    }

    private void restorePickaxe() {
        if (pickaxeSlotBeforeFarm >= 0 && mc.player != null && mc.player.getOffhandItem().is(ItemTags.PICKAXES)) {
            InvUtils.move().fromOffhand().to(pickaxeSlotBeforeFarm);
        }
        pickaxeSlotBeforeFarm = -1;
    }

    private FindItemResult findPickaxe() {
        FindItemResult r = InvUtils.find(s -> s.is(ItemTags.PICKAXES) && Utils.hasEnchantment(s, Enchantments.MENDING));
        return r.found() ? r : InvUtils.find(s -> s.is(ItemTags.PICKAXES));
    }

    private ItemStack pickaxeStack() {
        if (mc.player.getOffhandItem().is(ItemTags.PICKAXES)) return mc.player.getOffhandItem();
        FindItemResult r = findPickaxe();
        return r.found() ? mc.player.getInventory().getItem(r.slot()) : ItemStack.EMPTY;
    }

    private boolean hasMendingPickaxe() {
        ItemStack s = pickaxeStack();
        return !s.isEmpty() && Utils.hasEnchantment(s, Enchantments.MENDING);
    }

    private int pickaxePercent() {
        ItemStack s = pickaxeStack();
        if (s.isEmpty() || !s.isDamageableItem()) return 100;
        return (int) Math.round(100.0 * (s.getMaxDamage() - s.getDamageValue()) / s.getMaxDamage());
    }

    private int junkCount() {
        return InvUtils.find(s -> throwaway.get().contains(s.getItem())).count();
    }

    private IBaritone baritone() {
        return BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    private void setGoal(Goal g) {
        goal = g;
        timer = 0;
        stuckTicks = 0;
        baritone().getCustomGoalProcess().setGoalAndPath(g);
    }

    private void saveBaritone() {
        Settings s = BaritoneAPI.getSettings();
        sAllowPlace = s.allowPlace.value;
        sAllowBreak = s.allowBreak.value;
        sAllowInventory = s.allowInventory.value;
        sAllowParkour = s.allowParkour.value;
        sPlacePenalty = s.blockPlacementPenalty.value;
        sThrowaway = new ArrayList<>(s.acceptableThrowawayItems.value);
    }

    private void applyBaritone() {
        Settings s = BaritoneAPI.getSettings();
        s.allowPlace.value = true;
        s.allowBreak.value = true;
        s.allowInventory.value = true;
        s.allowParkour.value = false; // no parkour over the void
        s.blockPlacementPenalty.value = placePenalty.get();
        s.acceptableThrowawayItems.value = new ArrayList<>(throwaway.get());
    }

    private void restoreBaritone() {
        Settings s = BaritoneAPI.getSettings();
        s.allowPlace.value = sAllowPlace;
        s.allowBreak.value = sAllowBreak;
        s.allowInventory.value = sAllowInventory;
        s.allowParkour.value = sAllowParkour;
        s.blockPlacementPenalty.value = sPlacePenalty;
        s.acceptableThrowawayItems.value = sThrowaway;
        sThrowaway = null;
    }

    @Override
    public String getInfoString() {
        return state == null ? null : state.name().toLowerCase();
    }
}
