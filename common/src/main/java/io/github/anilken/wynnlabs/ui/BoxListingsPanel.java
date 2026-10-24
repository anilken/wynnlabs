package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.core.Rules;
import io.github.anilken.wynnlabs.market.Listings;
import io.github.anilken.wynnlabs.market.Prices;
import io.github.anilken.wynnlabs.search.BoxMarket;

import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/**
 * The box window's listings of the chosen item: per listing its overall roll as a bar coloured
 * by the roll, the roll % of its key stats, rerolls, price and how long ago it was seen. High
 * rolls (rules boxHighRoll, 80 %) are lit; listings seen 3+ days before the newest are faded and
 * marked "may be sold". Under the list, "x / total" and a "more" row while the market has more
 * pages; that row, or scrolling to the end, asks for the next page. No seller is ever shown.
 * Items without rolls (ingredients, materials) get a one-line table instead ({@link BoxPlainRows}).
 */
final class BoxListingsPanel {

    static final int ROW = 22;
    private static final int MORE = 14;

    private final BoxScreen s;
    private final ScrollBar bar = new ScrollBar();
    private int scroll;
    private int max;
    private int[] area = new int[4];

    BoxListingsPanel(BoxScreen s) {
        this.s = s;
    }

    /** Back to the top (another item was chosen). */
    void reset() {
        scroll = 0;
    }

    void draw(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
        area = new int[] {x, y, w, h};
        BoxMarket.Row r = s.selectedRow();
        if (r == null) {
            return;
        }
        boolean plain = !s.rolled();
        if (!plain) {
            // the head: the item and how many of its listings are held of the market's total
            String count = L.t("wynnlabs.box.held_of", r.held(), Math.max(r.held(), r.count()));
            int cw = s.font().width(count);
            s.ink(g, count, x + w - cw, y, Theme.INK_3);
            s.ink(g, s.fit(r.name(), w - cw - 6), x, y, HomePart.tierColour(r.item().tier()));
        }
        int head = plain ? BoxPlainRows.HEAD : 12;     // no rolls: the window's header names the item already
        int top = y + head;
        int listH = h - head;
        if (r.listings().isEmpty()) {
            String why = switch (r.state()) {
                case UNTRADABLE -> L.t("wynnlabs.box.untradable");
                case LOADING -> L.t("wynnlabs.box.loading");
                case UNKNOWN -> Listings.live() ? L.t("wynnlabs.box.price_unknown") : L.t("wynnlabs.box.live_off");
                default -> L.t("wynnlabs.box.no_listings");
            };
            s.ink(g, s.fit(why, w), x, top + 4, Theme.INK_3);
            if (r.pastPrice() != null) {
                s.ink(g, s.fit(Prices.past("~" + Prices.fmt(r.pastPrice())), w), x, top + 16, Theme.PRICE);
            }
            return;
        }
        boolean more = r.hasMore();
        int rowH = plain ? BoxPlainRows.ROW : ROW;
        int total = r.listings().size() * rowH + (more ? MORE : 0);
        max = ScrollBar.max(listH, total);
        scroll = Math.max(0, Math.min(max, scroll));
        int rw = w - ScrollBar.W - 2;
        if (plain) {
            BoxPlainRows.header(s, g, x, y, rw);
        }
        g.enableScissor(x, top, x + w, top + listH);
        int ry = top - scroll;
        double high = BoxScreen.highRoll();
        for (int i = 0; i < r.listings().size(); i++) {
            if (ry + rowH > top && ry < top + listH) {
                if (plain) {
                    BoxPlainRows.row(s, g, r.listings().get(i), i, x, ry, rw);
                } else {
                    listing(g, r.listings().get(i), i, x, ry, rw, high);
                }
            }
            ry += rowH;
        }
        if (more) {
            boolean waiting = Listings.loadingMore(r.name());
            String t = waiting ? L.t("wynnlabs.box.loading") : L.t("wynnlabs.box.more");
            boolean hover = !waiting && mx >= x && mx < x + rw && my >= Math.max(ry, top) && my < Math.min(ry + MORE, top + listH);
            s.ink(g, t, x + (rw - s.font().width(t)) / 2, ry + 3, hover ? Theme.GOLD_EDGE : Theme.LINK);
            if (!waiting && ry < top + listH) {
                String name = r.name();
                int ty = Math.max(ry, top);
                s.hits.add(new BoxScreen.Hit(x, ty, rw, Math.min(ry + MORE, top + listH) - ty, () -> Listings.requestMore(name)));
            }
        }
        g.disableScissor();
        bar.place(x + w - ScrollBar.W, top, listH, listH, total, scroll);
        bar.draw(g, mx, my);
    }

    private void listing(GuiGraphics g, BoxMarket.Listing l, int i, int x, int y, int w, double high) {
        boolean lit = BoxMarket.highRoll(l, high);
        if (lit) {
            g.fill(x, y, x + w, y + ROW, Theme.SELECTED);
            g.fill(x, y, x + 2, y + ROW, Theme.GOLD_DEEP);
        } else {
            Kit.row(g, x, y, w, ROW, i, false);
        }
        // line 1: the roll (or "unidentified"), then amount and price on the right
        if (l.unidentified() || l.overallRoll() == null) {
            s.ink(g, l.unidentified() ? L.t("wynnlabs.box.unidentified") : L.t("wynnlabs.box.no_roll"), x + 4, y + 2, Theme.INK_3);
        } else {
            double roll = l.overallRoll();
            int[] c = rollColours(roll);
            Kit.bar(g, x + 4, y + 3, 40, 7, roll / 100.0, c[0], c[1], c[2], false);
            s.ink(g, BoxScreen.pct(Math.round(roll)), x + 48, y + 2, lit ? Theme.GOOD : Theme.INK);
        }
        String price = exact(l.price()) + (l.amount() > 1 ? " ×" + l.amount() : "");
        int pw = s.font().width(price);
        s.ink(g, price, x + w - 4 - pw, y + 2, Theme.PRICE);
        // line 2: the key stats, then rerolls and age (or "may be sold")
        List<String> tail = new ArrayList<>();
        if (l.rerolls() > 0) {
            tail.add(L.n("wynnlabs.box.rerolls", l.rerolls()));
        }
        if (l.possiblySold()) {
            tail.add(L.t("wynnlabs.box.possibly_sold"));
        }
        if (l.seenAt() > 0) {
            tail.add(MenuPaint.ago(Math.min(l.seenAt(), System.currentTimeMillis())));
        }
        String right = String.join(" · ", tail);
        int tw = s.smallWidth(right);
        if (!right.isEmpty()) {
            s.small(g, right, x + w - 4 - tw, y + 12, l.possiblySold() ? Theme.WARN : Theme.INK_3);
        }
        List<String> stats = new ArrayList<>();
        for (BoxMarket.StatRoll st : BoxMarket.keyStats(l, Rules::statWeight, 3)) {
            stats.add(SearchResultsPanel.statName(st.key()) + " " + BoxScreen.pct(Math.round(st.percent())));
        }
        if (!stats.isEmpty()) {
            s.small(g, s.fit(String.join(" · ", stats), (int) ((w - 12 - tw) / MenuPaint.DESC_SCALE)), x + 4, y + 12, Theme.INK_2);
        }
        if (l.possiblySold()) {
            g.fill(x, y, x + w, y + ROW, 0x99D8C595);          // faded: paper over it
        }
    }

    /** One item: its icon, its name in its rarity's colour, its tier and level. */
    void singleHeader(GuiGraphics g, int x, int y, int room, BoxScreen.Single single, net.minecraft.world.item.ItemStack icon) {
        BoxMarket.Item it = single.item();
        g.renderItem(icon, x, y - 5);
        String stars = BoxPlainRows.stars(it.name());
        String name = s.fit(it.name(), room - 20 - s.font().width(stars));
        s.ink(g, name, x + 20, y, HomePart.tierColour(it.tier()));
        if (!stars.isEmpty()) {
            s.ink(g, stars, x + 20 + s.font().width(name), y, Theme.GOLD_DEEP);
            name += stars;
        }
        String rest = (it.tier() == io.github.anilken.wynnlabs.model.Tier.NORMAL ? "" : " · " + MenuPaint.nice(it.tier().name()))
                + (it.level() > 0 ? " · Lv " + it.level() : "");
        s.ink(g, s.fit(rest, room - 20 - s.font().width(name)), x + 20 + s.font().width(name), y, Theme.INK_2);
    }

    /**
     * One item's chips: its listing count, lowest and middle price, best roll, and for a rolled
     * piece its own roll against the market ("Seninki %72 · piyasada 3 daha iyi"). An item
     * without rolls gets a single chip: count, lowest price and total stock.
     */
    void singleChips(GuiGraphics g, int x, int y, BoxScreen.Single single) {
        BoxMarket.Row r = s.rows.isEmpty() ? null : s.rows.get(0);
        int cx = x;
        int count = r == null ? 0 : r.count();
        String lowest = BoxScreen.money(r == null || r.lowest() == null ? null : (double) r.lowest());
        if (!s.rolled()) {
            // no rolls: one chip, "8 ilan · En düşük 44 EB · Stok 137"
            String t = L.n("wynnlabs.box.listings", count) + " · " + L.t("wynnlabs.box.lowest", lowest)
                    + (r == null ? "" : " · " + L.t("wynnlabs.box.stock", BoxPlainRows.stock(r.listings())));
            s.chip(g, cx, y, t, Theme.PLAQUE, Theme.INK);
            return;
        }
        cx += s.chip(g, cx, y, L.n("wynnlabs.box.listings", count), Theme.PLAQUE, Theme.INK) + 4;
        cx += s.chip(g, cx, y, L.t("wynnlabs.box.lowest", lowest), Theme.PLAQUE, Theme.INK) + 4;
        cx += s.chip(g, cx, y, L.t("wynnlabs.box.median", BoxScreen.money(r == null || r.median() == null ? null : (double) r.median())),
                Theme.PLAQUE, Theme.INK) + 4;
        if (r != null && r.bestRoll() != null) {
            cx += s.chip(g, cx, y, L.t("wynnlabs.box.best_roll", BoxScreen.pct(r.bestRoll())), Theme.PLAQUE, Theme.INK) + 4;
        }
        if (single.ownRoll() != null && r != null) {
            int better = BoxMarket.better(r.listings(), single.ownRoll());
            s.chip(g, cx, y, L.t("wynnlabs.box.own_roll", BoxScreen.pct(Math.round(single.ownRoll())), better), Theme.SEAL_2, Theme.SEAL_TEXT);
        }
    }

    /** The bar's three tones for a roll: red under 50 %, amber under the high-roll mark, green above. */
    private static int[] rollColours(double roll) {
        if (roll >= BoxScreen.highRoll()) {
            return new int[] {Theme.GREEN_HI, Theme.GREEN, Theme.GREEN_LO};
        }
        if (roll >= 50) {
            return new int[] {Theme.AMBER_HI, Theme.AMBER, Theme.AMBER_LO};
        }
        return new int[] {0xFFE89080, 0xFFC85040, 0xFF983020};
    }

    boolean mouseClicked(double mx, double my) {
        return bar.mouseClicked(mx, my) && moved();
    }

    boolean mouseDragged(double my) {
        return bar.mouseDragged(my) && moved();
    }

    void mouseReleased() {
        bar.mouseReleased();
    }

    boolean mouseScrolled(double mx, double my, double v) {
        if (mx < area[0] || mx >= area[0] + area[2] || my < area[1] || my >= area[1] + area[3]) {
            return false;
        }
        scroll = Math.max(0, Math.min(max, scroll - (int) Math.signum(v) * (s.rolled() ? ROW : BoxPlainRows.ROW * 2)));
        atEnd();
        return true;
    }

    private boolean moved() {
        scroll = bar.offset();
        atEnd();
        return true;
    }

    /** Scrolled to the end while the market has more: the next page (asked once; Listings keeps the rest out). */
    private void atEnd() {
        BoxMarket.Row r = s.selectedRow();
        if (r != null && r.hasMore() && max > 0 && scroll >= max) {
            Listings.requestMore(r.name());
        }
    }

    /**
     * A listing's price to the emerald, the way the Trade Market writes it: "2 EB 49 E",
     * "1 LE 12 EB 3 E" (1 LE = 64 EB = 4096 E); parts that are 0 are left out.
     */
    static String exact(long emeralds) {
        long le = emeralds / 4096;
        long eb = emeralds % 4096 / 64;
        long e = emeralds % 64;
        StringBuilder b = new StringBuilder();
        if (le > 0) {
            b.append(le).append(" LE");
        }
        if (eb > 0) {
            b.append(b.length() > 0 ? " " : "").append(eb).append(" EB");
        }
        if (e > 0 || b.length() == 0) {
            b.append(b.length() > 0 ? " " : "").append(e).append(" E");
        }
        return b.toString();
    }
}
