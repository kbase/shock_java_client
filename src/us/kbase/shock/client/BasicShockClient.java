package us.kbase.shock.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLContextBuilder;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.util.EntityUtils;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import us.kbase.auth.AuthToken;
import us.kbase.shock.client.exceptions.InvalidShockUrlException;
import us.kbase.shock.client.exceptions.ShockHttpException;
import us.kbase.shock.client.exceptions.ShockNoFileException;

/**
 * A basic client for shock. Creating nodes, deleting nodes,
 * getting a subset of node data, and altering read acls is currently supported.
 * 
 * Currently limited to 1000 connections.
 * 
 * @author gaprice@lbl.gov
 *
 */
public class BasicShockClient {
	
	private String version;
	private final URI baseurl;
	private final URI nodeurl;
	private static CloseableHttpClient client;
	private final ObjectMapper mapper = new ObjectMapper();
	private AuthToken token = null;
	
	private static final String AUTH = "Authorization";
	private static final String OAUTH = "OAuth ";
	private static final String ATTRIBFILE = "attribs";
	
	private static int CHUNK_SIZE = 50000000; //~50 Mb
	
	/** Get the size of the upload / download chunk size.
	 * @return the size of the file chunks sent/received from the Shock server.
	 */
	public static int getChunkSize() {
		return CHUNK_SIZE;
	}
	private static String getDownloadURLPrefix() {
		return "/?download&index=size&chunk_size=" + CHUNK_SIZE + "&part=";
	}
	
	private static synchronized void createHttpClient(
			final boolean allowSelfSignedCerts) {
		if (client != null) {
			return; //already done
		}
		if (allowSelfSignedCerts) {
			//http://stackoverflow.com/questions/19517538/ignoring-ssl-certificate-in-apache-httpclient-4-3
			final SSLConnectionSocketFactory sslsf;
			try {
				final SSLContextBuilder builder = new SSLContextBuilder();
				builder.loadTrustMaterial(null, new TrustSelfSignedStrategy());
				sslsf = new SSLConnectionSocketFactory(builder.build());
			} catch (NoSuchAlgorithmException e) {
				throw new RuntimeException("Unable to build http client", e);
			} catch (KeyStoreException e) {
				throw new RuntimeException("Unable to build http client", e);
			} catch (KeyManagementException e) {
				throw new RuntimeException("Unable to build http client", e);
			}

			final Registry<ConnectionSocketFactory> registry =
					RegistryBuilder.<ConnectionSocketFactory>create()
					.register("http", new PlainConnectionSocketFactory())
					.register("https", sslsf)
					.build();

			final PoolingHttpClientConnectionManager cm =
					new PoolingHttpClientConnectionManager(registry);
			cm.setMaxTotal(1000); //perhaps these should be configurable
			cm.setDefaultMaxPerRoute(1000);

			//TODO set timeouts for the client for 1/2m for conn req timeout and std timeout
			client = HttpClients.custom()
					.setSSLSocketFactory(sslsf)
					.setConnectionManager(cm)
					.build();
		} else {
			final PoolingHttpClientConnectionManager cm =
					new PoolingHttpClientConnectionManager();
			cm.setMaxTotal(1000); //perhaps these should be configurable
			cm.setDefaultMaxPerRoute(1000);
			//TODO set timeouts for the client for 1/2m for conn req timeout and std timeout
			client = HttpClients.custom()
					.setConnectionManager(cm)
					.build();
		}
	}
	
	/**
	 * Create a new shock client.
	 * @param url the location of the shock server.
	 * @throws IOException if an IO problem occurs.
	 * @throws InvalidShockUrlException if the <code>url</code> does not
	 * reference a shock server.
	 */
	public BasicShockClient(final URL url)
			throws IOException, InvalidShockUrlException {
		this(url, false);
	}
	
	/**
	 * Create a new shock client authorized to act as a shock user.
	 * @param url the location of the shock server.
	 * @param token the authorization token to present to shock.
	 * @throws IOException if an IO problem occurs.
	 * @throws InvalidShockUrlException if the <code>url</code> does not
	 * reference a shock server.
	 * @throws ShockHttpException if the connection to shock fails.
	 */
	public BasicShockClient(final URL url, final AuthToken token)
			throws IOException, InvalidShockUrlException,
			ShockHttpException {
		this(url, token, false);
	}
	
	/**
	 * Create a new shock client.
	 * @param url the location of the shock server.
	 * @param allowSelfSignedCerts <code>true</code> to permit self signed
	 * certificates when contacting servers.
	 * @throws IOException if an IO problem occurs.
	 * @throws InvalidShockUrlException if the <code>url</code> does not
	 * reference a shock server.
	 */
	public BasicShockClient(final URL url, boolean allowSelfSignedCerts)
			throws InvalidShockUrlException, IOException {
		
		createHttpClient(allowSelfSignedCerts);
		
		mapper.enable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT);
		
		String turl = url.getProtocol() + "://" + url.getAuthority() + url.getPath();
		if (turl.charAt(turl.length() - 1) != '/') {
			turl = turl + "/";
		}
		try {
			baseurl = new URL(turl).toURI();
		} catch (URISyntaxException use) {
			throw new RuntimeException(use); //something went badly wrong 
		}
		if (!(url.getProtocol().equals("http") ||
				url.getProtocol().equals("https"))) {
			throw new InvalidShockUrlException(turl.toString());
			
		}
		getRemoteVersion();
		nodeurl = baseurl.resolve("node/");
	}
	
	/**
	 * Create a new shock client authorized to act as a shock user.
	 * @param url the location of the shock server.
	 * @param token the authorization token to present to shock.
	 * @param allowSelfSignedCerts <code>true</code> to permit self signed
	 * certificates when contacting servers.
	 * @throws IOException if an IO problem occurs.
	 * @throws InvalidShockUrlException if the <code>url</code> does not
	 * reference a shock server.
	 * @throws ShockHttpException if the connection to shock fails.
	 */
	public BasicShockClient(
			final URL url,
			final AuthToken token,
			boolean allowSelfSignedCerts)
			throws InvalidShockUrlException, ShockHttpException, IOException {
		this(url, allowSelfSignedCerts);
		updateToken(token);
		if (token != null) { // test shock config/auth etc.
			final ShockNode sn = addNode();
			sn.delete();
		}
	}
	
	/**
	 * Replace the token this client presents to the shock server.
	 * @param token the new token
	 */
	public void updateToken(final AuthToken token) {
		if (token == null) {
			this.token = null;
			return;
		}
		this.token = token;
	}
	
	/** Get the auth token used by this client, if any.
	 * 
	 * @return the auth token.
	 */
	public AuthToken getToken() {
		return token;
	}
	
	/** 
	 * Get the url of the shock server this client communicates with.
	 * @return the shock url.
	 */
	public URL getShockUrl() {
		return uriToUrl(baseurl);
	}
	
	/** Get the version of the Shock server. This version is cached in the
	 * client on startup and after getRemoteVersion() is called.
	 * @return the version.
	 */
	public String getShockVersion() {
		return version;
	}
	
	/** Fetch the version from the Shock server and cache it client side.
	 * @return the version.
	 * @throws IOException if an IO error occurs.
	 * @throws InvalidShockUrlException if the url no longer points to a Shock
	 * server.
	 */
	public String getRemoteVersion() throws IOException,
			InvalidShockUrlException {
		final CloseableHttpResponse response = client.execute(new HttpGet(baseurl));
		final Map<String, Object> shockresp;
		try {
			@SuppressWarnings("unchecked")
			final Map<String, Object> respobj = mapper.readValue(
					response.getEntity().getContent(), Map.class);
			shockresp = respobj;
		} catch (JsonParseException jpe) {
			throw new InvalidShockUrlException(baseurl.toString(), jpe);
		} finally {
			response.close();
		}
		if (!shockresp.containsKey("id")) {
			throw new InvalidShockUrlException(baseurl.toString());
		}
		if (!shockresp.get("id").equals("Shock")) {
			throw new InvalidShockUrlException(baseurl.toString());
		}
		version = (String) shockresp.get("version");
		return version;
	}
	
	private <T extends ShockResponse> ShockData processRequest(
			final HttpRequestBase httpreq,
			final Class<T> clazz)
			throws IOException, ShockHttpException {
		authorize(httpreq);
		final CloseableHttpResponse response = client.execute(httpreq);
		try {
			return getShockData(response, clazz);
		} finally {
			response.close();
		}
	}
	
	private <T extends ShockResponse> ShockData getShockData(
			final HttpResponse response,
			final Class<T> clazz)
			throws IOException, ShockHttpException {
		try {
			return mapper.readValue(response.getEntity().getContent(), clazz).getShockData();
		} catch (JsonParseException jpe) {
			throw new ShockHttpException(
					response.getStatusLine().getStatusCode(),
					"Invalid Shock response. Server said " +
					response.getStatusLine().getStatusCode() + " " +
					response.getStatusLine().getReasonPhrase() + 
					". JSON parser said " + jpe.getLocalizedMessage(), jpe);
		}
	}
	
	private void authorize(final HttpRequestBase httpreq) {
		if (token != null) {
			httpreq.setHeader(AUTH, OAUTH + token.getToken());
		}
	}

	/** 
	 * Gets a node from the shock server. Note the object returned 
	 * represents the shock node's state at the time getNode() was called
	 * and does not update further.
	 * @param id the ID of the shock node.
	 * @return a shock node object.
	 * @throws IOException if an IO problem occurs.
	 * @throws ShockHttpException if the node could not be fetched from shock.
	 * expired.
	 */
	public ShockNode getNode(final ShockNodeId id) throws IOException, ShockHttpException {
		if (id == null) {
			throw new NullPointerException("id may not be null");
		}
		final URI targeturl = nodeurl.resolve(id.getId());
		final HttpGet htg = new HttpGet(targeturl);
		final ShockNode sn = (ShockNode) processRequest
				(htg, ShockNodeResponse.class);
		sn.addClient(this);
		return sn;
	}
	
	/**
	 * Equivalent to client.getFile(client.getNode(id), file)
	 * @param id the ID of the shock node.
	 * @param file the stream to which the file will be written.
	 * @throws IOException if an IO problem occurs.
	 * @throws ShockHttpException if the file could not be fetched from shock.
	 */
	public void getFile(final ShockNodeId id, final OutputStream file)
			throws IOException, ShockHttpException {
		getFile(getNode(id), file);
	}
	
	/**
	 * Get the file for this shock node.
	 * @param sn the shock node from which to retrieve the file.
	 * @param os the stream to which the file will be written.
	 * @throws IOException if an IO problem occurs.
	 * @throws ShockHttpException if the file could not be fetched from shock.
	 */
	public void getFile(final ShockNode sn, final OutputStream os)
			throws IOException, ShockHttpException {
		if (os == null) {
			throw new NullPointerException("os");
		}
		final int chunks = getChunks(sn);
		final URI targeturl = nodeurl.resolve(sn.getId().getId() +
				getDownloadURLPrefix());
		for (int i = 0; i < chunks; i++) {
			final HttpGet htg = new HttpGet(targeturl.toString() + (i + 1));
			authorize(htg);
			final CloseableHttpResponse response = client.execute(htg);
			try {
				final int code = response.getStatusLine().getStatusCode();
				if (code > 299) {
					getShockData(response, ShockNodeResponse.class); //trigger errors
				}
				os.write(EntityUtils.toByteArray(response.getEntity()));
			} finally {
				response.close();
			}
		}
	}
	
	private static int getChunks(final ShockNode sn)
			throws ShockNoFileException {
		if (sn == null) {
			throw new NullPointerException("sn");
		}
		if (sn.getFileInformation().getSize() == 0) {
			throw new ShockNoFileException(400, "Node has no file");
		}
		final BigDecimal size = new BigDecimal(
				sn.getFileInformation().getSize());
		//if there are more than 2^32 chunks we're in big trouble
		return size.divide(new BigDecimal(CHUNK_SIZE))
				.setScale(0, BigDecimal.ROUND_CEILING).intValueExact();
	}
	
	/**
	 * Equivalent to client.getFile(client.getNode(id))
	 * @param id the ID of the shock node.
	 * @return an input stream containing the file.
	 * @throws IOException if an IO problem occurs.
	 * @throws ShockHttpException if the file could not be fetched from shock.
	 */
	public InputStream getFile(final ShockNodeId id)
			throws IOException, ShockHttpException {
		return getFile(getNode(id));
	}
	
	/** Get the file for this shock node. The input stream this function
	 * returns is naturally buffered.
	 * @param sn the shock node from which to retrieve the file.
	 * @return an input stream containing the file.
	 * @throws IOException if an IO problem occurs.
	 * @throws ShockHttpException if the file could not be fetched from shock.
	 */
	public InputStream getFile(final ShockNode sn)
			throws ShockHttpException, IOException {
		return new ShockFileInputStream(sn);
	}
	
	private class ShockFileInputStream extends InputStream {
		
		private final URI targeturl;
		private final int chunks;
		private int chunkCount = 0;
		private byte[] chunk;
		private int pos = 0;
		private boolean closed = false;
		
		public ShockFileInputStream(final ShockNode sn)
				throws ShockHttpException, IOException {
			chunks = getChunks(sn);
			targeturl = nodeurl.resolve(sn.getId().getId() +
					getDownloadURLPrefix());
			getNextChunk(); // must be at least one
		}
		private void getNextChunk() throws IOException, ShockHttpException {
			if (chunkCount >= chunks) {
				chunk = null;
				return;
			}
			final HttpGet htg = new HttpGet(targeturl.toString() +
					(chunkCount + 1));
			authorize(htg);
			final CloseableHttpResponse response = client.execute(htg);
			try {
				final int code = response.getStatusLine().getStatusCode();
				if (code > 299) {
					getShockData(response, ShockNodeResponse.class); //trigger errors
				}
				chunk = EntityUtils.toByteArray(response.getEntity());
				chunkCount++;
				pos = 0;
			} finally {
				response.close();
			}
		}

		@Override
		public int read() throws IOException {
			if (closed) {
				throw new IOException("Stream is closed.");
			}
			if (chunk == null) {
				return -1;
			}
			final int i = chunk[pos] & 0xFF;
			pos++;
			if (pos >= chunk.length) {
				getNextChunkWrapExcep();
			}
			return i;
		}

		private void getNextChunkWrapExcep() throws IOException {
			try {
				getNextChunk();
			} catch (ShockHttpException e) {
				throw new IOException("Couldn't fetch data from Shock: " +
						e.getMessage(), e);
			}
		}
		
		@Override
		public int read(byte b[], int off, int len) throws IOException {
			if (closed) {
				throw new IOException("Stream is closed.");
			}
			if (b == null) {
				throw new NullPointerException();
			} else if (off < 0 || len < 0 || len > b.length - off) {
				throw new IndexOutOfBoundsException();
			} else if (len == 0) {
				return 0;
			} else if (chunk == null) {
				return -1;
			}
			if (pos + len >= chunk.length) {
				System.arraycopy(chunk, pos, b, off, chunk.length - pos);
				final int size = chunk.length - pos;
				getNextChunkWrapExcep(); // sets chunk to null
				return size;
			} else {
				System.arraycopy(chunk, pos, b, off, len);
				pos += len;
				return len;
			}
		}
		
		@Override
		public void close() {
			closed = true;
			chunk = null;
		}
	}
	
	/**
	 * Creates an empty node on the shock server.
	 * @return a shock node object.
	 * @throws IOException if an IO problem occurs.
	 * @throws ShockHttpException if the node could not be created.
	 */
	public ShockNode addNode() throws IOException, ShockHttpException {
		return _addNode(null, null, null, null);
	}
	
	/**
	 * Creates a node on the shock server with user-specified attributes.
	 * @param attributes the user-specified attributes. The attributes must be serializable to
	 * JSON.
	 * @return a shock node object.
	 * @throws IOException if an IO problem occurs.
	 * @throws ShockHttpException if the node could not be created.
	 * @throws JsonProcessingException if the <code>attributes</code> could
	 * not be serialized to JSON.
	 */
	public ShockNode addNode(final Object attributes)
			throws IOException, ShockHttpException, JsonProcessingException {
		if (attributes == null) {
			throw new IllegalArgumentException("attributes may not be null");
		}
		return _addNode(attributes, null, null, null);
	}
	
	/**
	 * Creates a node on the shock server containing a file.
	 * @param file the file data.
	 * @param filename the name of the file.
	 * @param format the format of the file, e.g. ASCII, UTF-8, JSON. Ignored
	 * if null.
	 * @return a shock node object.
	 * @throws IOException if an IO problem occurs.
	 * @throws ShockHttpException if the node could not be created.
	 */
	public ShockNode addNode(final InputStream file, final String filename, final String format)
			throws IOException, ShockHttpException {
		if (file == null) {
			throw new IllegalArgumentException("file may not be null");
		}
		if (filename == null || filename.isEmpty()) {
			throw new IllegalArgumentException(
					"filename may not be null or empty");
		}
		return _addNodeStreaming(null, file, filename, format);
	}
	
	/**
	 * Creates a node on the shock server with user-specified attributes and 
	 * a file.
	 * @param attributes the user-specified attributes. The attributes must be serializable to
	 * JSON.
	 * @param file the file data.
	 * @param filename the name of the file.
	 * @param format the format of the file, e.g. ASCII, UTF-8, JSON. Ignored
	 * if null.
	 * @return a shock node object.
	 * @throws IOException if an IO problem occurs.
	 * @throws ShockHttpException if the node could not be created.
	 * @throws JsonProcessingException if the <code>attributes</code> could
	 * not be serialized to JSON.
	 */
	public ShockNode addNode(
			final Object attributes,
			final InputStream file,
			final String filename,
			final String format)
			throws IOException, ShockHttpException, JsonProcessingException {
		if (attributes == null) {
			throw new IllegalArgumentException("attributes may not be null");
		}
		if (file == null) {
			throw new IllegalArgumentException("file may not be null");
		}
		if (filename == null || filename.isEmpty()) {
			throw new IllegalArgumentException(
					"filename may not be null or empty");
		}
		return _addNodeStreaming(attributes, file, filename, format);
	}
	
	private ShockNode _addNode(
			final Object attributes,
			final byte[] file,
			final String filename,
			final String format)
			throws IOException, ShockHttpException, JsonProcessingException {
		final HttpPost htp = new HttpPost(nodeurl);
		if (attributes != null || file != null) {
			final MultipartEntityBuilder mpeb = MultipartEntityBuilder.create();
			if (attributes != null) {
				final byte[] attribs = mapper.writeValueAsBytes(attributes);
				mpeb.addBinaryBody("attributes", attribs,
						ContentType.APPLICATION_JSON, ATTRIBFILE);
			}
			if (file != null) {
				mpeb.addBinaryBody("upload", file, ContentType.DEFAULT_BINARY,
						filename);
			}
			if (format != null) {
				mpeb.addTextBody("format", format);
			}
			htp.setEntity(mpeb.build());
		}
		final ShockNode sn = (ShockNode) processRequest(htp,
				ShockNodeResponse.class);
		sn.addClient(this);
		return sn;
	}
	
	private ShockNode _addNodeStreaming(
			final Object attributes,
			final InputStream file,
			final String filename,
			final String format)
			throws IOException, ShockHttpException, JsonProcessingException {
		byte[] b = new byte[CHUNK_SIZE];
		int read = read(file, b);
		if (read < CHUNK_SIZE) {
			return _addNode(attributes, Arrays.copyOf(b, read), filename,
					format);
		}
		int chunks = 1;
		ShockNode sn;
		{
			final HttpPost htp = new HttpPost(nodeurl);
			final MultipartEntityBuilder mpeb = MultipartEntityBuilder.create();
			mpeb.addTextBody("parts", "unknown");
			if (attributes != null) {
				final byte[] attribs = mapper.writeValueAsBytes(attributes);
				mpeb.addBinaryBody("attributes", attribs,
						ContentType.APPLICATION_JSON, ATTRIBFILE);
			}
			if (format != null && !format.isEmpty()) {
				mpeb.addTextBody("format", format);
			}
			htp.setEntity(mpeb.build());
			sn = (ShockNode) processRequest(htp, ShockNodeResponse.class);
		}
		final URI targeturl = nodeurl.resolve(sn.getId().getId());
		while (read > 0) {
			final HttpPut htp = new HttpPut(targeturl);
			if (read < CHUNK_SIZE) {
				b = Arrays.copyOf(b, read);
			}
			final MultipartEntityBuilder mpeb = MultipartEntityBuilder.create();
			mpeb.addBinaryBody("" + chunks, b, ContentType.DEFAULT_BINARY, filename);
			htp.setEntity(mpeb.build());
			processRequest(htp, ShockNodeResponse.class);
			b = new byte[CHUNK_SIZE]; // could just zero it
			read = read(file, b);
			chunks++;
		}
		{
			final HttpPut htp = new HttpPut(targeturl);
			final MultipartEntityBuilder mpeb = MultipartEntityBuilder.create();
			mpeb.addTextBody("parts", "close");
			mpeb.addTextBody("file_name", filename);
			htp.setEntity(mpeb.build());
			sn = (ShockNode) processRequest(htp, ShockNodeResponse.class);
		}
		sn.addClient(this);
		return sn;
	}
	
	private int read(final InputStream file, final byte[] b) throws IOException {
		int pos = 0;
		while (pos < b.length) {
			final int read = file.read(b, pos, b.length - pos);
			if (read == -1) {
				break;
			}
			pos += read;
		}
		return pos;
	}
	
	/** Makes a copy of a shock node, including the indexes and attributes, owned by the user.
	 * @param id the ID of the shock node to copy.
	 * @param unlessAlreadyOwned if true and the shock node is already owned by the user,
	 * don't make a copy.
	 * @return the new shock node, or the node from the id if unlessAlreadyOwned is true and
	 * the user already owns the node.
	 * @throws ShockHttpException if a Shock exception occurs.
	 * @throws IOException if an IO exception occurs.
	 */
	public ShockNode copyNode(final ShockNodeId id, final boolean unlessAlreadyOwned)
			throws ShockHttpException, IOException {
		final ShockNode source = getNode(id);
		/* So it's possible to construct a token where the name is not the correct name for
		 * the token. In this case though, the user is just screwing themselves because at 
		 * this point all that'll happen is they'll make a copy when they didn't want to or
		 * vice versa, since the line above already guarantees they can read the node.
		 */
		if (unlessAlreadyOwned &&
				source.getACLs().getOwner().getUsername().equals(token.getUserName())) {
			return source;
		}
		final HttpPost htp = new HttpPost(nodeurl);
		final MultipartEntityBuilder mpeb = MultipartEntityBuilder.create();
		mpeb.addTextBody("copy_data", id.getId());
		mpeb.addTextBody("copy_indexes", "1");
		htp.setEntity(mpeb.build());
		ShockNode sn = (ShockNode) processRequest(htp, ShockNodeResponse.class);
		if (source.getAttributes() != null) {
			// as of shock 0.9.13 copy_attributes=1 will copy the attributes, but until then...
			final HttpPut put = new HttpPut(nodeurl.resolve(sn.getId().getId()));
			final MultipartEntityBuilder mpeb2 = MultipartEntityBuilder.create();
			final byte[] attribs = mapper.writeValueAsBytes(source.getAttributes());
			mpeb2.addBinaryBody("attributes", attribs, ContentType.APPLICATION_JSON, ATTRIBFILE);
			put.setEntity(mpeb2.build());
			sn = (ShockNode) processRequest(put, ShockNodeResponse.class);
		}
		sn.addClient(this);
		return sn;
	}
	
	/**
	 * Deletes a node on the shock server.
	 * @param id the node to delete.
	 * @throws IOException if an IO problem occurs.
	 * @throws ShockHttpException if the node could not be deleted.
	 */
	public void deleteNode(final ShockNodeId id)
			throws IOException, ShockHttpException {
		final URI targeturl = nodeurl.resolve(id.getId());
		final HttpDelete htd = new HttpDelete(targeturl);
		processRequest(htd, ShockNodeResponse.class); //triggers throwing errors
	}
	
	/** Add users to a node's ACLs.
	 * @param id the node to update.
	 * @param users the users to add to the ACL.
	 * @param aclType the ACL to which the users should be added.
	 * @return the new ACL
	 * @throws ShockHttpException if a shock error occurs.
	 * @throws IOException if an IO error occurs.
	 */
	public ShockACL addToNodeAcl(
			final ShockNodeId id,
			final List<String> users,
			final ShockACLType aclType)
			throws ShockHttpException, IOException {
		final URI targeturl = checkACLArgsAndGenURI(id, users, aclType);
		final HttpPut htp = new HttpPut(targeturl);
		return (ShockACL) processRequest(htp, ShockACLResponse.class);
	}
	
	/** Remove users to a node's ACLs.
	 * @param id the node to update.
	 * @param users the users to remove from the ACL.
	 * @param aclType the ACL to which the users should be removed.
	 * @return the new ACL.
	 * @throws ShockHttpException if a shock error occurs.
	 * @throws IOException if an IO error occurs.
	 */
	public ShockACL removeFromNodeAcl(
			final ShockNodeId id,
			final List<String> users,
			final ShockACLType aclType)
			throws ShockHttpException, IOException {
		final URI targeturl = checkACLArgsAndGenURI(id, users, aclType);
		final HttpDelete htd = new HttpDelete(targeturl);
		return (ShockACL) processRequest(htd, ShockACLResponse.class);
	}
	
	private URI checkACLArgsAndGenURI(
			final ShockNodeId id,
			final List<String> users,
			final ShockACLType aclType) {
		if (id == null) {
			throw new NullPointerException("id cannot be null");
		}
		if (users == null || users.isEmpty()) {
			throw new IllegalArgumentException(
					"user list cannot be null or empty");
		}
		if (aclType == null) {
			throw new NullPointerException("aclType cannot be null");
		}
		for (final String user: users) {
			if (user == null || user.equals("")) {
				throw new IllegalArgumentException(
						"user cannot be null or the empty string");
			}
		}
		final URI targeturl = nodeurl.resolve(id.getId() +
				aclType.getUrlFragmentForAcl() + "?users=" +
				StringUtils.join(users, ",") + ";verbosity=full");
		return targeturl;
	}
	
	/** Set a node publicly readable.
	 * @param id the ID of the node to set readable.
	 * @param publicRead true to set publicly readable, false to set private.
	 * @return the new ACLs.
	 * @throws ShockHttpException if a shock error occurs.
	 * @throws IOException if an IO error occurs.
	 */
	public ShockACL setPubliclyReadable(
			final ShockNodeId id,
			final boolean publicRead)
			throws ShockHttpException, IOException {
		if (id == null) {
			throw new NullPointerException("id");
		}
		final URI targeturl = nodeurl.resolve(id.getId() +
				// parameterize this if we support public write & delete,
				// which seems like a bad idea to me
				"/acl/public_read?verbosity=full");
		final HttpRequestBase req;
		if (publicRead) {
			req = new HttpPut(targeturl);
		} else {
			req = new HttpDelete(targeturl);
		}
		return (ShockACL) processRequest(req, ShockACLResponse.class);
	}
	
	/**
	 * Retrieves the access control lists (ACLs) from the shock server for
	 * a node. Note the object returned represents the shock node's state at
	 * the time getACLs() was called and does not update further.
	 * @param id the node to query.
	 * @return the ACLs for the node.
	 * @throws IOException if an IO problem occurs.
	 * @throws ShockHttpException if the node's access control lists could not
	 * be retrieved.
	 */
	public ShockACL getACLs(final ShockNodeId id)
			throws IOException, ShockHttpException {
		final URI targeturl = nodeurl.resolve(id.getId() + "/acl/?verbosity=full");
		final HttpGet htg = new HttpGet(targeturl);
		return (ShockACL) processRequest(htg, ShockACLResponse.class);
	}
	
	//for known good uris ONLY
	private URL uriToUrl(final URI uri) {
		try {
			return uri.toURL();
		} catch (MalformedURLException mue) {
			throw new RuntimeException(mue); //something is seriously fuxxored
		}
	}
}
