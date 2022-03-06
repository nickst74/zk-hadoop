package org.apache.hadoop.blockchain;

import java.io.IOException;
import java.math.BigInteger;
import java.util.List;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.hadoop.merkle_trees.Pair;
import org.web3j.crypto.CipherException;
import org.web3j.crypto.Hash;

public class DatanodeConnection extends Connection {
		
		private static Random randomness = new Random(System.currentTimeMillis());
    private Thread proofThread = null;
    private BlockingQueue<Pair<Pair<String, Long>, List<BigInteger>>> upload_queue = new LinkedBlockingQueue<Pair<Pair<String,Long>,List<BigInteger>>>();
		
    public DatanodeConnection(String blockchain, String password, String keystore_path, long chainId) throws IOException, CipherException{
        super(blockchain, password, keystore_path, chainId);
    }
    
    private class UploadProofT implements Runnable {
    	
    	//private List<Pair<Long,CompletableFuture<TransactionReceipt>>> f_txrs = new ArrayList<Pair<Long,CompletableFuture<TransactionReceipt>>>();
    	
			@Override
			public void run() {
				try {
					// just poll on queue for new hashes to upload
					while (true) {
						Pair<Pair<String, Long>, List<BigInteger>> toUpload = upload_queue.take();
						// terminate when you recieve pair with null value (consider all hashes uploaded)
						if(toUpload.getFirst() == null || toUpload.getSecond() == null) {
							continue;
						}
						String bp_id = toUpload.getFirst().getFirst();
						Long block_id = toUpload.getFirst().getSecond();
						List<BigInteger> numbers = toUpload.getSecond();
			      try {
							//f_txrs.add(new Pair<Long, CompletableFuture<TransactionReceipt>>(block_id,contract_wrapper.add_digest(Hash.sha3(bp_id.getBytes()), BigInteger.valueOf(block_id), root).sendAsync()));
			      	synchronized (contract_wrapper) {
				      	contract_wrapper.verify(Hash.sha3(bp_id.getBytes()), BigInteger.valueOf(block_id), numbers).sendAsync();
					      //Thread.sleep(300);
							}
						} catch (Exception e) {
							// well just ignore...
						}
					}
					// maybe dont wait, time varies too much and ruins the experiments
					//for (Pair<Long,CompletableFuture<TransactionReceipt>> ftxr : this.f_txrs) {
					//	System.out.println("Hash upload for block \""+Long.toString(ftxr.getFirst())+"\" complete with status: "+ftxr.getSecond().get().getStatus());
					//}
				} catch (Exception e) {
					// oh well, we tried...
					System.out.println("Upload proof thread got interupted. Operation failed.");
				} finally {
					proofThread = null;
					upload_queue = new LinkedBlockingQueue<Pair<Pair<String,Long>,List<BigInteger>>>();
				}
			}
    	
    }

    public void upload_proof(String bp_id, long block_id,	List<BigInteger> numbers) throws Exception {
        if(this.proofThread == null) {
        	this.proofThread = new Thread(new UploadProofT());
        	this.proofThread.start();
        }
    		// just add proof to upload queue
        this.upload_queue.add(new Pair<Pair<String,Long>, List<BigInteger>>(new Pair<String, Long>(bp_id, block_id), numbers));
        //return this.contract_wrapper.verify(Hash.sha3(bp_id.getBytes()), BigInteger.valueOf(block_id), numbers).sendAsync();
    }

    public byte[] get_seed(String bp_id) throws Exception{
    		CompletableFuture<byte[]> seed;
    		synchronized (this) {
          	seed = this.contract_wrapper.peek_seed(Hash.sha3(bp_id.getBytes())).sendAsync();
          	//Thread.sleep(100);
				}
    		return seed.get();
    }

    public void create_seed(String bp_id) throws Exception{
    		synchronized (this) {
						this.contract_wrapper.create_seed(Hash.sha3(bp_id.getBytes()), BigInteger.valueOf(Math.abs(randomness.nextLong()))).send();
    		}
    }

}
