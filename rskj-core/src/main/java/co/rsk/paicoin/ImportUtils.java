package co.rsk.paicoin;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;

import co.rsk.core.Coin;
import co.rsk.core.RskAddress;
import com.squareup.okhttp.MediaType;
import com.squareup.okhttp.OkHttpClient;
import org.apache.commons.lang3.builder.Builder;
import org.ethereum.vm.DataWord;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;


public final class ImportUtils {

    public final static MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json");

    public static <T> int size(List<T> list) {
        return (list != null ? list.size() : 0);
    }

    public static <T> T first(List<T> list) {
        int size = size(list);
        return (size > 0 ? list.get(0) : null);
    }

    public static <T> T last(List<T> list) {
        int size = size(list);
        return (size > 0 ? list.get(size - 1) : null);
    }

    public static co.rsk.bitcoinj.core.Coin round(co.rsk.bitcoinj.core.Coin coins, int multiple) {
        long value = coins.getValue();
        long n = multiple * co.rsk.bitcoinj.core.Coin.COIN.getValue();
        long rem = value % n;
        if (rem == 0)
            return coins;
        return co.rsk.bitcoinj.core.Coin.valueOf(value + (n - rem));
    }

    public static byte[] decodeHex(String strHex) {
        int length = strHex.length();
        int j = 0;
        if (strHex.startsWith("0x")) {
            j += 2;
            length -= 2;
        }
        int bytesCount = ((length & 0x01) != 0 ? length + 1 : length) >>> 1;
        byte[] result = new byte[bytesCount];
        int i = 0;
        if ((length & 0x01) != 0) {
            result[i] = (byte)toDigit(strHex.charAt(j), j);
            ++j;
            ++i;
        }
        while (i < bytesCount) {
            int n = toDigit(strHex.charAt(j), j) << 4;
            ++j;
            n |= toDigit(strHex.charAt(j), j);
            ++j;
            result[i] = (byte)(n & 0xFF);
            ++i;
        }
        return result;
    }

    public static String encodeHex(byte[] data) {
        if (data == null || data.length < 1)
            throw new IllegalArgumentException("Empty input data array");
        StringBuilder sb = new StringBuilder((data.length << 1) + 2);
        // sb.append("0x");
        for (byte n : data)
            sb.append(Character.forDigit((0xF0 & n) >>> 4, 16)).append(Character.forDigit(0x0F & n, 16));
        return sb.toString();
    }

    private static int toDigit(char ch, int index) {
        int digit = Character.digit(ch, 16);
        if (digit == -1)
            throw new IllegalArgumentException("Illegal hexadecimal character '" + ch + "' at index " + index);
        return digit;
    }

    public static OkHttpClient createHttpClient() throws GeneralSecurityException {
        // Create a trust manager that does not validate certificate chains
        SSLContext sslContext = SSLContext.getInstance("SSL");
        sslContext.init(null, new TrustManager[] { new X509TrustManager() }, new SecureRandom());
        OkHttpClient httpClient = new OkHttpClient()
            .setSslSocketFactory(sslContext.getSocketFactory())
            .setHostnameVerifier((hostname, session) -> true);
        httpClient.setConnectTimeout(10, TimeUnit.SECONDS);
        httpClient.setReadTimeout(10, TimeUnit.SECONDS);
        httpClient.setWriteTimeout(10, TimeUnit.SECONDS);
        return httpClient;
    }

    private static class X509TrustManager implements javax.net.ssl.X509TrustManager {

        public void checkClientTrusted(X509Certificate[] chain, String authType) {

        }

        public void checkServerTrusted(X509Certificate[] chain, String authType) {

        }

        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    @SuppressWarnings("unchecked")
    public final static class JsonObjectBuilder implements Builder<JSONObject> {

        private final JSONObject jsonObject;

        private JsonObjectBuilder() {
            jsonObject = new JSONObject();
        }

        public static JsonObjectBuilder create() {
            return new JsonObjectBuilder();
        }

        public JsonObjectBuilder set(String name, String value) {
            jsonObject.put(name, value);
            return this;
        }

        public JsonObjectBuilder set(String name, long value) {
            jsonObject.put(name, value);
            return this;
        }

        public JsonObjectBuilder set(String name, boolean value) {
            jsonObject.put(name, value);
            return this;
        }

        public JsonObjectBuilder set(String name, byte[] value) {
            jsonObject.put(name, encodeHex(value));
            return this;
        }

        public JsonObjectBuilder set(String name, Coin value) {
            jsonObject.put(name, value);
            return this;
        }

        public JsonObjectBuilder set(String name, JSONObject value) {
            jsonObject.put(name, value);
            return this;
        }

        public JsonObjectBuilder set(String name, JSONArray value) {
            jsonObject.put(name, value);
            return this;
        }

        public JsonObjectBuilder set(String name, RskAddress value) {
            jsonObject.put(name, encodeHex(value.getBytes()));
            return this;
        }

        public JsonObjectBuilder all(JSONObject value) {
            jsonObject.putAll(value);
            return this;
        }

        public JSONObject build() {
            return jsonObject;
        }
    }

    @SuppressWarnings("unchecked")
    public final static class JsonArrayBuilder implements Builder<JSONArray> {

        private final JSONArray jsonArray;

        private JsonArrayBuilder() {
            jsonArray = new JSONArray();
        }

        public static JsonArrayBuilder create() {
            return new JsonArrayBuilder();
        }

        public JsonArrayBuilder add(String value) {
            jsonArray.add(value);
            return this;
        }

        public JsonArrayBuilder add(long value) {
            jsonArray.add(value);
            return this;
        }

        public JsonArrayBuilder add(boolean value) {
            jsonArray.add(value);
            return this;
        }

        public JsonArrayBuilder add(byte[] value) {
            jsonArray.add(encodeHex(value));
            return this;
        }

        public JsonArrayBuilder add(Duration value) {
            jsonArray.add("0x" + Long.toHexString(value.toMillis()));
            return this;
        }

        public JsonArrayBuilder add(RskAddress value) {
            jsonArray.add(encodeHex(value.getBytes()));
            return this;
        }

        public JsonArrayBuilder add(JSONObject value) {
            jsonArray.add(value);
            return this;
        }

        public JsonArrayBuilder add(JSONArray value) {
            jsonArray.add(value);
            return this;
        }

        public JsonArrayBuilder add(DataWord value) {
            jsonArray.add(value.toString());
            return this;
        }

        public JSONArray build() {
            return jsonArray;
        }
    }
}
