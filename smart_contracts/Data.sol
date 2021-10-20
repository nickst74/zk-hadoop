// SPDX-License-Identifier: UNLICENCED
pragma solidity >=0.4.22 <0.9.0;
pragma experimental ABIEncoderV2;

import "./Verifier.sol";


library RNG {
    
    function sliceUint(bytes memory bs, uint start) internal pure returns (uint) {
        require(bs.length >= start + 32, "slicing out of range");
        uint x;
        assembly {
            x := mload(add(bs, add(0x20, start)))
        }
        return x;
    }

    // produce a seed for the caller datanode
    function gen_seed() internal view returns(uint) {
        return sliceUint(abi.encodePacked(block.timestamp, tx.origin), 0);
    }

    // generate a random number using a seed
    function gen(uint _seed, uint _block_id) internal pure returns (uint) {
        return uint(keccak256(abi.encodePacked(_seed, _block_id)));
    }

}

contract Data {

    mapping (uint=>bytes32) roots;
    mapping (address=>uint) seeds;

    // Need to be instantiated on deployment
    uint num_chall;     // challenges/block (under consideration???)
    uint num_chunks;    // chunks/block
    Verifier verifier;

    // if namenode deploys the contract, change the way we get the address
    constructor (uint _num_chall, uint _num_chunks) public {
        num_chall = _num_chall;
        num_chunks = _num_chunks;
        verifier = new Verifier();
    }

    event BlockReport(address indexed datanode, uint[] blocks, bool[] results);
    
    function peek_seed() public view returns(uint){
        return seeds[tx.origin];
    }
    
    struct input_str {
        uint[10] input;
        uint tmp;
    }
    
    struct proofs_struct {
        uint[] block_ids;
        uint[2][] a;
        uint[2][] b1;
        uint[2][] b2;
        uint[2][] c;
    }
    
    function get_input_vector(uint _block_id) internal view returns(input_str memory){
        input_str memory ret;
        //TODO: better way to get seed to uint
        ret.tmp = peek_seed();
        bytes32 root = roots[_block_id];
        //TODO: optimize using inline assembly (mload)
        ret.input[1] = uint(root) & 0xffffffff;
        ret.input[2] = uint(root >> 32) & 0xffffffff;
        ret.input[3] = uint(root >> 32*2) & 0xffffffff;
        ret.input[4] = uint(root >> 32*3) & 0xffffffff;
        ret.input[5] = uint(root >> 32*4) & 0xffffffff;
        ret.input[6] = uint(root >> 32*5) & 0xffffffff;
        ret.input[7] = uint(root >> 32*6) & 0xffffffff;
        ret.input[8] = uint(root >> 32*7) & 0xffffffff;
        ret.input[9] = 1;
        return ret;
    }

    function verify_helper(proofs_struct memory _proofs) internal{
        //TODO: check for exceptions (e.g. ArrayOutOfBounds)
        bool[] memory results = new bool[](_proofs.block_ids.length);
        input_str memory input;
        for(uint i = 0; i < _proofs.block_ids.length; i++){
            results[i] = true;
            input = get_input_vector(_proofs.block_ids[i]);
            for(uint j = 0; j < num_chall; j++){
                input.tmp = RNG.gen(input.tmp, _proofs.block_ids[i]);
                input.input[0] = input.tmp % num_chunks;
                if(!verifier.verifyTx(_proofs.a[i], [_proofs.b1[i], _proofs.b2[i]], _proofs.c[i], input.input)){
                    results[i] = false;
                    break;
                }
            }
        }
        emit BlockReport(tx.origin, _proofs.block_ids, results);
    }
    
    function verify(uint[] calldata _block_ids, uint[2][] calldata _as, uint[2][] calldata _bs1, uint[2][] calldata _bs2, uint[2][] calldata _cs) external{
        require(peek_seed() != 0 &&
                    _block_ids.length > 0 &&
                    _block_ids.length == _as.length / num_chall &&
                    _as.length == _bs1.length &&
                    _bs1.length == _bs2.length &&
                    _bs2.length == _cs.length
                );
        verify_helper(proofs_struct(_block_ids, _as, _bs1, _bs2, _cs));
        seeds[tx.origin] = RNG.gen_seed();
    }
    
    function dnode_init() external{
        require(peek_seed() == 0);
        seeds[tx.origin] = RNG.gen_seed();
    }

    function add_digest(uint _block_id, bytes32 _root) external{
        roots[_block_id] = _root;
    }

}

