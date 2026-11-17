package better112.shader;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;

import org.lwjgl.opengl.ARBFramebufferObject;
import org.lwjgl.opengl.EXTFramebufferBlit;
import org.lwjgl.opengl.EXTPackedDepthStencil;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GLContext;

/** A picture the shaders draw into: a framebuffer with a colour texture, a depth texture, or both. */
final class Target {

    static final int READ_FRAMEBUFFER = 0x8CA8, DRAW_FRAMEBUFFER = 0x8CA9;

    final int width, height;
    final int fbo;
    final int color;   // texture, 0 if none
    final int depth;   // texture, 0 if none

    private Target(int width, int height, int fbo, int color, int depth) {
        this.width = width;
        this.height = height;
        this.fbo = fbo;
        this.color = color;
        this.depth = depth;
    }

    /** A colour picture, filtered linearly when read. */
    static Target color(int width, int height, int internalFormat, int format, int type) {
        int tex = texture(width, height, internalFormat, format, type, GL11.GL_LINEAR);
        int fbo = OpenGlHelper.glGenFramebuffers();
        OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, fbo);
        OpenGlHelper.glFramebufferTexture2D(OpenGlHelper.GL_FRAMEBUFFER, OpenGlHelper.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tex, 0);
        check(fbo, "colour " + width + "x" + height);
        return new Target(width, height, fbo, tex, 0);
    }

    /**
     * A depth picture. With {@code stencil} its format is the packed depth and stencil the game's own picture
     * has once a mod asks for stencil, which a copy from that picture needs.
     */
    static Target depth(int width, int height, boolean stencil, boolean shadowCompare) {
        int tex = stencil
                ? texture(width, height, EXTPackedDepthStencil.GL_DEPTH24_STENCIL8_EXT, EXTPackedDepthStencil.GL_DEPTH_STENCIL_EXT,
                        EXTPackedDepthStencil.GL_UNSIGNED_INT_24_8_EXT, shadowCompare ? GL11.GL_LINEAR : GL11.GL_NEAREST)
                : texture(width, height, GL14.GL_DEPTH_COMPONENT24, GL11.GL_DEPTH_COMPONENT, GL11.GL_UNSIGNED_INT,
                        shadowCompare ? GL11.GL_LINEAR : GL11.GL_NEAREST);
        GlStateManager.bindTexture(tex);
        if (shadowCompare) {
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, GL14.GL_COMPARE_R_TO_TEXTURE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_FUNC, GL11.GL_LEQUAL);
        } else {
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE);
        }
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_DEPTH_TEXTURE_MODE, GL11.GL_LUMINANCE);
        int fbo = OpenGlHelper.glGenFramebuffers();
        OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, fbo);
        OpenGlHelper.glFramebufferTexture2D(OpenGlHelper.GL_FRAMEBUFFER, OpenGlHelper.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, tex, 0);
        if (stencil) {
            OpenGlHelper.glFramebufferTexture2D(OpenGlHelper.GL_FRAMEBUFFER, ARBFramebufferObject.GL_STENCIL_ATTACHMENT, GL11.GL_TEXTURE_2D, tex, 0);
        }
        // No colour: nothing is drawn or read there.
        GL11.glDrawBuffer(GL11.GL_NONE);
        GL11.glReadBuffer(GL11.GL_NONE);
        check(fbo, "depth " + width + "x" + height);
        return new Target(width, height, fbo, 0, tex);
    }

    private static int texture(int width, int height, int internalFormat, int format, int type, int filter) {
        int tex = GlStateManager.generateTexture();
        GlStateManager.bindTexture(tex);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, filter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, filter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internalFormat, width, height, 0, format, type, (ByteBuffer) null);
        return tex;
    }

    private static void check(int fbo, String what) {
        int status = OpenGlHelper.glCheckFramebufferStatus(OpenGlHelper.GL_FRAMEBUFFER);
        if (status != OpenGlHelper.GL_FRAMEBUFFER_COMPLETE) {
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, 0);
            OpenGlHelper.glDeleteFramebuffers(fbo);
            throw new IllegalStateException("Framebuffer for " + what + " incomplete: 0x" + Integer.toHexString(status));
        }
    }

    void bind() {
        OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, fbo);
        GlStateManager.viewport(0, 0, width, height);
    }

    void delete() {
        OpenGlHelper.glDeleteFramebuffers(fbo);
        if (color != 0) {
            GlStateManager.deleteTexture(color);
        }
        if (depth != 0) {
            GlStateManager.deleteTexture(depth);
        }
    }

    /** Copies from one framebuffer to another of the same size: colour, depth, or both (GL bits). */
    static void blit(int fromFbo, int toFbo, int width, int height, int mask) {
        OpenGlHelper.glBindFramebuffer(READ_FRAMEBUFFER, fromFbo);
        OpenGlHelper.glBindFramebuffer(DRAW_FRAMEBUFFER, toFbo);
        if (GLContext.getCapabilities().GL_ARB_framebuffer_object) {
            ARBFramebufferObject.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, mask, GL11.GL_NEAREST);
        } else {
            EXTFramebufferBlit.glBlitFramebufferEXT(0, 0, width, height, 0, 0, width, height, mask, GL11.GL_NEAREST);
        }
        OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, 0);
    }

    static boolean canBlit() {
        return GLContext.getCapabilities().GL_ARB_framebuffer_object || GLContext.getCapabilities().GL_EXT_framebuffer_blit;
    }

    @SuppressWarnings("unused")
    private static IntBuffer none() {
        return null;
    }
}
