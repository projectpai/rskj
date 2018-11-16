package co.rsk.paicoin;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;

import co.rsk.bitcoinj.core.Address;
import co.rsk.bitcoinj.core.BtcECKey;
import co.rsk.bitcoinj.core.BtcTransaction;
import co.rsk.bitcoinj.core.NetworkParameters;
import co.rsk.bitcoinj.core.PartialMerkleTree;
import co.rsk.bitcoinj.core.Sha256Hash;
import co.rsk.bitcoinj.core.TransactionInput;
import co.rsk.bitcoinj.crypto.TransactionSignature;
import co.rsk.bitcoinj.script.Script;
import co.rsk.bitcoinj.script.ScriptChunk;
import co.rsk.config.BridgeConstants;
import co.rsk.config.RskSystemProperties;
import co.rsk.core.ReversibleTransactionExecutor;
import co.rsk.core.RskAddress;
import co.rsk.core.Wallet;
import co.rsk.crypto.Keccak256;
import co.rsk.mine.MinerClient;
import co.rsk.mine.MinerServer;
import co.rsk.peg.StateForFederator;
import co.rsk.rpc.ExecutionBlockRetriever;
import org.apache.commons.lang3.ArrayUtils;
import org.ethereum.core.Account;
import org.ethereum.core.Block;
import org.ethereum.core.Blockchain;
import org.ethereum.core.Transaction;
import org.ethereum.core.TransactionReceipt;
import org.ethereum.facade.Ethereum;
import org.ethereum.listener.EthereumListener;
import org.ethereum.listener.EthereumListenerAdapter;
import org.ethereum.util.RLP;
import org.ethereum.util.RLPList;
import org.ethereum.vm.GasCost;
import org.ethereum.vm.LogInfo;
import org.ethereum.vm.program.ProgramResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import static co.rsk.paicoin.ImportUtils.encodeHex;
import static co.rsk.paicoin.ImportUtils.last;
import static co.rsk.peg.Bridge.ADD_LOCK_WHITELIST_ADDRESS;
import static co.rsk.peg.Bridge.ADD_SIGNATURE;
import static co.rsk.peg.Bridge.GET_BTC_BLOCKCHAIN_BEST_CHAIN_HEIGHT;
import static co.rsk.peg.Bridge.GET_FEDERATION_ADDRESS;
import static co.rsk.peg.Bridge.GET_MINIMUM_LOCK_TX_VALUE;
import static co.rsk.peg.Bridge.GET_STATE_FOR_BTC_RELEASE_CLIENT;
import static co.rsk.peg.Bridge.IS_BTC_TX_HASH_ALREADY_PROCESSED;
import static co.rsk.peg.Bridge.RECEIVE_HEADERS;
import static co.rsk.peg.Bridge.REGISTER_BTC_TRANSACTION;
import static co.rsk.peg.Bridge.RELEASE_BTC_TOPIC;
import static co.rsk.peg.Bridge.UPDATE_COLLECTIONS;
import static org.apache.commons.lang3.ArrayUtils.EMPTY_BYTE_ARRAY;
import static org.ethereum.vm.PrecompiledContracts.BRIDGE_ADDR;
import static org.springframework.beans.factory.config.ConfigurableBeanFactory.SCOPE_PROTOTYPE;


@Component
@Scope(SCOPE_PROTOTYPE)
public class BridgeClient {

    private final static Logger LOGGER = LoggerFactory.getLogger("bridge-client");

    private final BridgeConstants bridgeConstants;
    private final NetworkParameters networkParameters;
    private final Ethereum ethereum;
    private final Blockchain blockchain;
    private final Wallet wallet;
    private final ReversibleTransactionExecutor reversibleTransactionExecutor;
    private final ExecutionBlockRetriever executionBlockRetriever;
    private final MinerClient minerClient;
    private final MinerServer minerServer;

    private final RskAddress whitelistAuthorizeAddr;
    private final RskAddress importAddr;
    private final BigInteger maxTransferCoins;
    private final ArrayBlockingQueue<BtcTransaction> releasePaicoinsTxQueue;
    private EthereumListener ethereumListener;

    @Autowired
    public BridgeClient(RskSystemProperties rskSystemProperties, Ethereum ethereum, Blockchain blockchain, Wallet wallet,
                        ReversibleTransactionExecutor reversibleTransactionExecutor, ExecutionBlockRetriever executionBlockRetriever,
                        MinerClient minerClient, MinerServer minerServer) {
        this.bridgeConstants = rskSystemProperties.getBlockchainConfig().getCommonConstants().getBridgeConstants();
        this.networkParameters = bridgeConstants.getBtcParams();
        this.ethereum = ethereum;
        this.blockchain = blockchain;
        this.wallet = wallet;
        this.reversibleTransactionExecutor = reversibleTransactionExecutor;
        this.executionBlockRetriever = executionBlockRetriever;
        this.minerClient = minerClient;
        this.minerServer = minerServer;

        this.whitelistAuthorizeAddr = rskSystemProperties.getPaicoinWhitelistAuthorizeAddr();
        this.importAddr = rskSystemProperties.getPaicoinImportAddr();
        this.maxTransferCoins = BigInteger.valueOf(co.rsk.bitcoinj.core.Coin.COIN.multiply(rskSystemProperties.getPaicoinMaxTransferCoins()).getValue());
        this.releasePaicoinsTxQueue = new ArrayBlockingQueue<>(16);
    }

    public void start() {
        ethereumListener = new BlockEthereumListener();
        ethereum.addListener(ethereumListener);
    }

    public void stop() {
        ethereum.removeListener(ethereumListener);
    }

    public BridgeConstants getBridgeConstants() {
        return bridgeConstants;
    }

    public NetworkParameters getNetworkParameters() {
        return networkParameters;
    }

    private Account getAccount(RskAddress address) {
        Account account = wallet.getAccount(address);
        if (account == null)
            throw new RuntimeException("From address private key could not be found in this node");
        return account;
    }

    private BtcECKey getBtcECKey(RskAddress address) {
        return BtcECKey.fromPrivate(getAccount(address).getEcKey().getPrivKeyBytes());
    }

    private void sendTransaction(RskAddress address, byte[] data) {
        Account account = getAccount(address);
        synchronized (blockchain.getTransactionPool()) {
            Transaction transaction = ethereum.createTransaction(blockchain.getTransactionPool().getRepository().getNonce(address), BigInteger.ZERO,
                BigInteger.valueOf(GasCost.TRANSACTION_DEFAULT), BRIDGE_ADDR.getBytes(), BigInteger.ZERO, data);
            transaction.sign(account.getEcKey().getPrivKeyBytes());
            ethereum.submitTransaction(transaction.toImmutableTransaction());
        }
    }

    private ProgramResult callContract(byte[] data) {
        Block executionBlock = executionBlockRetriever.getExecutionBlock("pending");
        return reversibleTransactionExecutor.executeTransaction(executionBlock, executionBlock.getCoinbase(), EMPTY_BYTE_ARRAY,
            BigInteger.valueOf(GasCost.TRANSACTION_DEFAULT).toByteArray(), BRIDGE_ADDR.getBytes(), EMPTY_BYTE_ARRAY, data, RskAddress.nullAddress().getBytes());
    }

    private static Sha256Hash hashForSignature(BtcTransaction transaction, int inputIndex, List<ScriptChunk> chunks) {
        Script redeemScript = new Script(last(chunks).data);
        return transaction.hashForSignature(inputIndex, redeemScript, BtcTransaction.SigHash.ALL, false);
    }

    private static List<byte[]> transactionSignatures(BtcTransaction transaction, BtcECKey key) {
        List<TransactionInput> transactionInputs = transaction.getInputs();
        List<byte[]> signatures = new ArrayList<>(transactionInputs.size());
        for (int i = 0; i < transactionInputs.size(); ++i) {
            Sha256Hash sighash = hashForSignature(transaction, i, transactionInputs.get(i).getScriptSig().getChunks());
            signatures.add(key.sign(sighash).encodeToDER());
        }
        return signatures;
    }

    private static boolean hasSignature(BtcECKey key, Sha256Hash sighash, List<ScriptChunk> chunks) {
        for (int j = 1; j < chunks.size() - 1; j++) {
            ScriptChunk chunk = chunks.get(j);
            if (ArrayUtils.isEmpty(chunk.data))
                continue;
            TransactionSignature signature = TransactionSignature.decodeFromBitcoin(chunk.data, false, false);
            if (key.verify(sighash, signature))
                return true;
        }
        return false;
    }

    private static boolean hasSignedInput(BtcTransaction transaction, BtcECKey key) {
        List<TransactionInput> transactionInputs = transaction.getInputs();
        for (int i = 0; i < transactionInputs.size(); ++i) {
            List<ScriptChunk> chunks = transactionInputs.get(i).getScriptSig().getChunks();
            Sha256Hash sighash = hashForSignature(transaction, i, chunks);
            if (hasSignature(key, sighash, chunks))
                return true;
        }
        return false;
    }

    public Address getFederationAddress() {
        ProgramResult result = callContract(GET_FEDERATION_ADDRESS.encode());
        return Address.fromBase58(networkParameters, (String)GET_FEDERATION_ADDRESS.decodeResult(result.getHReturn())[0]);
    }

    public void registerPaicoinTransaction(byte[] txHex, long height, PartialMerkleTree merkleTree) {
        sendTransaction(importAddr, REGISTER_BTC_TRANSACTION.encode(txHex, height, merkleTree.bitcoinSerialize()));
    }

    public void receiveHeaders(byte[][] headers) {
        sendTransaction(importAddr, RECEIVE_HEADERS.encode((Object)headers));
    }

    public void updateCollections() {
        sendTransaction(importAddr, UPDATE_COLLECTIONS.encode());
    }

    public void addSignature(Keccak256 txHash, BtcTransaction transaction) {
        BtcECKey signer = getBtcECKey(importAddr);
        sendTransaction(importAddr, ADD_SIGNATURE.encode(signer.getPubKey(), transactionSignatures(transaction, signer), txHash.getBytes()));
    }

    public Map<Keccak256, BtcTransaction> getTransationsWaitingForSignature() {
        BtcECKey signer = getBtcECKey(importAddr);
        ProgramResult result = callContract(GET_STATE_FOR_BTC_RELEASE_CLIENT.encode());
        StateForFederator state = new StateForFederator((byte[])GET_STATE_FOR_BTC_RELEASE_CLIENT.decodeResult(result.getHReturn())[0], networkParameters);
        Map<Keccak256, BtcTransaction> transactions = state.getRskTxsWaitingForSignatures();
        transactions.entrySet().removeIf(e -> hasSignedInput(e.getValue(), signer));
        return transactions;
    }

    public void mineBlock() {
        Block bestBlock = blockchain.getBestBlock();
        minerServer.buildBlockToMine(bestBlock, false);
        minerClient.mineBlock();
    }

    public void addLockWhitelistAddress(String addressBase58) {
        sendTransaction(whitelistAuthorizeAddr, ADD_LOCK_WHITELIST_ADDRESS.encode(addressBase58, maxTransferCoins));
    }

    public long getBtcBlockchainBestChainHeight() {
        ProgramResult result = callContract(GET_BTC_BLOCKCHAIN_BEST_CHAIN_HEIGHT.encode());
        return ((BigInteger)GET_BTC_BLOCKCHAIN_BEST_CHAIN_HEIGHT.decodeResult(result.getHReturn())[0]).longValue();
    }

    public boolean isPaicoinTxHashAlreadyProcessed(Sha256Hash txHash) {
        ProgramResult result = callContract(IS_BTC_TX_HASH_ALREADY_PROCESSED.encode(txHash.toString()));
        return (Boolean)IS_BTC_TX_HASH_ALREADY_PROCESSED.decodeResult(result.getHReturn())[0];
    }

    public co.rsk.bitcoinj.core.Coin getMinimumLockTxValue() {
        ProgramResult result = callContract(GET_MINIMUM_LOCK_TX_VALUE.encode());
        return co.rsk.bitcoinj.core.Coin.valueOf(((BigInteger)GET_MINIMUM_LOCK_TX_VALUE.decodeResult(result.getHReturn())[0]).longValue());
    }

    public long getBlocksCount() {
        Block bestBlock = blockchain.getBestBlock();
        if (bestBlock == null)
            return 0;
        return bestBlock.getNumber();
    }

    public List<BtcTransaction> checkReleasePaicoinsTransactions() {
        ArrayList<BtcTransaction> transactions = new ArrayList<>(16);
        releasePaicoinsTxQueue.drainTo(transactions);
        return transactions;
    }

    private class BlockEthereumListener extends EthereumListenerAdapter {
        @Override
        public void onBlock(Block block, List<TransactionReceipt> receipts) {
            for (TransactionReceipt receipt : receipts) {
                if (receipt.isSuccessful()) {
                    Transaction transaction = receipt.getTransaction();
                    if (transaction.getReceiveAddress().equals(BRIDGE_ADDR)) {
                        for (LogInfo logInfo : receipt.getLogInfoList()) {
                            if (logInfo.getTopics().contains(RELEASE_BTC_TOPIC)) {
                                RLPList rlpList = (RLPList)RLP.decode2(logInfo.getData()).get(0);
                                byte[] data = rlpList.get(1).getRLPData();
                                BtcTransaction btcTransaction = new BtcTransaction(networkParameters, data);
                                LOGGER.info("Pending Paicoin transaction\nBlock: {}, TxId: {}\nTxHex: {}\n{}", block.getNumber(), transaction.getHash(), encodeHex(data), btcTransaction);
                                releasePaicoinsTxQueue.add(btcTransaction);
                            }
                        }
                    }
                }
            }
        }
    }
}
