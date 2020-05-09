package com.merman.celebrity.server.handlers;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.WeakHashMap;

import org.json.JSONObject;

import com.sun.net.httpserver.HttpExchange;

public class HttpExchangeUtil {
	private static WeakHashMap<HttpExchange, String> requestBodyCache		= new WeakHashMap<>();
	
	public static synchronized String getRequestBody(HttpExchange aExchange) {
		String requestBody = requestBodyCache.get(aExchange);
		if ( requestBody == null ) {
			int bufferSize = 1024 * 1024;
			List<String> contentLengthList = aExchange.getRequestHeaders().get("Content-Length");
			if ( contentLengthList != null
					&& ! contentLengthList.isEmpty() ) {
				String reportedContentLengthString = contentLengthList.get(0);
				try {
					int reportedContentLength = Integer.parseInt(reportedContentLengthString);
					if (reportedContentLength > 0) {
						bufferSize = reportedContentLength;
					}
				}
				catch (NumberFormatException e) {
					e.printStackTrace();
				}
			}
			try {
				InputStream inputStream = aExchange.getRequestBody();
				int totalBytesRead = 0;
				byte[] buffer = new byte[bufferSize];
				for (int bytesRead = 0;
						( bytesRead = inputStream.read(buffer, totalBytesRead, buffer.length - totalBytesRead ) ) != -1; ) {
					totalBytesRead += bytesRead;
					if (totalBytesRead == buffer.length) {
						byte[] newBuffer = new byte[2 * buffer.length];
						System.arraycopy(buffer, 0, newBuffer, 0, buffer.length);
						buffer = newBuffer;
					}
				}
				requestBody = new String(buffer, 0, totalBytesRead, StandardCharsets.UTF_8);

			}
			catch ( IOException e ) {
				e.printStackTrace();
			}

			requestBodyCache.put(aExchange, requestBody);
		}

		return requestBody;
	}
	
	public static LinkedHashMap<String, Object> getRequestBodyAsMap( HttpExchange aExchange ) {
		String requestBody = getRequestBody(aExchange);
		return toMap(requestBody);
	}
	
	public static LinkedHashMap<String, Object>	toMap( String aJSONString ) {
		LinkedHashMap<String, Object>		map		= new LinkedHashMap<>();

		if ( ! aJSONString.isEmpty() ) {
			JSONObject jsonObject = new JSONObject(aJSONString);
			String[] names = JSONObject.getNames(jsonObject);
			for (String name : names) {
				map.put(name, jsonObject.get(name));
			}
		}
		
		return map;
	}
		

	public static String getSessionID(HttpExchange aExchange) {
		LinkedHashMap<String, String>		cookie		= getCookie( aExchange );
		return cookie.get("session");
	}

	public static LinkedHashMap<String, String> getCookie(HttpExchange aExchange) {
		LinkedHashMap<String, String>		cookie		= new LinkedHashMap<>();
		List<String> cookieElementList = aExchange.getRequestHeaders().get("Cookie");
		if (cookieElementList != null) {
			for ( String cookieElement : cookieElementList ) {

				/* When testing with Cypress, it was the first time there were 2 cookies
				 * set instead of just 1 (the session, and also something set by Cypress).
				 * 
				 * For some reason, it came out as a list containing a string which was
				 * a semi-colon-separated list of cookies, instead of having each name-value
				 * pair as an element of the list. Something wrong there, but anyway we can
				 * still parse it.
				 */
				List<String> cookieElementElementList = Arrays.asList( cookieElement.split(";") );
				for ( String cookieElementElement: cookieElementElementList ) {
					int indexOfEquals = cookieElementElement.indexOf('=');
					if ( indexOfEquals >= 0
							&& indexOfEquals < cookieElementElement.length() - 1 ) {
						cookie.put(cookieElementElement.substring(0, indexOfEquals), cookieElementElement.substring(indexOfEquals + 1));
					}
				}
			}
		}
		return cookie;
	}
}
