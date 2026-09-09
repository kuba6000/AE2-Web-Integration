package pl.kuba6000.ae2webintegration.core;

import pl.kuba6000.ae2webintegration.core.interfaces.IAE;

/** Work that may inspect live Minecraft or AE2 state and therefore must run from the server tick. */
public interface IServerThreadTask {

    void runOnServerThread(IAE ae);

    void failIfPending(String status);
}
