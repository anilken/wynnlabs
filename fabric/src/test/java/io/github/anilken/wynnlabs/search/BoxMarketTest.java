package io.github.anilken.wynnlabs.search;

import io.github.anilken.wynnlabs.model.Klass;
import io.github.anilken.wynnlabs.model.Slot;
import io.github.anilken.wynnlabs.model.Tier;
import io.github.anilken.wynnlabs.search.BoxMarket.Filter;
import io.github.anilken.wynnlabs.search.BoxMarket.Held;
import io.github.anilken.wynnlabs.search.BoxMarket.Listing;
import io.github.anilken.wynnlabs.search.BoxMarket.Row;
import io.github.anilken.wynnlabs.search.BoxMarket.Sort;
import io.github.anilken.wynnlabs.search.BoxMarket.State;
import io.github.anilken.wynnlabs.search.BoxMarket.StatRoll;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The box window's rows, filters, sorting, summary and highlights, on hand-made listings. */
class BoxMarketTest {

    private static final long NOW = 1_800_000_000_000L;

    private static BoxMarket.Item item(String name, boolean tradable) {
        return new BoxMarket.Item(name, Slot.LEGGINGS, Tier.UNIQUE, 68, Klass.NONE, 46, tradable);
    }

    private static Listing id(int price, double roll, long agoMin) {
        return new Listing(false, roll, List.of(), 0, 1, price, NOW - agoMin * 60_000L, false);
    }

    private static Listing unid(int price, long agoMin) {
        return new Listing(true, null, List.of(), 0, 1, price, NOW - agoMin * 60_000L, false);
    }

    private static Held held(List<Listing> l, Integer total) {
        return new Held(BoxMarket.markSold(l), total, false, false);
    }

    /** 7 identified listings from 40 E to 90 E, the best at 86 %, and two unidentified ones. */
    private static List<Listing> seven() {
        return List.of(id(40, 61, 1), id(50, 86.4, 2), id(60, 70, 3), id(70, 55, 4), id(75, 12, 5), id(80, 33, 6),
                id(90, 80, 7), unid(20, 1), unid(25, 2));
    }

    @Test
    void anItemWithListings() {
        Row r = BoxMarket.row(item("Atoll", true), held(seven(), null), null, true, Filter.ID, Sort.ROLL);
        assertEquals(State.READY, r.state());
        assertEquals(7, r.count());
        assertEquals(40, r.lowest());
        assertEquals(86, r.bestRoll());
        assertEquals(70, r.median());
    }

    @Test
    void theAnswersTotalIsTheCount() {
        List<Listing> twenty = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            twenty.add(id(100 + i, 50, 1));
        }
        Row r = BoxMarket.row(item("Atoll", true), new Held(twenty, 57, true, false), null, true, Filter.ALL, Sort.ROLL);
        assertEquals(57, r.count());
        assertEquals(20, r.held());
        assertTrue(r.hasMore());
    }

    @Test
    void noListingsKeepsThePastPrice() {
        Row r = BoxMarket.row(item("Atoll", true), held(List.of(), null), 640.0, true, Filter.ALL, Sort.ROLL);
        assertEquals(State.NONE, r.state());
        assertNull(r.lowest());
        assertEquals(640.0, r.pastPrice());
    }

    @Test
    void notAskedYetIsLoadingOrUnknown() {
        assertEquals(State.LOADING, BoxMarket.row(item("A", true), null, null, true, Filter.ALL, Sort.ROLL).state());
        assertEquals(State.UNKNOWN, BoxMarket.row(item("A", true), null, null, false, Filter.ALL, Sort.ROLL).state());
        assertEquals(State.UNKNOWN,
                BoxMarket.row(item("A", true), new Held(List.of(), null, false, true), null, true, Filter.ALL, Sort.ROLL).state());
        assertEquals(State.UNTRADABLE, BoxMarket.row(item("A", false), null, null, true, Filter.ALL, Sort.ROLL).state());
    }

    @Test
    void theUnidentifiedFilterCoversOnlyUnidentifiedListings() {
        Row r = BoxMarket.row(item("Atoll", true), held(seven(), 30), null, true, Filter.UNID, Sort.PRICE);
        assertEquals(2, r.count());                     // the total is for all listings, not this filter
        assertEquals(20, r.lowest());
        assertNull(r.bestRoll());
        assertTrue(r.listings().stream().allMatch(Listing::unidentified));
    }

    @Test
    void possiblySoldListingsAreShownButLeftOutOfPrices() {
        List<Listing> l = List.of(id(500, 60, 10), id(100, 90, 4 * 24 * 60));
        Row r = BoxMarket.row(item("Atoll", true), held(l, null), null, true, Filter.ALL, Sort.PRICE);
        assertEquals(2, r.count());
        assertEquals(500, r.lowest());
        assertEquals(60, r.bestRoll());
        assertTrue(r.listings().get(1).possiblySold());   // after the current one
    }

    @Test
    void priceSortPutsRowsWithoutListingsLast() {
        List<BoxMarket.Item> items = List.of(item("Empty A", true), item("Cheap", true), item("Empty B", true),
                item("Dear", true), item("Quest Thing", false));
        Map<String, Held> held = Map.of("Cheap", held(List.of(id(10, 50, 1)), null),
                "Dear", held(List.of(id(900, 99, 1)), null), "Empty A", held(List.of(), null), "Empty B", held(List.of(), null));
        List<Row> rows = BoxMarket.rows(items, held, Map.of(), true, Filter.ALL, Sort.PRICE);
        assertEquals(List.of("Cheap", "Dear", "Empty A", "Empty B", "Quest Thing"), rows.stream().map(Row::name).toList());
        List<Row> byRoll = BoxMarket.rows(items, held, Map.of(), true, Filter.ALL, Sort.ROLL);
        assertEquals("Dear", byRoll.get(0).name());
    }

    @Test
    void listingsByRollHighestFirstUnidentifiedLast() {
        Row r = BoxMarket.row(item("Atoll", true), held(seven(), null), null, true, Filter.ALL, Sort.ROLL);
        assertEquals(86.4, r.listings().get(0).overallRoll(), 1e-9);
        assertTrue(r.listings().get(r.listings().size() - 1).unidentified());
        assertTrue(r.listings().get(r.listings().size() - 2).unidentified());
        Row p = BoxMarket.row(item("Atoll", true), held(seven(), null), null, true, Filter.ALL, Sort.PRICE);
        assertEquals(20, p.listings().get(0).price());
    }

    @Test
    void highRollAtTheThreshold() {
        assertTrue(BoxMarket.highRoll(id(1, 80, 1), 80));
        assertFalse(BoxMarket.highRoll(id(1, 79, 1), 80));
        assertFalse(BoxMarket.highRoll(unid(1, 1), 80));
    }

    @Test
    void keyStatsByWeightThenValueFirstThree() {
        Listing l = new Listing(false, 70.0, List.of(new StatRoll("walkSpeed", 5.0, 20.0), new StatRoll("rawHealth", 120.0, 92.0),
                new StatRoll("manaRegen", 3.0, 40.0), new StatRoll("noRoll", 1.0, null), new StatRoll("lootBonus", 9.0, 10.0)),
                0, 1, 10, NOW, false);
        List<StatRoll> k = BoxMarket.keyStats(l, s -> s.equals("manaRegen") ? 2.0 : s.equals("walkSpeed") ? 1.0 : 0, 3);
        assertEquals(List.of("manaRegen", "walkSpeed", "rawHealth"), k.stream().map(StatRoll::key).toList());
    }

    @Test
    void summaryNeverShowsZeroForUnknown() {
        Row none = BoxMarket.row(item("A", true), held(List.of(), null), null, true, Filter.ALL, Sort.ROLL);
        BoxMarket.Summary s = BoxMarket.summary(List.of(none), null);
        assertNull(s.average());
        assertNull(s.best());
        assertNull(s.unidentified());
        Row a = BoxMarket.row(item("A", true), held(List.of(id(100, 50, 1)), null), null, true, Filter.ALL, Sort.ROLL);
        Row b = BoxMarket.row(item("B", true), held(List.of(id(300, 50, 1)), null), null, true, Filter.ALL, Sort.ROLL);
        BoxMarket.Summary t = BoxMarket.summary(List.of(a, b, none), 150.0);
        assertEquals(200.0, t.average(), 1e-9);
        assertEquals(300.0, t.best(), 1e-9);
        assertEquals(150.0, t.unidentified(), 1e-9);
    }

    @Test
    void ownRollAgainstTheMarket() {
        List<Listing> l = new ArrayList<>(seven());
        l.add(id(5, 99, 4 * 24 * 60));                       // possibly sold: not counted
        assertEquals(2, BoxMarket.better(BoxMarket.markSold(l), 72));   // 86.4 % and 80 %
    }
}
