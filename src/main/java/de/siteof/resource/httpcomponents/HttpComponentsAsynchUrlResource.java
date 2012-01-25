package de.siteof.resource.httpcomponents;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpCookie;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.impl.DefaultConnectionReuseStrategy;
import org.apache.http.impl.nio.DefaultClientIOEventDispatch;
import org.apache.http.impl.nio.reactor.DefaultConnectingIOReactor;
import org.apache.http.message.BasicHttpRequest;
import org.apache.http.nio.NHttpConnection;
import org.apache.http.nio.protocol.BufferingHttpClientHandler;
import org.apache.http.nio.protocol.EventListener;
import org.apache.http.nio.protocol.HttpRequestExecutionHandler;
import org.apache.http.nio.reactor.ConnectingIOReactor;
import org.apache.http.nio.reactor.IOEventDispatch;
import org.apache.http.nio.reactor.IOReactorException;
import org.apache.http.nio.reactor.SessionRequest;
import org.apache.http.nio.reactor.SessionRequestCallback;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.CoreConnectionPNames;
import org.apache.http.params.CoreProtocolPNames;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.BasicHttpProcessor;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.RequestConnControl;
import org.apache.http.protocol.RequestContent;
import org.apache.http.protocol.RequestExpectContinue;
import org.apache.http.protocol.RequestTargetHost;
import org.apache.http.protocol.RequestUserAgent;
import org.apache.http.util.EntityUtils;

import de.siteof.resource.AbstractResource;
import de.siteof.resource.ICookieManager;
import de.siteof.resource.event.IResourceListener;
import de.siteof.resource.event.ResourceLoaderEvent;
import de.siteof.resource.util.ObjectHolder;
import de.siteof.task.AbstractTask;
import de.siteof.task.ITaskManager;
import de.siteof.task.SingleThreadTaskManager;
import de.siteof.task.SynchronousTaskManager;

public class HttpComponentsAsynchUrlResource extends AbstractResource {

	private static abstract class AbstractHttpRequestHandler implements HttpRequestExecutionHandler,
			SessionRequestCallback, EventListener {

		private String currentUrl;
		private String hostname;
		private int port;
		private HttpHost targetHost;
		private String uri;
		private String[] cookies;

		private boolean requestSent;
		private boolean responseReceived;

		protected abstract void onResponseComplete(
				HttpResponse response) throws IOException;

		protected abstract void onResponseFailed(
				HttpResponse response, int statusCode);

		protected abstract void onError(Throwable cause);

		protected abstract void onError(String message);

		protected abstract void onStatusMessage(String message);

		protected abstract void redirectTo(String redirectUrl);

		protected boolean isSuccessfulStatusCode(int statusCode) {
			return (statusCode == 200);
		}

		protected boolean isRedirectionStatusCode(int statusCode) {
			final boolean result;
			switch (statusCode) {
			case 301:
			case 302:
			case 303:
			case 307:
				result = true;
				break;
			default:
				result = false;
			}
			return result;
		}

		@Override
		public void finalizeContext(HttpContext context) {
		}

		protected abstract void saveSessionCookies(Header[] headers);

		@Override
		public void handleResponse(HttpResponse response, HttpContext context)
				throws IOException {
			if (responseReceived) {
				log.warn("response received multiple times?");
				return;
			}
			responseReceived = true;
			try {
				int statusCode = response.getStatusLine().getStatusCode();
				if (log.isDebugEnabled()) {
					log.debug("received response, status code " + statusCode);
				}
				this.onStatusMessage("received response, status code " + statusCode);
				Header[] headers = response.getAllHeaders();

				if (isSuccessfulStatusCode(statusCode)) {
					this.onResponseComplete(response);
				} else if (isRedirectionStatusCode(statusCode)) {
					saveSessionCookies(headers);
					String location = null;
					if (headers != null) {
						for (Header header: headers) {
							if ("Location".equals(header.getName())) {
								location = header.getValue();
							}
						}
					}
					if ((location != null) && (location.length() > 0)) {
						try {
							if (location.equals(currentUrl)) {
								onError("redirect location same as current address");
							} else {
								if (log.isInfoEnabled()) {
									log.info("Redirecting to: " + location + "(" + currentUrl + ")");
								}
								requestSent = false;
								redirectTo(location);
							}
						} catch (Exception e) {
							onError(new IOException("failed to redirect: " + location, e));
						}
					} else {
						onError("'Location' header not found for redirection");
					}
				} else {
					this.onResponseFailed(response, statusCode);
				}
			} catch (Throwable e) {
				this.onError(e);
			}
		}

		@Override
		public void initalizeContext(HttpContext context, Object attachment) {
			responseReceived = false;
			requestSent = false;
		}

		@Override
		public HttpRequest submitRequest(HttpContext context) {
			if (requestSent) {
				log.warn("request already sent?");
//				return null;
			}
			requestSent = true;
			String[] cookies = this.getCookies();
			String uri = (String) this.getUri();

			if (log.isDebugEnabled()) {
				log.debug("--------------");
				log.debug("Sending request to " + targetHost);
				log.debug("URI: " + uri);
				log.debug("--------------");
			}
			if (log.isInfoEnabled()) {
				log.info("uri=[" + uri + "]");
			}

			BasicHttpRequest request = new BasicHttpRequest("GET", uri);
			if (cookies != null) {
				for (int i = 0; i < cookies.length; i++) {
					request.addHeader("Cookie", cookies[i]);
				}
			}
			request.addHeader("User-Agent", "Mozilla/5.0 (X11; Linux i686; rv:7.0.1) Gecko/20100101 Firefox/7.0.1");

			return request;
		}


		@Override
		public void cancelled(SessionRequest request) {
			this.onError("request cancelled");
		}


		@Override
		public void completed(SessionRequest request) {
			this.onStatusMessage("request completed");
		}


		@Override
		public void failed(SessionRequest request) {
			this.onError("request failed");
		}


		@Override
		public void timeout(SessionRequest request) {
			this.onError("request timeout");
		}

		@Override
		public void connectionClosed(NHttpConnection conn) {
			this.onStatusMessage("connection closed");
			if (!responseReceived) {
				log.warn("connection closed (no response received)");
				this.onError("connection closed (no response received)");
			}
		}

		@Override
		public void connectionOpen(NHttpConnection conn) {
			this.onStatusMessage("connection open");
		}

		@Override
		public void connectionTimeout(NHttpConnection conn) {
			this.onStatusMessage("connection timeout");
		}

		@Override
		public void fatalIOException(IOException ex, NHttpConnection conn) {
			this.onStatusMessage("fatalIOException, " + ex);
		}

		@Override
		public void fatalProtocolException(HttpException ex,
				NHttpConnection conn) {
			this.onStatusMessage("fatalProtocolException, " + ex);
		}

		/**
		 * @return the targetHost
		 */
		public HttpHost getTargetHost() {
			return targetHost;
		}

		/**
		 * @param targetHost
		 *            the targetHost to set
		 */
		public void setTargetHost(HttpHost targetHost) {
			this.targetHost = targetHost;
		}

		/**
		 * @return the cookies
		 */
		public String[] getCookies() {
			return cookies;
		}

		/**
		 * @param cookies
		 *            the cookies to set
		 */
		public void setCookies(String[] cookies) {
			this.cookies = cookies;
		}

		/**
		 * @return the uri
		 */
		public String getUri() {
			return uri;
		}

		/**
		 * @param uri
		 *            the uri to set
		 */
		public void setUri(String uri) {
			this.uri = uri;
		}

		/**
		 * @return the hostname
		 */
		public String getHostname() {
			return hostname;
		}

		/**
		 * @param hostname the hostname to set
		 */
		public void setHostname(String hostname) {
			this.hostname = hostname;
		}

		/**
		 * @return the port
		 */
		public int getPort() {
			return port;
		}

		/**
		 * @param port the port to set
		 */
		public void setPort(int port) {
			this.port = port;
		}

		/**
		 * @return the currentUrl
		 */
		public String getCurrentUrl() {
			return currentUrl;
		}

		/**
		 * @param currentUrl the currentUrl to set
		 */
		public void setCurrentUrl(String currentUrl) {
			this.currentUrl = currentUrl;
		}
	}

	private static class DelegatingHttpRequestExecutionHandler implements
			HttpRequestExecutionHandler {
		private final static String ATTACHMENT_ATTRIBUTE_NAME = "attachment";

		public DelegatingHttpRequestExecutionHandler() {
			super();
		}

		private HttpRequestExecutionHandler getHandler(HttpContext context) {
			return (HttpRequestExecutionHandler) context.getAttribute(ATTACHMENT_ATTRIBUTE_NAME);
		}

		@Override
		public void finalizeContext(HttpContext context) {
			getHandler(context).finalizeContext(context);
		}

		@Override
		public void handleResponse(HttpResponse response, HttpContext context)
				throws IOException {
			getHandler(context).handleResponse(response, context);
		}

		@Override
		public void initalizeContext(HttpContext context, Object attachment) {
			context.setAttribute(ATTACHMENT_ATTRIBUTE_NAME, attachment);
			getHandler(context).initalizeContext(context, attachment);
		}

		@Override
		public HttpRequest submitRequest(HttpContext context) {
			return getHandler(context).submitRequest(context);
		}

	}


	private static class DelegatingEventLogger implements EventListener {

		private final static String ATTACHMENT_ATTRIBUTE_NAME = "attachment";

		private EventListener getListener(NHttpConnection connection) {
			HttpContext context = connection.getContext();
			Object o = context.getAttribute(ATTACHMENT_ATTRIBUTE_NAME);
			EventListener result;
			if (o instanceof EventListener) {
				result = (EventListener) o;
			} else {
				result = null;
			}
			return result;
		}

		@Override
		public void connectionOpen(final NHttpConnection connection) {
			if (log.isDebugEnabled()) {
				log.debug("Connection open: " + connection);
			}
			EventListener listener = this.getListener(connection);
			if (listener != null) {
				listener.connectionOpen(connection);
			}
		}

		@Override
		public void connectionTimeout(final NHttpConnection connection) {
			if (log.isDebugEnabled()) {
				log.debug("Connection timed out: " + connection);
			}
			EventListener listener = this.getListener(connection);
			if (listener != null) {
				listener.connectionTimeout(connection);
			}
		}

		@Override
		public void connectionClosed(final NHttpConnection connection) {
			if (log.isDebugEnabled()) {
				log.debug("Connection open: " + connection);
			}
			EventListener listener = this.getListener(connection);
			if (listener != null) {
				listener.connectionClosed(connection);
			}
		}

		@Override
		public void fatalIOException(final IOException ex,
				final NHttpConnection connection) {
			if (log.isErrorEnabled()) {
				log.error("I/O error: " + ex.getMessage());
			}
			EventListener listener = this.getListener(connection);
			if (listener != null) {
				listener.fatalIOException(ex, connection);
			}
		}

		@Override
		public void fatalProtocolException(final HttpException ex,
				final NHttpConnection connection) {
			if (log.isErrorEnabled()) {
				log.error("HTTP error: " + ex.getMessage());
			}
			EventListener listener = this.getListener(connection);
			if (listener != null) {
				listener.fatalProtocolException(ex, connection);
			}
		}

	}

	private static final Log log = LogFactory.getLog(HttpComponentsAsynchUrlResource.class);

	private static ConnectingIOReactor ioReactor;
	private static BasicHttpProcessor httpproc;

	private static ITaskManager httpTaskManager = new SingleThreadTaskManager();
	private static ITaskManager redirectTaskManager = new SynchronousTaskManager();

	private static Object initLock = new Object();

	private final ICookieManager cookieManager;

	private transient SessionRequest sessionRequest;

	public HttpComponentsAsynchUrlResource(String name, ICookieManager cookieManager,
			ITaskManager taskManager) {
		super(name, taskManager);
		this.cookieManager = cookieManager;
	}

	private static void init() {
		if (httpproc == null) {
			synchronized (initLock) {
				if (httpproc == null) {
					HttpParams params = new BasicHttpParams();
					params
							.setIntParameter(CoreConnectionPNames.SO_TIMEOUT,
									5000)
							.setIntParameter(
									CoreConnectionPNames.CONNECTION_TIMEOUT,
									10000)
							.setIntParameter(
									CoreConnectionPNames.SOCKET_BUFFER_SIZE,
									8 * 1024)
							.setBooleanParameter(
									CoreConnectionPNames.STALE_CONNECTION_CHECK,
									false).setBooleanParameter(
									CoreConnectionPNames.TCP_NODELAY, true)
							.setParameter(CoreProtocolPNames.USER_AGENT,
									"n/a");

					try {
						ioReactor = new DefaultConnectingIOReactor(2, params);
					} catch (IOReactorException e) {
						throw new RuntimeException(
								"Failed to create IO Reactor - " + e, e);
					}

					httpproc = new BasicHttpProcessor();
					httpproc.addInterceptor(new RequestContent());
					httpproc.addInterceptor(new RequestTargetHost());
					httpproc.addInterceptor(new RequestConnControl());
					httpproc.addInterceptor(new RequestUserAgent());
					httpproc.addInterceptor(new RequestExpectContinue());


					BufferingHttpClientHandler handler = new BufferingHttpClientHandler(
							httpproc, new DelegatingHttpRequestExecutionHandler(),
							new DefaultConnectionReuseStrategy(), params);

					handler.setEventListener(new DelegatingEventLogger());

					final IOEventDispatch ioEventDispatch = new DefaultClientIOEventDispatch(
							handler, params);

					Thread t = new Thread(new Runnable() {

						@Override
						public void run() {
							try {
								ioReactor.execute(ioEventDispatch);
							} catch (InterruptedIOException e) {
								log.warn("IO reactor thread interrupted", e);
							} catch (Throwable e) {
								log.error("IO reactor thread exception - " + e, e);
							}
							if (log.isInfoEnabled()) {
								log.info("IO reactor thread shutdown");
							}
						}

					});
					t.start();
				}
			}
		}
	}

	public static void disposeAll() {
		if (ioReactor != null) {
			synchronized (initLock) {
				if (ioReactor != null) {
					try {
						ioReactor.shutdown();
					} catch (IOException e) {
						log.warn("Failed to shutdown IO Reactor - " + e, e);
					}
					ioReactor = null;
				}
			}
		}
	}

	@Override
	public boolean exists() throws IOException {
		// may need to get re-implemented
		InputStream in = getResourceAsStream();
		if (in == null) {
			return false;
		}
		in.close();
		return true;
	}

	@Override
	public InputStream getResourceAsStream() throws IOException {
		byte[] data = this.getResourceBytes();
		InputStream in;
		if (data != null) {
			in = new ByteArrayInputStream(data);
		} else {
			in = null;
		}
		return in;
	}

	@Override
	public long getSize() {
		return 0;
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see de.siteof.webpicturebrowser.loader.AbstractResource#abort()
	 */
	@Override
	public void abort() {
		// super.abort();
		SessionRequest sessionRequest = this.sessionRequest;
		if (sessionRequest != null) {
			this.sessionRequest = null;
			sessionRequest.cancel();
		}
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see
	 * de.siteof.webpicturebrowser.loader.AbstractResource#getResourceAsStream
	 * (de.siteof.webpicturebrowser.loader.event.IResourceListener)
	 */
	@Override
	public void getResourceAsStream(
			IResourceListener<ResourceLoaderEvent<InputStream>> listener)
			throws IOException {
		// TODO Auto-generated method stub
//		super.getResourceAsStream(listener);
//		final IResourceListener<ResourceLoaderEvent<InputStream>> finalListener = listener;
//		this.getResourceBytes(new IResourceListener<ResourceLoaderEvent<byte[]>>() {
//			public void onResourceEvent(ResourceLoaderEvent<byte[]> event) {
//				if (event.isComplete()) {
//					byte[] data = event.getResult();
//					finalListener.onResourceEvent(
//							new ResourceLoaderEvent<InputStream>(
//							AsynchUrlResource.this, new ByteArrayInputStream(data), true));
//				} else {
//					finalListener.onResourceEvent(
//							new ResourceLoaderEvent<InputStream>(
//							AsynchUrlResource.this, event.getCause()));
//				}
//			}});
		final String name = this.getName();
		if (log.isDebugEnabled()) {
			log.debug("name=[" + name + "]");
		}
		final IResourceListener<ResourceLoaderEvent<InputStream>> finalListener = listener;

		final AbstractHttpRequestHandler handler = new AbstractHttpRequestHandler() {

			private final AtomicBoolean finalEventFired = new AtomicBoolean();

			@Override
			protected void redirectTo(final String redirectUrl) {
				final AbstractHttpRequestHandler handler = this;
				redirectTaskManager.addTask(new AbstractTask() {
					@Override
					public void execute() throws Exception {
						try {
							// TODO add redirection limit
							onStatusMessage("redirecting to: " + redirectUrl);
							updateHandlerForUrl(handler, redirectUrl);
							if (sessionRequest != null) {
								sessionRequest.cancel();
								sessionRequest = null;
							}
							sessionRequest = ioReactor.connect(
									new InetSocketAddress(handler.getHostname(), handler.getPort()),
									null,
									handler,
									handler);
						} catch (Throwable e) {
							onError(e);
						}
					}});
			}

			@Override
			protected void onError(Throwable cause) {
				if (!finalEventFired.getAndSet(true)) {
					finalListener.onResourceEvent(new ResourceLoaderEvent<InputStream>(
							HttpComponentsAsynchUrlResource.this,
							new IOException("request failed, name=[" + name + "], cause=" + cause)));
				}
			}

			@Override
			protected void onError(String message) {
				if (!finalEventFired.getAndSet(true)) {
					finalListener.onResourceEvent(new ResourceLoaderEvent<InputStream>(
							HttpComponentsAsynchUrlResource.this,
							new IOException(message + ", name=[" + name + "]")));
				}
			}

			@Override
			protected void onStatusMessage(String message) {
				if (!finalEventFired.getAndSet(true)) {
					notifyStatusMessage(finalListener, message);
					finalEventFired.set(false);
				}
			}

			@Override
			protected void onResponseComplete(HttpResponse response) throws IOException {
				HttpEntity entity = response.getEntity();
				InputStream in = entity.getContent();
				if (in != null) {
					long contentLength = entity.getContentLength();
			        if (contentLength > Integer.MAX_VALUE) {
			            throw new IllegalArgumentException("HTTP entity too large, " + contentLength);
			        }
			        final int availableLength = (int) contentLength;
					in = new FilterInputStream(in) {

						@Override
						public int available() throws IOException {
							return availableLength;
						}

						@Override
						public void close() throws IOException {
							try {
								super.close();
							} catch (Exception e) {
								log.debug("failed to close input stream - " + e, e);
							}
							try {
								if (sessionRequest != null) {
									sessionRequest.cancel();
									sessionRequest = null;
								}
							} catch (Exception e) {
								log.debug("failed to cancel connection - " + e, e);
							}
						}

					};
					try {
						if (!finalEventFired.getAndSet(true)) {
							finalListener.onResourceEvent(new ResourceLoaderEvent<InputStream>(
									HttpComponentsAsynchUrlResource.this, in, true));
							in = null;
						} else {
							log.warn("response complete after final event was fired");
						}
					} finally {
						if (in != null) {
							in.close();
						}
					}
				}
			}

			@Override
			protected void onResponseFailed(HttpResponse response, int statusCode) {
				onError("resource not found(" + statusCode + ")");
			}

			@Override
			protected void saveSessionCookies(Header[] headers) {
				HttpComponentsAsynchUrlResource.this.saveSessionCookies(headers);
			}};

		updateHandlerForUrl(handler, name);

		httpTaskManager.addTask(new AbstractTask() {

			@Override
			public void execute() throws Exception {

				try {
					init();

//					notifyStatusMessage(finalListener, "starting...");

					if (sessionRequest != null) {
						sessionRequest.cancel();
						sessionRequest = null;
					}
					sessionRequest = ioReactor.connect(
							new InetSocketAddress(handler.getHostname(), handler.getPort()),
							null,
							handler,
							handler);
				} catch (Throwable e) {
					finalListener.onResourceEvent(new ResourceLoaderEvent<InputStream>(
							HttpComponentsAsynchUrlResource.this,
							new IOException("failed to load resource, name=[" + name + "], " + e, e)));
				}

			}});
	}

	private void saveSessionCookies(Header[] headers) {
		if ((headers != null) && (this.cookieManager != null)) {
			List<HttpCookie> allCookies = new LinkedList<HttpCookie>();
			for (Header header: headers) {
				if ("Set-Cookie".equals(header.getName())) {
					List<HttpCookie> cookies = HttpCookie.parse(header.toString());
					if (cookies != null) {
						allCookies.addAll(cookies);
					}
				}
			}
			this.cookieManager.setSessionCookies(allCookies);
		}
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see
	 * de.siteof.webpicturebrowser.loader.AbstractResource#getResourceBytes(
	 * de.siteof.webpicturebrowser.loader.event.IResourceListener)
	 */
	@Override
	public void getResourceBytes(
			IResourceListener<ResourceLoaderEvent<byte[]>> listener)
			throws IOException {
		getResourceBytes(listener, this.getName());
	}

	protected void updateHandlerForUrl(AbstractHttpRequestHandler handler, String urlString) throws MalformedURLException {
		URL url = new URL(urlString);
		String host = url.getHost();
		int port = url.getPort();
		if (port < 0) {
			port = 80;
		}
		String uri = url.getFile();
		if (uri.length() == 0) {
			uri = "/";
		}
		String[] cookies = null;
		if (cookieManager != null) {
			cookies = cookieManager.getCookiesForName(urlString);
		}

		handler.setCurrentUrl(urlString);
		handler.setHostname(host);
		handler.setPort(port);
		handler.setTargetHost(new HttpHost(host));
		handler.setUri(uri);
		handler.setCookies(cookies);
	}

	public void getResourceBytes(
			IResourceListener<ResourceLoaderEvent<byte[]>> listener,
			final String name)
			throws IOException {

		if (log.isDebugEnabled()) {
			log.debug("name=[" + name + "]");
		}
		final IResourceListener<ResourceLoaderEvent<byte[]>> finalListener = listener;

		final AbstractHttpRequestHandler handler = new AbstractHttpRequestHandler() {

			private final AtomicBoolean finalEventFired = new AtomicBoolean();

			@Override
			protected void redirectTo(final String redirectUrl) {
				final AbstractHttpRequestHandler handler = this;
				redirectTaskManager.addTask(new AbstractTask() {
					@Override
					public void execute() throws Exception {
						try {
							// TODO add redirection limit
							onStatusMessage("redirecting to: " + redirectUrl);
							updateHandlerForUrl(handler, redirectUrl);
							if (sessionRequest != null) {
								sessionRequest.cancel();
								sessionRequest = null;
							}
							sessionRequest = ioReactor.connect(
									new InetSocketAddress(handler.getHostname(), handler.getPort()),
									null,
									handler,
									handler);
						} catch (Throwable e) {
							onError(e);
						}
					}});
			}

			@Override
			protected void onError(Throwable cause) {
				if (!finalEventFired.getAndSet(true)) {
					finalListener.onResourceEvent(new ResourceLoaderEvent<byte[]>(
							HttpComponentsAsynchUrlResource.this,
							new IOException("request failed, name=[" + name + "], cause=" + cause)));
				} else {
					log.warn("error after final completion: " + cause);
				}
			}

			@Override
			protected void onError(String message) {
				if (!finalEventFired.getAndSet(true)) {
					finalListener.onResourceEvent(new ResourceLoaderEvent<byte[]>(
							HttpComponentsAsynchUrlResource.this,
							new IOException(message + ", name=[" + name + "]")));
				} else {
					log.warn("error after final completion: " + message);
				}
			}

			@Override
			protected void onStatusMessage(String message) {
				if (!finalEventFired.getAndSet(true)) {
					notifyStatusMessage(finalListener, message);
					finalEventFired.set(false);
				}
			}

			@Override
			protected void onResponseComplete(HttpResponse response) throws IOException {
				HttpEntity entity = response.getEntity();
				byte[] data = EntityUtils.toByteArray(entity);
				if (!finalEventFired.getAndSet(true)) {
					finalListener.onResourceEvent(new ResourceLoaderEvent<byte[]>(
							HttpComponentsAsynchUrlResource.this, data, true));
				} else {
					log.warn("response complete after final event was fired");
				}
			}

			@Override
			protected void onResponseFailed(HttpResponse response, int statusCode) {
				onError("resource not found(" + statusCode + ")");
			}

			@Override
			protected void saveSessionCookies(Header[] headers) {
				HttpComponentsAsynchUrlResource.this.saveSessionCookies(headers);
			}};

		updateHandlerForUrl(handler, name);

		httpTaskManager.addTask(new AbstractTask() {

			@Override
			public void execute() throws Exception {

				try {
					init();

					notifyStatusMessage(finalListener, "starting...");

					if (sessionRequest != null) {
						if (log.isDebugEnabled()) {
							log.debug("cancelling previous session request");
						}
						sessionRequest.cancel();
						sessionRequest = null;
					}
					if (log.isDebugEnabled()) {
						log.debug("connecting, name=[" + name + "]");
					}
					sessionRequest = ioReactor.connect(
							new InetSocketAddress(handler.getHostname(), handler.getPort()),
							null,
							handler,
							handler);
				} catch (Throwable e) {
					finalListener.onResourceEvent(new ResourceLoaderEvent<byte[]>(
							HttpComponentsAsynchUrlResource.this,
							new IOException("failed to load resource, name=[" + name + "], " + e, e)));
				}

			}});
	}

	/* (non-Javadoc)
	 * @see de.siteof.webpicturebrowser.loader.AbstractResource#getResourceBytes()
	 */
	@Override
	public byte[] getResourceBytes() throws IOException {
		byte[] data = null;
		try {
			final ObjectHolder<ResourceLoaderEvent<byte[]>> result =
				new ObjectHolder<ResourceLoaderEvent<byte[]>>();
			final CountDownLatch requestCount = new CountDownLatch(1);
			this.getResourceBytes(new IResourceListener<ResourceLoaderEvent<byte[]>>() {
				@Override
				public void onResourceEvent(ResourceLoaderEvent<byte[]> event) {
					result.setObject(event);
					if ((event.isComplete()) || (event.isFailed())) {
						requestCount.countDown();
					}
				}});
			requestCount.await();
			ResourceLoaderEvent<byte[]> event = result.getObject();
			if (event.isComplete()) {
				data = event.getResult();
			} else {
				Throwable cause = event.getCause();
				if (cause != null) {
					throw new IOException("failed to retrieve bytes - " + cause, cause);
				} else {
					throw new IOException("failed to retrieve bytes, name=[" + this.getName() + "]");
				}
			}
		} catch (InterruptedException e) {
			log.warn("request interrupted, name=[" + this.getName() + "]");
		}
		return data;
	}

}
