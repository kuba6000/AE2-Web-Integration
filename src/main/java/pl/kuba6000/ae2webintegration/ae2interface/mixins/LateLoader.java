package pl.kuba6000.ae2webintegration.ae2interface.mixins;

import java.util.Collections;
import java.util.List;

import zone.rong.mixinbooter.ILateMixinLoader;

public class LateLoader implements ILateMixinLoader {

    @Override
    public List<String> getMixinConfigs() {
        return Collections.singletonList("mixins.ae2webintegration.json");
    }
}
