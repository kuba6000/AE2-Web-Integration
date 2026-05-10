package pl.kuba6000.ae2webintegration.core.api;

public interface IConfigProvider {

    void setValue(ConfigKey key, int value);

    void setValue(ConfigKey key, String value);

    void setValue(ConfigKey key, boolean value);
}
