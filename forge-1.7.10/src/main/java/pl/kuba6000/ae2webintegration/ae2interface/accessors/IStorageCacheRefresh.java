package pl.kuba6000.ae2webintegration.ae2interface.accessors;

/** Forces a complete monitor read without synthesizing a storage-change notification. */
public interface IStorageCacheRefresh {

    void web$refreshStorageCache();
}
