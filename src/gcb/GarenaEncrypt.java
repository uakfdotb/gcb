/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package gcb;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Security;
import java.util.Enumeration;
import java.util.Random;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMReader;

/**
 *
 * @author wizardus
 */
public class GarenaEncrypt {
	SecretKey skey;
	SecretKeySpec skey_spec;
	byte[] iv;
	IvParameterSpec iv_spec;

	KeyPair rsaKey;

	Random random;

	public GarenaEncrypt() {
		random = new Random();
	}

	public void initAES() {
		Main.println("[GEncrypt] Initializing AES Keys...");
		KeyGenerator kgen = null;
		
		try {
			kgen = KeyGenerator.getInstance("AES");
		} catch(NoSuchAlgorithmException nsae) {
			nsae.printStackTrace();
		}
		
		kgen.init(256);

		// Generate the secret key specs.
		skey = kgen.generateKey();
		byte[] raw = skey.getEncoded();

		skey_spec = new SecretKeySpec(raw, "AES");
		
		iv = new byte[16];
		random.nextBytes(iv);
		iv_spec = new IvParameterSpec(iv);
	}

	public void initAES(byte[] raw, byte[] iv) {
		this.iv = iv;
		skey_spec = new SecretKeySpec(raw, "AES");
		iv_spec = new IvParameterSpec(iv);
	}

	public void initRSA() {
		Main.println("[GEncrypt] Initializing RSA Keys...");
		BouncyCastleProvider bcp = new BouncyCastleProvider();
		Security.addProvider(bcp);

		Main.println("[GEncrypt] Reading private key in PEM format...");
		try {
			PEMReader pemreader = new PEMReader(new FileReader("gkey.pem"));
			rsaKey = (KeyPair) pemreader.readObject();
		} catch(IOException ioe) {
			ioe.printStackTrace();
		}
	}

	public byte[] rsaEncryptPrivate(byte[] data) throws Exception {
		Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
		cipher.init(Cipher.ENCRYPT_MODE, rsaKey.getPrivate());
		return cipher.doFinal(data);
	}

	public byte[] rsaDecryptPrivate(byte[] data) throws Exception {
		Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
		cipher.init(Cipher.DECRYPT_MODE, rsaKey.getPublic());
		return cipher.doFinal(data);
	}

	public byte[] aesDecrypt(byte[] data) throws Exception {
		Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
		cipher.init(Cipher.DECRYPT_MODE, skey_spec, iv_spec);

		return cipher.doFinal(data);
	}

	public byte[] aesEncrypt(byte[] data) throws Exception {
		Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
		cipher.init(Cipher.ENCRYPT_MODE, skey_spec, iv_spec);

		return cipher.doFinal(data);
	}

	public String md5(String in) {
		byte[] data = in.getBytes();
		
		try {
			MessageDigest md = MessageDigest.getInstance("MD5");
			return hexEncode(md.digest(data));
		} catch(NoSuchAlgorithmException e) {
			e.printStackTrace();
			return null;
		}
	}

	//code from http://www.exampledepot.com/egs/java.util.zip/CompArray.html
	public byte[] deflate(byte[] input) {
		// Create the compressor
		Deflater compressor = new Deflater();

		// Give the compressor the data to compress
		compressor.setInput(input);
		compressor.finish();

		// Create an expandable byte array to hold the compressed data.
		// You cannot use an array that's the same size as the orginal because
		// there is no guarantee that the compressed data will be smaller than
		// the uncompressed data.
		ByteArrayOutputStream bos = new ByteArrayOutputStream(input.length);

		// Compress the data
		byte[] buf = new byte[1024];
		while (!compressor.finished()) {
			int count = compressor.deflate(buf);
			bos.write(buf, 0, count);
		}
		try {
			bos.close();
		} catch (IOException e) {
		}

		// Get the compressed data
		return bos.toByteArray();
	}

	public byte[] expand(byte[] input) {
		// Create the decompressor and give it the data to compress
		Inflater decompressor = new Inflater();
		decompressor.setInput(input);

		// Create an expandable byte array to hold the decompressed data
		ByteArrayOutputStream bos = new ByteArrayOutputStream(input.length);

		// Decompress the data
		byte[] buf = new byte[1024];
		while (!decompressor.finished()) {
			try {
				int count = decompressor.inflate(buf);
				bos.write(buf, 0, count);
			} catch (DataFormatException e) {
			}
		}
		try {
			bos.close();
		} catch (IOException e) {
		}

		// Get the decompressed data
		return bos.toByteArray();
	}

	public String strFromBytes(byte[] input) {
		return strFromBytes(input, "UTF-8");
	}

	public String strFromBytes(byte[] input, String charset) {
		//find null byte
		int null_index = input.length;
		for(int i = 0; i < input.length; i++) {
			if(input[i] == 0) {
				null_index = i;
				break;
			}
		}

		try {
		return new String(input, 0, null_index, charset);
		} catch(UnsupportedEncodingException e) {
			Main.println("[GEncrypt] " + charset + " is unsupported: " + e.getLocalizedMessage());
			return null;
		}
	}

	public String strFromBytes16(byte[] input) throws IOException {
		//find null byte
		int null_index = input.length;
		for(int i = 0; i < input.length - 1; i+=2) {
			if(input[i] == 0 && input[i + 1] == 0) {
				null_index = i;
				break;
			}
		}

		try {
			return new String(input, 0, null_index, "UnicodeLittleUnmarked");
		} catch(UnsupportedEncodingException e) {
			Main.println("[GEncrypt] UnicodeLittleUnmarked is unsupported: " + e.getLocalizedMessage());
			return null;
		}
	}

	public static String cleanString(String string) {
		byte[] input = string.getBytes();

		for(int i = 0; i < input.length; i++) {
			if(input[i] < 32 || input[i] > 126) {
				//bad character, probably for formatting that we don't want
				input[i] = 46; //period
			}
		}
		
		return new String(input);
	}

	/**
	 * Convert the byte array to an int.
	 *
	 * @param b The byte array
	 * @return The integer
	 */
	public static int byteArrayToInt(byte[] b) {
		return byteArrayToInt(b, 0);
	}

	/**
	 * Convert the byte array to an int starting from the given offset.
	 *
	 * @param b The byte array
	 * @param offset The array offset
	 * @return The integer
	 */
	public static int byteArrayToInt(byte[] b, int offset) {
		int value = 0;
		for (int i = 0; i < 4; i++) {
			int shift = (4 - 1 - i) * 8;
			value += (b[i + offset] & 0x000000FF) << shift;
		}
		return value;
	}

	public static int byteArrayToIntLength(byte[] b, int offset, int length) {
		int value = 0;
		for (int i = 0; i < length; i++) {
			int shift = (length - 1 - i) * 8;
			value += (b[i + offset] & 0x000000FF) << shift;
		}
		return value;
	}

	public static int byteArrayToIntLittle(byte[] b, int offset) {
		int value = 0;
		for (int i = 3; i >= 0; i--) {
			int shift = i * 8;
			value += (b[i + offset] & 0x000000FF) << shift;
		}
		return value;
	}

	public static int byteArrayToIntLittleLength(byte[] b, int offset, int length) {
		int value = 0;
		for (int i = 0; i < length; i++) {
			int shift = i * 8;
			value += (b[i + offset] & 0x000000FF) << shift;
		}
		return value;
	}
	
	public static short readShort(byte[] data, int offset) {
	return (short) (((data[offset] << 8)) | ((data[offset + 1] & 0xff)));
	}

	public static byte[] shortToByteArray(short s) {
		return new byte[] { (byte) ((s & 0xFF00) >> 8), (byte) (s & 0x00FF) };
	}

	public static int unsignedShort(short s) {
		byte[] array = shortToByteArray(s);
		int b1 = (0x000000FF & ((int) array[0]));
		int b2 = (0x000000FF & ((int) array[1]));
	return (b1 << 8 | b2);
	}

	public static int unsignedByte(byte b) {
		return (0x000000FF & ((int)b));
	}

	public static String hexEncode(byte[] input) {
		if (input == null || input.length == 0)
		{
			return "";
		}

		int inputLength = input.length;
		StringBuilder output = new StringBuilder(inputLength * 2);

		for (int i = 0; i < inputLength; i++)
		{
			int next = input[i] & 0xff;
			if (next < 0x10)
			{
				output.append("0");
			}

			output.append(Integer.toHexString(next));
		}

		return output.toString();
	}

	public static byte[] hexEncode2(String first) {
		byte[] ret = new byte[32];

		//do some random stuff
		for(int i = 0; i < first.length() && i < 32; i++) {
			if(Character.isDigit(first.charAt(i))) {
				ret[i] = (byte) (first.charAt(i) - 18);
			} else {
				ret[i] = (byte) (first.charAt(i) - 36);
			}
		}

		return ret;
	}

	public static byte[] externalAddress() {
		try {
			URL whatismyip = new URL("http://checkip.dyndns.com/");
			BufferedReader in = new BufferedReader(new InputStreamReader(whatismyip.openStream()));

			String[] parts = in.readLine().split("<");

			InetAddress ip = InetAddress.getByName(parts[6].substring(25));

			Main.println("[GEncrypt] External IP address determined at " + ip.getHostAddress());

			return ip.getAddress();
		} catch(IOException ioe) {
			ioe.printStackTrace();
			return null;
		}
	}

	public static byte[] internalAddress() {
		try {
			InetAddress local_address = getFirstNonLoopbackAddress(true, false);
			Main.println("[GEncrypt] Internal IP address determined at " + local_address.getHostAddress());
			return local_address.getAddress();
		} catch(IOException ioe) {
			ioe.printStackTrace();
			return null;
		}
	}
	
	private static InetAddress getFirstNonLoopbackAddress(boolean preferIpv4, boolean preferIPv6) throws SocketException {
		Enumeration en = NetworkInterface.getNetworkInterfaces();
		while (en.hasMoreElements()) {
			NetworkInterface i = (NetworkInterface) en.nextElement();
			for (Enumeration en2 = i.getInetAddresses(); en2.hasMoreElements();) {
				InetAddress addr = (InetAddress) en2.nextElement();
				if (!addr.isLoopbackAddress()) {
					if (addr instanceof Inet4Address) {
						if (preferIPv6) {
							continue;
						}
						return addr;
					}
					if (addr instanceof Inet6Address) {
						if (preferIpv4) {
							continue;
						}
						return addr;
					}
				}
			}
		}
		return null;
	}

	//expensive but most correct integer checking
	public static boolean isInteger(String string) {
		try {
			Integer.valueOf(string);
			return true;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	public static String getTerminatedString(ByteBuffer buf) {
		return new String(getTerminatedArray(buf));
	}

	public static byte[] getTerminatedArray(ByteBuffer buf) {
		int start = buf.position();

		while(buf.get() != 0) {}
		int end = buf.position();

		byte[] bytes = new byte[end - start - 1]; //don't include terminator
		buf.position(start);
		buf.get(bytes);

		//put position after array
		buf.position(end); //skip terminator

		return bytes;
	}
}
