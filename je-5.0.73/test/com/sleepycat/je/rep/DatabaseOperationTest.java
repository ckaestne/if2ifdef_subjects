/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2013 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.rep;

import static com.sleepycat.persist.model.Relationship.MANY_TO_ONE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import org.junit.Test;

import com.sleepycat.bind.tuple.IntegerBinding;
import com.sleepycat.bind.tuple.StringBinding;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.DatabaseNotFoundException;
import com.sleepycat.je.DbInternal;
import com.sleepycat.je.OperationStatus;
import com.sleepycat.je.rep.dual.trigger.InvokeTest.RDBT;
import com.sleepycat.je.rep.utilint.RepTestUtils;
import com.sleepycat.je.rep.utilint.RepTestUtils.RepEnvInfo;
import com.sleepycat.je.rep.utilint.RepUtils;
import com.sleepycat.je.trigger.Trigger;
import com.sleepycat.je.utilint.VLSN;
import com.sleepycat.persist.EntityStore;
import com.sleepycat.persist.PrimaryIndex;
import com.sleepycat.persist.SecondaryIndex;
import com.sleepycat.persist.StoreConfig;
import com.sleepycat.persist.StoreNotFoundException;
import com.sleepycat.persist.model.Entity;
import com.sleepycat.persist.model.PrimaryKey;
import com.sleepycat.persist.model.SecondaryKey;
import com.sleepycat.util.test.SharedTestUtils;
import com.sleepycat.util.test.TestBase;

/**
 * Check that database operations are properly replicated.
 */
public class DatabaseOperationTest extends TestBase {

    private final File envRoot;
    private final String[] dbNames = new String[] {"DbA", "DbB"};
    private RepEnvInfo[] repEnvInfo;
    private Map<String, TestDb> expectedResults;
    private final boolean verbose = Boolean.getBoolean("verbose");

    public DatabaseOperationTest() {
        envRoot = SharedTestUtils.getTestDir();
    }

    /**
     * Check that master->replica replication of database operations work.
     */
    @Test
    public void testBasic()
        throws Exception {

        expectedResults = new HashMap<String, TestDb>();

        try {
            repEnvInfo = RepTestUtils.setupEnvInfos(envRoot, 2);
            ReplicatedEnvironment master = RepTestUtils.joinGroup(repEnvInfo);

            execDatabaseOperations(master);
            checkEquality(repEnvInfo);

            doMoreDatabaseOperations(master, repEnvInfo);
        } finally {
            RepTestUtils.shutdownRepEnvs(repEnvInfo);
        }
    }

    /* Test whether database configure changes are replayed on replicas. */
    @Test
    public void testDatabaseConfigUpdates()
        throws Exception {

        try {
            /* Open the ReplicatedEnvironments. */
            repEnvInfo = RepTestUtils.setupEnvInfos(envRoot, 2);
            ReplicatedEnvironment master = RepTestUtils.joinGroup(repEnvInfo);
            ReplicatedEnvironment replica = repEnvInfo[1].getEnv();

            assertTrue(master.getState().isMaster());
            assertTrue(replica.getState().isReplica());

            DatabaseConfig dbConfig = new DatabaseConfig();
            dbConfig.setAllowCreate(true);
            dbConfig.setTransactional(true);

            /* Open a database on the master. */
            Database db = master.openDatabase(null, "testDb", dbConfig);
            db.close();

            /* Override database properties. */
            dbConfig.setOverrideBtreeComparator(true);
            dbConfig.setOverrideDuplicateComparator(true);
            dbConfig.setOverrideTriggers(true);
            dbConfig.setBtreeComparator(new FooComparator());
            dbConfig.setDuplicateComparator(new BarComparator());
            dbConfig.setNodeMaxEntries(512);
            dbConfig.setKeyPrefixing(true);

            /* Set trigger properties. */
            List<Trigger> triggers = new LinkedList<Trigger>
                (Arrays.asList((Trigger) new RDBT("t1"),
                               (Trigger) new RDBT("t2")));
            dbConfig.setTriggers(triggers);

            db = master.openDatabase(null, "testDb", dbConfig);
            assertTrue
                (db.getConfig().getBtreeComparator() instanceof FooComparator);
            assertTrue(db.getConfig().getDuplicateComparator() 
                       instanceof BarComparator);
            assertTrue(db.getConfig().getNodeMaxEntries() == 512);
            assertTrue(db.getConfig().getKeyPrefixing());
            db.close();

            /*
             * Don't override a database BtreeComparator and make sure the
             * BtreeComparator doesn't change.
             */
            dbConfig.setOverrideBtreeComparator(false);
            dbConfig.setBtreeComparator(new BarComparator());
            db = master.openDatabase(null, "testDb", dbConfig);
            assertTrue
                (db.getConfig().getBtreeComparator() instanceof FooComparator);
            assertFalse
                (db.getConfig().getBtreeComparator() instanceof BarComparator);
            insertData(db);
            db.close();

            /* Do a sync make sure that all replicated entries are replayed. */
            VLSN vlsn = RepTestUtils.syncGroupToLastCommit(repEnvInfo,
                                                           repEnvInfo.length);
            RepTestUtils.checkNodeEquality(vlsn, false, repEnvInfo);

            /*
             * Open the database on the replica and make sure its
             * BtreeComparator is set.
             */
            dbConfig = new DatabaseConfig();
            dbConfig.setTransactional(true);
            dbConfig.setUseExistingConfig(true);
            db = replica.openDatabase(null, "testDb", dbConfig);

            /* 
             * Do the check to see configuration properties changes made on the 
             * master are replayed on the replica.
             */
            assertTrue
                (db.getConfig().getBtreeComparator() instanceof FooComparator);
            assertTrue(db.getConfig().getDuplicateComparator()
                       instanceof BarComparator);
            assertTrue(db.getConfig().getNodeMaxEntries() == 512);
            assertTrue(db.getConfig().getKeyPrefixing());
            assertTrue(db.getConfig().getTriggers().size() == 2);
            for (Trigger trigger : db.getConfig().getTriggers()) {
                assertTrue(trigger instanceof RDBT);
            }

            db.close();
        } finally {
            RepTestUtils.shutdownRepEnvs(repEnvInfo);
        }
    }

    /* 
     * Test the master updates a database config while the same name database 
     * on the replica is reading data. 
     */
    @Test
    public void testMasterUpdateWhileReplicaReading()
        throws Exception {

        try {
            /* Construct the replication group. */
            repEnvInfo = RepTestUtils.setupEnvInfos(envRoot, 2);
            ReplicatedEnvironment master = RepTestUtils.joinGroup(repEnvInfo);
            ReplicatedEnvironment replica = repEnvInfo[1].getEnv();

            assertTrue(master.getState().isMaster());
            assertTrue(replica.getState().isReplica());

            DatabaseConfig dbConfig = new DatabaseConfig();
            dbConfig.setAllowCreate(true);
            dbConfig.setTransactional(true);

            /* Open a database on the master and write some data. */
            Database db = master.openDatabase(null, "testDb", dbConfig);
            insertData(db);
            db.close();

            /* 
             * Open the database with a changed config on the replica, it is 
             * expected to fail because it requires a write operation. 
             */
            Database replicaDb = null;
            try {
                DatabaseConfig repConfig = dbConfig.clone();
                repConfig.setNodeMaxEntries(512);
                replicaDb = replica.openDatabase(null, "testDb", repConfig);
                fail("Expected exception here.");
            } catch (ReplicaWriteException e) {
                /* Expected exception. */
            } catch (Exception e) {
                fail("Unexpected exception: " + e);
            }

            /* 
             * Open the database on the replica with no database config 
             * changes, start a reading thread on the replica. 
             */
            replicaDb = replica.openDatabase(null, "testDb", dbConfig);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch end = new CountDownLatch(1);
            ReplicaReadingThread thread = 
                new ReplicaReadingThread(start, end, replicaDb);
            thread.start();
            
            /* Make sure the replica reading thread has done some jobs. */
            start.await();

            /* 
             * Do the database config changes, it would create a 
             * DatabasePreemptedException on the database on replicas. 
             */
            dbConfig.setNodeMaxEntries(512);
            db = master.openDatabase(null, "testDb", dbConfig);
            db.close();

            RepTestUtils.syncGroupToLastCommit(repEnvInfo, repEnvInfo.length);

            /* End the reading thread. */
            thread.setExit(true);
            end.await();
            assertTrue(thread.getException());

            /* 
             * Because DatabasePreemptedException, the underlying DatabaseImpl 
             * has been null, close it. 
             */
            replicaDb.close();

            /* Open the database on replica again, using the existed config. */
            dbConfig = new DatabaseConfig();
            dbConfig.setTransactional(true);
            dbConfig.setUseExistingConfig(true);
            replicaDb = replica.openDatabase(null, "testDb", dbConfig);
            assertEquals(512, replicaDb.getConfig().getNodeMaxEntries());
            replicaDb.close();
        } finally {
            RepTestUtils.shutdownRepEnvs(repEnvInfo);
        }
    }

    /**
     * Check that master->replica replication of database operations work, and
     * also verify that the client has logged enough information to act
     * as the master later on.
     */
    @Test
    public void testCascade()
        throws Exception {

        expectedResults = new HashMap<String, TestDb>();

        try {
            repEnvInfo = RepTestUtils.setupEnvInfos(envRoot, 5);

            /* Open all the replicated environments and select a master. */
            ReplicatedEnvironment master = RepTestUtils.joinGroup(repEnvInfo);
            /* Shutdown a replica. */
            for (RepEnvInfo repInfo : repEnvInfo) {
                if (repInfo.getEnv().getState().isReplica()) {
                    repInfo.closeEnv();
                    break;
                }
            }

            /* Record the former master id. */
            int formerMasterId = RepInternal.getNodeId(master);
            /* Do some database work. */
            execDatabaseOperations(master);
            /* Sync the replicators and shutdown the master. */
            checkEquality(RepTestUtils.getOpenRepEnvs(repEnvInfo));
            for (RepEnvInfo repInfo: repEnvInfo) {
                if (repInfo.getEnv() != null &&
                    repInfo.getEnv().getState().isMaster()) {
                    repInfo.closeEnv();
                    break;
                }
            }

            /* Find out the new master for those open replicators. */
            master = RepTestUtils.openRepEnvsJoin(repEnvInfo);
            /* Make sure the master is not the former one. */
            assertTrue(formerMasterId != RepInternal.getNodeId(master));
            doMoreDatabaseOperations(master,
                                     RepTestUtils.getOpenRepEnvs(repEnvInfo));

            /* Re-open closed replicators and check the node equality. */
            master = RepTestUtils.joinGroup(repEnvInfo);
            /* Verify the new master is different from the first master. */
            assertTrue(formerMasterId != RepInternal.getNodeId(master));
            assertEquals(RepTestUtils.getOpenRepEnvs(repEnvInfo).length,
                         repEnvInfo.length);
            checkEquality(repEnvInfo);
        } finally {
            RepTestUtils.shutdownRepEnvs(repEnvInfo);
        }
    }

    @Test
    public void testLocalTxnlDatabase()
        throws Exception {

        doTestLocalDatabase(true /*txnl*/);
    }

    @Test
    public void testLocalNonTxnlDatabase()
        throws Exception {

        doTestLocalDatabase(false /*txnl*/);
    }

    @Entity
    static class MyEntity {
        @PrimaryKey(sequence="id")
        int key;
        String data;
        @SecondaryKey(relate=MANY_TO_ONE)
        int skey = 1;
    }

    /**
     * Check that local (non-replicated) databases are not replicated.  Also
     * check that creating a replicated database on a replica is prohibited.
     * [#20543]
     */
    public void doTestLocalDatabase(boolean txnl)
        throws Exception {

        expectedResults = new HashMap<String, TestDb>();

        boolean success = false;

        try {
            repEnvInfo = RepTestUtils.setupEnvInfos(envRoot, 3);
            ReplicatedEnvironment master = RepTestUtils.joinGroup(repEnvInfo);

            execDatabaseOperations(master);
            checkEquality(repEnvInfo);

            final String localDbName = "LocalDb";
            final DatabaseEntry key = new DatabaseEntry();
            final DatabaseEntry data = new DatabaseEntry();
            final Database[] localDbs = new Database[repEnvInfo.length];
            final DatabaseConfig dbConfig = new DatabaseConfig();
            dbConfig.setTransactional(txnl);

            final String localStoreName = "LocalStore";
            final EntityStore[] localStores =
                new EntityStore[repEnvInfo.length];
            final StoreConfig storeConfig = new StoreConfig();
            storeConfig.setTransactional(txnl);

            /*
             * Ensure that a replicated database cannot be created on a
             * replica.
             */
            dbConfig.setAllowCreate(true);
            DbInternal.setReplicated(dbConfig, true);
            storeConfig.setAllowCreate(true);
            storeConfig.setReplicated(true);
            for (int i = 0; i < repEnvInfo.length; i += 1) {
                final ReplicatedEnvironment env = repEnvInfo[i].getEnv();
                if (env.getState().isReplica()) {

                    /* Check local Database. */
                    try {
                        env.openDatabase(null, "anotherRepDb", dbConfig);
                        fail();
                    } catch (ReplicaWriteException expected) {
                        assertTrue(txnl);
                    } catch (IllegalArgumentException expected) {
                        assertFalse(txnl);
                    }

                    /* Check local EntityStore. */
                    try {
                        new EntityStore(env, localStoreName, storeConfig);
                    } catch (ReplicaWriteException expected) {
                        assertTrue(txnl);
                    } catch (IllegalArgumentException expected) {
                        assertFalse(txnl);
                    }
                }
            }

            /*
             * Create a different local DB on each node and write a record with
             * a different data value.
             */
            dbConfig.setAllowCreate(true);
            DbInternal.setReplicated(dbConfig, false);
            storeConfig.setAllowCreate(true);
            storeConfig.setReplicated(false);
            for (int i = 0; i < repEnvInfo.length; i += 1) {
                final ReplicatedEnvironment env = repEnvInfo[i].getEnv();

                /* Check local Database. */
                final Database db =
                    env.openDatabase(null, localDbName, dbConfig);
                localDbs[i] = db;
                assertTrue(!DbInternal.getReplicated(db.getConfig()));
                key.setData(new byte[] { (byte) i });
                data.setData(new byte[] { (byte) i });
                OperationStatus status = db.put(null, key, data);
                assertSame(OperationStatus.SUCCESS, status);
                status = db.get(null, key, data, null);
                assertSame(OperationStatus.SUCCESS, status);
                assertEquals(i, data.getData()[0]);

                /* Check local EntityStore. */
                final EntityStore store =
                    new EntityStore(env, localStoreName, storeConfig);
                localStores[i] = store;
                assertTrue(!store.getConfig().getReplicated());
                final PrimaryIndex<Integer, MyEntity> index =
                    store.getPrimaryIndex(Integer.class, MyEntity.class);
                assertTrue(!DbInternal.getReplicated(index.getDatabase().
                                                           getConfig()));
                MyEntity entity = new MyEntity();
                entity.data = String.valueOf(i);
                index.put(entity);
                assertEquals(1, entity.key);
                entity = index.get(1);
                assertNotNull(entity);
                assertEquals(1, entity.key);
                assertEquals(String.valueOf(i), entity.data);
            }

            /*
             * Ensure that no records are replicated by checking that there is
             * only the one expected record on each node.
             */
            RepTestUtils.syncGroupToLastCommit(repEnvInfo, repEnvInfo.length);
            for (int i = 0; i < repEnvInfo.length; i += 1) {
                final ReplicatedEnvironment env = repEnvInfo[i].getEnv();

                /* Check local Database. */
                final Database db = localDbs[i];
                assertEquals(1, db.count());
                key.setData(new byte[] { (byte) i });
                OperationStatus status = db.get(null, key, data, null);
                assertSame(OperationStatus.SUCCESS, status);
                assertEquals(i, data.getData()[0]);
                db.close();
                env.removeDatabase(null, localDbName);

                /* Check local EntityStore. */
                final EntityStore store = localStores[i];
                final PrimaryIndex<Integer, MyEntity> index =
                    store.getPrimaryIndex(Integer.class, MyEntity.class);
                assertEquals(1, index.count());
                MyEntity entity = index.get(1);
                assertNotNull(entity);
                assertEquals(1, entity.key);
                assertEquals(String.valueOf(i), entity.data);
                store.close();
                for (String dbName : env.getDatabaseNames()) {
                    if (dbName.startsWith("persist#" + localStoreName)) {
                        env.removeDatabase(null, dbName);
                    }
                }
            }

            /* Ensure that local databases (removed above) do not exist. */
            RepTestUtils.syncGroupToLastCommit(repEnvInfo, repEnvInfo.length);
            dbConfig.setAllowCreate(false);
            DbInternal.setReplicated(dbConfig, false);
            storeConfig.setAllowCreate(false);
            storeConfig.setReplicated(false);
            for (int i = 0; i < repEnvInfo.length; i += 1) {
                final ReplicatedEnvironment env = repEnvInfo[i].getEnv();

                /* Check local Database. */
                try {
                    env.openDatabase(null, localDbName, dbConfig);
                    fail();
                } catch (DatabaseNotFoundException expected) {
                }

                /* Check local EntityStore. */
                try {
                    new EntityStore(env, localStoreName, storeConfig);
                    fail();
                } catch (StoreNotFoundException expected) {
                }
            }

            /* After removing local databases, all nodes should be equal. */
            checkEquality(repEnvInfo);
            doMoreDatabaseOperations(master, repEnvInfo);
            RepTestUtils.shutdownRepEnvs(repEnvInfo);
            success = true;
        } finally {
            if (!success) {
                try {
                    RepTestUtils.shutdownRepEnvs(repEnvInfo);
                } catch (Throwable e) {
                /* Do not preempt in-flight exception. */
                    System.out.println("Shutdown error while another " +
                                       "exception is in flight: " + e);
                }
            }
        }
    }

    /**
     * Check that with a local (non-replicated) EntityStore, auto-commit
     * transactions do not check replication consistency.  [#20543]
     */
    @Test
    public void testLocalStoreNoConsistency()
        throws IOException {

        /* Register custom consistency policy format while quiescent. */
        RepUtils.addConsistencyPolicyFormat
            (RepTestUtils.AlwaysFail.NAME,
             new RepTestUtils.AlwaysFailFormat());

        /* Open with max durabity and AlwaysFail consistency. */
        repEnvInfo = RepTestUtils.setupEnvInfos(envRoot, 3);
        for (RepEnvInfo rei : repEnvInfo) {
            rei.getEnvConfig().setDurability
                (RepTestUtils.SYNC_SYNC_ALL_DURABILITY);
            rei.getRepConfig().setConsistencyPolicy
                (new RepTestUtils.AlwaysFail());
        }
        ReplicatedEnvironment master = RepTestUtils.joinGroup(repEnvInfo);

        /* On master, create replicated store and write a record. */
        final StoreConfig repStoreConfig = new StoreConfig();
        repStoreConfig.setTransactional(true);
        repStoreConfig.setAllowCreate(true);
        final EntityStore repStore =
            new EntityStore(master, "repStore", repStoreConfig);
        final PrimaryIndex<Integer, MyEntity> repIndex =
            repStore.getPrimaryIndex(Integer.class, MyEntity.class);
        MyEntity entity = new MyEntity();
        entity.data = "aaa";
        repIndex.put(entity);
        assertEquals(1, entity.key);

        /* On replica, create local store and write/read/delete/truncate. */
        ReplicatedEnvironment replica = null;
        for (RepEnvInfo info : repEnvInfo) {
            if (info.getEnv() != master) {
                replica = info.getEnv();
                break;
            }
        }
        final StoreConfig localStoreConfig = new StoreConfig();
        localStoreConfig.setTransactional(true);
        localStoreConfig.setAllowCreate(true);
        localStoreConfig.setReplicated(false);
        final EntityStore localStore =
            new EntityStore(replica, "localStore", localStoreConfig);
        final PrimaryIndex<Integer, MyEntity> localIndex =
            localStore.getPrimaryIndex(Integer.class, MyEntity.class);
        entity = new MyEntity();
        entity.data = "aaa";
        localIndex.put(entity);
        assertEquals(1, entity.key);
        entity = localIndex.get(1);
        assertNotNull(entity);
        assertEquals(1, entity.key);
        assertEquals("aaa", entity.data);
        final SecondaryIndex<Integer, Integer, MyEntity> localSecIndex =
            localStore.getSecondaryIndex(localIndex, Integer.class, "skey");
        entity = localSecIndex.get(1);
        assertNotNull(entity);
        assertEquals(1, entity.key);
        assertEquals("aaa", entity.data);
        final boolean deleted = localIndex.delete(1);
        assertTrue(deleted);
        localStore.truncateClass(MyEntity.class);

        /* We're done. */
        localStore.close();
        repStore.close();
        RepTestUtils.shutdownRepEnvs(repEnvInfo);
    }

    /* Truncate, rename and remove databases on replicators. */
    private void doMoreDatabaseOperations(ReplicatedEnvironment master,
                                          RepEnvInfo[] repInfoArray)
        throws Exception {

        for (String dbName : dbNames) {
            truncateDatabases(master, dbName, repInfoArray);
            master.renameDatabase(null, dbName, "new" + dbName);
            checkEquality(repInfoArray);
            master.removeDatabase(null, "new" + dbName);
            checkEquality(repInfoArray);
        }
    }

    /**
     * Execute a variety of database operations on this node.
     */
    @SuppressWarnings("unchecked")
    private void execDatabaseOperations(ReplicatedEnvironment env)
        throws Exception {

        DatabaseConfig dbConfig = new DatabaseConfig();
        dbConfig.setAllowCreate(true);
        dbConfig.setTransactional(true);
        dbConfig.setSortedDuplicates(false);

        /* Make a vanilla database and add some records. */
        Database db = env.openDatabase(null, dbNames[0], dbConfig);
        insertData(db);
        expectedResults.put(dbNames[0],
                            new TestDb(db.getConfig(), db.count()));
        db.close();

        /* Make a database with comparators */
        dbConfig.setBtreeComparator(new FooComparator());
        dbConfig.setDuplicateComparator
            ((Class<Comparator<byte[]>>)
             Class.forName("com.sleepycat.je.rep." +
                           "DatabaseOperationTest$BarComparator"));
        db = env.openDatabase(null, dbNames[1], dbConfig);
        expectedResults.put(dbNames[1],
                            new TestDb(db.getConfig(), db.count()));
        db.close();
    }

    /* Insert some data for truncation verfication. */
    private void insertData(Database db)
        throws Exception {

        DatabaseEntry key = new DatabaseEntry();
        DatabaseEntry data = new DatabaseEntry();
        for (int i = 0; i < 10; i++) {
            IntegerBinding.intToEntry(i, key);
            StringBinding.stringToEntry("herococo", data);
            db.put(null, key, data);
        }
    }

    /*
     * Truncate the database on the master and check whether the db.count
     * is 0 after truncation.
     */
    private void truncateDatabases(ReplicatedEnvironment master,
                                   String dbName,
                                   RepEnvInfo[] repInfoArray)
        throws Exception {

        /* Check the correction of db.count before truncation. */
        long expectedCount = expectedResults.get(dbName).count;
        DatabaseConfig dbConfig =
            expectedResults.get(dbName).dbConfig.cloneConfig();
        checkCount(repInfoArray, dbName, dbConfig, expectedCount);

        /* Truncate the database and do the check. */
        master.truncateDatabase(null, dbName, true);
        /* Do the sync so that the replicators do the truncation. */
        RepTestUtils.syncGroupToLastCommit(repInfoArray, repInfoArray.length);
        checkCount(repInfoArray, dbName, dbConfig, 0);
        checkEquality(repInfoArray);
    }

    /* Check that the number of records in the database is correct */
    private void checkCount(RepEnvInfo[] repInfoArray,
                            String dbName,
                            DatabaseConfig dbConfig,
                            long dbCount)
        throws Exception {

        for (RepEnvInfo repInfo : repInfoArray) {
            Database db =
                repInfo.getEnv().openDatabase(null, dbName, dbConfig);
            assertEquals(dbCount, db.count());
            db.close();
        }
    }

    private void checkEquality(RepEnvInfo[] repInfoArray)
        throws Exception {

        VLSN vlsn = RepTestUtils.syncGroupToLastCommit(repInfoArray,
                                                       repInfoArray.length);
        RepTestUtils.checkNodeEquality(vlsn, verbose, repInfoArray);
    }

    /**
     * Keep track of the database name and other characteristics, to
     * be used in validating data.
     */
    static class TestDb {
        DatabaseConfig dbConfig;
        long count;

        TestDb(DatabaseConfig dbConfig, long count) {
            this.dbConfig = dbConfig.cloneConfig();
            this.count = count;
        }
    }

    /**
     * A placeholder comparator class, just for testing whether comparators
     * replicate properly.
     */
    @SuppressWarnings("serial")
    public static class FooComparator implements Comparator<byte[]>,
                                                 Serializable {

        public FooComparator() {
        }

        public int compare(@SuppressWarnings("unused") byte[] o1,
                           @SuppressWarnings("unused") byte[] o2) {
            /* No need to really fill in. */
            return 0;
        }
    }

    /**
     * A placeholder comparator class, just for testing whether comparators
     * replicate properly.
     */
    @SuppressWarnings("serial")
    public static class BarComparator implements Comparator<byte[]>,
                                                 Serializable {
        public BarComparator() {
        }

        public int compare(@SuppressWarnings("unused") byte[] arg0,
                           @SuppressWarnings("unused") byte[] arg1) {
            /* No need to really fill in. */
            return 0;
        }
    }

    /* A thread doing reads on the replica. */
    private class ReplicaReadingThread extends Thread {
        private CountDownLatch start;
        private CountDownLatch end;
        private Database db;
        private boolean exit = false;
        private boolean getException = false;

        public ReplicaReadingThread(CountDownLatch start, 
                                    CountDownLatch end, 
                                    Database db) {
            this.start = start;
            this.end = end;
            this.db = db;
        }

        public void run() {
            try {
                DatabaseEntry key = new DatabaseEntry();
                DatabaseEntry data = new DatabaseEntry();
                while (true) {
                    for (int i = 0; i < 10; i++) {
                        IntegerBinding.intToEntry(i, key);
                        try {
                            db.get(null, key, data, null);
                        } catch (DatabasePreemptedException e) {

                            /*
                             * DatabasePreemptedException is expected if the 
                             * db.get() is inovked while JE is preempting this
                             * database.
                             */
                            getException = true;
                        } catch (NullPointerException e) {

                            /*
                             * NullPointerException is expected if the db.get()
                             * is invoked after preempting this database action
                             * is finished.
                             */
                            getException = true;
                        }

                        if (!getException) {
                            assertEquals
                                ("herococo", 
                                 StringBinding.entryToString(data));
                        }
                    }

                    if (start.getCount() > 0) {
                        start.countDown();
                    }

                    if (exit && getException) {
                        break;
                    }
                }    
            } finally {
                /* Make the main thread goes on. */
                end.countDown();
            }
        }

        /* Exit the thread. */
        public void setExit(boolean exit) {
            this.exit = exit;
        }

        public boolean getException() {
            return getException;
        }
    }
}
