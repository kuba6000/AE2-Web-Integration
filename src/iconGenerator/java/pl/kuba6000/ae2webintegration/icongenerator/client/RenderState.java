package pl.kuba6000.ae2webintegration.icongenerator.client;

import java.nio.FloatBuffer;

import net.minecraft.client.renderer.OpenGlHelper;

import org.jetbrains.annotations.NotNull;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ARBFramebufferObject;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLContext;

/** Compatibility-profile state not all covered by glPushAttrib, particularly matrices and FBOs. */
final class RenderState implements AutoCloseable {

    private static final int MATRIX_COMPONENTS = 16;
    private int matrixMode;
    private int activeTexture;
    private int renderbuffer;
    private int drawFramebuffer;
    private boolean separateFramebuffers;
    private int readFramebuffer;
    private boolean packBindingSupported;
    private int packBuffer;
    private float brightnessX;
    private float brightnessY;
    private final Matrix projection = new Matrix(
        GL11.GL_PROJECTION,
        GL11.GL_PROJECTION_MATRIX,
        GL11.GL_PROJECTION_STACK_DEPTH);
    private final Matrix model = new Matrix(GL11.GL_MODELVIEW, GL11.GL_MODELVIEW_MATRIX, GL11.GL_MODELVIEW_STACK_DEPTH);
    private Matrix[] textures = new Matrix[0];

    /** Refresh values in owner-local storage; captures and framebuffer cleanup never overlap. */
    @NotNull
    RenderState snapshot() {
        packBindingSupported = PixelReadback.supportsPackBinding();
        packBuffer = packBindingSupported ? GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING) : 0;
        matrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        renderbuffer = GL11.glGetInteger(ARBFramebufferObject.GL_RENDERBUFFER_BINDING);
        drawFramebuffer = GL11.glGetInteger(ARBFramebufferObject.GL_DRAW_FRAMEBUFFER_BINDING);
        separateFramebuffers = GLContext.getCapabilities().OpenGL30
            || GLContext.getCapabilities().GL_ARB_framebuffer_object;
        readFramebuffer = separateFramebuffers ? GL11.glGetInteger(ARBFramebufferObject.GL_READ_FRAMEBUFFER_BINDING)
            : drawFramebuffer;
        brightnessX = OpenGlHelper.lastBrightnessX;
        brightnessY = OpenGlHelper.lastBrightnessY;
        // Delay context-dependent initialization until the first capture, not session construction.
        if (textures.length == 0) {
            textures = new Matrix[GL11.glGetInteger(GL13.GL_MAX_TEXTURE_UNITS)];
            for (int index = 0; index < textures.length; index++) {
                textures[index] = new Matrix(GL11.GL_TEXTURE, GL11.GL_TEXTURE_MATRIX, GL11.GL_TEXTURE_STACK_DEPTH);
            }
        }
        projection.snapshot();
        model.snapshot();
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushClientAttrib(GL11.GL_CLIENT_PIXEL_STORE_BIT | GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
        for (int index = 0; index < textures.length; index++) {
            OpenGlHelper.setActiveTexture(GL13.GL_TEXTURE0 + index);
            textures[index].snapshot();
        }
        OpenGlHelper.setActiveTexture(activeTexture);
        GL11.glMatrixMode(matrixMode);
        return this;
    }

    @Override
    public void close() {
        try {
            projection.restore();
            model.restore();
            for (int index = 0; index < textures.length; index++) {
                OpenGlHelper.setActiveTexture(GL13.GL_TEXTURE0 + index);
                textures[index].restore();
            }
        } finally {
            OpenGlHelper.func_153176_h(OpenGlHelper.field_153199_f, renderbuffer);
            if (GLContext.getCapabilities().OpenGL30) {
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
            } else if (separateFramebuffers) {
                ARBFramebufferObject.glBindFramebuffer(ARBFramebufferObject.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
                ARBFramebufferObject.glBindFramebuffer(ARBFramebufferObject.GL_READ_FRAMEBUFFER, readFramebuffer);
            } else {
                OpenGlHelper.func_153171_g(OpenGlHelper.field_153198_e, drawFramebuffer);
            }
            // Restore the target first so saved draw/read-buffer attributes apply to the original FBO.
            GL11.glPopClientAttrib();
            GL11.glPopAttrib();
            if (packBindingSupported) GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, packBuffer);
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, brightnessX, brightnessY);
            OpenGlHelper.setActiveTexture(activeTexture);
            GL11.glMatrixMode(matrixMode);
        }
    }

    private static final class Matrix {

        private final int mode;
        private final int name;
        private final int depthName;
        private int depth;
        private final FloatBuffer contents = BufferUtils.createFloatBuffer(MATRIX_COMPONENTS);

        private Matrix(int mode, int name, int depthName) {
            this.mode = mode;
            this.name = name;
            this.depthName = depthName;
        }

        private void snapshot() {
            GL11.glMatrixMode(mode);
            depth = GL11.glGetInteger(depthName);
            contents.clear();
            GL11.glGetFloat(name, contents);
            contents.rewind();
        }

        private void restore() {
            GL11.glMatrixMode(mode);
            int current = GL11.glGetInteger(depthName);
            while (current > depth) {
                GL11.glPopMatrix();
                current--;
            }
            if (current < depth) throw new IllegalStateException("Renderer consumed the caller's matrix stack");
            contents.rewind();
            GL11.glLoadMatrix(contents);
        }
    }
}
