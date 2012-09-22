/*
 * JBoss, Home of Professional Open Source
 * Copyright 2011, Red Hat Middleware LLC, and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.shrinkwrap.resolver.impl.maven.integration;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.logging.Logger;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.codec.binary.Base64;
import org.jboss.shrinkwrap.resolver.api.NoResolvedResultException;
import org.jboss.shrinkwrap.resolver.api.maven.Maven;
import org.jboss.shrinkwrap.resolver.impl.maven.util.TestFileUtil;
import org.jboss.shrinkwrap.resolver.impl.maven.util.ValidationUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mortbay.jetty.Handler;
import org.mortbay.jetty.Server;
import org.mortbay.jetty.handler.AbstractHandler;

/**
 * Tests resolution of the artifacts witch remote repository protected by password
 *
 * @author <a href="mailto:kpiwko@redhat.com">Karel Piwko</a>
 */
public class RepositoryAuthTestCase {
    private static final Logger log = Logger.getLogger(RepositoryAuthTestCase.class.getName());

    private static final int HTTP_TEST_PORT = 12345;

    private Server server;

    /**
     * Cleanup, remove the repositories from previous tests, start the server
     */
    @Before
    public void init() throws Exception {
        TestFileUtil.removeDirectory(new File("target/auth-repository"));
        this.server = startHttpServer();
    }

    @After
    public void after() throws Exception {
        shutdownHttpServer(server);
    }

    /*
     *
     * NOTE:
     *
     * BASIC Authentication is cached in HTTP, with no mechanism to tell the client to release. Therefore, each of these
     * tests may pass if executed in its own JVM, but if both are executed in the same JVM, the second one to run will
     * FAIL.
     *
     * The caching takes place, for instance on Sun JVMs, here:
     *
     * http://www.docjar.com/html/api/sun/net/www/protocol/http/AuthenticationInfo.java.html @ Line 283
     *
     * Because of this caching, LightweightHttpWagonAuthenticator#getPasswordAuthentication is only called once.
     */

    @Test(expected = NoResolvedResultException.class)
    public void searchRemoteWithWrongPassword() throws Exception {
        // Configure with wrong password and expect to fail
        Maven.configureResolver().fromFile("target/settings/profiles/settings-wrongauth.xml")
            .addDependency("org.jboss.shrinkwrap.test:test-deps-i:1.0.0").resolve().withoutTransitivity()
            .asSingle(File.class);
    }

    @Test
    public void searchRemoteWithPassword() throws Exception {
        // Configure with correct password and expect to pass
        final File resolved = Maven.configureResolver().fromFile("target/settings/profiles/settings-auth.xml")
            .addDependency("org.jboss.shrinkwrap.test:test-deps-i:1.0.0").resolve().withoutTransitivity()
            .asSingle(File.class);
        new ValidationUtil("test-deps-i").validate(resolved);
    }

    private Server startHttpServer() {
        // Start an Embedded HTTP Server
        final Handler handler = new AuthStaticFileHandler("shrinkwrap", "shrinkwrap");
        final Server httpServer = new Server(HTTP_TEST_PORT);
        httpServer.setHandler(handler);
        try {
            httpServer.start();
            log.info("HTTP Server Started: " + httpServer);
            return httpServer;
        } catch (final Exception e) {
            throw new RuntimeException("Could not start server");
        }
    }

    private void shutdownHttpServer(final Server httpServer) {
        if (httpServer != null) {
            try {
                httpServer.stop();
            } catch (final Exception e) {
                // Swallow
                log.severe("Could not stop HTTP Server cleanly, " + e.getMessage());
            }
            log.info("HTTP Server Stopped: " + httpServer);
        }
    }

    /**
     * Jetty Handler to serve a static character file from the web root with Authorization check
     */
    private static class AuthStaticFileHandler extends AbstractHandler implements Handler {

        private static final String AUTH_HEADER = "Authorization";

        private final String user;
        private final String password;

        public AuthStaticFileHandler(String user, String password) {
            super();
            this.user = user;
            this.password = password;
        }

        /**
         * @see org.mortbay.jetty.Handler#handle(java.lang.String, javax.servlet.http.HttpServletRequest,
         *      javax.servlet.http.HttpServletResponse, int)
         */
        @Override
        public void handle(final String target, final HttpServletRequest request, final HttpServletResponse response,
            final int dispatch) throws IOException, ServletException {

            log.fine("Authorizing request for artifact");
            final String authHeader = request.getHeader(AUTH_HEADER);
            if (authHeader == null || authHeader.length() == 0) {
                log.warning("Unauthorized access, please provide credentials");
                response.addHeader("WWW-Authenticate", "Basic realm=\"Secure Area\"");
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED,
                    "Unauthorized access, please provide credentials");
                return;
            }

            if (!authorize(request)) {
                log.warning("Invalid credentials");
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Invalid credentials");
                return;
            }

            // Set content type and status before we write anything to the stream
            response.setContentType("text/xml");
            response.setStatus(HttpServletResponse.SC_OK);

            // Obtain the requested file relative to the webroot
            final URL root = getCodebaseLocation();
            final URL fileUrl = new URL(root.toExternalForm() + target);
            URI uri = null;
            try {
                uri = fileUrl.toURI();
            } catch (final URISyntaxException urise) {
                throw new RuntimeException(urise);
            }
            final File file = new File(uri);

            // File not found, so 404
            if (!file.exists()) {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                log.warning("Requested file is not found: " + file);
                return;
            }

            // Write out each line
            final BufferedReader reader = new BufferedReader(new FileReader(file));
            final PrintWriter writer = response.getWriter();
            String line = null;
            while ((line = reader.readLine()) != null) {
                writer.println(line);
            }

            // Close 'er up
            writer.flush();
            reader.close();
            writer.close();
        }

        private URL getCodebaseLocation() throws MalformedURLException {
            return new File("target/repository").toURI().toURL();
        }

        private boolean authorize(HttpServletRequest request) {
            String authHeader = request.getHeader(AUTH_HEADER);

            // Basic auth
            if (authHeader != null && authHeader.startsWith("Basic")) {
                String credentials = user + ":" + password;

                String challenge = "Basic "
                    + new String(Base64.encodeBase64(credentials.getBytes(Charset.defaultCharset())),
                        Charset.defaultCharset());

                return authHeader.equals(challenge);
            }

            return false;
        }
    }

}