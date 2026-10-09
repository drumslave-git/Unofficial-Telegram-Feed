package org.unofficial.telegramfeed.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Calendar;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;
import org.unofficial.telegramfeed.core.RuleExpr.And;
import org.unofficial.telegramfeed.core.RuleExpr.Not;
import org.unofficial.telegramfeed.core.RuleExpr.Or;
import org.unofficial.telegramfeed.core.RuleExpr.Term;

public class RuleParserTest {

    @Test
    public void precedenceAndBindsTighterThanOrNotTightest() throws Exception {
        assertEquals(new Or(new Term("a"), new And(new Term("b"), new Not(new Term("c")))), RuleParser.parse("a OR b AND NOT c"));
    }

    @Test
    public void parenthesesQuotesModifiersKeywordCase() throws Exception {
        assertEquals(new And(new Or(new Term("bitcoin"), new Term("btc")), new Not(new Term("Air", false, true))), RuleParser.parse("(\"bitcoin\" or btc) and not ~=Air"));
        assertEquals(new Term("say \"hi\" \\ now"), RuleParser.parse("\"say \\\"hi\\\" \\\\ now\""));
        assertEquals(new Term("two words"), RuleParser.parse("\"two words\""));
    }

    private static RuleParser.SyntaxException error(String src) {
        try {
            RuleParser.parse(src);
        } catch (RuleParser.SyntaxException e) {
            return e;
        }
        fail(src + " parsed");
        return null;
    }

    @Test
    public void errorsSayWhatIsWrongAndWhere() {
        assertEquals(RuleParser.Problem.EXPECTED_TERM, error("").problem);
        assertEquals(RuleParser.Problem.EXPECTED_CLOSING_PAREN, error("(a OR b").problem);
        assertEquals(RuleParser.Problem.UNEXPECTED, error("a b").problem);
        assertEquals(RuleParser.Problem.KEYWORD, error("AND").problem);
        assertEquals(RuleParser.Problem.UNEXPECTED, error("a OR b)").problem);
        assertEquals(")", error("a OR b)").detail);
        assertEquals(7, error("a OR b)").position);
        assertEquals(RuleParser.Problem.KEYWORD, error("a OR and").problem);
        assertEquals("and", error("a OR and").detail);
        assertEquals(RuleParser.Problem.UNTERMINATED_QUOTE, error("\"open").problem);
        assertEquals(RuleParser.Problem.DANGLING_ESCAPE, error("\"a\\").problem);
        assertEquals(RuleParser.Problem.EMPTY_TERM, error("\"\"").problem);
        assertEquals("rule syntax: unexpected \")\" at 7", error("a OR b)").getMessage());
    }

    @Test
    public void formatRoundTrips() throws Exception {
        for (String src : new String[]{"a OR b AND NOT c", "(a OR b) AND NOT c", "~coin AND =BTC AND ~=\"Mixed Case\"", "\"a phrase\" OR word", "NOT (a AND b)", "\"and\""}) {
            RuleExpr e = RuleParser.parse(src);
            assertEquals(src, e, RuleParser.parse(RuleParser.format(e)));
        }
        assertEquals("(a OR b) AND NOT c", RuleParser.format(RuleParser.parse("(a OR b) AND NOT c")));
    }

    private static boolean m(String rule, String text) throws Exception {
        return new RuleEvaluator().matches(RuleParser.parse(rule), text);
    }

    @Test
    public void wholeWordsSubstringsCase() throws Exception {
        assertTrue(m("btc", "Buy BTC now"));
        assertFalse(m("btc", "altbtcoin"));
        assertTrue(m("~btc", "altbtcoin"));
        assertFalse(m("=BTC", "buy btc"));
        assertTrue(m("=BTC", "buy BTC"));
        assertTrue(m("coin", "coin."));
        assertFalse(m("coin", "coin_x"));
    }

    @Test
    public void phrasesMatchAcrossWhitespaceRuns() throws Exception {
        assertTrue(m("\"rate cut\"", "A rate\n  cut is coming"));
        assertFalse(m("\"rate cut\"", "rate-cut"));
    }

    @Test
    public void unicodeWordBoundariesAndCyrillicCaseFolding() throws Exception {
        assertTrue(m("курс", "Новый курс рубля"));
        assertTrue(m("курс", "Новый КУРС рубля"));
        assertFalse(m("курс", "экскурсия"));
        assertTrue(m("~курс", "экскурсия"));
        assertFalse(m("=Курс", "курс"));
        assertTrue(m("ünal", "Ünal geldi"));
        assertFalse(m("nal", "Ünal geldi"));
        assertFalse(m("币", "比特币涨了"));
        assertTrue(m("~币", "比特币涨了"));
        assertTrue(m("😀", "hi 😀 there"));
    }

    @Test
    public void booleanStructure() throws Exception {
        assertTrue(m("(bitcoin OR btc) AND NOT airdrop", "BTC pumps"));
        assertFalse(m("(bitcoin OR btc) AND NOT airdrop", "btc airdrop!"));
        assertTrue(m("NOT (a AND b)", "a only"));
        assertFalse(m("NOT (a AND b)", "a and b"));
    }

    private static Calendar at(int weekday, String hhmm) {
        int t = Schedule.parseTime(hhmm);
        Calendar c = Calendar.getInstance();
        c.set(2026, Calendar.SEPTEMBER, 14 + weekday - 1, t / 60, t % 60, 0); // 2026-09-14 is a Monday
        return c;
    }

    private static Set<Integer> days(int... d) {
        Set<Integer> s = new HashSet<>();
        for (int i : d) s.add(i);
        return s;
    }

    @Test
    public void scheduleWindowAndWeekdays() {
        Schedule s = new Schedule(days(1, 2, 3, 4, 5), Schedule.parseTime("09:00"), Schedule.parseTime("18:00"));
        assertTrue(s.isActive(at(1, "09:00")));
        assertTrue(s.isActive(at(1, "17:59")));
        assertFalse(s.isActive(at(1, "18:00")));
        assertFalse(s.isActive(at(6, "12:00")));
    }

    @Test
    public void scheduleWrapsPastMidnightWithTheStartDay() {
        Schedule s = new Schedule(days(5), Schedule.parseTime("22:00"), Schedule.parseTime("02:00"));
        assertTrue(s.isActive(at(5, "23:30")));
        assertTrue(s.isActive(at(6, "01:30")));
        assertFalse(s.isActive(at(6, "02:00")));
        assertFalse(s.isActive(at(6, "23:30")));
        assertFalse(s.isActive(at(1, "00:30")));
        Schedule sun = new Schedule(days(7), Schedule.parseTime("23:00"), Schedule.parseTime("01:00"));
        assertTrue(sun.isActive(at(1, "00:30")));
    }

    @Test
    public void scheduleWholeDayAndEncoding() {
        Schedule s = new Schedule(days(3), 0, 0);
        assertTrue(s.isActive(at(3, "00:00")));
        assertFalse(s.isActive(at(4, "00:00")));
        assertEquals(s, Schedule.decode(s.encode()));
        assertEquals("3|00:00|00:00", s.encode());
        try {
            Schedule.parseTime("25:00");
            fail();
        } catch (IllegalArgumentException expected) {
        }
        try {
            Schedule.parseTime("9");
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }
}
