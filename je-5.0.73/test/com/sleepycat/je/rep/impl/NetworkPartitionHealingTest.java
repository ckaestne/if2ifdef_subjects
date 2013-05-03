/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2010 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.rep.impl;

import static org.junit.Assert.assertTrue;

import java.util.logging.Logger;

import org.junit.Before;
import org.junit.Test;

import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.Transaction;
import com.sleepycat.je.rep.MasterReplicaTransitionException;
import com.sleepycat.je.rep.ReplicatedEnvironment;
import com.sleepycat.je.rep.ReplicationConfig;
import com.sleepycat.je.rep.utilint.RepTestUtils;
import com.sleepycat.je.rep.utilint.RepTestUtils.RepEnvInfo;
import com.sleepycat.je.rep.utilint.WaitForDetachedListener;
import com.sleepycat.je.rep.utilint.WaitForMasterListener;
import com.sleepycat.je.utilint.LoggerUtils;

public class NetworkPartitionHealingTest extends RepTestBase {

    private Logger logger;

    /* (non-Javadoc)
     * @see com.sleepycat.je.rep.impl.RepTestBase#setUp()
     */
    @Before
    public void setUp() 
        throws Exception {
        
        logger = LoggerUtils.getLoggerFixedPrefix(getClass(), "Test");
        groupSize = 3;
        super.setUp();
    }

    /**
     * This test captures the problem described in SR 20572 and related
     * SR 20258.
     *
     * Simulates a network partition test where a 3 node group (A, B, C) is
     * split into two: (A) and (B,C), resulting in two masters: an A and and a
     * newly elected B.
     *
     * The majority side (B,C) continues to make progress and performs durable
     * writes.
     *
     * The master on the majority side B goes down. There is now no master on
     * the (B,C) side since there is no quorum.
     *
     * The partition is healed. This should result in a master being elected on
     * the "majority" (B,C) side of the partition thus ensuring that
     * transactions are not lost.
     */
    @Test
    public void testPostNetworkPartitionMaster()
        throws DatabaseException, InterruptedException {

        /* Turn off master rebroadcasts via the large broadcast period */
        createPartitionedGroup("1000000 s");

        /* perform durable writes. */
        final RepEnvInfo rei2 = repEnvInfo[1];
        ReplicatedEnvironment env2 = rei2.getEnv();
        Transaction txn = env2.beginTransaction(null, null);
        Database db = env2.openDatabase(txn, "test", dbconfig);
        txn.commit();
        db.close();

        rei2.closeEnv();

        WaitForDetachedListener detachedWaiter = new WaitForDetachedListener();

        final RepEnvInfo rei1 = repEnvInfo[0];
        rei1.getEnv().setStateChangeListener(detachedWaiter);

        healPartition();

        rei2.openEnv();

        /* Node 1 should have detached */
        assertTrue(detachedWaiter.awaitDetached());
        assertTrue(rei1.getRepNode().getSavedShutdownException()
                   instanceof MasterReplicaTransitionException);

        /* Close the invalid handle. */
        rei1.closeEnv();

        /* Either node 2 or node 3 could have become a master. */

        /*
         * It's also possible that node2 the old master handle is invalid
         * because it could have become a master via a master query that
         * returned an obsolete result (itself) and subsequently encountered a
         * MasterReplicaTransitionException when node 3 was elected the master.
         */

        /*
         * The master must be on the majority partition's side. Check Node 3
         * first, since node2 could have been invalidated as above.
         */
        assertTrue(repEnvInfo[2].getEnv().getState().isMaster() ||
                   repEnvInfo[1].getEnv().getState().isMaster());

        /* Just verify that we can open it again after the MRT exception. */
        rei1.openEnv();

        /* Close the potentially invalid env handle. */
        rei2.closeEnv();
    }

    /**
     * Verifies that a unique master is re-established in the rep group after a
     * network partition involving a split where the master is on the minority
     * side of the network split has been resolved.
     *
     * Simulates a network partition with a master on the minority side and
     * then heals it. The obsolete master environment experiences a
     * MasterReplicaTransitionException as a result.
     *
     * 1) Start a 3 node RG. node 1 is master.
     *
     * 2) Disable Acceptor/Learner/Feeder for node 1. Simulating a network
     *    partition.
     *
     * 3) Force node 2 to be master. We now have 2 masters. With node 1 not
     *    able to process durable writes and node 2 the true master.
     *
     * 4) Heal the network partition.
     *
     * 5) Verify that node1 is informed of the new master and throws a
     *   MasterReplicaTransitionException.
     */
    @Test
    public void testPostNetworkPartition()
        throws DatabaseException, InterruptedException {

        final RepEnvInfo rei1 = repEnvInfo[0];

        createPartitionedGroup("1 s");

        WaitForDetachedListener detachedWaiter = new WaitForDetachedListener();
        rei1.getEnv().setStateChangeListener(detachedWaiter);

        /*
         * Sleep a multiple of the 1s period above. To ensure that the master
         * is broadcasting repeatedly.
         */
        Thread.sleep(10000);

        healPartition();

        assertTrue(detachedWaiter.awaitDetached());
        assertTrue(rei1.getRepNode().getSavedShutdownException()
                   instanceof MasterReplicaTransitionException);

        /* Close the invalid handle. */
        rei1.closeEnv();
    }

    /**
     * Simulates a network partitioned group with node 1 (the master) on one
     * side and nodes 2 an 3 on the other side, with node 2 being the master.
     *
     * It does so by disabling the Learner and Acceptor agents, as well as the
     * feeder service on node 1 and forcing node 2 to be the master, so that
     * node 1 is not informed that node 2 is the new master.
     */
    private void createPartitionedGroup(String rebroadcastPeriod)
        throws DatabaseException, InterruptedException {

        final RepEnvInfo rei1 = repEnvInfo[0];
        final RepEnvInfo rei2 = repEnvInfo[1];

        for (int i=0; i < groupSize; i++) {
            repEnvInfo[i].getRepConfig().setConfigParam
            (ReplicationConfig.ELECTIONS_REBROADCAST_PERIOD,
             rebroadcastPeriod);
        }

        createGroup();

        assertTrue(rei1.getEnv().getState().isMaster());

        logger.info("Simulating partition");

        RepTestUtils.disableServices(rei1);

        WaitForMasterListener masterWaiter = new WaitForMasterListener();
        rei2.getEnv().setStateChangeListener(masterWaiter);
        rei2.getRepNode().forceMaster(true);

        masterWaiter.awaitMastership();

        /* Two masters in group. */
        assertTrue(rei1.getEnv().getState().isMaster());
        assertTrue(rei2.getEnv().getState().isMaster());

        logger.info("Simulated partition");
    }

    private void healPartition() {
        logger.info("healed partition");

        final RepEnvInfo rei1 = repEnvInfo[0];
        RepTestUtils.reenableServices(rei1);
    }
}
