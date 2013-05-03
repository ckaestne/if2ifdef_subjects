/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2013 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.util;

import static org.junit.Assert.assertEquals;

import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.sleepycat.je.utilint.PropUtil;
import com.sleepycat.util.test.TestBase;

public class PropUtilTest extends TestBase {

    @Test
    public void testDurationToMillis() {

        /* Disallow negative values. */
        try {
            PropUtil.durationToMillis(-1, TimeUnit.SECONDS);
        } catch (IllegalArgumentException expected) {
        }

        /* Disallow millis > Integer.MAX_VALUE. */
        try {
            PropUtil.durationToMillis(((long) Integer.MAX_VALUE) + 1,
                                      TimeUnit.MILLISECONDS);
        } catch (IllegalArgumentException expected) {
        }

        /* Disallow null unit with non-zero time. */
        try {
            PropUtil.durationToMillis(1, null);
        } catch (IllegalArgumentException expected) {
        }

        /* Allow null unit with zero time. */
        assertEquals(0, PropUtil.durationToMillis(0, null));

        /* Positive input should result in at least 1 ms. */
        assertEquals(1, PropUtil.durationToMillis(1, TimeUnit.MICROSECONDS));
        assertEquals(1, PropUtil.durationToMillis(1, TimeUnit.NANOSECONDS));

        /* Misc conversions. */
        assertEquals(0, PropUtil.durationToMillis(0, TimeUnit.SECONDS));
        assertEquals(1, PropUtil.durationToMillis(1, TimeUnit.MILLISECONDS));
        assertEquals(1, PropUtil.durationToMillis(999, TimeUnit.MICROSECONDS));
        assertEquals(1, PropUtil.durationToMillis(1000, TimeUnit.MICROSECONDS));
        assertEquals(1, PropUtil.durationToMillis(1001, TimeUnit.MICROSECONDS));
        assertEquals(1, PropUtil.durationToMillis(1999, TimeUnit.MICROSECONDS));
        assertEquals(2, PropUtil.durationToMillis(2000000,
                                                  TimeUnit.NANOSECONDS));
    }

    @Test
    public void testMillisToDuration() {

        /* Disallow null unit. */
        try {
            PropUtil.millisToDuration(0, null);
        } catch (IllegalArgumentException expected) {
        }

        /* Misc conversions. */
        assertEquals(0, PropUtil.millisToDuration(0, TimeUnit.SECONDS));
        assertEquals(1, PropUtil.millisToDuration(1000, TimeUnit.SECONDS));
    }

    @Test
    public void testParseDuration() {

        /* Disallow empty string. */
        try {
            PropUtil.parseDuration("");
        } catch (IllegalArgumentException expected) {
        }

        /* Disallow whitespace. */
        try {
            PropUtil.parseDuration(" \t");
        } catch (IllegalArgumentException expected) {
        }

        /* Disallow bad number. */
        try {
            PropUtil.parseDuration("X");
        } catch (IllegalArgumentException expected) {
        }

        /* Disallow bad number with unit. */
        try {
            PropUtil.parseDuration("X ms");
        } catch (IllegalArgumentException expected) {
        }

        /* Disallow bad unit. */
        try {
            PropUtil.parseDuration("3 X");
        } catch (IllegalArgumentException expected) {
        }

        /* Disallow extra stuff after unit. */
        try {
            PropUtil.parseDuration("3 ms X");
        } catch (IllegalArgumentException expected) {
        }

        /* Disallow negative number. */
        try {
            PropUtil.parseDuration("-1");
        } catch (IllegalArgumentException expected) {
        }

        /* Disallow negative number with unit. */
        try {
            PropUtil.parseDuration("-1 ms");
        } catch (IllegalArgumentException expected) {
        }

        /* Positive input should result in at least 1 ms. */
        assertEquals(1, PropUtil.parseDuration("1 ns"));
        assertEquals(1, PropUtil.parseDuration("1 us"));
        assertEquals(1, PropUtil.parseDuration("1 nanoseconds"));
        assertEquals(1, PropUtil.parseDuration("1 microseconds"));

        /* TimeUnit names. */
        assertEquals(3, PropUtil.parseDuration("3000000 nanoseconds"));
        assertEquals(3, PropUtil.parseDuration("3000 microseconds"));
        assertEquals(3, PropUtil.parseDuration("3 milliseconds"));
        assertEquals(3000, PropUtil.parseDuration("3 seconds"));

        /* IEEE abbreviations. */
        assertEquals(3, PropUtil.parseDuration("3000000 NS"));
        assertEquals(3, PropUtil.parseDuration("3000 US"));
        assertEquals(3, PropUtil.parseDuration("3 MS"));
        assertEquals(3000, PropUtil.parseDuration("3 S"));
        assertEquals(3000 * 60, PropUtil.parseDuration("3 MIN"));
        assertEquals(3000 * 60 * 60, PropUtil.parseDuration("3 H"));
    }

    @Test
    public void testFormatDuration() {
        assertEquals("30 NANOSECONDS",
                     PropUtil.formatDuration(30, TimeUnit.NANOSECONDS));
        assertEquals("30 MICROSECONDS",
                     PropUtil.formatDuration(30, TimeUnit.MICROSECONDS));
        assertEquals("30 MILLISECONDS",
                     PropUtil.formatDuration(30, TimeUnit.MILLISECONDS));
        assertEquals("30 SECONDS",
                     PropUtil.formatDuration(30, TimeUnit.SECONDS));
    }
}
