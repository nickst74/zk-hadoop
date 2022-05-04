package org.apache.hadoop.blockchain;

import io.reactivex.Flowable;
import io.reactivex.functions.Function;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import org.web3j.abi.EventEncoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.DynamicBytes;
import org.web3j.abi.datatypes.Event;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.RemoteCall;
import org.web3j.protocol.core.RemoteFunctionCall;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.BaseEventResponse;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.tx.Contract;
import org.web3j.tx.TransactionManager;
import org.web3j.tx.gas.ContractGasProvider;

/**
 * <p>Auto generated code.
 * <p><strong>Do not modify!</strong>
 * <p>Please use the <a href="https://docs.web3j.io/command_line.html">web3j command line tools</a>,
 * or the org.web3j.codegen.SolidityFunctionWrapperGenerator in the 
 * <a href="https://github.com/web3j/web3j/tree/master/codegen">codegen module</a> to update.
 *
 * <p>Generated with web3j version 1.4.1.
 */
@SuppressWarnings("rawtypes")
public class Data extends Contract {
    public static final String BINARY = "0x608060405234801561001057600080fd5b5060405161001d9061005f565b604051809103906000f080158015610039573d6000803e3d6000fd5b5060018054600160a060020a031916600160a060020a039290921691909117905561006c565b6111fe80610b6c83390190565b610af18061007b6000396000f3fe608060405234801561001057600080fd5b5060043610610068577c010000000000000000000000000000000000000000000000000000000060003504632199c19a811461006d5780635c5f818a14610082578063c099ecd5146100c7578063e113d7c914610142575b600080fd5b61008061007b3660046107c6565b61016a565b005b6100806100903660046107a4565b6000918252602082815260408084203285526001019091529091207001000000000000000000000000000000009190910242179055565b61012c6100d536600461078b565b60008181526020818152604080832032808552600190910183529281902054815192830152818101939093526c01000000000000000000000000909102606082015281516054818303018152607490910190915290565b604051610139919061091a565b60405180910390f35b61008061015036600461089f565b600092835260208381526040808520938552929052912055565b600083815260208181526040808320328085526001820184528285205483519485018190529284018890526c010000000000000000000000000260608401529290916fffffffffffffffffffffffffffffffff831691906101dd906074016040516020818303038152906040528761058a565b600087815260208690526040812054919250906101f990610650565b8251606082015260016080820152905060005b60088110156105355782816008811061022757610227610a89565b6020020151825261023661069a565b60405180604001604052808984600861024f91906109e5565b8151811061025f5761025f610a89565b602002602001015181526020018984600861027a91906109e5565b6102859060016109cd565b8151811061029557610295610a89565b602090810291909101015190528152604080516080810182529081908101808b6102c08760086109e5565b6102cb9060026109cd565b815181106102db576102db610a89565b602002602001015181526020018b8660086102f691906109e5565b6103019060036109cd565b8151811061031157610311610a89565b6020026020010151815250815260200160405180604001604052808b86600861033a91906109e5565b6103459060046109cd565b8151811061035557610355610a89565b602002602001015181526020018b86600861037091906109e5565b61037b9060056109cd565b8151811061038b5761038b610a89565b602002602001015181525081525081602001819052506040518060400160405280898460086103ba91906109e5565b6103c59060066109cd565b815181106103d5576103d5610a89565b60200260200101518152602001898460086103f091906109e5565b6103fb9060076109cd565b8151811061040b5761040b610a89565b6020908102919091010151905260408083019190915260015490517fbcb9988600000000000000000000000000000000000000000000000000000000815273ffffffffffffffffffffffffffffffffffffffff9091169063bcb9988690610478908490879060040161094d565b60206040518083038186803b15801561049057600080fd5b505afa1580156104a4573d6000803e3d6000fd5b505050506040513d601f19601f820116820180604052508101906104c89190610762565b6105225760408051328152602081018790529081018a9052600160608201528a907f04534e26d4f9b557ad03dbbb13741f60740aadb9c7d7e894095eee2b643bd9529060800160405180910390a250505050505050505050565b508061052d81610a30565b91505061020c565b5060408051328152602081018590529081018890526000606082015288907f04534e26d4f9b557ad03dbbb13741f60740aadb9c7d7e894095eee2b643bd9529060800160405180910390a25050505050505050565b6105926106eb565b61059a6106eb565b600083856040516020016105af9291906108f4565b60408051601f19818403018152919052805160209091012090506105d561200082610a4b565b825260015b600881101561064657604080516020810184905290810186905260600160408051601f198184030181529190528051602090910120915061061d61200083610a4b565b83826008811061062f5761062f610a89565b60200201528061063e81610a30565b9150506105da565b5090949350505050565b61065861070a565b61066061070a565b6fffffffffffffffffffffffffffffffff838116604083015270010000000000000000000000000000000090930490921660208301525090565b6040805160a0810190915260006060820181815260808301919091528152602081016106c4610728565b81526020016106e6604051806040016040528060008152602001600081525090565b905290565b6040518061010001604052806008906020820280368337509192915050565b6040518060a001604052806005906020820280368337509192915050565b604051806040016040528061073b610744565b81526020016106e65b60405180604001604052806002906020820280368337509192915050565b60006020828403121561077457600080fd5b8151801515811461078457600080fd5b9392505050565b60006020828403121561079d57600080fd5b5035919050565b600080604083850312156107b757600080fd5b50508035926020909101359150565b6000806000606084860312156107db57600080fd5b833592506020808501359250604085013567ffffffffffffffff8082111561080257600080fd5b818701915087601f83011261081657600080fd5b81358181111561082857610828610aa2565b838102604051601f19603f8301168101818110858211171561084c5761084c610aa2565b604052828152858101935084860182860187018c101561086b57600080fd5b600095505b8386101561088e578035855260019590950194938601938601610870565b508096505050505050509250925092565b6000806000606084860312156108b457600080fd5b505081359360208301359350604090920135919050565b8060005b60028110156108ee5781518452602093840193909101906001016108cf565b50505050565b8281526000825161090c816020850160208701610a04565b919091016020019392505050565b6020815260008251806020840152610939816040850160208701610a04565b601f01601f19169190910160400192915050565b825180518252602090810151908201526101a081016020808501516109766040850182516108cb565b81015161098660808501826108cb565b506040850151805160c08501526020015160e084015261010083018460005b60058110156109c2578151835291830191908301906001016109a5565b505050509392505050565b600082198211156109e0576109e0610a70565b500190565b60008160001904831182151516156109ff576109ff610a70565b500290565b60005b83811015610a1f578181015183820152602001610a07565b838111156108ee5750506000910152565b6000600019821415610a4457610a44610a70565b5060010190565b600082610a6b5760e060020a634e487b7102600052601260045260246000fd5b500690565b60e060020a634e487b7102600052601160045260246000fd5b60e060020a634e487b7102600052603260045260246000fd5b60e060020a634e487b7102600052604160045260246000fdfea264697066735822122003cec5f7e990669c3afa01633b275576edf085f7739c4606d645d1455a22c81464736f6c63430008060033608060405234801561001057600080fd5b506111de806100206000396000f3fe608060405234801561001057600080fd5b5060043610610047577c01000000000000000000000000000000000000000000000000000000006000350463bcb99886811461004c575b600080fd5b61005f61005a366004610f67565b610073565b604051901515815260200160405180910390f35b60408051600580825260c082019092526000918291906020820160a08036833701905050905060005b60058110156100eb578381600581106100b7576100b7611176565b60200201518282815181106100ce576100ce611176565b6020908102919091010152806100e38161111d565b91505061009c565b506100f68185610110565b61010457600191505061010a565b60009150505b92915050565b60007f30644e72e131a029b85045b68181585d2833e84879b9709143e1f593f00000018161013c610293565b90508060800151518551600161015291906110cf565b1461015c57600080fd5b604080518082019091526000808252602082018190525b865181101561020a578387828151811061018f5761018f611176565b6020026020010151106101a157600080fd5b6101f6826101f185608001518460016101ba91906110cf565b815181106101ca576101ca611176565b60200260200101518a85815181106101e4576101e4611176565b602002602001015161080a565b61086e565b9150806102028161111d565b915050610173565b5061023381836080015160008151811061022657610226611176565b602002602001015161086e565b90506102778560000151866020015161024b846108c8565b856040015161025d8a604001516108c8565b6060880151885161026d906108c8565b8960200151610967565b610287576001935050505061010a565b50600095945050505050565b61029b610dd2565b6040805180820182527f07ecf1d7e585d16b7b77a8ed7ad3cfcca060bfc02916e2d6c7f154137d25a37281527f1bfb15cae4397db940f80a03046f8cf62a9496ca5f38d0a0514eb00e26122d846020808301919091529083528151608080820184527f0e96e6e92703abea55a982117d981e808b643bcfad81b26a004dcc99bcf11f438285019081527f17556613d438c6bae415c60bb760a78f8ee796daeaf370cf31e789792425335c606080850191909152908352845180860186527f2a75831954d4fe3a66296e921407df80b5fd8e9fd5349b50cf37cd1ab7c5df1981527f288f1e185ceadd5387377af8fa123ab72649b3f6c8309061a3f2acdf91e22165818601528385015285840192909252835180820185527f0fe1634511d60e16f81ea4207aff56b7c4357ccc41269384c3ece5bf800af8588186019081527f1266409fa49882fbab30c27bc852e4355deaa0b2ce16a0039bc108fc49aed353828501528152845180860186527f159e242b9b9c2839713d51a7d00828b4dd8f59ac3feaa1a504aac8cb8cf1d7c981527f1aab34e8291167f6bea88fdf01de456a5ff35dbd3051de22b1be0aa5683d6e90818601528185015285850152835190810184527f2e4c6c5117bcbf35c849d7b33938361573fe791386e6642b93a7d0c7446fb4af8185019081527f196c13244f492b9b4efd47af913db3d9a0f9c1b541af5a4db5a61167397bd18c828401528152835180850185527f1866807998e4c820db25337828a4da0c447568496874e1773577ef7086c69a4381527f17340047b4389365308b3cdcd4243e23d284c3531d36ad0fe004bd527b6fd4688185015281840152908401528151600680825260e08201909352919082015b604080518082019091526000808252602082015281526020019060019003908161051657505060808201908152604080518082019091527f1a52e1ee583c18a4a814f7a5715b89d93f1007b7eaff1f06a7a0f1e9bd05274881527f188417faea0dcc8fa86b5dd6a0a30f64a5b22a8910477b29905af2b16dc555356020820152905180516000906105a9576105a9611176565b602002602001018190525060405180604001604052807f0a1086b1b51a827a040659d3e9c206bd6f759913caa4e7abf971f2ff7ca9516881526020017f0d4ed41c88e60345fb50dac8a0d9c027d49d31d116752a03be4dce85927dcca8815250816080015160018151811061062057610620611176565b602002602001018190525060405180604001604052807f0ff636546d4fe8bd7c66d0ccfe4694cde504ad9f7674cb8001c3e5b5c79c7e6d81526020017f293dd1e235b4ac94c074d418c8d0091ec77a4c929fe8f7ac915cb923dd99c2c3815250816080015160028151811061069757610697611176565b602002602001018190525060405180604001604052807f2970672fe54bbb90700c89baf6293c02986f7ec267dba48c5da400e46aff232181526020017f0efb2c0d799339bde28d42222116c4fc24ed262ccf93f676261a1d331b36f3a9815250816080015160038151811061070e5761070e611176565b602002602001018190525060405180604001604052807f2cbd9a073c869e60dfd082b5215953c61e526dd2585b768007f5013ac17f333681526020017f223cd5bd4b5212a5428c641b46d519876abc88e73e742b67bd5aff4265c6c7b1815250816080015160048151811061078557610785611176565b602002602001018190525060405180604001604052807f148b0eecccf33aad31392600fd47aeefe383373b05b18e7237c495af5de52fe681526020017f2c20d3bf4b35bd80b8a7f4f3f4b2d5be3085cd4096a877885b739419cc4caf1c81525081608001516005815181106107fc576107fc611176565b602002602001018190525090565b6040805180820190915260008082526020820152610826610e23565b835181526020808501519082015260408101839052600060608360808460076107d05a03fa90508080156108595761085b565bfe5b508061086657600080fd5b505092915050565b604080518082019091526000808252602082015261088a610e41565b8351815260208085015181830152835160408301528301516060808301919091526000908360c08460066107d05a03fa90508080156108595761085b565b604080518082019091526000808252602082015281517f30644e72e131a029b85045b68181585d97816a916871ca8d3c208c16d87cfd479015801561090f57506020830151155b1561092f5750506040805180820190915260008082526020820152919050565b6040518060400160405280846000015181526020018285602001516109549190611138565b61095e9084611106565b90529392505050565b60408051600480825260a08201909252600091829190816020015b604080518082019091526000808252602082015281526020019060019003908161098257505060408051600480825260a0820190925291925060009190602082015b6109cc610e5f565b8152602001906001900390816109c45790505090508a826000815181106109f5576109f5611176565b60200260200101819052508882600181518110610a1457610a14611176565b60200260200101819052508682600281518110610a3357610a33611176565b60200260200101819052508482600381518110610a5257610a52611176565b60200260200101819052508981600081518110610a7157610a71611176565b60200260200101819052508781600181518110610a9057610a90611176565b60200260200101819052508581600281518110610aaf57610aaf611176565b60200260200101819052508381600381518110610ace57610ace611176565b6020026020010181905250610ae38282610af2565b9b9a5050505050505050505050565b60008151835114610b0257600080fd5b82516000610b118260066110e7565b905060008167ffffffffffffffff811115610b2e57610b2e61118f565b604051908082528060200260200182016040528015610b57578160200160208202803683370190505b50905060005b83811015610d9257868181518110610b7757610b77611176565b60200260200101516000015182826006610b9191906110e7565b610b9c9060006110cf565b81518110610bac57610bac611176565b602002602001018181525050868181518110610bca57610bca611176565b60200260200101516020015182826006610be491906110e7565b610bef9060016110cf565b81518110610bff57610bff611176565b602002602001018181525050858181518110610c1d57610c1d611176565b60209081029190910181015151015182610c388360066110e7565b610c439060026110cf565b81518110610c5357610c53611176565b602002602001018181525050858181518110610c7157610c71611176565b6020908102919091010151515182610c8a8360066110e7565b610c959060036110cf565b81518110610ca557610ca5611176565b602002602001018181525050858181518110610cc357610cc3611176565b602002602001015160200151600160028110610ce157610ce1611176565b602002015182610cf28360066110e7565b610cfd9060046110cf565b81518110610d0d57610d0d611176565b602002602001018181525050858181518110610d2b57610d2b611176565b602002602001015160200151600060028110610d4957610d49611176565b602002015182610d5a8360066110e7565b610d659060056110cf565b81518110610d7557610d75611176565b602090810291909101015280610d8a8161111d565b915050610b5d565b50610d9b610e84565b6000602082602086026020860160086107d05a03fa9050808015610859575080610dc457600080fd5b505115159695505050505050565b6040805160e08101909152600060a0820181815260c0830191909152815260208101610dfc610e5f565b8152602001610e09610e5f565b8152602001610e16610e5f565b8152602001606081525090565b60405180606001604052806003906020820280368337509192915050565b60405180608001604052806004906020820280368337509192915050565b6040518060400160405280610e72610ea2565b8152602001610e7f610ea2565b905290565b60405180602001604052806001906020820280368337509192915050565b60405180604001604052806002906020820280368337509192915050565b600082601f830112610ed157600080fd5b610ed9611089565b808385604086011115610eeb57600080fd5b60005b6002811015610f0d578135845260209384019390910190600101610eee565b509095945050505050565b600060408284031215610f2a57600080fd5b6040516040810181811067ffffffffffffffff82111715610f4d57610f4d61118f565b604052823581526020928301359281019290925250919050565b6000808284036101a080821215610f7d57600080fd5b61010080831215610f8d57600080fd5b610f95611060565b610f9f8888610f18565b81526080603f1985011215610fb357600080fd5b610fbb611089565b9350610fca8860408901610ec0565b8452610fd98860808901610ec0565b602081818701528581840152610ff28a60c08b01610f18565b60408401528297508961011f8a011261100a57600080fd5b6110126110ac565b9550859250838901935089858a01111561102b57600080fd5b600094505b60058510156110515783358652948501946001949094019392830192611030565b50959890975095505050505050565b6040516060810167ffffffffffffffff811182821017156110835761108361118f565b60405290565b6040805190810167ffffffffffffffff811182821017156110835761108361118f565b60405160a0810167ffffffffffffffff811182821017156110835761108361118f565b600082198211156110e2576110e261115d565b500190565b60008160001904831182151516156111015761110161115d565b500290565b6000828210156111185761111861115d565b500390565b60006000198214156111315761113161115d565b5060010190565b6000826111585760e060020a634e487b7102600052601260045260246000fd5b500690565b60e060020a634e487b7102600052601160045260246000fd5b60e060020a634e487b7102600052603260045260246000fd5b60e060020a634e487b7102600052604160045260246000fdfea26469706673582212207c7fd1957186fac2545fc5df2d73317fa8f9e35640c7aff65dafaacbfc8135c364736f6c63430008060033";

    public static final String FUNC_ADD_DIGEST = "add_digest";

    public static final String FUNC_CREATE_SEED = "create_seed";

    public static final String FUNC_PEEK_SEED = "peek_seed";

    public static final String FUNC_VERIFY = "verify";

    public static final Event BLOCKREPORT_EVENT = new Event("BlockReport", 
            Arrays.<TypeReference<?>>asList(new TypeReference<Bytes32>(true) {}, new TypeReference<Address>() {}, new TypeReference<Uint256>() {}, new TypeReference<Uint256>() {}, new TypeReference<Bool>() {}));
    ;

    protected static final HashMap<String, String> _addresses;

    static {
        _addresses = new HashMap<String, String>();
        _addresses.put("4217", "0x9fa427dE298287D627bBe47b5e2eFFac4eA4F45c");
    }

    @Deprecated
    protected Data(String contractAddress, Web3j web3j, Credentials credentials, BigInteger gasPrice, BigInteger gasLimit) {
        super(BINARY, contractAddress, web3j, credentials, gasPrice, gasLimit);
    }

    protected Data(String contractAddress, Web3j web3j, Credentials credentials, ContractGasProvider contractGasProvider) {
        super(BINARY, contractAddress, web3j, credentials, contractGasProvider);
    }

    @Deprecated
    protected Data(String contractAddress, Web3j web3j, TransactionManager transactionManager, BigInteger gasPrice, BigInteger gasLimit) {
        super(BINARY, contractAddress, web3j, transactionManager, gasPrice, gasLimit);
    }

    protected Data(String contractAddress, Web3j web3j, TransactionManager transactionManager, ContractGasProvider contractGasProvider) {
        super(BINARY, contractAddress, web3j, transactionManager, contractGasProvider);
    }

    public List<BlockReportEventResponse> getBlockReportEvents(TransactionReceipt transactionReceipt) {
        List<Contract.EventValuesWithLog> valueList = extractEventParametersWithLog(BLOCKREPORT_EVENT, transactionReceipt);
        ArrayList<BlockReportEventResponse> responses = new ArrayList<BlockReportEventResponse>(valueList.size());
        for (Contract.EventValuesWithLog eventValues : valueList) {
            BlockReportEventResponse typedResponse = new BlockReportEventResponse();
            typedResponse.log = eventValues.getLog();
            typedResponse.blockpool = (byte[]) eventValues.getIndexedValues().get(0).getValue();
            typedResponse.datanode = (String) eventValues.getNonIndexedValues().get(0).getValue();
            typedResponse.time = (BigInteger) eventValues.getNonIndexedValues().get(1).getValue();
            typedResponse.blockId = (BigInteger) eventValues.getNonIndexedValues().get(2).getValue();
            typedResponse.corrupt = (Boolean) eventValues.getNonIndexedValues().get(3).getValue();
            responses.add(typedResponse);
        }
        return responses;
    }

    public Flowable<BlockReportEventResponse> blockReportEventFlowable(EthFilter filter) {
        return web3j.ethLogFlowable(filter).map(new Function<Log, BlockReportEventResponse>() {
            @Override
            public BlockReportEventResponse apply(Log log) {
                Contract.EventValuesWithLog eventValues = extractEventParametersWithLog(BLOCKREPORT_EVENT, log);
                BlockReportEventResponse typedResponse = new BlockReportEventResponse();
                typedResponse.log = log;
                typedResponse.blockpool = (byte[]) eventValues.getIndexedValues().get(0).getValue();
                typedResponse.datanode = (String) eventValues.getNonIndexedValues().get(0).getValue();
                typedResponse.time = (BigInteger) eventValues.getNonIndexedValues().get(1).getValue();
                typedResponse.blockId = (BigInteger) eventValues.getNonIndexedValues().get(2).getValue();
                typedResponse.corrupt = (Boolean) eventValues.getNonIndexedValues().get(3).getValue();
                return typedResponse;
            }
        });
    }

    public Flowable<BlockReportEventResponse> blockReportEventFlowable(DefaultBlockParameter startBlock, DefaultBlockParameter endBlock) {
        EthFilter filter = new EthFilter(startBlock, endBlock, getContractAddress());
        filter.addSingleTopic(EventEncoder.encode(BLOCKREPORT_EVENT));
        return blockReportEventFlowable(filter);
    }

    public RemoteFunctionCall<TransactionReceipt> add_digest(byte[] _bp_id, BigInteger _block_id, byte[] _root) {
        final org.web3j.abi.datatypes.Function function = new org.web3j.abi.datatypes.Function(
                FUNC_ADD_DIGEST, 
                Arrays.<Type>asList(new org.web3j.abi.datatypes.generated.Bytes32(_bp_id), 
                new org.web3j.abi.datatypes.generated.Uint256(_block_id), 
                new org.web3j.abi.datatypes.generated.Bytes32(_root)), 
                Collections.<TypeReference<?>>emptyList());
        return executeRemoteCallTransaction(function);
    }

    public RemoteFunctionCall<TransactionReceipt> create_seed(byte[] _bp_id, BigInteger randomness) {
        final org.web3j.abi.datatypes.Function function = new org.web3j.abi.datatypes.Function(
                FUNC_CREATE_SEED, 
                Arrays.<Type>asList(new org.web3j.abi.datatypes.generated.Bytes32(_bp_id), 
                new org.web3j.abi.datatypes.generated.Uint256(randomness)), 
                Collections.<TypeReference<?>>emptyList());
        return executeRemoteCallTransaction(function);
    }

    public RemoteFunctionCall<byte[]> peek_seed(byte[] _bp_id) {
        final org.web3j.abi.datatypes.Function function = new org.web3j.abi.datatypes.Function(FUNC_PEEK_SEED, 
                Arrays.<Type>asList(new org.web3j.abi.datatypes.generated.Bytes32(_bp_id)), 
                Arrays.<TypeReference<?>>asList(new TypeReference<DynamicBytes>() {}));
        return executeRemoteCallSingleValueReturn(function, byte[].class);
    }

    public RemoteFunctionCall<TransactionReceipt> verify(byte[] _bp_id, BigInteger _block_id, List<BigInteger> numbers) {
        final org.web3j.abi.datatypes.Function function = new org.web3j.abi.datatypes.Function(
                FUNC_VERIFY, 
                Arrays.<Type>asList(new org.web3j.abi.datatypes.generated.Bytes32(_bp_id), 
                new org.web3j.abi.datatypes.generated.Uint256(_block_id), 
                new org.web3j.abi.datatypes.DynamicArray<org.web3j.abi.datatypes.generated.Uint256>(
                        org.web3j.abi.datatypes.generated.Uint256.class,
                        org.web3j.abi.Utils.typeMap(numbers, org.web3j.abi.datatypes.generated.Uint256.class))), 
                Collections.<TypeReference<?>>emptyList());
        return executeRemoteCallTransaction(function);
    }

    @Deprecated
    public static Data load(String contractAddress, Web3j web3j, Credentials credentials, BigInteger gasPrice, BigInteger gasLimit) {
        return new Data(contractAddress, web3j, credentials, gasPrice, gasLimit);
    }

    @Deprecated
    public static Data load(String contractAddress, Web3j web3j, TransactionManager transactionManager, BigInteger gasPrice, BigInteger gasLimit) {
        return new Data(contractAddress, web3j, transactionManager, gasPrice, gasLimit);
    }

    public static Data load(String contractAddress, Web3j web3j, Credentials credentials, ContractGasProvider contractGasProvider) {
        return new Data(contractAddress, web3j, credentials, contractGasProvider);
    }

    public static Data load(String contractAddress, Web3j web3j, TransactionManager transactionManager, ContractGasProvider contractGasProvider) {
        return new Data(contractAddress, web3j, transactionManager, contractGasProvider);
    }

    public static RemoteCall<Data> deploy(Web3j web3j, Credentials credentials, ContractGasProvider contractGasProvider) {
        return deployRemoteCall(Data.class, web3j, credentials, contractGasProvider, BINARY, "");
    }

    public static RemoteCall<Data> deploy(Web3j web3j, TransactionManager transactionManager, ContractGasProvider contractGasProvider) {
        return deployRemoteCall(Data.class, web3j, transactionManager, contractGasProvider, BINARY, "");
    }

    @Deprecated
    public static RemoteCall<Data> deploy(Web3j web3j, Credentials credentials, BigInteger gasPrice, BigInteger gasLimit) {
        return deployRemoteCall(Data.class, web3j, credentials, gasPrice, gasLimit, BINARY, "");
    }

    @Deprecated
    public static RemoteCall<Data> deploy(Web3j web3j, TransactionManager transactionManager, BigInteger gasPrice, BigInteger gasLimit) {
        return deployRemoteCall(Data.class, web3j, transactionManager, gasPrice, gasLimit, BINARY, "");
    }

    protected String getStaticDeployedAddress(String networkId) {
        return _addresses.get(networkId);
    }

    public static String getPreviouslyDeployedAddress(String networkId) {
        return _addresses.get(networkId);
    }

    public static class BlockReportEventResponse extends BaseEventResponse {
        public byte[] blockpool;

        public String datanode;

        public BigInteger time;

        public BigInteger blockId;

        public Boolean corrupt;
    }
}
