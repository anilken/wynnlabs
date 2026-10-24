package io.github.anilken.wynnlabs.crafting;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.anilken.wynnlabs.crafting.Ings.ing;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftSearchTest {

    private static final List<String> WOOD = List.of("woodworking");
    private static final int[] NO_POS = new int[6];
    private static final int[] NO_REQ = new int[5];

    // A small woodworking set where the floor binds, a meta boosts its neighbours and another inverts.
    static final Ingredient STRONG = ing("Strong", WOOD, new Object[][] {{"spellDamage", 10, 14}}, NO_POS, -60, 0, 0,
            new int[] {0, 0, 20, 0, 0});
    static final Ingredient MANA_EATER = ing("Mana Eater", WOOD, new Object[][] {{"spellDamage", 13, 17}, {"manaRegen", -2, -1}},
            NO_POS, -20, 0, 0, NO_REQ);
    static final Ingredient BOOSTER = ing("Booster", WOOD, new Object[][] {{"spellDamage", 2, 3}},
            new int[] {0, 0, 0, 0, 30, 0}, -40, 0, 0, NO_REQ);
    static final Ingredient INVERTER = ing("Inverter", WOOD, new Object[][] {}, new int[] {-200, 0, 0, 0, 0, 0}, -30, 0, 0,
            NO_REQ);
    static final Ingredient CURSED = ing("Cursed", WOOD, new Object[][] {{"spellDamage", -10, -8}, {"manaRegen", 1, 2}},
            NO_POS, -10, 0, 0, new int[] {0, 0, -15, 0, 0});
    static final Ingredient STURDY = ing("Sturdy", WOOD, new Object[][] {{"spellDamage", 1, 2}}, NO_POS, 80, 0, 0, NO_REQ);
    static final Ingredient TAILOR_ONLY = ing("Tailor Only", List.of("tailoring"), new Object[][] {{"spellDamage", 50, 60}},
            NO_POS, 0, 0, 0, NO_REQ);
    static final Ingredient USELESS = ing("Useless", WOOD, new Object[][] {{"walkSpeed", 5, 8}}, NO_POS, -20, 0, 0, NO_REQ);
    static final Ingredient TOO_HIGH = new Ingredient("Too High", 3, 60, WOOD,
            Map.of("spellDamage", new Ingredient.Stat(40, 50, "", "")), NO_POS, -10, 0, 0, NO_REQ);
    static final Ingredient LOCKED = new Ingredient("Locked", 3, 53, WOOD,
            Map.of("spellDamage", new Ingredient.Stat(30, 35, "", "")), NO_POS, -10, 0, 0, NO_REQ);

    static final List<Ingredient> SET = List.of(STRONG, MANA_EATER, BOOSTER, INVERTER, CURSED, STURDY);
    static final Ingredient WEAK_EATER = ing("Mana Eater", WOOD, new Object[][] {{"spellDamage", 6, 8}, {"manaRegen", -2, -1}},
            NO_POS, -20, 0, 0, NO_REQ);
    static final List<Ingredient> WEAK_SET = List.of(STRONG, WEAK_EATER, BOOSTER, INVERTER, CURSED, STURDY);

    private static CraftSearch.Request request(CraftSearch.Goal goal, List<Ingredient> ings, Map<String, Integer> owned) {
        return new CraftSearch.Request(CraftData.byId("Wand50-53"), 1, 1, Craft.Speed.NORMAL, goal, ings, owned, 52, false,
                name -> new CraftSearch.Price(name.length() * 100L, false));
    }

    @Test
    void candidatesFollowProfessionLevelStockAndUse() {
        List<Ingredient> all = new ArrayList<>(SET);
        all.addAll(List.of(TAILOR_ONLY, USELESS, TOO_HIGH, LOCKED));
        CraftSearch.Request q = request(CraftSearch.Goal.of("spellDamage"), all, Map.of("Strong", 2, "Inverter", 1));
        assertEquals(List.of("Strong", "Mana Eater", "Booster", "Inverter", "Cursed", "Sturdy"),
                CraftSearch.candidates(q, CraftSearch.Tier.ANY).stream().map(Ingredient::name).toList(),
                "no other profession, nothing above the recipe or the player's level, nothing without a use");
        assertEquals(List.of("Strong", "Inverter"),
                CraftSearch.candidates(q, CraftSearch.Tier.OWN).stream().map(Ingredient::name).toList(),
                "own only: what the player has, metas included");
        CraftSearch.Request unlocked = new CraftSearch.Request(q.recipe(), 1, 1, Craft.Speed.NORMAL, q.goal(), all, q.owned(),
                52, true, q.prices());
        assertTrue(CraftSearch.candidates(unlocked, CraftSearch.Tier.ANY).contains(LOCKED), "locked allowed on request");
        assertFalse(CraftSearch.candidates(unlocked, CraftSearch.Tier.ANY).contains(TOO_HIGH), "never above the recipe");
    }

    @Test
    void agreesWithBruteForce() {
        Map<String, Integer> owned = Map.of("Strong", 2, "Mana Eater", 3, "Inverter", 1, "Cursed", 2, "Sturdy", 1);
        List<CraftSearch.Goal> goals = List.of(
                CraftSearch.Goal.of("spellDamage"),
                new CraftSearch.Goal(Map.of("spellDamage", 1.0, "manaRegen", 4.0), CraftSearch.Pick.MIN, Set.of(),
                        CraftSearch.Goal.uncapped(), 1),
                new CraftSearch.Goal(Map.of("spellDamage", 1.0), CraftSearch.Pick.MAX, Set.of("manaRegen"),
                        new int[] {CraftSearch.Goal.NO_CAP, CraftSearch.Goal.NO_CAP, 30, CraftSearch.Goal.NO_CAP,
                                CraftSearch.Goal.NO_CAP}, 100));
        for (List<Ingredient> set : List.of(SET, WEAK_SET))
        for (CraftSearch.Goal goal : goals) {
            CraftSearch.Request q = request(goal, set, owned);
            for (CraftSearch.Tier tier : CraftSearch.Tier.values()) {
                CraftSearch.Outcome o = CraftSearch.search(q, tier, new CraftSearch.Budget(20_000));
                assertTrue(o.complete());
                Double brute = brute(q, tier);
                if (brute == null) {
                    assertNull(o.best(), tier + " " + goal);
                    continue;
                }
                assertNotNull(o.best(), tier + " " + goal);
                assertEquals(brute, o.best().score(), 1e-9, tier + " " + goal + " " + names(o.best()) + " brute " + bruteLayout);
                assertTrue(goal.allows(o.best().result()));
                int extra = o.best().bought().size();
                assertTrue(tier != CraftSearch.Tier.OWN || extra == 0);
                assertTrue(tier != CraftSearch.Tier.OWN_PLUS_TWO || extra <= 2);
            }
        }
    }

    static String bruteLayout;

    /** The best score over every way to fill the six slots from the set (and empty), or null if none is allowed. */
    private static Double brute(CraftSearch.Request q, CraftSearch.Tier tier) {
        List<Ingredient> pool = new ArrayList<>(CraftSearch.candidates(q, CraftSearch.Tier.ANY));
        pool.add(Ingredient.NONE);
        int n = pool.size();
        Double best = null;
        int[] pick = new int[6];
        int total = (int) Math.pow(n, 6);
        for (int code = 0; code < total; code++) {
            int c = code;
            for (int s = 0; s < 6; s++) {
                pick[s] = c % n;
                c /= n;
            }
            Map<String, Integer> used = new HashMap<>();
            int extra = 0;
            List<Ingredient> slots = new ArrayList<>();
            for (int s = 0; s < 6; s++) {
                Ingredient i = pool.get(pick[s]);
                slots.add(i);
                if (!i.empty() && used.merge(i.name(), 1, Integer::sum) > q.own(i.name())) {
                    extra++;
                }
            }
            if (tier == CraftSearch.Tier.OWN && extra > 0 || tier == CraftSearch.Tier.OWN_PLUS_TWO && extra > 2) {
                continue;
            }
            Craft.Result r = Craft.craft(q.recipe(), q.tier1(), q.tier2(), q.speed(), slots);
            if (!q.goal().allows(r)) {
                continue;
            }
            double score = q.goal().score(r.ids());
            if (best == null || score > best) {
                best = score;
                bruteLayout = slots.stream().map(i -> i.empty() ? "-" : i.name()).toList().toString();
            }
        }
        return best;
    }

    private static String names(CraftSearch.Layout l) {
        return l.slots().stream().map(i -> i.empty() ? "-" : i.name()).toList().toString();
    }

    @Test
    void filtersHoldAndWeightsReorder() {
        CraftSearch.Request plain = request(CraftSearch.Goal.of("spellDamage"), SET, Map.of());
        CraftSearch.Layout any = CraftSearch.search(plain, CraftSearch.Tier.ANY, new CraftSearch.Budget(20_000)).best();
        assertTrue(any.result().ids().get("manaRegen") != null && any.result().ids().get("manaRegen").min() < 0,
                "without a filter the best spell damage layout pays with mana regen: " + names(any));

        CraftSearch.Goal noNeg = new CraftSearch.Goal(Map.of("spellDamage", 1.0), CraftSearch.Pick.MEAN, Set.of("manaRegen"),
                CraftSearch.Goal.uncapped(), 1);
        CraftSearch.Layout filtered = CraftSearch.search(request(noNeg, SET, Map.of()), CraftSearch.Tier.ANY,
                new CraftSearch.Budget(20_000)).best();
        Craft.Range mana = filtered.result().ids().get("manaRegen");
        assertTrue(mana == null || mana.min() >= 0, names(filtered));
        assertTrue(filtered.score() <= any.score());

        CraftSearch.Goal manaHeavy = new CraftSearch.Goal(Map.of("spellDamage", 1.0, "manaRegen", 20.0),
                CraftSearch.Pick.MEAN, Set.of(), CraftSearch.Goal.uncapped(), 1);
        CraftSearch.Layout heavy = CraftSearch.search(request(manaHeavy, SET, Map.of()), CraftSearch.Tier.ANY,
                new CraftSearch.Budget(20_000)).best();
        assertTrue(heavy.result().ids().get("manaRegen").mean() > 0, "a heavy mana weight puts mana regen first: " + names(heavy));
        assertTrue(heavy.result().ids().get("spellDamage") == null
                || heavy.result().ids().get("spellDamage").mean() < any.result().ids().get("spellDamage").mean());
    }

    @Test
    void costOfBoughtIngredients() {
        Map<String, Integer> owned = Map.of("Strong", 1);
        CraftSearch.Request q = new CraftSearch.Request(CraftData.byId("Wand50-53"), 1, 1, Craft.Speed.NORMAL,
                CraftSearch.Goal.of("spellDamage"), SET, owned, 52, false,
                name -> switch (name) {
                    case "Strong" -> new CraftSearch.Price(1000, false);
                    case "Booster" -> new CraftSearch.Price(250, true);
                    default -> null;
                });
        CraftSearch.Layout l = CraftSearch.search(q, CraftSearch.Tier.ANY, new CraftSearch.Budget(20_000)).best();
        long strong = l.bought().stream().filter("Strong"::equals).count();
        long booster = l.bought().stream().filter("Booster"::equals).count();
        assertEquals(strong * 1000 + booster * 250, l.cost());
        assertEquals(booster > 0, l.oldPrices().contains("Booster"));
        boolean anyUnpriced = l.bought().stream().anyMatch(n -> !n.equals("Strong") && !n.equals("Booster"));
        assertEquals(!anyUnpriced, l.costComplete(), "an unknown price makes the cost incomplete and is named");
        long ownStrong = l.slots().stream().filter(i -> i.name().equals("Strong")).count();
        assertEquals(Math.max(0, ownStrong - 1), strong, "the owned copy is not bought");
    }

    /** About as many ingredients as the game has, a tenth of them meta, as a stress test. */
    static List<Ingredient> synthetic(int n, long seed) {
        Random rnd = new Random(seed);
        String[] ids = {"spellDamage", "manaRegen", "rawStrength", "rawHealth", "walkSpeed", "lifeSteal", "exploding",
                "rawIntelligence", "mainAttackDamage", "thorns"};
        List<Ingredient> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Map<String, Ingredient.Stat> m = new HashMap<>();
            int k = 1 + rnd.nextInt(3);
            for (int j = 0; j < k; j++) {
                int lo = rnd.nextInt(41) - 15;
                m.put(ids[rnd.nextInt(ids.length)], new Ingredient.Stat(lo, lo + rnd.nextInt(6), "", ""));
            }
            int[] pos = new int[6];
            if (rnd.nextInt(10) == 0) {
                pos[rnd.nextInt(6)] = (rnd.nextBoolean() ? 1 : -1) * (5 + rnd.nextInt(30)) * 5;
            }
            int[] req = new int[5];
            req[rnd.nextInt(5)] = rnd.nextInt(50) - 15;
            out.add(new Ingredient("Synthetic " + i, rnd.nextInt(4), 1 + rnd.nextInt(105), WOOD, m, pos,
                    -(20 + rnd.nextInt(160)) + (rnd.nextInt(20) == 0 ? 200 : 0), 0, 0, req));
        }
        return out;
    }

    @Test
    void fullListFinishesWithinBudget() {
        List<Ingredient> all = synthetic(970, 42);
        CraftSearch.Request q = new CraftSearch.Request(CraftData.byId("Wand103-105"), 3, 3, Craft.Speed.NORMAL,
                new CraftSearch.Goal(Map.of("spellDamage", 1.0, "manaRegen", 2.0), CraftSearch.Pick.MEAN, Set.of("rawHealth"),
                        new int[] {CraftSearch.Goal.NO_CAP, CraftSearch.Goal.NO_CAP, 60, CraftSearch.Goal.NO_CAP,
                                CraftSearch.Goal.NO_CAP}, 1),
                all, Map.of("Synthetic 1", 3, "Synthetic 2", 2), 105, false, name -> null);
        long t0 = System.nanoTime();
        List<CraftSearch.Outcome> out = CraftSearch.searchAll(q, 3000, new CraftSearch.Budget(4000));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms < 3800, "took " + ms + " ms");
        assertEquals(3, out.size());
        CraftSearch.Layout best = out.get(2).best();
        if (best == null) {
            // a busy CI machine can run out of the 4 s before the last tier finds a layout: then
            // the search must say it was cut short, never claim a finished search with nothing
            assertFalse(out.get(2).complete(), "a finished search with no layout");
            return;
        }
        assertTrue(q.goal().allows(best.result()));
        System.out.println("full search: " + ms + " ms, complete " + out.stream().map(CraftSearch.Outcome::complete).toList()
                + ", best " + best.score());
    }

    @Test
    void aNewRequestCancelsTheOld() throws Exception {
        SearchRunner runner = new SearchRunner();
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<CraftSearch.Budget> first = new AtomicReference<>();
        runner.run(60_000, b -> {
            first.set(b);
            started.countDown();
            while (!b.over()) {
                Thread.onSpinWait();
            }
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<List<CraftSearch.Outcome>> got = new AtomicReference<>();
        runner.submit(request(CraftSearch.Goal.of("spellDamage"), SET, Map.of()), 2000, r -> {
            got.set(r);
            done.countDown();
        });
        assertTrue(first.get().cancelled(), "the old search is told to stop");
        assertTrue(done.await(10, TimeUnit.SECONDS), "the new one runs once the old one stops");
        assertEquals(3, got.get().size());
    }
}
