/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2013 Oracle and/or its affiliates.  All rights reserved.
 *
 */
package com.sleepycat.je.rep;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sleepycat.je.EnvironmentFailureException;
import com.sleepycat.je.dbi.EnvironmentFailureReason;
import com.sleepycat.je.rep.ReplicatedEnvironment.State;
import com.sleepycat.je.rep.impl.RepImpl;
import com.sleepycat.je.rep.impl.node.Feeder;
import com.sleepycat.je.rep.impl.node.RepNode;
import com.sleepycat.je.rep.stream.FeederReplicaHandshake;
import com.sleepycat.je.rep.stream.Protocol;
import com.sleepycat.je.rep.utilint.RepTestUtils;
import com.sleepycat.je.rep.utilint.RepTestUtils.RepEnvInfo;
import com.sleepycat.util.test.SharedTestUtils;
import com.sleepycat.util.test.TestBase;

public class HandshakeTest extends TestBase {

    private final File envRoot;
    private final int groupSize = 4;

    private ReplicatedEnvironment master = null;
    private RepNode masterNode = null;

    RepEnvInfo[] repEnvInfo = null;
    RepEnvInfo replicaEnvInfo = null;

    public HandshakeTest() {
        envRoot = SharedTestUtils.getTestDir();
    }

    @Before
    public void setUp()
        throws Exception {

        super.setUp();
        
        repEnvInfo = RepTestUtils.setupEnvInfos(envRoot, groupSize);

        master = repEnvInfo[0].openEnv();
        replicaEnvInfo = repEnvInfo[1];
        State state = master.getState();
        masterNode = RepInternal.getRepImpl(master).getRepNode();
        assertEquals(ReplicatedEnvironment.State.MASTER, state);
    }

    @After
    public void tearDown()
        throws Exception {

        RepTestUtils.shutdownRepEnvs(repEnvInfo);
    }

    /**
     * Test error handling on a version mismatch
     */
    @Test
    public void testProtocolVersionMismatch()
        throws Throwable {

        /* Feeder is good to go */
        int defaultVersion = Protocol.getDefaultVersion();
        /* Hack the version number for the test */
        try {
            Protocol.setDefaultVersion(Integer.MIN_VALUE);
            checkForException
                (EnvironmentFailureReason.PROTOCOL_VERSION_MISMATCH);
        } finally {
            /* Restore the default version */
            Protocol.setDefaultVersion(defaultVersion + 1);
        }
    }

    /**
     * Test error handling on a log version mismatch
     */
    @Test
    public void testJEVersionMismatch()
        throws Throwable {

        try {
            FeederReplicaHandshake.setTestLogVersion(Integer.MAX_VALUE);
            checkForException(EnvironmentFailureReason.HANDSHAKE_ERROR);
        } finally {
            FeederReplicaHandshake.setTestLogVersion(0);
        }
    }

    /**
     * Test error handling when there is a duplicate replica node.
     */
    @Test
    public void testDup()
        throws Exception {

        // Introduce a fake feeder in the map
        masterNode.feederManager().putFeeder(replicaEnvInfo.getRepConfig().
                                             getNodeName(), new Feeder());
        checkForException(EnvironmentFailureReason.HANDSHAKE_ERROR);
    }

    @Test
    public void testReplicaLeadingClockSkew()
        throws Exception {

        int delta = (int) replicaEnvInfo.getRepConfig().getMaxClockDelta
            (TimeUnit.MILLISECONDS);
        try {
            RepImpl.setSkewMs(delta + 10);
            checkForException(EnvironmentFailureReason.HANDSHAKE_ERROR);
        } finally {
            RepImpl.setSkewMs(0);
        }
    }

    @Test
    public void testReplicaLaggingClockSkew()
        throws Exception {

        int delta = (int) replicaEnvInfo.getRepConfig().getMaxClockDelta
            (TimeUnit.MILLISECONDS);
        RepImpl.setSkewMs(-(delta + 10));
        checkForException(EnvironmentFailureReason.HANDSHAKE_ERROR);
        try {
            RepImpl.setSkewMs(0);
        } finally {
            RepImpl.setSkewMs(0);
        }
    }

    @Test
    public void testDuplicateSocket()
        throws Exception {

        ReplicatedEnvironment renv2 = repEnvInfo[1].openEnv();
        ReplicatedEnvironment renv3 = repEnvInfo[2].openEnv();
        renv3.close();
        try {
            ReplicationConfig config = repEnvInfo[3].getRepConfig();
            config.setNodeHostPort(repEnvInfo[2].getRepConfig().
                                   getNodeHostPort());
            ReplicatedEnvironment renv4 = repEnvInfo[3].openEnv();
            renv4.close();
            fail("Expected exception");
        } catch (EnvironmentFailureException e) {
            assertEquals(EnvironmentFailureReason.HANDSHAKE_ERROR,
                         e.getReason());
        } catch (Exception e) {
            fail ("Wrong exception type " + e);
        }
        renv2.close();
    }

    @Test
    public void testConflictingPort()
        throws Exception {

        /* Establish the node in the rep group db. */
        replicaEnvInfo.openEnv();
        replicaEnvInfo.closeEnv();

        ReplicationConfig config = replicaEnvInfo.getRepConfig();
        config.setNodeHostPort(config.getNodeHostname() + ":" + 8888 );

        checkForException(EnvironmentFailureReason.HANDSHAKE_ERROR);
    }

    @Test
    public void testConflictingType()
        throws Exception {

        /* Establish the node in the rep group db. */
        replicaEnvInfo.openEnv();
        replicaEnvInfo.closeEnv();

        ReplicationConfig config = replicaEnvInfo.getRepConfig();
        config.setNodeType(NodeType.MONITOR);

        checkForException(EnvironmentFailureReason.HANDSHAKE_ERROR);
    }

    @Test
    public void testBadGroupOnFirstOpen()
        throws Exception {

        ReplicationConfig config = replicaEnvInfo.getRepConfig();
        config.setGroupName("BAD");

        checkForException(EnvironmentFailureReason.UNEXPECTED_STATE_FATAL);
    }

    @Test
    public void testBadGroupOnReopen()
        throws Exception {

        /* Establish the node in the rep group db. */
        replicaEnvInfo.openEnv();
        replicaEnvInfo.closeEnv();

        ReplicationConfig config = replicaEnvInfo.getRepConfig();
        config.setGroupName("BAD");
        checkForException(EnvironmentFailureReason.UNEXPECTED_STATE);
    }

    private void checkForException(EnvironmentFailureReason reason) {
        try {
            replicaEnvInfo.openEnv();
            replicaEnvInfo.closeEnv();
            fail("expected exception");
        } catch (EnvironmentFailureException e) {
            assertEquals(reason, e.getReason());
        }
    }
}
