/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2013 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.rep.dual;

public class ReadCommittedTest extends com.sleepycat.je.ReadCommittedTest {

    // TODO: Issue with API read lock under review
    @Override
    public void testWithTransactionConfig() {
    }

    // TODO: as above
    @Override
    public void testWithCursorConfig() {
    }

    // TODO: as above
    @Override
    public void testWithLockMode() {
    }
}
