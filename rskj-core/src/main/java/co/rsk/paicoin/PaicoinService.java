package co.rsk.paicoin;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeMap;

import co.rsk.bitcoinj.core.Address;
import co.rsk.bitcoinj.core.BtcBlock;
import co.rsk.bitcoinj.core.BtcTransaction;
import co.rsk.bitcoinj.core.Coin;
import co.rsk.bitcoinj.core.MessageSerializer;
import co.rsk.bitcoinj.core.PartialMerkleTree;
import co.rsk.bitcoinj.core.Sha256Hash;
import co.rsk.bitcoinj.core.TransactionInput;
import co.rsk.bitcoinj.core.TransactionOutput;
import co.rsk.bitcoinj.core.Utils;
import co.rsk.config.RskSystemProperties;
import co.rsk.crypto.Keccak256;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import static co.rsk.bitcoinj.core.Utils.sha256hash160;
import static co.rsk.paicoin.ImportUtils.round;


@Component
public class PaicoinService {

    private final static Logger LOGGER = LoggerFactory.getLogger("paicoin-service");

    private final BridgeClient bridgeClient;
    private final Timer timer;
    private final PaicoinClient paicoinClient;
    private final int headersPerBlock;
    private final Duration receiveHeadersInterval;
    private final Duration pollInterval;
    private final MessageSerializer paicoinDeserializer;
    private final int updateCollectionsMaxBlocksCount;
    private final Duration updateCollectionsInterval;
    private final int paicoinsMultiple;

    private Instant receiveHeadersTimestamp;
    private long blocksCount;
    private Instant updateCollectionsTimestamp;
    private Address federationAddress;

    @Autowired
    public PaicoinService(RskSystemProperties rskSystemProperties, BridgeClient bridgeClient) throws GeneralSecurityException {
        this.bridgeClient = bridgeClient;
        timer = new Timer();
        paicoinClient = new PaicoinClient(rskSystemProperties.getPaicoinUrl(), rskSystemProperties.getPaicoinUsername(), rskSystemProperties.getPaicoinPassword());
        headersPerBlock = rskSystemProperties.getPaicoinHeadersPerBlock();
        receiveHeadersInterval = rskSystemProperties.getPaicoinReceiveHeadersInterval();
        pollInterval = rskSystemProperties.getPaicoinPollInterval();
        paicoinDeserializer = bridgeClient.getNetworkParameters().getDefaultSerializer();

        updateCollectionsMaxBlocksCount = rskSystemProperties.getPaicoinUpdateCollectionsMaxBlocksCount();
        updateCollectionsInterval = rskSystemProperties.getPaicoinUpdateCollectionsInterval();
        paicoinsMultiple = rskSystemProperties.getPaicoinsMultiple();

        receiveHeadersTimestamp = Instant.MIN;
        blocksCount = 0;
        updateCollectionsTimestamp = Instant.MIN;
    }

    private void getTxInputsAddresses(Collection<TransactionInput> txInputs, /* out */ Map<String, Coin> whitelist, Coin coins) {
        for (TransactionInput txInput : txInputs) {
            try {
                String address = new Address(txInput.getParams(), sha256hash160(txInput.getScriptSig().getPubKey())).toBase58();
                Coin transferCoins = whitelist.get(address);
                if (transferCoins == null) {
                    whitelist.put(address, round(coins, paicoinsMultiple));
                } else if (transferCoins.longValue() < coins.longValue()) {
                    whitelist.replace(address, round(coins, paicoinsMultiple));
                }
            } catch (Throwable t) {
                LOGGER.error("Error input address", t);
            }
        }
    }

    private PartialMerkleTree createPartialMerkleTree(BtcTransaction transaction, List<BtcTransaction> transactions) {
        byte[] bits = new byte[(transactions.size() + 7) / 8];
        List<Sha256Hash> leaves = new ArrayList<>(transactions.size());
        for (int i = 0; i < transactions.size(); ++i) {
            Sha256Hash blockTxHash = transactions.get(i).getHash();
            if (transaction.getHash().equals(blockTxHash))
                Utils.setBitLE(bits, i);
            leaves.add(blockTxHash);
        }
        return PartialMerkleTree.buildFromLeaves(transaction.getParams(), bits, leaves);
    }

    private void getTransfers(List<BtcTransaction> transactions, long height, /* out */ Map<String, Coin> whitelist, /* out */ Collection<Transfer> transfers) {
        for (BtcTransaction transaction : transactions) {
            if (!transaction.isCoinBase()) {
                for (TransactionOutput txOutput : transaction.getOutputs()) {
                    Address outAddr = txOutput.getAddressFromP2SH(transaction.getParams());
                    if (outAddr != null && outAddr.equals(federationAddress)) {
                        if (!bridgeClient.isPaicoinTxHashAlreadyProcessed(transaction.getHash())) {
                            getTxInputsAddresses(transaction.getInputs(), whitelist, txOutput.getValue());
                            transfers.add(new Transfer(transaction.bitcoinSerialize(), height, createPartialMerkleTree(transaction, transactions)));
                            break;
                        }
                    }
                }
            }
        }
    }

    private void importPaicoinBlocks() throws IOException, ParseException {
        long paiBlockchainHeight = bridgeClient.getBtcBlockchainBestChainHeight();
        LOGGER.debug("rsk-paicoin height: {}", paiBlockchainHeight);
        long blockchainHeight = paicoinClient.getBlockCount();
        LOGGER.debug("paicoin height: {}", blockchainHeight);

        int count = (int) (blockchainHeight - paiBlockchainHeight);
        LOGGER.debug("new blocks: {}", count);
        Instant now = Instant.now();
        if ((count >= headersPerBlock) || (count > 0 && now.minus(receiveHeadersInterval).isAfter(receiveHeadersTimestamp))) {

            Map<Long, BtcBlock> blockCache = new TreeMap<>();
            Map<String, Coin> whitelist = new LinkedHashMap<>(128);
            List<Transfer> transfers = new ArrayList<>(128);

            ++paiBlockchainHeight;

            int chanks = (count + headersPerBlock - 1) / headersPerBlock;
            for (int i = 0; i < chanks; ++i) {
                Map<String, byte[]> headers = new LinkedHashMap<>(headersPerBlock);

                for (int k = 0; k < headersPerBlock && count > 0; ++k, ++paiBlockchainHeight, --count) {
                    String hash = paicoinClient.getBlockHash(paiBlockchainHeight);
                    byte[] blockData = paicoinClient.getBlock(hash);
                    BtcBlock block = paicoinDeserializer.makeBlock(blockData);
                    headers.put(block.getHashAsString(), Arrays.copyOfRange(blockData, 0, BtcBlock.HEADER_SIZE));
                    blockCache.put(paiBlockchainHeight, block);

                    long confirmedBlockHeight = paiBlockchainHeight - bridgeClient.getBridgeConstants().getBtc2RskMinimumAcceptableConfirmations();
                    if (confirmedBlockHeight > 0) {
                        BtcBlock cachedBlock = blockCache.get(confirmedBlockHeight);
                        if (cachedBlock == null)
                            cachedBlock = paicoinDeserializer.makeBlock(paicoinClient.getBlock(paicoinClient.getBlockHash(confirmedBlockHeight)));
                        getTransfers(cachedBlock.getTransactions(), confirmedBlockHeight, whitelist, transfers);
                    }
                }
                if (bridgeClient.receiveHeaders(headers))
                    bridgeClient.mineBlock();
            }
            receiveHeadersTimestamp = now;

            if (!whitelist.isEmpty()) {
                for (Map.Entry<String, Coin> e : whitelist.entrySet())
                    bridgeClient.addLockWhitelistAddress(e.getKey(), e.getValue());
                bridgeClient.mineBlock();
            }

            if (!transfers.isEmpty()) {
                for (Transfer transfer : transfers)
                    bridgeClient.registerPaicoinTransaction(transfer.txHex, transfer.height, transfer.partialMerkleTree);
                bridgeClient.mineBlock();
            }

            LOGGER.debug("new rsk-paicoin height: {}", bridgeClient.getBtcBlockchainBestChainHeight());
        }
    }

    private void releasePaicoins() throws IOException, ParseException {
        long currentBlocksCount = bridgeClient.getBlocksCount();
        long count = currentBlocksCount - blocksCount;
        Instant now = Instant.now();
        if ((count >= updateCollectionsMaxBlocksCount) || (count > 0 && now.minus(updateCollectionsInterval).isAfter(updateCollectionsTimestamp))) {
            LOGGER.debug("Update collections");
            updateCollectionsTimestamp = now;
            blocksCount = currentBlocksCount;
            bridgeClient.updateCollections();
            bridgeClient.mineBlock();
        }
        Map<Keccak256, BtcTransaction> transactions = bridgeClient.getTransationsWaitingForSignature();
        for (Map.Entry<Keccak256, BtcTransaction> e : transactions.entrySet()) {
            Keccak256 hash = e.getKey();
            BtcTransaction transaction = e.getValue();
            LOGGER.debug("Pending transaction's hash: {}", hash);
            bridgeClient.addSignature(hash, transaction);
        }
        if (!transactions.isEmpty())
            bridgeClient.mineBlock();
        for (BtcTransaction transaction : bridgeClient.checkReleasePaicoinsTransactions()) {
            LOGGER.debug("Send transaction to Paicoin: {}", transaction);
            paicoinClient.sendTransaction(transaction.bitcoinSerialize());
        }
    }

    public void start() {
        bridgeClient.start();
        federationAddress = bridgeClient.getFederationAddress();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                if (!bridgeClient.hasSignatures())
                    return;
                try {
                    importPaicoinBlocks();
                } catch (Throwable t) {
                    LOGGER.error("Import Paicoin Blocks failed", t);
                }
                try {
                    releasePaicoins();
                } catch (Throwable t) {
                    LOGGER.error("Release Paicoins failed", t);
                }
            }
        }, 10000, pollInterval.toMillis());
    }

    public void stop() {
        timer.cancel();
        bridgeClient.stop();
    }

    private static class Transfer {

        private final byte[] txHex;
        private final long height;
        private final PartialMerkleTree partialMerkleTree;

        private Transfer(byte[] txHex, long height, PartialMerkleTree partialMerkleTree) {
            this.txHex = txHex;
            this.height = height;
            this.partialMerkleTree = partialMerkleTree;
        }
    }
}
