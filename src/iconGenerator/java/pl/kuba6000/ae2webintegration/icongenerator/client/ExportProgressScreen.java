package pl.kuba6000.ae2webintegration.icongenerator.client;

import java.util.List;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

import org.jetbrains.annotations.NotNull;
import org.lwjgl.input.Keyboard;

/** Exclusive, non-pausing dialog retained until the writer has completed or cleaned up. */
final class ExportProgressScreen extends GuiScreen {

    private final Runnable cancel;
    private final Runnable close;
    private String status;
    private boolean complete;

    ExportProgressScreen(@NotNull String status, @NotNull Runnable cancel, @NotNull Runnable close) {
        this.status = status;
        this.cancel = cancel;
        this.close = close;
    }

    @Override
    @SuppressWarnings("PMD.AvoidMagicNumbers")
    public void initGui() {
        buttonList.add(new GuiButton(0, width / 2 - 60, height / 2 + 36, 120, 20, complete ? "Done" : "Cancel export"));
    }

    @Override
    @SuppressWarnings("PMD.AvoidMagicNumbers")
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        int panelWidth = Math.min(320, width - 32);
        List<String> lines = fontRendererObj.listFormattedStringToWidth(status, Math.max(1, panelWidth - 32));
        int lineHeight = fontRendererObj.FONT_HEIGHT + 3;
        int visibleLines = Math.min(lines.size(), Math.max(1, (height - 120) / lineHeight));
        int panelHeight = 94 + visibleLines * lineHeight;
        int left = (width - panelWidth) / 2;
        int top = (height - panelHeight) / 2;
        drawRect(left - 1, top - 1, left + panelWidth + 1, top + panelHeight + 1, 0xFF777777);
        drawRect(left, top, left + panelWidth, top + panelHeight, 0xFF252525);
        drawCenteredString(fontRendererObj, "AE2 Web icon export", width / 2, top + 14, 0xFFFFFF);
        int y = top + 36;
        for (int index = 0; index < visibleLines; index++) {
            String line = index == visibleLines - 1 && visibleLines < lines.size() ? "More details: /ae2webicons status"
                : lines.get(index);
            drawCenteredString(fontRendererObj, line, width / 2, y, 0xDDDDDD);
            y += lineHeight;
        }
        if (!complete) {
            drawCenteredString(fontRendererObj, "The world continues running.", width / 2, y + 8, 0xAAAAAA);
        }
        buttonList.get(0).yPosition = top + panelHeight - 32;
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public void handleKeyboardInput() {
        // GuiScreen's default also forwards keys to Minecraft's global key handlers.
        if (Keyboard.getEventKeyState()) keyTyped(Keyboard.getEventCharacter(), Keyboard.getEventKey());
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) activate();
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 0) activate();
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
        buttonList.clear();
        initGui();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
