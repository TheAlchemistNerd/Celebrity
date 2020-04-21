package com.merman.celebrity.server;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;

import com.merman.celebrity.server.handlers.AnnotatedHandlers;
import com.merman.celebrity.server.handlers.AnnotatedMethodBasedHttpHandler;
import com.merman.celebrity.server.handlers.FormHandler;
import com.merman.celebrity.server.handlers.FormHandlerRegistry;
import com.merman.celebrity.server.handlers.ServeFileHandler;
import com.sun.net.httpserver.HttpServer;

public class Server {
	public static final Path CLIENT_FILE_DIRECTORY = new File( "../../client" ).toPath();
	
	private int portNumber = 8080;
	
	public void start() throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress( portNumber ), 10);

		Files.walk( CLIENT_FILE_DIRECTORY )
		.forEach( path -> {
			if ( Files.isRegularFile(path) ) {
				String pathAsString = CLIENT_FILE_DIRECTORY.relativize(path).toString();
				System.out.println( "adding file: " + pathAsString );
				server.createContext("/" + pathAsString, new ServeFileHandler(pathAsString));
			}
		} );

		System.out.println("\n\n");

		List<AnnotatedMethodBasedHttpHandler> handlers = AnnotatedMethodBasedHttpHandler.createHandlers(AnnotatedHandlers.class);
		for ( AnnotatedMethodBasedHttpHandler handler : handlers ) {
			if ( handler.getRequestType() == RequestType.GET_OR_POST ) {
				System.out.println("adding context " + handler.getContextName());
				server.createContext("/" + handler.getContextName(), handler);
			}
			else if ( handler.getRequestType() == RequestType.FORM ) {
				System.out.println("adding form handler " + handler.getContextName());
				FormHandlerRegistry.addHandler(new FormHandler(handler.getContextName(), handler) {

					@Override
					public boolean hasResponded() {
						return true;
					}

				});
			}
			else {
				System.err.println("Unknown request type: " + handler.getRequestType());
			}
		}
		
//		server.createContext( "/", new TestHandler("") );

		server.setExecutor( Executors.newFixedThreadPool(10) );
		server.start();
	}
}
