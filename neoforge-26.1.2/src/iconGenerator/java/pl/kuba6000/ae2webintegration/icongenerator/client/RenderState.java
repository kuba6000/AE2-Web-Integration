package pl.kuba6000.ae2webintegration.icongenerator.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.WindowRenderState;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4fStack;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.blaze3d.textures.GpuTextureView;

/** Render-thread scope for the public native state touched by GUI submission. */
final class RenderState implements AutoCloseable {

    private RenderTarget target;
    private int width, height, scale;
    private final Matrix4fStack captureStack = new Matrix4fStack(16);
    private Matrix4fStack modelView;
    private @Nullable GpuBufferSlice projection, fog, lights;
    private ProjectionType projectionType;
    private @Nullable GpuTextureView color, depth;
    private boolean scissor;
    private int x, y, scissorWidth, scissorHeight;

    @NotNull
    RenderState snapshot() {
        RenderSystem.assertOnRenderThread();
        Minecraft minecraft = Minecraft.getInstance();
        target = minecraft.getMainRenderTarget();
        WindowRenderState window = minecraft.gameRenderer.getGameRenderState().windowRenderState;
        width = window.width;
        height = window.height;
        scale = window.guiScale;
        modelView = RenderSystem.modelViewStack;
        projection = RenderSystem.getProjectionMatrixBuffer();
        projectionType = RenderSystem.getProjectionType();
        fog = RenderSystem.getShaderFog();
        lights = RenderSystem.getShaderLights();
        color = RenderSystem.outputColorTextureOverride;
        depth = RenderSystem.outputDepthTextureOverride;
        ScissorState clip = RenderSystem.getScissorStateForRenderTypeDraws();
        scissor = clip.enabled();
        x = clip.x();
        y = clip.y();
        scissorWidth = clip.width();
        scissorHeight = clip.height();
        RenderSystem.modelViewStack = captureStack.clear();
        RenderSystem.outputColorTextureOverride = null;
        RenderSystem.outputDepthTextureOverride = null;
        RenderSystem.disableScissorForRenderTypeDraws();
        return this;
    }

    @Override
    public void close() {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.mainRenderTarget = target;
        WindowRenderState window = minecraft.gameRenderer.getGameRenderState().windowRenderState;
        window.width = width;
        window.height = height;
        window.guiScale = scale;
        RenderSystem.modelViewStack = modelView;
        RenderSystem.setProjectionMatrix(projection, projectionType);
        RenderSystem.setShaderFog(fog);
        RenderSystem.setShaderLights(lights);
        RenderSystem.outputColorTextureOverride = color;
        RenderSystem.outputDepthTextureOverride = depth;
        if (scissor) RenderSystem.enableScissorForRenderTypeDraws(x, y, scissorWidth, scissorHeight);
        else RenderSystem.disableScissorForRenderTypeDraws();
    }
}
