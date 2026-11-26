package com.rinkynooble.shaderscompanion.client;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.IntConsumer;
import org.lwjgl.opengl.GL20C;

/** Compiling and linking the mod's own shaders (assets/shaderscompanion/shaders); each failure throws with GL's log. */
public final class Shaders {
    private Shaders() {
    }

    public static int compile(int type, String name) {
        return compile(type, name, "");
    }

    /** Compiles a shader from assets/shaderscompanion/shaders, with defines inserted after its #version line. */
    public static int compile(int type, String name, String defines) {
        String source;
        try (InputStream in = Shaders.class.getResourceAsStream("/assets/shaderscompanion/shaders/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing shader " + name);
            }
            source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("can't read shader " + name, e);
        }
        if (!defines.isEmpty()) {
            int line = source.indexOf('\n') + 1;
            source = source.substring(0, line) + defines + source.substring(line);
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

    /** Links the shaders into a program and deletes them; a program that fails to link is deleted too. */
    public static int link(String name, int... shaders) {
        return link(name, null, shaders);
    }

    /**
     * As {@link #link(String, int...)}, with {@code beforeLink} given the program once the shaders are attached (to bind
     * attribute and output locations).
     */
    public static int link(String name, IntConsumer beforeLink, int... shaders) {
        int program = GL20C.glCreateProgram();
        for (int shader : shaders) {
            GL20C.glAttachShader(program, shader);
        }
        if (beforeLink != null) {
            beforeLink.accept(program);
        }
        GL20C.glLinkProgram(program);
        for (int shader : shaders) {
            GL20C.glDetachShader(program, shader);
        }
        for (int shader : shaders) {
            GL20C.glDeleteShader(shader);
        }
        if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) == 0) {
            String log = GL20C.glGetProgramInfoLog(program);
            GL20C.glDeleteProgram(program);
            throw new IllegalStateException(name + " program failed to link: " + log);
        }
        return program;
    }
}
