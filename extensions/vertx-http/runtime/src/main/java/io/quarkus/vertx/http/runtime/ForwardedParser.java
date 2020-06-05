/*
 * Copyright 2014 Red Hat, Inc.
 *
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  and Apache License v2.0 which accompanies this distribution.
 *
 *  The Eclipse Public License is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 *
 *  The Apache License v2.0 is available at
 *  http://www.opensource.org/licenses/apache2.0.php
 *
 *  You may elect to redistribute this code under either of these licenses.
 */

// This code was Heavily influenced from spring forward header parser
// https://github.com/spring-projects/spring-framework/blob/master/spring-web/src/main/java/org/springframework/web/util/UriComponentsBuilder.java#L849

package io.quarkus.vertx.http.runtime;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jboss.logging.Logger;

import io.netty.util.AsciiString;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.SocketAddress;
import io.vertx.core.net.impl.SocketAddressImpl;

class ForwardedParser {
    private static final Logger log = Logger.getLogger(ForwardedParser.class);

    private static final String HTTP_SCHEME = "http";
    private static final String HTTPS_SCHEME = "https";
    private static final AsciiString FORWARDED = AsciiString.cached("Forwarded");
    private static final AsciiString X_FORWARDED_SSL = AsciiString.cached("X-Forwarded-Ssl");
    private static final AsciiString X_FORWARDED_PROTO = AsciiString.cached("X-Forwarded-Proto");
    private static final AsciiString X_FORWARDED_HOST = AsciiString.cached("X-Forwarded-Host");
    private static final AsciiString X_FORWARDED_PORT = AsciiString.cached("X-Forwarded-Port");
    private static final AsciiString X_FORWARDED_FOR = AsciiString.cached("X-Forwarded-For");
    private static final AsciiString X_FORWARDED_PREFIX = AsciiString.cached("X-Forwarded-Prefix");
    private static final AsciiString X_FORWARDED_SERVER = AsciiString.cached("X-Forwarded-Server");

    private static final Pattern FORWARDED_HOST_PATTERN = Pattern.compile("host=\"?([^;,\"]+)\"?");
    private static final Pattern FORWARDED_PROTO_PATTERN = Pattern.compile("proto=\"?([^;,\"]+)\"?");
    private static final Pattern FORWARDED_FOR_PATTERN = Pattern.compile("for=\"?([^;,\"]+)\"?");
    private static final Pattern FORWARDED_PREFIX_PATTERN = Pattern.compile("prefix=\"?([^;,\"]+)\"?");

    private final HttpServerRequest delegate;
    private final boolean allowForward;

    private boolean calculated;
    private String host;
    private int port = -1;
    private String scheme;
    private String uri;
    private String absoluteURI;
    private SocketAddress remoteAddress;

    ForwardedParser(HttpServerRequest delegate, boolean allowForward) {
        this.delegate = delegate;
        this.allowForward = allowForward;
    }

    public String scheme() {
        if (!calculated)
            calculate();
        return scheme;
    }

    String host() {
        if (!calculated)
            calculate();
        return host;
    }

    boolean isSSL() {
        if (!calculated)
            calculate();

        return scheme.equals(HTTPS_SCHEME);
    }

    String uri() {
        if (!calculated)
            calculate();

        return uri;
    }

    String absoluteURI() {
        if (!calculated)
            calculate();

        return absoluteURI;
    }

    SocketAddress remoteAddress() {
        if (!calculated)
            calculate();

        return remoteAddress;
    }

    private void calculate() {
        calculated = true;
        remoteAddress = delegate.remoteAddress();
        scheme = delegate.scheme();
        uri = delegate.uri();
        setHostAndPort(delegate.host(), port);

        String forwardedSsl = delegate.getHeader(X_FORWARDED_SSL);
        boolean isForwardedSslOn = forwardedSsl != null && forwardedSsl.equalsIgnoreCase("on");

        String forwarded = delegate.getHeader(FORWARDED);
        if (allowForward && forwarded != null) {
            String forwardedToUse = forwarded.split(",")[0];
            Matcher matcher = FORWARDED_PROTO_PATTERN.matcher(forwardedToUse);
            if (matcher.find()) {
                scheme = (matcher.group(1).trim());
                port = -1;
            } else if (isForwardedSslOn) {
                scheme = HTTPS_SCHEME;
                port = -1;
            }

            matcher = FORWARDED_HOST_PATTERN.matcher(forwardedToUse);
            if (matcher.find()) {
                setHostAndPort(matcher.group(1).trim(), port);
            }

            matcher = FORWARDED_FOR_PATTERN.matcher(forwardedToUse);
            if (matcher.find()) {
                remoteAddress = parseFor(matcher.group(1).trim(), remoteAddress.port());
            }

            matcher = FORWARDED_PREFIX_PATTERN.matcher(forwardedToUse);
            if (matcher.find()) {
                uri = appendPrefixToUri(matcher.group(1).trim(), delegate.uri());
            }
        } else if (!allowForward) {
            String protocolHeader = delegate.getHeader(X_FORWARDED_PROTO);
            if (protocolHeader != null) {
                scheme = protocolHeader.split(",")[0];
                port = -1;
            } else if (isForwardedSslOn) {
                scheme = HTTPS_SCHEME;
                port = -1;
            }

            String hostHeader = delegate.getHeader(X_FORWARDED_HOST);
            if (hostHeader != null) {
                setHostAndPort(hostHeader.split(",")[0], port);
            } else {
                String serverHeader = delegate.getHeader(X_FORWARDED_SERVER);
                if (serverHeader != null) {
                    setHostAndPort(serverHeader.split(",")[0], port);
                }
            }

            String portHeader = delegate.getHeader(X_FORWARDED_PORT);
            if (portHeader != null) {
                port = parsePort(portHeader.split(",")[0], port);
            }

            String forHeader = delegate.getHeader(X_FORWARDED_FOR);
            if (forHeader != null) {
                remoteAddress = parseFor(forHeader.split(",")[0], remoteAddress.port());
            }

            String prefixHeader = delegate.getHeader(X_FORWARDED_PREFIX);
            if (prefixHeader != null) {
                uri = appendPrefixToUri(prefixHeader, uri);
            }
        }

        if (((scheme.equals(HTTP_SCHEME) && port == 80) || (scheme.equals(HTTPS_SCHEME) && port == 443))) {
            port = -1;
        }

        host = host + (port >= 0 ? ":" + port : "");
        delegate.headers().set(HttpHeaders.HOST, host);
        absoluteURI = scheme + "://" + host + uri;
        log.debug("Recalculated absoluteURI to " + absoluteURI);
    }

    private void setHostAndPort(String hostToParse, int defaultPort) {
        int portSeparatorIdx = hostToParse.lastIndexOf(':');
        if (portSeparatorIdx > hostToParse.lastIndexOf(']')) {
            host = hostToParse.substring(0, portSeparatorIdx);
            delegate.headers().set(HttpHeaders.HOST, host);
            port = parsePort(hostToParse.substring(portSeparatorIdx + 1), defaultPort);
        } else {
            host = hostToParse;
            port = -1;
        }
    }

    private SocketAddress parseFor(String forToParse, int defaultPort) {
        String host = forToParse;
        int port = defaultPort;
        int portSeparatorIdx = forToParse.lastIndexOf(':');
        if (portSeparatorIdx > forToParse.lastIndexOf(']')) {
            host = forToParse.substring(0, portSeparatorIdx);
            port = parsePort(forToParse.substring(portSeparatorIdx + 1), defaultPort);
        }

        return new SocketAddressImpl(port, host);
    }

    private int parsePort(String portToParse, int defaultPort) {
        try {
            return Integer.parseInt(portToParse);
        } catch (NumberFormatException ignored) {
            log.error("Failed to parse a port from \"forwarded\"-type headers.");
            return defaultPort;
        }
    }

    private String appendPrefixToUri(String prefix, String uri) {
        return stripTailingSlash(prefix) + uri;
    }

    private String stripTailingSlash(String url) {
        if (url.endsWith("/")) {
            return url.substring(0, url.length() - 1);
        } else {
            return url;
        }
    }
}
