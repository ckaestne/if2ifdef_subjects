/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2013 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.rep;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.junit.Test;

import com.sleepycat.bind.tuple.IntegerBinding;
import com.sleepycat.je.Cursor;
import com.sleepycat.je.CursorConfig;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.OperationStatus;
import com.sleepycat.je.Transaction;
import com.sleepycat.je.TransactionConfig;
import com.sleepycat.je.rep.impl.node.RepNode;
import com.sleepycat.je.rep.utilint.RepTestUtils;
import com.sleepycat.je.rep.utilint.RepTestUtils.RepEnvInfo;
import com.sleepycat.je.rep.utilint.WaitForMasterListener;
import com.sleepycat.je.util.FileHandler;
import com.sleepycat.je.utilint.LoggerUtils;
import com.sleepycat.je.utilint.VLSN;
import com.sleepycat.util.test.SharedTestUtils;
import com.sleepycat.util.test.TestBase;

/**
 * Test what happens when a master node loses mastership and becomes a replica.
 */
public class MasterChangeTest extends TestBase {
    private final boolean verbose = Boolean.getBoolean("verbose");
    private static final String DB_NAME = "testDb";
    private final File envRoot;
    private final Logger logger;

    public MasterChangeTest() {
        envRoot = SharedTestUtils.getTestDir();
        logger = LoggerUtils.getLoggerFixedPrefix(getClass(), "Test");
        FileHandler.STIFLE_DEFAULT_ERROR_MANAGER = true;
    }

    /**
     * Currently, a Master cannot change from Master to Replica state without
     * going through recovery. The problem is that in order to have the proper
     * locking and transactional state as a replica, active MasterTxns must be
     * shut down, and replaced with ReplayTxns.
     *
     * A MasterReplicaException is a RestartRequiredException and will make the
     * environment go through recovery.  An alternative would have been to go
     * through the transaction manager to find and convert active transactions
     * to the proper ReplayTxn.Another option is to permit a state conversion
     * without recovery when there are no active transactions. The added
     * complexity did not seem worth the benefit of reducing the need to reopen
     * the environment handle in limited situations. [#19177]
     *
     * This test case checks that a master node that becomes a replica throws
     * MasterReplicaTransitionException, and functions properly after reopening.
     */
    @Test
    public void testMasterBecomesReplica()
        throws Throwable {

        RepEnvInfo[] repEnvInfo = null;
        Database db = null;
        int numNodes = 3;
        try {
            repEnvInfo = RepTestUtils.setupEnvInfos
                (envRoot, numNodes, RepTestUtils.SYNC_SYNC_NONE_DURABILITY);

            /*
             * Start the master first, to ensure that it is the master, and then
             * start the rest of the group.
             */
            ReplicatedEnvironment master = repEnvInfo[0].openEnv();
            assert master != null;

            db = createDb(master);
            db.close();
            Transaction oldMasterIncompleteTxn1 = doInsert(master, 1, false);

            for (int i = 1; i < numNodes; i++) {
                repEnvInfo[i].openEnv();
            }

            /*
             * After node1 and node2 join, make sure that their presence in the
             * rep group db is propagated before we do a forceMaster, by doing
             * a consistent read. When a node calls for an election, it must
             * have its own id available to itself from the rep group db on
             * disk. If it doesn't, it will send an election request with an
             * illegal node id. In real life, this can never happen, because a
             * node that does not have its own id won't win mastership, since
             * others will be ahead of it.
             */
            Transaction oldMasterTxn2 = doInsert(master, 2, true);
            makeReplicasConsistent(oldMasterTxn2, repEnvInfo[1], repEnvInfo[2]);

            /*
             * Mimic a network partition by forcing one replica to become the
             * master.
             */
            int lastIndex = numNodes - 1;
            WaitForMasterListener masterWaiter = new WaitForMasterListener();
            ReplicatedEnvironment forcedMaster = repEnvInfo[lastIndex].getEnv();
            forcedMaster.setStateChangeListener(masterWaiter);
            RepNode lastRepNode =  repEnvInfo[lastIndex].getRepNode();

            /*
             * Do one last incomplete transaction on the master, used to test
             * that the transaction will later be aborted after mastership
             * transfer.
             */
            @SuppressWarnings("unused")
            Transaction oldMasterIncompleteTxn3 = doInsert(master, 3, false);

            /*
             * Make node3 the master, and do some work on it.
             */
            lastRepNode.forceMaster(true);
            masterWaiter.awaitMastership();

            /*
             * We expect the old master to have experienced a restart required
             * exception.
             */
            RepTestUtils.checkForRestart(repEnvInfo[0], logger);

            /* Insert and commit on the new master. */
            Transaction txn5 = doInsert(forcedMaster, 4, true);

            /*
             * Sync up the group. We should now see records 2 and 4 on all
             * nodes, but records 1 and 3 were not committed, and should be
             * aborted.
             */
            logger.info("sync group");
            VLSN commitVLSN = RepTestUtils.syncGroupToLastCommit(repEnvInfo,
                                                                 numNodes);

            /*
             * Make sure we can do a transactional cursor scan of the data on
             * all nodes. This will ensure that we don't have any dangling
             * locks.
             */
            scanData(repEnvInfo);

            logger.info("run check");

            RepTestUtils.checkNodeEquality(commitVLSN, verbose, repEnvInfo);
        } catch (Throwable e) {
            e.printStackTrace();
            throw e;
        } finally {
            if (db != null) {
                db.close();
                db = null;
            }
            RepTestUtils.shutdownRepEnvs(repEnvInfo);
        }
    }

    /**
     * Do a transactional cursor scan and check that records 2 and 4 (only)
     * exist.Using a transactional cursor means we'll check for dangling locks.
     * If a lock is left over, it will cause a deadlock.
     */
    private void scanData(RepEnvInfo[] repEnvInfo) {
        for (RepEnvInfo info: repEnvInfo) {
            Database db = openDb(info.getEnv());
            Cursor c = db.openCursor(null, CursorConfig.READ_COMMITTED);
            DatabaseEntry key = new DatabaseEntry();
            DatabaseEntry data = new DatabaseEntry();

            /* Should be record 2 */
            assertEquals(OperationStatus.SUCCESS, c.getNext(key, data, null));
            assertEquals(2, IntegerBinding.entryToInt(key));
            assertEquals(OperationStatus.SUCCESS, c.getNext(key, data, null));
            assertEquals(4, IntegerBinding.entryToInt(key));
            assertEquals(OperationStatus.NOTFOUND,  c.getNext(key, data, null));

            c.close();
            db.close();
        }
    }

    private Database createDb(ReplicatedEnvironment master) {
        return openDb(master, true);
    }

    private Database openDb(ReplicatedEnvironment master) {
        return openDb(master, false);
    }

    private Database openDb(ReplicatedEnvironment master, boolean allowCreate) {

        Transaction txn = master.beginTransaction(null,null);
        DatabaseConfig dbConfig = new DatabaseConfig();
        dbConfig.setAllowCreate(allowCreate);
        dbConfig.setTransactional(true);
        Database db = master.openDatabase(txn, DB_NAME, dbConfig);
        txn.commit();
        return db;
    }

    /**
     * @return the transaction for the unfinished txn
     */
    private Transaction doInsert(ReplicatedEnvironment master,
                                 int val,
                                 boolean doCommit) {

        DatabaseEntry key = new DatabaseEntry();
        DatabaseEntry data = new DatabaseEntry();
        Transaction txn = null;

        Database db = openDb(master);
        txn = master.beginTransaction(null, null);
        IntegerBinding.intToEntry(val, key);
        IntegerBinding.intToEntry(val, data);
        assertEquals(OperationStatus.SUCCESS, db.put(txn, key, data));

        if (doCommit) {
            txn.commit();
        }

        db.close();

        return txn;
    }

    /**
     * Ensure that the specified nodes are consistent.with the specified
     * transaction.
     */
    private void makeReplicasConsistent(Transaction targetTxn,
                                        RepEnvInfo... repEnvInfo) {
        TransactionConfig txnConfig = new TransactionConfig();
        txnConfig.setConsistencyPolicy(new CommitPointConsistencyPolicy
                                       (targetTxn.getCommitToken(),
                                        1000,
                                        TimeUnit.SECONDS));

        for (RepEnvInfo info : repEnvInfo) {

            /*
             * Open a read transaction that forces the replicas to become
             * consistent.
             */
            Transaction txn = info.getEnv().beginTransaction(null, txnConfig);
            txn.commit();
        }
    }
}
