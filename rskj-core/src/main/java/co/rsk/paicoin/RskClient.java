package co.rsk.paicoin;

import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import co.rsk.bitcoinj.core.BtcECKey;
import co.rsk.bitcoinj.core.BtcTransaction;
import co.rsk.bitcoinj.core.NetworkParameters;
import co.rsk.bitcoinj.core.PartialMerkleTree;
import co.rsk.bitcoinj.core.Sha256Hash;
import co.rsk.bitcoinj.core.TransactionInput;
import co.rsk.bitcoinj.crypto.TransactionSignature;
import co.rsk.bitcoinj.script.Script;
import co.rsk.bitcoinj.script.ScriptChunk;
import co.rsk.core.Coin;
import co.rsk.core.RskAddress;
import co.rsk.crypto.Keccak256;
import co.rsk.peg.StateForFederator;
import com.squareup.okhttp.HttpUrl;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.RequestBody;
import com.squareup.okhttp.Response;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.ethereum.crypto.ECKey;
import org.ethereum.util.RLP;
import org.ethereum.util.RLPList;
import org.ethereum.vm.DataWord;
import org.ethereum.vm.PrecompiledContracts;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static co.rsk.paicoin.ImportUtils.JSON_MEDIA_TYPE;
import static co.rsk.paicoin.ImportUtils.JsonArrayBuilder;
import static co.rsk.paicoin.ImportUtils.JsonObjectBuilder;
import static co.rsk.paicoin.ImportUtils.createHttpClient;
import static co.rsk.paicoin.ImportUtils.decodeHex;
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
import static co.rsk.peg.Bridge.RELEASE_BTC;
import static co.rsk.peg.Bridge.UPDATE_COLLECTIONS;


public class RskClient {

    private final static Logger LOGGER = LoggerFactory.getLogger("rsk-client");

    private final static JSONParser JSON_PARSER = new JSONParser();

    private final OkHttpClient httpClient;
    private final Request.Builder request;

    public RskClient(String url) throws GeneralSecurityException {
        httpClient = createHttpClient();
        request = new Request.Builder().url(HttpUrl.parse(url));
    }

    private JSONObject doRequest(String method, JSONArray params) throws IOException, ParseException {
        JSONObject json = JsonObjectBuilder.create()
            .set("jsonrpc", "2.0")
            .set("id", 1)
            .set("method", method)
            .set("params", params)
            .build();
        Response response = httpClient.newCall(request.post(RequestBody.create(JSON_MEDIA_TYPE, json.toJSONString())).build()).execute();
        return (JSONObject)JSON_PARSER.parse(response.body().string());
    }

    private JSONObject sendTransaction(JsonObjectBuilder params) throws IOException, ParseException {
        return doRequest("eth_sendTransaction", JsonArrayBuilder.create()
            .add(params.set("to", PrecompiledContracts.BRIDGE_ADDR)
                .build())
            .build());
    }

    private JSONObject call(byte[] contract) throws IOException, ParseException {
        return doRequest("eth_call", JsonArrayBuilder.create()
            .add(JsonObjectBuilder.create()
                .set("to", PrecompiledContracts.BRIDGE_ADDR)
                .set("data", contract)
                .build())
            .add("pending")
            .build());
    }

    public void registerPaicoinTransaction(RskAddress address, byte[] txHex, long height, PartialMerkleTree merkleTree) throws IOException, ParseException {
        JSONObject result = sendTransaction(JsonObjectBuilder.create()
            .set("from", address)
            .set("data", REGISTER_BTC_TRANSACTION.encode(txHex, height, merkleTree.bitcoinSerialize())));
        LOGGER.debug("register paicoin transaction: {}", result.toJSONString());
    }

    public void receiveHeaders(RskAddress address, byte[][] headers) throws IOException, ParseException {
        JsonObjectBuilder params = JsonObjectBuilder.create()
            .set("to", PrecompiledContracts.BRIDGE_ADDR)
            .set("data", RECEIVE_HEADERS.encode((Object)headers));
        // long gas = estimateGas(params.build());
        JSONObject result = doRequest("eth_sendTransaction", JsonArrayBuilder.create()
            .add(params
                .set("from", address)
                /*.set("gas", gas)*/
                .build())
            .build());
        LOGGER.debug("receive headers: {}", result.toJSONString());
    }

    public Keccak256 releasePaicoins(RskAddress address, Coin amount) throws IOException, ParseException {
        JSONObject result = sendTransaction(JsonObjectBuilder.create()
            .set("from", address)
            .set("data", RELEASE_BTC.encode())
            .set("value", amount));
        LOGGER.debug("release paicoins: {}", result.toJSONString());
        return new Keccak256(decodeHex((String)result.get("result")));
    }

    public void updateCollections(RskAddress address) throws IOException, ParseException {
        JSONObject result = sendTransaction(JsonObjectBuilder.create()
            .set("from", address)
            .set("data", UPDATE_COLLECTIONS.encode()));
        LOGGER.debug("update collections: {}", result.toJSONString());
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

    public void addSignature(BtcECKey key, Keccak256 txHash, BtcTransaction transaction) throws IOException, ParseException {
        byte[] pubKey = key.getPubKey();
        JSONObject result = sendTransaction(JsonObjectBuilder.create()
            .set("from", ECKey.fromPublicOnly(pubKey).getAddress())
            .set("data", ADD_SIGNATURE.encode(pubKey, transactionSignatures(transaction, key), txHash.getBytes())));
        LOGGER.debug("add signature: {}", result.toJSONString());
    }

    public Map<Keccak256, BtcTransaction> getTransationsWaitingForSignature(NetworkParameters networkParameters, BtcECKey key) throws IOException, ParseException {
        JSONObject result = call(GET_STATE_FOR_BTC_RELEASE_CLIENT.encode());
        StateForFederator state = new StateForFederator((byte[])GET_STATE_FOR_BTC_RELEASE_CLIENT.decodeResult(decodeHex((String)result.get("result")))[0], networkParameters);
        Map<Keccak256, BtcTransaction> transactions = state.getRskTxsWaitingForSignatures();
        if (key != null)
            transactions.entrySet().removeIf(e -> hasSignedInput(e.getValue(), key));
        return transactions;
    }

    private long estimateGas(JSONObject txObject) throws IOException, ParseException {
        JSONObject result = doRequest("eth_estimateGas", JsonArrayBuilder.create()
            .add(txObject)
            .build());
        return Long.decode((String)result.get("result"));
    }

    public void mineBlock() throws IOException, ParseException {
        JSONObject result = doRequest("evm_mine", null);
        LOGGER.debug("mine block: {}", result.toJSONString());
    }

    public void addLockWhitelistAddress(RskAddress address, String addressBase58, BigInteger maxTransferValue) throws IOException, ParseException {
        JSONObject result = sendTransaction(JsonObjectBuilder.create()
            .set("from", address)
            .set("data", ADD_LOCK_WHITELIST_ADDRESS.encode(addressBase58, maxTransferValue)));
        LOGGER.debug("add lock whitelist address: {}", result.toJSONString());
    }

    public long getBtcBlockchainBestChainHeight() throws IOException, ParseException {
        JSONObject result = call(GET_BTC_BLOCKCHAIN_BEST_CHAIN_HEIGHT.encode());
        return ((BigInteger)GET_BTC_BLOCKCHAIN_BEST_CHAIN_HEIGHT.decodeResult(decodeHex((String)result.get("result")))[0]).longValue();
    }

    public boolean isPaicoinTxHashAlreadyProcessed(Sha256Hash txHash) throws IOException, ParseException {
        JSONObject result = call(IS_BTC_TX_HASH_ALREADY_PROCESSED.encode(txHash.toString()));
        return (Boolean)IS_BTC_TX_HASH_ALREADY_PROCESSED.decodeResult(decodeHex((String)result.get("result")))[0];
    }

    public co.rsk.bitcoinj.core.Coin getMinimumLockTxValue() throws IOException, ParseException {
        JSONObject result = call(GET_MINIMUM_LOCK_TX_VALUE.encode());
        return co.rsk.bitcoinj.core.Coin.valueOf(((BigInteger)GET_MINIMUM_LOCK_TX_VALUE.decodeResult(decodeHex((String)result.get("result")))[0]).longValue());
    }

    public String getFederationAddress() throws IOException, ParseException {
        JSONObject result = call(GET_FEDERATION_ADDRESS.encode());
        return (String)GET_FEDERATION_ADDRESS.decodeResult(decodeHex((String)result.get("result")))[0];
    }

    public long getBlocksCount() throws IOException, ParseException {
        JSONObject result = doRequest("eth_blockNumber", null);
        return NumberUtils.createBigInteger((String)result.get("result")).longValue();
    }

    public RskAddress importPaicoinAccount(BtcECKey key, String passPhrase) throws IOException, ParseException {
        JSONObject result = doRequest("personal_importRawKey", JsonArrayBuilder.create()
            .add(key.getPrivKeyBytes())
            .add(passPhrase)
            .build());
        return new RskAddress((String)result.get("result"));
    }

    public void unlockAccount(RskAddress address, String passPhrase, Duration duration) throws IOException, ParseException {
        JSONObject result = doRequest("personal_unlockAccount", JsonArrayBuilder.create()
            .add(address)
            .add(passPhrase)
            .add(duration)
            .build());
        LOGGER.debug("unlock account: {}", result.toJSONString());
    }

    private static JSONArray toJsonArray(DataWord word) {
        JSONArray jsonArray = new JSONArray();
        jsonArray.add(word.toString());
        return jsonArray;
    }

    public String newFilter(DataWord word) throws IOException, ParseException {
        JSONObject result = doRequest("eth_newFilter", JsonArrayBuilder.create()
            .add(JsonObjectBuilder.create()
                .set("address", PrecompiledContracts.BRIDGE_ADDR)
                .set("topics", toJsonArray(word))
                .build())
            .build());

        LOGGER.debug("set topic filter: {}", result.toJSONString());
        return (String)result.get("result");
    }

    public String newPendingTransactionFilter() throws IOException, ParseException {
        JSONObject result = doRequest("eth_newPendingTransactionFilter", null);
        LOGGER.debug("set pending transaction filter: {}", result.toJSONString());
        return (String)result.get("result");
    }

    public boolean uninstallFilter(String filterId) throws IOException, ParseException {
        JSONObject result = doRequest("eth_uninstallFilter", JsonArrayBuilder.create()
            .add(filterId)
            .build());
        LOGGER.debug("uninstall filter: {}", result.toJSONString());
        return (Boolean)result.get("result");
    }

    private JSONArray getFilterChanges(String filterId) throws IOException, ParseException {
        JSONObject result = doRequest("eth_getFilterChanges", JsonArrayBuilder.create()
            .add(filterId)
            .build());
        return (JSONArray)result.get("result");
    }

    public List<BtcTransaction> checkReleasePaicoinsTransactions(String filterId, NetworkParameters networkParameters) throws IOException, ParseException {
        JSONArray result = getFilterChanges(filterId);
        if (CollectionUtils.isEmpty(result))
            return Collections.EMPTY_LIST;
        List<BtcTransaction> transactions = new ArrayList<>(result.size());
        for (int i = 0; i < result.size(); ++i) {
            JSONObject jsonObject = (JSONObject)result.get(i);
            RLPList rlpList = (RLPList)RLP.decode2(decodeHex((String)jsonObject.get("data"))).get(0);
            transactions.add(new BtcTransaction(networkParameters, rlpList.get(1).getRLPData()));
        }
        return transactions;
    }

    public List<RskAddress> checkUpdateCollectionsRequests(String filterId) throws IOException, ParseException {
        JSONArray result = getFilterChanges(filterId);
        if (CollectionUtils.isEmpty(result))
            return Collections.EMPTY_LIST;
        List<RskAddress> addresses = new ArrayList<>(result.size());
        for (int i = 0; i < result.size(); ++i) {
            JSONObject jsonObject = (JSONObject)result.get(i);
            addresses.add(new RskAddress(RLP.decode2(decodeHex((String)jsonObject.get("data"))).get(0).getRLPData()));
        }
        return addresses;
    }

    public List<Keccak256> checkPendingTransactions(String filterId) throws IOException, ParseException {
        JSONArray result = getFilterChanges(filterId);
        if (CollectionUtils.isEmpty(result))
            return Collections.EMPTY_LIST;
        List<Keccak256> addresses = new ArrayList<>(result.size());
        for (int i = 0; i < result.size(); ++i) {
            addresses.add(new Keccak256(decodeHex((String)result.get(i))));
        }
        return addresses;
    }
}
