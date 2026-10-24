package io.github.anilken.wynnlabs.quests;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Stage blocks as real wiki pages write them: the task on the place's line, nested colours. */
class QuestWikiStageTest {

    private static final String BOLD = "'''";

    @Test
    void nestedColourTemplatesUnwrap() {
        // A Grave Mistake, stage 2: the game's own garbled line, a colour inside a colour
        assertEquals("» .;I. ,told;. .,you; :to, .,stay; .away';",
                QuestWiki.clean("» " + BOLD + "{{c|#a00|.;I. ,told;. {{c|#aaa|.,you}}; :to, {{c|#aaa|.,stay}}; .away';}}" + BOLD));
    }

    @Test
    void professionLevelsComeFromTheInfobox() {
        // The Shadow of the Beast: three gathering professions at 20
        String text = "{{Infobox/Quest\n| length = Long\n| level = 54\n| miningLevel = 20\n| woodcuttingLevel = 20\n"
                + "| fishingLevel = 20\n}}\n== Stage 1 ==\n» Go\n";
        QuestWiki.Info info = QuestWiki.parse(text);
        assertEquals(java.util.Map.of("MINING", 20, "WOODCUTTING", 20, "FISHING", 20), info.professions());
        assertEquals(java.util.List.of("MINING", "WOODCUTTING", "FISHING"), new java.util.ArrayList<>(info.professions().keySet()),
                "in the game's order");
    }

    @Test
    void aTaskOnThePlaceLineIsStillTheTask() {
        // A Grave Mistake, stage 1: the place template and the task share a line
        String text = "{{Infobox}}\n== Stage 1 ==\n{{RenderLocation | location = [[Twain Graveyard]] | x = 158 | y = 79 | z = -373 }}"
                + "» " + BOLD + "{{c|#a00|., STAY ;.}} 'AWAY{{c|#a00|:, .FROM;,}} ,.; [157,78,-377] {{c|#a00|':,}}" + BOLD
                + "\n\n* " + BOLD + "Alem:" + BOLD + " Leave.\n{{Clear}}\n"
                + "== Stage 2 == \n» " + BOLD + "{{c|#a00|.;I. ,told;. {{c|#aaa|.,you}}; :to, {{c|#aaa|.,stay}}; .away';}}" + BOLD + "\n";
        QuestWiki.Info info = QuestWiki.parse(text);
        assertEquals(2, info.stages().size());
        assertEquals("., STAY ;. 'AWAY:, .FROM;, ,.; [157,78,-377] ':,", info.stages().get(0).task());
        assertEquals(158, info.stages().get(0).at()[0], "the place pin comes first");
        assertEquals(".;I. ,told;. .,you; :to, .,stay; .away';", info.stages().get(1).task());
    }

    @Test
    void aPlaceWithoutHeightStillPins() {
        // A Journey Beyond, stage 15: the Silent Expanse's pages give x and z only
        String text = "{{Infobox}}\n==Stage 15==\n» Find Aledar, Lucio and Elphaba in the Fallen City. <br>\n"
                + "{{RenderLocation | location = [[Silent Expanse|The Silent Expanse]] | x = 489 | z = -601 }}\n";
        QuestWiki.Info info = QuestWiki.parse(text);
        int[] at = info.stages().get(0).at();
        assertEquals(489, at[0]);
        assertEquals(-601, at[2]);
    }
}
