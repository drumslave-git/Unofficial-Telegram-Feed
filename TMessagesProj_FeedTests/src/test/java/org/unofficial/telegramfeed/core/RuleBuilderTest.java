package org.unofficial.telegramfeed.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;

import org.junit.Test;
import org.unofficial.telegramfeed.core.RuleBuilder.BuilderTerm;

public class RuleBuilderTest {

    @Test
    public void roundTripsOrOfAndShapesWithNegationAndModifiers() throws Exception {
        for (String src : new String[]{"btc", "NOT btc", "a AND ~b", "a OR b", "(a AND NOT =b) OR c OR (d AND e AND \"f g\")"}) {
            RuleExpr e = RuleParser.parse(src);
            RuleBuilder m = RuleBuilder.fromExpr(e);
            assertNotNull(src, m);
            assertEquals(src, e, m.toExpr());
            assertTrue(src, m.isValid());
            assertEquals(src, e, RuleParser.parse(m.toText()));
        }
    }

    @Test
    public void deeperNestingIsNotRepresentable() throws Exception {
        assertNull(RuleBuilder.fromExpr(RuleParser.parse("NOT (a AND b)")));
        assertNull(RuleBuilder.fromExpr(RuleParser.parse("a AND (b OR c)")));
        assertNull(RuleBuilder.fromText("a AND (b OR c)"));
        assertNull(RuleBuilder.fromText("(a OR"));
    }

    @Test
    public void validitySingleTermSimplificationAndBlankTerms() {
        RuleBuilder empty = new RuleBuilder();
        assertTrue(empty.isEmpty());
        assertFalse(empty.isValid());
        assertEquals("", empty.toText());
        assertTrue(RuleBuilder.fromText("").isEmpty());

        RuleBuilder m = new RuleBuilder();
        m.groups.add(new ArrayList<>(Arrays.asList(new BuilderTerm(" btc ", true, false, true))));
        assertEquals(new RuleExpr.Not(new RuleExpr.Term("btc")), m.toExpr());
        assertEquals("NOT btc", m.toText());

        m.groups.get(0).add(new BuilderTerm("", true, false, false));
        m.groups.add(new ArrayList<>(Arrays.asList(new BuilderTerm("  ", true, false, false))));
        assertFalse(m.isValid());
        assertEquals("NOT btc", m.toText());
        assertEquals(1, m.withoutBlankTerms().groups.size());
    }

    @Test
    public void copiesAreDeep() {
        RuleBuilder m = RuleBuilder.fromText("a AND b");
        RuleBuilder copy = new RuleBuilder(m);
        assertEquals(m, copy);
        copy.groups.get(0).get(0).text = "z";
        assertEquals("a", m.groups.get(0).get(0).text);
    }
}
