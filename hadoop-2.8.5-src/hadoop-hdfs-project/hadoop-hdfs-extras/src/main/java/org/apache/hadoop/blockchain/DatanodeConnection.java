package org.apache.hadoop.blockchain;

import java.math.BigInteger;
import java.util.List;

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
        TransactionReceipt txr = this.contract_wrapper.verify(bp_id, block_ids, as, bs1, bs2, cs).send();
        return txr.getStatus();
    }

    public byte[] get_seed(String bp_id) throws Exception{
        return this.contract_wrapper.peek_seed(bp_id).send();
    }

    public void init_seed(String bp_id) throws Exception{
            this.contract_wrapper.dnode_init(bp_id).send();
    }

}
