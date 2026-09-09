package pl.kuba6000.ae2webintegration.core;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import com.google.common.net.InetAddresses;

/**
 * Works out which address a request really came from.
 * <p>
 * Behind a reverse proxy every request reaches us from the proxy, so the TCP peer address is useless for
 * both the localhost trust decision and rate limiting. Proxies pass the original address in a header, but
 * a header is only as trustworthy as whoever set it: honouring it unconditionally would let anyone send
 * {@code X-Forwarded-For: 127.0.0.1} and be treated as localhost. So headers are read <b>only</b> when the
 * direct peer is a configured trusted proxy. That condition is the entire safety mechanism - do not
 * relax it.
 */
@SuppressWarnings("UnstableApiUsage")
public final class ClientAddressResolver {

    /** A configured trusted entry: an address plus how many leading bits of it are significant. */
    private static final class Entry {

        private final byte[] address;
        private final int prefixBits;

        private Entry(byte[] address, int prefixBits) {
            this.address = address;
            this.prefixBits = prefixBits;
        }

        @SuppressWarnings("PMD.AvoidMagicNumbers") // CIDR matching uses byte widths and masks.
        boolean matches(byte[] candidate) {
            // Different families (4 vs 16 bytes) never match.
            if (candidate.length != address.length) {
                return false;
            }
            int fullBytes = prefixBits / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (candidate[i] != address[i]) {
                    return false;
                }
            }
            int remainingBits = prefixBits % 8;
            if (remainingBits == 0) {
                return true;
            }
            int mask = 0xFF << (8 - remainingBits);
            return (candidate[fullBytes] & mask) == (address[fullBytes] & mask);
        }
    }

    private final List<Entry> trusted;

    private ClientAddressResolver(List<Entry> trusted) {
        this.trusted = trusted;
    }

    /**
     * @param trustedProxies comma-separated addresses and/or CIDR blocks. Empty means nothing is trusted,
     *                       so forwarding headers are ignored entirely. Hostnames are rejected rather
     *                       than resolved, to keep config parsing free of DNS.
     */
    public static ClientAddressResolver fromConfig(String trustedProxies) {
        List<Entry> entries = new ArrayList<>();
        if (trustedProxies != null) {
            for (String raw : trustedProxies.split(",")) {
                Entry entry = parseEntry(raw.trim());
                if (entry != null) {
                    entries.add(entry);
                }
            }
        }
        return new ClientAddressResolver(entries);
    }

    private static Entry parseEntry(String raw) {
        if (raw.isEmpty()) {
            return null;
        }
        String addressPart = raw;
        int prefixBits = -1;
        int slash = raw.lastIndexOf('/');
        if (slash >= 0) {
            addressPart = raw.substring(0, slash)
                .trim();
            try {
                prefixBits = Integer.parseInt(
                    raw.substring(slash + 1)
                        .trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        byte[] address = parseLiteral(addressPart);
        if (address == null) {
            return null;
        }
        int maxBits = address.length * 8; // NOPMD - Convert address bytes to bits.
        if (prefixBits < 0) {
            prefixBits = maxBits;
        } else if (prefixBits > maxBits) {
            return null;
        }
        return new Entry(address, prefixBits);
    }

    /** Literal addresses only - {@code InetAddress.getByName} would perform a DNS lookup for a hostname. */
    private static byte[] parseLiteral(String text) {
        if (text.isEmpty() || !InetAddresses.isInetAddress(text)) {
            return null;
        }
        return InetAddresses.forString(text)
            .getAddress();
    }

    public boolean isTrusted(InetAddress address) {
        if (address == null) {
            return false;
        }
        byte[] raw = address.getAddress();
        for (Entry entry : trusted) {
            if (entry.matches(raw)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A connection is from this very machine when it arrives over loopback, or when its source address is
     * the same address it was addressed to - which is what happens when a proxy on this host is pointed at
     * one of the machine's own interfaces instead of at localhost. Neither can be spoofed remotely: TCP
     * needs a handshake, and a SYN carrying one of our own addresses would be answered to ourselves.
     * <p>
     * Such a peer is trusted implicitly, so a same-machine proxy never needs configuring. It grants
     * nothing extra either - anything running on this host can already reach us over loopback.
     */
    private static boolean isSameMachine(InetAddress peer, InetAddress localAddress) {
        return peer.isLoopbackAddress() || peer.equals(localAddress);
    }

    /**
     * @param peer         the address the TCP connection actually came from
     * @param localAddress the local destination address, or {@code null} if unknown
     * @param forwardedFor {@code X-Forwarded-For} header values, or {@code null}
     * @param realIp       {@code X-Real-IP} header values, or {@code null}
     * @return the address to treat as the client. Falls back to {@code peer} whenever the headers cannot
     *         be trusted or understood; never throws.
     */
    public InetAddress resolve(InetAddress peer, InetAddress localAddress, List<String> forwardedFor,
        List<String> realIp) {
        if (!isTrusted(peer) && !isSameMachine(peer, localAddress)) {
            return peer;
        }
        InetAddress fromChain = firstUntrustedFromRight(forwardedFor);
        if (fromChain != null) {
            return fromChain;
        }
        InetAddress fromRealIp = firstParsable(realIp);
        return fromRealIp != null ? fromRealIp : peer;
    }

    /**
     * {@code X-Forwarded-For: client, proxy1, proxy2} grows left to right as it passes through hops, and
     * the original client is free to pre-populate it. Only the entries our own proxies appended can be
     * believed, so walk from the right and stop at the first address that is not one of ours.
     */
    private InetAddress firstUntrustedFromRight(List<String> forwardedFor) {
        if (forwardedFor == null) {
            return null;
        }
        List<String> hops = new ArrayList<>();
        for (String headerValue : forwardedFor) {
            if (headerValue == null) {
                continue;
            }
            for (String hop : headerValue.split(",")) {
                hops.add(hop.trim());
            }
        }
        for (int i = hops.size() - 1; i >= 0; i--) {
            byte[] raw = parseLiteral(hops.get(i));
            if (raw == null) {
                continue;
            }
            InetAddress candidate = toInetAddress(hops.get(i));
            if (candidate != null && !isTrusted(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static InetAddress firstParsable(List<String> values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value == null) {
                continue;
            }
            InetAddress parsed = toInetAddress(value.trim());
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    private static InetAddress toInetAddress(String text) {
        if (text.isEmpty() || !InetAddresses.isInetAddress(text)) {
            return null;
        }
        return InetAddresses.forString(text);
    }
}
