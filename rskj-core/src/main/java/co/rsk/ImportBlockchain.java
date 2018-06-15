package co.rsk;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;

import com.squareup.okhttp.Credentials;
import com.squareup.okhttp.HttpUrl;
import com.squareup.okhttp.MediaType;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.RequestBody;
import com.squareup.okhttp.Response;
import org.ethereum.rpc.TypeConverter;
import org.ethereum.vm.PrecompiledContracts;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.spongycastle.util.encoders.Hex;

import static co.rsk.peg.Bridge.RECEIVE_HEADERS;


public class ImportBlockchain {

    private final static Logger LOGGER = LoggerFactory.getLogger(ImportBlockchain.class);
    private final static MediaType MEDIA_TYPE = MediaType.parse("application/json");
    private final static JSONParser JSON_PARSER = new JSONParser();

    private final HttpUrl paicoinUrl;
    private final HttpUrl rskUrl;

    private final OkHttpClient httpClient;

    public ImportBlockchain(String paicoinUrl, String rskUrl) throws Exception {
        this.paicoinUrl = HttpUrl.parse(paicoinUrl);
        this.rskUrl = HttpUrl.parse(rskUrl);
        this.httpClient = createHttpClient();
        this.httpClient.setConnectTimeout(60, TimeUnit.SECONDS);
        this.httpClient.setReadTimeout(60, TimeUnit.SECONDS);
        this.httpClient.setWriteTimeout(60, TimeUnit.SECONDS);
    }

    public long getBlockCount() throws IOException, ParseException {
        JSONObject json = new JSONObject();
        json.put("jsonrpc", "2.0");
        json.put("id", 1);
        json.put("method", "getblockcount");
        json.put("params", new JSONArray());
        RequestBody requestBody = RequestBody.create(MEDIA_TYPE, json.toJSONString());
        Request request = new Request.Builder()
            .url(paicoinUrl)
            .header("Authorization", Credentials.basic(paicoinUrl.username(), paicoinUrl.password()))
            .post(requestBody)
            .build();
        Response response = httpClient.newCall(request).execute();
        return (long)((JSONObject)JSON_PARSER.parse(response.body().string())).get("result");
    }

    public String getBlockHash(long idx) throws IOException, ParseException {
        JSONObject json = new JSONObject();
        json.put("jsonrpc", "2.0");
        json.put("id", 1);
        json.put("method", "getblockhash");
        JSONArray params = new JSONArray();
        params.add(idx);
        json.put("params", params);
        RequestBody requestBody = RequestBody.create(MEDIA_TYPE, json.toJSONString());
        Request request = new Request.Builder()
            .url(paicoinUrl)
            .header("Authorization", Credentials.basic(paicoinUrl.username(), paicoinUrl.password()))
            .post(requestBody)
            .build();
        Response response = httpClient.newCall(request).execute();
        return (String)((JSONObject)JSON_PARSER.parse(response.body().string())).get("result");
    }

    public String getBlock(String blockHash) throws IOException, ParseException {
        JSONObject json = new JSONObject();
        json.put("jsonrpc", "2.0");
        json.put("id", 1);
        json.put("method", "getblock");
        JSONArray params = new JSONArray();
        params.add(blockHash);
        params.add(0);
        json.put("params", params);
        Request request = new Request.Builder()
            .url(paicoinUrl)
            .header("Authorization", Credentials.basic(paicoinUrl.username(), paicoinUrl.password()))
            .post(RequestBody.create(MEDIA_TYPE, json.toJSONString()))
            .build();
        Response response = httpClient.newCall(request).execute();
        return (String)((JSONObject)JSON_PARSER.parse(response.body().string())).get("result");
    }

    public void receiveHeaders(byte[][] headers) throws IOException, ParseException {
        JSONObject json = new JSONObject();
        json.put("jsonrpc", "2.0");
        json.put("id", 1);
        json.put("method", "eth_call");
        JSONObject param = new JSONObject();
        param.put("data", Hex.toHexString(RECEIVE_HEADERS.encode(new Object[] { headers })));
        param.put("to", TypeConverter.toJsonHex(PrecompiledContracts.BRIDGE_ADDR_STR));
        JSONArray params = new JSONArray();
        params.add(param);
        params.add("latest");
        json.put("params", params);
        Request request = new Request.Builder()
            .url(rskUrl)
            .post(RequestBody.create(MEDIA_TYPE, json.toJSONString()))
            .build();
        Response response = httpClient.newCall(request).execute();
        LOGGER.debug("Received header: {}", ((JSONObject)JSON_PARSER.parse(response.body().string())).toJSONString());
    }

    private static byte[] decodeHex(String data) {
        int len = data.length();
        if ((len & 0x01) != 0)
            throw new RuntimeException("Odd number of characters.");
        byte[] out = new byte[len >> 1];
        for (int i = 0, j = 0; j < len; i++) {
            int f = toDigit(data.charAt(j), j) << 4;
            j++;
            f = f | toDigit(data.charAt(j), j);
            j++;
            out[i] = (byte)(f & 0xFF);
        }
        return out;
    }

    private static String encodeHex(byte[] data) {
        int length = data.length;
        StringBuilder sb = new StringBuilder(length << 1);
        for (byte n : data)
            sb.append(Character.forDigit((0xF0 & n) >>> 4, 16)).append(Character.forDigit(0x0F & n, 16));
        return sb.toString();
    }

    private static int toDigit(char ch, int index) {
        int digit = Character.digit(ch, 16);
        if (digit == -1)
            throw new RuntimeException("Illegal hexadecimal character '" + ch + "' at index " + index);
        return digit;
    }

    private static OkHttpClient createHttpClient() throws GeneralSecurityException {
        // Create a trust manager that does not validate certificate chains
        SSLContext sslContext = SSLContext.getInstance("SSL");
        sslContext.init(null, new TrustManager[] { new X509TrustManager() }, new SecureRandom());
        return new OkHttpClient()
            .setSslSocketFactory(sslContext.getSocketFactory())
            .setHostnameVerifier((hostname, session) -> true);
    }

    public static void main(String[] args) {
        try {
            ImportBlockchain importBlockchain = new ImportBlockchain(System.getProperty("paicoin.rpcurl"), System.getProperty("rsk.rpcurl"));
            long blockCount = importBlockchain.getBlockCount();
            for (long i = 1; i <= blockCount; ++i) {
                String hash = importBlockchain.getBlockHash(i);
                importBlockchain.receiveHeaders(new byte[][] { TypeConverter.stringHexToByteArray(importBlockchain.getBlock(hash)) });
            }
        } catch (Throwable t) {
            LOGGER.error("Import error", t);
        }
    }

    private static class X509TrustManager implements javax.net.ssl.X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {

        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {

        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
