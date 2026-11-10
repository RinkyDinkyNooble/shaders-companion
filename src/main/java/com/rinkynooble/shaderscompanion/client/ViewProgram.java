package com.rinkynooble.shaderscompanion.client;

import com.mojang.blaze3d.platform.GlStateManager;
import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;

/**
 * One of the mod's shader programs (assets/shaderscompanion/shaders/&lt;name&gt;.vsh and .fsh), with the uniforms every
 * view sets: its projection, depth scale, cut plane and camera offset.
 */
final class ViewProgram {
    /** The fragment outputs, in the order of the framebuffers' colour attachments. */
    static final String[] OUTPUTS = {"outAlbedo", "outData", "outDistance", "outPreview"};

    final int id;
    private final int viewProjection;
    private final int logDepth;
    private final int near;
    private final int clipPlane;
    private final int camera;

    /** Compiles and links the program, with its vertex attributes at the given locations. */
    ViewProgram(String name, String[] attributes, int[] locations) {
        int vertex = compile(GL20C.GL_VERTEX_SHADER, name + ".vsh");
        int fragment = compile(GL20C.GL_FRAGMENT_SHADER, name + ".fsh");
        int program = GL20C.glCreateProgram();
        GL20C.glAttachShader(program, vertex);
        GL20C.glAttachShader(program, fragment);
        for (int i = 0; i < attributes.length; i++) {
            GL20C.glBindAttribLocation(program, locations[i], attributes[i]);
        }
        for (int i = 0; i < OUTPUTS.length; i++) {
            GL30C.glBindFragDataLocation(program, i, OUTPUTS[i]);
        }
        GL20C.glLinkProgram(program);
        GL20C.glDetachShader(program, vertex);
        GL20C.glDetachShader(program, fragment);
        GL20C.glDeleteShader(vertex);
        GL20C.glDeleteShader(fragment);
        if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) == 0) {
            String log = GL20C.glGetProgramInfoLog(program);
            GL20C.glDeleteProgram(program);
            throw new IllegalStateException(name + " program failed to link: " + log);
        }
        this.id = program;
        this.viewProjection = uniform("u_ViewProjection");
        this.logDepth = uniform("u_LogDepth");
        this.near = uniform("u_Near");
        this.clipPlane = uniform("u_ClipPlane");
        this.camera = uniform("u_Camera");
    }

    /** A uniform's location, -1 when the program doesn't use it. */
    int uniform(String name) {
        return GL20C.glGetUniformLocation(this.id, name);
    }

    /**
     * Binds the program for a view.
     *
     * @param matrix    the view's projection times its view matrix
     * @param depth     1 / log2(far / near)
     * @param nearPlane the near plane's distance
     * @param plane     the view's cut plane: drawn where dot(xyz, position) + w is 0 or more
     * @param offset    the camera relative to the view's origin
     */
    void use(FloatBuffer matrix, float depth, float nearPlane, float[] plane, float[] offset) {
        GlStateManager._glUseProgram(this.id);
        GL20C.glUniformMatrix4fv(this.viewProjection, false, matrix);
        GL20C.glUniform1f(this.logDepth, depth);
        GL20C.glUniform1f(this.near, nearPlane);
        GL20C.glUniform4f(this.clipPlane, plane[0], plane[1], plane[2], plane[3]);
        GL20C.glUniform3f(this.camera, offset[0], offset[1], offset[2]);
    }

    private static int compile(int type, String name) {
        String source;
        try (InputStream in = ViewProgram.class.getResourceAsStream("/assets/shaderscompanion/shaders/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing shader " + name);
            }
            source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("can't read shader " + name, e);
        }
        int id = GL20C.glCreateShader(type);
        GL20C.glShaderSource(id, source);
        GL20C.glCompileShader(id);
        if (GL20C.glGetShaderi(id, GL20C.GL_COMPILE_STATUS) == 0) {
            String log = GL20C.glGetShaderInfoLog(id);
            GL20C.glDeleteShader(id);
            throw new IllegalStateException(name + " failed to compile: " + log);
        }
        return id;
    }
}
