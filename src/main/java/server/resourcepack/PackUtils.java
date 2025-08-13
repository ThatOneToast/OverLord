package server.resourcepack;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class PackUtils {

    public static String calcSHA1String(File file)  {
        try {
            return bytesToHexString(calcSHA1(file));
        } catch (IOException | NoSuchAlgorithmException e) {throw new RuntimeException(e);}
    }

    public static String getIp()  {
        String globalIp;
        try {
            URL url = URI.create("https://api.ipify.org").toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            globalIp = in.readLine();
            in.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return globalIp;
    }

    private static byte[] calcSHA1(File file) throws IOException, NoSuchAlgorithmException {
        try (FileInputStream fileInputStream = new FileInputStream(file);
             DigestInputStream digestInputStream = new DigestInputStream(fileInputStream, MessageDigest.getInstance("SHA-1"))) {
            byte[] bytes = new byte[1024];
            while (digestInputStream.read(bytes) > 0);
            return digestInputStream.getMessageDigest().digest();
        }
    }

    private static String bytesToHexString(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            int value = b & 0xFF;
            if (value < 16) sb.append("0");
            sb.append(Integer.toHexString(value).toUpperCase());
        }
        return sb.toString();
    }
}