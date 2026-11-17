package better112.shader;

import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

import org.lwjgl.opengl.APPLEVertexArrayObject;
import org.lwjgl.opengl.ARBVertexArrayObject;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLContext;

/**
 * One vertex array object per chunk section mesh: the buffer and the four attribute pointers
 * of the game's block vertex format, set up once instead of with six calls before every draw.
 * A section's buffer keeps its name while its contents are replaced, so its object stays good
 * until the buffer itself is deleted (render distance changed, renderers reloaded).
 */
final class Vaos {

    /**
     * Off by default: on a Mac (OpenGL 2.1 over Metal) drawing through these objects measured 3 to 5% fewer
     * frames than setting the pointers each time. The self-test switches them on to compare (shadervao:on).
     */
    static boolean enabled = "true".equals(System.getProperty("better112.shaderVao"));

    private static final Map<VertexBuffer, int[]> OBJECTS = new IdentityHashMap<VertexBuffer, int[]>();
    private static Field bufferId;
    private static boolean failed;
    private static int binds;

    private Vaos() {}

    static boolean usable() {
        if (!enabled || failed) {
            return false;
        }
        if (bufferId == null) {
            try {
                bufferId = ReflectionHelper.findField(VertexBuffer.class, "glBufferId", "field_177365_a");
                bufferId.setAccessible(true);
            } catch (RuntimeException e) {
                failed = true;
                return false;
            }
        }
        return GLContext.getCapabilities().GL_APPLE_vertex_array_object || GLContext.getCapabilities().GL_ARB_vertex_array_object;
    }

    private static int gen() {
        return GLContext.getCapabilities().GL_ARB_vertex_array_object ? ARBVertexArrayObject.glGenVertexArrays()
                : APPLEVertexArrayObject.glGenVertexArraysAPPLE();
    }

    private static void bindObject(int vao) {
        if (GLContext.getCapabilities().GL_ARB_vertex_array_object) {
            ARBVertexArrayObject.glBindVertexArray(vao);
        } else {
            APPLEVertexArrayObject.glBindVertexArrayAPPLE(vao);
        }
    }

    private static void delete(int vao) {
        if (GLContext.getCapabilities().GL_ARB_vertex_array_object) {
            ARBVertexArrayObject.glDeleteVertexArrays(vao);
        } else {
            APPLEVertexArrayObject.glDeleteVertexArraysAPPLE(vao);
        }
    }

    /** Binds the section's object, making it on first use or after its buffer was replaced. */
    static void bind(VertexBuffer vb) {
        int id;
        try {
            id = bufferId.getInt(vb);
        } catch (IllegalAccessException e) {
            failed = true;
            vb.bindBuffer();
            return;
        }
        int[] entry = OBJECTS.get(vb);
        if (entry != null && entry[1] == id) {
            bindObject(entry[0]);
            return;
        }
        if (entry != null) {
            delete(entry[0]);
        }
        int vao = gen();
        bindObject(vao);
        OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, id);
        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        GL11.glVertexPointer(3, GL11.GL_FLOAT, 28, 0L);
        GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
        GL11.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, 28, 12L);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
        GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GL11.glTexCoordPointer(2, GL11.GL_FLOAT, 28, 16L);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.lightmapTexUnit);
        GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GL11.glTexCoordPointer(2, GL11.GL_SHORT, 28, 24L);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
        OBJECTS.put(vb, new int[] {vao, id});
        if (++binds % 4096 == 0) {
            purge();
        }
    }

    static void unbind() {
        if (enabled && !failed && (GLContext.getCapabilities().GL_APPLE_vertex_array_object || GLContext.getCapabilities().GL_ARB_vertex_array_object)) {
            bindObject(0);
        }
    }

    /** Drops the objects of buffers that no longer exist. */
    private static void purge() {
        for (Iterator<Map.Entry<VertexBuffer, int[]>> it = OBJECTS.entrySet().iterator(); it.hasNext();) {
            Map.Entry<VertexBuffer, int[]> e = it.next();
            int id;
            try {
                id = bufferId.getInt(e.getKey());
            } catch (IllegalAccessException ex) {
                id = -1;
            }
            if (id != e.getValue()[1]) {
                delete(e.getValue()[0]);
                it.remove();
            }
        }
    }

    /** Every object, when the shaders are switched off or the context goes. */
    static void deleteAll() {
        for (int[] e : OBJECTS.values()) {
            delete(e[0]);
        }
        OBJECTS.clear();
    }

    static int count() {
        return OBJECTS.size();
    }
}
