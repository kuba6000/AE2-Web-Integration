package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

class ClientAddressResolverTest {

    private static InetAddress addr(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static List<String> header(String... values) {
        return Arrays.asList(values);
    }

    private static final InetAddress PROXY = addr("127.0.0.1");
    private static final InetAddress CLIENT = addr("203.0.113.7");
    private static final InetAddress OUTSIDER = addr("198.51.100.4");
    /** The address connections arrive on; distinct from every peer below unless a test says otherwise. */
    private static final InetAddress LOCAL = addr("10.9.9.9");

    // --- nothing is trusted by default ---

    @Test
    void withoutConfigurationARemoteHeaderIsIgnored() {
        // Loopback and same-machine peers are trusted implicitly, so use a genuinely remote peer here.
        ClientAddressResolver resolver = ClientAddressResolver.fromConfig("");
        assertEquals(OUTSIDER, resolver.resolve(OUTSIDER, LOCAL, header("203.0.113.7"), null));
    }

    @Test
    void spoofedForwardedForFromAnUntrustedPeerIsIgnored() {
        // The C-32 guard: without this, anyone could claim to be localhost and gain admin.
        ClientAddressResolver resolver = ClientAddressResolver.fromConfig("10.0.0.1");
        assertEquals(OUTSIDER, resolver.resolve(OUTSIDER, LOCAL, header("127.0.0.1"), header("127.0.0.1")));
    }

    // --- trusted peer ---

    @Test
    void trustedPeerSuppliesTheClientAddress() {
        ClientAddressResolver resolver = ClientAddressResolver.fromConfig("127.0.0.1");
        assertEquals(CLIENT, resolver.resolve(PROXY, LOCAL, header("203.0.113.7"), null));
    }

    @Test
    void chainIsWalkedRightToLeftSkippingTrustedHops() {
        // "client, proxy1" arriving from proxy2: both proxies are ours, the client is the first untrusted.
        ClientAddressResolver resolver = ClientAddressResolver.fromConfig("127.0.0.1, 10.0.0.1");
        assertEquals(CLIENT, resolver.resolve(PROXY, LOCAL, header("203.0.113.7, 10.0.0.1"), null));
    }

    @Test
    void leftmostEntriesAreNotTrustedBlindly() {
        // A client may pre-populate the header; the rightmost untrusted entry is what our proxy observed.
        ClientAddressResolver resolver = ClientAddressResolver.fromConfig("127.0.0.1");
        assertEquals(OUTSIDER, resolver.resolve(PROXY, LOCAL, header("203.0.113.7, 198.51.100.4"), null));
    }

    @Test
    void realIpIsUsedWhenForwardedForIsAbsent() {
        ClientAddressResolver resolver = ClientAddressResolver.fromConfig("127.0.0.1");
        assertEquals(CLIENT, resolver.resolve(PROXY, LOCAL, null, header("203.0.113.7")));
    }

    @Test
    void forwardedForWinsOverRealIp() {
        ClientAddressResolver resolver = ClientAddressResolver.fromConfig("127.0.0.1");
        assertEquals(CLIENT, resolver.resolve(PROXY, LOCAL, header("203.0.113.7"), header("198.51.100.4")));
    }

    // --- CIDR ---

    @Test
    void cidrEntryMatchesInsideTheRangeOnly() {
        ClientAddressResolver resolver = ClientAddressResolver.fromConfig("10.0.0.0/8");
        assertTrue(resolver.isTrusted(addr("10.1.2.3")));
        assertTrue(resolver.isTrusted(addr("10.255.255.255")));
        assertFalse(resolver.isTrusted(addr("11.0.0.1")));
    }

    @Test
    void cidrWithNonZeroHostBitsStillMatchesItsNetwork() {
        ClientAddressResolver resolver = ClientAddressResolver.fromConfig("192.168.1.55/24");
        assertTrue(resolver.isTrusted(addr("192.168.1.1")));
        assertFalse(resolver.isTrusted(addr("192.168.2.1")));
    }

    @Test
    void addressFamiliesDoNotMatchAcrossEachOther() {
        ClientAddressResolver resolver = ClientAddressResolver.fromConfig("10.0.0.0/8");
        assertFalse(resolver.isTrusted(addr("::1")));
    }

    @Test
    void ipv6EntriesAreSupported() {
        ClientAddressResolver resolver = ClientAddressResolver.fromConfig("::1");
        assertTrue(resolver.isTrusted(addr("::1")));
        assertFalse(resolver.isTrusted(addr("::2")));
    }

    // --- robustness: never throw, always fall back to the peer ---

    @Test
    void garbageInTheHeaderFallsBackToThePeer() {
        ClientAddressResolver resolver = ClientAddressResolver.fromConfig("127.0.0.1");
        assertEquals(PROXY, resolver.resolve(PROXY, LOCAL, header("not-an-address"), null));
        assertEquals(PROXY, resolver.resolve(PROXY, LOCAL, header(""), null));
        assertEquals(PROXY, resolver.resolve(PROXY, LOCAL, header("   ,  , "), null));
        assertEquals(PROXY, resolver.resolve(PROXY, LOCAL, Collections.emptyList(), null));
    }

    @Test
    void everyEntryBeingTrustedFallsBackToThePeer() {
        ClientAddressResolver resolver = ClientAddressResolver.fromConfig("127.0.0.1, 10.0.0.1");
        assertEquals(PROXY, resolver.resolve(PROXY, LOCAL, header("10.0.0.1"), null));
    }

    @Test
    void garbageInTheConfigIsSkippedWithoutBreakingValidEntries() {
        ClientAddressResolver resolver = ClientAddressResolver.fromConfig("nonsense, 127.0.0.1, 10.0.0.0/999, ,");
        assertTrue(resolver.isTrusted(PROXY));
        assertFalse(resolver.isTrusted(OUTSIDER));
    }

    @Test
    void hostnamesInTheConfigAreRejectedRatherThanResolved() {
        // Accepting hostnames would mean a DNS lookup during config parsing.
        ClientAddressResolver resolver = ClientAddressResolver.fromConfig("localhost");
        assertFalse(resolver.isTrusted(PROXY));
    }

    // --- an empty config still accepts a proxy on this machine ---

    @Test
    void loopbackIsAcceptedWithNoConfigurationAtAll() {
        // "the same machine" cannot be written as an address, so it is a built-in rule rather than a
        // default config value - listing 127.0.0.1 would be redundant and narrower than isLoopbackAddress.
        ClientAddressResolver resolver = ClientAddressResolver.fromConfig("");
        assertEquals(CLIENT, resolver.resolve(PROXY, LOCAL, header("203.0.113.7"), null));
        assertEquals(CLIENT, resolver.resolve(addr("127.0.0.5"), LOCAL, header("203.0.113.7"), null));
        assertEquals(CLIENT, resolver.resolve(addr("::1"), LOCAL, header("203.0.113.7"), null));
    }

    @Test
    void anEmptyConfigDoesNotTrustTheLocalNetwork() {
        // A LAN device has no localhost privileges today; trusting it would let it claim to be 127.0.0.1
        // and, with allow_no_password_on_localhost, become admin.
        ClientAddressResolver resolver = ClientAddressResolver.fromConfig("");
        assertFalse(resolver.isTrusted(addr("192.168.1.10")));
        assertEquals(addr("192.168.1.10"), resolver.resolve(addr("192.168.1.10"), LOCAL, header("127.0.0.1"), null));
    }

    // --- a proxy on this machine that targets a real interface instead of localhost ---

    @Test
    void aPeerAddressingItselfIsTreatedAsTheSameMachine() {
        // nginx doing proxy_pass to 192.168.1.50:2324 on this very host: source == destination.
        ClientAddressResolver resolver = ClientAddressResolver.fromConfig("");
        InetAddress own = addr("192.168.1.50");
        assertEquals(CLIENT, resolver.resolve(own, own, header("203.0.113.7"), null));
    }

    @Test
    void sameMachineWithoutHeadersIsNotTurnedIntoLoopback() {
        // Being on the same machine only decides whether the forwarding headers are believed. It must not
        // by itself make a caller look like localhost: a browser opened on the server and pointed at the
        // machine's own LAN address still has to log in.
        ClientAddressResolver resolver = ClientAddressResolver.fromConfig("");
        InetAddress own = addr("192.168.1.50");

        InetAddress resolved = resolver.resolve(own, own, null, null);

        assertEquals(own, resolved);
        assertFalse(resolved.isLoopbackAddress(), "must not be granted localhost trust");
    }

    @Test
    void aDifferentLanMachineIsStillNotTrusted() {
        // Same private range, but its source address is not the one it connected to.
        ClientAddressResolver resolver = ClientAddressResolver.fromConfig("");
        InetAddress neighbour = addr("192.168.1.77");
        assertEquals(neighbour, resolver.resolve(neighbour, addr("192.168.1.50"), header("127.0.0.1"), null));
    }

    @Test
    void anUnknownLocalAddressDoesNotWidenTrust() {
        ClientAddressResolver resolver = ClientAddressResolver.fromConfig("");
        assertEquals(OUTSIDER, resolver.resolve(OUTSIDER, null, header("127.0.0.1"), null));
    }

    @Test
    void hostnamesInTheHeaderAreRejectedRatherThanResolved() {
        ClientAddressResolver resolver = ClientAddressResolver.fromConfig("127.0.0.1");
        assertEquals(PROXY, resolver.resolve(PROXY, LOCAL, header("example.com"), null));
    }
}
