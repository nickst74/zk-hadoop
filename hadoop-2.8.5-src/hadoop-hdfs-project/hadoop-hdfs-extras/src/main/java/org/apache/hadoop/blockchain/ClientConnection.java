package org.apache.hadoop.blockchain;

import java.math.BigInteger;

import org.web3j.crypto.Hash;

import org.apache.hadoop.merkle_trees.Util;

public class ClientConnection extends Connection {

    public ClientConnection(String blockhain, String wallet_pk, String contract_address) {
        super(blockhain, wallet_pk, contract_address);
    }
    
    /**
     * Just a simple Runnable to upload hashes without blocking the main threads
     */
    private class UploadHashT implements Runnable {
    	
    	String bp_id;
    	long block_id;
    	byte[] root;
    	
    	public UploadHashT(String bp_id, long block_id, byte[] root) {
				this.bp_id = bp_id;
				this.block_id = block_id;
				this.root = root;
			}
    	
			@Override
			public void run() {
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
    	new Thread(new UploadHashT(bp_id, block_id, root)).start();
    }
    
}
