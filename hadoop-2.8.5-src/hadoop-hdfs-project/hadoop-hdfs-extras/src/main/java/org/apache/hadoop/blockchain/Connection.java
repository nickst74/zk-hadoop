package org.apache.hadoop.blockchain;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;

import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;
import org.web3j.crypto.CipherException;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.WalletUtils;
import org.web3j.tx.FastRawTransactionManager;
import org.web3j.tx.TransactionManager;
import org.web3j.tx.gas.DefaultGasProvider;

@SuppressWarnings("deprecation")
public class Connection {
    private final Web3j web3j;
    private final Credentials creds;
    protected final TransactionManager txManager;
    protected Data contract_wrapper;

    Connection(String blockhain, String password, String keystore_path, long chainId) throws IOException, CipherException{
        this.web3j = Web3j.build(new HttpService(blockhain));
        this.creds = WalletUtils.loadCredentials(password, new File(keystore_path));
        this.txManager = new FastRawTransactionManager(this.web3j, this.creds, chainId);
        this.contract_wrapper = null;
    }

    Connection(String blockhain, String password, String keystore_path, long chainId, String contract_address) throws IOException, CipherException{
        this.web3j = Web3j.build(new HttpService(blockhain));
        this.creds = WalletUtils.loadCredentials(password, new File(keystore_path));
        this.txManager = new FastRawTransactionManager(this.web3j, this.creds, chainId);
        this.connect(contract_address);
    }

    public void connect(String contract_address){
        try {
            this.contract_wrapper = Data.load(contract_address, this.web3j, this.txManager, DefaultGasProvider.GAS_PRICE, BigInteger.valueOf(2000000));
        } catch (Exception e) {
            System.out.println("Failed to resolve smart contract address. Please ensure its format is valid.");
            System.exit(1);
        }
    }

    public String getAddress(){
        return this.creds.getAddress();
    }
}
