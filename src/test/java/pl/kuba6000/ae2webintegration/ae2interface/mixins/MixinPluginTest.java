package pl.kuba6000.ae2webintegration.ae2interface.mixins;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MixinPluginTest {

    @Test
    void shouldApplyMixinDoesNotFilterDeclaredMixins() {
        MixinPlugin plugin = new MixinPlugin();

        assertTrue(plugin.shouldApplyMixin("target.Class", "mixin.Class"));
    }
}
