package io.github.anilken.wynnlabs.quests;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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

    private static int[] at(String stageBody) {
        return QuestWiki.parse("{{Infobox}}\n== Stage 1 ==\n" + stageBody).stages().get(0).at();
    }

    @Test
    void aTemplateOverSeveralLinesPins() {
        // A Confused Farmer, stage 4: {{Location}} with a value on each line
        assertArrayEquals(new int[] {305, 69, -1609}, at("» Go bring [[Drale]] back to the ranch.\n{{Location\n| x = 305\n| y = 69 \n| z = -1609\n}}\n"));
        // Troubled Tribesmen, stage 3: the height given last; its template lines are no hints
        QuestWiki.Stage st = QuestWiki.parse("== Stage 1 ==\n» Talk to [[Shaman Inrekei]].\n{{RenderLocation\n|location=[[Dernel Jungle]]\n"
                + "|x=-706\n|z=-572\n|y=23}}\n").stages().get(0);
        assertArrayEquals(new int[] {-706, 23, -572}, st.at());
        assertEquals(List.of(), st.hints());
        assertEquals("Dernel Jungle", st.names().get(0));
    }

    @Test
    void xAndZOnlyTakeTheHeightBefore() {
        // A Journey Beyond, stages 17 and 18: "(685, -407)" in the task, the height of the stage before
        String text = "== Stage 1 ==\n» Go.\n{{RenderLocation | x = 595 | y = 39 | z = -680 }}\n"
                + "== Stage 2 ==\n» Escort Aledar through the Eyeball Forest to Lutho at (685, -407). \n";
        assertArrayEquals(new int[] {685, 39, -407}, QuestWiki.parse(text).stages().get(1).at());
        // Eye of the Storm, stage 2; A Hunter's Calling, stage 1; Aldorei's Secret Part II, stage 8
        assertArrayEquals(new int[] {-714, 64, -5385}, at("» Search the ruins to find the cultists.\n{{ Location | x = -714 | z = -5385 }} \n"));
        assertArrayEquals(new int[] {1320, 64, -417}, at("» Speak to ???\n??? Is located at X: 1320 Z: -417.\n"));
        assertArrayEquals(new int[] {-285, 64, -4513}, at("» Go.\n{{HideSpoiler|Right-click on the grave at -285, -4513 to get 5 [[Old Bone]]|Spoiler}}\n"));
    }

    @Test
    void threeNumbersWithoutCommasPin() {
        // Beyond the Grave, stage 4; Redbeard's Booty, stage 1
        assertArrayEquals(new int[] {465, 28, -7920}, at("» Go.\n{{HideSpoiler| After doing the TV quiz, a window will be broken at 465 28 -7920. Go through it|x}}\n"));
        assertArrayEquals(new int[] {-570, 40, -3543}, at("» Talk with [[Marston]] in the ocean at [-570 40 -3543]\n"));
    }

    @Test
    void aSpoilersTextOnTheNextLineStillPins() {
        // Acquiring Credentials, stage 4: the spoiler's text starts the line after it with "|"
        QuestWiki.Stage st = QuestWiki.parse("== Stage 1 ==\n» Find the next clue.\n{{HideSpoiler\n"
                + "|Go through the gate at [-216, 84, -4947]|the next clue's location}}\n").stages().get(0);
        assertArrayEquals(new int[] {-216, 84, -4947}, st.at());
        assertEquals(List.of(), st.hints(), "the line stays out of the hints, as before");
    }

    @Test
    void numbersThatAreNoPlaceStayOut() {
        // A Journey Home, stage 6: a puzzle's solution; a mob's health; a typo'd place with four numbers (The Lost)
        assertNull(at("» Solve it.\n* Large: -9916, -1239\n* Small: -9907, -1257\n"));
        assertNull(at("» Kill them.\n* The boss has 20,000 health and hits at 12, 50 range.\n"));
        assertNull(at("» Climb.\nRight by Yuge's flag at 338, 104 -4464, there will be a sign.\n"));
    }

    @Test
    void aTemplateAfterTheLinesTextKeepsTheText() {
        // The Passage, stage 3: a spoiler and the place on one line; the spoiler stays a hint
        QuestWiki.Stage st = QuestWiki.parse("== Stage 1 ==\n» Search for clues.\n{{HideSpoiler|Go to the herb cave and kill "
                + "[[Dead Miner]] until you have 4 [[Zombie Eye]]}}{{RenderLocation|coordinates=-500, 69, -811}}\n").stages().get(0);
        assertArrayEquals(new int[] {-500, 69, -811}, st.at());
        assertEquals(1, st.hints().size());
    }
}
