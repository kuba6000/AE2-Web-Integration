package pl.kuba6000.ae2webintegration.icongenerator.client;

import org.jetbrains.annotations.NotNull;

import mezz.jei.api.IJeiRuntime;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.IModRegistry;
import mezz.jei.api.JEIPlugin;
import mezz.jei.api.ingredients.IModIngredientRegistration;

/** Loaded by JEI's plugin scanner only when the optional viewer is installed. */
@JEIPlugin
public final class JeiPlugin implements IModPlugin {

    public JeiPlugin() {}

    @Override
    public void registerIngredients(@NotNull IModIngredientRegistration registry) {
        JeiCatalogue.invalidate();
    }

    @Override
    public void register(@NotNull IModRegistry registry) {
        JeiCatalogue.register(registry.getIngredientRegistry());
    }

    @Override
    public void onRuntimeAvailable(@NotNull IJeiRuntime runtime) {
        JeiCatalogue.available();
    }
}
