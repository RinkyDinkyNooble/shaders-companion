package com.rinkynooble.shaderscompanion.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.rinkynooble.shaderscompanion.ShadersCompanion;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;

/**
 * GL binds that hold whatever Minecraft's record of the GL state says. GlStateManager skips a bind it believes is
 * already in place (the texture of each unit and the active unit; Oculus adds the program and the framebuffers), so
 * once that record is out of step with GL (another mod's direct GL call, a texture deleted while bound) a bind made
 * through it can silently do nothing: an upload then lands in another texture, or one of this mod's textures stays
 * bound where the game expects its own. Each bind here goes through GlStateManager, which keeps its record right, and
 * then to GL itself.
 */
final class Gl {
    private static Object[] textureRecords;
    private static Field recordedBinding;
    private static boolean recordsLookedUp;
    private static boolean unitMismatchLogged;
    private static boolean textureMismatchLogged;
    /** Times the record and GL disagreed when this mod started drawing (shown in the status). */
    static int mismatches;

    private Gl() {
    }

    /** Binds a 2D texture on the active unit. */
    static void bindTexture(int id) {
        GlStateManager._bindTexture(id);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, id);
    }

    /** Binds a 2D texture if it still exists (a mod may have deleted it since it was read), else none. */
    static void bindTextureIfAlive(int id) {
        bindTexture(id != 0 && GL11C.glIsTexture(id) ? id : 0);
    }

    static void activeTexture(int unit) {
        GlStateManager._activeTexture(unit);
        GL13C.glActiveTexture(unit);
    }

    static void useProgram(int id) {
        GlStateManager._glUseProgram(id);
        GL20C.glUseProgram(id);
    }

    static void bindFramebuffer(int target, int id) {
        GlStateManager._glBindFramebuffer(target, id);
        GL30C.glBindFramebuffer(target, id);
    }

    /**
     * Compares Minecraft's record with GL as this mod starts drawing: the active texture unit, then (with unit 0
     * active) unit 0's texture. A difference is another mod's doing; it is logged once of each kind, since it is the
     * state in which the game itself can draw with the wrong texture.
     */
    static void checkRecord(int activeUnit, int texture0) {
        int recordedUnit = GlStateManager._getActiveTexture();
        if (recordedUnit != activeUnit) {
            mismatches++;
            if (!unitMismatchLogged) {
                unitMismatchLogged = true;
                ShadersCompanion.LOGGER.warn("Shaders Companion: texture unit {} was active while Minecraft's record said unit {}. "
                        + "Another mod changed it with a direct GL call; this mod draws with the real state and leaves both in step.",
                        activeUnit - GL13C.GL_TEXTURE0, recordedUnit - GL13C.GL_TEXTURE0);
            }
        }
        int recorded = recordedTexture0();
        if (recorded >= 0 && recorded != texture0) {
            mismatches++;
            if (!textureMismatchLogged) {
                textureMismatchLogged = true;
                ShadersCompanion.LOGGER.warn("Shaders Companion: texture {} was bound on unit 0 while Minecraft's record said {}. "
                        + "Another mod bound or deleted a texture with a direct GL call; this mod draws with the real state and leaves both in step.",
                        texture0, recorded);
            }
        }
    }

    /** The texture Minecraft's record holds for unit 0; -1 when it holds none, or the record can't be read. */
    private static int recordedTexture0() {
        if (!recordsLookedUp) {
            recordsLookedUp = true;
            try {
                Field textures = GlStateManager.class.getDeclaredField("TEXTURES");
                textures.setAccessible(true);
                Object array = textures.get(null);
                Object[] records = new Object[Array.getLength(array)];
                for (int i = 0; i < records.length; i++) {
                    records[i] = Array.get(array, i);
                }
                recordedBinding = ObfuscationReflectionHelper.findField(records[0].getClass(), "f_84801_");
                textureRecords = records;
            } catch (RuntimeException | ReflectiveOperationException e) {
                textureRecords = null;
            }
        }
        if (textureRecords == null) {
            return -1;
        }
        try {
            return recordedBinding.getInt(textureRecords[0]);
        } catch (IllegalAccessException e) {
            return -1;
        }
    }
}
