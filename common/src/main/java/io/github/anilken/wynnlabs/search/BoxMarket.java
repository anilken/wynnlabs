package io.github.anilken.wynnlabs.search;

import io.github.anilken.wynnlabs.model.Klass;
import io.github.anilken.wynnlabs.model.Slot;
import io.github.anilken.wynnlabs.model.Tier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * The box contents window's model: every item a gear box can hold, with its current Trade Market
 * listings as Wynnventory reported them, filtered (all, identified, unidentified) and sorted (by
 * roll or by price). Pure: the window hands in the box's items and what the listings store holds,
 * and draws what comes out. Listings seen more than 3 days before the item's newest one are
 * marked possibly sold: shown, but left out of every price.
 */
public final class BoxMarket {

    private BoxMarket() {}

    /** One rolled stat of a listing: Wynnventory's stat key, its value and its roll %, either null when missing. */
    public record StatRoll(String key, Double value, Double percent) {}

    /**
     * One listing, without anything about its seller. {@code overallRoll} is the % shown on the
     * item (null for an unidentified one or when missing); {@code seenAt} epoch ms, 0 when unknown.
     */
    public record Listing(boolean unidentified, Double overallRoll, List<StatRoll> statRolls, int rerolls, int amount,
            int price, long seenAt, boolean possiblySold) {}

    /** An item the box can hold, as the gear data describes it. */
    public record Item(String name, Slot slot, Tier tier, int level, Klass klass, int identifyCost, boolean tradable) {}

    /**
     * What the listings store holds for one item: the listings of the pages fetched, the total
     * the answer reported (null when it gave none), whether more pages exist, and whether the
     * last request for it failed.
     */
    public record Held(List<Listing> listings, Integer total, boolean hasMore, boolean failed) {}

    /** LOADING: asked, no answer yet; READY: listings held; NONE: no listing now; UNKNOWN: cannot be asked; UNTRADABLE. */
    public enum State { LOADING, READY, NONE, UNKNOWN, UNTRADABLE }

    public enum Filter { ALL, ID, UNID }

    public enum Sort { ROLL, PRICE }

    /**
     * One row of the item list under a filter. {@code count}: listings under the filter (the
     * answer's total when the filter is ALL and a total was given); {@code lowest}, {@code median},
     * {@code bestRoll}: from the listings that are not possibly sold, null when none;
     * {@code listings}: the ones the filter keeps, sorted; {@code held}: listings held under the
     * filter; {@code pastPrice}: an older cached price, for a row without current listings.
     */
    public record Row(Item item, State state, int count, Integer lowest, Integer median, Integer bestRoll, int held,
            boolean hasMore, List<Listing> listings, Double pastPrice) {

        public String name() {
            return item.name();
        }
    }

    /** The summary chips: average and best lowest price over the rows, and the value sold unidentified; null = "?". */
    public record Summary(Double average, Double best, Double unidentified) {}

    /** How long before the newest listing one counts as possibly sold. */
    public static final long SOLD_AFTER = 3 * 24 * 3_600_000L;

    /** The listings with {@link Listing#possiblySold} set from the newest one's time (3-day window). */
    public static List<Listing> markSold(List<Listing> listings) {
        long newest = 0;
        for (Listing l : listings) {
            newest = Math.max(newest, l.seenAt());
        }
        long cutoff = newest > 0 ? newest - SOLD_AFTER : 0;
        List<Listing> out = new ArrayList<>(listings.size());
        for (Listing l : listings) {
            boolean old = l.seenAt() > 0 && l.seenAt() < cutoff;
            out.add(old == l.possiblySold() ? l : new Listing(l.unidentified(), l.overallRoll(), l.statRolls(), l.rerolls(),
                    l.amount(), l.price(), l.seenAt(), old));
        }
        return out;
    }

    /** Whether the filter keeps this listing. */
    public static boolean keeps(Filter f, Listing l) {
        return f == Filter.ALL || (f == Filter.UNID) == l.unidentified();
    }

    /**
     * The rows for the box's items, one per item in the given order and then sorted. {@code held}
     * by name (absent: never answered); {@code past} by name, an older cached price; {@code live}:
     * whether listings can be asked for at all.
     */
    public static List<Row> rows(List<Item> items, Map<String, Held> held, Map<String, Double> past, boolean live,
            Filter filter, Sort sort) {
        List<Row> out = new ArrayList<>(items.size());
        for (Item it : items) {
            out.add(row(it, held.get(it.name()), past.get(it.name()), live, filter, sort));
        }
        out.sort(rowOrder(sort));
        return out;
    }

    static Row row(Item it, Held h, Double past, boolean live, Filter filter, Sort sort) {
        if (!it.tradable()) {
            return new Row(it, State.UNTRADABLE, 0, null, null, null, 0, false, List.of(), null);
        }
        if (h == null || h.failed() && h.listings().isEmpty()) {
            State s = h == null && live ? State.LOADING : State.UNKNOWN;
            return new Row(it, s, 0, null, null, null, 0, false, List.of(), past);
        }
        if (h.listings().isEmpty()) {
            return new Row(it, State.NONE, 0, null, null, null, 0, false, List.of(), past);
        }
        List<Listing> kept = new ArrayList<>();
        List<Integer> prices = new ArrayList<>();
        Integer best = null;
        for (Listing l : h.listings()) {
            if (!keeps(filter, l)) {
                continue;
            }
            kept.add(l);
            if (l.possiblySold()) {
                continue;
            }
            prices.add(l.price());
            if (l.overallRoll() != null && !l.unidentified()) {
                int r = (int) Math.floor(l.overallRoll());
                best = best == null ? r : Math.max(best, r);
            }
        }
        prices.sort(null);
        Integer lowest = prices.isEmpty() ? null : prices.get(0);
        Integer median = prices.isEmpty() ? null : prices.get(prices.size() / 2);
        int count = kept.size();
        if (filter == Filter.ALL && h.total() != null) {
            count = Math.max(count, h.total());
        }
        kept.sort(listingOrder(sort));
        return new Row(it, State.READY, count, lowest, median, best, kept.size(), h.hasMore(), List.copyOf(kept),
                lowest == null ? past : null);
    }

    /**
     * Rows by best roll (ROLL) or lowest price (PRICE); rows without that number, then
     * untradable ones, last; then by name.
     */
    static Comparator<Row> rowOrder(Sort sort) {
        Comparator<Row> byNumber = sort == Sort.ROLL
                ? Comparator.comparing(Row::bestRoll, Comparator.nullsLast(Comparator.reverseOrder()))
                : Comparator.comparing(Row::lowest, Comparator.nullsLast(Comparator.naturalOrder()));
        return Comparator.comparing((Row r) -> r.state() == State.UNTRADABLE).thenComparing(byNumber)
                .thenComparing(Row::name);
    }

    /**
     * Listings by overall roll, highest first (ROLL) or price, lowest first (PRICE). Possibly
     * sold ones after the current ones; under ROLL unidentified ones and missing rolls last.
     */
    public static Comparator<Listing> listingOrder(Sort sort) {
        Comparator<Listing> c = Comparator.comparing(Listing::possiblySold);
        if (sort == Sort.ROLL) {
            return c.thenComparing(Listing::unidentified)
                    .thenComparing(Listing::overallRoll, Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparingInt(Listing::price);
        }
        return c.thenComparingInt(Listing::price)
                .thenComparing(Listing::overallRoll, Comparator.nullsLast(Comparator.reverseOrder()));
    }

    /** A listing to highlight: its overall roll at or above the threshold (rules boxHighRoll, 80). */
    public static boolean highRoll(Listing l, double threshold) {
        return !l.unidentified() && l.overallRoll() != null && l.overallRoll() >= threshold;
    }

    /** How many current (not possibly sold) identified listings rolled higher than {@code own} %. */
    public static int better(List<Listing> listings, double own) {
        int n = 0;
        for (Listing l : listings) {
            if (!l.possiblySold() && !l.unidentified() && l.overallRoll() != null && l.overallRoll() > own) {
                n++;
            }
        }
        return n;
    }

    /**
     * The stats to show beside a listing: the ones with a roll %, by the build's weight for the
     * stat ({@code weight}, larger first, 0 when it has none), then by the largest value; the first
     * {@code n}.
     */
    public static List<StatRoll> keyStats(Listing l, ToDoubleFunction<String> weight, int n) {
        List<StatRoll> with = new ArrayList<>();
        for (StatRoll s : l.statRolls()) {
            if (s.percent() != null) {
                with.add(s);
            }
        }
        with.sort(Comparator.comparingDouble((StatRoll s) -> -Math.abs(weight.applyAsDouble(s.key())))
                .thenComparingDouble(s -> s.value() == null ? 0 : -Math.abs(s.value())));
        return with.size() > n ? List.copyOf(with.subList(0, n)) : List.copyOf(with);
    }

    /**
     * The summary chips over the rows as filtered: the mean and the highest of the rows' lowest
     * prices (rows without one left out, never counted as 0), and {@code unidentified}, the
     * value the box verdict gives it sold unidentified. Null for "?".
     */
    public static Summary summary(List<Row> rows, Double unidentified) {
        double sum = 0;
        int n = 0;
        Double best = null;
        for (Row r : rows) {
            if (r.lowest() == null) {
                continue;
            }
            sum += r.lowest();
            n++;
            best = best == null ? r.lowest() : Math.max(best, r.lowest());
        }
        return new Summary(n == 0 ? null : sum / n, best, unidentified);
    }
}
