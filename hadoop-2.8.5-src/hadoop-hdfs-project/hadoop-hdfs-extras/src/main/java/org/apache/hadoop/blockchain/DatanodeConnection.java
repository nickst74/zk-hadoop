package org.apache.hadoop.blockchain;

import java.math.BigInteger;
import java.util.List;

import org.web3j.crypto.Hash;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

public class DatanodeConnection extends Connection {
    
    public DatanodeConnection(String blockchain, String wallet_pk){
        super(blockchain, wallet_pk);
    }

    public String upload_proofs(
    		String bp_id,
    		List<BigInteger> block_ids,
    		List<BigInteger> as,
    		List<BigInteger> bs1,
    		List<BigInteger> bs2,
    		List<BigInteger> cs) throws Exception {
        // send transaction and wait for result
        TransactionReceipt txr = this.contract_wrapper.verify(Hash.sha3(bp_id.getBytes()), block_ids, as, bs1, bs2, cs).send();
        return txr.getStatus();
    }

    public byte[] get_seed(String bp_id) throws Exception{
        return this.contract_wrapper.peek_seed(Hash.sha3(bp_id.getBytes())).send();
    }

    public void init_seed(String bp_id) throws Exception{
            this.contract_wrapper.dnode_init(Hash.sha3(bp_id.getBytes())).send();
    }

}
