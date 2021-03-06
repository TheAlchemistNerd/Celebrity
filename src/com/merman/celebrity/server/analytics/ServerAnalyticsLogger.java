package com.merman.celebrity.server.analytics;

import java.lang.management.ManagementFactory;
import java.util.Timer;
import java.util.TimerTask;

import com.merman.celebrity.game.GameManager;
import com.merman.celebrity.game.PlayerManager;
import com.merman.celebrity.server.CelebrityMain;
import com.merman.celebrity.server.Server;
import com.merman.celebrity.server.SessionManager;
import com.merman.celebrity.server.logging.Log;
import com.merman.celebrity.server.logging.LogMessageSubject;
import com.merman.celebrity.server.logging.LogMessageType;

public class ServerAnalyticsLogger {
	private Server server;
	private Timer  timer;
	
	private class MyTimerTask
	extends TimerTask {
		@Override
		public void run() {
			log(server);
		}
	}

	public ServerAnalyticsLogger(Server aServer) {
		server = aServer;
	}
	
	public synchronized void stop() {
		if (timer != null) {
			timer.cancel();
			timer = null;
		}
	}
	
	public synchronized void start() {
		if (timer != null) {
			throw new IllegalStateException("Already started");
		}
		timer = new Timer("ServerAnalyticsLogger", true);
		timer.schedule(new MyTimerTask(), 0, 60000);
	}
	
	private static void log(Server aServer) {
		Log.log(LogMessageType.INFO, LogMessageSubject.ANALYTICS,
				"games", GameManager.getNumGames(),
				"players", PlayerManager.getNumPlayers(),
				"sessions", SessionManager.getNumSessions(),
				"mem used", humanReadableByteCount(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(), false),
				"threads", ManagementFactory.getThreadMXBean().getThreadCount(),
				"sent", humanReadableByteCount(CelebrityMain.bytesSent.get(), false),
				"received", humanReadableByteCount(CelebrityMain.bytesReceived.get(), false)
				);
	}
	
	  /**
	   * Returns a string representing <code>aBytes</code> as KB, MB, GB etc depending on how large it is.
	   * <p>
	   * Credits: http://stackoverflow.com/questions/3758606/how-to-convert-byte-size-into-human-readable-format-in-java/3758880#3758880
	   * @param aBytes A number of bytes
	   * @param aUseSIDefinition if <code>true</code>, 1000 bytes = 1kb, 1000 kb = 1mb, etc. If <code>false</code>, 1024 bytes = 1kb, etc.
	   * @return A string representing <code>aBytes</code> as KB, MB, GB etc depending on how large it is. Returned string is accurate to 1 decimal place.
	   */
	  public static String humanReadableByteCount( long aBytes, boolean aUseSIDefinition ) {
	    int unit = aUseSIDefinition ? 1000 : 1024;
	    long absBytes = Math.abs( aBytes );
	    if (absBytes < unit) {
	      return aBytes + " B";
	    }
	    int exp = (int) (Math.log(absBytes) / Math.log(unit));
	    char pre = (aUseSIDefinition ? "kMGTPE" : "KMGTPE").charAt(exp-1);
	    return String.format("%.1f %sB", aBytes / Math.pow(unit, exp), pre);
	  }

}
