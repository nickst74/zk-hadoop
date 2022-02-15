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
package org.apache.hadoop.hdfs.server.datanode;

import static org.apache.hadoop.util.Time.monotonicNow;

import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.ha.HAServiceProtocol.HAServiceState;
import org.apache.hadoop.hdfs.client.BlockReportOptions;
import org.apache.hadoop.hdfs.protocol.BlockListAsLongs;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.hdfs.protocol.ExtendedBlock;
import org.apache.hadoop.hdfs.protocol.LocatedBlock;
import org.apache.hadoop.hdfs.protocol.RollingUpgradeStatus;
import org.apache.hadoop.hdfs.protocol.UnregisteredNodeException;
import org.apache.hadoop.hdfs.protocolPB.DatanodeLifelineProtocolClientSideTranslatorPB;
import org.apache.hadoop.hdfs.protocolPB.DatanodeProtocolClientSideTranslatorPB;
import org.apache.hadoop.hdfs.server.common.IncorrectVersionException;
import org.apache.hadoop.hdfs.server.namenode.FSNamesystem;
import org.apache.hadoop.hdfs.server.protocol.BlockReportContext;
import org.apache.hadoop.hdfs.server.protocol.DatanodeCommand;
import org.apache.hadoop.hdfs.server.protocol.DatanodeRegistration;
import org.apache.hadoop.hdfs.server.protocol.DatanodeStorage;
import org.apache.hadoop.hdfs.server.protocol.DisallowedDatanodeException;
import org.apache.hadoop.hdfs.server.protocol.HeartbeatResponse;
import org.apache.hadoop.hdfs.server.protocol.NamespaceInfo;
import org.apache.hadoop.hdfs.server.protocol.StorageBlockReport;
import org.apache.hadoop.hdfs.server.protocol.StorageReport;
import org.apache.hadoop.hdfs.server.protocol.VolumeFailureSummary;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.ipc.RemoteException;
import org.apache.hadoop.merkle_trees.MerkleProof;
import org.apache.hadoop.merkle_trees.MerkleTree;
import org.apache.hadoop.merkle_trees.Util;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.util.Time;
import org.apache.hadoop.util.VersionInfo;
import org.apache.hadoop.util.VersionUtil;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;

import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_CHUNK_SIZE_KEY;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_CHUNK_SIZE_DEFAULT;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_MERKLE_TREE_HEIGHT_KEY;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_MERKLE_TREE_HEIGHT_DEFAULT;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_CHALLENGE_COUNT_KEY;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_CHALLENGE_COUNT_DEFAULT;

import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_ZOKRATES_DIR_PATH_KEY;

/**
 * A thread per active or standby namenode to perform:
 * <ul>
 * <li> Pre-registration handshake with namenode</li>
 * <li> Registration with namenode</li>
 * <li> Send periodic heartbeats to the namenode</li>
 * <li> Handle commands received from the namenode</li>
 * </ul>
 */
@InterfaceAudience.Private
class BPServiceActor implements Runnable {
  
  static final Logger LOG = DataNode.LOG;
  final InetSocketAddress nnAddr;
  HAServiceState state;

  final BPOfferService bpos;
  
  volatile long lastCacheReport = 0;
  private final Scheduler scheduler;

  Thread bpThread;
  DatanodeProtocolClientSideTranslatorPB bpNamenode;

  static enum RunningState {
    CONNECTING, INIT_FAILED, RUNNING, EXITED, FAILED;
  }

  private volatile RunningState runningState = RunningState.CONNECTING;
  private volatile boolean shouldServiceRun = true;
  private final DataNode dn;
  private final DNConf dnConf;
  private long prevBlockReportId;

  private final IncrementalBlockReportManager ibrManager;

  private DatanodeRegistration bpRegistration;
  final LinkedList<BPServiceActorAction> bpThreadQueue 
      = new LinkedList<BPServiceActorAction>();

  private long fullBlockReportLeaseId = 0;
  // just a lock for thread safety in zokrates directory
  private static ReentrantLock zok_lock = new ReentrantLock();
  // Ethereum transactions should not be sent in parallel (see tx nonce definition and use)
  // need static lock, many threads will be trying to send txs in parallel
  private static ReentrantLock ethereum_lock = new ReentrantLock();

  BPServiceActor(InetSocketAddress nnAddr, InetSocketAddress lifelineNnAddr,
      BPOfferService bpos) {
    this.bpos = bpos;
    this.dn = bpos.getDataNode();
    this.nnAddr = nnAddr;
    this.lifelineSender = lifelineNnAddr != null ?
        new LifelineSender(lifelineNnAddr) : null;
    this.initialRegistrationComplete = lifelineNnAddr != null ?
        new CountDownLatch(1) : null;
    this.dnConf = dn.getDnConf();
    this.ibrManager = new IncrementalBlockReportManager(dnConf.ibrInterval);
    prevBlockReportId = ThreadLocalRandom.current().nextLong();
    scheduler = new Scheduler(dnConf.heartBeatInterval,
        dnConf.getLifelineIntervalMs(), dnConf.blockReportInterval);
  }

  public DatanodeRegistration getBpRegistration() {
    return bpRegistration;
  }

  IncrementalBlockReportManager getIbrManager() {
    return ibrManager;
  }

  boolean isAlive() {
    if (!shouldServiceRun || !bpThread.isAlive()) {
      return false;
    }
    return runningState == BPServiceActor.RunningState.RUNNING
        || runningState == BPServiceActor.RunningState.CONNECTING;
  }

  String getRunningState() {
    return runningState.toString();
  }

  @Override
  public String toString() {
    return bpos.toString() + " service to " + nnAddr;
  }
  
  InetSocketAddress getNNSocketAddress() {
    return nnAddr;
  }

  private String getNameNodeAddress() {
    return NetUtils.getHostPortString(getNNSocketAddress());
  }

  Map<String, String> getActorInfoMap() {
    final Map<String, String> info = new HashMap<String, String>();
    info.put("NamenodeAddress", getNameNodeAddress());
    info.put("BlockPoolID", bpos.getBlockPoolId());
    info.put("ActorState", getRunningState());
    info.put("LastHeartbeat",
        String.valueOf(getScheduler().getLastHearbeatTime()));
    info.put("LastBlockReport",
        String.valueOf(getScheduler().getLastBlockReportTime()));
    return info;
  }

  private final CountDownLatch initialRegistrationComplete;
  private final LifelineSender lifelineSender;

  /**
   * Used to inject a spy NN in the unit tests.
   */
  @VisibleForTesting
  void setNameNode(DatanodeProtocolClientSideTranslatorPB dnProtocol) {
    bpNamenode = dnProtocol;
  }

  @VisibleForTesting
  DatanodeProtocolClientSideTranslatorPB getNameNodeProxy() {
    return bpNamenode;
  }

  /**
   * Used to inject a spy NN in the unit tests.
   */
  @VisibleForTesting
  void setLifelineNameNode(
      DatanodeLifelineProtocolClientSideTranslatorPB dnLifelineProtocol) {
    lifelineSender.lifelineNamenode = dnLifelineProtocol;
  }

  @VisibleForTesting
  DatanodeLifelineProtocolClientSideTranslatorPB getLifelineNameNodeProxy() {
    return lifelineSender.lifelineNamenode;
  }

  /**
   * Perform the first part of the handshake with the NameNode.
   * This calls <code>versionRequest</code> to determine the NN's
   * namespace and version info. It automatically retries until
   * the NN responds or the DN is shutting down.
   * 
   * @return the NamespaceInfo
   */
  @VisibleForTesting
  NamespaceInfo retrieveNamespaceInfo() throws IOException {
    NamespaceInfo nsInfo = null;
    while (shouldRun()) {
      try {
        nsInfo = bpNamenode.versionRequest();
        LOG.debug(this + " received versionRequest response: " + nsInfo);
        break;
      } catch(SocketTimeoutException e) {  // namenode is busy
        LOG.warn("Problem connecting to server: " + nnAddr);
      } catch(IOException e ) {  // namenode is not available
        LOG.warn("Problem connecting to server: " + nnAddr);
      }
      
      // try again in a second
      sleepAndLogInterrupts(5000, "requesting version info from NN");
    }
    
    if (nsInfo != null) {
      checkNNVersion(nsInfo);
    } else {
      throw new IOException("DN shut down before block pool connected");
    }
    return nsInfo;
  }

  private void checkNNVersion(NamespaceInfo nsInfo)
      throws IncorrectVersionException {
    // build and layout versions should match
    String nnVersion = nsInfo.getSoftwareVersion();
    String minimumNameNodeVersion = dnConf.getMinimumNameNodeVersion();
    if (VersionUtil.compareVersions(nnVersion, minimumNameNodeVersion) < 0) {
      IncorrectVersionException ive = new IncorrectVersionException(
          minimumNameNodeVersion, nnVersion, "NameNode", "DataNode");
      LOG.warn(ive.getMessage());
      throw ive;
    }
    String dnVersion = VersionInfo.getVersion();
    if (!nnVersion.equals(dnVersion)) {
      LOG.info("Reported NameNode version '" + nnVersion + "' does not match " +
          "DataNode version '" + dnVersion + "' but is within acceptable " +
          "limits. Note: This is normal during a rolling upgrade.");
    }
  }

  private void connectToNNAndHandshake() throws IOException {
    // get NN proxy
    bpNamenode = dn.connectToNN(nnAddr);

    // First phase of the handshake with NN - get the namespace
    // info.
    NamespaceInfo nsInfo = retrieveNamespaceInfo();

    // Verify that this matches the other NN in this HA pair.
    // This also initializes our block pool in the DN if we are
    // the first NN connection for this BP.
    bpos.verifyAndSetNamespaceInfo(this, nsInfo);

    /* set thread name again to include NamespaceInfo when it's available. */
    this.bpThread.setName(formatThreadName("heartbeating", nnAddr));

    // Second phase of the handshake with the NN.
    register(nsInfo);
  }


  /**
   * Run an immediate block report on this thread. Used by tests.
   */
  @VisibleForTesting
  void triggerBlockReportForTests() {
    synchronized (ibrManager) {
      scheduler.scheduleHeartbeat();
      long oldBlockReportTime = scheduler.nextBlockReportTime;
      scheduler.forceFullBlockReportNow();
      ibrManager.notifyAll();
      while (oldBlockReportTime == scheduler.nextBlockReportTime) {
        try {
          ibrManager.wait(100);
        } catch (InterruptedException e) {
          return;
        }
      }
    }
  }
  
  @VisibleForTesting
  void triggerHeartbeatForTests() {
    synchronized (ibrManager) {
      final long nextHeartbeatTime = scheduler.scheduleHeartbeat();
      ibrManager.notifyAll();
      while (nextHeartbeatTime - scheduler.nextHeartbeatTime >= 0) {
        try {
          ibrManager.wait(100);
        } catch (InterruptedException e) {
          return;
        }
      }
    }
  }

  private long generateUniqueBlockReportId() {
    // Initialize the block report ID the first time through.
    // Note that 0 is used on the NN to indicate "uninitialized", so we should
    // not send a 0 value ourselves.
    prevBlockReportId++;
    while (prevBlockReportId == 0) {
      prevBlockReportId = ThreadLocalRandom.current().nextLong();
    }
    return prevBlockReportId;
  }
  
  /**
   * Reads block from FileSystem, and builds the MerkleTree.
   * The it returns all data required to produce the zk-proofs.
   * @return The MerkleProof struct for the given block
   */
  private final class MPTask implements Callable<MerkleProof> {
    private long block_id;
    private byte[] seed;
    private int chunk_size, tree_height, chall_count;

    MPTask(long block_id, byte[] seed, int chunk_size, int tree_height, int chall_count) {
      this.block_id = block_id;
      this.seed = seed;
      this.chunk_size = chunk_size;
      this.tree_height = tree_height;
      this.chall_count = chall_count;
    }

    @Override
    public MerkleProof call() {
      // Read block from filesystem
      ExtendedBlock eb;
      try {
        eb = new ExtendedBlock(bpos.getBlockPoolId(), dn.data.getStoredBlock(bpos.getBlockPoolId(), block_id));
        InputStream in_stream = dn.data.getBlockInputStream(eb, 0);
        byte[] buffer = new byte[(int) dn.data.getLength(eb)];
        IOUtils.readFully(in_stream, buffer, 0, buffer.length);
        // Initialize and build MerkleTree
        MerkleTree tree = new MerkleTree(buffer, this.chunk_size, this.tree_height);
        tree.build();
        // return MerkleProof for proof generation phase
        return tree.getMerkleProof(this.block_id, this.seed, this.chall_count);
      } catch(IOException e){
        // if it fails, just return null so we can discard it later
        return null;
      }
    }
    
  }
  
  /*
   * Uses ZoKrates to produce the zk-proofs, and submits them to the smart contract.
   */
  class ProofGenT implements Runnable {
  	
  	class Proof {
  		long blockId;
  		List<BigInteger> as = new ArrayList<BigInteger>();
  		List<BigInteger> bs1 = new ArrayList<BigInteger>();
  		List<BigInteger> bs2 = new ArrayList<BigInteger>();
  		List<BigInteger> cs = new ArrayList<BigInteger>();
  	}
  	
  	class ZokExecutorT implements Callable<Proof> {
  		MerkleProof mp;
  		
  		public ZokExecutorT(MerkleProof mp) {
				this.mp = mp;
			}

			@Override
			public Proof call() {
				Proof result = new Proof();
				result.blockId = mp.getBlock_id();
				String zok_dir = dn.getConf().get(DFS_ZOKRATES_DIR_PATH_KEY);
				// create tmp dir to work in
				String tmp_dir = zok_dir+"/"+bpos.getBlockPoolId()+"_"+mp.getBlock_id()+"/";
				try {
					File dir = new File(tmp_dir);
					if (!dir.exists()) {
						dir.mkdir();
					}
					// init process builder to run ZoKrates
				  ProcessBuilder pb = new ProcessBuilder();
				  pb.directory(new File(zok_dir));
				  // for all challenges generate proofs
				  while(!mp.isEmpty()) {
					  List<String> compute_witness_args = mp.nextWitness();
					  compute_witness_args.addAll(Arrays.asList("-o", tmp_dir+"witness"));
					  pb.command(compute_witness_args);
					  Process pr = pb.start();
					  pr.waitFor();
					  pb.command("./zokrates", "generate-proof", "-w", tmp_dir+"witness", "-j", tmp_dir+"proof.json");
					  pr = pb.start();
					  pr.waitFor();
					  JSONParser parser = new JSONParser();
					  JSONObject obj = (JSONObject) ((JSONObject) parser.parse(new FileReader(tmp_dir+"proof.json"))).get("proof");
					  JSONArray arr_a = (JSONArray) obj.get("a");
					  JSONArray arr_b = (JSONArray) obj.get("b");
					  JSONArray arr_c = (JSONArray) obj.get("c");
					  JSONArray arr_b1 = (JSONArray) arr_b.get(0);
					  JSONArray arr_b2 = (JSONArray) arr_b.get(1);
					  result.as.add(objToBigInt(arr_a.get(0)));
					  result.as.add(objToBigInt(arr_a.get(1)));
					  result.bs1.add(objToBigInt(arr_b1.get(0)));
					  result.bs1.add(objToBigInt(arr_b1.get(1)));
					  result.bs2.add(objToBigInt(arr_b2.get(0)));
					  result.bs2.add(objToBigInt(arr_b2.get(1)));
					  result.cs.add(objToBigInt(arr_c.get(0)));
					  result.cs.add(objToBigInt(arr_c.get(1)));
					  //LOG.info("Proof for "+mp.getBlock_id()+"\n"+arr_a.get(0)+arr_a.get(1));
				  }
				} catch (Exception e) {
					LOG.warn(e.getMessage());
				  // if exception is thrown just skip all the next
				  // and fill the next challenges with dummy numbers
				  // because it will fail anyway
				  List<BigInteger> dummy = Arrays.asList(BigInteger.valueOf(0), BigInteger.valueOf(0));
				  result.as.addAll(dummy);
				  result.bs1.addAll(dummy);
				  result.bs2.addAll(dummy);
				  result.cs.addAll(dummy);
				  while(!mp.isEmpty()) {
					  mp.skip();
					  result.as.addAll(dummy);
					  result.bs1.addAll(dummy);
					  result.bs2.addAll(dummy);
					  result.cs.addAll(dummy);
				  }
				} finally {
					// cleanup and return
					for (String file : Arrays.asList("witness", "proof.json", "")) {
						new File(tmp_dir+file).delete();
					}
				}
				return result;
			}
  	}
	  
	  List<MerkleProof> mproofs;
	  
	  public ProofGenT(List<Future<MerkleProof>> fmps) {
		  this.mproofs = new ArrayList<>();
		  for(Future<MerkleProof> fmp : fmps) {
			  try {
				  MerkleProof mp = fmp.get();
				  if(mp != null) {
					  this.mproofs.add(mp);
				  }
			  } catch (InterruptedException | ExecutionException e) {
				  /* Just ignore the entry */
			}
		  }
	  }
	  
	  private BigInteger objToBigInt(Object obj) {
		  String string = (String) obj;
		  // get rid of '0x' prefix
		  return new BigInteger(string.substring(2), 16);
	  }

	  @Override
	  public void run() {
		  if(this.mproofs.isEmpty()) {
			  LOG.info("Nothing to report. Releasing proof_gen_lock.");
			  bpos.proof_gen_in_progress.set(false);
			  return;
		  }
		  Collection<Callable<Proof>> tasks = new ArrayList<>();
		  for (MerkleProof mp : this.mproofs) {
				tasks.add(new ZokExecutorT(mp));
			}

		  List<BigInteger> block_ids = new ArrayList<>();
		  List<BigInteger> as = new ArrayList<>();
		  List<BigInteger> bs1 = new ArrayList<>();
		  List<BigInteger> bs2 = new ArrayList<>();
		  List<BigInteger> cs = new ArrayList<>();
		  
		  try {
		  	// TODO: need to change to something configurable
		  	ExecutorService executor = Executors.newFixedThreadPool(Math.min(2, Runtime.getRuntime().availableProcessors()));
		  	List<Future<Proof>> fproofs = executor.invokeAll(tasks);
			  executor.shutdown();
			  for (Future<Proof> fproof : fproofs) {
					try {
						Proof proof = fproof.get();
						block_ids.add(BigInteger.valueOf(proof.blockId));
						as.addAll(proof.as);
						bs1.addAll(proof.bs1);
						bs2.addAll(proof.bs2);
						cs.addAll(proof.cs);
					} catch (Exception e) {
						// just skip
					}
				}
			} catch (Exception e) {
				LOG.warn("ZoKrates proof generation calls failed, releasing proof_gen_lock and returning.");
			  bpos.proof_gen_in_progress.set(false);
			  return;
			}
		  if(block_ids.isEmpty()) {
		  	LOG.info("Nothing to report. Releasing proof_gen_lock.");
			  bpos.proof_gen_in_progress.set(false);
			  return;
		  }
		  // upload block report to smart contracts
		  try {
		  	ethereum_lock.lock();
			  String status = dn.getCon().upload_proofs(bpos.getBlockPoolId(), block_ids, as, bs1, bs2, cs);
			  LOG.info("BlockReport on chain returned with status -> "+status);
		  } catch (Exception e) {
			  LOG.warn("Exception during on-chain BlockReport: "+e.getMessage());
		  } finally {
		  	ethereum_lock.unlock();
			  // at last release the proof_gen_lock of the blockpool
			  LOG.info("Releasing proof_gen_lock");
			  bpos.proof_gen_in_progress.set(false);
		  }
	  }
  }
  
  
  /**
   * Asynchronously generate a seed and wait until it is done
   * Only called once for every datanode-blockpool, but is done
   * on separate thread so it does not stall the block report process.
   */
  class SeedInitT implements Runnable {
	  @Override
	  public void run() {
	  	try {
	  		LOG.info("Initializing my seed");
	  		ethereum_lock.lock();
        dn.getCon().init_seed(bpos.getBlockPoolId());
        LOG.info("Seed initialized, releasing lock. No upload at first block report.");
			} catch (Exception e) {
				LOG.warn("Exception thrown while generating seed: "+e.getMessage());
			} finally {
				ethereum_lock.unlock();
        bpos.proof_gen_in_progress.set(false);
			}
	  }
  }


  /**
   * Report the list blocks to the Namenode and process any commands issued.
   * Also report to the blockchain if needed
   */
  class BlockReportT implements Runnable {
    // only upload to blockchain if reporting to ACTIVE NN
    private final boolean upload_br;

    public BlockReportT(boolean upload_br){
      this.upload_br = upload_br;
    }
    
    private boolean seedIsValid(final byte[] seed) {
    	int result = 0;
    	for(int i = 0; i < 32; i++) {
    		result |= seed[i];
    	}
    	return result != 0;
    }
    
    @Override
    public void run() {
      boolean have_lock = false;
      try {
        LOG.info("Started block report Thread for BlockPool: "+bpos.getBlockPoolId());
        final ArrayList<DatanodeCommand> cmds = new ArrayList<DatanodeCommand>();

        // Flush any block information that precedes the block report. Otherwise
        // we have a chance that we will miss the delHint information
        // or we will report an RBW replica after the BlockReport already reports
        // a FINALIZED one.
        ibrManager.sendIBRs(bpNamenode, bpRegistration,
            bpos.getBlockPoolId(), dn.getMetrics());

        long brCreateStartTime = monotonicNow();
        List<FinalizedReplica> replicas = null;
        // just keep the readlock only until you get both lists of replicas
        LOG.info("Reading Filesystem metadata while holding the lock. I handle Active NN? -> "+ upload_br);
        bpos.readLock();
        Map<DatanodeStorage, BlockListAsLongs> perVolumeBlockLists =
            dn.getFSDataset().getBlockReports(bpos.getBlockPoolId());
        if(upload_br && bpos.proof_gen_in_progress.compareAndSet(false, true)){
          have_lock = true;
          LOG.info("Grabbed the proof gen lock");
          replicas = dn.getFSDataset().getFinalizedBlocks(bpos.getBlockPoolId());
        }
        bpos.readUnlock();
        if(have_lock) {
        	LOG.info("Found Finalized Replicas for upload : " + replicas.size());
        	try {
        		byte[] seed = dn.getCon().get_seed(bpos.getBlockPoolId());
        		LOG.info("Got my seed : " + Util.bytesToHex(seed));
        		if(!seedIsValid(seed)) {
        			// first time running block report for this blockpool, generating seed
        			new Thread(new SeedInitT()).run();        			
        		} else {
        			Collection<Callable<MerkleProof>> tasks = new ArrayList<>();
        			for(FinalizedReplica replica : replicas){
                      tasks.add(new MPTask(replica.getBlockId(),
                                          seed,
                                          dn.getConf().getInt(DFS_CHUNK_SIZE_KEY, DFS_CHUNK_SIZE_DEFAULT),
                                          dn.getConf().getInt(DFS_MERKLE_TREE_HEIGHT_KEY, DFS_MERKLE_TREE_HEIGHT_DEFAULT),
                                          dn.getConf().getInt(DFS_CHALLENGE_COUNT_KEY, DFS_CHALLENGE_COUNT_DEFAULT)));
              }
              ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
              LOG.info("Starting parallel merkle proof init");
              List<Future<MerkleProof>> future_mps = executor.invokeAll(tasks);
              // after all threads have finished, start a new thread to produce and submit zk-proofs on blockchain
              ProofGenT pgt = new ProofGenT(future_mps);
              executor.shutdown();
              LOG.info("Starting proof gen Thread");
              new Thread(pgt).start();
        		}
        	} catch (Exception e) {
        		// if an exception is thrown, just abort and release lock
        		// also log the exception message (just for debugging)
        		LOG.info("Aborting on-chain report and releasing proof_gen_lock: "+e.getMessage());
        		bpos.proof_gen_in_progress.set(false);
        	}
        }

        // Convert the reports to the format expected by the NN.
        int i = 0;
        int totalBlockCount = 0;
        StorageBlockReport reports[] =
            new StorageBlockReport[perVolumeBlockLists.size()];

        for(Map.Entry<DatanodeStorage, BlockListAsLongs> kvPair : perVolumeBlockLists.entrySet()) {
          BlockListAsLongs blockList = kvPair.getValue();
          reports[i++] = new StorageBlockReport(kvPair.getKey(), blockList);
          totalBlockCount += blockList.getNumberOfBlocks();
        }

        // Send the reports to the NN.
        int numReportsSent = 0;
        int numRPCs = 0;
        boolean success = false;
        long brSendStartTime = monotonicNow();
        long reportId = generateUniqueBlockReportId();
        try {
          if (totalBlockCount < dnConf.blockReportSplitThreshold) {
            // Below split threshold, send all reports in a single message.
            DatanodeCommand cmd = bpNamenode.blockReport(
                bpRegistration, bpos.getBlockPoolId(), reports,
                  new BlockReportContext(1, 0, reportId, fullBlockReportLeaseId));
            numRPCs = 1;
            numReportsSent = reports.length;
            if (cmd != null) {
              cmds.add(cmd);
            }
          } else {
            // Send one block report per message.
            for (int r = 0; r < reports.length; r++) {
              StorageBlockReport singleReport[] = { reports[r] };
              DatanodeCommand cmd = bpNamenode.blockReport(
                  bpRegistration, bpos.getBlockPoolId(), singleReport,
                  new BlockReportContext(reports.length, r, reportId,
                      fullBlockReportLeaseId));
              numReportsSent++;
              numRPCs++;
              if (cmd != null) {
                cmds.add(cmd);
              }
            }
          }
          success = true;
        } finally {
          // Log the block report processing stats from Datanode perspective
          long brSendCost = monotonicNow() - brSendStartTime;
          long brCreateCost = brSendStartTime - brCreateStartTime;
          dn.getMetrics().addBlockReport(brSendCost);
          final int nCmds = cmds.size();
          LOG.info((success ? "S" : "Uns") +
              "uccessfully sent block report 0x" +
              Long.toHexString(reportId) + ",  containing " + reports.length +
              " storage report(s), of which we sent " + numReportsSent + "." +
              " The reports had " + totalBlockCount +
              " total blocks and used " + numRPCs +
              " RPC(s). This took " + brCreateCost +
              " msec to generate and " + brSendCost +
              " msecs for RPC and NN processing." +
              " Got back " +
              ((nCmds == 0) ? "no commands" :
                  ((nCmds == 1) ? "one command: " + cmds.get(0) :
                      (nCmds + " commands: " + Joiner.on("; ").join(cmds)))) +
              ".");
        }
        scheduler.updateLastBlockReportTime(monotonicNow());
        scheduler.scheduleNextBlockReport();
        processCommand(cmds.size() == 0 ? null : cmds.toArray(new DatanodeCommand[cmds.size()]));
      } catch (IOException e){
        e.printStackTrace();
      } finally {
        fullBlockReportLeaseId = 0;
      }
    }
  }

  DatanodeCommand cacheReport() throws IOException {
    // If caching is disabled, do not send a cache report
    if (dn.getFSDataset().getCacheCapacity() == 0) {
      return null;
    }
    // send cache report if timer has expired.
    DatanodeCommand cmd = null;
    final long startTime = monotonicNow();
    if (startTime - lastCacheReport > dnConf.cacheReportInterval) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Sending cacheReport from service actor: " + this);
      }
      lastCacheReport = startTime;

      String bpid = bpos.getBlockPoolId();
      List<Long> blockIds = dn.getFSDataset().getCacheReport(bpid);
      long createTime = monotonicNow();

      cmd = bpNamenode.cacheReport(bpRegistration, bpid, blockIds);
      long sendTime = monotonicNow();
      long createCost = createTime - startTime;
      long sendCost = sendTime - createTime;
      dn.getMetrics().addCacheReport(sendCost);
      if (LOG.isDebugEnabled()) {
        LOG.debug("CacheReport of " + blockIds.size()
            + " block(s) took " + createCost + " msec to generate and "
            + sendCost + " msecs for RPC and NN processing");
      }
    }
    return cmd;
  }
  
  HeartbeatResponse sendHeartBeat(boolean requestBlockReportLease)
      throws IOException {
    scheduler.scheduleNextHeartbeat();
    StorageReport[] reports =
        dn.getFSDataset().getStorageReports(bpos.getBlockPoolId());
    if (LOG.isDebugEnabled()) {
      LOG.debug("Sending heartbeat with " + reports.length +
                " storage reports from service actor: " + this);
    }
    
    scheduler.updateLastHeartbeatTime(monotonicNow());
    VolumeFailureSummary volumeFailureSummary = dn.getFSDataset()
        .getVolumeFailureSummary();
    int numFailedVolumes = volumeFailureSummary != null ?
        volumeFailureSummary.getFailedStorageLocations().length : 0;
    return bpNamenode.sendHeartbeat(bpRegistration,
        reports,
        dn.getFSDataset().getCacheCapacity(),
        dn.getFSDataset().getCacheUsed(),
        dn.getXmitsInProgress(),
        dn.getXceiverCount(),
        numFailedVolumes,
        volumeFailureSummary,
        requestBlockReportLease);
  }

  @VisibleForTesting
  void sendLifelineForTests() throws IOException {
    lifelineSender.sendLifeline();
  }

  //This must be called only by BPOfferService
  void start() {
    if ((bpThread != null) && (bpThread.isAlive())) {
      //Thread is started already
      return;
    }
    bpThread = new Thread(this);
    bpThread.setDaemon(true); // needed for JUnit testing
    bpThread.start();

    if (lifelineSender != null) {
      lifelineSender.start();
    }
  }

  private String formatThreadName(
      final String action,
      final InetSocketAddress addr) {
    final String prefix = bpos.getBlockPoolId() != null ? bpos.getBlockPoolId()
        : bpos.getNameserviceId();
    return prefix + " " + action + " to " + addr;
  }

  //This must be called only by blockPoolManager.
  void stop() {
    shouldServiceRun = false;
    if (lifelineSender != null) {
      lifelineSender.stop();
    }
    if (bpThread != null) {
      bpThread.interrupt();
    }
  }
  
  //This must be called only by blockPoolManager
  void join() {
    try {
      if (lifelineSender != null) {
        lifelineSender.join();
      }
      if (bpThread != null) {
        bpThread.join();
      }
    } catch (InterruptedException ie) { }
  }
  
  //Cleanup method to be called by current thread before exiting.
  private synchronized void cleanUp() {
    
    shouldServiceRun = false;
    IOUtils.cleanup(null, bpNamenode);
    IOUtils.cleanup(null, lifelineSender);
    bpos.shutdownActor(this);
  }

  private void handleRollingUpgradeStatus(HeartbeatResponse resp) throws IOException {
    RollingUpgradeStatus rollingUpgradeStatus = resp.getRollingUpdateStatus();
    if (rollingUpgradeStatus != null &&
        rollingUpgradeStatus.getBlockPoolId().compareTo(bpos.getBlockPoolId()) != 0) {
      // Can this ever occur?
      LOG.error("Invalid BlockPoolId " +
          rollingUpgradeStatus.getBlockPoolId() +
          " in HeartbeatResponse. Expected " +
          bpos.getBlockPoolId());
    } else {
      bpos.signalRollingUpgrade(rollingUpgradeStatus);
    }
  }

  /**
   * Main loop for each BP thread. Run until shutdown,
   * forever calling remote NameNode functions.
   */
  private void offerService() throws Exception {
    LOG.info("For namenode " + nnAddr + " using"
        + " BLOCKREPORT_INTERVAL of " + dnConf.blockReportInterval + "msec"
        + " CACHEREPORT_INTERVAL of " + dnConf.cacheReportInterval + "msec"
        + " Initial delay: " + dnConf.initialBlockReportDelayMs + "msec"
        + "; heartBeatInterval=" + dnConf.heartBeatInterval
        + (lifelineSender != null ?
            "; lifelineIntervalMs=" + dnConf.getLifelineIntervalMs() : ""));
    //long fullBlockReportLeaseId = 0;

    //
    // Now loop for a long time....
    //
    while (shouldRun()) {
      try {
        final long startTime = scheduler.monotonicNow();

        //
        // Every so often, send heartbeat or block-report
        //
        final boolean sendHeartbeat = scheduler.isHeartbeatDue(startTime);
        HeartbeatResponse resp = null;
        if (sendHeartbeat) {
          //
          // All heartbeat messages include following info:
          // -- Datanode name
          // -- data transfer port
          // -- Total capacity
          // -- Bytes remaining
          //
          boolean requestBlockReportLease = (fullBlockReportLeaseId == 0) &&
                  scheduler.isBlockReportDue(startTime);
          if (!dn.areHeartbeatsDisabledForTests()) {
            resp = sendHeartBeat(requestBlockReportLease);
            assert resp != null;
            if (resp.getFullBlockReportLeaseId() != 0) {
              if (fullBlockReportLeaseId != 0) {
                LOG.warn(nnAddr + " sent back a full block report lease " +
                        "ID of 0x" +
                        Long.toHexString(resp.getFullBlockReportLeaseId()) +
                        ", but we already have a lease ID of 0x" +
                        Long.toHexString(fullBlockReportLeaseId) + ". " +
                        "Overwriting old lease ID.");
              }
              fullBlockReportLeaseId = resp.getFullBlockReportLeaseId();
            }
            dn.getMetrics().addHeartbeat(scheduler.monotonicNow() - startTime);

            // If the state of this NN has changed (eg STANDBY->ACTIVE)
            // then let the BPOfferService update itself.
            //
            // Important that this happens before processCommand below,
            // since the first heartbeat to a new active might have commands
            // that we should actually process.
            bpos.updateActorStatesFromHeartbeat(
                this, resp.getNameNodeHaState());
            state = resp.getNameNodeHaState().getState();

            if (state == HAServiceState.ACTIVE) {
              handleRollingUpgradeStatus(resp);
            }

            long startProcessCommands = monotonicNow();
            if (!processCommand(resp.getCommands()))
              continue;
            long endProcessCommands = monotonicNow();
            if (endProcessCommands - startProcessCommands > 2000) {
              LOG.info("Took " + (endProcessCommands - startProcessCommands)
                  + "ms to process " + resp.getCommands().length
                  + " commands from NN");
            }
          }
        }
        if (ibrManager.sendImmediately() || sendHeartbeat) {
          ibrManager.sendIBRs(bpNamenode, bpRegistration,
              bpos.getBlockPoolId(), dn.getMetrics());
        }

        //List<DatanodeCommand> cmds = null;
        boolean forceFullBr =
            scheduler.forceFullBlockReport.getAndSet(false);
        if (forceFullBr) {
          LOG.info("Forcing a full block report to " + nnAddr);
        }
        if ((fullBlockReportLeaseId != 0) || forceFullBr) {
          //cmds = blockReport(fullBlockReportLeaseId);
          //fullBlockReportLeaseId = 0;
          new Thread(new BlockReportT(bpos.getBpServiceToActive() == this)).start();
        }
        //processCommand(cmds == null ? null : cmds.toArray(new DatanodeCommand[cmds.size()]));

        if (!dn.areCacheReportsDisabledForTests()) {
          DatanodeCommand cmd = cacheReport();
          processCommand(new DatanodeCommand[]{ cmd });
        }

        if (sendHeartbeat) {
          dn.getMetrics().addHeartbeatTotal(
              scheduler.monotonicNow() - startTime);
        }

        // There is no work to do;  sleep until hearbeat timer elapses, 
        // or work arrives, and then iterate again.
        ibrManager.waitTillNextIBR(scheduler.getHeartbeatWaitTime());
      } catch(RemoteException re) {
        String reClass = re.getClassName();
        if (UnregisteredNodeException.class.getName().equals(reClass) ||
            DisallowedDatanodeException.class.getName().equals(reClass) ||
            IncorrectVersionException.class.getName().equals(reClass)) {
          LOG.warn(this + " is shutting down", re);
          shouldServiceRun = false;
          return;
        }
        LOG.warn("RemoteException in offerService", re);
        sleepAfterException();
      } catch (IOException e) {
        LOG.warn("IOException in offerService", e);
        sleepAfterException();
      }
      processQueueMessages();
    } // while (shouldRun())
  } // offerService

  private void sleepAfterException() {
    try {
      long sleepTime = Math.min(1000, dnConf.heartBeatInterval);
      Thread.sleep(sleepTime);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Register one bp with the corresponding NameNode
   * <p>
   * The bpDatanode needs to register with the namenode on startup in order
   * 1) to report which storage it is serving now and 
   * 2) to receive a registrationID
   *  
   * issued by the namenode to recognize registered datanodes.
   * 
   * @param nsInfo current NamespaceInfo
   * @see FSNamesystem#registerDatanode(DatanodeRegistration)
   * @throws IOException
   */
  void register(NamespaceInfo nsInfo) throws IOException {
    // The handshake() phase loaded the block pool storage
    // off disk - so update the bpRegistration object from that info
    DatanodeRegistration newBpRegistration = bpos.createRegistration();

    LOG.info(this + " beginning handshake with NN");

    while (shouldRun()) {
      try {
        // Use returned registration from namenode with updated fields
        newBpRegistration = bpNamenode.registerDatanode(newBpRegistration);
        newBpRegistration.setNamespaceInfo(nsInfo);
        bpRegistration = newBpRegistration;
        break;
      } catch(EOFException e) {  // namenode might have just restarted
        LOG.info("Problem connecting to server: " + nnAddr + " :"
            + e.getLocalizedMessage());
        sleepAndLogInterrupts(1000, "connecting to server");
      } catch(SocketTimeoutException e) {  // namenode is busy
        LOG.info("Problem connecting to server: " + nnAddr);
        sleepAndLogInterrupts(1000, "connecting to server");
      }
    }
    
    LOG.info("Block pool " + this + " successfully registered with NN");
    bpos.registrationSucceeded(this, bpRegistration);

    // random short delay - helps scatter the BR from all DNs
    scheduler.scheduleBlockReport(dnConf.initialBlockReportDelayMs);
  }


  private void sleepAndLogInterrupts(int millis,
      String stateString) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException ie) {
      LOG.info("BPOfferService " + this + " interrupted while " + stateString);
    }
  }

  /**
   * No matter what kind of exception we get, keep retrying to offerService().
   * That's the loop that connects to the NameNode and provides basic DataNode
   * functionality.
   *
   * Only stop when "shouldRun" or "shouldServiceRun" is turned off, which can
   * happen either at shutdown or due to refreshNamenodes.
   */
  @Override
  public void run() {
    LOG.info(this + " starting to offer service");

    try {
      while (true) {
        // init stuff
        try {
          // setup storage
          connectToNNAndHandshake();
          break;
        } catch (IOException ioe) {
          // Initial handshake, storage recovery or registration failed
          runningState = RunningState.INIT_FAILED;
          if (shouldRetryInit()) {
            // Retry until all namenode's of BPOS failed initialization
            LOG.error("Initialization failed for " + this + " "
                + ioe.getLocalizedMessage());
            sleepAndLogInterrupts(5000, "initializing");
          } else {
            runningState = RunningState.FAILED;
            LOG.error("Initialization failed for " + this + ". Exiting. ", ioe);
            return;
          }
        }
      }

      runningState = RunningState.RUNNING;
      if (initialRegistrationComplete != null) {
        initialRegistrationComplete.countDown();
      }

      while (shouldRun()) {
        try {
          offerService();
        } catch (Exception ex) {
          LOG.error("Exception in BPOfferService for " + this, ex);
          sleepAndLogInterrupts(5000, "offering service");
        }
      }
      runningState = RunningState.EXITED;
    } catch (Throwable ex) {
      LOG.warn("Unexpected exception in block pool " + this, ex);
      runningState = RunningState.FAILED;
    } finally {
      LOG.warn("Ending block pool service for: " + this);
      cleanUp();
    }
  }

  private boolean shouldRetryInit() {
    return shouldRun() && bpos.shouldRetryInit();
  }

  private boolean shouldRun() {
    return shouldServiceRun && dn.shouldRun();
  }

  /**
   * Process an array of datanode commands
   * 
   * @param cmds an array of datanode commands
   * @return true if further processing may be required or false otherwise. 
   */
  boolean processCommand(DatanodeCommand[] cmds) {
    if (cmds != null) {
      for (DatanodeCommand cmd : cmds) {
        try {
          if (bpos.processCommandFromActor(cmd, this) == false) {
            return false;
          }
        } catch (IOException ioe) {
          LOG.warn("Error processing datanode Command", ioe);
        }
      }
    }
    return true;
  }


  /**
   * Report a bad block from another DN in this cluster.
   */
  void reportRemoteBadBlock(DatanodeInfo dnInfo, ExtendedBlock block)
      throws IOException {
    LocatedBlock lb = new LocatedBlock(block, 
                                    new DatanodeInfo[] {dnInfo});
    bpNamenode.reportBadBlocks(new LocatedBlock[] {lb});
  }

  void reRegister() throws IOException {
    if (shouldRun()) {
      // re-retrieve namespace info to make sure that, if the NN
      // was restarted, we still match its version (HDFS-2120)
      NamespaceInfo nsInfo = retrieveNamespaceInfo();
      // and re-register
      register(nsInfo);
      scheduler.scheduleHeartbeat();
      // HDFS-9917,Standby NN IBR can be very huge if standby namenode is down
      // for sometime.
      if (state == HAServiceState.STANDBY) {
        ibrManager.clearIBRs();
      }
    }
  }

  void triggerBlockReport(BlockReportOptions options) {
    if (options.isIncremental()) {
      LOG.info(bpos.toString() + ": scheduling an incremental block report.");
      ibrManager.triggerIBR(true);
    } else {
      LOG.info(bpos.toString() + ": scheduling a full block report.");
      synchronized(ibrManager) {
        scheduler.forceFullBlockReportNow();
        ibrManager.notifyAll();
      }
    }
  }
  
  public void bpThreadEnqueue(BPServiceActorAction action) {
    synchronized (bpThreadQueue) {
      if (!bpThreadQueue.contains(action)) {
        bpThreadQueue.add(action);
      }
    }
  }

  private void processQueueMessages() {
    LinkedList<BPServiceActorAction> duplicateQueue;
    synchronized (bpThreadQueue) {
      duplicateQueue = new LinkedList<BPServiceActorAction>(bpThreadQueue);
      bpThreadQueue.clear();
    }
    while (!duplicateQueue.isEmpty()) {
      BPServiceActorAction actionItem = duplicateQueue.remove();
      try {
        actionItem.reportTo(bpNamenode, bpRegistration);
      } catch (BPServiceActorActionException baae) {
        LOG.warn(baae.getMessage() + nnAddr , baae);
        // Adding it back to the queue if not present
        bpThreadEnqueue(actionItem);
      }
    }
  }

  Scheduler getScheduler() {
    return scheduler;
  }

  private final class LifelineSender implements Runnable, Closeable {

    private final InetSocketAddress lifelineNnAddr;
    private Thread lifelineThread;
    private DatanodeLifelineProtocolClientSideTranslatorPB lifelineNamenode;

    public LifelineSender(InetSocketAddress lifelineNnAddr) {
      this.lifelineNnAddr = lifelineNnAddr;
    }

    @Override
    public void close() {
      stop();
      try {
        join();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      IOUtils.cleanup(null, lifelineNamenode);
    }

    @Override
    public void run() {
      // The lifeline RPC depends on registration with the NameNode, so wait for
      // initial registration to complete.
      while (shouldRun()) {
        try {
          initialRegistrationComplete.await();
          break;
        } catch (InterruptedException e) {
          // The only way thread interruption can happen while waiting on this
          // latch is if the state of the actor has been updated to signal
          // shutdown.  The next loop's call to shouldRun() will return false,
          // and the thread will finish.
          Thread.currentThread().interrupt();
        }
      }

      // After initial NameNode registration has completed, execute the main
      // loop for sending periodic lifeline RPCs if needed.  This is done in a
      // second loop to avoid a pointless wait on the above latch in every
      // iteration of the main loop.
      while (shouldRun()) {
        try {
          if (lifelineNamenode == null) {
            lifelineNamenode = dn.connectToLifelineNN(lifelineNnAddr);
          }
          sendLifelineIfDue();
          Thread.sleep(scheduler.getLifelineWaitTime());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } catch (IOException e) {
          LOG.warn("IOException in LifelineSender for " + BPServiceActor.this,
              e);
        }
      }

      LOG.info("LifelineSender for " + BPServiceActor.this + " exiting.");
    }

    public void start() {
      lifelineThread = new Thread(this,
          formatThreadName("lifeline", lifelineNnAddr));
      lifelineThread.setDaemon(true);
      lifelineThread.setUncaughtExceptionHandler(
          new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable t) {
              LOG.error(thread + " terminating on unexpected exception", t);
            }
          });
      lifelineThread.start();
    }

    public void stop() {
      if (lifelineThread != null) {
        lifelineThread.interrupt();
      }
    }

    public void join() throws InterruptedException {
      if (lifelineThread != null) {
        lifelineThread.join();
      }
    }

    private void sendLifelineIfDue() throws IOException {
      long startTime = scheduler.monotonicNow();
      if (!scheduler.isLifelineDue(startTime)) {
        if (LOG.isDebugEnabled()) {
          LOG.debug("Skipping sending lifeline for " + BPServiceActor.this
              + ", because it is not due.");
        }
        return;
      }
      if (dn.areHeartbeatsDisabledForTests()) {
        if (LOG.isDebugEnabled()) {
          LOG.debug("Skipping sending lifeline for " + BPServiceActor.this
              + ", because heartbeats are disabled for tests.");
        }
        return;
      }
      sendLifeline();
      dn.getMetrics().addLifeline(scheduler.monotonicNow() - startTime);
      scheduler.scheduleNextLifeline(scheduler.monotonicNow());
    }

    private void sendLifeline() throws IOException {
      StorageReport[] reports =
          dn.getFSDataset().getStorageReports(bpos.getBlockPoolId());
      if (LOG.isDebugEnabled()) {
        LOG.debug("Sending lifeline with " + reports.length + " storage " +
                  " reports from service actor: " + BPServiceActor.this);
      }
      VolumeFailureSummary volumeFailureSummary = dn.getFSDataset()
          .getVolumeFailureSummary();
      int numFailedVolumes = volumeFailureSummary != null ?
          volumeFailureSummary.getFailedStorageLocations().length : 0;
      lifelineNamenode.sendLifeline(bpRegistration,
                                    reports,
                                    dn.getFSDataset().getCacheCapacity(),
                                    dn.getFSDataset().getCacheUsed(),
                                    dn.getXmitsInProgress(),
                                    dn.getXceiverCount(),
                                    numFailedVolumes,
                                    volumeFailureSummary);
    }
  }

  /**
   * Utility class that wraps the timestamp computations for scheduling
   * heartbeats and block reports.
   */
  static class Scheduler {
    // nextBlockReportTime and nextHeartbeatTime may be assigned/read
    // by testing threads (through BPServiceActor#triggerXXX), while also
    // assigned/read by the actor thread.
    @VisibleForTesting
    volatile long nextBlockReportTime = monotonicNow();

    @VisibleForTesting
    volatile long nextHeartbeatTime = monotonicNow();

    @VisibleForTesting
    volatile long nextLifelineTime;

    @VisibleForTesting
    volatile long lastBlockReportTime = monotonicNow();

    @VisibleForTesting
    volatile long lastHeartbeatTime = monotonicNow();

    @VisibleForTesting
    boolean resetBlockReportTime = true;

    private final AtomicBoolean forceFullBlockReport =
        new AtomicBoolean(false);

    private final long heartbeatIntervalMs;
    private final long lifelineIntervalMs;
    private final long blockReportIntervalMs;

    Scheduler(long heartbeatIntervalMs, long lifelineIntervalMs,
        long blockReportIntervalMs) {
      this.heartbeatIntervalMs = heartbeatIntervalMs;
      this.lifelineIntervalMs = lifelineIntervalMs;
      this.blockReportIntervalMs = blockReportIntervalMs;
      scheduleNextLifeline(nextHeartbeatTime);
    }

    // This is useful to make sure NN gets Heartbeat before Blockreport
    // upon NN restart while DN keeps retrying Otherwise,
    // 1. NN restarts.
    // 2. Heartbeat RPC will retry and succeed. NN asks DN to reregister.
    // 3. After reregistration completes, DN will send Blockreport first.
    // 4. Given NN receives Blockreport after Heartbeat, it won't mark
    //    DatanodeStorageInfo#blockContentsStale to false until the next
    //    Blockreport.
    long scheduleHeartbeat() {
      nextHeartbeatTime = monotonicNow();
      scheduleNextLifeline(nextHeartbeatTime);
      return nextHeartbeatTime;
    }

    long scheduleNextHeartbeat() {
      // Numerical overflow is possible here and is okay.
      nextHeartbeatTime = monotonicNow() + heartbeatIntervalMs;
      scheduleNextLifeline(nextHeartbeatTime);
      return nextHeartbeatTime;
    }

    void updateLastHeartbeatTime(long heartbeatTime) {
      lastHeartbeatTime = heartbeatTime;
    }

    void updateLastBlockReportTime(long blockReportTime) {
      lastBlockReportTime = blockReportTime;
    }

    long getLastHearbeatTime() {
      return (monotonicNow() - lastHeartbeatTime)/1000;
    }

    long getLastBlockReportTime() {
      return (monotonicNow() - lastBlockReportTime)/1000;
    }

    long scheduleNextLifeline(long baseTime) {
      // Numerical overflow is possible here and is okay.
      nextLifelineTime = baseTime + lifelineIntervalMs;
      return nextLifelineTime;
    }

    boolean isHeartbeatDue(long startTime) {
      return (nextHeartbeatTime - startTime <= 0);
    }

    boolean isLifelineDue(long startTime) {
      return (nextLifelineTime - startTime <= 0);
    }

    boolean isBlockReportDue(long curTime) {
      return nextBlockReportTime - curTime <= 0;
    }

    void forceFullBlockReportNow() {
      forceFullBlockReport.set(true);
      resetBlockReportTime = true;
    }

    /**
     * This methods  arranges for the data node to send the block report at
     * the next heartbeat.
     */
    long scheduleBlockReport(long delay) {
      if (delay > 0) { // send BR after random delay
        // Numerical overflow is possible here and is okay.
        nextBlockReportTime =
            monotonicNow() + ThreadLocalRandom.current().nextInt((int) (delay));
      } else { // send at next heartbeat
        nextBlockReportTime = monotonicNow();
      }
      resetBlockReportTime = true; // reset future BRs for randomness
      return nextBlockReportTime;
    }

    /**
     * Schedule the next block report after the block report interval. If the
     * current block report was delayed then the next block report is sent per
     * the original schedule.
     * Numerical overflow is possible here.
     */
    void scheduleNextBlockReport() {
      // If we have sent the first set of block reports, then wait a random
      // time before we start the periodic block reports.
      if (resetBlockReportTime) {
        nextBlockReportTime = monotonicNow() +
            ThreadLocalRandom.current().nextInt((int)(blockReportIntervalMs));
        resetBlockReportTime = false;
      } else {
        /* say the last block report was at 8:20:14. The current report
         * should have started around 14:20:14 (default 6 hour interval).
         * If current time is :
         *   1) normal like 14:20:18, next report should be at 20:20:14.
         *   2) unexpected like 21:35:43, next report should be at 2:20:14
         *      on the next day.
         */
        nextBlockReportTime +=
              (((monotonicNow() - nextBlockReportTime + blockReportIntervalMs) /
                  blockReportIntervalMs)) * blockReportIntervalMs;
      }
    }

    long getHeartbeatWaitTime() {
      return nextHeartbeatTime - monotonicNow();
    }

    long getLifelineWaitTime() {
      return nextLifelineTime - monotonicNow();
    }

    /**
     * Wrapped for testing.
     * @return
     */
    @VisibleForTesting
    public long monotonicNow() {
      return Time.monotonicNow();
    }
  }
}
