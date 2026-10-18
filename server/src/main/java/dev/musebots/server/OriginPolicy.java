package dev.musebots.server;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Which web pages may open a connection to the band. A browser always says which page it is opening the connection for
 * (the {@code Origin} header), and a WebSocket is not covered by the usual cross-site rules, so without this check any website
 * could make its visitors' browsers start performances on this server.
 *
 * <p>A page is let in if it is served from this very server (its origin names the host the request was sent to), or if
 * its origin is on the list of extra origins. A client that sends no origin is not a browser, so it cannot be a victim of
 * that attack and is let in.
 */
public final class OriginPolicy {

    private final Set<String> allowed;

    private OriginPolicy(Set<String> allowed) {
        this.allowed = allowed;
    }

    /** Only pages served by this server itself. */
    public static OriginPolicy sameOriginOnly() {
        return new OriginPolicy(Set.of());
    }

    /** Pages served by this server, and pages on these origins, e.g. {@code https://band.example.com}. */
    public static OriginPolicy sameOriginAnd(Set<String> origins) {
        return new OriginPolicy(origins.stream().map(OriginPolicy::normalise).collect(Collectors.toUnmodifiableSet()));
    }

    /** Reads a comma-separated list such as the ALLOWED_ORIGINS setting; null or blank means none. */
    public static OriginPolicy fromList(String commaSeparated) {
        if (commaSeparated == null || commaSeparated.isBlank()) {
            return sameOriginOnly();
        }
        return sameOriginAnd(Arrays.stream(commaSeparated.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toSet()));
    }

    public Set<String> extraOrigins() {
        return allowed;
    }

    /**
     * @param origin the Origin header, or null if the client sent none
     * @param host   the Host header: the name (and port) the client used to reach this server
     */
    public boolean allows(String origin, String host) {
        if (origin == null || origin.isBlank()) {
            return true; // not a browser
        }
        if (allowed.contains(normalise(origin))) {
            return true;
        }
        return host != null && sameAuthority(origin, host);
    }

    /** True when the origin's host and port are those of the Host header; the scheme is not compared, as a proxy may terminate TLS. */
    private static boolean sameAuthority(String origin, String host) {
        try {
            URI uri = URI.create(origin.trim());
            if (uri.getHost() == null) {
                return false;
            }
            String authority = uri.getHost().toLowerCase(Locale.ROOT) + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
            return authority.equals(host.trim().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String normalise(String origin) {
        String trimmed = origin.trim().toLowerCase(Locale.ROOT);
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
