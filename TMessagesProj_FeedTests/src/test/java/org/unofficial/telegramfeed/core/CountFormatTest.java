package org.unofficial.telegramfeed.core;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class CountFormatTest {

    @Test
    public void smallCountsStayWhole() {
        assertEquals("0", CountFormat.shortCount(0));
        assertEquals("670", CountFormat.shortCount(670));
        assertEquals("999", CountFormat.shortCount(999));
    }

    @Test
    public void thousandsKeepOneDecimalBelowTenThousand() {
        assertEquals("1K", CountFormat.shortCount(1000));
        assertEquals("1K", CountFormat.shortCount(1056));
        assertEquals("1.1K", CountFormat.shortCount(1121));
        assertEquals("9.9K", CountFormat.shortCount(9999));
    }

    @Test
    public void largerCountsDropTheDecimal() {
        assertEquals("10K", CountFormat.shortCount(10_000));
        assertEquals("70K", CountFormat.shortCount(70_269));
        assertEquals("73K", CountFormat.shortCount(73_116));
        assertEquals("999K", CountFormat.shortCount(999_999));
    }

    @Test
    public void millions() {
        assertEquals("1M", CountFormat.shortCount(1_000_000));
        assertEquals("1.2M", CountFormat.shortCount(1_250_000));
        assertEquals("12M", CountFormat.shortCount(12_900_000));
    }
}
