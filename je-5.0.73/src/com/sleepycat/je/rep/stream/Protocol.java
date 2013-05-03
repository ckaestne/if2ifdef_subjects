/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2013 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.rep.stream;

import java.nio.ByteBuffer;
import java.util.UUID;

import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.EnvironmentFailureException;
import com.sleepycat.je.JEVersion;
import com.sleepycat.je.Durability.SyncPolicy;
import com.sleepycat.je.log.LogEntryType;
import com.sleepycat.je.log.LogUtils;
import com.sleepycat.je.rep.NodeType;
import com.sleepycat.je.rep.impl.RepNodeImpl;
import com.sleepycat.je.rep.impl.node.NameIdPair;
import com.sleepycat.je.rep.impl.node.RepNode;
import com.sleepycat.je.rep.utilint.BinaryProtocol;
import com.sleepycat.je.utilint.VLSN;

/**
 * Defines the messages used to set up a feeder-replica replication stream.
 *
 * From Feeder to Replica
 *
 *    Heartbeat -> HeartbeatResponse
 *    Commit -> Ack
 *    Entry
 * Note: in the future, we may want to support bulk entry messages
 *
 * From Replica to Feeder
 *
 * The following subset of messages represents the handshake protocol that
 * precedes the transmission of replication log entries.
 *
 *    ReplicaProtocolVersion -> FeederProtocolVersion | DuplicateNodeReject
 *    ReplicaJEVersions -> FeederJEVersions | JEVersions Reject
 *    MembershipInfo -> MembershipInfoOK | MembershipInfoReject
 *    SNTPRequest -> SNTPResponse
 *
 * Note that there may be multiple SNTPRequest/SNTPResponse message pairs that
 * are exchanged as part of a single handshake. So a successful handshake
 * requested sequence generated by the Replica looks like:
 *
 * ReplicaProtocolVersion ReplicaJEVersions MembershipInfo [SNTPRequest]+
 *
 * The following messages constitute the syncup and the transmission of log
 * entries.
 *
 *    EntryRequest -> Entry | EntryNotFound | AlternateMatchpoint
 *    RestoreRequest -> RestoreResponse
 *    StartStream
 *
 * The Protocol instance has local state in terms of buffers that are reused
 * across multiple messages. A Protocol instance is expected to be used in
 * strictly serial fashion. Consequently, there is an instance for each Replica
 * to Feeder connection, and two instances per Feeder to Replica connection:
 * one for the InputThread and one for the OutputThread.
 */
public class Protocol extends BinaryProtocol {

    /* The default (highest) version supported by the Protocol code. */
    private static int VERSION = 4;

    /* The minimum version we're willing to interact with. */
    private static int MIN_VERSION = 3;

    /*
     * Version in which HEARTBEAT_RESPONSE added a second field.  We can manage
     * without this optional additional information if we have to, we we can
     * still interact with the previous protocol version.
     */
    private static int HB_TXN_END_VLSN_VERSION = 4;

    /* The replication node that's communicating via this protocol. */
    private final RepNode repNode;

    /**
     * Returns a Protocol object configured that implements the specified
     * (supported) version.
     *
     * @param repNodethe the node using the protocol
     *
     * @param version the version of the protocol that must be implemented by
     *                this object.
     */
    private Protocol(RepNode repNode, int version) {
        super((repNode != null) ? repNode.getNameIdPair() : NameIdPair.NULL,
              VERSION,
              version,
              (repNode != null) ? repNode.getRepImpl() : null);

        /* repNode is only null during test usage. */
        this.repNode = repNode;
        this.configuredVersion = version;

        initializeMessageOps(new MessageOp[] {
            REPLICA_PROTOCOL_VERSION,
            FEEDER_PROTOCOL_VERSION,
            DUP_NODE_REJECT,
            REPLICA_JE_VERSIONS,
            FEEDER_JE_VERSIONS,
            JE_VERSIONS_REJECT,
            MEMBERSHIP_INFO,
            MEMBERSHIP_INFO_OK,
            MEMBERSHIP_INFO_REJECT,
            SNTP_REQUEST,
            SNTP_RESPONSE,
            ENTRY,
            START_STREAM,
            HEARTBEAT,
            HEARTBEAT_RESPONSE,
            COMMIT,
            ACK,
            ENTRY_REQUEST,
            ENTRY_NOTFOUND,
            RESTORE_REQUEST,
            RESTORE_RESPONSE,
            ALT_MATCHPOINT,
            SHUTDOWN_REQUEST,
            SHUTDOWN_RESPONSE
        });
    }

    /**
     * Returns a protocol object that supports the specific requested version.
     */
    public static Protocol get(RepNode repNode, int version) {
        assert(repNode != null);

        if (!isSupportedVersion(version)) {
            return null;
        }

        /*
         * Future code will do what is appropriate in support of the version
         * depending on the nature of the incompatibility.
         */
        return new Protocol(repNode, version);
    }

    static Protocol getProtocol(RepNode repNode) {
        assert(repNode != null);
        return new Protocol(repNode, VERSION);
    }

    /**
     * Returns true if the code can support the version.
     *
     * @param version being queried
     *
     * @return true if the version is supported by this implementation of the
     *              protocol.
     */
    private static boolean isSupportedVersion(int version) {
        if (version == Integer.MIN_VALUE) {
            /* For testing purposes. */
            return false;
        }

        /*
         * Version compatibility check: for now, a simple range check.  We can
         * make this fancier in the future if necessary.
         */
        return MIN_VERSION <= version && version <= VERSION;
    }

    public static int getDefaultVersion() {
        return VERSION;
    }

    /**
     * @hidden
     * For tests purposes only
     *
     * @param testVersion the version to set as the supported default version.
     */
    public static void setDefaultVersion(int testVersion) {
        VERSION = testVersion;
    }

    public final static MessageOp REPLICA_PROTOCOL_VERSION =
        new MessageOp((short) 1, ReplicaProtocolVersion.class);

    public final static MessageOp FEEDER_PROTOCOL_VERSION =
        new MessageOp((short) 2, FeederProtocolVersion.class);

    public final static MessageOp DUP_NODE_REJECT =
        new MessageOp((short) 3, DuplicateNodeReject.class);

    public final static MessageOp REPLICA_JE_VERSIONS =
        new MessageOp((short) 4, ReplicaJEVersions.class);

    public final static MessageOp FEEDER_JE_VERSIONS =
        new MessageOp((short) 5, FeederJEVersions.class);

    public final static MessageOp JE_VERSIONS_REJECT =
        new MessageOp((short) 6, JEVersionsReject.class);

    public final static MessageOp MEMBERSHIP_INFO =
        new MessageOp((short) 7, NodeGroupInfo.class);

    public final static MessageOp MEMBERSHIP_INFO_OK =
        new MessageOp((short) 8, NodeGroupInfoOK.class);

    public final static MessageOp MEMBERSHIP_INFO_REJECT =
        new MessageOp((short) 9, NodeGroupInfoReject.class);

    public final static MessageOp SNTP_REQUEST =
        new MessageOp((short)10, SNTPRequest.class);

    public final static MessageOp SNTP_RESPONSE =
        new MessageOp((short)11, SNTPResponse.class);

        /* Core Replication Stream post-handshake messages */
    public final static MessageOp ENTRY =
        new MessageOp((short) 101, Entry.class);

    public final static MessageOp START_STREAM =
        new MessageOp((short) 102, StartStream.class);

    public final static MessageOp HEARTBEAT =
        new MessageOp((short) 103, Heartbeat.class);

    public final static MessageOp HEARTBEAT_RESPONSE =
        new MessageOp((short) 104, HeartbeatResponse.class);

    public final static MessageOp COMMIT =
        new MessageOp((short) 105, Commit.class);

    public final static MessageOp ACK =
        new MessageOp((short) 106, Ack.class);

    public final static MessageOp ENTRY_REQUEST =
        new MessageOp((short) 107, EntryRequest.class);

    public final static MessageOp ENTRY_NOTFOUND =
        new MessageOp((short) 108, EntryNotFound.class);

    public final static MessageOp ALT_MATCHPOINT =
        new MessageOp((short) 109, AlternateMatchpoint.class);

    public final static MessageOp RESTORE_REQUEST =
        new MessageOp((short) 110, RestoreRequest.class);

    public final static MessageOp RESTORE_RESPONSE =
        new MessageOp((short) 111, RestoreResponse.class);

    public final static MessageOp SHUTDOWN_REQUEST =
        new MessageOp((short) 112, ShutdownRequest.class);

    public final static MessageOp SHUTDOWN_RESPONSE =
        new MessageOp((short) 113, ShutdownResponse.class);

    /**
     * Base class for all protocol handshake messages.
     */
    abstract class HandshakeMessage extends SimpleMessage {
    }

    /**
     * Version broadcasts the sending node's protocol version.
     */
    abstract class ProtocolVersion extends HandshakeMessage {
        private final int version;

        @SuppressWarnings("hiding")
        private final NameIdPair nameIdPair;

        public ProtocolVersion(int version) {
            super();
            this.version = version;
            this.nameIdPair = Protocol.this.nameIdPair;
        }

        @Override
        public ByteBuffer wireFormat() {
            return wireFormat(version, nameIdPair);
        }

        public ProtocolVersion(ByteBuffer buffer) {
            version = LogUtils.readInt(buffer);
            nameIdPair = getNameIdPair(buffer);
        }

        /**
         * @return the version
         */
        int getVersion() {
            return version;
        }

        /**
         * The nodeName of the sender
         *
         * @return nodeName
         */
        NameIdPair getNameIdPair() {
            return nameIdPair;
        }
    }

    /**
     * The replica sends the feeder its protocol version.
     *
     * IMPORTANT: This message must not change.
     */
    public class ReplicaProtocolVersion extends ProtocolVersion {

        public ReplicaProtocolVersion() {
            super(VERSION);
        }

        public ReplicaProtocolVersion(ByteBuffer buffer) {
            super(buffer);
        }

        @Override
        public MessageOp getOp() {
            return REPLICA_PROTOCOL_VERSION;
        }
    }

    /**
     * The feeder sends the replica its proposed version.
     *
     * IMPORTANT: This message must not change.
     */
    public class FeederProtocolVersion extends ProtocolVersion {

        public FeederProtocolVersion(int proposedVersion) {
            super(proposedVersion);
        }

        public FeederProtocolVersion(ByteBuffer buffer) {
            super(buffer);
        }

        @Override
        public MessageOp getOp() {
            return FEEDER_PROTOCOL_VERSION;
        }
    }

    /* Reject response to a ReplicaProtocolVersion request */
    public class DuplicateNodeReject extends RejectMessage {

        DuplicateNodeReject(String errorMessage) {
            super(errorMessage);
        }

        public DuplicateNodeReject(ByteBuffer buffer) {
            super(buffer);
        }

        @Override
        public MessageOp getOp() {
            return DUP_NODE_REJECT;
        }
    }

    public class SNTPRequest extends HandshakeMessage {

        private final long originateTimestamp;

        /* Set by the receiver at the time the message is recreated. */
        private long receiveTimestamp = -1;

        /*
         * Determines whether this is the last in a consecutive stream of
         * requests to determine the skew.
         */
        private boolean isLast = true;

        public SNTPRequest(boolean isLast) {
            super();
            this.isLast = isLast;
            originateTimestamp = repNode.getClock().currentTimeMillis();
        }

        @Override
        public ByteBuffer wireFormat() {
            return wireFormat(originateTimestamp, isLast);
        }

        public SNTPRequest(ByteBuffer buffer) {
            this.originateTimestamp = LogUtils.readLong(buffer);
            this.isLast = getBoolean(buffer);
            this.receiveTimestamp = repNode.getClock().currentTimeMillis();
        }

        @Override
        public MessageOp getOp() {
            return SNTP_REQUEST;
        }

        public long getOriginateTimestamp() {
            return originateTimestamp;
        }

        public long getReceiveTimestamp() {
            return receiveTimestamp;
        }

        public boolean isLast() {
            return isLast;
        }
    }

    public class SNTPResponse extends HandshakeMessage {

        /* These fields have the standard SNTP interpretation */
        private final long originateTimestamp; // time request sent by client
        private final long receiveTimestamp; // time request received by server

        /*
         * Initialized when the message is serialized to ensure it's as
         * accurate as possible.
         */
        private long transmitTimestamp = -1; // time reply sent by server

        /* Initialized at de-serialization for similar reasons. */
        private long destinationTimestamp = -1; //time reply received by client

        public SNTPResponse(SNTPRequest request) {
            this.originateTimestamp = request.originateTimestamp;
            this.receiveTimestamp = request.receiveTimestamp;
        }

        @Override
        public ByteBuffer wireFormat() {
            transmitTimestamp = repNode.getClock().currentTimeMillis();
            return wireFormat(originateTimestamp,
                              receiveTimestamp,
                              transmitTimestamp);
        }

        public SNTPResponse(ByteBuffer buffer) {
            originateTimestamp = LogUtils.readLong(buffer);
            receiveTimestamp = LogUtils.readLong(buffer);
            transmitTimestamp = LogUtils.readLong(buffer);
            destinationTimestamp = repNode.getClock().currentTimeMillis();
        }

        @Override
        public MessageOp getOp() {
            return SNTP_RESPONSE;
        }

        public long getOriginateTimestamp() {
            return originateTimestamp;
        }

        public long getReceiveTimestamp() {
            return receiveTimestamp;
        }

        public long getTransmitTimestamp() {
            return transmitTimestamp;
        }

        public long getDestinationTimestamp() {
            return destinationTimestamp;
        }

        public long getDelay() {
            assert(destinationTimestamp != -1);
            return (destinationTimestamp - originateTimestamp) -
                    (transmitTimestamp - receiveTimestamp);
        }

        public long getDelta() {
            assert(destinationTimestamp != -1);
            return ((receiveTimestamp - originateTimestamp) +
                    (transmitTimestamp - destinationTimestamp))/2;
        }
    }

    /**
     * Abstract message used as the basis for the exchange of software versions
     * between replicated nodes
     */
    abstract class JEVersions extends HandshakeMessage {
        private final JEVersion version;

        private final int logVersion;

        public JEVersions(JEVersion version, int logVersion) {
            this.version = version;
            this.logVersion = logVersion;
        }

        @Override
        public ByteBuffer wireFormat() {
            return wireFormat(version.getVersionString(), logVersion);
        }

        public JEVersions(ByteBuffer buffer) {
            this.version = new JEVersion(getString(buffer));
            this.logVersion = LogUtils.readInt(buffer);
        }

        public JEVersion getVersion() {
            return version;
        }

        public byte getLogVersion() {
            return (byte)logVersion;
        }
    }

    public class ReplicaJEVersions extends JEVersions {

        ReplicaJEVersions(JEVersion version, int logVersion) {
            super(version, logVersion);
        }

        public ReplicaJEVersions(ByteBuffer buffer) {
            super(buffer);
        }

        @Override
        public MessageOp getOp() {
            return REPLICA_JE_VERSIONS;
        }

    }

    public class FeederJEVersions extends JEVersions {

        FeederJEVersions(JEVersion version, int logVersion) {
            super(version, logVersion);
        }

        public FeederJEVersions(ByteBuffer buffer) {
            super(buffer);
        }

        @Override
        public MessageOp getOp() {
            return FEEDER_JE_VERSIONS;
        }
    }

    /* Reject response to a ReplicaJEVersions request */
    public class JEVersionsReject extends RejectMessage {

        public JEVersionsReject(String errorMessage) {
            super(errorMessage);
        }

        public JEVersionsReject(ByteBuffer buffer) {
            super(buffer);
        }

        @Override
        public MessageOp getOp() {
            return JE_VERSIONS_REJECT;
        }
    }

    public class NodeGroupInfo extends HandshakeMessage {
        private final String groupName;
        private final UUID uuid;

        @SuppressWarnings("hiding")
        private final NameIdPair nameIdPair;
        private final String hostName;
        private final int port;
        private final NodeType nodeType;
        private final boolean designatedPrimary;

        NodeGroupInfo(String groupName,
                      UUID uuid,
                      NameIdPair nameIdPair,
                      String hostName,
                      int port,
                      NodeType nodeType,
                      boolean designatedPrimary) {
            this.groupName = groupName;
            this.uuid = uuid;
            this.nameIdPair = nameIdPair;
            this.hostName = hostName;
            this.port = port;
            this.nodeType = nodeType;
            this.designatedPrimary = designatedPrimary;
        }

        @Override
        public MessageOp getOp() {
            return MEMBERSHIP_INFO;
        }

        @Override
        public ByteBuffer wireFormat() {
            return wireFormat(groupName,
                              uuid.getMostSignificantBits(),
                              uuid.getLeastSignificantBits(),
                              nameIdPair,
                              hostName,
                              port,
                              nodeType,
                              designatedPrimary);
        }

        public NodeGroupInfo(ByteBuffer buffer) {
            this.groupName = getString(buffer);
            this.uuid = new UUID(LogUtils.readLong(buffer),
                                 LogUtils.readLong(buffer));
            this.nameIdPair = getNameIdPair(buffer);
            this.hostName = getString(buffer);
            this.port = LogUtils.readInt(buffer);
            this.nodeType = getEnum(NodeType.class, buffer);
            this.designatedPrimary = getBoolean(buffer);
        }

        public String getGroupName() {
            return groupName;
        }

        public UUID getUUID() {
            return uuid;
        }

        public String getNodeName() {
            return nameIdPair.getName();
        }

        public int getNodeId() {
            return nameIdPair.getId();
        }

        public String getHostName() {
            return hostName;
        }

        public NameIdPair getNameIdPair() {
            return nameIdPair;
        }

        public int port() {
            return port;
        }
        public NodeType getNodeType() {
            return nodeType;
        }

        public boolean isDesignatedPrimary() {
            return designatedPrimary;
        }
    }

    public class NodeGroupInfoOK extends HandshakeMessage {

        private final UUID uuid;
        @SuppressWarnings("hiding")
        private final NameIdPair nameIdPair;

        public NodeGroupInfoOK(UUID uuid, NameIdPair nameIdPair) {
            super();
            this.uuid = uuid;
            this.nameIdPair = nameIdPair;
        }

        public NodeGroupInfoOK(ByteBuffer buffer) {
            uuid = new UUID(LogUtils.readLong(buffer),
                            LogUtils.readLong(buffer));
            nameIdPair = getNameIdPair(buffer);
        }

        @Override
        public ByteBuffer wireFormat() {
            return wireFormat(uuid.getMostSignificantBits(),
                              uuid.getLeastSignificantBits(),
                              nameIdPair);
        }

        @Override
        public MessageOp getOp() {
            return MEMBERSHIP_INFO_OK;
        }

        public NameIdPair getNameIdPair() {
            return nameIdPair;
        }

        public UUID getUUID() {
            return uuid;
        }
    }

    public class NodeGroupInfoReject extends RejectMessage {

        NodeGroupInfoReject(String errorMessage) {
            super(errorMessage);
        }

        @Override
        public MessageOp getOp() {
            return MEMBERSHIP_INFO_REJECT;
        }

        @Override
        public ByteBuffer wireFormat() {
            return wireFormat(errorMessage);
        }

        public NodeGroupInfoReject(ByteBuffer buffer) {
            super(buffer);
        }
    }

    /**
     * Base class for messages which contain only a VLSN
     */
    abstract class VLSNMessage extends Message {
        protected final VLSN vlsn;

        VLSNMessage(VLSN vlsn) {
            super();
            this.vlsn = vlsn;
        }

        public VLSNMessage(ByteBuffer buffer) {
            long vlsnSequence = LogUtils.readLong(buffer);
            vlsn = new VLSN(vlsnSequence);
        }

        @Override
        public ByteBuffer wireFormat() {
            int bodySize = 8;
            ByteBuffer messageBuffer = allocateInitializedBuffer(bodySize);
            LogUtils.writeLong(messageBuffer, vlsn.getSequence());
            messageBuffer.flip();
            return messageBuffer;
        }

        VLSN getVLSN() {
            return vlsn;
        }

        @Override
        public String toString() {
            return super.toString() + " " + vlsn;
        }
    }

    /**
     * A message containing a log entry in the replication stream.
     */
    public class Entry extends Message {

        /*
         * InputWireRecord is set when this Message had been received at this
         * node. OutputWireRecord is set when this message is created for
         * sending from this node.
         */
        protected InputWireRecord inputWireRecord;
        protected OutputWireRecord outputWireRecord;

        public Entry() {
            super();
        }

        public Entry(OutputWireRecord outputWireRecord) {
            super();
            this.outputWireRecord = outputWireRecord;
        }

        @Override
        public MessageOp getOp() {
            return ENTRY;
        }

        @Override
        public ByteBuffer wireFormat() {
            int bodySize = getWireSize();
            ByteBuffer messageBuffer = allocateInitializedBuffer(bodySize);
            outputWireRecord.writeToWire(messageBuffer);
            messageBuffer.flip();
            return messageBuffer;
        }

        protected int getWireSize() {
            return outputWireRecord.getWireSize();
        }

        public Entry(ByteBuffer buffer)
            throws DatabaseException {

            inputWireRecord =
                new InputWireRecord(repNode.getRepImpl(), buffer);
        }

        public InputWireRecord getWireRecord() {
            return inputWireRecord;
        }

        @Override
        public String toString() {

            StringBuilder sb = new StringBuilder();
            sb.append(super.toString());

            if (inputWireRecord != null) {
                sb.append(" ");
                sb.append(inputWireRecord);
            }

            if (outputWireRecord != null) {
                sb.append(" ");
                sb.append(outputWireRecord);
            }

            return sb.toString();
        }

        /* For unit test support */
        @Override
        public boolean match(Message other) {

            /*
             * This message was read in, but we need to compare it to a message
             * that was sent out.
             */
            if (outputWireRecord == null) {
                outputWireRecord = new OutputWireRecord(repNode.getRepImpl(),
                                                        inputWireRecord);
            }
            return super.match(other);
        }

        /* True if the log entry is a TxnAbort or TxnCommit. */
        public boolean isTxnEnd() {
            byte entryType = getWireRecord().getEntryType();
            if (LogEntryType.LOG_TXN_COMMIT.equalsType(entryType) ||
                LogEntryType.LOG_TXN_ABORT.equalsType(entryType)) {
                return true;
            }

            return false;
        }
    }

    /**
     * StartStream indicates that the replica would like the feeder to start
     * the replication stream at the proposed vlsn.
     */
    public class StartStream extends VLSNMessage {

        StartStream(VLSN startVLSN) {
            super(startVLSN);
        }

        public StartStream(ByteBuffer buffer) {
            super(buffer);
        }

        @Override
        public MessageOp getOp() {
            return START_STREAM;
        }
    }

    public class Heartbeat extends Message {

        private final long masterNow;
        private final long currentTxnEndVLSN;

        public Heartbeat(long masterNow, long currentTxnEndVLSN) {
            this.masterNow = masterNow;
            this.currentTxnEndVLSN = currentTxnEndVLSN;
        }

        @Override
        public MessageOp getOp() {
            return HEARTBEAT;
        }

        @Override
        public ByteBuffer wireFormat() {
            int bodySize = 8 * 2 /* masterNow + currentTxnEndVLSN */;
            ByteBuffer messageBuffer = allocateInitializedBuffer(bodySize);
            LogUtils.writeLong(messageBuffer, masterNow);
            LogUtils.writeLong(messageBuffer, currentTxnEndVLSN);
            messageBuffer.flip();
            return messageBuffer;
        }

        public Heartbeat(ByteBuffer buffer) {
            masterNow = LogUtils.readLong(buffer);
            currentTxnEndVLSN = LogUtils.readLong(buffer);
        }

        public long getMasterNow() {
            return masterNow;
        }

        public long getCurrentTxnEndVLSN() {
            return currentTxnEndVLSN;
        }

        @Override
        public String toString() {
            return super.toString() + " masterNow=" + masterNow +
                " currentCommit=" + currentTxnEndVLSN;
        }
    }

    public class HeartbeatResponse extends Message {
        /* The latest syncupVLSN */
        private final VLSN syncupVLSN;
        private final VLSN txnEndVLSN;

        public HeartbeatResponse(VLSN syncupVLSN, VLSN ackedVLSN) {
            super();
            this.syncupVLSN = syncupVLSN;
            this.txnEndVLSN = ackedVLSN;
        }

        public HeartbeatResponse(ByteBuffer buffer) {
            syncupVLSN = new VLSN(LogUtils.readLong(buffer));
            txnEndVLSN = 
                getVersion() >= HB_TXN_END_VLSN_VERSION ?
                new VLSN(LogUtils.readLong(buffer)) :
                null;
        }

        @Override
        public MessageOp getOp() {
            return HEARTBEAT_RESPONSE;
        }

        @Override
        public ByteBuffer wireFormat() {
            boolean includeTxnEndVLSN = getVersion() >= HB_TXN_END_VLSN_VERSION;
            int bodySize = includeTxnEndVLSN ?
                           8 * 2 :
                           8;
            ByteBuffer messageBuffer = allocateInitializedBuffer(bodySize);
            LogUtils.writeLong(messageBuffer, syncupVLSN.getSequence());
            if (includeTxnEndVLSN) {
                LogUtils.writeLong(messageBuffer, txnEndVLSN.getSequence());
            }
            messageBuffer.flip();
            return messageBuffer;
        }

        public VLSN getSyncupVLSN() {
            return syncupVLSN;
        }

        public VLSN getTxnEndVLSN() {
            return txnEndVLSN;
        }

        @Override
        public String toString() {
            return super.toString() + " syncupVLSN=" + syncupVLSN;
        }
    }

    /**
     * Message used to shutdown a node
     */
    public class ShutdownRequest extends SimpleMessage {
        /* The time that the shutdown was initiated on the master. */
        private final long shutdownTimeMs;

        public ShutdownRequest(long shutdownTimeMs) {
            super();
            this.shutdownTimeMs = shutdownTimeMs;
        }

        @Override
        public MessageOp getOp() {
            return SHUTDOWN_REQUEST;
        }

        public ShutdownRequest(ByteBuffer buffer) {
            shutdownTimeMs = LogUtils.readLong(buffer);
        }

        @Override
        public ByteBuffer wireFormat() {
            return wireFormat(shutdownTimeMs);
        }

        public long getShutdownTimeMs() {
            return shutdownTimeMs;
        }
    }

    /**
     * Message in response to a shutdown request.
     */
    public class ShutdownResponse extends Message {

        public ShutdownResponse() {
            super();
        }

        @Override
        public MessageOp getOp() {
            return SHUTDOWN_RESPONSE;
        }

        public ShutdownResponse(@SuppressWarnings("unused") ByteBuffer buffer) {
        }
    }

    public class Commit extends Entry {
        private boolean needsAck = true;
        private SyncPolicy replicaSyncPolicy = null;

        public Commit() { super();}

        public Commit(boolean needsAck,
                      SyncPolicy replicaSyncPolicy,
                      OutputWireRecord wireRecord) {
            super(wireRecord);
            this.needsAck = needsAck;
            this.replicaSyncPolicy = replicaSyncPolicy;
        }

        @Override
        public MessageOp getOp() {
            return COMMIT;
        }

        @Override
        public ByteBuffer wireFormat() {
            int bodySize = super.getWireSize() +
                1 /* needsAck */ +
                1 /* replica sync policy */;
            ByteBuffer messageBuffer = allocateInitializedBuffer(bodySize);
            messageBuffer.put((byte) (needsAck ? 1 : 0));
            messageBuffer.put((byte) replicaSyncPolicy.ordinal());
            outputWireRecord.writeToWire(messageBuffer);
            messageBuffer.flip();
            return messageBuffer;
        }

        public Commit(ByteBuffer buffer)
            throws DatabaseException {

            byte needsAckByte = buffer.get();
            if (needsAckByte == 0) {
               needsAck = false;
            } else if (needsAckByte == 1) {
                needsAck = true;
            } else {
                throw EnvironmentFailureException.unexpectedState
                    ("Invalid bool ordinal: " + needsAckByte);
            }
            byte syncPolicyByte = buffer.get();
            for (SyncPolicy p : SyncPolicy.values()) {
                if (p.ordinal() == syncPolicyByte) {
                    replicaSyncPolicy = p;
                    break;
                }
            }
            if (replicaSyncPolicy == null) {
                throw EnvironmentFailureException.unexpectedState
                    ("Invalid sync policy ordinal: " + syncPolicyByte);
            }
            inputWireRecord =
                new InputWireRecord(repNode.getRepImpl(), buffer);
        }

        public boolean getNeedsAck() {
            return needsAck;
        }

        public SyncPolicy getReplicaSyncPolicy() {
            return replicaSyncPolicy;
        }
    }

    public class Ack extends Message {

        private final long txnId;

        public Ack(long txnId) {
            super();
            this.txnId = txnId;
        }

        @Override
        public MessageOp getOp() {
            return ACK;
        }

        @Override
        public ByteBuffer wireFormat() {
            int bodySize = 8;
            ByteBuffer messageBuffer = allocateInitializedBuffer(bodySize);
            LogUtils.writeLong(messageBuffer, txnId);
            messageBuffer.flip();
            return messageBuffer;
        }

        public Ack(ByteBuffer buffer) {
            txnId = LogUtils.readLong(buffer);
        }

        public long getTxnId() {
            return txnId;
        }

        @Override
        public String toString() {
            return super.toString() + " txn " + txnId;
        }
    }

    /**
     * A replica node asks a feeder for the log entry at this VLSN.
     */
    public class EntryRequest extends VLSNMessage {

        EntryRequest(VLSN matchpoint) {
            super(matchpoint);
        }

        public EntryRequest(ByteBuffer buffer) {
            super(buffer);
        }

        @Override
        public MessageOp getOp() {
            return ENTRY_REQUEST;
        }
    }

    /**
     * Response when the EntryRequest asks for a VLSN that is below the VLSN
     * range covered by the Feeder.
     */
    public class EntryNotFound extends Message {

        public EntryNotFound() {
        }

        public EntryNotFound(@SuppressWarnings("unused") ByteBuffer buffer) {
            super();
        }

        @Override
        public MessageOp getOp() {
            return ENTRY_NOTFOUND;
        }
    }

    public class AlternateMatchpoint extends Message {

        private InputWireRecord alternateInput;
        private OutputWireRecord alternateOutput;

        AlternateMatchpoint(OutputWireRecord alternate) {
            super();
            this.alternateOutput = alternate;
        }

        @Override
        public MessageOp getOp() {
            return ALT_MATCHPOINT;
        }

        @Override
        public ByteBuffer wireFormat() {
            int bodySize = alternateOutput.getWireSize();
            ByteBuffer messageBuffer = allocateInitializedBuffer(bodySize);
            alternateOutput.writeToWire(messageBuffer);
            messageBuffer.flip();
            return messageBuffer;
        }

        public AlternateMatchpoint(ByteBuffer buffer)
            throws DatabaseException {
            alternateInput = new InputWireRecord(repNode.getRepImpl(), buffer);
        }

        public InputWireRecord getAlternateWireRecord() {
            return alternateInput;
        }

        /* For unit test support */
        @Override
        public boolean match(Message other) {

            /*
             * This message was read in, but we need to compare it to a message
             * that was sent out.
             */
            if (alternateOutput == null) {
                alternateOutput =
                    new OutputWireRecord(repNode.getRepImpl(), alternateInput);
            }
            return super.match(other);
        }
    }

    /**
     * Request from the replica to the feeder for sufficient information to
     * start a network restore.
     */
    public class RestoreRequest extends VLSNMessage {

        RestoreRequest(VLSN failedMatchpoint) {
            super(failedMatchpoint);
        }

        public RestoreRequest(ByteBuffer buffer) {
            super(buffer);
        }

        @Override
        public MessageOp getOp() {
            return RESTORE_REQUEST;
        }
    }

    /**
     * Response when the replica needs information to instigate a network
     * restore. The message contains two pieces of information. One is a set of
     * nodes that could be used as the basis for a NetworkBackup so that the
     * request node can become current again. The second is a suitable low vlsn
     * for the replica, which will be registered as this replica's local
     * CBVLSN. This will contribute to the global CBVLSN calculation.
     *
     * The feeder when sending this response must, if it is also the master,
     * update the membership table to set the local CBVLSN for the requesting
     * node thus ensuring that it can continue the replication stream at this
     * VLSN (or higher) when it retries the syncup operation.
     */
    public class RestoreResponse extends SimpleMessage {
        private final VLSN cbvlsn;

        private final RepNodeImpl[] logProviders;

        public RestoreResponse(VLSN cbvlsn, RepNodeImpl[] logProviders) {
            this.cbvlsn = cbvlsn;
            this.logProviders = logProviders;
        }

        public RestoreResponse(ByteBuffer buffer) {
            long vlsnSequence = LogUtils.readLong(buffer);
            cbvlsn = new VLSN(vlsnSequence);
            logProviders = getRepNodeImplArray(buffer);
        }

        @Override
        public ByteBuffer wireFormat() {
            return wireFormat(cbvlsn.getSequence(), logProviders);
        }

        @Override
        public MessageOp getOp() {
            return RESTORE_RESPONSE;
        }

        RepNodeImpl[] getLogProviders() {
            return logProviders;
        }

        VLSN getCBVLSN() {
            return cbvlsn;
        }
    }
}
