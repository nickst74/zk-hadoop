// EXPOSED VARIABLES
const ip_to_bc = {};
const bc_to_ip = {};

var datanodes = [];
const datanode_to_blocklist = {};
const file_to_blocklist = {};
const block_to_file = {};

//////////////////////


/**
  * Blockchain timestamp to datetime format
  * @param {Number} timestamp Timestamp as an integer
  * @returns String formatted timestamp
  */
 function format_time(timestamp) {
  let datetime = new Date(Math.floor(timestamp) * 1000);
  return datetime.toLocaleString();
}


/**
 * Fetches BlockReport events from blockchain and data from hdfs.
 * Then parses all events according to a function parameter.
 * @param {Function} myfunc Event parsing function
 */
async function blockchain(myfunc){

  async function parse_fsck_output() {
    console.log('Requesting block list from local hadoop cluster...');
    await $.get(
      '/fsck?ugi=hadoop&locations=1&blocks=1&files=1&path=%2F',
        data => {
          /*
           * step 1: parse data in order to construct:
           * datanode_to_blocklist dict
           * datanodes list
           */
          data.split('\n').filter(line => /^\d+\. /.test(line)).forEach(function(line) {
            let currDatanodesInfo = line.split('DatanodeInfoWithStorage[');
            let currDatanodes = [];
            for (var i = 1; i < currDatanodesInfo.length; i++) {
              currDatanodes.push(currDatanodesInfo[i].split(',')[0]);
            }
            let block_id = line.split('_')[1];
            for (const datanode of currDatanodes) {
              if (datanode_to_blocklist[datanode] === undefined)
                datanode_to_blocklist[datanode] = [block_id];
              else
                datanode_to_blocklist[datanode].push(block_id);
            }
          });
          
          for (const datanode in datanode_to_blocklist)
            datanode_to_blocklist[datanode].sort();
          
            datanodes = Object.keys(datanode_to_blocklist);

            console.log('... done. Local hadoop cluster consists of',
                        datanodes.length,
                        'datanode' + (datanodes.length > 1 ? 's' : '') + '.');

            /*
             * step 2: parse data again in order to construct:
             * file_to_blocklist dict
             * block_to_file dict
             */
            let lines = data.split('\n');
            let i = 0;
            let fileMode = false;
            let currFile;
            let currLine;
            while (i < lines.length) {
              currLine = lines[i];
              if (fileMode) {
                if (/^\d+\. /.test(currLine)) {
                  let block_id = currLine.split('_')[1];
                  file_to_blocklist[currFile].push(block_id);
                  block_to_file[block_id] = currFile;
                } else if (!currLine) {
                  fileMode = false;
                }
              } else {
                if (currLine.startsWith('/') && !currLine.endsWith('<dir>')) {
                  fileMode = true;
                  currFile = currLine.split(' ')[0];
                  file_to_blocklist[currFile] = [];
                }
              }
              i++;
            }

            console.log('Created mappings between files and HDFS blocks');
    })
    .fail(error => {
      alert("HTTP Filesystem check request failed");
      console.log("fsck query failed: "+error);
    });
  }


  /**
   * Initializes the contract object
   * @returns The contract
   */
  function get_contract(geth_addr, contract_addr) {
    const web3 = new Web3(new Web3.providers.HttpProvider(geth_addr));
    const CONTRACT_ABI = [{"inputs":[{"internalType":"uint256","name":"_num_chall","type":"uint256"},{"internalType":"uint256","name":"_num_chunks","type":"uint256"}],"stateMutability":"nonpayable","type":"constructor"},{"anonymous":false,"inputs":[{"indexed":true,"internalType":"bytes32","name":"blockpool","type":"bytes32"},{"indexed":false,"internalType":"address","name":"datanode","type":"address"},{"indexed":false,"internalType":"uint256","name":"time","type":"uint256"},{"indexed":false,"internalType":"uint256[]","name":"blocks","type":"uint256[]"},{"indexed":false,"internalType":"uint256[]","name":"corrupted","type":"uint256[]"}],"name":"BlockReport","type":"event"},{"inputs":[{"internalType":"bytes32","name":"_bp_id","type":"bytes32"},{"internalType":"uint256","name":"_block_id","type":"uint256"},{"internalType":"bytes32","name":"_root","type":"bytes32"}],"name":"add_digest","outputs":[],"stateMutability":"nonpayable","type":"function"},{"inputs":[{"internalType":"bytes32","name":"_bp_id","type":"bytes32"}],"name":"peek_seed","outputs":[{"internalType":"bytes","name":"","type":"bytes"}],"stateMutability":"view","type":"function","constant":true},{"inputs":[{"internalType":"bytes32","name":"_bp_id","type":"bytes32"}],"name":"dnode_init","outputs":[],"stateMutability":"nonpayable","type":"function"},{"inputs":[{"internalType":"bytes32","name":"_bp_id","type":"bytes32"},{"internalType":"uint256[]","name":"_block_ids","type":"uint256[]"},{"internalType":"uint256[]","name":"_as","type":"uint256[]"},{"internalType":"uint256[]","name":"_bs1","type":"uint256[]"},{"internalType":"uint256[]","name":"_bs2","type":"uint256[]"},{"internalType":"uint256[]","name":"_cs","type":"uint256[]"}],"name":"verify","outputs":[],"stateMutability":"nonpayable","type":"function"}];
    return new web3.eth.Contract(CONTRACT_ABI, contract_addr);
  }
  

  var blockpool, geth_addr, contract_addr;
  // start filesystem check call asyncronously
  const foo = parse_fsck_output();

  // get hdfs and blockchain info from namenode
  await $.get(
    '/jmx?qry=Hadoop:service=NameNode,name=NameNodeInfo',
    resp => {
      info = resp.beans[0];
      livenodes = JSON.parse(info.LiveNodes);
      for(const node in livenodes) {
        const ip = livenodes[node].xferaddr;
        const bc = livenodes[node].blockchainAddress;
        ip_to_bc[ip] = bc;
        bc_to_ip[bc] = ip;
      }
      blockpool = info.BlockPoolId;
      geth_addr = info.GethAddress;
      contract_addr = info.ContractAddress;
    }
  )
  .fail(error => {
    alert("Failed to fetch blockchain related info from Namenode");
    console.error("JMX Query failed: "+error);
  });
  await foo;
  // initialize contract
  // Get all BlockReport events and process them according to parameter function
  const events = await get_contract(geth_addr, contract_addr).getPastEvents('BlockReport', {
    filter: {'blockpool': Web3.utils.sha3(blockpool)},
    fromBlock: 'earliest',
    toBlock: 'latest'
  }, (error, _) => {
    if(error) {
      alert("[web3] Error while fetching BlockReports from blockchain");
      console.error(error);
    }
  });
  return myfunc(events);

};

//blockchain(x => console.log(x));