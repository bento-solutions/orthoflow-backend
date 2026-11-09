package com.orthoflow.platform.web;

import jakarta.servlet.http.HttpServletRequest;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HexFormat;

/**
 * Where a request came from, in one place.
 *
 * <p>Behind Traefik the TCP peer is Traefik. {@code server.forward-headers-strategy: native} makes
 * Tomcat's RemoteIpValve replace it with the client address from {@code X-Forwarded-For}, but only
 * when the peer is a trusted internal proxy, walking the header from the right, so a client cannot
 * pick the address it is counted under. Reading the header by hand did not do that: the rate
 * limiters took the first entry (the one a client writes) and the audit log the last, and sign-ins
 * recorded Traefik's own address.
 */
public final class ClientAddress {

    private ClientAddress() {
    }

    /** The client's address as the valve resolved it. */
    public static String of(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    /**
     * What a per-client limit counts under: the IPv4 address, or the /64 an IPv6 address belongs to.
     * A subscriber is handed a whole /64, so counting single IPv6 addresses gave anyone 2^64 fresh
     * allowances.
     */
    public static String limitKey(HttpServletRequest request) {
        String address = of(request);
        if (address == null || address.indexOf(':') < 0) {
            return address;
        }
        try {
            // A literal is parsed, never looked up: there is no name to resolve.
            byte[] bytes = InetAddress.getByName(address.startsWith("[") ? address.substring(1, address.length() - 1) : address).getAddress();
            if (bytes.length != 16) {
                return address;
            }
            return HexFormat.of().formatHex(bytes, 0, 8) + "::/64";
        } catch (UnknownHostException | IllegalArgumentException e) {
            return address;
        }
    }
}
