package org.apache.hadoop.blockchain;

import java.math.BigInteger;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.web3j.crypto.Hash;
import org.apache.hadoop.merkle_trees.Pair;
import org.apache.hadoop.merkle_trees.Util;

public class ClientConnection extends Connection {
	
		private Thread uploadThread = null;
		private BlockingQueue<Pair<Pair<String, Long>, byte[]>> upload_queue = new LinkedBlockingQueue<Pair<Pair<String,Long>,byte[]>>();

    public ClientConnection(String blockhain, String wallet_pk, String contract_address) {
        super(blockhain, wallet_pk, contract_address);
    }
    
    /**
     * Just a simple Runnable to upload hashes without blocking the main threads
     */
    private class UploadHashT implements Runnable {
    	
			@Override
			public void run() {
				try {
					// just poll on queue for new hashes to upload
					while (true) {
						Pair<Pair<String, Long>, byte[]> toUpload = upload_queue.take();
						// terminate when you recieve pair with null value (consider all hashes uploaded)
						if(toUpload.getFirst() == null || toUpload.getSecond() == null) {
							break;
						}
						String bp_id = toUpload.getFirst().getFirst();
						Long block_id = toUpload.getFirst().getSecond();
						byte[] root = toUpload.getSecond();
						// Calls should not be parallel so we dont get out of order tx sent (see tx nonce definition)
						synchronized (contract_wrapper) {
							System.out.println("Uploading hash for block: "+block_id+" -> 0x" + Util.bytesToHex(root));
			        try {
								contract_wrapper.add_digest(Hash.sha3(bp_id.getBytes()), BigInteger.valueOf(block_id), root).send();
							} catch (Exception e) {
								System.out.println("EXCEPTION for block " + Long.toString(block_id) + ": " + e.getMessage());
							}
						}
					}
				} catch (Exception e) {
					// oh well, we tried...
					System.out.println("Upload hash thread got interupted. Operation failed.");
				}
			}
    	
    }

    /**
     * Uploads merkle root hash for the given block id to the blockchain.
     * Transaction is signed and sent asynchronously, without waiting for result.
     * (UPDATE: it has to block as parallel txs can fail. See tx nonce)
     * @param bp_id BlockpoolID that the block is being uploaded to
     * @param block_id Unique identifier of the block
     * @param root The merkle root hash of the block
     */
    public void uploadHash(String bp_id, long block_id, byte[] root){
    	// just start a thread to handle it and return normal execution
    	this.upload_queue.add(new Pair<Pair<String,Long>, byte[]>(new Pair<String, Long>(bp_id, block_id), root));
    	if(this.uploadThread == null) {
    		this.uploadThread = new Thread(new UploadHashT());
    		this.uploadThread.start();
    	}
    }
    
    public void waitForUploads() {
    	// throw dummy item to the queue and wait for the uploadThread to terminate
    	this.upload_queue.add(new Pair<Pair<String,Long>, byte[]>(null, null));
    	if(this.uploadThread != null) {
    		try {
					this.uploadThread.join();
				} catch (InterruptedException e) {
					// nothing really, just leave it be
				}
    	}
    }
    
}
