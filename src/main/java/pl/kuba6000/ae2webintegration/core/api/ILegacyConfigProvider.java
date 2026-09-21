package pl.kuba6000.ae2webintegration.core.api;

@Deprecated
public interface ILegacyConfigProvider {

    boolean isAvailable();

    Object get(String key);

    void markAsMigrated();

}
