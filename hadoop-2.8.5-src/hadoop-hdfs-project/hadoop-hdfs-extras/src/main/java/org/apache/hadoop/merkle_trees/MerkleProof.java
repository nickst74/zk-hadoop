package org.apache.hadoop.merkle_trees;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

/**
 * Holds only the required data for producing the proofs
 */
public class MerkleProof {

    private final long block_id;
    private final byte[] root;
    private final LinkedList<Integer> challenges;
    private final LinkedList<byte[]> chunks;
    private final LinkedList<List<byte[]>> siblings;
    private final LinkedList<List<Boolean>> paths;
    
    public MerkleProof(
    		long block_id,
    		byte[] root,
    		LinkedList<Integer> challenges,
    		LinkedList<byte[]> chunks,
    		LinkedList<List<byte[]>> siblings,
    		LinkedList<List<Boolean>> paths) {
    	assert(
    			challenges.size() == chunks.size() &&
    			challenges.size() == siblings.size() &&
    			challenges.size() == siblings.size() &&
    			challenges.size() == paths.size());
    	this.block_id = block_id;
    	this.root = root;
    	this.challenges = challenges;
    	this.chunks = chunks;
    	this.siblings = siblings;
    	this.paths = paths;
    }
    
    public long getBlock_id() {
		return this.block_id;
	}
    
    public boolean isEmpty() {
    	return this.challenges.isEmpty();
    }
    
    public void skip() {
    	this.challenges.pop();
    	this.chunks.pop();
    	this.siblings.pop();
    	this.paths.pop();
    }
    
    private static void appendToList(List<String> ls, byte[] item) {
    	assert(item.length % 4 == 0);
    	for(int i = 0; i < item.length; i += 4) {
    		ls.add(Long.toString(Util.bytesToLong(item, i)));
    	}
    }
    
    public List<String> nextWitness() {
    	List<String> command = new ArrayList<String>(Arrays.asList("./zokrates", "compute-witness", "-a"));
    	// first goes the index
    	command.add(Integer.toString(this.challenges.pop()));
    	// second is the root hash
    	// reduced to 2 zok fields (128 bits each)
    	command.addAll(Arrays.asList((Util.rootToZokFields(this.root))));
    	// third is the raw data
    	appendToList(command, this.chunks.pop());
    	// fourth is the siblings list
    	for(byte[] sibling : this.siblings.pop()) {
    		appendToList(command, sibling);
    	}
    	// fifth and last is the path
    	for(boolean b : this.paths.pop()) {
    		command.add(b ? "1" : "0");
    	}
    	return command;
    }

}
