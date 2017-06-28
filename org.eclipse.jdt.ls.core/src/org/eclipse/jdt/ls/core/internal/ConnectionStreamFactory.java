/*******************************************************************************
 * Copyright (c) 2016-2017 Red Hat Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Red Hat Inc. - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.ls.core.internal;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.Socket;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;

import org.eclipse.core.runtime.Platform;
import org.newsclub.net.unix.AFUNIXSocket;
import org.newsclub.net.unix.AFUNIXSocketAddress;

/**
 * A factory for creating the streams for supported transmission methods.
 *
 * @author Gorkem Ercan
 *
 */
public class ConnectionStreamFactory {

	interface StreamProvider {
		InputStream getInputStream() throws IOException;

		OutputStream getOutputStream() throws IOException;
	}

	
	protected final class PipeStreamProvider implements StreamProvider {

		private final String pipeName;
		private InputStream fInputStream;
		private OutputStream fOutputStream;

		public PipeStreamProvider(String pipeName) {
			this.pipeName = pipeName;
		}

		private void initializeConnection() throws IOException {
			if (isWindows()) {
				String pipePath = "\\\\.\\pipe\\" + pipeName;
				RandomAccessFile readFile = new RandomAccessFile(pipePath, "rwd");
				FileChannel channel = readFile.getChannel();
				fInputStream = Channels.newInputStream(channel);
				fOutputStream = Channels.newOutputStream(channel);
			} else {
				String pipePath = "/tmp/" + pipeName + ".sock";
				AFUNIXSocket readSocket = AFUNIXSocket.newInstance();
				readSocket.connect(new AFUNIXSocketAddress(new File(pipePath)));
				fInputStream = readSocket.getInputStream();
				fOutputStream = readSocket.getOutputStream();
			}
		}

		@Override
		public InputStream getInputStream() throws IOException {
			if (fInputStream == null) {
				initializeConnection();
			}
			return fInputStream;
		}

		@Override
		public OutputStream getOutputStream() throws IOException {
			if (fOutputStream == null) {
				initializeConnection();
			}
			return fOutputStream;
		}
	}

	protected final class SocketStreamProvider implements StreamProvider {
		private final String readHost;
		private final String writeHost;
		private final int readPort;
		private final int writePort;

		public SocketStreamProvider(String readHost, int readPort, String writeHost, int writePort) {
			this.readHost = readHost;
			this.readPort = readPort;
			this.writeHost = writeHost;
			this.writePort = writePort;
		}

		@Override
		public InputStream getInputStream() throws IOException {
			Socket readSocket = new Socket(readHost, readPort);
			return readSocket.getInputStream();
		}

		@Override
		public OutputStream getOutputStream() throws IOException {
			Socket writeSocket = new Socket(writeHost, writePort);
			return writeSocket.getOutputStream();
		}

	}

	protected final class StdIOStreamProvider implements StreamProvider {

		/* (non-Javadoc)
		 * @see org.eclipse.jdt.ls.core.internal.ConnectionStreamFactory.StreamProvider#getInputStream()
		 */
		@Override
		public InputStream getInputStream() throws IOException {
			return JavaLanguageServerPlugin.getIn();
		}

		/* (non-Javadoc)
		 * @see org.eclipse.jdt.ls.core.internal.ConnectionStreamFactory.StreamProvider#getOutputStream()
		 */
		@Override
		public OutputStream getOutputStream() throws IOException {
			return JavaLanguageServerPlugin.getOut();
		}

	}

	private static String OS = System.getProperty("os.name").toLowerCase();
	private StreamProvider provider;

	/**
	 *
	 * @return
	 */
	public StreamProvider getSelectedStream() {
		if (provider == null) {
			final String pipeName = Environment.get("INOUT_PIPE_NAME");
			if (pipeName != null) {
				provider = new PipeStreamProvider(pipeName);
			}
			final String wHost = Environment.get("STDIN_HOST", "localhost");
			final String rHost = Environment.get("STDOUT_HOST", "localhost");
			final String wPort = Environment.get("STDIN_PORT");
			final String rPort = Environment.get("STDOUT_PORT");
			if (rPort != null && wPort != null) {
				provider = new SocketStreamProvider(rHost, Integer.parseInt(rPort), wHost, Integer.parseInt(wPort));
			}
			if (provider == null) {//Fall back to std io
				provider = new StdIOStreamProvider();
			}
		}
		return provider;
	}

	public InputStream getInputStream() throws IOException {
		return getSelectedStream().getInputStream();
	}

	public OutputStream getOutputStream() throws IOException {
		return getSelectedStream().getOutputStream();
	}

	protected static boolean isWindows() {
		return Platform.OS_WIN32.equals(Platform.getOS());
	}

}
