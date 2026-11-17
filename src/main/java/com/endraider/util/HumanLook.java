package com.endraider.util;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Smooth, slightly noisy client-side camera movement so the bot does not snap its view.
 * Every tick the view eases towards a target with a random speed factor, a speed cap and small jitter.
 */
public class HumanLook {
    private final Random random = new Random();
    private float targetYaw, targetPitch;
    private int idleTimer;

    public void lookAt(Vec3 pos) {
        Vec3 eyes = mc.player.getEyePosition();
        double dx = pos.x - eyes.x, dy = pos.y - eyes.y, dz = pos.z - eyes.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        targetYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        targetPitch = (float) -Math.toDegrees(Math.atan2(dy, h));
    }

    /** Slowly wanders around horizontally, roughly at eye level (makes endermen notice you). */
    public void idle() {
        if (--idleTimer <= 0) {
            idleTimer = 40 + random.nextInt(80);
            float turn = 35f + random.nextFloat() * 90f;
            targetYaw = mc.player.getYRot() + (random.nextBoolean() ? turn : -turn);
            targetPitch = -12f + random.nextFloat() * 18f;
        }
    }

    /** Applies one tick of easing. */
    public void tick(float maxSpeed) {
        float yaw = mc.player.getYRot(), pitch = mc.player.getXRot();
        float dYaw = Mth.wrapDegrees(targetYaw - yaw);
        float dPitch = targetPitch - pitch;

        float ease = 0.18f + random.nextFloat() * 0.17f;
        float stepYaw = Mth.clamp(dYaw * ease, -maxSpeed, maxSpeed);
        float stepPitch = Mth.clamp(dPitch * ease, -maxSpeed * 0.6f, maxSpeed * 0.6f);

        // tiny hand tremor while moving
        if (Math.abs(dYaw) > 2f) stepYaw += (random.nextFloat() - 0.5f) * 0.6f;
        if (Math.abs(dPitch) > 2f) stepPitch += (random.nextFloat() - 0.5f) * 0.4f;

        mc.player.setYRot(yaw + stepYaw);
        mc.player.setXRot(Mth.clamp(pitch + stepPitch, -90f, 90f));
    }

    /** Angle between current view and target, degrees. */
    public float error() {
        return Math.abs(Mth.wrapDegrees(targetYaw - mc.player.getYRot())) + Math.abs(targetPitch - mc.player.getXRot());
    }
}
