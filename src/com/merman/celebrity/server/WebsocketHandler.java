package com.merman.celebrity.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.merman.celebrity.game.Game;
import com.merman.celebrity.game.GameManager;
import com.merman.celebrity.game.Player;
import com.merman.celebrity.game.events.NotifyClientGameEventListener;
import com.merman.celebrity.server.handlers.HttpExchangeUtil;
import com.merman.celebrity.server.logging.Log;
import com.merman.celebrity.server.logging.LogMessageSubject;
import com.merman.celebrity.server.logging.LogMessageType;
import com.merman.celebrity.util.IntPool;

public class WebsocketHandler {
	private static final byte            MESSAGE_START_BYTE                          = (byte) 0x81;                         // -127
	private static final int             MESSAGE_START_BYTE_AS_INT                   = 0x81;                                // 129

	private static final int             LENGTH_BYTE_SUBTRACTION_CONSTANT            = 128;
	private static final byte            LENGTH_MAGNITUDE_16_BIT_INDICATOR           = 126;
	private static final byte            LENGTH_MAGNITUDE_64_BIT_INDICATOR           = 127;

	private static final int             MAX_LENGTH_16_BITS                          = 65536;

	private static final int             CLOSE_CONNECTION_BYTE                       = 0x88;
	private static final int             PING_BYTE                                   = 0x89;
	private static final int             PONG_BYTE                                   = 0x8A;

	private static final String          STOP                                        = "__STOP__";
	private static final String          PONG     									 = "__PONG__";
	private static final String          CLOSE_CONNECTION_MESSAGE                    = "03E9";

	private static IntPool               sThreadIndexPool                            = new IntPool();
	private static Map<Integer, AtomicInteger> sMapThreadIndicesToNumberOfFinalisedThreads = Collections.synchronizedMap(new HashMap<>());
	
	private final Socket socket;
	private volatile boolean started;
	private volatile boolean listen;
	private volatile boolean handshakeCompleted;
	
	private BlockingQueue<String> outgoingQueue = new ArrayBlockingQueue<>(1000, true);
	private Session session;
	
	private Timer pingTimer;
	private final MyInputStreamRunnable inputStreamRunnable = new MyInputStreamRunnable();
	private final MyOutputStreamRunnable outputStreamRunnable = new MyOutputStreamRunnable();
	private long lastSeenTimeMillis = System.currentTimeMillis();
	
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
							|| firstByteOfMessage == PING_BYTE
							|| firstByteOfMessage == PONG_BYTE
							|| firstByteOfMessage == CLOSE_CONNECTION_BYTE ) {

						byte[] lengthByteArray = new byte[9];
						nextByte = inputStream.read();
						bytesReceived++;
						
						byte lengthMagnitudeIndicator = (byte) ( nextByte - LENGTH_BYTE_SUBTRACTION_CONSTANT );
						lengthByteArray[0] = lengthMagnitudeIndicator;
						if ( lengthMagnitudeIndicator < 0 ) {
							log( LogMessageType.ERROR, LogMessageSubject.GENERAL, "Illegal magnitude indicator", lengthMagnitudeIndicator, "from byte", lengthByteArray[0] );
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
										lastSeenTimeMillis = System.currentTimeMillis();
									}
									else if ( firstByteOfMessage == PING_BYTE ) {
										lastSeenTimeMillis = System.currentTimeMillis();
										log( LogMessageType.DEBUG, LogMessageSubject.GENERAL, "ping received (zero-length message)" );
										enqueueMessage(PONG);
									}
									else {
										log( LogMessageType.INFO, LogMessageSubject.GENERAL, "zero-length message received" );
									}
								}
								else {
									lastSeenTimeMillis = System.currentTimeMillis();

									// FIXME can't handle messages whose lengths don't fit into an int
									byte[] encodedMessage = new byte[(int) messageLength];

									// read everything into byte array.
									// FIXME loop without a body! Should prob add some timeouts here if poss
									for ( int totalBytesRead = 0; ( totalBytesRead += inputStream.read(encodedMessage, totalBytesRead, encodedMessage.length - totalBytesRead ) ) < encodedMessage.length; );
									bytesReceived += encodedMessage.length;
									
									byte[] decodedMessage = decode(key, encodedMessage);
									String message;
									if ( firstByteOfMessage == CLOSE_CONNECTION_BYTE ) {
										message = bytesToHex(decodedMessage);
										if ( CLOSE_CONNECTION_MESSAGE.equals(message)) {
											message = "CLOSE_CONNECTION";
											stop();
										}
									}
									else {
										message = new String( decodedMessage, StandardCharsets.UTF_8 );
									}

									if ( message.equals("initial-test") ) {
										enqueueMessage("gotcha");
									}
									else if ( message.startsWith("JSON=")) {
										try {
											String jsonString = message.substring("JSON=".length());
											JSONObject jsonObject = new JSONObject(jsonString);
											Object object = jsonObject.get("log");
											if (object instanceof JSONArray) {
												JSONArray jsonLogArray = (JSONArray) object;
												Object[] logArray = new Object[jsonLogArray.length() + 2];
												logArray[0] = "Client log";
												logArray[1] = "==>";
												
												for (int argIndex = 0; argIndex < jsonLogArray.length(); argIndex++) {
													logArray[argIndex + 2] = jsonLogArray.get(argIndex);
												}
												log(LogMessageType.INFO, LogMessageSubject.SESSIONS, logArray);
											}
											else {
												log(LogMessageType.ERROR, LogMessageSubject.GENERAL, "Don't know how to handle JSONObject with log", object);
											}
										}
										catch (JSONException e) {
											// We also arrive here if object has no value for "log"
											log(LogMessageType.ERROR, LogMessageSubject.GENERAL, "Invalid or non-log JSON string from message", message, "Exception", e);
										}
									}
									else if ( firstByteOfMessage == PING_BYTE ) {
										/* RFC 6455:
										 *   A Ping frame MAY include "Application data".
										 * 
										 * it's also allowed to be empty. If it includes data, we have to pong back
										 * with the same data.
										 * 
										 * In practice, I hadn't noticed any browsers sending pings, until my Cypress
										 * tests started receiving them all the time (but only when testing on Firefox,
										 * and only when testing on the live server). The message sent with it was
										 * the string "PING".
										 * 
										 * It was very noticeable as it caused the tests to fail (this class fails as
										 * soon as it receives an unexpected byte through the InputStream, a bit extreme
										 * but it's good for spotting unexpected situations), so I don't think it
										 * was happening before.
										 */
										log( LogMessageType.DEBUG, LogMessageSubject.GENERAL, "ping received", message );
										enqueueMessage(PONG + message);
									}
									else if ( firstByteOfMessage == PONG_BYTE ) {
										log( LogMessageType.DEBUG, LogMessageSubject.GENERAL, "pong received", message );
									}
									else {
										log(LogMessageType.INFO, LogMessageSubject.GENERAL, "Message from socket", message );
									}
								}
							}
						}
					}
					else {
						stopSoon();
						log(LogMessageType.ERROR, LogMessageSubject.GENERAL, "Unexpected byte", bytesToHex(nextByte));
					}
					
					CelebrityMain.bytesReceived.accumulateAndGet(bytesReceived, Long::sum);
				}
			}
			catch ( SocketException e ) {
				log(LogMessageType.INFO, LogMessageSubject.GENERAL, "Handler no longer listening", e.getMessage());
			}
			catch ( IOException e ) {
				log(LogMessageType.ERROR, LogMessageSubject.GENERAL, "IOException in handler", e);
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
					byte messageStartByte;
					String messageContent = message;
					if ( message.isEmpty() ) {
						messageStartByte = (byte) PING_BYTE;
					}
					else if (message.startsWith(PONG)) {
						/* RFC 6455:
						 *   A Pong frame sent in response to a Ping frame must have identical
   						 *   "Application data" as found in the message body of the Ping frame
   						 *   being replied to.
   						 * 
   						 * we hack a solution here by concatenating the pong string
   						 * to the ping "Application data", and now removing the prefix again.
						 */
						messageStartByte	= (byte) PONG_BYTE;
						messageContent 		= message.substring(PONG.length());
					}
					else {
						messageStartByte = MESSAGE_START_BYTE;
					}
					
					sendMessage(messageStartByte, messageContent);

				}
				catch ( InterruptedException e ) {
					log(LogMessageType.ERROR, LogMessageSubject.GENERAL, "Output handler interrupted");
				}
				catch ( SocketException e ) {
					log(LogMessageType.INFO, LogMessageSubject.GENERAL, "Handler can no longer write", e.getMessage());
					stop();
				}
				catch (IOException e) {
					log(LogMessageType.ERROR, LogMessageSubject.GENERAL, "IOException in handler", e);
				}
				catch (RuntimeException e) {
					log(LogMessageType.ERROR, LogMessageSubject.GENERAL, "RuntimeException in handler", e);
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
				enqueueMessage("");
			}
		}
	}
	
	/**
	 * Thread which handles returning of index to {@link #sThreadIndexPool} on finalisation.
	 */
	private static class MyThread
	extends Thread {
		private int threadIndex;
		
		public MyThread(int aThreadIndex, Runnable aTarget, String aName) {
			super(aTarget, aName);
			threadIndex = aThreadIndex;
		}

		@Override
		protected void finalize() throws Throwable {
			int numFinalisedThreadsWithThisIndex = sMapThreadIndicesToNumberOfFinalisedThreads.computeIfAbsent(threadIndex, i -> new AtomicInteger(0)).incrementAndGet();
			if (numFinalisedThreadsWithThisIndex >= 2) {
				/* Doesn't account for ping timer, the 3rd thread which uses the same index.
				 * But that always ends at the same time as the others, so shouldn't be a problem
				 * in practice.
				 */
				sMapThreadIndicesToNumberOfFinalisedThreads.remove(threadIndex);
				sThreadIndexPool.push(threadIndex);
			}
			super.finalize();
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
						Session 	session 		= getSession();
						Player 		player 			= session == null ? null : session.getPlayer();
						Game 		game 			= player == null  ? null : player.getGame();
						Log.log(LogMessageType.ERROR, LogMessageSubject.GENERAL, "Hander for session", session, "player", player, "game", game, "trying to stop from stopSoon() method, exception", e);
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

		int threadNumber = sThreadIndexPool.pop();
		new MyThread(threadNumber, inputStreamRunnable,  "Websocket-InputStream-"  + threadNumber).start();
		new MyThread(threadNumber, outputStreamRunnable, "Websocket-OutputStream-" + threadNumber).start();

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

	public synchronized void stop() {
		listen = false;

		Session session = getSession();
		Player  player  = session == null ? null : session.getPlayer();
		Game    game    = player == null ? null : player.getGame();

		if ( socket != null ) {
			try {
				socket.close();
			} catch (IOException e) {
				log(LogMessageType.ERROR, LogMessageSubject.GENERAL, "IOException when closing socket of WebsocketHandler", e);
			}
		}
		if ( pingTimer != null ) {
			pingTimer.cancel();
			pingTimer = null;
		}
		
		if (session != null) {
			enqueueMessage(STOP);
		}
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
						Player player = session.getPlayer();
						Game game = player == null ? null : player.getGame();
						if (game != null) {
							JSONObject jsonObject = GameManager.toJSONObject(game, session.getSessionID(), true);
							jsonObject.put("GameEventType", "Refresh WebSocket");
							String jsonString = jsonObject.toString();
							enqueueMessage("JSON=" + jsonString);
							game.addGameEventListener(new NotifyClientGameEventListener(WebsocketHandler.this));
						}
					}
				}
				
				if (session == null) {
					Log.log(LogMessageType.ERROR, LogMessageSubject.GENERAL, "Unknown session ID", sessionID, "refusing handshake" );
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
				log(LogMessageType.INFO, LogMessageSubject.GENERAL, "Opened websocket to IP address", socket.getRemoteSocketAddress(), "player", session.getPlayer(), "session", session );

				return true;
			}
		}
		catch ( Exception e ) {
			Log.log(LogMessageType.ERROR, LogMessageSubject.GENERAL, "Exception during websocket handshake", e);
		}
		Log.log(LogMessageType.ERROR, LogMessageSubject.GENERAL, "Websocket handshake failed. IP", socket.getInetAddress());
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
			Session 	session 		= getSession();
			Player 		player 			= session == null ? null : session.getPlayer();
			Game 		game 			= player == null  ? null : player.getGame();
			Log.log(LogMessageType.ERROR, LogMessageSubject.GENERAL, "Hander for session", session, "player", player, "game", game, "interrupted when enqueuing output message", aMessage, "Exception", e);
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

	public long getLastSeenTimeMillis() {
		return lastSeenTimeMillis;
	}

	public boolean isListening() {
		return listen;
	}
	
	private Game getGame() {
		Player player = getPlayer();
		if (player == null) {
			return null;
		}
		else {
			return player.getGame();
		}
	}
	
	private Player getPlayer() {
		if (session == null) {
			return null;
		}
		else {
			return session.getPlayer();
		}
	}
	
	private void log(LogMessageType aType, LogMessageSubject aSubject, Object... aArgs) {
		Object[] logArgs = new Object[ aArgs.length + 6 ];
		System.arraycopy( new Object[] { "Player", getPlayer(), "session", session, "game", getGame() }, 0, logArgs, 0, 6);
		System.arraycopy(aArgs, 0, logArgs, 6, aArgs.length);
		Log.log(aType, aSubject, logArgs );
	}
}
