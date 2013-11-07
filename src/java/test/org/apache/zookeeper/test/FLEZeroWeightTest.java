/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.test;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Properties;
import java.util.Random;

import junit.framework.TestCase;

import org.apache.log4j.Logger;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.server.quorum.FastLeaderElection;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.apache.zookeeper.server.quorum.Vote;
import org.apache.zookeeper.server.quorum.QuorumPeer.QuorumServer;
import org.apache.zookeeper.server.quorum.QuorumPeer.ServerState;
import org.apache.zookeeper.server.quorum.flexible.QuorumHierarchical;
import org.junit.Before;
import org.junit.Test;

public class FLEZeroWeightTest extends TestCase {
    private static final Logger LOG = Logger.getLogger(HierarchicalQuorumTest.class);

    Properties qp;

    int count;
    HashMap<Long,QuorumServer> peers;
    ArrayList<LEThread> threads;
    File tmpdir[];
    int port[];
    Object finalObj;

    volatile Vote votes[];
    volatile boolean leaderDies;
    volatile long leader = -1;
    Random rand = new Random();


    @Before
    @Override
    protected void setUp() throws Exception {
        count = 9;

        peers = new HashMap<Long,QuorumServer>(count);
        threads = new ArrayList<LEThread>(count);
        votes = new Vote[count];
        tmpdir = new File[count];
        port = new int[count];
        finalObj = new Object();

        String config = "group.1=0:1:2\n" +
        "group.2=3:4:5\n" +
        "group.3=6:7:8\n" +
        "weight.0=1\n" +
        "weight.1=1\n" +
        "weight.2=1\n" +
        "weight.3=0\n" +
        "weight.4=0\n" +
        "weight.5=0\n" +
        "weight.6=0\n" +
        "weight.7=0\n" +
        "weight.8=0";

        ByteArrayInputStream is = new ByteArrayInputStream(config.getBytes());
        this.qp = new Properties();
        qp.load(is);

        LOG.info("SetUp " + getName());
    }

    protected void tearDown() throws Exception {
        for(int i = 0; i < threads.size(); i++) {
            LEThread leThread = threads.get(i);
            // shutdown() has to be explicitly called for every thread to
            // make sure that resources are freed properly and all fixed network ports
            // are available for other test cases
            QuorumBase.shutdown(leThread.peer);
        }
        LOG.info("FINISHED " + getName());
    }

    class LEThread extends Thread {
        int i;
        QuorumPeer peer;
        boolean fail;

        LEThread(QuorumPeer peer, int i) {
            this.i = i;
            this.peer = peer;
            LOG.info("Constructor: " + getName());
        }

        public void run() {
            try {
                Vote v = null;
                fail = false;
                while(true){

                    //while(true) {
                    peer.setPeerState(ServerState.LOOKING);
                    LOG.info("Going to call leader election.");
                    v = peer.getElectionAlg().lookForLeader();
                    if(v == null){
                        LOG.info("Thread " + i + " got a null vote");
                        return;
                    }

                    /*
                     * A real zookeeper would take care of setting the current vote. Here
                     * we do it manually.
                     */
                    peer.setCurrentVote(v);

                    LOG.info("Finished election: " + i + ", " + v.id);
                    votes[i] = v;

                    if((peer.getPeerState() == ServerState.LEADING) &&
                            (peer.getId() > 2)) fail = true;
                    
                    if((peer.getPeerState() == ServerState.FOLLOWING) ||
                            (peer.getPeerState() == ServerState.LEADING)) break;
                }
                LOG.debug("Thread " + i + " votes " + v);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    @Test
    public void testZeroWeightQuorum() throws Exception {
        FastLeaderElection le[] = new FastLeaderElection[count];

        LOG.info("TestZeroWeightQuorum: " + getName()+ ", " + count);
        for(int i = 0; i < count; i++) {
            peers.put(Long.valueOf(i),
                    new QuorumServer(i,
                            new InetSocketAddress(PortAssignment.unique()),
                    new InetSocketAddress(PortAssignment.unique())));
            tmpdir[i] = ClientBase.createTmpDir();
            port[i] = PortAssignment.unique();
        }

        for(int i = 0; i < le.length; i++) {
            QuorumHierarchical hq = new QuorumHierarchical(qp);
            QuorumPeer peer = new QuorumPeer(peers, tmpdir[i], tmpdir[i], port[i], 3, i, 1000, 2, 2, hq);
            peer.startLeaderElection();
            LEThread thread = new LEThread(peer, i);
            thread.start();
            threads.add(thread);
        }
        LOG.info("Started threads " + getName());

        for(int i = 0; i < threads.size(); i++) {
            threads.get(i).join(15000);
            if (threads.get(i).isAlive()) {
                fail("Threads didn't join");
            } else {
                if(threads.get(i).fail)
                    fail("Elected zero-weight server");
            }
        }
    }
}