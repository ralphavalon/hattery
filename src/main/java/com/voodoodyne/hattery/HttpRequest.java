/*
 * Copyright (c) 2010 Jeff Schnitzer.
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.voodoodyne.hattery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.BaseEncoding;
import com.google.common.io.ByteStreams;
import com.voodoodyne.hattery.util.MultipartWriter;
import com.voodoodyne.hattery.util.TeeOutputStream;
import com.voodoodyne.hattery.util.UrlUtils;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * <p>Immutable definition of a request; methods return new immutable object with the data changed.</p>
 * 
 * @author Jeff Schnitzer
 */
@Data
@AllArgsConstructor(access = AccessLevel.PACKAGE)
@Slf4j
@ToString(exclude = "mapper")	// string version is useless and noisy
public class HttpRequest {

	/** */
	public static final String APPLICATION_JSON = "application/json";
	public static final String APPLICATION_X_WWW_FORM_URLENCODED = "application/x-www-form-urlencoded; charset=utf-8";

	/** Just the first part of it for matching */
	private static final String APPLICATION_X_WWW_FORM_URLENCODED_BEGINNING = APPLICATION_X_WWW_FORM_URLENCODED.split(" ")[0];

	/** */
	private final Transport transport;

	/** */
	private final String method;

	/** URL so far; can be extended with path() */
	private final String url;

	/** value will be either String, Collection<String>, or BinaryAttachment */
	private final Map<String, Object> params;

	/** */
	private final String contentType;

	/** Object to be jsonfied */
	private final Object body;

	/** */
	private final Map<String, String> headers;

	/** 0 for no explicit timeout (aka default) */
	private final int timeout;

	/** 0 for no retries */
	private final int retries;

	/** */
	private final ObjectMapper mapper;

	/**
	 * Default values
	 */
	public HttpRequest(Transport transport) {
		this.transport = transport;
		this.method = HttpMethod.GET.name();
		this.url = null;
		this.params = Collections.emptyMap();
		this.headers = Collections.emptyMap();
		this.timeout = 0;
		this.retries = 0;
		this.mapper = new ObjectMapper();
		this.contentType = null;
		this.body = null;
	}

	/** */
	public HttpRequest method(String method) {
		Preconditions.checkNotNull(method);
		return new HttpRequest(transport, method, url, params, contentType, body, headers, timeout, retries, mapper);
	}

	/** */
	public HttpRequest method(HttpMethod method) {
		return method(method.name());
	}

	/** Shortcut for method(HttpMethod.GET) */
	public HttpRequest GET() {
		return method(HttpMethod.GET);
	}

	/** Shortcut for method(HttpMethod.POST) */
	public HttpRequest POST() {
		return method(HttpMethod.POST);
	}

	/** Shortcut for method(HttpMethod.PUT) */
	public HttpRequest PUT() {
		return method(HttpMethod.PUT);
	}

	/** Shortcut for method(HttpMethod.DELETE) */
	public HttpRequest DELETE() {
		return method(HttpMethod.DELETE);
	}

	/**
	 * Replaces the existing url wholesale
	 */
	public HttpRequest url(String url) {
		Preconditions.checkNotNull(url);
		return new HttpRequest(transport, method, url, params, contentType, body, headers, timeout, retries, mapper);
	}

	/**
	 * Appends path to the existing url. If no url is not set, this becomes the url.
	 * Ensures this is a separate path segment by adding or removing a leading '/' if necessary.
	 * @path is converted to a string via toString()
	 */
	public HttpRequest path(Object path) {
		Preconditions.checkNotNull(path);
		String url2 = (url == null) ? path.toString() : concatPath(url, path.toString());
		return url(url2);
	}

	/** Check for slashes */
	private String concatPath(String url, String path) {
		if (url.endsWith("/")) {
			return path.startsWith("/") ? (url + path.substring(1)) : (url + path);
		} else {
			return path.startsWith("/") ? (url + path) : (url + '/' + path);
		}
	}

	/**
	 * Set/override the parameter with a single value
	 * @return the updated, immutable request
	 */
	public HttpRequest param(String name, Object value) {
		return paramAnything(name, value);
	}

	/**
	 * Set/override the parameter with a list of values
	 * @return the updated, immutable request
	 */
	public HttpRequest param(String name, List<Object> value) {
		return paramAnything(name, ImmutableList.copyOf(value));
	}

	/**
	 * Set/override the parameters
	 * @return the updated, immutable request
	 */
	public HttpRequest param(Param... params) {
		HttpRequest here = this;
		for (Param param: params)
			here = paramAnything(param.getName(), param.getValue());

		return here;
	}

	/**
	 * Set/override the parameter with a binary attachment.
	 */
	public HttpRequest param(String name, InputStream stream, String contentType, String filename) {
		final BinaryAttachment attachment = new BinaryAttachment(stream, contentType, filename);
		return POST().paramAnything(name, attachment);
	}

	/** Private implementation lets us add anything, but don't expose that to the world */
	private HttpRequest paramAnything(String name, Object value) {
		final ImmutableMap<String, Object> params = new ImmutableMap.Builder<String, Object>().putAll(this.params).put(name, value).build();
		return new HttpRequest(transport, method, url, params, contentType, body, headers, timeout, retries, mapper);
	}

	/**
	 * Provide a body that will be turned into JSON.
	 */
	public HttpRequest body(Object body) {
		return new HttpRequest(transport, method, url, params, contentType, body, headers, timeout, retries, mapper);
	}
	
	/**
	 * Sets/overrides a header.  Value is not encoded in any particular way.
	 */
	public HttpRequest header(String name, String value) {
		final ImmutableMap<String, String> headers = new ImmutableMap.Builder<String, String>().putAll(this.headers).put(name, value).build();
		return new HttpRequest(transport, method, url, params, contentType, body, headers, timeout, retries, mapper);
	}
	
	/**
	 * Set a connection/read timeout in milliseconds, or 0 for no/default timeout.
	 */
	public HttpRequest timeout(int timeout) {
		return new HttpRequest(transport, method, url, params, contentType, body, headers, timeout, retries, mapper);
	}

	/**
	 * Set a retry count, or 0 for no retries
	 */
	public HttpRequest retries(int retries) {
		return new HttpRequest(transport, method, url, params, contentType, body, headers, timeout, retries, mapper);
	}

	/**
	 * Set the mapper. Be somewhat careful here, ObjectMappers are themselves not immutable (sigh).
	 */
	public HttpRequest mapper(ObjectMapper mapper) {
		return new HttpRequest(transport, method, url, params, contentType, body, headers, timeout, retries, mapper);
	}

	/**
	 * Set the basic auth header
	 */
	public HttpRequest basicAuth(String username, String password) {
		final String basic = username + ':' + password;

		// There is no standard for charset, might as well use utf-8
		final byte[] bytes = basic.getBytes(StandardCharsets.UTF_8);

		return header("Authorization", "Basic " + BaseEncoding.base64().encode(bytes));
	}

	/**
	 * Execute the request, providing the result in the response object - which might be an async wrapper, depending
	 * on the transport.
	 */
	public HttpResponse fetch() {
		Preconditions.checkState(url != null);

		log.debug("Fetching {}", this);
		log.debug("{} {}", getMethod(), getUrlComplete());

		try {
			return new HttpResponse(getTransport().fetch(this), getMapper());
		} catch (IOException e) {
			throw new IORException(e);
		}
	}

	/**
	 * @return the actual url for this request, with appropriate parameters
	 */
	public String getUrlComplete() {
		if (paramsAreInContent()) {
			return getUrl();
		} else {
			final String queryString = getQuery();
			return queryString.isEmpty() ? getUrl() : (getUrl() + "?" + queryString);
		}
	}

	/**
	 * @return the content type which should be submitted along with this data, of null if not present (ie a GET)
	 */
	public String getContentType() {
		if (contentType != null)
			return contentType;

		if (body != null)
			return APPLICATION_JSON;

		if (isPOST()) {
			if (hasBinaryAttachments()) {
				return MultipartWriter.CONTENT_TYPE;
			} else {
				return APPLICATION_X_WWW_FORM_URLENCODED;
			}
		} else {
			return null;
		}
	}

	/**
	 * Write any body content, if appropriate
	 */
	public void writeBody(OutputStream output) throws IOException {
		if (log.isDebugEnabled()) {
			output = new TeeOutputStream(output, new ByteArrayOutputStream());
		}

		final String ctype = getContentType();

		if (MultipartWriter.CONTENT_TYPE.equals(ctype)) {
			MultipartWriter writer = new MultipartWriter(output);
			writer.write(params);
		}
		else if (ctype != null && ctype.startsWith(APPLICATION_X_WWW_FORM_URLENCODED_BEGINNING)) {
			final String queryString = getQuery();
			output.write(queryString.getBytes(StandardCharsets.UTF_8));
		}
		else if (body instanceof byte[]) {
			output.write((byte[])body);
		}
		else if (body instanceof InputStream) {
			ByteStreams.copy((InputStream)body, output);
		}
		else if (APPLICATION_JSON.equals(ctype)) {
			mapper.writeValue(output, body);
		}

		if (log.isDebugEnabled()) {
			byte[] bytes = ((ByteArrayOutputStream)((TeeOutputStream)output).getTwo()).toByteArray();
			if (bytes.length > 0)
				log.debug("Wrote body: {}", new String(bytes, StandardCharsets.UTF_8));	// not necessarily utf8 but best choice available
		}
	}

	/** POST has a lot of special cases, so this is convenient */
	public boolean isPOST() {
		return HttpMethod.POST.name().equals(getMethod());
	}

	/** For some types, params go in the body (not on the url) */
	private boolean paramsAreInContent() {
		final String ctype = getContentType();
		return (ctype != null && ctype.startsWith(APPLICATION_X_WWW_FORM_URLENCODED_BEGINNING));
	}

	/** @return true if there are any binary attachments in the parameters */
	private boolean hasBinaryAttachments() {
		for (Object value: getParams().values())
			if (value instanceof BinaryAttachment)
				return true;

		return false;
	}

	/**
	 * Creates a string representing the current query string, or an empty string if there are no parameters.
	 * Will not work if there are binary attachments!
	 */
	public String getQuery() {

		if (this.getParams().isEmpty())
			return "";
		
		StringBuilder bld = null;
		
		for (Map.Entry<String, Object> param: this.params.entrySet()) {
			if (bld == null)
				bld = new StringBuilder();
			else
				bld.append('&');
			
			bld.append(UrlUtils.urlEncode(param.getKey()));
			bld.append('=');
			bld.append(UrlUtils.urlEncode(param.getValue().toString()));
		}
		
		return bld.toString();
	}
}