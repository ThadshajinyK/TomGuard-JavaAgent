package com.agent;


import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.lang.instrument.Instrumentation;
import java.lang.management.*;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.lang.management.ThreadMXBean;


public class ServerMetricsAgent {
    private static final String TOMCAT_URL = "http://localhost:8080";
    private static volatile boolean agentLoaded = false;
    private static final String  tomcatManagerUrl = "http://localhost:8080/manager";
    private static final String TOMCAT_USERNAME = "admin";
    private static final String TOMCAT_PASSWORD = "fairy26";
    private static final String LOGS_DIR = "C:\\Program Files\\Apache Software Foundation\\Tomcat 10.1\\logs";
    private static final Pattern LOG_PATTERN = Pattern.compile("^(\\d{2}-\\w{3}-\\d{4} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}) (\\w+) \\[(\\w+)\\] (\\S+) (.*)$");

    public static void premain(String agentArgs, Instrumentation inst) {
while (true) {
        //scheduler
        RuntimeMXBean runtimeMxBean = ManagementFactory.getRuntimeMXBean();
        String ipAddress = getIpAddress();
        String hostName = getHostName();
        String availability = "NotFound";
        // Get the start time of the JVM
        long jvmStartTime = runtimeMxBean.getStartTime(); // returns the VM start time in ms

        // Calculate the uptime of the JVM
        long uptimeInMillis = runtimeMxBean.getUptime(); ///returns uptime in ms
        //Initiate response time and request time
        long responseTimeInMillis = 0;
        long requestTimeInMillis = 0;

        // Set the URL of the server endpoint to monitor
        String serverUrl = "http://localhost:8080/";

        // Make a connection to the server endpoint
        long start = System.currentTimeMillis();
        try {
            URL url = new URL(serverUrl);
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("GET");
            int status = con.getResponseCode();
            long end = System.currentTimeMillis();
            responseTimeInMillis = end - start;
            requestTimeInMillis = start - jvmStartTime;
            availability = "online";
            //System.out.println("Uptime: " + uptimeInDays + " days, " + uptimeInHours % 24 + " hours, " + uptimeInMinutes % 60 + " minutes, " + uptimeInSeconds % 60 + " seconds.");
        } catch (Exception e) {
            availability = "offline";
            uptimeInMillis = 0;
        }

        long uptime = uptimeInMillis;
        String osVersion = getOsVersion();
        String osName = getOsName();
        String osArchitecture = getOsArchitecture();
        String jvmVersion = getJvmVersion();
        String noOfSession = String.valueOf(getSessionCount(tomcatManagerUrl, TOMCAT_USERNAME, TOMCAT_PASSWORD));

       Double memoryUsage = getMemoryUsage();
    agentLoaded = true;
    int threadCount=printThreadCount();

        // Save the retrieved metrics to the database or perform any other required actions
        saveServerDetails(hostName, ipAddress, uptime, availability, osName, osVersion, osArchitecture, jvmVersion);
        saveMyMetrics(uptimeInMillis, availability, responseTimeInMillis, requestTimeInMillis,noOfSession, memoryUsage, threadCount);

        // Gather application details
        String[] appNames = new String[0];
        try {
            appNames = getApplicationNames(TOMCAT_URL, TOMCAT_USERNAME, TOMCAT_PASSWORD);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        for (String appName : appNames) {
            String[] appDetails = new String[0];
            try {
                appDetails = getAppDetails(appName, TOMCAT_URL, TOMCAT_USERNAME, TOMCAT_PASSWORD);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            // Print the application details with labels
            String applicationName = appDetails[2];
            String path = appDetails[1];
            String state = appDetails[0];

            saveApplicationDetails(applicationName, path, state);


             //logs retrieving
            try {
                Files.walkFileTree(Paths.get(LOGS_DIR), new LogFileVisitor());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
            try {
                Thread.sleep(1500); // Adjust the delay as needed
                } catch (InterruptedException e) {
               e.printStackTrace();
            }//end of for each loop
         }
        // scheduler end*/
    }

        static class LogFileVisitor extends SimpleFileVisitor<Path> {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (file.toString().endsWith(".log")) {
                    try (Stream<String> lines = Files.lines(file)) {
                        lines.forEach(ServerMetricsAgent::processLogLine);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        }
    private static int getSessionCount(String tomcatManagerUrl, String username, String password) {
        int sessionCount = 1;

        String url = tomcatManagerUrl + "/status?XML=true";
        HttpClient client = HttpClient.newBuilder().build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", getAuthorizationHeader(username, password))
                .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                String xmlResponse = response.body();

                // Parse the XML response to extract the session count
                sessionCount = parseSessionCount(xmlResponse);
            } else {
                System.out.println("Failed to retrieve session count. Response code: " + response.statusCode());
            }
        } catch (Exception e) {
            System.out.println("An error occurred during the HTTP request or XML parsing:");
            e.printStackTrace();
        }

        return sessionCount;
    }

    private static int parseSessionCount(String xmlResponse) {
        int sessionCount = 0;

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(xmlResponse)));

            NodeList sessionsList = doc.getElementsByTagName("sessions");
            if (sessionsList.getLength() > 0) {
                Node sessionsNode = sessionsList.item(0);
                String count = sessionsNode.getTextContent();
                sessionCount = Integer.parseInt(count);
            }
        } catch (Exception e) {
            System.out.println("An error occurred during XML parsing:");
            e.printStackTrace();
        }

        return sessionCount;
    }

    private static String getAuthorizationHeader(String username, String password) {
        String credentials = username + ":" + password;
        String encodedCredentials = java.util.Base64.getEncoder().encodeToString(credentials.getBytes());
        return "Basic " + encodedCredentials;
    }
    private static void processLogLine(String line) {
        Matcher matcher = LOG_PATTERN.matcher(line);
        if (matcher.matches()) {
            String timestamp = matcher.group(1);
            String logLevel = matcher.group(2);
            String threadName = matcher.group(3);
            String loggerName = matcher.group(4);
            String message = matcher.group(5);

            // Perform further processing with the extracted log details
            saveLogDetails(timestamp, logLevel, threadName,loggerName, message);
        }
    }

    public static int printThreadCount() {
        int threadCount=0;
        if (!agentLoaded) {

            return threadCount;
        }

        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        threadCount = threadMXBean.getThreadCount();

        return threadCount;
    }

    /**
     *
     * @param timestamp
     * @param logLevel
     * @param threadName
     * @param loggerName
     * @param message
     * save details into database
     */
    private static void saveLogDetails(String timestamp, String logLevel, String threadName,String loggerName, String message) {

        try {
            // Create the log details payload
            String payload = "{"
                    + "\"timestamp\": \"" + timestamp + "\","
                    + "\"logLevel\": \"" + logLevel + "\","
                    + "\"loggerName\": \"" + loggerName + "\","
                    + "\"threadName\": \"" + threadName + "\","
                    + "\"message\": \"" + message + "\""
                    + "}";

            // Create the URL for your endpoint
            URL url = new URL("http://localhost:9090/api/logs/add");

            // Open a connection to the URL
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();

            // Set the request method to POST
            conn.setRequestMethod("POST");

            // Set the content type header
            conn.setRequestProperty("Content-Type", "application/json");

            // Enable output stream to send the payload
            conn.setDoOutput(true);

            // Get the output stream of the connection
            OutputStream outputStream = conn.getOutputStream();

            // Write the payload to the output stream
            outputStream.write(payload.getBytes());
            outputStream.flush();
            outputStream.close();

            // Get the response code
            int responseCode = conn.getResponseCode();

            // Check if the request was successful (response code 200)
            if (responseCode == HttpURLConnection.HTTP_OK) {
                // Log or handle the successful response
                System.out.println("Log details saved successfully");
            } else {
                // Log or handle the error response
                System.out.println("Failed to save log details. Response code: " + responseCode);
            }

            // Close the connection
            conn.disconnect();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }



        /**
         * method to get IP address
         * @return IP address
         */

        public static String getIpAddress () {
            try {
                InetAddress localHost = InetAddress.getLocalHost();
                return localHost.getHostAddress();
            } catch (UnknownHostException e) {
                e.printStackTrace();
                return null;
            }
        }

        /**
         * method to get host name
         * @return hostname of server
         */
        public static String getHostName () {
            try {
                InetAddress localHost = InetAddress.getLocalHost();
                return localHost.getHostName();
            } catch (UnknownHostException e) {
                e.printStackTrace();
                return null;
            }
        }

    /**
     * method to get OS version
     * @return OS version of the hardware
     */
        public static String getOsVersion () {
            return System.getProperty("os.version");
        }

    /**
     * methid to get OS name
     * @return OS name
     */
    public static String getOsName () {
            return System.getProperty("os.name");
        }

    /**
     * method to get OS architecture
     * @return OS architecture
     */
    public static String getOsArchitecture () {
            return System.getProperty("os.arch");
        }

    /**
     * method to find JVm version
     * @return JVM version
     */
        public static String getJvmVersion () {
            RuntimeMXBean runtimeMxBean = ManagementFactory.getRuntimeMXBean();
            return runtimeMxBean.getVmVersion();
        }

    /**
     * mareen memory usage
     * have to change in proper format
     */
    private static double getMemoryUsage(){
            MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();

            // Retrieve and print the memory usage as a percentage
//            MemoryUsage heapMemoryUsage = memoryMXBean.getHeapMemoryUsage();
//            long heapUsed = heapMemoryUsage.getUsed();
//            long heapCommitted = heapMemoryUsage.getCommitted();
//            double heapPercentage = (double) heapUsed / heapCommitted * 100;
//            System.out.println("Heap Memory Usage: " + String.format("%.2f", heapPercentage) + "%");

            MemoryUsage nonHeapMemoryUsage = memoryMXBean.getNonHeapMemoryUsage();
            long nonHeapUsed = nonHeapMemoryUsage.getUsed();
            long nonHeapCommitted = nonHeapMemoryUsage.getCommitted();
            double nonHeapPercentage = (double) nonHeapUsed / nonHeapCommitted * 100;
            return nonHeapPercentage;

        }

        /**
         * method to get deployed applications string[] array
         * /@param //jmxUrl
         * /@return
         */
        private static String[] getAppDetails(String appName, String tomcatUrl, String username, String password) throws IOException {
            String authEncoded = Base64.getEncoder().encodeToString((username + ":" + password).getBytes());
            URL detailsUrl = new URL(tomcatUrl + "/manager/text/list");
            HttpURLConnection detailsConn = (HttpURLConnection) detailsUrl.openConnection();
            detailsConn.setRequestProperty("Authorization", "Basic " + authEncoded);

            BufferedReader detailsReader = new BufferedReader(new InputStreamReader(detailsConn.getInputStream()));
            String line;
            String[] appDetails = new String[7]; // create a string array to store the application details

            while ((line = detailsReader.readLine()) != null) {
                if (line.startsWith(appName)) {
                    String[] parts = line.split(":");
                    if (parts.length >= 4) {
                        appDetails[0] = parts[1].trim(); // State
                        appDetails[1] = parts[0].trim(); // Path
                        appDetails[2] = parts[3].trim(); // Display Name
                        appDetails[3] = ""; // Document Base
                        appDetails[4] = ""; // WAR File
                        appDetails[5] = ""; // Context XML
                        appDetails[6] = ""; // Deployed At
                    }
                }
            }

            detailsReader.close();
            detailsConn.disconnect();

            return appDetails;
        }

    /**
     *
     * @param tomcatUrl
     * @param username
     * @param password
     * @return string array of application details (each)
     * @throws IOException
     */
    private static String[] getApplicationNames(String tomcatUrl, String username, String password) throws IOException {
        String authEncoded = Base64.getEncoder().encodeToString((username + ":" + password).getBytes());
        URL listUrl = new URL(tomcatUrl + "/manager/text/list");
        HttpURLConnection listConn = (HttpURLConnection) listUrl.openConnection();
        listConn.setRequestProperty("Authorization", "Basic " + authEncoded);

        BufferedReader listReader = new BufferedReader(new InputStreamReader(listConn.getInputStream()));
        String line;
        String[] appNames = new String[0];

        while ((line = listReader.readLine()) != null) {
            // if (line.contains(":running:")) {
            String[] parts = line.split(":");
            if (parts.length >= 2) {
                String appName = parts[0].trim();
                appNames = addToArray(appNames, appName);
            }
            //}
        }

        listReader.close();
        listConn.disconnect();

        return appNames;
    }

    /**
     *
     * @param array
     * @param value
     * @return a array
     */
    private static String[] addToArray(String[] array, String value) {
        String[] newArray = new String[array.length + 1];
        System.arraycopy(array, 0, newArray, 0, array.length);
        newArray[array.length] = value;
        return newArray;
    }

    /**
     * method to save server details to Database
     * @param hostName
     * @param ipAddress
     * @param uptime
     * @param availability
     * @param osName
     * @param osVersion
     * @param osArchitecture
     * @param jvmVersion
     */
        private static void saveServerDetails (String hostName, String ipAddress,long uptime, String
        availability, String osName, String osVersion, String osArchitecture, String jvmVersion){
            try {
                // Create the URL for the API endpoint
                URL url = new URL("http://localhost:9090/api/server/add");

                // Open a connection to the URL
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();

                // Set the request method to POST
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");

                // Create the JSON payload with the metrics data
                String payload = String.format("{\"hostName\": \"%s\", \"ipAddress\": \"%s\", \"uptime\": %d, " +
                                "\"availability\": \"%s\", \"osName\": \"%s\", \"osVersion\": \"%s\", \"osArchitecture\": \"%s\", " +
                                "\"jvmVersion\": \"%s\"}", hostName, ipAddress, uptime, availability, osName, osVersion,
                        osArchitecture, jvmVersion);


                // Enable output and write the payload to the connection
                connection.setDoOutput(true);
                OutputStream outputStream = connection.getOutputStream();
                outputStream.write(payload.getBytes());
                outputStream.flush();

                // Read the response from the connection
                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    // Request successful
                    System.out.println("Server Details sent successfully");
                } else {
                    // Request failed
                    System.out.println("Failed to send Server Details. Response code: " + responseCode);
                }

                // Close the connection and streams
                outputStream.close();
                connection.disconnect();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

    /**
     * method to save server metrics to database
     *
     * @param uptimeInMillis
     * @param availability
     * @param responseTimeInMillis
     * @param requestTimeInMillis  no of session and memory usage not added
     * @param noOfSession
     * @param memoryUsage
     * @param threadCount
     */
        private static void saveMyMetrics (long uptimeInMillis, String availability, long responseTimeInMillis,
                                           long requestTimeInMillis, String noOfSession, Double memoryUsage, int threadCount){
            try {
                // Create the URL for the API endpoint
                URL url = new URL("http://localhost:9090/api/metrics/add");

                // Open a connection to the URL
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();

                // Set the request method to POST
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");

                // Create the JSON payload with the metrics data
                String payload = String.format(
                        "{\"uptimeInMillis\": \"%d\", \"availability\": \"%s\", \"memoryUsage\": \"%.2f\",\"threadCount\": \"%d\", \"noOfSession\": \"%s\",\"responseTimeInMillis\": \"%d\", " +
                                "\"requestTimeInMillis\": \"%d\"}",
                        uptimeInMillis, availability,memoryUsage,threadCount, noOfSession, responseTimeInMillis, requestTimeInMillis);


                // Enable output and write the payload to the connection
                connection.setDoOutput(true);
                OutputStream outputStream = connection.getOutputStream();
                outputStream.write(payload.getBytes());
                outputStream.flush();

                // Read the response from the connection
                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    // Request successful
                    System.out.println("myMetrics sent successfully");
                } else {
                    // Request failed
                    System.out.println("Failed to send myMetrics. Response code: " + responseCode);
                }

                // Close the connection and streams
                outputStream.close();
                connection.disconnect();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

    /**
     * method to save application details into database
     * @param applicationName
     * @param path
     * @param state
     */
    private static void saveApplicationDetails(String applicationName, String path, String state) {
        try {
            // Create the URL for the API endpoint
            URL url = new URL("http://localhost:9090/api/apps/add");

            // Open a connection to the URL
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();

            // Set the request method to POST
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");

            // Create the JSON payload with the metrics data
            String payload = String.format(
                    "{\"applicationName\": \"%s\", \"path\": \"%s\", \"state\": \"%s\"}",
                    applicationName, path, state);


            // Enable output and write the payload to the connection
            connection.setDoOutput(true);
            OutputStream outputStream = connection.getOutputStream();
            outputStream.write(payload.getBytes());
            outputStream.flush();

            // Read the response from the connection
            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                // Request successful
                System.out.println("application details sent successfully");
            } else {
                // Request failed
                System.out.println("Failed to send application details. Response code: " + responseCode);
            }

            // Close the connection and streams
            outputStream.close();
            connection.disconnect();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}



