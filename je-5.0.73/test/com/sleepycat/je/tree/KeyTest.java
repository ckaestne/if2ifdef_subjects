/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2013 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.tree;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;

import org.junit.Before;
import org.junit.Test;

import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DbInternal;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.dbi.DatabaseId;
import com.sleepycat.je.dbi.DatabaseImpl;
import com.sleepycat.je.dbi.EnvironmentImpl;
import com.sleepycat.je.util.TestUtils;
import com.sleepycat.util.test.SharedTestUtils;
import com.sleepycat.util.test.TestBase;
import com.sleepycat.utilint.StringUtils;

public class KeyTest extends TestBase {
    private File envHome;
    private Environment env;

    @Before
    public void setUp() 
        throws Exception {

        envHome = SharedTestUtils.getTestDir();
        super.setUp();
    }

    @Test
    public void testKeyPrefixer() {
        assertEquals("aaa", makePrefix("aaaa", "aaab"));
        assertEquals("a", makePrefix("abaa", "aaab"));
        assertNull(makePrefix("baaa", "aaab"));
        assertEquals("aaa", makePrefix("aaa", "aaa"));
        assertEquals("aaa", makePrefix("aaa", "aaab"));
    }

    private String makePrefix(String k1, String k2) {
        byte[] ret = Key.createKeyPrefix(StringUtils.toUTF8(k1),
                                         StringUtils.toUTF8(k2));
        if (ret == null) {
            return null;
        } else {
            return StringUtils.fromUTF8(ret);
        }
    }

    @Test
    public void testKeyPrefixSubsetting() {
        keyPrefixSubsetTest("aaa", "aaa", true);
        keyPrefixSubsetTest("aa", "aaa", true);
        keyPrefixSubsetTest("aaa", "aa", false);
        keyPrefixSubsetTest("", "aa", false);
        keyPrefixSubsetTest(null, "aa", false);
        keyPrefixSubsetTest("baa", "aa", false);
    }

    private void keyPrefixSubsetTest(String keyPrefix,
                                     String newKey,
                                     boolean expect) {
        try {
            EnvironmentConfig envConfig = TestUtils.initEnvConfig();
            envConfig.setAllowCreate(true);
            env = new Environment(envHome, envConfig);
            byte[] keyPrefixBytes =
                (keyPrefix == null ? null : StringUtils.toUTF8(keyPrefix));
            byte[] newKeyBytes = StringUtils.toUTF8(newKey);
            DatabaseConfig dbConf = new DatabaseConfig();
            dbConf.setKeyPrefixing(true);
            EnvironmentImpl envImpl = DbInternal.getEnvironmentImpl(env);
            DatabaseImpl databaseImpl =
                new DatabaseImpl(null,
                                 "dummy", new DatabaseId(10), envImpl, dbConf);
            IN in = new IN(databaseImpl, null, 10, 10);
            in.setKeyPrefix(keyPrefixBytes);
            boolean result = in.compareToKeyPrefix(newKeyBytes);
            assertTrue(result == expect);
        } catch (Exception E) {
            E.printStackTrace();
            fail("caught " + E);
        }
    }

    @Test
    public void testKeyComparisonPerformance() {
        byte[] key1 = StringUtils.toUTF8("abcdefghijabcdefghij");
        byte[] key2 = StringUtils.toUTF8("abcdefghijabcdefghij");

        for (int i = 0; i < 1000000; i++) {
            assertTrue(Key.compareKeys(key1, key2, null) == 0);
        }
    }

    @Test
    public void testKeyComparison() {
        byte[] key1 = StringUtils.toUTF8("aaa");
        byte[] key2 = StringUtils.toUTF8("aab");
        assertTrue(Key.compareKeys(key1, key2, null) < 0);
        assertTrue(Key.compareKeys(key2, key1, null) > 0);
        assertTrue(Key.compareKeys(key1, key1, null) == 0);

        key1 = StringUtils.toUTF8("aa");
        key2 = StringUtils.toUTF8("aab");
        assertTrue(Key.compareKeys(key1, key2, null) < 0);
        assertTrue(Key.compareKeys(key2, key1, null) > 0);

        key1 = StringUtils.toUTF8("");
        key2 = StringUtils.toUTF8("aab");
        assertTrue(Key.compareKeys(key1, key2, null) < 0);
        assertTrue(Key.compareKeys(key2, key1, null) > 0);
        assertTrue(Key.compareKeys(key1, key1, null) == 0);

        key1 = StringUtils.toUTF8("");
        key2 = StringUtils.toUTF8("");
        assertTrue(Key.compareKeys(key1, key2, null) == 0);

        byte[] ba1 = { -1, -1, -1 };
        byte[] ba2 = { 0x7f, 0x7f, 0x7f };
        assertTrue(Key.compareKeys(ba1, ba2, null) > 0);

        try {
            Key.compareKeys(key1, null, null);
            fail("NullPointerException not caught");
        } catch (NullPointerException NPE) {
        }
    }
}
