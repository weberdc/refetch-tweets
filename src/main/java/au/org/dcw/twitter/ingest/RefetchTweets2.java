/*
 * Copyright 2017 Derek Weber
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package au.org.dcw.twitter.ingest;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.stream.Stream;

import com.google.common.collect.Lists;
import twitter4j.RateLimitStatus;
import twitter4j.RateLimitStatusEvent;
import twitter4j.RateLimitStatusListener;
import twitter4j.ResponseList;
import twitter4j.Status;
import twitter4j.Twitter;
import twitter4j.TwitterException;
import twitter4j.TwitterFactory;
import twitter4j.TwitterObjectFactory;
import twitter4j.conf.Configuration;
import twitter4j.conf.ConfigurationBuilder;


/**
 * This app reads in a file of tweets (one JSON object per line) and then refetches
 * each of the tweets, adds a "collected_at" timestamp to their JSON, and writes
 * them out to the end of another file. In this way, this app can be used to detect
 * when the metadata of the tweets change (e.g., retweet or favourite counts).
 * Twitter credentials are looked for in "./twitter.properties", and proxy info
 * is looked for in "./proxy.properties". Commandline options for the input file,
 * the output file, and the Twitter properties are provided, along with a verbose
 * mode.
 *
 * @author <a href="mailto:weber.dc@gmail.com">Derek Weber</a>
 */
class RefetchTweets2 {

    /** Twitter's preferred date time format. */
    public static final DateTimeFormatter TWITTER_TIMESTAMP_FORMAT =
        DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss ZZZ yyyy", Locale.ENGLISH);

    /**
     * Set to max number of IDs accepted by https://api.twitter.com/1.1/statuses/lookup.json
     *
     * @see Twitter's <a href="https://developer.twitter.com/en/docs/tweets/post-and-engage/api-reference/get-statuses-lookup">GET statuses/lookup</a>
     */
    public static final int REFETCH_BATCH_SIZE = 100;

    @Parameter(names = {"-i", "--seed-tweets"}, description = "File of seed tweets to update")
    public String infile = "./tweets.json";

    @Parameter(names = {"-o", "--outfile"}, description = "File to update with refetched tweets")
    public String outfile = "./updated-tweets.json";

    @Parameter(names = {"-c", "--credentials"},
               description = "Properties file with Twitter OAuth credentials")
    public String credentialsFile = "./twitter.properties";

    @Parameter(names = {"-v", "--debug", "--verbose"}, description = "Debug mode")
    public boolean debug = false;

    @Parameter(names = {"-h", "-?", "--help"}, description = "Help")
    public static boolean help = false;

    public static void main(String[] args) throws IOException {
        RefetchTweets2 theApp = new RefetchTweets2();

        // JCommander instance parses args, populates fields of theApp
        JCommander argsParser = JCommander.newBuilder()
            .addObject(theApp)
            .programName("bin/refetch-tweets[.bat]")
            .build();
        argsParser.parse(args);

        if (help) {
            StringBuilder sb = new StringBuilder();
            argsParser.usage(sb);
            System.out.println(sb.toString());
            System.exit(-1);
        }

        theApp.run();
    }

    public void run() throws IOException {

        reportConfiguration();

        // establish resources
//        final json = new JsonSlurper()
        final Configuration twitterConfig = makeTwitterConfig(credentialsFile, debug);
        final Twitter twitter = new TwitterFactory(twitterConfig).getInstance();
        twitter.addRateLimitStatusListener(new RateLimitStatusListener() {
            @Override
            public void onRateLimitStatus(RateLimitStatusEvent event) {
                maybeDoze(event.getRateLimitStatus());
            }

            @Override
            public void onRateLimitReached(RateLimitStatusEvent event) {
                maybeDoze(event.getRateLimitStatus());
            }
        });

        // read in tweet IDs
        final List<Long> tweetIDs = new ArrayList<Long>();
        try (Stream<String> stream = Files.lines(Paths.get(infile))) {
            // read in file of tweets and extract their IDs
            final ObjectMapper json = new ObjectMapper();

            stream.forEach(line -> {
                try {
                    JsonNode tweetObj = json.readValue(line, JsonNode.class);
                    JsonNode idStrObj = tweetObj.get("id_str");
                    Long id = null;
                    if (idStrObj != null) {
                        id = tweetObj.get("id_str").asLong(-1L);
                    } else {
                        id = tweetObj.get("id").asLong(-2L);
                    }
                    tweetIDs.add(id);
                } catch (IOException ioe) {
                    System.err.println("Failed to parse: " + line.substring(0, 70) + "...");
                }
            });
        } catch (IOException ioe) {
            ioe.printStackTrace();
            System.err.println("Failed to I/O somehow: "  + ioe.getMessage());
            System.exit(-1); // fail fast
        }
        p("Read in " + tweetIDs.size() + " tweet IDs\nRefetching tweet data...");
        // refetch tweets, and add a "collected_at" field to their JSON
        final List<String> refetchedTweetsInJSON = new ArrayList<String>();

        Lists.partition(tweetIDs, REFETCH_BATCH_SIZE).stream().forEach( batchOfIDs -> {
            final long[] arrayOfIDs = new long[batchOfIDs.size()];
            for (int i = 0; i < batchOfIDs.size(); i++) {
                arrayOfIDs[i] = batchOfIDs.get(i);
            }
            ResponseList<Status> response = null;
            try {
                response = twitter.lookup(arrayOfIDs);

                final String extraJSON = "\"collected_at\":\"" + timestamp() + "\",";
                response.stream().forEach ( tweet -> {
                    String rawJSON = TwitterObjectFactory.getRawJSON(tweet);
                    String modifiedJSON = rawJSON.charAt(0) + extraJSON + rawJSON.substring(1);
                    refetchedTweetsInJSON.add(modifiedJSON);
                });
            } catch (TwitterException te) {
                te.printStackTrace();
                System.err.println("Failed somehow: " + te.getMessage());
                System.err.println("Attempting to continue...");
            }
            p("Refetched " + arrayOfIDs.length + " tweets...");
            if (response != null) {
                maybeDoze(response.getRateLimitStatus());
            }
        });
        p("Refetched " + refetchedTweetsInJSON.size() + " tweets");
        // Write the modified JSON to the end of outfile
        p("Appending them to " + outfile + "...");
        try {
            Files.write(
                Paths.get(outfile),
                refetchedTweetsInJSON,
                StandardCharsets.UTF_8,
                StandardOpenOption.APPEND,
                StandardOpenOption.CREATE
            );
        } catch (IOException ioe) {
            ioe.printStackTrace();
            System.err.println("Failed to I/O somehow: " + ioe.getMessage());
        }
        System.out.println("Refetching complete at " + timestamp());
    }

    private void reportConfiguration() {
        System.out.println("Refetching tweets\n---");
        System.out.println("Seed tweets are in " + infile);
        System.out.println("Refetched tweets will be added to " + outfile);
        System.out.println("Debug mode: " + (debug ? "ON" : "OFF") +"\n---");
        System.out.println("\nStarting at " + timestamp());
    }

    /**
     * Print <code>s</code> if {@link #debug} is <code>true</code>.
     * @param s The String to print to stdout.
     */
    private void p(final String s) { if (debug) { System.out.println(s); } }

    /**
     * Return the current time in a string formatted as Twitter prefers.
     */
    private String timestamp() { return TWITTER_TIMESTAMP_FORMAT.format(ZonedDateTime.now()); }

    /**
     * If the provided {@link RateLimitStatus} indicates that we are about to exceed the rate
     * limit, in terms of number of calls or time window, then sleep for the rest of the period.
     *
     * @param status The current rate limit status of our calls to Twitter
     */
    private void maybeDoze(final RateLimitStatus status) {
        if (status == null) { return; }

        final int secondsUntilReset = status.getSecondsUntilReset();
        final int callsRemaining = status.getRemaining();
        if (secondsUntilReset < 10 || callsRemaining < 10) {
            final int untilReset = status.getSecondsUntilReset() + 5;
            System.out.println("Rate limit reached. Waiting ${untilReset} seconds starting at ${new Date()}...");
            try {
                Thread.sleep(untilReset * 1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            System.out.println("Resuming...");
        }
    }

    /**
     * Builds the {@link Configuration} object with which to connect to Twitter, including
     * credentials and proxy information if it's specified.
     *
     * @return a Twitter4j {@link Configuration} object
     * @throws IOException if there's an error loading the application's {@link #credentialsFile}.
     */
    private static Configuration makeTwitterConfig(
        final String credentialsFile,
        final boolean debug
    ) throws IOException {
        // TODO find a better name than credentials, given it might contain proxy info
        final Properties credentials = loadCredentials(credentialsFile);

        final ConfigurationBuilder conf = new ConfigurationBuilder();
        conf.setJSONStoreEnabled(true)
            .setDebugEnabled(debug)
            .setOAuthConsumerKey(credentials.getProperty("oauth.consumerKey"))
            .setOAuthConsumerSecret(credentials.getProperty("oauth.consumerSecret"))
            .setOAuthAccessToken(credentials.getProperty("oauth.accessToken"))
            .setOAuthAccessTokenSecret(credentials.getProperty("oauth.accessTokenSecret"));

        final Properties proxies = loadProxyProperties();
        if (proxies.containsKey("http.proxyHost")) {
            conf.setHttpProxyHost(proxies.getProperty("http.proxyHost"))
                .setHttpProxyPort(Integer.parseInt(proxies.getProperty("http.proxyPort")))
                .setHttpProxyUser(proxies.getProperty("http.proxyUser"))
                .setHttpProxyPassword(proxies.getProperty("http.proxyPassword"));
        }

        return conf.build();
    }

    /**
     * Loads the given {@code credentialsFile} from disk.
     *
     * @param credentialsFile the properties file with the Twitter credentials in it
     * @return A {@link Properties} map with the contents of credentialsFile
     * @throws IOException if there's a problem reading the credentialsFile.
     */
    private static Properties loadCredentials(final String credentialsFile)
        throws IOException {
        final Properties properties = new Properties();
        properties.load(Files.newBufferedReader(Paths.get(credentialsFile)));
        return properties;
    }

    /**
     * Loads proxy information from <code>"./proxy.properties"</code> if it is
     * present. If a proxy host and username are specified by no password, the
     * user is asked to type it in via stdin.
     *
     * @return A {@link Properties} map with proxy credentials.
     * @throws IOException if there's a problem reading the proxy info file.
     */
    private static Properties loadProxyProperties() {
        final Properties properties = new Properties();
        final String proxyFile = "./proxy.properties";
        if (new File(proxyFile).exists()) {
            boolean success = true;
            try (Reader fileReader = Files.newBufferedReader(Paths.get(proxyFile))) {
                properties.load(fileReader);
            } catch (IOException e) {
                System.err.println("Attempted and failed to load " + proxyFile + ": " + e.getMessage());
                success = false;
            }
            if (success && !properties.containsKey("http.proxyPassword")) {
                char[] password = System.console().readPassword("Please type in your proxy password: ");
                properties.setProperty("http.proxyPassword", new String(password));
                properties.setProperty("https.proxyPassword", new String(password));
            }
            properties.forEach((k, v) -> System.setProperty(k.toString(), v.toString()));
        }
        return properties;
    }
}
