package org.apache.hadoop.blockchain;

import java.math.BigInteger;
import java.util.List;
import java.util.Random;

import org.web3j.crypto.Hash;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

public class DatanodeConnection extends Connection {
		
		private static Random randomness = new Random(System.currentTimeMillis());
    
    public DatanodeConnection(String blockchain, String wallet_pk){
        super(blockchain, wallet_pk);
    }

    public String upload_proofs(String bp_id, long block_id,	List<BigInteger> numbers) throws Exception {
        // send transaction and wait for result
    		synchronized (this) {
          	return this.contract_wrapper.verify(Hash.sha3(bp_id.getBytes()), BigInteger.valueOf(block_id), numbers).send().getStatus();
				}
    }

    public byte[] get_seed(String bp_id) throws Exception{
    		synchronized (this) {
          	return this.contract_wrapper.peek_seed(Hash.sha3(bp_id.getBytes())).send();					
				}
    }

    public void create_seed(String bp_id) throws Exception{
    		synchronized (this) {
						this.contract_wrapper.create_seed(Hash.sha3(bp_id.getBytes()), BigInteger.valueOf(Math.abs(randomness.nextLong()))).send();
    		}
    }

}
