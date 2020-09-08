/**
 * Copyright (c) 2010-2020 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.ecotouch.internal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Network communication with Waterkotte EcoTouch heat pumps.
 *
 * The communication protocol was reverse engineered from the Easy-Con Android
 * app. The meaning of the EcoTouch tags was provided by Waterkotte's technical
 * service (by an excerpt of a developer manual).
 *
 * @author Sebastian Held <sebastian.held@gmx.de> - Initial contribution
 * @since 1.5.0
 */

public class EcoTouchConnector {
    private String ip;
    private String username;
    private String password;
    List<String> cookies;
    static Pattern responsePattern = Pattern.compile("#(.+)\\s+S_OK[^0-9-]+([0-9-]+)\\s+([0-9-.]+)");

    private final Logger logger = LoggerFactory.getLogger(EcoTouchConnector.class);

    /**
     * Create a network communication without having a current access token.
     */
    public EcoTouchConnector(String ip, String username, String password) {
        this.ip = ip;
        this.username = username;
        this.password = password;
        this.cookies = null;
    }

    /**
     * Create a network communication with access token. This speeds up
     * retrieving values, because the log in step is omitted.
     */
    public EcoTouchConnector(String ip, String username, String password, List<String> cookies) {
        this.ip = ip;
        this.username = username;
        this.password = password;
        this.cookies = cookies;
    }

    private synchronized void trylogin(boolean force) throws Exception {
        logger.debug("trylogin: in");
        if (force == false && cookies != null) {
            logger.debug("trylogin: out fast");
            return;
        }
        login();
        logger.debug("trylogin: out normal");
    }

    private void login() throws Exception {
        logger.debug("login");
        cookies = null;
        String url = null;
        String line1 = null, line2 = null;
        String cause = null;
        try {
            url = "http://" + ip + "/cgi/login?username=" + URLEncoder.encode(username, "UTF-8") + "&password="
                    + URLEncoder.encode(password, "UTF-8");
            URL loginurl = new URL(url);
            URLConnection connection = loginurl.openConnection();
            cookies = connection.getHeaderFields().get("Set-Cookie");
            BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            line1 = in.readLine();
            line2 = in.readLine();
        } catch (MalformedURLException e) {
            cause = e.toString();
            logger.debug("The URL '{}' is malformed: {}", url, cause);
        } catch (Exception e) {
            cause = e.toString();
            logger.debug("Cannot log into Waterkotte EcoTouch: {}", cause);
        }

        if (line2 != null && line2.trim().equals("#E_USER_DONT_EXIST")) {
            throw new IOException("Username does not exist.");
        }
        if (line2 != null && line2.trim().equals("#E_PASS_DONT_MATCH")) {
            throw new IOException("Password does not match.");
        }
        if (line2 != null && line2.trim().equals("#E_TOO_MANY_USERS")) {
            throw new IOException("Too many users already logged in.");
        }
        if (cookies == null) {
            if (cause == null)
                throw new IOException("Cannot login");
            else
                throw new IOException("Cannot login: " + cause);
        }
    }

    public void logout() {
        if (cookies != null) {
            try {
                URL logouturl = new URL("http://" + ip + "/cgi/logout");
                logouturl.openConnection();
            } catch (Exception e) {
            }
            cookies = null;
        }
    }

    /**
     * Request a value from the heat pump
     * 
     * @param tag
     *            The register to query (e.g. "A1")
     * @return value This value is a 16-bit integer.
     */
    public String getValue_str(String tag) throws Exception {
        trylogin(false);

        // request a value
        String url = "http://" + ip + "/cgi/readTags?n=1&t1=" + tag;
        StringBuilder body = null;
        int loginAttempt = 0;
        while (loginAttempt < 2) {
            BufferedReader reader = null;
            try {
                URLConnection connection = new URL(url).openConnection();
                if (cookies != null) {
                    for (String cookie : cookies) {
                        connection.addRequestProperty("Cookie", cookie.split(";", 2)[0]);
                    }
                }
                InputStream response = connection.getInputStream();
                reader = new BufferedReader(new InputStreamReader(response));
                body = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line + "\n");
                }
                if (body.toString().contains("#" + tag)) {
                    // succeeded
                    break;
                }
                // s.th. went wrong; try to log in again
                throw new Exception();
            } catch (Exception e) {
                logger.debug("getValue(): {}", e.toString());
                if (loginAttempt == 0) {
                    // try to login once
                    trylogin(true);
                }
                loginAttempt++;
            } finally {
                if (reader != null)
                    reader.close();
            }
        }

        if (body == null || !body.toString().contains("#" + tag)) {
            // failed
            logger.debug("Cannot get value for tag '{}' from Waterkotte EcoTouch.", tag);
            throw new Exception("invalid response from EcoTouch");
        }

        // ok, the body now contains s.th. like
        // #A30 S_OK
        // 192 223

        Matcher m = responsePattern.matcher(body.toString());
        boolean b = m.find();
        if (!b) {
            // ill formatted response
            logger.debug("ill formatted response: '{}'", body);
            throw new Exception("invalid response from EcoTouch");
        }

        return m.group(3).trim();
    }

    /**
     * Request a value from the heat pump
     * 
     * @param tags
     *            The registers to query (e.g. "A1")
     * @return values These values are 16-bit integers.
     */
    public Map<String,String> getValues_str(Set<String> tags) throws Exception {
        trylogin(false);

        final Integer maxNum = 100;

        Map<String,String> result = new HashMap<String,String>();
        Integer counter = 1;
        StringBuilder query = new StringBuilder();
        Iterator<String> iter = tags.iterator();
        while (iter.hasNext()) {
            query.append(String.format("t%d=%s&", counter, iter.next()));
            counter++;
            if (counter > maxNum) {
                query.deleteCharAt(query.length()-1); // remove last '&'
                String query_str = String.format("http://%s/cgi/readTags?n=%d&", ip, maxNum) + query;
                result.putAll(getValues_str(query_str));
                counter = 1;
                query = new StringBuilder();
            }
        }

        if (query.length() > 0) {
            query.deleteCharAt(query.length()-1); // remove last '&'
            String query_str = String.format("http://%s/cgi/readTags?n=%d&", ip, counter-1) + query;
            result.putAll(getValues_str(query_str));
        }

        return result;
    }
    
    /**
     * Send a request to the heat pump and evaluate the result
     * 
     * @param url
     *            The URL to connect to
     * @return values A map of tags and their respective integer values
     */
    private Map<String,String> getValues_str(String url) throws Exception {
        logger.debug("getValues({})", url);
        Map<String,String> result = new HashMap<String,String>();
        int loginAttempt = 0;
        while (loginAttempt < 2) {
            BufferedReader reader = null;
            try {
                URLConnection connection = new URL(url).openConnection();
                if (cookies != null) {
                    for (String cookie : cookies) {
                        connection.addRequestProperty("Cookie", cookie.split(";", 2)[0]);
                    }
                }
                InputStream response = connection.getInputStream();
                reader = new BufferedReader(new InputStreamReader(response));
                // the answer is s.th. like
                // #A30 S_OK
                // 192 223
                // [...]
                String line;
                while ((line = reader.readLine()) != null) {
                    String line2 = reader.readLine();
                    if (line2 == null)
                        break;
                    String doubleline = line + "\n" + line2;
                    Matcher m = responsePattern.matcher(doubleline);
                    if (m.find()) {
                        String tag = m.group(1);
                        String value = m.group(3).trim();
                        result.put(tag, value);
                    }
                }
                
                if (result.isEmpty()) {
                    // s.th. went wrong; try to log in again
                    throw new Exception();
                }

                // succeeded
                break;
            } catch (Exception e) {
                logger.debug("getValues(String): {}", e.toString());
                if (loginAttempt == 0) {
                    // try to login once
                    trylogin(true);
                }
                loginAttempt++;
            } finally {
                if (reader != null)
                    reader.close();
            }
        }

        return result;
    }

    /**
     * Set a value
     * 
     * @param tag
     *            The register to set (e.g. "A1")
     * @param value
     *            The 16-bit integer to set the register to
     * @return value This value is a 16-bit integer.
     */
    public int setValue(String tag, int value) throws Exception {
        trylogin(false);

        // set value
        String url = "http://" + ip + "/cgi/writeTags?returnValue=true&n=1&t1=" + tag + "&v1=" + value;
        StringBuilder body = null;
        int loginAttempt = 0;
        while (loginAttempt < 2) {
            BufferedReader reader = null;
            try {
                URLConnection connection = new URL(url).openConnection();
                if (cookies != null) {
                    for (String cookie : cookies) {
                        connection.addRequestProperty("Cookie", cookie.split(";", 2)[0]);
                    }
                }
                InputStream response = connection.getInputStream();
                reader = new BufferedReader(new InputStreamReader(response));
                body = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line + "\n");
                }
                if (body.toString().contains("#" + tag)) {
                    // succeeded
                    break;
                }
                // s.th. went wrong; try to log in
                throw new Exception();
            } catch (Exception e) {
                logger.debug("setValue(): {}", e.toString());
                if (loginAttempt == 0) {
                    // try to login once
                    trylogin(true);
                }
                loginAttempt++;
            } finally {
                if (reader != null)
                    reader.close();
            }
        }

        if (body == null || !body.toString().contains("#" + tag)) {
            // failed
            logger.debug("Cannot get value for tag '{}' from Waterkotte EcoTouch.", tag);
            throw new Exception("invalid response from EcoTouch");
        }

        // ok, the body now contains s.th. like
        // #A30 S_OK
        // 192 223

        Matcher m = responsePattern.matcher(body.toString());
        boolean b = m.find();
        if (!b) {
            // ill formatted response
            logger.debug("ill formatted response: '{}'", body);
            throw new Exception("invalid response from EcoTouch");
        }

        return Integer.parseInt(m.group(3));
    }

    /**
     * Authentication token. Store this and use it, when creating the next
     * instance of this class.
     * 
     * @return cookies: This includes the authentication token retrieved during
     *         log in.
     */
    public List<String> getCookies() {
        return cookies;
    }
}
