package org.apache.hadoop.blockchain;

import java.math.BigInteger;
import java.util.List;
import java.util.Arrays;
import java.util.LinkedList;

import org.apache.hadoop.merkle_trees.Proof;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

public class DatanodeConnection extends Connection {
    
    public DatanodeConnection(String blockchain, String wallet_pk){
        super(blockchain, wallet_pk);
    }

    private static class FlattenedProofs {
        public List<BigInteger> as;
        public List<BigInteger> bs1;
        public List<BigInteger> bs2;
        public List<BigInteger> cs;

        public FlattenedProofs(List<List<Proof>> proofs){
            this.as = new LinkedList<>();
            this.bs1 = new LinkedList<>();
            this.bs2 = new LinkedList<>();
            this.cs = new LinkedList<>();
    
            for(List<Proof> pproofs : proofs) {
                for (Proof proof : pproofs) {
                    this.as.addAll(Arrays.asList(proof.a));
                    this.bs1.addAll(Arrays.asList(proof.b[0]));
                    this.bs2.addAll(Arrays.asList(proof.b[1]));
                    this.cs.addAll(Arrays.asList(proof.c));
                }
            }
        }

    }

    public void upload_proofs(String bp_id, List<BigInteger> block_ids, List<List<Proof>> proofs){
        // flatten proofs so they can be uploaded to blockchain correctly
        FlattenedProofs flat_pr = new FlattenedProofs(proofs);
        // send transaction and wait for result but not called from main thread
        try {
            TransactionReceipt txr = this.contract_wrapper.verify(bp_id, block_ids, flat_pr.as, flat_pr.bs1, flat_pr.bs2, flat_pr.cs).send();
            // TODO: log transaction status
        } catch (Exception e) {
            // TODO: log the fail incident
            //e.printStackTrace();
        }
    }

    public byte[] get_seed(String bp_id){
        byte[] seed = null;
        try {
            seed = this.contract_wrapper.peek_seed(bp_id).send();
        } catch (Exception e) {
            // TODO: log seed retrieval failure
            //e.printStackTrace();
        }
        return seed;
    }

    public void init_seed(String bp_id){
        try {
            TransactionReceipt txr = this.contract_wrapper.dnode_init(bp_id).send();
        } catch (Exception e) {
            // TODO: log seed initialization failure
            //e.printStackTrace();
        }
    }

}
