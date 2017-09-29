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
package au.org.dcw.twitter.ingest

import com.beust.jcommander.JCommander
import com.beust.jcommander.Parameter

import groovy.json.JsonSlurper

import java.nio.file.Files
import java.nio.file.Paths
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

import twitter4j.RateLimitStatus
import twitter4j.RateLimitStatusEvent
import twitter4j.RateLimitStatusListener
import twitter4j.ResponseList
import twitter4j.Status
import twitter4j.Twitter
import twitter4j.TwitterException
import twitter4j.TwitterFactory
import twitter4j.TwitterObjectFactory
import twitter4j.conf.Configuration
import twitter4j.conf.ConfigurationBuilder


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
class RefetchTweets {

    /** Twitter's preferred date time format. */
    static final DateTimeFormatter TWITTER_TIMESTAMP_FORMAT =
        DateTimeFormatter.ofPattern('EEE MMM dd HH:mm:ss ZZZ yyyy', Locale.ENGLISH)

    /**
     * Set to max number of IDs accepted by https://api.twitter.com/1.1/statuses/lookup.json
     *
     * @see Twitter's <a href="https://developer.twitter.com/en/docs/tweets/post-and-engage/api-reference/get-statuses-lookup">GET statuses/lookup</a>
     */
    static final int REFETCH_BATCH_SIZE = 100

    @Parameter(names = ["-i", "--seed-tweets"], description = "File of seed tweets to update")
    String infile = "./tweets.json"

    @Parameter(names = ["-o", "--outfile"], description = "File to update with refetched tweets")
    String outfile = "./updated-tweets.json"

    @Parameter(names = ["-c", "--credentials"],
               description = "Properties file with Twitter OAuth credentials")
    String credentialsFile = "./twitter.properties"

    @Parameter(names = ["-v", "--debug", "--verbose"], description = "Debug mode")
    boolean debug = false

    @Parameter(names = ["-h", "-?", "--help"], description = "Help")
    static boolean help = false

    static void main(String[] args) {
        RefetchTweets theApp = new RefetchTweets()

        // JCommander instance parses args, populates fields of theApp
        JCommander argsParser = new JCommander(theApp, args)
        argsParser.setProgramName("bin/refetch-tweets[.bat]")

        if (help) {
            StringBuilder sb = new StringBuilder()
            argsParser.usage(sb)
            println(sb.toString())
            System.exit(-1)
        }

        theApp.run()
    }

    void run() {

        reportConfiguration()

        // establish resources
        final json = new JsonSlurper()
        final twitterConfig = makeTwitterConfig(credentialsFile, debug)
        final twitter = new TwitterFactory(twitterConfig).getInstance()
        twitter.addRateLimitStatusListener([
            onRateLimitStatus:  { maybeDoze(it.rateLimitStatus) },
            onRateLimitReached: { maybeDoze(it.rateLimitStatus) }
        ] as RateLimitStatusListener)

        // read in tweet IDs
        final List<Long> tweetIDs = []
        try {
            // read in file of tweets and extract their IDs
            new File(infile).eachLine { lineOfJSON ->
                final tweet = json.parseText(lineOfJSON)

                // use id_str if available (null it out if not)
                def id_str = tweet.id_str
                if (id_str == "null") { id_str = null }

                // use id as a backup, but force it to a Long in any case
                tweetIDs << (id_str ? Long.parseLong(id_str) : tweet.id)
            }
        } catch (IOException ioe) {
            ioe.printStackTrace()
            println("Failed to I/O somehow: ${ioe.message}")
            System.exit(-1) // fail fast
        }
        p("Read in ${tweetIDs.size()} tweet IDs\nRefetching tweet data...")
        // refetch tweets, and add a "collected_at" field to their JSON
        final List<String> refetchedTweetsInJSON = []
        tweetIDs.collate(REFETCH_BATCH_SIZE).each { batchOfIDs ->
            final arrayOfIDs = batchOfIDs.toArray(new Long[batchOfIDs.size()])
            ResponseList<Status> response = null
            try {
                response = twitter.lookup(arrayOfIDs)

                final extraJSON = "\"collected_at\":\"${timestamp()}\","
                response.each { tweet ->
                    String rawJSON = TwitterObjectFactory.getRawJSON(tweet)
                    String modifiedJSON = rawJSON[0] + extraJSON + rawJSON[1..-1]
                    refetchedTweetsInJSON << modifiedJSON
                }
            } catch (TwitterException te) {
                te.printStackTrace()
                println("Failed somehow: ${te.message}")
                println("Attempting to continue...")
            }
            p("Refetched ${arrayOfIDs.length} tweets...")
            maybeDoze(response?.rateLimitStatus)
        }
        p("Refetched ${refetchedTweetsInJSON.size()} tweets")
        // Write the modified JSON to the end of outfile
        p("Appending them to ${outfile}...")
        try {
            new File(outfile).withWriterAppend { out ->
                refetchedTweetsInJSON.each { out.println(it) }
            }
        } catch (IOException ioe) {
            ioe.printStackTrace()
            println("Failed to I/O somehow: ${ioe.message}")
        }
        println("Refetching complete at ${timestamp()}")
    }

    void reportConfiguration() {
        println("Refetching tweets\n---")
        println("Seed tweets are in ${infile}")
        println("Refetched tweets will be added to ${outfile}")
        println("Debug mode: ${debug ? "ON" : "OFF"}\n---")
        println("\nStarting at ${timestamp()}")
    }

    /**
     * Print <code>s</code> if {@link #debug} is <code>true</code>.
     * @param s The String to print to stdout.
     */
    void p(final String s) { if (debug) { println(s) } }

    /**
     * Return the current time in a string formatted as Twitter prefers.
     */
    String timestamp() { TWITTER_TIMESTAMP_FORMAT.format(ZonedDateTime.now()) }

    /**
     * If the provided {@link RateLimitStatus} indicates that we are about to exceed the rate
     * limit, in terms of number of calls or time window, then sleep for the rest of the period.
     *
     * @param status The current rate limit status of our calls to Twitter
     */
    void maybeDoze(final RateLimitStatus status) {
        if (! status) { return }

        final secondsUntilReset = status.secondsUntilReset
        final callsRemaining = status.remaining
        if (secondsUntilReset < 10 || callsRemaining < 10) {
            final untilReset = status.secondsUntilReset + 5
            println("Rate limit reached. Waiting ${untilReset} seconds starting at ${new Date()}...");
            try {
                Thread.sleep(untilReset * 1000)
            } catch (InterruptedException e) {
                e.printStackTrace()
            }
            println("Resuming...")
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
        final credentials = loadCredentials(credentialsFile);

        final conf = new ConfigurationBuilder()
        conf.setJSONStoreEnabled(true)
            .setDebugEnabled(debug)
            .setOAuthConsumerKey(credentials.getProperty("oauth.consumerKey"))
            .setOAuthConsumerSecret(credentials.getProperty("oauth.consumerSecret"))
            .setOAuthAccessToken(credentials.getProperty("oauth.accessToken"))
            .setOAuthAccessTokenSecret(credentials.getProperty("oauth.accessTokenSecret"))

        final proxies = loadProxyProperties()
        if (proxies.containsKey("http.proxyHost")) {
            conf.setHttpProxyHost(proxies.getProperty("http.proxyHost"))
                .setHttpProxyPort(Integer.parseInt(proxies.getProperty("http.proxyPort")))
                .setHttpProxyUser(proxies.getProperty("http.proxyUser"))
                .setHttpProxyPassword(proxies.getProperty("http.proxyPassword"))
        }

        conf.build()
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
        final properties = new Properties()
        properties.load(Files.newBufferedReader(Paths.get(credentialsFile)))
        properties
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
        final properties = new Properties()
        final proxyFile = "./proxy.properties"
        if (new File(proxyFile).exists()) {
            def success = true
            try {
                def fileReader = Files.newBufferedReader(Paths.get(proxyFile))
                properties.load(fileReader)
            } catch (IOException e) {
                System.err.println("Attempted and failed to load ${proxyFile}: ${e.message}")
                success = false
            }
            if (success && ! properties.containsKey("http.proxyPassword")) {
                def password = System.console().readPassword("Please type in your proxy password: ")
                properties.setProperty("http.proxyPassword", password)
                properties.setProperty("https.proxyPassword", password)
            }
            properties.each { k, v -> System.setProperty(k, v) }
        }
        properties
    }
}
