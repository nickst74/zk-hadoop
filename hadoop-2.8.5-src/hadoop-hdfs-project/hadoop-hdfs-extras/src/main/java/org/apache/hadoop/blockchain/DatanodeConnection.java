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
        public List<List<BigInteger>> as;
        public List<List<BigInteger>> bs1;
        public List<List<BigInteger>> bs2;
        public List<List<BigInteger>> cs;

        public FlattenedProofs(List<List<Proof>> proofs){
            this.as = new LinkedList<>();
            this.bs1 = new LinkedList<>();
            this.bs2 = new LinkedList<>();
            this.cs = new LinkedList<>();
    
            for(List<Proof> pproofs : proofs) {
                for (Proof proof : pproofs) {
                    this.as.add(Arrays.asList(proof.a));
                    this.bs1.add(Arrays.asList(proof.b[0]));
                    this.bs2.add(Arrays.asList(proof.b[1]));
                    this.cs.add(Arrays.asList(proof.c));
                }
            }
        }

    }

    public void upload_proofs(List<BigInteger> block_ids, List<List<Proof>> proofs){
        // flatten proofs so they can be uploaded to blockchain correctly
        FlattenedProofs flat_pr = new FlattenedProofs(proofs);
        // send transaction and wait for result but not called from main thread
        try {
            TransactionReceipt txr = this.contract_wrapper.verify(block_ids, flat_pr.as, flat_pr.bs1, flat_pr.bs2, flat_pr.cs).send();
            // TODO: log transaction status
        } catch (Exception e) {
            // TODO: log the fail incident
            //e.printStackTrace();
        }
    }

    public BigInteger get_seed(){
        BigInteger seed = null;
        try {
            seed = this.contract_wrapper.peek_seed().send();
        } catch (Exception e) {
            // TODO: log seed retrieval failure
            //e.printStackTrace();
        }
        return seed;
    }

    public void init_seed(){
        try {
            TransactionReceipt txr = this.contract_wrapper.dnode_init().send();
        } catch (Exception e) {
            // TODO: log seed initialization failure
            //e.printStackTrace();
        }
    }

}
