package pl.kuba6000.ae2webintegration.icongenerator.client;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import net.minecraft.client.renderer.ShaderInstance;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.VertexSorting;

/** Reusable caller-state guard; restores both driver state and Minecraft's cached state. */
final class RenderState implements AutoCloseable {

    private static final int TEXTURES = 12;
    private final IntBuffer viewport = BufferUtils.createIntBuffer(4);
    private final IntBuffer scissor = BufferUtils.createIntBuffer(4);
    private final ByteBuffer colorMask = BufferUtils.createByteBuffer(4);
    private final FloatBuffer clearColor = BufferUtils.createFloatBuffer(4);
    private final int[] bindings = new int[TEXTURES];
    private final int[] shaderTextures = new int[TEXTURES];
    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f texture = new Matrix4f();
    private final Matrix4f modelView = new Matrix4f();
    private final Matrix4fStack captureStack = new Matrix4fStack(16);
    private final Vector3f light0 = new Vector3f();
    private final Vector3f light1 = new Vector3f();
    private final float[] shaderColor = new float[4];
    private final float[] fogColor = new float[4];
    private @Nullable ShaderInstance shader;
    private Matrix4fStack callerStack;
    private VertexSorting sorting;
    private FogShape fogShape;
    private int drawFramebuffer, readFramebuffer, readBuffer, packBuffer, unpackBuffer;
    private int packAlignment, packRowLength, packSkipRows, packSkipPixels;
    private int activeTexture, program, vertexArray, arrayBuffer, elementBuffer;
    private int depthFunc, srcRgb, dstRgb, srcAlpha, dstAlpha, equationRgb, equationAlpha;
    private int cullFace, frontFace;
    private boolean blend, depth, depthMask, cull, scissorEnabled, polygonOffset, stencil, logic;
    private boolean hasLight0, hasLight1;
    private float fogStart, fogEnd, glintAlpha, lineWidth, polygonFactor, polygonUnits;
    private double clearDepth;

    @NotNull
    RenderState snapshot() {
        RenderSystem.assertOnRenderThread();
        drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        readBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
        packBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        unpackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        packAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        packRowLength = GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH);
        packSkipRows = GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS);
        packSkipPixels = GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS);
        viewport.clear();
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        scissor.clear();
        GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissor);
        colorMask.clear();
        GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, colorMask);
        clearColor.clear();
        GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, clearColor);
        clearDepth = GL11.glGetDouble(GL11.GL_DEPTH_CLEAR_VALUE);
        activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        for (int i = 0; i < TEXTURES; i++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
            bindings[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            shaderTextures[i] = RenderSystem.getShaderTexture(i);
        }
        GL13.glActiveTexture(activeTexture);
        program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        vertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        arrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        elementBuffer = GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);
        blend = GL11.glIsEnabled(GL11.GL_BLEND);
        depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        cullFace = GL11.glGetInteger(GL11.GL_CULL_FACE_MODE);
        frontFace = GL11.glGetInteger(GL11.GL_FRONT_FACE);
        scissorEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        polygonOffset = GL11.glIsEnabled(GL11.GL_POLYGON_OFFSET_FILL);
        polygonFactor = GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_FACTOR);
        polygonUnits = GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_UNITS);
        stencil = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
        logic = GL11.glIsEnabled(GL11.GL_COLOR_LOGIC_OP);
        srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        equationRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
        equationAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
        projection.set(RenderSystem.getProjectionMatrix());
        sorting = RenderSystem.getVertexSorting();
        texture.set(RenderSystem.getTextureMatrix());
        modelView.set(RenderSystem.getModelViewMatrix());
        callerStack = RenderSystem.getModelViewStack();
        shader = RenderSystem.getShader();
        System.arraycopy(RenderSystem.getShaderColor(), 0, shaderColor, 0, 4);
        System.arraycopy(RenderSystem.getShaderFogColor(), 0, fogColor, 0, 4);
        fogStart = RenderSystem.getShaderFogStart();
        fogEnd = RenderSystem.getShaderFogEnd();
        fogShape = RenderSystem.getShaderFogShape();
        glintAlpha = RenderSystem.getShaderGlintAlpha();
        lineWidth = RenderSystem.getShaderLineWidth();
        // Narrow access transformer: native API offers a setter but no corresponding getter.
        hasLight0 = RenderSystem.shaderLightDirections[0] != null;
        hasLight1 = RenderSystem.shaderLightDirections[1] != null;
        if (hasLight0) light0.set(RenderSystem.shaderLightDirections[0]);
        if (hasLight1) light1.set(RenderSystem.shaderLightDirections[1]);
        // Isolate the complete caller stack, including its depth, from throwing custom renderers.
        // Matrix4fStack exposes no public depth getter or full-stack copy-into operation.
        RenderSystem.modelViewStack = captureStack.clear();
        return this;
    }

    @Override
    public void close() {
        RenderSystem.modelViewStack = callerStack;
        ShaderInstance current = RenderSystem.getShader();
        if (current != null) current.clear();
        BufferUploader.invalidate();
        RenderSystem.setProjectionMatrix(projection, sorting);
        RenderSystem.setTextureMatrix(texture);
        // Preserve the applied matrix too; it can differ from the not-yet-applied stack top.
        RenderSystem.getModelViewMatrix()
            .set(modelView);
        RenderSystem.setShader(() -> shader);
        RenderSystem.setShaderColor(shaderColor[0], shaderColor[1], shaderColor[2], shaderColor[3]);
        RenderSystem.setShaderFogColor(fogColor[0], fogColor[1], fogColor[2], fogColor[3]);
        RenderSystem.setShaderFogStart(fogStart);
        RenderSystem.setShaderFogEnd(fogEnd);
        RenderSystem.setShaderFogShape(fogShape);
        RenderSystem.setShaderGlintAlpha(glintAlpha);
        RenderSystem.lineWidth(lineWidth);
        RenderSystem.setShaderLights(hasLight0 ? light0 : null, hasLight1 ? light1 : null);
        for (int i = 0; i < TEXTURES; i++) {
            int unit = GL13.GL_TEXTURE0 + i;
            GL13.glActiveTexture(unit);
            GlStateManager._activeTexture(unit);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, bindings[i]);
            GlStateManager._bindTexture(bindings[i]);
            RenderSystem.setShaderTexture(i, shaderTextures[i]);
        }
        GL13.glActiveTexture(activeTexture);
        GlStateManager._activeTexture(activeTexture);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
        GL11.glReadBuffer(readBuffer);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, packBuffer);
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, unpackBuffer);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, packAlignment);
        GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, packRowLength);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, packSkipRows);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, packSkipPixels);
        GL30.glBindVertexArray(vertexArray);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, arrayBuffer);
        if (vertexArray != 0) GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, elementBuffer);
        GL20.glUseProgram(program);
        GlStateManager._viewport(viewport.get(0), viewport.get(1), viewport.get(2), viewport.get(3));
        GlStateManager._scissorBox(scissor.get(0), scissor.get(1), scissor.get(2), scissor.get(3));
        // Raw restoration also repairs mods which bypassed Mojang's state cache.
        capability(GL11.GL_BLEND, blend);
        if (blend) GlStateManager._enableBlend();
        else GlStateManager._disableBlend();
        GL14.glBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
        GlStateManager._blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
        GL20.glBlendEquationSeparate(equationRgb, equationAlpha);
        capability(GL11.GL_DEPTH_TEST, depth);
        if (depth) GlStateManager._enableDepthTest();
        else GlStateManager._disableDepthTest();
        GL11.glDepthMask(depthMask);
        GlStateManager._depthMask(depthMask);
        GL11.glDepthFunc(depthFunc);
        GlStateManager._depthFunc(depthFunc);
        capability(GL11.GL_CULL_FACE, cull);
        if (cull) GlStateManager._enableCull();
        else GlStateManager._disableCull();
        GL11.glCullFace(cullFace);
        GL11.glFrontFace(frontFace);
        capability(GL11.GL_SCISSOR_TEST, scissorEnabled);
        if (scissorEnabled) GlStateManager._enableScissorTest();
        else GlStateManager._disableScissorTest();
        capability(GL11.GL_POLYGON_OFFSET_FILL, polygonOffset);
        if (polygonOffset) GlStateManager._enablePolygonOffset();
        else GlStateManager._disablePolygonOffset();
        GL11.glPolygonOffset(polygonFactor, polygonUnits);
        GlStateManager._polygonOffset(polygonFactor, polygonUnits);
        capability(GL11.GL_STENCIL_TEST, stencil);
        capability(GL11.GL_COLOR_LOGIC_OP, logic);
        if (logic) GlStateManager._enableColorLogicOp();
        else GlStateManager._disableColorLogicOp();
        boolean r = colorMask.get(0) != 0, g = colorMask.get(1) != 0;
        boolean b = colorMask.get(2) != 0, a = colorMask.get(3) != 0;
        GL11.glColorMask(r, g, b, a);
        GlStateManager._colorMask(r, g, b, a);
        GL11.glClearColor(clearColor.get(0), clearColor.get(1), clearColor.get(2), clearColor.get(3));
        GL11.glClearDepth(clearDepth);
    }

    private static void capability(int capability, boolean enabled) {
        if (enabled) GL11.glEnable(capability);
        else GL11.glDisable(capability);
    }
}
