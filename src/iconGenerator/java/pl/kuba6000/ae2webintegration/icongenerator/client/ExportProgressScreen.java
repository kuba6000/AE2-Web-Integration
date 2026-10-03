package pl.kuba6000.ae2webintegration.icongenerator.client;

import java.util.List;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import org.jetbrains.annotations.NotNull;

/** Exclusive, non-pausing dialog retained until the writer completes or finishes cleanup. */
final class ExportProgressScreen extends Screen {

    private final Runnable cancel;
    private final Runnable close;
    private String status;
    private boolean complete;
    private Button action;

    ExportProgressScreen(@NotNull String status, @NotNull Runnable cancel, @NotNull Runnable close) {
        super(Component.literal("AE2 Web icon export"));
        this.status = status;
        this.cancel = cancel;
        this.close = close;
    }

    @Override
    protected void init() {
        action = addRenderableWidget(
            Button.builder(Component.literal(complete ? "Done" : "Cancel export"), button -> activate())
                .bounds(width / 2 - 60, height / 2 + 36, 120, 20)
                .build());
    }

    @Override
    @SuppressWarnings("PMD.AvoidMagicNumbers")
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int panelWidth = Math.min(320, width - 32);
        List<FormattedCharSequence> lines = font.split(Component.literal(status), Math.max(1, panelWidth - 32));
        int lineHeight = font.lineHeight + 3;
        int visibleLines = Math.min(lines.size(), Math.max(1, (height - 120) / lineHeight));
        int panelHeight = 74 + visibleLines * lineHeight;
        int left = (width - panelWidth) / 2;
        int top = (height - panelHeight) / 2;
        graphics.fill(left - 1, top - 1, left + panelWidth + 1, top + panelHeight + 1, 0xFF777777);
        graphics.fill(left, top, left + panelWidth, top + panelHeight, 0xFF252525);
        graphics.drawCenteredString(font, title, width / 2, top + 14, 0xFFFFFF);
        int y = top + 36;
        for (int index = 0; index < visibleLines; index++) {
            FormattedCharSequence line = index == visibleLines - 1 && visibleLines < lines.size()
                ? Component.literal("More details: /ae2webicons status")
                    .getVisualOrderText()
                : lines.get(index);
            graphics.drawCenteredString(font, line, width / 2, y, 0xDDDDDD);
            y += lineHeight;
        }
        action.setY(top + panelHeight - 32);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        activate();
    }

    private void activate() {
        if (complete) close.run();
        else cancel.run();
    }

    void setStatus(@NotNull String status) {
        this.status = status;
    }

    void complete(@NotNull String status) {
        this.status = status;
        complete = true;
        action.setMessage(Component.literal("Done"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
