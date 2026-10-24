package io.github.anilken.wynnlabs.market;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import io.github.anilken.wynnlabs.game.Fixtures;
import io.github.anilken.wynnlabs.search.BoxMarket;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The box window's listings store on canned Wynnventory answers: what is kept of an answer (this
 * item only, no shinies, no seller), the 3-hour mark, paging on demand, freshness, and "Yenile".
 * Requests go to a recorder instead of the network.
 */
class ListingsTest {

    /** Records what would be sent; a name already waiting is not sent again, as Prices' queue does. */
    static final class Recorder implements Listings.Sender {
        boolean live = true;
        final List<String> sent = new ArrayList<>();
        final Set<String> waiting = new HashSet<>();

        @Override
        public boolean live() {
            return live;
        }

        @Override
        public boolean queue(String name, int page) {
            if (!live) {
                return false;
            }
            String token = name + "#" + page;
            if (waiting.add(token)) {
                sent.add(token);
            }
            return true;
        }
    }

    private Listings.Sender before;
    private Recorder rec;

    @BeforeEach
    void setUp() {
        before = Listings.sender;
        rec = new Recorder();
        Listings.sender = rec;
        Listings.clear();
    }

    @AfterEach
    void tearDown() {
        Listings.sender = before;
        Listings.clear();
    }

    static JsonObject answer(String file) throws IOException {
        String text = Files.readString(Fixtures.root().resolve("wynnventory").resolve(file), StandardCharsets.UTF_8);
        return new Gson().fromJson(text, JsonObject.class);
    }

    @Test
    void anAnswerKeepsThisItemOnlyWithoutShiniesOrSellers() throws IOException {
        Listings.Page p = Listings.parse("Atoll", answer("listings-atoll-p1.json"));
        assertEquals(5, p.listings().size());           // the shiny and "Atoll Shard" left out
        assertEquals(9, p.total());
        assertEquals(Boolean.TRUE, p.hasMore());
        assertEquals(1, p.listings().stream().filter(BoxMarket.Listing::unidentified).count());
        BoxMarket.Listing top = p.listings().get(0);
        assertEquals(86.4, top.overallRoll(), 1e-9);
        assertEquals(2, top.rerolls());
        assertEquals(4, top.statRolls().size());
        assertEquals("rawHealth", top.statRolls().get(0).key());
        assertEquals(92.0, top.statRolls().get(0).percent(), 1e-9);
        assertFalse(p.toString().contains("SellerX"));
        Listings.put("Atoll", 1, p, System.currentTimeMillis());
        assertFalse(Listings.held("Atoll").toString().contains("SellerX"));
    }

    @Test
    void listingsThreeDaysOlderThanTheNewestAreMarked() throws IOException {
        Listings.put("Atoll", 1, Listings.parse("Atoll", answer("listings-atoll-p1.json")), System.currentTimeMillis());
        List<BoxMarket.Listing> held = Listings.held("Atoll").listings();
        assertEquals(1, held.stream().filter(BoxMarket.Listing::possiblySold).count());
        assertTrue(held.stream().filter(BoxMarket.Listing::possiblySold).allMatch(l -> l.price() == 90));
    }

    @Test
    void aRecentAnswerIsNotAskedAgain() throws IOException {
        long now = System.currentTimeMillis();
        Listings.Page p = Listings.parse("Atoll", answer("listings-atoll-p1.json"));
        Listings.put("Atoll", 1, p, now - 2 * 60_000L);
        Listings.put("Canopy", 1, Listings.parse("Canopy", answer("listings-canopy.json")), now - 10 * 60_000L);
        Listings.request(List.of("Atoll", "Canopy", "Prayer"));
        assertEquals(List.of("Canopy#1", "Prayer#1"), rec.sent);
    }

    @Test
    void liveLookupsOffSendNothing() {
        rec.live = false;
        Listings.request(List.of("Atoll"));
        assertFalse(Listings.requestMore("Atoll"));
        Listings.Refresh r = Listings.refresh("box", List.of("Atoll", "Canopy"), "Atoll");
        assertTrue(rec.sent.isEmpty());
        assertFalse(r.running());
        assertEquals(2, r.done());
    }

    @Test
    void morePagesAreAskedOneAtATimeUntilTheLast() throws IOException {
        Listings.put("Atoll", 1, Listings.parse("Atoll", answer("listings-atoll-p1.json")), System.currentTimeMillis());
        assertTrue(Listings.requestMore("Atoll"));
        assertFalse(Listings.requestMore("Atoll"));     // page 2 is waiting: not asked twice
        assertEquals(List.of("Atoll#2"), rec.sent);
        Listings.put("Atoll", 2, Listings.parse("Atoll", answer("listings-atoll-p2.json")), System.currentTimeMillis());
        Listings.finished("Atoll", 2, true);
        BoxMarket.Held h = Listings.held("Atoll");
        assertEquals(7, h.listings().size());
        assertEquals(9, h.total());
        assertFalse(h.hasMore());
        assertFalse(Listings.requestMore("Atoll"));     // the last page: nothing more
        assertEquals(1, rec.sent.size());
    }

    @Test
    void aFailedPageStopsMore() throws IOException {
        Listings.put("Atoll", 1, Listings.parse("Atoll", answer("listings-atoll-p1.json")), System.currentTimeMillis());
        assertTrue(Listings.requestMore("Atoll"));
        Listings.finished("Atoll", 2, false);
        assertFalse(Listings.held("Atoll").hasMore());
        assertFalse(Listings.requestMore("Atoll"));
    }

    @Test
    void extraPagesGoWithTheFirstOnesFreshness() throws IOException {
        long old = System.currentTimeMillis() - 10 * 60_000L;
        Listings.put("Atoll", 1, Listings.parse("Atoll", answer("listings-atoll-p1.json")), old);
        Listings.put("Atoll", 2, Listings.parse("Atoll", answer("listings-atoll-p2.json")), old);
        BoxMarket.Held h = Listings.held("Atoll");
        assertEquals(5, h.listings().size());
        assertTrue(h.hasMore());
    }

    @Test
    void aFailedFirstPageIsKnownAsFailed() {
        Listings.finished("Prayer", 1, false);
        assertTrue(Listings.held("Prayer").failed());
        assertNull(Listings.held("Atoll"));
    }

    @Test
    void refreshAsksSelectedFirstOnceEachAndDropsExtraPages() throws IOException {
        long now = System.currentTimeMillis();
        Listings.put("Atoll", 1, Listings.parse("Atoll", answer("listings-atoll-p1.json")), now - 60_000L);
        Listings.put("Atoll", 2, Listings.parse("Atoll", answer("listings-atoll-p2.json")), now - 60_000L);
        rec.queue("Prayer", 1);                         // already waiting from elsewhere
        rec.sent.clear();
        Listings.Refresh r = Listings.refresh("LEGGINGS:UNIQUE:66-70", List.of("Atoll", "Canopy", "Prayer"), "Canopy");
        assertEquals(List.of("Canopy#1", "Atoll#1"), rec.sent);
        assertEquals(5, Listings.held("Atoll").listings().size());
        assertTrue(r.running());
        assertEquals(3, r.total());
        // a second press while it runs: the same refresh, nothing sent
        assertSame(r, Listings.refresh("LEGGINGS:UNIQUE:66-70", List.of("Atoll", "Canopy", "Prayer"), "Atoll"));
        assertEquals(2, rec.sent.size());
        for (String n : List.of("Canopy", "Atoll", "Prayer")) {
            Listings.finished(n, 1, true);
        }
        assertEquals(3, r.done());
        assertFalse(r.running());
        assertSame(r, Listings.refreshOf("LEGGINGS:UNIQUE:66-70"));
    }

    @Test
    void theUploadersStatShapeIsRead() {
        // Wynnventory's mod writes each stat from its ItemStat record: apiName, statRoll, rollPercentage
        String body = "{\"data\":[{\"name\":\"Atoll\",\"listing_price\":4096,\"overall_roll\":75.5,"
                + "\"stat_rolls\":[{\"apiName\":\"rawHealth\",\"displayName\":\"Health\",\"statRoll\":120,"
                + "\"rollPercentage\":81.5}]}],\"pagination\":{\"page\":1,\"page_size\":50,\"total_items\":1,\"total_pages\":1}}";
        Listings.Page p = Listings.parse("Atoll", new Gson().fromJson(body, JsonObject.class));
        BoxMarket.StatRoll r = p.listings().get(0).statRolls().get(0);
        assertEquals("rawHealth", r.key());
        assertEquals(120.0, r.value(), 1e-9);
        assertEquals(81.5, r.percent(), 1e-9);
        assertEquals(1, p.total());
        assertEquals(Boolean.FALSE, p.hasMore());
    }
}
