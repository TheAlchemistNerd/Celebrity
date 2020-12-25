package com.merman.celebrity.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Scanner;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.merman.celebrity.game.Game;
import com.merman.celebrity.game.GameManager;
import com.merman.celebrity.game.Player;
import com.merman.celebrity.game.events.NotifyClientGameEventListener;
import com.merman.celebrity.server.handlers.HttpExchangeUtil;
import com.merman.celebrity.server.logging.Log;
import com.merman.celebrity.server.logging.info.LogInfo;

public class WebsocketHandler {
	private static final byte            MESSAGE_START_BYTE                = (byte) 0x81;                         // -127
	private static final int             MESSAGE_START_BYTE_AS_INT         = 0x81;                                // 129

	private static final int             LENGTH_BYTE_SUBTRACTION_CONSTANT  = 128;
	private static final byte            LENGTH_MAGNITUDE_16_BIT_INDICATOR = 126;
	private static final byte            LENGTH_MAGNITUDE_64_BIT_INDICATOR = 127;
	
	private static final int 			 MAX_LENGTH_16_BITS				   = 65536;
	
	private static final int             CLOSE_CONNECTION_BYTE			   = 0x88;
	private static final int             PING_BYTE						   = 0x89;
	private static final int             PONG_BYTE						   = 0x8A;
	
	private static final String          STOP                              = "__STOP__";
	
	private static AtomicInteger threadCount = new AtomicInteger();
	
	private final Socket socket;
	private volatile boolean started;
	private volatile boolean listen;
	private volatile boolean handshakeCompleted;
	
	private BlockingQueue<String> outgoingQueue = new ArrayBlockingQueue<>(1000, true);
	private Session session;
	
	private Timer pingTimer;
	private final MyInputStreamRunnable inputStreamRunnable = new MyInputStreamRunnable();
	private final MyOutputStreamRunnable outputStreamRunnable = new MyOutputStreamRunnable();
	
	private volatile boolean stoppingSoon;
	
	private class MyInputStreamRunnable
	implements Runnable {

		@Override
		public void run() {
			try {
				handshakeCompleted = performHandshake();
				if ( ! handshakeCompleted ) {
					return;
				}
				InputStream inputStream = socket.getInputStream();
				for ( int nextByte; listen && ( nextByte = inputStream.read() ) != -1; ) {
					long bytesReceived = 1;
					int firstByteOfMessage = nextByte;
					if ( firstByteOfMessage == MESSAGE_START_BYTE_AS_INT
							|| firstByteOfMessage == PONG_BYTE
							|| firstByteOfMessage == CLOSE_CONNECTION_BYTE ) {

						byte[] lengthByteArray = new byte[9];
						nextByte = inputStream.read();
						bytesReceived++;
						
						byte lengthMagnitudeIndicator = (byte) ( nextByte - LENGTH_BYTE_SUBTRACTION_CONSTANT );
						lengthByteArray[0] = lengthMagnitudeIndicator;
						if ( lengthMagnitudeIndicator < 0 ) {
							Log.log(LogInfo.class, String.format( "Error: Illegal magnitude indicator [%d] from byte [%d]", lengthMagnitudeIndicator, lengthByteArray[0] ));
						}
						else {
							if ( lengthMagnitudeIndicator == LENGTH_MAGNITUDE_16_BIT_INDICATOR ) {
								inputStream.read(lengthByteArray, 1, 2);
								bytesReceived += 2;
							}
							else if ( lengthMagnitudeIndicator == LENGTH_MAGNITUDE_64_BIT_INDICATOR ) {
								inputStream.read(lengthByteArray, 1, 8);
								bytesReceived += 8;
							}

							long messageLength = toLength(lengthByteArray);

							if ( messageLength < 0 ) {
								throw new RuntimeException("Negative message length: " + messageLength);
							}
							else {
								byte[] key = { (byte) inputStream.read(), (byte) inputStream.read(), (byte) inputStream.read(), (byte) inputStream.read() };
								bytesReceived += 4;
								
								if ( messageLength == 0 ) {
									if ( firstByteOfMessage == PONG_BYTE ) {
									}
									else {
										Log.log(LogInfo.class, "zero-length message received" );
									}
								}
								else {
									// FIXME can't handle messages whose lengths don't fit into an int
									byte[] encodedMessage = new byte[(int) messageLength];

									// read everything into byte array.
									// FIXME loop without a body! Should prob add some timeouts here if poss
									for ( int totalBytesRead = 0; ( totalBytesRead += inputStream.read(encodedMessage, totalBytesRead, encodedMessage.length - totalBytesRead ) ) < encodedMessage.length; );
									bytesReceived += encodedMessage.length;
									
									byte[] decodedMessage = decode(key, encodedMessage);
									String message;
									if ( firstByteOfMessage == CLOSE_CONNECTION_BYTE ) {
										message = bytesToHex(decodedMessage) + " (encoded as " + bytesToHex(encodedMessage) + ")";
									}
									else {
										message = new String( decodedMessage, StandardCharsets.UTF_8 );
									}

									if ( message.equals("initial-test") ) {
										enqueueMessage("gotcha");
									}
									else {
										Log.log(LogInfo.class, "Message from socket: " + message );
									}
								}
							}
						}
					}
					else {
						stopSoon();
						Log.log(LogInfo.class, "Unexpected byte: " + bytesToHex(nextByte));
					}
					
					CelebrityMain.bytesReceived.accumulateAndGet(bytesReceived, Long::sum);
				}
			}
			catch ( SocketException e ) {
				Log.log(LogInfo.class, String.format("Handler for session [%s] (%s) no longer listening: %s", getSession(), getSession() == null ? null : getSession().getPlayer(), e.getMessage()));
			}
			catch ( IOException e ) {
				e.printStackTrace();
			}
		}
		
	}
	
	private class MyOutputStreamRunnable
	implements Runnable {

		@Override
		public void run() {
			waitForHandshake();
			while ( listen ) {
				try {
					String message 		= outgoingQueue.take();
					if ( STOP.equals(message) ) {
						continue;
					}
					sendMessage(MESSAGE_START_BYTE, message);

				}
				catch ( InterruptedException e ) {
					Log.log(LogInfo.class, String.format("Output handler for session [%s] interrupted", getSession()));
				}
				catch ( SocketException e ) {
					Log.log(LogInfo.class, String.format("Handler for session [%s] (%s) can no longer write: %s", getSession(), getSession().getPlayer(), e.getMessage()));
					try {
						stop();
					}
					catch ( IOException e2 ) {
						e2.printStackTrace();
					}
				}
				catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
		
		synchronized void sendMessage( byte aMessageStartByte, String aMessage ) throws IOException {
			byte[] messageBytes = aMessage.getBytes(StandardCharsets.UTF_8);
			byte[] lengthArray = toLengthArray(messageBytes.length);

			byte[] frame = new byte[messageBytes.length + lengthArray.length + 1];
			frame[0] = aMessageStartByte;
			System.arraycopy(lengthArray, 0, frame, 1, lengthArray.length);
			System.arraycopy(messageBytes, 0, frame, lengthArray.length + 1, messageBytes.length);

			socket.getOutputStream().write(frame);
			CelebrityMain.bytesSent.accumulateAndGet(frame.length, Long::sum);
		}
	}
	
	private class MyPingTimerTask
	extends TimerTask {

		@Override
		public void run() {
			waitForHandshake();
			if ( listen ) {
				try {
					outputStreamRunnable.sendMessage((byte) PING_BYTE, "");
					CelebrityMain.bytesSent.incrementAndGet();
				}
				catch ( SocketException e ) {
					Log.log(LogInfo.class, String.format("Handler for session [%s] (%s) can no longer write: %s", getSession(), getSession().getPlayer(), e.getMessage()));
					try {
						stop();
					}
					catch ( IOException e2 ) {
						e2.printStackTrace();
					}
				}
				catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}
	}
	

	public WebsocketHandler(Socket aSocket) {
		socket = aSocket;
	}
	
	private synchronized void stopSoon() {
		if ( ! stoppingSoon ) {
			stoppingSoon = true;
			new Thread("stopping a websocket handler") {

				@Override
				public void run() {
					try {
						Thread.sleep(2000);
						WebsocketHandler.this.stop();
					}
					catch ( Exception e ) {
						e.printStackTrace();
					}
				}
				
			}.start();
		}
	}

	private long toLength(byte[] aLengthByteArray) {
		long length = 0;
		int lengthMagnitudeIndicator = aLengthByteArray[0];
		if ( lengthMagnitudeIndicator < 0 ) {
			throw new IllegalArgumentException("Illegal magnitude indicator: " + aLengthByteArray[0]);
		}
		else if ( lengthMagnitudeIndicator < LENGTH_MAGNITUDE_16_BIT_INDICATOR ) {
			length = lengthMagnitudeIndicator;
		}
		else if ( lengthMagnitudeIndicator == LENGTH_MAGNITUDE_16_BIT_INDICATOR ) {
			int byteOneAsInt = aLengthByteArray[1];
			int byteTwoAsInt = aLengthByteArray[2];
			
			if ( byteOneAsInt < 0 ) {
				byteOneAsInt = 256 + byteOneAsInt;
			}
			if ( byteTwoAsInt < 0 ) {
				byteTwoAsInt = 256 + byteTwoAsInt;
			}
			length = ( ( byteOneAsInt << 8 ) | byteTwoAsInt );
		}
		else {
			assert aLengthByteArray[0] == LENGTH_MAGNITUDE_64_BIT_INDICATOR;
			
			for (int i = 1; i < 9; i++) {
				int shiftAmount = 8 * (8-i);
				long byteAsLong = aLengthByteArray[i];
				if ( byteAsLong < 0 ) {
					byteAsLong = 256 + byteAsLong;
				}
				length |= ( byteAsLong << shiftAmount );
			}
		}
		return length;
	}
	
	public static byte[] toLengthArray(long aLength) {
		byte[] lengthArray;
		if ( aLength < LENGTH_MAGNITUDE_16_BIT_INDICATOR ) {
			lengthArray = new byte[1];
			lengthArray[0] = (byte) aLength;
		}
		else if ( aLength < MAX_LENGTH_16_BITS ) {
			lengthArray = new byte[3];
			lengthArray[0] = LENGTH_MAGNITUDE_16_BIT_INDICATOR;
			lengthArray[1] = (byte) (aLength >> 8);
			lengthArray[2] = (byte) (aLength & 255);
		}
		else {
			lengthArray = new byte[9];
			lengthArray[0] = LENGTH_MAGNITUDE_64_BIT_INDICATOR;
			for (int i = 1; i < 9; i++) {
				int shiftAmount = 8 * (8-i);
				lengthArray[i] = (byte) (aLength >> shiftAmount);
			}
		}
		
		return lengthArray;
	}

	public synchronized void start() throws IOException {
		if ( started ) {
			throw new IllegalStateException("Already started");
		}
		started = true;
		listen = true;

		int threadNumber = threadCount.incrementAndGet();
		new Thread(inputStreamRunnable,  "Websocket-InputStream-"  + threadNumber).start();
		new Thread(outputStreamRunnable, "Websocket-OutputStream-" + threadNumber).start();

		pingTimer = new Timer("ping-timer-" + threadNumber);
		pingTimer.schedule(new MyPingTimerTask(), 5000, 10000);
	}

	private void waitForHandshake() {
		while ( ! handshakeCompleted
				&& listen ) {
			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
			}
		}
	}

	public synchronized void stop() throws IOException {
		listen = false;
		if ( socket != null ) {
			socket.close();
		}
		if ( pingTimer != null ) {
			pingTimer.cancel();
			pingTimer = null;
		}
		
		Session session = getSession();
		
		if (session != null) {
			enqueueMessage(STOP);
		}
		Player  player  = session == null ? null : session.getPlayer();
		Game    game    = player == null ? null : player.getGame();
		if ( game != null ) {
			game.removeAllGameEventListeners(this);
		}
	}

	private boolean performHandshake() throws IOException {
		InputStream inputStream = socket.getInputStream();
		OutputStream outputStream = socket.getOutputStream();

		try {
			// Don't close this scanner, doing so will close ths socket
			Scanner scanner = new Scanner(inputStream, StandardCharsets.UTF_8.name());
			String data = scanner.useDelimiter("\\r\\n\\r\\n").next();
			
//			System.out.println("\n=== Received incoming websocket request ===\n");
//			System.out.println(data);
			
			CelebrityMain.bytesReceived.accumulateAndGet(data.getBytes().length, Long::sum);
			
			Matcher get = Pattern.compile("^GET").matcher(data);
			if (get.find()) {
				Matcher cookieMatcher = Pattern.compile("Cookie: (.*)").matcher(data);
				String sessionID = null;
				if (cookieMatcher.find()) {
					String cookieString = cookieMatcher.group(1);
					HashMap<String, String> cookie = new HashMap<>();
					HttpExchangeUtil.parseCookie(cookieString, cookie);
					
					sessionID = cookie.get("session");
					if (sessionID != null) {
						session = SessionManager.getSession(sessionID);
					}
					
					

					if (session != null) {
						String restoreString = cookie.get(HttpExchangeUtil.COOKIE_RESTORE_KEY);
						if (HttpExchangeUtil.COOKIE_RESTORE_VALUE.equals(restoreString)) {
							Player player = session.getPlayer();
							Game game = player == null ? null : player.getGame();
							if (game != null) {
								enqueueMessage("GameState=" + GameManager.serialise(game, session.getSessionID(), true));
								
								game.addGameEventListener(new NotifyClientGameEventListener(WebsocketHandler.this));
							}
						}
					}
				}
				
				if (session == null) {
					Log.log(LogInfo.class, String.format( "Unknown session ID [%s], refusing handshake", sessionID));
					// Don't recognise this session, refuse this websocket
					stop();
					return false;
				}

				
				Matcher keyMatcher = Pattern.compile("Sec-WebSocket-Key: (.*)").matcher(data);
				keyMatcher.find();
				byte[] response;
				response = ("HTTP/1.1 101 Switching Protocols\r\n"
						+ "Connection: Upgrade\r\n"
						+ "Upgrade: websocket\r\n"
						+ "Sec-WebSocket-Accept: "
						+ Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((keyMatcher.group(1) + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.UTF_8)))
						+ "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
				outputStream.write(response, 0, response.length);
				
				CelebrityMain.bytesSent.accumulateAndGet(data.getBytes().length, Long::sum);
				
				SessionManager.putSocket( session, WebsocketHandler.this );
				Log.log(LogInfo.class, String.format( "Opened websocket with session %s [%s] from IP %s", session.getSessionID(), session.getPlayer(), socket.getRemoteSocketAddress() ));

				return true;
			}
		}
		catch ( Exception e ) {
			Log.log(LogInfo.class, "Exception during websocket handshake", e);
		}
		Log.log(LogInfo.class, "Websocket handshake failed. IP", socket.getInetAddress());
		stop();
		return false;
	}
	
	private static byte[] decode(byte[] aKey, byte[] aEncodedMessage) {
		byte[] decodedMessage = new byte[aEncodedMessage.length];
		for (int i = 0; i < aEncodedMessage.length; i++) {
			decodedMessage[i] = (byte) (aEncodedMessage[i] ^ aKey[i & 0x3]);
		}
		return decodedMessage;
	}
	
	public void enqueueMessage(String aMessage) {
		try {
			synchronized (outgoingQueue) {
				outgoingQueue.put(aMessage);
			}
		}
		catch ( InterruptedException e ) {
			e.printStackTrace();
		}
	}

	public Session getSession() {
		return session;
	}
	
	private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();
	public static String bytesToHex(byte[] bytes) {
	    char[] hexChars = new char[bytes.length * 2];
	    for (int j = 0; j < bytes.length; j++) {
	        int v = bytes[j] & 0xFF;
	        hexChars[j * 2] = HEX_ARRAY[v >>> 4];
	        hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
	    }
	    return new String(hexChars);
	}
	
	public static String bytesToHex(int aByte) {
		return bytesToHex((byte) (aByte & 0xFF ));
	}
	
	public static String bytesToHex(byte aByte) {
		char[] hexChars = new char[2];
		int v = aByte & 0xFF;
		hexChars[0] = HEX_ARRAY[v >>> 4];
		hexChars[1] = HEX_ARRAY[v & 0x0F];
		return new String(hexChars);
	}
}
