package co.rsk.paicoin;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;

import co.rsk.bitcoinj.core.Address;
import co.rsk.bitcoinj.core.BtcECKey;
import co.rsk.bitcoinj.core.BtcTransaction;
import co.rsk.bitcoinj.core.Coin;
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
import org.ethereum.crypto.ECKey;
import org.ethereum.crypto.HashUtil;
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
import org.springframework.stereotype.Component;

import static co.rsk.logger.LoggingMarker.SILENT;
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
import static co.rsk.peg.Bridge.REMOVE_LOCK_WHITELIST_ADDRESS;
import static co.rsk.peg.Bridge.UPDATE_COLLECTIONS;
import static org.apache.commons.lang3.ArrayUtils.EMPTY_BYTE_ARRAY;
import static org.ethereum.vm.PrecompiledContracts.BRIDGE_ADDR;


@Component
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

    private final ArrayBlockingQueue<BtcTransaction> releasePaicoinsTxQueue;
    private final EthereumListener ethereumListener;

    private BtcECKey importBtcECKey;
    private BtcECKey whitelistAuthorizeBtcECKey;

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
        this.releasePaicoinsTxQueue = new ArrayBlockingQueue<>(16);
        this.ethereumListener = new BlockEthereumListener();
    }

    public void start() {
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

    private static RskAddress getRskAddress(BtcECKey key) {
        if (key == null) {
            return RskAddress.nullAddress();
        } else {
            byte[] pubKey = (key.isCompressed() ? key.decompress() : key).getPubKey();
            return new RskAddress(HashUtil.sha3omit12(Arrays.copyOfRange(pubKey, 1, pubKey.length)));
        }
    }

    private void sendTransaction(BtcECKey key, byte[] data) {
        synchronized (blockchain.getTransactionPool()) {
            Transaction transaction = ethereum.createTransaction(blockchain.getTransactionPool().getRepository().getNonce(getRskAddress(key)), BigInteger.ZERO,
                BigInteger.valueOf(GasCost.TRANSACTION_DEFAULT), BRIDGE_ADDR.getBytes(), BigInteger.ZERO, data);
            transaction.sign(key.getPrivKeyBytes());
            ethereum.submitTransaction(transaction);
        }
    }

    private ProgramResult callContract(BtcECKey key, byte[] data) {
        Block executionBlock = executionBlockRetriever.getExecutionBlock("pending");
        return reversibleTransactionExecutor.executeTransaction(executionBlock, executionBlock.getCoinbase(), EMPTY_BYTE_ARRAY,
            BigInteger.valueOf(GasCost.TRANSACTION_DEFAULT).toByteArray(), BRIDGE_ADDR.getBytes(), EMPTY_BYTE_ARRAY, data, getRskAddress(key).getBytes());
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
        ProgramResult result = callContract(null, GET_FEDERATION_ADDRESS.encode());
        return Address.fromBase58(networkParameters, (String)GET_FEDERATION_ADDRESS.decodeResult(result.getHReturn())[0]);
    }

    public boolean hasRequiredSignatures() {
        boolean result = true;
        if (importBtcECKey == null) {
            for (BtcECKey key : bridgeConstants.getGenesisFederation().getPublicKeys()) {
                Account account = wallet.getAccount(getRskAddress(key));
                if (account != null) {
                    if (importBtcECKey != null) {
                        LOGGER.error(SILENT, "Federator's private key has to be unique for this node");
                        result = false;
                        break;
                    } else {
                        importBtcECKey = BtcECKey.fromPrivate(account.getEcKey().getPrivKey());
                    }
                }
            }
            if (!result) {
                importBtcECKey = null;
            } else if (importBtcECKey == null) {
                LOGGER.error(SILENT, "Federator's private key was not found");
                result = false;
            }
        }
        if (whitelistAuthorizeBtcECKey == null) {
            for (ECKey key : bridgeConstants.getLockWhitelistChangeAuthorizer().getAuthorizedKeys()) {
                Account account = wallet.getAccount(new RskAddress(key.getAddress()));
                if (account != null) {
                    if (whitelistAuthorizeBtcECKey != null) {
                        LOGGER.error(SILENT, "Whitelist authorizer's private key has to be unique for this node");
                        result = false;
                        break;
                    } else {
                        whitelistAuthorizeBtcECKey = BtcECKey.fromPrivate(account.getEcKey().getPrivKey());
                    }
                }
            }
            if (!result) {
                whitelistAuthorizeBtcECKey = null;
            } else if (whitelistAuthorizeBtcECKey == null) {
                LOGGER.error(SILENT, "Whitelist authorizer's private key was not found");
                result = false;
            }
        }
        return result;
    }

    public void registerPaicoinTransaction(byte[] txHex, long height, PartialMerkleTree merkleTree) {
        sendTransaction(importBtcECKey, REGISTER_BTC_TRANSACTION.encode(txHex, height, merkleTree.bitcoinSerialize()));
    }

    private static Object serializeBlockHeaders(Map<String, byte[]> blockHeaders) {
        return blockHeaders.values().toArray();
    }

    public boolean receiveHeaders(Map<String, byte[]> blockHeaders) {
        byte[] contract = RECEIVE_HEADERS.encode(serializeBlockHeaders(blockHeaders));
        ProgramResult result = callContract(importBtcECKey, contract);
        Object[] redundantHeaders = (Object[])RECEIVE_HEADERS.decodeResult(result.getHReturn())[0];
        if (ArrayUtils.isEmpty(redundantHeaders)) {
            sendTransaction(importBtcECKey, contract);
            return true;
        } else {
            for (Object hash : redundantHeaders)
                blockHeaders.remove(hash);
            if (!blockHeaders.isEmpty()) {
                sendTransaction(importBtcECKey, RECEIVE_HEADERS.encode(serializeBlockHeaders(blockHeaders)));
                return true;
            }
        }
        return false;
    }

    public void updateCollections() {
        sendTransaction(importBtcECKey, UPDATE_COLLECTIONS.encode());
    }

    public void addSignature(Keccak256 txHash, BtcTransaction transaction) {
        sendTransaction(importBtcECKey, ADD_SIGNATURE.encode(importBtcECKey.getPubKey(), transactionSignatures(transaction, importBtcECKey), txHash.getBytes()));
    }

    public Map<Keccak256, BtcTransaction> getTransationsWaitingForSignature() {
        ProgramResult result = callContract(null, GET_STATE_FOR_BTC_RELEASE_CLIENT.encode());
        StateForFederator state = new StateForFederator((byte[])GET_STATE_FOR_BTC_RELEASE_CLIENT.decodeResult(result.getHReturn())[0], networkParameters);
        Map<Keccak256, BtcTransaction> transactions = state.getRskTxsWaitingForSignatures();
        transactions.entrySet().removeIf(e -> hasSignedInput(e.getValue(), importBtcECKey));
        return transactions;
    }

    public Map<Keccak256, BtcTransaction> getTransationsWaitingForSignature(BtcECKey key) {
        ProgramResult result = callContract(null, GET_STATE_FOR_BTC_RELEASE_CLIENT.encode());
        StateForFederator state = new StateForFederator((byte[])GET_STATE_FOR_BTC_RELEASE_CLIENT.decodeResult(result.getHReturn())[0], networkParameters);
        Map<Keccak256, BtcTransaction> transactions = state.getRskTxsWaitingForSignatures();
        transactions.entrySet().removeIf(e -> hasSignedInput(e.getValue(), key));
        return transactions;
    }

    public void addSignature(BtcECKey key, Keccak256 txHash, BtcTransaction transaction) {
        sendTransaction(key, ADD_SIGNATURE.encode(key.getPubKey(), transactionSignatures(transaction, key), txHash.getBytes()));
    }

    public void mineBlock() {
        Block bestBlock = blockchain.getBestBlock();
        minerServer.buildBlockToMine(bestBlock, false);
        minerClient.mineBlock();
    }

    public void addLockWhitelistAddress(String address, Coin transferCoins) {
        byte[] contract = ADD_LOCK_WHITELIST_ADDRESS.encode(address, BigInteger.valueOf(transferCoins.getValue()));
        ProgramResult result = callContract(whitelistAuthorizeBtcECKey, contract);
        long maxTransferCoins = ((BigInteger)ADD_LOCK_WHITELIST_ADDRESS.decodeResult(result.getHReturn())[0]).longValue();
        if (maxTransferCoins == 0) {
            sendTransaction(whitelistAuthorizeBtcECKey, contract);
        } else if (maxTransferCoins > 0) {
            LOGGER.debug("Paicoin address is already in the whitelist");
            if (transferCoins.getValue() > maxTransferCoins) {
                sendTransaction(whitelistAuthorizeBtcECKey, REMOVE_LOCK_WHITELIST_ADDRESS.encode(address));
                sendTransaction(whitelistAuthorizeBtcECKey, contract);
            }
        } else {
            throw new RuntimeException("Invalid paicoin address");
        }
    }

    public long getBtcBlockchainBestChainHeight() {
        ProgramResult result = callContract(null, GET_BTC_BLOCKCHAIN_BEST_CHAIN_HEIGHT.encode());
        return ((BigInteger)GET_BTC_BLOCKCHAIN_BEST_CHAIN_HEIGHT.decodeResult(result.getHReturn())[0]).longValue();
    }

    public boolean isPaicoinTxHashAlreadyProcessed(Sha256Hash txHash) {
        ProgramResult result = callContract(null, IS_BTC_TX_HASH_ALREADY_PROCESSED.encode(txHash.toString()));
        return (Boolean)IS_BTC_TX_HASH_ALREADY_PROCESSED.decodeResult(result.getHReturn())[0];
    }

    public co.rsk.bitcoinj.core.Coin getMinimumLockTxValue() {
        ProgramResult result = callContract(null, GET_MINIMUM_LOCK_TX_VALUE.encode());
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
                                LOGGER.info("Pending Paicoin transaction\nBlock: {},\nTxId: {}\nTxHex: {}\n{}", block.getHash(), transaction.getHash(), encodeHex(data), btcTransaction);
                                releasePaicoinsTxQueue.add(btcTransaction);
                            }
                        }
                    }
                }
            }
        }
    }
}
