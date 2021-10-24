package org.apache.hadoop.blockchain;

import java.math.BigInteger;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.web3j.protocol.core.methods.response.TransactionReceipt;

public class ClientConnection extends Connection {

    private class TX{
        public final long block_id;
        public final CompletableFuture<TransactionReceipt> cf_tr;

        TX(long block_id, CompletableFuture<TransactionReceipt> cf_tr){
            this.block_id = block_id;
            this.cf_tr = cf_tr;
        }
    }

    // keep a list of awaiting transaction receipts
    // so we can check them at the end
    List<TX> txs;

    public ClientConnection(String blockhain, String wallet_pk, String contract_address) {
        super(blockhain, wallet_pk, contract_address);
        this.txs = new LinkedList<TX>();
    }

    /**
     * Uploads merkle root hash for the given block id to the blockchain.
     * Transaction is signed and sent asynchronously, without waiting for result.
     * @param block_id Unique identifier of the block
     * @param root The merkle root hash of the block
     */
    public void uploadHash(long block_id, byte[] root){
        CompletableFuture<TransactionReceipt> cf_tr = this.contract_wrapper.add_digest(BigInteger.valueOf(block_id), root).sendAsync();
        this.txs.add(new TX(block_id, cf_tr));
    }

    public void checkResults(){
        System.out.println("Checking results for uploaded hashes (total: "+this.txs.size()+")");
        for (TX tx : this.txs) {
            try {
                // TODO: maybe add timeout
                TransactionReceipt tr = tx.cf_tr.get();
                System.out.println(tx.block_id+": merkle root upload status is -> "+tr.getStatus());
            } catch (Exception e) {
                System.out.println(tx.block_id+": Exception thrown during transaction receipt retrieval");
                //e.printStackTrace();
            }
        }
    }
    
}
