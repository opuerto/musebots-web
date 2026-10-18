package dev.musebots.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class OriginPolicyTest {

    private final OriginPolicy sameOrigin = OriginPolicy.sameOriginOnly();

    @Test
    void aPageServedByThisServerMayConnect() {
        assertTrue(sameOrigin.allows("https://band.example.com", "band.example.com"));
        assertTrue(sameOrigin.allows("http://localhost:7070", "localhost:7070"));
        assertTrue(sameOrigin.allows("HTTPS://Band.Example.com", "band.example.com"), "names are not case sensitive");
    }

    @Test
    void theSchemeIsNotComparedBecauseAProxyMayEndTheEncryption() {
        // The browser says https; the proxy passes the request on over http. The host is what identifies the page.
        assertTrue(sameOrigin.allows("https://band.example.com", "band.example.com"));
        assertTrue(sameOrigin.allows("http://band.example.com", "band.example.com"));
    }

    @Test
    void aPageFromAnotherSiteMayNot() {
        assertFalse(sameOrigin.allows("https://evil.example", "band.example.com"));
        assertFalse(sameOrigin.allows("https://band.example.com.evil.example", "band.example.com"), "a longer name that starts the same");
        assertFalse(sameOrigin.allows("https://evil.example/band.example.com", "band.example.com"));
        assertFalse(sameOrigin.allows("http://localhost:3000", "localhost:7070"), "another port is another site");
    }

    @Test
    void oddOriginsAreRefusedRatherThanTrusted() {
        assertFalse(sameOrigin.allows("null", "band.example.com"), "a sandboxed page sends the origin 'null'");
        assertFalse(sameOrigin.allows("not a url", "band.example.com"));
        assertFalse(sameOrigin.allows("https://band.example.com", null), "without a host there is nothing to compare with");
    }

    @Test
    void aClientThatSendsNoOriginIsNotABrowserAndIsLetIn() {
        assertTrue(sameOrigin.allows(null, "band.example.com"));
        assertTrue(sameOrigin.allows("", "band.example.com"));
        assertTrue(sameOrigin.allows("  ", "band.example.com"));
    }

    @Test
    void extraOriginsOnTheListMayConnectToo() {
        OriginPolicy policy = OriginPolicy.sameOriginAnd(Set.of("https://www.example.com/", "HTTPS://Other.Example.com"));
        assertTrue(policy.allows("https://www.example.com", "band.example.com"), "a trailing slash on the list does not matter");
        assertTrue(policy.allows("https://other.example.com", "band.example.com"), "nor does case");
        assertTrue(policy.allows("https://band.example.com", "band.example.com"), "and the server's own pages still may");
        assertFalse(policy.allows("https://evil.example", "band.example.com"));
        assertFalse(policy.allows("http://www.example.com", "band.example.com"), "the list is exact: another scheme is another origin");
    }

    @Test
    void readsACommaSeparatedSetting() {
        assertTrue(OriginPolicy.fromList(null).extraOrigins().isEmpty());
        assertTrue(OriginPolicy.fromList("   ").extraOrigins().isEmpty());
        OriginPolicy policy = OriginPolicy.fromList(" https://a.example.com , https://b.example.com,, ");
        assertTrue(policy.allows("https://a.example.com", "x"));
        assertTrue(policy.allows("https://b.example.com", "x"));
        assertFalse(policy.allows("https://c.example.com", "x"));
    }

    @Test
    void theCapOnPerformancesIsAtLeastOne() {
        assertThrows(IllegalArgumentException.class, () -> new ServerLimits(60, 200, 2, 0));
        assertTrue(new ServerLimits(60, 200, 2).maxPerformances() >= 1);
        assertTrue(ServerLimits.DEFAULT.withMaxPerformances(3).maxPerformances() == 3);
    }
}
