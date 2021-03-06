package com.merman.celebrity.server.logging.info;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.merman.celebrity.server.logging.LogMessageSubject;
import com.merman.celebrity.server.logging.LogMessageType;

public class LogInfo {
	private static ThreadLocal<DateFormat>		threadLocalDateFormat		= new ThreadLocal<DateFormat>() {

		@Override
		protected DateFormat initialValue() {
			SimpleDateFormat		dateFormat		= new SimpleDateFormat("[yyyy-MM-dd HH:mm:ss]");
			dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
			return dateFormat;
		}
		
	};

	private String logString;
	private Date timeStamp = new Date();
	private LogMessageType type;
	private LogMessageSubject subject;
	private Object[] args;
	
	public LogInfo(LogMessageType aType, LogMessageSubject aSubject, Object... aArgs) {
		type = aType;
		subject = aSubject;
		args = aArgs;
	}
	
	public String formatArgs(Object... aArgs) {
		List<String> argStringList = IntStream.range(0, aArgs.length)
		.mapToObj( i -> {
			String formattedArg = format( aArgs[i] );
			if (i % 2 == 1) {
				formattedArg = "[" + formattedArg + "]";
				if (i < aArgs.length-1) {
					formattedArg += ",";
				}
			}
			return formattedArg;
		})
		.collect( Collectors.toList() );
		return String.join(" ", argStringList);
	}
	
	private static String format(Object aObject) {
		if ( aObject == null ) {
			return "null";
		}
		else if ( aObject instanceof Throwable ) {
			Throwable throwable = (Throwable) aObject;
			ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
			throwable.printStackTrace(new PrintStream( byteArrayOutputStream ));
			return throwable.toString() + "\n" + byteArrayOutputStream.toString();
		}
		else {
			return aObject.toString();
		}
	}
	
	@Override
	public String toString() {
		if (logString == null) {
			String timeStampString = threadLocalDateFormat.get().format(timeStamp);
			logString = String.format("%s %s [%s] [%s] %s", timeStampString, type, subject, Thread.currentThread().getName(), formatArgs(args));
		}
		
		return logString;
	}

	public Object[] getArgs() {
		return args;
	}

	public LogMessageType getType() {
		return type;
	}

	public LogMessageSubject getSubject() {
		return subject;
	}
}
