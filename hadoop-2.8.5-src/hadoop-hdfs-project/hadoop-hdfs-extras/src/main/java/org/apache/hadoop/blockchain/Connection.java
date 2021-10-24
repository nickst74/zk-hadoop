package org.apache.hadoop.blockchain;

import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;
import org.web3j.crypto.Credentials;
import org.web3j.tx.gas.DefaultGasProvider;

public class Connection {
    private final Web3j web3j;
    private final Credentials creds;
    protected final Data contract_wrapper;

    Connection(String blockhain, String wallet_pk, String contract_address){
        this.web3j = Web3j.build(new HttpService(blockhain));
        this.creds = Credentials.create(wallet_pk);
        Data wrapper = null;
        try {
            wrapper = Data.load(contract_address, this.web3j, this.creds, new DefaultGasProvider());
        } catch (Exception e) {
            System.out.println("Failed to resolve smart contract address. Please ensure its format is valid.");
            System.exit(1);
        }
        this.contract_wrapper = wrapper;
    }
}
