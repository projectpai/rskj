package co.rsk.paicoin;

import java.io.IOException;
import java.security.GeneralSecurityException;

import com.squareup.okhttp.Credentials;
import com.squareup.okhttp.HttpUrl;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.RequestBody;
import com.squareup.okhttp.Response;
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
import static co.rsk.paicoin.ImportUtils.encodeHex;


public class PaicoinClient {

    private final static Logger LOGGER = LoggerFactory.getLogger("paicoin-client");

    private final static JSONParser JSON_PARSER = new JSONParser();

    private final OkHttpClient httpClient;
    private final Request.Builder request;

    public PaicoinClient(String url, String username, String password) throws GeneralSecurityException {
        httpClient = createHttpClient();
        request = new Request.Builder().url(HttpUrl.parse(url)).header("Authorization", Credentials.basic(username, password));
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

    private static boolean hasError(String message, JSONObject result) {
        Object error = result.get("error");
        if (error == null)
            return false;
        LOGGER.error("{}: {}", message, ((JSONObject)error).get("message"));
        return true;
    }

    public long getBlockCount() throws IOException, ParseException {
        JSONObject result = doRequest("getblockcount", null);
        if (hasError("getBlockCount", result))
            return -1;
        return (Long)result.get("result");
    }

    public String getBlockHash(long idx) throws IOException, ParseException {
        JSONObject result = doRequest("getblockhash", JsonArrayBuilder.create()
            .add(idx)
            .build());
        if (hasError("getBlockHash", result))
            return null;
        return (String)result.get("result");
    }

    public byte[] getBlock(String blockHash) throws IOException, ParseException {
        JSONObject result = doRequest("getblock", JsonArrayBuilder.create()
            .add(blockHash)
            .add(0)
            .build());
        if (hasError("getBinaryBlock", result))
            return null;
        return decodeHex((String)result.get("result"));
    }

    public JSONObject getTransaction(String txHash, boolean verbose) throws IOException, ParseException {
        JSONObject result = doRequest("getrawtransaction", JsonArrayBuilder.create()
            .add(txHash)
            .add(verbose)
            .build());
        if (hasError("getTransaction", result))
            return null;
        return (JSONObject)result.get("result");
    }

    public byte[] sendTransaction(byte[] txHex) throws IOException, ParseException {
        JSONObject result = doRequest("sendrawtransaction", JsonArrayBuilder.create()
            .add(encodeHex(txHex))
            .build());
        if (hasError("sendTransaction", result))
            return null;
        return decodeHex((String)result.get("result"));
    }
}
