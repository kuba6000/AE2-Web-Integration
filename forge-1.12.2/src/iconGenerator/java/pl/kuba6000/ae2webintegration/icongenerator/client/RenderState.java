package pl.kuba6000.ae2webintegration.icongenerator.client;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;

import org.jetbrains.annotations.NotNull;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ARBFramebufferObject;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
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
    private int unpackBuffer;
    private int program;
    private int arrayBuffer;
    private int elementBuffer;
    private int vertexArray;
    private final FloatBuffer floats = BufferUtils.createFloatBuffer(MATRIX_COMPONENTS);
    private final IntBuffer ints = BufferUtils.createIntBuffer(MATRIX_COMPONENTS);
    private final ByteBuffer bytes = BufferUtils.createByteBuffer(MATRIX_COMPONENTS);
    private static final GlStateManager.FogMode[] FOG_MODES = GlStateManager.FogMode.values();
    private static final GlStateManager.CullFace[] CULL_FACES = GlStateManager.CullFace.values();
    private static final GlStateManager.TexGen[] TEX_GEN = GlStateManager.TexGen.values();
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
        unpackBuffer = packBindingSupported ? GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING) : 0;
        program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        arrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        elementBuffer = GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);
        vertexArray = GLContext.getCapabilities().OpenGL30 ? GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING) : 0;
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
            OpenGlHelper.glBindRenderbuffer(OpenGlHelper.GL_RENDERBUFFER, renderbuffer);
            if (GLContext.getCapabilities().OpenGL30) {
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
            } else if (separateFramebuffers) {
                ARBFramebufferObject.glBindFramebuffer(ARBFramebufferObject.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
                ARBFramebufferObject.glBindFramebuffer(ARBFramebufferObject.GL_READ_FRAMEBUFFER, readFramebuffer);
            } else {
                OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, drawFramebuffer);
            }
            // Restore the target first so saved draw/read-buffer attributes apply to the original FBO.
            GL11.glPopClientAttrib();
            GL11.glPopAttrib();
            if (packBindingSupported) {
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, packBuffer);
                GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, unpackBuffer);
            }
            if (GLContext.getCapabilities().OpenGL30) GL30.glBindVertexArray(vertexArray);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, arrayBuffer);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, elementBuffer);
            GL20.glUseProgram(program);
            synchronizeCaches();
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, brightnessX, brightnessY);
            OpenGlHelper.setActiveTexture(activeTexture);
            GL11.glMatrixMode(matrixMode);
        }
    }

    /** glPopAttrib restores driver state, but Mojang's Java caches must be reconciled through their public setters. */
    private void synchronizeCaches() {
        // Setters such as current color can also affect material state. Retain the exact restored driver state.
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            if (GL11.glIsEnabled(GL11.GL_ALPHA_TEST)) GlStateManager.enableAlpha();
            else GlStateManager.disableAlpha();
            GlStateManager
                .alphaFunc(GL11.glGetInteger(GL11.GL_ALPHA_TEST_FUNC), GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF));
            if (GL11.glIsEnabled(GL11.GL_LIGHTING)) GlStateManager.enableLighting();
            else GlStateManager.disableLighting();
            for (int index = 0; index < 8; index++) {
                if (GL11.glIsEnabled(GL11.GL_LIGHT0 + index)) GlStateManager.enableLight(index);
                else GlStateManager.disableLight(index);
            }
            if (GL11.glIsEnabled(GL11.GL_COLOR_MATERIAL)) GlStateManager.enableColorMaterial();
            else GlStateManager.disableColorMaterial();
            GlStateManager.colorMaterial(
                GL11.glGetInteger(GL11.GL_COLOR_MATERIAL_FACE),
                GL11.glGetInteger(GL11.GL_COLOR_MATERIAL_PARAMETER));
            if (GL11.glIsEnabled(GL11.GL_BLEND)) GlStateManager.enableBlend();
            else GlStateManager.disableBlend();
            GlStateManager.tryBlendFuncSeparate(
                GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB),
                GL11.glGetInteger(GL14.GL_BLEND_DST_RGB),
                GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA),
                GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA));
            if (GL11.glIsEnabled(GL11.GL_DEPTH_TEST)) GlStateManager.enableDepth();
            else GlStateManager.disableDepth();
            GlStateManager.depthFunc(GL11.glGetInteger(GL11.GL_DEPTH_FUNC));
            GlStateManager.depthMask(GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK));
            if (GL11.glIsEnabled(GL11.GL_FOG)) GlStateManager.enableFog();
            else GlStateManager.disableFog();
            int fogMode = GL11.glGetInteger(GL11.GL_FOG_MODE);
            for (GlStateManager.FogMode mode : FOG_MODES) if (mode.capabilityId == fogMode) GlStateManager.setFog(mode);
            GlStateManager.setFogDensity(GL11.glGetFloat(GL11.GL_FOG_DENSITY));
            GlStateManager.setFogStart(GL11.glGetFloat(GL11.GL_FOG_START));
            GlStateManager.setFogEnd(GL11.glGetFloat(GL11.GL_FOG_END));
            if (GL11.glIsEnabled(GL11.GL_CULL_FACE)) GlStateManager.enableCull();
            else GlStateManager.disableCull();
            int cullMode = GL11.glGetInteger(GL11.GL_CULL_FACE_MODE);
            for (GlStateManager.CullFace face : CULL_FACES) if (face.mode == cullMode) GlStateManager.cullFace(face);
            if (GL11.glIsEnabled(GL11.GL_POLYGON_OFFSET_FILL)) GlStateManager.enablePolygonOffset();
            else GlStateManager.disablePolygonOffset();
            GlStateManager.doPolygonOffset(
                GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_FACTOR),
                GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_UNITS));
            if (GL11.glIsEnabled(GL11.GL_COLOR_LOGIC_OP)) GlStateManager.enableColorLogic();
            else GlStateManager.disableColorLogic();
            GlStateManager.colorLogicOp(GL11.glGetInteger(GL11.GL_LOGIC_OP_MODE));
            if (GL11.glIsEnabled(GL11.GL_NORMALIZE)) GlStateManager.enableNormalize();
            else GlStateManager.disableNormalize();
            if (GL11.glIsEnabled(GL12.GL_RESCALE_NORMAL)) GlStateManager.enableRescaleNormal();
            else GlStateManager.disableRescaleNormal();
            GlStateManager.shadeModel(GL11.glGetInteger(GL11.GL_SHADE_MODEL));
            bytes.clear();
            GL11.glGetBoolean(GL11.GL_COLOR_WRITEMASK, bytes);
            GlStateManager.colorMask(bytes.get(0) != 0, bytes.get(1) != 0, bytes.get(2) != 0, bytes.get(3) != 0);
            floats.clear();
            GL11.glGetFloat(GL11.GL_CURRENT_COLOR, floats);
            GlStateManager.color(floats.get(0), floats.get(1), floats.get(2), floats.get(3));
            floats.clear();
            GL11.glGetFloat(GL11.GL_COLOR_CLEAR_VALUE, floats);
            GlStateManager.clearColor(floats.get(0), floats.get(1), floats.get(2), floats.get(3));
            GlStateManager.clearDepth(GL11.glGetDouble(GL11.GL_DEPTH_CLEAR_VALUE));
            for (int index = 0; index < 8; index++) {
                OpenGlHelper.setActiveTexture(GL13.GL_TEXTURE0 + index);
                GlStateManager.setActiveTexture(GL13.GL_TEXTURE0 + index);
                GlStateManager.bindTexture(GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D));
                if (GL11.glIsEnabled(GL11.GL_TEXTURE_2D)) GlStateManager.enableTexture2D();
                else GlStateManager.disableTexture2D();
            }
            OpenGlHelper.setActiveTexture(activeTexture);
            GlStateManager.setActiveTexture(activeTexture);
            for (int index = 0; index < TEX_GEN.length; index++) {
                if (GL11.glIsEnabled(GL11.GL_TEXTURE_GEN_S + index)) GlStateManager.enableTexGenCoord(TEX_GEN[index]);
                else GlStateManager.disableTexGenCoord(TEX_GEN[index]);
                ints.clear();
                GL11.glGetTexGen(GL11.GL_S + index, GL11.GL_TEXTURE_GEN_MODE, ints);
                GlStateManager.texGen(TEX_GEN[index], ints.get(0));
            }
        } finally {
            GL11.glPopAttrib();
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
