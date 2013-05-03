/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2013 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.rep.dual;

import java.util.List;

import org.junit.runners.Parameterized.Parameters;

public class SecondaryTest extends com.sleepycat.je.test.SecondaryTest {

    public SecondaryTest(String type, boolean multiKey) {
        super(type, multiKey);
    }
    
    @Parameters
    public static List<Object[]> genParams() {
        return paramsHelper(true);
    }
}
