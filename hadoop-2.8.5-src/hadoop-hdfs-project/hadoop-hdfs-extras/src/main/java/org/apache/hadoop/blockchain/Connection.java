package org.apache.hadoop.blockchain;

import java.math.BigInteger;

import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;
import org.web3j.crypto.Credentials;
import org.web3j.tx.gas.DefaultGasProvider;

@SuppressWarnings("deprecation")
public class Connection {
    private final Web3j web3j;
    private final Credentials creds;
    protected Data contract_wrapper;

    Connection(String blockhain, String wallet_pk){
        this.web3j = Web3j.build(new HttpService(blockhain));
        this.creds = Credentials.create(wallet_pk);
        this.contract_wrapper = null;
    }

    Connection(String blockhain, String wallet_pk, String contract_address){
        this.web3j = Web3j.build(new HttpService(blockhain));
        this.creds = Credentials.create(wallet_pk);
        this.connect(contract_address);
    }

    public void connect(String contract_address){
        try {
            this.contract_wrapper = Data.load(contract_address, this.web3j, this.creds, DefaultGasProvider.GAS_PRICE, BigInteger.valueOf(6000000));
        } catch (Exception e) {
            System.out.println("Failed to resolve smart contract address. Please ensure its format is valid.");
            System.exit(1);
        }
    }
}
