package better112.shader;

import java.io.File;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

/**
 * One GLSL program of the mod's shaders: a vertex and a fragment file from
 * assets/better112/shaders/b112, with #defines put in front. GLSL 1.20, which is what a
 * 1.12.2 game window offers on every system (macOS gives no more).
 */
final class Program {

    /** Where the sources are read from instead of the jar, for tuning without restarting (-Dbetter112.shaderDir). */
    static final String SOURCE_DIR = System.getProperty("better112.shaderDir");

    final String name;
    final int id;
    /** The frame whose uniforms were last set on this program. */
    int frameSet = -1;
    private final Map<String, Integer> uniforms = new HashMap<String, Integer>();

    private Program(String name, int id) {
        this.name = name;
        this.id = id;
    }

    /** Compiles and links; throws with the driver's log when that fails. */
    static Program create(String vertex, String fragment, String defines) {
        String header = "#version 120\n" + defines + source("common.glsl") + "\n";
        String vsh = source(vertex + ".vsh"), fsh = source(fragment + ".fsh");
        int vs = compile(GL20.GL_VERTEX_SHADER, header + vsh, vertex);
        int fs = compile(GL20.GL_FRAGMENT_SHADER, header + fsh, fragment);
        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, vs);
        GL20.glAttachShader(program, fs);
        GL20.glLinkProgram(program);
        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(program, 8192);
            GL20.glDeleteProgram(program);
            throw new IllegalStateException("Linking " + vertex + "/" + fragment + " failed: " + log);
        }
        return new Program(vertex + "/" + fragment, program);
    }

    private static int compile(int type, String text, String name) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, text);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader, 8192);
            GL20.glDeleteShader(shader);
            throw new IllegalStateException("Compiling " + name + " failed: " + log);
        }
        return shader;
    }

    static String source(String file) {
        try {
            if (SOURCE_DIR != null) {
                File f = new File(SOURCE_DIR, file);
                if (f.isFile()) {
                    return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                }
            }
            InputStream in = Program.class.getResourceAsStream("/assets/better112/shaders/b112/" + file);
            if (in == null) {
                throw new IllegalStateException("Missing shader source " + file);
            }
            try {
                return new String(readAll(in), StandardCharsets.UTF_8);
            } finally {
                in.close();
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not read shader source " + file, e);
        }
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private final Map<String, Integer> attributes = new HashMap<String, Integer>();

    int attribute(String attribute) {
        Integer loc = attributes.get(attribute);
        if (loc == null) {
            loc = GL20.glGetAttribLocation(id, attribute);
            attributes.put(attribute, loc);
        }
        return loc;
    }

    void use() {
        GL20.glUseProgram(id);
    }

    int uniform(String uniform) {
        Integer loc = uniforms.get(uniform);
        if (loc == null) {
            loc = GL20.glGetUniformLocation(id, uniform);
            uniforms.put(uniform, loc);
        }
        return loc;
    }

    void set(String uniform, int value) {
        int loc = uniform(uniform);
        if (loc >= 0) {
            GL20.glUniform1i(loc, value);
        }
    }

    void set(String uniform, float value) {
        int loc = uniform(uniform);
        if (loc >= 0) {
            GL20.glUniform1f(loc, value);
        }
    }

    void set(String uniform, float x, float y) {
        int loc = uniform(uniform);
        if (loc >= 0) {
            GL20.glUniform2f(loc, x, y);
        }
    }

    void set(String uniform, float x, float y, float z) {
        int loc = uniform(uniform);
        if (loc >= 0) {
            GL20.glUniform3f(loc, x, y, z);
        }
    }

    void set(String uniform, float[] v3) {
        set(uniform, v3[0], v3[1], v3[2]);
    }

    void setMatrix(String uniform, FloatBuffer matrix) {
        int loc = uniform(uniform);
        if (loc >= 0) {
            matrix.rewind();
            GL20.glUniformMatrix4(loc, false, matrix);
            matrix.rewind();
        }
    }

    void delete() {
        GL20.glDeleteProgram(id);
    }
}
