// SPDX-License-Identifier: UNLICENCED
pragma solidity >=0.4.22 <0.9.0;
//pragma experimental ABIEncoderV2;

import "./verifier.sol";


contract Data {

    struct bp_struct {
        mapping (uint=>bytes32) roots;
        mapping (address=>uint) seeds;
    }

    struct input_str {
        uint[10] input;
        uint tmp;
    }
    

    struct proofs_struct {
        bytes32 bp_id;
        uint[] block_ids;
        uint[] a;
        uint[] b1;
        uint[] b2;
        uint[] c;
    }
    
    mapping (bytes32=>bp_struct) bp_data;

    // Need to be instantiated on deployment
    uint num_chall;     // challenges/block
    uint num_chunks;    // chunks/block
    Verifier verifier;

    event BlockReport(bytes32 indexed blockpool, address datanode, uint time,  uint[] blocks, uint[] corrupted);

    
    constructor (uint _num_chall, uint _num_chunks) public {
        num_chall = _num_chall;
        num_chunks = _num_chunks;
        verifier = new Verifier();
    }


    function add_digest(bytes32 _bp_id, uint _block_id, bytes32 _root) external {
        bp_data[_bp_id].roots[_block_id] = _root;
    }


    function peek_seed(bytes32 _bp_id) external view returns(bytes memory) {
        return abi.encodePacked(bp_data[_bp_id].seeds[tx.origin], _bp_id, tx.origin);
    }


    function dnode_init(bytes32 _bp_id) external {
        require(bp_data[_bp_id].seeds[tx.origin] == 0);
        bp_data[_bp_id].seeds[tx.origin] = block.timestamp;
    }
    

    // generate a series of _count random numbers using a seed
    function gen_challenges(bytes memory _seed, uint _block_id, uint _bound, uint _count) internal pure returns (uint[] memory) {
        require(_count > 0);
        uint[] memory challenges = new uint[](_count);
        uint tmp = uint(keccak256(abi.encodePacked(_block_id, _seed)));
        challenges[0] = tmp % _bound;
        for(uint i = 1; i < _count; i++) {
            tmp = uint(keccak256(abi.encodePacked(tmp, _block_id)));
            challenges[i] = tmp % _bound;
        }
        return challenges;
    }

    
    function get_input_vector(bytes32 root) internal pure returns(uint[10] memory) {
        uint[10] memory input;
        // implemented with inline assembly for gas efficiency
        assembly {
            mstore(add(input, 0x120), 0x1)
            mstore(add(input, 0x100), and(root, 0xffffffff))
            mstore(add(input, 0xe0), and(div(root, 0x100000000), 0xffffffff))
            mstore(add(input, 0xc0), and(div(root, 0x10000000000000000), 0xffffffff))
            mstore(add(input, 0xa0), and(div(root, 0x1000000000000000000000000), 0xffffffff))
            mstore(add(input, 0x80), and(div(root, 0x100000000000000000000000000000000), 0xffffffff))
            mstore(add(input, 0x60), and(div(root, 0x10000000000000000000000000000000000000000), 0xffffffff))
            mstore(add(input, 0x40), and(div(root, 0x1000000000000000000000000000000000000000000000000), 0xffffffff))
            mstore(add(input, 0x20), and(div(root, 0x100000000000000000000000000000000000000000000000000000000), 0xffffffff))
        }
        return input;
    }

    function verify_helper(proofs_struct memory _proofs) internal {
        // declare result array
        bool[] memory results = new bool[](_proofs.block_ids.length);
        uint failed = 0;
        // keep a reference to the corresponding blockpool struct for cheaper access
        bp_struct storage bp_pointer = bp_data[_proofs.bp_id];
        bytes memory seed = abi.encodePacked(bp_pointer.seeds[tx.origin], _proofs.bp_id, tx.origin);
        // for every blockID check the proofs
        for(uint i = 0; i < _proofs.block_ids.length; i++) {
            results[i] = true;
            uint[] memory challenges = gen_challenges(seed, _proofs.block_ids[i], num_chunks, num_chall);
            uint[10] memory input = get_input_vector(bp_pointer.roots[_proofs.block_ids[i]]);
            for(uint j = 0; j < num_chall; j++) {
                input[0] = challenges[j];
                if(!verifier.verifyTx(
                                        [_proofs.a[(i*num_chall+j)*2], _proofs.a[(i*num_chall+j)*2+1]],
                                        [[_proofs.b1[(i*num_chall+j)*2], _proofs.b1[(i*num_chall+j)*2+1]], [_proofs.b2[(i*num_chall+j)*2], _proofs.b2[(i*num_chall+j)*2+1]]],
                                        [_proofs.c[(i*num_chall+j)*2], _proofs.c[(i*num_chall+j)*2+1]],
                                        input
                                     )) {
                    results[i] = true;
                    failed++;
                    break;
                }
            }
        }
        // collect all found corrupted blocks
        uint[] memory corrupted = new uint[](failed);
        for(uint i = 0; i < results.length; i++) {
            if(results[i]) {
                corrupted[--failed] = _proofs.block_ids[i];
            }
        }
        emit BlockReport(_proofs.bp_id, tx.origin, block.timestamp, _proofs.block_ids, corrupted);
        bp_pointer.seeds[tx.origin] = block.timestamp;
    }
    
    function verify(bytes32 _bp_id, uint[] calldata _block_ids, uint[] calldata _as, uint[] calldata _bs1, uint[] calldata _bs2, uint[] calldata _cs) external{
        require(bp_data[_bp_id].seeds[tx.origin] != 0);
        verify_helper(proofs_struct(_bp_id, _block_ids, _as, _bs1, _bs2, _cs));
    }

}

