// SPDX-License-Identifier: UNLICENCED
pragma solidity >=0.4.22 <0.9.0;
//pragma experimental ABIEncoderV2;

import "./verifier.sol";


contract Data {

    // Need to be instantiated on deployment
    uint constant num_chall = 1;     // challenges/block
    uint constant num_chunks = 65536;    // chunks/block

    struct bp_struct {
        mapping (uint=>bytes32) roots;
        mapping (address=>uint) seeds;
    }
    
    mapping (bytes32=>bp_struct) bp_data;
    Verifier verifier;

    event BlockReport(bytes32 indexed blockpool, address datanode, uint time,  uint blockId, bool corrupt);

    
    constructor () {
        verifier = new Verifier();
    }


    function add_digest(bytes32 _bp_id, uint _block_id, bytes32 _root) external {
        bp_data[_bp_id].roots[_block_id] = _root;
    }

    function create_seed(bytes32 _bp_id, uint randomness) external {
        uint seed = block.timestamp;
        // pack both randomness and timestamp in 256bit uint (timestamp on LSBs)
        assembly{
            let shifted_rand := mul(randomness, 0x100000000000000000000000000000000)
            seed := or(seed, shifted_rand)
        }
        bp_data[_bp_id].seeds[tx.origin] = seed;
    }


    function peek_seed(bytes32 _bp_id) external view returns(bytes memory) {
        return abi.encodePacked(bp_data[_bp_id].seeds[tx.origin], _bp_id, tx.origin);
    }

    // generate a series of _count random numbers using a seed
    function gen_challenges(bytes memory _seed, uint _block_id) internal pure returns (uint[num_chall] memory) {
        //require(num_chall > 0);
        uint[num_chall] memory challenges;
        uint tmp = uint(keccak256(abi.encodePacked(_block_id, _seed)));
        challenges[0] = tmp % num_chunks;
        for(uint i = 1; i < num_chall; i++) {
            tmp = uint(keccak256(abi.encodePacked(tmp, _block_id)));
            challenges[i] = tmp % num_chunks;
        }
        return challenges;
    }

    
    function get_input_vector(bytes32 root) internal pure returns(uint[4] memory) {
        uint[4] memory input;
        // implemented with inline assembly for gas efficiency
        assembly {mstore(add(input, 0x60), 0x1)
            mstore(add(input, 0x40), and(root, 0xffffffffffffffffffffffffffffffff))
            mstore(add(input, 0x20), and(div(root, 0x100000000000000000000000000000000), 0xffffffffffffffffffffffffffffffff))
        }
        return input;
    }

    
    function verify(bytes32 _bp_id, uint _block_id, uint[] memory numbers) external{
        //require(numbers == num_chall * 8);
        // first get a reference to the blockpool storage
        bp_struct storage bp_pointer = bp_data[_bp_id];
        // grab the seed and generate challenges
        uint seed = bp_pointer.seeds[tx.origin];
        uint time;
        assembly{
            time := and(seed, 0xffffffffffffffffffffffffffffffff)
        }
        uint[num_chall] memory challenges = gen_challenges(abi.encodePacked(seed, _bp_id, tx.origin), _block_id);
        // get the input vector for the verification
        uint[4] memory input = get_input_vector(bp_pointer.roots[_block_id]);
        for(uint i = 0; i < num_chall; i++) {
            input[0] = challenges[i];
            Verifier.Proof memory proof;
            proof.a = Pairing.G1Point(numbers[i*8], numbers[i*8+1]);
            proof.b = Pairing.G2Point([numbers[i*8+2], numbers[i*8+3]], [numbers[i*8+4], numbers[i*8+5]]);
            proof.c = Pairing.G1Point(numbers[i*8+6], numbers[i*8+7]);
            if(!verifier.verifyTx(proof, input)) {
                // maybe emit actual time and sort on client (will slow down ui for sure)???
                emit BlockReport(_bp_id, tx.origin, time, _block_id, true);
                return;
            }
        }
        emit BlockReport(_bp_id, tx.origin, time, _block_id, false);
    }

}

